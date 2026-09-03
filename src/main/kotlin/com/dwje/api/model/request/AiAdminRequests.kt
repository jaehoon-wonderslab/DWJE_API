package com.dwje.api.model.request

import jakarta.validation.constraints.NotBlank

/**
 * AI 모델 설정 저장 요청 — PUT /api/v1/ai/model-config
 *
 * @param thresholds     이상 탐지 임계치 목록 (key, value)
 * @param classification 분류 기준 (judge_boundary, borderline_range, hitl_criteria)
 */
data class AiModelConfigRequest(
    val thresholds: List<Map<String, Any?>> = emptyList(),
    val classification: Map<String, Any?> = emptyMap()
)

/**
 * 보안 필터링 패턴 등록·수정 요청 — POST/PUT /api/v1/ai/mask-rules/{ruleId}
 *
 * @param name           규칙명
 * @param fieldKey       연결 데이터 항목 key
 * @param targetFields   대상 물리 컬럼 목록 ("schema.table.column")
 * @param action         마스킹 처리 방식 (AI_MASK_TYPE — FULL/PARTIAL/HASH/DROP)
 * @param customerId     고객사 ID (고객사별 정책)
 * @param customerPolicy 정책 설명
 */
data class MaskRuleRequest(
    @field:NotBlank(message = "규칙명을 입력해 주세요.")
    val name: String,

    val fieldKey: String? = null,
    val targetFields: List<String> = emptyList(),

    @field:NotBlank(message = "처리 방식을 선택해 주세요.")
    val action: String,

    val customerId: Int? = null,
    val customerPolicy: String? = null,
    val useYn: Boolean? = true
)

/**
 * 릴리스 등록 요청 — POST /api/v1/ai/model-releases
 *
 * @param ver   버전 태그 (profileCd-vN)
 * @param vecId 코퍼스 스냅샷 코드
 * @param ftId  파인튜닝 자산 ID (assetKey:versionTag)
 * @param mode  전환 방식
 * @param note  비고
 */
data class ModelReleaseRequest(
    val ver: String? = null,
    val vecId: String? = null,
    val ftId: String? = null,
    val mode: String? = null,
    val note: String? = null
)

/**
 * 재색인 실행 요청 — POST /api/v1/ai/vector-builds
 *
 * @param sources      대상 문서 유형 코드 목록 (빈 목록이면 전체)
 * @param embedModelId 임베딩 모델 ID
 * @param chunkSize    청크 크기
 */
data class VectorBuildRequest(
    val sources: List<String> = emptyList(),
    val embedModelId: Int? = null,
    val chunkSize: Int? = null
)

/**
 * 파인튜닝 실행 요청 — POST /api/v1/ai/finetune-builds
 *
 * @param baseModel  베이스 모델
 * @param method     학습 방식 (LORA/QLORA/FULL)
 * @param trainsetId 학습 데이터셋 ID
 * @param epoch      학습 에폭 수
 */
data class FinetuneBuildRequest(
    val baseModel: String? = null,
    val method: String? = null,
    val trainsetId: String? = null,
    val epoch: Int? = null
)

/**
 * 모델 적용 요청 — POST /api/v1/ai/model-releases/{ver}/apply
 *
 * @param mode 전환 방식 — "즉시 전환" | "점진 전환"
 */
data class ModelApplyRequest(
    val mode: String? = null,
    val reason: String? = null
)
