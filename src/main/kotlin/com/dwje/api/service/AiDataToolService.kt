package com.dwje.api.service

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.exception.ResourceNotFoundException
import com.dwje.api.common.exception.MenuAccessDeniedException
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.common.util.MaskingSupport
import com.dwje.api.common.util.DataField
import com.dwje.api.common.util.MenuId
import com.dwje.api.common.util.AiBusinessPeriod
import com.dwje.api.common.util.ProcessPeriod
import com.dwje.api.common.util.ProcessPeriodRow
import com.dwje.api.config.AppProperties
import com.dwje.api.repository.DashboardProcessRepository
import com.dwje.api.repository.AiFactRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * AI 데이터 도구(skill) — 채팅이 **DB 집계값**을 근거로 쓰게 한다
 *
 * 사내 문서(FACA 보고서 등)에는 "전월 대비 불량률" 같은 수치가 없다. 그 질문에 모델은
 * "월간 통계 데이터는 포함되어 있지 않습니다" 라고 답했다 — 근거 밖을 말하지 않는 규칙대로다.
 * 수치는 실적 DB(mes.tb_pop_label_hist)에 있으므로 서버가 집계해 `[근거]` 줄로 넘긴다.
 *
 * ## 도구 호출은 서버가 한다
 * 처음 시험(2026-09-23)에서 dwje-ax 는 `tools` 를 주면 호출하지 않고 수치를 지어냈다. LLM 담당이 내장 지시문을 고쳐
 * 지금은 `tool_calls` 를 낸다. 그래도 **서버가 질문에서 기간·지표를 읽어 직접** 도구를 부른다([evidenceFor]) —
 * 도구 결과만으로 답하면 `[n]` 근거 번호가 붙지 않고, 집계를 `[근거] [1] …` 줄로 넣는 편이 확실하다(LLM 담당 권고).
 * 도구 정의는 MCP 모양(`name` · `description` · `inputSchema`)으로 두고 `GET /api/ai/tools` 로 공개한다 —
 * 모델에 `tools` 로 넘길 때는 `{"type":"function","function":{name, description, parameters: inputSchema}}` 로 바꾼다.
 *
 * ## 권한
 * 조회자의 데이터 권한으로 가린다(수량 = `qty`, 불량률·수율 = `yield`). 가려진 값은 근거 줄에서 빠진다 —
 * 모델이 보지 못한 값은 말할 수 없다.
 */
