package com.dwje.api.model.request

/**
 * 공식 용어 등록·수정 요청 — POST /api/v1/glossary/terms · PUT /api/v1/glossary/terms/{termId}
 *
 * `Map<String, String?>` 대신 타입으로 받는다. Map 으로 받으면 서버가 아는 키만 읽고
 * 나머지는 조용히 버려서, 키 이름을 잘못 보내도 200 이 나가고 값은 반영되지 않는다.
 * 사용자가 직접 입력하는 폼이라 오타 경로가 실제로 있다.
 *
 * @param term       공식 용어
 * @param definition 용어 정의
 * @param domainCd   분류명 — GET /api/v1/glossary/domains 의 code
 */
data class GlossaryTermRequest(
    val term: String? = null,
    val definition: String? = null,
    val domainCd: String? = null
)

/**
 * 유사어 등록·수정 요청
 * — POST /api/v1/glossary/terms/{termId}/variants · PUT /api/v1/glossary/variants/{variantId}
 *
 * @param word 현장에서 쓰는 유사어
 */
data class GlossaryVariantRequest(
    val word: String? = null
)
