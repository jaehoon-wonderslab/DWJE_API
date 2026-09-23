package com.dwje.api

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.config.AoiProperties
import com.dwje.api.config.AppProperties
import com.dwje.api.repository.AoiCosmeticRepository
import com.dwje.api.repository.AoiCosmeticRepository.ItemStat
import com.dwje.api.repository.AoiCosmeticRepository.LineCount
import com.dwje.api.repository.AoiCosmeticRepository.SerialKey
import com.dwje.api.service.AgentRunRecorder
import com.dwje.api.service.AoiCosmeticService
import com.dwje.api.service.AuthorizationService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

/**
 * AOI 외관 판정(COSMETIC) — 원천 없이 검증할 규약.
 *
 * 실측 근거는 `docs/AOI_COSMETIC_SOURCE_SURVEY_20260914.md`. 여기서 지키려는 것은 네 가지다.
 * 1. 불량률의 분모는 **제품 수**다 — 행(제품 × 검사항목)을 세면 항목 수만큼 부풀려진다
 * 2. `serialKey` 왕복 — `LOT_NO`·`SERIAL_NO` 가 빈 설비(MN-065·066·067)를 거절하지 않는다
 * 3. 설비별 블록 합치기 — 설비마다 검사 항목 구성이 다르다
 * 4. 기간 상한은 치수(7일)와 따로 3일이다
 */
class AoiCosmeticTest {

    /** JOIN 자리의 올바른 표기 — 별칭이 테이블 힌트보다 앞이다 */
    private val ALIASED_JOIN = AoiCosmeticRepository.TABLE_ALIASED

    private val props = AppProperties(
        aoi = AoiProperties(
            maxDays = 7,
            cosmetic = AoiProperties.Cosmetic(
                maxDays = 3,
                itemNames = mapOf("DF021" to "이물"),
                nonVerdictItems = listOf("DF009")
            )
        )
    )
    private val service = AoiCosmeticService(AoiCosmeticRepository(null), mock(AuthorizationService::class.java), mock(AgentRunRecorder::class.java), props)

    /** 09-12 MN-069 실측을 본뜬 값 */
    private fun count(prod: Long, ng: Long, single: Long, multi: Long, unexplained: Long = 0, overridden: Long = 0, rows: Long = prod * 6) =
        LineCount(
            wcCd = "S135", eqptCd = "MN-069", prodCnt = prod, prodNgCnt = ng,
            rowCnt = rows, serialCnt = 3, singleCnt = single, multiCnt = multi,
            unexplainedCnt = unexplained, overriddenCnt = overridden, firstAt = null, lastAt = null
        )

    @Test
    @DisplayName("serialKey — 네 조각 왕복. 빈 조각도 받는다(키가 빈 설비가 실재)")
    fun serialKey() {
        val k = SerialKey.decode("S135~MN-069~20260910~00022")!!
        assertEquals("S135", k.wcCd); assertEquals("MN-069", k.eqptCd)
        assertEquals("20260910", k.lotNo); assertEquals("00022", k.serialNo)
        assertEquals("S135~MN-069~20260910~00022", k.encode())

        // MN-065·066·067 은 WC_CD·LOT_NO·SERIAL_NO 가 전부 빈 문자열이다(실측 5절)
        val blank = SerialKey.decode("~MN-065~~")!!
        assertEquals("", blank.wcCd); assertEquals("", blank.lotNo); assertEquals("", blank.serialNo)

        assertNull(SerialKey.decode("S135~MN-069~20260910"), "조각이 모자라면 거절")
        assertNull(SerialKey.decode("S135~MN-069~20260910~00022~1"), "조각이 넘쳐도 거절")
    }

    @Test
    @DisplayName("불량률의 분모는 제품 수다 — 행 수가 아니다")
    fun ngRateDenominatorIsProducts() {
        // 실측 09-12 전체: 제품 458,076 · 최종 불량 73,109 · 행 3,144,557
        val b = service.merge(listOf(block(count(prod = 458_076, ng = 73_109, single = 60_000, multi = 13_029, rows = 3_144_557))))
        assertEquals(15.96, b.ngRate, 0.01, "제품 기준 15.96% (실측)")
        assertEquals(458_076L, b.prodCnt)
        assertEquals(3_144_557L, b.rowCnt, "행 수는 따로 보관하되 비율의 분모로 쓰지 않는다")
        // 행으로 나누면 2.3% 가 되어 실제(15.96%)의 7분의 1로 어긋난다 — 이 실수를 막는 것이 이 검증의 목적이다
        assertTrue(b.ngRate > pctByRows(b.prodNgCnt, b.rowCnt) * 6, "행 기준 비율과 확연히 달라야 한다")
    }

