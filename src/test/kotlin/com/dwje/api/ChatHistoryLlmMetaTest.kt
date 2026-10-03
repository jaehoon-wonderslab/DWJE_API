package com.dwje.api

import com.dwje.api.common.exception.BusinessException
import com.dwje.api.common.exception.MenuAccessDeniedException
import com.dwje.api.common.security.UserContext
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.common.util.MenuId
import com.dwje.api.config.AppProperties
import com.dwje.api.config.LlmProxyProperties
import com.dwje.api.repository.AiChatRepository
import com.dwje.api.service.AiAdminService
import com.dwje.api.service.AuditLogService
import com.dwje.api.service.DownloadLogService
import com.dwje.api.service.LlmChatProxyService
import com.dwje.api.service.SllmClient
import com.dwje.api.service.SystemDeptGuard
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.annotation.Transactional
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 질의 이력 LLM 메타(V71) · 근거 문서 요약 · 전사 질의 이력 관리자 전용(2026-10-03).
 * 실제 로컬 DB, 롤백. V71 컬럼이 없으면 저장 단정은 「건너뛰고 500 이 아님」 만 본다. 감사·다운로드 이력은 목.
 */
@SpringBootTest(properties = ["app.ai.prewarm-enabled=false"])
@ActiveProfiles("local")
@Transactional
class ChatHistoryLlmMetaTest {

    @Autowired lateinit var service: AiAdminService
    @Autowired lateinit var repo: AiChatRepository
    @Autowired lateinit var guard: SystemDeptGuard
    @Autowired lateinit var jdbc: NamedParameterJdbcTemplate
    @MockitoBean lateinit var audit: AuditLogService
    @MockitoBean lateinit var downloadLog: DownloadLogService

    private val json = ObjectMapper()
    private val me = UserPrincipal("10003", "제조", 4, "제조팀", null, null, false, menuPerms = setOf(MenuId.CHAT_HISTORY))
    private val itTeam = UserPrincipal("10004", "전산", 5, "전산팀", null, null, false, menuPerms = setOf(MenuId.CHAT_HISTORY))
    private val admin = UserPrincipal("10000", "관리자", 1, "통합관리자", null, null, true)

    @AfterEach fun clear() = UserContext.clear()

    private fun chat(user: String, q: String): Long = jdbc.queryForObject(
        "INSERT INTO ax.tb_ai_chat_log (asked_at, user_id, dept_nm, question, intent_cd, intent_nm) VALUES (now(), :u, '시험부', :q, 'metric', '지표 조회') RETURNING chat_id",
        MapSqlParameterSource().addValue("u", user).addValue("q", q), Long::class.java)!!

    /** 질의에 검색 히트 [n] 건 — 실제 청크를 빌린다 */
    private fun hits(chatId: Long, n: Int) {
        val chunks = jdbc.queryForList("SELECT chunk_id, doc_id FROM vec.tb_doc_chunk ORDER BY chunk_id LIMIT :n", MapSqlParameterSource("n", n))
        val qid = repo.insertQueryLog(chatId, "10003", 4, "q", null, 8, n, n, n, 0, 1)
        repo.insertQueryHits(qid, chunks.mapIndexed { i, c -> mapOf("chunkId" to c["chunk_id"], "docId" to c["doc_id"], "score" to 1.0 - i * 0.1) })
    }

    @Test
    @DisplayName("스트리밍 조각에서 model · id · finish_reason · usage 를 모은다 — include_usage 끝 조각(choices 빈 배열)도, 본문은 그대로")
    fun streamTapMeta() {
        val svc = LlmChatProxyService(AppProperties(), json, AiChatRepository(NamedParameterJdbcTemplate(DriverManagerDataSource())),
            SllmClient(AppProperties(), json))
        val tap = svc.StreamTap()
        val sse = listOf(
            """{"id":"chatcmpl-abc","model":"dwje-ax","choices":[{"index":0,"delta":{"content":"안녕"},"finish_reason":null}]}""",
            """{"id":"chatcmpl-abc","model":"dwje-ax","choices":[{"index":0,"delta":{"content":"하세요"},"finish_reason":"stop"}]}""",
            """{"id":"chatcmpl-abc","model":"dwje-ax","choices":[],"usage":{"prompt_tokens":120,"completion_tokens":8,"total_tokens":128}}"""
        ).joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"
        val bytes = sse.toByteArray()
        tap.feed(bytes, bytes.size)
        assertEquals("안녕하세요", tap.text()); assertTrue(tap.done)
        assertEquals(AiChatRepository.LlmMeta("dwje-ax", "chatcmpl-abc", "stop", 120, 8, 128, 1500), tap.meta(1500))
        assertEquals(AiChatRepository.LlmMeta(elapsedMs = 9), svc.StreamTap().meta(9), "아무것도 못 받으면 시간만")
    }

