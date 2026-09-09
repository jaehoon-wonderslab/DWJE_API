package com.dwje.api.repository

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

/**
 * 사용자 즐겨찾기 화면 Repository (`ax.tb_sys_user_favorite`, V23)
 *
 * 키는 (user_id, menu_id) 이고 정렬은 sort_seq 다. 웹은 화면 ID 를 `screenId` 라 부르지만
 * DB 컬럼은 권한·보고서 정의 테이블과 같이 `menu_id` 다.
 */
@Repository
class UserFavoriteRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) {

    /**
     * 사용자의 즐겨찾기를 정렬 순서대로 돌려준다.
     *
     * 사용 중지(`use_flg='N'`)된 메뉴는 뺀다 — 메뉴는 물리 삭제가 아니라 플래그로 내리므로
     * FK CASCADE 만으로는 목록에서 사라지지 않는다.
     */
    fun findByUser(userId: String): List<Pair<String, Int>> {
        val sql = """
            SELECT f.menu_id, f.sort_seq
            FROM ax.tb_sys_user_favorite f
            INNER JOIN ax.tb_sys_menu m ON m.menu_id = f.menu_id AND m.use_flg = 'Y'
            WHERE f.user_id = :userId
            ORDER BY f.sort_seq
        """.trimIndent()
        return jdbcTemplate.query(sql, MapSqlParameterSource("userId", userId)) { rs, _ ->
            rs.getString("menu_id") to rs.getInt("sort_seq")
        }
    }

    /** 주어진 ID 중 메뉴에 등록된 것만 돌려준다. (FK 위반을 400 으로 바꾸기 위한 사전 검사) */
    fun findExistingMenuIds(menuIds: Collection<String>): Set<String> {
        if (menuIds.isEmpty()) return emptySet()
        val sql = "SELECT menu_id FROM ax.tb_sys_menu WHERE menu_id = ANY(:ids)"
        val params = MapSqlParameterSource("ids", menuIds.toTypedArray())
        return jdbcTemplate.query(sql, params) { rs, _ -> rs.getString("menu_id") }.toSet()
    }

    /**
     * 사용자의 즐겨찾기를 통째로 바꾼다. 배열 순서가 sort_seq(0부터) 가 된다.
     *
     * 트랜잭션 안에서 DELETE → INSERT 한다. UNIQUE(user_id, sort_seq) 는 커밋 시점 검사라
     * 순서를 서로 바꾸는 갱신도 문장 중간에 걸리지 않는다.
     */
    fun replaceAll(userId: String, menuIds: List<String>) {
        jdbcTemplate.update(
            "DELETE FROM ax.tb_sys_user_favorite WHERE user_id = :userId",
            MapSqlParameterSource("userId", userId)
        )
        if (menuIds.isEmpty()) return

        val sql = """
            INSERT INTO ax.tb_sys_user_favorite (user_id, menu_id, sort_seq)
            VALUES (:userId, :menuId, :sortSeq)
        """.trimIndent()
        val batch = menuIds.mapIndexed { idx, menuId ->
            MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("menuId", menuId)
                .addValue("sortSeq", idx)
        }.toTypedArray()
        jdbcTemplate.batchUpdate(sql, batch)
    }
}
