package com.dwje.api.service

import com.dwje.api.common.util.AuditType
import com.dwje.api.common.exception.ResourceNotFoundException
import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.response.PageMeta
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.common.util.DateUtils
import com.dwje.api.common.util.MenuId
import com.dwje.api.common.util.PageRequestParam
import com.dwje.api.repository.AiChatRepository
import com.dwje.api.repository.VectorIndexRepository
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 자연어 질의 이력 서비스 (SY-08 · 08 기획서)
 *
 * 열람 범위 (V70) — 조회 엔드포인트마다 `scope` 를 받는다([viewerOf] 한 곳에서 판정).
 *   · scope=mine(기본) : 화면 권한 `chat-history`(자연어 질의 이력). 로그인한 계정 **본인** 질의만 — 통합관리자도 같다.
 *   · scope=all        : 화면 권한 `sys-chat-history`(시스템관리 › 전사 자연어 질의 이력). 전 사용자 질의.
 *   관리 기능(검토 저장 · 학습 답변 · 학습데이터 내보내기 · 디버그)은 `sys-chat-history` 쓰기 동작이다(requireWrite).
 * 값 가림 : 질의자보다 데이터 접근 권한이 좁은 열람자에게는 응답·판단 근거를 보이지 않는다(CHH-02). 이름 · 사번은 가리지 않는다.
 *          남의 이력을 본 조회는 감사 로그 RAW_VIEW, 응답을 가린 조회는 MASK 로 남긴다(CHH-06).
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
    private val askDebugRecorder: AiAskDebugRecorder,
    private val dataFieldService: DataFieldService,
    private val auditLogService: AuditLogService,
    private val appProperties: com.dwje.api.config.AppProperties = com.dwje.api.config.AppProperties()
) {

    companion object {
        /** 학습 답변 최대 길이 (V70) */
        const val TRAIN_ANSWER_MAX = 4000

        /** 질의 이력 목록 행의 근거 문서 요약 건수 */
        const val DOC_SUMMARY_TOP = 3

        /** scope 값 → 전사 범위(all) 여부. 비거나 mine 이면 false, 그 밖의 값은 400 */
        fun isAllScope(scope: String?): Boolean = when (scope?.trim()?.lowercase()?.ifEmpty { null } ?: "mine") {
            "mine" -> false
            "all" -> true
            else -> throw InvalidParameterException("조회 범위는 mine 또는 all 만 허용합니다. [$scope]", "scope")
        }

        /** scope 에 해당하는 화면 ID — mine = chat-history, all = sys-chat-history */
        fun menuOf(scope: String?): String = if (isAllScope(scope)) MenuId.SYS_CHAT_HISTORY else MenuId.CHAT_HISTORY
        private val REVIEW_CODES = setOf("USEFUL", "REASK", "BAD")
        private val RATING_FILTERS = setOf("USEFUL", "REASK", "BAD", "ALL")
        private val SOURCES = setOf("REVIEW_OR_USER", "REVIEW", "USER")
        private const val SESSION_MAX_DAYS = 92L
        /** 목록 필터 값 — 평가·검토 (NONE = 없음) */
        private val FILTER_CODES = setOf("USEFUL", "REASK", "BAD", "NONE")
        /** 「전체」 내려받기의 기간 하한 — 기간 조건을 두지 않는다(공통 10.6) */
        private val UNBOUNDED_FROM: LocalDate = LocalDate.of(2000, 1, 1)
        private const val NULL_KEYS_REASON = "이 기능 도입 전 질의라 질의자의 권한을 확인할 수 없어 응답을 표시하지 않습니다"
    }

    // ---------------------------------------------------------------------------------
    // 공용 판정
    // ---------------------------------------------------------------------------------

    /**
     * 열람자와 범위 (V70)
     *
     * @param all    전사 범위(scope=all) 여부
     * @param menu   판정·감사에 쓰는 화면 ID
     */
    data class Viewer(val p: UserPrincipal, val all: Boolean) {
        val menu: String get() = if (all) MenuId.SYS_CHAT_HISTORY else MenuId.CHAT_HISTORY
        /** 질의자 조건 — mine 이면 본인 사번, all 이면 없음 */
        val scopeUserId: String? get() = if (all) null else p.userId
        /** 관리 기능(검토 · 학습 답변 · 학습데이터 · 디버그) 표시 여부 — 전사 화면의 쓰기 동작 기준 */
        val canManage: Boolean get() = all && p.canWriteMenu(MenuId.SYS_CHAT_HISTORY)
    }

    /** LLM 응답 id(chatcmpl-…)는 전사 화면(scope=all)에서만 — 본인 화면에는 쓸모가 없고 서버 추적용이다 (V71) */
    private fun llmScope(row: Map<String, Any?>, v: Viewer): Map<String, Any?> = if (v.all) row else row - "llmRequestId"

    /** scope 를 판정하고 그 화면의 접근 권한을 확인한다 — mine 은 chat-history, all 은 sys-chat-history */
    private fun viewerOf(scope: String?): Viewer {
        val all = isAllScope(scope)
        return Viewer(authorizationService.requireMenu(if (all) MenuId.SYS_CHAT_HISTORY else MenuId.CHAT_HISTORY), all)
    }

    /**
     * 응답을 가릴 항목 — 질의자에게는 보였지만 열람자에게는 가려지는 데이터 항목. 가리지 않으면 null.
     * 질의자 본인 행·가릴 것이 없는 열람자는 가리지 않는다. 기록 이전 행(NULL)은 열람자에게 가려지는 항목을 모두 가린다.
     */
    @Suppress("UNCHECKED_CAST")
    private fun hiddenKeys(row: Map<String, Any?>, p: UserPrincipal, viewerBlind: Set<String>): Set<String>? {
        if (row["empNo"] == p.userId || viewerBlind.isEmpty()) return null
        val asker = row["blindFieldKeys"] as Set<String>? ?: return viewerBlind
        return (viewerBlind - asker).takeIf { it.isNotEmpty() }
    }

    /** 가린 행 표시 — 응답·판단 근거를 비우고 사유를 담는다. 질문은 그대로 둔다 */
    @Suppress("UNCHECKED_CAST")
    private fun applyHidden(row: Map<String, Any?>, hidden: Set<String>?): Map<String, Any?> {
        if (hidden == null) return row + mapOf("answerHidden" to false, "answerHiddenReason" to null)
        val names = dataFieldService.appliedFieldsCached().associate { it["key"] as String to it["name"] as String }
        val reason = if (row["blindFieldKeys"] == null) NULL_KEYS_REASON
            else "질의자보다 데이터 접근 권한이 좁아 응답을 표시하지 않습니다 (가려지는 항목: ${hidden.sorted().joinToString(", ") { names[it] ?: it }})"
        return row + mapOf("answer" to null, "judgmentBasis" to null, "answerHidden" to true, "answerHiddenReason" to reason)
    }

    /** 공개용 문장 정리 — 내부 조회 정보(스키마·SQL)는 숨긴다 */
    private fun publicRow(row: Map<String, Any?>): Map<String, Any?> = row + mapOf(
        "question" to AiResponseSanitizer.publicText(row["question"] as? String),
        "answer" to AiResponseSanitizer.publicText(row["answer"] as? String),
        "judgmentBasis" to (AiResponseSanitizer.publicText(row["judgmentBasis"] as? String) ?: "판단 근거 기록 없음"),
        "unansweredReason" to (row["unansweredReason"] ?: if ((row["answer"] as? String).isNullOrBlank()) "응답이 기록되지 않았습니다." else null)
    )

    /** 응답을 가린 조회의 감사 1행 (CHH-02) */
    private fun auditMask(menu: String, rows: List<Pair<Map<String, Any?>, Set<String>?>>, desc: String) {
        val hidden = rows.mapNotNull { it.second }
        if (hidden.isEmpty()) return
        auditLogService.record(
            logType = AuditType.MASK, menuId = menu, fieldKey = hidden.flatten().toSortedSet().joinToString(",").take(30),
            targetDesc = "질의 이력 응답 가림", resultCd = "BLIND", maskedCnt = hidden.size, remark = desc.take(500)
        )
    }

    /** 남의 이력을 본 조회의 감사 1행 (CHH-06) */
    private fun auditView(menu: String, targetDesc: String, remark: String? = null) =
        auditLogService.record(logType = AuditType.RAW_VIEW, menuId = menu, targetDesc = targetDesc.take(200), resultCd = "ALLOW", remark = remark)

    // =================================================================================
    // SY-08. 자연어 질의 이력
    // =================================================================================

    /** 질의 이력 요약 (No.186) */
    @Transactional(readOnly = true)
    fun getChatHistorySummary(
        from: String?, to: String?, userGroup: String?, cond: HistoryCond = HistoryCond(), scope: String? = null
    ): Map<String, Any?> {
        val v = viewerOf(scope)
        val (fromDate, toDate) = boundedPeriod(from, to)

        // canManage — 관리 기능(검토·학습 답변·학습데이터 내보내기·디버그) 표시 여부. mine 은 늘 false (V70)
        return aiChatRepository.findHistorySummary(fromDate, toDate, filterOf(v, userGroup, cond)) + mapOf(
            "scope" to if (v.all) "all" else "mine",
            "canManage" to v.canManage,
            // 보존 일수(0 = 파기 안 함, CHH-08) · 목표 답변율(CHH-11)
            "retentionDays" to appProperties.ai.chatRetentionDays.coerceAtLeast(0),
            // 보존 기간이 지나 다음 정리 때 지워질 질의 수 — 조회 조건과 무관한 전체 (R-20, 첫 실행 전에 보여 준다)
            "expiredCnt" to appProperties.ai.chatRetentionDays.takeIf { it > 0 }?.let { days ->
                aiChatRepository.countBefore(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).minusDays(days.toLong()).atStartOfDay())
            }.let { it ?: 0L },
            "targetAnswerRate" to appProperties.ai.targetAnswerRate
        )
    }

    /**
     * 질의 목록 조회 조건 (08 CHH-10) — 화면 값 그대로 받아 [filterOf] 가 검증한다.
     *
     * @param answered Y | N
     */
    data class HistoryCond(
        val keyword: String? = null,
        val rating: String? = null,
        val review: String? = null,
        val answered: String? = null,
        val empNo: String? = null
    )

    /** 조회 조건 검증 → 저장소 조건. 부서 · 질의자 사번 조건은 전사 범위(all)에서만 쓴다 — mine 은 본인 질의뿐이다 */
    private fun filterOf(v: Viewer, userGroup: String?, c: HistoryCond): AiChatRepository.HistoryFilter {
        fun code(v: String?, field: String, label: String): String? = v?.trim()?.uppercase()?.ifEmpty { null }?.also {
            if (it !in FILTER_CODES) throw InvalidParameterException("$label 값은 USEFUL/REASK/BAD/NONE 만 허용합니다.", field)
        }
        val answered = c.answered?.trim()?.uppercase()?.ifEmpty { null }?.also {
            if (it !in setOf("Y", "N")) throw InvalidParameterException("응답 여부는 Y 또는 N 만 허용합니다.", "answered")
        }
        return AiChatRepository.HistoryFilter(
            userGroup = userGroup?.takeIf { v.all }, keyword = c.keyword, rating = code(c.rating, "rating", "평가"),
            review = code(c.review, "review", "검토"), answered = answered,
            empNo = c.empNo?.takeIf { v.all }, scopeUserId = v.scopeUserId
        )
    }

    /** 조회 기간 — 92일 이내 (CHH-10). 「전체」 내려받기는 이 함수를 쓰지 않는다 */
    private fun boundedPeriod(from: String?, to: String?): Pair<LocalDate, LocalDate> {
        val (fromDate, toDate) = DateUtils.periodOf(from, to)
        if (ChronoUnit.DAYS.between(fromDate, toDate) + 1 > SESSION_MAX_DAYS) {
            throw InvalidParameterException("조회 기간은 92일 이내로 지정해 주세요.", "to")
        }
        return fromDate to toDate
    }

    /** 질의 이력 부서 선택지 — 기간 안 부서별 질의 건수. mine 은 본인 질의 범위 */
    @Transactional(readOnly = true)
    fun getChatHistoryGroups(from: String?, to: String?, scope: String? = null): Map<String, Any?> {
        val v = viewerOf(scope)
        val (fromDate, toDate) = boundedPeriod(from, to)
        return mapOf("items" to aiChatRepository.findHistoryGroups(fromDate, toDate, v.scopeUserId))
    }

    /** 질의 이력 목록 결과 — 가린 행 수(maskedRowCnt)를 함께 준다 */
    data class HistoryPage(val rows: List<Map<String, Any?>>, val meta: PageMeta, val maskedRowCnt: Int)

    /** 질의 이력 조회 (No.187) */
    @Transactional(readOnly = true)
    fun getChatHistory(
        from: String?,
        to: String?,
        userGroup: String?,
        page: Int?,
        size: Int?,
        cond: HistoryCond = HistoryCond(),
        exporting: Boolean = false,
        scope: String? = null
    ): HistoryPage {
        val v = viewerOf(scope)
        val principal = v.p

        // 「전체」 내려받기(exporting)는 기간을 두지 않고 감사는 내려받기 끝에서 한 번만 남긴다(CHH-06·07)
        val (fromDate, toDate) = if (exporting) UNBOUNDED_FROM to LocalDate.now().plusDays(1) else boundedPeriod(from, to)
        val filter = filterOf(v, userGroup, cond)
        val paging = PageRequestParam.of(page, size)

        val total = aiChatRepository.countHistory(fromDate, toDate, filter)
        val viewerBlind = dataFieldService.blindKeysFor(principal)
        val judged = aiChatRepository.findHistory(fromDate, toDate, filter, paging.limit, paging.offset).map { row ->
            row to hiddenKeys(row, principal, viewerBlind)
        }
        // 가림 판정(질의자 사번 필요) → 공개 문장 정리 → 응답 가림 순서. 이름 · 사번은 가리지 않는다(V70)
        // 근거 문서 요약(상세 hits 와 같은 원천) — 상위 3건 · 건수. 제목·소제목은 열람자 권한으로 가린다(상세와 같다)
        val hitSummaries = vectorIndexRepository.findHitSummaries(judged.map { it.first["messageId"] as Long }, DOC_SUMMARY_TOP)
        val rows = judged.map { (row, hidden) ->
            val (top, docCnt) = hitSummaries[row["messageId"] as Long] ?: (emptyList<Map<String, Any?>>() to 0)
            val docs = top.map { h ->
                val m = h.toMutableMap(); dataFieldService.maskHit(m, principal)
                mapOf("title" to m["title"], "page" to m["page"], "score" to m["score"])
            }
            llmScope(applyHidden(publicRow(row), hidden) - "blindFieldKeys", v) + mapOf("docs" to docs, "docCnt" to docCnt)
        }

        if (!exporting) {
            val desc = "from=$fromDate, to=$toDate, userGroup=${userGroup ?: "전체"}, keyword=${cond.keyword ?: "-"}, " +
                "rating=${filter.rating ?: "-"}, review=${filter.review ?: "-"}, answered=${filter.answered ?: "-"}, page=${paging.page}"
            if (judged.any { it.first["empNo"] != principal.userId }) auditView(v.menu, "질의 이력 목록", "$desc, rows=${rows.size}")
            auditMask(v.menu, judged, desc)
        }

        return HistoryPage(rows, PageMeta.of(paging.page, paging.size, total), judged.count { it.second != null })
    }

    /** 질의 상세 조회 (No.188) */
    @Transactional(readOnly = true)
    fun getChatDetail(messageId: Long, scope: String? = null): Map<String, Any?> {
        // 상세는 범위 화면의 접근 권한으로 연다. 디버그 진단만 전사 화면의 관리 기능이다(V70).
        val v = viewerOf(scope)
        val principal = v.p
        val canManage = v.canManage

        val chat = aiChatRepository.findChatLog(messageId)
            ?.let { it + mapOf("empNo" to it["userId"]) }
            ?: throw ResourceNotFoundException("질의를 찾을 수 없습니다. [messageId=$messageId]")
        // 범위 밖(mine 인데 남의 질의)은 있는지조차 알리지 않는다
        v.scopeUserId?.let { if (chat["userId"] != it) throw ResourceNotFoundException("질의를 찾을 수 없습니다. [messageId=$messageId]") }

        // 근거 문서 — 열람자 권한으로 발췌·제목을 가린다(CHH-05). 판단 근거가 비었을 때의 대체 문구에도 같은 목록을 쓴다
        val hits = vectorIndexRepository.findQueryDetail(messageId)?.get("queryId")?.let { it as? Long }
            ?.let { vectorIndexRepository.findQueryHits(it) }.orEmpty().take(8)
            .map { h ->
                val m = h.toMutableMap()
                dataFieldService.maskHit(m, principal)
                mapOf("docId" to m["docId"], "title" to m["title"], "page" to m["page"], "score" to m["score"],
                    "cited" to m["cited"], "heading" to m["heading"])
            }
        val basis = (chat["evidenceSummary"] as? String)?.takeIf { it.isNotBlank() }
            ?: hits.mapNotNull { it["title"] as? String }.distinct().joinToString("; ").ifBlank { "판단 근거 기록 없음" }
        val hidden = hiddenKeys(chat, principal, dataFieldService.blindKeysFor(principal))

        val base = mapOf(
            "messageId" to messageId,
            "empNo" to chat["userId"],
            "name" to chat["userNm"],
            "dept" to chat["deptNm"],
            "ts" to chat["askedAt"],
            "askedAt" to chat["askedAt"],
            "sessionKey" to (chat["sessionId"] ?: "chat-$messageId"),
            "hits" to hits,
            "question" to chat["question"],
            "answer" to chat["answer"],
            "judgmentBasis" to basis,
            "unansweredReason" to chat["unansweredReason"],
            "evaluationCriteria" to "근거 부합성·질문 충족 여부·응답 적시성",
            "elapsedMs" to chat["responseMs"],
            "maskedCnt" to chat["maskedCnt"],
            "rating" to chat["rating"],
            "ratingComment" to chat["ratingComment"],
            "review" to chat["review"],
            "reviewNm" to chat["reviewNm"],
            "reviewComment" to chat["reviewComment"],
            "reviewedBy" to chat["reviewedBy"],
            "reviewedAt" to chat["reviewedAt"],
            "answeredAt" to chat["answeredAt"],
            "trainAnswer" to chat["trainAnswer"],
            "trainAnswerAt" to chat["trainAnswerAt"],
            "trainAnswerBy" to chat["trainAnswerBy"],
            "trainAnswerByNm" to chat["trainAnswerByNm"],
            "intentNm" to chat["intentNm"],
            "llmModel" to chat["llmModel"],
            "finishReason" to chat["finishReason"],
            "promptTokens" to chat["promptTokens"],
            "completionTokens" to chat["completionTokens"],
            "totalTokens" to chat["totalTokens"],
            "llmMs" to chat["llmMs"],
            "llmRequestId" to chat["llmRequestId"],
            "docCnt" to hits.size,
            "blindFieldKeys" to chat["blindFieldKeys"]
        )
        val masked = llmScope(applyHidden(publicRow(base), hidden) - "blindFieldKeys", v)
        // 디버그 진단은 전사 화면 관리 기능(canManage)일 때만 — 아니면 키 자체가 없다
        val result = masked + ("userName" to masked["name"]) +
            (if (canManage) mapOf("debug" to askDebugRecorder.byChatId(messageId)) else emptyMap())

        if (chat["userId"] != principal.userId) auditView(v.menu, "질의 상세 messageId=$messageId 질의자=${chat["userId"]}")
        auditMask(v.menu, listOf(chat to hidden), "messageId=$messageId")
        return result
    }

    /**
     * 관리자 검토 저장 (08 CHH-04) — 질의자 평가와 따로 둔다. 다시 저장하면 덮어쓰고 이전 값은 감사 로그에 남긴다.
     */
    @Transactional
    fun saveReview(messageId: Long, reviewCd: String, comment: String?): Map<String, Any?> {
        val principal = authorizationService.requireWrite(MenuId.SYS_CHAT_HISTORY)
        val code = reviewCd.trim().uppercase()
        if (code !in REVIEW_CODES) throw InvalidParameterException("검토 값은 USEFUL/REASK/BAD 만 허용합니다.", "reviewCd")
        val chat = aiChatRepository.findChatLog(messageId)
            ?: throw ResourceNotFoundException("질의를 찾을 수 없습니다. [messageId=$messageId]")

        val reviewedAt = aiChatRepository.updateReview(messageId, code, comment?.trim()?.ifBlank { null }, principal.userId)
            ?: throw ResourceNotFoundException("질의를 찾을 수 없습니다. [messageId=$messageId]")
        auditLogService.record(
            logType = AuditType.RAW_VIEW, menuId = MenuId.SYS_CHAT_HISTORY,
            targetDesc = "질의 검토 messageId=$messageId 질의자=${chat["userId"]}", resultCd = "ALLOW",
            remark = "review ${chat["review"] ?: "없음"}/${(chat["reviewComment"] as String?).orEmpty()} → $code".take(500)
        )
        return mapOf("messageId" to messageId, "review" to code, "reviewedBy" to principal.userId, "reviewedAt" to reviewedAt)
    }

    /**
     * 학습 답변 저장 (V70) — 전사 자연어 질의 이력의 「답변 추가(학습 데이터)」 칸.
     * 빈 문자열·공백만이면 지운다. 다시 저장하면 덮어쓰고 이전 값의 길이는 감사 로그에 남긴다(검토 저장과 같은 방식).
     * 학습데이터 내보내기는 이 답변이 있는 질의를 넣고 응답 대신 이 답변을 쓴다.
     */
    @Transactional
    fun saveTrainAnswer(messageId: Long, answer: String?): Map<String, Any?> {
        val principal = authorizationService.requireWrite(MenuId.SYS_CHAT_HISTORY)
        val text = answer?.trim()?.ifEmpty { null }
        if (text != null && text.length > TRAIN_ANSWER_MAX) {
            throw InvalidParameterException("학습 답변은 ${TRAIN_ANSWER_MAX}자 이내로 입력해 주세요.", "answer")
        }
        val chat = aiChatRepository.findChatLog(messageId)
            ?: throw ResourceNotFoundException("질의를 찾을 수 없습니다. [messageId=$messageId]")

        val saved = aiChatRepository.updateTrainAnswer(messageId, text, principal.userId)
            ?: throw ResourceNotFoundException("질의를 찾을 수 없습니다. [messageId=$messageId]")
        val before = (chat["trainAnswer"] as String?)?.length?.let { "${it}자" } ?: "없음"
        val after = text?.length?.let { "${it}자" } ?: "삭제"
        auditLogService.record(
            logType = AuditType.RAW_VIEW, menuId = MenuId.SYS_CHAT_HISTORY,
            targetDesc = "질의 학습 답변 messageId=$messageId 질의자=${chat["userId"]}", resultCd = "ALLOW",
            remark = "trainAnswer $before → $after"
        )
        return mapOf("messageId" to messageId) + saved
    }

    // ---------------------------------------------------------------------------------
    // 세션별 조회 (08 CHH-18, 2차 결정 R-12)
    // ---------------------------------------------------------------------------------

    /**
     * 세션 목록 — 최근 질의 순. 기간은 92일 이내.
     *
     * @param empNo 질의자 사번 — 전사 범위(all)에서만 쓰고, mine 이면 무시한다
     */
    @Transactional(readOnly = true)
    fun getChatSessions(
        from: String?, to: String?, userGroup: String?, empNo: String?, keyword: String?, page: Int?, size: Int?,
        exporting: Boolean = false, cond: HistoryCond = HistoryCond(), scope: String? = null
    ): Pair<List<Map<String, Any?>>, PageMeta> {
        val v = viewerOf(scope)
        val principal = v.p
        val (fromDate, toDate) = if (exporting) UNBOUNDED_FROM to LocalDate.now().plusDays(1) else boundedPeriod(from, to)
        val filter = filterOf(v, userGroup, cond.copy(keyword = keyword ?: cond.keyword, empNo = empNo ?: cond.empNo))
        val viewerBlind = dataFieldService.blindKeysFor(principal)
        val paging = PageRequestParam.of(page, size)

        val total = aiChatRepository.countSessions(fromDate, toDate, filter, principal.userId, viewerBlind)
        val raw = aiChatRepository.findSessions(fromDate, toDate, filter, principal.userId, viewerBlind, paging.limit, paging.offset)
        val rows = raw.map { it + mapOf("firstQuestion" to AiResponseSanitizer.publicText(it["firstQuestion"] as String?)) }
        if (!exporting && raw.any { it["empNo"] != principal.userId }) {
            auditView(v.menu, "질의 세션 목록", "from=$fromDate, to=$toDate, userGroup=${userGroup ?: "전체"}, page=${paging.page}, rows=${rows.size}")
        }
        return rows to PageMeta.of(paging.page, paging.size, total)
    }

    /** 세션 상세 — 그 세션의 질의·응답을 시간순으로. 키는 session_id 또는 `chat-{messageId}`(세션 없는 질의) */
    @Transactional(readOnly = true)
    fun getChatSession(sessionKey: String, scope: String? = null): Map<String, Any?> {
        val v = viewerOf(scope)
        val principal = v.p
        val notFound = { ResourceNotFoundException("세션을 찾을 수 없습니다. [sessionKey=$sessionKey]") }
        val turns = Regex("^chat-(\\d+)$").find(sessionKey)?.let { aiChatRepository.findSessionTurns(null, it.groupValues[1].toLong()) }
            ?: runCatching { UUID.fromString(sessionKey) }.getOrNull()?.let { aiChatRepository.findSessionTurns(it, null) }
            ?: throw notFound()
        if (turns.isEmpty()) throw notFound()
        val owner = turns.first()
        v.scopeUserId?.let { if (owner["empNo"] != it) throw notFound() }

        val viewerBlind = dataFieldService.blindKeysFor(principal)
        val judged = turns.map { it to hiddenKeys(it, principal, viewerBlind) }
        val shaped = judged.map { (row, hidden) ->
            applyHidden(publicRow(row), hidden).filterKeys {
                it in setOf("messageId", "askedAt", "question", "answer", "answerHidden", "answerHiddenReason", "judgmentBasis",
                    "unansweredReason", "responseSec", "rating", "ratingComment", "review", "reviewNm", "reask",
                    "answeredAt", "trainAnswer", "trainAnswerAt", "trainAnswerBy", "trainAnswerByNm",
                    "intentNm", "llmModel", "finishReason", "promptTokens", "completionTokens", "totalTokens", "llmMs", "llmRequestId")
            }.let { llmScope(it, v) }
        }
        val head = mapOf("sessionKey" to sessionKey, "sessionId" to owner["sessionId"], "empNo" to owner["empNo"], "name" to owner["name"],
            "dept" to owner["dept"], "startedAt" to turns.first()["askedAt"], "lastAskedAt" to turns.last()["askedAt"])
        if (owner["empNo"] != principal.userId) auditView(v.menu, "세션 상세 sessionKey=$sessionKey 질의자=${owner["empNo"]}")
        auditMask(v.menu, judged, "sessionKey=$sessionKey")
        return head + mapOf("turns" to shaped)
    }

    @Transactional(readOnly = true)
    fun getAskDebug(requestId: String): Map<String, Any?> {
        // 디버그 진단 조회는 전사 화면의 관리 기능 — 쓰기 동작으로 판정한다(V70)
        authorizationService.requireWrite(MenuId.SYS_CHAT_HISTORY)
        val id = runCatching { UUID.fromString(requestId) }
            .getOrElse { throw InvalidParameterException("요청 ID 형식이 올바르지 않습니다.", "requestId") }
        return askDebugRecorder.byRequestId(id) ?: throw ResourceNotFoundException("진단 기록을 찾을 수 없습니다.")
    }

    /**
     * 학습데이터 내보내기 결과
     *
     * @param blindCnt 열람자 권한으로 응답을 볼 수 없어 뺀 샘플 수
     */
    data class TrainsetResult(
        val lines: List<String>, val blindCnt: Int, val from: String, val to: String, val rating: String, val source: String
    )

    /**
     * 학습데이터 내보내기 대상 (No.189, 08 CHH-03)
     *
     * - 평가: 기본 USEFUL. 출처 기본 REVIEW_OR_USER(관리자 검토가 있으면 검토, 없으면 질의자 평가). ALL 은 평가가 있는 건 전체.
     * - 질문은 정규화 문장이 아니라 **원문**(저장 시 가림 규칙을 한 번 더 거친다), 빈 답·가린 답은 뺀다.
     * - 열람자 권한으로 응답을 볼 수 없는 샘플은 빼고 [TrainsetResult.blindCnt] 로 센다(CHH-02 와 같은 규칙).
     * - 학습 답변(V70)이 있는 질의는 평가와 관계없이 넣고 응답 대신 학습 답변을 쓴다(meta.source = TRAIN_ANSWER).
     * - 0건이면 404 — 빈 파일을 내려주지 않는다.
     */
    @Transactional(readOnly = true)
    fun getTrainsetLines(from: String?, to: String?, ratingFilter: String?, source: String? = null): TrainsetResult {
        // 학습데이터 내보내기는 질의·응답 원문 전체를 반출하는 전사 화면의 관리 기능이다(V70)
        val principal = authorizationService.requireWrite(MenuId.SYS_CHAT_HISTORY)
        val rating = (ratingFilter?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: "USEFUL")
        if (rating !in RATING_FILTERS) throw InvalidParameterException("평가 필터는 USEFUL/REASK/BAD/ALL 만 허용합니다.", "ratingFilter")
        val src = source?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: "REVIEW_OR_USER"
        if (src !in SOURCES) throw InvalidParameterException("출처는 REVIEW_OR_USER/REVIEW/USER 만 허용합니다.", "source")
        val (fromDate, toDate) = DateUtils.periodOf(from, to, 90)

        val viewerBlind = dataFieldService.blindKeysFor(principal)
        var blindCnt = 0
        val lines = aiChatRepository.findTrainsetRows(fromDate, toDate, rating.takeIf { it != "ALL" }, src, AiResponseSanitizer.HIDDEN, 10000)
            .mapNotNull { row ->
                if (hiddenKeys(row, principal, viewerBlind) != null) { blindCnt++; return@mapNotNull null }
                val question = AiResponseSanitizer.publicText(AiQuestionPrivacy.forStorage(row["question"] as String? ?: ""))
                val trainAnswer = (row["trainAnswer"] as? String)?.trim()?.ifEmpty { null }
                val answer = trainAnswer ?: AiResponseSanitizer.publicText(stripHtml(row["answer"] as? String))
                if (question.isNullOrBlank() || answer.isNullOrBlank() ||
                    question == AiResponseSanitizer.HIDDEN || answer == AiResponseSanitizer.HIDDEN) return@mapNotNull null
                // JSONL 한 줄에 한 샘플(prompt/completion)을 담는다.
                objectMapper.writeValueAsString(
                    mapOf(
                        "messages" to listOf(mapOf("role" to "user", "content" to question), mapOf("role" to "assistant", "content" to answer)),
                        "meta" to mapOf("chatId" to row["chatId"], "rating" to row["rating"], "ratingSource" to row["ratingSource"],
                            "source" to if (trainAnswer != null) "TRAIN_ANSWER" else "ANSWER")
                    )
                )
            }
        if (lines.isEmpty()) throw ResourceNotFoundException("내보낼 학습 샘플이 없습니다. 기간이나 평가 조건을 바꿔 주세요.")
        return TrainsetResult(lines, blindCnt, fromDate.toString(), toDate.toString(), rating, src)
    }

    /** HTML 태그를 제거해 학습 샘플로 정리한다. */
    private fun stripHtml(html: String?): String? =
        html?.replace(Regex("<[^>]*>"), " ")?.replace(Regex("\\s+"), " ")?.trim()
}
