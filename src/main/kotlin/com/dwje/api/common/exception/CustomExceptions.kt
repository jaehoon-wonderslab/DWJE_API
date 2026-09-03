package com.dwje.api.common.exception

import com.dwje.api.common.response.ErrorCode

/**
 * 업무 예외 최상위 클래스
 *
 * 모든 업무 예외는 [ErrorCode] 를 보유하며 GlobalExceptionHandler 가 이를 HTTP 상태로 변환한다.
 *
 * @param errorCode 매핑 에러 코드
 * @param message   사용자에게 노출할 메시지
 * @param field     오류 유발 필드명 (선택)
 */
open class BusinessException(
    val errorCode: ErrorCode,
    override val message: String = errorCode.defaultMessage,
    val field: String? = null
) : RuntimeException(message)

/** E-AUTH-001 : 미인증 · 세션 만료 */
class UnauthenticatedException(message: String = ErrorCode.AUTH_UNAUTHENTICATED.defaultMessage) :
    BusinessException(ErrorCode.AUTH_UNAUTHENTICATED, message)

/** E-AUTH-002 : 메뉴 접근 권한 없음 */
class MenuAccessDeniedException(menuId: String) :
    BusinessException(ErrorCode.AUTH_MENU_DENIED, "화면 접근 권한이 없습니다. [$menuId]")

/** E-AUTH-003 : 데이터 접근 권한 없음 */
class DataAccessDeniedException(fieldKey: String) :
    BusinessException(ErrorCode.AUTH_DATA_DENIED, "데이터 접근 권한이 없습니다. [$fieldKey]", fieldKey)

/** E-VALID-001 : 필수 항목 누락 · 잘못된 파라미터 */
class InvalidParameterException(message: String, field: String? = null) :
    BusinessException(ErrorCode.VALID_REQUIRED, message, field)

/** E-VALID-002 : 중복 값 */
class DuplicatedValueException(message: String, field: String? = null) :
    BusinessException(ErrorCode.VALID_DUPLICATED, message, field)

/** E-RULE-001 : 업무 규칙 위반 */
class BusinessRuleException(message: String) :
    BusinessException(ErrorCode.RULE_VIOLATION, message)

/** E-NOTFOUND : 대상 없음 */
class ResourceNotFoundException(message: String = ErrorCode.NOT_FOUND.defaultMessage) :
    BusinessException(ErrorCode.NOT_FOUND, message)

/** E-SERVER : 시스템 오류 */
class SystemErrorException(message: String = ErrorCode.SERVER_ERROR.defaultMessage) :
    BusinessException(ErrorCode.SERVER_ERROR, message)
