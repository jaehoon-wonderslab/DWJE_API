package com.dwje.api.middleware

import com.dwje.api.common.exception.BusinessException
import com.dwje.api.common.response.ApiResponse
import com.dwje.api.common.response.ErrorCode
import com.dwje.api.common.security.JwtTokenProvider
import com.dwje.api.common.security.UserContext
import com.dwje.api.config.SecurityWhitelist
import com.dwje.api.service.AuthorizationService
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * JWT 인증/인가 필터 (미들웨어 3단계)
 *
 * 처리 순서
 * 1. 화이트리스트 경로면 인증 없이 통과
 * 2. `Authorization: Bearer <token>` 헤더에서 토큰 추출
 * 3. 서명·만료 검증 후 사번 확보
 * 4. 부서 기준 메뉴/데이터 권한을 조회해 [UserContext] 에 바인딩
 */
@Component
@Order(30)
class JwtAuthFilter(
    private val tokenProvider: JwtTokenProvider,
    private val authorizationService: AuthorizationService,
    private val objectMapper: ObjectMapper
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val BEARER_PREFIX = "Bearer "
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        // 1. 화이트리스트 검사 — 로그인/토큰 갱신/헬스체크/문서
        if (SecurityWhitelist.isWhitelisted(request.requestURI) || request.method == "OPTIONS") {
            filterChain.doFilter(request, response)
            return
        }

        try {
            try {
                // 2. 토큰 추출
                val token = extractToken(request)
                    ?: return reject(response, ErrorCode.AUTH_UNAUTHENTICATED, "인증 토큰이 없습니다.")

                // 3. 서명·만료 검증
                val claims = tokenProvider.parse(token)
                if (claims[JwtTokenProvider.CLAIM_TOKEN_TYPE] != JwtTokenProvider.TYPE_ACCESS) {
                    return reject(response, ErrorCode.AUTH_UNAUTHENTICATED, "접근 토큰이 아닙니다.")
                }

                // 4. 최신 권한을 DB 에서 조회해 컨텍스트에 바인딩한다.
                //    (토큰 발급 이후 관리자가 권한을 변경한 경우를 즉시 반영하기 위함)
                val impersonated = claims[JwtTokenProvider.CLAIM_IMPERSONATED] as? Boolean ?: false
                val principal = authorizationService.loadPrincipal(claims.subject, impersonated)
                UserContext.set(principal)
            } catch (e: BusinessException) {
                return reject(response, e.errorCode, e.message)
            } catch (e: Exception) {
                log.error("인증 필터 처리 중 오류 [URI: {}]", request.requestURI, e)
                return reject(response, ErrorCode.SERVER_ERROR, ErrorCode.SERVER_ERROR.defaultMessage)
            }

            // 컨트롤러 예외는 MVC 예외 처리기에 맡긴다. 인증 오류로 다시 포장하지 않는다.
            filterChain.doFilter(request, response)
        } finally {
            // 스레드 풀 재사용 시 이전 요청의 인증 정보가 남지 않도록 반드시 해제한다.
            UserContext.clear()
        }
    }

    /** Authorization 헤더에서 Bearer 토큰을 꺼낸다. */
    private fun extractToken(request: HttpServletRequest): String? {
        val header = request.getHeader(HttpHeaders.AUTHORIZATION) ?: return null
        if (!header.startsWith(BEARER_PREFIX, ignoreCase = true)) return null
        return header.substring(BEARER_PREFIX.length).trim().takeIf { it.isNotBlank() }
    }

    /** 인증 실패 시 표준 응답으로 즉시 종료한다. */
    private fun reject(response: HttpServletResponse, errorCode: ErrorCode, message: String) {
        response.status = errorCode.status.value()
        response.contentType = "${MediaType.APPLICATION_JSON_VALUE};charset=UTF-8"
        response.writer.write(objectMapper.writeValueAsString(ApiResponse.error(errorCode.code, message)))
    }
}
