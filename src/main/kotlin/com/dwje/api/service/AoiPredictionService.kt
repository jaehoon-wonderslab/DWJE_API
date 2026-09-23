package com.dwje.api.service

import com.dwje.api.common.util.DataField
import com.dwje.api.common.util.DateUtils
import com.dwje.api.common.util.MaskingSupport
import com.dwje.api.common.util.MenuId
import com.dwje.api.config.AppProperties
import com.dwje.api.repository.MetricStandardRepository
import com.dwje.api.repository.QualityRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * AOI 판정 분석·예측 서비스 (QC-02)
 *
 * 예측 방식
 * -------
 * 온프레미스 예측 모델 서빙이 연결되기 전까지는 **관측 시계열 기반 선형 추세 + 신뢰 밴드**를
 * 베이스라인 추정기로 사용한다. 산출 근거는 「추정 근거·모델 조회」(No.84) API 가 그대로 노출하며,
 * 학습 파라미터(임계값·경계 구간)는 ax.tb_ai_model_config 에서 읽어 반영한다.
 *
 * - 추세    : 최소제곱 선형회귀 기울기
 * - 밴드    : 잔차 표준편차 × 1.96 (95% 구간)
 * - 신뢰도  : 관측 표본 수와 잔차 크기로 산출 (0~1)
 *
 * 접근 : 화면 권한 `qc-aoi`(ax.tb_sys_dept_menu_perm) · 값 마스킹 : 데이터 권한(ax.tb_sys_dept_data_perm)
 */
