package com.dwje.api.repository

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

/**
 * 데이터 접근 항목 · 응답 필드명 Repository (SY-03, V33)
 *
 * 참조 테이블
 * - 항목       : ax.tb_sys_data_field (category_cd · apply_flg 는 V33)
 * - 응답 필드명 : ax.tb_sys_data_field_attr (attr_name 전역 UNIQUE)
 * - 참조 확인   : ax.tb_alm_cond · ax.tb_met_metric_std · ax.tb_rpt_form_field · vec.tb_doc_data_field · ax.tb_sys_dept_data_perm
 *
 * 항목 목록(화면·매트릭스용)은 [SystemUserRepository.findDataFields] 가 낸다. 여기는 등록·수정·삭제와
 * 마스킹 판정이 쓰는 「응답 필드명 → 항목」 맵을 맡는다.
 */
@Repository
class DataFieldRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) {

    companion object {
        /** 항목 분류 공통코드 그룹 (V33) */
        const val CATEGORY_GROUP = "DATA_FIELD_CATEGORY"
    }

    /** 항목 한 건 — 없으면 null. use_flg 와 무관하게 찾는다(삭제·수정 대상 확인용). */
    fun findField(fieldKey: String): Map<String, Any?>? {
        val sql = """
            SELECT f.field_key, f.field_nm, f.field_desc, f.category_cd, c.code_nm AS category_nm,
                   f.apply_flg, f.use_flg, f.sort_seq,
                   (SELECT string_agg(a.attr_name, ',' ORDER BY a.attr_name)
                      FROM ax.tb_sys_data_field_attr a WHERE a.field_key = f.field_key) AS attrs
            FROM ax.tb_sys_data_field f
            LEFT JOIN ax.tb_sys_code c ON c.group_cd = '$CATEGORY_GROUP' AND c.code = f.category_cd
            WHERE f.field_key = :fieldKey
        """.trimIndent()
        return jdbcTemplate.query(sql, MapSqlParameterSource("fieldKey", fieldKey)) { rs, _ ->
            mapOf(
                "key" to rs.getString("field_key"),
                "name" to rs.getString("field_nm"),
                "desc" to rs.getString("field_desc"),
                "category" to rs.getString("category_cd"),
                "categoryNm" to rs.getString("category_nm"),
                "applyFlg" to rs.getString("apply_flg"),
                "useFlg" to rs.getString("use_flg"),
                "sortSeq" to rs.getInt("sort_seq"),
                "attrs" to (rs.getString("attrs")?.split(",") ?: emptyList())
            )
        }.firstOrNull()
    }

    /** 사용 중(use_flg='Y') 항목 key — 정렬 순 */
    fun findActiveKeys(): List<String> =
        jdbcTemplate.query(
            "SELECT field_key FROM ax.tb_sys_data_field WHERE use_flg = 'Y' ORDER BY sort_seq, field_key",
            MapSqlParameterSource()
        ) { rs, _ -> rs.getString("field_key") }

    /**
     * 적용 중(use_flg='Y' AND apply_flg='Y') 항목과 그 응답 필드명 — `/auth/me` 의 dataFields.
     * 필드명이 하나도 없는 항목도 낸다(attrs 빈 배열) — 항목 자체는 켜져 있다는 뜻이다.
     */
    fun findAppliedFields(): List<Map<String, Any?>> {
        val sql = """
            SELECT f.field_key, f.field_nm, f.category_cd, c.code_nm AS category_nm, f.sort_seq,
                   (SELECT string_agg(a.attr_name, ',' ORDER BY a.attr_name)
                      FROM ax.tb_sys_data_field_attr a WHERE a.field_key = f.field_key) AS attrs
            FROM ax.tb_sys_data_field f
            LEFT JOIN ax.tb_sys_code c ON c.group_cd = '$CATEGORY_GROUP' AND c.code = f.category_cd
            WHERE f.use_flg = 'Y' AND f.apply_flg = 'Y'
            ORDER BY f.sort_seq, f.field_key
        """.trimIndent()
        return jdbcTemplate.query(sql, MapSqlParameterSource()) { rs, _ ->
            mapOf(
                "key" to rs.getString("field_key"),
                "name" to rs.getString("field_nm"),
                "category" to rs.getString("category_cd"),
                "categoryNm" to rs.getString("category_nm"),
                "attrs" to (rs.getString("attrs")?.split(",") ?: emptyList())
            )
        }
    }

