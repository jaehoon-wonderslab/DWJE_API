package com.dwje.api.repository

import com.dwje.api.common.util.Rs
import com.dwje.api.common.util.SqlLikeUtils
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

/**
 * 용어 사전 데이터 접근 Repository (SY-06)
 *
 * 공식 용어(ax.tb_gls_term)와 현장 유사어(ax.tb_gls_variant)를 관리하며,
 * 자연어 질의 시 유사어를 공식 용어로 정규화하는 데 사용된다.
 */
@Repository
class GlossaryRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) {

    /**
     * 용어 사전 요약 지표를 조회한다. (No.170)
     *
     * @param userId 조회자 사번 — 내가 등록한 유사어 건수 산출용
     */
    fun findSummary(userId: String): Map<String, Any?> {
        // 유사어 건수는 부모 용어가 살아 있는 것만 센다.
        // 목록(findTerms)과 정규화 사전이 이미 t.use_flg = 'Y' 로 조인하므로,
        // 요약만 전체를 세면 용어를 삭제한 뒤 "용어 0건 · 유사어 1건" 처럼 어긋난다.
        val sql = """
            SELECT
                (SELECT count(*) FROM ax.tb_gls_term WHERE use_flg = 'Y') AS term_cnt,
                (
                    SELECT count(*)
                      FROM ax.tb_gls_variant v
                     INNER JOIN ax.tb_gls_term t ON t.term_id = v.term_id AND t.use_flg = 'Y'
                ) AS variant_cnt,
                (SELECT count(*) FROM ax.tb_gls_domain WHERE use_flg = 'Y') AS domain_cnt,
                (
                    SELECT count(*)
                      FROM ax.tb_gls_variant v
                     INNER JOIN ax.tb_gls_term t ON t.term_id = v.term_id AND t.use_flg = 'Y'
                     WHERE v.owner_user_id = :userId
                ) AS my_variant_cnt,
                (
                    SELECT count(*)
                      FROM ax.tb_gls_term t
                     WHERE t.use_flg = 'Y'
                       AND NOT EXISTS (SELECT 1 FROM ax.tb_gls_variant v WHERE v.term_id = t.term_id)
                ) AS no_variant_term_cnt
        """.trimIndent()

        return jdbcTemplate.queryForObject(sql, MapSqlParameterSource("userId", userId)) { rs, _ ->
            mapOf(
                "termCnt" to rs.getLong("term_cnt"),
                "variantCnt" to rs.getLong("variant_cnt"),
                "domainCnt" to rs.getLong("domain_cnt"),
                "myVariantCnt" to rs.getLong("my_variant_cnt"),
                "noVariantTermCnt" to rs.getLong("no_variant_term_cnt")
            )
        } ?: emptyMap()
    }

    /**
     * 도메인별 용어 건수를 조회한다. (요약 화면 byDomain)
     */
    fun findCountByDomain(): List<Map<String, Any?>> {
        val sql = """
            SELECT d.domain_id, d.domain_nm, count(t.term_id) AS term_cnt
            FROM ax.tb_gls_domain d
            LEFT JOIN ax.tb_gls_term t ON t.domain_id = d.domain_id AND t.use_flg = 'Y'
            WHERE d.use_flg = 'Y'
            GROUP BY d.domain_id, d.domain_nm, d.sort_seq
            ORDER BY d.sort_seq, d.domain_nm
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource()) { rs, _ ->
            mapOf(
                "domainId" to rs.getInt("domain_id"),
                "domain" to rs.getString("domain_nm"),
                "termCnt" to rs.getLong("term_cnt")
            )
        }
    }

    /**
     * 용어 목록을 조회한다. (No.171)
     *
     * @param keyword  용어·정의·유사어 검색어
     * @param domainCd 도메인명
     * @param limit    조회 건수
     * @param offset   건너뛸 건수
     */
    fun findTerms(keyword: String?, domainCd: String?, limit: Int, offset: Int): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT
                t.term_id,
                t.term,
                t.term_def,
                d.domain_id,
                d.domain_nm,
                t.upd_date
            FROM ax.tb_gls_term t
            INNER JOIN ax.tb_gls_domain d ON d.domain_id = t.domain_id
            WHERE t.use_flg = 'Y'
            """.trimIndent()
        )

        val params = MapSqlParameterSource()
        appendTermFilters(sql, params, keyword, domainCd)

