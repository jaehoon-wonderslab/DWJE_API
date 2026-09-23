package com.dwje.api.service

import com.dwje.api.common.exception.BusinessRuleException
import com.dwje.api.common.exception.ResourceNotFoundException
import com.dwje.api.common.exception.SystemErrorException
import com.dwje.api.common.response.PageMeta
import com.dwje.api.common.util.DataField
import com.dwje.api.common.util.DateUtils
import com.dwje.api.common.util.MenuId
import com.dwje.api.common.util.PageRequestParam
import com.dwje.api.config.AppProperties
import com.dwje.api.repository.AlertRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * 이상 알림 서비스 (AL-01)
 *
 * 접근 : 화면 권한 `alert-list`. 발송 로그만 `alert-cond` 또는 `sys-recip` ([getSendLogs])
 */
@Service
class AlertService(
    private val alertRepository: AlertRepository,
    private val authorizationService: AuthorizationService,
    private val auditLogService: AuditLogService,
    private val appProperties: AppProperties
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 알림 목록 조회 (No.102)
     *
     * @param type   심각도 (CRIT/WARN/LOW)
     * @param period 조회 기간 — today | 7d | 30d
     */
    @Transactional(readOnly = true)
    fun getAlerts(
        type: String?,
        eqptCd: String?,
        period: String?,
        ackState: String?,
        page: Int?,
        size: Int?
    ): Pair<List<Map<String, Any?>>, PageMeta> {
        authorizationService.requireMenu(MenuId.ALERT_LIST)

        val (from, to) = resolvePeriod(period)
        val paging = PageRequestParam.of(page, size)
        val plantCd = appProperties.defaultPlantCd

        val total = alertRepository.countAlerts(plantCd, type, eqptCd, from, to, ackState)
        val rows = alertRepository.findAlerts(plantCd, type, eqptCd, from, to, ackState, paging.limit, paging.offset)

        return rows to PageMeta.of(paging.page, paging.size, total)
    }

    /**
     * 알림 상세 조회 (No.103)
     *
     * 동일 조건·설비의 과거 발생 이력을 원인 후보로 함께 제시한다.
     */
    @Transactional(readOnly = true)
    fun getAlert(alertId: Long): Pair<Map<String, Any?>, com.dwje.api.common.util.MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.ALERT_LIST)

        val alert = alertRepository.findAlert(alertId)
            ?: throw ResourceNotFoundException("알림을 찾을 수 없습니다. [alertId=$alertId]")

        val result = alert.toMutableMap()

        // 금형 코드는 mold 데이터 권한 대상이다.
        mask.applyTo(result, mapOf("moldCd" to DataField.MOLD))

        // 유사 이력에서 원인 후보와 권고 조치를 도출한다.
        val similar = alertRepository.findSimilarAlerts(
            alert["condId"] as? Int, alert["eqptCd"] as? String, alertId, 5
        )
        result["causeCandidates"] = buildCauseCandidates(similar)
        result["recentHistory"] = similar
        result["recommendation"] = buildRecommendation(alert, similar)

        return result.toMap() to mask
    }

    /**
     * 알림 확인 처리 (No.104 — 감사 로그 기록)
     *
     * @param actionNote 조치 내용
     */
    @Transactional
    fun acknowledge(alertId: Long, actionNote: String?): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.ALERT_LIST)

        val alert = alertRepository.findAlert(alertId)
            ?: throw ResourceNotFoundException("알림을 찾을 수 없습니다. [alertId=$alertId]")

        val updated = alertRepository.updateAck(alertId, actionNote, principal.userId)
        if (updated == 0) {
            throw BusinessRuleException("이미 확인 처리된 알림입니다. [현재 상태=${alert["ackState"]}]")
        }

        auditLogService.record(
            logType = "AUTO_GEN",
            menuId = MenuId.ALERT_LIST,
            targetDesc = "이상 알림 확인 [${alert["title"]}]",
            remark = "alertId=$alertId, 조치=${actionNote ?: "-"}"
        )

        val acked = alertRepository.findAlert(alertId)
            // 바로 위에서 확인 처리한 건이므로 사라졌다면 시스템 결함이다.
            ?: throw SystemErrorException("확인 처리 후 알림을 다시 읽지 못했습니다. [alertId=$alertId]")
        log.info("이상 알림 확인 처리 : alertId={} by={}", alertId, principal.userId)

        return mapOf("ackAt" to acked["ackAt"], "ackBy" to acked["ackBy"], "ackState" to acked["ackState"])
    }

    /**
     * 승격 대상 조회 (No.105)
     */
    @Transactional(readOnly = true)
    fun getEscalationTargets(): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.ALERT_LIST)

        val stages = alertRepository.findEscalationTargets().map { stage ->
            val groupId = stage["targetGroupId"] as? Int
            stage + mapOf(
                "targets" to (groupId?.let { alertRepository.findEscalationRecipients(it) } ?: emptyList<Any>())
            )
        }

        return mapOf("stages" to stages)
    }

    /**
     * 알림 발송 로그 조회 (No.106)
     *
     * 알림 조건(`alert-cond`) 또는 수신자 관리(`sys-recip`) 화면 권한이 있어야 한다. 알림 목록(`alert-list`)은
     * 전 부서가 가져서 넣으면 사실상 전원 허용이 된다 — 발송 로그는 수신자·채널을 드러내는 운영 정보라
     * 알림 설정 화면을 가진 사람에게만 보인다. 웹도 이 카드를 같은 화면 권한으로 숨긴다(2026-09-23, 부서 이름 비교 대신).
     */
    @Transactional(readOnly = true)
    fun getSendLogs(
        from: String?,
        to: String?,
        condId: Int?,
        channel: String?,
        page: Int?,
        size: Int?
    ): Pair<List<Map<String, Any?>>, PageMeta> {
        authorizationService.requireAnyMenu(MenuId.ALERT_COND, MenuId.SYS_RECIP)

        val (fromDate, toDate) = DateUtils.periodOf(from, to)
        val paging = PageRequestParam.of(page, size)

        val total = alertRepository.countSendLogs(fromDate, toDate, condId, channel)
        val rows = alertRepository.findSendLogs(fromDate, toDate, condId, channel, paging.limit, paging.offset)

        return rows to PageMeta.of(paging.page, paging.size, total)
    }

    // ---------------------------------------------------------------------------------
    // 내부 보조
    // ---------------------------------------------------------------------------------

    /**
     * 과거 유사 이력에서 원인 후보를 도출한다.
     *
     * 동일 조건으로 발생한 이력의 조치 내용을 빈도순으로 정리한다.
     */
    private fun buildCauseCandidates(similar: List<Map<String, Any?>>): List<Map<String, Any?>> {
        if (similar.isEmpty()) return emptyList()

        return similar
            .mapNotNull { it["actionNote"] as? String }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(3)
            .map { (note, cnt) ->
                mapOf(
                    "cause" to note,
                    "hitCnt" to cnt,
                    "confidence" to Math.round(cnt.toDouble() / similar.size * 100) / 100.0,
                    "basis" to "최근 90일 동일 조건 ${cnt}건에서 동일 조치"
                )
            }
    }

    /**
     * 조치 권고 문구를 구성한다.
     */
    private fun buildRecommendation(alert: Map<String, Any?>, similar: List<Map<String, Any?>>): String {
        val value = alert["basisValue"] as? Double
        val threshold = alert["threshold"] as? Double
        val target = alert["eqptNm"] ?: alert["eqptCd"] ?: alert["target"] ?: "대상"

        return when {
            value != null && threshold != null && value >= threshold * 1.5 ->
                "$target 임계값의 1.5배를 초과했다. 즉시 생산 중단 후 점검이 필요하다."
            similar.isNotEmpty() ->
                "$target 에서 최근 90일 ${similar.size}건의 동일 알림이 있었다. 반복 원인 점검을 권고한다."
            else ->
                "$target 상태를 확인하고 조치 결과를 기록하라."
        }
    }

    /**
     * period 파라미터를 조회 기간으로 변환한다.
     *
     * @param period today | 7d | 30d (기본 7d)
     */
    private fun resolvePeriod(period: String?): Pair<LocalDate, LocalDate> {
        val today = LocalDate.now()
        return when (period?.lowercase()) {
            "today" -> today to today
            "30d" -> today.minusDays(29) to today
            "90d" -> today.minusDays(89) to today
            else -> today.minusDays(6) to today
        }
    }
}
