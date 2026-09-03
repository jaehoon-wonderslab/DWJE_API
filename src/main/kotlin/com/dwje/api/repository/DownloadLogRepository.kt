package com.dwje.api.repository

import com.dwje.api.common.util.Rs
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.LocalDate

/**
 * 보고서 다운로드 이력 Repository (SY-14)
 *
 * 참조 테이블 : ax.tb_rpt_download_log, ax.tb_rpt_download_blind, ax.tb_rpt_report
 */
@Repository
class DownloadLogRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) {

    /**
     * 다운로드 이력을 기록한다. (No.224)
     *
     * @param userId    사번
     * @param deptNm    부서명
     * @param reportId  보고서 ID
     * @param menuId    화면 ID
     * @param targetNm  대상 보고서/목록명
     * @param formatCd  RPT_FORMAT — XLS / CSV / PDF
     * @param scopeDesc 조회 범위 설명
     * @param rowCnt    출력 행 수
     * @param blindCnt  마스킹 처리 건수
     * @param ipAddr    접속 IP
     * @param fileNm    생성 파일명
     * @return 생성된 다운로드 이력 ID
     */
    fun insert(
        userId: String,
        deptNm: String?,
        reportId: String?,
        menuId: String?,
        targetNm: String,
        formatCd: String,
        scopeDesc: String?,
        rowCnt: Int,
        blindCnt: Int,
        ipAddr: String?,
        fileNm: String?
    ): Long {
        val sql = """
            INSERT INTO ax.tb_rpt_download_log (
                downloaded_at, user_id, dept_nm, report_id, menu_id, target_nm,
                format_cd, scope_desc, row_cnt, blind_cnt, ip_addr, result_cd, file_nm
            ) VALUES (
                now(), :userId, :deptNm, :reportId, :menuId, :targetNm,
                :formatCd, :scopeDesc, :rowCnt, :blindCnt, CAST(:ipAddr AS inet), 'DONE', :fileNm
            )
            RETURNING dl_id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("userId", userId)
            .addValue("deptNm", deptNm)
            .addValue("reportId", reportId)
            .addValue("menuId", menuId)
            .addValue("targetNm", targetNm.take(200))
            .addValue("formatCd", formatCd)
            .addValue("scopeDesc", scopeDesc?.take(100))
            .addValue("rowCnt", rowCnt)
            .addValue("blindCnt", blindCnt)
            .addValue("ipAddr", ipAddr)
            .addValue("fileNm", fileNm?.take(200))

        return jdbcTemplate.queryForObject(sql, params, Long::class.java) ?: 0L
    }

    /**
     * 다운로드 시 마스킹된 항목별 셀 건수를 기록한다.
     *
     * @param dlId       다운로드 이력 ID
     * @param blindCells 데이터 항목 key 별 마스킹 셀 수
     */
    fun insertBlindDetail(dlId: Long, blindCells: Map<String, Int>) {
        if (blindCells.isEmpty()) return

        val sql = """
            INSERT INTO ax.tb_rpt_download_blind (dl_id, field_key, cell_cnt)
            VALUES (:dlId, :fieldKey, :cellCnt)
            ON CONFLICT (dl_id, field_key) DO UPDATE SET cell_cnt = EXCLUDED.cell_cnt
        """.trimIndent()

        val batch = blindCells.map { (fieldKey, cnt) ->
            MapSqlParameterSource()
                .addValue("dlId", dlId)
                .addValue("fieldKey", fieldKey)
                .addValue("cellCnt", cnt)
        }.toTypedArray()

        jdbcTemplate.batchUpdate(sql, batch)
    }

    /**
     * 다운로드 이력 요약을 조회한다. (No.222)
     *
     * @param from 조회 시작일
     * @param to   조회 종료일
     */
    fun findSummary(from: LocalDate, to: LocalDate): Map<String, Any?> {
        val sql = """
            SELECT
                count(*)                                                       AS total_cnt,
                count(*) FILTER (WHERE downloaded_at::date = current_date)      AS today_cnt,
                count(*) FILTER (WHERE blind_cnt > 0)                          AS blind_included_cnt,
                coalesce(sum(row_cnt), 0)                                      AS total_rows
            FROM ax.tb_rpt_download_log
            WHERE downloaded_at >= :from
              AND downloaded_at <  :toExclusive
        """.trimIndent()

        val params = periodParams(from, to)

        return jdbcTemplate.queryForObject(sql, params) { rs, _ ->
            mapOf(
                "totalCnt" to rs.getLong("total_cnt"),
                "todayCnt" to rs.getLong("today_cnt"),
                "blindIncludedCnt" to rs.getLong("blind_included_cnt"),
                "totalRows" to rs.getLong("total_rows")
            )
        } ?: emptyMap()
    }

    /**
     * 보고서별 다운로드 건수를 집계한다. (요약 화면 byReport)
     */
    fun findCountByReport(from: LocalDate, to: LocalDate, limit: Int): List<Map<String, Any?>> {
        val sql = """
            SELECT
                l.target_nm,
                l.report_id,
                count(*)                   AS dl_cnt,
                coalesce(sum(l.row_cnt),0) AS row_cnt
            FROM ax.tb_rpt_download_log l
            WHERE l.downloaded_at >= :from
              AND l.downloaded_at <  :toExclusive
            GROUP BY l.target_nm, l.report_id
            ORDER BY count(*) DESC
            LIMIT :limit
        """.trimIndent()

        val params = periodParams(from, to).addValue("limit", limit)

        return jdbcTemplate.query(sql, params) { rs, _ ->
            mapOf(
                "report" to rs.getString("target_nm"),
                "reportId" to rs.getString("report_id"),
                "cnt" to rs.getLong("dl_cnt"),
                "rowCnt" to rs.getLong("row_cnt")
            )
        }
    }

    /**
     * 사용자별 다운로드 건수를 집계한다. (요약 화면 byUser / topUser)
     */
    fun findCountByUser(from: LocalDate, to: LocalDate, limit: Int): List<Map<String, Any?>> {
        val sql = """
            SELECT
                l.user_id,
                max(u.user_nm) AS user_nm,
                max(l.dept_nm) AS dept_nm,
                count(*)       AS dl_cnt
            FROM ax.tb_rpt_download_log l
            LEFT JOIN ax.tb_sys_user u ON u.user_id = l.user_id
            WHERE l.downloaded_at >= :from
              AND l.downloaded_at <  :toExclusive
            GROUP BY l.user_id
            ORDER BY count(*) DESC
            LIMIT :limit
        """.trimIndent()

        val params = periodParams(from, to).addValue("limit", limit)

        return jdbcTemplate.query(sql, params) { rs, _ ->
            mapOf(
                "empNo" to rs.getString("user_id"),
                "name" to rs.getString("user_nm"),
                "dept" to rs.getString("dept_nm"),
                "cnt" to rs.getLong("dl_cnt")
            )
        }
    }

    /**
     * 다운로드 이력 목록을 조회한다. (No.223)
     */
    fun findLogs(
        from: LocalDate,
        to: LocalDate,
        reportId: String?,
        deptNm: String?,
        format: String?,
        limit: Int,
        offset: Int
    ): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT
                l.dl_id, l.downloaded_at, l.user_id, u.user_nm, l.dept_nm,
                l.target_nm, l.report_id, l.format_cd, l.scope_desc,
                l.row_cnt, l.blind_cnt, host(l.ip_addr) AS ip_addr, l.result_cd
            FROM ax.tb_rpt_download_log l
            LEFT JOIN ax.tb_sys_user u ON u.user_id = l.user_id
            WHERE l.downloaded_at >= :from
              AND l.downloaded_at <  :toExclusive
            """.trimIndent()
        )

