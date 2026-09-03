package com.dwje.api.common.response

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * 전 API 공통 JSON 응답 래퍼
 *
 * API 목록 명세 「공통 규약 / 1. 응답 포맷」을 그대로 구현한다.
 *
 * | 필드      | 설명                                                        |
 * |-----------|-------------------------------------------------------------|
 * | success   | 처리 성공 여부                                               |
 * | code      | 비즈니스 응답 코드 (SUCCESS / E-AUTH-001 …)                  |
 * | message   | 사용자 친화적 메시지                                          |
 * | data      | 응답 본문 (object | array)                                   |
 * | meta      | 페이징 정보 — page, size, total                              |
 * | masked    | 이번 응답에서 마스킹된 데이터 항목 key 배열 (프론트 '비공개' 배지) |
 * | error     | 실패 시 오류 상세 (code, message, field)                      |
 * | timestamp | 응답 생성 일시 (epoch millis)                                 |
 *
 * @param T 응답 데이터 페이로드 타입
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApiResponse<T>(
    val success: Boolean,
    val code: String,
    val message: String,
    val data: T? = null,
    val meta: PageMeta? = null,
    val masked: List<String>? = null,
    val error: ApiError? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {

        /** 단건/객체 정상 응답 */
        fun <T> ok(data: T?, message: String = "정상 처리되었습니다."): ApiResponse<T> =
            ApiResponse(success = true, code = "SUCCESS", message = message, data = data)

        /**
         * 마스킹 정보를 포함한 정상 응답
         *
         * @param maskedKeys 데이터 접근 권한이 없어 null 로 치환된 항목 key 목록
         */
        fun <T> ok(data: T?, maskedKeys: Collection<String>, message: String = "정상 처리되었습니다."): ApiResponse<T> =
            ApiResponse(
                success = true, code = "SUCCESS", message = message, data = data,
                masked = maskedKeys.takeIf { it.isNotEmpty() }?.sorted()
            )

        /** 페이징 목록 정상 응답 */
        fun <T> page(
            data: T?,
            meta: PageMeta,
            maskedKeys: Collection<String> = emptyList(),
            message: String = "조회가 완료되었습니다."
        ): ApiResponse<T> =
            ApiResponse(
                success = true, code = "SUCCESS", message = message, data = data, meta = meta,
                masked = maskedKeys.takeIf { it.isNotEmpty() }?.sorted()
            )

        /** 실패 응답 */
        fun error(code: String, message: String, field: String? = null): ApiResponse<Nothing?> =
            ApiResponse(
                success = false, code = code, message = message, data = null,
                error = ApiError(code = code, message = message, field = field)
            )
    }
}

/**
 * 오류 상세 정보
 *
 * @param code  에러 코드 (E-AUTH-001, E-VALID-001 …)
 * @param message 오류 메시지
 * @param field 오류가 발생한 요청 필드명 (검증 오류 시)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApiError(
    val code: String,
    val message: String,
    val field: String? = null
)

/**
 * 페이징 메타 정보
 *
 * @param page  현재 페이지 (1-base)
 * @param size  페이지당 건수
 * @param total 전체 건수
 */
data class PageMeta(
    val page: Int,
    val size: Int,
    val total: Long
) {
    /** 전체 페이지 수 */
    val totalPages: Int get() = if (size <= 0) 0 else ((total + size - 1) / size).toInt()

    companion object {
        fun of(page: Int, size: Int, total: Long): PageMeta = PageMeta(page, size, total)

        /**
         * 전량 조회(`size=0`) 응답 메타 — 전체가 한 쪽에 담긴다.
         *
         * `size` 를 전체 건수로 채워 `page=1`, `totalPages=1` 이 되게 한다.
         * 클라이언트가 meta 만 보고도 더 받을 쪽이 없다는 것을 알 수 있다.
         */
        fun all(total: Long): PageMeta = PageMeta(page = 1, size = total.toInt(), total = total)
    }
}
