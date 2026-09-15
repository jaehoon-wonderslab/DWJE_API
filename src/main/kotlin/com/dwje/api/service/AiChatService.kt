package com.dwje.api.service

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.exception.ResourceNotFoundException
import com.dwje.api.common.security.UserContext
import com.dwje.api.common.util.DataField
import com.dwje.api.model.request.AiAskRequest
import com.dwje.api.model.request.AiFeedbackRequest
import com.dwje.api.repository.AiChatRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 자연어 질의(AI 채팅) 서비스 (AI-01)
 *
 * 질의 처리 흐름
 * 1. 용어 사전 기반 정규화 (현장 유사어 → 공식 용어)
 * 2. 의도 분류 — denied / unknown / trend / trace / downtime / metric
 * 3. 부서 열람 권한이 있는 문서만 대상으로 근거 검색 (RAG)
 * 4. 데이터 접근 권한 기반 마스킹 적용 후 응답 조립
 * 5. 질의·검색 이력 기록 (감사 및 파인튜닝 학습데이터 후보)
 *
 * `denied` 분기는 감사 로그를 남기고, `unknown` 분기는 답을 추정하지 않고 자료 소재를 안내한다.
 *
 * ## 답변에 권한 없는 값을 넣지 않는다 (V33)
 * 화면은 표 블록을 `blindColumns` 로 가릴 수 있지만 문장(`answerHtml`)은 가릴 수 없다. 그래서 서버가 세 지점에서 막는다.
 * - 질의 자체가 권한 없는 항목을 묻는다 → `denied`(답변 없음). 항목 판정은 코드 키워드 + 항목 표(항목명·응답 필드명)라 새 항목도 걸린다.
 * - 근거 문서 검색 → 권한 없는 항목이 태그된 문서는 제외([AiChatRepository.searchDocumentChunks]). 제목·발췌가 답변에 실리기 때문이다.
 * - 표 블록 → 열 이름을 `tb_sys_data_field_attr` 에서 찾아 `blindColumns` 를 채우고, 권한 없는 열은 값을 null 로 보낸다.
 * `answerHtml` 은 질문 원문과 (걸러진) 문서 제목만으로 만든다. 수치를 문장에 넣는 경로를 새로 만들 때는
 * 반드시 [DataFieldService.fieldOf] 로 항목을 찾아 [com.dwje.api.common.security.UserPrincipal.canReadField] 를 통과한 값만 쓴다.
 */
