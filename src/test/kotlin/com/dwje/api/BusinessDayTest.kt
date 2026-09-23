package com.dwje.api

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.util.BusinessDay
import com.dwje.api.common.util.DailyReportPeriod
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 업무일 구간 계약 테스트 (2026-09-16)
 *
 * 공장의 하루는 자정이 아니라 **08:00 교대**로 끊긴다. 화면이 고른 날짜 `D` 는
 * `D 전날 08:00 ~ D 08:00` 을 뜻하고, 기간 조회도 같은 규칙을 쓴다.
 *
 * 이 테스트가 있는 이유: 날짜를 시각으로 바꾸는 곳이 저장소마다 흩어져 있었고
 * (`from.atStartOfDay()` / `to.plusDays(1).atStartOfDay()`), 한 군데만 빠뜨려도
 * **화면마다 숫자가 다른데 아무도 모르는** 실패가 된다. 규칙 자체(1~5)와
 * 그 규칙이 조회에 주입되는지(6~7)를 함께 고정한다.
 */
class BusinessDayTest {

    private val src = File("src/main/kotlin/com/dwje/api")

    @Test
    @DisplayName("1. 날짜 하나는 전날 08:00 ~ 그 날 08:00 이다")
    fun singleDay() {
        val w = BusinessDay.of(LocalDate.of(2026, 9, 16))
        assertEquals(LocalDateTime.of(2026, 9, 15, 8, 0), w.from)
        assertEquals(LocalDateTime.of(2026, 9, 16, 8, 0), w.toExclusive)
        assertEquals(24L, Duration.between(w.from, w.toExclusive).toHours())
    }

