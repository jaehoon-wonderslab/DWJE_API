package com.dwje.api

import com.dwje.api.common.exception.MenuAccessDeniedException
import com.dwje.api.common.exception.UnauthenticatedException
import com.dwje.api.common.exception.WriteAccessDeniedException
import com.dwje.api.common.response.ErrorCode
import com.dwje.api.common.security.JwtTokenProvider
import com.dwje.api.common.security.UserContext
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.common.util.MenuId
import com.dwje.api.config.JwtProperties
import com.dwje.api.middleware.JwtAuthFilter
import com.dwje.api.repository.AuthRepository
import com.dwje.api.service.AuthorizationService
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

/**
 * 권한 판정 공통 기반 — 공통 9.4 CMN-06(쓰기 동작) · 03 MNP-15(미배정 고정) · 01 ACC-03(초기 비밀번호 차단)
 *
 * 1. requireWrite (V70 — 조회/쓰기 칸 통합): 통합관리자 통과, 화면 접근 없음 → E-AUTH-002,
 *    화면 접근 있음 → 통과, 미배정 계정 → E-AUTH-004
 * 2. 미배정: DB 행이 넓게 들어가도 화면은 고정 5개와의 교집합, 데이터 권한은 빈 집합, 쓰기 동작은 어디서도 거부
 * 3. 잠긴 계정의 토큰은 다음 요청부터 401(잠금 문구)
 * 4. 초기 비밀번호 변경 전 계정은 허용 목록(정확 일치) 밖 요청이 403 E-AUTH-006
 */
class WritePermissionTest {

    @AfterEach
    fun clear() = UserContext.clear()

    private fun userRow(dept: String, superAdmin: Boolean = false, state: String = "ACTIVE", pwdChange: Boolean = false) =
        mapOf<String, Any?>(
            "userId" to "U1", "userName" to "시험", "deptId" to 9, "deptName" to dept,
            "positionCd" to null, "plantCd" to null, "superAdmin" to superAdmin, "userStateCd" to state,
            "pwdChangeRequired" to pwdChange
        )

    private fun authz(row: Map<String, Any?>, menus: Set<String>, data: Set<String> = setOf("qty")): AuthorizationService {
        val repo = mock(AuthRepository::class.java)
        doReturn(row).`when`(repo).findUserWithDept("U1")
        doReturn(menus).`when`(repo).findEffectiveMenuPermissions("U1")
        doReturn(data).`when`(repo).findDataPermissions(9)
        return AuthorizationService(repo)
    }

    private fun login(vararg menus: String, unassigned: Boolean = false, superAdmin: Boolean = false) = UserContext.set(
        UserPrincipal("U1", "시험", 9, if (unassigned) "미배정" else "시험부서", null, null, superAdmin,
            menuPerms = menus.toSet(), unassigned = unassigned)
    )

    private val service = AuthorizationService(mock(AuthRepository::class.java))

    @Test
    @DisplayName("requireWrite — 접근 없음 E-AUTH-002, 접근 있음 통과, 미배정 E-AUTH-004([화면 ID]), 통합관리자 통과")
    fun requireWriteRules() {
        login()
        assertThrows(MenuAccessDeniedException::class.java) { service.requireWrite(MenuId.SYS_GLOSS) }

        // 화면에 접근할 수 있으면 그 화면의 모든 동작을 허용한다 — 쓰기 칸은 따로 없다
        login(MenuId.SYS_GLOSS)
        assertEquals("U1", service.requireWrite(MenuId.SYS_GLOSS).userId)
        assertTrue(service.canWrite(MenuId.SYS_GLOSS))
        assertFalse(service.canWrite(MenuId.SYS_SYNC))

        login(MenuId.CHAT_HISTORY, unassigned = true)
        val e = assertThrows(WriteAccessDeniedException::class.java) { service.requireWrite(MenuId.CHAT_HISTORY) }
        assertEquals(ErrorCode.AUTH_WRITE_DENIED, e.errorCode)
        assertEquals("미배정 계정은 이 동작을 할 수 없습니다. [chat-history]", e.message)
        assertFalse(service.canWrite(MenuId.CHAT_HISTORY))
        // 미배정이라도 접근 권한이 없는 화면은 E-AUTH-002 가 먼저다
        assertThrows(MenuAccessDeniedException::class.java) { service.requireWrite(MenuId.SYS_GLOSS) }

        login(superAdmin = true)
        assertEquals("U1", service.requireWrite(MenuId.SYS_SYNC).userId)
        assertEquals("U1", service.requireWrite(MenuId.SYS_CHAT_HISTORY).userId)
    }

    @Test
    @DisplayName("requireAnyWrite — 두 화면 중 하나에 접근하면 통과, 미배정은 E-AUTH-004")
    fun requireAnyWrite() {
        login(MenuId.SYS_GW_DEPT)
        assertEquals("U1", service.requireAnyWrite(MenuId.SYS_ACCOUNT, MenuId.SYS_GW_DEPT).userId)
        login()
        assertThrows(MenuAccessDeniedException::class.java) { service.requireAnyWrite(MenuId.SYS_ACCOUNT, MenuId.SYS_GW_DEPT) }
        login(MenuId.SYS_ACCOUNT, MenuId.SYS_GW_DEPT, unassigned = true)
        assertThrows(WriteAccessDeniedException::class.java) { service.requireAnyWrite(MenuId.SYS_ACCOUNT, MenuId.SYS_GW_DEPT) }
    }

