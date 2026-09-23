package com.dwje.api.repository

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.LocalDate

/**
 * FACA 문서 근거 Repository (`vec.tb_doc_chunk` · `vec.tb_doc`)
 *
 * ## 왜 문서가 근거로 필요한가
 * 대조할 수 있는 MES 지표 5종은 전부 수치다. 「왜」는 수치로 말할 수 있어도
 * 「무엇을 할지」는 FACA 문서의 원인·대책 원문에서 나온다.
 * 압력·온도 수집값이 없어도 조치를 댈 수 있는 이유다.
 *
 * ## 권한은 문서 단위 함수가 판정한다
 * `vec.fn_allowed_doc(user_id, strict)` 이 사용자 ACTIVE 여부 · `tb_doc.del_flg` ·
 * `ingest_state_cd = 'EMBEDDED'` · `scope_cd` 와 부서 권한 · 통합관리자 여부를 본다.
 * `strict = true` 면 `blocked_field_keys` 가 있는 문서를 아예 제외한다.
 *
 * 다만 그 함수가 **보지 않는 것**이 있다 — `retention_until`. 보존기한이 지난 문서가
 * 근거로 붙지 않게 여기서 함께 거른다. (2026-09-05 term_fb48e0f3 확인)
 */
@Repository
class DocEvidenceRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) {

    /**
     * 처방 근거 후보를 찾는다. — `vec.fn_search_chunk` 하이브리드 검색
     *
     * 그쪽 세션이 만든 함수를 그대로 쓴다. 벡터·전문·트라이그램 3경로를 RRF 로 합치고
     * 문서 권한(`fn_allowed_doc`)까지 함께 판정하며, 청크의 `is_current`·`del_flg` 도
     * 함수 안에서 걸린다. 순위 규칙을 여기서 다시 만들면 두 곳이 갈린다.
     *
     * **공정·설비로 거르지 않는다.** `vec.tb_doc.wc_cd`·`eqpt_cd` 가 1,476건 전부
     * 비어 있어(2026-09-05 확인) 그 조건을 걸면 후보가 0건이 된다. 대신 공정·설비·
     * 불량 유형을 **질의 문장**에 넣어 검색으로 관련성을 찾는다.
     *
     * 보존기한(`retention_until`)은 함수가 보지 않으므로 결과에서 한 번 더 거른다.
     */
    fun searchPrescriptionCandidates(
        userId: String,
        queryText: String,
        queryEmbedding: List<Double>,
        limit: Int
    ): List<Map<String, Any?>> {
        val sql = """
            SELECT s.chunk_id, s.chunk_text, s.title, s.doc_type_cd, s.doc_date, s.doc_uid
            FROM vec.fn_search_chunk(
                     p_query_embedding => CAST(:embedding AS vector),
                     p_user_id         => :userId,
                     p_query_text      => :queryText,
                     p_top_k           => :topK,
                     -- 처방 근거는 불량 분석 문서에서만 찾는다.
                     -- MINUTES·YIELD·ETC 가 섞이면 회의록·수율표 문장이 처방 근거로 올라온다.
                     p_doc_type        => :docTypes,
                     -- 권한이 제한된 청크는 아예 후보에서 뺀다. (앞단 마스킹)
                     p_strict          => true
                 ) s
            INNER JOIN vec.tb_doc d ON d.doc_uid = s.doc_uid
            WHERE (d.retention_until IS NULL OR d.retention_until >= :today)
              AND char_length(s.chunk_text) >= :minLen
            ORDER BY s.rank_no
            LIMIT :limit
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("embedding", "[" + queryEmbedding.joinToString(",") + "]")
            .addValue("userId", userId)
            .addValue("queryText", queryText)
            .addValue("topK", limit * 3)
            .addValue("docTypes", PRESCRIPTION_DOC_TYPES)
            .addValue("today", LocalDate.now())
            .addValue("minLen", MIN_CHUNK_LEN)
            .addValue("limit", limit)

        return jdbcTemplate.query(sql, params) { rs, _ ->
            mapOf(
                "chunkId" to rs.getLong("chunk_id"),
                "text" to rs.getString("chunk_text"),
                "title" to rs.getString("title"),
                "docTypeCd" to rs.getString("doc_type_cd"),
                "docDate" to rs.getDate("doc_date")?.toLocalDate()?.toString()
            )
        }
    }

    /**
     * 처방 근거 후보 — **임베딩 없이** 키워드로 찾는다.
     *
     * 채팅 모델을 사내 LLM 서버(dwje-ax)로 옮긴 뒤 임베딩 모델(bge-m3)이 없는 환경이 생겼다.
     * 그때 원인 분석이 문서 근거를 통째로 잃지 않도록 전문 검색(tsv, 낱말 OR)으로 대신한다.
     * 권한은 벡터 검색과 같은 `vec.fn_allowed_doc(strict)` 로 거른다(통합관리자는 전부).
     */
    fun searchPrescriptionCandidatesByKeyword(userId: String, queryText: String, limit: Int): List<Map<String, Any?>> {
        val tsQuery = AiChatRepository.toOrTsQuery(queryText) ?: return emptyList()
        val sql = """
            SELECT c.chunk_id, c.chunk_text, d.title, d.doc_type_cd, d.doc_date
            FROM vec.tb_doc_chunk c
            INNER JOIN vec.tb_doc d ON d.doc_id = c.doc_id
            INNER JOIN vec.fn_allowed_doc(:userId, true) a ON a.doc_id = c.doc_id
            WHERE c.is_current
              AND c.del_flg = 'N'
              AND d.doc_type_cd IN (:docTypes)
              AND (d.retention_until IS NULL OR d.retention_until >= :today)
              AND char_length(c.chunk_text) >= :minLen
              AND c.tsv @@ to_tsquery('simple', :tsQuery)
            ORDER BY ts_rank(c.tsv, to_tsquery('simple', :tsQuery)) DESC, d.doc_date DESC NULLS LAST
            LIMIT :limit
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("userId", userId)
            .addValue("tsQuery", tsQuery)
            .addValue("docTypes", PRESCRIPTION_DOC_TYPES.toList())
            .addValue("today", LocalDate.now())
            .addValue("minLen", MIN_CHUNK_LEN)
            .addValue("limit", limit)

        return jdbcTemplate.query(sql, params) { rs, _ ->
            mapOf(
                "chunkId" to rs.getLong("chunk_id"),
                "text" to rs.getString("chunk_text"),
                "title" to rs.getString("title"),
                "docTypeCd" to rs.getString("doc_type_cd"),
                "docDate" to rs.getDate("doc_date")?.toLocalDate()?.toString()
            )
        }
    }

