package com.dwje.api.controller

import com.dwje.api.common.response.ApiResponse
import com.dwje.api.common.util.MenuId
import com.dwje.api.model.request.EvidenceImageRequest
import com.dwje.api.model.request.ExportFormatRequest
import com.dwje.api.model.request.QualityReportDraftRequest
import com.dwje.api.model.request.ReasonRequest
import com.dwje.api.model.request.ReportCorrectionRequest
import com.dwje.api.model.request.ReportFormRequest
import com.dwje.api.model.request.UnmaskRequest
import com.dwje.api.service.AoiPredictionService
import com.dwje.api.service.DownloadLogService
import com.dwje.api.service.ExportService
import com.dwje.api.service.QualityDefectService
import com.dwje.api.service.QualityReportService
import com.dwje.api.service.ReportFormService
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
 * 품질관리 API 컨트롤러 (QC-01 ~ QC-04)
 *
 * 불량 현황 조회 · AOI 판정 분석/예측 · 품질 보고서 · 보고서 양식 관리를 담당한다.
 */
@RestController
@RequestMapping("/api/v1/quality")
@Tag(name = "05. 품질관리")
class QualityController(
    private val qualityDefectService: QualityDefectService,
    private val aoiPredictionService: AoiPredictionService,
    private val qualityReportService: QualityReportService,
    private val reportFormService: ReportFormService,
    private val exportService: ExportService,
    private val downloadLogService: DownloadLogService
) {

    // =================================================================================
    // QC-01. 불량 현황 조회
    // =================================================================================

    /** 불량 현황 요약 (No.73) */
    @Operation(summary = "불량 현황 요약", description = "기간 불량 건수·불량률과 전기 대비 증감을 반환한다.")
    @GetMapping("/defects/summary")
    fun defectSummary(
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) processId: String?,
        @RequestParam(required = false) defectTypeCd: String?
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = qualityDefectService.getSummary(from, to, processId, defectTypeCd)
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    /** 불량 유형별 분포 (No.74) */
    @Operation(summary = "불량 유형별 분포", description = "불량 유형별 건수·구성비·전기 대비 증감을 반환한다.")
    @GetMapping("/defects/by-type")
    fun defectByType(
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) processId: String?
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = qualityDefectService.getByType(from, to, processId)
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    /** 라인별 불량률 (No.75) */
    @Operation(summary = "라인별 불량률", description = "설비별 불량 수량·불량률과 주 불량 유형을 반환한다.")
    @GetMapping("/defects/by-line")
    fun defectByLine(
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) processId: String?,
        @RequestParam(required = false, defaultValue = "5") topN: Int
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = qualityDefectService.getByLine(from, to, processId, topN)
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    // =================================================================================
    // QC-02. AOI 판정 분석·예측
    // =================================================================================

    /** 예측 요약 (No.76) */
    @Operation(summary = "예측 요약", description = "예측 불량률·임계 도달 예상·위험 LOT 수·모델 신뢰도를 반환한다.")
    @GetMapping("/aoi/prediction/summary")
    fun predictionSummary(
        @Parameter(description = "대상 공정") @RequestParam(required = false) target: String?,
        @Parameter(description = "예측 구간 (예: 8h)") @RequestParam(required = false) horizon: String?,
        @Parameter(description = "학습 구간 (예: 72h)") @RequestParam(required = false) trainPeriod: String?
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = aoiPredictionService.getPredictionSummary(target, horizon, trainPeriod)
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    /** 불량률 추이·예측 밴드 (No.77) */
    @Operation(summary = "불량률 추이·예측 밴드", description = "관측 구간과 예측 구간의 추정선·신뢰 밴드를 반환한다.")
    @GetMapping("/aoi/prediction/trend-band")
    fun trendBand(
        @RequestParam(required = false) target: String?,
        @RequestParam(required = false) horizon: String?
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = aoiPredictionService.getTrendBand(target, horizon)
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    /** 설비별 위험 예측·권고 (No.78) */
    @Operation(summary = "설비별 위험 예측·권고", description = "설비별 예측 불량률·임계 도달 예상 시간·조치 권고를 반환한다.")
    @GetMapping("/aoi/prediction/equipment-risk")
    fun equipmentRisk(
        @RequestParam(required = false) target: String?,
        @RequestParam(required = false) horizon: String?
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = aoiPredictionService.getEquipmentRisk(target, horizon)
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    /** 출하 전 위험 LOT (No.79) */
    @Operation(summary = "출하 전 위험 LOT", description = "출하 예정 LOT 중 LRR 위험이 높은 건을 반환한다.")
    @GetMapping("/aoi/prediction/lot-risk")
    fun lotRisk(
        @RequestParam(required = false) target: String?
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = aoiPredictionService.getLotRisk(target)
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    /** 잔여 시간 추가 발생 추정 (No.80) */
    @Operation(summary = "잔여 시간 추가 발생 추정", description = "예측 구간 동안 추가 발생할 불량 수량을 추정한다.")
    @GetMapping("/aoi/prediction/remaining-estimate")
    fun remainingEstimate(
        @RequestParam(required = false) target: String?,
        @RequestParam(required = false) horizon: String?
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = aoiPredictionService.getRemainingEstimate(target, horizon)
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    /** AOI 판정 드리프트 (No.81) */
    @Operation(summary = "AOI 판정 드리프트", description = "AOI 검사기별 판정 기준 이동량과 과검·미검 추정치를 반환한다.")
    @GetMapping("/aoi/inspector-drift")
    fun inspectorDrift(
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = aoiPredictionService.getInspectorDrift(from, to)
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    /** 불량 유형 구성 변화 (No.82) */
    @Operation(summary = "불량 유형 구성 변화", description = "기준일 구성비를 직전 N주 평균과 비교한다.")
    @GetMapping("/aoi/defect-type-shift")
    fun defectTypeShift(
        @RequestParam(required = false) date: String?,
        @RequestParam(required = false, defaultValue = "4") baseWeeks: Int
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = aoiPredictionService.getDefectTypeShift(date, baseWeeks)
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    /** 예측 재산출 (No.83) */
    @Operation(summary = "예측 재산출", description = "예측을 재산출하고 Agent 실행 이력에 기록한다.")
    @PostMapping("/aoi/prediction/recalculate")
    fun recalculate(
        @RequestParam(required = false) target: String?,
        @RequestParam(required = false) horizon: String?,
        @RequestParam(required = false) trainPeriod: String?
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(aoiPredictionService.recalculate(target, horizon, trainPeriod), "예측을 재산출했습니다.")

    /** 추정 근거·모델 조회 (No.84) */
    @Operation(summary = "추정 근거·모델 조회", description = "사용 중인 추정 방식·학습 구간·특징·검증 결과·한계를 반환한다.")
    @GetMapping("/aoi/prediction/basis")
    fun predictionBasis(): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(aoiPredictionService.getBasis())

    // =================================================================================
    // QC-03. 품질 보고서
    // =================================================================================

    /** 품질 보고서 초안 생성 (No.85) */
    @Operation(summary = "품질 보고서 초안 생성", description = "양식 정의에 따라 초안을 생성하고 MES 항목을 자동 기입한다.")
    @PostMapping("/reports/draft")
    fun createDraft(
        @Valid @RequestBody request: QualityReportDraftRequest
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(qualityReportService.createDraft(request), "초안을 생성했습니다.")

    /** 품질 보고서 조회 (No.86) */
    @Operation(summary = "품질 보고서 조회", description = "데이터 권한과 고객사 공개 정책을 적용해 보고서를 반환한다.")
    @GetMapping("/reports/{reportId}")
    fun getReport(@PathVariable reportId: Long): ApiResponse<Map<String, Any?>> {
        val (data, mask) = qualityReportService.getReport(reportId)
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    /** 자동 기입 현황 조회 (No.87) */
    @Operation(summary = "자동 기입 현황 조회", description = "항목별 기입 출처(MES/AI/수기)와 보정 여부를 반환한다.")
    @GetMapping("/reports/{reportId}/autofill-status")
    fun autofillStatus(@PathVariable reportId: Long): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(qualityReportService.getAutofillStatus(reportId))

    /** 마스킹 적용 내역 (No.88) */
    @Operation(summary = "마스킹 적용 내역", description = "적용된 마스킹 규칙과 대상 항목을 반환한다.")
    @GetMapping("/reports/{reportId}/masking")
    fun masking(@PathVariable reportId: Long): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(qualityReportService.getMaskingDetail(reportId))

    /** 마스킹 해제 요청 (No.89) */
    @Operation(summary = "마스킹 해제 요청", description = "마스킹 항목의 열람 해제를 요청하고 감사 로그에 기록한다.")
    @PostMapping("/reports/{reportId}/unmask-request")
    fun unmaskRequest(
        @PathVariable reportId: Long,
        @Valid @RequestBody request: UnmaskRequest
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(qualityReportService.requestUnmask(reportId, request), "해제 요청이 접수되었습니다.")

    /** 증빙 이미지 후보 조회 (No.90) */
    @Operation(summary = "증빙 이미지 후보 조회", description = "보고서에 첨부 가능한 증빙 이미지 후보를 반환한다.")
    @GetMapping("/reports/{reportId}/evidence-images")
    fun evidenceImageCandidates(
        @PathVariable reportId: Long,
        @Parameter(description = "선정 기준 — ng|lot|숫자") @RequestParam(required = false) criteria: String?
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(qualityReportService.getEvidenceImageCandidates(reportId, criteria))

    /** 증빙 이미지 첨부 (No.91) */
    @Operation(summary = "증빙 이미지 첨부", description = "선택한 증빙 이미지를 보고서에 첨부한다.")
    @PostMapping("/reports/{reportId}/evidence-images")
    fun attachEvidenceImages(
        @PathVariable reportId: Long,
        @Valid @RequestBody request: EvidenceImageRequest
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(qualityReportService.attachEvidenceImages(reportId, request), "이미지를 첨부했습니다.")

    /** 보고서 임시 저장 (No.92) */
    @Operation(summary = "보고서 임시 저장", description = "작성 중인 품질 보고서를 임시 저장한다.")
    @PutMapping("/reports/{reportId}")
    fun saveReport(
        @PathVariable reportId: Long,
        @Valid @RequestBody request: ReportCorrectionRequest
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(qualityReportService.save(reportId, request), "임시 저장되었습니다.")

    /** 보고서 확정 (No.93) */
    @Operation(summary = "보고서 확정", description = "품질 보고서를 확정한다.")
    @PostMapping("/reports/{reportId}/confirm")
    fun confirmReport(@PathVariable reportId: Long): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(qualityReportService.confirm(reportId), "보고서가 확정되었습니다.")

    /** 보고서 반려 (No.94) */
    @Operation(summary = "보고서 반려", description = "품질 보고서를 반려한다.")
    @PostMapping("/reports/{reportId}/reject")
    fun rejectReport(
        @PathVariable reportId: Long,
        @Valid @RequestBody(required = false) request: ReasonRequest?
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(qualityReportService.reject(reportId, request?.reason), "보고서가 반려되었습니다.")

    /** 보고서 초안 재생성 (No.95) */
    @Operation(summary = "보고서 초안 재생성", description = "자동 기입 항목만 다시 채우고 보정 항목은 보존한다.")
    @PostMapping("/reports/{reportId}/regenerate")
    fun regenerateReport(@PathVariable reportId: Long): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(qualityReportService.regenerate(reportId), "초안을 재생성했습니다.")

    /** 보고서 출력 (No.96) */
    @Operation(summary = "보고서 출력", description = "품질 보고서를 엑셀·CSV 로 출력한다. blind 항목은 제외한다.")
    @PostMapping("/reports/{reportId}/export")
    fun exportReport(
        @PathVariable reportId: Long,
        @Valid @RequestBody(required = false) request: ExportFormatRequest?
    ): ResponseEntity<ByteArrayResource> {
        val format = request?.format ?: "xls"
        val (doc, rows, mask) = qualityReportService.getExportRows(reportId)

        downloadLogService.record(
            reportId = doc["reportDefId"] as? String,
            reportNm = (doc["title"] as? String) ?: "품질 보고서",
            menuId = MenuId.QC_REPORT,
            format = format,
            scope = "reportId=$reportId",
            rowCnt = rows.size,
            blindCnt = mask.maskedCount(),
            blindCells = mask.maskedKeys().associateWith { rows.size }
        )

        return exportService.export(
            format = format,
            fileName = "quality_report_${reportId}_${exportService.timestamp()}",
            headers = listOf("섹션", "항목", "값", "기입출처"),
            keys = listOf("section", "field", "value", "origin"),
            rows = rows
        )
    }

    /** 품질 보고서 이력 (No.97) */
    @Operation(summary = "품질 보고서 이력", description = "기간·양식·상태별 품질 보고서 이력을 조회한다.")
    @GetMapping("/reports")
    fun reportHistory(
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) formId: Int?,
        @RequestParam(required = false) state: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?
    ): ApiResponse<Map<String, Any?>> {
        val (rows, meta) = qualityReportService.getHistory(from, to, formId, state, page, size)
        return ApiResponse.page(mapOf("items" to rows), meta)
    }

    // =================================================================================
    // QC-04. 보고서 양식 관리
    // =================================================================================

    /** 양식 목록 조회 (No.98) */
    @Operation(summary = "양식 목록 조회", description = "등록된 보고서 양식 목록을 반환한다.")
    @GetMapping("/report-forms")
    fun forms(): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(reportFormService.getForms())

    /** 양식 등록 (No.99) */
    @Operation(summary = "양식 등록", description = "보고서 양식과 항목 정의를 등록한다.")
    @PostMapping("/report-forms")
    fun createForm(@Valid @RequestBody request: ReportFormRequest): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(reportFormService.createForm(request), "양식이 등록되었습니다.")

    /** 양식 수정 (No.100) */
    @Operation(summary = "양식 수정", description = "양식 정보를 수정한다. 항목 정의 변경 시 파서 버전이 올라간다.")
    @PutMapping("/report-forms/{formId}")
    fun updateForm(
        @PathVariable formId: Int,
        @Valid @RequestBody request: ReportFormRequest
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(reportFormService.updateForm(formId, request), "양식이 수정되었습니다.")

    /** 양식 항목 정의 조회 (No.101) */
    @Operation(summary = "양식 항목 정의 조회", description = "양식의 항목 코드·필수 여부·연결 데이터 항목을 반환한다.")
    @GetMapping("/report-forms/{formId}/fields")
    fun formFields(@PathVariable formId: Int): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(reportFormService.getFormFields(formId))
}
