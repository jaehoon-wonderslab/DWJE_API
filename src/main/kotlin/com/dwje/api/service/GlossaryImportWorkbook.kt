package com.dwje.api.service

import com.dwje.api.common.exception.InvalidParameterException
import org.apache.poi.ss.usermodel.BorderStyle
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.ss.util.CellRangeAddressList
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayOutputStream

/**
 * 용어 사전 엑셀 업로드 — 업로드용 템플릿을 만들고, 올린 파일을 행으로 읽는다 (SY-06)
 *
 * 머리글은 템플릿과 같아야 한다. 비교할 때 앞뒤 공백과 필수 표시(`*`)는 무시한다.
 * 업무 판정(새 용어·기존 용어·유사어 규칙)은 [GlossaryService.importTerms] 가 한다.
 */
object GlossaryImportWorkbook {

    const val TERM_SHEET = "용어"
    const val GUIDE_SHEET = "안내"
    val HEADERS = listOf("공식 용어*", "뜻*", "분류", "유사어")

    /** 업로드 상한 — 파일 5MB · 데이터 1,000행 */
    const val MAX_BYTES = 5L * 1024 * 1024
    const val MAX_ROWS = 1_000

    /** 템플릿 예시 행 표시 — 이 글자로 시작하는 공식 용어 행은 읽지 않는다(지우지 않고 올려도 등록되지 않게) */
    const val EXAMPLE_MARK = "(예시)"

    /** 유사어 칸 구분자 — 쉼표·줄바꿈(전각 쉼표 포함) */
    private val VARIANT_SPLIT = Regex("[,，\\r\\n]+")

    /** 파일의 한 데이터 행 — [row] 는 엑셀 행 번호(머리글이 1행) */
    data class Row(val row: Int, val term: String, val definition: String, val domain: String, val variants: List<String>)

    /**
     * 업로드용 템플릿 — 「용어」 시트(머리글 + 예시 2행) · 「안내」 시트(규칙·글자 수·분류 목록)
     *
     * @param domains 지금 쓰는 분류 이름 — 「분류」 열에 목록 선택을 건다
     */
    fun template(domains: List<String>, termMax: Int, definitionMax: Int, variantMax: Int): ByteArray = XSSFWorkbook().use { wb ->
        val header = wb.createCellStyle().apply {
            fillForegroundColor = IndexedColors.GREY_25_PERCENT.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
            borderBottom = BorderStyle.THIN; borderTop = BorderStyle.THIN
            borderLeft = BorderStyle.THIN; borderRight = BorderStyle.THIN
            setFont(wb.createFont().apply { bold = true })
        }
        val example = wb.createCellStyle().apply {
            wrapText = true
            setFont(wb.createFont().apply { italic = true; color = IndexedColors.GREY_50_PERCENT.index })
        }
        val bold = wb.createCellStyle().apply { setFont(wb.createFont().apply { this.bold = true }) }

        val sheet = wb.createSheet(TERM_SHEET)
        sheet.createRow(0).let { r -> HEADERS.forEachIndexed { i, h -> r.createCell(i).apply { setCellValue(h); cellStyle = header } } }
        val sampleDomain = domains.firstOrNull() ?: ""
        listOf(
            listOf("$EXAMPLE_MARK 수율", "투입 수량 대비 양품 수량의 비율입니다.", sampleDomain, "양품률, 합격률"),
            listOf("$EXAMPLE_MARK 타발", "금형으로 소재를 찍어 모양을 내는 공정입니다.", sampleDomain, "펀칭\n프레스 타발")
        ).forEachIndexed { i, values ->
            val r = sheet.createRow(i + 1)
            values.forEachIndexed { c, v -> r.createCell(c).apply { setCellValue(v); cellStyle = example } }
        }
        listOf(24, 60, 18, 40).forEachIndexed { i, w -> sheet.setColumnWidth(i, w * 256) }
        sheet.createFreezePane(0, 1)

        val guide = wb.createSheet(GUIDE_SHEET)
        val lines = listOf(
            "용어 사전 업로드 안내" to true,
            "" to false,
            "1. 「용어」 시트의 머리글(1행)은 바꾸지 마십시오. 머리글이 다르면 파일 전체를 받지 않습니다." to false,
            "2. 「$EXAMPLE_MARK」 로 시작하는 예시 행은 읽지 않습니다. 지워도 됩니다." to false,
            "3. 공식 용어가 이미 있으면 그 용어에 유사어만 더합니다. 기존 용어의 뜻·분류는 바꾸지 않습니다." to false,
            "4. 새 공식 용어는 통합관리자만 등록할 수 있습니다. 새 용어 행은 뜻·분류가 필요합니다." to false,
            "5. 유사어는 쉼표(,) 또는 줄바꿈으로 여러 개를 적습니다. 이미 있는 유사어와 같은 파일 안 중복은 건너뜁니다." to false,
            "6. 유사어는 2자 이상이어야 하며, 숫자·날짜 표현과 공식 용어와 같은 낱말은 등록하지 않습니다." to false,
            "7. 한 번에 최대 ${"%,d".format(MAX_ROWS)}행, 파일 크기 ${MAX_BYTES / 1024 / 1024}MB 까지 올릴 수 있습니다. 빈 행은 건너뜁니다." to false,
            "8. 올리면 먼저 미리보기로 결과를 보여 줍니다. 오류 행은 빼고 나머지만 등록합니다." to false,
            "" to false,
            "글자 수 제한" to true,
            "공식 용어 ${termMax}자 · 뜻 ${definitionMax}자 · 유사어 ${variantMax}자" to false,
            "" to false,
            "분류 목록 (「분류」 열에 아래 이름 중 하나를 그대로 적습니다)" to true
        )
        lines.forEachIndexed { i, (text, isBold) ->
            guide.createRow(i).createCell(0).apply { setCellValue(text); if (isBold) cellStyle = bold }
        }
        val domainStart = lines.size
        domains.forEachIndexed { i, d -> guide.createRow(domainStart + i).createCell(0).setCellValue(d) }
        guide.setColumnWidth(0, 100 * 256)

        // 「분류」 열은 안내 시트의 분류 목록에서 고른다 — 목록에 없는 이름을 적어도 엑셀이 막지는 않게 경고만
        if (domains.isNotEmpty()) {
            val helper = sheet.dataValidationHelper
            val ref = "'$GUIDE_SHEET'!\$A\$${domainStart + 1}:\$A\$${domainStart + domains.size}"
            val validation = helper.createValidation(
                helper.createFormulaListConstraint(ref), CellRangeAddressList(1, MAX_ROWS + 1, 2, 2)
            ).apply {
                errorStyle = org.apache.poi.ss.usermodel.DataValidation.ErrorStyle.WARNING
                showErrorBox = true
            }
            sheet.addValidationData(validation)
        }

        ByteArrayOutputStream().use { out -> wb.write(out); out.toByteArray() }
    }

