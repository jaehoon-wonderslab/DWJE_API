package com.dwje.api.repository

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

/**
 * 데이터 접근 항목 · 응답 필드명 Repository (SY-03, V33)
 *
 * 참조 테이블
 * - 항목       : ax.tb_sys_data_field (apply_flg 는 V33). 분류(category_cd · DATA_FIELD_CATEGORY)는 2026-10-07 에 없앴다 — 읽지도 쓰지도 않는다
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

    /** 항목 한 건 — 없으면 null. use_flg 와 무관하게 찾는다(삭제·수정 대상 확인용). */
    fun findField(fieldKey: String): Map<String, Any?>? {
        val sql = """
            SELECT f.field_key, f.field_nm, f.field_desc,
                   f.apply_flg, f.use_flg, f.sort_seq,
                   (SELECT string_agg(a.attr_name, ',' ORDER BY a.attr_name)
                      FROM ax.tb_sys_data_field_attr a WHERE a.field_key = f.field_key) AS attrs
            FROM ax.tb_sys_data_field f
            WHERE f.field_key = :fieldKey
        """.trimIndent()
        return jdbcTemplate.query(sql, MapSqlParameterSource("fieldKey", fieldKey)) { rs, _ ->
            mapOf(
                "key" to rs.getString("field_key"),
                "name" to rs.getString("field_nm"),
                "desc" to rs.getString("field_desc"),
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
            SELECT f.field_key, f.field_nm, f.sort_seq,
                   (SELECT string_agg(a.attr_name, ',' ORDER BY a.attr_name)
                      FROM ax.tb_sys_data_field_attr a WHERE a.field_key = f.field_key) AS attrs
            FROM ax.tb_sys_data_field f
            WHERE f.use_flg = 'Y' AND f.apply_flg = 'Y'
            ORDER BY f.sort_seq, f.field_key
        """.trimIndent()
        return jdbcTemplate.query(sql, MapSqlParameterSource()) { rs, _ ->
            mapOf(
                "key" to rs.getString("field_key"),
                "name" to rs.getString("field_nm"),
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
    fun insertField(fieldKey: String, name: String, desc: String?, sortSeq: Int, actor: String): Int {
        val sql = """
            INSERT INTO ax.tb_sys_data_field
                (field_key, field_nm, field_desc, sort_seq, use_flg, apply_flg, ins_user, upd_user)
            VALUES (:fieldKey, :name, :desc, :sortSeq, 'Y', 'N', :actor, :actor)
        """.trimIndent()
        return jdbcTemplate.update(
            sql,
            MapSqlParameterSource()
                .addValue("fieldKey", fieldKey).addValue("name", name).addValue("desc", desc)
                .addValue("sortSeq", sortSeq).addValue("actor", actor)
        )
    }

    fun updateField(fieldKey: String, name: String, desc: String?, actor: String): Int {
        val sql = """
            UPDATE ax.tb_sys_data_field
               SET field_nm = :name, field_desc = :desc, upd_date = now(), upd_user = :actor
             WHERE field_key = :fieldKey
        """.trimIndent()
        return jdbcTemplate.update(
            sql,
            MapSqlParameterSource()
                .addValue("fieldKey", fieldKey).addValue("name", name).addValue("desc", desc)
                .addValue("actor", actor)
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
    // ---- 새로 발견된 응답 데이터 이름 (V83) ----

    /** 항목 표에 등록된 응답 필드명 전부 — 적용 여부 · 사용 여부와 관계없이 */
    fun findAllAttrNames(): Set<String> =
        jdbcTemplate.query("SELECT attr_name FROM ax.tb_sys_data_field_attr", MapSqlParameterSource()) { rs, _ -> rs.getString("attr_name") }.toSet()

    /** 기록표가 있는지 — V83 을 아직 적용하지 않은 DB 에서는 기록하지 않는다 */
    fun hasSeenTable(): Boolean =
        jdbcTemplate.queryForObject("SELECT to_regclass('ax.tb_sys_data_attr_seen') IS NOT NULL", MapSqlParameterSource(), Boolean::class.java) == true

    /**
     * 발견 기록 — 처음이면 넣고, 있으면 마지막 시각 · 횟수 · 경로(없던 것만, 1000자까지)를 고친다. 상태는 건드리지 않는다.
     */
    fun upsertSeen(attrNames: Collection<String>, apiPath: String): Int {
        if (attrNames.isEmpty()) return 0
        val sql = """
            INSERT INTO ax.tb_sys_data_attr_seen (attr_name, api_paths)
            VALUES (:attrName, :path)
            ON CONFLICT (attr_name) DO UPDATE SET
                last_seen_at = now(),
                seen_cnt = ax.tb_sys_data_attr_seen.seen_cnt + 1,
                api_paths = CASE
                    WHEN ax.tb_sys_data_attr_seen.api_paths IS NULL THEN :path
                    WHEN position(:path IN ax.tb_sys_data_attr_seen.api_paths) > 0 THEN ax.tb_sys_data_attr_seen.api_paths
                    WHEN length(ax.tb_sys_data_attr_seen.api_paths) + length(:path) + 1 > 1000 THEN ax.tb_sys_data_attr_seen.api_paths
                    ELSE ax.tb_sys_data_attr_seen.api_paths || ',' || :path
                END
        """.trimIndent()
        val batch = attrNames.map { MapSqlParameterSource().addValue("attrName", it).addValue("path", apiPath) }.toTypedArray()
        return jdbcTemplate.batchUpdate(sql, batch).sum()
    }

    /** 발견 기록 목록 — 항목 표에 등록된 이름은 뺀다 */
    fun findSeen(): List<Map<String, Any?>> {
        val sql = """
            SELECT s.attr_name, s.seen_cnt, s.api_paths, s.status_cd, s.upd_user,
                   to_char(s.first_seen_at AT TIME ZONE 'Asia/Seoul', 'YYYY-MM-DD"T"HH24:MI:SS') AS first_seen,
                   to_char(s.last_seen_at  AT TIME ZONE 'Asia/Seoul', 'YYYY-MM-DD"T"HH24:MI:SS') AS last_seen
              FROM ax.tb_sys_data_attr_seen s
             WHERE NOT EXISTS (SELECT 1 FROM ax.tb_sys_data_field_attr a WHERE a.attr_name = s.attr_name)
             ORDER BY s.status_cd, s.first_seen_at DESC, s.attr_name
        """.trimIndent()
        return jdbcTemplate.query(sql, MapSqlParameterSource()) { rs, _ ->
            mapOf(
                "attrName" to rs.getString("attr_name"),
                // 화면 시각은 한국 시각(DB 는 timestamptz)
                "firstSeenAt" to rs.getString("first_seen"),
                "lastSeenAt" to rs.getString("last_seen"),
                "seenCnt" to rs.getLong("seen_cnt"),
                "apiPaths" to (rs.getString("api_paths")?.split(',')?.filter { it.isNotBlank() } ?: emptyList()),
                "status" to rs.getString("status_cd"),
                "updUser" to rs.getString("upd_user")
            )
        }
    }

    /** 상태 바꾸기 — NEW · IGNORED. 기록이 없던 이름이면 넣는다(화면에서 미리 가리지 않음으로 둘 때) */
    fun setSeenStatus(attrNames: Collection<String>, status: String, actor: String): Int {
        if (attrNames.isEmpty()) return 0
        val sql = """
            INSERT INTO ax.tb_sys_data_attr_seen (attr_name, status_cd, upd_user)
            VALUES (:attrName, :status, :actor)
            ON CONFLICT (attr_name) DO UPDATE SET status_cd = :status, upd_date = now(), upd_user = :actor
        """.trimIndent()
        val batch = attrNames.map { MapSqlParameterSource().addValue("attrName", it).addValue("status", status).addValue("actor", actor) }.toTypedArray()
        return jdbcTemplate.batchUpdate(sql, batch).sum()
    }

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

    /**
     * 응답 필드명을 다른 항목으로 옮긴다. 없으면 새로 붙인다 (04 DTP-02).
     * attr_name 이 전역 UNIQUE 라 UPDATE 로 옮겨도 충돌하지 않는다. 메모는 새 값이 있을 때만 바꾼다.
     */
    fun moveAttr(attrName: String, toFieldKey: String, remark: String?, actor: String): Int {
        val params = MapSqlParameterSource().addValue("attrName", attrName).addValue("to", toFieldKey)
            .addValue("remark", remark).addValue("actor", actor)
        val moved = jdbcTemplate.update(
            "UPDATE ax.tb_sys_data_field_attr SET field_key = :to, remark = coalesce(:remark, remark) WHERE attr_name = :attrName",
            params
        )
        if (moved > 0) return moved
        return jdbcTemplate.update(
            "INSERT INTO ax.tb_sys_data_field_attr (field_key, attr_name, remark, ins_user) VALUES (:to, :attrName, :remark, :actor)",
            params
        )
    }

    /** 응답 필드명을 어느 항목에서도 뺀다 */
    fun releaseAttr(attrName: String): Int =
        jdbcTemplate.update(
            "DELETE FROM ax.tb_sys_data_field_attr WHERE attr_name = :attrName",
            MapSqlParameterSource("attrName", attrName)
        )

    /** 새 항목의 열람을 사용 중 부서 전부에 허용한다 — 통합관리자(전 권한)·미배정(0건 고정)은 뺀다 */
    fun grantFieldToDepts(fieldKey: String, unassignedDeptName: String, actor: String): Int =
        jdbcTemplate.update(
            """
            INSERT INTO ax.tb_sys_dept_data_perm (dept_id, field_key, is_allowed, ins_user, upd_user)
            SELECT d.dept_id, :fieldKey, true, :actor, :actor
              FROM ax.tb_sys_dept d
             WHERE d.use_flg = 'Y' AND NOT d.is_super_admin AND d.dept_nm <> :unassigned
            ON CONFLICT DO NOTHING
            """.trimIndent(),
            MapSqlParameterSource().addValue("fieldKey", fieldKey).addValue("unassigned", unassignedDeptName).addValue("actor", actor)
        )

    /**
     * 고객사 이름 후보 — 근거 문서 제목·발췌에서 고객사(customer) 권한이 없는 사람에게 가릴 낱말 (2026-10-03).
     *
     * - 문서 제목 맨 앞 대괄호 표기(`[LGIT CM]`·`[Cowell]`) — 사내 문서 적재 규칙상 고객사·사업부 표기다
     * - 고객사 마스터(`ax.tb_prod_customer`)의 코드·이름
     * - 문서 메타(`vec.vw_doc_context.customer_nm`)
     *
     * 용어 사전의 고객사 분류는 「고객」·「업체」 같은 일반 낱말이 섞여 있어 쓰지 않는다.
     */
    fun findCustomerNames(): List<String> = jdbcTemplate.queryForList(
        """
        SELECT DISTINCT n FROM (
            SELECT substring(title FROM '^\[([^]]+)\]') AS n FROM vec.tb_doc WHERE del_flg = 'N'
            UNION ALL SELECT customer_cd FROM ax.tb_prod_customer
            UNION ALL SELECT customer_nm FROM ax.tb_prod_customer
            UNION ALL SELECT customer_nm FROM vec.vw_doc_context
        ) x WHERE n IS NOT NULL AND length(trim(n)) >= 2
        """.trimIndent(),
        MapSqlParameterSource(), String::class.java
    )
}
