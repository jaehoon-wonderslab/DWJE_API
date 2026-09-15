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
     * 라인별 불량률 + 설비별 불량 유형 내역 (No.75)
     *
     * @param topN 상위 조회 대수. null·0 이면 **전체 설비** — 화면이 '상위 5개' 표기를 걷어내고 트리로 펼친다.
     *             양수는 그 수만큼(상한 [MAX_LINE_TOP_N]). 비용은 기간 길이에만 비례하고 N 과 무관하다
     *             ([QualityRepository.findDefectByLine]).
     */
    @Transactional(readOnly = true)
    fun getByLine(
        from: String?,
        to: String?,
        processId: String?,
        topN: Int?
    ): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.QC_DEFECT)
        val (fromDate, toDate) = DateUtils.periodOf(from, to)

        if (!mask.check(DataField.YIELD)) {
            return mapOf("items" to emptyList<Any>()) to mask
        }

        val limit = topN?.takeIf { it > 0 }?.coerceAtMost(MAX_LINE_TOP_N)
        val qtyAllowed = mask.check(DataField.QTY)
        val items = qualityRepository
            .findDefectByLine(appProperties.defaultPlantCd, fromDate, toDate, processId, limit)
            .map { row -> if (qtyAllowed) row else maskLineQty(row) }

        return mapOf("items" to items) to mask
    }

    /**
     * 불량 상세 분해 트리 (QC-01 — 공정 > 제품 > 설비 > 불량 유형, 순서는 `levels` 로)
     *
     * 화면에서 Tabulator dataTree 로 펼친다. 전량을 한 번에 준다 — 30일 기준 노드 약 3천(공정 30 · 제품 179 ·
     * 설비 1,267 · 유형 1,239+미상)이고 두 쿼리 합 0.8초라 지연 로딩·상한이 필요하지 않다.
     * 유형 단계를 빼면(`levels=wc,item,eqpt`) 불량 이력을 읽지 않아 더 빠르다.
     *
     * @param levels `wc,item,eqpt,defect` 꼴. 비면 [DefectTreeLevel.DEFAULT]. defect 는 마지막에만
     * @return levels · items(트리) · totals(기간 전체 정상·불량·불량률 — 요약 카드의 ngQty 와 같다)
     */
    @Transactional(readOnly = true)
    fun getDefectTree(
        from: String?,
        to: String?,
        processId: String?,
        levels: String?
    ): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.QC_DEFECT)
        val (fromDate, toDate) = DateUtils.periodOf(from, to)
        val parsed = DefectTreeLevel.parse(levels)
        val levelKeys = parsed.map { it.key }

        if (!mask.check(DataField.YIELD)) {
            return mapOf("levels" to levelKeys, "items" to emptyList<Any>(), "totals" to null) to mask
        }

        val plantCd = appProperties.defaultPlantCd
        val base = qualityRepository.findDefectTreeBase(plantCd, fromDate, toDate, processId)
        val types = if (parsed.last() == DefectTreeLevel.DEFECT) {
            qualityRepository.findDefectTreeTypes(plantCd, fromDate, toDate, processId)
        } else {
            emptyList()
        }

        val qtyAllowed = mask.check(DataField.QTY)
        val items = DefectTreeAssembler.assemble(parsed, base, types, plantCd)
        val ok = base.sumOf { it.okQty }
        val ng = base.sumOf { it.ngQty }
        val totals = mapOf(
            "okQty" to if (qtyAllowed) ok else null,
            "ngQty" to if (qtyAllowed) ng else null,
            "defectRate" to com.dwje.api.common.util.safeRate(
                java.math.BigDecimal.valueOf(ng), java.math.BigDecimal.valueOf(ok + ng)
            )
        )

        return mapOf(
            "levels" to levelKeys,
            "items" to if (qtyAllowed) items else DefectTreeAssembler.maskQty(items),
            "totals" to totals,
            "period" to mapOf("from" to fromDate.format(DateUtils.DATE), "to" to toDate.format(DateUtils.DATE))
        ) to mask
    }

    /**
     * 제품별 불량 현황 트리 (QC-01 제품별 불량 현황 카드) — 제품 > 불량 유형 > 설비 > 공정.
     *
     * 원장·유형 안분 쿼리는 [getDefectTree] 와 같고 조립만 [ProductDefectTreeAssembler] 가 한다.
     * 단계별 수량의 뜻(유형 아래의 totalQty 는 분모라 형제끼리 더하면 안 된다)은 그 조립기 문서에 있다.
     *
     * @return items(트리) · totals(기간 전체 총량·정상·불량·불량률 — 요약 카드와 같은 값) · period
     */
    @Transactional(readOnly = true)
    fun getByProduct(from: String?, to: String?, processId: String?): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.QC_DEFECT)
        val (fromDate, toDate) = DateUtils.periodOf(from, to)

        if (!mask.check(DataField.YIELD)) {
            return mapOf("items" to emptyList<Any>(), "totals" to null) to mask
        }

        val plantCd = appProperties.defaultPlantCd
        val base = qualityRepository.findDefectTreeBase(plantCd, fromDate, toDate, processId)
        val types = qualityRepository.findDefectTreeTypes(plantCd, fromDate, toDate, processId)

        val qtyAllowed = mask.check(DataField.QTY)
        val items = ProductDefectTreeAssembler.assemble(base, types, plantCd)
        val ok = base.sumOf { it.okQty }
        val ng = base.sumOf { it.ngQty }
        val totals = mapOf(
            "totalQty" to if (qtyAllowed) ok + ng else null,
            "okQty" to if (qtyAllowed) ok else null,
            "ngQty" to if (qtyAllowed) ng else null,
            "defectRate" to com.dwje.api.common.util.safeRate(
                java.math.BigDecimal.valueOf(ng), java.math.BigDecimal.valueOf(ok + ng)
            ),
            "itemCnt" to items.size
        )

        return mapOf(
            "items" to if (qtyAllowed) items else ProductDefectTreeAssembler.maskQty(items),
            "totals" to totals,
            "period" to mapOf("from" to fromDate.format(DateUtils.DATE), "to" to toDate.format(DateUtils.DATE))
        ) to mask
    }

    /** 수량 권한이 없으면 설비 수량 3종과 유형별 수량을 비운다. 비율(불량률·유형 비중·주 유형)은 남긴다. */
    @Suppress("UNCHECKED_CAST")
    private fun maskLineQty(row: Map<String, Any?>): Map<String, Any?> {
        val children = (row["children"] as? List<Map<String, Any?>>).orEmpty().map { it + mapOf("ngQty" to null) }
        return row + mapOf("ngQty" to null, "okQty" to null, "totalQty" to null, "children" to children)
    }

    companion object {
        /**
         * 라인별 조회 상위 대수 상한.
         *
         * 사업장 설비 마스터가 1,540대(2026-09 기준)라 전체 조회도 이 안에 든다.
         * 상한은 응답 크기를 지키는 안전판일 뿐, 성능은 N 이 아니라 기간에 달렸다.
         */
        const val MAX_LINE_TOP_N = 2000
    }
}
