package com.dwje.api

import com.dwje.api.repository.AiChatRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/** 질의 기록의 가린 항목·근거 수집 구간·평가 의견·검토 저장이 실제 표에 들어가는지 (08 CHH-02·04, V55). 롤백한다. */
@SpringBootTest(properties = ["app.ai.prewarm-enabled=false"])
@ActiveProfiles("local")
class AiChatLogColumnsSqlTest {

    @Autowired lateinit var repo: AiChatRepository
    @Autowired lateinit var jdbc: NamedParameterJdbcTemplate
    @Autowired lateinit var tx: PlatformTransactionManager

    @Test
    @DisplayName("blind_field_keys 는 정렬된 text[](빈 집합은 '{}'), ask_ms·rating_comment·review_* 가 저장된다")
    fun writesColumns() {
        TransactionTemplate(tx).execute { status ->
            status.setRollbackOnly()
            val id = repo.insertChatLog(UUID.randomUUID(), "10000", "통합관리자", "시험 질문", "시험 질문", "metric", "지표",
                "답", 120, 0, null, null, "근거", null, blindFieldKeys = setOf("yield", "price"), askMs = 120)
            val empty = repo.insertChatLog(UUID.randomUUID(), "10000", "통합관리자", "시험 질문2", null, "metric", "지표",
                "답", 10, 0, null, null, null, null, blindFieldKeys = emptySet(), askMs = 10)
            val p = MapSqlParameterSource("id", id)
            assertEquals("{price,yield}", jdbc.queryForObject("SELECT blind_field_keys::text FROM ax.tb_ai_chat_log WHERE chat_id = :id", p, String::class.java))
            assertEquals("{}", jdbc.queryForObject("SELECT blind_field_keys::text FROM ax.tb_ai_chat_log WHERE chat_id = :id",
                MapSqlParameterSource("id", empty), String::class.java))
            assertEquals(120, jdbc.queryForObject("SELECT ask_ms FROM ax.tb_ai_chat_log WHERE chat_id = :id", p, Int::class.java))
            assertEquals(setOf("price", "yield"), repo.findChatLog(id)!!["blindFieldKeys"])

            repo.updateRating(id, "USEFUL", "10000", "정확")
            assertEquals("정확", jdbc.queryForObject("SELECT rating_comment FROM ax.tb_ai_chat_log WHERE chat_id = :id AND rated_at IS NOT NULL", p, String::class.java))
            repo.updateReview(id, "BAD", "기간 오류", "10004")
            val r = repo.findChatLog(id)!!
            assertEquals("BAD", r["review"]); assertEquals("10004", r["reviewedBy"]); assertEquals("USEFUL", r["rating"], "질의자 평가는 그대로")
        }
    }
}
