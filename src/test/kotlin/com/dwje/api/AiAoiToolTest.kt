package com.dwje.api

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.exception.MenuAccessDeniedException
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.common.util.MaskingSupport
import com.dwje.api.config.AoiProperties
import com.dwje.api.config.AppProperties
import com.dwje.api.repository.AiFactRepository
import com.dwje.api.repository.DashboardProcessRepository
import com.dwje.api.service.*
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.mockito.stubbing.Answer
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class AiAoiToolTest {
    private val admin = UserPrincipal("admin", "관리자", 1, "전산", null, null, true)
    private val denied = UserPrincipal("user", "사용자", 2, "생산", null, null, false)
    private val masked = denied.copy(menuPerms = setOf("qc-aoi"))
    private val mapper = ObjectMapper()

    private fun plannerMessage(arguments: String) = mapper.readTree(
        """{"tool_calls":[{"function":{"name":"aoi_dimension_summary","arguments":${mapper.writeValueAsString(arguments)}}}]}""")

    @Test fun `AOI planner selects screen summary and requires explicit workcenter`() {
        val model = mock(LlmChatProxyService::class.java, Answer<Any?> { call ->
            if (call.method.name != "chooseTool") null
            else if ((call.arguments[0] as String).contains("S120") || (call.arguments[0] as String).contains("추측"))
                plannerMessage("""{"from":"2026-09-22","to":"2026-09-23","wcCd":"S120","eqptCd":"AOI01"}""")
            else plannerMessage("""{"wcCd":""}""")
        })
        val planner = AiQuestionPlanner(model, mapper)
        val chosen = planner.plan("S120 작업장 AOI01 설비 AOI 판정 요약", LocalDate.parse("2026-09-26"))
            as AiQuestionPlanner.Decision.AoiSummary
        assertEquals("S120", chosen.wcCd)
        assertEquals("AOI01", chosen.eqptCd)
        assertEquals(LocalDate.parse("2026-09-22"), chosen.from)
        assertEquals(AiQuestionPlanner.Decision.AoiWorkcenterRequired,
            planner.plan("AOI 판정 요약", LocalDate.parse("2026-09-26")))
        assertEquals(AiQuestionPlanner.Decision.AoiWorkcenterRequired,
            planner.plan("AOI01 설비 AOI 판정 요약", LocalDate.parse("2026-09-26")))
        assertEquals(AiQuestionPlanner.Decision.AoiWorkcenterRequired,
            planner.plan("AOI 판정 작업장 추측 금지", LocalDate.parse("2026-09-26")))
    }

    @Test fun `AOI planner rejects invalid date and unsafe workcenter`() {
        var arguments = """{"wcCd":"S120;DROP","from":"2026-09-22"}"""
        val model = mock(LlmChatProxyService::class.java, Answer<Any?> { call ->
            if (call.method.name == "chooseTool") plannerMessage(arguments) else null
        })
        val planner = AiQuestionPlanner(model, mapper)
        assertEquals(AiQuestionPlanner.Decision.Invalid(), planner.plan("AOI", LocalDate.parse("2026-09-26")))
        arguments = """{"wcCd":"S120","to":"2026-09-27"}"""
        assertEquals(AiQuestionPlanner.Decision.Invalid(AiQuestionPlanner.InvalidReason.FUTURE), planner.plan("AOI", LocalDate.parse("2026-09-26")))
    }

    private fun service(aoi: AoiDimensionService, planner: AiQuestionPlanner = mock(AiQuestionPlanner::class.java)) =
        AiDataToolService(mock(DashboardProcessRepository::class.java), mock(CommonMasterService::class.java),
            AppProperties(aoi = AoiProperties(maxDays = 7)), mock(AiFactRepository::class.java), planner, aoi)

    private fun aoiMock(): AoiDimensionService = mock(AoiDimensionService::class.java, Answer<Any?> { call ->
        when (call.method.name) {
            "periodOf" -> {
                val to = (call.arguments[1] as String?)?.let(LocalDate::parse) ?: LocalDate.parse("2026-09-26")
                val from = (call.arguments[0] as String?)?.let(LocalDate::parse) ?: to
                if (from.isAfter(to) || ChronoUnit.DAYS.between(from, to) >= 7)
                    throw InvalidParameterException("AOI 기간 상한", "to")
                from to to
            }
            "getSummary" -> {
                val principal = if ((call.arguments[2] as String) == "MASKED") masked else admin
                val showQty = principal.superAdmin
                val block = mapOf("eqptCd" to null, "measCnt" to if (showQty) 100L else null,
                    "failCnt" to if (showQty) 5L else null, "failRate" to if (showQty) 5.0 else null,
                    "explainedRate" to if (showQty) 80.0 else null, "fais" to listOf(mapOf("secret" to "raw")))
                (mapOf("wcCd" to call.arguments[2], "current" to mapOf("total" to block,
                    "equipments" to emptyList<Map<String, Any?>>() ), "limitSets" to listOf("secret")) to MaskingSupport(principal))
            }
            else -> null
        }
    })

    @Test fun `AOI call enforces menu workcenter period and only public projected values`() {
        val aoi = aoiMock()
        val service = service(aoi)
        val args = mapOf<String, Any?>("wcCd" to "S120", "from" to "2026-09-22", "to" to "2026-09-23")
        assertThrows(MenuAccessDeniedException::class.java) { service.call(AiDataToolService.AOI_DIMENSION_SUMMARY, args, denied) }
        assertFalse(mockingDetails(aoi).invocations.any { it.method.name == "getSummary" })
        assertEquals("WORKCENTER_REQUIRED", service.call(AiDataToolService.AOI_DIMENSION_SUMMARY, emptyMap(), admin)["executionCode"])
        assertFalse(mockingDetails(aoi).invocations.any { it.method.name == "getSummary" })
        assertThrows(InvalidParameterException::class.java) {
            service.call(AiDataToolService.AOI_DIMENSION_SUMMARY, args + ("to" to "2026-09-29"), admin)
        }
        val response = service.call(AiDataToolService.AOI_DIMENSION_SUMMARY, args, admin)
        assertEquals("OK", response["executionCode"])
        @Suppress("UNCHECKED_CAST")
        val rows = response["rows"] as List<Map<String, Any?>>
        assertEquals(100L, rows.single()["measCnt"])
        assertEquals(setOf("scope", "equipment", "measCnt", "failCnt", "failRate", "explainedRate"), rows.single().keys)
        assertFalse(response.toString().contains("secret"))
        assertEquals("S120", mockingDetails(aoi).invocations.single { it.method.name == "getSummary" }.arguments[2])
    }

    @Test fun `AOI chat evidence and table preserve qty yield masking`() {
        val aoi = aoiMock()
        val planner = mock(AiQuestionPlanner::class.java, Answer<Any?> { call ->
            if (call.method.name == "plan") AiQuestionPlanner.Decision.AoiSummary(null, null, "MASKED", null) else null
        })
        val service = service(aoi, planner)
        val result = service.evidenceForDetailed("MASKED 작업장 AOI 판정 요약", masked)
        assertEquals("AOI_DIMENSION_SUMMARY", result.route)
        assertEquals("OK", result.executionCode)
        assertNull(result.rawRows.single()["measCnt"])
        assertNull(result.rawRows.single()["failRate"])
        assertFalse(result.evidence.toString().contains("100"))
        assertFalse(result.evidence.toString().contains("secret"))

        val chat = AiChatService(mock(com.dwje.api.repository.AiChatRepository::class.java), mock(GlossaryNormalizer::class.java),
            mock(AuditLogService::class.java), mock(AuthorizationService::class.java),
            mock(AgentRunRecorder::class.java), mock(DataFieldService::class.java),
            mock(AiDataToolService::class.java), mock(AiAskDebugRecorder::class.java))
        val method = AiChatService::class.java.getDeclaredMethod("buildBlocks", String::class.java,
            List::class.java, AiDataToolService.EvidenceResult::class.java, UserPrincipal::class.java)
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val blocks = method.invoke(chat, "metric", emptyList<Map<String, Any?>>(), result, masked) as List<Map<String, Any?>>
        assertEquals("table", blocks.single()["type"])
        assertEquals(listOf("작업장 전체", "전체", "비공개", "비공개", "비공개", "비공개"),
            (blocks.single()["rows"] as List<*>).single())
        assertFalse(blocks.toString().contains("secret"))
    }

    @Test fun `AOI missing workcenter and menu denial stop before source lookup`() {
        val aoi = aoiMock()
        val planner = mock(AiQuestionPlanner::class.java, Answer<Any?> { call ->
            if (call.method.name == "plan") AiQuestionPlanner.Decision.AoiWorkcenterRequired else null
        })
        val service = service(aoi, planner)
        val required = service.evidenceForDetailed("AOI 판정 요약", admin)
        assertEquals("WORKCENTER_REQUIRED", required.executionCode)
        assertTrue(required.evidence.single()["text"].toString().contains("작업장"))
        val noMenu = service.evidenceForDetailed("AOI 판정 요약", denied)
        assertEquals("DENIED_MENU", noMenu.executionCode)
        assertFalse(mockingDetails(aoi).invocations.any { it.method.name == "getSummary" })
    }
}
