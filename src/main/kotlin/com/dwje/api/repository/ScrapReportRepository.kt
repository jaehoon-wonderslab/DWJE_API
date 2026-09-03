package com.dwje.api.repository

import com.dwje.api.common.util.Rs
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDate

/**
 * 폐기 보고서 Repository (RP-06, RP-07)
 *
 * 참조 테이블 : mes.tb_pop_stock_hist(폐기 전표) · mes.tb_pop_defect_hist ·
 *              ax.tb_rpt_scrap_row · ax.tb_prod_item_price(단가) · ax.tb_rpt_doc
 */
@Repository
class ScrapReportRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) {

    /**
     * MES 폐기 전표를 조회한다. (No.115 — 위저드 1단계)
     *
     * 재고 이동 이력에서 폐기(SCRAP) 유형 전표를 추출하고 불량 정보를 결합한다.
     *
     * @param originType 발생 구분 (제조공정/협력업체/IQC)
     */
    fun findMesVouchers(
        plantCd: String,
        from: LocalDate,
        to: LocalDate,
        processId: String?,
        modelCd: String?,
        defectTypeCd: String?,
        originType: String?,
        limit: Int,
        offset: Int
    ): List<Map<String, Any?>> {
        val sql = StringBuilder(baseVoucherSql())
        val params = voucherParams(plantCd, from, to, processId, modelCd, defectTypeCd, originType)

        sql.append("\nORDER BY sh.hist_date DESC, sh.lot_no\nLIMIT :limit OFFSET :offset")
        params.addValue("limit", limit).addValue("offset", offset)

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "voucherId" to rs.getString("voucher_id"),
                "occurDate" to Rs.dateTime(rs, "hist_date"),
                "lotNo" to rs.getString("lot_no"),
                "serialNo" to rs.getString("serial_no"),
                "itemCd" to rs.getString("item_cd"),
                "model" to rs.getString("model_cd"),
                "processId" to rs.getString("wc_cd"),
                "process" to rs.getString("wc_nm"),
                "defectCd" to rs.getString("defect_cd"),
                "defectType" to rs.getString("defect_nm"),
                "qty" to Rs.qty(rs, "qty"),
                "originType" to rs.getString("origin_type"),
                "docNo" to rs.getString("doc_no")
            )
        }
    }

    /** MES 폐기 전표 전체 건수 */
    fun countMesVouchers(
        plantCd: String,
        from: LocalDate,
        to: LocalDate,
        processId: String?,
        modelCd: String?,
        defectTypeCd: String?,
        originType: String?
    ): Long {
        val sql = "SELECT count(*) FROM (\n${baseVoucherSql()}\n) t"
        val params = voucherParams(plantCd, from, to, processId, modelCd, defectTypeCd, originType)
        return jdbcTemplate.queryForObject(sql, params, Long::class.java) ?: 0L
    }

    /**
     * 폐기 전표 기본 SQL
     *
     * 재고 이력의 폐기 유형 전표에 동일 LOT 의 불량 이력을 결합해 폐기 사유를 확보한다.
     * 이미 보고서에 편입된 전표는 doc_no 로 표시한다.
     */
    private fun baseVoucherSql(): String = """
        SELECT
            sh.plant_cd || '-' || sh.lot_no || '-' || sh.serial_no || '-' ||
                to_char(sh.hist_date, 'YYYYMMDD')                       AS voucher_id,
            sh.hist_date,
            sh.lot_no,
            sh.serial_no,
            sh.item_cd,
            p.model_cd,
            sh.wc_cd,
            w.wc_nm,
            d.defect_cd,
            md.defect_nm,
            sh.qty,
            CASE
                WHEN sh.remark ILIKE '%협력%' THEN '협력업체 발생'
                WHEN sh.remark ILIKE '%IQC%'  THEN 'IQC 발생'
                ELSE '제조공정 발생'
            END                                                          AS origin_type,
            (
                SELECT r.doc_id::text
                  FROM ax.tb_rpt_scrap_row r
                 WHERE r.lot_no = sh.lot_no AND r.item_cd = sh.item_cd
                 LIMIT 1
            )                                                            AS doc_no
        FROM mes.tb_pop_stock_hist sh
        LEFT JOIN mes.tb_md_workcenter w
               ON w.plant_cd = sh.plant_cd AND w.wc_cd = sh.wc_cd
        LEFT JOIN ax.tb_prod_item_map pm
               ON pm.plant_cd = sh.plant_cd AND pm.item_cd = sh.item_cd
        LEFT JOIN ax.tb_prod_product p ON p.product_id = pm.product_id
        LEFT JOIN LATERAL (
            SELECT dh.defect_cd
            FROM mes.tb_pop_defect_hist dh
            WHERE dh.plant_cd = sh.plant_cd
              AND dh.wc_cd    = sh.wc_cd
              AND dh.lot_no   = sh.lot_no
              AND dh.serial_no = sh.serial_no
            ORDER BY dh.qty DESC
            LIMIT 1
        ) d ON true
        LEFT JOIN mes.tb_md_defect md
               ON md.plant_cd = sh.plant_cd AND md.defect_cd = d.defect_cd
        WHERE sh.plant_cd   = :plantCd
          AND sh.hist_type  = 'SCRAP'
          AND sh.hist_date >= :from
          AND sh.hist_date <= :to
          AND (:processId::varchar    IS NULL OR sh.wc_cd     = :processId)
          AND (:modelCd::varchar      IS NULL OR p.model_cd   = :modelCd)
          AND (:defectTypeCd::varchar IS NULL OR d.defect_cd  = :defectTypeCd)
          AND (:originType::varchar   IS NULL OR
               CASE
                   WHEN sh.remark ILIKE '%협력%' THEN '협력업체 발생'
                   WHEN sh.remark ILIKE '%IQC%'  THEN 'IQC 발생'
                   ELSE '제조공정 발생'
               END = :originType)
    """.trimIndent()

    /** 폐기 전표 조회 공통 파라미터 */
    private fun voucherParams(
        plantCd: String,
        from: LocalDate,
        to: LocalDate,
        processId: String?,
        modelCd: String?,
        defectTypeCd: String?,
        originType: String?
    ): MapSqlParameterSource = MapSqlParameterSource()
        .addValue("plantCd", plantCd)
        .addValue("from", from)
        .addValue("to", to)
        .addValue("processId", processId?.trim()?.takeIf { it.isNotBlank() })
        .addValue("modelCd", modelCd?.trim()?.takeIf { it.isNotBlank() })
        .addValue("defectTypeCd", defectTypeCd?.trim()?.takeIf { it.isNotBlank() })
        .addValue("originType", originType?.trim()?.takeIf { it.isNotBlank() })

    /**
     * 폐기 상세 행을 등록한다. (전표 선택분 · 수기 추가분 공통)
     *
     * @return 생성된 행 ID
     */
    fun insertRow(
        docId: Long,
        voucherId: String?,
        occurDate: LocalDate?,
        lotNo: String?,
        itemCd: String?,
        modelCd: String?,
        wcCd: String?,
        defectCd: String?,
        reasonTxt: String?,
        kindCd: String,
        originTypeCd: String,
        qty: BigDecimal,
        isManual: Boolean,
        actor: String
    ): Long {
        val sql = """
            INSERT INTO ax.tb_rpt_scrap_row (
                doc_id, voucher_id, occur_date, lot_no, item_cd, model_cd, wc_cd,
                defect_cd, reason_txt, kind_cd, origin_type_cd, qty, is_manual, ins_user
            ) VALUES (
                :docId, :voucherId, :occurDate, :lotNo, :itemCd, :modelCd, :wcCd,
                :defectCd, :reasonTxt, :kindCd, :originTypeCd, :qty, :isManual, :actor
            )
            RETURNING row_id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("docId", docId)
            .addValue("voucherId", voucherId?.take(50))
            .addValue("occurDate", occurDate)
            .addValue("lotNo", lotNo)
            .addValue("itemCd", itemCd)
            .addValue("modelCd", modelCd?.take(50))
            .addValue("wcCd", wcCd)
            .addValue("defectCd", defectCd)
            .addValue("reasonTxt", reasonTxt?.take(200))
            .addValue("kindCd", kindCd)
            .addValue("originTypeCd", originTypeCd)
            .addValue("qty", qty)
            .addValue("isManual", isManual)
            .addValue("actor", actor)

        return jdbcTemplate.queryForObject(sql, params, Long::class.java) ?: 0L
    }

    /** 문서의 폐기 행을 모두 삭제한다. (전표 재선택 시) */
    fun deleteRowsByDoc(docId: Long, manualOnly: Boolean): Int {
        val sql = StringBuilder("DELETE FROM ax.tb_rpt_scrap_row WHERE doc_id = :docId")
        if (manualOnly) sql.append(" AND is_manual = true") else sql.append(" AND is_manual = false")
        return jdbcTemplate.update(sql.toString(), MapSqlParameterSource("docId", docId))
    }

    /** 수기 폐기 행을 삭제한다. (No.119) */
    fun deleteRow(docId: Long, rowId: Long): Int {
        val sql = "DELETE FROM ax.tb_rpt_scrap_row WHERE doc_id = :docId AND row_id = :rowId AND is_manual = true"
        val params = MapSqlParameterSource().addValue("docId", docId).addValue("rowId", rowId)
        return jdbcTemplate.update(sql, params)
    }

    /**
     * 문서의 폐기 상세 행을 조회한다.
     */
    fun findRows(docId: Long): List<Map<String, Any?>> {
        val sql = """
            SELECT
                r.row_id, r.voucher_id, r.occur_date, r.lot_no, r.item_cd, r.model_cd,
                r.wc_cd, w.wc_nm, r.defect_cd, md.defect_nm, r.reason_txt,
                r.kind_cd, r.origin_type_cd, r.qty, r.unit_price, r.price_source_cd,
                r.amount, r.is_manual, r.remark
            FROM ax.tb_rpt_scrap_row r
            LEFT JOIN mes.tb_md_workcenter w ON w.wc_cd = r.wc_cd
            LEFT JOIN mes.tb_md_defect     md ON md.defect_cd = r.defect_cd
            WHERE r.doc_id = :docId
            ORDER BY r.row_id
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource("docId", docId)) { rs, _ ->
            mapOf(
                "rowId" to rs.getLong("row_id"),
                "voucherId" to rs.getString("voucher_id"),
                "occurDate" to Rs.dateTime(rs, "occur_date"),
                "lotNo" to rs.getString("lot_no"),
                "itemCd" to rs.getString("item_cd"),
                "model" to rs.getString("model_cd"),
                "processId" to rs.getString("wc_cd"),
                "process" to (rs.getString("wc_nm") ?: rs.getString("wc_cd")),
                "defectCd" to rs.getString("defect_cd"),
                "reason" to (rs.getString("reason_txt") ?: rs.getString("defect_nm")),
                "kind" to rs.getString("kind_cd"),
                "originType" to rs.getString("origin_type_cd"),
                "qty" to Rs.qty(rs, "qty"),
                "unitPrice" to Rs.rate(rs, "unit_price", 2),
                "priceSource" to rs.getString("price_source_cd"),
                "amount" to Rs.rate(rs, "amount", 0),
                "manual" to rs.getBoolean("is_manual"),
                "remark" to rs.getString("remark")
            )
        }
    }

    /**
     * 원가 기준정보 단가를 적용해 금액을 산정한다. (No.120 — 위저드 3단계)
     *
     * 단가는 품목별 유효 시작일이 가장 최근인 원가(COST) 단가를 사용한다.
     * 이미 수기 조정된 행(price_source_cd='MANUAL')은 단가를 덮어쓰지 않는다.
     *
     * @param defaultUnitPrice 단가 기준정보가 없을 때 사용할 기본 단가
     * @return 갱신된 행 수
     */
    fun applyUnitPrices(docId: Long, plantCd: String, defaultUnitPrice: BigDecimal): Int {
        // 품목별 유효 단가를 상관 서브쿼리로 조회해 단가와 금액을 함께 갱신한다.
        val sql = """
            UPDATE ax.tb_rpt_scrap_row r
               SET unit_price = coalesce((
                       SELECT ip.unit_price
                         FROM ax.tb_prod_item_price ip
                        WHERE ip.plant_cd      = :plantCd
                          AND ip.item_cd       = r.item_cd
                          AND ip.price_kind_cd = 'COST'
                          AND ip.valid_from   <= coalesce(r.occur_date, current_date)
                        ORDER BY ip.valid_from DESC
                        LIMIT 1
                   ), :defaultUnitPrice),
                   price_source_cd = CASE
                       WHEN EXISTS (
                           SELECT 1 FROM ax.tb_prod_item_price ip
                            WHERE ip.plant_cd = :plantCd AND ip.item_cd = r.item_cd
                              AND ip.price_kind_cd = 'COST'
                       ) THEN 'MASTER' ELSE 'MASTER' END,
                   amount = r.qty * coalesce((
                       SELECT ip.unit_price
                         FROM ax.tb_prod_item_price ip
                        WHERE ip.plant_cd      = :plantCd
                          AND ip.item_cd       = r.item_cd
                          AND ip.price_kind_cd = 'COST'
                          AND ip.valid_from   <= coalesce(r.occur_date, current_date)
                        ORDER BY ip.valid_from DESC
                        LIMIT 1
                   ), :defaultUnitPrice)
             WHERE r.doc_id = :docId
               AND coalesce(r.price_source_cd, 'MASTER') <> 'MANUAL'
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("docId", docId)
            .addValue("plantCd", plantCd)
            .addValue("defaultUnitPrice", defaultUnitPrice)

        return jdbcTemplate.update(sql, params)
    }

    /**
     * 단가를 수기 조정한다. (No.121)
     *
     * @param keyType  조정 기준 — model | process
     * @param keyValue 기준 값
     * @return 갱신된 행 수
     */
    fun updateUnitPriceManually(
        docId: Long,
        keyType: String,
        keyValue: String,
        unitPrice: BigDecimal
    ): Int {
        // 기준 컬럼은 화이트리스트로만 결정한다. (SQL Injection 방지)
        val column = when (keyType.lowercase()) {
            "model" -> "model_cd"
            "process" -> "wc_cd"
            else -> throw IllegalArgumentException("단가 조정 기준은 model 또는 process 만 허용합니다.")
        }

        val sql = """
            UPDATE ax.tb_rpt_scrap_row
               SET unit_price      = :unitPrice,
                   price_source_cd = 'MANUAL',
                   amount          = qty * :unitPrice
             WHERE doc_id = :docId
               AND $column = :keyValue
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("docId", docId)
            .addValue("keyValue", keyValue)
            .addValue("unitPrice", unitPrice)

        return jdbcTemplate.update(sql, params)
    }

    /**
     * 폐기 집계 요약을 산출한다. (No.114 / No.120 — summary)
     */
    fun findSummary(docId: Long): Map<String, Any?> {
        val sql = """
            SELECT
                coalesce(sum(qty), 0)                                            AS total_qty,
                coalesce(sum(qty) FILTER (WHERE kind_cd = 'LOSS'), 0)            AS loss_qty,
                coalesce(sum(qty) FILTER (WHERE kind_cd = 'DEAD_STOCK'), 0)      AS dead_stock_qty,
                coalesce(sum(qty) FILTER (WHERE defect_cd IS NOT NULL), 0)       AS ng_qty,
                coalesce(sum(amount), 0)                                         AS total_amt,
                count(*)                                                         AS row_cnt
            FROM ax.tb_rpt_scrap_row
            WHERE doc_id = :docId
        """.trimIndent()

        return jdbcTemplate.queryForObject(sql, MapSqlParameterSource("docId", docId)) { rs, _ ->
            mapOf(
                "totalQty" to Rs.qty(rs, "total_qty"),
                "lossQty" to Rs.qty(rs, "loss_qty"),
                "deadStockQty" to Rs.qty(rs, "dead_stock_qty"),
                "ngQty" to Rs.qty(rs, "ng_qty"),
                "totalAmt" to Rs.rate(rs, "total_amt", 0),
                "rowCnt" to rs.getLong("row_cnt")
            )
        } ?: emptyMap()
    }

    /**
     * 폐기 보고서 목록을 조회한다. (No.113)
     */
    fun findScrapDocs(
        from: LocalDate,
        to: LocalDate,
        originType: String?,
        limit: Int,
        offset: Int
    ): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT
                d.doc_id, d.doc_no, d.title, d.state_cd, d.period_from, d.period_to,
                d.generated_at, d.confirmed_at,
                coalesce(s.total_qty, 0) AS total_qty,
                coalesce(s.total_amt, 0) AS total_amt
            FROM ax.tb_rpt_doc d
            LEFT JOIN LATERAL (
                SELECT sum(r.qty) AS total_qty, sum(r.amount) AS total_amt
                FROM ax.tb_rpt_scrap_row r
                WHERE r.doc_id = d.doc_id
            ) s ON true
            WHERE d.doc_kind_cd = 'SCRAP'
              AND d.del_flg     = 'N'
              AND coalesce(d.period_from::date, d.generated_at::date) >= :from
              AND coalesce(d.period_to::date,   d.generated_at::date) <= :to
            """.trimIndent()
        )

        val params = MapSqlParameterSource()
            .addValue("from", from)
            .addValue("to", to)

        if (!originType.isNullOrBlank()) {
            sql.append(
                """

                AND EXISTS (
                    SELECT 1 FROM ax.tb_rpt_scrap_row r
                     WHERE r.doc_id = d.doc_id AND r.origin_type_cd = :originType
                )
                """.trimIndent()
            )
            params.addValue("originType", originType.trim())
        }

        sql.append("\nORDER BY d.generated_at DESC\nLIMIT :limit OFFSET :offset")
        params.addValue("limit", limit).addValue("offset", offset)

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "docId" to rs.getLong("doc_id"),
                "docNo" to rs.getString("doc_no"),
                "title" to rs.getString("title"),
                "state" to rs.getString("state_cd"),
                "periodFrom" to Rs.dateTime(rs, "period_from"),
                "periodTo" to Rs.dateTime(rs, "period_to"),
                "generatedAt" to Rs.dateTime(rs, "generated_at"),
                "confirmedAt" to Rs.dateTime(rs, "confirmed_at"),
                "totalQty" to Rs.qty(rs, "total_qty"),
                "totalAmt" to Rs.rate(rs, "total_amt", 0)
            )
        }
    }

    /** 폐기 보고서 목록 전체 건수 */
    fun countScrapDocs(from: LocalDate, to: LocalDate, originType: String?): Long {
        val sql = StringBuilder(
            """
            SELECT count(*)
            FROM ax.tb_rpt_doc d
            WHERE d.doc_kind_cd = 'SCRAP'
              AND d.del_flg     = 'N'
              AND coalesce(d.period_from::date, d.generated_at::date) >= :from
              AND coalesce(d.period_to::date,   d.generated_at::date) <= :to
            """.trimIndent()
        )

        val params = MapSqlParameterSource().addValue("from", from).addValue("to", to)

        if (!originType.isNullOrBlank()) {
            sql.append(
                """

                AND EXISTS (
                    SELECT 1 FROM ax.tb_rpt_scrap_row r
                     WHERE r.doc_id = d.doc_id AND r.origin_type_cd = :originType
                )
                """.trimIndent()
            )
            params.addValue("originType", originType.trim())
        }

        return jdbcTemplate.queryForObject(sql.toString(), params, Long::class.java) ?: 0L
    }

    /**
     * 다음 폐기 보고서 문서번호를 채번한다. (SCRAP-YYYYMM-NNN)
     */
    fun nextDocNo(yearMonth: String): String {
        val sql = """
            SELECT coalesce(max(substring(doc_no from '[0-9]+$')::int), 0) + 1 AS next_seq
            FROM ax.tb_rpt_doc
            WHERE doc_kind_cd = 'SCRAP'
              AND doc_no LIKE :prefix || '%'
        """.trimIndent()

        val prefix = "SCRAP-$yearMonth-"
        val seq = jdbcTemplate.queryForObject(sql, MapSqlParameterSource("prefix", prefix), Int::class.java) ?: 1
        return "$prefix${seq.toString().padStart(3, '0')}"
    }
}
