package com.dwje.api

import com.dwje.api.common.exception.BusinessException
import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.exception.WriteAccessDeniedException
import com.dwje.api.common.response.ErrorCode
import com.dwje.api.common.exception.MenuAccessDeniedException
import com.dwje.api.common.exception.ResourceNotFoundException
import com.dwje.api.common.security.PasswordEncoderService
import com.dwje.api.common.security.UserContext
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.config.AppProperties
import com.dwje.api.model.request.GwDeptMapSaveRequest
import com.dwje.api.model.request.GwDeptReassignRequest
import com.dwje.api.repository.AuditLogRepository
import com.dwje.api.repository.AuthRepository
import com.dwje.api.repository.GwDeptMapRepository
import com.dwje.api.repository.SystemUserRepository
import com.dwje.api.service.AuditLogService
import com.dwje.api.service.AuthorizationService
import com.dwje.api.service.GwDeptMapService
import com.dwje.api.service.SystemDeptGuard
import com.dwje.api.service.SystemUserService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * 그룹웨어 부서 매핑 화면(SY-17) — 서비스 규약.
 *
 * 1. 저장은 전체 덮어쓰기 — joinYn=N 이면 부서를 비우고, 미배정·통합관리자·없는 부서는 400
 * 2. 삭제는 없는 행이면 404, 매핑 변경은 이력 GW_DEPT_MAP
 * 3. 재배정은 미배정 부서 계정 중 제안 부서가 있는 사람만 — 나머지는 skippedCnt, 이력은 계정 부서 이동과 같다
 * 4. 계정 부서 이동 API 를 그룹웨어 부서 매핑 권한만으로 부르면 미배정 계정만 옮길 수 있다
 */
class GwDeptMapServiceTest {

    /** 부서: 1 통합관리자(super) · 2 품질보증팀 · 5 전산팀 · 59 미배정 */
    private val depts = mapOf(
        1 to dept(1, "통합관리자", true), 2 to dept(2, "품질보증팀"), 5 to dept(5, "전산팀"), 59 to dept(59, "미배정")
    )

    private fun dept(id: Int, nm: String, superAdmin: Boolean = false): Map<String, Any?> =
        mapOf("deptId" to id, "deptNm" to nm, "desc" to null, "plantCd" to "PL01", "superAdmin" to superAdmin)

    /** 계정 사번 → 부서 */
    private val userDept = linkedMapOf("A1" to 59, "A2" to 59, "A3" to 59, "Q1" to 2)
    /** 계정 사번 → 상태 (없으면 ACTIVE) */
    private val userState = mutableMapOf<String, String>()

    private inner class MemUsers : SystemUserRepository(mock(NamedParameterJdbcTemplate::class.java)) {
        override fun findDept(deptId: Int) = depts[deptId]
        override fun findDeptIdByName(deptNm: String) = depts.values.firstOrNull { it["deptNm"] == deptNm }?.get("deptId") as Int?
        override fun findUserDeptId(empNo: String) = userDept[empNo]
        override fun existsUser(empNo: String) = empNo in userDept
        override fun updateUserDept(empNo: String, deptId: Int, actor: String): Int { userDept[empNo] = deptId; return 1 }
        override fun findMenuPermMatrix() = listOf(mapOf<String, Any?>("deptId" to 2, "menuId" to "qc-aoi"))
        override fun findDataPermMatrix() = emptyList<Map<String, Any?>>()
        override fun countMenuPerm(deptId: Int) = findMenuPermMatrix().count { it["deptId"] == deptId }
        override fun countDataPerm(deptId: Int) = 0
    }

