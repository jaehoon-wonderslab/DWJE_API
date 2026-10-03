package com.dwje.api

import com.dwje.api.common.exception.BusinessException
import com.dwje.api.common.exception.ConflictingValueException
import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.security.UserContext
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.common.util.MenuId
import com.dwje.api.model.request.AlertConditionRequest
import com.dwje.api.model.request.AlertConditionUpdateRequest
import com.dwje.api.model.request.CondEscalationInput
import com.dwje.api.model.request.RecipientGroupRequest
import com.dwje.api.model.request.RecipientRequest
import com.dwje.api.service.AlertConfigService
import com.dwje.api.service.AlertEngineMonitor
import com.dwje.api.service.AuditLogService
import com.dwje.api.service.MetricStandardService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mockingDetails
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.annotation.Transactional

/** 05 ALC-06~12·14·17 · 06 RCP-05~13·16 — 실제 로컬 DB, 롤백. 감사는 목(감사 표는 지울 수 없다). */
@SpringBootTest(properties = ["app.ai.prewarm-enabled=false"])
@ActiveProfiles("local")
@Transactional
class AlertRecipientP1DbTest {

    @Autowired lateinit var service: AlertConfigService
    @Autowired lateinit var metrics: MetricStandardService
    @Autowired lateinit var jdbc: NamedParameterJdbcTemplate
    @Autowired lateinit var alerts: com.dwje.api.service.AlertService
    @MockitoBean lateinit var audit: AuditLogService

    private val admin = UserPrincipal("10000", "관리자", 1, "통합관리자", null, null, true)

    @BeforeEach
    fun login() = UserContext.set(admin)

    @AfterEach
    fun clear() = UserContext.clear()

    private fun exec(sql: String) = jdbc.update(sql, MapSqlParameterSource())
    private fun long(sql: String) = jdbc.queryForObject(sql, MapSqlParameterSource(), Long::class.java)!!
    private fun str(sql: String) = jdbc.queryForObject(sql, MapSqlParameterSource(), String::class.java)
    private fun auditRemarks() = mockingDetails(audit).invocations.filter { it.method.name == "record" }.map { it.arguments[6] as String? }

    private fun req(name: String = "ZT P1 조건", channels: List<String> = listOf("MAIL"), groupIds: List<Int> = listOf(11)) =
        AlertConditionRequest(name = name, severity = "WARN", threshold = "10", channels = channels, groupIds = groupIds)

    @Test
    @DisplayName("ALC-12 코드·임계·길이 검증 — 첫 오류 field·문구, 아무것도 저장하지 않음")
    fun validation() {
        fun field(r: AlertConditionRequest) = assertThrows(InvalidParameterException::class.java) { service.createCondition(r) }
        assertEquals("알 수 없는 심각도 코드입니다. [XXX]", field(req().copy(severity = "XXX")).message)
        assertEquals("op", field(req().copy(op = "NE")).field)
        assertEquals("duration", field(req().copy(duration = "X")).field)
        assertEquals("validWindow", field(req().copy(validWindow = "X")).field)
        assertEquals("dedupMin", field(req().copy(dedupMin = "X")).field)
        assertEquals("thresholdVal", field(req().copy(threshold = "3.0 %")).field)
        assertEquals("thresholdVal", field(req().copy(threshold = null)).field)
        assertEquals("조건명은 100자까지 입력할 수 있습니다.", field(req(name = "가".repeat(101))).message)
        assertEquals("metricStdId", field(req().copy(metricStdId = 999999)).field)
        assertEquals("channels", field(req(channels = listOf("XX"))).field)
        exec("UPDATE ax.tb_alm_recip_group SET use_flg = 'N' WHERE group_id = 11")
        assertEquals("groupIds", field(req()).field)
        assertEquals(0L, long("SELECT count(*) FROM ax.tb_alm_cond WHERE cond_nm LIKE 'ZT P1%'"))
    }

