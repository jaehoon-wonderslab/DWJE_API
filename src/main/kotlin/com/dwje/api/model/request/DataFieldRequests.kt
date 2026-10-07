package com.dwje.api.model.request

/**
 * 데이터 접근 항목 등록·수정 요청 — POST /api/v1/system/data-fields · PUT /api/v1/system/data-fields/{fieldKey}
 *
 * 값 검증은 서비스가 한다(키 형식 · 이름 길이). 수정(PUT)에서는 fieldKey 를 무시한다 — 경로가 기준이다.
 *
 * @param fieldKey 항목 key — 소문자로 시작, 소문자·숫자·`_`·`-`, 2~30자. 등록 뒤 바꿀 수 없다(권한·필드명·응답 masked 배열이 이 값을 쓴다)
 * @param name     항목명 (50자 이내)
 * @param desc     설명 (300자 이내, 선택)
 * @param category 사용 중지(2026-10-07) — 분류는 판정·관리에 쓰이지 않아 없앴다. 옛 화면이 보내도 400 없이 버린다
 */
data class DataFieldSaveRequest(
    val fieldKey: String? = null,
    val name: String? = null,
    val desc: String? = null,
    @Deprecated("데이터 항목 분류 제거(2026-10-07) — 옛 WEB 번들 호환으로 받기만 하고 값은 무시. 모든 WEB 배포 뒤 삭제")
    @field:io.swagger.v3.oas.annotations.media.Schema(deprecated = true, description = "사용 중지 — 보내도 무시한다(데이터 항목 분류 제거, 2026-10-07)")
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
 * @param on true = 적용(마스킹이 걸린다) · false = 미적용. 서버 응답에는 다음 조회부터, 다른 사용자 화면 표시는 그 화면을 다시 열 때
 */
data class DataFieldApplyRequest(
    val on: Boolean = true
)

/**
 * 화면 열 매핑 일괄 저장 — PUT /api/v1/system/data-fields/mapping (04 DTP-02)
 *
 * 새 종류 만들기 · 열(응답 필드명) 옮기기 · 풀기 · 적용 켜기를 **한 트랜잭션**으로 한다. 하나라도 틀리면 아무것도 바뀌지 않는다.
 *
 * @param newFields 이번에 새로 만들 종류
 * @param moves     열마다 옮길 종류. `toFieldKey=null` 이면 그 열을 어느 종류에서도 뺀다
 * @param screenId  매핑을 고친 화면 ID — 이력 문구용
 */
data class DataFieldMappingRequest(
    val newFields: List<NewField>? = null,
    val moves: List<Move>? = null,
    val screenId: String? = null
) {
    /**
     * @param grantAllDepts true(기본) 면 통합관리자·미배정을 뺀 사용 중 부서 전부에 열람을 허용한 채 만든다
     * @param apply         true 면 만들면서 적용을 켠다(기본 false — 명시한 것만 켠다)
     */
    data class NewField(
        val fieldKey: String? = null,
        val name: String? = null,
        val desc: String? = null,
        @Deprecated("데이터 항목 분류 제거(2026-10-07) — 옛 WEB 번들 호환으로 받기만 하고 값은 무시. 모든 WEB 배포 뒤 삭제")
        @field:io.swagger.v3.oas.annotations.media.Schema(deprecated = true, description = "사용 중지 — 보내도 무시한다(데이터 항목 분류 제거, 2026-10-07)")
        val category: String? = null,
        val grantAllDepts: Boolean = true,
        val apply: Boolean = false
    )

    /** @param remark 어느 화면·열인지 메모(200자 이내). 형식 권장 「{화면명} · {열 이름}」 */
    data class Move(
        val attrName: String? = null,
        val toFieldKey: String? = null,
        val remark: String? = null
    )
}

/**
 * 항목별 부서 열람 저장 — `PUT /api/v1/system/data-fields/item-perms` (V82, 2026-10-07)
 *
 * 화면의 한 줄(항목) = 같은 뜻의 응답 필드명 여러 개. 이 필드명들을 항목 하나로 모으고 부서별 열람을 정한다.
 *
 * @param name  항목 이름(화면에 보이는 이름, 50자 이내) — 새 항목을 만들 때 이름으로 쓴다
 * @param attrs 이 항목의 응답 필드명(1~100개, JSON 키 꼴, 예약어 불가)
 * @param perms 부서 ID(문자열) → 열람 허용 여부. 넣은 부서만 바꾼다. 통합관리자 · 미배정 부서는 넣을 수 없다(409)
 */
data class DataItemPermRequest(
    val name: String? = null,
    val attrs: List<String>? = null,
    val perms: Map<String, Boolean>? = null
)
