package com.dwje.api.service

import com.dwje.api.common.exception.BusinessException
import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.response.ErrorCode
import com.dwje.api.common.security.UserContext
import com.dwje.api.config.AppProperties
import com.dwje.api.model.request.LlmChatMessage
import com.dwje.api.model.request.LlmChatRequest
import com.dwje.api.repository.AiChatRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.InputStream
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * 사내 LLM 채팅 프록시 (`/api/ai/chat`)
 *
 * 덕우전자 전용 모델(`dwje-ax`, GPU 서버 vLLM 의 LoRA · OpenAI 호환 API)에 대화를 넘긴다.
 * 본문을 모아 내부 정보 노출을 검사하고, 화면과 질의 이력에 같은 안전한 텍스트를 쓴다.
 *
 * ## 모델 사용 규칙 (어기면 사내 규칙이 적용되지 않는다)
 * - **system 은 서버가 정한 것(`app.llm.system-prompt`, 학습 데이터의 문서 어시스턴트 원문)만 보낸다.**
 *   vLLM LoRA 에는 예전 Ollama 모델처럼 내장 지시문이 없다(2026-10 전환). 화면이 보낸 system 은 버린다 —
 *   사용자가 지시문을 바꿔 근거 밖 답·금지 항목을 끌어내지 못하게. 근거는 마지막 user 메시지 안에 넣는다.
 * - `temperature`·`top_p` 같은 샘플링 값은 보내지 않는다(모델 기본값).
 * - `reasoning_effort: "none"` 은 반드시 넣는다. 빼면 추론(thinking)부터 해 응답이 느려진다.
 *
 * ## 한 번에 한 건
 * LLM 서버는 동시에 한 건만 처리하고 나머지는 대기열에 쌓는다. 한 사람이 대기열을 채우지 못하게
 * IP 기준 분당 요청 수를 제한한다. 대기는 LLM 서버의 대기열에 맡긴다 — 이쪽에서 따로 세우지 않는다.
 */
