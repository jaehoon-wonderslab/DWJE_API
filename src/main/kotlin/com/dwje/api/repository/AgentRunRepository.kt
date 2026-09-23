package com.dwje.api.repository

import com.dwje.api.common.util.Rs
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime

/**
 * Agent 마스터·실행 이력 접근 Repository (SY-12 · Agent 실행 현황)
 *
 * `ax.tb_ai_agent` 9행은 고정 마스터다. 실행 이력(`ax.tb_ai_agent_run`)은 각 Agent 가
 * 제 일을 마칠 때 한 줄씩 쌓고, 화면의 상태·최근 실행·처리량은 그 최신 행에서 뽑는다.
 *
 * `agent_id` 는 코드에 박지 않는다. 표의 `agent_no`(①~⑨)로 찾아 쓴다 —
 * 식별자가 환경마다 다를 수 있기 때문이다.
 */
@Repository
class AgentRunRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) {

    /**
     * 실행 이력 한 줄을 남긴다.
     *
     * `agent_no` 로 마스터를 찾아 넣으므로, 없는 번호를 주면 **0행이 들어가고 0 을 돌려준다**
     * (예외가 아니다). 기록은 곁다리라 본 기능을 멈춰 세울 이유가 없다.
     *
     * @return 생성된 run_id. 대상 Agent 가 없으면 0
     */
    fun insert(
        agentNo: String,
        stateCd: String,
        throughput: String?,
        elapsedMs: Long?,
        message: String?,
        error: Boolean = false
    ): Long {
        val sql = """
            INSERT INTO ax.tb_ai_agent_run (agent_id, run_at, state_cd, throughput_txt, elapsed_ms, message, err_flg)
            SELECT a.agent_id, now(), :stateCd, :throughput, :elapsedMs, :message, :errFlg
            FROM ax.tb_ai_agent a
            WHERE a.agent_no = :agentNo
            RETURNING run_id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("agentNo", agentNo)
            .addValue("stateCd", stateCd)
            .addValue("throughput", throughput?.take(50))
            .addValue("elapsedMs", elapsedMs?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt())
            .addValue("message", message?.take(500))
            .addValue("errFlg", if (error) "Y" else "N")

        return jdbcTemplate.query(sql, params) { rs, _ -> rs.getLong("run_id") }.firstOrNull() ?: 0L
    }

    /**
     * Agent 9종과 각자의 최신 실행을 함께 조회한다. (No.211)
     *
     * 실행 이력이 없는 Agent 도 빠지지 않게 `LEFT JOIN LATERAL` 로 붙인다 —
     * 한 번도 돈 적 없는 Agent 는 `state=IDLE · lastRunAt=null` 로 나간다.
     */
    fun findAgents(): List<Map<String, Any?>> {
        val sql = """
            SELECT
                a.agent_id, a.agent_no, a.agent_nm, a.agent_desc, a.use_flg,
                r.state_cd, r.run_at, r.throughput_txt, r.elapsed_ms, r.err_flg, r.message,
                sc.code_nm AS state_nm,
                (SELECT count(*) FROM ax.tb_ai_agent_run ar2
                  WHERE ar2.agent_id = a.agent_id AND ar2.run_at >= now() - interval '24 hours') AS run_cnt_24h,
                (SELECT count(*) FROM ax.tb_ai_agent_run ar3
                  WHERE ar3.agent_id = a.agent_id AND ar3.err_flg = 'Y'
                    AND ar3.run_at >= now() - interval '24 hours') AS err_cnt_24h
            FROM ax.tb_ai_agent a
            LEFT JOIN LATERAL (
                SELECT ar.state_cd, ar.run_at, ar.throughput_txt, ar.elapsed_ms, ar.err_flg, ar.message
                FROM ax.tb_ai_agent_run ar
                WHERE ar.agent_id = a.agent_id
                ORDER BY ar.run_at DESC
                LIMIT 1
            ) r ON true
            LEFT JOIN ax.tb_sys_code sc ON sc.group_cd = 'AI_AGENT_STATE' AND sc.code = r.state_cd
            WHERE a.use_flg = 'Y'
            ORDER BY a.sort_seq
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource()) { rs, _ ->
            mapOf(
                "agentId" to rs.getInt("agent_id"),
                "no" to rs.getString("agent_no"),
                // 경로 변수로 쓰는 값 — ①~⑨ 를 그대로 쓴다 (URL 인코딩 필요)
                "agentCd" to rs.getString("agent_no"),
                "name" to rs.getString("agent_nm"),
                "desc" to rs.getString("agent_desc"),
                "state" to (rs.getString("state_cd") ?: "IDLE"),
                "stateNm" to (rs.getString("state_nm") ?: "대기"),
                "lastRunAt" to Rs.dateTime(rs, "run_at"),
                "load" to rs.getString("throughput_txt"),
                "elapsedMs" to Rs.intOrNull(rs, "elapsed_ms"),
                "message" to rs.getString("message"),
                "error" to (rs.getString("err_flg")?.let { it == "Y" } ?: false),
                "runCnt24h" to rs.getLong("run_cnt_24h"),
                "errCnt24h" to rs.getLong("err_cnt_24h")
            )
        }
    }

    /**
     * Agent 실행 현황 요약 (No.210)
     *
     * `master.state` 는 최근 10분 창으로 판정한다. **그 창에 행이 하나도 없으면 `IDLE`** 이다 —
     * 예전에는 `OK` 를 돌려줬는데, 아무것도 안 돌고 있는 것과 정상인 것을 같은 값으로 보이게 해
     * 장애를 정상으로 읽히게 만들었다.
     */
    fun findSummary(): Map<String, Any?> {
        val sql = """
            SELECT
                count(*)                                       AS total_cnt,
                count(*) FILTER (WHERE r.err_flg = 'Y')        AS err_cnt,
                count(*) FILTER (WHERE r.state_cd = 'RUNNING') AS running_cnt,
                count(DISTINCT r.agent_id)                     AS active_agent_cnt,
                round(avg(r.elapsed_ms))                       AS avg_elapsed_ms,
                max(r.run_at)                                  AS last_run_at
            FROM ax.tb_ai_agent_run r
            WHERE r.run_at >= now() - interval '10 minutes'
        """.trimIndent()

        val window = jdbcTemplate.queryForObject(sql, MapSqlParameterSource()) { rs, _ ->
            val total = rs.getLong("total_cnt")
            val errCnt = rs.getLong("err_cnt")
            val runningCnt = rs.getLong("running_cnt")
            val avgElapsed = Rs.intOrNull(rs, "avg_elapsed_ms")
            mapOf(
                "master" to mapOf(
                    "state" to when {
                        errCnt > 0 -> "ERROR"
                        runningCnt > 0 -> "RUNNING"
                        total > 0 -> "OK"
                        else -> "IDLE"
                    },
                    "mode" to "AUTO",
                    "recentRunCnt" to total,
                    "avgElapsedMs" to avgElapsed
                ),
                "activeAgentCnt" to rs.getLong("active_agent_cnt"),
                // 분당 이벤트 — 10분 창의 실행 건수를 10 으로 나눈다
                "eventsPerMin" to Math.round(total / 10.0 * 10.0) / 10.0,
                "avgResponseSec" to avgElapsed?.let { Math.round(it / 1000.0 * 100.0) / 100.0 },
                "lastRunAt" to Rs.dateTime(rs, "last_run_at")
            )
        } ?: emptyMap()

        val totals = jdbcTemplate.queryForObject(
            """
            SELECT
                (SELECT count(*) FROM ax.tb_ai_agent WHERE use_flg = 'Y')                        AS agent_cnt,
                (SELECT count(*) FROM ax.tb_ai_agent_run)                                        AS run_cnt,
                (SELECT count(*) FROM ax.tb_ai_agent_run WHERE run_at >= now() - interval '24 hours') AS run_cnt_24h,
                (SELECT count(*) FROM ax.tb_ai_agent_run
                  WHERE err_flg = 'Y' AND run_at >= now() - interval '24 hours')                 AS err_cnt_24h
            """.trimIndent(),
            MapSqlParameterSource()
        ) { rs, _ ->
            mapOf(
                "agentCnt" to rs.getLong("agent_cnt"),
                "runCnt" to rs.getLong("run_cnt"),
                "runCnt24h" to rs.getLong("run_cnt_24h"),
                "errCnt24h" to rs.getLong("err_cnt_24h")
            )
        } ?: emptyMap()

        return window + totals
    }

    /** `agent_no` 로 Agent 한 건 — 없으면 null */
    fun findAgent(agentNo: String): Map<String, Any?>? =
        jdbcTemplate.query(
            "SELECT agent_id, agent_no, agent_nm, use_flg FROM ax.tb_ai_agent WHERE agent_no = :agentNo",
            MapSqlParameterSource("agentNo", agentNo)
        ) { rs, _ ->
            mapOf(
                "agentId" to rs.getInt("agent_id"),
                "no" to rs.getString("agent_no"),
                "name" to rs.getString("agent_nm"),
                "active" to Rs.yn(rs, "use_flg")
            )
        }.firstOrNull()

    /** Agent 한 종의 실행 이력 목록 (No.214) */
    fun findRuns(
        agentNo: String,
        from: OffsetDateTime?,
        to: OffsetDateTime?,
        stateCd: String?,
        limit: Int,
        offset: Int
    ): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT r.run_id, r.run_at, r.state_cd, sc.code_nm AS state_nm,
                   r.throughput_txt, r.elapsed_ms, r.message, r.err_flg
            FROM ax.tb_ai_agent_run r
            INNER JOIN ax.tb_ai_agent a ON a.agent_id = r.agent_id
            LEFT  JOIN ax.tb_sys_code sc ON sc.group_cd = 'AI_AGENT_STATE' AND sc.code = r.state_cd
            WHERE a.agent_no = :agentNo
            """.trimIndent()
        )
        val params = MapSqlParameterSource("agentNo", agentNo)
        appendRunFilters(sql, params, from, to, stateCd)
        sql.append("\nORDER BY r.run_at DESC, r.run_id DESC\nLIMIT :limit OFFSET :offset")
        params.addValue("limit", limit).addValue("offset", offset)

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "runId" to rs.getLong("run_id"),
                "runAt" to Rs.dateTime(rs, "run_at"),
                "state" to rs.getString("state_cd"),
                "stateNm" to rs.getString("state_nm"),
                "load" to rs.getString("throughput_txt"),
                "elapsedMs" to Rs.intOrNull(rs, "elapsed_ms"),
                "message" to rs.getString("message"),
                "error" to Rs.yn(rs, "err_flg")
            )
        }
    }

    /** 실행 이력 전체 건수 */
    fun countRuns(agentNo: String, from: OffsetDateTime?, to: OffsetDateTime?, stateCd: String?): Long {
        val sql = StringBuilder(
            """
            SELECT count(*)
            FROM ax.tb_ai_agent_run r
            INNER JOIN ax.tb_ai_agent a ON a.agent_id = r.agent_id
            WHERE a.agent_no = :agentNo
            """.trimIndent()
        )
        val params = MapSqlParameterSource("agentNo", agentNo)
        appendRunFilters(sql, params, from, to, stateCd)
        return jdbcTemplate.queryForObject(sql.toString(), params, Long::class.java) ?: 0L
    }

    /** 목록/건수 공통 동적 조건 */
    private fun appendRunFilters(
        sql: StringBuilder,
        params: MapSqlParameterSource,
        from: OffsetDateTime?,
        to: OffsetDateTime?,
        stateCd: String?
    ) {
        if (from != null) {
            sql.append(" AND r.run_at >= :from")
            params.addValue("from", from)
        }
        if (to != null) {
            sql.append(" AND r.run_at < :toExclusive")
            params.addValue("toExclusive", to)
        }
        if (!stateCd.isNullOrBlank()) {
            sql.append(" AND r.state_cd = :stateCd")
            params.addValue("stateCd", stateCd.trim())
        }
    }

    /**
     * Agent 사용/미사용 전환.
     *
     * 마스터 9행은 고정이라 지우지 않는다 — 실행 이력이 `agent_id` 로 물려 있어 지우면 이력도 끊긴다.
     * 끄면 목록·요약에서 빠지고, 기록(`insert`)은 `agent_no` 로 찾으므로 계속 쌓인다.
     */
    fun updateUseFlg(agentNo: String, on: Boolean): Int =
        jdbcTemplate.update(
            "UPDATE ax.tb_ai_agent SET use_flg = :useFlg WHERE agent_no = :agentNo AND use_flg <> :useFlg",
            MapSqlParameterSource().addValue("agentNo", agentNo).addValue("useFlg", if (on) "Y" else "N")
        )

}
