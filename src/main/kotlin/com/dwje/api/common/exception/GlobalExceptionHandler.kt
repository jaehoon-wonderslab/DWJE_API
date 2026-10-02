package com.dwje.api.common.exception

import com.dwje.api.common.response.ApiResponse
import com.dwje.api.common.response.ErrorCode
import com.dwje.api.common.security.UserContext
import com.dwje.api.common.util.AuditResult
import com.dwje.api.common.util.AuditType
import com.dwje.api.service.AuditLogService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import java.util.concurrent.ConcurrentHashMap
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.validation.BindException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException

/**
 * 전역 예외 처리기
 *
 * 컨트롤러/서비스/리포지토리에서 발생한 모든 예외를 표준 [ApiResponse] 포맷으로 변환한다.
 * - 업무 예외(BusinessException) : WARN 레벨 로그 (원인 파악 가능 수준)
 * - 시스템 예외(Exception)       : ERROR 레벨 + StackTrace 상세 기록
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 접근 거부 감사 기록기 — 단위 시험처럼 빈이 없으면 기록하지 않는다 */
    @Autowired(required = false)
    var auditLogService: AuditLogService? = null

    /** 같은 사람·같은 거부를 이 시간(초) 안에 다시 남기지 않는다 (09 AUD-10) */
    @Value("\${app.audit-access-denied-dedup-sec:60}")
    var accessDeniedDedupSec: Long = 60

    private val deniedSeen = ConcurrentHashMap<String, Long>()

    /**
     * 업무 예외 처리 — 정의된 [ErrorCode] 의 HTTP 상태로 응답한다.
     */
    @ExceptionHandler(BusinessException::class)
    fun handleBusiness(e: BusinessException, request: HttpServletRequest): ResponseEntity<ApiResponse<Any?>> {
        // 업무 예외는 스택트레이스 없이 원인만 기록한다.
        log.warn("비즈니스 처리 중 예외 발생 [{} {}] {} : {}", request.method, request.requestURI, e.errorCode.code, e.message)
        recordAccessDenied(e, request)
        // data 는 잠금 안내처럼 예외가 직접 실은 값만 내려 준다(없으면 기존 실패 응답과 같은 모양).
        return ResponseEntity.status(e.errorCode.status)
            .body(ApiResponse.errorWithData(e.errorCode.code, e.message, e.field, e.data))
    }

    /**
     * 화면·데이터·쓰기 권한 거부를 감사 로그(ACCESS_DENIED · REJECT)에 남긴다 (09 AUD-10).
     * 같은 사람·코드·대상은 [accessDeniedDedupSec] 초 안에 한 번만 — 화면이 같은 API 를 되풀이해 불러도 행이 쌓이지 않게.
     */
    internal fun recordAccessDenied(e: BusinessException, request: HttpServletRequest, now: Long = System.currentTimeMillis()) {
        val audit = auditLogService ?: return
        val (menuId, fieldKey) = when (e) {
            is MenuAccessDeniedException -> e.menuId to null
            is WriteAccessDeniedException -> e.menuId to null
            is DataAccessDeniedException -> null to e.fieldKey
            else -> return
        }
        val user = UserContext.currentOrNull()?.userId ?: "-"
        val key = "$user|${e.errorCode.code}|${menuId ?: fieldKey}"
        val last = deniedSeen[key]
        if (last != null && now - last < accessDeniedDedupSec * 1000) return
        deniedSeen[key] = now
        if (deniedSeen.size > 10_000) deniedSeen.entries.removeIf { now - it.value >= accessDeniedDedupSec * 1000 }
        audit.record(
            logType = AuditType.ACCESS_DENIED, menuId = menuId, fieldKey = fieldKey,
            targetDesc = "${request.method} ${request.requestURI}".take(300), resultCd = AuditResult.REJECT, remark = e.errorCode.code
        )
    }

    /**
     * @Valid 검증 실패 — 첫 번째 위반 필드를 안내한다.
     */
    @ExceptionHandler(MethodArgumentNotValidException::class, BindException::class)
    fun handleValidation(e: BindException, request: HttpServletRequest): ResponseEntity<ApiResponse<Nothing?>> {
        val fieldError = e.bindingResult.fieldErrors.firstOrNull()
        val field = fieldError?.field
        val message = fieldError?.defaultMessage ?: ErrorCode.VALID_REQUIRED.defaultMessage
        log.warn("요청 파라미터 검증 실패 [{} {}] field={} msg={}", request.method, request.requestURI, field, message)
        return ResponseEntity.status(ErrorCode.VALID_REQUIRED.status)
            .body(ApiResponse.error(ErrorCode.VALID_REQUIRED.code, message, field))
    }

    /**
     * 필수 쿼리 파라미터 누락
     */
    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun handleMissingParam(e: MissingServletRequestParameterException): ResponseEntity<ApiResponse<Nothing?>> {
        val message = "필수 파라미터가 누락되었습니다. [${e.parameterName}]"
        log.warn(message)
        return ResponseEntity.status(ErrorCode.VALID_REQUIRED.status)
            .body(ApiResponse.error(ErrorCode.VALID_REQUIRED.code, message, e.parameterName))
    }

    /**
     * 파라미터 타입 불일치 · 본문 파싱 실패
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class, HttpMessageNotReadableException::class)
    fun handleTypeMismatch(e: Exception): ResponseEntity<ApiResponse<Nothing?>> {
        val unknown = e.cause as? UnrecognizedPropertyException
        val field = (e as? MethodArgumentTypeMismatchException)?.name
            ?: unknown?.propertyName
            ?: jsonFieldOf(e)

        // 모르는 항목과 형식 오류는 원인이 달라 안내도 달라야 한다.
        // 모르는 항목이면 받는 키 목록까지 알려 준다 — 이름을 잘못 보낸 쪽이 바로 고칠 수 있다.
        val message = when {
            unknown != null -> {
                val allowed = unknown.knownPropertyIds.orEmpty().joinToString(" · ") { it.toString() }
                "요청에 없는 항목입니다. [$field]" + if (allowed.isNotBlank()) " 받는 항목은 $allowed 입니다." else ""
            }
            field != null -> "파라미터 형식이 올바르지 않습니다. [$field]"
            else -> "요청 본문 형식이 올바르지 않습니다."
        }
        log.warn("{} : {}", message, e.message)
        return ResponseEntity.status(ErrorCode.VALID_REQUIRED.status)
            .body(ApiResponse.error(ErrorCode.VALID_REQUIRED.code, message, field))
    }

    /**
     * 본문 역직렬화 실패의 원인 필드명을 뽑는다.
     *
     * Jackson 은 어느 필드에서 걸렸는지를 [MismatchedInputException.path] 에 담아 준다.
     * 이 값을 `error.field` 로 올려 주지 않으면 "요청 본문 형식이 올바르지 않습니다" 통짜만 남아,
     * 어느 값이 문제인지 호출부가 알 수 없다. (다른 검증 오류는 전부 필드를 짚어 준다)
     *
     * @return 가장 안쪽 필드명. 알 수 없으면 null
     */
    private fun jsonFieldOf(e: Exception): String? =
        (e.cause as? MismatchedInputException)
            ?.path
            ?.mapNotNull { it.fieldName }
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(".")

    /**
     * 존재하지 않는 경로 · 미지원 메서드
     */
    @ExceptionHandler(NoResourceFoundException::class, HttpRequestMethodNotSupportedException::class)
    fun handleNotFound(e: Exception): ResponseEntity<ApiResponse<Nothing?>> {
        log.warn("요청 대상 없음 : {}", e.message)
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ApiResponse.error(ErrorCode.NOT_FOUND.code, ErrorCode.NOT_FOUND.defaultMessage))
    }

    /**
     * 그 외 모든 시스템 예외 — StackTrace 를 포함해 ERROR 로 기록한다.
     */
    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception, request: HttpServletRequest): ResponseEntity<ApiResponse<Nothing?>> {
        if (request.requestURI == "/api/v1/ai/chat/ask") {
            log.error("AI 질의 처리 오류 [URI: {} {}] exceptionType={}", request.method, request.requestURI, e.javaClass.simpleName)
        } else {
            log.error("예상치 못한 시스템 오류 발생 [URI: {} {}]", request.method, request.requestURI, e)
        }
        return ResponseEntity.status(ErrorCode.SERVER_ERROR.status)
            .body(ApiResponse.error(ErrorCode.SERVER_ERROR.code, ErrorCode.SERVER_ERROR.defaultMessage))
    }
}
