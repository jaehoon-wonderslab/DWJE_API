package com.dwje.api.service

import com.dwje.api.common.util.AiBusinessPeriod
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** LLM의 1차 판단을 검증된 도구 인자로 바꾼다. 모델 텍스트를 SQL로 실행하지 않는다. */
@Service
class AiQuestionPlanner(private val proxy: LlmChatProxyService, private val objectMapper: ObjectMapper) {
    /** 조건 오류 갈래 — 날짜를 못 읽음 · 오늘 이후 · 시작>종료 · 기간 상한 초과 · 순위 범위 · 그 밖 */
    enum class InvalidReason { DATE, FUTURE, ORDER, SPAN, LIMIT, CONDITION }

    sealed interface Decision {
        data class ProductList(val period: AiBusinessPeriod, val groupByDate: Boolean = false) : Decision
        data class DailyProductDefect(val period: AiBusinessPeriod, val limit: Int) : Decision
        data class AoiSummary(val from: LocalDate?, val to: LocalDate?, val wcCd: String, val eqptCd: String?) : Decision
        data object AoiWorkcenterRequired : Decision
        data class DefectRateTop(val period: AiBusinessPeriod, val limit: Int) : Decision
        data class DefectTop(val period: AiBusinessPeriod, val limit: Int) : Decision
        data class ProductionCompare(val period: AiBusinessPeriod, val compare: AiBusinessPeriod?) : Decision
        data class DocumentCount(val years: List<Int>, val byRegistration: Boolean, val defectReports: Boolean) : Decision
        data object General : Decision
        data object Greeting : Decision
        data object Refusal : Decision
        data object Other : Decision
        /** 모델이 DB 도구를 골랐지만 인자가 검증을 통과하지 못했다 — [reason] 으로 안내 문구를 고른다 */
        data class Invalid(val reason: InvalidReason = InvalidReason.CONDITION) : Decision
        data object Unavailable : Decision
    }

    fun plan(
        question: String,
        today: LocalDate = LocalDate.now(ZoneId.of("Asia/Seoul")),
        previousPeriod: AiBusinessPeriod? = null,
        latestDataDate: LocalDate = today
    ): Decision {
        if (isGreeting(question)) return Decision.Greeting
        val routingTools = AiDataToolService.TOOLS + listOf(mapOf<String, Any?>(
            "name" to "general_chat",
            "description" to "인사, 일상 대화, 번역, 일반 지식. 사내 규정·문서·생산 실적·품질 데이터 및 이전 업무 조회의 후속 질문에는 사용하지 않는다.",
            "inputSchema" to mapOf("type" to "object", "properties" to emptyMap<String, Any>())
        ))
        val toolQuestion = buildString {
            append(question)
            append("\n[업무 데이터 기준일]\nMES에서 조회 가능한 최신 실적일은 $latestDataDate 이다.")
            previousPeriod?.let {
                append("\n[직전 조회 기간]\n${it.from}~${it.to}. 현재 질문이 기간을 새로 지정하지 않은 후속 조회라면 이 기간을 사용하라.")
            }
        }
        val message = proxy.chooseTool(toolQuestion, routingTools, today) ?: return Decision.Unavailable
        if (!message.isObject) return Decision.Unavailable
        val calls = message.path("tool_calls")
        if (calls.isMissingNode || calls.isNull || calls.isEmpty) {
            return if (message.path("content").asText("").isNotBlank()) Decision.Other else Decision.Unavailable
        }
        if (!calls.isArray || calls.size() != 1) return invalid("tool_calls=${calls.size()}", "", Decision.Invalid())
        val function = calls[0].path("function")
        val name = function.path("name").asText("")
        val rawArgs = function.path("arguments").asText("")
        val args = runCatching { objectMapper.readTree(rawArgs) }.getOrNull()
            ?: return invalid(name, rawArgs, Decision.Invalid())
        if (!args.isObject) return invalid(name, rawArgs, Decision.Invalid())
        val decision = validate(name, args, today, previousPeriod)
        if (decision is Decision.Invalid) return invalid(name, rawArgs, decision)
        if (decision is Decision.AoiSummary) {
            if (!question.contains(decision.wcCd, ignoreCase = true)) return Decision.AoiWorkcenterRequired
            if (decision.eqptCd != null && !question.contains(decision.eqptCd, ignoreCase = true)) return Decision.Invalid()
        }
        return decision
    }

