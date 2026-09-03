package com.dwje.api.model.request

import jakarta.validation.constraints.NotBlank
import java.math.BigDecimal

/**
 * 폐기 보고서 초안 생성·수정 요청 — POST/PUT /api/v1/reports/scrap/drafts
 *
 * @param step             현재 위저드 단계 (1~5)
 * @param cond             조회 조건 (from, to, processId, modelCd, defectTypeCd, originType)
 * @param pickedVoucherIds 선택한 MES 전표 ID 목록
 * @param form             결재 머리부 입력 값
 * @param review           검토 의견
 */
data class ScrapDraftRequest(
    val step: Int? = null,
    val cond: ScrapCondition? = null,
    val pickedVoucherIds: List<String> = emptyList(),
    val form: Map<String, String?> = emptyMap(),
    val review: Map<String, String?> = emptyMap()
)

/**
 * 폐기 전표 조회 조건
 */
data class ScrapCondition(
    val from: String? = null,
    val to: String? = null,
    val processId: String? = null,
    val modelCd: String? = null,
    val defectTypeCd: String? = null,
    val originType: String? = null
)

/**
 * 수기 폐기 행 추가 요청 — POST /api/v1/reports/scrap/drafts/{draftId}/manual-rows
 *
 * @param model   모델 코드
 * @param process 발생 공정
 * @param reason  폐기 사유
 * @param kind    폐기 구분 — LOSS(공정 Loss) | DEAD_STOCK(불용재고)
 * @param qty     폐기 수량
 */
data class ScrapManualRowRequest(
    @field:NotBlank(message = "모델을 입력해 주세요.")
    val model: String,

    val process: String? = null,

    @field:NotBlank(message = "폐기 사유를 입력해 주세요.")
    val reason: String,

    val kind: String = "LOSS",
    val qty: BigDecimal = BigDecimal.ZERO,
    val itemCd: String? = null,
    val occurDate: String? = null
)

/**
 * 단가 수기 조정 요청 — PUT /api/v1/reports/scrap/drafts/{draftId}/unit-price
 *
 * @param key       조정 기준 — model | process
 * @param keyValue  기준 값 (모델 코드 또는 공정 코드)
 * @param unitPrice 조정 단가
 * @param reason    조정 사유
 */
data class ScrapUnitPriceRequest(
    @field:NotBlank(message = "조정 기준을 선택해 주세요.")
    val key: String,

    @field:NotBlank(message = "기준 값을 입력해 주세요.")
    val keyValue: String,

    val unitPrice: BigDecimal = BigDecimal.ZERO,
    val reason: String? = null
)

/**
 * 검토 부서·결재선 지정 요청 — PUT /api/v1/reports/scrap/drafts/{draftId}/approval-line
 *
 * @param depts          검토 부서 목록
 * @param appr           결재선 (draft/review/approve 담당자 사번)
 * @param due            검토 기한 (YYYY-MM-DD)
 * @param notifyChannels 알림 채널 목록 (MAIL/SMS/MSG/POPUP)
 */
data class ApprovalLineRequest(
    val depts: List<ApprovalDeptInput> = emptyList(),
    val appr: Map<String, String?> = emptyMap(),
    val due: String? = null,
    val notifyChannels: List<String> = emptyList()
)

/**
 * 검토 부서 입력
 *
 * @param deptId  부서 ID
 * @param manager 담당자 사번
 */
data class ApprovalDeptInput(
    val deptId: Int? = null,
    val dept: String? = null,
    val manager: String? = null
)
