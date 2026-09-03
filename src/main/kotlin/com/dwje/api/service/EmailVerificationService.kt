package com.dwje.api.service

import com.dwje.api.common.exception.BusinessRuleException
import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.mail.VerificationMailSender
import com.dwje.api.common.security.PasswordEncoderService
import com.dwje.api.config.EmailVerificationProperties
import com.dwje.api.repository.EmailVerificationRepository
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.security.SecureRandom
import java.util.Base64

/**
 * 이메일 인증 서비스 (회원가입 · 비밀번호 찾기)
 *
 * ## 흐름
 * ```
 * 1) 코드 발송   sendCode(email, purpose)        → 메일로 6자리 코드
 * 2) 코드 검증   verifyCode(email, purpose, code) → 1회용 verificationToken
 * 3) 본 처리     signup / resetPassword(token, ...) → 토큰 소모
 * ```
 *
 * ## 보호 장치
 * - 코드는 해시로만 저장한다. 평문은 메일 발송 시점에만 존재한다.
 * - 만료(기본 5분) · 시도 상한(기본 5회) · 재발송 대기(60초) · 일일 발송 상한(10회)
 * - 새 코드를 보내면 이전 코드를 즉시 폐기해 동시 시도를 막는다.
 * - 토큰은 1회용이며 목적(SIGNUP/PASSWORD_RESET)이 다르면 통하지 않는다.
 */
