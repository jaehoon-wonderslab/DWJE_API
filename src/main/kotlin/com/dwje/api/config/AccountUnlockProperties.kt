package com.dwje.api.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 계정 잠금 해제 설정 (`app.security.account-unlock.*`) — 결정 R-02, 09 기획서 AUD-16
 *
 * @param emailEnabled 잠긴 계정을 본인이 이메일 인증으로 풀 수 있는지.
 *                     끄면 잠금 응답의 `mailEnabled` 가 false 이고 잠금 해제 경로 3건(`/auth/unlock/...`)은 503 E-AUTH-007 이다.
 *                     SMTP 접속 정보를 받기 전(dev·prod)에는 끄고, 관리자 잠금 해제(계정 관리 화면)만 쓴다.
 */
@ConfigurationProperties(prefix = "app.security.account-unlock")
data class AccountUnlockProperties(
    val emailEnabled: Boolean = false
)
