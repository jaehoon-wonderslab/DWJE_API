package com.dwje.api.repository

import com.dwje.api.common.util.BusinessDay
import com.dwje.api.common.util.DefectSql
import com.dwje.api.common.util.Rs
import com.dwje.api.common.util.SlotBucket
import com.dwje.api.common.util.TimeWindow
import java.time.LocalDate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

/**
 * AI 통합 대시보드 Repository (DB-01)
 *
 * 생산 실적은 라벨 이력(mes.tb_pop_label_hist)의 양품/불량 수량을 원천으로 하고,
 * 불량 유형별 상세는 불량 이력(mes.tb_pop_defect_hist)에서 집계한다.
 * 가동률 등 설비 지표는 지표 측정값(ax.tb_met_metric_value)을 사용한다.
 *
 * 산출식
 * - 생산량   = 양품(normal) + 불량(defect)
 * - 불량률(%) = 불량 / 생산량 × 100
 * - 수율(%)   = 양품 / 생산량 × 100
 */
@Repository
class DashboardAiRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) {

    /**
     * 통합 요약 지표를 조회한다. (No.21 — KPI 카드 4종)
     *
     * @param plantCd 사업장 코드
     * @param date    기준일
     * @return defectRate, uptimeRate, todayQty, okQty, ngQty
     */
    fun findSummary(plantCd: String, date: LocalDate): Map<String, Any?> =
        findSummary(plantCd, TimeWindow.ofDay(date))

    /** 생산·품질 요약 — 집계 구간을 직접 지정한다. */
    fun findSummary(plantCd: String, window: TimeWindow): Map<String, Any?> {
        val sql = """
            WITH prod AS (
                SELECT
                    coalesce(sum(lh.normal), 0)                              AS ok_qty,
                    coalesce(sum(lh.defect), 0)                              AS ng_qty,
                    coalesce(sum(lh.normal), 0) + coalesce(sum(lh.defect), 0) AS total_qty
                FROM mes.tb_pop_label_hist lh
                WHERE lh.plant_cd  = :plantCd
                  AND lh.del_flg   = 'N'
                  AND lh.ins_date >= :dayStart
                  AND lh.ins_date <  :dayEnd
            ),
            uptime AS (
                SELECT avg(mv.metric_value) AS uptime_rate
                FROM ax.tb_met_metric_value mv
                INNER JOIN ax.tb_met_metric_std ms ON ms.metric_id = mv.metric_id
                WHERE ms.metric_cd    = 'EQPT_UPTIME_RATE'
                  AND mv.measured_at >= :dayStart
                  AND mv.measured_at <  :dayEnd
                  AND (mv.plant_cd IS NULL OR mv.plant_cd = :plantCd)
            )
            SELECT
                prod.ok_qty,
                prod.ng_qty,
                prod.total_qty,
                CASE WHEN prod.total_qty > 0
                     THEN round(prod.ng_qty * 100.0 / prod.total_qty, 2)
                     ELSE 0 END                       AS defect_rate,
                round(coalesce(uptime.uptime_rate, 0), 2) AS uptime_rate
            FROM prod, uptime
        """.trimIndent()

        val params = dayParams(plantCd, window)

        return jdbcTemplate.queryForObject(sql, params) { rs, _ ->
            mapOf(
                "todayQty" to Rs.qty(rs, "total_qty"),
                "okQty" to Rs.qty(rs, "ok_qty"),
                "ngQty" to Rs.qty(rs, "ng_qty"),
                "defectRate" to Rs.rate(rs, "defect_rate"),
                "uptimeRate" to Rs.rate(rs, "uptime_rate")
            )
        } ?: emptyMap()
    }

    /**
     * 경계 판정 대기 건수를 조회한다. (No.21 — pendingBorderline)
     *
     * AI 불량 판정에서 경계 구간으로 분류되어 사람 확인(HITL)을 기다리는 건을 집계한다.
     * 경계 판정 기준은 ax.tb_ai_model_config 의 CLASSIFY 항목으로 관리한다.
     *
     * @return cnt(대기 건수), maxWaitMin(최장 대기 분)
     */
    fun findPendingBorderline(plantCd: String, date: LocalDate): Map<String, Any?> =
        findPendingBorderline(plantCd, TimeWindow.ofDay(date))

    /** 경계 판정 대기 건수 — 집계 구간을 직접 지정한다. */
    fun findPendingBorderline(plantCd: String, window: TimeWindow): Map<String, Any?> {
        val sql = """
            SELECT
                count(*)                                                                  AS cnt,
                coalesce(max(extract(epoch FROM (now() - a.occurred_at)) / 60), 0)::int    AS max_wait_min
            FROM ax.tb_alm_alert a
            WHERE a.ack_state_cd = 'OPEN'
              AND a.occurred_at >= :dayStart
              AND a.occurred_at <  :dayEnd
              AND (a.plant_cd IS NULL OR a.plant_cd = :plantCd)
              AND a.title ILIKE '%경계%'
        """.trimIndent()

        return jdbcTemplate.queryForObject(sql, dayParams(plantCd, window)) { rs, _ ->
            mapOf(
                "cnt" to rs.getLong("cnt"),
                "maxWaitMin" to rs.getInt("max_wait_min")
            )
        } ?: mapOf("cnt" to 0L, "maxWaitMin" to 0)
    }

    /**
     * 시간대별 불량률 추이를 조회한다. (No.22 — 전체 불량률 계열)
     *
     * @param plantCd      사업장 코드
     * @param date         기준일
     * @param intervalHour 집계 구간 시간 (기본 2시간)
     * @param processId    공정 코드 (미지정 시 전체)
     */
    fun findDefectTrend(
        plantCd: String,
        date: LocalDate,
        intervalHour: Int,
        processId: String?
    ): List<Map<String, Any?>> =
        findDefectTrend(plantCd, TimeWindow.ofDay(date), intervalHour, processId)

