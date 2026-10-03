package com.dwje.api

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.security.UserContext
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.common.util.CustomerFilterGuard
import com.dwje.api.common.util.DataField
import com.dwje.api.common.util.MenuId
import com.dwje.api.service.BlindCells
import com.dwje.api.service.CommonMasterService
import com.dwje.api.service.DataFieldService
import com.dwje.api.service.DayTargetService
import com.dwje.api.service.DefectExportConditions
import com.dwje.api.service.QualityDefectService
import com.dwje.api.service.QualityDefectWorkbook
import com.dwje.api.service.ReportService
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.annotation.Transactional
import java.io.ByteArrayInputStream
import java.time.LocalDate

/**
 * 데이터 접근 권한 누출 수정(2026-10-03) — 값만 가리고 행·표는 남긴다, 고객사 조건으로 짐작 못 하게 한다.
 *
 * 로컬 DB 의 시험 데이터(ins_user='DPTEST' — LRR 통보·출하 계획·PRESS 일목표)를 읽기만 한다(지우지 않음).
 * 권한은 DB 표(tb_sys_dept_data_perm)를 바꾸지 않고 시험 사용자([UserPrincipal.dataPerms])로 정한다. 롤백, 감사는 목.
 */
@SpringBootTest(properties = ["app.ai.prewarm-enabled=false"])
@ActiveProfiles("local")
@Transactional
class DataPermLeakFixTest {

    @Autowired lateinit var reports: ReportService
    @Autowired lateinit var dayTargets: DayTargetService
    @Autowired lateinit var masters: CommonMasterService
    @Autowired lateinit var quality: QualityDefectService
    @Autowired lateinit var dataFields: DataFieldService
    @MockitoBean lateinit var audit: com.dwje.api.service.AuditLogService

    private val screens = setOf(MenuId.RPT_LRR_CUSTOMER, MenuId.RPT_SHIP_PLAN, MenuId.PROD_DAILY, MenuId.QC_DEFECT)
    private val all = DataField.ALL.toSet()

    /** 생산관리팀 같은 일반 사용자 — [denied] 만 뺀 데이터 권한 */
    private fun login(vararg denied: String) = UserContext.set(
        UserPrincipal("10002", "시험", 3, "생산관리팀", null, null, false, menuPerms = screens, dataPerms = all - denied.toSet())
    )

    @AfterEach fun clear() = UserContext.clear()

    @Suppress("UNCHECKED_CAST")
    private fun rows(data: Map<String, Any?>, key: String) = data[key] as List<Map<String, Any?>>

    @Test
    @DisplayName("1. 고객사별 LRR — 고객사 권한이 없으면 byCustomerMonth[].label 도 null(행은 그대로), 있으면 이름")
    fun lrrLabel() {
        login()
        val open = rows(reports.getLrrByCustomer(2026, null, "month").first, "byCustomerMonth")
        assertTrue(open.any { it["label"] == "시험고객사A" }, open.toString().take(300))

        login(DataField.CUSTOMER)
        val (data, mask) = reports.getLrrByCustomer(2026, null, "month")
        val hidden = rows(data, "byCustomerMonth")
        assertEquals(open.size, hidden.size, "행 수는 같다")
        assertTrue(hidden.all { it["label"] == null })
        assertTrue(rows(data, "byCustomer").all { it["customer"] == null })
        assertTrue(DataField.CUSTOMER in mask.maskedKeys())
        assertFalse(data.toString().contains("시험고객사"))
    }

    @Test
    @DisplayName("2. 일목표 — 수량 권한이 없으면 targetQty 만 null + masked qty, 행(제품·공정·적용일)은 그대로")
    fun dayTargetQty() {
        login()
        val (open, _, openMask) = dayTargets.getTargets(null, null, null, 1, 0)
        assertTrue(open.any { it["targetQty"] != null }); assertFalse(DataField.QTY in openMask.maskedKeys())

        login(DataField.QTY)
        val (hidden, meta, mask) = dayTargets.getTargets(null, null, null, 1, 0)
        assertEquals(open.size, hidden.size); assertEquals(open.size.toLong(), meta.total)
        assertTrue(hidden.all { it.containsKey("targetQty") && it["targetQty"] == null })
        assertTrue(hidden.all { it["product"] != null })
        assertTrue(DataField.QTY in mask.maskedKeys())
    }

