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

/**
 * 제품군 순위 변경 요청 — PUT /api/v1/products/families/order
 *
 * `Map<String, Any?>` 대신 타입으로 받는다. Map 으로 받으면 바깥 키(`orders`)뿐 아니라
 * **안쪽 항목까지 조용히 버려진다** — 키 이름이나 타입이 어긋난 항목만 빠지고 200 이 나가서,
 * 10개를 보냈는데 3개만 반영되고도 화면은 성공으로 읽는다.
 *
 * @param orders 제품군별 순위. 비어 있으면 400
 */
data class FamilyOrderRequest(
    val orders: List<FamilyOrderEntry> = emptyList()
)

/**
 * 제품군 순위 항목
 *
 * @param familyCd 제품군 코드
 * @param rank     순위 (1-base)
 */
data class FamilyOrderEntry(
    val familyCd: String? = null,
    val rank: Int? = null
)

/**
 * 제품군 내 제품 순서 변경 요청 — PUT /api/v1/products/families/{familyCd}/products/order
 *
 * @param orders 제품별 순서. 비어 있으면 400
 */
data class ProductOrderRequest(
    val orders: List<ProductOrderEntry> = emptyList()
)

/**
 * 제품 순서 항목
 *
 * @param code 제품 모델 코드
 * @param seq  제품군 내 순서 (1-base)
 */
data class ProductOrderEntry(
    val code: String? = null,
    val seq: Int? = null
)
