package com.dwje.api

import com.dwje.api.repository.AiChatRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.stubbing.Answer
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.time.LocalDate
import java.util.UUID

/** V43 미적용 서버에서도 /ask, 답 저장, 이력 조회가 컬럼 누락 SQL을 만들지 않는다. */
class AiChatSchemaCompatibilityTest {
    private fun fixture(columnCount: Int): Pair<AiChatRepository, NamedParameterJdbcTemplate> {
        val jdbc = mock(NamedParameterJdbcTemplate::class.java, Answer<Any?> { call ->
            val sql = call.arguments.firstOrNull() as? String ?: return@Answer null
            when (call.method.name) {
                "queryForObject" -> if (sql.contains("information_schema.columns")) columnCount else 7L
                "query" -> emptyList<Any>()
                "update" -> 1
                else -> null
            }
        })
        return AiChatRepository(jdbc) to jdbc
    }

    private fun exercise(repository: AiChatRepository) {
        repository.insertChatLog(UUID.randomUUID(), "u1", "품질", "불량 top 2", "불량 top 2",
            "metric", "지표", null, 20, 0, null, null, "불량 2건", "응답 생성 중")
        repository.updateLlmAnswer(7L, "u1", "답변", 30)
        repository.findChatLog(7L)
        repository.findHistory(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-23"), null, 20, 0)
    }

    @Test fun `legacy schema omits V43 columns from write SQL and aliases read fields`() {
        val (repo, jdbc) = fixture(0)
        exercise(repo)
        val sqls = mockingDetails(jdbc).invocations.mapNotNull { it.arguments.firstOrNull() as? String }
        val insert = sqls.single { it.startsWith("INSERT INTO ax.tb_ai_chat_log") }
        val update = sqls.single { it.startsWith("UPDATE ax.tb_ai_chat_log") }
        assertFalse(insert.contains("evidence_summary"))
        assertFalse(insert.contains("unanswered_reason"))
        assertFalse(update.contains("unanswered_reason"))
        val reads = sqls.filter { it.contains("FROM ax.tb_ai_chat_log c") && it.contains("AS evidence_summary") }
        assertEquals(2, reads.size)
        assertTrue(reads.all { it.contains("NULL::text AS unanswered_reason") })
    }

    @Test fun `V43 schema persists basis and reason in write and read SQL`() {
        val (repo, jdbc) = fixture(2)
        exercise(repo)
        val sqls = mockingDetails(jdbc).invocations.mapNotNull { it.arguments.firstOrNull() as? String }
        assertTrue(sqls.single { it.startsWith("INSERT INTO ax.tb_ai_chat_log") }.contains("evidence_summary, unanswered_reason"))
        assertTrue(sqls.single { it.startsWith("UPDATE ax.tb_ai_chat_log") }.contains("unanswered_reason = CASE"))
        assertEquals(2, sqls.count { it.contains("c.evidence_summary, c.unanswered_reason") })
    }
}