    @Test
    @DisplayName("ALC-06·09 — 도달(reach), 고급 설정은 받고 버림(2026-10-03), ONCE 는 400, 지표 민감 항목 복사, 경고 없음")
    fun advancedAndReach() {
        val r = service.createCondition(req(channels = listOf("POPUP"), groupIds = listOf(11)))
        @Suppress("UNCHECKED_CAST")
        val reach = r["reach"] as Map<String, Any?>
        assertEquals(0, reach["receivingCnt"], "그룹 11 은 메일만 받는다")
        @Suppress("UNCHECKED_CAST")
        assertEquals(emptyList<String>(), (reach["byGroup"] as List<Map<String, Any?>>).single()["channelMatch"])

        val once = assertThrows(InvalidParameterException::class.java) {
            service.createCondition(req(name = "ZT P1 once").copy(validWindow = "ONCE", windowTime = "07:30"))
        }
        assertEquals("validWindow", once.field); assertEquals(AlertConfigService.MSG_ONCE_REMOVED, once.message)

        // 고급 설정 7가지를 옛 화면처럼 보내도(엉뚱한 값까지) 400 없이 저장되고 값은 버린다
        exec("UPDATE ax.tb_met_metric_std SET blind_field_key = 'price' WHERE metric_id = 3")
        val adv = service.createCondition(req(name = "ZT P1 adv").copy(
            metricStdId = 3, scopeDim = "X", windowTime = "99:99", evalIntervalSec = 61,
            ignoreWindow = true, autoClose = true, escalation = listOf(CondEscalationInput(9, true)), msgTemplate = "{{condNm}} {{nope}}"
        ))
        val id = adv["condId"] as Int
        assertEquals("price", str("SELECT blind_field_key FROM ax.tb_alm_cond WHERE cond_id = $id"))
        if (long("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'ax' AND table_name = 'tb_alm_cond_escalation'") > 0) {
            assertEquals(0L, long("SELECT count(*) FROM ax.tb_alm_cond_escalation WHERE cond_id = $id"), "조건별 승격을 쓰지 않는다")
        }
        assertEquals(emptyList<String>(), adv["warnings"])
        val detail = service.getCondition(id)
        listOf("scopeDim", "evalIntervalSec", "windowTime", "ignoreWindow", "autoClose", "msgTemplate", "escalation").forEach {
            assertFalse(detail.containsKey(it), it)
        }
        // 수정도 받고 버린다 · ONCE 로 바꾸면 400
        service.updateCondition(id, AlertConditionUpdateRequest(scopeDim = "X", evalIntervalSec = 7, msgTemplate = "x", windowTime = java.util.Optional.of("bad")))
        assertEquals("validWindow", assertThrows(InvalidParameterException::class.java) {
            service.updateCondition(id, AlertConditionUpdateRequest(validWindow = "ONCE"))
        }.field)

        // ALC-14 — 임계만 바꾸면 감사 remark 는 「임계 10 → 12」 하나
        org.mockito.Mockito.clearInvocations(audit)
        service.updateCondition(id, AlertConditionUpdateRequest(thresholdVal = java.math.BigDecimal("12")))
        assertEquals(listOf("임계 10 → 12"), auditRemarks())
        org.mockito.Mockito.clearInvocations(audit)
        service.updateCondition(id, AlertConditionUpdateRequest(name = "ZT P1 adv"))
        assertTrue(auditRemarks().isEmpty(), "바뀐 것이 없으면 감사 없음")
    }

    @Test
    @DisplayName("ALC-08·11·17 목록 — 판정·수집 중단·삭제 가능, 검색·그룹 필터·심각도 정렬, 민감 임계값 가림")
    fun list() {
        val (rows, _, _) = service.getConditions(null, null, null, 1, 0)
        assertTrue(rows.all { "evalState" in it && "metricStale" in it && "receivingCnt" in it && "deletable" in it && "alert7dCnt" in it })
        val order = rows.map { mapOf("CRIT" to 1, "WARN" to 2).getOrDefault(it["severity"] as String, 3) }
        assertEquals(order.sorted(), order)
        val (byGroup, meta, _) = service.getConditions(null, null, null, 1, 0, null, 15)
        assertEquals(long("SELECT count(DISTINCT cond_id) FROM ax.tb_alm_cond_group WHERE group_id = 15"), meta.total)
        assertTrue(byGroup.isNotEmpty())
        // 와일드카드는 글자 그대로 찾는다
        assertEquals(long("SELECT count(*) FROM ax.tb_alm_cond c LEFT JOIN ax.tb_met_metric_std ms ON ms.metric_id = c.metric_id WHERE (strpos(c.cond_nm, '%') > 0 OR strpos(coalesce(ms.metric_nm, ''), '%') > 0 OR strpos(coalesce(c.metric_desc, ''), '%') > 0)"),
            service.getConditions(null, null, null, 1, 0, "%", null).second.total)
        assertEquals("keyword", assertThrows(InvalidParameterException::class.java) {
            service.getConditions(null, null, null, 1, 10, "가".repeat(51), null)
        }.field)

        val condId = (byGroup.first()["condId"] as Int)
        exec("UPDATE ax.tb_alm_cond SET blind_field_key = 'price' WHERE cond_id = $condId")
        UserContext.set(UserPrincipal("10001", "품질", 2, "품질보증팀", null, null, false, menuPerms = setOf(MenuId.ALERT_COND)))
        val (masked, _, keys) = service.getConditions(null, null, null, 1, 0)
        val row = masked.single { it["condId"] == condId }
        assertNull(row["thresholdVal"]); assertNull(row["threshold"])
        assertEquals(listOf("price"), keys)
        assertTrue(masked.all { it["deletable"] == false }, "비통합관리자는 삭제 불가")
    }

