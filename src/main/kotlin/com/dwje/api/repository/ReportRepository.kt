package com.dwje.api.repository

import com.dwje.api.common.util.DefectSql
import com.dwje.api.common.util.Rs
import com.dwje.api.common.util.TimeWindow
import com.dwje.api.common.util.safeRate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/**
 * 정형 보고서 Repository (RP-01 ~ RP-05)
 *
 * - 아침회의 자료 : 일목표(ax.tb_prod_day_target — 제품·공정별 마스터) 대비 실적
 * - 연간 출하계획 : ax.tb_prod_ship_plan (회계연도 8월 시작)
 * - 제품별 수율   : mes.tb_pop_label_hist · mes.tb_pop_defect_hist · mes.tb_md_defect_by_item
 * - 고객사 LRR    : ax.tb_qc_lrr_notice 접수 이력 대비 출하 실적
 */
@Repository
class ReportRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) {

    /**
     * 아침회의 자료 본표를 조회한다. (No.107 / No.109)
     *
     * 일목표는 **제품·공정별 마스터**(`ax.tb_prod_day_target`)에서, 실적은 라벨 이력에서
     * 가져오고 주간 누적을 함께 산출한다.
     *
     * 마스터에 목표가 없는 공정은 `dayTarget`·`rate` 가 **null** 이다 —
     * 0 으로 채우면 "목표 없음" 과 "목표가 0" 이 구분되지 않고, 달성률 0 이 되어
     * 전 행이 위험으로 뜬다. 주간목표는 이 표의 주간 창(기준일 포함 7일)에 맞춰
     * 서비스가 `dayTarget x 7` 로 낸다.
     *
     * @param plantCd     사업장 코드
     * @param baseDate    기준일 (전일 실적 기준)
     * @param processCds  대상 공정 코드 목록 (빈 목록이면 전체)
     */
    fun findMorningMeetingRows(
        plantCd: String,
        baseDate: LocalDate,
        processCds: List<String>
    ): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            WITH day_actual AS (
                SELECT
                    lh.wc_cd,
                    coalesce(sum(lh.normal), 0) + coalesce(sum(lh.defect), 0) AS qty,
                    coalesce(sum(lh.defect), 0)                               AS ng_qty,
                    count(DISTINCT lh.eqpt_cd)                                AS eqpt_cnt
                FROM mes.tb_pop_label_hist lh
                WHERE lh.plant_cd  = :plantCd
                  AND lh.del_flg   = 'N'
                  AND lh.ins_date >= :dayStart
                  AND lh.ins_date <  :dayEnd
                GROUP BY lh.wc_cd
            ),
            week_actual AS (
                SELECT
                    lh.wc_cd,
                    coalesce(sum(lh.normal), 0) + coalesce(sum(lh.defect), 0) AS qty
                FROM mes.tb_pop_label_hist lh
                WHERE lh.plant_cd  = :plantCd
                  AND lh.del_flg   = 'N'
                  AND lh.ins_date >= :weekStart
                  AND lh.ins_date <  :dayEnd
                GROUP BY lh.wc_cd
            ),
            -- 일목표는 제품·공정별 마스터(ax.tb_prod_day_target)에서 온다.
            -- 예전에는 지표(ax.tb_met_metric_value, metric_cd='PROD_DAY_TARGET')를 읽었는데
            -- 그 테이블이 0행이라 coalesce 로 0 이 되어 **전 행이 달성률 0 · 위험**으로 떴다.
            --
            -- 마스터는 제품 x 공정 단위이고 이 표는 공정 한 줄이라 공정별로 합친다.
            -- 목표가 하나도 없는 공정은 sum() 이 NULL 이 되고, 그 NULL 을 그대로 내린다 —
            -- 0 으로 바꾸면 "목표 없음" 과 "목표가 0" 이 같은 값이 되어 구분할 수 없다.
            day_target AS (
                SELECT eff.wc_cd, sum(eff.target_qty) AS target_qty
                FROM (
                    -- 적용일 구간: 그 날짜 이하의 적용일 중 가장 늦은 것 한 건
                    SELECT DISTINCT ON (product, wc_cd) wc_cd, target_qty
                    FROM ax.tb_prod_day_target
                    WHERE plant_cd = :plantCd
                      AND apply_from <= :baseDate
                    ORDER BY product, wc_cd, apply_from DESC
                ) eff
                GROUP BY eff.wc_cd
            )
            SELECT
                w.wc_cd,
                w.wc_nm,
                w.sort_seq,
                dt.target_qty               AS day_target,
                coalesce(da.qty, 0)         AS day_actual,
                coalesce(da.ng_qty, 0)      AS day_ng,
                coalesce(da.eqpt_cnt, 0)    AS eqpt_cnt,
                coalesce(wa.qty, 0)         AS week_actual
            FROM mes.tb_md_workcenter w
            LEFT JOIN day_actual  da ON da.wc_cd = w.wc_cd
            LEFT JOIN week_actual wa ON wa.wc_cd = w.wc_cd
            LEFT JOIN day_target  dt ON dt.wc_cd = w.wc_cd
            WHERE w.plant_cd = :plantCd
              AND now() BETWEEN w.valid_from_dt AND w.valid_to_dt
            """.trimIndent()
        )

        val params = MapSqlParameterSource()
            .addValue("plantCd", plantCd)
            .addValue("dayStart", baseDate.atStartOfDay())
            .addValue("dayEnd", baseDate.plusDays(1).atStartOfDay())
            .addValue("weekStart", baseDate.minusDays(6).atStartOfDay())
            .addValue("baseDate", baseDate)

        if (processCds.isNotEmpty()) {
            sql.append(" AND w.wc_cd = ANY(:processCds)")
            params.addValue("processCds", processCds.toTypedArray())
        }

        sql.append("\nORDER BY w.sort_seq, w.wc_cd")

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            val dayTarget = rs.getBigDecimal("day_target")
            val dayActual = rs.getBigDecimal("day_actual")

            mapOf(
                "processId" to rs.getString("wc_cd"),
                "process" to rs.getString("wc_nm"),
                // 목표가 없으면 null 이다. 달성률도 서비스에서 내지 않는다.
                "dayTarget" to Rs.qty(rs, "day_target"),
                "dayActual" to Rs.qty(rs, "day_actual"),
                "dayNgQty" to Rs.qty(rs, "day_ng"),
                "rate" to if (dayTarget == null) null else safeRate(dayActual, dayTarget),
                "weekActual" to Rs.qty(rs, "week_actual"),
                "impactEqptCnt" to rs.getInt("eqpt_cnt")
            )
        }
    }

    /**
     * 일일 생산현황 보고 양식 본문(제품 × 공정)을 조회한다.
     *
     * 집계 구간은 서비스가 정한다. (전일 08:00 ~ 당일 08:00 / 주간 누적)
     *
     * 설비 대수는 **제품 단위로 `count(DISTINCT eqpt_cd)`** 를 낸다.
     * 품목별로 센 값을 더하면 한 설비가 같은 모델의 여러 품목을 찍을 때 중복으로 센다.
     *
     * 품목 → 제품 매핑이 없는 실적은 버리지 않고 `item_cd` 를 제품 코드 자리에 그대로 둔다.
     * (매핑은 1,106/1,139 만 있어 조용히 버리면 합계가 어긋난다)
     *
     * @param plantCd    사업장 코드
     * @param window     대상일 집계 구간
     * 주간 실적은 **두 기준으로 따로** 낸다.
     * `week_qty` 는 그 주 보고 구간들의 합이고, `week_qty_all_shift` 는 그 주 시작부터
     * 대상일까지 끊지 않은 연속 구간의 합이다.
     *
     * 2026-09-16 에 보고 구간이 24시간(전일 08:00 ~ 당일 08:00)이 되면서 일별 구간이
     * 빈틈없이 이어 붙어, 지금은 두 값이 **항상 같다.** 20:00 시작이던 때에는 사이의
     * 주간 교대 12시간이 연속 구간에만 들어가 서로 달랐고, 주간목표가 일목표 × 보고
     * 일수라서 달성률에는 보고 구간 합만 짝이 맞았다.
     * 구간이 다시 좁아지면 두 값은 또 갈라진다. 그래서 이름과 계산을 그대로 둔다.
     *
     * @param window        대상일 집계 구간
     * @param reportWindows 그 주 보고 구간 목록 (비어 있으면 `window` 하나로 본다)
     * @param processCds    대상 공정 코드 목록 (빈 목록이면 전체)
     */
    fun findDailySheetRows(
        plantCd: String,
        window: TimeWindow,
        reportWindows: List<TimeWindow>,
        processCds: List<String>
    ): List<Map<String, Any?>> {
        // 품목 → 제품 매핑은 (plant_cd, item_cd) 유일하므로 조인이 행을 늘리지 않는다.
        val processFilter = if (processCds.isEmpty()) "" else " AND lh.wc_cd = ANY(:processCds)"

        val windows = reportWindows.ifEmpty { listOf(window) }
        val weekFrom = windows.minOf { it.from }

        // 보고 구간에 드는지 판정하는 절. 구간이 최대 7개라 그대로 펼친다.
        val shiftPredicate = windows.indices.joinToString(
            separator = "\n                     OR "
        ) { i -> "(lh.ins_date >= :shiftFrom$i AND lh.ins_date < :shiftTo$i)" }

        val sql = """
            WITH lab AS (
                SELECT
                    lh.eqpt_cd,
                    lh.wc_cd,
                    lh.normal,
                    lh.defect,
                    lh.ins_date,
                    coalesce(p.model_cd, lh.item_cd) AS product,
                    p.model_nm                       AS product_nm,
                    ($shiftPredicate)                AS is_report_shift
                FROM mes.tb_pop_label_hist lh
                LEFT JOIN ax.tb_prod_item_map pm
                       ON pm.plant_cd = lh.plant_cd AND pm.item_cd = lh.item_cd
                LEFT JOIN ax.tb_prod_product p
                       ON p.product_id = pm.product_id
                WHERE lh.plant_cd  = :plantCd
                  AND lh.del_flg   = 'N'
                  AND lh.ins_date >= :weekStart
                  AND lh.ins_date <  :dayEnd
                  $processFilter
            ),
            day_agg AS (
                SELECT
                    product,
                    max(product_nm)                                     AS product_nm,
                    wc_cd,
                    coalesce(sum(normal), 0) + coalesce(sum(defect), 0)  AS qty,
                    coalesce(sum(normal), 0)                            AS ok_qty,
                    coalesce(sum(defect), 0)                            AS ng_qty,
                    count(DISTINCT eqpt_cd)                             AS eqpt_cnt
                FROM lab
                WHERE ins_date >= :dayStart
                GROUP BY product, wc_cd
            ),
            week_agg AS (
                SELECT
                    product,
                    max(product_nm) AS product_nm,
                    wc_cd,
                    -- 주간목표(일목표 x 보고 일수)와 짝이 맞는 값
                    coalesce(sum(normal) FILTER (WHERE is_report_shift), 0)
                        + coalesce(sum(defect) FILTER (WHERE is_report_shift), 0) AS qty,
                    -- 주간 교대까지 포함한 연속 구간
                    coalesce(sum(normal), 0) + coalesce(sum(defect), 0)           AS qty_all_shift
                FROM lab
                GROUP BY product, wc_cd
            )
            -- 주간 집계가 조인을 주도한다. 대상일 구간에 실적이 없고 그 주에만 있는 제품
            -- (예: 낮 근무만 돌린 제품)을 빼면 주간 합계가 조용히 모자란다.
            -- 실측: 08-28 대상일에서 PDX-S/W120 이 빠져 주간 연속구간 합이 60,000 적었다.
            SELECT
                wk.product,
                coalesce(d.product_nm, wk.product_nm) AS product_nm,
                wk.wc_cd,
                coalesce(w.wc_nm, wk.wc_cd)    AS wc_nm,
                coalesce(d.qty, 0)             AS qty,
                coalesce(d.ok_qty, 0)          AS ok_qty,
                coalesce(d.ng_qty, 0)          AS ng_qty,
                coalesce(d.eqpt_cnt, 0)        AS eqpt_cnt,
                wk.qty                         AS week_qty,
                wk.qty_all_shift               AS week_qty_all_shift
            FROM week_agg wk
            LEFT JOIN day_agg d
                   ON d.product = wk.product AND d.wc_cd = wk.wc_cd
            LEFT JOIN mes.tb_md_workcenter w
                   ON w.plant_cd = :plantCd AND w.wc_cd = wk.wc_cd
            ORDER BY w.sort_seq NULLS LAST, wk.wc_cd, coalesce(d.qty, 0) DESC, wk.product
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("plantCd", plantCd)
            .addValue("dayStart", window.from)
            .addValue("dayEnd", window.toExclusive)
            .addValue("weekStart", weekFrom)
        windows.forEachIndexed { i, w ->
            params.addValue("shiftFrom$i", w.from).addValue("shiftTo$i", w.toExclusive)
        }
        if (processCds.isNotEmpty()) params.addValue("processCds", processCds.toTypedArray())

