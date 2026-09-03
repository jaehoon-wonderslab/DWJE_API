package com.dwje.api.config

import com.dwje.api.common.validation.ResolvedPlaceholder
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * JWT 토큰 발급 설정 (`app.jwt.*`)
 *
 * ## 설정 우선순위
 * `application-{profile}.yml` 값 > Kotlin 생성자 기본값.
 * yml 에 키가 있으면 항상 yml 이 이기고, 키가 아예 없을 때만 생성자 기본값이 쓰인다.
 *
 * ## 검증 방식
 * Spring Boot 표준인 `@Validated` + Jakarta Bean Validation 제약을 쓴다.
 * 생성자에서 `require()` 로 던지는 방식과 달리
 * - 위반을 **한 번에 모두** 모아 보고한다 (하나 고치고 재기동을 반복하지 않아도 된다)
 * - Spring Boot 의 실패 분석기가 **읽기 쉬운 리포트**로 렌더링한다
 * - 스택트레이스 대신 "어떤 속성이 왜 잘못됐는지"가 드러난다
 *
 * ## secret 에 실제 기본값을 두지 않는 이유
 * 서명 키에 쓸 만한 기본값을 두면 운영에서 주입이 누락돼도 **소스에 적힌 키로 조용히 기동**한다.
 * 기본값을 빈 문자열로 두고 [NotBlank] 로 막으면, 누락·미해석·길이 부족이 모두
 * 같은 형태의 검증 실패로 보고된다.
 *
 * @param secret                  HMAC-SHA256 서명 키 (필수. 운영·개발은 환경변수로 주입)
 * @param issuer                  토큰 발급자
 * @param accessTokenValiditySec  Access Token 유효시간(초)
 * @param refreshTokenValiditySec Refresh Token 유효시간(초)
 */
@Validated
@ConfigurationProperties(prefix = "app.jwt")
data class JwtProperties(

    @field:NotBlank(message = "JWT 서명 키(app.jwt.secret)가 설정되지 않았습니다. 환경변수 주입 여부를 확인하세요.")
    @field:ResolvedPlaceholder
    @field:Size(
        min = 32,
        message = "JWT 서명 키는 UTF-8 기준 32바이트(256비트) 이상이어야 합니다. (RFC 7518 §3.2)"
    )
    val secret: String = "",

    @field:NotBlank(message = "토큰 발급자(app.jwt.issuer)는 비워 둘 수 없습니다.")
    val issuer: String = "dwje-api",

    @field:Min(value = 60, message = "Access Token 유효시간은 60초 이상이어야 합니다.")
    val accessTokenValiditySec: Long = 3600,

    @field:Min(value = 300, message = "Refresh Token 유효시간은 300초 이상이어야 합니다.")
    val refreshTokenValiditySec: Long = 604800
)
