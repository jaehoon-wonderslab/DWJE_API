package com.dwje.api.controller

import com.dwje.api.common.response.ApiResponse
import com.dwje.api.common.util.MenuId
import com.dwje.api.model.request.AiModelConfigRequest
import com.dwje.api.model.request.ExportFormatRequest
import com.dwje.api.model.request.FinetuneBuildRequest
import com.dwje.api.model.request.MaskRuleRequest
import com.dwje.api.model.request.ModelApplyRequest
import com.dwje.api.model.request.ModelReleaseRequest
import com.dwje.api.model.request.VectorBuildRequest
import com.dwje.api.service.AiAdminService
import com.dwje.api.service.DownloadLogService
import com.dwje.api.service.ExportService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * AI 운영 관리 컨트롤러 (SY-08, SY-10, SY-11, SY-12)
 *
 * 자연어 질의 이력 · AI 모델 설정 · 모델 버전 관리 · Agent 실행 현황을 담당한다.
 */
@RestController
@RequestMapping("/api/v1/ai")
@Tag(name = "11. 시스템관리 - AI 운영")
class AiAdminController(
    private val aiAdminService: AiAdminService,
    private val exportService: ExportService,
    private val downloadLogService: DownloadLogService
) {

    // =================================================================================
    // SY-08. 자연어 질의 이력
    // =================================================================================

    /** 질의 이력 요약 (No.186) */
    @Operation(summary = "질의 이력 요약", description = "질의 건수·의도 정확도·평균 응답 시간·재질의율을 반환한다.")
    @GetMapping("/chat/history/summary")
    fun historySummary(
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @Parameter(description = "부서명") @RequestParam(required = false) userGroup: String?
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(aiAdminService.getChatHistorySummary(from, to, userGroup))

    /** 학습데이터 내보내기 (No.189). {messageId} 매핑보다 먼저 선언한다. */
    @Operation(summary = "학습데이터 내보내기", description = "질의·응답 이력을 JSONL 학습 샘플로 내려받는다.")
    @PostMapping("/chat/history/export-trainset")
    fun exportTrainset(
        @Valid @RequestBody(required = false) request: ExportFormatRequest?,
        @Parameter(description = "평가 필터 — USEFUL|BAD|REASK") @RequestParam(required = false) ratingFilter: String?
    ): ResponseEntity<ByteArrayResource> {
        val lines = aiAdminService.getTrainsetLines(request?.from, request?.to, ratingFilter)

        downloadLogService.record(
            reportId = null,
            reportNm = "AI 학습데이터셋",
            menuId = MenuId.CHAT_HISTORY,
            format = "jsonl",
            scope = "from=${request?.from}, to=${request?.to}, rating=${ratingFilter ?: "전체"}",
            rowCnt = lines.size,
            blindCnt = 0
        )

        return exportService.jsonl("trainset_${exportService.timestamp()}", lines)
    }

    /** 질의 이력 조회 (No.187) */
    @Operation(summary = "질의 이력 조회", description = "기간·부서·의도별 질의 이력을 조회한다.")
    @GetMapping("/chat/history")
    fun history(
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) userGroup: String?,
        @RequestParam(required = false) intent: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?
    ): ApiResponse<Map<String, Any?>> {
        val (rows, meta) = aiAdminService.getChatHistory(from, to, userGroup, intent, page, size)
        return ApiResponse.page(mapOf("items" to rows), meta)
    }

    /** 질의 상세 조회 (No.188) */
    @Operation(summary = "질의 상세 조회", description = "질의 원문·정규화 문장·검색 히트·참여 Agent 를 반환한다.")
    @GetMapping("/chat/history/{messageId}")
    fun historyDetail(@PathVariable messageId: Long): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(aiAdminService.getChatDetail(messageId))

    // =================================================================================
    // SY-10. AI 모델 설정
    // =================================================================================

    /** AI 모델 설정 조회 (No.191) */
    @Operation(summary = "AI 모델 설정 조회", description = "이상 탐지 임계치와 분류 기준을 반환한다.")
    @GetMapping("/model-config")
    fun modelConfig(): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(aiAdminService.getModelConfig())

    /** AI 모델 설정 저장 (No.192) */
    @Operation(summary = "AI 모델 설정 저장", description = "임계치와 분류 기준을 저장하고 감사 로그에 기록한다.")
    @PutMapping("/model-config")
    fun saveModelConfig(@Valid @RequestBody request: AiModelConfigRequest): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(aiAdminService.saveModelConfig(request), "설정이 저장되었습니다.")

    /** 보안 필터링 패턴 목록 (No.193) */
    @Operation(summary = "보안 필터링 패턴 목록", description = "마스킹 규칙과 대상 컬럼을 반환한다.")
    @GetMapping("/mask-rules")
    fun maskRules(): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(aiAdminService.getMaskRules())

    /** 보안 필터링 패턴 등록 (No.194) */
    @Operation(summary = "보안 필터링 패턴 등록", description = "새 마스킹 규칙을 등록한다.")
    @PostMapping("/mask-rules")
    fun createMaskRule(@Valid @RequestBody request: MaskRuleRequest): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(aiAdminService.saveMaskRule(null, request), "패턴이 등록되었습니다.")

    /**
     * 보안 필터링 패턴 등록·수정 (No.194 — POST/PUT 동일 동작)
     *
     * 명세가 `POST/PUT /ai/mask-rules/{ruleId}` 로 정의되어 있어 두 메서드를 모두 매핑한다.
     * 지정한 ruleId 가 없으면 신규 등록으로 처리한다.
     */
    @Operation(summary = "보안 필터링 패턴 등록·수정", description = "지정 ID 의 마스킹 규칙을 등록하거나 수정한다.")
    @PostMapping("/mask-rules/{ruleId}")
    fun upsertMaskRule(
        @PathVariable ruleId: Int,
        @Valid @RequestBody request: MaskRuleRequest
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(aiAdminService.saveMaskRule(ruleId, request), "패턴이 저장되었습니다.")

    /** 보안 필터링 패턴 수정 (No.194) */
    @Operation(summary = "보안 필터링 패턴 수정", description = "마스킹 규칙을 수정한다.")
    @PutMapping("/mask-rules/{ruleId}")
    fun updateMaskRule(
        @PathVariable ruleId: Int,
        @Valid @RequestBody request: MaskRuleRequest
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(aiAdminService.saveMaskRule(ruleId, request), "패턴이 수정되었습니다.")

    // =================================================================================
    // SY-11. AI 모델 버전 관리
    // =================================================================================

    /** 모델 버전 요약 (No.195) */
    @Operation(summary = "모델 버전 요약", description = "서비스 중인 버전과 릴리스·색인·학습 완료 건수를 반환한다.")
    @GetMapping("/model-releases/summary")
    fun releaseSummary(): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(aiAdminService.getReleaseSummary())

    /** 버전별 성능 추이 (No.208). {ver} 매핑보다 먼저 선언한다. */
    @Operation(summary = "버전별 성능 추이", description = "릴리스 버전별 성능 지표 추이를 반환한다.")
    @GetMapping("/model-releases/performance-trend")
    fun performanceTrend(): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(aiAdminService.getPerformanceTrend())

    /** 배포·학습 이력 (No.209) */
    @Operation(summary = "배포·학습 이력", description = "모델 전환·롤백 이력을 조회한다.")
    @GetMapping("/model-releases/deploy-logs")
    fun deployLogs(
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?
    ): ApiResponse<Map<String, Any?>> {
        val (rows, meta) = aiAdminService.getDeployLogs(page, size)
        return ApiResponse.page(mapOf("items" to rows), meta)
    }

    /** 직전 버전 롤백 (No.200) */
    @Operation(summary = "직전 버전 롤백", description = "현재 서비스 버전을 내리고 직전 버전을 복원한다.")
    @PostMapping("/model-releases/rollback")
    fun rollback(
        @Valid @RequestBody(required = false) request: ModelApplyRequest?
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(aiAdminService.rollbackRelease(request?.reason), "직전 버전으로 롤백했습니다.")

    /** 릴리스 목록 조회 (No.196) */
    @Operation(summary = "릴리스 목록 조회", description = "AI 서빙 릴리스 목록을 조회한다.")
    @GetMapping("/model-releases")
    fun releases(
        @Parameter(description = "상태 — DRAFT|CANARY|ACTIVE|ROLLED_BACK|RETIRED")
        @RequestParam(required = false) state: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?
    ): ApiResponse<Map<String, Any?>> {
        val (rows, meta) = aiAdminService.getReleases(state, page, size)
        return ApiResponse.page(mapOf("items" to rows), meta)
    }

    /** 릴리스 등록 (No.197) */
    @Operation(summary = "릴리스 등록", description = "벡터 인덱스와 파인튜닝 자산을 묶어 릴리스를 등록한다.")
    @PostMapping("/model-releases")
    fun createRelease(@Valid @RequestBody request: ModelReleaseRequest): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(aiAdminService.createRelease(request), "릴리스가 등록되었습니다.")

    /** 적용 전 성능 비교 (No.198) */
    @Operation(summary = "적용 전 성능 비교", description = "현재 서비스 버전과 적용 대상 버전의 성능을 비교한다.")
    @GetMapping("/model-releases/{ver}/apply-preview")
    fun applyPreview(@PathVariable ver: String): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(aiAdminService.getApplyPreview(ver))

    /** 서비스 적용 (No.199) */
    @Operation(summary = "서비스 적용", description = "릴리스를 서비스에 적용한다. (즉시 전환 / 점진 전환)")
    @PostMapping("/model-releases/{ver}/apply")
    fun applyRelease(
        @PathVariable ver: String,
        @Valid @RequestBody(required = false) request: ModelApplyRequest?
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(aiAdminService.applyRelease(ver, request?.mode), "서비스에 적용되었습니다.")

    /** 릴리스 보관 (No.201) */
    @Operation(summary = "릴리스 보관", description = "릴리스를 보관 상태로 전환한다. 서비스 중인 버전은 보관할 수 없다.")
    @PostMapping("/model-releases/{ver}/archive")
    fun archiveRelease(@PathVariable ver: String): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(aiAdminService.archiveRelease(ver), "릴리스를 보관했습니다.")

    /** 벡터 인덱스 목록 (No.202) */
    @Operation(summary = "벡터 인덱스 목록", description = "색인 작업 이력과 통계를 조회한다.")
    @GetMapping("/vector-builds")
    fun vectorBuilds(
        @RequestParam(required = false) state: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?
    ): ApiResponse<Map<String, Any?>> {
        val (rows, meta) = aiAdminService.getVectorBuilds(state, page, size)
        return ApiResponse.page(mapOf("items" to rows), meta)
    }

    /** 모델 자산 목록 */
    @Operation(
        summary = "모델 자산 목록",
        description = "파인튜닝 폼의 베이스 모델·LoRA 선택지. kind 는 AI_ASSET_KIND (LLM_BASE/LORA/EMBED/RERANK)."
    )
    @GetMapping("/assets")
    fun assets(
        @Parameter(description = "자산 종류 — AI_ASSET_KIND") @RequestParam(required = false) kind: String?
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(aiAdminService.getAssets(kind))

    /** 임베딩 모델 목록 */
    @Operation(
        summary = "임베딩 모델 목록",
        description = "재색인 폼의 embedModelId 선택지. current=true 가 기본 모델이다."
    )
    @GetMapping("/embed-models")
    fun embedModels(): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(aiAdminService.getEmbedModels())

    /** 재색인 실행 (No.203) */
    @Operation(summary = "재색인 실행", description = "문서 벡터 색인 작업을 등록한다.")
    @PostMapping("/vector-builds")
    fun createVectorBuild(@Valid @RequestBody request: VectorBuildRequest): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(aiAdminService.createVectorBuild(request), "재색인 작업을 등록했습니다.")

    /** 벡터 인덱스 상세 (No.204) */
    @Operation(summary = "벡터 인덱스 상세", description = "색인 작업의 설정·통계·오류를 반환한다.")
    @GetMapping("/vector-builds/{vecId}")
    fun vectorBuild(@PathVariable vecId: String): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(aiAdminService.getVectorBuild(vecId))

    /** 파인튜닝 체크포인트 목록 (No.205) */
    @Operation(summary = "파인튜닝 체크포인트 목록", description = "LoRA 학습 산출물 목록을 조회한다.")
    @GetMapping("/finetune-builds")
    fun finetuneBuilds(
        @RequestParam(required = false) state: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?
    ): ApiResponse<Map<String, Any?>> {
        val (rows, meta) = aiAdminService.getFinetuneBuilds(state, page, size)
        return ApiResponse.page(mapOf("items" to rows), meta)
    }

    /** 파인튜닝 실행 (No.206) */
    @Operation(summary = "파인튜닝 실행", description = "파인튜닝 학습을 등록하고 체크포인트 자산을 생성한다.")
    @PostMapping("/finetune-builds")
    fun createFinetuneBuild(@Valid @RequestBody request: FinetuneBuildRequest): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(aiAdminService.createFinetuneBuild(request), "파인튜닝 작업을 등록했습니다.")

    /** 파인튜닝 상세 (No.207) */
    @Operation(summary = "파인튜닝 상세", description = "체크포인트 설정·검증 결과·로그를 반환한다.")
    @GetMapping("/finetune-builds/{ftId}")
    fun finetuneBuild(@PathVariable ftId: String): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(aiAdminService.getFinetuneBuild(ftId))

    // =================================================================================
    // SY-12. Agent 실행 현황
    // =================================================================================

    /** Agent 요약 (No.210) */
    @Operation(summary = "Agent 요약", description = "Master AI 상태와 Agent 처리량·응답 시간을 반환한다.")
    @GetMapping("/agents/summary")
    fun agentSummary(): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(aiAdminService.getAgentSummary())

    /** Master AI 파이프라인 조회 (No.212) */
    @Operation(summary = "Master AI 파이프라인 조회", description = "파이프라인 단계별 상태를 반환한다.")
    @GetMapping("/agents/pipeline")
    fun pipeline(): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(aiAdminService.getPipeline())

    /** Agent 목록 조회 (No.211) */
    @Operation(summary = "Agent 목록 조회", description = "Agent 9종의 상태와 최근 실행 정보를 반환한다.")
    @GetMapping("/agents")
    fun agents(): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(aiAdminService.getAgents())

    /** Agent 재시작 (No.213) */
    @Operation(summary = "Agent 재시작", description = "Agent 를 재시작하고 감사 로그에 기록한다.")
    @PostMapping("/agents/{agentCd}/restart")
    fun restartAgent(@PathVariable agentCd: String): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(aiAdminService.restartAgent(agentCd), "Agent 를 재시작했습니다.")

    /** Agent 실행 이력 (No.214) */
    @Operation(summary = "Agent 실행 이력", description = "Agent 실행 이력과 오류를 조회한다.")
    @GetMapping("/agents/{agentCd}/runs")
    fun agentRuns(
        @PathVariable agentCd: String,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) state: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?
    ): ApiResponse<Map<String, Any?>> {
        val (rows, meta) = aiAdminService.getAgentRuns(agentCd, from, to, state, page, size)
        return ApiResponse.page(mapOf("items" to rows), meta)
    }
}
