package com.dwje.api

import com.dwje.api.common.util.DefectSql
import com.dwje.api.repository.DefectTreeBaseRow
import com.dwje.api.repository.DefectTreeTypeRow
import com.dwje.api.service.ProductDefectTreeAssembler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * 제품별 불량 현황 트리(제품 > 유형 > 설비 > 공정) — 합계·분모 규약.
 *
 * 1. ngQty 는 모든 단계에서 상위 = 하위 합(유형 미상 포함, 반올림 잔차 보정)
 * 2. totalQty 는 제품 행만 원장 총량이고, 유형 이하는 그 단계의 분모(형제마다 되풀이) — okQty 는 null
 * 3. defectRate = ngQty ÷ 그 단계 분모, ratio = 상위 불량 대비(제품은 전체 불량 대비)
 * 4. 유형 미상도 설비·공정으로 펼쳐지고, 마스킹은 수량 3종만 비운다
 */
class QualityDefectByProductTest {

    // 제품 A: 설비 E1(공정 W1) 900/100, 설비 E2(공정 W1) 950/50, 설비 E2(공정 W2) 1000/50   → 총 3050, 불량 200
    // 제품 B: 설비 E1(공정 W1) 500/0
    private val base = listOf(
        DefectTreeBaseRow("W1", "PRESS(M-1공장)", "E1", "1호", "A", "제품 A", 900, 100),
        DefectTreeBaseRow("W1", "PRESS(M-1공장)", "E2", "2호", "A", "제품 A", 950, 50),
        DefectTreeBaseRow("W2", "PLATING", "E2", "2호", "A", "제품 A", 1000, 50),
        DefectTreeBaseRow("W1", "PRESS(M-1공장)", "E1", "1호", "B", "제품 B", 500, 0)
    )
    private fun t(wc: String, eqpt: String, item: String, cd: String, nm: String, ng: String) =
        DefectTreeTypeRow(wc, eqpt, item, cd, nm, BigDecimal(ng))
    private val types = listOf(
        t("W1", "E1", "A", "D1", "얼룩", "60.4"),     // E1/W1 유형 합 90.7 → 미상 9.3
        t("W1", "E1", "A", "D2", "찍힘", "30.3"),
        t("W1", "E2", "A", "D1", "얼룩", "49.6"),     // E2/W1 → 미상 0.4
        t("W2", "E2", "A", "D1", "얼룩", "20.5"),     // E2/W2 → 미상 29.5
        t("W2", "E2", "A", "D2", "찍힘", "0.0")
    )

    @Suppress("UNCHECKED_CAST")
    private fun ch(n: Map<String, Any?>) = (n["children"] as? List<Map<String, Any?>>).orEmpty()
    private fun ng(n: Map<String, Any?>) = n["ngQty"] as Long

    private val tree = ProductDefectTreeAssembler.assemble(base, types, "PL01")

    @Test
    @DisplayName("제품 행 — 원장 총량·정상·불량·불량률, ratio 는 전체 불량 중 비중, 불량 0 제품은 자식 없음")
    fun itemLevel() {
        assertEquals(listOf("A", "B"), tree.map { it["itemCd"] })
        val a = tree[0]
        assertEquals("item", a["level"]); assertEquals("제품 A", a["itemNm"]); assertEquals("PL01", a["plantCd"])
        assertNull(a["defectCd"]); assertNull(a["eqptCd"]); assertNull(a["wcCd"])
        assertEquals(3050L, a["totalQty"]); assertEquals(2850L, a["okQty"]); assertEquals(200L, a["ngQty"])
        assertEquals(6.56, a["defectRate"]); assertEquals(100.0, a["ratio"], "전체 불량 200 중 200")
        val b = tree[1]
        assertEquals(500L, b["totalQty"]); assertEquals(0L, b["ngQty"]); assertEquals(0.0, b["ratio"])
        assertTrue(!b.containsKey("children"))
    }

