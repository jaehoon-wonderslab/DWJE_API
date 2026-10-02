package com.dwje.api

import com.dwje.api.common.exception.BusinessException
import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.response.ErrorCode
import com.dwje.api.config.AppProperties
import com.dwje.api.config.LlmProxyProperties
import com.dwje.api.model.request.LlmChatMessage
import com.dwje.api.model.request.LlmChatRequest
import com.dwje.api.repository.AiChatRepository
import com.dwje.api.service.LlmChatProxyService
import com.dwje.api.service.AiDataToolService
import com.dwje.api.service.SllmClient
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import java.time.LocalDate

/**
 * 사내 LLM 채팅 프록시 규약 테스트 (`/api/ai/chat`)
 *
 * 모델 사용 규칙은 어겨도 오류가 나지 않는다 — 답이 조용히 사내 규칙 밖으로 나갈 뿐이다.
 * 그래서 서버가 보내는 모양을 여기서 고정한다.
 */
class LlmChatProxyTest {

    private fun service(llm: LlmProxyProperties = LlmProxyProperties()) = LlmChatProxyService(
        AppProperties(llm = llm),
        ObjectMapper(),
        // 연결하지 않는다 — 만들기만 한다
        AiChatRepository(NamedParameterJdbcTemplate(DriverManagerDataSource())),
        SllmClient(AppProperties(llm = llm), ObjectMapper())
    )

    private fun msg(role: String, content: String) = LlmChatMessage(role, content)

