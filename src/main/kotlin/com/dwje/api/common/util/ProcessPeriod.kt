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

    fun buckets(): List<String> = generateSequence(from) { it.plusDays(1) }
        .takeWhile { !it.isAfter(to) }.map { bucket(it).toString() }.distinct().toList()

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
