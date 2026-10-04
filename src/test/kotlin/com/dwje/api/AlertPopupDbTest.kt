package com.dwje.api

import com.dwje.api.common.exception.BusinessException
import com.dwje.api.common.exception.BusinessRuleException
import com.dwje.api.common.exception.ConflictingValueException
import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.exception.MenuAccessDeniedException
import com.dwje.api.common.exception.WriteAccessDeniedException
import com.dwje.api.common.security.UserContext
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.common.util.MenuId
import com.dwje.api.model.request.AlertConditionRequest
import com.dwje.api.model.request.AlertConditionUpdateRequest
import com.dwje.api.model.request.RecipientGroupUpdateRequest
import com.dwje.api.model.request.RecipientRequest
import com.dwje.api.service.AlertConfigService
import com.dwje.api.service.AlertService
import com.dwje.api.service.AuditLogService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.annotation.Transactional

/**
 * 나에게 온 팝업 알림(GET /alerts/popups, 2026-10-04) — 실제 로컬 DB, 테스트마다 롤백한다.
 * 기준점(after 없음) → 그 뒤 새 POPUP 발송만 · 내 것만 · 실패 발송은 빠짐.
 */
@SpringBootTest(properties = ["app.ai.prewarm-enabled=false"])
@ActiveProfiles("local")
@Transactional
class AlertPopupDbTest {

    @Autowired lateinit var alertService: AlertService
    @Autowired lateinit var jdbc: NamedParameterJdbcTemplate
    @MockitoBean lateinit var audit: AuditLogService

    private val me = UserPrincipal("10004", "전산", 5, "전산팀", null, null, false, menuPerms = setOf(MenuId.ALERT_LIST))

    @BeforeEach
    fun login() = UserContext.set(me)

    @AfterEach
    fun clear() = UserContext.clear()

    private fun newAlert(title: String): Long =
        jdbc.queryForObject(
            "INSERT INTO ax.tb_alm_alert (severity_cd, title, occurred_at, ack_state_cd) VALUES ('WARN', :t, now(), 'OPEN') RETURNING alert_id",
            MapSqlParameterSource("t", title), Long::class.java
        )!!

    private fun sendLog(alertId: Long, userId: String, channel: String, result: String) {
        jdbc.update(
            "INSERT INTO ax.tb_alm_send_log (alert_id, user_id, channel_cd, dest_addr, sent_at, send_result_cd) VALUES (:a, :u, :c, :u, now(), :r)",
            MapSqlParameterSource().addValue("a", alertId).addValue("u", userId).addValue("c", channel).addValue("r", result)
        )
    }

    @Test
    @DisplayName("팝업 — 기준점은 items 없이 lastSendId, 그 뒤 내 POPUP 성공만(메일 · 남의 것 · 실패 제외), 최대 5건 오름차순")
    fun popups() {
        val base = alertService.getMyPopups(null)
        assertEquals(emptyList<Any>(), base["items"])
        val after = base["lastSendId"] as Long

        val a1 = newAlert("ZT 팝업 1")
        sendLog(a1, "10004", "POPUP", "SENT")
        sendLog(a1, "10004", "MAIL", "SENT")
        sendLog(a1, "10000", "POPUP", "SENT")
        val a2 = newAlert("ZT 팝업 2")
        sendLog(a2, "10004", "POPUP", "FAIL")

        val r = alertService.getMyPopups(after)
        @Suppress("UNCHECKED_CAST")
        val items = r["items"] as List<Map<String, Any?>>
        assertEquals(listOf(a1), items.map { it["alertId"] })
        assertEquals("ZT 팝업 1", items[0]["title"])
        assertEquals(items[0]["sendId"], r["lastSendId"])

        // 다시 부르면 새 것이 없다
        @Suppress("UNCHECKED_CAST")
        val again = alertService.getMyPopups(r["lastSendId"] as Long)["items"] as List<Map<String, Any?>>
        assertTrue(again.isEmpty())

        // 6건이 와도 한 번에 5건
        repeat(6) { sendLog(newAlert("ZT 팝업 n$it"), "10004", "POPUP", "SENT") }
        @Suppress("UNCHECKED_CAST")
        val five = alertService.getMyPopups(r["lastSendId"] as Long)["items"] as List<Map<String, Any?>>
        assertEquals(5, five.size)
        assertTrue(five.zipWithNext().all { (x, y) -> (x["sendId"] as Long) < (y["sendId"] as Long) })
    }

    @Test
    @DisplayName("팝업 — 알림 목록 권한이 없으면 403")
    fun popupsNeedAlertList() {
        UserContext.set(UserPrincipal("10002", "생산", 3, "생산관리팀", null, null, false, menuPerms = emptySet()))
        assertThrows(MenuAccessDeniedException::class.java) { alertService.getMyPopups(null) }
    }
}
