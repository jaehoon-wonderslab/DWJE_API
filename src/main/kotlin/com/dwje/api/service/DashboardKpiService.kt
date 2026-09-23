package com.dwje.api.service

import com.dwje.api.common.util.DataField
import com.dwje.api.common.util.withUntypedSegment
import com.dwje.api.common.util.DateUtils
import com.dwje.api.common.util.MaskingSupport
import com.dwje.api.common.util.MenuId
import com.dwje.api.config.AppProperties
import com.dwje.api.repository.DashboardKpiRepository
import com.dwje.api.repository.MetricStandardRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.YearMonth

/**
 * 성과지표 대시보드 서비스 (DB-03)
 *
 * KPI 정의 (기능명세서 DB-03)
 * | KPI | 명칭          | 가중치 | 산출 범위          |
 * | ①  | 공정 불량률    | 0.4   | 주력 라인 10대     |
 * | ②  | 작업공수 지수  | 0.3   | 품질관리·보고 업무 |
 * | ③  | 설비 가동률    | 0.3   | IoT 부착 10대      |
 *
 * 접근 : 화면 권한 `dash-ai`(ax.tb_sys_dept_menu_perm · 계정 추가 허용 포함) · 값 마스킹 : 데이터 권한(ax.tb_sys_dept_data_perm).
 *        부서 규칙을 코드에 두지 않는다 — 명세의 "제조팀 제외"는 제조팀에 yield 데이터 권한이 없어 값이 가려지는 것으로 처리된다.
 *
 * 권한은 `dash-ai`(AI 통합 대시보드)를 따른다. 웹이 이 KPI 를 AI 통합 대시보드 화면에서 부르기 때문이다.
 * 성과지표 대시보드 메뉴(`dash-kpi`)는 꺼져 있어(use_flg=N) 그 메뉴로 가드하면 통합관리자 외에는 403 이었다(2026-09-23 변경).
 */
