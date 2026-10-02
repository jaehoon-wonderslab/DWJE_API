package com.dwje.api

import com.dwje.api.common.mail.MailSendStats
import com.dwje.api.common.mail.VerificationMailSender
import com.dwje.api.common.security.PasswordEncoderService
import com.dwje.api.config.EmailVerificationProperties
import com.dwje.api.config.LocalMailMode
import com.dwje.api.config.PasswordProperties
import com.dwje.api.repository.EmailVerificationRepository
import com.dwje.api.service.EmailVerificationService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.io.File

/** R-17 SMTP 설정 — 비밀값은 다루지 않는다(자리표시자·키 이름만 본다) */
class MailSmtpConfigTest {

    private val touched = listOf(
        "app.security.email-verification.fixed-code", "app.security.email-verification.daily-send-limit",
        "app.security.email-verification.resend-wait-sec", "spring.mail.host", "spring.mail.port", "spring.mail.username",
        "spring.mail.password", "spring.mail.default-encoding", "spring.mail.properties.mail.smtp.auth",
        "spring.mail.properties.mail.smtp.ssl.enable", "spring.mail.properties.mail.smtp.starttls.enable",
        "spring.mail.properties.mail.smtp.connectiontimeout", "spring.mail.properties.mail.smtp.timeout",
        "spring.mail.properties.mail.smtp.writetimeout"
    )

    @AfterEach
    fun clear() = touched.forEach { System.clearProperty(it) }

    @Test
    @DisplayName("local + AX_MAIL_SENDER_MODE=SMTP 이면 고정 코드·상한 해제를 끄고 메일 설정을 LOCAL_MAIL_* 자리표시자로, 그 밖에는 아무것도 바꾸지 않는다")
    fun localSwitch() {
        assertTrue(LocalMailMode.apply("local") { if (it == LocalMailMode.SWITCH) "LOG" else null }.isEmpty())
        assertTrue(LocalMailMode.apply("prod") { if (it == LocalMailMode.SWITCH) "SMTP" else null }.isEmpty())
        val keys = LocalMailMode.apply("local") { if (it == LocalMailMode.SWITCH) "SMTP" else null }
        assertEquals(touched.toSet(), keys.toSet())
        assertEquals("", System.getProperty("app.security.email-verification.fixed-code"))
        assertEquals("\${LOCAL_MAIL_PASSWORD}", System.getProperty("spring.mail.password"), "비밀값이 아니라 자리표시자")
        assertEquals("true", System.getProperty("spring.mail.properties.mail.smtp.ssl.enable"))
        assertEquals("false", System.getProperty("spring.mail.properties.mail.smtp.starttls.enable"))
        // 고정 코드를 끈 SMTP 설정은 기동 규칙을 통과한다
        EmailVerificationProperties(senderMode = "SMTP", fixedCode = "", dailySendLimit = 10)
        assertThrows(IllegalArgumentException::class.java) { EmailVerificationProperties(senderMode = "SMTP", fixedCode = "000000") }
    }

    @Test
    @DisplayName("dev·prod 메일 설정 — 465 SSL 직접 연결·STARTTLS 끔·제한 10초·발신 *_MAIL_FROM·이메일 잠금 해제 켬, 비밀번호는 자리표시자뿐")
    fun profileYml() {
        listOf("dev" to "DEV", "prod" to "PROD").forEach { (p, env) ->
            val yml = File("src/main/resources/application-$p.yml").readText()
            assertTrue(yml.contains("port: \${${env}_MAIL_PORT:465}"), p)
            assertTrue(yml.contains("mail.smtp.ssl.enable: true"), p)
            assertTrue(yml.contains("mail.smtp.starttls.enable: false"), p)
            listOf("connectiontimeout", "timeout", "writetimeout").forEach { assertTrue(yml.contains("mail.smtp.$it: 10000"), "$p $it") }
            assertTrue(yml.contains("from-address: \"\${${env}_MAIL_FROM:\${${env}_MAIL_USERNAME}}\""), p)
            assertTrue(Regex("account-unlock:\\s*\\n(\\s*#.*\\n)*\\s*email-enabled: true").containsMatchIn(yml), p)
            assertTrue(yml.contains("password: \"\${${env}_MAIL_PASSWORD}\""), p)
        }
        assertFalse(File("src/main/resources/application.yml").readText().contains("no-reply@dwje.co.kr"))
        assertEquals("덕우전자 AX", EmailVerificationProperties().fromName)
    }

    @Test
    @DisplayName("발송 실패는 요청 행 FAIL 과 함께 기동 뒤 실패 수(mailFailSinceBoot)에 들어간다")
    fun failureCounted() {
        val repo = mock(EmailVerificationRepository::class.java)
        // 재발송 대기에 걸리지 않게 — 「보낸 적 없음」
        org.mockito.Mockito.doReturn(null).`when`(repo).findSecondsSinceLastSend(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())
        val failing = object : VerificationMailSender {
            override fun sendVerificationCode(to: String, purpose: String, code: String, expireMinutes: Int) = throw IllegalStateException("smtp down")
        }
        val stats = MailSendStats()
        val service = EmailVerificationService(repo, failing, PasswordEncoderService(PasswordProperties()), EmailVerificationProperties(), mailSendStats = stats)
        val err = runCatching { service.sendCode("dw_ai@derkwoo.com", "SIGNUP") }.exceptionOrNull()
        assertEquals("인증 메일 발송에 실패했습니다. 잠시 후 다시 시도해 주세요.", err?.message, err?.toString())
        assertEquals(1, stats.failSinceBoot)
    }
}
