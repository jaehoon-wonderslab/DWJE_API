package com.dwje.api.model.request

import jakarta.validation.constraints.NotBlank
import java.math.BigDecimal

/**
 * 발송 조건 등록·수정 요청 — POST/PUT /api/v1/alert-conditions
 *
 * @param name         조건명
 * @param metricStdId  연결 지표 ID (ax.tb_met_metric_std)
 * @param op           비교 연산 (ALM_OP — GE/GT/LE/LT/EQ/RATE)
 * @param threshold    임계값
 * @param duration     지속 조건 (ALM_DURATION)
 * @param target       대상 범위 설명
 * @param severity     심각도 (ALM_SEVERITY)
 * @param channels     발송 채널 목록 (ALM_CHANNEL)
 * @param groupIds     수신 그룹 ID 목록
 * @param validWindow  유효 시간대 (ALM_WINDOW)
 * @param dedupMin     중복 억제 (ALM_DEDUP)
 */
data class AlertConditionRequest(
    @field:NotBlank(message = "조건명을 입력해 주세요.")
    val name: String,

    val metricStdId: Int? = null,
    val metricDesc: String? = null,
    val op: String = "GE",
    val threshold: BigDecimal? = null,
    val thresholdText: String? = null,
    val thresholdUnit: String? = null,
    val duration: String = "IMMEDIATE",
    val targetScope: String = "ALL_EQPT",
    val target: String? = null,

    @field:NotBlank(message = "심각도를 선택해 주세요.")
    val severity: String,

    val channels: List<String> = emptyList(),
    val groupIds: List<Int> = emptyList(),
    val validWindow: String = "ALWAYS",
    val dedupMin: String = "NONE",
    val msgTemplate: String? = null
)

/**
 * 수신 그룹 등록·수정 요청 — POST/PUT /api/v1/alert-recipient-groups
 *
 * @param name          그룹명
 * @param channels      발송 채널 목록
 * @param validWindow   유효 시간대
 * @param night         야간 수신 여부
 * @param memberEmpNos  구성원 사번 목록
 */
data class RecipientGroupRequest(
    @field:NotBlank(message = "그룹명을 입력해 주세요.")
    val name: String,

    val channels: List<String> = emptyList(),
    val validWindow: String = "ALWAYS",
    val night: Boolean = false,
    val deptId: Int? = null,
    val memberEmpNos: List<String> = emptyList()
)

/**
 * 수신자 등록·수정 요청 — POST/PUT /api/v1/alert-recipients
 *
 * @param empNo     사번
 * @param mail      메일 주소
 * @param hp        휴대전화 번호
 * @param messenger 메신저 ID
 * @param night     야간 수신 여부
 */
data class RecipientRequest(
    val empNo: String? = null,
    val mail: String? = null,
    val hp: String? = null,
    val messenger: String? = null,
    val night: Boolean? = null
)

/**
 * 당번 등록·수정 요청 — POST/PUT /api/v1/alert-duties
 *
 * @param from       시작일
 * @param to         종료일
 * @param groupId    수신 그룹 ID
 * @param mainEmpNo  주 담당자 사번
 * @param subEmpNo   대리 담당자 사번
 * @param reason     당번 사유 (ALM_DUTY_REASON)
 */
data class DutyRequest(
    val from: String? = null,
    val to: String? = null,
    val groupId: Int? = null,
    val mainEmpNo: String? = null,
    val subEmpNo: String? = null,
    val reason: String? = null,
    val remark: String? = null
)

/**
 * 승격 규칙 수정 요청 — PUT /api/v1/alert-escalation-rules
 *
 * @param stages 단계별 설정 목록
 */
data class EscalationRuleRequest(
    val stages: List<EscalationStageInput> = emptyList()
)

/**
 * 승격 단계 입력
 *
 * @param stage         승격 단계 (1~3)
 * @param waitMin       대기 시간(분)
 * @param targetGroupId 승격 대상 수신 그룹 ID
 */
data class EscalationStageInput(
    val stage: Int,
    val waitMin: Int? = null,
    val targetGroupId: Int? = null
)