    @Test
    @DisplayName("귀속 — 단일·복합만 설명된 것으로 센다. 오버라이드는 불량에 들어가지 않는다")
    fun attribution() {
        // 항목 불량인데 제품 합격(오버라이드) 50,976 은 prodNgCnt 밖이다
        val b = service.merge(listOf(count(prod = 458_076, ng = 73_109, single = 60_000, multi = 13_029, unexplained = 80, overridden = 50_976).let { block(it) }))
        assertEquals(73_109L, b.prodNgCnt)
        assertEquals(50_976L, b.overriddenCnt)
        assertEquals(80L, b.unexplainedCnt, "항목은 다 통과인데 최종 불량 — 실측 80건")
        assertEquals(99.89, b.explainedRate, 0.01, "(단일+복합) ÷ 최종불량")
    }

    @Test
    @DisplayName("설비별 합치기 — 항목 구성이 달라도 코드로 합쳐지고 비중이 다시 계산된다")
    fun mergeLines() {
        val a = block(count(prod = 100, ng = 30, single = 30, multi = 0), listOf(ItemStat("DF021", 100, 30, 30, 30)))
        // MN-065 류는 한글 항목만 쓴다
        val c = block(count(prod = 100, ng = 10, single = 10, multi = 0), listOf(ItemStat("스크래치", 100, 10, 10, 10)))
        val m = service.merge(listOf(a, c))

        assertEquals(200L, m.prodCnt); assertEquals(40L, m.prodNgCnt)
        assertEquals(listOf("DF021", "스크래치"), m.items.map { it.itemCd }, "단일 귀속 많은 순")
        assertEquals(75.0, m.items.first().sharePct, 0.01, "30 ÷ 40 — 합친 뒤 다시 계산")
        assertEquals("이물", m.items.first().itemNm, "설정에 이름이 있으면 채운다")
        assertNull(m.items.last().itemNm, "이름이 없으면 null — 화면이 코드를 그대로 쓴다")
    }


    @Test
    @DisplayName("합부를 정하지 않는 항목(DF009)은 귀속에서 빠지고 상위 항목으로도 뽑히지 않는다")
    fun nonVerdictItem() {
        // 실측: DF009 는 09-12 전 제품의 99.51% 에서 PASSED=0 이지만, DF009 만 불량인 제품 50,976개의 최종 불량은 0개다.
        val b = service.merge(listOf(block(
            count(prod = 61_390, ng = 10_844, single = 9_421, multi = 1_360, unexplained = 63),
            listOf(
                ItemStat("DF021", 61_390, 5_391, 5_391, 4_285),
                ItemStat("DF009", 61_390, 61_088, 0, 0, verdict = false)
            )
        )))

        assertEquals("DF021", b.topItem?.itemCd, "불량률 99.5% 인 DF009 가 아니라 실제 원인 항목이 뽑혀야 한다")
        assertEquals("DF009", b.items.last().itemCd, "합부가 아닌 항목은 목록 뒤로 밀린다")
        assertEquals(false, b.items.last().verdict)
        assertEquals(0.0, b.items.last().sharePct, 0.001, "귀속 비중 0 — 불량을 설명하지 않는다")
        assertTrue(b.items.first().verdict, "앞자리는 합부 항목")
    }