    /** 적용 중 항목의 「응답 필드명 → 항목 key」 맵 — AI 답변 표 블록의 blindColumns 판정용 */
    fun findAppliedAttrMap(): Map<String, String> {
        val sql = """
            SELECT a.attr_name, a.field_key
            FROM ax.tb_sys_data_field_attr a
            INNER JOIN ax.tb_sys_data_field f ON f.field_key = a.field_key
            WHERE f.use_flg = 'Y' AND f.apply_flg = 'Y'
        """.trimIndent()
        return jdbcTemplate.query(sql, MapSqlParameterSource()) { rs, _ ->
            rs.getString("attr_name") to rs.getString("field_key")
        }.toMap()
    }

    fun nextSortSeq(): Int =
        jdbcTemplate.queryForObject(
            "SELECT coalesce(max(sort_seq), 0) + 1 FROM ax.tb_sys_data_field", MapSqlParameterSource(), Int::class.java
        ) ?: 1

    /** 항목 등록 — apply_flg 는 'N'(미적용)으로 시작한다(2단계 스위치). */
    fun insertField(fieldKey: String, name: String, desc: String?, category: String?, sortSeq: Int, actor: String): Int {
        val sql = """
            INSERT INTO ax.tb_sys_data_field
                (field_key, field_nm, field_desc, category_cd, sort_seq, use_flg, apply_flg, ins_user, upd_user)
            VALUES (:fieldKey, :name, :desc, :category, :sortSeq, 'Y', 'N', :actor, :actor)
        """.trimIndent()
        return jdbcTemplate.update(
            sql,
            MapSqlParameterSource()
                .addValue("fieldKey", fieldKey).addValue("name", name).addValue("desc", desc)
                .addValue("category", category).addValue("sortSeq", sortSeq).addValue("actor", actor)
        )
    }

    fun updateField(fieldKey: String, name: String, desc: String?, category: String?, actor: String): Int {
        val sql = """
            UPDATE ax.tb_sys_data_field
               SET field_nm = :name, field_desc = :desc, category_cd = :category, upd_date = now(), upd_user = :actor
             WHERE field_key = :fieldKey
        """.trimIndent()
        return jdbcTemplate.update(
            sql,
            MapSqlParameterSource()
                .addValue("fieldKey", fieldKey).addValue("name", name).addValue("desc", desc)
                .addValue("category", category).addValue("actor", actor)
        )
    }

    /** 항목 삭제 — 부서 권한·응답 필드명은 FK CASCADE 로 함께 지워진다. */
    fun deleteField(fieldKey: String): Int =
        jdbcTemplate.update("DELETE FROM ax.tb_sys_data_field WHERE field_key = :fieldKey", MapSqlParameterSource("fieldKey", fieldKey))

    fun updateApplyFlg(fieldKey: String, on: Boolean, actor: String): Int {
        val sql = """
            UPDATE ax.tb_sys_data_field
               SET apply_flg = :flg, upd_date = now(), upd_user = :actor
             WHERE field_key = :fieldKey
        """.trimIndent()
        return jdbcTemplate.update(
            sql,
            MapSqlParameterSource().addValue("fieldKey", fieldKey).addValue("flg", if (on) "Y" else "N").addValue("actor", actor)
        )
    }