    @Test
    @DisplayName("3. 고객사 조건 — 고객사 권한이 없으면 customerCd 를 주는 순간 400(출하 계획·LRR·제품 목록), 권한이 있으면 그대로 거른다")
    fun customerFilter() {
        login(DataField.CUSTOMER)
        listOf<() -> Unit>(
            { reports.getShipPlan(2026, null, "TSTA", null) },
            { reports.getLrrByCustomer(2026, "TSTA", null) },
            { masters.getProducts(null, null, "TSTA", null, null, 1, 20) }
        ).forEach { call ->
            val e = assertThrows(InvalidParameterException::class.java) { call() }
            assertEquals("customerCd", e.field); assertEquals(CustomerFilterGuard.MESSAGE, e.message)
        }
        // 조건이 없으면 그대로 열린다(값만 가림)
        assertNotNull(reports.getShipPlan(2026, null, null, null).first["rows"])
        assertNotNull(reports.getShipPlan(2026, null, " ", null).first["rows"], "빈 문자열은 조건이 아니다")

        login()
        val filtered = rows(reports.getShipPlan(2026, null, "TSTA", null).first, "rows")
        assertTrue(filtered.isNotEmpty() && filtered.all { it["customer"] == "시험고객사A" })
    }

    @Test
    @DisplayName("4-1. 출하 계획 — 계획 권한이 없어도 행(모델·고객사)은 남고 values·total·월 합계·최다 월만 null")
    fun shipPlanRows() {
        login()
        val open = reports.getShipPlan(2026, null, null, null).first
        val openRows = rows(open, "rows")
        assertTrue(openRows.isNotEmpty())

        login(DataField.PLAN)
        val (data, mask) = reports.getShipPlan(2026, null, null, null)
        val hidden = rows(data, "rows")
        assertEquals(openRows.map { it["model"] }, hidden.map { it["model"] }, "행(모델)은 그대로 — 예전에는 0행")
        hidden.forEach { r ->
            @Suppress("UNCHECKED_CAST")
            assertTrue((r["values"] as List<Any?>).all { it == null }); assertNull(r["total"])
        }
        @Suppress("UNCHECKED_CAST")
        assertTrue((data["monthTotals"] as List<Any?>).all { it == null })
        assertNull(data["grandTotal"]); assertNull(data["peakMonth"])
        assertTrue(DataField.PLAN in mask.maskedKeys())

        login(DataField.PRICE)
        val amount = rows(reports.getShipPlan(2026, null, null, "amount").first, "rows")
        assertEquals(openRows.size, amount.size, "금액 모드 + 단가 권한 없음도 행은 남긴다")
    }

    @Test
    @DisplayName("4-2. 불량 현황 — 수율 권한이 없어도 유형·설비·수량 행은 남고 불량률·비중만 null (예전에는 items=[])")
    fun qualityRows() {
        val from = "2026-09-01"; val to = "2026-09-11"
        login()
        val openType = rows(quality.getByType(from, to, null).first, "items")
        val openLine = rows(quality.getByLine(from, to, null, null).first, "items")
        assertTrue(openType.isNotEmpty() && openLine.isNotEmpty())

        login(DataField.YIELD)
        val type = rows(quality.getByType(from, to, null).first, "items")
        assertEquals(openType.size, type.size)
        assertTrue(type.all { it["ratio"] == null }); assertTrue(type.any { it["cnt"] != null }, "불량 수량은 남는다")
        val line = rows(quality.getByLine(from, to, null, null).first, "items")
        assertEquals(openLine.size, line.size)
        assertTrue(line.all { it["defectRate"] == null }); assertTrue(line.any { it["ngQty"] != null })
        @Suppress("UNCHECKED_CAST")
        assertTrue(line.flatMap { (it["children"] as? List<Map<String, Any?>>).orEmpty() }.all { it["ratio"] == null })
        val tree = quality.getDefectTree(from, to, null, null).first
        assertTrue(rows(tree, "items").isNotEmpty())
        @Suppress("UNCHECKED_CAST")
        assertNull((tree["totals"] as Map<String, Any?>)["defectRate"])
        val product = quality.getByProduct(from, to, null).first
        assertTrue(rows(product, "items").isNotEmpty())
    }