    @Test
    @DisplayName("유형 단계 — 합 = 제품 불량(미상 포함·맨 뒤), 분모는 제품 총량이 되풀이, okQty 는 null")
    fun defectLevel() {
        val defects = ch(tree[0])
        assertEquals(listOf("D1", "D2", null), defects.map { it["defectCd"] })
        assertEquals(listOf("얼룩", "찍힘", DefectSql.UNTYPED_LABEL), defects.map { it["defectNm"] })
        // 60.4+49.6+20.5 = 130.5 → 131(HALF_UP), 30.3 → 30, 미상 9.3+0.4+29.5 = 39.2 → 39 : 합 200 ✓
        assertEquals(listOf(131L, 30L, 39L), defects.map { ng(it) })
        assertEquals(200L, defects.sumOf { ng(it) }, "유형 합 = 제품 불량")
        defects.forEach {
            assertEquals("defect", it["level"]); assertEquals("A", it["itemCd"])
            assertEquals(3050L, it["totalQty"], "분모는 제품 총량 — 형제마다 같다")
            assertNull(it["okQty"], "정상 수량은 유형에 귀속되지 않는다")
        }
        assertEquals(4.3, defects[0]["defectRate"], "131 / 3050")
        assertEquals(65.5, defects[0]["ratio"], "131 / 200")
        assertEquals(19.5, defects[2]["ratio"])
    }

    @Test
    @DisplayName("설비·공정 단계 — 합은 상위 불량과 같고, 분모는 (제품,설비) · (제품,설비,공정) 원장 총량, 공장은 작업장 이름에서")
    fun eqptAndWcLevels() {
        val d1 = ch(tree[0])[0]
        val eqpts = ch(d1)
        assertEquals(listOf("E2", "E1"), eqpts.map { it["eqptCd"] }, "49.6+20.5=70.1→70 > 60.4→60 (합 130 ≠ 131 → 잔차 1 은 가장 큰 자식 E2 에 더함)")
        assertEquals(listOf(71L, 60L), eqpts.map { ng(it) })
        assertEquals(131L, eqpts.sumOf { ng(it) }, "설비 합 = 유형 불량")
        val e2 = eqpts[0]
        assertEquals("eqpt", e2["level"]); assertEquals("D1", e2["defectCd"]); assertEquals("2호", e2["eqptNm"]); assertNull(e2["wcCd"])
        assertEquals(2050L, e2["totalQty"], "(제품 A, 설비 E2) 원장 총량 = 950+50 + 1000+50")
        assertNull(e2["okQty"])
        assertEquals(3.46, e2["defectRate"], "71 / 2050")
        assertEquals(54.2, e2["ratio"], "71 / 131")

        val wcs = ch(e2)
        assertEquals(listOf("W1", "W2"), wcs.map { it["wcCd"] })
        assertEquals(71L, wcs.sumOf { ng(it) }, "공정 합 = 설비 불량")
        assertEquals(listOf(50L, 21L), wcs.map { ng(it) }, "49.6→50, 20.5→21(HALF_UP) — 합 71 = 설비 불량, 잔차 없음")
        val w1 = wcs[0]
        assertEquals("wc", w1["level"]); assertEquals("PRESS(M-1공장)", w1["wcNm"]); assertEquals("M-1공장", w1["plantNm"])
        assertEquals("E2", w1["eqptCd"]); assertEquals("D1", w1["defectCd"]); assertEquals("A", w1["itemCd"])
        assertEquals(1000L, w1["totalQty"], "(A, E2, W1) 원장 950+50")
        assertNull(ch(w1).firstOrNull(), "공정 아래는 없다")
        assertNull(wcs[1]["plantNm"], "공장 표기 없는 작업장")
    }

    @Test
    @DisplayName("유형 미상도 설비·공정으로 펼쳐지고 합이 보존된다")
    fun untypedDrillsDown() {
        val untyped = ch(tree[0])[2]
        assertNull(untyped["defectCd"])
        val eqpts = ch(untyped)
        assertEquals(39L, eqpts.sumOf { ng(it) })
        assertEquals(listOf("E2", "E1"), eqpts.map { it["eqptCd"] }, "E2 미상 29.9 → 30, E1 9.3 → 9")
        val e2 = eqpts[0]
        assertEquals(30L, ng(e2))
        assertEquals(30L, ch(e2).sumOf { ng(it) })
        assertEquals(DefectSql.UNTYPED_LABEL, ch(e2)[0]["defectNm"], "잎 행에도 유형 이름이 남는다")
    }

    @Test
    @DisplayName("수량 마스킹 — totalQty·okQty·ngQty 만 비고 비율은 남는다")
    fun maskQty() {
        val masked = ProductDefectTreeAssembler.maskQty(tree)
        val leaf = ch(ch(ch(masked[0])[0])[0])[0]
        assertNull(masked[0]["totalQty"]); assertNull(masked[0]["ngQty"]); assertNull(leaf["ngQty"]); assertNull(leaf["totalQty"])
        assertEquals(6.56, masked[0]["defectRate"]); assertEquals(100.0, masked[0]["ratio"])
        assertTrue((leaf["ratio"] as Double) > 0.0)
    }
}
