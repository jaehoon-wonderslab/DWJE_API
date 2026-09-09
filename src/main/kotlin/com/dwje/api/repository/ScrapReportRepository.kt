package com.dwje.api.repository

import com.dwje.api.common.util.Rs
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.LocalDate

/**
 * 폐기 전표 조회 Repository (RP-06)
 *
 * ## 문서 관리 제거 후 남은 것 (2026-09-04)
 * 폐기 보고서 문서(초안·상세 표·단가 적용·결재선·발행)는 `ax.tb_rpt_doc` /
 * `ax.tb_rpt_scrap_row` 와 함께 사라졌다. 남는 것은 **MES 폐기 전표 조회**뿐이다 —
 * 문서가 아니라 MES 실적을 그대로 읽는 조회다.
 *
 * 참조 테이블 : mes.tb_pop_stock_hist(폐기 전표) · mes.tb_pop_defect_hist
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
                // 폐기 수량 — 양수. 원천은 재고 감소라 음수로 들어 있다.
                "qty" to Rs.qty(rs, "scrap_qty"),
                "originType" to rs.getString("origin_type"),
                "remark" to rs.getString("remark"),
                // DEFECT = 공정불량(불량 이력이 붙는다) / OTHER = 그 외(잔여 원재료·재고 처분 등)
                "scrapKind" to rs.getString("scrap_kind")
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
     * 문서 관리 제거(2026-09-04) 로 `doc_no` 는 더 이상 내려가지 않는다 —
     * "이미 보고서에 편입된 전표" 라는 개념 자체가 없어졌다.
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
            sh.remark,
            -- 폐기 수량은 재고 이동으로 기록돼 **음수**다. 폐기 보고서에서 쓰는 수량은
            -- 양수이므로 여기서 부호를 뒤집는다. 그대로 내리면 화면 합계가 음수가 된다.
            abs(sh.qty)                                                  AS scrap_qty,
            -- 양식의 세 갈래(공정불량 / 불용 재고 / Loss) 중 **구조화된 근거로 나눌 수 있는
            -- 것은 공정불량뿐**이다. 불량 이력이 붙는 전표는 공정에서 불량으로 버린 것이고,
            -- 붙지 않는 전표는 잔여 원재료·재고 처분 등이다.
            -- 불용 재고와 Loss 를 가르는 근거는 스키마에 없다 — hist_type 은 'SCRAP' 하나뿐이고
            -- remark 는 자유 텍스트(75종)라 키워드로 나누면 근거 없는 숫자가 된다.
            CASE WHEN d.defect_cd IS NOT NULL THEN 'DEFECT' ELSE 'OTHER' END AS scrap_kind
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
}
