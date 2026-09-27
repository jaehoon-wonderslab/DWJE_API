package com.dwje.api

import com.dwje.api.common.security.UserContext
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.config.AppProperties
import com.dwje.api.model.request.AiAskRequest
import com.dwje.api.repository.AiChatRepository
import com.dwje.api.repository.AiFactRepository
import com.dwje.api.repository.DashboardProcessRepository
import com.dwje.api.common.util.ProcessPeriodRow
import com.dwje.api.service.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.mockito.stubbing.Answer
import java.math.BigDecimal
import java.time.LocalDate

class AiToolRoutingDebugTest {
    private val admin = UserPrincipal("admin", "관리자", 1, "전산", null, null, null, true)

    @Test fun `daily defect route returns table evidence and distinguishes empty from denied`() {
        val period = com.dwje.api.common.util.AiBusinessPeriod(LocalDate.parse("2026-09-20"), LocalDate.parse("2026-09-22"))
        val planner = mock(AiQuestionPlanner::class.java, Answer<Any?> { call ->
            if (call.method.name == "plan") AiQuestionPlanner.Decision.DailyProductDefect(period, 200) else null
        })
        val question = "9월 20일~22일까지 생산된 불량 종류 및 제품 구분을 일자별로 정리"
        val rows = listOf(mapOf<String, Any?>("date" to "2026-09-20", "family" to "제품군 A",
            "code" to "M1", "name" to "모델 A", "defect" to "스크래치",
            "quantity" to BigDecimal("3.5"), "totalCount" to 1L))
        var queryRows: List<Map<String, Any?>> = rows
        val facts = mock(AiFactRepository::class.java, Answer<Any?> { call ->
            if (call.method.name == "dailyProductDefects") queryRows else null
        })
        val service = AiDataToolService(mock(DashboardProcessRepository::class.java),
            mock(CommonMasterService::class.java), AppProperties(), facts, planner, mock(AoiDimensionService::class.java))
        val result = service.evidenceForDetailed(question, admin)
        assertEquals("DAILY_PRODUCT_DEFECT", result.route)
        assertEquals("daily_product_defect", result.tool)
        assertEquals("OK", result.executionCode)
        assertEquals(rows, result.rawRows)
        assertTrue(result.evidence.single()["text"].toString().contains("스크래치"))
        val denied = UserPrincipal("noqty", "사용자", 2, "생산", null, null, null, false)
        val deniedResult = service.evidenceForDetailed(question, denied)
        assertEquals("DENIED_FIELDS", deniedResult.executionCode)
        assertTrue(deniedResult.rawRows.isEmpty())
        assertFalse(deniedResult.evidence.toString().contains("3.5"))
        queryRows = emptyList()
        assertEquals("EMPTY", service.evidenceForDetailed(question, admin).executionCode)
    }

    @Test fun `daily defect direct tool validates limits and masks unauthorized quantity`() {
        val facts = mock(AiFactRepository::class.java)
        val service = AiDataToolService(mock(DashboardProcessRepository::class.java),
            mock(CommonMasterService::class.java), AppProperties(), facts, mock(AiQuestionPlanner::class.java), mock(AoiDimensionService::class.java))
        val args = mapOf<String, Any?>("from" to "2026-09-20", "to" to "2026-09-22", "limit" to 200)
        val denied = UserPrincipal("noqty", "사용자", 2, "생산", null, null, null, false)
        assertEquals("DENIED_FIELDS", service.call(AiDataToolService.DAILY_PRODUCT_DEFECT, args, denied)["executionCode"])
        assertFalse(mockingDetails(facts).invocations.any { it.method.name == "dailyProductDefects" })
        assertThrows(com.dwje.api.common.exception.InvalidParameterException::class.java) {
            service.call(AiDataToolService.DAILY_PRODUCT_DEFECT, args + ("limit" to 201), admin)
        }
        assertThrows(com.dwje.api.common.exception.InvalidParameterException::class.java) {
            service.call(AiDataToolService.DAILY_PRODUCT_DEFECT, args + ("to" to "2026-10-22"), admin)
        }
    }

