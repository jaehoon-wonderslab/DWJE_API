package com.dwje.api.service

import com.dwje.api.common.exception.BusinessRuleException
import com.dwje.api.common.exception.DuplicatedValueException
import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.exception.ResourceNotFoundException
import com.dwje.api.common.exception.UnauthenticatedException
import com.dwje.api.common.security.JwtTokenProvider
import com.dwje.api.common.security.PasswordEncoderService
import com.dwje.api.common.security.UserContext
import com.dwje.api.config.AppProperties
import com.dwje.api.model.request.LoginRequest
import com.dwje.api.model.request.PasswordChangeRequest
import com.dwje.api.model.request.PasswordForgotRequest
import com.dwje.api.model.request.PasswordResetRequest
import com.dwje.api.model.request.SignupRequest
import com.dwje.api.model.request.SwitchAccountRequest
import com.dwje.api.model.response.DataFieldInfo
import com.dwje.api.model.response.DeptInfo
import com.dwje.api.model.response.LoginResponse
import com.dwje.api.model.response.LoginUser
import com.dwje.api.model.response.MenuGroup
import com.dwje.api.model.response.MenuItem
import com.dwje.api.model.response.MenuTreeResponse
import com.dwje.api.model.response.MyInfoResponse
import com.dwje.api.model.response.RefreshTokenResponse
import com.dwje.api.repository.AuthRepository
import com.dwje.api.repository.DataFieldRepository
import com.dwje.api.repository.EmailVerificationRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 사용자 인증 서비스
 *
 * 로그인·로그아웃·토큰 갱신·내 정보 조회·계정 전환을 처리하며,
 * 로그인 성공/실패를 모두 `ax.tb_sys_login_hist` 에 기록한다.
 */
