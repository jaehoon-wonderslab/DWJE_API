package com.dwje.api.repository

import com.dwje.api.common.util.DefectSql
import com.dwje.api.common.util.ProcessPeriod
import com.dwje.api.common.util.ProcessPeriodRow
import com.dwje.api.common.util.Rs
import com.dwje.api.common.util.safeRate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.LocalDate

/**
 * 공정 및 제품 대시보드 Repository (DB-02)
 *
 * MES 실적은 품목 코드(item_cd) 기준이고 화면은 제품 코드(model_cd) 기준이므로,
 * 제품-품목 매핑(ax.tb_prod_item_map)을 경유해 두 체계를 연결한다.
 *
 * 요약 지표는 단순 평균이 아닌 가중 평균(총불량 ÷ 총생산)으로 산출한다.
 */
@Repository
class DashboardProcessRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) {

    /** One range scan; selected sections and all-process comparison share one DB snapshot.
     * SUM includes collected values; groups with no collected values remain unknown.
     * Product mapping is unique on (plant_cd, item_cd); unregistered items stay in totals.
     */
    fun findPeriod(
        plantCd: String, range: ProcessPeriod, processId: String?, productCodes: List<String>
    ): List<Pair<String, ProcessPeriodRow>> {
        val productFilter = if (productCodes.isEmpty()) "" else "AND p.model_cd IN (:productCodes)"
        val sql = """
            WITH base AS MATERIALIZED (
                SELECT date_trunc(:unit, lh.ins_date)::date AS period,
                       lh.wc_cd, p.model_cd, p.model_nm, lh.normal, lh.defect
                FROM mes.tb_pop_label_hist lh
                LEFT JOIN ax.tb_prod_item_map pm
                  ON pm.plant_cd = lh.plant_cd AND pm.item_cd = lh.item_cd
                LEFT JOIN ax.tb_prod_product p ON p.product_id = pm.product_id
                WHERE lh.plant_cd = :plantCd AND lh.del_flg = 'N'
                  AND lh.ins_date >= :from AND lh.ins_date < :toExclusive
                  $productFilter
            ), selected AS (
                SELECT * FROM base WHERE (:processId::varchar IS NULL OR wc_cd = :processId)
            ), grouped AS (
                SELECT CASE WHEN grouping(period) = 0 THEN 'periods'
                            WHEN grouping(model_cd) = 0 THEN 'products' ELSE 'summary' END AS kind,
                       period, model_cd, max(model_nm) AS model_nm, NULL::varchar AS wc_cd,
                       CASE WHEN count(*) = 0 THEN 0 ELSE sum(normal) END AS ok_qty,
                       CASE WHEN count(*) = 0 THEN 0 ELSE sum(defect) END AS ng_qty
                FROM selected
                GROUP BY GROUPING SETS ((), (period), (model_cd))
                UNION ALL
                SELECT 'processes', NULL::date, NULL::varchar, NULL::varchar, wc_cd,
                       sum(normal),
                       sum(defect)
                FROM base GROUP BY wc_cd
            )
            SELECT g.*, w.wc_nm FROM grouped g
            LEFT JOIN mes.tb_md_workcenter w ON w.plant_cd = :plantCd AND w.wc_cd = g.wc_cd
            WHERE g.kind <> 'products' OR g.model_cd IS NOT NULL
            ORDER BY g.kind, g.period, g.model_cd, w.sort_seq NULLS LAST, g.wc_cd
        """.trimIndent()
        val params = MapSqlParameterSource("plantCd", plantCd)
            .addValue("from", range.from.atStartOfDay())
            .addValue("toExclusive", range.to.plusDays(1).atStartOfDay())
            .addValue("unit", range.unit).addValue("processId", processId)
            .addValue("productCodes", productCodes)
        return jdbcTemplate.query(sql, params) { rs, _ ->
            rs.getString("kind") to ProcessPeriodRow.of(rs.getBigDecimal("ok_qty"), rs.getBigDecimal("ng_qty")).copy(
                period = rs.getString("period"), code = rs.getString("model_cd"),
                productNm = if (rs.getString("kind") == "products") rs.getString("model_nm") ?: rs.getString("model_cd") else null,
                processId = rs.getString("wc_cd"), process = rs.getString("wc_nm") ?: rs.getString("wc_cd")
            )
        }
    }