    @Test
    @DisplayName("ALC-07 요약 — SENT 만 발송 수, 억제·건너뜀·실패 따로, 판정 이상 수, 엔진 상태 / ALC-10 알림 지표만")
    fun summaryAndMetrics() {
        val s = service.getConditionSummary()
        assertEquals(s["todaySuppressedCnt"], s["dedupCnt"])
        assertTrue("todayFailCnt" in s && "evalIssueCnt" in s)
        @Suppress("UNCHECKED_CAST")
        assertTrue((s["engine"] as Map<String, Any?>)["judge"] in setOf("OK", "STOPPED", "UNKNOWN"))
        assertEquals("STOPPED", AlertEngineMonitor.judgeOf(3901)); assertEquals("OK", AlertEngineMonitor.judgeOf(10)); assertEquals("UNKNOWN", AlertEngineMonitor.judgeOf(null))

        val (items, _) = metrics.getStandards(null, null, null, 1, 200, alertOnly = true)
        assertEquals(long("SELECT count(*) FROM ax.tb_met_metric_std WHERE use_flg = 'Y' AND apply_alert"), items.size.toLong())
        assertTrue(items.all { "collecting" in it && "lastValueAt" in it && "unitNm" in it })
    }

    @Test
    @DisplayName("RCP-05·06·12 — 목록 필터, 미배정 등록 409, 부재·야간 키 없음(V74), 연락처 \"\" 지우기·형식")
    fun recipients() {
        val (byGroup, meta, _) = service.getRecipients(1, 0, groupId = 11)
        assertEquals(long("SELECT count(*) FROM ax.tb_alm_recip_group_member WHERE group_id = 11"), meta.total)
        assertTrue(byGroup.isNotEmpty())
        assertTrue(byGroup.all { "state" !in it && "stateNm" !in it && "night" !in it && "remark" in it })
        assertEquals("userState", assertThrows(InvalidParameterException::class.java) { service.getRecipients(1, 10, userState = "X") }.field)

        val unassigned = str("SELECT u.user_id FROM ax.tb_sys_user u JOIN ax.tb_sys_dept d ON d.dept_id = u.dept_id WHERE d.dept_nm = '미배정' AND NOT EXISTS (SELECT 1 FROM ax.tb_alm_recipient r WHERE r.user_id = u.user_id) LIMIT 1")
        assertEquals("empNo", assertThrows(ConflictingValueException::class.java) {
            service.createRecipient(RecipientRequest(empNo = unassigned, mail = "x@dwje.co.kr"))
        }.field)

        // 옛 화면이 night · state · reason 을 함께 보내도 연락처만 바꾸고 나머지는 버린다
        @Suppress("DEPRECATION")
        assertEquals(true, service.updateRecipient("10003", RecipientRequest(night = true, state = "ABSENT", reason = "x"))["success"])

        exec("UPDATE ax.tb_alm_recipient SET mobile_no = '010-1111-2222' WHERE user_id = '10003'")
        service.updateRecipient("10003", RecipientRequest(hp = ""))
        assertNull(jdbc.queryForObject("SELECT mobile_no FROM ax.tb_alm_recipient WHERE user_id = '10003'", MapSqlParameterSource(), String::class.java))
        assertEquals("hp", assertThrows(InvalidParameterException::class.java) { service.updateRecipient("10003", RecipientRequest(hp = "abc")) }.field)
        // 메일은 계정 메일이 기준이라 수정 요청의 mail 은 형식과 무관하게 무시한다(2026-10-03)
        val copyBefore = str("SELECT email FROM ax.tb_alm_recipient WHERE user_id = '10003'")
        @Suppress("DEPRECATION")
        assertEquals(true, service.updateRecipient("10003", RecipientRequest(mail = "a@b"))["success"])
        assertEquals(copyBefore, str("SELECT email FROM ax.tb_alm_recipient WHERE user_id = '10003'"))
        assertFalse(auditRemarks().any { it?.contains("메일") == true }, "메일은 바뀌지 않으므로 감사에 메일 변경이 없다")
        assertFalse(auditRemarks().any { it?.contains("@") == true || it?.contains("010") == true }, "감사에 연락처 값이 없다")
    }

