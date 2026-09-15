package com.dwje.api

import com.dwje.api.common.util.DataField
import com.dwje.api.common.util.WorkcenterNames
import com.dwje.api.service.ProductionResultScreenWorkbook
import com.dwje.api.service.ResultScreenExport
import com.dwje.api.service.ResultScreenExportRow
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 실적 집계·조회 화면 전체 내려받기(scope=screen) — 만든 xlsx 를 **다시 읽어** 검증한다.
 *
 * 고정하는 규약
 * 1. 시트 3장의 이름·순서와 집계 결과 시트의 12개 열 머리글
 * 2. null 은 빈 셀이다 — `0` 도 `""` 도 아니다 (미측정·권한 마스킹을 값으로 만들지 않는다)
 * 3. 트리 깊이가 엑셀 행 그룹(outline)으로 옮겨진다
 * 4. 일별 추이 시트에 차트가 붙고, 가려진 계열은 차트에서도 빠진다
 * 5. 공정명의 괄호 공장 표기는 공장 열과 **정확히 같을 때만** 지운다
 */
class ProductionResultScreenExportTest {

    private val workbook = ProductionResultScreenWorkbook()

    private fun fixture(masked: List<String> = emptyList()): ResultScreenExport {
        val qtyOk = DataField.QTY !in masked
        val yieldOk = DataField.YIELD !in masked
        fun q(v: Long) = if (qtyOk) v else null
        fun r(v: Double) = if (yieldOk) v else null
        val rows = listOf(
            // 9/11 — 일자 행에는 가동률·비가동이 있고, 아래 행에는 없다(출처가 없다)
            ResultScreenExportRow(1, "2026-09-11", inputQty = q(1000), okQty = q(950), ngQty = q(50), defectRate = r(5.0), uptimeRate = 81.5, downtimeMin = 37),
            ResultScreenExportRow(2, "2026-09-11", productCd = "D63A", productNm = "D63A 모델", inputQty = q(700), okQty = q(670), ngQty = q(30), defectRate = r(4.29)),
            ResultScreenExportRow(3, "2026-09-11", productCd = "D63A", productNm = "D63A 모델", plantNm = "M-1공장", processNm = "PRESS", eqptCd = "MT-007", eqptNm = "C-프레스 7호", inputQty = q(400), okQty = q(380), ngQty = q(20), defectRate = r(5.0)),
            ResultScreenExportRow(3, "2026-09-11", productCd = "D63A", productNm = "D63A 모델", plantNm = null, processNm = "A2-PLATING(전해 라인)", eqptCd = "AT-013", eqptNm = "도금 13호", inputQty = q(300), okQty = q(290), ngQty = q(10), defectRate = r(3.33)),
            ResultScreenExportRow(2, "2026-09-11", productCd = "X99-S", productNm = null, inputQty = q(300), okQty = q(280), ngQty = q(20), defectRate = r(6.67)),
            ResultScreenExportRow(3, "2026-09-11", productCd = "X99-S", productNm = null, processNm = "W150", eqptCd = null, eqptNm = null, inputQty = q(300), okQty = q(280), ngQty = q(20), defectRate = r(6.67)),
            // 9/10 — 실적만 있고 지표는 없는 날
            ResultScreenExportRow(1, "2026-09-10", inputQty = q(500), okQty = q(500), ngQty = q(0), defectRate = r(0.0), uptimeRate = null, downtimeMin = null)
        )
        return ResultScreenExport(
            from = LocalDate.parse("2026-09-04"),
            to = LocalDate.parse("2026-09-11"),
            summary = mapOf(
                "inputQty" to q(1500), "okQty" to q(1450), "ngQty" to q(50),
                "defectRate" to r(3.33), "yield" to r(96.67), "avgUptimeRate" to 81.5, "downtimeMin" to 37
            ),
            dayRows = listOf(
                mapOf("period" to "2026-09-10", "inputQty" to q(500), "ngQty" to q(0), "defectRate" to r(0.0)),
                mapOf("period" to "2026-09-11", "inputQty" to q(1000), "ngQty" to q(50), "defectRate" to r(5.0))
            ),
            rows = rows,
            maskedFields = masked,
            downloadedBy = "관리자(10000)",
            generatedAt = LocalDateTime.of(2026, 9, 12, 9, 0)
        )
    }

    private fun open(bytes: ByteArray): XSSFWorkbook = XSSFWorkbook(ByteArrayInputStream(bytes))

    private fun Sheet.text(r: Int, c: Int): String? = getRow(r)?.getCell(c)?.takeIf { it.cellType == CellType.STRING }?.stringCellValue
    private fun Sheet.num(r: Int, c: Int): Double? = getRow(r)?.getCell(c)?.takeIf { it.cellType == CellType.NUMERIC }?.numericCellValue
    private fun Sheet.isBlank(r: Int, c: Int): Boolean = getRow(r)?.getCell(c).let { it == null || it.cellType == CellType.BLANK }

