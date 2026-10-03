package com.dwje.api.repository

import com.dwje.api.common.util.Rs
import com.dwje.api.common.util.SqlLikeUtils
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.util.UUID

/**
 * 자연어 질의(AI 채팅) 데이터 접근 Repository (AI-01, SY-08)
 *
 * 참조 테이블
 * - 질의/응답 이력 : ax.tb_ai_chat_log, ax.tb_ai_chat_agent
 * - 검색 이력      : vec.tb_query_log, vec.tb_query_hit, vec.tb_doc_chunk, vec.tb_doc
 * - 용어/마스킹    : ax.tb_gls_term, ax.tb_gls_variant, ax.tb_ai_mask_rule
 */
@Repository
class AiChatRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) {

    /**
     * V43은 API 시작 때 자동 적용되지 않는다. 적용 전에도 기본 채팅 계약을 유지한다.
     * 있다는 결과만 기억한다 — 없다는 결과를 기억하면 재기동 전까지 V43 적용을 알아채지 못한다 (08 CHH-13).
     */
    @Volatile private var basisColumnsSeen = false
    private fun hasBasisColumns(): Boolean {
        if (basisColumnsSeen) return true
        val sql = """
            SELECT count(*) FROM information_schema.columns
            WHERE table_schema = 'ax' AND table_name = 'tb_ai_chat_log'
              AND column_name IN ('evidence_summary', 'unanswered_reason')
        """.trimIndent()
        basisColumnsSeen = jdbcTemplate.queryForObject(sql, MapSqlParameterSource(), Int::class.java) == 2
        return basisColumnsSeen
    }

    /** V55(가림·검토·응답 구간 컬럼 8개)가 적용됐는지. 적용 전 서버에서도 덕반장 AI 질의가 500 이 나지 않게 한다 */
    @Volatile private var reviewColumnsSeen = false
    internal fun hasReviewColumns(): Boolean {
        if (reviewColumnsSeen) return true
        val sql = """
            SELECT count(*) FROM information_schema.columns
            WHERE table_schema = 'ax' AND table_name = 'tb_ai_chat_log'
              AND column_name IN ('blind_field_keys', 'rated_at', 'rating_comment', 'review_cd',
                                  'review_comment', 'reviewed_by', 'reviewed_at', 'ask_ms')
        """.trimIndent()
        reviewColumnsSeen = jdbcTemplate.queryForObject(sql, MapSqlParameterSource(), Int::class.java) == 8
        return reviewColumnsSeen
    }

    /** V70(학습 답변 컬럼 3개)이 적용됐는지. 적용 전 서버에서도 질의 이력 조회가 500 이 나지 않게 한다 */
    @Volatile private var trainColumnsSeen = false
    internal fun hasTrainColumns(): Boolean {
        if (trainColumnsSeen) return true
        val sql = """
            SELECT count(*) FROM information_schema.columns
            WHERE table_schema = 'ax' AND table_name = 'tb_ai_chat_log'
              AND column_name IN ('train_answer', 'train_answer_by', 'train_answer_at')
        """.trimIndent()
        trainColumnsSeen = jdbcTemplate.queryForObject(sql, MapSqlParameterSource(), Int::class.java) == 3
        return trainColumnsSeen
    }

    /**
     * 답변 시각 · 학습 답변 SELECT 조각 (V70) — 목록·상세·세션 공용. 적용 전이면 같은 이름의 NULL 로 채운다.
     * 답변 시각 = 질의 시각 + 응답 시간(response_ms). 응답 시간이 없으면 NULL.
     */
    private fun trainSelect(): String {
        val answeredAt = "c.asked_at + c.response_ms * interval '1 millisecond' AS answered_at"
        return if (hasTrainColumns())
            "$answeredAt, c.train_answer, c.train_answer_by, c.train_answer_at, " +
                "(SELECT tu.user_nm FROM ax.tb_sys_user tu WHERE tu.user_id = c.train_answer_by) AS train_answer_by_nm"
        else "$answeredAt, NULL::text AS train_answer, NULL::varchar AS train_answer_by, NULL::timestamptz AS train_answer_at, " +
            "NULL::varchar AS train_answer_by_nm"
    }

    /** V71(LLM 응답 메타 컬럼 7개)이 적용됐는지. 적용 전 서버에서도 답 저장·이력 조회가 500 이 나지 않게 한다 */
    @Volatile private var llmMetaColumnsSeen = false
    internal fun hasLlmMetaColumns(): Boolean {
        if (llmMetaColumnsSeen) return true
        val sql = """
            SELECT count(*) FROM information_schema.columns
            WHERE table_schema = 'ax' AND table_name = 'tb_ai_chat_log'
              AND column_name IN ('llm_model', 'llm_finish_reason', 'prompt_tokens', 'completion_tokens',
                                  'total_tokens', 'llm_ms', 'llm_request_id')
        """.trimIndent()
        llmMetaColumnsSeen = jdbcTemplate.queryForObject(sql, MapSqlParameterSource(), Int::class.java) == 7
        return llmMetaColumnsSeen
    }

    /** LLM 응답 메타 SELECT 조각 (V71) — 적용 전이면 같은 이름의 NULL. 의도 명칭(intent_nm)도 함께 낸다 */
    private fun llmSelect(): String = "c.intent_nm, " + if (hasLlmMetaColumns())
        "c.llm_model, c.llm_finish_reason, c.prompt_tokens, c.completion_tokens, c.total_tokens, c.llm_ms, c.llm_request_id"
    else "NULL::varchar AS llm_model, NULL::varchar AS llm_finish_reason, NULL::int AS prompt_tokens, " +
        "NULL::int AS completion_tokens, NULL::int AS total_tokens, NULL::int AS llm_ms, NULL::varchar AS llm_request_id"

    /** [llmSelect] 행 매핑 — `llmRequestId` 는 서비스가 scope=all 에서만 남긴다 */
    private fun llmFields(rs: java.sql.ResultSet): Map<String, Any?> = mapOf(
        "intentNm" to rs.getString("intent_nm"),
        "llmModel" to rs.getString("llm_model"),
        "finishReason" to rs.getString("llm_finish_reason"),
        "promptTokens" to Rs.intOrNull(rs, "prompt_tokens"),
        "completionTokens" to Rs.intOrNull(rs, "completion_tokens"),
        "totalTokens" to Rs.intOrNull(rs, "total_tokens"),
        "llmMs" to Rs.intOrNull(rs, "llm_ms"),
        "llmRequestId" to rs.getString("llm_request_id")
    )

    /** [trainSelect] 행 매핑 */
    private fun trainFields(rs: java.sql.ResultSet): Map<String, Any?> = mapOf(
        "answeredAt" to Rs.dateTime(rs, "answered_at"),
        "trainAnswer" to rs.getString("train_answer"),
        "trainAnswerAt" to Rs.dateTime(rs, "train_answer_at"),
        "trainAnswerBy" to rs.getString("train_answer_by"),
        "trainAnswerByNm" to rs.getString("train_answer_by_nm")
    )

    /** 가린 항목 집합 → text[] 리터럴. 빈 집합은 '{}' (NULL 은 「기록 이전」 이라 구분한다) */
    private fun textArray(keys: Collection<String>): String =
        keys.filter { it.matches(Regex("[a-z0-9_]+")) }.sorted().joinToString(",", "{", "}")

    /** text[] 컬럼 → 집합 (NULL 은 null 로 보존) */
    private fun readKeys(rs: java.sql.ResultSet, column: String): Set<String>? =
        (rs.getArray(column)?.array as? Array<*>)?.map { it.toString() }?.toSet()

