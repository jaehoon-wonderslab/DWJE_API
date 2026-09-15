package com.dwje.api

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.util.DefectSql
import com.dwje.api.repository.DefectTreeBaseRow
import com.dwje.api.repository.DefectTreeTypeRow
import com.dwje.api.service.DefectExportConditions
import com.dwje.api.service.DefectTreeAssembler
import com.dwje.api.service.DefectTreeLevel
import com.dwje.api.service.QualityDefectWorkbook
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.time.LocalDate

/**
 * 불량 상세 분해 트리 — 조립 규약과 엑셀 시트.
 *
 * 1. 모든 단계 행에 차원 열 10개가 있고 그 단계까지 확정된 값만 채운다
 * 2. 상위 수량 = 하위 합. 유형 행은 ngQty·ratio 만 있고 합이 상위 ngQty 와 정확히 맞는다(미상·반올림 보정)
 * 3. levels 순서를 바꾸면 같은 잎 자료로 다른 트리가 나오고, 총합은 같다
 * 4. levels 검증 — 모르는 이름·중복·defect 가 마지막이 아니면 400
 */
class QualityDefectTreeTest {

    private val base = listOf(
        DefectTreeBaseRow("W120", "PRESS(M-1공장)", "MT-001", "프레스 1호", "D63A-S", "D63A 프레스", 900, 100),
        DefectTreeBaseRow("W120", "PRESS(M-1공장)", "MT-002", "프레스 2호", "D63A-S", "D63A 프레스", 950, 50),
        DefectTreeBaseRow("W120", "PRESS(M-1공장)", "MT-002", "프레스 2호", "X99-S", "X99 프레스", 500, 0),
        DefectTreeBaseRow("W150", "A-PLATING(선별-출하)", null, null, "D63A-YP", "D63A 도금", 1000, 10)
    )
    private fun t(wc: String, eqpt: String?, item: String, cd: String, nm: String, ng: String) =
        DefectTreeTypeRow(wc, eqpt, item, cd, nm, BigDecimal(ng))
    private val types = listOf(
        t("W120", "MT-001", "D63A-S", "DF021", "얼룩", "60.4"),
        t("W120", "MT-001", "D63A-S", "DF003", "스크래치", "30.3"),   // 합 90.7 → 미상 9
        t("W120", "MT-002", "D63A-S", "DF021", "얼룩", "49.6"),      // 반올림 50 = 전량
        t("W150", null, "D63A-YP", "DF021", "얼룩", "10.6")          // 반올림 11 > 10 → 가장 큰 유형에서 보정
    )

    @Suppress("UNCHECKED_CAST")
    private fun children(n: Map<String, Any?>) = (n["children"] as? List<Map<String, Any?>>).orEmpty()

