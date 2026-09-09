package com.dwje.api.repository

import com.dwje.api.common.util.DateUtils
import com.dwje.api.common.util.Rs
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.LocalDate

/**
 * 제품·공정별 일목표 마스터 Repository (`ax.tb_prod_day_target`)
 *
 * 목표는 적용일부터 **다음 적용일 전까지** 유효하다. 종료일을 두지 않으므로
 * 어느 날짜의 목표는 "그 날짜 이하의 적용일 중 가장 늦은 것" 한 건이다.
 * (`DISTINCT ON` + `ORDER BY apply_from DESC`)
 */
@Repository
class DayTargetRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) {

    /**
     * 등록된 목표를 조회한다. (이력 포함)
     *
     * @param date 지정하면 **그 날짜에 유효한 한 건씩만** 돌려준다. 미지정 시 전 이력.
     */
    fun findTargets(
        plantCd: String,
        product: String?,
        wcCd: String?,
        date: LocalDate?,
        limit: Int,
        offset: Int
    ): List<Map<String, Any?>> {
        val filters = buildFilters(product, wcCd)

        // date 가 있으면 제품×공정마다 그 날짜에 유효한 최신 한 건만 고른다.
        val sql = if (date == null) {
            """
            SELECT t.target_id, t.product, t.wc_cd, coalesce(w.wc_nm, t.wc_cd) AS wc_nm,
                   t.apply_from, t.target_qty, t.remark, t.upd_date, t.upd_user
            FROM ax.tb_prod_day_target t
            LEFT JOIN mes.tb_md_workcenter w
                   ON w.plant_cd = t.plant_cd AND w.wc_cd = t.wc_cd
            WHERE t.plant_cd = :plantCd
              $filters
            ORDER BY t.product, t.wc_cd, t.apply_from DESC
            LIMIT :limit OFFSET :offset
            """.trimIndent()
        } else {
            """
            SELECT t.target_id, t.product, t.wc_cd, coalesce(w.wc_nm, t.wc_cd) AS wc_nm,
                   t.apply_from, t.target_qty, t.remark, t.upd_date, t.upd_user
            FROM (
                SELECT DISTINCT ON (product, wc_cd) *
                FROM ax.tb_prod_day_target
                WHERE plant_cd = :plantCd
                  AND apply_from <= :date
                ORDER BY product, wc_cd, apply_from DESC
            ) t
            LEFT JOIN mes.tb_md_workcenter w
                   ON w.plant_cd = t.plant_cd AND w.wc_cd = t.wc_cd
            WHERE 1 = 1
              $filters
            ORDER BY t.product, t.wc_cd
            LIMIT :limit OFFSET :offset
            """.trimIndent()
        }

        val params = baseParams(plantCd, product, wcCd, date)
            .addValue("limit", limit).addValue("offset", offset)

        return jdbcTemplate.query(sql, params) { rs, _ ->
            mapOf(
                "targetId" to rs.getLong("target_id"),
                "product" to rs.getString("product"),
                "processId" to rs.getString("wc_cd"),
                "processNm" to rs.getString("wc_nm"),
                "applyFrom" to rs.getDate("apply_from")?.toLocalDate()?.format(DateUtils.DATE),
                "targetQty" to Rs.qty(rs, "target_qty"),
                "remark" to rs.getString("remark"),
                "updDate" to Rs.dateTime(rs, "upd_date"),
                "updUser" to rs.getString("upd_user")
            )
        }
    }

    /** 조회 조건에 맞는 전체 건수. */
    fun countTargets(plantCd: String, product: String?, wcCd: String?, date: LocalDate?): Long {
        val filters = buildFilters(product, wcCd)
        val sql = if (date == null) {
            """
            SELECT count(*) FROM ax.tb_prod_day_target t
            WHERE t.plant_cd = :plantCd
              $filters
            """.trimIndent()
        } else {
            """
            SELECT count(*) FROM (
                SELECT DISTINCT ON (product, wc_cd) *
                FROM ax.tb_prod_day_target
                WHERE plant_cd = :plantCd AND apply_from <= :date
                ORDER BY product, wc_cd, apply_from DESC
            ) t
            WHERE 1 = 1
              $filters
            """.trimIndent()
        }
        return jdbcTemplate.queryForObject(sql, baseParams(plantCd, product, wcCd, date), Long::class.java) ?: 0L
    }

