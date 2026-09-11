package com.dwje.api.repository

import com.dwje.api.common.util.DateUtils
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

/**
 * 보고서 사용 횟수 Repository (`ax.tb_rpt_usage`, V24)
 *
 * 계정 × 보고서 화면 단위로 "몇 번 만들었고 마지막이 언제였는지" 만 남긴다.
 * `/menu/report` 의 자주 쓰는 보고서 버튼(최대 5개)이 읽는다.
 */
@Repository
class ReportUsageRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) {

    /**
     * 사용자의 사용 기록을 횟수 내림차순 → 최근 사용 내림차순으로 돌려준다.
     *
     * 사용 중지(`use_flg='N'`)된 메뉴는 뺀다. 메뉴 권한 필터는 서비스가 한다 —
     * 행 수가 보고서 화면 수(수 개~수십 개)라 전부 읽고 걸러도 부담이 없다.
     */
    fun findByUser(userId: String): List<UsageRow> {
        val sql = """
            SELECT r.menu_id, r.use_cnt, r.last_used_at
            FROM ax.tb_rpt_usage r
            INNER JOIN ax.tb_sys_menu m ON m.menu_id = r.menu_id AND m.use_flg = 'Y'
            WHERE r.user_id = :userId
            ORDER BY r.use_cnt DESC, r.last_used_at DESC, r.menu_id
        """.trimIndent()
        return jdbcTemplate.query(sql, MapSqlParameterSource("userId", userId)) { rs, _ ->
            UsageRow(
                menuId = rs.getString("menu_id"),
                useCount = rs.getInt("use_cnt"),
                lastUsedAt = DateUtils.format(rs.getObject("last_used_at"))
            )
        }
    }

    /** 메뉴에 등록된 ID 인지 확인한다. (FK 위반을 400 으로 바꾸기 위한 사전 검사) */
    fun menuExists(menuId: String): Boolean {
        val sql = "SELECT count(*) FROM ax.tb_sys_menu WHERE menu_id = :menuId"
        return (jdbcTemplate.queryForObject(sql, MapSqlParameterSource("menuId", menuId), Long::class.java) ?: 0L) > 0
    }

    /** 사용 1회를 기록한다 — 없으면 1 로 만들고, 있으면 +1 과 마지막 사용 시각 갱신. */
    fun increment(userId: String, menuId: String) {
        val sql = """
            INSERT INTO ax.tb_rpt_usage (user_id, menu_id, use_cnt, last_used_at)
            VALUES (:userId, :menuId, 1, now())
            ON CONFLICT (user_id, menu_id) DO UPDATE SET
                use_cnt      = ax.tb_rpt_usage.use_cnt + 1,
                last_used_at = now()
        """.trimIndent()
        val params = MapSqlParameterSource().addValue("userId", userId).addValue("menuId", menuId)
        jdbcTemplate.update(sql, params)
    }
}

/** 사용 기록 한 건 */
data class UsageRow(
    val menuId: String,
    val useCount: Int,
    val lastUsedAt: String?
)
