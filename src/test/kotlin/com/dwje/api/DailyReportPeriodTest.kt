package com.dwje.api

import com.dwje.api.common.util.DailyReportPeriod
import com.dwje.api.common.util.TimeWindow
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
 * 일일 생산현황 보고 집계 구간 계약 테스트
 *
 * 집계 구간은 **전일 20:00 ~ 당일 08:00** 이고, 화면은 대상일 하나만 고른다.
 *
 * 이 테스트가 있는 이유: 고치기 전 초안은 `from`/`to` 를 **날짜**로 넘겨
 * 실제로는 48시간(09-01 00:00 ~ 09-03 00:00)을 집계했다. 문서에 적힌
 * 08:00~08:00 과도 어긋났는데 응답의 `periodFrom`/`periodTo` 는 그럴듯한 값을
 * 그대로 보여 줘서 화면에서는 티가 나지 않았다. 즉 **구간 표기와 집계 범위가
 * 따로 놀아도 아무도 모르는** 실패 방식이었다.
 *
 * 그래서 규칙 자체(1~5)와 그 규칙이 집계에 주입되는지(6)를 함께 고정한다.
 */
class DailyReportPeriodTest {

    @Test
    @DisplayName("1. 대상일 구간은 전일 20:00 ~ 당일 08:00 이다")
    fun dailyWindow() {
        val w = DailyReportPeriod.of(LocalDate.of(2026, 9, 2))
        assertEquals(LocalDateTime.of(2026, 9, 1, 20, 0), w.from)
        assertEquals(LocalDateTime.of(2026, 9, 2, 8, 0), w.toExclusive)
    }

    @Test
    @DisplayName("2. 구간은 12시간이고 자정을 넘는다")
    fun spansMidnight() {
        val w = DailyReportPeriod.of(LocalDate.of(2026, 9, 2))
        assertEquals(
            12L, Duration.between(w.from, w.toExclusive).toHours(),
            "야간 교대 20:00 부터 주간 교대 시작 08:00 까지는 12시간이다"
        )
        assertTrue(
            w.from.toLocalDate() != w.toExclusive.toLocalDate(),
            "구간은 자정을 넘는다 — date_trunc('day') 로는 만들 수 없다"
        )
    }

    @Test
    @DisplayName("3. 주간 누적은 그 주 월요일 20:00 에 시작한다")
    fun weeklyStartsMonday() {
        // 2026-09-02 는 수요일, 그 주 월요일은 2026-08-31.
        val w = DailyReportPeriod.ofWeek(LocalDate.of(2026, 9, 2))
        assertEquals(LocalDateTime.of(2026, 8, 31, 20, 0), w.from)
        assertEquals(LocalDateTime.of(2026, 9, 2, 8, 0), w.toExclusive)
    }

    @Test
    @DisplayName("4. 어느 요일이든 주간 구간이 뒤집히지 않는다 — 월요일은 그 날 구간과 같다")
    fun weeklyNeverInverts() {
        // 2026-08-31(월) ~ 2026-09-06(일) 한 주를 모두 확인한다.
        (0..6).forEach { offset ->
            val target = LocalDate.of(2026, 8, 31).plusDays(offset.toLong())
            val day = DailyReportPeriod.of(target)
            val week = DailyReportPeriod.ofWeek(target)

            assertTrue(
                week.from.isBefore(week.toExclusive),
                "$target (${target.dayOfWeek}) 주간 구간이 뒤집혔다: ${week.from} ~ ${week.toExclusive}"
            )
            assertTrue(
                !week.from.isAfter(day.from),
                "$target (${target.dayOfWeek}) 주간 구간이 그 날 구간보다 늦게 시작한다 — " +
                    "그 날 실적이 주간 누적에서 빠진다: 주간 ${week.from} vs 당일 ${day.from}"
            )
        }

        // 월요일은 그 주의 첫 보고이므로 주간 누적이 그 날 구간과 같아야 한다.
        val monday = LocalDate.of(2026, 8, 31)
        assertEquals(
            DailyReportPeriod.of(monday), DailyReportPeriod.ofWeek(monday),
            "월요일 보고의 주간 누적은 그 날 구간과 같다"
        )
    }

