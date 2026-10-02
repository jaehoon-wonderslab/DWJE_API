package com.dwje.api

import com.dwje.api.common.util.ClientIpResolver
import com.dwje.api.config.AppProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

/**
 * 접속 IP 판정 — 09 기획서 AUD-03
 *
 * 브라우저가 직접 적은 X-Forwarded-For 는 믿지 않고, 신뢰 프록시가 넘긴 요청만 프록시 헤더를 본다.
 */
class ClientIpResolverTest {

    private val resolver = ClientIpResolver(AppProperties())

    private fun request(remote: String, vararg headers: Pair<String, String>) =
        MockHttpServletRequest().apply {
            remoteAddr = remote
            headers.forEach { (k, v) -> addHeader(k, v) }
        }

    @Test
    @DisplayName("API 에 직접 붙은 요청의 X-Forwarded-For 는 무시하고 접속 주소를 쓴다")
    fun directRequestIgnoresForwardedFor() {
        assertEquals("10.1.2.3", resolver.resolve(request("10.1.2.3", "X-Forwarded-For" to "1.2.3.4")))
        assertEquals("10.1.2.3", resolver.resolve(request("10.1.2.3", "X-Real-IP" to "1.2.3.4")))
    }

    @Test
    @DisplayName("루프백 프록시가 채운 X-Real-IP 를 쓴다")
    fun loopbackProxyRealIp() {
        assertEquals("10.9.8.7", resolver.resolve(request("127.0.0.1", "X-Real-IP" to "10.9.8.7")))
        assertEquals("10.9.8.7", resolver.resolve(request("0:0:0:0:0:0:0:1", "X-Real-IP" to "10.9.8.7")))
    }

    @Test
    @DisplayName("X-Forwarded-For 는 오른쪽부터 신뢰 프록시를 걷어 낸 첫 값 — 브라우저가 앞에 적은 값은 쓰지 않는다")
    fun forwardedForFromRight() {
        val r = request("127.0.0.1", "X-Forwarded-For" to "6.6.6.6, 10.0.0.5, 127.0.0.1")
        assertEquals("10.0.0.5", resolver.resolve(r))
    }

    @Test
    @DisplayName("IP 형식이 아닌 헤더 값은 버린다 (inet 컬럼 INSERT 를 깨뜨리지 않게)")
    fun garbageHeaderIsIgnored() {
        assertEquals("127.0.0.1", resolver.resolve(request("127.0.0.1", "X-Real-IP" to "evil<script>")))
    }

    @Test
    @DisplayName("신뢰 프록시 목록 설정을 따른다")
    fun configuredTrustedProxies() {
        val custom = ClientIpResolver(AppProperties(trustedProxies = listOf("10.0.0.1")))
        assertEquals("172.16.0.9", custom.resolve(request("10.0.0.1", "X-Real-IP" to "172.16.0.9")))
        assertEquals("127.0.0.1", custom.resolve(request("127.0.0.1", "X-Real-IP" to "172.16.0.9")))
    }
}