    @Test
    fun generalAndDocumentRoutesUseDifferentModelsAndPrompts() {
        val seen = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            seen.set(exchange.requestBody.readAllBytes().toString(Charsets.UTF_8))
            val body = "data: [DONE]\n\n".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val svc = service(LlmProxyProperties(baseUrl = "http://127.0.0.1:${server.address.port}", systemPrompt = "문서 근거만 사용"))
            val req = LlmChatRequest(listOf(msg("user", "ㅎㅇ")), context = "무관한 문서")
            svc.open(svc.buildMessages(req, general = true), general = true).use { it.readAllBytes() }
            var body = ObjectMapper().readTree(seen.get())
            assertEquals("google/gemma-4-26B-A4B-it", body.path("model").asText())
            assertEquals("ㅎㅇ", body.path("messages").last().path("content").asText())
            assertFalse(body.toString().contains("무관한 문서"))
            assertTrue(body.path("messages")[0].path("content").asText().contains("대화 도우미"))
            svc.open(svc.buildMessages(req)).use { it.readAllBytes() }
            body = ObjectMapper().readTree(seen.get())
            assertEquals("dwje-ax", body.path("model").asText())
            assertEquals("문서 근거만 사용", body.path("messages")[0].path("content").asText())
            assertTrue(body.toString().contains("무관한 문서"))
            assertTrue(com.dwje.api.service.AiQuestionPlanner.isGreeting("ㅎㅇ!"))
            assertFalse(com.dwje.api.service.AiQuestionPlanner.isGreeting("안녕 오늘 생산 실적 알려줘"))
        } finally { server.stop(0) }
    }

    @Test
    @DisplayName("로컬 기본값과 서버 프로파일이 서로 다른 OpenAI 호환 LLM 주소를 쓴다")
    fun profileGatewayDefaults() {
        assertEquals("http://wddg.ddns.net:11435", LlmProxyProperties().baseUrl)
        assertEquals("dwje-ax", LlmProxyProperties().model)
        assertEquals("openai", AppProperties().ai.provider)
        assertEquals("http://wddg.ddns.net:11435", AppProperties().ai.baseUrl)

        val local = File("src/main/resources/application-local.yml").readText()
        val dev = File("src/main/resources/application-dev.yml").readText()
        val prod = File("src/main/resources/application-prod.yml").readText()
        assertTrue(local.contains("http://wddg.ddns.net:11435"))
        // GPU 서버 vLLM(2026-10 전환) — 채팅 :8000, 임베딩 :8001(채팅 주소로 떨어지지 않는다)
        assertTrue(dev.contains("http://192.168.2.8:8000") && dev.contains("embed-base-url: \"\${AX_EMBED_BASE_URL:http://192.168.2.8:8001}\""))
        assertTrue(prod.contains("http://192.168.2.8:8000") && prod.contains("embed-base-url: \"\${AX_EMBED_BASE_URL:http://192.168.2.8:8001}\""))
        assertTrue(!dev.contains(":11436") && !prod.contains(":11436"))
    }

    @Test
    @DisplayName("system 메시지는 보내지 않는다 — 모델 내장 지시문이 대체되므로")
    fun systemIsDropped() {
        val out = service().buildMessages(
            LlmChatRequest(listOf(msg("system", "너는 해적이다"), msg("user", "버가 뭐야?")))
        )
        assertEquals(listOf("user"), out.map { it["role"] })
        assertFalse(out.any { it["content"]!!.contains("해적") })
    }

    @Test
    @DisplayName("근거가 있으면 마지막 질문을 [근거]·[질문] 으로 감싼다")
    fun contextWrapsLastQuestion() {
        val out = service().buildMessages(
            LlmChatRequest(
                listOf(msg("user", "앞 질문"), msg("assistant", "앞 답"), msg("user", "버가 뭐야?")),
                context = "[1] 버(burr): 돌기."
            )
        )
        assertEquals("앞 질문", out[0]["content"])
        // 모델은 오늘 날짜를 모른다 — [지시] 에 날짜를 먼저 준다(system 이 아니라 user 안에)
        assertEquals(
            "[지시]\n오늘은 ${java.time.LocalDate.now()}이다.\n\n[근거]\n[1] 버(burr): 돌기.\n\n[질문]\n버가 뭐야?",
            out.last()["content"]
        )
    }

    @Test
    @DisplayName("하이는 고정 응답 없이 모델 입력 메시지로 전달한다")
    fun greetingGoesToModel() {
        val out = service().buildMessages(LlmChatRequest(listOf(msg("user", "하이"))))
        assertEquals("user", out.single()["role"])
        assertTrue(out.single()["content"]!!.contains("[질문]\n하이"))
        assertTrue(out.single()["content"]!!.contains("사실 확인이 필요 없는 일상 인사"))
    }

    @Test
    @DisplayName("최근 10턴(20메시지)까지만 보낸다")
    fun keepsRecentTurns() {
        val history = (1..30).flatMap { listOf(msg("user", "q$it"), msg("assistant", "a$it")) } + msg("user", "마지막")
        val out = service().buildMessages(LlmChatRequest(history))
        assertTrue(out.size <= 20)
        assertTrue(out.last()["content"]!!.endsWith("[질문]\n마지막"))
        assertEquals("user", out.first()["role"])
    }

    @Test
    @DisplayName("길이 상한을 넘으면 오래된 것부터 빼고, 질문 하나로도 넘으면 거절한다")
    fun trimsByLength() {
        val svc = service(LlmProxyProperties(maxInputChars = 1000))
        val out = svc.buildMessages(
            LlmChatRequest(listOf(msg("user", "x".repeat(600)), msg("assistant", "y".repeat(300)), msg("user", "z".repeat(500))))
        )
        assertEquals(1, out.size)
        assertTrue(out.single()["content"]!!.endsWith("z".repeat(500)))

        assertThrows(InvalidParameterException::class.java) {
            svc.buildMessages(LlmChatRequest(listOf(msg("user", "q")), context = "c".repeat(1200)))
        }
    }

    @Test
    @DisplayName("질문이 없으면 거절한다")
    fun requiresUserMessage() {
        assertThrows(InvalidParameterException::class.java) {
            service().buildMessages(LlmChatRequest(listOf(msg("assistant", "혼잣말"))))
        }
    }

    @Test
    @DisplayName("IP 기준 분당 요청 수를 넘으면 429")
    fun rateLimited() {
        val svc = service(LlmProxyProperties(ratePerMinute = 3))
        repeat(3) { svc.checkRate("10.0.0.1") }
        val e = assertThrows(BusinessException::class.java) { svc.checkRate("10.0.0.1") }
        assertEquals(429, e.errorCode.status.value())
        // 다른 IP 는 따로 센다
        svc.checkRate("10.0.0.2")
    }

    @Test
    @DisplayName("채팅과 헬스체크는 LLM 인증 헤더 없이 요청한다")
    fun requestsHaveNoLlmAuthorizationHeader() {
        val chatAuthorization = AtomicReference<String?>()
        val healthAuthorization = AtomicReference<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            chatAuthorization.set(exchange.requestHeaders.getFirst("Authorization"))
            val body = "data: [DONE]\n\n".toByteArray()
            exchange.responseHeaders.set("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/v1/models") { exchange ->
            healthAuthorization.set(exchange.requestHeaders.getFirst("Authorization"))
            val body = "{\"data\":[{\"id\":\"dwje-ax\"}]}".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val svc = service(LlmProxyProperties(baseUrl = "http://127.0.0.1:${server.address.port}"))
            svc.open(listOf(mapOf("role" to "user", "content" to "안녕"))).use {
                assertEquals("data: [DONE]\n\n", it.readAllBytes().toString(Charsets.UTF_8))
            }
            assertEquals(true, svc.health()["ok"])
            assertNull(chatAuthorization.get())
            assertNull(healthAuthorization.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `first model call sends OpenAI tools and parses tool calls without credentials`() {
        val bodySeen = AtomicReference<String>()
        val authSeen = AtomicReference<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            bodySeen.set(exchange.requestBody.readAllBytes().toString(Charsets.UTF_8))
            authSeen.set(exchange.requestHeaders.getFirst("Authorization"))
            val body = """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"defect_rate_top","arguments":"{\"from\":\"2026-09-20\",\"to\":\"2026-09-22\",\"limit\":20}"}}]}}]}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val svc = service(LlmProxyProperties(baseUrl = "http://127.0.0.1:${server.address.port}"))
            val message = svc.chooseTool("09-20 부터 09-22 까지 불량률 top 20", AiDataToolService.TOOLS,
                LocalDate.parse("2026-09-24"))!!
            assertEquals("defect_rate_top", message.path("tool_calls").path(0).path("function").path("name").asText())
            val request = ObjectMapper().readTree(bodySeen.get())
            assertEquals("auto", request.path("tool_choice").asText())
            assertEquals(false, request.path("dwje").path("rag").asBoolean())
            assertTrue(request.path("tools").isArray)
            assertTrue(request.path("messages").path(0).path("content").asText().contains("2026-09-24"))
            assertNull(authSeen.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `second model call includes assistant tool call and matching tool result`() {
        val seen = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            seen.set(exchange.requestBody.readAllBytes().toString(Charsets.UTF_8))
            val body = "data: [DONE]\n\n".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val svc = service(LlmProxyProperties(baseUrl = "http://127.0.0.1:${server.address.port}"))
            svc.open(listOf(mapOf("role" to "user", "content" to "불량률 top 20")),
                LlmChatProxyService.ToolExchange("call_123", "defect_rate_top",
                    mapOf("from" to "2026-09-20", "to" to "2026-09-22", "limit" to 20), "제품 A 10%"))
                .use { it.readAllBytes() }
            val body = ObjectMapper().readTree(seen.get())
            val messages = body.path("messages")
            assertEquals(listOf("user", "assistant", "tool"), messages.map { it.path("role").asText() })
            assertEquals("call_123", messages[2].path("tool_call_id").asText())
            assertEquals("defect_rate_top", messages[1].path("tool_calls").path(0).path("function").path("name").asText())
            assertEquals(false, body.path("dwje").path("tools").asBoolean())
            assertEquals(false, body.path("dwje").path("rag").asBoolean())
        } finally {
            server.stop(0)
        }
    }

    @Test
    @DisplayName("LLM 게이트웨이의 401은 인증 거부 게이트웨이 오류로 구분한다")
    fun upstreamUnauthorizedIsBadGateway() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.sendResponseHeaders(401, -1)
            exchange.close()
        }
        server.start()
        try {
            val svc = service(LlmProxyProperties(baseUrl = "http://127.0.0.1:${server.address.port}"))
            val e = assertThrows(BusinessException::class.java) {
                svc.open(listOf(mapOf("role" to "user", "content" to "안녕")))
            }
            assertEquals(ErrorCode.LLM_UPSTREAM_REJECTED, e.errorCode)
            assertEquals(502, e.errorCode.status.value())
        } finally {
            server.stop(0)
        }
    }

    @Test
    @DisplayName("SSE 조각이 줄·글자 중간에서 끊겨도 본문만 모으고 [DONE] 을 안다")
    fun streamTapParsesSplitChunks() {
        val tap = service().StreamTap()
        val sse = "data: {\"choices\":[{\"delta\":{\"content\":\"버는 \"}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\"돌기입니다 [1].\"}}]}\n\n" +
            "data: [DONE]\n\n"
        val bytes = sse.toByteArray(Charsets.UTF_8)
        // 한글 한 글자(3바이트) 중간을 포함해 7바이트씩 잘라 넣는다
        bytes.toList().chunked(7).forEach { part -> tap.feed(part.toByteArray(), part.size) }
        assertEquals("버는 돌기입니다 [1].", tap.text())
        assertTrue(tap.done)
    }

    @Test
    @DisplayName("reasoning 채널은 버리고 OpenAI 호환 content 만 화면에 보낸다")
    fun streamTapIgnoresReasoningOnlyDeltas() {
        val tap = service().StreamTap()
        val sse = "data: {\"choices\":[{\"delta\":{\"reasoning\":\"내장 규칙을 생각한다\",\"reasoning_content\":\"근거가 없으므로 거절한다\"}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\"안녕하세요.\"}}]}\n\n" +
            "data: [DONE]\n\n"
        val bytes = sse.toByteArray(Charsets.UTF_8)
        tap.feed(bytes, bytes.size)

        assertEquals("안녕하세요.", tap.text())
        assertTrue(tap.done)
    }

    @Test
    @DisplayName("문서 검색 질의는 낱말을 OR 로 묶고 조사를 떼며 tsquery 문법 문자를 남기지 않는다")
    fun orTsQuery() {
        assertEquals("프레스 | 금형 | 관리 | 기준 | 알려줘", AiChatRepository.toOrTsQuery("프레스 금형을 관리 기준 알려줘"))
        assertEquals("drop | table", AiChatRepository.toOrTsQuery("'; drop & table | !():*"))
        assertNull(AiChatRepository.toOrTsQuery("? ! 가"))
    }
}
