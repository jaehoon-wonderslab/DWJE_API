package com.dwje.api

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.io.File

/**
 * 요청 본문 계약 테스트
 *
 * `FAIL_ON_UNKNOWN_PROPERTIES` 가 켜져 있어 타입 DTO 로 받는 본문은 모르는 키를 400 으로 막는다.
 * 그런데 `Map` 으로 받는 본문은 Jackson 이 거를 근거가 없어 **여전히 조용히 버린다.**
 * 그 목록이 `docs/REQUEST_BODY_CONTRACT.md` 에 적혀 있는데, 손으로 유지하면 어긋난다 —
 * 실제로 그 문서를 쓴 날 용어 API 4개를 DTO 로 바꾸고 문서를 갱신하지 않아 같은 날 안에 틀렸다.
 *
 * 이 테스트가 고정하는 규약
 * 1. `Map` 본문으로 받는 엔드포인트가 문서에 적힌 것과 정확히 일치한다
 * 2. 새 `Map` 본문이 생기면 빌드가 깨진다 — 조용히 무시되는 자리가 늘어나는 것을 알린다
 */
class RequestBodyContractTest {

    private val doc = File("docs/REQUEST_BODY_CONTRACT.md")

    /** 컨트롤러에서 `Map` 으로 본문을 받는 메서드를 찾는다. */
    private fun mapBodyMethods(): Set<String> {
        val scanner = ClassPathScanningCandidateComponentProvider(false).apply {
            addIncludeFilter(AnnotationTypeFilter(RestController::class.java))
        }
        return scanner.findCandidateComponents("com.dwje.api.controller")
            .mapNotNull { bd: BeanDefinition -> bd.beanClassName?.let { Class.forName(it) } }
            .flatMap { it.declaredMethods.toList() }
            .filter { method ->
                method.parameters.any { p ->
                    p.isAnnotationPresent(RequestBody::class.java) && Map::class.java.isAssignableFrom(p.type)
                }
            }
            .map { "${it.declaringClass.simpleName}.${it.name}" }
            .toSet()
    }

    @Test
    @DisplayName("Map 본문으로 받는 엔드포인트 수가 문서와 일치한다")
    fun mapBodyCountMatchesDoc() {
        assertTrue(doc.exists()) {
            "요청 본문 계약 문서가 없습니다. [${doc.path}] 저장소에 포함되는 파일입니다."
        }

        val actual = mapBodyMethods()
        val declared = Regex("""`Map` 본문 \*\*(\d+)개\*\*""")
            .find(doc.readText())
            ?.groupValues
            ?.get(1)
            ?.toInt()

        // 문서에서 숫자를 못 뽑으면 이 검사가 무력해진다.
        assertTrue(declared != null) {
            "문서에서 Map 본문 개수를 찾지 못했습니다. 서술 형식이 바뀌었다면 이 검사도 함께 고치세요."
        }

        assertTrue(actual.size == declared) {
            "Map 본문 엔드포인트가 문서와 다릅니다. 문서 $declared 개 / 실제 ${actual.size}개\n" +
                "  실제: ${actual.sorted()}\n" +
                "  Map 본문은 모르는 키를 조용히 버립니다. 늘었다면 타입 DTO 로 바꾸는 것을 검토하고, " +
                "그대로 둘 것이면 docs/REQUEST_BODY_CONTRACT.md 를 다시 생성하세요."
        }
    }

    @Test
    @DisplayName("사용자가 직접 입력하는 폼은 Map 본문으로 받지 않는다")
    fun userFacingFormsUseTypedBodies() {
        // 용어·유사어는 사용자가 값을 손으로 입력하는 자리다. 키 오타가 실제로 발생하는 경로라
        // Map 으로 받으면 200 이 나가고 값은 반영되지 않는다. 타입 DTO 로 고정한다.
        val mapBodies = mapBodyMethods()
        listOf("GlossaryController.createTerm", "GlossaryController.updateTerm",
               "GlossaryController.createVariant", "GlossaryController.updateVariant")
            .forEach { method ->
                assertTrue(method !in mapBodies) {
                    "$method 이 Map 본문으로 되돌아갔습니다. 키 오타가 조용히 무시됩니다."
                }
            }
    }
}