    /** 매핑: 'IPQC파트(M)' → 품질보증팀, '휴직자' 제외 */
    private inner class MemMaps : GwDeptMapRepository(mock(NamedParameterJdbcTemplate::class.java)) {
        val maps = linkedMapOf<String, Triple<Int?, String, String?>>(
            "IPQC파트(M)" to Triple(2, "Y", null), "휴직자_로그인불가" to Triple(null, "N", null)
        )
        /** 계정 사번 → 그룹웨어 부서명 */
        val gwDept = mutableMapOf("A1" to "IPQC파트(M)", "A2" to "IPQC파트(M)", "A3" to "멕시코법인(A)")
        var source = true
        var lastRun: Map<String, Any?>? = null
        var lastDone: Map<String, Any?>? = null

        override fun sourceExists() = source
        override fun countSourceRows() = 373L
        override fun findGroupwareRun(onlyDone: Boolean) = if (onlyDone) lastDone else lastRun
        override fun findMaps(unassignedDeptId: Int?, withSource: Boolean) = emptyList<Map<String, Any?>>()
        override fun countUsersInDept(deptId: Int) = userDept.count { it.value == deptId }.toLong()
        override fun findDeptByName(deptNm: String) =
            depts.values.firstOrNull { it["deptNm"] == deptNm }?.let { mapOf("deptId" to it["deptId"], "deptNm" to it["deptNm"]) }
        override fun findMap(gwDeptNm: String) = maps[gwDeptNm]?.let { (id, yn, remark) ->
            mapOf("gwDeptNm" to gwDeptNm, "deptId" to id, "deptNm" to id?.let { depts[it]?.get("deptNm") },
                  "joinYn" to yn, "state" to stateOf(yn, id), "remark" to remark)
        }
        override fun upsertMap(gwDeptNm: String, deptId: Int?, joinYn: String, remark: String?, actor: String): Int {
            maps[gwDeptNm] = Triple(deptId, joinYn, remark); return 1
        }
        override fun deleteMap(gwDeptNm: String) = if (maps.remove(gwDeptNm) != null) 1 else 0
        override fun insertMap(gwDeptNm: String, deptId: Int?, joinYn: String, remark: String?, actor: String): Int {
            if (gwDeptNm in maps) throw org.springframework.dao.DuplicateKeyException("pk"); maps[gwDeptNm] = Triple(deptId, joinYn, remark); return 1
        }
        override fun findUnassignedUsers(unassignedDeptId: Int, withSource: Boolean, keyword: String?,
                                         empNos: Collection<String>?, limit: Int?, offset: Int, gwDeptNms: Collection<String>?,
                                         states: Collection<String>?) =
            userDept.filter { it.value == unassignedDeptId && (empNos == null || it.key in empNos) &&
                              (gwDeptNms == null || gwDept[it.key] in gwDeptNms) }.keys.map { empNo ->
                val suggest = maps[gwDept[empNo]]?.takeIf { it.second == "Y" }?.first
                mapOf<String, Any?>("empNo" to empNo, "gwDeptNm" to gwDept[empNo], "state" to (userState[empNo] ?: "ACTIVE"),
                                    "suggestDeptId" to suggest, "suggestDeptNm" to suggest?.let { depts[it]?.get("deptNm") })
            }
    }

    private class MemAudit : AuditLogService(mock(AuditLogRepository::class.java), mock(AuthorizationService::class.java)) {
        val perm = mutableListOf<Pair<String, String>>()
        val audit = mutableListOf<String?>()
        val auditDesc = mutableListOf<String?>()
        override fun recordPermChange(actCd: String, targetKindCd: String, targetNm: String, detail: String, targetDeptId: Int?, targetUserId: String?, auditId: Long?) {
            perm += actCd to detail
        }
        override fun record(logType: String, menuId: String?, fieldKey: String?, targetDesc: String?, resultCd: String, maskedCnt: Int, remark: String?): Long? {
            audit += menuId
            auditDesc += targetDesc; return null
        }
    }

    private val users = MemUsers()
    private val maps = MemMaps()
    private val audit = MemAudit()
    private val authz = AuthorizationService(mock(AuthRepository::class.java))
    private val props = AppProperties()
    private val systemUserService = SystemUserService(users, authz, audit, PasswordEncoderService(com.dwje.api.config.PasswordProperties()), props)
    private val service = GwDeptMapService(maps, users, systemUserService, authz, audit, props)

    private fun login(vararg menus: String, superAdmin: Boolean = false) = UserContext.set(
        UserPrincipal(userId = "IT1", userName = "전산", deptId = 5, deptName = "전산팀", positionCd = null,
                      plantCd = null, superAdmin = superAdmin, menuPerms = menus.toSet())
    )

    /** 화면에는 접근하지만 쓰기 동작을 못 하는 계정 — V70 부터는 미배정 계정뿐이다 */
    private fun loginReadOnly(vararg menus: String) = UserContext.set(
        UserPrincipal(userId = "IT2", userName = "조회", deptId = 59, deptName = "미배정", positionCd = null,
                      plantCd = null, superAdmin = false, menuPerms = menus.toSet(), unassigned = true)
    )

