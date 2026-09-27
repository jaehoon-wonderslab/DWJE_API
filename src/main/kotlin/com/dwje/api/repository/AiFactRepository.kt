package com.dwje.api.repository

import com.dwje.api.common.util.AiBusinessPeriod
import com.dwje.api.common.util.DefectSql
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** AI가 사용할 수 있는 고정 SELECT. 사용자 문장을 SQL에 넣지 않는다. */
@Repository
class AiFactRepository(private val jdbc: NamedParameterJdbcTemplate) {
    /** 일자×모델×불량 유형. 라벨 기간을 먼저 제한하고 유형을 전체 LOT 키로 결합한다. */
    fun dailyProductDefects(plantCd: String, period: AiBusinessPeriod, limit: Int): List<Map<String, Any?>> {
        require(ChronoUnit.DAYS.between(period.from, period.to) < 31 && limit in 1..200)
        val sql = """
            WITH labels AS (
                SELECT lh.plant_cd, lh.wc_cd, lh.lot_no, lh.serial_no, lh.item_cd,
                       to_char(lh.ins_date + ${com.dwje.api.common.util.BusinessDay.BUCKET_SHIFT_SQL}, 'YYYY-MM-DD') AS work_date,
                       coalesce(lh.defect, 0) AS ng_qty
                FROM mes.tb_pop_label_hist lh
                WHERE lh.plant_cd = :plantCd AND lh.del_flg = 'N'
                  AND lh.ins_date >= :from AND lh.ins_date < :toExclusive
                  AND coalesce(lh.defect, 0) > 0
            ), mapped AS (
                SELECT l.*, coalesce(p.model_cd, l.item_cd) AS model_cd,
                       coalesce(p.model_nm, l.item_cd) AS model_nm, f.family_nm
                FROM labels l
                LEFT JOIN ax.tb_prod_item_map pm ON pm.plant_cd = l.plant_cd AND pm.item_cd = l.item_cd
                LEFT JOIN ax.tb_prod_product p ON p.product_id = pm.product_id
                LEFT JOIN ax.tb_prod_family f ON f.family_id = p.family_id
            ), type_join AS (
                SELECT l.work_date, l.model_cd, l.model_nm, l.family_nm, l.plant_cd,
                       l.wc_cd, l.lot_no, l.serial_no, l.ng_qty, dh.defect_cd,
                       sum(dh.qty) AS type_qty,
                       sum(sum(dh.qty)) OVER (
                           PARTITION BY l.plant_cd, l.wc_cd, l.lot_no, l.serial_no
                       ) AS label_type_total
                FROM mapped l
                INNER JOIN mes.tb_pop_defect_hist dh
                  ON dh.plant_cd = l.plant_cd AND dh.wc_cd = l.wc_cd
                 AND dh.lot_no = l.lot_no AND dh.serial_no = l.serial_no
                 ${DefectSql.excludeNonProduction()}
                GROUP BY l.work_date, l.model_cd, l.model_nm, l.family_nm, l.plant_cd,
                         l.wc_cd, l.lot_no, l.serial_no, l.ng_qty, dh.defect_cd
            ), typed AS (
                SELECT work_date, model_cd, model_nm, family_nm, defect_cd,
                       sum(ng_qty * type_qty / nullif(label_type_total, 0)) AS ng_qty
                FROM type_join
                GROUP BY work_date, model_cd, model_nm, family_nm, defect_cd
            ), totals AS (
                SELECT work_date, model_cd, model_nm, family_nm, sum(ng_qty) AS ng_qty
                FROM mapped GROUP BY work_date, model_cd, model_nm, family_nm
            ), untyped AS (
                SELECT t.work_date, t.model_cd, t.model_nm, t.family_nm,
                       NULL::text AS defect_cd, t.ng_qty - coalesce(sum(y.ng_qty), 0) AS ng_qty
                FROM totals t LEFT JOIN typed y ON y.work_date = t.work_date
                 AND y.model_cd = t.model_cd AND y.model_nm = t.model_nm
                 AND y.family_nm IS NOT DISTINCT FROM t.family_nm
                GROUP BY t.work_date, t.model_cd, t.model_nm, t.family_nm, t.ng_qty
                HAVING t.ng_qty - coalesce(sum(y.ng_qty), 0) > 0
            ), combined AS (
                SELECT * FROM typed UNION ALL SELECT * FROM untyped
            )
            SELECT c.work_date, c.model_cd, c.model_nm, c.family_nm, c.defect_cd,
                   coalesce(md.defect_nm, c.defect_cd, '${DefectSql.UNTYPED_LABEL}') AS defect_nm,
                   c.ng_qty, count(*) OVER () AS total_count
            FROM combined c
            LEFT JOIN mes.tb_md_defect md ON md.plant_cd = :plantCd AND md.defect_cd = c.defect_cd
            WHERE c.ng_qty > 0
            ORDER BY c.work_date ASC, c.model_cd ASC, defect_nm ASC, c.defect_cd ASC NULLS LAST
            LIMIT :limit
        """.trimIndent()
        return jdbc.query(sql, periodParams(plantCd, period).addValue("limit", limit)) { rs, _ ->
            mapOf("date" to rs.getString("work_date"), "code" to rs.getString("model_cd"),
                "name" to rs.getString("model_nm"), "family" to rs.getString("family_nm"),
                "defectCode" to rs.getString("defect_cd"), "defect" to rs.getString("defect_nm"),
                "quantity" to rs.getBigDecimal("ng_qty"), "totalCount" to rs.getLong("total_count"))
        }
    }
    /** 날짜를 먼저 제한하고 제품 매핑 후 제품 코드순으로 최대 100개를 낸다. */
    fun producedProducts(plantCd: String, period: AiBusinessPeriod): List<Map<String, Any?>> {
        val sql = """
            SELECT coalesce(p.model_cd, lh.item_cd) AS model_cd,
                   max(coalesce(p.model_nm, lh.item_cd)) AS model_nm,
                   sum(coalesce(lh.normal, 0) + coalesce(lh.defect, 0)) AS qty,
                   count(*) OVER () AS total_count
            FROM mes.tb_pop_label_hist lh
            LEFT JOIN ax.tb_prod_item_map pm
              ON pm.plant_cd = lh.plant_cd AND pm.item_cd = lh.item_cd
            LEFT JOIN ax.tb_prod_product p ON p.product_id = pm.product_id
            WHERE lh.plant_cd = :plantCd AND lh.del_flg = 'N'
              AND lh.ins_date >= :from AND lh.ins_date < :toExclusive
            GROUP BY coalesce(p.model_cd, lh.item_cd)
            ORDER BY model_cd ASC
            LIMIT 100
        """.trimIndent()
        return jdbc.query(sql, periodParams(plantCd, period)) { rs, _ ->
            mapOf("code" to rs.getString("model_cd"), "name" to rs.getString("model_nm"),
                "quantity" to rs.getBigDecimal("qty"), "totalCount" to rs.getLong("total_count"))
        }
    }

