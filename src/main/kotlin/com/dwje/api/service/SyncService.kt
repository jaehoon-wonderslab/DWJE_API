package com.dwje.api.service

import com.dwje.api.common.util.AuditType
import com.dwje.api.common.exception.BusinessRuleException
import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.exception.ResourceNotFoundException
import com.dwje.api.common.response.PageMeta
import com.dwje.api.common.util.DateUtils
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.common.util.MenuId
import com.dwje.api.common.validation.CodeValidator
import com.dwje.api.common.util.PageRequestParam
import com.dwje.api.config.AppProperties
import com.dwje.api.model.request.SchemaDriftResolveRequest
import com.dwje.api.model.request.SyncManualRequest
import com.dwje.api.repository.SyncRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 데이터 연동 이력 서비스 (SY-15)
 *
 * MES(MSSQL) → PostgreSQL 이관 작업의 진행 상황과 오류를 관리한다.
 * 재실행·수동 예약은 감사 로그에 기록된다. (공통 규약 6 — 연동)
 *
 * 스키마 드리프트(No.234~236)는 이관 엔진(MES_migration_engine)이 배치마다
 * ax.tb_sync_schema_drift 에 직접 기록한다. 여기서는 조회와 수동 해소만 담당하며
 * 판정 로직에는 관여하지 않는다.
 *
 * 접근 : 화면 권한 `sys-sync`(ax.tb_sys_dept_menu_perm) · 값 마스킹 : 없음
 */
