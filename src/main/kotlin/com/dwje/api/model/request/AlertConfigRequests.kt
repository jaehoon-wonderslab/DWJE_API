package com.dwje.api.model.request

import jakarta.validation.constraints.NotBlank
import java.math.BigDecimal
import java.util.Optional

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
 * @param validWindow  유효 시간대 (ALM_WINDOW) — `ONCE`(지정 시각 1회)는 400
 * @param dedupMin     중복 억제 (ALM_DEDUP)
 *
 * 고급 설정 7가지(msgTemplate · scopeDim · windowTime · evalIntervalSec · ignoreWindow · autoClose · escalation)는
 * 2026-10-03 에 없앴다. 옛 화면이 보내도 400 이 나지 않게 키는 남겨 받기만 하고 버린다(FAIL_ON_UNKNOWN_PROPERTIES).
 * 모든 조건은 고정값으로 동작한다 — 평가 단위 지표 수집 단위 · 평가 주기 60초 · 시간대 지킴 · 자동 해제 없음 ·
 * 메시지는 서버 기본 틀 · 조건별 승격 없음. 지속 조건 일 마감(DAY_CLOSE) · 일 1회(DAY_ONCE)는 항상 08:00 기준이다.
 */
data class AlertConditionRequest(
    @field:NotBlank(message = "조건명을 입력해 주세요.")
    val name: String,

    val metricStdId: Int? = null,
    val metricDesc: String? = null,
    val op: String = "GE",
    /** 임계값 표기(문자열) — 숫자만이면 값으로도 쓴다. 숫자 JSON 도 받는다(옛 본문) */
    val threshold: String? = null,
    val thresholdVal: BigDecimal? = null,
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
    @Deprecated("고급 설정 제거(2026-10-03) — 옛 WEB 번들 호환으로 받기만 하고 값은 무시. 모든 WEB 배포 뒤 삭제")
    @field:io.swagger.v3.oas.annotations.media.Schema(deprecated = true, description = "사용 중지 — 보내도 무시한다(발송 조건 고급 설정 제거, 2026-10-03)")
    val msgTemplate: String? = null,
    /** 개별 설비 선택(targetScope=PICK)일 때 설비 코드 1~500개 (ALC-05) */
    val pickTargets: List<String>? = null,
    @Deprecated("고급 설정 제거(2026-10-03) — 옛 WEB 번들 호환으로 받기만 하고 값은 무시. 모든 WEB 배포 뒤 삭제")
    @field:io.swagger.v3.oas.annotations.media.Schema(deprecated = true, description = "사용 중지 — 보내도 무시한다(발송 조건 고급 설정 제거, 2026-10-03)")
    val scopeDim: String? = null,
    @Deprecated("고급 설정 제거(2026-10-03) — 옛 WEB 번들 호환으로 받기만 하고 값은 무시. 모든 WEB 배포 뒤 삭제")
    @field:io.swagger.v3.oas.annotations.media.Schema(deprecated = true, description = "사용 중지 — 보내도 무시한다(발송 조건 고급 설정 제거, 2026-10-03)")
    val windowTime: String? = null,
    @Deprecated("고급 설정 제거(2026-10-03) — 옛 WEB 번들 호환으로 받기만 하고 값은 무시. 모든 WEB 배포 뒤 삭제")
    @field:io.swagger.v3.oas.annotations.media.Schema(deprecated = true, description = "사용 중지 — 보내도 무시한다(발송 조건 고급 설정 제거, 2026-10-03)")
    val evalIntervalSec: Int? = null,
    @Deprecated("고급 설정 제거(2026-10-03) — 옛 WEB 번들 호환으로 받기만 하고 값은 무시. 모든 WEB 배포 뒤 삭제")
    @field:io.swagger.v3.oas.annotations.media.Schema(deprecated = true, description = "사용 중지 — 보내도 무시한다(발송 조건 고급 설정 제거, 2026-10-03)")
    val ignoreWindow: Boolean? = null,
    @Deprecated("고급 설정 제거(2026-10-03) — 옛 WEB 번들 호환으로 받기만 하고 값은 무시. 모든 WEB 배포 뒤 삭제")
    @field:io.swagger.v3.oas.annotations.media.Schema(deprecated = true, description = "사용 중지 — 보내도 무시한다(발송 조건 고급 설정 제거, 2026-10-03)")
    val autoClose: Boolean? = null,
    @Deprecated("고급 설정 제거(2026-10-03) — 옛 WEB 번들 호환으로 받기만 하고 값은 무시. 모든 WEB 배포 뒤 삭제")
    @field:io.swagger.v3.oas.annotations.media.Schema(deprecated = true, description = "사용 중지 — 보내도 무시한다(발송 조건 고급 설정 제거, 2026-10-03)")
    val escalation: List<CondEscalationInput>? = null
)

/** 조건의 승격 단계 켬/끔 — 사용 중지(2026-10-03, 받고 버림). 옛 본문을 그대로 읽을 수 있게 모양만 남긴다 */
data class CondEscalationInput(
    val stage: Int,
    val on: Boolean
)

