package com.dwje.api.controller

import com.dwje.api.config.AppProperties
import com.dwje.api.model.request.AiToolCallRequest
import com.dwje.api.model.request.LlmChatRequest
import com.dwje.api.model.request.AiAskRequest
import com.dwje.api.model.request.LlmFollowupRequest
import com.dwje.api.common.response.ApiResponse
import com.dwje.api.common.security.UserContext
import com.dwje.api.common.util.ClientIpResolver
import com.dwje.api.common.util.MenuId
import com.dwje.api.service.AiDataToolService
import com.dwje.api.service.AuthorizationService
import com.dwje.api.service.AiChatService
import com.dwje.api.service.LlmChatProxyService
import org.springframework.web.bind.annotation.PathVariable
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 사내 LLM 채팅 프록시 컨트롤러
 *
 * 브라우저는 LLM 서버(`DWJE_LLM_BASE_URL`)를 직접 부르지 않고 이 두 경로만 부른다.
 * 경로가 `/api/v1` 아래가 아닌 것은 LLM 연동 명세(`/api/ai/chat`, `/api/ai/health`)를 그대로 따른 것이다.
 *
 * 모델 출력을 끝까지 검사한 뒤 SQL·내부 스키마가 없을 때만 SSE content로 보낸다.
 * 따라서 SSE 포맷은 유지하지만 첫 content는 생성 완료 후 도착한다.
 */
