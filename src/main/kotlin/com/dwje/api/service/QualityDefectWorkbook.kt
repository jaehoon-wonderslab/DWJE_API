package com.dwje.api.service

import com.dwje.api.common.util.DataField
import com.dwje.api.common.util.DateUtils
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * 불량 현황 조회(QC-01) 내려받기의 조회 조건 — 조회 조건 시트와 파일명에 쓴다.
 *
 * @param processId    공정 코드. null 이면 전체
 * @param defectTypeCd 불량 유형 코드 — 화면 요약 카드에만 걸리는 조건. 표에는 적용되지 않는다(화면과 같다)
 * @param maskedFields 이 사용자에게 가려진 데이터 항목 키(DataField)
 */
data class DefectExportConditions(
    val from: LocalDate,
    val to: LocalDate,
    val processId: String?,
    val defectTypeCd: String?,
    val maskedFields: List<String>,
    val downloadedBy: String?,
    val generatedAt: LocalDateTime = LocalDateTime.now()
) {
    val dayCount: Long get() = ChronoUnit.DAYS.between(from, to) + 1
}

/**
 * 불량 현황 조회 화면의 엑셀(xlsx) 생성기 — 유형별 분포 · 설비별 불량률.
 *
 * 두 문서 모두 첫 시트는 `조회 조건` 이고, 행은 조회 API(by-type · by-line)가 준 것을 그대로 쓴다.
 * null 은 빈 셀이다(권한 마스킹·값 없음을 0 으로 만들지 않는다).
 */
@Component
class QualityDefectWorkbook {

    companion object {
        const val SHEET_CONDITIONS = "조회 조건"
        const val SHEET_BY_TYPE = "불량 유형별 분포"
        const val SHEET_BY_LINE = "설비별 불량률"
        const val SHEET_LINE_TYPES = "설비별 불량 유형 상세"
        const val SHEET_TREE = "불량 상세 분해"

        val BY_TYPE_HEADERS = listOf("불량 유형 코드", "불량 유형", "불량 수량", "비중(%)")
        val BY_LINE_HEADERS = listOf("설비 코드", "설비명", "정상 수량", "불량 수량", "불량률(%)", "주 유형")
        val LINE_TYPE_HEADERS = listOf("설비 코드", "설비명", "불량 유형 코드", "불량 유형", "불량 수량", "비중(%)")
        val TREE_HEADERS = listOf(
            "단계", "공정 코드", "공정명", "공장", "제품 코드", "제품명", "설비 코드", "설비명", "불량 유형 코드", "불량 유형",
            "정상 수량", "불량 수량", "불량률(%)", "유형 비중(%)"
        )

        private val FIELD_LABELS = mapOf(
            DataField.QTY to "수량(정상·불량·유형별 수량)",
            DataField.YIELD to "비율(불량률·비중) — 표 전체가 비어 있음"
        )
    }

    /** 불량 유형별 분포 — `GET /quality/defects/by-type` 의 items 그대로 */
    fun byType(cond: DefectExportConditions, items: List<Map<String, Any?>>): ByteArray = XSSFWorkbook().use { wb ->
        val st = WorkbookStyles(wb)
        writeConditions(
            wb.createSheet(SHEET_CONDITIONS), cond, st,
            title = "불량 유형별 분포",
            extra = listOf(
                "유형 수" to items.count { it["defectCd"] != null },
                "불량 수량 합계" to items.sumOrNull("cnt"),
                "비중 기준" to "라벨 원장 불량 총량 대비 %. 유형이 붙지 않은 몫은 '유형 미상' 행으로 표시 — 비중 합 = 100%"
            )
        )
        val sheet = wb.createSheet(SHEET_BY_TYPE)
        st.header(sheet, BY_TYPE_HEADERS)
        items.forEachIndexed { i, it ->
            val row = sheet.createRow(i + 1)
            st.text(row, 0, it["defectCd"] as? String)
            st.text(row, 1, it["defectType"] as? String)
            st.number(row.createCell(2), it["cnt"], st.qty)
            st.number(row.createCell(3), it["ratio"], st.rate)
        }
        finish(sheet, items.size, listOf(16, 28, 14, 10))
        wb.bytes()
    }

