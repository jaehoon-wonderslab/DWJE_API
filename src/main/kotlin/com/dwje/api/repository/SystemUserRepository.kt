package com.dwje.api.repository

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.util.Rs
import com.dwje.api.common.util.SqlLikeUtils
import com.dwje.api.service.EmailVerificationService
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

/**
 * 계정 · 부서 · 권한 관리 Repository (SY-01, SY-02, SY-03)
 *
 * 참조 테이블 : ax.tb_sys_user, ax.tb_sys_dept, ax.tb_sys_login_hist,
 *              ax.tb_sys_menu, ax.tb_sys_menu_group, ax.tb_sys_dept_menu_perm,
 *              ax.tb_sys_data_field, ax.tb_sys_data_field_column, ax.tb_sys_dept_data_perm
 */
/**
 * 가입 경로 계산식 (01 ACC-08) — 컬럼이 아니라 등록자·비고로 판정한다.
 * 회원가입은 본인 사번으로 등록된다(AuthRepository 가입 INSERT) — 문서의 `ins_user IS NULL` 만 보면 가입 계정이 모두 ADMIN 이 된다.
 */
private const val JOIN_SRC_SQL = "CASE WHEN u.ins_user = 'SYSTEM' AND u.remark LIKE '그룹웨어 자동 가입%' THEN 'GROUPWARE' " +
    "WHEN u.ins_user IS NULL OR u.ins_user = u.user_id THEN 'SIGNUP' ELSE 'ADMIN' END"

@Repository
class SystemUserRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) {

    // =================================================================================
    // SY-01. 계정 관리
    // =================================================================================

    /**
     * 계정 관리 요약을 조회한다. (No.127)
     */
    fun findAccountSummary(): Map<String, Any?> {
        val sql = """
            SELECT
                count(*) FILTER (WHERE u.user_state_cd = 'ACTIVE')    AS active_cnt,
                count(*) FILTER (WHERE u.user_state_cd = 'SUSPENDED') AS suspended_cnt,
                count(*) FILTER (WHERE u.user_state_cd = 'PENDING')   AS pending_cnt,
                count(*) FILTER (WHERE u.user_state_cd = 'LOCKED')    AS locked_cnt,
                count(*) FILTER (WHERE u.pwd_change_req_yn = 'Y')     AS pwd_change_req_cnt,
                count(*) FILTER (WHERE u.is_switch_target)            AS switchable_cnt,
                (SELECT count(*) FROM ax.tb_sys_dept WHERE use_flg = 'Y') AS dept_cnt
            FROM ax.tb_sys_user u
        """.trimIndent()

        return jdbcTemplate.queryForObject(sql, MapSqlParameterSource()) { rs, _ ->
            mapOf(
                "userCnt" to mapOf(
                    "active" to rs.getLong("active_cnt"),
                    "suspended" to rs.getLong("suspended_cnt"),
                    // 승인 대기 전체 건수 — 화면 상단 표기. 검색 결과의 meta.total 과 분리해 검색 중에도 바뀌지 않는다.
                    "pending" to rs.getLong("pending_cnt"),
                    // 로그인 연속 실패로 잠긴 계정 (R-02, 01 ACC-05)
                    "locked" to rs.getLong("locked_cnt")
                ),
                // 초기 비밀번호를 아직 바꾸지 않은 계정 (R-04, 01 ACC-03)
                "pwdChangeRequiredCnt" to rs.getLong("pwd_change_req_cnt"),
                "deptCnt" to rs.getLong("dept_cnt"),
                "switchableCnt" to rs.getLong("switchable_cnt")
            )
        } ?: emptyMap()
    }

