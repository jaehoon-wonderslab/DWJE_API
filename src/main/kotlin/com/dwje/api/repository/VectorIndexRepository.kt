package com.dwje.api.repository

import com.dwje.api.common.util.Rs
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

/**
 * 벡터 색인 · 임베딩 작업 Repository (SY-06 용어 재색인, SY-08 질의 상세의 검색 히트)
 *
 * AI 모델 버전 관리 화면(SY-11)의 색인 작업 목록·상세·오류 조회는 2026-09-15 에 화면과 함께 제거됐다.
 *
 * 참조 테이블 : vec.tb_ingest_job, vec.tb_embed_model, vec.tb_query_log, vec.tb_query_hit,
 *              vec.tb_doc, vec.tb_doc_chunk
 */
@Repository
class VectorIndexRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) {

    /**
     * 색인 작업을 등록한다. (No.178 용어 재색인)
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
