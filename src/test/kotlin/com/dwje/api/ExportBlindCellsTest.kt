package com.dwje.api

import com.dwje.api.common.security.UserContext
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.config.AppProperties
import com.dwje.api.repository.AlertConfigRepository
import com.dwje.api.service.AlertTargetResolver
import com.dwje.api.service.BlindCells
import com.dwje.api.service.DataFieldService
import com.dwje.api.service.ExportService
import com.dwje.api.service.TargetGroupRow
import com.dwje.api.service.TargetMemberRow
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.time.LocalTime

/** 3단계 메인 결정 — 서버 생성 파일의 가린 칸은 「비공개」(R-10), 잠긴 계정도 알림 수신 */
class ExportBlindCellsTest {

    @AfterEach
    fun clear() = UserContext.clear()

    private fun exportService(): ExportService {
        val fields = mock(DataFieldService::class.java)
        doReturn(mapOf("okQty" to "qty", "ngQty" to "qty", "unitPrice" to "price")).`when`(fields).attrFieldMap()
        return ExportService().also { it.dataFieldService = fields }
    }

    @Test
    @DisplayName("권한 없는 항목 열의 null 은 비공개로 채우고 센다, 원래 빈 다른 열은 빈칸, 안내 시트에 건수")
    fun fillsMaskedCells() {
        UserContext.set(UserPrincipal("10001", "품질", 2, "품질보증팀", null, null, false, dataPerms = setOf("price")))
        val service = exportService()
        val blind = service.blindCells()
        val rows = listOf(
            mapOf("day" to "2026-09-01", "okQty" to null, "ngQty" to null, "unitPrice" to null, "memo" to null),
            mapOf("day" to "2026-09-02", "okQty" to null, "ngQty" to null, "unitPrice" to 10, "memo" to "x")
        )
        val file = service.excel("t", listOf("일", "양품", "불량", "단가", "메모"), listOf("day", "okQty", "ngQty", "unitPrice", "memo"), rows, blind = blind)
        assertEquals(4, blind.total)
        assertEquals(mapOf("qty" to 4), blind.counts())
        XSSFWorkbook(file.body!!.inputStream).use { wb ->
            val r1 = wb.getSheetAt(0).getRow(1)
            assertEquals(BlindCells.MASK, r1.getCell(1).stringCellValue)
            assertEquals("", r1.getCell(3).stringCellValue) // 단가는 볼 수 있는 항목 — 원래 빈 값
            assertEquals("", r1.getCell(4).stringCellValue)
            assertEquals("비공개 처리 4건(데이터 접근 권한 기준)", wb.getSheet("안내").getRow(0).getCell(0).stringCellValue)
        }
    }

    @Test
    @DisplayName("DTP-09 — 볼 수 없는 항목 열은 값이 있어도 비공개(운영 추가 항목은 서비스가 null 로 만들지 않는다), 통합 문서 텍스트 열도")
    fun masksValuesToo() {
        UserContext.set(UserPrincipal("10001", "품질", 2, "품질보증팀", null, null, false, dataPerms = setOf("qty")))
        val fields = mock(DataFieldService::class.java)
        doReturn(mapOf("defectType" to "f_defect", "okQty" to "qty")).`when`(fields).attrFieldMap()
        doReturn(listOf(mapOf<String, Any?>("key" to "f_defect", "name" to "불량 유형명"))).`when`(fields).appliedFieldsCached()
        val service = ExportService().also { it.dataFieldService = fields }

        val blind = service.blindCells()
        val file = service.excel("t", listOf("유형", "양품"), listOf("defectType", "okQty"),
            listOf(mapOf("defectType" to "찍힘", "okQty" to 10)), blind = blind)
        XSSFWorkbook(file.body!!.inputStream).use { wb ->
            assertEquals(BlindCells.MASK, wb.getSheetAt(0).getRow(1).getCell(0).stringCellValue)
            assertEquals(10.0, wb.getSheetAt(0).getRow(1).getCell(1).numericCellValue)
            assertEquals("항목별: 불량 유형명 1건", wb.getSheet("안내").getRow(1).getCell(0).stringCellValue)
        }
        assertEquals(1, blind.total)

        val wbBlind = service.blindCells()
        val bytes = com.dwje.api.service.QualityDefectWorkbook().byType(
            com.dwje.api.service.DefectExportConditions(java.time.LocalDate.now(), java.time.LocalDate.now(), null, null, emptyList(), null),
            listOf(mapOf("defectCd" to "D01", "defectType" to "찍힘", "cnt" to 3, "ratio" to 100.0)), wbBlind
        )
        XSSFWorkbook(bytes.inputStream()).use { wb ->
            val sheet = (0 until wb.numberOfSheets).map { wb.getSheetAt(it) }.first { it.getRow(0)?.getCell(1)?.stringCellValue?.contains("유형") == true && it.lastRowNum >= 1 }
            assertEquals(BlindCells.MASK, sheet.getRow(1).getCell(1).stringCellValue)
            assertTrue(wb.getSheet("안내") != null)
        }
        assertEquals(1, wbBlind.total)
    }

    @Test
    @DisplayName("통합관리자·비로그인은 가리지 않는다 — 안내 시트는 0건으로 쓴다")
    fun superAdminNoMask() {
        UserContext.set(UserPrincipal("10000", "관리자", 1, "통합관리자", null, null, true))
        val service = exportService()
        val blind = service.blindCells()
        val file = service.excel("t", listOf("양품"), listOf("okQty"), listOf(mapOf("okQty" to null)), blind = blind)
        assertEquals(0, blind.total)
        // 0건이어도 안내를 쓴다(공통 10.6, 4단계)
        XSSFWorkbook(file.body!!.inputStream).use { assertEquals("비공개 처리 0건(데이터 접근 권한 기준)", it.getSheet("안내").getRow(0).getCell(0).stringCellValue) }
        UserContext.clear()
        assertEquals(0, ExportService().blindCells().also { it.fill("okQty", null) }.total)
    }

    @Test
    @DisplayName("CSV 도 비공개로 채우고 마지막 줄에 안내")
    fun csv() {
        UserContext.set(UserPrincipal("10001", "품질", 2, "품질보증팀", null, null, false))
        val service = exportService()
        val text = String(service.csv("t", listOf("양품"), listOf("okQty"), listOf(mapOf("okQty" to null))).body!!.byteArray)
        assertTrue(text.contains("비공개\r\n"))
        assertTrue(text.trimEnd().endsWith("비공개 처리 1건(데이터 접근 권한 기준)"))
    }

    @Test
    @DisplayName("잠긴(LOCKED) 계정도 알림 대상, 정지·승인 대기는 제외 (기본 설정)")
    fun lockedReceives() {
        val resolver = AlertTargetResolver(AlertConfigRepository(mock(NamedParameterJdbcTemplate::class.java)), AppProperties())
        fun m(id: String, state: String) = TargetMemberRow(1, id, id, "부서", state, state, "RECV", "$id@x", null, null, false)
        val r = resolver.evaluate(
            listOf(TargetGroupRow(1, "G", true, false, listOf("MAIL"))),
            mapOf(1 to listOf(m("A", "ACTIVE"), m("L", "LOCKED"), m("S", "SUSPENDED"), m("P", "PENDING"))),
            listOf("MAIL"), LocalTime.NOON
        )
        assertEquals(listOf("A", "L"), r.targets.map { it.userId })
        assertEquals(setOf("S", "P"), r.skipped.filter { it.reason == "ACCOUNT_INACTIVE" }.map { it.empNo }.toSet())
    }
}
