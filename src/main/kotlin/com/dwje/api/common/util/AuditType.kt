package com.dwje.api.common.util

/**
 * 감사 유형 공통코드 `LOG_AUDIT_TYPE` (09 AUD-09, V58)
 *
 * 호출처는 문자열 대신 이 상수를 쓴다. 화면마다 새 유형을 만들지 않는다(공통 9장).
 */
object AuditType {
    /** 응답 값 가림 */
    const val MASK = "MASK"
    /** (제거됨) 원본 열람 요청 — 과거 행 조회용으로만 남긴다 */
    const val UNMASK_REQ = "UNMASK_REQ"
    /** 남의 원본 기록 열람(질의 이력 등) */
    const val RAW_VIEW = "RAW_VIEW"
    /** 계정·부서·메뉴·데이터 권한 변경 */
    const val PERM_CHANGE = "PERM_CHANGE"
    /** 로그인·로그아웃 (로그인 이력 표에서 온다) */
    const val LOGIN = "LOGIN"
    /** 사람이 아닌 배치·자동 처리 */
    const val AUTO_GEN = "AUTO_GEN"
    /** 내려받기·인쇄 */
    const val EXPORT = "EXPORT"
    /** 알림·지표·연동·모델 등 운영 설정 변경 */
    const val CONFIG_CHANGE = "CONFIG_CHANGE"
    /** 비밀번호·잠금·가입 승인·계정 전환 */
    const val ACCOUNT_SEC = "ACCOUNT_SEC"
    /** 화면·데이터·쓰기 권한 거부 */
    const val ACCESS_DENIED = "ACCESS_DENIED"
    /** 감사·다운로드 기록 열람 */
    const val AUDIT_VIEW = "AUDIT_VIEW"

    val ALL: Set<String> = linkedSetOf(
        MASK, UNMASK_REQ, RAW_VIEW, PERM_CHANGE, LOGIN, AUTO_GEN, EXPORT, CONFIG_CHANGE, ACCOUNT_SEC, ACCESS_DENIED, AUDIT_VIEW
    )
}

/** 감사 결과 공통코드 `LOG_AUDIT_RESULT` (09 AUD-09) */
object AuditResult {
    const val ALLOW = "ALLOW"
    const val BLIND = "BLIND"
    const val REJECT = "REJECT"
    const val MASKED = "MASKED"

    val ALL: Set<String> = linkedSetOf(ALLOW, BLIND, REJECT, MASKED)
}
