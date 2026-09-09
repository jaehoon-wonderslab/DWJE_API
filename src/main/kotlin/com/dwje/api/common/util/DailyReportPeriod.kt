package com.dwje.api.common.util

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * 일일 생산현황 보고 집계 구간 규칙 — **단일 출처**
 *
 * 화면은 대상일 하나만 고르고, 구간 규칙은 서버가 갖는다.
 *
 * 고치기 전에는 이 규칙이 서비스 안에 흩어져 있었고, 응답의 `periodFrom`/`periodTo` 는
 * 08:00~08:00 을 보여 주면서 실제 집계는 날짜 경계로 48시간을 더하고 있었다.
 * 표기와 집계가 따로 놀면 화면에서는 티가 나지 않으므로 한 곳에 모아 둔다.
 */
object DailyReportPeriod {

    /** 집계 구간 시작 시각 — 전일 20:00 (야간 교대 시작) */
    const val START_HOUR = 20

    /** 집계 구간 종료 시각 — 당일 08:00 (주간 교대 시작, 구간에서 제외) */
    const val END_HOUR = 8

    /**
     * 대상일의 보고 구간을 낸다. (전일 20:00 ~ 당일 08:00)
     *
     * 구간은 자정을 넘으므로 `date_trunc('day', ...)` 로는 만들 수 없다.
     */
    fun of(target: LocalDate): TimeWindow = TimeWindow(
        target.minusDays(1).atTime(START_HOUR, 0),
        target.atTime(END_HOUR, 0)
    )

    /**
     * 대상일까지의 주간 누적 구간을 낸다. (그 주 월요일 20:00 ~ 대상일 08:00)
     *
     * 대상일이 월요일이면 월요일 20:00 은 구간 종료(월요일 08:00)보다 **뒤**라
     * 구간이 뒤집힌다. 그 주의 첫 보고 구간이 일요일 20:00 에 시작하므로
     * 보고 구간 시작보다 뒤로 가지 않게 자른다 — 월요일 보고의 주간 누적은
     * 그 날 구간과 같아진다.
     *
     * 주의: 보고 구간은 12시간(20:00~08:00)이지만 주간 누적은 그 사이의
     * 주간 교대(08:00~20:00)까지 포함한 **연속 구간**이다.
     * 따라서 주간 누적은 그 주 일별 실적의 합과 같지 않다.
     */
    fun ofWeek(target: LocalDate): TimeWindow {
        val day = of(target)
        val monday: LocalDateTime = target
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .atTime(START_HOUR, 0)

        return TimeWindow(minOf(monday, day.from), day.toExclusive)
    }

    /**
     * 대상일까지 그 주에 들어간 보고 일수를 센다.
     *
     * 화면이 `주간목표 = 작성자 일목표 × weekDays` 를 계산하는 데 쓴다.
     * 구간 주인이 서버이므로 일수도 서버가 센다.
     *
     * 세는 단위는 **보고 구간의 개수**다. 월요일 20:00 부터 대상일 08:00 까지
     * 20:00~08:00 구간이 몇 번 들어갔는지와 같다.
     *
     *   월요일 대상 → 1 (그 주 첫 보고)
     *   화요일 대상 → 1 (월 20:00 ~ 화 08:00)
     *   수요일 대상 → 2
     *   목요일 대상 → 3
     *
     * 원본 양식은 제품마다 주간 일수가 다르다(가동 요일 차이로 보인다).
     * 그 규칙은 현업 확인 전까지 알 수 없으므로 여기서는 달력대로만 센다.
     */
    fun weekDays(target: LocalDate): Int {
        val monday = target.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return maxOf(1, ChronoUnit.DAYS.between(monday, target).toInt())
    }

    /**
     * 그 주에 들어간 보고 구간을 하나씩 돌려준다. (오래된 것부터)
     *
     * 주간 실적을 [ofWeek] 의 연속 구간으로 더하면 사이의 주간 교대(08:00~20:00)가
     * 섞여 들어와 **분모와 짝이 맞지 않는다.** 주간목표는 일목표 × [weekDays] 로
     * 보고 구간 단위인데 실적만 24시간 단위가 되기 때문이다.
     * (D64S/W110 09-02 : 보고 구간 합 606,225 vs 연속 구간 915,009 — 약 1.5배)
     *
     * 그래서 주간 실적은 이 구간들의 합으로 낸다.
     *
     * 시각 경계를 SQL 에서 `ins_date::time` 으로 판정하지 않는 이유:
     * `timestamptz::time` 은 **세션 TimeZone 설정**을 따르므로, 접속 세션이 UTC 면
     * 20:00/08:00 경계가 9시간 밀린 채 조용히 집계된다. 경계는 여기서 만들어 넘긴다.
     */
    fun weekWindows(target: LocalDate): List<TimeWindow> {
        val days = weekDays(target)
        return (0 until days).map { i -> of(target.minusDays((days - 1 - i).toLong())) }
    }
}
