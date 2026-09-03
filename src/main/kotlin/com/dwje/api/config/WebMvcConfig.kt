package com.dwje.api.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Web MVC 및 직렬화 설정
 *
 * - 날짜/시간은 ISO 문자열로 직렬화하고, null 필드는 응답에서 제외한다.
 * - CORS 는 인증 필터의 401 응답에도 헤더가 실려야 하므로 [CorsConfig] 의 필터에서 처리한다.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties::class, AppProperties::class, PasswordProperties::class, EmailVerificationProperties::class)
class WebMvcConfig : WebMvcConfigurer {

    /**
     * Kotlin data class 및 java.time 직렬화를 지원하는 ObjectMapper
     */
    @Bean
    fun objectMapper(): ObjectMapper =
        jacksonObjectMapper().apply {
            registerModule(JavaTimeModule())
            // 타임스탬프 숫자 대신 ISO-8601 문자열로 출력한다.
            disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            // 선언되지 않은 요청 필드는 400 으로 막는다.
            //
            // 예전에는 무시했다. 그러면 키 이름이 틀린 요청이 전 항목 null 로 역직렬화되어
            // 200 이 나가는데 아무 값도 바뀌지 않는다. 화면은 성공으로 읽고 사용자는
            // 왜 반영이 안 되는지 알 수 없다. 2026-09-01 하루에 세 번 이 유형을 밟았다.
            //   PATCH /system/users/{empNo}/state  본문 없음        → 200, 실제로는 ACTIVE 로 바뀜
            //   PUT   /metrics/standards/{stdId}   {field, value}  → 200, 아무 값도 안 바뀜
            //   POST  /alert-conditions            표시명 전송       → 어느 값이 문제인지 알 수 없음
            //
            // 조용히 버리는 것보다 시끄럽게 막는 쪽이 낫다.
            // 영향 범위는 docs/REQUEST_BODY_CONTRACT.md 에 엔드포인트별 허용 키로 정리했다.
            // Map 본문(7개)은 이 설정과 무관하므로 그쪽은 여전히 조용히 무시한다.
            enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        }
}