    /**
     * 공정·제품 요약 지표를 조회한다. (No.33 — 가중 평균 산출)
     *
     * @param plantCd      사업장 코드
     * @param date         기준일
     * @param processId    공정 코드
     * @param productCodes 제품 코드 목록 (빈 목록이면 전체)
     */
    fun findSummary(
        plantCd: String,
        date: LocalDate,
        processId: String?,
        productCodes: List<String>
    ): Map<String, Any?> {
        val sql = StringBuilder(
            """
            SELECT
                coalesce(sum(lh.normal), 0)                                AS ok_qty,
                coalesce(sum(lh.defect), 0)                                AS ng_qty,
                coalesce(sum(lh.normal), 0) + coalesce(sum(lh.defect), 0)  AS total_qty,
                count(DISTINCT pm.product_id)                              AS product_cnt
            FROM mes.tb_pop_label_hist lh
            LEFT JOIN ax.tb_prod_item_map pm
                   ON pm.plant_cd = lh.plant_cd AND pm.item_cd = lh.item_cd
            LEFT JOIN ax.tb_prod_product p
                   ON p.product_id = pm.product_id
            WHERE lh.plant_cd  = :plantCd
              AND lh.del_flg   = 'N'
              AND lh.ins_date >= :dayStart
              AND lh.ins_date <  :dayEnd
            """.trimIndent()
        )

        val params = dayParams(plantCd, date)
        appendProcessProductFilter(sql, params, processId, productCodes)

        return jdbcTemplate.queryForObject(sql.toString(), params) { rs, _ ->
            val ok = rs.getBigDecimal("ok_qty")
            val ng = rs.getBigDecimal("ng_qty")
            val total = rs.getBigDecimal("total_qty")
            mapOf(
                "qty" to Rs.qty(rs, "total_qty"),
                "okQty" to Rs.qty(rs, "ok_qty"),
                "ngQty" to Rs.qty(rs, "ng_qty"),
                // 가중 평균 : 총불량 ÷ 총생산 (제품별 불량률의 단순 평균이 아니다)
                "defectRate" to safeRate(ng, total),
                "yield" to safeRate(ok, total),
                "productCnt" to rs.getLong("product_cnt")
            )
        } ?: emptyMap()
    }

    /**
     * 선택 범위의 평균 가동률을 조회한다. (No.33 — avgUptime)
     */
    fun findAverageUptime(plantCd: String, date: LocalDate, processId: String?): Double? {
        val sql = StringBuilder(
            """
            SELECT round(avg(mv.metric_value), 2) AS uptime_rate
            FROM ax.tb_met_metric_value mv
            INNER JOIN ax.tb_met_metric_std ms ON ms.metric_id = mv.metric_id
            WHERE ms.metric_cd    = 'EQPT_UPTIME_RATE'
              AND mv.measured_at >= :dayStart
              AND mv.measured_at <  :dayEnd
              AND (mv.plant_cd IS NULL OR mv.plant_cd = :plantCd)
            """.trimIndent()
        )

        val params = dayParams(plantCd, date)
        if (!processId.isNullOrBlank()) {
            sql.append(" AND mv.wc_cd = :processId")
            params.addValue("processId", processId.trim())
        }

        return jdbcTemplate.query(sql.toString(), params) { rs, _ -> Rs.rate(rs, "uptime_rate") }.firstOrNull()
    }

