package com.dwje.api.controller

import com.dwje.api.common.response.ApiResponse
import com.dwje.api.model.request.AlertConditionRequest
import com.dwje.api.model.request.EscalationRuleRequest
import com.dwje.api.model.request.RecipientGroupRequest
import com.dwje.api.model.request.RecipientRequest
import com.dwje.api.model.request.StateChangeRequest
import com.dwje.api.service.AlertConfigService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 이상 알림 발송 조건 관리 컨트롤러 (SY-04)
 *
 * 접근 부서 : 전산팀 · 통합관리자
 */
@RestController
@RequestMapping("/api/v1/alert-conditions")
@Tag(name = "09. 시스템관리 - 알림")
class AlertConditionController(
    private val alertConfigService: AlertConfigService
) {

    /** 발송 조건 요약 (No.151) */
    @Operation(summary = "발송 조건 요약", description = "활성 조건 수와 당일 채널별 발송·중복 억제 현황을 반환한다.")
    @GetMapping("/summary")
    fun summary(): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(alertConfigService.getConditionSummary())

    /** 발송 조건 목록 조회 (No.152) */
    @Operation(summary = "발송 조건 목록 조회", description = "심각도·채널·활성 상태로 발송 조건을 조회한다.")
    @GetMapping
    fun conditions(
        @Parameter(description = "심각도 — CRIT|WARN|LOW") @RequestParam(required = false) severity: String?,
        @Parameter(description = "채널 — MAIL|POPUP|SMS|MSG") @RequestParam(required = false) channel: String?,
        @Parameter(description = "활성 상태 — on|off") @RequestParam(required = false) state: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?
    ): ApiResponse<Map<String, Any?>> {
        val (rows, meta) = alertConfigService.getConditions(severity, channel, state, page, size)
        return ApiResponse.page(mapOf("items" to rows), meta)
    }

    /** 발송 조건 등록 (No.153) */
    @Operation(summary = "발송 조건 등록", description = "지표·임계값·채널·수신 그룹을 지정해 발송 조건을 등록한다.")
    @PostMapping
    fun createCondition(@Valid @RequestBody request: AlertConditionRequest): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(alertConfigService.createCondition(request), "발송 조건이 등록되었습니다.")

    /** 발송 조건 수정 (No.154) */
    @Operation(summary = "발송 조건 수정", description = "발송 조건을 수정한다.")
    @PutMapping("/{condId}")
    fun updateCondition(
        @PathVariable condId: Int,
        @Valid @RequestBody request: AlertConditionRequest
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(alertConfigService.updateCondition(condId, request), "발송 조건이 수정되었습니다.")

    /** 발송 조건 삭제 */
    @Operation(
        summary = "발송 조건 삭제",
        description = "잘못 등록한 조건을 삭제한다. 이미 알림이 발생한 조건은 삭제할 수 없다(중지를 쓴다)."
    )
    @DeleteMapping("/{condId}")
    fun deleteCondition(@PathVariable condId: Int): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(alertConfigService.deleteCondition(condId), "발송 조건이 삭제되었습니다.")

    /** 발송 조건 활성/중지 (No.155) */
    @Operation(summary = "발송 조건 활성/중지", description = "발송 조건의 활성 상태를 전환한다.")
    @PatchMapping("/{condId}/state")
    fun changeConditionState(
        @PathVariable condId: Int,
        @Valid @RequestBody request: StateChangeRequest
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(
            alertConfigService.changeConditionState(condId, request.on ?: (request.state == "on")),
            "상태가 변경되었습니다."
        )

    /** 발송 조건 테스트 (No.156) */
    @Operation(summary = "발송 조건 테스트", description = "조건에 연결된 수신 그룹에 테스트 알림을 발송한다.")
    @PostMapping("/{condId}/test-send")
    fun testSend(@PathVariable condId: Int): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(alertConfigService.testSendCondition(condId), "테스트 발송이 완료되었습니다.")
}

/**
 * 알림 수신자 관리 컨트롤러 (SY-05)
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "09. 시스템관리 - 알림")
class AlertRecipientController(
    private val alertConfigService: AlertConfigService
) {

    /** 수신자 관리 요약 (No.157) */
    @Operation(summary = "수신자 관리 요약", description = "그룹 수, 수신/부재 인원, 야간 수신자, 활성 당번 수를 반환한다.")
    @GetMapping("/alert-recipients/summary")
    fun recipientSummary(): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(alertConfigService.getRecipientSummary())

    /** 수신 그룹 목록 (No.158) */
    @Operation(summary = "수신 그룹 목록", description = "수신 그룹과 채널·구성원을 반환한다.")
    @GetMapping("/alert-recipient-groups")
    fun groups(): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(alertConfigService.getRecipientGroups())

    /** 수신 그룹 등록 (No.159) */
    @Operation(summary = "수신 그룹 등록", description = "수신 그룹을 등록하고 채널·구성원을 지정한다.")
    @PostMapping("/alert-recipient-groups")
    fun createGroup(@Valid @RequestBody request: RecipientGroupRequest): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(alertConfigService.createRecipientGroup(request), "수신 그룹이 등록되었습니다.")

    /** 수신 그룹 수정 (No.160) */
    @Operation(summary = "수신 그룹 수정", description = "수신 그룹 정보를 수정한다.")
    @PutMapping("/alert-recipient-groups/{groupId}")
    fun updateGroup(
        @PathVariable groupId: Int,
        @Valid @RequestBody request: RecipientGroupRequest
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(alertConfigService.updateRecipientGroup(groupId, request), "수신 그룹이 수정되었습니다.")

    /** 수신 그룹 테스트 발송 (No.161) */
    @Operation(summary = "수신 그룹 테스트 발송", description = "그룹 구성원에게 테스트 알림을 발송한다.")
    @PostMapping("/alert-recipient-groups/{groupId}/test-send")
    fun testSendGroup(@PathVariable groupId: Int): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(alertConfigService.testSendGroup(groupId), "테스트 발송이 완료되었습니다.")

    /** 수신자 목록 (No.162) */
    @Operation(summary = "수신자 목록", description = "수신자 연락처와 수신 상태를 조회한다.")
    @GetMapping("/alert-recipients")
    fun recipients(
        @Parameter(description = "수신 상태 — 수신|부재") @RequestParam(required = false) state: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?
    ): ApiResponse<Map<String, Any?>> {
        val (rows, meta) = alertConfigService.getRecipients(state, page, size)
        return ApiResponse.page(mapOf("items" to rows), meta)
    }

    /** 수신자 등록 (No.163) */
    @Operation(summary = "수신자 등록", description = "계정을 알림 수신자로 등록한다.")
    @PostMapping("/alert-recipients")
    fun createRecipient(@Valid @RequestBody request: RecipientRequest): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(alertConfigService.createRecipient(request), "수신자가 등록되었습니다.")

    /** 수신자 수정 (No.164) */
    @Operation(summary = "수신자 수정", description = "수신자 연락처를 수정한다.")
    @PutMapping("/alert-recipients/{recipientId}")
    fun updateRecipient(
        @PathVariable recipientId: String,
        @Valid @RequestBody request: RecipientRequest
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(alertConfigService.updateRecipient(recipientId, request), "수신자가 수정되었습니다.")

    /** 수신/부재 토글 (No.165) */
    @Operation(summary = "수신/부재 토글", description = "수신자의 수신 상태를 전환한다.")
    @PatchMapping("/alert-recipients/{recipientId}/state")
    fun changeRecipientState(
        @PathVariable recipientId: String,
        @Valid @RequestBody request: StateChangeRequest
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(
            alertConfigService.changeRecipientState(recipientId, request.state ?: "RECV"),
            "수신 상태가 변경되었습니다."
        )

    /**
     * 승격 규칙 조회 (No.169)
     *
     * 수신자 관리 화면에서는 걷어냈다(2026-09-16). 규칙 자체는 알림 현황의
     * 「승격 대상」(GET /alerts/escalation-targets)이 그대로 읽으므로 조회·수정 API 는 남긴다.
     */
    @Operation(summary = "승격 규칙 조회", description = "승격 단계별 대기 시간과 승격 대상 그룹을 반환한다.")
    @GetMapping("/alert-escalation-rules")
    fun escalationRules(): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(alertConfigService.getEscalationRules())

    /** 승격 규칙 수정 (No.169) */
    @Operation(summary = "승격 규칙 수정", description = "승격 단계별 대기 시간과 대상 그룹을 수정한다.")
    @PutMapping("/alert-escalation-rules")
    fun updateEscalationRules(@Valid @RequestBody request: EscalationRuleRequest): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(alertConfigService.updateEscalationRules(request), "승격 규칙이 수정되었습니다.")
}
