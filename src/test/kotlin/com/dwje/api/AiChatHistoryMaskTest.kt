package com.dwje.api

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.exception.MenuAccessDeniedException
import com.dwje.api.common.exception.ResourceNotFoundException
import com.dwje.api.common.exception.WriteAccessDeniedException
import com.dwje.api.common.security.UserContext
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.common.util.MenuId
import com.dwje.api.repository.AiChatRepository
import com.dwje.api.repository.AuditLogRepository
import com.dwje.api.repository.AuthRepository
import com.dwje.api.repository.VectorIndexRepository
import com.dwje.api.service.AiAdminService
import com.dwje.api.service.AiAskDebugRecorder
import com.dwje.api.service.AuditLogService
import com.dwje.api.service.AuthorizationService
import com.dwje.api.service.DataFieldService
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.stubbing.Answer
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.time.LocalDate

/**
 * 질의 이력 열람 범위·응답 가림·검토·학습 답변·학습데이터 — 08 기획서 CHH-01·02·03·04 + V70 범위 분리 (DB 없이)
 *
 * scope=mine(기본, chat-history)은 본인 질의만(통합관리자도), scope=all 은 sys-chat-history 접근이 필요하다.
 * 검토 · 학습 답변 · 학습데이터는 sys-chat-history 쓰기 동작이다.
 *
 * 질의 1: 통합관리자(10000) 질의, 가린 항목 없음 / 질의 2: 제조팀(10003) 본인 질의, mold 제외 4종 가림 / 질의 3: 기록 이전(NULL)
 */
class AiChatHistoryMaskTest {

    @AfterEach
    fun clear() = UserContext.clear()

    private fun row(id: Long, empNo: String, name: String, keys: Set<String>?) = mapOf<String, Any?>(
        "messageId" to id, "ts" to "2026-09-23 08:39:03", "empNo" to empNo, "name" to name, "dept" to "부서",
        "question" to "질문 $id", "answer" to "답 $id", "judgmentBasis" to "근거", "unansweredReason" to null,
        "rating" to "USEFUL", "blindFieldKeys" to keys, "sessionKey" to "s-$id"
    )

    private inner class MemRepo : AiChatRepository(mock(NamedParameterJdbcTemplate::class.java)) {
        val rows = listOf(row(1, "10000", "관리자", emptySet()), row(2, "10003", "제조", setOf("price", "plan", "customer", "yield")),
            row(3, "10001", "품질", null))
        var reviewed: Triple<Long, String, String?>? = null
        override fun findHistorySummary(from: LocalDate, to: LocalDate, filter: AiChatRepository.HistoryFilter) = emptyMap<String, Any?>()
        override fun countBefore(cut: java.time.LocalDateTime) = 0L
        /** 질의 ID → 학습 답변 (V70) */
        val trainAnswers = mutableMapOf<Long, String>()
        override fun updateTrainAnswer(chatId: Long, answer: String?, writerId: String): Map<String, Any?>? {
            if (rows.none { it["messageId"] == chatId }) return null
            if (answer == null) trainAnswers.remove(chatId) else trainAnswers[chatId] = answer
            return mapOf("trainAnswer" to answer, "trainAnswerAt" to answer?.let { "2026-10-03 10:00:00" },
                "trainAnswerBy" to answer?.let { writerId }, "trainAnswerByNm" to answer?.let { "작성자" })
        }
        override fun findHistory(from: LocalDate, to: LocalDate, filter: AiChatRepository.HistoryFilter, limit: Int, offset: Int) =
            rows.filter { filter.scopeUserId == null || it["empNo"] == filter.scopeUserId }
        override fun countHistory(from: LocalDate, to: LocalDate, filter: AiChatRepository.HistoryFilter) =
            findHistory(from, to, filter, 50, 0).size.toLong()
        override fun findChatLog(chatId: Long) = rows.firstOrNull { it["messageId"] == chatId }?.let {
            mapOf<String, Any?>("chatId" to chatId, "userId" to it["empNo"], "userNm" to it["name"], "deptNm" to "부서",
                "question" to it["question"], "answer" to it["answer"], "evidenceSummary" to "근거", "blindFieldKeys" to it["blindFieldKeys"],
                "review" to null, "trainAnswer" to trainAnswers[chatId])
        }
        override fun updateReview(chatId: Long, reviewCd: String, comment: String?, reviewerId: String): String? {
            reviewed = Triple(chatId, reviewCd, comment); return "2026-10-02 09:12:40"
        }
        override fun findTrainsetRows(from: LocalDate, to: LocalDate, rating: String?, source: String, hidden: String, limit: Int) =
            rows.map { it + mapOf("chatId" to it["messageId"], "rating" to "USEFUL", "ratingSource" to "USER",
                "trainAnswer" to trainAnswers[it["messageId"]]) }
    }

    private inner class MemAudit : AuditLogService(mock(AuditLogRepository::class.java), mock(AuthorizationService::class.java)) {
        val rows = mutableListOf<Triple<String, String?, String>>()
        override fun record(logType: String, menuId: String?, fieldKey: String?, targetDesc: String?, resultCd: String, maskedCnt: Int, remark: String?): Long? {
            rows += Triple(logType, targetDesc, resultCd); return null
        }
    }

