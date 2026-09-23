package com.dwje.api.model.request

import jakarta.validation.constraints.NotBlank

// =====================================================================================
//  SY-10 · AI 모델 설정
// =====================================================================================

/**
 * AI 모델 설정 등록 — POST /api/v1/ai/model-config
 *
 * @param category  AI_CONFIG_CAT (ANOMALY · CLASSIFY · SECURITY)
 * @param key       설정 키. 분류 안에서 유일해야 한다 (`uq_tb_ai_model_config`)
 * @param valueType AI_VALUE_TYPE (NUM · TEXT · SELECT · BOOL · LIST)
 * @param options   valueType=SELECT 일 때 고를 수 있는 값 목록 (구분자 `,`)
 * @param agentCd   담당 Agent 번호 ①~⑨. 비우면 특정 Agent 에 매이지 않는 공통 설정
 */
data class AiModelConfigCreateRequest(
    @field:NotBlank(message = "설정 분류를 선택해 주세요.")
    val category: String,

    @field:NotBlank(message = "설정 키를 입력해 주세요.")
    val key: String,

    @field:NotBlank(message = "설정 이름을 입력해 주세요.")
    val name: String,

    @field:NotBlank(message = "설정 값을 입력해 주세요.")
    val value: String,

    val valueType: String = "NUM",
    val unit: String? = null,
    val options: String? = null,
    val description: String? = null,
    val agentCd: String? = null
)

/** AI 모델 설정 수정 — PUT /api/v1/ai/model-config/{configId} (분류·키는 바꾸지 않는다) */
data class AiModelConfigUpdateRequest(
    @field:NotBlank(message = "설정 이름을 입력해 주세요.")
    val name: String,

    @field:NotBlank(message = "설정 값을 입력해 주세요.")
    val value: String,

    val valueType: String = "NUM",
    val unit: String? = null,
    val options: String? = null,
    val description: String? = null,
    val agentCd: String? = null
)

/** 값 일괄 저장 — PUT /api/v1/ai/model-config */
data class AiModelConfigSaveRequest(
    val items: List<AiModelConfigValue> = emptyList()
)

/** 일괄 저장 한 줄 — `configId` 또는 (`category`,`key`) 중 하나로 대상을 짚는다 */
data class AiModelConfigValue(
    val configId: Int? = null,
    val category: String? = null,
    val key: String? = null,

    @field:NotBlank(message = "설정 값을 입력해 주세요.")
    val value: String
)

/** 사용/미사용 전환 — PATCH /api/v1/ai/model-config/{configId}/state */
data class AiModelConfigStateRequest(val on: Boolean)

/** Agent 사용/미사용 전환 — PATCH /api/v1/ai/agents/{agentCd}/state */
data class AiAgentStateRequest(val on: Boolean)
