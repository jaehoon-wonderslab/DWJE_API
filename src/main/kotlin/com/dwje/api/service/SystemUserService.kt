package com.dwje.api.service

import com.dwje.api.common.util.AuditType
import com.dwje.api.common.exception.BusinessException
import com.dwje.api.common.exception.BusinessRuleException
import com.dwje.api.common.response.ErrorCode
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.common.exception.DuplicatedValueException
import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.exception.MenuAccessDeniedException
import com.dwje.api.common.exception.ResourceNotFoundException
import com.dwje.api.common.response.PageMeta
import com.dwje.api.common.util.DataField
import com.dwje.api.common.util.MenuId
import com.dwje.api.common.util.PageRequestParam
import com.dwje.api.model.request.DataPermRequest
import com.dwje.api.model.request.DeptSaveRequest
import com.dwje.api.model.request.MenuPermCopyRequest
import com.dwje.api.model.request.MenuPermGroupRequest
import com.dwje.api.model.request.MenuPermRequest
import com.dwje.api.model.request.UserSaveRequest
import com.dwje.api.repository.SystemUserRepository
import org.slf4j.LoggerFactory
import com.dwje.api.common.security.PasswordEncoderService
import com.dwje.api.config.AccountUnlockProperties
import com.dwje.api.config.AppProperties
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 계정 · 부서 · 권한 관리 서비스 (SY-01, SY-02, SY-03)
 *
 * 모든 변경은 권한 변경 이력(ax.tb_sys_perm_log)과 감사 로그(ax.tb_log_audit)에 기록된다.
 *
 * 접근 : 화면 권한 `sys-account` · `sys-menu` · `sys-data`(메서드별, ax.tb_sys_dept_menu_perm) · 값 마스킹 : 없음
 *        조회는 `requireMenu`, 등록·수정·삭제·상태 전환·복사는 `requireWrite`(R-06). 시스템 부서 보호는 [SystemDeptGuard](CMN-01).
 */
