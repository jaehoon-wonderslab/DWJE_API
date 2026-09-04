package com.dwje.api.common.util

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 집계 구간 — 시작 포함, 종료 제외 (`>= from AND < toExclusive`).
 *
 * 일일 생산현황 보고의 집계 구간(전일 20:00 ~ 당일 08:00)은 자정을 넘으므로
 * 날짜 한 건으로는 표현할 수 없다. 화면은 대상일 하나만 고르고 구간 규칙은
 * 서버가 갖는다. (일일 생산현황 보고 §집계 구간)
 */
data class TimeWindow(val from: LocalDateTime, val toExclusive: LocalDateTime) {

    init {
        require(from.isBefore(toExclusive)) {
            "집계 구간의 시작은 종료보다 앞서야 한다: $from ~ $toExclusive"
        }
    }

    companion object {
        /** 하루 전체 — 00:00 부터 다음 날 00:00 까지. */
        fun ofDay(date: LocalDate): TimeWindow =
            TimeWindow(date.atStartOfDay(), date.plusDays(1).atStartOfDay())
    }
}
