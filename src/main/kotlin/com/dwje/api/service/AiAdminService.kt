package com.dwje.api.service

import com.dwje.api.common.exception.ResourceNotFoundException
import com.dwje.api.common.response.PageMeta
import com.dwje.api.common.util.DateUtils
import com.dwje.api.common.util.MenuId
import com.dwje.api.common.util.PageRequestParam
import com.dwje.api.repository.AiChatRepository
import com.dwje.api.repository.VectorIndexRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 자연어 질의 이력 서비스 (SY-08)
 *
 * 접근 부서 : 전 부서 (chat-history 메뉴 권한)
 *
 * AI 모델 설정(SY-10) · AI 모델 버전 관리(SY-11) · Agent 실행 현황(SY-12)은 2026-09-15 에 화면과 함께 제거됐다.
 * AI 통합 대시보드의 Agent 작동 현황은 `DashboardAiService` 가 따로 제공한다.
 */
@Service
class AiAdminService(
    private val aiChatRepository: AiChatRepository,
    private val vectorIndexRepository: VectorIndexRepository,
    private val authorizationService: AuthorizationService,
    private val objectMapper: ObjectMapper
) {

    // =================================================================================
    // SY-08. 자연어 질의 이력
    // =================================================================================

    /** 질의 이력 요약 (No.186) */
    @Transactional(readOnly = true)
    fun getChatHistorySummary(from: String?, to: String?, userGroup: String?): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.CHAT_HISTORY)
        val (fromDate, toDate) = DateUtils.periodOf(from, to)

        val summary = aiChatRepository.findHistorySummary(fromDate, toDate, userGroup).toMutableMap()
        // 목표 의도 정확도는 AI 성능 검증 기준(90%)을 따른다.
        summary["targetAccuracy"] = 90.0
        return summary.toMap()
    }

    /** 질의 이력 조회 (No.187) */
    @Transactional(readOnly = true)
    fun getChatHistory(
        from: String?,
        to: String?,
        userGroup: String?,
        intent: String?,
        page: Int?,
        size: Int?
    ): Pair<List<Map<String, Any?>>, PageMeta> {
        authorizationService.requireMenu(MenuId.CHAT_HISTORY)

        val (fromDate, toDate) = DateUtils.periodOf(from, to)
        val paging = PageRequestParam.of(page, size)

        val total = aiChatRepository.countHistory(fromDate, toDate, userGroup, intent)
        val rows = aiChatRepository.findHistory(fromDate, toDate, userGroup, intent, paging.limit, paging.offset)

        return rows to PageMeta.of(paging.page, paging.size, total)
    }

    /** 질의 상세 조회 (No.188) */
    @Transactional(readOnly = true)
    fun getChatDetail(messageId: Long): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.CHAT_HISTORY)

        val chat = aiChatRepository.findChatLog(messageId)
            ?: throw ResourceNotFoundException("질의를 찾을 수 없습니다. [messageId=$messageId]")

        val query = vectorIndexRepository.findQueryDetail(messageId)
        val hits = (query?.get("queryId") as? Long)?.let { vectorIndexRepository.findQueryHits(it) } ?: emptyList()

        return mapOf(
            "messageId" to messageId,
            "question" to chat["question"],
            "normalizedQuestion" to chat["normalizedQuestion"],
            "intent" to chat["intent"],
            "intentNm" to chat["intentNm"],
            "prompt" to chat["normalizedQuestion"],
            "answer" to chat["answer"],
            "hits" to hits,
            "search" to query,
            "agents" to aiChatRepository.findChatAgents(messageId),
            "elapsedMs" to chat["responseMs"],
            "maskedCnt" to chat["maskedCnt"],
            "rating" to chat["rating"]
        )
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
                        mapOf("role" to "user", "content" to (row["normalizedQuestion"] ?: row["question"])),
                        mapOf("role" to "assistant", "content" to stripHtml(row["answer"] as? String))
                    ),
                    "meta" to mapOf("intent" to row["intent"], "rating" to row["rating"], "chatId" to row["chatId"])
                )
            )
        }
    }

    /** HTML 태그를 제거해 학습 샘플로 정리한다. */
    private fun stripHtml(html: String?): String? =
        html?.replace(Regex("<[^>]*>"), " ")?.replace(Regex("\\s+"), " ")?.trim()
}
