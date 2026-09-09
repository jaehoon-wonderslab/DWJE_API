package com.dwje.api.repository

import com.dwje.api.common.util.DateUtils
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.LocalDate

/**
 * 보고서 작성 상태 Repository (`ax.tb_rpt_write_state`, V23)
 *
 * 두 종류의 원천을 읽는다.
 * - **기록 상태** : 화면의 「제출」「승인」 단추가 남긴 값. 이 테이블이다.
 * - **파생 상태** : `ax.tb_prod_daily_decision` 에 사람이 적은 행이 있으면 "작성 중".
 *   문서 관리 제거(2026-09-04) 뒤 남은 유일한 사람 입력이라 여기서만 파생한다.
 */
@Repository
class ReportWriteStateRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) {

    /** 대상일에 기록된 상태를 화면 ID 로 찾아 돌려준다. */
    fun findRecorded(bizDate: LocalDate): Map<String, WriteStateRow> {
        val sql = """
            SELECT s.menu_id, s.state_cd, s.upd_date, s.upd_user, u.user_nm
            FROM ax.tb_rpt_write_state s
            LEFT JOIN ax.tb_sys_user u ON u.user_id = s.upd_user
            WHERE s.biz_date = :bizDate
        """.trimIndent()
        return jdbcTemplate.query(sql, MapSqlParameterSource("bizDate", bizDate)) { rs, _ ->
            WriteStateRow(
                menuId = rs.getString("menu_id"),
                state = rs.getString("state_cd"),
                updatedAt = DateUtils.format(rs.getObject("upd_date")),
                updatedBy = rs.getString("upd_user"),
                updatedByName = rs.getString("user_nm")
            )
        }.associateBy { it.menuId }
    }

    /** 화면·대상일 한 건의 기록 상태. 없으면 null. */
    fun findRecorded(menuId: String, bizDate: LocalDate): WriteStateRow? {
        val sql = """
            SELECT s.menu_id, s.state_cd, s.upd_date, s.upd_user, u.user_nm
            FROM ax.tb_rpt_write_state s
            LEFT JOIN ax.tb_sys_user u ON u.user_id = s.upd_user
            WHERE s.menu_id = :menuId AND s.biz_date = :bizDate
        """.trimIndent()
        val params = MapSqlParameterSource().addValue("menuId", menuId).addValue("bizDate", bizDate)
        return jdbcTemplate.query(sql, params) { rs, _ ->
            WriteStateRow(
                menuId = rs.getString("menu_id"),
                state = rs.getString("state_cd"),
                updatedAt = DateUtils.format(rs.getObject("upd_date")),
                updatedBy = rs.getString("upd_user"),
                updatedByName = rs.getString("user_nm")
            )
        }.firstOrNull()
    }

    /**
     * 상태를 기록한다. 있으면 덮어쓴다 — 낮추는 방향(APPROVED→DRAFT)도 그대로 받는다.
     */
    fun upsert(menuId: String, bizDate: LocalDate, state: String, actor: String) {
        val sql = """
            INSERT INTO ax.tb_rpt_write_state (menu_id, biz_date, state_cd, ins_user, upd_user)
            VALUES (:menuId, :bizDate, :state, :actor, :actor)
            ON CONFLICT (menu_id, biz_date) DO UPDATE SET
                state_cd = EXCLUDED.state_cd,
                upd_date = now(),
                upd_user = :actor
        """.trimIndent()
        val params = MapSqlParameterSource()
            .addValue("menuId", menuId)
            .addValue("bizDate", bizDate)
            .addValue("state", state)
            .addValue("actor", actor)
        jdbcTemplate.update(sql, params)
    }

    /**
     * 파생 근거 — 대상일의 아침회의 결과 행.
     *
     * 하루 행 수는 제품 수(수십 건)라 그대로 읽어 서비스에서 판단한다.
     * `hasDecision` 이 참인 행만 아침회의 결정으로 본다(`GET /reports/press-morning/decisions` 와 같은 기준).
     */
    fun findDecisionRows(bizDate: LocalDate): List<DecisionTrace> {
        val sql = """
            SELECT d.decision IS NOT NULL             AS has_decision,
                   coalesce(d.upd_date, d.ins_date)   AS touched_at,
                   coalesce(d.upd_user, d.ins_user)   AS touched_by,
                   u.user_nm
            FROM ax.tb_prod_daily_decision d
            LEFT JOIN ax.tb_sys_user u ON u.user_id = coalesce(d.upd_user, d.ins_user)
            WHERE d.target_date = :bizDate
            ORDER BY coalesce(d.upd_date, d.ins_date) DESC NULLS LAST
        """.trimIndent()
        return jdbcTemplate.query(sql, MapSqlParameterSource("bizDate", bizDate)) { rs, _ ->
            DecisionTrace(
                hasDecision = rs.getBoolean("has_decision"),
                touchedAt = DateUtils.format(rs.getObject("touched_at")),
                touchedBy = rs.getString("touched_by"),
                touchedByName = rs.getString("user_nm")
            )
        }
    }
}

/** 기록 상태 한 건 */
data class WriteStateRow(
    val menuId: String,
    val state: String,
    val updatedAt: String?,
    val updatedBy: String?,
    val updatedByName: String?
)

/** 파생 근거 한 행 — 누가 언제 건드렸는지와 판정이 적혔는지만 본다. */
data class DecisionTrace(
    val hasDecision: Boolean,
    val touchedAt: String?,
    val touchedBy: String?,
    val touchedByName: String?
)
