package com.dwje.api.service

import java.time.LocalDate

/**
 * DB 질문에 LLM 없이 주는 고정 답 — 조회 결과 0건(EMPTY)·조건 오류(INVALID).
 *
 * 이 둘을 문서 어시스턴트 모델에 넘기면 「사내 문서에서 확인할 수 없습니다」 로 답해 틀린 안내가 된다(2026-10-02).
 */
object AiFixedAnswer {

    /** 도구별 「~ 실적」 이름 */
    private val TOOL_SUBJECT = mapOf(
        AiDataToolService.DEFECT_TOP to "불량 유형 실적",
        AiDataToolService.DEFECT_RATE_TOP to "제품별 불량률 실적",
        AiDataToolService.PRODUCTION_PRODUCT_LIST to "제품별 생산 실적",
        AiDataToolService.DAILY_PRODUCT_DEFECT to "일자별 제품 불량 실적",
        AiDataToolService.PRODUCTION_PERIOD_COMPARE to "생산 실적"
    )

    /**
     * 0건 안내 — 「2026-09-25 의 불량 유형 실적이 없습니다. MES 최신 실적일은 2026-09-30 입니다.」
     * AOI 는 도구가 만든 사유 문장(작업장·기간)을 그대로 쓴다. 해당 없으면 null(LLM 이 답한다).
     */
    fun empty(result: AiDataToolService.EvidenceResult): String? {
        if (result.executionCode != "EMPTY") return null
        if (result.tool == AiDataToolService.AOI_DIMENSION_SUMMARY) return result.evidence.firstOrNull()?.get("text")?.toString()
        val subject = TOOL_SUBJECT[result.tool] ?: return null
        val period = result.period ?: return null
        val range = if (period.from == period.to) "${period.from}" else "${period.from}~${period.to}"
        val latest = result.latestDataDate?.let { " MES 최신 실적일은 $it 입니다." }.orEmpty()
        return "$range 의 ${subject}이 없습니다.$latest"
    }

    /** 조건 오류 안내 — 갈래별 문구 */
    fun invalid(reason: AiQuestionPlanner.InvalidReason, today: LocalDate): String = when (reason) {
        AiQuestionPlanner.InvalidReason.DATE -> "조회 날짜를 이해하지 못했습니다. 2026-09-20 또는 9월 20일 처럼 다시 입력해 주십시오."
        AiQuestionPlanner.InvalidReason.FUTURE -> "아직 지나지 않은 날짜는 조회할 수 없습니다. 오늘($today) 이전 날짜로 다시 입력해 주십시오."
        AiQuestionPlanner.InvalidReason.ORDER -> "시작일이 종료일보다 늦습니다. 기간을 다시 입력해 주십시오."
        AiQuestionPlanner.InvalidReason.SPAN -> "한 번에 조회할 수 있는 기간은 92일(일자별 제품 불량은 31일)까지입니다. 기간을 줄여 다시 입력해 주십시오."
        AiQuestionPlanner.InvalidReason.LIMIT -> "순위는 제품별 불량률 1~20위, 불량 유형 1~10위까지 조회할 수 있습니다. 순위를 줄여 다시 입력해 주십시오."
        AiQuestionPlanner.InvalidReason.CONDITION -> "조회 조건을 이해하지 못했습니다. 날짜(예: 2026-09-20 또는 9월 20일)·순위·작업장을 확인해 다시 입력해 주십시오."
    }

    /** ask 결과에 대한 고정 답 — 없으면 null */
    fun of(result: AiDataToolService.EvidenceResult, today: LocalDate): String? = when {
        result.parseCode == "INVALID" -> invalid(result.invalidReason ?: AiQuestionPlanner.InvalidReason.CONDITION, today)
        else -> empty(result)
    }
}