    @Test
    @DisplayName("기본 순서 공정 > 제품 > 설비 > 유형 — 차원 열·합·정렬·미상 보정")
    fun defaultOrder() {
        val tree = DefectTreeAssembler.assemble(DefectTreeLevel.DEFAULT, base, types, "PL01")

        assertEquals(listOf("W120", "W150"), tree.map { it["wcCd"] }, "불량 수량 내림차순")
        val w120 = tree[0]
        assertEquals("wc", w120["level"]); assertEquals("PL01", w120["plantCd"]); assertEquals("M-1공장", w120["plantNm"])
        assertEquals("PRESS(M-1공장)", w120["wcNm"])
        assertNull(w120["itemCd"]); assertNull(w120["eqptCd"]); assertNull(w120["defectCd"], "그 단계까지 확정된 값만")
        assertEquals(2350L, w120["okQty"]); assertEquals(150L, w120["ngQty"]); assertEquals(6.0, w120["defectRate"])
        assertTrue(w120.containsKey("ratio") && w120["ratio"] == null, "수량 단계의 ratio 는 null")

        val items = children(w120)
        assertEquals(listOf("D63A-S", "X99-S"), items.map { it["itemCd"] })
        val d63 = items[0]
        assertEquals("item", d63["level"]); assertEquals("W120", d63["wcCd"]); assertEquals("D63A 프레스", d63["itemNm"]); assertNull(d63["eqptCd"])
        assertEquals(1850L, d63["okQty"]); assertEquals(150L, d63["ngQty"])
        assertEquals(150L, children(d63).sumOf { it["ngQty"] as Long }, "제품 = 설비 합")

        val eqpts = children(d63)
        assertEquals(listOf("MT-001", "MT-002"), eqpts.map { it["eqptCd"] })
        val mt1 = eqpts[0]
        assertEquals("eqpt", mt1["level"]); assertEquals("D63A-S", mt1["itemCd"]); assertEquals("프레스 1호", mt1["eqptNm"])
        assertEquals(10.0, mt1["defectRate"])

        val defects = children(mt1)
        assertEquals(listOf("얼룩", "스크래치", DefectSql.UNTYPED_LABEL), defects.map { it["defectNm"] })
        assertEquals(listOf(60L, 30L, 10L), defects.map { it["ngQty"] }, "반올림 60·30, 미상 = 100 − 90")
        assertEquals(listOf(60.0, 30.0, 10.0), defects.map { it["ratio"] })
        assertEquals("defect", defects[0]["level"]); assertEquals("DF021", defects[0]["defectCd"]); assertEquals("MT-001", defects[0]["eqptCd"])
        assertNull(defects[0]["okQty"]); assertNull(defects[0]["defectRate"], "유형 행에 정상 수량·불량률은 없다")
        assertNull(defects[2]["defectCd"], "유형 미상은 코드가 없다")
        assertTrue(defects.none { it.containsKey("children") })

        // 불량 0 인 설비는 유형 자식이 없다 → children 키 자체가 없다
        val x99eqpt = children(items[1])[0]
        assertEquals("MT-002", x99eqpt["eqptCd"]); assertEquals(0L, x99eqpt["ngQty"]); assertTrue(!x99eqpt.containsKey("children"))

        // 설비가 없는 라벨은 eqptCd null 행으로 남고, 반올림이 넘치면(11 > 10) 가장 큰 유형에서 덜어 합을 맞춘다
        val w150 = tree[1]
        val plating = children(children(w150)[0])[0]
        assertNull(plating["eqptCd"]); assertNull(w150["plantNm"], "공장 표기가 없는 작업장은 공장이 비어 있다")
        val pd = children(plating)
        assertEquals(listOf(10L), pd.map { it["ngQty"] }); assertEquals(100.0, pd[0]["ratio"])
    }

    @Test
    @DisplayName("levels 로 순서를 바꾸면 같은 자료로 다른 트리, 총합은 같다 · 유형을 빼면 잎이 설비다")
    fun alternativeOrders() {
        val byEqpt = DefectTreeAssembler.assemble(DefectTreeLevel.parse("wc,eqpt,item,defect"), base, types, "PL01")
        val w120 = byEqpt[0]
        assertEquals(listOf("MT-001", "MT-002"), children(w120).map { it["eqptCd"] })
        val mt2 = children(w120)[1]
        assertEquals("eqpt", mt2["level"]); assertNull(mt2["itemCd"])
        assertEquals(1450L, mt2["okQty"]); assertEquals(50L, mt2["ngQty"])
        assertEquals(listOf("D63A-S", "X99-S"), children(mt2).map { it["itemCd"] })
        assertEquals(150L, w120["ngQty"], "총합은 순서와 무관")

        val noDefect = DefectTreeAssembler.assemble(DefectTreeLevel.parse("wc,item,eqpt"), base, emptyList(), "PL01")
        val leaf = children(children(noDefect[0])[0])[0]
        assertEquals("eqpt", leaf["level"]); assertTrue(!leaf.containsKey("children"))

        val eqptOnly = DefectTreeAssembler.assemble(DefectTreeLevel.parse("eqpt,defect"), base, types, "PL01")
        assertEquals(listOf("MT-001", "MT-002", null), eqptOnly.map { it["eqptCd"] })
        assertNull(eqptOnly[0]["wcCd"], "공정 단계를 건너뛰면 공정은 확정되지 않는다")
        assertEquals(100L, children(eqptOnly[0]).sumOf { it["ngQty"] as Long })
    }

