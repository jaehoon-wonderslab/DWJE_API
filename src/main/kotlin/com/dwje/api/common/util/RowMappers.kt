package com.dwje.api.common.util

import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.ResultSet

/**
 * ResultSet 값 추출 보조 확장 함수 모음
 *
 * JdbcTemplate 결과 매핑 시 null 안전성과 소수 자릿수 처리를 일관되게 유지하기 위해 사용한다.
 */
object Rs {

    /** NULL 을 보존하는 Int 추출 */
    fun intOrNull(rs: ResultSet, column: String): Int? {
        val v = rs.getInt(column)
        return if (rs.wasNull()) null else v
    }

    /** NULL 을 보존하는 Long 추출 */
    fun longOrNull(rs: ResultSet, column: String): Long? {
        val v = rs.getLong(column)
        return if (rs.wasNull()) null else v
    }

    /** NULL 을 보존하는 Double 추출 */
    fun doubleOrNull(rs: ResultSet, column: String): Double? {
        val v = rs.getDouble(column)
        return if (rs.wasNull()) null else v
    }

    /** NULL 을 보존하는 Boolean 추출 */
    fun boolOrNull(rs: ResultSet, column: String): Boolean? {
        val v = rs.getBoolean(column)
        return if (rs.wasNull()) null else v
    }

    /** 소수 [scale] 자리로 반올림한 수치 (불량률·수율·가동률 등 % 지표) */
    fun rate(rs: ResultSet, column: String, scale: Int = 2): Double? {
        val v = rs.getBigDecimal(column) ?: return null
        return v.setScale(scale, RoundingMode.HALF_UP).toDouble()
    }

    /** 수량 컬럼 — numeric(18,6) 을 정수 수량으로 환산 */
    fun qty(rs: ResultSet, column: String): Long? {
        val v = rs.getBigDecimal(column) ?: return null
        return v.setScale(0, RoundingMode.HALF_UP).toLong()
    }

    /** timestamptz/date 컬럼을 표준 문자열로 변환 */
    fun dateTime(rs: ResultSet, column: String): String? = DateUtils.format(rs.getObject(column))

    /** char(1) Y/N 을 Boolean 으로 변환 */
    fun yn(rs: ResultSet, column: String): Boolean = "Y".equals(rs.getString(column), ignoreCase = true)
}

/** Boolean 을 DB 저장용 'Y'/'N' 으로 변환한다. */
fun Boolean.toYn(): String = if (this) "Y" else "N"

/**
 * 분모가 0 이거나 null 일 때 안전하게 비율(%)을 산출한다.
 *
 * @param numerator   분자
 * @param denominator 분모
 * @param scale       소수 자릿수
 */
fun safeRate(numerator: BigDecimal?, denominator: BigDecimal?, scale: Int = 2): Double {
    if (numerator == null || denominator == null || denominator.compareTo(BigDecimal.ZERO) == 0) return 0.0
    return numerator.multiply(BigDecimal(100))
        .divide(denominator, scale, RoundingMode.HALF_UP)
        .toDouble()
}
