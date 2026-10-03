package com.dwje.api.repository

import com.dwje.api.common.util.Rs
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet

/**
 * 인증·권한 데이터 접근 Repository
 *
 * - iBatis 를 사용하지 않고 문자열 변수로 SQL 을 직접 관리한다.
 * - SQL Injection 방지를 위해 NamedParameterJdbcTemplate 사용을 필수로 한다.
 *
 * 참조 테이블 : ax.tb_sys_user, ax.tb_sys_dept, ax.tb_sys_login_hist,
 *              ax.tb_sys_dept_menu_perm, ax.tb_sys_dept_data_perm, ax.tb_sys_menu, ax.tb_sys_menu_group
 */
@Repository
class AuthRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) {

    /**
     * 사번으로 계정 및 소속 부서 정보를 조회한다. (로그인 · 토큰 검증 공통)
     *
     * @param userId 사번
     * @return 계정 정보 Map (없으면 null)
     */
    fun findUserWithDept(userId: String): Map<String, Any?>? {
        val sql = """
            SELECT
                u.user_id,
                u.user_nm,
                u.dept_id,
                u.plant_cd,
                u.position_cd,
                u.user_state_cd,
                u.is_switch_target,
                u.pwd_hash,
                u.login_fail_cnt,
                u.last_login_at,
                u.email,
                u.pwd_change_req_yn,
                d.dept_nm,
                d.is_super_admin
            FROM ax.tb_sys_user u
            INNER JOIN ax.tb_sys_dept d ON d.dept_id = u.dept_id
            WHERE u.user_id = :userId
        """.trimIndent()

        val params = MapSqlParameterSource("userId", userId)

        return jdbcTemplate.query(sql, params) { rs: ResultSet, _ ->
            mapOf(
                "userId" to rs.getString("user_id"),
                "userName" to rs.getString("user_nm"),
                "deptId" to rs.getInt("dept_id"),
                "plantCd" to rs.getString("plant_cd"),
                "positionCd" to rs.getString("position_cd"),
                "userStateCd" to rs.getString("user_state_cd"),
                "switchable" to rs.getBoolean("is_switch_target"),
                "pwdHash" to rs.getString("pwd_hash"),
                "loginFailCnt" to rs.getInt("login_fail_cnt"),
                "lastLoginAt" to Rs.dateTime(rs, "last_login_at"),
                "email" to rs.getString("email"),
                // 초기 비밀번호 변경 요구 (R-04, 01 ACC-03). 컬럼 기본값이 'Y' 다.
                "pwdChangeRequired" to (rs.getString("pwd_change_req_yn") == "Y"),
                "deptName" to rs.getString("dept_nm"),
                "superAdmin" to rs.getBoolean("is_super_admin")
            )
        }.firstOrNull()
    }

    /**
     * 부서에 허용된 메뉴(화면) ID 목록을 조회한다.
     *
     * @param deptId 부서 ID
     * @return 읽기 권한이 있는 menu_id 집합
     */
    /**
     * 계정의 **유효** 화면 권한 — 부서 권한 ∪ 계정 추가 허용(`ax.tb_sys_user_menu_grant`). (V30 · 2026-09-13)
     *
     * 규칙은 뷰 `ax.vw_sys_user_menu_perm` 한 곳에 있다(사용 중지 화면 제외 포함). 통합관리자 전 화면 허용은
     * 뷰에 없으므로 호출 측이 `superAdmin` 이면 조회하지 않고 통과시킨다(기존 판정 그대로).
     */
    fun findEffectiveMenuPermissions(userId: String): Set<String> {
        val sql = "SELECT menu_id FROM ax.vw_sys_user_menu_perm WHERE user_id = :userId"
        return jdbcTemplate.query(sql, MapSqlParameterSource("userId", userId)) { rs, _ -> rs.getString("menu_id") }.toSet()
    }

    fun findMenuPermissions(deptId: Int): Set<String> {
        // 판정 뷰(vw_sys_user_menu_perm, V64)와 같은 기준 — 화면과 메뉴 그룹이 모두 사용 중이어야 한다 (03 MNP-14)
        val sql = """
            SELECT p.menu_id
            FROM ax.tb_sys_dept_menu_perm p
            INNER JOIN ax.tb_sys_menu m ON m.menu_id = p.menu_id
            INNER JOIN ax.tb_sys_menu_group mg ON mg.group_id = m.group_id AND mg.use_flg = 'Y'
            WHERE p.dept_id  = :deptId
              AND p.can_read = true
              AND m.use_flg  = 'Y'
        """.trimIndent()

        val params = MapSqlParameterSource("deptId", deptId)
        return jdbcTemplate.query(sql, params) { rs, _ -> rs.getString("menu_id") }.toSet()
    }

