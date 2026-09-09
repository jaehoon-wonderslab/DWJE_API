package com.dwje.api.repository

import com.dwje.api.common.util.Rs
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

/**
 * 보안 필터링(마스킹) 규칙 Repository (SY-10, QC-03)
 *
 * 참조 테이블 : ax.tb_ai_mask_rule, ax.tb_ai_mask_rule_column,
 *              ax.tb_sys_data_field, ax.tb_prod_customer
 */
@Repository
class MaskRuleRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) {

    /**
     * 마스킹 규칙 목록을 조회한다. (No.193 / No.88)
     *
     * @param customerId 고객사 ID — 지정 시 해당 고객사 정책과 전역 정책을 함께 반환
     */
    fun findRules(customerId: Int?): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT
                r.rule_id,
                r.rule_nm,
                r.field_key,
                df.field_nm,
                r.mask_type_cd,
                mt.code_nm      AS mask_type_nm,
                r.customer_id,
                c.customer_nm,
                r.policy_desc,
                r.use_flg,
                r.upd_date,
                u.user_nm       AS upd_user_nm,
                (
                    SELECT string_agg(
                               concat_ws('.', rc.target_schema, rc.target_table, rc.target_column),
                               ', ' ORDER BY rc.target_table, rc.target_column
                           )
                      FROM ax.tb_ai_mask_rule_column rc
                     WHERE rc.rule_id = r.rule_id
                ) AS target_columns
            FROM ax.tb_ai_mask_rule r
            LEFT JOIN ax.tb_sys_data_field df ON df.field_key   = r.field_key
            LEFT JOIN ax.tb_prod_customer  c  ON c.customer_id  = r.customer_id
            LEFT JOIN ax.tb_sys_user       u  ON u.user_id      = r.upd_user
            LEFT JOIN ax.tb_sys_code       mt ON mt.group_cd    = 'AI_MASK_TYPE' AND mt.code = r.mask_type_cd
            WHERE r.use_flg = 'Y'
            """.trimIndent()
        )

        val params = MapSqlParameterSource()
        if (customerId != null) {
            sql.append(" AND (r.customer_id = :customerId OR r.customer_id IS NULL)")
            params.addValue("customerId", customerId)
        }
        sql.append("\nORDER BY r.customer_id NULLS FIRST, r.rule_nm")

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "ruleId" to rs.getInt("rule_id"),
                "name" to rs.getString("rule_nm"),
                "fieldKey" to rs.getString("field_key"),
                "fieldNm" to rs.getString("field_nm"),
                "action" to rs.getString("mask_type_cd"),
                "actionNm" to rs.getString("mask_type_nm"),
                "customerId" to Rs.intOrNull(rs, "customer_id"),
                "customerPolicy" to rs.getString("customer_nm"),
                "policyDesc" to rs.getString("policy_desc"),
                "targetFields" to (rs.getString("target_columns")?.split(", ") ?: emptyList()),
                "useYn" to rs.getString("use_flg"),
                "updatedAt" to Rs.dateTime(rs, "upd_date"),
                "updatedBy" to rs.getString("upd_user_nm")
            )
        }
    }

    /**
     * 마스킹 규칙을 등록한다. (No.194)
     *
     * @return 생성된 규칙 ID
     */
    fun insertRule(
        ruleNm: String,
        fieldKey: String?,
        maskTypeCd: String,
        customerId: Int?,
        policyDesc: String?,
        actor: String
    ): Int {
        val sql = """
            INSERT INTO ax.tb_ai_mask_rule (
                rule_nm, field_key, mask_type_cd, customer_id, policy_desc, use_flg, ins_user, upd_user
            ) VALUES (
                :ruleNm, :fieldKey, :maskTypeCd, :customerId, :policyDesc, 'Y', :actor, :actor
            )
            RETURNING rule_id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("ruleNm", ruleNm.take(50))
            .addValue("fieldKey", fieldKey)
            .addValue("maskTypeCd", maskTypeCd)
            .addValue("customerId", customerId)
            .addValue("policyDesc", policyDesc?.take(200))
            .addValue("actor", actor)