        val params = periodParams(from, to)
        appendLogFilters(sql, params, reportId, deptNm, format)

        sql.append("\nORDER BY l.downloaded_at DESC\nLIMIT :limit OFFSET :offset")
        params.addValue("limit", limit).addValue("offset", offset)

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "dlId" to rs.getLong("dl_id"),
                "ts" to Rs.dateTime(rs, "downloaded_at"),
                "empNo" to rs.getString("user_id"),
                "name" to rs.getString("user_nm"),
                "dept" to rs.getString("dept_nm"),
                "report" to rs.getString("target_nm"),
                "reportId" to rs.getString("report_id"),
                "format" to rs.getString("format_cd"),
                "scope" to rs.getString("scope_desc"),
                "rowCnt" to rs.getInt("row_cnt"),
                "blindCnt" to rs.getInt("blind_cnt"),
                "ip" to rs.getString("ip_addr"),
                "result" to rs.getString("result_cd")
            )
        }
    }

    /** 다운로드 이력 전체 건수 */
    fun countLogs(from: LocalDate, to: LocalDate, reportId: String?, deptNm: String?, format: String?): Long {
        val sql = StringBuilder(
            """
            SELECT count(*)
            FROM ax.tb_rpt_download_log l
            WHERE l.downloaded_at >= :from
              AND l.downloaded_at <  :toExclusive
            """.trimIndent()
        )

        val params = periodParams(from, to)
        appendLogFilters(sql, params, reportId, deptNm, format)

        return jdbcTemplate.queryForObject(sql.toString(), params, Long::class.java) ?: 0L
    }

    /**
     * 보존 정책 현황을 조회한다. (No.225)
     *
     * @param retentionYears 보존 연수
     */
    fun findRetentionStatus(retentionYears: Int): Map<String, Any?> {
        val sql = """
            SELECT
                count(*)                                                                   AS total_cnt,
                count(*) FILTER (
                    WHERE downloaded_at < now() - make_interval(years => :retentionYears)
                )                                                                          AS archive_target_cnt,
                min(downloaded_at)                                                         AS oldest_at
            FROM ax.tb_rpt_download_log
        """.trimIndent()

        val params = MapSqlParameterSource("retentionYears", retentionYears)

        return jdbcTemplate.queryForObject(sql, params) { rs, _ ->
            mapOf(
                "retentionYears" to retentionYears,
                "totalCnt" to rs.getLong("total_cnt"),
                "archivedCnt" to rs.getLong("archive_target_cnt"),
                "oldestAt" to Rs.dateTime(rs, "oldest_at")
            )
        } ?: emptyMap()
    }

    /** 목록/건수 공통 동적 조건 */
    private fun appendLogFilters(
        sql: StringBuilder,
        params: MapSqlParameterSource,
        reportId: String?,
        deptNm: String?,
        format: String?
    ) {
        if (!reportId.isNullOrBlank()) {
            sql.append(" AND l.report_id = :reportId")
            params.addValue("reportId", reportId.trim())
        }
        if (!deptNm.isNullOrBlank()) {
            sql.append(" AND l.dept_nm = :deptNm")
            params.addValue("deptNm", deptNm.trim())
        }
        if (!format.isNullOrBlank()) {
            sql.append(" AND l.format_cd = :format")
            params.addValue("format", format.trim().uppercase())
        }
    }

    /** 조회 기간 공통 파라미터 */
    private fun periodParams(from: LocalDate, to: LocalDate): MapSqlParameterSource =
        MapSqlParameterSource()
            .addValue("from", from.atStartOfDay())
            .addValue("toExclusive", to.plusDays(1).atStartOfDay())
}
