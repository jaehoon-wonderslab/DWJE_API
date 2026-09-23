package com.dwje.api.controller

import com.dwje.api.common.response.ApiResponse
import com.dwje.api.model.request.AiAgentStateRequest
import com.dwje.api.model.request.AiModelConfigCreateRequest
import com.dwje.api.model.request.AiModelConfigSaveRequest
import com.dwje.api.model.request.AiModelConfigStateRequest
import com.dwje.api.model.request.AiModelConfigUpdateRequest
import com.dwje.api.service.AgentStatusService
import com.dwje.api.service.AiModelConfigService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * AI 모델 설정 컨트롤러 (SY-10 · base-model)
 *
 * Agent 별 임계치·분류 기준을 관리한다.
 *
 * ③ 불량 판정 Agent 의 불량 태그(`/ai/defect-tags`)는 2026-09-23 에 뺐다 — 웹이 부르지 않았고,
 * 근거 표 `ax.tb_ai_defect_tag` · `ax.tb_ai_defect_tag_map` 을 V42 가 지운다.
 *
 * 2026-09-15 에 화면과 함께 지웠다가 2026-09-22 요청으로 되살렸다. 그때 있던
 * `/ai/mask-rules` 는 함께 돌아오지 않았다 — 근거 표 `ax.tb_ai_mask_rule` 을 V32 가 지웠고,
 * 마스킹 규칙은 [DataFieldController] 의 데이터 접근 항목(V33)이 이어받았기 때문이다.
 */
