package com.dwje.api

import com.dwje.api.common.exception.BusinessException
import com.dwje.api.common.exception.BusinessRuleException
import com.dwje.api.common.exception.ConflictingValueException
import com.dwje.api.common.exception.InvalidParameterException
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
import java.util.Optional

/**
 * 05 ALC-01~05 · 06 RCP-01~04 — 실제 로컬 DB, 테스트마다 롤백한다.
 *
 * 감사 기록은 별도 트랜잭션(REQUIRES_NEW)이라 롤백되지 않고, 감사 표는 지울 수 없다(V51).
 * 그래서 감사 서비스는 목으로 바꿔 호출 여부만 본다.
 */
@SpringBootTest(properties = ["app.ai.prewarm-enabled=false"])
@ActiveProfiles("local")
@Transactional
class AlertConfigDbTest {

    @Autowired lateinit var service: AlertConfigService
    @Autowired lateinit var alertService: AlertService
    @Autowired lateinit var jdbc: NamedParameterJdbcTemplate
    @MockitoBean lateinit var audit: AuditLogService

    private val admin = UserPrincipal("10000", "관리자", 1, "통합관리자", null, null, true)

    /** 전산팀 — 두 화면 쓰기, worker 있음 */
    private val itTeam = UserPrincipal(
        "10004", "전산", 5, "전산팀", null, null, false,
        menuPerms = setOf(MenuId.ALERT_COND, MenuId.SYS_RECIP), dataPerms = setOf("worker")
    )

    @BeforeEach
    fun login() = UserContext.set(admin)

    @AfterEach
    fun clear() = UserContext.clear()

    private fun long(sql: String, p: Map<String, Any?> = emptyMap()) =
        jdbc.queryForObject(sql, MapSqlParameterSource(p), Long::class.java)!!

    private fun str(sql: String, p: Map<String, Any?> = emptyMap()) =
        jdbc.queryForObject(sql, MapSqlParameterSource(p), String::class.java)

    /** 그룹 11(MAIL, 10000·10003·10004)을 쓰는 새 조건 */
    private fun newCond(channels: List<String> = listOf("MAIL"), groupIds: List<Int> = listOf(11)): Int =
        service.createCondition(
            AlertConditionRequest(
                name = "ZT 알림 시험 조건", severity = "WARN", channels = channels, groupIds = groupIds,
                threshold = "5", thresholdUnit = "PCT", msgTemplate = "ZT 틀"
            )
        )["condId"] as Int

    @Test
    @DisplayName("ALC-01 상태 — 본문 없음 400(field on), 같은 값 changed=false·감사 없음, 바꾸면 changed=true")
    fun state() {
        val condId = newCond()
        clearInvocations(audit)
        val e = assertThrows(InvalidParameterException::class.java) { service.changeConditionState(condId, null, null) }
        assertEquals("on", e.field)
        assertEquals(false, service.changeConditionState(condId, true, null)["changed"])
        verify(audit, never()).record(anyString(), anyString(), any(), any(), anyString(), anyInt(), any())
        assertEquals(true, service.changeConditionState(condId, null, "off")["changed"])
        assertEquals("N", str("SELECT use_flg FROM ax.tb_alm_cond WHERE cond_id = $condId"))
    }

