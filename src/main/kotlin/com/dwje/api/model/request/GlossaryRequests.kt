package com.dwje.api.model.request

/**
 * 공식 용어 등록·수정 요청 — POST /api/v1/glossary/terms · PUT /api/v1/glossary/terms/{termId}
 *
 * `Map<String, String?>` 대신 타입으로 받는다. Map 으로 받으면 서버가 아는 키만 읽고
 * 나머지는 조용히 버려서, 키 이름을 잘못 보내도 200 이 나가고 값은 반영되지 않는다.
 * 사용자가 직접 입력하는 폼이라 오타 경로가 실제로 있다.
 *
 * @param term         공식 용어
 * @param definition   용어 정의
 * @param customerInfo 고객사 정보 — true 면 고객사 데이터 권한(customer)이 없는 열람자에게 「비공개 용어」 로 가린다(R-18, V75).
 *                     등록에서 생략하면 false, 수정에서 생략하면 지금 값을 그대로 둔다.
 * @param domainCd     분류 — V75 로 분류를 없앴다. 옛 화면이 보내도 400 이 나지 않게 받기만 하고 버린다
 */
data class GlossaryTermRequest(
    val term: String? = null,
    val definition: String? = null,
    val customerInfo: Boolean? = null,
    @Deprecated("V75 로 분류를 없앰 — 받고 버린다")
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
 * 용어 사전 내려받기 — POST /api/v1/glossary/terms/export (07 GLS-12 · 13 GLV-05)
 *
 * @param keyword     검색어 (scopeCd=ALL 이면 무시)
 * @param domainCd    분류 — V75 로 없앰. 받고 버린다
 * @param mineOnly    내가 등록한 유사어가 있는 용어만 — 용어 사전 관리 화면에서만 뜻이 있다
 * @param menuId      부르는 화면 — sys-gloss | gloss-view (그 화면 권한이 없으면 가진 화면으로 기록)
 * @param format      xlsx 만 (생략 가능)
 * @param scopeCd     VIEW | ALL — 공통 10.6 의 API 본문 이름
 * @param scope       화면 기획서가 쓰는 같은 값의 이름 — scopeCd 가 없을 때 읽는다
 * @param condSummary 조회 조건 요약 (다운로드 이력에 남긴다, 500자)
 */
data class GlossaryExportRequest(
    val keyword: String? = null,
    @Deprecated("V75 로 분류를 없앰 — 받고 버린다")
    val domainCd: String? = null,
    val mineOnly: Boolean? = null,
    val menuId: String? = null,
    val format: String? = null,
    val scopeCd: String? = null,
    val scope: String? = null,
    @field:jakarta.validation.constraints.Size(max = 500, message = "조회 조건은 500자 이내여야 합니다.")
    val condSummary: String? = null
)
