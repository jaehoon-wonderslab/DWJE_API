package com.dwje.api.middleware

import com.dwje.api.common.response.ApiResponse
import com.dwje.api.common.response.ErrorCode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.annotation.Order
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * SQL 인젝션 패턴 사전 방어 필터 (미들웨어 2단계)
 *
 * URL 파라미터 및 Request Body 에서 악의적인 SQL 예약어 패턴을 정규식으로 탐지하고,
 * 위반 시 `400 Bad Request` 로 차단한다.
 *
 * 본 필터는 1차 방어선일 뿐이며, 실제 쿼리는 전 구간 NamedParameter 바인딩을 사용한다.
 */
@Component
@Order(20)
class SqlInjectionCheckFilter(private val objectMapper: ObjectMapper) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** 차단 대상 SQL 인젝션 패턴 */
        private val PATTERNS: List<Regex> = listOf(
            // UNION SELECT / UNION ALL SELECT
            Regex("""\bunion\b\s+(all\s+)?\bselect\b""", RegexOption.IGNORE_CASE),
            // DROP / TRUNCATE / ALTER TABLE
            Regex("""\b(drop|truncate|alter)\s+(table|database|schema)\b""", RegexOption.IGNORE_CASE),
            // 주석을 이용한 조건 무력화 : ' OR 1=1 --
            Regex("""('|%27)\s*(or|and)\s*('|%27)?\s*\d+\s*=\s*\d+""", RegexOption.IGNORE_CASE),
            Regex("""\b(or|and)\s+\d+\s*=\s*\d+\s*(--|#|/\*)""", RegexOption.IGNORE_CASE),
            // 저장 프로시저 실행
            Regex("""\b(exec|execute)\s*(\(|\s)+(xp_|sp_)""", RegexOption.IGNORE_CASE),
            // 다중 구문 삽입 : ; DELETE FROM
            Regex(""";\s*(delete|update|insert|drop|grant|revoke)\b""", RegexOption.IGNORE_CASE),
            // 시스템 카탈로그 탐색
            Regex("""\b(information_schema\.|pg_catalog\.|pg_sleep\s*\(|sysobjects\b)""", RegexOption.IGNORE_CASE),
            // 파일 입출력
            Regex("""\b(load_file\s*\(|into\s+(out|dump)file)\b""", RegexOption.IGNORE_CASE)
        )

        /** 본문 검사 최대 바이트 (대용량 업로드는 검사 대상에서 제외) */
        private const val MAX_BODY_INSPECT_BYTES = 512 * 1024
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        // 1. 쿼리 파라미터 검사
        val badParam = request.parameterMap.entries.firstOrNull { (_, values) ->
            values.any { detect(it) }
        }?.key

        if (badParam != null) {
            reject(request, response, "요청 파라미터에 허용되지 않는 패턴이 포함되어 있습니다. [$badParam]")
            return
        }

        // 2. Request Body 검사 (JSON 계열 · 크기 제한 내에서만)
        if (isInspectableBody(request)) {
            val body = readCachedBody(request)
            if (body != null && detect(body)) {
                reject(request, response, "요청 본문에 허용되지 않는 패턴이 포함되어 있습니다.")
                return
            }
        }

        filterChain.doFilter(request, response)
    }

    /** 문자열에서 인젝션 의심 패턴을 탐지한다. */
    private fun detect(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        return PATTERNS.any { it.containsMatchIn(value) }
    }

    /** JSON/폼 본문이면서 검사 가능한 크기인지 판정한다. */
    private fun isInspectableBody(request: HttpServletRequest): Boolean {
        val contentType = request.contentType ?: return false
        if (!contentType.contains(MediaType.APPLICATION_JSON_VALUE, ignoreCase = true)) return false
        return request.contentLength in 1..MAX_BODY_INSPECT_BYTES
    }

    /**
     * AccessLogFilter 가 감싼 캐싱 래퍼에서 본문을 읽는다.
     * 래퍼가 본문을 미리 적재해 두므로 컨트롤러의 본문 읽기에 영향을 주지 않는다.
     */
    private fun readCachedBody(request: HttpServletRequest): String? =
        (request as? CachedBodyHttpServletRequest)?.bodyAsString()

    /** 400 Bad Request 표준 응답으로 차단한다. */
    private fun reject(request: HttpServletRequest, response: HttpServletResponse, message: String) {
        log.warn("[SQL-INJECTION] 차단 : {} {} ip={}", request.method, request.requestURI, request.remoteAddr)
        response.status = ErrorCode.VALID_REQUIRED.status.value()
        response.contentType = "${MediaType.APPLICATION_JSON_VALUE};charset=UTF-8"
        response.writer.write(
            objectMapper.writeValueAsString(ApiResponse.error(ErrorCode.VALID_REQUIRED.code, message))
        )
    }
}