@Service
class LlmChatProxyService(
    private val appProperties: AppProperties,
    private val objectMapper: ObjectMapper,
    private val aiChatRepository: AiChatRepository,
    private val sllmClient: SllmClient
) {

    data class ToolExchange(val callId: String, val name: String, val args: Map<String, Any?>, val result: String)

    private val log = LoggerFactory.getLogger(javaClass)

    private val http: HttpClient by lazy {
        HttpClient.newBuilder()
            // LLM 서버는 http 다. h2c 업그레이드를 시도하지 않는다.
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SEC))
            .build()
    }

    /** auto 도구 선택이 서버의 도구 파서 부재를 보고 json-schema 로 바뀌었는지 — 프로세스가 끝날 때까지 유지 */
    private val toolJsonMode = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 지금 도구 선택이 json-schema 방식인지 */
    fun toolJsonSchemaActive(): Boolean = appProperties.llm.toolMode == "json-schema" || (appProperties.llm.toolMode == "auto" && toolJsonMode.get())

    /** IP → 최근 1분 안의 요청 시각(ms) */
    private val hits = ConcurrentHashMap<String, ArrayDeque<Long>>()

    companion object {
        private const val CONNECT_TIMEOUT_SEC = 5L
        private const val HEALTH_TIMEOUT_SEC = 5L
        private const val WINDOW_MS = 60_000L
        private val ROLES = setOf("user", "assistant")

        /** json-schema 도구 선택에서 「도구 필요 없음」 */
        const val NO_TOOL = "none"

        /** 후속 질의 지시문 — 이 시스템이 실제로 답할 수 있는 범위 안에서만 묻게 한다 */
        private val FOLLOWUP_SYSTEM = """
            사용자가 방금 받은 답을 보고, 이어서 물어볼 만한 질문을 2~3개 쓴다. 한국어로 쓴다.
            - 이 시스템이 답할 수 있는 것: 기간별 생산량·불량·불량률·수율(공장 전체·공정별) 비교, 사내 FACA·품질 문서 검색.
            - 각 질문은 40자 이내의 한 문장으로 쓴다.
            - 답에 없는 수치나 코드를 질문에 넣지 않는다. 방금 한 질문을 되풀이하지 않는다.
        """.trimIndent()

        private val FOLLOWUP_SCHEMA: Map<String, Any?> = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "questions" to mapOf(
                    "type" to "array", "minItems" to 1, "maxItems" to 3,
                    "items" to mapOf("type" to "string")
                )
            ),
            "required" to listOf("questions")
        )
    }

    /**
     * IP 기준 분당 요청 수를 센다. 넘으면 [ErrorCode.LLM_RATE_LIMITED].
     *
     * 슬라이딩 윈도(최근 60초)다. 고정 분 단위로 세면 59초·61초에 몰아서 두 배를 보낼 수 있다.
     */
    fun checkRate(ip: String) {
        val now = System.currentTimeMillis()
        val limit = appProperties.llm.ratePerMinute
        val q = hits.computeIfAbsent(ip) { ArrayDeque() }
        val allowed = synchronized(q) {
            while (q.isNotEmpty() && now - q.peekFirst() >= WINDOW_MS) q.pollFirst()
            if (q.size >= limit) false else { q.addLast(now); true }
        }
        // 오래 조용한 IP 는 지운다 — 맵이 끝없이 자라지 않게.
        if (hits.size > 1000) hits.entries.removeIf { (_, d) -> synchronized(d) { d.isEmpty() || now - d.peekLast() >= WINDOW_MS } }
        if (!allowed) {
            log.info("LLM 채팅 요청 수 제한 : ip={} limit={}/분", ip, limit)
            throw BusinessException(ErrorCode.LLM_RATE_LIMITED, ErrorCode.LLM_RATE_LIMITED.defaultMessage)
        }
    }

    /**
     * 모델에 보낼 메시지를 만든다.
     *
     * 1. `system` 등 user·assistant 가 아닌 역할과 빈 메시지를 버린다
     * 2. 최근 [com.dwje.api.config.LlmProxyProperties.maxTurns] 턴(user+assistant 쌍)만 남긴다
     * 3. 마지막 user 메시지를 `[지시](오늘 날짜) · [근거] · [질문]` 으로 감싼다(근거가 없으면 [근거] 없이)
     * 4. 전체 길이가 상한을 넘으면 **오래된 것부터** 뺀다. 마지막 질문 하나로도 넘으면 거절한다
     */
    fun buildMessages(request: LlmChatRequest, general: Boolean = false): List<Map<String, String>> {
        val cfg = appProperties.llm

        val kept = request.messages.orEmpty()
            .filter { it.role in ROLES && !it.content.isNullOrBlank() }
            .takeLast(cfg.maxTurns * 2)
            .toMutableList()

        val lastUser = kept.indexOfLast { it.role == "user" }
        if (lastUser < 0) throw InvalidParameterException("질문을 입력해 주세요.", "messages")
        // 질문 뒤에 남은 assistant 는 모델이 이어 쓸 문장이 아니다 — 질문으로 끝나게 자른다.
        while (kept.size > lastUser + 1) kept.removeAt(kept.size - 1)

        // 모델은 오늘 날짜를 모른다. 없으면 "지난달" 을 2024-05 처럼 채운다(LLM 담당 실측, 2026-09-23).
        // [지시] 는 모델 내장 규칙 8번이 형식 지시로 따른다 — system 이 아니라 user 메시지 안에 둔다.
        val today = "[지시]\n오늘은 ${java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))}이다."
        val context = if (general) "" else request.context?.trim().orEmpty()
        val q = kept[lastUser].content!!.trim()
        val greeting = Regex("^(안녕(?:하세요|하십니까)?|하이|hi|hello|고마워(?:요)?|감사(?:합니다|해요)?)[!?.~ ]*$", RegexOption.IGNORE_CASE).matches(q)
        val instruction = if (greeting && context.isEmpty()) "$today\n이 질문은 사실 확인이 필요 없는 일상 인사다. 짧고 자연스럽게 답하라." else today
        val effectiveContext = if (greeting && context.isEmpty())
            "일상 인사·감사에는 짧고 정중하게 응답할 수 있다. 생산·품질 사실은 언급하지 않는다." else context
        kept[lastUser] = LlmChatMessage(
            "user",
            if (general) q else if (effectiveContext.isNotEmpty()) "$instruction\n\n[근거]\n$effectiveContext\n\n[질문]\n$q" else "$instruction\n\n[질문]\n$q"
        )

        var total = kept.sumOf { it.content!!.length }
        while (total > cfg.maxInputChars && kept.size > 1) {
            total -= kept.removeAt(0).content!!.length
        }
        if (total > cfg.maxInputChars) {
            throw InvalidParameterException(
                "질문과 근거 문서가 너무 깁니다. 합쳐서 ${cfg.maxInputChars}자 이내로 줄여 주세요. (현재 ${total}자)",
                if (context.isNotEmpty()) "context" else "messages"
            )
        }
        // 첫 메시지가 assistant 면 모델이 앞 맥락 없이 답을 이어받는 모양이 된다. user 부터 시작하게 한다.
        while (kept.size > 1 && kept.first().role != "user") kept.removeAt(0)

        return kept.map { mapOf("role" to it.role!!, "content" to it.content!!) }
    }

    /**
     * LLM 서버에 스트리밍 요청을 보내고 응답 본문 스트림을 연다.
     *
     * 헤더가 오기 전의 실패(연결 거부·시간 초과·오류 응답)는 여기서 [BusinessException] 으로 바꾼다 —
     * 아직 화면에 아무것도 보내지 않았으므로 상태 코드로 사유를 알릴 수 있다.
     * 스트림을 연 뒤의 끊김은 호출한 쪽이 받은 데까지만 흘려보낸다.
     */
    fun open(messages: List<Map<String, String>>, exchange: ToolExchange? = null, general: Boolean = false): InputStream {
        val cfg = appProperties.llm
        val upstreamMessages: List<Map<String, Any?>> = if (exchange == null) messages else messages + listOf(
            mapOf("role" to "assistant", "content" to null, "tool_calls" to listOf(mapOf(
                "id" to exchange.callId, "type" to "function", "function" to mapOf(
                    "name" to exchange.name, "arguments" to objectMapper.writeValueAsString(exchange.args))))),
            mapOf("role" to "tool", "tool_call_id" to exchange.callId, "content" to exchange.result)
        )
        // 서버가 정한 system 을 첫 메시지로 (화면이 보낸 system 은 buildMessages 에서 이미 버렸다)
        val systemPrompt = (if (general) cfg.generalSystemPrompt else cfg.systemPrompt).trim()
        val withSystem = if (systemPrompt.isEmpty() || upstreamMessages.firstOrNull()?.get("role") == "system") upstreamMessages
        else listOf(mapOf("role" to "system", "content" to systemPrompt)) + upstreamMessages
        val body = mapOf(
            "model" to if (general) cfg.generalModel else cfg.model,
            "messages" to withSystem,
            "stream" to true,
            "max_tokens" to 512,
            "reasoning_effort" to "none",
            // 마지막 조각에 usage(토큰 수)를 받는다 — 질의 이력에 남긴다(V71). 화면에는 이 조각을 그대로 보내지 않는다
            "stream_options" to mapOf("include_usage" to true),
            "dwje" to mapOf("rag" to false, "tools" to false)
        ) + cfg.sampling.fields() + mapOf("temperature" to if (general) 0.2 else 0.0)

        val request = HttpRequest.newBuilder()
            .uri(URI.create("${cfg.baseUrl.trimEnd('/')}/v1/chat/completions"))
            // 첫 응답(헤더)까지의 제한. 모델 적재(약 20초)와 대기열이 여기 들어간다.
            .timeout(Duration.ofMillis(cfg.timeoutMs))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
            .build()

        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (e: HttpTimeoutException) {
            log.warn("LLM 서버 응답 시간 초과 : {}ms", cfg.timeoutMs)
            throw BusinessException(ErrorCode.LLM_TIMEOUT, ErrorCode.LLM_TIMEOUT.defaultMessage)
        } catch (e: ConnectException) {
            log.warn("LLM 서버 연결 실패 : {}", e.javaClass.simpleName)
            throw BusinessException(ErrorCode.LLM_UNAVAILABLE, ErrorCode.LLM_UNAVAILABLE.defaultMessage)
        } catch (e: java.io.IOException) {
            log.warn("LLM 서버 호출 실패 : {}", e.javaClass.simpleName)
            throw BusinessException(ErrorCode.LLM_UNAVAILABLE, ErrorCode.LLM_UNAVAILABLE.defaultMessage)
        }

        if (response.statusCode() != 200) {
            response.body().close()
            log.warn("LLM 서버 응답 코드 {}", response.statusCode())
            val errorCode = if (response.statusCode() == 401 || response.statusCode() == 403) {
                ErrorCode.LLM_UPSTREAM_REJECTED
            } else {
                ErrorCode.LLM_UNAVAILABLE
            }
            throw BusinessException(errorCode, errorCode.defaultMessage)
        }
        return response.body()
    }

    /**
     * 1차 LLM 판단 — 도구 하나를 고르게 하고, 결과는 표준 OpenAI `message`(`tool_calls[0].function.{name, arguments}`) 모양으로 돌려준다.
     * 도구가 필요 없으면 `tool_calls` 없이 `content` 만 있는 message 다([AiQuestionPlanner] 가 Other 로 본다). 인자 검증은 서버가 한다.
     *
     * 방식은 `app.llm.tool-mode` 다. auto 는 native 로 시도하고, 서버가 도구 파서가 없다는 400 을 주면 json-schema 로 다시 부르고
     * 그 뒤로는 json-schema 만 쓴다(GPU 서버에 `--tool-call-parser` 를 켜면 native 그대로).
     */
    fun chooseTool(question: String, tools: List<Map<String, Any?>>, today: java.time.LocalDate): JsonNode? {
        val instruction = "오늘은 $today 이다(Asia/Seoul). 사용자의 의도를 먼저 파악해 필요한 DB 도구 하나를 선택하라. 실적·생산·불량·불량률을 묻는 질문은 문서검색/일반대화 도구로 보내지 말고 관련 DB 도구를 선택한다. 날짜는 교대 업무일 YYYY-MM-DD이며 양 끝 업무일을 포함한다. 연도 없는 월일은 종료 업무일이 오늘을 넘지 않는 가장 최근 유효 연도로 해석한다.\n" +
                    "도구 선택 기준: 인사·일상 대화·일반 지식은 general_chat을 선택한다. 사내 규정·문서 질문은 DB 도구 없이 문서 검색 대상으로 남긴다. 이전 업무 질문의 후속 조회는 일반 대화로 분류하지 않는다. 공장 전체 생산량·불량 건수·불량률과 기간 비교 또는 변화(%p 포함)는 production_period_compare를 선택하고, 불량률 변화는 조회 기간과 직전 비교 기간을 모두 채운다. '지난 일주일' 비교는 MES 최신 실적일을 끝 날짜로 최근 7개 업무일과 그 직전 7개 업무일을 비교한다. 제품별 불량률 순위는 defect_rate_top, 불량 유형 수량 순위는 defect_top을 선택한다. 후속 질문에 새 기간이 없고 [직전 조회 기간]이 제공되면 그 기간을 재사용한다.\n[질문]\n$question"
        if (toolJsonSchemaActive()) return chooseToolJson(instruction, tools)
        val (status, body) = callTool(nativeToolBody(instruction, tools)) ?: return null
        if (status == 200) return runCatching { objectMapper.readTree(body).path("choices").path(0).path("message") }.getOrNull()
        if (appProperties.llm.toolMode == "auto" && status == 400 && (body.contains("tool-call-parser") || body.contains("enable-auto-tool-choice"))) {
            if (toolJsonMode.compareAndSet(false, true)) {
                log.info("LLM 서버에 도구 파서가 없어 도구 선택을 json-schema 방식으로 바꿉니다(프로세스가 끝날 때까지)")
            }
            return chooseToolJson(instruction, tools)
        }
        log.warn("LLM 도구 판단 실패 status={}", status)
        return null
    }

    private fun toolMessages(content: String): List<Map<String, Any?>> {
        val cfg = appProperties.llm
        val system = cfg.systemPrompt.trim().takeIf { cfg.toolSystemPrompt && it.isNotEmpty() }
        return listOfNotNull(system?.let { mapOf("role" to "system", "content" to it) }, mapOf("role" to "user", "content" to content))
    }

    private fun nativeToolBody(instruction: String, tools: List<Map<String, Any?>>): Map<String, Any?> = mapOf(
        "model" to appProperties.llm.generalModel,
        "stream" to false,
            "max_tokens" to 512,
        "reasoning_effort" to "none",
        "temperature" to appProperties.llm.toolTemperature,
        "messages" to toolMessages(instruction),
        "tools" to tools.map { tool -> mapOf("type" to "function", "function" to mapOf(
            "name" to tool["name"], "description" to tool["description"], "parameters" to tool["inputSchema"])) },
        "tool_choice" to "auto",
        "dwje" to mapOf("rag" to false)
    )

    /** 도구 목록을 지시문에 넣고 `{name, arguments}` 를 스키마로 받는다 — 서버에 도구 파서가 없을 때 */
    internal fun jsonToolBody(instruction: String, tools: List<Map<String, Any?>>): Map<String, Any?> {
        val catalog = tools.map { mapOf("name" to it["name"], "description" to it["description"], "arguments" to it["inputSchema"]) }
        val content = instruction + "\n\n[도구 목록]\n" + objectMapper.writeValueAsString(catalog) +
            "\n\n위 도구 중 하나를 골라 name 과 arguments(그 도구의 arguments 스키마를 따른다)로 답하라. " +
            "사내 문서·규정 질문이면 name 을 \"$NO_TOOL\", arguments 를 {} 로 답하라."
        return mapOf(
            "model" to appProperties.llm.generalModel,
            "stream" to false,
            "max_tokens" to 512,
            "reasoning_effort" to "none",
            "temperature" to appProperties.llm.toolTemperature,
            "messages" to toolMessages(content),
            "response_format" to mapOf("type" to "json_schema", "json_schema" to mapOf("name" to "tool_choice", "schema" to toolChoiceSchema(tools))),
            "dwje" to mapOf("rag" to false)
        )
    }

    /**
     * `{name, arguments}` 스키마 — 도구마다 한 갈래(`anyOf`)로 묶어 name 에 맞는 arguments 스키마(required 포함)를 강제한다.
     * arguments 를 그냥 object 로 두면 「9/20 불량 유형 top 3」 에서 to 를 빼먹는 일이 15회 중 5회였다(2026-10-02).
     */
    internal fun toolChoiceSchema(tools: List<Map<String, Any?>>): Map<String, Any?> {
        fun branch(name: String, args: Any?) = mapOf(
            "type" to "object",
            "properties" to mapOf("name" to mapOf("type" to "string", "enum" to listOf(name)), "arguments" to args),
            "required" to listOf("name", "arguments")
        )
        val none = branch(NO_TOOL, mapOf("type" to "object", "properties" to emptyMap<String, Any>()))
        return mapOf("anyOf" to tools.mapNotNull { t -> (t["name"] as String?)?.let { branch(it, t["inputSchema"] ?: mapOf("type" to "object")) } } + none)
    }

    private fun chooseToolJson(instruction: String, tools: List<Map<String, Any?>>): JsonNode? {
        val (status, body) = callTool(jsonToolBody(instruction, tools)) ?: return null
        if (status != 200) { log.warn("LLM 도구 판단(json-schema) 실패 status={}", status); return null }
        val content = runCatching { objectMapper.readTree(body).path("choices").path(0).path("message").path("content").asText("") }.getOrNull()
            ?: return null
        return toolMessageOf(content)
    }

    /** json-schema 응답 `{name, arguments}` → native 와 같은 message 모양. 도구 없음이면 content 만 있는 message */
    internal fun toolMessageOf(content: String): JsonNode? {
        val picked = runCatching { objectMapper.readTree(content) }.getOrNull()?.takeIf { it.isObject } ?: return null
        val name = picked.path("name").asText("")
        val message = objectMapper.createObjectNode()
        if (name.isEmpty() || name == NO_TOOL) { message.put("content", NO_TOOL); return message }
        val call = message.putArray("tool_calls").addObject()
        call.put("id", "call_json_1"); call.put("type", "function")
        call.putObject("function").put("name", name)
            .put("arguments", objectMapper.writeValueAsString(picked.path("arguments").takeIf { it.isObject } ?: objectMapper.createObjectNode()))
        return message
    }

    /** 도구 판단 호출 — (상태 코드, 본문). 연결 실패면 null */
    private fun callTool(body: Map<String, Any?>): Pair<Int, String>? {
        val cfg = appProperties.llm
        val request = HttpRequest.newBuilder()
            .uri(URI.create("${cfg.baseUrl.trimEnd('/')}/v1/chat/completions"))
            .timeout(Duration.ofMillis(cfg.timeoutMs))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
            .build()
        return runCatching { http.send(request, HttpResponse.BodyHandlers.ofString()).let { it.statusCode() to it.body() } }
            .onFailure { log.warn("LLM 도구 판단 실패 type={}", it.javaClass.simpleName) }.getOrNull()
    }

    /**
     * 헬스체크 — `/v1/models` 에 설정한 모델이 올라와 있는지.
     *
     * @return `ok` · `model`(서버가 알려 준 태그, 없으면 null)
     */
    fun health(): Map<String, Any?> {
        val cfg = appProperties.llm
        val request = HttpRequest.newBuilder()
            .uri(URI.create("${cfg.baseUrl.trimEnd('/')}/v1/models"))
            .timeout(Duration.ofSeconds(HEALTH_TIMEOUT_SEC))
            .GET()
            .build()

        val response = runCatching { http.send(request, HttpResponse.BodyHandlers.ofString()) }
            .onFailure { log.warn("LLM 헬스체크 실패 : {}", it.javaClass.simpleName) }
            .getOrNull()
        if (response == null || response.statusCode() != 200) return mapOf("ok" to false, "model" to null)

        val ids = runCatching { objectMapper.readTree(response.body()).path("data").map { it.path("id").asText() } }
            .getOrDefault(emptyList())
        // 태그 없이 적은 설정(dwje-ax)은 서버에서 dwje-ax:latest 로 보인다.
        val found = ids.firstOrNull { it == cfg.model || it == "${cfg.model}:latest" }
        return mapOf("ok" to (found != null), "model" to found)
    }

    /**
     * 후속 질의 — 답을 본 사내 LLM 이 쓴다. 예전의 의도별 고정 문장(`buildFollowups`)을 대신한다.
     *
     * 모델이 없거나 바쁘면 빈 목록이다 — 고정 문장으로 채우지 않는다.
     * 같은 질문·답이면 [SllmClient] 캐시로 다시 부르지 않는다.
     */
    fun followups(question: String, answer: String): Map<String, Any?> {
        val user = "[방금 한 질문]\n${question.trim()}\n\n[받은 답]\n${answer.trim().take(3000)}"
        val result = sllmClient.chatJson(FOLLOWUP_SYSTEM, user, FOLLOWUP_SCHEMA)
        val questions = (result as? SllmResult.Ok)?.node?.path("questions")
            ?.mapNotNull { it.asText(null)?.trim()?.takeIf { q -> q.isNotEmpty() && q != question.trim() } }
            ?.distinct()?.take(3)
            .orEmpty()
        return mapOf(
            "questions" to questions,
            "reason" to when (result) {
                is SllmResult.Ok -> null
                SllmResult.Busy -> "MODEL_BUSY"
                SllmResult.Failed -> "MODEL_NOT_READY"
            }
        )
    }

    /**
     * 받은 답을 질의 이력에 저장한다. 실패해도 채팅은 이미 끝났으므로 로그만 남긴다.
     *
     * @param messageId `/ai/chat/ask` 가 준 이력 ID. 없으면 저장하지 않는다
     */
    fun saveAnswer(messageId: Long?, answer: String, elapsedMs: Long, meta: AiChatRepository.LlmMeta? = null) {
        if (messageId == null) return
        val userId = UserContext.currentOrNull()?.userId ?: return
        runCatching { aiChatRepository.updateLlmAnswer(messageId, userId, AiResponseSanitizer.publicText(answer)!!, elapsedMs.toInt(), meta) }
            .onSuccess { if (it == 0) log.warn("LLM 답 저장 대상 없음 : messageId={} user={}", messageId, userId) }
            .onFailure { log.warn("LLM 답 저장 실패 : messageId={} {}", messageId, it.toString()) }
    }

    fun publicAnswer(answer: String): String = AiResponseSanitizer.publicText(answer) ?: ""

    fun completionEvent(answer: String, done: Boolean): String =
        "data: ${objectMapper.writeValueAsString(mapOf("choices" to listOf(mapOf("index" to 0,
            "delta" to mapOf("content" to answer), "finish_reason" to null))))}\n\n" + if (done) "data: [DONE]\n\n" else ""

    /**
     * SSE 바이트를 흘려보내면서 `choices[0].delta.content` 만 모은다.
     *
     * 조각 경계가 줄 중간·UTF-8 글자 중간에 걸릴 수 있어 **바이트로 줄을 모은 뒤** 한 줄씩 해석한다.
     */
    inner class StreamTap {
        private val line = java.io.ByteArrayOutputStream()
        private val text = StringBuilder()

        /** 모델이 `[DONE]` 을 보냈는지 — 없이 끝나면 중간에 끊긴 것이다 */
        var done = false
            private set

        fun feed(buf: ByteArray, n: Int) {
            for (i in 0 until n) {
                val b = buf[i]
                if (b == '\n'.code.toByte()) flushLine() else line.write(b.toInt())
            }
        }

        fun text(): String = text.toString()

        // LLM 응답 메타(V71) — 조각마다 오는 model · id, 끝 조각의 finish_reason, include_usage 마지막 조각의 usage
        private var model: String? = null
        private var requestId: String? = null
        private var finishReason: String? = null
        private var usage: JsonNode? = null

        /** 받은 메타 + 호출 시간. 아무것도 못 받았으면(연결 직후 끊김 등) 시간만 */
        fun meta(elapsedMs: Long): com.dwje.api.repository.AiChatRepository.LlmMeta =
            com.dwje.api.repository.AiChatRepository.LlmMeta(
                model = model, requestId = requestId, finishReason = finishReason,
                promptTokens = usage?.path("prompt_tokens")?.takeIf { it.isIntegralNumber }?.asInt(),
                completionTokens = usage?.path("completion_tokens")?.takeIf { it.isIntegralNumber }?.asInt(),
                totalTokens = usage?.path("total_tokens")?.takeIf { it.isIntegralNumber }?.asInt(),
                elapsedMs = elapsedMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            )

        private fun flushLine() {
            val s = line.toString(Charsets.UTF_8).trim()
            line.reset()
            if (!s.startsWith("data:")) return
            val data = s.removePrefix("data:").trim()
            if (data == "[DONE]") { done = true; return }
            val node = runCatching { objectMapper.readTree(data) }.getOrNull() ?: return
            node.path("model").takeIf { it.isTextual }?.let { model = it.asText() }
            node.path("id").takeIf { it.isTextual }?.let { requestId = it.asText() }
            node.path("usage").takeIf { it.isObject }?.let { usage = it }
            val choice = node.path("choices").path(0)
            choice.path("finish_reason").takeIf { it.isTextual }?.let { finishReason = it.asText() }
            choice.path("delta").path("content").takeIf { it.isTextual }?.let { text.append(it.asText()) }
        }
    }
}
