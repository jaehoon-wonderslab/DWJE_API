package com.dwje.api.controller

import com.dwje.api.common.response.ApiResponse
import com.dwje.api.model.request.EmailCodeSendRequest
import com.dwje.api.model.request.EmailCodeVerifyRequest
import com.dwje.api.model.request.LoginRequest
import com.dwje.api.model.request.PasswordChangeRequest
import com.dwje.api.model.request.PasswordForgotRequest
import com.dwje.api.model.request.PasswordResetRequest
import com.dwje.api.model.request.RefreshTokenRequest
import com.dwje.api.model.request.SignupRequest
import com.dwje.api.model.request.SwitchAccountRequest
import com.dwje.api.model.response.LoginResponse
import com.dwje.api.model.response.MenuTreeResponse
import com.dwje.api.model.response.MyInfoResponse
import com.dwje.api.model.response.RefreshTokenResponse
import com.dwje.api.service.AuthService
import com.dwje.api.service.EmailVerificationService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 사용자 인증 관련 API 컨트롤러 (CM-01 ~ CM-03)
 *
 * 로그인, 로그아웃, 토큰 재발급, 내 정보·권한 조회, 계정 전환 요청을 처리한다.
 * 로그인/토큰 갱신은 화이트리스트 경로로 인증 없이 호출된다.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "01. 인증·공통")