    /**
     * 부서에 허용된 데이터 항목 key 목록을 조회한다. (7종 중 is_allowed = true)
     *
     * @param deptId 부서 ID
     * @return 허용 field_key 집합
     */
    fun findDataPermissions(deptId: Int): Set<String> {
        val sql = """
            SELECT p.field_key
            FROM ax.tb_sys_dept_data_perm p
            INNER JOIN ax.tb_sys_data_field f ON f.field_key = p.field_key
            WHERE p.dept_id    = :deptId
              AND p.is_allowed = true
              AND f.use_flg    = 'Y'
        """.trimIndent()

        val params = MapSqlParameterSource("deptId", deptId)
        return jdbcTemplate.query(sql, params) { rs, _ -> rs.getString("field_key") }.toSet()
    }

    /**
     * 로그인 이력을 기록한다. (성공/실패 모두 기록)
     *
     * @param userId     사번
     * @param resultCd   SYS_LOGIN_RESULT — SUCCESS / FAIL / LOCKED
     * @param failReason 실패 사유
     * @param ipAddr     접속 IP
     * @param userAgent  User-Agent
     * @return 생성된 로그인 이력 ID
     */
    fun insertLoginHistory(
        userId: String,
        resultCd: String,
        failReason: String?,
        ipAddr: String?,
        userAgent: String?,
        deptNm: String? = null
    ): Long {
        // dept_nm — 시도 당시 부서 스냅샷. 부서를 옮겨도 감사 목록의 로그인 행 부서가 바뀌지 않는다 (09 AUD-14, V58)
        val sql = """
            INSERT INTO ax.tb_sys_login_hist (user_id, login_at, result_cd, fail_reason, ip_addr, user_agent, dept_nm)
            VALUES (:userId, now(), :resultCd, :failReason, CAST(:ipAddr AS inet), :userAgent, :deptNm)
            RETURNING login_id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("userId", userId)
            .addValue("resultCd", resultCd)
            .addValue("failReason", failReason)
            .addValue("deptNm", deptNm?.take(50))
            .addValue("ipAddr", ipAddr)
            .addValue("userAgent", userAgent?.take(300))

        return jdbcTemplate.queryForObject(sql, params, Long::class.java) ?: 0L
    }

    /**
     * 최근 미종료 로그인 이력에 로그아웃 시각을 기록한다.
     *
     * @param userId 사번
     * @return 갱신 건수
     */
    fun updateLogout(userId: String): Int {
        val sql = """
            UPDATE ax.tb_sys_login_hist
               SET logout_at = now()
             WHERE login_id = (
                   SELECT login_id
                     FROM ax.tb_sys_login_hist
                    WHERE user_id   = :userId
                      AND result_cd = 'SUCCESS'
                      AND logout_at IS NULL
                    ORDER BY login_at DESC
                    LIMIT 1
             )
        """.trimIndent()

        return jdbcTemplate.update(sql, MapSqlParameterSource("userId", userId))
    }

    /**
     * 로그인 성공 시 최종 접속일시를 갱신하고 실패 횟수를 초기화한다.
     */
    fun markLoginSuccess(userId: String): Int {
        val sql = """
            UPDATE ax.tb_sys_user
               SET last_login_at  = now(),
                   login_fail_cnt = 0,
                   upd_date       = now(),
                   upd_user       = :userId
             WHERE user_id = :userId
        """.trimIndent()
        return jdbcTemplate.update(sql, MapSqlParameterSource("userId", userId))
    }

    /**
     * 로그인 실패 횟수를 1 증가시키고 증가 후 값을 반환한다.
     */
    fun increaseLoginFailCount(userId: String): Int {
        val sql = """
            UPDATE ax.tb_sys_user
               SET login_fail_cnt = login_fail_cnt + 1,
                   upd_date       = now()
             WHERE user_id = :userId
            RETURNING login_fail_cnt
        """.trimIndent()
        return jdbcTemplate.queryForObject(sql, MapSqlParameterSource("userId", userId), Int::class.java) ?: 0
    }

    /**
     * 계정 상태를 변경한다. (ACTIVE / SUSPENDED)
     */
    fun updateUserState(userId: String, stateCd: String, actor: String): Int {
        val sql = """
            UPDATE ax.tb_sys_user
               SET user_state_cd = :stateCd,
                   upd_date      = now(),
                   upd_user      = :actor
             WHERE user_id = :userId
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("userId", userId)
            .addValue("stateCd", stateCd)
            .addValue("actor", actor)

        return jdbcTemplate.update(sql, params)
    }

