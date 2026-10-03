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
                "myVariantCnt" to rs.getLong("my_variant_cnt"),
                "noVariantTermCnt" to rs.getLong("no_variant_term_cnt")
            )
        } ?: emptyMap()
    }

    /**
     * 용어 목록을 조회한다. (No.171)
     *
     * @param keyword    용어·정의·유사어 검색어
     * @param limit      조회 건수
     * @param offset     건너뛸 건수
     * @param mineOnly   이 사번이 등록한 유사어가 있는 용어만 (용어 사전 관리의 「내 유사어」 내려받기)
     * @param hiddenKeys 열람자가 볼 수 없는 데이터 항목 — 이 항목이 걸린 용어는 검색어에 걸리지 않는다 (R-18)
     */
    fun findTerms(
        keyword: String?, limit: Int, offset: Int, mineOnly: String? = null, hiddenKeys: Collection<String> = emptyList()
    ): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT
                t.term_id,
                t.term,
                t.term_def,
                t.data_field_key,
                t.ins_date,
                t.upd_date
            FROM ax.tb_gls_term t
            WHERE t.use_flg = 'Y'
            """.trimIndent()
        )

        val params = MapSqlParameterSource()
        appendTermFilters(sql, params, keyword, mineOnly, hiddenKeys)

        sql.append("\nORDER BY t.term\nLIMIT :limit OFFSET :offset")
        params.addValue("limit", limit).addValue("offset", offset)

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "termId" to rs.getInt("term_id"),
                "term" to rs.getString("term"),
                "definition" to rs.getString("term_def"),
                // 이 용어를 볼 때 필요한 데이터 항목 — 서비스가 가림 판정에 쓰고 응답에서는 뺀다 (R-18, V75)
                "fieldKey" to rs.getString("data_field_key"),
                // 등록일 — 관리 화면 전체 내려받기 열 (07 GLS-12)
                "createdAt" to Rs.dateTime(rs, "ins_date"),
                "updatedAt" to Rs.dateTime(rs, "upd_date")
            )
        }
    }

    /** 용어 목록 전체 건수 */
    fun countTerms(keyword: String?, mineOnly: String? = null, hiddenKeys: Collection<String> = emptyList()): Long {
        val sql = StringBuilder(
            """
            SELECT count(*)
            FROM ax.tb_gls_term t
            WHERE t.use_flg = 'Y'
            """.trimIndent()
        )
        val params = MapSqlParameterSource()
        appendTermFilters(sql, params, keyword, mineOnly, hiddenKeys)
        return jdbcTemplate.queryForObject(sql.toString(), params, Long::class.java) ?: 0L
    }

    /** 용어 목록/건수 공통 동적 조건 */
    private fun appendTermFilters(
        sql: StringBuilder,
        params: MapSqlParameterSource,
        keyword: String?,
        mineOnly: String? = null,
        hiddenKeys: Collection<String> = emptyList()
    ) {
        // 열람자가 볼 수 없는 용어는 검색어에 걸리지 않는다 — 가린 행이 검색어로 「그런 이름이 있다」 를 드러내지 않게 (R-18)
        if (hiddenKeys.isNotEmpty() && SqlLikeUtils.contains(keyword) != null) {
            sql.append(" AND (t.data_field_key IS NULL OR NOT (t.data_field_key = ANY(:hiddenKeys)))")
            params.addValue("hiddenKeys", hiddenKeys.toTypedArray())
        }
        if (mineOnly != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM ax.tb_gls_variant mv WHERE mv.term_id = t.term_id AND mv.owner_user_id = :mineOnly)")
            params.addValue("mineOnly", mineOnly)
        }
        SqlLikeUtils.contains(keyword)?.let {
            sql.append(
                """

                AND (
                        t.term     ILIKE :keyword ESCAPE '\'
                     OR t.term_def ILIKE :keyword ESCAPE '\'
                     OR EXISTS (
                            SELECT 1 FROM ax.tb_gls_variant v
                             WHERE v.term_id = t.term_id AND v.word ILIKE :keyword ESCAPE '\'
                        )
                )
                """.trimIndent()
            )
            params.addValue("keyword", it)
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
     * @param fieldKey 이 용어를 볼 때 필요한 데이터 항목 (고객사 정보 = customer, 없으면 null)
     * @return 생성된 용어 ID
     */
    fun insertTerm(term: String, definition: String, fieldKey: String?, actor: String): Int {
        val sql = """
            INSERT INTO ax.tb_gls_term (term, term_def, data_field_key, use_flg, ins_user, upd_user)
            VALUES (btrim(:term), :definition, :fieldKey, 'Y', :actor, :actor)
            RETURNING term_id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("term", term)
            .addValue("definition", definition)
            .addValue("fieldKey", fieldKey)
            .addValue("actor", actor)

        return jdbcTemplate.queryForObject(sql, params, Int::class.java) ?: 0
    }

    /**
     * 공식 용어를 수정한다. (No.173)
     */
    fun updateTerm(termId: Int, term: String, definition: String, fieldKey: String?, actor: String): Int {
        val sql = """
            UPDATE ax.tb_gls_term
               SET term           = btrim(:term),
                   term_def       = :definition,
                   data_field_key = :fieldKey,
                   upd_date  = now(),
                   upd_user  = :actor
             WHERE term_id = :termId
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("termId", termId)
            .addValue("term", term)
            .addValue("definition", definition)
            .addValue("fieldKey", fieldKey)
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
            SELECT v.variant_id, v.word, v.term_id, t.term, t.use_flg AS term_use_flg, v.owner_user_id
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
                // 붙은 용어가 삭제됐는지 — 409 안내를 나눈다 (07 GLS-14)
                "termActive" to (rs.getString("term_use_flg") == "Y"),
                "ownerUserId" to rs.getString("owner_user_id")
            )
        }.firstOrNull()
    }

    /**
     * 사용 중지된 용어를 되살린다. (같은 이름으로 다시 등록할 때)
     *
     * 정의·가림 표시(데이터 항목)는 새로 들어온 값으로 갱신한다. 딸린 유사어는 그대로 살아난다 —
     * 정규화 사전이 `t.use_flg = 'Y'` 로 조인하므로 사전에도 함께 돌아온다.
     * 되살아난 유사어 건수는 호출부가 응답에 담아 사용자에게 알린다.
     *
     * @return 변경된 행 수. 이미 사용 중이면 0
     */
    fun reviveTerm(termId: Int, definition: String, fieldKey: String?, actor: String): Int {
        val sql = """
            UPDATE ax.tb_gls_term
               SET use_flg        = 'Y',
                   term_def       = :definition,
                   data_field_key = :fieldKey,
                   upd_date  = now(),
                   upd_user  = :actor
             WHERE term_id = :termId
               AND use_flg = 'N'
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("termId", termId)
            .addValue("definition", definition)
            .addValue("fieldKey", fieldKey)
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
        // sameAsTerm — 이 유사어가 다른 사용 중 공식 용어와 같은 낱말이면 그 용어(정규화에서 뺀다, GLS-02·03)
        val sql = """
            SELECT v.variant_id, v.word, t.term_id, t.term, t.term_def,
                   t.data_field_key AS field_key,
                   (SELECT o.term FROM ax.tb_gls_term o
                     WHERE o.use_flg = 'Y' AND o.term_id <> v.term_id
                       AND lower(btrim(o.term)) = lower(btrim(v.word)) LIMIT 1) AS same_as_term
            FROM ax.tb_gls_variant v
            INNER JOIN ax.tb_gls_term t ON t.term_id = v.term_id AND t.use_flg = 'Y'
            ORDER BY length(v.word) DESC, v.word
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource()) { rs, _ ->
            mapOf(
                "variantId" to rs.getInt("variant_id"),
                "from" to rs.getString("word"),
                "to" to rs.getString("term"),
                "termId" to rs.getInt("term_id"),
                "definition" to rs.getString("term_def"),
                // 용어의 데이터 항목 — 이 항목을 볼 수 없는 열람자에게는 LLM [용어] 블록·응답에서 뺀다 (R-18)
                "fieldKey" to rs.getString("field_key"),
                "sameAsTerm" to rs.getString("same_as_term")
            )
        }
    }

    /** 사용 중 공식 용어 중 이 낱말과 같은 것 (대소문자·앞뒤 공백 무시, uq_gls_term_lower 기준) — 유사어 등록 검증 */
    fun findActiveTermByWord(word: String): Map<String, Any?>? =
        jdbcTemplate.query(
            "SELECT term_id, term FROM ax.tb_gls_term WHERE use_flg = 'Y' AND lower(btrim(term)) = lower(btrim(:word)) LIMIT 1",
            MapSqlParameterSource("word", word)
        ) { rs, _ -> mapOf<String, Any?>("termId" to rs.getInt("term_id"), "term" to rs.getString("term")) }.firstOrNull()

    /**
     * 이 낱말을 안에 품은 다른 사용 중 공식 용어 — (건수, 첫 예시). 유사어 등록 경고용(GLS-03)
     *
     * @param excludeTermId 유사어가 붙을 용어(자기 용어는 빼고 센다)
     */
    fun findTermsContaining(word: String, excludeTermId: Int): Pair<Long, String?> =
        jdbcTemplate.queryForObject(
            """
            SELECT count(*) AS cnt, min(term) AS example
              FROM ax.tb_gls_term
             WHERE use_flg = 'Y' AND term_id <> :ex
               AND lower(term) <> lower(btrim(:w)) AND strpos(lower(term), lower(btrim(:w))) > 0
            """.trimIndent(),
            MapSqlParameterSource().addValue("w", word).addValue("ex", excludeTermId)
        ) { rs, _ -> rs.getLong("cnt") to rs.getString("example") } ?: (0L to null)

    /** 유사어가 붙은 용어 ID (없으면 null) */
    fun findVariantTermId(variantId: Int): Int? =
        jdbcTemplate.query(
            "SELECT term_id FROM ax.tb_gls_variant WHERE variant_id = :id",
            MapSqlParameterSource("id", variantId)
        ) { rs, _ -> rs.getInt("term_id") }.firstOrNull()

    /**
     * 위험 유사어 점검 재료 — 사용 중 용어에 붙은 유사어 전부와 「같은 낱말인 다른 공식 용어」·「품은 공식 용어 수」(GLS-03).
     * 위험 판정(한 글자·숫자·날짜)은 서비스가 [com.dwje.api.service.GlossaryVariantRules] 로 한다.
     */
    fun findRiskVariantCandidates(): List<Map<String, Any?>> =
        jdbcTemplate.query(
            """
            SELECT v.variant_id, v.word, t.term_id, t.term, u.user_nm AS owner_nm,
                   (SELECT o.term FROM ax.tb_gls_term o WHERE o.use_flg = 'Y' AND o.term_id <> v.term_id
                      AND lower(btrim(o.term)) = lower(btrim(v.word)) LIMIT 1) AS same_term,
                   (SELECT count(*) FROM ax.tb_gls_term o WHERE o.use_flg = 'Y' AND o.term_id <> v.term_id
                      AND lower(o.term) <> lower(btrim(v.word)) AND strpos(lower(o.term), lower(btrim(v.word))) > 0) AS sub_cnt
              FROM ax.tb_gls_variant v
              JOIN ax.tb_gls_term t ON t.term_id = v.term_id AND t.use_flg = 'Y'
              LEFT JOIN ax.tb_sys_user u ON u.user_id = v.owner_user_id
             ORDER BY v.word
            """.trimIndent(),
            MapSqlParameterSource()
        ) { rs, _ ->
            mapOf(
                "variantId" to rs.getInt("variant_id"), "word" to rs.getString("word"),
                "termId" to rs.getInt("term_id"), "term" to rs.getString("term"), "ownerName" to rs.getString("owner_nm"),
                "sameTerm" to rs.getString("same_term"), "subCnt" to rs.getLong("sub_cnt")
            )
        }

    /** 사용 중 용어 한 건 (상세, GLV-04). 없거나 삭제된 용어면 null */
    fun findTermDetail(termId: Int): Map<String, Any?>? =
        jdbcTemplate.query(
            """
            SELECT t.term_id, t.term, t.term_def, t.data_field_key, t.upd_date
              FROM ax.tb_gls_term t
             WHERE t.term_id = :termId AND t.use_flg = 'Y'
            """.trimIndent(),
            MapSqlParameterSource("termId", termId)
        ) { rs, _ ->
            mapOf(
                "termId" to rs.getInt("term_id"), "term" to rs.getString("term"), "definition" to rs.getString("term_def"),
                "fieldKey" to rs.getString("data_field_key"), "updatedAt" to Rs.dateTime(rs, "upd_date")
            )
        }.firstOrNull()

    /**
     * 관련 용어 (GLV-04) — 규칙 3종, 이 순서로 최대 [limit] 건.
     * 1. REF_IN_DEF : 이 용어의 뜻에 다른 용어 이름이 들어 있음(그 이름이 3자 이상)
     * 2. REF_BY     : 다른 용어의 뜻에 이 용어 이름이 들어 있음(이 이름이 3자 이상)
     * 3. NAME_OVERLAP : 이름이 서로를 품음 (V75 전에는 같은 분류 안에서만 보던 SAME_DOMAIN_NAME)
     * 대소문자는 가리지 않는다.
     */
    fun findRelatedTerms(termId: Int, limit: Int = 10): List<Map<String, Any?>> =
        jdbcTemplate.query(
            """
            WITH me AS (SELECT term_id, term, coalesce(term_def, '') AS term_def
                          FROM ax.tb_gls_term WHERE term_id = :termId AND use_flg = 'Y'),
            cand AS (
              SELECT o.term_id, o.term, o.data_field_key,
                     CASE WHEN char_length(o.term) > 2 AND strpos(lower(me.term_def), lower(o.term)) > 0 THEN 1
                          WHEN char_length(me.term) > 2 AND strpos(lower(coalesce(o.term_def, '')), lower(me.term)) > 0 THEN 2
                          WHEN strpos(lower(o.term), lower(me.term)) > 0 OR strpos(lower(me.term), lower(o.term)) > 0 THEN 3
                     END AS ord
                FROM me
                JOIN ax.tb_gls_term o ON o.use_flg = 'Y' AND o.term_id <> me.term_id
            )
            SELECT term_id, term, data_field_key, ord FROM cand WHERE ord IS NOT NULL
             ORDER BY ord, term
             LIMIT :limit
            """.trimIndent(),
            MapSqlParameterSource().addValue("termId", termId).addValue("limit", limit)
        ) { rs, _ ->
            mapOf(
                "termId" to rs.getInt("term_id"), "term" to rs.getString("term"), "fieldKey" to rs.getString("data_field_key"),
                "reasonCd" to when (rs.getInt("ord")) { 1 -> "REF_IN_DEF"; 2 -> "REF_BY"; else -> "NAME_OVERLAP" }
            )
        }

    /** 용어 존재 여부 확인 */
    fun existsTerm(termId: Int): Boolean {
        val sql = "SELECT count(*) FROM ax.tb_gls_term WHERE term_id = :termId AND use_flg = 'Y'"
        return (jdbcTemplate.queryForObject(sql, MapSqlParameterSource("termId", termId), Long::class.java) ?: 0L) > 0
    }

    /** 용어 사전의 마지막 변경 시각 — 변경 이력(V60)이 있으면 그 최신, 없으면 용어·유사어 수정 시각 최신 */
    fun findLastChangedAt(): String? =
        jdbcTemplate.query(
            """
            SELECT greatest(
                (SELECT max(changed_at) FROM ax.tb_gls_change_log),
                (SELECT max(upd_date) FROM ax.tb_gls_term),
                (SELECT max(coalesce(upd_at, reg_at)) FROM ax.tb_gls_variant)
            ) AS at
            """.trimIndent(),
            MapSqlParameterSource()
        ) { rs, _ -> com.dwje.api.common.util.Rs.dateTime(rs, "at") }.firstOrNull()

    /** 용어에 걸린 데이터 항목 key 들 (R-18, V75) — 열람자가 못 보는 것을 고르는 재료 */
    fun findTermFieldKeys(): Set<String> =
        jdbcTemplate.query(
            "SELECT DISTINCT data_field_key FROM ax.tb_gls_term WHERE data_field_key IS NOT NULL",
            MapSqlParameterSource()
        ) { rs, _ -> rs.getString("data_field_key") }.toSet()

    /** 용어의 데이터 항목 key — 사용 여부 무관, 없으면 null */
    fun findTermFieldKey(termId: Int): String? =
        jdbcTemplate.query("SELECT data_field_key FROM ax.tb_gls_term WHERE term_id = :id", MapSqlParameterSource("id", termId)) { rs, _ ->
            rs.getString("data_field_key")
        }.firstOrNull()

    /**
     * 사전 변경 이력 1행 (07 GLS-07) — 업무 쓰기와 같은 트랜잭션에서 성공 뒤에 부른다. 409 로 롤백되면 이력도 남지 않는다.
     * JSON 키는 API 응답에 그대로 나가므로 camelCase(term, termDef, customerInfo, word)로 쓴다.
     * V75 전 이력에는 분류 이름(domainNm)이 남아 있다 — 지우지 않는다.
     */
    fun insertChangeLog(
        actorId: String, actorDeptNm: String?, targetCd: String, actionCd: String,
        termId: Int, variantId: Int?, before: Map<String, Any?>?, after: Map<String, Any?>?
    ): Int =
        jdbcTemplate.update(
            """
            INSERT INTO ax.tb_gls_change_log (actor_id, actor_dept_nm, target_cd, action_cd, term_id, variant_id, before_json, after_json)
            VALUES (:actorId, :actorDeptNm, :targetCd, :actionCd, :termId, :variantId, CAST(:before AS jsonb), CAST(:after AS jsonb))
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("actorId", actorId).addValue("actorDeptNm", actorDeptNm?.take(50))
                .addValue("targetCd", targetCd).addValue("actionCd", actionCd)
                .addValue("termId", termId).addValue("variantId", variantId)
                .addValue("before", before?.let { JSON.writeValueAsString(it) })
                .addValue("after", after?.let { JSON.writeValueAsString(it) })
        )

    /** 이력용 용어 값 — 사용 여부와 무관 */
    fun findTermSnapshot(termId: Int): Map<String, Any?>? =
        jdbcTemplate.query(
            """
            SELECT t.term, t.term_def, t.data_field_key
            FROM ax.tb_gls_term t
            WHERE t.term_id = :termId
            """.trimIndent(),
            MapSqlParameterSource("termId", termId)
        ) { rs, _ ->
            mapOf<String, Any?>("term" to rs.getString("term"), "termDef" to rs.getString("term_def"),
                "customerInfo" to (rs.getString("data_field_key") == com.dwje.api.common.util.DataField.CUSTOMER))
        }.firstOrNull()

    /** 이력용 유사어 값 — 낱말·용어·등록자 */
    fun findVariantSnapshot(variantId: Int): Map<String, Any?>? =
        jdbcTemplate.query(
            "SELECT word, term_id, owner_user_id FROM ax.tb_gls_variant WHERE variant_id = :variantId",
            MapSqlParameterSource("variantId", variantId)
        ) { rs, _ ->
            mapOf<String, Any?>("word" to rs.getString("word"), "termId" to rs.getInt("term_id"), "ownerUserId" to rs.getString("owner_user_id"))
        }.firstOrNull()

    private fun appendChangeFilters(sql: StringBuilder, params: MapSqlParameterSource, termId: Int?, from: java.time.LocalDate?, to: java.time.LocalDate?) {
        if (termId != null) {
            sql.append(" AND c.term_id = :termId")
            params.addValue("termId", termId)
        }
        if (from != null) {
            sql.append(" AND c.changed_at >= :from")
            params.addValue("from", java.sql.Timestamp.valueOf(from.atStartOfDay()))
        }
        if (to != null) {
            sql.append(" AND c.changed_at < :toEx")
            params.addValue("toEx", java.sql.Timestamp.valueOf(to.plusDays(1).atStartOfDay()))
        }
    }

    /** 사전 변경 이력 목록 (07 GLS-07) — 최신 순 */
    fun findChanges(termId: Int?, from: java.time.LocalDate?, to: java.time.LocalDate?, limit: Int, offset: Int): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT c.change_id, c.changed_at, c.actor_id, u.user_nm AS actor_nm, c.target_cd, c.action_cd,
                   c.term_id, t.term, t.data_field_key, c.variant_id, c.before_json::text AS before_json, c.after_json::text AS after_json
            FROM ax.tb_gls_change_log c
            LEFT JOIN ax.tb_sys_user u ON u.user_id = c.actor_id
            LEFT JOIN ax.tb_gls_term t ON t.term_id = c.term_id
            WHERE 1 = 1
            """.trimIndent()
        )
        val params = MapSqlParameterSource()
        appendChangeFilters(sql, params, termId, from, to)
        sql.append(" ORDER BY c.changed_at DESC, c.change_id DESC LIMIT :limit OFFSET :offset")
        params.addValue("limit", limit).addValue("offset", offset)
        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "changeId" to rs.getLong("change_id"),
                "at" to Rs.dateTime(rs, "changed_at"),
                "actorId" to rs.getString("actor_id"),
                "actorNm" to rs.getString("actor_nm"),
                "targetCd" to rs.getString("target_cd"),
                "actionCd" to rs.getString("action_cd"),
                "termId" to rs.getInt("term_id"),
                "term" to rs.getString("term"),
                "fieldKey" to rs.getString("data_field_key"),
                "variantId" to Rs.intOrNull(rs, "variant_id"),
                "before" to rs.getString("before_json")?.let { JSON.readValue(it, Map::class.java) },
                "after" to rs.getString("after_json")?.let { JSON.readValue(it, Map::class.java) }
            )
        }
    }

    fun countChanges(termId: Int?, from: java.time.LocalDate?, to: java.time.LocalDate?): Long {
        val sql = StringBuilder("SELECT count(*) FROM ax.tb_gls_change_log c WHERE 1 = 1")
        val params = MapSqlParameterSource()
        appendChangeFilters(sql, params, termId, from, to)
        return jdbcTemplate.queryForObject(sql.toString(), params, Long::class.java) ?: 0L
    }

    /**
     * 마지막 변경 수행자 이름 (07 GLS-06) — 사번은 주지 않는다.
     * 변경 이력(V60)이 있으면 그 최신 행, 아직 없으면(이력 기록 전 데이터) [findLastChangedAt] 과 같은 기준으로
     * 용어 수정자·유사어 등록자 중 가장 최근 사람이다.
     */
    fun findLastChangedBy(): String? =
        jdbcTemplate.query(
            """
            SELECT coalesce(u.user_nm, x.actor_id) AS nm
            FROM (
                (SELECT c.actor_id, c.changed_at AS at, 1 AS pri FROM ax.tb_gls_change_log c ORDER BY c.changed_at DESC, c.change_id DESC LIMIT 1)
                UNION ALL
                (SELECT coalesce(t.upd_user, t.ins_user), coalesce(t.upd_date, t.ins_date), 2 FROM ax.tb_gls_term t
                  ORDER BY coalesce(t.upd_date, t.ins_date) DESC NULLS LAST LIMIT 1)
                UNION ALL
                (SELECT v.owner_user_id, coalesce(v.upd_at, v.reg_at), 2 FROM ax.tb_gls_variant v
                  ORDER BY coalesce(v.upd_at, v.reg_at) DESC NULLS LAST LIMIT 1)
            ) x
            LEFT JOIN ax.tb_sys_user u ON u.user_id = x.actor_id
            WHERE x.actor_id IS NOT NULL
            ORDER BY x.pri, x.at DESC NULLS LAST
            LIMIT 1
            """.trimIndent(),
            MapSqlParameterSource()
        ) { rs, _ -> rs.getString("nm") }.firstOrNull()

    companion object {
        private val JSON = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
    }
}