    @Test
    @DisplayName("시트 3장 — 조회 요약 · 일별 추이 · 집계 결과 순서이고, 집계 결과 열은 요청한 12개다")
    fun sheetsAndHeaders() {
        open(workbook.build(fixture())).use { wb ->
            assertEquals(
                listOf(ProductionResultScreenWorkbook.SHEET_SUMMARY, ProductionResultScreenWorkbook.SHEET_TREND, ProductionResultScreenWorkbook.SHEET_TREE),
                (0 until wb.numberOfSheets).map { wb.getSheetName(it) }
            )
            val tree = wb.getSheet(ProductionResultScreenWorkbook.SHEET_TREE)
            assertEquals(
                listOf("일자", "제품명", "공장", "공정", "설비 코드", "설비명", "투입 수량", "양품 수량", "불량 수량", "불량률(%)", "가동률(%)", "비가동 시간(분)"),
                (0 until 12).map { tree.text(0, it) }
            )
            assertEquals(12, tree.getRow(0).lastCellNum.toInt(), "열이 12개를 넘으면 계약이 바뀐 것이다")

            val trend = wb.getSheet(ProductionResultScreenWorkbook.SHEET_TREND)
            assertEquals(listOf("일자", "생산량", "불량량", "불량률(%)"), (0 until 4).map { trend.text(0, it) })
        }
    }

    @Test
    @DisplayName("집계 결과 — 트리 행 전체가 들어가고, 설비 코드/명이 나뉘며, 값 없는 칸은 빈 셀이다")
    fun treeRowsAndBlanks() {
        val data = fixture()
        open(workbook.build(data)).use { wb ->
            val tree = wb.getSheet(ProductionResultScreenWorkbook.SHEET_TREE)
            assertEquals(data.rows.size, tree.lastRowNum, "머리글 아래에 트리 행 수만큼 있어야 한다(페이지 제한 없음)")

            // 일자 행 — 가동률·비가동 있음
            assertEquals("2026-09-11", tree.text(1, 0))
            assertEquals(1000.0, tree.num(1, 6))
            assertEquals(81.5, tree.num(1, 10))
            assertEquals(37.0, tree.num(1, 11))

            // 제품 소계 — 모델명, 가동률·비가동은 출처가 없어 빈 칸
            assertEquals("D63A 모델", tree.text(2, 1))
            assertEquals(700.0, tree.num(2, 6))
            assertTrue(tree.isBlank(2, 10)); assertTrue(tree.isBlank(2, 11))

            // 설비 행 — 코드와 이름이 따로, 공장·공정도 따로
            assertEquals("M-1공장", tree.text(3, 2))
            assertEquals("PRESS", tree.text(3, 3))
            assertEquals("MT-007", tree.text(3, 4))
            assertEquals("C-프레스 7호", tree.text(3, 5))
            assertEquals(5.0, tree.num(3, 9))

            // 공장 표기가 없는 공정 — 공장은 빈 칸, 다른 괄호는 그대로
            assertTrue(tree.isBlank(4, 2))
            assertEquals("A2-PLATING(전해 라인)", tree.text(4, 3))

            // 매핑 없는 품목 — 모델명 대신 코드. 설비 코드 없는 행은 빈 칸(지어내지 않는다)
            assertEquals("X99-S", tree.text(5, 1))
            assertTrue(tree.isBlank(6, 4)); assertTrue(tree.isBlank(6, 5))

            // 지표 없는 날 — 가동률·비가동 빈 칸, 0 이 아니다
            assertEquals("2026-09-10", tree.text(7, 0))
            assertTrue(tree.isBlank(7, 10)); assertTrue(tree.isBlank(7, 11))
        }
    }

    @Test
    @DisplayName("트리 깊이가 엑셀 행 그룹으로 옮겨진다 — 제품·설비 행은 1단계, 설비 행은 2단계")
    fun outlineLevels() {
        open(workbook.build(fixture())).use { wb ->
            val tree = wb.getSheet(ProductionResultScreenWorkbook.SHEET_TREE) as XSSFSheet
            fun level(r: Int) = tree.getRow(r).ctRow.outlineLevel.toInt()
            assertEquals(0, level(1), "일자 행")
            assertEquals(1, level(2), "제품 소계 행")
            assertEquals(2, level(3), "설비 행")
            assertEquals(2, level(4), "설비 행")
            assertEquals(1, level(5), "두 번째 제품 소계 행")
            assertEquals(2, level(6), "설비 행")
            assertEquals(0, level(7), "다음 일자 행")
            assertTrue(!tree.rowSumsBelow, "접기 버튼은 그룹 위(부모 행)에 있어야 한다")
        }
    }

