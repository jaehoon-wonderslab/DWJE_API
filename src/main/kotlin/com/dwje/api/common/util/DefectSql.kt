package com.dwje.api.common.util

/**
 * 불량 이력 조회 공통 SQL 조각
 *
 * `mes.tb_pop_defect_hist` 의 불량코드 중 일부는 생산 불량이 아니다.
 * 해당 코드가 붙은 라벨은 `tb_pop_label_hist.defect` 가 항상 0(양품 계상)이고
 * `dh.qty` 에는 그 라벨의 전체 수량이 들어간다. 재작업·반품 처리 표시에 가깝다.
 *
 * 2026-08 실측 기준 이 코드들이 불량 이력 수량의 44% 를 차지하므로,
 * 걸러내지 않으면 불량 유형 1위가 '기타 (R)' 로 나와 라벨 기준 불량률과 앞뒤가 맞지 않는다.
 *
 * `mes` 는 조회 전용이고 `tb_md_defect` 에 구분 컬럼도 없어 원본에 표시할 수 없다.
 * 그래서 제외 목록을 `ax.tb_sys_code` 의 `QC_DEFECT_NONPROD` 그룹으로 관리한다.
 * (V10 마이그레이션에서 13건 등록. 집계에 포함하려면 `use_flg` 를 'N' 으로 바꾸면 된다.)
 *
 * 자세한 근거는 `docs/MES_QUERY_GUIDE.md` 2-4 참고.
 */
object DefectSql {

    /**
     * 유형이 붙지 않은 불량을 담는 행·세그먼트의 표시명.
     *
     * 라벨 원장에는 불량 수량이 있는데 생산 불량코드 이력이 하나도 없는 라벨이 있다.
     * (2026-08 실측 907건 / 103,741 EA — 전체 불량의 1.02%)
     * 이 물량은 어떤 유형에도 안분되지 않으므로, 유형 목록의 합이 원장 총량에 못 미친다.
     * 차액을 이 이름의 행으로 명시해 `부분의 합 = 전체` 가 응답 안에서 성립하게 한다.
     */
    const val UNTYPED_LABEL = "유형 미상"

    /**
     * 생산 불량으로 계상하지 않는 불량코드를 제외하는 조건절.
     *
     * 불량 *유형 구성·추이* 를 뽑는 쿼리에 붙인다.
     * 불량 *수량·불량률* 은 `tb_pop_label_hist.defect` 기준이므로 이 조건과 무관하다.
     *
     * @param alias `mes.tb_pop_defect_hist` 의 별칭
     */
    fun excludeNonProduction(alias: String = "dh"): String =
        // 한 줄로 유지한다. 여러 줄이면 호출부 원시 문자열의 trimIndent() 계산이 흐트러진다.
        "AND NOT EXISTS (SELECT 1 FROM ax.tb_sys_code nc " +
            "WHERE nc.group_cd = 'QC_DEFECT_NONPROD' AND nc.use_flg = 'Y' " +
            "AND nc.code = $alias.defect_cd)"

    /**
     * 라벨 원장 CTE — 불량 유형 집계의 기준이 되는 라벨 집합.
     *
     * 기간 필터는 여기(`label_hist.ins_date`)에만 걸어야 한다.
     * `defect_hist` 에 기간을 따로 걸면 다른 행 집합이 잡혀 유형 합계가 라벨 기준 불량
     * 수량과 어긋난다. (MES_QUERY_GUIDE 2-3)
     *
     * 산출 컬럼 : plant_cd, wc_cd, lot_no, serial_no, item_cd, ng_qty
     *
     * @param alias        만들 CTE 이름
     * @param fromParam    기간 시작 바인딩 파라미터명 (`:` 제외)
     * @param toParam      기간 끝(미포함) 바인딩 파라미터명 (`:` 제외)
     * @param extraFilter  라벨 이력에 추가로 걸 조건절 (별칭 `lh`). 없으면 빈 문자열
     * @param plantParam   공장 코드 바인딩 파라미터명 (`:` 제외)
     */
    fun labelLedgerCte(
        alias: String,
        fromParam: String,
        toParam: String,
        extraFilter: String = "",
        plantParam: String = "plantCd"
    ): String = """
        $alias AS (
            SELECT lh.plant_cd, lh.wc_cd, lh.lot_no, lh.serial_no, lh.item_cd,
                   coalesce(lh.defect, 0) AS ng_qty
            FROM mes.tb_pop_label_hist lh
            WHERE lh.plant_cd  = :$plantParam
              AND lh.del_flg   = 'N'
              AND lh.ins_date >= :$fromParam
              AND lh.ins_date <  :$toParam
              $extraFilter
        )
    """.trimIndent()

