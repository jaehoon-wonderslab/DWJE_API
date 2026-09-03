package com.dwje.api.service

import com.dwje.api.common.util.DataField
import com.dwje.api.common.util.DateUtils
import com.dwje.api.common.util.MaskingSupport
import com.dwje.api.common.util.MenuId
import com.dwje.api.config.AppProperties
import com.dwje.api.repository.QualityRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 불량 현황 조회 서비스 (QC-01)
 *
 * 접근 부서 : 품질보증팀 · 생산관리팀 · 제조팀 · 경영진 · 통합관리자
 */
@Service
class QualityDefectService(
    private val qualityRepository: QualityRepository,
    private val authorizationService: AuthorizationService,
    private val appProperties: AppProperties
) {

    /**
     * 불량 현황 요약 (No.73)
     */
    @Transactional(readOnly = true)
    fun getSummary(
        from: String?,
        to: String?,
        processId: String?,
        defectTypeCd: String?
    ): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.QC_DEFECT)
        val (fromDate, toDate) = DateUtils.periodOf(from, to)

        val summary = qualityRepository
            .findDefectSummary(appProperties.defaultPlantCd, fromDate, toDate, processId, defectTypeCd)
            .toMutableMap()

        mask.applyTo(
            summary,
            mapOf(
                "ngQty" to DataField.QTY,
                "totalQty" to DataField.QTY,
                "prevNgQty" to DataField.QTY,
                "defectRate" to DataField.YIELD,
                "momChange" to DataField.YIELD
            )
        )

        return summary.toMap() to mask
    }

    /**
     * 불량 유형별 분포 (No.74)
     */
    @Transactional(readOnly = true)
    fun getByType(from: String?, to: String?, processId: String?): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.QC_DEFECT)
        val (fromDate, toDate) = DateUtils.periodOf(from, to)

        if (!mask.check(DataField.YIELD)) {
            return mapOf("items" to emptyList<Any>()) to mask
        }

        val qtyAllowed = mask.check(DataField.QTY)
        val items = qualityRepository
            .findDefectByType(appProperties.defaultPlantCd, fromDate, toDate, processId)
            .map { row -> if (qtyAllowed) row else row + mapOf("cnt" to null) }

        return mapOf("items" to items) to mask
    }

    /**
     * 라인별 불량률 (No.75)
     *
     * @param topN 상위 조회 건수
     */
    @Transactional(readOnly = true)
    fun getByLine(
        from: String?,
        to: String?,
        processId: String?,
        topN: Int
    ): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.QC_DEFECT)
        val (fromDate, toDate) = DateUtils.periodOf(from, to)

        if (!mask.check(DataField.YIELD)) {
            return mapOf("items" to emptyList<Any>()) to mask
        }

        val qtyAllowed = mask.check(DataField.QTY)
        val items = qualityRepository
            .findDefectByLine(appProperties.defaultPlantCd, fromDate, toDate, processId, topN.coerceIn(1, 100))
            .map { row -> if (qtyAllowed) row else row + mapOf("ngQty" to null) }

        return mapOf("items" to items) to mask
    }
}
