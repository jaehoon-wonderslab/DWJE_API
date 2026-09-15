package com.dwje.api.repository

import com.dwje.api.common.util.DefectSql
import com.dwje.api.common.util.Rs
import com.dwje.api.common.util.safeRate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDate

/**
 * 품질 현황 · AOI 판정 분석 Repository (QC-01, QC-02)
 *
 * 참조 테이블 : mes.tb_pop_defect_hist, mes.tb_pop_label_hist, mes.tb_md_defect,
 *              mes.tb_md_eqpt, mes.tb_md_mold, ax.tb_ai_model_config, ax.tb_ai_agent_run
 */
@Repository
class QualityRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) {

    /**
     * 불량 현황 요약을 조회한다. (No.73)
     *
     * 전기 대비 증감(momChange)을 함께 산출한다.
     *
     * ── 불량 수량의 기준은 `mes.tb_pop_label_hist.defect` 다 ────────────────────────
     * 이전에는 분자를 `tb_pop_defect_hist.qty`, 분모를 `tb_pop_label_hist` 총생산으로 두고
     * 두 테이블에 기간 필터를 각각 걸었다. 두 테이블은 수량 합계가 공정별로 어긋나므로
     * (검사·포장 공정은 defect_hist 가 라벨보다 크고, 프레스는 작다) 불량률이 부풀려졌다.
     * 2026-08-28 / PL01 실측으로 427,562 대 792,473, 약 1.85배 차이였다.
     *
     * 그래서 수량과 비율은 전부 라벨 이력을 원장으로 삼는다.
     * `tb_pop_defect_hist` 는 불량 *유형* 을 가려낼 때만 쓴다. (docs/MES_QUERY_GUIDE.md 2-4)
     *
     * 유형(`defectTypeCd`)을 지정하면 라벨의 불량 수량을 그 라벨의 유형 구성비로 안분한다.
     *     해당 유형 수량 = label.defect × (해당 유형 qty ÷ 그 라벨의 전체 유형 qty 합)
     * 유형별 수량 합계가 라벨 총 불량과 일치하도록 유지하기 위한 방식이며,
     * 두 테이블 수량이 이미 맞는 공정에서는 `defect_hist.qty` 와 같은 값이 된다.
     *
     * @param plantCd      사업장 코드
     * @param from         조회 시작일
     * @param to           조회 종료일
     * @param processId    공정 코드
     * @param defectTypeCd 불량 유형 코드
     */
    fun findDefectSummary(
        plantCd: String,
        from: LocalDate,
        to: LocalDate,
        processId: String?,
        defectTypeCd: String?
    ): Map<String, Any?> {
        val params = periodParams(plantCd, from, to)
        val days = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1
        params.addValue("prevFrom", from.minusDays(days).atStartOfDay())
        params.addValue("prevToExclusive", from.atStartOfDay())

        var prodFilter = ""
        if (!processId.isNullOrBlank()) {
            prodFilter = " AND lh.wc_cd = :processId"
            params.addValue("processId", processId.trim())
        }

        val byType = !defectTypeCd.isNullOrBlank()
        if (byType) params.addValue("defectTypeCd", defectTypeCd!!.trim())

        // 기간별 라벨 실적 CTE. 기간 필터는 라벨 이력에만 건다.
        fun labelCte(alias: String, fromParam: String, toParam: String) = """
            $alias AS (
                SELECT lh.plant_cd, lh.wc_cd, lh.lot_no, lh.serial_no,
                       coalesce(lh.normal, 0) AS ok_qty,
                       coalesce(lh.defect, 0) AS ng_qty
                FROM mes.tb_pop_label_hist lh
                WHERE lh.plant_cd  = :plantCd
                  AND lh.del_flg   = 'N'
                  AND lh.ins_date >= :$fromParam
                  AND lh.ins_date <  :$toParam
                  $prodFilter
            )
        """.trimIndent()

        // 유형 지정 시 라벨 불량 수량을 유형 구성비로 안분한다.
        // 유형 미지정이면 라벨 불량 수량을 그대로 쓴다.
        fun ngCte(alias: String, labelAlias: String) =
            if (byType) """
            $alias AS (
                SELECT l.ng_qty
                         * sum(dh.qty) FILTER (WHERE dh.defect_cd = :defectTypeCd)
                         / nullif(sum(dh.qty), 0)                            AS ng_qty
                FROM $labelAlias l
                INNER JOIN mes.tb_pop_defect_hist dh
                        ON dh.plant_cd  = l.plant_cd
                       AND dh.wc_cd     = l.wc_cd
                       AND dh.lot_no    = l.lot_no
                       AND dh.serial_no = l.serial_no
                       ${DefectSql.excludeNonProduction()}
                GROUP BY l.plant_cd, l.wc_cd, l.lot_no, l.serial_no, l.ng_qty
                HAVING sum(dh.qty) FILTER (WHERE dh.defect_cd = :defectTypeCd) > 0
            )
            """.trimIndent()
            else """
            $alias AS (
                SELECT l.ng_qty FROM $labelAlias l WHERE l.ng_qty > 0
            )
            """.trimIndent()

        val sql = """
            WITH
            ${labelCte("cur_label", "from", "toExclusive")},
            ${labelCte("prev_label", "prevFrom", "prevToExclusive")},
            ${ngCte("cur_ng", "cur_label")},
            ${ngCte("prev_ng", "prev_label")},
            prod AS (
                SELECT coalesce(sum(ok_qty + ng_qty), 0) AS total_qty FROM cur_label
            )
            SELECT
                (SELECT coalesce(sum(ng_qty), 0) FROM cur_ng)  AS ng_qty,
                (SELECT count(*)                 FROM cur_ng)  AS ng_cnt,
                (SELECT coalesce(sum(ng_qty), 0) FROM prev_ng) AS prev_ng_qty,
                prod.total_qty
            FROM prod
        """.trimIndent()

        return jdbcTemplate.queryForObject(sql, params) { rs, _ ->
            val ngQty = rs.getBigDecimal("ng_qty")
            val prevNgQty = rs.getBigDecimal("prev_ng_qty")
            val totalQty = rs.getBigDecimal("total_qty")

            // 전기 대비 증감률 = (당기 - 전기) / 전기 × 100
            val momChange = if (prevNgQty != null && prevNgQty.toDouble() > 0.0) {
                Math.round((ngQty.toDouble() - prevNgQty.toDouble()) / prevNgQty.toDouble() * 10000) / 100.0
            } else null

            mapOf(
                // 불량이 발생한 라벨 건수 (이전에는 불량 이력 행 수였다)
                "totalCnt" to rs.getLong("ng_cnt"),
                "ngQty" to Rs.qty(rs, "ng_qty"),
                "totalQty" to Rs.qty(rs, "total_qty"),
                "defectRate" to safeRate(ngQty, totalQty),
                "prevNgQty" to Rs.qty(rs, "prev_ng_qty"),
                "momChange" to momChange
            )
        } ?: emptyMap()
    }

