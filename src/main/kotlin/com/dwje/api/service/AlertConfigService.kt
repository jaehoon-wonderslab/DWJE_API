package com.dwje.api.service

import com.dwje.api.common.exception.BusinessRuleException
import com.dwje.api.common.exception.DuplicatedValueException
import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.exception.ResourceNotFoundException
import com.dwje.api.common.response.PageMeta
import com.dwje.api.common.util.DateUtils
import com.dwje.api.common.util.MenuId
import com.dwje.api.common.util.PageRequestParam
import com.dwje.api.model.request.AlertConditionRequest
import com.dwje.api.model.request.DutyRequest
import com.dwje.api.model.request.EscalationRuleRequest
import com.dwje.api.model.request.RecipientGroupRequest
import com.dwje.api.model.request.RecipientRequest
import com.dwje.api.repository.AlertConfigRepository
import com.dwje.api.repository.AlertRepository
import com.dwje.api.repository.SystemUserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * 이상 알림 발송 조건 · 수신자 관리 서비스 (SY-04, SY-05)
 *
 * 접근 부서 : 전산팀 · 통합관리자
 * 조건 변경은 감사 로그에 기록된다. (공통 규약 6 — 알림 조건)
 */
@Service
class AlertConfigService(
    private val alertConfigRepository: AlertConfigRepository,
    private val alertRepository: AlertRepository,
    private val systemUserRepository: SystemUserRepository,
    private val authorizationService: AuthorizationService,
    private val auditLogService: AuditLogService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    // =================================================================================
    // SY-04. 발송 조건 관리
    // =================================================================================

    /** 발송 조건 요약 (No.151) */
    @Transactional(readOnly = true)
    fun getConditionSummary(): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.ALERT_COND)
        return alertConfigRepository.findConditionSummary() + alertConfigRepository.findTodaySendStats()
    }

    /** 발송 조건 목록 조회 (No.152) */
    @Transactional(readOnly = true)
    fun getConditions(
        severity: String?,
        channel: String?,
        state: String?,
        page: Int?,
        size: Int?
    ): Pair<List<Map<String, Any?>>, PageMeta> {
        authorizationService.requireMenu(MenuId.ALERT_COND)
        val paging = PageRequestParam.of(page, size)

        val total = alertConfigRepository.countConditions(severity, channel, state)
        val rows = alertConfigRepository.findConditions(severity, channel, state, paging.limit, paging.offset)

        return rows to PageMeta.of(paging.page, paging.size, total)
    }

    /** 발송 조건 등록 (No.153) */
    @Transactional
    fun createCondition(request: AlertConditionRequest): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.ALERT_COND)

        if (alertConfigRepository.existsConditionName(request.name, null)) {
            throw DuplicatedValueException("이미 등록된 조건명입니다. [${request.name}]", "name")
        }

        val condId = alertConfigRepository.insertCondition(
            condNm = request.name,
            severityCd = request.severity,
            metricId = request.metricStdId,
            metricDesc = request.metricDesc ?: request.name,
            opCd = request.op,
            thresholdVal = request.threshold,
            thresholdText = request.thresholdText ?: request.threshold?.toPlainString() ?: "-",
            thresholdUnit = request.thresholdUnit,
            durationCd = request.duration,
            targetScopeCd = request.targetScope,
            targetDesc = request.target ?: "전체",
            windowCd = request.validWindow,
            dedupCd = request.dedupMin,
            msgTemplate = request.msgTemplate ?: defaultMessageTemplate(),
            actor = principal.userId
        )

        alertConfigRepository.replaceConditionChannels(condId, request.channels)
        alertConfigRepository.replaceConditionGroups(condId, request.groupIds)

        auditLogService.record(
            logType = "AUTO_GEN",
            menuId = MenuId.ALERT_COND,
            targetDesc = "알림 발송 조건 등록 [${request.name}]",
            remark = "심각도=${request.severity}, 임계=${request.threshold}, 채널=${request.channels.joinToString(",")}"
        )

        log.info("알림 발송 조건 등록 : condId={} name={}", condId, request.name)
        return mapOf("condId" to condId)
    }

    /** 발송 조건 수정 (No.154) */
    @Transactional
    fun updateCondition(condId: Int, request: AlertConditionRequest): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.ALERT_COND)

        val before = alertConfigRepository.findCondition(condId)
            ?: throw ResourceNotFoundException("발송 조건을 찾을 수 없습니다. [condId=$condId]")

        if (alertConfigRepository.existsConditionName(request.name, condId)) {
            throw DuplicatedValueException("이미 등록된 조건명입니다. [${request.name}]", "name")
        }

        alertConfigRepository.updateCondition(
            condId = condId,
            condNm = request.name,
            severityCd = request.severity,
            metricId = request.metricStdId,
            metricDesc = request.metricDesc ?: request.name,
            opCd = request.op,
            thresholdVal = request.threshold,
            thresholdText = request.thresholdText ?: request.threshold?.toPlainString() ?: "-",
            thresholdUnit = request.thresholdUnit,
            durationCd = request.duration,
            targetScopeCd = request.targetScope,
            targetDesc = request.target ?: "전체",
            windowCd = request.validWindow,
            dedupCd = request.dedupMin,
            msgTemplate = request.msgTemplate ?: defaultMessageTemplate(),
            actor = principal.userId
        )

        if (request.channels.isNotEmpty()) alertConfigRepository.replaceConditionChannels(condId, request.channels)
        if (request.groupIds.isNotEmpty()) alertConfigRepository.replaceConditionGroups(condId, request.groupIds)

        auditLogService.record(
            logType = "AUTO_GEN",
            menuId = MenuId.ALERT_COND,
            targetDesc = "알림 발송 조건 수정 [${request.name}]",
            remark = "이전 심각도=${before["severity"]} → ${request.severity}"
        )

        return mapOf("success" to true, "condId" to condId)
    }

    /** 발송 조건 활성/중지 (No.155) */
    @Transactional
    fun changeConditionState(condId: Int, on: Boolean): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.ALERT_COND)

        val cond = alertConfigRepository.findCondition(condId)
            ?: throw ResourceNotFoundException("발송 조건을 찾을 수 없습니다. [condId=$condId]")

        alertConfigRepository.updateConditionState(condId, on, principal.userId)

        auditLogService.record(
            logType = "AUTO_GEN",
            menuId = MenuId.ALERT_COND,
            targetDesc = "알림 조건 ${if (on) "활성" else "중지"} [${cond["name"]}]",
            remark = "condId=$condId"
        )

        return mapOf("success" to true, "on" to on)
    }

    /**
     * 발송 조건 삭제
     *
     * 중지(`state`)는 조건을 남겨 두고 끄는 것이고, 삭제는 잘못 만든 조건을 없애는 것이다.
     * 이미 알림이 발생한 조건은 지우지 않는다 — 지난 알림의 근거가 사라지기 때문이다.
     * 그 경우에는 중지를 쓰도록 안내한다.
     */
    @Transactional
    fun deleteCondition(condId: Int): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.ALERT_COND)

        val cond = alertConfigRepository.findCondition(condId)
            ?: throw ResourceNotFoundException("발송 조건을 찾을 수 없습니다. [condId=$condId]")

        val alertCnt = alertConfigRepository.countAlertsByCondition(condId)
        if (alertCnt > 0) {
            throw BusinessRuleException(
                "이미 알림이 발생한 조건은 삭제할 수 없습니다. [발생 ${alertCnt}건] " +
                    "지난 알림의 근거가 남아야 하므로 삭제 대신 중지를 사용하세요."
            )
        }

        alertConfigRepository.deleteCondition(condId)

        auditLogService.record(
            logType = "AUTO_GEN",
            menuId = MenuId.ALERT_COND,
            targetDesc = "알림 조건 삭제 [${cond["name"]}]",
            remark = "condId=$condId by=${principal.userId}"
        )

        return mapOf("success" to true)
    }

    /**
     * 발송 조건 테스트 (No.156)
     *
     * 실제 임계 초과와 무관하게 조건에 연결된 수신 그룹에 테스트 알림을 발송한다.
     */
    @Transactional
    fun testSendCondition(condId: Int): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.ALERT_COND)

        val cond = alertConfigRepository.findCondition(condId)
            ?: throw ResourceNotFoundException("발송 조건을 찾을 수 없습니다. [condId=$condId]")

        val channels = alertConfigRepository.findConditionChannels(condId).ifEmpty { listOf("MAIL") }
        val groupIds = alertConfigRepository.findConditionGroupIds(condId)

        val alertId = alertRepository.insertTestAlert(
            condId = condId,
            severityCd = (cond["severity"] as? String) ?: "LOW",
            title = "[테스트] ${cond["name"]}",
            targetDesc = cond["target"] as? String
        )

        val recipients = mutableListOf<Map<String, Any?>>()
        var sentCnt = 0

        groupIds.forEach { groupId ->
            alertConfigRepository.findGroupRecipients(groupId).forEach { r ->
                channels.forEach { channel ->
                    alertRepository.insertSendLog(
                        alertId = alertId,
                        groupId = groupId,
                        userId = r["empNo"] as String?,
                        channelCd = channel,
                        destAddr = destinationOf(channel, r),
                        resultCd = "SENT",
                        failReason = null,
                        escLevel = 0
                    )
                    sentCnt++
                }
                recipients.add(r)
            }
        }

        return mapOf("sentCnt" to sentCnt, "recipients" to recipients, "channels" to channels)
    }

    // =================================================================================
    // SY-05. 수신자 관리
    // =================================================================================

    /** 수신자 관리 요약 (No.157) */
    @Transactional(readOnly = true)
    fun getRecipientSummary(): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.SYS_RECIP)
        return alertConfigRepository.findRecipientSummary()
    }

    /** 수신 그룹 목록 (No.158) */
    @Transactional(readOnly = true)
    fun getRecipientGroups(): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.SYS_RECIP)
        return mapOf("items" to alertConfigRepository.findRecipientGroups())
    }

    /** 수신 그룹 등록 (No.159) */
    @Transactional
    fun createRecipientGroup(request: RecipientGroupRequest): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.SYS_RECIP)

        val groupId = alertConfigRepository.insertRecipientGroup(
            request.name, request.validWindow, request.night, request.deptId, principal.userId
        )
        alertConfigRepository.replaceGroupChannels(groupId, request.channels)
        alertConfigRepository.replaceGroupMembers(groupId, request.memberEmpNos, principal.userId)

        return mapOf("groupId" to groupId)
    }

    /** 수신 그룹 수정 (No.160) */
    @Transactional
    fun updateRecipientGroup(groupId: Int, request: RecipientGroupRequest): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.SYS_RECIP)
        requireGroup(groupId)

        alertConfigRepository.updateRecipientGroup(
            groupId, request.name, request.validWindow, request.night, request.deptId, principal.userId
        )
        if (request.channels.isNotEmpty()) alertConfigRepository.replaceGroupChannels(groupId, request.channels)
        if (request.memberEmpNos.isNotEmpty()) {
            alertConfigRepository.replaceGroupMembers(groupId, request.memberEmpNos, principal.userId)
        }

        return mapOf("success" to true)
    }

    /** 수신 그룹 테스트 발송 (No.161) */
    @Transactional
    fun testSendGroup(groupId: Int): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.SYS_RECIP)
        requireGroup(groupId)

        val alertId = alertRepository.insertTestAlert(
            condId = null, severityCd = "LOW", title = "[테스트] 수신 그룹 발송 확인", targetDesc = "groupId=$groupId"
        )

        var sentCnt = 0
        alertConfigRepository.findGroupRecipients(groupId).forEach { r ->
            alertRepository.insertSendLog(
                alertId = alertId,
                groupId = groupId,
                userId = r["empNo"] as String?,
                channelCd = "MAIL",
                destAddr = r["mail"] as String?,
                resultCd = "SENT",
                failReason = null,
                escLevel = 0
            )
            sentCnt++
        }

        return mapOf("sentCnt" to sentCnt)
    }

    /** 수신자 목록 (No.162) */
    @Transactional(readOnly = true)
    fun getRecipients(state: String?, page: Int?, size: Int?): Pair<List<Map<String, Any?>>, PageMeta> {
        authorizationService.requireMenu(MenuId.SYS_RECIP)
        val paging = PageRequestParam.of(page, size)

        val total = alertConfigRepository.countRecipients(state)
        val rows = alertConfigRepository.findRecipients(state, paging.limit, paging.offset)

        return rows to PageMeta.of(paging.page, paging.size, total)
    }

    /** 수신자 등록 (No.163) */
    @Transactional
    fun createRecipient(request: RecipientRequest): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.SYS_RECIP)

        val empNo = request.empNo?.trim()
            ?: throw InvalidParameterException("사번을 입력해 주세요.", "empNo")
        val mail = request.mail?.trim()
            ?: throw InvalidParameterException("메일 주소를 입력해 주세요.", "mail")

        if (!systemUserRepository.existsUser(empNo)) {
            throw ResourceNotFoundException("계정을 찾을 수 없습니다. [$empNo]")
        }
        if (alertConfigRepository.existsRecipient(empNo)) {
            throw DuplicatedValueException("이미 등록된 수신자입니다. [$empNo]", "empNo")
        }
        if (!mail.contains("@")) {
            throw InvalidParameterException("메일 주소 형식이 올바르지 않습니다.", "mail")
        }

        alertConfigRepository.insertRecipient(
            empNo, mail, request.hp, request.messenger, request.night ?: false, principal.userId
        )

        return mapOf("recipientId" to empNo)
    }

    /** 수신자 수정 (No.164) */
    @Transactional
    fun updateRecipient(recipientId: String, request: RecipientRequest): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.SYS_RECIP)

        if (!alertConfigRepository.existsRecipient(recipientId)) {
            throw ResourceNotFoundException("수신자를 찾을 수 없습니다. [$recipientId]")
        }
        request.mail?.let {
            if (!it.contains("@")) throw InvalidParameterException("메일 주소 형식이 올바르지 않습니다.", "mail")
        }

        alertConfigRepository.updateRecipient(
            recipientId, request.mail?.trim(), request.hp, request.messenger, request.night, principal.userId
        )

        return mapOf("success" to true)
    }

    /** 수신/부재 토글 (No.165) */
    @Transactional
    fun changeRecipientState(recipientId: String, state: String): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.SYS_RECIP)

        if (!alertConfigRepository.existsRecipient(recipientId)) {
            throw ResourceNotFoundException("수신자를 찾을 수 없습니다. [$recipientId]")
        }

        alertConfigRepository.updateRecipientState(recipientId, state, principal.userId)
        return mapOf("success" to true, "state" to alertConfigRepository.normalizeRecvState(state))
    }

    /** 당번·대리 목록 (No.166) */
    @Transactional(readOnly = true)
    fun getDuties(from: String?, to: String?, groupId: Int?): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.SYS_RECIP)
        val (fromDate, toDate) = DateUtils.periodOf(from, to, 30)
        return mapOf("items" to alertConfigRepository.findDuties(fromDate, toDate, groupId))
    }

    /** 당번 등록 (No.167) */
    @Transactional
    fun createDuty(request: DutyRequest): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.SYS_RECIP)

        val groupId = request.groupId
            ?: throw InvalidParameterException("수신 그룹을 선택해 주세요.", "groupId")
        val mainEmpNo = request.mainEmpNo
            ?: throw InvalidParameterException("주 담당자를 선택해 주세요.", "mainEmpNo")
        val subEmpNo = request.subEmpNo
            ?: throw InvalidParameterException("대리 담당자를 선택해 주세요.", "subEmpNo")

        // 주 담당자와 대리 담당자는 같을 수 없다. (DDL CHECK 제약과 동일)
        if (mainEmpNo == subEmpNo) {
            throw InvalidParameterException("주 담당자와 대리 담당자는 같을 수 없습니다.", "subEmpNo")
        }

        val fromDate = DateUtils.parseDate(request.from, "from", LocalDate.now())
        val toDate = DateUtils.parseDate(request.to, "to", fromDate.plusDays(6))
        if (toDate.isBefore(fromDate)) {
            throw InvalidParameterException("종료일이 시작일보다 빠릅니다.", "to")
        }

        val dutyId = alertConfigRepository.insertDuty(
            groupId, fromDate, toDate, mainEmpNo, subEmpNo, request.reason ?: "ROTATION",
            request.remark, principal.userId
        )

        return mapOf("dutyId" to dutyId)
    }

    /** 당번 수정 (No.168) */
    @Transactional
    fun updateDuty(dutyId: Int, request: DutyRequest): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.SYS_RECIP)

        val updated = alertConfigRepository.updateDuty(
            dutyId,
            request.from?.let { DateUtils.parseDate(it, "from") },
            request.to?.let { DateUtils.parseDate(it, "to") },
            request.mainEmpNo,
            request.subEmpNo,
            request.reason,
            request.remark
        )
        if (updated == 0) throw ResourceNotFoundException("당번 정보를 찾을 수 없습니다. [dutyId=$dutyId]")

        return mapOf("success" to true)
    }

    /** 당번 삭제 (No.168) */
    @Transactional
    fun deleteDuty(dutyId: Int): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.SYS_RECIP)

        val deleted = alertConfigRepository.deleteDuty(dutyId)
        if (deleted == 0) throw ResourceNotFoundException("당번 정보를 찾을 수 없습니다. [dutyId=$dutyId]")

        return mapOf("success" to true)
    }

    /** 승격 규칙 조회 (No.169) */
    @Transactional(readOnly = true)
    fun getEscalationRules(): Map<String, Any?> {
        authorizationService.requireAnyMenu(MenuId.SYS_RECIP, MenuId.ALERT_COND)
        return mapOf("stages" to alertConfigRepository.findEscalationRules())
    }

    /** 승격 규칙 수정 (No.169) */
    @Transactional
    fun updateEscalationRules(request: EscalationRuleRequest): Map<String, Any?> {
        authorizationService.requireAnyMenu(MenuId.SYS_RECIP, MenuId.ALERT_COND)

        var updated = 0
        request.stages.forEach { stage ->
            updated += alertConfigRepository.updateEscalationRule(stage.stage, stage.waitMin, stage.targetGroupId)
        }

        auditLogService.record(
            logType = "AUTO_GEN",
            menuId = MenuId.SYS_RECIP,
            targetDesc = "알림 승격 규칙 수정",
            remark = "변경 ${updated}단계"
        )

        return mapOf("success" to true, "updatedCnt" to updated)
    }

    // ---------------------------------------------------------------------------------
    // 내부 보조
    // ---------------------------------------------------------------------------------

    /** 수신 그룹 존재 확인 */
    private fun requireGroup(groupId: Int) {
        if (!alertConfigRepository.existsGroup(groupId)) {
            throw ResourceNotFoundException("수신 그룹을 찾을 수 없습니다. [groupId=$groupId]")
        }
    }

    /**
     * 채널별 실제 발송 주소를 선택한다.
     */
    private fun destinationOf(channel: String, recipient: Map<String, Any?>): String? = when (channel.uppercase()) {
        "MAIL" -> recipient["mail"] as? String
        "SMS" -> recipient["hp"] as? String
        "MSG" -> recipient["messenger"] as? String
        else -> null
    }

    /**
     * 기본 메시지 템플릿 — 단가·수율 등 민감정보는 본문에 포함하지 않는다.
     */
    private fun defaultMessageTemplate(): String =
        "[{심각도}] {조건명} 발생 — 대상 {대상} / 지표 {지표} / 임계 {임계값}. 시스템에서 상세를 확인하세요."
}
