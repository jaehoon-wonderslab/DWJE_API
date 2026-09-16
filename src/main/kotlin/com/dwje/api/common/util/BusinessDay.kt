package com.dwje.api.common.util

import com.dwje.api.common.exception.InvalidParameterException
import java.time.LocalDate

/**
 * 업무일(공장이 하루로 보는 단위) 규칙 — **단일 출처**
 *
 * 덕우전자의 하루는 자정이 아니라 **오전 8시 교대**로 끊긴다.
 * 그래서 화면이 고른 날짜 `D` 는 달력 하루가 아니라 **`D 전날 08:00` ~ `D 08:00`** 을 뜻한다.
 * 일일 생산현황 보고가 쓰던 규칙을 2026-09-16 에 날짜·기간 조회 화면 전체로 넓혔다.
 *
 * ```
 *   날짜 09-16 하나        → 09-15 08:00 ~ 09-16 08:00   (24시간)
 *   기간 09-01 ~ 09-16     → 09-01 08:00 ~ 09-16 08:00   (15일)
 *   기간 09-16 ~ 09-16     → 09-15 08:00 ~ 09-16 08:00   (1일, 아래 보호장치)
 * ```
 *
 * 기간은 **시작일 08:00 에서 시작해 종료일 08:00 에 끝난다**(2026-09-16 확정).
 * 그래서 구간이 덮는 업무일은 `시작일+1 ~ 종료일` 이고, 고른 날수보다 하루 적다.
 * 화면은 같은 날을 고르지 못하게 시작일을 하루 앞당겨 준다 —
 * 그래도 API 를 직접 부르면 같은 날이 올 수 있어 여기서 한 번 더 막는다.
 *
 * 날짜 하나짜리 조회([of])는 끝 날짜가 구간의 끝을 가리킨다. 달력 기준으로 바꿔
 * 생각하면 어긋나므로, 날짜를 시각으로 바꾸는 일은 반드시 여기를 거친다.
 *
 * **적용 범위** — 생산실적 · 불량현황 · AOI(외관/치수/시리얼) · 공정 대시보드 ·
 * AI 통합 대시보드 · 지표값. 감사 로그 · 다운로드 이력 · 알림 발송 이력 ·
 * 동기화 이력 · AI 질의 이력은 IT 기록이라 자정 기준을 그대로 둔다(2026-09-16 결정).
 */
object BusinessDay {

    /** 업무일이 바뀌는 시각 — 08:00 (주간 교대 시작) */
    const val START_HOUR = 8

    /**
     * SQL 에서 업무일 버킷을 만들 때 더하는 보정값.
     *
     * `ins_date + interval '16 hours'` 를 `date_trunc` 에 넣으면 08:00 경계가 자정으로
     * 옮겨져, 버킷 라벨이 그대로 업무일 날짜가 된다.
     * (09-15 08:00 → 09-16 00:00, 09-16 07:59 → 09-16 23:59 → 둘 다 업무일 09-16)
     *
     * 보정 없이 `date_trunc('day', ins_date)` 를 쓰면 구간 양 끝이 반쪽짜리 버킷이 된다.
     * SQL 에서 `::time` 으로 경계를 판정하지 않는 이유는 [DailyReportPeriod] 에 적어 두었다.
     */
    const val BUCKET_SHIFT_SQL = "interval '${24 - START_HOUR} hours'"

    /** 날짜 하나의 업무일 구간. (전날 08:00 ~ 그 날 08:00) */
    fun of(date: LocalDate): TimeWindow = TimeWindow(
        date.minusDays(1).atTime(START_HOUR, 0),
        date.atTime(START_HOUR, 0)
    )

    /**
     * 시작일 ~ 종료일의 조회 구간. (시작일 08:00 ~ 종료일 08:00)
     *
     * 같은 날이 오면 구간이 비어 결과가 0건이 되므로, 시작일을 하루 앞당겨 그 날
     * 하루(24시간)로 만든다. 화면도 같은 보정을 하지만 API 직접 호출을 막지 못한다.
     */
    fun ofRange(from: LocalDate, to: LocalDate): TimeWindow {
        if (from.isAfter(to)) {
            throw InvalidParameterException("조회 시작일이 종료일보다 늦습니다. [from=$from, to=$to]", "from")
        }
        val start = if (from == to) from.minusDays(1) else from
        return TimeWindow(start.atTime(START_HOUR, 0), to.atTime(START_HOUR, 0))
    }

    /**
     * [ofRange] 구간이 덮는 업무일 목록. (오래된 것부터)
     *
     * 추세 차트가 빈 구간까지 채워 그리려면 "이 기간에 어떤 날이 들어 있는지"를 알아야 한다.
     * 구간과 라벨이 서로 다른 계산을 하면 축 양 끝에 빈 막대가 생기므로 여기서 함께 낸다.
     * 고른 날수보다 하나 적다 — `09-01 ~ 09-16` 은 `09-02 ~ 09-16` 의 15일이다.
     */
    fun daysOf(from: LocalDate, to: LocalDate): List<LocalDate> {
        val window = ofRange(from, to)
        return generateSequence(window.from.toLocalDate().plusDays(1)) { it.plusDays(1) }
            .takeWhile { !it.isAfter(to) }
            .toList()
    }
}