    private var viewerBlind = emptySet<String>()
    private val dataFields = mock(DataFieldService::class.java, Answer<Any?> { inv ->
        when (inv.method.name) {
            "blindKeysFor" -> viewerBlind
            "appliedFieldsCached" -> listOf(mapOf("key" to "price", "name" to "단가·금액"), mapOf("key" to "yield", "name" to "수율·불량률"))
            else -> null
        }
    })
    private val repo = MemRepo()
    private val audit = MemAudit()
    private val service = AiAdminService(repo, mock(VectorIndexRepository::class.java), AuthorizationService(mock(AuthRepository::class.java)),
        ObjectMapper(), mock(AiAskDebugRecorder::class.java), dataFields, audit)

    /**
     * @param sys        전사 자연어 질의 이력(sys-chat-history) 접근 — scope=all · 관리 기능
     * @param superAdmin 통합관리자
     */
    private fun login(user: String, blind: Set<String>, sys: Boolean = false, unassigned: Boolean = false, superAdmin: Boolean = false) {
        viewerBlind = blind
        UserContext.set(UserPrincipal(user, "열람자", 4, if (unassigned) "미배정" else "제조팀", null, null, superAdmin,
            menuPerms = if (sys) setOf(MenuId.CHAT_HISTORY, MenuId.SYS_CHAT_HISTORY) else setOf(MenuId.CHAT_HISTORY), unassigned = unassigned))
    }

    @Test
    @DisplayName("CHH-02 — scope=all: 질의자보다 권한이 좁으면 응답·근거를 가리고 사유를 준다, 본인 질의·같은 권한은 그대로, 기록 이전은 가린다")
    fun masksAnswers() {
        login("10003", setOf("price", "plan", "customer", "yield"), sys = true)
        val page = service.getChatHistory(null, null, null, 1, 50, scope = "all")
        val byId = page.rows.associateBy { it["messageId"] }
        assertEquals(true, byId[1L]!!["answerHidden"]); assertNull(byId[1L]!!["answer"]); assertNull(byId[1L]!!["judgmentBasis"])
        assertTrue((byId[1L]!!["answerHiddenReason"] as String).contains("단가·금액, 수율·불량률"))
        assertEquals(false, byId[2L]!!["answerHidden"], "본인 질의는 가리지 않는다"); assertEquals("답 2", byId[2L]!!["answer"])
        assertEquals(true, byId[3L]!!["answerHidden"]); assertTrue((byId[3L]!!["answerHiddenReason"] as String).contains("도입 전"))
        assertEquals(2, page.maskedRowCnt)
        assertEquals("질문 1", byId[1L]!!["question"], "질문은 언제나 보인다")
        assertTrue(page.rows.none { it.containsKey("blindFieldKeys") })
        assertTrue(audit.rows.any { it.first == "MASK" && it.third == "BLIND" }); assertTrue(audit.rows.any { it.first == "RAW_VIEW" })

        login("10006", emptySet(), sys = true)
        assertEquals(0, service.getChatHistory(null, null, null, 1, 50, scope = "all").maskedRowCnt, "가릴 것이 없는 열람자에게는 모두 보인다")
    }

    @Test
    @DisplayName("V70 scope=all — 이름 · 사번을 가리지 않는다, sys-chat-history 접근이 없으면 403(E-AUTH-002), 범위 값 밖 400")
    fun allScope() {
        login("10003", emptySet(), sys = true)
        val byId = service.getChatHistory(null, null, null, 1, 50, scope = "all").rows.associateBy { it["messageId"] }
        assertEquals(setOf<Any?>(1L, 2L, 3L), byId.keys)
        assertEquals("관리자", byId[1L]!!["name"]); assertEquals("10000", byId[1L]!!["empNo"])
        assertEquals(true, service.getChatHistorySummary(null, null, null, scope = "all")["canManage"])

        login("10003", emptySet())
        assertThrows(MenuAccessDeniedException::class.java) { service.getChatHistory(null, null, null, 1, 50, scope = "all") }
        assertThrows(MenuAccessDeniedException::class.java) { service.getChatDetail(1L, "all") }
        assertEquals("scope", assertThrows(InvalidParameterException::class.java) { service.getChatHistory(null, null, null, 1, 50, scope = "team") }.field)
    }

    @Test
    @DisplayName("V70 scope=mine — 통합관리자도 본인 질의만, 남의 상세는 404, 관리 기능 없음(canManage=false · 디버그 없음)")
    fun mineScope() {
        login("10000", setOf("price"), superAdmin = true)
        val rows = service.getChatHistory(null, null, null, 1, 50).rows
        assertEquals(listOf(1L), rows.map { it["messageId"] }, "통합관리자도 mine 이면 본인 질의만")
        assertEquals(false, rows.single()["answerHidden"])
        assertThrows(ResourceNotFoundException::class.java) { service.getChatDetail(2L) }
        assertTrue("debug" !in service.getChatDetail(1L))
        assertEquals(false, service.getChatHistorySummary(null, null, null)["canManage"])
        assertEquals(3, service.getChatHistory(null, null, null, 1, 50, scope = "all").rows.size, "통합관리자는 all 로 전 사용자를 본다")
    }