    @Test
    @DisplayName("ALC-04 수정 — 이름만 보내면 나머지 유지, updatedAt 불일치 409, 채널·그룹 빈 배열 400")
    fun updateKeeps() {
        val condId = newCond()
        jdbc.update("UPDATE ax.tb_alm_cond SET scope_dim_cd = 'EQPT' WHERE cond_id = $condId", MapSqlParameterSource())
        val before = service.getCondition(condId)
        val res = service.updateCondition(condId, AlertConditionUpdateRequest(name = "ZT 알림 시험 조건2", updatedAt = before["updatedAt"] as String))
        val after = service.getCondition(condId)
        assertEquals("ZT 알림 시험 조건2", after["name"])
        listOf("msgTemplate", "thresholdUnit", "targetScope", "scopeDim", "op", "channels", "groupIds", "target").forEach {
            assertEquals(before[it], after[it], it)
        }
        assertEquals(after["updatedAt"], res["updatedAt"])

        val stale = assertThrows(ConflictingValueException::class.java) {
            service.updateCondition(condId, AlertConditionUpdateRequest(name = "x", updatedAt = "2001-01-01 00:00:00"))
        }
        assertEquals("updatedAt", stale.field)
        assertEquals("channels", assertThrows(InvalidParameterException::class.java) {
            service.updateCondition(condId, AlertConditionUpdateRequest(channels = emptyList()))
        }.field)
        assertEquals("groupIds", assertThrows(InvalidParameterException::class.java) {
            service.updateCondition(condId, AlertConditionUpdateRequest(groupIds = emptyList()))
        }.field)
        // thresholdUnit: JSON null → 비우기
        service.updateCondition(condId, AlertConditionUpdateRequest(thresholdUnit = Optional.empty()))
        assertNull(service.getCondition(condId)["thresholdUnit"])
    }

    @Test
    @DisplayName("ALC-05 대상 범위 — 모르는 코드 400, PICK 은 설비 필수·저장, PICK 해제 시 대상 삭제, 설명은 범위 표기명")
    fun targetScope() {
        val condId = newCond()
        assertEquals("targetScope", assertThrows(InvalidParameterException::class.java) {
            service.updateCondition(condId, AlertConditionUpdateRequest(targetScope = "NOPE"))
        }.field)
        assertEquals("pickTargets", assertThrows(InvalidParameterException::class.java) {
            service.updateCondition(condId, AlertConditionUpdateRequest(targetScope = "PICK"))
        }.field)
        val eqpts = jdbc.queryForList("SELECT eqpt_cd FROM mes.tb_md_eqpt WHERE plant_cd = 'PL01' ORDER BY eqpt_cd LIMIT 2", MapSqlParameterSource(), String::class.java)
        service.updateCondition(condId, AlertConditionUpdateRequest(targetScope = "PICK", pickTargets = eqpts))
        val d = service.getCondition(condId)
        assertEquals(eqpts, d["pickTargets"])
        assertEquals("개별 설비 선택", d["target"]) // 옛 설명이 「전체 설비」 표기명이라 따라간다
        service.updateCondition(condId, AlertConditionUpdateRequest(targetScope = "ALL_EQPT"))
        assertEquals(0L, long("SELECT count(*) FROM ax.tb_alm_cond_target WHERE cond_id = $condId"))
    }

    @Test
    @DisplayName("ALC-03 테스트 발송 — 대기열 적재·test_flg=Y·send_log 직접 기록 없음·연락처 없음, 60초 재요청 409")
    fun testSendCondition() {
        val condId = newCond()
        val logsBefore = long("SELECT count(*) FROM ax.tb_alm_send_log")
        val (data, _) = service.testSendCondition(condId)
        val alertId = (data["alertId"] as Number).toLong()
        assertEquals("Y", str("SELECT test_flg FROM ax.tb_alm_alert WHERE alert_id = $alertId"))
        val queued = long("SELECT count(*) FROM ax.tb_alm_send_queue WHERE alert_id = $alertId")
        assertEquals(queued.toInt(), data["queuedCnt"])
        assertTrue(queued >= 1)
        assertEquals(logsBefore, long("SELECT count(*) FROM ax.tb_alm_send_log"))
        assertFalse(data.toString().contains("@"), "응답에 메일 주소가 없어야 한다")

        assertThrows(ConflictingValueException::class.java) { service.testSendCondition(condId) }

        // 테스트 알림은 기본 알림 목록에 없다
        val (rows, _) = alertService.getAlerts(null, null, "today", null, 1, 500)
        assertTrue(rows.none { (it["alertId"] as Number).toLong() == alertId })
        val (all, _) = alertService.getAlerts(null, null, "today", null, 1, 500, includeTest = true)
        assertTrue(all.any { (it["alertId"] as Number).toLong() == alertId })
    }

