package com.dwje.api.service

import com.dwje.api.common.exception.BusinessException
import com.dwje.api.common.exception.BusinessRuleException
import com.dwje.api.common.response.ErrorCode
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.common.util.MenuId
import com.dwje.api.config.AppProperties
import org.springframework.stereotype.Component

/**
 * 시스템 부서(통합관리자 · 미배정) 보호 규칙 — 공통 기획서 4.1 CMN-01
 *
 * 계정 관리·그룹웨어 부서 매핑·메뉴 접근 권한·데이터 접근 권한·회원가입이 각자 검사를 넣으면 규칙이 또 갈라지므로
 * 판정과 오류 문구를 이 한 곳에 둔다. 각 서비스는 이 함수만 부른다.
 *
 * 오류 코드 구분(공통 9.7)
 * - 누가 해도 안 되는 변경(시스템 부서의 권한 변경·복사, 이름 변경·삭제, 미배정 계정 추가 메뉴) → 409 `E-RULE-001`
 * - 통합관리자만 할 수 있는 변경(통합관리자 부서로 배정, 관리 화면 4종 부여·회수) → 403 `E-AUTH-002`
 *
 * 미배정 부서는 지금처럼 이름(`app.unassigned-dept-name`)으로 판정한다(결정 D-03 전이라 역할 컬럼을 쓰지 않는다).
 * 부서 정보는 `SystemUserRepository.findDept` 의 Map(`deptNm`·`superAdmin`)을 그대로 받는다 — 판정에 DB 를 다시 읽지 않는다.
 */