    @Test fun `daily defect raw rows render as date model type table without schema fields`() {
        val chat = AiChatService(mock(AiChatRepository::class.java), mock(GlossaryNormalizer::class.java),
            mock(AuditLogService::class.java), mock(AuthorizationService::class.java),
            mock(AgentRunRecorder::class.java), mock(DataFieldService::class.java),
            mock(AiDataToolService::class.java), mock(AiAskDebugRecorder::class.java))
        val period = com.dwje.api.common.util.AiBusinessPeriod(LocalDate.parse("2026-09-20"), LocalDate.parse("2026-09-22"))
        val result = AiDataToolService.EvidenceResult(emptyList(), "DAILY_PRODUCT_DEFECT", "daily_product_defect",
            "LLM_STRUCTURED", "OK", null, 1, period, 5,
            listOf(mapOf("date" to "2026-09-20", "family" to "제품군 A", "name" to "모델 A",
                "code" to "M1", "defect" to "스크래치", "quantity" to BigDecimal(3))))
        val method = AiChatService::class.java.getDeclaredMethod("buildBlocks", String::class.java,
            List::class.java, AiDataToolService.EvidenceResult::class.java, UserPrincipal::class.java)
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val blocks = method.invoke(chat, "metric", emptyList<Map<String, Any?>>(), result, admin) as List<Map<String, Any?>>
        assertEquals("table", blocks.single()["type"])
        assertEquals(listOf("일자", "제품군", "제품명", "모델코드", "불량 유형", "불량 수량"), blocks.single()["head"])
        assertEquals(listOf("2026-09-20", "제품군 A", "모델 A", "M1", "스크래치", "3"),
            (blocks.single()["rows"] as List<*>).single())
        assertFalse(blocks.toString().contains("tb_pop_"))
    }

    @Test fun `plant-wide comparison computes percentage point change and renders totals table`() {
        val current = com.dwje.api.common.util.AiBusinessPeriod(LocalDate.parse("2026-09-19"), LocalDate.parse("2026-09-25"))
        val previous = com.dwje.api.common.util.AiBusinessPeriod(LocalDate.parse("2026-09-12"), LocalDate.parse("2026-09-18"))
        val planner = mock(AiQuestionPlanner::class.java, Answer<Any?> { call ->
            if (call.method.name == "plan") AiQuestionPlanner.Decision.ProductionCompare(current, previous) else null
        })
        var queryNo = 0
        val dashboard = mock(DashboardProcessRepository::class.java, Answer<Any?> { call ->
            if (call.method.name != "findPeriod") return@Answer null
            queryNo++
            val total = if (queryNo == 1) ProcessPeriodRow.of(BigDecimal("90"), BigDecimal("10"))
                else ProcessPeriodRow.of(BigDecimal("92"), BigDecimal("8"))
            listOf("summary" to total)
        })
        val dataService = AiDataToolService(dashboard, mock(CommonMasterService::class.java), AppProperties(),
            mock(AiFactRepository::class.java), planner, mock(AoiDimensionService::class.java))
        val evidence = dataService.evidenceForDetailed("지난 일주일간 공장 전체 불량률은 몇 %p 변했나?", admin)

        assertEquals("PRODUCTION_COMPARE", evidence.route)
        assertTrue(evidence.evidence.first()["text"].toString().contains("+2.00%p"))
        assertEquals(2.0, evidence.rawRows.first()["defectRateDeltaPt"])

        val chat = AiChatService(mock(AiChatRepository::class.java), mock(GlossaryNormalizer::class.java),
            mock(AuditLogService::class.java), mock(AuthorizationService::class.java),
            mock(AgentRunRecorder::class.java), mock(DataFieldService::class.java), dataService,
            mock(AiAskDebugRecorder::class.java))
        val method = AiChatService::class.java.getDeclaredMethod("buildBlocks", String::class.java,
            List::class.java, AiDataToolService.EvidenceResult::class.java, UserPrincipal::class.java)
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val blocks = method.invoke(chat, "metric", emptyList<Map<String, Any?>>(), evidence, admin) as List<Map<String, Any?>>
        assertEquals("table", blocks.single()["type"])
        assertEquals(listOf("기간", "업무일", "전체 생산량", "불량 건수", "불량률", "불량률 변화"), blocks.single()["head"])
        assertTrue(blocks.toString().contains("+2.00%p"))
        assertFalse(blocks.toString().contains("SELECT"))
    }

    @AfterEach fun clear() = UserContext.clear()

    private fun planner(): AiQuestionPlanner = mock(AiQuestionPlanner::class.java, Answer<Any?> { call ->
        val period = com.dwje.api.common.util.AiBusinessPeriod(LocalDate.parse("2026-09-20"), LocalDate.parse("2026-09-22"))
        if ((call.arguments[0] as String).contains("목록")) AiQuestionPlanner.Decision.ProductList(period)
        else AiQuestionPlanner.Decision.DefectRateTop(period, 20)
    })