    @Test
    @DisplayName("계정 메일 단일 기준(2026-10-03) — 계정 메일을 바꾸면 수신자 목록 mail·테스트 발송 대상 주소가 따라오고, 비었을 때만 수신자 사본")
    fun accountEmailIsSourceOfTruth() {
        val acct = "zt-acct-10003@wonderslab.test"
        exec("UPDATE ax.tb_alm_recipient SET email = 'zt-stale-10003@dwje.test' WHERE user_id = '10003'")
        exec("UPDATE ax.tb_sys_user SET email = '$acct', user_state_cd = 'ACTIVE' WHERE user_id = '10003'")

        fun listedMail() = service.getRecipients(1, 500).first.single { it["empNo"] == "10003" }["mail"]
        assertEquals(acct, listedMail())

        // 그룹 11(MAIL) 테스트 발송 — 대기열 주소가 계정 메일
        val (data, _) = service.testSendGroup(11)
        val alertId = (data["alertId"] as Number).toLong()
        assertEquals(acct, str("SELECT dest_addr FROM ax.tb_alm_send_queue WHERE alert_id = $alertId AND user_id = '10003' AND channel_cd = 'MAIL'"))

        // 계정 메일이 비면 수신자 행의 사본으로 내려간다 (수신 가능 판정도 그 사본 기준)
        exec("UPDATE ax.tb_sys_user SET email = NULL WHERE user_id = '10003'")
        assertEquals("zt-stale-10003@dwje.test", listedMail())
        exec("UPDATE ax.tb_sys_user SET email = '' WHERE user_id = '10003'")
        assertEquals("zt-stale-10003@dwje.test", listedMail())

        // 등록 — mail 없이도 계정 메일로 사본을 채운다. 계정 메일이 있으면 보낸 mail 은 무시한다
        exec("DELETE FROM ax.tb_alm_recipient WHERE user_id = '10003'")
        exec("UPDATE ax.tb_sys_user SET email = '$acct' WHERE user_id = '10003'")
        @Suppress("DEPRECATION")
        service.createRecipient(RecipientRequest(empNo = "10003", mail = "ignored@dwje.test"))
        assertEquals(acct, str("SELECT email FROM ax.tb_alm_recipient WHERE user_id = '10003'"))

        // 계정 메일이 없으면 보낸 mail 을 대체 주소로 쓰고, 그것도 없으면 400(mail)
        exec("DELETE FROM ax.tb_alm_recipient WHERE user_id = '10003'")
        exec("UPDATE ax.tb_sys_user SET email = NULL WHERE user_id = '10003'")
        assertEquals("mail", assertThrows(InvalidParameterException::class.java) { service.createRecipient(RecipientRequest(empNo = "10003")) }.field)
        @Suppress("DEPRECATION")
        service.createRecipient(RecipientRequest(empNo = "10003", mail = "fallback@dwje.test"))
        assertEquals("fallback@dwje.test", str("SELECT email FROM ax.tb_alm_recipient WHERE user_id = '10003'"))
    }

