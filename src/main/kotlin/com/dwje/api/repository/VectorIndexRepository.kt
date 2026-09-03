package com.dwje.api.repository

import com.dwje.api.common.util.Rs
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

/**
 * 벡터 색인 · 임베딩 작업 Repository (SY-06 재색인, SY-11 벡터 인덱스)
 *
 * 참조 테이블 : vec.tb_ingest_job, vec.tb_ingest_error, vec.tb_embed_model,
 *              vec.tb_doc, vec.tb_doc_version, vec.tb_doc_chunk
 */
@Repository
class VectorIndexRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) {

    /**
     * 색인 작업을 등록한다. (No.178 / No.203)
     *
     * job_id 는 "JOB-yyyyMMddHHmmss-seq" 형식으로 채번한다.
     *
     * @param jobTypeCd 작업 유형 — FULL / INCR / TERM_EMBED
     * @return 생성된 job_id
     */
    fun insertIngestJob(
        jobTypeCd: String,
        embedModelId: Int?,
        docCnt: Int,
        chunkCnt: Int,
        triggeredBy: String,
        remark: String?
    ): String {
        val jobId = "JOB-${LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))}"

        val sql = """
            INSERT INTO vec.tb_ingest_job (
                job_id, job_type_cd, embed_model_id, started_at,
                doc_cnt, chunk_cnt, embed_cnt, ok_cnt, ng_cnt, token_cnt,
                state_cd, triggered_by_cd, triggered_by, remark
            ) VALUES (
                :jobId, :jobTypeCd, :embedModelId, now(),
                :docCnt, :chunkCnt, 0, 0, 0, 0,
                'RUNNING', 'MANUAL', :triggeredBy, :remark
            )
            ON CONFLICT (job_id) DO NOTHING
            RETURNING job_id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("jobId", jobId)
            .addValue("jobTypeCd", jobTypeCd)
            .addValue("embedModelId", embedModelId)
            .addValue("docCnt", docCnt)
            .addValue("chunkCnt", chunkCnt)
            .addValue("triggeredBy", triggeredBy)
            .addValue("remark", remark?.take(500))

        return jdbcTemplate.query(sql, params) { rs, _ -> rs.getString("job_id") }.firstOrNull() ?: jobId
    }

    /**
     * 벡터 인덱스(색인 작업) 목록을 조회한다. (No.202)
     *
     * @param state 작업 상태 (RUNNING/DONE/FAIL)
     */
    fun findIngestJobs(state: String?, limit: Int, offset: Int): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT
                j.job_id, j.job_type_cd, j.started_at, j.ended_at, j.duration_sec,
                j.doc_cnt, j.chunk_cnt, j.embed_cnt, j.ok_cnt, j.ng_cnt, j.token_cnt,
                j.state_cd, j.triggered_by_cd, j.triggered_by, j.remark,
                m.model_key, m.model_nm, m.dim
            FROM vec.tb_ingest_job j
            LEFT JOIN vec.tb_embed_model m ON m.model_id = j.embed_model_id
            WHERE 1 = 1
            """.trimIndent()
        )

        val params = MapSqlParameterSource()
        if (!state.isNullOrBlank()) {
            sql.append(" AND j.state_cd = :state")
            params.addValue("state", state.trim().uppercase())
        }

