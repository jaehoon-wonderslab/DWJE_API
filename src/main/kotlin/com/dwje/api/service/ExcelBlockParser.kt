package com.dwje.api.service

import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.springframework.stereotype.Component
import java.io.InputStream
import java.time.format.DateTimeFormatter

/**
 * 업로드 엑셀 → 화면 블록 정규화 파서 (일반형 규칙)
 *
 * ## 왜 일반형인가
 * 발주자가 "정해진 엑셀 포맷"을 별도 제공하기로 했는데 아직 오지 않았다(2026-09-10).
 * 그래서 시트 하나 = 블록 하나로 보는 **일반형 규칙**으로 시작한다. 포맷 문서가 오면
 * 이 클래스만 바꾸고 화면·저장 구조는 그대로 둔다.
 *
 * ## 규칙
 * - 시트마다 블록 하나. 시트명이 제목이다.
 * - 시트명 끝의 접미어로 차트 종류를 정한다 : `#line` `#bar` `#grouped` `#donut` `#table` (예 `월별 불량률 #line`).
 *   조정안은 `[line]` 이었는데 **엑셀은 시트명에 `[` `]` 를 허용하지 않는다**(`: \ / ? * [ ]` 금지) — 그래서 `#` 로 바꿨다.
 *   접미어가 없으면 시리즈가 1개면 `bar`, 여러 개면 `line`, 숫자 열이 없으면 `table`.
 *   (시트명은 엑셀 제한으로 31자까지다 — 제목이 길면 접미어까지 안 들어간다)
 * - 1행 = 헤더. 1열 = x축(범주·일자). 나머지 열 = 시리즈. 숫자가 아닌 열은 표에는 남기고 차트 시리즈에서는 뺀다.
 * - 빈 행·빈 헤더 열은 건너뛴다. 병합 셀은 왼쪽 위 값만 읽힌다.
 *
 * ## 산출물
 * `sheets[]` 의 각 원소가 화면 「블록 렌더러」 계약 그대로다 :
 * `{ title, chartType, x, series[], rows[] }` — `rows` 는 `{ [x열명]: 값, [시리즈명]: 값 … }` 의 배열.
 * 규칙에 안 맞는 부분은 버리지 않고 `warnings[]` 로 알린다 — 조용히 빈 차트가 나오는 것이 가장 나쁜 결과다.
 */
@Component
class ExcelBlockParser {

    companion object {
        val CHART_TYPES = setOf("line", "bar", "grouped", "donut", "table")
        private val SUFFIX = Regex("""\s*#(\w+)\s*$""")
        private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private const val MAX_ROWS_PER_SHEET = 5_000
        private const val MAX_COLS = 60
    }

    private val formatter = DataFormatter()

    /**
     * 통합문서를 읽어 블록 목록과 경고를 돌려준다.
     *
     * @throws IllegalArgumentException 엑셀로 열 수 없는 파일
     */
    fun parse(input: InputStream): ParseResult {
        val workbook: Workbook = try {
            WorkbookFactory.create(input)
        } catch (e: Exception) {
            throw IllegalArgumentException("엑셀 파일로 열 수 없습니다. xlsx 형식인지 확인하세요. (${e.message})")
        }

        val warnings = mutableListOf<String>()
        val sheets = mutableListOf<Map<String, Any?>>()

        workbook.use { wb ->
            for (i in 0 until wb.numberOfSheets) {
                val sheet = wb.getSheetAt(i)
                if (wb.isSheetHidden(i) || wb.isSheetVeryHidden(i)) {
                    warnings.add("숨김 시트 '${sheet.sheetName}' 은 건너뛰었습니다.")
                    continue
                }
                parseSheet(sheet, warnings)?.let { sheets.add(it) }
            }
        }

        if (sheets.isEmpty()) {
            warnings.add("읽을 수 있는 시트가 없습니다. 1행 헤더 · 1열 x축 · 나머지 열 시리즈 형태인지 확인하세요.")
        }
        return ParseResult(sheets, warnings)
    }