    @Test fun `requested product list and rate top twenty select fact tools and return evidence`() {
        val facts = mock(AiFactRepository::class.java, Answer<Any?> { call ->
            when (call.method.name) {
                "producedProducts" -> listOf(mapOf<String, Any?>("code" to "P1", "name" to "제품 A",
                    "quantity" to BigDecimal(30), "totalCount" to 1L))
                "topDefectRates" -> listOf(mapOf<String, Any?>("code" to "P1", "name" to "제품 A",
                    "quantity" to BigDecimal(30), "defectQuantity" to BigDecimal(3),
                    "defectRate" to BigDecimal("10.00")))
                else -> null
            }
        })
        val master = mock(CommonMasterService::class.java)
        val service = AiDataToolService(mock(DashboardProcessRepository::class.java), master, AppProperties(), facts, planner(), mock(AoiDimensionService::class.java))
        val products = service.evidenceForDetailed("9월 20일 부터 9월 22일까지 생산된 제품 목록 출력", admin)
        val rates = service.evidenceForDetailed("09-20 부터 09-22 까지 불량률 top 20", admin)
        assertEquals("PRODUCT_LIST", products.route)
        assertEquals("production_product_list", products.tool)
        assertEquals("LLM_STRUCTURED", products.parseCode)
        assertEquals("OK", products.executionCode)
        assertEquals(1, products.rowCount)
        assertTrue(products.evidence.single()["text"].toString().contains("제품 A"))
        assertEquals("DEFECT_RATE_TOP", rates.route)
        assertEquals("defect_rate_top", rates.tool)
        assertEquals("LLM_STRUCTURED", rates.parseCode)
        assertEquals(1, rates.rowCount)
        assertTrue(rates.evidence.single()["text"].toString().contains("10.00%"))
        assertEquals(LocalDate.parse("2026-09-20"), rates.period?.from)
        assertTrue(mockingDetails(master).invocations.any { it.method.name == "getProductionDateRange" })
    }

    @Test fun `empty defect rate result explains missing requested ranking without turning it into production summary`() {
        val facts = mock(AiFactRepository::class.java, Answer<Any?> { call ->
            if (call.method.name == "topDefectRates") emptyList<Map<String, Any?>>() else null
        })
        val service = AiDataToolService(mock(DashboardProcessRepository::class.java),
            mock(CommonMasterService::class.java), AppProperties(), facts, planner(), mock(AoiDimensionService::class.java))

        val result = service.evidenceForDetailed("2026-09-25부터 2026-09-25까지 제품별 불량률 상위 5종 조회", admin)

        assertEquals("DEFECT_RATE_TOP", result.route)
        assertEquals("defect_rate_top", result.tool)
        assertEquals("EMPTY", result.executionCode)
        assertTrue(result.evidence.single()["text"].toString().contains("제품별 불량률 순위 자료가 없습니다"))
        assertFalse(result.evidence.single()["text"].toString().contains("생산 실적"))
        assertTrue(result.rawRows.isEmpty())
        assertFalse(mockingDetails(facts).invocations.any { it.method.name == "aggregate" })
    }

    @Test fun `tool failure exposes only stable code without SQL or parameters`() {
        val facts = mock(AiFactRepository::class.java, Answer<Any?> { throw IllegalStateException("SELECT secret_parameter") })
        val service = AiDataToolService(mock(DashboardProcessRepository::class.java),
            mock(CommonMasterService::class.java), AppProperties(), facts, planner(), mock(AoiDimensionService::class.java))
        val result = service.evidenceForDetailed("09-20 부터 09-22 까지 불량률 top 20", admin)
        assertEquals("FAILED", result.executionCode)
        assertEquals("QUERY_FAILED", result.errorCode)
        assertEquals(0, result.rowCount)
        assertFalse(result.toString().contains("secret_parameter"))
    }

    @Test fun `model callable tools expose bounded product and rate queries`() {
        val facts = mock(AiFactRepository::class.java)
        val service = AiDataToolService(mock(DashboardProcessRepository::class.java),
            mock(CommonMasterService::class.java), AppProperties(), facts, planner(), mock(AoiDimensionService::class.java))
        assertTrue(service.listTools().any { it["name"] == AiDataToolService.PRODUCTION_PRODUCT_LIST })
        assertTrue(service.listTools().any { it["name"] == AiDataToolService.DEFECT_RATE_TOP })
        val args = mapOf<String, Any?>("from" to "2026-09-20", "to" to "2026-09-22", "limit" to 20)
        val result = service.call(AiDataToolService.DEFECT_RATE_TOP, args, admin)
        assertEquals(20, result["limit"])
        val invocation = mockingDetails(facts).invocations.single { it.method.name == "topDefectRates" }
        val period = invocation.arguments[1] as com.dwje.api.common.util.AiBusinessPeriod
        assertEquals("2026-09-19T08:00", period.startInclusive.toString())
        assertEquals("2026-09-22T08:00", period.endExclusive.toString())
        assertEquals(20, invocation.arguments[2])
        assertThrows(com.dwje.api.common.exception.InvalidParameterException::class.java) {
            service.call(AiDataToolService.DEFECT_RATE_TOP, args + ("limit" to 21), admin)
        }
    }

