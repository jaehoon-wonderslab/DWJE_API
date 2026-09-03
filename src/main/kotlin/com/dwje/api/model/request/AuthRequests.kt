package com.dwje.api.model.request

import jakarta.validation.constraints.NotBlank

/**
 * 로그인 요청 — POST /api/v1/auth/login
 *
 * @param loginId  사번 (= 계정 ID)
 * @param password 비밀번호
 */
data class LoginRequest(
    @field:NotBlank(message = "사번을 입력해 주세요.")
    val loginId: String,

    @field:NotBlank(message = "비밀번호를 입력해 주세요.")
    val password: String
)

/**
 * 토큰 갱신 요청 — POST /api/v1/auth/refresh
 *
 * @param refreshToken 로그인 시 발급받은 갱신 토큰
 */
data class RefreshTokenRequest(
    @field:NotBlank(message = "갱신 토큰이 필요합니다.")
    val refreshToken: String
)

/**
 * 계정 전환 요청 — POST /api/v1/auth/switch (통합관리자 전용)
 *
 * @param empNo 전환 대상 사번
 */
data class SwitchAccountRequest(
    @field:NotBlank(message = "전환할 사번을 입력해 주세요.")
    val empNo: String
)
