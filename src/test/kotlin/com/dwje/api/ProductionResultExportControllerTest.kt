package com.dwje.api

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.util.DataField
import com.dwje.api.common.util.MaskingSupport
import com.dwje.api.common.util.MenuId
import com.dwje.api.config.AppProperties
import com.dwje.api.controller.ProductionController
import com.dwje.api.model.request.ExportFormatRequest
import com.dwje.api.repository.DashboardAiRepository
import com.dwje.api.repository.DownloadLogRepository
import com.dwje.api.repository.ProductionRepository
import com.dwje.api.service.AuditLogService
import com.dwje.api.service.AuthorizationService
import com.dwje.api.service.DailyReportService
import com.dwje.api.service.DashboardAiService
import com.dwje.api.service.DayTargetService
import com.dwje.api.service.DowntimeService
import com.dwje.api.service.DownloadLogService
import com.dwje.api.service.ExportService
import com.dwje.api.service.ProductionResultScreenWorkbook
import com.dwje.api.service.ProductionService
import com.dwje.api.service.ResultScreenExport
import com.dwje.api.service.ResultScreenExportRow
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.http.HttpHeaders
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate

/**
 * `POST /production/results/export?scope=screen` 계약 — 컨트롤러가 하는 일을 고정한다.
 *
 * 1. scope=screen 은 xlsx 바이너리 + `Content-Disposition` 파일명으로 응답하고, 그 문서가 실제로 열린다
 * 2. 다운로드 이력이 남는다 — 형식·행 수·파일명·크기·조건 스냅샷(scope=screen)
 * 3. scope 미지정은 예전 동작(집계 표 한 장)을 그대로 탄다
 * 4. 잘못된 scope · csv · unit≠day 는 400 이다
 *
 * 서비스는 진짜 DB 없이 결과만 돌려주는 하위 클래스로 바꿔 끼운다(Spring 컨텍스트 없음).
 */
class ProductionResultExportControllerTest {

    /** 이력 호출을 그대로 받아 두는 DownloadLogService */
    private class RecordingDownloadLog : DownloadLogService(
        mock(DownloadLogRepository::class.java), mock(AuditLogService::class.java),
        mock(AuthorizationService::class.java), mock(AppProperties::class.java), ObjectMapper()
    ) {
        data class Call(
            val reportNm: String, val menuId: String?, val format: String, val scope: String?, val rowCnt: Int,
            val blindCnt: Int, val blindCells: Map<String, Int>, val fileNm: String?, val params: Map<String, Any?>?, val fileSize: Long?
        )
        val calls = mutableListOf<Call>()

        override fun record(
            reportId: String?, reportNm: String, menuId: String?, format: String, scope: String?, rowCnt: Int, blindCnt: Int,
            blindCells: Map<String, Int>, fileNm: String?, params: Map<String, Any?>?, fileSize: Long?,
            scopeCd: String, condSummary: String?
        ): Long {
            calls += Call(reportNm, menuId, format, scope, rowCnt, blindCnt, blindCells, fileNm, params, fileSize)
            return calls.size.toLong()
        }
    }

    /** DB 대신 준비한 값을 주는 ProductionService */
    private class StubProduction(private val screen: ResultScreenExport) : ProductionService(
        mock(ProductionRepository::class.java), mock(DashboardAiRepository::class.java), mock(DashboardAiService::class.java),
        mock(AuthorizationService::class.java), mock(AppProperties::class.java)
    ) {
        var legacyCalls = 0
        var screenArgs: Pair<String?, String?>? = null

        override fun getResultScreenExport(from: String?, to: String?): ResultScreenExport {
            screenArgs = from to to
            return screen
        }

        override fun getResultRowsForExport(
            from: String?, to: String?, unit: String?, itemCd: String?, modelCd: String?, lineCd: String?
        ): Pair<List<Map<String, Any?>>, MaskingSupport> {
            legacyCalls++
            return listOf(mapOf("period" to "2026-09-11", "inputQty" to 1L)) to MaskingSupport(null)
        }
    }