    /**
     * 계정 목록을 조회한다. (No.128)
     *
     * @param keyword 사번·이름 검색어
     * @param deptId  부서 ID
     * @param state   계정 상태 (ACTIVE/SUSPENDED)
     */
    fun findUsers(
        keyword: String?,
        deptId: Int?,
        state: String?,
        switchable: Boolean?,
        limit: Int?,
        offset: Int,
        joinSrc: String? = null
    ): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT
                u.user_id, u.user_nm, u.dept_id, d.dept_nm,
                u.position_cd, pc.code_nm AS position_nm,
                u.user_state_cd, sc.code_nm AS state_nm,
                u.is_switch_target, u.plant_cd, u.last_login_at, u.login_fail_cnt, u.remark,
                u.email, u.pwd_change_req_yn,
                $JOIN_SRC_SQL AS join_src,
                to_char(u.ins_date AT TIME ZONE 'Asia/Seoul', 'YYYY-MM-DD HH24:MI') AS requested_at,
                -- 잠긴 시각 = 로그인 이력의 「연속 실패 N회 잠금」 최신 1건 (09 AUD-16, 01 ACC-05)
                CASE WHEN u.user_state_cd = 'LOCKED' THEN (
                    SELECT max(h.login_at) FROM ax.tb_sys_login_hist h
                     WHERE h.user_id = u.user_id AND h.result_cd = 'LOCKED' AND h.fail_reason LIKE '연속 실패%'
                ) END AS locked_at,
                ${stateReasonSql()} AS state_reason
            FROM ax.tb_sys_user u
            INNER JOIN ax.tb_sys_dept d  ON d.dept_id  = u.dept_id
            LEFT  JOIN ax.tb_sys_code pc ON pc.group_cd = 'SYS_POSITION'   AND pc.code = u.position_cd
            LEFT  JOIN ax.tb_sys_code sc ON sc.group_cd = 'SYS_USER_STATE' AND sc.code = u.user_state_cd
            WHERE 1 = 1
            """.trimIndent()
        )

        val params = MapSqlParameterSource()
        appendUserFilters(sql, params, keyword, deptId, state, switchable, joinSrc)

        sql.append("\nORDER BY d.sort_seq, u.user_nm")
        // limit 이 null 이면 전량(size=0) — 화면이 Tabulator 열 필터를 전체 결과에 걸 때 쓴다.
        if (limit != null) {
            sql.append("\nLIMIT :limit OFFSET :offset")
            params.addValue("limit", limit).addValue("offset", offset)
        }

        return jdbcTemplate.query(sql.toString(), params) { rs, _ -> userRow(rs) }
    }

    /**
     * 정지 사유(계산값, 01 ACC-05) — SUSPENDED 일 때만. 우선순위 RETIRED > REJECTED > ADMIN.
     * RETIRED 는 그룹웨어 인사 원천 표가 있을 때만 본다(없는 환경에서 SQL 이 깨지지 않게 조건부로 조립한다).
     */
    private fun stateReasonSql(): String {
        val retired = if (groupwareSourceExists()) {
            """WHEN (SELECT l.is_retired FROM groupware_user.tb_user_list l
                         WHERE l.empno = u.user_id ORDER BY l.is_retired, l.id LIMIT 1) THEN 'RETIRED'"""
        } else ""
        return """
            CASE WHEN u.user_state_cd <> 'SUSPENDED' THEN NULL
                 $retired
                 WHEN (SELECT pl.detail FROM ax.tb_sys_perm_log pl WHERE pl.target_user_id = u.user_id
                        ORDER BY pl.log_at DESC LIMIT 1) LIKE '회원가입 반려%' THEN 'REJECTED'
                 ELSE 'ADMIN' END
        """.trimIndent()
    }

    /** 그룹웨어 인사 원천 표(groupware_user.tb_user_list)가 있는지 */
    fun groupwareSourceExists(): Boolean =
        jdbcTemplate.queryForObject(
            "SELECT to_regclass('groupware_user.tb_user_list') IS NOT NULL", MapSqlParameterSource(), Boolean::class.java
        ) ?: false

    /** 사번 정확 일치 1건 — 승인·반려 대상 찾기(목록 검색은 부분 일치라 `1001` 이 `11001` 을 집을 수 있다, 01 ACC-01) */
    fun findUserByEmpNo(empNo: String): Map<String, Any?>? {
        val sql = """
            SELECT
                u.user_id, u.user_nm, u.dept_id, d.dept_nm,
                u.position_cd, pc.code_nm AS position_nm,
                u.user_state_cd, sc.code_nm AS state_nm,
                u.is_switch_target, u.plant_cd, u.last_login_at, u.login_fail_cnt, u.remark,
                u.email, u.pwd_change_req_yn, NULL::timestamptz AS locked_at,
                $JOIN_SRC_SQL AS join_src,
                to_char(u.ins_date AT TIME ZONE 'Asia/Seoul', 'YYYY-MM-DD HH24:MI') AS requested_at,
                ${stateReasonSql()} AS state_reason
            FROM ax.tb_sys_user u
            INNER JOIN ax.tb_sys_dept d  ON d.dept_id  = u.dept_id
            LEFT  JOIN ax.tb_sys_code pc ON pc.group_cd = 'SYS_POSITION'   AND pc.code = u.position_cd
            LEFT  JOIN ax.tb_sys_code sc ON sc.group_cd = 'SYS_USER_STATE' AND sc.code = u.user_state_cd
            WHERE u.user_id = :empNo
        """.trimIndent()
        return jdbcTemplate.query(sql, MapSqlParameterSource("empNo", empNo)) { rs, _ -> userRow(rs) }.firstOrNull()
    }

    /** 승인 대기 → 새 상태. 조건부라 두 관리자가 동시에 처리하면 한 쪽만 1행이다 */
    fun approvePending(empNo: String, newState: String, actor: String): Int =
        jdbcTemplate.update(
            """
            UPDATE ax.tb_sys_user SET user_state_cd = :state, upd_date = now(), upd_user = :actor
             WHERE user_id = :empNo AND user_state_cd = 'PENDING'
            """.trimIndent(),
            MapSqlParameterSource().addValue("empNo", empNo).addValue("state", newState).addValue("actor", actor)
        )

    /** 비고(remark)에 한 줄 덧붙인다 — 컬럼 길이(500)를 넘으면 앞쪽(오래된 줄)을 잘라 낸다 */
    fun appendRemark(empNo: String, line: String, actor: String): Int =
        jdbcTemplate.update(
            """
            UPDATE ax.tb_sys_user
               SET remark = right(concat_ws(E'\n', nullif(remark, ''), :line), 500), upd_date = now(), upd_user = :actor
             WHERE user_id = :empNo
            """.trimIndent(),
            MapSqlParameterSource().addValue("empNo", empNo).addValue("line", line).addValue("actor", actor)
        )

    private fun userRow(rs: java.sql.ResultSet): Map<String, Any?> =
            mapOf(
                "empNo" to rs.getString("user_id"),
                "name" to rs.getString("user_nm"),
                "deptId" to rs.getInt("dept_id"),
                "dept" to rs.getString("dept_nm"),
                "pos" to rs.getString("position_cd"),
                "posNm" to rs.getString("position_nm"),
                "state" to rs.getString("user_state_cd"),
                "stateNm" to rs.getString("state_nm"),
                "demo" to rs.getBoolean("is_switch_target"),
                "plantCd" to rs.getString("plant_cd"),
                "lastLoginAt" to Rs.dateTime(rs, "last_login_at"),
                "loginFailCnt" to rs.getInt("login_fail_cnt"),
                "remark" to rs.getString("remark"),
                "pwdChangeRequired" to (rs.getString("pwd_change_req_yn") == "Y"),
                "lockedAt" to Rs.dateTime(rs, "locked_at"),
                // 이메일 — 계정 관리 목록 「이메일」 열(2026-10-03). 이 행은 계정 관리(sys-user) 권한 API 에서만 나간다.
                // 가린 값(emailMasked)은 잠금 해제 · 가입 신청 안내가 그대로 쓴다
                "email" to rs.getString("email")?.takeIf { it.isNotBlank() },
                "emailMasked" to rs.getString("email")?.takeIf { it.isNotBlank() }?.let { EmailVerificationService.maskEmailOf(it) },
                // 정지 사유 — RETIRED(그룹웨어 퇴직) · REJECTED(가입 반려) · ADMIN(관리자 정지). 정지가 아니면 null
                "stateReason" to rs.getString("state_reason"),
                // 가입 경로 — GROUPWARE(그룹웨어 자동 가입) · SIGNUP(본인 가입 신청) · ADMIN(관리자 등록) (01 ACC-08)
                "joinSrc" to rs.getString("join_src"),
                // 등록 시각 — 승인 대기 목록의 「신청 시각」 (01 ACC-07)
                "requestedAt" to rs.getString("requested_at")
            )

    /** 계정 목록 전체 건수 */
    fun countUsers(keyword: String?, deptId: Int?, state: String?, switchable: Boolean?, joinSrc: String? = null): Long {
        val sql = StringBuilder(
            """
            SELECT count(*)
            FROM ax.tb_sys_user u
            INNER JOIN ax.tb_sys_dept d  ON d.dept_id  = u.dept_id
            LEFT  JOIN ax.tb_sys_code pc ON pc.group_cd = 'SYS_POSITION'   AND pc.code = u.position_cd
            LEFT  JOIN ax.tb_sys_code sc ON sc.group_cd = 'SYS_USER_STATE' AND sc.code = u.user_state_cd
            WHERE 1 = 1
            """.trimIndent()
        )

        val params = MapSqlParameterSource()
        appendUserFilters(sql, params, keyword, deptId, state, switchable, joinSrc)

        return jdbcTemplate.queryForObject(sql.toString(), params, Long::class.java) ?: 0L
    }

    /**
     * 계정 목록/건수 공통 동적 조건
     *
     * `keyword` 는 화면 표의 **전 열** 검색이다(2026-09-13 WEB 요청) — 사번·이름·부서명·직급 코드/이름·상태 코드/이름·마지막 접속 시각.
     */
    private fun appendUserFilters(
        sql: StringBuilder,
        params: MapSqlParameterSource,
        keyword: String?,
        deptId: Int?,
        state: String?,
        switchable: Boolean?,
        joinSrc: String? = null
    ) {
        SqlLikeUtils.contains(keyword)?.let {
            sql.append(
                " AND (u.user_id LIKE :keyword ESCAPE '\\' OR u.user_nm LIKE :keyword ESCAPE '\\'" +
                    " OR d.dept_nm LIKE :keyword ESCAPE '\\'" +
                    " OR u.position_cd LIKE :keyword ESCAPE '\\' OR coalesce(pc.code_nm, '') LIKE :keyword ESCAPE '\\'" +
                    " OR u.user_state_cd LIKE :keyword ESCAPE '\\' OR coalesce(sc.code_nm, '') LIKE :keyword ESCAPE '\\'" +
                    " OR coalesce(to_char(u.last_login_at, 'YYYY-MM-DD HH24:MI'), '') LIKE :keyword ESCAPE '\\')"
            )
            params.addValue("keyword", it)
        }
        if (deptId != null) {
            sql.append(" AND u.dept_id = :deptId")
            params.addValue("deptId", deptId)
        }
        // 상태는 쉼표로 여러 개 — LOCKED,SUSPENDED (01 ACC-08). 모르는 값이 하나라도 있으면 400
        val states = state?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.map { normalizeUserState(it) }?.distinct()
        if (!states.isNullOrEmpty()) {
            sql.append(" AND u.user_state_cd = ANY(:states)")
            params.addValue("states", states.toTypedArray())
        }
        if (joinSrc != null) {
            sql.append(" AND ($JOIN_SRC_SQL) = :joinSrc")
            params.addValue("joinSrc", joinSrc)
        }
        if (switchable != null) {
            sql.append(" AND u.is_switch_target = :switchable")
            params.addValue("switchable", switchable)
        }
    }

    /**
     * 계정을 등록한다. (No.129)
     *
     * 관리자가 만든 계정은 첫 로그인 때 비밀번호를 바꿔야 한다 — `pwd_change_req_yn='Y'` (R-04, 01 ACC-03).
     */
    fun insertUser(
        empNo: String,
        name: String,
        deptId: Int,
        positionCd: String,
        stateCd: String,
        switchable: Boolean,
        plantCd: String?,
        pwdHash: String?,
        actor: String
    ): Int {
        val sql = """
            INSERT INTO ax.tb_sys_user (
                user_id, user_nm, dept_id, plant_cd, position_cd, user_state_cd,
                is_switch_target, pwd_hash, pwd_upd_at, pwd_change_req_yn, ins_user, upd_user
            ) VALUES (
                :empNo, :name, :deptId, :plantCd, :positionCd, :stateCd,
                :switchable, :pwdHash, now(), 'Y', :actor, :actor
            )
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("empNo", empNo)
            .addValue("name", name.take(50))
            .addValue("deptId", deptId)
            .addValue("plantCd", plantCd)
            .addValue("positionCd", positionCd)
            .addValue("stateCd", stateCd)
            .addValue("switchable", switchable)
            .addValue("pwdHash", pwdHash)
            .addValue("actor", actor)

