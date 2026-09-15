package com.dwje.api

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.security.PasswordEncoderService
import com.dwje.api.config.AppProperties
import com.dwje.api.model.request.UserSaveRequest
import com.dwje.api.repository.AuditLogRepository
import com.dwje.api.repository.SystemUserRepository
import com.dwje.api.service.AuditLogService
import com.dwje.api.service.AuthorizationService
import com.dwje.api.service.SystemUserService
import com.dwje.api.service.planGrantChanges
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * 계정별 추가 허용 화면(V30) — 서비스 규약.
 *
 * 1. 치환 계획: 요청 목록 = 결과. 없던 것은 부여, 빠진 것은 회수, 순서·중복 무관
 * 2. 검증: 없는 화면 ID 가 하나라도 있으면 400(field=extraMenuIds) — 저장 전에 걸러 계정 정보도 바뀌지 않는다
 * 3. 적용: null 은 그대로, [] 는 전부 회수, 바뀐 것만 감사 이력(USER_MENU_PERM)
 * 4. 요청 DTO 에 extraMenuIds 가 있다(계약)
 */
class AccountMenuGrantTest {

    /** 원천 없이 도는 저장소 — 추가 허용 표만 메모리로 */
    private class MemRepo : SystemUserRepository(mock(NamedParameterJdbcTemplate::class.java)) {
        val active = setOf("dash-ai", "sys-account", "qc-aoi")
        val grants = linkedMapOf<String, MutableSet<String>>()
        override fun findActiveMenuIds(menuIds: Collection<String>): Set<String> = menuIds.filter { it in active }.toSet()
        override fun findUserGrantIds(empNo: String): Set<String> = grants[empNo]?.toSet() ?: emptySet()
        override fun insertUserGrants(empNo: String, menuIds: Collection<String>, actor: String): Int {
            grants.getOrPut(empNo) { linkedSetOf() }.addAll(menuIds); return menuIds.size
        }
        override fun deleteUserGrants(empNo: String, menuIds: Collection<String>): Int {
            grants[empNo]?.removeAll(menuIds.toSet()); return menuIds.size
        }
        override fun findUserGrants(empNos: Collection<String>): Map<String, List<String>> =
            empNos.associateWith { (grants[it] ?: emptySet()).sorted() }
        /** 부서 기본 권한 표 — 부서 1(전산)만 sys-account 를 기본으로 가진다 */
        override fun deptHasMenuPerm(deptId: Int, menuId: String): Boolean = deptId == 1 && menuId == "sys-account"
        val passwords = mutableMapOf<String, String>()
        override fun updatePasswordHash(empNo: String, pwdHash: String, actor: String): Int { passwords[empNo] = pwdHash; return 1 }
    }

    private class MemAudit : AuditLogService(mock(AuditLogRepository::class.java), mock(AuthorizationService::class.java)) {
        val perm = mutableListOf<Pair<String, String>>()
        override fun recordPermChange(actCd: String, targetKindCd: String, targetNm: String, detail: String, targetDeptId: Int?, targetUserId: String?) {
            perm += actCd to detail
        }
        override fun record(logType: String, menuId: String?, fieldKey: String?, targetDesc: String?, resultCd: String, maskedCnt: Int, remark: String?) {}
    }

    private val repo = MemRepo()
    private val audit = MemAudit()
    private val encoder = PasswordEncoderService(com.dwje.api.config.PasswordProperties())
    private val service = SystemUserService(repo, mock(AuthorizationService::class.java), audit, encoder)

    private fun principal(deptId: Int, superAdmin: Boolean = false, menuPerms: Set<String> = emptySet()) =
        com.dwje.api.common.security.UserPrincipal(
            userId = "P$deptId", userName = "p", deptId = deptId, deptName = "d", deptAbbr = null, positionCd = null,
            plantCd = null, superAdmin = superAdmin, menuPerms = menuPerms, dataPerms = emptySet()
        )

    @Test
    @DisplayName("치환 계획 — 요청 목록이 결과다")
    fun plan() {
        val p = planGrantChanges(current = setOf("a", "b"), requested = listOf("b", "c", "c"))
        assertEquals(listOf("c"), p.add, "중복 요청은 한 번만")
        assertEquals(listOf("a"), p.remove)
        assertEquals(planGrantChanges(setOf("a"), emptyList()), com.dwje.api.service.GrantPlan(emptyList(), listOf("a")), "[] 는 전부 회수")
        assertEquals(planGrantChanges(setOf("a"), listOf("a")), com.dwje.api.service.GrantPlan(emptyList(), emptyList()), "같으면 변화 없음")
    }

