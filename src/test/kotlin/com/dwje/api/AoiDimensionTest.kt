package com.dwje.api

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.config.AoiProperties
import com.dwje.api.config.AppProperties
import com.dwje.api.repository.AoiDimensionRepository
import com.dwje.api.service.AoiBriefingService
import com.dwje.api.service.AoiDimensionService
import com.dwje.api.service.AuthorizationService
import com.dwje.api.service.SllmClient
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.math.BigDecimal
import java.time.LocalDate

/**
 * AOI 치수 집계·브리핑 규약 (원천 없이 검증할 수 있는 부분).
 *
 * 1. 불량 행 집계 SQL — NOLOCK · 날짜 범위 비교 · 한계 리터럴 · 측정 실패 식 · 한계 있는 FAI 열만 읽는다
 * 2. 한계 세트 결정 — 설비 지정 > 작업장 기본 > 없음
 * 3. 기간 검증 — 상한 초과 400(field=to)
 * 4. 설비 블록 합치기 — 합·비율·한계값(세트가 다르면 비움)
 * 5. 브리핑 — 사실 목록 키, 규칙 문장, 근거 대조 탈락, 「(추정)」 표기
 */
class AoiDimensionTest {

    private val s120 = AoiProperties.LimitSet(
        wcCd = "S120", eqptCd = "*", resolution = 0.001, faiCount = 58,
        usl = mapOf(1 to 0.040, 10 to 22.880, 52 to 3.760), lsl = mapOf(51 to 3.560, 52 to 3.560), basis = "S120 5표본"
    )
    private val gp015 = AoiProperties.LimitSet(
        wcCd = "S110", eqptCd = "GP-015", resolution = 0.0001, faiCount = 45,
        usl = mapOf(1 to 0.070, 15 to 20.164), lsl = mapOf(15 to 20.074), basis = "GP-015 단일"
    )
    private val s110 = AoiProperties.LimitSet(
        wcCd = "S110", eqptCd = "*", resolution = 0.0001, faiCount = 45,
        usl = mapOf(1 to 0.070, 15 to 20.169), lsl = mapOf(15 to 20.069, 17 to 20.069), basis = "GP-009 3일"
    )
    private val props = AppProperties(aoi = AoiProperties(maxDays = 7, limits = listOf(s120, s110, gp015)))
    private val repo = AoiDimensionRepository(null)
    private val service = AoiDimensionService(repo, mock(AuthorizationService::class.java), props)

