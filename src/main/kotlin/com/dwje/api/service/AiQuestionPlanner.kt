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
    sealed interface Decision {
        data class ProductList(val period: AiBusinessPeriod, val groupByDate: Boolean = false) : Decision
        data class DailyProductDefect(val period: AiBusinessPeriod, val limit: Int) : Decision
        data class AoiSummary(val from: LocalDate?, val to: LocalDate?, val wcCd: String, val eqptCd: String?) : Decision
        data object AoiWorkcenterRequired : Decision
        data class DefectRateTop(val period: AiBusinessPeriod, val limit: Int) : Decision
        data class DefectTop(val period: AiBusinessPeriod, val limit: Int) : Decision
        data class ProductionCompare(val period: AiBusinessPeriod, val compare: AiBusinessPeriod?) : Decision
        data class DocumentCount(val years: List<Int>, val byRegistration: Boolean, val defectReports: Boolean) : Decision
        data object Greeting : Decision
        data object Refusal : Decision
        data object Other : Decision
        data object Invalid : Decision
        data object Unavailable : Decision
    }

    fun plan(
        question: String,
        today: LocalDate = LocalDate.now(ZoneId.of("Asia/Seoul")),
        previousPeriod: AiBusinessPeriod? = null,
        latestDataDate: LocalDate = today
    ): Decision {
        val toolQuestion = buildString {
            append(question)
            append("\n[업무 데이터 기준일]\nMES에서 조회 가능한 최신 실적일은 $latestDataDate 이다.")
            previousPeriod?.let {
                append("\n[직전 조회 기간]\n${it.from}~${it.to}. 현재 질문이 기간을 새로 지정하지 않은 후속 조회라면 이 기간을 사용하라.")
            }
        }
        val message = proxy.chooseTool(toolQuestion, AiDataToolService.TOOLS, today) ?: return Decision.Unavailable
        if (!message.isObject) return Decision.Unavailable
        val calls = message.path("tool_calls")
        if (calls.isMissingNode || calls.isNull || calls.isEmpty) {
            return if (message.path("content").asText("").isNotBlank()) Decision.Other else Decision.Unavailable
        }
        if (!calls.isArray || calls.size() != 1) return Decision.Invalid
        val function = calls[0].path("function")
        val name = function.path("name").asText("")
        val args = runCatching { objectMapper.readTree(function.path("arguments").asText("")) }.getOrNull()
            ?: return Decision.Invalid
        if (!args.isObject) return Decision.Invalid
        val decision = validate(name, args, today, previousPeriod)
        if (decision is Decision.AoiSummary) {
            if (!question.contains(decision.wcCd, ignoreCase = true)) return Decision.AoiWorkcenterRequired
            if (decision.eqptCd != null && !question.contains(decision.eqptCd, ignoreCase = true)) return Decision.Invalid
        }
        return decision
    }

    private fun validate(tool: String, node: JsonNode, today: LocalDate, previousPeriod: AiBusinessPeriod?): Decision {
        if (tool == "other") return Decision.Other
        if (tool == "greeting") return Decision.Greeting
        if (tool == "refusal") return Decision.Refusal
        if (tool == "document_count") {
            val years = node.path("years").takeIf { it.isArray }?.mapNotNull { it.takeIf(JsonNode::isIntegralNumber)?.asInt() }
                ?: return Decision.Invalid
            if (years.isEmpty() || years.size > 2 || years.distinct().size != years.size || years.any { it !in 2000..today.year }) return Decision.Invalid
            val basis = node.path("basis").asText("")
            if (basis !in setOf("registered", "document_date") || !node.path("defectReports").isBoolean) return Decision.Invalid
            return Decision.DocumentCount(years, basis == "registered", node.path("defectReports").asBoolean())
        }
        if (tool == "aoi_dimension_summary") {
            val wcCd = node.path("wcCd").takeIf { it.isTextual }?.asText()?.trim().orEmpty()
            if (wcCd.isEmpty()) return Decision.AoiWorkcenterRequired
            if (!SAFE_AOI_CODE.matches(wcCd)) return Decision.Invalid
            val eqptCd = node.path("eqptCd").takeIf { it.isTextual }?.asText()?.trim()?.takeIf { it.isNotEmpty() }
            if (eqptCd != null && !SAFE_AOI_CODE.matches(eqptCd)) return Decision.Invalid
            fun optionalDate(key: String): LocalDate? = node.path(key).takeIf { it.isTextual && it.asText().isNotBlank() }
                ?.let { runCatching { LocalDate.parse(it.asText()) }.getOrNull() }
            val from = optionalDate("from")
            val to = optionalDate("to")
            if (node.path("from").asText("").isNotBlank() && from == null) return Decision.Invalid
            if (node.path("to").asText("").isNotBlank() && to == null) return Decision.Invalid
            val effectiveTo = to ?: today
            if (effectiveTo.isAfter(today) || (from != null && (from.isAfter(effectiveTo) || ChronoUnit.DAYS.between(from, effectiveTo) > 92))) return Decision.Invalid
            return Decision.AoiSummary(from, to, wcCd, eqptCd)
        }
        if (tool !in setOf("production_product_list", "daily_product_defect", "defect_rate_top", "defect_top", "production_period_compare")) return Decision.Invalid
        val fromText = node.path("from").asText("")
        val toText = node.path("to").asText("")
        val inheritPrevious = tool == "production_period_compare" && previousPeriod != null && fromText.isBlank() && toText.isBlank()
        val from = if (inheritPrevious) previousPeriod!!.from else runCatching { LocalDate.parse(fromText) }.getOrNull() ?: return Decision.Invalid
        val to = if (inheritPrevious) previousPeriod!!.to else runCatching { LocalDate.parse(toText) }.getOrNull() ?: return Decision.Invalid
        if (from.isAfter(to) || to.isAfter(today) || ChronoUnit.DAYS.between(from, to) > 92) return Decision.Invalid
        val period = AiBusinessPeriod(from, to)
        if (tool == "daily_product_defect") {
            val limitNode = node.path("limit")
            val limit = if (limitNode.isMissingNode) 200 else limitNode.takeIf { it.isIntegralNumber }?.asInt() ?: return Decision.Invalid
            return if (ChronoUnit.DAYS.between(from, to) < 31 && limit in 1..200)
                Decision.DailyProductDefect(period, limit) else Decision.Invalid
        }
        if (tool == "production_product_list") {
            val groupByDate = node.path("groupByDate").asBoolean(false)
            return Decision.ProductList(period, groupByDate)
        }
        if (tool == "production_period_compare") {
            val cf = node.path("compareFrom").asText("")
            val ct = node.path("compareTo").asText("")
            val compare = if (cf.isBlank() && ct.isBlank()) null else {
                val start = runCatching { LocalDate.parse(cf) }.getOrNull() ?: return Decision.Invalid
                val end = runCatching { LocalDate.parse(ct) }.getOrNull() ?: return Decision.Invalid
                if (start.isAfter(end) || end.isAfter(today) || ChronoUnit.DAYS.between(start, end) > 92) return Decision.Invalid
                AiBusinessPeriod(start, end)
            }
            return Decision.ProductionCompare(period, compare)
        }
        val limit = node.path("limit").takeIf { it.isIntegralNumber }?.asInt() ?: return Decision.Invalid
        return when (tool) {
            "defect_rate_top" -> if (limit in 1..20) Decision.DefectRateTop(period, limit) else Decision.Invalid
            "defect_top" -> if (limit in 1..10) Decision.DefectTop(period, limit) else Decision.Invalid
            else -> Decision.Invalid
        }
    }

    companion object {
        private val SAFE_AOI_CODE = Regex("[A-Za-z0-9_-]{1,32}")
    }
}