    /**
     * 대상일에 유효한 목표를 **제품+공정 키로** 찾아 돌려준다.
     *
     * 일일 보고 양식 본문이 목표 밑값으로 쓴다.
     *
     * @return `"제품|공정" -> 목표 수량`
     */
    fun findEffectiveTargets(plantCd: String, date: LocalDate, wcCds: List<String>): Map<String, Long> {
        val wcFilter = if (wcCds.isEmpty()) "" else "AND wc_cd = ANY(:wcCds)"

        val sql = """
            SELECT DISTINCT ON (product, wc_cd) product, wc_cd, target_qty
            FROM ax.tb_prod_day_target
            WHERE plant_cd = :plantCd
              AND apply_from <= :date
              $wcFilter
            ORDER BY product, wc_cd, apply_from DESC
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("plantCd", plantCd)
            .addValue("date", date)
        if (wcCds.isNotEmpty()) params.addValue("wcCds", wcCds.toTypedArray())

        return jdbcTemplate.query(sql, params) { rs, _ ->
            "${rs.getString("product")}|${rs.getString("wc_cd")}" to (Rs.qty(rs, "target_qty") ?: 0L)
        }.toMap()
    }

    fun findById(targetId: Long): Map<String, Any?>? =
        jdbcTemplate.query(
            """
            SELECT target_id, plant_cd, product, wc_cd, apply_from, target_qty, remark
            FROM ax.tb_prod_day_target WHERE target_id = :targetId
            """.trimIndent(),
            MapSqlParameterSource("targetId", targetId)
        ) { rs, _ ->
            mapOf<String, Any?>(
                "targetId" to rs.getLong("target_id"),
                "product" to rs.getString("product"),
                "processId" to rs.getString("wc_cd"),
                "applyFrom" to rs.getDate("apply_from")?.toLocalDate()?.format(DateUtils.DATE),
                "targetQty" to Rs.qty(rs, "target_qty")
            )
        }.firstOrNull()

    /** 같은 제품·공정·적용일이 이미 있는지 본다. (`targetId` 는 자기 자신 제외) */
    fun existsSameKey(
        plantCd: String,
        product: String,
        wcCd: String,
        applyFrom: LocalDate,
        exceptId: Long?
    ): Boolean {
        val sql = """
            SELECT count(*) FROM ax.tb_prod_day_target
            WHERE plant_cd = :plantCd AND product = :product
              AND wc_cd = :wcCd AND apply_from = :applyFrom
              AND (:exceptId::bigint IS NULL OR target_id <> :exceptId)
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("plantCd", plantCd)
            .addValue("product", product)
            .addValue("wcCd", wcCd)
            .addValue("applyFrom", applyFrom)
            .addValue("exceptId", exceptId)

        return (jdbcTemplate.queryForObject(sql, params, Long::class.java) ?: 0L) > 0
    }

    fun insert(
        plantCd: String,
        product: String,
        wcCd: String,
        applyFrom: LocalDate,
        targetQty: Long,
        remark: String?,
        actor: String
    ): Long {
        val sql = """
            INSERT INTO ax.tb_prod_day_target (
                plant_cd, product, wc_cd, apply_from, target_qty, remark, ins_user
            ) VALUES (
                :plantCd, :product, :wcCd, :applyFrom, :targetQty, :remark, :actor
            )
            RETURNING target_id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("plantCd", plantCd)
            .addValue("product", product)
            .addValue("wcCd", wcCd)
            .addValue("applyFrom", applyFrom)
            .addValue("targetQty", targetQty)
            .addValue("remark", remark?.take(500))
            .addValue("actor", actor)

        return jdbcTemplate.queryForObject(sql, params, Long::class.java) ?: 0L
    }

    fun update(
        targetId: Long,
        applyFrom: LocalDate,
        targetQty: Long,
        remark: String?,
        actor: String
    ): Int {
        val sql = """
            UPDATE ax.tb_prod_day_target
               SET apply_from = :applyFrom,
                   target_qty = :targetQty,
                   remark     = :remark,
                   upd_date   = now(),
                   upd_user   = :actor
             WHERE target_id = :targetId
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("targetId", targetId)
            .addValue("applyFrom", applyFrom)
            .addValue("targetQty", targetQty)
            .addValue("remark", remark?.take(500))
            .addValue("actor", actor)

        return jdbcTemplate.update(sql, params)
    }

    fun delete(targetId: Long): Int =
        jdbcTemplate.update(
            "DELETE FROM ax.tb_prod_day_target WHERE target_id = :targetId",
            MapSqlParameterSource("targetId", targetId)
        )

    private fun buildFilters(product: String?, wcCd: String?): String = buildString {
        if (product != null) append("\n              AND t.product = :product")
        if (wcCd != null) append("\n              AND t.wc_cd = :wcCd")
    }

    private fun baseParams(
        plantCd: String,
        product: String?,
        wcCd: String?,
        date: LocalDate?
    ): MapSqlParameterSource = MapSqlParameterSource()
        .addValue("plantCd", plantCd)
        .apply {
            if (product != null) addValue("product", product)
            if (wcCd != null) addValue("wcCd", wcCd)
            if (date != null) addValue("date", date)
        }
}
