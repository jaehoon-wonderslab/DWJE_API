package com.dwje.api.common.util

import com.dwje.api.common.exception.InvalidParameterException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * 조회 기간·일자 파라미터 변환 유틸
 *
 * 공통 요청 파라미터 규약
 * - from / to : YYYY-MM-DD
 * - yearMonth : YYYY-MM
 */
object DateUtils {

    val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    val DATETIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    val YEAR_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM")

    /**
     * YYYY-MM-DD 문자열을 LocalDate 로 변환한다. 값이 없으면 [default] 를 사용한다.
     *
     * @throws InvalidParameterException 형식이 올바르지 않은 경우
     */
    fun parseDate(value: String?, paramName: String, default: LocalDate? = null): LocalDate {
        if (value.isNullOrBlank()) {
            return default ?: throw InvalidParameterException("필수 파라미터가 누락되었습니다. [$paramName]", paramName)
        }
        return try {
            LocalDate.parse(value.trim(), DATE)
        } catch (e: DateTimeParseException) {
            throw InvalidParameterException("일자 형식이 올바르지 않습니다(YYYY-MM-DD). [$paramName=$value]", paramName)
        }
    }

    /**
     * YYYY-MM 문자열을 YearMonth 로 변환한다. 값이 없으면 전월을 사용한다.
     */
    fun parseYearMonth(value: String?, paramName: String = "yearMonth"): YearMonth {
        if (value.isNullOrBlank()) return YearMonth.now().minusMonths(1)
        return try {
            YearMonth.parse(value.trim(), YEAR_MONTH)
        } catch (e: DateTimeParseException) {
            throw InvalidParameterException("연월 형식이 올바르지 않습니다(YYYY-MM). [$paramName=$value]", paramName)
        }
    }

    /**
     * 조회 기간(from~to)을 검증해 [Pair] 로 반환한다.
     *
     * - from 미지정 시 to 기준 [defaultDays] 일 전
     * - to   미지정 시 오늘
     * - from > to 이면 파라미터 오류
     */
    fun periodOf(from: String?, to: String?, defaultDays: Long = 30): Pair<LocalDate, LocalDate> {
        val toDate = parseDate(to, "to", LocalDate.now())
        val fromDate = parseDate(from, "from", toDate.minusDays(defaultDays))
        if (fromDate.isAfter(toDate)) {
            throw InvalidParameterException("조회 시작일이 종료일보다 늦습니다. [from=$fromDate, to=$toDate]", "from")
        }
        return fromDate to toDate
    }

    /** DB timestamptz 컬럼 값을 응답용 문자열(yyyy-MM-dd HH:mm:ss)로 변환한다. */
    fun format(value: Any?): String? = when (value) {
        null -> null
        is OffsetDateTime -> value.toLocalDateTime().format(DATETIME)
        is LocalDateTime -> value.format(DATETIME)
        is LocalDate -> value.format(DATE)
        is java.sql.Timestamp -> value.toLocalDateTime().format(DATETIME)
        is java.sql.Date -> value.toLocalDate().format(DATE)
        else -> value.toString()
    }

    /** 경과 시간을 "3시간 12분" 형태 한글 문자열로 변환한다. */
    fun humanizeMinutes(minutes: Long?): String? {
        if (minutes == null) return null
        if (minutes < 60) return "${minutes}분"
        val h = minutes / 60
        val m = minutes % 60
        return if (m == 0L) "${h}시간" else "${h}시간 ${m}분"
    }
}