    @Test
    @DisplayName("5. TimeWindow 는 뒤집힌 구간을 거부한다")
    fun rejectsInvertedWindow() {
        val at = LocalDateTime.of(2026, 9, 2, 8, 0)
        assertThrows(IllegalArgumentException::class.java) { TimeWindow(at, at) }
        assertThrows(IllegalArgumentException::class.java) { TimeWindow(at, at.minusHours(1)) }

        val day = TimeWindow.ofDay(LocalDate.of(2026, 9, 2))
        assertEquals(LocalDateTime.of(2026, 9, 2, 0, 0), day.from)
        assertEquals(LocalDateTime.of(2026, 9, 3, 0, 0), day.toExclusive)
    }

    @Test
    @DisplayName("6. 양식 본문 집계는 구간을 받는다 — 날짜 단위 호출로 되돌아가면 실패")
    fun sheetReceivesWindow() {
        // 문서 관리 제거(2026-09-04) 로 초안 생성(generateDraft)이 없어졌다.
        // 이제 구간을 주입받아야 하는 집계는 양식 본문 하나다.
        val src = File("src/main/kotlin/com/dwje/api/service/DailyReportService.kt").readText()
        val sheet = Regex("""fun getSheet\(.*?\n    \}""", RegexOption.DOT_MATCHES_ALL)
            .find(src)?.value
            ?: error("getSheet 를 찾지 못했다 — 이름이 바뀌었으면 이 테스트도 함께 고쳐야 한다")

        assertTrue(
            sheet.contains("DailyReportPeriod.of(target)"),
            "대상일 구간은 DailyReportPeriod 가 만들어야 한다 — 규칙이 두 곳에 생기면 어긋난다"
        )
        assertTrue(
            sheet.contains("window = day"),
            "findDailySheetRows 에 대상일 구간을 넘겨야 한다"
        )
        assertTrue(
            sheet.contains("reportWindows = reportWindows"),
            "주간 실적은 보고 구간 목록으로 집계해야 한다 — 연속 구간으로 되돌아가면 " +
                "주간목표(일목표 x 보고 일수)와 짝이 맞지 않는다"
        )
        assertTrue(
            !Regex("""TimeWindow\.ofDay|window = TimeWindow\.ofDay""").containsMatchIn(sheet),
            "하루 전체 구간(TimeWindow.ofDay)으로 되돌아가면 안 된다 — 보고 구간은 12시간이다"
        )
    }

    @Test
    @DisplayName("7. 양식 본문의 설비 대수는 제품 단위 DISTINCT 다 — 품목별 합계는 중복으로 센다")
    fun eqptCountIsProductGrain() {
        val src = File("src/main/kotlin/com/dwje/api/repository/ReportRepository.kt").readText()
        val sheet = Regex("""fun findDailySheetRows\(.*?\n    \}""", RegexOption.DOT_MATCHES_ALL)
            .find(src)?.value
            ?: error("findDailySheetRows 를 찾지 못했다 — 이름이 바뀌었으면 이 테스트도 함께 고쳐야 한다")

        // 설비 대수를 세는 CTE 만 떼어 낸다. 전체 SQL 을 느슨하게 훑으면
        // 뒤에 오는 week_agg 의 GROUP BY 절에 걸려 통과해 버린다. (실제로 그랬다)
        val dayAgg = Regex("""day_agg AS \((.*?)\n            \),""", RegexOption.DOT_MATCHES_ALL)
            .find(sheet)?.groupValues?.get(1)
            ?: error("day_agg CTE 를 찾지 못했다 — SQL 구조가 바뀌었으면 이 테스트도 함께 고쳐야 한다")

        assertTrue(
            dayAgg.contains("count(DISTINCT eqpt_cd)"),
            "설비 대수는 count(DISTINCT eqpt_cd) 로 센다: $dayAgg"
        )

        val groupBy = Regex("""GROUP BY ([^\n]*)""").find(dayAgg)?.groupValues?.get(1)?.trim()
            ?: error("day_agg 에 GROUP BY 가 없다")
        assertEquals(
            "product, wc_cd", groupBy,
            "설비 대수는 제품 × 공정 단위로 세야 한다. 품목을 GROUP BY 에 넣으면 " +
                "한 설비가 같은 모델의 여러 품목을 찍을 때 중복으로 센다 " +
                "(실데이터 확인: D63A/S136 은 1대인데 품목 단위로는 2대가 된다)"
        )
        assertTrue(
            !sheet.contains("sum(eqpt_cnt)"),
            "품목별 설비 대수를 더하면 안 된다 — 중복 계산이 된다"
        )
    }