    /**
     * 연속 실패 계정을 잠근다 — ACTIVE 인 계정만 LOCKED 로 바꾼다 (R-02, 09 AUD-16).
     *
     * 조건부 UPDATE 라 동시에 두 요청이 5회째를 받아도 한 번만 바뀐다. 잠금은 시스템만 건다(관리자 경로 없음).
     *
     * @return 실제로 잠근 건수 (0 이면 이미 잠겼거나 다른 상태)
     */
    fun lockUser(userId: String): Int {
        val sql = """
            UPDATE ax.tb_sys_user
               SET user_state_cd = 'LOCKED',
                   upd_date      = now(),
                   upd_user      = 'SYSTEM'
             WHERE user_id = :userId
               AND user_state_cd = 'ACTIVE'
        """.trimIndent()
        return jdbcTemplate.update(sql, MapSqlParameterSource("userId", userId))
    }

    /**
     * 잠긴 계정을 푼다 — LOCKED 인 계정만 ACTIVE 로 바꾸고 실패 횟수를 0 으로 둔다.
     *
     * 이메일 해제·비밀번호 찾기·관리자 해제가 동시에 와도 조건부 UPDATE 라 한 쪽만 성공한다.
     *
     * @param pwdChangeReq 함께 저장할 비밀번호 변경 요구(Y/N). null 이면 그대로 둔다
     * @return 실제로 푼 건수 (0 이면 이미 풀렸거나 정지 등 다른 상태)
     */
    fun unlockUser(userId: String, actor: String, pwdChangeReq: String?): Int {
        val sql = """
            UPDATE ax.tb_sys_user
               SET user_state_cd     = 'ACTIVE',
                   login_fail_cnt    = 0,
                   pwd_change_req_yn = coalesce(CAST(:pwdChangeReq AS varchar), pwd_change_req_yn),
                   upd_date          = now(),
                   upd_user          = :actor
             WHERE user_id = :userId
               AND user_state_cd = 'LOCKED'
        """.trimIndent()
        val params = MapSqlParameterSource()
            .addValue("userId", userId)
            .addValue("actor", actor)
            .addValue("pwdChangeReq", pwdChangeReq)
        return jdbcTemplate.update(sql, params)
    }

    /**
     * 잠긴 시각 — 로그인 이력의 「연속 실패 N회 잠금」 최신 1건 (잠금 응답·계정 목록의 lockedAt).
     * 백필로 잠긴 계정처럼 그런 행이 없으면 null 이다.
     */
    fun findLockedAt(userId: String): String? {
        val sql = """
            SELECT login_at
              FROM ax.tb_sys_login_hist
             WHERE user_id = :userId
               AND result_cd = 'LOCKED'
               AND fail_reason LIKE '연속 실패%'
             ORDER BY login_at DESC
             LIMIT 1
        """.trimIndent()
        return jdbcTemplate.query(sql, MapSqlParameterSource("userId", userId)) { rs, _ ->
            Rs.dateTime(rs, "login_at")
        }.firstOrNull()
    }

    /**
     * 초기 비밀번호 변경 요구(`pwd_change_req_yn`)를 저장한다 (R-04, 01 ACC-03).
     * 본인 변경·재설정·잠금 해제는 'N', 관리자 등록·관리자 비밀번호 변경·관리자 잠금 해제는 'Y'.
     */
    fun updatePwdChangeRequired(userId: String, required: Boolean, actor: String): Int {
        val sql = """
            UPDATE ax.tb_sys_user
               SET pwd_change_req_yn = :yn,
                   upd_date          = now(),
                   upd_user          = :actor
             WHERE user_id = :userId
        """.trimIndent()
        val params = MapSqlParameterSource()
            .addValue("userId", userId)
            .addValue("yn", if (required) "Y" else "N")
            .addValue("actor", actor)
        return jdbcTemplate.update(sql, params)
    }

