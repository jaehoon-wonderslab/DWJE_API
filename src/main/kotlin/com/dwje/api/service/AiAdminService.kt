package com.dwje.api.service

import com.dwje.api.common.exception.ResourceNotFoundException
import com.dwje.api.common.exception.MenuAccessDeniedException
import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.security.UserContext
import com.dwje.api.common.response.PageMeta
import com.dwje.api.common.util.DateUtils
import com.dwje.api.common.util.MenuId
import com.dwje.api.common.util.PageRequestParam
import com.dwje.api.repository.AiChatRepository
import com.dwje.api.repository.VectorIndexRepository
import java.util.UUID
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 자연어 질의 이력 서비스 (SY-08)
 *
 * 접근 : 화면 권한 `chat-history`(ax.tb_sys_dept_menu_perm) · 값 마스킹 : 없음
 *
 * AI 모델 설정(SY-10) · AI 모델 버전 관리(SY-11) · Agent 실행 현황(SY-12)은 2026-09-15 에 화면과 함께 제거됐다.
 * AI 통합 대시보드의 Agent 작동 현황은 `DashboardAiService` 가 따로 제공한다.
 */
@Service
class AiAdminService(
    private val aiChatRepository: AiChatRepository,
    private val vectorIndexRepository: VectorIndexRepository,
    private val authorizationService: AuthorizationService,
    private val objectMapper: ObjectMapper,
    private val askDebugRecorder: AiAskDebugRecorder
) {

    // =================================================================================
    // SY-08. 자연어 질의 이력
    // =================================================================================

    /** 질의 이력 요약 (No.186) */
    @Transactional(readOnly = true)
    fun getChatHistorySummary(from: String?, to: String?, userGroup: String?): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.CHAT_HISTORY)
        val (fromDate, toDate) = DateUtils.periodOf(from, to)

        return aiChatRepository.findHistorySummary(fromDate, toDate, userGroup)
    }

    /** 질의 이력 조회 (No.187) */
    @Transactional(readOnly = true)
    fun getChatHistory(
        from: String?,
        to: String?,
        userGroup: String?,
        page: Int?,
        size: Int?
    ): Pair<List<Map<String, Any?>>, PageMeta> {
        authorizationService.requireMenu(MenuId.CHAT_HISTORY)

        val (fromDate, toDate) = DateUtils.periodOf(from, to)
        val paging = PageRequestParam.of(page, size)

        val total = aiChatRepository.countHistory(fromDate, toDate, userGroup)
        val rows = aiChatRepository.findHistory(fromDate, toDate, userGroup, paging.limit, paging.offset).map { row ->
            row + mapOf("question" to AiResponseSanitizer.publicText(row["question"] as? String),
                "answer" to AiResponseSanitizer.publicText(row["answer"] as? String),
                "judgmentBasis" to (AiResponseSanitizer.publicText(row["judgmentBasis"] as? String) ?: "판단 근거 기록 없음"),
                "unansweredReason" to (row["unansweredReason"] ?: if ((row["answer"] as? String).isNullOrBlank()) "응답이 기록되지 않았습니다." else null))
        }

        return rows to PageMeta.of(paging.page, paging.size, total)
    }

    /** 질의 상세 조회 (No.188) */
    @Transactional(readOnly = true)
    fun getChatDetail(messageId: Long): Map<String, Any?> {
        requireDetailAdmin()

        val chat = aiChatRepository.findChatLog(messageId)
            ?: throw ResourceNotFoundException("질의를 찾을 수 없습니다. [messageId=$messageId]")
        val basis = (chat["evidenceSummary"] as? String)?.takeIf { it.isNotBlank() } ?: run {
            val query = vectorIndexRepository.findQueryDetail(messageId)
            val hits = (query?.get("queryId") as? Long)?.let { vectorIndexRepository.findQueryHits(it) }.orEmpty()
            hits.mapNotNull { it["title"] as? String }.distinct().joinToString("; ").ifBlank { "판단 근거 기록 없음" }
        }

        return mapOf(
            "messageId" to messageId,
            "question" to AiResponseSanitizer.publicText(chat["question"] as? String),
            "answer" to AiResponseSanitizer.publicText(chat["answer"] as? String),
            "judgmentBasis" to AiResponseSanitizer.publicText(basis),
            "unansweredReason" to (chat["unansweredReason"] ?: if ((chat["answer"] as? String).isNullOrBlank()) "응답이 기록되지 않았습니다." else null),
            "evaluationCriteria" to "근거 부합성·질문 충족 여부·응답 적시성",
            "debug" to askDebugRecorder.byChatId(messageId),
            "elapsedMs" to chat["responseMs"],
            "maskedCnt" to chat["maskedCnt"],
            "rating" to chat["rating"]
        )
    }

    @Transactional(readOnly = true)
    fun getAskDebug(requestId: String): Map<String, Any?> {
        requireDetailAdmin()
        val id = runCatching { UUID.fromString(requestId) }
            .getOrElse { throw InvalidParameterException("요청 ID 형식이 올바르지 않습니다.", "requestId") }
        return askDebugRecorder.byRequestId(id) ?: throw ResourceNotFoundException("진단 기록을 찾을 수 없습니다.")
    }

    private fun requireDetailAdmin() {
        authorizationService.requireMenu(MenuId.CHAT_HISTORY)
        if (!UserContext.current().superAdmin) throw MenuAccessDeniedException(MenuId.CHAT_HISTORY)
    }

    /** 학습데이터 내보내기 대상 (No.189) */
    @Transactional(readOnly = true)
    fun getTrainsetLines(from: String?, to: String?, ratingFilter: String?): List<String> {
        authorizationService.requireMenu(MenuId.CHAT_HISTORY)
        val (fromDate, toDate) = DateUtils.periodOf(from, to, 90)

        // JSONL 한 줄에 한 샘플(prompt/completion)을 담는다.
        return aiChatRepository.findTrainsetRows(fromDate, toDate, ratingFilter, 10000).map { row ->
            objectMapper.writeValueAsString(
                mapOf(
                    "messages" to listOf(
                        mapOf("role" to "user", "content" to AiResponseSanitizer.publicText((row["normalizedQuestion"] ?: row["question"]) as? String)),
                        mapOf("role" to "assistant", "content" to AiResponseSanitizer.publicText(stripHtml(row["answer"] as? String)))
                    ),
                    "meta" to mapOf("rating" to row["rating"], "chatId" to row["chatId"])
                )
            )
        }
    }

    /** HTML 태그를 제거해 학습 샘플로 정리한다. */
    private fun stripHtml(html: String?): String? =
        html?.replace(Regex("<[^>]*>"), " ")?.replace(Regex("\\s+"), " ")?.trim()
}
