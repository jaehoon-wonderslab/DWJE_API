package com.dwje.api.controller

import com.dwje.api.common.response.ApiResponse
import com.dwje.api.common.util.MenuId
import com.dwje.api.common.util.ReportFormat
import com.dwje.api.model.request.AiReviewRequest
import com.dwje.api.model.request.AiTrainAnswerRequest
import com.dwje.api.model.request.TrainsetExportRequest
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
 * 자연어 질의 이력 컨트롤러 (SY-08)
 *
 * 조회 엔드포인트는 `scope` 를 받는다(V70) — mine(기본)은 화면 권한 `chat-history` 로 본인 질의만,
 * all 은 화면 권한 `sys-chat-history`(전사 자연어 질의 이력)로 전 사용자 질의. 검수 · 학습 답변 · 학습데이터 · 디버그는
 * `sys-chat-history` 쓰기 동작이다.
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
    private val downloadLogService: DownloadLogService,
    private val listExportService: com.dwje.api.service.ListExportService
) {

    // =================================================================================
    // SY-08. 자연어 질의 이력
    // =================================================================================

    /** 질의 이력 요약 (No.186) */
    @Operation(summary = "질의 이력 요약", description = "질의 건수·응답률·평균 응답 시간·재질의율을 반환한다.")
    @GetMapping("/chat/history/summary")
    fun historySummary(
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @Parameter(description = "부서명") @RequestParam(required = false) userGroup: String?,
        @Parameter(description = "질문 검색어(대소문자 무시)") @RequestParam(required = false) keyword: String?,
        @Parameter(description = "질의자 평가 — USEFUL|REASK|BAD|NONE") @RequestParam(required = false) rating: String?,
        @Parameter(description = "관리자 검토 — USEFUL|REASK|BAD|NONE") @RequestParam(required = false) review: String?,
        @Parameter(description = "응답 여부 — Y|N") @RequestParam(required = false) answered: String?,
        @Parameter(description = "질의자 사번(scope=all 만)") @RequestParam(required = false) empNo: String?,
        @Parameter(description = "조회 범위 — mine(기본, 본인 질의 · chat-history) | all(전 사용자 · sys-chat-history)") @RequestParam(required = false) scope: String?
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(aiAdminService.getChatHistorySummary(from, to, userGroup, AiAdminService.HistoryCond(keyword, rating, review, answered, empNo), scope))

    /** 학습데이터 내보내기 (No.189, 08 CHH-03). {messageId} 매핑보다 먼저 선언한다. */
    @Operation(
        summary = "학습데이터 내보내기",
        description = "질의·응답 이력을 JSONL 학습 샘플로 내려받는다. sys-chat-history 쓰기 동작. 학습 답변이 있는 질의는 평가와 관계없이 넣고 응답 대신 학습 답변을 쓴다(meta.source=TRAIN_ANSWER). 본문 {from, to, ratingFilter(USEFUL|REASK|BAD|ALL, 기본 USEFUL), " +
            "source(REVIEW_OR_USER|REVIEW|USER, 기본 REVIEW_OR_USER), format}. 쿼리 ratingFilter 도 받는다(본문 우선). 응답 헤더 X-Sample-Count = 샘플 수, 0건이면 404."
    )
    @PostMapping("/chat/history/export-trainset")
    fun exportTrainset(
        @Valid @RequestBody(required = false) request: TrainsetExportRequest?,
        @Parameter(description = "평가 필터 — USEFUL|REASK|BAD|ALL") @RequestParam(required = false) ratingFilter: String?
    ): ResponseEntity<ByteArrayResource> {
        val result = aiAdminService.getTrainsetLines(request?.from, request?.to, request?.ratingFilter ?: ratingFilter, request?.source)
        val fileName = "trainset_${result.from}_${result.to}_${exportService.timestamp()}"
        val file = exportService.jsonl(fileName, result.lines)

        downloadLogService.record(
            reportId = null,
            reportNm = "AI 학습데이터셋",
            menuId = MenuId.SYS_CHAT_HISTORY,
            format = ReportFormat.JSONL,
            scope = "from=${result.from}, to=${result.to}, rating=${result.rating}, source=${result.source}",
            rowCnt = result.lines.size,
            blindCnt = result.blindCnt,
            fileNm = "$fileName.jsonl",
            fileSize = file.body?.contentLength()
        )

        return ResponseEntity.status(file.statusCode).headers(file.headers)
            .header("X-Sample-Count", result.lines.size.toString()).body(file.body)
    }

    /** 세션 목록 (08 CHH-18) — {messageId} 매핑보다 먼저 선언한다 */
    @Operation(
        summary = "질의 세션 목록",
        description = "질의 이력을 세션(대화) 단위로 묶어 최근 순으로 보여 준다. 기간 92일 이내. empNo · userGroup 은 scope=all 에서만 쓴다. " +
            "세션이 없는 질의는 sessionKey=chat-{messageId} 한 건짜리 세션이다. hiddenCnt = 열람자에게 응답이 가려지는 질의 수."
    )
    @GetMapping("/chat/history/sessions")
    fun sessions(
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @Parameter(description = "부서명") @RequestParam(required = false) userGroup: String?,
        @Parameter(description = "질의자 사번(scope=all 만)") @RequestParam(required = false) empNo: String?,
        @Parameter(description = "세션 안 질문 검색어") @RequestParam(required = false) keyword: String?,
        @Parameter(description = "이 평가가 있는 세션 — USEFUL|REASK|BAD|NONE") @RequestParam(required = false) rating: String?,
        @Parameter(description = "이 검토가 있는 세션 — USEFUL|REASK|BAD|NONE") @RequestParam(required = false) review: String?,
        @Parameter(description = "응답 여부 — Y|N (그런 질의가 있는 세션)") @RequestParam(required = false) answered: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
        @Parameter(description = "조회 범위 — mine(기본, 본인 질의 · chat-history) | all(전 사용자 · sys-chat-history)") @RequestParam(required = false) scope: String?
    ): ApiResponse<Map<String, Any?>> {
        val (rows, meta) = aiAdminService.getChatSessions(from, to, userGroup, empNo, keyword, page, size,
            cond = AiAdminService.HistoryCond(rating = rating, review = review, answered = answered), scope = scope)
        return ApiResponse.page(mapOf("items" to rows), meta)
    }

    /** 세션 상세 (08 CHH-18) */
    @Operation(summary = "질의 세션 상세", description = "세션 하나의 질의·응답을 시간순으로 보여 준다. 열람자 권한으로 볼 수 없는 응답은 answerHidden 으로 가린다.")
    @GetMapping("/chat/history/sessions/{sessionKey}")
    fun sessionDetail(@PathVariable sessionKey: String, @Parameter(description = "조회 범위 — mine(기본, 본인 질의 · chat-history) | all(전 사용자 · sys-chat-history)") @RequestParam(required = false) scope: String?): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(aiAdminService.getChatSession(sessionKey, scope))

    /** 관리자 검토 저장 (08 CHH-04) — sys-chat-history 쓰기 동작 */
    @Operation(summary = "질의 검토 저장", description = "관리자가 응답을 USEFUL·REASK·BAD 로 검토한다. 질의자 평가와 따로 저장한다. sys-chat-history 쓰기 동작.")
    @PutMapping("/chat/history/{messageId}/review")
    fun review(@PathVariable messageId: Long, @Valid @RequestBody request: AiReviewRequest): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(aiAdminService.saveReview(messageId, request.reviewCd, request.comment), "검토 결과를 저장했습니다.")

    /** 학습 답변 저장 (V70) — 전사 자연어 질의 이력의 「답변 추가(학습 데이터)」 */
    @Operation(
        summary = "질의 학습 답변 저장",
        description = "학습 데이터로 쓸 답변을 저장한다(4000자). 빈 문자열·공백이면 지운다. sys-chat-history 쓰기 동작. " +
            "응답 {messageId, trainAnswer, trainAnswerAt, trainAnswerBy, trainAnswerByNm}."
    )
    @PutMapping("/chat/history/{messageId}/train-answer")
    fun trainAnswer(@PathVariable messageId: Long, @Valid @RequestBody request: AiTrainAnswerRequest): ApiResponse<Map<String, Any?>> {
        val result = aiAdminService.saveTrainAnswer(messageId, request.answer)
        return ApiResponse.ok(result, if (result["trainAnswer"] == null) "학습 답변을 지웠습니다." else "학습 답변을 저장했습니다.")
    }

    /** 질의 이력 부서 선택지 (08 WEB 계약) */
    @Operation(summary = "질의 이력 부서 목록", description = "기간 안 질의의 부서별 건수. 화면의 부서 선택지.")
    @GetMapping("/chat/history/groups")
    fun historyGroups(
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @Parameter(description = "조회 범위 — mine(기본, 본인 질의 · chat-history) | all(전 사용자 · sys-chat-history)") @RequestParam(required = false) scope: String?
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(aiAdminService.getChatHistoryGroups(from, to, scope))

    /** 질의 이력 전체 내려받기 — view QUERY|SESSION (공통 CMN-07, 조회 권한) */
    @Operation(summary = "질의 이력 전체 내려받기", description = "질의 단위(QUERY, 별칭 MESSAGE) 또는 세션 단위(SESSION)로 xlsx 를 만든다. 범위는 전체(ALL)만 — 기간·화면 조건과 무관, 최근 순 상한 10,000건. 가린 응답은 「비공개」.")
    @PostMapping("/chat/history/export")
    fun historyExport(
        @Valid @RequestBody(required = false) request: com.dwje.api.model.request.ListExportRequest?,
        @Parameter(description = "조회 범위 — mine(기본) | all. 본문 scope(ALL)는 내려받기 범위라 따로 둔다") @RequestParam(required = false) scope: String?
    ): ResponseEntity<ByteArrayResource> = listExportService.chatHistory(request, scope)

    /** 질의 이력 조회 (No.187) */
    @Operation(summary = "질의 이력 조회", description = "기간·부서별 질의 이력을 조회한다. scope=mine(기본)은 본인 질의만, all 은 전 사용자. 행에 answeredAt · trainAnswer* 가 있다.")
    @GetMapping("/chat/history")
    fun history(
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) userGroup: String?,
        @Parameter(description = "질문 검색어(대소문자 무시)") @RequestParam(required = false) keyword: String?,
        @Parameter(description = "질의자 평가 — USEFUL|REASK|BAD|NONE") @RequestParam(required = false) rating: String?,
        @Parameter(description = "관리자 검토 — USEFUL|REASK|BAD|NONE") @RequestParam(required = false) review: String?,
        @Parameter(description = "응답 여부 — Y|N") @RequestParam(required = false) answered: String?,
        @Parameter(description = "질의자 사번(scope=all 만)") @RequestParam(required = false) empNo: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
        @Parameter(description = "조회 범위 — mine(기본, 본인 질의 · chat-history) | all(전 사용자 · sys-chat-history)") @RequestParam(required = false) scope: String?
    ): ApiResponse<Map<String, Any?>> {
        val result = aiAdminService.getChatHistory(from, to, userGroup, page, size, AiAdminService.HistoryCond(keyword, rating, review, answered, empNo), scope = scope)
        // maskedRowCnt — 열람자 권한으로 응답을 가린 행 수 (CHH-02)
        return ApiResponse.page(mapOf("items" to result.rows, "maskedRowCnt" to result.maskedRowCnt), result.meta)
    }

    /** 질의 상세 조회 (No.188) */
    @Operation(summary = "질의 상세 조회", description = "질문·응답·판단 근거·미응답 사유·응답시간·평가 기준을 반환한다.")
    @GetMapping("/chat/history/{messageId}")
    fun historyDetail(@PathVariable messageId: Long, @Parameter(description = "조회 범위 — mine(기본, 본인 질의 · chat-history) | all(전 사용자 · sys-chat-history)") @RequestParam(required = false) scope: String?): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(aiAdminService.getChatDetail(messageId, scope))

    /** chat_id가 생성되기 전에 실패한 ask도 requestId로 조회할 수 있다. sys-chat-history 쓰기 동작(관리 기능). */
    @GetMapping("/chat/history/debug/{requestId}")
    fun askDebug(@PathVariable requestId: String): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(aiAdminService.getAskDebug(requestId))
}
