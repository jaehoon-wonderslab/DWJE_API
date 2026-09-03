package com.dwje.api.controller

import com.dwje.api.common.response.ApiResponse
import com.dwje.api.service.DashboardProcessService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 공정 및 제품 대시보드 컨트롤러 (DB-02)
 *
 * 공정과 제품(113종)을 조합해 생산·품질 지표를 비교한다.
 */
@RestController
@RequestMapping("/api/v1/dashboard/process")
@Tag(name = "03. 대시보드")
class DashboardProcessController(
    private val dashboardProcessService: DashboardProcessService
) {

    /**
     * 공정·제품 요약 지표 (No.33 — 가중 평균 산출)
     */
    @Operation(summary = "공정·제품 요약 지표", description = "선택 범위의 생산량·불량률·수율·가동률을 가중 평균으로 반환한다.")
    @GetMapping("/summary")
    fun summary(
        @RequestParam(required = false) date: String?,
        @RequestParam(required = false) processId: String?,
        @Parameter(description = "제품 코드 목록") @RequestParam(required = false) productCodes: List<String>?
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = dashboardProcessService.getSummary(date, processId, productCodes)
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    /** 시간대별 불량률 추이 (No.34) */
    @Operation(summary = "시간대별 불량률 추이", description = "선택 공정·제품의 시간대별 불량률을 반환한다.")
    @GetMapping("/defect-trend")
    fun defectTrend(
        @RequestParam(required = false) date: String?,
        @RequestParam(required = false) processId: String?,
        @RequestParam(required = false) productCodes: List<String>?,
        @RequestParam(required = false) interval: String?
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = dashboardProcessService.getDefectTrend(date, processId, productCodes, interval)
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    /** 제품별 생산량·불량률 (No.35) */
    @Operation(summary = "제품별 생산량·불량률", description = "제품별 생산량과 불량률을 반환한다.")
    @GetMapping("/product-production")
    fun productProduction(
        @RequestParam(required = false) date: String?,
        @RequestParam(required = false) processId: String?,
        @RequestParam(required = false) productCodes: List<String>?
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = dashboardProcessService.getProductProduction(date, processId, productCodes)
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    /** 불량 유형 구성 (No.36) */
    @Operation(summary = "불량 유형 구성", description = "선택 공정·제품의 불량 유형 구성비를 반환한다.")
    @GetMapping("/defect-composition")
    fun defectComposition(
        @RequestParam(required = false) date: String?,
        @RequestParam(required = false) processId: String?,
        @RequestParam(required = false) productCodes: List<String>?
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = dashboardProcessService.getDefectComposition(date, processId, productCodes)
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    /** 제품별 수율 (No.37) */
    @Operation(summary = "제품별 수율", description = "제품별 수율과 목표 대비 달성 수준을 반환한다.")
    @GetMapping("/product-yield")
    fun productYield(
        @RequestParam(required = false) date: String?,
        @RequestParam(required = false) processId: String?,
        @RequestParam(required = false) productCodes: List<String>?
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = dashboardProcessService.getProductYield(date, processId, productCodes)
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    /** 제품별 가동률 (No.38 — 설비 점유 기준) */
    @Operation(summary = "제품별 가동률", description = "제품별 설비 점유 기준 가동률을 반환한다.")
    @GetMapping("/product-uptime")
    fun productUptime(
        @RequestParam(required = false) date: String?,
        @RequestParam(required = false) processId: String?,
        @RequestParam(required = false) productCodes: List<String>?
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(dashboardProcessService.getProductUptime(date, processId, productCodes))

    /** 공정 비교 (No.39 — 동일 제품 구성 기준) */
    @Operation(summary = "공정 비교", description = "동일 제품 구성 기준으로 공정별 불량률을 비교한다.")
    @GetMapping("/process-compare")
    fun processCompare(
        @RequestParam(required = false) date: String?,
        @RequestParam(required = false) productCodes: List<String>?
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = dashboardProcessService.getProcessCompare(date, productCodes)
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    /** 설비별 시간대 가동률 (No.40) */
    @Operation(summary = "설비별 시간대 가동률", description = "설비 × 시간대 히트맵 데이터를 반환한다.")
    @GetMapping("/equipment-uptime-heatmap")
    fun equipmentUptimeHeatmap(
        @RequestParam(required = false) date: String?,
        @RequestParam(required = false) processId: String?,
        @RequestParam(required = false) interval: String?
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(dashboardProcessService.getEquipmentUptimeHeatmap(date, processId, interval))

    /** 제품별 상세 목록 (No.41 — 생산량 내림차순 기본) */
    @Operation(summary = "제품별 상세 목록", description = "제품별 생산·품질 지표 상세 목록을 반환한다.")
    @GetMapping("/products")
    fun products(
        @RequestParam(required = false) date: String?,
        @RequestParam(required = false) processId: String?,
        @RequestParam(required = false) productCodes: List<String>?,
        @Parameter(description = "정렬 — qty|defectRate|product|rank|family") @RequestParam(required = false) sort: String?
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = dashboardProcessService.getProducts(date, processId, productCodes, sort)
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    /** Top N 제품 조회 (No.42) */
    @Operation(summary = "Top N 제품 조회", description = "제품군 순위(SY-07) 기준 상위 N 제품을 반환한다.")
    @GetMapping("/top-products")
    fun topProducts(
        @Parameter(description = "5 | 10 | 20 | 50 | all") @RequestParam(required = false) topN: String?
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = dashboardProcessService.getTopProducts(topN)
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    /** 선택 요약 (No.43) */
    @Operation(summary = "선택 요약", description = "선택 공정의 생산능력·목표 수율과 현재 수율 격차를 반환한다.")
    @GetMapping("/selection-summary")
    fun selectionSummary(
        @Parameter(description = "기준일 (미지정 시 오늘). 화면의 다른 위젯과 같은 값을 보내야 한다.")
        @RequestParam(required = false) date: String?,
        @RequestParam processId: String,
        @RequestParam(required = false) productCodes: List<String>?
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = dashboardProcessService.getSelectionSummary(date, processId, productCodes)
        return ApiResponse.ok(data, mask.maskedKeys())
    }
}
