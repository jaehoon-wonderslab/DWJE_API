package com.dwje.api.common.util

/**
 * LIKE 검색어 처리 유틸
 *
 * 사용자 입력에 포함된 LIKE 와일드카드(%, _)를 이스케이프해 의도치 않은 전체 스캔·정보 노출을 막는다.
 * SQL 자체는 항상 NamedParameter 로 바인딩하므로 이 유틸은 값 정제만 담당한다.
 */
object SqlLikeUtils {

    /**
     * 부분 일치 검색용 파라미터 값을 생성한다. (`%keyword%`)
     *
     * @return 검색어가 비어 있으면 null
     */
    fun contains(keyword: String?): String? {
        val k = keyword?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return "%${escape(k)}%"
    }

    /**
     * 전방 일치 검색용 파라미터 값을 생성한다. (`keyword%`)
     */
    fun startsWith(keyword: String?): String? {
        val k = keyword?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return "${escape(k)}%"
    }

    /** LIKE 특수문자 이스케이프 (ESCAPE '\' 사용 전제) */
    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
