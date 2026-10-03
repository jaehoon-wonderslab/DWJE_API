package com.dwje.api.repository

import com.dwje.api.common.util.Rs
import com.dwje.api.service.TargetGroupRow
import com.dwje.api.service.TargetMemberRow
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal

/**
 * 이상 알림 발송 조건 · 수신자 관리 Repository (SY-04, SY-05)
 *
 * 참조 테이블 : ax.tb_alm_cond, ax.tb_alm_cond_channel, ax.tb_alm_cond_group,
 *              ax.tb_alm_recip_group, ax.tb_alm_recip_group_channel,
 *              ax.tb_alm_recip_group_member, ax.tb_alm_recipient
 */
/**
 * 지표 수집 중단 판정 (05 ALC-08) — 엔진 ConditionEvaluator 와 같다: 최근 측정값이 max(평가 주기 × 배수, 600초) 보다 오래됐거나 없음.
 * mv 는 LATERAL 로 붙는 최근 측정 시각·수집 정의 사용 여부다. `:staleFactor` 는 엔진 stale-factor 와 같은 값.
 * 평가 주기는 고급 설정을 없앤 뒤(2026-10-03) 엔진 틱 60초로 고정이다 — 컬럼(eval_interval_sec)을 읽지 않는다.
 */
private const val STALE_SQL = "(c.metric_id IS NULL OR mv.last_value_at IS NULL OR " +
    "now() - mv.last_value_at > make_interval(secs => greatest(60 * :staleFactor, 600)))"

@Repository
class AlertConfigRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) {

    // =================================================================================
    // SY-04. 발송 조건
    // =================================================================================

    /**
     * 발송 조건 요약을 조회한다. (No.151)
     */
    fun findConditionSummary(): Map<String, Any?> {
        val sql = """
            SELECT
                count(*) FILTER (WHERE c.use_flg = 'Y')  AS active_cnt,
                count(*)                                  AS total_cnt
            FROM ax.tb_alm_cond c
        """.trimIndent()

        return jdbcTemplate.queryForObject(sql, MapSqlParameterSource()) { rs, _ ->
            mapOf("activeCnt" to rs.getLong("active_cnt"), "totalCnt" to rs.getLong("total_cnt"))
        } ?: emptyMap()
    }

    /**
     * 당일 발송 집계 (No.151, 05 ALC-07) — 테스트 알림 제외. 발송 수는 실제로 나간 SENT 만 센다
     * (예전에는 억제·건너뜀까지 「발송」 으로 셌다). 평균 지연은 1차 발송(승격 아님) SENT 의 평균이다.
     */
    fun findTodaySendStats(): Map<String, Any?> {
        val byChannelSql = """
            SELECT s.channel_cd, count(*) AS sent_cnt
              FROM ax.tb_alm_send_log s
              JOIN ax.tb_alm_alert a ON a.alert_id = s.alert_id
             WHERE s.sent_at >= date_trunc('day', now()) AND a.test_flg = 'N' AND s.send_result_cd = 'SENT'
             GROUP BY s.channel_cd
        """.trimIndent()
        val byChannel = jdbcTemplate.query(byChannelSql, MapSqlParameterSource()) { rs, _ ->
            rs.getString("channel_cd") to rs.getLong("sent_cnt")
        }.toMap()
        val totalsSql = """
            SELECT count(*) FILTER (WHERE s.send_result_cd = 'SENT')       AS sent_cnt,
                   count(*) FILTER (WHERE s.send_result_cd = 'SUPPRESSED') AS suppressed_cnt,
                   count(*) FILTER (WHERE s.send_result_cd = 'SKIPPED')    AS skipped_cnt,
                   count(*) FILTER (WHERE s.send_result_cd = 'FAIL')       AS fail_cnt,
                   avg(extract(epoch FROM (s.sent_at - a.occurred_at)))
                       FILTER (WHERE s.send_result_cd = 'SENT' AND coalesce(s.esc_level, 0) = 0) AS avg_delay_sec
              FROM ax.tb_alm_send_log s
              JOIN ax.tb_alm_alert a ON a.alert_id = s.alert_id
             WHERE s.sent_at >= date_trunc('day', now()) AND a.test_flg = 'N'
        """.trimIndent()
        return jdbcTemplate.queryForObject(totalsSql, MapSqlParameterSource()) { rs, _ ->
            val suppressed = rs.getLong("suppressed_cnt")
            mapOf(
                "byChannel" to byChannel,
                "todaySentCnt" to rs.getLong("sent_cnt"),
                "todaySuppressedCnt" to suppressed,
                "todaySkippedCnt" to rs.getLong("skipped_cnt"),
                "todayFailCnt" to rs.getLong("fail_cnt"),
                // 예전 키 — todaySuppressedCnt 와 같은 값으로 한 릴리스 함께 보낸다
                "dedupCnt" to suppressed,
                "avgDelaySec" to Rs.doubleOrNull(rs, "avg_delay_sec")?.let { Math.round(it * 10) / 10.0 }
            )
        } ?: emptyMap()
    }

    /** 알림 엔진 상태 재료 — 최근 평가 실행과 대기열 (05 ALC-07) */
    fun findEngineState(): Map<String, Any?> {
        val run = jdbcTemplate.query(
            "SELECT started_at, state_cd FROM ax.tb_alm_eval_run ORDER BY started_at DESC LIMIT 1", MapSqlParameterSource()
        ) { rs, _ -> rs.getObject("started_at", java.time.OffsetDateTime::class.java) to rs.getString("state_cd") }.firstOrNull()
        val queue = jdbcTemplate.queryForObject(
            """
            SELECT count(*) FILTER (WHERE state_cd IN ('PENDING', 'SENDING')) AS pending_cnt,
                   count(*) FILTER (WHERE state_cd = 'DEAD')                  AS dead_cnt
              FROM ax.tb_alm_send_queue
            """.trimIndent(), MapSqlParameterSource()
        ) { rs, _ -> rs.getLong("pending_cnt") to rs.getLong("dead_cnt") } ?: (0L to 0L)
        return mapOf("lastRunAt" to run?.first, "lastState" to run?.second, "pendingQueueCnt" to queue.first, "deadQueueCnt" to queue.second)
    }

    /** 판정 이상 조건 수 — 위반 중(BREACH 범위가 하나라도 있는 활성 조건)·수집 중단 (05 ALC-07) */
    fun findEvalIssueCnt(staleFactor: Int): Map<String, Any?> =
        jdbcTemplate.queryForObject(
            """
            SELECT count(*) FILTER (WHERE EXISTS (SELECT 1 FROM ax.tb_alm_cond_state st
                                                   WHERE st.cond_id = c.cond_id AND st.state_cd = 'BREACH')) AS breach,
                   count(*) FILTER (WHERE $STALE_SQL) AS stale
              FROM ax.tb_alm_cond c
              LEFT JOIN LATERAL (
            SELECT EXISTS (SELECT 1 FROM ax.tb_met_metric_collect mc WHERE mc.metric_id = c.metric_id AND mc.use_flg = 'Y') AS collecting,
                   (SELECT max(v.measured_at) FROM ax.tb_met_metric_value v WHERE v.metric_id = c.metric_id) AS last_value_at
        ) mv ON true
             WHERE c.use_flg = 'Y'
            """.trimIndent(), MapSqlParameterSource("staleFactor", staleFactor)
        ) { rs, _ -> mapOf("breach" to rs.getLong("breach"), "stale" to rs.getLong("stale")) } ?: emptyMap()

