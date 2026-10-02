package com.dwje.api.repository

import com.dwje.api.common.util.SqlLikeUtils
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

/**
 * 그룹웨어 부서 매핑 Repository (SY-17, 2026-09-30)
 *
 * 참조 테이블 : ax.tb_sys_dept_gw_map (V45), groupware_user.tb_user_list (MES 이관 엔진),
 *               ax.tb_sys_user, ax.tb_sys_dept, ax.tb_sys_code, ax.tb_sync_run
 *
 * `groupware_user.tb_user_list` 는 엔진이 만드는 표라 엔진을 설치하지 않은 DB 에는 없다.
 * 그때는 [sourceExists] 가 false 이고, 그룹웨어 쪽 집계는 매핑표 행만으로 0 을 채운다.
 */
@Repository
class GwDeptMapRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) {

    /** 엔진의 그룹웨어 인사정보 표가 있는지 */
    fun sourceExists(): Boolean =
        jdbcTemplate.queryForObject(
            "SELECT to_regclass('$SOURCE') IS NOT NULL", MapSqlParameterSource(), Boolean::class.java
        ) == true

    /** 이름으로 부서를 찾는다 (미배정 부서) */
    fun findDeptByName(deptNm: String): Map<String, Any?>? =
        jdbcTemplate.query(
            "SELECT dept_id, dept_nm FROM ax.tb_sys_dept WHERE dept_nm = :deptNm",
            MapSqlParameterSource("deptNm", deptNm)
        ) { rs, _ -> mapOf("deptId" to rs.getInt("dept_id"), "deptNm" to rs.getString("dept_nm")) }
            .firstOrNull()

    /**
     * 매핑 목록 — 그룹웨어 부서명(재직자 기준) ∪ 매핑표 행. 필터·쪽 나눔은 서비스가 한다(백여 행).
     *
     * @param unassignedDeptId 미배정 부서 ID. 없으면 unassignedCnt 는 0
     */
    fun findMaps(unassignedDeptId: Int?, withSource: Boolean): List<Map<String, Any?>> {
        val source = if (withSource) """
            src AS (
                SELECT l.dept_name AS nm,
                       count(*) AS active_cnt,
                       count(u.user_id) AS joined_cnt,
                       count(u.user_id) FILTER (WHERE u.dept_id = :unassignedDeptId) AS unassigned_cnt,
                       count(u.user_id) FILTER (WHERE u.dept_id = :unassignedDeptId AND u.pwd_change_req_yn = 'Y') AS unassigned_pwd_init_cnt
                  FROM $SOURCE l
                  LEFT JOIN ax.tb_sys_user u ON u.user_id = l.empno
                 WHERE NOT l.is_retired
                 GROUP BY l.dept_name
            )
        """.trimIndent() else """
            src AS (
                SELECT NULL::varchar AS nm, 0::bigint AS active_cnt, 0::bigint AS joined_cnt, 0::bigint AS unassigned_cnt,
                       0::bigint AS unassigned_pwd_init_cnt
                 WHERE false
            )
        """.trimIndent()

        val sql = """
            WITH $source,
            names AS (
                SELECT nm FROM src
                UNION
                SELECT gw_dept_nm FROM ax.tb_sys_dept_gw_map
            )
            SELECT n.nm                                  AS gw_dept_nm,
                   coalesce(s.active_cnt, 0)             AS active_cnt,
                   coalesce(s.joined_cnt, 0)             AS joined_cnt,
                   coalesce(s.unassigned_cnt, 0)         AS unassigned_cnt,
                   coalesce(s.unassigned_pwd_init_cnt, 0) AS unassigned_pwd_init_cnt,
                   m.dept_id,
                   d.dept_nm,
                   coalesce(m.join_yn, 'Y')              AS join_yn,
                   m.remark,
                   m.gw_dept_nm IS NOT NULL              AS has_row,
                   s.nm IS NOT NULL                      AS in_source,
                   to_char(m.upd_date AT TIME ZONE 'Asia/Seoul', 'YYYY-MM-DD HH24:MI') AS upd_date,
                   m.upd_user,
                   CASE WHEN m.upd_user = 'SYSTEM' THEN '시스템' ELSE coalesce(uu.user_nm, m.upd_user) END AS upd_user_nm
              FROM names n
              LEFT JOIN src s                    ON s.nm = n.nm
              LEFT JOIN ax.tb_sys_dept_gw_map m  ON m.gw_dept_nm = n.nm
              LEFT JOIN ax.tb_sys_dept d         ON d.dept_id = m.dept_id
              LEFT JOIN ax.tb_sys_user uu        ON uu.user_id = m.upd_user
             ORDER BY coalesce(s.active_cnt, 0) DESC, n.nm
        """.trimIndent()

        val params = MapSqlParameterSource("unassignedDeptId", unassignedDeptId ?: -1)
        return jdbcTemplate.query(sql, params) { rs, _ ->
            val deptId = rs.getInt("dept_id").takeUnless { rs.wasNull() }
            val joinYn = rs.getString("join_yn")
            mapOf(
                "gwDeptNm" to rs.getString("gw_dept_nm"),
                "activeCnt" to rs.getLong("active_cnt"),
                "joinedCnt" to rs.getLong("joined_cnt"),
                "unassignedCnt" to rs.getLong("unassigned_cnt"),
                // 그중 첫 로그인 비밀번호 변경이 남은 계정 — 정보로만(이동 조건 아님, R-05) (02 GWD-04)
                "unassignedPwdInitCnt" to rs.getLong("unassigned_pwd_init_cnt"),
                "deptId" to deptId,
                "deptNm" to rs.getString("dept_nm"),
                "joinYn" to joinYn,
                "state" to stateOf(joinYn, deptId),
                "remark" to rs.getString("remark"),
                "hasRow" to rs.getBoolean("has_row"),
                "inSource" to rs.getBoolean("in_source"),
                "updDate" to rs.getString("upd_date"),
                "updUser" to rs.getString("upd_user"),
                // 수정자 이름 — 엔진·배치가 쓴 행은 「시스템」 (02 GWD-10)
                "updUserNm" to rs.getString("upd_user_nm")
            )
        }
    }

