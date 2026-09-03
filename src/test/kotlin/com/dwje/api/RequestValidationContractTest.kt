package com.dwje.api

import jakarta.validation.Valid
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.lang.reflect.Method
import java.lang.reflect.Parameter

/**
 * 요청 본문 검증 규약 테스트
 *
 * ## 막으려는 실수
 * 요청 DTO 에 `@field:NotBlank` 같은 제약을 달아도, 컨트롤러 파라미터에 `@Valid` 가 없으면
 * Spring 은 **아무 말 없이 검증을 건너뛴다**. 개발자는 검증이 걸린 줄 알지만 실제로는 무방비다.
 *
 * 이 테스트는 "제약이 있는 DTO 는 반드시 `@Valid` 로 받는다"는 규약을 빌드 시점에 고정한다.
 * 나중에 누가 DTO 에 제약만 추가하고 컨트롤러를 놓치면 빌드가 깨진다.
 */
class RequestValidationContractTest {

    companion object {
        private const val CONTROLLER_PACKAGE = "com.dwje.api.controller"

        /** Jakarta Bean Validation 표준 제약이 속한 패키지 */
        private const val CONSTRAINT_PACKAGE = "jakarta.validation.constraints"

        /** 프로젝트에서 정의한 커스텀 제약이 속한 패키지 */
        private const val CUSTOM_CONSTRAINT_PACKAGE = "com.dwje.api.common.validation"
    }

    @Test
    @DisplayName("제약이 있는 요청 DTO 는 반드시 @Valid 와 함께 받아야 한다")
    fun constrainedRequestBodiesMustBeValidated() {
        val violations = mutableListOf<String>()

        scanControllers().forEach { controller ->
            controller.declaredMethods.forEach { method ->
                method.parameters
                    .filter { it.isAnnotationPresent(RequestBody::class.java) }
                    .filter { hasConstraints(it.type) }
                    .filterNot { isValidated(it) }
                    .forEach { param ->
                        violations += "${controller.simpleName}.${method.name}() ← " +
                            "${param.type.simpleName} 에 제약이 있으나 @Valid 가 없다"
                    }
            }
        }

        check(violations.isEmpty()) {
            buildString {
                appendLine("제약이 선언된 요청 DTO 가 @Valid 없이 바인딩되고 있다 (검증이 무시됨):")
                violations.forEach { appendLine("  - $it") }
                appendLine()
                appendLine("해당 파라미터를 '@Valid @RequestBody' 형태로 수정하라.")
            }
        }
    }

    @Test
    @DisplayName("스캔 자체가 동작하는지 확인 — 컨트롤러와 제약 DTO 가 실제로 발견되어야 한다")
    fun scannerFindsControllersAndConstraints() {
        val controllers = scanControllers()
        check(controllers.size >= 10) { "컨트롤러 스캔 실패: ${controllers.size}개만 발견됨" }

        val constrainedBodies = controllers
            .flatMap { it.declaredMethods.toList() }
            .flatMap { it.parameters.toList() }
            .filter { it.isAnnotationPresent(RequestBody::class.java) }
            .filter { hasConstraints(it.type) }

        // 규약 테스트가 항상 공회전하지 않도록, 검사 대상이 실제로 존재하는지 확인한다.
        check(constrainedBodies.isNotEmpty()) { "제약이 있는 요청 DTO 를 하나도 찾지 못했다 — 스캔 로직 점검 필요" }
    }

    /** `com.dwje.api.controller` 패키지의 @RestController 클래스를 모두 찾는다. */
    private fun scanControllers(): List<Class<*>> {
        val scanner = ClassPathScanningCandidateComponentProvider(false).apply {
            addIncludeFilter(AnnotationTypeFilter(RestController::class.java))
        }
        return scanner.findCandidateComponents(CONTROLLER_PACKAGE)
            .mapNotNull { bd: BeanDefinition -> bd.beanClassName?.let { Class.forName(it) } }
            .sortedBy { it.simpleName }
    }

    /**
     * DTO 필드에 Bean Validation 제약이 하나라도 선언되어 있는지 확인한다.
     *
     * Kotlin 의 `@field:NotBlank` 는 백킹 필드에 붙으므로 declaredFields 로 확인할 수 있다.
     */
    private fun hasConstraints(type: Class<*>): Boolean =
        type.declaredFields.any { field ->
            field.annotations.any { ann ->
                val pkg = ann.annotationClass.java.packageName
                pkg == CONSTRAINT_PACKAGE || pkg == CUSTOM_CONSTRAINT_PACKAGE
            }
        }

    /** 파라미터에 @Valid 또는 @Validated 가 붙어 있는지 확인한다. */
    private fun isValidated(param: Parameter): Boolean =
        param.isAnnotationPresent(Valid::class.java) || param.isAnnotationPresent(Validated::class.java)
}
