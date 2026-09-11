package com.dwje.api

import com.dwje.api.service.AoiDefectService
import com.dwje.api.service.ExcelBlockParser
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * 업로드 엑셀 일반형 파서 규약 테스트 (요구 6)
 *
 * 포맷 엑셀이 오기 전의 일반형 규칙을 고정한다 — 시트 = 블록, 1행 헤더, 1열 x축, 나머지 열 시리즈,
 * 시트명 접미어로 차트 종류. 규칙에 안 맞는 부분은 버리지 않고 경고로 알린다.
 */
class ExcelBlockParserTest {

    private val parser = ExcelBlockParser()

    private fun workbook(build: XSSFWorkbook.() -> Unit): ByteArray {
        val wb = XSSFWorkbook()
        wb.build()
        val out = ByteArrayOutputStream()
        wb.use { it.write(out) }
        return out.toByteArray()
    }

    private fun XSSFWorkbook.sheet(name: String, vararg rows: List<Any?>) {
        val s = createSheet(name)
        rows.forEachIndexed { r, cells ->
            val row = s.createRow(r)
            cells.forEachIndexed { c, v ->
                val cell = row.createCell(c)
                when (v) {
                    null -> Unit
                    is Number -> cell.setCellValue(v.toDouble())
                    is Boolean -> cell.setCellValue(v)
                    else -> cell.setCellValue(v.toString())
                }
            }
        }
    }

    @Test
    @DisplayName("시트명 '#접미어' 가 차트 종류가 되고, 1열은 x축, 숫자 열만 시리즈다")
    fun suffixAndSeries() {
        val bytes = workbook {
            sheet("월별 불량률 #line",
                listOf("월", "PRESS", "PLATING", "비고"),
                listOf("2026-01", 1.2, 2.1, "메모"),
                listOf("2026-02", 1.5, 2.0, null))
        }
        val result = parser.parse(bytes.inputStream())
        val block = result.sheets.single()

        assertEquals("월별 불량률", block["title"])
        assertEquals("line", block["chartType"])
        assertEquals("월", block["x"])
        assertEquals(listOf("PRESS", "PLATING"), block["series"])
        @Suppress("UNCHECKED_CAST")
        val rows = block["rows"] as List<Map<String, Any?>>
        assertEquals(2, rows.size)
        assertEquals("2026-01", rows[0]["월"])
        assertEquals(1.2, rows[0]["PRESS"])
        // 숫자가 아닌 열은 표에는 남고 시리즈에서만 빠진다 — 그 사실을 경고로 알린다.
        assertEquals("메모", rows[0]["비고"])
        assertTrue(result.warnings.any { it.contains("'비고'") }) { result.warnings.toString() }
        assertEquals("WARN", result.state)
    }

    @Test
    @DisplayName("접미어가 없으면 시리즈 1개는 bar, 여러 개는 line, 숫자 열이 없으면 table 이다")
    fun autoChartType() {
        val bytes = workbook {
            sheet("설비별 생산량", listOf("설비", "생산량"), listOf("MT-001", 12000), listOf("MT-002", 9800))
            sheet("추이", listOf("일", "A", "B"), listOf("1", 1, 2), listOf("2", 3, 4))
            sheet("회의 메모", listOf("항목", "내용"), listOf("결정", "라인 A 점검"))
        }
        val result = parser.parse(bytes.inputStream())
        val types = result.sheets.associate { it["title"] to it["chartType"] }
        assertEquals("bar", types["설비별 생산량"])
        assertEquals("line", types["추이"])
        assertEquals("table", types["회의 메모"])
        // 정수는 Long 으로 온다 — 화면이 12000.0 을 그리지 않게.
        @Suppress("UNCHECKED_CAST")
        val rows = result.sheets.first()["rows"] as List<Map<String, Any?>>
        assertEquals(12000L, rows[0]["생산량"])
    }

    @Test
    @DisplayName("모르는 접미어·빈 시트·헤더 1열은 버리지 않고 경고로 알린다")
    fun warningsInsteadOfSilence() {
        val bytes = workbook {
            sheet("일자별 #foo", listOf("일자", "수량"), listOf("2026-09-01", 10))
            sheet("빈시트")
            sheet("한열", listOf("x"), listOf("a"))
        }
        val result = parser.parse(bytes.inputStream())
        assertEquals(1, result.sheets.size)
        assertEquals("일자별", result.sheets[0]["title"])
        assertEquals("bar", result.sheets[0]["chartType"])
        assertTrue(result.warnings.any { it.contains("#foo") })
        assertTrue(result.warnings.any { it.contains("빈시트") })
        assertTrue(result.warnings.any { it.contains("한열") })
    }

    @Test
    @DisplayName("읽을 시트가 하나도 없으면 FAIL, 엑셀이 아니면 예외다")
    fun failAndInvalid() {
        val empty = workbook { sheet("x") }
        assertEquals("FAIL", parser.parse(empty.inputStream()).state)
        assertThrows(IllegalArgumentException::class.java) {
            parser.parse("not an excel".toByteArray().inputStream())
        }
    }

    @Test
    @DisplayName("불량 ID 는 plant-wc-lot-serial 이고 작업장 코드의 '-' 도 견딘다")
    fun defectIdSplit() {
        val k = AoiDefectService.splitDefectId("PL01-V140-20260803-00316")!!
        assertEquals(listOf("PL01", "V140", "20260803", "00316"), listOf(k.plantCd, k.wcCd, k.lotNo, k.serialNo))

        val dashed = AoiDefectService.splitDefectId("PL01-W-110-20260803-00316")!!
        assertEquals("W-110", dashed.wcCd)

        assertNull(AoiDefectService.splitDefectId("PL01-V140-20260803"))
        assertNull(AoiDefectService.splitDefectId("PL01--20260803-00316"))
    }
}
