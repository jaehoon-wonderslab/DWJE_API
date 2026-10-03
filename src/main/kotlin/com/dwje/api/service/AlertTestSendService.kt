package com.dwje.api.service

import com.dwje.api.common.util.AuditType
import com.dwje.api.common.exception.ConflictingValueException
import com.dwje.api.common.util.DataField
import com.dwje.api.common.util.MaskingSupport
import com.dwje.api.config.AppProperties
import com.dwje.api.repository.AlertRepository
import org.springframework.stereotype.Service

/**
 * 알림 테스트 발송 공용 (05 ALC-03 · 06 RCP-03)
 *
 * 발송 로그에 직접 SENT 를 쓰지 않고 실제 발송 경로(대기열 `tb_alm_send_queue`)에 넣는다.
 * 실제 발송과 발송 로그 기록은 Alert_Engine `SendDispatcher` 가 1분 틱에 한다.
 * 그래야 메일 서버 설정·연락처 오류가 테스트에서 드러난다.
 *
 * 권한 검사는 호출자(AlertConfigService)가 첫 줄에서 한다. 응답에는 연락처를 어떤 경우에도 싣지 않는다.
 */
@Service
class AlertTestSendService(
    private val alertRepository: AlertRepository,
    private val targetResolver: AlertTargetResolver,
    private val auditLogService: AuditLogService,
    private val appProperties: AppProperties,
    private val engineMonitor: AlertEngineMonitor
) {

    /** 테스트 발송 대상 — 조건 또는 그룹 */
    data class TestSubject(
        val dedupKey: String,
        val condId: Int?,
        val severityCd: String,
        val title: String,
        val targetDesc: String?,
        val groupIds: List<Int>,
        val channels: List<String>,
        val bodyLines: List<String>,
        val menuId: String,
        val auditName: String
    )

    /** @return 응답 데이터와 마스킹된 항목 키 */
    fun send(subject: TestSubject, mask: MaskingSupport): Pair<Map<String, Any?>, List<String>> {
        if (alertRepository.lockAndCheckRecentTest(subject.dedupKey)) {
            throw ConflictingValueException("방금 테스트했습니다. 1분 뒤 다시 시도해 주십시오.")
        }

        val result = targetResolver.resolve(subject.groupIds, subject.channels)

        var alertId: Long? = null
        var queuedCnt = 0
        if (result.targets.isNotEmpty()) {
            val id = alertRepository.insertTestAlert(
                subject.condId, subject.severityCd, subject.title, subject.targetDesc, subject.dedupKey
            )
            alertId = id
            val body = buildBody(subject.bodyLines, id)
            result.targets.forEach { t ->
                queuedCnt += alertRepository.enqueueSend(id, t.groupId, t.userId, t.channel, t.destAddr, subject.title, body)
            }
        }

        auditLogService.record(
            logType = AuditType.CONFIG_CHANGE,
            menuId = subject.menuId,
            targetDesc = "${subject.auditName} 대기열 ${queuedCnt}건",
            remark = "제외 ${result.skipped.size}건"
        )

        val data = mapOf(
            "alertId" to alertId,
            "queuedCnt" to queuedCnt,
            // 이전 응답 키 — 한 릴리스 동안 queuedCnt 와 같은 값으로 함께 보낸다
            "sentCnt" to queuedCnt,
            "channels" to subject.channels,
            "recipients" to result.targets.map {
                mapOf(
                    "empNo" to it.userId,
                    "name" to mask.on(DataField.WORKER) { it.userNm },
                    "dept" to it.deptNm,
                    "channel" to it.channel,
                    "groupId" to it.groupId
                )
            },
            "skipped" to result.skipped.map {
                mapOf(
                    "empNo" to it.empNo,
                    "name" to it.name?.let { nm -> mask.on(DataField.WORKER) { nm } },
                    "reason" to it.reason,
                    "reasonNm" to it.reasonNm,
                    "groupId" to it.groupId
                )
            },
            "engine" to engineState()
        )
        return data to mask.maskedKeys()
    }

    /** 알림 엔진 상태 — 대기열은 엔진이 1분 틱에 보내므로, 엔진이 멈춰 있으면 테스트가 「넣었지만 안 간다」 */
    private fun engineState(): Map<String, Any?> = engineMonitor.state()

    /** 본문 — 마지막 줄에 알림 링크(웹 주소 설정이 없으면 생략) */
    private fun buildBody(lines: List<String>, alertId: Long): String {
        val base = appProperties.alert.webBaseUrl.trim().trimEnd('/')
        val link = if (base.isEmpty()) emptyList() else listOf("알림 보기: $base/alert/list?alertId=$alertId")
        return (lines + "테스트 발송입니다. 실제 이상이 아닙니다." + link).joinToString("\n")
    }

    companion object {
        /** 응답 메시지 — 대기열 건수에 따라 */
        fun messageOf(data: Map<String, Any?>): String {
            val n = data["queuedCnt"] as? Int ?: 0
            return if (n > 0) "테스트 알림 ${n}건을 발송 대기열에 넣었습니다."
            else "발송 대상이 없어 테스트 알림을 만들지 않았습니다."
        }

        /** 조건 테스트 본문 줄 — 민감 항목(blind_field_key)이 걸린 조건은 임계값을 `***` 로 쓴다(엔진 MessageRenderer MASK 와 같은 표기) */
        fun conditionBodyLines(cond: Map<String, Any?>): List<String> {
            val blind = !(cond["blindFieldKey"] as? String).isNullOrBlank()
            val threshold = if (blind) "***" else listOfNotNull(cond["thresholdText"] as? String, cond["thresholdUnit"] as? String).joinToString(" ")
            return listOf(
                "조건: ${cond["name"]}",
                "지표: ${cond["metricNm"] ?: cond["metricDesc"] ?: "-"}",
                "비교: ${cond["opNm"] ?: cond["op"]} $threshold",
                "심각도: ${cond["severityNm"] ?: cond["severity"]}"
            )
        }

        fun conditionKey(condId: Int) = "TEST|COND|$condId"
        fun groupKey(groupId: Int) = "TEST|GROUP|$groupId"
    }
}
