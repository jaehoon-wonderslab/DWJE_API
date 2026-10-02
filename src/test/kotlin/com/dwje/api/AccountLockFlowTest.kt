package com.dwje.api

import com.dwje.api.common.security.PasswordEncoderService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

/**
 * 로그인 실패 기록·계정 잠금·이메일 잠금 해제·초기 비밀번호 차단 — 실제 로컬 DB 로 끝까지 돌린다.
 *
 * 09 기획서 AUD-01(실패 기록이 롤백되지 않음) · AUD-16(LOCKED · E-AUTH-005 · 잠금 해제 3경로) · 01 ACC-03(E-AUTH-006).
 * 로컬은 LOG 모드 · 고정 코드 000000 · 이메일 잠금 해제 켬(application-local.yml).
 *
 * 시드 계정(10000 등)은 잠그지 않는다 — 테스트마다 계정을 만든다.
 *
 * 로그인 이력·감사 로그·권한 이력은 위변조 방지 트리거(V51)로 지울 수 없다. 그래서 테스트 트랜잭션 안에서 돌리고 롤백한다.
 * 로그인 실패 기록은 `noRollbackFor` 라 호출자 트랜잭션에 얹히므로 같은 트랜잭션 안에서 보인다(AUD-01 의 「롤백되지 않음」 은
 * 예외로 끝나도 표시가 rollback-only 가 되지 않는다는 뜻이다). 감사·권한 이력은 별도 트랜잭션(REQUIRES_NEW)이라 목으로 받고 인자를 본다.
 * 인증 코드 발송만 독립 트랜잭션으로 커밋되므로 트랜잭션이 끝난 뒤 그 행을 지운다.
 */