@Service
class AuthService(
    private val authRepository: AuthRepository,
    private val authorizationService: AuthorizationService,
    private val tokenProvider: JwtTokenProvider,
    private val appProperties: AppProperties,
    private val passwordEncoderService: PasswordEncoderService,
    private val auditLogService: AuditLogService,
    private val emailVerificationService: EmailVerificationService,
    private val emailVerificationRepository: EmailVerificationRepository,
    private val dataFieldRepository: DataFieldRepository
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 사번/비밀번호 기반 로그인을 처리한다.
     *
     * 처리 절차
     * 1. 계정 존재 여부 확인 (없으면 실패 이력 기록 후 인증 오류)
     * 2. 계정 상태 확인 (정지 계정 차단)
     * 3. 비밀번호 대조 — 실패 시 실패 횟수 증가, 임계 초과 시 계정 정지
     * 4. 성공 시 최종 접속일시 갱신 및 토큰 발급
     *
     * @param request   로그인 요청 (사번, 비밀번호)
     * @param ipAddr    접속 IP
     * @param userAgent User-Agent
     * @return 접근/갱신 토큰 및 사용자 기본 정보
     */
    @Transactional
    fun processLogin(request: LoginRequest, ipAddr: String?, userAgent: String?): LoginResponse {
        val loginId = request.loginId.trim()

        // 1. 계정 조회 — 존재하지 않아도 계정 유무를 노출하지 않도록 동일 메시지를 사용한다.
        val user = authRepository.findUserWithDept(loginId)
        if (user == null) {
            authRepository.insertLoginHistory(loginId, "FAIL", "존재하지 않는 계정", ipAddr, userAgent)
            throw UnauthenticatedException("사번 또는 비밀번호가 올바르지 않습니다.")
        }

        // 2. 계정 상태 확인 — 승인 대기와 정지를 구분해 안내한다.
        val stateCd = user["userStateCd"] as String?
        if (stateCd != "ACTIVE") {
            val (reason, message) = when (stateCd) {
                "PENDING" -> "승인 대기 계정" to "가입 승인 대기 중인 계정입니다. 전산팀 승인 후 로그인할 수 있습니다."
                else -> "정지 계정" to "사용이 정지된 계정입니다. 관리자에게 문의하세요."
            }
            authRepository.insertLoginHistory(loginId, "LOCKED", reason, ipAddr, userAgent)
            throw UnauthenticatedException(message)
        }

        // 3. 비밀번호 대조
        val pwdHash = user["pwdHash"] as String?
        if (!passwordEncoderService.matches(request.password, pwdHash)) {
            val failCnt = authRepository.increaseLoginFailCount(loginId)

            // 연속 실패가 임계값에 도달하면 계정을 정지시킨다.
            if (failCnt >= appProperties.loginFailLimit) {
                authRepository.updateUserState(loginId, "SUSPENDED", "SYSTEM")
                authRepository.insertLoginHistory(loginId, "LOCKED", "연속 실패 ${failCnt}회 잠금", ipAddr, userAgent)
                throw UnauthenticatedException("비밀번호를 ${failCnt}회 잘못 입력하여 계정이 정지되었습니다.")
            }

            authRepository.insertLoginHistory(loginId, "FAIL", "비밀번호 불일치(${failCnt}회)", ipAddr, userAgent)
            throw UnauthenticatedException("사번 또는 비밀번호가 올바르지 않습니다.")
        }

        // 4. 로그인 성공 처리
        authRepository.markLoginSuccess(loginId)
        authRepository.insertLoginHistory(loginId, "SUCCESS", null, ipAddr, userAgent)

        // 5. 예전 방식(BCrypt 등)으로 저장된 해시는 이 시점에 최신 포맷으로 조용히 올린다.
        //    사용자는 아무것도 하지 않아도 다음 로그인부터 새 해시로 검증된다.
        if (passwordEncoderService.needsRehash(pwdHash)) {
            runCatching { authRepository.updatePasswordHash(loginId, passwordEncoderService.encode(request.password), loginId) }
                .onSuccess { log.info("비밀번호 해시를 최신 포맷으로 갱신했습니다: userId={}", loginId) }
                .onFailure { log.warn("비밀번호 해시 갱신 실패: userId={} ({})", loginId, it.message) }
        }

        return buildLoginResponse(user, impersonated = false)
    }

    /**
     * 로그아웃 — 최근 로그인 이력에 로그아웃 시각을 기록한다.
     *
     * 토큰 자체는 무상태(stateless)이므로 클라이언트가 폐기한다.
     */
    @Transactional
    fun processLogout() {
        val principal = UserContext.current()
        authRepository.updateLogout(principal.userId)
        log.info("로그아웃 처리 : userId={}", principal.userId)
    }

    /**
     * Refresh Token 으로 Access Token 을 재발급한다.
     *
     * @param refreshToken 갱신 토큰
     */
    @Transactional(readOnly = true)
    fun refreshAccessToken(refreshToken: String): RefreshTokenResponse {
        // 1. 갱신 토큰 검증 후 사번 확보
        val userId = tokenProvider.parseRefreshToken(refreshToken)

        // 2. 계정 유효성 재확인 — 토큰 발급 이후 정지된 계정을 차단한다.
        val user = authRepository.findUserWithDept(userId)
            ?: throw UnauthenticatedException("존재하지 않는 계정입니다.")
        if (user["userStateCd"] != "ACTIVE") {
            throw UnauthenticatedException("사용이 정지된 계정입니다.")
        }

        val accessToken = tokenProvider.createAccessToken(
            userId = user["userId"] as String,
            userName = user["userName"] as String,
            deptId = user["deptId"] as Int,
            deptName = user["deptName"] as String,
            superAdmin = user["superAdmin"] as Boolean,
            plantCd = user["plantCd"] as String?
        )

        return RefreshTokenResponse(accessToken, tokenProvider.accessTokenValiditySec())
    }

    /**
     * 내 정보 및 권한 전체를 조회한다.
     *
     * 메뉴 권한과 데이터 권한을 한 번에 반환하여 프론트 전 화면의 권한 판정 기준으로 사용한다.
     */
    @Transactional(readOnly = true)
    fun getMyInfo(): MyInfoResponse {
        val principal = UserContext.current()

        // 항목은 운영 중에 늘어난다(V33) — 코드 상수가 아니라 사용 중 항목 표를 기준으로 한다.
        // 통합관리자는 전 항목을 열람하므로 허용 목록을 전체로 채운다.
        val allKeys = dataFieldRepository.findActiveKeys()
        val dataPerms = if (principal.superAdmin) allKeys else allKeys.filter { it in principal.dataPerms }
        val blindFields = allKeys.filterNot { it in dataPerms }

        // 적용 중(apply_flg='Y') 항목만 — 화면은 이걸로 「응답 필드명 → 항목」 맵을 만들어 표·엑셀을 자동 마스킹한다.
        // 재로그인 때 반영되는 계약이라 여기서 캐시하지 않는다.
        @Suppress("UNCHECKED_CAST")
        val dataFields = dataFieldRepository.findAppliedFields().map {
            DataFieldInfo(
                key = it["key"] as String,
                name = it["name"] as String,
                category = it["category"] as String?,
                categoryNm = it["categoryNm"] as String?,
                attrs = it["attrs"] as List<String>
            )
        }

        // menuPerms 는 화면 접근 통제 목록이다 — 좌측 메뉴에 그릴 목록이 아니다.
        // 메뉴 트리 쿼리를 쓰면 하위 화면(is_sub_page)이 빠져 들어갈 수 없게 되므로
        // 통합관리자에게는 사용 중인 전 화면을 준다. (system/menu-perms 의 matrix 와 같은 기준)
        // 하위 화면을 메뉴에 그릴지는 클라이언트가 screens[].sub 로 판단한다.
        val menuPerms = if (principal.superAdmin) {
            authRepository.findAllMenuIds()
        } else {
            principal.menuPerms.sorted()
        }

        return MyInfoResponse(
            user = LoginUser(
                empNo = principal.userId,
                name = principal.userName,
                dept = principal.deptName,
                pos = principal.positionCd,
                deptId = principal.deptId,
                superAdmin = principal.superAdmin
            ),
            dept = DeptInfo(
                deptId = principal.deptId,
                deptNm = principal.deptName,
                deptAbbr = principal.deptAbbr,
                superAdmin = principal.superAdmin,
                plantCd = principal.plantCd
            ),
            menuPerms = menuPerms,
            dataPerms = dataPerms,
            blindFields = blindFields,
            dataFields = dataFields,
            servingModelVer = authRepository.findServingModelVersion(),
            impersonated = principal.impersonated
        )
    }

    /**
     * 계정 전환(대행 로그인) — 통합관리자 전용 데모 기능.
     *
     * 전환 대상은 `is_switch_target = true` 계정으로 한정하며, 전환 사실을 감사 로그에 남긴다.
     *
     * @param request 전환 대상 사번
     */
    @Transactional
    fun switchAccount(request: SwitchAccountRequest): LoginResponse {
        val actor = authorizationService.requireSuperAdmin()

        val target = authRepository.findUserWithDept(request.empNo.trim())
            ?: throw ResourceNotFoundException("전환할 계정을 찾을 수 없습니다. [${request.empNo}]")

        // 전환 허용 대상인지 확인한다.
        if (target["switchable"] != true) {
            throw BusinessRuleException("계정 전환이 허용되지 않은 계정입니다. [${request.empNo}]")
        }
        if (target["userStateCd"] != "ACTIVE") {
            throw BusinessRuleException("정지된 계정으로는 전환할 수 없습니다. [${request.empNo}]")
        }

        log.info("계정 전환 : actor={} → target={}", actor.userId, request.empNo)
        return buildLoginResponse(target, impersonated = true)
    }

    /**
     * 계정 전환 가능한 대상 목록을 조회한다.
     */
    @Transactional(readOnly = true)
    fun getSwitchTargets(): List<Map<String, Any?>> {
        authorizationService.requireSuperAdmin()
        return authRepository.findSwitchTargets()
    }

    /**
     * 로그인 사용자가 접근 가능한 메뉴 트리를 그룹 단위로 반환한다.
     */
    @Transactional(readOnly = true)
    fun getMenuTree(): MenuTreeResponse {
        val principal = UserContext.current()
        val rows = authRepository.findMenuTree(principal.deptId, principal.superAdmin)

        // 그룹 단위로 묶되 DB 정렬 순서(sort_seq)를 유지한다.
        val groups = rows
            .groupBy { Triple(it["groupId"] as String, it["groupName"] as String, it["solo"] as Boolean) }
            .map { (key, items) ->
                MenuGroup(
                    groupId = key.first,
                    group = key.second,
                    solo = key.third,
                    items = items.map {
                        MenuItem(
                            id = it["menuId"] as String,
                            name = it["menuName"] as String,
                            path = it["routePath"] as String?,
                            tag = it["tag"] as String?
                        )
                    }
                )
            }

        return MenuTreeResponse(groups)
    }

    // ---------------------------------------------------------------------------------
    // 내부 보조 메서드
    // ---------------------------------------------------------------------------------

    /**
     * 조회된 계정 정보로 토큰을 발급하고 로그인 응답을 조립한다.
     */
    private fun buildLoginResponse(user: Map<String, Any?>, impersonated: Boolean): LoginResponse {
        val userId = user["userId"] as String
        val userName = user["userName"] as String
        val deptId = user["deptId"] as Int
        val deptName = user["deptName"] as String
        val superAdmin = user["superAdmin"] as Boolean

        val accessToken = tokenProvider.createAccessToken(
            userId = userId,
            userName = userName,
            deptId = deptId,
            deptName = deptName,
            superAdmin = superAdmin,
            plantCd = user["plantCd"] as String?,
            impersonated = impersonated
        )

        return LoginResponse(
            accessToken = accessToken,
            refreshToken = tokenProvider.createRefreshToken(userId),
            expiresIn = tokenProvider.accessTokenValiditySec(),
            user = LoginUser(
                empNo = userId,
                name = userName,
                dept = deptName,
                pos = user["positionCd"] as String?,
                deptId = deptId,
                superAdmin = superAdmin
            )
        )
    }


    // =================================================================================
    // 회원가입 · 비밀번호 관리
    // =================================================================================

    /**
     * 회원가입을 처리한다.
     *
     * 사내 시스템이므로 가입 즉시 사용할 수 없다. 계정은 **PENDING(승인 대기)** 로 만들어지고,
     * 전산팀이 소속과 신원을 확인해 승인해야 로그인할 수 있다.
     *
     * 검증 순서
     * 1. 비밀번호 확인 일치
     * 2. 비밀번호 정책 (길이 · 공백 · 문자 조합)
     * 3. 사번 중복
     * 4. 부서 존재 여부
     *
     * @param request 가입 신청 정보
     * @return 등록된 사번과 상태
     */
    @Transactional
    fun signup(request: SignupRequest): Map<String, Any?> {
        val empNo = request.empNo.trim()

        // 1. 비밀번호 확인 일치 — 정책 검사보다 먼저 해서 오탈자를 빨리 알린다.
        if (request.password != request.passwordConfirm) {
            throw InvalidParameterException("비밀번호와 비밀번호 확인이 일치하지 않습니다.", "passwordConfirm")
        }

        // 2. 비밀번호 정책
        passwordEncoderService.validatePolicy(request.password, "password")

        // 사번을 비밀번호에 그대로 넣는 것을 막는다.
        if (request.password.contains(empNo, ignoreCase = true)) {
            throw InvalidParameterException("비밀번호에 사번을 포함할 수 없습니다.", "password")
        }

        // 3. 이메일 인증 확인 — 토큰을 여기서 소모해 재사용을 막는다.
        val verified = emailVerificationService.consumeToken(
            request.verificationToken, EmailVerificationService.PURPOSE_SIGNUP, request.email
        )

        // 4. 사번 중복
        if (authRepository.existsUserId(empNo)) {
            throw DuplicatedValueException("이미 사용 중인 사번입니다. [$empNo]", "empNo")
        }

        // 5. 이메일 중복 — 한 주소로 여러 계정을 만들 수 없다.
        if (emailVerificationRepository.existsEmail(verified.email)) {
            throw DuplicatedValueException("이미 사용 중인 이메일입니다.", "email")
        }

        // 6. 부서 확인
        val deptId = request.deptId
            ?: throw InvalidParameterException("소속 부서를 선택해 주세요.", "deptId")
        if (!authRepository.existsDept(deptId)) {
            throw InvalidParameterException("존재하지 않는 부서입니다. [deptId=$deptId]", "deptId")
        }

        authRepository.insertSignupUser(
            userId = empNo,
            userNm = request.name.trim(),
            deptId = deptId,
            plantCd = appProperties.defaultPlantCd,
            positionCd = request.pos?.takeIf { it.isNotBlank() } ?: "STAFF",
            pwdHash = passwordEncoderService.encode(request.password)
        )
        emailVerificationRepository.updateUserEmail(empNo, verified.email)

        // 가입 신청은 계정 생성 행위이므로 감사 로그에 남긴다.
        auditLogService.record(
            logType = "AUTO_GEN",
            menuId = null,
            targetDesc = "회원가입 신청 [$empNo ${request.name}]",
            remark = "부서=$deptId, 상태=PENDING, 이메일=${emailVerificationService.maskEmail(verified.email)}"
        )

        log.info("회원가입 신청 : empNo={} name={} deptId={}", empNo, request.name, deptId)

        return mapOf(
            "empNo" to empNo,
            "email" to emailVerificationService.maskEmail(verified.email),
            "state" to "PENDING",
            "message" to "가입 신청이 접수되었습니다. 전산팀 승인 후 로그인할 수 있습니다."
        )
    }

    /**
     * 사번 사용 가능 여부를 확인한다. (가입 화면의 중복 확인 버튼)
     *
     * @param empNo 확인할 사번
     */
    @Transactional(readOnly = true)
    fun checkEmpNoAvailable(empNo: String): Map<String, Any?> {
        val trimmed = empNo.trim()
        if (trimmed.isBlank()) {
            throw InvalidParameterException("사번을 입력해 주세요.", "empNo")
        }

        val used = authRepository.existsUserId(trimmed)
        return mapOf(
            "empNo" to trimmed,
            "available" to !used,
            "message" to if (used) "이미 사용 중인 사번입니다." else "사용할 수 있는 사번입니다."
        )
    }

    /**
     * 가입 시 선택할 수 있는 부서 목록을 조회한다. (통합관리자 부서 제외)
     */
    @Transactional(readOnly = true)
    fun getSignupDepts(): Map<String, Any?> =
        mapOf("depts" to authRepository.findSignupDepts())

    /**
     * 로그인한 사용자가 자기 비밀번호를 변경한다.
     *
     * @param request 현재 비밀번호 · 새 비밀번호
     */
    @Transactional
    fun changePassword(request: PasswordChangeRequest): Map<String, Any?> {
        val principal = UserContext.current()

        val user = authRepository.findUserWithDept(principal.userId)
            ?: throw UnauthenticatedException("계정 정보를 찾을 수 없습니다.")

        // 1. 현재 비밀번호 확인
        if (!passwordEncoderService.matches(request.currentPassword, user["pwdHash"] as String?)) {
            throw InvalidParameterException("현재 비밀번호가 일치하지 않습니다.", "currentPassword")
        }

        // 2. 새 비밀번호 확인 일치 · 정책 검사
        if (request.newPassword != request.newPasswordConfirm) {
            throw InvalidParameterException("새 비밀번호와 확인이 일치하지 않습니다.", "newPasswordConfirm")
        }
        passwordEncoderService.validatePolicy(request.newPassword, "newPassword")

        // 3. 직전 비밀번호 재사용 금지
        if (passwordEncoderService.matches(request.newPassword, user["pwdHash"] as String?)) {
            throw InvalidParameterException("현재 비밀번호와 다른 값으로 설정해 주세요.", "newPassword")
        }
        if (request.newPassword.contains(principal.userId, ignoreCase = true)) {
            throw InvalidParameterException("비밀번호에 사번을 포함할 수 없습니다.", "newPassword")
        }

        authRepository.updatePasswordHash(
            principal.userId, passwordEncoderService.encode(request.newPassword), principal.userId
        )

        auditLogService.record(
            logType = "AUTO_GEN",
            menuId = null,
            targetDesc = "비밀번호 변경 [${principal.userId}]",
            remark = "본인 변경"
        )

        log.info("비밀번호 변경 : userId={}", principal.userId)
        return mapOf("success" to true, "changedAt" to java.time.LocalDateTime.now().format(com.dwje.api.common.util.DateUtils.DATETIME))
    }

    /**
     * 비밀번호 찾기 — 본인 확인 코드 발송
     *
     * 사번과 등록 이메일이 모두 일치할 때만 코드를 보낸다.
     * **계정 존재 여부를 응답으로 알려 주지 않기 위해** 일치하지 않아도 같은 응답을 돌려준다.
     * (계정 열거 공격 방지)
     *
     * 발송 제한(재발송 대기·일일 상한)도 응답에 드러내지 않는다.
     * 제한은 계정이 실재할 때만 걸리므로, 그대로 409 를 돌려주면 상태 코드가
     * 계정 존재 여부를 알려 준다 — 같은 사번을 두 번 던져 409 가 나오면 실재 계정이다.
     * 이 화면이 막으려던 열거가 바로 그 경로로 뚫리므로, 어떤 사유든 200 + 같은 문구로 삼키고
     * 원인은 서버 로그에만 남긴다.
     *
     * @param request 사번 · 이메일
     */
    @Transactional
    fun forgotPassword(request: PasswordForgotRequest): Map<String, Any?> {
        val empNo = request.empNo.trim()
        val email = emailVerificationService.normalizeEmail(request.email)

        val user = emailVerificationRepository.findUserByEmpNoAndEmail(empNo, email)

        // 일치하는 계정이 있을 때만 실제로 발송한다.
        if (user != null && user["stateCd"] == "ACTIVE") {
            // 독립 트랜잭션으로 보낸다. 같은 트랜잭션에서 발송이 실패하면 예외를 삼켜도
            // 트랜잭션이 rollback-only 로 남아 커밋에서 500 이 된다.
            runCatching {
                emailVerificationService.sendCodeInNewTransaction(
                    email, EmailVerificationService.PURPOSE_PASSWORD_RESET, empNo
                )
            }.onFailure {
                // 발송 제한·발송 실패 모두 응답에 드러내지 않는다. 밖에서 보이는 결과가
                // 달라지는 순간 그 차이가 계정 존재 여부를 알려 준다.
                if (it is com.dwje.api.common.exception.BusinessException) {
                    log.info("비밀번호 찾기 코드 발송 제한: empNo={} reason={}", empNo, it.message)
                } else {
                    log.error("비밀번호 찾기 코드 발송 실패: empNo={}", empNo, it)
                }
            }
        } else {
            // 존재하지 않거나 사용 불가 계정 — 시도 자체는 감사 로그에 남긴다.
            log.info("비밀번호 찾기 대상 없음: empNo={} email={}", empNo, emailVerificationService.maskEmail(email))
            auditLogService.record(
                logType = "AUTO_GEN",
                menuId = null,
                targetDesc = "비밀번호 찾기 실패 시도 [$empNo]",
                resultCd = "REJECT",
                remark = "사번·이메일 불일치 또는 사용 불가 계정"
            )
        }

        return mapOf(
            "email" to emailVerificationService.maskEmail(email),
            "expireMinutes" to 5,
            "message" to "입력하신 정보와 일치하는 계정이 있으면 인증 코드를 보냈습니다. 메일함을 확인해 주세요."
        )
    }

    /**
     * 비밀번호 찾기 — 새 비밀번호로 재설정
     *
     * 이메일 인증으로 받은 1회용 토큰이 있어야 하며, 토큰에 묶인 계정만 변경한다.
     *
     * @param request 인증 토큰 · 새 비밀번호
     */
    @Transactional
    fun resetPassword(request: PasswordResetRequest): Map<String, Any?> {
        // 1. 토큰 검증 및 소모 — 대상 계정이 토큰에 묶여 있다.
        val verified = emailVerificationService.consumeToken(
            request.verificationToken, EmailVerificationService.PURPOSE_PASSWORD_RESET
        )
        val empNo = verified.targetUserId
            ?: throw InvalidParameterException("인증 정보에 대상 계정이 없습니다. 처음부터 다시 진행해 주세요.", "verificationToken")

        // 2. 새 비밀번호 확인 일치 · 정책 검사
        if (request.newPassword != request.newPasswordConfirm) {
            throw InvalidParameterException("새 비밀번호와 확인이 일치하지 않습니다.", "newPasswordConfirm")
        }
        passwordEncoderService.validatePolicy(request.newPassword, "newPassword")
        if (request.newPassword.contains(empNo, ignoreCase = true)) {
            throw InvalidParameterException("비밀번호에 사번을 포함할 수 없습니다.", "newPassword")
        }

        val user = authRepository.findUserWithDept(empNo)
            ?: throw ResourceNotFoundException("계정을 찾을 수 없습니다.")

        // 3. 직전 비밀번호 재사용 금지
        if (passwordEncoderService.matches(request.newPassword, user["pwdHash"] as String?)) {
            throw InvalidParameterException("이전 비밀번호와 다른 값으로 설정해 주세요.", "newPassword")
        }

        authRepository.updatePasswordHash(empNo, passwordEncoderService.encode(request.newPassword), empNo)

        // 4. 잠금 해제 — 실패 횟수로 정지된 계정을 다시 쓸 수 있게 한다.
        if (user["userStateCd"] == "SUSPENDED" && (user["loginFailCnt"] as? Int ?: 0) >= appProperties.loginFailLimit) {
            authRepository.updateUserState(empNo, "ACTIVE", "SYSTEM")
            log.info("비밀번호 재설정으로 계정 잠금 해제: empNo={}", empNo)
        }
        authRepository.markLoginSuccess(empNo)

        auditLogService.record(
            logType = "AUTO_GEN",
            menuId = null,
            targetDesc = "비밀번호 재설정 [$empNo]",
            remark = "이메일 인증 기반 재설정"
        )

        log.info("비밀번호 재설정 완료: empNo={}", empNo)
        return mapOf("success" to true, "empNo" to empNo, "message" to "비밀번호가 재설정되었습니다. 새 비밀번호로 로그인해 주세요.")
    }
}
