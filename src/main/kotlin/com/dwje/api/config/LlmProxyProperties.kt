package com.dwje.api.config

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank

/**
 * 사내 LLM 채팅 프록시 설정 (`app.llm.*`)
 *
 * [AiProperties] 와는 다른 서버다. 저쪽은 로컬 Ollama 의 네이티브 API(`/api/chat`)에
 * 지시문(system)과 JSON 스키마를 실어 부르고, 이쪽은 덕우전자 전용 모델(`dwje-ax`)의
 * **OpenAI 호환 API**(`/v1/chat/completions`)를 스트리밍으로 그대로 흘려보낸다.
 *
 * 브라우저가 LLM 서버를 직접 부르지 않는 이유
 * - LLM 서버의 CORS 는 `http://localhost:*` 만 허용한다(그 밖의 도메인은 403)
 * - LLM 서버가 http 라 https 화면에서 부르면 mixed content 로 막힌다
 * - 인증 없는 서버 주소를 클라이언트 번들에 드러내지 않는다
 *
 * @param baseUrl        LLM 서버 주소 (`DWJE_LLM_BASE_URL`)
 * @param model          모델 태그 (`DWJE_LLM_MODEL`)
 * @param timeoutMs      응답 전체 제한 시간(ms) (`DWJE_LLM_TIMEOUT_MS`)
 * @param maxTurns       모델에 보낼 최근 대화 턴 수 (user+assistant 한 쌍이 1턴)
 * @param maxInputChars  근거 문서까지 합한 입력 길이 상한(자)
 * @param ratePerMinute  IP 한 곳이 1분에 보낼 수 있는 요청 수
 */
data class LlmProxyProperties(

    @field:NotBlank(message = "LLM 서버 주소(app.llm.base-url)는 비워 둘 수 없습니다.")
    val baseUrl: String = "http://wddg.ddns.net:11435",

    @field:NotBlank(message = "LLM 모델 태그(app.llm.model)는 비워 둘 수 없습니다.")
    val model: String = "dwje-ax",

    val generalModel: String = "google/gemma-4-26B-A4B-it",
    val generalSystemPrompt: String = "당신은 친절한 한국어 대화 도우미다. 인사와 일반 지식 질문에 자연스럽고 간결하게 답한다. 사내 규정·실적·품질 데이터는 주어진 근거 없이 추측하지 않는다. 사내 자료가 필요한 질문이면 문서나 조회 조건을 요청한다.",

    /**
     * 모델이 메모리에 없으면 첫 응답에 약 20초가 걸리고, LLM 서버는 한 번에 한 건만 처리해
     * 나머지를 대기열에 쌓는다. 앞 요청을 기다리는 시간까지 넉넉히 준다.
     */
    @field:Min(value = 5000, message = "LLM 제한 시간(app.llm.timeout-ms)은 5000 이상이어야 합니다.")
    val timeoutMs: Long = 120_000,

    @field:Min(value = 1, message = "대화 턴 수(app.llm.max-turns)는 1 이상이어야 합니다.")
    val maxTurns: Int = 10,

    /** 모델 컨텍스트가 8,192 토큰이다. 한글 약 2만 자면 그 안에 든다. */
    @field:Min(value = 1000, message = "입력 길이 상한(app.llm.max-input-chars)은 1000 이상이어야 합니다.")
    val maxInputChars: Int = 20_000,

    /** LLM 서버가 동시에 한 건만 처리하므로 한 사람이 대기열을 채우지 못하게 막는다. */
    @field:Min(value = 1, message = "분당 요청 수(app.llm.rate-per-minute)는 1 이상이어야 합니다.")
    val ratePerMinute: Int = 10,

    /**
     * 도구 선택 방식 — `native`(`tools` + `tool_choice:"auto"`) | `json-schema`(도구 목록을 지시문에 넣고
     * `response_format` 으로 `{name, arguments}` 를 받음) | `auto`(native 로 시도하고, 서버에 도구 파서가 없다는 400 이면
     * 그때부터 json-schema). vLLM 은 `--enable-auto-tool-choice --tool-call-parser` 없이는 tools 를 400 으로 거절한다.
     */
    @field:jakarta.validation.constraints.Pattern(
        regexp = "auto|native|json-schema", message = "도구 선택 방식(app.llm.tool-mode)은 auto · native · json-schema 입니다."
    )
    val toolMode: String = "auto",

    /**
     * system 지시문 — 비우면 보내지 않는다. vLLM LoRA(`dwje-ax`)에는 예전 Ollama 모델처럼 내장 지시문이 없어,
     * 학습 데이터의 문서 어시스턴트 system 원문을 첫 메시지로 보낸다(채팅 스트리밍·JSON 호출).
     */
    val systemPrompt: String = "",

    /** 도구 선택 호출에도 [systemPrompt] 를 보낼지 — 문서 어시스턴트 지시문이 도구 선택을 흐리면 끈다 */
    val toolSystemPrompt: Boolean = false,

    /**
     * 도구 선택(`chooseTool`, native·json-schema 둘 다)의 temperature — 같은 질문에 같은 도구·인자가 나오게 0 이 기본이다.
     * vLLM 은 요청에 값이 없으면 모델 generation_config 의 값으로 뽑는다.
     */
    @field:jakarta.validation.constraints.DecimalMin(value = "0.0", message = "도구 선택 temperature(app.llm.tool-temperature)는 0 이상이어야 합니다.")
    val toolTemperature: Double = 0.0,

    /** 답변(채팅 스트리밍)·`SllmClient.chatJson`(openai 형식)의 샘플링 값 */
    @field:jakarta.validation.Valid
    val sampling: Sampling = Sampling()
) {
    /**
     * 예전 Ollama 모델(`Modelfile.dwje`)에 박혀 있던 값이 기본이다. vLLM 은 `top_k`·`repetition_penalty` 를
     * OpenAI 확장 필드로 받는다(2026-10-02 실서버 200 확인).
     */
    data class Sampling(
        @field:jakarta.validation.constraints.DecimalMin(value = "0.0", message = "app.llm.sampling.temperature 는 0 이상이어야 합니다.")
        val temperature: Double = 0.2,
        @field:jakarta.validation.constraints.DecimalMin(value = "0.0", inclusive = false, message = "app.llm.sampling.top-p 는 0 보다 커야 합니다.")
        @field:jakarta.validation.constraints.DecimalMax(value = "1.0", message = "app.llm.sampling.top-p 는 1 이하여야 합니다.")
        val topP: Double = 0.9,
        /** 0 이하이면 보내지 않는다(서버 기본) */
        val topK: Int = 64,
        @field:jakarta.validation.constraints.DecimalMin(value = "0.0", inclusive = false, message = "app.llm.sampling.repetition-penalty 는 0 보다 커야 합니다.")
        val repetitionPenalty: Double = 1.05
    ) {
        /** 요청 본문에 그대로 붙일 필드 */
        fun fields(): Map<String, Any> = linkedMapOf<String, Any>("temperature" to temperature, "top_p" to topP)
            .also { if (topK > 0) it["top_k"] = topK }
            .also { it["repetition_penalty"] = repetitionPenalty }
    }
}