@SpringBootTest(properties = ["app.ai.prewarm-enabled=false"])
@AutoConfigureMockMvc
@ActiveProfiles("local")
@org.springframework.transaction.annotation.Transactional
class AccountLockFlowTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: NamedParameterJdbcTemplate
    @Autowired lateinit var encoder: PasswordEncoderService
    @org.springframework.test.context.bean.override.mockito.MockitoBean lateinit var audit: com.dwje.api.service.AuditLogService
    private val json = ObjectMapper()

    private val created = mutableListOf<String>()
    private val oldPassword = "Lock!Test1234"
    private val newPassword = "New#Pass5678"

    /** 독립 트랜잭션으로 커밋된 인증 요청 행만 지운다 — 계정·이력은 테스트 롤백으로 사라진다 */
    @org.springframework.test.context.transaction.AfterTransaction
    fun cleanup() {
        if (created.isEmpty()) return
        val p = MapSqlParameterSource("ids", created.toTypedArray())
            .addValue("emails", created.map { "${it.lowercase()}@test.local" }.toTypedArray())
        jdbc.update("DELETE FROM ax.tb_sys_email_verify WHERE target_user_id = ANY(:ids) OR lower(email) = ANY(:emails)", p)
        created.clear()
    }

    /** 감사 기록 호출 인자 — (유형, 대상, 결과, 비고) */
    private fun audits(): List<List<Any?>> = org.mockito.Mockito.mockingDetails(audit).invocations
        .filter { it.method.name == "record" }.map { listOf(it.arguments[0], it.arguments[3], it.arguments[4], it.arguments[6]) }

    /** 권한 이력 호출 인자 — (행위자, 대상 사번, 내용). 로그인 사용자 경로와 비로그인 경로를 함께 본다 */
    private fun perms(): List<List<Any?>> = org.mockito.Mockito.mockingDetails(audit).invocations.mapNotNull {
        when (it.method.name) {
            "recordPermChangeAs" -> listOf(it.arguments[0], it.arguments[5], it.arguments[4])
            "recordPermChange" -> listOf("LOGIN", it.arguments[5], it.arguments[3])
            else -> null
        }
    }

    /** 품질보증팀 시험 계정 — 사번 접두사 ZT, 이메일은 사번 기반 */
    private fun newUser(state: String = "ACTIVE", pwdChange: String = "N", withEmail: Boolean = true): String {
        val id = "ZT" + (System.nanoTime() % 100_000_000).toString().padStart(8, '0')
        jdbc.update(
            """
            INSERT INTO ax.tb_sys_user (user_id, user_nm, dept_id, position_cd, user_state_cd, pwd_hash, pwd_upd_at,
                                        pwd_change_req_yn, email, ins_user, upd_user)
            VALUES (:id, '잠금시험', 2, 'STAFF', :state, :hash, now(), :yn, :email, 'TEST', 'TEST')
            """.trimIndent(),
            MapSqlParameterSource().addValue("id", id).addValue("state", state).addValue("hash", encoder.encode(oldPassword))
                .addValue("yn", pwdChange).addValue("email", if (withEmail) "${id.lowercase()}@test.local" else null)
        )
        created += id
        return id
    }

    private fun postJson(path: String, body: Map<String, Any?>, token: String? = null): Pair<Int, JsonNode> {
        val req = post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))
        if (token != null) req.header("Authorization", "Bearer $token")
        val res = mvc.perform(req).andReturn().response
        return res.status to json.readTree(res.contentAsByteArray)
    }

    private fun getJson(path: String, token: String): Pair<Int, JsonNode> {
        val res = mvc.perform(get(path).header("Authorization", "Bearer $token")).andReturn().response
        return res.status to json.readTree(res.contentAsByteArray)
    }

    private fun login(id: String, pw: String) = postJson("/api/v1/auth/login", mapOf("loginId" to id, "password" to pw))

    private fun one(sql: String, id: String): Any? =
        jdbc.queryForList(sql, MapSqlParameterSource("id", id)).firstOrNull()?.values?.firstOrNull()

    private fun count(sql: String, id: String): Long =
        jdbc.queryForObject(sql, MapSqlParameterSource("id", id), Long::class.java) ?: 0L

    @Test
    @DisplayName("AUD-01 — 계정 없음·비밀번호 1~4회 실패는 같은 401 E-AUTH-001 이고 실패 이력·횟수가 커밋된다, 성공하면 0")
    fun failuresAreCommitted() {
        val ghost = "ZT-NONE-" + System.nanoTime() % 100000
        val (s0, b0) = login(ghost, "x")
        assertEquals(401, s0); assertEquals("E-AUTH-001", b0["code"].asText())
        assertEquals(1, count("SELECT count(*) FROM ax.tb_sys_login_hist WHERE user_id = :id AND result_cd = 'FAIL' AND fail_reason = '존재하지 않는 계정' AND ip_addr IS NOT NULL", ghost))

        val id = newUser()
        repeat(4) { n ->
            val (s, b) = login(id, "wrong-$n")
            assertEquals(401, s); assertEquals("E-AUTH-001", b["code"].asText())
            assertEquals(b0["message"].asText(), b["message"].asText(), "계정 없음과 같은 문구여야 계정 유무가 드러나지 않는다")
        }
        assertEquals(4, (one("SELECT login_fail_cnt FROM ax.tb_sys_user WHERE user_id = :id", id) as Number).toInt())
        assertEquals(4, count("SELECT count(*) FROM ax.tb_sys_login_hist WHERE user_id = :id AND result_cd = 'FAIL'", id))

        assertEquals(200, login(id, oldPassword).first)
        assertEquals(0, (one("SELECT login_fail_cnt FROM ax.tb_sys_user WHERE user_id = :id", id) as Number).toInt())
    }

    @Test
    @DisplayName("AUD-16 — 5회째 401 E-AUTH-005 + LOCKED·이력·감사·권한 이력, 잠긴 뒤에는 맞는 비밀번호도 E-AUTH-005 이고 횟수가 그대로")
    fun fifthFailureLocks() {
        val id = newUser()
        repeat(4) { login(id, "wrong") }
        val (s, b) = login(id, "wrong")
        assertEquals(401, s); assertEquals("E-AUTH-005", b["code"].asText())
        assertTrue(b["data"]["mailEnabled"].asBoolean())
        assertTrue(b["data"]["emailMasked"].asText().endsWith("@test.local"))
        assertNotNull(b["data"]["lockedAt"].textValue())
        assertEquals("/forgot-password?mode=unlock", b["data"]["unlockPath"].asText())

        assertEquals("LOCKED", one("SELECT user_state_cd FROM ax.tb_sys_user WHERE user_id = :id", id))
        assertEquals(1, count("SELECT count(*) FROM ax.tb_sys_login_hist WHERE user_id = :id AND result_cd = 'LOCKED' AND fail_reason LIKE '연속 실패%'", id))
        assertEquals(1, audits().count { it[0] == "ACCOUNT_SEC" && it[2] == "REJECT" && it[1] == "계정 잠금 [$id]" })
        assertEquals(1, perms().count { it[0] == "SYSTEM" && it[1] == id && (it[2] as String).contains("ACTIVE → LOCKED") })

        val (s2, b2) = login(id, oldPassword)
        assertEquals(401, s2); assertEquals("E-AUTH-005", b2["code"].asText())
        assertEquals(5, (one("SELECT login_fail_cnt FROM ax.tb_sys_user WHERE user_id = :id", id) as Number).toInt())
        assertEquals(1, count("SELECT count(*) FROM ax.tb_sys_login_hist WHERE user_id = :id AND fail_reason = '잠긴 계정 로그인 시도'", id))
    }

    @Test
    @DisplayName("잠금 해제 요청 — 없는 사번·정지·잠김 모두 같은 200 본문, 정지 계정에는 코드가 생기지 않는다, 재요청은 발송 제한")
    fun unlockRequestDoesNotEnumerate() {
        val locked = newUser(state = "LOCKED")
        val suspended = newUser(state = "SUSPENDED")
        val ghost = "ZT-NONE-" + System.nanoTime() % 100000

        val bodies = listOf(ghost, suspended, locked).map { empNo ->
            val (s, b) = postJson("/api/v1/auth/unlock/request", mapOf("empNo" to empNo))
            assertEquals(200, s)
            (b as com.fasterxml.jackson.databind.node.ObjectNode).apply { remove("timestamp") }
        }
        assertEquals(bodies[0], bodies[1]); assertEquals(bodies[0], bodies[2])

        assertEquals(0, count("SELECT count(*) FROM ax.tb_sys_email_verify WHERE target_user_id = :id", suspended))
        assertEquals("SUSPENDED", one("SELECT user_state_cd FROM ax.tb_sys_user WHERE user_id = :id", suspended))
        assertEquals(1, audits().count { it[1] == "잠금 해제 요청 [$suspended]" && it[2] == "REJECT" && it[3] == "잠기지 않음" })
        assertEquals(1, count("SELECT count(*) FROM ax.tb_sys_email_verify WHERE target_user_id = :id AND purpose_cd = 'ACCOUNT_UNLOCK'", locked))

        // 재발송 대기 안의 재요청 — 응답은 같고 새 코드는 없다
        assertEquals(200, postJson("/api/v1/auth/unlock/request", mapOf("empNo" to locked)).first)
        assertEquals(1, count("SELECT count(*) FROM ax.tb_sys_email_verify WHERE target_user_id = :id", locked))
        assertEquals(1, audits().count { it[1] == "잠금 해제 요청 [$locked]" && it[3] == "발송 제한" })
    }

    @Test
    @DisplayName("잠금 해제 — 000000 검증 → 직전 비밀번호면 400(토큰 유지) → 새 비밀번호로 완료하면 ACTIVE·실패 0·변경 요구 N·로그인 200")
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    fun unlockCompletes() {
        // 400 이 토큰 사용을 되돌리는지는 요청마다 트랜잭션이 끝나야 보인다 — 이 시험만 트랜잭션 밖에서 돌리고
        // 지울 수 있는 행(계정·인증 요청)만 만든다. 로그인 이력이 남지 않게 마지막 확인은 로그인 대신 비밀번호 해시로 한다.
        val id = newUser(state = "LOCKED", pwdChange = "Y")
        try { unlockCompletesBody(id) } finally { cleanup() ; jdbc.update("DELETE FROM ax.tb_sys_user WHERE user_id = :id", MapSqlParameterSource("id", id)) }
    }

    private fun unlockCompletesBody(id: String) {
        jdbc.update("UPDATE ax.tb_sys_user SET login_fail_cnt = 5 WHERE user_id = :id", MapSqlParameterSource("id", id))
        postJson("/api/v1/auth/unlock/request", mapOf("empNo" to id))

        val (sv, bv) = postJson("/api/v1/auth/unlock/verify", mapOf("empNo" to id, "code" to "000000"))
        assertEquals(200, sv, bv.toString())
        val token = bv["data"]["verificationToken"].asText()

        val (s1, b1) = postJson("/api/v1/auth/unlock/complete",
            mapOf("verificationToken" to token, "newPassword" to oldPassword, "newPasswordConfirm" to oldPassword))
        assertEquals(400, s1); assertEquals("newPassword", b1["error"]["field"].asText())

        val (s2, b2) = postJson("/api/v1/auth/unlock/complete",
            mapOf("verificationToken" to token, "newPassword" to newPassword, "newPasswordConfirm" to newPassword))
        assertEquals(200, s2, b2.toString())
        assertEquals("ACTIVE", one("SELECT user_state_cd FROM ax.tb_sys_user WHERE user_id = :id", id))
        assertEquals(0, (one("SELECT login_fail_cnt FROM ax.tb_sys_user WHERE user_id = :id", id) as Number).toInt())
        assertEquals("N", one("SELECT pwd_change_req_yn FROM ax.tb_sys_user WHERE user_id = :id", id).toString())
        assertEquals(1, audits().count { it[1] == "잠금 해제 [$id]" && it[2] == "ALLOW" })
        assertEquals(1, perms().count { it[1] == id && (it[2] as String).contains("LOCKED → ACTIVE (본인 이메일 인증)") })
        assertTrue(encoder.matches(newPassword, one("SELECT pwd_hash FROM ax.tb_sys_user WHERE user_id = :id", id) as String))

        // 같은 토큰은 다시 쓸 수 없다
        val (s3, _) = postJson("/api/v1/auth/unlock/complete",
            mapOf("verificationToken" to token, "newPassword" to "Other#Pass999", "newPasswordConfirm" to "Other#Pass999"))
        assertEquals(409, s3)
    }

    @Test
    @DisplayName("공개 이메일 경로는 ACCOUNT_UNLOCK 을 받지 않는다(400 field=purpose) · 없는 사번 검증은 「유효한 인증 요청이 없습니다」")
    fun publicPathRejectsUnlockPurpose() {
        val (s, b) = postJson("/api/v1/auth/email/send-code", mapOf("email" to "a@test.local", "purpose" to "ACCOUNT_UNLOCK"))
        assertEquals(400, s); assertEquals("purpose", b["error"]["field"].asText())

        val (s2, b2) = postJson("/api/v1/auth/unlock/verify", mapOf("empNo" to "ZT-NONE-1", "code" to "000000"))
        assertEquals(409, s2); assertTrue(b2["message"].asText().startsWith("유효한 인증 요청이 없습니다"))
    }

    @Test
    @DisplayName("비밀번호 찾기로도 잠긴 계정의 코드가 나가고, 재설정하면 ACTIVE 가 된다")
    fun forgotPasswordUnlocks() {
        val id = newUser(state = "LOCKED")
        val email = "${id.lowercase()}@test.local"
        assertEquals(200, postJson("/api/v1/auth/password/forgot", mapOf("empNo" to id, "email" to email)).first)
        val (sv, bv) = postJson("/api/v1/auth/email/verify-code", mapOf("email" to email, "purpose" to "PASSWORD_RESET", "code" to "000000"))
        assertEquals(200, sv, bv.toString())
        val (sr, br) = postJson("/api/v1/auth/password/reset", mapOf(
            "verificationToken" to bv["data"]["verificationToken"].asText(),
            "newPassword" to newPassword, "newPasswordConfirm" to newPassword))
        assertEquals(200, sr, br.toString())
        assertEquals("ACTIVE", one("SELECT user_state_cd FROM ax.tb_sys_user WHERE user_id = :id", id))
    }

    @Test
    @DisplayName("ACC-03 — 초기 비밀번호 계정: 로그인 200·pwdChangeRequired, /auth/me 최소 정보, 업무 API 403 E-AUTH-006, 바꾸면 바로 풀린다")
    fun passwordChangeRequiredBlocksUntilChanged() {
        val id = newUser(pwdChange = "Y")
        val (s, b) = login(id, oldPassword)
        assertEquals(200, s); assertTrue(b["data"]["pwdChangeRequired"].asBoolean())
        val token = b["data"]["accessToken"].asText()

        val (sm, bm) = getJson("/api/v1/auth/me", token)
        assertEquals(200, sm)
        assertTrue(bm["data"]["pwdChangeRequired"].asBoolean())
        assertEquals(0, bm["data"]["menuPerms"].size()); assertEquals(0, bm["data"]["dataFields"].size())

        val (sb, bb) = getJson("/api/v1/system/users", token)
        assertEquals(403, sb); assertEquals("E-AUTH-006", bb["code"].asText())
        assertEquals(403, postJson("/api/v1/ai/chat/ask", mapOf("question" to "x"), token).first)

        val (sp, bp) = postJson("/api/v1/auth/password", mapOf(
            "currentPassword" to oldPassword, "newPassword" to newPassword, "newPasswordConfirm" to newPassword), token)
        assertEquals(200, sp, bp.toString())
        val (sm2, bm2) = getJson("/api/v1/auth/me", token)
        assertEquals(200, sm2)
        assertFalse(bm2["data"]["pwdChangeRequired"].asBoolean())
        assertTrue(bm2["data"]["menuPerms"].size() > 0, "품질보증팀 화면 권한이 다시 보여야 한다")
        assertTrue(bm2["data"].has("writePerms"))
    }
}
