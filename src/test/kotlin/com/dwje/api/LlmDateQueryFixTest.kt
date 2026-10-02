package com.dwje.api

import com.dwje.api.common.security.UserContext
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.common.util.AiBusinessPeriod
import com.dwje.api.config.AiProperties
import com.dwje.api.config.AppProperties
import com.dwje.api.config.LlmProxyProperties
import com.dwje.api.controller.LlmChatProxyController
import com.dwje.api.model.request.AiAskRequest
import com.dwje.api.model.request.LlmChatMessage
import com.dwje.api.model.request.LlmChatRequest
import com.dwje.api.repository.AiChatRepository
import com.dwje.api.service.*
import com.dwje.api.service.AiQuestionPlanner.Decision
import com.dwje.api.service.AiQuestionPlanner.InvalidReason
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.mockito.stubbing.Answer
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.net.InetSocketAddress
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

/** 덕반장 날짜 질의 보정(2026-10-02) — 날짜 너그럽게 읽기·고정 답·샘플링 값. 실서버 호출 없음 */
class LlmDateQueryFixTest {

    private val json = ObjectMapper()
    private val today = LocalDate.parse("2026-10-02")
    private val admin = UserPrincipal("admin", "관리자", 1, "전산", null, null, true)

    @AfterEach fun clear() = UserContext.clear()

    private class Fake(private val reply: (String) -> String) : AutoCloseable {
        val bodies = CopyOnWriteArrayList<String>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val body = ex.requestBody.readAllBytes().toString(Charsets.UTF_8)
                bodies += body
                val bytes = reply(body).toByteArray()
                ex.sendResponseHeaders(200, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
        val url get() = "http://127.0.0.1:${server.address.port}"
        override fun close() = server.stop(0)
    }

    private fun proxy(app: AppProperties) =
        LlmChatProxyService(app, json, AiChatRepository(NamedParameterJdbcTemplate(DriverManagerDataSource())), SllmClient(app, json))

    private fun plannerReturning(tool: String, args: String): AiQuestionPlanner {
        val model = mock(LlmChatProxyService::class.java, Answer<Any?> { call ->
            if (call.method.name == "chooseTool") json.readTree(
                """{"tool_calls":[{"id":"c1","type":"function","function":{"name":"$tool","arguments":${json.writeValueAsString(args)}}}]}""")
            else null
        })
        return AiQuestionPlanner(model, json)
    }

    @Test
    @DisplayName("날짜 읽기 — yyyy-M-d·yyyy/M/d·yyyy.M.d, 연도 없는 M/d·M-d 는 기준일을 넘지 않는 가장 최근 연도, 없는 날짜는 null")
    fun parseDate() {
        fun p(t: String, notAfter: LocalDate = today) = AiQuestionPlanner.parseDate(t, notAfter)?.toString()
        assertEquals("2026-09-20", p("2026-09-20"))
        assertEquals("2026-09-20", p("2026-9-20"))
        assertEquals("2026-09-20", p("2026/9/20"))
        assertEquals("2026-09-20", p("2026.09.20."))
        assertEquals("2026-09-20", p("9/20"))
        assertEquals("2026-09-20", p("09-20"))
        assertEquals("2026-10-02", p("10/2"), "오늘은 그해")
        assertEquals("2025-10-03", p("10/3"), "내일 날짜는 작년")
        assertEquals("2025-12-25", p("12/25"))
        assertEquals("2024-02-29", p("2/29"), "윤년까지 거슬러 찾는다")
        assertEquals("2025-12-28", p("12/28", LocalDate.parse("2026-01-03")), "시작일은 종료일 기준 — 해를 넘는 기간")
        listOf("2/30", "2026-13-01", "9월 20일", "", "20260920", "abc").forEach { assertNull(p(it), it) }
    }

