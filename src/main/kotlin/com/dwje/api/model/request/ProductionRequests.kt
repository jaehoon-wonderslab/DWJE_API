package com.dwje.api.model.request

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank

/**
 * 보고서 항목 보정 요청 — PUT /api/v1/production/daily-reports/{reportId}
 *
 * @param sections 섹션·항목 값 목록
 * @param remark   보정 사유
 */
data class ReportCorrectionRequest(
    val sections: List<ReportSectionInput> = emptyList(),
    val remark: String? = null
)

/**
 * 보고서 섹션 입력
 *
 * @param section 섹션 구분 (HEADER / RESULT / CONDITION / CAUSE / TRACE / ACTION / BODY)
 * @param fields  항목 목록
 */
data class ReportSectionInput(
    val section: String = "BODY",
    val fields: List<ReportFieldInput> = emptyList()
)

/**
 * 보고서 항목 입력
 *
 * @param fieldCode 항목 코드
 * @param fieldNm   항목명
 * @param value     항목 값
 * @param origin    기입 출처 (MES / AI / MANUAL)
 */
data class ReportFieldInput(
    val fieldCode: String? = null,
    val fieldNm: String? = null,
    val value: String? = null,
    val origin: String? = null
)

/**
 * 보고서 초안 재생성 요청 — POST /api/v1/production/daily-reports/draft/regenerate
 *
 * @param targetDate 대상 일자 (YYYY-MM-DD)
 */
data class ReportRegenerateRequest(
    val targetDate: String? = null
)

/**
 * 보고서 복제 요청 — POST /api/v1/production/daily-reports/{reportId}/copy
 *
 * @param targetDate 복제 대상 일자
 */
data class ReportCopyRequest(
    @field:NotBlank(message = "복제할 대상 일자를 입력해 주세요.")
    val targetDate: String
)

/**
 * 비가동 사유 등록 요청 — POST /api/v1/production/downtimes
 *
 * @param eqptCd   설비 코드
 * @param stopAt   정지 시각 (yyyy-MM-dd HH:mm:ss)
 * @param resumeAt 재가동 시각
 * @param reasonCd 비가동 사유 코드 (DOWN_REASON)
 * @param remark   비고
 */
data class DowntimeCreateRequest(
    @field:NotBlank(message = "설비를 선택해 주세요.")
    val eqptCd: String,

    @field:NotBlank(message = "정지 시각을 입력해 주세요.")
    val stopAt: String,

    val resumeAt: String? = null,

    @field:NotBlank(message = "비가동 사유를 선택해 주세요.")
    val reasonCd: String,

    val remark: String? = null
)

/**
 * 비가동 사유 수정 요청 — PUT /api/v1/production/downtimes/{downtimeId}
 */
data class DowntimeUpdateRequest(
    val reasonCd: String? = null,
    val remark: String? = null,
    val resumeAt: String? = null
)

/**
 * 제품·공정별 일목표 등록·수정 요청 — POST/PUT /api/v1/production/day-targets
 *
 * 목표는 `applyFrom` 부터 **다음 적용일 전까지** 유효하다. 종료일은 두지 않는다 —
 * 끝을 적게 하면 구간이 끊기거나 겹친 상태를 막을 방법이 따로 필요해진다.
 *
 * @param product   제품 코드 (등록 시 필수, 수정 시 무시)
 * @param processId 작업장(공정) 코드 (등록 시 필수, 수정 시 무시)
 * @param applyFrom 적용 시작일 (`yyyy-MM-dd`, 필수)
 * @param targetQty 일목표 수량 (필수, 0 이상)
 * @param remark    비고
 */
data class DayTargetRequest(
    val product: String? = null,
    val processId: String? = null,
    val applyFrom: String? = null,
    @field:Min(0, message = "일목표는 0 이상이어야 합니다.")
    val targetQty: Long? = null,
    val remark: String? = null
)
