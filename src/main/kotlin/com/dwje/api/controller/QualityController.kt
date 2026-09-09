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
 * 품질관리 API 컨트롤러 (QC-01 ~ QC-02)
 *
 * ## 품질 보고서(QC-03)·보고서 양식 관리(QC-04) 는 제거되었다 (2026-09-04)
 * 고객이 준 보고서 자료 7장에 품질 보고서가 없고, 만들기로 한 보고서 6종
 * 어디에도 들어가지 않아 사용자 결정으로 걷어냈다.
 * 마스킹 해제 요청도 함께 내렸다 — 그 요청을 띄우는 화면이 없어졌다.
 * 되살리려면 `restore/20260904_문서관리제거/` 를 보라.
 * (`ax.tb_rpt_unmask_req` 테이블과 `UNMASK_STATE` 코드는 남아 있다)
 *
 * 불량 현황 조회 · AOI 판정 분석/예측 · 품질 보고서 · 보고서 양식 관리를 담당한다.
 */
@RestController
@RequestMapping("/api/v1/quality")
@Tag(name = "05. 품질관리")
class QualityController(
    private val qualityDefectService: QualityDefectService,
    private val aoiPredictionService: AoiPredictionService,
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
}
