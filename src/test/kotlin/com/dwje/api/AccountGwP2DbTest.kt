package com.dwje.api

import com.dwje.api.common.exception.BusinessException
import com.dwje.api.common.exception.ConflictingValueException
import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.exception.ResourceNotFoundException
import com.dwje.api.common.security.UserContext
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.model.request.DeptSaveRequest
import com.dwje.api.model.request.GwDeptMapSaveRequest
import com.dwje.api.model.request.UserSaveRequest
import com.dwje.api.repository.AuthRepository
import com.dwje.api.service.AuditLogService
import com.dwje.api.service.AuthService
import com.dwje.api.service.GwDeptMapService
import com.dwje.api.service.SystemUserService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.annotation.Transactional

/** 01 ACC-10~13 · 02 GWD-04·10~12 · 03 MNP-14 · 메인 전달 15 — 실제 로컬 DB, 롤백. 감사는 목 */
@SpringBootTest(properties = ["app.ai.prewarm-enabled=false"])
@ActiveProfiles("local")
@Transactional
class AccountGwP2DbTest {

    @Autowired lateinit var users: SystemUserService
    @Autowired lateinit var gw: GwDeptMapService
    @Autowired lateinit var auth: AuthService
    @Autowired lateinit var authRepo: AuthRepository
    @Autowired lateinit var jdbc: NamedParameterJdbcTemplate
    @MockitoBean lateinit var audit: AuditLogService

    private val admin = UserPrincipal("10000", "관리자", 1, "통합관리자", null, null, true)

    @BeforeEach
    fun login() = UserContext.set(admin)

    @AfterEach
    fun clear() = UserContext.clear()

    private fun exec(sql: String) = jdbc.update(sql, MapSqlParameterSource())
    private fun long(sql: String) = jdbc.queryForObject(sql, MapSqlParameterSource(), Long::class.java)!!

    @Test
    @DisplayName("ACC-10 추가 허용 사유 저장·계정 행 extraMenus[{id,reason,grantedAt,grantedBy}], 200자 초과 400")
    fun grantReasons() {
        users.createUser(UserSaveRequest(empNo = "ZT-P2A", name = "시험", deptId = 2, extraMenuIds = listOf("dash-ai"),
            extraMenuReasons = mapOf("dash-ai" to "KPI 확인", "sys-sync" to "무시됨")))
        assertEquals("KPI 확인", jdbc.queryForObject(
            "SELECT grant_reason FROM ax.tb_sys_user_menu_grant WHERE user_id = 'ZT-P2A' AND menu_id = 'dash-ai'", MapSqlParameterSource(), String::class.java))
        val row = users.getUsers("ZT-P2A", null, null, null, 1, 10).first.single { it["empNo"] == "ZT-P2A" }
        @Suppress("UNCHECKED_CAST")
        val extra = (row["extraMenus"] as List<Map<String, Any?>>).single()
        assertEquals("dash-ai", extra["id"]); assertEquals("KPI 확인", extra["reason"]); assertNotNull(extra["grantedAt"]); assertNotNull(extra["grantedBy"])
        assertEquals(listOf("dash-ai"), row["extraMenuIds"])

        users.updateUser("ZT-P2A", UserSaveRequest(extraMenuReasons = mapOf("dash-ai" to "")))
        assertNull(jdbc.queryForObject("SELECT grant_reason FROM ax.tb_sys_user_menu_grant WHERE user_id = 'ZT-P2A'", MapSqlParameterSource(), String::class.java))
        assertEquals("extraMenuReasons", assertThrows(InvalidParameterException::class.java) {
            users.updateUser("ZT-P2A", UserSaveRequest(extraMenuReasons = mapOf("dash-ai" to "가".repeat(201))))
        }.field)
    }