    @Test
    @DisplayName("비합부 항목이 있으면 SQL 이 그 항목을 불량 개수에서 뺀다")
    fun nonVerdictSql() {
        val repo = AoiCosmeticRepository(null)
        val withNv = repo.buildPeriodSql(hasWc = true, hasEqpt = true, hasNonVerdict = true)
        assertTrue(withNv.contains("CATEGORY_STR NOT IN (:nonVerdict)"), "불량 항목 수 계산에서 제외 조건이 있어야 한다")
        assertTrue(withNv.contains("c.CATEGORY_STR NOT IN (:nonVerdict)"), "단일 귀속 계산에도 같은 조건")

        // 목록이 비면 `NOT IN ()` 이 되어 구문 오류가 난다 — 아예 조건을 빼야 한다
        val without = repo.buildPeriodSql(hasWc = false, hasEqpt = false, hasNonVerdict = false)
        assertTrue(!without.contains("NOT IN"), "비합부 항목이 없으면 조건을 붙이지 않는다")
        assertTrue(!without.contains(":nonVerdict"))
        assertTrue(!without.contains(":wc") && !without.contains(":eqpt"), "필터가 없으면 조건도 없다")
    }

    @Test
    @DisplayName("기간 전체를 한 번 읽는다 — 설비별로 쪼개지 않는다")
    fun singlePassSql() {
        val sql = AoiCosmeticRepository(null).buildPeriodSql(hasWc = false, hasEqpt = false, hasNonVerdict = true)
        // 설비 조건이 없어야 한 번 읽기다. 설비별로 쪼개면 09-12 하루가 집계 90초·목록 184초였다(실측).
        assertTrue(sql.contains("INTO #c"), "기간 행을 임시 테이블에 한 번 내려놓는다")
        assertTrue(sql.contains("GROUP BY p.WC_CD, p.EQPT_CD"), "설비 구분은 SQL 안에서 GROUP BY 로 한다")
        // 임시 테이블은 세션에 매여 있으므로 적재·집계·정리가 한 문장열이어야 한다(오류 208 재발 방지)
        assertTrue(sql.indexOf("INTO #c") < sql.lastIndexOf("DROP TABLE #c"), "한 문장열 안에서 적재하고 지운다")
        assertTrue(sql.contains("$ALIASED_JOIN"), "JOIN 은 별칭이 힌트보다 앞이어야 한다")
    }

    @Test
    @DisplayName("인덱스 이름은 식별자 모양만 SQL 에 들어간다")
    fun indexNameWhitelist() {
        assertEquals("SAMSUN_COSMETIC_DATE_TIME", AoiCosmeticRepository.safeIndexName("SAMSUN_COSMETIC_DATE_TIME"))
        assertEquals("IX_9", AoiCosmeticRepository.safeIndexName("  IX_9  "))
        assertNull(AoiCosmeticRepository.safeIndexName(""))
        assertNull(AoiCosmeticRepository.safeIndexName(null))
        assertNull(AoiCosmeticRepository.safeIndexName("IX) DROP TABLE x --"), "식별자가 아니면 힌트를 붙이지 않는다")
        assertNull(AoiCosmeticRepository.safeIndexName("IX-A"))
    }

    @Test
    @DisplayName("키가 빈 행은 상세를 열 수 없고 목록에서도 serialKey 가 없다")
    fun unkeyedSerial() {
        assertTrue(AoiCosmeticRepository.SerialKey("S135", "MN-069", "20260910", "00022").keyed)
        assertTrue(!AoiCosmeticRepository.SerialKey("", "MN-069", "", "").keyed)
        assertTrue(!AoiCosmeticRepository.SerialKey("S135", "MN-069", "20260910", "").keyed)

        val row = AoiCosmeticRepository.SerialRow(
            key = AoiCosmeticRepository.SerialKey("", "MN-069", "", ""),
            prodCnt = 488, prodNgCnt = 12, prodCntAll = 488, prodNgCntAll = 12,
            seqMin = 4, seqMax = 4705, cavity = null, firstAt = null, lastAt = null
        )
        val m = service.serialRowMap(row, qty = true, yield = true)
        assertNull(m["serialKey"], "링크를 걸 수 없는 행이다")
        assertEquals(false, m["keyed"])
        assertEquals(false, m["partial"], "키가 없는 행의 partial 은 '자정 넘김' 이 아니므로 false")

        val raw = "~MN-069~~"
        val e = assertThrows(InvalidParameterException::class.java) {
            service.requireKeyed(AoiCosmeticRepository.SerialKey.decode(raw)!!, raw)
        }
        assertTrue(e.message!!.contains("키가 비어"), "무슨 일인지 말해야 한다 : ${e.message}")
    }

