package com.dwje.api.repository

import com.dwje.api.common.util.Rs
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

/**
 * 제품군 순위 관리 Repository (SY-07)
 *
 * 참조 테이블 : ax.tb_prod_family, ax.tb_prod_product, ax.tb_prod_rank_log
 *
 * 제품 전체 순위(rank_no)는 제품군 순위(family.rank_no)와 제품군 내 순서(seq_in_family)로
 * 결정되며, 순위 변경 시 재계산한다.
 */
@Repository
class ProductRankRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) {

    /**
     * 제품군 순위 목록을 조회한다. (No.179)
     */
    fun findFamilies(): List<Map<String, Any?>> {
        val sql = """
            SELECT
                f.family_id, f.family_cd, f.family_nm, f.rank_no, f.def_rank_no,
                (SELECT count(*) FROM ax.tb_prod_product p
                  WHERE p.family_id = f.family_id AND p.use_flg = 'Y')          AS product_cnt,
                (SELECT p.model_cd FROM ax.tb_prod_product p
                  WHERE p.family_id = f.family_id AND p.use_flg = 'Y'
                  ORDER BY p.seq_in_family LIMIT 1)                             AS rep_product
            FROM ax.tb_prod_family f
            WHERE f.use_flg = 'Y'
            ORDER BY f.rank_no
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource()) { rs, _ ->
            mapOf(
                "familyId" to rs.getInt("family_id"),
                "familyCd" to rs.getString("family_cd"),
                "familyNm" to rs.getString("family_nm"),
                "rank" to rs.getInt("rank_no"),
                "defaultRank" to rs.getInt("def_rank_no"),
                "productCnt" to rs.getLong("product_cnt"),
                "repProduct" to rs.getString("rep_product")
            )
        }
    }

    /**
     * 제품군 순위를 변경한다. (No.180)
     *
     * UNIQUE(rank_no) 제약이 DEFERRABLE 이므로 트랜잭션 내에서 일괄 갱신할 수 있다.
     *
     * @param orders familyCd to rank 매핑
     * @return 변경 건수
     */
    fun updateFamilyOrders(orders: Map<String, Int>, actor: String): Int {
        if (orders.isEmpty()) return 0

        // 제약 검사를 커밋 시점으로 미뤄 순위 교환 중 일시적 중복을 허용한다.
        jdbcTemplate.jdbcTemplate.execute("SET CONSTRAINTS ALL DEFERRED")

        val sql = """
            UPDATE ax.tb_prod_family
               SET rank_no  = :rank,
                   upd_date = now(),
                   upd_user = :actor
             WHERE family_cd = :familyCd
        """.trimIndent()

        val batch = orders.map { (familyCd, rank) ->
            MapSqlParameterSource()
                .addValue("familyCd", familyCd)
                .addValue("rank", rank)
                .addValue("actor", actor)
        }.toTypedArray()

        return jdbcTemplate.batchUpdate(sql, batch).sum()
    }

    /**
     * 제품 전체 순위(rank_no)를 제품군 순위 × 제품군 내 순서로 재계산한다.
     *
     * @return 재계산된 제품 수
     */
    fun recalculateProductRanks(actor: String): Int {
        val sql = """
            WITH ranked AS (
                SELECT
                    p.product_id,
                    row_number() OVER (ORDER BY f.rank_no, p.seq_in_family, p.model_cd) AS new_rank
                FROM ax.tb_prod_product p
                INNER JOIN ax.tb_prod_family f ON f.family_id = p.family_id
                WHERE p.use_flg = 'Y'
            )
            UPDATE ax.tb_prod_product p
               SET rank_no  = r.new_rank,
                   upd_date = now(),
                   upd_user = :actor
              FROM ranked r
             WHERE p.product_id = r.product_id
               AND coalesce(p.rank_no, -1) <> r.new_rank
        """.trimIndent()

        return jdbcTemplate.update(sql, MapSqlParameterSource("actor", actor))
    }

    /**
     * 제품군 내 제품 순서를 조회한다. (No.181)
     */
    fun findProductsInFamily(familyCd: String): List<Map<String, Any?>> {
        val sql = """
            SELECT
                p.product_id, p.model_cd, p.model_nm, p.seq_in_family, p.def_seq, p.rank_no,
                c.customer_nm, pj.project_nm
            FROM ax.tb_prod_product p
            INNER JOIN ax.tb_prod_family   f  ON f.family_id   = p.family_id
            LEFT  JOIN ax.tb_prod_customer c  ON c.customer_id = p.customer_id
            LEFT  JOIN ax.tb_prod_project  pj ON pj.project_id = p.project_id
            WHERE f.family_cd = :familyCd
              AND p.use_flg   = 'Y'
            ORDER BY p.seq_in_family
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource("familyCd", familyCd)) { rs, _ ->
            mapOf(
                "productId" to rs.getInt("product_id"),
                "code" to rs.getString("model_cd"),
                "name" to rs.getString("model_nm"),
                "customer" to rs.getString("customer_nm"),
                "project" to rs.getString("project_nm"),
                "seq" to rs.getInt("seq_in_family"),
                "defaultSeq" to rs.getInt("def_seq"),
                "rank" to Rs.intOrNull(rs, "rank_no")
            )
        }
    }

    /**
     * 제품군 내 제품 순서를 변경한다. (No.182)
     *
     * @param orders modelCd to seq 매핑
     */
    fun updateProductOrders(familyCd: String, orders: Map<String, Int>, actor: String): Int {
        if (orders.isEmpty()) return 0

        jdbcTemplate.jdbcTemplate.execute("SET CONSTRAINTS ALL DEFERRED")

        val sql = """
            UPDATE ax.tb_prod_product p
               SET seq_in_family = :seq,
                   upd_date      = now(),
                   upd_user      = :actor
              FROM ax.tb_prod_family f
             WHERE f.family_id = p.family_id
               AND f.family_cd = :familyCd
               AND p.model_cd  = :modelCd
        """.trimIndent()

        val batch = orders.map { (modelCd, seq) ->
            MapSqlParameterSource()
                .addValue("familyCd", familyCd)
                .addValue("modelCd", modelCd)
                .addValue("seq", seq)
                .addValue("actor", actor)
        }.toTypedArray()

        return jdbcTemplate.batchUpdate(sql, batch).sum()
    }

    /**
     * 기본 순서를 복원한다. (No.183)
     *
     * 제품군 순위와 제품 순서를 각각 def_rank_no · def_seq 로 되돌린다.
     */
    fun resetToDefaultOrder(actor: String): Int {
        jdbcTemplate.jdbcTemplate.execute("SET CONSTRAINTS ALL DEFERRED")

        val familySql = """
            UPDATE ax.tb_prod_family
               SET rank_no  = def_rank_no,
                   upd_date = now(),
                   upd_user = :actor
             WHERE rank_no <> def_rank_no
        """.trimIndent()

        val productSql = """
            UPDATE ax.tb_prod_product
               SET seq_in_family = def_seq,
                   upd_date      = now(),
                   upd_user      = :actor
             WHERE seq_in_family <> def_seq
        """.trimIndent()

        val params = MapSqlParameterSource("actor", actor)
        return jdbcTemplate.update(familySql, params) + jdbcTemplate.update(productSql, params)
    }

    /**
     * 현재 순위 상위 N 제품을 조회한다. (No.184)
     */
    fun findTopRanking(topN: Int): List<Map<String, Any?>> {
        val sql = """
            SELECT
                p.rank_no, p.model_cd, p.model_nm, f.family_nm, f.family_cd,
                c.customer_nm, pj.project_nm
            FROM ax.tb_prod_product p
            INNER JOIN ax.tb_prod_family   f  ON f.family_id   = p.family_id
            LEFT  JOIN ax.tb_prod_customer c  ON c.customer_id = p.customer_id
            LEFT  JOIN ax.tb_prod_project  pj ON pj.project_id = p.project_id
            WHERE p.use_flg = 'Y'
            ORDER BY f.rank_no, p.seq_in_family
            LIMIT :topN
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource("topN", topN)) { rs, _ ->
            mapOf(
                "rank" to Rs.intOrNull(rs, "rank_no"),
                "code" to rs.getString("model_cd"),
                "name" to rs.getString("model_nm"),
                "family" to rs.getString("family_nm"),
                "familyCd" to rs.getString("family_cd"),
                "customer" to rs.getString("customer_nm"),
                "segment" to rs.getString("project_nm")
            )
        }
    }

    /**
     * 순위 변경 이력을 기록한다.
     *
     * @param actCd PROD_RANK_ACT — FAMILY / PRODUCT / RESET
     */
    fun insertRankLog(
        actCd: String,
        familyId: Int?,
        productId: Int?,
        detail: String,
        actorUserId: String,
        actorDeptNm: String?
    ) {
        val sql = """
            INSERT INTO ax.tb_prod_rank_log (log_at, act_cd, family_id, product_id, detail, actor_user_id, actor_dept_nm)
            VALUES (now(), :actCd, :familyId, :productId, :detail, :actorUserId, :actorDeptNm)
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("actCd", actCd)
            .addValue("familyId", familyId)
            .addValue("productId", productId)
            .addValue("detail", detail.take(300))
            .addValue("actorUserId", actorUserId)
            .addValue("actorDeptNm", actorDeptNm)

        jdbcTemplate.update(sql, params)
    }

    /**
     * 순위 변경 이력을 조회한다. (No.185)
     */
    fun findRankLogs(limit: Int, offset: Int): List<Map<String, Any?>> {
        val sql = """
            SELECT l.log_at, l.act_cd, l.detail, l.actor_user_id, l.actor_dept_nm,
                   u.user_nm, f.family_nm, p.model_cd
            FROM ax.tb_prod_rank_log l
            LEFT JOIN ax.tb_sys_user     u ON u.user_id    = l.actor_user_id
            LEFT JOIN ax.tb_prod_family  f ON f.family_id  = l.family_id
            LEFT JOIN ax.tb_prod_product p ON p.product_id = l.product_id
            ORDER BY l.log_at DESC
            LIMIT :limit OFFSET :offset
        """.trimIndent()

        val params = MapSqlParameterSource().addValue("limit", limit).addValue("offset", offset)

        return jdbcTemplate.query(sql, params) { rs, _ ->
            mapOf(
                "ts" to Rs.dateTime(rs, "log_at"),
                "type" to rs.getString("act_cd"),
                "detail" to rs.getString("detail"),
                "target" to (rs.getString("family_nm") ?: rs.getString("model_cd")),
                "by" to (rs.getString("user_nm") ?: rs.getString("actor_user_id")),
                "byDept" to rs.getString("actor_dept_nm")
            )
        }
    }

    /** 순위 변경 이력 전체 건수 */
    fun countRankLogs(): Long =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ax.tb_prod_rank_log", MapSqlParameterSource(), Long::class.java
        ) ?: 0L

    /** 제품군 코드로 family_id 를 조회한다. */
    fun findFamilyId(familyCd: String): Int? {
        val sql = "SELECT family_id FROM ax.tb_prod_family WHERE family_cd = :familyCd AND use_flg = 'Y'"
        return jdbcTemplate.query(sql, MapSqlParameterSource("familyCd", familyCd)) { rs, _ -> rs.getInt("family_id") }
            .firstOrNull()
    }
}
