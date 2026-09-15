package com.dwje.api

import com.dwje.api.common.util.DefectSql
import com.dwje.api.repository.DefectByLineRow
import com.dwje.api.repository.assembleDefectByLine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 라인별 불량률 응답 조립 — 설비 × 유형 평면 행을 `설비 → children[유형]` 트리로 만드는 규약.
 *
 * 1. 설비 순서는 입력(불량 수량 내림차순)을 지키고, 수량 3종(ngQty·okQty·totalQty)과 불량률이 붙는다
 * 2. children 은 안분 수량 내림차순, ratio 는 그 설비 불량 합 대비 %, 유형 합이 모자라면 '유형 미상' 으로 채워 합 = 100%
 * 3. mainType 은 가장 큰 유형. 유형이 없는 설비는 children 이 비고 mainType 은 null — '유형 미상' 을 주 유형으로 올리지 않는다
 */
class QualityDefectByLineAssembleTest {

    private fun row(eqpt: String, ng: Long, ok: Long, cd: String?, nm: String?, typeNg: Long?) =
        DefectByLineRow(eqpt, "$eqpt 호기", null, ng, ok, ok + ng, cd, nm, typeNg)

    @Test
    @DisplayName("설비 한 대 = 항목 하나, children 정렬·비중·유형 미상 보정, 주 유형")
    fun assemble() {
        val items = assembleDefectByLine(
            listOf(
                row("MN-077", 1000, 500, "D01", "얼룩", 600),
                row("MN-077", 1000, 500, "D02", "은하수", 300),
                row("BG-002", 400, 9600, "D02", "은하수", 400)
            )
        )
        assertEquals(listOf("MN-077", "BG-002"), items.map { it["eqptCd"] })

        val first = items[0]
        assertEquals(1000L, first["ngQty"]); assertEquals(500L, first["okQty"]); assertEquals(1500L, first["totalQty"])
        assertEquals(66.67, first["defectRate"])
        assertEquals("얼룩", first["mainType"])

        @Suppress("UNCHECKED_CAST")
        val children = first["children"] as List<Map<String, Any?>>
        assertEquals(listOf("얼룩", "은하수", DefectSql.UNTYPED_LABEL), children.map { it["defectType"] })
        assertEquals(listOf(600L, 300L, 100L), children.map { it["ngQty"] }, "유형 미상 = 1000 − 900")
        assertEquals(listOf(60.0, 30.0, 10.0), children.map { it["ratio"] })
        assertNull(children.last()["defectCd"], "유형 미상은 실제 코드가 아니라 코드를 비운다")
        assertEquals(1000L, children.sumOf { it["ngQty"] as Long }, "children 합 = 설비 불량 수량")

        @Suppress("UNCHECKED_CAST")
        val second = items[1]["children"] as List<Map<String, Any?>>
        assertEquals(1, second.size, "유형 합이 불량 수량과 같으면 유형 미상 행이 없다")
        assertEquals(100.0, second[0]["ratio"])
        assertEquals(4.0, items[1]["defectRate"])
    }

    @Test
    @DisplayName("유형이 하나도 없는 설비 — children 은 비고 mainType 은 null (0 이나 '유형 미상' 으로 채우지 않는다)")
    fun noTypes() {
        val items = assembleDefectByLine(listOf(row("YG-058", 50, 950, null, null, null)))
        assertEquals(1, items.size)
        assertNull(items[0]["mainType"])
        @Suppress("UNCHECKED_CAST")
        val children = items[0]["children"] as List<Map<String, Any?>>
        assertEquals(1, children.size, "불량은 있는데 유형이 없으면 전량이 '유형 미상' 이다")
        assertEquals(DefectSql.UNTYPED_LABEL, children[0]["defectType"])
        assertEquals(50L, children[0]["ngQty"])
        assertEquals(100.0, children[0]["ratio"])

        val clean = assembleDefectByLine(listOf(row("MT-001", 0, 1000, null, null, null)))
        @Suppress("UNCHECKED_CAST")
        val none = clean[0]["children"] as List<Map<String, Any?>>
        assertTrue(none.isEmpty(), "불량 0 인 설비는 자식이 없다")
        assertEquals(0.0, clean[0]["defectRate"])
    }
}