@Service
class EmailVerificationService(
    private val repository: EmailVerificationRepository,
    private val mailSender: VerificationMailSender,
    private val passwordEncoderService: PasswordEncoderService,
    private val props: EmailVerificationProperties
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val random = SecureRandom()

    init {
        if (!props.fixedCode.isNullOrBlank()) {
            log.warn(
                "이메일 인증 코드가 고정값으로 설정되었습니다 — 테스트 전용입니다. [fixedCode={}]",
                props.fixedCode
            )
        }
        if (props.dailySendLimit <= 0) {
            log.warn("이메일 인증 일일 발송 상한이 해제되었습니다 — 테스트 전용입니다. (재발송 대기 {}초는 유지)", props.resendWaitSec)
        }
    }

    companion object {
        const val PURPOSE_SIGNUP = "SIGNUP"
        const val PURPOSE_PASSWORD_RESET = "PASSWORD_RESET"

        private val ALLOWED_PURPOSES = setOf(PURPOSE_SIGNUP, PURPOSE_PASSWORD_RESET)

        /** 이메일 형식 최소 검증 — 실제 도달 가능 여부는 코드 수신으로 확인한다. */
        private val EMAIL_PATTERN = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
    }

    /**
     * 인증 코드를 발송한다.
     *
     * @param email        수신 이메일
     * @param purpose      인증 목적
     * @param targetUserId 비밀번호 찾기 대상 계정 (가입은 null)
     * @return 만료 시각과 재발송 가능 시각
     */
    /**
     * 코드 발송을 **독립 트랜잭션**으로 시도한다.
     *
     * 호출자가 발송 실패를 삼키고 정상 응답을 돌려주려면 이 메서드를 써야 한다.
     * [sendCode] 를 그대로 부르면 호출자의 트랜잭션에 참여하므로, 발송 제한 예외를
     * 호출자가 잡아도 트랜잭션이 rollback-only 로 표시된 채 남아 커밋 시점에
     * UnexpectedRollbackException(500) 으로 터진다.
     *
     * 비밀번호 찾기가 이 경로를 쓴다 — 계정 존재 여부를 응답으로 드러내지 않기 위해
     * 발송 제한을 삼켜야 하기 때문이다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun sendCodeInNewTransaction(email: String, purpose: String, targetUserId: String? = null): Map<String, Any?> =
        sendCode(email, purpose, targetUserId)

    @Transactional
    fun sendCode(email: String, purpose: String, targetUserId: String? = null): Map<String, Any?> {
        val normalizedEmail = normalizeEmail(email)
        val normalizedPurpose = normalizePurpose(purpose)

        // 1. 재발송 대기 — 같은 주소로 연속 발송을 막는다. (경과 시간은 DB 시계 기준)
        repository.findSecondsSinceLastSend(normalizedEmail, normalizedPurpose)?.let { elapsed ->
            if (elapsed < props.resendWaitSec) {
                throw BusinessRuleException("인증 코드를 다시 보내려면 ${props.resendWaitSec - elapsed}초 후에 시도해 주세요.")
            }
        }

        // 2. 일일 상한 — 메일 폭탄에 악용되지 않도록 제한한다. 0 이면 제한 없음(local 전용).
        if (props.dailySendLimit > 0 &&
            repository.countTodaySends(normalizedEmail) >= props.dailySendLimit
        ) {
            throw BusinessRuleException("오늘 인증 코드 발송 횟수를 초과했습니다. 내일 다시 시도하거나 전산팀에 문의하세요.")
        }

        // 3. 이전 코드 폐기 후 새 코드 생성
        repository.expirePrevious(normalizedEmail, normalizedPurpose)

        val code = generateCode()
        val verifyId = repository.insertRequest(
            email = normalizedEmail,
            purposeCd = normalizedPurpose,
            codeHash = passwordEncoderService.encode(code),
            targetUserId = targetUserId,
            expireMinutes = props.expireMinutes,
            ipAddr = currentIp()
        )

        // 4. 발송 — 실패해도 요청 자체는 남겨 원인을 추적한다.
        runCatching { mailSender.sendVerificationCode(normalizedEmail, normalizedPurpose, code, props.expireMinutes) }
            .onFailure {
                repository.updateSendResult(verifyId, "FAIL", it.message)
                log.error("인증 메일 발송 실패: to={} purpose={}", normalizedEmail, normalizedPurpose, it)
                throw BusinessRuleException("인증 메일 발송에 실패했습니다. 잠시 후 다시 시도해 주세요.")
            }

        return mapOf(
            "email" to maskEmail(normalizedEmail),
            "purpose" to normalizedPurpose,
            "expiresAt" to repository.findExpiresAtText(verifyId),
            "expireMinutes" to props.expireMinutes,
            "resendAvailableInSec" to props.resendWaitSec
        )
    }

    /**
     * 인증 코드를 검증하고 1회용 토큰을 발급한다.
     *
     * @return verificationToken 과 유효 시간
     */
    @Transactional
    fun verifyCode(email: String, purpose: String, code: String): Map<String, Any?> {
        val normalizedEmail = normalizeEmail(email)
        val normalizedPurpose = normalizePurpose(purpose)

        if (code.isBlank()) throw InvalidParameterException("인증 코드를 입력해 주세요.", "code")

        val request = repository.findLatestPending(normalizedEmail, normalizedPurpose, props.maxAttempts)
            ?: throw BusinessRuleException("유효한 인증 요청이 없습니다. 인증 코드를 다시 요청해 주세요.")

        // 1. 만료 확인 — 잔여 시간은 DB 시계로 계산된 값이다.
        val expiresInSec = request["expiresInSec"] as? Long ?: 0L
        if (expiresInSec <= 0L) {
            throw BusinessRuleException("인증 코드가 만료되었습니다. 다시 요청해 주세요.")
        }

        // 2. 코드 대조 — 실패는 시도 횟수를 올려 무차별 대입을 막는다.
        val verifyId = request["verifyId"] as Long
        if (!passwordEncoderService.matches(code.trim(), request["codeHash"] as String?)) {
            val attempts = repository.increaseAttempt(verifyId)
            val remain = props.maxAttempts - attempts
            throw if (remain <= 0) {
                BusinessRuleException("인증 시도 횟수를 초과했습니다. 인증 코드를 다시 요청해 주세요.")
            } else {
                InvalidParameterException("인증 코드가 올바르지 않습니다. (남은 시도 ${remain}회)", "code")
            }
        }

        // 3. 성공 — 1회용 토큰 발급
        val token = generateToken()
        repository.markVerified(verifyId, token)

        log.info("이메일 인증 성공: email={} purpose={}", maskEmail(normalizedEmail), normalizedPurpose)

        return mapOf(
            "verificationToken" to token,
            "purpose" to normalizedPurpose,
            "expireMinutes" to props.tokenExpireMinutes
        )
    }

    /**
     * 발급된 토큰을 검증하고 즉시 소모한다.
     *
     * @param token        1회용 토큰
     * @param purpose      기대하는 인증 목적
     * @param expectedEmail 가입 시 입력한 이메일과 인증한 이메일이 같은지 확인 (null 이면 생략)
     * @return 인증된 이메일과 대상 계정
     */
    @Transactional
    fun consumeToken(token: String?, purpose: String, expectedEmail: String? = null): VerifiedEmail {
        val value = token?.trim()?.takeIf { it.isNotBlank() }
            ?: throw InvalidParameterException("이메일 인증을 먼저 완료해 주세요.", "verificationToken")

        val normalizedPurpose = normalizePurpose(purpose)
        val found = repository.findUsableToken(value, normalizedPurpose, props.tokenExpireMinutes)
            ?: throw BusinessRuleException("이메일 인증이 만료되었거나 이미 사용되었습니다. 인증을 다시 진행해 주세요.")

        val verifiedEmail = found["email"] as String

        // 인증한 주소와 실제 사용 주소가 다르면 다른 사람의 인증을 빌려 쓰는 것이다.
        if (expectedEmail != null && !verifiedEmail.equals(normalizeEmail(expectedEmail), ignoreCase = true)) {
            throw BusinessRuleException("인증한 이메일과 입력한 이메일이 다릅니다.")
        }

        // 1회용 보장 — 동시 요청에서도 한 번만 통과한다.
        if (repository.consumeToken(found["verifyId"] as Long) == 0) {
            throw BusinessRuleException("이미 사용된 인증입니다. 인증을 다시 진행해 주세요.")
        }

        return VerifiedEmail(email = verifiedEmail, targetUserId = found["targetUserId"] as String?)
    }

    /**
     * 소모된 인증 결과
     *
     * @param email        인증된 이메일
     * @param targetUserId 비밀번호 찾기 대상 계정 (가입은 null)
     */
    data class VerifiedEmail(val email: String, val targetUserId: String?)

    // ---------------------------------------------------------------------------------
    // 내부 유틸
    // ---------------------------------------------------------------------------------

    /**
     * 숫자 인증 코드를 생성한다.
     *
     * `app.security.email-verification.fixed-code` 가 지정되어 있으면 그 값을 쓴다.
     * 자동 테스트용이며 local 프로파일에서만 설정된다. (SMTP 모드에서는 기동 시 거부)
     */
    private fun generateCode(): String =
        props.fixedCode?.takeIf { it.isNotBlank() }
            ?: (1..props.codeLength).map { random.nextInt(10) }.joinToString("")

    /** URL 안전한 1회용 토큰을 생성한다. */
    private fun generateToken(): String {
        val bytes = ByteArray(32).also { random.nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** 이메일 형식을 검증하고 소문자로 정규화한다. */
    fun normalizeEmail(email: String?): String {
        val value = email?.trim()?.lowercase()
            ?: throw InvalidParameterException("이메일을 입력해 주세요.", "email")
        if (value.isBlank()) throw InvalidParameterException("이메일을 입력해 주세요.", "email")
        if (value.length > 200) throw InvalidParameterException("이메일이 너무 깁니다.", "email")
        if (!EMAIL_PATTERN.matches(value)) {
            throw InvalidParameterException("이메일 형식이 올바르지 않습니다.", "email")
        }
        return value
    }

    /** 인증 목적을 화이트리스트로 검증한다. */
    private fun normalizePurpose(purpose: String?): String {
        val value = purpose?.trim()?.uppercase()
            ?: throw InvalidParameterException("인증 목적을 지정해 주세요.", "purpose")
        if (value !in ALLOWED_PURPOSES) {
            throw InvalidParameterException("인증 목적은 SIGNUP 또는 PASSWORD_RESET 만 허용합니다.", "purpose")
        }
        return value
    }

    /**
     * 응답에 이메일을 노출할 때 일부를 가린다. (계정 존재 여부 추측 방지)
     *
     * `hong@dwje.co.kr` → `ho**@dwje.co.kr`
     */
    fun maskEmail(email: String): String {
        val at = email.indexOf('@')
        if (at <= 0) return "***"
        val local = email.substring(0, at)
        val masked = when {
            local.length <= 2 -> local.first() + "*"
            else -> local.take(2) + "*".repeat(minOf(local.length - 2, 4))
        }
        return masked + email.substring(at)
    }

    /** 현재 요청의 클라이언트 IP */
    private fun currentIp(): String? {
        val attrs = RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes ?: return null
        return clientIp(attrs.request)
    }

    private fun clientIp(request: HttpServletRequest): String? {
        val forwarded = request.getHeader("X-Forwarded-For")
        if (!forwarded.isNullOrBlank()) return forwarded.split(",").first().trim()
        return request.remoteAddr
    }
}