    @Test
    @DisplayName("ALC-03 SMS 만인 조건 — 그룹이 받지 않는 채널이라 대기열 0·알림 없음")
    fun channelMismatch() {
        val condId = newCond(channels = listOf("SMS"))
        val (data, _) = service.testSendCondition(condId)
        assertNull(data["alertId"])
        assertEquals(0, data["queuedCnt"])
        @Suppress("UNCHECKED_CAST")
        val skipped = data["skipped"] as List<Map<String, Any?>>
        assertTrue(skipped.any { it["reason"] == "CHANNEL_MISMATCH" && it["reasonNm"] == "그룹이 받지 않는 채널(SMS)" })
    }

    @Test
    @DisplayName("삭제 — 테스트 알림만 있으면 삭제되고 cond_id NULL, 운영 알림이 있으면 409, 전산팀은 403")
    fun delete() {
        val condId = newCond()
        val (data, _) = service.testSendCondition(condId)
        val alertId = (data["alertId"] as Number).toLong()

        UserContext.set(itTeam)
        assertEquals("발송 조건 삭제는 통합관리자만 할 수 있습니다.",
            assertThrows(BusinessException::class.java) { service.deleteCondition(condId) }.message)

        UserContext.set(admin)
        val other = service.createCondition(AlertConditionRequest(name = "ZT 운영 알림 조건", severity = "LOW", threshold = "1", channels = listOf("MAIL"), groupIds = listOf(11)))["condId"] as Int
        jdbc.update("INSERT INTO ax.tb_alm_alert (cond_id, severity_cd, title, occurred_at, ack_state_cd) VALUES ($other, 'LOW', 'ZT', now(), 'OPEN')", MapSqlParameterSource())
        assertTrue(assertThrows(BusinessRuleException::class.java) { service.deleteCondition(other) }.message!!.contains("테스트 알림은 세지 않습니다"))

        service.deleteCondition(condId)
        assertEquals(0L, long("SELECT count(*) FROM ax.tb_alm_cond WHERE cond_id = $condId"))
        assertNull(jdbc.queryForObject("SELECT cond_id FROM ax.tb_alm_alert WHERE alert_id = $alertId", MapSqlParameterSource(), Int::class.java))
    }

