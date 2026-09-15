package com.dwje.api

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.config.AoiProperties
import com.dwje.api.config.AppProperties
import com.dwje.api.repository.AoiDimensionRepository
import com.dwje.api.repository.AoiDimensionRepository.DaySerial
import com.dwje.api.repository.AoiDimensionRepository.SerialKey
import com.dwje.api.repository.AoiDimensionRepository.SerialStat
import com.dwje.api.service.AoiDimensionService
import com.dwje.api.service.AoiSerialService
import com.dwje.api.service.AuthorizationService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * AOI 시리얼 목록·상세(DIMENSION) — 원천 없이 검증할 규약.
 * 1. serialKey `wc~eqpt~lot~serial` 왕복 · 잘못된 키 거절
 * 2. 목록 행 — failSeqs 가 failSeqCnt 보다 짧으면 잘림 표시, partial(seqMin>1), 마스킹
 * 3. 정렬 — 기본 failSeqCnt 내림차순, 통계 없는 행은 뒤로
 * 4. 기간 — date 하루 우선, from/to 상한
 */
class AoiSerialTest {

    private val props = AppProperties(aoi = AoiProperties(maxDays = 7, limits = listOf(
        AoiProperties.LimitSet(wcCd = "S120", eqptCd = "*", resolution = 0.001, faiCount = 58, usl = mapOf(1 to 0.040))
    )))
    private val dimension = AoiDimensionService(AoiDimensionRepository(null), mock(AuthorizationService::class.java), props)
    private val service = AoiSerialService(AoiDimensionRepository(null), dimension, mock(AuthorizationService::class.java), props)

    private fun day(wc: String, eq: String, lot: String, sn: String, seqMin: Int, last: String) =
        DaySerial(SerialKey(wc, eq, lot, sn), 3800, seqMin, 3800, LocalDateTime.parse("2026-09-11T00:00:00"), LocalDateTime.parse(last))

    @Test
    @DisplayName("serialKey — 네 조각 왕복, 조각이 모자라거나 비면 null")
    fun serialKey() {
        val k = SerialKey.parse("S120~MQ-008~20260910~00013")!!
        assertEquals("S120", k.wcCd); assertEquals("MQ-008", k.eqptCd); assertEquals("20260910", k.lotNo); assertEquals("00013", k.serialNo)
        assertEquals("S120~MQ-008~20260910~00013", k.key)
        assertEquals("", SerialKey.parse("~GP-011~20260910~00001")!!.wcCd, "작업장이 빈 행도 있다(실측) — 허용")
        assertNull(SerialKey.parse("S120~MQ-008~20260910"))
        assertNull(SerialKey.parse("S120~MQ-008~~00013"))
        assertNull(SerialKey.parse(null))
    }

    @Test
    @DisplayName("목록 행 — failSeqs 잘림 표시 · partial · 마스킹")
    fun rowMap() {
        val row = AoiSerialService.SerialRow(
            day("S120", "MQ-008", "20260910", "00013", 1, "2026-09-11T04:42:13"),
            SerialStat(SerialKey("S120", "MQ-008", "20260910", "00013"), 3808, 728, "#5 B", (1..20).toList()), 58
        )
        val m = service.rowMap(row, qty = true, yield = true)
        assertEquals("S120~MQ-008~20260910~00013", m["serialKey"])
        assertEquals(3808L, m["seqCnt"]); assertEquals(728L, m["failSeqCnt"]); assertEquals(19.12, m["failRate"]); assertEquals(false, m["passed"])
        assertEquals(20, (m["failSeqs"] as List<*>).size); assertEquals(true, m["failSeqsTruncated"], "728 > 20")
        assertEquals(false, m["partial"]); assertEquals(58, m["faiUsed"]); assertEquals("#5 B", m["cavity"])

        val carried = AoiSerialService.SerialRow(day("S110", "GP-011", "20260909", "00379", 1961, "2026-09-11T09:00:00"),
            SerialStat(SerialKey("S110", "GP-011", "20260909", "00379"), 3612, 5, null, listOf(3, 9, 400, 401, 3000)), null)
        val c = service.rowMap(carried, qty = true, yield = true)
        assertEquals(true, c["partial"], "SEQ 1961 부터 — 앞 구간은 전날"); assertEquals(false, c["failSeqsTruncated"], "5개 전부 실림")

        val masked = service.rowMap(row, qty = false, yield = false)
        assertNull(masked["seqCnt"]); assertNull(masked["failSeqCnt"]); assertNull(masked["daySeqCnt"]); assertNull(masked["failRate"])
        assertEquals(20, (masked["failSeqs"] as List<*>).size, "회차 번호는 수량이 아니라 남긴다")
    }

    @Test
    @DisplayName("정렬 — 기본 failSeqCnt 내림차순, 통계 없는 행은 뒤, 동률은 키 순")
    fun sort() {
        fun r(sn: String, fails: Long?, rate: Double = 0.0) = AoiSerialService.SerialRow(
            day("S120", "MQ-008", "20260910", sn, 1, "2026-09-11T0${sn.last()}:00:00"),
            fails?.let { SerialStat(SerialKey("S120", "MQ-008", "20260910", sn), 1000, it, null, emptyList()) }, 58)
        val rows = listOf(r("00001", 10), r("00002", 300), r("00003", null), r("00004", 300))
        assertEquals(listOf("00002", "00004", "00001", "00003"), service.sortRows(rows, "failSeqCnt", true).map { it.key.serialNo })
        assertEquals(listOf("00003", "00001", "00002", "00004"), service.sortRows(rows, "failSeqCnt", false).map { it.key.serialNo })
        assertEquals(listOf("00004", "00003", "00002", "00001"), service.sortRows(rows, "lastAt", true).map { it.key.serialNo })
    }

    @Test
    @DisplayName("기간 — date 가 있으면 하루, 없으면 from/to(상한 7일), 둘 다 없으면 오늘")
    fun period() {
        assertEquals(LocalDate.parse("2026-09-11") to LocalDate.parse("2026-09-11"), service.periodOf("2026-09-11", "2026-09-01", "2026-09-30"))
        assertEquals(LocalDate.parse("2026-09-05") to LocalDate.parse("2026-09-11"), service.periodOf(null, "2026-09-05", "2026-09-11"))
        assertEquals(LocalDate.now() to LocalDate.now(), service.periodOf(null, null, null))
        assertThrows(InvalidParameterException::class.java) { service.periodOf(null, "2026-09-01", "2026-09-11") }
    }

    @Test
    @DisplayName("MSSQL URL — 문자열 파라미터를 varchar 로 보내는 플래그를 없으면 붙이고, 있으면 건드리지 않는다")
    fun unicodeFlag() {
        assertEquals("jdbc:sqlserver://h;databaseName=EDGE;sendStringParametersAsUnicode=false",
            com.dwje.api.config.AoiMssqlConfig.withVarcharParams("jdbc:sqlserver://h;databaseName=EDGE;"))
        assertEquals("jdbc:sqlserver://h;sendStringParametersAsUnicode=true",
            com.dwje.api.config.AoiMssqlConfig.withVarcharParams("jdbc:sqlserver://h;sendStringParametersAsUnicode=true"))
    }

    @Test
    @DisplayName("시리얼 통계 topN 범위 검증")
    fun topN() {
        val repo = AoiDimensionRepository(null)
        assertThrows(IllegalArgumentException::class.java) { repo.serialStats(listOf(SerialKey("S120", "MQ-008", "20260910", "00013")), 500) }
        assertTrue(repo.serialStats(emptyList(), 20).isEmpty(), "키가 없으면 원천을 부르지 않는다")
    }
}
