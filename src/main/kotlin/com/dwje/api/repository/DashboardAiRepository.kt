package com.dwje.api.repository

import com.dwje.api.common.util.DefectSql
import com.dwje.api.common.util.Rs
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.LocalDate

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
    fun findSummary(plantCd: String, date: LocalDate): Map<String, Any?> {
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

        val params = dayParams(plantCd, date)

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
    fun findPendingBorderline(plantCd: String, date: LocalDate): Map<String, Any?> {
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

        return jdbcTemplate.queryForObject(sql, dayParams(plantCd, date)) { rs, _ ->
            mapOf(
                "cnt" to rs.getLong("cnt"),
                "maxWaitMin" to rs.getInt("max_wait_min")
            )
        } ?: mapOf("cnt" to 0L, "maxWaitMin" to 0)
    }

    /**
     * 시간대별 불량률 추이를 조회한다. (No.22 — 전체 + 주 불량유형 2계열)
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
            WHERE lh.plant_cd  = :plantCd
              AND lh.del_flg   = 'N'
              AND lh.ins_date >= :dayStart
              AND lh.ins_date <  :dayEnd
            """.trimIndent()
        )

        val params = dayParams(plantCd, date).addValue("intervalHour", intervalHour)

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
     * @param topN 상위 불량유형 개수
     */
    fun findDefectTrendByType(
        plantCd: String,
        date: LocalDate,
        intervalHour: Int,
        processId: String?,
        topN: Int
    ): List<Map<String, Any?>> {
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
                to_char(
                    date_trunc('hour', dh.ins_date)
                    - make_interval(hours => (extract(hour FROM dh.ins_date)::int % :intervalHour)),
                    'HH24:MI'
                )                          AS slot,
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

        val params = dayParams(plantCd, date)
            .addValue("intervalHour", intervalHour)
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
    fun findLineProduction(plantCd: String, date: LocalDate, processId: String?): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT
                lh.eqpt_cd,
                max(e.eqpt_nm)                                            AS eqpt_nm,
                max(e.model_nm)                                           AS model_nm,
                coalesce(sum(lh.normal), 0)                               AS ok_qty,
                coalesce(sum(lh.defect), 0)                               AS ng_qty,
                coalesce(sum(lh.normal), 0) + coalesce(sum(lh.defect), 0) AS total_qty
            FROM mes.tb_pop_label_hist lh
            LEFT JOIN mes.tb_md_eqpt e
                   ON e.plant_cd = lh.plant_cd AND e.eqpt_cd = lh.eqpt_cd
            WHERE lh.plant_cd  = :plantCd
              AND lh.del_flg   = 'N'
              AND lh.eqpt_cd IS NOT NULL
              AND lh.ins_date >= :dayStart
              AND lh.ins_date <  :dayEnd
            """.trimIndent()
        )

        val params = dayParams(plantCd, date)

        if (!processId.isNullOrBlank()) {
            sql.append(" AND lh.wc_cd = :processId")
            params.addValue("processId", processId.trim())
        }

        sql.append("\nGROUP BY lh.eqpt_cd\nORDER BY total_qty DESC")

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "eqptCd" to rs.getString("eqpt_cd"),
                "eqptNm" to rs.getString("eqpt_nm"),
                "model" to rs.getString("model_nm"),
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
    fun findQualityIndex(plantCd: String, date: LocalDate, metricCodes: List<String>): List<Map<String, Any?>> {
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

        val params = dayParams(plantCd, date).addValue("metricCodes", metricCodes.toTypedArray())

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
    fun findDefectComposition(plantCd: String, date: LocalDate, processId: String?): List<Map<String, Any?>> {
        val params = dayParams(plantCd, date)

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
     * 해당 일자의 라벨 원장 불량 총량을 조회한다. (No.25 — 유형 구성비의 분모)
     *
     * [findDefectComposition] 의 유형 합계는 유형이 붙지 않은 불량이 빠져 이 값보다 작다.
     * 표시된 유형만으로 분모를 잡으면 비중이 부풀려지므로 분모는 이 값을 쓴다.
     */
    fun findDefectLedgerTotal(plantCd: String, date: LocalDate, processId: String?): Long {
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

        val params = dayParams(plantCd, date)

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
    fun findProcessYield(plantCd: String, date: LocalDate): List<Map<String, Any?>> {
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

        return jdbcTemplate.query(sql, dayParams(plantCd, date)) { rs, _ ->
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
    fun findPlanVsActual(plantCd: String, date: LocalDate, intervalHour: Int): List<Map<String, Any?>> {
        val sql = """
            WITH slots AS (
                SELECT generate_series(
                    :dayStart::timestamp,
                    :dayEnd::timestamp - make_interval(hours => :intervalHour),
                    make_interval(hours => :intervalHour)
                ) AS slot_at
            ),
            actual AS (
                SELECT
                    date_trunc('hour', lh.ins_date)
                        - make_interval(hours => (extract(hour FROM lh.ins_date)::int % :intervalHour)) AS slot_at,
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
                    date_trunc('hour', sh.ins_date)
                        - make_interval(hours => (extract(hour FROM sh.ins_date)::int % :intervalHour)) AS slot_at,
                    coalesce(sum(sh.qty), 0)                                                           AS qty
                FROM mes.tb_pop_stock_hist sh
                WHERE sh.plant_cd  = :plantCd
                  AND sh.hist_type = 'PLAN'
                  AND sh.ins_date >= :dayStart
                  AND sh.ins_date <  :dayEnd
                GROUP BY 1
            )
            SELECT
                to_char(s.slot_at, 'HH24:MI')   AS slot,
                coalesce(plan.qty, 0)           AS plan_qty,
                coalesce(actual.qty, 0)         AS actual_qty
            FROM slots s
            LEFT JOIN plan   ON plan.slot_at   = s.slot_at
            LEFT JOIN actual ON actual.slot_at = s.slot_at
            ORDER BY s.slot_at
        """.trimIndent()

        val params = dayParams(plantCd, date).addValue("intervalHour", intervalHour)

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
    ): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT
                mv.eqpt_cd,
                coalesce(max(e.eqpt_nm), mv.eqpt_cd) AS eqpt_nm,
                to_char(
                    date_trunc('hour', mv.measured_at)
                    - make_interval(hours => (extract(hour FROM mv.measured_at)::int % :intervalHour)),
                    'HH24:MI'
                )                                    AS slot,
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

        val params = dayParams(plantCd, date).addValue("intervalHour", intervalHour)

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
    fun countLines(plantCd: String, date: LocalDate, processId: String?): Long {
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

        val params = dayParams(plantCd, date)

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
    ): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT
                e.eqpt_cd,
                e.eqpt_nm,
                e.model_nm,
                ew.wc_cd,
                coalesce(prod.ok_qty, 0)                                  AS ok_qty,
                coalesce(prod.ng_qty, 0)                                  AS ng_qty,
                coalesce(prod.ok_qty, 0) + coalesce(prod.ng_qty, 0)       AS total_qty,
                uptime.uptime_rate,
                uptime.last_measured_at
            FROM mes.tb_md_eqpt e
            INNER JOIN mes.tb_md_eqpt_by_workcenter ew
                    ON ew.plant_cd = e.plant_cd AND ew.eqpt_cd = e.eqpt_cd
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

        val params = dayParams(plantCd, date)

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
                    else -> "OK"
                },
                "mode" to "AUTO",
                "recentRunCnt" to rs.getLong("total_cnt"),
                "avgElapsedMs" to Rs.intOrNull(rs, "avg_elapsed_ms")
            )
        } ?: mapOf("state" to "OK", "mode" to "AUTO")
    }

    /**
     * 일자 범위 공통 파라미터 (당일 00:00 ~ 익일 00:00)
     */
    private fun dayParams(plantCd: String, date: LocalDate): MapSqlParameterSource =
        MapSqlParameterSource()
            .addValue("plantCd", plantCd)
            .addValue("dayStart", date.atStartOfDay())
            .addValue("dayEnd", date.plusDays(1).atStartOfDay())
}
