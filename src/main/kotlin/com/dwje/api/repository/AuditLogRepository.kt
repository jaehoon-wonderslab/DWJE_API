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
        ipAddr: String?,
        userAgent: String? = null
    ): Long {
        val sql = """
            INSERT INTO ax.tb_log_audit (
                log_at, log_type_cd, user_id, dept_nm, menu_id, field_key,
                target_desc, result_cd, masked_cnt, remark, ip_addr, user_agent
            ) VALUES (
                now(), :logTypeCd, :userId, :deptNm, :menuId, :fieldKey,
                :targetDesc, :resultCd, :maskedCnt, :remark, CAST(:ipAddr AS inet), :userAgent
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
            .addValue("userAgent", userAgent?.take(300))

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
        offset: Int,
        extra: AuditFilter = AuditFilter()
    ): List<Map<String, Any?>> {
        val params = baseParams(from, to, type, deptNm, empNo, extra)
        val sql = StringBuilder(filteredSql(type, extra, params))

        // 같은 시각이면 원천·키 역순 — 문자열 ID 정렬을 대신한다 (09 AUD-05)
        sql.append("\nORDER BY t.ts DESC, t.src, t.key DESC\nLIMIT :limit OFFSET :offset")
        params.addValue("limit", limit).addValue("offset", offset)

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                // 원천 접두어를 붙인 ID — A(감사) · P(권한 변경) · L(로그인) · O(로그아웃)
                "id" to rs.getString("id"),
                "src" to rs.getString("src"),
                "ts" to Rs.dateTime(rs, "ts"),
                "name" to rs.getString("user_nm"),
                "ua" to rs.getString("ua"),
                "type" to rs.getString("log_type"),
                "empNo" to rs.getString("emp_no"),
                "dept" to rs.getString("dept_nm"),
                "menuId" to rs.getString("menu_id"),
                "menuNm" to rs.getString("menu_nm"),
                "fieldKey" to rs.getString("field_key"),
                "maskedCnt" to rs.getInt("masked_cnt"),
                "target" to rs.getString("target_desc"),
                "detail" to rs.getString("detail"),
                "result" to rs.getString("result_cd"),
                "ip" to rs.getString("ip_addr")
            )
        }
    }

    /** 감사 로그 전체 건수 */
    fun countAuditLogs(
        from: LocalDate, to: LocalDate, type: String?, deptNm: String?, empNo: String?, extra: AuditFilter = AuditFilter()
    ): Long {
        val params = baseParams(from, to, type, deptNm, empNo, extra)
        val sql = "SELECT count(*) FROM (${filteredSql(type, extra, params)}) c"
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
     * 감사 로그 추가 조건 (09 기획서 WEB 계약)
     *
     * @param keyword              사번·이름·부서·대상·내용·IP 부분 일치
     * @param ip                   IP 또는 대역(10.0.0.0/8) — IPv4 앞부분(10.1.)만 주면 앞부분 일치(하위 호환)
     * @param result               [com.dwje.api.common.util.AuditResult]
     * @param excludeLoginSuccess  true 면 로그인 성공·로그아웃 행을 뺀다(행 수의 대부분이라 다른 기록이 묻힌다)
     * @param asOf                 이 시각까지의 행만 — 쪽을 넘기는 동안 새 행 때문에 밀리지 않게 (09 AUD-05 G-1)
     */
    data class AuditFilter(
        val keyword: String? = null,
        val ip: String? = null,
        val result: String? = null,
        val excludeLoginSuccess: Boolean = false,
        val asOf: java.time.LocalDateTime? = null
    )

    /** 쉼표로 여러 유형 — 대문자·빈 값 제거 */
    fun typesOf(type: String?): List<String> =
        type?.split(',')?.map { it.trim().uppercase() }?.filter { it.isNotEmpty() }?.distinct().orEmpty()

    /** 통합 행 + 이름·화면명 + 추가 조건 */
    private fun filteredSql(type: String?, extra: AuditFilter, params: MapSqlParameterSource): String {
        val where = StringBuilder("WHERE 1 = 1")
        if (SqlLikeUtils.contains(extra.keyword) != null) {
            where.append(
                " AND (coalesce(t.emp_no,'') LIKE :kw ESCAPE '\\' OR coalesce(u.user_nm,'') LIKE :kw ESCAPE '\\'" +
                    " OR coalesce(t.dept_nm,'') LIKE :kw ESCAPE '\\' OR coalesce(t.target_desc,'') LIKE :kw ESCAPE '\\'" +
                    " OR coalesce(t.detail,'') LIKE :kw ESCAPE '\\' OR coalesce(t.ip_addr,'') LIKE :kw ESCAPE '\\')"
            )
        }
        when (ipMode(extra.ip)) {
            IpMode.PREFIX -> {
                where.append(" AND t.ip_addr LIKE :ipPrefix")
                params.addValue("ipPrefix", extra.ip!!.trim() + "%")
            }
            IpMode.CIDR -> {
                where.append(" AND t.ip_inet <<= CAST(:ipNet AS inet)")
                params.addValue("ipNet", extra.ip!!.trim())
            }
            null -> {}
        }
        if (!extra.result.isNullOrBlank()) where.append(" AND t.result_cd = :result")
        if (extra.excludeLoginSuccess) where.append(" AND NOT (t.log_type = 'LOGIN' AND t.result_cd = 'ALLOW')")
        if (extra.asOf != null) {
            where.append(" AND t.ts <= :asOf")
            params.addValue("asOf", java.sql.Timestamp.valueOf(extra.asOf))
        }
        return """
            SELECT t.*, u.user_nm, m.menu_nm
            FROM (${unionSql(typesOf(type), params)}) t
            LEFT JOIN ax.tb_sys_user u ON u.user_id = t.emp_no
            LEFT JOIN ax.tb_sys_menu m ON m.menu_id = t.menu_id
            $where
        """.trimIndent()
    }

    enum class IpMode { PREFIX, CIDR }

    /** IP 조건 해석 — 빈 값 null, IPv4 앞부분은 앞부분 일치, IPv4·IPv6 주소·대역은 inet 포함 비교, 그 밖은 400 (09 AUD-12) */
    fun ipMode(ip: String?): IpMode? {
        val v = ip?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (IPV4_PARTIAL.matches(v)) return IpMode.PREFIX
        if (IPV4_CIDR.matches(v) && v.substringBefore('/').split('.').all { it.toInt() <= 255 } &&
            (v.substringAfter('/', "32").toIntOrNull() ?: 99) <= 32) return IpMode.CIDR
        if (IPV6_CIDR.matches(v) && (v.substringAfter('/', "128").toIntOrNull() ?: 999) <= 128) return IpMode.CIDR
        throw com.dwje.api.common.exception.InvalidParameterException("IP 주소 형식이 올바르지 않습니다.", "ip")
    }

    /**
     * 네 원천 UNION ALL — 감사(A) · 권한 변경(P, 감사 행과 이어진 것은 뺀다) · 로그인(L) · 로그아웃(O)
     *
     * 유형이 비어 있으면 감사 열람(AUDIT_VIEW)만 뺀다(09 AUD-08). EXPORT 를 고르면 예전 내려받기 기록(RAW_VIEW 「다운로드 형식=」)도 함께 본다(09 AUD-09).
     */
    private fun unionSql(types: List<String>, params: MapSqlParameterSource): String {
        val auditType = when {
            types.isEmpty() -> "a.log_type_cd <> 'AUDIT_VIEW'"
            "EXPORT" in types -> "(a.log_type_cd IN (:types) OR (a.log_type_cd = 'RAW_VIEW' AND a.remark LIKE '다운로드 형식=%'))"
            else -> "a.log_type_cd IN (:types)"
        }
        if (types.isNotEmpty()) params.addValue("types", types)
        val permOn = types.isEmpty() || "PERM_CHANGE" in types
        val loginOn = types.isEmpty() || "LOGIN" in types
        val parts = mutableListOf(
            """
            SELECT
                'A-' || a.audit_id                           AS id,
                'AUDIT'                                      AS src,
                a.audit_id                                   AS key,
                a.user_agent                                 AS ua,
                a.log_at                                     AS ts,
                a.log_type_cd                                AS log_type,
                a.user_id                                    AS emp_no,
                a.dept_nm                                    AS dept_nm,
                a.menu_id                                    AS menu_id,
                a.field_key                                  AS field_key,
                a.masked_cnt                                 AS masked_cnt,
                coalesce(a.target_desc, a.menu_id)           AS target_desc,
                coalesce(a.remark, a.field_key)              AS detail,
                a.result_cd                                  AS result_cd,
                host(a.ip_addr)                              AS ip_addr,
                a.ip_addr                                    AS ip_inet
            FROM ax.tb_log_audit a
            WHERE a.log_at >= :from AND a.log_at < :toExclusive
              AND $auditType
              AND (:deptNm::varchar IS NULL OR a.dept_nm     = :deptNm)
              AND (:empNo::varchar  IS NULL OR a.user_id     = :empNo)
            """.trimIndent()
        )
        if (permOn) parts += """
            SELECT
                'P-' || p.log_id, 'PERM', p.log_id, NULL::varchar, p.log_at, 'PERM_CHANGE',
                p.actor_user_id, p.actor_dept_nm, NULL::varchar, NULL::varchar, 0,
                p.target_nm, p.detail, 'ALLOW', NULL::text, NULL::inet
            FROM ax.tb_sys_perm_log p
            WHERE p.log_at >= :from AND p.log_at < :toExclusive
              AND p.audit_id IS NULL
              AND (:deptNm::varchar IS NULL OR p.actor_dept_nm = :deptNm)
              AND (:empNo::varchar  IS NULL OR p.actor_user_id = :empNo)
            """.trimIndent()
        if (loginOn) {
            parts += """
            SELECT
                'L-' || h.login_id, 'LOGIN', h.login_id, h.user_agent, h.login_at, 'LOGIN',
                h.user_id, coalesce(h.dept_nm, d.dept_nm), NULL::varchar, NULL::varchar, 0,
                '로그인', coalesce(h.fail_reason, h.result_cd),
                CASE WHEN h.result_cd = 'SUCCESS' THEN 'ALLOW' ELSE 'REJECT' END,
                host(h.ip_addr), h.ip_addr
            FROM ax.tb_sys_login_hist h
            LEFT JOIN ax.tb_sys_user u ON u.user_id = h.user_id
            LEFT JOIN ax.tb_sys_dept d ON d.dept_id = u.dept_id
            WHERE h.login_at >= :from AND h.login_at < :toExclusive
              AND (:deptNm::varchar IS NULL OR coalesce(h.dept_nm, d.dept_nm) = :deptNm)
              AND (:empNo::varchar  IS NULL OR h.user_id = :empNo)
            """.trimIndent()
            // 로그아웃 — 로그인 행의 logout_at. IP 는 로그인 때 IP 다 (09 AUD-10)
            parts += """
            SELECT
                'O-' || h.login_id, 'LOGIN', h.login_id, h.user_agent, h.logout_at, 'LOGIN',
                h.user_id, coalesce(h.dept_nm, d.dept_nm), NULL::varchar, NULL::varchar, 0,
                '로그아웃', NULL::varchar, 'ALLOW', host(h.ip_addr), h.ip_addr
            FROM ax.tb_sys_login_hist h
            LEFT JOIN ax.tb_sys_user u ON u.user_id = h.user_id
            LEFT JOIN ax.tb_sys_dept d ON d.dept_id = u.dept_id
            WHERE h.logout_at IS NOT NULL AND h.logout_at >= :from AND h.logout_at < :toExclusive
              AND (:deptNm::varchar IS NULL OR coalesce(h.dept_nm, d.dept_nm) = :deptNm)
              AND (:empNo::varchar  IS NULL OR h.user_id = :empNo)
            """.trimIndent()
        }
        return parts.joinToString("\n\nUNION ALL\n\n")
    }

    /** 통합 조회 공통 파라미터 */
    private fun baseParams(
        from: LocalDate,
        to: LocalDate,
        @Suppress("UNUSED_PARAMETER") type: String?,
        deptNm: String?,
        empNo: String?,
        extra: AuditFilter = AuditFilter()
    ): MapSqlParameterSource = MapSqlParameterSource()
        .addValue("from", from.atStartOfDay())
        .addValue("toExclusive", to.plusDays(1).atStartOfDay())
        .addValue("deptNm", deptNm?.trim()?.takeIf { it.isNotBlank() })
        .addValue("empNo", empNo?.trim()?.takeIf { it.isNotBlank() })
        .addValue("kw", SqlLikeUtils.contains(extra.keyword))
        .addValue("result", extra.result?.trim()?.uppercase()?.takeIf { it.isNotBlank() })

    companion object {
        private val IPV4_PARTIAL = Regex("""^\d{1,3}(\.\d{1,3}){0,2}\.?$""")
        private val IPV4_CIDR = Regex("""^\d{1,3}(\.\d{1,3}){3}(/\d{1,2})?$""")
        private val IPV6_CIDR = Regex("""^[0-9A-Fa-f:]*:[0-9A-Fa-f:.]*(/\d{1,3})?$""")
    }

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
        actorDeptNm: String?,
        auditId: Long? = null
    ): Long {
        // audit_id — 같은 사건의 감사 행. 원본 표는 UPDATE 를 막으므로(V51) INSERT 때 넣는다 (09 AUD-06)
        val sql = """
            INSERT INTO ax.tb_sys_perm_log (
                log_at, act_cd, target_kind_cd, target_dept_id, target_user_id,
                target_nm, detail, actor_user_id, actor_dept_nm, audit_id
            ) VALUES (
                now(), :actCd, :targetKindCd, :targetDeptId, :targetUserId,
                :targetNm, :detail, :actorUserId, :actorDeptNm, :auditId
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
            .addValue("auditId", auditId)

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
        keyword: String? = null,
        targetUserId: String? = null,
        gwDeptOnly: Boolean = false
    ): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT
                p.log_at, p.act_cd, ac.code_nm AS act_nm, p.target_kind_cd, p.target_nm, p.detail,
                p.target_user_id, p.target_dept_id,
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

        appendPermLogFilters(sql, params, target, actType, keyword, targetUserId, gwDeptOnly)

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
                // 구분 코드명(SYS_PERM_ACT) · 대상 사번·부서 (01 ACC-09)
                "actNm" to rs.getString("act_nm"),
                "targetUserId" to rs.getString("target_user_id"),
                "targetDeptId" to Rs.intOrNull(rs, "target_dept_id"),
                "detail" to rs.getString("detail"),
                "by" to (rs.getString("actor_nm") ?: rs.getString("actor_user_id")),
                "byEmpNo" to rs.getString("actor_user_id"),
                "byDept" to rs.getString("actor_dept_nm")
            )
        }
    }

    /** 계정·권한 변경 이력 전체 건수 */
    fun countPermLogs(
        from: LocalDate, to: LocalDate, target: String?, actType: String?, keyword: String? = null,
        targetUserId: String? = null, gwDeptOnly: Boolean = false
    ): Long {
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

        appendPermLogFilters(sql, params, target, actType, keyword, targetUserId, gwDeptOnly)

        return jdbcTemplate.queryForObject(sql.toString(), params, Long::class.java) ?: 0L
    }

    /**
     * 변경 이력 공통 조건. `keyword` 는 표의 전 열 검색(2026-09-13 WEB 요청) —
     * 대상·구분 코드/이름·내용·수행자 사번/이름/부서.
     */
    /**
     * @param actType      구분 코드 — 쉼표로 여러 개(MENU_PERM,USER_MENU_PERM) (03 MNP-07)
     * @param targetUserId 대상 사번 정확 일치 — 인덱스 ix_sys_perm_log_user 를 탄다 (01 ACC-09)
     * @param gwDeptOnly   그룹웨어 부서 매핑 화면 권한만 있는 조회 — 매핑 변경과 자동 가입 계정의 부서 이동만 (01 ACC-09)
     */
    private fun appendPermLogFilters(
        sql: StringBuilder, params: MapSqlParameterSource, target: String?, actType: String?, keyword: String?,
        targetUserId: String? = null, gwDeptOnly: Boolean = false
    ) {
        SqlLikeUtils.contains(target)?.let {
            sql.append(" AND p.target_nm LIKE :target ESCAPE '\\'")
            params.addValue("target", it)
        }
        val acts = actType?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct().orEmpty()
        if (acts.isNotEmpty()) {
            sql.append(" AND p.act_cd = ANY(:actTypes)")
            params.addValue("actTypes", acts.toTypedArray())
        }
        targetUserId?.trim()?.takeIf { it.isNotEmpty() }?.let {
            sql.append(" AND p.target_user_id = :targetUserId")
            params.addValue("targetUserId", it)
        }
        // 이동 전 부서가 이력에 없어 「미배정 계정의 이동」 은 자동 가입 계정의 부서 이동으로 근사한다(AUD-06 의 audit_id 연결 뒤 menu_id 로 바꾼다)
        if (gwDeptOnly) {
            sql.append(
                " AND (p.act_cd = 'GW_DEPT_MAP' OR (p.act_cd = 'ACCOUNT' AND p.detail LIKE '부서 이동 →%' AND EXISTS (" +
                    "SELECT 1 FROM ax.tb_sys_user t WHERE t.user_id = p.target_user_id AND t.ins_user = 'SYSTEM' " +
                    "AND t.remark LIKE '그룹웨어 자동 가입%')))"
            )
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
