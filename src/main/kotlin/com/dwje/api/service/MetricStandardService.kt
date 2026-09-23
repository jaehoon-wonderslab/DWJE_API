package com.dwje.api.service

import com.dwje.api.common.exception.ConflictingValueException
import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.exception.ResourceNotFoundException
import com.dwje.api.common.response.PageMeta
import com.dwje.api.common.util.DateUtils
import com.dwje.api.common.util.MenuId
import com.dwje.api.common.validation.CodeValidator
import com.dwje.api.common.util.PageRequestParam
import com.dwje.api.model.request.MetricCollectRequest
import com.dwje.api.model.request.MetricSourceSaveRequest
import com.dwje.api.model.request.MetricStandardRequest
import com.dwje.api.repository.MetricDirection
import com.dwje.api.repository.MetricStandardRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.ZoneId
import java.math.BigDecimal

/**
 * 지표 측정 데이터 관리 서비스 (SY-13)
 *
 * 여기서 등록한 기준 수치(정상/주의/위험)는 대시보드 목표선, 알림 발송 조건, KPI 산출의 기준이 된다.
 * 기준 수치 변경은 감사 로그(ax.tb_log_audit)에 남는다.
 *
 * ## 변경 이력 화면(No.220)은 없다
 * 근거 표 `ax.tb_met_metric_std_hist` 를 V31(2026-09-15)이 지웠다. 항목별 전/후를 남길 자리가
 * 사라져 이력 조회·기록을 함께 뺐다. 되살리려면 표부터 만들어야 한다(DB 담당).
 *
 * 접근 : 화면 권한 `sys-metric`(ax.tb_sys_dept_menu_perm) · 값 마스킹 : 없음 (웹 메뉴 없음 — 통합관리자 전용)
 */