/**
 * 발송 조건 수정 요청 — PUT /api/v1/alert-conditions/{condId} (05 ALC-04)
 *
 * 보낸 키만 바꾼다. 키를 빼면 저장된 값을 그대로 둔다(누락 = 유지).
 * - 문자열 `""` : 메시지 틀·대상 설명은 서버 기본값으로, 그 밖의 필수 항목은 400
 * - 목록 `[]`   : 채널·수신 그룹은 400(1개 이상 필수)
 * - `thresholdUnit` 은 JSON null 을 보내면 비운다(Optional — 키 없음 = 유지, null = 비우기)
 * - `threshold`(문자열)는 임계 표기, `thresholdVal`(숫자)은 값. 표기가 숫자뿐이고 값이 없으면 표기를 값으로도 쓴다
 * - `updatedAt` 을 보내면 저장된 수정 시각과 다를 때 409(다른 사용자가 먼저 수정)
 * - 고급 설정 7가지는 받고 버린다(등록 요청 설명 참고)
 */
data class AlertConditionUpdateRequest(
    val name: String? = null,
    val metricStdId: Int? = null,
    val metricDesc: String? = null,
    val op: String? = null,
    /** 임계값 표기(문자열). 숫자만이면 thresholdVal 이 없을 때 값으로도 쓴다 */
    val threshold: String? = null,
    val thresholdVal: BigDecimal? = null,
    val thresholdText: String? = null,
    val thresholdUnit: Optional<String>? = null,
    val duration: String? = null,
    val targetScope: String? = null,
    val target: String? = null,
    val pickTargets: List<String>? = null,
    val severity: String? = null,
    val channels: List<String>? = null,
    val groupIds: List<Int>? = null,
    val validWindow: String? = null,
    val dedupMin: String? = null,
    @Deprecated("고급 설정 제거(2026-10-03) — 옛 WEB 번들 호환으로 받기만 하고 값은 무시. 모든 WEB 배포 뒤 삭제")
    @field:io.swagger.v3.oas.annotations.media.Schema(deprecated = true, description = "사용 중지 — 보내도 무시한다(발송 조건 고급 설정 제거, 2026-10-03)")
    val msgTemplate: String? = null,
    val updatedAt: String? = null,
    @Deprecated("고급 설정 제거(2026-10-03) — 옛 WEB 번들 호환으로 받기만 하고 값은 무시. 모든 WEB 배포 뒤 삭제")
    @field:io.swagger.v3.oas.annotations.media.Schema(deprecated = true, description = "사용 중지 — 보내도 무시한다(발송 조건 고급 설정 제거, 2026-10-03)")
    val scopeDim: String? = null,
    @Deprecated("고급 설정 제거(2026-10-03) — 옛 WEB 번들 호환으로 받기만 하고 값은 무시. 모든 WEB 배포 뒤 삭제")
    @field:io.swagger.v3.oas.annotations.media.Schema(deprecated = true, description = "사용 중지 — 보내도 무시한다(발송 조건 고급 설정 제거, 2026-10-03)")
    val windowTime: Optional<String>? = null,
    @Deprecated("고급 설정 제거(2026-10-03) — 옛 WEB 번들 호환으로 받기만 하고 값은 무시. 모든 WEB 배포 뒤 삭제")
    @field:io.swagger.v3.oas.annotations.media.Schema(deprecated = true, description = "사용 중지 — 보내도 무시한다(발송 조건 고급 설정 제거, 2026-10-03)")
    val evalIntervalSec: Int? = null,
    @Deprecated("고급 설정 제거(2026-10-03) — 옛 WEB 번들 호환으로 받기만 하고 값은 무시. 모든 WEB 배포 뒤 삭제")
    @field:io.swagger.v3.oas.annotations.media.Schema(deprecated = true, description = "사용 중지 — 보내도 무시한다(발송 조건 고급 설정 제거, 2026-10-03)")
    val ignoreWindow: Boolean? = null,
    @Deprecated("고급 설정 제거(2026-10-03) — 옛 WEB 번들 호환으로 받기만 하고 값은 무시. 모든 WEB 배포 뒤 삭제")
    @field:io.swagger.v3.oas.annotations.media.Schema(deprecated = true, description = "사용 중지 — 보내도 무시한다(발송 조건 고급 설정 제거, 2026-10-03)")
    val autoClose: Boolean? = null,
    @Deprecated("고급 설정 제거(2026-10-03) — 옛 WEB 번들 호환으로 받기만 하고 값은 무시. 모든 WEB 배포 뒤 삭제")
    @field:io.swagger.v3.oas.annotations.media.Schema(deprecated = true, description = "사용 중지 — 보내도 무시한다(발송 조건 고급 설정 제거, 2026-10-03)")
    val escalation: List<CondEscalationInput>? = null
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
 * 수신 그룹 수정 요청 — PUT /api/v1/alert-recipient-groups/{groupId} (06 RCP-02)
 *
 * 보낸 키만 바꾼다(누락 = 유지).
 * - `memberEmpNos: []` 는 전원 제외, `channels: []` 는 400
 * - `deptId` 는 JSON null 이면 담당 부서를 비운다(Optional — 키 없음 = 유지)
 * - `updatedAt` 을 보내면 저장된 수정 시각과 다를 때 409
 */
data class RecipientGroupUpdateRequest(
    val name: String? = null,
    val channels: List<String>? = null,
    val validWindow: String? = null,
    val night: Boolean? = null,
    val deptId: Optional<Int>? = null,
    val memberEmpNos: List<String>? = null,
    val updatedAt: String? = null
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

