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

/**
 * 불량 현황 내려받기 요청 — POST /api/v1/quality/defects/by-type/export · by-line/export
 *
 * 화면(QC-01)의 조회 조건을 그대로 받는다. 조회 API(by-type · by-line)와 같은 조건으로 같은 행을 낸다.
 *
 * @param from         시작일 (YYYY-MM-DD). 비우면 종료일 −30일
 * @param to           종료일 (YYYY-MM-DD, 포함). 비우면 오늘
 * @param processId    공정(워크센터) 코드. 비우면 전체
 * @param defectTypeCd 불량 유형 코드 — 화면 요약 카드의 조건. 유형별 분포·설비별 표에는 화면과 같이
 *                     적용하지 않으며, 조회 조건 시트와 다운로드 이력에만 남긴다
 * @param format       `xlsx` 만 받는다(기본값)
 * @param levels       (by-line export) 불량 상세 분해 시트의 단계 순서 — `wc,item,eqpt,defect`. 비우면 기본 순서.
 *                     화면이 `GET /defects/tree?levels=` 에 보낸 값을 그대로 보내면 시트가 화면과 같은 순서다
 */
data class QualityDefectExportRequest(
    val from: String? = null,
    val to: String? = null,
    val processId: String? = null,
    val defectTypeCd: String? = null,
    val format: String = "xlsx",
    val levels: String? = null
)

/**
 * AOI 치수 AI 브리핑 생성 요청 — POST /api/v1/quality/aoi/dimension/briefing
 *
 * 집계 조회(`GET …/summary`)와 같은 조건이다. 집계는 캐시에서 다시 읽고 문장만 만든다.
 *
 * @param from   시작일 (YYYY-MM-DD)
 * @param to     종료일 (YYYY-MM-DD, 포함). 기간은 `app.aoi.max-days` 이하
 * @param wcCd   작업장 코드 (필수 — 한계 세트가 작업장·설비 단위다)
 * @param eqptCd 설비 코드. 비우면 작업장 전체
 */
data class AoiBriefingRequest(
    val from: String? = null,
    val to: String? = null,
    @field:NotBlank(message = "작업장 코드(wcCd)를 지정해 주세요.")
    val wcCd: String = "",
    val eqptCd: String? = null
)