        sql.append("\nORDER BY d.sort_seq, t.term\nLIMIT :limit OFFSET :offset")
        params.addValue("limit", limit).addValue("offset", offset)

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "termId" to rs.getInt("term_id"),
                "term" to rs.getString("term"),
                "definition" to rs.getString("term_def"),
                "domainId" to rs.getInt("domain_id"),
                "domain" to rs.getString("domain_nm"),
                "updatedAt" to Rs.dateTime(rs, "upd_date")
            )
        }
    }

    /** 용어 목록 전체 건수 */
    fun countTerms(keyword: String?, domainCd: String?): Long {
        val sql = StringBuilder(
            """
            SELECT count(*)
            FROM ax.tb_gls_term t
            INNER JOIN ax.tb_gls_domain d ON d.domain_id = t.domain_id
            WHERE t.use_flg = 'Y'
            """.trimIndent()
        )
        val params = MapSqlParameterSource()
        appendTermFilters(sql, params, keyword, domainCd)
        return jdbcTemplate.queryForObject(sql.toString(), params, Long::class.java) ?: 0L
    }

    /** 용어 목록/건수 공통 동적 조건 */
    private fun appendTermFilters(
        sql: StringBuilder,
        params: MapSqlParameterSource,
        keyword: String?,
        domainCd: String?
    ) {
        SqlLikeUtils.contains(keyword)?.let {
            sql.append(
                """

                AND (
                        t.term     LIKE :keyword ESCAPE '\'
                     OR t.term_def LIKE :keyword ESCAPE '\'
                     OR EXISTS (
                            SELECT 1 FROM ax.tb_gls_variant v
                             WHERE v.term_id = t.term_id AND v.word LIKE :keyword ESCAPE '\'
                        )
                )
                """.trimIndent()
            )
            params.addValue("keyword", it)
        }
        if (!domainCd.isNullOrBlank()) {
            sql.append(" AND d.domain_nm = :domainCd")
            params.addValue("domainCd", domainCd.trim())
        }
    }

    /**
     * 지정 용어들의 유사어를 한 번에 조회한다. (N+1 방지)
     *
     * @param termIds 용어 ID 목록
     * @param userId  조회자 사번 — 본인 등록 유사어만 수정 가능(editable) 판정
     */
    fun findVariantsByTermIds(termIds: List<Int>, userId: String): Map<Int, List<Map<String, Any?>>> {
        if (termIds.isEmpty()) return emptyMap()

        val sql = """
            SELECT
                v.variant_id,
                v.term_id,
                v.word,
                v.owner_user_id,
                v.owner_dept_nm,
                u.user_nm AS owner_nm,
                v.reg_at
            FROM ax.tb_gls_variant v
            LEFT JOIN ax.tb_sys_user u ON u.user_id = v.owner_user_id
            WHERE v.term_id = ANY(:termIds)
            ORDER BY v.term_id, v.reg_at
        """.trimIndent()

        val params = MapSqlParameterSource("termIds", termIds.toTypedArray())

        return jdbcTemplate.query(sql, params) { rs, _ ->
            val ownerUserId = rs.getString("owner_user_id")
            rs.getInt("term_id") to mapOf<String, Any?>(
                "variantId" to rs.getInt("variant_id"),
                "word" to rs.getString("word"),
                "byEmpNo" to ownerUserId,
                "byName" to rs.getString("owner_nm"),
                "byDept" to rs.getString("owner_dept_nm"),
                "at" to Rs.dateTime(rs, "reg_at"),
                // 본인이 등록한 유사어만 수정·삭제할 수 있다.
                "editable" to (ownerUserId == userId)
            )
        }.groupBy({ it.first }, { it.second })
    }

    /**
     * 공식 용어를 등록한다. (No.172)
     *
     * `btrim` 은 서비스의 `trim()` 과 겹치지만 일부러 둔다. 유니크 인덱스가
     * `lower(btrim(term))` 이라 앞뒤 공백이 붙은 채 저장되면 검사 기준과 저장값이 어긋난다.
     * 호출부가 하나 늘어도 그 불변식이 깨지지 않게 SQL 에서도 막는다. (`insertVariant` 도 같다)
     *
     * 중복이면 [org.springframework.dao.DuplicateKeyException] — 서비스가 409 로 바꾼다.
     *
     * @return 생성된 용어 ID
     */
    fun insertTerm(term: String, definition: String, domainId: Int, actor: String): Int {
        val sql = """
            INSERT INTO ax.tb_gls_term (term, term_def, domain_id, use_flg, ins_user, upd_user)
            VALUES (btrim(:term), :definition, :domainId, 'Y', :actor, :actor)
            RETURNING term_id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("term", term)
            .addValue("definition", definition)
            .addValue("domainId", domainId)
            .addValue("actor", actor)

        return jdbcTemplate.queryForObject(sql, params, Int::class.java) ?: 0
    }

    /**
     * 공식 용어를 수정한다. (No.173)
     */
    fun updateTerm(termId: Int, term: String, definition: String, domainId: Int, actor: String): Int {
        val sql = """
            UPDATE ax.tb_gls_term
               SET term      = btrim(:term),
                   term_def  = :definition,
                   domain_id = :domainId,
                   upd_date  = now(),
                   upd_user  = :actor
             WHERE term_id = :termId
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("termId", termId)
            .addValue("term", term)
            .addValue("definition", definition)
            .addValue("domainId", domainId)
            .addValue("actor", actor)

        return jdbcTemplate.update(sql, params)
    }

    /**
     * 공식 용어를 사용 중지한다. (소프트 삭제)
     *
     * `use_flg` 는 목록·요약·분류별 집계·정규화 사전 조회가 모두 함께 보는 값이다.
     * 'N' 으로 내리면 그 용어와 딸린 유사어가 정규화 사전에서도 함께 빠진다.
     * (정규화 사전 쿼리가 `tb_gls_term t ... AND t.use_flg = 'Y'` 로 조인한다)
     *
     * 물리 삭제하지 않는 이유는 `tb_gls_variant.term_id` 가 이 표를 참조하고,
     * 표에 `use_flg` 가 이미 있어 비활성 표기가 설계에 들어 있기 때문이다.
     * (유사어 쪽은 `use_flg` 가 없어 물리 삭제한다 — 표마다 방식이 다르다)
     *
     * @return 변경된 행 수. 이미 사용 중지된 용어면 0
     */
    fun deactivateTerm(termId: Int, actor: String): Int {
        val sql = """
            UPDATE ax.tb_gls_term
               SET use_flg  = 'N',
                   upd_date = now(),
                   upd_user = :actor
             WHERE term_id = :termId
               AND use_flg = 'Y'
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("termId", termId)
            .addValue("actor", actor)

        return jdbcTemplate.update(sql, params)
    }

    /**
     * 용어의 등록자와 사용 여부를 조회한다. (삭제 권한 판정용)
     *
     * @return null 이면 그런 용어가 없다
     */
    fun findTermOwner(termId: Int): Map<String, Any?>? {
        val sql = """
            SELECT ins_user, use_flg
            FROM ax.tb_gls_term
            WHERE term_id = :termId
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource("termId", termId)) { rs, _ ->
            mapOf("insUser" to rs.getString("ins_user"), "active" to Rs.yn(rs, "use_flg"))
        }.firstOrNull()
    }

    /** 용어에 달린 유사어 건수를 조회한다. (삭제 시 함께 빠지는 건수 안내용) */
    fun countVariantsByTerm(termId: Int): Long =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ax.tb_gls_variant WHERE term_id = :termId",
            MapSqlParameterSource("termId", termId),
            Long::class.java
        ) ?: 0L

    /**
     * 용어명으로 행을 찾는다. 대소문자·앞뒤 공백을 무시하며, 사용 중지된 것도 포함한다.
     *
     * 비교 식을 DB 유니크 인덱스와 글자 그대로 맞춰 둔다 (2026-09-16 신설) :
     *      uq_gls_term_lower UNIQUE btree (lower(TRIM(BOTH FROM term)))
     * 식이 어긋나면 "조회로는 안 걸렸는데 INSERT 는 막히는" 구간이 생겨 500 이 나간다.
     * 인덱스와 같은 식이라 이 조회는 그 인덱스를 그대로 탄다.
     *
     * 사용 중지된 행도 이름을 계속 점유한다 — 인덱스가 부분 인덱스가 아니기 때문이다.
     * 그래서 등록 시 이 조회로 사용 중지된 동명 행을 찾아 되살린다.
     *
     * 저장된 표기(`term`)를 함께 돌려준다. 중복 안내에 입력값이 아니라 **이미 등록된 표기**를
     * 보여 줘야 사용자가 'can' 을 왜 못 넣는지("CAN" 이 있다) 알 수 있다.
     *
     * @return null 이면 그 이름을 쓰는 행이 없다
     */
    fun findTermByName(term: String): Map<String, Any?>? {
        val sql = """
            SELECT term_id, term, use_flg
            FROM ax.tb_gls_term
            WHERE lower(btrim(term)) = lower(btrim(:term))
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource("term", term)) { rs, _ ->
            mapOf(
                "termId" to rs.getInt("term_id"),
                "term" to rs.getString("term"),
                "active" to Rs.yn(rs, "use_flg")
            )
        }.firstOrNull()
    }

    /**
     * 유사어를 단어로 찾는다. 대소문자·앞뒤 공백을 무시한다.
     *
     * `uq_gls_variant_word UNIQUE (lower(word))` 는 **표 전체에서** 한 번만 쓸 수 있게 한다 —
     * 용어별이 아니다. 그래서 다른 용어에 붙은 유사어도 걸리며, 안내에 그 용어명을 함께 담는다.
     *
     * 인덱스는 `lower(word)` 지만 여기서는 `btrim` 까지 씌운다. 저장은 항상 trim 해서 넣으므로
     * 결과는 같고, 앞뒤 공백만 다른 입력(`' CAN'`)이 사전에 걸러진다.
     *
     * @param excludeVariantId 수정 시 자기 자신은 제외한다
     * @return null 이면 그 단어를 쓰는 유사어가 없다
     */
    fun findVariantByWord(word: String, excludeVariantId: Int? = null): Map<String, Any?>? {
        val sql = """
            SELECT v.variant_id, v.word, v.term_id, t.term, v.owner_user_id
            FROM ax.tb_gls_variant v
            INNER JOIN ax.tb_gls_term t ON t.term_id = v.term_id
            WHERE lower(btrim(v.word)) = lower(btrim(:word))
              AND (:excludeVariantId::int IS NULL OR v.variant_id <> :excludeVariantId::int)
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("word", word)
            .addValue("excludeVariantId", excludeVariantId)

        return jdbcTemplate.query(sql, params) { rs, _ ->
            mapOf(
                "variantId" to rs.getInt("variant_id"),
                "word" to rs.getString("word"),
                "termId" to rs.getInt("term_id"),
                "term" to rs.getString("term"),
                "ownerUserId" to rs.getString("owner_user_id")
            )
        }.firstOrNull()
    }

    /**
     * 사용 중지된 용어를 되살린다. (같은 이름으로 다시 등록할 때)
     *
     * 정의·분류는 새로 들어온 값으로 갱신한다. 딸린 유사어는 그대로 살아난다 —
     * 정규화 사전이 `t.use_flg = 'Y'` 로 조인하므로 사전에도 함께 돌아온다.
     * 되살아난 유사어 건수는 호출부가 응답에 담아 사용자에게 알린다.
     *
     * @return 변경된 행 수. 이미 사용 중이면 0
     */
    fun reviveTerm(termId: Int, definition: String, domainId: Int, actor: String): Int {
        val sql = """
            UPDATE ax.tb_gls_term
               SET use_flg   = 'Y',
                   term_def  = :definition,
                   domain_id = :domainId,
                   upd_date  = now(),
                   upd_user  = :actor
             WHERE term_id = :termId
               AND use_flg = 'N'
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("termId", termId)
            .addValue("definition", definition)
            .addValue("domainId", domainId)
            .addValue("actor", actor)

        return jdbcTemplate.update(sql, params)
    }

    /**
     * 유사어가 존재하는지 확인한다. (없는 대상과 남의 것을 구분하기 위함)
     *
     * `tb_gls_variant` 는 물리 삭제라 `use_flg` 가 없다. 행 존재만 본다.
     */
    fun existsVariant(variantId: Int): Boolean =
        jdbcTemplate.queryForObject(
            "SELECT exists(SELECT 1 FROM ax.tb_gls_variant WHERE variant_id = :variantId)",
            MapSqlParameterSource("variantId", variantId),
            Boolean::class.java
        ) ?: false

    /**
     * 용어 분류(도메인) 목록을 조회한다. (용어 등록 선택지)
     *
     * 요약(`summary.byDomain`)은 용어 건수 집계라 화면 선택지 원본으로 쓰기에 알맞지 않다.
     * 분류 자체를 묻는 질문은 이 쿼리로 답한다.
     */
    fun findDomains(): List<Map<String, Any?>> {
        val sql = """
            SELECT domain_id, domain_nm
            FROM ax.tb_gls_domain
            WHERE use_flg = 'Y'
            ORDER BY sort_seq, domain_nm
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource()) { rs, _ ->
            mapOf(
                "domainId" to rs.getInt("domain_id"),
                // 용어 등록(POST /glossary/terms)의 domainCd 에 그대로 넣는 값이다.
                "code" to rs.getString("domain_nm"),
                "name" to rs.getString("domain_nm")
            )
        }
    }

    /** 도메인명으로 도메인 ID 를 조회한다. */
    fun findDomainId(domainNm: String): Int? {
        val sql = "SELECT domain_id FROM ax.tb_gls_domain WHERE domain_nm = :domainNm AND use_flg = 'Y'"
        return jdbcTemplate.query(sql, MapSqlParameterSource("domainNm", domainNm)) { rs, _ -> rs.getInt("domain_id") }
            .firstOrNull()
    }

    /**
     * 유사어를 등록한다. (No.174)
     *
     * @return 생성된 유사어 ID
     */
    fun insertVariant(termId: Int, word: String, ownerUserId: String, ownerDeptNm: String?): Int {
        val sql = """
            INSERT INTO ax.tb_gls_variant (term_id, word, owner_user_id, owner_dept_nm, reg_at, upd_at)
            VALUES (:termId, btrim(:word), :ownerUserId, :ownerDeptNm, now(), now())
            RETURNING variant_id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("termId", termId)
            .addValue("word", word)
            .addValue("ownerUserId", ownerUserId)
            .addValue("ownerDeptNm", ownerDeptNm)

        return jdbcTemplate.queryForObject(sql, params, Int::class.java) ?: 0
    }

    /**
     * 유사어를 수정한다. (No.175 — 본인 등록 건만)
     */
    fun updateVariant(variantId: Int, word: String, ownerUserId: String, superAdmin: Boolean): Int {
        val sql = """
            UPDATE ax.tb_gls_variant
               SET word   = btrim(:word),
                   upd_at = now()
             WHERE variant_id = :variantId
               AND (:superAdmin = true OR owner_user_id = :ownerUserId)
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("variantId", variantId)
            .addValue("word", word)
            .addValue("ownerUserId", ownerUserId)
            .addValue("superAdmin", superAdmin)

        return jdbcTemplate.update(sql, params)
    }

    /**
     * 유사어를 삭제한다. (No.176 — 본인 등록 건만)
     */
    fun deleteVariant(variantId: Int, ownerUserId: String, superAdmin: Boolean): Int {
        val sql = """
            DELETE FROM ax.tb_gls_variant
             WHERE variant_id = :variantId
               AND (:superAdmin = true OR owner_user_id = :ownerUserId)
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("variantId", variantId)
            .addValue("ownerUserId", ownerUserId)
            .addValue("superAdmin", superAdmin)

        return jdbcTemplate.update(sql, params)
    }

    /**
     * 정규화 사전(유사어 → 공식 용어) 전체를 조회한다. (No.177 · 자연어 질의 전처리)
     *
     * 긴 유사어부터 치환해야 부분 치환 오류가 발생하지 않으므로 길이 내림차순으로 정렬한다.
     */
    fun findNormalizationDictionary(): List<Map<String, Any?>> {
        val sql = """
            SELECT v.variant_id, v.word, t.term_id, t.term
            FROM ax.tb_gls_variant v
            INNER JOIN ax.tb_gls_term t ON t.term_id = v.term_id AND t.use_flg = 'Y'
            ORDER BY length(v.word) DESC, v.word
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource()) { rs, _ ->
            mapOf(
                "variantId" to rs.getInt("variant_id"),
                "from" to rs.getString("word"),
                "to" to rs.getString("term"),
                "termId" to rs.getInt("term_id")
            )
        }
    }

    /** 용어 존재 여부 확인 */
    fun existsTerm(termId: Int): Boolean {
        val sql = "SELECT count(*) FROM ax.tb_gls_term WHERE term_id = :termId AND use_flg = 'Y'"
        return (jdbcTemplate.queryForObject(sql, MapSqlParameterSource("termId", termId), Long::class.java) ?: 0L) > 0
    }
}
