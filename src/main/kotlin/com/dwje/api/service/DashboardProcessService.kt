package com.dwje.api.service

import com.dwje.api.common.exception.ResourceNotFoundException
import com.dwje.api.common.util.ProcessPeriod
import com.dwje.api.common.util.ProcessPeriodRow
import com.dwje.api.common.util.DataField
import com.dwje.api.common.util.withUntypedSegment
import com.dwje.api.common.util.DateUtils
import com.dwje.api.common.util.MaskingSupport
import com.dwje.api.common.util.MenuId
import com.dwje.api.common.util.SortResolver
import com.dwje.api.config.AppProperties
import com.dwje.api.repository.DashboardProcessRepository
import com.dwje.api.repository.MetricStandardRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * 공정 및 제품 대시보드 서비스 (DB-02)
 *
 * 공정과 제품(최대 113종)을 조합해 생산·품질 지표를 비교 분석한다.
 * 요약 지표는 가중 평균(총불량 ÷ 총생산)으로 산출한다.
 */
@Service
class DashboardProcessService(
    private val dashboardProcessRepository: DashboardProcessRepository,
    private val dashboardAiService: DashboardAiService,
    private val metricStandardRepository: MetricStandardRepository,
    private val authorizationService: AuthorizationService,
    private val appProperties: AppProperties
) {

    @Transactional(readOnly = true)
    fun getPeriod(from: String, to: String, unit: String, productCodes: List<String>?, processId: String?)
        : Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.DASH_PROC)
        val range = ProcessPeriod.parse(from, to, unit)
        val codes = productCodes.orEmpty().flatMap { it.split(',') }
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val rows = dashboardProcessRepository.findPeriod(
            appProperties.defaultPlantCd, range, requireValidProcess(processId), codes
        ).groupBy({ it.first }, { it.second })
        val periods = rows["periods"].orEmpty().associateBy { it.period }
        return mapOf(
            "summary" to rows.getValue("summary").single().masked(mask),
            "periods" to range.buckets().map { (periods[it] ?: ProcessPeriodRow.empty().copy(period = it)).masked(mask) },
            "products" to rows["products"].orEmpty().map { it.masked(mask) },
            "processes" to rows["processes"].orEmpty().map { it.masked(mask) }
        ) to mask
    }

    /** 제품별 상세 목록 정렬 허용 항목 */
    private val productSortColumns = mapOf(
        "qty" to "total_qty",
        "defectRate" to "(CASE WHEN sum(lh.normal) + sum(lh.defect) > 0 THEN sum(lh.defect) / (sum(lh.normal) + sum(lh.defect)) ELSE 0 END)",
        "product" to "p.model_cd",
        "rank" to "p.rank_no",
        "family" to "f.family_nm"
    )

    /**
     * 공정·제품 요약 지표 (No.33)
     */
    @Transactional(readOnly = true)
    fun getSummary(date: String?, processId: String?, productCodes: List<String>?): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.DASH_PROC)
        val target = DateUtils.parseDate(date, "date", LocalDate.now())
        val process = requireValidProcess(processId)
        val codes = normalizeCodes(productCodes)

        val summary = dashboardProcessRepository
            .findSummary(appProperties.defaultPlantCd, target, process, codes)
            .toMutableMap()

        summary["avgUptime"] = dashboardProcessRepository
            .findAverageUptime(appProperties.defaultPlantCd, target, processId)

        mask.applyTo(
            summary,
            mapOf(
                "qty" to DataField.QTY,
                "okQty" to DataField.QTY,
                "ngQty" to DataField.QTY,
                "defectRate" to DataField.YIELD,
                "yield" to DataField.YIELD
            )
        )

        return summary.toMap() to mask
    }

    /**
     * 시간대별 불량률 추이 (No.34)
     */
    @Transactional(readOnly = true)
    fun getDefectTrend(
        date: String?,
        processId: String?,
        productCodes: List<String>?,
        interval: String?
    ): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.DASH_PROC)
        val target = DateUtils.parseDate(date, "date", LocalDate.now())

        if (!mask.check(DataField.YIELD)) {
            return mapOf("labels" to emptyList<String>(), "series" to emptyList<Any>(), "target" to null) to mask
        }

        val rows = dashboardProcessRepository.findDefectTrend(
            appProperties.defaultPlantCd, target, requireValidProcess(processId), normalizeCodes(productCodes),
            dashboardAiService.parseIntervalHour(interval)
        )

        return mapOf(
            "labels" to rows.map { it["slot"] },
            "series" to listOf(mapOf("name" to "불량률", "data" to rows.map { it["defectRate"] })),
            "target" to metricStandardRepository.findStandardValue("DEFECT_RATE")
        ) to mask
    }

    /**
     * 제품별 생산량·불량률 (No.35)
     */
    @Transactional(readOnly = true)
    fun getProductProduction(
        date: String?,
        processId: String?,
        productCodes: List<String>?
    ): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.DASH_PROC)
        val rows = fetchProductRows(date, processId, productCodes, "ORDER BY total_qty DESC")

        // 양품·불량 수량을 같이 낸다 — 화면이 qty × defectRate 로 되짚으면 반올림만큼 어긋난다.
        val items = rows.map {
            mapOf(
                "product" to it["product"],
                "qty" to it["qty"],
                "okQty" to it["okQty"],
                "ngQty" to it["ngQty"],
                "defectRate" to it["defectRate"]
            )
        }
        return mapOf("items" to dashboardAiService.maskProductionRows(items, mask)) to mask
    }

    /**
     * 불량 유형 구성 (No.36)
     */
    @Transactional(readOnly = true)
    fun getDefectComposition(
        date: String?,
        processId: String?,
        productCodes: List<String>?
    ): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.DASH_PROC)
        val target = DateUtils.parseDate(date, "date", LocalDate.now())

        if (!mask.check(DataField.YIELD)) {
            return mapOf("segments" to emptyList<Any>()) to mask
        }

        val plantCd = appProperties.defaultPlantCd
        val process = requireValidProcess(processId)
        val codes = normalizeCodes(productCodes)

        // 분모는 라벨 원장 불량 총량이고, 차액은 '유형 미상' 세그먼트로 명시한다.
        // (MES_QUERY_GUIDE 2-4)
        val total = dashboardProcessRepository.findDefectLedgerTotal(plantCd, target, process, codes)
        val segments = withUntypedSegment(
            dashboardProcessRepository.findDefectComposition(plantCd, target, process, codes), total
        )

        return mapOf("segments" to segments, "total" to total) to mask
    }

    /**
     * 제품별 수율 (No.37)
     */
    @Transactional(readOnly = true)
    fun getProductYield(
        date: String?,
        processId: String?,
        productCodes: List<String>?
    ): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.DASH_PROC)

        if (!mask.check(DataField.YIELD)) {
            return mapOf("items" to emptyList<Any>(), "target" to null) to mask
        }

        val targetYield = metricStandardRepository.findStandardValue("PROD_YIELD_RATE")
        val rows = fetchProductRows(date, processId, productCodes, "ORDER BY total_qty DESC")

        val items = rows.map {
            mapOf(
                "product" to it["product"],
                "yield" to it["yield"],
                "level" to dashboardAiService.judgeLevel(it["yield"] as? Double, targetYield)
            )
        }

        return mapOf("items" to items, "target" to targetYield) to mask
    }

    /**
     * 제품별 가동률 (No.38 — 설비 점유 기준)
     */
    @Transactional(readOnly = true)
    fun getProductUptime(date: String?, processId: String?, productCodes: List<String>?): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.DASH_PROC)
        val target = DateUtils.parseDate(date, "date", LocalDate.now())

        return mapOf(
            "items" to dashboardProcessRepository.findProductUptime(
                appProperties.defaultPlantCd, target, requireValidProcess(processId), normalizeCodes(productCodes)
            )
        )
    }

    /**
     * 공정 비교 (No.39 — 동일 제품 구성 기준)
     */
    @Transactional(readOnly = true)
    fun getProcessCompare(date: String?, productCodes: List<String>?): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.DASH_PROC)
        val target = DateUtils.parseDate(date, "date", LocalDate.now())

        if (!mask.check(DataField.YIELD)) {
            return mapOf("items" to emptyList<Any>()) to mask
        }

        val rows = dashboardProcessRepository.findProcessCompare(
            appProperties.defaultPlantCd, target, normalizeCodes(productCodes)
        )
        return mapOf("items" to dashboardAiService.maskProductionRows(rows, mask)) to mask
    }

    /**
     * 설비별 시간대 가동률 히트맵 (No.40)
     */
    @Transactional(readOnly = true)
    fun getEquipmentUptimeHeatmap(date: String?, processId: String?, interval: String?): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.DASH_PROC)
        return dashboardAiService.getEquipmentUptimeHeatmap(date, requireValidProcess(processId), interval)
    }

    /**
     * 제품별 상세 목록 (No.41 — 생산량 내림차순 기본)
     */
    @Transactional(readOnly = true)
    fun getProducts(
        date: String?,
        processId: String?,
        productCodes: List<String>?,
        sort: String?
    ): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.DASH_PROC)
        val orderBy = SortResolver.resolve(sort, productSortColumns, "total_qty DESC")

        val rows = fetchProductRows(date, processId, productCodes, orderBy)
        val masked = dashboardAiService.maskProductionRows(rows, mask).map { row ->
            val m = row.toMutableMap()
            // 고객사 정보는 customer 권한 보유자에게만 노출한다.
            mask.applyTo(m, mapOf("customer" to DataField.CUSTOMER))
            m.toMap()
        }

        return mapOf("items" to masked) to mask
    }

    /**
     * Top N 제품 조회 (No.42 — SY-07 제품군 순위를 따름)
     *
     * @param topN 5 | 10 | 20 | 50 | all
     */
    @Transactional(readOnly = true)
    fun getTopProducts(topN: String?): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.DASH_PROC)

        val limit = when (topN?.lowercase()) {
            null, "", "all" -> null
            else -> topN.toIntOrNull()?.coerceIn(1, 500)
        }

        val rows = dashboardProcessRepository.findTopProducts(limit).map { row ->
            val m = row.toMutableMap()
            mask.applyTo(m, mapOf("customer" to DataField.CUSTOMER))
            m.toMap()
        }

        return mapOf(
            "productCodes" to rows.map { it["code"] },
            "products" to rows
        ) to mask
    }

    /**
     * 선택 요약 (No.43)
     */
    @Transactional(readOnly = true)
    fun getSelectionSummary(
        date: String?,
        processId: String,
        productCodes: List<String>?
    ): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.DASH_PROC)
        val plantCd = appProperties.defaultPlantCd

        val process = dashboardProcessRepository.findProcessInfo(plantCd, processId)
            ?: throw ResourceNotFoundException(
                "등록되지 않은 공정 코드입니다. [processId=$processId] " +
                    "공정 목록(GET /api/v1/common/masters/processes)의 id 를 사용하세요."
            )

        // 기준일을 받지 않고 오늘로 고정하면, 화면이 다른 기준일을 보고 있을 때
        // 이 카드만 값이 어긋난다. 다른 위젯과 같은 기준일을 쓴다.
        val target = DateUtils.parseDate(date, "date", LocalDate.now())
        val codes = normalizeCodes(productCodes)
        val summary = dashboardProcessRepository.findSummary(plantCd, target, processId, codes)

        val currentYield = summary["yield"] as? Double
        val targetYield = process["targetYield"] as? Double
        val yieldAllowed = mask.check(DataField.YIELD)

        return mapOf(
            "process" to process,
            "productCnt" to summary["productCnt"],
            "currentYield" to if (yieldAllowed) currentYield else null,
            "gap" to if (yieldAllowed && currentYield != null && targetYield != null) {
                Math.round((currentYield - targetYield) * 100) / 100.0
            } else null
        ) to mask
    }

    // ---------------------------------------------------------------------------------
    // 내부 보조
    // ---------------------------------------------------------------------------------

    /**
     * 제품별 집계 행을 조회한다. (여러 API 가 동일 원천을 사용하므로 공통화)
     */
    private fun fetchProductRows(
        date: String?,
        processId: String?,
        productCodes: List<String>?,
        orderBy: String
    ): List<Map<String, Any?>> {
        val target = DateUtils.parseDate(date, "date", LocalDate.now())
        return dashboardProcessRepository.findProductProduction(
            appProperties.defaultPlantCd, target, requireValidProcess(processId), normalizeCodes(productCodes), orderBy
        )
    }

    /** 제품 코드 목록의 공백·중복을 정리한다. */
    /**
     * 공정 코드가 실제 워크센터인지 확인한다.
     *
     * 없는 코드를 그냥 넘기면 `WHERE wc_cd = '없는값'` 이 되어 조용히 0건이 나온다.
     * 화면에서는 "데이터가 없음" 과 구분되지 않아 원인을 찾을 수 없다.
     * 실제로 화면이 목업 잔재인 `Press` 를 보내 전 위젯이 0 으로 보인 일이 있었다.
     * (유효 코드는 `GET /api/v1/common/masters/processes` 의 `id` — 예: W110, W120, W150)
     *
     * @param processId 공정 코드. 비어 있으면 전체 조회이므로 검증하지 않는다.
     * @throws ResourceNotFoundException 등록되지 않은 공정 코드인 경우
     */
    private fun requireValidProcess(processId: String?): String? {
        val id = processId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        dashboardProcessRepository.findProcessInfo(appProperties.defaultPlantCd, id)
            ?: throw ResourceNotFoundException(
                "등록되지 않은 공정 코드입니다. [processId=$id] " +
                    "공정 목록(GET /api/v1/common/masters/processes)의 id 를 사용하세요."
            )
        return id
    }

    private fun normalizeCodes(productCodes: List<String>?): List<String> =
        productCodes?.mapNotNull { it.trim().takeIf { c -> c.isNotBlank() } }?.distinct() ?: emptyList()
}
