package com.dwje.api

import com.dwje.api.common.security.JwtTokenProvider
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.transaction.annotation.Transactional

/**
 * 부서 약칭(dept_abbr) 제거(2026-10-02) — API 는 약칭을 읽지도 쓰지도 않는다.
 * DB 가 V68(제약 완화)·V69(컬럼 삭제) 어느 쪽이어도 통과해야 한다. 실제 로컬 DB, 롤백. 감사는 목.
 */
@SpringBootTest(properties = ["app.ai.prewarm-enabled=false"])
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Transactional
class DeptAbbrRemovalTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var tokens: JwtTokenProvider
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var jdbc: NamedParameterJdbcTemplate
    @MockitoBean lateinit var audit: com.dwje.api.service.AuditLogService

    private val bearer by lazy { "Bearer " + tokens.createAccessToken("10000", "관리자", 1, "통합관리자", true, null) }

    private fun send(method: String, path: String, body: String? = null): MvcResult {
        val req = when (method) { "POST" -> post(path); "PUT" -> put(path); else -> get(path) }
            .header("Authorization", bearer)
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body)
        return mvc.perform(req).andReturn()
    }

    private fun tree(r: MvcResult): JsonNode = json.readTree(r.response.getContentAsString(Charsets.UTF_8))

    /** V68 이면 컬럼이 남아 있다 — 그때만 값이 비었는지 본다 */
    private fun abbrColumnExists(): Boolean = jdbc.queryForObject(
        "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'ax' AND table_name = 'tb_sys_dept' AND column_name = 'dept_abbr'",
        MapSqlParameterSource(), Long::class.java)!! > 0

    private fun deptAbbr(deptId: Int): String? = jdbc.queryForObject(
        "SELECT dept_abbr FROM ax.tb_sys_dept WHERE dept_id = :id", MapSqlParameterSource("id", deptId), String::class.java)

    @Test
    @DisplayName("abbr 없이 부서 등록·수정 성공 / abbr 를 보내도 무시하고 성공(옛 화면 호환) / 모르는 키는 여전히 400")
    fun createUpdateWithoutAbbr() {
        val plain = send("POST", "/api/v1/system/depts", """{"deptNm":"ZT약칭없음"}""")
        assertEquals(200, plain.response.status, plain.response.contentAsString)
        val plainId = tree(plain).path("data").path("deptId").asInt()

        // 예전에는 5자라 400 이었다. 지금은 값을 보지 않는다
        val ignored = send("POST", "/api/v1/system/depts", """{"deptNm":"ZT약칭무시","abbr":"ABCDE"}""")
        assertEquals(200, ignored.response.status, ignored.response.contentAsString)
        val ignoredId = tree(ignored).path("data").path("deptId").asInt()
        if (abbrColumnExists()) {
            assertNull(deptAbbr(plainId)); assertNull(deptAbbr(ignoredId), "보낸 abbr 를 저장하지 않는다")
        }

        val updated = send("PUT", "/api/v1/system/depts/$ignoredId", """{"deptNm":"ZT약칭무시2","abbr":"  ","desc":"설명"}""")
        assertEquals(200, updated.response.status, updated.response.contentAsString)
        assertEquals("ZT약칭무시2", jdbc.queryForObject("SELECT dept_nm FROM ax.tb_sys_dept WHERE dept_id = :id",
            MapSqlParameterSource("id", ignoredId), String::class.java))
        if (abbrColumnExists()) assertNull(deptAbbr(ignoredId))

        assertEquals(400, send("POST", "/api/v1/system/depts", """{"deptNm":"ZT모르는키","abbrev":"x"}""").response.status)
    }

    @Test
    @DisplayName("중복 검사는 부서명만 — 같은 이름은 E-VALID-002(400, 기존 코드 그대로) 「이미 등록된 부서명입니다.」, 기존 부서의 옛 약칭과 같은 abbr 는 막지 않는다")
    fun duplicateNameOnly() {
        val first = send("POST", "/api/v1/system/depts", """{"deptNm":"ZT중복"}""")
        assertEquals(200, first.response.status)
        val dup = send("POST", "/api/v1/system/depts", """{"deptNm":"ZT중복","abbr":"ZZ"}""")
        assertEquals(400, dup.response.status, dup.response.contentAsString)
        assertEquals("E-VALID-002", tree(dup).path("code").asText())
        assertEquals("이미 등록된 부서명입니다.", tree(dup).path("message").asText())

        val sameOldAbbr = send("POST", "/api/v1/system/depts", """{"deptNm":"ZT옛약칭","abbr":"품보"}""")
        assertEquals(200, sameOldAbbr.response.status, "약칭 중복은 더 이상 검사하지 않는다")

        val id = tree(first).path("data").path("deptId").asInt()
        val rename = send("PUT", "/api/v1/system/depts/$id", """{"deptNm":"품질보증팀"}""")
        assertEquals("E-VALID-002", tree(rename).path("code").asText())
        assertEquals("이미 등록된 부서명입니다.", tree(rename).path("message").asText())
    }

    @Test
    @DisplayName("응답에 약칭 없음 — /auth/me dept.deptAbbr, 부서 목록·가입 부서 목록 abbr, 계정 목록 deptAbbr")
    fun responsesWithoutAbbr() {
        val me = send("GET", "/api/v1/auth/me")
        assertEquals(200, me.response.status, me.response.contentAsString)
        val dept = tree(me).path("data").path("dept")
        assertTrue(dept.has("deptNm")); assertFalse(dept.has("deptAbbr"), dept.toString())

        val depts = tree(send("GET", "/api/v1/system/depts")).path("data")
        val items = if (depts.isArray) depts else depts.path("items")
        assertTrue(items.size() > 0); assertTrue(items.none { it.has("abbr") }, items.toString().take(300))

        val signup = mvc.perform(get("/api/v1/auth/signup/depts")).andReturn()
        assertEquals(200, signup.response.status)
        val signupDepts = tree(signup).path("data").path("depts")
        assertTrue(signupDepts.size() > 0); assertTrue(signupDepts.none { it.has("abbr") })

        val users = tree(send("GET", "/api/v1/system/users?keyword=10001&page=1&size=10")).path("data")
        val rows = if (users.isArray) users else users.path("items")
        assertTrue(rows.size() > 0); assertTrue(rows.none { it.has("deptAbbr") })
    }
}