    /**
     * 불량 유형별 분포를 조회한다. (No.74 — 전기 대비 증감 포함)
     *
     * 수량은 라벨 원장(`label_hist.defect`) 을 유형 구성비로 안분해 산출한다.
     * `defect_hist` 에 기간을 직접 걸면 다른 행 집합이 잡혀 유형 합계가
     * [findDefectSummary] 의 `ngQty` 와 어긋난다. (MES_QUERY_GUIDE 2-3 / 2-4)
     *
     * 비중(`ratio`) 의 분모도 표시된 유형 합이 아니라 라벨 원장 불량 총량이다.
     * 유형이 붙지 않은 불량은 어떤 유형에도 안분되지 않으므로, 그 차액을
     * [DefectSql.UNTYPED_LABEL] 행으로 마지막에 붙인다.
     * 이러면 `cnt` 합 = [findDefectSummary] 의 `ngQty`, `ratio` 합 = 100% 가 성립한다.
     */
    fun findDefectByType(
        plantCd: String,
        from: LocalDate,
        to: LocalDate,
        processId: String?
    ): List<Map<String, Any?>> {
        val params = periodParams(plantCd, from, to)
        val days = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1
        params.addValue("prevFrom", from.minusDays(days).atStartOfDay())
        params.addValue("prevToExclusive", from.atStartOfDay())

        var prodFilter = ""
        if (!processId.isNullOrBlank()) {
            prodFilter = " AND lh.wc_cd = :processId"
            params.addValue("processId", processId.trim())
        }

        // 비중 분모는 라벨 원장 불량 총량 — findDefectSummary 의 ngQty 와 같은 값이다.
        // 유형이 하나도 없는 라벨도 세어야 하므로 유형 조회와 따로 뽑는다.
        val ledger = jdbcTemplate.queryForObject(
            """
            WITH
            ${DefectSql.labelLedgerCte("cur_label", "from", "toExclusive", prodFilter)},
            ${DefectSql.labelLedgerCte("prev_label", "prevFrom", "prevToExclusive", prodFilter)}
            SELECT
                (SELECT coalesce(sum(ng_qty), 0) FROM cur_label)  AS all_qty,
                (SELECT coalesce(sum(ng_qty), 0) FROM prev_label) AS prev_all_qty
            """.trimIndent(),
            params
        ) { rs, _ -> (Rs.qty(rs, "all_qty") ?: 0L) to (Rs.qty(rs, "prev_all_qty") ?: 0L) }
            ?: (0L to 0L)

        val (allQty, prevAllQty) = ledger

        val sql = """
            WITH
            ${DefectSql.labelLedgerCte("cur_label", "from", "toExclusive", prodFilter)},
            ${DefectSql.labelLedgerCte("prev_label", "prevFrom", "prevToExclusive", prodFilter)},
            ${DefectSql.apportionedTypeCte("cur", "cur_label")},
            ${DefectSql.apportionedTypeCte("prev", "prev_label")}
            SELECT
                cur.defect_cd,
                coalesce(md.defect_nm, cur.defect_cd) AS defect_nm,
                cur.ng_qty,
                prev.ng_qty                           AS prev_ng_qty
            FROM cur
            LEFT JOIN prev ON prev.defect_cd = cur.defect_cd
            LEFT JOIN mes.tb_md_defect md
                   ON md.plant_cd = :plantCd AND md.defect_cd = cur.defect_cd
            ORDER BY cur.ng_qty DESC
        """.trimIndent()

        val allQtyDecimal = BigDecimal.valueOf(allQty)

        val typed = jdbcTemplate.query(sql, params) { rs, _ ->
            val ngQty = rs.getBigDecimal("ng_qty")
            val prevNgQty = rs.getBigDecimal("prev_ng_qty")

            mapOf(
                "defectCd" to rs.getString("defect_cd"),
                "defectType" to rs.getString("defect_nm"),
                "cnt" to Rs.qty(rs, "ng_qty"),
                "ratio" to safeRate(ngQty, allQtyDecimal),
                "momChange" to momChangeOf(ngQty, prevNgQty)
            )
        }

        // 유형 미상 = 원장 총량 − 표시된 유형 수량 합.
        // 표시값(반올림 후) 기준으로 차액을 잡아 cnt 합이 원장 총량과 정확히 맞도록 한다.
        val typedQty = typed.sumOf { (it["cnt"] as? Long) ?: 0L }
        val untypedQty = allQty - typedQty
        if (untypedQty <= 0L) return typed

        val prevTypedQty = jdbcTemplate.queryForObject(
            """
            WITH
            ${DefectSql.labelLedgerCte("prev_label", "prevFrom", "prevToExclusive", prodFilter)},
            ${DefectSql.apportionedTypeCte("prev", "prev_label")}
            SELECT coalesce(sum(ng_qty), 0) AS ng_qty FROM prev
            """.trimIndent(),
            params
        ) { rs, _ -> Rs.qty(rs, "ng_qty") ?: 0L } ?: 0L

        val prevUntypedQty = prevAllQty - prevTypedQty

        return typed + mapOf(
            // 실제 불량코드가 아니므로 코드는 비운다. 화면이 일반 유형과 구분해 그릴 수 있다.
            "defectCd" to null,
            "defectType" to DefectSql.UNTYPED_LABEL,
            "cnt" to untypedQty,
            "ratio" to safeRate(BigDecimal.valueOf(untypedQty), allQtyDecimal),
            "momChange" to momChangeOf(
                BigDecimal.valueOf(untypedQty),
                BigDecimal.valueOf(prevUntypedQty)
            )
        )
    }

