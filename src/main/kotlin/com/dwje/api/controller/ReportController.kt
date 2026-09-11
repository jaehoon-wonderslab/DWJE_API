package com.dwje.api.controller

import com.dwje.api.common.response.ApiResponse
import com.dwje.api.common.util.MenuId
import com.dwje.api.model.request.ApprovalLineRequest
import com.dwje.api.model.request.ExportFormatRequest
import com.dwje.api.model.request.ReportUsageRequest
import com.dwje.api.model.request.ReportWriteStateRequest
import com.dwje.api.model.request.ScrapDraftRequest
import com.dwje.api.model.request.ScrapManualRowRequest
import com.dwje.api.model.request.ScrapUnitPriceRequest
import com.dwje.api.service.DownloadLogService
import com.dwje.api.service.ExportService
import com.dwje.api.service.ReportService
import com.dwje.api.service.ReportUsageService
import com.dwje.api.service.ReportWriteStateService
import com.dwje.api.service.ScrapReportService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 보고서 API 컨트롤러 (RP-01 ~ RP-07)
 *
 * 아침회의 자료 · 연간 출하계획 · 제품별 수율 · 고객사별 LRR · 폐기 보고서를 담당한다.
 */
@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "07. 보고서")
class ReportController(
    private val reportService: ReportService,
    private val reportWriteStateService: ReportWriteStateService,
    private val reportUsageService: ReportUsageService,
    private val scrapReportService: ScrapReportService,
    private val exportService: ExportService,
    private val downloadLogService: DownloadLogService
) {

    // =================================================================================
    // RP-01 / RP-02. 아침회의 자료
    // =================================================================================

    /** PRESS 아침회의 자료 조회 (No.107) */
    @Operation(summary = "PRESS 아침회의 자료 조회", description = "Press 공정 일목표 대비 실적과 주간 누적 달성률을 반환한다.")
    @GetMapping("/press-morning")
    fun pressMorning(
        @RequestParam(required = false) baseDate: String?,
        @Parameter(description = "대상 공정 코드 목록") @RequestParam(required = false) processScope: List<String>?,
        @Parameter(description = "상태 필터 — NORMAL|WARN|CRIT") @RequestParam(required = false) state: String?
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = reportService.getMorningMeeting(
            MenuId.RPT_PRESS_MORNING, baseDate, processScope ?: emptyList(), state
        )
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    /** 금일 결정 사항·DRI 조회 (No.108) */
    @Operation(summary = "금일 결정 사항·DRI 조회", description = "아침회의 결정 사항과 담당자(DRI)를 반환한다.")
    @GetMapping("/press-morning/decisions")
    fun pressMorningDecisions(
        @RequestParam(required = false) baseDate: String?
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(reportService.getMorningDecisions(baseDate))

    /** Plating·Coating 아침회의 자료 조회 (No.109) */
    @Operation(summary = "Plating·Coating 아침회의 자료 조회", description = "Plating·Coating 라인의 아침회의 요약표를 반환한다.")
    @GetMapping("/plating-morning")
    fun platingMorning(
        @RequestParam(required = false) baseDate: String?,
        @Parameter(description = "대상 공정 — all | A Plating | B Plating | Coating")
        @RequestParam(required = false) processScope: List<String>?,
        @RequestParam(required = false) state: String?
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = reportService.getMorningMeeting(
            MenuId.RPT_PLATING_MORNING, baseDate, processScope ?: emptyList(), state
        )
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    // =================================================================================
    // RP-03 ~ RP-05. 출하계획 · 수율 · LRR
    // =================================================================================

    /** 연간 출하계획 조회 (No.110) */
    @Operation(summary = "연간 출하계획 조회", description = "회계연도(8월 시작) 12개월 모델 × 고객사 출하계획을 반환한다.")
    @GetMapping("/ship-plan")
    fun shipPlan(
        @Parameter(description = "회계연도 시작 연도") @RequestParam(required = false) planYear: Int?,
        @RequestParam(required = false) modelCd: String?,
        @RequestParam(required = false) customerCd: String?,
        @Parameter(description = "단위 — qty|amount") @RequestParam(required = false) unit: String?
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = reportService.getShipPlan(planYear, modelCd, customerCd, unit)
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    /** 제품별 수율 조회 (No.111) */
    @Operation(
        summary = "제품별 수율 조회",
        description = "월간 모델별 수율과 Loss 유형 11종·관리 항목 3종을 반환한다. " +
            "rows 는 쪽 단위이고 summary·lossTypes·mgmtTypes 는 항상 전체 기준이다."
    )
    @GetMapping("/yield-by-model")
    fun yieldByModel(
        @RequestParam(required = false) yearMonth: String?,
        @RequestParam(required = false) modelCd: String?,
        @RequestParam(required = false) processId: String?,
        @RequestParam(required = false) page: Int?,
        @Parameter(description = "쪽 크기. 0 이면 전량(인쇄·내려받기용)") @RequestParam(required = false) size: Int?
    ): ApiResponse<Map<String, Any?>> {
        val (data, meta, mask) = reportService.getYieldByModel(yearMonth, modelCd, processId, page, size)
        return ApiResponse.page(data, meta, mask.maskedKeys())
    }

    /** 고객사별 LRR 조회 (No.112) */
    @Operation(summary = "고객사별 LRR 조회", description = "고객사 출하수량 대비 LRR 통보 수량을 집계한다.")
    @GetMapping("/lrr-by-customer")
    fun lrrByCustomer(
        @RequestParam(required = false) baseYear: Int?,
        @RequestParam(required = false) customerCd: String?,
        @Parameter(description = "집계 단위 — month|quarter|year") @RequestParam(required = false) unit: String?
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = reportService.getLrrByCustomer(baseYear, customerCd, unit)
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    // =================================================================================
    // 보고서 센터 — 작성 상태 (일일 생산현황 보고 · 아침회의 자료 · 폐기 보고서)
    // =================================================================================

    /**
     * 보고서 작성 상태 조회 — 허브 상단 "오늘 작성할 보고서" 띠
     *
     * 문서 관리가 없어 상태는 `max(파생, 기록)` 이다. 파생은 아침회의 결과 행 유무로 DRAFT 까지만,
     * 제출·승인은 화면 단추가 남긴 기록(`ax.tb_rpt_write_state`)에서 온다.
     */
    @Operation(
        summary = "보고서 작성 상태 조회",
        description = "대상일의 화면별 작성 상태(NONE|DRAFT|SUBMITTED|APPROVED)를 반환한다. " +
            "대상은 prod-daily · rpt-press-morning · rpt-plating-morning · rpt-scrap 중 호출자에게 메뉴 권한이 있는 화면이다. " +
            "source 는 DERIVED(저장 행 유무로 판단) 또는 RECORDED(제출·승인 단추로 기록)."
    )
    @GetMapping("/status")
    fun writeStatus(
        @Parameter(description = "보고서 대상일 yyyy-MM-dd. 미지정 시 오늘") @RequestParam(required = false) baseDate: String?
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(reportWriteStateService.getStatus(baseDate))

    /** 보고서 작성 상태 기록 — 화면 머리말의 「제출」「승인」「작성 중으로 되돌리기」 */
    @Operation(
        summary = "보고서 작성 상태 기록",
        description = "화면·대상일의 작성 상태를 DRAFT|SUBMITTED|APPROVED 로 기록한다. 있으면 덮어쓴다(낮추는 방향 포함). " +
            "대상 화면이 아니면 400, 해당 화면 메뉴 권한이 없으면 E-AUTH-002."
    )
    @PutMapping("/status")
    fun setWriteStatus(
        @Valid @RequestBody request: ReportWriteStateRequest
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(reportWriteStateService.setStatus(request), "작성 상태를 기록했습니다.")

    // =================================================================================
    // 보고서 화면 — 자주 쓰는 보고서 (계정별 사용 횟수)
    // =================================================================================

    /** 자주 쓰는 보고서 상위 N — `/menu/report` 버튼 줄 */
    @Operation(
        summary = "자주 쓰는 보고서 조회",
        description = "현재 사용자가 보고서를 만든 횟수 내림차순 → 최근 사용 내림차순으로 상위 top 개를 반환한다. " +
            "사용 중지 메뉴와 권한이 없는 화면은 제외한다. 기록이 없으면 items: []."
    )
    @GetMapping("/usage")
    fun usage(
        @Parameter(description = "반환 개수. 기본 5, 최대 20") @RequestParam(required = false) top: Int?
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(reportUsageService.getTop(top))

    /** 보고서 사용 1회 기록 — 만들 때마다 웹이 호출 */
    @Operation(
        summary = "보고서 사용 기록",
        description = "screenId 의 사용 횟수를 +1 하고 마지막 사용 시각을 갱신한 뒤 상위 5개 목록을 반환한다. " +
            "메뉴에 없는 ID 는 400, 권한 없는 화면은 E-AUTH-002."
    )
    @PostMapping("/usage")
    fun recordUsage(
        @Valid @RequestBody request: ReportUsageRequest
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(reportUsageService.record(request), "사용 기록을 저장했습니다.")

    // =================================================================================
    // RP-06 / RP-07. 폐기 보고서 · 작성 위저드
    // =================================================================================

    /** MES 폐기 전표 조회 (No.115 — 1단계). {docNo} 매핑보다 먼저 선언한다. */
    @Operation(summary = "MES 폐기 전표 조회", description = "기간·공정·모델·불량 유형·발생 구분으로 폐기 전표를 조회한다.")
    @GetMapping("/scrap/mes-vouchers")
    fun mesVouchers(
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) processId: String?,
        @RequestParam(required = false) modelCd: String?,
        @RequestParam(required = false) defectTypeCd: String?,
        @RequestParam(required = false) originType: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?
    ): ApiResponse<Map<String, Any?>> {
        val (rows, meta, mask) = scrapReportService.getMesVouchers(
            from, to, processId, modelCd, defectTypeCd, originType, page, size
        )
        return ApiResponse.page(mapOf("items" to rows), meta, mask.maskedKeys())
    }
}
