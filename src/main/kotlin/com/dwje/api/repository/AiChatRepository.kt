package com.dwje.api.repository

import com.dwje.api.common.util.Rs
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.util.UUID

/**
 * 자연어 질의(AI 채팅) 데이터 접근 Repository (AI-01, SY-08)
 *
 * 참조 테이블
 * - 질의/응답 이력 : ax.tb_ai_chat_log, ax.tb_ai_chat_agent, ax.tb_ai_chat_term
 * - 검색 이력      : vec.tb_query_log, vec.tb_query_hit, vec.tb_doc_chunk, vec.tb_doc
 * - 용어/마스킹    : ax.tb_gls_term, ax.tb_gls_variant, ax.tb_ai_mask_rule
 */
@Repository
class AiChatRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) {

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
        prevChatId: Long?
    ): Long {
        val sql = """
            INSERT INTO ax.tb_ai_chat_log (
                session_id, asked_at, user_id, dept_nm, question, normalized_question,
                intent_cd, intent_nm, answer, response_ms, is_reask, prev_chat_id,
                blind_applied_cnt, profile_id
            ) VALUES (
                :sessionId, now(), :userId, :deptNm, :question, :normalizedQuestion,
                :intentCd, :intentNm, :answer, :responseMs, :isReask, :prevChatId,
                :blindAppliedCnt, :profileId
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
        val sql = """
            SELECT
                c.chat_id, c.session_id, c.user_id, c.dept_nm, c.question, c.normalized_question,
                c.intent_cd, c.intent_nm, c.answer, c.response_ms, c.rating_cd,
                c.blind_applied_cnt, c.profile_id, c.asked_at
            FROM ax.tb_ai_chat_log c
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
            )
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

    /**
     * 추천 질의 목록을 조회한다. (No.17 — 현장 빈출 질의 기반)
     *
     * 최근 30일간 '유용' 평가를 받았거나 재질의 없이 종료된 질의를 빈도순으로 집계한다.
     *
     * @param limit 조회 건수
     */
    fun findSuggestions(limit: Int): List<Map<String, Any?>> {
        val sql = """
            SELECT
                c.normalized_question           AS q,
                max(c.intent_nm)                AS intent_nm,
                count(*)                        AS ask_cnt,
                round(avg(c.response_ms))       AS avg_ms
            FROM ax.tb_ai_chat_log c
            WHERE c.asked_at >= now() - interval '30 days'
              AND c.normalized_question IS NOT NULL
              AND c.intent_cd NOT IN ('denied', 'unknown')
              AND (c.rating_cd IS NULL OR c.rating_cd = 'USEFUL')
            GROUP BY c.normalized_question
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
    fun updateRating(chatId: Long, ratingCd: String, userId: String): Int {
        val sql = """
            UPDATE ax.tb_ai_chat_log
               SET rating_cd = :ratingCd
             WHERE chat_id = :chatId
               AND user_id = :userId
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("chatId", chatId)
            .addValue("ratingCd", ratingCd)
            .addValue("userId", userId)

        return jdbcTemplate.update(sql, params)
    }

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
     * @param queryText 정규화된 질의문
     * @param deptId    조회자 부서 ID
     * @param topK      반환 건수
     */
    fun searchDocumentChunks(queryText: String, deptId: Int, topK: Int): List<Map<String, Any?>> {
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
                ts_rank(ch.tsv, plainto_tsquery('simple', :queryText))     AS ts_score
            FROM vec.tb_doc_chunk ch
            INNER JOIN vec.tb_doc d ON d.doc_id = ch.doc_id
            WHERE ch.del_flg    = 'N'
              AND ch.is_current = true
              AND d.del_flg     = 'N'
              AND ch.tsv @@ plainto_tsquery('simple', :queryText)
              AND (
                    d.scope_cd = 'ALL'
                 OR d.owner_dept_id = :deptId
                 OR EXISTS (
                        SELECT 1 FROM vec.tb_doc_dept_perm p
                         WHERE p.doc_id = d.doc_id AND p.dept_id = :deptId AND p.can_read = true
                    )
              )
            ORDER BY ts_score DESC, d.doc_date DESC NULLS LAST
            LIMIT :topK
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("queryText", queryText)
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
    fun findHistorySummary(from: LocalDate, to: LocalDate, userGroup: String?): Map<String, Any?> {
        val sql = StringBuilder(
            """
            SELECT
                count(*)                                                              AS question_cnt,
                count(*) FILTER (WHERE c.intent_cd NOT IN ('unknown', 'denied'))      AS classified_cnt,
                count(*) FILTER (WHERE c.is_reask)                                    AS reask_cnt,
                round(avg(c.response_ms) / 1000.0, 2)                                 AS avg_response_sec,
                count(*) FILTER (WHERE c.rating_cd = 'USEFUL')                        AS useful_cnt,
                count(*) FILTER (WHERE c.rating_cd = 'BAD')                           AS bad_cnt
            FROM ax.tb_ai_chat_log c
            WHERE c.asked_at >= :from
              AND c.asked_at <  :toExclusive
            """.trimIndent()
        )

        val params = MapSqlParameterSource()
            .addValue("from", from.atStartOfDay())
            .addValue("toExclusive", to.plusDays(1).atStartOfDay())

        if (!userGroup.isNullOrBlank()) {
            sql.append(" AND c.dept_nm = :userGroup")
            params.addValue("userGroup", userGroup.trim())
        }

        return jdbcTemplate.queryForObject(sql.toString(), params) { rs, _ ->
            val questionCnt = rs.getLong("question_cnt")
            val classifiedCnt = rs.getLong("classified_cnt")
            val reaskCnt = rs.getLong("reask_cnt")
            mapOf(
                "questionCnt" to questionCnt,
                // 의도 정확도 = 분류 성공 건수 / 전체 질의
                "intentAccuracy" to if (questionCnt > 0) Math.round(classifiedCnt * 10000.0 / questionCnt) / 100.0 else 0.0,
                "avgResponseSec" to Rs.doubleOrNull(rs, "avg_response_sec"),
                "requeryRate" to if (questionCnt > 0) Math.round(reaskCnt * 10000.0 / questionCnt) / 100.0 else 0.0,
                "usefulCnt" to rs.getLong("useful_cnt"),
                "badCnt" to rs.getLong("bad_cnt")
            )
        } ?: emptyMap()
    }

    /**
     * 질의 이력 목록을 조회한다. (No.187)
     *
     * @param intent 의도 코드 필터
     */
    fun findHistory(
        from: LocalDate,
        to: LocalDate,
        userGroup: String?,
        intent: String?,
        limit: Int,
        offset: Int
    ): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT
                c.chat_id, c.asked_at, c.user_id, u.user_nm, c.dept_nm,
                c.question, c.intent_cd, c.intent_nm, c.response_ms, c.rating_cd,
                c.is_reask, c.blind_applied_cnt,
                (
                    SELECT string_agg(a.agent_no, ',' ORDER BY ca.call_seq)
                      FROM ax.tb_ai_chat_agent ca
                     INNER JOIN ax.tb_ai_agent a ON a.agent_id = ca.agent_id
                     WHERE ca.chat_id = c.chat_id
                ) AS agents
            FROM ax.tb_ai_chat_log c
            LEFT JOIN ax.tb_sys_user u ON u.user_id = c.user_id
            WHERE c.asked_at >= :from
              AND c.asked_at <  :toExclusive
            """.trimIndent()
        )

        val params = historyParams(from, to, userGroup, intent)
        appendHistoryFilters(sql, userGroup, intent)

        sql.append("\nORDER BY c.asked_at DESC\nLIMIT :limit OFFSET :offset")
        params.addValue("limit", limit).addValue("offset", offset)

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "messageId" to rs.getLong("chat_id"),
                "ts" to Rs.dateTime(rs, "asked_at"),
                "empNo" to rs.getString("user_id"),
                "name" to rs.getString("user_nm"),
                "dept" to rs.getString("dept_nm"),
                "question" to rs.getString("question"),
                "intent" to rs.getString("intent_cd"),
                "intentNm" to rs.getString("intent_nm"),
                "agents" to (rs.getString("agents")?.split(",") ?: emptyList()),
                "responseSec" to Rs.intOrNull(rs, "response_ms")?.let { Math.round(it / 100.0) / 10.0 },
                "rating" to rs.getString("rating_cd"),
                "reask" to rs.getBoolean("is_reask"),
                "maskedCnt" to rs.getInt("blind_applied_cnt")
            )
        }
    }

    /** 질의 이력 전체 건수 */
    fun countHistory(from: LocalDate, to: LocalDate, userGroup: String?, intent: String?): Long {
        val sql = StringBuilder(
            """
            SELECT count(*)
            FROM ax.tb_ai_chat_log c
            WHERE c.asked_at >= :from
              AND c.asked_at <  :toExclusive
            """.trimIndent()
        )

        val params = historyParams(from, to, userGroup, intent)
        appendHistoryFilters(sql, userGroup, intent)

        return jdbcTemplate.queryForObject(sql.toString(), params, Long::class.java) ?: 0L
    }

    /** 질의 이력 공통 파라미터 */
    private fun historyParams(
        from: LocalDate,
        to: LocalDate,
        userGroup: String?,
        intent: String?
    ): MapSqlParameterSource {
        val params = MapSqlParameterSource()
            .addValue("from", from.atStartOfDay())
            .addValue("toExclusive", to.plusDays(1).atStartOfDay())
        if (!userGroup.isNullOrBlank()) params.addValue("userGroup", userGroup.trim())
        if (!intent.isNullOrBlank()) params.addValue("intent", intent.trim())
        return params
    }

    /** 질의 이력 공통 동적 조건 */
    private fun appendHistoryFilters(sql: StringBuilder, userGroup: String?, intent: String?) {
        if (!userGroup.isNullOrBlank()) sql.append(" AND c.dept_nm = :userGroup")
        if (!intent.isNullOrBlank()) sql.append(" AND c.intent_cd = :intent")
    }

    /**
     * 학습데이터 내보내기 대상을 조회한다. (No.189)
     *
     * @param ratingFilter 평가 필터 (USEFUL/BAD/REASK)
     */
    fun findTrainsetRows(
        from: LocalDate,
        to: LocalDate,
        ratingFilter: String?,
        limit: Int
    ): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT c.chat_id, c.question, c.normalized_question, c.intent_cd, c.answer, c.rating_cd
            FROM ax.tb_ai_chat_log c
            WHERE c.asked_at >= :from
              AND c.asked_at <  :toExclusive
              AND c.answer IS NOT NULL
              AND c.intent_cd <> 'denied'
            """.trimIndent()
        )

        val params = MapSqlParameterSource()
            .addValue("from", from.atStartOfDay())
            .addValue("toExclusive", to.plusDays(1).atStartOfDay())
            .addValue("limit", limit)

        if (!ratingFilter.isNullOrBlank()) {
            sql.append(" AND c.rating_cd = :ratingFilter")
            params.addValue("ratingFilter", ratingFilter.trim().uppercase())
        }

        sql.append("\nORDER BY c.asked_at DESC\nLIMIT :limit")

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "chatId" to rs.getLong("chat_id"),
                "question" to rs.getString("question"),
                "normalizedQuestion" to rs.getString("normalized_question"),
                "intent" to rs.getString("intent_cd"),
                "answer" to rs.getString("answer"),
                "rating" to rs.getString("rating_cd")
            )
        }
    }
}
