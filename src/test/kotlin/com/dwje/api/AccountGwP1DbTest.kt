package com.dwje.api

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.exception.WriteAccessDeniedException
import com.dwje.api.common.security.UserContext
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.common.util.MenuId
import com.dwje.api.model.request.GwDeptMapBulkSaveRequest
import com.dwje.api.model.request.UserSaveRequest
import com.dwje.api.service.AuditLogService
import com.dwje.api.service.GwDeptMapService
import com.dwje.api.service.SystemUserService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mockingDetails
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.annotation.Transactional

/**
 * 01 ACC-06~09 · 02 GWD-04·05 — 실제 로컬 DB, 롤백. 감사·권한 변경 이력은 지울 수 없으므로 감사 서비스는 목.
 */
@SpringBootTest(properties = ["app.ai.prewarm-enabled=false"])
@ActiveProfiles("local")
@Transactional
class AccountGwP1DbTest {

    @Autowired lateinit var users: SystemUserService
    @Autowired lateinit var gw: GwDeptMapService
    @Autowired lateinit var auditRepo: com.dwje.api.repository.AuditLogRepository
    @Autowired lateinit var authz: com.dwje.api.service.AuthorizationService
    @Autowired lateinit var jdbc: NamedParameterJdbcTemplate
    @MockitoBean lateinit var auditMock: AuditLogService

    /** 조회용 진짜 감사 서비스 — 빈은 목이라 따로 만든다(조회만 한다) */
    private val audit by lazy { AuditLogService(auditRepo, authz) }

    private val admin = UserPrincipal("10000", "관리자", 1, "통합관리자", null, null, true)

    @BeforeEach
    fun login() = UserContext.set(admin)

    @AfterEach
    fun clear() = UserContext.clear()

    private fun exec(sql: String) = jdbc.update(sql, MapSqlParameterSource())
    private fun long(sql: String) = jdbc.queryForObject(sql, MapSqlParameterSource(), Long::class.java)!!

    /** 목 감사 서비스에 남은 권한 변경 이력 detail (대상 표기, 내용) */
    private fun permLogs(): List<Pair<String, String>> = mockingDetails(auditMock).invocations
        .filter { it.method.name == "recordPermChange" }.map { it.arguments[2] as String to it.arguments[3] as String }

    private fun auditCount(type: String) = mockingDetails(auditMock).invocations
        .count { it.method.name == "record" && it.arguments[0] == type }

    @Test
    @DisplayName("ACC-06 — 계정 수정에서 부서만 바꾸면 「부서 이동 → …」 한 줄(대상 이름(사번))·감사 PERM_CHANGE 1, 이름·직급만 바꾸면 바뀐 필드만")
    fun deptMoveViaUpdate() {
        exec("INSERT INTO ax.tb_sys_user (user_id, user_nm, dept_id, position_cd) VALUES ('ZT600', '시험', 2, 'STAFF')")
        val res = users.updateUser("ZT600", UserSaveRequest(deptId = 3))
        assertEquals(3L, long("SELECT dept_id FROM ax.tb_sys_user WHERE user_id = 'ZT600'"))
        val logs = permLogs()
        assertEquals(1, logs.size, "부서만 바뀌면 「계정 수정」 줄은 없다")
        assertEquals("시험(ZT600)", logs.single().first)
        assertTrue(logs.single().second.startsWith("부서 이동 → 생산관리팀 (메뉴 "))
        assertEquals(1, auditCount("PERM_CHANGE"))
        assertEquals(res["appliedMenuCnt"], long("SELECT count(*) FROM ax.tb_sys_dept_menu_perm p JOIN ax.tb_sys_menu m ON m.menu_id = p.menu_id AND m.use_flg = 'Y' JOIN ax.tb_sys_menu_group g ON g.group_id = m.group_id AND g.use_flg = 'Y' WHERE p.dept_id = 3 AND p.can_read").toInt())

        org.mockito.Mockito.clearInvocations(auditMock)
        users.updateUser("ZT600", UserSaveRequest(name = "시험2", pos = "LEADER", deptId = 3))
        assertEquals(listOf("시험2(ZT600)" to "계정 수정 — 이름 시험→시험2, 직급 STAFF→LEADER"), permLogs())
        assertEquals(0, auditCount("PERM_CHANGE"))
    }

