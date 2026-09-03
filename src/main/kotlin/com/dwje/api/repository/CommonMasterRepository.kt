package com.dwje.api.repository

import com.dwje.api.common.util.Rs
import com.dwje.api.common.util.SqlLikeUtils
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet

/**
 * 공통 코드 · 기준정보 조회 Repository (CM-05)
 *
 * 참조 테이블
 * - 공통코드   : ax.tb_sys_code, ax.tb_sys_code_group
 * - 공정       : mes.tb_md_workcenter, ax.tb_sys_plant
 * - 설비       : mes.tb_md_eqpt, mes.tb_md_eqpt_by_workcenter
 * - 제품       : ax.tb_prod_product, ax.tb_prod_family, ax.tb_prod_customer, ax.tb_prod_project
 * - 불량유형   : mes.tb_md_defect, mes.tb_md_defect_by_item, ax.tb_ai_defect_tag
 * - 금형       : mes.tb_md_mold, mes.tb_md_mold_by_eqpt
 */
@Repository
class CommonMasterRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) {

    /**
     * 공통코드 목록을 조회한다. (No.7)
     *
     * @param groupCd 코드 그룹 (미지정 시 전체 그룹)
     * @return 코드 목록 — cd, nm, sort, useYn
     */
    fun findCodes(groupCd: String?): List<Map<String, Any?>> {
        // 1. 기본 SQL 정의
        val sql = StringBuilder(
            """
            SELECT
                c.group_cd,
                g.group_nm,
                c.code,
                c.code_nm,
                c.code_desc,
                c.attr1,
                c.attr2,
                c.sort_seq,
                c.use_flg
            FROM ax.tb_sys_code c
            INNER JOIN ax.tb_sys_code_group g ON g.group_cd = c.group_cd
            WHERE g.use_flg = 'Y'
            """.trimIndent()
        )

        // 2. 동적 파라미터 바인딩 객체 생성
        val params = MapSqlParameterSource()

        // 3. 동적 조건 추가
        if (!groupCd.isNullOrBlank()) {
            sql.append(" AND c.group_cd = :groupCd")
            params.addValue("groupCd", groupCd.trim())
        }

        sql.append(" ORDER BY g.sort_seq, c.sort_seq, c.code")

        // 4. 쿼리 실행 및 결과 매핑
        return jdbcTemplate.query(sql.toString(), params) { rs: ResultSet, _ ->
            mapOf(
                "groupCd" to rs.getString("group_cd"),
                "groupNm" to rs.getString("group_nm"),
                "cd" to rs.getString("code"),
                "nm" to rs.getString("code_nm"),
                "desc" to rs.getString("code_desc"),
                "attr1" to rs.getString("attr1"),
                "attr2" to rs.getString("attr2"),
                "sort" to rs.getInt("sort_seq"),
                "useYn" to rs.getString("use_flg")
            )
        }
    }

    /**
     * 공정(작업장) 목록을 조회한다. (No.8 — Press / A Plating / B Plating / Coating)
     *
     * 설비 대수는 작업장-설비 매핑에서 집계하고, 목표 수율·생산능력은 지표 기준에서 가져온다.
     *
     * @param plantCd 사업장 코드
     */
    fun findProcesses(plantCd: String): List<Map<String, Any?>> {
        val sql = """
            SELECT
                w.wc_cd,
                w.wc_nm,
                w.sort_seq,
                p.plant_nm,
                (
                    SELECT count(*)
                      FROM mes.tb_md_eqpt_by_workcenter ew
                     INNER JOIN mes.tb_md_eqpt e
                             ON e.plant_cd = ew.plant_cd AND e.eqpt_cd = ew.eqpt_cd
                     WHERE ew.plant_cd = w.plant_cd
                       AND ew.wc_cd    = w.wc_cd
                       AND e.use_flg   = 'Y'
                ) AS eqpt_cnt,
                (
                    SELECT ms.std_val
                      FROM ax.tb_met_metric_std ms
                     WHERE ms.metric_cd = 'YIELD_' || w.wc_cd
                       AND ms.use_flg   = 'Y'
                     LIMIT 1
                ) AS target_yield,
                (
                    SELECT ms.std_val
                      FROM ax.tb_met_metric_std ms
                     WHERE ms.metric_cd = 'CAPACITY_' || w.wc_cd
                       AND ms.use_flg   = 'Y'
                     LIMIT 1
                ) AS capacity
            FROM mes.tb_md_workcenter w
            INNER JOIN ax.tb_sys_plant p ON p.plant_cd = w.plant_cd
            WHERE w.plant_cd = :plantCd
              AND now() BETWEEN w.valid_from_dt AND w.valid_to_dt
            ORDER BY w.sort_seq, w.wc_cd
        """.trimIndent()

        val params = MapSqlParameterSource("plantCd", plantCd)

        // 선행 공정은 정렬 순서상 직전 작업장으로 판정한다.
        var previous: String? = null
        return jdbcTemplate.query(sql, params) { rs, _ ->
            val id = rs.getString("wc_cd")
            val row = mapOf(
                "id" to id,
                "name" to rs.getString("wc_nm"),
                "pre" to previous,
                "plant" to rs.getString("plant_nm"),
                "eqptCnt" to rs.getInt("eqpt_cnt"),
                "targetYield" to Rs.rate(rs, "target_yield"),
                "capacity" to Rs.rate(rs, "capacity", 0)
            )
            previous = id
            row
        }
    }

