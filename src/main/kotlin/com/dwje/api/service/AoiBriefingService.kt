package com.dwje.api.service

import com.dwje.api.common.util.DataField
import com.dwje.api.common.util.DateUtils
import com.dwje.api.common.util.MaskingSupport
import com.dwje.api.common.util.MenuId
import com.dwje.api.config.AppProperties
import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import kotlin.math.abs

/**
 * AOI 치수 AI 브리핑 — 숫자는 [AoiDimensionService] 가 만들고, sLLM 은 **문장과 조치 제안만** 쓴다.
 *
 * ## 응답의 세 층
 * 1. `ruleLines`  — 서버가 규칙으로 만든 사실 문장. 모델이 없어도 항상 나간다. 숫자는 여기서 나온다.
 * 2. `aiLines`    — 모델이 쓴 요약 문장. 문장마다 인용한 사실 키(`evidence`)를 달고, 서버가 [facts] 와 대조해
 *                   키가 없거나 값이 다른 문장은 버린다(`droppedCnt`).
 * 3. `actions`    — 모델의 조치 제안. **모두 「(추정)」 을 앞에 붙인다**(현업 결정 5). 근거 FAI 가 사실에 없으면 버린다.
 *
 * ## 말하지 않는 것
 * - FAI 이름은 없다(현업 결정 9) — 번호와 한계값까지만.
 * - 설명률이 낮아도 숨기지 않는다(결정 11). `ruleLines` 첫 줄에 늘 적는다.
 * - 측정 실패는 불량의 근거 유형 중 하나로 따로 말한다(결정 10).
 * - 권한이 없어 가려진 수량은 사실에 넣지 않으므로 모델도 말할 수 없다.
 */