    @Test
    @DisplayName("일반 부서 — 화면 = 뷰 행 전부, 접근하는 화면은 모두 쓰기 가능, 데이터 = 부서 행")
    fun normalDeptPrincipal() {
        val p = authz(userRow("전산팀"), setOf("sys-account", "dash-ai")).loadPrincipal("U1")
        assertEquals(setOf("sys-account", "dash-ai"), p.menuPerms)
        assertTrue(p.canWriteMenu("sys-account") && p.canWriteMenu("dash-ai"))
        assertFalse(p.canWriteMenu("sys-sync"))
        assertEquals(setOf("qty"), p.dataPerms)
        assertFalse(p.unassigned)
    }

    @Test
    @DisplayName("미배정 — DB 에 sys-account·qc-aoi 행과 데이터 행이 있어도 고정 5개 안의 화면만, 쓰기 동작 불가, 데이터 0건")
    fun unassignedIsClamped() {
        val menus = setOf("dash-ai", "ai-chat", "chat-history", "sys-account", "qc-aoi")
        val p = authz(userRow("미배정"), menus, data = setOf("qty", "price")).loadPrincipal("U1")
        assertEquals(setOf("dash-ai", "ai-chat", "chat-history"), p.menuPerms)
        assertTrue(MenuId.UNASSIGNED_SCREENS.containsAll(p.menuPerms))
        assertEquals(emptySet<String>(), p.dataPerms)
        assertTrue(p.unassigned)
        assertFalse(p.canWriteMenu("dash-ai"))
        assertFalse(p.canWriteMenu("chat-history"))
    }

    @Test
    @DisplayName("통합관리자 — 권한 행을 읽지 않고 모든 화면 쓰기 통과")
    fun superAdminPasses() {
        val p = authz(userRow("통합관리자", superAdmin = true), emptySet()).loadPrincipal("U1")
        assertTrue(p.canWriteMenu(MenuId.SYS_CHAT_HISTORY) && p.canWriteMenu(MenuId.SYS_MENU))
    }

    @Test
    @DisplayName("잠긴 계정의 토큰은 다음 요청부터 401 — 잠금 문구, 정지는 정지 문구")
    fun lockedTokenRejected() {
        val locked = assertThrows(UnauthenticatedException::class.java) {
            authz(userRow("전산팀", state = "LOCKED"), emptySet()).loadPrincipal("U1")
        }
        assertTrue(locked.message.contains("잠겼습니다"))
        val suspended = assertThrows(UnauthenticatedException::class.java) {
            authz(userRow("전산팀", state = "SUSPENDED"), emptySet()).loadPrincipal("U1")
        }
        assertTrue(suspended.message.contains("정지"))
    }

    @Test
    @DisplayName("초기 비밀번호 변경 전 — 허용 5개(정확 일치)만 통과, 그 밖은 컨트롤러 전에 403 E-AUTH-006")
    fun passwordChangeGate() {
        val tokens = JwtTokenProvider(JwtProperties(secret = "test-only-jwt-signing-secret-123456"))
        val principal = UserPrincipal("U1", "시험", 9, "미배정", null, null, false,
            menuPerms = setOf("dash-ai"), pwdChangeRequired = true)
        val authz = mock(AuthorizationService::class.java)
        doReturn(principal).`when`(authz).loadPrincipal("U1", false, null)
        val filter = JwtAuthFilter(tokens, authz, ObjectMapper())
        val token = tokens.createAccessToken("U1", "시험", 9, "미배정", false, null)

        fun call(method: String, path: String): Pair<Int, Boolean> {
            val req = MockHttpServletRequest(method, path).apply { addHeader("Authorization", "Bearer $token") }
            val res = MockHttpServletResponse()
            var reached = false
            filter.doFilter(req, res, FilterChain { _, _ -> reached = true })
            if (!reached) assertTrue(res.contentAsString.contains("E-AUTH-006"), res.contentAsString)
            return res.status to reached
        }

        assertEquals(200 to true, call("GET", "/api/v1/auth/me"))
        assertEquals(200 to true, call("POST", "/api/v1/auth/password"))
        assertEquals(200 to true, call("POST", "/api/v1/auth/logout"))
        assertEquals(403 to false, call("GET", "/api/v1/system/users"))
        assertEquals(403 to false, call("POST", "/api/v1/ai/chat/ask"))
        assertEquals(403 to false, call("GET", "/api/v1/dashboard/ai/summary"))
        // 접두사 일치가 아니다 — 비밀번호 경로 아래 다른 경로·다른 메서드는 막힌다
        assertEquals(403 to false, call("GET", "/api/v1/auth/password"))
        assertEquals(403 to false, call("POST", "/api/v1/auth/me"))
        assertEquals(403 to false, call("GET", "/api/v1/auth/switch-targets"))
    }
}
