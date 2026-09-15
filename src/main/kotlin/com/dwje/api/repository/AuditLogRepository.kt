package com.dwje.api.repository

import com.dwje.api.common.util.Rs
import com.dwje.api.common.util.SqlLikeUtils
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.LocalDate

/**
 * 보안 감사 로그 Repository (SY-09)
 *
 * 참조 테이블 : ax.tb_log_audit, ax.tb_sys_perm_log, ax.tb_sys_login_hist
 *
 * 감사 로그 자동 기록 대상 (공통 규약 6)
 * - 권한 변경 : /system/menu-perms, /system/data-perms, /system/users, /system/depts
 * - 마스킹    : /quality/reports/{id}/unmask-request, blind 열람 시도
 * - 출력      : /reports/{id}/export, /print, /download-logs
 * - AI 모델   : /ai/model-releases/{ver}/apply, /rollback, /ai/model-config
 * - 기준 수치 : /metrics/standards 전체
 * - 순위      : /products/families/order
 * - 알림 조건 : /alert-conditions 전체
 * - 연동      : /sync/jobs/{id}/retry, /sync/jobs/manual
 */
@Repository
class AuditLogRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) {

    /**
     * 감사 로그를 기록한다.
     *
     * @param logTypeCd  LOG_AUDIT_TYPE — MASK / UNMASK_REQ / RAW_VIEW / PERM_CHANGE / LOGIN / AUTO_GEN
     * @param userId     수행자 사번
     * @param deptNm     수행자 부서명
     * @param menuId     화면 ID
     * @param fieldKey   대상 데이터 항목 key
     * @param targetDesc 대상 설명
     * @param resultCd   LOG_AUDIT_RESULT — ALLOW / BLIND / REJECT
     * @param maskedCnt  마스킹 처리 건수
     * @param remark     비고
     * @param ipAddr     접속 IP
     * @return 생성된 감사 로그 ID
     */
    fun insert(
        logTypeCd: String,
        userId: String?,
        deptNm: String?,
        menuId: String?,
        fieldKey: String?,
        targetDesc: String?,
        resultCd: String,
        maskedCnt: Int,
        remark: String?,
        ipAddr: String?
    ): Long {
        val sql = """
            INSERT INTO ax.tb_log_audit (
                log_at, log_type_cd, user_id, dept_nm, menu_id, field_key,
                target_desc, result_cd, masked_cnt, remark, ip_addr
            ) VALUES (
                now(), :logTypeCd, :userId, :deptNm, :menuId, :fieldKey,
                :targetDesc, :resultCd, :maskedCnt, :remark, CAST(:ipAddr AS inet)
            )
            RETURNING audit_id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("logTypeCd", logTypeCd)
            .addValue("userId", userId)
            .addValue("deptNm", deptNm)
            .addValue("menuId", menuId)
            .addValue("fieldKey", fieldKey)
            .addValue("targetDesc", targetDesc?.take(300))
            .addValue("resultCd", resultCd)
            .addValue("maskedCnt", maskedCnt)
            .addValue("remark", remark?.take(500))
            .addValue("ipAddr", ipAddr)

        return jdbcTemplate.queryForObject(sql, params, Long::class.java) ?: 0L
    }

    /**
     * 감사 로그를 조회한다. (No.190)
     *
     * 감사 로그(ax.tb_log_audit) · 권한 변경 이력(ax.tb_sys_perm_log) · 로그인 이력(ax.tb_sys_login_hist)을
     * 하나의 타임라인으로 통합해 반환한다.
     *
     * @param from     조회 시작일
     * @param to       조회 종료일
     * @param type     로그 유형 (LOG_AUDIT_TYPE)
     * @param deptNm   부서명
     * @param empNo    대상 사번
     */
    fun findAuditLogs(
        from: LocalDate,
        to: LocalDate,
        type: String?,
        deptNm: String?,
        empNo: String?,
        limit: Int,
        offset: Int
    ): List<Map<String, Any?>> {
        val sql = StringBuilder(unionSql())
        val params = baseParams(from, to, type, deptNm, empNo)

        sql.append("\nORDER BY ts DESC\nLIMIT :limit OFFSET :offset")
        params.addValue("limit", limit).addValue("offset", offset)

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "ts" to Rs.dateTime(rs, "ts"),
                "type" to rs.getString("log_type"),
                "empNo" to rs.getString("emp_no"),
                "dept" to rs.getString("dept_nm"),
                "target" to rs.getString("target_desc"),
                "detail" to rs.getString("detail"),
                "result" to rs.getString("result_cd"),
                "ip" to rs.getString("ip_addr")
            )
        }
    }

    /** 감사 로그 전체 건수 */
    fun countAuditLogs(from: LocalDate, to: LocalDate, type: String?, deptNm: String?, empNo: String?): Long {
        val sql = "SELECT count(*) FROM (${unionSql()}) t"
        val params = baseParams(from, to, type, deptNm, empNo)
        return jdbcTemplate.queryForObject(sql, params, Long::class.java) ?: 0L
    }

    /**
     * 데이터 접근 감사 조회 (No.150) — 데이터 항목 열람/차단 이력만 추출한다.
     */
    fun findDataAccessAudit(
        from: LocalDate,
        to: LocalDate,
        empNo: String?,
        fieldKey: String?,
        limit: Int,
        offset: Int
    ): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT
                a.log_at,
                a.user_id,
                a.dept_nm,
                a.field_key,
                f.field_nm,
                a.menu_id,
                m.menu_nm,
                a.log_type_cd,
                a.result_cd,
                a.masked_cnt,
                a.target_desc
            FROM ax.tb_log_audit a
            LEFT JOIN ax.tb_sys_data_field f ON f.field_key = a.field_key
            LEFT JOIN ax.tb_sys_menu       m ON m.menu_id   = a.menu_id
            WHERE a.log_at >= :from
              AND a.log_at <  :toExclusive
              AND a.field_key IS NOT NULL
            """.trimIndent()
        )

