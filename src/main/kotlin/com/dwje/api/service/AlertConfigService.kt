package com.dwje.api.service

import com.dwje.api.common.util.AuditType
import com.dwje.api.common.exception.BusinessException
import com.dwje.api.common.response.ErrorCode
import com.dwje.api.common.exception.BusinessRuleException
import com.dwje.api.common.exception.ConflictingValueException
import com.dwje.api.common.exception.DuplicatedValueException
import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.exception.ResourceNotFoundException
import com.dwje.api.common.response.PageMeta
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.common.util.DataField
import com.dwje.api.common.util.MaskingSupport
import com.dwje.api.common.util.MenuId
import com.dwje.api.common.util.PageRequestParam
import com.dwje.api.common.validation.CodeValidator
import com.dwje.api.config.AppProperties
import com.dwje.api.model.request.AlertConditionRequest
import com.dwje.api.model.request.AlertConditionUpdateRequest
import com.dwje.api.model.request.EscalationRuleRequest
import com.dwje.api.model.request.RecipientGroupRequest
import com.dwje.api.model.request.RecipientGroupUpdateRequest
import com.dwje.api.model.request.RecipientRequest
import com.dwje.api.repository.AlertConfigRepository
import com.dwje.api.repository.AlertCondValues
import com.dwje.api.repository.CommonMasterRepository
import com.dwje.api.repository.SystemUserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

/**
 * 이상 알림 발송 조건 · 수신자 관리 서비스 (SY-04, SY-05)
 *
 * 접근 : 화면 권한 `alert-cond` · `sys-recip`(메서드별, ax.tb_sys_dept_menu_perm)
 * 값 마스킹 : 수신자 이름·연락처는 worker 데이터 권한이 없으면 null (06 RCP-01)
 * 조건 변경은 감사 로그에 기록된다. (공통 규약 6 — 알림 조건)
 */