@Service
class AoiBriefingService(
    private val dimensionService: AoiDimensionService,
    private val sllmClient: SllmClient,
    private val authorizationService: AuthorizationService,
    private val appProperties: AppProperties
) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val MODEL_NOT_READY = "MODEL_NOT_READY"
        const val MODEL_BUSY = "MODEL_BUSY"
        const val ESTIMATE_TAG = "(추정)"

        /** 근거 값 대조 허용 오차 — 상대 1% 또는 절대 0.01 */
        private const val REL_TOL = 0.01
        private const val ABS_TOL = 0.01

        val SCHEMA: Map<String, Any?> = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "summary" to mapOf(
                    "type" to "array", "minItems" to 1, "maxItems" to 5,
                    "items" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "text" to mapOf("type" to "string"),
                            "evidence" to mapOf(
                                "type" to "array", "minItems" to 1,
                                "items" to mapOf(
                                    "type" to "object",
                                    "properties" to mapOf("key" to mapOf("type" to "string"), "value" to mapOf("type" to "number")),
                                    "required" to listOf("key", "value")
                                )
                            )
                        ),
                        "required" to listOf("text", "evidence")
                    )
                ),
                "actions" to mapOf(
                    "type" to "array", "minItems" to 0, "maxItems" to 4,
                    "items" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "text" to mapOf("type" to "string"),
                            "fai" to mapOf("type" to "integer", "description" to "이 조치가 겨누는 FAI 번호. 사실 목록에 있는 번호만")
                        ),
                        "required" to listOf("text", "fai")
                    )
                )
            ),
            "required" to listOf("summary", "actions")
        )

        val SYSTEM = """
            당신은 덕우전자 AOI 치수 검사 결과를 품질 담당자에게 브리핑한다. 한국어로 쓴다.

            규칙
            - 아래 '사실' 목록에 있는 값만 쓴다. 없는 값·이름·원인을 만들지 않는다.
            - FAI 항목에는 이름이 없다. 반드시 'FAI10' 처럼 번호로 부르고, 필요하면 한계값(상한·하한)을 함께 적는다.
            - summary 는 2~4문장. 첫 문장에 집계 기간과 대상(작업장·설비)을 밝힌다.
              불량 중 어느 FAI 가 어느 방향(상한 초과·하한 미달)으로 얼마나 차지하는지, 설명률이 얼마인지,
              측정 실패가 몇 건인지, 직전 기간과 무엇이 달라졌는지를 말한다.
            - summary 각 문장은 인용한 사실의 key 를 evidence 에 담는다. key 는 사실 목록에 적힌 것을 그대로 옮기고
              value 는 그 값을 그대로 넣는다. 비울 수 없다.
            - 설명률이 낮아도 그대로 말한다. 설명률이 90% 미만일 때만 그 이유(확정 한계가 없는 항목의 이탈)를 한 구절 덧붙이고, 그 이상이면 이유를 붙이지 않는다.
            - actions 는 사실 목록의 상위 FAI 를 겨누는 점검·조치 제안이다. 0~3개. 각 항목의 fai 에는 그 FAI 번호를 넣는다.
              조치 문장은 '무엇을 점검·조정할지' 로 쓴다. 근거 없는 원인 단정은 하지 않는다.
            - 문장에 '(근거: …)' 같은 출처 표기를 쓰지 않는다.
        """.trimIndent()
    }

    /**
     * 브리핑 생성.
     *
     * 집계는 [AoiDimensionService] 캐시에서 다시 읽으므로 화면이 표를 먼저 그린 뒤 이 API 를 부르면 원천을 다시 읽지 않는다.
     */
    fun briefing(from: String?, to: String?, wcCd: String, eqptCd: String?): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.QC_AOI)
        val qtyAllowed = mask.check(DataField.QTY)
        val yieldAllowed = mask.check(DataField.YIELD)

        val (current, previous) = dimensionService.summaryFor(from, to, wcCd, eqptCd)
        val facts = factsOf(current, previous, qtyAllowed, yieldAllowed)
        val ruleLines = ruleLines(current, previous, qtyAllowed, yieldAllowed)

        val base = linkedMapOf<String, Any?>(
            "period" to mapOf("from" to current.from.format(DateUtils.DATE), "to" to current.to.format(DateUtils.DATE), "days" to current.days),
            "wcCd" to current.wcCd, "eqptCd" to current.eqptCd,
            "ruleLines" to ruleLines,
            "facts" to facts.map { (k, v) -> mapOf("key" to k, "value" to v) },
            "aiLines" to emptyList<Any>(), "actions" to emptyList<Any>(), "droppedCnt" to 0,
            "generatedAt" to LocalDateTime.now().format(DateUtils.DATETIME),
            "modelVer" to appProperties.ai.model
        )

        if (!sllmClient.isEnabled()) return (base + mapOf("status" to null, "reason" to MODEL_NOT_READY)) to mask

        val user = userMessage(current, previous, facts, qtyAllowed, yieldAllowed)
        return when (val r = sllmClient.chatJson(SYSTEM, user, SCHEMA)) {
            SllmResult.Busy -> (base + mapOf("status" to null, "reason" to MODEL_BUSY)) to mask
            SllmResult.Failed -> (base + mapOf("status" to null, "reason" to MODEL_NOT_READY)) to mask
            is SllmResult.Ok -> {
                val verified = verify(r.node, facts, current)
                (base + mapOf(
                    "status" to if (verified.lines.isEmpty() && verified.actions.isEmpty()) null else "OK",
                    "aiLines" to verified.lines, "actions" to verified.actions, "droppedCnt" to verified.dropped
                )) to mask
            }
        }
    }

    // ── 사실 목록 ─────────────────────────────────────────────────────────────

    /**
     * 모델과 검증기가 함께 보는 사실 — `key → 값`. 가려진 수량은 넣지 않는다.
     * 키 규칙: 전체 `measCnt` `failCnt` `failRate` `explainedRate` `singleCnt` `multiCnt` `unconfirmedCnt` `zeroCnt`,
     * FAI 별 `FAI10.violCnt` `FAI10.violSingleCnt` `FAI10.sharePct` `FAI10.violPct` `FAI10.exceedAvg`(단일 귀속 행 평균) `FAI10.usl` `FAI10.lsl` `FAI10.overCnt` `FAI10.underCnt`,
     * 직전 기간 `prev.failCnt` `prev.failRate` `prev.explainedRate` `delta.failRatePt`.
     */
    internal fun factsOf(cur: AoiDimensionService.Summary, prev: AoiDimensionService.Summary?, qty: Boolean, yield: Boolean): LinkedHashMap<String, Double> {
        val f = LinkedHashMap<String, Double>()
        val t = cur.total
        if (qty) {
            f["measCnt"] = t.measCnt.toDouble(); f["failCnt"] = t.failCnt.toDouble()
            f["singleCnt"] = t.singleCnt.toDouble(); f["multiCnt"] = t.multiCnt.toDouble()
            f["unconfirmedCnt"] = t.unconfirmedCnt.toDouble(); f["zeroCnt"] = t.zeroCnt.toDouble()
        }
        if (yield) { f["failRate"] = t.failRate; f["explainedRate"] = t.explainedRate }
        t.fais.take(appProperties.aoi.briefingTopFai).forEach { x ->
            val p = "FAI${x.fai}"
            x.usl?.let { f["$p.usl"] = it }; x.lsl?.let { f["$p.lsl"] = it }
            if (qty) {
                f["$p.violCnt"] = x.violCnt.toDouble(); f["$p.violSingleCnt"] = x.violSingleCnt.toDouble()
                f["$p.overCnt"] = x.overCnt.toDouble(); f["$p.underCnt"] = x.underCnt.toDouble()
                x.exceedAvgSingle?.let { f["$p.exceedAvg"] = round4(it) }
            }
            if (yield) { f["$p.sharePct"] = x.sharePct; f["$p.violPct"] = x.violPct }
        }
        if (prev != null) {
            if (qty) f["prev.failCnt"] = prev.total.failCnt.toDouble()
            if (yield) {
                f["prev.failRate"] = prev.total.failRate; f["prev.explainedRate"] = prev.total.explainedRate
                f["delta.failRatePt"] = round2(t.failRate - prev.total.failRate)
            }
        }
        return f
    }

    /** 규칙이 만드는 사실 문장 — 모델이 없어도 이것만으로 브리핑이 선다. */
    internal fun ruleLines(cur: AoiDimensionService.Summary, prev: AoiDimensionService.Summary?, qty: Boolean, yield: Boolean): List<String> {
        val t = cur.total
        val target = cur.eqptCd ?: "${cur.wcCd} 전체"
        val period = if (cur.from == cur.to) cur.from.format(DateUtils.DATE) else "${cur.from.format(DateUtils.DATE)}~${cur.to.format(DateUtils.DATE)}"
        val scale = t.resolution?.let { BigDecimal.valueOf(it).scale() } ?: 3
        val lines = mutableListOf<String>()

        lines += buildString {
            append("$period $target 측정 ")
            append(if (qty) "${fmt(t.measCnt)}건 중 불량 ${fmt(t.failCnt)}건" else "결과")
            if (yield) append("(불량률 ${t.failRate}%)")
            append(", 설명률 ")
            append(if (yield) "${t.explainedRate}%" else "—")
            append(".")
        }
        val top = t.fais.take(appProperties.aoi.briefingTopFai)
        if (top.isNotEmpty()) {
            lines += top.joinToString(", ", prefix = "원인 항목: ", postfix = ".") { x ->
                val dir = when {
                    x.overCnt > 0 && x.underCnt == 0L -> "상한 ${x.usl?.let { num(it, scale) }} 초과"
                    x.underCnt > 0 && x.overCnt == 0L -> "하한 ${x.lsl?.let { num(it, scale) }} 미달"
                    else -> "상한 ${x.usl?.let { num(it, scale) }}·하한 ${x.lsl?.let { num(it, scale) }} 이탈"
                }
                buildString {
                    append("FAI${x.fai} $dir")
                    if (qty) append(" ${fmt(x.violCnt)}건")
                    if (yield) append("(단일 귀속 ${x.sharePct}%)")
                    if (qty) x.exceedAvgSingle?.let { append(", 평균 ${if (x.overCnt >= x.underCnt) "+" else "-"}${num(it, scale)}") }
                }
            }
        }
        if (qty) {
            lines += "귀속: 단일 항목 ${fmt(t.singleCnt)}건 · 복합 ${fmt(t.multiCnt)}건 · 미확정 항목 이탈 ${fmt(t.unconfirmedCnt)}건 · 측정 실패 ${fmt(t.zeroCnt)}건."
        }
        if (prev != null && yield) {
            val d = round2(t.failRate - prev.total.failRate)
            lines += "직전 동일 기간(${prev.from.format(DateUtils.DATE)}~${prev.to.format(DateUtils.DATE)}) 불량률 ${prev.total.failRate}% 대비 ${if (d >= 0) "+" else ""}$d%p" +
                (prev.total.topFai?.let { p -> if (t.topFai != null && p.fai != t.topFai!!.fai) ", 1위 원인이 FAI${p.fai}에서 FAI${t.topFai!!.fai}로 바뀜" else "" } ?: "") + "."
        }
        return lines
    }

    private fun userMessage(cur: AoiDimensionService.Summary, prev: AoiDimensionService.Summary?, facts: Map<String, Double>, qty: Boolean, yield: Boolean): String = buildString {
        appendLine("대상: 작업장 ${cur.wcCd}" + (cur.eqptCd?.let { " 설비 $it" } ?: " 전체 설비 ${cur.equipments.size}대"))
        appendLine("기간: ${cur.from.format(DateUtils.DATE)} ~ ${cur.to.format(DateUtils.DATE)} (${cur.days}일)")
        prev?.let { appendLine("직전 동일 기간: ${it.from.format(DateUtils.DATE)} ~ ${it.to.format(DateUtils.DATE)}") }
        appendLine("한계 세트 근거: ${cur.total.limitBasis ?: "없음"}")
        appendLine()
        appendLine("사실 (key = 값). 문장에 쓴 값은 evidence 에 key 그대로 담는다.")
        facts.forEach { (k, v) -> appendLine("- $k = ${num(v, 4).trimEnd('0').trimEnd('.')}") }
        if (!qty) appendLine("- 수량은 제공되지 않음(권한). 건수를 말하지 않는다.")
        if (!yield) appendLine("- 비율은 제공되지 않음(권한). 비율을 말하지 않는다.")
        appendLine()
        appendLine("규칙이 만든 사실 문장(참고 — 되풀이하지 말고 뜻을 풀어 쓴다):")
        ruleLines(cur, prev, qty, yield).forEach { appendLine("- $it") }
        appendLine()
        appendLine("귀속 규칙: 확정 한계를 넘은 FAI 1개=단일, 2개 이상=복합, 0개=미확정 항목 이탈, FAI 값이 대부분 0=측정 실패. 설명률=(단일+복합)/불량.")
    }

    // ── 검증 ─────────────────────────────────────────────────────────────────

    internal data class Verified(val lines: List<Map<String, Any?>>, val actions: List<Map<String, Any?>>, val dropped: Int)

    /** 모델 문장의 근거 키·값을 사실과 대조하고, 조치 제안에 「(추정)」 을 붙인다. */
    internal fun verify(node: JsonNode, facts: Map<String, Double>, cur: AoiDimensionService.Summary): Verified {
        var dropped = 0
        val lines = node.path("summary").mapNotNull { item ->
            val text = item.path("text").asText("").trim()
            val evidence = item.path("evidence").map { e -> e.path("key").asText("") to e.path("value").asDouble(Double.NaN) }
            val bad = evidence.filter { (k, v) -> facts[k]?.let { !close(it, v) } ?: true }
            if (text.isBlank() || evidence.isEmpty() || bad.isNotEmpty()) {
                dropped++
                log.debug("AOI 브리핑 문장 탈락 : {} — 근거 불일치 {}", text.take(60), bad)
                null
            } else {
                mapOf("text" to text, "evidence" to evidence.map { (k, v) -> mapOf("key" to k, "value" to v) })
            }
        }
        val knownFai = cur.total.fais.take(appProperties.aoi.briefingTopFai).map { it.fai }.toSet()
        val actions = node.path("actions").mapNotNull { item ->
            val text = item.path("text").asText("").trim()
            val fai = item.path("fai").asInt(-1)
            if (text.isBlank() || fai !in knownFai) { dropped++; null } else {
                mapOf(
                    "text" to if (text.startsWith(ESTIMATE_TAG)) text else "$ESTIMATE_TAG $text",
                    "fai" to fai,
                    "estimate" to true
                )
            }
        }
        return Verified(lines, actions, dropped)
    }

    private fun close(expected: Double, actual: Double): Boolean =
        !actual.isNaN() && (abs(expected - actual) <= ABS_TOL || abs(expected - actual) <= abs(expected) * REL_TOL)

    private fun fmt(n: Long): String = String.format("%,d", n)
    private fun num(v: Double, scale: Int): String = BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP).toPlainString()
}
