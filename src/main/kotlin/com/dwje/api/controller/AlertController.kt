package com.dwje.api.controller

import com.dwje.api.common.response.ApiResponse
import com.dwje.api.model.request.ReasonRequest
import com.dwje.api.service.AlertService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 이상 알림 API 컨트롤러 (AL-01)
 *
 * 전 부서가 접근하며, 알림 확인 처리는 감사 로그에 기록된다.
 */
@RestController
@RequestMapping("/api/v1/alerts")
@Tag(name = "06. 이상 알림")
class AlertController(
    private val alertService: AlertService
) {

    /**
     * 알림 목록 조회 (No.102)
     *
     * @param type   심각도 — CRIT | WARN | LOW
     * @param period 조회 기간 — today | 7d | 30d
     */
    @Operation(summary = "알림 목록 조회", description = "심각도·설비·기간별 이상 알림 목록을 조회한다.")
    @GetMapping
    fun alerts(
        @Parameter(description = "심각도 — CRIT|WARN|LOW") @RequestParam(required = false) type: String?,
        @RequestParam(required = false) eqptCd: String?,
        @Parameter(description = "조회 기간 — today|7d|30d") @RequestParam(required = false) period: String?,
        @Parameter(description = "확인 상태 — OPEN|ACKED|CLOSED") @RequestParam(required = false) ackState: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?
    ): ApiResponse<Map<String, Any?>> {
        val (rows, meta) = alertService.getAlerts(type, eqptCd, period, ackState, page, size)
        return ApiResponse.page(mapOf("items" to rows), meta)
    }

    /**
     * 승격 대상 조회 (No.105)
     *
     * 경로 충돌을 피하기 위해 {alertId} 매핑보다 먼저 선언한다.
     */
    @Operation(summary = "승격 대상 조회", description = "승격 단계별 대기 건수와 수신 대상자를 반환한다.")
    @GetMapping("/escalation-targets")
    fun escalationTargets(): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(alertService.getEscalationTargets())

    /**
     * 알림 발송 로그 조회 (No.106)
     */
    @Operation(summary = "알림 발송 로그 조회", description = "채널·조건별 발송 결과와 지연 시간을 조회한다.")
    @GetMapping("/send-logs")
    fun sendLogs(
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) condId: Int?,
        @Parameter(description = "발송 채널 — MAIL|POPUP|SMS|MSG") @RequestParam(required = false) channel: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?
    ): ApiResponse<Map<String, Any?>> {
        val (rows, meta) = alertService.getSendLogs(from, to, condId, channel, page, size)
        return ApiResponse.page(mapOf("items" to rows), meta)
    }

    /**
     * 알림 상세 조회 (No.103)
     */
    @Operation(summary = "알림 상세 조회", description = "발생 근거·임계값·원인 후보·권고 조치를 반환한다.")
    @GetMapping("/{alertId}")
    fun alert(@PathVariable alertId: Long): ApiResponse<Map<String, Any?>> {
        val (data, mask) = alertService.getAlert(alertId)
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    /**
     * 알림 확인 처리 (No.104)
     */
    @Operation(summary = "알림 확인 처리", description = "알림을 확인 처리하고 조치 내용을 기록한다.")
    @PostMapping("/{alertId}/ack")
    fun ack(
        @PathVariable alertId: Long,
        @Valid @RequestBody(required = false) request: ReasonRequest?
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(alertService.acknowledge(alertId, request?.actionNote ?: request?.reason), "확인 처리되었습니다.")
}
