package com.dwje.api.controller

import com.dwje.api.common.response.ApiResponse
import com.dwje.api.common.util.MenuId
import com.dwje.api.model.request.ExportFormatRequest
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
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 자연어 질의 이력 컨트롤러 (SY-08)
 *
 * AI 모델 설정(`/ai/model-config`, `/ai/mask-rules`) · 모델 버전 관리(`/ai/model-releases`, `/ai/vector-builds`,
 * `/ai/finetune-builds`, `/ai/assets`, `/ai/embed-models`) · Agent 실행 현황(`/ai/agents…`)은 2026-09-15 에 화면과 함께 제거됐다.
 * AI 통합 대시보드가 쓰는 `GET /dashboard/ai/agents` 는 `DashboardAiController` 에 그대로 있다.
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
}