    @Test
    @DisplayName("ACC-11 삭제 사전 확인(막는 참조·함께 지워지는 참조·가입 경로), 막는 참조가 있으면 409 + data, 본인은 deletable=false")
    fun deleteCheck() {
        users.createUser(UserSaveRequest(empNo = "ZT-P2D", name = "삭제시험", deptId = 2, extraMenuIds = listOf("dash-ai")))
        exec("INSERT INTO ax.tb_alm_recipient (user_id, email, recv_state_cd) VALUES ('ZT-P2D', 'zt@x.local', 'RECV')")
        val ok = users.getDeleteCheck("ZT-P2D")
        assertEquals(true, ok["deletable"]); assertEquals("ADMIN", ok["joinSrc"])
        assertEquals(mapOf("recipients" to 1L, "menuGrants" to 1L, "usage" to 0L), ok["cascade"])

        exec("INSERT INTO ax.tb_ai_serving_profile (profile_id, profile_cd, version_no, profile_nm, activated_by) VALUES (999901, 'ZT', 1, 'ZT', 'ZT-P2D')")
        val blocked = users.getDeleteCheck("ZT-P2D")
        assertEquals(false, blocked["deletable"]); assertEquals(mapOf("servingProfiles" to 1L, "docs" to 0L), blocked["blocking"])
        val e = assertThrows(BusinessException::class.java) { users.deleteUser("ZT-P2D") }
        assertEquals("E-RULE-001", e.errorCode.code); assertEquals(false, (e.data as Map<*, *>)["deletable"])

        exec("DELETE FROM ax.tb_ai_serving_profile WHERE profile_id = 999901")
        users.deleteUser("ZT-P2D")
        assertEquals(0L, long("SELECT count(*) FROM ax.tb_alm_recipient WHERE user_id = 'ZT-P2D'"))
        assertEquals(false, users.getDeleteCheck("10000")["deletable"], "본인")
        assertEquals(true, users.getDeleteCheck("10000")["self"])
    }

    @Test
    @DisplayName("ACC-12 길이·빈 값 400(말없이 자르지 않음) / 메인 15-6 부서 size=0 은 page 와 무관하게 전량")
    fun validationAndDepts() {
        // 부서 약칭은 없앴다(2026-10-02) — 길이·빈 값 검사는 부서명·설명만
        assertEquals("deptNm", assertThrows(InvalidParameterException::class.java) { users.createDept(DeptSaveRequest(deptNm = "가".repeat(51))) }.field)
        assertEquals("deptNm", assertThrows(InvalidParameterException::class.java) { users.createDept(DeptSaveRequest(deptNm = "  ")) }.field)
        assertEquals("desc", assertThrows(InvalidParameterException::class.java) { users.createDept(DeptSaveRequest(deptNm = "ZT부서", desc = "가".repeat(201))) }.field)
        assertEquals("name", assertThrows(InvalidParameterException::class.java) { users.createUser(UserSaveRequest(empNo = "ZT-P2V", name = " ", deptId = 2)) }.field)
        assertEquals("name", assertThrows(InvalidParameterException::class.java) { users.createUser(UserSaveRequest(empNo = "ZT-P2V", name = "가".repeat(51), deptId = 2)) }.field)
        assertEquals("name", assertThrows(InvalidParameterException::class.java) { users.updateUser("10001", UserSaveRequest(name = "")) }.field)
        assertEquals("deptNm", assertThrows(InvalidParameterException::class.java) { users.updateDept(2, DeptSaveRequest(deptNm = " ")) }.field)
        assertEquals(0L, long("SELECT count(*) FROM ax.tb_sys_dept WHERE dept_nm LIKE 'ZT%'"))

        val total = long("SELECT count(*) FROM ax.tb_sys_dept WHERE use_flg = 'Y'")
        val (rows, meta) = users.getDepts(null, 1, 0)
        assertEquals(total, rows.size.toLong()); assertEquals(total, meta!!.total)
    }

    @Test
    @DisplayName("ACC-13 로그인 전 사번 확인은 가입 경로를 알리지 않는다 — 자동 가입 계정도 일반 중복 응답과 같은 모양")
    fun signupCheck() {
        val gwUser = jdbc.queryForObject("SELECT user_id FROM ax.tb_sys_user WHERE ins_user = 'SYSTEM' AND remark LIKE '그룹웨어 자동 가입%' LIMIT 1",
            MapSqlParameterSource(), String::class.java)!!
        val gwRes = auth.checkEmpNoAvailable(gwUser)
        val adminRes = auth.checkEmpNoAvailable("10001")
        assertEquals(false, gwRes["available"]); assertNull(gwRes["reason"])
        assertEquals(adminRes - "empNo", gwRes - "empNo", "자동 가입 여부가 응답에서 드러나지 않는다")
        assertEquals(setOf("empNo", "available", "message"), gwRes.keys)
        assertEquals(true, auth.checkEmpNoAvailable("ZT-NONE-9")["available"])
    }

