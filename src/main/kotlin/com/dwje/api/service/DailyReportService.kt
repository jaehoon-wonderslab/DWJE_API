package com.dwje.api.service

import com.dwje.api.common.exception.BusinessRuleException
import com.dwje.api.common.exception.ResourceNotFoundException
import com.dwje.api.common.exception.SystemErrorException
import com.dwje.api.common.response.PageMeta
import com.dwje.api.common.security.UserContext
import com.dwje.api.common.util.DataField
import com.dwje.api.common.util.DateUtils
import com.dwje.api.common.util.MaskingSupport
import com.dwje.api.common.util.MenuId
import com.dwje.api.common.util.PageRequestParam
import com.dwje.api.config.AppProperties
import com.dwje.api.model.request.ReportCorrectionRequest
import com.dwje.api.repository.DashboardAiRepository
import com.dwje.api.repository.DowntimeRepository
import com.dwje.api.repository.ProductionRepository
import com.dwje.api.repository.ResultFilter
import com.dwje.api.repository.ReportDocRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 일일 생산현황 보고 서비스 (PR-03, PR-04)
 *
 * 보고 대상 기간은 전일 08:00 ~ 당일 08:00 이다.
 * AI 가 MES 실적을 집계해 초안을 만들고, 담당자가 항목을 보정한 뒤 확정한다.
 *
 * 접근 부서 : 생산관리팀 · 통합관리자
 */
