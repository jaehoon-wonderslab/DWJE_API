package com.dwje.api.common.util

import java.time.LocalDate
import java.time.LocalDateTime

/** 자연어에서 말한 양 끝 업무일을 모두 포함한다. 각 업무일은 전일 08:00~당일 08:00이다. */
data class AiBusinessPeriod(val from: LocalDate, val to: LocalDate) {
    init { require(!from.isAfter(to)) }
    val startInclusive: LocalDateTime get() = from.minusDays(1).atTime(BusinessDay.START_HOUR, 0)
    val endExclusive: LocalDateTime get() = to.atTime(BusinessDay.START_HOUR, 0)
}
