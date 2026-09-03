package com.dwje.api.service

import com.dwje.api.common.response.PageMeta
import com.dwje.api.common.util.DataField
import com.dwje.api.common.util.withUntypedSegment
import com.dwje.api.common.util.DateUtils
import com.dwje.api.common.util.MaskingSupport
import com.dwje.api.common.util.MenuId
import com.dwje.api.common.util.PageRequestParam
import com.dwje.api.config.AppProperties
import com.dwje.api.repository.DashboardAiRepository
import com.dwje.api.repository.MetricStandardRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * AI 통합 대시보드 서비스 (DB-01)
 *
 * 전 부서가 열람하는 화면이며, 수량(qty)·수율(yield) 항목은 데이터 접근 권한에 따라 마스킹한다.
 */
@Service
class DashboardAiService(
    private val dashboardAiRepository: DashboardAiRepository,
    private val metricStandardRepository: MetricStandardRepository,
    private val authorizationService: AuthorizationService,
    private val appProperties: AppProperties
) {

    companion object {
        /** 공정 품질 지수 6축 지표 코드 — 양품률·가동률·정시완료·검사정확도·이상대응·데이터정합 */
        private val QUALITY_INDEX_METRICS = listOf(
            "PROD_OK_RATE",
            "EQPT_UPTIME_RATE",
            "PROD_ONTIME_RATE",
            "AOI_ACCURACY_RATE",
            "ALERT_RESPONSE_RATE",
            "DATA_CONSISTENCY_RATE"
        )

        /** 기본 집계 구간 (2시간) */
        private const val DEFAULT_INTERVAL_HOUR = 2

        /** 불량률 추이 보조 계열 개수 */
        private const val TREND_TOP_DEFECT = 2
    }

    /**
     * 통합 요약 지표 (No.21 — KPI 카드 4종)
     *
     * @param date 기준일 (미지정 시 오늘)
     */
    @Transactional(readOnly = true)
    fun getSummary(date: String?): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.DASH_AI)
        val target = DateUtils.parseDate(date, "date", LocalDate.now())
        val plantCd = appProperties.defaultPlantCd

        val summary = dashboardAiRepository.findSummary(plantCd, target).toMutableMap()

        // 수량·수율 항목은 데이터 접근 권한에 따라 마스킹한다.
        mask.applyTo(
            summary,
            mapOf(
                "todayQty" to DataField.QTY,
                "okQty" to DataField.QTY,
                "ngQty" to DataField.QTY,
                "defectRate" to DataField.YIELD
            )
        )

        summary["pendingBorderline"] = dashboardAiRepository.findPendingBorderline(plantCd, target)
        summary["date"] = target.format(DateUtils.DATE)

        return summary.toMap() to mask
    }

    /**
     * 시간대별 불량률 추이 (No.22 — 전체 + 주 불량유형 2계열)
     *
     * @param date     기준일
     * @param interval 집계 구간 (예: "2h")
     */
    @Transactional(readOnly = true)
    fun getDefectTrend(date: String?, interval: String?): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.DASH_AI)
        val target = DateUtils.parseDate(date, "date", LocalDate.now())
        val hours = parseIntervalHour(interval)
        val plantCd = appProperties.defaultPlantCd

        // 수율·불량률 권한이 없으면 계열 데이터를 반환하지 않는다.
        if (!mask.check(DataField.YIELD)) {
            return mapOf("labels" to emptyList<String>(), "series" to emptyList<Any>(), "target" to null) to mask
        }

        val overall = dashboardAiRepository.findDefectTrend(plantCd, target, hours, null)
        val labels = overall.map { it["slot"] as String }

        // 전체 불량률 계열
        val series = mutableListOf<Map<String, Any?>>(
            mapOf("name" to "전체", "data" to overall.map { it["defectRate"] })
        )

        // 주 불량유형 계열 — 슬롯별 불량 수량을 라벨 순서에 맞춰 배치한다.
        val byType = dashboardAiRepository.findDefectTrendByType(plantCd, target, hours, null, TREND_TOP_DEFECT)
        byType.groupBy { it["defectNm"] as String }.forEach { (name, rows) ->
            val bySlot = rows.associate { (it["slot"] as String) to it["ngQty"] }
            series.add(mapOf("name" to name, "data" to labels.map { bySlot[it] ?: 0L }))
        }

        return mapOf(
            "labels" to labels,
            "series" to series,
            "target" to metricStandardRepository.findStandardValue("DEFECT_RATE")
        ) to mask
    }

    /**
     * 라인별 생산량·불량률 (No.23)
     */
    @Transactional(readOnly = true)
    fun getLineProduction(date: String?, processId: String?): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.DASH_AI)
        val target = DateUtils.parseDate(date, "date", LocalDate.now())

        val rows = dashboardAiRepository.findLineProduction(appProperties.defaultPlantCd, target, processId)
        val masked = maskProductionRows(rows, mask)

        return mapOf("lines" to masked) to mask
    }

    /**
     * 공정 품질 지수 6축 (No.24)
     */
    @Transactional(readOnly = true)
    fun getQualityIndex(date: String?): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.DASH_AI)
        val target = DateUtils.parseDate(date, "date", LocalDate.now())

        if (!mask.check(DataField.YIELD)) {
            return mapOf("axes" to emptyList<Any>()) to mask
        }

        val axes = dashboardAiRepository.findQualityIndex(
            appProperties.defaultPlantCd, target, QUALITY_INDEX_METRICS
        )
        return mapOf("axes" to axes) to mask
    }

    /**
     * 불량 유형 구성 (No.25 — 경계 판정 건 제외 표기)
     */
    @Transactional(readOnly = true)
    fun getDefectComposition(date: String?, processId: String?): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.DASH_AI)
        val target = DateUtils.parseDate(date, "date", LocalDate.now())
        val plantCd = appProperties.defaultPlantCd

        if (!mask.check(DataField.YIELD)) {
            return mapOf("segments" to emptyList<Any>(), "total" to null) to mask
        }

        // 분모는 라벨 원장 불량 총량. 표시된 유형 합으로 잡으면 유형 미상 물량이 분모에서 빠져
        // 비중이 부풀려진다. 차액은 '유형 미상' 세그먼트로 명시한다. (MES_QUERY_GUIDE 2-4)
        val total = dashboardAiRepository.findDefectLedgerTotal(plantCd, target, processId)
        val segments = withUntypedSegment(
            dashboardAiRepository.findDefectComposition(plantCd, target, processId), total
        )
        val borderline = dashboardAiRepository.findPendingBorderline(plantCd, target)

        return mapOf(
            "segments" to segments,
            "total" to total,
            // 경계 판정 대기 건은 구성 비율에서 제외되었음을 명시한다.
            "excludedBorderline" to borderline["cnt"]
        ) to mask
    }

    /**
     * 공정별 수율 (No.26)
     */
    @Transactional(readOnly = true)
    fun getProcessYield(date: String?): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.DASH_AI)
        val target = DateUtils.parseDate(date, "date", LocalDate.now())

        if (!mask.check(DataField.YIELD)) {
            return mapOf("items" to emptyList<Any>(), "target" to null) to mask
        }

        val targetYield = metricStandardRepository.findStandardValue("PROD_YIELD_RATE")
        val items = dashboardAiRepository.findProcessYield(appProperties.defaultPlantCd, target).map { row ->
            val m = row.toMutableMap()
            // 목표 대비 달성 수준을 3단계로 판정한다.
            m["level"] = judgeLevel(row["yield"] as? Double, targetYield)
            if (!mask.allowed(DataField.QTY)) {
                m["qty"] = null; m["okQty"] = null; m["ngQty"] = null
            }
            m.toMap()
        }

        return mapOf(
            "items" to items,
            "target" to targetYield,
            "note" to "양품 수량 ÷ (양품 + 불량) 기준. 재작업 투입분은 제외한다."
        ) to mask
    }

    /**
     * 생산 계획 대비 실적 (No.27)
     */
    @Transactional(readOnly = true)
    fun getPlanVsActual(date: String?, interval: String?): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.DASH_AI)
        val target = DateUtils.parseDate(date, "date", LocalDate.now())
        val hours = parseIntervalHour(interval)

        // 계획 수량은 plan, 실적 수량은 qty 권한이 필요하다.
        val planAllowed = mask.check(DataField.PLAN)
        val qtyAllowed = mask.check(DataField.QTY)

        val rows = dashboardAiRepository.findPlanVsActual(appProperties.defaultPlantCd, target, hours)
        val items = rows.map { row ->
            mapOf(
                "slot" to row["slot"],
                "plan" to if (planAllowed) row["plan"] else null,
                "actual" to if (qtyAllowed) row["actual"] else null
            )
        }

        val cumPlan = rows.sumOf { (it["plan"] as? Long) ?: 0L }
        val cumActual = rows.sumOf { (it["actual"] as? Long) ?: 0L }

        return mapOf(
            "items" to items,
            "cumPlan" to if (planAllowed) cumPlan else null,
            "cumActual" to if (qtyAllowed) cumActual else null,
            "rate" to if (planAllowed && qtyAllowed && cumPlan > 0) {
                Math.round(cumActual * 10000.0 / cumPlan) / 100.0
            } else null
        ) to mask
    }

    /**
     * 설비별 시간대 가동률 히트맵 (No.28 — 낮을수록 진하게 표기)
     */
    @Transactional(readOnly = true)
    fun getEquipmentUptimeHeatmap(date: String?, processId: String?, interval: String?): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.DASH_AI)
        val target = DateUtils.parseDate(date, "date", LocalDate.now())
        val hours = parseIntervalHour(interval)

        val rows = dashboardAiRepository.findEquipmentUptimeHeatmap(
            appProperties.defaultPlantCd, target, processId, hours
        )
        return buildHeatmap(rows)
    }

    /**
     * 라인별 현황 목록 (No.29)
     *
     * 설비 전량(1,300여 건)을 매번 그리지 않도록 쪽 단위로 반환한다.
     * `size=0` 이면 전량이며, 목록 키는 `items` 가 아니라 `lines` 다.
     */
    @Transactional(readOnly = true)
    fun getLines(
        date: String?,
        processId: String?,
        page: Int?,
        size: Int?
    ): Triple<Map<String, Any?>, PageMeta, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.DASH_AI)
        val target = DateUtils.parseDate(date, "date", LocalDate.now())
        val plantCd = appProperties.defaultPlantCd
        val paging = PageRequestParam.ofAllowAll(page, size)

        val total = dashboardAiRepository.countLines(plantCd, target, processId)
        val rows = dashboardAiRepository.findLines(
            plantCd, target, processId, paging.limitOrNull, paging.offset
        )

        val meta = if (paging.isAll) PageMeta.all(total) else PageMeta.of(paging.page, paging.size, total)
        return Triple(mapOf("lines" to maskProductionRows(rows, mask)), meta, mask)
    }

    /**
     * 이상 알림 요약 (No.31)
     *
     * @param hours 조회 시간 범위 (기본 24시간)
     */
    @Transactional(readOnly = true)
    fun getAlerts(hours: Int): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.DASH_AI)
        return mapOf(
            "alerts" to dashboardAiRepository.findRecentAlerts(appProperties.defaultPlantCd, hours.coerceIn(1, 720), 20)
        )
    }

    /**
     * Agent 작동 현황 요약 (No.32)
     */
    @Transactional(readOnly = true)
    fun getAgents(): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.DASH_AI)
        return mapOf(
            "master" to dashboardAiRepository.findMasterState(),
            "agents" to dashboardAiRepository.findAgentStatus()
        )
    }

    // ---------------------------------------------------------------------------------
    // 공용 보조 로직
    // ---------------------------------------------------------------------------------

    /**
     * 생산 수량·불량률 행에 데이터 접근 권한 마스킹을 적용한다.
     */
    internal fun maskProductionRows(
        rows: List<Map<String, Any?>>,
        mask: MaskingSupport
    ): List<Map<String, Any?>> {
        val qtyAllowed = mask.check(DataField.QTY)
        val yieldAllowed = mask.check(DataField.YIELD)

        if (qtyAllowed && yieldAllowed) return rows

        return rows.map { row ->
            val m = row.toMutableMap()
            if (!qtyAllowed) {
                listOf("qty", "okQty", "ngQty").forEach { if (m.containsKey(it)) m[it] = null }
            }
            if (!yieldAllowed) {
                listOf("defectRate", "yield").forEach { if (m.containsKey(it)) m[it] = null }
            }
            m.toMap()
        }
    }

    /**
     * 행 목록(eqptCd × slot × value)을 히트맵 행렬로 변환한다.
     *
     * @return cols(시간대), rows(설비), data(2차원 값), lo/hi(색상 스케일 경계)
     */
    internal fun buildHeatmap(rows: List<Map<String, Any?>>): Map<String, Any?> {
        val cols = rows.mapNotNull { it["slot"] as? String }.distinct()
        val rowKeys = rows.mapNotNull { it["eqptCd"] as? String }.distinct()

        // (설비, 시간대) → 값 조회용 인덱스를 만들어 O(1) 로 채운다.
        val index = rows.associateBy(
            { (it["eqptCd"] as? String to it["slot"] as? String) },
            { it["value"] as? Double }
        )

        val data = rowKeys.map { eqptCd -> cols.map { slot -> index[eqptCd to slot] } }
        val values = data.flatten().filterNotNull()

        return mapOf(
            "cols" to cols,
            "rows" to rowKeys,
            "data" to data,
            "lo" to (values.minOrNull() ?: 0.0),
            "hi" to (values.maxOrNull() ?: 100.0),
            // 가동률은 낮을수록 문제이므로 색상 스케일을 반전해 표기한다.
            "invert" to true
        )
    }

    /**
     * 목표 대비 달성 수준을 판정한다.
     *
     * @return GOOD(목표 이상) / WARN(목표의 95% 이상) / BAD
     */
    internal fun judgeLevel(value: Double?, target: Double?): String {
        if (value == null || target == null || target <= 0.0) return "UNKNOWN"
        return when {
            value >= target -> "GOOD"
            value >= target * 0.95 -> "WARN"
            else -> "BAD"
        }
    }

    /**
     * "2h" 형태의 집계 구간 문자열을 시간 단위 정수로 변환한다.
     */
    internal fun parseIntervalHour(interval: String?): Int {
        if (interval.isNullOrBlank()) return DEFAULT_INTERVAL_HOUR
        val digits = interval.filter { it.isDigit() }
        return digits.toIntOrNull()?.coerceIn(1, 12) ?: DEFAULT_INTERVAL_HOUR
    }
}