@Service
class AiDataToolService(
    private val dashboardProcessRepository: DashboardProcessRepository,
    private val commonMasterService: CommonMasterService,
    private val appProperties: AppProperties,
    private val aiFactRepository: AiFactRepository,
    private val questionPlanner: AiQuestionPlanner,
    private val aoiDimensionService: AoiDimensionService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    data class EvidenceResult(
        val evidence: List<Map<String, Any?>>,
        val route: String,
        val tool: String?,
        val parseCode: String,
        val executionCode: String,
        val errorCode: String?,
        val rowCount: Int,
        val period: AiBusinessPeriod?,
        val elapsedMs: Int,
        val rawRows: List<Map<String, Any?>> = emptyList(),
        val isDaily: Boolean = false
    )

    companion object {
        const val PRODUCTION_PERIOD_COMPARE = "production_period_compare"
        const val PRODUCTION_PRODUCT_LIST = "production_product_list"
        const val DAILY_PRODUCT_DEFECT = "daily_product_defect"
        const val AOI_DIMENSION_SUMMARY = "aoi_dimension_summary"
        const val DEFECT_RATE_TOP = "defect_rate_top"
        const val DEFECT_TOP = "defect_top"
        const val DOCUMENT_COUNT = "document_count"

        /** 공정별 줄 상한 — 근거가 길어지면 모델 컨텍스트(8,192 토큰)를 먹는다 */
        private const val PROCESS_LINES = 8

        /** DB 부하 상한과 같다(ProcessPeriod) */
        private const val MAX_SPAN_DAYS = 92L

        /** 이 낱말이 있으면 수치 질문으로 보고 집계를 붙인다 */
        private val METRIC_WORDS = listOf("불량률", "불량", "생산량", "생산", "실적", "수율", "양품", "달성", "수량")

        private val COMPARE_WORDS = listOf("대비", "비교", "변화", "증감", "추이", "늘었", "줄었", "올랐", "떨어", "차이")

        /** MCP 도구 정의 — `inputSchema` 는 JSON Schema */
        val TOOLS: List<Map<String, Any?>> = listOf(
            mapOf(
                "name" to PRODUCTION_PERIOD_COMPARE,
                "description" to "공장 전체 및 공정별 생산 실적(전체 생산량·양품·불량 건수·불량률·수율)을 업무일 기간으로 집계한다. " +
                    "기간 비교·변화·증감·%p 질문은 이 도구를 쓰고 compareFrom·compareTo에 직전 기간을 지정해 두 기간을 비교한다. " +
                    "예: '지난 일주일간 공장 전체 불량률은 몇 %p 변했나?'는 최근 7개 업무일과 직전 7개 업무일을 조회한다. " +
                    "후속 질문에서 날짜가 생략되면 제공된 직전 조회 기간을 재사용한다. 한 기간은 최대 92일. 업무일(08:00 교대) 기준.",
                "inputSchema" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "from" to mapOf("type" to "string", "format" to "date", "description" to "시작일 YYYY-MM-DD"),
                        "to" to mapOf("type" to "string", "format" to "date", "description" to "종료일 YYYY-MM-DD"),
                        "compareFrom" to mapOf("type" to "string", "format" to "date", "description" to "비교 기간 시작일(선택)"),
                        "compareTo" to mapOf("type" to "string", "format" to "date", "description" to "비교 기간 종료일(선택)"),
                        "label" to mapOf("type" to "string", "description" to "기간 이름(선택, 예: 이번 달)"),
                        "compareLabel" to mapOf("type" to "string", "description" to "비교 기간 이름(선택, 예: 지난달)")
                    ),
                    "required" to listOf("from", "to")
                )
            ),
            mapOf(
                "name" to PRODUCTION_PRODUCT_LIST,
                "description" to "지정한 교대 업무일에 생산된 제품 목록을 조회한다. 일자별/날짜별 구분이 필요한 경우 groupByDate를 true로 지정한다. 양 끝 업무일을 포함한다.",
                "inputSchema" to mapOf("type" to "object", "properties" to mapOf(
                    "from" to mapOf("type" to "string", "format" to "date", "description" to "시작일 YYYY-MM-DD"),
                    "to" to mapOf("type" to "string", "format" to "date", "description" to "종료일 YYYY-MM-DD"),
                    "groupByDate" to mapOf("type" to "boolean", "description" to "일자별/날짜별로 나누어 조회할지 여부 (기본값: false)")),
                    "required" to listOf("from", "to"))
            ),
            mapOf(
                "name" to DAILY_PRODUCT_DEFECT,
                "description" to "일자별 생산 불량 종류와 제품(모델) 구분 및 제품군을 표로 조회한다. 예: 9월 20일~22일까지 생산된 불량 종류 및 제품 구분을 일자별로 정리. 양 끝 교대 업무일을 포함한다. 최대 31일, 최대 200행. 연도 없는 날짜는 Asia/Seoul 오늘 이전 최근 유효 연도로 판단한다.",
                "inputSchema" to mapOf("type" to "object", "properties" to mapOf(
                    "from" to mapOf("type" to "string", "format" to "date", "description" to "첫 업무일 YYYY-MM-DD"),
                    "to" to mapOf("type" to "string", "format" to "date", "description" to "끝 업무일 YYYY-MM-DD"),
                    "limit" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 200, "description" to "최대 표 행 수, 기본 200")),
                    "required" to listOf("from", "to"))
            ),
            mapOf(
                "name" to AOI_DIMENSION_SUMMARY,
                "description" to "AOI 판정 분석 화면의 치수 요약을 작업장별로 조회한다. 예: S120 작업장 AOI 판정 요약, S110 작업장의 9월 22일 AOI 불량률. 작업장이 질문에 명시되지 않으면 wcCd를 추측하지 말고 비워 두어 작업장 지정 안내를 받는다. 기존 QC_AOI 권한·최대 조회 기간·수량/비율 마스킹을 적용한다. AOI serial/detail/한계값/예측은 조회하지 않는다.",
                "inputSchema" to mapOf("type" to "object", "properties" to mapOf(
                    "from" to mapOf("type" to "string", "format" to "date", "description" to "선택 시작일 YYYY-MM-DD, AOI 화면 기간 기준"),
                    "to" to mapOf("type" to "string", "format" to "date", "description" to "선택 종료일 YYYY-MM-DD, AOI 화면 기간 기준"),
                    "wcCd" to mapOf("type" to "string", "description" to "질문에 명시된 작업장 코드. 없으면 빈 문자열"),
                    "eqptCd" to mapOf("type" to "string", "description" to "질문에 명시된 설비 코드, 선택")),
                    "required" to listOf("wcCd"))
            ),
            mapOf(
                "name" to DEFECT_RATE_TOP,
                "description" to "지정한 교대 업무일의 제품별 가중 불량률 상위 1~20종을 조회한다. 양 끝 업무일을 포함한다.",
                "inputSchema" to mapOf("type" to "object", "properties" to mapOf(
                    "from" to mapOf("type" to "string", "format" to "date"),
                    "to" to mapOf("type" to "string", "format" to "date"),
                    "limit" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 20)),
                    "required" to listOf("from", "to", "limit"))
            ),
            mapOf(
                "name" to DEFECT_TOP,
                "description" to "기간의 불량 유형별 수량 상위 1~10종을 조회한다. 양 끝 교대 업무일을 포함한다.",
                "inputSchema" to mapOf("type" to "object", "properties" to mapOf(
                    "from" to mapOf("type" to "string", "format" to "date"),
                    "to" to mapOf("type" to "string", "format" to "date"),
                    "limit" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 10)),
                    "required" to listOf("from", "to", "limit"))
            ),
            mapOf(
                "name" to DOCUMENT_COUNT,
                "description" to "열람 가능한 색인 완료 문서의 연도별 개수를 조회한다. 작성·생성은 등록 시각, 다루는 기간은 문서 기준일이다.",
                "inputSchema" to mapOf("type" to "object", "properties" to mapOf(
                    "years" to mapOf("type" to "array", "items" to mapOf("type" to "integer"), "maxItems" to 2),
                    "basis" to mapOf("type" to "string", "enum" to listOf("registered", "document_date")),
                    "defectReports" to mapOf("type" to "boolean")),
                    "required" to listOf("years", "basis", "defectReports"))
            )
        )

        /** 이름 붙은 기간 */
        data class Period(val from: LocalDate, val to: LocalDate, val label: String)

        /**
         * 질문 → (기간, 비교 기간). 수치 질문이 아니면 null.
         *
         * 기준일은 **마지막 실적일**이다(오늘 실적이 아직 없을 수 있다). 기간 말이 없으면 이번 달 vs 지난달.
         * 비교 말(대비·변화·추이…)이 있고 기간이 하나면 바로 앞 같은 길이의 기간과 비교한다.
         */
        fun resolvePeriods(question: String, last: LocalDate): Pair<Period, Period?>? {
            val q = question.replace(" ", "")
            if (METRIC_WORDS.none { q.contains(it) }) return null

            val thisMonth = Period(last.withDayOfMonth(1), last, "이번 달")
            val prevMonthStart = last.withDayOfMonth(1).minusMonths(1)
            val prevMonth = Period(prevMonthStart, prevMonthStart.with(TemporalAdjusters.lastDayOfMonth()), "지난달")
            val weekStart = last.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val thisWeek = Period(weekStart, last, "이번 주")
            val prevWeek = Period(weekStart.minusWeeks(1), weekStart.minusDays(1), "지난주")
            val today = Period(last, last, "최근 실적일")
            val yesterday = Period(last.minusDays(1), last.minusDays(1), "그 전날")

            val found = mutableListOf<Period>()
            fun has(vararg w: String) = w.any { q.contains(it) }
            if (has("이번달", "금월", "당월", "이달")) found += thisMonth
            if (has("지난달", "전월", "저번달", "지난월")) found += prevMonth
            if (has("이번주", "금주", "이번한주")) found += thisWeek
            if (has("지난주", "전주", "저번주")) found += prevWeek
            if (has("오늘", "금일", "당일")) found += today
            if (has("어제", "전일")) found += yesterday
            // "8월" · "2026-08" · "2026년 8월"
            Regex("""(?:(\d{4})[년\-.]\s*)?(\d{1,2})월|(\d{4})-(\d{2})(?!-\d)""").findAll(question).forEach { m ->
                val year = (m.groupValues[1].ifEmpty { m.groupValues[3] }).toIntOrNull() ?: last.year
                val month = (m.groupValues[2].ifEmpty { m.groupValues[4] }).toIntOrNull() ?: return@forEach
                if (month !in 1..12) return@forEach
                val start = LocalDate.of(year, month, 1)
                if (start.isAfter(last)) return@forEach
                val end = minOf(start.with(TemporalAdjusters.lastDayOfMonth()), last)
                found += Period(start, end, "${year}년 ${month}월")
            }
            val periods = found.distinctBy { it.from to it.to }.sortedByDescending { it.from }

            return when {
                periods.size >= 2 -> periods[0] to periods[1]
                periods.size == 1 -> {
                    val p = periods[0]
                    if (COMPARE_WORDS.none { q.contains(it) }) p to null
                    else p to previousOf(p)
                }
                else -> thisMonth to prevMonth
            }
        }

        /** 바로 앞 같은 성격의 기간 — 달이면 앞 달 전체, 아니면 같은 길이 */
        private fun previousOf(p: Period): Period {
            if (p.from.dayOfMonth == 1 && (p.label.endsWith("월") || p.label == "이번 달")) {
                val start = p.from.minusMonths(1)
                return Period(start, start.with(TemporalAdjusters.lastDayOfMonth()), "그 전달")
            }
            val days = ChronoUnit.DAYS.between(p.from, p.to) + 1
            return Period(p.from.minusDays(days), p.from.minusDays(1), "직전 ${days}일")
        }

        private fun num(v: BigDecimal?): String? = v?.let { "%,d".format(it.toLong()) }
        private fun pct(v: Double?): String? = v?.let { "%.2f%%".format(it) }
    }

    /** 도구 목록 (MCP `tools/list` 모양) */
    fun listTools(): List<Map<String, Any?>> = TOOLS

    /** 엑셀 내보내기도 채팅 근거와 동일한 고정 SELECT·권한 판정을 사용한다. */
    fun defectTopForExport(from: LocalDate, to: LocalDate, limit: Int, principal: UserPrincipal): Pair<List<Map<String, Any?>>, Int> {
        require(limit in 1..10 && !from.isAfter(to) && ChronoUnit.DAYS.between(from, to) <= 92)
        val mask = MaskingSupport(principal)
        val rows = aiFactRepository.topDefects(appProperties.defaultPlantCd, AiBusinessPeriod(from, to), limit)
            .mapIndexed { index, row ->
                mapOf<String, Any?>("rank" to index + 1, "from" to from.toString(), "to" to to.toString(),
                    "defect" to row["name"], "quantity" to mask.on(DataField.QTY) { row["quantity"] as? BigDecimal })
            }
        return rows to mask.maskedCount()
    }

    /**
     * 도구 실행 (MCP `tools/call` 모양) — 구조화된 결과를 낸다.
     *
     * @param principal 조회자 — 데이터 권한으로 값을 가린다
     */
    fun call(name: String, args: Map<String, Any?>, principal: UserPrincipal): Map<String, Any?> {
        if (name !in setOf(PRODUCTION_PERIOD_COMPARE, PRODUCTION_PRODUCT_LIST, DAILY_PRODUCT_DEFECT, AOI_DIMENSION_SUMMARY, DEFECT_RATE_TOP, DEFECT_TOP, DOCUMENT_COUNT))
            throw ResourceNotFoundException("없는 도구입니다. [$name]")
        if (name == AOI_DIMENSION_SUMMARY) {
            if (!principal.canAccessMenu(MenuId.QC_AOI)) throw MenuAccessDeniedException(MenuId.QC_AOI)
            val wcCd = args["wcCd"]?.toString()?.trim().orEmpty()
            if (wcCd.isBlank()) return mapOf("executionCode" to "WORKCENTER_REQUIRED", "reason" to "AOI 작업장을 지정해 주세요.", "rows" to emptyList<Any>())
            val decision = aoiArguments(wcCd, args["eqptCd"]?.toString(), args["from"]?.toString(), args["to"]?.toString())
            val (data, _) = aoiDimensionService.getSummary(decision.from?.toString(), decision.to?.toString(), decision.wcCd, decision.eqptCd)
            val projection = aoiProjection(data)
            return mapOf("executionCode" to projection.code, "reason" to projection.reason,
                "wcCd" to wcCd, "rows" to projection.rows)
        }
        if (name == DOCUMENT_COUNT) {
            val years = (args["years"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() }
                ?: throw InvalidParameterException("연도가 필요합니다.", "years")
            val currentYear = LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).year
            if (years.isEmpty() || years.size > 2 || years.any { it !in 2000..currentYear })
                throw InvalidParameterException("연도는 1~2개여야 합니다.", "years")
            val basis = args["basis"] as? String
            if (basis !in setOf("registered", "document_date") || args["defectReports"] !is Boolean)
                throw InvalidParameterException("문서 집계 기준이 올바르지 않습니다.", "basis")
            return mapOf("years" to years.map { year -> mapOf("year" to year,
                "counts" to aiFactRepository.documentCount(year, principal.userId, basis == "registered", args["defectReports"] == true)) })
        }
        fun date(key: String, required: Boolean): LocalDate? {
            val v = args[key]?.toString()?.trim().orEmpty()
            if (v.isEmpty()) {
                if (required) throw InvalidParameterException("$key 가 필요합니다(YYYY-MM-DD).", key)
                return null
            }
            return runCatching { LocalDate.parse(v) }.getOrElse { throw InvalidParameterException("$key 형식이 올바르지 않습니다(YYYY-MM-DD). [$v]", key) }
        }
        val from = date("from", true)!!
        val to = date("to", true)!!
        if (from.isAfter(to) || ChronoUnit.DAYS.between(from, to) > MAX_SPAN_DAYS)
            throw InvalidParameterException("날짜 범위는 시작일 이상 종료일 이하, 최대 92일이어야 합니다.", "to")
        if (name == DAILY_PRODUCT_DEFECT) {
            if (to.isAfter(LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))))
                throw InvalidParameterException("미래 업무일은 조회할 수 없습니다.", "to")
            if (ChronoUnit.DAYS.between(from, to) >= 31)
                throw InvalidParameterException("최대 31개 업무일만 조회할 수 있습니다.", "to")
            val limit = args["limit"]?.let { it as? Int ?: throw InvalidParameterException("표 행 수가 올바르지 않습니다.", "limit") } ?: 200
            if (limit !in 1..200) throw InvalidParameterException("표 행 수는 1~200이어야 합니다.", "limit")
            if (!MaskingSupport(principal).allowed(DataField.QTY))
                return mapOf("reason" to "불량 수량 열람 권한이 없습니다.", "executionCode" to "DENIED_FIELDS", "rows" to emptyList<Any>())
            val rows = aiFactRepository.dailyProductDefects(appProperties.defaultPlantCd, AiBusinessPeriod(from, to), limit)
            return mapOf("from" to from.toString(), "to" to to.toString(), "limit" to limit,
                "totalCount" to (rows.firstOrNull()?.get("totalCount") ?: 0L), "rows" to rows)
        }
        if (name == PRODUCTION_PRODUCT_LIST) {
            val period = AiBusinessPeriod(from, to)
            val mask = MaskingSupport(principal)
            val groupByDate = (args["groupByDate"] as? Boolean) == true
            if (groupByDate) {
                val rows = aiFactRepository.producedProductsDaily(appProperties.defaultPlantCd, period)
                return mapOf("from" to from.toString(), "to" to to.toString(), "groupByDate" to true,
                    "totalCount" to (rows.firstOrNull()?.get("totalCount") ?: 0L),
                    "rows" to rows.map { row -> mapOf("date" to row["date"], "code" to row["code"], "name" to row["name"],
                        "quantity" to mask.on(DataField.QTY) { row["quantity"] as? BigDecimal }) })
            }
            val rows = aiFactRepository.producedProducts(appProperties.defaultPlantCd, period)
            return mapOf("from" to from.toString(), "to" to to.toString(), "totalCount" to (rows.firstOrNull()?.get("totalCount") ?: 0L),
                "rows" to rows.map { row -> mapOf("code" to row["code"], "name" to row["name"],
                    "quantity" to mask.on(DataField.QTY) { row["quantity"] as? BigDecimal }) })
        }
        if (name == DEFECT_RATE_TOP) {
            val limit = (args["limit"] as? Number)?.toInt()
                ?: throw InvalidParameterException("순위는 1~20 사이여야 합니다.", "limit")
            if (limit !in 1..20) throw InvalidParameterException("순위는 1~20 사이여야 합니다.", "limit")
            val mask = MaskingSupport(principal)
            if (!mask.allowed(DataField.YIELD)) return mapOf("reason" to "불량률 항목의 열람 권한이 없습니다.", "rows" to emptyList<Any>())
            val rows = aiFactRepository.topDefectRates(appProperties.defaultPlantCd, AiBusinessPeriod(from, to), limit)
            return mapOf("from" to from.toString(), "to" to to.toString(), "limit" to limit,
                "rows" to rows.mapIndexed { index, row -> mapOf("rank" to index + 1,
                    "code" to row["code"], "name" to row["name"], "defectRate" to row["defectRate"],
                    "quantity" to mask.on(DataField.QTY) { row["quantity"] as? BigDecimal },
                    "defectQuantity" to mask.on(DataField.QTY) { row["defectQuantity"] as? BigDecimal }) })
        }
        if (name == DEFECT_TOP) {
            val limit = (args["limit"] as? Number)?.toInt()
                ?: throw InvalidParameterException("순위는 1~10 사이여야 합니다.", "limit")
            if (limit !in 1..10) throw InvalidParameterException("순위는 1~10 사이여야 합니다.", "limit")
            val mask = MaskingSupport(principal)
            val rows = aiFactRepository.topDefects(appProperties.defaultPlantCd, AiBusinessPeriod(from, to), limit)
            return mapOf("from" to from.toString(), "to" to to.toString(), "limit" to limit,
                "rows" to rows.mapIndexed { index, row -> mapOf("rank" to index + 1, "name" to row["name"],
                    "quantity" to mask.on(DataField.QTY) { row["quantity"] as? BigDecimal }) })
        }
        val main = Period(from, to, args["label"]?.toString() ?: "기간")
        val cf = date("compareFrom", false)
        val ct = date("compareTo", false)
        val compare = if (cf != null && ct != null) Period(cf, ct, args["compareLabel"]?.toString() ?: "비교 기간") else null
        return aggregate(main, compare, MaskingSupport(principal))
    }

    private fun aoiArguments(wcCd: String, eqptCd: String?, from: String?, to: String?): AiQuestionPlanner.Decision.AoiSummary {
        val code = Regex("[A-Za-z0-9_-]{1,32}")
        if (!code.matches(wcCd)) throw InvalidParameterException("작업장 코드 형식이 올바르지 않습니다.", "wcCd")
        val equipment = eqptCd?.trim()?.takeIf { it.isNotEmpty() }
        if (equipment != null && !code.matches(equipment)) throw InvalidParameterException("설비 코드 형식이 올바르지 않습니다.", "eqptCd")
        fun parse(value: String?, key: String): LocalDate? = value?.trim()?.takeIf { it.isNotEmpty() }?.let {
            runCatching { LocalDate.parse(it) }.getOrElse { throw InvalidParameterException("날짜 형식이 올바르지 않습니다.", key) }
        }
        val fromDate = parse(from, "from")
        val toDate = parse(to, "to")
        if ((toDate ?: LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))).isAfter(LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))))
            throw InvalidParameterException("미래 날짜는 조회할 수 없습니다.", "to")
        aoiDimensionService.periodOf(fromDate?.toString(), toDate?.toString())
        return AiQuestionPlanner.Decision.AoiSummary(fromDate, toDate, wcCd, equipment)
    }

    private data class AoiProjection(val code: String, val reason: String, val rows: List<Map<String, Any?>>)

    /** 화면 서비스가 이미 마스킹한 현재 기간의 공개 요약 필드만 추린다. */
    private fun aoiProjection(data: Map<String, Any?>): AoiProjection {
        if (data["reason"] == AoiDimensionService.SOURCE_NOT_CONFIGURED)
            return AoiProjection("SOURCE_UNAVAILABLE", "AOI 데이터 원천이 설정되지 않아 조회할 수 없습니다.", emptyList())
        val current = data["current"] as? Map<*, *>
            ?: return AoiProjection("SOURCE_UNAVAILABLE", "AOI 집계 결과를 받을 수 없습니다.", emptyList())
        val total = current["total"] as? Map<*, *>
            ?: return AoiProjection("SOURCE_UNAVAILABLE", "AOI 집계 결과를 받을 수 없습니다.", emptyList())
        val wc = data["wcCd"]?.toString().orEmpty()
        fun row(source: Map<*, *>, scope: String): Map<String, Any?> = mapOf(
            "scope" to scope, "equipment" to source["eqptCd"],
            "measCnt" to source["measCnt"], "failCnt" to source["failCnt"],
            "failRate" to source["failRate"], "explainedRate" to source["explainedRate"]
        )
        val equipmentRows = (current["equipments"] as? List<*>)?.mapNotNull { it as? Map<*, *> }.orEmpty()
        val rows = (listOf(row(total, "작업장 전체")) + equipmentRows.take(49).map { row(it, "설비") }).take(50)
        val count = total["measCnt"] as? Number
        if (count != null && count.toLong() == 0L)
            return AoiProjection("EMPTY", "작업장 $wc 의 AOI 측정 집계가 없습니다.", emptyList())
        val text = buildString {
            append("작업장 $wc AOI 치수 집계 · ${rows.size}행")
            if (equipmentRows.size > 49) append(" (설비 일부만 표시)")
            rows.forEach { r ->
                append("\n- ${r["scope"]}${r["equipment"]?.let { " $it" } ?: ""}: 측정 ${r["measCnt"] ?: "비공개"}, 불량 ${r["failCnt"] ?: "비공개"}, 불량률 ${r["failRate"] ?: "비공개"}, 설명률 ${r["explainedRate"] ?: "비공개"}")
            }
        }
        return AoiProjection("OK", text, rows)
    }

    /**
     * 질문에 맞는 집계를 근거 줄로 만든다. 수치 질문이 아니면 빈 목록.
     *
     * @return `[{ title, text, tool, args }]` — `text` 가 `[근거]` 한 줄. 번호는 화면이 붙인다
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun evidenceForDetailed(
        question: String,
        principal: UserPrincipal,
        previousPeriod: AiBusinessPeriod? = null
    ): EvidenceResult {
        val started = System.currentTimeMillis()
        val today = LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))
        val latestDataDate = runCatching {
            LocalDate.parse(commonMasterService.getProductionDateRange(null, null)["toDate"].toString())
        }.getOrNull() ?: today
        val decision = questionPlanner.plan(question, today, previousPeriod, latestDataDate)
        val product = decision as? AiQuestionPlanner.Decision.ProductList
        val dailyDefect = decision as? AiQuestionPlanner.Decision.DailyProductDefect
        val aoi = decision as? AiQuestionPlanner.Decision.AoiSummary
        val rate = decision as? AiQuestionPlanner.Decision.DefectRateTop
        val route = when (decision) {
            is AiQuestionPlanner.Decision.ProductList -> "PRODUCT_LIST"
            is AiQuestionPlanner.Decision.DailyProductDefect -> "DAILY_PRODUCT_DEFECT"
            is AiQuestionPlanner.Decision.AoiSummary -> "AOI_DIMENSION_SUMMARY"
            AiQuestionPlanner.Decision.AoiWorkcenterRequired -> "AOI_WORKCENTER_REQUIRED"
            is AiQuestionPlanner.Decision.DefectRateTop -> "DEFECT_RATE_TOP"
            is AiQuestionPlanner.Decision.DefectTop -> "DEFECT_TOP"
            is AiQuestionPlanner.Decision.ProductionCompare -> "PRODUCTION_COMPARE"
            is AiQuestionPlanner.Decision.DocumentCount -> "DOCUMENT_COUNT"
            AiQuestionPlanner.Decision.Greeting -> "GREETING"
            AiQuestionPlanner.Decision.Refusal -> "REFUSAL"
            AiQuestionPlanner.Decision.Other -> "OTHER"
            AiQuestionPlanner.Decision.Invalid -> "INVALID"
            AiQuestionPlanner.Decision.Unavailable -> "PLANNER_UNAVAILABLE"
        }
        val period = product?.period ?: dailyDefect?.period ?: rate?.period ?: (decision as? AiQuestionPlanner.Decision.DefectTop)?.period
            ?: (decision as? AiQuestionPlanner.Decision.ProductionCompare)?.period
        if (decision == AiQuestionPlanner.Decision.Invalid || decision == AiQuestionPlanner.Decision.Unavailable) {
            val evidence = listOf(mapOf<String, Any?>("title" to "조회 조건 확인",
                "text" to if (decision == AiQuestionPlanner.Decision.Unavailable) "질문을 판단하는 모델을 사용할 수 없습니다. 잠시 후 다시 시도해 주세요."
                    else "날짜 범위 또는 순위 조건을 해석할 수 없습니다. 날짜와 1~20 사이의 순위를 확인해 주세요.",
                "tool" to "condition_check", "args" to emptyMap<String, Any>()))
            return EvidenceResult(evidence, route, null, if (decision == AiQuestionPlanner.Decision.Invalid) "INVALID" else "UNAVAILABLE",
                "SKIPPED", if (decision == AiQuestionPlanner.Decision.Invalid) "INVALID_CONDITION" else "MODEL_UNAVAILABLE", 0, null,
                (System.currentTimeMillis() - started).toInt())
        }
        if (decision == AiQuestionPlanner.Decision.AoiWorkcenterRequired) {
            val allowed = principal.canAccessMenu(MenuId.QC_AOI)
            val evidence = listOf(mapOf<String, Any?>("title" to "AOI 조회 조건",
                "text" to if (allowed) "AOI 작업장을 지정해 주세요." else "AOI 판정 분석 화면의 열람 권한이 없습니다.",
                "tool" to "condition_check", "args" to emptyMap<String, Any>()))
            return EvidenceResult(evidence, route, null, "LLM_STRUCTURED", if (allowed) "WORKCENTER_REQUIRED" else "DENIED_MENU",
                null, 0, null, (System.currentTimeMillis() - started).toInt())
        }
        return try {
            if (aoi != null) {
                if (!principal.canAccessMenu(MenuId.QC_AOI)) {
                    val evidence = listOf(mapOf<String, Any?>("title" to "AOI 열람 제한", "text" to "AOI 판정 분석 화면의 열람 권한이 없습니다.",
                        "tool" to "condition_check", "args" to emptyMap<String, Any>()))
                    EvidenceResult(evidence, route, null, "LLM_STRUCTURED", "DENIED_MENU", null, 0, null,
                        (System.currentTimeMillis() - started).toInt())
                } else {
                    aoiArguments(aoi.wcCd, aoi.eqptCd, aoi.from?.toString(), aoi.to?.toString())
                    val (data, _) = aoiDimensionService.getSummary(aoi.from?.toString(), aoi.to?.toString(), aoi.wcCd, aoi.eqptCd)
                    val projection = aoiProjection(data)
                    val args = mapOf("from" to aoi.from?.toString(), "to" to aoi.to?.toString(),
                        "wcCd" to aoi.wcCd, "eqptCd" to aoi.eqptCd)
                    val evidence = listOf(mapOf<String, Any?>("title" to "AOI 치수 요약", "text" to projection.reason,
                        "tool" to AOI_DIMENSION_SUMMARY, "args" to args))
                    EvidenceResult(evidence, route, AOI_DIMENSION_SUMMARY, "LLM_STRUCTURED", projection.code,
                        null, projection.rows.size, null, (System.currentTimeMillis() - started).toInt(), projection.rows)
                }
            } else if (dailyDefect != null) {
                if (!MaskingSupport(principal).allowed(DataField.QTY)) {
                    val evidence = listOf(mapOf<String, Any?>("title" to "불량 수량 열람 제한",
                        "text" to "불량 수량 열람 권한이 없습니다.", "tool" to DAILY_PRODUCT_DEFECT,
                        "args" to emptyMap<String, Any>()))
                    EvidenceResult(evidence, route, DAILY_PRODUCT_DEFECT, "LLM_STRUCTURED", "DENIED_FIELDS", null,
                        0, dailyDefect.period, (System.currentTimeMillis() - started).toInt())
                } else {
                    val rows = aiFactRepository.dailyProductDefects(appProperties.defaultPlantCd, dailyDefect.period, dailyDefect.limit)
                    val total = rows.firstOrNull()?.get("totalCount") ?: 0L
                    val summary = if (rows.isEmpty()) "${dailyDefect.period.from}~${dailyDefect.period.to} 교대 업무일에 생산 불량 내역이 없습니다."
                    else buildString {
                        append("${dailyDefect.period.from}~${dailyDefect.period.to} 교대 업무일 · 일자×제품 모델×불량 유형 · 전체 ${total}행 중 ${rows.size}행")
                        rows.forEach { row ->
                            append("\n- ${row["date"]} · ${row["name"]} (${row["code"]})")
                            row["family"]?.let { append(" · 제품군 $it") }
                            append(" · ${row["defect"]} · 불량 ${num(row["quantity"] as? BigDecimal)}")
                        }
                    }
                    val evidence = listOf(mapOf<String, Any?>("title" to "일자별 제품·불량 유형", "text" to summary,
                        "tool" to DAILY_PRODUCT_DEFECT, "args" to mapOf("from" to dailyDefect.period.from.toString(),
                            "to" to dailyDefect.period.to.toString(), "limit" to dailyDefect.limit)))
                    EvidenceResult(evidence, route, DAILY_PRODUCT_DEFECT, "LLM_STRUCTURED",
                        if (rows.isEmpty()) "EMPTY" else "OK", null, rows.size, dailyDefect.period,
                        (System.currentTimeMillis() - started).toInt(), rows)
                }
            } else if (product != null) {
                val mask = MaskingSupport(principal)
                if (product.groupByDate) {
                    val rows = aiFactRepository.producedProductsDaily(appProperties.defaultPlantCd, product.period)
                    val text = if (rows.isEmpty()) "${product.period.from}~${product.period.to} 교대 영업일에 등록된 생산 제품이 없습니다."
                    else buildString {
                        append("${product.period.from}~${product.period.to} 교대 영업일 일자별 생산 제품 목록 · 총 ${rows.size}건")
                        val byDate = rows.groupBy { it["date"] as String }
                        byDate.forEach { (date, items) ->
                            append("\n\n[$date]")
                            items.forEach { row ->
                                val qty = mask.on(DataField.QTY) { row["quantity"] as? BigDecimal }
                                append("\n- ${row["name"] ?: row["code"]} (${row["code"]}) · 생산량 ${qty?.let { num(it) } ?: "비공개"}")
                            }
                        }
                    }
                    val evidence = listOf(mapOf<String, Any?>("title" to "일자별 생산 제품 목록", "text" to text,
                        "tool" to "production_product_list", "args" to mapOf("from" to product.period.from.toString(),
                            "to" to product.period.to.toString(), "groupByDate" to true)))
                    EvidenceResult(evidence, route, "production_product_list", "LLM_STRUCTURED",
                        if (rows.isEmpty()) "EMPTY" else "OK", null, rows.size, product.period,
                        (System.currentTimeMillis() - started).toInt(), rows, isDaily = true)
                } else {
                    val rows = aiFactRepository.producedProducts(appProperties.defaultPlantCd, product.period)
                    val text = if (rows.isEmpty()) "${product.period.from}~${product.period.to} 교대 영업일에 등록된 생산 제품이 없습니다."
                        else buildString {
                            append("${product.period.from}~${product.period.to} 교대 영업일 생산 제품 목록 · 전체 ${rows.first()["totalCount"]}종, 최대 100종 표시")
                            rows.forEach { row ->
                                val qty = mask.on(DataField.QTY) { row["quantity"] as? BigDecimal }
                                append("\n- ${row["name"] ?: row["code"]} (${row["code"]}) · 생산량 ${qty?.let { num(it) } ?: "비공개"}")
                            }
                        }
                    val evidence = listOf(mapOf<String, Any?>("title" to "생산 제품 목록", "text" to text,
                        "tool" to "production_product_list", "args" to mapOf("from" to product.period.from.toString(), "to" to product.period.to.toString())))
                    EvidenceResult(evidence, route, "production_product_list", "LLM_STRUCTURED",
                        if (rows.isEmpty()) "EMPTY" else "OK", null, rows.size, product.period,
                        (System.currentTimeMillis() - started).toInt(), rows, isDaily = false)
                }
            } else if (rate != null) {
                val mask = MaskingSupport(principal)
                if (!mask.allowed(DataField.YIELD)) {
                    val evidence = listOf(mapOf<String, Any?>("title" to "불량률 열람 제한", "text" to "불량률 항목의 열람 권한이 없습니다.",
                        "tool" to "defect_rate_top", "args" to emptyMap<String, Any>()))
                    EvidenceResult(evidence, route, "defect_rate_top", "LLM_STRUCTURED", "DENIED_FIELDS", null, 0,
                        rate.period, (System.currentTimeMillis() - started).toInt())
                } else {
                    val rows = aiFactRepository.topDefectRates(appProperties.defaultPlantCd, rate.period, rate.limit)
                    val text = if (rows.isEmpty()) "MES 실적 DB 조회 결과, ${rate.period.from}~${rate.period.to} 교대 업무일에 제품별 불량률 순위 자료가 없습니다. 해당 기간의 집계 자료를 확인해 주세요."
                        else buildString {
                            append("${rate.period.from}~${rate.period.to} 교대 영업일 제품별 가중 불량률 상위 ${rate.limit}종")
                            rows.forEachIndexed { index, row ->
                                val qty = mask.on(DataField.QTY) { row["quantity"] as? BigDecimal }
                                val ng = mask.on(DataField.QTY) { row["defectQuantity"] as? BigDecimal }
                                append("\n${index + 1}. ${row["name"] ?: row["code"]} (${row["code"]}) · 불량률 ${row["defectRate"]}%")
                                if (qty != null && ng != null) append(" · 불량 ${num(ng)}/${num(qty)}")
                            }
                        }
                    val evidence = listOf(mapOf<String, Any?>("title" to "제품별 불량률 상위 ${rate.limit}", "text" to text,
                        "tool" to "defect_rate_top", "args" to mapOf("from" to rate.period.from.toString(),
                            "to" to rate.period.to.toString(), "limit" to rate.limit)))
                    EvidenceResult(evidence, route, "defect_rate_top", "LLM_STRUCTURED",
                        if (rows.isEmpty()) "EMPTY" else "OK", null, rows.size, rate.period,
                        (System.currentTimeMillis() - started).toInt(), rows)
                }
            } else if (decision is AiQuestionPlanner.Decision.DefectTop) {
                val mask = MaskingSupport(principal)
                val rows = aiFactRepository.topDefects(appProperties.defaultPlantCd, decision.period, decision.limit)
                val evidence = rows.mapIndexed { index, row ->
                    val qty = mask.on(DataField.QTY) { row["quantity"] as? BigDecimal }
                    mapOf<String, Any?>("title" to "불량 상위 ${decision.limit} · ${decision.period.from}~${decision.period.to}",
                        "text" to "${decision.period.from}~${decision.period.to} 교대 영업일 불량 ${index + 1}위: ${row["name"]}, 수량 ${qty?.let { num(it) } ?: "비공개"}",
                        "tool" to "defect_top", "args" to mapOf("from" to decision.period.from.toString(),
                            "to" to decision.period.to.toString(), "limit" to decision.limit))
                }
                EvidenceResult(evidence, route, "defect_top", "LLM_STRUCTURED", if (rows.isEmpty()) "EMPTY" else "OK",
                    null, rows.size, decision.period, (System.currentTimeMillis() - started).toInt(), rows)
            } else if (decision is AiQuestionPlanner.Decision.DocumentCount) {
                val evidence = decision.years.map { year ->
                    val counts = aiFactRepository.documentCount(year, principal.userId, decision.byRegistration, decision.defectReports)
                    val basis = if (decision.byRegistration) "시스템 등록 시각(한국시간)" else "문서 기준일"
                    val extra = if (decision.byRegistration) "" else " 기준일 미기재 ${counts["undated"]}건은 연도 집계에서 제외."
                    val category = if (decision.defectReports) "불량 보고서" else "문서"
                    mapOf<String, Any?>("title" to "${year}년 $category 개수",
                        "text" to "${year}년 $basis 기준 열람 가능한 색인 완료 $category ${counts["dated"]}건.$extra",
                        "tool" to "document_count", "args" to mapOf("year" to year,
                            "basis" to if (decision.byRegistration) "registered" else "document_date"))
                }
                EvidenceResult(evidence, route, "document_count", "LLM_STRUCTURED", "OK", null, evidence.size,
                    null, (System.currentTimeMillis() - started).toInt())
            } else if (decision is AiQuestionPlanner.Decision.ProductionCompare) {
                val main = Period(decision.period.from, decision.period.to, "조회 기간")
                val compare = decision.compare?.let { Period(it.from, it.to, "비교 기간") }
                @Suppress("UNCHECKED_CAST")
                val periods = aggregate(main, compare, MaskingSupport(principal))["periods"] as List<Map<String, Any?>>
                val totals = periods.map { it["total"] as? ProcessPeriodRow }
                val rateDeltaPt = totals.getOrNull(0)?.defectRate?.let { current ->
                    totals.getOrNull(1)?.defectRate?.let { previous -> current - previous }
                }
                val evidence = periods.mapIndexed { index, p ->
                    val total = p["total"] as ProcessPeriodRow?
                    val delta = if (index == 0 && rateDeltaPt != null) {
                        " · 직전 기간 대비 불량률 ${if (rateDeltaPt >= 0) "+" else ""}${"%.2f".format(rateDeltaPt)}%p"
                    } else ""
                    mapOf<String, Any?>("title" to "생산 실적 집계 · ${p["label"]}",
                        "text" to "${p["from"]}~${p["to"]} 교대 업무일 생산 실적 · ${metrics(total)}$delta",
                        "tool" to PRODUCTION_PERIOD_COMPARE, "args" to mapOf("from" to p["from"], "to" to p["to"]))
                }
                val tableRows = periods.mapIndexed { index, p ->
                    val total = totals[index]
                    mapOf<String, Any?>("label" to p["label"], "from" to p["from"], "to" to p["to"],
                        "quantity" to total?.qty, "defectQuantity" to total?.ngQty,
                        "defectRate" to total?.defectRate, "yieldRate" to total?.yieldRate,
                        "defectRateDeltaPt" to if (index == 0) rateDeltaPt else null)
                }
                EvidenceResult(evidence, route, PRODUCTION_PERIOD_COMPARE, "LLM_STRUCTURED", "OK", null,
                    evidence.size, decision.period, (System.currentTimeMillis() - started).toInt(), tableRows)
            } else {
                EvidenceResult(emptyList(), route, null, "LLM_STRUCTURED", "SKIPPED", null, 0,
                    null, (System.currentTimeMillis() - started).toInt())
            }
        } catch (e: InvalidParameterException) {
            if (route != "AOI_DIMENSION_SUMMARY") throw e
            EvidenceResult(listOf(mapOf("title" to "AOI 조회 조건",
                "text" to "AOI 조회 기간 또는 작업장·설비 조건을 확인해 주세요.", "tool" to "condition_check",
                "args" to emptyMap<String, Any>())), route, null, "INVALID", "SKIPPED", "INVALID_CONDITION", 0,
                null, (System.currentTimeMillis() - started).toInt())
        } catch (e: MenuAccessDeniedException) {
            EvidenceResult(listOf(mapOf("title" to "AOI 열람 제한", "text" to "AOI 판정 분석 화면의 열람 권한이 없습니다.",
                "tool" to "condition_check", "args" to emptyMap<String, Any>())), route, null,
                "LLM_STRUCTURED", "DENIED_MENU", null, 0, null, (System.currentTimeMillis() - started).toInt())
        } catch (e: Exception) {
            log.warn("AI 도구 실행 실패: route={} exceptionType={}", route, e.javaClass.simpleName)
            EvidenceResult(emptyList(), route, when (route) { "PRODUCT_LIST" -> PRODUCTION_PRODUCT_LIST; "DAILY_PRODUCT_DEFECT" -> DAILY_PRODUCT_DEFECT; "DEFECT_RATE_TOP" -> DEFECT_RATE_TOP; else -> null },
                "LLM_STRUCTURED", "FAILED", "QUERY_FAILED", 0, period,
                (System.currentTimeMillis() - started).toInt())
        }
    }

    fun evidenceFor(question: String, principal: UserPrincipal): List<Map<String, Any?>> =
        evidenceForDetailed(question, principal).evidence

    private fun legacyEvidenceFor(question: String, principal: UserPrincipal): List<Map<String, Any?>> {
        AiQuestionParser.documentCount(question, LocalDate.now(java.time.ZoneId.of("Asia/Seoul")))?.let { spec ->
            return spec.years.map { year ->
                val counts = aiFactRepository.documentCount(year, principal.userId, spec.byRegistration, spec.defectReports)
                val label = if (spec.byRegistration) "시스템 등록 시각(한국시간)" else "문서 기준일"
                val extra = if (spec.byRegistration) "" else " 기준일 미기재 ${counts["undated"]}건은 연도 집계에서 제외."
                val category = if (spec.defectReports) "불량 보고서" else "문서"
                mapOf("title" to "${year}년 $category 개수", "text" to "${year}년 $label 기준 열람 가능한 색인 완료 $category ${counts["dated"]}건.$extra",
                    "tool" to "document_count", "args" to mapOf("year" to year, "basis" to if (spec.byRegistration) "registered" else "document_date"))
            }
        }
        val last = runCatching {
            LocalDate.parse(commonMasterService.getProductionDateRange(null, null)["toDate"].toString())
        }.getOrNull() ?: return emptyList()
        AiQuestionParser.defectTop(question, last)?.let { top ->
            val mask = MaskingSupport(principal)
            return aiFactRepository.topDefects(appProperties.defaultPlantCd, top.period, top.limit)
                .mapIndexed { index, row ->
                    val qty = mask.on(DataField.QTY) { row["quantity"] as? BigDecimal }
                    mapOf("title" to "불량 상위 ${top.limit} · ${top.period.from}~${top.period.to}",
                        "text" to "${top.period.from}~${top.period.to} 교대 영업일 불량 ${index + 1}위: ${row["name"]}, 수량 ${qty?.let { num(it) } ?: "비공개"}",
                        "tool" to "defect_top", "args" to mapOf("from" to top.period.from.toString(),
                            "to" to top.period.to.toString(), "limit" to top.limit))
                }
        }
        val (main, compare) = resolvePeriods(question, last) ?: return emptyList()
        val mask = MaskingSupport(principal)
        val result = aggregate(main, compare, mask)
        val args = buildMap<String, Any?> {
            put("from", main.from.toString()); put("to", main.to.toString()); put("label", main.label)
            compare?.let { put("compareFrom", it.from.toString()); put("compareTo", it.to.toString()); put("compareLabel", it.label) }
        }

        @Suppress("UNCHECKED_CAST")
        val periods = result["periods"] as List<Map<String, Any?>>
        val lines = mutableListOf<Map<String, Any?>>()
        periods.forEach { p ->
            val total = p["total"] as ProcessPeriodRow?
            val from = LocalDate.parse(p["from"].toString())
            val to = LocalDate.parse(p["to"].toString())
            val days = ChronoUnit.DAYS.between(from, to) + 1
            // 기간 길이가 다르면(16일 vs 31일) 생산량을 그대로 비교하면 안 된다 — 부분 월임을 적고 일평균을 함께 준다.
            val partial = from.dayOfMonth == 1 && to != to.with(TemporalAdjusters.lastDayOfMonth())
            val range = "${p["from"]}~${p["to"]}(${p["label"]}${if (partial) ", 부분 월" else ""}, ${days}일)"
            val daily = total?.qty?.let { " · 일평균 생산량 " + num(it.divide(BigDecimal(days), 0, java.math.RoundingMode.HALF_UP)) } ?: ""
            lines += mapOf(
                "title" to "생산 실적 집계 · 공장 전체 · $range",
                "text" to "생산 실적 집계 · 공장 전체 · $range · " + metrics(total) + daily,
                "tool" to PRODUCTION_PERIOD_COMPARE,
                "args" to args
            )
        }
        // 공정별 — 앞 기간의 불량 수 많은 순. 비교 기간 값은 같은 줄에 나란히 적는다.
        @Suppress("UNCHECKED_CAST")
        val mainProcesses = periods.first()["processes"] as List<ProcessPeriodRow>
        @Suppress("UNCHECKED_CAST")
        val cmpBy = (periods.getOrNull(1)?.get("processes") as List<ProcessPeriodRow>?)?.associateBy { it.processId }.orEmpty()
        val mentioned = question.lowercase()
        val picked = (mainProcesses.filter { r -> r.process?.lowercase()?.let { n -> n.split(Regex("""[^\p{L}\p{N}]+""")).any { it.length >= 2 && mentioned.contains(it) } } == true } +
            mainProcesses.sortedByDescending { it.ngQty ?: BigDecimal.ZERO }).distinctBy { it.processId }.take(PROCESS_LINES)
        if (picked.isNotEmpty()) {
            val text = buildString {
                append("생산 실적 집계 · 공정별(불량 수 많은 순) · ${periods.first()["label"]}")
                periods.getOrNull(1)?.let { append(" vs ${it["label"]}") }
                picked.forEach { r ->
                    append("\n- ${r.processId} ${r.process ?: ""} · ${periods.first()["label"]} ${metrics(r)}")
                    cmpBy[r.processId]?.let { c -> append(" · ${periods[1]["label"]} ${metrics(c)}") }
                }
            }
            lines += mapOf(
                "title" to "생산 실적 집계 · 공정별 · ${periods.first()["from"]}~${periods.first()["to"]}",
                "text" to text,
                "tool" to PRODUCTION_PERIOD_COMPARE,
                "args" to args
            )
        }
        return lines
    }

    private fun metrics(r: ProcessPeriodRow?): String {
        if (r == null) return "실적 없음"
        val parts = listOfNotNull(
            num(r.qty)?.let { "생산량 $it" },
            num(r.ngQty)?.let { "불량 $it" },
            pct(r.defectRate)?.let { "불량률 $it" },
            pct(r.yieldRate)?.let { "수율 $it" }
        )
        return parts.joinToString(" · ").ifEmpty { "값 비공개(권한 밖)" }
    }

    /** 기간별 공장 전체·공정별 집계. 92일을 넘으면 끝에서 92일로 자른다 */
    private fun aggregate(main: Period, compare: Period?, mask: MaskingSupport): Map<String, Any?> {
        val periods = listOfNotNull(main, compare).map { p ->
            val from = maxOf(p.from, p.to.minusDays(MAX_SPAN_DAYS - 1))
            // ProcessPeriod의 from은 경계 날짜다. 첫 업무일을 포함하려면 전일을 넘긴다.
            val range = ProcessPeriod.parse(from.minusDays(1).toString(), p.to.toString(), "month")
            val rows = dashboardProcessRepository.findPeriod(appProperties.defaultPlantCd, range, null, emptyList())
                .groupBy({ it.first }, { it.second })
            mapOf(
                "label" to p.label,
                "from" to from.toString(),
                "to" to p.to.toString(),
                "clipped" to (from != p.from),
                "total" to rows["summary"]?.singleOrNull()?.masked(mask),
                "processes" to rows["processes"].orEmpty().map { it.masked(mask) }
            )
        }
        return mapOf("tool" to PRODUCTION_PERIOD_COMPARE, "periods" to periods, "maskedFields" to mask.maskedKeys())
    }
}