    /** 필드명이 이미 붙어 있는 항목 — {fieldKey, fieldNm}. 없으면 null. attr_name 은 전역 UNIQUE 라 최대 한 건이다. */
    fun findAttrOwner(attrName: String): Map<String, Any?>? {
        val sql = """
            SELECT a.field_key, f.field_nm
            FROM ax.tb_sys_data_field_attr a
            INNER JOIN ax.tb_sys_data_field f ON f.field_key = a.field_key
            WHERE a.attr_name = :attrName
        """.trimIndent()
        return jdbcTemplate.query(sql, MapSqlParameterSource("attrName", attrName)) { rs, _ ->
            mapOf("fieldKey" to rs.getString("field_key"), "fieldNm" to rs.getString("field_nm"))
        }.firstOrNull()
    }

    /** 응답 필드명 등록. 중복이면 [org.springframework.dao.DuplicateKeyException] — 서비스가 409 로 바꾼다. */
    fun insertAttr(fieldKey: String, attrName: String, remark: String?, actor: String): Int {
        val sql = """
            INSERT INTO ax.tb_sys_data_field_attr (field_key, attr_name, remark, ins_user)
            VALUES (:fieldKey, :attrName, :remark, :actor)
        """.trimIndent()
        return jdbcTemplate.update(
            sql,
            MapSqlParameterSource().addValue("fieldKey", fieldKey).addValue("attrName", attrName)
                .addValue("remark", remark).addValue("actor", actor)
        )
    }

    /** 항목의 응답 필드명 전체 — 이름순 */
    fun findFieldAttrs(fieldKey: String): List<String> =
        jdbcTemplate.query(
            "SELECT attr_name FROM ax.tb_sys_data_field_attr WHERE field_key = :fieldKey ORDER BY attr_name",
            MapSqlParameterSource("fieldKey", fieldKey)
        ) { rs, _ -> rs.getString("attr_name") }

    fun deleteAttr(fieldKey: String, attrName: String): Int =
        jdbcTemplate.update(
            "DELETE FROM ax.tb_sys_data_field_attr WHERE field_key = :fieldKey AND attr_name = :attrName",
            MapSqlParameterSource().addValue("fieldKey", fieldKey).addValue("attrName", attrName)
        )

    /**
     * 항목을 참조하는 행 수 — 삭제 가능 여부 판정.
     *
     * FK 가 CASCADE 가 아닌 표(알림 조건 · 지표 기준 · 보고서 양식 필드 · 문서 태그)에 참조가 남아 있으면
     * DELETE 가 FK 위반으로 실패한다. 그 전에 세어서 409 로 알린다. 부서 권한은 CASCADE 라 건수만 알린다.
     */
    fun countReferences(fieldKey: String): Map<String, Long> {
        val sql = """
            SELECT
                (SELECT count(*) FROM ax.tb_alm_cond        WHERE blind_field_key = :k) AS alert_cond,
                (SELECT count(*) FROM ax.tb_met_metric_std  WHERE blind_field_key = :k) AS metric_std,
                (SELECT count(*) FROM ax.tb_rpt_form_field  WHERE blind_field_key = :k) AS report_form_field,
                (SELECT count(*) FROM vec.tb_doc_data_field WHERE field_key       = :k) AS doc_tag,
                (SELECT count(*) FROM ax.tb_sys_dept_data_perm WHERE field_key    = :k) AS dept_perm,
                (SELECT count(*) FROM ax.tb_sys_data_field_attr WHERE field_key   = :k) AS attr
        """.trimIndent()
        return jdbcTemplate.query(sql, MapSqlParameterSource("k", fieldKey)) { rs, _ ->
            mapOf(
                "alertCond" to rs.getLong("alert_cond"),
                "metricStd" to rs.getLong("metric_std"),
                "reportFormField" to rs.getLong("report_form_field"),
                "docTag" to rs.getLong("doc_tag"),
                "deptPerm" to rs.getLong("dept_perm"),
                "attr" to rs.getLong("attr")
            )
        }.first()
    }
}
