package com.dwje.api.service

import com.dwje.api.common.exception.BusinessRuleException
import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.exception.ResourceNotFoundException
import com.dwje.api.common.response.PageMeta
import com.dwje.api.common.util.DataField
import com.dwje.api.common.util.DateUtils
import com.dwje.api.common.util.MaskingSupport
import com.dwje.api.common.util.MenuId
import com.dwje.api.common.util.PageRequestParam
import com.dwje.api.config.AppProperties
import com.dwje.api.model.request.ApprovalLineRequest
import com.dwje.api.model.request.ScrapDraftRequest
import com.dwje.api.model.request.ScrapManualRowRequest
import com.dwje.api.model.request.ScrapUnitPriceRequest
import com.dwje.api.repository.AlertRepository
import com.dwje.api.repository.ReportDocRepository
import com.dwje.api.repository.ScrapReportRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

/**
 * 폐기 보고서 서비스 (RP-06, RP-07)
 *
 * 위저드 5단계
 * 1. MES 폐기 전표 검색·선택
 * 2. MES 미보유 항목 수기 입력
 * 3. 원가 기준정보 단가로 금액 산정 (수기 조정 가능)
 * 4. 검토 부서·결재선 지정
 * 5. 미리보기 → 임시저장 / 검토 요청 / 보고서 생성
 *
 * 접근 부서 : 품질보증팀 · 생산관리팀 · 경영진 · 통합관리자
 */