@Service
class AlertConfigService(
    private val alertConfigRepository: AlertConfigRepository,
    private val systemUserRepository: SystemUserRepository,
    private val authorizationService: AuthorizationService,
    private val auditLogService: AuditLogService,
    private val alertTestSendService: AlertTestSendService,
    private val targetResolver: AlertTargetResolver,
    private val codeValidator: CodeValidator,
    private val appProperties: AppProperties,
    private val commonMasterRepository: CommonMasterRepository,
    private val engineMonitor: AlertEngineMonitor
) {

    private val log = LoggerFactory.getLogger(javaClass)

    // =================================================================================
    // SY-04. 발송 조건 관리
    // =================================================================================

    /** 발송 조건 요약 (No.151) */
    @Transactional(readOnly = true)
    fun getConditionSummary(): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.ALERT_COND)
        return alertConfigRepository.findConditionSummary() + alertConfigRepository.findTodaySendStats() + mapOf(
            // 판정 이상(위반 중·수집 중단) · 엔진 상태 (05 ALC-07)
            "evalIssueCnt" to alertConfigRepository.findEvalIssueCnt(appProperties.alert.staleFactor),
            "engine" to engineMonitor.state()
        )
    }

    /** 발송 조건 목록 조회 (No.152) */
    @Transactional(readOnly = true)
    fun getConditions(
        severity: String?,
        channel: String?,
        state: String?,
        page: Int?,
        size: Int?,
        keyword: String? = null,
        groupId: Int? = null
    ): Triple<List<Map<String, Any?>>, PageMeta, List<String>> {
        val principal = authorizationService.requireMenu(MenuId.ALERT_COND)
        // size=0 이면 전량 — 상한 [CONDITION_ALL_MAX] 건에서 자르고 meta.truncated
        val paging = PageRequestParam.ofAllowAll(page, size)
        val limit = paging.limitOrNull ?: CONDITION_ALL_MAX
        val kw = keyword?.trim()?.ifEmpty { null }?.also {
            if (it.length > 50) throw InvalidParameterException("검색어는 50자까지 입력할 수 있습니다.", "keyword")
        }
        severity?.trim()?.ifEmpty { null }?.let { requireCode("ALM_SEVERITY", it, "severity", "심각도") }
        channel?.trim()?.ifEmpty { null }?.let { requireCode("ALM_CHANNEL", it, "channel", "발송 채널") }

        val total = alertConfigRepository.countConditions(severity, channel, state, kw, groupId)
        val rows = alertConfigRepository.findConditions(severity, channel, state, limit, paging.offset, kw, groupId, appProperties.alert.staleFactor)
        val condGroups = alertConfigRepository.findConditionGroups(rows.map { it["condId"] as Int })
        val allGroupIds = condGroups.values.flatten().map { it["groupId"] as Int }.distinct()
        val counts = groupCounts(allGroupIds)
        val targetGroups = alertConfigRepository.findTargetGroups(allGroupIds).associateBy { it.groupId }
        val members = alertConfigRepository.findTargetMembers(allGroupIds).groupBy { it.groupId }
        val mask = authorizationService.masking()

        // groups 는 객체 배열(05 ALC-04). 이름 배열은 groupNames 로 한 릴리스 함께 보낸다.
        val items = rows.map { row ->
            val condId = row["condId"] as Int
            val gs = condGroups[condId].orEmpty()
            @Suppress("UNCHECKED_CAST")
            val reach = targetResolver.evaluate(gs.mapNotNull { targetGroups[it["groupId"] as Int] }, members,
                row["channels"] as List<String>, java.time.LocalTime.NOON, ignoreNight = true)
            val out = (row - "realAlertCnt").toMutableMap()
            out["groups"] = gs.map { it + counts.getValue(it["groupId"] as Int) }
            // 조건 단위 받는 사람 수(중복 제외, 야간 무관) · 삭제 가능(통합관리자 · 운영 알림 0건) (05 ALC-08)
            out["receivingCnt"] = reach.targets.map { it.userId }.distinct().size
            out["deletable"] = principal.superAdmin && (row["realAlertCnt"] as Long) == 0L
            // 운영 알림 수(테스트 제외) — 「운영 알림 N건이 있어 삭제할 수 없습니다」 툴팁 (05 ALC-13)
            out["alertCnt"] = row["realAlertCnt"]
            // 민감 항목이 걸린 조건의 임계값은 그 항목 권한이 없으면 가린다 (05 ALC-17)
            (row["blindFieldKey"] as String?)?.let { key ->
                if (mask.on(key) { true } == null) { out["thresholdVal"] = null; out["threshold"] = null }
            }
            out
        }
        val meta = if (paging.isAll) PageMeta.capped(total, CONDITION_ALL_MAX, items.size) else PageMeta.of(paging.page, paging.size, total)
        return Triple(items, meta, mask.maskedKeys())
    }

    /**
     * 발송 조건 등록 (No.153)
     *
     * 검증 순서(05 ALC-12) — 조건명·중복 → 코드 6종 → 지표 → 임계 → 대상 범위·설비·설명 → 채널 → 수신 그룹 →
     * 지정 시각·평가 주기·승격 → 메시지 틀 길이. 첫 오류에서 멈추고 아무것도 저장하지 않는다.
     * 지표의 민감 항목(blind_field_key)은 화면 입력 없이 지표에서 복사한다(ALC-09).
     */
    @Transactional
    fun createCondition(request: AlertConditionRequest): Map<String, Any?> {
        val principal = authorizationService.requireWrite(MenuId.ALERT_COND)

        val name = requireCondName(request.name, null)
        val metricBlind = request.metricStdId?.let { requireMetric(it) }
        val (thresholdVal, thresholdText) = requireThreshold(request.thresholdVal, request.threshold, request.thresholdText, null, null)
        val targetScope = requireTargetScope(request.targetScope)
        val v = AlertCondValues(
            name = name,
            severity = requireCode("ALM_SEVERITY", request.severity, "severity", "심각도"),
            metricId = request.metricStdId,
            metricDesc = requireLength(request.metricDesc?.trim()?.ifEmpty { null } ?: name, 100, "metricDesc", "지표 설명"),
            op = requireCode("ALM_OP", request.op, "op", "비교"),
            thresholdVal = thresholdVal,
            thresholdText = thresholdText,
            thresholdUnit = request.thresholdUnit?.trim()?.ifEmpty { null }?.let { requireLength(it, 20, "thresholdUnit", "임계 단위") },
            duration = requireCode("ALM_DURATION", request.duration, "duration", "지속 조건"),
            targetScope = targetScope,
            targetDesc = resolveTargetDesc(request.target, targetScope, currentDesc = null, currentScope = null),
            validWindow = requireCode("ALM_WINDOW", request.validWindow, "validWindow", "유효 시간대"),
            dedupMin = requireCode("ALM_DEDUP", request.dedupMin, "dedupMin", "중복 억제"),
            msgTemplate = request.msgTemplate?.takeIf { it.isNotBlank() } ?: defaultMessageTemplate(),
            scopeDim = requireCode("ALM_SCOPE_DIM", request.scopeDim, "scopeDim", "평가 단위"),
            windowTime = request.windowTime?.trim()?.ifEmpty { null }?.let { requireWindowTime(it) },
            evalIntervalSec = requireEvalInterval(request.evalIntervalSec),
            ignoreWindow = request.ignoreWindow,
            autoClose = request.autoClose,
            blindFieldKey = metricBlind?.second
        )
        // 코드 순서는 문서 표대로 — 위 생성자 인자 순서가 검사 순서다(심각도 → 비교 → 지속 → 유효 시간대 → 중복 억제 → 평가 단위)
        val picks = resolvePicks(targetScope, request.pickTargets, current = null)
        val channels = requireChannels(request.channels)
        val groupIds = requireGroupIds(request.groupIds)
        requireOnceTime(v.validWindow, v.windowTime)
        val escalation = request.escalation?.let { requireEscalation(it) }
        requireLength(v.msgTemplate, 4000, "msgTemplate", "메시지 틀")

        val condId = alertConfigRepository.insertCondition(v, principal.userId)
        alertConfigRepository.replaceConditionChannels(condId, channels)
        alertConfigRepository.replaceConditionGroups(condId, groupIds)
        alertConfigRepository.replaceConditionTargets(condId, picks)
        escalation?.let { alertConfigRepository.replaceConditionEscalation(condId, it) }

        auditLogService.record(
            logType = AuditType.CONFIG_CHANGE,
            menuId = MenuId.ALERT_COND,
            targetDesc = "알림 발송 조건 등록 [$name]",
            remark = ("심각도=${v.severity}, 지표=${v.metricId ?: "-"}, 비교·임계=${v.op} ${v.thresholdText}, " +
                "채널=${channels.joinToString(",")}, 그룹=$groupIds").take(500)
        )

        log.info("알림 발송 조건 등록 : condId={} name={}", condId, name)
        return mapOf(
            "condId" to condId,
            "reach" to targetResolver.reach(groupIds, channels),
            "warnings" to warningsOf(v.msgTemplate, escalation)
        )
    }

    /** 발송 조건 상세 (05 ALC-04) */
    @Transactional(readOnly = true)
    fun getCondition(condId: Int): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.ALERT_COND)
        val cond = alertConfigRepository.findConditionDetail(condId)
            ?: throw ResourceNotFoundException("발송 조건을 찾을 수 없습니다. [condId=$condId]")
        val groups = groupObjects(alertConfigRepository.findConditionGroups(listOf(condId))[condId].orEmpty())
        val detail = cond.toMutableMap()
        detail["thresholdVal"] = (cond["thresholdVal"] as? BigDecimal)?.stripTrailingZeros()
        detail["threshold"] = cond["thresholdText"]
        // 삭제 버튼 — 통합관리자이고 운영 알림이 한 건도 없을 때(R-13, 테스트 알림은 세지 않는다)
        val alertCnt = alertConfigRepository.countAlertsByCondition(condId)
        detail["alertCnt"] = alertCnt
        detail["deletable"] = com.dwje.api.common.security.UserContext.current().superAdmin && alertCnt == 0L
        detail["groupIds"] = groups.map { it["groupId"] }
        detail["groups"] = groups
        return detail
    }

    /**
     * 발송 조건 수정 (No.154, 05 ALC-04)
     *
     * 보낸 키만 바꾼다. 예전에는 빠진 키를 DTO 기본값(op=GE, 대상=전체 설비 등)으로 덮어 써서
     * 이름만 고쳐도 메시지 틀·대상 범위가 초기화됐다. 검증 순서는 등록과 같다(ALC-12).
     * 감사에는 바뀐 필드만 한국어 이름으로 「임계 10 → 12」 처럼 남긴다(ALC-14). 바뀐 것이 없으면 남기지 않는다.
     */
    @Transactional
    fun updateCondition(condId: Int, request: AlertConditionUpdateRequest): Map<String, Any?> {
        val principal = authorizationService.requireWrite(MenuId.ALERT_COND)

        val cur = alertConfigRepository.findConditionDetail(condId)
            ?: throw ResourceNotFoundException("발송 조건을 찾을 수 없습니다. [condId=$condId]")

        request.updatedAt?.trim()?.takeIf { it.isNotEmpty() }?.let {
            if (it != cur["updatedAt"]) {
                throw ConflictingValueException("다른 사용자가 먼저 수정했습니다. 다시 열어 확인해 주십시오.", "updatedAt")
            }
        }

        val name = request.name?.let { requireCondName(it, condId) } ?: cur["name"] as String
        val severity = request.severity?.let { requireCode("ALM_SEVERITY", it, "severity", "심각도") } ?: cur["severity"] as String
        val op = request.op?.let { requireCode("ALM_OP", it, "op", "비교") } ?: cur["op"] as String
        val duration = request.duration?.let { requireCode("ALM_DURATION", it, "duration", "지속 조건") } ?: cur["duration"] as String
        val validWindow = request.validWindow?.let { requireCode("ALM_WINDOW", it, "validWindow", "유효 시간대") } ?: cur["validWindow"] as String
        val dedupMin = request.dedupMin?.let { requireCode("ALM_DEDUP", it, "dedupMin", "중복 억제") } ?: cur["dedupMin"] as String
        val scopeDim = request.scopeDim?.let { requireCode("ALM_SCOPE_DIM", it, "scopeDim", "평가 단위") } ?: cur["scopeDim"] as String

        val curMetric = cur["metricStdId"] as Int?
        val metricId = request.metricStdId ?: curMetric
        // 지표를 바꿨을 때만 민감 항목을 지표에서 다시 복사한다(ALC-09 — 지표 쪽 변경은 다음 저장 때 반영)
        val blindFieldKey = if (request.metricStdId != null && request.metricStdId != curMetric) requireMetric(request.metricStdId).second
            else cur["blindFieldKey"] as String?
        val (thresholdVal, thresholdText) = requireThreshold(
            request.thresholdVal, request.threshold, request.thresholdText, cur["thresholdVal"] as BigDecimal?, cur["thresholdText"] as String
        )
        // Optional — 키 없음(null) = 유지, JSON null(empty) = 비우기. `?:` 로 합치면 비우기가 유지로 바뀐다.
        val thresholdUnit = if (request.thresholdUnit == null) cur["thresholdUnit"] as String?
            else request.thresholdUnit.orElse(null)?.trim()?.ifEmpty { null }?.let { requireLength(it, 20, "thresholdUnit", "임계 단위") }

        val curScope = cur["targetScope"] as String
        val targetScope = request.targetScope?.let { requireTargetScope(it) } ?: curScope
        @Suppress("UNCHECKED_CAST")
        val curPicks = cur["pickTargets"] as List<String>
        val picks = resolvePicks(targetScope, request.pickTargets, current = if (curScope == "PICK") curPicks else null)
        val targetDesc = resolveTargetDesc(request.target, targetScope, cur["target"] as String, curScope)

        val channels = request.channels?.let { requireChannels(it) }
        val groupIds = request.groupIds?.let { requireGroupIds(it) }
        val windowTime = when (val w = request.windowTime) {
            null -> cur["windowTime"] as String?
            else -> w.orElse(null)?.trim()?.ifEmpty { null }?.let { requireWindowTime(it) }
        }
        val evalIntervalSec = request.evalIntervalSec?.let { requireEvalInterval(it) } ?: cur["evalIntervalSec"] as Int
        requireOnceTime(validWindow, windowTime)
        val escalation = request.escalation?.let { requireEscalation(it) }
        val msgTemplate = when {
            request.msgTemplate == null -> cur["msgTemplate"] as String
            request.msgTemplate.isBlank() -> defaultMessageTemplate()
            else -> request.msgTemplate
        }
        requireLength(msgTemplate, 4000, "msgTemplate", "메시지 틀")

        val v = AlertCondValues(
            name = name, severity = severity, metricId = metricId,
            metricDesc = request.metricDesc?.trim()?.let { requireLength(it.ifEmpty { name }, 100, "metricDesc", "지표 설명") } ?: cur["metricDesc"] as String,
            op = op, thresholdVal = thresholdVal, thresholdText = thresholdText, thresholdUnit = thresholdUnit,
            duration = duration, targetScope = targetScope, targetDesc = targetDesc, validWindow = validWindow, dedupMin = dedupMin,
            msgTemplate = msgTemplate, scopeDim = scopeDim, windowTime = windowTime, evalIntervalSec = evalIntervalSec,
            ignoreWindow = request.ignoreWindow ?: (cur["ignoreWindow"] as Boolean),
            autoClose = request.autoClose ?: (cur["autoClose"] as Boolean),
            blindFieldKey = blindFieldKey
        )

        // 바뀐 필드 — 한국어 이름, 연락처·이름은 넣지 않는다(ALC-14)
        val changes = mutableListOf<String>()
        fun diff(label: String, before: Any?, after: Any?) { if (before != after) changes += "$label ${before ?: "-"} → ${after ?: "-"}" }
        diff("조건명", cur["name"], v.name)
        diff("심각도", cur["severity"], v.severity)
        diff("지표", curMetric, v.metricId)
        diff("비교", cur["op"], v.op)
        if ((cur["thresholdVal"] as BigDecimal?)?.compareTo(v.thresholdVal) != 0 || cur["thresholdText"] != v.thresholdText) {
            changes += "임계 ${cur["thresholdText"]} → ${v.thresholdText}"
        }
        diff("단위", cur["thresholdUnit"], v.thresholdUnit)
        diff("지속", cur["duration"], v.duration)
        diff("대상 범위", curScope, v.targetScope)
        if (picks != curPicks) changes += "대상 설비 ${curPicks.size}대 → ${picks.size}대"
        diff("대상 설명", cur["target"], v.targetDesc)
        @Suppress("UNCHECKED_CAST")
        channels?.let { if (it.sorted() != (cur["channels"] as List<String>).sorted()) changes += "채널 ${cur["channels"]} → $it" }
        val groupsBefore = alertConfigRepository.findConditionGroupIds(condId).sorted()
        groupIds?.let { if (it.sorted() != groupsBefore) changes += "수신 그룹 $groupsBefore → $it" }
        diff("유효 시간대", cur["validWindow"], v.validWindow)
        diff("중복 억제", cur["dedupMin"], v.dedupMin)
        diff("평가 단위", cur["scopeDim"], v.scopeDim)
        diff("지정 시각", cur["windowTime"], v.windowTime)
        diff("평가 주기", cur["evalIntervalSec"], v.evalIntervalSec)
        diff("시간대 무시", cur["ignoreWindow"], v.ignoreWindow)
        diff("자동 해제", cur["autoClose"], v.autoClose)
        @Suppress("UNCHECKED_CAST")
        escalation?.let { e ->
            val before = (cur["escalation"] as List<Map<String, Any?>>).associate { it["stage"] as Int to it["on"] as Boolean }
            e.filter { (stage, on) -> before[stage] != on }.takeIf { it.isNotEmpty() }
                ?.let { changes += "승격 " + it.joinToString(", ") { (st, on) -> "${st}차 ${if (on) "켬" else "끔"}" } }
        }
        if (msgTemplate != cur["msgTemplate"]) changes += "메시지 틀 변경"

        alertConfigRepository.updateCondition(condId, v, principal.userId)
        channels?.let { alertConfigRepository.replaceConditionChannels(condId, it) }
        groupIds?.let { alertConfigRepository.replaceConditionGroups(condId, it) }
        alertConfigRepository.replaceConditionTargets(condId, picks)
        escalation?.let { alertConfigRepository.replaceConditionEscalation(condId, it) }

        if (changes.isNotEmpty()) {
            auditLogService.record(
                logType = AuditType.CONFIG_CHANGE,
                menuId = MenuId.ALERT_COND,
                targetDesc = "알림 발송 조건 수정 [$name]",
                remark = changes.joinToString(", ").take(500)
            )
        }

        val updatedAt = alertConfigRepository.findConditionDetail(condId)?.get("updatedAt")
        @Suppress("UNCHECKED_CAST")
        return mapOf(
            "success" to true, "condId" to condId, "updatedAt" to updatedAt,
            "reach" to targetResolver.reach(groupIds ?: groupsBefore, channels ?: (cur["channels"] as List<String>)),
            "warnings" to warningsOf(msgTemplate, escalation)
        )
    }

    /**
     * 발송 조건 활성/중지 (No.155, 05 ALC-01)
     *
     * 바꿀 상태가 없으면 400 이다 — 예전에는 `{}` 를 「중지」 로 읽어 조건이 조용히 꺼졌다.
     * 이미 그 상태면 저장·감사 없이 `changed=false` 로 돌려준다.
     */
    @Transactional
    fun changeConditionState(condId: Int, on: Boolean?, state: String?): Map<String, Any?> {
        val principal = authorizationService.requireWrite(MenuId.ALERT_COND)

        val next = on ?: when (state?.trim()?.lowercase()) {
            "on" -> true
            "off" -> false
            else -> null
        } ?: throw InvalidParameterException("바꿀 상태(on)를 보내 주십시오.", "on")

        val cond = alertConfigRepository.findCondition(condId)
            ?: throw ResourceNotFoundException("발송 조건을 찾을 수 없습니다. [condId=$condId]")

        if (cond["on"] == next) return mapOf("success" to true, "on" to next, "changed" to false)

        alertConfigRepository.updateConditionState(condId, next, principal.userId)

        auditLogService.record(
            logType = AuditType.CONFIG_CHANGE,
            menuId = MenuId.ALERT_COND,
            targetDesc = "알림 조건 ${if (next) "활성" else "중지"} [${cond["name"]}]",
            remark = "condId=$condId, ${if (next) "중지 → 활성" else "활성 → 중지"}"
        )

        return mapOf("success" to true, "on" to next, "changed" to true)
    }

    /**
     * 발송 조건 삭제
     *
     * 중지(`state`)는 조건을 남겨 두고 끄는 것이고, 삭제는 잘못 만든 조건을 없애는 것이다.
     * 이미 운영 알림이 발생한 조건은 지우지 않는다 — 지난 알림의 근거가 사라지기 때문이다.
     * 그 경우에는 중지를 쓰도록 안내한다. 테스트 알림은 세지 않고, 삭제 전에 조건 연결만 끊는다.
     */
    @Transactional
    fun deleteCondition(condId: Int): Map<String, Any?> {
        // 삭제는 통합관리자만(R-13). 쓰기 권한자는 등록·수정·중지·테스트까지 — 쓰지 않는 조건은 중지한다.
        val principal = authorizationService.requireMenu(MenuId.ALERT_COND)
        if (!principal.superAdmin) {
            throw BusinessException(ErrorCode.AUTH_MENU_DENIED, "발송 조건 삭제는 통합관리자만 할 수 있습니다.")
        }

        val cond = alertConfigRepository.findCondition(condId)
            ?: throw ResourceNotFoundException("발송 조건을 찾을 수 없습니다. [condId=$condId]")

        val alertCnt = alertConfigRepository.countAlertsByCondition(condId)
        if (alertCnt > 0) {
            throw BusinessRuleException(
                "이미 알림이 발생한 조건은 삭제할 수 없습니다. [발생 ${alertCnt}건] " +
                    "지난 알림의 근거가 남아야 하므로 삭제 대신 중지를 사용하세요. 테스트 알림은 세지 않습니다."
            )
        }

        val groupIds = alertConfigRepository.findConditionGroupIds(condId)
        val detached = alertConfigRepository.detachTestAlerts(condId)
        alertConfigRepository.deleteCondition(condId)

        auditLogService.record(
            logType = AuditType.CONFIG_CHANGE,
            menuId = MenuId.ALERT_COND,
            targetDesc = "알림 조건 삭제 [${cond["name"]}]",
            remark = "condId=$condId, 그룹=$groupIds, 테스트 알림 연결 해제 ${detached}건"
        )

        return mapOf("success" to true)
    }

    /**
     * 발송 조건 테스트 (No.156, 05 ALC-03)
     *
     * 실제 임계 초과와 무관하게 조건에 연결된 수신 그룹·채널로 테스트 알림을 발송 대기열에 넣는다.
     * 중지된 조건도 시험할 수 있다. 유효 시간대는 보지 않고 개인 야간 미수신은 지킨다.
     */
    @Transactional
    fun testSendCondition(condId: Int): Pair<Map<String, Any?>, List<String>> {
        authorizationService.requireWrite(MenuId.ALERT_COND)

        val cond = alertConfigRepository.findConditionDetail(condId)
            ?: throw ResourceNotFoundException("발송 조건을 찾을 수 없습니다. [condId=$condId]")

        @Suppress("UNCHECKED_CAST")
        val subject = AlertTestSendService.TestSubject(
            dedupKey = AlertTestSendService.conditionKey(condId),
            condId = condId,
            severityCd = cond["severity"] as String,
            title = "[테스트] ${cond["name"]}",
            targetDesc = cond["target"] as String?,
            groupIds = alertConfigRepository.findConditionGroupIds(condId),
            channels = cond["channels"] as List<String>,
            bodyLines = AlertTestSendService.conditionBodyLines(cond),
            menuId = MenuId.ALERT_COND,
            auditName = "발송 조건 테스트 [${cond["name"]}]"
        )
        return alertTestSendService.send(subject, authorizationService.masking())
    }

    // =================================================================================
    // SY-05. 수신자 관리
    // =================================================================================

    /**
     * 수신자 관리 요약 (No.157) — 수신 불가 계정 수(inactiveAccountCnt, RCP-04),
     * 야간에 실제로 받는 사람 수(nightCnt)와 야간 구간(nightWindow, RCP-10), 대상 그룹 없는 승격 단계 수
     */
    @Transactional(readOnly = true)
    fun getRecipientSummary(): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.SYS_RECIP)
        return alertConfigRepository.findRecipientSummary(targetResolver.receivableUserStates) +
            // 엔진 alert.night 와 같은 값이어야 한다(06 Q-02)
            ("nightWindow" to mapOf("from" to appProperties.alert.nightFrom, "to" to appProperties.alert.nightTo))
    }

    /**
     * 수신 그룹 목록 (No.158, 05 ALC-02 · 06 RCP-01·02·09·16)
     *
     * 발송 조건 화면(alert-cond)도 그룹을 골라야 하므로 읽을 수 있다. 다만 그 경우 멤버 목록은 뺀다.
     * 수신자 관리(sys-recip) 권한이면 멤버를 싣되 이름은 worker 데이터 권한이 없으면 null 이다.
     * 받는 사람 수는 그 그룹 채널의 연락처 기준이다(RCP-09). 상한 [GROUP_LIST_MAX] 건 — 넘으면 truncated.
     *
     * @return 응답 데이터와 마스킹된 항목 키
     */
    @Transactional(readOnly = true)
    fun getRecipientGroups(includeInactive: Boolean = false): Pair<Map<String, Any?>, List<String>> {
        val principal = authorizationService.requireAnyMenu(MenuId.SYS_RECIP, MenuId.ALERT_COND)
        val full = principal.canAccessMenu(MenuId.SYS_RECIP)
        val mask = authorizationService.masking()

        val all = alertConfigRepository.findRecipientGroups(includeInactive = includeInactive)
        val groups = all.take(GROUP_LIST_MAX)
        val ids = groups.map { it["groupId"] as Int }
        val members = alertConfigRepository.findTargetMembers(ids).groupBy { it.groupId }
        val conds = alertConfigRepository.findConditionsByGroups(ids)
        val escs = alertConfigRepository.findEscStagesByGroups(ids)

        val items = groups.map { g ->
            val id = g["groupId"] as Int
            val ms = members[id].orEmpty()
            @Suppress("UNCHECKED_CAST")
            val receivable = ms.count { targetResolver.isReceivable(it, g["channels"] as List<String>) }
            val row = g.toMutableMap()
            row["memberCnt"] = ms.size
            // receivingCnt(05) 와 receivableCnt(06) 는 같은 값 — 한 릴리스 둘 다 보낸다
            row["receivingCnt"] = receivable
            row["receivableCnt"] = receivable
            row["conds"] = conds[id].orEmpty()
            row["condCnt"] = conds[id].orEmpty().count { it["on"] == true }
            row["escStages"] = escs[id].orEmpty()
            if (full) {
                row["members"] = ms.map { memberObject(it, mask) }
                row["memberEmpNos"] = ms.map { it.userId }
            }
            row
        }
        val data = mutableMapOf<String, Any?>("items" to items)
        if (all.size > GROUP_LIST_MAX) data["truncated"] = true
        return data to mask.maskedKeys()
    }

    /** 수신 그룹 상세 (06 RCP-02) */
    @Transactional(readOnly = true)
    fun getRecipientGroup(groupId: Int): Pair<Map<String, Any?>, List<String>> {
        authorizationService.requireMenu(MenuId.SYS_RECIP)
        val mask = authorizationService.masking()

        val g = alertConfigRepository.findRecipientGroups(groupId).firstOrNull()
            ?: throw ResourceNotFoundException("수신 그룹을 찾을 수 없습니다. [groupId=$groupId]")
        val ms = alertConfigRepository.findTargetMembers(listOf(groupId))

        val data = g.toMutableMap()
        data["members"] = ms.map { memberObject(it, mask) }
        data["memberEmpNos"] = ms.map { it.userId }
        @Suppress("UNCHECKED_CAST")
        data["receivableCnt"] = ms.count { targetResolver.isReceivable(it, g["channels"] as List<String>) }
        data["conds"] = alertConfigRepository.findGroupConditions(groupId)
        data["escStages"] = alertConfigRepository.findGroupEscStages(groupId)
        data["deptOptions"] = alertConfigRepository.findDeptOptions(appProperties.unassignedDeptName)
        return data to mask.maskedKeys()
    }

    /** 수신 그룹 등록 (No.159) */
    @Transactional
    fun createRecipientGroup(request: RecipientGroupRequest): Map<String, Any?> {
        val principal = authorizationService.requireWrite(MenuId.SYS_RECIP)

        val name = requireGroupName(request.name, null)
        val validWindow = requireCode("ALM_WINDOW", request.validWindow, "validWindow", "유효 시간대")
        val channels = requireGroupChannels(request.channels)
        request.deptId?.let { requireDept(it) }
        val empNos = requireRecipientEmpNos(request.memberEmpNos)

        val groupId = alertConfigRepository.insertRecipientGroup(name, validWindow, request.night, request.deptId, principal.userId)
        alertConfigRepository.replaceGroupChannels(groupId, channels)
        alertConfigRepository.replaceGroupMembers(groupId, empNos, principal.userId)

        auditLogService.record(
            logType = AuditType.CONFIG_CHANGE, menuId = MenuId.SYS_RECIP, targetDesc = "수신 그룹 등록 [$name]",
            remark = "채널=${channels.joinToString(",")}, 시간대=$validWindow, 멤버=$empNos".take(500)
        )
        return mapOf("groupId" to groupId)
    }

    /**
     * 수신 그룹 수정 (No.160, 06 RCP-02)
     *
     * 보낸 키만 바꾼다. 예전에는 DTO 기본값으로 담당 부서·시간대·야간 수신을 덮어 썼고,
     * 멤버를 모두 빼는 저장(`[]`)은 조용히 무시했다.
     */
    @Transactional
    fun updateRecipientGroup(groupId: Int, request: RecipientGroupUpdateRequest): Map<String, Any?> {
        val principal = authorizationService.requireWrite(MenuId.SYS_RECIP)

        val cur = alertConfigRepository.findRecipientGroups(groupId).firstOrNull()
            ?: throw ResourceNotFoundException("수신 그룹을 찾을 수 없습니다. [groupId=$groupId]")

        request.updatedAt?.trim()?.takeIf { it.isNotEmpty() }?.let {
            if (it != cur["updatedAt"]) {
                throw ConflictingValueException("다른 사용자가 먼저 수정했습니다. 다시 열어 확인해 주십시오.", "updatedAt")
            }
        }

        val name = request.name?.let { requireGroupName(it, groupId) } ?: cur["name"] as String
        val validWindow = request.validWindow?.let { requireCode("ALM_WINDOW", it, "validWindow", "유효 시간대") } ?: cur["validWindow"] as String
        val night = request.night ?: cur["night"] as Boolean
        val deptId = when (val d = request.deptId) {
            null -> cur["deptId"] as Int?
            else -> d.orElse(null)?.also { requireDept(it) }
        }
        val channels = request.channels?.let { requireGroupChannels(it) }
        val empNos = request.memberEmpNos?.let { requireRecipientEmpNos(it) }

        alertConfigRepository.updateRecipientGroup(groupId, name, validWindow, night, deptId, principal.userId)
        channels?.let { alertConfigRepository.replaceGroupChannels(groupId, it) }
        var added = emptyList<String>()
        var removed = emptyList<String>()
        empNos?.let {
            val before = alertConfigRepository.findTargetMembers(listOf(groupId)).map { m -> m.userId }.toSet()
            added = it.filter { e -> e !in before }
            removed = before.filter { e -> e !in it }.sorted()
            alertConfigRepository.replaceGroupMembers(groupId, it, principal.userId)
        }

        // 바뀐 항목 + 멤버 사번 목록 — 이름·연락처는 넣지 않는다 (06 RCP-13)
        val changes = mutableListOf<String>()
        if (name != cur["name"]) changes += "이름 ${cur["name"]} → $name"
        if (validWindow != cur["validWindow"]) changes += "시간대 ${cur["validWindow"]} → $validWindow"
        if (night != cur["night"]) changes += "야간 수신 ${cur["night"]} → $night"
        if (deptId != cur["deptId"]) changes += "담당 부서 ${cur["deptId"] ?: "-"} → ${deptId ?: "-"}"
        @Suppress("UNCHECKED_CAST")
        channels?.let { if (it.sorted() != (cur["channels"] as List<String>).sorted()) changes += "채널 ${cur["channels"]} → $it" }
        if (added.isNotEmpty()) changes += "멤버 추가=$added"
        if (removed.isNotEmpty()) changes += "멤버 제외=$removed"
        if (changes.isNotEmpty()) {
            auditLogService.record(
                logType = AuditType.CONFIG_CHANGE, menuId = MenuId.SYS_RECIP, targetDesc = "수신 그룹 수정 [$name]",
                remark = changes.joinToString(", ").take(500)
            )
        }

        val updatedAt = alertConfigRepository.findRecipientGroups(groupId).firstOrNull()?.get("updatedAt")
        return mapOf("success" to true, "updatedAt" to updatedAt)
    }

    /**
     * 수신 그룹 사용/중지 (06 RCP-08) — 사용 중 발송 조건이나 승격 규칙이 쓰는 그룹은 중지하지 않는다(409).
     */
    @Transactional
    fun changeRecipientGroupState(groupId: Int, on: Boolean?): Map<String, Any?> {
        val principal = authorizationService.requireWrite(MenuId.SYS_RECIP)
        val next = on ?: throw InvalidParameterException("바꿀 상태(on)를 보내 주십시오.", "on")
        val g = alertConfigRepository.findRecipientGroups(groupId).firstOrNull()
            ?: throw ResourceNotFoundException("수신 그룹을 찾을 수 없습니다. [groupId=$groupId]")
        if ((g["useFlg"] == "Y") == next) return mapOf("success" to true, "on" to next, "changed" to false)

        if (!next) {
            val conds = alertConfigRepository.findGroupConditions(groupId).filter { it["on"] == true }
            val escs = alertConfigRepository.findGroupEscStages(groupId)
            if (conds.isNotEmpty() || escs.isNotEmpty()) {
                throw BusinessException(
                    ErrorCode.RULE_VIOLATION,
                    "발송 조건 ${conds.size}건·승격 규칙 ${escs.size}단계가 이 그룹을 씁니다. 먼저 연결을 바꿔 주십시오.",
                    null, mapOf("conds" to conds.map { mapOf("condId" to it["condId"], "name" to it["name"]) }, "escStages" to escs)
                )
            }
        }
        alertConfigRepository.updateGroupUse(groupId, next, principal.userId)
        auditLogService.record(
            logType = AuditType.CONFIG_CHANGE, menuId = MenuId.SYS_RECIP,
            targetDesc = "수신 그룹 ${if (next) "사용" else "사용 중지"} [${g["name"]}]", remark = "groupId=$groupId"
        )
        return mapOf("success" to true, "on" to next, "changed" to true)
    }

    /** 수신 그룹 테스트 발송 (No.161, 06 RCP-03) — 그룹의 모든 채널로 대기열에 넣는다 */
    @Transactional
    fun testSendGroup(groupId: Int): Pair<Map<String, Any?>, List<String>> {
        authorizationService.requireWrite(MenuId.SYS_RECIP)

        val g = alertConfigRepository.findRecipientGroups(groupId).firstOrNull()
            ?: throw ResourceNotFoundException("수신 그룹을 찾을 수 없습니다. [groupId=$groupId]")
        val name = g["name"] as String

        @Suppress("UNCHECKED_CAST")
        val subject = AlertTestSendService.TestSubject(
            dedupKey = AlertTestSendService.groupKey(groupId),
            condId = null,
            severityCd = "LOW",
            title = "[테스트] 수신 그룹 발송 확인 — $name",
            targetDesc = name,
            groupIds = listOf(groupId),
            channels = g["channels"] as List<String>,
            bodyLines = listOf("수신 그룹: $name"),
            menuId = MenuId.SYS_RECIP,
            auditName = "수신 그룹 테스트 [$name]"
        )
        return alertTestSendService.send(subject, authorizationService.masking())
    }

    /**
     * 수신자 목록 (No.162, 06 RCP-01·05·11)
     *
     * 이름·메일·휴대전화·메신저는 worker 데이터 권한이 없으면 null 이다(응답 `masked`).
     *
     * @param keyword   이름·사번·부서 검색(50자 이내)
     * @param groupId   이 그룹의 멤버만
     * @param userState 계정 상태(SYS_USER_STATE)
     * @return 행, 페이지 정보, 마스킹된 항목 키
     */
    @Transactional(readOnly = true)
    fun getRecipients(
        state: String?, page: Int?, size: Int?, keyword: String? = null, groupId: Int? = null, userState: String? = null
    ): Triple<List<Map<String, Any?>>, PageMeta, List<String>> {
        authorizationService.requireMenu(MenuId.SYS_RECIP)
        val kw = keyword?.trim()?.ifEmpty { null }?.also {
            if (it.length > 50) throw InvalidParameterException("검색어는 50자까지 입력할 수 있습니다.", "keyword")
        }
        val recv = state?.trim()?.ifEmpty { null }?.let {
            requireCode("ALM_RECV_STATE", alertConfigRepository.normalizeRecvState(it), "state", "수신 상태")
        }
        val us = userState?.trim()?.ifEmpty { null }?.let { requireCode("SYS_USER_STATE", it, "userState", "계정 상태") }
        val filter = AlertConfigRepository.RecipientFilter(kw, groupId, us)
        val paging = PageRequestParam.ofAllowAll(page, size)
        val mask = authorizationService.masking()

        val total = alertConfigRepository.countRecipients(recv, filter)
        val rows = alertConfigRepository.findRecipients(recv, paging.limitOrNull ?: RECIPIENT_ALL_MAX, paging.offset, filter).map {
            it.toMutableMap().also { row -> mask.applyTo(row, CONTACT_FIELDS) }
        }
        val meta = if (paging.isAll) PageMeta.capped(total, RECIPIENT_ALL_MAX, rows.size) else PageMeta.of(paging.page, paging.size, total)
        return Triple(rows, meta, mask.maskedKeys())
    }

    /**
     * 수신자 등록 후보 (06 RCP-06) — 아직 수신자가 아닌 수신 가능 상태 계정(잠김 포함). 미배정 계정은 제외한다(R-14).
     * 메일 주소는 worker 데이터 권한이 없으면 null.
     */
    @Transactional(readOnly = true)
    fun getRecipientCandidates(keyword: String?, deptId: Int?, size: Int?): Pair<Map<String, Any?>, List<String>> {
        authorizationService.requireMenu(MenuId.SYS_RECIP)
        val mask = authorizationService.masking()
        val limit = (size ?: 20).coerceIn(1, 100)
        val states = targetResolver.receivableUserStates
        val items = alertConfigRepository.findRecipientCandidates(keyword, deptId, appProperties.unassignedDeptName, limit, states).map {
            it.toMutableMap().also { row -> mask.applyTo(row, mapOf("email" to DataField.WORKER)) }
        }
        val total = alertConfigRepository.countRecipientCandidates(keyword, deptId, appProperties.unassignedDeptName, states)
        return mapOf("items" to items, "meta" to mapOf("page" to 1, "size" to limit, "total" to total)) to mask.maskedKeys()
    }

    /** 수신자 등록 (No.163) — 연락처를 다루므로 worker 데이터 권한이 필요하다(RCP-01) */
    @Transactional
    fun createRecipient(request: RecipientRequest): Map<String, Any?> {
        val principal = authorizationService.requireWrite(MenuId.SYS_RECIP)
        requireContactPermission(principal)

        val empNo = request.empNo?.trim()?.ifEmpty { null }
            ?: throw InvalidParameterException("사번을 입력해 주세요.", "empNo")
        val mail = request.mail?.trim()?.ifEmpty { null }
            ?: throw InvalidParameterException("메일 주소를 입력해 주세요.", "mail")

        if (!systemUserRepository.existsUser(empNo)) {
            throw ResourceNotFoundException("계정을 찾을 수 없습니다. [$empNo]")
        }
        // 부서 배정 전(미배정) 계정은 수신자로 두지 않는다 (R-14, 06 RCP-06)
        if (alertConfigRepository.findUserDeptName(empNo) == appProperties.unassignedDeptName) {
            throw ConflictingValueException("부서 배정 전 계정은 알림 수신자로 등록할 수 없습니다.", "empNo")
        }
        if (alertConfigRepository.existsRecipient(empNo)) {
            throw DuplicatedValueException("이미 등록된 수신자입니다. [$empNo]", "empNo")
        }
        requireMail(mail)
        val hp = request.hp?.trim()?.ifEmpty { null }?.let { requireHp(it) }
        val messenger = request.messenger?.trim()?.ifEmpty { null }?.let { requireMessenger(it) }

        alertConfigRepository.insertRecipient(empNo, mail, hp, messenger, request.night ?: false, principal.userId)

        auditLogService.record(
            logType = AuditType.CONFIG_CHANGE, menuId = MenuId.SYS_RECIP, targetDesc = "수신자 등록 [$empNo]",
            remark = listOfNotNull("메일", hp?.let { "휴대전화" }, messenger?.let { "메신저" }).joinToString("·") + " 등록"
        )
        return mapOf("recipientId" to empNo)
    }

    /**
     * 수신자 수정 (No.164) — 연락처를 다루므로 worker 데이터 권한이 필요하다(RCP-01).
     * 키 없음 = 그대로, 휴대전화·메신저 `""` = 지우기, 메일 `""` = 400(필수) (06 RCP-12).
     */
    @Transactional
    fun updateRecipient(recipientId: String, request: RecipientRequest): Map<String, Any?> {
        val principal = authorizationService.requireWrite(MenuId.SYS_RECIP)
        requireContactPermission(principal)

        val before = alertConfigRepository.findRecipientRow(recipientId)
            ?: throw ResourceNotFoundException("수신자를 찾을 수 없습니다. [$recipientId]")
        request.empNo?.trim()?.ifEmpty { null }?.let {
            if (it != recipientId) throw InvalidParameterException("경로의 사번과 본문의 사번이 다릅니다. [$it]", "empNo")
        }
        val mail = request.mail?.trim()?.let {
            if (it.isEmpty()) throw InvalidParameterException("메일 주소를 입력해 주세요.", "mail")
            requireMail(it)
        }
        val hp = request.hp?.trim()?.let { if (it.isEmpty()) "" else requireHp(it) }
        val messenger = request.messenger?.trim()?.let { if (it.isEmpty()) "" else requireMessenger(it) }

        alertConfigRepository.updateRecipient(recipientId, mail, hp, messenger, request.night, principal.userId)

        // 바뀐 항목 이름만 — 값은 남기지 않는다 (06 RCP-13)
        val changed = listOfNotNull(
            "메일".takeIf { mail != null && mail != before["mail"] },
            "휴대전화".takeIf { hp != null && hp.ifEmpty { null } != before["hp"] },
            "메신저".takeIf { messenger != null && messenger.ifEmpty { null } != before["messenger"] },
            "야간 수신".takeIf { request.night != null && request.night != before["night"] }
        )
        if (changed.isNotEmpty()) {
            auditLogService.record(
                logType = AuditType.CONFIG_CHANGE, menuId = MenuId.SYS_RECIP, targetDesc = "수신자 수정 [$recipientId]",
                remark = changed.joinToString(", ") + " 변경"
            )
        }
        return mapOf("success" to true)
    }

    /**
     * 수신/부재 전환 (No.165, 06 RCP-07) — 상태 필수. 사유(reason)를 보내면 비고에 남긴다(`""` 은 지움).
     */
    @Transactional
    fun changeRecipientState(recipientId: String, state: String?, reason: String? = null): Map<String, Any?> {
        val principal = authorizationService.requireWrite(MenuId.SYS_RECIP)
        val raw = state?.trim()?.ifEmpty { null }
            ?: throw InvalidParameterException("바꿀 수신 상태(RECV·ABSENT)를 보내 주십시오.", "state")
        val next = requireCode("ALM_RECV_STATE", alertConfigRepository.normalizeRecvState(raw), "state", "수신 상태")
        val cleanReason = reason?.trim()
        if (cleanReason != null && cleanReason.length > 300) {
            throw InvalidParameterException("비고는 300자까지 입력할 수 있습니다.", "reason")
        }
        val before = alertConfigRepository.findRecipientRow(recipientId)
            ?: throw ResourceNotFoundException("수신자를 찾을 수 없습니다. [$recipientId]")
        if (before["state"] == next && cleanReason == null) return mapOf("success" to true, "state" to next, "changed" to false)

        alertConfigRepository.updateRecipientState(recipientId, next, principal.userId, cleanReason)
        auditLogService.record(
            logType = AuditType.CONFIG_CHANGE, menuId = MenuId.SYS_RECIP, targetDesc = "수신자 상태 변경 [$recipientId]",
            remark = "${before["state"]} → $next, 사유 ${if (cleanReason.isNullOrEmpty()) "없음" else "있음"}"
        )
        return mapOf("success" to true, "state" to next, "changed" to true)
    }

    /**
     * 수신자를 빼면 생기는 영향 (06 RCP-08) — 그룹별 남는 수신 가능 인원, 받는 사람이 없어지는 그룹,
     * 그 그룹을 쓰는 사용 중 발송 조건·승격 단계.
     */
    @Transactional(readOnly = true)
    fun getRecipientImpact(recipientId: String): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.SYS_RECIP)
        if (!alertConfigRepository.existsRecipient(recipientId)) throw ResourceNotFoundException("수신자를 찾을 수 없습니다. [$recipientId]")
        return impactOf(recipientId)
    }

    /**
     * 수신자 삭제 (06 RCP-08) — 받는 사람이 없어지는 그룹이 생기면 force 없이는 409(data 에 영향).
     * 그룹 멤버십은 함께 지워지고 지난 발송 로그는 남는다.
     */
    @Transactional
    fun deleteRecipient(recipientId: String, force: Boolean): Map<String, Any?> {
        val principal = authorizationService.requireWrite(MenuId.SYS_RECIP)
        requireContactPermission(principal)
        if (!alertConfigRepository.existsRecipient(recipientId)) throw ResourceNotFoundException("수신자를 찾을 수 없습니다. [$recipientId]")
        val impact = impactOf(recipientId)
        @Suppress("UNCHECKED_CAST")
        val zero = impact["zeroGroups"] as List<Map<String, Any?>>
        if (zero.isNotEmpty() && !force) {
            throw BusinessException(
                ErrorCode.RULE_VIOLATION, "이 수신자를 지우면 받는 사람이 없어지는 수신 그룹이 있습니다. 확인 후 다시 요청해 주십시오.",
                null, mapOf("zeroGroups" to zero, "affectedConds" to impact["affectedConds"])
            )
        }
        @Suppress("UNCHECKED_CAST")
        val groupCnt = (impact["groups"] as List<*>).size
        alertConfigRepository.deleteRecipient(recipientId)
        auditLogService.record(
            logType = AuditType.CONFIG_CHANGE, menuId = MenuId.SYS_RECIP, targetDesc = "수신자 삭제 [$recipientId]",
            remark = "영향 그룹=${zero.map { it["groupId"] }}, force=${if (force) "Y" else "N"}"
        )
        return mapOf("success" to true, "removedGroupCnt" to groupCnt)
    }

    private fun impactOf(empNo: String): Map<String, Any?> {
        val groupIds = alertConfigRepository.findMemberGroupIds(empNo)
        val groups = alertConfigRepository.findTargetGroups(groupIds).filter { it.useFlg }
        val members = alertConfigRepository.findTargetMembers(groups.map { it.groupId }).groupBy { it.groupId }
        val rows = groups.map { g ->
            val ms = members[g.groupId].orEmpty()
            val before = ms.count { targetResolver.isReceivable(it, g.channels) }
            val after = ms.filter { it.userId != empNo }.count { targetResolver.isReceivable(it, g.channels) }
            Triple(g, before, after)
        }
        val zero = rows.filter { (_, before, after) -> before > 0 && after == 0 }.map { it.first.groupId }
        val conds = alertConfigRepository.findConditionsByGroups(zero)
        val escs = alertConfigRepository.findEscStagesByGroups(zero)
        return mapOf(
            "groups" to rows.map { (g, _, after) -> mapOf("groupId" to g.groupId, "name" to g.groupNm, "receivableCntAfter" to after) },
            "zeroGroups" to rows.filter { it.first.groupId in zero }.map { mapOf("groupId" to it.first.groupId, "name" to it.first.groupNm) },
            "affectedConds" to conds.values.flatten().filter { it["on"] == true }.distinctBy { it["condId"] }
                .map { mapOf("condId" to it["condId"], "name" to it["name"]) },
            "affectedEscStages" to escs.values.flatten()
        )
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
        // 두 화면 중 하나의 쓰기 권한(R-06, 06 RCP-15)
        authorizationService.requireAnyWrite(MenuId.SYS_RECIP, MenuId.ALERT_COND)

        var updated = 0
        request.stages.forEach { stage ->
            updated += alertConfigRepository.updateEscalationRule(stage.stage, stage.waitMin, stage.targetGroupId)
        }

        auditLogService.record(
            logType = AuditType.CONFIG_CHANGE,
            menuId = MenuId.SYS_RECIP,
            targetDesc = "알림 승격 규칙 수정",
            remark = "변경 ${updated}단계"
        )

        return mapOf("success" to true, "updatedCnt" to updated)
    }

    // ---------------------------------------------------------------------------------
    // 내부 보조
    // ---------------------------------------------------------------------------------

    // ---- 발송 조건 입력 검증 (05 ALC-06·09·12) ----

    /** 코드 검증 — 「알 수 없는 {라벨} 코드입니다. [값]」 (4.4.3 문구, CodeValidator 문구와 다르다) */
    private fun requireCode(groupCd: String, value: String?, field: String, label: String): String {
        val code = value?.trim()?.uppercase().orEmpty()
        if (code.isEmpty() || !codeValidator.exists(groupCd, code)) {
            throw InvalidParameterException("알 수 없는 $label 코드입니다. [${value ?: ""}]", field)
        }
        return code
    }

    /** 길이 검사 — 자르지 않고 400 */
    private fun requireLength(value: String, max: Int, field: String, label: String): String {
        if (value.length > max) {
            // 받침이 있으면 「은」, 없으면 「는」 — 「메시지 틀은 …」, 「지표 설명은 …」
            val last = label.last()
            val josa = if (last in '가'..'힣' && (last - '가') % 28 == 0) "는" else "은"
            throw InvalidParameterException("$label$josa ${"%,d".format(max)}자까지 입력할 수 있습니다.", field)
        }
        return value
    }

    private fun requireCondName(raw: String, excludeCondId: Int?): String {
        val name = raw.trim()
        if (name.isEmpty()) throw InvalidParameterException("조건명을 입력해 주세요.", "name")
        if (name.length > 100) throw InvalidParameterException("조건명은 100자까지 입력할 수 있습니다.", "name")
        if (alertConfigRepository.existsConditionName(name, excludeCondId)) {
            throw DuplicatedValueException("이미 등록된 조건명입니다. [$name]", "name")
        }
        return name
    }

    /** 지표 확인 — 없으면 400. 반환값의 second 는 지표의 민감 항목 key */
    private fun requireMetric(metricId: Int): Pair<Boolean, String?> =
        alertConfigRepository.findMetricBlindKey(metricId)
            ?: throw InvalidParameterException("없는 감지 지표입니다. [$metricId]", "metricStdId")

    /**
     * 임계값 — 숫자 필수(numeric(18,4)). `threshold`(표기)가 숫자뿐이면 값으로도 쓴다.
     * 수정에서 아무 키도 안 보내면 기존 값을 그대로 둔다.
     *
     * @return 값, 표기
     */
    private fun requireThreshold(
        thresholdVal: BigDecimal?, threshold: String?, thresholdText: String?, curVal: BigDecimal?, curText: String?
    ): Pair<BigDecimal, String> {
        val textIn = thresholdText?.trim()?.ifEmpty { null } ?: threshold?.trim()?.ifEmpty { null }
        val valueIn = thresholdVal ?: threshold?.trim()?.toBigDecimalOrNull()
        val value = valueIn ?: (if (textIn == null) curVal else null)
            ?: throw InvalidParameterException("임계값을 숫자로 입력해 주십시오.", "thresholdVal")
        val stripped = value.stripTrailingZeros()
        if (stripped.scale() > 4 || stripped.precision() - stripped.scale() > 14) {
            throw InvalidParameterException("임계값을 숫자로 입력해 주십시오.", "thresholdVal")
        }
        val text = textIn ?: (if (valueIn == null) curText else null) ?: stripped.toPlainString()
        return value to requireLength(text, 50, "thresholdText", "임계 표기")
    }

    private fun requireWindowTime(value: String): String {
        if (!Regex("^([01]\\d|2[0-3]):[0-5]\\d$").matches(value)) {
            throw InvalidParameterException("지정 시각은 HH:mm 형식입니다.", "windowTime")
        }
        return value
    }

    private fun requireOnceTime(validWindow: String, windowTime: String?) {
        if (validWindow == "ONCE" && windowTime == null) {
            throw InvalidParameterException("지정 시각 1회는 시각(HH:mm)이 필요합니다.", "windowTime")
        }
    }

    private fun requireEvalInterval(sec: Int): Int {
        if (sec !in EVAL_INTERVALS) throw InvalidParameterException("평가 주기는 60·300·600·1800·3600초 중에서 고릅니다.", "evalIntervalSec")
        return sec
    }

    /** 승격 단계 켬/끔 — 없는 단계는 400 */
    private fun requireEscalation(items: List<com.dwje.api.model.request.CondEscalationInput>): List<Pair<Int, Boolean>> {
        val levels = alertConfigRepository.findEscalationTargets()
        return items.map {
            if (it.stage !in levels) throw InvalidParameterException("없는 승격 단계입니다. [${it.stage}]", "escalation")
            it.stage to it.on
        }
    }

    /** 저장은 막지 않는 경고 — 대상 그룹 없는 승격 단계 · 정의되지 않은 메시지 변수 (ALC-09) */
    private fun warningsOf(template: String, escalation: List<Pair<Int, Boolean>>?): List<String> {
        val warnings = mutableListOf<String>()
        val targets = alertConfigRepository.findEscalationTargets()
        escalation.orEmpty().filter { (stage, on) -> on && targets[stage] == null }.forEach { (stage, _) ->
            warnings += "승격 ${stage}차 규칙에 대상 그룹이 없습니다 — 승격해도 아무도 받지 못합니다"
        }
        Regex("\\{\\{\\s*(\\w+)\\s*}}").findAll(template).map { it.groupValues[1] }.distinct()
            .filterNot { it in TEMPLATE_VARS }.forEach { warnings += "정의되지 않은 변수입니다: {{$it}}" }
        return warnings
    }

    /** 그룹별 멤버 수·수신 가능 인원 */
    private fun groupCounts(groupIds: List<Int>): Map<Int, Map<String, Any?>> {
        val ids = groupIds.distinct()
        val members = alertConfigRepository.findTargetMembers(ids).groupBy { it.groupId }
        return ids.associateWith { id ->
            val ms = members[id].orEmpty()
            mapOf("memberCnt" to ms.size, "receivingCnt" to ms.count { targetResolver.isReceivable(it) })
        }
    }

    /** 조건에 걸린 그룹 행에 멤버 수·수신 가능 인원을 붙인다 */
    private fun groupObjects(groups: List<Map<String, Any?>>): List<Map<String, Any?>> {
        val counts = groupCounts(groups.map { it["groupId"] as Int })
        return groups.map { it + counts.getValue(it["groupId"] as Int) }
    }

    /** 그룹 멤버 응답 객체 — 이름은 worker 권한이 없으면 null */
    private fun memberObject(m: TargetMemberRow, mask: MaskingSupport): Map<String, Any?> = mapOf(
        "empNo" to m.userId,
        "name" to mask.on(DataField.WORKER) { m.userNm },
        "dept" to m.deptNm,
        "state" to m.recvState,
        "userState" to m.userState
    )

    private fun requireChannels(channels: List<String>): List<String> =
        channels.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct()
            .ifEmpty { throw InvalidParameterException("발송 채널을 1개 이상 선택해 주십시오.", "channels") }
            .onEach { if (!codeValidator.exists("ALM_CHANNEL", it)) throw InvalidParameterException("알 수 없는 발송 채널입니다. [$it]", "channels") }

    /** 수신 그룹 — 1개 이상, 있고 사용 중이어야 한다 (05 ALC-06) */
    private fun requireGroupIds(groupIds: List<Int>): List<Int> {
        val ids = groupIds.distinct().ifEmpty { throw InvalidParameterException("수신 그룹을 1개 이상 선택해 주십시오.", "groupIds") }
        val found = alertConfigRepository.findGroupsUse(ids)
        ids.forEach { id ->
            val g = found[id] ?: throw InvalidParameterException("없는 수신 그룹입니다. [groupId=$id]", "groupIds")
            if (g.second != "Y") throw InvalidParameterException("사용 중지된 수신 그룹입니다. [${g.first}]", "groupIds")
        }
        return ids
    }

    /** 대상 범위 코드 검증 (ALM_TARGET, ALC-05) */
    private fun requireTargetScope(value: String): String {
        val code = value.trim().uppercase()
        if (code.isEmpty() || !codeValidator.exists("ALM_TARGET", code)) {
            throw InvalidParameterException("알 수 없는 대상 범위 코드입니다. [$value]", "targetScope")
        }
        return code
    }

    /**
     * 개별 설비 대상 결정 (ALC-05)
     *
     * PICK 이 아니면 빈 목록(남은 대상을 지운다). PICK 인데 목록을 안 보냈으면 기존 대상을 유지하고,
     * 기존 대상도 없으면 400 이다.
     *
     * @param current 기존 PICK 대상(기존 범위가 PICK 이 아니면 null)
     */
    private fun resolvePicks(targetScope: String, incoming: List<String>?, current: List<String>?): List<String> {
        if (targetScope != "PICK") return emptyList()
        val picks = (incoming ?: current).orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (picks.isEmpty()) throw InvalidParameterException("개별 설비 선택은 설비를 1대 이상 골라야 합니다.", "pickTargets")
        if (picks.size > PICK_MAX) throw InvalidParameterException("개별 설비는 ${PICK_MAX}대까지 고를 수 있습니다.", "pickTargets")
        if (incoming != null) {
            val unknown = commonMasterRepository.findUnknownEqptCodes(appProperties.defaultPlantCd, picks)
            if (unknown.isNotEmpty()) {
                throw InvalidParameterException("없는 설비 코드입니다. [${unknown.take(10).joinToString(", ")}]", "pickTargets")
            }
        }
        return picks
    }

    /**
     * 대상 설명 결정 — 비면 범위 표기명. 수정에서 키를 빼고 범위만 바꿨을 때, 기존 설명이 옛 범위 표기명
     * 그대로였으면 새 범위 표기명으로 따라간다.
     */
    private fun resolveTargetDesc(incoming: String?, targetScope: String, currentDesc: String?, currentScope: String?): String {
        val label = { scope: String -> alertConfigRepository.findCodeName("ALM_TARGET", scope) ?: scope }
        val desc = when {
            incoming == null && currentDesc != null ->
                if (currentScope != null && currentScope != targetScope && currentDesc == label(currentScope)) label(targetScope)
                else currentDesc
            incoming.isNullOrBlank() -> label(targetScope)
            else -> incoming.trim()
        }
        if (desc.length > 200) throw InvalidParameterException("대상 설명은 200자까지 입력할 수 있습니다.", "target")
        return desc
    }

    private fun requireGroupName(raw: String, excludeGroupId: Int?): String {
        val name = raw.trim()
        if (name.isEmpty()) throw InvalidParameterException("그룹명을 입력해 주세요.", "name")
        if (name.length > 50) throw InvalidParameterException("그룹명은 50자까지 입력할 수 있습니다.", "name")
        if (alertConfigRepository.existsGroupName(name, excludeGroupId)) {
            throw DuplicatedValueException("이미 등록된 수신 그룹명입니다. [$name]", "name")
        }
        return name
    }

    private fun requireDept(deptId: Int) {
        if (!alertConfigRepository.existsActiveDept(deptId)) {
            throw InvalidParameterException("사용 중인 부서가 아닙니다. [deptId=$deptId]", "deptId")
        }
    }

    /** 수신자로 등록된 사번만 그룹에 넣는다 — 예전에는 아닌 사번을 조용히 버렸다 */
    private fun requireRecipientEmpNos(empNos: List<String>): List<String> {
        val list = empNos.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val unknown = alertConfigRepository.findNonRecipients(list)
        if (unknown.isNotEmpty()) {
            throw InvalidParameterException("수신자로 등록되지 않은 사번이 있습니다. [${unknown.joinToString(", ")}]", "memberEmpNos")
        }
        return list
    }

    /** 그룹 채널 — 1개 이상, 코드 확인 (06 RCP-12) */
    private fun requireGroupChannels(channels: List<String>): List<String> =
        channels.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct()
            .ifEmpty { throw InvalidParameterException("수신 채널을 1개 이상 선택해 주십시오.", "channels") }
            .onEach { if (!codeValidator.exists("ALM_CHANNEL", it)) throw InvalidParameterException("알 수 없는 발송 채널입니다. [$it]", "channels") }

    private fun requireMail(mail: String): String {
        if (mail.length > 200 || !Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(mail)) {
            throw InvalidParameterException("메일 주소 형식이 올바르지 않습니다.", "mail")
        }
        return mail
    }

    private fun requireHp(hp: String): String {
        if (!Regex("^[0-9-]{9,20}$").matches(hp)) throw InvalidParameterException("휴대전화는 숫자와 하이픈 9~20자입니다.", "hp")
        return hp
    }

    private fun requireMessenger(id: String): String {
        if (id.length > 50) throw InvalidParameterException("메신저 ID 는 50자까지 입력할 수 있습니다.", "messenger")
        return id
    }

    /** 연락처 쓰기 — worker 데이터 권한 (RCP-01). 쓰기 권한 검사 뒤에 부른다 */
    private fun requireContactPermission(principal: UserPrincipal) {
        if (!principal.canReadField(DataField.WORKER)) {
            throw BusinessException(ErrorCode.AUTH_DATA_DENIED, "연락처를 다룰 데이터 권한(worker)이 없습니다.")
        }
    }

    /**
     * 기본 메시지 템플릿 — 단가·수율 등 민감정보는 본문에 포함하지 않는다.
     *
     * 조건에 틀을 주지 않았을 때의 기본 틀. 치환은 Alert_Engine(MessageRenderer)이 `{{변수}}` 꼴만 한다 —
     * 예전 기본값(`{심각도}` 꼴)은 치환되지 않고 글자 그대로 나갔다(2026-09-23 수정).
     * 쓸 수 있는 변수: severity · condNm · scope · eqptNm · target · metricNm · metricDesc · value · unit · op · threshold · evidence · occurredAt · link
     */
    private fun defaultMessageTemplate(): String =
        "[{{severity}}] {{condNm}} — {{scope}} {{metricNm}} {{value}}{{unit}} ({{op}} {{threshold}}{{unit}}) {{link}}"

    companion object {
        /** 개별 설비 선택 상한 */
        const val PICK_MAX = 500

        /** 평가 주기(초) 선택지 — 엔진이 받는 값 (05 ALC-09) */
        private val EVAL_INTERVALS = setOf(60, 300, 600, 1800, 3600)

        /** 메시지 틀 변수 — 엔진 MessageRenderer 가 치환하는 이름 */
        private val TEMPLATE_VARS = setOf(
            "severity", "condNm", "scope", "eqptNm", "target", "metricNm", "metricDesc", "value", "unit", "op",
            "threshold", "evidence", "occurredAt", "link"
        )

        /** 전량 조회(size=0) 상한 — 발송 조건 · 수신자 */
        const val CONDITION_ALL_MAX = 1_000
        const val RECIPIENT_ALL_MAX = 5_000
        /** 수신 그룹 목록 상한 (06 RCP-16) */
        const val GROUP_LIST_MAX = 1_000

        /** 수신자 연락처 — worker 데이터 권한이 없으면 null */
        private val CONTACT_FIELDS = mapOf(
            "name" to DataField.WORKER,
            "mail" to DataField.WORKER,
            "hp" to DataField.WORKER,
            "messenger" to DataField.WORKER
        )
    }
}
