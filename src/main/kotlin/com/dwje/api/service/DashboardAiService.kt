package com.dwje.api.service

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.response.PageMeta
import com.dwje.api.common.security.UserContext
import com.dwje.api.common.util.DataField
import com.dwje.api.common.util.DefectSql
import com.dwje.api.common.util.TimeWindow
import com.dwje.api.common.util.WorkcenterNames
import com.dwje.api.common.util.withUntypedSegment
import com.dwje.api.common.util.DateUtils
import com.dwje.api.common.util.MaskingSupport
import com.dwje.api.common.util.MenuId
import com.dwje.api.common.util.PageRequestParam
import com.dwje.api.common.util.SlotBucket
import com.dwje.api.config.AppProperties
import com.dwje.api.repository.DashboardAiRepository
import com.dwje.api.repository.DayTargetRepository
import com.dwje.api.repository.DocEvidenceRepository
import com.dwje.api.repository.MetricStandardRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime
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
    private val evidenceVerifier: AiEvidenceVerifier,
    private val sllmClient: SllmClient,
    private val docEvidenceRepository: DocEvidenceRepository,
    private val objectMapper: ObjectMapper,
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

        /**
         * 지금 다른 분석이 도는 중이라 시작하지 못했다는 사유.
         *
         * 모델이 없는 것(`MODEL_NOT_READY`)과 다르다 — 잠시 뒤 다시 하면 된다.
         * 방금 결과를 본 사용자에게 "모델 준비 중" 이라고 하면 이상하게 읽힌다.
         */
        internal const val MODEL_BUSY = "MODEL_BUSY"

        /** 모델에 넘기는 불량 유형 상위 건수 */
        /** 공장 값의 출처 — 이름에서 읽은 값임을 응답에 밝힌다. */
        private const val PLANT_SOURCE_WC_NM = "wc_nm"

        private const val DEFECT_TOP_N = 5

        /** 모델에 넘기는 이상 후보 설비 상위 건수 */
        private const val ANOMALY_TOP_N = 10

        /**
         * 대상 공정 하나에 붙이는 참고 문서 수
         *
         * 2026-09-23 dwje-ax(컨텍스트 8,192 토큰)로 옮기며 4 → 2. 대상 4곳 × 4건 × 600자이면 입력만 7,979토큰이라
         * 답에 213토큰밖에 남지 않아 매번 잘렸다(`finish_reason=length`). 2건 × 400자면 입력이 절반 아래로 준다.
         */
        private const val DOC_PER_TARGET = 2

        /** 문서 청크를 프롬프트에 넣을 때 자르는 길이 */
        private const val DOC_TEXT_LIMIT = 400

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

        /** 불량률 추이 보조 계열 개수 — `topN` 미지정 시 기본값 */
        private const val TREND_TOP_DEFECT = 2

        /**
         * `topN` 에 이 값을 주면 구간에서 발생한 불량 유형을 전부 계열로 낸다.
         *
         * 같은 화면의 불량 유형 구성(defect-composition)은 전 유형을 보이는데 추이만
         * 상위 2종이면 화면 안에서 유형 목록이 어긋난다. 화면이 전 유형을 그리겠다고
         * 명시할 때만 전량을 내리고, 기본은 상위 N 을 유지해 기존 호출을 깨지 않는다.
         */
        internal const val TREND_TOP_ALL = "all"

        /** 칸 시작 시각(`slotAt`) 으로 받는 형식들 — 화면의 칸 라벨(`2026-08-28 00시`)을 그대로 받는다. */
        private val SLOT_AT_FORMATS: List<Pair<java.time.format.DateTimeFormatter, Boolean>> = listOf(
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss") to false,
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm") to false,
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH'시'") to false,
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH") to false,
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd") to true
        )

        /**
         * 불량 유형을 전량 가져올 때 쓰는 상한.
         *
         * 전 기간 유형이 124종이라 넉넉히 잡으면 사실상 전량이다. 슬롯별 1위를
         * 정확히 내려면 상위 몇 종만 봐서는 안 된다 — 그 슬롯의 1위가 전 구간
         * 상위에 없을 수 있다.
         */
        private const val DEFECT_TYPE_ALL = 500
    }

    /**
     * 통합 요약 지표 (No.21 — KPI 카드 4종)
     *
     * @param date 기준일 (미지정 시 오늘)
     */
    @Transactional(readOnly = true)
    fun getSummary(
        date: String?,
        from: String? = null,
        to: String? = null
    ): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.DASH_AI)
        val window = windowOf(date, from, to)
        val plantCd = appProperties.defaultPlantCd

        val summary = dashboardAiRepository.findSummary(plantCd, window).toMutableMap()

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

        summary["pendingBorderline"] = dashboardAiRepository.findPendingBorderline(plantCd, window)
        // date 는 기준일(구간이면 마지막 날) — 구간 자체는 period 를 봐야 한다.
        summary["date"] = window.toExclusive.toLocalDate().minusDays(1).format(DateUtils.DATE)
        summary["period"] = periodOf(window)

        return summary.toMap() to mask
    }

    /**
     * 시간대별 불량률 추이 (No.22 — 전체 불량률 + 불량유형 수량 계열)
     *
     * `series[0]` 은 전체 불량률, `series[1..]` 는 유형별 수량(EA)이다. 유형 계열은
     * 구간 합계 내림차순이며 값은 `labels` 와 같은 길이·순서로 채운다(없는 칸은 0).
     *
     * @param date     기준일
     * @param interval 집계 구간 (예: "2h")
     * @param topN     유형 계열 수. null·빈값이면 상위 [TREND_TOP_DEFECT]종, [TREND_TOP_ALL]이면 전 유형,
     *                 양의 정수면 상위 N종
     */
    @Transactional(readOnly = true)
    fun getDefectTrend(
        date: String?,
        from: String? = null,
        to: String? = null,
        interval: String? = null,
        topN: String? = null
    ): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.DASH_AI)
        val hours = parseIntervalHour(interval)
        val typeLimit = parseTrendTopN(topN)
        val plantCd = appProperties.defaultPlantCd

        val window = windowOf(date, from, to)
        val bucket = SlotBucket.of(window, hours)

        // 수율·불량률 권한이 없으면 계열 데이터를 반환하지 않는다.
        if (!mask.check(DataField.YIELD)) {
            return mapOf(
                "labels" to emptyList<String>(),
                "series" to emptyList<Any>(),
                "target" to null,
                "period" to periodOf(window),
                "bucket" to bucket.describe()
            ) to mask
        }

        val overall = dashboardAiRepository.findDefectTrend(plantCd, window, hours, null)
        val labels = overall.map { it["slot"] as String }

        // 전체 불량률 계열
        val series = mutableListOf<Map<String, Any?>>(
            mapOf("name" to "전체", "data" to overall.map { it["defectRate"] })
        )

        // 불량 유형은 **전 유형**을 가져온다(전 기간 124종). 슬롯별 1위를 정확히 내려면
        // 상위 몇 종만 봐서는 안 된다 — 그 슬롯의 1위가 전체 상위에 없을 수 있다.
        val byType = dashboardAiRepository.findDefectTrendByType(plantCd, window, hours, null, DEFECT_TYPE_ALL)

        // 계열은 구간 합계 내림차순이다. 기본은 상위 N종만 그리고, 화면이 topN=all 로
        // 전 유형을 요구할 때만 전부 낸다(불량 유형 구성 위젯과 유형 목록을 맞추기 위해).
        val rankedNames = byType.groupBy { it["defectNm"] as String }
            .mapValues { (_, rows) -> rows.sumOf { (it["ngQty"] as? Number)?.toLong() ?: 0L } }
            .entries.sortedByDescending { it.value }
            .map { it.key }
        val topNames = if (typeLimit == null) rankedNames else rankedNames.take(typeLimit)

        topNames.forEach { name ->
            val bySlot = byType.filter { it["defectNm"] == name }
                .associate { (it["slot"] as String) to it["ngQty"] }
            series.add(mapOf("name" to name, "data" to labels.map { bySlot[it] ?: 0L }))
        }

        // 전 유형이면 '유형 미상' 도 계열로 낸다 — 불량 유형 구성 위젯과 유형 집합이 같아야 한다.
        // 구성과 같이 마지막 계열이며, 구간 발생량이 0 이면 붙이지 않는다.
        val includesUntyped = typeLimit == null && appendUntypedSeries(series, labels, plantCd, window, hours)

        // 슬롯별 1위 유형 — 계열이 상위 N종뿐이라 계열만 보면 1위를 알 수 없다.
        val topBySlot = byType.groupBy { it["slot"] as String }
            .mapValues { (_, rows) ->
                rows.maxByOrNull { (it["ngQty"] as? Number)?.toLong() ?: 0L }?.get("defectNm") as? String
            }

        val qtyAllowed = mask.check(DataField.QTY)

        return mapOf(
            "labels" to labels,
            "series" to series,
            // 계열 범위를 응답에 밝힌다. 상위 N 이면 화면이 계열 합을 총 불량으로 쓰면 안 된다.
            "seriesScope" to seriesScopeOf(typeLimit, series.size - 1, includesUntyped),
            // 슬롯별 실측 수량 — 화면 툴팁이 지어내지 않도록 서버가 낸다.
            "slots" to overall.map { row ->
                val total = (row["totalQty"] as? Number)?.toLong()
                val ng = (row["ngQty"] as? Number)?.toLong()
                mapOf(
                    "slot" to row["slot"],
                    "inputQty" to if (qtyAllowed) total else null,
                    "okQty" to if (qtyAllowed && total != null && ng != null) total - ng else null,
                    "ngQty" to if (qtyAllowed) ng else null,
                    "defectRate" to row["defectRate"],
                    "topDefectNm" to topBySlot[row["slot"]]
                )
            },
            // 불량률 목표는 지표 기준(ax.tb_met_metric_std)에 없다 — 있으면 값이 온다.
            "target" to metricStandardRepository.findStandardValue("DEFECT_RATE"),
            "period" to periodOf(window),
            // 칸 단위는 구간 길이가 정한다 — 화면이 축 라벨을 그대로 받아 그린다.
            "bucket" to bucket.describe()
        ) to mask
    }

    /**
     * 불량률 추이 칸 하나의 불량 유형 상세 (No.22 — 칸 클릭 다이얼로그)
     *
     * 칸은 [getDefectTrend] 와 같은 구간·집계 단위로 해석한다. 화면은 추이를 조회할 때 쓴 date/from/to/interval 을
     * 그대로 넘기고, 칸은 라벨(`slot`, labels[] 의 값) 또는 칸 시작 시각(`slotAt`) 으로 고른다.
     *
     * 수량은 불량 유형 구성(No.25)과 같은 라벨 원장 안분 값이라 `유형 합 + 유형 미상 = totalNgQty` 가 성립한다.
     *
     * @param slot    칸 라벨 — 추이 응답 labels[]/slots[].slot 의 값 (예: "08:00", "08-28 00시", "08-28")
     * @param slotAt  칸 시작 시각 — `yyyy-MM-dd HH:mm`, `yyyy-MM-ddTHH:mm`, `yyyy-MM-dd HH시`, `yyyy-MM-dd`.
     *                칸 안의 아무 시각이어도 그 칸으로 맞춘다. slot 보다 우선한다
     * @param plantCd 공장 코드 — 미지정 시 기본 사업장
     */
    @Transactional(readOnly = true)
    fun getDefectTrendSlotDetails(
        date: String?,
        from: String? = null,
        to: String? = null,
        interval: String? = null,
        slot: String? = null,
        slotAt: String? = null,
        plantCd: String? = null
    ): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.DASH_AI)
        val hours = parseIntervalHour(interval)
        val plant = plantCd?.trim()?.takeIf { it.isNotEmpty() } ?: appProperties.defaultPlantCd

        val window = windowOf(date, from, to)
        val bucket = SlotBucket.of(window, hours)
        val slotWindow = resolveSlot(window, bucket, slot, slotAt)

        val base = linkedMapOf<String, Any?>(
            "slot" to bucket.labelOf(slotWindow.from),
            "slotFrom" to slotWindow.from.format(DateUtils.DATETIME),
            "slotTo" to slotWindow.toExclusive.format(DateUtils.DATETIME),
            "plantCd" to plant,
            "period" to periodOf(window),
            "bucket" to bucket.describe()
        )

        // 수율·불량률 권한이 없으면 유형 상세를 내리지 않는다 — 추이·구성과 같은 정책.
        if (!mask.check(DataField.YIELD)) {
            base["inputQty"] = null; base["okQty"] = null; base["totalNgQty"] = null
            base["typedNgQty"] = null; base["untypedNgQty"] = null; base["defectRate"] = null
            base["items"] = emptyList<Any>()
            return base.toMap() to mask
        }

        val qtyAllowed = mask.check(DataField.QTY)
        val workerAllowed = mask.check(DataField.WORKER)
        val totals = dashboardAiRepository.findSlotLabelTotals(plant, slotWindow)
        val total = (totals["ngQty"] as? Number)?.toLong() ?: 0L
        val input = (totals["totalQty"] as? Number)?.toLong()

        val rows = dashboardAiRepository.findSlotDefectDetails(plant, slotWindow)
        val typed = rows.sumOf { (it["ngQty"] as? Number)?.toLong() ?: 0L }
        // 유형 미상 = 칸 불량 − 유형 안분 합. 표시값 기준 차액이라 부분의 합이 total 과 정확히 맞는다.
        val untyped = (total - typed).coerceAtLeast(0L)

        val items = rows.mapIndexed { i, row ->
            val ng = (row["ngQty"] as? Number)?.toLong() ?: 0L
            linkedMapOf<String, Any?>(
                "rank" to i + 1,
                "defectTypeCd" to row["defectCd"],
                "defectType" to row["defectNm"],
                "untyped" to false,
                "ngQty" to if (qtyAllowed) ng else null,
                "ratio" to com.dwje.api.common.util.safeRate(ng.toBigDecimal(), total.toBigDecimal()),
                "rawQty" to if (qtyAllowed) row["rawQty"] else null,
                "recordCount" to row["recordCount"],
                "lotCount" to row["lotCount"],
                "itemCount" to row["itemCount"],
                "itemCds" to row["itemCds"],
                "processIds" to row["processIds"],
                "remarks" to row["remarks"],
                "insUsers" to if (workerAllowed) row["insUsers"] else null,
                "firstAt" to row["firstAt"],
                "lastAt" to row["lastAt"],
                "useFlg" to row["useFlg"],
                "masterRemark" to row["masterRemark"]
            )
        }.toMutableList<Map<String, Any?>>()

        if (untyped > 0L) {
            items.add(
                mapOf(
                    "rank" to items.size + 1,
                    "defectTypeCd" to null,
                    "defectType" to DefectSql.UNTYPED_LABEL,
                    "untyped" to true,
                    "ngQty" to if (qtyAllowed) untyped else null,
                    "ratio" to com.dwje.api.common.util.safeRate(untyped.toBigDecimal(), total.toBigDecimal()),
                    "rawQty" to null,
                    "recordCount" to 0L,
                    "lotCount" to 0L,
                    "itemCount" to 0L,
                    "itemCds" to emptyList<String>(),
                    "processIds" to emptyList<String>(),
                    "remarks" to emptyList<String>(),
                    "insUsers" to if (workerAllowed) emptyList<String>() else null,
                    "firstAt" to null,
                    "lastAt" to null,
                    "useFlg" to null,
                    "masterRemark" to null
                )
            )
        }

        base["inputQty"] = if (qtyAllowed) input else null
        base["okQty"] = if (qtyAllowed && input != null) input - total else null
        base["totalNgQty"] = if (qtyAllowed) total else null
        base["typedNgQty"] = if (qtyAllowed) typed.coerceAtMost(total) else null
        base["untypedNgQty"] = if (qtyAllowed) untyped else null
        base["defectRate"] = totals["defectRate"]
        base["labelCount"] = totals["labelCount"]
        base["items"] = items
        return base.toMap() to mask
    }

    /**
     * 칸 라벨 또는 칸 시작 시각을 실제 조회 구간으로 바꾼다.
     *
     * `slotAt` 이 있으면 그 시각이 속한 칸, 없으면 `slot` 라벨의 칸이다. 칸 끝은 조회 구간을 넘지 않는다.
     */
    internal fun resolveSlot(window: TimeWindow, bucket: SlotBucket, slot: String?, slotAt: String?): TimeWindow {
        val at = slotAt?.trim()?.takeIf { it.isNotEmpty() }
        val label = slot?.trim()?.takeIf { it.isNotEmpty() }

        val start = when {
            at != null -> bucket.slotStartOf(parseSlotAt(at))
            label != null -> bucket.parseLabel(label, window)
                ?: throw InvalidParameterException(
                    "조회 구간(${periodLabel(window)})에 없는 칸 라벨입니다. 추이 응답의 labels 값을 쓰세요. [slot=$slot]", "slot"
                )
            else -> throw InvalidParameterException("칸을 고르는 slot 또는 slotAt 가 필요합니다.", "slotAt")
        }

        if (start.isBefore(window.from) || !start.isBefore(window.toExclusive)) {
            throw InvalidParameterException(
                "칸 시각이 조회 구간(${periodLabel(window)}) 밖입니다. [slotAt=${start.format(DateUtils.DATETIME)}]", "slotAt"
            )
        }
        val end = minOf(bucket.slotEndOf(start), window.toExclusive)
        return TimeWindow(maxOf(start, window.from), end)
    }

    private fun parseSlotAt(value: String): LocalDateTime {
        val text = value.replace('T', ' ').trim()
        SLOT_AT_FORMATS.forEach { (pattern, dateOnly) ->
            try {
                return if (dateOnly) LocalDate.parse(text, pattern).atStartOfDay() else LocalDateTime.parse(text, pattern)
            } catch (e: java.time.format.DateTimeParseException) {
                // 다음 형식으로
            }
        }
        throw InvalidParameterException(
            "slotAt 형식이 올바르지 않습니다. yyyy-MM-dd HH:mm · yyyy-MM-dd HH시 · yyyy-MM-dd 중 하나여야 합니다. [slotAt=$value]",
            "slotAt"
        )
    }

    /**
     * 라인별 생산량·불량률 (No.23)
     */
    @Transactional(readOnly = true)
    fun getLineProduction(
        date: String?,
        processId: String?,
        from: String? = null,
        to: String? = null
    ): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.DASH_AI)
        val window = windowOf(date, from, to)

        val rows = dashboardAiRepository.findLineProduction(appProperties.defaultPlantCd, window, processId)
        val masked = maskProductionRows(rows, mask)

        return mapOf("lines" to masked, "period" to periodOf(window)) to mask
    }

    /**
     * 공정 품질 지수 6축 (No.24)
     */
    @Transactional(readOnly = true)
    fun getQualityIndex(
        date: String?,
        from: String? = null,
        to: String? = null
    ): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.DASH_AI)
        val window = windowOf(date, from, to)

        if (!mask.check(DataField.YIELD)) {
            return mapOf("axes" to emptyList<Any>(), "period" to periodOf(window)) to mask
        }

        val axes = dashboardAiRepository.findQualityIndex(
            appProperties.defaultPlantCd, window, QUALITY_INDEX_METRICS
        )
        return mapOf("axes" to axes, "period" to periodOf(window)) to mask
    }

    /**
     * 불량 유형 구성 (No.25 — 경계 판정 건 제외 표기)
     */
    @Transactional(readOnly = true)
    fun getDefectComposition(
        date: String?,
        processId: String?,
        from: String? = null,
        to: String? = null
    ): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.DASH_AI)
        val window = windowOf(date, from, to)
        val plantCd = appProperties.defaultPlantCd

        if (!mask.check(DataField.YIELD)) {
            return mapOf(
                "segments" to emptyList<Any>(),
                "total" to null,
                "period" to periodOf(window)
            ) to mask
        }

        // 분모는 라벨 원장 불량 총량. 표시된 유형 합으로 잡으면 유형 미상 물량이 분모에서 빠져
        // 비중이 부풀려진다. 차액은 '유형 미상' 세그먼트로 명시한다. (MES_QUERY_GUIDE 2-4)
        val total = dashboardAiRepository.findDefectLedgerTotal(plantCd, window, processId)
        val segments = withUntypedSegment(
            dashboardAiRepository.findDefectComposition(plantCd, window, processId), total
        )
        val borderline = dashboardAiRepository.findPendingBorderline(plantCd, window)

        return mapOf(
            "segments" to segments,
            "total" to total,
            // 경계 판정 대기 건은 구성 비율에서 제외되었음을 명시한다.
            "excludedBorderline" to borderline["cnt"],
            "period" to periodOf(window)
        ) to mask
    }

    /**
     * 공정별 수율 (No.26)
     */
    @Transactional(readOnly = true)
    fun getProcessYield(
        date: String?,
        from: String? = null,
        to: String? = null
    ): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.DASH_AI)
        val window = windowOf(date, from, to)

        if (!mask.check(DataField.YIELD)) {
            return mapOf(
                "items" to emptyList<Any>(),
                "target" to null,
                "period" to periodOf(window)
            ) to mask
        }

        val targetYield = metricStandardRepository.findStandardValue("PROD_YIELD_RATE")
        val items = dashboardAiRepository.findProcessYield(appProperties.defaultPlantCd, window).map { row ->
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
            "note" to "양품 수량 ÷ (양품 + 불량) 기준. 재작업 투입분은 제외한다.",
            "period" to periodOf(window)
        ) to mask
    }

    /**
     * 생산 계획 대비 실적 (No.27)
     */
    @Transactional(readOnly = true)
    fun getPlanVsActual(
        date: String?,
        interval: String?,
        from: String? = null,
        to: String? = null
    ): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.DASH_AI)
        val window = windowOf(date, from, to)
        val hours = parseIntervalHour(interval)
        val bucket = SlotBucket.of(window, hours)

        // 계획 수량은 plan, 실적 수량은 qty 권한이 필요하다.
        val planAllowed = mask.check(DataField.PLAN)
        val qtyAllowed = mask.check(DataField.QTY)

        val rows = dashboardAiRepository.findPlanVsActual(appProperties.defaultPlantCd, window, hours)
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
            } else null,
            "period" to periodOf(window),
            "bucket" to bucket.describe()
        ) to mask
    }

    /**
     * 설비별 시간대 가동률 히트맵 (No.28 — 낮을수록 진하게 표기)
     */
    @Transactional(readOnly = true)
    fun getEquipmentUptimeHeatmap(
        date: String?,
        processId: String?,
        interval: String?,
        from: String? = null,
        to: String? = null
    ): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.DASH_AI)
        val window = windowOf(date, from, to)
        val hours = parseIntervalHour(interval)
        val bucket = SlotBucket.of(window, hours)

        val rows = dashboardAiRepository.findEquipmentUptimeHeatmap(
            appProperties.defaultPlantCd, window, processId, hours
        )
        return buildHeatmap(rows) + mapOf(
            "period" to periodOf(window),
            "bucket" to bucket.describe()
        )
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
        size: Int?,
        from: String? = null,
        to: String? = null
    ): Triple<Map<String, Any?>, PageMeta, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.DASH_AI)
        val window = windowOf(date, from, to)
        val plantCd = appProperties.defaultPlantCd
        val paging = PageRequestParam.ofAllowAll(page, size)

        val total = dashboardAiRepository.countLines(plantCd, window, processId)
        val rows = dashboardAiRepository.findLines(
            plantCd, window, processId, paging.limitOrNull, paging.offset
        )

        val meta = if (paging.isAll) PageMeta.all(total) else PageMeta.of(paging.page, paging.size, total)
        return Triple(
            mapOf("lines" to maskProductionRows(rows, mask), "period" to periodOf(window)),
            meta,
            mask
        )
    }

    /**
     * 설비 × 제품 실적 목록 (실적 집계 조회 3단계)
     *
     * [getLines] 는 설비 한 대에 한 행이라 두 제품 이상 돌린 설비의 수량이 대표 제품
     * 한 칸에 몰린다. 제품으로 묶어 그리려면 이 목록을 쓴다.
     *
     * 공장은 작업장 이름에 적힌 것만 채운다 — DB 에 공장 축이 없다.
     */
    @Transactional(readOnly = true)
    fun getLineProducts(
        date: String?,
        processId: String?,
        page: Int?,
        size: Int?,
        from: String? = null,
        to: String? = null
    ): Triple<Map<String, Any?>, PageMeta, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.DASH_AI)
        val window = windowOf(date, from, to)
        val plantCd = appProperties.defaultPlantCd
        val paging = PageRequestParam.ofAllowAll(page, size)

        val total = dashboardAiRepository.countLineProducts(plantCd, window, processId)
        val rows = dashboardAiRepository.findLineProducts(
            plantCd, window, processId, paging.limitOrNull, paging.offset
        ).map { row ->
            val plantNm = plantOf(row["processNm"] as? String)
            row + mapOf(
                "plantNm" to plantNm,
                "plantSource" to plantNm?.let { PLANT_SOURCE_WC_NM }
            )
        }

        val meta = if (paging.isAll) PageMeta.all(total) else PageMeta.of(paging.page, paging.size, total)
        return Triple(
            mapOf("lines" to maskProductionRows(rows, mask), "period" to periodOf(window)),
            meta,
            mask
        )
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
    fun getBriefing(date: String?, from: String?, to: String?): Map<String, Any?> {
        val (_, mask) = authorizationService.guard(MenuId.DASH_AI)
        val window = windowOf(date, from, to)

        // 모델에 넘길 지표는 지금부터 모은다 — 권한 필터를 모델보다 먼저 세워 둔다.
        val input = collectBriefingInput(window, mask)
        log.debug(
            "AI 브리핑 입력 준비 : date={} 가려진 항목={} 이상후보={}건",
            input.date, input.maskedFields, input.anomalyCandidates.size
        )

        val model = callModel(
            system = AiPrompt.BRIEFING_SYSTEM,
            user = AiPrompt.userMessage(input),
            schema = AiPrompt.BRIEFING_SCHEMA,
            field = "lines"
        )

        return verifyAndBuild(
            modelLines = model.lines,
            reason = model.reason,
            window = window,
            mask = mask,
            emptyShape = mapOf(
                "status" to null,
                "lines" to emptyList<Any>(),
                "periodFrom" to window.from.format(DateUtils.DATETIME),
                "periodTo" to window.toExclusive.format(DateUtils.DATETIME),
                "generatedAt" to null,
                "modelVer" to null
            )
        ) { verified ->
            mapOf(
                // 대조를 통과한 문장이 하나도 없으면 상태도 내지 않는다.
                "status" to if (verified.lines.isEmpty()) null else "OK",
                "lines" to verified.lines,
                "droppedCnt" to verified.droppedCnt,
                "periodFrom" to window.from.format(DateUtils.DATETIME),
                "periodTo" to window.toExclusive.format(DateUtils.DATETIME),
                "generatedAt" to LocalDateTime.now().format(DateUtils.DATETIME),
                "modelVer" to appProperties.ai.model
            )
        }
    }

    /**
     * AI 공정 원인 분석 및 처방 권고 (XAI & Prescription, sLLM)
     *
     * ## 대상은 하나가 아니다 (2026-09-05 사용자 지시)
     * "불량률이 3.5% 가 넘어가는 **모든 공정**에 대해서 결과를 정리해서 보여줘."
     * 가장 나쁜 한 건만 보면 아침회의에서 "오늘 볼 것" 을 정할 수 없다.
     *
     * ## 원인도 모델이 쓴다 (2026-09-23)
     * 예전에는 서버가 고정 문장("…공정의 불량률이 기준을 넘었습니다")을 원인 칸에 넣었다. AI 결과로 보이는 자리에
     * 코드에 박힌 문장을 두지 않는다. 대상마다 「원인 지표」(공정 수율 · 최다 불량 설비 불량률)를 kind·key 와 함께 주고
     * 모델이 원인을 쓰면, 서버가 그 값을 다시 계산해 대조한다([AiEvidenceVerifier]). 틀린 값은 버린다.
     * thinking 을 끈 dwje-ax(`reasoning_effort: none`)라 추론 토큰 부담은 없다.
     *
     * ## 원인·처방은 한 번에 모아서 부른다
     * 대상마다 부르면 호출 수만큼 추론 비용이 곱해진다. 한 응답에 모든 대상의 조치를
     * 받고 `processId` 로 묶는다. 대신 응답이 길어지므로 대상 수에 상한을 둔다
     * ([AiProperties.causeMaxTargets]) — 넘치면 잘린 응답이 통째로 버려진다.
     *
     * @param threshold 불량률 기준(%). 미지정 시 설정값(기본 3.5)
     */
    @Transactional(readOnly = true)
    fun getCausePrescription(
        date: String?,
        from: String?,
        to: String?,
        processId: String?,
        eqptCd: String?,
        threshold: Double?
    ): Map<String, Any?> {
        val (_, mask) = authorizationService.guard(MenuId.DASH_AI)
        val window = windowOf(date, from, to)
        val cfg = appProperties.ai
        val limit = threshold ?: cfg.causeThreshold

        val candidates = findTargets(window, processId, limit)
        val analyzed = candidates.take(cfg.causeMaxTargets)
        val omitted = candidates.size - analyzed.size

        // 기준을 넘는 공정이 없으면 모델을 부르지 않는다. 부를 이유가 없다.
        if (analyzed.isEmpty()) {
            return mapOf(
                "threshold" to limit,
                "targets" to emptyList<Any>(),
                "omittedCnt" to 0,
                "droppedCnt" to 0,
                "periodFrom" to window.from.format(DateUtils.DATETIME),
                "periodTo" to window.toExclusive.format(DateUtils.DATETIME),
                "analyzedAt" to LocalDateTime.now().format(DateUtils.DATETIME),
                "modelVer" to cfg.model,
                "reason" to null
            )
        }

        val input = collectBriefingInput(window, mask, processId)
        // 대상마다 따로 검색한다. 여러 대상의 이름을 한 질의에 섞으면 뜻이 희석돼
        // 어느 대상과도 무관한 문서가 올라온다. 검색은 임베딩 한 번 + 조회라 싸다.
        val docsByTarget = analyzed.associate { it.processId to searchDocs(input, it, eqptCd) }
        val model = callPrescriptions(input, analyzed, docsByTarget)

        // 원인·처방 모두 모델이 쓰고 서버가 근거를 대조한다. 대조를 통과하지 못한 문장은 버린다(droppedCnt).
        // 모델이 없으면 대상 표(공정·설비·불량률 — 지표 그대로)만 보이고 원인·처방 칸은 비어 있다.
        val verified = if (model.lines == null) {
            null
        } else {
            evidenceVerifier.verifyLines(model.lines, window, mask, UserContext.current().userId)
        }

        val keptText = verified?.lines?.associateBy { it["text"] as? String } ?: emptyMap()
        fun keptBy(section: String) = (model.lines ?: emptyList())
            .filter { it["section"] == section }
            .mapNotNull { keptText[it["text"] as? String]?.plus("processId" to it["processId"]) }
            .groupBy { it["processId"] as? String }
        val contributionsBy = keptBy("contribution")
        val prescriptionsBy = keptBy("prescription")

        val qtyAllowed = mask.check(DataField.QTY)
        val targets = analyzed.map { t ->
            mapOf(
                "processId" to t.processId,
                "processNm" to t.processNm,
                "eqptCd" to t.eqptCd,
                "eqptNm" to t.eqptNm,
                // 공장은 마스터에 없다. 다만 **작업장 이름에 적혀 있으면** 그것을 읽는다 —
                // 접두로 추정하는 것과 달리 현업이 직접 적어 둔 값을 옮기는 것이다.
                // 적혀 있지 않으면 null 이다. 어디서 온 값인지 plantSource 로 밝힌다.
                // plantCd 는 늘 null 이다 — 공장 **코드** 체계가 어느 마스터에도 없다.
                // 이름에 적힌 것은 표시용 이름("M-3공장")이지 코드가 아니다.
                "plantCd" to null,
                "plantNm" to plantOf(t.processNm),
                "plantSource" to plantOf(t.processNm)?.let { PLANT_SOURCE_WC_NM },
                // 그 설비가 그 구간에 가장 많이 만든 제품 하나 + 나머지 종 수.
                "product" to t.product,
                "productNm" to t.productNm,
                "productEtcCnt" to t.productEtcCnt,
                "defectRate" to t.defectRate,
                // 기준을 넘어도 분모가 작으면 사람이 순위를 달리 본다.
                "numerator" to if (qtyAllowed) t.ngQty else null,
                "denominator" to if (qtyAllowed) t.qty else null,
                "contributions" to (contributionsBy[t.processId] ?: emptyList()),
                "prescriptions" to (prescriptionsBy[t.processId] ?: emptyList())
            )
        }

        return mapOf(
            // 화면이 "기준 초과 N곳" 을 적을 때 지어내지 않도록 서버가 알린다.
            "threshold" to limit,
            "targets" to targets,
            // 상한에 걸려 빠진 공정 수 — 조용히 빠지면 안 된다.
            "omittedCnt" to omitted,
            "droppedCnt" to (verified?.droppedCnt ?: 0),
            "periodFrom" to window.from.format(DateUtils.DATETIME),
            "periodTo" to window.toExclusive.format(DateUtils.DATETIME),
            "analyzedAt" to LocalDateTime.now().format(DateUtils.DATETIME),
            "modelVer" to cfg.model,
            "reason" to model.reason
        )
    }

    /**
     * 기준을 넘는 공정을 찾는다. — 불량률 높은 순
     *
     * 공정 단위로 판정하고, 그 안에서 가장 나쁜 설비를 함께 붙인다.
     * 사용자가 말한 "공정" 이 판정 단위이고 설비는 어디를 볼지 알려 주는 정보다.
     */
    private fun findTargets(window: TimeWindow, processId: String?, threshold: Double): List<CauseTarget> {
        val plantCd = appProperties.defaultPlantCd
        val wanted = processId?.trim()?.takeIf { it.isNotBlank() }

        return dashboardAiRepository.findProcessYield(plantCd, window)
            .asSequence()
            .filter { wanted == null || it["processId"] == wanted }
            .mapNotNull { row ->
                val qty = (row["qty"] as? Number)?.toLong() ?: return@mapNotNull null
                val ngQty = (row["ngQty"] as? Number)?.toLong() ?: 0L
                if (qty < appProperties.anomalyMinQty) return@mapNotNull null

                val rate = Math.round(ngQty * 10000.0 / qty) / 100.0
                if (rate <= threshold) return@mapNotNull null

                val wcCd = row["processId"] as? String ?: return@mapNotNull null
                val worst = dashboardAiRepository.findLineProduction(plantCd, window, wcCd)
                    .filter { (it["qty"] as? Number)?.toLong() ?: 0L >= appProperties.anomalyMinQty }
                    .maxByOrNull { (it["defectRate"] as? Number)?.toDouble() ?: 0.0 }

                CauseTarget(
                    processId = wcCd,
                    processNm = row["process"] as? String,
                    eqptCd = worst?.get("eqptCd") as? String,
                    eqptNm = worst?.get("eqptNm") as? String,
                    product = worst?.get("product") as? String,
                    productNm = worst?.get("productNm") as? String,
                    productEtcCnt = (worst?.get("productEtcCnt") as? Number)?.toInt() ?: 0,
                    // 설비 불량률을 함께 들고 온다. 원인 문장의 근거 값이라
                    // 0 같은 자리표시자를 넣으면 대조에서 탈락한다 (실측으로 겪었다).
                    eqptDefectRate = (worst?.get("defectRate") as? Number)?.toDouble(),
                    defectRate = rate,
                    qty = qty,
                    ngQty = ngQty
                )
            }
            .sortedByDescending { it.defectRate }
            .toList()
    }

    /** 여러 대상의 조치를 **한 번의 호출로** 받는다. */
    private fun callPrescriptions(
        input: AiBriefingInput,
        targets: List<CauseTarget>,
        docsByTarget: Map<String, List<Map<String, Any?>>>
    ): ModelLines {
        if (!sllmClient.isEnabled()) return ModelLines(null, MODEL_NOT_READY)

        // 대상과 그 대상의 참고 문서를 붙여서 준다. 문서 목록을 한데 모아 주면
        // 모델이 어느 대상의 조치인지 헷갈려 아무 문서나 붙인다.
        val block = buildString {
            appendLine("분석 대상 공정마다 조치를 쓴다. processId 에는 아래 코드를 그대로 넣는다.")
            targets.forEach { t ->
                appendLine()
                appendLine("[${t.processId}] ${t.processNm} · 불량률 ${t.defectRate}%" +
                    (t.eqptNm?.let { nm -> " · 최다 불량 설비 $nm" } ?: ""))
                // 원인 문장이 인용할 지표 — 서버 대조기(AiEvidenceVerifier)가 다시 계산해 맞춰 보는 kind·key 다.
                appendLine("  원인 지표 (contributions 의 근거로 쓴다)")
                appendLine("  - 공정 수율 ${"%.2f".format(100.0 - t.defectRate)}%  (kind=yield, key=${t.processId})")
                if (t.eqptCd != null && t.eqptDefectRate != null) {
                    appendLine("  - 최다 불량 설비 ${t.eqptNm ?: t.eqptCd} 불량률 ${t.eqptDefectRate}%  (kind=anomaly, key=${t.eqptCd})")
                }

                val docs = docsByTarget[t.processId].orEmpty()
                if (docs.isEmpty()) {
                    appendLine("  참고 문서 없음 — 이 공정은 조치를 쓰지 않는다.")
                } else {
                    appendLine("  참고 문서 (kind=doc, key 에 각 항목 앞의 숫자를 그대로 넣는다)")
                    docs.forEach {
                        appendLine("  - ${it["chunkId"]} : ${it["title"]}")
                        appendLine("    ${(it["text"] as? String)?.replace("\n", " ")?.take(DOC_TEXT_LIMIT)}")
                    }
                }
            }
        }

        return when (val r = sllmClient.chatJson(AiPrompt.CAUSE_SYSTEM, AiPrompt.userMessage(input, block), AiPrompt.CAUSE_SCHEMA)) {
            // 원인과 처방을 한 번에 받는다. 두 배열을 구분하려고 절(section)을 줄마다 붙인다.
            is SllmResult.Ok -> ModelLines(
                readLines(r.node, "contributions").map { it + ("section" to "contribution") } +
                    readLines(r.node, "prescriptions").map { it + ("section" to "prescription") },
                null
            )
            SllmResult.Busy -> ModelLines(null, MODEL_BUSY)
            SllmResult.Failed -> ModelLines(null, MODEL_NOT_READY)
        }
    }

    /**
     * 처방 근거 후보를 검색한다.
     *
     * 질의 문장은 **대상 공정·설비와 불량 유형**으로 만든다. 문서에 공정·설비 코드가
     * 비어 있어(1,476건 전부) 코드로 거를 수 없으므로 검색으로 관련성을 찾는다.
     */
    private fun searchDocs(
        input: AiBriefingInput,
        target: CauseTarget,
        eqptCd: String?
    ): List<Map<String, Any?>> {
        val terms = buildList {
            target.processNm?.let { add(it) }
            target.eqptNm?.let { add(it) }
            eqptCd?.trim()?.takeIf { it.isNotBlank() }?.let { add(it) }
            input.defectComposition.take(3).mapNotNull { it.label }.forEach { add(it) }
            add("원인")
            add("대책")
        }
        val query = terms.distinct().joinToString(" ")

        val embedding = sllmClient.embed(query) ?: run {
            // 임베딩 모델이 없는 환경(사내 LLM 서버에는 bge-m3 가 없다) — 키워드 검색으로 대신한다.
            log.info("질의 임베딩에 실패해 키워드 검색으로 문서 근거를 찾습니다 : query={}", query)
            return docEvidenceRepository.searchPrescriptionCandidatesByKeyword(
                userId = UserContext.current().userId,
                queryText = query,
                limit = DOC_PER_TARGET
            )
        }

        return docEvidenceRepository.searchPrescriptionCandidates(
            userId = UserContext.current().userId,
            queryText = query,
            queryEmbedding = embedding,
            limit = DOC_PER_TARGET
        )
    }

    /**
     * 조회 구간을 정한다.
     *
     * `from`·`to` 가 오면 그 구간 전체, `date` 만 오면 그날 하루, 둘 다 없으면 오늘이다.
     *
     * 화면이 기간을 고르면 `date` 에 **종료일**을 넣어 보내는데, 그것만 보면
     * 한 달을 골라도 마지막 하루만 분석된다. 실제로 그렇게 어긋나 있었다.
     */
    /**
     * 작업장 이름에 적힌 공장을 읽는다. — `"C1-FQC(M-3공장)"` → `"M-3공장"`
     *
     * ## 추정이 아니라 전사(轉寫)다
     * 이름 접두(A→M-1)로 **추정**하는 것은 하지 않는다 — 근거가 없다.
     * 그러나 이름 안에 `(M-3공장)` 이라고 적혀 있으면 그것은 현업이 직접 적어 둔 값이고,
     * 읽는 것은 지어내는 것이 아니다.
     *
     * ## 적힌 것만 읽는다
     * 활성 작업장 37개 중 9개에만 적혀 있고 나머지 28개는 `null` 이다.
     * 표기는 예외 없이 일정하다 — '공장' 글자를 가진 9개가 전부 `(M-n공장)` 형태이고
     * 다른 표기는 0건이다 (2026-09-06 확인). 그래서 정규식 하나로 충분하다.
     *
     * 뽑는 규칙을 화면과 서버 두 곳에 두면 나중에 어긋나므로 서버에만 둔다.
     */
    private fun plantOf(processNm: String?): String? = WorkcenterNames.plantOf(processNm)

    /**
     * 구간을 사람이 읽는 문자열로 만든다.
     *
     * 하루면 날짜 하나, 길면 시작~종료다. 모델이 문장에 기간을 밝히려면
     * 입력에 기간이 그렇게 적혀 있어야 한다 — "총 생산 수량은 …" 만으로는
     * 하루인지 한 달인지 구분되지 않는다.
     */
    /** 화면이 "무엇을 집계했는지" 를 그대로 보여줄 수 있도록 구간을 응답에 싣는다. */
    private fun periodOf(window: TimeWindow): Map<String, Any?> = mapOf(
        "from" to window.from.toLocalDate().format(DateUtils.DATE),
        "to" to window.toExclusive.toLocalDate().minusDays(1).format(DateUtils.DATE),
        "label" to periodLabel(window)
    )

    private fun periodLabel(window: TimeWindow): String {
        val start = window.from.toLocalDate()
        val endInclusive = window.toExclusive.toLocalDate().minusDays(1)
        return if (start == endInclusive) {
            start.format(DateUtils.DATE)
        } else {
            "${start.format(DateUtils.DATE)} ~ ${endInclusive.format(DateUtils.DATE)}"
        }
    }

    private fun windowOf(date: String?, from: String?, to: String?): TimeWindow {
        val fromDate = from?.trim()?.takeIf { it.isNotBlank() }?.let { DateUtils.parseDate(it, "from") }
        val toDate = to?.trim()?.takeIf { it.isNotBlank() }?.let { DateUtils.parseDate(it, "to") }

        if (fromDate != null || toDate != null) {
            val start = fromDate ?: toDate!!
            val end = toDate ?: fromDate!!
            if (start.isAfter(end)) {
                throw InvalidParameterException("조회 시작일이 종료일보다 늦습니다. [$start ~ $end]", "from")
            }
            // 종료일을 포함한다 — 8/1~8/28 을 고르면 28일도 본 것이어야 한다.
            return TimeWindow(start.atStartOfDay(), end.plusDays(1).atStartOfDay())
        }

        return TimeWindow.ofDay(DateUtils.parseDate(date, "date", LocalDate.now()))
    }

    /**
     * 분석 대상 한 건
     *
     * ## 공장(M-1 · M-2 · M-3)은 담지 않는다 — 데이터가 없다 (2026-09-06 전수 확인)
     * `plant_cd` 는 label_hist·stock_hist 모두 `PL01` 한 종류로 **사업장**이지 공장이 아니다.
     * `lot_no` 는 8자리 날짜라(7,533,238건 전부, 예외 0) 공장 자리가 없다.
     * `defect_hist` · `tb_prod_product` · `tb_prod_item_map` 에도 공장 컬럼이 없다.
     * `mes`·`ax` 의 모든 문자열 컬럼에서 `M-숫자`·"공장" 을 훑었을 때 걸린 것은
     * 금형코드(`MPM-058`)·작업장 이름 10/39건·작업자 자유 메모("1공장TCE세척품", 750만 중 1.5만)뿐이다.
     * (2026-09-06 재확인: MES 재이관 뒤 작업장이 37→39개, 이름에 공장이 적힌 것이 9→10개가 되었다.)
     *
     * 작업장 이름으로 추정하지 않는다 — 10개만 맞고, 코드 체계로도 알 수 없다
     * (`S130`=M-1 · `S131`=M-3 처럼 `S13x` 가 공장에 걸쳐 있다).
     *
     * `tb_md_workcenter.bp_nm` 이 후보로 보이지만 아니다 (2026-09-06 재확인).
     * 값이 제조 1PART · 2PART · 3PART · 내재화 · 백광테크 · AT솔루션 · WELDING ·
     * 김천사업장 으로, **거래처·담당 조직** 필드다. 작업장 39개 중 18개만 채워져
     * 있고, 이름에 공장이 적힌 10개 중 4개만 값이 있으며 그중 3개(프레스 W110·W150·W120)
     * 만 `제조 n PART` 로 공장과 맞는다. `S130`(A-WELDING, M-1공장)은 `WELDING` 이다.
     * 3/39 로는 공장 축이 될 수 없다.
     * 현업이 "작업장별 공장" 을 확인해 주면 프레스 범위처럼 코드 매핑을 설정으로 넣는다.
     */
    private data class CauseTarget(
        val processId: String,
        val processNm: String?,
        val eqptCd: String?,
        val eqptNm: String?,
        val product: String?,
        val productNm: String?,
        val productEtcCnt: Int,
        val eqptDefectRate: Double?,
        val defectRate: Double,
        val qty: Long,
        val ngQty: Long
    )

    /** 모델을 부르고 배열 한 필드를 읽는다. 실패하면 null — 값을 지어내지 않는다. */
    private fun callModel(
        system: String,
        user: String,
        schema: Map<String, Any?>,
        field: String
    ): ModelLines {
        if (!sllmClient.isEnabled()) return ModelLines(null, MODEL_NOT_READY)

        return when (val r = sllmClient.chatJson(system, user, schema)) {
            is SllmResult.Ok -> ModelLines(readLines(r.node, field), null)
            SllmResult.Busy -> ModelLines(null, MODEL_BUSY)
            SllmResult.Failed -> ModelLines(null, MODEL_NOT_READY)
        }
    }

    /** 모델이 낸 문장과, 못 냈다면 그 사유 */
    private data class ModelLines(val lines: List<Map<String, Any?>>?, val reason: String?)

    @Suppress("UNCHECKED_CAST")
    private fun readLines(node: com.fasterxml.jackson.databind.JsonNode, field: String): List<Map<String, Any?>> =
        runCatching {
            objectMapper.convertValue(node.path(field), List::class.java) as List<Map<String, Any?>>
        }.getOrDefault(emptyList())


    /**
     * 모델 응답을 **근거 대조를 통과시킨 뒤** 응답으로 만든다.
     *
     * 모델이 아직 없으면 `MODEL_NOT_READY` 를 낸다. 있으면 반드시 이 자리를 지나야
     * 하며, 대조에 실패한 문장은 버려지고 `droppedCnt` 로 몇 건인지 알린다.
     *
     * 모델 호출부가 이 함수를 우회해 결과를 그대로 내리면 검증이 무의미해진다 —
     * 그래서 응답을 만드는 유일한 통로로 둔다.
     */
    private fun verifyAndBuild(
        modelLines: List<Map<String, Any?>>?,
        reason: String?,
        window: TimeWindow,
        mask: MaskingSupport,
        emptyShape: Map<String, Any?>,
        build: (AiEvidenceVerifier.VerifyResult) -> Map<String, Any?>
    ): Map<String, Any?> {
        if (modelLines == null) return emptyShape + mapOf("reason" to (reason ?: MODEL_NOT_READY))

        val verified = evidenceVerifier.verifyLines(
            modelLines, window, mask, UserContext.current().userId
        )
        return build(verified)
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
        window: TimeWindow,
        mask: MaskingSupport,
        processId: String? = null
    ): AiBriefingInput {
        val plantCd = appProperties.defaultPlantCd

        val summary = dashboardAiRepository.findSummary(plantCd, window)
        val defects = dashboardAiRepository.findDefectComposition(plantCd, window, processId)
        val lines = dashboardAiRepository.findLineProduction(plantCd, window, processId)

        val totalQty = (summary["todayQty"] as? Number)?.toLong()
        val ngQty = (summary["ngQty"] as? Number)?.toLong()
        val defectRate = (summary["defectRate"] as? Number)?.toDouble()

        // 계획 수량은 일목표 마스터에서 온다. 없으면 null 이다 — 상수를 박지 않는다.
        val effective: Map<String, Long> =
            dayTargetRepository.findEffectiveTargets(plantCd, window.toExclusive.toLocalDate().minusDays(1), emptyList())
        val planQty: Long? = if (effective.isEmpty()) null else effective.values.sum()

        return AiBriefingInput.of(
            // 구간이 하루보다 길면 시작~종료로 적는다. 모델이 문장에 기간을 밝혀야 한다.
            date = periodLabel(window),
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

    /**
     * 불량률 추이의 유형 계열 수를 해석한다.
     *
     * @return 계열 상한. `null` 이면 전 유형(`topN=all`)
     * @throws InvalidParameterException `all` 도, 양의 정수도 아닌 값
     */
    internal fun parseTrendTopN(topN: String?): Int? {
        val value = topN?.trim()
        if (value.isNullOrEmpty()) return TREND_TOP_DEFECT
        if (value.equals(TREND_TOP_ALL, ignoreCase = true)) return null

        val n = value.toIntOrNull()
        if (n == null || n < 1) {
            throw InvalidParameterException("topN 은 'all' 또는 1 이상의 정수여야 합니다. [topN=$topN]", "topN")
        }
        return n
    }

    /**
     * '유형 미상' 계열을 덧붙인다 — 유형 이력이 없는 라벨의 불량 수량.
     *
     * 불량 유형 구성의 '유형 미상' 세그먼트와 같은 정의라 두 위젯의 유형 집합이 맞는다.
     * 구간 발생량이 0 이면 붙이지 않는다.
     *
     * @return 계열을 붙였는지
     */
    private fun appendUntypedSeries(
        series: MutableList<Map<String, Any?>>,
        labels: List<String>,
        plantCd: String,
        window: TimeWindow,
        hours: Int
    ): Boolean {
        val bySlot = dashboardAiRepository.findUntypedDefectTrend(plantCd, window, hours, null)
            .associate { (it["slot"] as String) to ((it["ngQty"] as? Number)?.toLong() ?: 0L) }
        val data = labels.map { bySlot[it] ?: 0L }
        if (data.sum() <= 0L) return false

        series.add(mapOf("name" to DefectSql.UNTYPED_LABEL, "data" to data))
        return true
    }

    /**
     * 유형 계열의 범위 표기 — 화면이 계열 합을 총 불량으로 써도 되는지 알린다.
     *
     * @param typeLimit       요청한 상한. `null` 이면 전 유형
     * @param count           실제로 내린 유형 계열 수 ('유형 미상' 포함)
     * @param includesUntyped '유형 미상' 계열이 마지막에 붙었는지
     */
    private fun seriesScopeOf(typeLimit: Int?, count: Int, includesUntyped: Boolean): Map<String, Any?> =
        if (typeLimit == null) {
            mapOf(
                "kind" to "ALL",
                "topN" to null,
                "count" to count,
                "includesUntyped" to includesUntyped,
                "note" to "series[1..] 는 구간에서 발생한 전 불량 유형이다. " +
                    "유형이 붙지 않은 불량이 있으면 마지막 계열 '${DefectSql.UNTYPED_LABEL}' 로 낸다."
            )
        } else {
            mapOf(
                "kind" to "TOP_N",
                "topN" to typeLimit,
                "count" to count,
                "includesUntyped" to false,
                "note" to "series[1..] 는 구간 합계 상위 ${typeLimit}종이다. 합계가 총 불량이 아니다."
            )
        }
}