    @Test
    @DisplayName("8. 매핑 없는 품목의 실적을 버리지 않는다")
    fun unmappedItemsSurvive() {
        val src = File("src/main/kotlin/com/dwje/api/repository/ReportRepository.kt").readText()
        val sheet = Regex("""fun findDailySheetRows\(.*?\n    \}""", RegexOption.DOT_MATCHES_ALL)
            .find(src)?.value ?: error("findDailySheetRows 를 찾지 못했다")

        assertTrue(
            sheet.contains("LEFT JOIN ax.tb_prod_item_map"),
            "품목 → 제품 매핑은 LEFT JOIN 이어야 한다. INNER JOIN 이면 매핑 없는 실적이 사라져 " +
                "양식 본문 합계가 구간 합계와 어긋난다 (매핑은 1,106/1,139 만 있다)"
        )
        assertTrue(
            sheet.contains("coalesce(p.model_cd, lh.item_cd)"),
            "매핑이 없으면 item_cd 를 제품 코드 자리에 그대로 둔다"
        )
    }

    @Test
    @DisplayName("9. weekDays 는 그 주에 들어간 보고 구간의 개수다")
    fun weekDaysCountsReportWindows() {
        // 2026-08-31 이 월요일인 주.
        mapOf(
            LocalDate.of(2026, 8, 31) to 1, // 월 — 그 주 첫 보고
            LocalDate.of(2026, 9, 1) to 1, // 화
            LocalDate.of(2026, 9, 2) to 2, // 수
            LocalDate.of(2026, 9, 3) to 3, // 목
            LocalDate.of(2026, 9, 4) to 4, // 금
            LocalDate.of(2026, 9, 5) to 5, // 토
            LocalDate.of(2026, 9, 6) to 6 // 일
        ).forEach { (target, expected) ->
            assertEquals(
                expected, DailyReportPeriod.weekDays(target),
                "$target (${target.dayOfWeek}) 의 주간 일수"
            )
        }

        // 원본 양식 대조: 2026-08-20(목) 은 주간목표가 일목표의 3배였다.
        assertEquals(
            3, DailyReportPeriod.weekDays(LocalDate.of(2026, 8, 20)),
            "원본 양식의 26.08.20(목) 주간목표 1,326k ÷ 일목표 442k = 3 과 맞아야 한다"
        )
    }

    @Test
    @DisplayName("10. weekDays 는 1 보다 작아지지 않는다 — 주간목표가 0 이 되면 달성률이 무한이 된다")
    fun weekDaysNeverZero() {
        (0..365).forEach { offset ->
            val target = LocalDate.of(2026, 1, 1).plusDays(offset.toLong())
            assertTrue(
                DailyReportPeriod.weekDays(target) >= 1,
                "$target (${target.dayOfWeek}) 의 주간 일수가 1 미만이다 — " +
                    "화면이 주간목표 = 일목표 × weekDays 로 쓰므로 0 이면 목표가 사라진다"
            )
        }
    }

    @Test
    @DisplayName("11. weekWindows 는 그 주 보고 구간을 weekDays 개 만큼 오래된 순서로 낸다")
    fun weekWindowsShape() {
        val target = LocalDate.of(2026, 9, 2) // 수요일
        val windows = DailyReportPeriod.weekWindows(target)

        assertEquals(DailyReportPeriod.weekDays(target), windows.size, "구간 개수는 weekDays 와 같다")
        assertEquals(
            listOf(
                LocalDateTime.of(2026, 8, 31, 20, 0) to LocalDateTime.of(2026, 9, 1, 8, 0),
                LocalDateTime.of(2026, 9, 1, 20, 0) to LocalDateTime.of(2026, 9, 2, 8, 0)
            ),
            windows.map { it.from to it.toExclusive }
        )
        // 마지막 구간은 대상일의 보고 구간이다 — 주간 실적에 그 날이 빠지면 안 된다.
        assertEquals(DailyReportPeriod.of(target), windows.last())
    }

    @Test
    @DisplayName("12. 보고 구간들은 겹치지 않고 주간 연속 구간 안에 들어간다")
    fun weekWindowsDisjointAndBounded() {
        (0..365).forEach { offset ->
            val target = LocalDate.of(2026, 1, 1).plusDays(offset.toLong())
            val windows = DailyReportPeriod.weekWindows(target)
            val week = DailyReportPeriod.ofWeek(target)

            assertEquals(
                week.from, windows.first().from,
                "$target : 주간 연속 구간의 시작은 첫 보고 구간의 시작과 같아야 한다"
            )
            assertEquals(
                week.toExclusive, windows.last().toExclusive,
                "$target : 주간 연속 구간의 끝은 마지막 보고 구간의 끝과 같아야 한다"
            )

            windows.forEach { w ->
                assertEquals(
                    12L, Duration.between(w.from, w.toExclusive).toHours(),
                    "$target : 보고 구간은 12시간이다"
                )
            }
            windows.zipWithNext().forEach { (a, b) ->
                assertTrue(
                    !b.from.isBefore(a.toExclusive),
                    "$target : 보고 구간이 겹친다 — 실적이 두 번 더해진다: $a / $b"
                )
            }
        }
    }