    @Test fun `each ask records route counts and time without storing question in debug object`() {
        UserContext.set(admin)
        val question = "09-20 부터 09-22 까지 불량률 top 20"
        val repo = mock(AiChatRepository::class.java)
        val glossary = mock(GlossaryNormalizer::class.java)
        `when`(glossary.normalize(question)).thenReturn(GlossaryNormalizer.NormalizeResult(question, emptyList()))
        val fields = mock(DataFieldService::class.java)
        `when`(fields.blindKeysFor(admin)).thenReturn(emptySet())
        val tools = mock(AiDataToolService::class.java)
        val period = com.dwje.api.common.util.AiBusinessPeriod(LocalDate.parse("2026-09-20"), LocalDate.parse("2026-09-22"))
        `when`(tools.evidenceForDetailed(question, admin)).thenReturn(AiDataToolService.EvidenceResult(
            listOf(mapOf("title" to "제품별 불량률", "text" to "제품 A 10%", "tool" to "defect_rate_top")),
            "DEFECT_RATE_TOP", "defect_rate_top", "EXPLICIT_RANGE", "OK", null, 1, period, 12))
        val recorder = mock(AiAskDebugRecorder::class.java)
        val chat = AiChatService(repo, glossary, mock(AuditLogService::class.java),
            mock(AuthorizationService::class.java), mock(AgentRunRecorder::class.java), fields, tools, recorder)
        val response = chat.ask(AiAskRequest(question = question))
        assertEquals("defect_rate_top", (response["dataEvidence"] as List<*>).first().let { (it as Map<*, *>)["tool"] })
        val debug = mockingDetails(recorder).invocations.single { it.method.name == "record" }.arguments[0] as AiAskDebug
        assertEquals("DEFECT_RATE_TOP", debug.route)
        assertEquals("EXPLICIT_RANGE", debug.parseCode)
        assertEquals("OK", debug.executionCode)
        assertEquals(1, debug.rowCount)
        assertEquals(12, debug.toolMs)
        assertFalse(debug.toString().contains(question))
    }

    @Test fun `structured production query skips stale vector documents and keeps database evidence only`() {
        UserContext.set(admin)
        val question = "2026-09-25부터 2026-09-25까지 제품별 불량률 상위 5종 조회"
        val repo = mock(AiChatRepository::class.java)
        val glossary = mock(GlossaryNormalizer::class.java)
        `when`(glossary.normalize(question)).thenReturn(GlossaryNormalizer.NormalizeResult(question, emptyList()))
        val fields = mock(DataFieldService::class.java)
        `when`(fields.blindKeysFor(admin)).thenReturn(emptySet())
        val tools = mock(AiDataToolService::class.java)
        `when`(tools.evidenceForDetailed(question, admin, null)).thenReturn(AiDataToolService.EvidenceResult(
            listOf(mapOf("title" to "제품별 불량률 상위 5", "text" to "DB 조회 결과가 없습니다.", "tool" to "defect_rate_top")),
            "DEFECT_RATE_TOP", "defect_rate_top", "LLM_STRUCTURED", "EMPTY", null, 0,
            com.dwje.api.common.util.AiBusinessPeriod(LocalDate.parse("2026-09-25"), LocalDate.parse("2026-09-25")), 8))
        val chat = AiChatService(repo, glossary, mock(AuditLogService::class.java),
            mock(AuthorizationService::class.java), mock(AgentRunRecorder::class.java), fields, tools,
            mock(AiAskDebugRecorder::class.java))

        val response = chat.ask(AiAskRequest(question = question))

        assertTrue((response["sources"] as List<*>).isEmpty())
        verify(repo, never()).searchDocumentChunks(anyString(), anyInt(), anyInt(), anyBoolean())
    }

    @Test fun `failed ask still emits diagnostic code without original input`() {
        UserContext.set(admin)
        val question = "password=hidden-value 불량 조회"
        val glossary = mock(GlossaryNormalizer::class.java)
        `when`(glossary.normalize(question)).thenThrow(IllegalStateException("secret detail"))
        val recorder = mock(AiAskDebugRecorder::class.java)
        val chat = AiChatService(mock(AiChatRepository::class.java), glossary, mock(AuditLogService::class.java),
            mock(AuthorizationService::class.java), mock(AgentRunRecorder::class.java),
            mock(DataFieldService::class.java), mock(AiDataToolService::class.java), recorder)
        assertThrows(IllegalStateException::class.java) { chat.ask(AiAskRequest(question = question)) }
        val debug = mockingDetails(recorder).invocations.single { it.method.name == "record" }.arguments[0] as AiAskDebug
        assertEquals("ASK_FAILED", debug.executionCode)
        assertEquals("ASK_FAILED", debug.errorCode)
        assertFalse(debug.toString().contains("hidden-value"))
        assertFalse(debug.toString().contains("secret detail"))
    }
}
