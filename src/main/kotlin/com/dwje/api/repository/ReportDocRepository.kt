package com.dwje.api.repository

import com.dwje.api.common.util.Rs
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 보고서 문서 인스턴스 Repository (PR-03/04, QC-03, RP-06/07 공통)
 *
 * 참조 테이블 : ax.tb_rpt_doc, ax.tb_rpt_doc_field, ax.tb_rpt_doc_event,
 *              ax.tb_rpt_doc_image, ax.tb_rpt_doc_approval, ax.tb_rpt_scrap_row
 *              (스키마 확장 — resources/db/V2__ax_report_extension.sql)
 *
 * 문서 상태 흐름 : DRAFT → SAVED → CONFIRMED / REJECTED (폐기 보고서는 PUBLISHED 추가)
 */
@Repository
class ReportDocRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) {

    /**
     * 보고서 문서를 생성한다.
     *
     * @param docKindCd DAILY / QUALITY / SCRAP
     * @return 생성된 문서 ID
     */
    fun insertDoc(
        docKindCd: String,
        reportId: String?,
        formId: Int?,
        title: String?,
        targetDate: LocalDate?,
        periodFrom: LocalDateTime?,
        periodTo: LocalDateTime?,
        occurDate: LocalDate?,
        versionNo: Int,
        plantCd: String?,
        lotNo: String?,
        productId: Int?,
        customerId: Int?,
        disclosurePolicy: String?,
        docNo: String?,
        actor: String
    ): Long {
        val sql = """
            INSERT INTO ax.tb_rpt_doc (
                doc_no, report_id, form_id, doc_kind_cd, title,
                target_date, period_from, period_to, occur_date,
                version_no, state_cd, plant_cd, lot_no, product_id, customer_id,
                disclosure_policy, generated_at, generated_by, ins_user, upd_user
            ) VALUES (
                :docNo, :reportId, :formId, :docKindCd, :title,
                :targetDate, :periodFrom, :periodTo, :occurDate,
                :versionNo, 'DRAFT', :plantCd, :lotNo, :productId, :customerId,
                :disclosurePolicy, now(), :actor, :actor, :actor
            )
            RETURNING doc_id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("docNo", docNo)
            .addValue("reportId", reportId)
            .addValue("formId", formId)
            .addValue("docKindCd", docKindCd)
            .addValue("title", title?.take(200))
            .addValue("targetDate", targetDate)
            .addValue("periodFrom", periodFrom)
            .addValue("periodTo", periodTo)
            .addValue("occurDate", occurDate)
            .addValue("versionNo", versionNo)
            .addValue("plantCd", plantCd)
            .addValue("lotNo", lotNo)
            .addValue("productId", productId)
            .addValue("customerId", customerId)
            .addValue("disclosurePolicy", disclosurePolicy?.take(200))
            .addValue("actor", actor)

        return jdbcTemplate.queryForObject(sql, params, Long::class.java) ?: 0L
    }

    /**
     * 문서 단건을 조회한다.
     */
    fun findDoc(docId: Long): Map<String, Any?>? {
        val sql = """
            SELECT
                d.doc_id, d.doc_no, d.report_id, r.report_nm, d.form_id, f.form_nm,
                d.doc_kind_cd, d.title, d.target_date, d.period_from, d.period_to, d.occur_date,
                d.version_no, d.state_cd, d.plant_cd, d.lot_no, d.product_id, p.model_cd,
                d.customer_id, c.customer_nm, d.disclosure_policy, d.correction_cnt,
                d.summary_json, d.due_date,
                d.generated_at, d.generated_by, gu.user_nm AS generated_by_nm,
                d.confirmed_at, d.confirmed_by, cu.user_nm AS confirmed_by_nm,
                d.rejected_at, d.rejected_by, d.reject_reason, d.remark
            FROM ax.tb_rpt_doc d
            LEFT JOIN ax.tb_rpt_report   r  ON r.report_id   = d.report_id
            LEFT JOIN ax.tb_rpt_form     f  ON f.form_id     = d.form_id
            LEFT JOIN ax.tb_prod_product p  ON p.product_id  = d.product_id
            LEFT JOIN ax.tb_prod_customer c ON c.customer_id = d.customer_id
            LEFT JOIN ax.tb_sys_user     gu ON gu.user_id    = d.generated_by
            LEFT JOIN ax.tb_sys_user     cu ON cu.user_id    = d.confirmed_by
            WHERE d.doc_id  = :docId
              AND d.del_flg = 'N'
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource("docId", docId)) { rs, _ -> mapDoc(rs) }.firstOrNull()
    }

    /**
     * 문서 번호(doc_no)로 문서를 조회한다. (폐기 보고서 상세)
     */
    fun findDocByNo(docNo: String): Map<String, Any?>? {
        val sql = "SELECT doc_id FROM ax.tb_rpt_doc WHERE doc_no = :docNo AND del_flg = 'N'"
        val docId = jdbcTemplate.query(sql, MapSqlParameterSource("docNo", docNo)) { rs, _ -> rs.getLong("doc_id") }
            .firstOrNull() ?: return null
        return findDoc(docId)
    }

    /**
     * 지정 일자의 최신 보고서 문서를 조회한다. (일일 생산현황 보고 초안 조회)
     */
    fun findLatestDocByTargetDate(docKindCd: String, targetDate: LocalDate): Map<String, Any?>? {
        val sql = """
            SELECT doc_id
            FROM ax.tb_rpt_doc
            WHERE doc_kind_cd = :docKindCd
              AND target_date = :targetDate
              AND del_flg     = 'N'
            ORDER BY version_no DESC, doc_id DESC
            LIMIT 1
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("docKindCd", docKindCd)
            .addValue("targetDate", targetDate)

        val docId = jdbcTemplate.query(sql, params) { rs, _ -> rs.getLong("doc_id") }.firstOrNull() ?: return null
        return findDoc(docId)
    }

    /**
     * 문서 목록을 조회한다. (보고서 이력 조회)
     *
     * @param docKindCd 문서 구분
     * @param from      조회 시작일
     * @param to        조회 종료일
     * @param state     문서 상태
     * @param formId    양식 ID (품질 보고서)
     */
    fun findDocs(
        docKindCd: String,
        from: LocalDate,
        to: LocalDate,
        state: String?,
        formId: Int?,
        limit: Int,
        offset: Int
    ): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT
                d.doc_id, d.doc_no, d.report_id, r.report_nm, d.form_id, f.form_nm,
                d.doc_kind_cd, d.title, d.target_date, d.period_from, d.period_to, d.occur_date,
                d.version_no, d.state_cd, d.plant_cd, d.lot_no, d.product_id, p.model_cd,
                d.customer_id, c.customer_nm, d.disclosure_policy, d.correction_cnt,
                d.summary_json, d.due_date,
                d.generated_at, d.generated_by, gu.user_nm AS generated_by_nm,
                d.confirmed_at, d.confirmed_by, cu.user_nm AS confirmed_by_nm,
                d.rejected_at, d.rejected_by, d.reject_reason, d.remark
            FROM ax.tb_rpt_doc d
            LEFT JOIN ax.tb_rpt_report   r  ON r.report_id   = d.report_id
            LEFT JOIN ax.tb_rpt_form     f  ON f.form_id     = d.form_id
            LEFT JOIN ax.tb_prod_product p  ON p.product_id  = d.product_id
            LEFT JOIN ax.tb_prod_customer c ON c.customer_id = d.customer_id
            LEFT JOIN ax.tb_sys_user     gu ON gu.user_id    = d.generated_by
            LEFT JOIN ax.tb_sys_user     cu ON cu.user_id    = d.confirmed_by
            WHERE d.doc_kind_cd = :docKindCd
              AND d.del_flg     = 'N'
              AND coalesce(d.target_date, d.generated_at::date) >= :from
              AND coalesce(d.target_date, d.generated_at::date) <= :to
            """.trimIndent()
        )

        val params = MapSqlParameterSource()
            .addValue("docKindCd", docKindCd)
            .addValue("from", from)
            .addValue("to", to)

        appendDocFilters(sql, params, state, formId)

        sql.append("\nORDER BY coalesce(d.target_date, d.generated_at::date) DESC, d.version_no DESC")
        sql.append("\nLIMIT :limit OFFSET :offset")
        params.addValue("limit", limit).addValue("offset", offset)

        return jdbcTemplate.query(sql.toString(), params) { rs, _ -> mapDoc(rs) }
    }

    /** 문서 목록 전체 건수 */
    fun countDocs(docKindCd: String, from: LocalDate, to: LocalDate, state: String?, formId: Int?): Long {
        val sql = StringBuilder(
            """
            SELECT count(*)
            FROM ax.tb_rpt_doc d
            WHERE d.doc_kind_cd = :docKindCd
              AND d.del_flg     = 'N'
              AND coalesce(d.target_date, d.generated_at::date) >= :from
              AND coalesce(d.target_date, d.generated_at::date) <= :to
            """.trimIndent()
        )

        val params = MapSqlParameterSource()
            .addValue("docKindCd", docKindCd)
            .addValue("from", from)
            .addValue("to", to)

        appendDocFilters(sql, params, state, formId)

        return jdbcTemplate.queryForObject(sql.toString(), params, Long::class.java) ?: 0L
    }

    /** 문서 목록/건수 공통 동적 조건 */
    private fun appendDocFilters(sql: StringBuilder, params: MapSqlParameterSource, state: String?, formId: Int?) {
        if (!state.isNullOrBlank()) {
            sql.append(" AND d.state_cd = :state")
            params.addValue("state", state.trim().uppercase())
        }
        if (formId != null) {
            sql.append(" AND d.form_id = :formId")
            params.addValue("formId", formId)
        }
    }

    /**
     * 문서 상태를 변경한다. (확정 / 반려)
     *
     * @param stateCd 변경할 상태
     * @param reason  반려 사유
     */
    fun updateDocState(docId: Long, stateCd: String, reason: String?, actor: String): Int {
        val sql = """
            UPDATE ax.tb_rpt_doc
               SET state_cd      = :stateCd,
                   confirmed_at  = CASE WHEN :stateCd = 'CONFIRMED' THEN now() ELSE confirmed_at END,
                   confirmed_by  = CASE WHEN :stateCd = 'CONFIRMED' THEN :actor ELSE confirmed_by END,
                   rejected_at   = CASE WHEN :stateCd = 'REJECTED'  THEN now() ELSE rejected_at END,
                   rejected_by   = CASE WHEN :stateCd = 'REJECTED'  THEN :actor ELSE rejected_by END,
                   reject_reason = CASE WHEN :stateCd = 'REJECTED'  THEN :reason ELSE reject_reason END,
                   upd_date      = now(),
                   upd_user      = :actor
             WHERE doc_id  = :docId
               AND del_flg = 'N'
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("docId", docId)
            .addValue("stateCd", stateCd)
            .addValue("reason", reason?.take(500))
            .addValue("actor", actor)

        return jdbcTemplate.update(sql, params)
    }

    /**
     * 문서를 소프트 삭제한다. (폐기 위저드 취소)
     *
     * 물리 삭제하지 않는다 — 자식 6종(field · event · image · approval · scrap_row · unmask_req)은
     * 그대로 남고, 모든 조회가 `del_flg = 'N'` 을 걸어 목록·상세·통계에서 함께 빠진다.
     * 이미 삭제된 문서나 다른 구분의 문서는 건드리지 않도록 구분 코드도 조건에 넣는다.
     *
     * @return 갱신 행 수 (0 이면 대상이 없거나 이미 삭제됨)
     */
    fun softDeleteDoc(docId: Long, docKindCd: String, actor: String): Int {
        val sql = """
            UPDATE ax.tb_rpt_doc
               SET del_flg  = 'Y',
                   upd_date = now(),
                   upd_user = :actor
             WHERE doc_id      = :docId
               AND doc_kind_cd = :docKindCd
               AND del_flg     = 'N'
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("docId", docId)
            .addValue("docKindCd", docKindCd)
            .addValue("actor", actor)

        return jdbcTemplate.update(sql, params)
    }

    /** 문서 번호를 채번해 저장한다. (폐기 보고서 발행) */
    fun updateDocNo(docId: Long, docNo: String, stateCd: String, actor: String): Int {
        val sql = """
            UPDATE ax.tb_rpt_doc
               SET doc_no   = :docNo,
                   state_cd = :stateCd,
                   upd_date = now(),
                   upd_user = :actor
             WHERE doc_id = :docId
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("docId", docId)
            .addValue("docNo", docNo)
            .addValue("stateCd", stateCd)
            .addValue("actor", actor)

        return jdbcTemplate.update(sql, params)
    }

    /** 보정 건수를 갱신한다. */
    fun updateCorrectionCount(docId: Long, correctionCnt: Int, actor: String): Int {
        val sql = """
            UPDATE ax.tb_rpt_doc
               SET correction_cnt = :correctionCnt,
                   state_cd       = CASE WHEN state_cd = 'DRAFT' THEN 'SAVED' ELSE state_cd END,
                   upd_date       = now(),
                   upd_user       = :actor
             WHERE doc_id = :docId
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("docId", docId)
            .addValue("correctionCnt", correctionCnt)
            .addValue("actor", actor)

        return jdbcTemplate.update(sql, params)
    }

    /** 요약 정보(JSON)를 저장한다. */
    fun updateSummary(docId: Long, summaryJson: String?, actor: String): Int {
        val sql = """
            UPDATE ax.tb_rpt_doc
               SET summary_json = CAST(:summaryJson AS jsonb),
                   upd_date     = now(),
                   upd_user     = :actor
             WHERE doc_id = :docId
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("docId", docId)
            .addValue("summaryJson", summaryJson)
            .addValue("actor", actor)

        return jdbcTemplate.update(sql, params)
    }

    // ---------------------------------------------------------------------------------
    // 보고서 항목 값
    // ---------------------------------------------------------------------------------

    /**
     * 보고서 항목 값을 일괄 저장한다. (기존 값은 교체)
     *
     * @param fields 항목 목록 — sectionCd, fieldNm, fieldCode, fieldValue, originCd, blindFieldKey
     */
    fun replaceFields(docId: Long, fields: List<Map<String, Any?>>) {
        jdbcTemplate.update("DELETE FROM ax.tb_rpt_doc_field WHERE doc_id = :docId", MapSqlParameterSource("docId", docId))
        if (fields.isEmpty()) return

        val sql = """
            INSERT INTO ax.tb_rpt_doc_field (
                doc_id, field_seq, section_cd, field_nm, field_code,
                field_value, origin_cd, is_corrected, blind_field_key, remark
            ) VALUES (
                :docId, :fieldSeq, :sectionCd, :fieldNm, :fieldCode,
                :fieldValue, :originCd, :isCorrected, :blindFieldKey, :remark
            )
        """.trimIndent()

        val batch = fields.mapIndexed { idx, f ->
            MapSqlParameterSource()
                .addValue("docId", docId)
                .addValue("fieldSeq", (idx + 1).toShort())
                .addValue("sectionCd", f["sectionCd"] ?: "BODY")
                .addValue("fieldNm", (f["fieldNm"] as? String)?.take(100) ?: "-")
                .addValue("fieldCode", (f["fieldCode"] as? String)?.take(50))
                .addValue("fieldValue", f["fieldValue"] as? String)
                .addValue("originCd", f["originCd"] ?: "MANUAL")
                .addValue("isCorrected", f["isCorrected"] ?: false)
                .addValue("blindFieldKey", f["blindFieldKey"] as? String)
                .addValue("remark", (f["remark"] as? String)?.take(300))
        }.toTypedArray()

        jdbcTemplate.batchUpdate(sql, batch)
    }

    /**
     * 보고서 항목 값을 조회한다.
     */
    fun findFields(docId: Long): List<Map<String, Any?>> {
        val sql = """
            SELECT field_seq, section_cd, field_nm, field_code, field_value,
                   origin_cd, is_corrected, blind_field_key, remark
            FROM ax.tb_rpt_doc_field
            WHERE doc_id = :docId
            ORDER BY field_seq
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource("docId", docId)) { rs, _ ->
            mapOf(
                "seq" to rs.getInt("field_seq"),
                "section" to rs.getString("section_cd"),
                "field" to rs.getString("field_nm"),
                "fieldCode" to rs.getString("field_code"),
                "value" to rs.getString("field_value"),
                "origin" to rs.getString("origin_cd"),
                "corrected" to rs.getBoolean("is_corrected"),
                "blindFieldKey" to rs.getString("blind_field_key"),
                "remark" to rs.getString("remark")
            )
        }
    }

    /**
     * 항목 값을 개별 보정한다. (보정 표시 포함)
     *
     * @return 보정된 항목 수
     */
    fun updateFieldValues(docId: Long, values: Map<String, String?>, actor: String): Int {
        if (values.isEmpty()) return 0

        val sql = """
            UPDATE ax.tb_rpt_doc_field
               SET field_value  = :fieldValue,
                   is_corrected = true,
                   origin_cd    = 'MANUAL'
             WHERE doc_id    = :docId
               AND field_code = :fieldCode
        """.trimIndent()

        val batch = values.map { (fieldCode, value) ->
            MapSqlParameterSource()
                .addValue("docId", docId)
                .addValue("fieldCode", fieldCode)
                .addValue("fieldValue", value)
        }.toTypedArray()

        return jdbcTemplate.batchUpdate(sql, batch).sum()
    }

    // ---------------------------------------------------------------------------------
    // 처리 이력 · 이미지 · 결재선
    // ---------------------------------------------------------------------------------

    /**
     * 문서 처리 이력을 기록한다.
     *
     * @param eventTypeCd GENERATE / REGENERATE / CORRECT / SAVE / CONFIRM / REJECT / COPY / PUBLISH / EXPORT
     */
    fun insertEvent(docId: Long, eventTypeCd: String, detail: String?, actor: String, actorDeptNm: String?) {
        val sql = """
            INSERT INTO ax.tb_rpt_doc_event (doc_id, event_at, event_type_cd, detail, actor_user_id, actor_dept_nm)
            VALUES (:docId, now(), :eventTypeCd, :detail, :actor, :actorDeptNm)
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("docId", docId)
            .addValue("eventTypeCd", eventTypeCd)
            .addValue("detail", detail?.take(500))
            .addValue("actor", actor)
            .addValue("actorDeptNm", actorDeptNm)

        jdbcTemplate.update(sql, params)
    }

    /**
     * 문서 처리 이력을 조회한다. (No.65)
     */
    fun findEvents(docId: Long): List<Map<String, Any?>> {
        val sql = """
            SELECT e.event_at, e.event_type_cd, e.detail, e.actor_user_id, e.actor_dept_nm, u.user_nm
            FROM ax.tb_rpt_doc_event e
            LEFT JOIN ax.tb_sys_user u ON u.user_id = e.actor_user_id
            WHERE e.doc_id = :docId
            ORDER BY e.event_at
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource("docId", docId)) { rs, _ ->
            mapOf(
                "ts" to Rs.dateTime(rs, "event_at"),
                "type" to rs.getString("event_type_cd"),
                "detail" to rs.getString("detail"),
                "by" to (rs.getString("user_nm") ?: rs.getString("actor_user_id")),
                "byDept" to rs.getString("actor_dept_nm")
            )
        }
    }

    /**
     * 증빙 이미지를 첨부한다. (No.91)
     *
     * @return 첨부 건수
     */
    fun insertImages(docId: Long, images: List<Map<String, Any?>>, actor: String): Int {
        if (images.isEmpty()) return 0

        val sql = """
            INSERT INTO ax.tb_rpt_doc_image (doc_id, image_nm, nas_path, defect_cd, lot_no, captured_at, attached_by)
            VALUES (:docId, :imageNm, :nasPath, :defectCd, :lotNo, :capturedAt, :actor)
        """.trimIndent()

        val batch = images.map { img ->
            MapSqlParameterSource()
                .addValue("docId", docId)
                .addValue("imageNm", (img["name"] as? String)?.take(200) ?: "evidence")
                .addValue("nasPath", (img["nasPath"] as? String)?.take(1000) ?: "")
                .addValue("defectCd", img["defectCd"])
                .addValue("lotNo", img["lotNo"])
                .addValue("capturedAt", img["capturedAt"])
                .addValue("actor", actor)
        }.toTypedArray()

        return jdbcTemplate.batchUpdate(sql, batch).sum()
    }

    /** 첨부된 증빙 이미지를 조회한다. */
    fun findImages(docId: Long): List<Map<String, Any?>> {
        val sql = """
            SELECT image_id, image_nm, nas_path, defect_cd, lot_no, captured_at, attached_at
            FROM ax.tb_rpt_doc_image
            WHERE doc_id = :docId
            ORDER BY image_id
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource("docId", docId)) { rs, _ ->
            mapOf(
                "id" to rs.getLong("image_id"),
                "name" to rs.getString("image_nm"),
                "nasPath" to rs.getString("nas_path"),
                "defectType" to rs.getString("defect_cd"),
                "lotNo" to rs.getString("lot_no"),
                "capturedAt" to Rs.dateTime(rs, "captured_at"),
                "attachedAt" to Rs.dateTime(rs, "attached_at")
            )
        }
    }

    /**
     * 검토 부서·결재선을 지정한다. (No.122 — 기존 결재선 교체)
     */
    fun replaceApprovals(docId: Long, approvals: List<Map<String, Any?>>, dueDate: LocalDate?, actor: String) {
        jdbcTemplate.update(
            "DELETE FROM ax.tb_rpt_doc_approval WHERE doc_id = :docId",
            MapSqlParameterSource("docId", docId)
        )

        if (approvals.isNotEmpty()) {
            val sql = """
                INSERT INTO ax.tb_rpt_doc_approval (doc_id, appr_seq, step_cd, dept_id, manager_user_id, state_cd, due_date)
                VALUES (:docId, :apprSeq, :stepCd, :deptId, :managerUserId, 'WAITING', :dueDate)
            """.trimIndent()

            val batch = approvals.mapIndexed { idx, a ->
                MapSqlParameterSource()
                    .addValue("docId", docId)
                    .addValue("apprSeq", (idx + 1).toShort())
                    .addValue("stepCd", a["stepCd"] ?: "REVIEW")
                    .addValue("deptId", a["deptId"])
                    .addValue("managerUserId", a["managerUserId"])
                    .addValue("dueDate", dueDate)
            }.toTypedArray()

            jdbcTemplate.batchUpdate(sql, batch)
        }

        // 문서 자체의 기한도 함께 갱신한다.
        jdbcTemplate.update(
            "UPDATE ax.tb_rpt_doc SET due_date = :dueDate, upd_date = now(), upd_user = :actor WHERE doc_id = :docId",
            MapSqlParameterSource().addValue("docId", docId).addValue("dueDate", dueDate).addValue("actor", actor)
        )
    }

    /** 결재선·검토 의견을 조회한다. */
    fun findApprovals(docId: Long): List<Map<String, Any?>> {
        val sql = """
            SELECT a.appr_seq, a.step_cd, a.dept_id, d.dept_nm, a.manager_user_id, u.user_nm,
                   a.state_cd, a.opinion, a.acted_at, a.due_date
            FROM ax.tb_rpt_doc_approval a
            LEFT JOIN ax.tb_sys_dept d ON d.dept_id = a.dept_id
            LEFT JOIN ax.tb_sys_user u ON u.user_id = a.manager_user_id
            WHERE a.doc_id = :docId
            ORDER BY a.appr_seq
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource("docId", docId)) { rs, _ ->
            mapOf(
                "seq" to rs.getInt("appr_seq"),
                "step" to rs.getString("step_cd"),
                "deptId" to Rs.intOrNull(rs, "dept_id"),
                "dept" to rs.getString("dept_nm"),
                "manager" to rs.getString("user_nm"),
                "managerEmpNo" to rs.getString("manager_user_id"),
                "state" to rs.getString("state_cd"),
                "opinion" to rs.getString("opinion"),
                "actedAt" to Rs.dateTime(rs, "acted_at"),
                "due" to Rs.dateTime(rs, "due_date")
            )
        }
    }

    /** 문서 결과 행 매핑 */
    private fun mapDoc(rs: java.sql.ResultSet): Map<String, Any?> = mapOf(
        "docId" to rs.getLong("doc_id"),
        "reportId" to rs.getLong("doc_id"),
        "docNo" to rs.getString("doc_no"),
        "reportDefId" to rs.getString("report_id"),
        "reportNm" to rs.getString("report_nm"),
        "formId" to Rs.intOrNull(rs, "form_id"),
        "formNm" to rs.getString("form_nm"),
        "kind" to rs.getString("doc_kind_cd"),
        "title" to rs.getString("title"),
        "targetDate" to Rs.dateTime(rs, "target_date"),
        "periodFrom" to Rs.dateTime(rs, "period_from"),
        "periodTo" to Rs.dateTime(rs, "period_to"),
        "occurDate" to Rs.dateTime(rs, "occur_date"),
        "version" to rs.getInt("version_no"),
        "state" to rs.getString("state_cd"),
        "plantCd" to rs.getString("plant_cd"),
        "lotNo" to rs.getString("lot_no"),
        "productId" to Rs.intOrNull(rs, "product_id"),
        "model" to rs.getString("model_cd"),
        "customerId" to Rs.intOrNull(rs, "customer_id"),
        "customer" to rs.getString("customer_nm"),
        "disclosurePolicy" to rs.getString("disclosure_policy"),
        "correctionCnt" to rs.getInt("correction_cnt"),
        "summaryJson" to rs.getString("summary_json"),
        "due" to Rs.dateTime(rs, "due_date"),
        "generatedAt" to Rs.dateTime(rs, "generated_at"),
        "generatedBy" to (rs.getString("generated_by_nm") ?: rs.getString("generated_by")),
        "confirmedAt" to Rs.dateTime(rs, "confirmed_at"),
        "confirmedBy" to (rs.getString("confirmed_by_nm") ?: rs.getString("confirmed_by")),
        "rejectedAt" to Rs.dateTime(rs, "rejected_at"),
        "rejectReason" to rs.getString("reject_reason"),
        "remark" to rs.getString("remark")
    )
}
