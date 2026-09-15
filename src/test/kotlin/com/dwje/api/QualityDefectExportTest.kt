package com.dwje.api

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.util.DataField
import com.dwje.api.common.util.MaskingSupport
import com.dwje.api.common.util.MenuId
import com.dwje.api.config.AppProperties
import com.dwje.api.controller.QualityController
import com.dwje.api.model.request.QualityDefectExportRequest
import com.dwje.api.repository.DownloadLogRepository
import com.dwje.api.repository.QualityRepository
import com.dwje.api.service.AoiDefectService
import com.dwje.api.service.AoiPredictionService
import com.dwje.api.service.AuditLogService
import com.dwje.api.service.AuthorizationService
import com.dwje.api.service.DefectExportConditions
import com.dwje.api.service.DownloadLogService
import com.dwje.api.service.ExportService
import com.dwje.api.service.QualityDefectService
import com.dwje.api.service.QualityDefectWorkbook
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate

/**
 * 불량 현황 내려받기(by-type · by-line export) — 만든 xlsx 를 다시 읽어 검증하고, 컨트롤러 계약을 고정한다.
 *
 * 1. 시트 구성·머리글이 요청한 열이고, 유형 상세 시트는 children 을 설비마다 펼친다
 * 2. null(권한 마스킹)은 빈 셀이며 요약에 밝힌다
 * 3. 응답은 xlsx 바이너리 + `Content-Disposition` 파일명, 다운로드 이력에 형식·행 수·파일명·크기·조건이 남는다
 * 4. csv 는 400 이고 이력을 남기지 않는다
 */
class QualityDefectExportTest {

    private val workbook = QualityDefectWorkbook()

    private val cond = DefectExportConditions(
        from = LocalDate.parse("2026-09-01"), to = LocalDate.parse("2026-09-11"),
        processId = "W120", defectTypeCd = "D01", maskedFields = emptyList(), downloadedBy = "관리자(10000)"
    )

    private val typeItems: List<Map<String, Any?>> = listOf(
        mapOf("defectCd" to "D01", "defectType" to "얼룩", "cnt" to 600L, "ratio" to 60.0, "momChange" to 3.2),
        mapOf("defectCd" to "D02", "defectType" to "은하수", "cnt" to 300L, "ratio" to 30.0, "momChange" to null),
        mapOf("defectCd" to null, "defectType" to "유형 미상", "cnt" to 100L, "ratio" to 10.0, "momChange" to null)
    )

    private val lineItems: List<Map<String, Any?>> = listOf(
        mapOf(
            "eqptCd" to "MN-077", "eqptNm" to "Cosmetic 02호기", "model" to null,
            "ngQty" to 1000L, "okQty" to 500L, "totalQty" to 1500L, "defectRate" to 66.67, "mainType" to "얼룩",
            "children" to listOf(
                mapOf("defectCd" to "D01", "defectType" to "얼룩", "ngQty" to 600L, "ratio" to 60.0),
                mapOf("defectCd" to null, "defectType" to "유형 미상", "ngQty" to 400L, "ratio" to 40.0)
            )
        ),
        mapOf(
            "eqptCd" to "BG-002", "eqptNm" to null, "model" to "BG형",
            "ngQty" to 0L, "okQty" to 1000L, "totalQty" to 1000L, "defectRate" to 0.0, "mainType" to null,
            "children" to emptyList<Map<String, Any?>>()
        )
    )

    private fun open(bytes: ByteArray) = XSSFWorkbook(ByteArrayInputStream(bytes))
    private fun Sheet.text(r: Int, c: Int): String? = getRow(r)?.getCell(c)?.takeIf { it.cellType == CellType.STRING }?.stringCellValue
    private fun Sheet.num(r: Int, c: Int): Double? = getRow(r)?.getCell(c)?.takeIf { it.cellType == CellType.NUMERIC }?.numericCellValue
    private fun Sheet.isBlank(r: Int, c: Int): Boolean = getRow(r)?.getCell(c).let { it == null || it.cellType == CellType.BLANK }
    private fun Sheet.valueOf(label: String): Any? {
        val r = (1..lastRowNum).first { text(it, 0) == label }
        return text(r, 1) ?: num(r, 1)
    }