    @Test
    @DisplayName("ACC-06 — 미배정으로 옮기면 상속 수는 고정 화면 수(5)·데이터 0")
    fun moveToUnassigned() {
        exec("INSERT INTO ax.tb_sys_user (user_id, user_nm, dept_id, position_cd) VALUES ('ZT601', '시험', 2, 'STAFF')")
        val r = users.changeUserDept("ZT601", 59)
        assertEquals(MenuId.UNASSIGNED_SCREENS.size, r["appliedMenuCnt"])
        assertEquals(0, r["appliedDataCnt"])
    }

    @Test
    @DisplayName("ACC-07·08 — 대기 목록 email(가린 값)·requestedAt, joinSrc 열·필터, 상태 쉼표 여러 개, 요약 unassignedCnt")
    fun listsAndSummary() {
        exec("INSERT INTO ax.tb_sys_user (user_id, user_nm, dept_id, position_cd, user_state_cd, email, ins_user) VALUES ('ZT602', '가입', 59, 'STAFF', 'PENDING', 'signup.user@dwje.co.kr', 'ZT602')")
        val (pending, _) = users.getPendingUsers(1, 50, "ZT602")
        val row = pending.single()
        assertNotEquals("signup.user@dwje.co.kr", row["email"])
        assertEquals(row["emailMasked"], row["email"])
        assertTrue((row["requestedAt"] as String).matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}")))
        assertEquals("SIGNUP", row["joinSrc"])

        val (gwRows, gwMeta) = users.getUsers(null, null, null, null, 1, 1, "groupware")
        assertEquals(long("SELECT count(*) FROM ax.tb_sys_user WHERE ins_user = 'SYSTEM' AND remark LIKE '그룹웨어 자동 가입%'"), gwMeta.total)
        assertTrue(gwRows.all { it["joinSrc"] == "GROUPWARE" })
        assertEquals("joinSrc", assertThrows(InvalidParameterException::class.java) { users.getUsers(null, null, null, null, 1, 1, "X") }.field)

        exec("UPDATE ax.tb_sys_user SET user_state_cd = 'LOCKED' WHERE user_id = '10003'")
        val (multi, _) = users.getUsers(null, null, "LOCKED,PENDING", null, 1, 500)
        assertTrue(multi.isNotEmpty() && multi.all { it["state"] in setOf("LOCKED", "PENDING") })
        assertThrows(InvalidParameterException::class.java) { users.getUsers(null, null, "LOCKED,NOPE", null, 1, 10) }

        assertEquals(long("SELECT count(*) FROM ax.tb_sys_user WHERE dept_id = 59"), users.getAccountSummary()["unassignedCnt"])
    }

    @Test
    @DisplayName("ACC-09 — 행 actNm·targetUserId·targetDeptId, actType 쉼표 여러 개, 대상 사번 정확 일치, 365일 초과 400, 그룹웨어 화면만이면 제한 조회")
    fun permLogQuery() {
        val (rows, _) = audit.getPermLogs("2026-09-01", null, null, "ACCOUNT,DEPT", 1, 50)
        assertTrue(rows.all { it["actType"] in setOf("ACCOUNT", "DEPT") && "actNm" in it && "targetUserId" in it && "targetDeptId" in it })
        rows.firstOrNull { it["actType"] == "ACCOUNT" }?.let { assertEquals("계정", it["actNm"]) }
        val someUser = jdbc.queryForList("SELECT target_user_id FROM ax.tb_sys_perm_log WHERE target_user_id IS NOT NULL LIMIT 1",
            MapSqlParameterSource(), String::class.java).firstOrNull()
        if (someUser != null) {
            val (byUser, _) = audit.getPermLogs("2025-10-02", "2026-10-01", null, null, 1, 0, null, someUser)
            assertTrue(byUser.all { it["targetUserId"] == someUser })
        }
        assertThrows(InvalidParameterException::class.java) { audit.getPermLogs("2025-01-01", "2026-10-01", null, null, 1, 10) }

        UserContext.set(UserPrincipal("10004", "전산", 5, "전산팀", null, null, false, menuPerms = setOf(MenuId.SYS_GW_DEPT)))
        val (gwOnly, _) = audit.getPermLogs("2026-09-01", null, null, null, 1, 0)
        assertTrue(gwOnly.all { it["actType"] == "GW_DEPT_MAP" || (it["actType"] == "ACCOUNT" && (it["detail"] as String).startsWith("부서 이동 →")) })
        assertEquals(0L, audit.getPermLogs("2026-09-01", null, null, "DEPT", 1, 10).second.total)
    }