    /** 매핑 행 1건 (없으면 null) */
    fun findMap(gwDeptNm: String): Map<String, Any?>? =
        jdbcTemplate.query(
            """
            SELECT m.gw_dept_nm, m.dept_id, d.dept_nm, m.join_yn, m.remark
              FROM ax.tb_sys_dept_gw_map m
              LEFT JOIN ax.tb_sys_dept d ON d.dept_id = m.dept_id
             WHERE m.gw_dept_nm = :gwDeptNm
            """.trimIndent(),
            MapSqlParameterSource("gwDeptNm", gwDeptNm)
        ) { rs, _ ->
            val deptId = rs.getInt("dept_id").takeUnless { rs.wasNull() }
            mapOf(
                "gwDeptNm" to rs.getString("gw_dept_nm"),
                "deptId" to deptId,
                "deptNm" to rs.getString("dept_nm"),
                "joinYn" to rs.getString("join_yn"),
                "state" to stateOf(rs.getString("join_yn"), deptId),
                "remark" to rs.getString("remark")
            )
        }.firstOrNull()

    /** 매핑 여러 건 (없는 이름은 빠진다) */
    fun findMapsByNames(names: Collection<String>): Map<String, Map<String, Any?>> {
        if (names.isEmpty()) return emptyMap()
        return jdbcTemplate.query(
            """
            SELECT m.gw_dept_nm, m.dept_id, d.dept_nm, m.join_yn, m.remark
              FROM ax.tb_sys_dept_gw_map m
              LEFT JOIN ax.tb_sys_dept d ON d.dept_id = m.dept_id
             WHERE m.gw_dept_nm = ANY(:names)
            """.trimIndent(),
            MapSqlParameterSource("names", names.toTypedArray())
        ) { rs, _ ->
            val deptId = rs.getInt("dept_id").takeUnless { rs.wasNull() }
            rs.getString("gw_dept_nm") to mapOf<String, Any?>(
                "gwDeptNm" to rs.getString("gw_dept_nm"), "deptId" to deptId, "deptNm" to rs.getString("dept_nm"),
                "joinYn" to rs.getString("join_yn"), "state" to stateOf(rs.getString("join_yn"), deptId), "remark" to rs.getString("remark")
            )
        }.toMap()
    }

