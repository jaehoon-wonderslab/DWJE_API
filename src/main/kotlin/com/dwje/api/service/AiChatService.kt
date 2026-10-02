package com.dwje.api.service

import com.dwje.api.common.util.AuditType
import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.exception.ResourceNotFoundException
import com.dwje.api.common.exception.BusinessException
import com.dwje.api.common.security.UserContext
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.model.request.AiAskRequest
import com.dwje.api.model.request.AiFeedbackRequest
import com.dwje.api.repository.AiChatRepository
import com.dwje.api.common.util.AiBusinessPeriod
import com.dwje.api.common.util.DataField
import com.dwje.api.common.util.MaskingSupport
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * 자연어 질의(AI 채팅) 서비스 (AI-01)
 *
 * 질의 처리 흐름
 * 1. 용어 사전 기반 정규화 (현장 유사어 → 공식 용어)
 * 2. 의도 분류 — unknown / trend / trace / downtime / metric
 * 3. 부서 열람 권한이 있는 문서만 대상으로 근거 검색 (RAG) — 통합관리자는 모든 문서
 * 4. 데이터 접근 권한 기반 마스킹 적용 후 응답 조립 — 답 문장은 만들지 않는다(사내 LLM 이 근거로 쓴다)
 * 5. 질의·검색 이력 기록 (감사 및 파인튜닝 학습데이터 후보)
 *
 * `unknown` 분기는 답을 추정하지 않고 자료 소재를 안내한다.
 *
 * ## 질의는 막지 않고 결과에서 값만 가린다 (V33, 2026-09-16 요청자 결정)
 * 권한 없는 항목이 섞인 질의도 정상으로 답한다. 데이터 권한을 이유로 한 `denied` 는 없다.
 * 화면은 표 블록을 `blindColumns` 로 가릴 수 있지만 문장은 가릴 수 없으므로 서버가 출력 단계에서 가린다.
 * - 근거 문서 → 검색에서 빼지 않는다(빼면 답이 틀려진다). 권한 없는 항목이 태그된 문서는 발췌를 가리고 제목·쪽만 남긴다([DataFieldService.maskHit])
 * - 표 블록 → 열 이름을 `tb_sys_data_field_attr` 에서 찾아 `blindColumns` 를 채우고, 권한 없는 열은 값을 null 로
 * 응답 `blindFields` 에 이 사용자에게 가려지는 항목 key 를 실어 화면이 「어떤 항목이 가려졌는지」 알린다.
 */