    @Test
    @DisplayName("levels 검증 — 비면 기본, 모르는 이름·중복·defect 위치 오류는 400")
    fun parseLevels() {
        assertEquals(DefectTreeLevel.DEFAULT, DefectTreeLevel.parse(null))
        assertEquals(DefectTreeLevel.DEFAULT, DefectTreeLevel.parse(" "))
        assertEquals(listOf(DefectTreeLevel.WC, DefectTreeLevel.EQPT), DefectTreeLevel.parse(" WC , eqpt "))
        assertThrows(InvalidParameterException::class.java) { DefectTreeLevel.parse("wc,line") }
        assertThrows(InvalidParameterException::class.java) { DefectTreeLevel.parse("wc,wc,defect") }
        assertThrows(InvalidParameterException::class.java) { DefectTreeLevel.parse("defect,eqpt") }
    }

    @Test
    @DisplayName("수량 마스킹 — 모든 단계의 okQty·ngQty 가 비고 비율은 남는다")
    fun maskQty() {
        val masked = DefectTreeAssembler.maskQty(DefectTreeAssembler.assemble(DefectTreeLevel.DEFAULT, base, types, "PL01"))
        val leaf = children(children(children(masked[0])[0])[0])[0]
        assertNull(masked[0]["ngQty"]); assertNull(leaf["ngQty"])
        assertEquals(6.0, masked[0]["defectRate"]); assertEquals(60.0, leaf["ratio"])
    }

    @Test
    @DisplayName("by-line 엑셀에 '불량 상세 분해' 시트 — 14열, 깊이별 행 그룹, 단계 이름은 한글")
    fun treeSheet() {
        val tree = DefectTreeAssembler.assemble(DefectTreeLevel.DEFAULT, base, types, "PL01")
        val cond = DefectExportConditions(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-11"), null, null, emptyList(), "관리자(10000)")
        val bytes = QualityDefectWorkbook().byLine(cond, emptyList(), DefectTreeLevel.DEFAULT, tree)
        XSSFWorkbook(ByteArrayInputStream(bytes)).use { wb ->
            assertEquals(QualityDefectWorkbook.SHEET_TREE, wb.getSheetName(3))
            val s = wb.getSheet(QualityDefectWorkbook.SHEET_TREE) as XSSFSheet
            assertEquals(QualityDefectWorkbook.TREE_HEADERS, (0 until 14).map { s.getRow(0).getCell(it).stringCellValue })
            val flat = DefectTreeAssembler.flatten(tree)
            assertEquals(flat.size, s.lastRowNum)
            // 1행 공정, 2행 제품, 3행 설비, 4행 유형 — 깊이가 outline 으로
            assertEquals("공정", s.getRow(1).getCell(0).stringCellValue); assertEquals(0, s.getRow(1).ctRow.outlineLevel.toInt())
            assertEquals("제품", s.getRow(2).getCell(0).stringCellValue); assertEquals(1, s.getRow(2).ctRow.outlineLevel.toInt())
            assertEquals("설비", s.getRow(3).getCell(0).stringCellValue); assertEquals(2, s.getRow(3).ctRow.outlineLevel.toInt())
            assertEquals("불량 유형", s.getRow(4).getCell(0).stringCellValue); assertEquals(3, s.getRow(4).ctRow.outlineLevel.toInt())
            assertEquals("M-1공장", s.getRow(1).getCell(3).stringCellValue)
            assertEquals(150.0, s.getRow(1).getCell(11).numericCellValue)
            assertEquals(CellType.BLANK, s.getRow(4).getCell(10).cellType, "유형 행의 정상 수량은 빈 칸")
            assertEquals(60.0, s.getRow(4).getCell(13).numericCellValue, "유형 비중")
            val c = wb.getSheet(QualityDefectWorkbook.SHEET_CONDITIONS)
            val r = (1..c.lastRowNum).first { c.getRow(it).getCell(0).stringCellValue == "불량 상세 분해 단계" }
            assertEquals("공정 > 제품 > 설비 > 불량 유형", c.getRow(r).getCell(1).stringCellValue)
        }
    }
}