    private val screenData = ResultScreenExport(
        from = LocalDate.parse("2026-09-04"), to = LocalDate.parse("2026-09-11"),
        summary = mapOf("inputQty" to 1500L),
        // 수율(yield) 권한이 없는 사용자 — 서비스가 비율 값을 null 로 가려 넘긴다
        dayRows = listOf(mapOf("period" to "2026-09-11", "inputQty" to 1500L, "ngQty" to 50L, "defectRate" to null)),
        rows = listOf(
            ResultScreenExportRow(1, "2026-09-11", inputQty = 1500, okQty = 1450, ngQty = 50, defectRate = null),
            ResultScreenExportRow(2, "2026-09-11", productCd = "D63A", inputQty = 1500, okQty = 1450, ngQty = 50, defectRate = null),
            ResultScreenExportRow(3, "2026-09-11", productCd = "D63A", eqptCd = "MT-007", eqptNm = "프레스 7호", inputQty = 1500, okQty = 1450, ngQty = 50, defectRate = null)
        ),
        maskedFields = listOf(DataField.YIELD),
        downloadedBy = "관리자(10000)"
    )

    private val production = StubProduction(screenData)
    private val downloadLog = RecordingDownloadLog()
    private val controller = ProductionController(
        production, mock(DailyReportService::class.java), mock(DayTargetService::class.java), mock(DowntimeService::class.java),
        ExportService().also { export ->
            export.dataFieldService = mock(com.dwje.api.service.DataFieldService::class.java).also {
                org.mockito.Mockito.doReturn(mapOf("defectRate" to DataField.YIELD, "yield" to DataField.YIELD, "inputQty" to DataField.QTY))
                    .`when`(it).attrFieldMap()
            }
        }, downloadLog, ProductionResultScreenWorkbook()
    )

    @org.junit.jupiter.api.BeforeEach
    fun login() = com.dwje.api.common.security.UserContext.set(
        com.dwje.api.common.security.UserPrincipal("10003", "제조", 4, "제조팀", null, null, null, false, dataPerms = setOf(DataField.QTY))
    )

    @org.junit.jupiter.api.AfterEach
    fun logout() = com.dwje.api.common.security.UserContext.clear()

