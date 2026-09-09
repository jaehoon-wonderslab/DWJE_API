package com.dwje.api.repository

import com.dwje.api.common.util.Rs
import com.dwje.api.common.util.TimeWindow
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 비가동 관리 Repository (PR-05)
 *
 * 참조 테이블 : ax.tb_prod_downtime(확장), mes.tb_md_eqpt, ax.tb_sys_code(DOWN_REASON),
 *              ax.tb_ai_agent_run
 */
@Repository
class DowntimeRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) {

    /**
     * 비가동 요약을 조회한다. (No.68)
     *
     * @param plantCd 사업장 코드
     * @param date    기준일
     * @return totalMin, registeredCnt, unregisteredCnt
     */
    fun findSummary(plantCd: String, date: LocalDate): Map<String, Any?> =
        findSummary(plantCd, TimeWindow.ofDay(date))

    /** 비가동 요약 — 집계 구간을 직접 지정한다. */
    fun findSummary(plantCd: String, window: TimeWindow): Map<String, Any?> {
        val sql = """
            SELECT
                coalesce(sum(d.elapsed_min), 0)                    AS total_min,
                count(*) FILTER (WHERE d.is_registered)            AS registered_cnt,
                count(*) FILTER (WHERE NOT d.is_registered)        AS unregistered_cnt,
                count(*)                                           AS total_cnt
            FROM ax.tb_prod_downtime d
            WHERE d.plant_cd = :plantCd
              AND d.stop_at >= :dayStart
              AND d.stop_at <  :dayEnd
        """.trimIndent()

        return jdbcTemplate.queryForObject(sql, dayParams(plantCd, window)) { rs, _ ->
            mapOf(
                "totalMin" to rs.getLong("total_min"),
                "registeredCnt" to rs.getLong("registered_cnt"),
                "unregisteredCnt" to rs.getLong("unregistered_cnt"),
                "totalCnt" to rs.getLong("total_cnt")
            )
        } ?: emptyMap()
    }

    /**
     * 사유별 비가동 시간을 집계한다. (No.68 — byReason)
     */
    fun findSummaryByReason(plantCd: String, date: LocalDate): List<Map<String, Any?>> {
        val sql = """
            SELECT
                coalesce(d.reason_cd, 'UNREGISTERED')                  AS reason_cd,
                coalesce(c.code_nm, '미등록')                           AS reason_nm,
                coalesce(sum(d.elapsed_min), 0)                        AS total_min,
                count(*)                                               AS cnt
            FROM ax.tb_prod_downtime d
            LEFT JOIN ax.tb_sys_code c
                   ON c.group_cd = 'DOWN_REASON' AND c.code = d.reason_cd
            WHERE d.plant_cd = :plantCd
              AND d.stop_at >= :dayStart
              AND d.stop_at <  :dayEnd
            GROUP BY 1, 2
            -- elapsed_min 은 nullable 이므로 coalesce 없이 DESC 하면 NULL 이 먼저 온다.
            ORDER BY coalesce(sum(d.elapsed_min), 0) DESC
        """.trimIndent()

        return jdbcTemplate.query(sql, dayParams(plantCd, date)) { rs, _ ->
            mapOf(
                "reasonCd" to rs.getString("reason_cd"),
                "reasonNm" to rs.getString("reason_nm"),
                "totalMin" to rs.getLong("total_min"),
                "cnt" to rs.getLong("cnt")
            )
        }
    }

