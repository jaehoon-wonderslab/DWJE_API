package com.dwje.api.service

import org.apache.poi.ss.usermodel.BorderStyle
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellStyle
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook

/**
 * 화면 내려받기용 통합 문서(xlsx)의 공통 셀 스타일과 쓰기 도우미.
 *
 * 스타일은 워크북 소속이라 문서마다 한 번 만든다. 실적 집계·불량 현황 내려받기가 같은 모양을 쓰도록
 * 여기 한 곳에 둔다 — 머리글 회색, 트리 1단계(부모) 굵은 회색, 2단계 연노랑, 그 아래 무늬 없음.
 *
 * null 은 **빈 셀**로 쓴다. `""` 도 `0` 도 쓰지 않는다 — 미측정·권한 마스킹을 값으로 만들지 않기 위해서다.
 */
class WorkbookStyles(private val wb: XSSFWorkbook) {

    companion object {
        const val FMT_QTY = "#,##0"
        const val FMT_RATE = "0.00"
    }

    private fun base(
        fill: IndexedColors? = null,
        bold: Boolean = false,
        fmt: String? = null,
        align: HorizontalAlignment? = null
    ): XSSFCellStyle = wb.createCellStyle().apply {
        borderBottom = BorderStyle.THIN; borderTop = BorderStyle.THIN
        borderLeft = BorderStyle.THIN; borderRight = BorderStyle.THIN
        if (fill != null) { fillForegroundColor = fill.index; fillPattern = FillPatternType.SOLID_FOREGROUND }
        if (bold) setFont(wb.createFont().apply { this.bold = true })
        if (fmt != null) dataFormat = wb.createDataFormat().getFormat(fmt)
        if (align != null) alignment = align
    }

    val header: XSSFCellStyle = base(IndexedColors.GREY_25_PERCENT, bold = true, align = HorizontalAlignment.CENTER)
    val label: XSSFCellStyle = base(IndexedColors.GREY_25_PERCENT, bold = true)
    val text: XSSFCellStyle = base()
    val wrap: XSSFCellStyle = base().apply { wrapText = true }
    val qty: XSSFCellStyle = base(fmt = FMT_QTY)
    val rate: XSSFCellStyle = base(fmt = FMT_RATE)

    // 트리 깊이별 — 1단계 굵게+회색, 2단계 연노랑, 그 아래 무늬 없음
    private val l1Text = base(IndexedColors.GREY_25_PERCENT, bold = true)
    private val l1Qty = base(IndexedColors.GREY_25_PERCENT, bold = true, fmt = FMT_QTY)
    private val l1Rate = base(IndexedColors.GREY_25_PERCENT, bold = true, fmt = FMT_RATE)
    private val l2Text = base(IndexedColors.LEMON_CHIFFON)
    private val l2Qty = base(IndexedColors.LEMON_CHIFFON, fmt = FMT_QTY)
    private val l2Rate = base(IndexedColors.LEMON_CHIFFON, fmt = FMT_RATE)

    fun textOf(level: Int): XSSFCellStyle = when (level) { 1 -> l1Text; 2 -> l2Text; else -> text }
    fun qtyOf(level: Int): XSSFCellStyle = when (level) { 1 -> l1Qty; 2 -> l2Qty; else -> qty }
    fun rateOf(level: Int): XSSFCellStyle = when (level) { 1 -> l1Rate; 2 -> l2Rate; else -> rate }

    // ── 쓰기 도우미 ─────────────────────────────────────────────────────────────

    /** 0행 머리글 */
    fun header(sheet: XSSFSheet, headers: List<String>) {
        val row = sheet.createRow(0)
        headers.forEachIndexed { i, h -> row.createCell(i).apply { setCellValue(h); cellStyle = header } }
    }

    /** 문자열 셀 — null 이면 스타일만 입힌 빈 셀 */
    fun text(row: Row, col: Int, value: String?, style: CellStyle = text) {
        val cell = row.createCell(col)
        cell.cellStyle = style
        if (value != null) cell.setCellValue(value)
    }

    /** 숫자만 숫자 셀로 쓴다. null 은 빈 셀 — 스타일만 입혀 테두리를 맞춘다. */
    fun number(cell: Cell, value: Any?, style: CellStyle) {
        cell.cellStyle = style
        when (value) {
            null -> Unit
            is Number -> cell.setCellValue(value.toDouble())
            else -> cell.setCellValue(value.toString())
        }
    }

    /**
     * `항목 | 값` 두 열 표 — 조회 요약 시트 공용.
     *
     * 값이 숫자면 라벨에 `%` 가 있을 때 비율 서식, 아니면 수량 서식이다. 문자열은 줄바꿈 셀.
     */
    fun keyValue(sheet: XSSFSheet, lines: List<Pair<String, Any?>>, labelWidth: Int = 24, valueWidth: Int = 90) {
        header(sheet, listOf("항목", "값"))
        lines.forEachIndexed { i, (label, value) ->
            val row = sheet.createRow(i + 1)
            text(row, 0, label, this.label)
            val cell = row.createCell(1)
            when (value) {
                null -> cell.cellStyle = text
                is Number -> number(cell, value, if (label.contains("%")) rate else qty)
                else -> { cell.cellStyle = wrap; cell.setCellValue(value.toString()) }
            }
        }
        sheet.setColumnWidth(0, labelWidth * 256)
        sheet.setColumnWidth(1, valueWidth * 256)
    }
}
