package com.dwje.api.common.util

import com.dwje.api.common.exception.InvalidParameterException
import com.fasterxml.jackson.annotation.JsonInclude
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** Period labels are bucket start dates; boundary buckets include only requested dates. */
data class ProcessPeriod(val from: LocalDate, val to: LocalDate, val unit: String) {
    fun bucket(date: LocalDate): LocalDate = when (unit) {
        "week" -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        "month" -> date.withDayOfMonth(1)
        else -> date
    }

    /**
     * 축에 세울 구간 라벨. 조회 구간이 실제로 덮는 업무일에서 뽑는다.
     *
     * 달력 날짜(from..to)로 만들면 시작일 쪽에 빈 막대가 하나 생긴다 —
     * 구간이 `시작일 08:00` 에 시작하므로 그 날은 업무일로 들어오지 않는다.
     * 구간과 라벨은 [BusinessDay] 한 곳에서 나와야 어긋나지 않는다.
     */
    fun buckets(): List<String> =
        BusinessDay.daysOf(from, to).map { bucket(it).toString() }.distinct()

    companion object {
        fun parse(from: String, to: String, unit: String): ProcessPeriod {
            fun date(value: String, field: String): LocalDate = try {
                if (!Regex("\\d{4}-\\d{2}-\\d{2}").matches(value)) throw IllegalArgumentException()
                LocalDate.parse(value)
            } catch (_: RuntimeException) {
                throw InvalidParameterException("일자 형식이 올바르지 않습니다(YYYY-MM-DD). [$field=$value]", field)
            }
            val start = date(from, "from")
            val end = date(to, "to")
            if (start > end || ChronoUnit.DAYS.between(start, end) > 92) {
                throw InvalidParameterException("조회 기간은 시작일 ≤ 종료일이고 두 날짜 간격은 최대 92일이어야 합니다.", "to")
            }
            if (unit !in setOf("day", "week", "month")) {
                throw InvalidParameterException("집계 단위는 day, week, month 중 하나여야 합니다.", "unit")
            }
            return ProcessPeriod(start, end, unit)
        }
    }
}

/** Explicit null metrics survive the application's NON_NULL serialization default. */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class ProcessPeriodRow(
    val qty: BigDecimal?,
    val okQty: BigDecimal?,
    val ngQty: BigDecimal?,
    val defectRate: Double?,
    val yieldRate: Double?,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val period: String? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val code: String? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val productNm: String? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val processId: String? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val process: String? = null
) {
    fun masked(mask: MaskingSupport): ProcessPeriodRow = copy(
        qty = mask.on(DataField.QTY) { qty },
        okQty = mask.on(DataField.QTY) { okQty },
        ngQty = mask.on(DataField.QTY) { ngQty },
        defectRate = mask.on(DataField.YIELD) { defectRate },
        yieldRate = mask.on(DataField.YIELD) { yieldRate }
    )

    companion object {
        fun of(ok: BigDecimal?, ng: BigDecimal?): ProcessPeriodRow {
            val total = if (ok != null && ng != null) ok + ng else null
            fun rate(n: BigDecimal?): Double? = if (n == null || total == null || total.signum() == 0) null
                else n.multiply(BigDecimal(100)).divide(total, 2, RoundingMode.HALF_UP).toDouble()
            return ProcessPeriodRow(total, ok, ng, rate(ng), rate(ok))
        }
        fun empty(): ProcessPeriodRow = of(BigDecimal.ZERO, BigDecimal.ZERO)
    }
}
