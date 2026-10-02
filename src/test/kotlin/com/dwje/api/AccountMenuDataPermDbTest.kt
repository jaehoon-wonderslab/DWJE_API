package com.dwje.api

import com.dwje.api.common.exception.BusinessException
import com.dwje.api.common.exception.BusinessRuleException
import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.security.UserContext
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.common.util.MenuId
import com.dwje.api.model.request.DataFieldAttrRequest
import com.dwje.api.model.request.DataFieldMappingRequest
import com.dwje.api.model.request.DataPermRequest
import com.dwje.api.model.request.MenuPermCopyRequest
import com.dwje.api.model.request.MenuPermGroupRequest
import com.dwje.api.model.request.MenuPermRequest
import com.dwje.api.model.request.UserSaveRequest
import com.dwje.api.service.AuditLogService
import com.dwje.api.service.DataFieldService
import com.dwje.api.service.SystemUserService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
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

/**
 * 01 ACC-01·02·04·05 · 03 MNP-01·02·04·16·17 · 04 DTP-01·02·03·16 — 실제 로컬 DB, 테스트마다 롤백.
 * 감사 기록은 REQUIRES_NEW 라 롤백되지 않고 지울 수도 없으므로(V51) 목으로 바꾼다.
 */
@SpringBootTest(properties = ["app.ai.prewarm-enabled=false"])
@ActiveProfiles("local")
@Transactional
class AccountMenuDataPermDbTest {

    @Autowired lateinit var service: SystemUserService
    @Autowired lateinit var dataFieldService: DataFieldService
    @Autowired lateinit var jdbc: NamedParameterJdbcTemplate
    @MockitoBean lateinit var audit: AuditLogService

    private val admin = UserPrincipal("10000", "관리자", 1, "통합관리자", null, null, null, true)

    /** 전산팀 관리자(비통합관리자) — 시스템관리 화면 쓰기 */
    private val itAdmin = UserPrincipal(
        "10004", "최전산", 5, "전산팀", null, null, null, false,
        menuPerms = setOf(MenuId.SYS_ACCOUNT, MenuId.SYS_MENU, MenuId.SYS_DATA),
        writePerms = setOf(MenuId.SYS_ACCOUNT, MenuId.SYS_MENU, MenuId.SYS_DATA)
    )

    @BeforeEach
    fun login() = UserContext.set(admin)

    @AfterEach
    fun clear() = UserContext.clear()

    private fun exec(sql: String) = jdbc.update(sql, MapSqlParameterSource())
    private fun str(sql: String) = jdbc.queryForObject(sql, MapSqlParameterSource(), String::class.java)
    private fun long(sql: String) = jdbc.queryForObject(sql, MapSqlParameterSource(), Long::class.java)!!

    private fun user(id: String, nm: String, dept: Int, state: String) =
        exec("INSERT INTO ax.tb_sys_user (user_id, user_nm, dept_id, position_cd, user_state_cd) VALUES ('$id', '$nm', $dept, 'STAFF', '$state')")

    // ---------------------------------------------------------------- 01 계정

    @Test
    @DisplayName("ACC-01 승인 — 사번 정확 일치(부분 일치 계정이 앞에 정렬돼도), 다시 처리하면 409")
    fun approveExact() {
        user("ZT1001", "가나다", 2, "PENDING")
        user("ZT100", "하하하", 2, "PENDING")
        service.approveSignup("ZT100", true, null)
        assertEquals("ACTIVE", str("SELECT user_state_cd FROM ax.tb_sys_user WHERE user_id = 'ZT100'"))
        assertEquals("PENDING", str("SELECT user_state_cd FROM ax.tb_sys_user WHERE user_id = 'ZT1001'"))
        val e = assertThrows(BusinessRuleException::class.java) { service.approveSignup("ZT100", true, null) }
        assertEquals("이미 다른 관리자가 처리했습니다. [현재 상태=ACTIVE]", e.message)
    }

