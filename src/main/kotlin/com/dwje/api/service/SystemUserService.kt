package com.dwje.api.service

import com.dwje.api.common.exception.BusinessException
import com.dwje.api.common.exception.BusinessRuleException
import com.dwje.api.common.response.ErrorCode
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.common.exception.DuplicatedValueException
import com.dwje.api.common.exception.InvalidParameterException
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
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 계정 · 부서 · 권한 관리 서비스 (SY-01, SY-02, SY-03)
 *
 * 모든 변경은 권한 변경 이력(ax.tb_sys_perm_log)과 감사 로그(ax.tb_log_audit)에 기록된다.
 *
 * 접근 부서 : 전산팀 · 통합관리자
 */
@Service
class SystemUserService(
    private val systemUserRepository: SystemUserRepository,
    private val authorizationService: AuthorizationService,
    private val auditLogService: AuditLogService,
    private val passwordEncoderService: PasswordEncoderService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** 계정 등록 시 초기 비밀번호가 없으면 사번을 사용한다. */
        private const val DEFAULT_PASSWORD_SUFFIX = "!Dwje1234"
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
            "canChangePassword" to canChangePassword
        )
        // 화면이 비밀번호 필드를 보일지 정하는 기준 — 통합관리자 또는 소속 부서가 기본으로 계정 관리 권한을 가진 사람
        summary["canChangePassword"] = canChangePassword
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
        size: Int?
    ): Pair<List<Map<String, Any?>>, PageMeta> {
        authorizationService.requireMenu(MenuId.SYS_ACCOUNT)
        // size=0 이면 전량 — 화면이 Tabulator 열 필터를 전체 결과에 걸고 쪽은 브라우저에서 나눈다.
        val paging = PageRequestParam.ofAllowAll(page, size)

        val total = systemUserRepository.countUsers(keyword, deptId, state, switchable)
        val rows = withExtraMenus(systemUserRepository.findUsers(keyword, deptId, state, switchable, paging.limitOrNull, paging.offset))

        return rows to (if (paging.isAll) PageMeta.all(total) else PageMeta.of(paging.page, paging.size, total))
    }

    /** 계정 등록 (No.129) */
    @Transactional
    fun createUser(request: UserSaveRequest): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.SYS_ACCOUNT)

        val empNo = request.empNo?.trim()
            ?: throw InvalidParameterException("사번을 입력해 주세요.", "empNo")
        val name = request.name?.trim()
            ?: throw InvalidParameterException("이름을 입력해 주세요.", "name")
        val deptId = request.deptId
            ?: throw InvalidParameterException("부서를 선택해 주세요.", "deptId")

        if (systemUserRepository.existsUser(empNo)) {
            throw DuplicatedValueException("이미 등록된 사번입니다. [$empNo]", "empNo")
        }
        systemUserRepository.findDept(deptId)
            ?: throw ResourceNotFoundException("부서를 찾을 수 없습니다. [deptId=$deptId]")

        val stateCd = systemUserRepository.normalizeUserState(request.state ?: "ACTIVE")
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
        val grants = applyExtraMenus(empNo, "$name($empNo)", validateExtraMenus(request.extraMenuIds), principal.userId)
        log.info("계정 등록 : empNo={} name={} by={}", empNo, name, principal.userId)
        return mapOf("empNo" to empNo, "extraMenuIds" to grants)
    }

    /** 계정 수정 (No.130) */
    @Transactional
    fun updateUser(empNo: String, request: UserSaveRequest): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.SYS_ACCOUNT)
        requireUser(empNo)

        val stateCd = request.state?.let { systemUserRepository.normalizeUserState(it) }
        // 추가 허용 화면은 계정 정보와 **한 트랜잭션**이다 — 없는 화면 ID 가 하나라도 있으면 이름·부서도 바뀌지 않는다.
        val requestedMenus = validateExtraMenus(request.extraMenuIds)
        // 비밀번호 — 비어 있으면 그대로. 값이 있으면 관리자 판정·정책 검사를 저장 전에 끝내 실패 시 아무것도 바뀌지 않게 한다.
        val newPasswordHash = preparePasswordChange(request.password, empNo, principal)

        // 자기 자신의 계정을 사용 상태에서 내릴 수 없다. (PENDING 도 로그인이 막힌다)
        if (stateCd != null && stateCd != "ACTIVE" && empNo == principal.userId) {
            throw BusinessRuleException("로그인 중인 본인 계정은 사용 상태에서 내릴 수 없습니다.")
        }

        val updated = systemUserRepository.updateUser(
            empNo = empNo,
            name = request.name?.trim(),
            deptId = request.deptId,
            positionCd = request.pos,
            stateCd = stateCd,
            switchable = request.switchable,
            actor = principal.userId
        )
        if (updated == 0) throw ResourceNotFoundException("계정을 찾을 수 없습니다. [$empNo]")

        auditLogService.recordPermChange(
            actCd = "ACCOUNT",
            targetKindCd = "USER",
            targetNm = empNo,
            detail = "계정 수정 — 이름=${request.name ?: "-"}, 부서=${request.deptId ?: "-"}, 상태=${stateCd ?: "-"}",
            targetDeptId = request.deptId,
            targetUserId = empNo
        )

        val grants = applyExtraMenus(empNo, empNo, requestedMenus, principal.userId)
        val passwordChanged = newPasswordHash != null
        if (newPasswordHash != null) {
            systemUserRepository.updatePasswordHash(empNo, newPasswordHash, principal.userId)
            // 비밀번호 값은 어디에도 남기지 않는다 — 바뀌었다는 사실과 누가 바꿨는지만.
            auditLogService.recordPermChange(
                actCd = "ACCOUNT", targetKindCd = "USER", targetNm = empNo,
                detail = "비밀번호 변경(관리자 ${principal.userId})", targetUserId = empNo
            )
            auditLogService.record(
                logType = "AUTO_GEN", menuId = MenuId.SYS_ACCOUNT,
                targetDesc = "비밀번호 변경 [$empNo]", remark = "관리자 변경 by ${principal.userId}"
            )
            log.info("비밀번호 변경(관리자) : empNo={} by={}", empNo, principal.userId)
        }
        return mapOf("success" to true, "empNo" to empNo, "extraMenuIds" to grants, "passwordChanged" to passwordChanged)
    }

    /** 계정 삭제 (No.131) */
    @Transactional
    fun deleteUser(empNo: String): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.SYS_ACCOUNT)
        requireUser(empNo)

        // 로그인 중인 본인 계정은 삭제할 수 없다. (E-RULE-001)
        if (empNo == principal.userId) {
            throw BusinessRuleException("로그인 중인 본인 계정은 삭제할 수 없습니다.")
        }

        systemUserRepository.deleteUser(empNo)

        auditLogService.recordPermChange(
            actCd = "ACCOUNT",
            targetKindCd = "USER",
            targetNm = empNo,
            detail = "계정 삭제",
            targetUserId = empNo
        )

        log.info("계정 삭제 : empNo={} by={}", empNo, principal.userId)
        return mapOf("success" to true)
    }

    /** 계정 사용/정지 (No.132) */
    @Transactional
    fun changeUserState(empNo: String, state: String?): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.SYS_ACCOUNT)
        requireUser(empNo)

        // 상태를 빼고 부르면 예전에는 ACTIVE 로 간주했다. 그래서 body 를 비운 채 '정지' 를
        // 눌러도 200 + "변경되었습니다" 가 나가면서 실제로는 계정이 활성화됐다.
        // 무엇을 바꾸려는지 지정하지 않은 요청은 성공으로 처리하지 않는다.
        val requested = state?.trim()?.takeIf { it.isNotBlank() }
            ?: throw InvalidParameterException("변경할 계정 상태를 지정해 주세요.", "state")

        val stateCd = systemUserRepository.normalizeUserState(requested)

        // 본인 계정을 ACTIVE 에서 내리면 스스로 로그인할 수 없게 된다.
        // 로그인 판정이 'ACTIVE 인가' 이므로 SUSPENDED 뿐 아니라 PENDING 도 같은 결과다.
        if (stateCd != "ACTIVE" && empNo == principal.userId) {
            throw BusinessRuleException("로그인 중인 본인 계정은 사용 상태에서 내릴 수 없습니다.")
        }

        systemUserRepository.updateUser(empNo, null, null, null, stateCd, null, principal.userId)

        auditLogService.recordPermChange(
            actCd = "ACCOUNT",
            targetKindCd = "USER",
            targetNm = empNo,
            detail = "계정 상태 변경 → $stateCd",
            targetUserId = empNo
        )

        return mapOf("success" to true, "state" to stateCd)
    }

    /** 계정 부서 이동 (No.133) */
    @Transactional
    fun changeUserDept(empNo: String, deptId: Int): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.SYS_ACCOUNT)
        requireUser(empNo)

        val dept = systemUserRepository.findDept(deptId)
            ?: throw ResourceNotFoundException("부서를 찾을 수 없습니다. [deptId=$deptId]")

        systemUserRepository.updateUserDept(empNo, deptId, principal.userId)

        // 이동한 부서의 메뉴/데이터 권한을 그대로 상속한다.
        val appliedMenuCnt = systemUserRepository.findMenuPermMatrix().count { it["deptId"] == deptId }
        val appliedDataCnt = systemUserRepository.findDataPermMatrix().count { it["deptId"] == deptId }

        auditLogService.recordPermChange(
            actCd = "ACCOUNT",
            targetKindCd = "USER",
            targetNm = empNo,
            detail = "부서 이동 → ${dept["deptNm"]} (메뉴 ${appliedMenuCnt}건 · 데이터 ${appliedDataCnt}건 상속)",
            targetDeptId = deptId,
            targetUserId = empNo
        )
        auditLogService.record(
            logType = "PERM_CHANGE",
            menuId = MenuId.SYS_ACCOUNT,
            targetDesc = "계정 부서 이동 [$empNo → ${dept["deptNm"]}]",
            remark = "메뉴 ${appliedMenuCnt}건 · 데이터 ${appliedDataCnt}건"
        )

        return mapOf("success" to true, "appliedMenuCnt" to appliedMenuCnt, "appliedDataCnt" to appliedDataCnt)
    }

    // =================================================================================
    // 부서 관리
    // =================================================================================

    /** 부서 목록 조회 (No.135) */
    @Transactional(readOnly = true)
    fun getDepts(): Map<String, Any?> {
        authorizationService.requireAnyMenu(MenuId.SYS_ACCOUNT, MenuId.SYS_MENU, MenuId.SYS_DATA)
        return mapOf("items" to systemUserRepository.findDepts())
    }

    /**
     * 부서 목록 — 키워드(부서명·약칭·설명)와 쪽 나눔 (2026-09-13 WEB 요청).
     * `page`·`size` 가 없거나 `size=0` 이면 전량(기존 선택지 호출 호환)이고 meta 는 null 이다.
     */
    @Transactional(readOnly = true)
    fun getDepts(keyword: String?, page: Int?, size: Int?): Pair<List<Map<String, Any?>>, PageMeta?> {
        authorizationService.requireAnyMenu(MenuId.SYS_ACCOUNT, MenuId.SYS_MENU, MenuId.SYS_DATA)
        val paged = page != null || (size != null && size > 0)
        if (!paged) return systemUserRepository.findDepts(keyword, null, 0) to null
        val paging = PageRequestParam.of(page, size)
        val total = systemUserRepository.countDepts(keyword)
        val rows = systemUserRepository.findDepts(keyword, paging.limit, paging.offset)
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
        val principal = authorizationService.requireMenu(MenuId.SYS_ACCOUNT)

        val deptNm = request.deptNm?.trim()
            ?: throw InvalidParameterException("부서명을 입력해 주세요.", "deptNm")
        val abbr = request.abbr?.trim()
            ?: throw InvalidParameterException("부서 약칭을 입력해 주세요.", "abbr")

        if (systemUserRepository.existsDeptName(deptNm, abbr, null)) {
            throw DuplicatedValueException("이미 등록된 부서명 또는 약칭입니다.", "deptNm")
        }

        val deptId = systemUserRepository.insertDept(deptNm, abbr, request.desc, request.plantCd, principal.userId)

        // 초기 권한을 지정 부서에서 복사한다.
        var copiedCnt = 0
        request.initPermFrom?.let { fromDeptId ->
            copiedCnt = systemUserRepository.copyMenuPerms(fromDeptId, deptId, principal.userId)
        }

        auditLogService.recordPermChange(
            actCd = "DEPT",
            targetKindCd = "DEPT",
            targetNm = deptNm,
            detail = "부서 등록 — 약칭=$abbr, 초기 권한 복사=${copiedCnt}건",
            targetDeptId = deptId
        )

        return mapOf("deptId" to deptId, "copiedMenuCnt" to copiedCnt)
    }

    /** 부서 수정 (No.137) */
    @Transactional
    fun updateDept(deptId: Int, request: DeptSaveRequest): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.SYS_ACCOUNT)
        systemUserRepository.findDept(deptId)
            ?: throw ResourceNotFoundException("부서를 찾을 수 없습니다. [deptId=$deptId]")

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
        authorizationService.requireMenu(MenuId.SYS_ACCOUNT)

        val dept = systemUserRepository.findDept(deptId)
            ?: throw ResourceNotFoundException("부서를 찾을 수 없습니다. [deptId=$deptId]")

        // 소속 계정이 있는 부서는 삭제할 수 없다. (E-RULE-001)
        val userCnt = systemUserRepository.countUsersInDept(deptId)
        if (userCnt > 0) {
            throw BusinessRuleException("소속 계정이 ${userCnt}명 있어 삭제할 수 없습니다. 계정을 먼저 이동하세요.")
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

    /** 메뉴 권한 매트릭스 조회 (No.140) */
    @Transactional(readOnly = true)
    fun getMenuPermMatrix(): Map<String, Any?> {
        // 조회만 계정 관리(SYS_ACCOUNT)에도 열어 준다 — 계정별 추가 허용 화면의 선택지가 이 매트릭스다. 쓰기는 SYS_MENU 그대로.
        authorizationService.requireAnyMenu(MenuId.SYS_MENU, MenuId.SYS_ACCOUNT)

        val screens = systemUserRepository.findAllMenus()
        val depts = systemUserRepository.findDepts()
        val perms = systemUserRepository.findMenuPermMatrix()

        // 통합관리자 부서는 전 화면 접근 권한을 갖는 것으로 표기한다.
        val matrix = depts.associate { dept ->
            val deptId = dept["deptId"] as Int
            val allowed = if (dept["superAdmin"] == true) {
                screens.map { it["id"] as String }
            } else {
                perms.filter { it["deptId"] == deptId }.map { it["menuId"] as String }
            }
            deptId.toString() to allowed
        }

        return mapOf("screens" to screens, "depts" to depts, "matrix" to matrix)
    }

    /** 메뉴 권한 단건 변경 (No.141) */
    @Transactional
    fun changeMenuPerm(request: MenuPermRequest): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.SYS_MENU)
        val dept = requireDept(request.deptId)

        systemUserRepository.upsertMenuPerm(request.deptId, request.screenId, request.allowed, principal.userId)

        auditLogService.recordPermChange(
            actCd = "MENU_PERM",
            targetKindCd = "MENU",
            targetNm = "${dept["deptNm"]} / ${request.screenId}",
            detail = "메뉴 권한 ${if (request.allowed) "부여" else "회수"}",
            targetDeptId = request.deptId
        )
        auditLogService.record(
            logType = "PERM_CHANGE",
            menuId = MenuId.SYS_MENU,
            targetDesc = "메뉴 권한 변경 [${dept["deptNm"]} / ${request.screenId}]",
            remark = if (request.allowed) "부여" else "회수"
        )

        return mapOf("success" to true)
    }

    /** 메뉴 권한 그룹 일괄 변경 (No.142) */
    @Transactional
    fun changeMenuPermByGroup(request: MenuPermGroupRequest): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.SYS_MENU)
        val dept = requireDept(request.deptId)

        val changedCnt = systemUserRepository.updateMenuPermByGroup(
            request.deptId, request.groupNm, request.allowed, principal.userId
        )

        auditLogService.recordPermChange(
            actCd = "MENU_PERM",
            targetKindCd = "MENU",
            targetNm = "${dept["deptNm"]} / 그룹 ${request.groupNm}",
            detail = "그룹 일괄 ${if (request.allowed) "부여" else "회수"} (${changedCnt}건)",
            targetDeptId = request.deptId
        )

        return mapOf("changedCnt" to changedCnt)
    }

    /** 부서 메뉴 권한 복사 (No.143) */
    @Transactional
    fun copyMenuPerms(request: MenuPermCopyRequest): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.SYS_MENU)

        val fromDept = requireDept(request.fromDeptId)
        val toDept = requireDept(request.toDeptId)
        if (request.fromDeptId == request.toDeptId) {
            throw BusinessRuleException("원본 부서와 대상 부서가 같습니다.")
        }

        val copiedCnt = systemUserRepository.copyMenuPerms(request.fromDeptId, request.toDeptId, principal.userId)

        auditLogService.recordPermChange(
            actCd = "MENU_PERM",
            targetKindCd = "DEPT",
            targetNm = "${toDept["deptNm"]}",
            detail = "메뉴 권한 복사 (${fromDept["deptNm"]} → ${toDept["deptNm"]}, ${copiedCnt}건)",
            targetDeptId = request.toDeptId
        )

        return mapOf("copiedCnt" to copiedCnt)
    }

    // =================================================================================
    // 데이터 접근 권한
    // =================================================================================

    /** 데이터 항목 목록 (No.145) */
    @Transactional(readOnly = true)
    fun getDataFields(): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.SYS_DATA)
        return mapOf("items" to systemUserRepository.findDataFields())
    }

    /** 데이터 권한 매트릭스 조회 (No.146) */
    @Transactional(readOnly = true)
    fun getDataPermMatrix(): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.SYS_DATA)

        val fields = systemUserRepository.findDataFields()
        val depts = systemUserRepository.findDepts()
        val perms = systemUserRepository.findDataPermMatrix()

        val matrix = depts.associate { dept ->
            val deptId = dept["deptId"] as Int
            val allowed = if (dept["superAdmin"] == true) {
                fields.map { it["key"] as String }
            } else {
                perms.filter { it["deptId"] == deptId }.map { it["fieldKey"] as String }
            }
            deptId.toString() to allowed
        }

        return mapOf("fields" to fields, "depts" to depts, "matrix" to matrix)
    }

    /** 데이터 권한 변경 (No.147) */
    @Transactional
    fun changeDataPerm(request: DataPermRequest): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.SYS_DATA)
        val dept = requireDept(request.deptId)

        // 항목은 운영 중에 늘어난다(V33) — 코드 상수가 아니라 항목 표를 본다.
        if (systemUserRepository.findDataFields().none { it["key"] == request.fieldKey }) {
            throw InvalidParameterException("존재하지 않는 데이터 항목입니다. [${request.fieldKey}]", "fieldKey")
        }

        systemUserRepository.upsertDataPerm(request.deptId, request.fieldKey, request.allowed, principal.userId)

        auditLogService.recordPermChange(
            actCd = "DATA_PERM",
            targetKindCd = "FIELD",
            targetNm = "${dept["deptNm"]} / ${request.fieldKey}",
            detail = "데이터 권한 ${if (request.allowed) "부여" else "회수"}",
            targetDeptId = request.deptId
        )
        auditLogService.record(
            logType = "PERM_CHANGE",
            menuId = MenuId.SYS_DATA,
            fieldKey = request.fieldKey,
            targetDesc = "데이터 권한 변경 [${dept["deptNm"]} / ${request.fieldKey}]",
            remark = if (request.allowed) "부여" else "회수"
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
            val allowed = target.canReadField(key)
            mapOf(
                "fieldKey" to key,
                "name" to f["name"],
                "rendered" to if (allowed) "원본 노출" else "비공개",
                "masked" to !allowed
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
    fun approveSignup(empNo: String, approve: Boolean, reason: String?): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.SYS_ACCOUNT)

        val user = systemUserRepository.findUsers(empNo, null, null, null, 1, 0).firstOrNull()
            ?: throw ResourceNotFoundException("계정을 찾을 수 없습니다. [$empNo]")

        // 승인 대상은 가입 신청 상태여야 한다.
        if (user["state"] != "PENDING") {
            throw BusinessRuleException("승인 대기 상태의 계정만 처리할 수 있습니다. [현재 상태=${user["state"]}]")
        }

        val newState = if (approve) "ACTIVE" else "SUSPENDED"
        systemUserRepository.updateUser(empNo, null, null, null, newState, null, principal.userId)

        auditLogService.recordPermChange(
            actCd = "ACCOUNT",
            targetKindCd = "USER",
            targetNm = "${user["name"]}($empNo)",
            detail = if (approve) "회원가입 승인 → ACTIVE" else "회원가입 반려 → SUSPENDED (사유: ${reason ?: "-"})",
            targetDeptId = user["deptId"] as? Int,
            targetUserId = empNo
        )

        log.info("회원가입 {} : empNo={} by={}", if (approve) "승인" else "반려", empNo, principal.userId)
        return mapOf("empNo" to empNo, "state" to newState, "approved" to approve)
    }

    /**
     * 승인 대기 계정 목록을 조회한다.
     */
    @Transactional(readOnly = true)
    fun getPendingUsers(page: Int?, size: Int?, keyword: String? = null): Pair<List<Map<String, Any?>>, PageMeta> {
        authorizationService.requireMenu(MenuId.SYS_ACCOUNT)
        val paging = PageRequestParam.ofAllowAll(page, size)

        val total = systemUserRepository.countUsers(keyword, null, "PENDING", null)
        val rows = withExtraMenus(systemUserRepository.findUsers(keyword, null, "PENDING", null, paging.limitOrNull, paging.offset))

        return rows to (if (paging.isAll) PageMeta.all(total) else PageMeta.of(paging.page, paging.size, total))
    }

    // =================================================================================
    // 계정별 추가 허용 화면 (V30 ax.tb_sys_user_menu_grant) — 부서 권한에 더하는 화면. 열람만 부여한다(can_write=false).
    // =================================================================================

    /** 목록 행에 계정별 추가 허용 화면(`extraMenuIds`)을 붙인다 — 없는 계정도 빈 배열로, 화면이 배열로만 읽는다. */
    private fun withExtraMenus(rows: List<Map<String, Any?>>): List<Map<String, Any?>> {
        val grants = systemUserRepository.findUserGrants(rows.mapNotNull { it["empNo"] as? String })
        return rows.map { it + mapOf("extraMenuIds" to (grants[it["empNo"]] ?: emptyList<String>())) }
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
    internal fun applyExtraMenus(empNo: String, targetNm: String, validated: List<String>?, actor: String): List<String> {
        if (validated != null) {
            val plan = planGrantChanges(systemUserRepository.findUserGrantIds(empNo), validated)
            systemUserRepository.insertUserGrants(empNo, plan.add, actor)
            systemUserRepository.deleteUserGrants(empNo, plan.remove)
            if (plan.add.isNotEmpty() || plan.remove.isNotEmpty()) {
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
                    targetUserId = empNo
                )
                auditLogService.record(
                    logType = "PERM_CHANGE",
                    menuId = MenuId.SYS_ACCOUNT,
                    targetDesc = "계정 추가 화면 변경 [$empNo]",
                    remark = "부여 ${plan.add.size}건 · 회수 ${plan.remove.size}건"
                )
                log.info("계정 추가 화면 변경 : empNo={} add={} remove={} by={}", empNo, plan.add, plan.remove, actor)
            }
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
    internal fun preparePasswordChange(rawPassword: String?, empNo: String, principal: UserPrincipal): String? {
        val password = rawPassword?.takeIf { it.isNotBlank() } ?: return null
        if (!canChangePassword(principal)) {
            throw BusinessException(
                ErrorCode.AUTH_MENU_DENIED,
                "비밀번호 변경은 통합관리자 또는 계정 관리 권한을 기본으로 가진 부서의 관리자만 할 수 있습니다."
            )
        }
        passwordEncoderService.validatePolicy(password, "password")
        if (password.contains(empNo, ignoreCase = true)) {
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
