package com.dwje.api.common.security

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.config.PasswordProperties
import org.slf4j.LoggerFactory
import org.springframework.security.crypto.bcrypt.BCrypt
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * 비밀번호 해시 생성·검증 컴포넌트
 *
 * ## 저장 포맷
 * 알고리즘을 접두사로 담아 **자기 서술적**으로 저장한다. 알고리즘을 바꿔도 기존 해시가 그대로 검증된다.
 *
 * ```
 * {pbkdf2-sha512}210000$<솔트 Base64>$<해시 Base64>   ← 기본
 * {sha512}<솔트 Base64>$<해시 Base64>                  ← 단순 SHA-512 (레거시 호환용)
 * $2a$10$....                                          ← BCrypt (이전에 발급된 계정)
 * ```
 *
 * `ax.tb_sys_user.pwd_hash` 는 varchar(200) 이며 가장 긴 PBKDF2 포맷도 약 137자로 여유가 있다.
 *
 * ## SHA-512 를 쓰되 반복 확장을 두는 이유
 * SHA-512 는 원래 **빠르게** 설계된 해시라 GPU 로 초당 수십억 회 시도할 수 있다.
 * 비밀번호에 단독으로 쓰면 유출 시 대입 공격에 취약하다.
 * 그래서 기본값은 같은 SHA-512 계열이면서 솔트·반복(PBKDF2-HMAC-SHA512, RFC 8018)을 적용한다.
 * 기존 시스템과 해시 값을 **바이트 단위로 맞춰야 하는 경우**에만
 * `app.security.password.algorithm: SHA512` 로 단순 방식을 쓴다.
 */
