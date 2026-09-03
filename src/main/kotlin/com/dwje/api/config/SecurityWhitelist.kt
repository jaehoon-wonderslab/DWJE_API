package com.dwje.api.config

import org.springframework.util.AntPathMatcher

/**
 * 보안 체크 제외 API 화이트리스트
 *
 * 로그인·토큰 갱신·헬스체크·API 문서 등 인증이 필요 없는 경로를 명시적으로 관리한다.
 * 이 목록에 없는 모든 경로는 JwtAuthFilter 에서 토큰 검증을 거친다.
 */
object SecurityWhitelist {

    private val matcher = AntPathMatcher()

    /** 인증 없이 호출 가능한 경로 목록 */
    val PUBLIC_PATHS: List<String> = listOf(
        "/api/v1/auth/login",
        "/api/v1/auth/refresh",
        // 회원가입은 로그인 전에 호출하므로 인증을 요구하지 않는다.
        // 가입된 계정은 PENDING 상태라 관리자 승인 전까지 로그인할 수 없다.
        "/api/v1/auth/signup",
        "/api/v1/auth/signup/check-emp-no",
        "/api/v1/auth/signup/depts",
        // 이메일 인증·비밀번호 찾기는 로그인 전에 쓰므로 인증을 요구하지 않는다.
        // 대신 발송 대기·일일 상한·시도 횟수 제한으로 남용을 막는다.
        "/api/v1/auth/email/send-code",
        "/api/v1/auth/email/verify-code",
        "/api/v1/auth/password/forgot",
        "/api/v1/auth/password/reset",
        "/api/v1/health",
        "/actuator/health",
        "/swagger-ui/**",
        "/swagger-ui.html",
        "/v3/api-docs/**",
        "/favicon.ico",
        "/error"
    )

    /**
     * 요청 경로가 화이트리스트에 해당하는지 판정한다.
     *
     * @param path 컨텍스트를 제외한 요청 URI
     */
    fun isWhitelisted(path: String): Boolean =
        PUBLIC_PATHS.any { pattern ->
            if (pattern.contains("*")) matcher.match(pattern, path) else pattern == path
        }
}
