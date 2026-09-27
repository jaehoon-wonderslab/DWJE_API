package com.dwje.api

import com.dwje.api.repository.AiChatDebugRepository
import com.dwje.api.service.AiAskDebug
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.stubbing.Answer
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.time.LocalDate
import java.util.UUID

class AiChatDebugRepositoryTest {
    @Test fun `debug storage contains route and counts but no question SQL or credentials`() {
        val jdbc = mock(NamedParameterJdbcTemplate::class.java, Answer<Any?> { call ->
            if (call.method.name == "update") 1 else null
        })
        val record = AiAskDebug(UUID.randomUUID(), "u1", 7L, "DEFECT_RATE_TOP", "EXPLICIT_RANGE",
            "defect_rate_top", "OK", null, LocalDate.parse("2026-09-20"), LocalDate.parse("2026-09-22"),
            20, 0, 13, 25)
        AiChatDebugRepository(jdbc).insert(record)
        val call = mockingDetails(jdbc).invocations.single()
        val sql = call.arguments[0] as String
        val params = call.arguments[1] as MapSqlParameterSource
        assertTrue(sql.trimStart().startsWith("INSERT INTO ax.tb_ai_chat_debug"))
        assertEquals("DEFECT_RATE_TOP", params.getValue("route"))
        assertEquals(20, params.getValue("rowCount"))
        assertEquals(LocalDate.parse("2026-09-20"), params.getValue("periodFrom"))
        assertEquals(LocalDate.parse("2026-09-22"), params.getValue("periodTo"))
        assertFalse(params.parameterNames.any { it.contains("question", true) || it.contains("sql", true) ||
            it.contains("token", true) || it.contains("password", true) })
    }
}
