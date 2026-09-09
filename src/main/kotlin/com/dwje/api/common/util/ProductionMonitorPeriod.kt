package com.dwje.api.common.util

import java.time.LocalDate

/**
 * 생산 모니터링 기준일의 생산 실적 집계 구간.
 *
 * 생산 모니터의 기준일은 생산 실적 조회(`/production/results`)와 같은 달력 일자다.
 * 따라서 `2026-08-30`은 `2026-08-30 00:00:00` 이상,
 * `2026-08-31 00:00:00` 미만을 뜻한다.
 */
object ProductionMonitorPeriod {
    fun of(targetDate: LocalDate): TimeWindow = TimeWindow.ofDay(targetDate)
}