    /**
     * 매핑 일괄 저장 (02 GWD-05) — 부서·가입 여부를 같은 값으로. [keepRemark] 면 기존 메모를 그대로 둔다.
     */
    fun upsertMaps(names: List<String>, deptId: Int?, joinYn: String, remark: String?, keepRemark: Boolean, actor: String): Int {
        val remarkSet = if (keepRemark) "" else "remark = EXCLUDED.remark,"
        val sql = """
            INSERT INTO ax.tb_sys_dept_gw_map (gw_dept_nm, dept_id, join_yn, remark, ins_user, upd_user)
            VALUES (:gwDeptNm, :deptId, :joinYn, :remark, :actor, :actor)
            ON CONFLICT (gw_dept_nm) DO UPDATE
               SET dept_id  = EXCLUDED.dept_id,
                   join_yn  = EXCLUDED.join_yn,
                   $remarkSet
                   upd_date = now(),
                   upd_user = EXCLUDED.upd_user
        """.trimIndent()
        return names.sumOf { nm ->
            jdbcTemplate.update(sql, MapSqlParameterSource().addValue("gwDeptNm", nm).addValue("deptId", deptId)
                .addValue("joinYn", joinYn).addValue("remark", if (keepRemark) null else remark).addValue("actor", actor))
        }
    }