    /** 검증 탈락 — 도구 이름·인자 원문만 남긴다(질문 원문은 남기지 않는다). 원인 추적용 */
    private fun invalid(tool: String, rawArgs: String, decision: Decision.Invalid): Decision {
        log.warn("AI 도구 인자 검증 탈락 : tool={} reason={} args={}", tool, decision.reason, rawArgs.take(300))
        return decision
    }

    /** 기간 검사 — 시작>종료 · 종료가 오늘 이후 · [maxDays] 초과. 통과면 null */
    private fun periodRule(from: LocalDate, to: LocalDate, today: LocalDate, maxDays: Long = 92): Decision.Invalid? = when {
        from.isAfter(to) -> Decision.Invalid(InvalidReason.ORDER)
        to.isAfter(today) -> Decision.Invalid(InvalidReason.FUTURE)
        ChronoUnit.DAYS.between(from, to) > maxDays -> Decision.Invalid(InvalidReason.SPAN)
        else -> null
    }

    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)

    private fun validate(tool: String, node: JsonNode, today: LocalDate, previousPeriod: AiBusinessPeriod?): Decision {
        if (tool == "general_chat") return Decision.General
        if (tool == "other") return Decision.Other
        if (tool == "greeting") return Decision.Greeting
        if (tool == "refusal") return Decision.Refusal
        if (tool == "document_count") {
            val years = node.path("years").takeIf { it.isArray }?.mapNotNull { it.takeIf(JsonNode::isIntegralNumber)?.asInt() }
                ?: return Decision.Invalid()
            if (years.isEmpty() || years.size > 2 || years.distinct().size != years.size || years.any { it !in 2000..today.year }) return Decision.Invalid()
            val basis = node.path("basis").asText("")
            if (basis !in setOf("registered", "document_date") || !node.path("defectReports").isBoolean) return Decision.Invalid()
            return Decision.DocumentCount(years, basis == "registered", node.path("defectReports").asBoolean())
        }
        if (tool == "aoi_dimension_summary") {
            val wcCd = node.path("wcCd").takeIf { it.isTextual }?.asText()?.trim().orEmpty()
            if (wcCd.isEmpty()) return Decision.AoiWorkcenterRequired
            if (!SAFE_AOI_CODE.matches(wcCd)) return Decision.Invalid()
            val eqptCd = node.path("eqptCd").takeIf { it.isTextual }?.asText()?.trim()?.takeIf { it.isNotEmpty() }
            if (eqptCd != null && !SAFE_AOI_CODE.matches(eqptCd)) return Decision.Invalid()
            fun optionalDate(key: String): LocalDate? = node.path(key).takeIf { it.isTextual && it.asText().isNotBlank() }
                ?.let { parseDate(it.asText(), today) }
            val from = optionalDate("from")
            val to = optionalDate("to")
            if (node.path("from").asText("").isNotBlank() && from == null) return Decision.Invalid(InvalidReason.DATE)
            if (node.path("to").asText("").isNotBlank() && to == null) return Decision.Invalid(InvalidReason.DATE)
            val effectiveTo = to ?: today
            periodRule(from ?: effectiveTo, effectiveTo, today)?.let { return it }
            return Decision.AoiSummary(from, to, wcCd, eqptCd)
        }
        if (tool !in setOf("production_product_list", "daily_product_defect", "defect_rate_top", "defect_top", "production_period_compare")) return Decision.Invalid()
        val fromRaw = node.path("from").asText("").trim()
        val toRaw = node.path("to").asText("").trim()
        val inheritPrevious = tool == "production_period_compare" && previousPeriod != null && fromRaw.isBlank() && toRaw.isBlank()
        // 한쪽 끝만 오면 그 하루로 본다 — 「9/20 불량 유형 top 3」 에서 to 를 빼먹은 인자가 실제로 왔다(2026-10-02)
        val fromText = fromRaw.ifBlank { toRaw }
        val toText = toRaw.ifBlank { fromRaw }
        val to = if (inheritPrevious) previousPeriod!!.to else parseDate(toText, today) ?: return Decision.Invalid(InvalidReason.DATE)
        val from = if (inheritPrevious) previousPeriod!!.from else parseDate(fromText, to) ?: return Decision.Invalid(InvalidReason.DATE)
        periodRule(from, to, today)?.let { return it }
        val period = AiBusinessPeriod(from, to)
        if (tool == "daily_product_defect") {
            val limitNode = node.path("limit")
            val limit = if (limitNode.isMissingNode) 200 else limitNode.takeIf { it.isIntegralNumber }?.asInt() ?: return Decision.Invalid(InvalidReason.LIMIT)
            return when {
                ChronoUnit.DAYS.between(from, to) >= 31 -> Decision.Invalid(InvalidReason.SPAN)
                limit !in 1..200 -> Decision.Invalid(InvalidReason.LIMIT)
                else -> Decision.DailyProductDefect(period, limit)
            }
        }
        if (tool == "production_product_list") {
            val groupByDate = node.path("groupByDate").asBoolean(false)
            return Decision.ProductList(period, groupByDate)
        }
        if (tool == "production_period_compare") {
            val cf = node.path("compareFrom").asText("")
            val ct = node.path("compareTo").asText("")
            val compare = if (cf.isBlank() && ct.isBlank()) null else {
                val end = parseDate(ct.ifBlank { cf }, today) ?: return Decision.Invalid(InvalidReason.DATE)
                val start = parseDate(cf.ifBlank { ct }, end) ?: return Decision.Invalid(InvalidReason.DATE)
                periodRule(start, end, today)?.let { return it }
                AiBusinessPeriod(start, end)
            }
            return Decision.ProductionCompare(period, compare)
        }
        val limit = node.path("limit").takeIf { it.isIntegralNumber }?.asInt() ?: return Decision.Invalid(InvalidReason.LIMIT)
        return when (tool) {
            "defect_rate_top" -> if (limit in 1..20) Decision.DefectRateTop(period, limit) else Decision.Invalid(InvalidReason.LIMIT)
            "defect_top" -> if (limit in 1..10) Decision.DefectTop(period, limit) else Decision.Invalid(InvalidReason.LIMIT)
            else -> Decision.Invalid()
        }
    }

    companion object {
        fun isGreeting(question: String): Boolean = Regex(
            "^(ㅎㅇ|안녕(?:하세요|하십니까)?|하이|hi|hello|고마워(?:요)?|감사(?:합니다|해요)?)[!?.~\\s]*$",
            RegexOption.IGNORE_CASE
        ).matches(question.trim())

        private val SAFE_AOI_CODE = Regex("[A-Za-z0-9_-]{1,32}")
        private val FULL_DATE = Regex("""(\d{4})\s*[-/.]\s*(\d{1,2})\s*[-/.]\s*(\d{1,2})\.?""")
        private val MONTH_DAY = Regex("""(\d{1,2})\s*[-/.]\s*(\d{1,2})\.?""")

        /**
         * 모델이 낸 날짜를 너그럽게 읽는다 — `yyyy-MM-dd` 외에 `yyyy-M-d`·`yyyy/M/d`·`yyyy.M.d`, 연도 없는 `M/d`·`M-d`.
         *
         * 연도가 없으면 [notAfter] 를 넘지 않는 가장 최근 연도로 본다(지시문의 「종료 업무일이 오늘을 넘지 않는 가장 최근 유효 연도」).
         * 종료일은 오늘을, 시작일은 이미 읽은 종료일을 [notAfter] 로 준다 — 12/28~1/3 처럼 해를 넘는 기간도 맞게 읽힌다.
         * 없는 날짜(2/30)·그 밖의 모양은 null. 미래·92일·from>to 검사는 부르는 쪽이 그대로 한다.
         */
        fun parseDate(text: String, notAfter: LocalDate): LocalDate? {
            val t = text.trim()
            if (t.isEmpty()) return null
            FULL_DATE.matchEntire(t)?.destructured?.let { (y, m, d) -> return of(y.toInt(), m.toInt(), d.toInt()) }
            MONTH_DAY.matchEntire(t)?.destructured?.let { (m, d) ->
                val month = m.toInt(); val day = d.toInt()
                // 2/29 는 윤년까지 거슬러 찾는다
                return (0..8).asSequence().mapNotNull { of(notAfter.year - it, month, day) }.firstOrNull { !it.isAfter(notAfter) }
            }
            return null
        }

        private fun of(y: Int, m: Int, d: Int): LocalDate? = runCatching { LocalDate.of(y, m, d) }.getOrNull()
    }
}