@Component
class PasswordEncoderService(private val props: PasswordProperties) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val random = SecureRandom()

    companion object {
        const val PREFIX_PBKDF2 = "{pbkdf2-sha512}"
        const val PREFIX_SHA512 = "{sha512}"

        private const val ALGO_PBKDF2 = "PBKDF2_SHA512"
        private const val ALGO_SHA512 = "SHA512"

        private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA512"
        private const val DIGEST_ALGORITHM = "SHA-512"

        /** SHA-512 출력 길이(비트) */
        private const val KEY_LENGTH_BITS = 512

        /** BCrypt 해시 접두사 */
        private val BCRYPT_PREFIXES = listOf("\$2a\$", "\$2b\$", "\$2y\$")
    }

    /**
     * 평문 비밀번호를 설정된 알고리즘으로 해시한다.
     *
     * @param rawPassword 평문 비밀번호
     * @return 알고리즘 접두사를 포함한 저장용 해시 문자열
     */
    fun encode(rawPassword: String): String {
        val salt = ByteArray(props.saltBytes).also { random.nextBytes(it) }

        return when (props.algorithm.uppercase()) {
            ALGO_SHA512 -> PREFIX_SHA512 + encodeB64(salt) + "$" + encodeB64(sha512(salt, rawPassword))
            else -> {
                val hash = pbkdf2(rawPassword, salt, props.iterations)
                "$PREFIX_PBKDF2${props.iterations}$${encodeB64(salt)}$${encodeB64(hash)}"
            }
        }
    }

    /**
     * 평문 비밀번호가 저장된 해시와 일치하는지 확인한다.
     *
     * 저장 포맷을 스스로 판별하므로 PBKDF2·단순 SHA-512·BCrypt 가 섞여 있어도 동작한다.
     * 비교는 타이밍 공격을 피하기 위해 상수 시간 비교를 쓴다.
     *
     * @param rawPassword 입력 평문
     * @param storedHash  DB 에 저장된 해시 (null 이면 항상 false)
     */
    fun matches(rawPassword: String, storedHash: String?): Boolean {
        if (storedHash.isNullOrBlank()) return false

        return runCatching {
            when {
                storedHash.startsWith(PREFIX_PBKDF2) -> matchesPbkdf2(rawPassword, storedHash)
                storedHash.startsWith(PREFIX_SHA512) -> matchesSha512(rawPassword, storedHash)
                BCRYPT_PREFIXES.any { storedHash.startsWith(it) } -> BCrypt.checkpw(rawPassword, storedHash)
                else -> {
                    log.warn("알 수 없는 비밀번호 해시 포맷입니다. 계정 비밀번호를 재설정해야 합니다.")
                    false
                }
            }
        }.getOrElse {
            log.warn("비밀번호 검증 중 오류: {}", it.message)
            false
        }
    }

    /**
     * 저장된 해시가 현재 설정 기준에 못 미쳐 재해시가 필요한지 판정한다.
     *
     * 로그인 성공 시점에 조용히 최신 포맷으로 올려 두면 사용자는 아무것도 하지 않아도 된다.
     *
     * @return 재해시 대상이면 true (BCrypt 레거시, 알고리즘 변경, 반복 횟수 상향)
     */
    fun needsRehash(storedHash: String?): Boolean {
        if (storedHash.isNullOrBlank()) return true

        // 레거시 BCrypt 는 현재 표준이 아니므로 재해시 대상이다.
        if (BCRYPT_PREFIXES.any { storedHash.startsWith(it) }) return true

        return when (props.algorithm.uppercase()) {
            ALGO_SHA512 -> !storedHash.startsWith(PREFIX_SHA512)
            else -> {
                if (!storedHash.startsWith(PREFIX_PBKDF2)) return true
                // 반복 횟수를 올린 뒤에는 기존 해시도 새 기준으로 다시 만든다.
                val iterations = storedHash.removePrefix(PREFIX_PBKDF2).substringBefore('$').toIntOrNull()
                iterations == null || iterations < props.iterations
            }
        }
    }

    /**
     * 비밀번호 정책을 검증한다. 위반 시 E-VALID-001 로 사유를 알려 준다.
     *
     * @param rawPassword 검사할 평문
     * @param field       오류 응답에 표기할 요청 필드명
     */
    fun validatePolicy(rawPassword: String?, field: String = "password") {
        val password = rawPassword ?: throw InvalidParameterException("비밀번호를 입력해 주세요.", field)

        if (password.length < props.minLength) {
            throw InvalidParameterException("비밀번호는 ${props.minLength}자 이상이어야 합니다.", field)
        }
        if (password.any { it.isWhitespace() }) {
            throw InvalidParameterException("비밀번호에 공백을 포함할 수 없습니다.", field)
        }
        if (props.requireMixedTypes) {
            // 영문·숫자·특수문자 중 2종 이상을 섞도록 요구한다.
            val kinds = listOf(
                password.any { it.isLetter() },
                password.any { it.isDigit() },
                password.any { !it.isLetterOrDigit() }
            ).count { it }

            if (kinds < 2) {
                throw InvalidParameterException("비밀번호는 영문·숫자·특수문자 중 2종 이상을 조합해야 합니다.", field)
            }
        }
    }

    // ---------------------------------------------------------------------------------
    // 내부 구현
    // ---------------------------------------------------------------------------------

    /** `{pbkdf2-sha512}반복$솔트$해시` 검증 */
    private fun matchesPbkdf2(rawPassword: String, storedHash: String): Boolean {
        val parts = storedHash.removePrefix(PREFIX_PBKDF2).split("$")
        if (parts.size != 3) return false

        val iterations = parts[0].toIntOrNull() ?: return false
        val salt = decodeB64(parts[1])
        val expected = decodeB64(parts[2])

        return MessageDigest.isEqual(pbkdf2(rawPassword, salt, iterations), expected)
    }

    /** `{sha512}솔트$해시` 검증 */
    private fun matchesSha512(rawPassword: String, storedHash: String): Boolean {
        val parts = storedHash.removePrefix(PREFIX_SHA512).split("$")
        if (parts.size != 2) return false

        val salt = decodeB64(parts[0])
        val expected = decodeB64(parts[1])

        return MessageDigest.isEqual(sha512(salt, rawPassword), expected)
    }

    /** PBKDF2-HMAC-SHA512 파생 키 생성 */
    private fun pbkdf2(rawPassword: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(rawPassword.toCharArray(), salt, iterations, KEY_LENGTH_BITS)
        return try {
            SecretKeyFactory.getInstance(PBKDF2_ALGORITHM).generateSecret(spec).encoded
        } finally {
            // 파생 후 평문 사본을 즉시 지운다.
            spec.clearPassword()
        }
    }

    /** 솔트를 앞에 붙인 단일 SHA-512 */
    private fun sha512(salt: ByteArray, rawPassword: String): ByteArray =
        MessageDigest.getInstance(DIGEST_ALGORITHM).apply {
            update(salt)
            update(rawPassword.toByteArray(Charsets.UTF_8))
        }.digest()

    private fun encodeB64(bytes: ByteArray): String = Base64.getEncoder().withoutPadding().encodeToString(bytes)

    private fun decodeB64(value: String): ByteArray = Base64.getDecoder().decode(value)
}