    /** V55 컬럼 SELECT 조각 — 적용 전이면 같은 이름의 NULL 로 채운다 */
    private fun reviewSelect(): String = if (hasReviewColumns())
        "c.blind_field_keys, c.review_cd, c.review_comment, c.reviewed_by, c.reviewed_at, c.rating_comment, c.rated_at"
    else "NULL::text[] AS blind_field_keys, NULL::varchar AS review_cd, NULL::varchar AS review_comment, " +
        "NULL::varchar AS reviewed_by, NULL::timestamptz AS reviewed_at, NULL::varchar AS rating_comment, NULL::timestamptz AS rated_at"

    /** V55 컬럼 행 매핑 — 목록·상세·세션 공용 */
    private fun reviewFields(rs: java.sql.ResultSet): Map<String, Any?> = mapOf(
        "blindFieldKeys" to readKeys(rs, "blind_field_keys"),
        "review" to rs.getString("review_cd"),
        "reviewNm" to REVIEW_NAMES[rs.getString("review_cd")],
        "reviewComment" to rs.getString("review_comment"),
        "reviewedBy" to rs.getString("reviewed_by"),
        "reviewedAt" to Rs.dateTime(rs, "reviewed_at"),
        "ratingComment" to rs.getString("rating_comment"),
        "ratedAt" to Rs.dateTime(rs, "rated_at")
    )

    /**
     * 질의 로그를 등록한다. (No.14 — 자연어 질의 요청)
     *
     * @param sessionId          대화 세션 ID
     * @param userId             사번
     * @param deptNm             부서명
     * @param question           원문 질의
     * @param normalizedQuestion 용어 정규화된 질의
     * @param intentCd           의도 코드 (denied|unknown|trend|trace|downtime|metric)
     * @param intentNm           의도 명칭
     * @param answer             응답 본문(HTML)
     * @param responseMs         응답 소요 시간(ms)
     * @param blindAppliedCnt    마스킹 적용 건수
     * @param profileId          응답에 사용된 서빙 프로파일 ID
     * @return 생성된 질의 로그 ID (messageId)
     */
    fun insertChatLog(
        sessionId: UUID,
        userId: String,
        deptNm: String?,
        question: String,
        normalizedQuestion: String?,
        intentCd: String,
        intentNm: String?,
        answer: String?,
        responseMs: Int,
        blindAppliedCnt: Int,
        profileId: Int?,
        prevChatId: Long?,
        evidenceSummary: String?,
        unansweredReason: String?,
        blindFieldKeys: Set<String>? = null,
        askMs: Int? = null
    ): Long {
        val basisColumns = hasBasisColumns()
        // 질의자에게 가린 데이터 항목(CHH-02)과 근거 수집 구간(ask_ms)은 V55 가 있을 때만 쓴다
        val reviewColumns = blindFieldKeys != null && hasReviewColumns()
        val extraColumns = (if (basisColumns) ", evidence_summary, unanswered_reason" else "") +
            (if (reviewColumns) ", blind_field_keys, ask_ms" else "")
        val extraValues = (if (basisColumns) ", :evidenceSummary, :unansweredReason" else "") +
            (if (reviewColumns) ", CAST(:blindFieldKeys AS text[]), :askMs" else "")
        val sql = """
            INSERT INTO ax.tb_ai_chat_log (
                session_id, asked_at, user_id, dept_nm, question, normalized_question,
                intent_cd, intent_nm, answer, response_ms, is_reask, prev_chat_id,
                blind_applied_cnt, profile_id$extraColumns
            ) VALUES (
                :sessionId, now(), :userId, :deptNm, :question, :normalizedQuestion,
                :intentCd, :intentNm, :answer, :responseMs, :isReask, :prevChatId,
                :blindAppliedCnt, :profileId$extraValues
            )
            RETURNING chat_id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("sessionId", sessionId)
            .addValue("userId", userId)
            .addValue("deptNm", deptNm)
            .addValue("question", question)
            .addValue("normalizedQuestion", normalizedQuestion)
            .addValue("intentCd", intentCd)
            .addValue("intentNm", intentNm)
            .addValue("answer", answer)
            .addValue("responseMs", responseMs)
            .addValue("isReask", prevChatId != null)
            .addValue("prevChatId", prevChatId)
            .addValue("blindAppliedCnt", blindAppliedCnt)
            .addValue("profileId", profileId)
            .addValue("evidenceSummary", evidenceSummary)
            .addValue("unansweredReason", unansweredReason)
            .addValue("blindFieldKeys", blindFieldKeys?.let { textArray(it) })
            .addValue("askMs", askMs)

        return jdbcTemplate.queryForObject(sql, params, Long::class.java) ?: 0L
    }

    /**
     * 질의에 참여한 Agent 를 기록한다.
     *
     * @param chatId   질의 로그 ID
     * @param agentIds 참여 Agent ID 목록 (호출 순서대로)
     */
    fun insertChatAgents(chatId: Long, agentIds: List<Int>) {
        if (agentIds.isEmpty()) return

        val sql = """
            INSERT INTO ax.tb_ai_chat_agent (chat_id, agent_id, call_seq, elapsed_ms)
            VALUES (:chatId, :agentId, :callSeq, :elapsedMs)
            ON CONFLICT (chat_id, agent_id, call_seq) DO NOTHING
        """.trimIndent()

        val batch = agentIds.mapIndexed { idx, agentId ->
            MapSqlParameterSource()
                .addValue("chatId", chatId)
                .addValue("agentId", agentId)
                .addValue("callSeq", idx + 1)
                .addValue("elapsedMs", null)
        }.toTypedArray()

        jdbcTemplate.batchUpdate(sql, batch)
    }

    /**
     * 사내 LLM(`/api/ai/chat`)이 스트리밍으로 쓴 답을 질의 이력에 덮어쓴다.
     *
     * `/ai/chat/ask` 는 문서 검색·권한·이력 기록까지만 하고 답변 칸에는 검색 요약을 적는다.
     * 화면이 그 검색 결과를 근거로 LLM 에 다시 묻고, 받은 답을 여기로 돌려준다 —
     * 그래야 대화 복원·질의 이력 화면이 사용자가 실제로 본 답을 보여 준다.
     * 남의 이력을 고치지 못하게 `user_id` 를 함께 건다.
     *
     * @return 고친 행 수 (0 이면 없는 ID 이거나 남의 이력)
     */
    fun updateLlmAnswer(chatId: Long, userId: String, answer: String, responseMs: Int, meta: LlmMeta? = null): Int {
        val reasonUpdate = if (hasBasisColumns())
            ", unanswered_reason = CASE WHEN :answer = '' THEN '모델 응답이 생성되지 않았습니다.' ELSE NULL END" else ""
        // LLM 응답 메타(V71) — 컬럼이 없으면 건너뛴다. 값이 없는 항목(서버가 안 준 usage 등)은 NULL
        val metaUpdate = if (meta != null && hasLlmMetaColumns()) """,
                   llm_model = :llmModel, llm_finish_reason = :finishReason,
                   prompt_tokens = :promptTokens, completion_tokens = :completionTokens, total_tokens = :totalTokens,
                   llm_ms = :llmMs, llm_request_id = :llmRequestId""" else ""
        val sql = """
            UPDATE ax.tb_ai_chat_log
               SET answer      = :answer,
                   response_ms = ${if (hasReviewColumns()) "coalesce(ask_ms, 0) + :responseMs" else ":responseMs"}$reasonUpdate$metaUpdate
             WHERE chat_id = :chatId
               AND user_id = :userId
        """.trimIndent()
        return jdbcTemplate.update(
            sql,
            MapSqlParameterSource()
                .addValue("chatId", chatId)
                .addValue("userId", userId)
                .addValue("answer", answer)
                .addValue("responseMs", responseMs)
                .addValue("llmModel", meta?.model?.take(100))
                .addValue("finishReason", meta?.finishReason?.take(30))
                .addValue("promptTokens", meta?.promptTokens)
                .addValue("completionTokens", meta?.completionTokens)
                .addValue("totalTokens", meta?.totalTokens)
                .addValue("llmMs", meta?.elapsedMs)
                .addValue("llmRequestId", meta?.requestId?.take(100))
        )
    }

    /** LLM 응답 메타 — 스트리밍 조각의 model · id · finish_reason · usage 와 호출 시간 (V71) */
    data class LlmMeta(
        val model: String? = null,
        val requestId: String? = null,
        val finishReason: String? = null,
        val promptTokens: Int? = null,
        val completionTokens: Int? = null,
        val totalTokens: Int? = null,
        val elapsedMs: Int? = null
    )

    /**
     * 세션 단위 대화 이력을 조회한다. (No.15)
     *
     * @param sessionId 세션 ID
     * @param userId    사번 — 본인 세션만 조회 가능
     */
    fun findSessionMessages(sessionId: UUID, userId: String): List<Map<String, Any?>> {
        val sql = """
            SELECT
                chat_id,
                question,
                answer,
                intent_cd,
                intent_nm,
                asked_at,
                response_ms,
                rating_cd,
                blind_applied_cnt
            FROM ax.tb_ai_chat_log
            WHERE session_id = :sessionId
              AND user_id    = :userId
            ORDER BY asked_at, chat_id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("sessionId", sessionId)
            .addValue("userId", userId)

        // 한 건의 로그에서 사용자 질문과 AI 응답 두 개의 메시지를 생성한다.
        val messages = mutableListOf<Map<String, Any?>>()
        jdbcTemplate.query(sql, params) { rs ->
            val ts = Rs.dateTime(rs, "asked_at")
            messages.add(
                mapOf(
                    "messageId" to rs.getLong("chat_id"),
                    "who" to "user",
                    "html" to rs.getString("question"),
                    "intent" to null,
                    "ts" to ts
                )
            )
            messages.add(
                mapOf(
                    "messageId" to rs.getLong("chat_id"),
                    "who" to "ai",
                    "html" to rs.getString("answer"),
                    "intent" to rs.getString("intent_cd"),
                    "intentNm" to rs.getString("intent_nm"),
                    "elapsedMs" to Rs.intOrNull(rs, "response_ms"),
                    "rating" to rs.getString("rating_cd"),
                    "maskedCnt" to rs.getInt("blind_applied_cnt"),
                    "ts" to ts
                )
            )
        }
        return messages
    }

