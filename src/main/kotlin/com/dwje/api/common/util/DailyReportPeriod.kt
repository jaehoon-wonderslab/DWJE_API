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
 *
 * 2026-09-16 변경: 시작 시각을 20:00 에서 08:00 으로 옮겼다. 그 전까지 이 보고는
 * 야간 교대분(12시간)만 봤는데, 현업 기준이 **하루 전체**로 바뀌었다.
 * 이제 구간은 전일 08:00 ~ 당일 08:00 의 24시간이고, 주간 교대분이 함께 들어온다.
 */
object DailyReportPeriod {

    /** 집계 구간 시작 시각 — 전일 08:00 (전날 주간 교대 시작) */
    const val START_HOUR = BusinessDay.START_HOUR

    /** 집계 구간 종료 시각 — 당일 08:00 (당일 주간 교대 시작, 구간에서 제외) */
    const val END_HOUR = BusinessDay.START_HOUR

    /**
     * 대상일의 보고 구간을 낸다. (전일 08:00 ~ 당일 08:00)
     *
     * 구간은 자정을 넘으므로 `date_trunc('day', ...)` 로는 만들 수 없다.
     * 24시간이지만 달력 하루와 8시간 어긋나 있다 — 날짜 경계로 바꿔 쓰면 조용히 틀린다.
     *
     * 이 보고만의 규칙이 아니라 날짜 조회 전체의 기준이라 [BusinessDay] 가 갖는다.
     */
    fun of(target: LocalDate): TimeWindow = BusinessDay.of(target)

    /**
     * 대상일까지의 주간 누적 구간을 낸다. (그 주 월요일 08:00 ~ 대상일 08:00)
     *
     * 대상일이 월요일이면 월요일 08:00 은 구간 종료(월요일 08:00)와 같아 구간이
     * 비어 버린다. 그 주의 첫 보고 구간이 일요일 08:00 에 시작하므로 보고 구간
     * 시작보다 뒤로 가지 않게 자른다 — 월요일 보고의 주간 누적은 그 날 구간과 같아진다.
     *
     * 보고 구간이 24시간이 된 뒤로는 일별 구간이 빈틈없이 이어 붙으므로,
     * 이 연속 구간의 합과 [weekWindows] 구간들의 합이 같다.
     * (20:00 시작이던 때에는 사이의 주간 교대 12시간이 연속 구간에만 들어가 서로 달랐다.)
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
     * 세는 단위는 **보고 구간의 개수**다. 월요일 08:00 부터 대상일 08:00 까지
     * 08:00~08:00 구간이 몇 번 들어갔는지와 같다.
     *
     *   월요일 대상 → 1 (그 주 첫 보고)
     *   화요일 대상 → 1 (월 08:00 ~ 화 08:00)
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
     * 보고 구간이 24시간이 된 뒤로 이 구간들의 합은 [ofWeek] 연속 구간의 합과 같다.
     * 그래도 구간 목록으로 더하는 방식을 유지하는 이유는 두 가지다 —
     * 주간목표가 일목표 × [weekDays] 라 실적도 같은 **보고 구간 단위**로 세어야 분모와
     * 짝이 맞고, 구간 규칙이 다시 좁아져도(예: 교대분만 보기) 집계 쪽을 고치지 않아도 된다.
     * (20:00 시작이던 때에는 두 값이 실제로 달랐다 —
     *  D64S/W110 09-02 : 보고 구간 합 606,225 vs 연속 구간 915,009, 약 1.5배)
     *
     * 시각 경계를 SQL 에서 `ins_date::time` 으로 판정하지 않는 이유:
     * `timestamptz::time` 은 **세션 TimeZone 설정**을 따르므로, 접속 세션이 UTC 면
     * 08:00 경계가 9시간 밀린 채 조용히 집계된다. 경계는 여기서 만들어 넘긴다.
     */
    fun weekWindows(target: LocalDate): List<TimeWindow> {
        val days = weekDays(target)
        return (0 until days).map { i -> of(target.minusDays((days - 1 - i).toLong())) }
    }
}
