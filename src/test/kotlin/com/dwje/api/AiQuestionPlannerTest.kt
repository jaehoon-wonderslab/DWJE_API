package com.dwje.api

import com.dwje.api.common.util.AiBusinessPeriod
import com.dwje.api.service.AiDataToolService
import com.dwje.api.service.AiQuestionPlanner
import com.dwje.api.service.LlmChatProxyService
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.mockito.stubbing.Answer
import java.time.LocalDate

class AiQuestionPlannerTest {
    private val mapper = ObjectMapper()

    private fun tool(name: String, args: String) = mapper.readTree(
        """{"tool_calls":[{"function":{"name":"$name","arguments":${mapper.writeValueAsString(args)}}}]}"""
    )

    @Test fun `LLM chooses product defect rate tool and validated date range`() {
        val question = "2026-09-25부터 2026-09-25까지 제품별 불량률 상위 5종 조회"
        val model = mock(LlmChatProxyService::class.java, Answer<Any?> { call ->
            assertTrue((call.arguments[0] as String).contains(question))
            tool("defect_rate_top", """{"from":"2026-09-25","to":"2026-09-25","limit":5}""")
        })
        val result = AiQuestionPlanner(model, mapper).plan(question, LocalDate.parse("2026-09-26"))
            as AiQuestionPlanner.Decision.DefectRateTop

        assertEquals(AiBusinessPeriod(LocalDate.parse("2026-09-25"), LocalDate.parse("2026-09-25")), result.period)
        assertEquals(5, result.limit)
        assertEquals(1, mockingDetails(model).invocations.count { it.method.name == "chooseTool" })
    }

    @Test fun `LLM chooses daily product defect tool and date validation rejects unsafe ranges`() {
        val question = "9월 20일~22일까지 생산된 불량 종류 및 제품 구분을 일자별로 정리"
        val model = mock(LlmChatProxyService::class.java, Answer<Any?> { call ->
            val prompt = call.arguments[0] as String
            if (prompt.contains("2026-10-23")) tool("daily_product_defect", """{"from":"2026-09-20","to":"2026-10-22","limit":200}""")
            else tool("daily_product_defect", """{"from":"2026-09-20","to":"2026-09-22"}""")
        })
        val planner = AiQuestionPlanner(model, mapper)
        val selected = planner.plan(question, LocalDate.parse("2026-09-26")) as AiQuestionPlanner.Decision.DailyProductDefect
        assertEquals(200, selected.limit)
        assertEquals("2026-09-19T08:00", selected.period.startInclusive.toString())
        assertEquals("2026-09-22T08:00", selected.period.endExclusive.toString())
        assertEquals(AiQuestionPlanner.Decision.Invalid, planner.plan(question, LocalDate.parse("2026-10-23")))
    }

    @Test fun `LLM receives MES latest date and selects recent seven business days comparison`() {
        val question = "지난 일주일간 공장 전체 불량률은 몇 %p 변했나?"
        val model = mock(LlmChatProxyService::class.java, Answer<Any?> { call ->
            val prompt = call.arguments[0] as String
            assertTrue(prompt.contains("최신 실적일은 2026-09-25"))
            tool("production_period_compare", """{"from":"2026-09-19","to":"2026-09-25","compareFrom":"2026-09-12","compareTo":"2026-09-18"}""")
        })
        val result = AiQuestionPlanner(model, mapper).plan(
            question,
            today = LocalDate.parse("2026-09-26"),
            latestDataDate = LocalDate.parse("2026-09-25")
        ) as AiQuestionPlanner.Decision.ProductionCompare

        assertEquals(AiBusinessPeriod(LocalDate.parse("2026-09-19"), LocalDate.parse("2026-09-25")), result.period)
        assertEquals(AiBusinessPeriod(LocalDate.parse("2026-09-12"), LocalDate.parse("2026-09-18")), result.compare)
    }

    @Test fun `LLM selected follow-up comparison reuses prior date range when dates omitted`() {
        val previous = AiBusinessPeriod(LocalDate.parse("2026-09-19"), LocalDate.parse("2026-09-25"))
        val model = mock(LlmChatProxyService::class.java, Answer<Any?> { call ->
            assertTrue((call.arguments[0] as String).contains("[직전 조회 기간]\n2026-09-19~2026-09-25"))
            tool("production_period_compare", """{}""")
        })
        val result = AiQuestionPlanner(model, mapper).plan(
            "전체 생산량과 불량 건수도 보이는 데이터로 다시 조회해 줘",
            today = LocalDate.parse("2026-09-26"),
            previousPeriod = previous,
            latestDataDate = LocalDate.parse("2026-09-25")
        ) as AiQuestionPlanner.Decision.ProductionCompare

        assertEquals(previous, result.period)
        assertNull(result.compare)
        assertEquals(1, mockingDetails(model).invocations.count { it.method.name == "chooseTool" })
    }

    @Test fun `LLM controls date grouping and greeting answer path`() {
        val model = mock(LlmChatProxyService::class.java, Answer<Any?> { call ->
            val prompt = call.arguments[0] as String
            if (prompt.contains("일자별")) tool("production_product_list", """{"from":"2026-09-20","to":"2026-09-22","groupByDate":true}""")
            else mapper.readTree("""{"role":"assistant","content":"안녕하세요.","tool_calls":null}""")
        })
        val planner = AiQuestionPlanner(model, mapper)
        val daily = planner.plan("9월 20일부터 22일까지 제품 목록을 일자별로 정리", LocalDate.parse("2026-09-26"))
            as AiQuestionPlanner.Decision.ProductList
        assertTrue(daily.groupByDate)
        assertEquals(AiBusinessPeriod(LocalDate.parse("2026-09-20"), LocalDate.parse("2026-09-22")), daily.period)
        assertEquals(AiQuestionPlanner.Decision.Other, planner.plan("안녕", LocalDate.parse("2026-09-26")))
        assertEquals(2, mockingDetails(model).invocations.count { it.method.name == "chooseTool" })
    }
}
