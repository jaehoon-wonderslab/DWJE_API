package com.dwje.api.service

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.exception.ResourceNotFoundException
import com.dwje.api.common.security.UserContext
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
    private val aiDataToolService: AiDataToolService
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

        // 1. 세션 확보 — 미지정 시 새 세션을 생성한다.
        val sessionId = parseSessionId(request.sessionId) ?: UUID.randomUUID()
        val question = request.question.trim()

        // 2. 용어 정규화 — 현장 유사어를 공식 용어로 치환한다.
        val normalized = glossaryNormalizer.normalize(question)

        // 3. 질의는 막지 않는다 — 데이터 권한은 결과에서 값만 가린다. (2026-09-16 요청자 결정)
        val blindKeys = dataFieldService.blindKeysFor(principal)

        // 4. 의도 분류
        val intent = classifyIntent(normalized.normalizedText)

        // 5. 근거 문서 검색 — 부서 열람 권한이 있는 문서만(통합관리자는 전부). 데이터 항목 권한으로는 빼지 않고 출력에서 발췌를 가린다.
        val hits = aiChatRepository.searchDocumentChunks(normalized.normalizedText, principal.deptId, SEARCH_TOP_K, principal.superAdmin)
            .map { it.toMutableMap() }
        var blindAppliedCnt = hits.sumOf { dataFieldService.maskHit(it, principal) }

        // 5-1. 수치 질문이면 실적 DB 집계를 근거로 붙인다 — 문서에는 월별 불량률 같은 수치가 없다.
        //      집계는 조회자의 데이터 권한으로 가린다. 실패해도 문서 근거로는 답한다.
        //      원래 질문으로 판단한다 — 용어 정규화가 "불량" 을 다른 공식 용어(예: ISSUE)로 바꿔 수치 질문임을 놓친다.
        val dataEvidence = runCatching { aiDataToolService.evidenceFor(question, principal) }
            .onFailure { log.warn("질의 집계 근거 실패 : {}", it.toString()) }
            .getOrDefault(emptyList())

        // 6. 근거가 없으면 답을 추정하지 않고 자료 소재를 안내한다. (unknown)
        val finalIntent = if (hits.isEmpty() && dataEvidence.isEmpty() && intent != "metric") "unknown" else intent

        // 7. 표 블록 — 열별 항목 key(blindColumns) 를 채우고 권한 없는 열은 값을 비운다. 가린 값은 문장 마스킹에도 쓴다.
        val blocks = buildBlocks(finalIntent, hits)
        val blindValues = linkedSetOf<String>()
        blindAppliedCnt += blocks.sumOf { applyBlindColumns(it, principal, blindValues) }

        // 8. 문장 — 권한 없는 항목의 값만 「비공개」로. 문장 구조는 그대로다.
        // 답 문장은 여기서 만들지 않는다 — 사내 LLM(/api/ai/chat)이 이 근거로 쓰고, 받은 답을 이 이력에 저장한다.
        // 예전에는 "「질문」에 대한 분석 결과입니다…" 같은 고정 문장을 답으로 저장·반환했다(LLM 없이). 그 자리는 비워 둔다.
        val answerHtml: String? = null

        // 가린 것이 있으면 감사 로그(MASK) 한 건 — 공통 규약 6. 질의는 막지 않았으므로 결과 코드는 MASKED 다.
        if (blindAppliedCnt > 0) {
            auditLogService.record(
                logType = "MASK", menuId = "ai-chat", fieldKey = blindKeys.sorted().joinToString(",").take(30),
                targetDesc = question.take(300), resultCd = "MASKED", maskedCnt = blindAppliedCnt, remark = "자연어 질의 결과 값 마스킹"
            )
        }

        // 9. 질의 이력 기록
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

        // 10. 참여 Agent 및 검색 이력 기록
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

        return mapOf(
            "messageId" to chatId,
            "sessionId" to sessionId.toString(),
            "intent" to finalIntent,
            "intentNm" to intentName(finalIntent),
            "answerHtml" to answerHtml,
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
            "normalizedQuestion" to normalized.normalizedText,
            "termReplacements" to normalized.replacements,
            // 후속 질의는 답을 본 LLM 이 만든다(POST /api/ai/followups). 의도별 고정 문장은 두지 않는다.
            "followups" to emptyList<Any>(),
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
    internal fun applyBlindColumns(
        block: MutableMap<String, Any?>,
        principal: com.dwje.api.common.security.UserPrincipal,
        maskedValues: MutableCollection<String>? = null
    ): Int {
        val rows = (block["rows"] as? List<MutableMap<String, Any?>>).orEmpty()
        val columns = (block["columns"] as? List<String>) ?: rows.firstOrNull()?.keys?.toList().orEmpty()
        val blindColumns = dataFieldService.blindColumnsFor(columns)
        block["columns"] = columns
        block["blindColumns"] = blindColumns
        return dataFieldService.maskRows(rows, columns, blindColumns, principal, maskedValues)
    }

    /** 세션 ID 문자열을 UUID 로 변환한다. (형식 오류 시 null) */
    private fun parseSessionId(value: String?): UUID? =
        value?.takeIf { it.isNotBlank() }?.let { runCatching { UUID.fromString(it.trim()) }.getOrNull() }

    /** HTML 태그를 제거해 평문으로 만든다. (파일 저장용) */
    private fun stripHtml(html: String?): String? =
        html?.replace(Regex("<[^>]*>"), " ")?.replace(Regex("\\s+"), " ")?.trim()
}