    /**
     * 올린 파일을 데이터 행으로 읽는다. 「용어」 시트가 없으면 첫 시트를 읽는다.
     *
     * 머리글이 다르면·행이 [MAX_ROWS] 를 넘으면 400. 빈 행과 예시 행은 건너뛴다.
     */
    fun parse(bytes: ByteArray): List<Row> {
        val wb = try {
            WorkbookFactory.create(bytes.inputStream())
        } catch (e: Exception) {
            throw InvalidParameterException("엑셀 파일을 읽을 수 없습니다. 템플릿을 내려받아 다시 작성해 주십시오.", "file")
        }
        return wb.use {
            val sheet = it.getSheet(TERM_SHEET) ?: it.getSheetAt(0)
            val fmt = DataFormatter()
            fun text(s: Sheet, r: Int, c: Int): String =
                s.getRow(r)?.getCell(c)?.let { cell -> fmt.formatCellValue(cell) }?.trim() ?: ""

            val actual = (0 until HEADERS.size).map { c -> text(sheet, 0, c).removeSuffix("*").trim() }
            if (actual != HEADERS.map { h -> h.removeSuffix("*") }) {
                throw InvalidParameterException(
                    "템플릿의 머리글과 다릅니다. 1행은 [${HEADERS.joinToString(" · ")}] 이어야 합니다. (파일: ${actual.joinToString(" · ")})",
                    "file"
                )
            }

            val rows = mutableListOf<Row>()
            for (r in 1..sheet.lastRowNum) {
                val cells = (0 until HEADERS.size).map { c -> text(sheet, r, c) }
                if (cells.all { c -> c.isEmpty() }) continue
                if (cells[0].startsWith(EXAMPLE_MARK)) continue
                rows += Row(
                    row = r + 1, term = cells[0], definition = cells[1], domain = cells[2],
                    variants = cells[3].split(VARIANT_SPLIT).map { v -> v.trim() }.filter { v -> v.isNotEmpty() }
                )
                if (rows.size > MAX_ROWS) {
                    throw InvalidParameterException("한 번에 ${"%,d".format(MAX_ROWS)}행까지 올릴 수 있습니다. 파일을 나눠 올려 주십시오.", "file")
                }
            }
            if (rows.isEmpty()) throw InvalidParameterException("등록할 행이 없습니다. 「용어」 시트 2행부터 적어 주십시오.", "file")
            rows
        }
    }
}
