package com.dwje.api.controller

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.response.ApiResponse
import com.dwje.api.common.security.UserContext
import com.dwje.api.common.util.DateUtils
import com.dwje.api.common.util.MenuId
import com.dwje.api.model.request.EvidenceImageRequest
import com.dwje.api.model.request.ExportFormatRequest
import com.dwje.api.model.request.QualityDefectExportRequest
import com.dwje.api.model.request.QualityReportDraftRequest
import com.dwje.api.model.request.ReasonRequest
import com.dwje.api.model.request.ReportCorrectionRequest
import com.dwje.api.model.request.ReportFormRequest
import com.dwje.api.model.request.UnmaskRequest
import com.dwje.api.service.AoiDefectService
import com.dwje.api.service.AoiPredictionService
import com.dwje.api.service.DownloadLogService
import com.dwje.api.service.ExportService
import com.dwje.api.service.DefectExportConditions
import com.dwje.api.service.DefectTreeLevel
import com.dwje.api.service.QualityDefectService
import com.dwje.api.service.QualityDefectWorkbook
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
    private val aoiDefectService: AoiDefectService,
    private val exportService: ExportService,
    private val downloadLogService: DownloadLogService,
    private val defectWorkbook: QualityDefectWorkbook
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

    /** 라인별 불량률 + 설비별 불량 유형 내역 (No.75) */
    @Operation(
        summary = "라인별 불량률",
        description = "설비별 정상·불량 수량, 불량률, 주 불량 유형과 그 설비의 불량 유형 내역(children)을 반환한다. " +
            "topN 을 비우거나 0 이면 전체 설비(불량 수량 내림차순). 비용은 기간 길이에 비례하고 N 과 무관하다."
    )
    @GetMapping("/defects/by-line")
    fun defectByLine(
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) processId: String?,
        @Parameter(description = "상위 조회 대수. 비우거나 0 이면 전체 설비, 양수는 그 수만큼(상한 2000)")
        @RequestParam(required = false) topN: Int?
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = qualityDefectService.getByLine(from, to, processId, topN)
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    /**
     * 불량 상세 분해 트리 (QC-01 드릴다운)
     */
    @Operation(
        summary = "불량 상세 분해 트리",
        description = "정상·불량 수량과 불량률을 공정 > 제품 > 설비 > 불량 유형 트리로 반환한다(children 중첩). " +
            "levels 로 순서를 바꿀 수 있고(예 wc,eqpt,item,defect) defect 는 마지막에만 온다. " +
            "모든 단계 행에 plantCd/plantNm·wcCd/wcNm·itemCd/itemNm·eqptCd/eqptNm·defectCd/defectNm 열이 있고 그 단계까지 확정된 값만 채운다. " +
            "상위 수량은 하위 합과 같다. 유형 행은 ngQty 와 ratio(상위 ngQty 대비 %)만 있다. 전량 응답 — 30일 약 3천 노드."
    )
    @GetMapping("/defects/tree")
    fun defectTree(
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) processId: String?,
        @Parameter(description = "단계 순서 — wc,item,eqpt,defect 중 골라 콤마로. 비우면 wc,item,eqpt,defect")
        @RequestParam(required = false) levels: String?
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = qualityDefectService.getDefectTree(from, to, processId, levels)
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    /**
     * 제품별 불량 현황 트리 (QC-01 제품별 불량 현황 카드)
     */
    @Operation(
        summary = "제품별 불량 현황 트리",
        description = "제품 > 불량 유형 > 설비(라인) > 공정 트리를 반환한다(children 중첩). " +
            "제품 행은 원장 총량·정상·불량·불량률과 전체 불량 중 비중(ratio). 유형 이하 행의 totalQty 는 그 단계의 원장 분모라 형제끼리 더하면 안 되고, " +
            "okQty 는 null, ngQty 는 안분 불량(상위 = 하위 합, 유형 미상 포함), ratio 는 상위 불량 대비 비중이다."
    )
    @GetMapping("/defects/by-product")
    fun defectByProduct(
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) processId: String?
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = qualityDefectService.getByProduct(from, to, processId)
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    /**
     * 불량 유형별 분포 내려받기 (xlsx)
     *
     * 계약은 실적 집계 화면 전체 내려받기(`production/results/export?scope=screen`)와 같다 —
     * xlsx 바이너리 + `Content-Disposition` 파일명 + 다운로드 이력 + 권한 마스킹.
     */
    @Operation(
        summary = "불량 유형별 분포 내려받기",
        description = "불량 유형별 분포(by-type)를 조회 조건 시트와 함께 xlsx 로 내려받는다. format 은 xlsx 만 받는다."
    )
    @PostMapping("/defects/by-type/export")
    fun defectByTypeExport(
        @Valid @RequestBody(required = false) request: QualityDefectExportRequest?
    ): ResponseEntity<ByteArrayResource> {
        val req = request ?: QualityDefectExportRequest()
        requireXlsx(req.format)
        val (fromDate, toDate) = DateUtils.periodOf(req.from, req.to)
        val (data, mask) = qualityDefectService.getByType(req.from, req.to, req.processId)
        val items = itemsOf(data)

        val bytes = defectWorkbook.byType(conditionsOf(req, fromDate, toDate, mask.maskedKeys()), items)
        val fileName = "불량_유형별_분포_${fromDate}_${toDate}.xlsx"
        val response = exportService.xlsx(bytes, fileName)

        recordDefectExport("불량 유형별 분포", req, fromDate, toDate, items.size, mask, fileName, bytes.size)
        return response
    }

    /**
     * 설비별 불량률 내려받기 (xlsx) — 설비별 표 + 설비별 불량 유형 상세 시트
     */
    @Operation(
        summary = "설비별 불량률 내려받기",
        description = "설비별 불량률(by-line, 전체 설비)과 설비별 불량 유형 상세를 조회 조건 시트와 함께 xlsx 로 내려받는다. format 은 xlsx 만 받는다."
    )
    @PostMapping("/defects/by-line/export")
    fun defectByLineExport(
        @Valid @RequestBody(required = false) request: QualityDefectExportRequest?
    ): ResponseEntity<ByteArrayResource> {
        val req = request ?: QualityDefectExportRequest()
        requireXlsx(req.format)
        val (fromDate, toDate) = DateUtils.periodOf(req.from, req.to)
        // 내려받기는 화면과 같이 전체 설비다. 불량 상세 분해 트리도 같은 조건으로 함께 담는다.
        val (data, mask) = qualityDefectService.getByLine(req.from, req.to, req.processId, null)
        val items = itemsOf(data)
        val levels = DefectTreeLevel.parse(req.levels)
        val (tree, _) = qualityDefectService.getDefectTree(req.from, req.to, req.processId, req.levels)

        val bytes = defectWorkbook.byLine(
            conditionsOf(req, fromDate, toDate, mask.maskedKeys()), items, levels, itemsOf(tree)
        )
        val fileName = "설비별_불량률_${fromDate}_${toDate}.xlsx"
        val response = exportService.xlsx(bytes, fileName)

        recordDefectExport("설비별 불량률", req, fromDate, toDate, items.size, mask, fileName, bytes.size)
        return response
    }

    private fun requireXlsx(format: String?) {
        val normalized = (format ?: "xlsx").trim().lowercase()
        if (normalized !in setOf("xlsx", "xls", "excel")) {
            throw InvalidParameterException("불량 현황 내려받기는 xlsx 만 지원합니다. [format=$format]", "format")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun itemsOf(data: Map<String, Any?>): List<Map<String, Any?>> =
        (data["items"] as? List<Map<String, Any?>>).orEmpty()

    private fun conditionsOf(
        req: QualityDefectExportRequest,
        fromDate: java.time.LocalDate,
        toDate: java.time.LocalDate,
        maskedFields: List<String>
    ): DefectExportConditions = DefectExportConditions(
        from = fromDate,
        to = toDate,
        processId = req.processId?.trim()?.takeIf { it.isNotEmpty() },
        defectTypeCd = req.defectTypeCd?.trim()?.takeIf { it.isNotEmpty() },
        maskedFields = maskedFields,
        downloadedBy = UserContext.currentOrNull()?.let { "${it.userName}(${it.userId})" }
    )

    /** 파일을 만든 뒤에 이력을 남긴다 — 만들다 실패하면 DONE 으로 기록되지 않는다. */
    private fun recordDefectExport(
        reportNm: String,
        req: QualityDefectExportRequest,
        fromDate: java.time.LocalDate,
        toDate: java.time.LocalDate,
        rowCnt: Int,
        mask: com.dwje.api.common.util.MaskingSupport,
        fileName: String,
        fileSize: Int
    ) {
        downloadLogService.record(
            reportId = null,
            reportNm = reportNm,
            menuId = MenuId.QC_DEFECT,
            format = "xlsx",
            // scope_desc 는 100자 컬럼이다 — 조건 전문은 params 에 있다.
            scope = "from=$fromDate, to=$toDate, processId=${req.processId ?: "전체"}".take(100),
            rowCnt = rowCnt,
            blindCnt = mask.maskedCount(),
            blindCells = mask.maskedKeys().associateWith { rowCnt },
            fileNm = fileName,
            params = mapOf(
                "from" to fromDate.toString(),
                "to" to toDate.toString(),
                "processId" to req.processId,
                "defectTypeCd" to req.defectTypeCd,
                "levels" to req.levels,
                "format" to "xlsx"
            ),
            fileSize = fileSize.toLong()
        )
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

    // =================================================================================
    // AOI 판정 분석 — 불량 상세·이미지 (2026-09-10 요구 9)
    // =================================================================================

    /** AOI 불량 목록 */
    @Operation(
        summary = "AOI 불량 목록",
        description = "AOI 설비가 불량으로 찍은 라벨 이력을 id·판정 일시로 분류한다. 기간 미지정 시 to=오늘, from=7일 전. " +
            "defectId = plant-wc-lot-serial. defectTypeCd/Nm 은 불량 이력이 붙은 행에만 있다(AOI 는 대부분 null). imageCnt 는 매핑된 NAS 이미지 수."
    )
    @GetMapping("/aoi/defects")
    fun aoiDefects(
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @Parameter(description = "AOI 설비 코드 — /aoi/defects/equipments 참고") @RequestParam(required = false) eqptCd: String?,
        @RequestParam(required = false) defectTypeCd: String?,
        @RequestParam(required = false) lotNo: String?,
        @Parameter(description = "작업장(공정) 코드") @RequestParam(required = false) processId: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?
    ): ApiResponse<Map<String, Any?>> {
        val (data, meta, mask) = aoiDefectService.getDefects(from, to, eqptCd, defectTypeCd, lotNo, processId, page, size)
        return ApiResponse.page(data, meta, mask.maskedKeys())
    }

    /** AOI 설비 목록 (필터용) — {defectId} 매핑보다 먼저 선언한다. */
    @Operation(summary = "AOI 설비 목록", description = "설비 마스터에서 모델명·설비명에 AOI 가 들어간 설비.")
    @GetMapping("/aoi/defects/equipments")
    fun aoiEquipments(): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(aoiDefectService.getEquipments())

    /** AOI 불량 상세 + 이미지 */
    @Operation(
        summary = "AOI 불량 상세·이미지",
        description = "불량 한 건의 상세, 불량 유형 내역(defects[]), NAS 이미지 목록(images[]). " +
            "images[].url 은 서명 토큰이 붙은 프록시 주소(유효 15분), thumbUrl 은 160px 썸네일. available 은 NAS 에 파일이 실제로 있는지."
    )
    @GetMapping("/aoi/defects/{defectId}")
    fun aoiDefect(@PathVariable defectId: String): ApiResponse<Map<String, Any?>> {
        val (data, mask) = aoiDefectService.getDefect(defectId)
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    /** 추정 근거·모델 조회 (No.84) */
    @Operation(summary = "추정 근거·모델 조회", description = "사용 중인 추정 방식·학습 구간·특징·검증 결과·한계를 반환한다.")
    @GetMapping("/aoi/prediction/basis")
    fun predictionBasis(): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(aoiPredictionService.getBasis())
}
