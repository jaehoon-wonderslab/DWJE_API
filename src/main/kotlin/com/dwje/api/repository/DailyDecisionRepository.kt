package com.dwje.api.repository

import com.dwje.api.common.util.DateUtils
import com.dwje.api.common.util.Rs
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.LocalDate

/**
 * 일일 생산현황 보고 아침회의 결과 Repository (`ax.tb_prod_daily_decision`)
 *
 * 문서 관리가 제거되어 보고서는 조회 조건으로 매번 만들어 내려받는 산출물이 되었다.
 * 그런데 회의에서 정한 제품별 일목표·판정·담당·기한은 산출물이 아니라 사람이 남기는
 * 결정이라 따로 남는다. 그래서 키가 문서(doc_id)가 아니라 **(대상일, 제품)** 이다.
 */
@Repository
class DailyDecisionRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) {

    /**
     * 회의 결과를 저장한다.
     *
     * 보낸 제품만 갱신한다 — 화면이 한 줄만 고쳐 보낼 수 있어야 하고,
     * 전체를 지우고 다시 넣으면 다른 사람이 방금 채운 줄이 사라진다.
     *
     * 값에 `null` 을 보내면 그 칸을 비우는 뜻으로 본다. (판정 취소 등)
     */
    fun upsertRows(targetDate: LocalDate, rows: List<DailyDecisionRow>, actor: String) {
        if (rows.isEmpty()) return

        val sql = """
            INSERT INTO ax.tb_prod_daily_decision (
                target_date, product, target_qty, decision, dri, due_date, ins_user
            ) VALUES (
                :targetDate, :product, :targetQty, :decision, :dri, :dueDate, :actor
            )
            ON CONFLICT (target_date, product) DO UPDATE SET
                target_qty = EXCLUDED.target_qty,
                decision   = EXCLUDED.decision,
                dri        = EXCLUDED.dri,
                due_date   = EXCLUDED.due_date,
                upd_date   = now(),
                upd_user   = :actor
        """.trimIndent()

        val batch = rows.map { r ->
            MapSqlParameterSource()
                .addValue("targetDate", targetDate)
                .addValue("product", r.product)
                .addValue("targetQty", r.targetQty)
                .addValue("decision", r.decision)
                .addValue("dri", r.dri)
                .addValue("dueDate", r.dueDate)
                .addValue("actor", actor)
        }.toTypedArray()

        jdbcTemplate.batchUpdate(sql, batch)
    }

    /**
     * 대상일의 회의 결과를 제품 코드로 찾아 돌려준다.
     */
    fun findRows(targetDate: LocalDate): Map<String, Map<String, Any?>> {
        val sql = """
            SELECT product, target_qty, decision, dri, due_date
            FROM ax.tb_prod_daily_decision
            WHERE target_date = :targetDate
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource("targetDate", targetDate)) { rs, _ ->
            rs.getString("product") to mapOf<String, Any?>(
                "targetQty" to Rs.qty(rs, "target_qty"),
                "decision" to rs.getString("decision"),
                "dri" to rs.getString("dri"),
                "due" to rs.getDate("due_date")?.toLocalDate()?.format(DateUtils.DATE)
            )
        }.toMap()
    }
}

/**
 * 회의 결과 한 줄 — 저장용 값 객체
 *
 * 요청 DTO 를 Repository 까지 내려보내지 않는다. 검증·기본값 정리는 서비스가 하고,
 * 여기에는 이미 확인된 값만 온다.
 */
data class DailyDecisionRow(
    val product: String,
    val targetQty: Long?,
    val decision: String?,
    val dri: String?,
    val dueDate: LocalDate?
)