    @Test
    @DisplayName("검증 — null 은 그대로(null), 없는 화면은 400 field=extraMenuIds, 공백·중복은 정리")
    fun validate() {
        assertNull(service.validateExtraMenus(null))
        assertEquals(listOf("dash-ai", "sys-account"), service.validateExtraMenus(listOf(" dash-ai ", "sys-account", "dash-ai", "")))
        val e = assertThrows(InvalidParameterException::class.java) { service.validateExtraMenus(listOf("dash-ai", "NO_SUCH_MENU")) }
        assertEquals("extraMenuIds", e.field); assertTrue(e.message!!.contains("NO_SUCH_MENU"))
    }

    @Test
    @DisplayName("적용 — 부여·회수·유지, 바뀐 것만 USER_MENU_PERM 이력, null 은 손대지 않음")
    fun apply() {
        assertEquals(listOf("dash-ai", "sys-account"), service.applyExtraMenus("W1", "W1", listOf("sys-account", "dash-ai"), "10000"))
        assertEquals(1, audit.perm.size); assertEquals("USER_MENU_PERM", audit.perm[0].first)
        assertTrue(audit.perm[0].second.contains("부여 [sys-account, dash-ai]"))

        assertEquals(listOf("dash-ai", "sys-account"), service.applyExtraMenus("W1", "W1", null, "10000"), "미전달은 유지")
        assertEquals(1, audit.perm.size, "변화 없으면 이력 없음")

        assertEquals(listOf("qc-aoi", "sys-account"), service.applyExtraMenus("W1", "W1", listOf("sys-account", "qc-aoi"), "10000"))
        assertTrue(audit.perm[1].second.contains("부여 [qc-aoi]") && audit.perm[1].second.contains("회수 [dash-ai]"))

        assertEquals(emptyList<String>(), service.applyExtraMenus("W1", "W1", emptyList(), "10000"), "[] 는 전부 회수")
        assertTrue(audit.perm[2].second.contains("회수 [qc-aoi, sys-account]"))
        assertEquals(3, audit.perm.size)
    }

    @Test
    @DisplayName("계약 — UserSaveRequest 에 extraMenuIds 가 있고 기본은 null(미전달=유지)")
    fun dto() {
        val om = ObjectMapper().findAndRegisterModules()
        val a = om.readValue("""{"name":"x"}""", UserSaveRequest::class.java)
        assertNull(a.extraMenuIds)
        val b = om.readValue("""{"extraMenuIds":[]}""", UserSaveRequest::class.java)
        assertEquals(emptyList<String>(), b.extraMenuIds)
        assertEquals(listOf("sys-account"), om.readValue("""{"extraMenuIds":["sys-account"]}""", UserSaveRequest::class.java).extraMenuIds)
    }

    // ── 관리자 비밀번호 변경 (2026-09-14) ──

    @Test
    @DisplayName("관리자 판정 — 통합관리자 또는 부서 기본 sys-account. 계정 추가 허용으로만 받은 sys-account 는 아니다")
    fun canChangePassword() {
        assertTrue(service.canChangePassword(principal(deptId = 9, superAdmin = true)))
        assertTrue(service.canChangePassword(principal(deptId = 1)), "전산팀 — 부서 기본 권한")
        assertTrue(!service.canChangePassword(principal(deptId = 2, menuPerms = setOf("sys-account"))), "추가 허용으로 sys-account 를 가진 품질팀 사용자는 불가")
    }

    @Test
    @DisplayName("비밀번호 준비 — 비어 있으면 그대로(null), 비관리자 403, 정책 위반·사번 포함 400, 통과하면 해시(원문 아님)")
    fun preparePassword() {
        assertNull(service.preparePasswordChange(null, "W1", principal(2)))
        assertNull(service.preparePasswordChange("   ", "W1", principal(2)), "공백만이면 미변경")

        val denied = assertThrows(com.dwje.api.common.exception.BusinessException::class.java) {
            service.preparePasswordChange("Good!Pass1", "W1", principal(2, menuPerms = setOf("sys-account")))
        }
        assertEquals(com.dwje.api.common.response.ErrorCode.AUTH_MENU_DENIED, denied.errorCode, "403")

        assertThrows(InvalidParameterException::class.java) { service.preparePasswordChange("short", "W1", principal(1)) }
        val emp = assertThrows(InvalidParameterException::class.java) { service.preparePasswordChange("W1-Good!Pass", "W1", principal(1)) }
        assertEquals("password", emp.field)

        val hash = service.preparePasswordChange("Good!Pass1", "W1", principal(1))!!
        assertTrue(hash != "Good!Pass1" && encoder.matches("Good!Pass1", hash), "해시로만 저장")
    }
}
