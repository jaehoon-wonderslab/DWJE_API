package com.dwje.api

import com.dwje.api.common.exception.ConflictingValueException
import com.dwje.api.common.exception.UnauthenticatedException
import com.dwje.api.common.security.PasswordEncoderService
import com.dwje.api.common.security.UserContext
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.model.request.DataFieldSaveRequest
import com.dwje.api.model.request.LoginRequest
import com.dwje.api.repository.AuditLogRepository
import com.dwje.api.service.AlertConfigService
import com.dwje.api.service.AuditLogService
import com.dwje.api.service.AuthService
import com.dwje.api.service.DashboardUploadService
import com.dwje.api.service.DataFieldService
import com.dwje.api.service.GlossaryService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mockingDetails
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/** 04 DTP-11 · 05 ALC-13 · 07 GLS-12·13·14·06 · 09 AUD-14 · 11 UPD-09·10 — 실제 로컬 DB, 롤백. 감사는 목 */
@SpringBootTest(properties = ["app.ai.prewarm-enabled=false"])
@ActiveProfiles("local")
@Transactional
class MiscP2DbTest {

    @Autowired lateinit var glossary: GlossaryService
    @Autowired lateinit var dataFields: DataFieldService
    @Autowired lateinit var alerts: AlertConfigService
    @Autowired lateinit var uploads: DashboardUploadService
    @Autowired lateinit var auth: AuthService
    @Autowired lateinit var auditRepo: AuditLogRepository
    @Autowired lateinit var encoder: PasswordEncoderService
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
    @DisplayName("GLS-14 삭제된 용어에 붙은 유사어 중복은 따로 안내 / GLS-13 byDomain 유사어 수 / GLS-06 이력 전에는 용어·유사어 최근 수정자 / GLS-12 등록일 열")
    fun glossary() {
        exec("UPDATE ax.tb_gls_term SET use_flg = 'N' WHERE term_id = 8")
        val word = jdbc.queryForObject("SELECT word FROM ax.tb_gls_variant WHERE term_id = 8 LIMIT 1", MapSqlParameterSource(), String::class.java)!!
        val e = assertThrows(ConflictingValueException::class.java) { glossary.createVariant(5, word) }
        assertTrue(e.message!!.startsWith("삭제된 공식 용어 [프레스 변형]"), e.message)
        exec("UPDATE ax.tb_gls_term SET use_flg = 'Y' WHERE term_id = 8")
        assertTrue(assertThrows(ConflictingValueException::class.java) { glossary.createVariant(5, word) }.message!!.startsWith("이미 등록된 유사어입니다."))

        @Suppress("UNCHECKED_CAST")
        val byDomain = glossary.getSummary()["byDomain"] as List<Map<String, Any?>>
        assertTrue(byDomain.all { "variantCnt" in it && "noVariantTermCnt" in it })
        assertEquals(long("SELECT count(*) FROM ax.tb_gls_variant v JOIN ax.tb_gls_term t USING (term_id) WHERE t.use_flg = 'Y'"),
            byDomain.sumOf { it["variantCnt"] as Long })

        if (long("SELECT count(*) FROM ax.tb_gls_change_log") == 0L) {
            exec("UPDATE ax.tb_gls_term SET upd_date = now() + interval '1 minute', upd_user = '10001' WHERE term_id = 5")
            assertEquals(jdbc.queryForObject("SELECT user_nm FROM ax.tb_sys_user WHERE user_id = '10001'", MapSqlParameterSource(), String::class.java),
                glossary.getSummary()["lastChangedBy"])
        }
        assertTrue(glossary.exportTerms(com.dwje.api.model.request.GlossaryExportRequest(menuId = "sys-gloss")).headers.contains("등록일"))
    }