    private fun req(empNos: List<String>? = null, gwDeptNms: List<String>? = null, all: Boolean? = null, includeSuspended: Boolean? = null) =
        GwDeptReassignRequest(empNos, gwDeptNms, all, includeSuspended)

    @Suppress("UNCHECKED_CAST")
    private fun reasons(r: Map<String, Any?>) =
        (r["skipped"] as List<Map<String, Any?>>).associate { it["empNo"] as String to it["reason"] as String }

    @AfterEach
    fun clear() = UserContext.clear()

    @Test
    @DisplayName("화면 권한이 없으면 403")
    fun needsMenu() {
        login("sys-account")
        assertThrows(MenuAccessDeniedException::class.java) { service.reassign(req(all = true)) }
    }

    @Test
    @DisplayName("저장 — joinYn=N 이면 부서를 비우고, 빠진 부서는 미배정으로 덮어쓴다")
    fun saveOverwrites() {
        login("sys-gw-dept")
        assertEquals("EXCLUDED", service.saveMap(GwDeptMapSaveRequest("IPQC파트(M)", deptId = 2, joinYn = "N"))["state"])
        assertNull(maps.maps["IPQC파트(M)"]!!.first, "가입 제외면 부서를 저장하지 않는다")

        assertEquals("UNMAPPED", service.saveMap(GwDeptMapSaveRequest("IPQC파트(M)"))["state"])
        assertEquals(Triple(null, "Y", null), maps.maps["IPQC파트(M)"])

        assertEquals("MAPPED", service.saveMap(GwDeptMapSaveRequest(" 공백 부서 ", deptId = 5, remark = "확인"))["state"])
        assertTrue(maps.maps.containsKey(" 공백 부서 "), "부서명은 다듬지 않는다")
        assertEquals("GW_DEPT_MAP", audit.perm.last().first)
        assertTrue(audit.perm.last().second.contains("미배정") || audit.perm.last().second.contains("없음"))
        assertEquals("sys-gw-dept", audit.audit.last())
    }

    @Test
    @DisplayName("저장 — 없는 부서·미배정·통합관리자 부서는 400(field=deptId)")
    fun saveRejects() {
        login("sys-gw-dept")
        listOf(999, 59, 1).forEach { id ->
            val e = assertThrows(InvalidParameterException::class.java) { service.saveMap(GwDeptMapSaveRequest("X", deptId = id)) }
            assertEquals("deptId", e.field, "deptId=$id")
        }
        assertTrue("X" !in maps.maps, "거부되면 저장하지 않는다")
    }

    @Test
    @DisplayName("삭제 — 없는 행은 404, 있으면 지우고 이력")
    fun delete() {
        login("sys-gw-dept")
        assertThrows(ResourceNotFoundException::class.java) { service.deleteMap("없는부서") }
        assertEquals("UNMAPPED", service.deleteMap("IPQC파트(M)")["state"])
        assertTrue("IPQC파트(M)" !in maps.maps)
        assertEquals("GW_DEPT_MAP", audit.perm.last().first)
    }

    @Test
    @DisplayName("T-G01 재배정 — 대상 미지정(null·{}·empNos:[])은 400(field=empNos), 아무도 옮기지 않는다")
    fun reassignNeedsTarget() {
        login("sys-gw-dept")
        listOf(null, req(), req(empNos = emptyList()), req(empNos = listOf(" ")), req(all = false)).forEach { r ->
            val e = assertThrows(InvalidParameterException::class.java) { service.reassign(r) }
            assertEquals("empNos", e.field)
            assertEquals("옮길 계정을 지정하거나 전체 재배정을 명시하십시오.", e.message)
        }
        assertEquals(listOf(59, 59, 59), listOf(userDept["A1"], userDept["A2"], userDept["A3"]))
    }

    @Test
    @DisplayName("T-G01·T-G02 재배정 전체(all=true) — 제안 부서가 있는 미배정 계정 전부, 제안 없음도 skipped 에 센다")
    fun reassignAll() {
        login("sys-gw-dept")
        val r = service.reassign(req(all = true))
        assertEquals(2, r["movedCnt"])
        assertEquals(1, r["skippedCnt"], "A3 는 제안 부서가 없다")
        assertEquals(mapOf("A3" to "NO_SUGGESTION"), reasons(r))
        assertEquals(listOf(mapOf("deptId" to 2, "deptNm" to "품질보증팀", "cnt" to 2)), r["byDept"])
        @Suppress("UNCHECKED_CAST")
        assertEquals("IPQC파트(M)", (r["items"] as List<Map<String, Any?>>).first()["gwDeptNm"])
        assertEquals(2, userDept["A1"]); assertEquals(2, userDept["A2"])
        assertEquals(59, userDept["A3"], "제안 부서가 없으면 그대로")
        assertTrue(audit.perm.all { it.first == "ACCOUNT" } && audit.perm.first().second.contains("메뉴 1건"), "계정 부서 이동과 같은 이력")
        assertEquals("미배정 계정 재배정 [전체]", audit.auditDesc.last(), "요청 단위 감사 1줄")

        assertEquals(0, service.reassign(req(all = true))["movedCnt"], "옮길 계정이 없어도 정상")
    }

