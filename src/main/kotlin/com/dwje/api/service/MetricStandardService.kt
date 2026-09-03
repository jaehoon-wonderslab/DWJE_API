package com.dwje.api.service

import com.dwje.api.common.exception.DuplicatedValueException
import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.exception.ResourceNotFoundException
import com.dwje.api.common.response.PageMeta
import com.dwje.api.common.util.MenuId
import com.dwje.api.common.validation.CodeValidator
import com.dwje.api.common.util.PageRequestParam
import com.dwje.api.model.request.MetricStandardRequest
import com.dwje.api.repository.MetricDirection
import com.dwje.api.repository.MetricStandardRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

/**
 * 지표 측정 데이터 관리 서비스 (SY-13)
 *
 * 여기서 등록한 기준 수치(정상/주의/위험)는 대시보드 목표선, 알림 발송 조건, KPI 산출의 기준이 된다.
 * 기준 수치 변경은 이력(ax.tb_met_metric_std_hist)과 감사 로그에 기록된다.
 *
 * 접근 부서 : 전산팀 · 통합관리자
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
            throw DuplicatedValueException("이미 등록된 지표 코드입니다. [$metricCd]", "metricCd")
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

        // 변경된 항목만 이력을 남긴다.
        recordHistory(stdId, "STD", before["normal"], request.normal, principal.userId, principal.deptName)
        recordHistory(stdId, "WARN", before["warn"], request.warn, principal.userId, principal.deptName)
        recordHistory(stdId, "CRIT", before["critical"], request.critical, principal.userId, principal.deptName)
        recordHistory(stdId, "WINDOW", before["window"], request.window, principal.userId, principal.deptName)

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
        metricStandardRepository.insertStandardHistory(
            stdId, "USE", (before["applyDashboard"] as? Boolean)?.toString(), applied.toString(),
            principal.userId, principal.deptName
        )

        auditLogService.record(
            logType = "AUTO_GEN",
            menuId = MenuId.SYS_METRIC,
            targetDesc = "지표 ${if (applied) "적용" else "해제"} [${before["name"]}]",
            remark = "stdId=$stdId"
        )

        return mapOf("success" to true, "applied" to applied)
    }

    /** 기준 수치 변경 이력 (No.220) */
    @Transactional(readOnly = true)
    fun getStandardHistory(stdId: Int?, page: Int?, size: Int?): Pair<List<Map<String, Any?>>, PageMeta> {
        authorizationService.requireMenu(MenuId.SYS_METRIC)
        val paging = PageRequestParam.of(page, size)

        val total = metricStandardRepository.countStandardHistory(stdId)
        val rows = metricStandardRepository.findStandardHistory(stdId, paging.limit, paging.offset)

        return rows to PageMeta.of(paging.page, paging.size, total)
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
     * 변경 전후가 다를 때만 이력을 남긴다.
     */
    private fun recordHistory(
        stdId: Int,
        fieldCd: String,
        before: Any?,
        after: Any?,
        actor: String,
        actorDeptNm: String?
    ) {
        if (after == null) return
        val beforeStr = before?.toString()
        val afterStr = after.toString()
        if (beforeStr == afterStr) return

        metricStandardRepository.insertStandardHistory(stdId, fieldCd, beforeStr, afterStr, actor, actorDeptNm)
    }

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
}