    /**
     * 비가동 이력을 조회한다. (No.69)
     *
     * @param eqptCd     설비 코드
     * @param reasonCd   사유 코드
     * @param registered 등록 여부 필터
     */
    fun findDowntimes(
        plantCd: String,
        date: LocalDate,
        eqptCd: String?,
        reasonCd: String?,
        registered: Boolean?,
        limit: Int,
        offset: Int
    ): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT
                d.downtime_id,
                d.eqpt_cd,
                e.eqpt_nm,
                d.wc_cd,
                d.stop_at,
                d.resume_at,
                d.elapsed_min,
                d.is_registered,
                d.reason_cd,
                c.code_nm AS reason_nm,
                d.remark,
                d.detected_by_cd,
                d.registered_at,
                u.user_nm AS registered_by_nm
            FROM ax.tb_prod_downtime d
            LEFT JOIN mes.tb_md_eqpt e
                   ON e.plant_cd = d.plant_cd AND e.eqpt_cd = d.eqpt_cd
            LEFT JOIN ax.tb_sys_code c
                   ON c.group_cd = 'DOWN_REASON' AND c.code = d.reason_cd
            LEFT JOIN ax.tb_sys_user u ON u.user_id = d.registered_by
            WHERE d.plant_cd = :plantCd
              AND d.stop_at >= :dayStart
              AND d.stop_at <  :dayEnd
            """.trimIndent()
        )

        val params = dayParams(plantCd, date)
        appendDowntimeFilters(sql, params, eqptCd, reasonCd, registered)

        sql.append("\nORDER BY d.stop_at DESC\nLIMIT :limit OFFSET :offset")
        params.addValue("limit", limit).addValue("offset", offset)

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "downtimeId" to rs.getLong("downtime_id"),
                "eqptCd" to rs.getString("eqpt_cd"),
                "eqptNm" to rs.getString("eqpt_nm"),
                "processId" to rs.getString("wc_cd"),
                "stopAt" to Rs.dateTime(rs, "stop_at"),
                "resumeAt" to Rs.dateTime(rs, "resume_at"),
                "elapsedMin" to Rs.intOrNull(rs, "elapsed_min"),
                "registered" to rs.getBoolean("is_registered"),
                "reasonCd" to rs.getString("reason_cd"),
                "reasonNm" to rs.getString("reason_nm"),
                "remark" to rs.getString("remark"),
                "detectedBy" to rs.getString("detected_by_cd"),
                "registeredAt" to Rs.dateTime(rs, "registered_at"),
                "registeredBy" to rs.getString("registered_by_nm")
            )
        }
    }

    /** 비가동 이력 전체 건수 */
    fun countDowntimes(
        plantCd: String,
        date: LocalDate,
        eqptCd: String?,
        reasonCd: String?,
        registered: Boolean?
    ): Long {
        val sql = StringBuilder(
            """
            SELECT count(*)
            FROM ax.tb_prod_downtime d
            WHERE d.plant_cd = :plantCd
              AND d.stop_at >= :dayStart
              AND d.stop_at <  :dayEnd
            """.trimIndent()
        )

        val params = dayParams(plantCd, date)
        appendDowntimeFilters(sql, params, eqptCd, reasonCd, registered)

        return jdbcTemplate.queryForObject(sql.toString(), params, Long::class.java) ?: 0L
    }

    /** 비가동 목록/건수 공통 동적 조건 */
    private fun appendDowntimeFilters(
        sql: StringBuilder,
        params: MapSqlParameterSource,
        eqptCd: String?,
        reasonCd: String?,
        registered: Boolean?
    ) {
        if (!eqptCd.isNullOrBlank()) {
            sql.append(" AND d.eqpt_cd = :eqptCd")
            params.addValue("eqptCd", eqptCd.trim())
        }
        if (!reasonCd.isNullOrBlank()) {
            sql.append(" AND d.reason_cd = :reasonCd")
            params.addValue("reasonCd", reasonCd.trim())
        }
        if (registered != null) {
            sql.append(" AND d.is_registered = :registered")
            params.addValue("registered", registered)
        }
    }

    /**
     * Agent 사유 후보를 조회한다. (No.70)
     *
     * 동일 설비의 과거 등록 이력에서 유사 시간대·유사 지속시간 패턴의 사유를 빈도순으로 제안한다.
     *
     * @param eqptCd 설비 코드
     * @param stopAt 정지 시각
     */
    fun findReasonSuggestions(plantCd: String, eqptCd: String, stopAt: LocalDateTime, limit: Int): List<Map<String, Any?>> {
        val sql = """
            WITH history AS (
                SELECT
                    d.reason_cd,
                    count(*)                       AS hit_cnt,
                    round(avg(d.elapsed_min))      AS avg_min
                FROM ax.tb_prod_downtime d
                WHERE d.plant_cd      = :plantCd
                  AND d.eqpt_cd       = :eqptCd
                  AND d.is_registered = true
                  AND d.reason_cd    IS NOT NULL
                  AND d.stop_at      >= :stopAt::timestamptz - interval '90 days'
                  -- 정지 시각의 시간대(±2시간)가 유사한 이력을 우선한다.
                  AND abs(extract(hour FROM d.stop_at) - extract(hour FROM :stopAt::timestamptz)) <= 2
                GROUP BY d.reason_cd
            ),
            total AS (SELECT coalesce(sum(hit_cnt), 0) AS all_cnt FROM history)
            SELECT
                h.reason_cd,
                c.code_nm                                                        AS reason_nm,
                h.hit_cnt,
                h.avg_min,
                CASE WHEN t.all_cnt > 0
                     THEN round(h.hit_cnt::numeric / t.all_cnt, 4)
                     ELSE 0 END                                                  AS confidence
            FROM history h
            CROSS JOIN total t
            LEFT JOIN ax.tb_sys_code c ON c.group_cd = 'DOWN_REASON' AND c.code = h.reason_cd
            ORDER BY h.hit_cnt DESC
            LIMIT :limit
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("plantCd", plantCd)
            .addValue("eqptCd", eqptCd)
            .addValue("stopAt", stopAt)
            .addValue("limit", limit)

