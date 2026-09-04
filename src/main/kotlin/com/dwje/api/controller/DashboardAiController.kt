package com.dwje.api.controller

import com.dwje.api.common.response.ApiResponse
import com.dwje.api.service.DashboardAiService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * AI 통합 대시보드 컨트롤러 (DB-01)
 *
 * 전 부서가 접근하며, 수량(qty)·수율(yield) 항목은 데이터 접근 권한에 따라 마스킹된다.
 * 화면 갱신 방식은 수동 새로고침이다.
 */
@RestController
@RequestMapping("/api/v1/dashboard/ai")
@Tag(name = "03. 대시보드")
class DashboardAiController(
    private val dashboardAiService: DashboardAiService
) {

    /**
     * 통합 요약 지표 (No.21 — KPI 카드 4종)
     *
     * @param date 기준일 (YYYY-MM-DD, 미지정 시 오늘)
     */
    @Operation(summary = "통합 요약 지표", description = "불량률·가동률·당일 생산량·경계 판정 대기 건을 반환한다.")
    @GetMapping("/summary")
    fun summary(
        @Parameter(description = "기준일 (YYYY-MM-DD)") @RequestParam(required = false) date: String?
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = dashboardAiService.getSummary(date)
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    /**
     * 시간대별 불량률 추이 (No.22 — 전체 + 주 불량유형 2계열)
     */
    @Operation(summary = "시간대별 불량률 추이", description = "전체 불량률과 상위 불량유형 계열을 시간대별로 반환한다.")
    @GetMapping("/defect-trend")
    fun defectTrend(
        @RequestParam(required = false) date: String?,
        @Parameter(description = "집계 구간 (예: 2h)") @RequestParam(required = false) interval: String?
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = dashboardAiService.getDefectTrend(date, interval)
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    /**
     * 라인별 생산량·불량률 (No.23)
     */
    @Operation(summary = "라인별 생산량·불량률", description = "설비별 생산량과 불량률을 조회한다.")
    @GetMapping("/line-production")
    fun lineProduction(
        @RequestParam(required = false) date: String?,
        @RequestParam(required = false) processId: String?
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = dashboardAiService.getLineProduction(date, processId)
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    /**
     * 공정 품질 지수 6축 (No.24)
     *
     * 축 : 양품률 · 가동률 · 정시완료 · 검사정확도 · 이상대응 · 데이터정합
     */
    @Operation(summary = "공정 품질 지수(6축)", description = "6개 축의 실측값과 목표값을 반환한다.")
    @GetMapping("/quality-index")
    fun qualityIndex(
        @RequestParam(required = false) date: String?
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = dashboardAiService.getQualityIndex(date)
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    /**
     * 불량 유형 구성 (No.25 — 경계 판정 건 제외 표기)
     */
    @Operation(summary = "불량 유형 구성", description = "불량 유형별 구성비를 반환한다. 경계 판정 대기 건은 제외한다.")
    @GetMapping("/defect-composition")
    fun defectComposition(
        @RequestParam(required = false) date: String?,
        @RequestParam(required = false) processId: String?
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = dashboardAiService.getDefectComposition(date, processId)
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    /**
     * 공정별 수율 (No.26)
     */
    @Operation(summary = "공정별 수율", description = "공정별 수율과 목표 대비 달성 수준을 반환한다.")
    @GetMapping("/process-yield")
    fun processYield(
        @RequestParam(required = false) date: String?
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = dashboardAiService.getProcessYield(date)
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    /**
     * 생산 계획 대비 실적 (No.27)
     */
    @Operation(summary = "생산 계획 대비 실적", description = "시간대별 계획·실적과 누계 달성률을 반환한다.")
    @GetMapping("/plan-vs-actual")
    fun planVsActual(
        @RequestParam(required = false) date: String?,
        @RequestParam(required = false) interval: String?
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = dashboardAiService.getPlanVsActual(date, interval)
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    /**
     * 설비별 시간대 가동률 히트맵 (No.28 — 낮을수록 진하게(invert))
     */
    @Operation(summary = "설비별 시간대 가동률", description = "설비 × 시간대 히트맵 데이터를 반환한다.")
    @GetMapping("/equipment-uptime-heatmap")
    fun equipmentUptimeHeatmap(
        @RequestParam(required = false) date: String?,
        @RequestParam(required = false) processId: String?,
        @RequestParam(required = false) interval: String?
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(dashboardAiService.getEquipmentUptimeHeatmap(date, processId, interval))

    /**
     * 라인별 현황 목록 (No.29 — 행 클릭 시 설비 상세)
     */
    @Operation(
        summary = "라인별 현황 목록",
        description = "설비별 생산량·불량률·가동률·상태를 쪽 단위로 반환한다. 목록 키는 lines 다."
    )
    @GetMapping("/lines")
    fun lines(
        @RequestParam(required = false) date: String?,
        @RequestParam(required = false) processId: String?,
        @RequestParam(required = false) page: Int?,
        @Parameter(description = "쪽 크기. 0 이면 전량(인쇄·내려받기용)") @RequestParam(required = false) size: Int?
    ): ApiResponse<Map<String, Any?>> {
        val (data, meta, mask) = dashboardAiService.getLines(date, processId, page, size)
        return ApiResponse.page(data, meta, mask.maskedKeys())
    }

    /**
     * 이상 알림 요약 (No.31)
     *
     * @param hours 조회 시간 범위 (기본 24시간)
     */
    @Operation(summary = "이상 알림 요약", description = "최근 발생한 이상 알림을 심각도 순으로 반환한다.")
    @GetMapping("/alerts")
    fun alerts(
        @Parameter(description = "조회 시간 범위(시간)") @RequestParam(required = false, defaultValue = "24") hours: Int
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(dashboardAiService.getAlerts(hours))

    /**
     * Agent 작동 현황 요약 (No.32)
     */
    @Operation(summary = "Agent 작동 현황 요약", description = "Master AI 상태와 Agent 9종의 최근 실행 상태를 반환한다.")
    @GetMapping("/agents")
    fun agents(): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(dashboardAiService.getAgents())

    /**
     * AI 일일 종합 브리핑
     */
    @Operation(summary = "AI 일일 종합 브리핑", description = "당일 생산·품질 현황 및 특이 이상 징후를 종합 분석한 AI 브리핑을 반환한다.")
    @GetMapping("/briefing")
    fun briefing(
        @Parameter(description = "기준일 (YYYY-MM-DD)") @RequestParam(required = false) date: String?
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(dashboardAiService.getBriefing(date))

    /**
     * AI 공정 원인 분석 및 처방 권고
     */
    @Operation(summary = "AI 공정 원인 분석 및 처방 권고", description = "설비별 불량 유발 원인 인자 기여도(XAI)와 AI 처방 조치 가이드를 반환한다.")
    @GetMapping("/cause-prescription")
    fun causePrescription(
        @Parameter(description = "기준일 (YYYY-MM-DD)") @RequestParam(required = false) date: String?,
        @Parameter(description = "설비 코드 (예: PR-03)") @RequestParam(required = false) eqptCd: String?
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(dashboardAiService.getCausePrescription(date, eqptCd))
}
