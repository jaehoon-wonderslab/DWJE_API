package com.dwje.api.controller

import com.dwje.api.common.response.ApiResponse
import com.dwje.api.model.request.ConnectionTestRequest
import com.dwje.api.model.request.DownloadLogRecordRequest
import com.dwje.api.model.request.SchemaDriftResolveRequest
import com.dwje.api.model.request.SyncManualRequest
import com.dwje.api.service.DownloadLogService
import com.dwje.api.service.SyncService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 보고서 다운로드 이력 컨트롤러 (SY-14)
 */
@RestController
@RequestMapping("/api/v1/download-logs")
@Tag(name = "12. 시스템관리 - 운영")
class DownloadLogController(
    private val downloadLogService: DownloadLogService
) {

    /** 다운로드 이력 요약 (No.222) */
    @Operation(summary = "다운로드 이력 요약", description = "총 건수·당일 건수·blind 포함 건수와 상위 사용자를 반환한다.")
    @GetMapping("/summary")
    fun summary(
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(downloadLogService.getSummary(from, to))

    /** 보존 정책 조회 (No.225) */
    @Operation(summary = "보존 정책 조회", description = "다운로드 이력 보존 연수와 보관 대상 건수를 반환한다.")
    @GetMapping("/retention-policy")
    fun retentionPolicy(): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(downloadLogService.getRetentionPolicy())

    /** 다운로드 이력 조회 (No.223) */
    @Operation(summary = "다운로드 이력 조회", description = "기간·보고서·부서·형식별 다운로드 이력을 조회한다.")
    @GetMapping
    fun logs(
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) reportId: String?,
        @Parameter(description = "부서명") @RequestParam(required = false) deptId: String?,
        @Parameter(description = "형식 — XLS|CSV|PDF") @RequestParam(required = false) format: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?
    ): ApiResponse<Map<String, Any?>> {
        val (rows, meta) = downloadLogService.getLogs(from, to, reportId, deptId, format, page, size)
        return ApiResponse.page(mapOf("items" to rows), meta)
    }

    /**
     * 다운로드 이력 기록 (No.224)
     *
     * 프론트에서 클라이언트 측 내려받기를 수행한 경우 이력을 남기기 위해 호출한다.
     */
    @Operation(
        summary = "다운로드 이력 기록",
        description = "클라이언트 측 내려받기 이력을 기록한다. " +
            "받는 키는 reportId · reportNm · menuId · format · scope · rowCnt · blindCnt 이며 그 외 키는 400."
    )
    @PostMapping
    fun record(@Valid @RequestBody request: DownloadLogRecordRequest): ApiResponse<Map<String, Any?>> {
        val logId = downloadLogService.record(
            reportId = request.reportId,
            reportNm = request.reportNm?.takeIf { it.isNotBlank() } ?: "보고서",
            menuId = request.menuId,
            format = request.format?.takeIf { it.isNotBlank() } ?: "xls",
            scope = request.scope,
            rowCnt = request.rowCnt ?: 0,
            blindCnt = request.blindCnt ?: 0,
            params = request.params,
            fileSize = request.fileSize
        )
        return ApiResponse.ok(mapOf("logId" to logId), "다운로드 이력이 기록되었습니다.")
    }
}

/**
 * 데이터 연동 이력 컨트롤러 (SY-15)
 *
 * 접근 부서 : 전산팀 · 통합관리자
 */