    @Test
    @DisplayName("계획기 — to 를 빼먹은 실제 인자(9/20)는 그 하루, 연도 없는 값도 통과, 미래·92일·시작>종료·순위 검사는 그대로(갈래별)")
    fun plannerLenient() {
        fun plan(args: String, tool: String = "defect_top") = plannerReturning(tool, args).plan("q", today)
        val day = AiBusinessPeriod(LocalDate.parse("2026-09-20"), LocalDate.parse("2026-09-20"))
        assertEquals(Decision.DefectTop(day, 3), plan("""{"from":"2026-09-20","limit":3}"""), "2026-10-02 실측 인자")
        assertEquals(Decision.DefectTop(day, 3), plan("""{"to":"2026-09-20","limit":3}"""))
        assertEquals(Decision.DefectTop(day, 3), plan("""{"from":"9/20","to":"9/20","limit":3}"""))
        assertEquals(Decision.DefectRateTop(AiBusinessPeriod(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-10")), 3),
            plan("""{"from":"2026/9/1","to":"2026-9-10","limit":3}""", "defect_rate_top"))
        assertEquals(Decision.Invalid(InvalidReason.FUTURE), plan("""{"from":"2026-10-03","to":"2026-10-03","limit":3}"""))
        assertEquals(Decision.Invalid(InvalidReason.ORDER), plan("""{"from":"2026-09-21","to":"2026-09-20","limit":3}"""))
        assertEquals(Decision.Invalid(InvalidReason.SPAN), plan("""{"from":"2026-01-01","to":"2026-09-20","limit":3}"""))
        assertEquals(Decision.Invalid(InvalidReason.LIMIT), plan("""{"from":"2026-09-20","to":"2026-09-20","limit":50}"""))
        assertEquals(Decision.Invalid(InvalidReason.DATE), plan("""{"from":"9월 20일","to":"9월 20일","limit":3}"""))
        assertEquals(Decision.Invalid(InvalidReason.DATE), plan("""{"limit":3}"""))
    }

    @Test
    @DisplayName("고정 문구 — 0건은 기간·도구 종류·MES 최신 실적일, 조건 오류는 갈래별, 나머지는 null(LLM 이 답함)")
    fun fixedTexts() {
        fun result(tool: String, exec: String, from: String, to: String, parse: String = "LLM_STRUCTURED") = AiDataToolService.EvidenceResult(
            emptyList(), "R", tool, parse, exec, null, 0, AiBusinessPeriod(LocalDate.parse(from), LocalDate.parse(to)), 1,
            latestDataDate = LocalDate.parse("2026-09-30"))
        assertEquals("2026-09-25 의 불량 유형 실적이 없습니다. MES 최신 실적일은 2026-09-30 입니다.",
            AiFixedAnswer.of(result("defect_top", "EMPTY", "2026-09-25", "2026-09-25"), today))
        assertEquals("2026-09-01~2026-09-10 의 제품별 불량률 실적이 없습니다. MES 최신 실적일은 2026-09-30 입니다.",
            AiFixedAnswer.of(result("defect_rate_top", "EMPTY", "2026-09-01", "2026-09-10"), today))
        assertNull(AiFixedAnswer.of(result("defect_top", "OK", "2026-09-25", "2026-09-25"), today))
        val invalid = AiDataToolService.EvidenceResult(emptyList(), "INVALID", null, "INVALID", "SKIPPED", "INVALID_CONDITION", 0, null, 1,
            invalidReason = InvalidReason.DATE)
        assertEquals("조회 날짜를 이해하지 못했습니다. 2026-09-20 또는 9월 20일 처럼 다시 입력해 주십시오.", AiFixedAnswer.of(invalid, today))
        assertTrue(AiFixedAnswer.of(invalid.copy(invalidReason = InvalidReason.FUTURE), today)!!.contains("오늘(2026-10-02)"))
        assertTrue(AiFixedAnswer.of(invalid.copy(invalidReason = InvalidReason.LIMIT), today)!!.contains("1~20위"))
    }

    @Test
    @DisplayName("ask — 0건이면 문서 검색 없이 고정 답을 이력 답으로 적고 fixedAnswer 로 돌려준다, INVALID 도 문서 검색 없음, route 진단은 그대로")
    fun askFixedAnswer() {
        UserContext.set(admin)
        val question = "9/25 불량 유형 top 3"
        val repo = mock(AiChatRepository::class.java)
        val glossary = mock(GlossaryNormalizer::class.java)
        `when`(glossary.normalize(anyString())).thenAnswer { GlossaryNormalizer.NormalizeResult(it.arguments[0] as String, emptyList()) }
        val fields = mock(DataFieldService::class.java)
        `when`(fields.blindKeysFor(admin)).thenReturn(emptySet())
        val tools = mock(AiDataToolService::class.java)
        val empty = AiDataToolService.EvidenceResult(
            listOf(mapOf("title" to "불량 상위", "text" to "DB 조회 결과가 없습니다.", "tool" to "defect_top")),
            "DEFECT_TOP", "defect_top", "LLM_STRUCTURED", "EMPTY", null, 0,
            AiBusinessPeriod(LocalDate.parse("2026-09-25"), LocalDate.parse("2026-09-25")), 8, latestDataDate = LocalDate.parse("2026-09-30"))
        `when`(tools.evidenceForDetailed(question, admin, null)).thenReturn(empty)
        val recorder = mock(AiAskDebugRecorder::class.java)
        val chat = AiChatService(repo, glossary, mock(AuditLogService::class.java), mock(AuthorizationService::class.java),
            mock(AgentRunRecorder::class.java), fields, tools, recorder)

        val response = chat.ask(AiAskRequest(question = question))
        val fixed = "2026-09-25 의 불량 유형 실적이 없습니다. MES 최신 실적일은 2026-09-30 입니다."
        assertEquals(fixed, response["fixedAnswer"])
        val insert = mockingDetails(repo).invocations.single { it.method.name == "insertChatLog" }
        assertTrue(insert.arguments.contains(fixed), "질의 이력 답으로 적는다")
        val debug = mockingDetails(recorder).invocations.single { it.method.name == "record" }.arguments[0] as AiAskDebug
        assertEquals("DEFECT_TOP", debug.route); assertEquals("EMPTY", debug.executionCode)

        val q2 = "9/20 불량 유형 top 3"
        `when`(tools.evidenceForDetailed(q2, admin, null)).thenReturn(AiDataToolService.EvidenceResult(emptyList(), "INVALID", null,
            "INVALID", "SKIPPED", "INVALID_CONDITION", 0, null, 1, invalidReason = InvalidReason.DATE))
        assertTrue((chat.ask(AiAskRequest(question = q2))["fixedAnswer"] as String).startsWith("조회 날짜를 이해하지 못했습니다."))
        verify(repo, never()).searchDocumentChunks(anyString(), anyInt(), anyInt(), anyBoolean())
    }

    @Test
    @DisplayName("/api/ai/chat — 고정 답이 있으면(ask 결과 또는 messageId 이력) 모델을 부르지 않고 SSE 한 번으로 보낸다")
    fun chatSkipsModel() {
        Fake { "data: [DONE]\n\n" }.use { f ->
            val app = AppProperties(llm = LlmProxyProperties(baseUrl = f.url))
            val svc = proxy(app)
            val chat = mock(AiChatService::class.java)
            UserContext.set(admin)
            `when`(chat.ask(AiAskRequest(null, "9/25 불량 유형 top 3"))).thenReturn(mapOf("messageId" to 7L, "sessionId" to "s",
                "fixedAnswer" to "2026-09-25 의 불량 유형 실적이 없습니다."))
            `when`(chat.storedFixedAnswer(8L, "admin")).thenReturn("조회 날짜를 이해하지 못했습니다.")
            val controller = LlmChatProxyController(svc, chat, mock(AiDataToolService::class.java), app, mock(AuthorizationService::class.java))

            val first = MockHttpServletResponse()
            controller.chat(LlmChatRequest(listOf(LlmChatMessage("user", "9/25 불량 유형 top 3"))), MockHttpServletRequest(), first)
            assertTrue(first.contentAsString.contains("2026-09-25 의 불량 유형 실적이 없습니다.") && first.contentAsString.contains("[DONE]"))
            assertEquals("7", first.getHeader("X-AI-Message-Id"))

            val second = MockHttpServletResponse()
            controller.chat(LlmChatRequest(listOf(LlmChatMessage("user", "9/20 불량 유형 top 3")), messageId = 8L), MockHttpServletRequest(), second)
            assertTrue(second.contentAsString.contains("조회 날짜를 이해하지 못했습니다."))
            assertTrue(f.bodies.isEmpty(), "모델 호출 없음")
        }
    }

    @Test
    @DisplayName("샘플링 — 도구 선택은 temperature 0(설정값), 문서 스트리밍은 temperature 0, chatJson 은 0.2·0.9·64·1.05, top-k 0 이하는 보내지 않는다")
    fun sampling() {
        Fake { body ->
            if (json.readTree(body).path("stream").asBoolean()) "data: [DONE]\n\n"
            else """{"choices":[{"message":{"content":"{\"name\":\"none\",\"arguments\":{}}"},"finish_reason":"stop"}]}"""
        }.use { f ->
            val app = AppProperties(llm = LlmProxyProperties(baseUrl = f.url, toolMode = "json-schema"), ai = AiProperties(baseUrl = f.url))
            val svc = proxy(app)
            svc.chooseTool("x", AiDataToolService.TOOLS, today)
            val tool = json.readTree(f.bodies.last())
            assertEquals(0.0, tool.path("temperature").asDouble(-1.0))
            assertFalse(tool.has("top_k"))
            svc.open(listOf(mapOf("role" to "user", "content" to "안녕"))).use { it.readAllBytes() }
            SllmClient(app, json).chatJson("지시", "근거", mapOf("type" to "object"))
            f.bodies.takeLast(2).map(json::readTree).forEach { b ->
                assertEquals(if (b.path("stream").asBoolean()) 0.0 else 0.2, b.path("temperature").asDouble()); assertEquals(0.9, b.path("top_p").asDouble())
                assertEquals(64, b.path("top_k").asInt()); assertEquals(1.05, b.path("repetition_penalty").asDouble())
            }
        }
        Fake { "data: [DONE]\n\n" }.use { f ->
            val app = AppProperties(llm = LlmProxyProperties(baseUrl = f.url, toolMode = "native", toolTemperature = 0.3,
                sampling = LlmProxyProperties.Sampling(temperature = 0.5, topK = 0)))
            val svc = proxy(app)
            svc.chooseTool("x", AiDataToolService.TOOLS, today)
            assertEquals(0.3, json.readTree(f.bodies.last()).path("temperature").asDouble())
            assertTrue(json.readTree(f.bodies.last()).has("tools"))
            svc.open(listOf(mapOf("role" to "user", "content" to "안녕"))).use { it.readAllBytes() }
            val stream = json.readTree(f.bodies.last())
            assertEquals(0.0, stream.path("temperature").asDouble()); assertFalse(stream.has("top_k"))
        }
    }

    @Test
    @DisplayName("json-schema 도구 선택 — 도구별 anyOf 갈래로 name 에 맞는 arguments(required) 를 강제한다, 도구 없음 갈래 포함")
    fun toolChoiceSchema() {
        val svc = proxy(AppProperties(llm = LlmProxyProperties(toolMode = "json-schema")))
        val branches = json.valueToTree<com.fasterxml.jackson.databind.JsonNode>(svc.toolChoiceSchema(AiDataToolService.TOOLS)).path("anyOf")
        assertEquals(AiDataToolService.TOOLS.size + 1, branches.size())
        val defectTop = branches.single { it.path("properties").path("name").path("enum").path(0).asText() == "defect_top" }
        assertEquals(listOf("from", "to", "limit"), defectTop.path("properties").path("arguments").path("required").map { it.asText() })
        assertTrue(branches.any { it.path("properties").path("name").path("enum").path(0).asText() == LlmChatProxyService.NO_TOOL })
    }
}
