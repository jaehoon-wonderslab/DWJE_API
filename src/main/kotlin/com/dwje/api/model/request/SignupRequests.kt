package com.dwje.api.model.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

/**
 * 회원가입 요청 — POST /api/v1/auth/signup
 *
 * 사내 시스템이므로 가입 즉시 사용할 수 없고, 전산팀 승인(PENDING → ACTIVE) 후 로그인된다.
 *
 * @param empNo           사번 (로그인 ID)
 * @param name            이름
 * @param deptId          소속 부서 ID
 * @param pos             직급 코드 (SYS_POSITION). 미지정 시 STAFF
 * @param password        비밀번호
 * @param passwordConfirm 비밀번호 확인
 */
data class SignupRequest(
    @field:NotBlank(message = "사번을 입력해 주세요.")
    @field:Pattern(
        regexp = "^[A-Za-z0-9_-]{4,30}$",
        message = "사번은 영문·숫자·하이픈·밑줄 4~30자로 입력해 주세요."
    )
    val empNo: String,

    @field:NotBlank(message = "이름을 입력해 주세요.")
    @field:Size(max = 50, message = "이름은 50자 이내로 입력해 주세요.")
    val name: String,

    val deptId: Int? = null,
    val pos: String? = null,

    @field:NotBlank(message = "이메일을 입력해 주세요.")
    @field:Size(max = 200, message = "이메일은 200자 이내로 입력해 주세요.")
    val email: String,

    @field:NotBlank(message = "이메일 인증을 먼저 완료해 주세요.")
    val verificationToken: String,

    @field:NotBlank(message = "비밀번호를 입력해 주세요.")
    val password: String,

    @field:NotBlank(message = "비밀번호 확인을 입력해 주세요.")
    val passwordConfirm: String
)

/**
 * 비밀번호 변경 요청 — POST /api/v1/auth/password
 *
 * @param currentPassword 현재 비밀번호
 * @param newPassword     새 비밀번호
 * @param newPasswordConfirm 새 비밀번호 확인
 */
data class PasswordChangeRequest(
    @field:NotBlank(message = "현재 비밀번호를 입력해 주세요.")
    val currentPassword: String,

    @field:NotBlank(message = "새 비밀번호를 입력해 주세요.")
    val newPassword: String,

    @field:NotBlank(message = "새 비밀번호 확인을 입력해 주세요.")
    val newPasswordConfirm: String
)

/**
 * 가입 승인·반려 요청 — POST /api/v1/system/users/{empNo}/approve
 *
 * @param approve true = 승인(ACTIVE), false = 반려(SUSPENDED)
 * @param reason  반려 사유
 */
data class SignupApprovalRequest(
    val approve: Boolean = true,
    val reason: String? = null
)

/**
 * 이메일 인증 코드 발송 요청 — POST /api/v1/auth/email/send-code
 *
 * @param email   수신 이메일
 * @param purpose 인증 목적 — SIGNUP | PASSWORD_RESET
 */
data class EmailCodeSendRequest(
    @field:NotBlank(message = "이메일을 입력해 주세요.")
    val email: String,

    @field:NotBlank(message = "인증 목적을 지정해 주세요.")
    val purpose: String = "SIGNUP"
)

/**
 * 이메일 인증 코드 검증 요청 — POST /api/v1/auth/email/verify-code
 *
 * @param email   인증 코드를 받은 이메일
 * @param purpose 인증 목적
 * @param code    입력한 인증 코드
 */
data class EmailCodeVerifyRequest(
    @field:NotBlank(message = "이메일을 입력해 주세요.")
    val email: String,

    @field:NotBlank(message = "인증 목적을 지정해 주세요.")
    val purpose: String = "SIGNUP",

    @field:NotBlank(message = "인증 코드를 입력해 주세요.")
    val code: String
)

/**
 * 비밀번호 찾기 시작 요청 — POST /api/v1/auth/password/forgot
 *
 * 사번과 등록된 이메일이 모두 일치할 때만 인증 코드를 보낸다.
 * 계정 존재 여부를 알려 주지 않기 위해 응답은 항상 같다.
 *
 * @param empNo 사번
 * @param email 계정에 등록된 이메일
 */
data class PasswordForgotRequest(
    @field:NotBlank(message = "사번을 입력해 주세요.")
    val empNo: String,

    @field:NotBlank(message = "이메일을 입력해 주세요.")
    val email: String
)

/**
 * 비밀번호 재설정 요청 — POST /api/v1/auth/password/reset
 *
 * @param verificationToken  이메일 인증으로 발급받은 1회용 토큰
 * @param newPassword        새 비밀번호
 * @param newPasswordConfirm 새 비밀번호 확인
 */
data class PasswordResetRequest(
    @field:NotBlank(message = "이메일 인증을 먼저 완료해 주세요.")
    val verificationToken: String,

    @field:NotBlank(message = "새 비밀번호를 입력해 주세요.")
    val newPassword: String,

    @field:NotBlank(message = "새 비밀번호 확인을 입력해 주세요.")
    val newPasswordConfirm: String
)
