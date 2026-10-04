package com.dwje.api.repository

import com.dwje.api.common.util.Rs
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.LocalDate

/**
 * 이상 알림 Repository (AL-01)
 *
 * 참조 테이블 : ax.tb_alm_alert, ax.tb_alm_send_log, ax.tb_alm_cond,
 *              ax.tb_ai_agent
 */
@Repository
class AlertRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) {

    /**
     * 알림 목록을 조회한다. (No.102)
     *
     * @param severity 심각도 (ALM_SEVERITY — CRIT/WARN/LOW)
     * @param eqptCd   설비 코드
     * @param from     조회 시작일
     * @param to       조회 종료일
     * @param ackState 확인 상태 (ALM_ACK_STATE)
     */
    fun findAlerts(
        plantCd: String,
        severity: String?,
        eqptCd: String?,
        from: LocalDate,
        to: LocalDate,
        ackState: String?,
        limit: Int,
        offset: Int,
        includeTest: Boolean = false,
        condId: Int? = null,
        alertId: Long? = null
    ): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT
                a.alert_id,
                a.test_flg,
                a.severity_cd,
                sv.code_nm                                          AS severity_nm,
                a.title,
                a.occurred_at,
                a.metric_value,
                a.threshold_val,
                a.evidence_desc,
                a.target_desc,
                a.eqpt_cd,
                e.eqpt_nm,
                a.wc_cd,
                a.lot_no,
                a.item_cd,
                a.defect_cd,
                a.ack_state_cd,
                a.ack_user_id,
                u.user_nm                                           AS ack_user_nm,
                a.ack_at,
                a.esc_level,
                c.cond_nm,
                ag.agent_no,
                ag.agent_nm,
                extract(epoch FROM (now() - a.occurred_at)) / 60    AS elapsed_min
            FROM ax.tb_alm_alert a
            LEFT JOIN mes.tb_md_eqpt e  ON e.plant_cd = :plantCd AND e.eqpt_cd = a.eqpt_cd
            LEFT JOIN ax.tb_alm_cond c  ON c.cond_id  = a.cond_id
            LEFT JOIN ax.tb_ai_agent ag ON ag.agent_id = a.detect_agent_id
            LEFT JOIN ax.tb_sys_user u  ON u.user_id  = a.ack_user_id
            LEFT JOIN ax.tb_sys_code sv ON sv.group_cd = 'ALM_SEVERITY' AND sv.code = a.severity_cd
            WHERE a.occurred_at >= :from
              AND a.occurred_at <  :toExclusive
              AND (a.plant_cd IS NULL OR a.plant_cd = :plantCd)
            """.trimIndent()
        )

        val params = periodParams(plantCd, from, to)
        appendAlertFilters(sql, params, severity, eqptCd, ackState, includeTest, condId, alertId)

        sql.append(
            """

