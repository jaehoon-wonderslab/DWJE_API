package com.dwje.api

import com.dwje.api.config.AppProperties
import com.dwje.api.config.JwtProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * 설정 바인딩·검증 규약 테스트
 *
 * 고정하는 규약
 * 1. yml 값 > Kotlin 생성자 기본값 (우선순위)
 * 2. 서명 키 누락·미해석 플레이스홀더·길이 부족은 모두 **기동 실패**
 * 3. 업무 설정 값의 범위 제약도 기동 시점에 걸린다
 */
class ConfigBindingTest {

    @EnableConfigurationProperties(AppProperties::class, JwtProperties::class)
    class ConfigOnly

    /** 모든 제약을 통과하는 더미 서명 키 (45바이트) */
    private val validSecret = "test-only-secret-key-0123456789-abcdefghijklmn"

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration::class.java))
        .withUserConfiguration(ConfigOnly::class.java)
        .withPropertyValues("app.jwt.secret=$validSecret")

    // =================================================================================
    // 1. 우선순위
    // =================================================================================

    @Test
    @DisplayName("yml 키가 없으면 Kotlin 생성자 기본값이 쓰인다")
    fun fallsBackToConstructorDefault() {
        runner.run { ctx ->
            val app = ctx.getBean(AppProperties::class.java)
            assertEquals("PL01", app.defaultPlantCd)
            assertEquals(5, app.loginFailLimit)
            assertEquals(true, app.maskingEnabled)
            assertEquals(3, app.downloadRetentionYears)

            val jwt = ctx.getBean(JwtProperties::class.java)
            assertEquals("dwje-api", jwt.issuer)
            assertEquals(3600L, jwt.accessTokenValiditySec)
        }
    }

    @Test
    @DisplayName("yml 키가 있으면 생성자 기본값을 덮어쓴다")
    fun ymlOverridesConstructorDefault() {
        runner.withPropertyValues(
            "app.login-fail-limit=9",
            "app.default-plant-cd=PL03",
            "app.jwt.issuer=dwje-dev"
        ).run { ctx ->
            val app = ctx.getBean(AppProperties::class.java)
            assertEquals(9, app.loginFailLimit)
            assertEquals("PL03", app.defaultPlantCd)
            assertEquals("dwje-dev", ctx.getBean(JwtProperties::class.java).issuer)
        }
    }

    // =================================================================================
    // 2. 서명 키 검증 — 세 가지 실패 경로
    // =================================================================================

    @Test
    @DisplayName("서명 키가 비어 있으면 기동에 실패한다")
    fun blankSecretIsRejected() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration::class.java))
            .withUserConfiguration(ConfigOnly::class.java)
            .run { ctx -> assertFailsWith(ctx, "JWT 서명 키(app.jwt.secret)가 설정되지 않았습니다") }
    }

    @Test
    @DisplayName("환경변수 미주입으로 남은 플레이스홀더는 길이와 무관하게 차단된다")
    fun unresolvedPlaceholderIsRejected() {
        // 32바이트를 넘겨 길이 검증만으로는 통과하는 이름이어도 반드시 걸려야 한다.
        val longPlaceholder = "\${PROD_JWT_SIGNING_SECRET_KEY_VALUE_LONG_ENOUGH}"
        check(longPlaceholder.toByteArray().size >= 32) { "테스트 전제: 길이 검증만으로는 못 걸러야 한다" }

        runner.withPropertyValues("app.jwt.secret=$longPlaceholder")
            .run { ctx -> assertFailsWith(ctx, "플레이스홀더") }
    }

    @Test
    @DisplayName("서명 키가 256비트 미만이면 기동에 실패한다")
    fun shortSecretIsRejected() {
        runner.withPropertyValues("app.jwt.secret=too-short-key")
            .run { ctx -> assertFailsWith(ctx, "32바이트(256비트) 이상") }
    }

    // =================================================================================
    // 3. 업무 설정 범위 제약
    // =================================================================================

    @Test
    @DisplayName("로그인 실패 잠금 임계값이 범위를 벗어나면 기동에 실패한다")
    fun loginFailLimitOutOfRangeIsRejected() {
        runner.withPropertyValues("app.login-fail-limit=0")
            .run { ctx -> assertFailsWith(ctx, "1 이상") }

        runner.withPropertyValues("app.login-fail-limit=999")
            .run { ctx -> assertFailsWith(ctx, "20 이하") }
    }

    @Test
    @DisplayName("보존 연수가 범위를 벗어나면 기동에 실패한다")
    fun retentionYearsOutOfRangeIsRejected() {
        runner.withPropertyValues("app.download-retention-years=0")
            .run { ctx -> assertFailsWith(ctx, "1년 이상") }
    }

    /**
     * 컨텍스트 기동이 실패했고, 실패 내용에 기대 메시지가 포함되는지 확인한다.
     */
    private fun assertFailsWith(ctx: AssertableApplicationContext, expectedMessage: String) {
        val failure = ctx.startupFailure
        check(failure != null) { "기동이 실패해야 하는데 정상 기동했다 (기대 메시지: $expectedMessage)" }

        val detail = failure.stackTraceToString()
        check(detail.contains(expectedMessage)) {
            "기대 메시지를 찾지 못했다.\n  기대: $expectedMessage\n  실제: ${detail.lines().take(12).joinToString("\n")}"
        }
    }
}