@Service
class DailyReportService(
    private val reportDocRepository: ReportDocRepository,
    private val productionRepository: ProductionRepository,
    private val dashboardAiRepository: DashboardAiRepository,
    private val downtimeRepository: DowntimeRepository,
    private val authorizationService: AuthorizationService,
    private val auditLogService: AuditLogService,
    private val appProperties: AppProperties,
    private val objectMapper: ObjectMapper
) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** 보고서 문서 구분 */
        private const val DOC_KIND = "DAILY"

        /** 보고 기간 시작 시각 (전일 08:00) */
        private const val PERIOD_START_HOUR = 8

        /** 보고서 정의 ID */
        private const val REPORT_DEF_ID = "RPT_DAILY_PROD"
    }

    /**
     * 보고서 초안을 조회한다. (No.59)
     *
     * 대상 일자의 초안이 없으면 즉시 생성한다.
     *
     * @param targetDate 대상 일자 (미지정 시 오늘)
     */
    @Transactional
    fun getDraft(targetDate: String?): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.PROD_DAILY)
        val target = DateUtils.parseDate(targetDate, "targetDate", LocalDate.now())

        val doc = reportDocRepository.findLatestDocByTargetDate(DOC_KIND, target)
            ?: run {
                val docId = generateDraft(target, version = 1)
                reportDocRepository.findDoc(docId)
                    ?: throw SystemErrorException("생성한 보고서 초안을 다시 읽지 못했습니다. [reportId=$docId]")
            }

        return buildDraftResponse(doc, mask) to mask
    }

    /**
     * 보고서 초안을 재생성한다. (No.60)
     *
     * 기존 문서는 보존하고 새 버전을 만든다.
     */
    @Transactional
    fun regenerateDraft(targetDate: String?): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.PROD_DAILY)
        val target = DateUtils.parseDate(targetDate, "targetDate", LocalDate.now())

        val previous = reportDocRepository.findLatestDocByTargetDate(DOC_KIND, target)
        // 확정된 보고서는 재생성할 수 없다.
        if (previous?.get("state") == "CONFIRMED") {
            throw BusinessRuleException("이미 확정된 보고서는 재생성할 수 없습니다. 반려 후 진행하세요.")
        }

        val nextVersion = ((previous?.get("version") as? Int) ?: 0) + 1
        val docId = generateDraft(target, nextVersion)

        return mapOf("reportId" to docId, "version" to nextVersion)
    }

    /**
     * 보고서 항목을 보정한다. (No.61)
     *
     * @param reportId 보고서 문서 ID
     * @param request  섹션·항목 값
     */
    @Transactional
    fun correct(reportId: Long, request: ReportCorrectionRequest): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.PROD_DAILY)
        val doc = requireEditableDoc(reportId)

        // 1. 항목 코드별 보정 값을 수집한다.
        val values = request.sections
            .flatMap { it.fields }
            .mapNotNull { f -> f.fieldCode?.let { it to f.value } }
            .toMap()

        val corrected = reportDocRepository.updateFieldValues(reportId, values, principal.userId)

        // 2. 누적 보정 건수를 갱신한다.
        val totalCorrection = ((doc["correctionCnt"] as? Int) ?: 0) + corrected
        reportDocRepository.updateCorrectionCount(reportId, totalCorrection, principal.userId)

        reportDocRepository.insertEvent(
            reportId, "CORRECT", "항목 ${corrected}건 보정${request.remark?.let { " — $it" } ?: ""}",
            principal.userId, principal.deptName
        )

        log.info("일일 생산현황 보고 항목 보정 : reportId={} 보정={}건", reportId, corrected)
        return mapOf("reportId" to reportId, "correctionCnt" to totalCorrection)
    }

    /**
     * 보고서를 임시 저장한다. (No.62)
     */
    @Transactional
    fun save(reportId: Long, request: ReportCorrectionRequest): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.PROD_DAILY)
        requireEditableDoc(reportId)

        val values = request.sections.flatMap { it.fields }
            .mapNotNull { f -> f.fieldCode?.let { it to f.value } }
            .toMap()

        reportDocRepository.updateFieldValues(reportId, values, principal.userId)
        reportDocRepository.updateDocState(reportId, "SAVED", null, principal.userId)
        reportDocRepository.insertEvent(reportId, "SAVE", "임시 저장", principal.userId, principal.deptName)

        return mapOf("success" to true, "reportId" to reportId)
    }

    /**
     * 보고서를 확정한다. (No.63 — 감사 로그 기록)
     */
    @Transactional
    fun confirm(reportId: Long): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.PROD_DAILY)
        val doc = reportDocRepository.findDoc(reportId)
            ?: throw ResourceNotFoundException("보고서를 찾을 수 없습니다. [reportId=$reportId]")

        if (doc["state"] == "CONFIRMED") {
            throw BusinessRuleException("이미 확정된 보고서입니다.")
        }

        reportDocRepository.updateDocState(reportId, "CONFIRMED", null, principal.userId)
        reportDocRepository.insertEvent(reportId, "CONFIRM", "보고서 확정", principal.userId, principal.deptName)

        auditLogService.record(
            logType = "AUTO_GEN",
            menuId = MenuId.PROD_DAILY,
            targetDesc = "일일 생산현황 보고 확정 [${doc["targetDate"]}]",
            remark = "reportId=$reportId, version=${doc["version"]}"
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
     * 보고서를 반려한다. (No.64)
     *
     * @param reason 반려 사유
     */
    @Transactional
    fun reject(reportId: Long, reason: String?): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.PROD_DAILY)
        reportDocRepository.findDoc(reportId)
            ?: throw ResourceNotFoundException("보고서를 찾을 수 없습니다. [reportId=$reportId]")

        reportDocRepository.updateDocState(reportId, "REJECTED", reason, principal.userId)
        reportDocRepository.insertEvent(reportId, "REJECT", reason ?: "반려", principal.userId, principal.deptName)

        return mapOf("state" to "REJECTED", "reason" to reason)
    }

    /**
     * 보고서 생성 이력을 조회한다. (No.65)
     */
    @Transactional(readOnly = true)
    fun getEvents(reportId: Long): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.PROD_DAILY)
        return mapOf("events" to reportDocRepository.findEvents(reportId))
    }

    /**
     * 보고서 이력을 조회한다. (No.66 — PR-04 이전 보고서)
     */
    @Transactional(readOnly = true)
    fun getHistory(
        from: String?,
        to: String?,
        state: String?,
        page: Int?,
        size: Int?
    ): Pair<List<Map<String, Any?>>, PageMeta> {
        authorizationService.requireAnyMenu(MenuId.PROD_DAILY, MenuId.DAILY_HISTORY)

        val (fromDate, toDate) = DateUtils.periodOf(from, to, 90)
        val paging = PageRequestParam.of(page, size)

        val total = reportDocRepository.countDocs(DOC_KIND, fromDate, toDate, state, null)
        val rows = reportDocRepository.findDocs(DOC_KIND, fromDate, toDate, state, null, paging.limit, paging.offset)

        val items = rows.map {
            mapOf(
                "reportId" to it["docId"],
                "targetDate" to it["targetDate"],
                "version" to it["version"],
                "state" to it["state"],
                "generatedAt" to it["generatedAt"],
                "confirmedAt" to it["confirmedAt"],
                "confirmedBy" to it["confirmedBy"],
                "correctionCnt" to it["correctionCnt"]
            )
        }

        return items to PageMeta.of(paging.page, paging.size, total)
    }

    /**
     * 보고서를 복제한다. (No.67)
     *
     * @param reportId   원본 보고서 ID
     * @param targetDate 복제 대상 일자
     */
    @Transactional
    fun copy(reportId: Long, targetDate: String): Map<String, Any?> {
        val principal = authorizationService.requireAnyMenu(MenuId.PROD_DAILY, MenuId.DAILY_HISTORY)
        val source = reportDocRepository.findDoc(reportId)
            ?: throw ResourceNotFoundException("복제할 보고서를 찾을 수 없습니다. [reportId=$reportId]")

        val target = DateUtils.parseDate(targetDate, "targetDate")
        val existing = reportDocRepository.findLatestDocByTargetDate(DOC_KIND, target)
        val nextVersion = ((existing?.get("version") as? Int) ?: 0) + 1

        val (periodFrom, periodTo) = reportPeriod(target)
        val newDocId = reportDocRepository.insertDoc(
            docKindCd = DOC_KIND,
            reportId = REPORT_DEF_ID,
            formId = null,
            title = "일일 생산현황 보고 (${target.format(DateUtils.DATE)})",
            targetDate = target,
            periodFrom = periodFrom,
            periodTo = periodTo,
            occurDate = null,
            versionNo = nextVersion,
            plantCd = appProperties.defaultPlantCd,
            lotNo = null,
            productId = null,
            customerId = null,
            disclosurePolicy = null,
            docNo = null,
            actor = principal.userId
        )

        // 원본 항목 값을 그대로 복제한다.
        val sourceFields = reportDocRepository.findFields(reportId).map { f ->
            mapOf<String, Any?>(
                "sectionCd" to f["section"],
                "fieldNm" to f["field"],
                "fieldCode" to f["fieldCode"],
                "fieldValue" to f["value"],
                "originCd" to f["origin"],
                "isCorrected" to f["corrected"],
                "blindFieldKey" to f["blindFieldKey"]
            )
        }
        reportDocRepository.replaceFields(newDocId, sourceFields)
        reportDocRepository.insertEvent(
            newDocId, "COPY", "보고서 복제 (원본 reportId=$reportId)", principal.userId, principal.deptName
        )

        log.info("일일 생산현황 보고 복제 : {} → {}", reportId, newDocId)
        return mapOf("newReportId" to newDocId, "targetDate" to target.format(DateUtils.DATE))
    }

    // ---------------------------------------------------------------------------------
    // 초안 생성
    // ---------------------------------------------------------------------------------

    /**
     * MES 실적을 집계해 보고서 초안을 생성한다.
     *
     * 섹션 구성
     * - RESULT    : 생산 실적 (투입/양품/불량/불량률/수율)
     * - CONDITION : 공정별 수율
     * - CAUSE     : 주요 불량 유형
     * - ACTION    : 비가동 현황
     *
     * @return 생성된 문서 ID
     */
    private fun generateDraft(target: LocalDate, version: Int): Long {
        val principal = UserContext.current()
        val plantCd = appProperties.defaultPlantCd
        val (periodFrom, periodTo) = reportPeriod(target)

        val docId = reportDocRepository.insertDoc(
            docKindCd = DOC_KIND,
            reportId = REPORT_DEF_ID,
            formId = null,
            title = "일일 생산현황 보고 (${target.format(DateUtils.DATE)})",
            targetDate = target,
            periodFrom = periodFrom,
            periodTo = periodTo,
            occurDate = null,
            versionNo = version,
            plantCd = plantCd,
            lotNo = null,
            productId = null,
            customerId = null,
            disclosurePolicy = null,
            docNo = null,
            actor = principal.userId
        )

        // 1. 보고 기간(전일 08:00~당일 08:00)의 생산 실적을 집계한다.
        val summary = productionRepository.findResultSummary(
            ResultFilter(plantCd = plantCd, from = target.minusDays(1), to = target)
        )
        val processYield = dashboardAiRepository.findProcessYield(plantCd, target)
        val defectComposition = dashboardAiRepository.findDefectComposition(plantCd, target, null)
        val downtimeSummary = downtimeRepository.findSummary(plantCd, target)

        val fields = mutableListOf<Map<String, Any?>>()

        // 2. 생산 실적 섹션
        fields += field("RESULT", "투입 수량", "inputQty", summary["inputQty"]?.toString(), "MES", DataField.QTY)
        fields += field("RESULT", "양품 수량", "okQty", summary["okQty"]?.toString(), "MES", DataField.QTY)
        fields += field("RESULT", "불량 수량", "ngQty", summary["ngQty"]?.toString(), "MES", DataField.QTY)
        fields += field("RESULT", "불량률(%)", "defectRate", summary["defectRate"]?.toString(), "MES", DataField.YIELD)
        fields += field("RESULT", "수율(%)", "yieldRate", summary["yield"]?.toString(), "MES", DataField.YIELD)

        // 3. 공정별 수율 섹션
        processYield.forEach { p ->
            fields += field(
                "CONDITION", "${p["process"]} 수율(%)", "yield_${p["processId"]}",
                p["yield"]?.toString(), "MES", DataField.YIELD
            )
        }

        // 4. 주요 불량 유형 섹션 (상위 5종)
        defectComposition.take(5).forEach { d ->
            fields += field(
                "CAUSE", "${d["label"]} 불량 수량", "defect_${d["code"]}",
                d["value"]?.toString(), "MES", DataField.YIELD
            )
        }

        // 5. 비가동 현황 섹션
        fields += field("ACTION", "총 비가동 시간(분)", "downtimeMin", downtimeSummary["totalMin"]?.toString(), "MES", null)
        fields += field("ACTION", "사유 미등록 건수", "unregisteredCnt", downtimeSummary["unregisteredCnt"]?.toString(), "MES", null)
        fields += field("ACTION", "특이사항", "note", null, "MANUAL", null)

        reportDocRepository.replaceFields(docId, fields)
        reportDocRepository.updateSummary(docId, objectMapper.writeValueAsString(summary), principal.userId)
        reportDocRepository.insertEvent(
            docId, if (version > 1) "REGENERATE" else "GENERATE",
            "MES 실적 기반 초안 생성 (v$version)", principal.userId, principal.deptName
        )

        return docId
    }

    /**
     * 초안 조회 응답을 조립한다. (섹션별 그룹핑 + 마스킹)
     */
    private fun buildDraftResponse(doc: Map<String, Any?>, mask: MaskingSupport): Map<String, Any?> {
        val docId = doc["docId"] as Long
        val fields = reportDocRepository.findFields(docId)

        // 항목에 지정된 blind 항목 key 로 값을 마스킹한다.
        val maskedFields = fields.map { f ->
            val blindKey = f["blindFieldKey"] as? String
            if (blindKey != null && !mask.check(blindKey)) f + mapOf("value" to null, "masked" to true)
            else f
        }

        val sections = maskedFields.groupBy { it["section"] as String }
            .map { (section, items) -> mapOf("section" to section, "fields" to items) }

        return mapOf(
            "reportId" to docId,
            "version" to doc["version"],
            "state" to doc["state"],
            "targetDate" to doc["targetDate"],
            "periodFrom" to doc["periodFrom"],
            "periodTo" to doc["periodTo"],
            "generatedAt" to doc["generatedAt"],
            "generatedBy" to doc["generatedBy"],
            "correctionCnt" to doc["correctionCnt"],
            "sections" to sections,
            "summary" to parseSummary(doc["summaryJson"] as? String, mask)
        )
    }

    /** 요약 JSON 을 파싱하고 마스킹을 적용한다. */
    private fun parseSummary(json: String?, mask: MaskingSupport): Map<String, Any?> {
        if (json.isNullOrBlank()) return emptyMap()

        @Suppress("UNCHECKED_CAST")
        val parsed = runCatching { objectMapper.readValue(json, Map::class.java) as Map<String, Any?> }
            .getOrDefault(emptyMap())

        val result = parsed.toMutableMap()
        mask.applyTo(
            result,
            mapOf(
                "inputQty" to DataField.QTY,
                "okQty" to DataField.QTY,
                "ngQty" to DataField.QTY,
                "defectRate" to DataField.YIELD,
                "yield" to DataField.YIELD
            )
        )
        return result.toMap()
    }

    /**
     * 편집 가능한 문서인지 확인한다. (확정 문서는 수정 불가)
     */
    private fun requireEditableDoc(reportId: Long): Map<String, Any?> {
        val doc = reportDocRepository.findDoc(reportId)
            ?: throw ResourceNotFoundException("보고서를 찾을 수 없습니다. [reportId=$reportId]")
        if (doc["state"] == "CONFIRMED") {
            throw BusinessRuleException("확정된 보고서는 수정할 수 없습니다.")
        }
        return doc
    }

    /**
     * 보고 대상 기간을 산출한다. (전일 08:00 ~ 당일 08:00)
     */
    private fun reportPeriod(target: LocalDate): Pair<LocalDateTime, LocalDateTime> =
        target.minusDays(1).atTime(PERIOD_START_HOUR, 0) to target.atTime(PERIOD_START_HOUR, 0)

    /** 보고서 항목 한 건을 구성한다. */
    private fun field(
        section: String,
        name: String,
        code: String,
        value: String?,
        origin: String,
        blindFieldKey: String?
    ): Map<String, Any?> = mapOf(
        "sectionCd" to section,
        "fieldNm" to name,
        "fieldCode" to code,
        "fieldValue" to value,
        "originCd" to origin,
        "isCorrected" to false,
        "blindFieldKey" to blindFieldKey
    )
}