    fun findDefectTrend(
        plantCd: String,
        window: TimeWindow,
        intervalHour: Int,
        processId: String?
    ): List<Map<String, Any?>> {
        val bucket = SlotBucket.of(window, intervalHour)
        val sql = StringBuilder(
            """
            SELECT
                ${bucket.labelExpr("lh.ins_date")}                           AS slot,
                min(lh.ins_date)                                             AS slot_at,
                coalesce(sum(lh.normal), 0) + coalesce(sum(lh.defect), 0)    AS total_qty,
                coalesce(sum(lh.defect), 0)                                  AS ng_qty
            FROM mes.tb_pop_label_hist lh
            WHERE lh.plant_cd  = :plantCd
              AND lh.del_flg   = 'N'
              AND lh.ins_date >= :dayStart
              AND lh.ins_date <  :dayEnd
            """.trimIndent()
        )

        val params = dayParams(plantCd, window).addValue("intervalHour", bucket.intervalHour)

        if (!processId.isNullOrBlank()) {
            sql.append(" AND lh.wc_cd = :processId")
            params.addValue("processId", processId.trim())
        }

        sql.append("\nGROUP BY 1\nORDER BY min(lh.ins_date)")

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            val total = rs.getBigDecimal("total_qty")
            val ng = rs.getBigDecimal("ng_qty")
            mapOf(
                "slot" to rs.getString("slot"),
                "totalQty" to Rs.qty(rs, "total_qty"),
                "ngQty" to Rs.qty(rs, "ng_qty"),
                "defectRate" to com.dwje.api.common.util.safeRate(ng, total)
            )
        }
    }

    /**
     * 시간대별 주요 불량유형 추이를 조회한다. (No.22 — 보조 계열)
     *
     * @param topN 상위 불량유형 개수 — 전 유형이 필요하면 유형 수보다 큰 값을 준다
     */
    fun findDefectTrendByType(
        plantCd: String,
        date: LocalDate,
        intervalHour: Int,
        processId: String?,
        topN: Int
    ): List<Map<String, Any?>> =
        findDefectTrendByType(plantCd, TimeWindow.ofDay(date), intervalHour, processId, topN)

    fun findDefectTrendByType(
        plantCd: String,
        window: TimeWindow,
        intervalHour: Int,
        processId: String?,
        topN: Int
    ): List<Map<String, Any?>> {
        val bucket = SlotBucket.of(window, intervalHour)
        val sql = StringBuilder(
            """
            WITH top_defects AS (
                SELECT dh.defect_cd
                FROM mes.tb_pop_defect_hist dh
                WHERE dh.plant_cd  = :plantCd
                  AND dh.ins_date >= :dayStart
                  AND dh.ins_date <  :dayEnd
                  {PROCESS_FILTER_DH}
                  ${DefectSql.excludeNonProduction()}
                GROUP BY dh.defect_cd
                ORDER BY sum(dh.qty) DESC
                LIMIT :topN
            )
            SELECT
                ${bucket.labelExpr("dh.ins_date")} AS slot,
                min(dh.ins_date)           AS slot_at,
                dh.defect_cd,
                max(md.defect_nm)          AS defect_nm,
                coalesce(sum(dh.qty), 0)   AS ng_qty
            FROM mes.tb_pop_defect_hist dh
            INNER JOIN top_defects td ON td.defect_cd = dh.defect_cd
            LEFT  JOIN mes.tb_md_defect md
                    ON md.plant_cd = dh.plant_cd AND md.defect_cd = dh.defect_cd
            WHERE dh.plant_cd  = :plantCd
              AND dh.ins_date >= :dayStart
              AND dh.ins_date <  :dayEnd
              {PROCESS_FILTER_DH2}
              ${DefectSql.excludeNonProduction()}
            GROUP BY 1, dh.defect_cd
            ORDER BY min(dh.ins_date), dh.defect_cd
            """.trimIndent()
        )

        val params = dayParams(plantCd, window)
            .addValue("intervalHour", bucket.intervalHour)
            .addValue("topN", topN)

        // 공정 필터는 두 곳(top_defects CTE, 본 쿼리)에 동일하게 적용한다.
        val processFilter = if (!processId.isNullOrBlank()) {
            params.addValue("processId", processId.trim())
            "AND dh.wc_cd = :processId"
        } else {
            ""
        }

        val finalSql = sql.toString()
            .replace("{PROCESS_FILTER_DH}", processFilter)
            .replace("{PROCESS_FILTER_DH2}", processFilter)

        return jdbcTemplate.query(finalSql, params) { rs, _ ->
            mapOf(
                "slot" to rs.getString("slot"),
                "defectCd" to rs.getString("defect_cd"),
                "defectNm" to (rs.getString("defect_nm") ?: rs.getString("defect_cd")),
                "ngQty" to Rs.qty(rs, "ng_qty")
            )
        }
    }

    /**
     * 라인(설비)별 생산량·불량률을 조회한다. (No.23)
     *
     * @param processId 공정 코드
     */
    fun findLineProduction(plantCd: String, date: LocalDate, processId: String?): List<Map<String, Any?>> =
        findLineProduction(plantCd, TimeWindow.ofDay(date), processId)

    /** 설비별 생산·불량 — 집계 구간을 직접 지정한다. */
    fun findLineProduction(
        plantCd: String,
        window: TimeWindow,
        processId: String?
    ): List<Map<String, Any?>> {
        val productFilter = if (processId.isNullOrBlank()) "" else "AND lh.wc_cd = :processId"
        val params = dayParams(plantCd, window)
        if (!processId.isNullOrBlank()) params.addValue("processId", processId.trim())

        val sql = StringBuilder(
            """
            WITH prod AS (
                SELECT
                    lh.eqpt_cd,
                    lh.wc_cd,
                    -- 설비 마스터의 model_nm 은 1,511대 전부 null 이라 쓸 수 없다.
                    -- 실적의 품목을 제품 마스터로 옮겨 그 구간에 실제로 돌린 제품을 낸다.
                    coalesce(p.model_cd, lh.item_cd)                          AS product,
                    p.model_nm                                                AS product_nm,
                    coalesce(sum(lh.normal), 0)                               AS ok_qty,
                    coalesce(sum(lh.defect), 0)                               AS ng_qty,
                    coalesce(sum(lh.normal), 0) + coalesce(sum(lh.defect), 0) AS total_qty
                FROM mes.tb_pop_label_hist lh
                LEFT JOIN ax.tb_prod_item_map pm
                       ON pm.plant_cd = lh.plant_cd AND pm.item_cd = lh.item_cd
                LEFT JOIN ax.tb_prod_product p ON p.product_id = pm.product_id
                WHERE lh.plant_cd  = :plantCd
                  AND lh.del_flg   = 'N'
                  AND lh.eqpt_cd IS NOT NULL
                  AND lh.ins_date >= :dayStart
                  AND lh.ins_date <  :dayEnd
                  $productFilter
                GROUP BY lh.eqpt_cd, lh.wc_cd, coalesce(p.model_cd, lh.item_cd), p.model_nm
            )
            SELECT
                pr.eqpt_cd,
                max(e.eqpt_nm)          AS eqpt_nm,
                max(e.model_nm)         AS model_nm,
                max(w.wc_cd)            AS wc_cd,
                max(w.wc_nm)            AS wc_nm,
                sum(pr.ok_qty)          AS ok_qty,
                sum(pr.ng_qty)          AS ng_qty,
                sum(pr.total_qty)       AS total_qty,
                -- 가장 많이 만든 제품 하나와 나머지 종 수. 한 칸에 열 개를 늘어놓으면 못 읽는다.
                (array_agg(pr.product    ORDER BY pr.total_qty DESC))[1] AS top_product,
                (array_agg(pr.product_nm ORDER BY pr.total_qty DESC))[1] AS top_product_nm,
                count(DISTINCT pr.product) - 1                           AS product_etc_cnt
            FROM prod pr
            LEFT JOIN mes.tb_md_eqpt e
                   ON e.plant_cd = :plantCd AND e.eqpt_cd = pr.eqpt_cd
            LEFT JOIN mes.tb_md_workcenter w
                   ON w.plant_cd = :plantCd AND w.wc_cd = pr.wc_cd
            """.trimIndent()
        )

        sql.append("\nGROUP BY pr.eqpt_cd\nORDER BY sum(pr.total_qty) DESC")

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "eqptCd" to rs.getString("eqpt_cd"),
                "eqptNm" to rs.getString("eqpt_nm"),
                "model" to rs.getString("model_nm"),
                "processId" to rs.getString("wc_cd"),
                "processNm" to rs.getString("wc_nm"),
                // 그 구간에 실제로 돌린 제품 — 설비 마스터가 비어 있어 실적에서 낸다.
                "product" to rs.getString("top_product"),
                "productNm" to rs.getString("top_product_nm"),
                "productEtcCnt" to rs.getInt("product_etc_cnt"),
                "qty" to Rs.qty(rs, "total_qty"),
                "okQty" to Rs.qty(rs, "ok_qty"),
                "ngQty" to Rs.qty(rs, "ng_qty"),
                "defectRate" to com.dwje.api.common.util.safeRate(
                    rs.getBigDecimal("ng_qty"), rs.getBigDecimal("total_qty")
                )
            )
        }
    }

    /**
     * 공정 품질 지수 6축을 조회한다. (No.24)
     *
     * 축 : 양품률 · 가동률 · 정시완료 · 검사정확도 · 이상대응 · 데이터정합
     * 지표 기준(ax.tb_met_metric_std)에 등록된 지표의 당일 실측 평균과 기준값을 대비한다.
     */
    fun findQualityIndex(plantCd: String, date: LocalDate, metricCodes: List<String>): List<Map<String, Any?>> =
        findQualityIndex(plantCd, TimeWindow.ofDay(date), metricCodes)

    /** 공정 품질 지수 6축 — 집계 구간을 직접 지정한다. */
    fun findQualityIndex(
        plantCd: String,
        window: TimeWindow,
        metricCodes: List<String>
    ): List<Map<String, Any?>> {
        val sql = """
            SELECT
                ms.metric_cd,
                ms.metric_nm,
                ms.std_val,
                ms.unit_cd,
                round(avg(mv.metric_value), 2) AS actual_val
            FROM ax.tb_met_metric_std ms
            LEFT JOIN ax.tb_met_metric_value mv
                   ON mv.metric_id    = ms.metric_id
                  AND mv.measured_at >= :dayStart
                  AND mv.measured_at <  :dayEnd
                  AND (mv.plant_cd IS NULL OR mv.plant_cd = :plantCd)
            WHERE ms.metric_cd = ANY(:metricCodes)
              AND ms.use_flg   = 'Y'
            GROUP BY ms.metric_id, ms.metric_cd, ms.metric_nm, ms.std_val, ms.unit_cd
            ORDER BY array_position(:metricCodes, ms.metric_cd)
        """.trimIndent()

        val params = dayParams(plantCd, window).addValue("metricCodes", metricCodes.toTypedArray())

        return jdbcTemplate.query(sql, params) { rs, _ ->
            mapOf(
                "metricCd" to rs.getString("metric_cd"),
                "label" to rs.getString("metric_nm"),
                "value" to Rs.rate(rs, "actual_val"),
                "target" to Rs.rate(rs, "std_val"),
                "unit" to rs.getString("unit_cd")
            )
        }
    }

    /**
     * 불량 유형 구성을 조회한다. (No.25 — 경계 판정 건 제외)
     *
     * @param processId 공정 코드
     */
    fun findDefectComposition(plantCd: String, date: LocalDate, processId: String?): List<Map<String, Any?>> =
        findDefectComposition(plantCd, TimeWindow.ofDay(date), processId)

    /** 불량 유형 구성비 — 집계 구간을 직접 지정한다. */
    fun findDefectComposition(
        plantCd: String,
        window: TimeWindow,
        processId: String?
    ): List<Map<String, Any?>> {
        val params = dayParams(plantCd, window)

        var prodFilter = ""
        if (!processId.isNullOrBlank()) {
            prodFilter = " AND lh.wc_cd = :processId"
            params.addValue("processId", processId.trim())
        }

        // 기간은 라벨 이력에만 걸고, 라벨 불량 수량을 유형 구성비로 안분한다.
        // (MES_QUERY_GUIDE 2-3 / 2-4)
        val sql = """
            WITH
            ${DefectSql.labelLedgerCte("cur_label", "dayStart", "dayEnd", prodFilter)},
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
     * 시간대별 '유형 미상' 불량 수량 — 유형 이력이 하나도 없는 라벨의 불량 수량. (No.22 — topN=all 보조 계열)
     *
     * 불량 유형 구성(No.25)의 '유형 미상' 과 같은 정의다. 유형이 하나라도 붙은 라벨은 그 불량이
     * 유형들에 안분되므로, 유형이 전혀 없는 라벨의 불량만 미상으로 남는다. 라벨 시각 기준으로
     * 칸을 나눠 전체 불량률 계열([findDefectTrend])과 같은 칸에 놓인다.
     *
     * 전체 계열의 불량 − 유형 계열 합으로 구하면 안 된다. 유형 계열은 원표(defect_hist) 시각으로
     * 칸이 잡히고 안분도 되지 않아 칸마다 부호가 뒤집힌다(2026-08 실측: 28칸 중 12칸 음수).
     */
    fun findUntypedDefectTrend(
        plantCd: String,
        window: TimeWindow,
        intervalHour: Int,
        processId: String?
    ): List<Map<String, Any?>> {
        val bucket = SlotBucket.of(window, intervalHour)
        val sql = StringBuilder(
            """
            SELECT
                ${bucket.labelExpr("lh.ins_date")}   AS slot,
                min(lh.ins_date)                     AS slot_at,
                coalesce(sum(lh.defect), 0)          AS ng_qty
            FROM mes.tb_pop_label_hist lh
            WHERE lh.plant_cd  = :plantCd
              AND lh.del_flg   = 'N'
              AND lh.ins_date >= :dayStart
              AND lh.ins_date <  :dayEnd
              AND coalesce(lh.defect, 0) > 0
              AND NOT EXISTS (
                  SELECT 1
                  FROM mes.tb_pop_defect_hist dh
                  WHERE dh.plant_cd  = lh.plant_cd
                    AND dh.wc_cd     = lh.wc_cd
                    AND dh.lot_no    = lh.lot_no
                    AND dh.serial_no = lh.serial_no
                    ${DefectSql.excludeNonProduction()}
              )
            """.trimIndent()
        )

        val params = dayParams(plantCd, window).addValue("intervalHour", bucket.intervalHour)

        if (!processId.isNullOrBlank()) {
            sql.append(" AND lh.wc_cd = :processId")
            params.addValue("processId", processId.trim())
        }

        sql.append("\nGROUP BY 1\nORDER BY min(lh.ins_date)")

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "slot" to rs.getString("slot"),
                "ngQty" to Rs.qty(rs, "ng_qty")
            )
        }
    }

    /**
     * 칸 하나의 라벨 원장 합계 — 투입·불량 수량. (No.22 칸 클릭 상세의 분모)
     *
     * [findDefectTrend] 한 칸과 같은 행 집합이라 칸 값과 정확히 맞는다.
     */
    fun findSlotLabelTotals(plantCd: String, slot: TimeWindow): Map<String, Any?> {
        val sql = """
            SELECT
                coalesce(sum(lh.normal), 0) + coalesce(sum(lh.defect), 0) AS total_qty,
                coalesce(sum(lh.defect), 0)                               AS ng_qty,
                count(*)                                                  AS label_cnt
            FROM mes.tb_pop_label_hist lh
            WHERE lh.plant_cd  = :plantCd
              AND lh.del_flg   = 'N'
              AND lh.ins_date >= :slotFrom
              AND lh.ins_date <  :slotTo
        """.trimIndent()

        return jdbcTemplate.queryForObject(sql, slotParams(plantCd, slot)) { rs, _ ->
            val total = rs.getBigDecimal("total_qty")
            val ng = rs.getBigDecimal("ng_qty")
            mapOf(
                "totalQty" to Rs.qty(rs, "total_qty"),
                "ngQty" to Rs.qty(rs, "ng_qty"),
                "labelCount" to rs.getLong("label_cnt"),
                "defectRate" to com.dwje.api.common.util.safeRate(ng, total)
            )
        } ?: emptyMap()
    }

    /**
     * 칸 하나의 불량 유형 상세 — 유형별 안분 수량과 원표 속성. (No.22 칸 클릭 상세)
     *
     * 수량은 불량 유형 구성(No.25)과 같이 라벨 원장 불량을 유형 구성비로 안분한 값이다.
     * 그래서 유형 합 + 유형 미상 = 칸의 불량 수량이 성립한다. 원표 합계(`rawQty`)는
     * 안분 전 값으로 따로 낸다 — 라벨 불량 수량과 어긋날 수 있어 비율의 분모로 쓰지 않는다.
     *
     * 원표(`tb_pop_defect_hist`)에 있는 속성은 유형별로 모아 전부 낸다 — 품목, 공정, 비고,
     * 등록자, 최초·최종 시각, 이력 건수, LOT 수. 마스터(`tb_md_defect`)의 사용 여부·비고도 붙인다.
     */
    fun findSlotDefectDetails(plantCd: String, slot: TimeWindow): List<Map<String, Any?>> {
        val sql = """
            WITH
            ${DefectSql.labelLedgerCte("slot_label", "slotFrom", "slotTo")},
            ${DefectSql.apportionedTypeCte("cur", "slot_label")},
            raw AS (
                SELECT dh.defect_cd,
                       coalesce(sum(dh.qty), 0)                                   AS raw_qty,
                       count(*)                                                   AS record_cnt,
                       count(DISTINCT dh.lot_no)                                  AS lot_cnt,
                       count(DISTINCT dh.item_cd)                                 AS item_cnt,
                       string_agg(DISTINCT dh.item_cd, ',')                       AS item_cds,
                       string_agg(DISTINCT dh.wc_cd, ',')                         AS wc_cds,
                       string_agg(DISTINCT nullif(trim(dh.remark), ''), ' | ')    AS remarks,
                       string_agg(DISTINCT nullif(trim(dh.ins_user), ''), ',')    AS ins_users,
                       min(dh.ins_date)                                           AS first_at,
                       max(dh.ins_date)                                           AS last_at
                FROM slot_label l
                INNER JOIN mes.tb_pop_defect_hist dh
                        ON dh.plant_cd  = l.plant_cd
                       AND dh.wc_cd     = l.wc_cd
                       AND dh.lot_no    = l.lot_no
                       AND dh.serial_no = l.serial_no
                       ${DefectSql.excludeNonProduction()}
                GROUP BY dh.defect_cd
            )
            SELECT
                raw.defect_cd,
                coalesce(md.defect_nm, raw.defect_cd) AS defect_nm,
                md.use_flg                            AS md_use_flg,
                md.remark                             AS md_remark,
                coalesce(cur.ng_qty, 0)               AS ng_qty,
                raw.raw_qty, raw.record_cnt, raw.lot_cnt, raw.item_cnt,
                raw.item_cds, raw.wc_cds, raw.remarks, raw.ins_users,
                raw.first_at, raw.last_at
            FROM raw
            LEFT JOIN cur ON cur.defect_cd = raw.defect_cd
            LEFT JOIN mes.tb_md_defect md
                   ON md.plant_cd = :plantCd AND md.defect_cd = raw.defect_cd
            ORDER BY coalesce(cur.ng_qty, 0) DESC, raw.raw_qty DESC, raw.defect_cd
        """.trimIndent()

        return jdbcTemplate.query(sql, slotParams(plantCd, slot)) { rs, _ ->
            mapOf(
                "defectCd" to rs.getString("defect_cd"),
                "defectNm" to rs.getString("defect_nm"),
                "useFlg" to rs.getString("md_use_flg"),
                "masterRemark" to rs.getString("md_remark"),
                "ngQty" to Rs.qty(rs, "ng_qty"),
                "rawQty" to Rs.qty(rs, "raw_qty"),
                "recordCount" to rs.getLong("record_cnt"),
                "lotCount" to rs.getLong("lot_cnt"),
                "itemCount" to rs.getLong("item_cnt"),
                "itemCds" to splitList(rs.getString("item_cds"), ","),
                "processIds" to splitList(rs.getString("wc_cds"), ","),
                "remarks" to splitList(rs.getString("remarks"), " | "),
                "insUsers" to splitList(rs.getString("ins_users"), ","),
                "firstAt" to Rs.dateTime(rs, "first_at"),
                "lastAt" to Rs.dateTime(rs, "last_at")
            )
        }
    }

    private fun splitList(joined: String?, separator: String): List<String> =
        joined?.split(separator)?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    private fun slotParams(plantCd: String, slot: TimeWindow): MapSqlParameterSource =
        MapSqlParameterSource()
            .addValue("plantCd", plantCd)
            .addValue("slotFrom", slot.from)
            .addValue("slotTo", slot.toExclusive)

    /**
     * 해당 일자의 라벨 원장 불량 총량을 조회한다. (No.25 — 유형 구성비의 분모)
     *
     * [findDefectComposition] 의 유형 합계는 유형이 붙지 않은 불량이 빠져 이 값보다 작다.
     * 표시된 유형만으로 분모를 잡으면 비중이 부풀려지므로 분모는 이 값을 쓴다.
     */
    fun findDefectLedgerTotal(plantCd: String, date: LocalDate, processId: String?): Long =
        findDefectLedgerTotal(plantCd, TimeWindow.ofDay(date), processId)

    /** 라벨 원장 불량 총량 — 집계 구간을 직접 지정한다. */
    fun findDefectLedgerTotal(plantCd: String, window: TimeWindow, processId: String?): Long {
        val sql = StringBuilder(
            """
            SELECT coalesce(sum(coalesce(lh.defect, 0)), 0) AS ng_qty
            FROM mes.tb_pop_label_hist lh
            WHERE lh.plant_cd  = :plantCd
              AND lh.del_flg   = 'N'
              AND lh.ins_date >= :dayStart
              AND lh.ins_date <  :dayEnd
            """.trimIndent()
        )

        val params = dayParams(plantCd, window)

        if (!processId.isNullOrBlank()) {
            sql.append(" AND lh.wc_cd = :processId")
            params.addValue("processId", processId.trim())
        }

        return jdbcTemplate.queryForObject(sql.toString(), params) { rs, _ ->
            Rs.qty(rs, "ng_qty") ?: 0L
        } ?: 0L
    }

    /**
     * 공정별 수율을 조회한다. (No.26)
     */
    fun findProcessYield(plantCd: String, date: LocalDate): List<Map<String, Any?>> =
        findProcessYield(plantCd, TimeWindow.ofDay(date))

    /** 공정별 수율 — 집계 구간을 직접 지정한다. */
    fun findProcessYield(plantCd: String, window: TimeWindow): List<Map<String, Any?>> {
        val sql = """
            SELECT
                lh.wc_cd,
                coalesce(max(w.wc_nm), lh.wc_cd)                          AS wc_nm,
                coalesce(sum(lh.normal), 0)                               AS ok_qty,
                coalesce(sum(lh.defect), 0)                               AS ng_qty,
                coalesce(sum(lh.normal), 0) + coalesce(sum(lh.defect), 0) AS total_qty,
                max(w.sort_seq)                                           AS sort_seq
            FROM mes.tb_pop_label_hist lh
            LEFT JOIN mes.tb_md_workcenter w
                   ON w.plant_cd = lh.plant_cd AND w.wc_cd = lh.wc_cd
            WHERE lh.plant_cd  = :plantCd
              AND lh.del_flg   = 'N'
              AND lh.ins_date >= :dayStart
              AND lh.ins_date <  :dayEnd
            GROUP BY lh.wc_cd
            ORDER BY max(w.sort_seq) NULLS LAST, lh.wc_cd
        """.trimIndent()

        return jdbcTemplate.query(sql, dayParams(plantCd, window)) { rs, _ ->
            mapOf(
                "processId" to rs.getString("wc_cd"),
                "process" to rs.getString("wc_nm"),
                "okQty" to Rs.qty(rs, "ok_qty"),
                "ngQty" to Rs.qty(rs, "ng_qty"),
                "qty" to Rs.qty(rs, "total_qty"),
                "yield" to com.dwje.api.common.util.safeRate(
                    rs.getBigDecimal("ok_qty"), rs.getBigDecimal("total_qty")
                )
            )
        }
    }

    /**
     * 생산 계획 대비 실적을 조회한다. (No.27)
     *
     * 계획 수량은 재고 이동 이력(mes.tb_pop_stock_hist)의 계획 유형 전표를,
     * 실적은 라벨 이력의 생산 수량을 시간대별로 집계한다.
     *
     * @param intervalHour 집계 구간 시간
     */
    fun findPlanVsActual(plantCd: String, date: LocalDate, intervalHour: Int): List<Map<String, Any?>> =
        findPlanVsActual(plantCd, TimeWindow.ofDay(date), intervalHour)

    /** 계획 대비 실적 — 집계 구간을 직접 지정한다. 칸 단위는 구간 길이가 정한다. */
    fun findPlanVsActual(plantCd: String, window: TimeWindow, intervalHour: Int): List<Map<String, Any?>> {
        val bucket = SlotBucket.of(window, intervalHour)
        val sql = """
            WITH slots AS (
                SELECT generate_series(
                    ${bucket.slotExpr("(:dayStart)::timestamp")},
                    (:dayEnd)::timestamp - ${bucket.stepInterval()},
                    ${bucket.stepInterval()}
                ) AS slot_at
            ),
            actual AS (
                SELECT
                    ${bucket.slotExpr("lh.ins_date")}                                                   AS slot_at,
                    coalesce(sum(lh.normal), 0) + coalesce(sum(lh.defect), 0)                          AS qty
                FROM mes.tb_pop_label_hist lh
                WHERE lh.plant_cd  = :plantCd
                  AND lh.del_flg   = 'N'
                  AND lh.ins_date >= :dayStart
                  AND lh.ins_date <  :dayEnd
                GROUP BY 1
            ),
            plan AS (
                SELECT
                    ${bucket.slotExpr("sh.ins_date")}                                                   AS slot_at,
                    coalesce(sum(sh.qty), 0)                                                           AS qty
                FROM mes.tb_pop_stock_hist sh
                WHERE sh.plant_cd  = :plantCd
                  AND sh.hist_type = 'PLAN'
                  AND sh.ins_date >= :dayStart
                  AND sh.ins_date <  :dayEnd
                GROUP BY 1
            )
            SELECT
                to_char(s.slot_at, '${bucket.format}') AS slot,
                coalesce(plan.qty, 0)           AS plan_qty,
                coalesce(actual.qty, 0)         AS actual_qty
            FROM slots s
            LEFT JOIN plan   ON plan.slot_at   = s.slot_at
            LEFT JOIN actual ON actual.slot_at = s.slot_at
            ORDER BY s.slot_at
        """.trimIndent()

        val params = dayParams(plantCd, window).addValue("intervalHour", bucket.intervalHour)

        return jdbcTemplate.query(sql, params) { rs, _ ->
            mapOf(
                "slot" to rs.getString("slot"),
                "plan" to Rs.qty(rs, "plan_qty"),
                "actual" to Rs.qty(rs, "actual_qty")
            )
        }
    }

    /**
     * 설비별 시간대 가동률 히트맵 데이터를 조회한다. (No.28 / No.40)
     *
     * @param processId    공정 코드
     * @param intervalHour 집계 구간 시간
     */
    fun findEquipmentUptimeHeatmap(
        plantCd: String,
        date: LocalDate,
        processId: String?,
        intervalHour: Int
    ): List<Map<String, Any?>> =
        findEquipmentUptimeHeatmap(plantCd, TimeWindow.ofDay(date), processId, intervalHour)

    /** 설비별 가동률 히트맵 — 집계 구간을 직접 지정한다. */
    fun findEquipmentUptimeHeatmap(
        plantCd: String,
        window: TimeWindow,
        processId: String?,
        intervalHour: Int
    ): List<Map<String, Any?>> {
        val bucket = SlotBucket.of(window, intervalHour)
        val sql = StringBuilder(
            """
            SELECT
                mv.eqpt_cd,
                coalesce(max(e.eqpt_nm), mv.eqpt_cd) AS eqpt_nm,
                ${bucket.labelExpr("mv.measured_at")} AS slot,
                min(mv.measured_at)                  AS slot_at,
                round(avg(mv.metric_value), 2)       AS uptime_rate
            FROM ax.tb_met_metric_value mv
            INNER JOIN ax.tb_met_metric_std ms ON ms.metric_id = mv.metric_id
            LEFT  JOIN mes.tb_md_eqpt e
                    ON e.plant_cd = :plantCd AND e.eqpt_cd = mv.eqpt_cd
            WHERE ms.metric_cd    = 'EQPT_UPTIME_RATE'
              AND mv.eqpt_cd     IS NOT NULL
              AND mv.measured_at >= :dayStart
              AND mv.measured_at <  :dayEnd
              AND (mv.plant_cd IS NULL OR mv.plant_cd = :plantCd)
            """.trimIndent()
        )

        val params = dayParams(plantCd, window).addValue("intervalHour", bucket.intervalHour)

        if (!processId.isNullOrBlank()) {
            sql.append(" AND mv.wc_cd = :processId")
            params.addValue("processId", processId.trim())
        }

        sql.append("\nGROUP BY mv.eqpt_cd, 3\nORDER BY mv.eqpt_cd, min(mv.measured_at)")

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "eqptCd" to rs.getString("eqpt_cd"),
                "eqptNm" to rs.getString("eqpt_nm"),
                "slot" to rs.getString("slot"),
                "value" to Rs.rate(rs, "uptime_rate")
            )
        }
    }

    /**
     * 라인별 현황 목록의 전체 건수를 조회한다. (No.29 — 페이징 meta.total)
     *
     * 목록 본문과 달리 생산·가동률 LATERAL 이 필요 없으므로 설비 기준만 센다.
     */
    fun countLines(plantCd: String, date: LocalDate, processId: String?): Long =
        countLines(plantCd, TimeWindow.ofDay(date), processId)

    /** 라인 목록 전체 건수 — 집계 구간을 직접 지정한다. */
    fun countLines(plantCd: String, window: TimeWindow, processId: String?): Long {
        val sql = StringBuilder(
            """
            SELECT count(*)
            FROM mes.tb_md_eqpt e
            INNER JOIN mes.tb_md_eqpt_by_workcenter ew
                    ON ew.plant_cd = e.plant_cd AND ew.eqpt_cd = e.eqpt_cd
            WHERE e.plant_cd = :plantCd
              AND e.use_flg  = 'Y'
            """.trimIndent()
        )

        val params = dayParams(plantCd, window)

        if (!processId.isNullOrBlank()) {
            sql.append(" AND ew.wc_cd = :processId")
            params.addValue("processId", processId.trim())
        }

        return jdbcTemplate.queryForObject(sql.toString(), params, Long::class.java) ?: 0L
    }

    /**
     * 라인별 현황 목록을 조회한다. (No.29 — 행 클릭 시 설비 상세로 연결)
     *
     * @param limit  쪽 크기. null 이면 전량 조회
     * @param offset 건너뛸 건수
     */
    fun findLines(
        plantCd: String,
        date: LocalDate,
        processId: String?,
        limit: Int?,
        offset: Int
    ): List<Map<String, Any?>> =
        findLines(plantCd, TimeWindow.ofDay(date), processId, limit, offset)

    /** 라인별 현황 목록 — 집계 구간을 직접 지정한다. */
    fun findLines(
        plantCd: String,
        window: TimeWindow,
        processId: String?,
        limit: Int?,
        offset: Int
    ): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT
                e.eqpt_cd,
                e.eqpt_nm,
                e.model_nm,
                ew.wc_cd,
                w.wc_nm,
                coalesce(prod.ok_qty, 0)                                  AS ok_qty,
                coalesce(prod.ng_qty, 0)                                  AS ng_qty,
                coalesce(prod.ok_qty, 0) + coalesce(prod.ng_qty, 0)       AS total_qty,
                uptime.uptime_rate,
                uptime.last_measured_at,
                item.top_product,
                item.top_product_nm,
                coalesce(item.product_etc_cnt, 0)                         AS product_etc_cnt
            FROM mes.tb_md_eqpt e
            INNER JOIN mes.tb_md_eqpt_by_workcenter ew
                    ON ew.plant_cd = e.plant_cd AND ew.eqpt_cd = e.eqpt_cd
            LEFT JOIN mes.tb_md_workcenter w
                   ON w.plant_cd = ew.plant_cd AND w.wc_cd = ew.wc_cd
            -- 설비 마스터의 model_nm 은 1,511대 전부 null 이라 제품 칸을 채울 수 없다.
            -- 그 구간 실적의 품목을 제품 마스터로 옮겨 실제로 돌린 제품을 낸다.
            LEFT JOIN LATERAL (
                SELECT
                    (array_agg(t.product    ORDER BY t.qty DESC))[1] AS top_product,
                    (array_agg(t.product_nm ORDER BY t.qty DESC))[1] AS top_product_nm,
                    count(*) - 1                                     AS product_etc_cnt
                FROM (
                    SELECT
                        coalesce(p.model_cd, lh.item_cd)                          AS product,
                        p.model_nm                                                AS product_nm,
                        coalesce(sum(lh.normal), 0) + coalesce(sum(lh.defect), 0) AS qty
                    FROM mes.tb_pop_label_hist lh
                    LEFT JOIN ax.tb_prod_item_map pm
                           ON pm.plant_cd = lh.plant_cd AND pm.item_cd = lh.item_cd
                    LEFT JOIN ax.tb_prod_product p ON p.product_id = pm.product_id
                    WHERE lh.plant_cd  = e.plant_cd
                      AND lh.eqpt_cd   = e.eqpt_cd
                      AND lh.del_flg   = 'N'
                      AND lh.ins_date >= :dayStart
                      AND lh.ins_date <  :dayEnd
                    GROUP BY coalesce(p.model_cd, lh.item_cd), p.model_nm
                ) t
            ) item ON true
            LEFT JOIN LATERAL (
                SELECT
                    coalesce(sum(lh.normal), 0) AS ok_qty,
                    coalesce(sum(lh.defect), 0) AS ng_qty
                FROM mes.tb_pop_label_hist lh
                WHERE lh.plant_cd  = e.plant_cd
                  AND lh.eqpt_cd   = e.eqpt_cd
                  AND lh.del_flg   = 'N'
                  AND lh.ins_date >= :dayStart
                  AND lh.ins_date <  :dayEnd
            ) prod ON true
            LEFT JOIN LATERAL (
                SELECT
                    round(avg(mv.metric_value), 2) AS uptime_rate,
                    max(mv.measured_at)            AS last_measured_at
                FROM ax.tb_met_metric_value mv
                INNER JOIN ax.tb_met_metric_std ms ON ms.metric_id = mv.metric_id
                WHERE ms.metric_cd    = 'EQPT_UPTIME_RATE'
                  AND mv.eqpt_cd      = e.eqpt_cd
                  AND mv.measured_at >= :dayStart
                  AND mv.measured_at <  :dayEnd
            ) uptime ON true
            WHERE e.plant_cd = :plantCd
              AND e.use_flg  = 'Y'
            """.trimIndent()
        )

        val params = dayParams(plantCd, window)

        if (!processId.isNullOrBlank()) {
            sql.append(" AND ew.wc_cd = :processId")
            params.addValue("processId", processId.trim())
        }

        sql.append("\nORDER BY ew.wc_cd, e.eqpt_cd")

        // 설비 한 건마다 생산·가동률 LATERAL 이 두 번 도므로 쪽 단위로 잘라 받는다.
        if (limit != null) {
            sql.append("\nLIMIT :limit OFFSET :offset")
            params.addValue("limit", limit)
            params.addValue("offset", offset)
        }

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            val uptime = Rs.rate(rs, "uptime_rate")
            val lastAt = Rs.dateTime(rs, "last_measured_at")
            mapOf(
                "eqptCd" to rs.getString("eqpt_cd"),
                "eqptNm" to rs.getString("eqpt_nm"),
                "model" to rs.getString("model_nm"),
                "processId" to rs.getString("wc_cd"),
                "processNm" to rs.getString("wc_nm"),
                // 그 구간에 실제로 돌린 제품 — 설비 마스터가 비어 있어 실적에서 낸다.
                "product" to rs.getString("top_product"),
                "productNm" to rs.getString("top_product_nm"),
                "productEtcCnt" to rs.getInt("product_etc_cnt"),
                "qty" to Rs.qty(rs, "total_qty"),
                "okQty" to Rs.qty(rs, "ok_qty"),
                "ngQty" to Rs.qty(rs, "ng_qty"),
                "defectRate" to com.dwje.api.common.util.safeRate(
                    rs.getBigDecimal("ng_qty"), rs.getBigDecimal("total_qty")
                ),
                "uptimeRate" to uptime,
                "lastCollectedAt" to lastAt,
                // 가동률 실측이 없으면 정지, 기준 미만이면 경고로 판정한다.
                "state" to when {
                    uptime == null -> "STOPPED"
                    uptime < 60.0 -> "WARNING"
                    else -> "RUNNING"
                }
            )
        }
    }

    /**
     * 설비 × 제품 실적을 조회한다. (실적 집계 조회 3단계)
     *
     * [findLines] 는 설비 한 대에 한 행이라, 두 제품 이상 돌린 설비는 수량이 대표 제품
     * 한 칸에 몰린다(2026-09-03 기준 512대 중 76대가 2종 이상, 최대 10종). 제품으로
     * 묶어 그리려면 설비가 만든 제품마다 행이 나뉘어야 한다.
     *
     * 실적이 있는 설비만 나온다 — 안 돌린 설비는 3단계에 그릴 것이 없다.
     */
    fun findLineProducts(
        plantCd: String,
        window: TimeWindow,
        processId: String?,
        limit: Int?,
        offset: Int
    ): List<Map<String, Any?>> {
        val sql = StringBuilder(lineProductBaseSql())
        val params = dayParams(plantCd, window)

        if (!processId.isNullOrBlank()) {
            sql.append(" AND lh.wc_cd = :processId")
            params.addValue("processId", processId.trim())
        }

        sql.append("\nGROUP BY lh.eqpt_cd, lh.wc_cd, coalesce(p.model_cd, lh.item_cd)")
        sql.append("\nORDER BY lh.wc_cd, lh.eqpt_cd, coalesce(p.model_cd, lh.item_cd)")

        if (limit != null) {
            sql.append("\nLIMIT :limit OFFSET :offset")
            params.addValue("limit", limit)
            params.addValue("offset", offset)
        }

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "eqptCd" to rs.getString("eqpt_cd"),
                "eqptNm" to rs.getString("eqpt_nm"),
                "processId" to rs.getString("wc_cd"),
                "processNm" to rs.getString("wc_nm"),
                "product" to rs.getString("product"),
                "productNm" to rs.getString("product_nm"),
                "qty" to Rs.qty(rs, "total_qty"),
                "okQty" to Rs.qty(rs, "ok_qty"),
                "ngQty" to Rs.qty(rs, "ng_qty"),
                "defectRate" to com.dwje.api.common.util.safeRate(
                    rs.getBigDecimal("ng_qty"), rs.getBigDecimal("total_qty")
                )
            )
        }
    }

    /** 설비 × 제품 실적의 전체 건수. (페이징 meta.total) */
    fun countLineProducts(plantCd: String, window: TimeWindow, processId: String?): Long {
        val inner = StringBuilder(
            """
            SELECT 1
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
        val params = dayParams(plantCd, window)

        if (!processId.isNullOrBlank()) {
            inner.append(" AND lh.wc_cd = :processId")
            params.addValue("processId", processId.trim())
        }

        inner.append("\nGROUP BY lh.eqpt_cd, lh.wc_cd, coalesce(p.model_cd, lh.item_cd)")

        val sql = "SELECT count(*) FROM (\n$inner\n) t"
        return jdbcTemplate.queryForObject(sql, params, Long::class.java) ?: 0L
    }

    /**
     * 일자 × 설비 × 제품 실적 — 실적 집계 화면 전체 내려받기(일자→제품→설비 트리)용.
     *
     * [findLineProducts] 와 **같은 본문 SQL** 에 일자 그룹만 더한 것이다. 화면은 일자마다
     * `/dashboard/ai/line-products?date=` 를 한 번씩 부르는데, 내려받기는 기간을 한 번에 읽어
     * 같은 행을 만든다. 정렬은 일자 내림차순(화면 표와 같다) → 제품 → 공정 → 설비.
     *
     * 페이지 제한이 없다 — 화면이 쪽을 나누어 보여 준 것을 전부 담아야 하기 때문이다.
     * (하루 620행 안팎 · 2026-09-03 기준. 31일이면 2만 행 규모)
     */
    fun findLineProductsByDay(plantCd: String, window: TimeWindow): List<Map<String, Any?>> {
        val sql = lineProductBaseSql()
            .replaceFirst(
                Regex("""SELECT\s+lh\.eqpt_cd,"""),
                "SELECT\n    to_char(date_trunc('day', lh.ins_date), 'YYYY-MM-DD') AS period,\n    lh.eqpt_cd,"
            ) +
            "\nGROUP BY 1, lh.eqpt_cd, lh.wc_cd, coalesce(p.model_cd, lh.item_cd)" +
            "\nORDER BY 1 DESC, coalesce(p.model_cd, lh.item_cd), lh.wc_cd, lh.eqpt_cd"
        require(sql.contains("AS period")) { "line-products 본문 SQL 의 SELECT 머리가 바뀌어 일자 열을 끼울 수 없다." }

        return jdbcTemplate.query(sql, dayParams(plantCd, window)) { rs, _ ->
            mapOf(
                "period" to rs.getString("period"),
                "eqptCd" to rs.getString("eqpt_cd"),
                "eqptNm" to rs.getString("eqpt_nm"),
                "processId" to rs.getString("wc_cd"),
                "processNm" to rs.getString("wc_nm"),
                "product" to rs.getString("product"),
                "productNm" to rs.getString("product_nm"),
                "qty" to Rs.qty(rs, "total_qty"),
                "okQty" to Rs.qty(rs, "ok_qty"),
                "ngQty" to Rs.qty(rs, "ng_qty"),
                "defectRate" to com.dwje.api.common.util.safeRate(
                    rs.getBigDecimal("ng_qty"), rs.getBigDecimal("total_qty")
                )
            )
        }
    }

    /**
     * 설비 × 제품 실적의 본문 SQL.
     *
     * 제품 식별자는 [findLines] 의 `product` 와 같은 식(`coalesce(model_cd, item_cd)`)이다
     * — 두 응답을 화면에서 맞붙일 수 있어야 한다.
     */
    private fun lineProductBaseSql(): String =
        """
        SELECT
            lh.eqpt_cd,
            coalesce(max(e.eqpt_nm), lh.eqpt_cd)                      AS eqpt_nm,
            lh.wc_cd,
            max(w.wc_nm)                                              AS wc_nm,
            coalesce(p.model_cd, lh.item_cd)                          AS product,
            max(p.model_nm)                                           AS product_nm,
            coalesce(sum(lh.normal), 0)                               AS ok_qty,
            coalesce(sum(lh.defect), 0)                               AS ng_qty,
            coalesce(sum(lh.normal), 0) + coalesce(sum(lh.defect), 0) AS total_qty
        FROM mes.tb_pop_label_hist lh
        LEFT JOIN mes.tb_md_eqpt e
               ON e.plant_cd = lh.plant_cd AND e.eqpt_cd = lh.eqpt_cd
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

    /**
     * 설비 상세를 조회한다. (No.30 — 모달)
     *
     * @param eqptCd 설비 코드
     */
    fun findEquipmentDetail(plantCd: String, eqptCd: String, date: LocalDate): Map<String, Any?>? {
        val sql = """
            SELECT
                e.eqpt_cd,
                e.eqpt_nm,
                e.model_nm,
                e.spec,
                e.manufacturer,
                ew.wc_cd,
                w.wc_nm,
                coalesce(prod.ok_qty, 0)                            AS ok_qty,
                coalesce(prod.ng_qty, 0)                            AS ng_qty,
                coalesce(prod.ok_qty, 0) + coalesce(prod.ng_qty, 0) AS total_qty,
                uptime.uptime_rate,
                uptime.last_measured_at,
                mold.mold_cd,
                mold.mold_nm
            FROM mes.tb_md_eqpt e
            LEFT JOIN mes.tb_md_eqpt_by_workcenter ew
                   ON ew.plant_cd = e.plant_cd AND ew.eqpt_cd = e.eqpt_cd
            LEFT JOIN mes.tb_md_workcenter w
                   ON w.plant_cd = ew.plant_cd AND w.wc_cd = ew.wc_cd
            LEFT JOIN LATERAL (
                SELECT coalesce(sum(lh.normal),0) AS ok_qty, coalesce(sum(lh.defect),0) AS ng_qty
                FROM mes.tb_pop_label_hist lh
                WHERE lh.plant_cd = e.plant_cd AND lh.eqpt_cd = e.eqpt_cd
                  AND lh.del_flg = 'N'
                  AND lh.ins_date >= :dayStart AND lh.ins_date < :dayEnd
            ) prod ON true
            LEFT JOIN LATERAL (
                SELECT round(avg(mv.metric_value),2) AS uptime_rate, max(mv.measured_at) AS last_measured_at
                FROM ax.tb_met_metric_value mv
                INNER JOIN ax.tb_met_metric_std ms ON ms.metric_id = mv.metric_id
                WHERE ms.metric_cd = 'EQPT_UPTIME_RATE'
                  AND mv.eqpt_cd = e.eqpt_cd
                  AND mv.measured_at >= :dayStart AND mv.measured_at < :dayEnd
            ) uptime ON true
            LEFT JOIN LATERAL (
                SELECT m.mold_cd, m.mold_nm
                FROM mes.tb_md_mold_by_eqpt me
                INNER JOIN mes.tb_md_mold m
                        ON m.plant_cd = me.plant_cd AND m.mold_cd = me.mold_cd
                WHERE me.plant_cd = e.plant_cd AND me.eqpt_cd = e.eqpt_cd
                ORDER BY me.ins_date DESC
                LIMIT 1
            ) mold ON true
            WHERE e.plant_cd = :plantCd
              AND e.eqpt_cd  = :eqptCd
        """.trimIndent()

        val params = dayParams(plantCd, date).addValue("eqptCd", eqptCd)

        return jdbcTemplate.query(sql, params) { rs, _ ->
            val uptime = Rs.rate(rs, "uptime_rate")
            val lastAt = rs.getObject("last_measured_at")
            mapOf(
                "eqptCd" to rs.getString("eqpt_cd"),
                "eqptNm" to rs.getString("eqpt_nm"),
                "model" to rs.getString("model_nm"),
                "spec" to rs.getString("spec"),
                "manufacturer" to rs.getString("manufacturer"),
                "workcenter" to rs.getString("wc_nm"),
                "processId" to rs.getString("wc_cd"),
                "qty" to Rs.qty(rs, "total_qty"),
                "okQty" to Rs.qty(rs, "ok_qty"),
                "ngQty" to Rs.qty(rs, "ng_qty"),
                "defectRate" to com.dwje.api.common.util.safeRate(
                    rs.getBigDecimal("ng_qty"), rs.getBigDecimal("total_qty")
                ),
                "uptimeRate" to uptime,
                "iotState" to if (lastAt != null) "COLLECTING" else "NO_DATA",
                "lastCollectedAt" to Rs.dateTime(rs, "last_measured_at"),
                "moldCd" to rs.getString("mold_cd"),
                "moldNm" to rs.getString("mold_nm")
            )
        }.firstOrNull()
    }

    /**
     * 설비의 최근 정지 경과 시간을 조회한다. (No.30 — stopElapsedMin)
     */
    fun findStopElapsedMinutes(plantCd: String, eqptCd: String): Int? {
        val sql = """
            SELECT extract(epoch FROM (now() - max(mv.measured_at))) / 60 AS elapsed_min
            FROM ax.tb_met_metric_value mv
            INNER JOIN ax.tb_met_metric_std ms ON ms.metric_id = mv.metric_id
            WHERE ms.metric_cd = 'EQPT_UPTIME_RATE'
              AND mv.eqpt_cd   = :eqptCd
              AND (mv.plant_cd IS NULL OR mv.plant_cd = :plantCd)
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("plantCd", plantCd)
            .addValue("eqptCd", eqptCd)

        return jdbcTemplate.query(sql, params) { rs, _ -> Rs.doubleOrNull(rs, "elapsed_min")?.toInt() }
            .firstOrNull()
    }

    /**
     * 최근 이상 알림 요약을 조회한다. (No.31)
     *
     * @param hours 조회 시간 범위
     */
    fun findRecentAlerts(plantCd: String, hours: Int, limit: Int): List<Map<String, Any?>> {
        val sql = """
            SELECT
                a.alert_id,
                a.severity_cd,
                a.title,
                a.evidence_desc,
                a.target_desc,
                a.eqpt_cd,
                a.occurred_at,
                a.ack_state_cd,
                extract(epoch FROM (now() - a.occurred_at)) / 60 AS elapsed_min,
                ag.agent_no,
                ag.agent_nm
            FROM ax.tb_alm_alert a
            LEFT JOIN ax.tb_ai_agent ag ON ag.agent_id = a.detect_agent_id
            WHERE a.occurred_at >= now() - make_interval(hours => :hours)
              AND (a.plant_cd IS NULL OR a.plant_cd = :plantCd)
            ORDER BY
                CASE a.severity_cd WHEN 'CRIT' THEN 1 WHEN 'WARN' THEN 2 ELSE 3 END,
                a.occurred_at DESC
            LIMIT :limit
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("plantCd", plantCd)
            .addValue("hours", hours)
            .addValue("limit", limit)

        return jdbcTemplate.query(sql, params) { rs, _ ->
            mapOf(
                "alertId" to rs.getLong("alert_id"),
                "level" to rs.getString("severity_cd"),
                "title" to rs.getString("title"),
                "desc" to (rs.getString("evidence_desc") ?: rs.getString("target_desc")),
                "eqptCd" to rs.getString("eqpt_cd"),
                "occurredAt" to Rs.dateTime(rs, "occurred_at"),
                "elapsed" to com.dwje.api.common.util.DateUtils.humanizeMinutes(
                    Rs.doubleOrNull(rs, "elapsed_min")?.toLong()
                ),
                "ackState" to rs.getString("ack_state_cd"),
                "agent" to (rs.getString("agent_nm")?.let { "${rs.getString("agent_no")} $it" })
            )
        }
    }

    /**
     * Agent 작동 현황 요약을 조회한다. (No.32 / No.211)
     *
     * 각 Agent 의 최신 실행 이력에서 상태·처리량을 가져온다.
     */
    fun findAgentStatus(): List<Map<String, Any?>> {
        val sql = """
            SELECT
                a.agent_id,
                a.agent_no,
                a.agent_nm,
                a.agent_desc,
                r.state_cd,
                r.run_at,
                r.throughput_txt,
                r.elapsed_ms,
                r.err_flg
            FROM ax.tb_ai_agent a
            LEFT JOIN LATERAL (
                SELECT ar.state_cd, ar.run_at, ar.throughput_txt, ar.elapsed_ms, ar.err_flg
                FROM ax.tb_ai_agent_run ar
                WHERE ar.agent_id = a.agent_id
                ORDER BY ar.run_at DESC
                LIMIT 1
            ) r ON true
            WHERE a.use_flg = 'Y'
            ORDER BY a.sort_seq
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource()) { rs, _ ->
            mapOf(
                "agentId" to rs.getInt("agent_id"),
                "no" to rs.getString("agent_no"),
                "name" to rs.getString("agent_nm"),
                "desc" to rs.getString("agent_desc"),
                "state" to (rs.getString("state_cd") ?: "IDLE"),
                "last" to Rs.dateTime(rs, "run_at"),
                "load" to rs.getString("throughput_txt"),
                "elapsedMs" to Rs.intOrNull(rs, "elapsed_ms"),
                "error" to Rs.yn(rs, "err_flg")
            )
        }
    }

    /**
     * Master AI 상태를 판정한다. (No.32 — master{state,mode})
     *
     * 최근 10분 내 오류 실행이 있으면 ERROR, 실행 중이면 RUNNING, 그 외 정상으로 본다.
     *
     * **그 창에 행이 하나도 없으면 IDLE 이다.** 예전에는 OK 를 돌려줬는데,
     * 아무것도 안 돌고 있는 상태와 정상을 같은 값으로 보이게 해 장애를 정상으로 읽히게 했다.
     * (SY-12 요약도 같은 규칙이다 — [AgentRunRepository.findSummary])
     */
    fun findMasterState(): Map<String, Any?> {
        val sql = """
            SELECT
                count(*) FILTER (WHERE r.err_flg = 'Y')          AS err_cnt,
                count(*) FILTER (WHERE r.state_cd = 'RUNNING')   AS running_cnt,
                count(*)                                         AS total_cnt,
                round(avg(r.elapsed_ms))                         AS avg_elapsed_ms
            FROM ax.tb_ai_agent_run r
            WHERE r.run_at >= now() - interval '10 minutes'
        """.trimIndent()

        return jdbcTemplate.queryForObject(sql, MapSqlParameterSource()) { rs, _ ->
            val errCnt = rs.getLong("err_cnt")
            val runningCnt = rs.getLong("running_cnt")
            mapOf(
                "state" to when {
                    errCnt > 0 -> "ERROR"
                    runningCnt > 0 -> "RUNNING"
                    rs.getLong("total_cnt") > 0 -> "OK"
                    else -> "IDLE"
                },
                "mode" to "AUTO",
                "recentRunCnt" to rs.getLong("total_cnt"),
                "avgElapsedMs" to Rs.intOrNull(rs, "avg_elapsed_ms")
            )
        } ?: mapOf("state" to "IDLE", "mode" to "AUTO")
    }

    /**
     * 일자 범위 공통 파라미터 — 업무일 기준 (전날 08:00 ~ 그 날 08:00)
     *
     * 달력 하루가 아니다. 공장의 하루는 08:00 교대로 끊기므로 [BusinessDay] 를 쓴다.
     */
    private fun dayParams(plantCd: String, date: LocalDate): MapSqlParameterSource =
        dayParams(plantCd, BusinessDay.of(date))

    /**
     * 집계 구간 파라미터 — 자정을 넘는 구간(일일 생산현황 보고)도 담을 수 있다.
     *
     * `dayStart`/`dayEnd` 라는 이름은 하루 단위 호출처가 많아 유지한다.
     */
    private fun dayParams(plantCd: String, window: TimeWindow): MapSqlParameterSource =
        MapSqlParameterSource()
            .addValue("plantCd", plantCd)
            .addValue("dayStart", window.from)
            .addValue("dayEnd", window.toExclusive)
}