    @Test
    @DisplayName("RCP-03 그룹 테스트 — 부재·정지 제외, 대기열 경로, 계정 정지는 ACCOUNT_INACTIVE")
    fun groupTestSend() {
        jdbc.update("UPDATE ax.tb_sys_user SET user_state_cd = 'SUSPENDED' WHERE user_id = '10004'", MapSqlParameterSource())
        val (data, _) = service.testSendGroup(11)
        @Suppress("UNCHECKED_CAST") val recipients = data["recipients"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST") val skipped = data["skipped"] as List<Map<String, Any?>>
        assertTrue(recipients.none { it["empNo"] == "10004" })
        assertTrue(skipped.any { it["empNo"] == "10004" && it["reason"] == "ACCOUNT_INACTIVE" })
        assertEquals(recipients.size, data["queuedCnt"])
        val alertId = (data["alertId"] as Number).toLong()
        assertEquals("[테스트] 수신 그룹 발송 확인 — 엔진 가동", str("SELECT title FROM ax.tb_alm_alert WHERE alert_id = $alertId"))
        assertEquals(recipients.size.toLong(), long("SELECT count(*) FROM ax.tb_alm_send_queue WHERE alert_id = $alertId"))
    }

    @Test
    @DisplayName("ALC-02·RCP-01 — alert-cond 만이면 그룹 멤버 키 없음, worker 없으면 이름·연락처 null + masked, 연락처 쓰기 403")
    fun groupsAndMasking() {
        val condOnly = UserPrincipal("10001", "품질", 2, "품질보증팀", null, null, false, menuPerms = setOf(MenuId.ALERT_COND))
        UserContext.set(condOnly)
        val (data, _) = service.getRecipientGroups()
        @Suppress("UNCHECKED_CAST") val items = data["items"] as List<Map<String, Any?>>
        assertTrue(items.isNotEmpty())
        assertTrue(items.all { "members" !in it && "memberEmpNos" !in it && "memberCnt" in it && "receivingCnt" in it })

        val noWorker = UserPrincipal("10001", "품질", 2, "품질보증팀", null, null, false,
            menuPerms = setOf(MenuId.SYS_RECIP))
        UserContext.set(noWorker)
        val (rows, _, masked) = service.getRecipients(null, 1, 50)
        assertEquals(listOf("worker"), masked)
        assertTrue(rows.all { it["mail"] == null && it["name"] == null && it["empNo"] != null && "userState" in it })
        val (groups, gMasked) = service.getRecipientGroups()
        assertEquals(listOf("worker"), gMasked)
        @Suppress("UNCHECKED_CAST")
        val members = (groups["items"] as List<Map<String, Any?>>).flatMap { it["members"] as List<Map<String, Any?>> }
        assertTrue(members.all { it["name"] == null })
        val denied = assertThrows(BusinessException::class.java) { service.updateRecipient("10000", RecipientRequest(hp = "000")) }
        assertEquals("E-AUTH-003", denied.errorCode.code)

        UserContext.set(itTeam)
        val (rows2, _, masked2) = service.getRecipients(null, 1, 50)
        assertTrue(masked2.isEmpty())
        assertTrue(rows2.all { it["mail"] != null })
    }

    @Test
    @DisplayName("RCP-02 그룹 수정 — 이름만 보내면 부서·채널 유지, memberEmpNos [] 는 전원 제외, deptId null 은 비우기, updatedAt 불일치 409")
    fun groupUpdateKeeps() {
        val before = service.getRecipientGroup(11).first
        service.updateRecipientGroup(11, RecipientGroupUpdateRequest(name = "ZT 엔진 가동", updatedAt = before["updatedAt"] as String))
        val after = service.getRecipientGroup(11).first
        assertEquals(before["deptId"], after["deptId"])
        assertEquals(before["channels"], after["channels"])
        assertEquals(before["memberEmpNos"], after["memberEmpNos"])

        service.updateRecipientGroup(11, RecipientGroupUpdateRequest(memberEmpNos = emptyList(), deptId = Optional.empty()))
        assertEquals(0L, long("SELECT count(*) FROM ax.tb_alm_recip_group_member WHERE group_id = 11"))
        assertNull(jdbc.queryForObject("SELECT dept_id FROM ax.tb_alm_recip_group WHERE group_id = 11", MapSqlParameterSource(), Int::class.java))

        assertEquals("memberEmpNos", assertThrows(InvalidParameterException::class.java) {
            service.updateRecipientGroup(11, RecipientGroupUpdateRequest(memberEmpNos = listOf("NO-SUCH")))
        }.field)
        assertThrows(ConflictingValueException::class.java) {
            service.updateRecipientGroup(11, RecipientGroupUpdateRequest(night = true, updatedAt = "2001-01-01 00:00:00"))
        }
    }

    @Test
    @DisplayName("쓰기 동작 — 미배정 계정은 화면에 접근해도 조건 등록·수정·상태·테스트 모두 E-AUTH-004, DB 불변 (V70)")
    fun writePerm() {
        val condId = newCond()
        UserContext.set(UserPrincipal("10001", "품질", 59, "미배정", null, null, false, menuPerms = setOf(MenuId.ALERT_COND), unassigned = true))
        val name = str("SELECT cond_nm FROM ax.tb_alm_cond WHERE cond_id = $condId")
        assertThrows(WriteAccessDeniedException::class.java) { service.updateCondition(condId, AlertConditionUpdateRequest(name = "x")) }
        assertThrows(WriteAccessDeniedException::class.java) { service.changeConditionState(condId, false, null) }
        assertThrows(WriteAccessDeniedException::class.java) { service.testSendCondition(condId) }
        assertEquals(name, str("SELECT cond_nm FROM ax.tb_alm_cond WHERE cond_id = $condId"))
        verify(audit, times(1)).record(anyString(), anyString(), any(), any(), anyString(), anyInt(), any())
    }
}