class AuthController(
    private val authService: AuthService,
    private val emailVerificationService: EmailVerificationService
) {

    /**
     * 사용자 아이디/비밀번호 기반 로그인 처리 (No.1)
     *
     * 로그인 성공/실패를 모두 접속 이력(ax.tb_sys_login_hist)에 기록한다.
     *
     * @param request     로그인 요청 정보 (사번, 비밀번호)
     * @param httpRequest 접속 IP · User-Agent 추출용 원본 요청
     * @return 인증 토큰(Access/Refresh Token) 및 사용자 기본 정보
     */
    @Operation(summary = "로그인", description = "사번/비밀번호로 인증하고 accessToken · refreshToken 을 발급한다.")
    @PostMapping("/login")
    fun login(
        @Valid @RequestBody request: LoginRequest,
        httpRequest: HttpServletRequest
    ): ApiResponse<LoginResponse> {
        // 1. 접속 정보 추출 — 프록시 경유 시 X-Forwarded-For 우선
        val ipAddr = clientIp(httpRequest)
        val userAgent = httpRequest.getHeader("User-Agent")

        // 2. 인증 서비스 실행 및 토큰 발행
        val result = authService.processLogin(request, ipAddr, userAgent)
        return ApiResponse.ok(result, "로그인에 성공하였습니다.")
    }

    /**
     * 로그아웃 처리 (No.2)
     *
     * 최근 로그인 이력에 로그아웃 시각을 기록한다. 토큰 폐기는 클라이언트가 수행한다.
     */
    @Operation(summary = "로그아웃", description = "접속 이력에 로그아웃 시각을 기록한다.")
    @PostMapping("/logout")
    fun logout(): ApiResponse<Map<String, Boolean>> {
        authService.processLogout()
        return ApiResponse.ok(mapOf("success" to true), "로그아웃되었습니다.")
    }

    /**
     * 토큰 갱신 (No.3)
     *
     * @param request 갱신 토큰
     * @return 재발급된 접근 토큰
     */
    @Operation(summary = "토큰 갱신", description = "refreshToken 으로 accessToken 을 재발급한다.")
    @PostMapping("/refresh")
    fun refresh(@Valid @RequestBody request: RefreshTokenRequest): ApiResponse<RefreshTokenResponse> {
        val result = authService.refreshAccessToken(request.refreshToken)
        return ApiResponse.ok(result, "토큰이 갱신되었습니다.")
    }

    /**
     * 내 정보·권한 조회 (No.4)
     *
     * 메뉴 권한과 데이터 권한을 한 번에 반환한다 — 프론트 전 화면의 권한 판정 기준.
     */
    @Operation(summary = "내 정보·권한 조회", description = "사용자 정보와 메뉴/데이터 권한을 한 번에 반환한다.")
    @GetMapping("/me")
    fun me(): ApiResponse<MyInfoResponse> =
        ApiResponse.ok(authService.getMyInfo(), "조회가 완료되었습니다.")

    /**
     * 계정 전환 (No.5) — 통합관리자 전용 데모 기능
     *
     * @param request 전환 대상 사번
     * @return 전환된 계정 기준의 접근 토큰 및 사용자 정보
     */
    @Operation(summary = "계정 전환", description = "통합관리자가 다른 계정으로 전환한다. (프로토타입 데모)")
    @PostMapping("/switch")
    fun switchAccount(@Valid @RequestBody request: SwitchAccountRequest): ApiResponse<LoginResponse> {
        val result = authService.switchAccount(request)
        return ApiResponse.ok(result, "계정이 전환되었습니다.")
    }

    /**
     * 계정 전환 가능 대상 목록 조회 (계정 전환 팝업 지원)
     */
    @Operation(summary = "계정 전환 대상 목록", description = "전환 허용된 계정 목록을 조회한다.")
    @GetMapping("/switch-targets")
    fun switchTargets(): ApiResponse<List<Map<String, Any?>>> =
        ApiResponse.ok(authService.getSwitchTargets(), "조회가 완료되었습니다.")

    // =================================================================================
    // 이메일 인증 (회원가입 · 비밀번호 찾기 공용)
    // =================================================================================

    /**
     * 이메일 인증 코드 발송
     *
     * 회원가입은 가입하려는 주소로, 비밀번호 찾기는 `/auth/password/forgot` 이 대신 호출한다.
     *
     * @param request 이메일 · 인증 목적(SIGNUP)
     */
    @Operation(
        summary = "이메일 인증 코드 발송",
        description = "입력한 이메일로 인증 코드를 보낸다. 재발송 대기·일일 상한이 적용된다."
    )
    @PostMapping("/email/send-code")
    fun sendEmailCode(@Valid @RequestBody request: EmailCodeSendRequest): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(
            emailVerificationService.sendCode(request.email, request.purpose),
            "인증 코드를 보냈습니다. 메일함을 확인해 주세요."
        )

    /**
     * 이메일 인증 코드 검증
     *
     * 성공하면 1회용 `verificationToken` 을 돌려준다.
     * 이 토큰을 회원가입 또는 비밀번호 재설정 요청에 함께 보내야 한다.
     *
     * @param request 이메일 · 인증 목적 · 인증 코드
     */
    @Operation(
        summary = "이메일 인증 코드 검증",
        description = "인증 코드를 확인하고 1회용 verificationToken 을 발급한다."
    )
    @PostMapping("/email/verify-code")
    fun verifyEmailCode(@Valid @RequestBody request: EmailCodeVerifyRequest): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(
            emailVerificationService.verifyCode(request.email, request.purpose, request.code),
            "이메일 인증이 완료되었습니다."
        )

    // =================================================================================
    // 비밀번호 찾기
    // =================================================================================

    /**
     * 비밀번호 찾기 — 본인 확인 코드 발송
     *
     * 사번과 등록 이메일이 모두 일치할 때만 코드를 보낸다.
     * 계정 존재 여부를 노출하지 않기 위해 결과와 무관하게 동일한 응답을 반환한다.
     *
     * @param request 사번 · 이메일
     */
    @Operation(
        summary = "비밀번호 찾기 — 인증 코드 발송",
        description = "사번과 등록 이메일이 일치하면 인증 코드를 보낸다. 계정 존재 여부는 응답으로 알려 주지 않는다."
    )
    @PostMapping("/password/forgot")
    fun forgotPassword(@Valid @RequestBody request: PasswordForgotRequest): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(authService.forgotPassword(request))

    /**
     * 비밀번호 찾기 — 새 비밀번호로 재설정
     *
     * `/auth/email/verify-code` (purpose = PASSWORD_RESET) 로 받은 토큰이 필요하다.
     *
     * @param request 인증 토큰 · 새 비밀번호
     */
    @Operation(
        summary = "비밀번호 재설정",
        description = "이메일 인증 토큰으로 본인 확인 후 비밀번호를 재설정한다. 잠긴 계정은 함께 해제된다."
    )
    @PostMapping("/password/reset")
    fun resetPassword(@Valid @RequestBody request: PasswordResetRequest): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(authService.resetPassword(request), "비밀번호가 재설정되었습니다.")

    // =================================================================================
    // 회원가입 · 비밀번호
    // =================================================================================

    /**
     * 회원가입
     *
     * 인증 없이 호출한다. 생성된 계정은 **승인 대기(PENDING)** 상태이며,
     * 전산팀이 승인해야 로그인할 수 있다.
     *
     * @param request 사번 · 이름 · 부서 · 비밀번호
     */
    @Operation(
        summary = "회원가입",
        description = "가입을 신청한다. 계정은 승인 대기 상태로 생성되며 전산팀 승인 후 로그인할 수 있다."
    )
    @PostMapping("/signup")
    fun signup(@Valid @RequestBody request: SignupRequest): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(authService.signup(request), "가입 신청이 접수되었습니다.")

    /**
     * 사번 중복 확인 (가입 화면의 중복 확인)
     *
     * @param empNo 확인할 사번
     */
    @Operation(summary = "사번 중복 확인", description = "가입 시 입력한 사번을 쓸 수 있는지 확인한다.")
    @GetMapping("/signup/check-emp-no")
    fun checkEmpNo(
        @Parameter(description = "확인할 사번") @RequestParam empNo: String
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(authService.checkEmpNoAvailable(empNo))

    /**
     * 가입 가능 부서 목록 (통합관리자 부서 제외)
     */
    @Operation(summary = "가입 가능 부서 목록", description = "가입 화면의 소속 부서 선택 목록을 반환한다.")
    @GetMapping("/signup/depts")
    fun signupDepts(): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(authService.getSignupDepts())

    /**
     * 비밀번호 변경 (로그인 상태)
     *
     * @param request 현재 비밀번호 · 새 비밀번호
     */
    @Operation(summary = "비밀번호 변경", description = "로그인한 사용자가 자신의 비밀번호를 변경한다.")
    @PostMapping("/password")
    fun changePassword(@Valid @RequestBody request: PasswordChangeRequest): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(authService.changePassword(request), "비밀번호가 변경되었습니다.")

    /**
     * 프록시/로드밸런서를 경유한 실제 클라이언트 IP 를 추출한다.
     */
    private fun clientIp(request: HttpServletRequest): String? {
        val forwarded = request.getHeader("X-Forwarded-For")
        if (!forwarded.isNullOrBlank()) return forwarded.split(",").first().trim()
        return request.remoteAddr
    }
}

/**
 * 메뉴 트리 조회 컨트롤러 (CM-01)
 */
@RestController
@RequestMapping("/api/v1/menus")
@Tag(name = "01. 인증·공통")
class MenuController(
    private val authService: AuthService
) {

    /**
     * 메뉴 트리 조회 (No.6)
     *
     * 로그인 사용자의 부서 권한으로 접근 가능한 항목만 반환한다.
     */
    @Operation(summary = "메뉴 트리 조회", description = "접근 가능한 메뉴만 그룹 단위로 반환한다.")
    @GetMapping
    fun menus(): ApiResponse<MenuTreeResponse> =
        ApiResponse.ok(authService.getMenuTree(), "조회가 완료되었습니다.")
}