    @Test
    @DisplayName("GWD-04 요약 unassignedPwdInitCnt / GWD-10 updUserNm(SYSTEM=시스템) / GWD-11 이어받기(옛 행 값 복사·옛 행 삭제, 404·409·같은 이름 400)")
    fun gwMaps() {
        val unassigned = long("SELECT dept_id FROM ax.tb_sys_dept WHERE dept_nm = '미배정'")
        assertEquals(long("SELECT count(*) FROM ax.tb_sys_user WHERE dept_id = $unassigned AND pwd_change_req_yn = 'Y'"), gw.getSummary()["unassignedPwdInitCnt"])
        val maps = gw.getMaps(null, null, 1, 0).first
        assertTrue(maps.filter { it["hasRow"] == true && it["updUser"] == "SYSTEM" }.all { it["updUserNm"] == "시스템" })

        exec("INSERT INTO ax.tb_sys_dept_gw_map (gw_dept_nm, dept_id, join_yn, remark, ins_user, upd_user) VALUES ('ZT옛부서', 3, 'Y', 'ZT 메모', '10000', '10000')")
        val r = gw.saveMap(GwDeptMapSaveRequest(gwDeptNm = "ZT새부서", deptId = 4, joinYn = "N", fromGwDeptNm = "ZT옛부서"))
        assertEquals("ZT옛부서", r["inheritedFrom"]); assertEquals("MAPPED", r["state"])
        assertEquals("3|Y|ZT 메모", jdbc.queryForObject("SELECT dept_id || '|' || join_yn || '|' || remark FROM ax.tb_sys_dept_gw_map WHERE gw_dept_nm = 'ZT새부서'", MapSqlParameterSource(), String::class.java))
        assertEquals(0L, long("SELECT count(*) FROM ax.tb_sys_dept_gw_map WHERE gw_dept_nm = 'ZT옛부서'"))
        assertThrows(ResourceNotFoundException::class.java) { gw.saveMap(GwDeptMapSaveRequest(gwDeptNm = "ZT다른", fromGwDeptNm = "ZT없음")) }
        exec("INSERT INTO ax.tb_sys_dept_gw_map (gw_dept_nm, dept_id, join_yn, ins_user, upd_user) VALUES ('ZT또옛', 3, 'Y', '10000', '10000')")
        assertThrows(ConflictingValueException::class.java) { gw.saveMap(GwDeptMapSaveRequest(gwDeptNm = "ZT새부서", fromGwDeptNm = "ZT또옛")) }
        assertEquals("fromGwDeptNm", assertThrows(InvalidParameterException::class.java) { gw.saveMap(GwDeptMapSaveRequest(gwDeptNm = "ZT또옛", fromGwDeptNm = "ZT또옛")) }.field)
    }

    @Test
    @DisplayName("GWD-12 미배정 계정 retired·stateReason, state 필터(쉼표 다중·모르는 값 400) / MNP-14 메뉴 그룹을 끄면 유효 권한에서 빠진다")
    fun unassignedAndGroupFlag() {
        val (rows, _) = gw.getUnassignedUsers(null, 1, 0)
        assertTrue(rows.all { "retired" in it && "stateReason" in it })
        assertTrue(rows.filter { it["state"] != "SUSPENDED" }.all { it["stateReason"] == null })
        val (active, meta) = gw.getUnassignedUsers(null, 1, 0, "ACTIVE,LOCKED")
        assertTrue(active.all { it["state"] in setOf("ACTIVE", "LOCKED") })
        assertEquals(rows.count { it["state"] in setOf("ACTIVE", "LOCKED") }.toLong(), meta.total)
        assertEquals("state", assertThrows(InvalidParameterException::class.java) { gw.getUnassignedUsers(null, 1, 0, "NOPE") }.field)

        val reportMenus = jdbc.queryForList("SELECT menu_id FROM ax.tb_sys_menu WHERE group_id = 'report'", MapSqlParameterSource(), String::class.java).toSet()
        val before = authRepo.findMenuPermissions(2)
        assertTrue(before.any { it in reportMenus }, "품질보증팀은 보고서 화면 권한이 있다(시험 전제)")
        exec("UPDATE ax.tb_sys_menu_group SET use_flg = 'N' WHERE group_id = 'report'")
        // 판정 뷰(V64)와 부서 기준 보조 질의가 같은 기준이다
        assertTrue(authRepo.findEffectiveMenuPermissions("10001").none { it in reportMenus })
        assertTrue(authRepo.findMenuPermissions(2).none { it in reportMenus })
    }
}