@Service
class MetricStandardService(
    private val metricStandardRepository: MetricStandardRepository,
    private val authorizationService: AuthorizationService,
    private val auditLogService: AuditLogService,
    private val codeValidator: CodeValidator
) {

    companion object {
        /** 집계 구간 공통코드 그룹 */
        private const val MET_WINDOW_GROUP = "MET_WINDOW"
    }

    private val log = LoggerFactory.getLogger(javaClass)

    /** 지표 기준 요약 (No.215) */
    @Transactional(readOnly = true)
    fun getSummary(): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.SYS_METRIC)

        val summary = metricStandardRepository.findSummary().toMutableMap()
        val judgeCounts = metricStandardRepository.findRecentJudgeCounts(24)

        summary["criticalCnt"] = judgeCounts["CRIT"] ?: 0L
        summary["warnCnt"] = judgeCounts["WARN"] ?: 0L
        summary["normalCnt"] = judgeCounts["NORMAL"] ?: 0L

        return summary.toMap()
    }

    /** 지표 기준 목록 조회 (No.216) */
    @Transactional(readOnly = true)
    fun getStandards(
        category: String?,
        applied: Boolean?,
        level: String?,
        page: Int?,
        size: Int?
    ): Pair<List<Map<String, Any?>>, PageMeta> {
        authorizationService.requireMenu(MenuId.SYS_METRIC)
        val paging = PageRequestParam.of(page, size)

        val total = metricStandardRepository.countStandards(category, applied, level)
        val rows = metricStandardRepository.findStandards(category, applied, level, paging.limit, paging.offset)

        return rows to PageMeta.of(paging.page, paging.size, total)
    }

    /** 지표 기준 등록 (No.217) */
    @Transactional
    fun createStandard(request: MetricStandardRequest): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.SYS_METRIC)

        val name = request.name?.trim()
            ?: throw InvalidParameterException("지표명을 입력해 주세요.", "name")
        val metricCd = request.metricCd?.trim() ?: generateMetricCode(name)

        if (metricStandardRepository.existsMetricCode(metricCd, null)) {
            // 다른 지표가 이미 쓰는 코드다 — 같은 회차에 만든 나머지 3개 화면과 같이 409 로 맞춘다
            throw ConflictingValueException("이미 등록된 지표 코드입니다. [$metricCd]", "metricCd")
        }

        requireConsistentDirection(
            request.direction,
            request.warn ?: BigDecimal.ZERO,
            request.critical ?: BigDecimal.ZERO
        )

        val stdId = metricStandardRepository.insertStandard(
            metricCd = metricCd,
            metricNm = name,
            catCd = request.category ?: "PROD",
            unitCd = request.unit ?: "PCT",
            stdVal = request.normal ?: BigDecimal.ZERO,
            warnVal = request.warn ?: BigDecimal.ZERO,
            critVal = request.critical ?: BigDecimal.ZERO,
            windowCd = request.window ?: "DAY_CLOSE",
            calcBase = request.basis ?: "-",
            applied = request.applied ?: true,
            actor = principal.userId
        )

        auditLogService.record(
            logType = "AUTO_GEN",
            menuId = MenuId.SYS_METRIC,
            targetDesc = "지표 기준 등록 [$name]",
            remark = "정상=${request.normal}, 주의=${request.warn}, 위험=${request.critical}"
        )

        log.info("지표 기준 등록 : stdId={} metricCd={}", stdId, metricCd)
        return mapOf("stdId" to stdId, "metricCd" to metricCd)
    }

    /**
     * 지표 기준 수치 수정 (No.218)
     *
     * 항목별 변경 전후를 이력 테이블에 기록한다.
     */
    @Transactional
    fun updateStandard(stdId: Int, request: MetricStandardRequest): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.SYS_METRIC)

        val before = metricStandardRepository.findStandard(stdId)
            ?: throw ResourceNotFoundException("지표 기준을 찾을 수 없습니다. [stdId=$stdId]")

        // 이 API 가 바꿀 수 있는 항목이 하나도 안 담긴 요청은 성공으로 처리하지 않는다.
        // Jackson 이 모르는 필드를 무시하므로, {field:'normal', value:77} 처럼 모양이 다른 본문은
        // 전 항목 null 로 역직렬화된다. 그대로 두면 200 "수정되었습니다" 가 나가는데
        // 실제로는 아무것도 바뀌지 않아, 화면이 성공을 알리고도 값이 그대로인 상태가 된다.
        if (request.normal == null && request.warn == null && request.critical == null &&
            request.window == null && request.basis == null
        ) {
            val hint = if (request.direction != null) {
                " direction 은 저장 항목이 아니라 warn/critical 관계에서 계산되므로, 방향을 바꾸려면 두 임계값을 보내 주세요."
            } else {
                ""
            }
            throw InvalidParameterException(
                "수정할 항목이 없습니다. normal · warn · critical · window · basis 중 하나 이상을 담아 주세요.$hint",
                "normal"
            )
        }

        codeValidator.require(MET_WINDOW_GROUP, request.window, "window", "집계 구간")

        // direction 은 산출값이다. 수정 결과(요청값 ∪ 기존값)의 warn/crit 관계와 어긋나면
        // 화면이 기대한 방향과 반대로 판정되므로 저장 전에 막는다.
        requireConsistentDirection(
            request.direction,
            request.warn ?: (before["warn"] as? Number)?.let { BigDecimal.valueOf(it.toDouble()) } ?: BigDecimal.ZERO,
            request.critical ?: (before["critical"] as? Number)?.let { BigDecimal.valueOf(it.toDouble()) } ?: BigDecimal.ZERO
        )

        metricStandardRepository.updateStandardValues(
            metricId = stdId,
            stdVal = request.normal,
            warnVal = request.warn,
            critVal = request.critical,
            windowCd = request.window,
            calcBase = request.basis,
            actor = principal.userId
        )

        auditLogService.record(
            logType = "AUTO_GEN",
            menuId = MenuId.SYS_METRIC,
            targetDesc = "지표 기준 수치 수정 [${before["name"]}]",
            remark = "정상 ${before["normal"]}→${request.normal ?: before["normal"]}, " +
                "주의 ${before["warn"]}→${request.warn ?: before["warn"]}, " +
                "위험 ${before["critical"]}→${request.critical ?: before["critical"]}"
        )

        val after = metricStandardRepository.findStandard(stdId)
        val level = judgeLevel(after, metricStandardRepository.findLatestValue(stdId))
        return mapOf("success" to true, "level" to level, "direction" to after?.get("direction"))
    }

    /** 지표 적용/해제 (No.219) */
    @Transactional
    fun changeStandardApplied(stdId: Int, applied: Boolean): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.SYS_METRIC)

        val before = metricStandardRepository.findStandard(stdId)
            ?: throw ResourceNotFoundException("지표 기준을 찾을 수 없습니다. [stdId=$stdId]")

        metricStandardRepository.updateStandardApplied(stdId, applied, principal.userId)

        auditLogService.record(
            logType = "AUTO_GEN",
            menuId = MenuId.SYS_METRIC,
            targetDesc = "지표 ${if (applied) "적용" else "해제"} [${before["name"]}]",
            remark = "stdId=$stdId"
        )

        return mapOf("success" to true, "applied" to applied)
    }


    /** 기준 수치 사용처 조회 (No.221) */
    @Transactional(readOnly = true)
    fun getStandardUsage(stdId: Int): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.SYS_METRIC)

        metricStandardRepository.findStandard(stdId)
            ?: throw ResourceNotFoundException("지표 기준을 찾을 수 없습니다. [stdId=$stdId]")

        return metricStandardRepository.findStandardUsage(stdId)
    }

    // ---------------------------------------------------------------------------------
    // 내부 보조
    // ---------------------------------------------------------------------------------


    /**
     * 지표명으로 코드를 생성한다. (영문·숫자만 남기고 대문자화)
     */
    /**
     * 요청의 `direction` 이 warn/crit 관계와 맞는지 확인한다. (null 이면 검사하지 않는다)
     *
     * `direction` 은 저장 컬럼이 아니라 [MetricDirection.of] 로 산출되는 값이다.
     * 화면이 `high` 를 보냈는데 임계값 순서가 `low` 를 뜻하면, 저장은 되지만 판정이 반대로 돌아간다.
     * 그런 요청은 200 을 주지 않고 어느 값을 어떻게 고쳐야 하는지 알린다.
     */
    private fun requireConsistentDirection(direction: String?, warn: BigDecimal, crit: BigDecimal) {
        if (direction == null) return
        val normalized = direction.trim().lowercase()
        if (normalized !in MetricDirection.VALUES) {
            throw InvalidParameterException(
                "direction 은 ${MetricDirection.VALUES.joinToString(" | ")} 중 하나여야 합니다. [$direction]",
                "direction"
            )
        }
        // warn == crit 이면 방향을 판별할 수 없다 — 요청 값을 거부할 근거도 없으니 통과시킨다.
        val derived = MetricDirection.of(warn, crit) ?: return
        if (normalized != derived) {
            val guide = if (normalized == MetricDirection.HIGH) {
                "클수록 좋은 지표(high)는 critical 이 warn 보다 작아야 합니다"
            } else {
                "작을수록 좋은 지표(low)는 critical 이 warn 보다 커야 합니다"
            }
            throw InvalidParameterException(
                "direction=$normalized 과 임계값 관계가 어긋납니다 (warn=${warn.stripTrailingZeros().toPlainString()}, " +
                    "critical=${crit.stripTrailingZeros().toPlainString()} → $derived). $guide.",
                "direction"
            )
        }
    }

    private fun generateMetricCode(name: String): String {
        val normalized = name.filter { it.isLetterOrDigit() }.uppercase().take(20)
        return if (normalized.isBlank()) "METRIC_${System.currentTimeMillis() % 100000}" else normalized
    }

    /**
     * 수정된 기준으로 최신 측정값의 판정 등급을 산출한다. (PUT 응답 `level`)
     *
     * 예전에는 값을 보지 않고 항상 NORMAL 을 돌려주는 스텁이었다 — 화면이 "저장 시 판정 재계산" 을
     * 기대하는 자리라 실제로 계산한다. 방향은 [MetricDirection] 규칙을 그대로 쓴다.
     *
     * | direction | CRIT            | WARN            |
     * |-----------|-----------------|-----------------|
     * | low       | value >= crit   | value >= warn   |
     * | high      | value <= crit   | value <= warn   |
     *
     * 측정 이력이 없거나(신규 지표) 방향을 판별할 수 없으면(warn == crit) 목록 API 와 같은 규칙으로 NORMAL.
     * 목록의 `level` 은 수집기가 기록한 `judge_cd` 를 쓰므로, 다음 수집 전까지는 두 값이 다를 수 있다.
     */
    private fun judgeLevel(standard: Map<String, Any?>?, currentValue: BigDecimal?): String {
        if (standard == null) return "UNKNOWN"
        if (currentValue == null) return "NORMAL"

        val warn = (standard["warn"] as? Number)?.let { BigDecimal.valueOf(it.toDouble()) } ?: return "NORMAL"
        val crit = (standard["critical"] as? Number)?.let { BigDecimal.valueOf(it.toDouble()) } ?: return "NORMAL"

        return when (MetricDirection.of(warn, crit)) {
            MetricDirection.LOW -> when {
                currentValue >= crit -> "CRIT"
                currentValue >= warn -> "WARN"
                else -> "NORMAL"
            }
            MetricDirection.HIGH -> when {
                currentValue <= crit -> "CRIT"
                currentValue <= warn -> "WARN"
                else -> "NORMAL"
            }
            else -> "NORMAL"
        }
    }

    // ---------------------------------------------------------------------------------
    // 수집 정의 · 산출 근거 · 측정값 (2026-09-22 신규)
    // ---------------------------------------------------------------------------------

    /**
     * 수집 정의 조회 — 이 지표의 값을 무엇이 채우는가.
     *
     * 정의가 없으면 `collect` 가 null 이다. 그 상태가 곧 `tb_met_metric_value` 가 비는 이유다.
     */
    @Transactional(readOnly = true)
    fun getCollect(stdId: Int): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.SYS_METRIC)
        val standard = metricStandardRepository.findStandard(stdId)
            ?: throw ResourceNotFoundException("지표 기준을 찾을 수 없습니다. [stdId=$stdId]")

        return mapOf(
            "stdId" to stdId,
            "metricCd" to standard["metricCd"],
            "name" to standard["name"],
            "collect" to metricStandardRepository.findCollect(stdId),
            "sources" to metricStandardRepository.findSources(stdId)
        )
    }

    /**
     * 수집 정의 저장 (등록·수정 겸용 — 지표당 한 건이다).
     *
     * **저장한다고 값이 바로 들어오지는 않는다.** 실제 수집은 별도 프로세스가 주기로 돌며,
     * 이 API 는 그 프로세스가 읽을 정의를 관리한다. 화면이 "적용" 을 눌러도 `last_value_at` 이
     * 바로 차지 않는 이유를 응답의 `note` 로 함께 알린다.
     */
    @Transactional
    fun saveCollect(stdId: Int, request: MetricCollectRequest): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.SYS_METRIC)
        val standard = metricStandardRepository.findStandard(stdId)
            ?: throw ResourceNotFoundException("지표 기준을 찾을 수 없습니다. [stdId=$stdId]")

        codeValidator.require("MET_COLLECT_MODE", request.collectMode, "collectMode", "수집 방식")
        codeValidator.require("ALM_SCOPE_DIM", request.dimCd, "dimCd", "수집 차원")

        if (request.intervalSec <= 0 || request.lookbackMin <= 0) {
            throw InvalidParameterException(
                "수집 주기와 조회 구간은 1 이상이어야 합니다. [intervalSec=${request.intervalSec}, lookbackMin=${request.lookbackMin}]",
                "intervalSec"
            )
        }
        // SQL 방식인데 질의가 없으면 수집기가 돌 때 조용히 아무것도 하지 않는다. 저장 단계에서 막는다.
        val sqlText = request.sqlText?.trim()?.takeIf { it.isNotBlank() }
        if (request.collectMode == "SQL" && sqlText == null) {
            throw InvalidParameterException("수집 방식이 SQL 이면 실행할 질의(sqlText)가 있어야 합니다.", "sqlText")
        }

        metricStandardRepository.upsertCollect(
            metricId = stdId,
            collectMode = request.collectMode,
            collectorCd = request.collectorCd?.trim()?.takeIf { it.isNotBlank() },
            dimCd = request.dimCd,
            intervalSec = request.intervalSec,
            lookbackMin = request.lookbackMin,
            sqlText = sqlText,
            applied = request.applied,
            actor = principal.userId
        )

        auditLogService.record(
            logType = "AUTO_GEN",
            menuId = MenuId.SYS_METRIC,
            targetDesc = "지표 수집 정의 저장 [${standard["name"]}]",
            remark = "방식=${request.collectMode}, 주기=${request.intervalSec}s, 적용=${request.applied}"
        )

        return mapOf(
            "success" to true,
            "collect" to metricStandardRepository.findCollect(stdId),
            "note" to "정의만 저장했습니다. 실제 수집은 수집 프로세스가 주기로 수행합니다."
        )
    }

    /** 산출 근거 저장 — 넘어온 목록으로 통째로 바꾼다(빈 목록이면 전부 해제) */
    @Transactional
    fun saveSources(stdId: Int, request: MetricSourceSaveRequest): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.SYS_METRIC)
        val standard = metricStandardRepository.findStandard(stdId)
            ?: throw ResourceNotFoundException("지표 기준을 찾을 수 없습니다. [stdId=$stdId]")

        val items = request.items.map { item ->
            val schema = item.schema.trim().takeIf { it.isNotBlank() }
                ?: throw InvalidParameterException("스키마를 입력해 주세요.", "schema")
            val table = item.table.trim().takeIf { it.isNotBlank() }
                ?: throw InvalidParameterException("테이블을 입력해 주세요.", "table")
            MetricStandardRepository.SourceWrite(
                schema = schema,
                table = table,
                column = item.column?.trim()?.takeIf { it.isNotBlank() },
                aggExpr = item.aggExpr?.trim()?.takeIf { it.isNotBlank() },
                remark = item.remark?.trim()?.takeIf { it.isNotBlank() }
            )
        }

        val saved = metricStandardRepository.replaceSources(stdId, items)

        auditLogService.record(
            logType = "AUTO_GEN",
            menuId = MenuId.SYS_METRIC,
            targetDesc = "지표 산출 근거 저장 [${standard["name"]}]",
            remark = "근거 ${saved}건"
        )

        return mapOf("success" to true, "savedCnt" to saved, "sources" to metricStandardRepository.findSources(stdId))
    }

    /** 측정값 목록 — 지표·기간·판정 등급으로 거른다 */
    @Transactional(readOnly = true)
    fun getValues(
        stdId: Int?,
        from: String?,
        to: String?,
        judge: String?,
        page: Int?,
        size: Int?
    ): Pair<List<Map<String, Any?>>, PageMeta> {
        authorizationService.requireMenu(MenuId.SYS_METRIC)
        val paging = PageRequestParam.of(page, size)

        val fromAt = from?.takeIf { it.isNotBlank() }
            ?.let { DateUtils.parseDate(it, "from").atStartOfDay().atZone(ZoneId.systemDefault()).toOffsetDateTime() }
        // to 는 그 날을 포함해야 하므로 다음 날 0시 미만으로 본다
        val toAt = to?.takeIf { it.isNotBlank() }
            ?.let { DateUtils.parseDate(it, "to").plusDays(1).atStartOfDay().atZone(ZoneId.systemDefault()).toOffsetDateTime() }

        val total = metricStandardRepository.countValues(stdId, fromAt, toAt, judge)
        val rows = metricStandardRepository.findValues(stdId, fromAt, toAt, judge, paging.limit, paging.offset)

        return rows to PageMeta.of(paging.page, paging.size, total)
    }

}
