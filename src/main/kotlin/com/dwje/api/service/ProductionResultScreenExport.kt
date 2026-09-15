package com.dwje.api.service

import com.dwje.api.common.util.DataField
import com.dwje.api.common.util.DateUtils
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.ss.util.CellReference
import org.apache.poi.xddf.usermodel.chart.AxisCrosses
import org.apache.poi.xddf.usermodel.chart.AxisPosition
import org.apache.poi.xddf.usermodel.chart.BarDirection
import org.apache.poi.xddf.usermodel.chart.ChartTypes
import org.apache.poi.xddf.usermodel.chart.LegendPosition
import org.apache.poi.xddf.usermodel.chart.MarkerStyle
import org.apache.poi.xddf.usermodel.chart.XDDFBarChartData
import org.apache.poi.xddf.usermodel.chart.XDDFDataSourcesFactory
import org.apache.poi.xddf.usermodel.chart.XDDFLineChartData
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * 실적 집계·조회 화면(`/production/result`) 전체 내려받기 — 트리 한 줄.
 *
 * 화면의 Tabulator 트리(일자 → 제품 → 설비)를 평평하게 편 것이다. `level` 이 깊이다.
 * 값이 없는 칸은 null 로 남긴다 — 미측정(가동률·비가동은 설비·제품 행에 출처가 없다)과
 * 권한 마스킹을 0 으로 채우지 않는다.
 *
 * @param level      1 일자 · 2 제품 소계 · 3 설비
 * @param productCd  제품 식별자 — `coalesce(model_cd, item_cd)` (line-products 와 같은 식)
 * @param productNm  제품 모델명. 매핑이 없는 품목은 null 이라 코드로 대신 적는다
 * @param plantNm    공장 — 작업장 이름의 `(M-n공장)` 표기에서만 읽는다. 없으면 null
 * @param processNm  공정명 — 공장 열과 같은 괄호 표기만 지운 값
 */
data class ResultScreenExportRow(
    val level: Int,
    val period: String,
    val productCd: String? = null,
    val productNm: String? = null,
    val plantNm: String? = null,
    val processNm: String? = null,
    val eqptCd: String? = null,
    val eqptNm: String? = null,
    val inputQty: Long? = null,
    val okQty: Long? = null,
    val ngQty: Long? = null,
    val defectRate: Double? = null,
    val uptimeRate: Double? = null,
    val downtimeMin: Int? = null
) {
    /** 시트의 '제품명' 칸 — 모델명이 없으면 코드다. 지어내지 않는다. */
    val productLabel: String? get() = productNm?.takeIf { it.isNotBlank() } ?: productCd
}

/**
 * 실적 집계 화면 전체 내려받기 한 건의 재료.
 *
 * @param summary   기간 전체 합계 — inputQty · okQty · ngQty · defectRate · yield · avgUptimeRate · downtimeMin
 * @param dayRows   일별 행(추이 시트) — 오름차순. period · inputQty · okQty · ngQty · defectRate · yield · uptimeRate · downtimeMin
 * @param rows      집계 결과 시트의 트리(일자 내림차순, 화면 표와 같다)
 * @param maskedFields 이 사용자에게 가려진 데이터 항목 키(DataField) — 요약 시트에 밝힌다
 */
data class ResultScreenExport(
    val from: LocalDate,
    val to: LocalDate,
    val summary: Map<String, Any?>,
    val dayRows: List<Map<String, Any?>>,
    val rows: List<ResultScreenExportRow>,
    val maskedFields: List<String>,
    val downloadedBy: String?,
    val generatedAt: LocalDateTime = LocalDateTime.now()
) {
    val unit: String get() = "day"
    val dayCount: Long get() = ChronoUnit.DAYS.between(from, to) + 1
    fun countOf(level: Int): Int = rows.count { it.level == level }
}

/**
 * 실적 집계 화면 전체 엑셀(xlsx) 생성기 — 시트 3장.
 *
 * 1. `조회 요약`   조회 조건 · 기간 전체 합계 · 마스킹 · 생성 정보
 * 2. `일별 추이`   일자 · 생산량 · 불량량 · 불량률 표 + 막대(생산·불량)/꺾은선(불량률) 차트
 * 3. `집계 결과`   일자 → 제품 → 설비 트리 전체 (엑셀 행 그룹으로 접고 펼 수 있다)
 *
 * null 은 **빈 칸**이다. `""` 도 `0` 도 쓰지 않는다 — 합계·차트가 빈 칸을 값으로 세지 않도록.
 */
@Component
class ProductionResultScreenWorkbook {