    @Test
    @DisplayName("채팅 스트리밍 본문에 stream_options.include_usage=true")
    fun includeUsage() {
        val bodies = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex -> bodies += ex.requestBody.readAllBytes().toString(Charsets.UTF_8)
                val b = "data: [DONE]\n\n".toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
            start()
        }
        try {
            val app = AppProperties(llm = LlmProxyProperties(baseUrl = "http://127.0.0.1:${server.address.port}"))
            LlmChatProxyService(app, json, AiChatRepository(NamedParameterJdbcTemplate(DriverManagerDataSource())), SllmClient(app, json))
                .open(listOf(mapOf("role" to "user", "content" to "x"))).use { it.readAllBytes() }
            assertTrue(json.readTree(bodies.single()).path("stream_options").path("include_usage").asBoolean())
        } finally { server.stop(0) }
    }

    @Test
    @DisplayName("답 저장 — V71 이 있으면 메타 7개를 남기고, 없으면 건너뛴다(답·응답 시간은 그대로)")
    fun saveMeta() {
        val id = chat("10003", "LLM 메타 저장 시험")
        val meta = AiChatRepository.LlmMeta("dwje-ax", "chatcmpl-xyz", "length", 100, 3500, 3600, 4200)
        assertEquals(1, repo.updateLlmAnswer(id, "10003", "답", 4300, meta))
        assertEquals("답", jdbc.queryForObject("SELECT answer FROM ax.tb_ai_chat_log WHERE chat_id = :id", MapSqlParameterSource("id", id), String::class.java))
        val row = repo.findChatLog(id)!!
        assertEquals("지표 조회", row["intentNm"])
        if (repo.hasLlmMetaColumns()) {
            assertEquals("dwje-ax", row["llmModel"]); assertEquals("length", row["finishReason"])
            assertEquals(100, row["promptTokens"]); assertEquals(3500, row["completionTokens"]); assertEquals(3600, row["totalTokens"])
            assertEquals(4200, row["llmMs"]); assertEquals("chatcmpl-xyz", row["llmRequestId"])
        } else {
            assertNull(row["llmModel"]); assertNull(row["totalTokens"])
        }
    }

    @Test
    @DisplayName("목록 행 — intentNm · LLM 메타 · docs(상위 3건 title/page/score) · docCnt, llmRequestId 는 scope=all 에서만")
    fun listFields() {
        val withDocs = chat("10003", "근거 있는 질의")
        hits(withDocs, 5)
        val noDocs = chat("10003", "근거 없는 질의")
        repo.updateLlmAnswer(withDocs, "10003", "답", 900, AiChatRepository.LlmMeta("dwje-ax", "chatcmpl-1", "stop", 10, 5, 15, 800))

        UserContext.set(me)
        val mine = service.getChatHistory(null, null, null, 1, 50).rows
        val row = mine.single { it["messageId"] == withDocs }
        @Suppress("UNCHECKED_CAST")
        val docs = row["docs"] as List<Map<String, Any?>>
        assertEquals(3, docs.size); assertEquals(5, row["docCnt"])
        assertEquals(setOf("title", "page", "score"), docs.first().keys)
        assertTrue((docs[0]["score"] as Double) >= (docs[1]["score"] as Double))
        assertEquals("지표 조회", row["intentNm"])
        assertFalse(row.containsKey("llmRequestId"), "본인 화면에는 LLM 응답 id 없음")
        listOf("llmModel", "finishReason", "promptTokens", "completionTokens", "totalTokens", "llmMs").forEach { assertTrue(row.containsKey(it), it) }
        if (repo.hasLlmMetaColumns()) { assertEquals("dwje-ax", row["llmModel"]); assertEquals(15, row["totalTokens"]) }
        val empty = mine.single { it["messageId"] == noDocs }
        assertEquals(emptyList<Any>(), empty["docs"]); assertEquals(0, empty["docCnt"])

        UserContext.set(admin)
        val all = service.getChatHistory(null, null, null, 1, 50, scope = "all").rows.single { it["messageId"] == withDocs }
        assertTrue(all.containsKey("llmRequestId"))
        if (repo.hasLlmMetaColumns()) assertEquals("chatcmpl-1", all["llmRequestId"])
        val detail = service.getChatDetail(withDocs, "all")
        assertEquals(5, detail["docCnt"]); assertEquals("지표 조회", detail["intentNm"]); assertTrue(detail.containsKey("llmRequestId"))
        UserContext.set(me)
        assertFalse(service.getChatDetail(withDocs).containsKey("llmRequestId"))
    }

    @Test
    @DisplayName("전사 질의 이력은 관리자 전용 — 관리 화면 목록에 들고, 전산팀은 scope=all 403, 통합관리자만 부여·회수")
    fun adminOnly() {
        assertTrue(MenuId.SYS_CHAT_HISTORY in MenuId.ADMIN_SCREENS)
        UserContext.set(itTeam)
        assertThrows(MenuAccessDeniedException::class.java) { service.getChatHistory(null, null, null, 1, 10, scope = "all") }
        assertEquals("E-AUTH-002", assertThrows(BusinessException::class.java) { guard.assertCanGrantAdminScreen(itTeam, listOf(MenuId.SYS_CHAT_HISTORY)) }.errorCode.code)
        guard.assertCanGrantAdminScreen(admin, listOf(MenuId.SYS_CHAT_HISTORY))
    }
}
