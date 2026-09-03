package com.dwje.api.repository

import com.dwje.api.common.util.Rs
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

/**
 * 지표 기준·측정값 Repository (SY-13, DB-03)
 *
 * 참조 테이블
 * - 기준정보 : ax.tb_met_metric_std, ax.tb_met_metric_source, ax.tb_met_metric_std_hist
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

    /**
     * 지표 기준 요약 (No.215)
     */
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

    /**
     * 판정 등급별 최근 측정 건수를 조회한다. (요약 화면 criticalCnt / warnCnt)
     */
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

    /**
     * 지표 기준 목록을 조회한다. (No.216)
     *
     * @param category 지표 구분 (MET_CATEGORY)
     * @param applied  적용 여부
     * @param level    최근 판정 등급 (NORMAL/WARN/CRIT)
     */
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

    /** 지표 기준 목록 전체 건수 */
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
     * 지표 기준을 등록한다. (No.217)
     *
     * @return 생성된 지표 ID
     */
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

    /** 지표 기준 단건 조회 */
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

    /** 공통코드 그룹에 그 코드가 있는지 확인한다. (요청 값 검증용) */
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

    /** 공통코드 그룹의 코드 목록. (오류 메시지에 허용 값을 적기 위함) */
    fun findCodeList(groupCd: String): List<String> =
        jdbcTemplate.query(
            "SELECT code FROM ax.tb_sys_code WHERE group_cd = :groupCd AND use_flg = 'Y' ORDER BY sort_seq",
            MapSqlParameterSource("groupCd", groupCd)
        ) { rs, _ -> rs.getString("code") }

    /**
     * 지표 기준 수치를 수정한다. (No.218)
     */
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

    /**
     * 지표 적용/해제 상태를 변경한다. (No.219)
     */
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

    /**
     * 기준 수치 변경 이력을 기록한다. (ax.tb_met_metric_std_hist)
     *
     * @param fieldCd MET_STD_FIELD — STD / WARN / CRIT / WINDOW / USE
     */
    fun insertStandardHistory(
        metricId: Int,
        fieldCd: String,
        beforeVal: String?,
        afterVal: String?,
        changedBy: String,
        actorDeptNm: String?
    ) {
        val sql = """
            INSERT INTO ax.tb_met_metric_std_hist (
                metric_id, changed_at, field_cd, before_val, after_val, changed_by, actor_dept_nm
            ) VALUES (
                :metricId, now(), :fieldCd, :beforeVal, :afterVal, :changedBy, :actorDeptNm
            )
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("metricId", metricId)
            .addValue("fieldCd", fieldCd)
            .addValue("beforeVal", beforeVal?.take(50))
            .addValue("afterVal", afterVal?.take(50))
            .addValue("changedBy", changedBy)
            .addValue("actorDeptNm", actorDeptNm)

        jdbcTemplate.update(sql, params)
    }

    /**
     * 기준 수치 변경 이력을 조회한다. (No.220)
     */
    fun findStandardHistory(metricId: Int?, limit: Int, offset: Int): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT
                h.hist_id, h.changed_at, h.field_cd, h.before_val, h.after_val,
                h.changed_by, h.actor_dept_nm, ms.metric_nm, u.user_nm
            FROM ax.tb_met_metric_std_hist h
            INNER JOIN ax.tb_met_metric_std ms ON ms.metric_id = h.metric_id
            LEFT  JOIN ax.tb_sys_user u        ON u.user_id    = h.changed_by
            WHERE 1 = 1
            """.trimIndent()
        )

        val params = MapSqlParameterSource()
        if (metricId != null) {
            sql.append(" AND h.metric_id = :metricId")
            params.addValue("metricId", metricId)
        }

        sql.append("\nORDER BY h.changed_at DESC\nLIMIT :limit OFFSET :offset")
        params.addValue("limit", limit).addValue("offset", offset)

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "ts" to Rs.dateTime(rs, "changed_at"),
                "metric" to rs.getString("metric_nm"),
                "field" to rs.getString("field_cd"),
                "before" to rs.getString("before_val"),
                "after" to rs.getString("after_val"),
                "by" to (rs.getString("user_nm") ?: rs.getString("changed_by")),
                "byDept" to rs.getString("actor_dept_nm")
            )
        }
    }

    /** 기준 수치 변경 이력 전체 건수 */
    fun countStandardHistory(metricId: Int?): Long {
        val sql = StringBuilder("SELECT count(*) FROM ax.tb_met_metric_std_hist h WHERE 1 = 1")
        val params = MapSqlParameterSource()
        if (metricId != null) {
            sql.append(" AND h.metric_id = :metricId")
            params.addValue("metricId", metricId)
        }
        return jdbcTemplate.queryForObject(sql.toString(), params, Long::class.java) ?: 0L
    }

    /**
     * 기준 수치 사용처를 조회한다. (No.221)
     *
     * 알림 발송 조건 · 대시보드 · 보고서 중 해당 지표를 참조하는 항목을 찾는다.
     */
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

    /** 지표 코드 중복 여부 확인 */
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
