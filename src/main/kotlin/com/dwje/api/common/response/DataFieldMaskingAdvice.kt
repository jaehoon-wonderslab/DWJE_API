package com.dwje.api.common.response

import com.dwje.api.common.security.UserContext
import com.dwje.api.service.DataFieldService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.slf4j.LoggerFactory
import org.springframework.core.MethodParameter
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.converter.json.AbstractJackson2HttpMessageConverter
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice

/**
 * 응답 필드명 기준 공통 마스킹 — 모든 [ApiResponse] 의 `data` 를 훑어, 조회자가 열람할 수 없는
 * **적용 중 데이터 항목의 응답 필드명**(`ax.tb_sys_data_field_attr`) 값을 null 로 바꾸고 `masked` 에 항목 key 를 더한다.
 *
 * ## 왜 필요한가
 * 기본 7개 항목은 API 마다 코드로 가린다([com.dwje.api.common.util.MaskingSupport]). 운영 중에 화면에서 추가한 항목은
 * 코드가 모르므로 `/auth/me` dataFields(화면)와 AI 표 블록에만 걸리고, API 응답 원본에는 값이 그대로 실렸다
 * (2026-09-23 WEB 요청 — 개발자 도구·다른 클라이언트로 보였다). 이 advice 가 그 틈을 막는다.
 *
 * ## 규칙
 * - 판정은 DB 만 본다 — 필드명→항목은 [DataFieldService.attrFieldMap](적용 중 항목만), 열람 가능 여부는 요청마다 새로 읽는
 *   principal 의 데이터 권한(`ax.tb_sys_dept_data_perm`). 통합관리자는 가리지 않는다.
 * - 기본 7개 항목의 필드명도 같이 본다. API 별 마스킹이 이미 null 로 만든 값은 다시 null 이 될 뿐이라 결과가 같다.
 * - 키 이름이 같으면 깊이와 상관없이 가린다(대소문자 구분). 값이 배열·객체여도 통째로 null.
 * - 가릴 키가 응답에 **있을 때만** `masked` 에 넣는다(기존 API 별 규칙과 같은 뜻 — "이번 응답에서 가린 항목").
 * - 권한·항목 변경 반영: 권한은 다음 요청부터(principal 을 요청마다 읽는다), 항목·필드명은 API 로 바꾸면 즉시,
 *   DB 를 직접 고쳤다면 [DataFieldService.CACHE_TTL_MS](60초) 안에 반영된다. 다시 로그인할 필요가 없다.
 *
 * ## 비용
 * 조회자에게 가릴 필드명이 하나도 없으면(통합관리자 · 전 항목 허용) 아무것도 하지 않는다. 있으면 `data` 를 Jackson 트리로
 * 한 번 바꿔 훑는다 — 직렬화를 한 번 더 하는 정도다. 가릴 키가 실제로 없으면 원래 객체를 그대로 내보낸다.
 *
 * ## 범위 밖
 * 엑셀·CSV 같은 파일 응답(`ResponseEntity<ByteArray>` 등)은 [ApiResponse] 가 아니라 이 advice 를 타지 않는다.
 */
@RestControllerAdvice
class DataFieldMaskingAdvice(
    private val dataFieldService: DataFieldService,
    private val objectMapper: ObjectMapper
) : ResponseBodyAdvice<Any> {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun supports(returnType: MethodParameter, converterType: Class<out HttpMessageConverter<*>>): Boolean =
        AbstractJackson2HttpMessageConverter::class.java.isAssignableFrom(converterType)

    override fun beforeBodyWrite(
        body: Any?,
        returnType: MethodParameter,
        selectedContentType: MediaType,
        selectedConverterType: Class<out HttpMessageConverter<*>>,
        request: ServerHttpRequest,
        response: ServerHttpResponse
    ): Any? {
        if (body !is ApiResponse<*> || body.data == null) return body
        val principal = UserContext.currentOrNull() ?: return body
        if (principal.superAdmin) return body

        val blind = blindAttrs(dataFieldService.attrFieldMap()) { principal.canReadField(it) }
        if (blind.isEmpty()) return body

        val tree: JsonNode = objectMapper.valueToTree(body.data)
        val hit = maskTree(tree, blind)
        if (hit.isEmpty()) return body

        log.debug("응답 필드명 마스킹 uri={} user={} fields={}", request.uri.path, principal.userId, hit)
        @Suppress("UNCHECKED_CAST")
        return (body as ApiResponse<Any?>).copy(
            data = tree,
            masked = ((body.masked ?: emptyList()) + hit).distinct().sorted()
        )
    }

    companion object {

        /** 「응답 필드명 → 항목 key」 중 조회자가 열람할 수 없는 것만 */
        fun blindAttrs(attrFieldMap: Map<String, String>, canRead: (String) -> Boolean): Map<String, String> {
            val readable = HashMap<String, Boolean>()
            return attrFieldMap.filterValues { key -> !readable.getOrPut(key) { canRead(key) } }
        }

        /**
         * 트리를 훑어 [blind] 에 있는 키의 값을 null 로 바꾼다. 가린 키의 하위는 내려가지 않는다.
         * 재귀 대신 스택을 써서 깊은 트리에서도 스택이 넘치지 않는다.
         *
         * @return 가린 필드명이 속한 항목 key (응답에 키가 있었던 것만)
         */
        fun maskTree(root: JsonNode, blind: Map<String, String>): Set<String> {
            val hit = linkedSetOf<String>()
            val stack = ArrayDeque<JsonNode>().apply { add(root) }
            while (stack.isNotEmpty()) {
                when (val node = stack.removeLast()) {
                    is ObjectNode -> {
                        val names = node.fieldNames().asSequence().toList()
                        for (name in names) {
                            val fieldKey = blind[name]
                            if (fieldKey != null) {
                                hit.add(fieldKey)
                                if (!node.get(name).isNull) node.set<JsonNode>(name, NullNode.instance)
                            } else {
                                val child = node.get(name)
                                if (child.isContainerNode) stack.add(child)
                            }
                        }
                    }
                    is ArrayNode -> node.forEach { if (it.isContainerNode) stack.add(it) }
                    else -> Unit
                }
            }
            return hit
        }
    }
}
