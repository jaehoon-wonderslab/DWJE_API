package com.dwje.api.common.util

import com.dwje.api.common.exception.InvalidParameterException

/**
 * 공통 페이징·정렬 파라미터
 *
 * 공통 요청 파라미터 규약 — page/size 기본값 1 / 50, sort 는 "field,asc|desc"
 *
 * @param page 1-base 페이지 번호
 * @param size 페이지당 건수 (최대 1000)
 */
data class PageRequestParam(
    val page: Int = 1,
    val size: Int = 50
) {
    /** 전량 조회 요청인지 여부 — [ofAllowAll] 로 만들고 `size=0` 을 받은 경우 */
    val isAll: Boolean get() = size <= 0

    /** SQL OFFSET 값 */
    val offset: Int get() = if (isAll) 0 else (page - 1) * size

    /** SQL LIMIT 값 */
    val limit: Int get() = size

    /** SQL LIMIT 값 — 전량 조회면 null (LIMIT 절을 붙이지 않는다) */
    val limitOrNull: Int? get() = if (isAll) null else size

    companion object {
        private const val MAX_SIZE = 1000

        /**
         * 요청 값에서 페이징 파라미터를 생성한다. 범위를 벗어나면 기본값으로 보정한다.
         */
        fun of(page: Int?, size: Int?): PageRequestParam {
            val p = (page ?: 1).coerceAtLeast(1)
            val s = (size ?: 50).coerceIn(1, MAX_SIZE)
            return PageRequestParam(p, s)
        }

        /**
         * 전량 조회를 허용하는 페이징 파라미터.
         *
         * `size=0` 이면 쪽을 나누지 않고 전체를 한 번에 반환한다([isAll]).
         * 인쇄·엑셀 내려받기처럼 화면이 전 건을 한꺼번에 필요로 하는 목록에만 쓴다.
         * `size` 를 아예 넘기지 않으면 기본 쪽 크기가 적용된다.
         */
        fun ofAllowAll(page: Int?, size: Int?): PageRequestParam =
            if (size != null && size <= 0) PageRequestParam(page = 1, size = 0) else of(page, size)
    }
}

/**
 * 정렬 파라미터 해석기
 *
 * 클라이언트가 넘긴 "field,asc|desc" 문자열을 화이트리스트 기반으로 ORDER BY 절로 변환한다.
 * 컬럼명을 SQL 에 직접 연결하므로 반드시 허용 목록(allowed) 안에서만 매핑한다. (SQL Injection 방지)
 */
object SortResolver {

    /**
     * @param sort    요청 정렬 문자열 (예: "qty,desc")
     * @param allowed 정렬 허용 맵 — "요청 필드명" to "실제 SQL 컬럼/표현식"
     * @param default 기본 ORDER BY 절 (허용되지 않는 요청일 때 사용)
     * @return "ORDER BY ..." 절 (앞뒤 공백 없음)
     */
    fun resolve(sort: String?, allowed: Map<String, String>, default: String): String {
        if (sort.isNullOrBlank()) return "ORDER BY $default"

        val parts = sort.split(",").map { it.trim() }
        val field = parts.getOrNull(0) ?: return "ORDER BY $default"
        val direction = when (parts.getOrNull(1)?.lowercase()) {
            "desc" -> "DESC"
            "asc", null, "" -> "ASC"
            else -> throw InvalidParameterException("정렬 방향은 asc 또는 desc 만 허용합니다. [sort=$sort]", "sort")
        }

        // 화이트리스트에 없는 컬럼은 SQL 로 넘기지 않는다.
        val column = allowed[field]
            ?: throw InvalidParameterException("정렬할 수 없는 항목입니다. [sort=$field]", "sort")

        return "ORDER BY $column $direction"
    }
}