    /** 매핑 저장 — 전체 덮어쓰기(upsert) */
    fun upsertMap(gwDeptNm: String, deptId: Int?, joinYn: String, remark: String?, actor: String): Int =
        jdbcTemplate.update(
            """
            INSERT INTO ax.tb_sys_dept_gw_map (gw_dept_nm, dept_id, join_yn, remark, ins_user, upd_user)
            VALUES (:gwDeptNm, :deptId, :joinYn, :remark, :actor, :actor)
            ON CONFLICT (gw_dept_nm) DO UPDATE
               SET dept_id  = EXCLUDED.dept_id,
                   join_yn  = EXCLUDED.join_yn,
                   remark   = EXCLUDED.remark,
                   upd_date = now(),
                   upd_user = EXCLUDED.upd_user
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("gwDeptNm", gwDeptNm)
                .addValue("deptId", deptId)
                .addValue("joinYn", joinYn)
                .addValue("remark", remark)
                .addValue("actor", actor)
        )

    /** 이어받기용 새 행 — upsert 가 아니라 INSERT 다. 같은 이름이 생기면 PK 위반(호출자가 409 로 바꾼다) (02 GWD-11) */
    fun insertMap(gwDeptNm: String, deptId: Int?, joinYn: String, remark: String?, actor: String): Int =
        jdbcTemplate.update(
            """
            INSERT INTO ax.tb_sys_dept_gw_map (gw_dept_nm, dept_id, join_yn, remark, ins_user, upd_user)
            VALUES (:gwDeptNm, :deptId, :joinYn, :remark, :actor, :actor)
            """.trimIndent(),
            MapSqlParameterSource().addValue("gwDeptNm", gwDeptNm).addValue("deptId", deptId)
                .addValue("joinYn", joinYn).addValue("remark", remark).addValue("actor", actor)
        )

    fun deleteMap(gwDeptNm: String): Int =
        jdbcTemplate.update(
            "DELETE FROM ax.tb_sys_dept_gw_map WHERE gw_dept_nm = :gwDeptNm",
            MapSqlParameterSource("gwDeptNm", gwDeptNm)
        )

    /** 미배정 부서 계정 수 */
    fun countUsersInDept(deptId: Int): Long =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ax.tb_sys_user WHERE dept_id = :deptId",
            MapSqlParameterSource("deptId", deptId), Long::class.java
        ) ?: 0

    /** 부서 안에서 첫 로그인 비밀번호 변경이 남은 계정 수 — 미배정 요약용 (02 GWD-04) */
    fun countPwdInitInDept(deptId: Int): Long =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ax.tb_sys_user WHERE dept_id = :deptId AND pwd_change_req_yn = 'Y'",
            MapSqlParameterSource("deptId", deptId), Long::class.java
        ) ?: 0

    /** 원천 표(그룹웨어 인사정보) 전체 행 수 — 요약의 이상 상태 표시(GWD-03). 원천 표가 있을 때만 부른다 */
    fun countSourceRows(): Long =
        jdbcTemplate.queryForObject("SELECT count(*) FROM $SOURCE", MapSqlParameterSource(), Long::class.java) ?: 0L

    /**
     * 그룹웨어 연동 실행 1건 — 최근 실행(`onlyDone=false`) 또는 직전 성공 실행(`onlyDone=true`, 상태 DONE).
     * 시각은 한국 시각 `YYYY-MM-DD HH24:MI`. 실행 기록이 없으면 null.
     */
    fun findGroupwareRun(onlyDone: Boolean): Map<String, Any?>? =
        jdbcTemplate.query(
            """
            SELECT to_char(started_at AT TIME ZONE 'Asia/Seoul', 'YYYY-MM-DD HH24:MI') AS started_at, state_cd, message
              FROM ax.tb_sync_run
             WHERE mode_cd = 'GROUPWARE'
               ${if (onlyDone) "AND state_cd = 'DONE'" else ""}
             ORDER BY started_at DESC
             LIMIT 1
            """.trimIndent(),
            MapSqlParameterSource()
        ) { rs, _ ->
            mapOf<String, Any?>("startedAt" to rs.getString("started_at"), "stateCd" to rs.getString("state_cd"),
                "message" to rs.getString("message"))
        }.firstOrNull()

    /**
     * 미배정 부서 계정 — 자동 가입이 아닌 계정이 옮겨져 있어도 포함한다.
     * 제안 부서 = 지금 매핑표에서 그 그룹웨어 부서가 가입 대상이고 AX 부서가 정해져 있으면 그 부서.
     *
     * @param empNos    주면 그 사번만 (재배정 대상 확인용)
     * @param limit     null 이면 전량
     * @param gwDeptNms 주면 그 그룹웨어 부서 소속만 (재배정 범위, GWD-01). 원천 표가 없으면 아무도 고르지 않는다
     */
    fun findUnassignedUsers(
        unassignedDeptId: Int,
        withSource: Boolean,
        keyword: String?,
        empNos: Collection<String>?,
        limit: Int?,
        offset: Int,
        gwDeptNms: Collection<String>? = null,
        states: Collection<String>? = null
    ): List<Map<String, Any?>> {
        val params = MapSqlParameterSource("deptId", unassignedDeptId)
        val sql = StringBuilder(unassignedSelect(withSource))
        appendUnassignedFilters(sql, params, withSource, keyword, empNos, gwDeptNms, states)
        sql.append(" ORDER BY u.ins_date DESC, u.user_id")
        if (limit != null) {
            sql.append(" LIMIT :limit OFFSET :offset")
            params.addValue("limit", limit).addValue("offset", offset)
        }

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            val suggestId = rs.getInt("suggest_dept_id").takeUnless { rs.wasNull() }
            mapOf(
                "empNo" to rs.getString("user_id"),
                "name" to rs.getString("user_nm"),
                "gwDeptNm" to rs.getString("gw_dept_nm"),
                "pos" to rs.getString("position_cd"),
                "posNm" to rs.getString("position_nm"),
                "state" to rs.getString("user_state_cd"),
                "stateNm" to rs.getString("state_nm"),
                "pwdChangeRequired" to (rs.getString("pwd_change_req_yn") == "Y"),
                // 그룹웨어 퇴사 여부(재직 행이 없음) — 원천 표가 없거나 원천에 사번이 없으면 null(모름) (02 GWD-12)
                "retired" to rs.getObject("retired") as Boolean?,
                // 정지 사유 RETIRED · REJECTED · ADMIN — 정지가 아니면 null. 계정 목록의 stateReason 과 같은 판정
                "stateReason" to rs.getString("state_reason"),
                "joinedAt" to rs.getString("joined_at"),
                "lastLoginAt" to rs.getString("last_login_at"),
                "suggestDeptId" to suggestId,
                "suggestDeptNm" to rs.getString("suggest_dept_nm")
            )
        }
    }

    fun countUnassignedUsers(unassignedDeptId: Int, withSource: Boolean, keyword: String?, states: Collection<String>? = null): Long {
        val params = MapSqlParameterSource("deptId", unassignedDeptId)
        val sql = StringBuilder("SELECT count(*) FROM (${unassignedSelect(withSource)}")
        appendUnassignedFilters(sql, params, withSource, keyword, null, null, states)
        sql.append(") t")
        return jdbcTemplate.queryForObject(sql.toString(), params, Long::class.java) ?: 0
    }

    private fun unassignedSelect(withSource: Boolean): String {
        // 같은 사번이 tb_user_list 에 두 행 있을 수 없게 엔진이 거르지만(사번 중복 제외), 조인이 행을 불리지 않게 1행으로 줄인다
        val gw = if (withSource) """
            LEFT JOIN LATERAL (
                SELECT l.dept_name, l.is_retired FROM $SOURCE l WHERE l.empno = u.user_id
                 ORDER BY l.is_retired, l.id LIMIT 1
            ) g ON true
            LEFT JOIN ax.tb_sys_dept_gw_map m ON m.gw_dept_nm = g.dept_name AND m.join_yn = 'Y'
            LEFT JOIN ax.tb_sys_dept sd       ON sd.dept_id = m.dept_id
        """.trimIndent() else """
            LEFT JOIN (SELECT NULL::varchar AS dept_name, NULL::boolean AS is_retired) g ON true
            LEFT JOIN (SELECT NULL::int AS dept_id) m ON true
            LEFT JOIN (SELECT NULL::int AS dept_id, NULL::varchar AS dept_nm) sd ON true
        """.trimIndent()

        return """
            SELECT u.user_id, u.user_nm, g.dept_name AS gw_dept_nm,
                   u.position_cd, pc.code_nm AS position_nm,
                   u.user_state_cd, sc.code_nm AS state_nm, u.pwd_change_req_yn,
                   g.is_retired AS retired,
                   CASE WHEN u.user_state_cd <> 'SUSPENDED' THEN NULL
                        WHEN g.is_retired THEN 'RETIRED'
                        WHEN (SELECT pl.detail FROM ax.tb_sys_perm_log pl WHERE pl.target_user_id = u.user_id
                               ORDER BY pl.log_at DESC LIMIT 1) LIKE '회원가입 반려%' THEN 'REJECTED'
                        ELSE 'ADMIN' END AS state_reason,
                   to_char(u.ins_date AT TIME ZONE 'Asia/Seoul', 'YYYY-MM-DD HH24:MI') AS joined_at,
                   to_char(u.last_login_at AT TIME ZONE 'Asia/Seoul', 'YYYY-MM-DD HH24:MI') AS last_login_at,
                   sd.dept_id AS suggest_dept_id, sd.dept_nm AS suggest_dept_nm
              FROM ax.tb_sys_user u
              $gw
              LEFT JOIN ax.tb_sys_code pc ON pc.group_cd = 'SYS_POSITION'   AND pc.code = u.position_cd
              LEFT JOIN ax.tb_sys_code sc ON sc.group_cd = 'SYS_USER_STATE' AND sc.code = u.user_state_cd
             WHERE u.dept_id = :deptId
        """.trimIndent()
    }

    private fun appendUnassignedFilters(
        sql: StringBuilder,
        params: MapSqlParameterSource,
        withSource: Boolean,
        keyword: String?,
        empNos: Collection<String>?,
        gwDeptNms: Collection<String>? = null,
        states: Collection<String>? = null
    ) {
        if (!states.isNullOrEmpty()) {
            sql.append(" AND u.user_state_cd = ANY(:states)")
            params.addValue("states", states.toTypedArray())
        }
        SqlLikeUtils.contains(keyword)?.let {
            sql.append(" AND (u.user_id LIKE :keyword ESCAPE '\\' OR u.user_nm LIKE :keyword ESCAPE '\\'")
            if (withSource) sql.append(" OR coalesce(g.dept_name, '') LIKE :keyword ESCAPE '\\'")
            sql.append(")")
            params.addValue("keyword", it)
        }
        if (empNos != null) {
            sql.append(" AND u.user_id = ANY(:empNos)")
            params.addValue("empNos", empNos.toTypedArray())
        }
        if (gwDeptNms != null) {
            // 그룹웨어 부서명은 엔진이 글자 그대로 비교한다 — 다듬지 않고 그대로 맞춘다
            if (withSource) {
                sql.append(" AND g.dept_name = ANY(:gwDeptNms)")
                params.addValue("gwDeptNms", gwDeptNms.toTypedArray())
            } else {
                sql.append(" AND false")
            }
        }
    }

    companion object {
        const val SOURCE = "groupware_user.tb_user_list"

        /** 화면 상태 — join_yn 'N' 이 우선, 그다음 AX 부서가 있으면 매핑됨, 그 외(행 없음 포함)는 미매핑 */
        fun stateOf(joinYn: String?, deptId: Int?): String = when {
            joinYn == "N" -> "EXCLUDED"
            deptId != null -> "MAPPED"
            else -> "UNMAPPED"
        }
    }
}