    @Test
    @DisplayName("일별 추이 — 오름차순 표와 차트 2개(생산·불량 막대, 불량률 꺾은선)")
    fun trendSheetWithCharts() {
        open(workbook.build(fixture())).use { wb ->
            val trend = wb.getSheet(ProductionResultScreenWorkbook.SHEET_TREND) as XSSFSheet
            assertEquals("2026-09-10", trend.text(1, 0))
            assertEquals("2026-09-11", trend.text(2, 0))
            assertEquals(1000.0, trend.num(2, 1))
            assertEquals(50.0, trend.num(2, 2))
            assertEquals(5.0, trend.num(2, 3))
            assertEquals(2, trend.drawingPatriarch.charts.size)
        }
    }

    @Test
    @DisplayName("수량 권한이 없으면 수량 칸은 빈 셀이고 차트도 불량률 하나만 남는다 — 요약에 마스킹 항목을 밝힌다")
    fun maskedQtyStaysBlank() {
        open(workbook.build(fixture(masked = listOf(DataField.QTY)))).use { wb ->
            val tree = wb.getSheet(ProductionResultScreenWorkbook.SHEET_TREE)
            (1..7).forEach { r -> (6..8).forEach { c -> assertTrue(tree.isBlank(r, c), "행 $r 열 $c 은 빈 칸이어야 한다") } }
            assertEquals(5.0, tree.num(1, 9), "비율 권한은 있으므로 불량률은 남는다")

            val trend = wb.getSheet(ProductionResultScreenWorkbook.SHEET_TREND) as XSSFSheet
            assertTrue(trend.isBlank(2, 1)); assertTrue(trend.isBlank(2, 2))
            assertEquals(1, trend.drawingPatriarch.charts.size)

            val summary = wb.getSheet(ProductionResultScreenWorkbook.SHEET_SUMMARY)
            val maskLine = (1..summary.lastRowNum).first { summary.text(it, 0) == "권한 마스킹 항목" }
            assertEquals("수량(투입·양품·불량)", summary.text(maskLine, 1))
            val inputLine = (1..summary.lastRowNum).first { summary.text(it, 0) == "투입 수량 합계" }
            assertTrue(summary.isBlank(inputLine, 1))
        }
    }

    @Test
    @DisplayName("조회 요약 — 조회 조건과 기간 전체 합계가 적힌다")
    fun summarySheet() {
        open(workbook.build(fixture())).use { wb ->
            val s = wb.getSheet(ProductionResultScreenWorkbook.SHEET_SUMMARY)
            val byLabel = (1..s.lastRowNum).associate { s.text(it, 0) to it }
            assertEquals("2026-09-04", s.text(byLabel.getValue("조회 시작일"), 1))
            assertEquals("2026-09-11", s.text(byLabel.getValue("조회 종료일"), 1))
            assertEquals(8.0, s.num(byLabel.getValue("조회 일수"), 1), "양끝 포함")
            assertEquals("일별(day)", s.text(byLabel.getValue("집계 단위"), 1))
            assertEquals("전체", s.text(byLabel.getValue("제품"), 1))
            assertEquals(1500.0, s.num(byLabel.getValue("투입 수량 합계"), 1))
            assertEquals(81.5, s.num(byLabel.getValue("평균 가동률(%)"), 1))
            assertEquals(2.0, s.num(byLabel.getValue("실적 있는 일수"), 1))
            assertEquals(3.0, s.num(byLabel.getValue("설비 행 수"), 1))
            assertEquals("없음", s.text(byLabel.getValue("권한 마스킹 항목"), 1))
            assertEquals("관리자(10000)", s.text(byLabel.getValue("내려받은 사용자"), 1))
        }
    }

    @Test
    @DisplayName("공정명의 괄호 공장 표기는 공장 열과 정확히 같을 때만 지운다")
    fun processNameDedupe() {
        assertEquals("M-1공장", WorkcenterNames.plantOf("PRESS(M-1공장)"))
        assertNull(WorkcenterNames.plantOf("A2-PLATING(전해 라인)"))
        assertNull(WorkcenterNames.plantOf("MPM-058 금형"), "금형 코드의 M- 에 걸리면 안 된다")

        assertEquals("PRESS", WorkcenterNames.withoutPlant("PRESS(M-1공장)", "M-1공장"))
        assertEquals("PRESS(M-2공장)", WorkcenterNames.withoutPlant("PRESS(M-2공장)", "M-1공장"), "다른 공장 표기는 남긴다")
        assertEquals("A2-PLATING(전해 라인)", WorkcenterNames.withoutPlant("A2-PLATING(전해 라인)", null), "공장 열이 없으면 지우지 않는다")
        assertEquals("PRESS (M-1공장)", WorkcenterNames.withoutPlant("PRESS (M-1공장)", "M-3공장"))
        assertNull(WorkcenterNames.withoutPlant(null, "M-1공장"))
    }
}