    /**
     * 시간대별 불량률 추이를 조회한다. (No.34)
     */
    fun findDefectTrend(
        plantCd: String,
        date: LocalDate,
        processId: String?,
        productCodes: List<String>,
        intervalHour: Int
    ): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT
                to_char(
                    date_trunc('hour', lh.ins_date)
                    - make_interval(hours => (extract(hour FROM lh.ins_date)::int % :intervalHour)),
                    'HH24:MI'
                )                                                            AS slot,
                min(lh.ins_date)                                             AS slot_at,
                coalesce(sum(lh.normal), 0) + coalesce(sum(lh.defect), 0)    AS total_qty,
                coalesce(sum(lh.defect), 0)                                  AS ng_qty
            FROM mes.tb_pop_label_hist lh
            LEFT JOIN ax.tb_prod_item_map pm
                   ON pm.plant_cd = lh.plant_cd AND pm.item_cd = lh.item_cd
            LEFT JOIN ax.tb_prod_product p ON p.product_id = pm.product_id
            WHERE lh.plant_cd  = :plantCd
              AND lh.del_flg   = 'N'
              AND lh.ins_date >= :dayStart
              AND lh.ins_date <  :dayEnd
            """.trimIndent()
        )

        val params = dayParams(plantCd, date).addValue("intervalHour", intervalHour)
        appendProcessProductFilter(sql, params, processId, productCodes)

        sql.append("\nGROUP BY 1\nORDER BY min(lh.ins_date)")

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "slot" to rs.getString("slot"),
                "defectRate" to safeRate(rs.getBigDecimal("ng_qty"), rs.getBigDecimal("total_qty")),
                "totalQty" to Rs.qty(rs, "total_qty"),
                "ngQty" to Rs.qty(rs, "ng_qty")
            )
        }
    }

    /**
     * 제품별 생산량·불량률·수율을 조회한다. (No.35 / No.37 / No.41)
     *
     * @param orderByClause 정렬 절 (화이트리스트 검증 완료)
     */
    fun findProductProduction(
        plantCd: String,
        date: LocalDate,
        processId: String?,
        productCodes: List<String>,
        orderByClause: String
    ): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT
                p.product_id,
                p.model_cd,
                p.model_nm,
                f.family_nm,
                c.customer_nm,
                pj.project_nm,
                p.rank_no,
                coalesce(sum(lh.normal), 0)                                AS ok_qty,
                coalesce(sum(lh.defect), 0)                                AS ng_qty,
                coalesce(sum(lh.normal), 0) + coalesce(sum(lh.defect), 0)  AS total_qty
            FROM mes.tb_pop_label_hist lh
            INNER JOIN ax.tb_prod_item_map pm
                    ON pm.plant_cd = lh.plant_cd AND pm.item_cd = lh.item_cd
            INNER JOIN ax.tb_prod_product p  ON p.product_id  = pm.product_id
            INNER JOIN ax.tb_prod_family  f  ON f.family_id   = p.family_id
            LEFT  JOIN ax.tb_prod_customer c ON c.customer_id = p.customer_id
            LEFT  JOIN ax.tb_prod_project pj ON pj.project_id = p.project_id
            WHERE lh.plant_cd  = :plantCd
              AND lh.del_flg   = 'N'
              AND lh.ins_date >= :dayStart
              AND lh.ins_date <  :dayEnd
            """.trimIndent()
        )

        val params = dayParams(plantCd, date)
        appendProcessProductFilter(sql, params, processId, productCodes)

        sql.append("\nGROUP BY p.product_id, p.model_cd, p.model_nm, f.family_nm, c.customer_nm, pj.project_nm, p.rank_no")
        sql.append("\n").append(orderByClause)

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            val ok = rs.getBigDecimal("ok_qty")
            val ng = rs.getBigDecimal("ng_qty")
            val total = rs.getBigDecimal("total_qty")
            mapOf(
                "productId" to rs.getInt("product_id"),
                "product" to rs.getString("model_cd"),
                "productNm" to rs.getString("model_nm"),
                "family" to rs.getString("family_nm"),
                "customer" to rs.getString("customer_nm"),
                "project" to rs.getString("project_nm"),
                "rank" to Rs.intOrNull(rs, "rank_no"),
                "qty" to Rs.qty(rs, "total_qty"),
                "okQty" to Rs.qty(rs, "ok_qty"),
                "ngQty" to Rs.qty(rs, "ng_qty"),
                "defectRate" to safeRate(ng, total),
                "yield" to safeRate(ok, total)
            )
        }
    }

    /**
     * 불량 유형 구성을 조회한다. (No.36)
     */
    fun findDefectComposition(
        plantCd: String,
        date: LocalDate,
        processId: String?,
        productCodes: List<String>
    ): List<Map<String, Any?>> {
        val params = dayParams(plantCd, date)
        val labelFilter = defectLabelFilter(params, processId, productCodes)

        // 기간은 라벨 이력에만 걸고, 라벨 불량 수량을 유형 구성비로 안분한다.
        // (MES_QUERY_GUIDE 2-3 / 2-4)
        val sql = """
            WITH
            ${DefectSql.labelLedgerCte("cur_label", "dayStart", "dayEnd", labelFilter)},
            ${DefectSql.apportionedTypeCte("cur", "cur_label")}
            SELECT
                cur.defect_cd,
                coalesce(md.defect_nm, cur.defect_cd) AS defect_nm,
                cur.ng_qty
            FROM cur
            LEFT JOIN mes.tb_md_defect md
                   ON md.plant_cd = :plantCd AND md.defect_cd = cur.defect_cd
            ORDER BY cur.ng_qty DESC
        """.trimIndent()

        return jdbcTemplate.query(sql, params) { rs, _ ->
            mapOf(
                "code" to rs.getString("defect_cd"),
                "label" to rs.getString("defect_nm"),
                "value" to Rs.qty(rs, "ng_qty")
            )
        }
    }

    /**
     * 해당 일자의 라벨 원장 불량 총량을 조회한다. (No.36 — 유형 구성비의 분모)
     *
     * [findDefectComposition] 의 유형 합계는 유형이 붙지 않은 불량이 빠져 이 값보다 작다.
     * 표시된 유형만으로 분모를 잡으면 비중이 부풀려지므로 분모는 이 값을 쓴다.
     */
    fun findDefectLedgerTotal(
        plantCd: String,
        date: LocalDate,
        processId: String?,
        productCodes: List<String>
    ): Long {
        val params = dayParams(plantCd, date)
        val labelFilter = defectLabelFilter(params, processId, productCodes)

        val sql = """
            SELECT coalesce(sum(coalesce(lh.defect, 0)), 0) AS ng_qty
            FROM mes.tb_pop_label_hist lh
            WHERE lh.plant_cd  = :plantCd
              AND lh.del_flg   = 'N'
              AND lh.ins_date >= :dayStart
              AND lh.ins_date <  :dayEnd
              $labelFilter
        """.trimIndent()

        return jdbcTemplate.queryForObject(sql, params) { rs, _ ->
            Rs.qty(rs, "ng_qty") ?: 0L
        } ?: 0L
    }

    /**
     * 제품별 가동률을 조회한다. (No.38 — 설비 점유 기준)
     */
    fun findProductUptime(
        plantCd: String,
        date: LocalDate,
        processId: String?,
        productCodes: List<String>
    ): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT
                p.model_cd,
                round(avg(mv.metric_value), 2) AS uptime_rate
            FROM ax.tb_met_metric_value mv
            INNER JOIN ax.tb_met_metric_std ms ON ms.metric_id  = mv.metric_id
            INNER JOIN ax.tb_prod_product   p  ON p.product_id  = mv.product_id
            WHERE ms.metric_cd    = 'EQPT_UPTIME_RATE'
              AND mv.measured_at >= :dayStart
              AND mv.measured_at <  :dayEnd
              AND (mv.plant_cd IS NULL OR mv.plant_cd = :plantCd)
            """.trimIndent()
        )

        val params = dayParams(plantCd, date)

        if (!processId.isNullOrBlank()) {
            sql.append(" AND mv.wc_cd = :processId")
            params.addValue("processId", processId.trim())
        }
        if (productCodes.isNotEmpty()) {
            sql.append(" AND p.model_cd = ANY(:productCodes)")
            params.addValue("productCodes", productCodes.toTypedArray())
        }

        sql.append("\nGROUP BY p.model_cd\nORDER BY p.model_cd")

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "product" to rs.getString("model_cd"),
                "uptimeRate" to Rs.rate(rs, "uptime_rate")
            )
        }
    }

    /**
     * 공정 비교 데이터를 조회한다. (No.39 — 동일 제품 구성 기준)
     */
    fun findProcessCompare(
        plantCd: String,
        date: LocalDate,
        productCodes: List<String>
    ): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT
                lh.wc_cd,
                coalesce(max(w.wc_nm), lh.wc_cd)                          AS wc_nm,
                max(w.sort_seq)                                           AS sort_seq,
                coalesce(sum(lh.normal), 0)                               AS ok_qty,
                coalesce(sum(lh.defect), 0)                               AS ng_qty,
                coalesce(sum(lh.normal), 0) + coalesce(sum(lh.defect), 0) AS total_qty
            FROM mes.tb_pop_label_hist lh
            LEFT JOIN mes.tb_md_workcenter w
                   ON w.plant_cd = lh.plant_cd AND w.wc_cd = lh.wc_cd
            LEFT JOIN ax.tb_prod_item_map pm
                   ON pm.plant_cd = lh.plant_cd AND pm.item_cd = lh.item_cd
            LEFT JOIN ax.tb_prod_product p ON p.product_id = pm.product_id
            WHERE lh.plant_cd  = :plantCd
              AND lh.del_flg   = 'N'
              AND lh.ins_date >= :dayStart
              AND lh.ins_date <  :dayEnd
            """.trimIndent()
        )

        val params = dayParams(plantCd, date)
        if (productCodes.isNotEmpty()) {
            sql.append(" AND p.model_cd = ANY(:productCodes)")
            params.addValue("productCodes", productCodes.toTypedArray())
        }

        sql.append("\nGROUP BY lh.wc_cd\nORDER BY max(w.sort_seq) NULLS LAST, lh.wc_cd")

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "processId" to rs.getString("wc_cd"),
                "process" to rs.getString("wc_nm"),
                "qty" to Rs.qty(rs, "total_qty"),
                "defectRate" to safeRate(rs.getBigDecimal("ng_qty"), rs.getBigDecimal("total_qty")),
                "yield" to safeRate(rs.getBigDecimal("ok_qty"), rs.getBigDecimal("total_qty"))
            )
        }
    }

    /**
     * Top N 제품 코드를 조회한다. (No.42 — SY-07 제품군 순위를 따름)
     *
     * @param topN 조회 건수 (null 이면 전체)
     */
    fun findTopProducts(topN: Int?): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT
                p.model_cd,
                p.model_nm,
                f.family_cd,
                f.family_nm,
                f.rank_no       AS family_rank,
                p.seq_in_family,
                p.rank_no,
                c.customer_nm
            FROM ax.tb_prod_product p
            INNER JOIN ax.tb_prod_family   f ON f.family_id   = p.family_id
            LEFT  JOIN ax.tb_prod_customer c ON c.customer_id = p.customer_id
            WHERE p.use_flg = 'Y'
            ORDER BY f.rank_no, p.seq_in_family
            """.trimIndent()
        )

        val params = MapSqlParameterSource()
        if (topN != null) {
            sql.append("\nLIMIT :topN")
            params.addValue("topN", topN)
        }

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "code" to rs.getString("model_cd"),
                "name" to rs.getString("model_nm"),
                "family" to rs.getString("family_nm"),
                "familyCd" to rs.getString("family_cd"),
                "customer" to rs.getString("customer_nm"),
                "rank" to Rs.intOrNull(rs, "rank_no"),
                "seq" to rs.getInt("seq_in_family")
            )
        }
    }

    /**
     * 선택 요약 정보를 조회한다. (No.43)
     *
     * @param processId 공정 코드
     */
    fun findProcessInfo(plantCd: String, processId: String): Map<String, Any?>? {
        val sql = """
            SELECT
                w.wc_cd,
                w.wc_nm,
                (
                    SELECT ms.std_val FROM ax.tb_met_metric_std ms
                     WHERE ms.metric_cd = 'YIELD_' || w.wc_cd AND ms.use_flg = 'Y' LIMIT 1
                ) AS target_yield,
                (
                    SELECT ms.std_val FROM ax.tb_met_metric_std ms
                     WHERE ms.metric_cd = 'CAPACITY_' || w.wc_cd AND ms.use_flg = 'Y' LIMIT 1
                ) AS capacity
            FROM mes.tb_md_workcenter w
            WHERE w.plant_cd = :plantCd
              AND w.wc_cd    = :processId
            LIMIT 1
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("plantCd", plantCd)
            .addValue("processId", processId)

        return jdbcTemplate.query(sql, params) { rs, _ ->
            mapOf(
                "id" to rs.getString("wc_cd"),
                "name" to rs.getString("wc_nm"),
                "targetYield" to Rs.rate(rs, "target_yield"),
                "capacity" to Rs.rate(rs, "capacity", 0)
            )
        }.firstOrNull()
    }

    // ---------------------------------------------------------------------------------
    // 공통 동적 조건
    // ---------------------------------------------------------------------------------

    /**
     * 라벨 이력 기준 공정·제품 필터를 부착한다.
     */
    private fun appendProcessProductFilter(
        sql: StringBuilder,
        params: MapSqlParameterSource,
        processId: String?,
        productCodes: List<String>
    ) {
        if (!processId.isNullOrBlank()) {
            sql.append(" AND lh.wc_cd = :processId")
            params.addValue("processId", processId.trim())
        }
        if (productCodes.isNotEmpty()) {
            sql.append(" AND p.model_cd = ANY(:productCodes)")
            params.addValue("productCodes", productCodes.toTypedArray())
        }
    }

    /**
     * 라벨 이력(별칭 `lh`) 기준 공정·제품 필터절을 만든다.
     *
     * 제품 필터는 라벨의 품목코드로 EXISTS 로 건다.
     * 불량 이력 쪽에 걸면 기간 필터까지 그쪽으로 끌려가 행 집합이 어긋난다.
     * (MES_QUERY_GUIDE 2-3)
     */
    private fun defectLabelFilter(
        params: MapSqlParameterSource,
        processId: String?,
        productCodes: List<String>
    ): String {
        val filter = StringBuilder()

        if (!processId.isNullOrBlank()) {
            filter.append(" AND lh.wc_cd = :processId")
            params.addValue("processId", processId.trim())
        }
        if (productCodes.isNotEmpty()) {
            filter.append(
                " AND EXISTS (SELECT 1 FROM ax.tb_prod_item_map pm " +
                    "INNER JOIN ax.tb_prod_product p ON p.product_id = pm.product_id " +
                    "WHERE pm.plant_cd = lh.plant_cd AND pm.item_cd = lh.item_cd " +
                    "AND p.model_cd = ANY(:productCodes))"
            )
            params.addValue("productCodes", productCodes.toTypedArray())
        }

        return filter.toString()
    }

    /** 일자 범위 공통 파라미터 */
    private fun dayParams(plantCd: String, date: LocalDate): MapSqlParameterSource =
        MapSqlParameterSource()
            .addValue("plantCd", plantCd)
            .addValue("dayStart", date.atStartOfDay())
            .addValue("dayEnd", date.plusDays(1).atStartOfDay())
}
