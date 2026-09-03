package com.dwje.api.model.request

import jakarta.validation.constraints.NotBlank
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