    @Test
    @DisplayName("불량 행 집계 SQL — NOLOCK, 범위 비교, 한계 리터럴, 측정 실패 식, 필요한 FAI 열만")
    fun failBreakdownSql() {
        val sql = repo.buildFailBreakdownSql(s120.faiNumbers, s120, 58, 5)
        assertTrue(sql.contains("WITH (NOLOCK)"))
        assertTrue(sql.contains("DATE_TIME >= :from AND DATE_TIME < :to"), "CAST(DATE_TIME AS date) 를 쓰면 인덱스를 못 탄다")
        assertTrue(sql.contains("PASSED = '0'"))
        assertTrue(sql.contains("CASE WHEN FAI1 > 0.04 THEN 1 ELSE 0 END"), "상한만 있는 FAI")
        assertTrue(sql.contains("CASE WHEN FAI51 < 3.56 THEN 1 ELSE 0 END"), "하한만 있는 FAI")
        assertTrue(sql.contains("CASE WHEN FAI52 > 3.76 OR FAI52 < 3.56 THEN 1 ELSE 0 END"), "양쪽")
        assertTrue(sql.contains("CASE WHEN FAI58 <> 0 THEN 1 ELSE 0 END") && !sql.contains("FAI59"), "측정 실패 식은 사용 FAI 범위(58)까지")
        assertTrue(sql.contains(") <= 5 THEN 1 ELSE 0 END AS z"))
        assertTrue(sql.contains("('FAI10', FAI10, 22.88, NULL)") && sql.contains("('FAI51', FAI51, NULL, 3.56)"))
        assertTrue(sql.contains("AS exceed_sum_single"), "단일 귀속 행만의 초과량 합")
        assertTrue(sql.contains("('${AoiDimensionRepository.ROW_KEY}'"))
        // SELECT 절의 FAI 열은 한계가 있는 것만 — 측정 실패 식 안의 FAI 는 전송되지 않는다
        val selectCols = sql.substringAfter("AS viol").substringBefore("FROM")
        assertEquals(listOf("FAI1", "FAI10", "FAI51", "FAI52"), Regex("""FAI\d+""").findAll(selectCols).map { it.value }.toList())
        assertFalse(sql.contains("1E-") || sql.contains("E-0"), "지수 표기 리터럴 금지")

        val one = repo.buildEquipmentSummarySql(s120.faiNumbers, s120, 58, 5)
        assertTrue(one.contains("INTO #a") && one.contains("DROP TABLE #a;"), "단일 패스 — 기간 행을 임시 테이블에 한 번 내리고 끝에 지운다")
        assertTrue(one.contains("SET NOCOUNT ON;"), "행 수 메시지가 결과 집합을 가리지 않게")
        assertTrue(one.contains("WHERE f.PASSED = '0'") && one.contains("(SELECT COUNT_BIG(*) FROM #a)"), "건수는 전체, 분해는 불량 행만")
        assertTrue(one.contains("INDEX(SAMSUN_DIMENSION_DATE_TIME)"), "기간 조건 질의는 DATE_TIME 인덱스를 고정한다")
        val intoCols = one.substringAfter("SELECT PASSED, LOT_NO, SERIAL_NO, DATE_TIME,").substringBefore("INTO #a")
        assertEquals(listOf("FAI1", "FAI10", "FAI51", "FAI52"), Regex("""(?<=viol, )[^\n]*""").find(intoCols)?.value?.let { Regex("""FAI\d+""").findAll(it).map { m -> m.value }.toList() }, "임시 테이블에는 한계 있는 FAI 열만")

        val none = repo.buildFailBreakdownSql(emptyList(), null, 45, 5)
        assertTrue(none.contains("(0) AS viol"), "한계 세트가 없으면 위반 0 — 전부 미확정")
        assertThrows(IllegalArgumentException::class.java) { repo.buildFailBreakdownSql(listOf(101), null, 58, 5) }
    }

    @Test
    @DisplayName("한계 세트 결정 — 설비 지정이 작업장 기본을 이기고, 모르는 작업장은 없음")
    fun resolveLimits() {
        assertEquals("GP-015 단일", service.resolveLimits("S110", "GP-015")?.basis)
        assertEquals("GP-009 3일", service.resolveLimits("S110", "GP-009")?.basis)
        assertEquals("S120 5표본", service.resolveLimits("S120", "MQ-008")?.basis)
        assertNull(service.resolveLimits("S999", "X"))
        assertEquals(listOf(1, 51, 52, 10).sorted(), s120.faiNumbers)
    }

    @Test
    @DisplayName("기간 — 기본은 오늘 하루, 상한(7일) 초과는 400 field=to, 역순은 400 field=from")
    fun period() {
        val (f, t) = service.periodOf(null, null)
        assertEquals(LocalDate.now(), f); assertEquals(LocalDate.now(), t)
        val (f7, t7) = service.periodOf("2026-09-05", "2026-09-11")
        assertEquals(LocalDate.parse("2026-09-05"), f7); assertEquals(LocalDate.parse("2026-09-11"), t7)
        val over = assertThrows(InvalidParameterException::class.java) { service.periodOf("2026-09-04", "2026-09-11") }
        assertTrue(over.message!!.contains("최대 7일"))
        assertThrows(InvalidParameterException::class.java) { service.periodOf("2026-09-12", "2026-09-11") }
    }

    private fun fai(n: Int, usl: Double?, lsl: Double?, viol: Long, single: Long, over: Long, under: Long, sum: String, share: Double, violPct: Double) =
        AoiDimensionService.Fai(n, usl, lsl, viol, single, over, under, BigDecimal(sum), BigDecimal(sum), BigDecimal(sum), share, violPct)

    private fun block(eqpt: String, meas: Long, fail: Long, single: Long, multi: Long, unconf: Long, zero: Long, fais: List<AoiDimensionService.Fai>, basis: String, res: Double) =
        AoiDimensionService.Block(eqpt, basis, res, meas, fail, 10, single, multi, unconf, zero, fais, null, null)

