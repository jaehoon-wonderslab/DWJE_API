package com.dwje.api

import com.dwje.api.common.security.UserContext
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.common.util.WorkcenterNames
import com.dwje.api.service.AlertConfigService
import com.dwje.api.service.CommonMasterService
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.annotation.Transactional

/**
 * 발송 조건 「고급 설정」 제거 · 설비 검색 1공장 한정 (2026-10-03). 실제 로컬 DB, 롤백. 감사는 목.
 */
@SpringBootTest(properties = ["app.ai.prewarm-enabled=false"])
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@ActiveProfiles("local")
@Transactional
class AlertAdvRemovalTest {

    @Autowired lateinit var masters: CommonMasterService
    @Autowired lateinit var alerts: AlertConfigService
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var mvc: org.springframework.test.web.servlet.MockMvc
    @Autowired lateinit var tokens: com.dwje.api.common.security.JwtTokenProvider
    @MockitoBean lateinit var audit: com.dwje.api.service.AuditLogService
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    lateinit var mapping: org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

    @AfterEach fun clear() = UserContext.clear()

    @Test
    @DisplayName("옛 화면 본문(HTTP) — 고급 설정 7가지를 보내도 400 없이 등록·수정되고(받고 버림), 모르는 키는 여전히 400, ONCE 는 400")
    fun oldBodiesAccepted() {
        val bearer = "Bearer " + tokens.createAccessToken("10000", "관리자", 1, "통합관리자", true, null)
        val adv = """"msgTemplate":"x","scopeDim":"EQPT","windowTime":"07:30","evalIntervalSec":300,"ignoreWindow":true,"autoClose":true,"escalation":[{"stage":1,"on":true}]"""
        fun send(req: org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder, body: String) =
            mvc.perform(req.header("Authorization", bearer).contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body)).andReturn().response
        val created = send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/alert-conditions"),
            """{"name":"ZT 고급 제거","severity":"WARN","threshold":"5","channels":["MAIL"],"groupIds":[11],$adv}""")
        assertEquals(200, created.status, created.contentAsString)
        val id = json.readTree(created.contentAsString).path("data").path("condId").asInt()
        val updated = send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/alert-conditions/$id"),
            """{"name":"ZT 고급 제거2",$adv,"windowTime":null}""")
        assertEquals(200, updated.status, updated.contentAsString)
        val detail = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/alert-conditions/$id")
            .header("Authorization", bearer)).andReturn().response
        val data = json.readTree(detail.contentAsString).path("data")
        assertEquals("ZT 고급 제거2", data.path("name").asText())
        listOf("msgTemplate", "scopeDim", "windowTime", "evalIntervalSec", "ignoreWindow", "autoClose", "escalation").forEach { assertFalse(data.has(it), it) }
        assertEquals(400, send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/alert-conditions"),
            """{"name":"ZT 모르는 키","severity":"WARN","nope":1}""").status)
        // Optional 필드 — JSON null 은 비우기(이전에는 ObjectMapper 에 Jdk8Module 이 없어 이 PUT 이 전부 500 이었다)
        val clear = send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/alert-conditions/$id"), """{"thresholdUnit":null}""")
        assertEquals(200, clear.status, clear.contentAsString)
        val group = send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/alert-recipient-groups/999999"), """{"deptId":null}""")
        assertEquals(404, group.status, "수신 그룹 수정도 본문을 읽는다(없는 그룹이라 404) — " + group.contentAsString)
        val once = send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/alert-conditions/$id"), """{"validWindow":"ONCE"}""")
        assertEquals(400, once.status)
        assertEquals(AlertConfigService.MSG_ONCE_REMOVED, json.readTree(once.contentAsString).path("message").asText())
    }

    @Test
    @DisplayName("발송 조건 목록 행에 msgTemplate 없음 · 기본 틀은 엔진과 같은 문장")
    fun listWithoutTemplate() {
        UserContext.set(UserPrincipal("10000", "관리자", 1, "통합관리자", null, null, true))
        val (rows, _, _) = alerts.getConditions(null, null, null, 1, 50)
        assertTrue(rows.isNotEmpty())
        rows.forEach { r -> listOf("msgTemplate", "scopeDim", "evalIntervalSec", "escalation").forEach { assertFalse(r.containsKey(it), it) } }
        assertEquals("[{{severity}}] {{condNm}} — {{scope}} {{metricNm}} {{value}}{{unit}} ({{op}} {{threshold}}{{unit}}) {{link}}",
            AlertConfigService.DEFAULT_MESSAGE_TEMPLATE)
    }

    @Test
    @DisplayName("설비 검색 — 설비코드 한 행(작업장 여러 곳은 wcCds), factory=M-1공장 이면 그 공장 작업장 설비만, 표기 없는 설비는 빠짐")
    fun equipmentsFactory() {
        val all = masters.getEquipments(null, null)
        assertEquals(all.size, all.map { it["eqptCd"] }.distinct().size, "설비코드 중복 없음")
        @Suppress("UNCHECKED_CAST")
        val multi = all.filter { (it["wcCds"] as List<String>).size > 1 }
        assertTrue(multi.isNotEmpty(), "작업장 2곳에 걸린 설비가 한 행으로 합쳐진다")
        assertEquals((multi.first()["wcCds"] as List<*>).first(), multi.first()["wcCd"])

        val m1 = masters.getEquipments(null, null, "M-1공장")
        assertTrue(m1.isNotEmpty())
        assertEquals(m1.size, m1.map { it["eqptCd"] }.distinct().size)
        @Suppress("UNCHECKED_CAST")
        m1.forEach { r -> (r["wcNms"] as List<String>).forEach { assertEquals("M-1공장", WorkcenterNames.plantOf(it), it) } }
        assertTrue(m1.size < all.size)
        assertTrue(m1.none { it["wcCd"] == null }, "작업장 없는 설비는 빠진다")
        assertEquals(all.size, masters.getEquipments(null, null, " ").size, "빈 factory 는 조건이 아니다")
        assertTrue(masters.getEquipments(null, null, "없는공장").isEmpty())
    }

    @Test
    @DisplayName("설비 검색 kind=PRESS — 작업장 이름에 「프레스」 든 설비만, factory=M-1공장 과 함께면 1공장 프레스 작업장만(W110·S141), 모르는 kind 는 400")
    fun equipmentsPress() {
        val all = masters.getEquipments(null, null)
        val press = masters.getEquipments(null, null, null, "PRESS")
        @Suppress("UNCHECKED_CAST")
        val wcNms = { rows: List<Map<String, Any?>> -> rows.flatMap { it["wcNms"] as List<String> } }
        assertTrue(press.isNotEmpty() && press.size < all.size)
        assertTrue(wcNms(press).all { it.contains("프레스") })

        val m1Press = masters.getEquipments(null, null, "M-1공장", "press")
        assertTrue(m1Press.isNotEmpty())
        assertEquals(m1Press.size, m1Press.map { it["eqptCd"] }.distinct().size, "설비코드 한 행")
        wcNms(m1Press).forEach { assertTrue(it.contains("프레스") && WorkcenterNames.plantOf(it) == "M-1공장", it) }
        val m1 = masters.getEquipments(null, null, "M-1공장")
        assertTrue(m1Press.size < m1.size, "1공장의 프레스 아닌 설비는 빠진다")
        @Suppress("UNCHECKED_CAST")
        assertEquals(setOf("W110", "S141"), m1Press.flatMap { it["wcCds"] as List<String> }.toSet())

        assertEquals(all.size, masters.getEquipments(null, null, null, " ").size, "빈 kind 는 조건이 아니다")
        val e = org.junit.jupiter.api.Assertions.assertThrows(com.dwje.api.common.exception.InvalidParameterException::class.java) {
            masters.getEquipments(null, null, null, "LASER")
        }
        assertEquals("kind", e.field)
    }

    @Test
    @DisplayName("승격 규칙 기능 제거 — 승격 API 경로가 없고, 수신 그룹 목록·상세·수신자 관리 요약에 승격 항목이 없다")
    fun escalationRemoved() {
        val paths = mapping.handlerMethods.keys.flatMap { it.patternValues }
        assertTrue(paths.none { it.contains("escalation") }, paths.filter { it.contains("escalation") }.toString())

        UserContext.set(UserPrincipal("10000", "관리자", 1, "통합관리자", null, null, true))
        val (groups, _) = alerts.getRecipientGroups(includeInactive = true)
        @Suppress("UNCHECKED_CAST")
        val items = groups["items"] as List<Map<String, Any?>>
        assertTrue(items.isNotEmpty())
        items.forEach { assertFalse(it.containsKey("escStages"), "escStages") }
        val (detail, _) = alerts.getRecipientGroup(items.first()["groupId"] as Int)
        assertFalse(detail.containsKey("escStages"), "escStages")
        assertFalse(alerts.getRecipientSummary().containsKey("escNoTargetCnt"), "escNoTargetCnt")
    }
}
