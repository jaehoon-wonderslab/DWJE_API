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
                       count(u.user_id) FILTER (WHERE u.dept_id = :unassignedDeptId) AS unassigned_cnt
                  FROM $SOURCE l
                  LEFT JOIN ax.tb_sys_user u ON u.user_id = l.empno
                 WHERE NOT l.is_retired
                 GROUP BY l.dept_name
            )
        """.trimIndent() else """
            src AS (
                SELECT NULL::varchar AS nm, 0::bigint AS active_cnt, 0::bigint AS joined_cnt, 0::bigint AS unassigned_cnt
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
                   m.dept_id,
                   d.dept_nm,
                   coalesce(m.join_yn, 'Y')              AS join_yn,
                   m.remark,
                   m.gw_dept_nm IS NOT NULL              AS has_row,
                   s.nm IS NOT NULL                      AS in_source,
                   to_char(m.upd_date AT TIME ZONE 'Asia/Seoul', 'YYYY-MM-DD HH24:MI') AS upd_date,
                   m.upd_user
              FROM names n
              LEFT JOIN src s                    ON s.nm = n.nm
              LEFT JOIN ax.tb_sys_dept_gw_map m  ON m.gw_dept_nm = n.nm
              LEFT JOIN ax.tb_sys_dept d         ON d.dept_id = m.dept_id
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
                "deptId" to deptId,
                "deptNm" to rs.getString("dept_nm"),
                "joinYn" to joinYn,
                "state" to stateOf(joinYn, deptId),
                "remark" to rs.getString("remark"),
                "hasRow" to rs.getBoolean("has_row"),
                "inSource" to rs.getBoolean("in_source"),
                "updDate" to rs.getString("upd_date"),
                "updUser" to rs.getString("upd_user")
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

    /**
     * 가장 최근 그룹웨어 동기화 실행 — 시작 시각과 message. 없으면 null.
     * 엔진이 message 에 "인사정보 요약 · AX 가입 요약" 을 남긴다.
     */
    fun findLastGroupwareRun(): Pair<String?, String?>? =
        jdbcTemplate.query(
            """
            SELECT to_char(started_at AT TIME ZONE 'Asia/Seoul', 'YYYY-MM-DD HH24:MI') AS started_at, message
              FROM ax.tb_sync_run
             WHERE mode_cd = 'GROUPWARE'
             ORDER BY started_at DESC
             LIMIT 1
            """.trimIndent(),
            MapSqlParameterSource()
        ) { rs, _ -> rs.getString("started_at") to rs.getString("message") }.firstOrNull()

    /**
     * 미배정 부서 계정 — 자동 가입이 아닌 계정이 옮겨져 있어도 포함한다.
     * 제안 부서 = 지금 매핑표에서 그 그룹웨어 부서가 가입 대상이고 AX 부서가 정해져 있으면 그 부서.
     *
     * @param empNos 주면 그 사번만 (재배정 대상 확인용)
     * @param limit  null 이면 전량
     */
    fun findUnassignedUsers(
        unassignedDeptId: Int,
        withSource: Boolean,
        keyword: String?,
        empNos: Collection<String>?,
        limit: Int?,
        offset: Int
    ): List<Map<String, Any?>> {
        val params = MapSqlParameterSource("deptId", unassignedDeptId)
        val sql = StringBuilder(unassignedSelect(withSource))
        appendUnassignedFilters(sql, params, withSource, keyword, empNos)
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
                "joinedAt" to rs.getString("joined_at"),
                "lastLoginAt" to rs.getString("last_login_at"),
                "suggestDeptId" to suggestId,
                "suggestDeptNm" to rs.getString("suggest_dept_nm")
            )
        }
    }

    fun countUnassignedUsers(unassignedDeptId: Int, withSource: Boolean, keyword: String?): Long {
        val params = MapSqlParameterSource("deptId", unassignedDeptId)
        val sql = StringBuilder("SELECT count(*) FROM (${unassignedSelect(withSource)}")
        appendUnassignedFilters(sql, params, withSource, keyword, null)
        sql.append(") t")
        return jdbcTemplate.queryForObject(sql.toString(), params, Long::class.java) ?: 0
    }

    private fun unassignedSelect(withSource: Boolean): String {
        // 같은 사번이 tb_user_list 에 두 행 있을 수 없게 엔진이 거르지만(사번 중복 제외), 조인이 행을 불리지 않게 1행으로 줄인다
        val gw = if (withSource) """
            LEFT JOIN LATERAL (
                SELECT l.dept_name FROM $SOURCE l WHERE l.empno = u.user_id
                 ORDER BY l.is_retired, l.id LIMIT 1
            ) g ON true
            LEFT JOIN ax.tb_sys_dept_gw_map m ON m.gw_dept_nm = g.dept_name AND m.join_yn = 'Y'
            LEFT JOIN ax.tb_sys_dept sd       ON sd.dept_id = m.dept_id
        """.trimIndent() else """
            LEFT JOIN (SELECT NULL::varchar AS dept_name) g ON true
            LEFT JOIN (SELECT NULL::int AS dept_id) m ON true
            LEFT JOIN (SELECT NULL::int AS dept_id, NULL::varchar AS dept_nm) sd ON true
        """.trimIndent()

        return """
            SELECT u.user_id, u.user_nm, g.dept_name AS gw_dept_nm,
                   u.position_cd, pc.code_nm AS position_nm,
                   u.user_state_cd, sc.code_nm AS state_nm,
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
        empNos: Collection<String>?
    ) {
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