    /**
     * 라인(설비)별 불량률과 설비별 불량 유형 내역을 조회한다. (No.75)
     *
     * ## 한 번의 집계로 설비 전부를 낸다 — LATERAL 을 걷어냈다
     * 예전에는 설비마다 LATERAL 로 주 불량 유형을 따로 구했다. GROUP BY 앞에 있을 때는 라벨 행마다
     * 돌아 9월 2일치(라벨 11,992행)에서 60초 타임아웃이 났고, 상위 N 대로 자른 뒤에도 N 회 반복이라
     * "전체 설비" 를 줄 수 없었다. 지금은 기간 라벨 원장 하나를 만들어 설비별 수량(prod)과
     * 설비×유형 안분 수량(types)을 같은 원장에서 집계한다. 비용은 **기간 길이**에만 비례하고 N 과 무관하다.
     * (로컬 7.3M 라벨 실측 — 1일 0.26초, 30일 1.1초, 90일 3.2초, 365일 10.3초)
     *
     * ## 유형 수량은 by-type 와 같은 안분식이다
     * `라벨.defect × (그 유형 qty ÷ 그 라벨의 유형 qty 합)` — [DefectSql.apportionedTypeCte] 와 같다.
     * 그래서 한 설비의 유형 합 = 그 설비의 불량 수량(라벨 원장)이고, 유형이 붙지 않은 몫은
     * [assembleDefectByLine] 이 '유형 미상' 으로 채운다. 주 불량 유형(mainType)은 그 설비에서 안분 수량이
     * 가장 큰 유형이다. (예전 LATERAL 은 `defect_hist.qty` 원값 순이었다 — 재작업 표시 코드를 걸러도
     * 라벨 불량과 어긋나는 분모라 유형 비중을 낼 수 없었다)
     *
     * @param topN 상위 조회 대수. null 이면 전체 설비(설비 마스터 1,540대가 상한이다)
     * @return 설비 한 대가 한 항목 — eqptCd · eqptNm · model · ngQty · okQty · totalQty · defectRate · mainType ·
     *         children[{defectCd, defectType, ngQty, ratio}] (ratio 는 그 설비 불량 합 대비 %)
     */
    fun findDefectByLine(
        plantCd: String,
        from: LocalDate,
        to: LocalDate,
        processId: String?,
        topN: Int?
    ): List<Map<String, Any?>> {
        val sql = """
            WITH label AS (
                SELECT lh.plant_cd, lh.wc_cd, lh.lot_no, lh.serial_no, lh.eqpt_cd,
                       coalesce(lh.normal, 0) AS ok_qty,
                       coalesce(lh.defect, 0) AS ng_qty
                FROM mes.tb_pop_label_hist lh
                WHERE lh.plant_cd  = :plantCd
                  AND lh.del_flg   = 'N'
                  AND lh.eqpt_cd IS NOT NULL
                  AND lh.ins_date >= :from
                  AND lh.ins_date <  :toExclusive
                  {PROCESS_FILTER}
            ),
            prod AS (
                SELECT eqpt_cd,
                       sum(ng_qty)               AS ng_qty,
                       sum(ok_qty)               AS ok_qty,
                       sum(ok_qty) + sum(ng_qty) AS total_qty
                FROM label
                GROUP BY eqpt_cd
                -- coalesce 로 0 을 채운 값이라 NULL 이 상위에 오는 일은 없다.
                ORDER BY sum(ng_qty) DESC, eqpt_cd
                {LIMIT}
            ),
            type_join AS (
                SELECT l.eqpt_cd, dh.defect_cd, l.ng_qty,
                       sum(dh.qty)                                 AS type_qty,
                       sum(sum(dh.qty)) OVER (
                           PARTITION BY l.plant_cd, l.wc_cd, l.lot_no, l.serial_no
                       )                                           AS label_type_total
                FROM label l
                INNER JOIN mes.tb_pop_defect_hist dh
                        ON dh.plant_cd  = l.plant_cd
                       AND dh.wc_cd     = l.wc_cd
                       AND dh.lot_no    = l.lot_no
                       AND dh.serial_no = l.serial_no
                       ${DefectSql.excludeNonProduction()}
                -- 기간 필터는 라벨 이력에만 건다. 불량 이력에 따로 걸면 집합이 어긋난다.
                WHERE l.ng_qty > 0
                GROUP BY l.plant_cd, l.wc_cd, l.lot_no, l.serial_no, l.ng_qty, l.eqpt_cd, dh.defect_cd
            ),
            types AS (
                SELECT eqpt_cd, defect_cd,
                       coalesce(sum(ng_qty * type_qty / nullif(label_type_total, 0)), 0) AS ng_qty
                FROM type_join
                GROUP BY eqpt_cd, defect_cd
            )
            SELECT
                p.eqpt_cd,
                e.eqpt_nm,
                e.model_nm,
                p.ng_qty,
                p.ok_qty,
                p.total_qty,
                t.defect_cd,
                coalesce(md.defect_nm, t.defect_cd) AS defect_nm,
                t.ng_qty                            AS type_ng_qty
            FROM prod p
            LEFT JOIN mes.tb_md_eqpt e
                   ON e.plant_cd = :plantCd AND e.eqpt_cd = p.eqpt_cd
            LEFT JOIN types t ON t.eqpt_cd = p.eqpt_cd
            LEFT JOIN mes.tb_md_defect md
                   ON md.plant_cd = :plantCd AND md.defect_cd = t.defect_cd
            ORDER BY p.ng_qty DESC, p.eqpt_cd, t.ng_qty DESC NULLS LAST, t.defect_cd
        """.trimIndent()

        val params = periodParams(plantCd, from, to)

        val processFilter = if (!processId.isNullOrBlank()) {
            params.addValue("processId", processId.trim())
            "AND lh.wc_cd = :processId"
        } else {
            ""
        }
        val limit = if (topN != null) {
            params.addValue("topN", topN)
            "LIMIT :topN"
        } else {
            ""
        }

        val flat = jdbcTemplate.query(
            sql.replace("{PROCESS_FILTER}", processFilter).replace("{LIMIT}", limit), params
        ) { rs, _ ->
            DefectByLineRow(
                eqptCd = rs.getString("eqpt_cd"),
                eqptNm = rs.getString("eqpt_nm"),
                model = rs.getString("model_nm"),
                ngQty = Rs.qty(rs, "ng_qty") ?: 0L,
                okQty = Rs.qty(rs, "ok_qty") ?: 0L,
                totalQty = Rs.qty(rs, "total_qty") ?: 0L,
                defectCd = rs.getString("defect_cd"),
                defectNm = rs.getString("defect_nm"),
                typeNgQty = Rs.qty(rs, "type_ng_qty")
            )
        }
        return assembleDefectByLine(flat)
    }