@Service
class AoiPredictionService(
    private val qualityRepository: QualityRepository,
    private val metricStandardRepository: MetricStandardRepository,
    private val authorizationService: AuthorizationService,
    private val agentRunRecorder: AgentRunRecorder,
    private val appProperties: AppProperties
) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** 기본 학습 관측 구간 (시간) */
        private const val DEFAULT_TRAIN_HOURS = 72

        /** 기본 예측 구간 (시간) */
        private const val DEFAULT_HORIZON_HOURS = 8

        /** 95% 신뢰구간 계수 */
        private const val CONFIDENCE_Z = 1.96

        /** 불량률 임계 지표 코드 */
        private const val THRESHOLD_METRIC = "DEFECT_RATE"

        /** 위험 LOT 조회 기간(일) */
        private const val RISK_LOT_DAYS = 3
    }

    /**
     * 예측 요약 (No.76)
     *
     * @param target      대상 범위 (공정 코드)
     * @param horizon     예측 구간 (예: "8h")
     * @param trainPeriod 학습 구간 (예: "72h")
     */
    @Transactional(readOnly = true)
    fun getPredictionSummary(
        target: String?,
        horizon: String?,
        trainPeriod: String?
    ): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.QC_AOI)

        if (!mask.check(DataField.YIELD)) {
            return mapOf("predictedDefectRate" to null, "modelConfidence" to null) to mask
        }

        val trainHours = parseHours(trainPeriod, DEFAULT_TRAIN_HOURS)
        val horizonHours = parseHours(horizon, DEFAULT_HORIZON_HOURS)
        val threshold = metricStandardRepository.findStandardValue(THRESHOLD_METRIC)

        val series = qualityRepository.findHourlyDefectSeries(appProperties.defaultPlantCd, target, trainHours)
        val values = series.mapNotNull { it["defectRate"] as? Double }
        val fit = fitLinear(values)

        val predicted = fit?.predict(values.size + horizonHours - 1)
        // 임계값 도달까지 남은 시간 = (임계값 - 현재값) / 시간당 증가량
        val thresholdEtaHours = etaToThreshold(fit, values, threshold)

        val riskLots = qualityRepository.findRiskLots(appProperties.defaultPlantCd, RISK_LOT_DAYS, 50)
        val riskLotCnt = threshold?.let { th -> riskLots.count { (it["defectRate"] as? Double ?: 0.0) >= th } }
            ?: riskLots.size

        return mapOf(
            "predictedDefectRate" to predicted?.let { round2(it) },
            "currentDefectRate" to values.lastOrNull()?.let { round2(it) },
            "threshold" to threshold,
            "thresholdReachCnt" to (thresholdEtaHours?.let { if (it <= horizonHours) 1 else 0 } ?: 0),
            "thresholdEtaHours" to thresholdEtaHours,
            "riskLotCnt" to riskLotCnt,
            "modelConfidence" to fit?.confidence(values.size),
            "trainHours" to trainHours,
            "horizonHours" to horizonHours,
            "sampleCnt" to values.size
        ) to mask
    }

    /**
     * 불량률 추이·예측 밴드 (No.77)
     *
     * 관측 구간과 예측 구간을 하나의 시계열로 이어 붙이고, 경계 인덱스(splitIndex)를 함께 반환한다.
     */
    @Transactional(readOnly = true)
    fun getTrendBand(target: String?, horizon: String?): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.QC_AOI)

        if (!mask.check(DataField.YIELD)) {
            return mapOf("labels" to emptyList<String>(), "actual" to emptyList<Any>()) to mask
        }

        val horizonHours = parseHours(horizon, DEFAULT_HORIZON_HOURS)
        val series = qualityRepository.findHourlyDefectSeries(appProperties.defaultPlantCd, target, DEFAULT_TRAIN_HOURS)
        val values = series.mapNotNull { it["defectRate"] as? Double }
        val fit = fitLinear(values)

        val labels = series.map { it["label"] as String }.toMutableList()
        val actual = values.map { round2(it) }.toMutableList<Double?>()
        val estimated = mutableListOf<Double?>()
        val bandLow = mutableListOf<Double?>()
        val bandHigh = mutableListOf<Double?>()

        // 1. 관측 구간은 회귀 적합값을 추정선으로 사용한다.
        values.indices.forEach { i ->
            val est = fit?.predict(i)
            estimated.add(est?.let { round2(it) })
            bandLow.add(est?.let { round2((it - CONFIDENCE_Z * (fit.residualSd)).coerceAtLeast(0.0)) })
            bandHigh.add(est?.let { round2(it + CONFIDENCE_Z * fit.residualSd) })
        }

        // 2. 예측 구간은 실측값 없이 추정선과 밴드만 채운다.
        (1..horizonHours).forEach { h ->
            val idx = values.size + h - 1
            labels.add("+${h}h")
            actual.add(null)
            val est = fit?.predict(idx)
            estimated.add(est?.let { round2(it) })
            bandLow.add(est?.let { round2((it - CONFIDENCE_Z * fit.residualSd).coerceAtLeast(0.0)) })
            bandHigh.add(est?.let { round2(it + CONFIDENCE_Z * fit.residualSd) })
        }

        return mapOf(
            "labels" to labels,
            "actual" to actual,
            "estimated" to estimated,
            "bandLow" to bandLow,
            "bandHigh" to bandHigh,
            "threshold" to metricStandardRepository.findStandardValue(THRESHOLD_METRIC),
            "splitIndex" to values.size
        ) to mask
    }

    /**
     * 설비별 위험 예측·권고 (No.78)
     */
    @Transactional(readOnly = true)
    fun getEquipmentRisk(target: String?, horizon: String?): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.QC_AOI)

        if (!mask.check(DataField.YIELD)) {
            return mapOf("items" to emptyList<Any>()) to mask
        }

        val horizonHours = parseHours(horizon, DEFAULT_HORIZON_HOURS)
        val threshold = metricStandardRepository.findStandardValue(THRESHOLD_METRIC)
        val moldAllowed = mask.check(DataField.MOLD)

        val stats = qualityRepository.findEquipmentDefectStats(
            appProperties.defaultPlantCd, target, DEFAULT_TRAIN_HOURS
        )

        val items = stats.map { s ->
            val current = s["currentRate"] as? Double ?: 0.0
            val base = s["baseRate"] as? Double ?: 0.0
            // 최근 구간과 전체 구간의 차이를 시간당 증가율로 환산한다.
            val slopePerHour = (current - base) / (DEFAULT_TRAIN_HOURS / 2.0).coerceAtLeast(1.0)

            val plus2h = round2((current + slopePerHour * 2).coerceAtLeast(0.0))
            val plus8h = round2((current + slopePerHour * horizonHours).coerceAtLeast(0.0))
            val eta = if (threshold != null && slopePerHour > 0.0001 && current < threshold) {
                round2((threshold - current) / slopePerHour)
            } else null

            mapOf(
                "eqptCd" to s["eqptCd"],
                "eqptNm" to s["eqptNm"],
                "aoiCd" to s["eqptCd"],
                "currentRate" to round2(current),
                "plus2h" to plus2h,
                "plus8h" to plus8h,
                "thresholdEta" to eta,
                "mainFactor" to s["mainFactor"],
                "moldCd" to if (moldAllowed) s["moldCd"] else null,
                "recommendation" to recommend(current, plus8h, threshold, s["mainFactor"] as? String),
                "confidence" to confidenceOf(s["totalQty"] as? Long)
            )
        }

        return mapOf("items" to items, "threshold" to threshold) to mask
    }

    /**
     * 출하 전 위험 LOT (No.79)
     */
    @Transactional(readOnly = true)
    fun getLotRisk(target: String?): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.QC_AOI)

        if (!mask.check(DataField.YIELD)) {
            return mapOf("items" to emptyList<Any>()) to mask
        }

        val threshold = metricStandardRepository.findStandardValue(THRESHOLD_METRIC) ?: 0.0
        val customerAllowed = mask.check(DataField.CUSTOMER)
        val qtyAllowed = mask.check(DataField.QTY)

        val items = qualityRepository.findRiskLots(appProperties.defaultPlantCd, RISK_LOT_DAYS, 20).map { lot ->
            val rate = lot["defectRate"] as? Double ?: 0.0
            // LRR 발생 확률은 임계값 대비 초과 정도로 근사한다.
            val lrrProbability = if (threshold > 0) round2((rate / threshold * 50).coerceAtMost(99.0)) else null

            mapOf(
                "lotNo" to lot["lotNo"],
                "model" to lot["model"],
                "customer" to if (customerAllowed) lot["customer"] else null,
                "qty" to if (qtyAllowed) lot["qty"] else null,
                "defectRate" to rate,
                "lrrProbability" to lrrProbability,
                "basis" to "최근 ${RISK_LOT_DAYS}일 LOT 불량률 ${rate}% · 주 불량 ${lot["mainDefect"] ?: "-"}",
                "shipDue" to null,
                "recommendation" to if (rate >= threshold) "출하 보류 후 전수 재검사 권고" else "샘플 재검사 후 출하"
            )
        }

        return mapOf("items" to items, "threshold" to threshold) to mask
    }

    /**
     * 잔여 시간 추가 발생 추정 (No.80)
     */
    @Transactional(readOnly = true)
    fun getRemainingEstimate(target: String?, horizon: String?): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.QC_AOI)

        val qtyAllowed = mask.check(DataField.QTY)
        val yieldAllowed = mask.check(DataField.YIELD)
        if (!qtyAllowed && !yieldAllowed) {
            return mapOf("estimatedNgQty" to null, "estimatedRate" to null) to mask
        }

        val horizonHours = parseHours(horizon, DEFAULT_HORIZON_HOURS)
        val series = qualityRepository.findHourlyDefectSeries(appProperties.defaultPlantCd, target, DEFAULT_TRAIN_HOURS)

        val rates = series.mapNotNull { it["defectRate"] as? Double }
        val fit = fitLinear(rates)
        val estimatedRate = fit?.predict(rates.size + horizonHours - 1)?.coerceAtLeast(0.0)

        // 시간당 평균 생산량 × 예측 구간 × 예측 불량률
        val avgHourlyQty = series.mapNotNull { it["totalQty"] as? Long }.let {
            if (it.isEmpty()) 0.0 else it.average()
        }
        val estimatedNgQty = estimatedRate?.let { Math.round(avgHourlyQty * horizonHours * it / 100.0) }

        return mapOf(
            "estimatedNgQty" to if (qtyAllowed) estimatedNgQty else null,
            "estimatedRate" to if (yieldAllowed) estimatedRate?.let { round2(it) } else null,
            "avgHourlyQty" to if (qtyAllowed) Math.round(avgHourlyQty) else null,
            "horizonHours" to horizonHours,
            "confidence" to fit?.confidence(rates.size)
        ) to mask
    }

    /**
     * AOI 판정 드리프트 (No.81)
     */
    @Transactional(readOnly = true)
    fun getInspectorDrift(from: String?, to: String?): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.QC_AOI)

        if (!mask.check(DataField.YIELD)) {
            return mapOf("items" to emptyList<Any>()) to mask
        }

        val (fromDate, toDate) = DateUtils.periodOf(from, to, 14)
        val configs = qualityRepository.findModelConfigs("CLASSIFY").associateBy { it["key"] as String }
        val borderlineRange = (configs["borderline_range"]?.get("value") as? String)?.toDoubleOrNull() ?: 5.0

        val qtyAllowed = mask.check(DataField.QTY)
        val items = qualityRepository.findAoiJudgeStats(appProperties.defaultPlantCd, fromDate, toDate).map { s ->
            val drift = s["drift"] as? Double ?: 0.0
            val sampleQty = (s["sampleQty"] as? Long) ?: 0L
            val totalQty = (s["totalQty"] as? Long) ?: 0L

            mapOf(
                "aoiCd" to s["aoiCd"],
                "aoiNm" to s["aoiNm"],
                "judgeCnt" to if (qtyAllowed) s["judgeCnt"] else null,
                "drift" to drift,
                // 드리프트 부호로 과검(+)·미검(-) 추정치를 나눈다.
                "overRejectEst" to if (drift > 0) round2(drift) else 0.0,
                "underRejectEst" to if (drift < 0) round2(abs(drift)) else 0.0,
                "recheckMatchRate" to if (totalQty > 0) round2(100.0 - abs(drift)) else null,
                "borderlineRatio" to if (totalQty > 0) round2(sampleQty * 100.0 / totalQty) else null,
                "state" to when {
                    abs(drift) >= borderlineRange * 2 -> "CRITICAL"
                    abs(drift) >= borderlineRange -> "WARNING"
                    else -> "NORMAL"
                }
            )
        }

        return mapOf("items" to items, "borderlineRange" to borderlineRange) to mask
    }

    /**
     * 불량 유형 구성 변화 (No.82)
     *
     * @param baseWeeks 비교 기준 주 수
     */
    @Transactional(readOnly = true)
    fun getDefectTypeShift(date: String?, baseWeeks: Int): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.QC_AOI)

        if (!mask.check(DataField.YIELD)) {
            return mapOf("items" to emptyList<Any>()) to mask
        }

        val target = DateUtils.parseDate(date, "date", LocalDate.now())
        val items = qualityRepository.findDefectTypeShift(
            appProperties.defaultPlantCd, target, baseWeeks.coerceIn(1, 26)
        )

        return mapOf("items" to items, "baseWeeks" to baseWeeks) to mask
    }

    /**
     * 예측 재산출 (No.83)
     *
     * 재산출 작업을 Agent 실행 이력에 기록하고 작업 ID 를 반환한다.
     */
    @Transactional
    fun recalculate(target: String?, horizon: String?, trainPeriod: String?): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.QC_AOI)

        val trainHours = parseHours(trainPeriod, DEFAULT_TRAIN_HOURS)
        val horizonHours = parseHours(horizon, DEFAULT_HORIZON_HOURS)

        // 원인 분석 Agent(④) 실행 이력으로 기록한다.
        val runId = agentRunRecorder.record(
            agentNo = AgentRunRecorder.CAUSE,
            throughput = "${trainHours}h 관측",
            message = "AOI 불량률 예측 재산출 (target=${target ?: "전체"}, train=${trainHours}h, horizon=${horizonHours}h)"
        )

        log.info("AOI 예측 재산출 : runId={} target={} train={}h horizon={}h", runId, target, trainHours, horizonHours)

        return mapOf(
            "jobId" to runId,
            "predictedAt" to java.time.LocalDateTime.now().format(DateUtils.DATETIME),
            "trainHours" to trainHours,
            "horizonHours" to horizonHours
        )
    }

    /**
     * 추정 근거·모델 조회 (No.84)
     *
     * 사용 중인 추정 방식·학습 구간·특징·검증 결과·한계를 명시한다.
     */
    @Transactional(readOnly = true)
    fun getBasis(): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.QC_AOI)

        val configs = qualityRepository.findModelConfigs("ANOMALY")
        val series = qualityRepository.findHourlyDefectSeries(appProperties.defaultPlantCd, null, DEFAULT_TRAIN_HOURS)
        val values = series.mapNotNull { it["defectRate"] as? Double }
        val fit = fitLinear(values)

        return mapOf(
            "model" to mapOf(
                "name" to "선형 추세 + 95% 신뢰 밴드 (베이스라인 추정기)",
                "type" to "OLS_LINEAR",
                "note" to "온프레미스 예측 모델 서빙 연동 전까지 사용하는 통계 베이스라인이다."
            ),
            "trainPeriod" to "최근 ${DEFAULT_TRAIN_HOURS}시간 (시간 단위 집계 ${values.size}개 표본)",
            "features" to listOf(
                mapOf("name" to "시간대별 불량률", "source" to "mes.tb_pop_label_hist"),
                mapOf("name" to "불량 유형 구성", "source" to "mes.tb_pop_defect_hist"),
                mapOf("name" to "설비 가동률", "source" to "ax.tb_met_metric_value"),
                mapOf("name" to "불량률 임계 기준", "source" to "ax.tb_met_metric_std")
            ),
            "validation" to mapOf(
                "sampleCnt" to values.size,
                "residualSd" to fit?.residualSd?.let { round2(it) },
                "slopePerHour" to fit?.slope?.let { round2(it) },
                "confidence" to fit?.confidence(values.size)
            ),
            "parameters" to configs,
            "limitations" to listOf(
                "표본이 24개(시간) 미만이면 추세 신뢰도가 급격히 낮아진다.",
                "금형 교체·자재 변경 등 계단식 변화는 선형 추세로 설명되지 않는다.",
                "경계 판정(HITL) 대기 건은 실측 불량률에 반영되지 않는다."
            )
        )
    }

    // ---------------------------------------------------------------------------------
    // 통계 베이스라인 추정기
    // ---------------------------------------------------------------------------------

    /**
     * 최소제곱 선형회귀 결과
     *
     * @param intercept  절편
     * @param slope      기울기 (시간당 변화량)
     * @param residualSd 잔차 표준편차
     */
    private data class LinearFit(val intercept: Double, val slope: Double, val residualSd: Double) {

        /** 지정 인덱스의 추정값 */
        fun predict(index: Int): Double = intercept + slope * index

        /**
         * 추정 신뢰도(0~1)를 산출한다.
         * 표본이 많고 잔차가 작을수록 높다.
         */
        fun confidence(sampleCnt: Int): Double {
            if (sampleCnt < 3) return 0.0
            val sampleScore = (sampleCnt / 48.0).coerceAtMost(1.0)
            val residualScore = 1.0 / (1.0 + residualSd)
            return Math.round(sampleScore * residualScore * 100) / 100.0
        }
    }

    /**
     * 값 시계열에 최소제곱 직선을 적합한다.
     *
     * @return 표본이 2개 미만이면 null
     */
    private fun fitLinear(values: List<Double>): LinearFit? {
        val n = values.size
        if (n < 2) return null

        val meanX = (n - 1) / 2.0
        val meanY = values.average()

        var sxx = 0.0
        var sxy = 0.0
        values.forEachIndexed { i, y ->
            val dx = i - meanX
            sxx += dx * dx
            sxy += dx * (y - meanY)
        }
        if (sxx == 0.0) return LinearFit(meanY, 0.0, 0.0)

        val slope = sxy / sxx
        val intercept = meanY - slope * meanX

        // 잔차 표준편차
        val residualSs = values.foldIndexed(0.0) { i, acc, y ->
            val residual = y - (intercept + slope * i)
            acc + residual * residual
        }
        val residualSd = if (n > 2) sqrt(residualSs / (n - 2)) else 0.0

        return LinearFit(intercept, slope, residualSd)
    }

    /**
     * 임계값 도달까지 남은 시간을 산출한다.
     *
     * @return 이미 초과했거나 증가 추세가 아니면 null
     */
    private fun etaToThreshold(fit: LinearFit?, values: List<Double>, threshold: Double?): Double? {
        if (fit == null || threshold == null) return null
        val current = values.lastOrNull() ?: return null
        if (current >= threshold) return 0.0
        if (fit.slope <= 0.0001) return null
        return round2((threshold - current) / fit.slope)
    }

    /**
     * 설비별 조치 권고 문구를 생성한다.
     */
    private fun recommend(current: Double, predicted: Double, threshold: Double?, mainFactor: String?): String {
        val factor = mainFactor ?: "주 불량 유형 미확인"
        return when {
            threshold == null -> "불량률 임계 기준이 등록되지 않았다. SY-13 에서 기준을 등록하라."
            current >= threshold -> "임계 초과 상태다. 즉시 설비 정지 후 $factor 원인 점검이 필요하다."
            predicted >= threshold -> "예측 구간 내 임계 초과가 예상된다. $factor 중심으로 사전 점검을 권고한다."
            predicted >= threshold * 0.8 -> "임계 근접 추세다. 검사 주기를 단축하고 $factor 추이를 관찰하라."
            else -> "현재 안정 구간이다. 정상 주기 점검을 유지한다."
        }
    }

    /** 관측 수량 기반 신뢰도 근사 */
    private fun confidenceOf(totalQty: Long?): Double {
        val qty = totalQty ?: 0L
        return Math.round((qty / 5000.0).coerceIn(0.0, 1.0) * 100) / 100.0
    }

    /** "8h" / "72" 형태 문자열을 시간 정수로 변환한다. */
    private fun parseHours(value: String?, default: Int): Int {
        if (value.isNullOrBlank()) return default
        val digits = value.filter { it.isDigit() }
        return digits.toIntOrNull()?.coerceIn(1, 720) ?: default
    }

    /** 소수 둘째 자리 반올림 */
    private fun round2(value: Double): Double = Math.round(value * 100) / 100.0
}
