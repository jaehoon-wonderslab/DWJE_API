package com.dwje.api.repository

import com.dwje.api.common.util.Rs
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

/**
 * 보고서 양식 관리 Repository (QC-04)
 *
 * 참조 테이블 : ax.tb_rpt_form, ax.tb_rpt_form_field, ax.tb_rpt_report,
 *              ax.tb_prod_customer, ax.tb_sys_data_field
 */
@Repository
class ReportFormRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) {

    /**
     * 양식 목록을 조회한다. (No.98)
     */
    fun findForms(): List<Map<String, Any?>> {
        val sql = """
            SELECT
                f.form_id,
                f.form_nm,
                f.form_type_cd,
                ct.code_nm                                                     AS form_type_nm,
                f.customer_id,
                c.customer_nm,
                f.disclosure_policy,
                f.parser_ver,
                f.report_id,
                r.report_nm,
                f.use_flg,
                f.upd_date,
                u.user_nm                                                      AS upd_user_nm,
                (SELECT count(*) FROM ax.tb_rpt_form_field ff WHERE ff.form_id = f.form_id) AS field_cnt
            FROM ax.tb_rpt_form f
            LEFT JOIN ax.tb_prod_customer c ON c.customer_id = f.customer_id
            LEFT JOIN ax.tb_rpt_report   r  ON r.report_id   = f.report_id
            LEFT JOIN ax.tb_sys_user     u  ON u.user_id     = f.upd_user
            LEFT JOIN ax.tb_sys_code     ct ON ct.group_cd   = 'RPT_FORM_TYPE' AND ct.code = f.form_type_cd
            WHERE f.use_flg = 'Y'
            ORDER BY f.form_nm
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource()) { rs, _ ->
            mapOf(
                "formId" to rs.getInt("form_id"),
                "name" to rs.getString("form_nm"),
                "type" to rs.getString("form_type_cd"),
                "typeNm" to rs.getString("form_type_nm"),
                "customerId" to Rs.intOrNull(rs, "customer_id"),
                "customer" to rs.getString("customer_nm"),
                "disclosurePolicy" to rs.getString("disclosure_policy"),
                "parserVer" to rs.getString("parser_ver"),
                "reportId" to rs.getString("report_id"),
                "reportNm" to rs.getString("report_nm"),
                "fieldCnt" to rs.getLong("field_cnt"),
                "updatedAt" to Rs.dateTime(rs, "upd_date"),
                "updatedBy" to rs.getString("upd_user_nm")
            )
        }
    }

    /** 양식 단건 조회 */
    fun findForm(formId: Int): Map<String, Any?>? {
        val sql = """
            SELECT f.form_id, f.form_nm, f.form_type_cd, f.customer_id, c.customer_nm,
                   f.disclosure_policy, f.parser_ver, f.report_id, f.use_flg
            FROM ax.tb_rpt_form f
            LEFT JOIN ax.tb_prod_customer c ON c.customer_id = f.customer_id
            WHERE f.form_id = :formId
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource("formId", formId)) { rs, _ ->
            mapOf(
                "formId" to rs.getInt("form_id"),
                "name" to rs.getString("form_nm"),
                "type" to rs.getString("form_type_cd"),
                "customerId" to Rs.intOrNull(rs, "customer_id"),
                "customer" to rs.getString("customer_nm"),
                "disclosurePolicy" to rs.getString("disclosure_policy"),
                "parserVer" to rs.getString("parser_ver"),
                "reportId" to rs.getString("report_id")
            )
        }.firstOrNull()
    }

    /**
     * 양식을 등록한다. (No.99)
     *
     * @return 생성된 양식 ID
     */
    fun insertForm(
        formNm: String,
        formTypeCd: String,
        customerId: Int?,
        disclosurePolicy: String?,
        reportId: String?,
        parserVer: String,
        actor: String
    ): Int {
        val sql = """
            INSERT INTO ax.tb_rpt_form (
                form_nm, form_type_cd, customer_id, disclosure_policy, parser_ver, report_id,
                use_flg, ins_user, upd_user
            ) VALUES (
                :formNm, :formTypeCd, :customerId, :disclosurePolicy, :parserVer, :reportId,
                'Y', :actor, :actor
            )
            RETURNING form_id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("formNm", formNm.take(100))
            .addValue("formTypeCd", formTypeCd)
            .addValue("customerId", customerId)
            .addValue("disclosurePolicy", disclosurePolicy?.take(200))
            .addValue("parserVer", parserVer)
            .addValue("reportId", reportId)
            .addValue("actor", actor)

        return jdbcTemplate.queryForObject(sql, params, Int::class.java) ?: 0
    }

    /**
     * 양식을 수정한다. (No.100)
     *
     * 항목 정의가 바뀌면 파서 버전을 올려야 하므로 parserVer 를 함께 갱신한다.
     */
    fun updateForm(
        formId: Int,
        formNm: String,
        formTypeCd: String,
        customerId: Int?,
        disclosurePolicy: String?,
        parserVer: String,
        actor: String
    ): Int {
        val sql = """
            UPDATE ax.tb_rpt_form
               SET form_nm           = :formNm,
                   form_type_cd      = :formTypeCd,
                   customer_id       = :customerId,
                   disclosure_policy = :disclosurePolicy,
                   parser_ver        = :parserVer,
                   upd_date          = now(),
                   upd_user          = :actor
             WHERE form_id = :formId
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("formId", formId)
            .addValue("formNm", formNm.take(100))
            .addValue("formTypeCd", formTypeCd)
            .addValue("customerId", customerId)
            .addValue("disclosurePolicy", disclosurePolicy?.take(200))
            .addValue("parserVer", parserVer)
            .addValue("actor", actor)

        return jdbcTemplate.update(sql, params)
    }

    /**
     * 양식 항목 정의를 조회한다. (No.101)
     */
    fun findFormFields(formId: Int): List<Map<String, Any?>> {
        val sql = """
            SELECT ff.field_seq, ff.field_nm, ff.field_code, ff.is_required,
                   ff.blind_field_key, df.field_nm AS blind_field_nm, ff.remark
            FROM ax.tb_rpt_form_field ff
            LEFT JOIN ax.tb_sys_data_field df ON df.field_key = ff.blind_field_key
            WHERE ff.form_id = :formId
            ORDER BY ff.field_seq
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource("formId", formId)) { rs, _ ->
            mapOf(
                "seq" to rs.getInt("field_seq"),
                "field" to rs.getString("field_code"),
                "label" to rs.getString("field_nm"),
                "required" to rs.getBoolean("is_required"),
                "dataFieldKey" to rs.getString("blind_field_key"),
                "dataFieldNm" to rs.getString("blind_field_nm"),
                // 기입 출처는 항목 코드 규칙으로 판정한다. (MES 집계 항목은 접두사 사용)
                "origin" to when {
                    rs.getString("field_code")?.startsWith("mes_") == true -> "MES"
                    rs.getString("field_code")?.startsWith("ai_") == true -> "AI"
                    else -> "MANUAL"
                },
                "remark" to rs.getString("remark")
            )
        }
    }

    /**
     * 양식 항목 정의를 일괄 교체한다.
     *
     * @param fields 항목 목록 — fieldNm, fieldCode, required, blindFieldKey, remark
     */
    fun replaceFormFields(formId: Int, fields: List<Map<String, Any?>>) {
        jdbcTemplate.update(
            "DELETE FROM ax.tb_rpt_form_field WHERE form_id = :formId",
            MapSqlParameterSource("formId", formId)
        )
        if (fields.isEmpty()) return

        val sql = """
            INSERT INTO ax.tb_rpt_form_field (form_id, field_seq, field_nm, field_code, is_required, blind_field_key, remark)
            VALUES (:formId, :fieldSeq, :fieldNm, :fieldCode, :isRequired, :blindFieldKey, :remark)
        """.trimIndent()

        val batch = fields.mapIndexed { idx, f ->
            MapSqlParameterSource()
                .addValue("formId", formId)
                .addValue("fieldSeq", (idx + 1).toShort())
                .addValue("fieldNm", (f["label"] as? String ?: f["fieldNm"] as? String ?: "항목${idx + 1}").take(100))
                .addValue("fieldCode", (f["field"] as? String ?: f["fieldCode"] as? String)?.take(50))
                .addValue("isRequired", f["required"] as? Boolean ?: false)
                .addValue("blindFieldKey", f["dataFieldKey"] as? String ?: f["blindFieldKey"] as? String)
                .addValue("remark", (f["remark"] as? String)?.take(200))
        }.toTypedArray()

        jdbcTemplate.batchUpdate(sql, batch)
    }

    /** 양식명 중복 여부 확인 */
    fun existsFormName(formNm: String, excludeFormId: Int?): Boolean {
        val sql = """
            SELECT count(*)
            FROM ax.tb_rpt_form
            WHERE form_nm = :formNm
              AND (:excludeFormId::int IS NULL OR form_id <> :excludeFormId)
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("formNm", formNm)
            .addValue("excludeFormId", excludeFormId)

        return (jdbcTemplate.queryForObject(sql, params, Long::class.java) ?: 0L) > 0
    }

    /** 다음 파서 버전을 산출한다. (v1.0 → v1.1) */
    fun nextParserVersion(current: String?): String {
        val version = current?.removePrefix("v")?.split(".")
        val major = version?.getOrNull(0)?.toIntOrNull() ?: 1
        val minor = (version?.getOrNull(1)?.toIntOrNull() ?: 0) + 1
        return "v$major.$minor"
    }
}