    /**
     * 발송 조건 목록을 조회한다. (No.152)
     */
    fun findConditions(
        severity: String?,
        channel: String?,
        state: String?,
        limit: Int,
        offset: Int,
        keyword: String? = null,
        groupId: Int? = null,
        staleFactor: Int = 3
    ): List<Map<String, Any?>> {
        val sql = StringBuilder(baseConditionSql())
        val params = MapSqlParameterSource("staleFactor", staleFactor)
        appendConditionFilters(sql, params, severity, channel, state, keyword, groupId)

        // 심각도 순(위험 → 주의 → 낮음) → 조건명 (05 ALC-11)
        sql.append("\nORDER BY CASE c.severity_cd WHEN 'CRIT' THEN 1 WHEN 'WARN' THEN 2 ELSE 3 END, c.cond_nm, c.cond_id")
        sql.append("\nLIMIT :limit OFFSET :offset")
        params.addValue("limit", limit).addValue("offset", offset)

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf<String, Any?>(
                "condId" to rs.getInt("cond_id"),
                "on" to Rs.yn(rs, "use_flg"),
                "name" to rs.getString("cond_nm"),
                "metricId" to Rs.intOrNull(rs, "metric_id"),
                "metric" to (rs.getString("metric_nm") ?: rs.getString("metric_desc")),
                "op" to rs.getString("op_cd"),
                "threshold" to rs.getString("threshold_text"),
                "thresholdVal" to Rs.rate(rs, "threshold_val", 4),
                "thresholdUnit" to rs.getString("threshold_unit"),
                "duration" to rs.getString("duration_cd"),
                "targetScope" to rs.getString("target_scope_cd"),
                "target" to rs.getString("target_desc"),
                "severity" to rs.getString("severity_cd"),
                "validWindow" to rs.getString("window_cd"),
                "dedupMin" to rs.getString("dedup_cd"),
                "blindFieldKey" to rs.getString("blind_field_key"),
                "channels" to (rs.getString("channels")?.split(",") ?: emptyList()),
                "groupNames" to (rs.getString("group_names")?.split(",") ?: emptyList()),
                "groupIds" to (rs.getString("group_ids")?.split(",")?.map { it.toInt() } ?: emptyList<Int>()),
                "pickCnt" to rs.getInt("pick_cnt"),
                "updatedAt" to Rs.dateTime(rs, "upd_date"),
                // 판정 현황 · 지표 수집 · 최근 발생 (05 ALC-08) — 엔진 ConditionEvaluator 와 같은 수집 중단 기준
                "evalState" to mapOf<String, Any?>(
                    "breach" to rs.getLong("st_breach"), "pending" to rs.getLong("st_pending"),
                    "normal" to rs.getLong("st_normal"), "lastEvalAt" to Rs.dateTime(rs, "st_last_eval_at")
                ),
                "metricStale" to rs.getBoolean("metric_stale"),
                "collecting" to rs.getBoolean("collecting"),
                "lastAlertAt" to Rs.dateTime(rs, "last_alert_at"),
                "alert7dCnt" to rs.getLong("alert7d_cnt"),
                "realAlertCnt" to rs.getLong("real_alert_cnt")
            )
        }
    }

    /** 발송 조건 전체 건수 */
    fun countConditions(severity: String?, channel: String?, state: String?, keyword: String? = null, groupId: Int? = null): Long {
        val sql = StringBuilder(
            """
            SELECT count(*)
            FROM ax.tb_alm_cond c
            LEFT JOIN ax.tb_met_metric_std ms ON ms.metric_id = c.metric_id
            WHERE 1 = 1
            """.trimIndent()
        )
        val params = MapSqlParameterSource()
        appendConditionFilters(sql, params, severity, channel, state, keyword, groupId)
        return jdbcTemplate.queryForObject(sql.toString(), params, Long::class.java) ?: 0L
    }

    /** 발송 조건 단건 조회 */
    fun findCondition(condId: Int): Map<String, Any?>? {
        val sql = "${baseConditionSql()}\n  AND c.cond_id = :condId"
        return jdbcTemplate.query(sql, MapSqlParameterSource("condId", condId).addValue("staleFactor", 3)) { rs, _ ->
            mapOf(
                "condId" to rs.getInt("cond_id"),
                "name" to rs.getString("cond_nm"),
                "severity" to rs.getString("severity_cd"),
                "metricId" to Rs.intOrNull(rs, "metric_id"),
                "target" to rs.getString("target_desc"),
                "on" to Rs.yn(rs, "use_flg"),
                "channels" to (rs.getString("channels")?.split(",") ?: emptyList()),
                "groups" to (rs.getString("group_names")?.split(",") ?: emptyList())
            )
        }.firstOrNull()
    }

    /** 발송 조건 기본 SQL (채널·수신 그룹을 문자열로 집계) */
    private fun baseConditionSql(): String = """
        SELECT
            c.cond_id, c.cond_nm, c.severity_cd, c.metric_id, ms.metric_nm, c.metric_desc,
            c.op_cd, c.threshold_val, c.threshold_text, c.threshold_unit,
            c.duration_cd, c.target_scope_cd, c.target_desc, c.window_cd, c.dedup_cd,
            c.blind_field_key, c.use_flg, c.upd_date,
            (
                SELECT string_agg(cc.channel_cd, ',' ORDER BY cc.channel_cd)
                  FROM ax.tb_alm_cond_channel cc WHERE cc.cond_id = c.cond_id
            ) AS channels,
            (
                SELECT string_agg(g.group_nm, ',' ORDER BY g.group_nm)
                  FROM ax.tb_alm_cond_group cg
                 INNER JOIN ax.tb_alm_recip_group g ON g.group_id = cg.group_id
                 WHERE cg.cond_id = c.cond_id
            ) AS group_names,
            (
                SELECT string_agg(cg.group_id::text, ',' ORDER BY g.group_nm)
                  FROM ax.tb_alm_cond_group cg
                 INNER JOIN ax.tb_alm_recip_group g ON g.group_id = cg.group_id
                 WHERE cg.cond_id = c.cond_id
            ) AS group_ids,
            (SELECT count(*) FROM ax.tb_alm_cond_target ct WHERE ct.cond_id = c.cond_id) AS pick_cnt,
            es.breach AS st_breach, es.pending AS st_pending, es.normal AS st_normal, es.last_eval_at AS st_last_eval_at,
            al.last_alert_at, al.alert7d AS alert7d_cnt, al.real_cnt AS real_alert_cnt, mv.collecting,
            $STALE_SQL AS metric_stale
        FROM ax.tb_alm_cond c
        LEFT JOIN ax.tb_met_metric_std ms ON ms.metric_id = c.metric_id
        LEFT JOIN LATERAL (
            SELECT count(*) FILTER (WHERE st.state_cd = 'BREACH')  AS breach,
                   count(*) FILTER (WHERE st.state_cd = 'PENDING') AS pending,
                   count(*) FILTER (WHERE st.state_cd = 'NORMAL')  AS normal,
                   max(st.last_eval_at)                            AS last_eval_at
              FROM ax.tb_alm_cond_state st WHERE st.cond_id = c.cond_id
        ) es ON true
        LEFT JOIN LATERAL (
            SELECT max(a.occurred_at) AS last_alert_at,
                   count(*) FILTER (WHERE a.occurred_at >= now() - interval '7 days') AS alert7d,
                   count(*) AS real_cnt
              FROM ax.tb_alm_alert a WHERE a.cond_id = c.cond_id AND a.test_flg = 'N'
        ) al ON true
        LEFT JOIN LATERAL (
            SELECT EXISTS (SELECT 1 FROM ax.tb_met_metric_collect mc WHERE mc.metric_id = c.metric_id AND mc.use_flg = 'Y') AS collecting,
                   (SELECT max(v.measured_at) FROM ax.tb_met_metric_value v WHERE v.metric_id = c.metric_id) AS last_value_at
        ) mv ON true
        WHERE 1 = 1
    """.trimIndent()

    /** 발송 조건 목록/건수 공통 동적 조건 */
    private fun appendConditionFilters(
        sql: StringBuilder,
        params: MapSqlParameterSource,
        severity: String?,
        channel: String?,
        state: String?,
        keyword: String? = null,
        groupId: Int? = null
    ) {
        // 조건명·지표명·지표 설명 부분 일치 (05 ALC-11)
        com.dwje.api.common.util.SqlLikeUtils.contains(keyword)?.let {
            sql.append(" AND (c.cond_nm ILIKE :kw ESCAPE '\\' OR coalesce(ms.metric_nm, '') ILIKE :kw ESCAPE '\\' OR c.metric_desc ILIKE :kw ESCAPE '\\')")
            params.addValue("kw", it)
        }
        if (groupId != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM ax.tb_alm_cond_group cgf WHERE cgf.cond_id = c.cond_id AND cgf.group_id = :groupIdF)")
            params.addValue("groupIdF", groupId)
        }
        if (!severity.isNullOrBlank()) {
            sql.append(" AND c.severity_cd = :severity")
            params.addValue("severity", severity.trim().uppercase())
        }
        if (!channel.isNullOrBlank()) {
            sql.append(
                """

                AND EXISTS (
                    SELECT 1 FROM ax.tb_alm_cond_channel cc
                     WHERE cc.cond_id = c.cond_id AND cc.channel_cd = :channel
                )
                """.trimIndent()
            )
            params.addValue("channel", channel.trim().uppercase())
        }
        if (!state.isNullOrBlank()) {
            sql.append(" AND c.use_flg = :state")
            params.addValue("state", if (state.equals("on", true) || state == "Y") "Y" else "N")
        }
    }

    /**
     * 발송 조건을 등록한다. (No.153) — 값은 서비스가 검증을 마친 것이다(길이도 검사하므로 여기서 자르지 않는다).
     *
     * @return 생성된 조건 ID
     */
    fun insertCondition(v: AlertCondValues, actor: String): Int {
        val sql = """
            INSERT INTO ax.tb_alm_cond (
                cond_nm, severity_cd, metric_id, metric_desc, op_cd,
                threshold_val, threshold_text, threshold_unit, duration_cd,
                target_scope_cd, target_desc, window_cd, dedup_cd, blind_field_key,
                use_flg, ins_user, upd_user
            ) VALUES (
                :condNm, :severityCd, :metricId, :metricDesc, :opCd,
                :thresholdVal, :thresholdText, :thresholdUnit, :durationCd,
                :targetScopeCd, :targetDesc, :windowCd, :dedupCd, :blindFieldKey,
                'Y', :actor, :actor
            )
            RETURNING cond_id
        """.trimIndent()
        return jdbcTemplate.queryForObject(sql, conditionParams(v, actor), Int::class.java) ?: 0
    }

    /** 발송 조건을 수정한다. (No.154) — 병합된 전체 값으로 */
    fun updateCondition(condId: Int, v: AlertCondValues, actor: String): Int {
        val sql = """
            UPDATE ax.tb_alm_cond
               SET cond_nm           = :condNm,
                   severity_cd       = :severityCd,
                   metric_id         = :metricId,
                   metric_desc       = :metricDesc,
                   op_cd             = :opCd,
                   threshold_val     = :thresholdVal,
                   threshold_text    = :thresholdText,
                   threshold_unit    = :thresholdUnit,
                   duration_cd       = :durationCd,
                   target_scope_cd   = :targetScopeCd,
                   target_desc       = :targetDesc,
                   window_cd         = :windowCd,
                   dedup_cd          = :dedupCd,
                   blind_field_key   = :blindFieldKey,
                   upd_date          = now(),
                   upd_user          = :actor
             WHERE cond_id = :condId
        """.trimIndent()
        return jdbcTemplate.update(sql, conditionParams(v, actor).addValue("condId", condId))
    }

    /** 발송 조건 등록/수정 공통 파라미터 */
    private fun conditionParams(v: AlertCondValues, actor: String): MapSqlParameterSource = MapSqlParameterSource()
        .addValue("condNm", v.name)
        .addValue("severityCd", v.severity)
        .addValue("metricId", v.metricId)
        .addValue("metricDesc", v.metricDesc)
        .addValue("opCd", v.op)
        .addValue("thresholdVal", v.thresholdVal)
        .addValue("thresholdText", v.thresholdText)
        .addValue("thresholdUnit", v.thresholdUnit)
        .addValue("durationCd", v.duration)
        .addValue("targetScopeCd", v.targetScope)
        .addValue("targetDesc", v.targetDesc)
        .addValue("windowCd", v.validWindow)
        .addValue("dedupCd", v.dedupMin)
        .addValue("blindFieldKey", v.blindFieldKey)
        .addValue("actor", actor)

    /** 지표 존재 여부와 그 지표의 민감 항목 key — 없으면 null (05 ALC-09·12) */
    fun findMetricBlindKey(metricId: Int): Pair<Boolean, String?>? =
        jdbcTemplate.query(
            "SELECT blind_field_key FROM ax.tb_met_metric_std WHERE metric_id = :id",
            MapSqlParameterSource("id", metricId)
        ) { rs, _ -> true to rs.getString("blind_field_key") }.firstOrNull()

    /** 수신 그룹의 이름·사용 여부 (05 ALC-06 — 저장 전 확인) */
    fun findGroupsUse(groupIds: Collection<Int>): Map<Int, Pair<String, String>> {
        if (groupIds.isEmpty()) return emptyMap()
        return jdbcTemplate.query(
            "SELECT group_id, group_nm, use_flg FROM ax.tb_alm_recip_group WHERE group_id IN (:ids)",
            MapSqlParameterSource("ids", groupIds)
        ) { rs, _ -> rs.getInt("group_id") to (rs.getString("group_nm") to rs.getString("use_flg")) }.toMap()
    }

    /** 발송 조건 채널을 교체한다. */
    fun replaceConditionChannels(condId: Int, channels: List<String>) {
        jdbcTemplate.update(
            "DELETE FROM ax.tb_alm_cond_channel WHERE cond_id = :condId",
            MapSqlParameterSource("condId", condId)
        )
        if (channels.isEmpty()) return

        val sql = """
            INSERT INTO ax.tb_alm_cond_channel (cond_id, channel_cd)
            VALUES (:condId, :channelCd)
            ON CONFLICT (cond_id, channel_cd) DO NOTHING
        """.trimIndent()

        val batch = channels.map {
            MapSqlParameterSource().addValue("condId", condId).addValue("channelCd", it.uppercase())
        }.toTypedArray()

        jdbcTemplate.batchUpdate(sql, batch)
    }

    /** 발송 조건 수신 그룹을 교체한다. */
    fun replaceConditionGroups(condId: Int, groupIds: List<Int>) {
        jdbcTemplate.update(
            "DELETE FROM ax.tb_alm_cond_group WHERE cond_id = :condId",
            MapSqlParameterSource("condId", condId)
        )
        if (groupIds.isEmpty()) return

        val sql = """
            INSERT INTO ax.tb_alm_cond_group (cond_id, group_id)
            VALUES (:condId, :groupId)
            ON CONFLICT (cond_id, group_id) DO NOTHING
        """.trimIndent()

        val batch = groupIds.map {
            MapSqlParameterSource().addValue("condId", condId).addValue("groupId", it)
        }.toTypedArray()

        jdbcTemplate.batchUpdate(sql, batch)
    }

    /**
     * 발송 조건 활성/중지 상태를 변경한다. (No.155)
     */
    fun updateConditionState(condId: Int, on: Boolean, actor: String): Int {
        val sql = """
            UPDATE ax.tb_alm_cond
               SET use_flg  = :useFlg,
                   upd_date = now(),
                   upd_user = :actor
             WHERE cond_id = :condId
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("condId", condId)
            .addValue("useFlg", if (on) "Y" else "N")
            .addValue("actor", actor)

        return jdbcTemplate.update(sql, params)
    }

    /**
     * 발송 조건을 삭제한다.
     *
     * 채널·수신그룹·에스컬레이션은 이 조건에만 딸린 값이라 함께 지운다.
     * 이미 발생한 알림(`tb_alm_alert`)은 지우지 않는다 — 지난 사실이므로 남겨야 한다.
     * 그래서 알림이 하나라도 걸려 있으면 호출부가 먼저 막는다.
     *
     * @return 삭제된 조건 행 수
     */
    fun deleteCondition(condId: Int): Int {
        val params = MapSqlParameterSource("condId", condId)
        // 조건별 승격 표(tb_alm_cond_escalation)는 V73 에서 지운다. 그 전(V72)에 옛 화면이 남긴 행이 있으면 FK 로 삭제가 막히므로
        // 표가 있을 때만 그 조건의 행을 함께 지운다 — 읽지는 않는다(2026-10-03 고급 설정 제거)
        val escalationTable = jdbcTemplate.queryForObject(
            "SELECT to_regclass('ax.tb_alm_cond_escalation') IS NOT NULL", MapSqlParameterSource(), Boolean::class.java
        ) == true
        (listOf("tb_alm_cond_channel", "tb_alm_cond_group") + if (escalationTable) listOf("tb_alm_cond_escalation") else emptyList()).forEach {
            jdbcTemplate.update("DELETE FROM ax.$it WHERE cond_id = :condId", params)
        }
        return jdbcTemplate.update("DELETE FROM ax.tb_alm_cond WHERE cond_id = :condId", params)
    }

    /** 이 조건으로 발생한 운영 알림 건수 — 테스트 알림(test_flg='Y')은 세지 않는다. (삭제 가능 여부 판정용) */
    fun countAlertsByCondition(condId: Int): Long =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ax.tb_alm_alert WHERE cond_id = :condId AND test_flg = 'N'",
            MapSqlParameterSource("condId", condId),
            Long::class.java
        ) ?: 0L

    /**
     * 조건 삭제 전에 그 조건의 테스트 알림에서 조건 연결을 끊는다.
     *
     * `tb_alm_alert.cond_id` FK 는 CASCADE 가 아니라 테스트 알림이 남아 있으면 삭제가 막힌다.
     * 테스트 알림은 지난 근거가 아니므로 행은 남기고 연결만 끊는다.
     */
    fun detachTestAlerts(condId: Int): Int =
        jdbcTemplate.update(
            "UPDATE ax.tb_alm_alert SET cond_id = NULL WHERE cond_id = :condId AND test_flg = 'Y'",
            MapSqlParameterSource("condId", condId)
        )

    /** 조건명 중복 여부 확인 */
    fun existsConditionName(condNm: String, excludeCondId: Int?): Boolean {
        val sql = """
            SELECT count(*)
            FROM ax.tb_alm_cond
            WHERE cond_nm = :condNm
              AND (:excludeCondId::int IS NULL OR cond_id <> :excludeCondId)
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("condNm", condNm)
            .addValue("excludeCondId", excludeCondId)

        return (jdbcTemplate.queryForObject(sql, params, Long::class.java) ?: 0L) > 0
    }

    /** 조건에 연결된 수신 그룹 ID 목록 */
    fun findConditionGroupIds(condId: Int): List<Int> {
        val sql = "SELECT group_id FROM ax.tb_alm_cond_group WHERE cond_id = :condId"
        return jdbcTemplate.query(sql, MapSqlParameterSource("condId", condId)) { rs, _ -> rs.getInt("group_id") }
    }

    /** 조건에 연결된 채널 목록 */
    fun findConditionChannels(condId: Int): List<String> {
        val sql = "SELECT channel_cd FROM ax.tb_alm_cond_channel WHERE cond_id = :condId ORDER BY channel_cd"
        return jdbcTemplate.query(sql, MapSqlParameterSource("condId", condId)) { rs, _ -> rs.getString("channel_cd") }
    }

    // =================================================================================
    // SY-05. 수신자 관리
    // =================================================================================

    /**
     * 수신자 관리 요약을 조회한다. (No.157)
     */
    fun findRecipientSummary(receivableStates: List<String>): Map<String, Any?> {
        val sql = """
            SELECT
                (SELECT count(*) FROM ax.tb_alm_recip_group WHERE use_flg = 'Y')          AS group_cnt,
                (SELECT count(*) FROM ax.tb_alm_recipient r
                   JOIN ax.tb_sys_user u ON u.user_id = r.user_id
                  WHERE u.user_state_cd <> ALL(:states))                                  AS inactive_cnt,
                (SELECT count(*) FROM ax.tb_alm_recipient WHERE recv_state_cd = 'RECV')   AS receiving_cnt,
                (SELECT count(*) FROM ax.tb_alm_recipient WHERE recv_state_cd = 'ABSENT') AS absent_cnt,
                (SELECT count(*) FROM ax.tb_alm_recipient WHERE night_recv)               AS night_personal_cnt,
                -- 야간에 실제로 받는 사람 — 개인 또는 소속 그룹(사용 중) 야간 수신, 수신 상태 · 수신 가능 계정 (06 RCP-10)
                (SELECT count(DISTINCT r.user_id) FROM ax.tb_alm_recipient r
                   JOIN ax.tb_sys_user u ON u.user_id = r.user_id
                  WHERE r.recv_state_cd = 'RECV' AND u.user_state_cd = ANY(:states)
                    AND (r.night_recv OR EXISTS (
                         SELECT 1 FROM ax.tb_alm_recip_group_member m
                           JOIN ax.tb_alm_recip_group g ON g.group_id = m.group_id AND g.use_flg = 'Y' AND g.night_recv
                          WHERE m.user_id = r.user_id)))                                  AS night_cnt
        """.trimIndent()

        val params = MapSqlParameterSource("states", receivableStates.toTypedArray())
        return jdbcTemplate.queryForObject(sql, params) { rs, _ ->
            mapOf(
                "groupCnt" to rs.getLong("group_cnt"),
                "inactiveAccountCnt" to rs.getLong("inactive_cnt"),
                "recipientCnt" to mapOf(
                    "receiving" to rs.getLong("receiving_cnt"),
                    "absent" to rs.getLong("absent_cnt")
                ),
                "nightCnt" to rs.getLong("night_cnt"),
                // 예전 의미(개인 야간 수신 설정 수) — 한 릴리스 함께 보낸다
                "nightPersonalCnt" to rs.getLong("night_personal_cnt")
            )
        } ?: emptyMap()
    }

    /**
     * 수신 그룹 목록을 조회한다. (No.158)
     *
     * 멤버는 [findTargetMembers] 로 따로 읽어 서비스가 붙인다 — 이름·사번을 한 순서로 맞추고
     * 수신 가능 인원을 [com.dwje.api.service.AlertTargetResolver] 와 같은 규칙으로 세기 위함이다.
     *
     * @param groupId 지정하면 그 그룹 한 건(사용 중지 그룹 포함) — 그룹 상세용
     */
    fun findRecipientGroups(groupId: Int? = null, includeInactive: Boolean = false): List<Map<String, Any?>> {
        val sql = """
            SELECT
                g.group_id, g.group_nm, g.window_cd, g.night_recv, g.dept_id, d.dept_nm, g.use_flg, g.upd_date,
                (
                    SELECT string_agg(gc.channel_cd, ',' ORDER BY gc.channel_cd)
                      FROM ax.tb_alm_recip_group_channel gc WHERE gc.group_id = g.group_id
                ) AS channels
            FROM ax.tb_alm_recip_group g
            LEFT JOIN ax.tb_sys_dept d ON d.dept_id = g.dept_id
            WHERE (:groupId::int IS NULL AND (g.use_flg = 'Y' OR :inactive)) OR g.group_id = :groupId
            ORDER BY g.use_flg DESC, g.group_nm
        """.trimIndent()

        val params = MapSqlParameterSource("groupId", groupId).addValue("inactive", includeInactive)
        return jdbcTemplate.query(sql, params) { rs, _ ->
            mapOf(
                "groupId" to rs.getInt("group_id"),
                "name" to rs.getString("group_nm"),
                "validWindow" to rs.getString("window_cd"),
                "night" to rs.getBoolean("night_recv"),
                "deptId" to Rs.intOrNull(rs, "dept_id"),
                "dept" to rs.getString("dept_nm"),
                "useFlg" to rs.getString("use_flg"),
                "channels" to (rs.getString("channels")?.split(",") ?: emptyList()),
                "updatedAt" to Rs.dateTime(rs, "upd_date")
            )
        }
    }

    /**
     * 수신 그룹을 등록한다. (No.159)
     *
     * @return 생성된 그룹 ID
     */
    fun insertRecipientGroup(
        groupNm: String,
        windowCd: String,
        night: Boolean,
        deptId: Int?,
        actor: String
    ): Int {
        val sql = """
            INSERT INTO ax.tb_alm_recip_group (group_nm, window_cd, night_recv, dept_id, use_flg, ins_user, upd_user)
            VALUES (:groupNm, :windowCd, :night, :deptId, 'Y', :actor, :actor)
            RETURNING group_id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("groupNm", groupNm.take(50))
            .addValue("windowCd", windowCd)
            .addValue("night", night)
            .addValue("deptId", deptId)
            .addValue("actor", actor)

        return jdbcTemplate.queryForObject(sql, params, Int::class.java) ?: 0
    }

    /**
     * 수신 그룹을 수정한다. (No.160)
     */
    fun updateRecipientGroup(
        groupId: Int,
        groupNm: String,
        windowCd: String,
        night: Boolean,
        deptId: Int?,
        actor: String
    ): Int {
        val sql = """
            UPDATE ax.tb_alm_recip_group
               SET group_nm   = :groupNm,
                   window_cd  = :windowCd,
                   night_recv = :night,
                   dept_id    = :deptId,
                   upd_date   = now(),
                   upd_user   = :actor
             WHERE group_id = :groupId
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("groupId", groupId)
            .addValue("groupNm", groupNm.take(50))
            .addValue("windowCd", windowCd)
            .addValue("night", night)
            .addValue("deptId", deptId)
            .addValue("actor", actor)

        return jdbcTemplate.update(sql, params)
    }

    /** 수신 그룹 채널을 교체한다. */
    fun replaceGroupChannels(groupId: Int, channels: List<String>) {
        jdbcTemplate.update(
            "DELETE FROM ax.tb_alm_recip_group_channel WHERE group_id = :groupId",
            MapSqlParameterSource("groupId", groupId)
        )
        if (channels.isEmpty()) return

        val sql = """
            INSERT INTO ax.tb_alm_recip_group_channel (group_id, channel_cd)
            VALUES (:groupId, :channelCd)
            ON CONFLICT (group_id, channel_cd) DO NOTHING
        """.trimIndent()

        val batch = channels.map {
            MapSqlParameterSource().addValue("groupId", groupId).addValue("channelCd", it.uppercase())
        }.toTypedArray()

        jdbcTemplate.batchUpdate(sql, batch)
    }

    /** 수신 그룹 구성원을 교체한다. */
    fun replaceGroupMembers(groupId: Int, empNos: List<String>, actor: String) {
        jdbcTemplate.update(
            "DELETE FROM ax.tb_alm_recip_group_member WHERE group_id = :groupId",
            MapSqlParameterSource("groupId", groupId)
        )
        if (empNos.isEmpty()) return

        // 수신자 등록이 되어 있는 계정만 그룹에 편성할 수 있다.
        val sql = """
            INSERT INTO ax.tb_alm_recip_group_member (group_id, user_id, ins_user)
            SELECT :groupId, r.user_id, :actor
            FROM ax.tb_alm_recipient r
            WHERE r.user_id = :userId
            ON CONFLICT (group_id, user_id) DO NOTHING
        """.trimIndent()

        val batch = empNos.map {
            MapSqlParameterSource().addValue("groupId", groupId).addValue("userId", it).addValue("actor", actor)
        }.toTypedArray()

        jdbcTemplate.batchUpdate(sql, batch)
    }

    /** 수신 그룹 존재 확인 */
    fun existsGroup(groupId: Int): Boolean {
        val sql = "SELECT count(*) FROM ax.tb_alm_recip_group WHERE group_id = :groupId"
        return (jdbcTemplate.queryForObject(sql, MapSqlParameterSource("groupId", groupId), Long::class.java) ?: 0L) > 0
    }

    /**
     * 수신자 목록을 조회한다. (No.162)
     *
     * @param state 수신 상태 (RECV/ABSENT)
     */
    fun findRecipients(state: String?, limit: Int, offset: Int, filter: RecipientFilter = RecipientFilter()): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT
                r.user_id, u.user_nm, d.dept_nm, u.position_cd, pc.code_nm AS position_nm,
                r.email, r.mobile_no, r.messenger_id, r.night_recv,
                r.recv_state_cd, sc.code_nm AS recv_state_nm, r.remark,
                u.user_state_cd, us.code_nm AS user_state_nm,
                (
                    SELECT string_agg(g.group_nm, ',' ORDER BY g.group_nm)
                      FROM ax.tb_alm_recip_group_member m
                     INNER JOIN ax.tb_alm_recip_group g ON g.group_id = m.group_id AND g.use_flg = 'Y'
                     WHERE m.user_id = r.user_id
                ) AS group_names
            FROM ax.tb_alm_recipient r
            INNER JOIN ax.tb_sys_user u ON u.user_id = r.user_id
            LEFT  JOIN ax.tb_sys_dept d ON d.dept_id = u.dept_id
            LEFT  JOIN ax.tb_sys_code pc ON pc.group_cd = 'SYS_POSITION'   AND pc.code = u.position_cd
            LEFT  JOIN ax.tb_sys_code sc ON sc.group_cd = 'ALM_RECV_STATE' AND sc.code = r.recv_state_cd
            LEFT  JOIN ax.tb_sys_code us ON us.group_cd = 'SYS_USER_STATE' AND us.code = u.user_state_cd
            WHERE 1 = 1
            """.trimIndent()
        )

        val params = MapSqlParameterSource()
        appendRecipientFilters(sql, params, state, filter)

        sql.append("\nORDER BY d.sort_seq, u.user_nm, r.user_id\nLIMIT :limit OFFSET :offset")
        params.addValue("limit", limit).addValue("offset", offset)

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "empNo" to rs.getString("user_id"),
                "recipientId" to rs.getString("user_id"),
                "name" to rs.getString("user_nm"),
                "dept" to rs.getString("dept_nm"),
                "pos" to rs.getString("position_cd"),
                "posNm" to (rs.getString("position_nm") ?: rs.getString("position_cd")),
                "mail" to rs.getString("email"),
                "hp" to rs.getString("mobile_no"),
                "messenger" to rs.getString("messenger_id"),
                "night" to rs.getBoolean("night_recv"),
                "state" to rs.getString("recv_state_cd"),
                "stateNm" to rs.getString("recv_state_nm"),
                // 계정 상태 — 정지·승인 대기 계정은 수신 상태여도 알림을 받지 못한다(RCP-04)
                "userState" to rs.getString("user_state_cd"),
                "userStateNm" to rs.getString("user_state_nm"),
                // 소속 수신 그룹 — 화면이 그룹으로 좁혀 보는 기준이다 (없으면 빈 배열)
                "groups" to (rs.getString("group_names")?.split(",") ?: emptyList()),
                "remark" to rs.getString("remark")
            )
        }
    }

    /** 수신자 전체 건수 */
    fun countRecipients(state: String?, filter: RecipientFilter = RecipientFilter()): Long {
        val sql = StringBuilder(
            """
            SELECT count(*) FROM ax.tb_alm_recipient r
              JOIN ax.tb_sys_user u ON u.user_id = r.user_id
              LEFT JOIN ax.tb_sys_dept d ON d.dept_id = u.dept_id
             WHERE 1 = 1
            """.trimIndent()
        )
        val params = MapSqlParameterSource()
        appendRecipientFilters(sql, params, state, filter)
        return jdbcTemplate.queryForObject(sql.toString(), params, Long::class.java) ?: 0L
    }

    /** 수신자 목록 조건 (06 RCP-05·11) — 이름·사번·부서 검색 · 소속 그룹 · 계정 상태 */
    data class RecipientFilter(val keyword: String? = null, val groupId: Int? = null, val userState: String? = null)

    private fun appendRecipientFilters(sql: StringBuilder, params: MapSqlParameterSource, state: String?, f: RecipientFilter) {
        if (!state.isNullOrBlank()) {
            sql.append(" AND r.recv_state_cd = :state")
            params.addValue("state", normalizeRecvState(state))
        }
        com.dwje.api.common.util.SqlLikeUtils.contains(f.keyword)?.let {
            sql.append(" AND (u.user_nm ILIKE :kw ESCAPE '\\' OR r.user_id ILIKE :kw ESCAPE '\\' OR coalesce(d.dept_nm, '') ILIKE :kw ESCAPE '\\')")
            params.addValue("kw", it)
        }
        f.groupId?.let {
            sql.append(" AND EXISTS (SELECT 1 FROM ax.tb_alm_recip_group_member mf WHERE mf.user_id = r.user_id AND mf.group_id = :groupIdF)")
            params.addValue("groupIdF", it)
        }
        f.userState?.let {
            sql.append(" AND u.user_state_cd = :userState")
            params.addValue("userState", it)
        }
    }

    /**
     * 수신자를 등록한다. (No.163)
     */
    fun insertRecipient(
        empNo: String,
        email: String,
        mobileNo: String?,
        messengerId: String?,
        night: Boolean,
        actor: String
    ): Int {
        val sql = """
            INSERT INTO ax.tb_alm_recipient (
                user_id, email, mobile_no, messenger_id, night_recv, recv_state_cd, ins_user, upd_user
            ) VALUES (
                :empNo, :email, :mobileNo, :messengerId, :night, 'RECV', :actor, :actor
            )
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("empNo", empNo)
            .addValue("email", email)
            .addValue("mobileNo", mobileNo)
            .addValue("messengerId", messengerId)
            .addValue("night", night)
            .addValue("actor", actor)

        return jdbcTemplate.update(sql, params)
    }

    /**
     * 수신자를 수정한다. (No.164)
     *
     * 휴대전화·메신저는 `""` 을 보내면 지운다(06 RCP-12). null(키 없음)은 그대로 둔다. 길이는 서비스가 검사한다.
     */
    fun updateRecipient(
        empNo: String,
        email: String?,
        mobileNo: String?,
        messengerId: String?,
        night: Boolean?,
        actor: String
    ): Int {
        val sql = """
            UPDATE ax.tb_alm_recipient
               SET email        = coalesce(:email, email),
                   mobile_no    = CASE WHEN :hpGiven THEN nullif(:mobileNo, '') ELSE mobile_no END,
                   messenger_id = CASE WHEN :msgGiven THEN nullif(:messengerId, '') ELSE messenger_id END,
                   night_recv   = coalesce(:night, night_recv),
                   upd_date     = now(),
                   upd_user     = :actor
             WHERE user_id = :empNo
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("empNo", empNo)
            .addValue("email", email)
            .addValue("mobileNo", mobileNo)
            .addValue("messengerId", messengerId)
            .addValue("hpGiven", mobileNo != null)
            .addValue("msgGiven", messengerId != null)
            .addValue("night", night)
            .addValue("actor", actor)

        return jdbcTemplate.update(sql, params)
    }

    /**
     * 수신/부재 상태를 토글한다. (No.165)
     */
    fun updateRecipientState(empNo: String, state: String, actor: String, reason: String? = null): Int {
        // 비고는 사유를 보냈을 때만 바꾼다 — "" 은 지운다 (06 RCP-07)
        val sql = """
            UPDATE ax.tb_alm_recipient
               SET recv_state_cd = :state,
                   remark        = CASE WHEN :reasonGiven THEN nullif(:reason, '') ELSE remark END,
                   upd_date      = now(),
                   upd_user      = :actor
             WHERE user_id = :empNo
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("empNo", empNo)
            .addValue("state", normalizeRecvState(state))
            .addValue("reason", reason)
            .addValue("reasonGiven", reason != null)
            .addValue("actor", actor)

        return jdbcTemplate.update(sql, params)
    }

    /** 수신자 한 명의 저장 값 — 수정 감사(바뀐 항목 이름)·상태 비교용. 응답에 싣지 않는다 */
    fun findRecipientRow(empNo: String): Map<String, Any?>? =
        jdbcTemplate.query(
            "SELECT email, mobile_no, messenger_id, night_recv, recv_state_cd, remark FROM ax.tb_alm_recipient WHERE user_id = :empNo",
            MapSqlParameterSource("empNo", empNo)
        ) { rs, _ ->
            mapOf<String, Any?>(
                "mail" to rs.getString("email"), "hp" to rs.getString("mobile_no"), "messenger" to rs.getString("messenger_id"),
                "night" to rs.getBoolean("night_recv"), "state" to rs.getString("recv_state_cd"), "remark" to rs.getString("remark")
            )
        }.firstOrNull()

    /** 수신자 존재 확인 */
    fun existsRecipient(empNo: String): Boolean {
        val sql = "SELECT count(*) FROM ax.tb_alm_recipient WHERE user_id = :empNo"
        return (jdbcTemplate.queryForObject(sql, MapSqlParameterSource("empNo", empNo), Long::class.java) ?: 0L) > 0
    }

    /**
     * 수신 그룹 구성원의 발송 대상 정보를 조회한다. (테스트 발송용)
     */
    fun findGroupRecipients(groupId: Int): List<Map<String, Any?>> {
        val sql = """
            SELECT r.user_id, u.user_nm, r.email, r.mobile_no, r.messenger_id, r.recv_state_cd
            FROM ax.tb_alm_recip_group_member m
            INNER JOIN ax.tb_alm_recipient r ON r.user_id = m.user_id
            INNER JOIN ax.tb_sys_user      u ON u.user_id = m.user_id
            WHERE m.group_id = :groupId
              AND r.recv_state_cd = 'RECV'
            ORDER BY u.user_nm
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource("groupId", groupId)) { rs, _ ->
            mapOf(
                "empNo" to rs.getString("user_id"),
                "name" to rs.getString("user_nm"),
                "mail" to rs.getString("email"),
                "hp" to rs.getString("mobile_no"),
                "messenger" to rs.getString("messenger_id")
            )
        }
    }

    /** 수신 상태 표기값을 코드로 정규화한다. */
    fun normalizeRecvState(state: String): String = when (state.trim()) {
        "수신", "RECV", "recv" -> "RECV"
        "부재", "ABSENT", "absent" -> "ABSENT"
        else -> state.trim().uppercase()
    }

    // =================================================================================
    // 수신 대상 판정 · 조건 상세 (05 ALC-03~05, 06 RCP-02~04)
    // =================================================================================

    /** 수신자 등록 후보 — 사용 중 계정 중 아직 수신자가 아니고 미배정이 아닌 사람 (06 RCP-05, R-14) */
    fun findRecipientCandidates(
        keyword: String?, deptId: Int?, unassignedDeptName: String, limit: Int, states: List<String> = listOf("ACTIVE")
    ): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT u.user_id, u.user_nm, d.dept_nm, pc.code_nm AS position_nm, u.position_cd, u.email
              FROM ax.tb_sys_user u
              JOIN ax.tb_sys_dept d ON d.dept_id = u.dept_id
              LEFT JOIN ax.tb_sys_code pc ON pc.group_cd = 'SYS_POSITION' AND pc.code = u.position_cd
             WHERE u.user_state_cd = ANY(:states)
               AND d.dept_nm <> :unassigned
               AND NOT EXISTS (SELECT 1 FROM ax.tb_alm_recipient r WHERE r.user_id = u.user_id)
            """.trimIndent()
        )
        val params = MapSqlParameterSource().addValue("unassigned", unassignedDeptName).addValue("limit", limit)
            .addValue("states", states.toTypedArray())
        com.dwje.api.common.util.SqlLikeUtils.contains(keyword)?.let {
            sql.append(" AND (u.user_id LIKE :kw ESCAPE '\\' OR u.user_nm LIKE :kw ESCAPE '\\')")
            params.addValue("kw", it)
        }
        if (deptId != null) {
            sql.append(" AND u.dept_id = :deptId")
            params.addValue("deptId", deptId)
        }
        sql.append("\nORDER BY d.sort_seq, u.user_nm, u.user_id\nLIMIT :limit")
        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "empNo" to rs.getString("user_id"),
                "name" to rs.getString("user_nm"),
                "dept" to rs.getString("dept_nm"),
                "posNm" to (rs.getString("position_nm") ?: rs.getString("position_cd")),
                "email" to rs.getString("email")
            )
        }
    }

    // ---- 수신자 영향·삭제·그룹 사용 중지 (06 RCP-06·08·09) ----

    /** 계정의 부서 이름 (없으면 null) */
    fun findUserDeptName(empNo: String): String? =
        jdbcTemplate.query(
            "SELECT d.dept_nm FROM ax.tb_sys_user u JOIN ax.tb_sys_dept d ON d.dept_id = u.dept_id WHERE u.user_id = :empNo",
            MapSqlParameterSource("empNo", empNo)
        ) { rs, _ -> rs.getString("dept_nm") }.firstOrNull()

    /** 이 수신자가 멤버인 그룹 ID (사용 중지 그룹 포함) */
    fun findMemberGroupIds(empNo: String): List<Int> =
        jdbcTemplate.query(
            "SELECT group_id FROM ax.tb_alm_recip_group_member WHERE user_id = :empNo ORDER BY group_id",
            MapSqlParameterSource("empNo", empNo)
        ) { rs, _ -> rs.getInt("group_id") }

    /** 그룹별 발송 조건 (그룹 ID → [{condId, name, on}]) */
    fun findConditionsByGroups(groupIds: Collection<Int>): Map<Int, List<Map<String, Any?>>> {
        if (groupIds.isEmpty()) return emptyMap()
        return jdbcTemplate.query(
            """
            SELECT cg.group_id, c.cond_id, c.cond_nm, c.use_flg
              FROM ax.tb_alm_cond_group cg JOIN ax.tb_alm_cond c ON c.cond_id = cg.cond_id
             WHERE cg.group_id IN (:ids)
             ORDER BY c.cond_nm
            """.trimIndent(), MapSqlParameterSource("ids", groupIds)
        ) { rs, _ ->
            rs.getInt("group_id") to mapOf<String, Any?>("condId" to rs.getInt("cond_id"), "name" to rs.getString("cond_nm"), "on" to Rs.yn(rs, "use_flg"))
        }.groupBy({ it.first }, { it.second })
    }

    /** 수신자 삭제 — 그룹 멤버십은 FK CASCADE, 지난 발송 로그는 그대로 남는다 */
    fun deleteRecipient(empNo: String): Int =
        jdbcTemplate.update("DELETE FROM ax.tb_alm_recipient WHERE user_id = :empNo", MapSqlParameterSource("empNo", empNo))

    /** 수신 그룹 사용/중지 */
    fun updateGroupUse(groupId: Int, on: Boolean, actor: String): Int =
        jdbcTemplate.update(
            "UPDATE ax.tb_alm_recip_group SET use_flg = :yn, upd_date = now(), upd_user = :actor WHERE group_id = :groupId",
            MapSqlParameterSource().addValue("groupId", groupId).addValue("yn", if (on) "Y" else "N").addValue("actor", actor)
        )

    /** 수신자 등록 후보 전체 수 — [findRecipientCandidates] 와 같은 조건 */
    fun countRecipientCandidates(keyword: String?, deptId: Int?, unassignedDeptName: String, states: List<String>): Long {
        val sql = StringBuilder(
            """
            SELECT count(*)
              FROM ax.tb_sys_user u
              JOIN ax.tb_sys_dept d ON d.dept_id = u.dept_id
             WHERE u.user_state_cd = ANY(:states)
               AND d.dept_nm <> :unassigned
               AND NOT EXISTS (SELECT 1 FROM ax.tb_alm_recipient r WHERE r.user_id = u.user_id)
            """.trimIndent()
        )
        val params = MapSqlParameterSource().addValue("unassigned", unassignedDeptName).addValue("states", states.toTypedArray())
        com.dwje.api.common.util.SqlLikeUtils.contains(keyword)?.let {
            sql.append(" AND (u.user_id LIKE :kw ESCAPE '\\' OR u.user_nm LIKE :kw ESCAPE '\\')")
            params.addValue("kw", it)
        }
        if (deptId != null) { sql.append(" AND u.dept_id = :deptId"); params.addValue("deptId", deptId) }
        return jdbcTemplate.queryForObject(sql.toString(), params, Long::class.java) ?: 0L
    }

    /** 알림 엔진 최근 평가 시각 — 테스트 발송 응답의 엔진 상태(05 ALC-07) */
    fun findLastEvalRunAt(): java.time.OffsetDateTime? =
        jdbcTemplate.query(
            "SELECT max(started_at) AS at FROM ax.tb_alm_eval_run", MapSqlParameterSource()
        ) { rs, _ -> rs.getObject("at", java.time.OffsetDateTime::class.java) }.firstOrNull()

    /** 수신 대상 판정용 그룹·채널 */
    fun findTargetGroups(groupIds: List<Int>): List<TargetGroupRow> {
        if (groupIds.isEmpty()) return emptyList()
        val sql = """
            SELECT g.group_id, g.group_nm, g.use_flg, g.night_recv,
                   (SELECT string_agg(gc.channel_cd, ',' ORDER BY gc.channel_cd)
                      FROM ax.tb_alm_recip_group_channel gc WHERE gc.group_id = g.group_id) AS channels
              FROM ax.tb_alm_recip_group g
             WHERE g.group_id IN (:groupIds)
        """.trimIndent()
        return jdbcTemplate.query(sql, MapSqlParameterSource("groupIds", groupIds)) { rs, _ ->
            TargetGroupRow(
                groupId = rs.getInt("group_id"),
                groupNm = rs.getString("group_nm"),
                useFlg = Rs.yn(rs, "use_flg"),
                night = rs.getBoolean("night_recv"),
                channels = rs.getString("channels")?.split(",") ?: emptyList()
            )
        }
    }

    /** 수신 대상 판정용 멤버 — 제외 사유를 내야 하므로 엔진처럼 WHERE 로 거르지 않고 다 읽는다 */
    fun findTargetMembers(groupIds: List<Int>): List<TargetMemberRow> {
        if (groupIds.isEmpty()) return emptyList()
        val sql = """
            SELECT m.group_id, m.user_id, u.user_nm, d.dept_nm, u.user_state_cd, us.code_nm AS user_state_nm,
                   rc.recv_state_cd, rc.email, rc.mobile_no, rc.messenger_id, rc.night_recv
              FROM ax.tb_alm_recip_group_member m
              JOIN ax.tb_alm_recipient rc ON rc.user_id = m.user_id
              JOIN ax.tb_sys_user u       ON u.user_id  = m.user_id
              LEFT JOIN ax.tb_sys_dept d  ON d.dept_id  = u.dept_id
              LEFT JOIN ax.tb_sys_code us ON us.group_cd = 'SYS_USER_STATE' AND us.code = u.user_state_cd
             WHERE m.group_id IN (:groupIds)
             ORDER BY m.group_id, u.user_nm, m.user_id
        """.trimIndent()
        return jdbcTemplate.query(sql, MapSqlParameterSource("groupIds", groupIds)) { rs, _ ->
            TargetMemberRow(
                groupId = rs.getInt("group_id"),
                userId = rs.getString("user_id"),
                userNm = rs.getString("user_nm"),
                deptNm = rs.getString("dept_nm"),
                userState = rs.getString("user_state_cd"),
                userStateNm = rs.getString("user_state_nm"),
                recvState = rs.getString("recv_state_cd"),
                email = rs.getString("email"),
                mobileNo = rs.getString("mobile_no"),
                messengerId = rs.getString("messenger_id"),
                night = rs.getBoolean("night_recv")
            )
        }
    }

    /** 조건별 연결 수신 그룹 — 목록·상세 `groups[]` 용 (조건 ID → 그룹 목록) */
    fun findConditionGroups(condIds: List<Int>): Map<Int, List<Map<String, Any?>>> {
        if (condIds.isEmpty()) return emptyMap()
        val sql = """
            SELECT cg.cond_id, g.group_id, g.group_nm, g.use_flg
              FROM ax.tb_alm_cond_group cg
              JOIN ax.tb_alm_recip_group g ON g.group_id = cg.group_id
             WHERE cg.cond_id IN (:condIds)
             ORDER BY cg.cond_id, g.group_nm
        """.trimIndent()
        return jdbcTemplate.query(sql, MapSqlParameterSource("condIds", condIds)) { rs, _ ->
            rs.getInt("cond_id") to mapOf(
                "groupId" to rs.getInt("group_id"),
                "name" to rs.getString("group_nm"),
                "useFlg" to rs.getString("use_flg")
            )
        }.groupBy({ it.first }, { it.second })
    }

    /**
     * 발송 조건 상세 (ALC-04) — 그룹·대상 설비는 따로 붙인다.
     * 고급 설정 7가지는 읽지 않는다(2026-10-03 제거 — V72·V73 어느 쪽에서도 같은 응답). `c.*` 대신 쓰는 열만 고른다.
     */
    fun findConditionDetail(condId: Int): Map<String, Any?>? {
        val sql = """
            SELECT c.cond_id, c.cond_nm, c.use_flg, c.metric_id, c.metric_desc, c.op_cd, c.threshold_val, c.threshold_text,
                   c.threshold_unit, c.duration_cd, c.target_scope_cd, c.target_desc, c.severity_cd, c.window_cd, c.dedup_cd,
                   c.blind_field_key, c.upd_date,
                   ms.metric_nm, ms.unit_cd, un.code_nm AS unit_nm,
                   opc.code_nm AS op_nm, sv.code_nm AS severity_nm,
                   (SELECT string_agg(cc.channel_cd, ',' ORDER BY cc.channel_cd)
                      FROM ax.tb_alm_cond_channel cc WHERE cc.cond_id = c.cond_id) AS channels,
                   (SELECT string_agg(ct.target_cd, ',' ORDER BY ct.target_cd)
                      FROM ax.tb_alm_cond_target ct
                     WHERE ct.cond_id = c.cond_id AND ct.target_dim_cd = 'EQPT') AS pick_targets
              FROM ax.tb_alm_cond c
              LEFT JOIN ax.tb_met_metric_std ms ON ms.metric_id = c.metric_id
              LEFT JOIN ax.tb_sys_code un  ON un.group_cd  = 'MET_UNIT'     AND un.code  = ms.unit_cd
              LEFT JOIN ax.tb_sys_code opc ON opc.group_cd = 'ALM_OP'       AND opc.code = c.op_cd
              LEFT JOIN ax.tb_sys_code sv  ON sv.group_cd  = 'ALM_SEVERITY' AND sv.code  = c.severity_cd
             WHERE c.cond_id = :condId
        """.trimIndent()
        return jdbcTemplate.query(sql, MapSqlParameterSource("condId", condId)) { rs, _ ->
            mapOf<String, Any?>(
                "condId" to rs.getInt("cond_id"),
                "name" to rs.getString("cond_nm"),
                "on" to Rs.yn(rs, "use_flg"),
                "metricStdId" to Rs.intOrNull(rs, "metric_id"),
                "metricNm" to rs.getString("metric_nm"),
                "metricDesc" to rs.getString("metric_desc"),
                "unit" to rs.getString("unit_cd"),
                "unitNm" to rs.getString("unit_nm"),
                "op" to rs.getString("op_cd"),
                "opNm" to rs.getString("op_nm"),
                "thresholdVal" to rs.getBigDecimal("threshold_val"),
                "thresholdText" to rs.getString("threshold_text"),
                "thresholdUnit" to rs.getString("threshold_unit"),
                "duration" to rs.getString("duration_cd"),
                "targetScope" to rs.getString("target_scope_cd"),
                "target" to rs.getString("target_desc"),
                "pickTargets" to (rs.getString("pick_targets")?.split(",") ?: emptyList()),
                "severity" to rs.getString("severity_cd"),
                "severityNm" to rs.getString("severity_nm"),
                "channels" to (rs.getString("channels")?.split(",") ?: emptyList()),
                "validWindow" to rs.getString("window_cd"),
                "dedupMin" to rs.getString("dedup_cd"),
                "blindFieldKey" to rs.getString("blind_field_key"),
                "updatedAt" to Rs.dateTime(rs, "upd_date")
            )
        }.firstOrNull()
    }

    /** 개별 설비 대상(PICK)을 교체한다. 빈 목록이면 지우기만 한다 */
    fun replaceConditionTargets(condId: Int, eqptCds: List<String>) {
        jdbcTemplate.update(
            "DELETE FROM ax.tb_alm_cond_target WHERE cond_id = :condId",
            MapSqlParameterSource("condId", condId)
        )
        if (eqptCds.isEmpty()) return
        val sql = """
            INSERT INTO ax.tb_alm_cond_target (cond_id, target_dim_cd, target_cd)
            VALUES (:condId, 'EQPT', :targetCd)
            ON CONFLICT DO NOTHING
        """.trimIndent()
        val batch = eqptCds.map {
            MapSqlParameterSource().addValue("condId", condId).addValue("targetCd", it)
        }.toTypedArray()
        jdbcTemplate.batchUpdate(sql, batch)
    }

    /** 공통코드 표기명 (없으면 null) */
    fun findCodeName(groupCd: String, code: String): String? =
        jdbcTemplate.query(
            "SELECT code_nm FROM ax.tb_sys_code WHERE group_cd = :groupCd AND code = :code",
            MapSqlParameterSource().addValue("groupCd", groupCd).addValue("code", code)
        ) { rs, _ -> rs.getString("code_nm") }.firstOrNull()

    /** 그룹 상세 — 이 그룹을 쓰는 발송 조건 */
    fun findGroupConditions(groupId: Int): List<Map<String, Any?>> =
        jdbcTemplate.query(
            """
            SELECT c.cond_id, c.cond_nm, c.use_flg
              FROM ax.tb_alm_cond_group cg
              JOIN ax.tb_alm_cond c ON c.cond_id = cg.cond_id
             WHERE cg.group_id = :groupId
             ORDER BY c.cond_nm
            """.trimIndent(),
            MapSqlParameterSource("groupId", groupId)
        ) { rs, _ -> mapOf("condId" to rs.getInt("cond_id"), "name" to rs.getString("cond_nm"), "on" to Rs.yn(rs, "use_flg")) }

    /** 그룹 담당 부서 선택지 — 사용 중 부서(미배정 제외) */
    fun findDeptOptions(unassignedDeptName: String): List<Map<String, Any?>> =
        jdbcTemplate.query(
            """
            SELECT dept_id, dept_nm FROM ax.tb_sys_dept
             WHERE use_flg = 'Y' AND dept_nm <> :unassigned
             ORDER BY sort_seq, dept_id
            """.trimIndent(),
            MapSqlParameterSource("unassigned", unassignedDeptName)
        ) { rs, _ -> mapOf("value" to rs.getInt("dept_id"), "label" to rs.getString("dept_nm")) }

    /** 사용 중 부서 존재 확인 */
    fun existsActiveDept(deptId: Int): Boolean =
        jdbcTemplate.queryForObject(
            "SELECT exists(SELECT 1 FROM ax.tb_sys_dept WHERE dept_id = :deptId AND use_flg = 'Y')",
            MapSqlParameterSource("deptId", deptId),
            Boolean::class.java
        ) ?: false

    /** 수신자로 등록되지 않은 사번 */
    fun findNonRecipients(empNos: List<String>): List<String> {
        if (empNos.isEmpty()) return emptyList()
        val known = jdbcTemplate.query(
            "SELECT user_id FROM ax.tb_alm_recipient WHERE user_id IN (:empNos)",
            MapSqlParameterSource("empNos", empNos)
        ) { rs, _ -> rs.getString("user_id") }.toSet()
        return empNos.filterNot { it in known }
    }

    /** 그룹명 중복 확인 */
    fun existsGroupName(groupNm: String, excludeGroupId: Int?): Boolean =
        jdbcTemplate.queryForObject(
            """
            SELECT exists(SELECT 1 FROM ax.tb_alm_recip_group
                           WHERE group_nm = :groupNm AND (:excludeId::int IS NULL OR group_id <> :excludeId))
            """.trimIndent(),
            MapSqlParameterSource().addValue("groupNm", groupNm).addValue("excludeId", excludeGroupId),
            Boolean::class.java
        ) ?: false
}

/** 발송 조건 한 건의 저장 값 — 서비스가 검증·병합을 마친 값 (05 ALC-09·12) */
data class AlertCondValues(
    val name: String,
    val severity: String,
    val metricId: Int?,
    val metricDesc: String,
    val op: String,
    val thresholdVal: java.math.BigDecimal,
    val thresholdText: String,
    val thresholdUnit: String?,
    val duration: String,
    val targetScope: String,
    val targetDesc: String,
    val validWindow: String,
    val dedupMin: String,
    val blindFieldKey: String?
)
