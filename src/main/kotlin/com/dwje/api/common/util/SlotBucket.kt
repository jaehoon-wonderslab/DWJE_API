package com.dwje.api.common.util

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.MonthDay
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/** 시계열 한 칸의 단위. */
enum class SlotUnit { HOUR, DAY, WEEK }

/**
 * 시계열 집계 칸 규칙.
 *
 * 구간이 길어지면 시간 단위로는 칸이 수백 개가 되어 화면이 못 읽는다.
 * (2시간 단위로 석 달이면 1,080칸이다.) 그래서 구간 길이에 따라 칸을
 * 시간 → 일 → 주로 넓힌다.
 *
 * 규칙을 서버 한 곳에 두고 축 라벨까지 서버가 만들어 내려준다. 화면이 따로
 * 계산하면 두 규칙이 갈린다.
 */
data class SlotBucket(
    val unit: SlotUnit,
    val intervalHour: Int,
    val format: String
) {

    /**
     * 시각 컬럼을 칸 시작 시각으로 자르는 SQL 식.
     *
     * 시간 단위는 하루 안에서 `시 % 구간` 으로 자른다 — 하루를 넘겨 자르면
     * 날짜 경계가 어긋난다. 일·주는 `date_trunc` 가 경계를 맞춰 준다.
     */
    fun slotExpr(column: String): String = when (unit) {
        SlotUnit.HOUR ->
            "date_trunc('hour', $column) - make_interval(hours => (extract(hour FROM $column)::int % :intervalHour))"
        SlotUnit.DAY -> "date_trunc('day', $column)"
        SlotUnit.WEEK -> "date_trunc('week', $column)"
    }

    /** 칸 시작 시각을 축 라벨로 만드는 SQL 식. */
    fun labelExpr(column: String): String = "to_char(${slotExpr(column)}, '$format')"

    /** 칸을 만들어 내는 SQL 식 — 실적이 없는 칸도 0으로 그려야 할 때 쓴다. */
    fun stepInterval(): String = when (unit) {
        SlotUnit.HOUR -> "make_interval(hours => :intervalHour)"
        SlotUnit.DAY -> "make_interval(days => 1)"
        SlotUnit.WEEK -> "make_interval(days => 7)"
    }

    /** [format](PostgreSQL to_char) 과 같은 라벨을 만드는 Java 패턴. */
    private val javaFormat: DateTimeFormatter = DateTimeFormatter.ofPattern(
        when (format) {
            "HH24:MI" -> "HH:mm"
            "MM-DD HH24시" -> "MM-dd HH시"
            else -> "MM-dd"
        }
    )

    /**
     * 시각이 속한 칸의 시작 시각 — [slotExpr] 과 같은 규칙을 Kotlin 으로 옮긴 것.
     *
     * 칸 하나를 다시 조회할 때(칸 클릭 상세) SQL 과 같은 경계를 써야 합계가 칸 값과 맞는다.
     */
    fun slotStartOf(at: LocalDateTime): LocalDateTime = when (unit) {
        SlotUnit.HOUR -> at.truncatedTo(ChronoUnit.HOURS).minusHours((at.hour % intervalHour).toLong())
        SlotUnit.DAY -> at.toLocalDate().atStartOfDay()
        SlotUnit.WEEK -> at.toLocalDate().with(DayOfWeek.MONDAY).atStartOfDay()
    }

    /** 칸 시작 시각의 다음 칸 시작 — 칸의 끝(미포함). [stepInterval] 과 같다. */
    fun slotEndOf(start: LocalDateTime): LocalDateTime = when (unit) {
        SlotUnit.HOUR -> start.plusHours(intervalHour.toLong())
        SlotUnit.DAY -> start.plusDays(1)
        SlotUnit.WEEK -> start.plusDays(7)
    }

    /** 칸 시작 시각의 축 라벨 — [labelExpr] 과 같은 문자열. */
    fun labelOf(start: LocalDateTime): String = start.format(javaFormat)

    /**
     * 축 라벨을 칸 시작 시각으로 되돌린다. 라벨에 연도가 없어 [window] 안에서 찾는다.
     *
     * @return 구간 안에 그 라벨의 칸이 없거나 형식이 다르면 null
     */
    fun parseLabel(label: String, window: TimeWindow): LocalDateTime? {
        val text = label.trim()
        return try {
            when (format) {
                "HH24:MI" -> {
                    val time = LocalTime.parse(text, javaFormat)
                    window.from.toLocalDate().atTime(time).takeIf { it in window }
                        ?: window.from.toLocalDate().plusDays(1).atTime(time).takeIf { it in window }
                }
                else -> {
                    val monthDay = MonthDay.parse(text.substring(0, 5), DateTimeFormatter.ofPattern("MM-dd"))
                    val hour = if (format == "MM-DD HH24시") text.substring(6, 8).toInt() else 0
                    (window.from.year..window.toExclusive.year)
                        .map { year -> monthDay.atYear(year).atTime(hour, 0) }
                        .firstOrNull { it in window }
                }
            }
        } catch (e: DateTimeParseException) {
            null
        } catch (e: IndexOutOfBoundsException) {
            null
        } catch (e: NumberFormatException) {
            null
        }
    }

    private operator fun TimeWindow.contains(at: LocalDateTime): Boolean =
        !at.isBefore(from) && at.isBefore(toExclusive)

    /** 화면이 축을 어떻게 그릴지 알 수 있도록 응답에 싣는 설명. */
    fun describe(): Map<String, Any?> = mapOf(
        "unit" to unit.name,
        "intervalHour" to intervalHour,
        "note" to when (unit) {
            SlotUnit.HOUR -> "${intervalHour}시간 단위"
            SlotUnit.DAY -> "일 단위 — 구간이 ${HOURLY_MAX_DAYS}일을 넘어 시간 단위를 접었다"
            SlotUnit.WEEK -> "주 단위(월요일 시작) — 구간이 ${DAILY_MAX_DAYS}일을 넘어 일 단위를 접었다"
        }
    )

    companion object {
        /** 이 일수까지는 시간 단위로 그린다. 7일 × 12칸 = 84칸. */
        const val HOURLY_MAX_DAYS = 7L

        /** 이 일수까지는 일 단위로 그린다. 석 달이면 92칸. */
        const val DAILY_MAX_DAYS = 120L

        fun of(window: TimeWindow, intervalHour: Int): SlotBucket {
            val days = ChronoUnit.DAYS.between(
                window.from.toLocalDate(), window.toExclusive.toLocalDate()
            )
            return when {
                days <= 1 -> SlotBucket(SlotUnit.HOUR, intervalHour, "HH24:MI")
                days <= HOURLY_MAX_DAYS -> SlotBucket(SlotUnit.HOUR, intervalHour, "MM-DD HH24시")
                days <= DAILY_MAX_DAYS -> SlotBucket(SlotUnit.DAY, 24, "MM-DD")
                else -> SlotBucket(SlotUnit.WEEK, 24 * 7, "MM-DD")
            }
        }
    }
}