@RestController
@RequestMapping("/api/v1/sync")
@Tag(name = "12. 시스템관리 - 운영")
class SyncController(
    private val syncService: SyncService
) {

    /** 연동 요약 (No.226) */
    @Operation(summary = "연동 요약", description = "당일 이관 건수·실패 건수·평균 소요 시간을 반환한다.")
    @GetMapping("/jobs/summary")
    fun summary(
        @RequestParam(required = false) date: String?
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(syncService.getSummary(date))

    /** 수동 이관 예약 (No.230). {jobId} 매핑보다 먼저 선언한다. */
    @Operation(
        summary = "수동 이관 예약",
        description = "선택한 원본 테이블의 이관 작업을 예약 대기(PENDING) 상태로 만든다. " +
            "실제 실행은 이관 엔진이 예약 시각 이후 큐를 점검할 때 수행한다."
    )
    @PostMapping("/jobs/manual")
    fun scheduleManual(@Valid @RequestBody request: SyncManualRequest): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(syncService.scheduleManualJobs(request), "수동 이관을 예약했습니다. 이관 엔진이 곧 실행합니다.")

    /** 이관 실행 이력 조회 (SY-15 — 엔진 1회 실행 = 1행) */
    @Operation(
        summary = "이관 실행 이력 조회",
        description = "이관 엔진을 한 번 돌린 단위의 이력. 원본 접속 실패·대상 없음·건너뜀처럼 " +
            "테이블 작업까지 가지 못한 실행도 남는다. 테이블별 상세는 이관 작업 이력(runId 로 연결). " +
            "tableCnt 는 대상 '테이블' 수이고 행수는 okRows·ngRows 다. " +
            "driftOpenCntAtRun 은 그 실행 시점의 값으로 현재 미해소 건수와 다를 수 있다. " +
            "message 는 실패 사유이며 예외 연쇄가 이어져 최대 2000자까지 길어질 수 있다. " +
            "접속 URL(원본·대상)은 서버 주소가 드러나므로 응답에 포함하지 않는다. " +
            "dryRun=true 는 모의 실행이며 대상만 확인하고 아무것도 반영하지 않았다 — " +
            "상태·모드와 직교하므로 state 로는 구분할 수 없다."
    )
    @GetMapping("/runs")
    fun runs(
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @Parameter(description = "결과 — SYNC_RUN_STATE (DONE|PARTIAL|FAIL|PREFLIGHT_FAIL|NO_WORK|SKIPPED|ABORTED|RUNNING)")
        @RequestParam(required = false) state: String?,
        @Parameter(description = "모드 — SYNC_RUN_MODE (SCHEDULED|MANUAL|QUEUE|RETRY)")
        @RequestParam(required = false) mode: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?
    ): ApiResponse<Map<String, Any?>> {
        val (rows, meta) = syncService.getRuns(from, to, state, mode, page, size)
        return ApiResponse.page(mapOf("items" to rows), meta)
    }

    /** 이관 작업 이력 조회 (No.227) */
    @Operation(summary = "이관 작업 이력 조회", description = "기간·원본 테이블·상태별 이관 작업 이력을 조회한다.")
    @GetMapping("/jobs")
    fun jobs(
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) srcTable: String?,
        @Parameter(description = "상태 — DONE|RUNNING|FAIL|RETRY_DONE|ABORTED")
        @RequestParam(required = false) state: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?
    ): ApiResponse<Map<String, Any?>> {
        val (rows, meta) = syncService.getJobs(from, to, srcTable, state, page, size)
        return ApiResponse.page(mapOf("items" to rows), meta)
    }

    /** 이관 작업 상세 (No.228) */
    @Operation(summary = "이관 작업 상세", description = "작업 정보·매핑 파라미터·오류 목록을 반환한다.")
    @GetMapping("/jobs/{jobId}")
    fun job(@PathVariable jobId: String): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(syncService.getJob(jobId))

    /** 이관 작업 재실행 (No.229) */
    @Operation(
        summary = "이관 작업 재실행",
        description = "실패한 이관 작업을 같은 매핑으로 다시 예약한다. 실제 실행은 이관 엔진이 수행한다."
    )
    @PostMapping("/jobs/{jobId}/retry")
    fun retry(@PathVariable jobId: String): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(syncService.retryJob(jobId), "재실행을 등록했습니다. 이관 엔진이 곧 실행합니다.")

    /** 연결 테스트 (No.231) */
    @Operation(summary = "연결 테스트", description = "원본·대상 데이터베이스 연결 상태를 확인한다.")
    @PostMapping("/connection-test")
    fun connectionTest(
        @Valid @RequestBody(required = false) request: ConnectionTestRequest?
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(syncService.testConnection(request?.target ?: "postgresql"))

    /** 연동 매핑 조회 (No.232) */
    @Operation(summary = "연동 매핑 조회", description = "원본 → 대상 테이블 매핑과 키 컬럼을 반환한다.")
    @GetMapping("/maps")
    fun maps(
        @RequestParam(required = false) srcTable: String?
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(syncService.getMaps(srcTable))

    /** 연동 정책 조회 (No.233) */
    @Operation(summary = "연동 정책 조회", description = "배치 스케줄·증분 기준·재시도 정책·실패 알림 조건을 반환한다.")
    @GetMapping("/policy")
    fun policy(): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(syncService.getPolicy())

    // ── 스키마 드리프트 (No.234~236) ────────────────────────────────────────────────────
    //  이관 정의(ax.tb_sync_map)와 원본·대상 DB 의 실제 테이블 목록이 어긋난 사실.
    //  판정과 기록은 이관 엔진(MES_migration_engine)이 배치마다 직접 수행하고,
    //  여기서는 조회와 수동 해소만 제공한다.

    /** 스키마 드리프트 요약 (No.234) */
    @Operation(
        summary = "스키마 드리프트 요약",
        description = "미해소 드리프트 건수를 위치(원본/대상)·구분(신규/유실)별로 반환한다. 화면 상단 경고 배지에 사용한다."
    )
    @GetMapping("/schema-drift/summary")
    fun driftSummary(): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(syncService.getDriftSummary())

    /** 스키마 드리프트 목록 (No.235) */
    @Operation(
        summary = "스키마 드리프트 목록 조회",
        description = "이관 정의에 없는 신규 테이블과 정의에는 있으나 사라진 테이블을 조회한다. " +
            "발견 횟수가 많을수록 오래 방치된 건이므로 내림차순으로 정렬한다."
    )
    @GetMapping("/schema-drift")
    fun drifts(
        @Parameter(description = "발견 위치 — SOURCE(원본 MSSQL) | TARGET(대상 PostgreSQL)")
        @RequestParam(required = false) side: String?,
        @Parameter(description = "드리프트 구분 — NEW(신규 발견) | MISSING(유실)")
        @RequestParam(required = false) kind: String?,
        @Parameter(description = "해소 여부. 미지정 시 전체, false 로 주면 처리할 목록만")
        @RequestParam(required = false) resolved: Boolean?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?
    ): ApiResponse<Map<String, Any?>> {
        val (rows, meta) = syncService.getDrifts(side, kind, resolved, page, size)
        return ApiResponse.page(mapOf("items" to rows), meta)
    }

    /** 스키마 드리프트 수동 해소 (No.236) */
    @Operation(
        summary = "스키마 드리프트 해소 처리",
        description = "조치할 것이 없다고 판단한 드리프트를 목록에서 내린다. " +
            "실제 원인이 남아 있으면 다음 배치에서 엔진이 다시 열고 발견 횟수를 이어서 센다."
    )
    @PostMapping("/schema-drift/{driftId}/resolve")
    fun resolveDrift(
        @PathVariable driftId: Long,
        @Valid @RequestBody(required = false) request: SchemaDriftResolveRequest?
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(syncService.resolveDrift(driftId, request), "드리프트를 해소 처리했습니다.")
}