    /**
     * 라벨 원장 불량 수량을 유형 구성비로 안분해 유형별 수량을 만드는 CTE 쌍.
     *
     *     해당 유형 수량 = label.defect × (해당 유형 qty ÷ 그 라벨의 전체 유형 qty 합)
     *
     * 유형별 수량의 합이 라벨 총 불량과 어긋나지 않게 하는 방식이다. (MES_QUERY_GUIDE 2-4)
     * 유형이 붙지 않은(또는 (R)/T 코드만 붙은) 라벨의 불량은 어느 유형에도 들어가지 않으므로,
     * 유형 합계는 라벨 총 불량보다 작다. 그 차이가 '유형 미상' 물량이다.
     *
     * `$outAlias` 와 `${'$'}{outAlias}_join` 두 CTE 를 콤마로 이어 반환한다.
     * 산출 컬럼 : defect_cd, ng_qty
     *
     * @param outAlias    만들 CTE 이름 (유형별 수량)
     * @param labelAlias  [labelLedgerCte] 로 만든 라벨 원장 CTE 이름
     */
    fun apportionedTypeCte(outAlias: String, labelAlias: String): String = """
        ${outAlias}_join AS (
            SELECT dh.defect_cd,
                   l.ng_qty,
                   sum(dh.qty)                                 AS type_qty,
                   sum(sum(dh.qty)) OVER (
                       PARTITION BY l.plant_cd, l.wc_cd, l.lot_no, l.serial_no
                   )                                           AS label_type_total
            FROM $labelAlias l
            INNER JOIN mes.tb_pop_defect_hist dh
                    ON dh.plant_cd  = l.plant_cd
                   AND dh.wc_cd     = l.wc_cd
                   AND dh.lot_no    = l.lot_no
                   AND dh.serial_no = l.serial_no
                   ${excludeNonProduction()}
            WHERE l.ng_qty > 0
            GROUP BY l.plant_cd, l.wc_cd, l.lot_no, l.serial_no, l.ng_qty, dh.defect_cd
        ),
        $outAlias AS (
            SELECT defect_cd,
                   coalesce(sum(ng_qty * type_qty / nullif(label_type_total, 0)), 0) AS ng_qty
            FROM ${outAlias}_join
            GROUP BY defect_cd
        )
    """.trimIndent()
}

/**
 * 유형 구성 세그먼트에 '유형 미상' 을 덧붙인다.
 *
 * 유형이 붙지 않은 불량은 어떤 유형에도 안분되지 않으므로 세그먼트 합이 원장 총량에 못 미친다.
 * 그 차액을 한 세그먼트로 명시해 응답 안에서 `부분의 합 = total` 이 성립하게 한다.
 * 표시값(반올림 후) 기준으로 차액을 잡아 합이 정확히 맞도록 한다.
 *
 * @param segments 유형별 세그먼트 — `code`, `label`, `value`
 * @param total    라벨 원장 불량 총량
 */
internal fun withUntypedSegment(segments: List<Map<String, Any?>>, total: Long): List<Map<String, Any?>> {
    val typed = segments.sumOf { (it["value"] as? Long) ?: 0L }
    val untyped = total - typed
    if (untyped <= 0L) return segments

    // 실제 불량코드가 아니므로 code 는 비운다. 화면이 일반 유형과 구분해 그릴 수 있다.
    return segments + mapOf<String, Any?>(
        "code" to null,
        "label" to DefectSql.UNTYPED_LABEL,
        "value" to untyped
    )
}
