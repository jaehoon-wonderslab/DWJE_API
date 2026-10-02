package com.dwje.api

import com.dwje.api.common.exception.BusinessException
import com.dwje.api.common.response.ErrorCode
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.config.AppProperties
import com.dwje.api.service.SystemDeptGuard
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 시스템 부서(통합관리자·미배정) 보호 규칙 — 공통 기획서 4.1 CMN-01
 *
 * 1. 누가 해도 안 되는 변경은 409 E-RULE-001 (시스템 부서 권한 변경·복사, 이름 변경·삭제, 미배정 계정 추가 메뉴)
 * 2. 통합관리자만 할 수 있는 변경은 403 E-AUTH-002 (통합관리자 부서 배정, 관리 화면 4종 부여·회수)
 * 3. 미배정 판정은 부서 이름(app.unassigned-dept-name) 하나로 한다
 */
class SystemDeptGuardTest {

    private val guard = SystemDeptGuard(AppProperties())

    private fun dept(id: Int, nm: String, superAdmin: Boolean = false) =
        mapOf<String, Any?>("deptId" to id, "deptNm" to nm, "superAdmin" to superAdmin)

    private val superDept = dept(1, "통합관리자", superAdmin = true)
    private val unassigned = dept(59, "미배정")
    private val it = dept(5, "전산팀")

    private fun user(superAdmin: Boolean) =
        UserPrincipal("10004", "전산", 5, "전산팀", null, null, null, superAdmin)

    private fun assertCode(code: ErrorCode, block: () -> Unit): BusinessException {
        val e = assertThrows(BusinessException::class.java) { block() }
        assertEquals(code, e.errorCode, e.message)
        return e
    }

    @Test
    @DisplayName("시스템 부서 권한 변경은 409 — 통합관리자·미배정 문구가 다르고, 일반 부서는 통과")
    fun systemDeptPermIsFixed() {
        val e1 = assertCode(ErrorCode.RULE_VIOLATION) { guard.assertNotSystemDeptPerm(superDept) }
        assertEquals(SystemDeptGuard.MSG_SUPER_ADMIN_PERM_FIXED, e1.message)
        val e2 = assertCode(ErrorCode.RULE_VIOLATION) { guard.assertNotSystemDeptPerm(unassigned) }
        assertEquals(SystemDeptGuard.MSG_UNASSIGNED_PERM_FIXED, e2.message)
        assertDoesNotThrow { guard.assertNotSystemDeptPerm(it) }
    }

    @Test
    @DisplayName("통합관리자 부서 배정은 통합관리자만 — 아니면 403 E-AUTH-002(field=deptId)")
    fun assignSuperAdminDeptOnlyBySuperAdmin() {
        val e = assertCode(ErrorCode.AUTH_MENU_DENIED) { guard.assertCanAssignDept(user(false), superDept) }
        assertEquals("deptId", e.field)
        assertDoesNotThrow { guard.assertCanAssignDept(user(true), superDept) }
        assertDoesNotThrow { guard.assertCanAssignDept(user(false), it) }
    }

    @Test
    @DisplayName("시스템 부서 이름 변경·삭제는 409 — 약칭·설명만 바꾸는 수정(이름 그대로·null)은 통과")
    fun systemDeptImmutable() {
        assertCode(ErrorCode.RULE_VIOLATION) { guard.assertSystemDeptImmutable(unassigned, newName = "임시") }
        assertCode(ErrorCode.RULE_VIOLATION) { guard.assertSystemDeptImmutable(superDept, deleting = true) }
        assertDoesNotThrow { guard.assertSystemDeptImmutable(unassigned, newName = "미배정") }
        assertDoesNotThrow { guard.assertSystemDeptImmutable(unassigned, newName = null) }
        assertDoesNotThrow { guard.assertSystemDeptImmutable(it, newName = "전산실", deleting = true) }
    }

    @Test
    @DisplayName("관리 화면 4종 부여·회수는 통합관리자만 — 바뀌는 화면에 관리 화면이 없으면 통과")
    fun adminScreensOnlyBySuperAdmin() {
        val e = assertCode(ErrorCode.AUTH_MENU_DENIED) { guard.assertCanGrantAdminScreen(user(false), listOf("qc-aoi", "sys-menu")) }
        assertTrue(e.message.contains("[sys-menu]"))
        assertDoesNotThrow { guard.assertCanGrantAdminScreen(user(false), listOf("qc-aoi", "sys-audit")) }
        assertDoesNotThrow { guard.assertCanGrantAdminScreen(user(true), listOf("sys-account", "sys-gw-dept")) }
    }

    @Test
    @DisplayName("미배정 계정 추가 메뉴는 409 — null·빈 목록은 허용, 일반 부서는 무관")
    fun noExtraMenusForUnassigned() {
        assertCode(ErrorCode.RULE_VIOLATION) { guard.assertNoExtraMenusForUnassigned(unassigned, listOf("qc-aoi")) }
        assertDoesNotThrow { guard.assertNoExtraMenusForUnassigned(unassigned, null) }
        assertDoesNotThrow { guard.assertNoExtraMenusForUnassigned(unassigned, emptyList()) }
        assertDoesNotThrow { guard.assertNoExtraMenusForUnassigned(it, listOf("qc-aoi")) }
    }

    @Test
    @DisplayName("미배정 판정은 설정한 부서 이름 하나로 한다")
    fun unassignedByConfiguredName() {
        val custom = SystemDeptGuard(AppProperties(unassignedDeptName = "부서 미정"))
        assertTrue(custom.isUnassignedDept(dept(70, "부서 미정")))
        assertEquals(null, custom.systemRoleOf(unassigned))
        assertEquals("UNASSIGNED", guard.systemRoleOf(unassigned))
        assertEquals("SUPER_ADMIN", guard.systemRoleOf(superDept))
    }
}