    /**
     * 불량 상세 분해 트리의 **수량 원장** — (공정, 설비, 제품) 단위 정상·불량 수량. (QC-01 트리)
     *
     * 라벨 이력을 세 차원으로 묶은 것이라 어떤 순서로 중첩해도 상위 = 하위 합이 성립한다.
     * 설비가 비어 있는 라벨(2026-08~09 실측 30일 7건, 불량 0)은 `eqptCd = null` 행으로 남긴다 — 빼면 요약 카드와 어긋난다.
     * 30일 1,267행 · 365일 4천 행 안팎, 0.3초/1.5초.
     */
    fun findDefectTreeBase(plantCd: String, from: LocalDate, to: LocalDate, processId: String?): List<DefectTreeBaseRow> {
        val params = periodParams(plantCd, from, to)
        val processFilter = processFilterOf(params, processId)
        val sql = """
            SELECT
                lh.wc_cd,
                max(w.wc_nm)                AS wc_nm,
                lh.eqpt_cd,
                max(e.eqpt_nm)              AS eqpt_nm,
                lh.item_cd,
                max(i.item_nm)              AS item_nm,
                coalesce(sum(lh.normal), 0) AS ok_qty,
                coalesce(sum(lh.defect), 0) AS ng_qty
            FROM mes.tb_pop_label_hist lh
            LEFT JOIN mes.tb_md_workcenter w ON w.plant_cd = lh.plant_cd AND w.wc_cd = lh.wc_cd
            LEFT JOIN mes.tb_md_eqpt e       ON e.plant_cd = lh.plant_cd AND e.eqpt_cd = lh.eqpt_cd
            LEFT JOIN mes.tb_md_item i       ON i.plant_cd = lh.plant_cd AND i.item_cd = lh.item_cd
            WHERE lh.plant_cd  = :plantCd
              AND lh.del_flg   = 'N'
              AND lh.ins_date >= :from
              AND lh.ins_date <  :toExclusive
              $processFilter
            GROUP BY lh.wc_cd, lh.eqpt_cd, lh.item_cd
        """.trimIndent()
        return jdbcTemplate.query(sql, params) { rs, _ ->
            DefectTreeBaseRow(
                wcCd = rs.getString("wc_cd"), wcNm = rs.getString("wc_nm"),
                eqptCd = rs.getString("eqpt_cd"), eqptNm = rs.getString("eqpt_nm"),
                itemCd = rs.getString("item_cd"), itemNm = rs.getString("item_nm"),
                okQty = Rs.qty(rs, "ok_qty") ?: 0L, ngQty = Rs.qty(rs, "ng_qty") ?: 0L
            )
        }
    }

    /**
     * 불량 상세 분해 트리의 **유형 안분행** — (공정, 설비, 제품, 불량 유형) 단위 안분 불량 수량. (QC-01 트리)
     *
     * 안분식은 by-type · by-line 과 같다(`라벨.defect × 유형 qty ÷ 그 라벨 유형 qty 합`, 생산 불량이 아닌 코드 제외).
     * 불량이 있는 라벨만 불량 이력과 붙이므로 30일 0.5초 · 365일 3초다(라벨 7.3M · 불량 이력 9.1M 실측).
     * 반올림하지 않은 값을 돌려준다 — 조립 단계에서 차원을 합친 뒤 한 번만 반올림해야 잔차가 쌓이지 않는다.
     */
    fun findDefectTreeTypes(plantCd: String, from: LocalDate, to: LocalDate, processId: String?): List<DefectTreeTypeRow> {
        val params = periodParams(plantCd, from, to)
        val processFilter = processFilterOf(params, processId)
        val sql = """
            WITH label AS (
                SELECT lh.plant_cd, lh.wc_cd, lh.lot_no, lh.serial_no, lh.eqpt_cd, lh.item_cd,
                       coalesce(lh.defect, 0) AS ng_qty
                FROM mes.tb_pop_label_hist lh
                WHERE lh.plant_cd  = :plantCd
                  AND lh.del_flg   = 'N'
                  AND lh.ins_date >= :from
                  AND lh.ins_date <  :toExclusive
                  AND coalesce(lh.defect, 0) > 0
                  $processFilter
            ),
            type_join AS (
                SELECT l.wc_cd, l.eqpt_cd, l.item_cd, dh.defect_cd, l.ng_qty,
                       sum(dh.qty)                                 AS type_qty,
                       sum(sum(dh.qty)) OVER (
                           PARTITION BY l.plant_cd, l.wc_cd, l.lot_no, l.serial_no
                       )                                           AS label_type_total
                FROM label l
                INNER JOIN mes.tb_pop_defect_hist dh
                        ON dh.plant_cd  = l.plant_cd
                       AND dh.wc_cd     = l.wc_cd
                       AND dh.lot_no    = l.lot_no
                       AND dh.serial_no = l.serial_no
                       ${DefectSql.excludeNonProduction()}
                GROUP BY l.plant_cd, l.wc_cd, l.lot_no, l.serial_no, l.ng_qty, l.eqpt_cd, l.item_cd, dh.defect_cd
            )
            SELECT
                t.wc_cd, t.eqpt_cd, t.item_cd, t.defect_cd,
                coalesce(md.defect_nm, t.defect_cd)                                   AS defect_nm,
                coalesce(sum(t.ng_qty * t.type_qty / nullif(t.label_type_total, 0)), 0) AS ng_qty
            FROM type_join t
            LEFT JOIN mes.tb_md_defect md ON md.plant_cd = :plantCd AND md.defect_cd = t.defect_cd
            GROUP BY t.wc_cd, t.eqpt_cd, t.item_cd, t.defect_cd, md.defect_nm
        """.trimIndent()
        return jdbcTemplate.query(sql, params) { rs, _ ->
            DefectTreeTypeRow(
                wcCd = rs.getString("wc_cd"), eqptCd = rs.getString("eqpt_cd"), itemCd = rs.getString("item_cd"),
                defectCd = rs.getString("defect_cd"), defectNm = rs.getString("defect_nm"),
                ngQty = rs.getBigDecimal("ng_qty") ?: BigDecimal.ZERO
            )
        }
    }

