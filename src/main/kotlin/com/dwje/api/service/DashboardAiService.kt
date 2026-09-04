package com.dwje.api.service

import com.dwje.api.common.response.PageMeta
import com.dwje.api.common.util.DataField
import com.dwje.api.common.util.TimeWindow
import com.dwje.api.common.util.withUntypedSegment
import com.dwje.api.common.util.DateUtils
import com.dwje.api.common.util.MaskingSupport
import com.dwje.api.common.util.MenuId
import com.dwje.api.common.util.PageRequestParam
import com.dwje.api.config.AppProperties
import com.dwje.api.repository.DashboardAiRepository
import com.dwje.api.repository.DayTargetRepository
import com.dwje.api.repository.MetricStandardRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import org.slf4j.LoggerFactory

/**
 * AI 통합 대시보드 서비스 (DB-01)
 *
 * 전 부서가 열람하는 화면이며, 수량(qty)·수율(yield) 항목은 데이터 접근 권한에 따라 마스킹한다.
 */
@Service
class DashboardAiService(
    private val dashboardAiRepository: DashboardAiRepository,
    private val metricStandardRepository: MetricStandardRepository,
    private val dayTargetRepository: DayTargetRepository,
    private val authorizationService: AuthorizationService,
    private val appProperties: AppProperties
) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /**
         * 모델이 아직 붙지 않았음을 알리는 사유 코드
         *
         * 값을 지어내는 대신 이 코드를 내린다. 화면은 "모델 준비 중" 으로 그린다.
         */
        internal const val MODEL_NOT_READY = "MODEL_NOT_READY"

        /** 모델에 넘기는 불량 유형 상위 건수 */
        private const val DEFECT_TOP_N = 5

        /** 모델에 넘기는 이상 후보 설비 상위 건수 */
        private const val ANOMALY_TOP_N = 10

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
    fun getDefectTrend(
        date: String?,
        from: String? = null,
        to: String? = null,
        interval: String? = null
    ): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.DASH_AI)
        val hours = parseIntervalHour(interval)
        val plantCd = appProperties.defaultPlantCd

        val window = when {
            !from.isNullOrBlank() && !to.isNullOrBlank() -> {
                val start = DateUtils.parseDate(from, "from", LocalDate.now())
                val end = DateUtils.parseDate(to, "to", start)
                TimeWindow(start.atStartOfDay(), end.plusDays(1).atStartOfDay())
            }
            else -> {
                val target = DateUtils.parseDate(date, "date", LocalDate.now())
                TimeWindow.ofDay(target)
            }
        }

        // 수율·불량률 권한이 없으면 계열 데이터를 반환하지 않는다.
        if (!mask.check(DataField.YIELD)) {
            return mapOf("labels" to emptyList<String>(), "series" to emptyList<Any>(), "target" to null) to mask
        }

        val overall = dashboardAiRepository.findDefectTrend(plantCd, window, hours, null)
        val labels = overall.map { it["slot"] as String }

        // 전체 불량률 계열
        val series = mutableListOf<Map<String, Any?>>(
            mapOf("name" to "전체", "data" to overall.map { it["defectRate"] })
        )

        // 주 불량유형 계열 — 슬롯별 불량 수량을 라벨 순서에 맞춰 배치한다.
        val byType = dashboardAiRepository.findDefectTrendByType(plantCd, window, hours, null, TREND_TOP_DEFECT)
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

    /**
     * AI 일일 품질·생산 종합 브리핑 (sLLM)
     *
     * ## 서버의 역할
     * 지표를 모아 **마스킹을 통과한 값만** 모델에 넘기고, 결과를 그대로 내린다.
     * 문장을 서버가 쓰지 않는다.
     *
     * ## 고치기 전에 무엇이 잘못됐었나
     * 이 함수는 지표를 읽어 **직접 문장을 만들어 내려보내고 있었다.**
     *   - `planQty = 150000L` 상수 → 달성률 14,642% (실측)
     *   - "타발 압력 편차(±14%) · 금형 온도 상승(48.5℃)이 원인의 58%" —
     *     압력·온도 수집값이 이 시스템에 **없다.** 설비 지표 3값은 전부 null 이다.
     *   - "SPM 5% 감속 · 하사점 +2μm 보정 권고" — 근거 없는 처방
     *
     * 그럴듯한 문장이라 화면에서는 티가 나지 않는다. 지어낸 값을 내리는 것보다
     * **모른다고 말하는 것**이 낫다.
     *
     * ## 모델이 붙기 전까지
     * `reason = "MODEL_NOT_READY"` 로 비어 있는 응답을 낸다.
     * 화면은 그걸 받아 "모델 준비 중" 으로 그린다.
     */
    @Transactional(readOnly = true)
    fun getBriefing(date: String?): Map<String, Any?> {
        val (_, mask) = authorizationService.guard(MenuId.DASH_AI)
        val target = DateUtils.parseDate(date, "date", LocalDate.now())

        // 모델에 넘길 지표는 지금부터 모은다 — 권한 필터를 모델보다 먼저 세워 둔다.
        val input = collectBriefingInput(target, mask)
        log.debug(
            "AI 브리핑 입력 준비 : date={} 가려진 항목={} 이상후보={}건",
            input.date, input.maskedFields, input.anomalyCandidates.size
        )

        return modelNotReady(
            mapOf(
                "status" to null,
                "lines" to emptyList<Any>(),
                "generatedAt" to null,
                "modelVer" to null
            )
        )
    }

    /**
     * AI 공정 원인 분석 및 처방 권고 (XAI & Prescription, sLLM)
     *
     * ## 고치기 전에 무엇이 잘못됐었나
     * 설비 목록을 `PR-01` ~ `PR-10` 으로 **만들어 내고** 있었다.
     * 설비 마스터 1,540대 중 `PR-` 로 시작하는 코드는 **0대다.** 없는 설비다.
     * 기여 요인(압력 118.4Ton · SPM 182 · 금형온도 48.5℃ · 하사점 +8.2μm)도
     * 전부 상수였다. 그 값을 수집하는 경로가 이 시스템에 없다.
     *
     * ## 모델이 붙기 전까지
     * `reason = "MODEL_NOT_READY"` 로 비어 있는 응답을 낸다.
     */
    @Transactional(readOnly = true)
    fun getCausePrescription(date: String?, processId: String?, eqptCd: String?): Map<String, Any?> {
        val (_, mask) = authorizationService.guard(MenuId.DASH_AI)
        val target = DateUtils.parseDate(date, "date", LocalDate.now())

        val input = collectBriefingInput(target, mask, processId)
        log.debug(
            "AI 원인 분석 입력 준비 : date={} processId={} eqptCd={} 이상후보={}건",
            input.date, processId, eqptCd, input.anomalyCandidates.size
        )

        return modelNotReady(
            mapOf(
                "target" to null,
                "contributions" to emptyList<Any>(),
                "prescriptions" to emptyList<Any>(),
                "analyzedAt" to null,
                "modelVer" to null
            )
        )
    }

    /**
     * 모델이 아직 없다는 응답을 만든다.
     *
     * 값을 지어내지 않고 **없다고 분명히 말한다.** 화면이 "모델 준비 중" 으로 그린다.
     */
    private fun modelNotReady(shape: Map<String, Any?>): Map<String, Any?> =
        shape + mapOf("reason" to MODEL_NOT_READY)

    /**
     * 모델에 넘길 지표를 모은다. — **마스킹 필터는 [AiBriefingInput.of] 안에 있다**
     *
     * 이 함수는 값을 읽어 오기만 하고, 어떤 값이 어떤 권한에 걸리는지는
     * [AiBriefingInput.of] 한 곳에서 판정한다. 여기서 직접 걸러 넣으면
     * 필터가 두 곳으로 갈라져 한쪽만 고쳐지는 일이 생긴다.
     */
    internal fun collectBriefingInput(
        target: LocalDate,
        mask: MaskingSupport,
        processId: String? = null
    ): AiBriefingInput {
        val plantCd = appProperties.defaultPlantCd

        val summary = dashboardAiRepository.findSummary(plantCd, target)
        val defects = dashboardAiRepository.findDefectComposition(plantCd, target, processId)
        val lines = dashboardAiRepository.findLineProduction(plantCd, target, processId)

        val totalQty = (summary["todayQty"] as? Number)?.toLong()
        val ngQty = (summary["ngQty"] as? Number)?.toLong()
        val defectRate = (summary["defectRate"] as? Number)?.toDouble()

        // 계획 수량은 일목표 마스터에서 온다. 없으면 null 이다 — 상수를 박지 않는다.
        val effective: Map<String, Long> =
            dayTargetRepository.findEffectiveTargets(plantCd, target, emptyList())
        val planQty: Long? = if (effective.isEmpty()) null else effective.values.sum()

        return AiBriefingInput.of(
            date = target.format(DateUtils.DATE),
            mask = mask,
            totalQty = totalQty,
            okQty = (summary["okQty"] as? Number)?.toLong(),
            ngQty = ngQty,
            defectRate = defectRate,
            yieldRate = defectRate?.let { Math.round((100.0 - it) * 100.0) / 100.0 },
            planQty = planQty,
            defectComposition = defects.take(DEFECT_TOP_N).map {
                AiBriefingInput.DefectShare(
                    code = it["code"] as? String,
                    label = it["label"] as? String,
                    qty = (it["value"] as? Number)?.toLong()
                )
            },
            anomalyCandidates = anomalyCandidates(lines)
        )
    }

    /**
     * 이상 후보 설비를 고른다. — **최소 생산량 미만은 뺀다**
     *
     * 생산량이 적으면 1건 불량으로도 불량률 100% 가 된다. 고치기 전에는 그런 행이
     * 그날의 최대 이슈로 올라왔다 (실측: 1건 생산 · 1건 불량 · 불량률 100%).
     * 모델에 그대로 넘기면 모델이 그 행을 문장에 쓴다.
     *
     * 하한값([AppProperties.anomalyMinQty])은 **현업 확인 전 임시값**이다.
     * 얼마가 맞는지는 현장 감각이 필요해 설정으로 빼 두었다.
     */
    private fun anomalyCandidates(lines: List<Map<String, Any?>>): List<AiBriefingInput.AnomalyCandidate> {
        val floor = appProperties.anomalyMinQty

        return lines.asSequence()
            .mapNotNull { r ->
                val qty = (r["qty"] as? Number)?.toLong() ?: return@mapNotNull null
                if (qty < floor) return@mapNotNull null

                AiBriefingInput.AnomalyCandidate(
                    eqptCd = r["eqptCd"] as? String,
                    eqptNm = r["eqptNm"] as? String,
                    qty = qty,
                    ngQty = (r["ngQty"] as? Number)?.toLong(),
                    defectRate = (r["defectRate"] as? Number)?.toDouble()
                )
            }
            .sortedByDescending { it.defectRate ?: 0.0 }
            .take(ANOMALY_TOP_N)
            .toList()
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
