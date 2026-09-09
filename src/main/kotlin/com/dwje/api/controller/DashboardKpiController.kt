package com.dwje.api.controller

import com.dwje.api.common.response.ApiResponse
import com.dwje.api.common.util.MenuId
import com.dwje.api.model.request.ExportFormatRequest
import com.dwje.api.service.DashboardKpiService
import com.dwje.api.service.DownloadLogService
import com.dwje.api.service.ExportService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 성과지표 대시보드 컨트롤러 (DB-03)
 *
 * 접근 부서 : 품질보증팀 · 생산관리팀 · 전산팀 · 경영진 · 통합관리자 (제조팀 제외)
 */
@RestController
@RequestMapping("/api/v1/dashboard/kpi")
@Tag(name = "03. 대시보드")
class DashboardKpiController(
    private val dashboardKpiService: DashboardKpiService,
    private val exportService: ExportService,
    private val downloadLogService: DownloadLogService
) {

    /**
     * KPI 요약 3종 (No.44 — 가중치 0.4/0.3/0.3)
     *
     * @param yearMonth 대상 연월 (YYYY-MM)
     */
    @Operation(summary = "KPI 요약(3종)", description = "공정 불량률·작업공수 지수·설비 가동률과 종합 달성률을 반환한다.")
    @GetMapping("/summary")
    fun summary(
        @Parameter(description = "대상 연월 (YYYY-MM)") @RequestParam(required = false) yearMonth: String?
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = dashboardKpiService.getSummary(yearMonth)
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    /** KPI 추이 (No.45 — 구축 전 = 100 지수) */
    @Operation(summary = "KPI 추이", description = "KPI 3종의 월별 추이를 반환한다.")
    @GetMapping("/trend")
    fun trend(
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = dashboardKpiService.getTrend(from, to)
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    /** 불량 유형 분포 (No.46) */
    @Operation(summary = "불량 유형 분포", description = "대상 연월 누계 불량 유형 구성비를 반환한다.")
    @GetMapping("/defect-distribution")
    fun defectDistribution(
        @RequestParam(required = false) yearMonth: String?
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = dashboardKpiService.getDefectDistribution(yearMonth)
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    /** 월별 불량 유형 추이 (No.47 — 상위 2개 유형) */
    @Operation(summary = "월별 불량 유형 추이", description = "상위 N개 불량 유형의 월별 추이를 반환한다.")
    @GetMapping("/defect-type-trend")
    fun defectTypeTrend(
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false, defaultValue = "2") topN: Int
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = dashboardKpiService.getDefectTypeTrend(from, to, topN)
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    /** AI 성능 6축 (No.48) */
    @Operation(summary = "AI 성능 6축", description = "현재 서비스 중인 AI 서빙 버전의 성능 6축을 반환한다.")
    @GetMapping("/ai-performance")
    fun aiPerformance(
        @RequestParam(required = false) yearMonth: String?
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(dashboardKpiService.getAiPerformance(yearMonth))

    /** 부서별 작업공수 절감 (No.49 — 지수 100 = 기준선) */
    @Operation(summary = "부서별 작업공수 절감", description = "구축 전 대비 부서별 작업공수 지수를 반환한다.")
    @GetMapping("/manhour-saving")
    fun manhourSaving(
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(dashboardKpiService.getManhourSaving(from, to))

    /** 월별 목표 달성률 (No.50 — KPI 3종 가중 합산) */
    @Operation(summary = "월별 목표 달성률", description = "KPI 3종을 가중 합산한 월별 종합 달성률을 반환한다.")
    @GetMapping("/achievement-trend")
    fun achievementTrend(
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = dashboardKpiService.getAchievementTrend(from, to)
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    /** AI 성능 목표 충족 (No.51 — 5개 항목) */
    @Operation(summary = "AI 성능 목표 충족", description = "AI 성능 검증 5개 항목의 목표 충족 여부를 반환한다.")
    @GetMapping("/ai-target-status")
    fun aiTargetStatus(
        @RequestParam(required = false) yearMonth: String?
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(dashboardKpiService.getAiTargetStatus(yearMonth))

    /** 월별 지표 실측값 (No.52 — 히트맵) */
    @Operation(summary = "월별 지표 실측값", description = "연도별 월 × 지표 히트맵 데이터를 반환한다.")
    @GetMapping("/monthly-matrix")
    fun monthlyMatrix(
        @Parameter(description = "대상 연도") @RequestParam(required = false) year: Int?
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = dashboardKpiService.getMonthlyMatrix(year)
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    /** KPI 측정 기준 조회 (No.53 — 모달) */
    @Operation(summary = "KPI 측정 기준 조회", description = "KPI 별 산출식·데이터 소스·측정 주기·제외 조건을 반환한다.")
    @GetMapping("/basis")
    fun basis(): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(dashboardKpiService.getBasis())

    /**
     * KPI 증빙 내려받기 (No.54 — 산출 근거 원천 데이터)
     *
     * @param request 대상 연월 및 다운로드 형식
     */
    @Operation(summary = "KPI 증빙 내려받기", description = "KPI 산출 근거 원천 데이터를 엑셀로 내려받는다.")
    @PostMapping("/evidence-export")
    fun evidenceExport(
        @Valid @RequestBody(required = false) request: ExportFormatRequest?
    ): ResponseEntity<ByteArrayResource> {
        val yearMonth = request?.yearMonth
        val format = request?.format ?: "xls"
        val (rows, mask) = dashboardKpiService.getEvidenceRows(yearMonth)

        // 파일을 먼저 만든다 — 크기를 이력에 남기고, 만들다 실패하면 'DONE' 기록도 막는다.
        val response = exportService.export(
            format = format,
            fileName = "kpi_evidence_${exportService.timestamp()}",
            headers = listOf("지표코드", "지표명", "측정일시", "측정값", "판정", "사업장", "공정", "설비", "품목", "원천", "비고"),
            keys = listOf("metricCd", "metricNm", "measuredAt", "value", "judge", "plantCd", "wcCd", "eqptCd", "itemCd", "src", "remark"),
            rows = rows
        )

        // 다운로드 이력 및 감사 로그를 남긴다.
        downloadLogService.record(
            reportId = null,
            reportNm = "KPI 산출 증빙",
            menuId = MenuId.DASH_KPI,
            format = format,
            scope = "yearMonth=${yearMonth ?: "전월"}",
            rowCnt = rows.size,
            blindCnt = mask.maskedCount(),
            blindCells = mask.maskedKeys().associateWith { rows.size },
            params = mapOf("yearMonth" to yearMonth, "format" to format),
            fileSize = response.body?.contentLength()
        )

        return response
    }
}
