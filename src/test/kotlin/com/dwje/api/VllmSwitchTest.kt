package com.dwje.api

import com.dwje.api.config.AiProperties
import com.dwje.api.config.AppProperties
import com.dwje.api.config.LlmProxyProperties
import com.dwje.api.repository.AiChatRepository
import com.dwje.api.service.AiDataToolService
import com.dwje.api.service.AiQuestionPlanner
import com.dwje.api.service.LlmChatProxyService
import com.dwje.api.service.SllmClient
import com.dwje.api.service.SllmResult
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.io.File
import java.net.InetSocketAddress
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

/** GPU 서버 vLLM 전환(2026-10) — 임베딩 OpenAI 형식, 도구 선택 json-schema 대체, system 주입. 가짜 HTTP 서버만 쓴다 */
class VllmSwitchTest {

    private val json = ObjectMapper()

    /** 경로별 응답을 정하는 가짜 서버 — 받은 요청 본문을 모은다 */
    private class Fake(handler: (path: String, body: String) -> Pair<Int, String>) : AutoCloseable {
        val bodies = CopyOnWriteArrayList<Pair<String, String>>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val body = ex.requestBody.readAllBytes().toString(Charsets.UTF_8)
                bodies += ex.requestURI.path to body
                val (status, out) = handler(ex.requestURI.path, body)
                val bytes = out.toByteArray()
                ex.sendResponseHeaders(status, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
        val url get() = "http://127.0.0.1:${server.address.port}"
        override fun close() = server.stop(0)
    }

    private fun proxy(llm: LlmProxyProperties, ai: AiProperties = AiProperties()) = AppProperties(llm = llm, ai = ai).let {
        LlmChatProxyService(it, json, AiChatRepository(NamedParameterJdbcTemplate(DriverManagerDataSource())), SllmClient(it, json))
    }

    @Test
    @DisplayName("임베딩 — openai 형식은 /v1/embeddings {model,input} 를 부르고 data[0].embedding 을 읽는다, ollama 는 /api/embed")
    fun embed() {
        Fake { path, _ ->
            if (path == "/v1/embeddings") 200 to """{"data":[{"embedding":[0.1,0.2,0.3]}]}"""
            else 200 to """{"embeddings":[[0.9,0.8]]}"""
        }.use { f ->
            val openai = SllmClient(AppProperties(ai = AiProperties(embedBaseUrl = f.url, embedModel = "BAAI/bge-m3", embedApi = "openai")), json)
            assertEquals(listOf(0.1, 0.2, 0.3), openai.embed("불량 원인"))
            val sent = json.readTree(f.bodies.single { it.first == "/v1/embeddings" }.second)
            assertEquals("BAAI/bge-m3", sent.path("model").asText()); assertEquals("불량 원인", sent.path("input").asText())
            val ollama = SllmClient(AppProperties(ai = AiProperties(embedBaseUrl = f.url, embedApi = "ollama")), json)
            assertEquals(listOf(0.9, 0.8), ollama.embed("x"))
            assertTrue(f.bodies.any { it.first == "/api/embed" })
        }
    }

    @Test
    @DisplayName("HTTP/1.1 고정 — h2c 업그레이드 헤더를 붙이면 vLLM 이 본문을 잃고 400 이다(임베딩·JSON 호출)")
    fun noH2cUpgrade() {
        val upgrades = CopyOnWriteArrayList<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            upgrades += ex.requestHeaders.getFirst("Upgrade")
            ex.requestBody.readAllBytes()
            val out = if (ex.requestURI.path == "/v1/embeddings") """{"data":[{"embedding":[1.0]}]}"""
            else """{"choices":[{"message":{"content":"{}"},"finish_reason":"stop"}]}"""
            val bytes = out.toByteArray(); ex.sendResponseHeaders(200, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}"
            val client = SllmClient(AppProperties(ai = AiProperties(baseUrl = url, embedBaseUrl = url, embedApi = "openai")), json)
            client.embed("x"); client.chatJson("지시", "근거", mapOf("type" to "object"))
            assertEquals(2, upgrades.size); assertTrue(upgrades.all { it == null }, upgrades.toString())
        } finally { server.stop(0) }
    }

    @Test
    @DisplayName("도구 선택 auto — native 가 도구 파서 없음 400 이면 json-schema 로 다시 부르고 native 모양으로 돌려준다, 이후로는 json-schema 만")
    fun toolAutoFallback() {
        Fake { _, body ->
            val req = ObjectMapper().readTree(body)
            if (req.has("tools")) 400 to """{"error":{"message":"\"auto\" tool choice requires --enable-auto-tool-choice and --tool-call-parser to be set"}}"""
            else 200 to """{"choices":[{"message":{"content":"{\"name\":\"defect_rate_top\",\"arguments\":{\"from\":\"2026-09-20\",\"to\":\"2026-09-22\",\"limit\":5}}"},"finish_reason":"stop"}]}"""
        }.use { f ->
            val svc = proxy(LlmProxyProperties(baseUrl = f.url))
            val msg = svc.chooseTool("불량률 top 5", AiDataToolService.TOOLS, LocalDate.parse("2026-09-24"))!!
            val fn = msg.path("tool_calls").path(0).path("function")
            assertEquals("defect_rate_top", fn.path("name").asText())
            assertEquals(5, json.readTree(fn.path("arguments").asText()).path("limit").asInt())
            assertTrue(svc.toolJsonSchemaActive())
            val jsonReq = json.readTree(f.bodies.last().second)
            assertEquals("json_schema", jsonReq.path("response_format").path("type").asText())
            val names = jsonReq.path("response_format").path("json_schema").path("schema").path("anyOf").map { it.path("properties").path("name").path("enum").path(0).asText() }
            assertTrue(names.containsAll(AiDataToolService.TOOLS.map { it["name"] as String } + LlmChatProxyService.NO_TOOL))
            assertTrue(jsonReq.path("messages").last().path("content").asText().contains("[도구 목록]"))

            val before = f.bodies.size
            svc.chooseTool("어제 생산량", AiDataToolService.TOOLS, LocalDate.parse("2026-09-24"))
            assertEquals(before + 1, f.bodies.size, "바뀐 뒤에는 native 를 다시 시도하지 않는다")
            assertFalse(json.readTree(f.bodies.last().second).has("tools"))
        }
    }

