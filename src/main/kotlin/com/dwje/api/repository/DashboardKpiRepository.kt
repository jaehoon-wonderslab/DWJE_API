package com.dwje.api.repository

import com.dwje.api.common.util.DefectSql
import com.dwje.api.common.util.Rs
import com.dwje.api.common.util.safeRate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.YearMonth

/**
 * 성과지표 대시보드 Repository (DB-03)
 *
 * KPI 3종(공정 불량률 0.4 / 작업공수 지수 0.3 / 설비 가동률 0.3)의 실측값과
 * AI 서빙 성능 검증 결과를 조회한다.
 *
 * 데이터 소스 : ax.tb_met_metric_std · ax.tb_met_metric_value · ax.tb_met_metric_source
 *              · ax.tb_ai_serving_profile · ax.tb_ai_model_asset · mes.tb_pop_defect_hist
 */
@Repository
class DashboardKpiRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) {

    /**
     * 월 누계 불량 유형 분포를 조회한다. (No.46)
     *
     * @param plantCd   사업장 코드
     * @param yearMonth 대상 연월
     */
    fun findDefectDistribution(plantCd: String, yearMonth: YearMonth): List<Map<String, Any?>> {
        // 기간은 라벨 이력에만 걸고, 라벨 불량 수량을 유형 구성비로 안분한다.
        // (MES_QUERY_GUIDE 2-3 / 2-4)
        val sql = """
            WITH
            ${DefectSql.labelLedgerCte("cur_label", "from", "toExclusive")},
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

        val params = monthParams(plantCd, yearMonth)

        return jdbcTemplate.query(sql, params) { rs, _ ->
            mapOf(
                "code" to rs.getString("defect_cd"),
                "label" to rs.getString("defect_nm"),
                "value" to Rs.qty(rs, "ng_qty")
            )
        }
    }

    /**
     * 해당 월의 라벨 원장 불량 총량을 조회한다. (No.46 — 유형 구성비의 분모)
     *
     * [findDefectDistribution] 의 유형 합계는 유형이 붙지 않은 불량이 빠져 이 값보다 작다.
     * 표시된 유형만으로 분모를 잡으면 비중이 부풀려지므로 분모는 이 값을 쓴다.
     */
    fun findDefectLedgerTotal(plantCd: String, yearMonth: YearMonth): Long {
        val sql = """
            SELECT coalesce(sum(coalesce(lh.defect, 0)), 0) AS ng_qty
            FROM mes.tb_pop_label_hist lh
            WHERE lh.plant_cd  = :plantCd
              AND lh.del_flg   = 'N'
              AND lh.ins_date >= :from
              AND lh.ins_date <  :toExclusive
        """.trimIndent()

        return jdbcTemplate.queryForObject(sql, monthParams(plantCd, yearMonth)) { rs, _ ->
            Rs.qty(rs, "ng_qty") ?: 0L
        } ?: 0L
    }

    /**
     * 월별 불량 유형 추이를 조회한다. (No.47 — 상위 N개 유형)
     *
     * @param topN 상위 불량유형 개수
     */
    fun findDefectTypeTrend(
        plantCd: String,
        from: YearMonth,
        to: YearMonth,
        topN: Int
    ): List<Map<String, Any?>> {
        val sql = """
            WITH top_defects AS (
                SELECT dh.defect_cd
                FROM mes.tb_pop_defect_hist dh
                WHERE dh.plant_cd  = :plantCd
                  AND dh.ins_date >= :from
                  AND dh.ins_date <  :toExclusive
                  ${DefectSql.excludeNonProduction()}
                GROUP BY dh.defect_cd
                ORDER BY sum(dh.qty) DESC
                LIMIT :topN
            )
            SELECT
                to_char(dh.ins_date, 'YYYY-MM')           AS ym,
                dh.defect_cd,
                coalesce(max(md.defect_nm), dh.defect_cd) AS defect_nm,
                coalesce(sum(dh.qty), 0)                  AS ng_qty
            FROM mes.tb_pop_defect_hist dh
            INNER JOIN top_defects td ON td.defect_cd = dh.defect_cd
            LEFT  JOIN mes.tb_md_defect md
                    ON md.plant_cd = dh.plant_cd AND md.defect_cd = dh.defect_cd
            WHERE dh.plant_cd  = :plantCd
              AND dh.ins_date >= :from
              AND dh.ins_date <  :toExclusive
              ${DefectSql.excludeNonProduction()}
            GROUP BY 1, dh.defect_cd
            ORDER BY 1, dh.defect_cd
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("plantCd", plantCd)
            .addValue("from", from.atDay(1).atStartOfDay())
            .addValue("toExclusive", to.plusMonths(1).atDay(1).atStartOfDay())
            .addValue("topN", topN)

        return jdbcTemplate.query(sql, params) { rs, _ ->
            mapOf(
                "ym" to rs.getString("ym"),
                "defectNm" to rs.getString("defect_nm"),
                "value" to Rs.qty(rs, "ng_qty")
            )
        }
    }

