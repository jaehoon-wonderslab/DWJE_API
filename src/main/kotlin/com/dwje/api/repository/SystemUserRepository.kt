package com.dwje.api.repository

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.util.Rs
import com.dwje.api.common.util.SqlLikeUtils
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
                    "pending" to rs.getLong("pending_cnt")
                ),
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
        offset: Int
    ): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT
                u.user_id, u.user_nm, u.dept_id, d.dept_nm, d.dept_abbr,
                u.position_cd, pc.code_nm AS position_nm,
                u.user_state_cd, sc.code_nm AS state_nm,
                u.is_switch_target, u.plant_cd, u.last_login_at, u.login_fail_cnt, u.remark
            FROM ax.tb_sys_user u
            INNER JOIN ax.tb_sys_dept d  ON d.dept_id  = u.dept_id
            LEFT  JOIN ax.tb_sys_code pc ON pc.group_cd = 'SYS_POSITION'   AND pc.code = u.position_cd
            LEFT  JOIN ax.tb_sys_code sc ON sc.group_cd = 'SYS_USER_STATE' AND sc.code = u.user_state_cd
            WHERE 1 = 1
            """.trimIndent()
        )

        val params = MapSqlParameterSource()
        appendUserFilters(sql, params, keyword, deptId, state, switchable)

        sql.append("\nORDER BY d.sort_seq, u.user_nm")
        // limit 이 null 이면 전량(size=0) — 화면이 Tabulator 열 필터를 전체 결과에 걸 때 쓴다.
        if (limit != null) {
            sql.append("\nLIMIT :limit OFFSET :offset")
            params.addValue("limit", limit).addValue("offset", offset)
        }

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "empNo" to rs.getString("user_id"),
                "name" to rs.getString("user_nm"),
                "deptId" to rs.getInt("dept_id"),
                "dept" to rs.getString("dept_nm"),
                "deptAbbr" to rs.getString("dept_abbr"),
                "pos" to rs.getString("position_cd"),
                "posNm" to rs.getString("position_nm"),
                "state" to rs.getString("user_state_cd"),
                "stateNm" to rs.getString("state_nm"),
                "demo" to rs.getBoolean("is_switch_target"),
                "plantCd" to rs.getString("plant_cd"),
                "lastLoginAt" to Rs.dateTime(rs, "last_login_at"),
                "loginFailCnt" to rs.getInt("login_fail_cnt"),
                "remark" to rs.getString("remark")
            )
        }
    }

    /** 계정 목록 전체 건수 */
    fun countUsers(keyword: String?, deptId: Int?, state: String?, switchable: Boolean?): Long {
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
        appendUserFilters(sql, params, keyword, deptId, state, switchable)

        return jdbcTemplate.queryForObject(sql.toString(), params, Long::class.java) ?: 0L
    }

    /**
     * 계정 목록/건수 공통 동적 조건
     *
     * `keyword` 는 화면 표의 **전 열** 검색이다(2026-09-13 WEB 요청) — 사번·이름·부서명·부서 약칭·직급 코드/이름·상태 코드/이름·마지막 접속 시각.
     */
    private fun appendUserFilters(
        sql: StringBuilder,
        params: MapSqlParameterSource,
        keyword: String?,
        deptId: Int?,
        state: String?,
        switchable: Boolean?
    ) {
        SqlLikeUtils.contains(keyword)?.let {
            sql.append(
                " AND (u.user_id LIKE :keyword ESCAPE '\\' OR u.user_nm LIKE :keyword ESCAPE '\\'" +
                    " OR d.dept_nm LIKE :keyword ESCAPE '\\' OR d.dept_abbr LIKE :keyword ESCAPE '\\'" +
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
        if (!state.isNullOrBlank()) {
            sql.append(" AND u.user_state_cd = :state")
            params.addValue("state", normalizeUserState(state))
        }
        if (switchable != null) {
            sql.append(" AND u.is_switch_target = :switchable")
            params.addValue("switchable", switchable)
        }
    }

    /**
     * 계정을 등록한다. (No.129)
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
                is_switch_target, pwd_hash, pwd_upd_at, ins_user, upd_user
            ) VALUES (
                :empNo, :name, :deptId, :plantCd, :positionCd, :stateCd,
                :switchable, :pwdHash, now(), :actor, :actor
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

    // =================================================================================
    // 부서 관리
    // =================================================================================

    /**
     * 부서 목록을 조회한다. (No.135 / No.134 / No.144)
     *
     * 부서별 소속 계정 수와 메뉴/데이터 권한 수를 함께 집계한다.
     */
    fun findDepts(): List<Map<String, Any?>> = findDepts(null, null, 0)

    /** 부서 건수 — 키워드(부서명·약칭·설명) */
    fun countDepts(keyword: String?): Long {
        val sql = StringBuilder("SELECT count(*) FROM ax.tb_sys_dept d WHERE d.use_flg = 'Y'")
        val params = MapSqlParameterSource()
        appendDeptKeyword(sql, params, keyword)
        return jdbcTemplate.queryForObject(sql.toString(), params, Long::class.java) ?: 0L
    }

    private fun appendDeptKeyword(sql: StringBuilder, params: MapSqlParameterSource, keyword: String?) {
        SqlLikeUtils.contains(keyword)?.let {
            sql.append(
                " AND (d.dept_nm LIKE :keyword ESCAPE '\\' OR d.dept_abbr LIKE :keyword ESCAPE '\\'" +
                    " OR coalesce(d.dept_desc, '') LIKE :keyword ESCAPE '\\')"
            )
            params.addValue("keyword", it)
        }
    }

    /**
     * 부서 목록 — `keyword`(부서명·약칭·설명) 와 쪽 나눔. `limit` 이 null 이면 전량(기존 호출).
     */
    fun findDepts(keyword: String?, limit: Int?, offset: Int): List<Map<String, Any?>> {
        val sql = StringBuilder("""
            SELECT
                d.dept_id,
                d.dept_nm,
                d.dept_abbr,
                d.dept_desc,
                d.plant_cd,
                d.is_super_admin,
                d.sort_seq,
                (SELECT count(*) FROM ax.tb_sys_user u
                  WHERE u.dept_id = d.dept_id)                                        AS user_cnt,
                (SELECT count(*) FROM ax.tb_sys_dept_menu_perm p
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
                "abbr" to rs.getString("dept_abbr"),
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
    fun insertDept(deptNm: String, abbr: String, desc: String?, plantCd: String?, actor: String): Int {
        val sql = """
            INSERT INTO ax.tb_sys_dept (dept_nm, dept_abbr, dept_desc, plant_cd, use_flg, ins_user, upd_user)
            VALUES (:deptNm, :abbr, :desc, :plantCd, 'Y', :actor, :actor)
            RETURNING dept_id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("deptNm", deptNm.take(50))
            .addValue("abbr", abbr.take(4))
            .addValue("desc", desc?.take(200))
            .addValue("plantCd", plantCd)
            .addValue("actor", actor)

        return jdbcTemplate.queryForObject(sql, params, Int::class.java) ?: 0
    }

    /**
     * 부서를 수정한다. (No.137)
     */
    fun updateDept(deptId: Int, deptNm: String?, abbr: String?, desc: String?, actor: String): Int {
        val sql = """
            UPDATE ax.tb_sys_dept
               SET dept_nm   = coalesce(:deptNm, dept_nm),
                   dept_abbr = coalesce(:abbr, dept_abbr),
                   dept_desc = coalesce(:desc, dept_desc),
                   upd_date  = now(),
                   upd_user  = :actor
             WHERE dept_id = :deptId
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("deptId", deptId)
            .addValue("deptNm", deptNm?.take(50))
            .addValue("abbr", abbr?.take(4))
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
            SELECT dept_id, dept_nm, dept_abbr, dept_desc, plant_cd, is_super_admin
            FROM ax.tb_sys_dept
            WHERE dept_id = :deptId
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource("deptId", deptId)) { rs, _ ->
            mapOf(
                "deptId" to rs.getInt("dept_id"),
                "deptNm" to rs.getString("dept_nm"),
                "abbr" to rs.getString("dept_abbr"),
                "desc" to rs.getString("dept_desc"),
                "plantCd" to rs.getString("plant_cd"),
                "superAdmin" to rs.getBoolean("is_super_admin")
            )
        }.firstOrNull()
    }

    /** 부서 소속 계정 수 */
    fun countUsersInDept(deptId: Int): Long {
        val sql = "SELECT count(*) FROM ax.tb_sys_user WHERE dept_id = :deptId"
        return jdbcTemplate.queryForObject(sql, MapSqlParameterSource("deptId", deptId), Long::class.java) ?: 0L
    }

    /** 부서명·약칭 중복 여부 확인 */
    fun existsDeptName(deptNm: String?, abbr: String?, excludeDeptId: Int?): Boolean {
        val sql = """
            SELECT count(*)
            FROM ax.tb_sys_dept
            WHERE (:deptNm::varchar IS NOT NULL AND dept_nm = :deptNm
                   OR :abbr::varchar IS NOT NULL AND dept_abbr = :abbr)
              AND (:excludeDeptId::int IS NULL OR dept_id <> :excludeDeptId)
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("deptNm", deptNm)
            .addValue("abbr", abbr)
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
            SELECT m.menu_id, m.menu_nm, m.group_id, g.group_nm, m.is_sub_page, m.sort_seq, g.sort_seq AS group_seq
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
                "sub" to rs.getBoolean("is_sub_page")
            )
        }
    }

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
            SELECT p.dept_id, p.menu_id, p.can_read, p.can_write
            FROM ax.tb_sys_dept_menu_perm p
            INNER JOIN ax.tb_sys_menu m ON m.menu_id = p.menu_id AND m.use_flg = 'Y'
            INNER JOIN ax.tb_sys_menu_group g ON g.group_id = m.group_id AND g.use_flg = 'Y'
            WHERE p.can_read = true
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource()) { rs, _ ->
            mapOf(
                "deptId" to rs.getInt("dept_id"),
                "menuId" to rs.getString("menu_id"),
                "canWrite" to rs.getBoolean("can_write")
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
            INSERT INTO ax.tb_sys_dept_menu_perm (dept_id, menu_id, can_read, can_write, ins_user, upd_user)
            VALUES (:deptId, :menuId, true, false, :actor, :actor)
            ON CONFLICT (dept_id, menu_id)
            DO UPDATE SET can_read = true, upd_date = now(), upd_user = :actor
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("deptId", deptId)
            .addValue("menuId", menuId)
            .addValue("actor", actor)

        return jdbcTemplate.update(sql, params)
    }

    /**
     * 메뉴 그룹 단위로 권한을 일괄 변경한다. (No.142)
     *
     * @return 변경된 건수
     */
    fun updateMenuPermByGroup(deptId: Int, groupId: String, allowed: Boolean, actor: String): Int {
        if (!allowed) {
            val sql = """
                DELETE FROM ax.tb_sys_dept_menu_perm p
                 WHERE p.dept_id = :deptId
                   AND EXISTS (
                       SELECT 1 FROM ax.tb_sys_menu m
                        WHERE m.menu_id = p.menu_id AND m.group_id = :groupId
                   )
            """.trimIndent()
            return jdbcTemplate.update(
                sql,
                MapSqlParameterSource().addValue("deptId", deptId).addValue("groupId", groupId)
            )
        }

        val sql = """
            INSERT INTO ax.tb_sys_dept_menu_perm (dept_id, menu_id, can_read, can_write, ins_user, upd_user)
            SELECT :deptId, m.menu_id, true, false, :actor, :actor
            FROM ax.tb_sys_menu m
            WHERE m.group_id = :groupId AND m.use_flg = 'Y'
            ON CONFLICT (dept_id, menu_id)
            DO UPDATE SET can_read = true, upd_date = now(), upd_user = :actor
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("deptId", deptId)
            .addValue("groupId", groupId)
            .addValue("actor", actor)

        return jdbcTemplate.update(sql, params)
    }

    /**
     * 부서 간 메뉴 권한을 복사한다. (No.143)
     *
     * @return 복사된 건수
     */
    fun copyMenuPerms(fromDeptId: Int, toDeptId: Int, actor: String): Int {
        // 대상 부서의 기존 권한을 제거한 뒤 원본 부서 권한을 그대로 복사한다.
        jdbcTemplate.update(
            "DELETE FROM ax.tb_sys_dept_menu_perm WHERE dept_id = :toDeptId",
            MapSqlParameterSource("toDeptId", toDeptId)
        )

        val sql = """
            INSERT INTO ax.tb_sys_dept_menu_perm (dept_id, menu_id, can_read, can_write, ins_user, upd_user)
            SELECT :toDeptId, p.menu_id, p.can_read, p.can_write, :actor, :actor
            FROM ax.tb_sys_dept_menu_perm p
            WHERE p.dept_id = :fromDeptId
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("fromDeptId", fromDeptId)
            .addValue("toDeptId", toDeptId)
            .addValue("actor", actor)

        return jdbcTemplate.update(sql, params)
    }

    // =================================================================================
    // SY-03. 데이터 접근 권한
    // =================================================================================

    /**
     * 데이터 항목 목록을 조회한다. (No.145 — V33 확장)
     *
     * 각 항목에 붙은 API 응답 필드명(`ax.tb_sys_data_field_attr`) · 분류 · 적용 스위치를 함께 낸다.
     * 사용 중(use_flg='Y') 항목 전체다 — 미적용(applyFlg='N') 항목도 나온다. 마스킹이 실제로 걸리는 것은
     * applyFlg='Y' 인 항목뿐이고, 그 목록은 `/auth/me` 의 dataFields 가 따로 낸다.
     */
    fun findDataFields(): List<Map<String, Any?>> {
        val sql = """
            SELECT
                f.field_key, f.field_nm, f.field_desc, f.sort_seq, f.category_cd, f.apply_flg,
                c.code_nm AS category_nm,
                (
                    SELECT string_agg(a.attr_name, ',' ORDER BY a.attr_name)
                      FROM ax.tb_sys_data_field_attr a
                     WHERE a.field_key = f.field_key
                ) AS attrs
            FROM ax.tb_sys_data_field f
            LEFT JOIN ax.tb_sys_code c ON c.group_cd = 'DATA_FIELD_CATEGORY' AND c.code = f.category_cd
            WHERE f.use_flg = 'Y'
            ORDER BY f.sort_seq, f.field_key
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource()) { rs, _ ->
            mapOf(
                "key" to rs.getString("field_key"),
                "name" to rs.getString("field_nm"),
                "desc" to rs.getString("field_desc"),
                "category" to rs.getString("category_cd"),
                "categoryNm" to rs.getString("category_nm"),
                "applyFlg" to rs.getString("apply_flg"),
                "sortSeq" to rs.getInt("sort_seq"),
                "attrs" to (rs.getString("attrs")?.split(",") ?: emptyList())
            )
        }
    }

    /**
     * 부서 × 데이터 항목 권한 매트릭스를 조회한다. (No.146)
     */
    fun findDataPermMatrix(): List<Map<String, Any?>> {
        val sql = """
            SELECT p.dept_id, p.field_key, p.is_allowed
            FROM ax.tb_sys_dept_data_perm p
            WHERE p.is_allowed = true
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource()) { rs, _ ->
            mapOf("deptId" to rs.getInt("dept_id"), "fieldKey" to rs.getString("field_key"))
        }
    }

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

    /** 계정 상태 표기값(사용/정지)을 코드로 정규화한다. */
    fun normalizeUserState(state: String): String = when (state.trim()) {
        "사용", "ACTIVE", "active" -> "ACTIVE"
        "정지", "SUSPENDED", "suspended" -> "SUSPENDED"
        "승인대기", "승인 대기", "PENDING", "pending" -> "PENDING"
        // 정의되지 않은 값을 그대로 저장하면 안 된다. 예전에는 uppercase() 해서 통과시켰는데,
        // 그러면 코드 매핑에서 빠져 stateNm 이 null 이 되고(화면에 빈칸), 로그인 판정은
        // 'ACTIVE 가 아님' 으로 걸려 계정이 조용히 잠긴다.
        // 본인 계정 정지 금지 검사도 'SUSPENDED' 만 보므로 우회된다.
        else -> throw InvalidParameterException(
            "계정 상태 값이 올바르지 않습니다. [$state] " +
                "허용 값은 ACTIVE(사용) · SUSPENDED(정지) · PENDING(승인 대기) 입니다.",
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
            INNER JOIN ax.tb_sys_menu_group mg ON mg.group_id = m.group_id
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

    /** 추가 허용 부여(멱등). 계정 관리 화면은 열람만 부여한다 — can_write 는 false 로 둔다. */
    fun insertUserGrants(empNo: String, menuIds: Collection<String>, actor: String): Int {
        if (menuIds.isEmpty()) return 0
        val sql = """
            INSERT INTO ax.tb_sys_user_menu_grant (user_id, menu_id, can_write, ins_user, upd_user)
            VALUES (:empNo, :menuId, false, :actor, :actor)
            ON CONFLICT (user_id, menu_id) DO UPDATE SET upd_user = EXCLUDED.upd_user, upd_date = now()
        """.trimIndent()
        val batch = menuIds.map { MapSqlParameterSource().addValue("empNo", empNo).addValue("menuId", it).addValue("actor", actor) }
        return jdbcTemplate.batchUpdate(sql, batch.toTypedArray()).sum()
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
