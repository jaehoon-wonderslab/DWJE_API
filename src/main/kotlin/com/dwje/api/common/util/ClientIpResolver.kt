package com.dwje.api.common.util

import com.dwje.api.config.AppProperties
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/**
 * 접속 IP 판정 공통 함수 — 09 기획서 AUD-03 (공통 CM-06)
 *
 * 예전에는 다섯 곳이 `X-Forwarded-For` 의 **첫 값**을 그대로 믿었다. 첫 값은 브라우저가 마음대로 적을 수 있어
 * 감사 기록·로그인 이력·다운로드 이력의 IP 를 위조할 수 있었다. 판정 규칙을 이 한 곳에 둔다.
 *
 * 규칙
 * 1. 요청을 넘긴 주소(`remoteAddr`)가 신뢰 프록시(`app.trusted-proxies`, 기본 루프백)가 아니면 → `remoteAddr`
 * 2. 신뢰 프록시이고 `X-Real-IP` 가 있으면 → `X-Real-IP` (같은 서버 nginx 가 채운 값)
 * 3. 신뢰 프록시이고 `X-Forwarded-For` 가 있으면 → **오른쪽부터** 신뢰 프록시를 걷어 낸 첫 값
 * 4. 그 밖에는 `remoteAddr`
 *
 * 헤더 원문은 어디에도 기록하지 않는다(길이·위조 문자열).
 */
@Component
class ClientIpResolver(
    private val appProperties: AppProperties = AppProperties()
) {

    private val trusted: Set<String> get() = appProperties.trustedProxies.map { it.trim() }.toSet()

    /** 요청의 실제 클라이언트 IP. 판정할 수 없으면 null */
    fun resolve(request: HttpServletRequest): String? {
        val remote = request.remoteAddr?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (remote !in trusted) return remote

        request.getHeader("X-Real-IP")?.trim()?.takeIf { it.isNotEmpty() && isIpLiteral(it) }?.let { return it }

        val forwarded = request.getHeader("X-Forwarded-For")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        return forwarded.asReversed().firstOrNull { it !in trusted && isIpLiteral(it) } ?: remote
    }

    /** 지금 처리 중인 요청의 IP — 요청 컨텍스트가 없으면(배치·스케줄러) null */
    fun currentRequestIp(): String? {
        val attrs = RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes ?: return null
        return resolve(attrs.request)
    }

    /**
     * IPv4·IPv6 표기만 받는다. DB 컬럼이 `inet` 이라 형식이 틀린 값은 INSERT 를 깨뜨린다.
     * (DNS 조회를 하지 않도록 문자 구성만 본다)
     */
    private fun isIpLiteral(value: String): Boolean =
        value.length <= 45 && value.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == '.' || it == ':' }
}