    /**
     * 근거 대조용 — 청크 한 건을 권한·현행·보존기한까지 확인해 돌려준다.
     *
     * 돌려주지 못하는 이유가 셋이므로 호출부가 사유를 구분할 수 있게 상태를 함께 낸다.
     *  - 청크 자체가 없거나 현행이 아님 → `null`
     *  - 권한 없음 → `allowed = false`
     *  - 보존기한 경과 → `expired = true`
     */
    fun findChunkForVerify(chunkId: Long, userId: String): DocChunkRef? {
        val sql = """
            SELECT c.chunk_id, c.chunk_seq, c.chunk_text, c.page_no, c.section_path, c.heading,
                   d.title, d.doc_type_cd, d.doc_date, d.doc_uid, d.file_nm, d.source_path,
                   (a.doc_id IS NOT NULL)                                        AS allowed,
                   (d.retention_until IS NOT NULL AND d.retention_until < :today) AS expired
            FROM vec.tb_doc_chunk c
            INNER JOIN vec.tb_doc d ON d.doc_id = c.doc_id
            LEFT JOIN vec.fn_allowed_doc(:userId, true) a ON a.doc_id = c.doc_id
            WHERE c.chunk_id = :chunkId
              AND c.is_current
              AND c.del_flg = 'N'
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("chunkId", chunkId)
            .addValue("userId", userId)
            .addValue("today", LocalDate.now())

        return jdbcTemplate.query(sql, params) { rs, _ ->
            DocChunkRef(
                chunkId = rs.getLong("chunk_id"),
                chunkSeq = rs.getInt("chunk_seq"),
                text = rs.getString("chunk_text") ?: "",
                title = rs.getString("title"),
                docTypeCd = rs.getString("doc_type_cd"),
                docDate = rs.getDate("doc_date")?.toLocalDate()?.toString(),
                docUid = rs.getString("doc_uid"),
                fileNm = rs.getString("file_nm"),
                sourcePath = rs.getString("source_path"),
                pageNo = rs.getObject("page_no") as? Int,
                sectionPath = rs.getString("section_path"),
                heading = rs.getString("heading"),
                allowed = rs.getBoolean("allowed"),
                expired = rs.getBoolean("expired")
            )
        }.firstOrNull()
    }

    companion object {
        /** 너무 짧은 청크는 조치 근거가 되지 못한다. (머리글·표 조각 등) */
        private const val MIN_CHUNK_LEN = 80

        /**
         * 처방 근거로 삼는 문서 유형 — 불량 분석 문서만.
         *
         * FACA 1,090건 · REPORT_8D 37건. MINUTES(회의록)·YIELD(수율표)·ETC 를
         * 섞으면 조치가 아닌 문장이 처방 근거로 올라온다.
         */
        private val PRESCRIPTION_DOC_TYPES = arrayOf("FACA", "REPORT_8D")
    }
}

/** 근거 대조용 청크 — 권한·보존기한 판정을 함께 담는다. */
data class DocChunkRef(
    val chunkId: Long,
    /**
     * 문서 내 청크 순번. **0 은 합성 개요 청크**다.
     *
     * 문서마다 하나씩 있고(1,476건) 문서명·고객사·이슈 폴더명·경로를 파이프라인이
     * 만들어 넣은 것이다. 사람이 쓴 원인·대책이 아니라서 인용 대조는 통과하지만
     * 근거로서 실체가 없다. 처방 근거에서는 제외한다.
     */
    val chunkSeq: Int,
    val text: String,
    val title: String?,
    val docTypeCd: String?,
    val docDate: String?,
    val docUid: String?,
    /** 원본 파일명 — 사용자가 실제로 문서를 찾아볼 수 있어야 한다 */
    val fileNm: String?,
    /** 원본 파일 경로 (NAS) */
    val sourcePath: String?,
    /** 문서 내 쪽 번호 */
    val pageNo: Int?,
    /** 문서 구조 경로 — 고객사 > 이슈 > 문서 */
    val sectionPath: String?,
    /** 청크가 속한 소제목 */
    val heading: String?,
    val allowed: Boolean,
    val expired: Boolean
)
