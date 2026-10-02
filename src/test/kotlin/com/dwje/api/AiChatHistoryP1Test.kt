package com.dwje.api

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.security.UserContext
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.common.util.MenuId
import com.dwje.api.model.request.ListExportRequest
import com.dwje.api.repository.AiChatRepository
import com.dwje.api.service.AiAdminService
import com.dwje.api.service.AiChatRetentionJob
import com.dwje.api.service.AuditLogService
import com.dwje.api.service.DownloadLogService
import com.dwje.api.service.ListExportService
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.annotation.Transactional

/**
 * 08 자연어 질의 이력 P1 — CHH-05(상세)·06(내려받기 감사 1회)·07/19(전체 = 조건 무시·가린 응답 「비공개」)·
 * 08(보존 기간 파기)·10(조회 조건)·11(응답 시간 누적). 실제 로컬 DB, 롤백.
 * 감사·다운로드 이력은 지울 수 없으므로(V51·V62) 목으로 바꿔 호출만 본다.
 */
@SpringBootTest(properties = ["app.ai.prewarm-enabled=false"])
@ActiveProfiles("local")
@Transactional
class AiChatHistoryP1Test {

    @Autowired lateinit var service: AiAdminService
    @Autowired lateinit var listExport: ListExportService
    @Autowired lateinit var retention: AiChatRetentionJob
    @Autowired lateinit var repo: AiChatRepository
    @Autowired lateinit var jdbc: NamedParameterJdbcTemplate
    @MockitoBean lateinit var audit: AuditLogService
    @MockitoBean lateinit var downloadLog: DownloadLogService

    /** 제조팀 — 질의 이력 조회만, 데이터 권한 없음 */
    private val viewer = UserPrincipal("10003", "제조", 4, "제조팀", null, null, false, menuPerms = setOf(MenuId.CHAT_HISTORY))
    /** 전산팀 — 질의 이력 쓰기 */
    private val manager = UserPrincipal("10004", "전산", 5, "전산팀", null, null, false,
        menuPerms = setOf(MenuId.CHAT_HISTORY), writePerms = setOf(MenuId.CHAT_HISTORY))

    @BeforeEach
    fun login() = UserContext.set(viewer)

    @AfterEach
    fun clear() = UserContext.clear()

    /** 감사 기록 호출 수 — 유형별 (Kotlin 비 null 인자에 Mockito eq() 를 쓸 수 없어 호출 목록을 센다) */
    private fun auditCalls(type: String) =
        org.mockito.Mockito.mockingDetails(audit).invocations.count { it.method.name == "record" && it.arguments[0] == type }

    private fun long(sql: String) = jdbc.queryForObject(sql, MapSqlParameterSource(), Long::class.java)!!

    /** 시험 질의 한 건 — [daysAgo] 일 전 */
    private fun chat(user: String, q: String, answer: String?, daysAgo: Int = 0, prev: Long? = null): Long =
        jdbc.queryForObject(
            """
            INSERT INTO ax.tb_ai_chat_log (asked_at, user_id, dept_nm, question, answer, prev_chat_id)
            VALUES (now() - make_interval(days => :d), :u, '시험부', :q, :a, :prev) RETURNING chat_id
            """.trimIndent(),
            MapSqlParameterSource().addValue("d", daysAgo).addValue("u", user).addValue("q", q).addValue("a", answer).addValue("prev", prev),
            Long::class.java
        )!!

    @Test
    @DisplayName("CHH-10 조건 — 검색어(대소문자 무시)·응답 여부·평가 NONE·검토, 값 오류 400, 92일 초과 400, 사번은 쓰기 권한자만")
    fun filters() {
        val a = chat("10000", "ZT Alpha 불량", "답")
        val b = chat("10000", "zt alpha 수율", null)
        val kw = service.getChatHistory(null, null, null, 1, 100, AiAdminService.HistoryCond(keyword = "zt ALPHA"))
        assertEquals(setOf(a, b), kw.rows.map { it["messageId"] }.toSet())
        val unanswered = service.getChatHistory(null, null, null, 1, 100, AiAdminService.HistoryCond(keyword = "zt alpha", answered = "N"))
        assertEquals(listOf(b), unanswered.rows.map { it["messageId"] })
        assertEquals(2, service.getChatHistory(null, null, null, 1, 100, AiAdminService.HistoryCond(keyword = "zt alpha", rating = "none")).rows.size)
        repo.updateReview(a, "BAD", null, "10004")
        assertEquals(listOf(a), service.getChatHistory(null, null, null, 1, 100, AiAdminService.HistoryCond(keyword = "zt alpha", review = "BAD")).rows.map { it["messageId"] })

        assertEquals("rating", assertThrows(InvalidParameterException::class.java) {
            service.getChatHistory(null, null, null, 1, 10, AiAdminService.HistoryCond(rating = "GOOD"))
        }.field)
        assertThrows(InvalidParameterException::class.java) { service.getChatHistory("2026-01-01", "2026-09-01", null, 1, 10) }
        assertThrows(InvalidParameterException::class.java) { service.getChatHistoryGroups("2026-01-01", "2026-09-01") }

        // 제조팀이 사번 조건을 주면 무시된다
        assertEquals(2, service.getChatHistory(null, null, null, 1, 100, AiAdminService.HistoryCond(keyword = "zt alpha", empNo = "99999")).rows.size)
        UserContext.set(manager)
        assertEquals(0, service.getChatHistory(null, null, null, 1, 100, AiAdminService.HistoryCond(keyword = "zt alpha", empNo = "99999")).rows.size)
        // 세션 목록도 같은 조건(그런 질의가 있는 세션)
        val (sessions, _) = service.getChatSessions(null, null, null, null, "zt alpha", 1, 100, cond = AiAdminService.HistoryCond(answered = "N"))
        assertEquals(listOf("chat-$b"), sessions.map { it["sessionKey"] })
        // 요약 — 보존 일수·목표 답변율
        val summary = service.getChatHistorySummary(null, null, null)
        assertEquals(1095, summary["retentionDays"], "R-20 보존 3년")
        assertTrue("expiredCnt" in summary)
        assertTrue("targetAnswerRate" in summary)
    }