    @Test
    @DisplayName("by-type — 조회 조건 + 유형별 분포(코드·유형·불량 수량·비중)")
    fun byType() {
        open(workbook.byType(cond, typeItems)).use { wb ->
            assertEquals(listOf(QualityDefectWorkbook.SHEET_CONDITIONS, QualityDefectWorkbook.SHEET_BY_TYPE), (0 until wb.numberOfSheets).map { wb.getSheetName(it) })
            val s = wb.getSheet(QualityDefectWorkbook.SHEET_BY_TYPE)
            assertEquals(listOf("불량 유형 코드", "불량 유형", "불량 수량", "비중(%)"), (0 until 4).map { s.text(0, it) })
            assertEquals(3, s.lastRowNum)
            assertEquals("D01", s.text(1, 0)); assertEquals("얼룩", s.text(1, 1)); assertEquals(600.0, s.num(1, 2)); assertEquals(60.0, s.num(1, 3))
            assertTrue(s.isBlank(3, 0), "유형 미상은 코드가 빈 칸")
            assertEquals("유형 미상", s.text(3, 1))

            val c = wb.getSheet(QualityDefectWorkbook.SHEET_CONDITIONS)
            assertEquals("2026-09-01", c.valueOf("조회 시작일"))
            assertEquals(11.0, c.valueOf("조회 일수"))
            assertEquals("W120", c.valueOf("공정"))
            assertEquals("D01", c.valueOf("불량 유형 조건"))
            assertTrue((c.valueOf("불량 유형 조건 적용 범위") as String).contains("요약 카드"), "유형 조건이 표에 걸리지 않음을 밝힌다")
            assertEquals(1000.0, c.valueOf("불량 수량 합계"))
            assertEquals(2.0, c.valueOf("유형 수"), "유형 미상은 유형 수에 세지 않는다")
            assertEquals("없음", c.valueOf("권한 마스킹 항목"))
        }
    }

    @Test
    @DisplayName("by-line — 설비별 표 + 설비별 유형 상세, 이름 없는 설비는 모델명, 유형 없는 설비는 빈 칸")
    fun byLine() {
        open(workbook.byLine(cond.copy(defectTypeCd = null), lineItems)).use { wb ->
            assertEquals(
                listOf(QualityDefectWorkbook.SHEET_CONDITIONS, QualityDefectWorkbook.SHEET_BY_LINE, QualityDefectWorkbook.SHEET_LINE_TYPES),
                (0 until wb.numberOfSheets).map { wb.getSheetName(it) }
            )
            val line = wb.getSheet(QualityDefectWorkbook.SHEET_BY_LINE)
            assertEquals(listOf("설비 코드", "설비명", "정상 수량", "불량 수량", "불량률(%)", "주 유형"), (0 until 6).map { line.text(0, it) })
            assertEquals(2, line.lastRowNum)
            assertEquals("MN-077", line.text(1, 0)); assertEquals(500.0, line.num(1, 2)); assertEquals(1000.0, line.num(1, 3))
            assertEquals(66.67, line.num(1, 4)); assertEquals("얼룩", line.text(1, 5))
            assertEquals("BG형", line.text(2, 1), "설비명이 없으면 모델명 — 화면과 같다")
            assertTrue(line.isBlank(2, 5), "유형 없는 설비의 주 유형은 빈 칸")

            val detail = wb.getSheet(QualityDefectWorkbook.SHEET_LINE_TYPES)
            assertEquals(listOf("설비 코드", "설비명", "불량 유형 코드", "불량 유형", "불량 수량", "비중(%)"), (0 until 6).map { detail.text(0, it) })
            assertEquals(2, detail.lastRowNum, "children 수만큼")
            assertEquals("MN-077", detail.text(1, 0)); assertEquals("D01", detail.text(1, 2)); assertEquals(600.0, detail.num(1, 4)); assertEquals(60.0, detail.num(1, 5))
            assertTrue(detail.isBlank(2, 2)); assertEquals("유형 미상", detail.text(2, 3)); assertEquals(400.0, detail.num(2, 4))

            val c = wb.getSheet(QualityDefectWorkbook.SHEET_CONDITIONS)
            assertEquals("전체", c.valueOf("불량 유형 조건"))
            assertEquals(2.0, c.valueOf("설비 수")); assertEquals(1000.0, c.valueOf("불량 수량 합계")); assertEquals(1500.0, c.valueOf("정상 수량 합계"))
            assertEquals(2.0, c.valueOf("유형 상세 행 수"))
        }
    }