    @Test
    @DisplayName("ACC-01 반려 — 사유가 비고에 [날짜 반려] 로, 목록 stateReason=REJECTED 는 이력 기준이라 여기선 ADMIN 이하로 계산")
    fun rejectRemark() {
        user("ZT200", "반려", 2, "PENDING")
        service.approveSignup("ZT200", false, "부서 확인 불가")
        assertTrue(str("SELECT remark FROM ax.tb_sys_user WHERE user_id = 'ZT200'")!!.endsWith("반려] 부서 확인 불가"))
    }

    @Test
    @DisplayName("ACC-02 본인 — 부서 변경 409(통합관리자 부서여도 409 가 먼저), 이름만 바꾸면 통과")
    fun selfChange() {
        UserContext.set(itAdmin)
        val e = assertThrows(BusinessRuleException::class.java) { service.changeUserDept("10004", 1) }
        assertEquals("본인 계정의 부서와 추가 메뉴는 다른 관리자가 바꿔야 합니다.", e.message)
        assertThrows(BusinessRuleException::class.java) { service.updateUser("10004", UserSaveRequest(deptId = 4)) }
        service.updateUser("10004", UserSaveRequest(name = "최전산", deptId = 5))
        assertEquals(5L, long("SELECT dept_id FROM ax.tb_sys_user WHERE user_id = '10004'"))
    }

    @Test
    @DisplayName("ACC-04 부서 — 참조가 있으면 409 + data.refs(500 아님), 목록 systemRole·미배정 고정 표시")
    fun deptRefs() {
        exec("INSERT INTO ax.tb_sys_dept (dept_id, dept_nm, dept_abbr) VALUES (9901, 'ZT부서', 'ZT')")
        exec("INSERT INTO ax.tb_sys_dept_gw_map (gw_dept_nm, dept_id) VALUES ('ZT그룹웨어부서', 9901)")
        val e = assertThrows(BusinessException::class.java) { service.deleteDept(9901) }
        assertEquals("다른 설정이 이 부서를 쓰고 있어 삭제할 수 없습니다. 그룹웨어 매핑 1건", e.message)
        @Suppress("UNCHECKED_CAST")
        assertEquals(1L, ((e.data as Map<String, Any?>)["refs"] as Map<String, Long>)["gwDeptMaps"])

        @Suppress("UNCHECKED_CAST")
        val rows = service.getDepts()["items"] as List<Map<String, Any?>>
        val unassigned = rows.single { it["deptNm"] == "미배정" }
        assertEquals("UNASSIGNED", unassigned["systemRole"])
        assertEquals(MenuId.UNASSIGNED_SCREENS.toList(), unassigned["fixedMenus"])
        assertEquals("SUPER_ADMIN", rows.single { it["deptId"] == 1 }["systemRole"])
        assertNull(rows.single { it["deptId"] == 5 }["systemRole"])
    }

    @Test
    @DisplayName("ACC-05 정지 사유 — 비고에 남고 목록 stateReason=ADMIN, 승인 대기 계정 상태 토글은 409")
    fun suspendReason() {
        user("ZT300", "정지", 2, "ACTIVE")
        val res = service.changeUserState("ZT300", "SUSPENDED", false, "장기 휴직")
        assertEquals(false, res["unlocked"])
        assertTrue(str("SELECT remark FROM ax.tb_sys_user WHERE user_id = 'ZT300'")!!.endsWith("정지] 장기 휴직"))
        val (rows, _) = service.getUsers("ZT300", null, null, null, 1, 10)
        assertEquals("ADMIN", rows.single()["stateReason"])

        user("ZT301", "대기", 2, "PENDING")
        assertEquals("승인 대기 계정은 가입 승인·반려로 처리하십시오.",
            assertThrows(BusinessRuleException::class.java) { service.changeUserState("ZT301", "ACTIVE") }.message)
    }

    // ---------------------------------------------------------------- 03 메뉴