    /**
     * 월 누계 생산·불량 수량을 조회한다. (KPI 불량률 산출 보조)
     */
    fun findMonthlyProduction(plantCd: String, yearMonth: YearMonth): Map<String, Any?> {
        val sql = """
            SELECT
                coalesce(sum(lh.normal), 0)                                AS ok_qty,
                coalesce(sum(lh.defect), 0)                                AS ng_qty,
                coalesce(sum(lh.normal), 0) + coalesce(sum(lh.defect), 0)  AS total_qty
            FROM mes.tb_pop_label_hist lh
            WHERE lh.plant_cd  = :plantCd
              AND lh.del_flg   = 'N'
              AND lh.ins_date >= :from
              AND lh.ins_date <  :toExclusive
        """.trimIndent()

        return jdbcTemplate.queryForObject(sql, monthParams(plantCd, yearMonth)) { rs, _ ->
            mapOf(
                "okQty" to Rs.qty(rs, "ok_qty"),
                "ngQty" to Rs.qty(rs, "ng_qty"),
                "totalQty" to Rs.qty(rs, "total_qty"),
                "defectRate" to safeRate(rs.getBigDecimal("ng_qty"), rs.getBigDecimal("total_qty")),
                "yield" to safeRate(rs.getBigDecimal("ok_qty"), rs.getBigDecimal("total_qty"))
            )
        } ?: emptyMap()
    }

    /**
     * 현재 서비스 중인 AI 서빙 프로파일의 성능 검증 결과를 조회한다. (No.48 / No.51)
     *
     * eval_json 에는 의도정확도·인용정확도·거부정확도·환각률 등 항목별 점수가 담긴다.
     */
    fun findServingEvaluation(): Map<String, Any?>? {
        val sql = """
            SELECT
                p.profile_id,
                p.profile_cd,
                p.version_no,
                p.profile_nm,
                p.eval_score,
                p.eval_baseline,
                p.eval_json,
                p.must_pass_fail,
                p.state_cd,
                p.activated_at
            FROM ax.tb_ai_serving_profile p
            WHERE p.service_cd = 'CHAT'
              AND p.state_cd   = 'ACTIVE'
            ORDER BY p.activated_at DESC NULLS LAST
            LIMIT 1
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource()) { rs, _ ->
            mapOf(
                "profileId" to rs.getInt("profile_id"),
                "ver" to "${rs.getString("profile_cd")}-v${rs.getInt("version_no")}",
                "name" to rs.getString("profile_nm"),
                "score" to Rs.rate(rs, "eval_score", 3),
                "baseline" to Rs.rate(rs, "eval_baseline", 3),
                "evalJson" to rs.getString("eval_json"),
                "mustPassFail" to rs.getInt("must_pass_fail"),
                "state" to rs.getString("state_cd"),
                "activatedAt" to Rs.dateTime(rs, "activated_at")
            )
        }.firstOrNull()
    }

    /**
     * 서빙 프로파일 버전별 성능 추이를 조회한다. (No.208)
     */
    fun findServingPerformanceTrend(limit: Int): List<Map<String, Any?>> {
        val sql = """
            SELECT
                p.profile_cd || '-v' || p.version_no AS ver,
                p.eval_score,
                p.eval_baseline,
                p.eval_json,
                p.activated_at,
                p.state_cd
            FROM ax.tb_ai_serving_profile p
            WHERE p.service_cd = 'CHAT'
              AND p.eval_score IS NOT NULL
            ORDER BY p.activated_at NULLS LAST, p.version_no
            LIMIT :limit
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource("limit", limit)) { rs, _ ->
            mapOf(
                "ver" to rs.getString("ver"),
                "score" to Rs.rate(rs, "eval_score", 3),
                "baseline" to Rs.rate(rs, "eval_baseline", 3),
                "evalJson" to rs.getString("eval_json"),
                "activatedAt" to Rs.dateTime(rs, "activated_at"),
                "state" to rs.getString("state_cd")
            )
        }
    }

    /**
     * KPI 증빙 원천 데이터를 조회한다. (No.54 — 산출 근거 원천 데이터)
     *
     * @param metricCodes 대상 지표 코드
     * @param yearMonth   대상 연월
     */
    fun findEvidenceRows(metricCodes: List<String>, yearMonth: YearMonth): List<Map<String, Any?>> {
        val sql = """
            SELECT
                ms.metric_cd,
                ms.metric_nm,
                mv.measured_at,
                mv.metric_value,
                mv.judge_cd,
                mv.plant_cd,
                mv.wc_cd,
                mv.eqpt_cd,
                mv.item_cd,
                mv.src_cd,
                mv.remark
            FROM ax.tb_met_metric_value mv
            INNER JOIN ax.tb_met_metric_std ms ON ms.metric_id = mv.metric_id
            WHERE ms.metric_cd    = ANY(:metricCodes)
              AND mv.measured_at >= :from
              AND mv.measured_at <  :toExclusive
            ORDER BY ms.metric_cd, mv.measured_at
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("metricCodes", metricCodes.toTypedArray())
            .addValue("from", yearMonth.atDay(1).atStartOfDay())
            .addValue("toExclusive", yearMonth.plusMonths(1).atDay(1).atStartOfDay())

        return jdbcTemplate.query(sql, params) { rs, _ ->
            mapOf(
                "metricCd" to rs.getString("metric_cd"),
                "metricNm" to rs.getString("metric_nm"),
                "measuredAt" to Rs.dateTime(rs, "measured_at"),
                "value" to Rs.rate(rs, "metric_value", 4),
                "judge" to rs.getString("judge_cd"),
                "plantCd" to rs.getString("plant_cd"),
                "wcCd" to rs.getString("wc_cd"),
                "eqptCd" to rs.getString("eqpt_cd"),
                "itemCd" to rs.getString("item_cd"),
                "src" to rs.getString("src_cd"),
                "remark" to rs.getString("remark")
            )
        }
    }

    /** 연월 범위 공통 파라미터 */
    private fun monthParams(plantCd: String, yearMonth: YearMonth): MapSqlParameterSource =
        MapSqlParameterSource()
            .addValue("plantCd", plantCd)
            .addValue("from", yearMonth.atDay(1).atStartOfDay())
            .addValue("toExclusive", yearMonth.plusMonths(1).atDay(1).atStartOfDay())
}