            ORDER BY
                CASE a.severity_cd WHEN 'CRIT' THEN 1 WHEN 'WARN' THEN 2 ELSE 3 END,
                a.occurred_at DESC
            LIMIT :limit OFFSET :offset
            """.trimIndent()
        )
        params.addValue("limit", limit).addValue("offset", offset)

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "alertId" to rs.getLong("alert_id"),
                "level" to rs.getString("severity_cd"),
                "levelNm" to rs.getString("severity_nm"),
                "title" to rs.getString("title"),
                "occurredAt" to Rs.dateTime(rs, "occurred_at"),
                "elapsed" to com.dwje.api.common.util.DateUtils.humanizeMinutes(
                    Rs.doubleOrNull(rs, "elapsed_min")?.toLong()
                ),
                "eqptCd" to rs.getString("eqpt_cd"),
                "eqptNm" to rs.getString("eqpt_nm"),
                "processId" to rs.getString("wc_cd"),
                "lotNo" to rs.getString("lot_no"),
                "itemCd" to rs.getString("item_cd"),
                "defectCd" to rs.getString("defect_cd"),
                "desc" to (rs.getString("evidence_desc") ?: rs.getString("target_desc")),
                "condNm" to rs.getString("cond_nm"),
                "ackState" to rs.getString("ack_state_cd"),
                "ackBy" to (rs.getString("ack_user_nm") ?: rs.getString("ack_user_id")),
                "ackAt" to Rs.dateTime(rs, "ack_at"),
                "escLevel" to rs.getInt("esc_level"),
                "agent" to rs.getString("agent_nm")?.let { "${rs.getString("agent_no")} $it" },
                "test" to Rs.yn(rs, "test_flg")
            )
        }
    }

    /** 알림 목록 전체 건수 */
    fun countAlerts(
        plantCd: String,
        severity: String?,
        eqptCd: String?,
        from: LocalDate,
        to: LocalDate,
        ackState: String?,
        includeTest: Boolean = false,
        condId: Int? = null,
        alertId: Long? = null
    ): Long {
        val sql = StringBuilder(
            """
            SELECT count(*)
            FROM ax.tb_alm_alert a
            WHERE a.occurred_at >= :from
              AND a.occurred_at <  :toExclusive
              AND (a.plant_cd IS NULL OR a.plant_cd = :plantCd)
            """.trimIndent()
        )

        val params = periodParams(plantCd, from, to)
        appendAlertFilters(sql, params, severity, eqptCd, ackState, includeTest, condId, alertId)

        return jdbcTemplate.queryForObject(sql.toString(), params, Long::class.java) ?: 0L
    }

    /** 알림 목록/건수 공통 동적 조건 */
    private fun appendAlertFilters(
        sql: StringBuilder,
        params: MapSqlParameterSource,
        severity: String?,
        eqptCd: String?,
        ackState: String?,
        includeTest: Boolean,
        condId: Int? = null,
        alertId: Long? = null
    ) {
        // 테스트 발송 알림(test_flg='Y')은 기본 목록에서 뺀다(05 ALC-03). 상세는 거르지 않는다 — 테스트 메일 링크가 열려야 한다.
        if (!includeTest) sql.append(" AND a.test_flg = 'N'")
        if (!severity.isNullOrBlank()) {
            sql.append(" AND a.severity_cd = :severity")
            params.addValue("severity", severity.trim().uppercase())
        }
        if (!eqptCd.isNullOrBlank()) {
            sql.append(" AND a.eqpt_cd = :eqptCd")
            params.addValue("eqptCd", eqptCd.trim())
        }
        if (!ackState.isNullOrBlank()) {
            sql.append(" AND a.ack_state_cd = :ackState")
            params.addValue("ackState", ackState.trim().uppercase())
        }
        if (condId != null) {
            sql.append(" AND a.cond_id = :condIdF")
            params.addValue("condIdF", condId)
        }
        if (alertId != null) {
            sql.append(" AND a.alert_id = :alertIdF")
            params.addValue("alertIdF", alertId)
        }
    }

    /**
     * 알림 상세를 조회한다. (No.103)
     */
    fun findAlert(alertId: Long): Map<String, Any?>? {
        val sql = """
            SELECT
                a.alert_id, a.cond_id, c.cond_nm, c.metric_desc,
                a.metric_id, ms.metric_nm, a.severity_cd, a.title, a.occurred_at,
                a.metric_value, a.threshold_val, a.evidence_desc, a.target_desc,
                a.plant_cd, a.wc_cd, a.eqpt_cd, e.eqpt_nm, a.mold_cd, a.item_cd,
                a.lot_no, a.serial_no, a.defect_cd, md.defect_nm,
                a.ack_state_cd, a.ack_user_id, u.user_nm AS ack_user_nm, a.ack_at, a.ack_note,
                a.esc_level, ag.agent_no, ag.agent_nm
            FROM ax.tb_alm_alert a
            LEFT JOIN ax.tb_alm_cond      c  ON c.cond_id   = a.cond_id
            LEFT JOIN ax.tb_met_metric_std ms ON ms.metric_id = a.metric_id
            LEFT JOIN mes.tb_md_eqpt      e  ON e.plant_cd  = a.plant_cd AND e.eqpt_cd = a.eqpt_cd
            LEFT JOIN mes.tb_md_defect    md ON md.plant_cd = a.plant_cd AND md.defect_cd = a.defect_cd
            LEFT JOIN ax.tb_sys_user      u  ON u.user_id   = a.ack_user_id
            LEFT JOIN ax.tb_ai_agent      ag ON ag.agent_id = a.detect_agent_id
            WHERE a.alert_id = :alertId
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource("alertId", alertId)) { rs, _ ->
            mapOf(
                "alertId" to rs.getLong("alert_id"),
                "condId" to Rs.intOrNull(rs, "cond_id"),
                "condNm" to rs.getString("cond_nm"),
                "metricDesc" to (rs.getString("metric_desc") ?: rs.getString("metric_nm")),
                "level" to rs.getString("severity_cd"),
                "title" to rs.getString("title"),
                "occurredAt" to Rs.dateTime(rs, "occurred_at"),
                "basisValue" to Rs.rate(rs, "metric_value", 4),
                "threshold" to Rs.rate(rs, "threshold_val", 4),
                "evidence" to rs.getString("evidence_desc"),
                "target" to rs.getString("target_desc"),
                "eqptCd" to rs.getString("eqpt_cd"),
                "eqptNm" to rs.getString("eqpt_nm"),
                "moldCd" to rs.getString("mold_cd"),
                "processId" to rs.getString("wc_cd"),
                "itemCd" to rs.getString("item_cd"),
                "lotNo" to rs.getString("lot_no"),
                "serialNo" to rs.getString("serial_no"),
                "mainDefectType" to (rs.getString("defect_nm") ?: rs.getString("defect_cd")),
                "ackState" to rs.getString("ack_state_cd"),
                "ackBy" to (rs.getString("ack_user_nm") ?: rs.getString("ack_user_id")),
                "ackAt" to Rs.dateTime(rs, "ack_at"),
                "ackNote" to rs.getString("ack_note"),
                "escLevel" to rs.getInt("esc_level"),
                "agent" to rs.getString("agent_nm")?.let { "${rs.getString("agent_no")} $it" }
            )
        }.firstOrNull()
    }

    /**
     * 동일 조건·동일 설비의 최근 발생 이력을 조회한다. (No.103 — 원인 후보 판단 근거)
     */
    fun findSimilarAlerts(condId: Int?, eqptCd: String?, excludeAlertId: Long, limit: Int): List<Map<String, Any?>> {
        val sql = """
            SELECT
                a.alert_id, a.title, a.occurred_at, a.metric_value, a.ack_note,
                d.defect_nm
            FROM ax.tb_alm_alert a
            LEFT JOIN mes.tb_md_defect d ON d.plant_cd = a.plant_cd AND d.defect_cd = a.defect_cd
            WHERE a.alert_id <> :excludeAlertId
              AND a.test_flg = 'N'
              AND (:condId::int  IS NULL OR a.cond_id = :condId)
              AND (:eqptCd::varchar IS NULL OR a.eqpt_cd = :eqptCd)
              AND a.occurred_at >= now() - interval '90 days'
            ORDER BY a.occurred_at DESC
            LIMIT :limit
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("excludeAlertId", excludeAlertId)
            .addValue("condId", condId)
            .addValue("eqptCd", eqptCd)
            .addValue("limit", limit)

        return jdbcTemplate.query(sql, params) { rs, _ ->
            mapOf(
                "alertId" to rs.getLong("alert_id"),
                "title" to rs.getString("title"),
                "occurredAt" to Rs.dateTime(rs, "occurred_at"),
                "value" to Rs.rate(rs, "metric_value", 4),
                "defectNm" to rs.getString("defect_nm"),
                "actionNote" to rs.getString("ack_note")
            )
        }
    }

    /**
     * 알림 확인 처리를 기록한다. (No.104)
     *
     * @param actionNote 조치 내용
     */
    fun updateAck(alertId: Long, actionNote: String?, actor: String): Int {
        val sql = """
            UPDATE ax.tb_alm_alert
               SET ack_state_cd = 'ACKED',
                   ack_user_id  = :actor,
                   ack_at       = now(),
                   ack_note     = :actionNote
             WHERE alert_id     = :alertId
               AND ack_state_cd = 'OPEN'
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("alertId", alertId)
            .addValue("actionNote", actionNote?.take(500))
            .addValue("actor", actor)

        return jdbcTemplate.update(sql, params)
    }

    /**
     * 나에게 온 팝업 알림 — 발송 로그의 POPUP · 발송 성공 행 중 [afterSendId] 뒤(오름차순, 최대 [limit]건).
     * 웹 우측 상단 토스트가 1분마다 읽는다(2026-10-04). 테스트 발송도 포함한다(test 표시).
     */
    fun findMyPopups(userId: String, afterSendId: Long, plantCd: String, limit: Int): List<Map<String, Any?>> =
        jdbcTemplate.query(
            """
            SELECT s.send_id, s.sent_at, a.alert_id, a.title, a.severity_cd, sv.code_nm AS severity_nm,
                   a.occurred_at, a.eqpt_cd, e.eqpt_nm, a.test_flg, c.cond_nm
              FROM ax.tb_alm_send_log s
              JOIN ax.tb_alm_alert a ON a.alert_id = s.alert_id
              LEFT JOIN mes.tb_md_eqpt e  ON e.plant_cd = :plantCd AND e.eqpt_cd = a.eqpt_cd
              LEFT JOIN ax.tb_alm_cond c  ON c.cond_id  = a.cond_id
              LEFT JOIN ax.tb_sys_code sv ON sv.group_cd = 'ALM_SEVERITY' AND sv.code = a.severity_cd
             WHERE s.user_id = :userId AND s.channel_cd = 'POPUP' AND s.send_result_cd = 'SENT'
               AND s.send_id > :after
             ORDER BY s.send_id
             LIMIT :limit
            """.trimIndent(),
            MapSqlParameterSource().addValue("userId", userId).addValue("after", afterSendId)
                .addValue("plantCd", plantCd).addValue("limit", limit)
        ) { rs, _ ->
            mapOf(
                "sendId" to rs.getLong("send_id"),
                "sentAt" to Rs.dateTime(rs, "sent_at"),
                "alertId" to rs.getLong("alert_id"),
                "title" to rs.getString("title"),
                "level" to rs.getString("severity_cd"),
                "levelNm" to rs.getString("severity_nm"),
                "occurredAt" to Rs.dateTime(rs, "occurred_at"),
                "eqptCd" to rs.getString("eqpt_cd"),
                "eqptNm" to rs.getString("eqpt_nm"),
                "condNm" to rs.getString("cond_nm"),
                "test" to (rs.getString("test_flg") == "Y")
            )
        }

    /** 나에게 온 팝업 발송 로그의 마지막 번호 — 처음 부를 때 기준점(그 전 것은 띄우지 않음). 없으면 0 */
    fun lastMyPopupSendId(userId: String): Long =
        jdbcTemplate.queryForObject(
            "SELECT coalesce(max(send_id), 0) FROM ax.tb_alm_send_log WHERE user_id = :userId AND channel_cd = 'POPUP' AND send_result_cd = 'SENT'",
            MapSqlParameterSource("userId", userId), Long::class.java
        ) ?: 0L

    /**
     * 알림 발송 로그를 조회한다. (No.106)
     */
    fun findSendLogs(
        from: LocalDate,
        to: LocalDate,
        condId: Int?,
        channel: String?,
        limit: Int,
        offset: Int
    ): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT
                s.send_id,
                s.sent_at,
                s.channel_cd,
                ch.code_nm                                                     AS channel_nm,
                s.dest_addr,
                s.send_result_cd,
                rs.code_nm                                                     AS result_nm,
                s.fail_reason,
                s.esc_level,
                s.is_proxy,
                s.user_id,
                u.user_nm,
                a.alert_id,
                a.title,
                a.test_flg,
                c.cond_id,
                c.cond_nm,
                extract(epoch FROM (s.sent_at - a.occurred_at))                AS delay_sec
            FROM ax.tb_alm_send_log s
            INNER JOIN ax.tb_alm_alert a ON a.alert_id = s.alert_id
            LEFT  JOIN ax.tb_alm_cond  c ON c.cond_id  = a.cond_id
            LEFT  JOIN ax.tb_sys_user  u ON u.user_id  = s.user_id
            LEFT  JOIN ax.tb_sys_code ch ON ch.group_cd = 'ALM_CHANNEL'     AND ch.code = s.channel_cd
            LEFT  JOIN ax.tb_sys_code rs ON rs.group_cd = 'ALM_SEND_RESULT' AND rs.code = s.send_result_cd
            WHERE s.sent_at >= :from
              AND s.sent_at <  :toExclusive
            """.trimIndent()
        )

        val params = MapSqlParameterSource()
            .addValue("from", from.atStartOfDay())
            .addValue("toExclusive", to.plusDays(1).atStartOfDay())

        appendSendLogFilters(sql, params, condId, channel)

        sql.append("\nORDER BY s.sent_at DESC\nLIMIT :limit OFFSET :offset")
        params.addValue("limit", limit).addValue("offset", offset)

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "sendId" to rs.getLong("send_id"),
                "ts" to Rs.dateTime(rs, "sent_at"),
                "condId" to Rs.intOrNull(rs, "cond_id"),
                "condNm" to rs.getString("cond_nm"),
                "alertId" to rs.getLong("alert_id"),
                "alertTitle" to rs.getString("title"),
                "channel" to (rs.getString("channel_nm") ?: rs.getString("channel_cd")),
                "recipient" to (rs.getString("user_nm") ?: rs.getString("dest_addr")),
                "result" to (rs.getString("result_nm") ?: rs.getString("send_result_cd")),
                "failReason" to rs.getString("fail_reason"),
                "escLevel" to rs.getInt("esc_level"),
                "proxy" to rs.getBoolean("is_proxy"),
                "test" to Rs.yn(rs, "test_flg"),
                "delaySec" to Rs.doubleOrNull(rs, "delay_sec")?.toInt()
            )
        }
    }

    /** 알림 발송 로그 전체 건수 */
    fun countSendLogs(from: LocalDate, to: LocalDate, condId: Int?, channel: String?): Long {
        val sql = StringBuilder(
            """
            SELECT count(*)
            FROM ax.tb_alm_send_log s
            INNER JOIN ax.tb_alm_alert a ON a.alert_id = s.alert_id
            WHERE s.sent_at >= :from
              AND s.sent_at <  :toExclusive
            """.trimIndent()
        )

        val params = MapSqlParameterSource()
            .addValue("from", from.atStartOfDay())
            .addValue("toExclusive", to.plusDays(1).atStartOfDay())

        appendSendLogFilters(sql, params, condId, channel)

        return jdbcTemplate.queryForObject(sql.toString(), params, Long::class.java) ?: 0L
    }

    /** 발송 로그 공통 동적 조건 */
    private fun appendSendLogFilters(
        sql: StringBuilder,
        params: MapSqlParameterSource,
        condId: Int?,
        channel: String?
    ) {
        if (condId != null) {
            sql.append(" AND a.cond_id = :condId")
            params.addValue("condId", condId)
        }
        if (!channel.isNullOrBlank()) {
            sql.append(" AND s.channel_cd = :channel")
            params.addValue("channel", channel.trim().uppercase())
        }
    }

    /**
     * 테스트 발송용 알림을 생성한다. (05 ALC-03 / 06 RCP-03)
     *
     * 실제 이상 상황이 아니므로 확인 상태를 즉시 CLOSED 로 두고 `test_flg='Y'` 로 운영 알림과 가른다.
     * `dedup_key`(`TEST|COND|id`·`TEST|GROUP|id`)는 연타 방지 판정에 쓴다 — 엔진 키(`{condId}|{scopeKey}`)와 겹치지 않는다.
     */
    fun insertTestAlert(condId: Int?, severityCd: String, title: String, targetDesc: String?, dedupKey: String): Long {
        val sql = """
            INSERT INTO ax.tb_alm_alert (
                cond_id, severity_cd, title, occurred_at, target_desc, ack_state_cd, evidence_desc, test_flg, dedup_key
            ) VALUES (
                :condId, :severityCd, :title, now(), :targetDesc, 'CLOSED', '테스트 발송', 'Y', :dedupKey
            )
            RETURNING alert_id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("condId", condId)
            .addValue("severityCd", severityCd)
            .addValue("title", title.take(200))
            .addValue("targetDesc", targetDesc?.take(200))
            .addValue("dedupKey", dedupKey)

        return jdbcTemplate.queryForObject(sql, params, Long::class.java) ?: 0L
    }

    /**
     * 테스트 발송 연타 방지 — 같은 키를 트랜잭션 잠금으로 줄 세운 뒤 60초 안의 테스트 알림이 있는지 본다.
     *
     * @return 60초 안에 같은 키의 테스트 알림이 있으면 true
     */
    fun lockAndCheckRecentTest(dedupKey: String): Boolean {
        val params = MapSqlParameterSource("key", "alm-test:$dedupKey")
        jdbcTemplate.queryForList("SELECT pg_advisory_xact_lock(hashtext(:key))::text AS locked", params)
        return jdbcTemplate.queryForObject(
            """
            SELECT exists(
                SELECT 1 FROM ax.tb_alm_alert
                 WHERE test_flg = 'Y' AND dedup_key = :dedupKey
                   AND occurred_at > now() - interval '60 seconds'
            )
            """.trimIndent(),
            MapSqlParameterSource("dedupKey", dedupKey),
            Boolean::class.java
        ) ?: false
    }

    /**
     * 발송 대기열에 1건 넣는다. 실제 발송·발송 로그 기록은 Alert_Engine `SendDispatcher` 가 한다.
     *
     * 같은 사람이 두 그룹에 있어도 `ux_alm_send_queue_once` 로 한 행만 남는다.
     *
     * @return 실제로 들어간 행 수(0 또는 1)
     */
    fun enqueueSend(
        alertId: Long,
        groupId: Int?,
        userId: String,
        channelCd: String,
        destAddr: String,
        subject: String,
        body: String
    ): Int {
        val sql = """
            INSERT INTO ax.tb_alm_send_queue (
                alert_id, group_id, user_id, channel_cd, dest_addr, subject, body, esc_level, is_proxy
            ) VALUES (
                :alertId, :groupId, :userId, :channelCd, :destAddr, :subject, :body, 0, false
            )
            ON CONFLICT DO NOTHING
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("alertId", alertId)
            .addValue("groupId", groupId)
            .addValue("userId", userId)
            .addValue("channelCd", channelCd)
            .addValue("destAddr", destAddr.take(200))
            .addValue("subject", subject.take(300))
            .addValue("body", body)

        return jdbcTemplate.update(sql, params)
    }

    /** 조회 기간 공통 파라미터 */
    private fun periodParams(plantCd: String, from: LocalDate, to: LocalDate): MapSqlParameterSource =
        MapSqlParameterSource()
            .addValue("plantCd", plantCd)
            .addValue("from", from.atStartOfDay())
            .addValue("toExclusive", to.plusDays(1).atStartOfDay())
}