@Service
class AiChatService(
    private val aiChatRepository: AiChatRepository,
    private val glossaryNormalizer: GlossaryNormalizer,
    private val auditLogService: AuditLogService,
    private val authorizationService: AuthorizationService,
    private val agentRunRecorder: AgentRunRecorder,
    private val dataFieldService: DataFieldService,
    private val aiDataToolService: AiDataToolService,
    private val askDebugRecorder: AiAskDebugRecorder
) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** 치환 내역 중 열람자가 볼 수 있는 분류의 것만 — 분류 데이터 항목(fieldKey)은 응답에 싣지 않는다 (R-18) */
        fun visibleReplacements(replacements: List<Map<String, Any?>>, principal: UserPrincipal): List<Map<String, Any?>> =
            replacements.filter { r -> (r["fieldKey"] as String?)?.let { principal.canReadField(it) } ?: true }.map { it - "fieldKey" }

        /** 의도 = 원문 판정, unknown 이면 정규화 문장 판정 (07 GLS-04) */
        fun intentOf(question: String, normalizedText: String): String =
            classifyIntent(question).takeIf { it != "unknown" } ?: classifyIntent(normalizedText)

        /**
         * 문서 검색어 = 공식 용어 + 원문 (07 GLS-04) — 원문 표현과 공식 용어 둘 다로 찾는다.
         * 검색이 앞 12낱말만 쓰므로 공식 용어를 앞에 둔다.
         */
        fun searchTextOf(question: String, replacements: List<Map<String, Any?>>): String {
            val terms = replacements.mapNotNull { it["to"] as? String }.distinct()
            return if (terms.isEmpty()) question else terms.joinToString(" ") + " " + question
        }

        private fun classifyIntent(question: String): String =
            if (isGreeting(question)) "greeting" else INTENT_KEYWORDS.firstOrNull { (_, keywords) -> keywords.any { question.contains(it, ignoreCase = true) } }
                ?.first
                ?: "unknown"

        private fun isGreeting(question: String): Boolean =
            Regex("^(안녕(?:하세요|하십니까)?|하이|hi|hello|고마워(?:요)?|감사(?:합니다|해요)?)[!?.~ ]*$", RegexOption.IGNORE_CASE)
                .matches(question.trim())

        private val DATABASE_TOOL_ROUTES = setOf(
            "PRODUCT_LIST", "DAILY_PRODUCT_DEFECT", "AOI_DIMENSION_SUMMARY", "AOI_WORKCENTER_REQUIRED",
            "DEFECT_RATE_TOP", "DEFECT_TOP", "PRODUCTION_COMPARE", "DOCUMENT_COUNT"
        )
        /** 근거 문서 검색 상위 K */
        private const val SEARCH_TOP_K = 8

        /** 의도별 참여 Agent 매핑 — ax.tb_ai_agent.agent_no */
        private val INTENT_AGENTS: Map<String, List<String>> = mapOf(
            "trend" to listOf("②", "④"),
            "trace" to listOf("⑤", "⑧"),
            "downtime" to listOf("②", "⑨"),
            "metric" to listOf("②", "⑥"),
            "unknown" to listOf("②")
        )

        /** 의도 판정 키워드 사전 */
        private val INTENT_KEYWORDS: List<Pair<String, List<String>>> = listOf(
            "downtime" to listOf("비가동", "정지", "멈춤", "가동률", "다운타임"),
            "trace" to listOf("추적", "이력", "LOT", "로트", "언제", "어느 설비", "어디서"),
            "trend" to listOf("추이", "변화", "증가", "감소", "트렌드", "지난주", "전월", "대비"),
            "metric" to listOf("불량률", "수율", "생산량", "실적", "지표", "달성률", "얼마", "몇")
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
        val trace = AskTrace(UUID.randomUUID())
        try {
            return askInternal(request, started, principal, trace)
        } catch (e: Exception) {
            trace.executionCode = "ASK_FAILED"
            trace.errorCode = if (e is BusinessException) e.errorCode.code else "ASK_FAILED"
            throw e
        } finally {
            askDebugRecorder.record(trace.snapshot(principal.userId, (System.currentTimeMillis() - started).toInt()))
        }
    }

    private class AskTrace(val requestId: UUID) {
        var chatId: Long? = null
        var route = "NONE"
        var parseCode = "NOT_PARSED"
        var tool: String? = null
        var executionCode = "SKIPPED"
        var errorCode: String? = null
        var periodFrom: java.time.LocalDate? = null
        var periodTo: java.time.LocalDate? = null
        var rowCount = 0
        var docHitCount = 0
        var toolMs = 0

        fun snapshot(userId: String, totalMs: Int) = AiAskDebug(requestId, userId, chatId, route, parseCode,
            tool, executionCode, errorCode, periodFrom, periodTo, rowCount, docHitCount, toolMs, totalMs)
    }

    private fun askInternal(request: AiAskRequest, started: Long, principal: UserPrincipal, trace: AskTrace): Map<String, Any?> {

        // 1. 세션 확보 — 미지정 시 새 세션을 생성한다.
        val sessionId = parseSessionId(request.sessionId) ?: UUID.randomUUID()
        val question = request.question.trim()
        val storedQuestion = AiQuestionPrivacy.forStorage(question)

        // 2. 용어 정규화 — 현장 유사어를 공식 용어로 치환한다.
        val normalized = glossaryNormalizer.normalize(question)

        // 3. 질의는 막지 않는다 — 데이터 권한은 결과에서 값만 가린다. (2026-09-16 요청자 결정)
        val blindKeys = dataFieldService.blindKeysFor(principal)

        // 4. 의도 분류 — 원문 우선, 원문이 어느 의도에도 걸리지 않을 때만 정규화 문장으로 본다 (07 GLS-04)
        //    정규화가 「불량」 을 다른 공식 용어로 바꿔 「추이」 질문이 다른 의도로 뒤집히지 않게 한다.
        val intent = intentOf(question, normalized.normalizedText)

        // 4-1. 이전 대화 맥락 및 기간 확인
        val prevChatId = aiChatRepository.findLastChatId(sessionId, principal.userId)
        val prevChat = prevChatId?.let { aiChatRepository.findChatLog(it) }
        val previousPeriod = extractPreviousPeriod(prevChat)

        // 5. 모델이 먼저 도구와 날짜를 판단한다. 검증된 인자로 고정 SELECT를 실행한다.
        //      집계는 조회자의 데이터 권한으로 가린다. 실패해도 문서 근거로는 답한다.
        //      원래 질문으로 판단한다 — 용어 정규화가 "불량" 을 다른 공식 용어(예: ISSUE)로 바꿔 수치 질문임을 놓친다.
        val toolResult = aiDataToolService.evidenceForDetailed(question, principal, previousPeriod)
        trace.route = toolResult.route
        trace.parseCode = toolResult.parseCode
        trace.tool = toolResult.tool
        trace.executionCode = toolResult.executionCode
        trace.errorCode = toolResult.errorCode
        trace.periodFrom = toolResult.period?.from
        trace.periodTo = toolResult.period?.to
        trace.rowCount = toolResult.rowCount
        trace.toolMs = toolResult.elapsedMs
        val dataEvidence = toolResult.evidence

        // 5-0. DB 조회가 0건(EMPTY)이거나 모델이 고른 DB 도구의 조건이 검증에서 떨어졌으면(INVALID) LLM 없이 고정 답이다.
        //      문서 어시스턴트 모델에 넘기면 「사내 문서에서 확인할 수 없습니다」 로 답해 틀린 안내가 된다.
        val fixedAnswer = AiFixedAnswer.of(toolResult, java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")))

        // 5-1. 근거 문서 검색 — 문서 질의에만 실행한다. MES/품질 데이터 질문은 DB 도구 결과가
        // 유일한 근거여야 하며, 오래된 벡터 문서가 빈 조회 결과를 대신하거나 답을 덮지 않게 한다.
        // 고정 답(조건 오류 포함)도 문서를 찾지 않는다 — 모델이 DB 도구를 고른 질문이다.
        val hits = (if (toolResult.route in DATABASE_TOOL_ROUTES || toolResult.route in setOf("GREETING", "GENERAL") || fixedAnswer != null) emptyList() else
            aiChatRepository.searchDocumentChunks(searchTextOf(question, normalized.replacements), principal.deptId, SEARCH_TOP_K, principal.superAdmin))
            .map { it.toMutableMap() }
        trace.docHitCount = hits.size
        var blindAppliedCnt = hits.sumOf { dataFieldService.maskHit(it, principal) }

        // 6. 근거가 없으면 답을 추정하지 않고 자료 소재를 안내한다. (unknown)
        val finalIntent = when {
            toolResult.route == "GENERAL" -> "general"
            toolResult.route == "GREETING" -> "greeting"
            toolResult.route == "REFUSAL" -> "unknown"
            dataEvidence.isNotEmpty() && intent == "unknown" -> "metric"
            hits.isEmpty() && dataEvidence.isEmpty() && intent != "metric" && intent != "greeting" -> "unknown"
            else -> intent
        }

        // 7. 표 블록 — 실적 집계 데이터 표 및 근거 문서 블록 생성
        val blocks = buildBlocks(finalIntent, hits, toolResult, principal)
        val blindValues = linkedSetOf<String>()
        blindAppliedCnt += blocks.sumOf { applyBlindColumns(it, principal, blindValues) }

        // 8. 문장 — 권한 없는 항목의 값만 「비공개」로. 문장 구조는 그대로다.
        // 답 문장은 여기서 만들지 않는다 — 사내 LLM(/api/ai/chat)이 이 근거로 쓰고, 받은 답을 이 이력에 저장한다.
        // 예전에는 "「질문」에 대한 분석 결과입니다…" 같은 고정 문장을 답으로 저장·반환했다(LLM 없이). 그 자리는 비워 둔다.
        val answerHtml: String? = null

        // 가린 것이 있으면 감사 로그(MASK) 한 건 — 공통 규약 6. 질의는 막지 않았으므로 결과 코드는 MASKED 다.
        if (blindAppliedCnt > 0) {
            auditLogService.record(
                logType = AuditType.MASK, menuId = "ai-chat", fieldKey = blindKeys.sorted().joinToString(",").take(30),
                targetDesc = "AI 질의 결과", resultCd = "MASKED", maskedCnt = blindAppliedCnt, remark = "자연어 질의 결과 값 마스킹"
            )
        }

        // 9. 질의 이력 기록
        val elapsedMs = (System.currentTimeMillis() - started).toInt()
        val chatId = aiChatRepository.insertChatLog(
            sessionId = sessionId,
            userId = principal.userId,
            deptNm = principal.deptName,
            question = storedQuestion,
            normalizedQuestion = AiQuestionPrivacy.forStorage(normalized.normalizedText),
            intentCd = finalIntent,
            intentNm = intentName(finalIntent),
            answer = fixedAnswer ?: answerHtml,
            responseMs = elapsedMs,
            blindAppliedCnt = blindAppliedCnt,
            profileId = aiChatRepository.findActiveProfileId(),
            prevChatId = prevChatId,
            evidenceSummary = (hits.mapNotNull { it["title"]?.toString() } + dataEvidence.mapNotNull { it["text"]?.toString() })
                .joinToString("; ").take(5000).ifEmpty { null },
            unansweredReason = if (fixedAnswer != null) null else if (hits.isEmpty() && dataEvidence.isEmpty() && finalIntent !in setOf("greeting", "general")) "답변 근거를 찾지 못했습니다." else "응답 생성 중입니다.",
            // 질의자에게 가린 데이터 항목 — 질의 이력에서 권한이 더 좁은 열람자에게 응답을 가리는 기준(08 CHH-02)
            blindFieldKeys = blindKeys,
            askMs = elapsedMs
        )
        trace.chatId = chatId

        // 10. 참여 Agent 및 검색 이력 기록
        val agentNos = INTENT_AGENTS[finalIntent] ?: emptyList()
        aiChatRepository.insertChatAgents(chatId, aiChatRepository.findAgentIdsByNo(agentNos))

        val queryId = aiChatRepository.insertQueryLog(
            chatId = chatId,
            userId = principal.userId,
            deptId = principal.deptId,
            queryText = storedQuestion,
            normalizedText = AiQuestionPrivacy.forStorage(normalized.normalizedText),
            topK = SEARCH_TOP_K,
            poolCnt = hits.size,
            hitCnt = hits.size,
            citedCnt = hits.size,
            blockedDocCnt = 0,
            totalMs = elapsedMs
        )
        aiChatRepository.insertQueryHits(queryId, hits)

        // ⑦ 보안 필터링 Agent — 답변·발췌·표에 마스킹을 적용하고 나온 자리다.
        // 가린 것이 0건이어도 남긴다. "필터가 돌고 있다" 는 사실 자체가 이 화면이 보려는 것이다.
        // 의도별 참여 Agent(②④⑤⑥⑧⑨)는 여기서 남기지 않는다 — 각자 제 작업 지점에서 남기고,
        // ⑨ 는 Alert_Engine 담당이라 API 가 건드리면 중복이 된다.
        agentRunRecorder.record(
            agentNo = AgentRunRecorder.SECURITY,
            throughput = "마스킹 ${blindAppliedCnt}건",
            elapsedMs = elapsedMs.toLong(),
            message = "AI 답변 보안 필터링 (의도=$finalIntent · 가린 항목=${blindKeys.size}종)"
        )

        val followups = if (finalIntent in setOf("greeting", "general")) emptyList() else generateFollowups(question, finalIntent, toolResult, hits)

        return mapOf(
            "messageId" to chatId,
            "debugRequestId" to trace.requestId.toString(),
            "sessionId" to sessionId.toString(),
            "chatRoute" to if (finalIntent in setOf("greeting", "general")) "general" else "rag",
            "intent" to finalIntent,
            "intentNm" to intentName(finalIntent),
            "answerHtml" to answerHtml,
            // LLM 없이 정한 답(조회 0건·조건 오류) — 있으면 /api/ai/chat 이 모델을 부르지 않고 이 문장을 보낸다
            "fixedAnswer" to fixedAnswer,
            "blocks" to blocks,
            "blindFields" to blindKeys.sorted(),
            "blindAppliedCnt" to blindAppliedCnt,
            "agents" to agentNos.map { mapOf("no" to it) },
            "sources" to hits.map {
                mapOf(
                    "docId" to it["docId"],
                    "chunkId" to it["chunkId"],
                    "title" to it["title"],
                    "docType" to it["docType"],
                    "docDate" to it["docDate"],
                    "page" to it["page"],
                    "snippet" to it["snippet"],
                    "blinded" to (it["blinded"] == true),
                    "blindTags" to (it["blindTags"] ?: emptyList<String>())
                )
            },
            // 실적 DB 집계 근거 — 화면이 문서 근거보다 앞 번호([1]…)로 붙여 LLM 에 넘긴다
            "dataEvidence" to dataEvidence.map { mapOf("title" to it["title"], "text" to it["text"], "tool" to it["tool"], "args" to it["args"]) },
            "normalizedQuestion" to AiQuestionPrivacy.forStorage(normalized.normalizedText),
            // 열람자가 볼 수 없는 분류(고객사 등)의 용어는 응답·LLM [용어] 블록에서 뺀다 — 정규화 자체는 사전 전체를 쓴다 (R-18)
            "termReplacements" to visibleReplacements(normalized.replacements, principal),
            // 후속 질문 목록 — 화면에 칩으로 표시되어 클릭 시 즉시 탐색 가능
            "followups" to followups,
            "elapsedMs" to elapsedMs
        )
    }

    /**
     * `/ai/chat/ask` 가 질의 이력에 미리 적어 둔 고정 답 — 본인 이력이고 답이 비어 있지 않을 때만.
     * 화면은 ask 뒤 `/api/ai/chat` 에 messageId 를 넘기므로 이것으로 모델 호출을 건너뛴다.
     */
    @Transactional(readOnly = true)
    fun storedChatRoute(messageId: Long, userId: String): String =
        aiChatRepository.findChatLog(messageId)
            ?.takeIf { it["userId"] == userId }
            ?.get("intent")?.toString()
            .let { if (it in setOf("greeting", "general")) "general" else "rag" }

    @Transactional(readOnly = true)
    fun storedFixedAnswer(messageId: Long, userId: String): String? =
        aiChatRepository.findChatLog(messageId)
            ?.takeIf { it["userId"] == userId }
            ?.get("answer")?.toString()?.takeIf { it.isNotBlank() }

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
            "messages" to publicMessages(aiChatRepository.findSessionMessages(uuid, principal.userId))
        )
    }

    @Transactional(readOnly = true)
    fun getLatestSessionMessages(): Map<String, Any?> {
        val principal = UserContext.current()
        val sessionId = aiChatRepository.findLatestSessionId(principal.userId)
        return mapOf("sessionId" to sessionId?.toString(),
            "messages" to (sessionId?.let { publicMessages(aiChatRepository.findSessionMessages(it, principal.userId)) } ?: emptyList<Any>()))
    }

    private fun publicMessages(messages: List<Map<String, Any?>>): List<Map<String, Any?>> = messages.map { message ->
        message + ("html" to AiResponseSanitizer.publicText(message["html"] as? String))
    }

    /**
     * 새 대화를 시작한다. (No.16 — 기존 세션 맥락 초기화)
     *
     * 이력은 감사·학습 목적으로 보존하고 세션 연결만 해제한다.
     */
    @Transactional
    fun startNewSession(sessionId: String): Map<String, Any?> {
        val principal = UserContext.current()
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

        val updated = aiChatRepository.updateRating(messageId, ratingCd, principal.userId,
            request.comment?.trim()?.ifBlank { null }?.take(500))
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
                "question" to AiResponseSanitizer.publicText(chat["question"] as? String),
                "intent" to chat["intentNm"],
                // HTML 태그를 제거한 평문으로 저장한다.
                "answer" to AiResponseSanitizer.publicText(stripHtml(chat["answer"] as String?)),
                "elapsedMs" to chat["responseMs"]
            )
        )
        return chat to rows
    }

    // ---------------------------------------------------------------------------------
    // 내부 판정 로직
    // ---------------------------------------------------------------------------------

    /**
     * 키워드 기반으로 질의 의도를 분류한다.
     */
    /** 의도 코드의 한글 명칭 */
    private fun intentName(intent: String): String = when (intent) {
        "trend" -> "추이 분석"
        "trace" -> "이력 추적"
        "downtime" -> "비가동 조회"
        "metric" -> "지표 조회"
        "greeting", "general" -> "일반 대화"
        "denied" -> "권한 없음"
        else -> "미분류"
    }

    /**
     * 화면 렌더링용 블록(표·차트 등) 구성을 생성한다.
     * 실적 집계 데이터가 있으면 표 블록(type="table")을 생성하고, 근거 문서가 있으면 출처 블록(type="sources")을 생성한다.
     */
    private fun buildBlocks(
        intent: String,
        hits: List<Map<String, Any?>>,
        toolResult: AiDataToolService.EvidenceResult,
        principal: UserPrincipal
    ): List<MutableMap<String, Any?>> {
        val blocks = mutableListOf<MutableMap<String, Any?>>()
        val mask = MaskingSupport(principal)

        if (toolResult.executionCode == "OK" && toolResult.rawRows.isNotEmpty()) {
            if (toolResult.route == "PRODUCTION_COMPARE") {
                blocks += mutableMapOf(
                    "type" to "table",
                    "title" to "${toolResult.period?.from}~${toolResult.period?.to} 공장 전체 생산·불량 집계",
                    "head" to listOf("기간", "업무일", "전체 생산량", "불량 건수", "불량률", "불량률 변화"),
                    "rows" to toolResult.rawRows.map { r ->
                        val quantity = r["quantity"] as? BigDecimal
                        val defectQuantity = r["defectQuantity"] as? BigDecimal
                        val rate = (r["defectRate"] as? Number)?.toDouble()
                        val rateDelta = (r["defectRateDeltaPt"] as? Number)?.toDouble()
                        listOf(
                            r["label"]?.toString().orEmpty(),
                            "${r["from"]}~${r["to"]}",
                            quantity?.let { num(it) } ?: "비공개",
                            defectQuantity?.let { num(it) } ?: "비공개",
                            rate?.let { "%.2f%%".format(it) } ?: "비공개",
                            rateDelta?.let { "${if (it >= 0) "+" else ""}${"%.2f".format(it)}%p" } ?: "—"
                        )
                    }
                )
            } else if (toolResult.route == "AOI_DIMENSION_SUMMARY") {
                blocks += mutableMapOf(
                    "type" to "table",
                    "title" to "AOI 치수 집계",
                    "head" to listOf("구분", "설비", "측정 수", "불량 수", "불량률", "설명률"),
                    "rows" to toolResult.rawRows.map { r ->
                        listOf(r["scope"]?.toString().orEmpty(), r["equipment"]?.toString() ?: "전체",
                            mask.on(DataField.QTY) { r["measCnt"] }?.toString() ?: "비공개",
                            mask.on(DataField.QTY) { r["failCnt"] }?.toString() ?: "비공개",
                            mask.on(DataField.YIELD) { r["failRate"] }?.toString() ?: "비공개",
                            mask.on(DataField.YIELD) { r["explainedRate"] }?.toString() ?: "비공개")
                    }
                )
            } else if (toolResult.route == "DAILY_PRODUCT_DEFECT") {
                blocks += mutableMapOf(
                    "type" to "table",
                    "title" to "${toolResult.period?.from}~${toolResult.period?.to} 일자별 제품·불량 유형",
                    "head" to listOf("일자", "제품군", "제품명", "모델코드", "불량 유형", "불량 수량"),
                    "rows" to toolResult.rawRows.map { r ->
                        val qty = mask.on(DataField.QTY) { r["quantity"] as? BigDecimal }
                        listOf(r["date"]?.toString().orEmpty(), r["family"]?.toString() ?: "미매핑",
                            r["name"]?.toString() ?: r["code"]?.toString().orEmpty(),
                            r["code"]?.toString().orEmpty(), r["defect"]?.toString().orEmpty(),
                            qty?.let { num(it) } ?: "비공개")
                    }
                )
            } else if (toolResult.route == "PRODUCT_LIST") {
                if (toolResult.isDaily) {
                    blocks += mutableMapOf(
                        "type" to "table",
                        "title" to "${toolResult.period?.from}~${toolResult.period?.to} 일자별 생산 제품 목록",
                        "head" to listOf("일자", "제품명", "제품코드", "생산량"),
                        "rows" to toolResult.rawRows.map { r ->
                            val qty = mask.on(DataField.QTY) { r["quantity"] as? BigDecimal }
                            listOf(
                                r["date"]?.toString().orEmpty(),
                                r["name"]?.toString() ?: r["code"]?.toString().orEmpty(),
                                r["code"]?.toString().orEmpty(),
                                qty?.let { num(it) } ?: "비공개"
                            )
                        }
                    )
                } else {
                    blocks += mutableMapOf(
                        "type" to "table",
                        "title" to "${toolResult.period?.from}~${toolResult.period?.to} 생산 제품 목록",
                        "head" to listOf("제품명", "제품코드", "생산량"),
                        "rows" to toolResult.rawRows.map { r ->
                            val qty = mask.on(DataField.QTY) { r["quantity"] as? BigDecimal }
                            listOf(
                                r["name"]?.toString() ?: r["code"]?.toString().orEmpty(),
                                r["code"]?.toString().orEmpty(),
                                qty?.let { num(it) } ?: "비공개"
                            )
                        }
                    )
                }
            } else if (toolResult.route == "DEFECT_RATE_TOP") {
                blocks += mutableMapOf(
                    "type" to "table",
                    "title" to "${toolResult.period?.from}~${toolResult.period?.to} 제품별 불량률 순위",
                    "head" to listOf("순위", "제품명", "제품코드", "불량률", "생산량", "불량수량"),
                    "rows" to toolResult.rawRows.mapIndexed { idx, r ->
                        val qty = mask.on(DataField.QTY) { r["quantity"] as? BigDecimal }
                        val ng = mask.on(DataField.QTY) { r["defectQuantity"] as? BigDecimal }
                        listOf(
                            "${idx + 1}",
                            r["name"]?.toString() ?: r["code"]?.toString().orEmpty(),
                            r["code"]?.toString().orEmpty(),
                            "${r["defectRate"]}%",
                            qty?.let { num(it) } ?: "비공개",
                            ng?.let { num(it) } ?: "비공개"
                        )
                    }
                )
            } else if (toolResult.route == "DEFECT_TOP") {
                blocks += mutableMapOf(
                    "type" to "table",
                    "title" to "${toolResult.period?.from}~${toolResult.period?.to} 불량 유형별 순위",
                    "head" to listOf("순위", "불량 유형", "수량"),
                    "rows" to toolResult.rawRows.mapIndexed { idx, r ->
                        val qty = mask.on(DataField.QTY) { r["quantity"] as? BigDecimal }
                        listOf(
                            "${idx + 1}",
                            r["name"]?.toString().orEmpty(),
                            qty?.let { num(it) } ?: "비공개"
                        )
                    }
                )
            }
        }

        if (hits.isNotEmpty()) {
            blocks += mutableMapOf(
                "type" to "sources",
                "title" to "근거 자료",
                "columns" to listOf("title", "page", "date"),
                "rows" to hits.map { mutableMapOf<String, Any?>("title" to it["title"], "page" to it["page"], "date" to it["docDate"]) }
            )
        }
        return blocks
    }

    /**
     * 표 블록에 `blindColumns` 를 채우고, 권한 없는 열의 값을 null 로 바꾼다.
     */
    @Suppress("UNCHECKED_CAST")
    internal fun applyBlindColumns(
        block: MutableMap<String, Any?>,
        principal: com.dwje.api.common.security.UserPrincipal,
        maskedValues: MutableCollection<String>? = null
    ): Int {
        if (block["type"] != "sources") return 0
        val rows = (block["rows"] as? List<MutableMap<String, Any?>>).orEmpty()
        val columns = (block["columns"] as? List<String>) ?: rows.firstOrNull()?.keys?.toList().orEmpty()
        val blindColumns = dataFieldService.blindColumnsFor(columns)
        block["columns"] = columns
        block["blindColumns"] = blindColumns
        return dataFieldService.maskRows(rows, columns, blindColumns, principal, maskedValues)
    }

    private fun extractPreviousPeriod(chat: Map<String, Any?>?): AiBusinessPeriod? {
        if (chat == null) return null
        val evidenceSummary = chat["evidenceSummary"] as? String
        if (!evidenceSummary.isNullOrBlank()) {
            val m = Regex("""(\d{4}-\d{2}-\d{2})\s*~\s*(\d{4}-\d{2}-\d{2})""").find(evidenceSummary)
            if (m != null) {
                val from = runCatching { LocalDate.parse(m.groupValues[1]) }.getOrNull()
                val to = runCatching { LocalDate.parse(m.groupValues[2]) }.getOrNull()
                if (from != null && to != null && !from.isAfter(to)) {
                    return AiBusinessPeriod(from, to)
                }
            }
        }
        val q = chat["question"] as? String ?: return null
        val m = Regex("""(?:(\d{4})[년\-.]\s*)?(\d{1,2})월\s*(\d{1,2})일?.*?(?:(\d{4})[년\-.]\s*)?(\d{1,2})월\s*(\d{1,2})일?""").find(q)
            ?: return null
        val currentYear = LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).year
        val y1 = m.groupValues[1].toIntOrNull() ?: currentYear
        val m1 = m.groupValues[2].toIntOrNull() ?: return null
        val d1 = m.groupValues[3].toIntOrNull() ?: return null
        val y2 = m.groupValues[4].toIntOrNull() ?: y1
        val m2 = m.groupValues[5].toIntOrNull() ?: return null
        val d2 = m.groupValues[6].toIntOrNull() ?: return null
        val from = runCatching { LocalDate.of(y1, m1, d1) }.getOrNull() ?: return null
        val to = runCatching { LocalDate.of(y2, m2, d2) }.getOrNull() ?: return null
        return if (!from.isAfter(to)) AiBusinessPeriod(from, to) else null
    }

    private fun generateFollowups(
        question: String,
        finalIntent: String,
        toolResult: AiDataToolService.EvidenceResult,
        hits: List<Map<String, Any?>>
    ): List<String> {
        val followups = mutableListOf<String>()
        val period = toolResult.period

        if (toolResult.route == "PRODUCT_LIST" && period != null) {
            val topModel = toolResult.rawRows.firstOrNull()?.let { (it["name"] ?: it["code"])?.toString() }
            if (toolResult.isDaily) {
                followups += "${period.from}부터 ${period.to}까지 제품별 불량률 순위 조회"
                if (!topModel.isNullOrBlank()) {
                    followups += "${topModel} 제품의 공정별 생산 실적 및 수율 비교"
                }
                followups += "${period.from}부터 ${period.to}까지 발생한 주요 불량 원인 분석"
            } else {
                followups += "${period.from}부터 ${period.to}까지 생산된 제품 목록을 일자별로 정리"
                followups += "${period.from}부터 ${period.to}까지 제품별 불량률 상위 5종 조회"
                followups += "${period.from}부터 ${period.to}까지 발생한 주요 불량 유형 TOP 5"
            }
        } else if (toolResult.route == "DEFECT_RATE_TOP" && period != null) {
            val topModel = toolResult.rawRows.firstOrNull()?.let { (it["name"] ?: it["code"])?.toString() }
            if (!topModel.isNullOrBlank()) {
                followups += "${topModel} 관련 FACA 품질 개선 대책 보고서 검색"
                followups += "${topModel}의 주요 불량 유형(원인) 분석"
            }
            followups += "${period.from}부터 ${period.to}까지 생산된 제품 목록을 일자별로 정리"
            followups += "전월 대비 이번 달 불량률 및 생산량 증감 비교"
        } else if (toolResult.route == "DEFECT_TOP" && period != null) {
            val topDefect = toolResult.rawRows.firstOrNull()?.get("name")?.toString()
            if (!topDefect.isNullOrBlank()) {
                followups += "${topDefect} 불량이 가장 많이 발생한 공정은?"
                followups += "${topDefect} 관련 원인 분석 및 개선 대책서 검색"
            }
            followups += "${period.from}부터 ${period.to}까지 제품별 불량률 상위 목록 확인"
            followups += "${period.from}부터 ${period.to}까지 공정별 생산 실적 및 수율 비교"
        } else if (toolResult.route == "PRODUCTION_COMPARE" && period != null) {
            followups += "${period.from}부터 ${period.to}까지 제품별 불량률 상위 5종 조회"
            followups += "${period.from}부터 ${period.to}까지 생산된 제품 목록을 일자별로 정리"
            followups += "가장 불량이 많이 발생한 공정의 설비 이상 및 비가동 내역 확인"
        } else if (hits.isNotEmpty()) {
            val firstTitle = hits.firstOrNull()?.get("title")?.toString().orEmpty()
            if (firstTitle.contains("FACA") || firstTitle.contains("품질") || firstTitle.contains("개선")) {
                followups += "해당 조치에 대한 재발 방지 대책 및 점검 주기 검색"
                followups += "최근 1주일간 해당 공정의 불량률 및 생산 실적 조회"
                followups += "동일 불량 현상 관련 과거 클레임 보고서 확인"
            } else {
                followups += "관련 설비 점검 주기 및 표준 작업 가이드 검색"
                followups += "최근 1주일간 주요 생산 모델 목록 조회"
                followups += "이번 달 공장 전체 생산량 및 수율 조회"
            }
        } else {
            followups += "9월 20일 부터 9월 22일까지 생산된 제품 목록을 일자별로 정리"
            followups += "최근 1주일간 제품별 불량률 상위 5종 조회"
            followups += "이번 주 공장 전체 생산 실적 및 수율 비교"
        }

        return followups.filter { it != question.trim() }.distinct().take(3)
    }

    private fun num(v: BigDecimal?): String? = v?.let { "%,d".format(it.toLong()) }

    /** 세션 ID 문자열을 UUID 로 변환한다. (형식 오류 시 null) */
    private fun parseSessionId(value: String?): UUID? =
        value?.takeIf { it.isNotBlank() }?.let { runCatching { UUID.fromString(it.trim()) }.getOrNull() }

    /** HTML 태그를 제거해 평문으로 만든다. (파일 저장용) */
    private fun stripHtml(html: String?): String? =
        html?.replace(Regex("<[^>]*>"), " ")?.replace(Regex("\\s+"), " ")?.trim()
}
