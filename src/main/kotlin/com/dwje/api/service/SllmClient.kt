package com.dwje.api.service

import com.dwje.api.config.AppProperties
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * sLLM 호출 클라이언트 — 사내 LLM(dwje-ax) OpenAI 호환 `/v1/chat/completions` 또는 로컬 Ollama `/api/chat`
 *
 * 형식은 `app.ai.provider` 로 고른다(기본 `openai`). openai 형식에서는 system 을 보내지 않는다 —
 * 지시문은 user 메시지의 `[지시]` 블록으로 옮긴다([openAiUserMessage]).
 *
 * ## 의존성을 더하지 않는다
 * `java.net.http.HttpClient` 는 JDK 표준(Java 11+)이고 이 프로젝트는 JDK 21 이다.
 * `WebClient` 를 쓰려면 `spring-boot-starter-webflux` 를 넣어야 하는데, 이 앱은
 * 서블릿 스택이라 리액티브 스택을 함께 올릴 이유가 없다.
 *
 * ## 이 모델에서 실측으로 걸린 것 (gemma4:e4b-dwje)
 * 1. **`message.thinking` 이 따로 온다** (1,700~1,900자). 파싱해야 하는 것은
 *    `message.content` 다. `thinking` 을 JSON 으로 읽으면 깨진다.
 * 2. **`num_predict` 가 작으면 `content` 가 빈 문자열로 온다.** 추론에 토큰을
 *    먼저 쓰기 때문이다. 300 에서 재현했고 1,200 에서 정상이었다.
 * 3. **`done_reason` 이 `stop` 이 아니면 잘린 응답**이다. 그대로 파싱하면
 *    깨진 JSON 이거나, 더 나쁘게는 문장이 중간에 끊긴 채 통과한다.
 * 4. 2~3문장에 약 28초다.
 *
 * ## 스키마는 말로 부탁하지 않는다
 * `format` 에 JSON Schema 를 주면 모양이 강제된다. 특히 `evidence` 에
 * `minItems: 1` 을 걸어야 한다 — 프롬프트로 "근거를 담아라" 라고만 하면
 * **빈 배열로 온다.** 실측으로 확인했다(그때는 모든 문장이 근거 없음으로 탈락).
 *
 * 다만 모양이 맞아도 값의 진위는 별개다. 그래서 [AiEvidenceVerifier] 가 뒤에 선다.
 */
