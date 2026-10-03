package com.dwje.api

import com.dwje.api.common.security.JwtTokenProvider
import com.dwje.api.common.security.UserContext
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.common.util.MenuId
import com.dwje.api.service.AuditLogService
import com.dwje.api.service.GlossaryService
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.transaction.annotation.Transactional

/**
 * 용어 분류 삭제(V75) · 고객사 가림을 용어 단위로 옮김 — customerInfo 등록·수정, 응답에 분류 없음.
 * 실제 로컬 DB(V75 적용), 롤백. 감사는 목.
 */
@SpringBootTest(properties = ["app.ai.prewarm-enabled=false"])
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Transactional
class GlossaryCustomerInfoTest {

    @Autowired lateinit var service: GlossaryService
    @Autowired lateinit var jdbc: NamedParameterJdbcTemplate
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var tokens: JwtTokenProvider
    @Autowired lateinit var json: ObjectMapper
    @MockitoBean lateinit var audit: AuditLogService

    private val admin = UserPrincipal("10000", "관리자", 1, "통합관리자", null, null, true)
    /** 관리 화면 쓰기 · 고객사 데이터 권한 없음 */
    private val writer = UserPrincipal("10002", "박생산", 3, "생산팀", null, null, false, menuPerms = setOf(MenuId.SYS_GLOSS))
    /** 조회 화면만 · 고객사 데이터 권한 없음 */
    private val viewer = UserPrincipal("10003", "조회", 3, "생산팀", null, null, false, menuPerms = setOf(MenuId.GLOSS_VIEW))
    /** 조회 화면 · 고객사 데이터 권한 있음 */
    private val customerViewer = viewer.copy(dataPerms = setOf("customer"))

    @BeforeEach
    fun login() = UserContext.set(admin)

    @AfterEach
    fun clear() = UserContext.clear()

    private fun key(termId: Int): String? = jdbc.queryForList(
        "SELECT data_field_key FROM ax.tb_gls_term WHERE term_id = :id", MapSqlParameterSource("id", termId), String::class.java).single()

    @Suppress("UNCHECKED_CAST")
    private fun listed(termId: Int, keyword: String) = service.getTerms(keyword, 1, 50).first.single { it["termId"] == termId }

    @Test
    @DisplayName("customerInfo 등록·수정 — true=customer 저장, 수정에서 생략하면 그대로, false 면 풂 / 고객사 권한 없는 열람자에게 가려짐")
    fun createUpdate() {
        val id = service.createTerm("ZT고객사용어", "고객사 이름", customerInfo = true)["termId"] as Int
        assertEquals("customer", key(id))
        assertEquals(true, listed(id, "ZT고객사")["customerInfo"])

        UserContext.set(viewer)
        val hidden = service.getTerms(null, 1, 2000).first.single { it["termId"] == id }
        assertEquals(GlossaryService.BLIND_TERM, hidden["term"]); assertEquals(true, hidden["blinded"])
        assertNull(hidden["customerInfo"], "조회 화면에는 고객사 정보 표시를 주지 않는다")
        assertEquals(true, service.getTermDetail(id)["blinded"])
        UserContext.set(customerViewer)
        assertEquals("ZT고객사용어", service.getTermDetail(id)["term"])
        UserContext.set(writer)
        assertEquals(true, service.getTermDetail(id)["blinded"])

        UserContext.set(admin)
        service.updateTerm(id, "ZT고객사용어", "고친 뜻")
        assertEquals("customer", key(id), "생략하면 지금 값을 둔다")
        service.updateTerm(id, "ZT고객사용어", "고친 뜻", customerInfo = false)
        assertNull(key(id))
        assertEquals(false, listed(id, "ZT고객사")["customerInfo"])
        UserContext.set(viewer)
        assertEquals("ZT고객사용어", service.getTermDetail(id)["term"])

        UserContext.set(admin)
        val plain = service.createTerm("ZT일반용어", "일반")["termId"] as Int
        assertNull(key(plain), "등록에서 생략하면 false")
    }

    @Test
    @DisplayName("응답에 분류가 없다 — 요약 domainCnt·byDomain, 목록·상세 domain·domainId, 변경 이력 domainId, 내려받기 「분류」 열")
    fun noDomainInResponses() {
        val summary = service.getSummary()
        assertFalse("domainCnt" in summary || "byDomain" in summary)
        val row = service.getTerms(null, 1, 5).first.first()
        assertTrue(row.keys.none { it.startsWith("domain") } && "fieldKey" !in row && "customerInfo" in row, row.keys.toString())
        val detail = service.getTermDetail(5)
        assertTrue(detail.keys.none { it.startsWith("domain") } && "fieldKey" !in detail)
        @Suppress("UNCHECKED_CAST")
        assertTrue((detail["relatedTerms"] as List<Map<String, Any?>>).all { it.keys.none { k -> k.startsWith("domain") || k == "fieldKey" } })
        service.updateTerm(5, "8D", "8단계 문제해결 보고서")
        assertTrue(service.getChanges(5, null, null, 1, 5).first.all { it.keys.none { k -> k == "domainId" || k == "fieldKey" } })
        val export = service.exportTerms(com.dwje.api.model.request.GlossaryExportRequest(menuId = "sys-gloss", scopeCd = "ALL"))
        assertFalse("분류" in export.headers)
    }

    @Test
    @DisplayName("HTTP — 등록 본문 customerInfo, 옛 화면이 보내는 domainCd 는 받고 버림, GET /glossary/domains 는 없음")
    fun http() {
        val bearer = "Bearer " + tokens.createAccessToken("10000", "관리자", 1, "통합관리자", true, null)
        val created = mvc.perform(post("/api/v1/glossary/terms").header("Authorization", bearer)
            .contentType(MediaType.APPLICATION_JSON).content("""{"term":"ZT본문용어","definition":"뜻","customerInfo":true,"domainCd":"고객사"}""")).andReturn()
        assertEquals(200, created.response.status, created.response.getContentAsString(Charsets.UTF_8))
        val id = json.readTree(created.response.getContentAsString(Charsets.UTF_8)).path("data").path("termId").asInt()
        assertEquals("customer", key(id))

        val updated = mvc.perform(put("/api/v1/glossary/terms/$id").header("Authorization", bearer)
            .contentType(MediaType.APPLICATION_JSON).content("""{"term":"ZT본문용어","definition":"뜻","customerInfo":false}""")).andReturn()
        assertEquals(200, updated.response.status)
        assertNull(key(id))

        assertNotEquals(200, mvc.perform(get("/api/v1/glossary/domains").header("Authorization", bearer)).andReturn().response.status)
    }
}
