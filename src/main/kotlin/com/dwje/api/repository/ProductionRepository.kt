package com.dwje.api.repository

import com.dwje.api.common.util.Rs
import com.dwje.api.common.util.SqlLikeUtils
import com.dwje.api.common.util.safeRate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.LocalDate

/**
 * 생산 모니터링 · 실적 집계 Repository (PR-01, PR-02)
 *
 * IoT 복합 센서가 부착된 프레스 10대와 AOI 검사기 10대의 진행 현황을 조회하며,
 * 실적 집계는 라벨 이력(mes.tb_pop_label_hist)과 재고 이동 이력(mes.tb_pop_stock_hist)을 사용한다.
 */
@Repository
class ProductionRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) {

    /**
     * 모니터링 요약을 조회한다. (No.55)
     *
     * 가동 상태는 최근 수집된 가동률 지표로 판정한다.
     * - 실측 없음  : 정지(stopped)
     * - 기준 미만  : 경고(warning)
     * - 그 외      : 가동(running)
     *
     * @param plantCd   사업장 코드
     * @param processId 공정 코드
     * @param warnLevel 경고 판정 가동률 임계값
     */
    fun findMonitorSummary(plantCd: String, processId: String?, warnLevel: Double): Map<String, Any?> {
        val sql = StringBuilder(
            """
            WITH eqpt AS (
                SELECT
                    e.eqpt_cd,
                    (
                        SELECT round(avg(mv.metric_value), 2)
                          FROM ax.tb_met_metric_value mv
                         INNER JOIN ax.tb_met_metric_std ms ON ms.metric_id = mv.metric_id
                         WHERE ms.metric_cd    = 'EQPT_UPTIME_RATE'
                           AND mv.eqpt_cd      = e.eqpt_cd
                           AND mv.measured_at >= now() - interval '1 hour'
                    ) AS uptime_rate
                FROM mes.tb_md_eqpt e
                INNER JOIN mes.tb_md_eqpt_by_workcenter ew
                        ON ew.plant_cd = e.plant_cd AND ew.eqpt_cd = e.eqpt_cd
                WHERE e.plant_cd = :plantCd
                  AND e.use_flg  = 'Y'
                  {PROCESS_FILTER}
            ),
            throughput AS (
                SELECT coalesce(sum(lh.normal), 0) + coalesce(sum(lh.defect), 0) AS qty
                FROM mes.tb_pop_label_hist lh
                WHERE lh.plant_cd  = :plantCd
                  AND lh.del_flg   = 'N'
                  AND lh.ins_date >= now() - interval '1 hour'
            )
            SELECT
                count(*) FILTER (WHERE eqpt.uptime_rate IS NOT NULL AND eqpt.uptime_rate >= :warnLevel) AS running,
                count(*) FILTER (WHERE eqpt.uptime_rate IS NOT NULL AND eqpt.uptime_rate <  :warnLevel) AS warning,
                count(*) FILTER (WHERE eqpt.uptime_rate IS NULL)                                        AS stopped,
                max(throughput.qty)                                                                     AS hourly_throughput
            FROM eqpt, throughput
            """.trimIndent()
        )

        val params = MapSqlParameterSource()
            .addValue("plantCd", plantCd)
            .addValue("warnLevel", warnLevel)

        val processFilter = if (!processId.isNullOrBlank()) {
            params.addValue("processId", processId.trim())
            "AND ew.wc_cd = :processId"
        } else {
            ""
        }

        return jdbcTemplate.queryForObject(sql.toString().replace("{PROCESS_FILTER}", processFilter), params) { rs, _ ->
            mapOf(
                "running" to rs.getLong("running"),
                "warning" to rs.getLong("warning"),
                "stopped" to rs.getLong("stopped"),
                "hourlyThroughput" to Rs.qty(rs, "hourly_throughput")
            )
        } ?: emptyMap()
    }