@Service
class AiChatService(
    private val aiChatRepository: AiChatRepository,
    private val glossaryNormalizer: GlossaryNormalizer,
    private val auditLogService: AuditLogService,
    private val authorizationService: AuthorizationService,
    private val dataFieldService: DataFieldService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** 근거 문서 검색 상위 K */
        private const val SEARCH_TOP_K = 8

        /** 의도별 참여 Agent 매핑 — ax.tb_ai_agent.agent_no */
        private val INTENT_AGENTS: Map<String, List<String>> = mapOf(
            "trend" to listOf("②", "④"),
            "trace" to listOf("⑤", "⑧"),
            "downtime" to listOf("②", "⑨"),
            "metric" to listOf("②", "⑥"),
            "unknown" to listOf("②"),
            "denied" to listOf("⑦")
        )

        /** 의도 판정 키워드 사전 */
        private val INTENT_KEYWORDS: List<Pair<String, List<String>>> = listOf(
            "downtime" to listOf("비가동", "정지", "멈춤", "가동률", "다운타임"),
            "trace" to listOf("추적", "이력", "LOT", "로트", "언제", "어느 설비", "어디서"),
            "trend" to listOf("추이", "변화", "증가", "감소", "트렌드", "지난주", "전월", "대비"),
            "metric" to listOf("불량률", "수율", "생산량", "실적", "지표", "달성률", "얼마", "몇")
        )

        /** 데이터 접근 권한이 필요한 질의 키워드 — 권한 없으면 denied 처리 */
        private val RESTRICTED_KEYWORDS: List<Pair<String, List<String>>> = listOf(
            DataField.PRICE to listOf("단가", "금액", "원가", "가공비", "매출"),
            DataField.CUSTOMER to listOf("고객사", "거래처", "납품처"),
            DataField.PLAN to listOf("출하계획", "출하 계획", "연간계획"),
            DataField.WORKER to listOf("작업자", "근태", "담당자 사번")
        )
    }

    /**
     * 자연어 질의를 처리한다. (No.14)
     *
     * @param request 세션 ID 및 질의문
     * @return 응답 메시지 · 의도 · 근거 문서 · 참여 Agent · 후속 질의 후보
     */
    @Transactional
    fun ask(request: AiAskRequest): Map<String, Any?> {
        val started = System.currentTimeMillis()
        val principal = UserContext.current()

        // 1. 세션 확보 — 미지정 시 새 세션을 생성한다.
        val sessionId = parseSessionId(request.sessionId) ?: UUID.randomUUID()
        val question = request.question.trim()

        // 2. 용어 정규화 — 현장 유사어를 공식 용어로 치환한다.
        val normalized = glossaryNormalizer.normalize(question)

        // 3. 권한 기반 질의 차단 판정 (denied) — 코드 키워드 + 항목 표(항목명·응답 필드명).
        //    원문과 정규화 문장을 모두 본다 — 용어 치환이 키워드를 바꿔 놓아도(실측: 「E2E」→「ER2E」) 차단이 빠지지 않게.
        val deniedField = detectRestrictedField(question, principal) ?: detectRestrictedField(normalized.normalizedText, principal)
        if (deniedField != null) {
            return handleDenied(sessionId, question, normalized.normalizedText, deniedField, started)
        }

        // 4. 의도 분류
        val intent = classifyIntent(normalized.normalizedText)

        // 5. 근거 문서 검색 — 부서 열람 권한이 있는 문서만, 권한 없는 데이터 항목이 태그된 문서는 제외한다.
        val blindKeys = dataFieldService.blindKeysFor(principal)
        val hits = aiChatRepository.searchDocumentChunks(normalized.normalizedText, principal.deptId, SEARCH_TOP_K, blindKeys)

        // 6. 근거가 없으면 답을 추정하지 않고 자료 소재를 안내한다. (unknown)
        val finalIntent = if (hits.isEmpty() && intent != "metric") "unknown" else intent
        val answerHtml = buildAnswerHtml(finalIntent, normalized.normalizedText, hits)

        // 표 블록 — 열별 항목 key(blindColumns) 를 채우고 권한 없는 열은 값을 비운다.
        val blocks = buildBlocks(finalIntent, hits)
        val blindAppliedCnt = blocks.sumOf { applyBlindColumns(it, principal) }

        // 7. 질의 이력 기록
        val elapsedMs = (System.currentTimeMillis() - started).toInt()
        val prevChatId = aiChatRepository.findLastChatId(sessionId, principal.userId)
        val chatId = aiChatRepository.insertChatLog(
            sessionId = sessionId,
            userId = principal.userId,
            deptNm = principal.deptName,
            question = question,
            normalizedQuestion = normalized.normalizedText,
            intentCd = finalIntent,
            intentNm = intentName(finalIntent),
            answer = answerHtml,
            responseMs = elapsedMs,
            blindAppliedCnt = blindAppliedCnt,
            profileId = aiChatRepository.findActiveProfileId(),
            prevChatId = prevChatId
        )

        // 8. 참여 Agent 및 검색 이력 기록
        val agentNos = INTENT_AGENTS[finalIntent] ?: emptyList()
        aiChatRepository.insertChatAgents(chatId, aiChatRepository.findAgentIdsByNo(agentNos))

        val queryId = aiChatRepository.insertQueryLog(
            chatId = chatId,
            userId = principal.userId,
            deptId = principal.deptId,
            queryText = question,
            normalizedText = normalized.normalizedText,
            topK = SEARCH_TOP_K,
            poolCnt = hits.size,
            hitCnt = hits.size,
            citedCnt = hits.size,
            blockedDocCnt = 0,
            totalMs = elapsedMs
        )
        aiChatRepository.insertQueryHits(queryId, hits)

        return mapOf(
            "messageId" to chatId,
            "sessionId" to sessionId.toString(),
            "intent" to finalIntent,
            "intentNm" to intentName(finalIntent),
            "answerHtml" to answerHtml,
            "blocks" to blocks,
            "blindFields" to blindKeys.sorted(),
            "agents" to agentNos.map { mapOf("no" to it) },
            "sources" to hits.map {
                mapOf(
                    "docId" to it["docId"],
                    "chunkId" to it["chunkId"],
                    "title" to it["title"],
                    "docType" to it["docType"],
                    "docDate" to it["docDate"],
                    "page" to it["page"],
                    "snippet" to it["snippet"]
                )
            },
            "normalizedQuestion" to normalized.normalizedText,
            "termReplacements" to normalized.replacements,
            "followups" to buildFollowups(finalIntent),
            "elapsedMs" to elapsedMs
        )
    }

    /**
     * 세션 대화 이력을 조회한다. (No.15)
     *
     * @param sessionId 세션 ID
     */
    @Transactional(readOnly = true)
    fun getSessionMessages(sessionId: String): Map<String, Any?> {
        val principal = UserContext.current()
        val uuid = parseSessionId(sessionId)
            ?: throw InvalidParameterException("세션 ID 형식이 올바르지 않습니다. [$sessionId]", "sessionId")

        return mapOf(
            "sessionId" to sessionId,
            "messages" to aiChatRepository.findSessionMessages(uuid, principal.userId)
        )
    }

    /**
     * 새 대화를 시작한다. (No.16 — 기존 세션 맥락 초기화)
     *
     * 이력은 감사·학습 목적으로 보존하고 세션 연결만 해제한다.
     */
    @Transactional
    fun startNewSession(sessionId: String): Map<String, Any?> {
        val principal = UserContext.current()
        parseSessionId(sessionId)?.let { aiChatRepository.detachSession(it, principal.userId) }

        val newSessionId = UUID.randomUUID()
        return mapOf("newSessionId" to newSessionId.toString())
    }

    /**
     * 추천 질의 목록을 조회한다. (No.17 — 현장 빈출 질의 기반)
     *
     * @param limit 조회 건수
     */
    @Transactional(readOnly = true)
    fun getSuggestions(limit: Int): Map<String, Any?> =
        mapOf("suggestions" to aiChatRepository.findSuggestions(limit))

    /**
     * 응답 평가를 등록한다. (No.19 — 파인튜닝 학습데이터 후보)
     *
     * @param messageId 질의 로그 ID
     * @param request   평가 값 및 의견
     */
    @Transactional
    fun saveFeedback(messageId: Long, request: AiFeedbackRequest): Map<String, Any?> {
        val principal = UserContext.current()

        // 화면의 good/bad 값을 코드값(AI_CHAT_RATING)으로 변환한다.
        val ratingCd = when (request.rating.lowercase()) {
            "good", "useful" -> "USEFUL"
            "bad" -> "BAD"
            "reask" -> "REASK"
            else -> throw InvalidParameterException("평가 값은 good/bad/reask 만 허용합니다. [${request.rating}]", "rating")
        }

        val updated = aiChatRepository.updateRating(messageId, ratingCd, principal.userId)
        if (updated == 0) throw ResourceNotFoundException("평가할 응답을 찾을 수 없습니다. [messageId=$messageId]")

        log.info("AI 응답 평가 등록 : messageId={} rating={} by={}", messageId, ratingCd, principal.userId)
        return mapOf("success" to true, "rating" to ratingCd)
    }

    /**
     * 내려받기 대상 응답을 조회한다. (No.18 — blind 항목 제외 후 저장)
     *
     * @param messageId 질의 로그 ID
     */
    @Transactional(readOnly = true)
    fun getExportRows(messageId: Long): Pair<Map<String, Any?>, List<Map<String, Any?>>> {
        val principal = UserContext.current()
        val chat = aiChatRepository.findChatLog(messageId)
            ?: throw ResourceNotFoundException("응답을 찾을 수 없습니다. [messageId=$messageId]")

        // 본인 질의만 내려받을 수 있다.
        if (chat["userId"] != principal.userId && !principal.superAdmin) {
            throw ResourceNotFoundException("응답을 찾을 수 없습니다. [messageId=$messageId]")
        }

        val rows = listOf(
            mapOf<String, Any?>(
                "askedAt" to chat["askedAt"],
                "question" to chat["question"],
                "intent" to chat["intentNm"],
                // HTML 태그를 제거한 평문으로 저장한다.
                "answer" to stripHtml(chat["answer"] as String?),
                "elapsedMs" to chat["responseMs"]
            )
        )
        return chat to rows
    }

    // ---------------------------------------------------------------------------------
    // 내부 판정 로직
    // ---------------------------------------------------------------------------------

    /**
     * 데이터 접근 권한이 필요한 질의인지 판정한다.
     *
     * 코드 키워드([RESTRICTED_KEYWORDS])에 더해 항목 표의 항목명 조각(`단가·금액` → 단가, 금액)과
     * 응답 필드명(`unitPrice`)을 키워드로 본다. 그래서 운영 중에 추가된 항목도 배포 없이 걸린다.
     *
     * @return 권한이 없어 차단해야 하는 데이터 항목 key (없으면 null)
     */
    internal fun detectRestrictedField(question: String, principal: com.dwje.api.common.security.UserPrincipal): String? {
        if (principal.superAdmin) return null
        RESTRICTED_KEYWORDS
            .firstOrNull { (field, keywords) -> !principal.canReadField(field) && keywords.any { question.contains(it) } }
            ?.let { return it.first }
        return dataFieldService.restrictedFieldFor(question, principal)
    }

    /**
     * denied 분기 — 답변을 생성하지 않고 감사 로그를 남긴다.
     */
    private fun handleDenied(
        sessionId: UUID,
        question: String,
        normalizedQuestion: String,
        deniedField: String,
        started: Long
    ): Map<String, Any?> {
        val principal = UserContext.current()
        val elapsedMs = (System.currentTimeMillis() - started).toInt()
        val answerHtml =
            "<p>요청하신 내용에는 <strong>열람 권한이 없는 데이터 항목</strong>이 포함되어 있어 답변할 수 없습니다.</p>" +
                "<p>필요하시면 전산팀에 데이터 접근 권한을 신청해 주세요.</p>"

        val chatId = aiChatRepository.insertChatLog(
            sessionId = sessionId,
            userId = principal.userId,
            deptNm = principal.deptName,
            question = question,
            normalizedQuestion = normalizedQuestion,
            intentCd = "denied",
            intentNm = "권한 없음",
            answer = answerHtml,
            responseMs = elapsedMs,
            blindAppliedCnt = 1,
            profileId = null,
            prevChatId = null
        )
        aiChatRepository.insertChatAgents(chatId, aiChatRepository.findAgentIdsByNo(listOf("⑦")))

        // denied 분기는 감사 로그에 기록한다.
        auditLogService.record(
            logType = "MASK",
            menuId = "ai-chat",
            fieldKey = deniedField,
            targetDesc = question.take(300),
            resultCd = "BLIND",
            maskedCnt = 1,
            remark = "자연어 질의 권한 차단"
        )

        return mapOf(
            "messageId" to chatId,
            "sessionId" to sessionId.toString(),
            "intent" to "denied",
            "intentNm" to "권한 없음",
            "answerHtml" to answerHtml,
            "blocks" to emptyList<Any>(),
            "agents" to listOf(mapOf("no" to "⑦")),
            "sources" to emptyList<Any>(),
            "followups" to emptyList<Any>(),
            "deniedField" to deniedField,
            "elapsedMs" to elapsedMs
        )
    }

    /**
     * 키워드 기반으로 질의 의도를 분류한다.
     */
    private fun classifyIntent(question: String): String =
        INTENT_KEYWORDS.firstOrNull { (_, keywords) -> keywords.any { question.contains(it, ignoreCase = true) } }
            ?.first
            ?: "unknown"

    /** 의도 코드의 한글 명칭 */
    private fun intentName(intent: String): String = when (intent) {
        "trend" -> "추이 분석"
        "trace" -> "이력 추적"
        "downtime" -> "비가동 조회"
        "metric" -> "지표 조회"
        "denied" -> "권한 없음"
        else -> "미분류"
    }

    /**
     * 응답 HTML 을 조립한다.
     *
     * unknown 분기는 답을 추정하지 않고 자료 소재를 안내한다.
     */
    private fun buildAnswerHtml(intent: String, question: String, hits: List<Map<String, Any?>>): String {
        if (intent == "unknown") {
            return buildString {
                append("<p>질문에 정확히 답할 수 있는 근거 자료를 찾지 못했습니다.</p>")
                append("<p>다음 자료를 확인해 보시거나, 기간·공정·제품을 지정해 다시 질문해 주세요.</p>")
                append("<ul><li>생산 모니터링 · 실적 집계 화면</li><li>품질 보고서 · 아침회의 자료</li></ul>")
            }
        }

        return buildString {
            append("<p>「${escapeHtml(question)}」에 대한 분석 결과입니다.</p>")
            if (hits.isNotEmpty()) {
                append("<p>다음 ${hits.size}건의 근거 자료를 참고했습니다.</p><ol>")
                hits.forEach { hit ->
                    append("<li>${escapeHtml(hit["title"] as? String ?: "-")}")
                    (hit["heading"] as? String)?.let { append(" — ${escapeHtml(it)}") }
                    append("</li>")
                }
                append("</ol>")
            }
        }
    }

    /**
     * 화면 렌더링용 블록(표·차트 등) 구성을 생성한다.
     *
     * 표 블록은 `columns`(열 이름 = 응답 필드명) 와 `rows`(가변 Map) 를 가진다. blindColumns 는 [applyBlindColumns] 가 채운다.
     */
    private fun buildBlocks(intent: String, hits: List<Map<String, Any?>>): List<MutableMap<String, Any?>> {
        if (hits.isEmpty()) return emptyList()
        return listOf(
            mutableMapOf(
                "type" to "sources",
                "title" to "근거 자료",
                "columns" to listOf("title", "page", "date"),
                "rows" to hits.map { mutableMapOf<String, Any?>("title" to it["title"], "page" to it["page"], "date" to it["docDate"]) }
            )
        )
    }

    /**
     * 표 블록에 `blindColumns` 를 채우고, 권한 없는 열의 값을 null 로 바꾼다.
     *
     * 열 이름은 `columns` 가 없으면 첫 행의 키에서 얻는다. 항목은 `tb_sys_data_field_attr`(적용 중 항목)에서 찾는다 —
     * 코드에 박힌 매핑이 없어 새 항목·새 필드명도 배포 없이 걸린다.
     *
     * @return 가린 칸 수
     */
    @Suppress("UNCHECKED_CAST")
    internal fun applyBlindColumns(block: MutableMap<String, Any?>, principal: com.dwje.api.common.security.UserPrincipal): Int {
        val rows = (block["rows"] as? List<MutableMap<String, Any?>>).orEmpty()
        val columns = (block["columns"] as? List<String>) ?: rows.firstOrNull()?.keys?.toList().orEmpty()
        val blindColumns = dataFieldService.blindColumnsFor(columns)
        block["columns"] = columns
        block["blindColumns"] = blindColumns
        return dataFieldService.maskRows(rows, columns, blindColumns, principal)
    }

    /**
     * 의도별 후속 질의 후보를 제시한다.
     */
    private fun buildFollowups(intent: String): List<Map<String, String>> = when (intent) {
        "trend" -> listOf(
            mapOf("q" to "같은 기간 공정별 수율은 어떻게 되나요?"),
            mapOf("q" to "주요 불량 유형 구성 변화도 알려주세요.")
        )
        "trace" -> listOf(
            mapOf("q" to "해당 LOT 의 후속 공정 이력도 보여주세요."),
            mapOf("q" to "같은 금형에서 생산된 다른 LOT 도 확인해 주세요.")
        )
        "downtime" -> listOf(
            mapOf("q" to "비가동 사유별 누적 시간을 알려주세요."),
            mapOf("q" to "미등록 비가동 건이 남아 있나요?")
        )
        "metric" -> listOf(
            mapOf("q" to "목표 대비 달성률은 어떻게 되나요?"),
            mapOf("q" to "전월 대비 변화량을 알려주세요.")
        )
        else -> emptyList()
    }

    /** 세션 ID 문자열을 UUID 로 변환한다. (형식 오류 시 null) */
    private fun parseSessionId(value: String?): UUID? =
        value?.takeIf { it.isNotBlank() }?.let { runCatching { UUID.fromString(it.trim()) }.getOrNull() }

    /** HTML 태그를 제거해 평문으로 만든다. (파일 저장용) */
    private fun stripHtml(html: String?): String? =
        html?.replace(Regex("<[^>]*>"), " ")?.replace(Regex("\\s+"), " ")?.trim()

    /** HTML 특수문자를 이스케이프한다. (XSS 방지) */
    private fun escapeHtml(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
