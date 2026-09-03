package com.dwje.api

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.security.PasswordEncoderService
import com.dwje.api.config.PasswordProperties
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.crypto.bcrypt.BCrypt

/**
 * 비밀번호 해시·정책 테스트
 *
 * 반복 횟수를 낮춰 테스트 속도를 확보하되, 알고리즘 동작 자체는 운영 설정과 동일하다.
 */
class PasswordEncoderTest {

    private val pbkdf2 = PasswordEncoderService(PasswordProperties(algorithm = "PBKDF2_SHA512", iterations = 10_000))
    private val sha512 = PasswordEncoderService(PasswordProperties(algorithm = "SHA512"))

    private val raw = "Dwje!2026"

    @Test
    @DisplayName("PBKDF2-SHA512 로 해시하고 검증한다")
    fun pbkdf2RoundTrip() {
        val hash = pbkdf2.encode(raw)

        assertTrue(hash.startsWith(PasswordEncoderService.PREFIX_PBKDF2), "알고리즘 접두사가 있어야 한다: $hash")
        assertTrue(pbkdf2.matches(raw, hash))
        assertFalse(pbkdf2.matches("wrong-password", hash))
    }

    @Test
    @DisplayName("단순 SHA-512 로 해시하고 검증한다")
    fun sha512RoundTrip() {
        val hash = sha512.encode(raw)

        assertTrue(hash.startsWith(PasswordEncoderService.PREFIX_SHA512), "알고리즘 접두사가 있어야 한다: $hash")
        assertTrue(sha512.matches(raw, hash))
        assertFalse(sha512.matches("wrong-password", hash))
    }

    @Test
    @DisplayName("같은 비밀번호라도 솔트가 달라 해시가 매번 다르다")
    fun saltMakesHashesUnique() {
        assertNotEquals(pbkdf2.encode(raw), pbkdf2.encode(raw))
        assertNotEquals(sha512.encode(raw), sha512.encode(raw))
    }

    @Test
    @DisplayName("알고리즘이 달라도 접두사로 판별해 서로 검증한다")
    fun crossAlgorithmVerification() {
        // 설정이 PBKDF2 여도 과거에 SHA512 로 저장된 해시를 검증할 수 있어야 한다.
        assertTrue(pbkdf2.matches(raw, sha512.encode(raw)))
        assertTrue(sha512.matches(raw, pbkdf2.encode(raw)))
    }

    @Test
    @DisplayName("이전에 BCrypt 로 발급된 계정도 계속 로그인된다")
    fun legacyBcryptStillVerifies() {
        val legacy = BCrypt.hashpw(raw, BCrypt.gensalt(4))

        assertTrue(pbkdf2.matches(raw, legacy), "레거시 BCrypt 해시를 검증하지 못하면 기존 계정이 잠긴다")
        assertFalse(pbkdf2.matches("wrong-password", legacy))
        assertTrue(pbkdf2.needsRehash(legacy), "BCrypt 는 최신 포맷으로 재해시 대상이어야 한다")
    }

    @Test
    @DisplayName("반복 횟수를 올리면 기존 해시가 재해시 대상이 된다")
    fun higherIterationsTriggerRehash() {
        val oldHash = pbkdf2.encode(raw)
        val stronger = PasswordEncoderService(PasswordProperties(algorithm = "PBKDF2_SHA512", iterations = 50_000))

        assertTrue(stronger.matches(raw, oldHash), "재해시 전에도 검증은 되어야 한다")
        assertTrue(stronger.needsRehash(oldHash))
        assertFalse(stronger.needsRehash(stronger.encode(raw)))
    }

    @Test
    @DisplayName("null·빈 해시나 알 수 없는 포맷은 통과시키지 않는다")
    fun rejectsUnknownFormats() {
        assertFalse(pbkdf2.matches(raw, null))
        assertFalse(pbkdf2.matches(raw, ""))
        assertFalse(pbkdf2.matches(raw, "plain-text-password"))
        // 접두사만 있고 본문이 깨진 경우
        assertFalse(pbkdf2.matches(raw, "${PasswordEncoderService.PREFIX_PBKDF2}broken"))
    }

    @Test
    @DisplayName("비밀번호 정책 — 길이·공백·문자 조합")
    fun passwordPolicy() {
        // 정상
        pbkdf2.validatePolicy("Dwje!2026")
        pbkdf2.validatePolicy("abcd1234")

        assertThrows<InvalidParameterException> { pbkdf2.validatePolicy("Ab1!") }          // 너무 짧음
        assertThrows<InvalidParameterException> { pbkdf2.validatePolicy("abcd 1234") }     // 공백
        assertThrows<InvalidParameterException> { pbkdf2.validatePolicy("abcdefghij") }    // 영문만
        assertThrows<InvalidParameterException> { pbkdf2.validatePolicy(null) }            // 미입력
    }

    @Test
    @DisplayName("해시 문자열이 pwd_hash varchar(200) 에 들어간다")
    fun hashFitsColumn() {
        val production = PasswordEncoderService(PasswordProperties(algorithm = "PBKDF2_SHA512", iterations = 210_000))
        listOf(production.encode(raw), sha512.encode(raw)).forEach {
            assertTrue(it.length <= 200, "해시 길이 ${it.length} 가 컬럼 한계를 넘는다: $it")
        }
    }
}
