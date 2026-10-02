package com.dwje.api

import com.dwje.api.controller.LlmChatProxyController
import com.dwje.api.service.AiChatService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 07 GLS-04 — 정규화 결과의 사용 범위(의도·검색어·LLM [용어] 블록) */
class GlossaryUsageScopeTest {

    @Test
    @DisplayName("의도는 원문 우선 — 정규화가 다른 의도 낱말을 만들어도 원문 판정이 이긴다, 원문이 unknown 이면 정규화 문장")
    fun intent() {
        assertEquals("trend", AiChatService.intentOf("이번 주 불량률 추이 알려줘", "이번 주 LOT 추이 알려줘"))
        assertEquals("trace", AiChatService.intentOf("로뜨 보여줘", "LOT 보여줘"))
        assertEquals("unknown", AiChatService.intentOf("안내 부탁", "안내 부탁"))
    }

    @Test
    @DisplayName("검색어는 공식 용어(중복 제거)를 앞에, 원문을 뒤에 — 치환이 없으면 원문 그대로")
    fun searchText() {
        val r = listOf(mapOf("from" to "로뜨", "to" to "LOT"), mapOf("from" to "로트", "to" to "LOT"))
        assertEquals("LOT 로뜨 로트 확인", AiChatService.searchTextOf("로뜨 로트 확인", r))
        assertEquals("원문", AiChatService.searchTextOf("원문", emptyList()))
    }

    @Test
    @DisplayName("LLM 근거 — 치환이 있을 때만 [용어] 블록(현장 표현 → 공식 용어 : 뜻, 최대 10줄), 끄면 없음")
    fun glossaryBlock() {
        val ask = mapOf(
            "sources" to listOf(mapOf("snippet" to "문서 발췌")),
            "termReplacements" to (1..12).map { mapOf("from" to "w$it", "to" to "T$it", "definition" to "뜻$it") } +
                mapOf("from" to "w1", "to" to "T1", "definition" to "뜻1")
        )
        val ctx = LlmChatProxyController.evidenceContext(ask, includeFacts = true)
        assertTrue(ctx.startsWith("[용어]\nw1 → T1 : 뜻1\n"))
        assertEquals(10, ctx.lines().count { it.contains(" → ") })
        assertTrue(ctx.endsWith("\n\n문서 발췌"))

        assertEquals("문서 발췌", LlmChatProxyController.evidenceContext(ask - "termReplacements", includeFacts = true))
        assertFalse(LlmChatProxyController.evidenceContext(ask, includeFacts = true, glossaryBlock = false).contains("[용어]"))
        assertEquals("[용어]\nw → T", LlmChatProxyController.evidenceContext(
            mapOf("termReplacements" to listOf(mapOf("from" to "w", "to" to "T", "definition" to null))), includeFacts = true))
    }
}