    /**
     * 단건 질의 로그를 조회한다.
     *
     * @param chatId 질의 로그 ID (messageId)
     */
    fun findChatLog(chatId: Long): Map<String, Any?>? {
        val basisSelect = if (hasBasisColumns()) "c.evidence_summary, c.unanswered_reason" else
            "NULL::text AS evidence_summary, NULL::text AS unanswered_reason"
        val sql = """
            SELECT
                c.chat_id, c.session_id, c.user_id, c.dept_nm, c.question, c.normalized_question,
                c.intent_cd, c.intent_nm, c.answer, c.response_ms, c.rating_cd,
                c.blind_applied_cnt, c.profile_id, c.asked_at, c.is_reask, $basisSelect, ${reviewSelect()},
                ${trainSelect()}, ${llmSelect().removePrefix("c.intent_nm, ")}, u.user_nm
            FROM ax.tb_ai_chat_log c
            LEFT JOIN ax.tb_sys_user u ON u.user_id = c.user_id
            WHERE c.chat_id = :chatId
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource("chatId", chatId)) { rs, _ ->
            mapOf(
                "chatId" to rs.getLong("chat_id"),
                "sessionId" to rs.getString("session_id"),
                "userId" to rs.getString("user_id"),
                "deptNm" to rs.getString("dept_nm"),
                "question" to rs.getString("question"),
                "normalizedQuestion" to rs.getString("normalized_question"),
                "intent" to rs.getString("intent_cd"),
                "intentNm" to rs.getString("intent_nm"),
                "answer" to rs.getString("answer"),
                "responseMs" to Rs.intOrNull(rs, "response_ms"),
                "rating" to rs.getString("rating_cd"),
                "maskedCnt" to rs.getInt("blind_applied_cnt"),
                "profileId" to Rs.intOrNull(rs, "profile_id"),
                "askedAt" to Rs.dateTime(rs, "asked_at")
                ,"evidenceSummary" to rs.getString("evidence_summary")
                ,"unansweredReason" to rs.getString("unanswered_reason")
                ,"userNm" to rs.getString("user_nm")
                ,"reask" to rs.getBoolean("is_reask")
            ) + reviewFields(rs) + trainFields(rs) + llmFields(rs)
        }.firstOrNull()
    }

    /**
     * 세션의 마지막 질의 ID 를 조회한다. (재질의 연결용)
     */
    fun findLastChatId(sessionId: UUID, userId: String): Long? {
        val sql = """
            SELECT chat_id
            FROM ax.tb_ai_chat_log
            WHERE session_id = :sessionId AND user_id = :userId
            ORDER BY asked_at DESC, chat_id DESC
            LIMIT 1
        """.trimIndent()

        val params = MapSqlParameterSource().addValue("sessionId", sessionId).addValue("userId", userId)
        return jdbcTemplate.query(sql, params) { rs, _ -> rs.getLong("chat_id") }.firstOrNull()
    }

    /** 이 사용자에게 저장된 가장 최근 대화 세션. 다른 사용자의 세션은 조회하지 않는다. */
    fun findLatestSessionId(userId: String): UUID? {
        val sql = """
            SELECT session_id FROM ax.tb_ai_chat_log
            WHERE user_id = :userId AND session_id IS NOT NULL
            ORDER BY asked_at DESC, chat_id DESC LIMIT 1
        """.trimIndent()
        return jdbcTemplate.query(sql, MapSqlParameterSource("userId", userId)) { rs, _ ->
            rs.getObject("session_id", UUID::class.java)
        }.firstOrNull()
    }

    /**
     * 추천 질의 목록을 조회한다. (No.17 — 현장 빈출 질의 기반)
     *
     * 최근 30일간 '유용' 평가를 받았거나 재질의 없이 종료된 질의를 빈도순으로 집계한다.
     * 사용자가 입력한 원문으로 묶는다 — 정규화 문장은 화면에 보이지 않는 표현이라 추천으로 쓰지 않는다 (07 GLS-04).
     *
     * @param limit 조회 건수
     */
    fun findSuggestions(limit: Int): List<Map<String, Any?>> {
        val sql = """
            SELECT
                c.question                      AS q,
                max(c.intent_nm)                AS intent_nm,
                count(*)                        AS ask_cnt,
                round(avg(c.response_ms))       AS avg_ms
            FROM ax.tb_ai_chat_log c
            WHERE c.asked_at >= now() - interval '30 days'
              AND c.question IS NOT NULL
              AND c.intent_cd NOT IN ('denied', 'unknown')
              AND (c.rating_cd IS NULL OR c.rating_cd = 'USEFUL')
            GROUP BY c.question
            ORDER BY count(*) DESC, max(c.asked_at) DESC
            LIMIT :limit
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource("limit", limit)) { rs, _ ->
            mapOf(
                "q" to rs.getString("q"),
                "desc" to rs.getString("intent_nm"),
                "askCnt" to rs.getLong("ask_cnt"),
                "avgMs" to Rs.intOrNull(rs, "avg_ms")
            )
        }
    }

    /**
     * 응답 평가를 기록한다. (No.19 — 파인튜닝 학습데이터 후보)
     *
     * @param chatId    질의 로그 ID
     * @param ratingCd  AI_CHAT_RATING — USEFUL / REASK / BAD
     * @param userId    평가자 사번 (본인 질의만 평가 가능)
     * @return 갱신 건수
     */
    fun updateRating(chatId: Long, ratingCd: String, userId: String, comment: String? = null): Int {
        // 평가 시각·의견(V55, CHH-04) — 적용 전 서버에서는 평가 값만 남긴다
        val extra = if (hasReviewColumns()) ", rated_at = now(), rating_comment = :comment" else ""
        val sql = """
            UPDATE ax.tb_ai_chat_log
               SET rating_cd = :ratingCd$extra
             WHERE chat_id = :chatId
               AND user_id = :userId
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("chatId", chatId)
            .addValue("ratingCd", ratingCd)
            .addValue("userId", userId)
            .addValue("comment", comment)

        return jdbcTemplate.update(sql, params)
    }

    /**
     * 관리자 검토 저장 (08 CHH-04) — 질의자 평가(rating_cd)와 따로 둔다. 다시 검토하면 덮어쓴다.
     *
     * @return 저장 시각 (질의가 없으면 null)
     */
    fun updateReview(chatId: Long, reviewCd: String, comment: String?, reviewerId: String): String? =
        jdbcTemplate.query(
            """
            UPDATE ax.tb_ai_chat_log
               SET review_cd = :cd, review_comment = :comment, reviewed_by = :by, reviewed_at = now()
             WHERE chat_id = :chatId
            RETURNING reviewed_at
            """.trimIndent(),
            MapSqlParameterSource().addValue("cd", reviewCd).addValue("comment", comment)
                .addValue("by", reviewerId).addValue("chatId", chatId)
        ) { rs, _ -> Rs.dateTime(rs, "reviewed_at") }.firstOrNull()

    /**
     * 학습 답변 저장 (V70) — 전사 자연어 질의 이력에서 관리자가 적는 「이렇게 답해야 했다」 답변.
     * [answer] 가 null 이면 지운다(작성자 · 시각도 함께 비운다). 다시 저장하면 덮어쓴다.
     *
     * @return 저장 뒤 값(trainAnswer · trainAnswerAt · trainAnswerBy · trainAnswerByNm). 질의가 없으면 null
     */
    fun updateTrainAnswer(chatId: Long, answer: String?, writerId: String): Map<String, Any?>? =
        jdbcTemplate.query(
            """
            UPDATE ax.tb_ai_chat_log c
               SET train_answer    = CAST(:answer AS text),
                   train_answer_by = CASE WHEN CAST(:answer AS text) IS NULL THEN NULL ELSE :by END,
                   train_answer_at = CASE WHEN CAST(:answer AS text) IS NULL THEN NULL ELSE now() END
             WHERE c.chat_id = :chatId
            RETURNING c.train_answer, c.train_answer_by, c.train_answer_at,
                      (SELECT tu.user_nm FROM ax.tb_sys_user tu WHERE tu.user_id = c.train_answer_by) AS train_answer_by_nm
            """.trimIndent(),
            MapSqlParameterSource().addValue("answer", answer).addValue("by", writerId).addValue("chatId", chatId)
        ) { rs, _ ->
            mapOf(
                "trainAnswer" to rs.getString("train_answer"),
                "trainAnswerAt" to Rs.dateTime(rs, "train_answer_at"),
                "trainAnswerBy" to rs.getString("train_answer_by"),
                "trainAnswerByNm" to rs.getString("train_answer_by_nm")
            )
        }.firstOrNull()

    /**
     * 세션 대화 로그를 삭제한다. (No.16 — 새 대화 시작 시 세션 맥락 초기화)
     *
     * 이력 자체는 감사·학습 목적으로 보존해야 하므로 세션 ID 만 분리한다.
     *
     * @return 분리된 건수
     */
    fun detachSession(sessionId: UUID, userId: String): Int {
        val sql = """
            UPDATE ax.tb_ai_chat_log
               SET session_id = NULL
             WHERE session_id = :sessionId
               AND user_id    = :userId
        """.trimIndent()

        val params = MapSqlParameterSource().addValue("sessionId", sessionId).addValue("userId", userId)
        return jdbcTemplate.update(sql, params)
    }

    /**
     * 사용자 부서가 열람 가능한 문서 청크를 하이브리드 검색한다. (RAG 근거 확보)
     *
     * 벡터 임베딩은 배치가 채우므로, 본 API 에서는 전문 검색(tsv)과 유사도(trigram)로
     * 후보를 뽑아 근거 문서를 제시한다. 부서 열람 권한이 없는 문서는 후보에서 제외한다.
     *
     * ## 낱말은 OR 로 묶는다
     * `plainto_tsquery` 는 낱말을 **모두 AND** 로 묶는다. "프레스 금형 관리 기준 알려줘" 면 `알려줘` 까지
     * 문서에 있어야 걸려 자연어 질문은 늘 0건이었다(2026-09-23 실측 — "금형" 한 낱말은 8건).
     * 낱말을 OR 로 묶고 순위는 `ts_rank` 에 맡긴다 — 많이 겹치는 청크가 위로 온다.
     *
     * @param queryText 정규화된 질의문
     * ## 통합관리자는 모든 문서를 본다
     * 메뉴·데이터 항목과 같은 규칙이다([com.dwje.api.common.security.UserPrincipal.superAdmin]).
     * 부서 열람 권한표(`vec.tb_doc_dept_perm`)에 행을 채워 주는 방식으로 하지 않는다 — 문서가 새로
     * 적재될 때마다 빠뜨리게 된다(2026-09-23: 문서 1,476건이 경영진·전산팀에만 열려 통합관리자 질의가 늘 근거 없음).
     *
     * @param deptId     조회자 부서 ID
     * @param topK       반환 건수
     * @param superAdmin 통합관리자 — 부서 열람 권한을 보지 않는다
     */
    fun searchDocumentChunks(queryText: String, deptId: Int, topK: Int, superAdmin: Boolean = false): List<Map<String, Any?>> {
        // 문서에 붙은 데이터 항목 태그(vec.tb_doc_data_field)를 함께 낸다. 권한 없는 항목이 태그된 문서도
        // 근거에서 빼지 않는다(빼면 답이 틀려진다) — 서비스가 출력 단계에서 발췌를 가린다.
        val sql = """
            SELECT
                ch.chunk_id,
                ch.doc_id,
                ch.chunk_seq,
                ch.heading,
                ch.page_no,
                left(ch.chunk_text, 500)                                   AS snippet,
                (SELECT string_agg(df.field_key, ',') FROM vec.tb_doc_data_field df WHERE df.doc_id = d.doc_id) AS field_tags,
                d.title,
                d.doc_type_cd,
                d.doc_date,
                ts_rank(ch.tsv, to_tsquery('simple', :tsQuery))           AS ts_score
            FROM vec.tb_doc_chunk ch
            INNER JOIN vec.tb_doc d ON d.doc_id = ch.doc_id
            WHERE ch.del_flg    = 'N'
              AND ch.is_current = true
              AND d.del_flg     = 'N'
              AND ch.tsv @@ to_tsquery('simple', :tsQuery)
              AND (
                    :superAdmin
                 OR d.scope_cd = 'ALL'
                 OR d.owner_dept_id = :deptId
                 OR EXISTS (
                        SELECT 1 FROM vec.tb_doc_dept_perm p
                         WHERE p.doc_id = d.doc_id AND p.dept_id = :deptId AND p.can_read = true
                    )
              )
            ORDER BY ts_score DESC, d.doc_date DESC NULLS LAST
            LIMIT :topK
        """.trimIndent()

        val tsQuery = toOrTsQuery(queryText) ?: return emptyList()
        val params = MapSqlParameterSource()
            .addValue("tsQuery", tsQuery)
            .addValue("superAdmin", superAdmin)
            .addValue("deptId", deptId)
            .addValue("topK", topK)

        return jdbcTemplate.query(sql, params) { rs, _ ->
            mapOf(
                "chunkId" to rs.getLong("chunk_id"),
                "docId" to rs.getLong("doc_id"),
                "title" to rs.getString("title"),
                "docType" to rs.getString("doc_type_cd"),
                "docDate" to Rs.dateTime(rs, "doc_date"),
                "heading" to rs.getString("heading"),
                "page" to Rs.intOrNull(rs, "page_no"),
                "snippet" to rs.getString("snippet"),
                "fieldTags" to (rs.getString("field_tags")?.split(",") ?: emptyList()),
                "score" to Rs.doubleOrNull(rs, "ts_score")
            )
        }
    }

    /**
     * 검색 실행 이력을 vec.tb_query_log 에 기록한다.
     *
     * @return 생성된 query_id
     */
    fun insertQueryLog(
        chatId: Long,
        userId: String,
        deptId: Int,
        queryText: String,
        normalizedText: String?,
        topK: Int,
        poolCnt: Int,
        hitCnt: Int,
        citedCnt: Int,
        blockedDocCnt: Int,
        totalMs: Int
    ): Long {
        val sql = """
            INSERT INTO vec.tb_query_log (
                chat_id, asked_at, user_id, dept_id, query_text, normalized_text,
                top_k, pool_cnt, hit_cnt, cited_cnt, blocked_doc_cnt, total_ms
            ) VALUES (
                :chatId, now(), :userId, :deptId, :queryText, :normalizedText,
                :topK, :poolCnt, :hitCnt, :citedCnt, :blockedDocCnt, :totalMs
            )
            RETURNING query_id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("chatId", chatId)
            .addValue("userId", userId)
            .addValue("deptId", deptId)
            .addValue("queryText", queryText)
            .addValue("normalizedText", normalizedText)
            .addValue("topK", topK)
            .addValue("poolCnt", poolCnt)
            .addValue("hitCnt", hitCnt)
            .addValue("citedCnt", citedCnt)
            .addValue("blockedDocCnt", blockedDocCnt)
            .addValue("totalMs", totalMs)

        return jdbcTemplate.queryForObject(sql, params, Long::class.java) ?: 0L
    }

    /**
     * 검색 히트 상세를 vec.tb_query_hit 에 기록한다.
     */
    fun insertQueryHits(queryId: Long, hits: List<Map<String, Any?>>) {
        if (hits.isEmpty()) return

        val sql = """
            INSERT INTO vec.tb_query_hit (
                query_id, chunk_id, doc_id, rank_no, ts_score, final_seq, is_cited
            ) VALUES (
                :queryId, :chunkId, :docId, :rankNo, :tsScore, :finalSeq, true
            )
            ON CONFLICT (query_id, chunk_id) DO NOTHING
        """.trimIndent()

        val batch = hits.mapIndexed { idx, hit ->
            MapSqlParameterSource()
                .addValue("queryId", queryId)
                .addValue("chunkId", hit["chunkId"])
                .addValue("docId", hit["docId"])
                .addValue("rankNo", idx + 1)
                .addValue("tsScore", (hit["score"] as? Double)?.toFloat())
                .addValue("finalSeq", idx + 1)
        }.toTypedArray()

        jdbcTemplate.batchUpdate(sql, batch)
    }

    /**
     * Agent 코드(①~⑨)로 agent_id 를 조회한다.
     */
    fun findAgentIdsByNo(agentNos: List<String>): List<Int> {
        if (agentNos.isEmpty()) return emptyList()

        val sql = """
            SELECT agent_id
            FROM ax.tb_ai_agent
            WHERE agent_no = ANY(:agentNos)
              AND use_flg  = 'Y'
            ORDER BY sort_seq
        """.trimIndent()

        val params = MapSqlParameterSource("agentNos", agentNos.toTypedArray())
        return jdbcTemplate.query(sql, params) { rs, _ -> rs.getInt("agent_id") }
    }

    /**
     * 질의에 참여한 Agent 정보를 조회한다.
     */
    fun findChatAgents(chatId: Long): List<Map<String, Any?>> {
        val sql = """
            SELECT a.agent_no, a.agent_nm, ca.call_seq, ca.elapsed_ms
            FROM ax.tb_ai_chat_agent ca
            INNER JOIN ax.tb_ai_agent a ON a.agent_id = ca.agent_id
            WHERE ca.chat_id = :chatId
            ORDER BY ca.call_seq
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource("chatId", chatId)) { rs, _ ->
            mapOf(
                "no" to rs.getString("agent_no"),
                "name" to rs.getString("agent_nm"),
                "seq" to rs.getInt("call_seq"),
                "elapsedMs" to Rs.intOrNull(rs, "elapsed_ms")
            )
        }
    }

    /**
     * 현재 서비스 중(ACTIVE)인 서빙 프로파일 ID 를 조회한다.
     */
    fun findActiveProfileId(): Int? {
        val sql = """
            SELECT profile_id
            FROM ax.tb_ai_serving_profile
            WHERE service_cd = 'CHAT' AND state_cd = 'ACTIVE'
            ORDER BY activated_at DESC NULLS LAST
            LIMIT 1
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource()) { rs, _ -> rs.getInt("profile_id") }.firstOrNull()
    }

    // =================================================================================
    // SY-08. 자연어 질의 이력
    // =================================================================================

    /**
     * 질의 이력 요약을 조회한다. (No.186)
     *
     * @param from      조회 시작일
     * @param to        조회 종료일
     * @param userGroup 부서명
     */
    fun findHistorySummary(from: LocalDate, to: LocalDate, filter: HistoryFilter = HistoryFilter()): Map<String, Any?> {
        val reviewCounts = if (hasReviewColumns()) """
                count(*) FILTER (WHERE c.review_cd IS NOT NULL)                       AS reviewed_cnt,
                count(*) FILTER (WHERE c.review_cd = 'USEFUL')                        AS review_useful_cnt,
                count(*) FILTER (WHERE c.review_cd = 'BAD')                           AS review_bad_cnt,""" else """
                0 AS reviewed_cnt, 0 AS review_useful_cnt, 0 AS review_bad_cnt,"""
        val sql = StringBuilder(
            """
            SELECT$reviewCounts
                count(DISTINCT coalesce(c.session_id::text, 'chat-' || c.chat_id))  AS session_cnt,
                count(*)                                                              AS question_cnt,
                count(*) FILTER (WHERE c.answer IS NOT NULL AND btrim(c.answer) <> '') AS answered_cnt,
                count(*) FILTER (WHERE c.is_reask)                                    AS reask_cnt,
                round(avg(c.response_ms) / 1000.0, 2)                                 AS avg_response_sec,
                count(*) FILTER (WHERE c.rating_cd = 'USEFUL')                        AS useful_cnt,
                count(*) FILTER (WHERE c.rating_cd = 'BAD')                           AS bad_cnt
            FROM ax.tb_ai_chat_log c
            WHERE c.asked_at >= :from
              AND c.asked_at <  :toExclusive
            """.trimIndent()
        )

        val params = historyParams(from, to)
        appendHistoryFilters(sql, params, filter)

        return jdbcTemplate.queryForObject(sql.toString(), params) { rs, _ ->
            val questionCnt = rs.getLong("question_cnt")
            val answeredCnt = rs.getLong("answered_cnt")
            val reaskCnt = rs.getLong("reask_cnt")
            mapOf(
                "questionCnt" to questionCnt,
                "answerRate" to if (questionCnt > 0) Math.round(answeredCnt * 10000.0 / questionCnt) / 100.0 else 0.0,
                "avgResponseSec" to Rs.doubleOrNull(rs, "avg_response_sec"),
                "requeryRate" to if (questionCnt > 0) Math.round(reaskCnt * 10000.0 / questionCnt) / 100.0 else 0.0,
                "usefulCnt" to rs.getLong("useful_cnt"),
                "badCnt" to rs.getLong("bad_cnt"),
                // 관리자 검토(CHH-04)·세션 수(CHH-18)
                "reviewedCnt" to rs.getLong("reviewed_cnt"),
                "reviewUsefulCnt" to rs.getLong("review_useful_cnt"),
                "reviewBadCnt" to rs.getLong("review_bad_cnt"),
                "sessionCnt" to rs.getLong("session_cnt")
            )
        } ?: emptyMap()
    }

    /**
     * 질의 이력 목록을 조회한다. (No.187)
     *
     */
    fun findHistory(
        from: LocalDate,
        to: LocalDate,
        filter: HistoryFilter,
        limit: Int,
        offset: Int
    ): List<Map<String, Any?>> {
        val basisSelect = if (hasBasisColumns()) "c.evidence_summary, c.unanswered_reason" else
            "NULL::text AS evidence_summary, NULL::text AS unanswered_reason"
        val sql = StringBuilder(
            """
            SELECT
                c.chat_id, c.asked_at, c.user_id, u.user_nm, c.dept_nm,
                c.question, c.answer, $basisSelect,
                c.response_ms, c.rating_cd, c.is_reask, c.blind_applied_cnt, ${reviewSelect()}, ${trainSelect()},
                ${llmSelect()},
                coalesce(c.session_id::text, 'chat-' || c.chat_id) AS session_key
            FROM ax.tb_ai_chat_log c
            LEFT JOIN ax.tb_sys_user u ON u.user_id = c.user_id
            WHERE c.asked_at >= :from
              AND c.asked_at <  :toExclusive
            """.trimIndent()
        )

        val params = historyParams(from, to)
        appendHistoryFilters(sql, params, filter)

        sql.append("\nORDER BY c.asked_at DESC, c.chat_id DESC\nLIMIT :limit OFFSET :offset")
        params.addValue("limit", limit).addValue("offset", offset)

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "messageId" to rs.getLong("chat_id"),
                "ts" to Rs.dateTime(rs, "asked_at"),
                "empNo" to rs.getString("user_id"),
                "name" to rs.getString("user_nm"),
                "dept" to rs.getString("dept_nm"),
                "question" to rs.getString("question"),
                "answer" to rs.getString("answer"),
                "judgmentBasis" to rs.getString("evidence_summary"),
                "unansweredReason" to rs.getString("unanswered_reason"),
                "evaluationCriteria" to "근거 부합성·질문 충족 여부·응답 적시성",
                "responseSec" to Rs.intOrNull(rs, "response_ms")?.let { Math.round(it / 100.0) / 10.0 },
                "rating" to rs.getString("rating_cd"),
                "reask" to rs.getBoolean("is_reask"),
                "maskedCnt" to rs.getInt("blind_applied_cnt"),
                "sessionKey" to rs.getString("session_key")
            ) + reviewFields(rs) + trainFields(rs) + llmFields(rs)
        }
    }

    /** 질의 이력 전체 건수 */
    /** 기간 안 질의의 부서별 건수 — 화면 부서 선택지 (08 WEB 계약 `GET /ai/chat/history/groups`) */
    fun findHistoryGroups(from: LocalDate, to: LocalDate, scopeUserId: String?): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT coalesce(c.dept_nm, '-') AS dept_nm, count(*) AS cnt
            FROM ax.tb_ai_chat_log c
            WHERE c.asked_at >= :from
              AND c.asked_at <  :toExclusive
            """.trimIndent()
        )
        val params = historyParams(from, to)
        appendHistoryFilters(sql, params, HistoryFilter(scopeUserId = scopeUserId))
        sql.append("\nGROUP BY coalesce(c.dept_nm, '-')\nORDER BY count(*) DESC, 1")
        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf("dept" to rs.getString("dept_nm"), "cnt" to rs.getLong("cnt"))
        }
    }

    fun countHistory(from: LocalDate, to: LocalDate, filter: HistoryFilter = HistoryFilter()): Long {
        val sql = StringBuilder(
            """
            SELECT count(*)
            FROM ax.tb_ai_chat_log c
            WHERE c.asked_at >= :from
              AND c.asked_at <  :toExclusive
            """.trimIndent()
        )

        val params = historyParams(from, to)
        appendHistoryFilters(sql, params, filter)

        return jdbcTemplate.queryForObject(sql.toString(), params, Long::class.java) ?: 0L
    }

    /** 질의 이력 공통 파라미터 */
    private fun historyParams(from: LocalDate, to: LocalDate): MapSqlParameterSource =
        MapSqlParameterSource()
            .addValue("from", from.atStartOfDay())
            .addValue("toExclusive", to.plusDays(1).atStartOfDay())

    /**
     * 질의 이력 조회 조건 (08 CHH-10)
     *
     * @param keyword     질문 부분 일치(대소문자 무시)
     * @param rating      질의자 평가 USEFUL|REASK|BAD, NONE = 평가 없음
     * @param review      관리자 검토 — rating 과 같은 값
     * @param answered    Y = 응답이 있고 미응답 사유 없음, N = 그 반대(응답 생성 중 포함)
     * @param empNo       질의자 사번 — 서비스가 쓰기 권한자에게만 넘긴다
     * @param scopeUserId 그 사람의 질의만(미배정은 본인 이력만, D-22)
     */
    data class HistoryFilter(
        val userGroup: String? = null,
        val keyword: String? = null,
        val rating: String? = null,
        val review: String? = null,
        val answered: String? = null,
        val empNo: String? = null,
        val scopeUserId: String? = null
    )

    /** 질의 이력 공통 동적 조건 — 범위(부서·질의자) + 질의 행 조건 */
    private fun appendHistoryFilters(sql: StringBuilder, params: MapSqlParameterSource, f: HistoryFilter) {
        appendScopeFilters(sql, params, f)
        rowCondition(params, f)?.let { sql.append(" AND $it") }
    }

    /** 부서·열람 범위·질의자 조건 */
    private fun appendScopeFilters(sql: StringBuilder, params: MapSqlParameterSource, f: HistoryFilter) {
        f.userGroup?.trim()?.takeIf { it.isNotEmpty() }?.let { sql.append(" AND c.dept_nm = :userGroup"); params.addValue("userGroup", it) }
        f.scopeUserId?.let { sql.append(" AND c.user_id = :scopeUserId"); params.addValue("scopeUserId", it) }
        f.empNo?.trim()?.takeIf { it.isNotEmpty() }?.let { sql.append(" AND c.user_id = :askerEmpNo"); params.addValue("askerEmpNo", it) }
    }

    /** 질의 한 행에 거는 조건(검색어·평가·검토·응답 여부) — 없으면 null. 세션 목록은 이 조건을 만족하는 질의가 있는 세션이다 */
    private fun rowCondition(params: MapSqlParameterSource, f: HistoryFilter): String? {
        val parts = mutableListOf<String>()
        com.dwje.api.common.util.SqlLikeUtils.contains(f.keyword)?.let {
            parts += "c.question ILIKE :kw ESCAPE '\\'"; params.addValue("kw", it)
        }
        f.rating?.let {
            if (it == "NONE") parts += "c.rating_cd IS NULL"
            else { parts += "c.rating_cd = :ratingCd"; params.addValue("ratingCd", it) }
        }
        if (f.review != null && hasReviewColumns()) {
            if (f.review == "NONE") parts += "c.review_cd IS NULL"
            else { parts += "c.review_cd = :reviewCd"; params.addValue("reviewCd", f.review) }
        }
        val answeredExpr = "(c.answer IS NOT NULL AND btrim(c.answer) <> ''" +
            (if (hasBasisColumns()) " AND c.unanswered_reason IS NULL)" else ")")
        when (f.answered) {
            "Y" -> parts += answeredExpr
            "N" -> parts += "NOT $answeredExpr"
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" AND ")
    }

    /**
     * 세션 목록 (08 CHH-18) — 세션 키 = session_id, 세션이 없는 질의는 `chat-{chat_id}` 한 건짜리 세션.
     * 집계는 기간 안의 질의 기준이다. [viewerBlind] 가 있으면 열람자에게 가려질 질의 수(hiddenCnt)를 함께 센다.
     *
     * @param viewerId    열람자 사번 — 본인 질의는 가리지 않는다
     * @param viewerBlind 열람자에게 가려지는 데이터 항목. 비면 가림 없음
     */
    fun findSessions(
        from: LocalDate, to: LocalDate, filter: HistoryFilter,
        viewerId: String, viewerBlind: Set<String>, limit: Int?, offset: Int
    ): List<Map<String, Any?>> {
        val (cte, params) = sessionCte(from, to, filter, viewerId, viewerBlind)
        val sql = StringBuilder("$cte\nSELECT s.*, u.user_nm FROM s LEFT JOIN ax.tb_sys_user u ON u.user_id = s.user_id")
            .append("\nORDER BY s.last_asked_at DESC, s.session_key")
        if (limit != null) {
            sql.append(" LIMIT :limit OFFSET :offset"); params.addValue("limit", limit).addValue("offset", offset)
        }
        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "sessionKey" to rs.getString("session_key"), "sessionId" to rs.getString("session_id"),
                "startedAt" to Rs.dateTime(rs, "started_at"), "lastAskedAt" to Rs.dateTime(rs, "last_asked_at"),
                "empNo" to rs.getString("user_id"), "name" to rs.getString("user_nm"), "dept" to rs.getString("dept_nm"),
                "questionCnt" to rs.getInt("question_cnt"), "firstQuestion" to rs.getString("first_question"),
                "answeredCnt" to rs.getInt("answered_cnt"), "usefulCnt" to rs.getInt("useful_cnt"),
                "badCnt" to rs.getInt("bad_cnt"), "reviewedCnt" to rs.getInt("reviewed_cnt"), "hiddenCnt" to rs.getInt("hidden_cnt")
            )
        }
    }

    /** 세션 목록 전체 건수 — [findSessions] 와 같은 조건 */
    fun countSessions(
        from: LocalDate, to: LocalDate, filter: HistoryFilter,
        viewerId: String, viewerBlind: Set<String>
    ): Long {
        val (cte, params) = sessionCte(from, to, filter, viewerId, viewerBlind)
        return jdbcTemplate.queryForObject("$cte\nSELECT count(*) FROM s", params, Long::class.java) ?: 0L
    }

    private fun sessionCte(
        from: LocalDate, to: LocalDate, filter: HistoryFilter,
        viewerId: String, viewerBlind: Set<String>
    ): Pair<String, MapSqlParameterSource> {
        val v55 = hasReviewColumns()
        val params = historyParams(from, to)
            .addValue("viewerId", viewerId).addValue("viewerBlind", textArray(viewerBlind))
        val where = StringBuilder()
        appendScopeFilters(where, params, filter)
        // 검색어·평가·검토·응답 여부 — 그 조건에 맞는 질의가 하나라도 있는 세션(질의 수 등은 세션 전체 기준)
        val having = rowCondition(params, filter)?.let { "\n HAVING bool_or($it)" } ?: ""
        val hidden = when {
            viewerBlind.isEmpty() -> "0"
            v55 -> "count(*) FILTER (WHERE c.user_id <> :viewerId AND (c.blind_field_keys IS NULL " +
                "OR NOT (CAST(:viewerBlind AS text[]) <@ c.blind_field_keys)))"
            else -> "count(*) FILTER (WHERE c.user_id <> :viewerId)"
        }
        val reviewed = if (v55) "count(*) FILTER (WHERE c.review_cd IS NOT NULL)" else "0"
        val cte = """
            WITH s AS (
              SELECT coalesce(c.session_id::text, 'chat-' || c.chat_id) AS session_key,
                     min(c.session_id::text) AS session_id,
                     min(c.asked_at) AS started_at, max(c.asked_at) AS last_asked_at,
                     min(c.user_id) AS user_id, min(c.dept_nm) AS dept_nm, count(*) AS question_cnt,
                     left((array_agg(c.question ORDER BY c.asked_at, c.chat_id))[1], 80) AS first_question,
                     count(*) FILTER (WHERE c.answer IS NOT NULL AND btrim(c.answer) <> '') AS answered_cnt,
                     count(*) FILTER (WHERE c.rating_cd = 'USEFUL') AS useful_cnt,
                     count(*) FILTER (WHERE c.rating_cd = 'BAD') AS bad_cnt,
                     $reviewed AS reviewed_cnt,
                     $hidden AS hidden_cnt
                FROM ax.tb_ai_chat_log c
               WHERE c.asked_at >= :from AND c.asked_at < :toExclusive$where
               GROUP BY 1$having
            )
        """.trimIndent()
        return cte to params
    }

    /**
     * 세션 한 건의 질의를 시간순으로 (08 CHH-18) — [sessionId] 또는 세션 없는 질의 [chatId] 하나.
     * 기간 제한은 없다. 덕반장 AI 의 본인 세션 조회([findSessionMessages])와 달리 질의자를 거르지 않는다(권한은 서비스가 본다).
     */
    fun findSessionTurns(sessionId: UUID?, chatId: Long?): List<Map<String, Any?>> {
        val basisSelect = if (hasBasisColumns()) "c.evidence_summary, c.unanswered_reason" else
            "NULL::text AS evidence_summary, NULL::text AS unanswered_reason"
        val where = if (sessionId != null) "c.session_id = :sessionId" else "c.chat_id = :chatId AND c.session_id IS NULL"
        val sql = """
            SELECT c.chat_id, c.session_id, c.asked_at, c.user_id, u.user_nm, c.dept_nm, c.question, c.answer,
                   $basisSelect, c.response_ms, c.rating_cd, c.is_reask, ${reviewSelect()}, ${trainSelect()}, ${llmSelect()}
              FROM ax.tb_ai_chat_log c
              LEFT JOIN ax.tb_sys_user u ON u.user_id = c.user_id
             WHERE $where
             ORDER BY c.asked_at, c.chat_id
        """.trimIndent()
        val params = MapSqlParameterSource().addValue("sessionId", sessionId).addValue("chatId", chatId)
        return jdbcTemplate.query(sql, params) { rs, _ ->
            mapOf(
                "messageId" to rs.getLong("chat_id"), "sessionId" to rs.getString("session_id"),
                "askedAt" to Rs.dateTime(rs, "asked_at"), "empNo" to rs.getString("user_id"), "name" to rs.getString("user_nm"),
                "dept" to rs.getString("dept_nm"), "question" to rs.getString("question"), "answer" to rs.getString("answer"),
                "judgmentBasis" to rs.getString("evidence_summary"), "unansweredReason" to rs.getString("unanswered_reason"),
                "responseSec" to Rs.intOrNull(rs, "response_ms")?.let { Math.round(it / 100.0) / 10.0 },
                "rating" to rs.getString("rating_cd"), "reask" to rs.getBoolean("is_reask")
            ) + reviewFields(rs) + trainFields(rs) + llmFields(rs)
        }
    }

    /**
     * 학습데이터 내보내기 대상을 조회한다. (No.189, 08 CHH-03)
     *
     * 평가 = 출처에 따라 관리자 검토(review_cd)와 질의자 평가(rating_cd) 중 하나. 기본은 검토가 있으면 검토, 없으면 질의자 평가.
     * 빈 답·가린 답(AiResponseSanitizer.HIDDEN)은 뺀다.
     * 학습 답변(train_answer, V70)이 있는 행은 평가와 관계없이 넣는다 — 서비스가 응답 대신 학습 답변을 쓴다.
     *
     * @param rating 평가 코드. null 이면 평가가 있는 건 전체(ALL)
     * @param source REVIEW_OR_USER | REVIEW | USER
     * @param hidden 가린 답 문구
     */
    fun findTrainsetRows(
        from: LocalDate,
        to: LocalDate,
        rating: String?,
        source: String,
        hidden: String,
        limit: Int
    ): List<Map<String, Any?>> {
        val v55 = hasReviewColumns()
        val eff = when {
            !v55 || source == "USER" -> "c.rating_cd"
            source == "REVIEW" -> "c.review_cd"
            else -> "coalesce(c.review_cd, c.rating_cd)"
        }
        val ratingSource = if (!v55) "'USER'" else
            "CASE WHEN '$source' = 'USER' OR c.review_cd IS NULL THEN 'USER' ELSE 'REVIEW' END"
        val train = hasTrainColumns()
        val trainCond = if (train) "c.train_answer IS NOT NULL AND btrim(c.train_answer) <> ''" else "false"
        val sql = StringBuilder(
            """
            SELECT c.chat_id, c.user_id, c.question, c.normalized_question, c.answer, c.rating_cd,
                   ${if (v55) "c.blind_field_keys" else "NULL::text[] AS blind_field_keys"},
                   ${if (train) "c.train_answer" else "NULL::text AS train_answer"},
                   $eff AS eff_rating, $ratingSource AS rating_source
            FROM ax.tb_ai_chat_log c
            WHERE c.asked_at >= :from
              AND c.asked_at <  :toExclusive
              AND (($trainCond) OR (c.answer IS NOT NULL AND btrim(c.answer) <> '' AND c.answer <> :hidden
              AND $eff IS NOT NULL
            """.trimIndent()
        )

        val params = MapSqlParameterSource()
            .addValue("from", from.atStartOfDay())
            .addValue("toExclusive", to.plusDays(1).atStartOfDay())
            .addValue("limit", limit)
            .addValue("hidden", hidden)

        if (rating != null) {
            sql.append(" AND $eff = :rating")
            params.addValue("rating", rating)
        }
        sql.append("))")

        sql.append("\nORDER BY c.asked_at DESC\nLIMIT :limit")

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "chatId" to rs.getLong("chat_id"),
                "empNo" to rs.getString("user_id"),
                "question" to rs.getString("question"),
                "normalizedQuestion" to rs.getString("normalized_question"),
                "answer" to rs.getString("answer"),
                "rating" to rs.getString("eff_rating"),
                "ratingSource" to rs.getString("rating_source"),
                "trainAnswer" to rs.getString("train_answer"),
                "blindFieldKeys" to readKeys(rs, "blind_field_keys")
            )
        }
    }

    companion object {
        /** 검토·평가 코드 이름 (AI_CHAT_RATING) */
        val REVIEW_NAMES = mapOf("USEFUL" to "유용", "REASK" to "재질의", "BAD" to "오답")

        /**
         * 질의문 → OR tsquery (`프레스 | 금형 | 관리`)
         *
         * `simple` 사전은 어간을 자르지 않아 `금형을` 과 `금형` 이 다른 낱말이다. 흔한 조사를 떼어 둘을 맞춘다.
         * 글자·숫자만 남기므로 tsquery 문법 문자(`&|!():*`)가 들어올 수 없다.
         *
         * @return 쓸 낱말이 없으면 null
         */
        fun toOrTsQuery(text: String): String? {
            val particles = listOf("으로", "에서", "에게", "까지", "부터", "하고", "을", "를", "이", "가", "은", "는", "의", "에", "로", "와", "과", "도", "만")
            val words = text.lowercase()
                .split(Regex("""[^\p{L}\p{N}]+"""))
                .map { w -> particles.firstOrNull { w.length > it.length + 1 && w.endsWith(it) }?.let { w.dropLast(it.length) } ?: w }
                .filter { it.length >= 2 }
                .distinct()
                .take(12)
            return words.takeIf { it.isNotEmpty() }?.joinToString(" | ")
        }
    }

    // =================================================================================
    // 보존 기간 경과 파기 (08 CHH-08)
    // =================================================================================

    /** 파기 한 묶음 크기 — 한 문장이 표를 오래 잡지 않게 나눠 지운다 */
    private val purgeChunk = 5_000

    /**
     * [cut] 이전 질의를 진단 기록 → 검색 기록 → 재질의 연결 → 질의 순으로 지운다.
     * 검색 근거(vec.tb_query_hit)·Agent 연결은 FK CASCADE, 검색 기록의 chat_id 는 SET NULL 이다.
     * 진단 표(V44)가 없는 DB 는 그 단계를 건너뛴다.
     *
     * @return 표별 지운 행 수
     */
    /** 이 시각보다 오래된 질의 수 — 보존 기간 경과 건수 (R-20). 정리 배치와 같은 기준(asked_at < cut) */
    fun countBefore(cut: java.time.LocalDateTime): Long =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ax.tb_ai_chat_log WHERE asked_at < :cut", MapSqlParameterSource("cut", cut), Long::class.java
        ) ?: 0L

    fun purgeBefore(cut: java.time.LocalDateTime): Map<String, Int> {
        val p = MapSqlParameterSource("cut", cut)
        fun loop(sql: String): Int {
            var total = 0
            while (true) {
                val n = jdbcTemplate.update(sql, p.addValue("lim", purgeChunk))
                total += n
                if (n < purgeChunk) return total
            }
        }
        val debugExists = jdbcTemplate.queryForObject(
            "SELECT to_regclass('ax.tb_ai_chat_debug') IS NOT NULL", MapSqlParameterSource(), Boolean::class.java
        ) == true
        val debug = if (!debugExists) 0 else loop(
            "DELETE FROM ax.tb_ai_chat_debug WHERE ctid IN (SELECT ctid FROM ax.tb_ai_chat_debug WHERE asked_at < :cut LIMIT :lim)"
        )
        val query = loop(
            "DELETE FROM vec.tb_query_log WHERE ctid IN (SELECT ctid FROM vec.tb_query_log WHERE asked_at < :cut LIMIT :lim)"
        )
        // 재질의 연결(prev_chat_id)은 FK 가 NO ACTION 이라 지울 질의를 가리키는 연결을 먼저 끊는다
        jdbcTemplate.update(
            """
            UPDATE ax.tb_ai_chat_log SET prev_chat_id = NULL
             WHERE prev_chat_id IN (SELECT chat_id FROM ax.tb_ai_chat_log WHERE asked_at < :cut)
            """.trimIndent(), p
        )
        val chat = loop(
            "DELETE FROM ax.tb_ai_chat_log WHERE chat_id IN (SELECT chat_id FROM ax.tb_ai_chat_log WHERE asked_at < :cut LIMIT :lim)"
        )
        return linkedMapOf("chat" to chat, "query" to query, "debug" to debug)
    }
}
