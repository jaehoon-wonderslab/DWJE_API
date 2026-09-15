package com.dwje.api.controller

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.response.ApiResponse
import com.dwje.api.common.util.DateUtils
import com.dwje.api.common.util.ProductionMonitorPeriod
import com.dwje.api.common.util.MenuId
import com.dwje.api.model.request.DailyReportRowsRequest
import com.dwje.api.model.request.DayTargetRequest
import com.dwje.api.model.request.DowntimeCreateRequest
import com.dwje.api.model.request.DowntimeUpdateRequest
import com.dwje.api.model.request.ExportFormatRequest
import com.dwje.api.model.request.ReasonRequest
import com.dwje.api.model.request.ReportCopyRequest
import com.dwje.api.model.request.ReportCorrectionRequest
import com.dwje.api.model.request.ReportRegenerateRequest
import com.dwje.api.service.DailyReportService
import com.dwje.api.service.DayTargetService
import com.dwje.api.service.DowntimeService
import com.dwje.api.service.DownloadLogService
import com.dwje.api.service.ExportService
import com.dwje.api.service.ProductionResultScreenWorkbook
import com.dwje.api.service.ProductionService
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
    private val dayTargetService: DayTargetService,
    private val downtimeService: DowntimeService,
    private val exportService: ExportService,
    private val downloadLogService: DownloadLogService,
    private val resultScreenWorkbook: ProductionResultScreenWorkbook
) {

    // =================================================================================
    // PR-01. 생산 모니터링
    // =================================================================================

    /**
     * 모니터링 요약 (No.55)
     *
     * @param processId 공정 코드
     * @param targetDate 기준일(YYYY-MM-DD). 지정 시 해당 일자 생산 실적 전체를 집계한다.
     */
    @Operation(summary = "모니터링 요약", description = "가동·경고·정지 설비 수와 시간당 처리량을 반환한다.")
    @GetMapping("/monitor/summary")
    fun monitorSummary(
        @RequestParam(required = false) processId: String?,
        @Parameter(description = "기준일(YYYY-MM-DD) — 지정 시 해당일 00:00 ~ 익일 00:00")
        @RequestParam(required = false) targetDate: String?
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = productionService.getMonitorSummary(processId, targetDate)
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
        @Parameter(description = "기준일(YYYY-MM-DD) — 지정 시 해당일 00:00 ~ 익일 00:00")
        @RequestParam(required = false) targetDate: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?
    ): ApiResponse<Map<String, Any?>> {
        val (rows, meta, mask) =
            productionService.getMonitorEquipments(lineRange, model, processId, state, targetDate, page, size)
        val data = linkedMapOf<String, Any?>("items" to rows)
        targetDate?.takeIf { it.isNotBlank() }?.let {
            val target = DateUtils.parseDate(it, "targetDate")
            data["targetDate"] = target.format(DateUtils.DATE)
            val period = ProductionMonitorPeriod.of(target)
            data["periodFrom"] = period.from.format(DateUtils.DATETIME)
            data["periodTo"] = period.toExclusive.format(DateUtils.DATETIME)
        }
        return ApiResponse.page(data, meta, mask.maskedKeys())
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
    @Operation(
        summary = "실적 집계 내려받기",
        description = "실적 집계 결과를 엑셀·CSV 로 내려받는다. " +
            "scope=screen 이면 실적 집계·조회 화면 전체(조회 요약 · 일별 추이+차트 · 일자→제품→설비 트리)를 " +
            "xlsx 한 파일로 내려받는다 — 이때 unit 은 day 만, 제품·설비는 전체이며 페이지 제한이 없다."
    )
    @PostMapping("/results/export")
    fun resultsExport(
        @Valid @RequestBody(required = false) request: ExportFormatRequest?,
        @Parameter(description = "내려받기 범위 — 비우면 집계 표 한 장, screen 이면 화면 전체 통합 문서(xlsx)")
        @RequestParam(required = false) scope: String?,
        @RequestParam(required = false) unit: String?,
        @Parameter(description = "실적 품목 코드 정확 일치 — 예 D63A-S") @RequestParam(required = false) itemCd: String?,
        @Parameter(description = "제품 모델 코드 — 그 모델의 품목 전부. common/masters/products 의 code, 예 D63A") @RequestParam(required = false) modelCd: String?,
        @Parameter(description = "설비 코드 — 예 MT-007. 공정 코드가 아니다") @RequestParam(required = false) lineCd: String?
    ): ResponseEntity<ByteArrayResource> {
        when (scope?.trim()?.lowercase()) {
            null, "" -> Unit
            "screen" -> return resultsExportScreen(request, unit)
            else -> throw InvalidParameterException("scope 는 비우거나 screen 만 허용합니다. [scope=$scope]", "scope")
        }

        val format = request?.format ?: "xls"
        val (rows, mask) = productionService.getResultRowsForExport(
            request?.from, request?.to, unit, itemCd, modelCd, lineCd
        )

        // 파일을 먼저 만든다 — 크기를 이력에 남겨야 하고, 만들다 실패하면
        // 'DONE' 으로 기록되는 것도 막힌다. (문서를 저장하지 않으므로 이 이력이 유일한 기록이다)
        val response = exportService.export(
            format = format,
            fileName = "production_results_${exportService.timestamp()}",
            headers = listOf("기간", "투입수량", "양품수량", "불량수량", "불량률(%)", "수율(%)", "가동률(%)", "비가동(분)"),
            keys = listOf("period", "inputQty", "okQty", "ngQty", "defectRate", "yield", "uptimeRate", "downtimeMin"),
            rows = rows
        )

        downloadLogService.record(
            reportId = null,
            reportNm = "생산 실적 집계",
            menuId = MenuId.PROD_RESULT,
            format = format,
            scope = "from=${request?.from}, to=${request?.to}, unit=${unit ?: "day"}",
            rowCnt = rows.size,
            blindCnt = mask.maskedCount(),
            blindCells = mask.maskedKeys().associateWith { rows.size },
            params = mapOf(
                "from" to request?.from,
                "to" to request?.to,
                "unit" to (unit ?: "day"),
                "itemCd" to itemCd,
                "modelCd" to modelCd,
                "lineCd" to lineCd,
                "format" to format
            ),
            fileSize = response.body?.contentLength()
        )

        return response
    }

    /**
     * 실적 집계·조회 화면 전체 내려받기 (scope=screen)
     *
     * 계약 — `POST /results/export?scope=screen&unit=day` + 본문 `{from, to, format:"xlsx"}`.
     * 응답은 xlsx 바이너리(`Content-Disposition: attachment; filename*=UTF-8''실적_집계_전체_{from}_{to}.xlsx`).
     * 화면은 일별·전체 제품으로 고정이므로 unit 은 day 만, csv 는 받지 않는다(시트 3장·차트를 담을 수 없다).
     */
    private fun resultsExportScreen(request: ExportFormatRequest?, unit: String?): ResponseEntity<ByteArrayResource> {
        val format = (request?.format ?: "xlsx").trim().lowercase()
        if (format !in SCREEN_EXPORT_FORMATS) {
            throw InvalidParameterException("scope=screen 은 xlsx 만 지원합니다. [format=$format]", "format")
        }
        if (!unit.isNullOrBlank() && unit.trim().lowercase() != "day") {
            throw InvalidParameterException("scope=screen 은 일별(unit=day)만 지원합니다. [unit=$unit]", "unit")
        }

        val data = productionService.getResultScreenExport(request?.from, request?.to)

        // 파일을 먼저 만든다 — 크기를 이력에 남겨야 하고, 만들다 실패하면 'DONE' 으로 기록되는 것도 막힌다.
        val bytes = resultScreenWorkbook.build(data)
        val fileName = "실적_집계_전체_${data.from}_${data.to}.xlsx"
        val response = exportService.xlsx(bytes, fileName)

        downloadLogService.record(
            reportId = null,
            reportNm = "생산 실적 집계(화면 전체)",
            menuId = MenuId.PROD_RESULT,
            format = "xlsx",
            // scope_desc 는 100자 컬럼이다 — 조건 전문은 params 에 있다.
            scope = "screen from=${data.from}, to=${data.to}, unit=day",
            rowCnt = data.rows.size,
            blindCnt = data.maskedFields.size,
            blindCells = data.maskedFields.associateWith { data.rows.size },
            fileNm = fileName,
            params = mapOf(
                "scope" to "screen",
                "from" to data.from.toString(),
                "to" to data.to.toString(),
                "unit" to "day",
                "modelCd" to null,
                "lineCd" to null,
                "format" to "xlsx",
                "sheets" to listOf(
                    ProductionResultScreenWorkbook.SHEET_SUMMARY,
                    ProductionResultScreenWorkbook.SHEET_TREND,
                    ProductionResultScreenWorkbook.SHEET_TREE
                ),
                "dayRows" to data.countOf(1),
                "productRows" to data.countOf(2),
                "equipmentRows" to data.countOf(3)
            ),
            fileSize = bytes.size.toLong()
        )

        return response
    }

    private companion object {
        /** scope=screen 이 받는 형식 — 전부 xlsx 로 낸다 */
        val SCREEN_EXPORT_FORMATS = setOf("xlsx", "xls", "excel")
    }

    // =================================================================================
    // PR-03. 일일 생산현황 보고 (문서 관리 없음 — 조회 조건으로 매번 만든다)
    // =================================================================================

    /**
     * 보고서 양식 본문 조회 (제품 × 공정 — 전일 20:00 ~ 당일 08:00)
     *
     * 문서를 저장하지 않으므로 초안·버전·확정이 없다. 대상일만 보내면 매번 집계한다.
     */
    @Operation(
        summary = "보고서 양식 본문 조회",
        description = "일일 생산현황 보고 양식의 제품 × 공정 본문을 조회한다. 목표 수량은 출처가 없어 저장값이 없으면 null 이다."
    )
    @GetMapping("/daily-reports/sheet")
    fun dailySheet(
        @RequestParam(required = false) targetDate: String?,
        @RequestParam(required = false) processId: String?
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = dailyReportService.getSheet(targetDate, processId)
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    /**
     * 아침회의 결과 저장 (제품별 일목표·판정·담당·기한)
     *
     * 문서가 없어 키가 대상일이다. 예전 `/{reportId}/rows` 를 대체한다.
     */
    @Operation(
        summary = "아침회의 결과 저장",
        description = "아침회의에서 정한 제품별 일목표·판정·담당·기한을 대상일 기준으로 저장한다. 보낸 제품만 갱신한다."
    )
    @PostMapping("/daily-reports/rows")
    fun dailySaveRows(
        @Valid @RequestBody request: DailyReportRowsRequest
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(
            dailyReportService.saveRows(request.targetDate, request.rows),
            "회의 결과를 저장했습니다."
        )

    // =================================================================================
    // PR-03-1. 제품·공정별 일목표 마스터
    //
    // 목표는 적용일부터 다음 적용일 전까지 유효하다. 일일 보고에서 작성자가 그날만
    // 목표를 달리 잡으면 그 값이 마스터를 덮어쓴다 — 저장값 > 마스터 > null.
    // =================================================================================

    /** 일목표 조회 */
    @Operation(
        summary = "일목표 조회",
        description = "제품·공정별 일목표를 조회한다. date 를 주면 그 날짜에 유효한 한 건씩만 반환한다."
    )
    @GetMapping("/day-targets")
    fun dayTargets(
        @RequestParam(required = false) product: String?,
        @RequestParam(required = false) processId: String?,
        @Parameter(description = "이 날짜에 유효한 목표만 조회. 미지정 시 전 이력")
        @RequestParam(required = false) date: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?
    ): ApiResponse<Map<String, Any?>> {
        val (rows, meta) = dayTargetService.getTargets(product, processId, date, page, size)
        return ApiResponse.page(mapOf("items" to rows), meta)
    }

    /** 일목표 등록 */
    @Operation(summary = "일목표 등록", description = "제품·공정·적용일 기준으로 일목표를 등록한다.")
    @PostMapping("/day-targets")
    fun createDayTarget(
        @Valid @RequestBody request: DayTargetRequest
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(dayTargetService.create(request), "일목표를 등록했습니다.")

    /** 일목표 수정 */
    @Operation(
        summary = "일목표 수정",
        description = "적용일·수량·비고를 수정한다. 제품·공정은 바꿀 수 없다 — 바꿔야 하면 지우고 새로 등록한다."
    )
    @PutMapping("/day-targets/{targetId}")
    fun updateDayTarget(
        @PathVariable targetId: Long,
        @Valid @RequestBody request: DayTargetRequest
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(dayTargetService.update(targetId, request), "일목표를 수정했습니다.")

    /** 일목표 삭제 */
    @Operation(summary = "일목표 삭제", description = "일목표 한 건을 삭제한다.")
    @DeleteMapping("/day-targets/{targetId}")
    fun deleteDayTarget(@PathVariable targetId: Long): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(dayTargetService.delete(targetId), "일목표를 삭제했습니다.")

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