    @Test
    @DisplayName("DTP-11 같은 값 저장은 changed=false·감사 없음, 바뀐 칸만 감사 문구 / ALC-13 목록·상세 alertCnt(테스트 알림 제외)")
    fun dataFieldAndAlertCnt() {
        clearInvocations(audit)
        val same = dataFields.update("qty", DataFieldSaveRequest(name = "생산·출하 수량", desc = jdbc.queryForObject(
            "SELECT field_desc FROM ax.tb_sys_data_field WHERE field_key = 'qty'", MapSqlParameterSource(), String::class.java), category = "QTY"))
        assertEquals(false, same["changed"])
        assertEquals(0, mockingDetails(audit).invocations.size)
        val changed = dataFields.update("qty", DataFieldSaveRequest(name = "ZT 수량", desc = null, category = "COST"))
        assertEquals(true, changed["changed"])
        val remark = mockingDetails(audit).invocations.first { it.method.name == "recordPermChange" }.arguments
        assertTrue(remark.any { it is String && it.contains("이름 생산·출하 수량 → ZT 수량") && it.contains("분류 QTY → COST") && it.contains("설명 변경") }, remark.toList().toString())

        val (rows, _, _) = alerts.getConditions(null, null, null, 1, 0)
        val r6 = rows.single { it["condId"] == 6 }
        assertEquals(long("SELECT count(*) FROM ax.tb_alm_alert WHERE cond_id = 6 AND test_flg = 'N'"), r6["alertCnt"])
        assertEquals(r6["alertCnt"], alerts.getCondition(6)["alertCnt"])
    }

    @Test
    @DisplayName("UPD-09 버전 이력 경고 문장(최대 20)·warningTruncated / UPD-10 같은 해시의 앞 버전 duplicateOf")
    fun uploadVersions() {
        @Suppress("UNCHECKED_CAST")
        val v = uploads.listVersions(1)["items"] as List<Map<String, Any?>>
        v.forEach { row ->
            @Suppress("UNCHECKED_CAST")
            val w = row["warnings"] as List<String>
            assertEquals(minOf(row["warningCnt"] as Int, 20), w.size)
            assertEquals((row["warningCnt"] as Int) > 20, row["warningTruncated"])
        }
        val dup = long("SELECT count(*) FROM ax.tb_dash_upload_ver a JOIN ax.tb_dash_upload_ver b ON a.doc_id = b.doc_id AND b.ver < a.ver AND a.sha256 = b.sha256 WHERE a.doc_id = 1 AND a.ver = 2")
        assertEquals(if (dup > 0) 1 else null, v.single { it["version"] == 2 }["duplicateOf"])
        assertNull(v.single { it["version"] == 1 }["duplicateOf"])
    }

    @Test
    @DisplayName("AUD-14 로그인 이력에 시도 당시 부서 스냅샷 — 부서를 옮겨도 감사 목록의 로그인 행 부서는 그대로, 없는 사번은 null")
    fun loginDeptSnapshot() {
        UserContext.clear()
        exec("""INSERT INTO ax.tb_sys_user (user_id, user_nm, dept_id, position_cd, user_state_cd, pwd_hash, pwd_upd_at, pwd_change_req_yn, ins_user, upd_user)
                VALUES ('ZT-LGD', '로그인부서', 2, 'STAFF', 'ACTIVE', '${encoder.encode("Login#Dept123")}', now(), 'N', 'TEST', 'TEST')""")
        assertThrows(UnauthenticatedException::class.java) { auth.processLogin(LoginRequest("ZT-LGD", "wrong"), "10.9.9.8", "ZT") }
        auth.processLogin(LoginRequest("ZT-LGD", "Login#Dept123"), "10.9.9.8", "ZT")
        assertEquals(listOf("품질보증팀", "품질보증팀"),
            jdbc.queryForList("SELECT dept_nm FROM ax.tb_sys_login_hist WHERE user_id = 'ZT-LGD' ORDER BY login_id", MapSqlParameterSource(), String::class.java))
        exec("UPDATE ax.tb_sys_user SET dept_id = 3 WHERE user_id = 'ZT-LGD'")
        val rows = auditRepo.findAuditLogs(LocalDate.now().minusDays(1), LocalDate.now(), "LOGIN", null, "ZT-LGD", 10, 0)
        assertTrue(rows.isNotEmpty() && rows.all { it["dept"] == "품질보증팀" })

        assertThrows(UnauthenticatedException::class.java) { auth.processLogin(LoginRequest("ZT-NONE-LGD", "x"), "10.9.9.8", "ZT") }
        assertNull(jdbc.queryForObject("SELECT dept_nm FROM ax.tb_sys_login_hist WHERE user_id = 'ZT-NONE-LGD'", MapSqlParameterSource(), String::class.java))
    }
}