@RestController
@RequestMapping("/api/ai")
@Tag(name = "02. AI 질의")
class LlmChatProxyController(
    private val llmChatProxyService: LlmChatProxyService,
    private val aiChatService: AiChatService,
    private val aiDataToolService: AiDataToolService,
    private val appProperties: AppProperties,
    private val authorizationService: AuthorizationService,
    private val clientIpResolver: ClientIpResolver = ClientIpResolver()
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 전체 제한 시간이 지나면 LLM 응답 스트림을 닫는다 — 헤더 뒤에 멈춘 스트림을 붙들지 않게. */
    private val watchdog = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "llm-stream-watchdog").apply { isDaemon = true }
    }

    /**
     * 채팅 — 모델 응답을 검사하고 OpenAI 호환 SSE(`data: {...}` … `data: [DONE]`)로 보낸다.
     *
     * 스트림을 열기 전의 실패는 JSON 오류(429 · 502 · 504)로 답한다.
     * 연 뒤에 끊기면 받은 데까지만 보낸다 — `[DONE]` 이 오지 않은 것으로 화면이 끊김을 안다.
     * `messageId` 를 주면 받은 본문을 그 질의 이력의 답변으로 저장한다.
     */
    @Operation(summary = "사내 LLM 채팅 (스트리밍)", description = "덕우전자 전용 모델에 대화를 넘기고 SSE 응답을 그대로 흘려보낸다.")
    @PostMapping("/chat")
    fun chat(
        @Valid @RequestBody request: LlmChatRequest,
        httpRequest: HttpServletRequest,
        response: HttpServletResponse
    ) {
        // 덕반장 AI 화면 권한 — 로그인만으로는 부를 수 없다(D-26, 03 MNP-15). 미배정은 ai-chat 을 가져 통과한다(R-11).
        authorizationService.requireMenu(MenuId.AI_CHAT)
        llmChatProxyService.checkRate(clientIp(httpRequest))
        val saved = if (request.messageId == null) {
            val question = request.messages.orEmpty().lastOrNull { it.role == "user" }?.content.orEmpty()
            aiChatService.ask(AiAskRequest(request.sessionId, question))
        } else null
        // 도구 파서가 없는 서버(json-schema 도구 선택)는 tool 역할 대화도 받지 못할 수 있다 — DB 결과를 [근거] 에 넣는다
        val toolExchange = if (llmChatProxyService.toolJsonSchemaActive()) null else saved?.let(::toolExchange)
        val effectiveRequest = if (saved != null && request.context.isNullOrBlank())
            request.copy(context = evidenceContext(saved, includeFacts = toolExchange == null, appProperties.ai.glossaryBlockEnabled)) else request
        val route = saved?.get("chatRoute")?.toString()
            ?: request.messageId?.let { aiChatService.storedChatRoute(it, UserContext.current().userId) }
            ?: "rag"
        val general = route == "general"
        val messages = llmChatProxyService.buildMessages(effectiveRequest, general)
        val messageId = request.messageId ?: saved?.get("messageId") as? Long
        // 조회 0건·조건 오류는 ask 가 정한 고정 답을 보낸다 — 모델을 부르지 않는다(이력에는 ask 가 이미 적었다)
        val fixed = (saved?.get("fixedAnswer") as? String)
            ?: request.messageId?.let { aiChatService.storedFixedAnswer(it, UserContext.current().userId) }
        if (fixed != null) {
            response.status = HttpServletResponse.SC_OK
            response.contentType = "text/event-stream;charset=UTF-8"
            response.setHeader("Cache-Control", "no-cache, no-transform")
            if (saved != null) {
                response.setHeader("X-AI-Session-Id", saved["sessionId"].toString())
                response.setHeader("X-AI-Message-Id", messageId.toString())
            }
            val publicAnswer = llmChatProxyService.publicAnswer(fixed)
            response.outputStream.write(llmChatProxyService.completionEvent(publicAnswer, true).toByteArray(Charsets.UTF_8))
            response.outputStream.flush()
            log.info("LLM 채팅 : 고정 답(모델 호출 없음) 본문={}자", publicAnswer.length)
            return
        }
        val started = System.currentTimeMillis()
        val upstream = try { llmChatProxyService.open(messages, if (general) null else toolExchange, general) } catch (e: Exception) {
            llmChatProxyService.saveAnswer(messageId, "", System.currentTimeMillis() - started)
            throw e
        }

        response.status = HttpServletResponse.SC_OK
        response.contentType = "text/event-stream;charset=UTF-8"
        response.setHeader("Cache-Control", "no-cache, no-transform")
        response.setHeader("X-Accel-Buffering", "no")
        if (saved != null) {
            response.setHeader("X-AI-Session-Id", saved["sessionId"].toString())
            response.setHeader("X-AI-Message-Id", messageId.toString())
        }

        val deadline = watchdog.schedule(
            { runCatching { upstream.close() } },
            appProperties.llm.timeoutMs, TimeUnit.MILLISECONDS
        )
        val tap = llmChatProxyService.StreamTap()
        var bytes = 0L
        var outcome = "완료"
        try {
            upstream.use { input ->
                val buf = ByteArray(4096)
                while (true) {
                    val n = try {
                        input.read(buf)
                    } catch (e: IOException) {
                        outcome = if (deadline.isDone) "제한 시간 초과" else "LLM 스트림 끊김"
                        break
                    }
                    if (n < 0) break
                    tap.feed(buf, n)
                    bytes += n
                }
            }
        } finally {
            deadline.cancel(false)
            val elapsed = System.currentTimeMillis() - started
            if (outcome == "완료" && !tap.done) outcome = "LLM 스트림 끊김"
            // 화면에 보인 것과 같은 모양으로 남긴다 — 끊겼으면 끊겼다고 적는다.
            val answer = tap.text().let { if (outcome == "완료" || it.isBlank()) it else "$it\n\n($outcome)" }
            val publicAnswer = llmChatProxyService.publicAnswer(answer)
            try {
                response.outputStream.write(llmChatProxyService.completionEvent(publicAnswer, tap.done).toByteArray(Charsets.UTF_8))
                response.outputStream.flush()
            } catch (e: IOException) {
                outcome = "화면이 중단"
            }
            llmChatProxyService.saveAnswer(messageId, publicAnswer, elapsed)
            log.info("LLM 채팅 : {} {}ms {}B 본문={}자 메시지={}건", outcome, elapsed, bytes, tap.text().length, messages.size)
        }
    }

    /** 후속 질의 — 답을 본 사내 LLM 이 2~3개를 쓴다. `{ questions[], reason }` */
    @Operation(summary = "후속 질의 만들기", description = "방금 받은 답을 보고 이어서 물을 만한 질문을 사내 LLM 이 만든다.")
    @PostMapping("/followups")
    fun followups(@Valid @RequestBody request: LlmFollowupRequest): ApiResponse<Map<String, Any?>> {
        authorizationService.requireMenu(MenuId.AI_CHAT)
        return ApiResponse.ok(llmChatProxyService.followups(request.question!!, request.answer!!))
    }

    /**
     * 헬스체크 — LLM 서버 `/v1/models` 에 모델이 올라와 있는지. `{ ok, model }`
     *
     * 이것만 공통 응답 래퍼(ApiResponse) 없이 낸다 — LLM 연동 명세가 `{ ok, model }` 모양을 정했다.
     */
    @Operation(summary = "사내 LLM 헬스체크", description = "LLM 서버에 설정한 모델이 올라와 있는지 확인한다.")
    @GetMapping("/health")
    fun health(): Map<String, Any?> = llmChatProxyService.health()

    /**
     * AI 데이터 도구 목록 — MCP `tools/list` 모양(`name` · `description` · `inputSchema`)
     *
     * 모델이 도구 호출을 지원하게 되면 이 정의를 그대로 `tools` 로 넘긴다. MCP 서버로 감쌀 때도 이 목록을 쓴다.
     */
    @Operation(summary = "AI 데이터 도구 목록", description = "채팅 근거로 쓰는 DB 집계 도구(MCP tools/list 모양)")
    @GetMapping("/tools")
    fun tools(): ApiResponse<Map<String, Any?>> {
        authorizationService.requireMenu(MenuId.AI_CHAT)
        return ApiResponse.ok(mapOf("tools" to aiDataToolService.listTools()))
    }

    /** AI 데이터 도구 실행 — MCP `tools/call` 모양. 조회자의 데이터 권한으로 값을 가린다 */
    @Operation(summary = "AI 데이터 도구 실행", description = "DB 집계 도구를 실행해 구조화된 결과를 낸다(MCP tools/call 모양)")
    @PostMapping("/tools/{name}")
    fun callTool(@PathVariable name: String, @RequestBody(required = false) args: AiToolCallRequest?): ApiResponse<Map<String, Any?>> {
        authorizationService.requireMenu(MenuId.AI_CHAT)
        val a = args ?: AiToolCallRequest()
        return ApiResponse.ok(aiDataToolService.call(
            name,
            mapOf("from" to a.from, "to" to a.to, "wcCd" to a.wcCd, "eqptCd" to a.eqptCd,
                "groupByDate" to a.groupByDate,
                "compareFrom" to a.compareFrom, "compareTo" to a.compareTo,
                "label" to a.label, "compareLabel" to a.compareLabel, "limit" to a.limit,
                "years" to a.years, "basis" to a.basis, "defectReports" to a.defectReports),
            UserContext.current()
        ))
    }

    /**
     * 요청 수 제한의 기준 IP — 판정 규칙은 [ClientIpResolver] 한 곳에 있다(AUD-03).
     * `X-Forwarded-For` 의 첫 값은 브라우저가 마음대로 적을 수 있어 제한을 우회당하므로 믿지 않는다.
     */
    private fun clientIp(request: HttpServletRequest): String = clientIpResolver.resolve(request) ?: "-"

    companion object {
        /** [용어] 블록 최대 줄 수 (07 GLS-04) */
        const val GLOSSARY_BLOCK_MAX = 10

        /**
         * LLM 근거 문자열 — 치환이 있으면 앞에 `[용어]` 블록(현장 표현 → 공식 용어 : 뜻, 중복 제거 최대 10줄),
         * 그 뒤에 데이터 근거·문서 발췌. 전체 12,000자.
         */
        internal fun evidenceContext(ask: Map<String, Any?>, includeFacts: Boolean, glossaryBlock: Boolean = true): String {
            @Suppress("UNCHECKED_CAST")
            val facts = (ask["dataEvidence"] as? List<Map<String, Any?>>).orEmpty()
            @Suppress("UNCHECKED_CAST")
            val sources = (ask["sources"] as? List<Map<String, Any?>>).orEmpty()
            val evidence = ((if (includeFacts) facts.mapNotNull { it["text"]?.toString() } else emptyList()) + sources.mapNotNull { source ->
                source["snippet"]?.toString()?.takeIf { it.isNotBlank() }
            }).joinToString("\n")
            @Suppress("UNCHECKED_CAST")
            val lines = if (!glossaryBlock) emptyList() else (ask["termReplacements"] as? List<Map<String, Any?>>).orEmpty()
                .mapNotNull { r ->
                    val from = r["from"]?.toString() ?: return@mapNotNull null
                    val to = r["to"]?.toString() ?: return@mapNotNull null
                    "$from → $to" + (r["definition"]?.toString()?.takeIf { it.isNotBlank() }?.let { " : $it" } ?: "")
                }
                .distinct().take(GLOSSARY_BLOCK_MAX)
            val block = if (lines.isEmpty()) "" else "[용어]\n" + lines.joinToString("\n")
            return listOf(block, evidence).filter { it.isNotEmpty() }.joinToString("\n\n").take(12_000)
        }
    }

    private fun toolExchange(ask: Map<String, Any?>): LlmChatProxyService.ToolExchange? {
        @Suppress("UNCHECKED_CAST")
        val facts = (ask["dataEvidence"] as? List<Map<String, Any?>>).orEmpty()
        val first = facts.firstOrNull() ?: return null
        val name = first["tool"]?.toString() ?: return null
        if (AiDataToolService.TOOLS.none { it["name"] == name }) return null
        @Suppress("UNCHECKED_CAST")
        val args = (first["args"] as? Map<String, Any?>).orEmpty()
        val result = facts.mapNotNull { it["text"]?.toString() }.joinToString("\n")
        val id = "call_" + ask["debugRequestId"].toString().replace("-", "")
        return LlmChatProxyService.ToolExchange(id, name, args, result)
    }
}
