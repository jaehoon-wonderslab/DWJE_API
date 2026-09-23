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
 * 덕우전자 전용 모델(`dwje-ax`, Ollama OpenAI 호환 API)에 대화를 넘기고 SSE 응답을 **그대로** 돌려준다.
 * 응답을 고치지 않는다 — 조각이 도착하는 대로 화면에 흘려보내는 것이 이 서비스의 일이다.
 * 다만 질의 이력에 남기려고 흘려보내면서 본문 텍스트만 따로 모은다([StreamTap]).
 *
 * ## 모델 사용 규칙 (어기면 사내 규칙이 적용되지 않는다)
 * - **`role: "system"` 은 절대 보내지 않는다.** 보내면 모델에 내장된 덕우전자 지시문
 *   (근거 기반 답변, `[1]` 근거 번호, 도메인 용어, 단가·개인정보 금지)이 통째로 대체된다.
 *   화면이 보내 와도 여기서 버린다. 근거는 마지막 user 메시지 안에 넣는다.
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

    private val log = LoggerFactory.getLogger(javaClass)

    private val http: HttpClient by lazy {
        HttpClient.newBuilder()
            // LLM 서버는 http 다. h2c 업그레이드를 시도하지 않는다.
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SEC))
            .build()
    }

    /** IP → 최근 1분 안의 요청 시각(ms) */
    private val hits = ConcurrentHashMap<String, ArrayDeque<Long>>()

    companion object {
        private const val CONNECT_TIMEOUT_SEC = 5L
        private const val HEALTH_TIMEOUT_SEC = 5L
        private const val WINDOW_MS = 60_000L
        private val ROLES = setOf("user", "assistant")

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
    fun buildMessages(request: LlmChatRequest): List<Map<String, String>> {
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
        val today = "[지시]\n오늘은 ${java.time.LocalDate.now()}이다."
        val context = request.context?.trim().orEmpty()
        val q = kept[lastUser].content!!.trim()
        kept[lastUser] = LlmChatMessage(
            "user",
            if (context.isNotEmpty()) "$today\n\n[근거]\n$context\n\n[질문]\n$q" else "$today\n\n[질문]\n$q"
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
    fun open(messages: List<Map<String, String>>): InputStream {
        val cfg = appProperties.llm
        val body = mapOf(
            "model" to cfg.model,
            "messages" to messages,
            "stream" to true,
            "reasoning_effort" to "none"
        )

        val request = HttpRequest.newBuilder()
            .uri(URI.create("${cfg.baseUrl.trimEnd('/')}/v1/chat/completions"))
            // 첫 응답(헤더)까지의 제한. 모델 적재(약 20초)와 대기열이 여기 들어간다.
            .timeout(Duration.ofMillis(cfg.timeoutMs))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .apply { if (cfg.apiKey.isNotBlank()) header("Authorization", "Bearer ${cfg.apiKey}") }
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
            .build()

        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (e: HttpTimeoutException) {
            log.warn("LLM 서버 응답 시간 초과 : {}ms {}", cfg.timeoutMs, e.toString())
            throw BusinessException(ErrorCode.LLM_TIMEOUT, ErrorCode.LLM_TIMEOUT.defaultMessage)
        } catch (e: ConnectException) {
            log.warn("LLM 서버 연결 실패 : {} {}", cfg.baseUrl, e.toString())
            throw BusinessException(ErrorCode.LLM_UNAVAILABLE, ErrorCode.LLM_UNAVAILABLE.defaultMessage)
        } catch (e: java.io.IOException) {
            log.warn("LLM 서버 호출 실패 : {} {}", cfg.baseUrl, e.toString())
            throw BusinessException(ErrorCode.LLM_UNAVAILABLE, ErrorCode.LLM_UNAVAILABLE.defaultMessage)
        }

        if (response.statusCode() != 200) {
            val detail = response.body().use { it.readNBytes(300).toString(Charsets.UTF_8) }
            log.warn("LLM 서버 응답 코드 {} : {}", response.statusCode(), detail)
            throw BusinessException(ErrorCode.LLM_UNAVAILABLE, ErrorCode.LLM_UNAVAILABLE.defaultMessage)
        }
        return response.body()
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
            .apply { if (cfg.apiKey.isNotBlank()) header("Authorization", "Bearer ${cfg.apiKey}") }
            .GET()
            .build()

        val response = runCatching { http.send(request, HttpResponse.BodyHandlers.ofString()) }
            .onFailure { log.warn("LLM 헬스체크 실패 : {} {}", cfg.baseUrl, it.toString()) }
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
    fun saveAnswer(messageId: Long?, answer: String, elapsedMs: Long) {
        if (messageId == null || answer.isBlank()) return
        val userId = UserContext.currentOrNull()?.userId ?: return
        runCatching { aiChatRepository.updateLlmAnswer(messageId, userId, answer, elapsedMs.toInt()) }
            .onSuccess { if (it == 0) log.warn("LLM 답 저장 대상 없음 : messageId={} user={}", messageId, userId) }
            .onFailure { log.warn("LLM 답 저장 실패 : messageId={} {}", messageId, it.toString()) }
    }

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

        private fun flushLine() {
            val s = line.toString(Charsets.UTF_8).trim()
            line.reset()
            if (!s.startsWith("data:")) return
            val data = s.removePrefix("data:").trim()
            if (data == "[DONE]") { done = true; return }
            runCatching { objectMapper.readTree(data) }.getOrNull()
                ?.path("choices")?.path(0)?.path("delta")?.path("content")
                ?.takeIf { it.isTextual }
                ?.let { text.append(it.asText()) }
        }
    }
}