@Component
class SystemDeptGuard(
    private val appProperties: AppProperties = AppProperties()
) {

    companion object {
        const val MSG_SUPER_ADMIN_PERM_FIXED = "통합관리자 부서는 전 권한으로 고정되어 조정할 수 없습니다."
        const val MSG_UNASSIGNED_PERM_FIXED = "미배정 부서는 대시보드·덕반장 AI·질의 이력 조회 전용으로 고정되어 조정할 수 없습니다."
        const val MSG_SYSTEM_DEPT_IMMUTABLE = "미배정(통합관리자) 부서는 이름을 바꾸거나 삭제할 수 없습니다. 자동 가입과 권한 판정이 이 부서를 씁니다."
        const val MSG_ASSIGN_SUPER_ADMIN = "통합관리자 부서로의 배정은 통합관리자만 할 수 있습니다."
        const val MSG_ADMIN_SCREEN = "관리 화면 권한은 통합관리자만 부여하거나 회수할 수 있습니다."
        const val MSG_UNASSIGNED_EXTRA_MENU = "미배정 계정에는 추가 메뉴를 줄 수 없습니다. 먼저 실제 부서로 옮기십시오."
        const val MSG_UNASSIGNED_COPY = "미배정 부서는 고정 부서라 복사 대상·원본이 될 수 없습니다."
        const val MSG_UNASSIGNED_DATA_PERM_FIXED = "미배정 부서의 데이터 권한은 고정되어 조정할 수 없습니다."
    }

    /** 미배정 부서 이름인지 — 판정 함수는 이것 하나다 */
    fun isUnassignedDeptName(deptNm: String?): Boolean =
        deptNm != null && deptNm == appProperties.unassignedDeptName

    /** 부서 Map(`deptNm`) 이 미배정 부서인지 */
    fun isUnassignedDept(dept: Map<String, Any?>): Boolean = isUnassignedDeptName(dept["deptNm"] as String?)

    /** 부서 Map(`superAdmin`) 이 통합관리자 부서인지 */
    fun isSuperAdminDept(dept: Map<String, Any?>): Boolean = dept["superAdmin"] == true

    /** 시스템 부서 구분 — `SUPER_ADMIN` · `UNASSIGNED` · null (화면 배지·잠금 표시용) */
    fun systemRoleOf(dept: Map<String, Any?>): String? = when {
        isSuperAdminDept(dept) -> "SUPER_ADMIN"
        isUnassignedDept(dept) -> "UNASSIGNED"
        else -> null
    }

    /**
     * 대상 부서가 통합관리자 부서면 409. (권한 복사의 대상 등 「통합관리자 부서를 건드리는」 동작)
     *
     * @param action 오류 문구 앞에 붙일 동작 이름. null 이면 권한 고정 문구를 쓴다
     */
    fun assertNotSuperAdminDept(dept: Map<String, Any?>, action: String? = null) {
        if (isSuperAdminDept(dept)) {
            throw BusinessRuleException(action?.let { "통합관리자 부서는 $it 대상이 될 수 없습니다." } ?: MSG_SUPER_ADMIN_PERM_FIXED)
        }
    }

    /**
     * 계정을 대상 부서로 배정·이동할 수 있는지 — 통합관리자 부서로의 배정은 통합관리자만 (403 E-AUTH-002).
     * 계정 등록·수정·부서 이동·가입 승인·그룹웨어 재배정에서 부른다.
     */
    fun assertCanAssignDept(principal: UserPrincipal, dept: Map<String, Any?>) {
        if (isSuperAdminDept(dept) && !principal.superAdmin) {
            throw BusinessException(ErrorCode.AUTH_MENU_DENIED, MSG_ASSIGN_SUPER_ADMIN, "deptId")
        }
    }

    /**
     * 시스템 부서의 이름 변경·삭제를 막는다 (409).
     *
     * @param newName 바꾸려는 이름. null 이면 이름을 바꾸지 않는 수정(설명만)으로 보고 통과시킨다
     * @param deleting 삭제 요청이면 true
     */
    fun assertSystemDeptImmutable(dept: Map<String, Any?>, newName: String? = null, deleting: Boolean = false) {
        if (systemRoleOf(dept) == null) return
        val renaming = newName != null && newName != dept["deptNm"]
        if (deleting || renaming) throw BusinessRuleException(MSG_SYSTEM_DEPT_IMMUTABLE)
    }

    /**
     * 시스템 부서를 대상으로 하는 메뉴·데이터 권한 변경과 복사를 막는다 (409, R-01).
     * 통합관리자는 권한 행 없이 전 권한, 미배정은 고정 5개 화면 · 데이터 권한 0건이다.
     */
    fun assertNotSystemDeptPerm(dept: Map<String, Any?>) {
        if (isSuperAdminDept(dept)) throw BusinessRuleException(MSG_SUPER_ADMIN_PERM_FIXED)
        if (isUnassignedDept(dept)) throw BusinessRuleException(MSG_UNASSIGNED_PERM_FIXED)
    }

    /** 데이터 권한 변경 — 통합관리자·미배정 409, 미배정 문구는 데이터 전용(04 DTP-03) */
    fun assertNotSystemDeptDataPerm(dept: Map<String, Any?>) {
        if (isSuperAdminDept(dept)) throw BusinessRuleException(MSG_SUPER_ADMIN_PERM_FIXED)
        if (isUnassignedDept(dept)) throw BusinessRuleException(MSG_UNASSIGNED_DATA_PERM_FIXED)
    }

    /**
     * 관리 화면 4종(`MenuId.ADMIN_SCREENS`)의 부여·회수는 통합관리자만 (403 E-AUTH-002, R-07).
     *
     * @param changedMenuIds 이번 요청으로 실제로 바뀌는 화면 ID — 변화가 없는 화면은 넣지 않는다
     */
    fun assertCanGrantAdminScreen(principal: UserPrincipal, changedMenuIds: Collection<String>) {
        if (principal.superAdmin) return
        val admin = changedMenuIds.filter { it in MenuId.ADMIN_SCREENS }.distinct()
        if (admin.isNotEmpty()) {
            throw BusinessException(ErrorCode.AUTH_MENU_DENIED, "$MSG_ADMIN_SCREEN [${admin.joinToString(", ")}]")
        }
    }

    /**
     * 미배정 소속 계정에는 추가 허용 화면을 저장하지 않는다 (409, R-01 · 01 ACC-14 · 03 MNP-15).
     * 미전달(null)·빈 목록은 허용한다.
     */
    fun assertNoExtraMenusForUnassigned(dept: Map<String, Any?>, requested: List<String>?) {
        if (isUnassignedDept(dept) && !requested.isNullOrEmpty()) {
            throw BusinessRuleException(MSG_UNASSIGNED_EXTRA_MENU)
        }
    }
}
