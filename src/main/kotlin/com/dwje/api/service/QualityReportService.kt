package com.dwje.api.service

import com.dwje.api.common.exception.BusinessRuleException
import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.exception.ResourceNotFoundException
import com.dwje.api.common.exception.SystemErrorException
import com.dwje.api.common.response.PageMeta
import com.dwje.api.common.util.DataField
import com.dwje.api.common.util.DateUtils
import com.dwje.api.common.util.MaskingSupport
import com.dwje.api.common.util.MenuId
import com.dwje.api.common.util.PageRequestParam
import com.dwje.api.config.AppProperties
import com.dwje.api.model.request.EvidenceImageRequest
import com.dwje.api.model.request.QualityReportDraftRequest
import com.dwje.api.model.request.ReportCorrectionRequest
import com.dwje.api.model.request.UnmaskRequest
import com.dwje.api.repository.MaskRuleRepository
import com.dwje.api.repository.QualityRepository
import com.dwje.api.repository.ReportDocRepository
import com.dwje.api.repository.ReportFormRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * 품질 보고서 서비스 (QC-03)
 *
 * 양식(ax.tb_rpt_form)의 항목 정의에 따라 초안을 만들고, MES 실적으로 자동 기입한 뒤
 * 고객사 공개 정책(ax.tb_ai_mask_rule)과 데이터 접근 권한을 적용해 마스킹한다.
 *
 * 접근 부서 : 품질보증팀 · 생산관리팀 · 통합관리자
 */