@Service
class SyncService(
    private val syncRepository: SyncRepository,
    private val authorizationService: AuthorizationService,
    private val auditLogService: AuditLogService,
    private val agentRunRecorder: AgentRunRecorder,
    private val codeValidator: CodeValidator,
    private val appProperties: AppProperties = AppProperties()
) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private val DATETIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        private val ALLOWED_KINDS = mapOf("full" to "FULL", "incremental" to "INCR", "incr" to "INCR")
        private val ALLOWED_DRIFT_SIDES = setOf("SOURCE", "TARGET")
        private val ALLOWED_DRIFT_KINDS = setOf("NEW", "MISSING")
        /** 재실행할 수 있는 작업 상태 (SYN-02) */
        val RETRYABLE_STATES = setOf("FAIL", "ABORTED")
    }

    /** 연동 요약 (No.226) */
    @Transactional(readOnly = true)
    fun getSummary(date: String?): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.SYS_SYNC)
        val target = DateUtils.parseDate(date, "date", LocalDate.now())
        // 기존 필드는 기준일 하루 기준, 상태 줄 필드(SYN-03)는 기준일과 무관하게 지금 기준이다
        val stats = syncRepository.findHealthStats(appProperties.sync.stalePendingMin)
        val (healthState, healthReason) = SyncHealth.evaluate(stats, appProperties.sync)
        return syncRepository.findSummary(target) + mapOf(
            "healthState" to healthState,
            "healthReason" to healthReason,
            "lastRun" to stats.lastRun,
            "lastSuccessAt" to stats.lastSuccessAt,
            "staleMin" to stats.staleMin,
            "consecutiveFailRuns" to SyncHealth.consecutiveFailRuns(stats.recentRunStates),
            "todayFailRunCnt" to stats.todayFailRunCnt,
            "openFailJobCnt" to stats.openFailJobCnt,
            "oldestOpenFailAt" to stats.oldestOpenFailAt,
            "stalePendingCnt" to stats.stalePendingCnt,
            "alert" to syncRepository.findSyncAlertStats()
        )
    }

    /**
     * 이관 실행 이력 (SY-15 — 엔진 1회 실행 = 1행)
     *
     * 테이블별 상세는 [getJobs] 이고, 같은 실행에 속한 작업은 `runId` 로 묶인다.
     * 원본 접속 실패처럼 테이블 작업까지 가지 못한 실행도 여기에는 남는다.
     *
     * @param state SYNC_RUN_STATE — RUNNING/DONE/PARTIAL/FAIL/PREFLIGHT_FAIL/NO_WORK/SKIPPED/ABORTED
     * @param mode  SYNC_RUN_MODE — SCHEDULED/MANUAL/QUEUE/RETRY
     */
    @Transactional(readOnly = true)
    fun getRuns(
        from: String?,
        to: String?,
        state: String?,
        mode: String?,
        page: Int?,
        size: Int?,
        source: String? = null
    ): Pair<List<Map<String, Any?>>, PageMeta> {
        authorizationService.requireMenu(MenuId.SYS_SYNC)

        // 코드 집합 밖의 값을 그냥 넘기면 조용히 0건이 나와 "실행 이력이 없음" 과 구분되지 않는다.
        codeValidator.require("SYNC_RUN_STATE", state, "state", "실행 결과")
        codeValidator.require("SYNC_RUN_MODE", mode, "mode", "실행 모드")
        val src = source?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        if (src != null && src !in setOf("MES", "GROUPWARE")) {
            throw InvalidParameterException("실행 구분은 MES 또는 GROUPWARE 만 허용합니다. [$source]", "source")
        }

        val (fromDate, toDate) = DateUtils.periodOf(from, to, 7)
        val paging = PageRequestParam.of(page, size)

        val total = syncRepository.countRuns(fromDate, toDate, state, mode, src)
        val rows = syncRepository.findRuns(fromDate, toDate, state, mode, paging.limit, paging.offset, src)

        return rows to PageMeta.of(paging.page, paging.size, total)
    }

    /** 데이터 연동 화면 조회 권한 — 전체 내려받기가 입력 검사 전에 부른다 */
    fun requireViewer() {
        authorizationService.requireMenu(MenuId.SYS_SYNC)
    }

    /** 이관 작업 이력 조회 (No.227) */
    @Transactional(readOnly = true)
    fun getJobs(
        from: String?,
        to: String?,
        srcTable: String?,
        state: String?,
        page: Int?,
        size: Int?,
        runId: String? = null,
        includePending: Boolean = true
    ): Pair<List<Map<String, Any?>>, PageMeta> {
        authorizationService.requireMenu(MenuId.SYS_SYNC)
        codeValidator.require("SYNC_STATE", state, "state", "작업 상태")

        val (fromDate, toDate) = DateUtils.periodOf(from, to, 7)
        val paging = PageRequestParam.of(page, size)
        val run = runId?.trim()?.takeIf { it.isNotEmpty() }

        val total = syncRepository.countJobs(fromDate, toDate, srcTable, state, run, includePending)
        val rows = syncRepository.findJobs(fromDate, toDate, srcTable, state, paging.limit, paging.offset, run, includePending)

        return rows to PageMeta.of(paging.page, paging.size, total)
    }

    /** 이관 작업 상세 (No.228) */
    @Transactional(readOnly = true)
    fun getJob(jobId: String): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.SYS_SYNC)

        val found = syncRepository.findJob(jobId)
            ?: throw ResourceNotFoundException("이관 작업을 찾을 수 없습니다. [jobId=$jobId]")

        // 재실행 연결과 판정 (SYN-02) — 화면은 retryable 로 버튼을, supersededBy 로 「이후 정상 완료」 안내를 그린다
        @Suppress("UNCHECKED_CAST")
        val job = found["job"] as Map<String, Any?>
        val state = job["state"] as String?
        val retriedBy = syncRepository.findRetriedBy(jobId)
        val (retryable, reason) = retryVerdict(state, found["mapUseFlg"] as String?, retriedBy)
        val supersededBy = if (state in RETRYABLE_STATES) syncRepository.findSupersededBy(jobId) else null
        val enriched = job + mapOf(
            "retryable" to retryable, "retryBlockedReason" to reason,
            "retriedBy" to retriedBy, "supersededBy" to supersededBy
        )
        return (found - "mapUseFlg") + mapOf(
            "job" to enriched, "errorTotal" to syncRepository.countJobErrors(jobId), "errors" to syncRepository.findJobErrors(jobId, 500)
        )
    }

    /**
     * 이관 작업 재실행 (No.229 — 감사 로그 기록)
     *
     * 실행은 이관 엔진이 한다. 여기서는 PENDING 작업을 만들어 큐에 넣기만 하고,
     * 엔진이 다음 큐 점검(기본 60초 주기)에서 집어가 실행한다.
     */
    @Transactional
    fun retryJob(jobId: String): Map<String, Any?> {
        val principal = requireSyncWrite()

        // 원 작업을 잠그고 판정한다 — 같은 재실행이 동시에 두 번 와도 예약은 하나만 생긴다 (SYN-02)
        val t = syncRepository.lockJobForRetry(jobId)
            ?: throw ResourceNotFoundException("이관 작업을 찾을 수 없습니다. [jobId=$jobId]")
        val context = "원 상태=${t.state}, 매핑=${t.mapId}, 구분=${t.kind ?: "-"}"
        fun reject(message: String): Nothing {
            auditLogService.record(
                logType = AuditType.CONFIG_CHANGE, menuId = MenuId.SYS_SYNC,
                targetDesc = "이관 작업 재실행 [$jobId]", resultCd = "REJECT", remark = "$message; $context"
            )
            throw BusinessRuleException(message)
        }
        if (t.state !in RETRYABLE_STATES) reject("실패하거나 중단된 작업만 재실행할 수 있습니다. [state=${t.state}]")
        syncRepository.findActiveRetry(jobId)?.let { reject("이미 재실행이 예약되어 있습니다. [newJobId=$it]") }
        if (t.mapUseFlg != "Y") reject("사용 중지된 이관 정의라 재실행할 수 없습니다. [map_id=${t.mapId}]")

        val newJobId = syncRepository.insertRetryJob(jobId, principal.userId)
            ?: throw ResourceNotFoundException("재실행할 매핑 정보를 찾을 수 없습니다. [jobId=$jobId]")
        // 이후 같은 매핑이 이미 정상 완료됐으면 알린다 — 재실행은 그래도 등록한다(데이터 확인은 사람이 한다)
        val supersededBy = syncRepository.findSupersededBy(jobId)

        auditLogService.record(
            logType = AuditType.CONFIG_CHANGE,
            menuId = MenuId.SYS_SYNC,
            targetDesc = "이관 작업 재실행 [$jobId]",
            // 이후 정상 완료된 작업이 있으면 함께 남긴다 (12 SYN-12)
            remark = "새 작업=$newJobId, $context, supersededBy=${supersededBy?.get("jobId") ?: "-"}"
        )

        log.info("이관 작업 재실행 예약 : {} → {} (이관 엔진이 집어갑니다)", jobId, newJobId)
        return mapOf("newJobId" to newJobId, "state" to "PENDING", "supersededBy" to supersededBy)
    }

    /**
     * 재실행 가능 여부와 막힌 사유 — 목록 SQL(`SyncRepository.RETRYABLE_SQL`)과 같은 규칙이다 (SYN-02).
     *
     * @param retriedBy 이 작업을 재실행한 작업들(`state` 포함)
     * @return (가능 여부, 막힌 사유 — 가능하면 null)
     */
    fun retryVerdict(state: String?, mapUseFlg: String?, retriedBy: List<Map<String, Any?>>): Pair<Boolean, String?> {
        if (state !in RETRYABLE_STATES) return false to "실패하거나 중단된 작업만 재실행할 수 있습니다."
        retriedBy.firstOrNull { it["state"] in setOf("PENDING", "RUNNING") }
            ?.let { return false to "재실행이 이미 예약되어 있습니다. [${it["jobId"]}]" }
        retriedBy.firstOrNull { it["state"] in setOf("DONE", "RETRY_DONE") }
            ?.let { return false to "이미 재실행되어 완료되었습니다. [${it["jobId"]}]" }
        if (mapUseFlg != "Y") return false to "사용 중지된 이관 정의입니다."
        return true to null
    }

    /**
     * 수동 이관 예약 (No.230 — 감사 로그 기록)
     *
     * 실행은 이관 엔진이 한다. 예약 시각(scheduledAt)이 지난 PENDING 작업을
     * 엔진이 원자적으로 선점해 실행하므로, 여기서 즉시 실행되지는 않는다.
     */
    @Transactional
    fun scheduleManualJobs(request: SyncManualRequest): Map<String, Any?> {
        val principal = requireSyncWrite()

        if (request.srcTables.isEmpty()) {
            throw InvalidParameterException("이관할 대상 테이블을 선택해 주세요.", "srcTables")
        }

        val kind = ALLOWED_KINDS[request.kind.lowercase()]
            ?: throw InvalidParameterException("이관 구분은 full 또는 incremental 만 허용합니다.", "kind")

        // 증분은 변경 판별 기준 컬럼이 있어야 한다. 없는 테이블은 예약 단계에서 돌려보낸다.
        if (kind == "INCR") {
            val unsupported = syncRepository.findTablesWithoutCdc(request.srcTables)
            if (unsupported.isNotEmpty()) {
                throw InvalidParameterException(
                    "증분 이관 기준 컬럼이 없는 테이블입니다. 전체 이관을 선택해 주세요. " +
                        "[${unsupported.joinToString(", ")}]",
                    "kind"
                )
            }
        }

        val scheduledAt = request.scheduledAt?.let {
            runCatching { LocalDateTime.parse(it.trim(), DATETIME_FORMAT) }.getOrNull()
                ?: throw InvalidParameterException("예약 시각 형식이 올바르지 않습니다(yyyy-MM-dd HH:mm:ss).", "scheduledAt")
        } ?: LocalDateTime.now()

        val jobIds = syncRepository.insertManualJobs(request.srcTables, kind, scheduledAt, principal.userId)

        if (jobIds.isEmpty()) {
            // use_flg='N' 이거나 매핑에 없는 테이블만 넘어온 경우 — 감사는 남기지 않는다(예약한 것이 없다, 12 SYN-12)
            throw InvalidParameterException(
                "이관 정의에 없거나 사용 중지된 테이블입니다. 연동 매핑을 확인해 주세요.", "srcTables"
            )
        }

        auditLogService.record(
            logType = AuditType.CONFIG_CHANGE,
            menuId = MenuId.SYS_SYNC,
            targetDesc = "수동 이관 예약",
            remark = "대상=${request.srcTables.joinToString(",")}, 구분=$kind, 작업=${jobIds.size}건"
        )

        // ② 데이터 분류 Agent — **API 가 끝낸 일은 예약까지**다. 실제 이관은 MES_migration_engine 이 한다.
        // 그래서 "완료" 가 아니라 "예약 N건" 으로 남긴다. 이관이 끝난 시점의 기록은
        // 엔진 쪽에서 남기는 편이 맞다(⑨ 를 Alert_Engine 이 맡는 것과 같은 경계다).
        agentRunRecorder.record(
            agentNo = AgentRunRecorder.CLASSIFY,
            throughput = "예약 ${jobIds.size}건",
            message = "MES 수동 이관 예약 (${request.srcTables.joinToString(", ").take(300)} · 구분=$kind)"
        )

        return mapOf("jobIds" to jobIds, "scheduledCnt" to jobIds.size, "state" to "PENDING")
    }

    /** 연결 테스트 (No.231) */
    @Transactional(readOnly = true)
    fun testConnection(target: String): Map<String, Any?> {
        requireSyncWrite()
        return syncRepository.testConnection(target)
    }

    /** 연동 매핑 조회 (No.232) */
    @Transactional(readOnly = true)
    fun getMaps(srcTable: String?): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.SYS_SYNC)
        return mapOf("items" to syncRepository.findMaps(srcTable))
    }

    /** 스키마 드리프트 요약 (No.234) */
    @Transactional(readOnly = true)
    fun getDriftSummary(): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.SYS_SYNC)
        return syncRepository.findDriftSummary()
    }

    /** 스키마 드리프트 목록 (No.235) */
    @Transactional(readOnly = true)
    fun getDrifts(
        side: String?,
        kind: String?,
        resolved: Boolean?,
        page: Int?,
        size: Int?
    ): Pair<List<Map<String, Any?>>, PageMeta> {
        authorizationService.requireMenu(MenuId.SYS_SYNC)

        val normalizedSide = normalizeEnum(side, ALLOWED_DRIFT_SIDES, "발견 위치는 SOURCE 또는 TARGET 만 허용합니다.", "side")
        val normalizedKind = normalizeEnum(kind, ALLOWED_DRIFT_KINDS, "드리프트 구분은 NEW 또는 MISSING 만 허용합니다.", "kind")
        // 기본은 미해소 건만 본다. 해소 이력까지 보려면 resolved=false 를 명시적으로 해제한다.
        val paging = PageRequestParam.of(page, size)

        val total = syncRepository.countDrifts(normalizedSide, normalizedKind, resolved)
        val rows = syncRepository.findDrifts(normalizedSide, normalizedKind, resolved, paging.limit, paging.offset)

        return rows to PageMeta.of(paging.page, paging.size, total)
    }

    /** 스키마 드리프트 수동 해소 (No.236 — 감사 로그 기록) */
    @Transactional
    fun resolveDrift(driftId: Long, request: SchemaDriftResolveRequest?): Map<String, Any?> {
        val principal = requireSyncWrite()

        if (!syncRepository.existsDrift(driftId)) {
            throw ResourceNotFoundException("스키마 드리프트를 찾을 수 없습니다. [driftId=$driftId]")
        }

        val updated = syncRepository.resolveDrift(driftId, principal.userId, request?.note)
        if (updated == 0) {
            throw InvalidParameterException("이미 해소 처리된 드리프트입니다.", "driftId")
        }

        auditLogService.record(
            logType = AuditType.CONFIG_CHANGE,
            menuId = MenuId.SYS_SYNC,
            targetDesc = "스키마 드리프트 해소 [driftId=$driftId]",
            remark = request?.note?.take(200)
        )

        log.info("스키마 드리프트 수동 해소 : driftId={}, by={}", driftId, principal.userId)
        return mapOf("driftId" to driftId, "resolved" to true)
    }

    /**
     * 열거형 파라미터를 대문자로 정규화하고 허용 값인지 확인한다.
     * 잘못된 값을 조용히 무시하면 사용자가 필터가 걸린 줄 알고 오해한다.
     */
    private fun normalizeEnum(value: String?, allowed: Set<String>, message: String, field: String): String? {
        if (value.isNullOrBlank()) return null
        val normalized = value.trim().uppercase()
        if (normalized !in allowed) throw InvalidParameterException(message, field)
        return normalized
    }

    /** 연동 정책 조회 (No.233) */
    @Transactional(readOnly = true)
    fun getPolicy(): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.SYS_SYNC)

        val policy = syncRepository.findPolicy().toMutableMap()
        // 이관 이력 보존 기간은 감사 요건에 따라 3년으로 고정한다.
        policy["retentionDays"] = 365 * 3
        return policy.toMap()
    }

    /**
     * 연동 쓰기 동작(재실행·수동 이관·연결 시험·드리프트 해소)의 쓰기 권한 확인 (R-06, 12 SYN-14).
     * 거부 기록은 전역 예외 처리기가 ACCESS_DENIED 로 남긴다(09 AUD-10) — 여기서 따로 남기면 두 줄이 된다.
     */
    private fun requireSyncWrite(): UserPrincipal = authorizationService.requireWrite(MenuId.SYS_SYNC)
}