    @Test
    @DisplayName("MNP-02·16 단건 — 없는 화면 400(field=screenId), WRITE 켜기·끄기, READ 끄면 행 삭제")
    fun singlePerm() {
        assertEquals("screenId", assertThrows(InvalidParameterException::class.java) {
            service.changeMenuPerm(MenuPermRequest(4, "no-such", true))
        }.field)
        assertEquals("perm", assertThrows(InvalidParameterException::class.java) {
            service.changeMenuPerm(MenuPermRequest(4, "rpt-scrap", true, "ALL"))
        }.field)
        val on = service.changeMenuPerm(MenuPermRequest(4, "rpt-scrap", true, "WRITE"))
        assertEquals(true, on["read"]); assertEquals(true, on["write"])
        val off = service.changeMenuPerm(MenuPermRequest(4, "rpt-scrap", false, "WRITE"))
        assertEquals(true, off["read"]); assertEquals(false, off["write"])
        service.changeMenuPerm(MenuPermRequest(4, "rpt-scrap", false, "READ"))
        assertEquals(0L, long("SELECT count(*) FROM ax.tb_sys_dept_menu_perm WHERE dept_id = 4 AND menu_id = 'rpt-scrap'"))
    }

    @Test
    @DisplayName("MNP-04 그룹 — 옛 groupNm 도 그룹 ID 로 해석, 동작 화면 제외(기본), 비통합관리자 system 그룹 403")
    fun groupPerm() {
        exec("DELETE FROM ax.tb_sys_dept_menu_perm WHERE dept_id = 4 AND menu_id IN (SELECT menu_id FROM ax.tb_sys_menu WHERE group_id = 'dashboard')")
        val res = service.changeMenuPermByGroup(MenuPermGroupRequest(deptId = 4, groupNm = "대시보드"))
        @Suppress("UNCHECKED_CAST")
        val added = res["added"] as List<String>
        assertTrue("dash-ai" in added)
        assertTrue("dash-ai-upload" !in added)
        UserContext.set(itAdmin)
        assertThrows(BusinessException::class.java) { service.changeMenuPermByGroup(MenuPermGroupRequest(deptId = 4, groupId = "system")) }
    }

    @Test
    @DisplayName("MNP-01 복사 — dryRun 은 쓰지 않고 해시를 준다, 해시 불일치 409 문구, 원본 통합관리자는 사용 중 전 화면")
    fun copy() {
        val before = long("SELECT count(*) FROM ax.tb_sys_dept_menu_perm WHERE dept_id = 4")
        val preview = service.copyMenuPerms(MenuPermCopyRequest(3, 4, dryRun = true))
        assertEquals(before, long("SELECT count(*) FROM ax.tb_sys_dept_menu_perm WHERE dept_id = 4"))
        val hash = preview["expectedHash"] as String
        assertEquals("미리보기 이후 권한이 바뀌었습니다. 미리보기를 다시 실행하세요.",
            assertThrows(BusinessRuleException::class.java) { service.copyMenuPerms(MenuPermCopyRequest(3, 4, expectedHash = "x$hash")) }.message)
        assertThrows(BusinessRuleException::class.java) { service.copyMenuPerms(MenuPermCopyRequest(3, 59, dryRun = true)) }
        assertThrows(BusinessRuleException::class.java) { service.copyMenuPerms(MenuPermCopyRequest(3, 1, dryRun = true)) }

        service.copyMenuPerms(MenuPermCopyRequest(1, 4, expectedHash = service.copyMenuPerms(MenuPermCopyRequest(1, 4, dryRun = true))["expectedHash"] as String))
        val active = long("SELECT count(*) FROM ax.tb_sys_menu m JOIN ax.tb_sys_menu_group g ON g.group_id = m.group_id AND g.use_flg = 'Y' WHERE m.use_flg = 'Y'")
        assertEquals(active, long("SELECT count(*) FROM ax.tb_sys_dept_menu_perm p JOIN ax.tb_sys_menu m ON m.menu_id = p.menu_id AND m.use_flg = 'Y' WHERE p.dept_id = 4 AND p.can_read AND p.can_write"))
    }

