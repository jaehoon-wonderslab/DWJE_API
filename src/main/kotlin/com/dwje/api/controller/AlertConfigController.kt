package com.dwje.api.controller

import com.dwje.api.common.response.ApiResponse
import com.dwje.api.model.request.AlertConditionRequest
import com.dwje.api.model.request.AlertConditionUpdateRequest
import com.dwje.api.model.request.EscalationRuleRequest
import com.dwje.api.model.request.RecipientGroupRequest
import com.dwje.api.model.request.RecipientGroupUpdateRequest
import com.dwje.api.model.request.RecipientRequest
import com.dwje.api.model.request.StateChangeRequest
import com.dwje.api.service.AlertConfigService
import com.dwje.api.service.AlertTestSendService
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
 * 접근 : 화면 권한 `alert-cond`(ax.tb_sys_dept_menu_perm) · 값 마스킹 : 없음
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
        @Parameter(description = "조건명·지표명·지표 설명 검색어(50자 이내)") @RequestParam(required = false) keyword: String?,
        @Parameter(description = "이 수신 그룹을 쓰는 조건만") @RequestParam(required = false) groupId: Int?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?
    ): ApiResponse<Map<String, Any?>> {
        val (rows, meta, masked) = alertConfigService.getConditions(severity, channel, state, page, size, keyword, groupId)
        return ApiResponse.page(mapOf("items" to rows), meta, masked)
    }

    /** 발송 조건 등록 (No.153) */
    @Operation(summary = "발송 조건 등록", description = "지표·임계값·채널·수신 그룹을 지정해 발송 조건을 등록한다.")
    @PostMapping
    fun createCondition(@Valid @RequestBody request: AlertConditionRequest): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(alertConfigService.createCondition(request), "발송 조건이 등록되었습니다.")

    /** 발송 조건 상세 (05 ALC-04) */
    @Operation(summary = "발송 조건 상세", description = "수정 화면을 채울 조건 전체 값(대상 설비·그룹·고급 설정·수정 시각)을 반환한다.")
    @GetMapping("/{condId}")
    fun condition(@PathVariable condId: Int): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(alertConfigService.getCondition(condId))

    /** 발송 조건 수정 (No.154) */
    @Operation(
        summary = "발송 조건 수정",
        description = "보낸 키만 바꾼다(누락 = 유지). updatedAt 을 보내면 그 사이 다른 사람이 고쳤을 때 409."
    )
    @PutMapping("/{condId}")
    fun updateCondition(
        @PathVariable condId: Int,
        @Valid @RequestBody request: AlertConditionUpdateRequest
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
        @RequestBody(required = false) request: StateChangeRequest?
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(
            alertConfigService.changeConditionState(condId, request?.on, request?.state),
            "상태가 변경되었습니다."
        )

    /** 발송 조건 테스트 (No.156) */
    @Operation(
        summary = "발송 조건 테스트",
        description = "조건의 수신 그룹·채널로 테스트 알림을 발송 대기열에 넣는다. 실제 발송은 알림 엔진이 1분 안에 한다. 같은 조건은 60초에 한 번."
    )
    @PostMapping("/{condId}/test-send")
    fun testSend(@PathVariable condId: Int): ApiResponse<Map<String, Any?>> {
        val (data, masked) = alertConfigService.testSendCondition(condId)
        return ApiResponse.ok(data, masked, AlertTestSendService.messageOf(data))
    }
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
    @Operation(summary = "수신자 관리 요약", description = "그룹 수, 수신·부재 인원, 계정 상태상 받을 수 없는 인원, 야간 수신 인원·야간 구간, 대상 그룹 없는 승격 단계 수를 반환한다.")
    @GetMapping("/alert-recipients/summary")
    fun recipientSummary(): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(alertConfigService.getRecipientSummary())

    /** 수신 그룹 목록 (No.158) */
    @Operation(summary = "수신 그룹 목록", description = "수신 그룹과 채널·구성원을 반환한다.")
    @GetMapping("/alert-recipient-groups")
    fun groups(
        @Parameter(description = "사용 중지 그룹 포함 여부(기본 false)") @RequestParam(required = false) includeInactive: Boolean?
    ): ApiResponse<Map<String, Any?>> {
        val (data, masked) = alertConfigService.getRecipientGroups(includeInactive ?: false)
        return ApiResponse.ok(data, masked)
    }

    /** 수신 그룹 상세 (06 RCP-02) */
    @Operation(summary = "수신 그룹 상세", description = "그룹 편집 화면을 채울 값(멤버·수신 가능 인원·사용 조건·부서 선택지·수정 시각)을 반환한다.")
    @GetMapping("/alert-recipient-groups/{groupId}")
    fun group(@PathVariable groupId: Int): ApiResponse<Map<String, Any?>> {
        val (data, masked) = alertConfigService.getRecipientGroup(groupId)
        return ApiResponse.ok(data, masked)
    }

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
        @Valid @RequestBody request: RecipientGroupUpdateRequest
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(alertConfigService.updateRecipientGroup(groupId, request), "수신 그룹이 수정되었습니다.")

    /** 수신 그룹 사용/중지 (06 RCP-08) */
    @Operation(summary = "수신 그룹 사용/중지", description = "본문 {on}. 사용 중 발송 조건·승격 규칙이 쓰는 그룹을 중지하면 409(data 에 conds·escStages).")
    @PatchMapping("/alert-recipient-groups/{groupId}/state")
    fun changeGroupState(
        @PathVariable groupId: Int,
        @RequestBody(required = false) request: StateChangeRequest?
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(alertConfigService.changeRecipientGroupState(groupId, request?.on), "수신 그룹 상태가 변경되었습니다.")

    /** 수신 그룹 테스트 발송 (No.161) */
    @Operation(summary = "수신 그룹 테스트 발송", description = "그룹 구성원에게 테스트 알림을 발송한다.")
    @PostMapping("/alert-recipient-groups/{groupId}/test-send")
    fun testSendGroup(@PathVariable groupId: Int): ApiResponse<Map<String, Any?>> {
        val (data, masked) = alertConfigService.testSendGroup(groupId)
        return ApiResponse.ok(data, masked, AlertTestSendService.messageOf(data))
    }

    /** 수신자 목록 (No.162) */
    @Operation(summary = "수신자 목록", description = "수신자 연락처와 수신 상태를 조회한다.")
    @GetMapping("/alert-recipients")
    fun recipients(
        @Parameter(description = "수신 상태 — RECV|ABSENT(수신|부재)") @RequestParam(required = false) state: String?,
        @Parameter(description = "이름·사번·부서 검색어(50자 이내)") @RequestParam(required = false) keyword: String?,
        @Parameter(description = "이 수신 그룹의 멤버만") @RequestParam(required = false) groupId: Int?,
        @Parameter(description = "계정 상태 — ACTIVE|SUSPENDED|PENDING|LOCKED") @RequestParam(required = false) userState: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?
    ): ApiResponse<Map<String, Any?>> {
        val (rows, meta, masked) = alertConfigService.getRecipients(state, page, size, keyword, groupId, userState)
        return ApiResponse.page(mapOf("items" to rows), meta, masked)
    }

    /** 수신자 등록 후보 (06 RCP-05) — 아직 수신자가 아닌 사용 중 계정, 미배정 제외 */
    @Operation(summary = "수신자 등록 후보", description = "수신자로 등록할 계정을 찾는다. 미배정 계정은 나오지 않는다(R-14). 메일 주소는 worker 권한이 없으면 null.")
    @GetMapping("/alert-recipients/candidates")
    fun recipientCandidates(
        @RequestParam(required = false) keyword: String?,
        @RequestParam(required = false) deptId: Int?,
        @Parameter(description = "최대 건수(1~100, 기본 20)") @RequestParam(required = false) size: Int?
    ): ApiResponse<Map<String, Any?>> {
        val (data, masked) = alertConfigService.getRecipientCandidates(keyword, deptId, size)
        return ApiResponse.ok(data, masked)
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
        @RequestBody(required = false) request: StateChangeRequest?
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(
            alertConfigService.changeRecipientState(recipientId, request?.state, request?.reason),
            "수신 상태가 변경되었습니다."
        )

    /** 수신자를 빼면 생기는 영향 (06 RCP-08) */
    @Operation(summary = "수신자 삭제 영향", description = "이 수신자를 빼면 그룹별 남는 수신 가능 인원, 받는 사람이 없어지는 그룹, 그 그룹을 쓰는 발송 조건·승격 단계.")
    @GetMapping("/alert-recipients/{recipientId}/impact")
    fun recipientImpact(@PathVariable recipientId: String): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(alertConfigService.getRecipientImpact(recipientId))

    /** 수신자 삭제 (06 RCP-08) */
    @Operation(
        summary = "수신자 삭제",
        description = "받는 사람이 없어지는 수신 그룹이 생기면 force=true 없이는 409(data 에 zeroGroups·affectedConds). 그룹 멤버십도 함께 지운다. 지난 발송 로그는 남는다."
    )
    @DeleteMapping("/alert-recipients/{recipientId}")
    fun deleteRecipient(
        @PathVariable recipientId: String,
        @RequestParam(required = false) force: Boolean?
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(alertConfigService.deleteRecipient(recipientId, force ?: false), "수신자를 삭제했습니다.")

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