        return jdbcTemplate.query(sql, params) { rs, _ ->
            mapOf(
                "product" to rs.getString("product"),
                "productNm" to rs.getString("product_nm"),
                "processId" to rs.getString("wc_cd"),
                "processNm" to rs.getString("wc_nm"),
                "qty" to Rs.qty(rs, "qty"),
                "okQty" to Rs.qty(rs, "ok_qty"),
                "ngQty" to Rs.qty(rs, "ng_qty"),
                "weekQty" to Rs.qty(rs, "week_qty"),
                "weekQtyAllShift" to Rs.qty(rs, "week_qty_all_shift"),
                "eqptCnt" to rs.getInt("eqpt_cnt")
            )
        }
    }

    /**
     * 연간 출하계획을 조회한다. (No.110 — 회계연도 8월 시작 12개월)
     *
     * @param planYear   회계연도 시작 연도
     * @param modelCd    모델 코드
     * @param customerCd 고객사 코드
     */
    fun findShipPlan(planYear: Int, modelCd: String?, customerCd: String?): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT
                p.model_cd,
                p.model_nm,
                c.customer_cd,
                c.customer_nm,
                sp.plan_year,
                sp.plan_month,
                sp.plan_qty,
                sp.unit_price,
                sp.plan_amount
            FROM ax.tb_prod_ship_plan sp
            INNER JOIN ax.tb_prod_product  p ON p.product_id  = sp.product_id
            LEFT  JOIN ax.tb_prod_customer c ON c.customer_id = coalesce(sp.customer_id, p.customer_id)
            WHERE (
                    (sp.plan_year = :planYear     AND sp.plan_month >= 8)
                 OR (sp.plan_year = :nextYear     AND sp.plan_month <= 7)
                  )
            """.trimIndent()
        )

        val params = MapSqlParameterSource()
            .addValue("planYear", planYear)
            .addValue("nextYear", planYear + 1)

        if (!modelCd.isNullOrBlank()) {
            sql.append(" AND p.model_cd = :modelCd")
            params.addValue("modelCd", modelCd.trim())
        }
        if (!customerCd.isNullOrBlank()) {
            sql.append(" AND c.customer_cd = :customerCd")
            params.addValue("customerCd", customerCd.trim())
        }

        sql.append("\nORDER BY p.model_cd, sp.plan_year, sp.plan_month")

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "model" to rs.getString("model_cd"),
                "modelNm" to rs.getString("model_nm"),
                "customerCd" to rs.getString("customer_cd"),
                "customer" to rs.getString("customer_nm"),
                "year" to rs.getInt("plan_year"),
                "month" to rs.getInt("plan_month"),
                "planQty" to Rs.qty(rs, "plan_qty"),
                "unitPrice" to Rs.rate(rs, "unit_price", 2),
                "planAmount" to Rs.rate(rs, "plan_amount", 0)
            )
        }
    }

    /**
     * 제품별 월간 수율을 조회한다. (No.111)
     *
     * 일자 × 모델 단위로 투입·양품·불량을 집계한다.
     *
     * @param yearMonth 기준 월
     * @param modelCd   모델 코드
     * @param processId 공정 코드
     */
    fun findYieldByModel(
        plantCd: String,
        yearMonth: YearMonth,
        modelCd: String?,
        processId: String?
    ): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT
                to_char(lh.ins_date, 'YYYY-MM-DD')                        AS prod_date,
                coalesce(p.model_cd, lh.item_cd)                          AS model_cd,
                coalesce(sum(lh.normal), 0)                               AS ok_qty,
                coalesce(sum(lh.defect), 0)                               AS ng_qty,
                coalesce(sum(lh.normal), 0) + coalesce(sum(lh.defect), 0) AS total_qty
            FROM mes.tb_pop_label_hist lh
            LEFT JOIN ax.tb_prod_item_map pm
                   ON pm.plant_cd = lh.plant_cd AND pm.item_cd = lh.item_cd
            LEFT JOIN ax.tb_prod_product p ON p.product_id = pm.product_id
            WHERE lh.plant_cd  = :plantCd
              AND lh.del_flg   = 'N'
              AND lh.ins_date >= :from
              AND lh.ins_date <  :toExclusive
            """.trimIndent()
        )

        val params = monthParams(plantCd, yearMonth)

        if (!modelCd.isNullOrBlank()) {
            sql.append(" AND p.model_cd = :modelCd")
            params.addValue("modelCd", modelCd.trim())
        }
        if (!processId.isNullOrBlank()) {
            sql.append(" AND lh.wc_cd = :processId")
            params.addValue("processId", processId.trim())
        }

        sql.append("\nGROUP BY 1, 2\nORDER BY 1, 2")

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            val ok = rs.getBigDecimal("ok_qty")
            val ng = rs.getBigDecimal("ng_qty")
            val total = rs.getBigDecimal("total_qty")
            mapOf(
                "date" to rs.getString("prod_date"),
                "model" to rs.getString("model_cd"),
                "inputQty" to Rs.qty(rs, "total_qty"),
                "okQty" to Rs.qty(rs, "ok_qty"),
                "ngQty" to Rs.qty(rs, "ng_qty"),
                "defectRate" to safeRate(ng, total),
                "yield" to safeRate(ok, total)
            )
        }
    }

    /**
     * 제품별 Loss 유형 분해를 조회한다. (No.111 — Loss 11종 + 관리 항목 3종)
     *
     * 불량 코드별 수량을 일자 × 모델 단위로 반환한다.
     */
    fun findLossBreakdown(
        plantCd: String,
        yearMonth: YearMonth,
        modelCd: String?,
        processId: String?
    ): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT
                to_char(dh.ins_date, 'YYYY-MM-DD')            AS prod_date,
                coalesce(p.model_cd, dh.item_cd)              AS model_cd,
                dh.defect_cd,
                coalesce(max(md.defect_nm), dh.defect_cd)     AS defect_nm,
                coalesce(sum(dh.qty), 0)                      AS ng_qty
            FROM mes.tb_pop_defect_hist dh
            LEFT JOIN mes.tb_md_defect md
                   ON md.plant_cd = dh.plant_cd AND md.defect_cd = dh.defect_cd
            LEFT JOIN ax.tb_prod_item_map pm
                   ON pm.plant_cd = dh.plant_cd AND pm.item_cd = dh.item_cd
            LEFT JOIN ax.tb_prod_product p ON p.product_id = pm.product_id
            WHERE dh.plant_cd  = :plantCd
              AND dh.ins_date >= :from
              AND dh.ins_date <  :toExclusive
              ${DefectSql.excludeNonProduction()}
            """.trimIndent()
        )

        val params = monthParams(plantCd, yearMonth)

        if (!modelCd.isNullOrBlank()) {
            sql.append(" AND p.model_cd = :modelCd")
            params.addValue("modelCd", modelCd.trim())
        }
        if (!processId.isNullOrBlank()) {
            sql.append(" AND dh.wc_cd = :processId")
            params.addValue("processId", processId.trim())
        }

        sql.append("\nGROUP BY 1, 2, dh.defect_cd\nORDER BY 1, 2")

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "date" to rs.getString("prod_date"),
                "model" to rs.getString("model_cd"),
                "defectCd" to rs.getString("defect_cd"),
                "defectNm" to rs.getString("defect_nm"),
                "qty" to Rs.qty(rs, "ng_qty")
            )
        }
    }

    /**
     * 고객사별 LRR 통보 집계를 조회한다. (No.112)
     *
     * @param baseYear   기준 연도
     * @param customerCd 고객사 코드
     */
    fun findLrrByCustomer(baseYear: Int, customerCd: String?): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT
                c.customer_id,
                c.customer_cd,
                c.customer_nm,
                n.ship_year,
                n.ship_month,
                coalesce(sum(n.lrr_qty), 0)  AS lrr_qty,
                coalesce(sum(n.ship_qty), 0) AS ship_qty,
                count(*)                     AS notice_cnt
            FROM ax.tb_qc_lrr_notice n
            INNER JOIN ax.tb_prod_customer c ON c.customer_id = n.customer_id
            WHERE n.ship_year IN (:baseYear, :prevYear)
            """.trimIndent()
        )

        val params = MapSqlParameterSource()
            .addValue("baseYear", baseYear)
            .addValue("prevYear", baseYear - 1)

        if (!customerCd.isNullOrBlank()) {
            sql.append(" AND c.customer_cd = :customerCd")
            params.addValue("customerCd", customerCd.trim())
        }

        sql.append("\nGROUP BY c.customer_id, c.customer_cd, c.customer_nm, n.ship_year, n.ship_month")
        sql.append("\nORDER BY c.customer_nm, n.ship_year, n.ship_month")

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "customerCd" to rs.getString("customer_cd"),
                "customer" to rs.getString("customer_nm"),
                "year" to rs.getInt("ship_year"),
                "month" to rs.getInt("ship_month"),
                "lrrQty" to Rs.qty(rs, "lrr_qty"),
                "shipQty" to Rs.qty(rs, "ship_qty"),
                "noticeCnt" to rs.getLong("notice_cnt"),
                "lrrRate" to safeRate(rs.getBigDecimal("lrr_qty"), rs.getBigDecimal("ship_qty"), 4)
            )
        }
    }

    /**
     * LRR 불량 유형별 발생 건수를 조회한다. (No.112 — 블록 1)
     */
    fun findLrrByDefectType(baseYear: Int, customerCd: String?): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT
                coalesce(md.defect_nm, n.defect_txt, n.defect_cd, '미분류') AS defect_nm,
                n.ship_year,
                n.ship_month,
                coalesce(sum(n.lrr_qty), 0) AS lrr_qty,
                count(*)                    AS notice_cnt
            FROM ax.tb_qc_lrr_notice n
            INNER JOIN ax.tb_prod_customer c ON c.customer_id = n.customer_id
            LEFT  JOIN mes.tb_md_defect md
                    ON md.plant_cd = n.plant_cd AND md.defect_cd = n.defect_cd
            WHERE n.ship_year IN (:baseYear, :prevYear)
            """.trimIndent()
        )

        val params = MapSqlParameterSource()
            .addValue("baseYear", baseYear)
            .addValue("prevYear", baseYear - 1)

        if (!customerCd.isNullOrBlank()) {
            sql.append(" AND c.customer_cd = :customerCd")
            params.addValue("customerCd", customerCd.trim())
        }

        sql.append("\nGROUP BY 1, n.ship_year, n.ship_month\nORDER BY 1, n.ship_year, n.ship_month")

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "defectType" to rs.getString("defect_nm"),
                "year" to rs.getInt("ship_year"),
                "month" to rs.getInt("ship_month"),
                "qty" to Rs.qty(rs, "lrr_qty"),
                "cnt" to rs.getLong("notice_cnt")
            )
        }
    }

    /**
     * 보고서 정의 정보를 조회한다. (출력·인쇄 시 이력 기록용)
     */
    fun findReportDefinition(reportId: String): Map<String, Any?>? {
        val sql = """
            SELECT report_id, report_nm, report_group, menu_id
            FROM ax.tb_rpt_report
            WHERE report_id = :reportId AND use_flg = 'Y'
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource("reportId", reportId)) { rs, _ ->
            mapOf(
                "reportId" to rs.getString("report_id"),
                "reportNm" to rs.getString("report_nm"),
                "group" to rs.getString("report_group"),
                "menuId" to rs.getString("menu_id")
            )
        }.firstOrNull()
    }

    /** 연월 범위 공통 파라미터 */
    private fun monthParams(plantCd: String, yearMonth: YearMonth): MapSqlParameterSource =
        MapSqlParameterSource()
            .addValue("plantCd", plantCd)
            .addValue("from", yearMonth.atDay(1).atStartOfDay())
            .addValue("toExclusive", yearMonth.plusMonths(1).atDay(1).atStartOfDay())
}
