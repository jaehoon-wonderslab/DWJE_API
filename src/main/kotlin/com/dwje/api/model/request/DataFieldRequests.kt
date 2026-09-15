package com.dwje.api.model.request

/**
 * 데이터 접근 항목 등록·수정 요청 — POST /api/v1/system/data-fields · PUT /api/v1/system/data-fields/{fieldKey}
 *
 * 값 검증은 서비스가 한다(키 형식 · 이름 길이 · 분류 코드). 수정(PUT)에서는 fieldKey 를 무시한다 — 경로가 기준이다.
 *
 * @param fieldKey 항목 key — 소문자로 시작, 소문자·숫자·`_`·`-`, 2~30자. 등록 뒤 바꿀 수 없다(권한·필드명·응답 masked 배열이 이 값을 쓴다)
 * @param name     항목명 (50자 이내)
 * @param desc     설명 (300자 이내, 선택)
 * @param category 분류 코드 — 공통코드 DATA_FIELD_CATEGORY (선택)
 */
data class DataFieldSaveRequest(
    val fieldKey: String? = null,
    val name: String? = null,
    val desc: String? = null,
    val category: String? = null
)

/**
 * 응답 필드명 등록 요청 — POST /api/v1/system/data-fields/{fieldKey}/attrs
 *
 * @param attrName API 응답 JSON 필드명 (unitPrice · yieldRate). 대소문자를 구분하고 전역에서 하나의 항목에만 붙는다
 * @param remark   어느 화면·API 의 값인지 메모 (선택, 200자 이내)
 */
data class DataFieldAttrRequest(
    val attrName: String? = null,
    val remark: String? = null
)

/**
 * 적용 스위치 요청 — PATCH /api/v1/system/data-fields/{fieldKey}/apply
 *
 * @param on true = 적용(마스킹이 걸린다) · false = 미적용. 반영은 재로그인 때
 */
data class DataFieldApplyRequest(
    val on: Boolean = true
)