    @Test
    @DisplayName("MNP-17 매트릭스 — screens kind/admin/common, depts locked, 미배정 = 고정 5개·쓰기 없음, version")
    fun matrix() {
        val m = service.getMenuPermMatrix()
        @Suppress("UNCHECKED_CAST") val screens = m["screens"] as List<Map<String, Any?>>
        assertEquals("ACTION", screens.single { it["id"] == "dash-ai-upload" }["kind"])
        assertEquals(true, screens.single { it["id"] == "sys-menu" }["admin"])
        assertEquals(true, screens.single { it["id"] == "chat-history" }["common"])
        @Suppress("UNCHECKED_CAST") val writeMatrix = m["writeMatrix"] as Map<String, List<String>>
        assertEquals(emptyList<String>(), writeMatrix["59"])
        @Suppress("UNCHECKED_CAST") val matrix = m["matrix"] as Map<String, List<String>>
        assertEquals(MenuId.UNASSIGNED_SCREENS, matrix["59"]!!.toSet())
        assertEquals(64, (m["version"] as String).length)
        assertTrue("grantCounts" in m && m["canEditAdminScreens"] == true)
    }

    // ---------------------------------------------------------------- 04 데이터

    @Test
    @DisplayName("DTP-01·03 — 예약어 필드명 409, 미배정 데이터 권한 409(데이터 전용 문구), 목록 builtIn·reservedAttrs")
    fun reservedAndFixed() {
        assertEquals("시스템이 쓰는 필드명이라 가릴 수 없습니다. [name]",
            assertThrows(BusinessRuleException::class.java) { dataFieldService.addAttr("qty", DataFieldAttrRequest("name")) }.message)
        assertEquals("미배정 부서의 데이터 권한은 고정되어 조정할 수 없습니다.",
            assertThrows(BusinessRuleException::class.java) { service.changeDataPerm(DataPermRequest(59, "qty", true)) }.message)
        val fields = service.getDataFields()
        @Suppress("UNCHECKED_CAST")
        assertEquals(true, (fields["items"] as List<Map<String, Any?>>).single { it["key"] == "qty" }["builtIn"])
        assertTrue("empNo" in (fields["reservedAttrs"] as List<*>))
        @Suppress("UNCHECKED_CAST")
        val depts = service.getDataPermMatrix()["depts"] as List<Map<String, Any?>>
        assertEquals("UNASSIGNED", depts.single { it["deptId"] == 59 }["locked"])
    }

    @Test
    @DisplayName("DTP-02 매핑 — 새 종류+이동+해제 한 번에, 미배정·통합관리자 부서 허용 행 없음 / 예약어가 섞이면 아무것도 안 바뀜")
    fun mapping() {
        exec("INSERT INTO ax.tb_sys_data_field_attr (field_key, attr_name) VALUES ('qty', 'ztOldAttr')")
        val res = dataFieldService.saveMapping(
            DataFieldMappingRequest(
                newFields = listOf(DataFieldMappingRequest.NewField("zt-eqpt", "ZT 설비 코드", category = null, apply = true)),
                moves = listOf(
                    DataFieldMappingRequest.Move("ztEqptCd", "zt-eqpt", "AI 통합 대시보드 · 설비 코드"),
                    DataFieldMappingRequest.Move("ztOldAttr", null)
                ),
                screenId = "dash-ai"
            ),
            "미배정"
        )
        assertEquals(listOf("zt-eqpt"), res["created"])
        assertEquals(listOf(mapOf("attrName" to "ztOldAttr", "from" to "qty")), res["released"])
        assertEquals(listOf("zt-eqpt"), res["applied"])
        assertEquals("zt-eqpt", str("SELECT field_key FROM ax.tb_sys_data_field_attr WHERE attr_name = 'ztEqptCd'"))
        assertEquals(0L, long("SELECT count(*) FROM ax.tb_sys_dept_data_perm WHERE field_key = 'zt-eqpt' AND dept_id IN (1, 59)"))
        assertTrue(long("SELECT count(*) FROM ax.tb_sys_dept_data_perm WHERE field_key = 'zt-eqpt'") > 0)

        assertThrows(BusinessRuleException::class.java) {
            dataFieldService.saveMapping(
                DataFieldMappingRequest(
                    newFields = listOf(DataFieldMappingRequest.NewField("zt-two", "ZT 둘")),
                    moves = listOf(DataFieldMappingRequest.Move("ztA", "zt-two"), DataFieldMappingRequest.Move("status", "zt-two"))
                ),
                "미배정"
            )
        }
        assertEquals(0L, long("SELECT count(*) FROM ax.tb_sys_data_field WHERE field_key = 'zt-two'"))
    }

