package com.dwje.api.controller

import com.dwje.api.common.response.ApiResponse
import com.dwje.api.model.request.AiAskRequest
import com.dwje.api.model.request.AiExportRequest
import com.dwje.api.model.request.AiFeedbackRequest
import com.dwje.api.service.AiChatService
import com.dwje.api.service.DownloadLogService
import com.dwje.api.service.ExportService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/**
 * 자연어 질의 API 컨트롤러 (AI-01)
 *
 * 사내 데이터·문서를 근거로 질의에 답하며, 데이터 접근 권한이 없는 항목은 응답 단계에서 차단한다.
 */
@RestController
@RequestMapping("/api/v1/ai/chat")
@Tag(name = "02. AI 질의")
class AiChatController(
    private val aiChatService: AiChatService,
    private val exportService: ExportService,
    private val downloadLogService: DownloadLogService
) {

    /**
     * 자연어 질의 요청 (No.14)
     *
     * denied 분기 시 감사 로그를 기록하며, unknown 분기는 답을 추정하지 않고 자료 소재를 안내한다.
     *
     * @param request 세션 ID 및 질의문
     */
    @Operation(summary = "자연어 질의 요청", description = "질의를 정규화·분류하고 근거 문서를 검색해 응답한다.")
    @PostMapping("/ask")
    fun ask(@Valid @RequestBody request: AiAskRequest): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(aiChatService.ask(request), "질의가 처리되었습니다.")

    /**
     * 세션 대화 조회 (No.15)
     *
     * @param sessionId 세션 ID
     */
    @Operation(summary = "세션 대화 조회", description = "세션 단위 질의·응답 이력을 조회한다.")
    @GetMapping("/sessions/{sessionId}")
    fun session(@PathVariable sessionId: String): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(aiChatService.getSessionMessages(sessionId))

    /**
     * 새 대화 시작 (No.16) — 세션 맥락을 초기화한다.
     *
     * @param sessionId 초기화할 세션 ID
     */
    @Operation(summary = "새 대화 시작", description = "기존 세션 맥락을 끊고 새 세션 ID 를 발급한다.")
    @DeleteMapping("/sessions/{sessionId}")
    fun newSession(@PathVariable sessionId: String): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(aiChatService.startNewSession(sessionId), "새 대화를 시작합니다.")

    /**
     * 추천 질의 목록 (No.17) — 현장 빈출 질의 기반으로 갱신된다.
     *
     * @param limit 조회 건수
     */
    @Operation(summary = "추천 질의 목록", description = "최근 30일 빈출 질의를 기반으로 추천 질의를 제공한다.")
    @GetMapping("/suggestions")
    fun suggestions(
        @Parameter(description = "조회 건수") @RequestParam(required = false, defaultValue = "6") limit: Int
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(aiChatService.getSuggestions(limit.coerceIn(1, 30)))

    /**
     * 응답 결과 내려받기 (No.18) — blind 항목 제외 후 저장한다.
     *
     * @param messageId 질의 로그 ID
     * @param request   다운로드 형식
     */
    @Operation(summary = "응답 결과 내려받기", description = "질의 응답을 엑셀·CSV 로 내려받는다.")
    @PostMapping("/messages/{messageId}/export")
    fun export(
        @PathVariable messageId: Long,
        @Valid @RequestBody(required = false) request: AiExportRequest?
    ): ResponseEntity<ByteArrayResource> {
        val format = request?.format ?: "xls"
        val (chat, rows) = aiChatService.getExportRows(messageId)

        // 다운로드 이력을 기록한다. (공통 규약 6 — 출력 감사 대상)
        downloadLogService.record(
            reportId = null,
            reportNm = "자연어 질의 응답",
            menuId = "ai-chat",
            format = format,
            scope = "messageId=$messageId",
            rowCnt = rows.size,
            blindCnt = (chat["maskedCnt"] as? Int) ?: 0
        )

        return exportService.export(
            format = format,
            fileName = "ai_chat_${messageId}_${exportService.timestamp()}",
            headers = listOf("질의일시", "질문", "의도", "답변", "응답시간(ms)"),
            keys = listOf("askedAt", "question", "intent", "answer", "elapsedMs"),
            rows = rows
        )
    }

    /**
     * 응답 평가 (No.19) — 파인튜닝 학습데이터 후보로 활용한다.
     *
     * @param messageId 질의 로그 ID
     * @param request   평가 값(good|bad|reask) 및 의견
     */
    @Operation(summary = "응답 평가", description = "AI 응답 품질을 평가한다. (파인튜닝 학습데이터 후보)")
    @PostMapping("/messages/{messageId}/feedback")
    fun feedback(
        @PathVariable messageId: Long,
        @Valid @RequestBody request: AiFeedbackRequest
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(aiChatService.saveFeedback(messageId, request), "평가가 등록되었습니다.")

    /**
     * 음성 입력 변환 (No.20) — 현장 PC·PDA 용
     *
     * 음성 인식 엔진 연동 전까지는 요청을 접수하고 변환 미지원 상태를 반환한다.
     *
     * @param audio 업로드된 음성 파일
     */
    @Operation(summary = "음성 입력 변환", description = "음성을 텍스트로 변환한다. (엔진 연동 전 미지원 응답)")
    @PostMapping("/asr")
    fun asr(
        @RequestParam("audio") audio: MultipartFile
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(
            mapOf(
                "text" to null,
                "supported" to false,
                "fileName" to audio.originalFilename,
                "sizeBytes" to audio.size
            ),
            "음성 인식 엔진 연동 전으로 변환 결과를 제공하지 않습니다."
        )
}
