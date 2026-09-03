package com.dwje.api.config

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * 비밀번호 해시·정책 설정 (`app.security.password.*`)
 *
 * ## 알고리즘
 * | 값 | 설명 |
 * |---|---|
 * | `PBKDF2_SHA512` | PBKDF2-HMAC-SHA512 (기본). 솔트 + 반복 확장. RFC 8018 표준 |
 * | `SHA512` | 솔트 1회 SHA-512. 기존 시스템 해시와 맞춰야 할 때만 사용 |
 *
 * 둘 다 SHA-512 계열이며, 저장 문자열에 알고리즘 접두사가 포함되어 서로 섞여 있어도 검증된다.
 *
 * @param algorithm  해시 알고리즘 — PBKDF2_SHA512 | SHA512
 * @param iterations PBKDF2 반복 횟수 (OWASP 2023 권고 210,000)
 * @param saltBytes  솔트 바이트 수
 * @param minLength  비밀번호 최소 길이
 * @param requireMixedTypes 영문·숫자·특수문자 중 2종 이상 조합 요구 여부
 */
@Validated
@ConfigurationProperties(prefix = "app.security.password")
data class PasswordProperties(

    @field:NotBlank(message = "비밀번호 해시 알고리즘을 지정해야 합니다.")
    val algorithm: String = "PBKDF2_SHA512",

    @field:Min(value = 10_000, message = "PBKDF2 반복 횟수는 10,000 이상이어야 합니다.")
    @field:Max(value = 2_000_000, message = "PBKDF2 반복 횟수가 과도하면 로그인 응답이 지연됩니다.")
    val iterations: Int = 210_000,

    @field:Min(value = 8, message = "솔트는 8바이트 이상이어야 합니다.")
    @field:Max(value = 64, message = "솔트는 64바이트 이하로 설정하세요.")
    val saltBytes: Int = 16,

    @field:Min(value = 8, message = "비밀번호 최소 길이는 8자 이상이어야 합니다.")
    @field:Max(value = 64, message = "비밀번호 최소 길이는 64자 이하로 설정하세요.")
    val minLength: Int = 8,

    val requireMixedTypes: Boolean = true
)