    @Test
    @DisplayName("4-3. 불량 현황 엑셀 — 비중·불량률 열만 「비공개」, 유형·수량 열은 값, 비공개 건수 = 실제 가린 칸 수")
    fun qualityWorkbook() {
        val items = listOf(
            mapOf("defectCd" to "D1", "defectType" to "스크래치", "cnt" to 10L, "ratio" to null),
            mapOf("defectCd" to "D2", "defectType" to "찍힘", "cnt" to 5L, "ratio" to null)
        )
        val blind = BlindCells(mapOf("defectRate" to DataField.YIELD) + QualityDefectWorkbook.EXTRA_ATTRS)
        val cond = DefectExportConditions(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-11"), null, null, listOf(DataField.YIELD), "시험")
        val bytes = QualityDefectWorkbook().byType(cond, items, blind)
        XSSFWorkbook(ByteArrayInputStream(bytes)).use { wb ->
            val sheet = wb.getSheet(QualityDefectWorkbook.SHEET_BY_TYPE)
            assertEquals("스크래치", sheet.getRow(1).getCell(1).stringCellValue)
            assertEquals(10.0, sheet.getRow(1).getCell(2).numericCellValue)
            assertEquals(BlindCells.MASK, sheet.getRow(1).getCell(3).stringCellValue)
            assertEquals(BlindCells.MASK, sheet.getRow(2).getCell(3).stringCellValue)
            assertEquals("비공개 처리 2건(데이터 접근 권한 기준)", wb.getSheet("안내").getRow(0).getCell(0).stringCellValue)
        }
        assertEquals(2, blind.total); assertEquals(mapOf(DataField.YIELD to 2), blind.counts())
    }

    @Test
    @DisplayName("5. AI 근거 문서 — 고객사 권한이 없으면 제목 앞 대괄호·본문 속 고객사 이름을 가린다, 있으면 그대로")
    fun docTitleCustomer() {
        val (title, cnt) = dataFields.maskCustomerNames("[Cowell] 260310_MEM_SC stain 1st draft Cowell向 전면얼룩 검사 결과")
        assertEquals("[비공개] 260310_MEM_SC stain 1st draft 비공개向 전면얼룩 검사 결과", title); assertEquals(2, cnt)
        assertEquals("[비공개] 269999_MEM-BF Cu-exposure FACA 비공개 MEM-BF", dataFields.maskCustomerNames("[LGIT CM] 269999_MEM-BF Cu-exposure FACA LGIT MEM-BF").first)
        assertEquals("시험고객사 없는 문장 Sharpness", dataFields.maskCustomerNames("시험고객사 없는 문장 Sharpness").first, "다른 낱말의 일부는 두지 않는다")

        val noCustomer = UserPrincipal("10002", "시험", 3, "생산관리팀", null, null, false, dataPerms = all - DataField.CUSTOMER)
        val hit = mutableMapOf<String, Any?>("title" to "[Sharp] 출하 검사 보고서", "heading" to "Sharp 요청 사항", "snippet" to "본문")
        assertTrue(dataFields.maskHit(hit, noCustomer) >= 2)
        assertEquals("[비공개] 출하 검사 보고서", hit["title"]); assertEquals("비공개 요청 사항", hit["heading"])

        val withCustomer = UserPrincipal("10002", "시험", 3, "생산관리팀", null, null, false, dataPerms = all)
        val open = mutableMapOf<String, Any?>("title" to "[Sharp] 출하 검사 보고서", "snippet" to "본문")
        dataFields.maskHit(open, withCustomer)
        assertEquals("[Sharp] 출하 검사 보고서", open["title"])
    }
}