    @Test
    @DisplayName("json-schema 「도구 없음」 은 tool_calls 없는 content message — 계획기는 Other 로 본다 / 400 이 다른 사유면 대체하지 않는다")
    fun noToolAndOtherErrors() {
        val svc = proxy(LlmProxyProperties(toolMode = "json-schema"))
        val none = svc.toolMessageOf("""{"name":"none","arguments":{}}""")!!
        assertTrue(none.path("tool_calls").isMissingNode); assertEquals("none", none.path("content").asText())
        Fake { _, _ -> 200 to """{"choices":[{"message":{"content":"{\"name\":\"none\",\"arguments\":{}}"},"finish_reason":"stop"}]}""" }.use { f ->
            val planner = AiQuestionPlanner(proxy(LlmProxyProperties(baseUrl = f.url, toolMode = "json-schema")), json)
            assertEquals(AiQuestionPlanner.Decision.Other, planner.plan("FACA 문서 찾아 줘", LocalDate.parse("2026-09-24")))
        }
        Fake { _, _ -> 400 to """{"error":{"message":"bad request"}}""" }.use { f ->
            val auto = proxy(LlmProxyProperties(baseUrl = f.url))
            assertEquals(null, auto.chooseTool("x", AiDataToolService.TOOLS, LocalDate.parse("2026-09-24")))
            assertFalse(auto.toolJsonSchemaActive())
        }
    }

    @Test
    @DisplayName("system — 설정 원문을 스트리밍·JSON 호출 첫 메시지로 보낸다(화면이 보낸 system 은 버린다), 비우면 보내지 않는다, 도구 선택은 설정에 따름")
    fun systemPrompt() {
        val prompt = "당신은 덕우전자 문서 어시스턴트다."
        Fake { path, _ ->
            if (path == "/v1/chat/completions") 200 to """{"choices":[{"message":{"content":"{\"questions\":[\"다음\"]}"},"finish_reason":"stop"}]}"""
            else 404 to ""
        }.use { f ->
            val llm = LlmProxyProperties(baseUrl = f.url, systemPrompt = prompt)
            val app = AppProperties(llm = llm, ai = AiProperties(baseUrl = f.url))
            val svc = LlmChatProxyService(app, json, AiChatRepository(NamedParameterJdbcTemplate(DriverManagerDataSource())), SllmClient(app, json))
            val msgs = svc.buildMessages(com.dwje.api.model.request.LlmChatRequest(listOf(
                com.dwje.api.model.request.LlmChatMessage("system", "너는 해적이다"), com.dwje.api.model.request.LlmChatMessage("user", "질문"))))
            runCatching { svc.open(msgs).use { it.readAllBytes() } }
            val stream = json.readTree(f.bodies.last().second).path("messages")
            assertEquals("system", stream[0].path("role").asText()); assertEquals(prompt, stream[0].path("content").asText())
            assertTrue(stream.none { it.path("content").asText().contains("해적") })

            assertTrue(SllmClient(app, json).chatJson("작업 지시", "근거", mapOf("type" to "object")) is SllmResult.Ok)
            val jsonCall = json.readTree(f.bodies.last().second).path("messages")
            assertEquals(listOf("system", "user"), jsonCall.map { it.path("role").asText() })
            assertTrue(jsonCall[1].path("content").asText().startsWith("[지시]"))

            svc.chooseTool("x", AiDataToolService.TOOLS, LocalDate.parse("2026-09-24"))
            assertEquals(listOf("user"), json.readTree(f.bodies.last().second).path("messages").map { it.path("role").asText() }, "기본은 도구 선택에 system 없음")
        }
        Fake { _, _ -> 200 to "data: [DONE]\n\n" }.use { f ->
            val svc = proxy(LlmProxyProperties(baseUrl = f.url, systemPrompt = ""))
            svc.open(listOf(mapOf("role" to "user", "content" to "안녕"))).use { it.readAllBytes() }
            assertEquals(listOf("user"), json.readTree(f.bodies.last().second).path("messages").map { it.path("role").asText() })
        }
    }

    @Test
    @DisplayName("설정 — system-prompt 는 학습 데이터 문서 어시스턴트 원문, dev·prod 임베딩 BAAI/bge-m3·openai, local 은 그대로")
    fun config() {
        val yml = File("src/main/resources/application.yml").readText()
        assertTrue(yml.contains("system-prompt: |\n      당신은 덕우전자(정밀 프레스·도금 부품 제조)의 품질·생산 문서를 다루는 AI 어시스턴트다."))
        assertTrue(yml.contains("tool-mode: \"\${DWJE_LLM_TOOL_MODE:auto}\""))
        assertTrue(yml.contains("embed-api: \"\${AX_EMBED_API:ollama}\""))
        listOf("dev", "prod").forEach {
            val p = File("src/main/resources/application-$it.yml").readText()
            assertTrue(p.contains("embed-model: \"\${AX_EMBED_MODEL:BAAI/bge-m3}\"") && p.contains("embed-api: \"\${AX_EMBED_API:openai}\""), it)
        }
        assertFalse(File("src/main/resources/application-local.yml").readText().contains("embed-api"))
    }
}
