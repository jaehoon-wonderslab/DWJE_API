package com.dwje.api.service

import com.dwje.api.common.exception.BusinessRuleException
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
        summary["currentUser"] = mapOf(
            "empNo" to principal.userId,
            "name" to principal.userName,
            "dept" to principal.deptName
        )
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
        val paging = PageRequestParam.of(page, size)

        val total = systemUserRepository.countUsers(keyword, deptId, state, switchable)
        val rows = systemUserRepository.findUsers(keyword, deptId, state, switchable, paging.limit, paging.offset)

        return rows to PageMeta.of(paging.page, paging.size, total)
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

        log.info("계정 등록 : empNo={} name={} by={}", empNo, name, principal.userId)
        return mapOf("empNo" to empNo)
    }

    /** 계정 수정 (No.130) */
    @Transactional
    fun updateUser(empNo: String, request: UserSaveRequest): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.SYS_ACCOUNT)
        requireUser(empNo)

        val stateCd = request.state?.let { systemUserRepository.normalizeUserState(it) }

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

        return mapOf("success" to true, "empNo" to empNo)
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
        authorizationService.requireMenu(MenuId.SYS_MENU)

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
                DataField.ALL
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

        if (request.fieldKey !in DataField.ALL) {
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
        val rows = systemUserRepository.findDataPermByUser(paging.limit, paging.offset).map { row ->
            @Suppress("UNCHECKED_CAST")
            val allowed = if (row["superAdmin"] == true) DataField.ALL else (row["allowedFields"] as List<String>)
            row + mapOf(
                "allowedFields" to allowed,
                "maskedFields" to DataField.ALL.filterNot { it in allowed }
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
    fun getPendingUsers(page: Int?, size: Int?): Pair<List<Map<String, Any?>>, PageMeta> {
        authorizationService.requireMenu(MenuId.SYS_ACCOUNT)
        val paging = PageRequestParam.of(page, size)

        val total = systemUserRepository.countUsers(null, null, "PENDING", null)
        val rows = systemUserRepository.findUsers(null, null, "PENDING", null, paging.limit, paging.offset)

        return rows to PageMeta.of(paging.page, paging.size, total)
    }
}
