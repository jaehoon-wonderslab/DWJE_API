package com.dwje.api.controller

import com.dwje.api.common.response.ApiResponse
import com.dwje.api.common.util.MenuId
import com.dwje.api.model.request.DowntimeCreateRequest
import com.dwje.api.model.request.DowntimeUpdateRequest
import com.dwje.api.model.request.ExportFormatRequest
import com.dwje.api.model.request.ReasonRequest
import com.dwje.api.model.request.ReportCopyRequest
import com.dwje.api.model.request.ReportCorrectionRequest
import com.dwje.api.model.request.ReportRegenerateRequest
import com.dwje.api.service.DailyReportService
import com.dwje.api.service.DowntimeService
import com.dwje.api.service.DownloadLogService
import com.dwje.api.service.ExportService
import com.dwje.api.service.ProductionService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 생산관리 API 컨트롤러 (PR-01 ~ PR-05)
 *
 * 생산 모니터링 · 실적 집계 · 일일 생산현황 보고 · 비가동 관리를 담당한다.
 */
@RestController
@RequestMapping("/api/v1/production")
@Tag(name = "04. 생산관리")
class ProductionController(
    private val productionService: ProductionService,
    private val dailyReportService: DailyReportService,
    private val downtimeService: DowntimeService,
    private val exportService: ExportService,
    private val downloadLogService: DownloadLogService
) {

    // =================================================================================
    // PR-01. 생산 모니터링
    // =================================================================================

    /**
     * 모니터링 요약 (No.55)
     *
     * @param processId 공정 코드
     */
    @Operation(summary = "모니터링 요약", description = "가동·경고·정지 설비 수와 시간당 처리량을 반환한다.")
    @GetMapping("/monitor/summary")
    fun monitorSummary(
        @RequestParam(required = false) processId: String?
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = productionService.getMonitorSummary(processId)
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    /**
     * 설비별 실시간 현황 (No.56 — 10초 폴링)
     */
    @Operation(summary = "설비별 실시간 현황", description = "설비별 생산량·불량률·가동률·타발속도를 조회한다.")
    @GetMapping("/monitor/equipments")
    fun monitorEquipments(
        @Parameter(description = "설비코드 전방 일치 검색어 — 범위 표기가 아니라 접두어다 (예 MT)")
        @RequestParam(required = false) lineRange: String?,
        @Parameter(description = "설비 모델명") @RequestParam(required = false) model: String?,
        @Parameter(description = "공정 코드 — 응답의 processId 와 같은 값 (예 S120)")
        @RequestParam(required = false) processId: String?,
        @Parameter(description = "상태 — RUNNING|WARNING|STOPPED") @RequestParam(required = false) state: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?
    ): ApiResponse<Map<String, Any?>> {
        val (rows, meta, mask) =
            productionService.getMonitorEquipments(lineRange, model, processId, state, page, size)
        return ApiResponse.page(mapOf("items" to rows), meta, mask.maskedKeys())
    }

    /**
     * 설비 상세 조회 (No.30 — 모달)
     *
     * @param eqptCd 설비 코드
     */
    @Operation(summary = "설비 상세 조회", description = "설비의 당일 실적·가동률·금형·정지 경과 시간을 반환한다.")
    @GetMapping("/equipments/{eqptCd}")
    fun equipmentDetail(
        @PathVariable eqptCd: String,
        @RequestParam(required = false) date: String?
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = productionService.getEquipmentDetail(eqptCd, date)
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    // =================================================================================
    // PR-02. 실적 집계·조회
    // =================================================================================

    /**
     * 실적 집계 조회 (No.57)
     *
     * @param unit 집계 단위 — day | week | month
     */
    @Operation(
        summary = "실적 집계 조회",
        description = "기간·단위별 생산 실적을 집계해 반환한다. " +
            "품목(itemCd)과 모델(modelCd)은 서로 다른 코드 체계이며, 등록되지 않은 코드는 404 로 알린다."
    )
    @GetMapping("/results")
    fun results(
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @Parameter(description = "집계 단위 — day|week|month") @RequestParam(required = false) unit: String?,
        @Parameter(description = "실적 품목 코드 정확 일치 — 예 D63A-S") @RequestParam(required = false) itemCd: String?,
        @Parameter(description = "제품 모델 코드 — 그 모델의 품목 전부. common/masters/products 의 code, 예 D63A") @RequestParam(required = false) modelCd: String?,
        @Parameter(description = "설비 코드 — 예 MT-007. 공정 코드가 아니다") @RequestParam(required = false) lineCd: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?
    ): ApiResponse<Map<String, Any?>> {
        val (data, meta, mask) = productionService.getResults(from, to, unit, itemCd, modelCd, lineCd, page, size)
        return ApiResponse.page(data, meta, mask.maskedKeys())
    }

    /**
     * 실적 추이 차트 (No.58)
     */
    @Operation(
        summary = "실적 추이 차트",
        description = "기간별 생산량·불량률·수율 추이를 반환한다. " +
            "필터는 실적 집계 조회(No.57)와 같다 — 화면이 두 API 를 같은 파라미터로 부른다."
    )
    @GetMapping("/results/trend")
    fun resultsTrend(
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) unit: String?,
        @Parameter(description = "실적 품목 코드 정확 일치 — 예 D63A-S") @RequestParam(required = false) itemCd: String?,
        @Parameter(description = "제품 모델 코드 — 그 모델의 품목 전부. common/masters/products 의 code, 예 D63A") @RequestParam(required = false) modelCd: String?,
        @Parameter(description = "설비 코드 — 예 MT-007. 공정 코드가 아니다") @RequestParam(required = false) lineCd: String?
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = productionService.getResultTrend(from, to, unit, itemCd, modelCd, lineCd)
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    /**
     * 실적 집계 내려받기
     */
    @Operation(summary = "실적 집계 내려받기", description = "실적 집계 결과를 엑셀·CSV 로 내려받는다.")
    @PostMapping("/results/export")
    fun resultsExport(
        @Valid @RequestBody(required = false) request: ExportFormatRequest?,
        @RequestParam(required = false) unit: String?,
        @Parameter(description = "실적 품목 코드 정확 일치 — 예 D63A-S") @RequestParam(required = false) itemCd: String?,
        @Parameter(description = "제품 모델 코드 — 그 모델의 품목 전부. common/masters/products 의 code, 예 D63A") @RequestParam(required = false) modelCd: String?,
        @Parameter(description = "설비 코드 — 예 MT-007. 공정 코드가 아니다") @RequestParam(required = false) lineCd: String?
    ): ResponseEntity<ByteArrayResource> {
        val format = request?.format ?: "xls"
        val (rows, mask) = productionService.getResultRowsForExport(
            request?.from, request?.to, unit, itemCd, modelCd, lineCd
        )

        downloadLogService.record(
            reportId = null,
            reportNm = "생산 실적 집계",
            menuId = MenuId.PROD_RESULT,
            format = format,
            scope = "from=${request?.from}, to=${request?.to}, unit=${unit ?: "day"}",
            rowCnt = rows.size,
            blindCnt = mask.maskedCount(),
            blindCells = mask.maskedKeys().associateWith { rows.size }
        )

        return exportService.export(
            format = format,
            fileName = "production_results_${exportService.timestamp()}",
            headers = listOf("기간", "투입수량", "양품수량", "불량수량", "불량률(%)", "수율(%)", "가동률(%)", "비가동(분)"),
            keys = listOf("period", "inputQty", "okQty", "ngQty", "defectRate", "yield", "uptimeRate", "downtimeMin"),
            rows = rows
        )
    }

    // =================================================================================
    // PR-03 / PR-04. 일일 생산현황 보고 · 이전 보고서
    // =================================================================================

    /**
     * 보고서 초안 조회 (No.59 — 전일 08:00 ~ 당일 08:00)
     */
    @Operation(summary = "보고서 초안 조회", description = "대상 일자의 일일 생산현황 보고 초안을 조회한다. 없으면 생성한다.")
    @GetMapping("/daily-reports/draft")
    fun dailyDraft(
        @RequestParam(required = false) targetDate: String?
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = dailyReportService.getDraft(targetDate)
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    /**
     * 보고서 초안 재생성 (No.60)
     */
    @Operation(summary = "보고서 초안 재생성", description = "MES 실적을 다시 집계해 새 버전 초안을 만든다.")
    @PostMapping("/daily-reports/draft/regenerate")
    fun dailyRegenerate(
        @Valid @RequestBody(required = false) request: ReportRegenerateRequest?
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(dailyReportService.regenerateDraft(request?.targetDate), "초안을 재생성했습니다.")

    /**
     * 보고서 항목 보정 (No.61)
     */
    @Operation(summary = "보고서 항목 보정", description = "AI 초안 항목을 사람이 보정한다.")
    @PutMapping("/daily-reports/{reportId}")
    fun dailyCorrect(
        @PathVariable reportId: Long,
        @Valid @RequestBody request: ReportCorrectionRequest
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(dailyReportService.correct(reportId, request), "보정이 반영되었습니다.")

    /**
     * 보고서 임시 저장 (No.62)
     */
    @Operation(summary = "보고서 임시 저장", description = "작성 중인 보고서를 임시 저장한다.")
    @PostMapping("/daily-reports/{reportId}/save")
    fun dailySave(
        @PathVariable reportId: Long,
        @Valid @RequestBody request: ReportCorrectionRequest
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(dailyReportService.save(reportId, request), "임시 저장되었습니다.")

    /**
     * 보고서 확정 (No.63 — 감사 로그 기록)
     */
    @Operation(summary = "보고서 확정", description = "보고서를 확정 상태로 전환한다.")
    @PostMapping("/daily-reports/{reportId}/confirm")
    fun dailyConfirm(@PathVariable reportId: Long): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(dailyReportService.confirm(reportId), "보고서가 확정되었습니다.")

    /**
     * 보고서 반려 (No.64)
     */
    @Operation(summary = "보고서 반려", description = "확정 요청된 보고서를 반려한다.")
    @PostMapping("/daily-reports/{reportId}/reject")
    fun dailyReject(
        @PathVariable reportId: Long,
        @Valid @RequestBody(required = false) request: ReasonRequest?
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(dailyReportService.reject(reportId, request?.reason), "보고서가 반려되었습니다.")

    /**
     * 보고서 생성 이력 (No.65)
     */
    @Operation(summary = "보고서 생성 이력", description = "보고서 생성·보정·확정 이력을 조회한다.")
    @GetMapping("/daily-reports/{reportId}/events")
    fun dailyEvents(@PathVariable reportId: Long): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(dailyReportService.getEvents(reportId))

    /**
     * 보고서 이력 조회 (No.66 — PR-04 이전 보고서)
     */
    @Operation(summary = "보고서 이력 조회", description = "기간별 일일 생산현황 보고 이력을 조회한다.")
    @GetMapping("/daily-reports")
    fun dailyHistory(
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) state: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?
    ): ApiResponse<Map<String, Any?>> {
        val (rows, meta) = dailyReportService.getHistory(from, to, state, page, size)
        return ApiResponse.page(mapOf("items" to rows), meta)
    }

    /**
     * 보고서 복제 (No.67)
     */
    @Operation(summary = "보고서 복제", description = "기존 보고서를 다른 일자로 복제한다.")
    @PostMapping("/daily-reports/{reportId}/copy")
    fun dailyCopy(
        @PathVariable reportId: Long,
        @Valid @RequestBody request: ReportCopyRequest
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(dailyReportService.copy(reportId, request.targetDate), "보고서를 복제했습니다.")

    // =================================================================================
    // PR-05. 비가동 관리
    // =================================================================================

    /**
     * 비가동 요약 (No.68)
     */
    @Operation(summary = "비가동 요약", description = "총 비가동 시간과 사유 등록/미등록 건수를 반환한다.")
    @GetMapping("/downtimes/summary")
    fun downtimeSummary(
        @RequestParam(required = false) date: String?
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(downtimeService.getSummary(date))

    /**
     * 비가동 이력 조회 (No.69)
     */
    @Operation(summary = "비가동 이력 조회", description = "설비·사유·등록 여부로 비가동 이력을 조회한다.")
    @GetMapping("/downtimes")
    fun downtimes(
        @RequestParam(required = false) date: String?,
        @RequestParam(required = false) eqptCd: String?,
        @RequestParam(required = false) reasonCd: String?,
        @Parameter(description = "사유 등록 여부") @RequestParam(required = false) registered: Boolean?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?
    ): ApiResponse<Map<String, Any?>> {
        val (rows, meta) = downtimeService.getDowntimes(date, eqptCd, reasonCd, registered, page, size)
        return ApiResponse.page(mapOf("items" to rows), meta)
    }

    /**
     * Agent 사유 후보 제안 (No.70)
     */
    @Operation(summary = "Agent 사유 후보 제안", description = "과거 이력을 근거로 비가동 사유 후보를 제안한다.")
    @GetMapping("/downtimes/reason-suggestion")
    fun reasonSuggestion(
        @RequestParam eqptCd: String,
        @Parameter(description = "정지 시각 (yyyy-MM-dd HH:mm:ss)") @RequestParam(required = false) stopAt: String?
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(downtimeService.getReasonSuggestions(eqptCd, stopAt))

    /**
     * 비가동 사유 등록 (No.71)
     */
    @Operation(summary = "비가동 사유 등록", description = "정지 구간에 비가동 사유를 등록한다.")
    @PostMapping("/downtimes")
    fun createDowntime(
        @Valid @RequestBody request: DowntimeCreateRequest
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(downtimeService.createDowntime(request), "비가동 사유가 등록되었습니다.")

    /**
     * 비가동 사유 수정 (No.72 — 감사 로그 기록)
     */
    @Operation(summary = "비가동 사유 수정", description = "등록된 비가동 사유를 수정한다.")
    @PutMapping("/downtimes/{downtimeId}")
    fun updateDowntime(
        @PathVariable downtimeId: Long,
        @Valid @RequestBody request: DowntimeUpdateRequest
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(downtimeService.updateDowntime(downtimeId, request), "비가동 사유가 수정되었습니다.")
}
