package com.dwje.api.service

/**
 * sLLM 프롬프트와 JSON Schema — **한 곳에 모아 둔다**
 *
 * 지시문과 스키마가 갈라지면 한쪽만 고쳐진다. 실제로 "근거를 담아라" 를 지시문에만
 * 적었을 때 모델이 `evidence: []` 를 냈고, 스키마에 `minItems: 1` 을 걸어서야 담겼다.
 * **모양은 스키마로 강제하고, 지시문은 내용만 다룬다.**
 */
object AiPrompt {

    /**
     * 근거 한 건의 스키마.
     *
     * `kind` 를 `enum` 으로 닫는다 — 서버가 대조할 수 있는 종류만 받는다.
     * 열어 두면 모델이 "press_tonnage" 같은 것을 내고, 그러면 문장이 전부 탈락한다.
     */
    private fun evidenceItem(kinds: List<String>, required: List<String>) = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "kind" to mapOf(
                "type" to "string",
                "enum" to kinds,
                "description" to "qty·defect_rate 는 key=total, yield 는 key=공정코드, " +
                    "defect 는 key=불량코드, anomaly 는 key=설비코드, " +
                    "doc 는 key 에 참고 문서 목록에 적힌 숫자를 그대로 넣는다"
            ),
            "key" to mapOf("type" to "string"),
            "value" to mapOf(
                "type" to "number",
                "description" to "인용한 수치. 지표 근거에서만 쓴다"
            ),
            "quote" to mapOf(
                "type" to "string",
                "description" to "kind=doc 일 때만. 참고 문서 원문에서 한 문장을 그대로 발췌한다"
            )
        ),
        // 필수 항목은 절마다 다르다.
        //   지표 근거 : value 가 없으면 대조할 것이 없다
        //   문서 근거 : quote 가 없으면 대조할 것이 없다
        // required 에서 빼면 모델이 그 항목을 아예 내지 않는다 — 둘 다 실측으로 겪었다.
        // (value 를 뺐을 때 전 문장 "value 를 수치로 읽을 수 없음",
        //  quote 를 뺐을 때 전 처방 "quote 없음" 으로 탈락)
        "required" to required
    )

    /**
     * 문장 + 근거. `evidence` 는 비울 수 없다.
     *
     * 근거 종류를 절마다 다르게 준다 — 원인(contributions)에 문서를 허용하면
     * 모델이 조치 문장을 원인 자리에 넣어 두 절이 같은 내용으로 겹친다.
     * 실측으로 그렇게 나왔다. 지시문으로 부탁하지 말고 스키마로 막는다.
     */
    private fun lineItem(
        kinds: List<String>,
        required: List<String>,
        withProcess: Boolean = false
    ) = mapOf(
        "type" to "object",
        "properties" to buildMap {
            put("text", mapOf("type" to "string"))
            if (withProcess) {
                put(
                    "processId",
                    mapOf(
                        "type" to "string",
                        "description" to "이 조치가 어느 공정 것인지. 주어진 대상 목록의 공정 코드만 쓴다"
                    )
                )
            }
            put(
                "evidence",
                mapOf(
                    "type" to "array",
                    // 비우면 문장이 전부 탈락한다.
                    "minItems" to 1,
                    "items" to evidenceItem(kinds, required)
                )
            )
        },
        "required" to if (withProcess) listOf("text", "processId", "evidence") else listOf("text", "evidence")
    )

    /** 지표 근거 — 서버가 값을 다시 계산해 대조할 수 있는 종류 */
    private val METRIC_KINDS = listOf("qty", "defect_rate", "yield", "defect", "anomaly")

    /** 지표 근거의 필수 항목 — 값이 있어야 대조한다 */
    private val METRIC_REQUIRED = listOf("kind", "key", "value")

    /** 문서 근거 — 인용문을 원문과 대조한다 */
    private val DOC_KINDS = listOf("doc")

    /** 문서 근거의 필수 항목 — 인용문이 있어야 대조한다 */
    private val DOC_REQUIRED = listOf("kind", "key", "quote")

    /** 브리핑 스키마 */
    val BRIEFING_SCHEMA: Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "lines" to mapOf(
                "type" to "array", "minItems" to 1, "maxItems" to 6,
                "items" to lineItem(METRIC_KINDS, METRIC_REQUIRED)
            )
        ),
        "required" to listOf("lines")
    )

    /** 원인 분석·처방 스키마 — 기여 요인과 처방 모두 근거를 든다. */
    val CAUSE_SCHEMA: Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            // 조치는 문서 근거로만 — 「무엇을 할지」는 FACA 문서에서 나온다.
            // processId 로 어느 공정의 조치인지 표시한다. 대상이 여럿이라 묶어야 한다.
            "prescriptions" to mapOf(
                "type" to "array", "minItems" to 1, "maxItems" to 12,
                "items" to lineItem(DOC_KINDS, DOC_REQUIRED, withProcess = true)
            ),
            // 원인은 지표 근거로만 — 「왜 기준을 넘었는지」는 대상마다 준 지표에서 나온다.
            // 예전에는 서버가 고정 문장("…공정의 불량률이 기준을 넘었습니다")을 만들었다. 모델이 쓰고 서버가 대조한다.
            "contributions" to mapOf(
                "type" to "array", "minItems" to 1, "maxItems" to 12,
                "items" to lineItem(METRIC_KINDS, METRIC_REQUIRED, withProcess = true)
            )
        ),
        "required" to listOf("contributions", "prescriptions")
    )

    /**
     * 공통 지시문.
     *
     * 근거 표기를 문장 안에 쓰지 말라는 지시가 필요하다 — 모델이
     * "(근거: kind=qty, key=total)" 을 문장에 그대로 적어 넣는다.
     *
     * 다만 그 지시를 "근거를 내지 말라" 로 읽지 않게 두 문장을 분리해 적는다.
     * 실측으로 한 문장에 섞어 썼을 때 `evidence` 가 빈 배열로 왔다.
     */
    private val COMMON_RULES = """
        규칙
        - 주어진 지표에 없는 값은 만들지 않는다. 모르는 것은 말하지 않는다.
        - 각 문장은 그 문장이 인용한 수치를 evidence 배열에 반드시 담는다. 비울 수 없다.
        - evidence 의 kind·key 는 아래 지표에 **적힌 그대로** 옮긴다. 다른 kind 를 붙이지 않는다.
        - '(근거로 쓸 수 없음)' 이라 적힌 항목은 문장에 쓰지 않는다.
        - evidence 의 value 는 지표에 적힌 숫자를 **그대로** 넣는다. 단위를 바꾸지 않는다.
          퍼센트는 퍼센트 값 그대로다 — 1.95 이지 0.0195 가 아니다.
        - 문장 본문(text)에는 '(근거: kind=qty)' 같은 출처 표기를 쓰지 않는다. 근거는 evidence 에만 담는다.
        - 값이 제공되지 않은 항목은 언급하지 않는다. 추측하지 않는다.
        - 문장(text)에는 수치·비율·수량을 쓰지 않는다. 숫자는 evidence 에만 담는다.
          비교는 말로 한다 — '밑돈다' · '가장 크다'.
        - 지표에 비교 대상(전일·전주·목표)이 주어지지 않았으면 비교하지 않는다. '전일 대비' 같은 말을 쓰지 않는다.
        - 뜻이 없는 문장('일정 수준을 유지한다' · '지표로 나타난다')은 쓰지 않는다. 사실 하나를 분명히 말한다.
    """.trimIndent()

    /** 브리핑 지시문 */
    val BRIEFING_SYSTEM = """
        당신은 덕우전자 생산·품질 일일 브리핑을 쓴다. 한국어로 쓴다.

        $COMMON_RULES
        - **첫 문장에 집계 기간을 밝힌다.** 하루면 그 날짜를, 여러 날이면 그 기간을 말한다.
          기간을 밝히지 않으면 하루치인지 한 달치인지 읽는 사람이 알 수 없다.
        - 2~4문장으로 쓴다. 각 문장은 하나의 사실만 말한다.
    """.trimIndent()

    /** 원인 분석·처방 지시문 */
    val CAUSE_SYSTEM = """
        당신은 덕우전자 공정 불량의 원인을 짚고 조치를 권고한다. 한국어로 쓴다.

        $COMMON_RULES
        - 기간·집계 범위를 말하는 문장("…까지의 분석 결과입니다")은 쓰지 않는다. 기간은 화면이 따로 보여 준다.
        - contributions 에는 대상 공정마다 **왜 기준을 넘었는지**를 1~2문장으로 쓴다.
          그 공정의 「원인 지표」에 적힌 kind·key·value 만 근거로 든다(공정 수율, 최다 불량 설비 불량률).
          문장에 **공정 이름과 설비 이름을 적는다** — 어느 공정 이야기인지 문장만 보고 알 수 있어야 한다.
          공정마다 같은 문장을 되풀이하지 않는다.
        - prescriptions 에는 **무엇을 할지**만 쓴다. 문장은 행동으로 끝난다 — '…을 점검한다' · '…을 교체한다' · '…을 조정한다'.
          지표를 다시 설명하지 않는다. contributions 에 쓴 원인을 되풀이하지 않는다. 집계 결과를 요약하지 않는다.
        - 대상 공정마다 조치를 쓴다. processId 에는 주어진 대상 목록의 공정 코드를 그대로 넣는다.
        - 조치 문장은 **인용한 문서에 적힌 조치를 우리 말로 옮긴 것**이어야 한다.
          "조치가 필요합니다" 처럼 무엇을 할지 없는 문장은 쓰지 않는다.
        - 참고 문서가 없다고 적힌 공정은 조치를 쓰지 않는다. 억지로 만들지 않는다.
          참고 문서에 그 공정의 조치가 적혀 있지 않아도 쓰지 않는다 — '조치 사항이 문서에 없습니다' 같은 문장도 쓰지 않는다.
        - 문서를 근거로 들 때는 kind=doc 으로 하고, key 에는 참고 문서 목록의 각 항목 앞에 적힌
          숫자를 **그 숫자 그대로** 넣는다. 'chunkId' 라는 글자를 넣지 않는다.
          quote 에는 그 문서 원문에서 그대로 발췌한 문장을 담는다.
          quote 는 요약하거나 고쳐 쓰지 않는다. 원문 문자열과 한 글자도 다르면 안 된다.
        - quote 는 **한 문장, 150자 이내**로 짧게 발췌한다. 길게 옮기면 응답이 잘린다.
        - 설비·공정 코드는 주어진 것만 쓴다. 없는 코드를 만들지 않는다.
    """.trimIndent()

    /**
     * 모델에 넘길 지표 본문을 만든다.
     *
     * 가려진 항목은 값을 넣지 않고 **가려졌다고 적는다** — 비워 두면 모델이 추측한다.
     */
    fun userMessage(input: AiBriefingInput, extra: String? = null): String = buildString {
        // 하루면 날짜, 길면 "시작 ~ 종료" 가 들어온다. 이름을 "기간" 으로 두어야
        // 모델이 한 달치를 하루로 말하지 않는다.
        appendLine("집계 기간: ${input.date}")
        appendLine()
        appendLine("지표 (제공된 것만 쓸 수 있다)")
        // 근거로 쓸 수 있는 항목에는 kind·key 를 **줄마다** 적는다.
        // 절 머리에만 적으면 모델이 다른 kind 를 붙인다 — 실측으로 수율을 qty 로,
        // 불량 수량을 qty/불량코드 로 붙여 전부 VALUE_MISMATCH 로 탈락했다.
        line("총 생산 수량", input.totalQty, "EA", "qty", "total")
        line("전체 불량률", input.defectRate, "%", "defect_rate", "total")
        // 아래 셋은 대조할 kind 가 없다. 근거로 쓸 수 없으므로 그렇게 적는다.
        line("양품 수량", input.okQty, "EA", null, null)
        line("불량 수량", input.ngQty, "EA", null, null)
        line("수율", input.yieldRate, "%", null, null)
        line("계획 수량", input.planQty, "EA", null, null)
        line("계획 대비 달성률", input.achievementRate, "%", null, null)

        if (input.defectComposition.isNotEmpty()) {
            appendLine()
            appendLine("불량 유형")
            input.defectComposition.forEach {
                appendLine("- ${it.label ?: it.code}: ${it.qty} EA  (kind=defect, key=${it.code})")
            }
        }

        if (input.anomalyCandidates.isNotEmpty()) {
            appendLine()
            appendLine("설비별 불량률")
            input.anomalyCandidates.forEach {
                appendLine("- ${it.eqptNm ?: it.eqptCd}: ${it.defectRate}%  (kind=anomaly, key=${it.eqptCd})")
            }
        }

        if (input.maskedFields.isNotEmpty()) {
            appendLine()
            appendLine("아래 항목은 열람 권한이 없어 제공되지 않았다. 언급하지 않는다.")
            appendLine("- ${input.maskedFields.joinToString(", ")}")
        }

        if (!extra.isNullOrBlank()) {
            appendLine()
            append(extra)
        }
    }

    /** 값이 있을 때만 한 줄 적는다. 없으면 "제공되지 않음" 으로 남긴다. */
    private fun StringBuilder.line(
        name: String,
        value: Any?,
        unit: String,
        kind: String?,
        key: String?
    ) {
        if (value == null) {
            appendLine("- $name: 제공되지 않음")
            return
        }
        val ref = if (kind != null) "  (kind=$kind, key=$key)" else "  (근거로 쓸 수 없음)"
        appendLine("- $name: $value $unit$ref")
    }
}
