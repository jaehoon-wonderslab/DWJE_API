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
        @Parameter(description = "기준일 (YYYY-MM-DD)") @RequestParam(required = false) date: String?,
        @Parameter(description = "구간 시작일 (YYYY-MM-DD) — 주면 date 대신 이 구간을 집계한다")
        @RequestParam(required = false) from: String?,
        @Parameter(description = "구간 종료일 (YYYY-MM-DD) — 종료일을 포함한다")
        @RequestParam(required = false) to: String?
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = dashboardAiService.getSummary(date, from, to)
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    /**
     * 시간대별 불량률 추이 (No.22 — 전체 불량률 + 불량유형 수량 계열)
     *
     * `series[0]` 은 전체 불량률(%), `series[1..]` 는 불량유형별 수량(EA)이며 모두 `labels` 와
     * 같은 길이·순서다. 유형 계열 수는 `topN` 이 정한다 — 미지정 시 구간 합계 상위 2종,
     * `all` 이면 그 구간·공장에서 발생한 전 유형. 같은 화면의 `defect-composition` 과 유형을
     * 맞추려면 `all` 을 쓴다.
     */
    @Operation(
        summary = "시간대별 불량률 추이",
        description = "전체 불량률 계열(series[0])과 불량유형별 수량 계열(series[1..])을 시간대별로 반환한다. " +
            "유형 계열은 topN 미지정 시 구간 합계 상위 2종, topN=all 이면 구간에서 발생한 전 유형이다. " +
            "계열 범위는 seriesScope 로 알린다."
    )
    @GetMapping("/defect-trend")
    fun defectTrend(
        @RequestParam(required = false) date: String?,
        @Parameter(description = "시작일 (YYYY-MM-DD)") @RequestParam(required = false) from: String?,
        @Parameter(description = "종료일 (YYYY-MM-DD)") @RequestParam(required = false) to: String?,
        @Parameter(description = "집계 구간 (예: 2h)") @RequestParam(required = false) interval: String?,
        @Parameter(description = "불량유형 계열 수. 미지정 시 상위 2종, all 이면 전 유형, 양의 정수면 상위 N종")
        @RequestParam(required = false) topN: String?
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = dashboardAiService.getDefectTrend(date, from, to, interval, topN)
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    /**
     * 불량률 추이 칸 하나의 불량 유형 상세 (No.22 — 칸 클릭 다이얼로그)
     *
     * 추이를 조회할 때 쓴 date/from/to/interval 을 그대로 넘기고, 칸은 `slot`(labels[] 값) 또는
     * `slotAt`(칸 시작 시각) 으로 고른다. 실제로 본 구간은 `slotFrom`·`slotTo` 로 알린다.
     */
    @Operation(
        summary = "불량률 추이 칸 상세 (불량 유형 전량)",
        description = "추이의 칸 하나에서 발생한 불량 유형을 전량 반환한다. items[] 는 rank·defectTypeCd·defectType·ngQty·ratio·untyped 와 " +
            "원표 속성(rawQty·recordCount·lotCount·itemCount·itemCds·processIds·remarks·insUsers·firstAt·lastAt·useFlg·masterRemark)을 담는다. " +
            "수량은 불량 유형 구성과 같은 라벨 원장 안분 값이라 유형 합 + 유형 미상 = totalNgQty 다. " +
            "칸은 slotAt(칸 시작 시각: yyyy-MM-dd HH:mm, yyyy-MM-dd HH시, yyyy-MM-dd) 또는 slot(추이 labels[] 값) 으로 고른다. " +
            "구간·집계 단위는 defect-trend 와 같은 date/from/to/interval 로 해석한다."
    )
    @GetMapping("/defect-trend/slot-details")
    fun defectTrendSlotDetails(
        @RequestParam(required = false) date: String?,
        @Parameter(description = "시작일 (YYYY-MM-DD) — 추이 조회와 같은 값") @RequestParam(required = false) from: String?,
        @Parameter(description = "종료일 (YYYY-MM-DD) — 추이 조회와 같은 값") @RequestParam(required = false) to: String?,
        @Parameter(description = "집계 구간 (예: 2h) — 추이 조회와 같은 값") @RequestParam(required = false) interval: String?,
        @Parameter(description = "칸 라벨 — 추이 응답 labels[]/slots[].slot 의 값 (예: 08:00, 08-28 00시, 08-28)")
        @RequestParam(required = false) slot: String?,
        @Parameter(description = "칸 시작 시각 — yyyy-MM-dd HH:mm · yyyy-MM-dd HH시 · yyyy-MM-dd. slot 보다 우선한다")
        @RequestParam(required = false) slotAt: String?,
        @Parameter(description = "공장 코드 — 미지정 시 기본 사업장") @RequestParam(required = false) plantCd: String?
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = dashboardAiService.getDefectTrendSlotDetails(date, from, to, interval, slot, slotAt, plantCd)
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    /**
     * 라인별 생산량·불량률 (No.23)
     */
    @Operation(summary = "라인별 생산량·불량률", description = "설비별 생산량과 불량률을 조회한다.")
    @GetMapping("/line-production")
    fun lineProduction(
        @RequestParam(required = false) date: String?,
        @RequestParam(required = false) processId: String?,
        @Parameter(description = "구간 시작일 (YYYY-MM-DD) — 주면 date 대신 이 구간을 집계한다")
        @RequestParam(required = false) from: String?,
        @Parameter(description = "구간 종료일 (YYYY-MM-DD) — 종료일을 포함한다")
        @RequestParam(required = false) to: String?
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = dashboardAiService.getLineProduction(date, processId, from, to)
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
        @RequestParam(required = false) date: String?,
        @Parameter(description = "구간 시작일 (YYYY-MM-DD) — 주면 date 대신 이 구간을 집계한다")
        @RequestParam(required = false) from: String?,
        @Parameter(description = "구간 종료일 (YYYY-MM-DD) — 종료일을 포함한다")
        @RequestParam(required = false) to: String?
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = dashboardAiService.getQualityIndex(date, from, to)
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    /**
     * 불량 유형 구성 (No.25 — 경계 판정 건 제외 표기)
     */
    @Operation(summary = "불량 유형 구성", description = "불량 유형별 구성비를 반환한다. 경계 판정 대기 건은 제외한다.")
    @GetMapping("/defect-composition")
    fun defectComposition(
        @RequestParam(required = false) date: String?,
        @RequestParam(required = false) processId: String?,
        @Parameter(description = "구간 시작일 (YYYY-MM-DD) — 주면 date 대신 이 구간을 집계한다")
        @RequestParam(required = false) from: String?,
        @Parameter(description = "구간 종료일 (YYYY-MM-DD) — 종료일을 포함한다")
        @RequestParam(required = false) to: String?
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = dashboardAiService.getDefectComposition(date, processId, from, to)
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    /**
     * 공정별 수율 (No.26)
     */
    @Operation(summary = "공정별 수율", description = "공정별 수율과 목표 대비 달성 수준을 반환한다.")
    @GetMapping("/process-yield")
    fun processYield(
        @RequestParam(required = false) date: String?,
        @Parameter(description = "구간 시작일 (YYYY-MM-DD) — 주면 date 대신 이 구간을 집계한다")
        @RequestParam(required = false) from: String?,
        @Parameter(description = "구간 종료일 (YYYY-MM-DD) — 종료일을 포함한다")
        @RequestParam(required = false) to: String?
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = dashboardAiService.getProcessYield(date, from, to)
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    /**
     * 생산 계획 대비 실적 (No.27)
     */
    @Operation(summary = "생산 계획 대비 실적", description = "시간대별 계획·실적과 누계 달성률을 반환한다.")
    @GetMapping("/plan-vs-actual")
    fun planVsActual(
        @RequestParam(required = false) date: String?,
        @RequestParam(required = false) interval: String?,
        @Parameter(description = "구간 시작일 (YYYY-MM-DD) — 주면 date 대신 이 구간을 집계한다")
        @RequestParam(required = false) from: String?,
        @Parameter(description = "구간 종료일 (YYYY-MM-DD) — 종료일을 포함한다")
        @RequestParam(required = false) to: String?
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = dashboardAiService.getPlanVsActual(date, interval, from, to)
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
        @RequestParam(required = false) interval: String?,
        @Parameter(description = "구간 시작일 (YYYY-MM-DD) — 주면 date 대신 이 구간을 집계한다")
        @RequestParam(required = false) from: String?,
        @Parameter(description = "구간 종료일 (YYYY-MM-DD) — 종료일을 포함한다")
        @RequestParam(required = false) to: String?
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(dashboardAiService.getEquipmentUptimeHeatmap(date, processId, interval, from, to))

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
        @Parameter(description = "쪽 크기. 0 이면 전량(인쇄·내려받기용)") @RequestParam(required = false) size: Int?,
        @Parameter(description = "구간 시작일 (YYYY-MM-DD) — 주면 date 대신 이 구간을 집계한다")
        @RequestParam(required = false) from: String?,
        @Parameter(description = "구간 종료일 (YYYY-MM-DD) — 종료일을 포함한다")
        @RequestParam(required = false) to: String?
    ): ApiResponse<Map<String, Any?>> {
        val (data, meta, mask) = dashboardAiService.getLines(date, processId, page, size, from, to)
        return ApiResponse.page(data, meta, mask.maskedKeys())
    }

    /**
     * 설비 × 제품 실적 목록 (실적 집계 조회 3단계)
     *
     * [lines] 는 설비 한 대에 한 행이라 두 제품 이상 돌린 설비의 수량이 대표 제품 한 칸에
     * 몰린다. 제품으로 묶어 그릴 때는 이 목록을 쓴다. 목록 키는 [lines] 와 같이 lines 다.
     */
    @Operation(
        summary = "설비 × 제품 실적 목록",
        description = "설비가 그 구간에 만든 제품마다 한 행으로 생산량·불량률을 반환한다. 실적이 있는 설비만 나온다."
    )
    @GetMapping("/line-products")
    fun lineProducts(
        @RequestParam(required = false) date: String?,
        @RequestParam(required = false) processId: String?,
        @RequestParam(required = false) page: Int?,
        @Parameter(description = "쪽 크기. 0 이면 전량(인쇄·내려받기용)") @RequestParam(required = false) size: Int?,
        @Parameter(description = "구간 시작일 (YYYY-MM-DD) — 주면 date 대신 이 구간을 집계한다")
        @RequestParam(required = false) from: String?,
        @Parameter(description = "구간 종료일 (YYYY-MM-DD) — 종료일을 포함한다")
        @RequestParam(required = false) to: String?
    ): ApiResponse<Map<String, Any?>> {
        val (data, meta, mask) = dashboardAiService.getLineProducts(date, processId, page, size, from, to)
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
     * AI 일일 품질·생산 종합 브리핑 (sLLM)
     *
     * 서버는 지표를 모아 모델에 넘기고 결과를 그대로 내린다. 문장을 서버가 쓰지 않는다.
     * 모델이 붙기 전까지는 `reason = "MODEL_NOT_READY"` 로 비어 있는 응답을 낸다.
     */
    @Operation(
        summary = "AI 일일 품질·생산 종합 브리핑",
        description = "sLLM 이 만든 브리핑을 반환한다. 근거(evidence) 없는 문장은 내리지 않는다. " +
            "from·to 를 주면 그 구간 전체를, date 만 주면 그날 하루를 본다. 실제로 본 구간은 periodFrom·periodTo 로 알린다. " +
            "모델 준비 전에는 reason=MODEL_NOT_READY 와 빈 lines 를 반환한다."
    )
    @GetMapping("/briefing")
    fun briefing(
        @Parameter(description = "기준일 (YYYY-MM-DD). from·to 가 오면 무시된다")
        @RequestParam(required = false) date: String?,
        @Parameter(description = "조회 시작일 — 구간 전체를 분석한다")
        @RequestParam(required = false) from: String?,
        @Parameter(description = "조회 종료일 (포함)")
        @RequestParam(required = false) to: String?
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(dashboardAiService.getBriefing(date, from, to))

    /**
     * AI 공정 원인 분석 및 처방 권고 (XAI & Prescription, sLLM)
     */
    @Operation(
        summary = "AI 공정 원인 분석 및 처방 권고",
        description = "불량률이 기준을 넘는 **모든 공정**을 분석해 targets 배열로 반환한다. " +
            "from·to 를 주면 그 구간 전체를, date 만 주면 그날 하루를 본다. 실제로 본 구간은 periodFrom·periodTo 로 알린다. " +
            "원인은 지표로 서버가 만들고 처방은 sLLM 이 만든다. " +
            "모델 준비 전에는 reason=MODEL_NOT_READY, 다른 분석이 도는 중이면 reason=MODEL_BUSY 다."
    )
    @GetMapping("/cause-prescription")
    fun causePrescription(
        @Parameter(description = "기준일 (YYYY-MM-DD). from·to 가 오면 무시된다")
        @RequestParam(required = false) date: String?,
        @Parameter(description = "조회 시작일 — 구간 전체를 분석한다")
        @RequestParam(required = false) from: String?,
        @Parameter(description = "조회 종료일 (포함)")
        @RequestParam(required = false) to: String?,
        @Parameter(description = "공정(작업장) 코드 — 지정하면 그 공정만 본다") @RequestParam(required = false) processId: String?,
        @Parameter(description = "설비 코드 — 문서 검색 질의에 함께 쓴다") @RequestParam(required = false) eqptCd: String?,
        @Parameter(description = "불량률 기준(%). 이 값을 넘는 공정만 분석한다. 미지정 시 설정값")
        @RequestParam(required = false) threshold: Double?
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(dashboardAiService.getCausePrescription(date, from, to, processId, eqptCd, threshold))
}