    @Test
    @DisplayName("수량 권한이 없으면 수량 칸은 빈 셀, 합계도 빈 칸, 요약에 마스킹을 밝힌다")
    fun maskedQty() {
        val masked = lineItems.map { it + mapOf("ngQty" to null, "okQty" to null, "totalQty" to null) }
        open(workbook.byLine(cond.copy(maskedFields = listOf(DataField.QTY)), masked)).use { wb ->
            val line = wb.getSheet(QualityDefectWorkbook.SHEET_BY_LINE)
            assertTrue(line.isBlank(1, 2)); assertTrue(line.isBlank(1, 3))
            assertEquals(66.67, line.num(1, 4), "비율 권한은 있으므로 불량률은 남는다")
            val c = wb.getSheet(QualityDefectWorkbook.SHEET_CONDITIONS)
            assertEquals("수량(정상·불량·유형별 수량)", c.valueOf("권한 마스킹 항목"))
            val r = (1..c.lastRowNum).first { c.text(it, 0) == "불량 수량 합계" }
            assertTrue(c.isBlank(r, 1), "전부 가려졌으면 합계도 빈 칸 — 0 이 아니다")
        }
    }

    // ── 컨트롤러 계약 ────────────────────────────────────────────────────────────

    private class RecordingDownloadLog : DownloadLogService(
        mock(DownloadLogRepository::class.java), mock(AuditLogService::class.java),
        mock(AuthorizationService::class.java), mock(AppProperties::class.java), ObjectMapper()
    ) {
        data class Call(val reportNm: String, val menuId: String?, val format: String, val scope: String?, val rowCnt: Int,
                        val blindCnt: Int, val fileNm: String?, val params: Map<String, Any?>?, val fileSize: Long?)
        val calls = mutableListOf<Call>()
        override fun record(
            reportId: String?, reportNm: String, menuId: String?, format: String, scope: String?, rowCnt: Int, blindCnt: Int,
            blindCells: Map<String, Int>, fileNm: String?, params: Map<String, Any?>?, fileSize: Long?
        ): Long { calls += Call(reportNm, menuId, format, scope, rowCnt, blindCnt, fileNm, params, fileSize); return calls.size.toLong() }
    }

    private class StubDefects(private val types: List<Map<String, Any?>>, private val lines: List<Map<String, Any?>>) : QualityDefectService(
        mock(QualityRepository::class.java), mock(AuthorizationService::class.java), mock(AppProperties::class.java)
    ) {
        var lineTopN: Int? = -1
        override fun getByType(from: String?, to: String?, processId: String?): Pair<Map<String, Any?>, MaskingSupport> =
            mapOf("items" to types) to MaskingSupport(null)
        override fun getByLine(from: String?, to: String?, processId: String?, topN: Int?): Pair<Map<String, Any?>, MaskingSupport> {
            lineTopN = topN
            return mapOf("items" to lines) to MaskingSupport(null)
        }
        var treeLevels: String? = "unset"
        override fun getDefectTree(from: String?, to: String?, processId: String?, levels: String?): Pair<Map<String, Any?>, MaskingSupport> {
            treeLevels = levels
            val leaf = mapOf("level" to "eqpt", "plantCd" to "PL01", "wcCd" to "W120", "itemCd" to "D63A-S", "eqptCd" to "MN-077", "okQty" to 500L, "ngQty" to 1000L, "defectRate" to 66.67, "ratio" to null)
            val item = mapOf("level" to "item", "plantCd" to "PL01", "wcCd" to "W120", "itemCd" to "D63A-S", "okQty" to 500L, "ngQty" to 1000L, "defectRate" to 66.67, "ratio" to null, "children" to listOf(leaf))
            val wc = mapOf("level" to "wc", "plantCd" to "PL01", "wcCd" to "W120", "wcNm" to "PRESS", "okQty" to 500L, "ngQty" to 1000L, "defectRate" to 66.67, "ratio" to null, "children" to listOf(item))
            return mapOf("levels" to listOf("wc", "item", "eqpt"), "items" to listOf(wc)) to MaskingSupport(null)
        }
    }

