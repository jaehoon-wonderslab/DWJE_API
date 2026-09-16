package com.dwje.api.repository

import com.dwje.api.common.util.Rs
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal

/**
 * 이상 알림 발송 조건 · 수신자 관리 Repository (SY-04, SY-05)
 *
 * 참조 테이블 : ax.tb_alm_cond, ax.tb_alm_cond_channel, ax.tb_alm_cond_group,
 *              ax.tb_alm_cond_escalation, ax.tb_alm_recip_group, ax.tb_alm_recip_group_channel,
 *              ax.tb_alm_recip_group_member, ax.tb_alm_recipient,
 *              ax.tb_alm_escalation_rule
 */
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
     * 당일 채널별 발송 건수와 평균 지연을 조회한다. (No.151)
     */
    fun findTodaySendStats(): Map<String, Any?> {
        val sql = """
            SELECT
                s.channel_cd,
                count(*)                                                             AS send_cnt,
                count(*) FILTER (WHERE s.send_result_cd = 'SUPPRESSED')              AS dedup_cnt,
                avg(extract(epoch FROM (s.sent_at - a.occurred_at)))                 AS avg_delay_sec
            FROM ax.tb_alm_send_log s
            INNER JOIN ax.tb_alm_alert a ON a.alert_id = s.alert_id
            WHERE s.sent_at >= date_trunc('day', now())
            GROUP BY s.channel_cd
        """.trimIndent()

        val rows = jdbcTemplate.query(sql, MapSqlParameterSource()) { rs, _ ->
            mapOf(
                "channel" to rs.getString("channel_cd"),
                "cnt" to rs.getLong("send_cnt"),
                "dedupCnt" to rs.getLong("dedup_cnt"),
                "avgDelaySec" to Rs.doubleOrNull(rs, "avg_delay_sec")?.let { Math.round(it * 10) / 10.0 }
            )
        }

        return mapOf(
            "byChannel" to rows.associate { (it["channel"] as String) to it["cnt"] },
            "todaySentCnt" to rows.sumOf { (it["cnt"] as Long) },
            "dedupCnt" to rows.sumOf { (it["dedupCnt"] as Long) },
            "avgDelaySec" to rows.mapNotNull { it["avgDelaySec"] as? Double }.average()
                .takeIf { !it.isNaN() }?.let { Math.round(it * 10) / 10.0 }
        )
    }

    /**
     * 발송 조건 목록을 조회한다. (No.152)
     */
    fun findConditions(
        severity: String?,
        channel: String?,
        state: String?,
        limit: Int,
        offset: Int
    ): List<Map<String, Any?>> {
        val sql = StringBuilder(baseConditionSql())
        val params = MapSqlParameterSource()
        appendConditionFilters(sql, params, severity, channel, state)

        sql.append("\nORDER BY c.severity_cd, c.cond_nm\nLIMIT :limit OFFSET :offset")
        params.addValue("limit", limit).addValue("offset", offset)

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
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
                "msgTemplate" to rs.getString("msg_template"),
                "channels" to (rs.getString("channels")?.split(",") ?: emptyList()),
                "groups" to (rs.getString("group_names")?.split(",") ?: emptyList()),
                "updatedAt" to Rs.dateTime(rs, "upd_date")
            )
        }
    }

    /** 발송 조건 전체 건수 */
    fun countConditions(severity: String?, channel: String?, state: String?): Long {
        val sql = StringBuilder(
            """
            SELECT count(*)
            FROM ax.tb_alm_cond c
            WHERE 1 = 1
            """.trimIndent()
        )
        val params = MapSqlParameterSource()
        appendConditionFilters(sql, params, severity, channel, state)
        return jdbcTemplate.queryForObject(sql.toString(), params, Long::class.java) ?: 0L
    }

    /** 발송 조건 단건 조회 */
    fun findCondition(condId: Int): Map<String, Any?>? {
        val sql = "${baseConditionSql()}\n  AND c.cond_id = :condId"
        return jdbcTemplate.query(sql, MapSqlParameterSource("condId", condId)) { rs, _ ->
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
            c.blind_field_key, c.msg_template, c.use_flg, c.upd_date,
            (
                SELECT string_agg(cc.channel_cd, ',' ORDER BY cc.channel_cd)
                  FROM ax.tb_alm_cond_channel cc WHERE cc.cond_id = c.cond_id
            ) AS channels,
            (
                SELECT string_agg(g.group_nm, ',' ORDER BY g.group_nm)
                  FROM ax.tb_alm_cond_group cg
                 INNER JOIN ax.tb_alm_recip_group g ON g.group_id = cg.group_id
                 WHERE cg.cond_id = c.cond_id
            ) AS group_names
        FROM ax.tb_alm_cond c
        LEFT JOIN ax.tb_met_metric_std ms ON ms.metric_id = c.metric_id
        WHERE 1 = 1
    """.trimIndent()

    /** 발송 조건 목록/건수 공통 동적 조건 */
    private fun appendConditionFilters(
        sql: StringBuilder,
        params: MapSqlParameterSource,
        severity: String?,
        channel: String?,
        state: String?
    ) {
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
     * 발송 조건을 등록한다. (No.153)
     *
     * @return 생성된 조건 ID
     */
    fun insertCondition(
        condNm: String,
        severityCd: String,
        metricId: Int?,
        metricDesc: String,
        opCd: String,
        thresholdVal: BigDecimal?,
        thresholdText: String,
        thresholdUnit: String?,
        durationCd: String,
        targetScopeCd: String,
        targetDesc: String,
        windowCd: String,
        dedupCd: String,
        msgTemplate: String,
        actor: String
    ): Int {
        val sql = """
            INSERT INTO ax.tb_alm_cond (
                cond_nm, severity_cd, metric_id, metric_desc, op_cd,
                threshold_val, threshold_text, threshold_unit, duration_cd,
                target_scope_cd, target_desc, window_cd, dedup_cd, msg_template,
                use_flg, ins_user, upd_user
            ) VALUES (
                :condNm, :severityCd, :metricId, :metricDesc, :opCd,
                :thresholdVal, :thresholdText, :thresholdUnit, :durationCd,
                :targetScopeCd, :targetDesc, :windowCd, :dedupCd, :msgTemplate,
                'Y', :actor, :actor
            )
            RETURNING cond_id
        """.trimIndent()

        val params = conditionParams(
            condNm, severityCd, metricId, metricDesc, opCd, thresholdVal, thresholdText,
            thresholdUnit, durationCd, targetScopeCd, targetDesc, windowCd, dedupCd, msgTemplate, actor
        )

        return jdbcTemplate.queryForObject(sql, params, Int::class.java) ?: 0
    }

    /**
     * 발송 조건을 수정한다. (No.154)
     */
    fun updateCondition(
        condId: Int,
        condNm: String,
        severityCd: String,
        metricId: Int?,
        metricDesc: String,
        opCd: String,
        thresholdVal: BigDecimal?,
        thresholdText: String,
        thresholdUnit: String?,
        durationCd: String,
        targetScopeCd: String,
        targetDesc: String,
        windowCd: String,
        dedupCd: String,
        msgTemplate: String,
        actor: String
    ): Int {
        val sql = """
            UPDATE ax.tb_alm_cond
               SET cond_nm         = :condNm,
                   severity_cd     = :severityCd,
                   metric_id       = :metricId,
                   metric_desc     = :metricDesc,
                   op_cd           = :opCd,
                   threshold_val   = :thresholdVal,
                   threshold_text  = :thresholdText,
                   threshold_unit  = :thresholdUnit,
                   duration_cd     = :durationCd,
                   target_scope_cd = :targetScopeCd,
                   target_desc     = :targetDesc,
                   window_cd       = :windowCd,
                   dedup_cd        = :dedupCd,
                   msg_template    = :msgTemplate,
                   upd_date        = now(),
                   upd_user        = :actor
             WHERE cond_id = :condId
        """.trimIndent()

        val params = conditionParams(
            condNm, severityCd, metricId, metricDesc, opCd, thresholdVal, thresholdText,
            thresholdUnit, durationCd, targetScopeCd, targetDesc, windowCd, dedupCd, msgTemplate, actor
        ).addValue("condId", condId)

        return jdbcTemplate.update(sql, params)
    }

    /** 발송 조건 등록/수정 공통 파라미터 */
    private fun conditionParams(
        condNm: String,
        severityCd: String,
        metricId: Int?,
        metricDesc: String,
        opCd: String,
        thresholdVal: BigDecimal?,
        thresholdText: String,
        thresholdUnit: String?,
        durationCd: String,
        targetScopeCd: String,
        targetDesc: String,
        windowCd: String,
        dedupCd: String,
        msgTemplate: String,
        actor: String
    ): MapSqlParameterSource = MapSqlParameterSource()
        .addValue("condNm", condNm.take(100))
        .addValue("severityCd", severityCd)
        .addValue("metricId", metricId)
        .addValue("metricDesc", metricDesc.take(100))
        .addValue("opCd", opCd)
        .addValue("thresholdVal", thresholdVal)
        .addValue("thresholdText", thresholdText.take(50))
        .addValue("thresholdUnit", thresholdUnit?.take(20))
        .addValue("durationCd", durationCd)
        .addValue("targetScopeCd", targetScopeCd)
        .addValue("targetDesc", targetDesc.take(200))
        .addValue("windowCd", windowCd)
        .addValue("dedupCd", dedupCd)
        .addValue("msgTemplate", msgTemplate)
        .addValue("actor", actor)

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
        listOf("tb_alm_cond_channel", "tb_alm_cond_group", "tb_alm_cond_escalation").forEach {
            jdbcTemplate.update("DELETE FROM ax.$it WHERE cond_id = :condId", params)
        }
        return jdbcTemplate.update("DELETE FROM ax.tb_alm_cond WHERE cond_id = :condId", params)
    }

    /** 이 조건으로 발생한 알림 건수. (삭제 가능 여부 판정용) */
    fun countAlertsByCondition(condId: Int): Long =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ax.tb_alm_alert WHERE cond_id = :condId",
            MapSqlParameterSource("condId", condId),
            Long::class.java
        ) ?: 0L

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
    fun findRecipientSummary(): Map<String, Any?> {
        val sql = """
            SELECT
                (SELECT count(*) FROM ax.tb_alm_recip_group WHERE use_flg = 'Y')          AS group_cnt,
                (SELECT count(*) FROM ax.tb_alm_recipient WHERE recv_state_cd = 'RECV')   AS receiving_cnt,
                (SELECT count(*) FROM ax.tb_alm_recipient WHERE recv_state_cd = 'ABSENT') AS absent_cnt,
                (SELECT count(*) FROM ax.tb_alm_recipient WHERE night_recv)               AS night_cnt
        """.trimIndent()

        return jdbcTemplate.queryForObject(sql, MapSqlParameterSource()) { rs, _ ->
            mapOf(
                "groupCnt" to rs.getLong("group_cnt"),
                "recipientCnt" to mapOf(
                    "receiving" to rs.getLong("receiving_cnt"),
                    "absent" to rs.getLong("absent_cnt")
                ),
                "nightCnt" to rs.getLong("night_cnt")
            )
        } ?: emptyMap()
    }

    /**
     * 수신 그룹 목록을 조회한다. (No.158)
     */
    fun findRecipientGroups(): List<Map<String, Any?>> {
        val sql = """
            SELECT
                g.group_id, g.group_nm, g.window_cd, g.night_recv, g.dept_id, d.dept_nm, g.use_flg,
                (
                    SELECT string_agg(gc.channel_cd, ',' ORDER BY gc.channel_cd)
                      FROM ax.tb_alm_recip_group_channel gc WHERE gc.group_id = g.group_id
                ) AS channels,
                (
                    SELECT string_agg(u.user_nm, ',' ORDER BY u.user_nm)
                      FROM ax.tb_alm_recip_group_member m
                     INNER JOIN ax.tb_sys_user u ON u.user_id = m.user_id
                     WHERE m.group_id = g.group_id
                ) AS member_names,
                (
                    SELECT string_agg(m.user_id, ',' ORDER BY m.user_id)
                      FROM ax.tb_alm_recip_group_member m WHERE m.group_id = g.group_id
                ) AS member_ids
            FROM ax.tb_alm_recip_group g
            LEFT JOIN ax.tb_sys_dept d ON d.dept_id = g.dept_id
            WHERE g.use_flg = 'Y'
            ORDER BY g.group_nm
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource()) { rs, _ ->
            mapOf(
                "groupId" to rs.getInt("group_id"),
                "name" to rs.getString("group_nm"),
                "validWindow" to rs.getString("window_cd"),
                "night" to rs.getBoolean("night_recv"),
                "deptId" to Rs.intOrNull(rs, "dept_id"),
                "dept" to rs.getString("dept_nm"),
                "channels" to (rs.getString("channels")?.split(",") ?: emptyList()),
                "members" to (rs.getString("member_names")?.split(",") ?: emptyList()),
                "memberEmpNos" to (rs.getString("member_ids")?.split(",") ?: emptyList())
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
    fun findRecipients(state: String?, limit: Int, offset: Int): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT
                r.user_id, u.user_nm, d.dept_nm, u.position_cd, pc.code_nm AS position_nm,
                r.email, r.mobile_no, r.messenger_id, r.night_recv,
                r.recv_state_cd, sc.code_nm AS recv_state_nm, r.remark,
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
            WHERE 1 = 1
            """.trimIndent()
        )

        val params = MapSqlParameterSource()
        if (!state.isNullOrBlank()) {
            sql.append(" AND r.recv_state_cd = :state")
            params.addValue("state", normalizeRecvState(state))
        }

        sql.append("\nORDER BY d.sort_seq, u.user_nm\nLIMIT :limit OFFSET :offset")
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
                // 소속 수신 그룹 — 화면이 그룹으로 좁혀 보는 기준이다 (없으면 빈 배열)
                "groups" to (rs.getString("group_names")?.split(",") ?: emptyList()),
                "remark" to rs.getString("remark")
            )
        }
    }

    /** 수신자 전체 건수 */
    fun countRecipients(state: String?): Long {
        val sql = StringBuilder("SELECT count(*) FROM ax.tb_alm_recipient r WHERE 1 = 1")
        val params = MapSqlParameterSource()
        if (!state.isNullOrBlank()) {
            sql.append(" AND r.recv_state_cd = :state")
            params.addValue("state", normalizeRecvState(state))
        }
        return jdbcTemplate.queryForObject(sql.toString(), params, Long::class.java) ?: 0L
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
            .addValue("email", email.take(200))
            .addValue("mobileNo", mobileNo?.take(20))
            .addValue("messengerId", messengerId?.take(50))
            .addValue("night", night)
            .addValue("actor", actor)

        return jdbcTemplate.update(sql, params)
    }

    /**
     * 수신자를 수정한다. (No.164)
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
                   mobile_no    = coalesce(:mobileNo, mobile_no),
                   messenger_id = coalesce(:messengerId, messenger_id),
                   night_recv   = coalesce(:night, night_recv),
                   upd_date     = now(),
                   upd_user     = :actor
             WHERE user_id = :empNo
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("empNo", empNo)
            .addValue("email", email?.take(200))
            .addValue("mobileNo", mobileNo?.take(20))
            .addValue("messengerId", messengerId?.take(50))
            .addValue("night", night)
            .addValue("actor", actor)

        return jdbcTemplate.update(sql, params)
    }

    /**
     * 수신/부재 상태를 토글한다. (No.165)
     */
    fun updateRecipientState(empNo: String, state: String, actor: String): Int {
        val sql = """
            UPDATE ax.tb_alm_recipient
               SET recv_state_cd = :state,
                   upd_date      = now(),
                   upd_user      = :actor
             WHERE user_id = :empNo
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("empNo", empNo)
            .addValue("state", normalizeRecvState(state))
            .addValue("actor", actor)

        return jdbcTemplate.update(sql, params)
    }

    /** 수신자 존재 확인 */
    fun existsRecipient(empNo: String): Boolean {
        val sql = "SELECT count(*) FROM ax.tb_alm_recipient WHERE user_id = :empNo"
        return (jdbcTemplate.queryForObject(sql, MapSqlParameterSource("empNo", empNo), Long::class.java) ?: 0L) > 0
    }

    /**
     * 승격 규칙을 조회한다. (No.169)
     */
    fun findEscalationRules(): List<Map<String, Any?>> {
        val sql = """
            SELECT r.esc_rule_id, r.esc_level, r.level_nm, r.after_min, r.to_target_desc,
                   r.to_group_id, g.group_nm, r.severity_filter, r.note, r.use_flg
            FROM ax.tb_alm_escalation_rule r
            LEFT JOIN ax.tb_alm_recip_group g ON g.group_id = r.to_group_id
            ORDER BY r.esc_level
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource()) { rs, _ ->
            mapOf(
                "escRuleId" to rs.getInt("esc_rule_id"),
                "stage" to rs.getInt("esc_level"),
                "stageNm" to rs.getString("level_nm"),
                "waitMin" to rs.getInt("after_min"),
                "targetDesc" to rs.getString("to_target_desc"),
                "targetGroupId" to Rs.intOrNull(rs, "to_group_id"),
                "targetGroupNm" to rs.getString("group_nm"),
                "severityFilter" to rs.getString("severity_filter"),
                "note" to rs.getString("note"),
                "on" to Rs.yn(rs, "use_flg")
            )
        }
    }

    /**
     * 승격 규칙을 수정한다. (No.169)
     */
    fun updateEscalationRule(escLevel: Int, waitMin: Int?, targetGroupId: Int?): Int {
        val sql = """
            UPDATE ax.tb_alm_escalation_rule
               SET after_min   = coalesce(:waitMin, after_min),
                   to_group_id = coalesce(:targetGroupId, to_group_id)
             WHERE esc_level = :escLevel
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("escLevel", escLevel)
            .addValue("waitMin", waitMin)
            .addValue("targetGroupId", targetGroupId)

        return jdbcTemplate.update(sql, params)
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
}