    companion object {
        const val SHEET_SUMMARY = "조회 요약"
        const val SHEET_TREND = "일별 추이"
        const val SHEET_TREE = "집계 결과"

        val TREND_HEADERS = listOf("일자", "생산량", "불량량", "불량률(%)")

        val TREE_HEADERS = listOf(
            "일자", "제품명", "공장", "공정", "설비 코드", "설비명",
            "투입 수량", "양품 수량", "불량 수량", "불량률(%)", "가동률(%)", "비가동 시간(분)"
        )

        /** 데이터 항목 키 → 요약 시트에 적는 이름 */
        private val FIELD_LABELS = mapOf(
            DataField.QTY to "수량(투입·양품·불량)",
            DataField.YIELD to "비율(불량률·수율)"
        )
    }

    fun build(data: ResultScreenExport): ByteArray = XSSFWorkbook().use { wb ->
        val styles = WorkbookStyles(wb)
        writeSummary(wb.createSheet(SHEET_SUMMARY), data, styles)
        writeTrend(wb.createSheet(SHEET_TREND), data, styles)
        writeTree(wb.createSheet(SHEET_TREE), data, styles)
        ByteArrayOutputStream().use { out ->
            wb.write(out)
            out.toByteArray()
        }
    }

    // ── 1. 조회 요약 ──────────────────────────────────────────────────────────────

    private fun writeSummary(sheet: XSSFSheet, data: ResultScreenExport, st: WorkbookStyles) {
        val s = data.summary
        val masked = data.maskedFields.map { FIELD_LABELS[it] ?: it }
        val lines: List<Pair<String, Any?>> = listOf(
            "화면" to "실적 집계·조회 (/production/result)",
            "조회 시작일" to data.from.format(DateUtils.DATE),
            "조회 종료일" to data.to.format(DateUtils.DATE),
            "조회 일수" to data.dayCount,
            "집계 단위" to "일별(day)",
            "제품" to "전체",
            "설비" to "전체",
            "집계 기준" to "MES 라벨 이력(mes.tb_pop_label_hist) · 삭제분 제외 · 등록 시각 기준 일 단위(00:00~24:00) · 출하 원장이 아님",
            "투입 수량 합계" to s["inputQty"],
            "양품 수량 합계" to s["okQty"],
            "불량 수량 합계" to s["ngQty"],
            "불량률(%)" to s["defectRate"],
            "수율(%)" to s["yield"],
            "평균 가동률(%)" to s["avgUptimeRate"],
            "비가동 시간 합계(분)" to s["downtimeMin"],
            "실적 있는 일수" to data.countOf(1),
            "제품 소계 행 수" to data.countOf(2),
            "설비 행 수" to data.countOf(3),
            "권한 마스킹 항목" to (if (masked.isEmpty()) "없음" else masked.joinToString(", ")),
            "내려받은 사용자" to data.downloadedBy,
            "생성 시각" to data.generatedAt.format(DateUtils.DATETIME),
            "빈 칸의 뜻" to "값 없음(미측정 또는 권한 마스킹). 0 으로 채우지 않음. 가동률·비가동 시간은 일자 행에만 출처가 있음"
        )
        st.keyValue(sheet, lines)
    }

    // ── 2. 일별 추이 ──────────────────────────────────────────────────────────────

    private fun writeTrend(sheet: XSSFSheet, data: ResultScreenExport, st: WorkbookStyles) {
        st.header(sheet, TREND_HEADERS)
        data.dayRows.forEachIndexed { i, d ->
            val row = sheet.createRow(i + 1)
            st.text(row, 0, d["period"]?.toString())
            st.number(row.createCell(1), d["inputQty"], st.qty)
            st.number(row.createCell(2), d["ngQty"], st.qty)
            st.number(row.createCell(3), d["defectRate"], st.rate)
        }
        listOf(14, 14, 14, 12).forEachIndexed { i, w -> sheet.setColumnWidth(i, w * 256) }
        sheet.createFreezePane(0, 1)

        val n = data.dayRows.size
        if (n == 0) return
        val qtyAllowed = DataField.QTY !in data.maskedFields
        val yieldAllowed = DataField.YIELD !in data.maskedFields
        // 가려진 계열은 차트에도 올리지 않는다 — 빈 칸을 0 으로 그리면 없는 값을 만드는 셈이다.
        if (qtyAllowed) drawQtyChart(sheet, n)
        if (yieldAllowed) drawRateChart(sheet, n, offsetRows = if (qtyAllowed) 18 else 0)
    }

    private fun drawQtyChart(sheet: XSSFSheet, n: Int) {
        val drawing = sheet.createDrawingPatriarch()
        val anchor = drawing.createAnchor(0, 0, 0, 0, 5, 0, 15, 17)
        val chart = drawing.createChart(anchor)
        chart.setTitleText("일별 생산량·불량량")
        chart.titleOverlay = false
        chart.getOrAddLegend().position = LegendPosition.BOTTOM

        val cat = chart.createCategoryAxis(AxisPosition.BOTTOM)
        val value = chart.createValueAxis(AxisPosition.LEFT).apply { crosses = AxisCrosses.AUTO_ZERO }
        val bar = chart.createData(ChartTypes.BAR, cat, value) as XDDFBarChartData
        bar.barDirection = BarDirection.COL
        bar.setVaryColors(false)

        val labels = XDDFDataSourcesFactory.fromStringCellRange(sheet, CellRangeAddress(1, n, 0, 0))
        val qty = XDDFDataSourcesFactory.fromNumericCellRange(sheet, CellRangeAddress(1, n, 1, 1))
        val ng = XDDFDataSourcesFactory.fromNumericCellRange(sheet, CellRangeAddress(1, n, 2, 2))
        bar.addSeries(labels, qty).setTitle(TREND_HEADERS[1], CellReference(sheet.sheetName, 0, 1, true, true))
        bar.addSeries(labels, ng).setTitle(TREND_HEADERS[2], CellReference(sheet.sheetName, 0, 2, true, true))
        chart.plot(bar)
    }

