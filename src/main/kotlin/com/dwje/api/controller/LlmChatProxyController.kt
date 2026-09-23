package com.dwje.api.controller

import com.dwje.api.config.AppProperties
import com.dwje.api.model.request.AiToolCallRequest
import com.dwje.api.model.request.LlmChatRequest
import com.dwje.api.model.request.LlmFollowupRequest
import com.dwje.api.common.response.ApiResponse
import com.dwje.api.common.security.UserContext
import com.dwje.api.service.AiDataToolService
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
 * ## 스트리밍은 서블릿 스레드에서 직접 쓴다
 * `StreamingResponseBody`·`SseEmitter` 는 비동기 요청이라 `spring.mvc.async.request-timeout`
 * (Tomcat 기본 30초)에 걸린다. 첫 호출은 모델 적재에만 약 20초가 걸려 그 제한에 닿는다.
 * 여기서는 응답 스트림에 직접 쓰고 조각마다 `flush` 한다 — 압축(`server.compression`)은
 * `text/event-stream` 에 걸리지 않고, nginx 는 `X-Accel-Buffering: no` 로 버퍼링을 끈다.
 */
@RestController
@RequestMapping("/api/ai")
@Tag(name = "02. AI 질의")
class LlmChatProxyController(
    private val llmChatProxyService: LlmChatProxyService,
    private val aiDataToolService: AiDataToolService,
    private val appProperties: AppProperties
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 전체 제한 시간이 지나면 LLM 응답 스트림을 닫는다 — 헤더 뒤에 멈춘 스트림을 붙들지 않게. */
    private val watchdog = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "llm-stream-watchdog").apply { isDaemon = true }
    }

    /**
     * 채팅 — SSE(`data: {...}` … `data: [DONE]`)를 LLM 서버에서 받은 그대로 흘려보낸다.
     *
     * 스트림을 열기 전의 실패는 JSON 오류(429 · 502 · 504)로 답한다.
     * 연 뒤에 끊기면 받은 데까지만 보낸다 — `[DONE]` 이 오지 않은 것으로 화면이 끊김을 안다.
     * 화면이 연결을 끊으면(생성 중단) 다음 조각을 쓰다 실패하고, 그때 LLM 쪽 연결도 닫아 생성을 멈춘다.
     * `messageId` 를 주면 받은 본문을 그 질의 이력의 답변으로 저장한다.
     */
    @Operation(summary = "사내 LLM 채팅 (스트리밍)", description = "덕우전자 전용 모델에 대화를 넘기고 SSE 응답을 그대로 흘려보낸다.")
    @PostMapping("/chat")
    fun chat(
        @Valid @RequestBody request: LlmChatRequest,
        httpRequest: HttpServletRequest,
        response: HttpServletResponse
    ) {
        llmChatProxyService.checkRate(clientIp(httpRequest))
        val messages = llmChatProxyService.buildMessages(request)
        val started = System.currentTimeMillis()
        val upstream = llmChatProxyService.open(messages)

        response.status = HttpServletResponse.SC_OK
        response.contentType = "text/event-stream;charset=UTF-8"
        response.setHeader("Cache-Control", "no-cache, no-transform")
        response.setHeader("X-Accel-Buffering", "no")

        val deadline = watchdog.schedule(
            { runCatching { upstream.close() } },
            appProperties.llm.timeoutMs, TimeUnit.MILLISECONDS
        )
        val tap = llmChatProxyService.StreamTap()
        var bytes = 0L
        var outcome = "완료"
        try {
            upstream.use { input ->
                val out = response.outputStream
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
                    try {
                        out.write(buf, 0, n)
                        out.flush()
                    } catch (e: IOException) {
                        // 화면이 끊었다(생성 중단·창 닫기). upstream 을 닫으면 LLM 서버도 생성을 멈춘다.
                        outcome = "화면이 중단"
                        break
                    }
                    bytes += n
                }
            }
        } finally {
            deadline.cancel(false)
            val elapsed = System.currentTimeMillis() - started
            if (outcome == "완료" && !tap.done) outcome = "LLM 스트림 끊김"
            // 화면에 보인 것과 같은 모양으로 남긴다 — 끊겼으면 끊겼다고 적는다.
            val answer = tap.text().let { if (outcome == "완료" || it.isBlank()) it else "$it\n\n($outcome)" }
            llmChatProxyService.saveAnswer(request.messageId, answer, elapsed)
            log.info("LLM 채팅 : {} {}ms {}B 본문={}자 메시지={}건", outcome, elapsed, bytes, tap.text().length, messages.size)
        }
    }

    /** 후속 질의 — 답을 본 사내 LLM 이 2~3개를 쓴다. `{ questions[], reason }` */
    @Operation(summary = "후속 질의 만들기", description = "방금 받은 답을 보고 이어서 물을 만한 질문을 사내 LLM 이 만든다.")
    @PostMapping("/followups")
    fun followups(@Valid @RequestBody request: LlmFollowupRequest): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(llmChatProxyService.followups(request.question!!, request.answer!!))

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
    fun tools(): ApiResponse<Map<String, Any?>> = ApiResponse.ok(mapOf("tools" to aiDataToolService.listTools()))

    /** AI 데이터 도구 실행 — MCP `tools/call` 모양. 조회자의 데이터 권한으로 값을 가린다 */
    @Operation(summary = "AI 데이터 도구 실행", description = "DB 집계 도구를 실행해 구조화된 결과를 낸다(MCP tools/call 모양)")
    @PostMapping("/tools/{name}")
    fun callTool(@PathVariable name: String, @RequestBody(required = false) args: AiToolCallRequest?): ApiResponse<Map<String, Any?>> {
        val a = args ?: AiToolCallRequest()
        return ApiResponse.ok(aiDataToolService.call(
            name,
            mapOf("from" to a.from, "to" to a.to, "compareFrom" to a.compareFrom, "compareTo" to a.compareTo,
                "label" to a.label, "compareLabel" to a.compareLabel),
            UserContext.current()
        ))
    }

    /**
     * 요청 수 제한에 쓸 IP.
     *
     * `X-Forwarded-For` 의 첫 값은 브라우저가 마음대로 적을 수 있어 제한을 우회당한다.
     * 같은 서버의 nginx(루프백)가 넘긴 요청일 때만 nginx 가 채운 `X-Real-IP` 를 믿는다.
     */
    private fun clientIp(request: HttpServletRequest): String {
        val remote = request.remoteAddr ?: "-"
        val fromProxy = remote == "127.0.0.1" || remote == "0:0:0:0:0:0:0:1" || remote == "::1"
        val real = request.getHeader("X-Real-IP")
        return if (fromProxy && !real.isNullOrBlank()) real.trim() else remote
    }
}
