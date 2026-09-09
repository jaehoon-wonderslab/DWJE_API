package com.dwje.api.service

import com.dwje.api.common.response.PageMeta
import com.dwje.api.common.util.DataField
import com.dwje.api.common.util.DateUtils
import com.dwje.api.common.util.MaskingSupport
import com.dwje.api.common.util.MenuId
import com.dwje.api.common.util.PageRequestParam
import com.dwje.api.common.util.safeRate
import com.dwje.api.config.AppProperties
import com.dwje.api.repository.MetricStandardRepository
import com.dwje.api.repository.DailyDecisionRepository
import com.dwje.api.repository.ReportRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.YearMonth

/**
 * 정형 보고서 서비스 (RP-01 ~ RP-05)
 *
 * 아침회의 자료 · 연간 출하계획 · 제품별 수율 · 고객사별 LRR 을 담당한다.
 */
@Service
class ReportService(
    private val reportRepository: ReportRepository,
    private val dailyDecisionRepository: DailyDecisionRepository,
    private val metricStandardRepository: MetricStandardRepository,
    private val authorizationService: AuthorizationService,
    private val appProperties: AppProperties
) {

    companion object {
        /** 신호등 판정 임계값 — 달성률 100% 이상 정상 / 95% 이상 주의 / 그 미만 위험 */
        private const val LEVEL_NORMAL = 100.0
        private const val LEVEL_WARN = 95.0

        /**
         * 아침회의 자료의 주간 창 — 기준일 포함 7일 (기준일 -6일 00:00 ~ 기준일 24:00)
         *
         * 일일 생산현황 보고의 주간 누적(그 주 월요일 20:00 부터)과 **규칙이 다르다.**
         * 두 보고서가 서로 다른 창을 쓰는 것을 알고 두는 것이고, 아침회의 자료의
         * 주간목표는 이 창에 맞춰 `일목표 x 7` 로 낸다. 화면이 짐작하지 않도록
         * 응답에 `weekDays` 로 함께 내린다.
         */
        private const val WEEK_WINDOW_DAYS = 7

        /** 회계연도 시작 월 (8월) */
        private const val FISCAL_START_MONTH = 8

        /** 제품별 수율 목표 (%) */
        private const val DEFAULT_YIELD_TARGET = 99.0

        /**
         * Loss 유형 11종 + 관리 항목 3종 표시 순서
         * 기능명세서 RP-04 본표 2단 헤더 구성과 동일하다.
         */
        val LOSS_TYPES = listOf(
            "품질검사", "재료성(소재불량)", "스크래치", "찍힘", "치수", "BURR", "변형", "Try/초품",
            "얼룩", "기타", "자주검사"
        )
        val MGMT_TYPES = listOf("도면치수NG", "불용재고", "신규 불용폐기")
    }

    /**
     * 아침회의 자료를 조회한다. (No.107 PRESS / No.109 Plating·Coating)
     *
     * @param baseDate     기준일 (전일 실적 기준)
     * @param processCds   대상 공정 코드 목록
     * @param stateFilter  상태 필터 (NORMAL/WARN/CRIT)
     */
    @Transactional(readOnly = true)
    fun getMorningMeeting(
        menuId: String,
        baseDate: String?,
        processCds: List<String>,
        stateFilter: String?
    ): Pair<Map<String, Any?>, MaskingSupport> {
        // PRESS(RP-01) 와 Plating·Coating(RP-02) 이 같은 집계를 쓰되 화면 권한은 각각 판정한다.
        val (_, mask) = authorizationService.guard(menuId)

        // 기준일 미지정 시 전일 실적을 본다.
        val target = DateUtils.parseDate(baseDate, "baseDate", LocalDate.now().minusDays(1))
        val qtyAllowed = mask.check(DataField.QTY)
        val yieldAllowed = mask.check(DataField.YIELD)

        // 공정을 지정하지 않으면 그 보고서의 기본 범위를 쓴다.
        // PRESS 자료에 도금·코팅 공정이 섞여 올라오면 안 되고, 화면이 매번 작업장
        // 코드를 나열해야 하면 한 곳만 빠뜨려도 조용히 어긋난다.
        val scope = processCds.mapNotNull { it.trim().takeIf { c -> c.isNotBlank() } }
            .ifEmpty {
                when (menuId) {
                    MenuId.RPT_PLATING_MORNING -> appProperties.platingWorkcenters
                    else -> appProperties.pressWorkcenters
                }
            }

        val raw = reportRepository.findMorningMeetingRows(appProperties.defaultPlantCd, target, scope)

        val rows = raw.map { r ->
            // 목표가 없으면 달성률·상태·주간목표를 내지 않는다.
            // 0 을 내면 못 지킨 것과 목표가 없는 것이 같은 값이 되어, 아침회의 자료가
            // 전 행 위험으로 뜬다. 그건 자료로 쓸 수 없다.
            val dayTarget = r["dayTarget"] as? Long
            val rate = r["rate"] as? Double
            val state = rate?.let { judgeSignal(it) }

            val weekTarget = dayTarget?.let { it * WEEK_WINDOW_DAYS }
            val weekActual = r["weekActual"] as? Long
            val weekRate = if (weekTarget != null && weekTarget > 0 && weekActual != null) {
                safeRate(weekActual.toBigDecimal(), weekTarget.toBigDecimal())
            } else {
                null
            }

            mapOf(
                "state" to state,
                "processId" to r["processId"],
                "process" to r["process"],
                "issue" to buildIssue(r, state),
                "dayTarget" to if (qtyAllowed) dayTarget else null,
                "dayActual" to if (qtyAllowed) r["dayActual"] else null,
                "rate" to if (yieldAllowed) rate else null,
                "weekTarget" to if (qtyAllowed) weekTarget else null,
                "weekActual" to if (qtyAllowed) weekActual else null,
                "weekRate" to if (yieldAllowed) weekRate else null,
                "weekDays" to WEEK_WINDOW_DAYS,
                "impactEqptCnt" to r["impactEqptCnt"],
                "decision" to null,
                "dri" to null,
                "due" to null
            )
        }.filter { stateFilter.isNullOrBlank() || it["state"] == stateFilter.uppercase() }

        // 합계 행 — 목표·실적 합계와 가중 평균 달성률
        //
        // 목표를 가진 공정이 하나도 없으면 합계 목표는 **null** 이다. 0 으로 두면
        // 달성률이 0 이 되어 "목표를 못 지켰다" 로 읽힌다.
        // 수량 합계는 전 공정을 더한다 — 실제로 만든 양이다.
        val totalActual = raw.sumOf { (it["dayActual"] as? Long) ?: 0L }
        val totalWeekActual = raw.sumOf { (it["weekActual"] as? Long) ?: 0L }

        // 달성률은 **목표가 있는 공정만으로** 낸다.
        // 목표는 일부 공정만 있는데 실적은 전 공정을 더하면 분모와 짝이 맞지 않아
        // 달성률이 부풀어 오른다 (실측: 목표 2,000,000 · 전 공정 실적 3,992,507 → 199.63%).
        val withTarget = raw.filter { it["dayTarget"] != null }
        val totalTarget = if (withTarget.isEmpty()) null else withTarget.sumOf { it["dayTarget"] as Long }
        val ratedActual = withTarget.sumOf { (it["dayActual"] as? Long) ?: 0L }
        val ratedWeekActual = withTarget.sumOf { (it["weekActual"] as? Long) ?: 0L }
        val totalWeekTarget = totalTarget?.let { it * WEEK_WINDOW_DAYS }

        val avgRate = if (totalTarget != null && totalTarget > 0) {
            Math.round(ratedActual * 10000.0 / totalTarget) / 100.0
        } else {
            null
        }
        val weekRate = if (totalWeekTarget != null && totalWeekTarget > 0) {
            Math.round(ratedWeekActual * 10000.0 / totalWeekTarget) / 100.0
        } else {
            null
        }

        return mapOf(
            "baseDate" to target.format(DateUtils.DATE),
            // 서버가 실제로 어느 작업장을 집계했는지 화면이 확인할 수 있게 함께 내린다.
            "processCds" to scope,
            "summary" to mapOf(
                "dayTarget" to if (qtyAllowed) totalTarget else null,
                "dayActual" to if (qtyAllowed) totalActual else null,
                "avgRate" to if (yieldAllowed) avgRate else null,
                "weekRate" to if (yieldAllowed) weekRate else null,
                // 달성률이 몇 개 공정을 근거로 나온 값인지 밝힌다.
                // 목표가 없는 공정은 달성률 계산에서 빠지므로 화면이 그 사실을 적어야 한다.
                "rateProcessCnt" to withTarget.size,
                "processCnt" to raw.size,
                "issueCnt" to rows.count { it["state"] != null && it["state"] != "NORMAL" }
            ),
            "rows" to rows,
            "total" to mapOf(
                "dayTarget" to if (qtyAllowed) totalTarget else null,
                "dayActual" to if (qtyAllowed) totalActual else null,
                "rate" to if (yieldAllowed) avgRate else null,
                // 달성률의 분자 — 목표가 있는 공정의 실적만 더한 값.
                // dayActual(전 공정 합계)과 다를 수 있고, 그때 rate 는 이 값을 쓴다.
                "ratedActual" to if (qtyAllowed) ratedActual else null,
                "rateProcessCnt" to withTarget.size,
                "weekTarget" to if (qtyAllowed) totalWeekTarget else null,
                "weekActual" to if (qtyAllowed) totalWeekActual else null,
                "weekRate" to if (yieldAllowed) weekRate else null
            )
        ) to mask
    }

    /**
     * 금일 결정 사항·DRI 를 조회한다. (No.108)
     *
     * ## 출처가 바뀌었다 (2026-09-04)
     * 예전에는 일일 보고서 **문서**의 ACTION 섹션 항목을 읽었다. 그 섹션은 자유
     * 텍스트라 `dri`·`due` 를 담을 자리가 없어 늘 null 이었다.
     *
     * 문서 관리가 제거되면서 아침회의 결과는 `ax.tb_prod_daily_decision` 에
     * 제품별로 남는다. 그래서 이제 담당(`dri`)과 기한(`due`) 이 실제로 채워진다.
     */
    @Transactional(readOnly = true)
    fun getMorningDecisions(baseDate: String?): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.RPT_PRESS_MORNING)
        val target = DateUtils.parseDate(baseDate, "baseDate", LocalDate.now())

        // 판정이 적힌 제품만 결정 사항으로 본다 — 일목표만 넣은 줄은 회의 결정이 아니다.
        val items = dailyDecisionRepository.findRows(target)
            .filterValues { it["decision"] != null }
            .map { (product, row) ->
                mapOf(
                    "team" to row["dri"],
                    "action" to product,
                    "detail" to row["decision"],
                    "dri" to row["dri"],
                    "due" to row["due"]
                )
            }
            .sortedBy { it["action"] as? String }

        return mapOf("baseDate" to target.format(DateUtils.DATE), "items" to items)
    }

    /**
     * 연간 출하계획을 조회한다. (No.110 — 회계연도 8월 시작 12개월)
     *
     * @param planYear 회계연도 시작 연도
     * @param unit     단위 — qty(수량) | amount(금액)
     */
    @Transactional(readOnly = true)
    fun getShipPlan(
        planYear: Int?,
        modelCd: String?,
        customerCd: String?,
        unit: String?
    ): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.RPT_SHIP_PLAN)

        val year = planYear ?: fiscalYearOf(LocalDate.now())
        val amountMode = unit?.lowercase() == "amount"

        // 계획 수량은 plan, 금액은 price, 고객사는 customer 권한이 필요하다.
        val planAllowed = mask.check(DataField.PLAN)
        val priceAllowed = if (amountMode) mask.check(DataField.PRICE) else true
        val customerAllowed = mask.check(DataField.CUSTOMER)

        if (!planAllowed || !priceAllowed) {
            return mapOf("months" to fiscalMonths(year), "rows" to emptyList<Any>()) to mask
        }

        val raw = reportRepository.findShipPlan(year, modelCd, customerCd)
        val months = fiscalMonths(year)

        // 모델 × 고객사 단위로 12개월 배열을 구성한다.
        val rows = raw.groupBy { (it["model"] as String) to (it["customer"] as String?) }
            .map { (key, list) ->
                val byMonth = list.associate { "${it["year"]}-${it["month"]}" to it }
                val values = months.map { m ->
                    val cell = byMonth["${m.year}-${m.monthValue}"]
                    if (amountMode) cell?.get("planAmount") else cell?.get("planQty")
                }
                mapOf(
                    "model" to key.first,
                    "customer" to if (customerAllowed) key.second else null,
                    "values" to values,
                    "total" to values.filterNotNull().sumOf { (it as Number).toDouble() }
                )
            }
            .sortedBy { it["model"] as String }

        // 월별 총계와 최다 출하 월
        val monthTotals = months.indices.map { idx ->
            rows.sumOf { r ->
                @Suppress("UNCHECKED_CAST")
                ((r["values"] as List<Any?>)[idx] as? Number)?.toDouble() ?: 0.0
            }
        }
        val peakIndex = monthTotals.indices.maxByOrNull { monthTotals[it] } ?: 0

        return mapOf(
            "planYear" to year,
            "unit" to (unit ?: "qty"),
            "months" to months.map { it.format(DateUtils.YEAR_MONTH) },
            "rows" to rows,
            "monthTotals" to monthTotals,
            "grandTotal" to monthTotals.sum(),
            "modelCnt" to rows.map { it["model"] }.distinct().size,
            "customerCnt" to rows.mapNotNull { it["customer"] }.distinct().size,
            "peakMonth" to months.getOrNull(peakIndex)?.format(DateUtils.YEAR_MONTH)
        ) to mask
    }

    /**
     * 제품별 수율을 조회한다. (No.111 — Loss 11종 + 관리 항목 3종)
     *
     * `rows` 만 쪽 단위로 자른다. `size=0` 이면 전량이며, 인쇄·엑셀 내려받기가 이 경로를 쓴다.
     * `summary`·`lossTypes`·`mgmtTypes` 는 쪽과 무관하게 항상 전체 기준이다.
     */
    @Transactional(readOnly = true)
    fun getYieldByModel(
        yearMonth: String?,
        modelCd: String?,
        processId: String?,
        page: Int?,
        size: Int?
    ): Triple<Map<String, Any?>, PageMeta, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.RPT_YIELD_MODEL)

        val ym = DateUtils.parseYearMonth(yearMonth)
        val plantCd = appProperties.defaultPlantCd
        val qtyAllowed = mask.check(DataField.QTY)
        val yieldAllowed = mask.check(DataField.YIELD)

        val base = reportRepository.findYieldByModel(plantCd, ym, modelCd, processId)
        val loss = reportRepository.findLossBreakdown(plantCd, ym, modelCd, processId)

        // (일자, 모델) 키로 Loss 유형별 수량을 배치한다.
        val lossIndex = loss.groupBy { (it["date"] as String) to (it["model"] as String) }

        // 순번(no)은 쪽을 잘라도 전체 기준으로 유지한다.
        val allRows = base.mapIndexed { idx, r ->
            val key = (r["date"] as String) to (r["model"] as String)
            val lossRows = lossIndex[key] ?: emptyList()

            mapOf(
                "no" to (idx + 1),
                "date" to r["date"],
                "model" to r["model"],
                "inputQty" to if (qtyAllowed) r["inputQty"] else null,
                "okQty" to if (qtyAllowed) r["okQty"] else null,
                "ngQty" to if (qtyAllowed) r["ngQty"] else null,
                "defectRate" to if (yieldAllowed) r["defectRate"] else null,
                "yield" to if (yieldAllowed) r["yield"] else null,
                "loss" to LOSS_TYPES.associateWith { type ->
                    if (!qtyAllowed) null
                    else lossRows.filter { matchesType(it["defectNm"] as? String, type) }
                        .sumOf { (it["qty"] as? Long) ?: 0L }
                },
                "mgmt" to MGMT_TYPES.associateWith { type ->
                    if (!qtyAllowed) null
                    else lossRows.filter { matchesType(it["defectNm"] as? String, type) }
                        .sumOf { (it["qty"] as? Long) ?: 0L }
                }
            )
        }

        val paging = PageRequestParam.ofAllowAll(page, size)
        val total = allRows.size.toLong()
        val rows = if (paging.isAll) allRows else allRows.drop(paging.offset).take(paging.size)
        val meta = if (paging.isAll) PageMeta.all(total) else PageMeta.of(paging.page, paging.size, total)

        // 화면의 '합계' 행과 'Loss 비중' 표가 쓰는 값이다.
        // 쪽 단위 rows 를 더하면 한 쪽 분량만 잡히므로 전 건 기준으로 미리 집계해 함께 내린다.
        fun totalsOf(types: List<String>, key: String): Map<String, Long?> =
            types.associateWith { type ->
                if (!qtyAllowed) null
                else allRows.sumOf { row ->
                    @Suppress("UNCHECKED_CAST")
                    (row[key] as? Map<String, Long?>)?.get(type) ?: 0L
                }
            }

        // summary 는 쪽과 무관하게 전체 기준이다.
        val totalInput = base.sumOf { (it["inputQty"] as? Long) ?: 0L }
        val totalOk = base.sumOf { (it["okQty"] as? Long) ?: 0L }
        val totalNg = base.sumOf { (it["ngQty"] as? Long) ?: 0L }
        val target = metricStandardRepository.findStandardValue("PROD_YIELD_RATE") ?: DEFAULT_YIELD_TARGET

        val data = mapOf(
            "yearMonth" to ym.format(DateUtils.YEAR_MONTH),
            "summary" to mapOf(
                "inputQty" to if (qtyAllowed) totalInput else null,
                "okQty" to if (qtyAllowed) totalOk else null,
                "ngQty" to if (qtyAllowed) totalNg else null,
                "yield" to if (yieldAllowed && totalInput > 0) Math.round(totalOk * 10000.0 / totalInput) / 100.0 else null,
                "defectRate" to if (yieldAllowed && totalInput > 0) Math.round(totalNg * 10000.0 / totalInput) / 100.0 else null,
                "target" to target
            ),
            "lossTypes" to LOSS_TYPES,
            "mgmtTypes" to MGMT_TYPES,
            // 전체 기준 합계 — rows 가 쪽 단위여도 합계 행이 흔들리지 않게 한다.
            "lossTotals" to totalsOf(LOSS_TYPES, "loss"),
            "mgmtTotals" to totalsOf(MGMT_TYPES, "mgmt"),
            "rows" to rows
        )

        return Triple(data, meta, mask)
    }

    /**
     * 고객사별 LRR 을 조회한다. (No.112)
     *
     * @param baseYear 기준 연도
     * @param unit     집계 단위 — month | quarter | year
     */
    @Transactional(readOnly = true)
    fun getLrrByCustomer(
        baseYear: Int?,
        customerCd: String?,
        unit: String?
    ): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.RPT_LRR_CUSTOMER)

        val year = baseYear ?: LocalDate.now().year
        val qtyAllowed = mask.check(DataField.QTY)
        val yieldAllowed = mask.check(DataField.YIELD)
        val customerAllowed = mask.check(DataField.CUSTOMER)

        val raw = reportRepository.findLrrByCustomer(year, customerCd)
        val byDefect = reportRepository.findLrrByDefectType(year, customerCd)

        val current = raw.filter { it["year"] == year }
        val previous = raw.filter { it["year"] == year - 1 }

        val curShip = current.sumOf { (it["shipQty"] as? Long) ?: 0L }
        val curLrr = current.sumOf { (it["lrrQty"] as? Long) ?: 0L }
        val prevShip = previous.sumOf { (it["shipQty"] as? Long) ?: 0L }
        val prevLrr = previous.sumOf { (it["lrrQty"] as? Long) ?: 0L }

        val curRate = if (curShip > 0) Math.round(curLrr * 1000000.0 / curShip) / 10000.0 else 0.0
        val prevRate = if (prevShip > 0) Math.round(prevLrr * 1000000.0 / prevShip) / 10000.0 else 0.0

        // 고객사별 누적 집계 (블록 4)
        val byCustomer = current.groupBy { it["customer"] as String? }
            .map { (customer, list) ->
                val ship = list.sumOf { (it["shipQty"] as? Long) ?: 0L }
                val lrr = list.sumOf { (it["lrrQty"] as? Long) ?: 0L }
                mapOf(
                    "customer" to if (customerAllowed) customer else null,
                    "shipQty" to if (qtyAllowed) ship else null,
                    "lrrQty" to if (qtyAllowed) lrr else null,
                    "lrrRate" to if (yieldAllowed && ship > 0) Math.round(lrr * 1000000.0 / ship) / 10000.0 else null,
                    "shipShare" to if (qtyAllowed && curShip > 0) Math.round(ship * 10000.0 / curShip) / 100.0 else null
                )
            }

        return mapOf(
            "baseYear" to year,
            "unit" to (unit ?: "month"),
            "summary" to mapOf(
                "shipQty" to if (qtyAllowed) curShip else null,
                "lrrCnt" to current.sumOf { (it["noticeCnt"] as? Long) ?: 0L },
                "lrrRate" to if (yieldAllowed) curRate else null,
                "yoyImprovement" to if (yieldAllowed) Math.round((prevRate - curRate) * 10000) / 10000.0 else null
            ),
            "byDefectType" to aggregateByUnit(byDefect, unit, qtyAllowed),
            "byCustomerMonth" to aggregateByUnit(current, unit, qtyAllowed),
            "byCustomer" to byCustomer
        ) to mask
    }

    // ---------------------------------------------------------------------------------
    // 내부 보조
    // ---------------------------------------------------------------------------------

    /**
     * 달성률로 신호등 상태를 판정한다.
     *
     * 판정 기준값은 SY-13(지표 측정 데이터 관리)의 PROD_ACHIEVE_RATE 기준을 따른다.
     */
    private fun judgeSignal(rate: Double): String = when {
        rate >= LEVEL_NORMAL -> "NORMAL"
        rate >= LEVEL_WARN -> "WARN"
        else -> "CRIT"
    }

    /**
     * 신호등 상태에 따른 이슈 문구를 만든다.
     */
    private fun buildIssue(row: Map<String, Any?>, state: String?): String? {
        if (state == "NORMAL") return null
        // 목표가 없으면 "목표 대비 미달" 을 쓸 수 없다. 불량만 알린다.
        val dayTarget = row["dayTarget"] as? Long
        val gap = if (dayTarget == null) 0L else dayTarget - ((row["dayActual"] as? Long) ?: 0L)
        val ngQty = (row["dayNgQty"] as? Long) ?: 0L
        return buildString {
            if (gap > 0) append("목표 대비 ${gap}EA 미달")
            if (ngQty > 0) {
                if (isNotEmpty()) append(" · ")
                append("불량 ${ngQty}EA 발생")
            }
        }.ifBlank { null }
    }

    /**
     * 회계연도 12개월(8월~익년 7월) 배열을 만든다.
     */
    private fun fiscalMonths(planYear: Int): List<YearMonth> =
        (0 until 12).map { YearMonth.of(planYear, FISCAL_START_MONTH).plusMonths(it.toLong()) }

    /** 기준일이 속한 회계연도 시작 연도를 산출한다. */
    private fun fiscalYearOf(date: LocalDate): Int =
        if (date.monthValue >= FISCAL_START_MONTH) date.year else date.year - 1

    /**
     * 불량명이 Loss 유형에 해당하는지 판정한다.
     *
     * MES 불량명이 표준 Loss 유형명과 완전히 일치하지 않을 수 있어 부분 일치로 매칭한다.
     */
    private fun matchesType(defectNm: String?, type: String): Boolean {
        if (defectNm.isNullOrBlank()) return type == "기타"
        val normalized = type.substringBefore("(")
        return defectNm.contains(normalized, ignoreCase = true)
    }

    /**
     * 월별 집계를 요청 단위(월/분기/연간)로 재집계한다.
     */
    private fun aggregateByUnit(
        rows: List<Map<String, Any?>>,
        unit: String?,
        qtyAllowed: Boolean
    ): List<Map<String, Any?>> {
        val groupKey: (Map<String, Any?>) -> String = when (unit?.lowercase()) {
            "quarter" -> { r -> "Q${(((r["month"] as? Int) ?: 1) - 1) / 3 + 1}" }
            "year" -> { r -> "${r["year"]}" }
            else -> { r -> String.format("%02d월", (r["month"] as? Int) ?: 0) }
        }

        val labelKey: (Map<String, Any?>) -> String = { r ->
            (r["customer"] as? String) ?: (r["defectType"] as? String) ?: "-"
        }

        return rows.groupBy { labelKey(it) to groupKey(it) }
            .map { (key, list) ->
                mapOf(
                    "label" to key.first,
                    "period" to key.second,
                    "qty" to if (qtyAllowed) list.sumOf { ((it["qty"] ?: it["lrrQty"]) as? Long) ?: 0L } else null,
                    "shipQty" to if (qtyAllowed) list.sumOf { (it["shipQty"] as? Long) ?: 0L } else null,
                    "cnt" to list.sumOf { ((it["cnt"] ?: it["noticeCnt"]) as? Long) ?: 0L }
                )
            }
            .sortedWith(compareBy({ it["label"] as String }, { it["period"] as String }))
    }
}