    @Test
    @DisplayName("scope=screen — xlsx 바이너리 · 파일명 · 3장짜리 문서, 다운로드 이력에 조건과 크기가 남는다")
    fun screenExport() {
        val res = controller.resultsExport(
            ExportFormatRequest(format = "xlsx", from = "2026-09-04", to = "2026-09-11"),
            scope = "screen", unit = "day", itemCd = null, modelCd = null, lineCd = null
        )

        assertEquals(200, res.statusCode.value())
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", res.headers.contentType.toString())
        val disposition = res.headers.getFirst(HttpHeaders.CONTENT_DISPOSITION) ?: ""
        assertTrue(disposition.startsWith("attachment"), disposition)
        val encoded = Regex("filename\\*=UTF-8''([^;]+)").find(disposition)?.groupValues?.get(1)
        assertEquals("실적_집계_전체_2026-09-04_2026-09-11.xlsx", URLDecoder.decode(encoded, StandardCharsets.UTF_8))
        assertEquals("2026-09-04" to "2026-09-11", production.screenArgs)
        assertEquals(0, production.legacyCalls, "scope=screen 은 예전 경로를 타지 않는다")

        val bytes = res.body!!.byteArray
        assertEquals(bytes.size.toLong(), res.headers.contentLength)
        XSSFWorkbook(ByteArrayInputStream(bytes)).use { wb ->
            // 요약·추이·트리 3장 + 가린 칸이 있어 「안내」 (R-10)
            assertEquals(4, wb.numberOfSheets)
            assertEquals(ProductionResultScreenWorkbook.SHEET_TREE, wb.getSheetName(2))
            assertEquals("비공개 처리 6건(데이터 접근 권한 기준)", wb.getSheet("안내").getRow(0).getCell(0).stringCellValue)
            assertEquals(3, wb.getSheetAt(2).lastRowNum, "트리 행 3개")
            assertEquals("비공개", wb.getSheetAt(2).getRow(1).getCell(9).stringCellValue, "가린 불량률 칸")
            assertEquals(1500.0, wb.getSheetAt(2).getRow(1).getCell(6).numericCellValue, "볼 수 있는 수량은 그대로")
        }

        val call = downloadLog.calls.single()
        assertEquals("생산 실적 집계(화면 전체)", call.reportNm)
        assertEquals(MenuId.PROD_RESULT, call.menuId)
        assertEquals("XLSX", call.format)
        assertEquals(3, call.rowCnt)
        // 「비공개」 로 실제 채운 칸 — 트리 불량률 3 + 일별 추이 불량률 1 + 요약 불량률·수율 2 (R-10)
        assertEquals(6, call.blindCnt, "비공개 건수는 실제로 채운 칸 수")
        assertEquals(mapOf(DataField.YIELD to 6), call.blindCells)
        assertEquals("실적_집계_전체_2026-09-04_2026-09-11.xlsx", call.fileNm)
        assertEquals(bytes.size.toLong(), call.fileSize)
        assertTrue((call.scope ?: "").length <= 100, "scope_desc 컬럼은 100자다")
        assertTrue(call.scope!!.startsWith("screen "))
        val params = call.params!!
        assertEquals("screen", params["scope"])
        assertEquals("day", params["unit"])
        assertEquals("2026-09-04", params["from"])
        assertEquals("2026-09-11", params["to"])
        assertEquals(1, params["dayRows"]); assertEquals(1, params["productRows"]); assertEquals(1, params["equipmentRows"])
    }

    @Test
    @DisplayName("scope 미지정 — 예전 집계 표 내려받기 그대로")
    fun legacyPathUnchanged() {
        val res = controller.resultsExport(
            ExportFormatRequest(format = "xlsx", from = "2026-09-04", to = "2026-09-11"),
            scope = null, unit = "day", itemCd = null, modelCd = null, lineCd = null
        )
        assertEquals(200, res.statusCode.value())
        assertEquals(1, production.legacyCalls)
        XSSFWorkbook(ByteArrayInputStream(res.body!!.byteArray)).use { wb ->
            // 표 1장 + 가린 칸 안내 — 수율 권한이 없어 불량률·수율 열이 「비공개」(R-10)
            assertEquals(listOf("Sheet1", "안내"), (0 until wb.numberOfSheets).map { wb.getSheetName(it) })
            assertEquals("비공개", wb.getSheetAt(0).getRow(1).getCell(4).stringCellValue)
        }
        assertEquals(2, downloadLog.calls.single().blindCnt, "불량률·수율 1행씩")
        assertEquals("생산 실적 집계", downloadLog.calls.single().reportNm)
    }

    @Test
    @DisplayName("scope 오타 · csv · unit≠day 는 400 이고 이력을 남기지 않는다")
    fun rejects() {
        val body = ExportFormatRequest(format = "xlsx", from = "2026-09-04", to = "2026-09-11")
        assertThrows(InvalidParameterException::class.java) {
            controller.resultsExport(body, scope = "page", unit = "day", itemCd = null, modelCd = null, lineCd = null)
        }
        assertThrows(InvalidParameterException::class.java) {
            controller.resultsExport(body.copy(format = "csv"), scope = "screen", unit = "day", itemCd = null, modelCd = null, lineCd = null)
        }
        assertThrows(InvalidParameterException::class.java) {
            controller.resultsExport(body, scope = "screen", unit = "week", itemCd = null, modelCd = null, lineCd = null)
        }
        assertTrue(downloadLog.calls.isEmpty())
        assertEquals(0, production.legacyCalls)
    }
}
