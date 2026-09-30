package com.dwje.api

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.exception.MenuAccessDeniedException
import com.dwje.api.common.exception.ResourceNotFoundException
import com.dwje.api.common.security.PasswordEncoderService
import com.dwje.api.common.security.UserContext
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.config.AppProperties
import com.dwje.api.model.request.GwDeptMapSaveRequest
import com.dwje.api.repository.AuditLogRepository
import com.dwje.api.repository.AuthRepository
import com.dwje.api.repository.GwDeptMapRepository
import com.dwje.api.repository.SystemUserRepository
import com.dwje.api.service.AuditLogService
import com.dwje.api.service.AuthorizationService
import com.dwje.api.service.GwDeptMapService
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
        mapOf("deptId" to id, "deptNm" to nm, "abbr" to nm.take(2), "desc" to null, "plantCd" to "PL01", "superAdmin" to superAdmin)

    /** 계정 사번 → 부서 */
    private val userDept = linkedMapOf("A1" to 59, "A2" to 59, "A3" to 59, "Q1" to 2)

    private inner class MemUsers : SystemUserRepository(mock(NamedParameterJdbcTemplate::class.java)) {
        override fun findDept(deptId: Int) = depts[deptId]
        override fun findDeptIdByName(deptNm: String) = depts.values.firstOrNull { it["deptNm"] == deptNm }?.get("deptId") as Int?
        override fun findUserDeptId(empNo: String) = userDept[empNo]
        override fun existsUser(empNo: String) = empNo in userDept
        override fun updateUserDept(empNo: String, deptId: Int, actor: String): Int { userDept[empNo] = deptId; return 1 }
        override fun findMenuPermMatrix() = listOf(mapOf<String, Any?>("deptId" to 2, "menuId" to "qc-aoi"))
        override fun findDataPermMatrix() = emptyList<Map<String, Any?>>()
    }

    /** 매핑: 'IPQC파트(M)' → 품질보증팀, '휴직자' 제외 */
    private inner class MemMaps : GwDeptMapRepository(mock(NamedParameterJdbcTemplate::class.java)) {
        val maps = linkedMapOf<String, Triple<Int?, String, String?>>(
            "IPQC파트(M)" to Triple(2, "Y", null), "휴직자_로그인불가" to Triple(null, "N", null)
        )
        /** 계정 사번 → 그룹웨어 부서명 */
        val gwDept = mapOf("A1" to "IPQC파트(M)", "A2" to "IPQC파트(M)", "A3" to "멕시코법인(A)")

        override fun sourceExists() = true
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
        override fun findUnassignedUsers(unassignedDeptId: Int, withSource: Boolean, keyword: String?,
                                         empNos: Collection<String>?, limit: Int?, offset: Int) =
            userDept.filter { it.value == unassignedDeptId && (empNos == null || it.key in empNos) }.keys.map { empNo ->
                val suggest = maps[gwDept[empNo]]?.takeIf { it.second == "Y" }?.first
                mapOf<String, Any?>("empNo" to empNo, "gwDeptNm" to gwDept[empNo],
                                    "suggestDeptId" to suggest, "suggestDeptNm" to suggest?.let { depts[it]?.get("deptNm") })
            }
    }

    private class MemAudit : AuditLogService(mock(AuditLogRepository::class.java), mock(AuthorizationService::class.java)) {
        val perm = mutableListOf<Pair<String, String>>()
        val audit = mutableListOf<String?>()
        override fun recordPermChange(actCd: String, targetKindCd: String, targetNm: String, detail: String, targetDeptId: Int?, targetUserId: String?) {
            perm += actCd to detail
        }
        override fun record(logType: String, menuId: String?, fieldKey: String?, targetDesc: String?, resultCd: String, maskedCnt: Int, remark: String?) {
            audit += menuId
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
        UserPrincipal(userId = "IT1", userName = "전산", deptId = 5, deptName = "전산팀", deptAbbr = null, positionCd = null,
                      plantCd = null, superAdmin = superAdmin, menuPerms = menus.toSet())
    )

    @AfterEach
    fun clear() = UserContext.clear()

    @Test
    @DisplayName("화면 권한이 없으면 403")
    fun needsMenu() {
        login("sys-account")
        assertThrows(MenuAccessDeniedException::class.java) { service.reassign(null) }
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
    @DisplayName("재배정 — empNos 없으면 제안 부서가 있는 미배정 계정 전체")
    fun reassignAll() {
        login("sys-gw-dept")
        val r = service.reassign(null)
        assertEquals(2, r["movedCnt"])
        assertEquals(0, r["skippedCnt"])
        assertEquals(2, userDept["A1"]); assertEquals(2, userDept["A2"])
        assertEquals(59, userDept["A3"], "제안 부서가 없으면 그대로")
        assertTrue(audit.perm.all { it.first == "ACCOUNT" } && audit.perm.first().second.contains("메뉴 1건"), "계정 부서 이동과 같은 이력")

        assertEquals(0, service.reassign(null)["movedCnt"], "옮길 계정이 없어도 정상")
    }

    @Test
    @DisplayName("재배정 — 미배정이 아니거나 제안이 없는 사번은 건너뛴다")
    fun reassignSelected() {
        login("sys-gw-dept")
        val r = service.reassign(listOf("A1", "A3", "Q1", "A1", "NOPE"))
        assertEquals(1, r["movedCnt"])
        assertEquals(3, r["skippedCnt"], "A3(제안 없음) · Q1(미배정 아님) · NOPE(없는 계정)")
        assertEquals(2, userDept["Q1"])
    }

    @Test
    @DisplayName("계정 부서 이동 — 그룹웨어 부서 매핑 권한만 있으면 미배정 계정만, 통합관리자 부서로는 불가")
    fun changeDeptViaGwDept() {
        login("sys-gw-dept")
        assertThrows(MenuAccessDeniedException::class.java) { systemUserService.changeUserDept("Q1", 5) }
        assertThrows(InvalidParameterException::class.java) { systemUserService.changeUserDept("A1", 1) }
        systemUserService.changeUserDept("A1", 5)
        assertEquals(5, userDept["A1"])
        assertEquals("sys-gw-dept", audit.audit.last())

        login("sys-account")
        systemUserService.changeUserDept("Q1", 5)
        assertEquals(5, userDept["Q1"], "계정 관리 권한은 예전처럼 제한 없음")
        assertEquals("sys-account", audit.audit.last())
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
