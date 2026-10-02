package com.dwje.api.config

/**
 * local 프로파일의 실제 메일 발송 전환 (R-17) — `AX_MAIL_SENDER_MODE=SMTP` 하나로 바꾼다.
 *
 * local 은 자동 시험을 위해 LOG 모드·고정 코드(000000)·발송 상한 해제가 기본이다. 그런데 고정 코드와 상한 해제는
 * SMTP 모드에서 기동을 거부한다([EmailVerificationProperties]) — 실제 메일이 나가는 환경에 시험 설정이 섞이지 않게 하려는 규칙이다.
 * 그래서 SMTP 로 띄우면 시스템 프로퍼티(yml 보다 우선)로 두 시험 설정을 끄고, 메일 서버 설정을 `LOCAL_MAIL_*` 자리표시자로 채운다.
 *
 * 비밀값은 이 코드가 읽거나 출력하지 않는다 — `${'$'}{LOCAL_MAIL_PASSWORD}` 같은 자리표시자만 넣고 Spring 이 환경 파일 값으로 푼다.
 */
object LocalMailMode {

    const val SWITCH = "AX_MAIL_SENDER_MODE"

    /** 바꾼 설정 키 — 기동 로그용(값은 찍지 않는다) */
    fun apply(profile: String, env: (String) -> String? = { System.getProperty(it) ?: System.getenv(it) }): List<String> {
        if (profile != "local" || !env(SWITCH).equals("SMTP", ignoreCase = true)) return emptyList()
        val overrides = linkedMapOf(
            "app.security.email-verification.fixed-code" to "",
            "app.security.email-verification.daily-send-limit" to "10",
            "app.security.email-verification.resend-wait-sec" to "60",
            "spring.mail.host" to "\${LOCAL_MAIL_HOST}",
            "spring.mail.port" to "\${LOCAL_MAIL_PORT:465}",
            "spring.mail.username" to "\${LOCAL_MAIL_USERNAME}",
            "spring.mail.password" to "\${LOCAL_MAIL_PASSWORD}",
            "spring.mail.default-encoding" to "UTF-8",
            "spring.mail.properties.mail.smtp.auth" to "true",
            "spring.mail.properties.mail.smtp.ssl.enable" to "true",
            "spring.mail.properties.mail.smtp.starttls.enable" to "false",
            "spring.mail.properties.mail.smtp.connectiontimeout" to "10000",
            "spring.mail.properties.mail.smtp.timeout" to "10000",
            "spring.mail.properties.mail.smtp.writetimeout" to "10000"
        )
        overrides.forEach { (k, v) -> if (System.getProperty(k) == null) System.setProperty(k, v) }
        return overrides.keys.toList()
    }
}