@Service
class DashboardKpiService(
    private val dashboardKpiRepository: DashboardKpiRepository,
    private val metricStandardRepository: MetricStandardRepository,
    private val authorizationService: AuthorizationService,
    private val appProperties: AppProperties,
    private val objectMapper: ObjectMapper
) {

    companion object {
        /** KPI 3종 정의 — 지표 코드, 표시명, 가중치, 값이 작을수록 좋은 지표 여부 */
        private val KPI_DEFINITIONS = listOf(
            KpiDef("DEFECT_RATE", "공정 불량률", 0.4, lowerIsBetter = true),
            KpiDef("MANHOUR_INDEX", "작업공수 지수", 0.3, lowerIsBetter = true),
            KpiDef("EQPT_UPTIME_RATE", "설비 가동률", 0.3, lowerIsBetter = false)
        )

        /** AI 성능 6축 항목 — eval_json 키와 표시명 */
        private val AI_AXES = listOf(
            "intent" to "의도 정확도",
            "cite" to "인용 정확도",
            "refuse" to "거부 정확도",
            "halluc" to "환각 억제율",
            "latency" to "응답 속도",
            "coverage" to "질의 커버리지"
        )

        /** AI 성능 목표 충족 검증 항목 5종 */
        private val AI_TARGET_ITEMS = listOf(
            AiTarget("intent", "의도 정확도", 90.0),
            AiTarget("cite", "인용 정확도", 85.0),
            AiTarget("refuse", "거부 정확도", 95.0),
            AiTarget("halluc", "환각 억제율", 98.0),
            AiTarget("latency", "응답 속도", 80.0)
        )

        /** 구축 전 기준선 지수 */
        private const val BASELINE_INDEX = 100.0
    }

    /** KPI 정의 */
    private data class KpiDef(
        val metricCd: String,
        val name: String,
        val weight: Double,
        val lowerIsBetter: Boolean
    )

    /** AI 성능 목표 항목 */
    private data class AiTarget(val key: String, val label: String, val target: Double)

    /**
     * KPI 요약 3종 (No.44 — 가중치 0.4/0.3/0.3)
     *
     * @param yearMonth 대상 연월 (YYYY-MM, 미지정 시 전월)
     */
    @Transactional(readOnly = true)
    fun getSummary(yearMonth: String?): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.DASH_AI)
        val ym = DateUtils.parseYearMonth(yearMonth)

        // 수량·수율 권한이 없으면 KPI 값을 반환하지 않는다.
        val qtyAllowed = mask.check(DataField.QTY)
        val yieldAllowed = mask.check(DataField.YIELD)

        val measured = metricStandardRepository
            .findMonthlyAverage(KPI_DEFINITIONS.map { it.metricCd }, ym)
            .associateBy { it["metricCd"] as String }

        var totalRate = 0.0
        val kpis = KPI_DEFINITIONS.mapIndexed { idx, def ->
            val row = measured[def.metricCd]
            val value = row?.get("value") as? Double
            val target = row?.get("target") as? Double
            val rate = achievementRate(value, target, def.lowerIsBetter)

            // 가중 합산 종합 달성률 누적
            if (rate != null) totalRate += rate * def.weight

            // 불량률·가동률은 yield/qty 권한에 따라 마스킹한다.
            val visible = if (def.lowerIsBetter) yieldAllowed else qtyAllowed || yieldAllowed

            mapOf(
                "no" to (idx + 1),
                "metricCd" to def.metricCd,
                "name" to (row?.get("name") ?: def.name),
                "weight" to def.weight,
                "unit" to row?.get("unit"),
                "value" to if (visible) value else null,
                "target" to if (visible) target else null,
                "rate" to if (visible) rate else null,
                "level" to judgeKpiLevel(rate)
            )
        }

        return mapOf(
            "yearMonth" to ym.format(DateUtils.YEAR_MONTH),
            "kpis" to kpis,
            "totalRate" to if (yieldAllowed) Math.round(totalRate * 100) / 100.0 else null
        ) to mask
    }

    /**
     * KPI 추이 (No.45 — 구축 전 = 100 지수)
     */
    @Transactional(readOnly = true)
    fun getTrend(from: String?, to: String?): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.DASH_AI)

        if (!mask.check(DataField.YIELD)) {
            return mapOf("labels" to emptyList<String>(), "series" to emptyList<Any>(), "baseline" to BASELINE_INDEX) to mask
        }

        val (fromYm, toYm) = monthRange(from, to)
        val rows = metricStandardRepository.findMonthlyValues(KPI_DEFINITIONS.map { it.metricCd }, fromYm, toYm)

        val labels = rows.mapNotNull { it["ym"] as? String }.distinct().sorted()
        val series = KPI_DEFINITIONS.map { def ->
            val byMonth = rows.filter { it["metricCd"] == def.metricCd }
                .associate { (it["ym"] as String) to (it["value"] as? Double) }
            mapOf("name" to def.name, "data" to labels.map { byMonth[it] })
        }

        return mapOf("labels" to labels, "series" to series, "baseline" to BASELINE_INDEX) to mask
    }

    /**
     * 불량 유형 분포 (No.46)
     */
    @Transactional(readOnly = true)
    fun getDefectDistribution(yearMonth: String?): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.DASH_AI)

        if (!mask.check(DataField.YIELD)) {
            return mapOf("segments" to emptyList<Any>()) to mask
        }

        val ym = DateUtils.parseYearMonth(yearMonth)
        val plantCd = appProperties.defaultPlantCd
        // 분모는 라벨 원장 불량 총량이고, 차액은 '유형 미상' 세그먼트로 명시한다.
        // (MES_QUERY_GUIDE 2-4)
        val total = dashboardKpiRepository.findDefectLedgerTotal(plantCd, ym)
        val segments = withUntypedSegment(
            dashboardKpiRepository.findDefectDistribution(plantCd, ym), total
        )

        return mapOf(
            "yearMonth" to ym.format(DateUtils.YEAR_MONTH),
            "segments" to segments,
            "total" to total
        ) to mask
    }

    /**
     * 월별 불량 유형 추이 (No.47 — 상위 2개 유형)
     */
    @Transactional(readOnly = true)
    fun getDefectTypeTrend(from: String?, to: String?, topN: Int): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.DASH_AI)

        if (!mask.check(DataField.YIELD)) {
            return mapOf("labels" to emptyList<String>(), "series" to emptyList<Any>()) to mask
        }

        val (fromYm, toYm) = monthRange(from, to)
        val rows = dashboardKpiRepository.findDefectTypeTrend(
            appProperties.defaultPlantCd, fromYm, toYm, topN.coerceIn(1, 10)
        )

        val labels = rows.mapNotNull { it["ym"] as? String }.distinct().sorted()
        val series = rows.groupBy { it["defectNm"] as String }.map { (name, list) ->
            val byMonth = list.associate { (it["ym"] as String) to it["value"] }
            mapOf("name" to name, "data" to labels.map { byMonth[it] ?: 0L })
        }

        return mapOf("labels" to labels, "series" to series) to mask
    }

    /**
     * AI 성능 6축 (No.48)
     */
    @Transactional(readOnly = true)
    fun getAiPerformance(yearMonth: String?): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.DASH_AI)

        val profile = dashboardKpiRepository.findServingEvaluation()
        val eval = parseEvalJson(profile?.get("evalJson") as? String)

        val axes = AI_AXES.map { (key, label) ->
            mapOf(
                "key" to key,
                "label" to label,
                "value" to eval[key],
                "target" to AI_TARGET_ITEMS.firstOrNull { it.key == key }?.target
            )
        }

        return mapOf(
            "yearMonth" to DateUtils.parseYearMonth(yearMonth).format(DateUtils.YEAR_MONTH),
            "servingVer" to profile?.get("ver"),
            "axes" to axes
        )
    }

    /**
     * 부서별 작업공수 절감 (No.49 — 구축 전 대비 지수 100 = 기준선)
     */
    @Transactional(readOnly = true)
    fun getManhourSaving(from: String?, to: String?): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.DASH_AI)
        val (fromDate, toDate) = DateUtils.periodOf(from, to, 180)

        return mapOf(
            "items" to metricStandardRepository.findValuesByDept("MANHOUR_INDEX", fromDate, toDate),
            "baseline" to BASELINE_INDEX
        )
    }

    /**
     * 월별 목표 달성률 (No.50 — KPI 3종 가중 합산)
     */
    @Transactional(readOnly = true)
    fun getAchievementTrend(from: String?, to: String?): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.DASH_AI)

        if (!mask.check(DataField.YIELD)) {
            return mapOf("labels" to emptyList<String>(), "series" to emptyList<Any>()) to mask
        }

        val (fromYm, toYm) = monthRange(from, to)
        val rows = metricStandardRepository.findMonthlyValues(KPI_DEFINITIONS.map { it.metricCd }, fromYm, toYm)
        val targets = metricStandardRepository
            .findMonthlyAverage(KPI_DEFINITIONS.map { it.metricCd }, toYm)
            .associate { (it["metricCd"] as String) to (it["target"] as? Double) }

        val labels = rows.mapNotNull { it["ym"] as? String }.distinct().sorted()

        // 월별로 KPI 3종의 가중 달성률을 합산한다.
        val data = labels.map { ym ->
            var sum = 0.0
            KPI_DEFINITIONS.forEach { def ->
                val value = rows.firstOrNull { it["ym"] == ym && it["metricCd"] == def.metricCd }?.get("value") as? Double
                val rate = achievementRate(value, targets[def.metricCd], def.lowerIsBetter)
                if (rate != null) sum += rate * def.weight
            }
            Math.round(sum * 100) / 100.0
        }

        return mapOf(
            "labels" to labels,
            "series" to listOf(mapOf("name" to "종합 달성률", "data" to data))
        ) to mask
    }

    /**
     * AI 성능 목표 충족 (No.51 — 5개 항목)
     */
    @Transactional(readOnly = true)
    fun getAiTargetStatus(yearMonth: String?): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.DASH_AI)

        val profile = dashboardKpiRepository.findServingEvaluation()
        val eval = parseEvalJson(profile?.get("evalJson") as? String)

        val items = AI_TARGET_ITEMS.map { t ->
            val actual = eval[t.key]
            mapOf(
                "item" to t.label,
                "key" to t.key,
                "target" to t.target,
                "actual" to actual,
                "pass" to (actual != null && actual >= t.target)
            )
        }

        val passCnt = items.count { it["pass"] == true }

        return mapOf(
            "yearMonth" to DateUtils.parseYearMonth(yearMonth).format(DateUtils.YEAR_MONTH),
            "servingVer" to profile?.get("ver"),
            "segments" to listOf(
                mapOf("label" to "충족", "value" to passCnt),
                mapOf("label" to "미충족", "value" to (items.size - passCnt))
            ),
            "items" to items
        )
    }

    /**
     * 월별 지표 실측값 (No.52 — 히트맵)
     *
     * @param year 대상 연도
     */
    @Transactional(readOnly = true)
    fun getMonthlyMatrix(year: Int?): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.DASH_AI)

        val qtyAllowed = mask.check(DataField.QTY)
        val yieldAllowed = mask.check(DataField.YIELD)
        if (!qtyAllowed && !yieldAllowed) {
            return mapOf("cols" to emptyList<String>(), "rows" to emptyList<String>(), "data" to emptyList<Any>()) to mask
        }

        val targetYear = year ?: YearMonth.now().year
        val from = YearMonth.of(targetYear, 1)
        val to = YearMonth.of(targetYear, 12)

        val metricCodes = metricStandardRepository.findDashboardMetricCodes(null)
            .mapNotNull { it["metricCd"] as? String }
        if (metricCodes.isEmpty()) {
            return mapOf("cols" to emptyList<String>(), "rows" to emptyList<String>(), "data" to emptyList<Any>()) to mask
        }

        val rows = metricStandardRepository.findMonthlyValues(metricCodes, from, to)

        val cols = (1..12).map { String.format("%d-%02d", targetYear, it) }
        val rowNames = rows.mapNotNull { it["metricNm"] as? String }.distinct()
        val index = rows.associateBy({ (it["metricNm"] as? String) to (it["ym"] as? String) }, { it["value"] })

        return mapOf(
            "year" to targetYear,
            "cols" to cols,
            "rows" to rowNames,
            "data" to rowNames.map { name -> cols.map { index[name to it] } }
        ) to mask
    }

    /**
     * KPI 측정 기준 조회 (No.53 — 모달)
     */
    @Transactional(readOnly = true)
    fun getBasis(): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.DASH_AI)

        val basis = metricStandardRepository.findKpiBasis(KPI_DEFINITIONS.map { it.metricCd })
        val withWeight = basis.mapIndexed { idx, row ->
            val def = KPI_DEFINITIONS.firstOrNull { it.metricCd == row["metricCd"] }
            row + mapOf("no" to (idx + 1), "weight" to def?.weight)
        }

        return mapOf("kpis" to withWeight)
    }

    /**
     * KPI 증빙 내려받기 원천 데이터 (No.54 — 산출 근거 원천 데이터)
     */
    @Transactional(readOnly = true)
    fun getEvidenceRows(yearMonth: String?): Pair<List<Map<String, Any?>>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.DASH_AI)
        val ym = DateUtils.parseYearMonth(yearMonth)

        // 권한이 없는 항목은 파일에도 담지 않는다. (blind 항목 제외 후 저장)
        if (!mask.check(DataField.YIELD)) {
            return emptyList<Map<String, Any?>>() to mask
        }

        return dashboardKpiRepository.findEvidenceRows(KPI_DEFINITIONS.map { it.metricCd }, ym) to mask
    }

    // ---------------------------------------------------------------------------------
    // 내부 산출 로직
    // ---------------------------------------------------------------------------------

    /**
     * 목표 대비 달성률(%)을 산출한다.
     *
     * - 값이 클수록 좋은 지표(가동률) : 실측 ÷ 목표 × 100
     * - 값이 작을수록 좋은 지표(불량률·공수) : 목표 ÷ 실측 × 100
     */
    private fun achievementRate(value: Double?, target: Double?, lowerIsBetter: Boolean): Double? {
        if (value == null || target == null) return null
        if (lowerIsBetter) {
            if (value <= 0.0) return null
            return Math.round(target / value * 10000) / 100.0
        }
        if (target <= 0.0) return null
        return Math.round(value / target * 10000) / 100.0
    }

    /** 달성률을 3단계로 판정한다. */
    private fun judgeKpiLevel(rate: Double?): String = when {
        rate == null -> "UNKNOWN"
        rate >= 100.0 -> "GOOD"
        rate >= 90.0 -> "WARN"
        else -> "BAD"
    }

    /**
     * 서빙 프로파일의 eval_json 을 항목별 점수 맵으로 변환한다.
     */
    private fun parseEvalJson(json: String?): Map<String, Double> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            objectMapper.readTree(json).fields().asSequence()
                .mapNotNull { (key, node) -> if (node.isNumber) key to node.asDouble() else null }
                .toMap()
        }.getOrDefault(emptyMap())
    }

    /**
     * from/to 파라미터를 연월 범위로 변환한다. (미지정 시 최근 12개월)
     */
    private fun monthRange(from: String?, to: String?): Pair<YearMonth, YearMonth> {
        val toYm = if (to.isNullOrBlank()) YearMonth.now() else parseFlexibleYearMonth(to)
        val fromYm = if (from.isNullOrBlank()) toYm.minusMonths(11) else parseFlexibleYearMonth(from)
        return fromYm to toYm
    }

    /** YYYY-MM 또는 YYYY-MM-DD 형식을 모두 허용한다. */
    private fun parseFlexibleYearMonth(value: String): YearMonth =
        if (value.trim().length > 7) YearMonth.from(DateUtils.parseDate(value, "period"))
        else DateUtils.parseYearMonth(value)
}