    private fun parseSheet(sheet: Sheet, warnings: MutableList<String>): Map<String, Any?>? {
        val rawName = sheet.sheetName.trim()
        val (title, requestedType) = splitTitle(rawName, warnings)

        val headerRow = firstNonEmptyRow(sheet)
        if (headerRow == null) {
            warnings.add("시트 '$rawName' 이 비어 있어 건너뛰었습니다.")
            return null
        }

        // 헤더 — 빈 칸은 열 자체를 건너뛴다.
        val columns = mutableListOf<Pair<Int, String>>()
        for (c in 0 until minOf(headerRow.lastCellNum.toInt(), MAX_COLS)) {
            val text = cellText(headerRow.getCell(c))
            if (text.isNotBlank()) columns.add(c to text)
        }
        if (columns.size < 2) {
            warnings.add("시트 '$rawName' 의 헤더가 2열 미만이라 건너뛰었습니다. (x축 1열 + 시리즈 1열 이상 필요)")
            return null
        }
        if (headerRow.lastCellNum > MAX_COLS) {
            warnings.add("시트 '$rawName' 은 ${MAX_COLS}열까지만 읽었습니다.")
        }

        val xName = columns.first().second
        val candidateSeries = columns.drop(1)

        // 본문 — 각 열이 숫자 열인지 판단하면서 읽는다.
        val rows = mutableListOf<Map<String, Any?>>()
        val numericCount = candidateSeries.associate { it.second to 0 }.toMutableMap()
        val filledCount = candidateSeries.associate { it.second to 0 }.toMutableMap()

        var truncated = false
        for (r in (headerRow.rowNum + 1)..sheet.lastRowNum) {
            val row = sheet.getRow(r) ?: continue
            if (isEmptyRow(row, columns)) continue
            if (rows.size >= MAX_ROWS_PER_SHEET) { truncated = true; break }

            val map = LinkedHashMap<String, Any?>()
            map[xName] = cellValue(row.getCell(columns.first().first))
            for ((idx, name) in candidateSeries) {
                val v = cellValue(row.getCell(idx))
                map[name] = v
                if (v != null && v.toString().isNotBlank()) {
                    filledCount[name] = filledCount.getValue(name) + 1
                    if (v is Number) numericCount[name] = numericCount.getValue(name) + 1
                }
            }
            rows.add(map)
        }
        if (truncated) warnings.add("시트 '$rawName' 은 ${MAX_ROWS_PER_SHEET}행까지만 읽었습니다.")
        if (rows.isEmpty()) {
            warnings.add("시트 '$rawName' 에 데이터 행이 없어 건너뛰었습니다.")
            return null
        }

        // 채워진 칸의 대부분(80% 이상)이 숫자인 열만 시리즈다.
        val series = candidateSeries.map { it.second }.filter { name ->
            val filled = filledCount.getValue(name)
            filled > 0 && numericCount.getValue(name) * 5 >= filled * 4
        }
        val dropped = candidateSeries.map { it.second } - series.toSet()
        if (dropped.isNotEmpty() && requestedType != "table") {
            warnings.add("시트 '$rawName' 의 열 ${dropped.joinToString { "'$it'" }} 은 숫자가 아니라 차트 시리즈에서 뺐습니다(표에는 남습니다).")
        }

        val chartType = when {
            requestedType != null -> requestedType
            series.isEmpty() -> "table"
            series.size == 1 -> "bar"
            else -> "line"
        }
        if (chartType != "table" && series.isEmpty()) {
            warnings.add("시트 '$rawName' 은 '$chartType' 차트를 요청했지만 숫자 열이 없어 표로만 보입니다.")
        }
        if (chartType == "donut" && rows.size > 12) {
            warnings.add("시트 '$rawName' 은 도넛 차트인데 항목이 ${rows.size}개입니다. 12개 이하를 권합니다.")
        }

        return mapOf(
            "title" to title,
            "chartType" to if (series.isEmpty()) "table" else chartType,
            "x" to xName,
            "series" to series,
            "columns" to columns.map { it.second },
            "rows" to rows
        )
    }

    /** 시트명에서 `#type` 접미어를 떼어 (제목, 종류) 로 나눈다. 모르는 접미어는 경고만 남긴다. */
    private fun splitTitle(name: String, warnings: MutableList<String>): Pair<String, String?> {
        val m = SUFFIX.find(name) ?: return name to null
        val type = m.groupValues[1].lowercase()
        val title = name.removeRange(m.range).trim().ifBlank { name }
        return if (type in CHART_TYPES) title to type
        else {
            warnings.add("시트 '$name' 의 차트 종류 '#$type' 는 모르는 값입니다. 가능: ${CHART_TYPES.joinToString { "#$it" }}. 자동 판정했습니다.")
            title to null
        }
    }

    private fun firstNonEmptyRow(sheet: Sheet): Row? {
        for (r in sheet.firstRowNum..sheet.lastRowNum) {
            val row = sheet.getRow(r) ?: continue
            if ((0 until row.lastCellNum.toInt()).any { cellText(row.getCell(it)).isNotBlank() }) return row
        }
        return null
    }

    private fun isEmptyRow(row: Row, columns: List<Pair<Int, String>>): Boolean =
        columns.all { (idx, _) -> cellText(row.getCell(idx)).isBlank() }

    /** 셀 값을 JSON 친화적인 값으로 — 숫자는 Double/Long, 날짜는 yyyy-MM-dd, 나머지는 문자열. */
    private fun cellValue(cell: Cell?): Any? {
        if (cell == null) return null
        val type = if (cell.cellType == CellType.FORMULA) cell.cachedFormulaResultType else cell.cellType
        return when (type) {
            CellType.NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    cell.localDateTimeCellValue?.toLocalDate()?.format(DATE_FMT)
                } else {
                    val d = cell.numericCellValue
                    if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e15) d.toLong() else d
                }
            }
            CellType.BOOLEAN -> cell.booleanCellValue
            CellType.STRING -> cell.stringCellValue.trim().ifBlank { null }
            CellType.BLANK -> null
            CellType.ERROR -> null
            else -> cellText(cell).ifBlank { null }
        }
    }

    private fun cellText(cell: Cell?): String =
        if (cell == null) "" else runCatching { formatter.formatCellValue(cell).trim() }.getOrDefault("")
}

/**
 * 파싱 결과
 *
 * @param sheets   블록 목록 — 화면 블록 렌더러 계약 그대로
 * @param warnings 규칙에 안 맞아 조정한 내용. 비어 있으면 OK, 있으면 WARN, 시트가 없으면 FAIL 로 본다.
 */
data class ParseResult(
    val sheets: List<Map<String, Any?>>,
    val warnings: List<String>
) {
    val state: String
        get() = when {
            sheets.isEmpty() -> "FAIL"
            warnings.isNotEmpty() -> "WARN"
            else -> "OK"
        }
}