    /**
     * 사용 중인 전 화면 ID 를 조회한다. (통합관리자 접근 권한 산출용)
     *
     * [findMenuTree] 는 **좌측 메뉴 렌더링용**이라 메뉴에 노출하지 않는 하위 화면
     * (`is_sub_page`)을 일부러 제외한다. 그 결과를 **접근 권한 목록**으로 쓰면
     * 하위 화면이 빠져 통합관리자조차 그 화면에 들어갈 수 없게 된다.
     * (실제로 `/auth/me` 가 이 쿼리를 써서 하위 화면 4개가 막힌 일이 있었다.
     *  같은 부서의 `system/menu-perms` matrix 는 36건, `/auth/me` 는 32건이었다.)
     *
     * '메뉴에 보일 것' 과 '들어갈 수 있는 것' 은 다른 질문이므로 쿼리를 나눈다.
     */
    fun findAllMenuIds(): List<String> {
        val sql = """
            SELECT m.menu_id
            FROM ax.tb_sys_menu m
            INNER JOIN ax.tb_sys_menu_group g ON g.group_id = m.group_id
            WHERE m.use_flg = 'Y'
              AND g.use_flg = 'Y'
            ORDER BY g.sort_seq, m.sort_seq
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource()) { rs, _ -> rs.getString("menu_id") }
    }

    /**
     * 로그인 사용자가 접근 가능한 메뉴 트리를 그룹 단위로 조회한다.
     *
     * @param deptId     부서 ID
     * @param superAdmin 통합관리자 여부 — true 면 전체 메뉴를 반환한다.
     */
    /**
     * 좌측 메뉴 트리 — 통합관리자가 아니면 **유효 화면 권한**(부서 ∪ 계정 추가 허용, `ax.vw_sys_user_menu_perm`)으로 거른다.
     * 부서만 보면 계정에만 열어 준 화면이 메뉴에서 빠진다(2026-09-13 V30).
     */
    fun findMenuTree(userId: String, superAdmin: Boolean): List<Map<String, Any?>> {
        // 기본 SQL 정의 — 메뉴에 노출되지 않는 하위 화면(is_sub_page)은 트리에서 제외한다.
        val sql = StringBuilder(
            """
            SELECT
                g.group_id,
                g.group_nm,
                g.is_solo,
                g.sort_seq  AS group_seq,
                m.menu_id,
                m.menu_nm,
                m.route_path,
                m.tag_cd,
                m.sort_seq  AS menu_seq
            FROM ax.tb_sys_menu m
            INNER JOIN ax.tb_sys_menu_group g ON g.group_id = m.group_id
            WHERE m.use_flg     = 'Y'
              AND g.use_flg     = 'Y'
              AND m.is_sub_page = false
            """.trimIndent()
        )

        val params = MapSqlParameterSource()

        // 통합관리자가 아니면 유효 화면 권한(부서 ∪ 계정 추가 허용)으로 필터링한다.
        if (!superAdmin) {
            sql.append(
                """

                AND EXISTS (
                    SELECT 1
                      FROM ax.vw_sys_user_menu_perm v
                     WHERE v.menu_id = m.menu_id
                       AND v.user_id = :userId
                )
                """.trimIndent()
            )
            params.addValue("userId", userId)
        }

        sql.append("\nORDER BY g.sort_seq, m.sort_seq")

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "groupId" to rs.getString("group_id"),
                "groupName" to rs.getString("group_nm"),
                "solo" to rs.getBoolean("is_solo"),
                "menuId" to rs.getString("menu_id"),
                "menuName" to rs.getString("menu_nm"),
                "routePath" to rs.getString("route_path"),
                "tag" to rs.getString("tag_cd")
            )
        }
    }

    /**
     * 계정 전환 대상 계정 목록을 조회한다. (프로토타입 데모 기능)
     */
    fun findSwitchTargets(): List<Map<String, Any?>> {
        val sql = """
            SELECT u.user_id, u.user_nm, u.position_cd, d.dept_nm
            FROM ax.tb_sys_user u
            INNER JOIN ax.tb_sys_dept d ON d.dept_id = u.dept_id
            WHERE u.is_switch_target = true
              AND u.user_state_cd    = 'ACTIVE'
            ORDER BY d.sort_seq, u.user_nm
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource()) { rs, _ ->
            mapOf(
                "empNo" to rs.getString("user_id"),
                "name" to rs.getString("user_nm"),
                "pos" to rs.getString("position_cd"),
                "dept" to rs.getString("dept_nm")
            )
        }
    }

    /**
     * 현재 서비스 중(ACTIVE)인 AI 서빙 프로파일 버전 태그를 조회한다.
     * — 내 정보 조회 응답의 servingModelVer 항목
     */
    fun findServingModelVersion(): String? {
        val sql = """
            SELECT p.profile_cd || '-v' || p.version_no AS serving_ver
            FROM ax.tb_ai_serving_profile p
            WHERE p.service_cd = 'CHAT'
              AND p.state_cd   = 'ACTIVE'
            ORDER BY p.activated_at DESC NULLS LAST
            LIMIT 1
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource()) { rs, _ -> rs.getString("serving_ver") }
            .firstOrNull()
    }

    /**
     * 비밀번호 해시를 갱신한다. (비밀번호 변경 · 레거시 해시 재발급 공용)
     *
     * @param userId  사번
     * @param pwdHash 새 해시 문자열
     * @param actor   수행자
     */
    fun updatePasswordHash(userId: String, pwdHash: String, actor: String): Int {
        val sql = """
            UPDATE ax.tb_sys_user
               SET pwd_hash   = :pwdHash,
                   pwd_upd_at = now(),
                   upd_date   = now(),
                   upd_user   = :actor
             WHERE user_id = :userId
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("userId", userId)
            .addValue("pwdHash", pwdHash)
            .addValue("actor", actor)

        return jdbcTemplate.update(sql, params)
    }

    /**
     * 회원가입 계정을 등록한다. (승인 대기 상태)
     *
     * 관리자가 만드는 계정(No.129)과 달리 상태가 PENDING 이고 계정 전환 대상이 아니다.
     *
     * @return 등록 건수
     */
    fun insertSignupUser(
        userId: String,
        userNm: String,
        deptId: Int,
        plantCd: String?,
        positionCd: String,
        pwdHash: String
    ): Int {
        val sql = """
            INSERT INTO ax.tb_sys_user (
                user_id, user_nm, dept_id, plant_cd, position_cd, user_state_cd,
                is_switch_target, pwd_hash, pwd_upd_at, pwd_change_req_yn, remark, ins_user, upd_user
            ) VALUES (
                :userId, :userNm, :deptId, :plantCd, :positionCd, 'PENDING',
                false, :pwdHash, now(), 'N', '회원가입 신청 — 관리자 승인 대기', :userId, :userId
            )
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("userId", userId)
            .addValue("userNm", userNm.take(50))
            .addValue("deptId", deptId)
            .addValue("plantCd", plantCd)
            .addValue("positionCd", positionCd)
            .addValue("pwdHash", pwdHash)

        return jdbcTemplate.update(sql, params)
    }

    /**
     * 사번 사용 가능 여부를 확인한다. (회원가입 중복 검사)
     *
     * @return 이미 등록된 사번이면 true
     */
    fun existsUserId(userId: String): Boolean {
        val sql = "SELECT count(*) FROM ax.tb_sys_user WHERE user_id = :userId"
        return (jdbcTemplate.queryForObject(sql, MapSqlParameterSource("userId", userId), Long::class.java) ?: 0L) > 0
    }

    /**
     * 가입 신청 시 선택 가능한 부서 목록을 조회한다.
     *
     * 통합관리자 부서와 미배정 부서(시스템 부서)는 스스로 신청할 수 없도록 제외한다 (CMN-01, 01 ACC-02).
     *
     * @param unassignedDeptName 미배정 부서 이름 (`app.unassigned-dept-name`)
     */
    fun findSignupDepts(unassignedDeptName: String): List<Map<String, Any?>> {
        val sql = """
            SELECT dept_id, dept_nm, dept_desc
            FROM ax.tb_sys_dept
            WHERE use_flg = 'Y' AND is_super_admin = false AND dept_nm <> :unassignedDeptName
            ORDER BY sort_seq, dept_nm
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource("unassignedDeptName", unassignedDeptName)) { rs, _ ->
            mapOf(
                "deptId" to rs.getInt("dept_id"),
                "deptNm" to rs.getString("dept_nm"),
                "desc" to rs.getString("dept_desc")
            )
        }
    }

    /** 부서 단건 — 가입 신청의 시스템 부서 판정용 (deptId · deptNm · superAdmin). 사용 중지 부서는 없음으로 본다 */
    fun findActiveDept(deptId: Int): Map<String, Any?>? {
        val sql = "SELECT dept_id, dept_nm, is_super_admin FROM ax.tb_sys_dept WHERE dept_id = :deptId AND use_flg = 'Y'"
        return jdbcTemplate.query(sql, MapSqlParameterSource("deptId", deptId)) { rs, _ ->
            mapOf(
                "deptId" to rs.getInt("dept_id"),
                "deptNm" to rs.getString("dept_nm"),
                "superAdmin" to rs.getBoolean("is_super_admin")
            )
        }.firstOrNull()
    }

    /** 부서 존재 여부 확인 (가입 신청 검증용) */
    fun existsDept(deptId: Int): Boolean {
        val sql = "SELECT count(*) FROM ax.tb_sys_dept WHERE dept_id = :deptId AND use_flg = 'Y'"
        return (jdbcTemplate.queryForObject(sql, MapSqlParameterSource("deptId", deptId), Long::class.java) ?: 0L) > 0
    }
}