        val params = MapSqlParameterSource()
            .addValue("from", from.atStartOfDay())
            .addValue("toExclusive", to.plusDays(1).atStartOfDay())

        if (!empNo.isNullOrBlank()) {
            sql.append(" AND a.user_id = :empNo")
            params.addValue("empNo", empNo.trim())
        }
        if (!fieldKey.isNullOrBlank()) {
            sql.append(" AND a.field_key = :fieldKey")
            params.addValue("fieldKey", fieldKey.trim())
        }

        sql.append("\nORDER BY a.log_at DESC\nLIMIT :limit OFFSET :offset")
        params.addValue("limit", limit).addValue("offset", offset)

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "ts" to Rs.dateTime(rs, "log_at"),
                "empNo" to rs.getString("user_id"),
                "dept" to rs.getString("dept_nm"),
                "fieldKey" to rs.getString("field_key"),
                "fieldNm" to rs.getString("field_nm"),
                "screen" to (rs.getString("menu_nm") ?: rs.getString("menu_id")),
                "action" to rs.getString("log_type_cd"),
                "result" to rs.getString("result_cd"),
                "maskedCnt" to rs.getInt("masked_cnt"),
                "target" to rs.getString("target_desc")
            )
        }
    }

    /** 데이터 접근 감사 전체 건수 */
    fun countDataAccessAudit(from: LocalDate, to: LocalDate, empNo: String?, fieldKey: String?): Long {
        val sql = StringBuilder(
            """
            SELECT count(*)
            FROM ax.tb_log_audit a
            WHERE a.log_at >= :from
              AND a.log_at <  :toExclusive
              AND a.field_key IS NOT NULL
            """.trimIndent()
        )

        val params = MapSqlParameterSource()
            .addValue("from", from.atStartOfDay())
            .addValue("toExclusive", to.plusDays(1).atStartOfDay())

        if (!empNo.isNullOrBlank()) {
            sql.append(" AND a.user_id = :empNo")
            params.addValue("empNo", empNo.trim())
        }
        if (!fieldKey.isNullOrBlank()) {
            sql.append(" AND a.field_key = :fieldKey")
            params.addValue("fieldKey", fieldKey.trim())
        }

        return jdbcTemplate.queryForObject(sql.toString(), params, Long::class.java) ?: 0L
    }

    /**
     * 감사 로그 통합 조회 SQL (3개 원천 UNION ALL)
     */
    private fun unionSql(): String = """
        SELECT
            a.log_at                                     AS ts,
            a.log_type_cd                                AS log_type,
            a.user_id                                    AS emp_no,
            a.dept_nm                                    AS dept_nm,
            coalesce(a.target_desc, a.menu_id)           AS target_desc,
            coalesce(a.remark, a.field_key)              AS detail,
            a.result_cd                                  AS result_cd,
            host(a.ip_addr)                              AS ip_addr
        FROM ax.tb_log_audit a
        WHERE a.log_at >= :from AND a.log_at < :toExclusive
          AND (:type::varchar   IS NULL OR a.log_type_cd = :type)
          AND (:deptNm::varchar IS NULL OR a.dept_nm     = :deptNm)
          AND (:empNo::varchar  IS NULL OR a.user_id     = :empNo)

        UNION ALL

        SELECT
            p.log_at                                     AS ts,
            'PERM_CHANGE'                                AS log_type,
            p.actor_user_id                              AS emp_no,
            p.actor_dept_nm                              AS dept_nm,
            p.target_nm                                  AS target_desc,
            p.detail                                     AS detail,
            'ALLOW'                                      AS result_cd,
            NULL                                         AS ip_addr
        FROM ax.tb_sys_perm_log p
        WHERE p.log_at >= :from AND p.log_at < :toExclusive
          AND (:type::varchar   IS NULL OR :type = 'PERM_CHANGE')
          AND (:deptNm::varchar IS NULL OR p.actor_dept_nm = :deptNm)
          AND (:empNo::varchar  IS NULL OR p.actor_user_id = :empNo)

        UNION ALL

        SELECT
            h.login_at                                   AS ts,
            'LOGIN'                                      AS log_type,
            h.user_id                                    AS emp_no,
            d.dept_nm                                    AS dept_nm,
            '로그인'                                      AS target_desc,
            coalesce(h.fail_reason, h.result_cd)         AS detail,
            CASE WHEN h.result_cd = 'SUCCESS' THEN 'ALLOW' ELSE 'REJECT' END AS result_cd,
            host(h.ip_addr)                              AS ip_addr
        FROM ax.tb_sys_login_hist h
        LEFT JOIN ax.tb_sys_user u ON u.user_id = h.user_id
        LEFT JOIN ax.tb_sys_dept d ON d.dept_id = u.dept_id
        WHERE h.login_at >= :from AND h.login_at < :toExclusive
          AND (:type::varchar   IS NULL OR :type = 'LOGIN')
          AND (:deptNm::varchar IS NULL OR d.dept_nm = :deptNm)
          AND (:empNo::varchar  IS NULL OR h.user_id = :empNo)
    """.trimIndent()

    /** 통합 조회 공통 파라미터 */
    private fun baseParams(
        from: LocalDate,
        to: LocalDate,
        type: String?,
        deptNm: String?,
        empNo: String?
    ): MapSqlParameterSource = MapSqlParameterSource()
        .addValue("from", from.atStartOfDay())
        .addValue("toExclusive", to.plusDays(1).atStartOfDay())
        .addValue("type", type?.trim()?.takeIf { it.isNotBlank() })
        .addValue("deptNm", deptNm?.trim()?.takeIf { it.isNotBlank() })
        .addValue("empNo", empNo?.trim()?.takeIf { it.isNotBlank() })

    /**
     * 권한 변경 이력을 기록한다. (ax.tb_sys_perm_log)
     *
     * @param actCd        SYS_PERM_ACT — ACCOUNT / DEPT / MENU_PERM / DATA_PERM
     * @param targetKindCd 대상 종류
     * @param targetDeptId 대상 부서 ID
     * @param targetUserId 대상 사번
     * @param targetNm     대상 표시명
     * @param detail       변경 내용 (변경 전후)
     */
    fun insertPermLog(
        actCd: String,
        targetKindCd: String,
        targetDeptId: Int?,
        targetUserId: String?,
        targetNm: String,
        detail: String,
        actorUserId: String,
        actorDeptNm: String?
    ): Long {
        val sql = """
            INSERT INTO ax.tb_sys_perm_log (
                log_at, act_cd, target_kind_cd, target_dept_id, target_user_id,
                target_nm, detail, actor_user_id, actor_dept_nm
            ) VALUES (
                now(), :actCd, :targetKindCd, :targetDeptId, :targetUserId,
                :targetNm, :detail, :actorUserId, :actorDeptNm
            )
            RETURNING log_id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("actCd", actCd)
            .addValue("targetKindCd", targetKindCd)
            .addValue("targetDeptId", targetDeptId)
            .addValue("targetUserId", targetUserId)
            .addValue("targetNm", targetNm.take(100))
            .addValue("detail", detail.take(500))
            .addValue("actorUserId", actorUserId)
            .addValue("actorDeptNm", actorDeptNm)

        return jdbcTemplate.queryForObject(sql, params, Long::class.java) ?: 0L
    }

    /**
     * 계정·권한 변경 이력을 조회한다. (No.139)
     */
    fun findPermLogs(
        from: LocalDate,
        to: LocalDate,
        target: String?,
        actType: String?,
        limit: Int?,
        offset: Int,
        keyword: String? = null
    ): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT
                p.log_at, p.act_cd, p.target_kind_cd, p.target_nm, p.detail,
                p.actor_user_id, p.actor_dept_nm, u.user_nm AS actor_nm
            FROM ax.tb_sys_perm_log p
            LEFT JOIN ax.tb_sys_user u ON u.user_id = p.actor_user_id
            LEFT JOIN ax.tb_sys_code ac ON ac.group_cd = 'SYS_PERM_ACT' AND ac.code = p.act_cd
            WHERE p.log_at >= :from AND p.log_at < :toExclusive
            """.trimIndent()
        )

        val params = MapSqlParameterSource()
            .addValue("from", from.atStartOfDay())
            .addValue("toExclusive", to.plusDays(1).atStartOfDay())

        appendPermLogFilters(sql, params, target, actType, keyword)

        sql.append("\nORDER BY p.log_at DESC")

        // limit 이 null 이면 전량(size=0)

        if (limit != null) {

            sql.append("\nLIMIT :limit OFFSET :offset")

            params.addValue("limit", limit).addValue("offset", offset)

        }

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "ts" to Rs.dateTime(rs, "log_at"),
                "target" to rs.getString("target_nm"),
                "targetKind" to rs.getString("target_kind_cd"),
                "actType" to rs.getString("act_cd"),
                "detail" to rs.getString("detail"),
                "by" to (rs.getString("actor_nm") ?: rs.getString("actor_user_id")),
                "byEmpNo" to rs.getString("actor_user_id"),
                "byDept" to rs.getString("actor_dept_nm")
            )
        }
    }

    /** 계정·권한 변경 이력 전체 건수 */
    fun countPermLogs(from: LocalDate, to: LocalDate, target: String?, actType: String?, keyword: String? = null): Long {
        val sql = StringBuilder(
            """
            SELECT count(*)
            FROM ax.tb_sys_perm_log p
            LEFT JOIN ax.tb_sys_user u ON u.user_id = p.actor_user_id
            LEFT JOIN ax.tb_sys_code ac ON ac.group_cd = 'SYS_PERM_ACT' AND ac.code = p.act_cd
            WHERE p.log_at >= :from AND p.log_at < :toExclusive
            """.trimIndent()
        )

        val params = MapSqlParameterSource()
            .addValue("from", from.atStartOfDay())
            .addValue("toExclusive", to.plusDays(1).atStartOfDay())

        appendPermLogFilters(sql, params, target, actType, keyword)

        return jdbcTemplate.queryForObject(sql.toString(), params, Long::class.java) ?: 0L
    }

    /**
     * 변경 이력 공통 조건. `keyword` 는 표의 전 열 검색(2026-09-13 WEB 요청) —
     * 대상·구분 코드/이름·내용·수행자 사번/이름/부서.
     */
    private fun appendPermLogFilters(sql: StringBuilder, params: MapSqlParameterSource, target: String?, actType: String?, keyword: String?) {
        SqlLikeUtils.contains(target)?.let {
            sql.append(" AND p.target_nm LIKE :target ESCAPE '\\'")
            params.addValue("target", it)
        }
        if (!actType.isNullOrBlank()) {
            sql.append(" AND p.act_cd = :actType")
            params.addValue("actType", actType.trim())
        }
        SqlLikeUtils.contains(keyword)?.let {
            sql.append(
                " AND (p.target_nm LIKE :keyword ESCAPE '\\' OR p.detail LIKE :keyword ESCAPE '\\'" +
                    " OR p.act_cd LIKE :keyword ESCAPE '\\' OR coalesce(ac.code_nm, '') LIKE :keyword ESCAPE '\\'" +
                    " OR p.target_kind_cd LIKE :keyword ESCAPE '\\' OR coalesce(p.target_user_id, '') LIKE :keyword ESCAPE '\\'" +
                    " OR p.actor_user_id LIKE :keyword ESCAPE '\\' OR coalesce(u.user_nm, '') LIKE :keyword ESCAPE '\\'" +
                    " OR coalesce(p.actor_dept_nm, '') LIKE :keyword ESCAPE '\\')"
            )
            params.addValue("keyword", it)
        }
    }
}
