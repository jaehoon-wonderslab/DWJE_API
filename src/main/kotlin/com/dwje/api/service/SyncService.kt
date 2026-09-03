package com.dwje.api.service

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.exception.ResourceNotFoundException
import com.dwje.api.common.response.PageMeta
import com.dwje.api.common.util.DateUtils
import com.dwje.api.common.util.MenuId
import com.dwje.api.common.validation.CodeValidator
import com.dwje.api.common.util.PageRequestParam
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
 * 접근 부서 : 전산팀 · 통합관리자
 */
@Service
class SyncService(
    private val syncRepository: SyncRepository,
    private val authorizationService: AuthorizationService,
    private val auditLogService: AuditLogService,
    private val codeValidator: CodeValidator
) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private val DATETIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        private val ALLOWED_KINDS = mapOf("full" to "FULL", "incremental" to "INCR", "incr" to "INCR")
        private val ALLOWED_DRIFT_SIDES = setOf("SOURCE", "TARGET")
        private val ALLOWED_DRIFT_KINDS = setOf("NEW", "MISSING")
    }

    /** 연동 요약 (No.226) */
    @Transactional(readOnly = true)
    fun getSummary(date: String?): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.SYS_SYNC)
        val target = DateUtils.parseDate(date, "date", LocalDate.now())
        return syncRepository.findSummary(target)
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
        size: Int?
    ): Pair<List<Map<String, Any?>>, PageMeta> {
        authorizationService.requireMenu(MenuId.SYS_SYNC)

        // 코드 집합 밖의 값을 그냥 넘기면 조용히 0건이 나와 "실행 이력이 없음" 과 구분되지 않는다.
        codeValidator.require("SYNC_RUN_STATE", state, "state", "실행 결과")
        codeValidator.require("SYNC_RUN_MODE", mode, "mode", "실행 모드")

        val (fromDate, toDate) = DateUtils.periodOf(from, to, 7)
        val paging = PageRequestParam.of(page, size)

        val total = syncRepository.countRuns(fromDate, toDate, state, mode)
        val rows = syncRepository.findRuns(fromDate, toDate, state, mode, paging.limit, paging.offset)

        return rows to PageMeta.of(paging.page, paging.size, total)
    }

    /** 이관 작업 이력 조회 (No.227) */
    @Transactional(readOnly = true)
    fun getJobs(
        from: String?,
        to: String?,
        srcTable: String?,
        state: String?,
        page: Int?,
        size: Int?
    ): Pair<List<Map<String, Any?>>, PageMeta> {
        authorizationService.requireMenu(MenuId.SYS_SYNC)
        codeValidator.require("SYNC_STATE", state, "state", "작업 상태")

        val (fromDate, toDate) = DateUtils.periodOf(from, to, 7)
        val paging = PageRequestParam.of(page, size)

        val total = syncRepository.countJobs(fromDate, toDate, srcTable, state)
        val rows = syncRepository.findJobs(fromDate, toDate, srcTable, state, paging.limit, paging.offset)

        return rows to PageMeta.of(paging.page, paging.size, total)
    }

    /** 이관 작업 상세 (No.228) */
    @Transactional(readOnly = true)
    fun getJob(jobId: String): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.SYS_SYNC)

        val job = syncRepository.findJob(jobId)
            ?: throw ResourceNotFoundException("이관 작업을 찾을 수 없습니다. [jobId=$jobId]")

        return job + mapOf("errors" to syncRepository.findJobErrors(jobId, 500))
    }

    /**
     * 이관 작업 재실행 (No.229 — 감사 로그 기록)
     *
     * 실행은 이관 엔진이 한다. 여기서는 PENDING 작업을 만들어 큐에 넣기만 하고,
     * 엔진이 다음 큐 점검(기본 60초 주기)에서 집어가 실행한다.
     */
    @Transactional
    fun retryJob(jobId: String): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.SYS_SYNC)

        if (!syncRepository.existsJob(jobId)) {
            throw ResourceNotFoundException("이관 작업을 찾을 수 없습니다. [jobId=$jobId]")
        }

        val newJobId = syncRepository.insertRetryJob(jobId, principal.userId)
            ?: throw ResourceNotFoundException("재실행할 매핑 정보를 찾을 수 없습니다. [jobId=$jobId]")

        auditLogService.record(
            logType = "AUTO_GEN",
            menuId = MenuId.SYS_SYNC,
            targetDesc = "이관 작업 재실행 [$jobId]",
            remark = "새 작업=$newJobId"
        )

        log.info("이관 작업 재실행 예약 : {} → {} (이관 엔진이 집어갑니다)", jobId, newJobId)
        return mapOf("newJobId" to newJobId, "state" to "PENDING")
    }

    /**
     * 수동 이관 예약 (No.230 — 감사 로그 기록)
     *
     * 실행은 이관 엔진이 한다. 예약 시각(scheduledAt)이 지난 PENDING 작업을
     * 엔진이 원자적으로 선점해 실행하므로, 여기서 즉시 실행되지는 않는다.
     */
    @Transactional
    fun scheduleManualJobs(request: SyncManualRequest): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.SYS_SYNC)

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

        auditLogService.record(
            logType = "AUTO_GEN",
            menuId = MenuId.SYS_SYNC,
            targetDesc = "수동 이관 예약",
            remark = "대상=${request.srcTables.joinToString(",")}, 구분=$kind, 작업=${jobIds.size}건"
        )

        if (jobIds.isEmpty()) {
            // use_flg='N' 이거나 매핑에 없는 테이블만 넘어온 경우
            throw InvalidParameterException(
                "이관 정의에 없거나 사용 중지된 테이블입니다. 연동 매핑을 확인해 주세요.", "srcTables"
            )
        }

        return mapOf("jobIds" to jobIds, "scheduledCnt" to jobIds.size, "state" to "PENDING")
    }

    /** 연결 테스트 (No.231) */
    @Transactional(readOnly = true)
    fun testConnection(target: String): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.SYS_SYNC)
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
        val principal = authorizationService.requireMenu(MenuId.SYS_SYNC)

        if (!syncRepository.existsDrift(driftId)) {
            throw ResourceNotFoundException("스키마 드리프트를 찾을 수 없습니다. [driftId=$driftId]")
        }

        val updated = syncRepository.resolveDrift(driftId, principal.userId, request?.note)
        if (updated == 0) {
            throw InvalidParameterException("이미 해소 처리된 드리프트입니다.", "driftId")
        }

        auditLogService.record(
            logType = "AUTO_GEN",
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
}
