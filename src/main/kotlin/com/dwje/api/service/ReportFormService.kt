package com.dwje.api.service

import com.dwje.api.common.exception.DuplicatedValueException
import com.dwje.api.common.exception.ResourceNotFoundException
import com.dwje.api.common.util.MenuId
import com.dwje.api.common.validation.CodeValidator
import com.dwje.api.model.request.ReportFormRequest
import com.dwje.api.repository.ReportFormRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 보고서 양식 관리 서비스 (QC-04)
 *
 * 양식 항목 정의가 바뀌면 파서 버전을 올려 기존 문서와 구분한다.
 *
 * 접근 부서 : 품질보증팀 · 생산관리팀 · 통합관리자
 */
@Service
class ReportFormService(
    private val reportFormRepository: ReportFormRepository,
    private val authorizationService: AuthorizationService,
    private val codeValidator: CodeValidator
) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** 양식 유형 코드 그룹 */
        private const val FORM_TYPE_GROUP = "RPT_FORM_TYPE"

        /** 고객사 공개 정책 코드 그룹 */
        private const val DISCLOSURE_GROUP = "VEC_CONFIDENTIAL"
    }

    /**
     * 양식 유형·공개 정책이 코드 집합 안의 값인지 확인한다.
     *
     * 키 이름이 맞아도 값이 표시명(`품질 이슈`)이면 그대로 저장되어,
     * 조회 시 `typeNm` 이 null 인 행이 조용히 남는다.
     */
    private fun requireCodes(request: ReportFormRequest) {
        codeValidator.require(FORM_TYPE_GROUP, request.type, "type", "양식 유형")
        codeValidator.require(DISCLOSURE_GROUP, request.disclosurePolicy, "disclosurePolicy", "공개 정책")
    }

    /**
     * 양식 목록 조회 (No.98)
     */
    @Transactional(readOnly = true)
    fun getForms(): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.REPORT_FORMS)
        return mapOf("items" to reportFormRepository.findForms())
    }

    /**
     * 양식 등록 (No.99)
     */
    @Transactional
    fun createForm(request: ReportFormRequest): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.REPORT_FORMS)

        requireCodes(request)

        // 양식명은 유일해야 한다.
        if (reportFormRepository.existsFormName(request.name, null)) {
            throw DuplicatedValueException("이미 등록된 양식명입니다. [${request.name}]", "name")
        }

        val parserVer = "v1.0"
        val formId = reportFormRepository.insertForm(
            formNm = request.name,
            formTypeCd = request.type,
            customerId = request.customerId,
            disclosurePolicy = request.disclosurePolicy,
            reportId = request.reportId,
            parserVer = parserVer,
            actor = principal.userId
        )

        reportFormRepository.replaceFormFields(formId, toFieldMaps(request))

        log.info("보고서 양식 등록 : formId={} name={}", formId, request.name)
        return mapOf("formId" to formId, "parserVer" to parserVer)
    }

    /**
     * 양식 수정 (No.100)
     *
     * 항목 정의가 함께 전달되면 파서 버전을 올린다.
     */
    @Transactional
    fun updateForm(formId: Int, request: ReportFormRequest): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.REPORT_FORMS)

        requireCodes(request)

        val form = reportFormRepository.findForm(formId)
            ?: throw ResourceNotFoundException("보고서 양식을 찾을 수 없습니다. [formId=$formId]")

        if (reportFormRepository.existsFormName(request.name, formId)) {
            throw DuplicatedValueException("이미 등록된 양식명입니다. [${request.name}]", "name")
        }

        // 항목 정의 변경 시에만 파서 버전을 올린다.
        val parserVer = if (request.fields.isNotEmpty()) {
            reportFormRepository.nextParserVersion(form["parserVer"] as? String)
        } else {
            form["parserVer"] as? String ?: "v1.0"
        }

        reportFormRepository.updateForm(
            formId = formId,
            formNm = request.name,
            formTypeCd = request.type,
            customerId = request.customerId,
            disclosurePolicy = request.disclosurePolicy,
            parserVer = parserVer,
            actor = principal.userId
        )

        if (request.fields.isNotEmpty()) {
            reportFormRepository.replaceFormFields(formId, toFieldMaps(request))
        }

        return mapOf("parserVer" to parserVer, "formId" to formId)
    }

    /**
     * 양식 항목 정의 조회 (No.101)
     */
    @Transactional(readOnly = true)
    fun getFormFields(formId: Int): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.REPORT_FORMS)
        reportFormRepository.findForm(formId)
            ?: throw ResourceNotFoundException("보고서 양식을 찾을 수 없습니다. [formId=$formId]")

        return mapOf("formId" to formId, "fields" to reportFormRepository.findFormFields(formId))
    }

    /** 요청 DTO 를 Repository 입력 Map 으로 변환한다. */
    private fun toFieldMaps(request: ReportFormRequest): List<Map<String, Any?>> =
        request.fields.map { f ->
            mapOf<String, Any?>(
                "field" to f.field,
                "label" to f.label,
                "required" to f.required,
                "dataFieldKey" to f.dataFieldKey,
                "remark" to f.remark
            )
        }
}