@Service
class SystemUserService(
    private val systemUserRepository: SystemUserRepository,
    private val authorizationService: AuthorizationService,
    private val auditLogService: AuditLogService,
    private val passwordEncoderService: PasswordEncoderService,
    private val appProperties: AppProperties = AppProperties(),
    private val systemDeptGuard: SystemDeptGuard = SystemDeptGuard(appProperties),
    private val accountUnlockProperties: AccountUnlockProperties = AccountUnlockProperties()
) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** 계정 등록 시 초기 비밀번호가 없으면 사번을 사용한다. */
        private const val DEFAULT_PASSWORD_SUFFIX = "!Dwje1234"

        /** 비고 줄의 날짜 기준 */
        private val KST = java.time.ZoneId.of("Asia/Seoul")
    }

    // =================================================================================
    // 계정 관리
    // =================================================================================

    /** 계정 관리 요약 (No.127) */
    @Transactional(readOnly = true)
    fun getAccountSummary(): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.SYS_ACCOUNT)
        val summary = systemUserRepository.findAccountSummary().toMutableMap()
        val canChangePassword = canChangePassword(principal)
        summary["currentUser"] = mapOf(
            "empNo" to principal.userId,
            "name" to principal.userName,
            "dept" to principal.deptName,
            "canChangePassword" to canChangePassword,
            "superAdmin" to principal.superAdmin
        )
        // 화면이 비밀번호 필드를 보일지 정하는 기준 — 통합관리자 또는 소속 부서가 기본으로 계정 관리 권한을 가진 사람
        summary["canChangePassword"] = canChangePassword
        // 쓰기 권한(R-06) — false 면 화면은 등록·편집·삭제·승인 버튼을 비활성으로 그린다
        summary["canWrite"] = principal.canWriteMenu(MenuId.SYS_ACCOUNT)
        // 인증 메일 마지막 발송 실패 시각 — 있으면 화면이 「메일 발송 실패」 경고를 띄운다 (R-17, SMTP 계정 휴면 대비)
        summary["mailLastFailAt"] = systemUserRepository.findMailLastFailAt()
        // 이메일 잠금 해제 사용 여부(R-02) — false 면 잠긴 계정은 관리자 해제만 가능하다
        summary["mailEnabled"] = accountUnlockProperties.emailEnabled
        // 미배정 부서 소속 수 (01 ACC-08) — 미배정 부서가 없으면 0
        summary["unassignedCnt"] = systemUserRepository.findDeptIdByName(appProperties.unassignedDeptName)
            ?.let { systemUserRepository.countUsersInDept(it) } ?: 0L
        return summary.toMap()
    }

    /** 계정 목록 조회 (No.128) */
    @Transactional(readOnly = true)
    fun getUsers(
        keyword: String?,
        deptId: Int?,
        state: String?,
        switchable: Boolean?,
        page: Int?,
        size: Int?,
        joinSrc: String? = null
    ): Pair<List<Map<String, Any?>>, PageMeta> {
        authorizationService.requireMenu(MenuId.SYS_ACCOUNT)
        // size=0 이면 전량 — 화면이 Tabulator 열 필터를 전체 결과에 걸고 쪽은 브라우저에서 나눈다.
        val paging = PageRequestParam.ofAllowAll(page, size)
        val src = joinSrc?.trim()?.uppercase()?.ifEmpty { null }?.also {
            if (it !in setOf("GROUPWARE", "SIGNUP", "ADMIN")) {
                throw InvalidParameterException("가입 경로는 GROUPWARE · SIGNUP · ADMIN 중 하나여야 합니다.", "joinSrc")
            }
        }

        val total = systemUserRepository.countUsers(keyword, deptId, state, switchable, src)
        val rows = withExtraMenus(systemUserRepository.findUsers(keyword, deptId, state, switchable, paging.limitOrNull, paging.offset, src))

        return rows to (if (paging.isAll) PageMeta.all(total) else PageMeta.of(paging.page, paging.size, total))
    }

    /** 계정 등록 (No.129) */
    @Transactional
    fun createUser(request: UserSaveRequest): Map<String, Any?> {
        val principal = authorizationService.requireWrite(MenuId.SYS_ACCOUNT)

        val empNo = request.empNo?.trim()
            ?: throw InvalidParameterException("사번을 입력해 주세요.", "empNo")
        val name = request.name?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw InvalidParameterException("이름을 입력해 주세요.", "name")
        checkMax(name, 50, "이름은", "name")
        val deptId = request.deptId
            ?: throw InvalidParameterException("부서를 선택해 주세요.", "deptId")

        if (systemUserRepository.existsUser(empNo)) {
            throw DuplicatedValueException("이미 등록된 사번입니다. [$empNo]", "empNo")
        }
        val dept = systemUserRepository.findDept(deptId)
            ?: throw ResourceNotFoundException("부서를 찾을 수 없습니다. [deptId=$deptId]")
        // 시스템 부서 규칙(CMN-01) — 통합관리자 부서 배정은 통합관리자만, 미배정 계정에는 추가 메뉴 없음
        systemDeptGuard.assertCanAssignDept(principal, dept)
        val requestedMenus = validateExtraMenus(request.extraMenuIds)
        systemDeptGuard.assertNoExtraMenusForUnassigned(dept, requestedMenus)
        // 관리 화면 4종 추가 허용은 통합관리자만(R-07) — 새 계정이라 요청 목록 전부가 부여다
        systemDeptGuard.assertCanGrantAdminScreen(principal, requestedMenus.orEmpty())

        val stateCd = assertAssignableState(systemUserRepository.normalizeUserState(request.state ?: "ACTIVE"))
        // 관리자가 초기 비밀번호를 주지 않으면 사번 기반 임시 비밀번호를 발급한다.
        val initialPassword = request.password ?: "$empNo$DEFAULT_PASSWORD_SUFFIX"
        passwordEncoderService.validatePolicy(initialPassword, "password")
        val pwdHash = passwordEncoderService.encode(initialPassword)

        systemUserRepository.insertUser(
            empNo = empNo,
            name = name,
            deptId = deptId,
            positionCd = request.pos ?: "STAFF",
            stateCd = stateCd,
            switchable = request.switchable ?: false,
            plantCd = request.plantCd,
            pwdHash = pwdHash,
            actor = principal.userId
        )

        auditLogService.recordPermChange(
            actCd = "ACCOUNT",
            targetKindCd = "USER",
            targetNm = "$name($empNo)",
            detail = "계정 등록 — 부서=$deptId, 직급=${request.pos ?: "STAFF"}, 상태=$stateCd",
            targetDeptId = deptId,
            targetUserId = empNo
        )

        // 추가 허용 화면도 같은 트랜잭션에서 저장한다(계정 정보 + 추가 메뉴 원자 저장).
        val grants = applyExtraMenus(empNo, "$name($empNo)", requestedMenus, principal.userId, validateGrantReasons(request.extraMenuReasons))
        log.info("계정 등록 : empNo={} name={} by={}", empNo, name, principal.userId)
        return mapOf("empNo" to empNo, "extraMenuIds" to grants)
    }

    /** 계정 수정 (No.130) */
    @Transactional
    fun updateUser(empNo: String, request: UserSaveRequest): Map<String, Any?> {
        val principal = authorizationService.requireWrite(MenuId.SYS_ACCOUNT)
        requireUser(empNo)

        val stateCd = request.state?.let { assertAssignableState(systemUserRepository.normalizeUserState(it)) }
        // 추가 허용 화면은 계정 정보와 **한 트랜잭션**이다 — 없는 화면 ID 가 하나라도 있으면 이름·부서도 바뀌지 않는다.
        val requestedMenus = validateExtraMenus(request.extraMenuIds)

        // 시스템 부서 규칙(CMN-01) — 검사는 저장 뒤의 부서 기준이다(같은 저장에서 실부서로 옮기면 추가 메뉴를 허용).
        val currentDeptId = systemUserRepository.findUserDeptId(empNo)
        val targetDeptId = request.deptId ?: currentDeptId
        val targetDept = targetDeptId?.let { systemUserRepository.findDept(it) }
            ?: throw ResourceNotFoundException("부서를 찾을 수 없습니다. [deptId=$targetDeptId]")
        val currentGrants = systemUserRepository.findUserGrantIds(empNo)
        val plan = requestedMenus?.let { planGrantChanges(currentGrants, it) }
        // 본인 부서·추가 메뉴는 다른 관리자가 바꾼다(409) — 통합관리자 부서 배정 403 보다 먼저 본다(01 ACC-02, 공통 4.1)
        assertNotSelfPermChange(principal, empNo, currentDeptId, targetDept,
            plan != null && (plan.add.isNotEmpty() || plan.remove.isNotEmpty()))
        if (request.deptId != null) systemDeptGuard.assertCanAssignDept(principal, targetDept)
        systemDeptGuard.assertNoExtraMenusForUnassigned(targetDept, requestedMenus)
        // 관리 화면 4종 부여·회수는 통합관리자만(R-07) — 이미 가진 화면을 그대로 두는 저장은 통과한다(치환 계획 기준)
        if (plan != null) systemDeptGuard.assertCanGrantAdminScreen(principal, plan.add + plan.remove)
        // 실부서 → 미배정 이동이면 추가 허용을 전부 회수한다(01 ACC-14). 관리 화면 회수도 통합관리자만(R-07)
        val movingToUnassigned = currentDeptId != targetDept["deptId"] && systemDeptGuard.isUnassignedDept(targetDept)
        if (movingToUnassigned) systemDeptGuard.assertCanGrantAdminScreen(principal, currentGrants)
        // 비밀번호 — 비어 있으면 그대로. 값이 있으면 관리자 판정·정책 검사를 저장 전에 끝내 실패 시 아무것도 바뀌지 않게 한다.
        val newPasswordHash = preparePasswordChange(request.password, empNo, principal)

        // 자기 자신의 계정을 사용 상태에서 내릴 수 없다. (PENDING 도 로그인이 막힌다)
        if (stateCd != null && stateCd != "ACTIVE" && empNo == principal.userId) {
            throw BusinessRuleException("로그인 중인 본인 계정은 사용 상태에서 내릴 수 없습니다.")
        }

        val before = systemUserRepository.findUserByEmpNo(empNo)
            ?: throw ResourceNotFoundException("계정을 찾을 수 없습니다. [$empNo]")
        val newName = request.name?.trim()
        newName?.let {
            // 생략은 그대로, 빈 값은 400 (01 ACC-12, 공통 CMN-04)
            if (it.isEmpty()) throw InvalidParameterException("이름을 입력해 주세요.", "name")
            checkMax(it, 50, "이름은", "name")
        }
        val targetNm = "${newName ?: before["name"]}($empNo)"
        val deptChanged = request.deptId != null && request.deptId != currentDeptId

        // 부서는 이동 경로 한 곳(moveDept)이 맡는다 — 여기서는 나머지 필드만 저장한다(01 ACC-06)
        val updated = systemUserRepository.updateUser(
            empNo = empNo,
            name = newName,
            deptId = null,
            positionCd = request.pos,
            stateCd = stateCd,
            switchable = request.switchable,
            actor = principal.userId
        )
        if (updated == 0) throw ResourceNotFoundException("계정을 찾을 수 없습니다. [$empNo]")

        // 「계정 수정」 이력은 바뀐 필드만 이름으로 — 바뀐 것이 없으면 남기지 않는다(부서 이동은 따로 한 줄)
        val changes = listOfNotNull(
            newName?.takeIf { it != before["name"] }?.let { "이름 ${before["name"]}→$it" },
            request.pos?.takeIf { it != before["pos"] }?.let { "직급 ${before["pos"]}→$it" },
            stateCd?.takeIf { it != before["state"] }?.let { "상태 ${before["state"]}→$it" },
            request.switchable?.takeIf { it != before["demo"] }?.let { "계정 전환 대상 ${before["demo"]}→$it" }
        )
        if (changes.isNotEmpty()) {
            auditLogService.recordPermChange(
                actCd = "ACCOUNT",
                targetKindCd = "USER",
                targetNm = targetNm,
                detail = "계정 수정 — ${changes.joinToString(", ")}",
                targetDeptId = currentDeptId,
                targetUserId = empNo
            )
        }

        var applied: Pair<Int, Int>? = null
        if (deptChanged) applied = moveDept(principal, empNo, targetNm, currentDeptId, targetDept, MenuId.SYS_ACCOUNT)
        val grants = if (movingToUnassigned) {
            systemUserRepository.findUserGrants(listOf(empNo))[empNo] ?: emptyList()
        } else {
            applyExtraMenus(empNo, targetNm, requestedMenus, principal.userId, validateGrantReasons(request.extraMenuReasons))
        }
        val passwordChanged = newPasswordHash != null
        if (newPasswordHash != null) {
            systemUserRepository.updatePasswordHash(empNo, newPasswordHash, principal.userId)
            // 관리자가 정해 준 비밀번호는 본인이 첫 로그인 때 바꿔야 한다(R-04, 01 ACC-03)
            systemUserRepository.updatePwdChangeRequired(empNo, true, principal.userId)
            // 비밀번호 값은 어디에도 남기지 않는다 — 바뀌었다는 사실과 누가 바꿨는지만.
            val permAuditId = auditLogService.record(
                logType = AuditType.ACCOUNT_SEC, menuId = MenuId.SYS_ACCOUNT,
                targetDesc = "비밀번호 변경 [$empNo]", remark = "관리자 변경 by ${principal.userId}"
            )
            auditLogService.recordPermChange(
                actCd = "ACCOUNT", targetKindCd = "USER", targetNm = targetNm,
                detail = "비밀번호 변경(관리자 ${principal.userId})", targetUserId = empNo,
                auditId = permAuditId
            )
            log.info("비밀번호 변경(관리자) : empNo={} by={}", empNo, principal.userId)
        }
        return mapOf(
            "success" to true, "empNo" to empNo, "extraMenuIds" to grants, "passwordChanged" to passwordChanged,
            "appliedMenuCnt" to applied?.first, "appliedDataCnt" to applied?.second
        )
    }

    /** 계정 삭제 (No.131) */
    @Transactional
    fun deleteUser(empNo: String): Map<String, Any?> {
        val principal = authorizationService.requireWrite(MenuId.SYS_ACCOUNT)
        requireUser(empNo)

        // 로그인 중인 본인 계정은 삭제할 수 없다. (E-RULE-001)
        if (empNo == principal.userId) {
            throw BusinessRuleException("로그인 중인 본인 계정은 삭제할 수 없습니다.")
        }

        // 막는 참조가 있으면 DB 오류(500) 전에 409 와 건수 (01 ACC-11)
        val check = deleteCheckOf(empNo, principal)
        if (check["deletable"] != true) {
            @Suppress("UNCHECKED_CAST")
            val b = check["blocking"] as Map<String, Long>
            throw BusinessException(
                ErrorCode.RULE_VIOLATION,
                "다른 기록이 이 계정을 참조하고 있어 삭제할 수 없습니다. [서빙 프로필 활성화 ${b["servingProfiles"]}건 · 문서 작성 ${b["docs"]}건] 삭제 대신 정지하십시오.",
                null, check
            )
        }
        @Suppress("UNCHECKED_CAST")
        val cascade = check["cascade"] as Map<String, Long>
        val nm = systemUserRepository.findUserByEmpNo(empNo)?.get("name")
        systemUserRepository.deleteUser(empNo)

        auditLogService.recordPermChange(
            actCd = "ACCOUNT",
            targetKindCd = "USER",
            targetNm = "$nm($empNo)",
            detail = "계정 삭제 — 알림 수신자 ${cascade["recipients"]}건 · 추가 허용 화면 ${cascade["menuGrants"]}건 · 화면 사용 기록 ${cascade["usage"]}건 함께 삭제",
            targetUserId = empNo
        )

        log.info("계정 삭제 : empNo={} by={}", empNo, principal.userId)
        return mapOf("success" to true)
    }

    /**
     * 계정 삭제 사전 확인 (01 ACC-11) — 삭제 모달이 막는 참조·함께 지워지는 참조를 미리 보여 준다. 기록하지 않는다.
     */
    @Transactional(readOnly = true)
    fun getDeleteCheck(empNo: String): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.SYS_ACCOUNT)
        requireUser(empNo)
        return deleteCheckOf(empNo, principal)
    }

    private fun deleteCheckOf(empNo: String, principal: UserPrincipal): Map<String, Any?> {
        val refs = systemUserRepository.findUserDeleteRefs(empNo)
        val blocking = mapOf("servingProfiles" to (refs["servingProfiles"] ?: 0L), "docs" to (refs["docs"] ?: 0L))
        val self = empNo == principal.userId
        return mapOf(
            "empNo" to empNo,
            "deletable" to (!self && blocking.values.all { it == 0L }),
            "self" to self,
            "blocking" to blocking,
            "cascade" to mapOf("recipients" to (refs["recipients"] ?: 0L), "menuGrants" to (refs["menuGrants"] ?: 0L), "usage" to (refs["usage"] ?: 0L)),
            // GROUPWARE 면 화면이 「다음 동기화에서 다시 가입되지 않습니다」 를 알린다
            "joinSrc" to refs["joinSrc"]
        )
    }

    /**
     * 계정 사용/정지 (No.132)
     *
     * - `LOCKED` 로 바꾸는 요청은 400 — 잠금은 로그인 실패로만 생긴다(09 AUD-16).
     * - 잠긴 계정을 `ACTIVE` 로 → **관리자 잠금 해제**: 실패 횟수 0, 첫 로그인 비밀번호 변경 요구(Y) (01 ACC-05).
     *   `resetPassword=true` 면 비밀번호도 초기 규칙값으로 되돌린다(관리자 비밀번호 변경 권한 규칙 적용).
     * - 정지 해제(`SUSPENDED → ACTIVE`)도 정지 중 쌓인 실패 횟수를 0 으로 정리한다.
     *
     * @param state         바꿀 상태
     * @param resetPassword 잠금 해제 때 비밀번호도 초기화할지 (기본 false)
     */
    @Transactional
    fun changeUserState(empNo: String, state: String?, resetPassword: Boolean = false, reason: String? = null): Map<String, Any?> {
        val principal = authorizationService.requireWrite(MenuId.SYS_ACCOUNT)
        requireUser(empNo)

        val cleanReason = reason?.trim()?.ifEmpty { null }
        if (cleanReason != null && cleanReason.length > 200) {
            throw InvalidParameterException("사유는 200자 이내여야 합니다.", "reason")
        }

        // 상태를 빼고 부르면 예전에는 ACTIVE 로 간주했다. 그래서 body 를 비운 채 '정지' 를
        // 눌러도 200 + "변경되었습니다" 가 나가면서 실제로는 계정이 활성화됐다.
        // 무엇을 바꾸려는지 지정하지 않은 요청은 성공으로 처리하지 않는다.
        val requested = state?.trim()?.takeIf { it.isNotBlank() }
            ?: throw InvalidParameterException("변경할 계정 상태를 지정해 주세요.", "state")

        val stateCd = assertAssignableState(systemUserRepository.normalizeUserState(requested))

        // 본인 계정을 ACTIVE 에서 내리면 스스로 로그인할 수 없게 된다.
        // 로그인 판정이 'ACTIVE 인가' 이므로 SUSPENDED 뿐 아니라 PENDING 도 같은 결과다.
        if (stateCd != "ACTIVE" && empNo == principal.userId) {
            throw BusinessRuleException("로그인 중인 본인 계정은 사용 상태에서 내릴 수 없습니다.")
        }

        val before = systemUserRepository.findUserStateCd(empNo)
        // 승인 대기 계정은 이 경로로 바꾸지 않는다 — 승인·반려 이력이 남지 않기 때문이다(01 ACC-07)
        if (before == "PENDING") {
            throw BusinessRuleException("승인 대기 계정은 가입 승인·반려로 처리하십시오.")
        }

        // 관리자 잠금 해제 (LOCKED → ACTIVE)
        if (before == "LOCKED" && stateCd == "ACTIVE") {
            return unlockByAdmin(empNo, principal, resetPassword)
        }

        systemUserRepository.updateUser(empNo, null, null, null, stateCd, null, principal.userId)
        if (before == "SUSPENDED" && stateCd == "ACTIVE") {
            systemUserRepository.resetLoginFailCount(empNo, principal.userId)
        }
        // 정지 사유는 비고에 날짜와 함께 남긴다 — 형식 `[yyyy-MM-dd 정지] 사유` (01 ACC-05)
        if (stateCd == "SUSPENDED" && cleanReason != null) {
            systemUserRepository.appendRemark(empNo, "[${java.time.LocalDate.now(KST)} 정지] $cleanReason", principal.userId)
        }

        auditLogService.recordPermChange(
            actCd = "ACCOUNT",
            targetKindCd = "USER",
            targetNm = displayNm(empNo),
            detail = "계정 상태 변경 ${before ?: "-"} → $stateCd" + (cleanReason?.let { " · 사유: $it" } ?: ""),
            targetUserId = empNo
        )

        val after = systemUserRepository.findUserByEmpNo(empNo)
        return mapOf(
            "success" to true, "state" to stateCd, "unlocked" to false,
            "pwdChangeRequired" to (after?.get("pwdChangeRequired") ?: false)
        )
    }

    /**
     * 관리자 잠금 해제 — 조건부 UPDATE 라 본인 이메일 해제와 동시에 와도 한 쪽만 성공한다.
     * 해제 후 첫 로그인에서 비밀번호를 바꾸게 한다(5회 실패는 대입 시도일 수 있다).
     */
    private fun unlockByAdmin(empNo: String, principal: UserPrincipal, resetPassword: Boolean): Map<String, Any?> {
        // 비밀번호 초기화는 관리자 비밀번호 변경과 같은 권한 규칙 — 저장 전에 판정해 실패 시 아무것도 바뀌지 않게 한다
        val newPasswordHash = if (resetPassword) preparePasswordChange("$empNo$DEFAULT_PASSWORD_SUFFIX", empNo, principal, checkEmpNo = false) else null

        if (systemUserRepository.unlockUserByAdmin(empNo, principal.userId) == 0) {
            throw BusinessRuleException("이미 잠금이 해제된 계정입니다. 목록을 새로 불러와 확인하십시오.")
        }
        if (newPasswordHash != null) {
            systemUserRepository.updatePasswordHash(empNo, newPasswordHash, principal.userId)
            systemUserRepository.updatePwdChangeRequired(empNo, true, principal.userId)
        }

        val permAuditId = auditLogService.record(
            logType = AuditType.ACCOUNT_SEC, menuId = MenuId.SYS_ACCOUNT,
            targetDesc = "잠금 해제(관리자) [$empNo]", remark = if (resetPassword) "비밀번호 초기화 포함" else null
        )
        auditLogService.recordPermChange(
            actCd = "ACCOUNT", targetKindCd = "USER", targetNm = displayNm(empNo),
            detail = "잠금 해제(관리자) LOCKED → ACTIVE" + if (resetPassword) " · 비밀번호 초기화" else "",
            targetUserId = empNo,
            auditId = permAuditId
        )
        log.info("관리자 잠금 해제 : empNo={} resetPassword={} by={}", empNo, resetPassword, principal.userId)
        return mapOf(
            "success" to true, "state" to "ACTIVE", "loginFailCnt" to 0,
            "pwdChangeRequired" to true, "unlocked" to true, "passwordReset" to resetPassword
        )
    }

    /** 관리자가 직접 지정할 수 없는 상태를 거른다 — 잠금은 로그인 실패로만 생긴다(01 ACC-05, 09 AUD-16) */
    private fun assertAssignableState(stateCd: String): String {
        if (stateCd == "LOCKED") {
            throw InvalidParameterException("잠김 상태는 로그인 실패로만 바뀝니다. 정지하려면 SUSPENDED 를 지정하십시오.", "state")
        }
        return stateCd
    }

    /** 계정 부서 이동 (No.133) */
    @Transactional
    fun changeUserDept(empNo: String, deptId: Int): Map<String, Any?> {
        // 계정 관리 화면 외에 그룹웨어 부서 매핑 화면(미배정 계정 한 명씩 옮기기)도 이 API 를 쓴다 (2026-09-30)
        // 두 화면 중 하나의 쓰기 권한(R-06, 01 ACC-15 · 02 GWD-14)
        val principal = authorizationService.requireAnyWrite(MenuId.SYS_ACCOUNT, MenuId.SYS_GW_DEPT)
        requireUser(empNo)

        val dept = systemUserRepository.findDept(deptId)
            ?: throw ResourceNotFoundException("부서를 찾을 수 없습니다. [deptId=$deptId]")
        val currentDeptId = systemUserRepository.findUserDeptId(empNo)

        // 본인 부서 변경은 409 — 통합관리자 부서 배정 403 보다 먼저(01 ACC-02)
        assertNotSelfPermChange(principal, empNo, currentDeptId, dept, false)
        // 통합관리자 부서로의 배정은 통합관리자만 — 경로와 무관하게 먼저 본다(CMN-01, 02 GWD-02: 403 E-AUTH-002)
        systemDeptGuard.assertCanAssignDept(principal, dept)
        val movingToUnassigned = currentDeptId != deptId && systemDeptGuard.isUnassignedDept(dept)
        if (movingToUnassigned) systemDeptGuard.assertCanGrantAdminScreen(principal, systemUserRepository.findUserGrantIds(empNo))

        // 계정 관리 쓰기 권한 없이 그룹웨어 부서 매핑 쓰기 권한으로만 들어오면 미배정 부서 계정만 옮긴다
        val viaGwDept = !principal.canWriteMenu(MenuId.SYS_ACCOUNT)
        if (viaGwDept) {
            val unassignedId = systemUserRepository.findDeptIdByName(appProperties.unassignedDeptName)
            if (unassignedId == null || systemUserRepository.findUserDeptId(empNo) != unassignedId) {
                throw MenuAccessDeniedException(MenuId.SYS_ACCOUNT)
            }
        }

        val (appliedMenuCnt, appliedDataCnt) = moveDept(principal, empNo, displayNm(empNo), currentDeptId, dept,
            if (viaGwDept) MenuId.SYS_GW_DEPT else MenuId.SYS_ACCOUNT)

        return mapOf("success" to true, "appliedMenuCnt" to appliedMenuCnt, "appliedDataCnt" to appliedDataCnt)
    }

    /**
     * 부서 이동 한 곳 (01 ACC-06) — 계정 수정·부서 이동·가입 승인·그룹웨어 재배정이 모두 이 함수로 옮긴다.
     *
     * 검사(본인 409 · 통합관리자 부서 403 · 관리 화면 회수 403)는 호출부가 **쓰기 전에** 끝내 둔다.
     * 여기서는 부서 변경 → (실부서 → 미배정이면) 추가 허용 회수 → 상속 권한 수 → 이력을 맡는다.
     *
     * @param targetNm 이력에 남길 대상 표기 「이름(사번)」
     * @param counts   상속 권한 수를 이미 셌으면 넘긴다(재배정은 부서별로 한 번만 센다)
     * @return 상속 메뉴 수, 상속 데이터 항목 수
     */
    fun moveDept(
        principal: UserPrincipal, empNo: String, targetNm: String, currentDeptId: Int?, dept: Map<String, Any?>, menuId: String,
        counts: Pair<Int, Int>? = null
    ): Pair<Int, Int> {
        val deptId = dept["deptId"] as Int
        systemUserRepository.updateUserDept(empNo, deptId, principal.userId)
        if (currentDeptId != deptId && systemDeptGuard.isUnassignedDept(dept)) {
            revokeGrantsOnUnassignedMove(empNo, targetNm, principal.userId)
        }
        val applied = counts ?: appliedCounts(dept)
        recordDeptMove(empNo, targetNm, dept, applied.first, applied.second, menuId)
        return applied
    }

    /**
     * 부서가 실제로 주는 권한 수 — 통합관리자는 사용 중 전 화면·전 항목, 미배정은 고정 화면 수·0,
     * 그 밖에는 사용 중 화면·항목의 허용 행 수 (메뉴 권한 매트릭스와 같은 기준)
     */
    fun appliedCounts(dept: Map<String, Any?>): Pair<Int, Int> = when {
        systemDeptGuard.isSuperAdminDept(dept) ->
            systemUserRepository.findAllMenus().size to systemUserRepository.findDataFields().size
        systemDeptGuard.isUnassignedDept(dept) ->
            systemUserRepository.findAllMenus().count { it["id"] in MenuId.UNASSIGNED_SCREENS } to 0
        else -> {
            val deptId = dept["deptId"] as Int
            systemUserRepository.countMenuPerm(deptId) to systemUserRepository.countDataPerm(deptId)
        }
    }

    /** 이력 대상 표기 「이름(사번)」 — 계정이 없으면 사번만 */
    private fun displayNm(empNo: String): String =
        systemUserRepository.findUserByEmpNo(empNo)?.get("name")?.let { "$it($empNo)" } ?: empNo

    /**
     * 계정 부서 이동의 이력 기록 — 권한 변경 이력(ACCOUNT)과 감사 로그.
     * 그룹웨어 부서 매핑 화면의 일괄 재배정도 같은 형식으로 남긴다.
     *
     * @param targetNm 대상 표기 「이름(사번)」
     */
    fun recordDeptMove(empNo: String, targetNm: String, dept: Map<String, Any?>, appliedMenuCnt: Int, appliedDataCnt: Int, menuId: String) {
        val deptId = dept["deptId"] as Int
        val permAuditId = auditLogService.record(
            logType = AuditType.PERM_CHANGE,
            menuId = menuId,
            targetDesc = "계정 부서 이동 [$empNo → ${dept["deptNm"]}]",
            remark = "메뉴 ${appliedMenuCnt}건 · 데이터 ${appliedDataCnt}건"
        )
        auditLogService.recordPermChange(
            actCd = "ACCOUNT",
            targetKindCd = "USER",
            targetNm = targetNm,
            detail = "부서 이동 → ${dept["deptNm"]} (메뉴 ${appliedMenuCnt}건 · 데이터 ${appliedDataCnt}건 상속)",
            targetDeptId = deptId,
            targetUserId = empNo,
            auditId = permAuditId
        )
    }

    // =================================================================================
    // 부서 관리
    // =================================================================================

    /** 부서 목록 조회 (No.135) */
    @Transactional(readOnly = true)
    fun getDepts(): Map<String, Any?> {
        authorizationService.requireAnyMenu(MenuId.SYS_ACCOUNT, MenuId.SYS_MENU, MenuId.SYS_DATA, MenuId.SYS_GW_DEPT)
        return mapOf("items" to withSystemRole(systemUserRepository.findDepts()))
    }

    /**
     * 부서 행에 시스템 부서 표시를 붙인다(01 ACC-04·ACC-14) — `systemRole`, 미배정이면 고정 권한 `lockedPerms`·`fixedMenus`·`fixedDataFields`.
     */
    private fun withSystemRole(rows: List<Map<String, Any?>>): List<Map<String, Any?>> = rows.map { row ->
        val role = systemDeptGuard.systemRoleOf(row)
        val extra = mutableMapOf<String, Any?>("systemRole" to role, "lockedPerms" to (role != null))
        if (role == "UNASSIGNED") {
            extra["fixedMenus"] = MenuId.UNASSIGNED_SCREENS.toList()
            extra["fixedDataFields"] = emptyList<String>()
        }
        row + extra
    }

    /**
     * 부서 목록 — 키워드(부서명·약칭·설명)와 쪽 나눔 (2026-09-13 WEB 요청).
     * `page`·`size` 가 없으면 전량(기존 선택지 호출 호환)이고 meta 는 null 이다. `size=0` 이면 page 와 무관하게 전량이고 meta 는 전량 표시다.
     */
    @Transactional(readOnly = true)
    fun getDepts(keyword: String?, page: Int?, size: Int?): Pair<List<Map<String, Any?>>, PageMeta?> {
        // 그룹웨어 부서 매핑 화면도 부서 선택지로 쓴다 (2026-09-30)
        authorizationService.requireAnyMenu(MenuId.SYS_ACCOUNT, MenuId.SYS_MENU, MenuId.SYS_DATA, MenuId.SYS_GW_DEPT)
        // size=0 이면 page 와 무관하게 전량 — 다른 목록(size=0)과 같은 규칙, meta 는 전량 표시 (01 4단계 메인 15-6)
        if (size != null && size <= 0) {
            val rows = withSystemRole(systemUserRepository.findDepts(keyword, null, 0))
            return rows to PageMeta.all(rows.size.toLong())
        }
        val paged = page != null || size != null
        if (!paged) return withSystemRole(systemUserRepository.findDepts(keyword, null, 0)) to null
        val paging = PageRequestParam.of(page, size)
        val total = systemUserRepository.countDepts(keyword)
        val rows = withSystemRole(systemUserRepository.findDepts(keyword, paging.limit, paging.offset))
        return rows to PageMeta.of(paging.page, paging.size, total)
    }

    /** 부서별 권한 비교 (No.134) / 부서별 적용 현황 (No.144) */
    @Transactional(readOnly = true)
    fun getDeptPermStatus(): Map<String, Any?> {
        authorizationService.requireAnyMenu(MenuId.SYS_MENU, MenuId.SYS_DATA, MenuId.SYS_ACCOUNT)

        val items = systemUserRepository.findDepts().map {
            mapOf(
                "deptId" to it["deptId"],
                "deptNm" to it["deptNm"],
                "menuCnt" to it["menuCnt"],
                "dataCnt" to it["dataCnt"],
                "userCnt" to it["userCnt"]
            )
        }
        return mapOf("items" to items)
    }

    /** 부서 등록 (No.136) */
    @Transactional
    fun createDept(request: DeptSaveRequest): Map<String, Any?> {
        val principal = authorizationService.requireWrite(MenuId.SYS_ACCOUNT)

        val deptNm = request.deptNm?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw InvalidParameterException("부서명을 입력해 주세요.", "deptNm")
        val abbr = request.abbr?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw InvalidParameterException("부서 약칭을 입력해 주세요.", "abbr")
        checkDeptFields(deptNm, abbr, request.desc)

        if (systemUserRepository.existsDeptName(deptNm, abbr, null)) {
            throw DuplicatedValueException("이미 등록된 부서명 또는 약칭입니다.", "deptNm")
        }

        val deptId = systemUserRepository.insertDept(deptNm, abbr, request.desc, request.plantCd, principal.userId)

        // 초기 권한을 지정 부서에서 복사한다.
        var copiedCnt = 0
        request.initPermFrom?.let { fromDeptId ->
            // 복사 화면과 같은 규칙(03 MNP-01) — 미배정 원본 409, 통합관리자 원본은 사용 중 전 화면
            val fromDept = requireActiveDept(fromDeptId)
            if (systemDeptGuard.isUnassignedDept(fromDept)) throw BusinessRuleException(SystemDeptGuard.MSG_UNASSIGNED_COPY)
            val plan = planMenuCopy(fromDept, deptId)
            systemDeptGuard.assertCanGrantAdminScreen(principal, plan.adminChanged)
            copiedCnt = systemUserRepository.replaceMenuPerms(deptId, plan.src, principal.userId)
        }
        // 전사 공통 화면(질의 이력·용어 사전 조회)은 복사 여부와 관계없이 조회 권한을 준다 — 복사로 이미 있는 행의 쓰기 칸은 그대로
        val defaults = if (systemDeptGuard.isUnassignedDeptName(deptNm)) MenuId.DEFAULT_READ_MENUS - MenuId.GLOSS_VIEW
            else MenuId.DEFAULT_READ_MENUS
        defaults.forEach { systemUserRepository.upsertMenuPerm(deptId, it, true, principal.userId) }

        auditLogService.recordPermChange(
            actCd = "DEPT",
            targetKindCd = "DEPT",
            targetNm = deptNm,
            detail = "부서 등록 — 약칭=$abbr, 초기 권한 복사=${copiedCnt}건, 기본 조회 화면=${defaults.joinToString(",")}",
            targetDeptId = deptId
        )

        return mapOf("deptId" to deptId, "copiedMenuCnt" to copiedCnt, "defaultMenuCnt" to defaults.size)
    }

    /** 길이 상한 — 넘으면 말없이 자르지 않고 400 (01 ACC-12) */
    private fun checkMax(value: String, max: Int, label: String, field: String) {
        if (value.length > max) throw InvalidParameterException("$label ${max}자 이내여야 합니다.", field)
    }

    /** 부서명 50자 · 약칭 1~4자 · 설명 200자 (01 ACC-12, 컬럼 길이와 같다) */
    private fun checkDeptFields(deptNm: String?, abbr: String?, desc: String?) {
        deptNm?.let { checkMax(it, 50, "부서명은", "deptNm") }
        abbr?.let { checkMax(it, 4, "부서 약칭은", "abbr") }
        desc?.trim()?.let { checkMax(it, 200, "설명은", "desc") }
    }

    /** 부서 수정 (No.137) */
    @Transactional
    fun updateDept(deptId: Int, request: DeptSaveRequest): Map<String, Any?> {
        val principal = authorizationService.requireWrite(MenuId.SYS_ACCOUNT)
        val dept = systemUserRepository.findDept(deptId)
            ?: throw ResourceNotFoundException("부서를 찾을 수 없습니다. [deptId=$deptId]")
        // 미배정·통합관리자 부서의 이름은 바꿀 수 없다 — 자동 가입과 권한 판정이 이름으로 찾는다(CMN-01). 약칭·설명은 바꿀 수 있다.
        systemDeptGuard.assertSystemDeptImmutable(dept, newName = request.deptNm?.trim())
        request.deptNm?.let { if (it.isBlank()) throw InvalidParameterException("부서명을 입력해 주세요.", "deptNm") }
        request.abbr?.let { if (it.isBlank()) throw InvalidParameterException("부서 약칭을 입력해 주세요.", "abbr") }
        checkDeptFields(request.deptNm?.trim(), request.abbr?.trim(), request.desc)

        if (systemUserRepository.existsDeptName(request.deptNm?.trim(), request.abbr?.trim(), deptId)) {
            throw DuplicatedValueException("이미 등록된 부서명 또는 약칭입니다.", "deptNm")
        }

        systemUserRepository.updateDept(deptId, request.deptNm?.trim(), request.abbr?.trim(), request.desc, principal.userId)

        auditLogService.recordPermChange(
            actCd = "DEPT",
            targetKindCd = "DEPT",
            targetNm = request.deptNm ?: "$deptId",
            detail = "부서 수정 — 약칭=${request.abbr ?: "-"}",
            targetDeptId = deptId
        )

        return mapOf("success" to true)
    }

    /** 부서 삭제 (No.138) */
    @Transactional
    fun deleteDept(deptId: Int): Map<String, Any?> {
        authorizationService.requireWrite(MenuId.SYS_ACCOUNT)

        val dept = systemUserRepository.findDept(deptId)
            ?: throw ResourceNotFoundException("부서를 찾을 수 없습니다. [deptId=$deptId]")
        // 시스템 부서(미배정·통합관리자)는 삭제할 수 없다(CMN-01)
        systemDeptGuard.assertSystemDeptImmutable(dept, deleting = true)

        // 이 부서를 쓰는 행이 있으면 삭제하지 않는다(E-RULE-001, 01 ACC-04) — FK 가 CASCADE 가 아니라 그대로 지우면 500 이다
        val refs = systemUserRepository.countDeptRefs(deptId)
        if (refs.values.any { it > 0 }) {
            val labels = mapOf(
                "users" to "소속 계정 %d명", "gwDeptMaps" to "그룹웨어 매핑 %d건", "alertRecipGroups" to "알림 수신 그룹 %d건",
                "docs" to "문서 %d건", "metricStds" to "지표 기준 %d건"
            )
            val summary = refs.filter { it.value > 0 }.map { (k, v) -> labels.getValue(k).format(v) }.joinToString(" · ")
            throw BusinessException(
                ErrorCode.RULE_VIOLATION, "다른 설정이 이 부서를 쓰고 있어 삭제할 수 없습니다. $summary", null, mapOf("refs" to refs)
            )
        }

        systemUserRepository.deleteDept(deptId)

        auditLogService.recordPermChange(
            actCd = "DEPT",
            targetKindCd = "DEPT",
            targetNm = dept["deptNm"] as String,
            detail = "부서 삭제",
            targetDeptId = deptId
        )

        return mapOf("success" to true)
    }

    // =================================================================================
    // 메뉴 접근 권한
    // =================================================================================

    /**
     * 메뉴 권한 매트릭스 조회 (No.140, 03 MNP-17)
     *
     * 통합관리자는 전 화면 조회·쓰기, 미배정은 고정 5개 화면 조회만으로 그린다(DB 행이 아니라 상수 — R-01).
     */
    @Transactional(readOnly = true)
    fun getMenuPermMatrix(): Map<String, Any?> {
        // 조회만 계정 관리(SYS_ACCOUNT)에도 열어 준다 — 계정별 추가 허용 화면의 선택지가 이 매트릭스다. 쓰기는 SYS_MENU 그대로.
        val principal = authorizationService.requireAnyMenu(MenuId.SYS_MENU, MenuId.SYS_ACCOUNT)

        val screens = systemUserRepository.findAllMenus().map {
            val id = it["id"] as String
            it + mapOf("admin" to (id in MenuId.ADMIN_SCREENS), "common" to (id in MenuId.COMMON_SCREENS))
        }
        val screenIds = screens.map { it["id"] as String }
        val depts = systemUserRepository.findDepts().map {
            val role = systemDeptGuard.systemRoleOf(it)
            it + mapOf("unassigned" to (role == "UNASSIGNED"), "locked" to role)
        }
        val rows = systemUserRepository.findActiveMenuPermRows()

        val matrix = linkedMapOf<String, List<String>>()
        val writeMatrix = linkedMapOf<String, List<String>>()
        depts.forEach { dept ->
            val deptId = dept["deptId"] as Int
            val mine = rows.filter { it.deptId == deptId && it.read }
            when (dept["locked"]) {
                "SUPER_ADMIN" -> { matrix["$deptId"] = screenIds; writeMatrix["$deptId"] = screenIds }
                "UNASSIGNED" -> {
                    val fixed = screenIds.filter { it in MenuId.UNASSIGNED_SCREENS }
                    if (mine.map { it.menuId }.toSet() != fixed.toSet()) {
                        log.warn("미배정 부서 메뉴 권한 행이 고정 화면과 다릅니다 : db={} fixed={}", mine.map { it.menuId }, fixed)
                    }
                    matrix["$deptId"] = fixed; writeMatrix["$deptId"] = emptyList()
                }
                else -> {
                    matrix["$deptId"] = mine.map { it.menuId }
                    writeMatrix["$deptId"] = mine.filter { it.write }.map { it.menuId }
                }
            }
        }

        val grants = systemUserRepository.findGrantsByMenu()
        val result = linkedMapOf<String, Any?>(
            "screens" to screens, "depts" to depts, "matrix" to matrix, "writeMatrix" to writeMatrix,
            "grantCounts" to grants.mapValues { it.value.size },
            "canEditAdminScreens" to principal.superAdmin,
            "version" to permHash(rows.filter { it.menuId in screenIds })
        )
        // 계정별 이름은 메뉴 권한 화면에만 — 계정 관리 화면은 건수만 본다(03 Q5)
        if (principal.canAccessMenu(MenuId.SYS_MENU)) result["grants"] = grants
        return result
    }

    /** 메뉴 권한 단건 변경 (No.141, 03 MNP-02·16) */
    @Transactional
    fun changeMenuPerm(request: MenuPermRequest): Map<String, Any?> {
        val principal = authorizationService.requireWrite(MenuId.SYS_MENU)
        val dept = requireActiveDept(request.deptId)
        // 시스템 부서(통합관리자·미배정)의 권한은 고정이다(CMN-01, R-01) · 관리 화면 4종은 통합관리자만(R-07)
        systemDeptGuard.assertNotSystemDeptPerm(dept)
        val screen = systemUserRepository.findAllMenus().firstOrNull { it["id"] == request.screenId }
            ?: throw InvalidParameterException("존재하지 않거나 사용 중지된 화면 ID 입니다. [${request.screenId}]", "screenId")
        val perm = requirePerm(request.perm)
        val isAction = screen["kind"] == "ACTION"

        val before = systemUserRepository.findMenuPerm(request.deptId, request.screenId)
        val expected = expectedAfter(before, request.allowed, perm, isAction)
        val changed = (before?.read ?: false) != expected.first || (before?.write ?: false) != expected.second
        if (changed) systemDeptGuard.assertCanGrantAdminScreen(principal, listOf(request.screenId))

        systemUserRepository.applyMenuPerm(request.deptId, request.screenId, request.allowed, perm, isAction, principal.userId)
        val after = systemUserRepository.findMenuPerm(request.deptId, request.screenId)

        if (changed) {
            val label = "${request.screenId}(${if (perm == "WRITE") "쓰기" else "조회"})"
            val permAuditId = auditLogService.record(
                logType = AuditType.PERM_CHANGE,
                menuId = MenuId.SYS_MENU,
                targetDesc = "메뉴 권한 변경 [${dept["deptNm"]} / ${request.screenId}]",
                remark = "${if (request.allowed) "부여" else "회수"} $label"
            )
            auditLogService.recordPermChange(
                actCd = "MENU_PERM",
                targetKindCd = "MENU",
                targetNm = "${dept["deptNm"]} / ${screen["name"]}(${request.screenId})",
                detail = "${if (request.allowed) "부여" else "회수"} [$label]",
                targetDeptId = request.deptId,
                auditId = permAuditId
            )
        }

        return mapOf("success" to true, "read" to (after?.read ?: false), "write" to (after?.write ?: false), "changed" to changed)
    }

    /** 메뉴 권한 그룹 일괄 변경 (No.142, 03 MNP-04) */
    @Transactional
    fun changeMenuPermByGroup(request: MenuPermGroupRequest): Map<String, Any?> {
        val principal = authorizationService.requireWrite(MenuId.SYS_MENU)
        val dept = requireActiveDept(request.deptId)
        systemDeptGuard.assertNotSystemDeptPerm(dept)
        val raw = (request.groupId ?: request.groupNm)?.trim()?.ifEmpty { null }
            ?: throw InvalidParameterException("메뉴 그룹을 선택해 주세요.", "groupId")
        val groupId = systemUserRepository.findMenuGroupId(raw)
            ?: throw InvalidParameterException("메뉴 그룹을 찾을 수 없습니다. [$raw]", "groupId")
        val perm = requirePerm(request.perm)

        val screens = systemUserRepository.findAllMenus()
            .filter { it["groupId"] == groupId && (request.includeActions || it["kind"] != "ACTION") }
        // 그룹에 관리 화면이 있으면 요청 전체를 거부한다(부분 적용으로 혼동하지 않게, R-07)
        systemDeptGuard.assertCanGrantAdminScreen(principal, screens.map { it["id"] as String })

        val added = mutableListOf<String>()
        val removed = mutableListOf<String>()
        screens.forEach { sc ->
            val id = sc["id"] as String
            val isAction = sc["kind"] == "ACTION"
            val before = systemUserRepository.findMenuPerm(request.deptId, id)
            val exp = expectedAfter(before, request.allowed, perm, isAction)
            val beforeOn = if (perm == "WRITE") before?.write ?: false else before?.read ?: false
            val afterOn = if (perm == "WRITE") exp.second else exp.first
            if (beforeOn == afterOn && (before?.read ?: false) == exp.first) return@forEach
            systemUserRepository.applyMenuPerm(request.deptId, id, request.allowed, perm, isAction, principal.userId)
            if (request.allowed) added += id else removed += id
        }
        val changedCnt = added.size + removed.size
        // 바뀐 칸이 없으면 이력을 남기지 않는다 — 단건 변경과 같은 규칙(03 MNP-04)
        if (changedCnt == 0) return mapOf("changedCnt" to 0, "added" to added, "removed" to removed)

        val permAuditId = auditLogService.record(
            logType = AuditType.PERM_CHANGE, menuId = MenuId.SYS_MENU,
            targetDesc = "메뉴 권한 그룹 변경 [${dept["deptNm"]} / $groupId]", remark = "${changedCnt}건"
        )
        auditLogService.recordPermChange(
            actCd = "MENU_PERM",
            targetKindCd = "MENU",
            targetNm = "${dept["deptNm"]} / 그룹 $groupId",
            detail = "그룹 일괄 ${if (request.allowed) "부여" else "회수"}(${if (perm == "WRITE") "쓰기" else "조회"}) ${changedCnt}건" +
                (added + removed).takeIf { it.isNotEmpty() }?.let { " [${it.joinToString(", ")}]" }.orEmpty(),
            targetDeptId = request.deptId,
            auditId = permAuditId
        )

        return mapOf("changedCnt" to changedCnt, "added" to added, "removed" to removed)
    }

    /**
     * 부서 메뉴 권한 복사 (No.143, 03 MNP-01)
     *
     * `dryRun=true` 면 저장 없이 바뀔 내용과 해시를 돌려준다(조회 권한). 실행 때 그 해시를 보내면
     * 미리보기 이후 두 부서 권한이 바뀌었는지 확인해 409 로 막는다. 원본이 통합관리자면 사용 중 전 화면 조회·쓰기다.
     */
    @Transactional
    fun copyMenuPerms(request: MenuPermCopyRequest): Map<String, Any?> {
        val principal = if (request.dryRun) authorizationService.requireMenu(MenuId.SYS_MENU)
            else authorizationService.requireWrite(MenuId.SYS_MENU)

        val fromDept = requireActiveDept(request.fromDeptId)
        val toDept = requireActiveDept(request.toDeptId)
        if (request.fromDeptId == request.toDeptId) {
            throw BusinessRuleException("원본 부서와 대상 부서가 같습니다.")
        }
        systemDeptGuard.assertNotSuperAdminDept(toDept, "복사")
        if (systemDeptGuard.isUnassignedDept(fromDept) || systemDeptGuard.isUnassignedDept(toDept)) {
            throw BusinessRuleException(SystemDeptGuard.MSG_UNASSIGNED_COPY)
        }

        val plan = planMenuCopy(fromDept, request.toDeptId)
        if (request.dryRun) {
            return mapOf(
                "dryRun" to true,
                "from" to mapOf("deptId" to request.fromDeptId, "deptNm" to fromDept["deptNm"]),
                "to" to mapOf("deptId" to request.toDeptId, "deptNm" to toDept["deptNm"],
                    "userCnt" to systemUserRepository.countUsersInDept(request.toDeptId)),
                "added" to plan.added.map { (id, w) -> mapOf("id" to id, "read" to true, "write" to w) },
                "removed" to plan.removed.map { mapOf("id" to it) },
                "adminScreensChanged" to plan.adminChanged,
                "requiresSuperAdmin" to plan.adminChanged.isNotEmpty(),
                "expectedHash" to plan.hash
            )
        }

        when {
            request.expectedHash.isNullOrBlank() ->
                log.warn("미리보기 없이 메뉴 권한 복사 : from={} to={} by={}", request.fromDeptId, request.toDeptId, principal.userId)
            request.expectedHash != plan.hash ->
                throw BusinessRuleException("미리보기 이후 권한이 바뀌었습니다. 미리보기를 다시 실행하세요.")
        }
        systemDeptGuard.assertCanGrantAdminScreen(principal, plan.adminChanged)

        val copiedCnt = systemUserRepository.replaceMenuPerms(request.toDeptId, plan.src, principal.userId)

        val detail = "메뉴 권한 복사 (${fromDept["deptNm"]} → ${toDept["deptNm"]}) 부여 [" +
            plan.added.entries.joinToString(", ") { "${it.key}(${if (it.value) "쓰기" else "조회"})" } + "] · 회수 [" + plan.removed.joinToString(", ") + "]"
        val permAuditId = auditLogService.record(
            logType = AuditType.PERM_CHANGE, menuId = MenuId.SYS_MENU,
            targetDesc = "메뉴 권한 복사 [${fromDept["deptNm"]} → ${toDept["deptNm"]}]",
            remark = "부여 ${plan.added.size}건 · 회수 ${plan.removed.size}건"
        )
        auditLogService.recordPermChange(
            actCd = "MENU_PERM",
            targetKindCd = "DEPT",
            targetNm = "${toDept["deptNm"]}",
            detail = if (detail.length > 500) detail.take(480) + " 외 ${plan.added.size + plan.removed.size}건" else detail,
            targetDeptId = request.toDeptId,
            auditId = permAuditId
        )

        return mapOf("copiedCnt" to copiedCnt, "added" to plan.added.keys.toList(), "removed" to plan.removed)
    }

    /** 복사 계획 — 원본 집합, 바뀌는 칸, 관리 화면 변화, 두 부서 해시 */
    private data class MenuCopyPlan(
        val src: Map<String, Boolean>,
        val added: Map<String, Boolean>,
        val removed: List<String>,
        val adminChanged: List<String>,
        val hash: String
    )

    private fun planMenuCopy(fromDept: Map<String, Any?>, toDeptId: Int): MenuCopyPlan {
        val fromDeptId = fromDept["deptId"] as Int
        val screenIds = systemUserRepository.findAllMenus().map { it["id"] as String }
        val rows = systemUserRepository.findActiveMenuPermRows().filter { it.menuId in screenIds }
        val src: Map<String, Boolean> = if (systemDeptGuard.isSuperAdminDept(fromDept)) {
            screenIds.associateWith { true }
        } else {
            rows.filter { it.deptId == fromDeptId && it.read }.associate { it.menuId to it.write }
        }
        val dst = rows.filter { it.deptId == toDeptId && it.read }.associate { it.menuId to it.write }
        val added = src.filter { (id, w) -> dst[id] != w }
        val removed = dst.keys.filter { it !in src }
        val adminChanged = (added.keys + removed).filter { it in MenuId.ADMIN_SCREENS }.distinct().sorted()
        // 원본이 통합관리자면 행이 없으므로 부서 ID + 사용 중 화면 목록으로 해시한다
        val fromPart = if (systemDeptGuard.isSuperAdminDept(fromDept)) "SUPER|$fromDeptId|" + screenIds.sorted().joinToString(",")
            else permHash(rows.filter { it.deptId == fromDeptId })
        return MenuCopyPlan(src, added, removed, adminChanged, sha256("$fromPart#" + permHash(rows.filter { it.deptId == toDeptId })))
    }

    /** 권한 행 집합의 해시 — `deptId|menuId|read|write` 정렬 후 SHA-256 hex */
    private fun permHash(rows: List<com.dwje.api.repository.MenuPermRow>): String =
        sha256(rows.map { "${it.deptId}|${it.menuId}|${it.read}|${it.write}" }.sorted().joinToString("\n"))

    private fun sha256(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /** perm 값 검증 — READ | WRITE */
    private fun requirePerm(perm: String?): String {
        val p = perm?.trim()?.uppercase().orEmpty().ifEmpty { "READ" }
        if (p != "READ" && p != "WRITE") throw InvalidParameterException("권한 구분은 READ 또는 WRITE 입니다.", "perm")
        return p
    }

    /** 칸 변경 후 예상 값(조회, 쓰기) — [SystemUserRepository.applyMenuPerm] 규칙과 같다 */
    private fun expectedAfter(before: com.dwje.api.repository.MenuPermRow?, allowed: Boolean, perm: String, isAction: Boolean): Pair<Boolean, Boolean> =
        when {
            perm == "READ" && allowed -> true to ((before?.write ?: false) || isAction)
            perm == "READ" -> false to false
            allowed -> true to true
            else -> (before?.read ?: false) to false
        }

    // =================================================================================
    // 데이터 접근 권한
    // =================================================================================

    /** 데이터 항목 목록 (No.145) */
    @Transactional(readOnly = true)
    fun getDataFields(): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.SYS_DATA)
        return mapOf("items" to withAttrDetails(systemUserRepository.findDataFields()), "reservedAttrs" to DataFieldService.RESERVED_ATTRS.toList())
    }

    /** 항목 행에 `builtIn`(기본 7종)과 `attrDetails[{attrName, remark}]` 를 붙인다 (04 DTP-01·02) */
    private fun withAttrDetails(fields: List<Map<String, Any?>>): List<Map<String, Any?>> {
        val details = systemUserRepository.findAttrDetails()
        return fields.map {
            it + mapOf("builtIn" to (it["key"] in DataField.ALL), "attrDetails" to details[it["key"]].orEmpty())
        }
    }

    /** 데이터 권한 매트릭스 조회 (No.146) */
    @Transactional(readOnly = true)
    fun getDataPermMatrix(): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.SYS_DATA)

        val fields = withAttrDetails(systemUserRepository.findDataFields())
        val depts = systemUserRepository.findDepts().map {
            val role = systemDeptGuard.systemRoleOf(it)
            it + mapOf("unassigned" to (role == "UNASSIGNED"), "locked" to role)
        }
        val perms = systemUserRepository.findDataPermMatrix()

        // 통합관리자 = 전 항목, 미배정 = 0건(DB 행과 무관하게 고정, 04 DTP-16)
        val matrix = depts.associate { dept ->
            val deptId = dept["deptId"] as Int
            val allowed = when (dept["locked"]) {
                "SUPER_ADMIN" -> fields.map { it["key"] as String }
                "UNASSIGNED" -> emptyList()
                else -> perms.filter { it["deptId"] == deptId }.map { it["fieldKey"] as String }
            }
            deptId.toString() to allowed
        }

        return mapOf("fields" to fields, "depts" to depts, "matrix" to matrix, "reservedAttrs" to DataFieldService.RESERVED_ATTRS.toList())
    }

    /** 데이터 권한 변경 (No.147) */
    @Transactional
    fun changeDataPerm(request: DataPermRequest): Map<String, Any?> {
        val principal = authorizationService.requireWrite(MenuId.SYS_DATA)
        val dept = requireActiveDept(request.deptId)
        // 통합관리자는 전 항목, 미배정은 0건으로 고정이다(CMN-01, R-11, 04 DTP-03)
        systemDeptGuard.assertNotSystemDeptDataPerm(dept)

        // 항목은 운영 중에 늘어난다(V33) — 코드 상수가 아니라 항목 표를 본다.
        if (systemUserRepository.findDataFields().none { it["key"] == request.fieldKey }) {
            throw InvalidParameterException("존재하지 않는 데이터 항목입니다. [${request.fieldKey}]", "fieldKey")
        }

        systemUserRepository.upsertDataPerm(request.deptId, request.fieldKey, request.allowed, principal.userId)

        val permAuditId = auditLogService.record(
            logType = AuditType.PERM_CHANGE,
            menuId = MenuId.SYS_DATA,
            fieldKey = request.fieldKey,
            targetDesc = "데이터 권한 변경 [${dept["deptNm"]} / ${request.fieldKey}]",
            remark = if (request.allowed) "부여" else "회수"
        )
        auditLogService.recordPermChange(
            actCd = "DATA_PERM",
            targetKindCd = "FIELD",
            targetNm = "${dept["deptNm"]} / ${request.fieldKey}",
            detail = "데이터 권한 ${if (request.allowed) "부여" else "회수"}",
            targetDeptId = request.deptId,
            auditId = permAuditId
        )

        return mapOf("success" to true)
    }

    /**
     * 적용 미리보기 (No.148)
     *
     * 특정 계정 기준으로 항목별 실제 노출 여부를 보여준다.
     */
    @Transactional(readOnly = true)
    fun previewDataPerm(empNo: String): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.SYS_DATA)

        val target = authorizationService.loadPrincipalForInspection(empNo)
        val fields = systemUserRepository.findDataFields()

        val items = fields.map { f ->
            val key = f["key"] as String
            // 미적용 항목은 권한과 무관하게 가려지지 않는다 — 실제 노출 기준으로 보인다(04 DTP-10)
            val applied = f["applyFlg"] == "Y"
            val masked = applied && !target.canReadField(key)
            mapOf(
                "fieldKey" to key,
                "name" to f["name"],
                "applied" to applied,
                "rendered" to if (masked) "비공개" else "원본 노출",
                "masked" to masked
            )
        }

        return mapOf(
            "empNo" to empNo,
            "name" to target.userName,
            "dept" to target.deptName,
            "items" to items
        )
    }

    /** 계정별 적용 결과 (No.149) */
    @Transactional(readOnly = true)
    fun getDataPermByUser(page: Int?, size: Int?): Pair<List<Map<String, Any?>>, PageMeta> {
        authorizationService.requireMenu(MenuId.SYS_DATA)
        val paging = PageRequestParam.of(page, size)

        val total = systemUserRepository.countUsersAll()
        val allKeys = systemUserRepository.findDataFields().map { it["key"] as String }
        val rows = systemUserRepository.findDataPermByUser(paging.limit, paging.offset).map { row ->
            @Suppress("UNCHECKED_CAST")
            val allowed = if (row["superAdmin"] == true) allKeys else (row["allowedFields"] as List<String>)
            row + mapOf(
                "allowedFields" to allowed,
                "maskedFields" to allKeys.filterNot { it in allowed }
            )
        }

        return rows to PageMeta.of(paging.page, paging.size, total)
    }

    // ---------------------------------------------------------------------------------
    // 내부 보조
    // ---------------------------------------------------------------------------------

    /** 계정 존재 확인 */
    private fun requireUser(empNo: String) {
        if (!systemUserRepository.existsUser(empNo)) {
            throw ResourceNotFoundException("계정을 찾을 수 없습니다. [$empNo]")
        }
    }

    /** 부서 존재 확인 */
    private fun requireDept(deptId: Int): Map<String, Any?> =
        systemUserRepository.findDept(deptId)
            ?: throw ResourceNotFoundException("부서를 찾을 수 없습니다. [deptId=$deptId]")

    /** 권한 화면용 부서 확인 — 사용 중지 부서도 404 (03 MNP-02) */
    private fun requireActiveDept(deptId: Int): Map<String, Any?> =
        systemUserRepository.findDept(deptId)?.takeIf { it["useFlg"] != "N" }
            ?: throw ResourceNotFoundException("부서를 찾을 수 없습니다. [deptId=$deptId]")

    /**
     * 회원가입 승인·반려
     *
     * 승인하면 PENDING → ACTIVE 로 전환되어 로그인할 수 있고, 반려하면 SUSPENDED 로 둔다.
     * 승인 대기 상태가 아닌 계정에는 적용하지 않는다.
     *
     * @param empNo   대상 사번
     * @param approve true = 승인, false = 반려
     * @param reason  반려 사유
     */
    @Transactional
    fun approveSignup(empNo: String, approve: Boolean, reason: String?, deptId: Int? = null): Map<String, Any?> {
        val principal = authorizationService.requireWrite(MenuId.SYS_ACCOUNT)

        // 사번 정확 일치 — 목록 검색(부분 일치)으로 찾으면 `1001` 승인이 `11001` 을 집을 수 있다(01 ACC-01)
        val user = systemUserRepository.findUserByEmpNo(empNo)
            ?: throw ResourceNotFoundException("계정을 찾을 수 없습니다. [$empNo]")
        if (user["state"] != "PENDING") {
            throw BusinessRuleException("이미 다른 관리자가 처리했습니다. [현재 상태=${user["state"]}]")
        }

        // 승인과 함께 부서 지정(ACC-07) — 검사는 저장 전에 전부
        val currentDeptId = user["deptId"] as Int
        val newDept = if (approve && deptId != null && deptId != currentDeptId) {
            val dept = systemUserRepository.findDept(deptId)
                ?: throw ResourceNotFoundException("부서를 찾을 수 없습니다. [deptId=$deptId]")
            assertNotSelfPermChange(principal, empNo, currentDeptId, dept, false)
            systemDeptGuard.assertCanAssignDept(principal, dept)
            dept
        } else null

        val newState = if (approve) "ACTIVE" else "SUSPENDED"
        // 조건부 UPDATE — 두 관리자가 동시에 처리하면 늦은 쪽은 0행이라 409
        if (systemUserRepository.approvePending(empNo, newState, principal.userId) == 0) {
            throw BusinessRuleException("이미 다른 관리자가 처리했습니다. [현재 상태=${systemUserRepository.findUserStateCd(empNo) ?: "-"}]")
        }
        val cleanReason = reason?.trim()?.ifEmpty { null }
        if (!approve && cleanReason != null) {
            systemUserRepository.appendRemark(empNo, "[${java.time.LocalDate.now(KST)} 반려] $cleanReason", principal.userId)
        }

        val permAuditId = auditLogService.record(
            logType = AuditType.ACCOUNT_SEC, menuId = MenuId.SYS_ACCOUNT,
            targetDesc = "회원가입 ${if (approve) "승인" else "반려"} [$empNo]", remark = cleanReason
        )
        auditLogService.recordPermChange(
            actCd = "ACCOUNT",
            targetKindCd = "USER",
            targetNm = "${user["name"]}($empNo)",
            detail = if (approve) "회원가입 승인 → ACTIVE" else "회원가입 반려 → SUSPENDED (사유: ${cleanReason ?: "-"})",
            targetDeptId = currentDeptId,
            targetUserId = empNo,
            auditId = permAuditId
        )

        val finalDeptId = newDept?.get("deptId") as Int? ?: currentDeptId
        val (appliedMenuCnt, appliedDataCnt) = if (newDept != null) {
            moveDept(principal, empNo, "${user["name"]}($empNo)", currentDeptId, newDept, MenuId.SYS_ACCOUNT)
        } else {
            appliedCounts(systemUserRepository.findDept(finalDeptId) ?: emptyMap())
        }

        log.info("회원가입 {} : empNo={} by={}", if (approve) "승인" else "반려", empNo, principal.userId)
        return mapOf(
            "empNo" to empNo, "state" to newState, "approved" to approve,
            "dept" to mapOf("deptId" to finalDeptId, "deptNm" to (newDept?.get("deptNm") ?: user["dept"])),
            "appliedMenuCnt" to appliedMenuCnt, "appliedDataCnt" to appliedDataCnt
        )
    }

    /**
     * 본인 계정의 부서·추가 메뉴는 다른 관리자가 바꾼다 (409, 01 ACC-02). 통합관리자는 예외.
     * 같은 값을 그대로 다시 보내는 저장(편집 폼 재전송)은 변경으로 보지 않는다.
     */
    private fun assertNotSelfPermChange(
        principal: UserPrincipal, targetEmpNo: String, currentDeptId: Int?, newDept: Map<String, Any?>, menusChanged: Boolean
    ) {
        if (principal.superAdmin || targetEmpNo != principal.userId) return
        if (newDept["deptId"] != currentDeptId || menusChanged) {
            throw BusinessRuleException("본인 계정의 부서와 추가 메뉴는 다른 관리자가 바꿔야 합니다.")
        }
    }

    /** 실부서 → 미배정 이동 시 추가 허용 전량 회수 + 이력 (01 ACC-14) */
    private fun revokeGrantsOnUnassignedMove(empNo: String, targetNm: String, actor: String): List<String> {
        val ids = systemUserRepository.findUserGrantIds(empNo).sorted()
        if (ids.isEmpty()) return emptyList()
        systemUserRepository.deleteUserGrants(empNo, ids)
        val permAuditId = auditLogService.record(
            logType = AuditType.PERM_CHANGE, menuId = MenuId.SYS_ACCOUNT,
            targetDesc = "계정 추가 화면 회수 [$empNo]", remark = "미배정 이동 ${ids.size}건"
        )
        auditLogService.recordPermChange(
            actCd = "USER_MENU_PERM", targetKindCd = "USER", targetNm = targetNm,
            detail = "미배정 이동으로 회수 [${ids.joinToString(", ")}]", targetUserId = empNo,
            auditId = permAuditId
        )
        return emptyList()
    }

    /**
     * 승인 대기 계정 목록을 조회한다.
     */
    @Transactional(readOnly = true)
    fun getPendingUsers(page: Int?, size: Int?, keyword: String? = null): Pair<List<Map<String, Any?>>, PageMeta> {
        authorizationService.requireMenu(MenuId.SYS_ACCOUNT)
        val paging = PageRequestParam.ofAllowAll(page, size)

        val total = systemUserRepository.countUsers(keyword, null, "PENDING", null)
        // 신청 메일은 가린 값만(원본은 응답에 넣지 않는다) — 4.4 의 email 과 ACC-05 의 emailMasked 를 함께 준다(01 ACC-07)
        val rows = withExtraMenus(systemUserRepository.findUsers(keyword, null, "PENDING", null, paging.limitOrNull, paging.offset))
            .map { it + ("email" to it["emailMasked"]) }

        return rows to (if (paging.isAll) PageMeta.all(total) else PageMeta.of(paging.page, paging.size, total))
    }

    // =================================================================================
    // 계정별 추가 허용 화면 (V30 ax.tb_sys_user_menu_grant) — 부서 권한에 더하는 화면. 열람만 부여한다(can_write=false).
    // =================================================================================

    /** 목록 행에 계정별 추가 허용 화면(`extraMenuIds`)을 붙인다 — 없는 계정도 빈 배열로, 화면이 배열로만 읽는다. */
    private fun withExtraMenus(rows: List<Map<String, Any?>>): List<Map<String, Any?>> {
        val ids = rows.mapNotNull { it["empNo"] as? String }
        val grants = systemUserRepository.findUserGrants(ids)
        // 사유·부여 시각·부여자를 함께 (01 ACC-10). extraMenuIds 는 하위 호환으로 둔다
        val details = systemUserRepository.findUserGrantDetails(ids)
        return rows.map {
            it + mapOf(
                "extraMenuIds" to (grants[it["empNo"]] ?: emptyList<String>()),
                "extraMenus" to (details[it["empNo"]] ?: emptyList<Map<String, Any?>>())
            )
        }
    }

    /** 추가 허용 사유 검증 — 200자 이내 (01 ACC-10). 저장 전에 걸러 원자성을 지킨다 */
    internal fun validateGrantReasons(reasons: Map<String, String?>?): Map<String, String?>? {
        reasons?.entries?.firstOrNull { (it.value?.trim()?.length ?: 0) > 200 }?.let {
            throw InvalidParameterException("추가 허용 사유는 200자 이내여야 합니다. [${it.key}]", "extraMenuReasons")
        }
        return reasons?.mapKeys { it.key.trim() }
    }

    /**
     * 요청한 화면 ID 를 검증한다. `null` 은 "그대로 둔다". 없는·사용 중지 화면이 있으면 400 — 저장 전에 걸러 원자성을 지킨다.
     */
    internal fun validateExtraMenus(requested: List<String>?): List<String>? {
        if (requested == null) return null
        val ids = requested.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val active = systemUserRepository.findActiveMenuIds(ids)
        val unknown = ids.filterNot { it in active }
        if (unknown.isNotEmpty()) {
            throw InvalidParameterException("존재하지 않거나 사용 중지된 화면 ID 입니다. [${unknown.joinToString(", ")}]", "extraMenuIds")
        }
        return ids
    }

    /**
     * 추가 허용 화면을 요청 목록으로 **치환**한다 — 없던 것은 부여, 목록에서 빠진 것은 회수. 바뀐 것만 이력에 남긴다.
     *
     * @param validated [validateExtraMenus] 를 거친 목록. null 이면 아무것도 하지 않고 현재 값을 돌려준다
     * @return 저장 뒤의 추가 허용 화면 목록(사용 중인 화면만, 메뉴 순)
     */
    internal fun applyExtraMenus(
        empNo: String, targetNm: String, validated: List<String>?, actor: String, reasons: Map<String, String?>? = null
    ): List<String> {
        if (validated != null) {
            val plan = planGrantChanges(systemUserRepository.findUserGrantIds(empNo), validated)
            systemUserRepository.insertUserGrants(empNo, plan.add, actor)
            systemUserRepository.deleteUserGrants(empNo, plan.remove)
            if (plan.add.isNotEmpty() || plan.remove.isNotEmpty()) {
                val permAuditId = auditLogService.record(
                    logType = AuditType.PERM_CHANGE,
                    menuId = MenuId.SYS_ACCOUNT,
                    targetDesc = "계정 추가 화면 변경 [$empNo]",
                    remark = "부여 ${plan.add.size}건 · 회수 ${plan.remove.size}건"
                )
                auditLogService.recordPermChange(
                    actCd = "USER_MENU_PERM",
                    targetKindCd = "USER",
                    targetNm = targetNm,
                    detail = buildString {
                        append("계정 추가 화면 ")
                        if (plan.add.isNotEmpty()) append("부여 [${plan.add.joinToString(", ")}]")
                        if (plan.add.isNotEmpty() && plan.remove.isNotEmpty()) append(" · ")
                        if (plan.remove.isNotEmpty()) append("회수 [${plan.remove.joinToString(", ")}]")
                    },
                    targetUserId = empNo,
                    auditId = permAuditId
                )
                log.info("계정 추가 화면 변경 : empNo={} add={} remove={} by={}", empNo, plan.add, plan.remove, actor)
            }
        }
        // 사유 — 지금 부여 중인 화면만 저장한다 (01 ACC-10)
        reasons?.takeIf { it.isNotEmpty() }?.let { r ->
            val granted = systemUserRepository.findUserGrantIds(empNo)
            systemUserRepository.updateGrantReasons(empNo, r.filterKeys { it in granted }, actor)
        }
        return systemUserRepository.findUserGrants(listOf(empNo))[empNo] ?: emptyList()
    }

    // =================================================================================
    // 관리자 비밀번호 변경 (2026-09-14) — PUT /system/users/{empNo} 의 password
    // =================================================================================

    /**
     * 비밀번호를 바꿀 수 있는 관리자 — 통합관리자, 또는 **소속 부서가 기본으로** 계정 관리(sys-account) 권한을 가진 사람.
     * 계정 추가 허용(extraMenuIds)으로만 sys-account 를 받은 사용자는 아니다 — 부서 권한 표만 본다.
     */
    fun canChangePassword(principal: UserPrincipal): Boolean =
        principal.superAdmin || systemUserRepository.deptHasMenuPerm(principal.deptId, MenuId.SYS_ACCOUNT)

    /**
     * 요청의 비밀번호를 검사해 저장할 해시를 만든다. 비어 있으면 null(그대로 둔다).
     *
     * 순서 — 관리자 판정(403) → 정책(400) → 사번 포함 금지(400). 저장 전에 전부 끝내 실패하면 계정 정보도 바뀌지 않는다.
     */
    internal fun preparePasswordChange(rawPassword: String?, empNo: String, principal: UserPrincipal, checkEmpNo: Boolean = true): String? {
        val password = rawPassword?.takeIf { it.isNotBlank() } ?: return null
        if (!canChangePassword(principal)) {
            throw BusinessException(
                ErrorCode.AUTH_MENU_DENIED,
                "비밀번호 변경은 통합관리자 또는 계정 관리 권한을 기본으로 가진 부서의 관리자만 할 수 있습니다."
            )
        }
        passwordEncoderService.validatePolicy(password, "password")
        // 초기 규칙값(사번 + 접미어)으로 되돌리는 관리자 잠금 해제는 사번 포함 검사를 건너뛴다 — 계정 등록과 같은 규칙
        if (checkEmpNo && password.contains(empNo, ignoreCase = true)) {
            throw InvalidParameterException("비밀번호에 사번을 포함할 수 없습니다.", "password")
        }
        return passwordEncoderService.encode(password)
    }

}

/** 추가 허용 치환 계획 — 현재 집합과 요청 목록의 차이. 순수 함수라 테스트로 고정한다. */
data class GrantPlan(val add: List<String>, val remove: List<String>)

fun planGrantChanges(current: Set<String>, requested: List<String>): GrantPlan {
    val wanted = requested.toSet()
    return GrantPlan(add = requested.distinct().filter { it !in current }, remove = current.filter { it !in wanted }.sorted())
}
