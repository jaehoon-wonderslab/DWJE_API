package com.dwje.api

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.exception.MenuAccessDeniedException
import com.dwje.api.common.exception.ResourceNotFoundException
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
 * 08(보존 기간 파기)·10(조회 조건)·11(응답 시간 누적) + V70(scope mine/all · 학습 답변 · 답변 시각). 실제 로컬 DB, 롤백.
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

    /** 제조팀 — 자연어 질의 이력(본인 질의)만, 데이터 권한 없음 */
    private val viewer = UserPrincipal("10003", "제조", 4, "제조팀", null, null, false, menuPerms = setOf(MenuId.CHAT_HISTORY))
    /** 전산팀 — 전사 자연어 질의 이력(sys-chat-history) 접근 = 관리 기능 */
    private val manager = UserPrincipal("10004", "전산", 5, "전산팀", null, null, false,
        menuPerms = setOf(MenuId.CHAT_HISTORY, MenuId.SYS_CHAT_HISTORY))
    private val admin = UserPrincipal("10000", "관리자", 1, "통합관리자", null, null, true)

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
    @DisplayName("CHH-10 조건 — 검색어(대소문자 무시)·응답 여부·평가 NONE·검토, 값 오류 400, 92일 초과 400, 사번은 scope=all 에서만")
    fun filters() {
        val a = chat("10000", "ZT Alpha 불량", "답")
        val b = chat("10000", "zt alpha 수율", null)
        UserContext.set(manager)
        val all = "all"
        val kw = service.getChatHistory(null, null, null, 1, 100, AiAdminService.HistoryCond(keyword = "zt ALPHA"), scope = all)
        assertEquals(setOf(a, b), kw.rows.map { it["messageId"] }.toSet())
        val unanswered = service.getChatHistory(null, null, null, 1, 100, AiAdminService.HistoryCond(keyword = "zt alpha", answered = "N"), scope = all)
        assertEquals(listOf(b), unanswered.rows.map { it["messageId"] })
        assertEquals(2, service.getChatHistory(null, null, null, 1, 100, AiAdminService.HistoryCond(keyword = "zt alpha", rating = "none"), scope = all).rows.size)
        repo.updateReview(a, "BAD", null, "10004")
        assertEquals(listOf(a), service.getChatHistory(null, null, null, 1, 100, AiAdminService.HistoryCond(keyword = "zt alpha", review = "BAD"), scope = all).rows.map { it["messageId"] })

        assertEquals("rating", assertThrows(InvalidParameterException::class.java) {
            service.getChatHistory(null, null, null, 1, 10, AiAdminService.HistoryCond(rating = "GOOD"))
        }.field)
        assertThrows(InvalidParameterException::class.java) { service.getChatHistory("2026-01-01", "2026-09-01", null, 1, 10) }
        assertThrows(InvalidParameterException::class.java) { service.getChatHistoryGroups("2026-01-01", "2026-09-01") }

        // scope=all 은 사번 조건을 쓴다
        assertEquals(0, service.getChatHistory(null, null, null, 1, 100, AiAdminService.HistoryCond(keyword = "zt alpha", empNo = "99999"), scope = all).rows.size)
        // 세션 목록도 같은 조건(그런 질의가 있는 세션)
        val (sessions, _) = service.getChatSessions(null, null, null, null, "zt alpha", 1, 100, cond = AiAdminService.HistoryCond(answered = "N"), scope = all)
        assertEquals(listOf("chat-$b"), sessions.map { it["sessionKey"] })
        // mine 은 사번 조건을 무시하고 본인 질의만 — 제조팀 본인 질의
        UserContext.set(viewer)
        val mine = chat("10003", "zt alpha 본인", "답")
        assertEquals(listOf(mine), service.getChatHistory(null, null, null, 1, 100, AiAdminService.HistoryCond(keyword = "zt alpha", empNo = "10000")).rows.map { it["messageId"] })
        UserContext.set(manager)
        // 요약 — 보존 일수·목표 답변율
        val summary = service.getChatHistorySummary(null, null, null, scope = all)
        assertEquals(1095, summary["retentionDays"], "R-20 보존 3년")
        assertTrue("expiredCnt" in summary)
        assertTrue("targetAnswerRate" in summary)
    }

    @Test
    @DisplayName("CHH-05 상세 — hits·askedAt·userName(가리지 않음), mine 은 남의 질의 404 · debug 없음, all + 관리 기능이면 debug")
    fun detail() {
        val id = chat("10000", "ZT 상세 질문", "답")
        val own = chat("10003", "ZT 본인 상세", "답")
        assertThrows(ResourceNotFoundException::class.java) { service.getChatDetail(id) }
        val d = service.getChatDetail(own)
        assertTrue(d["hits"] is List<*>)
        assertEquals(d["ts"], d["askedAt"])
        assertEquals(d["name"], d["userName"])
        assertEquals("10003", d["empNo"])
        assertFalse("debug" in d)
        UserContext.set(manager)
        val all = service.getChatDetail(id, "all")
        assertTrue("debug" in all)
        assertEquals("10000", all["empNo"]); assertFalse((all["name"] as String).endsWith("**"))
    }

    @Test
    @DisplayName("V70 scope — mine 은 통합관리자도 본인 질의만, all 은 sys-chat-history 가 없으면 403, 행에 answeredAt(질의 시각 + 응답 시간)")
    fun scopes() {
        val other = chat("10003", "ZT 범위 남의 질의", "답")
        val mineId = chat("10000", "ZT 범위 본인 질의", "답")
        jdbc.update("UPDATE ax.tb_ai_chat_log SET asked_at = timestamp '2026-10-03 09:00:00', response_ms = 2500 WHERE chat_id = $mineId",
            MapSqlParameterSource())
        UserContext.set(admin)
        val mine = service.getChatHistory("2026-10-03", "2026-10-03", null, 1, 500, AiAdminService.HistoryCond(keyword = "ZT 범위")).rows
        assertEquals(listOf(mineId), mine.map { it["messageId"] }, "통합관리자도 mine 이면 본인 질의만")
        assertEquals("2026-10-03 09:00:02", mine.single()["answeredAt"])
        assertEquals(false, service.getChatHistorySummary(null, null, null)["canManage"])
        assertThrows(ResourceNotFoundException::class.java) { service.getChatDetail(other) }
        assertTrue(service.getChatHistory(null, null, null, 1, 500, AiAdminService.HistoryCond(keyword = "ZT 범위"), scope = "all")
            .rows.any { it["messageId"] == other })

        UserContext.set(viewer)
        assertThrows(MenuAccessDeniedException::class.java) { service.getChatHistory(null, null, null, 1, 10, scope = "all") }
        assertThrows(MenuAccessDeniedException::class.java) { service.getChatSessions(null, null, null, null, null, 1, 10, scope = "all") }
        assertThrows(MenuAccessDeniedException::class.java) { service.getChatHistorySummary(null, null, null, scope = "all") }
        assertThrows(MenuAccessDeniedException::class.java) { service.getChatHistoryGroups(null, null, "all") }
        assertThrows(MenuAccessDeniedException::class.java) { listExport.chatHistory(ListExportRequest(), "all") }
        assertTrue(service.getChatHistory(null, null, null, 1, 500).rows.all { it["empNo"] == "10003" })
        assertThrows(MenuAccessDeniedException::class.java) { service.saveTrainAnswer(other, "답") }
    }

    @Test
    @DisplayName("V70 학습 답변 — 저장하면 목록·상세에 보이고 학습셋에 응답 대신 들어간다(평가 없어도), 비우면 지워진다")
    fun trainAnswerFlow() {
        val id = chat("10003", "ZT 학습 질문", "원래 답")
        UserContext.set(manager)
        val saved = service.saveTrainAnswer(id, "  올바른 답  ")
        assertEquals("올바른 답", saved["trainAnswer"]); assertEquals("10004", saved["trainAnswerBy"])
        assertTrue(saved["trainAnswerAt"] is String); assertTrue(saved["trainAnswerByNm"] != null)

        val row = service.getChatHistory(null, null, null, 1, 100, AiAdminService.HistoryCond(keyword = "ZT 학습"), scope = "all").rows.single()
        assertEquals("올바른 답", row["trainAnswer"]); assertEquals("10004", row["trainAnswerBy"]); assertEquals(saved["trainAnswerByNm"], row["trainAnswerByNm"])
        assertEquals("올바른 답", service.getChatDetail(id, "all")["trainAnswer"])

        // 평가가 없어도 학습 답변이 있으면 넣는다 — 전산팀은 데이터 권한이 없어 남의 질의 응답이 가려지므로 통합관리자로 확인
        UserContext.set(admin)
        val line = service.getTrainsetLines(null, null, "USEFUL").lines.single { it.contains("ZT 학습 질문") }
        assertTrue(line.contains("올바른 답") && !line.contains("원래 답"), line)
        assertTrue(line.contains("\"source\":\"TRAIN_ANSWER\""), line)

        val cleared = service.saveTrainAnswer(id, "")
        assertEquals(null, cleared["trainAnswer"]); assertEquals(null, cleared["trainAnswerBy"]); assertEquals(null, cleared["trainAnswerAt"])
        assertEquals(0L, long("SELECT count(*) FROM ax.tb_ai_chat_log WHERE chat_id = $id AND (train_answer IS NOT NULL OR train_answer_by IS NOT NULL)"))
    }

    @Test
    @DisplayName("CHH-07·19 전체 내려받기 — 기간과 무관, 가린 응답은 「비공개」 이고 건수 = 다운로드 이력 blindCnt, 감사는 한 번")
    fun export() {
        chat("10000", "ZT 오래된 질문", "오래된 답", daysAgo = 200)
        UserContext.set(manager)
        val file = listExport.chatHistory(ListExportRequest(view = "MESSAGE", from = "2026-09-30", to = "2026-09-30"), "all")
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
            listExport.chatHistory(ListExportRequest(scopeCd = "VIEW"), "all")
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