    private fun drawRateChart(sheet: XSSFSheet, n: Int, offsetRows: Int) {
        val drawing = sheet.drawingPatriarch ?: sheet.createDrawingPatriarch()
        val anchor = drawing.createAnchor(0, 0, 0, 0, 5, offsetRows, 15, offsetRows + 17)
        val chart = drawing.createChart(anchor)
        chart.setTitleText("일별 불량률(%)")
        chart.titleOverlay = false
        chart.getOrAddLegend().position = LegendPosition.BOTTOM

        val cat = chart.createCategoryAxis(AxisPosition.BOTTOM)
        val value = chart.createValueAxis(AxisPosition.LEFT).apply { crosses = AxisCrosses.AUTO_ZERO }
        val line = chart.createData(ChartTypes.LINE, cat, value) as XDDFLineChartData
        line.setVaryColors(false)

        val labels = XDDFDataSourcesFactory.fromStringCellRange(sheet, CellRangeAddress(1, n, 0, 0))
        val rate = XDDFDataSourcesFactory.fromNumericCellRange(sheet, CellRangeAddress(1, n, 3, 3))
        (line.addSeries(labels, rate) as XDDFLineChartData.Series).apply {
            setTitle(TREND_HEADERS[3], CellReference(sheet.sheetName, 0, 3, true, true))
            setSmooth(false)
            setMarkerStyle(MarkerStyle.CIRCLE)
        }
        chart.plot(line)
    }

    // ── 3. 집계 결과(트리) ────────────────────────────────────────────────────────

    private fun writeTree(sheet: XSSFSheet, data: ResultScreenExport, st: WorkbookStyles) {
        st.header(sheet, TREE_HEADERS)
        // 접기 버튼을 그룹 **위**(일자·제품 행)에 둔다 — 화면 트리와 같은 방향.
        sheet.rowSumsBelow = false

        data.rows.forEachIndexed { i, r ->
            val row = sheet.createRow(i + 1)
            val text = st.textOf(r.level)
            st.text(row, 0, r.period, text)
            st.text(row, 1, r.productLabel, text)
            st.text(row, 2, r.plantNm, text)
            st.text(row, 3, r.processNm, text)
            st.text(row, 4, r.eqptCd, text)
            st.text(row, 5, r.eqptNm, text)
            st.number(row.createCell(6), r.inputQty, st.qtyOf(r.level))
            st.number(row.createCell(7), r.okQty, st.qtyOf(r.level))
            st.number(row.createCell(8), r.ngQty, st.qtyOf(r.level))
            st.number(row.createCell(9), r.defectRate, st.rateOf(r.level))
            st.number(row.createCell(10), r.uptimeRate, st.rateOf(r.level))
            st.number(row.createCell(11), r.downtimeMin, st.qtyOf(r.level))
        }

        groupRows(sheet, data.rows)

        listOf(12, 28, 10, 26, 12, 22, 12, 12, 12, 10, 10, 14).forEachIndexed { i, w -> sheet.setColumnWidth(i, w * 256) }
        sheet.createFreezePane(0, 1)
        if (data.rows.isNotEmpty()) {
            sheet.setAutoFilter(CellRangeAddress(0, data.rows.size, 0, TREE_HEADERS.size - 1))
        }
    }

    /**
     * 트리 깊이를 엑셀 행 그룹(outline)으로 옮긴다.
     *
     * 일자 행 아래의 제품·설비 행은 1단계, 제품 행 아래의 설비 행은 2단계다.
     * 엑셀은 같은 구간을 두 번 묶으면 단계가 하나 올라가므로 순서대로 두 번 부른다.
     */
    private fun groupRows(sheet: XSSFSheet, rows: List<ResultScreenExportRow>) {
        // rows[k] 는 엑셀 k+1 행(0행은 머리글)이다.
        fun group(level: Int) {
            var i = 0
            while (i < rows.size) {
                if (rows[i].level != level) { i++; continue }
                var j = i + 1
                while (j < rows.size && rows[j].level > level) j++
                // 자식 rows[i+1 .. j-1] → 엑셀 (i+2) .. j 행
                if (j > i + 1) sheet.groupRow(i + 2, j)
                i = j
            }
        }
        group(1)
        group(2)
    }
}