@Service
class ScrapReportService(
    private val scrapReportRepository: ScrapReportRepository,
    private val reportDocRepository: ReportDocRepository,
    private val alertRepository: AlertRepository,
    private val authorizationService: AuthorizationService,
    private val auditLogService: AuditLogService,
    private val appProperties: AppProperties,
    private val objectMapper: ObjectMapper
) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val DOC_KIND = "SCRAP"

        /** 단가 기준정보가 없을 때 사용하는 기본 단가 (기능명세서 RP-07-F04) */
        private val DEFAULT_UNIT_PRICE = BigDecimal("60.5")
    }

    /**
     * 폐기 보고서 목록을 조회한다. (No.113)
     */
    @Transactional(readOnly = true)
    fun getScrapDocs(
        from: String?,
        to: String?,
        originType: String?,
        page: Int?,
        size: Int?
    ): Triple<List<Map<String, Any?>>, PageMeta, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.RPT_SCRAP)

        val (fromDate, toDate) = DateUtils.periodOf(from, to, 180)
        val paging = PageRequestParam.of(page, size)

        val total = scrapReportRepository.countScrapDocs(fromDate, toDate, originType)
        val rows = scrapReportRepository.findScrapDocs(fromDate, toDate, originType, paging.limit, paging.offset)

        val qtyAllowed = mask.check(DataField.QTY)
        val priceAllowed = mask.check(DataField.PRICE)

        val masked = rows.map {
            it + mapOf(
                "totalQty" to if (qtyAllowed) it["totalQty"] else null,
                "totalAmt" to if (priceAllowed) it["totalAmt"] else null
            )
        }

        return Triple(masked, PageMeta.of(paging.page, paging.size, total), mask)
    }

    /**
     * 폐기 보고서 상세를 조회한다. (No.114)
     *
     * @param docNo 문서번호
     */
    @Transactional(readOnly = true)
    fun getScrapDoc(docNo: String): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.RPT_SCRAP)

        val doc = reportDocRepository.findDocByNo(docNo)
            ?: throw ResourceNotFoundException("폐기 보고서를 찾을 수 없습니다. [docNo=$docNo]")

        return buildDocDetail(doc, mask) to mask
    }

    /**
     * MES 폐기 전표를 조회한다. (No.115 — 1단계)
     */
    @Transactional(readOnly = true)
    fun getMesVouchers(
        from: String?,
        to: String?,
        processId: String?,
        modelCd: String?,
        defectTypeCd: String?,
        originType: String?,
        page: Int?,
        size: Int?
    ): Triple<List<Map<String, Any?>>, PageMeta, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.RPT_SCRAP_NEW)

        val (fromDate, toDate) = DateUtils.periodOf(from, to, 60)
        val paging = PageRequestParam.of(page, size)
        val plantCd = appProperties.defaultPlantCd

        val total = scrapReportRepository.countMesVouchers(
            plantCd, fromDate, toDate, processId, modelCd, defectTypeCd, originType
        )
        val rows = scrapReportRepository.findMesVouchers(
            plantCd, fromDate, toDate, processId, modelCd, defectTypeCd, originType, paging.limit, paging.offset
        )

        val qtyAllowed = mask.check(DataField.QTY)
        val masked = if (qtyAllowed) rows else rows.map { it + mapOf("qty" to null) }

        return Triple(masked, PageMeta.of(paging.page, paging.size, total), mask)
    }

    /**
     * 초안을 생성하고 선택 전표를 반영한다. (No.116 — 임시저장)
     */
    @Transactional
    fun createDraft(request: ScrapDraftRequest): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.RPT_SCRAP_NEW)
        val plantCd = appProperties.defaultPlantCd

        val cond = request.cond
        val (fromDate, toDate) = DateUtils.periodOf(cond?.from, cond?.to, 60)

        val docId = reportDocRepository.insertDoc(
            docKindCd = DOC_KIND,
            reportId = null,
            formId = null,
            title = "폐기 보고서 (${fromDate.format(DateUtils.DATE)} ~ ${toDate.format(DateUtils.DATE)})",
            targetDate = toDate,
            periodFrom = fromDate.atStartOfDay(),
            periodTo = toDate.plusDays(1).atStartOfDay(),
            occurDate = null,
            versionNo = 1,
            plantCd = plantCd,
            lotNo = null,
            productId = null,
            customerId = null,
            disclosurePolicy = null,
            docNo = null,
            actor = principal.userId
        )

        applyPickedVouchers(docId, request, fromDate, toDate, principal.userId)
        reportDocRepository.updateSummary(docId, objectMapper.writeValueAsString(request.form), principal.userId)
        reportDocRepository.insertEvent(
            docId, "GENERATE", "폐기 보고서 초안 생성 (전표 ${request.pickedVoucherIds.size}건)",
            principal.userId, principal.deptName
        )

        log.info("폐기 보고서 초안 생성 : docId={} 전표={}건", docId, request.pickedVoucherIds.size)
        return mapOf("draftId" to docId, "docNo" to null, "step" to (request.step ?: 1))
    }

    /**
     * 초안을 삭제한다. (위저드 취소 — 신규 요청, API 목록 외)
     *
     * 위저드 2단계 이후 중단하면 초안이 남아 목록에 빈 문서로 쌓인다. 화면의 "취소" 가 이 API 를 부른다.
     *
     * - 초안이 없거나 폐기 보고서가 아니면 404
     * - 이미 발행(PUBLISHED)·확정(CONFIRMED)된 문서는 409 — 문서번호가 채번된 보고서는 취소 대상이 아니다
     * - 그 외(DRAFT · SAVED · REJECTED)는 소프트 삭제. 자식 행은 그대로 두고 조회에서만 빠진다
     */
    @Transactional
    fun deleteDraft(draftId: Long): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.RPT_SCRAP_NEW)

        val doc = reportDocRepository.findDoc(draftId)
            ?: throw ResourceNotFoundException("초안을 찾을 수 없습니다. [draftId=$draftId]")
        if (doc["kind"] != DOC_KIND) {
            throw ResourceNotFoundException("폐기 보고서 초안이 아닙니다. [draftId=$draftId]")
        }
        if (doc["state"] == "PUBLISHED" || doc["state"] == "CONFIRMED") {
            throw BusinessRuleException(
                "이미 생성된 보고서는 취소할 수 없습니다. [docNo=${doc["docNo"] ?: "-"}, state=${doc["state"]}]"
            )
        }

        val deleted = reportDocRepository.softDeleteDoc(draftId, DOC_KIND, principal.userId)
        if (deleted == 0) {
            throw ResourceNotFoundException("초안을 찾을 수 없습니다. [draftId=$draftId]")
        }

        log.info("폐기 보고서 초안 삭제 : draftId={} state={} by={}", draftId, doc["state"], principal.userId)
        return mapOf("success" to true, "draftId" to draftId)
    }

    /**
     * 초안을 수정한다. (No.117)
     */
    @Transactional
    fun updateDraft(draftId: Long, request: ScrapDraftRequest): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.RPT_SCRAP_NEW)
        val doc = requireEditableDraft(draftId)

        // 전표 선택이 갱신되면 MES 출처 행을 교체한다.
        if (request.pickedVoucherIds.isNotEmpty() || request.cond != null) {
            val (fromDate, toDate) = DateUtils.periodOf(request.cond?.from, request.cond?.to, 60)
            applyPickedVouchers(draftId, request, fromDate, toDate, principal.userId)
        }

        val payload = mapOf(
            "step" to request.step,
            "form" to request.form,
            "review" to request.review,
            "cond" to request.cond
        )
        reportDocRepository.updateSummary(draftId, objectMapper.writeValueAsString(payload), principal.userId)
        reportDocRepository.updateDocState(draftId, "SAVED", null, principal.userId)
        reportDocRepository.insertEvent(
            draftId, "SAVE", "초안 수정 (step=${request.step ?: doc["version"]})", principal.userId, principal.deptName
        )

        return mapOf("success" to true, "draftId" to draftId)
    }

    /**
     * 수기 폐기 행을 추가한다. (No.118 — 2단계)
     */
    @Transactional
    fun addManualRow(draftId: Long, request: ScrapManualRowRequest): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.RPT_SCRAP_NEW)
        requireEditableDraft(draftId)

        if (request.qty <= BigDecimal.ZERO) {
            throw InvalidParameterException("폐기 수량은 0보다 커야 합니다.", "qty")
        }

        val rowId = scrapReportRepository.insertRow(
            docId = draftId,
            voucherId = null,
            occurDate = request.occurDate?.let { DateUtils.parseDate(it, "occurDate") },
            lotNo = null,
            itemCd = request.itemCd,
            modelCd = request.model,
            wcCd = request.process,
            defectCd = null,
            reasonTxt = request.reason,
            kindCd = normalizeKind(request.kind),
            originTypeCd = "MANUAL",
            qty = request.qty,
            isManual = true,
            actor = principal.userId
        )

        return mapOf("rowId" to rowId, "qty" to request.qty)
    }

    /**
     * 수기 폐기 행을 삭제한다. (No.119)
     */
    @Transactional
    fun deleteManualRow(draftId: Long, rowId: Long): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.RPT_SCRAP_NEW)
        requireEditableDraft(draftId)

        val deleted = scrapReportRepository.deleteRow(draftId, rowId)
        if (deleted == 0) throw ResourceNotFoundException("삭제할 수기 행을 찾을 수 없습니다. [rowId=$rowId]")

        return mapOf("success" to true)
    }

    /**
     * 폐기 금액을 산정한다. (No.120 — 3단계)
     *
     * 원가 기준정보 단가를 적용하되 수기 조정된 행의 단가는 보존한다.
     */
    @Transactional
    fun calculate(draftId: Long): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.RPT_SCRAP_NEW)
        requireEditableDraft(draftId)

        // 금액 산정 결과는 price 권한 보유자에게만 노출한다.
        val priceAllowed = mask.check(DataField.PRICE)
        val qtyAllowed = mask.check(DataField.QTY)

        scrapReportRepository.applyUnitPrices(draftId, appProperties.defaultPlantCd, DEFAULT_UNIT_PRICE)

        val rows = scrapReportRepository.findRows(draftId).map {
            it + mapOf(
                "qty" to if (qtyAllowed) it["qty"] else null,
                "unitPrice" to if (priceAllowed) it["unitPrice"] else null,
                "amount" to if (priceAllowed) it["amount"] else null
            )
        }
        val summary = scrapReportRepository.findSummary(draftId).toMutableMap()
        if (!qtyAllowed) listOf("totalQty", "lossQty", "deadStockQty", "ngQty").forEach { summary[it] = null }
        if (!priceAllowed) summary["totalAmt"] = null

        return mapOf("rows" to rows, "summary" to summary.toMap()) to mask
    }

    /**
     * 단가를 수기 조정한다. (No.121 — 조정 이력 보존)
     */
    @Transactional
    fun adjustUnitPrice(draftId: Long, request: ScrapUnitPriceRequest): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.RPT_SCRAP_NEW)
        requireEditableDraft(draftId)

        if (request.unitPrice <= BigDecimal.ZERO) {
            throw InvalidParameterException("단가는 0보다 커야 합니다.", "unitPrice")
        }

        val updated = scrapReportRepository.updateUnitPriceManually(
            draftId, request.key, request.keyValue, request.unitPrice
        )

        // 단가 조정은 금액에 직결되므로 감사 로그에 남긴다.
        auditLogService.record(
            logType = "AUTO_GEN",
            menuId = MenuId.RPT_SCRAP_NEW,
            fieldKey = DataField.PRICE,
            targetDesc = "폐기 단가 수기 조정 [${request.key}=${request.keyValue}]",
            remark = "단가=${request.unitPrice}, 사유=${request.reason ?: "-"}, 적용=${updated}행"
        )
        reportDocRepository.insertEvent(
            draftId, "CORRECT",
            "단가 조정 [${request.key}=${request.keyValue}] → ${request.unitPrice}",
            principal.userId, principal.deptName
        )

        return mapOf("price" to request.unitPrice, "appliedRows" to updated)
    }

    /**
     * 검토 부서·결재선을 지정한다. (No.122 — 4단계)
     */
    @Transactional
    fun setApprovalLine(draftId: Long, request: ApprovalLineRequest): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.RPT_SCRAP_NEW)
        requireEditableDraft(draftId)

        val approvals = mutableListOf<Map<String, Any?>>()

        // 결재선 3단계 (기안 → 검토 → 승인)
        listOf("draft" to "DRAFT", "review" to "REVIEW", "approve" to "APPROVE").forEach { (key, step) ->
            request.appr[key]?.takeIf { it.isNotBlank() }?.let {
                approvals.add(mapOf("stepCd" to step, "deptId" to null, "managerUserId" to it))
            }
        }

        // 검토 부서 4칸
        request.depts.forEach { d ->
            approvals.add(mapOf("stepCd" to "REVIEW", "deptId" to d.deptId, "managerUserId" to d.manager))
        }

        val due = request.due?.let { DateUtils.parseDate(it, "due") }
        reportDocRepository.replaceApprovals(draftId, approvals, due, principal.userId)
        reportDocRepository.insertEvent(
            draftId, "SAVE", "결재선 지정 (${approvals.size}단계)", principal.userId, principal.deptName
        )

        return mapOf("success" to true, "approvalCnt" to approvals.size)
    }

    /**
     * 검토 요청을 발송한다. (No.123)
     *
     * 지정된 검토 담당자에게 알림을 발송하고 발송 로그를 남긴다.
     */
    @Transactional
    fun sendReviewRequest(draftId: Long, channels: List<String>): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.RPT_SCRAP_NEW)
        val doc = requireEditableDraft(draftId)

        val approvals = reportDocRepository.findApprovals(draftId)
            .filter { it["managerEmpNo"] != null }

        if (approvals.isEmpty()) {
            throw BusinessRuleException("검토 담당자가 지정되지 않았습니다. 결재선을 먼저 지정하세요.")
        }

        // 검토 요청 알림을 발송 로그에 기록한다.
        val alertId = alertRepository.insertTestAlert(
            condId = null,
            severityCd = "LOW",
            title = "폐기 보고서 검토 요청 [${doc["title"]}]",
            targetDesc = "draftId=$draftId"
        )

        val targetChannels = channels.ifEmpty { listOf("MAIL") }
        var sentCnt = 0
        approvals.forEach { appr ->
            targetChannels.forEach { channel ->
                alertRepository.insertSendLog(
                    alertId = alertId,
                    groupId = null,
                    userId = appr["managerEmpNo"] as String?,
                    channelCd = channel.uppercase(),
                    destAddr = null,
                    resultCd = "SENT",
                    failReason = null,
                    escLevel = 0
                )
                sentCnt++
            }
        }

        reportDocRepository.insertEvent(
            draftId, "SAVE", "검토 요청 발송 (${sentCnt}건)", principal.userId, principal.deptName
        )

        return mapOf("sentCnt" to sentCnt, "recipients" to approvals.map { it["manager"] })
    }

    /**
     * 보고서를 생성(발행)한다. (No.124 — 5단계)
     *
     * 문서번호를 채번하고 상태를 PUBLISHED 로 전환한다.
     */
    @Transactional
    fun publish(draftId: Long): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.RPT_SCRAP_NEW)
        val doc = requireEditableDraft(draftId)

        val rows = scrapReportRepository.findRows(draftId)
        if (rows.isEmpty()) {
            throw BusinessRuleException("폐기 대상이 없습니다. 전표를 선택하거나 수기 행을 추가하세요.")
        }

        val yearMonth = (doc["periodTo"] as? String)?.take(7)?.replace("-", "")
            ?: YearMonth.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"))

        val docNo = scrapReportRepository.nextDocNo(yearMonth)
        reportDocRepository.updateDocNo(draftId, docNo, "PUBLISHED", principal.userId)
        reportDocRepository.insertEvent(
            draftId, "PUBLISH", "폐기 보고서 발행 [$docNo]", principal.userId, principal.deptName
        )

        auditLogService.record(
            logType = "AUTO_GEN",
            menuId = MenuId.RPT_SCRAP_NEW,
            targetDesc = "폐기 보고서 발행 [$docNo]",
            remark = "행 ${rows.size}건"
        )

        log.info("폐기 보고서 발행 : draftId={} docNo={}", draftId, docNo)
        return mapOf("docNo" to docNo, "reportId" to draftId)
    }

    /**
     * 발행된 문서 또는 초안의 상세를 조회한다. (출력·인쇄 공통)
     */
    @Transactional(readOnly = true)
    fun getDocDetailById(docId: Long): Pair<Map<String, Any?>, MaskingSupport> {
        // 출력·인쇄(No.125·126)는 "보고서별 열람 권한" 기준이므로 보고서 화면 하나라도 있으면 허용한다.
        val principal = authorizationService.requireAnyMenu(*MenuId.ALL_REPORT_SCREENS)
        val mask = com.dwje.api.common.util.MaskingSupport(principal)
        val doc = reportDocRepository.findDoc(docId)
            ?: throw ResourceNotFoundException("보고서를 찾을 수 없습니다. [reportId=$docId]")
        return buildDocDetail(doc, mask) to mask
    }

    // ---------------------------------------------------------------------------------
    // 내부 보조
    // ---------------------------------------------------------------------------------

    /**
     * 문서 상세(결재 머리부 · 발생 정보 · 상세 표 · 검토 의견)를 조립한다.
     */
    private fun buildDocDetail(doc: Map<String, Any?>, mask: MaskingSupport): Map<String, Any?> {
        val docId = doc["docId"] as Long
        val qtyAllowed = mask.check(DataField.QTY)
        val priceAllowed = mask.check(DataField.PRICE)

        val rows = scrapReportRepository.findRows(docId)
        val summary = scrapReportRepository.findSummary(docId).toMutableMap()
        val totalAmt = (summary["totalAmt"] as? Double) ?: 0.0

        val maskedRows = rows.map {
            val amount = it["amount"] as? Double
            it + mapOf(
                "qty" to if (qtyAllowed) it["qty"] else null,
                "unitPrice" to if (priceAllowed) it["unitPrice"] else null,
                "amount" to if (priceAllowed) amount else null,
                "ratio" to if (priceAllowed && totalAmt > 0 && amount != null) {
                    Math.round(amount / totalAmt * 10000) / 100.0
                } else null
            )
        }

        if (!qtyAllowed) listOf("totalQty", "lossQty", "deadStockQty", "ngQty").forEach { summary[it] = null }
        if (!priceAllowed) summary["totalAmt"] = null

        val approvals = reportDocRepository.findApprovals(docId)

        return mapOf(
            "docId" to docId,
            "header" to mapOf(
                "docNo" to doc["docNo"],
                "title" to doc["title"],
                "state" to doc["state"],
                "draft" to approvals.firstOrNull { it["step"] == "DRAFT" }?.get("manager"),
                "review" to approvals.filter { it["step"] == "REVIEW" }.mapNotNull { it["manager"] },
                "approve" to approvals.firstOrNull { it["step"] == "APPROVE" }?.get("manager"),
                "retention" to "${appProperties.downloadRetentionYears}년",
                "due" to doc["due"]
            ),
            "occurInfo" to mapOf(
                "periodFrom" to doc["periodFrom"],
                "periodTo" to doc["periodTo"],
                "generatedAt" to doc["generatedAt"],
                "generatedBy" to doc["generatedBy"],
                "plantCd" to doc["plantCd"]
            ),
            "summary" to summary.toMap(),
            "rows" to maskedRows,
            "reviewOpinions" to approvals.filter { it["step"] == "REVIEW" }
                .map { mapOf("dept" to it["dept"], "manager" to it["manager"], "opinion" to it["opinion"], "state" to it["state"]) },
            "events" to reportDocRepository.findEvents(docId)
        )
    }

    /**
     * 선택된 MES 전표를 폐기 상세 행으로 반영한다. (기존 MES 출처 행은 교체)
     */
    private fun applyPickedVouchers(
        docId: Long,
        request: ScrapDraftRequest,
        from: LocalDate,
        to: LocalDate,
        actor: String
    ) {
        if (request.pickedVoucherIds.isEmpty()) return

        scrapReportRepository.deleteRowsByDoc(docId, manualOnly = false)

        val cond = request.cond
        val vouchers = scrapReportRepository.findMesVouchers(
            appProperties.defaultPlantCd, from, to,
            cond?.processId, cond?.modelCd, cond?.defectTypeCd, cond?.originType,
            10000, 0
        ).filter { it["voucherId"] in request.pickedVoucherIds }

        vouchers.forEach { v ->
            scrapReportRepository.insertRow(
                docId = docId,
                voucherId = v["voucherId"] as String?,
                occurDate = (v["occurDate"] as? String)?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() },
                lotNo = v["lotNo"] as String?,
                itemCd = v["itemCd"] as String?,
                modelCd = v["model"] as String?,
                wcCd = v["processId"] as String?,
                defectCd = v["defectCd"] as String?,
                reasonTxt = v["defectType"] as String?,
                kindCd = "LOSS",
                originTypeCd = "MES",
                qty = BigDecimal.valueOf((v["qty"] as? Long) ?: 0L),
                isManual = false,
                actor = actor
            )
        }
    }

    /** 편집 가능한 초안인지 확인한다. */
    private fun requireEditableDraft(draftId: Long): Map<String, Any?> {
        val doc = reportDocRepository.findDoc(draftId)
            ?: throw ResourceNotFoundException("초안을 찾을 수 없습니다. [draftId=$draftId]")
        if (doc["state"] == "PUBLISHED") {
            throw BusinessRuleException("이미 발행된 보고서는 수정할 수 없습니다.")
        }
        return doc
    }

    /** 폐기 구분 값을 코드로 정규화한다. */
    private fun normalizeKind(kind: String): String = when (kind.uppercase()) {
        "LOSS" -> "LOSS"
        "DEAD_STOCK", "불용재고" -> "DEAD_STOCK"
        else -> "LOSS"
    }
}