    @Test
    @DisplayName("설비 블록 합치기 — 합·설명률·FAI 비중을 다시 계산하고, 세트가 다른 FAI 의 한계값은 비운다")
    fun merge() {
        val a = block("GP-009", 1000, 100, 60, 20, 15, 5, listOf(fai(15, 20.169, 20.069, 50, 40, 10, 40, "0.5", 66.67, 50.0), fai(1, 0.070, null, 30, 20, 30, 0, "0.3", 33.33, 30.0)), "GP-009 3일", 0.0001)
        val b = block("GP-015", 500, 100, 40, 10, 40, 10, listOf(fai(15, 20.164, 20.074, 30, 30, 0, 30, "0.6", 75.0, 30.0), fai(1, 0.070, null, 20, 10, 20, 0, "0.1", 25.0, 20.0)), "GP-015 단일", 0.0001)
        val m = service.merge(listOf(a, b))
        assertNull(m.eqptCd)
        assertEquals(1500L, m.measCnt); assertEquals(200L, m.failCnt); assertEquals(13.33, m.failRate)
        assertEquals(100L, m.singleCnt); assertEquals(30L, m.multiCnt); assertEquals(55L, m.unconfirmedCnt); assertEquals(15L, m.zeroCnt)
        assertEquals(65.0, m.explainedRate, "(100+30)/200")
        val f15 = m.fais.first { it.fai == 15 }
        assertEquals(80L, f15.violCnt); assertEquals(70L, f15.violSingleCnt); assertEquals(70.0, f15.sharePct, "70/100 단일 귀속")
        assertEquals(40.0, f15.violPct, "80/200")
        assertNull(f15.usl, "GP-009 20.169 와 GP-015 20.164 가 달라 전체에는 적지 않는다"); assertNull(f15.lsl)
        val f1 = m.fais.first { it.fai == 1 }
        assertEquals(0.070, f1.usl, "같은 값이면 남긴다"); assertEquals(30L, f1.violSingleCnt)
        assertEquals(15, m.topFai?.fai)
        assertEquals("GP-009 3일 / GP-015 단일", m.limitBasis); assertEquals(0.0001, m.resolution)
        assertEquals(BigDecimal("1.1"), f15.exceedSum); assertEquals(0.01375, f15.exceedAvg); assertEquals(0.015714, f15.exceedAvgSingle!!, 0.000001)
    }

    @Test
    @DisplayName("응답 마스킹 — 수량 없으면 건수 null, 비율 없으면 비율 null, 나머지는 남는다")
    fun masking() {
        val b = block("MQ-008", 1000, 100, 80, 10, 5, 5, listOf(fai(10, 22.880, null, 80, 70, 80, 0, "0.8", 87.5, 80.0)), "S120", 0.001)
        val noQty = service.blockMap(b, qty = false, yield = true)
        assertNull(noQty["measCnt"]); assertNull((noQty["attribution"] as Map<*, *>)["zero"]); assertEquals(10.0, noQty["failRate"])
        @Suppress("UNCHECKED_CAST") val f = (noQty["fais"] as List<Map<String, Any?>>)[0]
        assertNull(f["violCnt"]); assertNull(f["exceedSum"]); assertEquals(87.5, f["sharePct"]); assertEquals(22.88, f["usl"])
        val noYield = service.blockMap(b, qty = true, yield = false)
        assertNull(noYield["failRate"]); assertNull(noYield["explainedRate"]); assertEquals(100L, noYield["failCnt"])
    }

    // ── 브리핑 ────────────────────────────────────────────────────────────────

    private val briefing = AoiBriefingService(service, mock(SllmClient::class.java), mock(AuthorizationService::class.java), props)

    private fun summary(eqpt: String?, from: String, to: String, blocks: List<AoiDimensionService.Block>) =
        AoiDimensionService.Summary(LocalDate.parse(from), LocalDate.parse(to), "S120", eqpt, service.merge(blocks), blocks, 10, 2)

    private val cur = summary("MQ-008", "2026-09-11", "2026-09-11", listOf(
        block("MQ-008", 59328, 11527, 10174, 1173, 159, 21,
            listOf(fai(10, 22.880, null, 5164, 4546, 5164, 0, "37.8", 44.68, 44.8), fai(1, 0.040, null, 4971, 4337, 4971, 0, "32.7", 42.63, 43.1)),
            "S120 5표본", 0.001)))
    private val prev = summary("MQ-008", "2026-09-10", "2026-09-10", listOf(
        block("MQ-008", 52744, 6282, 5000, 1028, 254, 0, listOf(fai(1, 0.040, null, 3000, 2129, 3000, 0, "20", 42.6, 47.8)), "S120 5표본", 0.001)))