    @Test
    @DisplayName("RCP-08·09·12 — 영향 조회·삭제 409/force, 그룹 사용 중지 409, 그룹 목록 condCnt·receivableCnt, 그룹명 중복 문구")
    fun impactAndGroups() {
        @Suppress("UNCHECKED_CAST")
        val groups = service.getRecipientGroups().first["items"] as List<Map<String, Any?>>
        val g15 = groups.single { it["groupId"] == 15 }
        assertEquals(long("SELECT count(*) FROM ax.tb_alm_cond_group cg JOIN ax.tb_alm_cond c USING (cond_id) WHERE cg.group_id = 15 AND c.use_flg = 'Y'"), (g15["condCnt"] as Int).toLong())
        assertEquals(g15["receivingCnt"], g15["receivableCnt"])

        // 그룹 15 의 유일한 멤버(10000)를 빼면 받는 사람이 없어진다
        val impact = service.getRecipientImpact("10000")
        @Suppress("UNCHECKED_CAST")
        assertTrue((impact["zeroGroups"] as List<Map<String, Any?>>).any { it["groupId"] == 15 })
        val e = assertThrows(BusinessException::class.java) { service.deleteRecipient("10000", false) }
        assertEquals("E-RULE-001", e.errorCode.code)
        service.deleteRecipient("10000", true)
        assertEquals(0L, long("SELECT count(*) FROM ax.tb_alm_recip_group_member WHERE user_id = '10000'"))

        assertThrows(BusinessException::class.java) { service.changeRecipientGroupState(15, false) }
        assertEquals("on", assertThrows(InvalidParameterException::class.java) { service.changeRecipientGroupState(10, null) }.field)
        service.changeRecipientGroupState(10, false)
        assertEquals("N", str("SELECT use_flg FROM ax.tb_alm_recip_group WHERE group_id = 10"))

        assertEquals("이미 등록된 수신 그룹명입니다. [엔진 가동]", assertThrows(com.dwje.api.common.exception.DuplicatedValueException::class.java) {
            service.createRecipientGroup(RecipientGroupRequest(name = "엔진 가동", channels = listOf("MAIL")))
        }.message)
        assertEquals("channels", assertThrows(InvalidParameterException::class.java) {
            service.createRecipientGroup(RecipientGroupRequest(name = "ZT 그룹", channels = emptyList()))
        }.field)
        assertEquals("validWindow", assertThrows(InvalidParameterException::class.java) {
            service.createRecipientGroup(RecipientGroupRequest(name = "ZT 그룹", channels = listOf("MAIL"), validWindow = "X"))
        }.field)
        @Suppress("UNCHECKED_CAST")
        val summary = service.getRecipientSummary()
        assertTrue(listOf("nightWindow", "nightPersonalCnt", "nightCnt").none { it in summary }, "야간 수신 제거(V74)")
        assertEquals(long("SELECT count(*) FROM ax.tb_alm_recipient"), summary["recipientCnt"])
        assertEquals(summary["recipientCnt"], (summary["receivableCnt"] as Long) + (summary["inactiveAccountCnt"] as Long))
        assertFalse("escNoTargetCnt" in summary, "승격 규칙 제거(2026-10-03) — 대상 그룹 없는 승격 단계 수는 보내지 않는다")
    }

    @Test
    @DisplayName("메인 10-13 — 알림 목록 condId 필터, alertId 는 기간·테스트 여부와 무관하게 그 한 건")
    fun alertListFilters() {
        val old = long("SELECT min(alert_id) FROM ax.tb_alm_alert")
        exec("UPDATE ax.tb_alm_alert SET occurred_at = now() - interval '200 days', test_flg = 'Y' WHERE alert_id = $old")
        val (one, meta) = alerts.getAlerts(null, null, "today", null, 1, 10, alertId = old)
        assertEquals(1L, meta.total)
        assertEquals(old, one.single()["alertId"])

        val condId = long("SELECT cond_id FROM ax.tb_alm_alert WHERE cond_id IS NOT NULL AND test_flg = 'N' GROUP BY cond_id ORDER BY count(*) DESC LIMIT 1").toInt()
        val (_, byCond) = alerts.getAlerts(null, null, "30d", null, 1, 10, condId = condId)
        assertEquals(long("SELECT count(*) FROM ax.tb_alm_alert WHERE cond_id = $condId AND test_flg = 'N' AND occurred_at >= current_date - 29 AND (plant_cd IS NULL OR plant_cd = 'PL01')"), byCond.total)
    }
}
