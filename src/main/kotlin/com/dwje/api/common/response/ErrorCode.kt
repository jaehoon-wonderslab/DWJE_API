package com.dwje.api.common.response

import org.springframework.http.HttpStatus

/**
 * API 목록 명세 「공통 규약 / 2. 에러 코드」 정의
 *
 * @param code   비즈니스 에러 코드
 * @param status 매핑되는 HTTP 상태 코드
 * @param defaultMessage 기본 메시지
 */
enum class ErrorCode(
    val code: String,
    val status: HttpStatus,
    val defaultMessage: String
) {
    /** 미인증 · 세션 만료 */
    AUTH_UNAUTHENTICATED("E-AUTH-001", HttpStatus.UNAUTHORIZED, "인증이 필요합니다. 다시 로그인해 주세요."),

    /** 메뉴 접근 권한 없음 */
    AUTH_MENU_DENIED("E-AUTH-002", HttpStatus.FORBIDDEN, "해당 화면에 접근할 권한이 없습니다."),

    /** 데이터 접근 권한 없음 */
    AUTH_DATA_DENIED("E-AUTH-003", HttpStatus.FORBIDDEN, "해당 데이터 항목을 조회할 권한이 없습니다."),

    /** 필수 항목 누락 */
    VALID_REQUIRED("E-VALID-001", HttpStatus.BAD_REQUEST, "필수 항목이 누락되었습니다."),

    /** 중복 값 (아이디 · 부서명 · 용어 등) */
    VALID_DUPLICATED("E-VALID-002", HttpStatus.BAD_REQUEST, "이미 등록된 값입니다."),

    /** 업무 규칙 위반 */
    RULE_VIOLATION("E-RULE-001", HttpStatus.CONFLICT, "업무 규칙에 위배되어 처리할 수 없습니다."),

    /** 대상 없음 */
    NOT_FOUND("E-NOTFOUND", HttpStatus.NOT_FOUND, "요청하신 대상을 찾을 수 없습니다."),

    /** 서버 오류 */
    SERVER_ERROR("E-SERVER", HttpStatus.INTERNAL_SERVER_ERROR, "시스템 오류가 발생했습니다. 관리자에게 문의하세요."),

    /** 외부 원천(MSSQL EDGE 등) 조회 실패 — 우리 쪽 오류가 아니라 화면이 "원천 응답 없음" 으로 안내한다 */
    SOURCE_UNAVAILABLE("E-SOURCE-001", HttpStatus.SERVICE_UNAVAILABLE, "원천 데이터 조회에 실패했습니다. 잠시 뒤 다시 시도해 주세요."),

    /** 외부 원천 조회 시간 초과 — 기간을 줄이면 된다 */
    SOURCE_TIMEOUT("E-SOURCE-002", HttpStatus.GATEWAY_TIMEOUT, "원천 데이터 조회가 제한 시간을 넘었습니다. 조회 기간을 줄여 다시 시도해 주세요."),

    /** 사내 LLM 서버에 닿지 못했거나 오류로 답했다 (`/api/ai/chat`) */
    LLM_UNAVAILABLE("E-LLM-001", HttpStatus.BAD_GATEWAY, "AI 서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요."),

    /** 사내 LLM 게이트웨이가 요청을 거부함 — 인증 정책을 포함한 게이트웨이 설정 확인 필요 */
    LLM_UPSTREAM_REJECTED("E-LLM-004", HttpStatus.BAD_GATEWAY, "AI 서버가 요청을 거부했습니다. 관리자에게 문의해 주세요."),

    /** 사내 LLM 서버가 제한 시간 안에 답하지 않았다 */
    LLM_TIMEOUT("E-LLM-002", HttpStatus.GATEWAY_TIMEOUT, "AI 서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요."),

    /** 분당 요청 수 초과 — LLM 서버가 동시에 한 건만 처리한다 */
    LLM_RATE_LIMITED("E-LLM-003", HttpStatus.TOO_MANY_REQUESTS, "요청이 많습니다. 잠시 후 다시 시도해 주세요.")
}
