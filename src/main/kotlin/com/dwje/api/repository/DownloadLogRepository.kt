package com.dwje.api.repository

import com.dwje.api.common.util.ReportFormat
import com.dwje.api.common.util.Rs
import com.fasterxml.jackson.databind.ObjectMapper
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
    private val jdbcTemplate: NamedParameterJdbcTemplate,
    private val objectMapper: ObjectMapper
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
     * @param originCd    출처 — CLIENT(브라우저가 만든 파일) | SERVER(서버가 만든 파일) (DLG-05)
     * @param deptId      내려받은 시점의 부서 ID 스냅샷 (DLG-01)
     * @param scopeCd     내려받은 범위 — VIEW(조회 목록) | ALL(전체) | null(옛 기록) (DLG-15)
     * @param condSummary 조회 조건 요약 (500자, DLG-15)
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
        fileNm: String?,
        paramsJson: String?,
        fileSize: Long?,
        originCd: String? = null,
        deptId: Int? = null,
        scopeCd: String? = null,
        condSummary: String? = null
    ): Long {
        val sql = """
            INSERT INTO ax.tb_rpt_download_log (
                downloaded_at, user_id, dept_nm, report_id, menu_id, target_nm,
                format_cd, scope_desc, row_cnt, blind_cnt, ip_addr, result_cd, file_nm,
                params_json, file_size, origin_cd, dept_id, scope_cd, cond_summary
            ) VALUES (
                now(), :userId, :deptNm, :reportId, :menuId, :targetNm,
                :formatCd, :scopeDesc, :rowCnt, :blindCnt, CAST(:ipAddr AS inet), 'DONE', :fileNm,
                CAST(:paramsJson AS jsonb), :fileSize, :originCd, :deptId, :scopeCd, :condSummary
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
            // 문서를 저장하지 않으므로 "어떤 조건으로 만든 파일인지" 는 이 스냅샷이 유일한 단서다.
            .addValue("paramsJson", paramsJson)
            .addValue("fileSize", fileSize)
            .addValue("originCd", originCd)
            .addValue("deptId", deptId)
            .addValue("scopeCd", scopeCd)
            .addValue("condSummary", condSummary?.take(500))

        return jdbcTemplate.queryForObject(sql, params, Long::class.java) ?: 0L
    }

    /** 화면 ID 가 등록돼 있는지 (사용 여부와 무관 — 권한 검사가 실질 차단이다, DLG-03·05) */
    fun menuExists(menuId: String): Boolean =
        (jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ax.tb_sys_menu WHERE menu_id = :menuId",
            MapSqlParameterSource("menuId", menuId), Long::class.java
        ) ?: 0L) > 0

    /** 화면 ID 에 묶인 보고서 정의 ID (RPT_*). 보고서 화면이 아니면 null (DLG-03) */
    fun findReportIdByMenu(menuId: String): String? =
        jdbcTemplate.query(
            "SELECT report_id FROM ax.tb_rpt_report WHERE menu_id = :menuId LIMIT 1",
            MapSqlParameterSource("menuId", menuId)
        ) { rs, _ -> rs.getString("report_id") }.firstOrNull()

    /** 보고서 정의 ID(RPT_*)의 화면 ID. 정의가 없으면 null */
    fun findMenuByReportId(reportId: String): String? =
        jdbcTemplate.query(
            "SELECT menu_id FROM ax.tb_rpt_report WHERE report_id = :reportId",
            MapSqlParameterSource("reportId", reportId)
        ) { rs, _ -> rs.getString("menu_id") }.firstOrNull()

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
            ON CONFLICT (dl_id, field_key) DO NOTHING
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
    fun findSummary(from: LocalDate, to: LocalDate, filter: LogFilter = LogFilter()): Map<String, Any?> {
        // 목록과 같은 FROM·조건 — 카드 총 건수와 목록 meta.total 이 같다 (10 DLG-06). 금일은 한국 시각 자정부터
        val sql = StringBuilder(
            """
            SELECT
                count(*)                                                       AS total_cnt,
                count(*) FILTER (WHERE l.downloaded_at >= (date_trunc('day', now() AT TIME ZONE 'Asia/Seoul')) AT TIME ZONE 'Asia/Seoul') AS today_cnt,
                count(*) FILTER (WHERE l.blind_cnt > 0)                        AS blind_included_cnt,
                coalesce(sum(l.row_cnt), 0)                                    AS total_rows
            """.trimIndent() + "\n" + logFrom
        )
        val params = periodParams(from, to)
        appendLogFilters(sql, params, filter)

        return jdbcTemplate.queryForObject(sql.toString(), params) { rs, _ ->
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
     * 목록 조회 조건 (No.223)
     *
     * @param menuId  화면 ID — 서버 기록의 menu_id, 과거 브라우저가 report_id 에 넣은 화면 ID, RPT_* 의 화면을 모두 잡는다 (DLG-03)
     * @param dept    부서 — 숫자면 부서 ID(새 기록은 dept_id, 과거 기록은 그 부서의 지금 이름), 숫자가 아니면 부서명 (DLG-01)
     * @param format  형식 코드 — 과거 표시명 기록도 같은 코드로 걸린다 (DLG-02)
     * @param scopeCd VIEW | ALL | UNKNOWN(범위 코드가 없는 옛 기록) (DLG-15)
     */
    data class LogFilter(
        val reportId: String? = null,
        val menuId: String? = null,
        val dept: String? = null,
        val format: String? = null,
        val scopeCd: String? = null,
        /** 사번·이름·부서·보고서·화면명·조회 조건·파일명 부분 일치 */
        val keyword: String? = null,
        /** true 면 비공개 칸이 있는 기록만 */
        val blindOnly: Boolean = false,
        /** 한 건(상세) */
        val dlId: Long? = null,
        /** 사번 정확 일치 (10 DLG-09) */
        val empNo: String? = null,
        /** 생성 경로 — CLIENT | SERVER | UNKNOWN(값 없음, 옛 기록) (10 DLG-09) */
        val origin: String? = null
    )

    /** 목록·건수 공통 FROM — 과거 기록의 화면 ID 를 report_id·보고서 정의에서 찾아 menu_resolved 로 맞춘다 */
    private val logFrom = """
        FROM ax.tb_rpt_download_log l
        LEFT JOIN ax.tb_sys_user u   ON u.user_id = l.user_id
        LEFT JOIN ax.tb_sys_menu m2  ON l.menu_id IS NULL AND m2.menu_id = l.report_id
        LEFT JOIN ax.tb_rpt_report r ON r.report_id = l.report_id
        LEFT JOIN ax.tb_sys_menu m   ON m.menu_id = coalesce(l.menu_id, m2.menu_id, r.menu_id)
        WHERE l.downloaded_at >= :from
          AND l.downloaded_at <  :toExclusive
    """.trimIndent()

    /**
     * 다운로드 이력 목록을 조회한다. (No.223)
     */
    fun findLogs(from: LocalDate, to: LocalDate, filter: LogFilter, limit: Int, offset: Int): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT
                l.dl_id, l.downloaded_at, l.user_id, u.user_nm, l.dept_nm, l.dept_id,
                l.target_nm, l.report_id, l.format_cd, ${ReportFormat.SQL_NORMALIZED} AS format_norm, l.scope_desc,
                l.row_cnt, l.blind_cnt, host(l.ip_addr) AS ip_addr, l.result_cd,
                l.file_nm, l.params_json::text AS params_json, l.file_size,
                l.origin_cd, l.scope_cd, l.cond_summary,
                coalesce(l.menu_id, m2.menu_id, r.menu_id) AS menu_resolved, m.menu_nm
            """.trimIndent() + "\n" + logFrom
        )

        val params = periodParams(from, to)
        appendLogFilters(sql, params, filter)

        sql.append("\nORDER BY l.downloaded_at DESC, l.dl_id DESC\nLIMIT :limit OFFSET :offset")
        params.addValue("limit", limit).addValue("offset", offset)

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "dlId" to rs.getLong("dl_id"),
                "ts" to Rs.dateTime(rs, "downloaded_at"),
                "empNo" to rs.getString("user_id"),
                "name" to rs.getString("user_nm"),
                "dept" to rs.getString("dept_nm"),
                "deptId" to (rs.getObject("dept_id") as? Number)?.toInt(),
                "report" to rs.getString("target_nm"),
                "menuId" to rs.getString("menu_resolved"),
                "menuNm" to rs.getString("menu_nm"),
                "reportId" to rs.getString("report_id"),
                "format" to rs.getString("format_norm"),
                "formatRaw" to rs.getString("format_cd"),
                "origin" to rs.getString("origin_cd"),
                "scope" to rs.getString("scope_desc"),
                "scopeCd" to rs.getString("scope_cd"),
                // 옛 기록은 범위 문구(scope_desc)가 조건 요약이었다
                "condSummary" to (rs.getString("cond_summary") ?: rs.getString("scope_desc")),
                "rowCnt" to rs.getInt("row_cnt"),
                "blindCnt" to rs.getInt("blind_cnt"),
                "fileNm" to rs.getString("file_nm"),
                // 문서를 저장하지 않으므로 이 스냅샷이 같은 산출물을 다시 만들 유일한 단서다.
                "params" to rs.getString("params_json")?.let {
                    runCatching { objectMapper.readValue(it, Map::class.java) }.getOrNull()
                },
                "fileSize" to rs.getObject("file_size") as? Long,
                "ip" to rs.getString("ip_addr"),
                "result" to rs.getString("result_cd")
            )
        }
    }

    /** 한 건의 비공개 칸 — 항목별 (10 DLG 상세) */
    fun findBlindFields(dlId: Long): List<Map<String, Any?>> =
        jdbcTemplate.query(
            """
            SELECT b.field_key, f.field_nm, b.cell_cnt
              FROM ax.tb_rpt_download_blind b
              LEFT JOIN ax.tb_sys_data_field f ON f.field_key = b.field_key
             WHERE b.dl_id = :dlId
             ORDER BY b.cell_cnt DESC, b.field_key
            """.trimIndent(),
            MapSqlParameterSource("dlId", dlId)
        ) { rs, _ -> mapOf("fieldKey" to rs.getString("field_key"), "fieldNm" to rs.getString("field_nm"), "cellCnt" to rs.getInt("cell_cnt")) }

    /** 다운로드 이력 전체 건수 */
    fun countLogs(from: LocalDate, to: LocalDate, filter: LogFilter): Long {
        val sql = StringBuilder("SELECT count(*)\n$logFrom")
        val params = periodParams(from, to)
        appendLogFilters(sql, params, filter)
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
                min(downloaded_at)                                                         AS oldest_at,
                (SELECT count(*) FROM ax.tb_rpt_download_log_arch)                         AS archived_cnt,
                (SELECT max(archived_at) FROM ax.tb_rpt_download_log_arch)                 AS last_archive_at
            FROM ax.tb_rpt_download_log
        """.trimIndent()

        val params = MapSqlParameterSource("retentionYears", retentionYears)

        return jdbcTemplate.queryForObject(sql, params) { rs, _ ->
            mapOf(
                "retentionYears" to retentionYears,
                "totalCnt" to rs.getLong("total_cnt"),
                // 보존 경과 — 다음 아카이브 대상 (10 DLG-07). archivedCnt 는 아카이브 표에 이미 옮긴 건수다
                "expiredCnt" to rs.getLong("archive_target_cnt"),
                "archiveTargetCnt" to rs.getLong("archive_target_cnt"),
                "archivedCnt" to rs.getLong("archived_cnt"),
                "oldestAt" to Rs.dateTime(rs, "oldest_at"),
                "lastArchiveAt" to Rs.dateTime(rs, "last_archive_at")
            )
        } ?: emptyMap()
    }

    /** 목록/건수 공통 동적 조건 */
    private fun appendLogFilters(sql: StringBuilder, params: MapSqlParameterSource, f: LogFilter) {
        f.dlId?.let {
            sql.append(" AND l.dl_id = :dlId")
            params.addValue("dlId", it)
        }
        com.dwje.api.common.util.SqlLikeUtils.contains(f.keyword)?.let {
            sql.append(
                " AND (l.user_id LIKE :kw ESCAPE '\\' OR coalesce(u.user_nm,'') LIKE :kw ESCAPE '\\'" +
                    " OR coalesce(l.dept_nm,'') LIKE :kw ESCAPE '\\' OR coalesce(l.target_nm,'') LIKE :kw ESCAPE '\\'" +
                    " OR coalesce(m.menu_nm,'') LIKE :kw ESCAPE '\\' OR coalesce(l.cond_summary, l.scope_desc, '') LIKE :kw ESCAPE '\\'" +
                    " OR coalesce(l.file_nm,'') LIKE :kw ESCAPE '\\')"
            )
            params.addValue("kw", it)
        }
        if (f.blindOnly) sql.append(" AND l.blind_cnt > 0")
        f.empNo?.trim()?.takeIf { it.isNotEmpty() }?.let {
            sql.append(" AND l.user_id = :empNo")
            params.addValue("empNo", it)
        }
        when (f.origin?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }) {
            null -> Unit
            "UNKNOWN" -> sql.append(" AND l.origin_cd IS NULL")
            else -> {
                sql.append(" AND l.origin_cd = :originCd")
                params.addValue("originCd", f.origin.trim().uppercase())
            }
        }
        f.reportId?.trim()?.takeIf { it.isNotEmpty() }?.let {
            sql.append(" AND l.report_id = :reportId")
            params.addValue("reportId", it)
        }
        f.menuId?.trim()?.takeIf { it.isNotEmpty() }?.let {
            sql.append(" AND coalesce(l.menu_id, m2.menu_id, r.menu_id) = :menuId")
            params.addValue("menuId", it)
        }
        f.dept?.trim()?.takeIf { it.isNotEmpty() }?.let { dept ->
            val deptId = dept.toIntOrNull()
            if (deptId != null) {
                // 새 기록은 부서 ID 스냅샷, 과거 기록은 그 부서의 지금 이름으로 찾는다
                sql.append(
                    " AND (l.dept_id = :deptId OR (l.dept_id IS NULL AND l.dept_nm = " +
                        "(SELECT d.dept_nm FROM ax.tb_sys_dept d WHERE d.dept_id = :deptId)))"
                )
                params.addValue("deptId", deptId)
            } else {
                sql.append(" AND l.dept_nm = :deptNm")
                params.addValue("deptNm", dept)
            }
        }
        f.format?.trim()?.takeIf { it.isNotEmpty() }?.let {
            sql.append(" AND ${ReportFormat.SQL_NORMALIZED} = :format")
            params.addValue("format", ReportFormat.normalize(it) ?: it.uppercase())
        }
        when (f.scopeCd?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }) {
            null -> Unit
            "UNKNOWN" -> sql.append(" AND l.scope_cd IS NULL")
            else -> {
                sql.append(" AND l.scope_cd = :scopeCd")
                params.addValue("scopeCd", f.scopeCd.trim().uppercase())
            }
        }
    }

    /** 조회 기간 공통 파라미터 */
    private fun periodParams(from: LocalDate, to: LocalDate): MapSqlParameterSource =
        MapSqlParameterSource()
            .addValue("from", from.atStartOfDay())
            .addValue("toExclusive", to.plusDays(1).atStartOfDay())
}
