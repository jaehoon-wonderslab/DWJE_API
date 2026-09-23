package com.dwje.api.repository

import com.dwje.api.common.util.BusinessDay
import com.dwje.api.common.util.Rs
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

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
/**
 * 지표 값 방향 — 기준값과 임계값의 관계에서 산출한다.
 *
 * `ax.tb_met_metric_std` 에는 방향 컬럼이 없다. DDL 의 `crit_val` 주석이 규칙을 정한다 —
 * "crit_val >= warn_val 이면 값이 클수록 나쁨, 반대면 값이 작을수록 나쁨".
 * 화면·요청 DTO 는 이를 **좋은 쪽** 기준으로 부른다 : `high` = 클수록 좋음, `low` = 작을수록 좋음.
 *
 * 저장 컬럼이 아니므로 POST/PUT 의 `direction` 은 그대로 저장되지 않고,
 * warn/crit 순서와 어긋나면 400 으로 알린다. ([MetricStandardService])
 */
object MetricDirection {
    /** 값이 클수록 좋음 — 수율·가동률처럼 임계값이 기준값보다 낮다 */
    const val HIGH = "high"

    /** 값이 작을수록 좋음 — 불량률·정지 시간처럼 임계값이 기준값보다 높다 */
    const val LOW = "low"

    val VALUES = listOf(HIGH, LOW)