    @Test
    @DisplayName("목록 행은 기간 값과 시리얼 전체 값을 함께 낸다 — 상세와 어긋나지 않게")
    fun serialRowCarriesBothTotals() {
        // 실측: S135~MN-069~20260910~00022 목록 1,946 / 상세 3,480 (자정을 넘긴 시리얼)
        val row = AoiCosmeticRepository.SerialRow(
            key = AoiCosmeticRepository.SerialKey("S135", "MN-069", "20260910", "00022"),
            prodCnt = 1_946, prodNgCnt = 207, prodCntAll = 3_480, prodNgCntAll = 372,
            seqMin = 1_535, seqMax = 3_480, cavity = "#1 B", firstAt = null, lastAt = null
        )
        val m = service.serialRowMap(row, qty = true, yield = true)
        assertEquals(1_946L, m["prodCnt"]); assertEquals(3_480L, m["prodCntAll"])
        assertEquals(207L, m["prodNgCnt"]); assertEquals(372L, m["prodNgCntAll"])
        assertEquals(true, m["partial"], "전체가 기간 값보다 크면 앞 구간이 기간 밖에 있다")

        val masked = service.serialRowMap(row, qty = false, yield = false)
        assertNull(masked["prodCntAll"]); assertNull(masked["ngRateAll"])
    }

    @Test
    @DisplayName("only 는 ng·all 만 받는다 — 모르는 값을 조용히 기본값으로 되돌리지 않는다")
    fun onlyRejectsUnknown() {
        // 웹의 EMPTY_FILTERS 가 `all` 을 지워 서버가 기본값 ng 로 답하던 일이 있었다(2026-09-14).
        // 값이 사라지거나 어긋나면 400 이 나야 그날 바로 드러난다.
        assertEquals(true, service.parseOnly(null), "기본은 불량만")
        assertEquals(true, service.parseOnly("ng"))
        assertEquals(false, service.parseOnly("all"))
        assertEquals(false, service.parseOnly(" ALL "), "대소문자·공백은 받아 준다")
        assertThrows(InvalidParameterException::class.java) { service.parseOnly("everything") }
        assertThrows(InvalidParameterException::class.java) { service.parseOnly("true") }
        assertThrows(InvalidParameterException::class.java) { service.parseOnly("") }
    }

    @Test
    @DisplayName("기간 상한은 치수와 따로 3일이다")
    fun maxDays() {
        val (from, to) = service.periodOf("2026-09-10", "2026-09-12")
        assertEquals("2026-09-10", from.toString()); assertEquals("2026-09-12", to.toString())

        val e = assertThrows(InvalidParameterException::class.java) { service.periodOf("2026-09-09", "2026-09-12") }
        assertTrue(e.message!!.contains("최대 3일"), "메시지에 상한이 보여야 한다 : ${e.message}")

        assertThrows(InvalidParameterException::class.java) { service.periodOf("2026-09-12", "2026-09-10") }
    }

    private fun pctByRows(n: Long, d: Long): Double = n * 100.0 / d

    private fun mapped(items: List<ItemStat>) = items.map {
        AoiCosmeticService.Item(
            it.itemCd, props.aoi.cosmetic.itemNames[it.itemCd], it.inspCnt, it.ngCnt, it.ngFinalCnt, it.ngSingleCnt,
            it.itemCd !in props.aoi.cosmetic.nonVerdictItems
        )
    }

    private fun block(c: LineCount, items: List<ItemStat> = listOf(ItemStat("DF021", c.prodCnt, c.singleCnt, c.singleCnt, c.singleCnt))) =
        AoiCosmeticService.Block(
            wcCd = c.wcCd, eqptCd = c.eqptCd, prodCnt = c.prodCnt, prodNgCnt = c.prodNgCnt,
            rowCnt = c.rowCnt, serialCnt = c.serialCnt, singleCnt = c.singleCnt, multiCnt = c.multiCnt,
            unexplainedCnt = c.unexplainedCnt, overriddenCnt = c.overriddenCnt,
            items = mapped(items).filter { it.ngCnt > 0 },
            allItems = mapped(items),
            firstAt = null, lastAt = null
        )
}