    /**
     * 설비 목록을 조회한다. (No.9)
     *
     * @param plantCd   사업장 코드
     * @param processId 공정(작업장) 코드 — 미지정 시 전체
     * @param keyword   설비코드/설비명 검색어
     */
    fun findEquipments(plantCd: String, processId: String?, keyword: String?): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT DISTINCT
                e.eqpt_cd,
                e.eqpt_nm,
                e.model_nm,
                ew.wc_cd,
                w.wc_nm,
                e.use_flg
            FROM mes.tb_md_eqpt e
            LEFT JOIN mes.tb_md_eqpt_by_workcenter ew
                   ON ew.plant_cd = e.plant_cd AND ew.eqpt_cd = e.eqpt_cd
            LEFT JOIN mes.tb_md_workcenter w
                   ON w.plant_cd = ew.plant_cd AND w.wc_cd = ew.wc_cd
            WHERE e.plant_cd = :plantCd
              AND e.use_flg  = 'Y'
            """.trimIndent()
        )

        val params = MapSqlParameterSource("plantCd", plantCd)

        if (!processId.isNullOrBlank()) {
            sql.append(" AND ew.wc_cd = :processId")
            params.addValue("processId", processId.trim())
        }

        SqlLikeUtils.contains(keyword)?.let {
            sql.append(" AND (e.eqpt_cd LIKE :keyword ESCAPE '\\' OR e.eqpt_nm LIKE :keyword ESCAPE '\\')")
            params.addValue("keyword", it)
        }

        sql.append(" ORDER BY ew.wc_cd NULLS LAST, e.eqpt_cd")

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "eqptCd" to rs.getString("eqpt_cd"),
                "eqptNm" to rs.getString("eqpt_nm"),
                "model" to rs.getString("model_nm"),
                "wcCd" to rs.getString("wc_cd"),
                "wcNm" to rs.getString("wc_nm"),
                // 실시간 상태는 IoT 수집 지표에서 별도 판정하므로 기준정보 조회에서는 사용 여부만 반환한다.
                "state" to if (Rs.yn(rs, "use_flg")) "USE" else "STOP"
            )
        }
    }

    /**
     * 제품 목록을 조회한다. (No.10 — 제품 선택 팝업 113종)
     *
     * @param keyword     제품코드/제품명 검색어
     * @param familyCd    제품군 코드
     * @param customerCd  고객사 코드
     * @param projectCd   프로젝트 코드
     * @param orderByClause 정렬 절 (SortResolver 로 화이트리스트 검증된 값)
     * @param limit       조회 건수
     * @param offset      건너뛸 건수
     */
    fun findProducts(
        keyword: String?,
        familyCd: String?,
        customerCd: String?,
        projectCd: String?,
        orderByClause: String,
        limit: Int,
        offset: Int
    ): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT
                p.product_id,
                p.model_cd,
                p.model_nm,
                p.seq_in_family,
                p.rank_no,
                f.family_cd,
                f.family_nm,
                f.rank_no AS family_rank,
                c.customer_cd,
                c.customer_nm,
                pj.project_cd,
                pj.project_nm
            FROM ax.tb_prod_product p
            INNER JOIN ax.tb_prod_family   f  ON f.family_id  = p.family_id
            LEFT  JOIN ax.tb_prod_customer c  ON c.customer_id = p.customer_id
            LEFT  JOIN ax.tb_prod_project  pj ON pj.project_id = p.project_id
            WHERE p.use_flg = 'Y'
            """.trimIndent()
        )

        val params = MapSqlParameterSource()
        appendProductFilters(sql, params, keyword, familyCd, customerCd, projectCd)

        sql.append("\n").append(orderByClause)
        sql.append("\nLIMIT :limit OFFSET :offset")
        params.addValue("limit", limit).addValue("offset", offset)

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "productId" to rs.getInt("product_id"),
                "code" to rs.getString("model_cd"),
                "name" to rs.getString("model_nm"),
                "family" to rs.getString("family_nm"),
                "familyCd" to rs.getString("family_cd"),
                "customer" to rs.getString("customer_nm"),
                "customerCd" to rs.getString("customer_cd"),
                "project" to rs.getString("project_nm"),
                "projectCd" to rs.getString("project_cd"),
                "rank" to Rs.intOrNull(rs, "rank_no"),
                "seq" to rs.getInt("seq_in_family")
            )
        }
    }

    /**
     * 제품 목록 전체 건수를 조회한다. (페이징 meta.total)
     */
    fun countProducts(keyword: String?, familyCd: String?, customerCd: String?, projectCd: String?): Long {
        val sql = StringBuilder(
            """
            SELECT count(*)
            FROM ax.tb_prod_product p
            INNER JOIN ax.tb_prod_family   f  ON f.family_id   = p.family_id
            LEFT  JOIN ax.tb_prod_customer c  ON c.customer_id = p.customer_id
            LEFT  JOIN ax.tb_prod_project  pj ON pj.project_id = p.project_id
            WHERE p.use_flg = 'Y'
            """.trimIndent()
        )

        val params = MapSqlParameterSource()
        appendProductFilters(sql, params, keyword, familyCd, customerCd, projectCd)

        return jdbcTemplate.queryForObject(sql.toString(), params, Long::class.java) ?: 0L
    }

    /**
     * 제품 목록/건수 조회에 공통으로 적용되는 동적 조건을 부착한다.
     */
    private fun appendProductFilters(
        sql: StringBuilder,
        params: MapSqlParameterSource,
        keyword: String?,
        familyCd: String?,
        customerCd: String?,
        projectCd: String?
    ) {
        SqlLikeUtils.contains(keyword)?.let {
            sql.append(" AND (p.model_cd LIKE :keyword ESCAPE '\\' OR p.model_nm LIKE :keyword ESCAPE '\\')")
            params.addValue("keyword", it)
        }
        if (!familyCd.isNullOrBlank()) {
            sql.append(" AND f.family_cd = :familyCd")
            params.addValue("familyCd", familyCd.trim())
        }
        if (!customerCd.isNullOrBlank()) {
            sql.append(" AND c.customer_cd = :customerCd")
            params.addValue("customerCd", customerCd.trim())
        }
        if (!projectCd.isNullOrBlank()) {
            sql.append(" AND pj.project_cd = :projectCd")
            params.addValue("projectCd", projectCd.trim())
        }
    }

    /**
     * 고객사 목록을 조회한다. (No.11)
     */
    fun findCustomers(): List<Map<String, Any?>> {
        val sql = """
            SELECT customer_id, customer_cd, customer_nm, disclosure_note
            FROM ax.tb_prod_customer
            WHERE use_flg = 'Y'
            ORDER BY customer_nm
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource()) { rs, _ ->
            mapOf(
                "customerId" to rs.getInt("customer_id"),
                "code" to rs.getString("customer_cd"),
                "name" to rs.getString("customer_nm"),
                "disclosureNote" to rs.getString("disclosure_note")
            )
        }
    }

    /**
     * 불량 유형 목록을 조회한다. (No.12)
     *
     * AI 불량 태그(ax.tb_ai_defect_tag)와 매핑된 분류를 함께 반환한다.
     *
     * @param plantCd   사업장 코드
     * @param processId 공정(작업장) 코드 — 지정 시 해당 공정에서 사용되는 불량만 조회
     */
    fun findDefectTypes(plantCd: String, processId: String?): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT DISTINCT
                d.defect_cd,
                d.defect_nm,
                t.tag_nm AS category
            FROM mes.tb_md_defect d
            LEFT JOIN ax.tb_ai_defect_tag_map m
                   ON m.plant_cd = d.plant_cd AND m.defect_cd = d.defect_cd
            LEFT JOIN ax.tb_ai_defect_tag t
                   ON t.tag_id = m.tag_id AND t.use_flg = 'Y'
            WHERE d.plant_cd = :plantCd
              AND d.use_flg  = 'Y'
            """.trimIndent()
        )

        val params = MapSqlParameterSource("plantCd", plantCd)

        if (!processId.isNullOrBlank()) {
            sql.append(
                """

                AND EXISTS (
                    SELECT 1
                      FROM mes.tb_md_defect_by_item di
                     WHERE di.plant_cd  = d.plant_cd
                       AND di.defect_cd = d.defect_cd
                       AND di.wc_cd     = :processId
                )
                """.trimIndent()
            )
            params.addValue("processId", processId.trim())
        }

        sql.append("\nORDER BY d.defect_cd")

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "code" to rs.getString("defect_cd"),
                "name" to rs.getString("defect_nm"),
                "category" to rs.getString("category")
            )
        }
    }

    /**
     * 금형 목록을 조회한다. (No.13 — 데이터 항목 mold 권한 필요)
     *
     * @param plantCd 사업장 코드
     * @param eqptCd  설비 코드 — 지정 시 해당 설비에 장착 가능한 금형만 조회
     */
    fun findMolds(plantCd: String, eqptCd: String?): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT DISTINCT
                m.mold_cd,
                m.mold_nm,
                m.degree,
                m.cavity,
                (
                    SELECT coalesce(sum(lh.normal + coalesce(lh.defect, 0)), 0)
                      FROM mes.tb_pop_label_hist lh
                     WHERE lh.plant_cd = m.plant_cd
                       AND lh.mold_cd  = m.mold_cd
                       AND lh.del_flg  = 'N'
                ) AS shot_cnt
            FROM mes.tb_md_mold m
            """.trimIndent()
        )

        val params = MapSqlParameterSource("plantCd", plantCd)

        if (!eqptCd.isNullOrBlank()) {
            sql.append(
                """

                INNER JOIN mes.tb_md_mold_by_eqpt me
                        ON me.plant_cd = m.plant_cd AND me.mold_cd = m.mold_cd
                """.trimIndent()
            )
        }

        sql.append("\nWHERE m.plant_cd = :plantCd AND m.use_flg = 'Y'")

        if (!eqptCd.isNullOrBlank()) {
            sql.append(" AND me.eqpt_cd = :eqptCd")
            params.addValue("eqptCd", eqptCd.trim())
        }

        sql.append("\nORDER BY m.mold_cd")

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            val shotCnt = Rs.qty(rs, "shot_cnt") ?: 0L
            mapOf(
                "moldCd" to rs.getString("mold_cd"),
                "moldNm" to rs.getString("mold_nm"),
                "degree" to rs.getInt("degree"),
                "cavity" to rs.getInt("cavity"),
                "shotCnt" to shotCnt,
                // 금형 수명 기준은 지표 기준정보에서 관리하되, 미등록 시 잔여 타수를 산출하지 않는다.
                "remainShot" to null
            )
        }
    }

    /**
     * 실적 데이터가 존재하는 날짜 범위를 조회한다. (화면 기준일 초기화용)
     *
     * `mes.tb_pop_label_hist` 는 수백만 행이라 단순 min/max 는 풀스캔이 되어 약 1초가 걸린다.
     * 워크센터(수십 건) 단위로 `ix_pop_label_live(plant_cd, wc_cd, ins_date)` 인덱스의
     * 양 끝 한 건씩만 읽도록 LATERAL 로 분해하면 20ms 대로 떨어진다.
     *
     * @param plantCd 사업장 코드
     */
    fun findProductionDateRange(plantCd: String, processId: String? = null): Map<String, Any?> {
        val processFilter = if (processId.isNullOrBlank()) "" else " AND w.wc_cd = :processId"
        val sql = """
            SELECT min(b.min_dt)::date AS from_dt,
                   max(b.max_dt)::date AS to_dt
            FROM mes.tb_md_workcenter w
            CROSS JOIN LATERAL (
                SELECT (SELECT l.ins_date FROM mes.tb_pop_label_hist l
                         WHERE l.plant_cd = w.plant_cd AND l.wc_cd = w.wc_cd AND l.del_flg = 'N'
                         ORDER BY l.ins_date ASC LIMIT 1)  AS min_dt,
                       (SELECT l.ins_date FROM mes.tb_pop_label_hist l
                         WHERE l.plant_cd = w.plant_cd AND l.wc_cd = w.wc_cd AND l.del_flg = 'N'
                         ORDER BY l.ins_date DESC LIMIT 1) AS max_dt
            ) b
            WHERE w.plant_cd = :plantCd$processFilter
        """.trimIndent()

        val params = MapSqlParameterSource().addValue("plantCd", plantCd)
        if (!processId.isNullOrBlank()) params.addValue("processId", processId.trim())

        return jdbcTemplate.query(sql, params) { rs, _ ->
            mapOf(
                "fromDate" to rs.getDate("from_dt")?.toLocalDate()?.toString(),
                "toDate" to rs.getDate("to_dt")?.toLocalDate()?.toString()
            )
        }.firstOrNull() ?: mapOf("fromDate" to null, "toDate" to null)
    }
}