        return jdbcTemplate.update(sql, params)
    }

    /**
     * 계정을 수정한다. (No.130)
     */
    fun updateUser(
        empNo: String,
        name: String?,
        deptId: Int?,
        positionCd: String?,
        stateCd: String?,
        switchable: Boolean?,
        actor: String
    ): Int {
        val sql = """
            UPDATE ax.tb_sys_user
               SET user_nm          = coalesce(:name, user_nm),
                   dept_id          = coalesce(:deptId, dept_id),
                   position_cd      = coalesce(:positionCd, position_cd),
                   user_state_cd    = coalesce(:stateCd, user_state_cd),
                   is_switch_target = coalesce(:switchable, is_switch_target),
                   upd_date         = now(),
                   upd_user         = :actor
             WHERE user_id = :empNo
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("empNo", empNo)
            .addValue("name", name?.take(50))
            .addValue("deptId", deptId)
            .addValue("positionCd", positionCd)
            .addValue("stateCd", stateCd)
            .addValue("switchable", switchable)
            .addValue("actor", actor)

        return jdbcTemplate.update(sql, params)
    }

    /**
     * 계정을 삭제한다. (No.131)
     */
    /**
     * 계정 삭제 전 참조 건수 (01 ACC-11) — 막는 참조(서빙 프로필 활성화자·문서 작성자, FK RESTRICT)와
     * 함께 지워지는 참조(알림 수신자·추가 허용 화면·화면 사용 횟수, FK CASCADE). 가입 경로도 함께.
     */
    fun findUserDeleteRefs(empNo: String): Map<String, Any?> =
        jdbcTemplate.queryForObject(
            """
            SELECT (SELECT count(*) FROM ax.tb_ai_serving_profile p WHERE p.activated_by = :empNo) AS serving_cnt,
                   (SELECT count(*) FROM vec.tb_doc d WHERE d.author_user_id = :empNo)            AS doc_cnt,
                   (SELECT count(*) FROM ax.tb_alm_recipient r WHERE r.user_id = :empNo)          AS recipient_cnt,
                   (SELECT count(*) FROM ax.tb_sys_user_menu_grant g WHERE g.user_id = :empNo)    AS grant_cnt,
                   (SELECT count(*) FROM ax.tb_rpt_usage x WHERE x.user_id = :empNo)              AS usage_cnt,
                   (SELECT $JOIN_SRC_SQL FROM ax.tb_sys_user u WHERE u.user_id = :empNo)          AS join_src
            """.trimIndent(),
            MapSqlParameterSource("empNo", empNo)
        ) { rs, _ ->
            mapOf(
                "servingProfiles" to rs.getLong("serving_cnt"), "docs" to rs.getLong("doc_cnt"),
                "recipients" to rs.getLong("recipient_cnt"), "menuGrants" to rs.getLong("grant_cnt"),
                "usage" to rs.getLong("usage_cnt"), "joinSrc" to rs.getString("join_src")
            )
        } ?: emptyMap()

    /** 인증 메일 마지막 발송 실패 시각 (R-17) — 없으면 null */
    fun findMailLastFailAt(): String? =
        jdbcTemplate.query(
            "SELECT max(ins_date) AS at FROM ax.tb_sys_email_verify WHERE send_result_cd = 'FAIL'", MapSqlParameterSource()
        ) { rs, _ -> Rs.dateTime(rs, "at") }.firstOrNull()

    fun deleteUser(empNo: String): Int =
        jdbcTemplate.update(
            "DELETE FROM ax.tb_sys_user WHERE user_id = :empNo",
            MapSqlParameterSource("empNo", empNo)
        )

    /**
     * 계정의 부서를 이동한다. (No.133)
     *
     * 부서 이동 시 메뉴/데이터 권한은 새 부서 권한을 상속한다.
     */
    fun updateUserDept(empNo: String, deptId: Int, actor: String): Int {
        val sql = """
            UPDATE ax.tb_sys_user
               SET dept_id  = :deptId,
                   upd_date = now(),
                   upd_user = :actor
             WHERE user_id = :empNo
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("empNo", empNo)
            .addValue("deptId", deptId)
            .addValue("actor", actor)

        return jdbcTemplate.update(sql, params)
    }

    /** 계정 존재 여부 확인 */
    fun existsUser(empNo: String): Boolean {
        val sql = "SELECT count(*) FROM ax.tb_sys_user WHERE user_id = :empNo"
        return (jdbcTemplate.queryForObject(sql, MapSqlParameterSource("empNo", empNo), Long::class.java) ?: 0L) > 0
    }

    /** 계정의 현재 부서 ID (없는 계정이면 null) */
    fun findUserDeptId(empNo: String): Int? =
        jdbcTemplate.query(
            "SELECT dept_id FROM ax.tb_sys_user WHERE user_id = :empNo",
            MapSqlParameterSource("empNo", empNo)
        ) { rs, _ -> rs.getInt("dept_id") }.firstOrNull()

    /** 계정의 현재 상태 코드 (없는 계정이면 null) */
    fun findUserStateCd(empNo: String): String? =
        jdbcTemplate.query(
            "SELECT user_state_cd FROM ax.tb_sys_user WHERE user_id = :empNo",
            MapSqlParameterSource("empNo", empNo)
        ) { rs, _ -> rs.getString("user_state_cd") }.firstOrNull()

    /**
     * 관리자 잠금 해제 — LOCKED 인 계정만 ACTIVE 로 바꾸고 실패 횟수를 0, 비밀번호 변경 요구를 Y 로 둔다 (01 ACC-05).
     * 5회 실패는 대입 시도일 수 있으므로 해제 후 첫 로그인에서 비밀번호를 바꾸게 한다.
     *
     * @return 실제로 푼 건수 (0 이면 그사이 본인 해제 등으로 이미 풀림)
     */
    fun unlockUserByAdmin(empNo: String, actor: String): Int =
        jdbcTemplate.update(
            """
            UPDATE ax.tb_sys_user
               SET user_state_cd = 'ACTIVE', login_fail_cnt = 0, pwd_change_req_yn = 'Y',
                   upd_date = now(), upd_user = :actor
             WHERE user_id = :empNo AND user_state_cd = 'LOCKED'
            """.trimIndent(),
            MapSqlParameterSource().addValue("empNo", empNo).addValue("actor", actor)
        )

    /** 로그인 실패 횟수를 0 으로 — 정지 해제(SUSPENDED → ACTIVE) 때 정지 중 쌓인 값을 정리한다 (01 ACC-05) */
    fun resetLoginFailCount(empNo: String, actor: String): Int =
        jdbcTemplate.update(
            "UPDATE ax.tb_sys_user SET login_fail_cnt = 0, upd_date = now(), upd_user = :actor WHERE user_id = :empNo",
            MapSqlParameterSource().addValue("empNo", empNo).addValue("actor", actor)
        )

    /**
     * 초기 비밀번호 변경 요구(`pwd_change_req_yn`) — 관리자가 비밀번호를 바꿔 주면 Y (R-04, 01 ACC-03).
     */
    fun updatePwdChangeRequired(empNo: String, required: Boolean, actor: String): Int =
        jdbcTemplate.update(
            "UPDATE ax.tb_sys_user SET pwd_change_req_yn = :yn, upd_date = now(), upd_user = :actor WHERE user_id = :empNo",
            MapSqlParameterSource().addValue("empNo", empNo).addValue("yn", if (required) "Y" else "N").addValue("actor", actor)
        )

    /** 이름으로 부서 ID 를 찾는다 (그룹웨어 자동 가입의 미배정 부서) */
    fun findDeptIdByName(deptNm: String): Int? =
        jdbcTemplate.query(
            "SELECT dept_id FROM ax.tb_sys_dept WHERE dept_nm = :deptNm",
            MapSqlParameterSource("deptNm", deptNm)
        ) { rs, _ -> rs.getInt("dept_id") }.firstOrNull()

    // =================================================================================
    // 부서 관리
    // =================================================================================

    /**
     * 부서 목록을 조회한다. (No.135 / No.134 / No.144)
     *
     * 부서별 소속 계정 수와 메뉴/데이터 권한 수를 함께 집계한다.
     */
    fun findDepts(): List<Map<String, Any?>> = findDepts(null, null, 0)

    /** 부서 건수 — 키워드(부서명·설명) */
    fun countDepts(keyword: String?): Long {
        val sql = StringBuilder("SELECT count(*) FROM ax.tb_sys_dept d WHERE d.use_flg = 'Y'")
        val params = MapSqlParameterSource()
        appendDeptKeyword(sql, params, keyword)
        return jdbcTemplate.queryForObject(sql.toString(), params, Long::class.java) ?: 0L
    }

    private fun appendDeptKeyword(sql: StringBuilder, params: MapSqlParameterSource, keyword: String?) {
        SqlLikeUtils.contains(keyword)?.let {
            sql.append(
                " AND (d.dept_nm LIKE :keyword ESCAPE '\\'" +
                    " OR coalesce(d.dept_desc, '') LIKE :keyword ESCAPE '\\')"
            )
            params.addValue("keyword", it)
        }
    }

    /**
     * 부서 목록 — `keyword`(부서명·설명) 와 쪽 나눔. `limit` 이 null 이면 전량(기존 호출).
     */
    fun findDepts(keyword: String?, limit: Int?, offset: Int): List<Map<String, Any?>> {
        val sql = StringBuilder("""
            SELECT
                d.dept_id,
                d.dept_nm,
                d.dept_desc,
                d.plant_cd,
                d.is_super_admin,
                d.sort_seq,
                (SELECT count(*) FROM ax.tb_sys_user u
                  WHERE u.dept_id = d.dept_id)                                        AS user_cnt,
                (SELECT count(*) FROM ax.tb_sys_dept_menu_perm p
                   JOIN ax.tb_sys_menu m ON m.menu_id = p.menu_id AND m.use_flg = 'Y'
                   JOIN ax.tb_sys_menu_group g ON g.group_id = m.group_id AND g.use_flg = 'Y'
                  WHERE p.dept_id = d.dept_id AND p.can_read)                         AS menu_cnt,
                (SELECT count(*) FROM ax.tb_sys_dept_data_perm p
                  WHERE p.dept_id = d.dept_id AND p.is_allowed)                       AS data_cnt
            FROM ax.tb_sys_dept d
            WHERE d.use_flg = 'Y'
        """.trimIndent())
        val params = MapSqlParameterSource()
        appendDeptKeyword(sql, params, keyword)
        sql.append("\nORDER BY d.sort_seq, d.dept_nm")
        if (limit != null) {
            sql.append("\nLIMIT :limit OFFSET :offset")
            params.addValue("limit", limit).addValue("offset", offset)
        }

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            val superAdmin = rs.getBoolean("is_super_admin")
            mapOf(
                "deptId" to rs.getInt("dept_id"),
                "deptNm" to rs.getString("dept_nm"),
                "desc" to rs.getString("dept_desc"),
                "plantCd" to rs.getString("plant_cd"),
                "superAdmin" to superAdmin,
                "userCnt" to rs.getLong("user_cnt"),
                // 통합관리자 부서는 전 권한을 보유하므로 개별 권한 행을 세지 않는다.
                "menuCnt" to if (superAdmin) null else rs.getLong("menu_cnt"),
                "dataCnt" to if (superAdmin) null else rs.getLong("data_cnt")
            )
        }
    }

    /**
     * 부서를 등록한다. (No.136)
     *
     * @return 생성된 부서 ID
     */
    fun insertDept(deptNm: String, desc: String?, plantCd: String?, actor: String): Int {
        val sql = """
            INSERT INTO ax.tb_sys_dept (dept_nm, dept_desc, plant_cd, use_flg, ins_user, upd_user)
            VALUES (:deptNm, :desc, :plantCd, 'Y', :actor, :actor)
            RETURNING dept_id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("deptNm", deptNm.take(50))
            .addValue("desc", desc?.take(200))
            .addValue("plantCd", plantCd)
            .addValue("actor", actor)

        return jdbcTemplate.queryForObject(sql, params, Int::class.java) ?: 0
    }

    /**
     * 부서를 수정한다. (No.137)
     */
    fun updateDept(deptId: Int, deptNm: String?, desc: String?, actor: String): Int {
        val sql = """
            UPDATE ax.tb_sys_dept
               SET dept_nm   = coalesce(:deptNm, dept_nm),
                   dept_desc = coalesce(:desc, dept_desc),
                   upd_date  = now(),
                   upd_user  = :actor
             WHERE dept_id = :deptId
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("deptId", deptId)
            .addValue("deptNm", deptNm?.take(50))
            .addValue("desc", desc?.take(200))
            .addValue("actor", actor)

        return jdbcTemplate.update(sql, params)
    }

    /**
     * 부서를 삭제한다. (No.138)
     */
    fun deleteDept(deptId: Int): Int =
        jdbcTemplate.update(
            "DELETE FROM ax.tb_sys_dept WHERE dept_id = :deptId",
            MapSqlParameterSource("deptId", deptId)
        )

    /** 부서 단건 조회 */
    fun findDept(deptId: Int): Map<String, Any?>? {
        val sql = """
            SELECT dept_id, dept_nm, dept_desc, plant_cd, is_super_admin, use_flg
            FROM ax.tb_sys_dept
            WHERE dept_id = :deptId
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource("deptId", deptId)) { rs, _ ->
            mapOf(
                "deptId" to rs.getInt("dept_id"),
                "deptNm" to rs.getString("dept_nm"),
                "desc" to rs.getString("dept_desc"),
                "plantCd" to rs.getString("plant_cd"),
                "superAdmin" to rs.getBoolean("is_super_admin"),
                "useFlg" to rs.getString("use_flg")
            )
        }.firstOrNull()
    }

    /**
     * 부서를 참조하는 행 수 — 삭제 전 확인(01 ACC-04). 이 표들은 FK 가 CASCADE 가 아니라 남아 있으면 삭제가 500 으로 깨진다.
     */
    fun countDeptRefs(deptId: Int): Map<String, Long> {
        val sql = """
            SELECT
              (SELECT count(*) FROM ax.tb_sys_user        WHERE dept_id       = :d) AS users,
              (SELECT count(*) FROM ax.tb_sys_dept_gw_map WHERE dept_id       = :d) AS gw_dept_maps,
              (SELECT count(*) FROM ax.tb_alm_recip_group WHERE dept_id       = :d) AS alert_recip_groups,
              (SELECT count(*) FROM vec.tb_doc            WHERE owner_dept_id = :d) AS docs,
              (SELECT count(*) FROM ax.tb_met_metric_std  WHERE owner_dept_id = :d) AS metric_stds
        """.trimIndent()
        return jdbcTemplate.queryForObject(sql, MapSqlParameterSource("d", deptId)) { rs, _ ->
            linkedMapOf(
                "users" to rs.getLong("users"),
                "gwDeptMaps" to rs.getLong("gw_dept_maps"),
                "alertRecipGroups" to rs.getLong("alert_recip_groups"),
                "docs" to rs.getLong("docs"),
                "metricStds" to rs.getLong("metric_stds")
            )
        } ?: emptyMap()
    }

    /** 부서의 사용 중 화면 조회 권한 수 — 메뉴 권한 매트릭스와 같은 조인 (01 ACC-06) */
    fun countMenuPerm(deptId: Int): Int =
        jdbcTemplate.queryForObject(
            """
            SELECT count(*) FROM ax.tb_sys_dept_menu_perm p
              JOIN ax.tb_sys_menu m ON m.menu_id = p.menu_id AND m.use_flg = 'Y'
              JOIN ax.tb_sys_menu_group g ON g.group_id = m.group_id AND g.use_flg = 'Y'
             WHERE p.dept_id = :deptId AND p.can_read
            """.trimIndent(), MapSqlParameterSource("deptId", deptId), Int::class.java
        ) ?: 0

    /** 부서의 사용 중 데이터 항목 허용 수 */
    fun countDataPerm(deptId: Int): Int =
        jdbcTemplate.queryForObject(
            """
            SELECT count(*) FROM ax.tb_sys_dept_data_perm p
              JOIN ax.tb_sys_data_field f ON f.field_key = p.field_key AND f.use_flg = 'Y'
             WHERE p.dept_id = :deptId AND p.is_allowed
            """.trimIndent(), MapSqlParameterSource("deptId", deptId), Int::class.java
        ) ?: 0

    /** 부서 소속 계정 수 */
    fun countUsersInDept(deptId: Int): Long {
        val sql = "SELECT count(*) FROM ax.tb_sys_user WHERE dept_id = :deptId"
        return jdbcTemplate.queryForObject(sql, MapSqlParameterSource("deptId", deptId), Long::class.java) ?: 0L
    }

    /** 부서명 중복 여부 확인 */
    fun existsDeptName(deptNm: String?, excludeDeptId: Int?): Boolean {
        val sql = """
            SELECT count(*)
            FROM ax.tb_sys_dept
            WHERE :deptNm::varchar IS NOT NULL AND dept_nm = :deptNm
              AND (:excludeDeptId::int IS NULL OR dept_id <> :excludeDeptId)
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("deptNm", deptNm)
            .addValue("excludeDeptId", excludeDeptId)

        return (jdbcTemplate.queryForObject(sql, params, Long::class.java) ?: 0L) > 0
    }

    // =================================================================================
    // SY-02. 메뉴 접근 권한
    // =================================================================================

    /**
     * 전체 화면 목록을 조회한다. (No.140 — 매트릭스 컬럼)
     */
    fun findAllMenus(): List<Map<String, Any?>> {
        val sql = """
            SELECT m.menu_id, m.menu_nm, m.group_id, g.group_nm, m.is_sub_page, m.sort_seq, g.sort_seq AS group_seq,
                   m.parent_menu_id, m.route_path
            FROM ax.tb_sys_menu m
            INNER JOIN ax.tb_sys_menu_group g ON g.group_id = m.group_id
            WHERE m.use_flg = 'Y' AND g.use_flg = 'Y'
            ORDER BY g.sort_seq, m.sort_seq
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource()) { rs, _ ->
            mapOf(
                "id" to rs.getString("menu_id"),
                "name" to rs.getString("menu_nm"),
                "groupId" to rs.getString("group_id"),
                "group" to rs.getString("group_nm"),
                "sub" to rs.getBoolean("is_sub_page"),
                "kind" to menuKind(rs.getBoolean("is_sub_page"), rs.getString("route_path")),
                "parentId" to rs.getString("parent_menu_id")
            )
        }
    }

    /** 화면 종류 — ACTION(하위 페이지이면서 경로에 `#`, 예: 업로드 리포트 업로드) · SUB(하위 페이지) · MENU */
    private fun menuKind(sub: Boolean, routePath: String?): String = when {
        sub && routePath?.contains('#') == true -> "ACTION"
        sub -> "SUB"
        else -> "MENU"
    }

    /** 사용 중 화면의 권한 행 전부(조회 칸이 꺼진 행 포함) — 매트릭스 version·복사 해시 계산용 */
    fun findActiveMenuPermRows(): List<MenuPermRow> {
        val sql = """
            SELECT p.dept_id, p.menu_id, p.can_read
            FROM ax.tb_sys_dept_menu_perm p
            INNER JOIN ax.tb_sys_menu m ON m.menu_id = p.menu_id AND m.use_flg = 'Y'
            INNER JOIN ax.tb_sys_menu_group g ON g.group_id = m.group_id AND g.use_flg = 'Y'
        """.trimIndent()
        return jdbcTemplate.query(sql, MapSqlParameterSource()) { rs, _ ->
            MenuPermRow(rs.getInt("dept_id"), rs.getString("menu_id"), rs.getBoolean("can_read"))
        }
    }

    /** 부서·화면 한 칸의 현재 값 (행 없으면 null) */
    fun findMenuPerm(deptId: Int, menuId: String): MenuPermRow? =
        jdbcTemplate.query(
            "SELECT dept_id, menu_id, can_read FROM ax.tb_sys_dept_menu_perm WHERE dept_id = :deptId AND menu_id = :menuId",
            MapSqlParameterSource().addValue("deptId", deptId).addValue("menuId", menuId)
        ) { rs, _ -> MenuPermRow(rs.getInt("dept_id"), rs.getString("menu_id"), rs.getBoolean("can_read")) }
            .firstOrNull()

    /**
     * 메뉴 접근 권한 한 칸을 바꾼다 (03 MNP-02, V70 — 조회/쓰기 칸 통합).
     * 허용이면 행을 넣고(이미 있으면 can_read 를 켬), 회수면 행을 지운다. 접근 = 그 화면의 모든 동작 허용이다.
     */
    fun applyMenuPerm(deptId: Int, menuId: String, allowed: Boolean, actor: String): Int =
        upsertMenuPerm(deptId, menuId, allowed, actor)

    /** 이름으로 메뉴 그룹 ID 찾기(옛 groupNm 본문 호환) */
    fun findMenuGroupId(groupIdOrNm: String): String? =
        jdbcTemplate.query(
            "SELECT group_id FROM ax.tb_sys_menu_group WHERE group_id = :v OR group_nm = :v ORDER BY (group_id = :v) DESC LIMIT 1",
            MapSqlParameterSource("v", groupIdOrNm)
        ) { rs, _ -> rs.getString("group_id") }.firstOrNull()

    /**
     * 메뉴 권한 복사 저장 (03 MNP-01) — 대상 부서의 **사용 중 화면** 행만 지우고 원본 집합을 넣는다.
     * 사용 중지 화면의 보존 행은 남긴다(화면을 되살리면 그대로 돌아오게).
     *
     * @param menuIds 접근을 허용할 화면 ID
     * @return 넣은 행 수
     */
    fun replaceMenuPerms(toDeptId: Int, menuIds: Collection<String>, actor: String): Int {
        jdbcTemplate.update(
            """
            DELETE FROM ax.tb_sys_dept_menu_perm p USING ax.tb_sys_menu m
             WHERE p.dept_id = :toDeptId AND m.menu_id = p.menu_id AND m.use_flg = 'Y'
            """.trimIndent(),
            MapSqlParameterSource("toDeptId", toDeptId)
        )
        if (menuIds.isEmpty()) return 0
        val sql = """
            INSERT INTO ax.tb_sys_dept_menu_perm (dept_id, menu_id, can_read, ins_user, upd_user)
            VALUES (:toDeptId, :menuId, true, :actor, :actor)
            ON CONFLICT (dept_id, menu_id) DO UPDATE SET can_read = true, upd_date = now(), upd_user = :actor
        """.trimIndent()
        menuIds.forEach { menuId ->
            jdbcTemplate.update(sql, MapSqlParameterSource().addValue("toDeptId", toDeptId).addValue("menuId", menuId)
                .addValue("actor", actor))
        }
        return menuIds.size
    }

    /** 화면별 계정 추가 허용 (사용 중 화면만) — 매트릭스 grants·grantCounts */
    fun findGrantsByMenu(): Map<String, List<Map<String, Any?>>> =
        jdbcTemplate.query(
            """
            SELECT g.menu_id, g.user_id, u.user_nm, u.dept_id
              FROM ax.tb_sys_user_menu_grant g
              JOIN ax.tb_sys_user u ON u.user_id = g.user_id
              JOIN ax.tb_sys_menu m ON m.menu_id = g.menu_id AND m.use_flg = 'Y'
              JOIN ax.tb_sys_menu_group mg ON mg.group_id = m.group_id AND mg.use_flg = 'Y'
             ORDER BY g.menu_id, u.user_nm, g.user_id
            """.trimIndent(),
            MapSqlParameterSource()
        ) { rs, _ ->
            rs.getString("menu_id") to mapOf<String, Any?>(
                "empNo" to rs.getString("user_id"), "name" to rs.getString("user_nm"),
                "deptId" to rs.getInt("dept_id")
            )
        }.groupBy({ it.first }, { it.second })

    /**
     * 부서 × 화면 메뉴 권한 매트릭스를 조회한다. (No.140)
     */
    fun findMenuPermMatrix(): List<Map<String, Any?>> {
        // 사용 중지된 화면은 제외한다. 권한 행은 화면을 내려도 남아 있으므로
        // (복원 시 그대로 되돌리기 위해 남긴다) 조인 없이 읽으면 matrix 에만 나타난다.
        // 그러면 같은 응답의 screens 에는 없는 화면이 matrix 에 있는 상태가 되어,
        // 권한 관리 화면이 존재하지 않는 행을 그리고 관리자가 켜고 꺼도 아무 일이 없다.
        // 실제로 비가동 관리(prod-down)를 내렸을 때 그렇게 남았다.
        val sql = """
            SELECT p.dept_id, p.menu_id
            FROM ax.tb_sys_dept_menu_perm p
            INNER JOIN ax.tb_sys_menu m ON m.menu_id = p.menu_id AND m.use_flg = 'Y'
            INNER JOIN ax.tb_sys_menu_group g ON g.group_id = m.group_id AND g.use_flg = 'Y'
            WHERE p.can_read = true
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource()) { rs, _ ->
            mapOf(
                "deptId" to rs.getInt("dept_id"),
                "menuId" to rs.getString("menu_id")
            )
        }
    }

    /**
     * 메뉴 권한 단건을 변경한다. (No.141)
     *
     * @param allowed true 면 부여(UPSERT), false 면 회수(DELETE)
     */
    fun upsertMenuPerm(deptId: Int, menuId: String, allowed: Boolean, actor: String): Int {
        if (!allowed) {
            val sql = "DELETE FROM ax.tb_sys_dept_menu_perm WHERE dept_id = :deptId AND menu_id = :menuId"
            return jdbcTemplate.update(
                sql,
                MapSqlParameterSource().addValue("deptId", deptId).addValue("menuId", menuId)
            )
        }

        val sql = """
            INSERT INTO ax.tb_sys_dept_menu_perm (dept_id, menu_id, can_read, ins_user, upd_user)
            VALUES (:deptId, :menuId, true, :actor, :actor)
            ON CONFLICT (dept_id, menu_id)
            DO UPDATE SET can_read = true, upd_date = now(), upd_user = :actor
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("deptId", deptId)
            .addValue("menuId", menuId)
            .addValue("actor", actor)

        return jdbcTemplate.update(sql, params)
    }

    // =================================================================================
    // SY-03. 데이터 접근 권한
    // =================================================================================

    /**
     * 데이터 항목 목록을 조회한다. (No.145 — V33 확장)
     *
     * 각 항목에 붙은 API 응답 필드명(`ax.tb_sys_data_field_attr`) · 적용 스위치를 함께 낸다(분류는 2026-10-07 에 없앴다).
     * 사용 중(use_flg='Y') 항목 전체다 — 미적용(applyFlg='N') 항목도 나온다. 마스킹이 실제로 걸리는 것은
     * applyFlg='Y' 인 항목뿐이고, 그 목록은 `/auth/me` 의 dataFields 가 따로 낸다.
     */
    fun findDataFields(): List<Map<String, Any?>> {
        val sql = """
            SELECT
                f.field_key, f.field_nm, f.field_desc, f.sort_seq, f.apply_flg,
                (
                    SELECT string_agg(a.attr_name, ',' ORDER BY a.attr_name)
                      FROM ax.tb_sys_data_field_attr a
                     WHERE a.field_key = f.field_key
                ) AS attrs
            FROM ax.tb_sys_data_field f
            WHERE f.use_flg = 'Y'
            ORDER BY f.sort_seq, f.field_key
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource()) { rs, _ ->
            mapOf(
                "key" to rs.getString("field_key"),
                "name" to rs.getString("field_nm"),
                "desc" to rs.getString("field_desc"),
                "applyFlg" to rs.getString("apply_flg"),
                "sortSeq" to rs.getInt("sort_seq"),
                "attrs" to (rs.getString("attrs")?.split(",") ?: emptyList())
            )
        }
    }

    /** 항목별 응답 필드명과 메모 (항목 key → [{attrName, remark}]) — 메모는 자유 글(200자), 매핑 화면은 「{화면명} · {열 이름}」 으로 쓴다 */
    fun findAttrDetails(): Map<String, List<Map<String, Any?>>> =
        jdbcTemplate.query(
            "SELECT field_key, attr_name, remark FROM ax.tb_sys_data_field_attr ORDER BY field_key, attr_name",
            MapSqlParameterSource()
        ) { rs, _ ->
            rs.getString("field_key") to mapOf<String, Any?>("attrName" to rs.getString("attr_name"), "remark" to rs.getString("remark"))
        }.groupBy({ it.first }, { it.second })

    /**
     * 부서 × 데이터 항목 권한 매트릭스를 조회한다. (No.146)
     */
    fun findDataPermMatrix(): List<Map<String, Any?>> {
        val sql = """
            SELECT p.dept_id, p.field_key, p.is_allowed
            FROM ax.tb_sys_dept_data_perm p
            INNER JOIN ax.tb_sys_data_field f ON f.field_key = p.field_key AND f.use_flg = 'Y'
            WHERE p.is_allowed = true
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource()) { rs, _ ->
            mapOf("deptId" to rs.getInt("dept_id"), "fieldKey" to rs.getString("field_key"))
        }
    }

    /** 부서가 그 항목을 볼 수 있는지 — 권한 행이 있고 허용일 때만 true (V82 항목별 권한) */
    fun isDataPermAllowed(deptId: Int, fieldKey: String): Boolean =
        jdbcTemplate.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM ax.tb_sys_dept_data_perm WHERE dept_id = :deptId AND field_key = :fieldKey AND is_allowed = true)",
            MapSqlParameterSource().addValue("deptId", deptId).addValue("fieldKey", fieldKey),
            Boolean::class.java
        ) == true

    /**
     * 데이터 권한을 변경한다. (No.147)
     */
    fun upsertDataPerm(deptId: Int, fieldKey: String, allowed: Boolean, actor: String): Int {
        val sql = """
            INSERT INTO ax.tb_sys_dept_data_perm (dept_id, field_key, is_allowed, ins_user, upd_user)
            VALUES (:deptId, :fieldKey, :allowed, :actor, :actor)
            ON CONFLICT (dept_id, field_key)
            DO UPDATE SET is_allowed = :allowed, upd_date = now(), upd_user = :actor
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("deptId", deptId)
            .addValue("fieldKey", fieldKey)
            .addValue("allowed", allowed)
            .addValue("actor", actor)

        return jdbcTemplate.update(sql, params)
    }

    /**
     * 계정별 데이터 권한 적용 결과를 조회한다. (No.148 / No.149)
     */
    fun findDataPermByUser(limit: Int, offset: Int): List<Map<String, Any?>> {
        val sql = """
            SELECT
                u.user_id, u.user_nm, d.dept_id, d.dept_nm, d.is_super_admin,
                (
                    SELECT string_agg(p.field_key, ',' ORDER BY f.sort_seq)
                      FROM ax.tb_sys_dept_data_perm p
                     INNER JOIN ax.tb_sys_data_field f ON f.field_key = p.field_key
                     WHERE p.dept_id = d.dept_id AND p.is_allowed
                ) AS allowed_fields
            FROM ax.tb_sys_user u
            INNER JOIN ax.tb_sys_dept d ON d.dept_id = u.dept_id
            ORDER BY d.sort_seq, u.user_nm
            LIMIT :limit OFFSET :offset
        """.trimIndent()

        val params = MapSqlParameterSource().addValue("limit", limit).addValue("offset", offset)

        return jdbcTemplate.query(sql, params) { rs, _ ->
            mapOf(
                "empNo" to rs.getString("user_id"),
                "name" to rs.getString("user_nm"),
                "deptId" to rs.getInt("dept_id"),
                "dept" to rs.getString("dept_nm"),
                "superAdmin" to rs.getBoolean("is_super_admin"),
                "allowedFields" to (rs.getString("allowed_fields")?.split(",") ?: emptyList())
            )
        }
    }

    /** 계정별 데이터 권한 전체 건수 */
    fun countUsersAll(): Long =
        jdbcTemplate.queryForObject("SELECT count(*) FROM ax.tb_sys_user", MapSqlParameterSource(), Long::class.java) ?: 0L

    /**
     * 계정 상태 표기값(사용/정지)을 코드로 정규화한다.
     *
     * `LOCKED`(잠김)는 읽기(목록 필터)에만 쓴다 — 잠금은 로그인 실패로만 생기므로
     * 관리자가 LOCKED 로 바꾸는 요청은 서비스가 400 으로 막는다(09 AUD-16, 01 ACC-05).
     */
    fun normalizeUserState(state: String): String = when (state.trim()) {
        "사용", "ACTIVE", "active" -> "ACTIVE"
        "정지", "SUSPENDED", "suspended" -> "SUSPENDED"
        "승인대기", "승인 대기", "PENDING", "pending" -> "PENDING"
        "잠김", "LOCKED", "locked" -> "LOCKED"
        // 정의되지 않은 값을 그대로 저장하면 안 된다. 예전에는 uppercase() 해서 통과시켰는데,
        // 그러면 코드 매핑에서 빠져 stateNm 이 null 이 되고(화면에 빈칸), 로그인 판정은
        // 'ACTIVE 가 아님' 으로 걸려 계정이 조용히 잠긴다.
        // 본인 계정 정지 금지 검사도 'SUSPENDED' 만 보므로 우회된다.
        else -> throw InvalidParameterException(
            "계정 상태 값이 올바르지 않습니다. [$state] " +
                "허용 값은 ACTIVE(사용) · SUSPENDED(정지) · PENDING(승인 대기) · LOCKED(잠김, 조회 전용) 입니다.",
            "state"
        )
    }

    // =================================================================================
    // 계정별 추가 허용 화면 (V30 ax.tb_sys_user_menu_grant) — 2026-09-13
    // =================================================================================

    /** 여러 계정의 추가 허용 화면 — 목록 한 쪽에 붙일 때. 사용 중인 화면만, 메뉴 정렬 순. */
    fun findUserGrants(empNos: Collection<String>): Map<String, List<String>> {
        if (empNos.isEmpty()) return emptyMap()
        val sql = """
            SELECT g.user_id, g.menu_id
            FROM ax.tb_sys_user_menu_grant g
            INNER JOIN ax.tb_sys_menu m ON m.menu_id = g.menu_id AND m.use_flg = 'Y'
            INNER JOIN ax.tb_sys_menu_group mg ON mg.group_id = m.group_id AND mg.use_flg = 'Y'
            WHERE g.user_id = ANY(:ids)
            ORDER BY g.user_id, mg.sort_seq, m.sort_seq
        """.trimIndent()
        return jdbcTemplate.query(sql, MapSqlParameterSource("ids", empNos.toTypedArray())) { rs, _ ->
            rs.getString("user_id") to rs.getString("menu_id")
        }.groupBy({ it.first }, { it.second })
    }

    /** 한 계정의 추가 허용 화면 전부(사용 중지 화면 포함 — 치환 계산에 쓴다) */
    fun findUserGrantIds(empNo: String): Set<String> =
        jdbcTemplate.query(
            "SELECT menu_id FROM ax.tb_sys_user_menu_grant WHERE user_id = :empNo",
            MapSqlParameterSource("empNo", empNo)
        ) { rs, _ -> rs.getString("menu_id") }.toSet()

    /** 주어진 화면 ID 중 실제로 있고 사용 중인 것 */
    fun findActiveMenuIds(menuIds: Collection<String>): Set<String> {
        if (menuIds.isEmpty()) return emptySet()
        return jdbcTemplate.query(
            "SELECT menu_id FROM ax.tb_sys_menu WHERE menu_id = ANY(:ids) AND use_flg = 'Y'",
            MapSqlParameterSource("ids", menuIds.toTypedArray())
        ) { rs, _ -> rs.getString("menu_id") }.toSet()
    }

    /** 추가 허용 부여(멱등). 행이 있으면 그 화면에 접근할 수 있다(V70 — 쓰기 칸 없음). */
    fun insertUserGrants(empNo: String, menuIds: Collection<String>, actor: String): Int {
        if (menuIds.isEmpty()) return 0
        val sql = """
            INSERT INTO ax.tb_sys_user_menu_grant (user_id, menu_id, ins_user, upd_user)
            VALUES (:empNo, :menuId, :actor, :actor)
            ON CONFLICT (user_id, menu_id) DO UPDATE SET upd_user = EXCLUDED.upd_user, upd_date = now()
        """.trimIndent()
        val batch = menuIds.map { MapSqlParameterSource().addValue("empNo", empNo).addValue("menuId", it).addValue("actor", actor) }
        return jdbcTemplate.batchUpdate(sql, batch.toTypedArray()).sum()
    }

    /** 추가 허용 사유 저장 — 이미 부여된 행만 바꾼다. 빈 값은 사유 지우기 (01 ACC-10) */
    fun updateGrantReasons(empNo: String, reasons: Map<String, String?>, actor: String): Int {
        if (reasons.isEmpty()) return 0
        val batch = reasons.map { (menuId, reason) ->
            MapSqlParameterSource().addValue("empNo", empNo).addValue("menuId", menuId)
                .addValue("reason", reason?.trim()?.takeIf { it.isNotEmpty() }).addValue("actor", actor)
        }
        return jdbcTemplate.batchUpdate(
            """
            UPDATE ax.tb_sys_user_menu_grant SET grant_reason = :reason, upd_user = :actor, upd_date = now()
             WHERE user_id = :empNo AND menu_id = :menuId AND grant_reason IS DISTINCT FROM :reason
            """.trimIndent(), batch.toTypedArray()
        ).sum()
    }

    /** 계정별 추가 허용 상세 — 사유·부여 시각·부여자 이름 (01 ACC-10, 사용 중 화면만, 메뉴 순) */
    fun findUserGrantDetails(empNos: Collection<String>): Map<String, List<Map<String, Any?>>> {
        if (empNos.isEmpty()) return emptyMap()
        return jdbcTemplate.query(
            """
            SELECT g.user_id, g.menu_id, m.menu_nm, g.grant_reason, g.ins_date, g.ins_user, u.user_nm AS granted_by_nm
            FROM ax.tb_sys_user_menu_grant g
            INNER JOIN ax.tb_sys_menu m ON m.menu_id = g.menu_id AND m.use_flg = 'Y'
            INNER JOIN ax.tb_sys_menu_group mg ON mg.group_id = m.group_id AND mg.use_flg = 'Y'
            LEFT JOIN ax.tb_sys_user u ON u.user_id = g.ins_user
            WHERE g.user_id = ANY(:ids)
            ORDER BY g.user_id, mg.sort_seq, m.sort_seq
            """.trimIndent(),
            MapSqlParameterSource("ids", empNos.toTypedArray())
        ) { rs, _ ->
            rs.getString("user_id") to mapOf<String, Any?>(
                "id" to rs.getString("menu_id"), "name" to rs.getString("menu_nm"), "reason" to rs.getString("grant_reason"),
                "grantedAt" to Rs.dateTime(rs, "ins_date"), "grantedBy" to (rs.getString("granted_by_nm") ?: rs.getString("ins_user"))
            )
        }.groupBy({ it.first }, { it.second })
    }

    /** 추가 허용 회수 = 삭제 */
    fun deleteUserGrants(empNo: String, menuIds: Collection<String>): Int {
        if (menuIds.isEmpty()) return 0
        return jdbcTemplate.update(
            "DELETE FROM ax.tb_sys_user_menu_grant WHERE user_id = :empNo AND menu_id = ANY(:ids)",
            MapSqlParameterSource().addValue("empNo", empNo).addValue("ids", menuIds.toTypedArray())
        )
    }


    // =================================================================================
    // 관리자 비밀번호 변경 (2026-09-14)
    // =================================================================================

    /** 부서가 화면 권한을 **기본으로**(부서 메뉴 권한 표) 갖는지 — 계정 추가 허용은 보지 않는다 */
    fun deptHasMenuPerm(deptId: Int, menuId: String): Boolean =
        (jdbcTemplate.queryForObject(
            """
            SELECT count(*) FROM ax.tb_sys_dept_menu_perm p
            INNER JOIN ax.tb_sys_menu m ON m.menu_id = p.menu_id AND m.use_flg = 'Y'
            INNER JOIN ax.tb_sys_menu_group g ON g.group_id = m.group_id AND g.use_flg = 'Y'
            WHERE p.dept_id = :deptId AND p.menu_id = :menuId AND p.can_read = true
            """.trimIndent(),
            MapSqlParameterSource().addValue("deptId", deptId).addValue("menuId", menuId), Long::class.java
        ) ?: 0L) > 0

    /** 비밀번호 해시 교체 + 실패 횟수 초기화. 마지막 접속 시각은 건드리지 않는다(로그인이 아니다). */
    fun updatePasswordHash(empNo: String, pwdHash: String, actor: String): Int =
        jdbcTemplate.update(
            """
            UPDATE ax.tb_sys_user
               SET pwd_hash = :pwdHash, pwd_upd_at = now(), login_fail_cnt = 0, upd_date = now(), upd_user = :actor
             WHERE user_id = :empNo
            """.trimIndent(),
            MapSqlParameterSource().addValue("empNo", empNo).addValue("pwdHash", pwdHash).addValue("actor", actor)
        )

}

/** 부서 × 화면 권한 행 — 행이 있고 read 면 접근 허용(V70 부터 쓰기 칸 없음) */
data class MenuPermRow(val deptId: Int, val menuId: String, val read: Boolean)
