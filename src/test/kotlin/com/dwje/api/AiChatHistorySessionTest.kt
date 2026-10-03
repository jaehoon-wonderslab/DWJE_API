package com.dwje.api

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.exception.ResourceNotFoundException
import com.dwje.api.common.security.UserContext
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.service.AiAdminService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
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

/**
 * 질의 이력 세션별 조회 — 실제 로컬 DB (08 CHH-18). 기대값은 DB 에서 직접 센 값과 비교한다.
 * 통합관리자 · scope=all(전사, V70)로 조회해 감사 RAW_VIEW 를 남기지 않게 하고(본인 아님 조회는 감사가 남는다), 남의 세션 열람은 단위 시험(AiChatHistoryMaskTest)이 맡는다.
 */
@SpringBootTest(properties = ["app.ai.prewarm-enabled=false"])
@ActiveProfiles("local")
class AiChatHistorySessionTest {

    @Autowired lateinit var service: AiAdminService
    @Autowired lateinit var jdbc: NamedParameterJdbcTemplate
    // 남의 기록 열람(RAW_VIEW)이 지울 수 없는 감사 표에 커밋되지 않게 (V51)
    @org.springframework.test.context.bean.override.mockito.MockitoBean lateinit var audit: com.dwje.api.service.AuditLogService

    private val from = "2026-09-09"
    private val to = "2026-09-23"

    @BeforeEach
    fun login() = UserContext.set(UserPrincipal("10000", "관리자", 1, "통합관리자", null, null, true))

    @AfterEach
    fun clear() = UserContext.clear()

    private fun one(sql: String) = jdbc.queryForObject(sql, MapSqlParameterSource(), Long::class.java)!!

    @Test
    @DisplayName("세션 수·질의 수가 DB 와 같고, 세션 상세는 시간순, 없는 키는 404, 93일은 400")
    fun sessions() {
        val window = "asked_at >= '$from'::date AND asked_at < '$to'::date + 1"
        val expectSessions = one("SELECT count(DISTINCT coalesce(session_id::text, 'chat-' || chat_id)) FROM ax.tb_ai_chat_log WHERE $window")
        val expectQuestions = one("SELECT count(*) FROM ax.tb_ai_chat_log WHERE $window")
        val (rows, meta) = service.getChatSessions(from, to, null, null, null, 1, 1000, scope = "all")
        assertEquals(expectSessions, meta.total)
        assertEquals(expectQuestions, rows.sumOf { (it["questionCnt"] as Int).toLong() })

        rows.maxByOrNull { it["questionCnt"] as Int }?.let { biggest ->
            @Suppress("UNCHECKED_CAST")
            val turns = service.getChatSession(biggest["sessionKey"] as String, "all")["turns"] as List<Map<String, Any?>>
            assertTrue(turns.size >= (biggest["questionCnt"] as Int))
            assertEquals(turns.map { it["askedAt"] as String }.sorted(), turns.map { it["askedAt"] as String })
        }
        listOf("chat-999999999", "00000000-0000-0000-0000-000000000000", "abc").forEach { key ->
            assertThrows(ResourceNotFoundException::class.java) { service.getChatSession(key, "all") }
        }
        assertThrows(InvalidParameterException::class.java) { service.getChatSessions("2026-06-01", "2026-09-01", null, null, null, 1, 50) }
    }

    @Test
    @DisplayName("검색어는 세션 안 질문 하나만 걸려도 그 세션을 낸다")
    fun keyword() {
        val q = jdbc.queryForList("SELECT question FROM ax.tb_ai_chat_log WHERE asked_at >= '$from'::date ORDER BY chat_id LIMIT 1",
            MapSqlParameterSource(), String::class.java).firstOrNull() ?: return
        val word = q.trim().split(Regex("\\s+")).first()
        val (rows, _) = service.getChatSessions(from, to, null, null, word, 1, 1000, scope = "all")
        assertTrue(rows.isNotEmpty())
    }
}
