package com.dwje.api.common.mail

import com.dwje.api.config.EmailVerificationProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.stereotype.Component

/**
 * 인증 메일 발송 인터페이스
 *
 * 발송 방식은 `app.security.email-verification.sender-mode` 로 고른다.
 * 로컬 개발은 SMTP 서버 없이도 흐름을 확인할 수 있어야 하므로 로그 출력 구현을 기본으로 둔다.
 */
interface VerificationMailSender {

    /**
     * 인증 코드 메일을 발송한다.
     *
     * @param to      수신 주소
     * @param purpose 인증 목적 (제목·본문 문구 결정)
     * @param code    인증 코드 평문
     * @param expireMinutes 유효 시간(분)
     * @throws Exception 발송 실패 시. 호출 측이 발송 결과를 기록한다.
     */
    fun sendVerificationCode(to: String, purpose: String, code: String, expireMinutes: Int)

    /** 목적별 메일 제목 */
    fun subjectOf(purpose: String): String = when (purpose) {
        "SIGNUP" -> "[덕우전자 AX] 회원가입 인증 코드"
        "PASSWORD_RESET" -> "[덕우전자 AX] 비밀번호 찾기 인증 코드"
        else -> "[덕우전자 AX] 이메일 인증 코드"
    }

    /** 목적별 메일 본문 */
    fun bodyOf(purpose: String, code: String, expireMinutes: Int): String {
        val action = if (purpose == "PASSWORD_RESET") "비밀번호 재설정" else "회원가입"
        return """
            덕우전자 AX 시스템 $action 인증 코드입니다.

                인증 코드 : $code

            이 코드는 ${expireMinutes}분간 유효합니다.
            본인이 요청하지 않았다면 이 메일을 무시하시고 전산팀에 알려 주세요.
        """.trimIndent()
    }
}

/**
 * 로컬·개발용 메일 발송기
 *
 * SMTP 없이 인증 코드를 **로그로 출력**한다. 실제 메일은 나가지 않으므로
 * 운영에서 이 구현이 선택되지 않도록 프로파일 설정을 반드시 확인해야 한다.
 */
@Component
@ConditionalOnProperty(
    prefix = "app.security.email-verification",
    name = ["sender-mode"],
    havingValue = "LOG",
    matchIfMissing = true
)
class LoggingVerificationMailSender : VerificationMailSender {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun sendVerificationCode(to: String, purpose: String, code: String, expireMinutes: Int) {
        // 로컬에서 화면 흐름을 확인할 수 있도록 코드를 그대로 남긴다.
        log.info(
            "\n[메일 발송(로그 모드)] ───────────────────────────────\n" +
                "  받는 사람 : {}\n  제목      : {}\n  인증 코드 : {}  (유효 {}분)\n" +
                "─────────────────────────────────────────────────",
            to, subjectOf(purpose), code, expireMinutes
        )
    }
}

/**
 * SMTP 메일 발송기 (dev · prod)
 *
 * `spring.mail.*` 설정이 필요하며, 발송 실패는 호출 측에서 발송 결과로 기록한다.
 */
@Component
@ConditionalOnProperty(
    prefix = "app.security.email-verification",
    name = ["sender-mode"],
    havingValue = "SMTP"
)
class SmtpVerificationMailSender(
    private val mailSender: JavaMailSender,
    private val props: EmailVerificationProperties
) : VerificationMailSender {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun sendVerificationCode(to: String, purpose: String, code: String, expireMinutes: Int) {
        val message = SimpleMailMessage().apply {
            setFrom(props.fromAddress)
            setTo(to)
            setSubject(subjectOf(purpose))
            setText(bodyOf(purpose, code, expireMinutes))
        }

        mailSender.send(message)
        // 코드 자체는 절대 로그에 남기지 않는다.
        log.info("인증 메일 발송 완료: to={} purpose={}", to, purpose)
    }
}