    /** 날짜별 생산 제품 목록과 생산량 (최대 200건). */
    fun producedProductsDaily(plantCd: String, period: AiBusinessPeriod): List<Map<String, Any?>> {
        val sql = """
            SELECT to_char(lh.ins_date + ${com.dwje.api.common.util.BusinessDay.BUCKET_SHIFT_SQL}, 'YYYY-MM-DD') AS work_date,
                   coalesce(p.model_cd, lh.item_cd) AS model_cd,
                   max(coalesce(p.model_nm, lh.item_cd)) AS model_nm,
                   sum(coalesce(lh.normal, 0) + coalesce(lh.defect, 0)) AS qty,
                   count(*) OVER () AS total_count
            FROM mes.tb_pop_label_hist lh
            LEFT JOIN ax.tb_prod_item_map pm
              ON pm.plant_cd = lh.plant_cd AND pm.item_cd = lh.item_cd
            LEFT JOIN ax.tb_prod_product p ON p.product_id = pm.product_id
            WHERE lh.plant_cd = :plantCd AND lh.del_flg = 'N'
              AND lh.ins_date >= :from AND lh.ins_date < :toExclusive
            GROUP BY to_char(lh.ins_date + ${com.dwje.api.common.util.BusinessDay.BUCKET_SHIFT_SQL}, 'YYYY-MM-DD'), coalesce(p.model_cd, lh.item_cd)
            ORDER BY work_date ASC, model_cd ASC
            LIMIT 200
        """.trimIndent()
        return jdbc.query(sql, periodParams(plantCd, period)) { rs, _ ->
            mapOf("date" to rs.getString("work_date"), "code" to rs.getString("model_cd"),
                "name" to rs.getString("model_nm"), "quantity" to rs.getBigDecimal("qty"),
                "totalCount" to rs.getLong("total_count"))
        }
    }

