package com.dwje.api.model.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size

/**
 * 자연어 질의 요청 — POST /api/v1/ai/chat/ask
 *
 * @param sessionId 대화 세션 ID (미지정 시 새 세션 생성)
 * @param question  사용자 질의문
 */
data class AiAskRequest(
    val sessionId: String? = null,

    @field:NotBlank(message = "질문을 입력해 주세요.")
    @field:Size(max = 2000, message = "질문은 2000자 이내로 입력해 주세요.")
    val question: String
)

/**
 * 응답 평가 요청 — POST /api/v1/ai/chat/messages/{messageId}/feedback
 *
 * @param rating  평가 — good(유용) | bad(오답) | reask(재질의)
 * @param comment 보완 의견
 */
data class AiFeedbackRequest(
    @field:NotBlank(message = "평가 값을 선택해 주세요.")
    val rating: String,
    val comment: String? = null
)

/**
 * 응답 결과 내려받기 요청 — POST /api/v1/ai/chat/messages/{messageId}/export
 *
 * @param format 다운로드 형식 — xls | csv
 */
data class AiExportRequest(
    val format: String = "xls"
)

/**
 * 용어 정규화 미리보기 요청 — POST /api/v1/glossary/normalize
 *
 * @param text 정규화 대상 문장
 */
data class GlossaryNormalizeRequest(
    @field:NotBlank(message = "정규화할 문장을 입력해 주세요.")
    val text: String
)

/**
 * 사내 LLM 채팅 한 메시지 — POST /api/ai/chat
 *
 * @param role    `user` · `assistant` 만 받는다. `system` 은 서버가 버린다(모델 내장 지시문이 대체되므로)
 * @param content 본문
 */
data class LlmChatMessage(
    val role: String? = null,
    val content: String? = null
)

/**
 * 사내 LLM 채팅 요청 — POST /api/ai/chat
 *
 * 길이 제한은 합계로 서버가 판정한다(근거 문서 포함 약 2만 자). 여기서는 터무니없는 크기만 막는다.
 *
 * @param messages 대화(오래된 것부터). 서버는 최근 10턴만 보낸다
 * @param context  근거 문서 — 있으면 마지막 질문을 `[근거] … [질문] …` 으로 감싼다
 * @param messageId `/api/v1/ai/chat/ask` 가 준 질의 이력 ID — 주면 받은 답을 그 이력의 답변으로 저장한다(선택)
 */
data class LlmChatRequest(
    @field:NotEmpty(message = "질문을 입력해 주세요.")
    @field:Size(max = 200, message = "대화가 너무 깁니다. 새 대화로 시작해 주세요.")
    val messages: List<LlmChatMessage>? = null,

    @field:Size(max = 100_000, message = "근거 문서가 너무 깁니다.")
    val context: String? = null,

    val messageId: Long? = null
)

/**
 * AI 데이터 도구 실행 인자 — POST /api/ai/tools/{name}
 *
 * MCP `tools/call` 의 `arguments` 자리다. 도구가 늘면 그 도구의 인자를 여기에 더한다
 * (`GET /api/ai/tools` 의 `inputSchema` 와 같은 이름). Map 으로 받지 않는다 — 모르는 키가 조용히 버려진다.
 *
 * @param from         시작일 YYYY-MM-DD
 * @param to           종료일 YYYY-MM-DD
 * @param compareFrom  비교 기간 시작일(선택)
 * @param compareTo    비교 기간 종료일(선택)
 * @param label        기간 이름(선택)
 * @param compareLabel 비교 기간 이름(선택)
 */
data class AiToolCallRequest(
    val from: String? = null,
    val to: String? = null,
    val compareFrom: String? = null,
    val compareTo: String? = null,
    val label: String? = null,
    val compareLabel: String? = null
)

/**
 * 후속 질의 만들기 — POST /api/ai/followups
 *
 * @param question 방금 한 질문
 * @param answer   사내 LLM 이 쓴 답(스트리밍으로 받은 것)
 */
data class LlmFollowupRequest(
    @field:NotBlank(message = "질문이 필요합니다.")
    @field:Size(max = 2000, message = "질문은 2000자 이내입니다.")
    val question: String? = null,

    @field:NotBlank(message = "답이 필요합니다.")
    @field:Size(max = 20_000, message = "답이 너무 깁니다.")
    val answer: String? = null
)
