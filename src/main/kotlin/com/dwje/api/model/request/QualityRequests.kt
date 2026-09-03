package com.dwje.api.model.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty

/**
 * 품질 보고서 초안 생성 요청 — POST /api/v1/quality/reports/draft
 *
 * @param formId            보고서 양식 ID
 * @param lotNo             대상 LOT 번호
 * @param occurDate         발생일 (YYYY-MM-DD)
 * @param disclosurePolicy  공개 정책 (고객사별)
 */
data class QualityReportDraftRequest(
    val formId: Int? = null,
    val lotNo: String? = null,
    val occurDate: String? = null,
    val disclosurePolicy: String? = null
)

/**
 * 마스킹 해제 요청 — POST /api/v1/quality/reports/{reportId}/unmask-request
 *
 * @param fields 해제를 요청할 데이터 항목 key 목록
 * @param reason 요청 사유
 */
data class UnmaskRequest(
    @field:NotEmpty(message = "해제할 항목을 선택해 주세요.")
    val fields: List<String> = emptyList(),

    @field:NotBlank(message = "요청 사유를 입력해 주세요.")
    val reason: String
)

/**
 * 증빙 이미지 첨부 요청 — POST /api/v1/quality/reports/{reportId}/evidence-images
 *
 * @param imageIds 첨부할 이미지 식별자 목록
 * @param images   이미지 상세 (name, nasPath, defectCd, lotNo)
 */
data class EvidenceImageRequest(
    val imageIds: List<String> = emptyList(),
    val images: List<EvidenceImageInput> = emptyList()
)

/**
 * 증빙 이미지 입력
 */
data class EvidenceImageInput(
    val name: String? = null,
    val nasPath: String? = null,
    val defectCd: String? = null,
    val lotNo: String? = null
)

/**
 * 보고서 양식 등록·수정 요청 — POST/PUT /api/v1/quality/report-forms
 *
 * @param name             양식명
 * @param type             양식 유형 (RPT_FORM_TYPE)
 * @param customerId       고객사 ID
 * @param disclosurePolicy 공개 정책
 * @param fields           항목 정의 목록
 */
data class ReportFormRequest(
    @field:NotBlank(message = "양식명을 입력해 주세요.")
    val name: String,

    @field:NotBlank(message = "양식 유형을 선택해 주세요.")
    val type: String,

    val customerId: Int? = null,
    val disclosurePolicy: String? = null,
    val reportId: String? = null,
    val fields: List<ReportFormFieldInput> = emptyList()
)

/**
 * 보고서 양식 항목 정의 입력
 *
 * @param field         항목 코드
 * @param label         항목명
 * @param required      필수 여부
 * @param dataFieldKey  연결된 데이터 접근 항목 key (마스킹 대상 지정)
 */
data class ReportFormFieldInput(
    val field: String? = null,
    val label: String? = null,
    val required: Boolean = false,
    val dataFieldKey: String? = null,
    val remark: String? = null
)