@Service
class QualityReportService(
    private val reportDocRepository: ReportDocRepository,
    private val reportFormRepository: ReportFormRepository,
    private val maskRuleRepository: MaskRuleRepository,
    private val qualityRepository: QualityRepository,
    private val authorizationService: AuthorizationService,
    private val auditLogService: AuditLogService,
    private val appProperties: AppProperties
) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val DOC_KIND = "QUALITY"
    }

    /**
     * 품질 보고서 초안을 생성한다. (No.85)
     *
     * 양식 항목 정의를 읽어 문서 항목을 만들고, MES 접두사(mes_) 항목은 실적으로 자동 기입한다.
     */
    @Transactional
    fun createDraft(request: QualityReportDraftRequest): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.QC_REPORT)

        val formId = request.formId
            ?: throw InvalidParameterException("보고서 양식을 선택해 주세요.", "formId")
        val form = reportFormRepository.findForm(formId)
            ?: throw ResourceNotFoundException("보고서 양식을 찾을 수 없습니다. [formId=$formId]")

        val occurDate = request.occurDate?.let { DateUtils.parseDate(it, "occurDate") }

        val docId = reportDocRepository.insertDoc(
            docKindCd = DOC_KIND,
            reportId = form["reportId"] as String?,
            formId = formId,
            title = "${form["name"]} (${request.lotNo ?: occurDate ?: "신규"})",
            targetDate = occurDate,
            periodFrom = null,
            periodTo = null,
            occurDate = occurDate,
            versionNo = 1,
            plantCd = appProperties.defaultPlantCd,
            lotNo = request.lotNo,
            productId = null,
            customerId = form["customerId"] as Int?,
            disclosurePolicy = request.disclosurePolicy ?: form["disclosurePolicy"] as String?,
            docNo = null,
            actor = principal.userId
        )

        // 양식 항목 정의를 문서 항목으로 전개한다.
        val formFields = reportFormRepository.findFormFields(formId)
        val docFields = formFields.map { ff ->
            val origin = ff["origin"] as String
            mapOf<String, Any?>(
                "sectionCd" to sectionOf(ff["field"] as String?),
                "fieldNm" to ff["label"],
                "fieldCode" to ff["field"],
                "fieldValue" to autoFillValue(ff["field"] as String?, request.lotNo, occurDate),
                "originCd" to origin,
                "isCorrected" to false,
                "blindFieldKey" to ff["dataFieldKey"]
            )
        }
        reportDocRepository.replaceFields(docId, docFields)
        reportDocRepository.insertEvent(
            docId, "GENERATE", "양식 [${form["name"]}] 기반 초안 생성", principal.userId, principal.deptName
        )

        log.info("품질 보고서 초안 생성 : docId={} formId={} lot={}", docId, formId, request.lotNo)

        return mapOf(
            "reportId" to docId,
            "version" to 1,
            "formId" to formId,
            "sections" to groupSections(reportDocRepository.findFields(docId))
        )
    }

    /**
     * 품질 보고서를 조회한다. (No.86)
     *
     * 데이터 접근 권한과 고객사 공개 정책을 모두 적용해 마스킹한다.
     */
    @Transactional(readOnly = true)
    fun getReport(reportId: Long): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.QC_REPORT)
        val doc = requireDoc(reportId)

        val fields = reportDocRepository.findFields(reportId)
        val customerId = doc["customerId"] as Int?

        // 고객사 공개 정책에서 마스킹(FULL/DROP) 대상 데이터 항목을 수집한다.
        val policyBlindKeys = maskRuleRepository.findRules(customerId)
            .filter { it["action"] in setOf("FULL", "DROP", "HASH") }
            .mapNotNull { it["fieldKey"] as? String }
            .toSet()

        val maskedFields = fields.map { f ->
            val blindKey = f["blindFieldKey"] as? String
            val blocked = blindKey != null && (!mask.check(blindKey) || blindKey in policyBlindKeys)
            if (blocked) f + mapOf("value" to null, "masked" to true) else f
        }

        val sections = groupSections(maskedFields)

        return mapOf(
            "reportId" to reportId,
            "header" to mapOf(
                "title" to doc["title"],
                "formId" to doc["formId"],
                "formNm" to doc["formNm"],
                "lotNo" to doc["lotNo"],
                "occurDate" to doc["occurDate"],
                "customer" to if (mask.check(DataField.CUSTOMER)) doc["customer"] else null,
                "disclosurePolicy" to doc["disclosurePolicy"],
                "version" to doc["version"],
                "generatedAt" to doc["generatedAt"],
                "generatedBy" to doc["generatedBy"]
            ),
            "resultTable" to sections.firstOrNull { it["section"] == "RESULT" },
            "processCondition" to sections.firstOrNull { it["section"] == "CONDITION" },
            "causeAnalysis" to sections.firstOrNull { it["section"] == "CAUSE" },
            "traceHistory" to sections.firstOrNull { it["section"] == "TRACE" },
            "actions" to sections.firstOrNull { it["section"] == "ACTION" },
            "sections" to sections,
            "images" to reportDocRepository.findImages(reportId),
            "state" to doc["state"],
            "correctionCnt" to doc["correctionCnt"]
        ) to mask
    }

    /**
     * 자동 기입 현황을 조회한다. (No.87)
     *
     * 각 항목의 기입 출처(MES / AI / MANUAL)와 보정 여부를 반환한다.
     */
    @Transactional(readOnly = true)
    fun getAutofillStatus(reportId: Long): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.QC_REPORT)
        requireDoc(reportId)

        val fields = reportDocRepository.findFields(reportId)
        val byOrigin = fields.groupingBy { it["origin"] as String }.eachCount()

        return mapOf(
            "fields" to fields.map {
                mapOf(
                    "field" to it["field"],
                    "label" to it["fieldCode"],
                    "origin" to it["origin"],
                    "corrected" to it["corrected"],
                    "filled" to ((it["value"] as? String)?.isNotBlank() ?: false)
                )
            },
            "summary" to mapOf(
                "total" to fields.size,
                "mes" to (byOrigin["MES"] ?: 0),
                "ai" to (byOrigin["AI"] ?: 0),
                "manual" to (byOrigin["MANUAL"] ?: 0),
                "corrected" to fields.count { it["corrected"] == true }
            )
        )
    }

    /**
     * 마스킹 적용 내역을 조회한다. (No.88)
     */
    @Transactional(readOnly = true)
    fun getMaskingDetail(reportId: Long): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.QC_REPORT)
        val doc = requireDoc(reportId)

        val rules = maskRuleRepository.findRules(doc["customerId"] as Int?)
        return mapOf(
            "reportId" to reportId,
            "customer" to doc["customer"],
            "disclosurePolicy" to doc["disclosurePolicy"],
            "rules" to rules.map {
                mapOf(
                    "ruleId" to it["ruleId"],
                    "field" to (it["fieldNm"] ?: it["fieldKey"]),
                    "fieldKey" to it["fieldKey"],
                    "policy" to (it["policyDesc"] ?: it["customerPolicy"]),
                    "action" to it["actionNm"],
                    "targets" to it["targetFields"]
                )
            }
        )
    }

    /**
     * 마스킹 해제를 요청한다. (No.89 — 감사 로그 기록)
     */
    @Transactional
    fun requestUnmask(reportId: Long, request: UnmaskRequest): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.QC_REPORT)
        requireDoc(reportId)

        val requestId = maskRuleRepository.insertUnmaskRequest(
            docId = reportId,
            menuId = MenuId.QC_REPORT,
            fieldKeys = request.fields,
            reason = request.reason,
            requesterId = principal.userId,
            requesterDept = principal.deptName
        )

        // 마스킹 해제 요청은 감사 로그 필수 기록 대상이다.
        auditLogService.record(
            logType = "UNMASK_REQ",
            menuId = MenuId.QC_REPORT,
            fieldKey = request.fields.firstOrNull(),
            targetDesc = "품질 보고서 마스킹 해제 요청 [reportId=$reportId]",
            resultCd = "ALLOW",
            maskedCnt = request.fields.size,
            remark = "항목=${request.fields.joinToString(",")} / 사유=${request.reason}"
        )

        return mapOf("requestId" to requestId, "state" to "REQUESTED")
    }

    /**
     * 증빙 이미지 후보를 조회한다. (No.90)
     *
     * @param criteria 후보 선정 기준 — ng(불량 발생분) | lot(동일 LOT) | 숫자(최근 N건)
     */
    @Transactional(readOnly = true)
    fun getEvidenceImageCandidates(reportId: Long, criteria: String?): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.QC_REPORT)
        val doc = requireDoc(reportId)

        // NAS 이미지 인덱스가 연동되기 전까지는 이미 첨부된 이미지와 LOT 정보를 근거로 후보를 제시한다.
        val attached = reportDocRepository.findImages(reportId)

        return mapOf(
            "criteria" to (criteria ?: "ng"),
            "lotNo" to doc["lotNo"],
            "images" to attached,
            "note" to "NAS 이미지 인덱스 연동 전으로 첨부된 이미지만 반환한다."
        )
    }

    /**
     * 증빙 이미지를 첨부한다. (No.91)
     */
    @Transactional
    fun attachEvidenceImages(reportId: Long, request: EvidenceImageRequest): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.QC_REPORT)
        val doc = requireDoc(reportId)

        val images = request.images.map { img ->
            mapOf<String, Any?>(
                "name" to (img.name ?: img.nasPath?.substringAfterLast('/') ?: "evidence"),
                "nasPath" to (img.nasPath ?: ""),
                "defectCd" to img.defectCd,
                "lotNo" to (img.lotNo ?: doc["lotNo"])
            )
        }.ifEmpty {
            // imageIds 만 전달된 경우 식별자를 경로로 사용한다.
            request.imageIds.map { id ->
                mapOf<String, Any?>("name" to id, "nasPath" to id, "defectCd" to null, "lotNo" to doc["lotNo"])
            }
        }

        if (images.isEmpty()) throw InvalidParameterException("첨부할 이미지를 선택해 주세요.", "imageIds")

        val attachedCnt = reportDocRepository.insertImages(reportId, images, principal.userId)
        reportDocRepository.insertEvent(
            reportId, "CORRECT", "증빙 이미지 ${attachedCnt}건 첨부", principal.userId, principal.deptName
        )

        return mapOf("attachedCnt" to attachedCnt)
    }

    /**
     * 보고서를 임시 저장한다. (No.92)
     */
    @Transactional
    fun save(reportId: Long, request: ReportCorrectionRequest): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.QC_REPORT)
        val doc = requireDoc(reportId)
        if (doc["state"] == "CONFIRMED") throw BusinessRuleException("확정된 보고서는 수정할 수 없습니다.")

        val values = request.sections.flatMap { it.fields }
            .mapNotNull { f -> f.fieldCode?.let { it to f.value } }
            .toMap()

        val corrected = reportDocRepository.updateFieldValues(reportId, values, principal.userId)
        reportDocRepository.updateDocState(reportId, "SAVED", null, principal.userId)
        reportDocRepository.updateCorrectionCount(
            reportId, ((doc["correctionCnt"] as? Int) ?: 0) + corrected, principal.userId
        )
        reportDocRepository.insertEvent(reportId, "SAVE", "임시 저장 (${corrected}건)", principal.userId, principal.deptName)

        return mapOf("success" to true, "correctionCnt" to corrected)
    }

    /**
     * 보고서를 확정한다. (No.93 — 감사 로그 기록)
     */
    @Transactional
    fun confirm(reportId: Long): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.QC_REPORT)
        val doc = requireDoc(reportId)
        if (doc["state"] == "CONFIRMED") throw BusinessRuleException("이미 확정된 보고서입니다.")

        reportDocRepository.updateDocState(reportId, "CONFIRMED", null, principal.userId)
        reportDocRepository.insertEvent(reportId, "CONFIRM", "보고서 확정", principal.userId, principal.deptName)

        auditLogService.record(
            logType = "AUTO_GEN",
            menuId = MenuId.QC_REPORT,
            targetDesc = "품질 보고서 확정 [${doc["title"]}]",
            remark = "reportId=$reportId"
        )

        val confirmed = reportDocRepository.findDoc(reportId)
            ?: throw SystemErrorException("확정 처리 후 보고서를 다시 읽지 못했습니다. [reportId=$reportId]")
        return mapOf(
            "state" to confirmed["state"],
            "confirmedAt" to confirmed["confirmedAt"],
            "confirmedBy" to confirmed["confirmedBy"]
        )
    }

    /**
     * 보고서를 반려한다. (No.94)
     */
    @Transactional
    fun reject(reportId: Long, reason: String?): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.QC_REPORT)
        requireDoc(reportId)

        reportDocRepository.updateDocState(reportId, "REJECTED", reason, principal.userId)
        reportDocRepository.insertEvent(reportId, "REJECT", reason ?: "반려", principal.userId, principal.deptName)

        return mapOf("state" to "REJECTED", "reason" to reason)
    }

    /**
     * 보고서 초안을 재생성한다. (No.95)
     */
    @Transactional
    fun regenerate(reportId: Long): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.QC_REPORT)
        val doc = requireDoc(reportId)
        if (doc["state"] == "CONFIRMED") throw BusinessRuleException("확정된 보고서는 재생성할 수 없습니다.")

        val formId = doc["formId"] as? Int
            ?: throw BusinessRuleException("양식이 지정되지 않아 재생성할 수 없습니다.")

        // 자동 기입 항목만 다시 채우고 사람이 보정한 항목은 보존한다.
        val existing = reportDocRepository.findFields(reportId).associateBy { it["fieldCode"] as? String }
        val formFields = reportFormRepository.findFormFields(formId)

        val refreshed = formFields.map { ff ->
            val code = ff["field"] as? String
            val prev = existing[code]
            val corrected = prev?.get("corrected") == true
            mapOf<String, Any?>(
                "sectionCd" to sectionOf(code),
                "fieldNm" to ff["label"],
                "fieldCode" to code,
                "fieldValue" to if (corrected) prev?.get("value") else autoFillValue(code, doc["lotNo"] as? String, null),
                "originCd" to if (corrected) "MANUAL" else ff["origin"],
                "isCorrected" to corrected,
                "blindFieldKey" to ff["dataFieldKey"]
            )
        }

        reportDocRepository.replaceFields(reportId, refreshed)
        reportDocRepository.insertEvent(
            reportId, "REGENERATE", "자동 기입 항목 재생성 (보정 항목 보존)", principal.userId, principal.deptName
        )

        return mapOf("reportId" to reportId, "version" to doc["version"])
    }

    /**
     * 품질 보고서 이력을 조회한다. (No.97)
     */
    @Transactional(readOnly = true)
    fun getHistory(
        from: String?,
        to: String?,
        formId: Int?,
        state: String?,
        page: Int?,
        size: Int?
    ): Pair<List<Map<String, Any?>>, PageMeta> {
        authorizationService.requireMenu(MenuId.QC_REPORT)

        val (fromDate, toDate) = DateUtils.periodOf(from, to, 90)
        val paging = PageRequestParam.of(page, size)

        val total = reportDocRepository.countDocs(DOC_KIND, fromDate, toDate, state, formId)
        val rows = reportDocRepository.findDocs(DOC_KIND, fromDate, toDate, state, formId, paging.limit, paging.offset)

        return rows to PageMeta.of(paging.page, paging.size, total)
    }

    /**
     * 보고서 출력용 행을 반환한다. (No.96)
     */
    @Transactional(readOnly = true)
    fun getExportRows(reportId: Long): Triple<Map<String, Any?>, List<Map<String, Any?>>, MaskingSupport> {
        val (data, mask) = getReport(reportId)
        val doc = requireDoc(reportId)

        @Suppress("UNCHECKED_CAST")
        val sections = data["sections"] as List<Map<String, Any?>>
        val rows = sections.flatMap { section ->
            @Suppress("UNCHECKED_CAST")
            val fields = section["fields"] as List<Map<String, Any?>>
            fields.map {
                mapOf<String, Any?>(
                    "section" to section["section"],
                    "field" to it["field"],
                    "value" to it["value"],
                    "origin" to it["origin"]
                )
            }
        }

        return Triple(doc, rows, mask)
    }

    // ---------------------------------------------------------------------------------
    // 내부 보조
    // ---------------------------------------------------------------------------------

    /** 문서 존재 확인 */
    private fun requireDoc(reportId: Long): Map<String, Any?> =
        reportDocRepository.findDoc(reportId)
            ?: throw ResourceNotFoundException("보고서를 찾을 수 없습니다. [reportId=$reportId]")

    /**
     * 항목 코드로 소속 섹션을 판정한다.
     */
    private fun sectionOf(fieldCode: String?): String {
        val code = fieldCode?.lowercase() ?: return "BODY"
        return when {
            code.contains("result") || code.contains("qty") || code.contains("rate") -> "RESULT"
            code.contains("condition") || code.contains("param") || code.contains("spec") -> "CONDITION"
            code.contains("cause") || code.contains("analysis") -> "CAUSE"
            code.contains("trace") || code.contains("lot") || code.contains("hist") -> "TRACE"
            code.contains("action") || code.contains("measure") -> "ACTION"
            code.contains("title") || code.contains("date") || code.contains("customer") -> "HEADER"
            else -> "BODY"
        }
    }

    /**
     * MES 접두사(mes_) 항목의 자동 기입 값을 산출한다.
     *
     * LOT 기준 불량 통계 등 실적 연계 값을 채운다. 그 외 항목은 공란으로 남긴다.
     */
    private fun autoFillValue(fieldCode: String?, lotNo: String?, occurDate: LocalDate?): String? {
        if (fieldCode == null) return null
        if (!fieldCode.startsWith("mes_")) return null
        if (lotNo.isNullOrBlank()) return null

        // LOT 단위 불량 요약을 조회해 대표 값을 기입한다.
        val riskLots = qualityRepository.findRiskLots(appProperties.defaultPlantCd, 30, 200)
        val lot = riskLots.firstOrNull { it["lotNo"] == lotNo } ?: return null

        return when (fieldCode.removePrefix("mes_")) {
            "lot_no" -> lot["lotNo"] as? String
            "model" -> lot["model"] as? String
            "qty" -> lot["qty"]?.toString()
            "ng_qty" -> lot["ngQty"]?.toString()
            "defect_rate" -> lot["defectRate"]?.toString()
            "main_defect" -> lot["mainDefect"] as? String
            "occur_date" -> occurDate?.format(DateUtils.DATE)
            else -> null
        }
    }

    /** 항목 목록을 섹션 단위로 묶는다. */
    private fun groupSections(fields: List<Map<String, Any?>>): List<Map<String, Any?>> =
        fields.groupBy { it["section"] as String }
            .map { (section, items) -> mapOf("section" to section, "fields" to items) }
}
