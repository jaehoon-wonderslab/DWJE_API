package com.dwje.api.controller

import com.dwje.api.common.response.ApiResponse
import com.dwje.api.model.request.AiAskRequest
import com.dwje.api.model.request.AiDefectTopExportRequest
import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.exception.SystemErrorException
import com.dwje.api.common.security.UserContext
import com.dwje.api.common.util.MenuId
import com.dwje.api.common.util.ReportFormat
import com.dwje.api.service.AuthorizationService
import com.dwje.api.service.AiDataToolService
import java.time.LocalDate
import java.time.temporal.ChronoUnit
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
 *
 * 접근 : 화면 권한 `ai-chat` — 모든 엔드포인트가 첫 줄에서 확인한다(D-26, 03 MNP-15). 로그인만으로는 부를 수 없다.
 *        미배정 부서는 `ai-chat` 을 가지므로 통과하고(R-11), 값은 데이터 권한 0건으로 모두 가려진다.
 */
@RestController
@RequestMapping("/api/v1/ai/chat")
@Tag(name = "02. AI 질의")
class AiChatController(
    private val aiChatService: AiChatService,
    private val aiDataToolService: AiDataToolService,
    private val exportService: ExportService,
    private val downloadLogService: DownloadLogService,
    private val authorizationService: AuthorizationService
) {

    /** 덕반장 AI 화면 권한 확인 — 이 컨트롤러의 모든 엔드포인트가 부른다 */
    private fun requireAiChat() = authorizationService.requireMenu(MenuId.AI_CHAT)

    /**
     * 자연어 질의 요청 (No.14)
     *
     * denied 분기 시 감사 로그를 기록하며, unknown 분기는 답을 추정하지 않고 자료 소재를 안내한다.
     *
     * @param request 세션 ID 및 질의문
     */
    @Operation(summary = "자연어 질의 요청", description = "질의를 정규화·분류하고 근거 문서를 검색해 응답한다.")
    @PostMapping("/ask")
    fun ask(@Valid @RequestBody request: AiAskRequest): ApiResponse<Map<String, Any?>> {
        requireAiChat()
        return ApiResponse.ok(aiChatService.ask(request), "질의가 처리되었습니다.")
    }

    /** 화면 재진입 시 로그인한 사용자의 마지막 저장 대화를 복원한다. */
    @GetMapping("/sessions/latest")
    fun latestSession(): ApiResponse<Map<String, Any?>> {
        requireAiChat()
        return ApiResponse.ok(aiChatService.getLatestSessionMessages())
    }

    /**
     * 세션 대화 조회 (No.15)
     *
     * @param sessionId 세션 ID
     */
    @Operation(summary = "세션 대화 조회", description = "세션 단위 질의·응답 이력을 조회한다.")
    @GetMapping("/sessions/{sessionId}")
    fun session(@PathVariable sessionId: String): ApiResponse<Map<String, Any?>> {
        requireAiChat()
        return ApiResponse.ok(aiChatService.getSessionMessages(sessionId))
    }

    /**
     * 새 대화 시작 (No.16) — 세션 맥락을 초기화한다.
     *
     * @param sessionId 초기화할 세션 ID
     */
    @Operation(summary = "새 대화 시작", description = "기존 세션 맥락을 끊고 새 세션 ID 를 발급한다.")
    @DeleteMapping("/sessions/{sessionId}")
    fun newSession(@PathVariable sessionId: String): ApiResponse<Map<String, Any?>> {
        requireAiChat()
        return ApiResponse.ok(aiChatService.startNewSession(sessionId), "새 대화를 시작합니다.")
    }

    /**
     * 추천 질의 목록 (No.17) — 현장 빈출 질의 기반으로 갱신된다.
     *
     * @param limit 조회 건수
     */
    @Operation(summary = "추천 질의 목록", description = "최근 30일 빈출 질의를 기반으로 추천 질의를 제공한다.")
    @GetMapping("/suggestions")
    fun suggestions(
        @Parameter(description = "조회 건수") @RequestParam(required = false, defaultValue = "6") limit: Int
    ): ApiResponse<Map<String, Any?>> {
        requireAiChat()
        return ApiResponse.ok(aiChatService.getSuggestions(limit.coerceIn(1, 30)))
    }

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
        requireAiChat()
        val format = request?.format ?: "xls"
        val (chat, rows) = aiChatService.getExportRows(messageId)

        // 파일을 만든 뒤 기록한다 — 형식이 틀리면 export 가 먼저 400 을 내고 이력이 남지 않는다.
        // 비공개 건수는 파일 안 칸 수로 센다 — 질의 응답의 마스킹 수와 다르다 (10 DLG-15)
        val blind = exportService.blindCells()
        val file = exportService.export(
            format = format,
            fileName = "ai_chat_${messageId}_${exportService.timestamp()}",
            headers = listOf("질의일시", "질문", "의도", "답변", "응답시간(ms)"),
            keys = listOf("askedAt", "question", "intent", "answer", "elapsedMs"),
            rows = rows,
            blind = blind
        )

        // 다운로드 이력을 기록한다. (공통 규약 6 — 출력 감사 대상)
        downloadLogService.record(
            reportId = null,
            reportNm = "자연어 질의 응답",
            menuId = MenuId.AI_CHAT,
            format = ReportFormat.ofServerExport(format),
            scope = "messageId=$messageId",
            rowCnt = rows.size,
            blindCnt = blind.total,
            blindCells = blind.counts(),
            fileSize = file.body?.contentLength(),
            params = mapOf("messageId" to messageId, "answerMaskedCnt" to chat["maskedCnt"])
        )
        return file
    }

    /** 실제 xlsx 파일을 만들고 기존 보고서 다운로드 이력에 남긴다. */
    @PostMapping("/defects/top/export")
    fun exportDefectTop(@Valid @RequestBody request: AiDefectTopExportRequest): ResponseEntity<ByteArrayResource> {
        requireAiChat()
        fun date(value: String): LocalDate = runCatching { LocalDate.parse(value) }
            .getOrElse { throw InvalidParameterException("날짜 형식은 YYYY-MM-DD이어야 합니다.", "date") }
        val from = date(request.from)
        val to = date(request.to)
        if (request.limit !in 1..10 || from.isAfter(to) || ChronoUnit.DAYS.between(from, to) > 92)
            throw InvalidParameterException("기간은 93일 이내, 순위는 1~10이어야 합니다.", "limit")
        val (rows, _) = aiDataToolService.defectTopForExport(from, to, request.limit, UserContext.current())
        val fileName = "defect_top_${from}_${to}_${exportService.timestamp()}"
        val blind = exportService.blindCells(mapOf("quantity" to com.dwje.api.common.util.DataField.QTY))
        val result = exportService.excel(fileName,
            listOf("순위", "시작 업무일", "종료 업무일", "불량 유형", "불량 수량"),
            listOf("rank", "from", "to", "defect", "quantity"), rows, blind = blind)
        val logId = downloadLogService.record(reportId = null, reportNm = "불량 유형 상위 ${request.limit}", menuId = MenuId.AI_CHAT,
            format = ReportFormat.XLSX, scope = "${from}~${to}", rowCnt = rows.size, blindCnt = blind.total,
            blindCells = blind.counts(),
            fileNm = "$fileName.xlsx", fileSize = result.body?.contentLength())
        if (logId == 0L) throw SystemErrorException("다운로드 이력을 저장하지 못했습니다. 잠시 후 다시 시도해 주세요.")
        return result
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
    ): ApiResponse<Map<String, Any?>> {
        requireAiChat()
        return ApiResponse.ok(aiChatService.saveFeedback(messageId, request), "평가가 등록되었습니다.")
    }

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
    ): ApiResponse<Map<String, Any?>> {
        requireAiChat()
        return ApiResponse.ok(
            mapOf(
                "text" to null,
                "supported" to false,
                "fileName" to audio.originalFilename,
                "sizeBytes" to audio.size
            ),
            "음성 인식 엔진 연동 전으로 변환 결과를 제공하지 않습니다."
        )
    }
}
