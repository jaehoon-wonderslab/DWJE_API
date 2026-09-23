package com.dwje.api

import com.dwje.api.service.AiDataToolService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * AI 데이터 도구 — 질문에서 기간을 읽는 규칙
 *
 * 기준일은 마지막 실적일이다. 기간을 잘못 읽으면 모델은 받은 근거대로 **틀린 기간을 맞다고** 말한다.
 */
class AiDataToolTest {

    private val last = LocalDate.of(2026, 9, 16)
    private fun r(q: String) = AiDataToolService.resolvePeriods(q, last)

    @Test
    @DisplayName("수치 질문이 아니면 집계하지 않는다")
    fun notMetric() {
        assertNull(r("버(burr)가 뭐야?"))
    }

    @Test
    @DisplayName("기간 말이 없으면 이번 달(마지막 실적일까지) vs 지난달")
    fun defaultMonthCompare() {
        val (a, b) = r("불량률 어때?")!!
        assertEquals(LocalDate.of(2026, 9, 1) to last, a.from to a.to)
        assertEquals(LocalDate.of(2026, 8, 1) to LocalDate.of(2026, 8, 31), b!!.from to b.to)
    }

    @Test
    @DisplayName("전월 대비 → 이번 달 vs 지난달, 띄어쓰기와 무관")
    fun prevMonth() {
        val (a, b) = r("전월 대비 이번 달 생산량 변화")!!
        assertEquals("이번 달", a.label)
        assertEquals("지난달", b!!.label)
    }

    @Test
    @DisplayName("'8월' 과 '7월' 을 말하면 두 달을 비교한다 — 최근 것이 앞")
    fun explicitMonths() {
        val (a, b) = r("7월과 8월 불량률 비교")!!
        assertEquals(LocalDate.of(2026, 8, 1) to LocalDate.of(2026, 8, 31), a.from to a.to)
        assertEquals(LocalDate.of(2026, 7, 1), b!!.from)
    }

    @Test
    @DisplayName("한 기간 + 비교 말 → 바로 앞 같은 길이")
    fun previousWeek() {
        val (a, b) = r("이번 주 불량 추이")!!
        assertEquals(LocalDate.of(2026, 9, 14) to last, a.from to a.to)
        assertEquals(LocalDate.of(2026, 9, 11) to LocalDate.of(2026, 9, 13), b!!.from to b.to)
    }

    @Test
    @DisplayName("한 기간만 물으면 비교 기간 없이")
    fun single() {
        val (a, b) = r("어제 생산 실적")!!
        assertEquals(LocalDate.of(2026, 9, 15), a.from)
        assertNull(b)
    }
}