    /**
     * 설비별 불량률 + 설비별 불량 유형 상세 + 불량 상세 분해 트리.
     *
     * @param items  `GET /quality/defects/by-line` 의 items(children 포함) 그대로
     * @param levels 트리 시트의 단계 순서 — `GET /quality/defects/tree` 에 준 것과 같다
     * @param tree   `GET /quality/defects/tree` 의 items 그대로. 비우면 트리 시트를 만들지 않는다
     */
    @Suppress("UNCHECKED_CAST")
    fun byLine(
        cond: DefectExportConditions,
        items: List<Map<String, Any?>>,
        levels: List<DefectTreeLevel> = DefectTreeLevel.DEFAULT,
        tree: List<Map<String, Any?>>? = null
    ): ByteArray = XSSFWorkbook().use { wb ->
        val st = WorkbookStyles(wb)
        val childrenOf = { it: Map<String, Any?> -> (it["children"] as? List<Map<String, Any?>>).orEmpty() }
        val typeRows = items.sumOf { childrenOf(it).size }
        writeConditions(
            wb.createSheet(SHEET_CONDITIONS), cond, st,
            title = "설비별 불량률",
            extra = listOf(
                "설비 수" to items.size,
                "불량 수량 합계" to items.sumOrNull("ngQty"),
                "정상 수량 합계" to items.sumOrNull("okQty"),
                "유형 상세 행 수" to typeRows,
                "주 유형 기준" to "그 설비에서 안분 수량이 가장 큰 불량 유형. 유형이 없는 설비는 빈 칸",
                "유형 비중 기준" to "그 설비의 불량 수량 대비 %. 유형이 붙지 않은 몫은 '유형 미상' — 설비별 비중 합 = 100%"
            ) + (tree?.let {
                val flat = DefectTreeAssembler.flatten(it)
                listOf(
                    "불량 상세 분해 단계" to levels.joinToString(" > ") { l -> l.label },
                    "불량 상세 분해 행 수" to flat.size,
                    "불량 상세 분해 기준" to "각 단계 행은 그 단계까지 확정된 차원만 채움. 상위 수량 = 하위 합. 유형 행은 불량 수량과 유형 비중만 있음. 엑셀 행 그룹으로 접고 펼 수 있음"
                )
            } ?: emptyList())
        )

        val line = wb.createSheet(SHEET_BY_LINE)
        st.header(line, BY_LINE_HEADERS)
        items.forEachIndexed { i, it ->
            val row = line.createRow(i + 1)
            st.text(row, 0, it["eqptCd"] as? String)
            st.text(row, 1, it["eqptNm"] as? String ?: it["model"] as? String)
            st.number(row.createCell(2), it["okQty"], st.qty)
            st.number(row.createCell(3), it["ngQty"], st.qty)
            st.number(row.createCell(4), it["defectRate"], st.rate)
            st.text(row, 5, it["mainType"] as? String)
        }
        finish(line, items.size, listOf(12, 30, 14, 14, 10, 18))

        val detail = wb.createSheet(SHEET_LINE_TYPES)
        st.header(detail, LINE_TYPE_HEADERS)
        var r = 1
        items.forEach { item ->
            childrenOf(item).forEach { c ->
                val row = detail.createRow(r++)
                st.text(row, 0, item["eqptCd"] as? String)
                st.text(row, 1, item["eqptNm"] as? String ?: item["model"] as? String)
                st.text(row, 2, c["defectCd"] as? String)
                st.text(row, 3, c["defectType"] as? String)
                st.number(row.createCell(4), c["ngQty"], st.qty)
                st.number(row.createCell(5), c["ratio"], st.rate)
            }
        }
        finish(detail, r - 1, listOf(12, 30, 16, 28, 14, 10))

        if (tree != null) writeTree(wb.createSheet(SHEET_TREE), levels, tree, st)
        wb.bytes()
    }