    @Test
    @DisplayName("T-G02 재배정 사번 지정 — 사유: 제안 없음·미배정 아님·없는 계정, 중복 사번은 한 번")
    fun reassignSelected() {
        login("sys-gw-dept")
        val r = service.reassign(req(empNos = listOf("A1", "A3", "Q1", "A1", "NOPE")))
        assertEquals(1, r["movedCnt"])
        assertEquals(3, r["skippedCnt"])
        assertEquals(mapOf("A3" to "NO_SUGGESTION", "Q1" to "NOT_UNASSIGNED", "NOPE" to "NOT_FOUND"), reasons(r))
        assertEquals(2, userDept["Q1"])
    }

    @Test
    @DisplayName("T-G01 그룹웨어 부서 지정 — 그 부서 소속만, 사번과 함께 주면 교집합")
    fun reassignByGwDept() {
        login("sys-gw-dept")
        maps.gwDept["A3"] = "IPQC파트(M)"
        maps.gwDept["A2"] = "다른부서"
        val r = service.reassign(req(gwDeptNms = listOf("IPQC파트(M)")))
        assertEquals(2, r["movedCnt"]); assertEquals(59, userDept["A2"], "다른 그룹웨어 부서는 그대로")
        userDept["A1"] = 59; userDept["A3"] = 59
        val both = service.reassign(req(empNos = listOf("A1", "A2"), gwDeptNms = listOf("IPQC파트(M)")))
        assertEquals(1, both["movedCnt"]); assertEquals(59, userDept["A3"]); assertEquals(59, userDept["A2"])
    }

    @Test
    @DisplayName("T-G02·T-G08 상태 — 정지는 기본 건너뜀(SUSPENDED), includeSuspended 면 이동, 잠김은 기본 이동, 통합관리자 제안은 건너뜀")
    fun reassignStateAndSuperAdmin() {
        login("sys-gw-dept")
        userState["A1"] = "SUSPENDED"; userState["A2"] = "LOCKED"
        userDept["S1"] = 59; maps.gwDept["S1"] = "임원실"; maps.maps["임원실"] = Triple(1, "Y", null)
        val r = service.reassign(req(all = true))
        assertEquals(mapOf("A1" to "SUSPENDED", "A3" to "NO_SUGGESTION", "S1" to "SUPER_ADMIN_SUGGESTED"), reasons(r))
        assertEquals(2, userDept["A2"], "잠긴 계정도 옮긴다")
        assertEquals(59, userDept["S1"], "통합관리자 부서로는 옮기지 않는다")
        val r2 = service.reassign(req(empNos = listOf("A1"), includeSuspended = true))
        assertEquals(1, r2["movedCnt"]); assertEquals(2, userDept["A1"])
    }

    @Test
    @DisplayName("T-G07 조회만 있으면 재배정·부서 지정은 E-AUTH-004")
    fun readOnlyCannotWrite() {
        loginReadOnly("sys-gw-dept")
        assertThrows(WriteAccessDeniedException::class.java) { service.reassign(req(all = true)) }
        assertThrows(WriteAccessDeniedException::class.java) { systemUserService.changeUserDept("A1", 5) }
        assertEquals(59, userDept["A1"])
    }