    @Test
    @DisplayName("13. 주간 실적은 보고 구간 합과 연속 구간을 따로 낸다 — 한 이름에 두 기준을 담지 않는다")
    fun weekQtySeparatesShiftBasis() {
        val src = File("src/main/kotlin/com/dwje/api/repository/ReportRepository.kt").readText()
        val sheet = Regex("""fun findDailySheetRows\(.*?\n    \}""", RegexOption.DOT_MATCHES_ALL)
            .find(src)?.value ?: error("findDailySheetRows 를 찾지 못했다")

        assertTrue(
            sheet.contains("FILTER (WHERE is_report_shift)"),
            "주간 실적(week_qty)은 보고 구간에 든 것만 더해야 한다. " +
                "연속 구간으로 더하면 주간 교대가 섞여 주간목표(일목표 x 보고 일수)와 짝이 맞지 않는다 " +
                "(D64S/W110 09-02 : 606,225 vs 915,009)"
        )
        assertTrue(
            sheet.contains("week_qty_all_shift"),
            "연속 구간 합은 지우지 말고 별도 열로 남긴다 — 낮 근무 실적을 보려는 화면이 있다"
        )

        // timestamptz::time 은 세션 TimeZone 을 따라 경계가 조용히 밀린다.
        assertTrue(
            !sheet.contains("::time"),
            "보고 구간 경계를 SQL 의 ::time 으로 판정하면 안 된다 — " +
                "timestamptz::time 은 세션 TimeZone 설정을 따르므로 접속 세션이 UTC 면 " +
                "20:00/08:00 경계가 9시간 밀린 채 집계된다. 경계는 파라미터로 넘긴다"
        )
        assertTrue(
            sheet.contains("shiftFrom") && sheet.contains("shiftTo"),
            "보고 구간 경계는 이름 붙인 파라미터(shiftFrom/shiftTo)로 넘겨야 한다"
        )
    }

    @Test
    @DisplayName("14. 주간 집계가 조인을 주도한다 — 대상일에 없고 그 주에만 있는 제품이 빠지면 주간 합계가 모자란다")
    fun weekAggregationDrivesTheJoin() {
        val src = File("src/main/kotlin/com/dwje/api/repository/ReportRepository.kt").readText()
        val sheet = Regex("""fun findDailySheetRows\(.*?\n    \}""", RegexOption.DOT_MATCHES_ALL)
            .find(src)?.value ?: error("findDailySheetRows 를 찾지 못했다")

        // 최종 SELECT 의 FROM 절이 무엇인지 본다.
        val fromClause = Regex("""\n            FROM (\w+)""").findAll(sheet)
            .map { it.groupValues[1] }.toList()
        assertTrue(
            fromClause.contains("week_agg"),
            "최종 SELECT 는 week_agg 에서 시작해야 한다. day_agg 로 주도하면 대상일 구간에 " +
                "실적이 없고 그 주에만 있는 제품(낮 근무만 돌린 제품 등)의 행이 빠져 " +
                "주간 합계가 조용히 모자란다. 실측: 08-28 대상일에서 PDX-S/W120 이 빠져 " +
                "주간 연속구간 합이 60,000 적었다. FROM 절=$fromClause"
        )
        assertTrue(
            Regex("""LEFT JOIN day_agg\s+d""").containsMatchIn(sheet),
            "day_agg 는 LEFT JOIN 이어야 한다 — INNER 면 같은 누락이 다시 생긴다"
        )
        // 대상일 실적이 없는 행은 0 으로 내려야 한다. null 이면 화면이 합계를 못 낸다.
        listOf("coalesce(d.qty, 0)", "coalesce(d.ok_qty, 0)", "coalesce(d.ng_qty, 0)", "coalesce(d.eqpt_cnt, 0)")
            .forEach { expr ->
                assertTrue(sheet.contains(expr), "대상일 실적이 없는 행은 0 으로 내려야 한다: $expr")
            }
    }
}
