package com.dwje.api.repository

import com.dwje.api.service.AiAskDebug
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

/** 질문 원문·SQL·인증정보가 없는 진단 메타데이터만 저장한다. */
@Repository
class AiChatDebugRepository(private val jdbc: NamedParameterJdbcTemplate) {
    fun available(): Boolean = jdbc.queryForObject(
        "SELECT to_regclass('ax.tb_ai_chat_debug') IS NOT NULL",
        MapSqlParameterSource(), Boolean::class.java
    ) == true

    fun insert(debug: AiAskDebug): Int {
        val sql = """
            INSERT INTO ax.tb_ai_chat_debug
                (request_id, chat_id, user_id, route_cd, parse_cd, tool_cd, execute_cd,
                 error_cd, period_from, period_to, row_cnt, doc_hit_cnt, tool_ms, total_ms)
            VALUES (:requestId, :chatId, :userId, :route, :parseCode, :tool, :executionCode,
                    :errorCode, :periodFrom, :periodTo, :rowCount, :docHitCount, :toolMs, :totalMs)
            ON CONFLICT (request_id) DO NOTHING
        """.trimIndent()
        return jdbc.update(sql, MapSqlParameterSource("requestId", debug.requestId)
            .addValue("chatId", debug.chatId).addValue("userId", debug.userId)
            .addValue("route", debug.route).addValue("parseCode", debug.parseCode)
            .addValue("tool", debug.tool).addValue("executionCode", debug.executionCode)
            .addValue("errorCode", debug.errorCode).addValue("periodFrom", debug.periodFrom)
            .addValue("periodTo", debug.periodTo).addValue("rowCount", debug.rowCount)
            .addValue("docHitCount", debug.docHitCount).addValue("toolMs", debug.toolMs)
            .addValue("totalMs", debug.totalMs))
    }

    fun findByChatId(chatId: Long): Map<String, Any?>? = find("chat_id = :chatId", MapSqlParameterSource("chatId", chatId))

    fun findByRequestId(requestId: UUID): Map<String, Any?>? =
        find("request_id = :requestId", MapSqlParameterSource("requestId", requestId))

    private fun find(where: String, params: MapSqlParameterSource): Map<String, Any?>? {
        val sql = """
            SELECT request_id, chat_id, user_id, asked_at, route_cd, parse_cd, tool_cd, execute_cd,
                   error_cd, period_from, period_to, row_cnt, doc_hit_cnt, tool_ms, total_ms
            FROM ax.tb_ai_chat_debug WHERE $where LIMIT 1
        """.trimIndent()
        return jdbc.query(sql, params) { rs, _ ->
            mapOf("requestId" to rs.getString("request_id"), "messageId" to rs.getObject("chat_id"),
                "userId" to rs.getString("user_id"), "askedAt" to rs.getString("asked_at"),
                "route" to rs.getString("route_cd"), "parseCode" to rs.getString("parse_cd"),
                "tool" to rs.getString("tool_cd"), "executionCode" to rs.getString("execute_cd"),
                "errorCode" to rs.getString("error_cd"), "periodFrom" to rs.getString("period_from"),
                "periodTo" to rs.getString("period_to"), "rowCount" to rs.getInt("row_cnt"),
                "documentHitCount" to rs.getInt("doc_hit_cnt"), "toolMs" to rs.getInt("tool_ms"),
                "totalMs" to rs.getInt("total_ms"))
        }.firstOrNull()
    }
}
