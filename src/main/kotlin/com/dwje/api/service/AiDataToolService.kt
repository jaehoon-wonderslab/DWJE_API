package com.dwje.api.service

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.exception.ResourceNotFoundException
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.common.util.MaskingSupport
import com.dwje.api.common.util.ProcessPeriod
import com.dwje.api.common.util.ProcessPeriodRow
import com.dwje.api.config.AppProperties
import com.dwje.api.repository.DashboardProcessRepository
import org.springframework.stereotype.Service
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
    private val appProperties: AppProperties
) {

    companion object {
        const val PRODUCTION_PERIOD_COMPARE = "production_period_compare"

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
                "description" to "생산 실적(생산량·양품·불량·불량률·수율)을 기간으로 집계한다. 공장 전체와 공정별(불량 수 많은 순)로 낸다. " +
                    "compareFrom·compareTo 를 주면 두 기간을 나란히 낸다. 한 기간은 최대 92일. 업무일(08:00 교대) 기준.",
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

    /**
     * 도구 실행 (MCP `tools/call` 모양) — 구조화된 결과를 낸다.
     *
     * @param principal 조회자 — 데이터 권한으로 값을 가린다
     */
    fun call(name: String, args: Map<String, Any?>, principal: UserPrincipal): Map<String, Any?> {
        if (name != PRODUCTION_PERIOD_COMPARE) throw ResourceNotFoundException("없는 도구입니다. [$name]")
        fun date(key: String, required: Boolean): LocalDate? {
            val v = args[key]?.toString()?.trim().orEmpty()
            if (v.isEmpty()) {
                if (required) throw InvalidParameterException("$key 가 필요합니다(YYYY-MM-DD).", key)
                return null
            }
            return runCatching { LocalDate.parse(v) }.getOrElse { throw InvalidParameterException("$key 형식이 올바르지 않습니다(YYYY-MM-DD). [$v]", key) }
        }
        val main = Period(date("from", true)!!, date("to", true)!!, args["label"]?.toString() ?: "기간")
        val cf = date("compareFrom", false)
        val ct = date("compareTo", false)
        val compare = if (cf != null && ct != null) Period(cf, ct, args["compareLabel"]?.toString() ?: "비교 기간") else null
        return aggregate(main, compare, MaskingSupport(principal))
    }

    /**
     * 질문에 맞는 집계를 근거 줄로 만든다. 수치 질문이 아니면 빈 목록.
     *
     * @return `[{ title, text, tool, args }]` — `text` 가 `[근거]` 한 줄. 번호는 화면이 붙인다
     */
    fun evidenceFor(question: String, principal: UserPrincipal): List<Map<String, Any?>> {
        val last = runCatching {
            LocalDate.parse(commonMasterService.getProductionDateRange(null, null)["toDate"].toString())
        }.getOrNull() ?: return emptyList()
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
            val from = maxOf(p.from, p.to.minusDays(MAX_SPAN_DAYS))
            val range = ProcessPeriod.parse(from.toString(), p.to.toString(), "month")
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
