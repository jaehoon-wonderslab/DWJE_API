package com.dwje.api.repository

import com.dwje.api.common.util.Rs
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

/**
 * 지표 기준·측정값 Repository (DB-03 성과지표 · 대시보드 목표선 · AOI 예측 기준값)
 *
 * 지표 측정 데이터 관리 화면(SY-13)은 2026-09-15 에 제거됐다 — 기준 등록·수정·이력·사용처 조회는 없다.
 * 남은 것은 다른 화면이 읽는 기준값·측정값 조회와 측정값 적재다.
 *
 * 참조 테이블
 * - 기준정보 : ax.tb_met_metric_std, ax.tb_met_metric_source
 * - 측정값   : ax.tb_met_metric_value
 */
@Repository
class MetricStandardRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) {

    /**
     * 지표 코드로 기준값(std_val)을 조회한다. (대시보드 목표선 표기용)
     *
     * @param metricCd 지표 코드
     * @return 기준값 (미등록 시 null)
     */
    fun findStandardValue(metricCd: String): Double? {
        val sql = """
            SELECT std_val
            FROM ax.tb_met_metric_std
            WHERE metric_cd = :metricCd AND use_flg = 'Y'
            LIMIT 1
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource("metricCd", metricCd)) { rs, _ ->
            Rs.rate(rs, "std_val")
        }.firstOrNull()
    }

    /** 지표 기준 목록/건수 공통 동적 조건 */
    private fun appendStandardFilters(
        sql: StringBuilder,
        params: MapSqlParameterSource,
        category: String?,
        applied: Boolean?,
        level: String?
    ) {
        if (!category.isNullOrBlank()) {
            sql.append(" AND ms.cat_cd = :category")
            params.addValue("category", category.trim())
        }
        if (applied != null) {
            sql.append(
                if (applied) " AND (ms.apply_alert OR ms.apply_dashboard OR ms.apply_report)"
                else " AND NOT (ms.apply_alert OR ms.apply_dashboard OR ms.apply_report)"
            )
        }
        if (!level.isNullOrBlank()) {
            sql.append(" AND cur.judge_cd = :level")
            params.addValue("level", level.trim().uppercase())
        }
    }

    /**
     * 월별 지표 실측값을 조회한다. (No.52 — 히트맵 / No.45 KPI 추이)
     *
     * @param metricCodes 대상 지표 코드 목록
     * @param from        조회 시작 연월
     * @param to          조회 종료 연월
     */
    fun findMonthlyValues(metricCodes: List<String>, from: YearMonth, to: YearMonth): List<Map<String, Any?>> {
        val sql = """
            SELECT
                ms.metric_cd,
                ms.metric_nm,
                to_char(mv.measured_at, 'YYYY-MM')  AS ym,
                round(avg(mv.metric_value), 2)      AS avg_val
            FROM ax.tb_met_metric_value mv
            INNER JOIN ax.tb_met_metric_std ms ON ms.metric_id = mv.metric_id
            WHERE ms.metric_cd    = ANY(:metricCodes)
              AND mv.measured_at >= :from
              AND mv.measured_at <  :toExclusive
            GROUP BY ms.metric_cd, ms.metric_nm, 3
            ORDER BY 3, ms.metric_cd
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("metricCodes", metricCodes.toTypedArray())
            .addValue("from", from.atDay(1).atStartOfDay())
            .addValue("toExclusive", to.plusMonths(1).atDay(1).atStartOfDay())

        return jdbcTemplate.query(sql, params) { rs, _ ->
            mapOf(
                "metricCd" to rs.getString("metric_cd"),
                "metricNm" to rs.getString("metric_nm"),
                "ym" to rs.getString("ym"),
                "value" to Rs.rate(rs, "avg_val")
            )
        }
    }

    /**
     * 특정 연월의 지표 평균값을 조회한다. (No.44 KPI 요약 / No.51 AI 성능 목표 충족)
     */
    fun findMonthlyAverage(metricCodes: List<String>, yearMonth: YearMonth): List<Map<String, Any?>> {
        val sql = """
            SELECT
                ms.metric_cd,
                ms.metric_nm,
                ms.unit_cd,
                ms.std_val,
                ms.warn_val,
                ms.crit_val,
                round(avg(mv.metric_value), 2) AS avg_val,
                count(mv.value_id)             AS measure_cnt
            FROM ax.tb_met_metric_std ms
            LEFT JOIN ax.tb_met_metric_value mv
                   ON mv.metric_id    = ms.metric_id
                  AND mv.measured_at >= :from
                  AND mv.measured_at <  :toExclusive
            WHERE ms.metric_cd = ANY(:metricCodes)
              AND ms.use_flg   = 'Y'
            GROUP BY ms.metric_id, ms.metric_cd, ms.metric_nm, ms.unit_cd, ms.std_val, ms.warn_val, ms.crit_val
            ORDER BY array_position(:metricCodes, ms.metric_cd)
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("metricCodes", metricCodes.toTypedArray())
            .addValue("from", yearMonth.atDay(1).atStartOfDay())
            .addValue("toExclusive", yearMonth.plusMonths(1).atDay(1).atStartOfDay())

        return jdbcTemplate.query(sql, params) { rs, _ ->
            mapOf(
                "metricCd" to rs.getString("metric_cd"),
                "name" to rs.getString("metric_nm"),
                "unit" to rs.getString("unit_cd"),
                "value" to Rs.rate(rs, "avg_val"),
                "target" to Rs.rate(rs, "std_val"),
                "warn" to Rs.rate(rs, "warn_val"),
                "critical" to Rs.rate(rs, "crit_val"),
                "measureCnt" to rs.getLong("measure_cnt")
            )
        }
    }

    /**
     * 대시보드 적용 지표 목록을 조회한다. (KPI 지표 코드 확보용)
     *
     * @param catCd 지표 구분
     */
    fun findDashboardMetricCodes(catCd: String?): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT metric_id, metric_cd, metric_nm, unit_cd, std_val, cat_cd
            FROM ax.tb_met_metric_std
            WHERE use_flg = 'Y' AND apply_dashboard = true
            """.trimIndent()
        )