@Service
class SllmClient(
    private val appProperties: AppProperties,
    private val objectMapper: ObjectMapper
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val http: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SEC))
            .build()
    }

    companion object {
        private const val CONNECT_TIMEOUT_SEC = 5L

        /** 지시+근거 글자 수 상한(약 1.8자/토큰 · 출력 1,000~1,500토큰 여유) */
        private const val PROMPT_CHAR_BUDGET = 11_000
    }

    /**
     * 모델 호출을 **한 줄로 세운다.**
     *
     * 로컬 서빙(Ollama)은 한 번에 하나씩 처리한다. 두 요청을 동시에 던지면 뒤엣것이
     * 앞엣것을 기다렸다 시작해, 둘을 합한 시간이 제한에 닿아 **둘 다 실패**한다.
     * 실측: 따로 부르면 88초·98초로 되는데 동시에 부르면 원인 분석이 통째로 실패했다.
     *
     * 화면 하나만 순차로 바꿔서는 부족하다 — 사용자가 두 명이거나 배치가 겹치면 같은 일이 난다.
     * 서버에서 세워 두면 뒤엣것은 기다렸다 제 시간을 온전히 쓰고, 기다리다 못 하면
     * **빨리 포기해 사유를 남긴다.** 240초를 붙들고 있다가 둘 다 죽는 것보다 낫다.
     *
     * GPU 서버로 옮겨 동시 처리가 되면 허용 개수를 늘리면 된다.
     */
    private val gate = java.util.concurrent.Semaphore(1, true)

    /**
     * 응답 캐시 — 키는 모델·지시문·입력·스키마 전체의 SHA-256.
     *
     * 입력에 권한 마스킹이 이미 반영돼 있으므로 가려지는 항목이 다른 사용자는 키가 달라 서로 섞이지 않는다.
     * 캐시하는 것은 **모델 응답**이다. 근거 대조(AiEvidenceVerifier)는 호출한 쪽이 매번 사용자 권한으로 다시 한다.
     */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, JsonNode>>()

    private fun cacheKey(vararg parts: Any?): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(objectMapper.writeValueAsBytes(it)); md.update(0) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun cached(key: String): JsonNode? {
        val hit = cache[key] ?: return null
        if (hit.first < System.currentTimeMillis()) { cache.remove(key); return null }
        return hit.second
    }

    private fun remember(key: String, node: JsonNode) {
        val ttl = appProperties.ai.cacheTtlSec
        if (ttl <= 0) return
        val now = System.currentTimeMillis()
        if (cache.size > 500) cache.entries.removeIf { it.value.first < now }
        cache[key] = (now + ttl * 1000) to node
    }

    /**
     * 질의 문장을 임베딩한다. (`/api/embed`)
     *
     * `vec.fn_search_chunk` 가 질의 벡터를 요구한다. 저장된 임베딩과 **같은 모델·차원**
     * 이어야 한다 — 저장분은 1024차원(`embed_model_id = 1`)이고 `bge-m3` 출력도
     * 1024차원이라 맞는다. 다른 모델로 임베딩하면 유사도가 뜻을 잃는다.
     *
     * @return 임베딩 벡터. 실패하면 `null`
     */
    fun embed(text: String): List<Double>? {
        val cfg = appProperties.ai

        val request = HttpRequest.newBuilder()
            .uri(URI.create("${cfg.embedBaseUrl.ifBlank { cfg.baseUrl }.trimEnd('/')}/api/embed"))
            .timeout(Duration.ofSeconds(cfg.timeoutSec))
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    objectMapper.writeValueAsString(
                        mapOf("model" to cfg.embedModel, "input" to text)
                    )
                )
            )
            .build()

        val response = runCatching { http.send(request, HttpResponse.BodyHandlers.ofString()) }
            .onFailure { log.warn("임베딩 호출 실패 : {}", it.toString()) }
            .getOrNull() ?: return null

        if (response.statusCode() != 200) {
            log.warn("임베딩 응답 코드 {}", response.statusCode())
            return null
        }

        val root = runCatching { objectMapper.readTree(response.body()) }.getOrNull() ?: return null
        val arr = root.path("embeddings").firstOrNull() ?: root.path("embedding")
        if (!arr.isArray || arr.isEmpty) {
            log.warn("임베딩 응답에 벡터가 없습니다 : {}", response.body().take(200))
            return null
        }
        return arr.map { it.asDouble() }
    }

    /** 모델 호출을 쓸 수 있는 상태인지. 끄면 두 AI 엔드포인트가 `MODEL_NOT_READY` 를 낸다. */
    fun isEnabled(): Boolean = appProperties.ai.enabled

    /**
     * 스키마를 강제해 JSON 한 건을 받는다.
     *
     * @param system 지시문
     * @param user   지표·근거를 담은 입력
     * @param schema `format` 에 넣을 JSON Schema
     * @return 파싱 결과 또는 실패 사유. 값을 지어내지 않는다
     */
    fun chatJson(system: String, user: String, schema: Map<String, Any?>): SllmResult {
        val cfg = appProperties.ai
        val openai = cfg.provider == "openai"

        val body = if (openai) {
            // system 을 보내면 모델 내장 지시문(근거 기반 답변·[n] 표기·단가 금지)이 통째로 대체된다.
            // 지시문은 user 메시지 맨 앞에 넣고, 모양은 response_format 으로 강제한다. 샘플링 값은 보내지 않는다.
            mapOf(
                "model" to cfg.model,
                "stream" to false,
                "reasoning_effort" to "none",
                "max_tokens" to cfg.numPredict,
                "response_format" to mapOf(
                    "type" to "json_schema",
                    "json_schema" to mapOf("name" to "answer", "schema" to schema)
                ),
                "messages" to listOf(mapOf("role" to "user", "content" to openAiUserMessage(system, user)))
            )
        } else {
            mapOf(
                "model" to cfg.model,
                "stream" to false,
                "format" to schema,
                "options" to mapOf("num_predict" to cfg.numPredict, "temperature" to 0),
                "messages" to listOf(
                    mapOf("role" to "system", "content" to system),
                    mapOf("role" to "user", "content" to user)
                )
            )
        }

        val request = HttpRequest.newBuilder()
            .uri(URI.create("${cfg.baseUrl.trimEnd('/')}${if (openai) "/v1/chat/completions" else "/api/chat"}"))
            .timeout(Duration.ofSeconds(cfg.timeoutSec))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
            .build()

        // 컨텍스트 8,192 토큰 — 지시+근거가 약 11,000자를 넘으면 답에 쓸 자리가 모자라 잘린다(LLM 담당 권고).
        if (openai && system.length + user.length > PROMPT_CHAR_BUDGET) {
            log.warn("sLLM 입력이 깁니다 : {}자 > {}자 — 응답이 잘릴 수 있습니다", system.length + user.length, PROMPT_CHAR_BUDGET)
        }
        val key = cacheKey(cfg.provider, cfg.model, system, user, schema)
        cached(key)?.let { return SllmResult.Ok(it) }

        // 앞선 호출이 끝날 때까지 기다린다. 너무 오래 기다려야 하면 시작하지 않는다.
        if (!gate.tryAcquire(cfg.queueWaitSec, java.util.concurrent.TimeUnit.SECONDS)) {
            log.warn(
                "sLLM 이 다른 요청을 처리 중이라 {}초 기다렸지만 차례가 오지 않았습니다 — 이번 요청은 포기합니다",
                cfg.queueWaitSec
            )
            // 모델이 없는 것과 지금 바쁜 것은 다르다. 화면이 다르게 안내해야 한다 —
            // 방금 결과를 본 사용자에게 "모델 준비 중" 은 이상하게 읽힌다.
            return SllmResult.Busy
        }

        // 기다리는 동안 같은 입력을 앞 요청(미리 계산 등)이 끝냈을 수 있다 — 다시 부르지 않는다.
        cached(key)?.let { gate.release(); return SllmResult.Ok(it) }

        val started = System.currentTimeMillis()
        val response = try {
            runCatching { http.send(request, HttpResponse.BodyHandlers.ofString()) }
                .onFailure { log.warn("sLLM 호출 실패 : model={} {}", cfg.model, it.toString()) }
                .getOrNull()
        } finally {
            gate.release()
        } ?: return SllmResult.Failed
        val elapsed = System.currentTimeMillis() - started

        if (response.statusCode() != 200) {
            log.warn("sLLM 응답 코드 {}", response.statusCode())
            return SllmResult.Failed
        }

        val root = runCatching { objectMapper.readTree(response.body()) }
            .onFailure { log.warn("sLLM 응답을 JSON 으로 읽지 못했습니다 : {}", it.toString()) }
            .getOrNull() ?: return SllmResult.Failed

        // OpenAI 형식은 choices[0] 안에 있다. 네이티브 형식과 같은 이름으로 맞춰 아래를 한 벌로 둔다.
        val choice = root.path("choices").path(0)
        val message = if (openai) choice.path("message") else root.path("message")

        // 잘린 응답은 버린다. 중간에 끊긴 문장이 통과하면 검증기도 못 잡는다.
        val doneReason = (if (openai) choice.path("finish_reason") else root.path("done_reason")).asText(null)
        if (doneReason != null && doneReason != "stop") {
            // 입력·출력 토큰을 함께 남긴다 — 컨텍스트(8,192)를 입력이 다 먹은 것인지, 모델이 길게 쓴 것인지 가른다.
            log.warn(
                "sLLM 응답이 잘렸습니다 : done_reason={} num_predict={} usage={} 입력={}자 본문 끝={} — 이 응답은 버립니다",
                doneReason, cfg.numPredict, root.path("usage"), system.length + user.length,
                message.path("content").asText("").takeLast(160)
            )
            return SllmResult.Failed
        }

        // thinking 이 아니라 content 를 읽는다.
        val content = message.path("content").asText("")
        if (content.isBlank()) {
            log.warn(
                "sLLM 본문이 비어 있습니다 : num_predict={} thinking={}자 — 추론에 토큰을 다 쓴 것으로 보입니다",
                cfg.numPredict, message.path("thinking").asText("").length
            )
            return SllmResult.Failed
        }

        log.info("sLLM 응답 : model={} {}ms 입력={}자 본문={}자 usage={}", cfg.model, elapsed, system.length + user.length, content.length, root.path("usage"))

        return runCatching { objectMapper.readTree(content) }
            .onFailure { log.warn("sLLM 본문을 JSON 으로 읽지 못했습니다 : {}", content.take(300)) }
            .getOrNull()
            ?.let { remember(key, it); SllmResult.Ok(it) }
            ?: SllmResult.Failed
    }

    /**
     * OpenAI 형식에서 쓸 user 메시지 — `[지시]` · `[근거]` · `[질문]`
     *
     * 모델 내장 지시문이 `[근거]`·`[질문]` 틀을 읽도록 튜닝돼 있어 그 틀을 그대로 쓴다.
     * 지시문(원래 system)은 맨 앞 `[지시]` 에 둔다. 모양(JSON)은 response_format 이 강제한다.
     */
    internal fun openAiUserMessage(system: String, user: String): String = buildString {
        appendLine("[지시]")
        // 모델은 오늘 날짜를 모른다 — "전일" · "지난달" 을 엉뚱한 해로 채우지 않게 먼저 알린다.
        appendLine("오늘은 ${java.time.LocalDate.now()}이다.")
        appendLine(system.trim())
        appendLine()
        appendLine("[근거]")
        appendLine(user.trim())
        appendLine()
        appendLine("[질문]")
        append("위 [지시]에 따라 [근거]에 있는 값만 써서 JSON 으로 답하라.")
    }
}

/**
 * sLLM 호출 결과.
 *
 * 실패를 `null` 하나로 뭉치면 화면이 사유를 구분할 수 없다 — 모델이 없는 것과
 * 지금 다른 분석이 도는 것은 사용자에게 다르게 안내해야 한다.
 */
sealed interface SllmResult {
    /** 응답을 받아 JSON 으로 읽었다. */
    data class Ok(val node: JsonNode) : SllmResult

    /** 앞선 호출이 끝나기를 기다리다 포기했다. 잠시 뒤 다시 하면 된다. */
    data object Busy : SllmResult

    /** 호출·파싱에 실패했거나 응답이 잘렸다. */
    data object Failed : SllmResult
}