    @Test
    @DisplayName("사실 목록 — 전체·FAI 별·직전 기간 키, 가려진 수량은 빠진다")
    fun facts() {
        val f = briefing.factsOf(cur, prev, qty = true, yield = true)
        assertEquals(11527.0, f["failCnt"]); assertEquals(19.43, f["failRate"]); assertEquals(98.44, f["explainedRate"])
        assertEquals(22.88, f["FAI10.usl"]); assertEquals(5164.0, f["FAI10.violCnt"]); assertEquals(4546.0, f["FAI10.violSingleCnt"]); assertEquals(44.68, f["FAI10.sharePct"])
        assertEquals(0.0083, f["FAI10.exceedAvg"]!!, 0.0001)
        assertEquals(11.91, f["prev.failRate"]); assertEquals(7.52, f["delta.failRatePt"])
        val masked = briefing.factsOf(cur, prev, qty = false, yield = true)
        assertNull(masked["failCnt"]); assertNull(masked["FAI10.violCnt"]); assertEquals(19.43, masked["failRate"])
    }

    @Test
    @DisplayName("규칙 문장 — 기간·대상·설명률·원인 항목(번호+한계값+방향)·귀속·직전 대비, FAI 이름 없음")
    fun ruleLines() {
        val lines = briefing.ruleLines(cur, prev, qty = true, yield = true)
        assertTrue(lines[0].startsWith("2026-09-11 MQ-008 측정 59,328건 중 불량 11,527건(불량률 19.43%), 설명률 98.44%."), lines[0])
        assertTrue(lines[1].contains("FAI10 상한 22.880 초과 5,164건(단일 귀속 44.68%), 평균 +0.008"), lines[1])
        assertTrue(lines[1].contains("FAI1 상한 0.040 초과"), lines[1])
        assertTrue(lines[2].contains("단일 항목 10,174건 · 복합 1,173건 · 미확정 항목 이탈 159건 · 측정 실패 21건"), lines[2])
        assertTrue(lines[3].contains("직전 동일 기간(2026-09-10~2026-09-10) 불량률 11.91% 대비 +7.52%p") && lines[3].contains("FAI1에서 FAI10로 바뀜"), lines[3])
        val noQty = briefing.ruleLines(cur, null, qty = false, yield = true)
        assertTrue(noQty[0].contains("측정 결과(불량률 19.43%)") && noQty.none { it.contains("건") }, noQty.toString())
    }

    @Test
    @DisplayName("근거 대조 — 사실과 다른 값·모르는 키·근거 없는 문장은 버리고, 조치에는 (추정) 을 붙이고 모르는 FAI 는 버린다")
    fun verify() {
        val facts = briefing.factsOf(cur, prev, qty = true, yield = true)
        val node = ObjectMapper().readTree(
            """
            {"summary":[
              {"text":"9월 11일 MQ-008 불량의 절반 가까이가 FAI10 상한 초과다.","evidence":[{"key":"FAI10.sharePct","value":44.68},{"key":"FAI10.usl","value":22.88}]},
              {"text":"설명률은 90%다.","evidence":[{"key":"explainedRate","value":90.0}]},
              {"text":"FAI23 이 문제다.","evidence":[{"key":"FAI23.sharePct","value":10}]},
              {"text":"근거 없는 문장","evidence":[]},
              {"text":"불량률이 전날보다 올랐다.","evidence":[{"key":"delta.failRatePt","value":7.5}]}
            ],
            "actions":[
              {"text":"MQ-008 의 FAI10 측정 지점 고정 상태를 점검한다","fai":10},
              {"text":"(추정) FAI1 관련 지그를 점검한다","fai":1},
              {"text":"FAI23 을 본다","fai":23}
            ]}
            """.trimIndent()
        )
        val v = briefing.verify(node, facts, cur)
        assertEquals(2, v.lines.size, "44.68·22.88 일치, 7.5≈7.52(1%) 통과 / 90 ≠ 98.44 · FAI23 · 빈 근거 탈락")
        assertEquals(listOf("(추정) MQ-008 의 FAI10 측정 지점 고정 상태를 점검한다", "(추정) FAI1 관련 지그를 점검한다"), v.actions.map { it["text"] })
        assertTrue(v.actions.all { it["estimate"] == true })
        assertEquals(4, v.dropped, "문장 3 + 조치 1")
    }
}