    @Test
    @DisplayName("T-G03 계정 부서 이동 — 그룹웨어 부서 매핑 권한만 있으면 미배정 계정만, 통합관리자 부서는 경로와 무관하게 403 E-AUTH-002")
    fun changeDeptViaGwDept() {
        login("sys-gw-dept")
        assertThrows(MenuAccessDeniedException::class.java) { systemUserService.changeUserDept("Q1", 5) }
        val e = assertThrows(BusinessException::class.java) { systemUserService.changeUserDept("A1", 1) }
        assertEquals(ErrorCode.AUTH_MENU_DENIED, e.errorCode)
        assertEquals(SystemDeptGuard.MSG_ASSIGN_SUPER_ADMIN, e.message)
        systemUserService.changeUserDept("A1", 5)
        assertEquals(5, userDept["A1"])
        assertEquals("sys-gw-dept", audit.audit.last())

        login("sys-account")
        assertThrows(BusinessException::class.java) { systemUserService.changeUserDept("A2", 1) }
        assertEquals(59, userDept["A2"])
        systemUserService.changeUserDept("Q1", 5)
        assertEquals(5, userDept["Q1"], "계정 관리 권한은 예전처럼 미배정이 아닌 계정도 옮긴다")
        assertEquals("sys-account", audit.audit.last())

        login("sys-account", superAdmin = true)
        systemUserService.changeUserDept("A2", 1)
        assertEquals(1, userDept["A2"], "통합관리자는 통합관리자 부서로 옮길 수 있다")
    }

    @Test
    @DisplayName("T-G05 요약 health — 미배정 부서 없음·원천 표 없음을 알리고, 최근 실행과 직전 성공 실행을 나눈다")
    fun summaryHealth() {
        login("sys-gw-dept")
        maps.lastRun = mapOf("startedAt" to "2026-10-01 09:00", "stateCd" to "FAIL", "message" to "원본 실패")
        maps.lastDone = mapOf("startedAt" to "2026-09-30 14:38", "stateCd" to "DONE", "message" to "원본 · AX 가입 1(미배정 1) / 이미 가입 0 / 제외 부서 7")
        val s1 = service.getSummary()
        @Suppress("UNCHECKED_CAST") val h1 = s1["health"] as Map<String, Any?>
        assertEquals(true, h1["unassignedDeptFound"]); assertEquals(true, h1["sourceExists"]); assertEquals(373L, h1["sourceRowCnt"])
        assertEquals("미배정", h1["engineDeptName"])
        @Suppress("UNCHECKED_CAST") val sync = s1["lastSync"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST") val join = s1["lastJoin"] as Map<String, Any?>
        assertEquals("FAIL", sync["stateCd"]); assertEquals("2026-09-30 14:38", join["startedAt"])
        assertEquals("AX 가입 1(미배정 1) / 이미 가입 0 / 제외 부서 7", join["summary"])
        assertEquals(true, s1["canWrite"])

        maps.source = false
        val other = GwDeptMapService(maps, users, systemUserService, authz, audit, AppProperties(unassignedDeptName = "없는이름"))
        val s2 = other.getSummary()
        @Suppress("UNCHECKED_CAST") val h2 = s2["health"] as Map<String, Any?>
        assertEquals(false, h2["unassignedDeptFound"]); assertEquals(false, h2["sourceExists"]); assertEquals(0L, h2["sourceRowCnt"])
        assertEquals(0L, s2["unassignedUserCnt"])

        loginReadOnly("sys-gw-dept")
        assertEquals(false, service.getSummary()["canWrite"])
    }

    @Test
    @DisplayName("요약의 lastJoinMessage — 엔진 message 에서 AX 가입 요약만")
    fun joinMessage() {
        assertEquals("AX 가입 366(미배정 349) / 이미 가입 0 / 제외 부서 7",
            GwDeptMapService.joinMessageOf("원본 465행(…) → 신규 0 / 동일 373 · AX 가입 366(미배정 349) / 이미 가입 0 / 제외 부서 7"))
        assertEquals("AX 가입 건너뜀 — ax.tb_sys_dept_gw_map 이 없습니다", GwDeptMapService.joinMessageOf("원본 … · AX 가입 건너뜀 — ax.tb_sys_dept_gw_map 이 없습니다"))
        assertNull(GwDeptMapService.joinMessageOf("원본 465행 → 신규 0"))
        assertNull(GwDeptMapService.joinMessageOf(null))
    }

    @Test
    @DisplayName("상태 판정 — N 우선, 부서 있으면 MAPPED, 나머지 UNMAPPED")
    fun state() {
        assertEquals("EXCLUDED", GwDeptMapRepository.stateOf("N", 2))
        assertEquals("MAPPED", GwDeptMapRepository.stateOf("Y", 2))
        assertEquals("UNMAPPED", GwDeptMapRepository.stateOf("Y", null))
        assertEquals("UNMAPPED", GwDeptMapRepository.stateOf(null, null))
    }
}
