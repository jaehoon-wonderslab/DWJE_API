package com.dwje.api.repository

import com.dwje.api.common.util.Rs
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime

/**
 * 감사·다운로드 기록 아카이브 Repository (09 AUD-11 · 10 DLG-07)
 *
 * 원본 표는 고칠 수 없다(V51·V62 트리거). DELETE 는 같은 트랜잭션에 `ax.audit_purge = 'on'` 이 있을 때만 통과하므로,
 * 메서드는 **호출자 트랜잭션 안에서** 설정을 켜고 한 문장(DELETE … RETURNING → INSERT)으로 옮긴다 — 빠지거나 두 번 들어가지 않는다.
 * 컬럼은 이름으로 나열한다. 원본에 컬럼이 늘면 아카이브 표도 마이그레이션으로 같이 늘린다.
 */
@Repository
class AuditArchiveRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) {

    /** 원천 — 표 이름, 기준 시각 컬럼, 옮길 컬럼 */
    enum class Source(val table: String, val tsColumn: String, val columns: String) {
        AUDIT(
            "ax.tb_log_audit", "log_at",
            "audit_id, log_at, log_type_cd, user_id, dept_nm, menu_id, field_key, target_desc, result_cd, masked_cnt, remark, " +
                "ip_addr, plant_cd, wc_cd, lot_no, serial_no, item_cd, user_agent"
        ),
        PERM(
            "ax.tb_sys_perm_log", "log_at",
            "log_id, log_at, act_cd, target_kind_cd, target_dept_id, target_user_id, target_nm, detail, actor_user_id, actor_dept_nm, audit_id"
        ),
        LOGIN(
            "ax.tb_sys_login_hist", "login_at",
            "login_id, user_id, login_at, logout_at, result_cd, fail_reason, ip_addr, user_agent, dept_nm"
        ),
        DOWNLOAD(
            "ax.tb_rpt_download_log", "downloaded_at",
            "dl_id, downloaded_at, user_id, dept_nm, report_id, menu_id, target_nm, format_cd, scope_desc, row_cnt, blind_cnt, " +
                "ip_addr, result_cd, file_nm, params_json, file_size, dept_id, origin_cd, scope_cd, cond_summary"
        );

        val archTable: String get() = "${table}_arch"
    }

    /** 보존 기준 시각 — DB 시각으로 구한다(앱 시간대와 DB UTC 의 혼동을 피한다) */
    fun cutoff(years: Int): OffsetDateTime =
        jdbcTemplate.queryForObject(
            "SELECT now() - make_interval(years => :y)", MapSqlParameterSource("y", years), OffsetDateTime::class.java
        )!!

    /**
     * 다른 인스턴스가 같은 배치를 돌리고 있는지 — 트랜잭션 잠금이라 커밋·롤백 때 풀린다.
     * @return false 면 이번 묶음을 건너뛴다
     */
    fun tryLock(): Boolean =
        jdbcTemplate.queryForObject(
            "SELECT pg_try_advisory_xact_lock(hashtext('ax.audit_archive'))", MapSqlParameterSource(), Boolean::class.java
        ) ?: false

    /**
     * 한 묶음 옮기기 — [cutoff] 보다 오래된 행을 최대 [limit] 건. 호출자 트랜잭션 안에서 돈다.
     * 다운로드는 비공개 항목 내역(blind)을 먼저 복사하고 원본 log 를 지운다(blind 는 CASCADE 로 함께 지워진다).
     *
     * @return 옮긴 행 수 (다운로드는 log 행 수)
     */
    fun archiveBefore(source: Source, cutoff: OffsetDateTime, limit: Int): Int {
        jdbcTemplate.jdbcTemplate.execute("SET LOCAL ax.audit_purge = 'on'")
        val params = MapSqlParameterSource().addValue("cutoff", cutoff).addValue("limit", limit)
        val key = source.columns.substringBefore(',').trim()
        if (source == Source.DOWNLOAD) {
            jdbcTemplate.update(
                """
                INSERT INTO ax.tb_rpt_download_blind_arch (dl_id, field_key, cell_cnt)
                SELECT b.dl_id, b.field_key, b.cell_cnt
                FROM ax.tb_rpt_download_blind b
                WHERE b.dl_id IN (
                    SELECT dl_id FROM ax.tb_rpt_download_log WHERE downloaded_at < :cutoff ORDER BY downloaded_at, dl_id LIMIT :limit
                )
                """.trimIndent(),
                params
            )
        }
        val sql = """
            WITH moved AS (
                DELETE FROM ${source.table}
                 WHERE $key IN (SELECT $key FROM ${source.table} WHERE ${source.tsColumn} < :cutoff ORDER BY ${source.tsColumn}, $key LIMIT :limit)
                RETURNING ${source.columns}
            )
            INSERT INTO ${source.archTable} (${source.columns}, archived_at)
            SELECT ${source.columns}, now() FROM moved
        """.trimIndent()
        return jdbcTemplate.update(sql, params)
    }

    /** 원천별 현황 — 전체·보존 경과·가장 오래된 시각·아카이브 건수 (보존 정책 응답) */
    fun status(source: Source, cutoff: OffsetDateTime): Map<String, Any?> =
        jdbcTemplate.queryForObject(
            """
            SELECT count(*) AS total_cnt,
                   count(*) FILTER (WHERE ${source.tsColumn} < :cutoff) AS expired_cnt,
                   min(${source.tsColumn}) AS oldest_at,
                   (SELECT count(*) FROM ${source.archTable}) AS archived_cnt,
                   (SELECT max(archived_at) FROM ${source.archTable}) AS last_archived_at
            FROM ${source.table}
            """.trimIndent(),
            MapSqlParameterSource("cutoff", cutoff)
        ) { rs, _ ->
            mapOf(
                "src" to source.name,
                "totalCnt" to rs.getLong("total_cnt"),
                "expiredCnt" to rs.getLong("expired_cnt"),
                "archivedCnt" to rs.getLong("archived_cnt"),
                "oldestAt" to Rs.dateTime(rs, "oldest_at"),
                "lastArchivedAt" to Rs.dateTime(rs, "last_archived_at")
            )
        }!!

    /** 마지막 아카이브 실행 시각 — 배치가 남긴 AUTO_GEN 감사 행 */
    fun findLastArchiveAt(): String? =
        jdbcTemplate.query(
            "SELECT max(log_at) AS at FROM ax.tb_log_audit WHERE log_type_cd = 'AUTO_GEN' AND target_desc LIKE '감사 기록 아카이브%'",
            MapSqlParameterSource()
        ) { rs, _ -> Rs.dateTime(rs, "at") }.firstOrNull()
}