    @Test
    @DisplayName("CHH-01 — 미배정도 mine 은 본인 이력만, 남의 상세는 404 · 본인 상세는 RAW_VIEW 없음")
    fun unassignedScope() {
        login("10003", emptySet(), unassigned = true)
        assertEquals(listOf(2L), service.getChatHistory(null, null, null, 1, 50).rows.map { it["messageId"] })
        assertThrows(ResourceNotFoundException::class.java) { service.getChatDetail(1L) }
        audit.rows.clear()
        service.getChatDetail(2L)
        assertTrue(audit.rows.none { it.first == "RAW_VIEW" })
        login("10003", emptySet(), sys = true)
        service.getChatDetail(1L, "all")
        assertTrue(audit.rows.any { it.first == "RAW_VIEW" && it.second!!.contains("질의자=10000") })
    }

    @Test
    @DisplayName("CHH-04 — 검토는 sys-chat-history 쓰기 동작(접근 없음 E-AUTH-002 · 미배정 E-AUTH-004), 코드 밖 400, 저장하면 검토자·시각을 돌려준다")
    fun review() {
        login("10003", emptySet())
        assertThrows(MenuAccessDeniedException::class.java) { service.saveReview(1L, "BAD", null) }
        login("10003", emptySet(), sys = true, unassigned = true)
        assertThrows(WriteAccessDeniedException::class.java) { service.saveReview(1L, "BAD", null) }
        login("10004", emptySet(), sys = true)
        assertEquals("reviewCd", assertThrows(InvalidParameterException::class.java) { service.saveReview(1L, "GOOD", null) }.field)
        val r = service.saveReview(1L, "bad", "  기간 오류  ")
        assertEquals("BAD", r["review"]); assertEquals("10004", r["reviewedBy"])
        assertEquals(Triple(1L, "BAD", "기간 오류"), repo.reviewed)
        assertThrows(ResourceNotFoundException::class.java) { service.saveReview(99L, "BAD", null) }
    }

    @Test
    @DisplayName("V70 학습 답변 — sys-chat-history 쓰기 동작, 앞뒤 공백 정리 · 공백만이면 지움 · 4000자 초과 400 · 감사 1행")
    fun trainAnswer() {
        login("10003", emptySet())
        assertThrows(MenuAccessDeniedException::class.java) { service.saveTrainAnswer(1L, "답") }
        login("10004", emptySet(), sys = true)
        audit.rows.clear()
        val saved = service.saveTrainAnswer(1L, "  올바른 답  ")
        assertEquals(1L, saved["messageId"]); assertEquals("올바른 답", saved["trainAnswer"]); assertEquals("10004", saved["trainAnswerBy"])
        assertEquals("올바른 답", repo.trainAnswers[1L])
        assertTrue(audit.rows.any { it.first == "RAW_VIEW" && it.second!!.contains("학습 답변 messageId=1") })

        val cleared = service.saveTrainAnswer(1L, "   ")
        assertNull(cleared["trainAnswer"]); assertNull(cleared["trainAnswerBy"]); assertTrue(1L !in repo.trainAnswers)
        assertEquals("answer", assertThrows(InvalidParameterException::class.java) { service.saveTrainAnswer(1L, "가".repeat(4001)) }.field)
        assertThrows(ResourceNotFoundException::class.java) { service.saveTrainAnswer(99L, "답") }
    }

    @Test
    @DisplayName("CHH-03 — 열람자 권한으로 볼 수 없는 샘플은 빼고 센다, 필터·출처 값 검증, 원문 질문, 학습 답변이 있으면 응답 대신 쓴다")
    fun trainset() {
        login("10003", emptySet())
        assertThrows(MenuAccessDeniedException::class.java) { service.getTrainsetLines(null, null, null) }
        login("10004", setOf("price"), sys = true)
        val r = service.getTrainsetLines(null, null, null)
        assertEquals(1, r.lines.size, "질의 1(통합관리자)·3(기록 이전)은 가려져 빠진다 — 질의 2 만"); assertEquals(2, r.blindCnt)
        assertEquals("USEFUL", r.rating); assertEquals("REVIEW_OR_USER", r.source)
        assertTrue(r.lines.single().contains("질문 2"))
        assertTrue(r.lines.single().contains("\"source\":\"ANSWER\""))
        assertEquals("ratingFilter", assertThrows(InvalidParameterException::class.java) { service.getTrainsetLines(null, null, "GOOD") }.field)
        assertEquals("source", assertThrows(InvalidParameterException::class.java) { service.getTrainsetLines(null, null, "ALL", "ANY") }.field)

        repo.trainAnswers[2L] = "학습용 올바른 답"
        val t = service.getTrainsetLines(null, null, null).lines.single()
        assertTrue(t.contains("학습용 올바른 답") && !t.contains("답 2"), t)
        assertTrue(t.contains("\"source\":\"TRAIN_ANSWER\""), t)
    }
}