        sql.append("\nORDER BY j.started_at DESC\nLIMIT :limit OFFSET :offset")
        params.addValue("limit", limit).addValue("offset", offset)

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "vecId" to rs.getString("job_id"),
                "jobId" to rs.getString("job_id"),
                "type" to rs.getString("job_type_cd"),
                "startedAt" to Rs.dateTime(rs, "started_at"),
                "endedAt" to Rs.dateTime(rs, "ended_at"),
                "duration" to Rs.intOrNull(rs, "duration_sec"),
                "state" to rs.getString("state_cd"),
                "docCnt" to rs.getInt("doc_cnt"),
                "chunkCnt" to rs.getInt("chunk_cnt"),
                "embedCnt" to rs.getInt("embed_cnt"),
                "okCnt" to rs.getInt("ok_cnt"),
                "ngCnt" to rs.getInt("ng_cnt"),
                "tokenCnt" to rs.getLong("token_cnt"),
                "embedModel" to rs.getString("model_nm"),
                "embedModelKey" to rs.getString("model_key"),
                "dim" to Rs.intOrNull(rs, "dim"),
                "triggeredBy" to rs.getString("triggered_by"),
                "remark" to rs.getString("remark")
            )
        }
    }

    /** 색인 작업 전체 건수 */
    fun countIngestJobs(state: String?): Long {
        val sql = StringBuilder("SELECT count(*) FROM vec.tb_ingest_job j WHERE 1 = 1")
        val params = MapSqlParameterSource()
        if (!state.isNullOrBlank()) {
            sql.append(" AND j.state_cd = :state")
            params.addValue("state", state.trim().uppercase())
        }
        return jdbcTemplate.queryForObject(sql.toString(), params, Long::class.java) ?: 0L
    }

    /**
     * 색인 작업 상세와 오류를 조회한다. (No.204)
     */
    fun findIngestJob(jobId: String): Map<String, Any?>? {
        val sql = """
            SELECT
                j.job_id, j.job_type_cd, j.started_at, j.ended_at, j.duration_sec,
                j.doc_cnt, j.chunk_cnt, j.embed_cnt, j.ok_cnt, j.ng_cnt, j.token_cnt,
                j.state_cd, j.triggered_by, j.remark,
                m.model_key, m.model_nm, m.dim, m.distance_cd
            FROM vec.tb_ingest_job j
            LEFT JOIN vec.tb_embed_model m ON m.model_id = j.embed_model_id
            WHERE j.job_id = :jobId
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource("jobId", jobId)) { rs, _ ->
            mapOf(
                "vecId" to rs.getString("job_id"),
                "config" to mapOf(
                    "type" to rs.getString("job_type_cd"),
                    "embedModel" to rs.getString("model_nm"),
                    "embedModelKey" to rs.getString("model_key"),
                    "dim" to Rs.intOrNull(rs, "dim"),
                    "distance" to rs.getString("distance_cd")
                ),
                "stats" to mapOf(
                    "startedAt" to Rs.dateTime(rs, "started_at"),
                    "endedAt" to Rs.dateTime(rs, "ended_at"),
                    "duration" to Rs.intOrNull(rs, "duration_sec"),
                    "state" to rs.getString("state_cd"),
                    "docCnt" to rs.getInt("doc_cnt"),
                    "chunkCnt" to rs.getInt("chunk_cnt"),
                    "embedCnt" to rs.getInt("embed_cnt"),
                    "okCnt" to rs.getInt("ok_cnt"),
                    "ngCnt" to rs.getInt("ng_cnt"),
                    "tokenCnt" to rs.getLong("token_cnt")
                ),
                "triggeredBy" to rs.getString("triggered_by"),
                "remark" to rs.getString("remark")
            )
        }.firstOrNull()
    }

    /**
     * 색인 오류 목록을 조회한다. (No.204)
     */
    fun findIngestErrors(jobId: String, limit: Int): List<Map<String, Any?>> {
        val sql = """
            SELECT err_id, doc_id, chunk_id, stage_cd, err_code, err_msg, resolved, ins_date
            FROM vec.tb_ingest_error
            WHERE job_id = :jobId
            ORDER BY err_id
            LIMIT :limit
        """.trimIndent()

        val params = MapSqlParameterSource().addValue("jobId", jobId).addValue("limit", limit)

        return jdbcTemplate.query(sql, params) { rs, _ ->
            mapOf(
                "errId" to rs.getLong("err_id"),
                "docId" to Rs.longOrNull(rs, "doc_id"),
                "chunkId" to Rs.longOrNull(rs, "chunk_id"),
                "stage" to rs.getString("stage_cd"),
                "code" to rs.getString("err_code"),
                "message" to rs.getString("err_msg"),
                "resolved" to rs.getBoolean("resolved"),
                "at" to Rs.dateTime(rs, "ins_date")
            )
        }
    }

    /**
     * 기본 임베딩 모델 ID 를 조회한다.
     */
    fun findDefaultEmbedModelId(): Int? {
        val sql = """
            SELECT model_id
            FROM vec.tb_embed_model
            WHERE use_flg = 'Y'
            ORDER BY is_default DESC, model_id
            LIMIT 1
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource()) { rs, _ -> rs.getInt("model_id") }.firstOrNull()
    }

    /**
     * 색인 대상 문서 통계를 조회한다. (재색인 실행 시 대상 규모 산출)
     *
     * @param sources 문서 유형 코드 목록 (빈 목록이면 전체)
     */
    fun findIndexTargetStats(sources: List<String>): Map<String, Any?> {
        val sql = StringBuilder(
            """
            SELECT
                count(DISTINCT d.doc_id)                                    AS doc_cnt,
                coalesce(sum(d.chunk_cnt), 0)                               AS chunk_cnt
            FROM vec.tb_doc d
            WHERE d.del_flg = 'N'
            """.trimIndent()
        )

        val params = MapSqlParameterSource()
        if (sources.isNotEmpty()) {
            sql.append(" AND d.doc_type_cd = ANY(:sources)")
            params.addValue("sources", sources.toTypedArray())
        }

        return jdbcTemplate.queryForObject(sql.toString(), params) { rs, _ ->
            mapOf("docCnt" to rs.getLong("doc_cnt"), "chunkCnt" to rs.getLong("chunk_cnt"))
        } ?: mapOf("docCnt" to 0L, "chunkCnt" to 0L)
    }

    /**
     * 질의 상세를 조회한다. (No.188)
     */
    fun findQueryDetail(chatId: Long): Map<String, Any?>? {
        val sql = """
            SELECT
                q.query_id, q.chat_id, q.asked_at, q.user_id, q.dept_id,
                q.query_text, q.normalized_text, q.top_k, q.candidate_k,
                q.pool_cnt, q.hit_cnt, q.cited_cnt, q.blocked_doc_cnt,
                q.embed_ms, q.search_ms, q.rerank_ms, q.total_ms
            FROM vec.tb_query_log q
            WHERE q.chat_id = :chatId
            ORDER BY q.asked_at DESC
            LIMIT 1
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource("chatId", chatId)) { rs, _ ->
            mapOf(
                "queryId" to rs.getLong("query_id"),
                "askedAt" to Rs.dateTime(rs, "asked_at"),
                "queryText" to rs.getString("query_text"),
                "normalizedText" to rs.getString("normalized_text"),
                "topK" to rs.getInt("top_k"),
                "candidateK" to rs.getInt("candidate_k"),
                "poolCnt" to rs.getInt("pool_cnt"),
                "hitCnt" to rs.getInt("hit_cnt"),
                "citedCnt" to rs.getInt("cited_cnt"),
                "blockedDocCnt" to rs.getInt("blocked_doc_cnt"),
                "embedMs" to Rs.intOrNull(rs, "embed_ms"),
                "searchMs" to Rs.intOrNull(rs, "search_ms"),
                "rerankMs" to Rs.intOrNull(rs, "rerank_ms"),
                "elapsedMs" to Rs.intOrNull(rs, "total_ms")
            )
        }.firstOrNull()
    }

    /**
     * 질의 검색 히트 목록을 조회한다. (No.188)
     */
    fun findQueryHits(queryId: Long): List<Map<String, Any?>> {
        val sql = """
            SELECT
                h.chunk_id, h.doc_id, h.rank_no, h.vec_sim, h.ts_score, h.trgm_sim,
                h.rrf_score, h.rerank_score, h.is_cited, h.masked_field_keys,
                d.title, c.heading, c.page_no
            FROM vec.tb_query_hit h
            LEFT JOIN vec.tb_doc       d ON d.doc_id   = h.doc_id
            LEFT JOIN vec.tb_doc_chunk c ON c.chunk_id = h.chunk_id
            WHERE h.query_id = :queryId
            ORDER BY h.rank_no
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource("queryId", queryId)) { rs, _ ->
            mapOf(
                "docId" to rs.getLong("doc_id"),
                "chunkId" to rs.getLong("chunk_id"),
                "title" to rs.getString("title"),
                "heading" to rs.getString("heading"),
                "page" to Rs.intOrNull(rs, "page_no"),
                "rank" to rs.getInt("rank_no"),
                "score" to (Rs.doubleOrNull(rs, "rerank_score") ?: Rs.doubleOrNull(rs, "rrf_score")
                    ?: Rs.doubleOrNull(rs, "ts_score")),
                "cited" to rs.getBoolean("is_cited"),
                "maskedFields" to ((rs.getArray("masked_field_keys")?.array as? Array<*>)?.map { it.toString() }
                    ?: emptyList<String>())
            )
        }
    }
}