    /**
     * 불량 상세 분해 트리 — 깊이 우선으로 펴고, 깊이를 엑셀 행 그룹(outline)으로 옮긴다.
     * 1단계 굵은 회색 · 2단계 연노랑 · 그 아래 무늬 없음. 접기 버튼은 부모 행 위.
     */
    private fun writeTree(sheet: XSSFSheet, levels: List<DefectTreeLevel>, tree: List<Map<String, Any?>>, st: WorkbookStyles) {
        st.header(sheet, TREE_HEADERS)
        sheet.rowSumsBelow = false
        val flat = DefectTreeAssembler.flatten(tree)
        val labelOf = levels.associate { it.key to it.label }
        flat.forEachIndexed { i, (depth, n) ->
            val row = sheet.createRow(i + 1)
            val text = st.textOf(depth)
            st.text(row, 0, labelOf[n["level"] as? String] ?: n["level"] as? String, text)
            st.text(row, 1, n["wcCd"] as? String, text)
            st.text(row, 2, n["wcNm"] as? String, text)
            st.text(row, 3, n["plantNm"] as? String, text)
            st.text(row, 4, n["itemCd"] as? String, text)
            st.text(row, 5, n["itemNm"] as? String, text)
            st.text(row, 6, n["eqptCd"] as? String, text)
            st.text(row, 7, n["eqptNm"] as? String, text)
            st.text(row, 8, n["defectCd"] as? String, text)
            st.text(row, 9, n["defectNm"] as? String, text)
            st.number(row.createCell(10), n["okQty"], st.qtyOf(depth))
            st.number(row.createCell(11), n["ngQty"], st.qtyOf(depth))
            st.number(row.createCell(12), n["defectRate"], st.rateOf(depth))
            st.number(row.createCell(13), n["ratio"], st.rateOf(depth))
        }
        // 깊이 d 의 행 아래에 이어지는 더 깊은 행들을 한 그룹으로. 깊이 1..(최대-1) 순서로 묶으면 단계가 쌓인다.
        val maxDepth = flat.maxOfOrNull { it.first } ?: 0
        for (d in 1 until maxDepth) {
            var i = 0
            while (i < flat.size) {
                if (flat[i].first != d) { i++; continue }
                var j = i + 1
                while (j < flat.size && flat[j].first > d) j++
                if (j > i + 1) sheet.groupRow(i + 2, j)
                i = j
            }
        }
        finish(sheet, flat.size, listOf(10, 10, 26, 10, 14, 30, 12, 26, 14, 20, 14, 14, 10, 12))
    }

    // ── 공통 ─────────────────────────────────────────────────────────────────────

    private fun writeConditions(
        sheet: XSSFSheet,
        cond: DefectExportConditions,
        st: WorkbookStyles,
        title: String,
        extra: List<Pair<String, Any?>>
    ) {
        val masked = cond.maskedFields.map { FIELD_LABELS[it] ?: it }
        val lines = mutableListOf<Pair<String, Any?>>(
            "화면" to "불량 현황 조회 (/quality/defect) — $title",
            "조회 시작일" to cond.from.format(DateUtils.DATE),
            "조회 종료일" to cond.to.format(DateUtils.DATE),
            "조회 일수" to cond.dayCount,
            "공정" to (cond.processId ?: "전체"),
            "불량 유형 조건" to (cond.defectTypeCd ?: "전체")
        )
        if (cond.defectTypeCd != null) {
            lines += "불량 유형 조건 적용 범위" to
                "화면 요약 카드(불량 수량·불량률)에만 적용. 이 표는 화면과 같이 전체 유형 기준"
        }
        lines += "집계 기준" to
            "불량 수량·불량률은 MES 라벨 이력(mes.tb_pop_label_hist.defect) 원장 · 불량 유형은 불량 이력(tb_pop_defect_hist)을 " +
            "라벨 불량 수량에 안분 · 생산 불량이 아닌 코드(QC_DEFECT_NONPROD) 제외 · 기간은 라벨 등록 시각 기준"
        lines += extra
        lines += "권한 마스킹 항목" to (if (masked.isEmpty()) "없음" else masked.joinToString(", "))
        lines += "내려받은 사용자" to cond.downloadedBy
        lines += "생성 시각" to cond.generatedAt.format(DateUtils.DATETIME)
        lines += "빈 칸의 뜻" to "값 없음(권한 마스킹 또는 해당 없음). 0 으로 채우지 않음"
        st.keyValue(sheet, lines)
    }

    private fun finish(sheet: XSSFSheet, rowCount: Int, widths: List<Int>) {
        widths.forEachIndexed { i, w -> sheet.setColumnWidth(i, w * 256) }
        sheet.createFreezePane(0, 1)
        if (rowCount > 0) sheet.setAutoFilter(CellRangeAddress(0, rowCount, 0, widths.size - 1))
    }

    /** 수량 합 — 하나라도 값이 있어야 합을 낸다. 전부 null(마스킹)이면 null. */
    private fun List<Map<String, Any?>>.sumOrNull(key: String): Long? {
        val values = mapNotNull { (it[key] as? Number)?.toLong() }
        return if (values.isEmpty()) null else values.sum()
    }

    private fun XSSFWorkbook.bytes(): ByteArray = ByteArrayOutputStream().use { out -> write(out); out.toByteArray() }
}
