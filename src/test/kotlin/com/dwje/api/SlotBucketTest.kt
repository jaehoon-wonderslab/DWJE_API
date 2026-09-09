package com.dwje.api

import com.dwje.api.common.util.SlotBucket
import com.dwje.api.common.util.SlotUnit
import com.dwje.api.common.util.TimeWindow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * 시계열 칸 규칙 테스트
 *
 * 구간이 길어질 때 칸이 몇 개가 되는지가 이 규칙의 존재 이유다.
 * 2시간 단위로 석 달을 그리면 1,080칸이라 화면이 못 읽는다.
 */
class SlotBucketTest {

    private fun window(from: String, toInclusive: String) = TimeWindow(
        LocalDate.parse(from).atStartOfDay(),
        LocalDate.parse(toInclusive).plusDays(1).atStartOfDay()
    )

    @Test
    @DisplayName("하루는 시간 단위이고 라벨에 날짜가 없다")
    fun oneDayIsHourly() {
        val b = SlotBucket.of(window("2026-09-03", "2026-09-03"), 2)
        assertEquals(SlotUnit.HOUR, b.unit)
        assertEquals(2, b.intervalHour)
        assertEquals("HH24:MI", b.format)
    }

    @Test
    @DisplayName("이틀~7일은 시간 단위이되 라벨에 날짜가 붙는다")
    fun weekIsHourlyWithDate() {
        val b = SlotBucket.of(window("2026-08-29", "2026-09-04"), 2)
        assertEquals(SlotUnit.HOUR, b.unit)
        assertEquals("MM-DD HH24시", b.format)
    }

    @Test
    @DisplayName("7일을 넘으면 일 단위로 접는다 — 석 달이 92칸")
    fun longRangeFoldsToDaily() {
        val b = SlotBucket.of(window("2026-06-06", "2026-09-05"), 2)
        assertEquals(SlotUnit.DAY, b.unit)
        assertEquals(24, b.intervalHour)
        assertEquals("MM-DD", b.format)
    }

    @Test
    @DisplayName("120일을 넘으면 주 단위로 접는다 — 1년이 53칸")
    fun veryLongRangeFoldsToWeekly() {
        val b = SlotBucket.of(window("2026-01-01", "2026-12-31"), 2)
        assertEquals(SlotUnit.WEEK, b.unit)
    }

    @Test
    @DisplayName("경계 — 7일째는 시간, 8일째는 일")
    fun boundaryIsSevenDays() {
        assertEquals(SlotUnit.HOUR, SlotBucket.of(window("2026-09-01", "2026-09-07"), 2).unit)
        assertEquals(SlotUnit.DAY, SlotBucket.of(window("2026-09-01", "2026-09-08"), 2).unit)
    }

    @Test
    @DisplayName("칸 규칙이 실제로 칸 수를 줄인다 — 석 달 2시간이면 1,080칸이다")
    fun foldingActuallyCutsSlotCount() {
        val w = window("2026-06-06", "2026-09-05")
        val days = 92L
        val hourlySlots = days * (24 / 2)
        assertEquals(1104L, hourlySlots)

        val b = SlotBucket.of(w, 2)
        val foldedSlots = days * 24 / b.intervalHour
        assertEquals(92L, foldedSlots)
    }

    @Test
    @DisplayName("일·주 칸은 시 % 구간으로 자르지 않는다 — 날짜 경계가 어긋난다")
    fun dailyBucketUsesDateTrunc() {
        val daily = SlotBucket.of(window("2026-06-06", "2026-09-05"), 2)
        assertEquals("date_trunc('day', lh.ins_date)", daily.slotExpr("lh.ins_date"))

        val weekly = SlotBucket.of(window("2026-01-01", "2026-12-31"), 2)
        assertEquals("date_trunc('week', lh.ins_date)", weekly.slotExpr("lh.ins_date"))

        val hourly = SlotBucket.of(window("2026-09-03", "2026-09-03"), 2)
        assertTrue(hourly.slotExpr("lh.ins_date").contains("% :intervalHour"))
    }

    @Test
    @DisplayName("화면이 축을 그릴 수 있도록 단위를 응답에 싣는다")
    fun describeCarriesUnit() {
        val d = SlotBucket.of(window("2026-06-06", "2026-09-05"), 2).describe()
        assertEquals("DAY", d["unit"])
        assertEquals(24, d["intervalHour"])
        assertTrue((d["note"] as String).isNotBlank())
    }
}
