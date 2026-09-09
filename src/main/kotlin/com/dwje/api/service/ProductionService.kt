package com.dwje.api.service

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.exception.ResourceNotFoundException
import com.dwje.api.common.response.PageMeta
import com.dwje.api.common.util.DataField
import com.dwje.api.common.util.DateUtils
import com.dwje.api.common.util.MaskingSupport
import com.dwje.api.common.util.MenuId
import com.dwje.api.common.util.PageRequestParam
import com.dwje.api.common.util.ProductionMonitorPeriod
import com.dwje.api.config.AppProperties
import com.dwje.api.repository.DashboardAiRepository
import com.dwje.api.repository.ProductionRepository
import com.dwje.api.repository.ResultFilter
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * 생산 모니터링 · 실적 집계 서비스 (PR-01, PR-02)
 *
 * 접근 부서
 * - 생산 모니터링 : 품질보증팀 · 생산관리팀 · 제조팀 · 통합관리자
 * - 실적 집계     : 품질보증팀 · 생산관리팀 · 경영진 · 통합관리자
 */
@Service
class ProductionService(
    private val productionRepository: ProductionRepository,
    private val dashboardAiRepository: DashboardAiRepository,
    private val dashboardAiService: DashboardAiService,
    private val authorizationService: AuthorizationService,
    private val appProperties: AppProperties
) {

    companion object {
        /** 경고 판정 가동률 임계값(%) — 이 값 미만이면 warning */
        private const val WARN_UPTIME_LEVEL = 60.0

        /** 실적 집계 허용 단위 */
        private val ALLOWED_UNITS = setOf("day", "week", "month")

        /** 기준일 모니터의 기본 설비 화면은 C-프레스 10대(MT-001~010)다. */
        private const val DEFAULT_MONITOR_PRESS_PROCESS = "W120"
        private const val DEFAULT_MONITOR_PRESS_PREFIX = "MT"
    }

    /**
     * 모니터링 요약 (No.55)
     *
     * @param processId 공정 코드
     */
    @Transactional(readOnly = true)
    fun getMonitorSummary(processId: String?, targetDate: String?): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.PROD_MONITOR)
        val plantCd = appProperties.defaultPlantCd
        val target = targetDate?.takeIf { it.isNotBlank() }
            ?.let { DateUtils.parseDate(it, "targetDate") }
        val window = target?.let(ProductionMonitorPeriod::of)

        val summary = productionRepository.findMonitorSummary(plantCd, processId, WARN_UPTIME_LEVEL, window).toMutableMap()

        // 시간당 처리량은 수량(qty) 권한 대상이다.
        mask.applyTo(summary, mapOf("hourlyThroughput" to DataField.QTY, "totalThroughput" to DataField.QTY))
        summary["stoppedDetail"] = productionRepository.findStoppedDetail(plantCd, processId, window)
        target?.let { summary["targetDate"] = it.format(DateUtils.DATE) }
        window?.let {
            summary["periodFrom"] = it.from.format(DateUtils.DATETIME)
            summary["periodTo"] = it.toExclusive.format(DateUtils.DATETIME)
        }

        return summary.toMap() to mask
    }

    /**
     * 설비별 실시간 현황 (No.56 — 10초 폴링)
     */
    @Transactional(readOnly = true)
    fun getMonitorEquipments(
        lineRange: String?,
        model: String?,
        processId: String?,
        state: String?,
        targetDate: String?,
        page: Int?,
        size: Int?
    ): Triple<List<Map<String, Any?>>, PageMeta, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.PROD_MONITOR)
        val plantCd = appProperties.defaultPlantCd
        val target = targetDate?.takeIf { it.isNotBlank() }
            ?.let { DateUtils.parseDate(it, "targetDate") }
        val window = target?.let(ProductionMonitorPeriod::of)
        // size=0 은 전량이다. 다른 목록(/dashboard/ai/lines · /reports/yield-by-model)과 같은 규약이다.
        // of() 를 쓰면 size 가 coerceIn(1, 1000) 되어 0 이 1 로 보정된다 — 실제로 그래서
        // size=0 이 1건만 돌려주고 있었다.
        // 기준일 첫 화면은 MT-001~010 프레스 10대를 바로 보여 준다. 명시한 page/size는
        // 그대로 존중하므로 나머지 프레스까지 보거나 전량(size=0)을 받는 기존 용도는 깨지지 않는다.
        val paging = if (window != null && page == null && size == null) {
            PageRequestParam.of(1, 10)
        } else {
            PageRequestParam.ofAllowAll(page, size)
        }

        // 기준일 화면의 기본 목록은 실적이 연결된 C-프레스 10대다. 호출자가 공정·설비
        // 조건을 주면 그 조건을 우선해 기존의 범용 모니터 조회도 유지한다.
        val resolvedProcessId = if (window != null && processId.isNullOrBlank()) DEFAULT_MONITOR_PRESS_PROCESS else processId
        val resolvedLineRange = if (window != null && lineRange.isNullOrBlank()) DEFAULT_MONITOR_PRESS_PREFIX else lineRange

        val total = productionRepository.countMonitorEquipments(
            plantCd, resolvedLineRange, model, resolvedProcessId, state, WARN_UPTIME_LEVEL, window
        )
        val rows = productionRepository.findMonitorEquipments(
            plantCd, resolvedLineRange, model, resolvedProcessId, state, WARN_UPTIME_LEVEL, window, paging.limitOrNull, paging.offset
        )

        // 수량·수율에 더해 금형 코드는 mold 권한 대상이다.
        val masked = dashboardAiService.maskProductionRows(rows, mask).map { row ->
            val m = row.toMutableMap()
            mask.applyTo(m, mapOf("moldCd" to DataField.MOLD, "strokeSpeed" to DataField.MOLD))
            m.toMap()
        }

        val meta = if (paging.isAll) PageMeta.all(total) else PageMeta.of(paging.page, paging.size, total)
        return Triple(masked, meta, mask)
    }

    /**
     * 설비 상세 조회 (No.30 — 모달)
     *
     * @param eqptCd 설비 코드
     * @param date   기준일
     */
    @Transactional(readOnly = true)
    fun getEquipmentDetail(eqptCd: String, date: String?): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.PROD_MONITOR)
        val plantCd = appProperties.defaultPlantCd
        val target = DateUtils.parseDate(date, "date", LocalDate.now())

        val detail = dashboardAiRepository.findEquipmentDetail(plantCd, eqptCd, target)
            ?: throw com.dwje.api.common.exception.ResourceNotFoundException("설비 정보를 찾을 수 없습니다. [$eqptCd]")

        val result = detail.toMutableMap()
        result["stopElapsedMin"] = dashboardAiRepository.findStopElapsedMinutes(plantCd, eqptCd)

        mask.applyTo(
            result,
            mapOf(
                "qty" to DataField.QTY,
                "okQty" to DataField.QTY,
                "ngQty" to DataField.QTY,
                "defectRate" to DataField.YIELD,
                "moldCd" to DataField.MOLD,
                "moldNm" to DataField.MOLD,
                "spec" to DataField.MOLD
            )
        )

        return result.toMap() to mask
    }

    /**
     * 실적 집계 조회 (No.57)
     *
     * @param unit 집계 단위 — day | week | month
     */
    @Transactional(readOnly = true)
    fun getResults(
        from: String?,
        to: String?,
        unit: String?,
        itemCd: String?,
        modelCd: String?,
        lineCd: String?,
        page: Int?,
        size: Int?
    ): Triple<Map<String, Any?>, PageMeta, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.PROD_RESULT)
        val (fromDate, toDate) = DateUtils.periodOf(from, to)
        val aggUnit = normalizeUnit(unit)
        val paging = PageRequestParam.of(page, size)
        val filter = resultFilterOf(fromDate, toDate, itemCd, modelCd, lineCd)

        val total = productionRepository.countResults(filter, aggUnit)
        val rows = productionRepository.findResults(filter, aggUnit, paging.limit, paging.offset)
        val summary = productionRepository.findResultSummary(filter).toMutableMap()

        mask.applyTo(
            summary,
            mapOf(
                "inputQty" to DataField.QTY,
                "okQty" to DataField.QTY,
                "ngQty" to DataField.QTY,
                "defectRate" to DataField.YIELD,
                "yield" to DataField.YIELD
            )
        )

        val maskedRows = maskResultRows(rows, mask)

        return Triple(
            mapOf("items" to maskedRows, "summary" to summary.toMap(), "unit" to aggUnit),
            PageMeta.of(paging.page, paging.size, total),
            mask
        )
    }

    /**
     * 실적 추이 차트 (No.58)
     */
    @Transactional(readOnly = true)
    fun getResultTrend(
        from: String?,
        to: String?,
        unit: String?,
        itemCd: String?,
        modelCd: String?,
        lineCd: String?
    ): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.PROD_RESULT)
        val (fromDate, toDate) = DateUtils.periodOf(from, to)
        val aggUnit = normalizeUnit(unit)

        val qtyAllowed = mask.check(DataField.QTY)
        val yieldAllowed = mask.check(DataField.YIELD)

        // 표(No.57)와 같은 조건으로 조회한다. 화면이 두 API 를 같은 파라미터로 부르므로
        // 여기서 조건 하나를 흘리면 표는 비고 차트만 그려지는 상태가 된다.
        val filter = resultFilterOf(fromDate, toDate, itemCd, modelCd, lineCd)

        // 추이는 최대 366개 구간까지 조회한다.
        val rows = productionRepository.findResults(filter, aggUnit, 366, 0)
            .sortedBy { it["period"] as String }

        val series = mutableListOf<Map<String, Any?>>()
        if (qtyAllowed) series.add(mapOf("name" to "생산량", "data" to rows.map { it["inputQty"] }))
        if (yieldAllowed) {
            series.add(mapOf("name" to "불량률", "data" to rows.map { it["defectRate"] }))
            series.add(mapOf("name" to "수율", "data" to rows.map { it["yield"] }))
        }

        return mapOf(
            "labels" to rows.map { it["period"] },
            "series" to series,
            "unit" to aggUnit
        ) to mask
    }

    /**
     * 실적 집계 내려받기용 전체 행을 조회한다. (엑셀 출력)
     */
    @Transactional(readOnly = true)
    fun getResultRowsForExport(
        from: String?,
        to: String?,
        unit: String?,
        itemCd: String?,
        modelCd: String?,
        lineCd: String?
    ): Pair<List<Map<String, Any?>>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.PROD_RESULT)
        val (fromDate, toDate) = DateUtils.periodOf(from, to)

        val filter = resultFilterOf(fromDate, toDate, itemCd, modelCd, lineCd)
        val rows = productionRepository.findResults(filter, normalizeUnit(unit), 10000, 0)
        return maskResultRows(rows, mask) to mask
    }

    /**
     * 실적 집계 조회 조건을 만들면서 코드를 검증한다.
     *
     * 없는 코드를 그대로 넘기면 `WHERE ... = '없는값'` 이 되어 조용히 0건이 나온다.
     * 화면에서는 "데이터가 없음" 과 구분되지 않아 원인을 찾을 수 없다.
     * 실제로 화면이 모델 코드(`D63A`)를 품목 코드 자리에 보내 표가 늘 비어 있던 일이 있었다.
     *
     * @param itemCd  실적 품목 코드 — `label_hist.item_cd` 정확 일치 (예 `D63A-S`)
     * @param modelCd 제품 모델 코드 — 그 모델에 매핑된 품목 전부 (예 `D63A`)
     * @param lineCd  설비 코드 (예 `MT-007`). 공정 코드(`W120`)가 아니다
     * @throws ResourceNotFoundException 등록되지 않은 모델·설비 코드인 경우
     */
    private fun resultFilterOf(
        fromDate: LocalDate,
        toDate: LocalDate,
        itemCd: String?,
        modelCd: String?,
        lineCd: String?
    ): ResultFilter {
        val plantCd = appProperties.defaultPlantCd
        val filter = ResultFilter.of(plantCd, fromDate, toDate, itemCd, modelCd, lineCd)

        // 모델 코드를 품목 코드 자리에 넣는 실수가 잦다 — 실적 품목은 `D63A-S` 처럼
        // 공정 접미사가 붙은 값이고, 제품 선택 목록이 주는 코드는 `D63A` 다.
        // 조용히 0건을 주면 화면에서 "데이터 없음" 과 구분되지 않으므로 어느 쪽인지 알려 준다.
        filter.itemCd?.let {
            if (!productionRepository.existsMappedItem(plantCd, it) &&
                productionRepository.existsProductModel(it)
            ) {
                throw ResourceNotFoundException(
                    "[$it] 은 품목 코드가 아니라 제품 모델 코드입니다. modelCd=$it 로 보내세요. " +
                        "itemCd 는 실적 품목 코드(예 ${it}-S)만 받습니다."
                )
            }
        }

        filter.modelCd?.let {
            if (!productionRepository.existsProductModel(it)) {
                throw ResourceNotFoundException(
                    "등록되지 않은 제품 모델 코드입니다. [modelCd=$it] " +
                        "제품 목록(GET /api/v1/common/masters/products)의 code 를 사용하세요."
                )
            }
        }

        filter.lineCd?.let {
            if (!productionRepository.existsEquipment(plantCd, it)) {
                throw ResourceNotFoundException(
                    "등록되지 않은 설비 코드입니다. [lineCd=$it] " +
                        "설비 코드는 GET /api/v1/dashboard/ai/lines 의 eqptCd 입니다. " +
                        "공정 코드(예 W120)를 넣으면 이 오류가 납니다."
                )
            }
        }

        return filter
    }

    /**
     * 실적 행에 데이터 접근 권한 마스킹을 적용한다.
     */
    private fun maskResultRows(rows: List<Map<String, Any?>>, mask: MaskingSupport): List<Map<String, Any?>> {
        val qtyAllowed = mask.check(DataField.QTY)
        val yieldAllowed = mask.check(DataField.YIELD)
        if (qtyAllowed && yieldAllowed) return rows

        return rows.map { row ->
            val m = row.toMutableMap()
            if (!qtyAllowed) listOf("inputQty", "okQty", "ngQty").forEach { m[it] = null }
            if (!yieldAllowed) listOf("defectRate", "yield").forEach { m[it] = null }
            m.toMap()
        }
    }

    /**
     * 집계 단위를 화이트리스트로 검증한다. (SQL 에 직접 반영되는 값)
     */
    private fun normalizeUnit(unit: String?): String {
        val normalized = (unit ?: "day").lowercase()
        if (normalized !in ALLOWED_UNITS) {
            throw InvalidParameterException("집계 단위는 day/week/month 만 허용합니다. [unit=$unit]", "unit")
        }
        return normalized
    }
}
