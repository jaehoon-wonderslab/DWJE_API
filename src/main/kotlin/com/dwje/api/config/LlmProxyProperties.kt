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
    val ratePerMinute: Int = 10
)