    // ---------------------------------------------------------------- 3단계 P1

    @Test
    @DisplayName("DTP-05 기본 7종 적용 끄기 409(운영 항목은 끌 수 있음), DTP-10 미리보기 applied — 미적용 항목은 가려지지 않는다")
    fun applyAndPreview() {
        assertEquals("기본 항목은 서버 판정 코드가 직접 쓰므로 적용을 끌 수 없습니다. 부서 권한으로 조정하세요.",
            assertThrows(BusinessRuleException::class.java) { dataFieldService.setApply("qty", false) }.message)
        assertEquals("Y", str("SELECT apply_flg FROM ax.tb_sys_data_field WHERE field_key = 'qty'"))
        exec("INSERT INTO ax.tb_sys_data_field (field_key, field_nm, apply_flg, sort_seq) VALUES ('zt-p1', 'ZT 항목', 'N', 99)")
        @Suppress("UNCHECKED_CAST")
        val items = service.previewDataPerm("10001")["items"] as List<Map<String, Any?>>
        val zt = items.single { it["fieldKey"] == "zt-p1" }
        assertEquals(false, zt["applied"]); assertEquals(false, zt["masked"]); assertEquals("원본 노출", zt["rendered"])
        assertEquals(true, items.single { it["fieldKey"] == "price" }["masked"], "품질보증팀은 단가 권한이 없다")
        dataFieldService.setApply("zt-p1", true)
        dataFieldService.setApply("zt-p1", false)
    }

    @Test
    @DisplayName("MNP-04 바뀐 칸이 없는 그룹 일괄은 이력 없음, MNP-06 계정 관리 권한만이면 grants 명단 없음(건수만)")
    fun groupNoopAndGrants() {
        service.changeMenuPermByGroup(MenuPermGroupRequest(deptId = 4, groupId = "dashboard"))
        org.mockito.Mockito.clearInvocations(audit)
        val again = service.changeMenuPermByGroup(MenuPermGroupRequest(deptId = 4, groupId = "dashboard"))
        assertEquals(0, again["changedCnt"])
        assertEquals(0, org.mockito.Mockito.mockingDetails(audit).invocations.size)

        exec("INSERT INTO ax.tb_sys_user_menu_grant (user_id, menu_id, can_write) VALUES ('10004', 'sys-dl', false)")
        @Suppress("UNCHECKED_CAST")
        assertTrue(((service.getMenuPermMatrix()["grants"] as Map<String, List<Map<String, Any?>>>)["sys-dl"]).orEmpty().any { it["empNo"] == "10004" })
        UserContext.set(UserPrincipal("10002", "생산", 3, "생산관리팀", null, null, null, false, menuPerms = setOf(MenuId.SYS_ACCOUNT)))
        val m = service.getMenuPermMatrix()
        assertTrue("grants" !in m)
        @Suppress("UNCHECKED_CAST")
        assertEquals(1, (m["grantCounts"] as Map<String, Int>)["sys-dl"])
    }
}
