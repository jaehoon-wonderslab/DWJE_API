package com.dwje.api.config

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * 이메일 인증 설정 (`app.security.email-verification.*`)
 *
 * @param codeLength      인증 코드 자릿수
 * @param expireMinutes   코드 유효 시간(분)
 * @param maxAttempts     코드 검증 시도 상한 (초과 시 요청 폐기)
 * @param resendWaitSec   재발송 대기 시간(초)
 * @param dailySendLimit  이메일당 일일 발송 상한. 0 이면 제한 없음(local 전용)
 * @param tokenExpireMinutes 검증 성공 후 발급되는 1회용 토큰 유효 시간(분)
 * @param senderMode      발송 방식 — LOG(로컬: 로그 출력) | SMTP(실제 발송)
 * @param fromAddress     발신자 주소
 * @param fixedCode       테스트용 고정 인증 코드 (local 전용). 지정하면 난수 대신 이 값을 발급한다
 */
@Validated
@ConfigurationProperties(prefix = "app.security.email-verification")
data class EmailVerificationProperties(

    @field:Min(value = 4, message = "인증 코드는 4자리 이상이어야 합니다.")
    @field:Max(value = 10, message = "인증 코드는 10자리 이하로 설정하세요.")
    val codeLength: Int = 6,

    @field:Min(value = 1, message = "코드 유효 시간은 1분 이상이어야 합니다.")
    @field:Max(value = 60, message = "코드 유효 시간이 길면 탈취 위험이 커집니다. 60분 이하로 설정하세요.")
    val expireMinutes: Int = 5,

    @field:Min(value = 3, message = "시도 상한은 3회 이상이어야 합니다.")
    @field:Max(value = 10, message = "시도 상한은 10회 이하로 설정하세요.")
    val maxAttempts: Int = 5,

    @field:Min(value = 10, message = "재발송 대기는 10초 이상이어야 합니다.")
    val resendWaitSec: Int = 60,

    /**
     * 이메일당 일일 발송 상한. `0` 은 제한 없음이다.
     *
     * 자동 테스트가 같은 시드 계정으로 하루에 수십 번 발송을 시도하는데,
     * 비밀번호 찾기는 계정 열거를 막기 위해 발송 제한을 삼키고 항상 200 을 준다.
     * 그래서 상한에 닿아도 테스트가 알 수 없고, 검사가 조용히 통과만 하게 된다.
     * local 에서 제한을 끄는 이유가 이것이다 — 재발송 대기(resendWaitSec)는 그대로 남아
     * 발송 제한 자체는 회원가입 경로에서 계속 검증된다.
     *
     * 실제 메일을 보내는 SMTP 모드에서는 0 을 허용하지 않는다(기동 거부).
     */
    @field:Min(value = 0, message = "일일 발송 상한은 0 이상이어야 합니다. (0 = 제한 없음)")
    @field:Max(value = 100, message = "일일 발송 상한은 100회 이하로 설정하세요.")
    val dailySendLimit: Int = 10,

    @field:Min(value = 1, message = "토큰 유효 시간은 1분 이상이어야 합니다.")
    @field:Max(value = 60, message = "토큰 유효 시간은 60분 이하로 설정하세요.")
    val tokenExpireMinutes: Int = 10,

    @field:NotBlank(message = "메일 발송 방식을 지정해야 합니다.")
    val senderMode: String = "LOG",

    @field:NotBlank(message = "발신자 주소를 지정해야 합니다.")
    val fromAddress: String = "no-reply@dwje.co.kr",

    /**
     * 테스트용 고정 인증 코드 — `local` 프로파일 전용.
     *
     * 값이 있으면 난수 대신 이 코드를 발급한다.
     * 자동 테스트가 회원가입·가입승인 흐름을 끝까지 확인할 수 있게 하려는 설정이다.
     * 실제 메일을 보내는 SMTP 모드에서는 기동 자체를 거부해 운영 유입을 막는다.
     */
    val fixedCode: String? = null
) {
    init {
        val fixed = fixedCode
        if (!fixed.isNullOrBlank()) {
            require(!senderMode.equals("SMTP", ignoreCase = true)) {
                "고정 인증 코드는 실제 메일을 발송하는 SMTP 모드에서 쓸 수 없습니다. " +
                    "app.security.email-verification.fixed-code 는 local 프로파일에서만 지정하세요."
            }
            require(fixed.length == codeLength) {
                "고정 인증 코드 자릿수가 codeLength($codeLength) 와 다릅니다. [fixedCode=$fixed]"
            }
            require(fixed.all { it.isDigit() }) {
                "고정 인증 코드는 숫자만 허용합니다. [fixedCode=$fixed]"
            }
        }

        // 제한 없음은 메일이 실제로 나가지 않는 환경에서만 의미가 있다.
        require(dailySendLimit > 0 || !senderMode.equals("SMTP", ignoreCase = true)) {
            "일일 발송 상한 0(제한 없음)은 실제 메일을 발송하는 SMTP 모드에서 쓸 수 없습니다. " +
                "app.security.email-verification.daily-send-limit 는 local 에서만 0 으로 두세요."
        }
    }
}