    /**
     * 정지 상태 설비의 상세를 조회한다. (No.55 — stoppedDetail)
     */
    fun findStoppedDetail(plantCd: String, processId: String?): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT
                e.eqpt_cd,
                e.eqpt_nm,
                ew.wc_cd,
                d.stop_at,
                d.reason_cd,
                c.code_nm AS reason_nm,
                extract(epoch FROM (now() - d.stop_at)) / 60 AS elapsed_min
            FROM mes.tb_md_eqpt e
            INNER JOIN mes.tb_md_eqpt_by_workcenter ew
                    ON ew.plant_cd = e.plant_cd AND ew.eqpt_cd = e.eqpt_cd
            LEFT JOIN LATERAL (
                SELECT dt.stop_at, dt.reason_cd
                FROM ax.tb_prod_downtime dt
                WHERE dt.plant_cd  = e.plant_cd
                  AND dt.eqpt_cd   = e.eqpt_cd
                  AND dt.resume_at IS NULL
                ORDER BY dt.stop_at DESC
                LIMIT 1
            ) d ON true
            LEFT JOIN ax.tb_sys_code c
                   ON c.group_cd = 'DOWN_REASON' AND c.code = d.reason_cd
            WHERE e.plant_cd = :plantCd
              AND e.use_flg  = 'Y'
              AND d.stop_at IS NOT NULL
            """.trimIndent()
        )

        val params = MapSqlParameterSource("plantCd", plantCd)
        if (!processId.isNullOrBlank()) {
            sql.append(" AND ew.wc_cd = :processId")
            params.addValue("processId", processId.trim())
        }
        sql.append("\nORDER BY d.stop_at")

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "eqptCd" to rs.getString("eqpt_cd"),
                "eqptNm" to rs.getString("eqpt_nm"),
                "processId" to rs.getString("wc_cd"),
                "stopAt" to Rs.dateTime(rs, "stop_at"),
                "reasonCd" to rs.getString("reason_cd"),
                "reasonNm" to rs.getString("reason_nm"),
                "elapsedMin" to Rs.doubleOrNull(rs, "elapsed_min")?.toInt()
            )
        }
    }

    /**
     * 설비별 실시간 현황을 조회한다. (No.56 — 10초 폴링)
     *
     * @param lineRange 설비코드 **전방 일치** 검색어. 범위(`A ~ B`) 표기가 아니라 접두어다 (예 `MT` → MT-002…MT-007)
     * @param model     설비 모델명
     * @param processId 공정(워크센터) 코드 — 응답의 `processId` 와 같은 값 (예 S120)
     * @param state     상태 필터 (RUNNING / WARNING / STOPPED)
     */
    fun findMonitorEquipments(
        plantCd: String,
        lineRange: String?,
        model: String?,
        processId: String?,
        state: String?,
        warnLevel: Double,
        limit: Int,
        offset: Int
    ): List<Map<String, Any?>> {
        val sql = StringBuilder(baseMonitorSql())
        val params = monitorParams(plantCd, lineRange, model, processId, warnLevel)
        appendMonitorStateFilter(sql, params, state)

        sql.append("\nORDER BY e.eqpt_cd\nLIMIT :limit OFFSET :offset")
        params.addValue("limit", limit).addValue("offset", offset)

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            val uptime = Rs.rate(rs, "uptime_rate")
            mapOf(
                "eqptCd" to rs.getString("eqpt_cd"),
                "eqptNm" to rs.getString("eqpt_nm"),
                "model" to rs.getString("model_nm"),
                "processId" to rs.getString("wc_cd"),
                "qty" to Rs.qty(rs, "total_qty"),
                "okQty" to Rs.qty(rs, "ok_qty"),
                "ngQty" to Rs.qty(rs, "ng_qty"),
                "defectRate" to safeRate(rs.getBigDecimal("ng_qty"), rs.getBigDecimal("total_qty")),
                "uptimeRate" to uptime,
                "strokeSpeed" to Rs.rate(rs, "stroke_speed"),
                "moldCd" to rs.getString("mold_cd"),
                "lastCollectedAt" to Rs.dateTime(rs, "last_measured_at"),
                "state" to rs.getString("eqpt_state")
            )
        }
    }

    /** 설비별 실시간 현황 전체 건수 */
    fun countMonitorEquipments(
        plantCd: String,
        lineRange: String?,
        model: String?,
        processId: String?,
        state: String?,
        warnLevel: Double
    ): Long {
        val sql = StringBuilder("SELECT count(*) FROM (\n${baseMonitorSql()}\n")
        val params = monitorParams(plantCd, lineRange, model, processId, warnLevel)
        appendMonitorStateFilter(sql, params, state)
        sql.append("\n) t")

        return jdbcTemplate.queryForObject(sql.toString(), params, Long::class.java) ?: 0L
    }

    /**
     * 설비별 실시간 현황 기본 SQL
     *
     * 당일 생산 실적과 최근 1시간 가동률·타발 속도를 설비 단위로 결합한다.
     */
    private fun baseMonitorSql(): String = """
        SELECT
            e.eqpt_cd,
            e.eqpt_nm,
            e.model_nm,
            ew.wc_cd,
            coalesce(prod.ok_qty, 0)                            AS ok_qty,
            coalesce(prod.ng_qty, 0)                            AS ng_qty,
            coalesce(prod.ok_qty, 0) + coalesce(prod.ng_qty, 0)  AS total_qty,
            m.uptime_rate,
            m.last_measured_at,
            s.stroke_speed,
            mold.mold_cd,
            CASE
                WHEN m.uptime_rate IS NULL          THEN 'STOPPED'
                WHEN m.uptime_rate <  :warnLevel    THEN 'WARNING'
                ELSE 'RUNNING'
            END AS eqpt_state
        FROM mes.tb_md_eqpt e
        INNER JOIN mes.tb_md_eqpt_by_workcenter ew
                ON ew.plant_cd = e.plant_cd AND ew.eqpt_cd = e.eqpt_cd
        LEFT JOIN LATERAL (
            SELECT coalesce(sum(lh.normal), 0) AS ok_qty, coalesce(sum(lh.defect), 0) AS ng_qty
            FROM mes.tb_pop_label_hist lh
            WHERE lh.plant_cd  = e.plant_cd
              AND lh.eqpt_cd   = e.eqpt_cd
              AND lh.del_flg   = 'N'
              AND lh.ins_date >= date_trunc('day', now())
        ) prod ON true
        LEFT JOIN LATERAL (
            SELECT round(avg(mv.metric_value), 2) AS uptime_rate, max(mv.measured_at) AS last_measured_at
            FROM ax.tb_met_metric_value mv
            INNER JOIN ax.tb_met_metric_std ms ON ms.metric_id = mv.metric_id
            WHERE ms.metric_cd    = 'EQPT_UPTIME_RATE'
              AND mv.eqpt_cd      = e.eqpt_cd
              AND mv.measured_at >= now() - interval '1 hour'
        ) m ON true
        LEFT JOIN LATERAL (
            SELECT round(avg(mv.metric_value), 2) AS stroke_speed
            FROM ax.tb_met_metric_value mv
            INNER JOIN ax.tb_met_metric_std ms ON ms.metric_id = mv.metric_id
            WHERE ms.metric_cd    = 'PRESS_STROKE_SPM'
              AND mv.eqpt_cd      = e.eqpt_cd
              AND mv.measured_at >= now() - interval '1 hour'
        ) s ON true
        LEFT JOIN LATERAL (
            SELECT me.mold_cd
            FROM mes.tb_md_mold_by_eqpt me
            WHERE me.plant_cd = e.plant_cd AND me.eqpt_cd = e.eqpt_cd
            ORDER BY me.ins_date DESC
            LIMIT 1
        ) mold ON true
        WHERE e.plant_cd = :plantCd
          AND e.use_flg  = 'Y'
          AND (:lineRange::varchar IS NULL OR e.eqpt_cd LIKE :lineRange ESCAPE '\')
          AND (:model::varchar     IS NULL OR e.model_nm = :model)
          AND (:processId::varchar IS NULL OR ew.wc_cd = :processId)
    """.trimIndent()

    /** 모니터링 공통 파라미터 */
    private fun monitorParams(
        plantCd: String,
        lineRange: String?,
        model: String?,
        processId: String?,
        warnLevel: Double
    ): MapSqlParameterSource = MapSqlParameterSource()
        .addValue("plantCd", plantCd)
        .addValue("warnLevel", warnLevel)
        .addValue("lineRange", SqlLikeUtils.startsWith(lineRange))
        .addValue("model", model?.trim()?.takeIf { it.isNotBlank() })
        .addValue("processId", processId?.trim()?.takeIf { it.isNotBlank() })

    /** 상태 필터는 CASE 식 결과를 감싸는 서브쿼리 밖에서 적용한다. */
    private fun appendMonitorStateFilter(sql: StringBuilder, params: MapSqlParameterSource, state: String?) {
        if (state.isNullOrBlank()) return
        val normalized = state.trim().uppercase()
        sql.append(
            when (normalized) {
                "STOPPED" -> "\n  AND m.uptime_rate IS NULL"
                "WARNING" -> "\n  AND m.uptime_rate IS NOT NULL AND m.uptime_rate < :warnLevel"
                "RUNNING" -> "\n  AND m.uptime_rate IS NOT NULL AND m.uptime_rate >= :warnLevel"
                else -> ""
            }
        )
    }

    /**
     * 실적 집계를 조회한다. (No.57)
     *
     * @param unit 집계 단위 — day | week | month
     */
    fun findResults(
        filter: ResultFilter,
        unit: String,
        limit: Int,
        offset: Int
    ): List<Map<String, Any?>> {
        val sql = StringBuilder(baseResultSql(unit, filter))
        val params = resultParams(filter)

        sql.append("\nORDER BY prod.period DESC\nLIMIT :limit OFFSET :offset")
        params.addValue("limit", limit).addValue("offset", offset)

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            val ok = rs.getBigDecimal("ok_qty")
            val ng = rs.getBigDecimal("ng_qty")
            val total = rs.getBigDecimal("total_qty")
            mapOf(
                "period" to rs.getString("period"),
                "inputQty" to Rs.qty(rs, "total_qty"),
                "okQty" to Rs.qty(rs, "ok_qty"),
                "ngQty" to Rs.qty(rs, "ng_qty"),
                "defectRate" to safeRate(ng, total),
                "yield" to safeRate(ok, total),
                "uptimeRate" to Rs.rate(rs, "uptime_rate"),
                "downtimeMin" to Rs.intOrNull(rs, "downtime_min")
            )
        }
    }

    /** 실적 집계 전체 건수 (기간 그룹 수) */
    fun countResults(filter: ResultFilter, unit: String): Long {
        val sql = "SELECT count(*) FROM (\n${baseResultSql(unit, filter)}\n) t"
        return jdbcTemplate.queryForObject(sql, resultParams(filter), Long::class.java) ?: 0L
    }

    /** 등록된 설비 코드인지 확인한다. (실적 필터 검증용) */
    fun existsEquipment(plantCd: String, eqptCd: String): Boolean =
        jdbcTemplate.queryForObject(
            """
            SELECT exists(
                SELECT 1 FROM mes.tb_md_eqpt
                WHERE plant_cd = :plantCd AND eqpt_cd = :eqptCd AND use_flg = 'Y'
            )
            """.trimIndent(),
            MapSqlParameterSource().addValue("plantCd", plantCd).addValue("eqptCd", eqptCd),
            Boolean::class.java
        ) ?: false

    /**
     * 품목 매핑에 등록된 품목 코드인지 확인한다. (실적 필터 검증용)
     *
     * 매핑에 없어도 실적에 존재할 수 있는 품목이 소수 있으므로(2026-08 기준 1,136종 중 30종),
     * 이 값이 false 라는 것만으로 잘못된 코드라고 단정하지 않는다.
     */
    fun existsMappedItem(plantCd: String, itemCd: String): Boolean =
        jdbcTemplate.queryForObject(
            """
            SELECT exists(
                SELECT 1 FROM ax.tb_prod_item_map WHERE plant_cd = :plantCd AND item_cd = :itemCd
            )
            """.trimIndent(),
            MapSqlParameterSource().addValue("plantCd", plantCd).addValue("itemCd", itemCd),
            Boolean::class.java
        ) ?: false

    /** 등록된 제품 모델 코드인지 확인한다. (실적 필터 검증용) */
    fun existsProductModel(modelCd: String): Boolean =
        jdbcTemplate.queryForObject(
            """
            SELECT exists(
                SELECT 1 FROM ax.tb_prod_product WHERE model_cd = :modelCd AND use_flg = 'Y'
            )
            """.trimIndent(),
            MapSqlParameterSource("modelCd", modelCd),
            Boolean::class.java
        ) ?: false

    /**
     * 실적 집계 요약(전 기간 합계)을 조회한다. (No.57 — summary)
     */
    fun findResultSummary(filter: ResultFilter): Map<String, Any?> {
        val sql = """
            SELECT
                coalesce(sum(lh.normal), 0)                               AS ok_qty,
                coalesce(sum(lh.defect), 0)                               AS ng_qty,
                coalesce(sum(lh.normal), 0) + coalesce(sum(lh.defect), 0) AS total_qty
            FROM mes.tb_pop_label_hist lh
            WHERE lh.plant_cd  = :plantCd
              AND lh.del_flg   = 'N'
              AND lh.ins_date >= :from
              AND lh.ins_date <  :toExclusive
              AND (:itemCd::varchar IS NULL OR lh.item_cd = :itemCd)
              AND (:lineCd::varchar IS NULL OR lh.eqpt_cd = :lineCd)
              ${modelFilterSql(filter.modelCd)}
        """.trimIndent()

        return jdbcTemplate.queryForObject(sql, resultParams(filter)) { rs, _ ->
            mapOf(
                "inputQty" to Rs.qty(rs, "total_qty"),
                "okQty" to Rs.qty(rs, "ok_qty"),
                "ngQty" to Rs.qty(rs, "ng_qty"),
                "defectRate" to safeRate(rs.getBigDecimal("ng_qty"), rs.getBigDecimal("total_qty")),
                "yield" to safeRate(rs.getBigDecimal("ok_qty"), rs.getBigDecimal("total_qty"))
            )
        } ?: emptyMap()
    }

    /**
     * 실적 집계 기본 SQL — 집계 단위에 따라 기간 표기를 달리한다.
     *
     * 집계 단위 문자열은 화이트리스트로 검증된 값만 전달된다. (SQL Injection 방지)
     */
    /**
     * 실적 집계 기본 SQL — 집계 단위에 따라 기간 표기를 달리한다.
     *
     * 집계 단위 문자열은 화이트리스트로 검증된 값만 전달된다. (SQL Injection 방지)
     *
     * 가동률·비가동 시간은 **기간으로 미리 집계한 CTE 를 LEFT JOIN** 한다.
     * 상관 서브쿼리에서 바깥 쿼리의 lh.ins_date 를 참조하면
     * PostgreSQL 이 "subquery uses ungrouped column" 오류를 낸다.
     */
    private fun baseResultSql(unit: String, filter: ResultFilter): String {
        val fmt = periodFormatLiteral(unit)
        val truncUnit = when (unit.lowercase()) {
            "week" -> "week"
            "month" -> "month"
            else -> "day"
        }

        return """
            WITH prod AS (
                SELECT
                    to_char(date_trunc('$truncUnit', lh.ins_date), $fmt)       AS period,
                    coalesce(sum(lh.normal), 0)                               AS ok_qty,
                    coalesce(sum(lh.defect), 0)                               AS ng_qty,
                    coalesce(sum(lh.normal), 0) + coalesce(sum(lh.defect), 0) AS total_qty
                FROM mes.tb_pop_label_hist lh
                WHERE lh.plant_cd  = :plantCd
                  AND lh.del_flg   = 'N'
                  AND lh.ins_date >= :from
                  AND lh.ins_date <  :toExclusive
                  AND (:itemCd::varchar IS NULL OR lh.item_cd = :itemCd)
                  AND (:lineCd::varchar IS NULL OR lh.eqpt_cd = :lineCd)
                  ${modelFilterSql(filter.modelCd)}
                GROUP BY 1
            ),
            uptime AS (
                SELECT
                    to_char(date_trunc('$truncUnit', mv.measured_at), $fmt) AS period,
                    round(avg(mv.metric_value), 2)                          AS uptime_rate
                FROM ax.tb_met_metric_value mv
                INNER JOIN ax.tb_met_metric_std ms ON ms.metric_id = mv.metric_id
                WHERE ms.metric_cd    = 'EQPT_UPTIME_RATE'
                  AND mv.measured_at >= :from
                  AND mv.measured_at <  :toExclusive
                  AND (mv.plant_cd IS NULL OR mv.plant_cd = :plantCd)
                GROUP BY 1
            ),
            downtime AS (
                SELECT
                    to_char(date_trunc('$truncUnit', dt.stop_at), $fmt) AS period,
                    coalesce(sum(dt.elapsed_min), 0)                    AS downtime_min
                FROM ax.tb_prod_downtime dt
                WHERE dt.plant_cd  = :plantCd
                  AND dt.stop_at  >= :from
                  AND dt.stop_at  <  :toExclusive
                  AND (:lineCd::varchar IS NULL OR dt.eqpt_cd = :lineCd)
                GROUP BY 1
            )
            SELECT
                prod.period,
                prod.ok_qty,
                prod.ng_qty,
                prod.total_qty,
                uptime.uptime_rate,
                downtime.downtime_min
            FROM prod
            LEFT JOIN uptime   ON uptime.period   = prod.period
            LEFT JOIN downtime ON downtime.period = prod.period
        """.trimIndent()
    }

    /** 집계 단위별 to_char 포맷 리터럴 */
    private fun periodFormatLiteral(unit: String): String = when (unit.lowercase()) {
        "week" -> "'IYYY-\"W\"IW'"
        "month" -> "'YYYY-MM'"
        else -> "'YYYY-MM-DD'"
    }

    /** 실적 집계 공통 파라미터 */
    private fun resultParams(filter: ResultFilter): MapSqlParameterSource = MapSqlParameterSource()
        .addValue("plantCd", filter.plantCd)
        .addValue("from", filter.from.atStartOfDay())
        .addValue("toExclusive", filter.to.plusDays(1).atStartOfDay())
        .addValue("itemCd", filter.itemCd)
        .addValue("lineCd", filter.lineCd)
        .apply { if (filter.modelCd != null) addValue("modelCd", filter.modelCd) }

    /**
     * 모델 코드 필터절.
     *
     * 실적의 품목 코드(`label_hist.item_cd` — 예 `D63A-S`)와 제품 선택에 쓰는
     * 모델 코드(`tb_prod_product.model_cd` — 예 `D63A`)는 서로 다른 값이다.
     * 한 모델에 여러 품목이 달리므로 품목 매핑을 거쳐 EXISTS 로 건다.
     */
    private fun modelFilterSql(modelCd: String?): String =
        if (modelCd == null) "" else
            "AND EXISTS (SELECT 1 FROM ax.tb_prod_item_map pm " +
                "INNER JOIN ax.tb_prod_product p ON p.product_id = pm.product_id " +
                "WHERE pm.plant_cd = lh.plant_cd AND pm.item_cd = lh.item_cd " +
                "AND p.model_cd = :modelCd)"
}

/**
 * 실적 집계 조회 조건
 *
 * 품목 코드와 모델 코드는 서로 다른 값이다. 섞어 쓰면 조용히 0건이 나온다.
 *
 * @param itemCd  실적 품목 코드 — `label_hist.item_cd` 정확 일치 (예 `D63A-S`)
 * @param modelCd 제품 모델 코드 — 그 모델에 매핑된 품목 전부 (예 `D63A`)
 * @param lineCd  설비 코드 — `label_hist.eqpt_cd` 정확 일치 (예 `MT-007`). 공정 코드가 아니다
 */
data class ResultFilter(
    val plantCd: String,
    val from: LocalDate,
    val to: LocalDate,
    val itemCd: String? = null,
    val modelCd: String? = null,
    val lineCd: String? = null
) {
    companion object {
        /** 공백을 정리해 조건을 만든다. 빈 문자열은 조건 없음으로 본다. */
        fun of(
            plantCd: String,
            from: LocalDate,
            to: LocalDate,
            itemCd: String?,
            modelCd: String?,
            lineCd: String?
        ): ResultFilter = ResultFilter(
            plantCd = plantCd,
            from = from,
            to = to,
            itemCd = itemCd?.trim()?.takeIf { it.isNotBlank() },
            modelCd = modelCd?.trim()?.takeIf { it.isNotBlank() },
            lineCd = lineCd?.trim()?.takeIf { it.isNotBlank() }
        )
    }
}
