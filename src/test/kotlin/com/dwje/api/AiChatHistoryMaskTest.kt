package com.dwje.api

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.exception.ResourceNotFoundException
import com.dwje.api.common.exception.WriteAccessDeniedException
import com.dwje.api.common.security.UserContext
import com.dwje.api.common.security.UserPrincipal
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
 * 질의 이력 열람 범위·응답 가림·이름 가림·검토·학습데이터 — 08 기획서 CHH-01·02·03·04·09 (DB 없이)
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
        override fun findHistory(from: LocalDate, to: LocalDate, filter: AiChatRepository.HistoryFilter, limit: Int, offset: Int) =
            rows.filter { filter.scopeUserId == null || it["empNo"] == filter.scopeUserId }
        override fun countHistory(from: LocalDate, to: LocalDate, filter: AiChatRepository.HistoryFilter) =
            findHistory(from, to, filter, 50, 0).size.toLong()
        override fun findChatLog(chatId: Long) = rows.firstOrNull { it["messageId"] == chatId }?.let {
            mapOf<String, Any?>("chatId" to chatId, "userId" to it["empNo"], "userNm" to it["name"], "deptNm" to "부서",
                "question" to it["question"], "answer" to it["answer"], "evidenceSummary" to "근거", "blindFieldKeys" to it["blindFieldKeys"],
                "review" to null)
        }
        override fun updateReview(chatId: Long, reviewCd: String, comment: String?, reviewerId: String): String? {
            reviewed = Triple(chatId, reviewCd, comment); return "2026-10-02 09:12:40"
        }
        override fun findTrainsetRows(from: LocalDate, to: LocalDate, rating: String?, source: String, hidden: String, limit: Int) =
            rows.map { it + mapOf("chatId" to it["messageId"], "rating" to "USEFUL", "ratingSource" to "USER") }
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

    private fun login(user: String, blind: Set<String>, write: Boolean = false, unassigned: Boolean = false) {
        viewerBlind = blind
        UserContext.set(UserPrincipal(user, "열람자", 4, if (unassigned) "미배정" else "제조팀", null, null, null, false,
            menuPerms = setOf("chat-history"), writePerms = if (write) setOf("chat-history") else emptySet(), unassigned = unassigned))
    }

    @Test
    @DisplayName("CHH-02 — 질의자보다 권한이 좁으면 응답·근거를 가리고 사유를 준다, 본인 질의·같은 권한은 그대로, 기록 이전은 가린다")
    fun masksAnswers() {
        login("10003", setOf("price", "plan", "customer", "yield"))
        val page = service.getChatHistory(null, null, null, 1, 50)
        val byId = page.rows.associateBy { it["messageId"] }
        assertEquals(true, byId[1L]!!["answerHidden"]); assertNull(byId[1L]!!["answer"]); assertNull(byId[1L]!!["judgmentBasis"])
        assertTrue((byId[1L]!!["answerHiddenReason"] as String).contains("단가·금액, 수율·불량률"))
        assertEquals(false, byId[2L]!!["answerHidden"], "본인 질의는 가리지 않는다"); assertEquals("답 2", byId[2L]!!["answer"])
        assertEquals(true, byId[3L]!!["answerHidden"]); assertTrue((byId[3L]!!["answerHiddenReason"] as String).contains("도입 전"))
        assertEquals(2, page.maskedRowCnt)
        assertEquals("질문 1", byId[1L]!!["question"], "질문은 언제나 보인다")
        assertTrue(page.rows.none { it.containsKey("blindFieldKeys") })
        assertTrue(audit.rows.any { it.first == "MASK" && it.third == "BLIND" }); assertTrue(audit.rows.any { it.first == "RAW_VIEW" })

        login("10006", emptySet())
        assertEquals(0, service.getChatHistory(null, null, null, 1, 50).maskedRowCnt, "가릴 것이 없는 열람자에게는 모두 보인다")
    }

    @Test
    @DisplayName("CHH-09 — 쓰기 권한이 없으면 남의 이름은 첫 글자만, 사번은 비운다 · 쓰기 권한자와 본인은 그대로")
    fun masksNames() {
        login("10003", emptySet())
        val byId = service.getChatHistory(null, null, null, 1, 50).rows.associateBy { it["messageId"] }
        assertEquals("관**", byId[1L]!!["name"]); assertNull(byId[1L]!!["empNo"])
        assertEquals("제조", byId[2L]!!["name"]); assertEquals("10003", byId[2L]!!["empNo"])
        login("10004", emptySet(), write = true)
        assertEquals("관리자", service.getChatHistory(null, null, null, 1, 50).rows.first { it["messageId"] == 1L }["name"])
    }

    @Test
    @DisplayName("CHH-01 · D-22 — 미배정은 본인 이력만, 남의 상세는 404 · 남의 상세를 보면 RAW_VIEW, 본인 상세는 없음")
    fun unassignedScope() {
        login("10003", emptySet(), unassigned = true)
        assertEquals(listOf(2L), service.getChatHistory(null, null, null, 1, 50).rows.map { it["messageId"] })
        assertThrows(ResourceNotFoundException::class.java) { service.getChatDetail(1L) }
        audit.rows.clear()
        service.getChatDetail(2L)
        assertTrue(audit.rows.none { it.first == "RAW_VIEW" })
        login("10003", emptySet())
        service.getChatDetail(1L)
        assertTrue(audit.rows.any { it.first == "RAW_VIEW" && it.second!!.contains("질의자=10000") })
    }

    @Test
    @DisplayName("CHH-04 — 검토는 쓰기 권한(E-AUTH-004), 코드 밖 400, 저장하면 검토자·시각을 돌려준다")
    fun review() {
        login("10003", emptySet())
        assertThrows(WriteAccessDeniedException::class.java) { service.saveReview(1L, "BAD", null) }
        login("10004", emptySet(), write = true)
        assertEquals("reviewCd", assertThrows(InvalidParameterException::class.java) { service.saveReview(1L, "GOOD", null) }.field)
        val r = service.saveReview(1L, "bad", "  기간 오류  ")
        assertEquals("BAD", r["review"]); assertEquals("10004", r["reviewedBy"])
        assertEquals(Triple(1L, "BAD", "기간 오류"), repo.reviewed)
        assertThrows(ResourceNotFoundException::class.java) { service.saveReview(99L, "BAD", null) }
    }

    @Test
    @DisplayName("CHH-03 — 열람자 권한으로 볼 수 없는 샘플은 빼고 센다, 필터·출처 값 검증, 원문 질문")
    fun trainset() {
        login("10004", setOf("price"), write = true)
        val r = service.getTrainsetLines(null, null, null)
        assertEquals(1, r.lines.size, "질의 1(통합관리자)·3(기록 이전)은 가려져 빠진다 — 질의 2 만"); assertEquals(2, r.blindCnt)
        assertEquals("USEFUL", r.rating); assertEquals("REVIEW_OR_USER", r.source)
        assertTrue(r.lines.single().contains("질문 2"))
        assertEquals("ratingFilter", assertThrows(InvalidParameterException::class.java) { service.getTrainsetLines(null, null, "GOOD") }.field)
        assertEquals("source", assertThrows(InvalidParameterException::class.java) { service.getTrainsetLines(null, null, "ALL", "ANY") }.field)
    }
}