    @Test
    @DisplayName("GWD-04·05 — 매핑 행 unassignedPwdInitCnt·미배정 행 pwdChangeRequired, 일괄 지정 한 트랜잭션(이력 부서마다 1 + 감사 1), 틀리면 0건")
    fun gwBulk() {
        @Suppress("UNCHECKED_CAST")
        val maps = gw.getMaps(null, null, 1, 0).first
        assertTrue(maps.all { "unassignedPwdInitCnt" in it })
        @Suppress("UNCHECKED_CAST")
        val unassigned = gw.getUnassignedUsers(null, 1, 5).first
        assertTrue(unassigned.all { "pwdChangeRequired" in it })

        exec("INSERT INTO ax.tb_sys_dept_gw_map (gw_dept_nm, dept_id, remark) VALUES ('ZT부서A', 2, '기존 메모')")
        val res = gw.saveMapsBulk(GwDeptMapBulkSaveRequest(gwDeptNms = listOf("ZT부서A", "ZT부서B ", "ZT부서B "), deptId = 4))
        assertEquals(2, res["savedCnt"])
        assertEquals("기존 메모", jdbc.queryForObject("SELECT remark FROM ax.tb_sys_dept_gw_map WHERE gw_dept_nm = 'ZT부서A'", MapSqlParameterSource(), String::class.java))
        assertEquals(1L, long("SELECT count(*) FROM ax.tb_sys_dept_gw_map WHERE gw_dept_nm = 'ZT부서B ' AND dept_id = 4"), "공백을 다듬지 않는다")
        assertEquals(2, permLogs().size)
        assertEquals(1, auditCount("PERM_CHANGE"))

        assertThrows(InvalidParameterException::class.java) {
            gw.saveMapsBulk(GwDeptMapBulkSaveRequest(gwDeptNms = listOf("ZT부서C"), deptId = 99999))
        }
        assertEquals(0L, long("SELECT count(*) FROM ax.tb_sys_dept_gw_map WHERE gw_dept_nm = 'ZT부서C'"))
        gw.saveMapsBulk(GwDeptMapBulkSaveRequest(gwDeptNms = listOf("ZT부서C"), deptId = 4, joinYn = "N"))
        assertNull(jdbc.queryForObject("SELECT dept_id FROM ax.tb_sys_dept_gw_map WHERE gw_dept_nm = 'ZT부서C'", MapSqlParameterSource(), Int::class.java))
        assertThrows(InvalidParameterException::class.java) { gw.saveMapsBulk(GwDeptMapBulkSaveRequest(gwDeptNms = (1..201).map { "ZT$it" })) }

        // V70 — 화면에 접근하면 쓰기 동작도 된다. 쓰기 동작을 못 하는 것은 미배정 계정뿐이다
        UserContext.set(UserPrincipal("10001", "품질", 59, "미배정", null, null, false, menuPerms = setOf(MenuId.SYS_GW_DEPT), unassigned = true))
        assertThrows(WriteAccessDeniedException::class.java) { gw.saveMapsBulk(GwDeptMapBulkSaveRequest(gwDeptNms = listOf("ZT부서D"))) }
    }
}
