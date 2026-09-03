package com.dwje.api.common.validation

import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass

/**
 * 설정 값이 **해석된 실제 값**인지 검증하는 제약
 *
 * ## 왜 필요한가
 * Spring 의 설정 바인딩(`Binder`)은 해석되지 않은 `${ENV}` 를 오류로 처리하지 않고
 * **문자열 그대로** 바인딩한다. 생성자 기본값으로 폴백하지도 않는다.
 * 따라서 아래 yml 에서 환경변수가 주입되지 않으면 서명 키 값이 `"${PROD_JWT_SECRET}"` 이 된다.
 *
 * ```yaml
 * app:
 *   jwt:
 *     secret: "${PROD_JWT_SECRET}"
 * ```
 *
 * 이 상태로 기동하면 소스에 공개된 문자열이 서명 키가 되어 누구나 토큰을 위조할 수 있다.
 * 길이 검증(`@Size`)만으로는 환경변수 이름이 길면 통과해 버리므로 별도 제약이 필요하다.
 *
 * ## 오탐 위험
 * `${식별자}` 형태를 포함할 때만 위반으로 본다. Base64·Hex 키는 `{`, `}` 를 포함할 수 없고,
 * 임의 패스프레이즈가 이 형태를 우연히 포함할 가능성은 사실상 없다.
 *
 * @see ResolvedPlaceholderValidator
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Constraint(validatedBy = [ResolvedPlaceholderValidator::class])
annotation class ResolvedPlaceholder(
    val message: String = "환경변수가 주입되지 않아 설정 값이 플레이스홀더(\${...}) 상태로 남아 있습니다. " +
        "해당 환경변수를 설정한 뒤 다시 기동하세요.",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = []
)

/**
 * [ResolvedPlaceholder] 검증기
 *
 * ### 위반 메시지에 원본 값을 넣지 않는 이유
 * Hibernate Validator 는 메시지에 EL(`${...}`) 보간을 수행한다.
 * 위반 값(`${PROD_JWT_SECRET}`)을 메시지에 그대로 넣으면 EL 로 평가되어
 * 예외가 나거나 의도치 않은 값이 노출된다. 어떤 속성인지는 위반 경로(property path)가 알려주므로
 * 메시지는 정적으로 유지한다.
 */
class ResolvedPlaceholderValidator : ConstraintValidator<ResolvedPlaceholder, String> {

    companion object {
        /** `${VAR}` · `${VAR:default}` 형태의 플레이스홀더 토큰 */
        private val PLACEHOLDER_TOKEN = Regex("""\$\{[A-Za-z_][A-Za-z0-9_.\-]*(?::[^}]*)?}""")
    }

    override fun isValid(value: String?, context: ConstraintValidatorContext?): Boolean {
        // null·공백 판정은 @NotBlank 의 책임이므로 여기서는 통과시킨다. (제약 하나당 관심사 하나)
        if (value.isNullOrBlank()) return true
        return !PLACEHOLDER_TOKEN.containsMatchIn(value)
    }
}
