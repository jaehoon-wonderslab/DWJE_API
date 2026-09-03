package com.dwje.api.config

import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import org.springframework.web.filter.CorsFilter

/**
 * CORS 정책 — 서블릿 필터 단계에서 적용한다.
 *
 * WebMvcConfigurer.addCorsMappings 는 DispatcherServlet 안에서 동작하므로
 * 그보다 앞선 인증·보안 필터가 401/403 으로 응답을 끝내면 CORS 헤더가 붙지 않는다.
 * 브라우저는 헤더 없는 응답을 차단해 프론트에는 상태 코드 없는 네트워크 오류로만 전달되고,
 * 화면이 토큰 만료를 감지하지 못한다.
 *
 * 그래서 CORS 를 필터로 올려 [com.dwje.api.middleware.SqlInjectionCheckFilter](20) ·
 * [com.dwje.api.middleware.JwtAuthFilter](30) 보다 먼저(10) 실행시킨다.
 * 이러면 인증 실패 응답에도 헤더가 실린다.
 */
@Configuration
class CorsConfig {

    companion object {
        /** SqlInjectionCheckFilter(20) · JwtAuthFilter(30) 보다 먼저 실행돼야 한다. */
        private const val CORS_FILTER_ORDER = 10
    }

    /**
     * 프론트 개발 서버 및 사내 배포 도메인 허용 정책.
     *
     * Bean 으로 올리지 않는다. `CorsConfigurationSource` 타입 Bean 은 MVC 가
     * `mvcHandlerMappingIntrospector` 로 이미 하나 등록해 두어 주입 시 후보가 둘이 된다.
     */
    private fun corsConfigurationSource(): CorsConfigurationSource {
        val config = CorsConfiguration().apply {
            allowedOriginPatterns = listOf("http://localhost:*", "http://127.0.0.1:*", "https://*.dwje.internal")
            allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            allowedHeaders = listOf("*")
            exposedHeaders = listOf("Content-Disposition")
            allowCredentials = true
            maxAge = 3600L
        }

        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/api/**", config)
        }
    }

    /**
     * 인증 필터보다 앞서는 CORS 필터.
     *
     * 프리플라이트(OPTIONS)는 이 필터가 직접 200 으로 응답하고 체인을 끊는다.
     */
    @Bean
    fun corsFilterRegistration(): FilterRegistrationBean<CorsFilter> =
        FilterRegistrationBean(CorsFilter(corsConfigurationSource())).apply {
            order = CORS_FILTER_ORDER
        }
}