    /**
     * 주의·위험 임계값 관계로 방향을 산출한다.
     *
     * 둘이 같으면(신규 등록 기본값 0/0 등) 판정 근거가 없어 `null` 을 돌려준다.
     * 화면은 null 을 "방향 미정" 으로 그리고, 쓰기 검증([MetricStandardService])도 이때는 건너뛴다.
     */
    fun of(warn: BigDecimal?, crit: BigDecimal?): String? {
        if (warn == null || crit == null) return null
        val cmp = crit.compareTo(warn)
        return when {
            cmp > 0 -> LOW
            cmp < 0 -> HIGH
            else -> null
        }
    }
}

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

        val window = BusinessDay.ofRange(from, to)
        val params = MapSqlParameterSource()
            .addValue("metricCd", metricCd)
            .addValue("from", window.from)
            .addValue("toExclusive", window.toExclusive)

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

    // =================================================================================
    //  지표 측정 데이터 관리 화면 전용 (SY-13)
    //
    //  2026-09-15 에 화면과 함께 지웠다가 2026-09-22 요청으로 되살렸다.
    //  변경 이력(insertStandardHistory · findStandardHistory · countStandardHistory)만 빼 뒀다 —
    //  근거 표 ax.tb_met_metric_std_hist 를 V31 이 지웠기 때문이다.
    //  이력이 다시 필요하면 표부터 되살려야 한다(DB 담당).
    // =================================================================================

    fun findSummary(): Map<String, Any?> {
        val sql = """
            SELECT
                count(*)                                                            AS total_cnt,
                count(*) FILTER (WHERE apply_dashboard OR apply_alert OR apply_report) AS applied_cnt,
                count(*) FILTER (WHERE apply_alert)                                 AS alert_cnt,
                max(upd_date)                                                       AS last_updated_at,
                (
                    SELECT u.user_nm
                      FROM ax.tb_met_metric_std s2
                      LEFT JOIN ax.tb_sys_user u ON u.user_id = s2.upd_user
                     WHERE s2.use_flg = 'Y'
                     ORDER BY s2.upd_date DESC
                     LIMIT 1
                )                                                                   AS last_updated_by
            FROM ax.tb_met_metric_std
            WHERE use_flg = 'Y'
        """.trimIndent()

        return jdbcTemplate.queryForObject(sql, MapSqlParameterSource()) { rs, _ ->
            mapOf(
                "totalCnt" to rs.getLong("total_cnt"),
                "appliedCnt" to rs.getLong("applied_cnt"),
                "alertCnt" to rs.getLong("alert_cnt"),
                "lastUpdated" to mapOf(
                    "at" to Rs.dateTime(rs, "last_updated_at"),
                    "by" to rs.getString("last_updated_by")
                )
            )
        } ?: emptyMap()
    }

    fun findRecentJudgeCounts(hours: Int): Map<String, Long> {
        val sql = """
            SELECT mv.judge_cd, count(*) AS cnt
            FROM ax.tb_met_metric_value mv
            WHERE mv.measured_at >= now() - make_interval(hours => :hours)
            GROUP BY mv.judge_cd
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource("hours", hours)) { rs, _ ->
            rs.getString("judge_cd") to rs.getLong("cnt")
        }.toMap()
    }

    fun findStandards(
        category: String?,
        applied: Boolean?,
        level: String?,
        limit: Int,
        offset: Int
    ): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT
                ms.metric_id,
                ms.metric_cd,
                ms.metric_nm,
                ms.cat_cd,
                ms.unit_cd,
                ms.std_val,
                ms.warn_val,
                ms.crit_val,
                ms.window_cd,
                ms.calc_base,
                ms.apply_alert,
                ms.apply_dashboard,
                ms.apply_report,
                ms.blind_field_key,
                ms.upd_date,
                u.user_nm       AS upd_user_nm,
                d.dept_nm       AS owner_dept_nm,
                cur.metric_value AS current_value,
                cur.judge_cd     AS current_judge
            FROM ax.tb_met_metric_std ms
            LEFT JOIN ax.tb_sys_user u ON u.user_id = ms.upd_user
            LEFT JOIN ax.tb_sys_dept d ON d.dept_id = ms.owner_dept_id
            LEFT JOIN LATERAL (
                SELECT mv.metric_value, mv.judge_cd
                FROM ax.tb_met_metric_value mv
                WHERE mv.metric_id = ms.metric_id
                ORDER BY mv.measured_at DESC
                LIMIT 1
            ) cur ON true
            WHERE ms.use_flg = 'Y'
            """.trimIndent()
        )

        val params = MapSqlParameterSource()
        appendStandardFilters(sql, params, category, applied, level)

        sql.append("\nORDER BY ms.cat_cd, ms.metric_nm\nLIMIT :limit OFFSET :offset")
        params.addValue("limit", limit).addValue("offset", offset)

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "stdId" to rs.getInt("metric_id"),
                "metricCd" to rs.getString("metric_cd"),
                "category" to rs.getString("cat_cd"),
                "name" to rs.getString("metric_nm"),
                "unit" to rs.getString("unit_cd"),
                "currentValue" to Rs.rate(rs, "current_value", 4),
                "normal" to Rs.rate(rs, "std_val", 4),
                "warn" to Rs.rate(rs, "warn_val", 4),
                "critical" to Rs.rate(rs, "crit_val", 4),
                "direction" to MetricDirection.of(rs.getBigDecimal("warn_val"), rs.getBigDecimal("crit_val")),
                "window" to rs.getString("window_cd"),
                "basis" to rs.getString("calc_base"),
                "level" to (rs.getString("current_judge") ?: "NORMAL"),
                "applied" to (rs.getBoolean("apply_dashboard") || rs.getBoolean("apply_alert") || rs.getBoolean("apply_report")),
                "applyAlert" to rs.getBoolean("apply_alert"),
                "applyDashboard" to rs.getBoolean("apply_dashboard"),
                "applyReport" to rs.getBoolean("apply_report"),
                "blindFieldKey" to rs.getString("blind_field_key"),
                "ownerDept" to rs.getString("owner_dept_nm"),
                "updatedAt" to Rs.dateTime(rs, "upd_date"),
                "updatedBy" to rs.getString("upd_user_nm")
            )
        }
    }

    fun countStandards(category: String?, applied: Boolean?, level: String?): Long {
        val sql = StringBuilder(
            """
            SELECT count(*)
            FROM ax.tb_met_metric_std ms
            LEFT JOIN LATERAL (
                SELECT mv.judge_cd
                FROM ax.tb_met_metric_value mv
                WHERE mv.metric_id = ms.metric_id
                ORDER BY mv.measured_at DESC
                LIMIT 1
            ) cur ON true
            WHERE ms.use_flg = 'Y'
            """.trimIndent()
        )

        val params = MapSqlParameterSource()
        appendStandardFilters(sql, params, category, applied, level)

        return jdbcTemplate.queryForObject(sql.toString(), params, Long::class.java) ?: 0L
    }

    fun insertStandard(
        metricCd: String,
        metricNm: String,
        catCd: String,
        unitCd: String,
        stdVal: BigDecimal,
        warnVal: BigDecimal,
        critVal: BigDecimal,
        windowCd: String,
        calcBase: String,
        applied: Boolean,
        actor: String
    ): Int {
        val sql = """
            INSERT INTO ax.tb_met_metric_std (
                metric_cd, metric_nm, cat_cd, unit_cd, std_val, warn_val, crit_val,
                window_cd, calc_base, apply_alert, apply_dashboard, apply_report,
                use_flg, ins_user, upd_user
            ) VALUES (
                :metricCd, :metricNm, :catCd, :unitCd, :stdVal, :warnVal, :critVal,
                :windowCd, :calcBase, :applied, :applied, false,
                'Y', :actor, :actor
            )
            RETURNING metric_id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("metricCd", metricCd)
            .addValue("metricNm", metricNm)
            .addValue("catCd", catCd)
            .addValue("unitCd", unitCd)
            .addValue("stdVal", stdVal)
            .addValue("warnVal", warnVal)
            .addValue("critVal", critVal)
            .addValue("windowCd", windowCd)
            .addValue("calcBase", calcBase)
            .addValue("applied", applied)
            .addValue("actor", actor)

        return jdbcTemplate.queryForObject(sql, params, Int::class.java) ?: 0
    }

    fun findLatestValue(metricId: Int): BigDecimal? {
        val sql = """
            SELECT mv.metric_value
            FROM ax.tb_met_metric_value mv
            WHERE mv.metric_id = :metricId
            ORDER BY mv.measured_at DESC
            LIMIT 1
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource("metricId", metricId)) { rs, _ ->
            rs.getBigDecimal("metric_value")
        }.firstOrNull()
    }

    fun findStandard(metricId: Int): Map<String, Any?>? {
        val sql = """
            SELECT metric_id, metric_cd, metric_nm, cat_cd, unit_cd,
                   std_val, warn_val, crit_val, window_cd, calc_base,
                   apply_alert, apply_dashboard, apply_report
            FROM ax.tb_met_metric_std
            WHERE metric_id = :metricId
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource("metricId", metricId)) { rs, _ ->
            mapOf(
                "stdId" to rs.getInt("metric_id"),
                "metricCd" to rs.getString("metric_cd"),
                "name" to rs.getString("metric_nm"),
                "category" to rs.getString("cat_cd"),
                "unit" to rs.getString("unit_cd"),
                "normal" to Rs.rate(rs, "std_val", 4),
                "warn" to Rs.rate(rs, "warn_val", 4),
                "critical" to Rs.rate(rs, "crit_val", 4),
                "direction" to MetricDirection.of(rs.getBigDecimal("warn_val"), rs.getBigDecimal("crit_val")),
                "window" to rs.getString("window_cd"),
                "basis" to rs.getString("calc_base"),
                "applyAlert" to rs.getBoolean("apply_alert"),
                "applyDashboard" to rs.getBoolean("apply_dashboard"),
                "applyReport" to rs.getBoolean("apply_report")
            )
        }.firstOrNull()
    }

    fun existsCode(groupCd: String, code: String): Boolean =
        jdbcTemplate.queryForObject(
            """
            SELECT exists(
                SELECT 1 FROM ax.tb_sys_code
                WHERE group_cd = :groupCd AND code = :code AND use_flg = 'Y'
            )
            """.trimIndent(),
            MapSqlParameterSource().addValue("groupCd", groupCd).addValue("code", code),
            Boolean::class.java
        ) ?: false

    fun findCodeList(groupCd: String): List<String> =
        jdbcTemplate.query(
            "SELECT code FROM ax.tb_sys_code WHERE group_cd = :groupCd AND use_flg = 'Y' ORDER BY sort_seq",
            MapSqlParameterSource("groupCd", groupCd)
        ) { rs, _ -> rs.getString("code") }

    fun updateStandardValues(
        metricId: Int,
        stdVal: BigDecimal?,
        warnVal: BigDecimal?,
        critVal: BigDecimal?,
        windowCd: String?,
        calcBase: String?,
        actor: String
    ): Int {
        val sql = """
            UPDATE ax.tb_met_metric_std
               SET std_val   = coalesce(:stdVal, std_val),
                   warn_val  = coalesce(:warnVal, warn_val),
                   crit_val  = coalesce(:critVal, crit_val),
                   window_cd = coalesce(:windowCd, window_cd),
                   calc_base = coalesce(:calcBase, calc_base),
                   upd_date  = now(),
                   upd_user  = :actor
             WHERE metric_id = :metricId
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("metricId", metricId)
            .addValue("stdVal", stdVal)
            .addValue("warnVal", warnVal)
            .addValue("critVal", critVal)
            .addValue("windowCd", windowCd)
            .addValue("calcBase", calcBase)
            .addValue("actor", actor)

        return jdbcTemplate.update(sql, params)
    }

    fun updateStandardApplied(metricId: Int, applied: Boolean, actor: String): Int {
        val sql = """
            UPDATE ax.tb_met_metric_std
               SET apply_dashboard = :applied,
                   apply_alert     = :applied,
                   upd_date        = now(),
                   upd_user        = :actor
             WHERE metric_id = :metricId
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("metricId", metricId)
            .addValue("applied", applied)
            .addValue("actor", actor)

        return jdbcTemplate.update(sql, params)
    }

    fun findStandardUsage(metricId: Int): Map<String, Any?> {
        val condSql = """
            SELECT cond_id, cond_nm, severity_cd, use_flg
            FROM ax.tb_alm_cond
            WHERE metric_id = :metricId
            ORDER BY cond_nm
        """.trimIndent()

        val sourceSql = """
            SELECT src_schema, src_table, src_column, agg_expr, remark
            FROM ax.tb_met_metric_source
            WHERE metric_id = :metricId
            ORDER BY src_seq
        """.trimIndent()

        val flagSql = """
            SELECT apply_dashboard, apply_report, apply_alert
            FROM ax.tb_met_metric_std
            WHERE metric_id = :metricId
        """.trimIndent()

        val params = MapSqlParameterSource("metricId", metricId)

        val conditions = jdbcTemplate.query(condSql, params) { rs, _ ->
            mapOf(
                "condId" to rs.getInt("cond_id"),
                "name" to rs.getString("cond_nm"),
                "severity" to rs.getString("severity_cd"),
                "on" to Rs.yn(rs, "use_flg")
            )
        }

        val sources = jdbcTemplate.query(sourceSql, params) { rs, _ ->
            mapOf(
                "schema" to rs.getString("src_schema"),
                "table" to rs.getString("src_table"),
                "column" to rs.getString("src_column"),
                "aggExpr" to rs.getString("agg_expr"),
                "remark" to rs.getString("remark")
            )
        }

        val flags = jdbcTemplate.query(flagSql, params) { rs, _ ->
            mapOf(
                "dashboard" to rs.getBoolean("apply_dashboard"),
                "report" to rs.getBoolean("apply_report"),
                "alert" to rs.getBoolean("apply_alert")
            )
        }.firstOrNull() ?: emptyMap()

        return mapOf(
            "alertConditions" to conditions,
            "sources" to sources,
            "dashboards" to if (flags["dashboard"] == true) listOf("AI 통합 대시보드", "성과지표 대시보드") else emptyList<String>(),
            "reports" to if (flags["report"] == true) listOf("일일 생산현황 보고", "품질 보고서") else emptyList<String>()
        )
    }

    fun existsMetricCode(metricCd: String, excludeId: Int?): Boolean {
        val sql = """
            SELECT count(*)
            FROM ax.tb_met_metric_std
            WHERE metric_cd = :metricCd
              AND (:excludeId::int IS NULL OR metric_id <> :excludeId)
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("metricCd", metricCd)
            .addValue("excludeId", excludeId)

        return (jdbcTemplate.queryForObject(sql, params, Long::class.java) ?: 0L) > 0
    }


    // =================================================================================
    //  수집 정의 · 산출 근거 (SY-13 · 2026-09-22 신규)
    //
    //  ax.tb_met_metric_value 가 0행인 이유가 여기 있다 — 값을 채우는 정의가 없다.
    //  표 주석이 그 사정을 그대로 적어 두었다: "지금 그 표가 0행이라 알림 조건이 비교할 값이 없다".
    //  수집기 자체(주기 실행)는 API 가 아니라 별도 프로세스의 몫이고, 여기서는 정의를 관리한다.
    // =================================================================================

    /** 수집 정의 한 건 — 없으면 null (지표당 0 또는 1건, PK 가 metric_id 다) */
    fun findCollect(metricId: Int): Map<String, Any?>? =
        jdbcTemplate.query(
            """
            SELECT c.metric_id, c.collect_mode_cd, mc.code_nm AS collect_mode_nm, c.collector_cd,
                   c.dim_cd, c.interval_sec, c.lookback_min, c.sql_text, c.use_flg,
                   c.last_run_at, c.last_value_at, c.last_error, c.upd_date, u.user_nm AS upd_user_nm
            FROM ax.tb_met_metric_collect c
            LEFT JOIN ax.tb_sys_code mc ON mc.group_cd = 'MET_COLLECT_MODE' AND mc.code = c.collect_mode_cd
            LEFT JOIN ax.tb_sys_user u  ON u.user_id = c.upd_user
            WHERE c.metric_id = :metricId
            """.trimIndent(),
            MapSqlParameterSource("metricId", metricId)
        ) { rs, _ ->
            mapOf(
                "metricId" to rs.getInt("metric_id"),
                "collectMode" to rs.getString("collect_mode_cd"),
                "collectModeNm" to rs.getString("collect_mode_nm"),
                "collectorCd" to rs.getString("collector_cd"),
                "dimCd" to rs.getString("dim_cd"),
                "intervalSec" to rs.getInt("interval_sec"),
                "lookbackMin" to rs.getInt("lookback_min"),
                "sqlText" to rs.getString("sql_text"),
                "applied" to Rs.yn(rs, "use_flg"),
                "lastRunAt" to Rs.dateTime(rs, "last_run_at"),
                "lastValueAt" to Rs.dateTime(rs, "last_value_at"),
                "lastError" to rs.getString("last_error"),
                "updatedAt" to Rs.dateTime(rs, "upd_date"),
                "updatedBy" to rs.getString("upd_user_nm")
            )
        }.firstOrNull()

    /** 수집 정의 등록·수정 — 지표당 한 건이라 UPSERT 로 둔다 */
    fun upsertCollect(
        metricId: Int,
        collectMode: String,
        collectorCd: String?,
        dimCd: String,
        intervalSec: Int,
        lookbackMin: Int,
        sqlText: String?,
        applied: Boolean,
        actor: String
    ): Int =
        jdbcTemplate.update(
            """
            INSERT INTO ax.tb_met_metric_collect (
                metric_id, collect_mode_cd, collector_cd, dim_cd, interval_sec, lookback_min,
                sql_text, use_flg, ins_user, upd_user
            ) VALUES (
                :metricId, :collectMode, :collectorCd, :dimCd, :intervalSec, :lookbackMin,
                :sqlText, :useFlg, :actor, :actor
            )
            ON CONFLICT (metric_id) DO UPDATE SET
                collect_mode_cd = EXCLUDED.collect_mode_cd,
                collector_cd    = EXCLUDED.collector_cd,
                dim_cd          = EXCLUDED.dim_cd,
                interval_sec    = EXCLUDED.interval_sec,
                lookback_min    = EXCLUDED.lookback_min,
                sql_text        = EXCLUDED.sql_text,
                use_flg         = EXCLUDED.use_flg,
                upd_date        = now(),
                upd_user        = EXCLUDED.upd_user
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("metricId", metricId)
                .addValue("collectMode", collectMode)
                .addValue("collectorCd", collectorCd?.take(50))
                .addValue("dimCd", dimCd)
                .addValue("intervalSec", intervalSec)
                .addValue("lookbackMin", lookbackMin)
                .addValue("sqlText", sqlText)
                .addValue("useFlg", if (applied) "Y" else "N")
                .addValue("actor", actor)
        )

    /** 산출 근거 매핑 목록 */
    fun findSources(metricId: Int): List<Map<String, Any?>> =
        jdbcTemplate.query(
            """
            SELECT metric_id, src_seq, src_schema, src_table, src_column, agg_expr, remark
            FROM ax.tb_met_metric_source
            WHERE metric_id = :metricId
            ORDER BY src_seq
            """.trimIndent(),
            MapSqlParameterSource("metricId", metricId)
        ) { rs, _ ->
            mapOf(
                "seq" to rs.getInt("src_seq"),
                "schema" to rs.getString("src_schema"),
                "table" to rs.getString("src_table"),
                "column" to rs.getString("src_column"),
                "aggExpr" to rs.getString("agg_expr"),
                "remark" to rs.getString("remark")
            )
        }

    /** 산출 근거를 통째로 바꾼다 — 순번은 넘어온 차례대로 1부터 다시 매긴다 */
    fun replaceSources(metricId: Int, sources: List<SourceWrite>): Int {
        jdbcTemplate.update(
            "DELETE FROM ax.tb_met_metric_source WHERE metric_id = :metricId",
            MapSqlParameterSource("metricId", metricId)
        )
        if (sources.isEmpty()) return 0
        val batch = sources.mapIndexed { idx, src ->
            MapSqlParameterSource()
                .addValue("metricId", metricId)
                .addValue("seq", idx + 1)
                .addValue("schema", src.schema.take(63))
                .addValue("table", src.table.take(63))
                .addValue("column", src.column?.take(63))
                .addValue("aggExpr", src.aggExpr?.take(300))
                .addValue("remark", src.remark?.take(200))
        }.toTypedArray()
        return jdbcTemplate.batchUpdate(
            """
            INSERT INTO ax.tb_met_metric_source (metric_id, src_seq, src_schema, src_table, src_column, agg_expr, remark)
            VALUES (:metricId, :seq, :schema, :table, :column, :aggExpr, :remark)
            """.trimIndent(),
            batch
        ).sum()
    }

    /** 산출 근거 한 줄 */
    data class SourceWrite(
        val schema: String,
        val table: String,
        val column: String?,
        val aggExpr: String?,
        val remark: String?
    )

    /** 측정값 목록 — 화면의 "측정 데이터" 탭 */
    fun findValues(
        metricId: Int?,
        from: java.time.OffsetDateTime?,
        to: java.time.OffsetDateTime?,
        judgeCd: String?,
        limit: Int,
        offset: Int
    ): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT v.value_id, v.metric_id, s.metric_cd, s.metric_nm, s.unit_cd,
                   v.measured_at, v.metric_value, v.judge_cd, jc.code_nm AS judge_nm,
                   v.plant_cd, v.wc_cd, v.eqpt_cd, v.mold_cd, v.item_cd, v.lot_no, v.serial_no,
                   v.src_cd, v.remark
            FROM ax.tb_met_metric_value v
            INNER JOIN ax.tb_met_metric_std s ON s.metric_id = v.metric_id
            LEFT  JOIN ax.tb_sys_code jc ON jc.group_cd = 'MET_JUDGE' AND jc.code = v.judge_cd
            WHERE 1 = 1
            """.trimIndent()
        )
        val params = MapSqlParameterSource()
        appendValueFilters(sql, params, metricId, from, to, judgeCd)
        sql.append("\nORDER BY v.measured_at DESC, v.value_id DESC\nLIMIT :limit OFFSET :offset")
        params.addValue("limit", limit).addValue("offset", offset)

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "valueId" to rs.getLong("value_id"),
                "metricId" to rs.getInt("metric_id"),
                "metricCd" to rs.getString("metric_cd"),
                "metricNm" to rs.getString("metric_nm"),
                "unit" to rs.getString("unit_cd"),
                "measuredAt" to Rs.dateTime(rs, "measured_at"),
                "value" to rs.getBigDecimal("metric_value"),
                "judge" to rs.getString("judge_cd"),
                "judgeNm" to rs.getString("judge_nm"),
                "plantCd" to rs.getString("plant_cd"),
                "wcCd" to rs.getString("wc_cd"),
                "eqptCd" to rs.getString("eqpt_cd"),
                "moldCd" to rs.getString("mold_cd"),
                "itemCd" to rs.getString("item_cd"),
                "lotNo" to rs.getString("lot_no"),
                "serialNo" to rs.getString("serial_no"),
                "src" to rs.getString("src_cd"),
                "remark" to rs.getString("remark")
            )
        }
    }

    /** 측정값 건수 */
    fun countValues(
        metricId: Int?,
        from: java.time.OffsetDateTime?,
        to: java.time.OffsetDateTime?,
        judgeCd: String?
    ): Long {
        val sql = StringBuilder("SELECT count(*) FROM ax.tb_met_metric_value v WHERE 1 = 1")
        val params = MapSqlParameterSource()
        appendValueFilters(sql, params, metricId, from, to, judgeCd)
        return jdbcTemplate.queryForObject(sql.toString(), params, Long::class.java) ?: 0L
    }

    private fun appendValueFilters(
        sql: StringBuilder,
        params: MapSqlParameterSource,
        metricId: Int?,
        from: java.time.OffsetDateTime?,
        to: java.time.OffsetDateTime?,
        judgeCd: String?
    ) {
        if (metricId != null) {
            sql.append(" AND v.metric_id = :metricId")
            params.addValue("metricId", metricId)
        }
        if (from != null) {
            sql.append(" AND v.measured_at >= :from")
            params.addValue("from", from)
        }
        if (to != null) {
            sql.append(" AND v.measured_at < :toExclusive")
            params.addValue("toExclusive", to)
        }
        if (!judgeCd.isNullOrBlank()) {
            sql.append(" AND v.judge_cd = :judgeCd")
            params.addValue("judgeCd", judgeCd.trim())
        }
    }

}