    private val defects = StubDefects(typeItems, lineItems)
    private val downloadLog = RecordingDownloadLog()
    private val controller = QualityController(
        defects, mock(AoiPredictionService::class.java), mock(AoiDefectService::class.java), ExportService(), downloadLog, workbook
    )

    private fun filenameOf(disposition: String): String =
        URLDecoder.decode(Regex("filename\\*=UTF-8''([^;]+)").find(disposition)!!.groupValues[1], StandardCharsets.UTF_8)

    @Test
    @DisplayName("by-type export — xlsx 응답·파일명·이력")
    fun byTypeExportContract() {
        val res = controller.defectByTypeExport(QualityDefectExportRequest(from = "2026-09-01", to = "2026-09-11", processId = "W120", defectTypeCd = "D01"))
        assertEquals(200, res.statusCode.value())
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", res.headers.contentType.toString())
        assertEquals("불량_유형별_분포_2026-09-01_2026-09-11.xlsx", filenameOf(res.headers.getFirst("Content-Disposition")!!))
        open(res.body!!.byteArray).use { wb -> assertEquals(2, wb.numberOfSheets) }

        val call = downloadLog.calls.single()
        assertEquals("불량 유형별 분포", call.reportNm); assertEquals(MenuId.QC_DEFECT, call.menuId); assertEquals("xlsx", call.format)
        assertEquals(3, call.rowCnt); assertEquals(0, call.blindCnt)
        assertEquals("불량_유형별_분포_2026-09-01_2026-09-11.xlsx", call.fileNm)
        assertEquals(res.body!!.byteArray.size.toLong(), call.fileSize)
        assertTrue(call.scope!!.length <= 100)
        assertEquals("W120", call.params!!["processId"]); assertEquals("D01", call.params!!["defectTypeCd"])
    }

    @Test
    @DisplayName("by-line export — 전체 설비(topN 없음)로 조회하고 시트 4장(트리 포함), 이력의 행 수는 설비 수")
    fun byLineExportContract() {
        val res = controller.defectByLineExport(QualityDefectExportRequest(from = "2026-09-01", to = "2026-09-11", levels = "wc,item,eqpt"))
        assertEquals(200, res.statusCode.value())
        assertEquals(null, defects.lineTopN, "내려받기는 상위 N 이 아니라 전체 설비다")
        assertEquals("wc,item,eqpt", defects.treeLevels, "본문 levels 가 트리 조회로 전달된다")
        assertEquals("설비별_불량률_2026-09-01_2026-09-11.xlsx", filenameOf(res.headers.getFirst("Content-Disposition")!!))
        open(res.body!!.byteArray).use { wb ->
            assertEquals(4, wb.numberOfSheets)
            assertEquals(QualityDefectWorkbook.SHEET_TREE, wb.getSheetName(3))
            assertEquals(3, wb.getSheetAt(3).lastRowNum, "트리 3단계 = 3행")
        }
        assertEquals("wc,item,eqpt", downloadLog.calls.single().params!!["levels"])
        val call = downloadLog.calls.single()
        assertEquals("설비별 불량률", call.reportNm); assertEquals(2, call.rowCnt)
        assertEquals("전체", call.scope!!.substringAfter("processId="))
    }

    @Test
    @DisplayName("csv 는 400 이고 이력을 남기지 않는다 · 본문이 없으면 기본 기간으로 낸다")
    fun rejectsCsvAndDefaults() {
        assertThrows(InvalidParameterException::class.java) {
            controller.defectByTypeExport(QualityDefectExportRequest(format = "csv"))
        }
        assertTrue(downloadLog.calls.isEmpty())
        val res = controller.defectByLineExport(null)
        assertEquals(200, res.statusCode.value())
        assertTrue(filenameOf(res.headers.getFirst("Content-Disposition")!!).startsWith("설비별_불량률_"))
    }
}