@RestController
@RequestMapping("/api/v1/ai")
@Tag(name = "11. 시스템관리 - AI 운영")
class AiModelConfigController(
    private val service: AiModelConfigService
) {

    /** AI 모델 설정 조회 (No.191) */
    @Operation(
        summary = "AI 모델 설정 조회",
        description = "Agent 별 임계치·분류 기준을 조회한다. 화면 탭을 위해 `byCategory` 로도 묶어서 낸다."
    )
    @GetMapping("/model-config")
    fun configs(
        @Parameter(description = "설정 분류 — ANOMALY|CLASSIFY|SECURITY")
        @RequestParam(required = false) category: String?,
        @Parameter(description = "담당 Agent 번호 ①~⑨") @RequestParam(required = false) agentCd: String?,
        @Parameter(description = "사용 여부") @RequestParam(required = false) applied: Boolean?
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(service.getConfigs(category, agentCd, applied))

    /** AI 모델 설정 값 일괄 저장 (No.192) */
    @Operation(
        summary = "AI 모델 설정 값 일괄 저장",
        description = "여러 설정 값을 한 번에 저장한다. 한 줄이라도 형식이 어긋나면 아무것도 저장하지 않는다."
    )
    @PutMapping("/model-config")
    fun saveConfigValues(@Valid @RequestBody request: AiModelConfigSaveRequest): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(service.saveConfigValues(request), "설정이 저장되었습니다.")

    /** AI 모델 설정 등록 */
    @Operation(summary = "AI 모델 설정 등록", description = "새 설정 키를 등록한다. 같은 (분류, 키)는 409.")
    @PostMapping("/model-config")
    fun createConfig(@Valid @RequestBody request: AiModelConfigCreateRequest): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(service.createConfig(request), "설정이 등록되었습니다.")

    /** AI 모델 설정 수정 */
    @Operation(summary = "AI 모델 설정 수정", description = "설정 이름·값·형식을 수정한다. 분류와 키는 바꿀 수 없다.")
    @PutMapping("/model-config/{configId}")
    fun updateConfig(
        @PathVariable configId: Int,
        @Valid @RequestBody request: AiModelConfigUpdateRequest
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(service.updateConfig(configId, request), "설정이 수정되었습니다.")

    /** AI 모델 설정 사용/미사용 */
    @Operation(
        summary = "AI 모델 설정 사용/미사용",
        description = "설정을 끄거나 켠다. 지우지 않는다 — 읽는 쪽이 키로 값을 찾으므로 지우면 조용히 기본값으로 돌아간다."
    )
    @PatchMapping("/model-config/{configId}/state")
    fun changeConfigState(
        @PathVariable configId: Int,
        @Valid @RequestBody request: AiModelConfigStateRequest
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(service.changeConfigState(configId, request.on), "적용 상태가 변경되었습니다.")
}

/**
 * Agent 실행 현황 컨트롤러 (SY-12 · ai-agent)
 *
 * AI 통합 대시보드가 쓰는 `GET /dashboard/ai/agents` 와는 **다른 엔드포인트**다.
 * 그쪽은 대시보드 패널용이고, 여기는 관리 화면용이라 24시간 실행·오류 건수까지 낸다.
 *
 * Master AI 파이프라인(No.212)은 근거 표(`ax.tb_ai_pipeline_stage`)가 V31 에서 지워져 없다.
 */
@RestController
@RequestMapping("/api/v1/ai/agents")
@Tag(name = "11. 시스템관리 - AI 운영")
class AiAgentController(
    private val service: AgentStatusService
) {

    /** Agent 요약 (No.210) */
    @Operation(
        summary = "Agent 요약",
        description = "Master 상태·활성 Agent 수·분당 이벤트·평균 응답 시간. 최근 10분 창에 실행이 없으면 master.state=IDLE."
    )
    @GetMapping("/summary")
    fun summary(): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(service.getSummary())

    /** Agent 목록 조회 (No.211) */
    @Operation(
        summary = "Agent 목록 조회",
        description = "Agent 9종과 각자의 최신 실행을 조회한다. 한 번도 돈 적 없는 Agent 는 state=IDLE."
    )
    @GetMapping
    fun agents(): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(service.getAgents())

    /** Agent 실행 이력 (No.214) */
    @Operation(
        summary = "Agent 실행 이력",
        description = "Agent 한 종의 실행 이력을 조회한다. 기간 미지정 시 최근 7일. `agentCd` 는 ①~⑨ (URL 인코딩)."
    )
    @GetMapping("/{agentCd}/runs")
    fun runs(
        @PathVariable agentCd: String,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @Parameter(description = "실행 상태 — OK|RUNNING|IDLE|ERROR|STOPPED")
        @RequestParam(required = false) state: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?
    ): ApiResponse<Map<String, Any?>> {
        val (rows, meta) = service.getAgentRuns(agentCd, from, to, state, page, size)
        return ApiResponse.page(mapOf("items" to rows), meta)
    }

    /** Agent 사용/미사용 */
    @Operation(
        summary = "Agent 사용/미사용",
        description = "Agent 를 목록에서 내리거나 되올린다. 미사용 Agent 는 목록·요약에서 빠진다."
    )
    @PatchMapping("/{agentCd}/state")
    fun changeState(
        @PathVariable agentCd: String,
        @Valid @RequestBody request: AiAgentStateRequest
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(service.changeAgentState(agentCd, request.on), "Agent 상태가 변경되었습니다.")

    /** Agent 재시작 (No.213) */
    @Operation(
        summary = "Agent 재시작",
        description = "실행 이력에 재시작 요청을 남긴다. Agent 별도 런타임이 없어 프로세스를 다시 띄우지는 않는다(응답 note 참고)."
    )
    @PostMapping("/{agentCd}/restart")
    fun restart(@PathVariable agentCd: String): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(service.restartAgent(agentCd), "재시작 요청을 기록했습니다.")
}

// AI 서비스 버전 관리(SY-11 · sys-model-ver — `/ai/model-releases` · `/ai/assets` · `/ai/corpus-snapshots` ·
// `/ai/serving-routes`)는 2026-09-23 에 뺐다. 웹이 한 번도 부르지 않았고, 근거 표(모델 자산 · 코퍼스 스냅샷 ·
// 서빙 자산 · 라우팅)와 뷰 · 함수를 V42 가 지운다. `ax.tb_ai_serving_profile` 은 남는다.