        val params = MapSqlParameterSource()
        if (!catCd.isNullOrBlank()) {
            sql.append(" AND cat_cd = :catCd")
            params.addValue("catCd", catCd.trim())
        }
        sql.append("\nORDER BY cat_cd, metric_nm")

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "metricId" to rs.getInt("metric_id"),
                "metricCd" to rs.getString("metric_cd"),
                "name" to rs.getString("metric_nm"),
                "unit" to rs.getString("unit_cd"),
                "target" to Rs.rate(rs, "std_val"),
                "category" to rs.getString("cat_cd")
            )
        }
    }

    /**
     * KPI 측정 기준(산식·원천·주기)을 조회한다. (No.53 — 모달)
     */
    fun findKpiBasis(metricCodes: List<String>): List<Map<String, Any?>> {
        val sql = """
            SELECT
                ms.metric_id,
                ms.metric_cd,
                ms.metric_nm,
                ms.calc_base,
                ms.window_cd,
                ms.unit_cd,
                ms.std_val,
                string_agg(
                    concat_ws('.', msrc.src_schema, msrc.src_table, msrc.src_column),
                    ', ' ORDER BY msrc.src_seq
                ) AS sources,
                string_agg(msrc.remark, ' / ' ORDER BY msrc.src_seq) AS remarks
            FROM ax.tb_met_metric_std ms
            LEFT JOIN ax.tb_met_metric_source msrc ON msrc.metric_id = ms.metric_id
            WHERE ms.metric_cd = ANY(:metricCodes)
              AND ms.use_flg   = 'Y'
            GROUP BY ms.metric_id, ms.metric_cd, ms.metric_nm, ms.calc_base, ms.window_cd, ms.unit_cd, ms.std_val
            ORDER BY array_position(:metricCodes, ms.metric_cd)
        """.trimIndent()

        val params = MapSqlParameterSource("metricCodes", metricCodes.toTypedArray())

        return jdbcTemplate.query(sql, params) { rs, _ ->
            mapOf(
                "metricCd" to rs.getString("metric_cd"),
                "name" to rs.getString("metric_nm"),
                "formula" to rs.getString("calc_base"),
                "source" to rs.getString("sources"),
                "cycle" to rs.getString("window_cd"),
                "unit" to rs.getString("unit_cd"),
                "target" to Rs.rate(rs, "std_val"),
                "exclusion" to rs.getString("remarks")
            )
        }
    }

    /**
     * 부서별 지표 실측값을 조회한다. (No.49 — 부서별 작업공수 절감)
     */
    fun findValuesByDept(metricCd: String, from: LocalDate, to: LocalDate): List<Map<String, Any?>> {
        val sql = """
            SELECT
                d.dept_nm,
                round(avg(mv.metric_value), 2) AS avg_val
            FROM ax.tb_met_metric_value mv
            INNER JOIN ax.tb_met_metric_std ms ON ms.metric_id     = mv.metric_id
            INNER JOIN ax.tb_sys_dept       d  ON d.dept_id        = ms.owner_dept_id
            WHERE ms.metric_cd    = :metricCd
              AND mv.measured_at >= :from
              AND mv.measured_at <  :toExclusive
            GROUP BY d.dept_id, d.dept_nm, d.sort_seq
            ORDER BY d.sort_seq, d.dept_nm
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("metricCd", metricCd)
            .addValue("from", from.atStartOfDay())
            .addValue("toExclusive", to.plusDays(1).atStartOfDay())

        return jdbcTemplate.query(sql, params) { rs, _ ->
            mapOf(
                "dept" to rs.getString("dept_nm"),
                "index" to Rs.rate(rs, "avg_val")
            )
        }
    }

    /**
     * 지표 측정값을 기록한다. (비가동 등록 등 업무 처리 시 파생 지표 적재)
     */
    fun insertMetricValue(
        metricId: Int,
        metricValue: BigDecimal,
        judgeCd: String,
        plantCd: String?,
        wcCd: String?,
        eqptCd: String?,
        srcCd: String,
        remark: String?
    ): Long {
        val sql = """
            INSERT INTO ax.tb_met_metric_value (
                metric_id, measured_at, metric_value, judge_cd,
                plant_cd, wc_cd, eqpt_cd, src_cd, remark
            ) VALUES (
                :metricId, now(), :metricValue, :judgeCd,
                :plantCd, :wcCd, :eqptCd, :srcCd, :remark
            )
            RETURNING value_id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("metricId", metricId)
            .addValue("metricValue", metricValue)
            .addValue("judgeCd", judgeCd)
            .addValue("plantCd", plantCd)
            .addValue("wcCd", wcCd)
            .addValue("eqptCd", eqptCd)
            .addValue("srcCd", srcCd)
            .addValue("remark", remark?.take(300))

        return jdbcTemplate.queryForObject(sql, params, Long::class.java) ?: 0L
    }

    /** 지표 코드로 지표 ID 를 조회한다. */
    fun findMetricIdByCode(metricCd: String): Int? {
        val sql = "SELECT metric_id FROM ax.tb_met_metric_std WHERE metric_cd = :metricCd AND use_flg = 'Y' LIMIT 1"
        return jdbcTemplate.query(sql, MapSqlParameterSource("metricCd", metricCd)) { rs, _ -> rs.getInt("metric_id") }
            .firstOrNull()
    }
}