        return jdbcTemplate.query(sql, params) { rs, _ ->
            val hitCnt = rs.getLong("hit_cnt")
            val avgMin = Rs.intOrNull(rs, "avg_min")
            mapOf(
                "reasonCd" to rs.getString("reason_cd"),
                "reasonNm" to rs.getString("reason_nm"),
                "confidence" to Rs.rate(rs, "confidence", 4),
                "basis" to "동일 설비 최근 90일 유사 시간대 ${hitCnt}건 · 평균 ${avgMin ?: 0}분"
            )
        }
    }

    /**
     * 비가동 사유를 등록한다. (No.71)
     *
     * 이미 감지된 비가동 구간이 있으면 갱신하고, 없으면 수기 등록으로 신규 생성한다.
     *
     * @return 등록/갱신된 비가동 이력 ID
     */
    fun upsertDowntime(
        plantCd: String,
        eqptCd: String,
        wcCd: String?,
        stopAt: LocalDateTime,
        resumeAt: LocalDateTime?,
        reasonCd: String,
        remark: String?,
        actor: String
    ): Long {
        // 1. 동일 설비·동일 정지 시각(±5분)의 기존 감지 건을 찾는다.
        val findSql = """
            SELECT downtime_id
            FROM ax.tb_prod_downtime
            WHERE plant_cd = :plantCd
              AND eqpt_cd  = :eqptCd
              AND stop_at BETWEEN :stopAt::timestamptz - interval '5 minutes'
                              AND :stopAt::timestamptz + interval '5 minutes'
            ORDER BY abs(extract(epoch FROM (stop_at - :stopAt::timestamptz)))
            LIMIT 1
        """.trimIndent()

        val findParams = MapSqlParameterSource()
            .addValue("plantCd", plantCd)
            .addValue("eqptCd", eqptCd)
            .addValue("stopAt", stopAt)

        val existingId = jdbcTemplate.query(findSql, findParams) { rs, _ -> rs.getLong("downtime_id") }.firstOrNull()

        val params = MapSqlParameterSource()
            .addValue("plantCd", plantCd)
            .addValue("eqptCd", eqptCd)
            .addValue("wcCd", wcCd)
            .addValue("stopAt", stopAt)
            .addValue("resumeAt", resumeAt)
            .addValue("reasonCd", reasonCd)
            .addValue("remark", remark?.take(500))
            .addValue("actor", actor)

        // 2. 기존 건이 있으면 사유를 채워 등록 상태로 전환한다.
        if (existingId != null) {
            val updateSql = """
                UPDATE ax.tb_prod_downtime
                   SET reason_cd     = :reasonCd,
                       remark        = :remark,
                       resume_at     = coalesce(:resumeAt, resume_at),
                       elapsed_min   = CASE
                                         WHEN coalesce(:resumeAt, resume_at) IS NOT NULL
                                         THEN (extract(epoch FROM (coalesce(:resumeAt, resume_at) - stop_at)) / 60)::int
                                         ELSE elapsed_min
                                       END,
                       is_registered = true,
                       registered_at = now(),
                       registered_by = :actor,
                       upd_date      = now(),
                       upd_user      = :actor
                 WHERE downtime_id = :downtimeId
            """.trimIndent()

            jdbcTemplate.update(updateSql, params.addValue("downtimeId", existingId))
            return existingId
        }

        // 3. 감지 건이 없으면 수기 등록으로 신규 생성한다.
        val insertSql = """
            INSERT INTO ax.tb_prod_downtime (
                plant_cd, eqpt_cd, wc_cd, stop_at, resume_at, elapsed_min,
                reason_cd, remark, is_registered, detected_by_cd,
                registered_at, registered_by, ins_user, upd_user
            ) VALUES (
                :plantCd, :eqptCd, :wcCd, :stopAt, :resumeAt,
                CASE WHEN :resumeAt::timestamptz IS NOT NULL
                     THEN (extract(epoch FROM (:resumeAt::timestamptz - :stopAt::timestamptz)) / 60)::int
                     ELSE NULL END,
                :reasonCd, :remark, true, 'MANUAL',
                now(), :actor, :actor, :actor
            )
            RETURNING downtime_id
        """.trimIndent()

        return jdbcTemplate.queryForObject(insertSql, params, Long::class.java) ?: 0L
    }

    /**
     * 비가동 사유를 수정한다. (No.72)
     */
    fun updateDowntime(
        downtimeId: Long,
        reasonCd: String?,
        remark: String?,
        resumeAt: LocalDateTime?,
        actor: String
    ): Int {
        val sql = """
            UPDATE ax.tb_prod_downtime
               SET reason_cd   = coalesce(:reasonCd, reason_cd),
                   remark      = coalesce(:remark, remark),
                   resume_at   = coalesce(:resumeAt, resume_at),
                   elapsed_min = CASE
                                   WHEN coalesce(:resumeAt, resume_at) IS NOT NULL
                                   THEN (extract(epoch FROM (coalesce(:resumeAt, resume_at) - stop_at)) / 60)::int
                                   ELSE elapsed_min
                                 END,
                   is_registered = true,
                   upd_date    = now(),
                   upd_user    = :actor
             WHERE downtime_id = :downtimeId
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("downtimeId", downtimeId)
            .addValue("reasonCd", reasonCd)
            .addValue("remark", remark?.take(500))
            .addValue("resumeAt", resumeAt)
            .addValue("actor", actor)

        return jdbcTemplate.update(sql, params)
    }

    /** 비가동 이력 단건 조회 */
    fun findDowntime(downtimeId: Long): Map<String, Any?>? {
        val sql = """
            SELECT downtime_id, plant_cd, eqpt_cd, wc_cd, stop_at, resume_at,
                   elapsed_min, reason_cd, remark, is_registered
            FROM ax.tb_prod_downtime
            WHERE downtime_id = :downtimeId
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource("downtimeId", downtimeId)) { rs, _ ->
            mapOf(
                "downtimeId" to rs.getLong("downtime_id"),
                "plantCd" to rs.getString("plant_cd"),
                "eqptCd" to rs.getString("eqpt_cd"),
                "processId" to rs.getString("wc_cd"),
                "stopAt" to Rs.dateTime(rs, "stop_at"),
                "resumeAt" to Rs.dateTime(rs, "resume_at"),
                "elapsedMin" to Rs.intOrNull(rs, "elapsed_min"),
                "reasonCd" to rs.getString("reason_cd"),
                "remark" to rs.getString("remark"),
                "registered" to rs.getBoolean("is_registered")
            )
        }.firstOrNull()
    }

    /** 설비가 속한 작업장 코드를 조회한다. */
    fun findWorkcenterOfEquipment(plantCd: String, eqptCd: String): String? {
        val sql = """
            SELECT wc_cd
            FROM mes.tb_md_eqpt_by_workcenter
            WHERE plant_cd = :plantCd AND eqpt_cd = :eqptCd
            ORDER BY wc_cd
            LIMIT 1
        """.trimIndent()

        val params = MapSqlParameterSource().addValue("plantCd", plantCd).addValue("eqptCd", eqptCd)
        return jdbcTemplate.query(sql, params) { rs, _ -> rs.getString("wc_cd") }.firstOrNull()
    }

    /** 일자 범위 공통 파라미터 */
    private fun dayParams(plantCd: String, date: LocalDate): MapSqlParameterSource =
        dayParams(plantCd, TimeWindow.ofDay(date))

    /** 집계 구간 파라미터 — 자정을 넘는 구간(일일 생산현황 보고)도 담을 수 있다. */
    private fun dayParams(plantCd: String, window: TimeWindow): MapSqlParameterSource =
        MapSqlParameterSource()
            .addValue("plantCd", plantCd)
            .addValue("dayStart", window.from)
            .addValue("dayEnd", window.toExclusive)
}