    @Test
    @DisplayName("2. 기간은 시작일 08:00 ~ 종료일 08:00 이다")
    fun range() {
        val w = BusinessDay.ofRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 16))
        assertEquals(LocalDateTime.of(2026, 9, 1, 8, 0), w.from)
        assertEquals(LocalDateTime.of(2026, 9, 16, 8, 0), w.toExclusive)
        assertEquals(
            15L, Duration.between(w.from, w.toExclusive).toDays(),
            "09-01 08:00 ~ 09-16 08:00 은 15일이다 — 고른 날수보다 하루 적다(2026-09-16 확정)"
        )
    }

    @Test
    @DisplayName("3. 같은 날이 오면 시작일을 하루 앞당겨 막는다 — 빈 구간이 되면 안 된다")
    fun sameDayIsNotEmpty() {
        val day = LocalDate.of(2026, 9, 16)
        val w = BusinessDay.ofRange(day, day)
        assertTrue(w.from.isBefore(w.toExclusive), "구간이 비면 화면이 0건으로 보인다")
        assertEquals(BusinessDay.of(day), w, "단일 날짜 조회와 같아야 한다")
        assertEquals(24L, Duration.between(w.from, w.toExclusive).toHours())
    }

    @Test
    @DisplayName("4. 구간 일수는 고른 날수보다 하루 적다 — 같은 날만 예외로 1일")
    fun dayCountMatchesSelection() {
        val from = LocalDate.of(2026, 1, 1)
        (0..400).forEach { offset ->
            val to = from.plusDays(offset.toLong())
            val w = BusinessDay.ofRange(from, to)
            val expected = if (offset == 0) 1L else offset.toLong()
            assertEquals(
                expected, Duration.between(w.from, w.toExclusive).toDays(),
                "$from ~ $to 의 구간 일수"
            )
            assertEquals(
                expected.toInt(), BusinessDay.daysOf(from, to).size,
                "$from ~ $to : 구간 일수와 축 라벨 개수가 다르면 빈 막대가 생긴다"
            )
            assertEquals(to, BusinessDay.daysOf(from, to).last(), "마지막 업무일은 종료일이다")
        }
    }

    @Test
    @DisplayName("5. 시작일이 종료일보다 늦으면 400 으로 막는다 — 500 이 되면 안 된다")
    fun rejectsInvertedRange() {
        assertThrows(InvalidParameterException::class.java) {
            BusinessDay.ofRange(LocalDate.of(2026, 9, 16), LocalDate.of(2026, 9, 15))
        }
    }

    @Test
    @DisplayName("6. 일일 생산현황 보고와 규칙이 갈라지지 않는다")
    fun dailyReportSharesTheRule() {
        (0..365).forEach { offset ->
            val d = LocalDate.of(2026, 1, 1).plusDays(offset.toLong())
            assertEquals(
                BusinessDay.of(d), DailyReportPeriod.of(d),
                "$d : 일일 보고 구간과 업무일 구간이 달라졌다 — 규칙이 두 곳에 생겼다"
            )
        }
    }

    @Test
    @DisplayName("7. 공장 데이터 조회가 날짜를 자정으로 바꾸지 않는다")
    fun factoryQueriesUseBusinessDay() {
        val targets = listOf(
            "repository/ProductionRepository.kt",
            "repository/QualityRepository.kt",
            "repository/DashboardProcessRepository.kt",
            "repository/MetricStandardRepository.kt",
            "service/AoiCosmeticService.kt",
            "service/AoiDimensionService.kt",
            "service/AoiSerialService.kt"
        )
        targets.forEach { rel ->
            val text = File(src, rel).readText()
            assertTrue(
                text.contains("BusinessDay."),
                "$rel 이 BusinessDay 를 쓰지 않는다 — 날짜 조회가 08:00 기준에서 빠졌다"
            )
            assertTrue(
                !text.contains("to.plusDays(1).atStartOfDay()") &&
                    !text.contains("key.to.plusDays(1).atStartOfDay()"),
                "$rel 에 자정 기준 변환이 남아 있다 — 화면마다 숫자가 달라진다"
            )
        }
    }

    @Test
    @DisplayName("8. 일·주·월 버킷도 08:00 로 옮긴다 — 안 그러면 양 끝이 반쪽 막대가 된다")
    fun bucketsAreShifted() {
        assertEquals("interval '16 hours'", BusinessDay.BUCKET_SHIFT_SQL, "08:00 경계를 자정으로 옮기는 보정값")

        listOf("repository/ProductionRepository.kt", "repository/DashboardProcessRepository.kt").forEach { rel ->
            val text = File(src, rel).readText()
            val dollar = '$'
            Regex("""date_trunc\((?:'${dollar}truncUnit'|:unit), ([A-Za-z_.]+)\)""").findAll(text).forEach { m ->
                error("$rel : date_trunc 가 보정 없이 ${m.groupValues[1]} 을 쓴다 — 버킷이 자정 기준으로 잘린다")
            }
            assertTrue(
                text.contains("BUCKET_SHIFT_SQL"),
                "$rel 의 기간 버킷에 BusinessDay.BUCKET_SHIFT_SQL 이 없다"
            )
        }
    }

    @Test
    @DisplayName("9. 이력·로그 조회는 자정 기준을 그대로 둔다 — IT 기록까지 옮기지 않기로 했다")
    fun logScreensKeepMidnight() {
        listOf(
            "repository/AuditLogRepository.kt",
            "repository/AlertRepository.kt",
            "repository/SyncRepository.kt",
            "repository/DownloadLogRepository.kt",
            "repository/AiChatRepository.kt"
        ).forEach { rel ->
            val text = File(src, rel).readText()
            assertTrue(
                text.contains("to.plusDays(1).atStartOfDay()"),
                "$rel 이 업무일 기준으로 넘어갔다 — 2026-09-16 결정은 공장 데이터만이다. " +
                    "범위를 넓히려면 이 테스트부터 함께 고친다"
            )
        }
    }

    @Test
    @DisplayName("10. 실시간 모니터링의 '오늘'은 자정 기준을 그대로 둔다")
    fun realtimeMonitorKeepsMidnight() {
        val text = File(src, "repository/ProductionRepository.kt").readText()
        assertTrue(
            text.contains("\"lh.ins_date >= date_trunc('day', now())\""),
            "실시간 모니터링의 오늘 필터가 바뀌었다 — 이 화면은 08:00 기준에서 뺀다(2026-09-16 결정). " +
                "지금 현장을 보는 곳이라 08:00 이전에 열면 실적이 비어 보인다"
        )
    }
}
