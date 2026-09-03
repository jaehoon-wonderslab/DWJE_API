package com.dwje.api.controller

import com.dwje.api.common.response.ApiResponse
import com.dwje.api.common.util.MenuId
import com.dwje.api.model.request.ApprovalLineRequest
import com.dwje.api.model.request.ExportFormatRequest
import com.dwje.api.model.request.ScrapDraftRequest
import com.dwje.api.model.request.ScrapManualRowRequest
import com.dwje.api.model.request.ScrapUnitPriceRequest
import com.dwje.api.service.DownloadLogService
import com.dwje.api.service.ExportService
import com.dwje.api.service.ReportService
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

    /** 폐기 보고서 목록 조회 (No.113) */
    @Operation(summary = "폐기 보고서 목록 조회", description = "기간·발생 구분별 폐기 보고서 목록을 조회한다.")
    @GetMapping("/scrap")
    fun scrapDocs(
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) originType: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?
    ): ApiResponse<Map<String, Any?>> {
        val (rows, meta, mask) = scrapReportService.getScrapDocs(from, to, originType, page, size)
        return ApiResponse.page(mapOf("items" to rows), meta, mask.maskedKeys())
    }

    /** 초안 생성·임시저장 (No.116) */
    @Operation(summary = "폐기 보고서 초안 생성", description = "선택한 MES 전표로 폐기 보고서 초안을 생성한다.")
    @PostMapping("/scrap/drafts")
    fun createScrapDraft(@Valid @RequestBody request: ScrapDraftRequest): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(scrapReportService.createDraft(request), "초안이 생성되었습니다.")

    /** 초안 수정 (No.117) */
    @Operation(summary = "폐기 보고서 초안 수정", description = "위저드 단계 상태와 선택 전표·입력 값을 갱신한다.")
    @PutMapping("/scrap/drafts/{draftId}")
    fun updateScrapDraft(
        @PathVariable draftId: Long,
        @Valid @RequestBody request: ScrapDraftRequest
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(scrapReportService.updateDraft(draftId, request), "초안이 저장되었습니다.")

    /** 초안 삭제 — 위저드 취소 (신규, API 목록 외) */
    @Operation(
        summary = "폐기 보고서 초안 삭제",
        description = "위저드를 중단할 때 남은 초안을 삭제한다(소프트 삭제). " +
            "없는 초안은 404, 이미 발행·확정된 보고서는 409 로 거부한다."
    )
    @DeleteMapping("/scrap/drafts/{draftId}")
    fun deleteScrapDraft(@PathVariable draftId: Long): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(scrapReportService.deleteDraft(draftId), "초안이 삭제되었습니다.")

    /** 수기 폐기 행 추가 (No.118 — 2단계) */
    @Operation(summary = "수기 폐기 행 추가", description = "MES 전표에 없는 불용재고·반품분을 수기 행으로 추가한다.")
    @PostMapping("/scrap/drafts/{draftId}/manual-rows")
    fun addManualRow(
        @PathVariable draftId: Long,
        @Valid @RequestBody request: ScrapManualRowRequest
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(scrapReportService.addManualRow(draftId, request), "수기 행이 추가되었습니다.")

    /** 수기 폐기 행 삭제 (No.119) */
    @Operation(summary = "수기 폐기 행 삭제", description = "수기로 추가한 폐기 행을 삭제한다.")
    @DeleteMapping("/scrap/drafts/{draftId}/manual-rows/{rowId}")
    fun deleteManualRow(
        @PathVariable draftId: Long,
        @PathVariable rowId: Long
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(scrapReportService.deleteManualRow(draftId, rowId), "수기 행이 삭제되었습니다.")

    /** 폐기 금액 산정 (No.120 — 3단계) */
    @Operation(summary = "폐기 금액 산정", description = "원가 기준정보 단가를 적용해 폐기 금액을 산정한다.")
    @PostMapping("/scrap/drafts/{draftId}/calculate")
    fun calculate(@PathVariable draftId: Long): ApiResponse<Map<String, Any?>> {
        val (data, mask) = scrapReportService.calculate(draftId)
        return ApiResponse.ok(data, mask.maskedKeys(), "금액을 산정했습니다.")
    }

    /** 단가 수기 조정 (No.121) */
    @Operation(summary = "단가 수기 조정", description = "모델·공정 단위로 단가를 직접 조정한다. 조정 이력이 보존된다.")
    @PutMapping("/scrap/drafts/{draftId}/unit-price")
    fun adjustUnitPrice(
        @PathVariable draftId: Long,
        @Valid @RequestBody request: ScrapUnitPriceRequest
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(scrapReportService.adjustUnitPrice(draftId, request), "단가가 조정되었습니다.")

    /** 검토 부서·결재선 지정 (No.122 — 4단계) */
    @Operation(summary = "검토 부서·결재선 지정", description = "검토 부서와 기안·검토·승인 3단 결재선을 지정한다.")
    @PutMapping("/scrap/drafts/{draftId}/approval-line")
    fun setApprovalLine(
        @PathVariable draftId: Long,
        @Valid @RequestBody request: ApprovalLineRequest
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(scrapReportService.setApprovalLine(draftId, request), "결재선이 지정되었습니다.")

    /** 검토 요청 발송 (No.123) */
    @Operation(summary = "검토 요청 발송", description = "지정 부서·담당자에게 검토 요청 알림을 발송한다.")
    @PostMapping("/scrap/drafts/{draftId}/review-request")
    fun sendReviewRequest(
        @PathVariable draftId: Long,
        @Valid @RequestBody(required = false) request: ApprovalLineRequest?
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(
            scrapReportService.sendReviewRequest(draftId, request?.notifyChannels ?: emptyList()),
            "검토 요청을 발송했습니다."
        )

    /** 보고서 생성 (No.124 — 5단계) */
    @Operation(summary = "폐기 보고서 생성", description = "문서번호를 채번하고 보고서를 발행한다.")
    @PostMapping("/scrap/drafts/{draftId}/publish")
    fun publish(@PathVariable draftId: Long): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(scrapReportService.publish(draftId), "보고서가 생성되었습니다.")

    /** 폐기 보고서 상세 조회 (No.114) */
    @Operation(summary = "폐기 보고서 상세 조회", description = "결재 양식 머리부·발생 정보·상세 표·검토 의견을 반환한다.")
    @GetMapping("/scrap/{docNo}")
    fun scrapDoc(@PathVariable docNo: String): ApiResponse<Map<String, Any?>> {
        val (data, mask) = scrapReportService.getScrapDoc(docNo)
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    // =================================================================================
    // 공통 출력
    // =================================================================================

    /** 보고서 출력 (No.125 — 엑셀·CSV·PDF) */
    @Operation(summary = "보고서 출력", description = "보고서를 엑셀·CSV 로 출력한다. blind 항목은 제외한다.")
    @PostMapping("/{reportId}/export")
    fun export(
        @PathVariable reportId: Long,
        @Valid @RequestBody(required = false) request: ExportFormatRequest?
    ): ResponseEntity<ByteArrayResource> {
        val format = request?.format ?: "xls"
        val (detail, mask) = scrapReportService.getDocDetailById(reportId)

        @Suppress("UNCHECKED_CAST")
        val rows = detail["rows"] as List<Map<String, Any?>>

        downloadLogService.record(
            reportId = null,
            reportNm = ((detail["header"] as? Map<*, *>)?.get("title") as? String) ?: "보고서",
            menuId = MenuId.RPT_SCRAP,
            format = format,
            scope = "reportId=$reportId",
            rowCnt = rows.size,
            blindCnt = mask.maskedCount(),
            blindCells = mask.maskedKeys().associateWith { rows.size }
        )

        return exportService.export(
            format = format,
            fileName = "report_${reportId}_${exportService.timestamp()}",
            headers = listOf("모델", "공정", "폐기 사유", "수량", "단가", "금액", "비중(%)", "구분"),
            keys = listOf("model", "process", "reason", "qty", "unitPrice", "amount", "ratio", "kind"),
            rows = rows
        )
    }

    /** 보고서 인쇄용 조회 (No.126) */
    @Operation(summary = "보고서 인쇄용 조회", description = "인쇄 양식용 데이터를 반환하고 다운로드 이력을 기록한다.")
    @GetMapping("/{reportId}/print")
    fun print(@PathVariable reportId: Long): ApiResponse<Map<String, Any?>> {
        val (detail, mask) = scrapReportService.getDocDetailById(reportId)

        @Suppress("UNCHECKED_CAST")
        val rows = detail["rows"] as List<Map<String, Any?>>

        downloadLogService.record(
            reportId = null,
            reportNm = ((detail["header"] as? Map<*, *>)?.get("title") as? String) ?: "보고서",
            menuId = MenuId.RPT_SCRAP,
            format = "pdf",
            scope = "print reportId=$reportId",
            rowCnt = rows.size,
            blindCnt = mask.maskedCount()
        )

        return ApiResponse.ok(detail, mask.maskedKeys())
    }
}
