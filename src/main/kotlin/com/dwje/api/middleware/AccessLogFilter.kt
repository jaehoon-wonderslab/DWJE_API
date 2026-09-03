package com.dwje.api.middleware

import com.dwje.api.common.security.UserContext
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 접속 정보 및 감사 로그 기록 필터 (미들웨어 1단계)
 *
 * 기록 항목 : 요청 시각, 클라이언트 IP, User-Agent, URI, HTTP Method, 파라미터,
 *            응답 상태 코드, 소요 시간, 인증 사용자(사번/부서)
 *
 * 개인정보(비밀번호 등)는 마스킹 처리하여 기록한다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class AccessLogFilter : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger("com.dwje.api.access")

    companion object {
        /** 로그에 원문을 남기지 않는 민감 파라미터 */
        private val SENSITIVE_KEYS = setOf(
            "password", "pwd", "passwd", "newPassword", "currentPassword",
            "token", "accessToken", "refreshToken", "authorization", "ssn", "residentNo"
        )
        private const val MASK = "****"
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val started = System.currentTimeMillis()
        // 본문을 이후 필터(SQL 인젝션 검사)와 컨트롤러가 모두 읽을 수 있도록 캐싱 래퍼를 적용한다.
        val wrapped = CachedBodyHttpServletRequest(request)

        try {
            filterChain.doFilter(wrapped, response)
        } finally {
            val elapsed = System.currentTimeMillis() - started
            val principal = UserContext.currentOrNull()
            val actor = principal?.let { "${it.userId}/${it.deptName}" } ?: "-"

            log.info(
                "[ACCESS] {} {} status={} elapsed={}ms ip={} user={} params={} ua={}",
                request.method,
                request.requestURI,
                response.status,
                elapsed,
                clientIp(request),
                actor,
                maskedParams(request),
                request.getHeader("User-Agent")?.take(120) ?: "-"
            )
        }
    }

    /**
     * 프록시/로드밸런서를 경유한 실제 클라이언트 IP 를 추출한다.
     */
    private fun clientIp(request: HttpServletRequest): String {
        val headers = listOf("X-Forwarded-For", "X-Real-IP", "Proxy-Client-IP", "WL-Proxy-Client-IP")
        for (h in headers) {
            val v = request.getHeader(h)
            if (!v.isNullOrBlank() && !"unknown".equals(v, ignoreCase = true)) {
                // X-Forwarded-For 는 "client, proxy1, proxy2" 형태이므로 첫 값을 취한다.
                return v.split(",").first().trim()
            }
        }
        return request.remoteAddr ?: "-"
    }

    /**
     * 쿼리 파라미터를 민감 항목 마스킹 후 문자열로 만든다.
     */
    private fun maskedParams(request: HttpServletRequest): String {
        val map = request.parameterMap
        if (map.isEmpty()) return "-"
        return map.entries.joinToString(", ", "{", "}") { (key, values) ->
            val value = if (SENSITIVE_KEYS.any { it.equals(key, ignoreCase = true) }) {
                MASK
            } else {
                values.joinToString("|").take(200)
            }
            "$key=$value"
        }
    }
}