    /** 제품별 가중 불량률 순위. 0 생산량은 분모가 없어 제외한다. */
    fun topDefectRates(plantCd: String, period: AiBusinessPeriod, limit: Int): List<Map<String, Any?>> {
        require(limit in 1..20)
        val sql = """
            WITH product_qty AS (
                SELECT coalesce(p.model_cd, lh.item_cd) AS model_cd,
                       max(coalesce(p.model_nm, lh.item_cd)) AS model_nm,
                       sum(coalesce(lh.normal, 0) + coalesce(lh.defect, 0)) AS qty,
                       sum(coalesce(lh.defect, 0)) AS ng_qty
                FROM mes.tb_pop_label_hist lh
                LEFT JOIN ax.tb_prod_item_map pm
                  ON pm.plant_cd = lh.plant_cd AND pm.item_cd = lh.item_cd
                LEFT JOIN ax.tb_prod_product p ON p.product_id = pm.product_id
                WHERE lh.plant_cd = :plantCd AND lh.del_flg = 'N'
                  AND lh.ins_date >= :from AND lh.ins_date < :toExclusive
                GROUP BY coalesce(p.model_cd, lh.item_cd)
            )
            SELECT model_cd, model_nm, qty, ng_qty,
                   round(ng_qty * 100.0 / nullif(qty, 0), 2) AS defect_rate
            FROM product_qty
            WHERE qty > 0
            ORDER BY defect_rate DESC, ng_qty DESC, model_cd ASC
            LIMIT :limit
        """.trimIndent()
        return jdbc.query(sql, periodParams(plantCd, period).addValue("limit", limit)) { rs, _ ->
            mapOf("code" to rs.getString("model_cd"), "name" to rs.getString("model_nm"),
                "quantity" to rs.getBigDecimal("qty"), "defectQuantity" to rs.getBigDecimal("ng_qty"),
                "defectRate" to rs.getBigDecimal("defect_rate"))
        }
    }

    private fun periodParams(plantCd: String, period: AiBusinessPeriod) = MapSqlParameterSource("plantCd", plantCd)
        .addValue("from", period.startInclusive)
        .addValue("toExclusive", period.endExclusive)

    fun topDefects(plantCd: String, period: AiBusinessPeriod, limit: Int): List<Map<String, Any?>> {
        require(limit in 1..10)
        val sql = """
            WITH ${DefectSql.labelLedgerCte("labels", "from", "toExclusive")},
                 ${DefectSql.apportionedTypeCte("types", "labels")}
            SELECT t.defect_cd, coalesce(md.defect_nm, t.defect_cd) AS defect_nm,
                   t.ng_qty
            FROM types t
            LEFT JOIN mes.tb_md_defect md
              ON md.plant_cd = :plantCd AND md.defect_cd = t.defect_cd
            WHERE t.ng_qty > 0
            ORDER BY t.ng_qty DESC, t.defect_cd ASC
            LIMIT :limit
        """.trimIndent()
        val params = MapSqlParameterSource("plantCd", plantCd)
            .addValue("from", period.startInclusive)
            .addValue("toExclusive", period.endExclusive)
            .addValue("limit", limit)
        return jdbc.query(sql, params) { rs, _ ->
            mapOf("name" to rs.getString("defect_nm"), "quantity" to rs.getBigDecimal("ng_qty"))
        }
    }

    /** vec 메타데이터의 문서 기준일. 중복 chunk를 문서 한 건으로 센다. */
    fun documentCount(year: Int, userId: String, byRegistration: Boolean, defectReports: Boolean): Map<String, Long> {
        val dateCondition = if (byRegistration) "d.ins_date >= :fromTime AND d.ins_date < :toTime" else
            "d.doc_date >= :from AND d.doc_date < :toExclusive"
        val typeCondition = if (defectReports) "AND d.doc_type_cd IN ('SCRAP', 'CLAIM')" else ""
        val sql = """
            SELECT count(DISTINCT d.doc_id) FILTER
                     (WHERE $dateCondition) AS dated_count,
                   count(DISTINCT d.doc_id) FILTER (WHERE d.doc_date IS NULL) AS undated_count
            FROM vec.tb_doc d
            INNER JOIN vec.fn_allowed_doc(:userId, false) allowed ON allowed.doc_id = d.doc_id
            WHERE d.del_flg = 'N' $typeCondition
        """.trimIndent()
        val zone = java.time.ZoneId.of("Asia/Seoul")
        return jdbc.queryForObject(sql, MapSqlParameterSource("from", LocalDate.of(year, 1, 1))
            .addValue("toExclusive", LocalDate.of(year + 1, 1, 1))
            .addValue("fromTime", LocalDate.of(year, 1, 1).atStartOfDay(zone).toOffsetDateTime())
            .addValue("toTime", LocalDate.of(year + 1, 1, 1).atStartOfDay(zone).toOffsetDateTime())
            .addValue("userId", userId)) { rs, _ ->
            mapOf("dated" to rs.getLong("dated_count"), "undated" to rs.getLong("undated_count"))
        } ?: mapOf("dated" to 0L, "undated" to 0L)
    }
}