    @Test
    @DisplayName("CHH-05 상세 — hits·askedAt·userName(가림 뒤 이름), 쓰기 권한이 없으면 debug 키 자체가 없다")
    fun detail() {
        val id = chat("10000", "ZT 상세 질문", "답")
        val d = service.getChatDetail(id)
        assertTrue(d["hits"] is List<*>)
        assertEquals(d["ts"], d["askedAt"])
        assertEquals(d["name"], d["userName"])
        assertTrue((d["userName"] as String).endsWith("**"))
        assertFalse("debug" in d)
        UserContext.set(manager)
        assertTrue("debug" in service.getChatDetail(id))
    }

    @Test
    @DisplayName("CHH-07·19 전체 내려받기 — 기간과 무관, 가린 응답은 「비공개」 이고 건수 = 다운로드 이력 blindCnt, 감사는 한 번")
    fun export() {
        chat("10000", "ZT 오래된 질문", "오래된 답", daysAgo = 200)
        val file = listExport.chatHistory(ListExportRequest(view = "MESSAGE", from = "2026-09-30", to = "2026-09-30"))
        XSSFWorkbook(file.body!!.inputStream).use { wb ->
            val sheet = wb.getSheetAt(0)
            val rows = (1..sheet.lastRowNum).map { sheet.getRow(it) }
            val old = rows.single { it.getCell(5).stringCellValue == "ZT 오래된 질문" }
            assertEquals("비공개", old.getCell(6).stringCellValue, "남의 응답 — 질의자 권한 기록이 없어 가림")
            assertEquals(long("SELECT count(*) FROM ax.tb_ai_chat_log").toInt(), rows.size, "기간 조건을 무시한 전체")
            val masked = rows.count { it.getCell(6).stringCellValue == "비공개" } * 2
            val call = org.mockito.Mockito.mockingDetails(downloadLog).invocations.single { it.method.name == "record" }
            assertEquals(masked, call.arguments[6], "blindCnt = 파일의 「비공개」 칸 수")
            assertEquals("ALL", call.arguments[11])
        }
        assertEquals(1, auditCalls("EXPORT"))
        assertEquals(0, auditCalls("RAW_VIEW"))
        assertEquals("scopeCd", assertThrows(InvalidParameterException::class.java) {
            listExport.chatHistory(ListExportRequest(scopeCd = "VIEW"))
        }.field)
    }

    @Test
    @DisplayName("CHH-08 파기 — 보존 일수보다 오래된 질의·재질의 연결·검색 기록을 지운다, 최근 질의는 남는다, 0 이면 아무것도 안 한다")
    fun retention() {
        val old1 = chat("10000", "ZT 파기1", "답", daysAgo = 3000)
        val old2 = chat("10000", "ZT 파기2", "답", daysAgo = 3000, prev = old1)
        val recent = chat("10000", "ZT 최근", "답", daysAgo = 0, prev = old2)
        jdbc.update("INSERT INTO vec.tb_query_log (chat_id, asked_at, user_id, query_text) VALUES ($old1, now() - interval '3000 days', '10000', 'ZT')",
            MapSqlParameterSource())
        assertTrue(retention.purge(0).isEmpty())
        assertEquals(0, auditCalls("AUTO_GEN"))

        val deleted = retention.purge(2900)
        assertTrue(deleted.getValue("chat") >= 2)
        assertEquals(0L, long("SELECT count(*) FROM ax.tb_ai_chat_log WHERE chat_id IN ($old1, $old2)"))
        assertEquals(1L, long("SELECT count(*) FROM ax.tb_ai_chat_log WHERE chat_id = $recent AND prev_chat_id IS NULL"))
        assertEquals(1, auditCalls("AUTO_GEN"))
    }

    @Test
    @DisplayName("CHH-11 응답 시간 — LLM 응답을 두 번 저장해도 ask_ms + LLM 시간(누적하지 않음)")
    fun responseMs() {
        val id = chat("10000", "ZT 응답 시간", null)
        jdbc.update("UPDATE ax.tb_ai_chat_log SET ask_ms = 1200, response_ms = 1200 WHERE chat_id = $id", MapSqlParameterSource())
        repo.updateLlmAnswer(id, "10000", "답", 3000)
        repo.updateLlmAnswer(id, "10000", "답", 3000)
        assertEquals(4200L, long("SELECT response_ms FROM ax.tb_ai_chat_log WHERE chat_id = $id"))
    }
}