        return jdbcTemplate.queryForObject(sql, params, Int::class.java) ?: 0
    }

    /**
     * 마스킹 규칙을 수정한다. (No.194)
     */
    fun updateRule(
        ruleId: Int,
        ruleNm: String,
        fieldKey: String?,
        maskTypeCd: String,
        customerId: Int?,
        policyDesc: String?,
        useYn: String,
        actor: String
    ): Int {
        val sql = """
            UPDATE ax.tb_ai_mask_rule
               SET rule_nm      = :ruleNm,
                   field_key    = :fieldKey,
                   mask_type_cd = :maskTypeCd,
                   customer_id  = :customerId,
                   policy_desc  = :policyDesc,
                   use_flg      = :useYn,
                   upd_date     = now(),
                   upd_user     = :actor
             WHERE rule_id = :ruleId
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("ruleId", ruleId)
            .addValue("ruleNm", ruleNm.take(50))
            .addValue("fieldKey", fieldKey)
            .addValue("maskTypeCd", maskTypeCd)
            .addValue("customerId", customerId)
            .addValue("policyDesc", policyDesc?.take(200))
            .addValue("useYn", useYn)
            .addValue("actor", actor)

        return jdbcTemplate.update(sql, params)
    }

    /**
     * 규칙 대상 물리 컬럼을 교체한다.
     *
     * @param targets "schema.table.column" 형식 문자열 목록
     */
    fun replaceRuleColumns(ruleId: Int, targets: List<String>) {
        jdbcTemplate.update(
            "DELETE FROM ax.tb_ai_mask_rule_column WHERE rule_id = :ruleId",
            MapSqlParameterSource("ruleId", ruleId)
        )
        if (targets.isEmpty()) return

        val sql = """
            INSERT INTO ax.tb_ai_mask_rule_column (rule_id, target_schema, target_table, target_column)
            VALUES (:ruleId, :schema, :table, :column)
            ON CONFLICT (rule_id, target_schema, target_table, target_column) DO NOTHING
        """.trimIndent()

        val batch = targets.mapNotNull { target ->
            // "schema.table.column" 3단 구성만 등록한다.
            val parts = target.trim().split(".")
            if (parts.size != 3) return@mapNotNull null
            MapSqlParameterSource()
                .addValue("ruleId", ruleId)
                .addValue("schema", parts[0])
                .addValue("table", parts[1])
                .addValue("column", parts[2])
        }.toTypedArray()

        if (batch.isNotEmpty()) jdbcTemplate.batchUpdate(sql, batch)
    }

    /** 규칙 존재 여부 확인 */
    fun exists(ruleId: Int): Boolean {
        val sql = "SELECT count(*) FROM ax.tb_ai_mask_rule WHERE rule_id = :ruleId"
        return (jdbcTemplate.queryForObject(sql, MapSqlParameterSource("ruleId", ruleId), Long::class.java) ?: 0L) > 0
    }

    /**
     * 마스킹 해제 요청을 등록한다. (No.89)
     *
     * 문서 관리 제거(2026-09-04) 로 `doc_id` 가 없어졌다.
     * 요청 맥락은 화면(`menu_id`)과 데이터 항목(`field_keys`) 으로 남는다.
     *
     * @return 생성된 요청 ID
     */
    fun insertUnmaskRequest(
        menuId: String?,
        fieldKeys: List<String>,
        reason: String,
        requesterId: String,
        requesterDept: String?
    ): Long {
        val sql = """
            INSERT INTO ax.tb_rpt_unmask_req (
                menu_id, field_keys, reason, state_cd, requested_at, requester_id, requester_dept
            ) VALUES (
                :menuId, :fieldKeys, :reason, 'REQUESTED', now(), :requesterId, :requesterDept
            )
            RETURNING req_id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("menuId", menuId)
            .addValue("fieldKeys", fieldKeys.toTypedArray())
            .addValue("reason", reason.take(500))
            .addValue("requesterId", requesterId)
            .addValue("requesterDept", requesterDept)

        return jdbcTemplate.queryForObject(sql, params, Long::class.java) ?: 0L
    }
}