    /** 공정 조건절 — 비어 있으면 전체. 별칭 `lh`. */
    private fun processFilterOf(params: MapSqlParameterSource, processId: String?): String =
        if (processId.isNullOrBlank()) "" else {
            params.addValue("processId", processId.trim())
            "AND lh.wc_cd = :processId"
        }

    /**
     * 시간 단위 불량률 시계열을 조회한다. (No.77 예측 밴드 학습 데이터)
     *
     * @param hours 조회 시간 범위
     */
    fun findHourlyDefectSeries(plantCd: String, processId: String?, hours: Int): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT
                to_char(date_trunc('hour', lh.ins_date), 'MM-DD HH24:MI')  AS label,
                date_trunc('hour', lh.ins_date)                            AS slot_at,
                coalesce(sum(lh.normal), 0) + coalesce(sum(lh.defect), 0)  AS total_qty,
                coalesce(sum(lh.defect), 0)                                AS ng_qty
            FROM mes.tb_pop_label_hist lh
            WHERE lh.plant_cd  = :plantCd
              AND lh.del_flg   = 'N'
              AND lh.ins_date >= now() - make_interval(hours => :hours)
            """.trimIndent()
        )

        val params = MapSqlParameterSource()
            .addValue("plantCd", plantCd)
            .addValue("hours", hours)

        if (!processId.isNullOrBlank()) {
            sql.append(" AND lh.wc_cd = :processId")
            params.addValue("processId", processId.trim())
        }

        sql.append("\nGROUP BY 1, 2\nORDER BY 2")

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "label" to rs.getString("label"),
                "defectRate" to safeRate(rs.getBigDecimal("ng_qty"), rs.getBigDecimal("total_qty")),
                "totalQty" to Rs.qty(rs, "total_qty"),
                "ngQty" to Rs.qty(rs, "ng_qty")
            )
        }
    }

    /**
     * 설비별 최근 불량률과 주요 요인을 조회한다. (No.78 — 설비별 위험 예측 기초 데이터)
     *
     * @param hours 최근 관측 시간 범위
     */
    fun findEquipmentDefectStats(plantCd: String, processId: String?, hours: Int): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT
                lh.eqpt_cd,
                max(e.eqpt_nm)                                            AS eqpt_nm,
                max(lh.mold_cd)                                           AS mold_cd,
                coalesce(sum(lh.normal), 0) + coalesce(sum(lh.defect), 0) AS total_qty,
                coalesce(sum(lh.defect), 0)                               AS ng_qty,
                -- 최근 절반 구간과 이전 절반 구간을 나눠 추세를 판정한다.
                coalesce(sum(lh.defect) FILTER (
                    WHERE lh.ins_date >= now() - make_interval(hours => :halfHours)
                ), 0)                                                     AS recent_ng_qty,
                coalesce(sum(lh.normal + lh.defect) FILTER (
                    WHERE lh.ins_date >= now() - make_interval(hours => :halfHours)
                ), 0)                                                     AS recent_total_qty,
                -- 설비별 주 불량 요인.
                -- 이전에는 이 서브쿼리가 설비와 상관되어 있지 않아 전 설비가 같은 값을 받았다.
                -- 불량 이력에는 eqpt_cd 가 없으므로 라벨 이력의 PK 전체(lot_no + serial_no)로
                -- 조인해 설비를 특정한다. (docs/MES_QUERY_GUIDE.md 2-2)
                (
                    SELECT coalesce(md.defect_nm, dh.defect_cd)
                      FROM mes.tb_pop_defect_hist dh
                      INNER JOIN mes.tb_pop_label_hist lh2
                              ON lh2.plant_cd  = dh.plant_cd
                             AND lh2.wc_cd     = dh.wc_cd
                             AND lh2.lot_no    = dh.lot_no
                             AND lh2.serial_no = dh.serial_no
                      LEFT JOIN mes.tb_md_defect md
                             ON md.plant_cd = dh.plant_cd AND md.defect_cd = dh.defect_cd
                     -- plant_cd 는 외부 쿼리가 GROUP BY 하지 않으므로 파라미터를 쓴다.
                     -- lh.plant_cd 를 참조하면 'subquery uses ungrouped column' 이 난다.
                     WHERE lh2.plant_cd  = :plantCd
                       AND lh2.eqpt_cd   = lh.eqpt_cd
                       AND lh2.del_flg   = 'N'
                       AND lh2.ins_date >= now() - make_interval(hours => :hours)
                       ${DefectSql.excludeNonProduction()}
                     GROUP BY dh.defect_cd, md.defect_nm
                     ORDER BY sum(dh.qty) DESC
                     LIMIT 1
                )                                                          AS main_factor
            FROM mes.tb_pop_label_hist lh
            LEFT JOIN mes.tb_md_eqpt e
                   ON e.plant_cd = lh.plant_cd AND e.eqpt_cd = lh.eqpt_cd
            WHERE lh.plant_cd  = :plantCd
              AND lh.del_flg   = 'N'
              AND lh.eqpt_cd IS NOT NULL
              AND lh.ins_date >= now() - make_interval(hours => :hours)
            """.trimIndent()
        )

        val params = MapSqlParameterSource()
            .addValue("plantCd", plantCd)
            .addValue("hours", hours)
            .addValue("halfHours", (hours / 2).coerceAtLeast(1))

        if (!processId.isNullOrBlank()) {
            sql.append(" AND lh.wc_cd = :processId")
            params.addValue("processId", processId.trim())
        }

        // nullable 집계 정렬 — coalesce 없이 DESC 하면 NULL 이 먼저 온다.
        sql.append("\nGROUP BY lh.eqpt_cd\nORDER BY coalesce(sum(lh.defect), 0) DESC")

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "eqptCd" to rs.getString("eqpt_cd"),
                "eqptNm" to rs.getString("eqpt_nm"),
                "moldCd" to rs.getString("mold_cd"),
                "currentRate" to safeRate(rs.getBigDecimal("recent_ng_qty"), rs.getBigDecimal("recent_total_qty")),
                "baseRate" to safeRate(rs.getBigDecimal("ng_qty"), rs.getBigDecimal("total_qty")),
                "totalQty" to Rs.qty(rs, "total_qty"),
                "ngQty" to Rs.qty(rs, "ng_qty"),
                "mainFactor" to rs.getString("main_factor")
            )
        }
    }

    /**
     * 출하 전 위험 LOT 을 조회한다. (No.79)
     *
     * 최근 생산 LOT 중 불량률이 높은 순으로 반환한다.
     *
     * @param days 조회 일수
     * @param topN 조회 건수
     */
    fun findRiskLots(plantCd: String, days: Int, topN: Int): List<Map<String, Any?>> {
        val sql = """
            SELECT
                lh.lot_no,
                max(lh.item_cd)                                           AS item_cd,
                max(p.model_cd)                                           AS model_cd,
                max(c.customer_nm)                                        AS customer_nm,
                coalesce(sum(lh.normal), 0) + coalesce(sum(lh.defect), 0) AS total_qty,
                coalesce(sum(lh.defect), 0)                               AS ng_qty,
                max(lh.ins_date)                                          AS last_at,
                (
                    SELECT coalesce(md.defect_nm, dh.defect_cd)
                      FROM mes.tb_pop_defect_hist dh
                      LEFT JOIN mes.tb_md_defect md
                             ON md.plant_cd = dh.plant_cd AND md.defect_cd = dh.defect_cd
                     WHERE dh.plant_cd = lh.plant_cd
                       AND dh.lot_no   = lh.lot_no
                       ${DefectSql.excludeNonProduction()}
                     GROUP BY dh.defect_cd, md.defect_nm
                     ORDER BY sum(dh.qty) DESC
                     LIMIT 1
                )                                                          AS main_defect
            FROM mes.tb_pop_label_hist lh
            LEFT JOIN ax.tb_prod_item_map pm
                   ON pm.plant_cd = lh.plant_cd AND pm.item_cd = lh.item_cd
            LEFT JOIN ax.tb_prod_product  p ON p.product_id  = pm.product_id
            LEFT JOIN ax.tb_prod_customer c ON c.customer_id = p.customer_id
            WHERE lh.plant_cd  = :plantCd
              AND lh.del_flg   = 'N'
              AND lh.stock_flg = 'Y'
              AND lh.ins_date >= now() - make_interval(days => :days)
            GROUP BY lh.plant_cd, lh.lot_no
            HAVING coalesce(sum(lh.normal), 0) + coalesce(sum(lh.defect), 0) > 0
            ORDER BY
                coalesce(
                    coalesce(sum(lh.defect), 0)::numeric
                    / nullif(coalesce(sum(lh.normal), 0) + coalesce(sum(lh.defect), 0), 0),
                    0
                ) DESC
            LIMIT :topN
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("plantCd", plantCd)
            .addValue("days", days)
            .addValue("topN", topN)

        return jdbcTemplate.query(sql, params) { rs, _ ->
            mapOf(
                "lotNo" to rs.getString("lot_no"),
                "itemCd" to rs.getString("item_cd"),
                "model" to rs.getString("model_cd"),
                "customer" to rs.getString("customer_nm"),
                "qty" to Rs.qty(rs, "total_qty"),
                "ngQty" to Rs.qty(rs, "ng_qty"),
                "defectRate" to safeRate(rs.getBigDecimal("ng_qty"), rs.getBigDecimal("total_qty")),
                "mainDefect" to rs.getString("main_defect"),
                "lastAt" to Rs.dateTime(rs, "last_at")
            )
        }
    }

    /**
     * AOI 검사기별 판정 통계를 조회한다. (No.81 — 판정 드리프트)
     *
     * AOI 설비는 설비 마스터의 모델명으로 식별한다.
     */
    fun findAoiJudgeStats(plantCd: String, from: LocalDate, to: LocalDate): List<Map<String, Any?>> {
        val sql = """
            SELECT
                lh.eqpt_cd                                                 AS aoi_cd,
                max(e.eqpt_nm)                                             AS aoi_nm,
                count(*)                                                   AS judge_cnt,
                coalesce(sum(lh.defect), 0)                                AS ng_qty,
                coalesce(sum(lh.normal), 0) + coalesce(sum(lh.defect), 0)  AS total_qty,
                coalesce(sum(lh.sample), 0)                                AS sample_qty,
                -- 조회 기간 전반부 불량률과 후반부 불량률을 비교해 드리프트를 산출한다.
                coalesce(sum(lh.defect) FILTER (WHERE lh.ins_date <  :midPoint), 0)          AS first_ng,
                coalesce(sum(lh.normal + lh.defect) FILTER (WHERE lh.ins_date <  :midPoint), 0) AS first_total,
                coalesce(sum(lh.defect) FILTER (WHERE lh.ins_date >= :midPoint), 0)          AS second_ng,
                coalesce(sum(lh.normal + lh.defect) FILTER (WHERE lh.ins_date >= :midPoint), 0) AS second_total
            FROM mes.tb_pop_label_hist lh
            INNER JOIN mes.tb_md_eqpt e
                    ON e.plant_cd = lh.plant_cd AND e.eqpt_cd = lh.eqpt_cd
            WHERE lh.plant_cd  = :plantCd
              AND lh.del_flg   = 'N'
              AND lh.ins_date >= :from
              AND lh.ins_date <  :toExclusive
              AND (e.model_nm ILIKE '%AOI%' OR e.eqpt_nm ILIKE '%AOI%')
            GROUP BY lh.eqpt_cd
            ORDER BY lh.eqpt_cd
        """.trimIndent()

        val params = periodParams(plantCd, from, to)
        params.addValue("midPoint", from.plusDays(java.time.temporal.ChronoUnit.DAYS.between(from, to) / 2).atStartOfDay())

        return jdbcTemplate.query(sql, params) { rs, _ ->
            val firstRate = safeRate(rs.getBigDecimal("first_ng"), rs.getBigDecimal("first_total"))
            val secondRate = safeRate(rs.getBigDecimal("second_ng"), rs.getBigDecimal("second_total"))
            mapOf(
                "aoiCd" to rs.getString("aoi_cd"),
                "aoiNm" to rs.getString("aoi_nm"),
                "judgeCnt" to rs.getLong("judge_cnt"),
                "totalQty" to Rs.qty(rs, "total_qty"),
                "ngQty" to Rs.qty(rs, "ng_qty"),
                "sampleQty" to Rs.qty(rs, "sample_qty"),
                "firstRate" to firstRate,
                "secondRate" to secondRate,
                // 드리프트 = 후반부 불량률 - 전반부 불량률 (판정 기준 이동량)
                "drift" to Math.round((secondRate - firstRate) * 100) / 100.0
            )
        }
    }

    /**
     * 불량 유형 구성 변화를 조회한다. (No.82)
     *
     * 기준일 구성비와 직전 N주 평균 구성비를 비교한다.
     *
     * @param baseWeeks 비교 기준 주 수
     */
    fun findDefectTypeShift(plantCd: String, date: LocalDate, baseWeeks: Int): List<Map<String, Any?>> {
        val sql = """
            WITH today AS (
                SELECT dh.defect_cd, coalesce(sum(dh.qty), 0) AS ng_qty
                FROM mes.tb_pop_defect_hist dh
                WHERE dh.plant_cd  = :plantCd
                  AND dh.ins_date >= :dayStart
                  AND dh.ins_date <  :dayEnd
                  ${DefectSql.excludeNonProduction()}
                GROUP BY dh.defect_cd
            ),
            base AS (
                SELECT
                    dh.defect_cd,
                    coalesce(sum(dh.qty), 0) / :baseWeeks::numeric / 7 AS avg_daily_qty
                FROM mes.tb_pop_defect_hist dh
                WHERE dh.plant_cd  = :plantCd
                  AND dh.ins_date >= :baseFrom
                  AND dh.ins_date <  :dayStart
                  ${DefectSql.excludeNonProduction()}
                GROUP BY dh.defect_cd
            )
            SELECT
                coalesce(today.defect_cd, base.defect_cd)                                    AS defect_cd,
                coalesce(md.defect_nm, coalesce(today.defect_cd, base.defect_cd))            AS defect_nm,
                coalesce(today.ng_qty, 0)                                                    AS today_qty,
                round(coalesce(base.avg_daily_qty, 0), 2)                                    AS base_avg_qty
            FROM today
            FULL OUTER JOIN base ON base.defect_cd = today.defect_cd
            LEFT JOIN mes.tb_md_defect md
                   ON md.plant_cd = :plantCd AND md.defect_cd = coalesce(today.defect_cd, base.defect_cd)
            ORDER BY coalesce(today.ng_qty, 0) DESC
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("plantCd", plantCd)
            .addValue("dayStart", date.atStartOfDay())
            .addValue("dayEnd", date.plusDays(1).atStartOfDay())
            .addValue("baseFrom", date.minusWeeks(baseWeeks.toLong()).atStartOfDay())
            .addValue("baseWeeks", baseWeeks)

        return jdbcTemplate.query(sql, params) { rs, _ ->
            val today = rs.getBigDecimal("today_qty")?.toDouble() ?: 0.0
            val baseAvg = rs.getBigDecimal("base_avg_qty")?.toDouble() ?: 0.0
            val change = if (baseAvg > 0.0) Math.round((today - baseAvg) / baseAvg * 10000) / 100.0 else null

            mapOf(
                "defectCd" to rs.getString("defect_cd"),
                "defectType" to rs.getString("defect_nm"),
                "today" to today,
                "baseAvg" to baseAvg,
                "change" to change,
                "interpretation" to when {
                    change == null -> "비교 기준 데이터 없음"
                    change >= 50.0 -> "기준 대비 급증 — 원인 점검 필요"
                    change >= 20.0 -> "기준 대비 증가"
                    change <= -20.0 -> "기준 대비 감소"
                    else -> "기준 수준 유지"
                }
            )
        }
    }

    /**
     * AI 모델 설정 값을 조회한다. (예측 파라미터 · 판정 기준)
     *
     * @param categoryCd AI_CONFIG_CAT — ANOMALY / CLASSIFY / SECURITY
     */
    fun findModelConfigs(categoryCd: String?): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT
                mc.config_id, mc.category_cd, mc.config_key, mc.config_nm, mc.config_value,
                mc.value_type_cd, mc.unit, mc.opt_values, mc.description, mc.use_flg,
                a.agent_no, a.agent_nm, mc.upd_date, u.user_nm AS upd_user_nm
            FROM ax.tb_ai_model_config mc
            LEFT JOIN ax.tb_ai_agent a ON a.agent_id = mc.agent_id
            LEFT JOIN ax.tb_sys_user u ON u.user_id  = mc.upd_user
            WHERE mc.use_flg = 'Y'
            """.trimIndent()
        )

        val params = MapSqlParameterSource()
        if (!categoryCd.isNullOrBlank()) {
            sql.append(" AND mc.category_cd = :categoryCd")
            params.addValue("categoryCd", categoryCd.trim())
        }
        sql.append("\nORDER BY mc.category_cd, mc.config_key")

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "configId" to rs.getInt("config_id"),
                "category" to rs.getString("category_cd"),
                "key" to rs.getString("config_key"),
                "name" to rs.getString("config_nm"),
                "value" to rs.getString("config_value"),
                "valueType" to rs.getString("value_type_cd"),
                "unit" to rs.getString("unit"),
                "options" to rs.getString("opt_values"),
                "description" to rs.getString("description"),
                "agentCd" to rs.getString("agent_no"),
                "agentNm" to rs.getString("agent_nm"),
                "updatedAt" to Rs.dateTime(rs, "upd_date"),
                "updatedBy" to rs.getString("upd_user_nm")
            )
        }
    }

    /**
     * AI 모델 설정 값을 저장한다. (No.192)
     *
     * @return 갱신 건수
     */
    fun updateModelConfig(categoryCd: String, configKey: String, configValue: String, actor: String): Int {
        val sql = """
            UPDATE ax.tb_ai_model_config
               SET config_value = :configValue,
                   upd_date     = now(),
                   upd_user     = :actor
             WHERE category_cd = :categoryCd
               AND config_key  = :configKey
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("categoryCd", categoryCd)
            .addValue("configKey", configKey)
            .addValue("configValue", configValue.take(300))
            .addValue("actor", actor)

        return jdbcTemplate.update(sql, params)
    }

    /**
     * Agent 실행 이력을 등록한다. (No.83 — 예측 재산출 작업 기록)
     *
     * @return 생성된 실행 ID
     */
    fun insertAgentRun(agentNo: String, stateCd: String, message: String?, throughput: String?): Long {
        val sql = """
            INSERT INTO ax.tb_ai_agent_run (agent_id, run_at, state_cd, throughput_txt, message, err_flg)
            SELECT a.agent_id, now(), :stateCd, :throughput, :message, 'N'
            FROM ax.tb_ai_agent a
            WHERE a.agent_no = :agentNo
            RETURNING run_id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("agentNo", agentNo)
            .addValue("stateCd", stateCd)
            .addValue("throughput", throughput?.take(50))
            .addValue("message", message?.take(500))

        return jdbcTemplate.query(sql, params) { rs, _ -> rs.getLong("run_id") }.firstOrNull() ?: 0L
    }

    /** 조회 기간 공통 파라미터 */
    /**
     * 전기 대비 증감률 = (당기 - 전기) / 전기 × 100
     *
     * 전기 실적이 없거나 0 이면 증감을 낼 수 없으므로 null 을 준다.
     */
    private fun momChangeOf(current: BigDecimal?, previous: BigDecimal?): Double? {
        if (current == null || previous == null || previous.toDouble() <= 0.0) return null
        return Math.round((current.toDouble() - previous.toDouble()) / previous.toDouble() * 10000) / 100.0
    }

    private fun periodParams(plantCd: String, from: LocalDate, to: LocalDate): MapSqlParameterSource =
        MapSqlParameterSource()
            .addValue("plantCd", plantCd)
            .addValue("from", from.atStartOfDay())
            .addValue("toExclusive", to.plusDays(1).atStartOfDay())
}

/**
 * [QualityRepository.findDefectByLine] 의 평면 행 — 설비 × 불량 유형 한 줄.
 * 유형이 하나도 없는 설비는 `defectCd`/`typeNgQty` 가 null 인 한 줄로 온다.
 */
data class DefectByLineRow(
    val eqptCd: String,
    val eqptNm: String?,
    val model: String?,
    val ngQty: Long,
    val okQty: Long,
    val totalQty: Long,
    val defectCd: String?,
    val defectNm: String?,
    val typeNgQty: Long?
)

/**
 * 설비 × 유형 평면 행을 `설비 → children[유형]` 트리로 조립한다.
 *
 * - 설비 순서는 입력 순서(불량 수량 내림차순)를 지킨다.
 * - `children` 은 안분 수량 내림차순. `ratio` 는 그 설비 불량 합(`ngQty`) 대비 %.
 * - 유형 합이 설비 불량 수량에 못 미치면 차액을 [DefectSql.UNTYPED_LABEL] 행으로 덧붙여
 *   `children.ngQty 합 = ngQty`, `ratio 합 = 100%` 가 응답 안에서 성립하게 한다.
 *   반올림 뒤 표시값 기준으로 차액을 잡는다(by-type 와 같은 방식).
 * - `mainType` 은 첫 자식(가장 큰 유형)의 이름. 유형이 없는 설비는 null 이다 — '유형 미상' 을 주 유형으로
 *   올리지 않는다(그건 유형이 아니라 유형이 없다는 뜻이다).
 */
internal fun assembleDefectByLine(rows: List<DefectByLineRow>): List<Map<String, Any?>> {
    val byEqpt = LinkedHashMap<String, MutableList<DefectByLineRow>>()
    rows.forEach { byEqpt.getOrPut(it.eqptCd) { mutableListOf() }.add(it) }

    return byEqpt.values.map { group ->
        val head = group.first()
        val ngQty = BigDecimal.valueOf(head.ngQty)

        val typed = group.filter { it.defectCd != null }
            .sortedWith(compareByDescending<DefectByLineRow> { it.typeNgQty ?: 0L }.thenBy { it.defectCd })
            .map {
                val qty = it.typeNgQty ?: 0L
                mapOf<String, Any?>(
                    "defectCd" to it.defectCd,
                    "defectType" to (it.defectNm ?: it.defectCd),
                    "ngQty" to qty,
                    "ratio" to safeRate(BigDecimal.valueOf(qty), ngQty)
                )
            }

        val untyped = head.ngQty - typed.sumOf { (it["ngQty"] as Long) }
        val children = if (untyped > 0L) typed + mapOf<String, Any?>(
            // 실제 불량코드가 아니므로 코드는 비운다. 화면이 일반 유형과 구분해 그릴 수 있다.
            "defectCd" to null,
            "defectType" to DefectSql.UNTYPED_LABEL,
            "ngQty" to untyped,
            "ratio" to safeRate(BigDecimal.valueOf(untyped), ngQty)
        ) else typed

        mapOf(
            "eqptCd" to head.eqptCd,
            "eqptNm" to head.eqptNm,
            "model" to head.model,
            "ngQty" to head.ngQty,
            "okQty" to head.okQty,
            "totalQty" to head.totalQty,
            "defectRate" to safeRate(ngQty, BigDecimal.valueOf(head.totalQty)),
            "mainType" to typed.firstOrNull()?.get("defectType"),
            "children" to children
        )
    }
}

/** [QualityRepository.findDefectTreeBase] 의 한 행 — (공정, 설비, 제품) 정상·불량 수량. 설비가 없는 라벨은 `eqptCd = null`. */
data class DefectTreeBaseRow(
    val wcCd: String,
    val wcNm: String?,
    val eqptCd: String?,
    val eqptNm: String?,
    val itemCd: String,
    val itemNm: String?,
    val okQty: Long,
    val ngQty: Long
)

/** [QualityRepository.findDefectTreeTypes] 의 한 행 — (공정, 설비, 제품, 유형) 안분 불량 수량(반올림 전). */
data class DefectTreeTypeRow(
    val wcCd: String,
    val eqptCd: String?,
    val itemCd: String,
    val defectCd: String,
    val defectNm: String?,
    val ngQty: BigDecimal
)
