package com.dwje.api.repository

import com.dwje.api.common.util.Rs
import com.dwje.api.common.util.SensitiveText
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 데이터 연동 이력 Repository (SY-15)
 *
 * 참조 테이블 : ax.tb_sync_map, ax.tb_sync_job, ax.tb_sync_job_error,
 *               ax.tb_sync_schema_drift, ax.tb_alm_cond
 */
/** 이관 실패 알림 지표 코드(V61) — 실패 알림 조건 판정 (12 SYN-04) */
private const val SYNC_ALERT_METRICS = "'SYNC_FAIL_RATE', 'SYNC_STALE_MIN'"

/** 실행 구분 — MES(그룹웨어 외 전부) · GROUPWARE (12 SYN-09) */
private const val SOURCE_SQL = "(:source::varchar IS NULL OR (:source = 'GROUPWARE' AND r.mode_cd = 'GROUPWARE') OR (:source = 'MES' AND r.mode_cd <> 'GROUPWARE'))"

/** 작업 기간 조건 — 대기 작업은 includePending 이면 기간과 무관하게 넣는다 (12 SYN-08) */
private const val JOB_PERIOD_SQL = "WHERE ((coalesce(j.started_at, j.scheduled_at) >= :from AND coalesce(j.started_at, j.scheduled_at) < :toExclusive)" +
    " OR (:includePending AND j.state_cd = 'PENDING'))"

@Repository
class SyncRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) {

    /**
     * 연동 요약을 조회한다. (No.226)
     *
     * @param date 기준일
     */
    fun findSummary(date: LocalDate): Map<String, Any?> {
        val sql = """
            SELECT
                coalesce(sum(j.ok_rows), 0)                                       AS today_rows,
                coalesce(sum(j.ng_rows), 0)                                       AS failed_rows,
                count(*) FILTER (WHERE j.state_cd = 'FAIL')                       AS failed_job_cnt,
                count(*) FILTER (WHERE j.state_cd = 'RUNNING')                    AS running_job_cnt,
                count(*)                                                          AS total_job_cnt,
                round(avg(j.duration_sec) / 60.0, 1)                              AS avg_duration_min,
                max(j.started_at)                                                 AS last_batch_at
            FROM ax.tb_sync_job j
            WHERE j.started_at >= :dayStart
              AND j.started_at <  :dayEnd
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("dayStart", date.atStartOfDay())
            .addValue("dayEnd", date.plusDays(1).atStartOfDay())

        return jdbcTemplate.queryForObject(sql, params) { rs, _ ->
            val failedJobCnt = rs.getLong("failed_job_cnt")
            val runningCnt = rs.getLong("running_job_cnt")
            mapOf(
                // 실패 작업이 있으면 이상, 진행 중이면 진행, 그 외 정상으로 표기한다.
                "syncState" to when {
                    failedJobCnt > 0 -> "FAIL"
                    runningCnt > 0 -> "RUNNING"
                    else -> "DONE"
                },
                "todayRows" to rs.getLong("today_rows"),
                "failedRows" to rs.getLong("failed_rows"),
                "failedJobCnt" to failedJobCnt,
                "runningJobCnt" to runningCnt,
                "totalJobCnt" to rs.getLong("total_job_cnt"),
                "avgDurationMin" to Rs.doubleOrNull(rs, "avg_duration_min"),
                "lastBatchAt" to Rs.dateTime(rs, "last_batch_at")
            )
        } ?: emptyMap()
    }

    /**
     * 연동 상태 판정 재료 (SYN-03) — 기준일과 무관하게 **지금** 기준이다.
     * MES 이관 실행만 본다(그룹웨어 인사정보·모의 실행 제외). 읽기만 한다.
     *
     * @param stalePendingMin 예약 시각이 이 분 넘게 지난 PENDING 을 오래된 예약으로 센다
     */
    fun findHealthStats(stalePendingMin: Long): SyncHealthStats {
        val p = MapSqlParameterSource("stalePendingMin", stalePendingMin)
        val lastRun = jdbcTemplate.query(
            """
            SELECT r.run_id, r.mode_cd, coalesce(mc.code_nm, r.mode_cd) AS mode_nm,
                   r.state_cd, coalesce(sc.code_nm, r.state_cd) AS state_nm, r.started_at, r.message
              FROM ax.tb_sync_run r
              LEFT JOIN ax.tb_sys_code mc ON mc.group_cd = 'SYNC_RUN_MODE'  AND mc.code = r.mode_cd
              LEFT JOIN ax.tb_sys_code sc ON sc.group_cd = 'SYNC_RUN_STATE' AND sc.code = r.state_cd
             WHERE r.mode_cd <> 'GROUPWARE' AND NOT r.is_dry_run
             ORDER BY r.started_at DESC LIMIT 1
            """.trimIndent(), p
        ) { rs, _ ->
            mapOf<String, Any?>(
                "runId" to rs.getString("run_id"), "mode" to rs.getString("mode_cd"), "modeNm" to rs.getString("mode_nm"),
                "state" to rs.getString("state_cd"), "stateNm" to rs.getString("state_nm"),
                "startedAt" to Rs.dateTime(rs, "started_at"), "message" to SensitiveText.mask(rs.getString("message"))
            )
        }.firstOrNull()

        // 최근부터 실패가 끊길 때까지의 실행 상태 — 진행 중·건너뜀은 성공도 실패도 아니라 뺀다(7일 창은 성능용)
        val recentStates = jdbcTemplate.query(
            """
            SELECT state_cd FROM ax.tb_sync_run
             WHERE mode_cd <> 'GROUPWARE' AND NOT is_dry_run AND state_cd NOT IN ('RUNNING','SKIPPED')
               AND started_at >= now() - interval '7 days'
             ORDER BY started_at DESC LIMIT 50
            """.trimIndent(), p
        ) { rs, _ -> rs.getString("state_cd") }

        return jdbcTemplate.queryForObject(
            """
            SELECT
              (SELECT count(*) FROM ax.tb_sync_run
                WHERE mode_cd <> 'GROUPWARE' AND NOT is_dry_run
                  AND state_cd IN ('FAIL','PARTIAL','PREFLIGHT_FAIL')
                  AND started_at >= ((now() AT TIME ZONE 'Asia/Seoul')::date)::timestamp AT TIME ZONE 'Asia/Seoul') AS today_fail_run_cnt,
              (SELECT max(ended_at) FROM ax.tb_sync_job WHERE state_cd IN ('DONE','RETRY_DONE'))           AS last_success_at,
              (SELECT floor(extract(epoch FROM now() - max(ended_at)) / 60)::bigint
                 FROM ax.tb_sync_job WHERE state_cd IN ('DONE','RETRY_DONE'))                              AS stale_min,
              open_fail.cnt AS open_fail_job_cnt, open_fail.oldest AS oldest_open_fail_at,
              (SELECT count(*) FROM ax.tb_sync_job
                WHERE state_cd = 'PENDING' AND scheduled_at < now() - make_interval(mins => CAST(:stalePendingMin AS int))) AS stale_pending_cnt
            FROM (
              -- 미조치 실패: 실패·중단 중 같은 매핑이 이후 정상 완료되지 않았고 진행 중 재실행도 없는 것(사용 중 매핑만)
              SELECT count(*) AS cnt, min(coalesce(f.started_at, f.scheduled_at)) AS oldest
                FROM ax.tb_sync_job f JOIN ax.tb_sync_map m ON m.map_id = f.map_id AND m.use_flg = 'Y'
               WHERE f.state_cd IN ('FAIL','ABORTED')
                 AND NOT EXISTS (SELECT 1 FROM ax.tb_sync_job s
                                  WHERE s.map_id = f.map_id AND s.state_cd IN ('DONE','RETRY_DONE')
                                    AND coalesce(s.started_at, s.scheduled_at) > coalesce(f.started_at, f.scheduled_at))
                 AND NOT EXISTS (SELECT 1 FROM ax.tb_sync_job r
                                  WHERE r.retry_of_job_id = f.job_id AND r.state_cd IN ('PENDING','RUNNING'))
            ) open_fail
            """.trimIndent(), p
        ) { rs, _ ->
            SyncHealthStats(
                lastRun = lastRun,
                recentRunStates = recentStates,
                todayFailRunCnt = rs.getLong("today_fail_run_cnt"),
                lastSuccessAt = Rs.dateTime(rs, "last_success_at"),
                staleMin = (rs.getObject("stale_min") as? Number)?.toLong(),
                openFailJobCnt = rs.getLong("open_fail_job_cnt"),
                oldestOpenFailAt = Rs.dateTime(rs, "oldest_open_fail_at"),
                stalePendingCnt = rs.getLong("stale_pending_cnt")
            )
        }!!
    }

    /**
     * 이관 실행 이력을 조회한다. (SY-15 — 엔진 1회 실행 = 1행)
     *
     * `tb_sync_job` 은 "테이블 1건의 이관" 단위라, 그 앞에서 멈춘 실행은 흔적이 없다.
     * 원본 접속 실패(PREFLIGHT_FAIL)·대상 없음(NO_WORK)·다른 인스턴스 점유(SKIPPED)가
     * 그런 경우다. 실행 자체를 남기는 표가 `tb_sync_run` 이고, 테이블별 상세는
     * `tb_sync_job.run_id` 로 묶인다.
     *
     * 쓰기 주체는 이관 엔진(MES_migration_engine)이고 API 는 읽기만 한다.
     */
    fun findRuns(
        from: LocalDate,
        to: LocalDate,
        state: String?,
        mode: String?,
        limit: Int,
        offset: Int,
        source: String? = null
    ): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT
                r.run_id,
                r.mode_cd,
                coalesce(mc.code_nm, r.mode_cd)  AS mode_nm,
                r.state_cd,
                coalesce(sc.code_nm, r.state_cd) AS state_nm,
                r.started_at,
                r.ended_at,
                r.duration_sec,
                r.triggered_by_cd,
                r.triggered_by,
                r.options_desc,
                r.target_cnt,
                r.success_cnt,
                r.fail_cnt,
                r.ok_rows,
                r.ng_rows,
                r.drift_open_cnt,
                r.is_dry_run,
                r.engine_version,
                r.host_name,
                r.message
            FROM ax.tb_sync_run r
            LEFT JOIN ax.tb_sys_code mc
                   ON mc.group_cd = 'SYNC_RUN_MODE'  AND mc.code = r.mode_cd
            LEFT JOIN ax.tb_sys_code sc
                   ON sc.group_cd = 'SYNC_RUN_STATE' AND sc.code = r.state_cd
            WHERE r.started_at >= :from
              AND r.started_at <  :toExclusive
              AND (:state::varchar IS NULL OR r.state_cd = :state)
              AND (:mode::varchar  IS NULL OR r.mode_cd  = :mode)
              AND $SOURCE_SQL
            """.trimIndent()
        )

        val params = runParams(from, to, state, mode, source)
        sql.append("\nORDER BY r.started_at DESC\nLIMIT :limit OFFSET :offset")
        params.addValue("limit", limit).addValue("offset", offset)

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "runId" to rs.getString("run_id"),
                "mode" to rs.getString("mode_cd"),
                "modeNm" to rs.getString("mode_nm"),
                "state" to rs.getString("state_cd"),
                "stateNm" to rs.getString("state_nm"),
                "startedAt" to Rs.dateTime(rs, "started_at"),
                "endedAt" to Rs.dateTime(rs, "ended_at"),
                "durationSec" to Rs.intOrNull(rs, "duration_sec"),
                "triggeredByCd" to rs.getString("triggered_by_cd"),
                "triggeredBy" to rs.getString("triggered_by"),
                // 실행 문구·옵션·호스트는 내부 주소를 가린 값이다(SYN-05). DB 원문은 그대로 둔다.
                "options" to SensitiveText.mask(rs.getString("options_desc")),
                // 테이블 수다. tb_sync_job.target_rows(진짜 행수)와 이름이 겹치면
                // 같은 응답의 okRows·ngRows 옆에서 행수로 읽힌다. 그래서 tableCnt 로 내린다.
                "tableCnt" to rs.getInt("target_cnt"),
                "successCnt" to rs.getInt("success_cnt"),
                "failCnt" to rs.getInt("fail_cnt"),
                "okRows" to rs.getLong("ok_rows"),
                "ngRows" to rs.getLong("ng_rows"),
                // 그 실행 시점의 미해소 드리프트 건수다. 지금 값과 다를 수 있다
                // (해소하면 tb_sync_schema_drift 는 줄지만 이 값은 기록으로 남는다).
                "driftOpenCntAtRun" to Rs.intOrNull(rs, "drift_open_cnt"),
                // 모의 실행 여부. true 면 대상만 확인하고 아무것도 반영하지 않았다.
                // 상태·모드와 직교한다 — 모의 실행이 프리플라이트에서 실패하면
                // state=PREFLIGHT_FAIL + dryRun=true 조합이 된다(실제로 그런 행이 있다).
                // 화면은 이 값으로 구분한다. options 문자열을 뒤지면 문구가 바뀔 때 조용히 깨진다.
                "dryRun" to rs.getBoolean("is_dry_run"),
                "engineVersion" to rs.getString("engine_version"),
                "host" to SensitiveText.mask(rs.getString("host_name")),
                "message" to SensitiveText.mask(rs.getString("message"))
            )
        }
    }

    /** 이관 실행 이력 전체 건수 */
    fun countRuns(from: LocalDate, to: LocalDate, state: String?, mode: String?, source: String? = null): Long {
        val sql = StringBuilder(
            """
            SELECT count(*)
            FROM ax.tb_sync_run r
            WHERE r.started_at >= :from
              AND r.started_at <  :toExclusive
              AND (:state::varchar IS NULL OR r.state_cd = :state)
              AND (:mode::varchar  IS NULL OR r.mode_cd  = :mode)
              AND $SOURCE_SQL
            """.trimIndent()
        )
        return jdbcTemplate.queryForObject(
            sql.toString(), runParams(from, to, state, mode, source), Long::class.java
        ) ?: 0L
    }

    /** 실행 이력 공통 조건 — 상태·모드 필터를 함께 붙인다. */
    private fun runParams(
        from: LocalDate,
        to: LocalDate,
        state: String?,
        mode: String?,
        source: String? = null
    ): MapSqlParameterSource = MapSqlParameterSource()
        .addValue("from", from.atStartOfDay())
        .addValue("toExclusive", to.plusDays(1).atStartOfDay())
        .addValue("state", state?.trim()?.takeIf { it.isNotBlank() })
        .addValue("mode", mode?.trim()?.takeIf { it.isNotBlank() })
        .addValue("source", source?.trim()?.uppercase()?.takeIf { it.isNotBlank() })

    /**
     * 이관 작업 이력을 조회한다. (No.227)
     */
    fun findJobs(
        from: LocalDate,
        to: LocalDate,
        srcTable: String?,
        state: String?,
        limit: Int,
        offset: Int,
        runId: String? = null,
        includePending: Boolean = true
    ): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT
                j.job_id, j.map_id, j.run_id, j.sync_kind_cd, j.started_at, j.scheduled_at,
                j.ended_at, j.duration_sec,
                j.target_rows, j.ok_rows, j.ng_rows, j.state_cd, j.checksum_match,
                j.retry_cnt, j.triggered_by_cd, j.triggered_by, j.remark, j.retry_of_job_id,
                m.src_db, m.src_schema, m.src_table, m.tgt_schema, m.tgt_table,
                $RETRYABLE_SQL AS retryable
            FROM ax.tb_sync_job j
            INNER JOIN ax.tb_sync_map m ON m.map_id = j.map_id
            -- PENDING 작업은 아직 시작하지 않아 started_at 이 NULL 이다. 예약 시각으로 기간을 판정한다.
            $JOB_PERIOD_SQL
            """.trimIndent()
        )

        val params = jobParams(from, to, includePending)

        appendJobFilters(sql, params, srcTable, state, runId)

        // 대기 중인 작업을 맨 위에 둔다 — 운영자가 가장 먼저 확인해야 할 행이다
        sql.append("\nORDER BY (j.state_cd = 'PENDING') DESC, coalesce(j.started_at, j.scheduled_at) DESC")
        sql.append("\nLIMIT :limit OFFSET :offset")
        params.addValue("limit", limit).addValue("offset", offset)

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "jobId" to rs.getString("job_id"),
                // 소속 실행. 실행 이력(GET /sync/runs)에서 이 값으로 상세를 묶는다.
                // 이 필드가 없으면 실행 목록은 "테이블 N건 성공" 인데 상세는 0건이 된다.
                // 엔진이 run_id 를 채우기 전(2026-09-02 이전) 작업은 null 이다.
                "runId" to rs.getString("run_id"),
                "srcTable" to "${rs.getString("src_schema")}.${rs.getString("src_table")}",
                "dstTable" to "${rs.getString("tgt_schema")}.${rs.getString("tgt_table")}",
                "kind" to rs.getString("sync_kind_cd"),
                "startedAt" to Rs.dateTime(rs, "started_at"),
                "scheduledAt" to Rs.dateTime(rs, "scheduled_at"),
                "endedAt" to Rs.dateTime(rs, "ended_at"),
                "duration" to Rs.intOrNull(rs, "duration_sec"),
                "rows" to rs.getLong("target_rows"),
                "okRows" to rs.getLong("ok_rows"),
                "ngRows" to rs.getLong("ng_rows"),
                "state" to rs.getString("state_cd"),
                "checksumMatch" to Rs.boolOrNull(rs, "checksum_match"),
                "retryCnt" to rs.getInt("retry_cnt"),
                "triggeredBy" to rs.getString("triggered_by_cd"),
                // 요청자(사번 또는 SYSTEM) — triggeredBy 는 경로 코드 그대로 둔다 (12 SYN-07 · 4.4)
                "triggeredByUser" to rs.getString("triggered_by"),
                // 실패 원인 — 내부 주소를 가린 값 (12 SYN-15 엑셀 열)
                "remark" to SensitiveText.mask(rs.getString("remark")),
                // 재실행 연결(V57) — 이 작업이 재실행이면 원 작업, 화면이 「재실행」 버튼을 그릴지 (SYN-02)
                "retryOfJobId" to rs.getString("retry_of_job_id"),
                "retryable" to rs.getBoolean("retryable")
            )
        }
    }

    /** 이관 작업 전체 건수 */
    fun countJobs(
        from: LocalDate, to: LocalDate, srcTable: String?, state: String?, runId: String? = null, includePending: Boolean = true
    ): Long {
        val sql = StringBuilder(
            """
            SELECT count(*)
            FROM ax.tb_sync_job j
            INNER JOIN ax.tb_sync_map m ON m.map_id = j.map_id
            $JOB_PERIOD_SQL
            """.trimIndent()
        )

        val params = jobParams(from, to, includePending)

        appendJobFilters(sql, params, srcTable, state, runId)

        return jdbcTemplate.queryForObject(sql.toString(), params, Long::class.java) ?: 0L
    }

    /** 작업 목록/건수 공통 동적 조건 */
    private fun appendJobFilters(
        sql: StringBuilder,
        params: MapSqlParameterSource,
        srcTable: String?,
        state: String?,
        runId: String? = null
    ) {
        // 원본 테이블 — 대소문자 무시, 목록 응답처럼 스키마를 달고 와도(dbo.TB_X) 같은 결과 (12 SYN-08)
        if (!srcTable.isNullOrBlank()) {
            sql.append(" AND (upper(m.src_table) = upper(:srcTable) OR upper(m.src_schema || '.' || m.src_table) = upper(:srcTable))")
            params.addValue("srcTable", srcTable.trim())
        }
        runId?.trim()?.takeIf { it.isNotEmpty() }?.let {
            sql.append(" AND j.run_id = :runId")
            params.addValue("runId", it)
        }
        if (!state.isNullOrBlank()) {
            sql.append(" AND j.state_cd = :state")
            params.addValue("state", state.trim().uppercase())
        }
    }

    /**
     * 이관 작업 상세를 조회한다. (No.228)
     */
    fun findJob(jobId: String): Map<String, Any?>? {
        val sql = """
            SELECT
                j.job_id, j.map_id, j.run_id, j.sync_kind_cd, j.started_at, j.ended_at, j.scheduled_at, j.duration_sec,
                j.target_rows, j.ok_rows, j.ng_rows, j.state_cd, j.checksum_match,
                j.retry_cnt, j.triggered_by_cd, j.triggered_by, u.user_nm AS triggered_by_nm, j.remark, j.retry_of_job_id,
                m.src_db, m.src_schema, m.src_table, m.tgt_schema, m.tgt_table,
                m.key_columns, m.cdc_column, m.schedule_desc, m.schedule_cron, m.use_flg
            FROM ax.tb_sync_job j
            INNER JOIN ax.tb_sync_map m ON m.map_id = j.map_id
            LEFT JOIN ax.tb_sys_user u ON u.user_id = j.triggered_by
            WHERE j.job_id = :jobId
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource("jobId", jobId)) { rs, _ ->
            mapOf(
                "job" to mapOf(
                    "jobId" to rs.getString("job_id"),
                    "runId" to rs.getString("run_id"),
                    "kind" to rs.getString("sync_kind_cd"),
                    "startedAt" to Rs.dateTime(rs, "started_at"),
                    "endedAt" to Rs.dateTime(rs, "ended_at"),
                    "scheduledAt" to Rs.dateTime(rs, "scheduled_at"),
                    "duration" to Rs.intOrNull(rs, "duration_sec"),
                    "rows" to rs.getLong("target_rows"),
                    "okRows" to rs.getLong("ok_rows"),
                    "ngRows" to rs.getLong("ng_rows"),
                    "state" to rs.getString("state_cd"),
                    "checksumMatch" to Rs.boolOrNull(rs, "checksum_match"),
                    "retryCnt" to rs.getInt("retry_cnt"),
                    "triggeredBy" to rs.getString("triggered_by_cd"),
                    // 요청자 사번(또는 SYSTEM)과 이름 — 사번이 아니면 이름은 null (12 SYN-07)
                    "triggeredByUser" to rs.getString("triggered_by"),
                    "triggeredByName" to rs.getString("triggered_by_nm"),
                    "remark" to SensitiveText.mask(rs.getString("remark")),
                    "retryOfJobId" to rs.getString("retry_of_job_id")
                ),
                "params" to mapOf(
                    "srcDb" to rs.getString("src_db"),
                    "srcTable" to "${rs.getString("src_schema")}.${rs.getString("src_table")}",
                    "dstTable" to "${rs.getString("tgt_schema")}.${rs.getString("tgt_table")}",
                    "keyColumns" to rs.getString("key_columns"),
                    "cdcColumn" to rs.getString("cdc_column"),
                    "schedule" to rs.getString("schedule_desc"),
                    "cron" to rs.getString("schedule_cron")
                ),
                "mapId" to rs.getInt("map_id"),
                "mapUseFlg" to rs.getString("use_flg")
            )
        }.firstOrNull()
    }

    /**
     * 이관 작업 오류를 조회한다. (No.228)
     */
    fun findJobErrors(jobId: String, limit: Int): List<Map<String, Any?>> {
        val sql = """
            SELECT err_id, err_seq, err_code, err_msg, src_key, payload, retried_at, resolved
            FROM ax.tb_sync_job_error
            WHERE job_id = :jobId
            ORDER BY err_seq
            LIMIT :limit
        """.trimIndent()

        val params = MapSqlParameterSource().addValue("jobId", jobId).addValue("limit", limit)

        return jdbcTemplate.query(sql, params) { rs, _ ->
            mapOf(
                "rowNo" to rs.getInt("err_seq"),
                "code" to rs.getString("err_code"),
                "message" to SensitiveText.mask(rs.getString("err_msg")),
                // 원본 키와 원본 행(jsonb) — 접속 문자열이 섞일 수 있어 가린 값 (12 SYN-07, Q12 결정 전)
                "srcKey" to SensitiveText.mask(rs.getString("src_key")),
                "payload" to SensitiveText.mask(rs.getString("payload")),
                // 하위 호환 — 예전 합친 칸
                "rawData" to SensitiveText.mask(rs.getString("payload") ?: rs.getString("src_key")),
                "retriedAt" to Rs.dateTime(rs, "retried_at"),
                "resolved" to rs.getBoolean("resolved")
            )
        }
    }

    /** 재실행 판정에 쓰는 원 작업 정보 */
    data class RetryTarget(val jobId: String, val mapId: Int, val state: String, val kind: String?, val mapUseFlg: String?)

    /**
     * 재실행할 원 작업을 **잠그고** 읽는다 (SYN-02). 같은 작업 재실행이 동시에 두 번 와도 한 요청만 예약을 만든다.
     */
    fun lockJobForRetry(jobId: String): RetryTarget? =
        jdbcTemplate.query(
            """
            SELECT j.job_id, j.map_id, j.state_cd, j.sync_kind_cd, m.use_flg
              FROM ax.tb_sync_job j JOIN ax.tb_sync_map m ON m.map_id = j.map_id
             WHERE j.job_id = :jobId
               FOR UPDATE OF j
            """.trimIndent(),
            MapSqlParameterSource("jobId", jobId)
        ) { rs, _ ->
            RetryTarget(rs.getString("job_id"), rs.getInt("map_id"), rs.getString("state_cd"),
                rs.getString("sync_kind_cd"), rs.getString("use_flg"))
        }.firstOrNull()

    /** 원 작업의 진행 중(PENDING·RUNNING) 재실행 — 있으면 새로 예약하지 않는다 */
    fun findActiveRetry(jobId: String): String? =
        jdbcTemplate.query(
            """
            SELECT job_id FROM ax.tb_sync_job
             WHERE retry_of_job_id = :jobId AND state_cd IN ('PENDING','RUNNING')
             ORDER BY coalesce(started_at, scheduled_at) DESC LIMIT 1
            """.trimIndent(),
            MapSqlParameterSource("jobId", jobId)
        ) { rs, _ -> rs.getString("job_id") }.firstOrNull()

    /**
     * 같은 매핑에서 원 작업 **이후** 정상 완료된 가장 최근 작업 — 재실행하지 않아도 데이터가 이미 맞을 수 있다는 근거.
     * 없으면 null.
     */
    fun findSupersededBy(jobId: String): Map<String, Any?>? =
        jdbcTemplate.query(
            """
            SELECT s.job_id, s.ended_at
              FROM ax.tb_sync_job s JOIN ax.tb_sync_job f ON f.job_id = :jobId
             WHERE s.map_id = f.map_id AND s.job_id <> f.job_id
               AND s.state_cd IN ('DONE','RETRY_DONE')
               AND coalesce(s.started_at, s.scheduled_at) > coalesce(f.started_at, f.scheduled_at)
             ORDER BY s.ended_at DESC NULLS LAST LIMIT 1
            """.trimIndent(),
            MapSqlParameterSource("jobId", jobId)
        ) { rs, _ -> mapOf<String, Any?>("jobId" to rs.getString("job_id"), "endedAt" to Rs.dateTime(rs, "ended_at")) }.firstOrNull()

    /** 원 작업을 재실행한 작업들 — 최근 순 */
    fun findRetriedBy(jobId: String): List<Map<String, Any?>> =
        jdbcTemplate.query(
            """
            SELECT job_id, state_cd, started_at, ended_at FROM ax.tb_sync_job
             WHERE retry_of_job_id = :jobId
             ORDER BY coalesce(started_at, scheduled_at) DESC
            """.trimIndent(),
            MapSqlParameterSource("jobId", jobId)
        ) { rs, _ -> mapOf<String, Any?>("jobId" to rs.getString("job_id"), "state" to rs.getString("state_cd"),
            "startedAt" to Rs.dateTime(rs, "started_at"), "endedAt" to Rs.dateTime(rs, "ended_at")) }

    /** 작업 오류 전체 건수 — 상세 errors 는 500건까지라 따로 센다 (12 SYN-07) */
    fun countJobErrors(jobId: String): Long =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ax.tb_sync_job_error WHERE job_id = :jobId", MapSqlParameterSource("jobId", jobId), Long::class.java
        ) ?: 0L

    /** 작업 목록 기간 조건 — 기간 안 작업 + (includePending 이면) 기간과 무관한 대기 작업 (12 SYN-08) */
    private fun jobParams(from: LocalDate, to: LocalDate, includePending: Boolean) = MapSqlParameterSource()
        .addValue("from", from.atStartOfDay())
        .addValue("toExclusive", to.plusDays(1).atStartOfDay())
        .addValue("includePending", includePending)

    /**
     * 이관 작업을 재실행 등록한다. (No.229)
     *
     * 원본 작업의 매핑으로 새 작업을 만든다.
     *
     * @return 생성된 job_id
     */
    fun insertRetryJob(sourceJobId: String, triggeredBy: String): String? {
        val newJobId = nextQueueJobId()

        // 실행은 이관 엔진이 한다. 여기서는 예약(PENDING)만 걸고 즉시 실행 시각을 준다.
        val sql = """
            INSERT INTO ax.tb_sync_job (
                job_id, map_id, sync_kind_cd, started_at, scheduled_at,
                target_rows, ok_rows, ng_rows,
                state_cd, retry_cnt, triggered_by_cd, triggered_by, remark, retry_of_job_id
            )
            SELECT
                :newJobId, j.map_id, j.sync_kind_cd, NULL, now(), 0, 0, 0,
                'PENDING', j.retry_cnt + 1, 'RETRY', :triggeredBy,
                '작업 ' || j.job_id || ' 재실행', j.job_id
            FROM ax.tb_sync_job j
            WHERE j.job_id = :sourceJobId
            RETURNING job_id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("newJobId", newJobId)
            .addValue("sourceJobId", sourceJobId)
            .addValue("triggeredBy", triggeredBy)

        return jdbcTemplate.query(sql, params) { rs, _ -> rs.getString("job_id") }.firstOrNull()
    }

    /**
     * 수동 이관 작업을 예약한다. (No.230)
     *
     * @param srcTables 대상 원본 테이블 목록
     * @param kind      이관 구분 (FULL/INCR)
     * @return 생성된 job_id 목록
     */
    fun insertManualJobs(
        srcTables: List<String>,
        kind: String,
        scheduledAt: LocalDateTime,
        triggeredBy: String
    ): List<String> {
        if (srcTables.isEmpty()) return emptyList()

        val jobIds = mutableListOf<String>()

        srcTables.forEach { srcTable ->
            // 실행은 이관 엔진이 한다. 여기서는 예약(PENDING)만 걸고,
            // 엔진이 scheduled_at 이 지난 작업을 집어가 RUNNING 으로 전환한다.
            val sql = """
                INSERT INTO ax.tb_sync_job (
                    job_id, map_id, sync_kind_cd, started_at, scheduled_at,
                    target_rows, ok_rows, ng_rows,
                    state_cd, triggered_by_cd, triggered_by, remark
                )
                SELECT
                    :jobId, m.map_id, :kind, NULL, :scheduledAt, 0, 0, 0,
                    'PENDING', 'MANUAL', :triggeredBy, '수동 이관 예약'
                FROM ax.tb_sync_map m
                WHERE m.src_table = :srcTable AND m.use_flg = 'Y'
                LIMIT 1
                RETURNING job_id
            """.trimIndent()

            val params = MapSqlParameterSource()
                .addValue("jobId", nextQueueJobId())
                .addValue("kind", kind)
                .addValue("scheduledAt", scheduledAt)
                .addValue("srcTable", srcTable)
                .addValue("triggeredBy", triggeredBy)

            jdbcTemplate.query(sql, params) { rs, _ -> rs.getString("job_id") }
                .firstOrNull()
                ?.let { jobIds.add(it) }
        }

        return jobIds
    }

    /**
     * 증분 이관이 불가능한 테이블을 골라낸다.
     *
     * 증분은 cdc_column(최종 변경 시각 컬럼) 을 기준으로 변경분을 판별한다. 그 컬럼이 없는
     * 마스터 테이블에 증분을 걸면 이관 엔진이 실행 단계에서 실패한다.
     * 예약을 받아두고 나중에 실패시키는 대신, 여기서 즉시 돌려보낸다.
     *
     * @return 증분을 걸 수 없는 원본 테이블명 목록
     */
    fun findTablesWithoutCdc(srcTables: List<String>): List<String> {
        if (srcTables.isEmpty()) return emptyList()
        val sql = """
            SELECT src_table
              FROM ax.tb_sync_map
             WHERE src_table IN (:srcTables)
               AND use_flg = 'Y'
               AND (cdc_column IS NULL OR btrim(cdc_column) = '')
             ORDER BY src_table
        """.trimIndent()
        return jdbcTemplate.query(sql, MapSqlParameterSource("srcTables", srcTables)) { rs, _ ->
            rs.getString("src_table")
        }
    }

    /**
     * 화면에서 예약하는 작업의 ID 를 만든다 — SYNC-yyMMddHHmmss-N
     *
     * 두 가지 제약을 동시에 만족해야 한다.
     *  · job_id 는 varchar(20) 이다. 4자리 연도를 쓰면 접미 번호가 붙는 순간 21자가 되어
     *    수동 이관이 통째로 실패한다. 그래서 2자리 연도를 쓴다 (최대 20자).
     *  · 초 단위 타임스탬프만으로는 같은 초에 들어온 두 요청이 같은 ID 를 만든다.
     *    요청 안의 순번으로는 요청 경계를 넘는 충돌을 막지 못하므로 시퀀스로 뽑는다.
     *
     * 정기 배치가 만드는 엔진 작업(MIG-YYMMDD-NN)과는 접두어로 구분된다.
     */
    private fun nextQueueJobId(): String {
        val ts = LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyMMddHHmmss"))
        val seq = jdbcTemplate.queryForObject(
            "SELECT nextval('ax.seq_sync_job_no')", MapSqlParameterSource(), Long::class.java,
        ) ?: 1L
        return "SYNC-$ts-$seq"
    }

    /**
     * 연동 매핑을 조회한다. (No.232)
     */
    fun findMaps(srcTable: String?): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT
                m.map_id, m.src_db, m.src_schema, m.src_table, m.tgt_schema, m.tgt_table,
                m.sync_kind_cd, m.schedule_desc, m.schedule_cron, m.key_columns, m.cdc_column,
                m.cumulative_rows, m.last_sync_at, m.last_job_id, m.use_flg, m.remark
            FROM ax.tb_sync_map m
            WHERE m.use_flg = 'Y'
            """.trimIndent()
        )

        val params = MapSqlParameterSource()
        if (!srcTable.isNullOrBlank()) {
            sql.append(" AND m.src_table = :srcTable")
            params.addValue("srcTable", srcTable.trim())
        }

        sql.append("\nORDER BY m.src_table")

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "mapId" to rs.getInt("map_id"),
                "srcDb" to rs.getString("src_db"),
                "srcSchema" to rs.getString("src_schema"),
                "srcTable" to rs.getString("src_table"),
                "dstSchema" to rs.getString("tgt_schema"),
                "dstTable" to rs.getString("tgt_table"),
                "kind" to rs.getString("sync_kind_cd"),
                "schedule" to rs.getString("schedule_desc"),
                "cron" to rs.getString("schedule_cron"),
                "keyColumns" to rs.getString("key_columns"),
                "cdcColumn" to rs.getString("cdc_column"),
                "transform" to rs.getString("remark"),
                "cumulativeRows" to rs.getLong("cumulative_rows"),
                "lastSyncAt" to Rs.dateTime(rs, "last_sync_at"),
                "lastJobId" to rs.getString("last_job_id")
            )
        }
    }

    /**
     * 연동 정책을 조회한다. (No.233)
     *
     * 배치 스케줄과 증분 기준 컬럼, 연동 실패 알림 조건을 반환한다.
     */
    fun findPolicy(): Map<String, Any?> {
        val sql = """
            SELECT
                (
                    SELECT string_agg(DISTINCT m.schedule_cron, ', ')
                      FROM ax.tb_sync_map m WHERE m.use_flg = 'Y' AND m.schedule_cron IS NOT NULL
                )                                                                       AS batch_cron,
                (
                    SELECT string_agg(DISTINCT m.cdc_column, ', ')
                      FROM ax.tb_sync_map m WHERE m.use_flg = 'Y' AND m.cdc_column IS NOT NULL
                )                                                                       AS incremental_key,
                (
                    SELECT max(j.retry_cnt) FROM ax.tb_sync_job j
                )                                                                       AS max_retry_cnt,
                (
                    SELECT c.cond_id FROM ax.tb_alm_cond c
                      JOIN ax.tb_met_metric_std m ON m.metric_id = c.metric_id
                     WHERE m.metric_cd IN ($SYNC_ALERT_METRICS) AND c.use_flg = 'Y'
                     ORDER BY c.cond_id
                     LIMIT 1
                )                                                                       AS fail_alert_cond_id
        """.trimIndent()

        return jdbcTemplate.queryForObject(sql, MapSqlParameterSource()) { rs, _ ->
            mapOf(
                "batchCron" to rs.getString("batch_cron"),
                "incrementalKey" to rs.getString("incremental_key"),
                "retryPolicy" to "실패 시 최대 ${Rs.intOrNull(rs, "max_retry_cnt") ?: 0}회 재시도",
                "failAlertCondId" to Rs.intOrNull(rs, "fail_alert_cond_id")
            )
        } ?: emptyMap()
    }

    /**
     * 연결 테스트를 수행한다. (No.231)
     *
     * PostgreSQL 은 현재 커넥션으로 즉시 확인하고, 원본 DB(MSSQL)는 최근 이관 성공 이력으로 판정한다.
     */
    fun testConnection(target: String): Map<String, Any?> {
        return when (target.lowercase()) {
            "postgresql", "pg", "ax" -> {
                val alive = runCatching {
                    jdbcTemplate.queryForObject("SELECT 1", MapSqlParameterSource(), Int::class.java) == 1
                }.getOrDefault(false)
                mapOf("target" to "postgresql", "connected" to alive, "checkedAt" to LocalDateTime.now().toString())
            }

            else -> {
                // 원본 DB 는 API 서버에서 직접 접속하지 않으므로 최근 이관 성공 여부로 대체 판정한다.
                val sql = """
                    SELECT max(j.ended_at) AS last_ok_at, count(*) AS ok_cnt
                    FROM ax.tb_sync_job j
                    WHERE j.state_cd IN ('DONE', 'RETRY_DONE')
                      AND j.ended_at >= now() - interval '24 hours'
                """.trimIndent()

                jdbcTemplate.queryForObject(sql, MapSqlParameterSource()) { rs, _ ->
                    val okCnt = rs.getLong("ok_cnt")
                    mapOf(
                        "target" to target,
                        "connected" to (okCnt > 0),
                        "basis" to "최근 24시간 이관 성공 ${okCnt}건",
                        "lastOkAt" to Rs.dateTime(rs, "last_ok_at"),
                        "checkedAt" to LocalDateTime.now().toString()
                    )
                } ?: mapOf("target" to target, "connected" to false)
            }
        }
    }

    /** 이관 작업 존재 확인 */
    fun existsJob(jobId: String): Boolean {
        val sql = "SELECT count(*) FROM ax.tb_sync_job WHERE job_id = :jobId"
        return (jdbcTemplate.queryForObject(sql, MapSqlParameterSource("jobId", jobId), Long::class.java) ?: 0L) > 0
    }

    // =====================================================================================
    //  스키마 드리프트 (No.234~236)
    //
    //  ax.tb_sync_schema_drift 는 MES_migration_engine 이 배치마다 직접 기록한다.
    //  API 는 조회와 수동 해소만 담당하고 판정 로직에는 관여하지 않는다.
    // =====================================================================================

    /**
     * 스키마 드리프트 요약을 조회한다. (No.234)
     *
     * 미해소 건만 센다. 해소된 이력은 목록(No.235)에서 확인한다.
     */
    fun findDriftSummary(): Map<String, Any?> {
        val sql = """
            SELECT
                count(*) FILTER (WHERE side_cd = 'SOURCE' AND drift_cd = 'NEW')     AS source_new_cnt,
                count(*) FILTER (WHERE side_cd = 'SOURCE' AND drift_cd = 'MISSING') AS source_missing_cnt,
                count(*) FILTER (WHERE side_cd = 'TARGET' AND drift_cd = 'NEW')     AS target_new_cnt,
                count(*) FILTER (WHERE side_cd = 'TARGET' AND drift_cd = 'MISSING') AS target_missing_cnt,
                count(*)                                                            AS open_cnt,
                max(detect_cnt)                                                     AS max_detect_cnt,
                min(first_seen_at)                                                  AS oldest_seen_at,
                max(last_seen_at)                                                   AS last_checked_at
            FROM ax.tb_sync_schema_drift
            WHERE NOT resolved
        """.trimIndent()

        return jdbcTemplate.queryForObject(sql, MapSqlParameterSource()) { rs, _ ->
            val openCnt = rs.getLong("open_cnt")
            mapOf(
                // 미해소 건이 있으면 화면 상단에 경고 배지를 띄운다.
                "driftState" to if (openCnt > 0) "DRIFT" else "CLEAN",
                "openCnt" to openCnt,
                "sourceNewCnt" to rs.getLong("source_new_cnt"),
                "sourceMissingCnt" to rs.getLong("source_missing_cnt"),
                "targetNewCnt" to rs.getLong("target_new_cnt"),
                "targetMissingCnt" to rs.getLong("target_missing_cnt"),
                "maxDetectCnt" to Rs.intOrNull(rs, "max_detect_cnt"),
                "oldestSeenAt" to Rs.dateTime(rs, "oldest_seen_at"),
                "lastCheckedAt" to Rs.dateTime(rs, "last_checked_at")
            )
        } ?: emptyMap()
    }

    /**
     * 스키마 드리프트 목록을 조회한다. (No.235)
     *
     * 기본은 미해소 건만 본다. 오래 방치된 것부터 보이도록 detect_cnt 내림차순으로 정렬한다.
     */
    fun findDrifts(
        side: String?,
        kind: String?,
        resolved: Boolean?,
        limit: Int,
        offset: Int
    ): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT d.drift_id, d.side_cd, d.drift_cd, d.db_name, d.schema_nm, d.table_nm,
                   d.map_id, m.src_table, m.tgt_table, d.detail,
                   d.first_seen_at, d.first_run_id, d.last_seen_at, d.last_run_id,
                   d.detect_cnt, d.resolved, d.resolved_at, d.resolved_by, d.resolve_note
            FROM ax.tb_sync_schema_drift d
            LEFT JOIN ax.tb_sync_map m ON m.map_id = d.map_id
            WHERE 1 = 1
            """.trimIndent()
        )
        val params = MapSqlParameterSource()
        appendDriftFilters(sql, params, side, kind, resolved)

        sql.append(" ORDER BY d.resolved, d.detect_cnt DESC, d.side_cd, d.drift_cd, d.table_nm")
        sql.append(" LIMIT :limit OFFSET :offset")
        params.addValue("limit", limit).addValue("offset", offset)

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "driftId" to rs.getLong("drift_id"),
                "side" to rs.getString("side_cd"),
                "kind" to rs.getString("drift_cd"),
                "dbName" to rs.getString("db_name"),
                "schemaName" to rs.getString("schema_nm"),
                "tableName" to rs.getString("table_nm"),
                // 화면 목록에 한 줄로 노출할 객체 이름
                "objectName" to "${rs.getString("db_name")}.${rs.getString("schema_nm")}.${rs.getString("table_nm")}",
                "mapId" to Rs.intOrNull(rs, "map_id"),
                "srcTable" to rs.getString("src_table"),
                "dstTable" to rs.getString("tgt_table"),
                "detail" to rs.getString("detail"),
                "firstSeenAt" to Rs.dateTime(rs, "first_seen_at"),
                "firstRunId" to rs.getString("first_run_id"),
                "lastSeenAt" to Rs.dateTime(rs, "last_seen_at"),
                "lastRunId" to rs.getString("last_run_id"),
                "detectCnt" to rs.getInt("detect_cnt"),
                "resolved" to rs.getBoolean("resolved"),
                "resolvedAt" to Rs.dateTime(rs, "resolved_at"),
                "resolvedBy" to rs.getString("resolved_by"),
                "resolveNote" to rs.getString("resolve_note")
            )
        }
    }

    /** 스키마 드리프트 건수 (No.235 페이징) */
    fun countDrifts(side: String?, kind: String?, resolved: Boolean?): Long {
        val sql = StringBuilder("SELECT count(*) FROM ax.tb_sync_schema_drift d WHERE 1 = 1")
        val params = MapSqlParameterSource()
        appendDriftFilters(sql, params, side, kind, resolved)
        return jdbcTemplate.queryForObject(sql.toString(), params, Long::class.java) ?: 0L
    }

    private fun appendDriftFilters(
        sql: StringBuilder,
        params: MapSqlParameterSource,
        side: String?,
        kind: String?,
        resolved: Boolean?
    ) {
        if (!side.isNullOrBlank()) {
            sql.append(" AND d.side_cd = :side")
            params.addValue("side", side.trim().uppercase())
        }
        if (!kind.isNullOrBlank()) {
            sql.append(" AND d.drift_cd = :kind")
            params.addValue("kind", kind.trim().uppercase())
        }
        if (resolved != null) {
            sql.append(" AND d.resolved = :resolved")
            params.addValue("resolved", resolved)
        }
    }

    /**
     * 스키마 드리프트를 수동으로 해소 처리한다. (No.236)
     *
     * "이관 대상이 아님" 처럼 조치할 것이 없다고 판단한 건을 목록에서 내리는 용도다.
     * 실제 원인이 남아 있으면 다음 배치에서 엔진이 다시 열고 detect_cnt 를 이어서 센다.
     *
     * @return 갱신 건수 (이미 해소된 건이면 0)
     */
    fun resolveDrift(driftId: Long, userId: String, note: String?): Int {
        val sql = """
            UPDATE ax.tb_sync_schema_drift
               SET resolved     = true,
                   resolved_at  = now(),
                   resolved_by  = :userId,
                   resolve_note = :note
             WHERE drift_id = :driftId
               AND NOT resolved
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("driftId", driftId)
            .addValue("userId", userId)
            .addValue("note", note?.take(300))

        return jdbcTemplate.update(sql, params)
    }

    /** 스키마 드리프트 존재 확인 */
    fun existsDrift(driftId: Long): Boolean {
        val sql = "SELECT count(*) FROM ax.tb_sync_schema_drift WHERE drift_id = :driftId"
        return (jdbcTemplate.queryForObject(sql, MapSqlParameterSource("driftId", driftId), Long::class.java) ?: 0L) > 0
    }

    companion object {
        /**
         * 목록의 재실행 가능 여부 — 서비스의 `SyncService.retryVerdict` 와 같은 규칙이다(테스트로 묶음).
         * 실패·중단 작업이고, 매핑이 사용 중이고, 진행 중이거나 완료된 재실행이 없을 때만.
         */
        const val RETRYABLE_SQL = """(j.state_cd IN ('FAIL','ABORTED') AND m.use_flg = 'Y'
                AND NOT EXISTS (SELECT 1 FROM ax.tb_sync_job r
                                 WHERE r.retry_of_job_id = j.job_id
                                   AND r.state_cd IN ('PENDING','RUNNING','DONE','RETRY_DONE')))"""
    }

    /**
     * 이관 실패 알림 현황 (12 SYN-04) — 이관 지표(V61: SYNC_FAIL_RATE · SYNC_STALE_MIN)를 쓰는 사용 중 발송 조건 수,
     * 그 조건의 미확인 운영 알림 수, 마지막 알림 시각. 조건이 0 이면 화면은 「실패 알림 미설정」 을 띄운다.
     */
    fun findSyncAlertStats(): Map<String, Any?> =
        jdbcTemplate.queryForObject(
            """
            WITH c AS (
                SELECT c.cond_id FROM ax.tb_alm_cond c
                  JOIN ax.tb_met_metric_std m ON m.metric_id = c.metric_id
                 WHERE m.metric_cd IN ($SYNC_ALERT_METRICS) AND c.use_flg = 'Y'
            )
            SELECT (SELECT count(*) FROM c) AS cond_cnt,
                   (SELECT count(*) FROM ax.tb_alm_alert a WHERE a.cond_id IN (SELECT cond_id FROM c)
                       AND a.test_flg = 'N' AND a.ack_state_cd = 'OPEN') AS open_cnt,
                   (SELECT max(a.occurred_at) FROM ax.tb_alm_alert a WHERE a.cond_id IN (SELECT cond_id FROM c)
                       AND a.test_flg = 'N') AS last_at
            """.trimIndent(),
            MapSqlParameterSource()
        ) { rs, _ ->
            mapOf(
                "condCnt" to rs.getLong("cond_cnt"),
                "openAlertCnt" to rs.getLong("open_cnt"),
                "lastAlertAt" to com.dwje.api.common.util.Rs.dateTime(rs, "last_at")
            )
        } ?: emptyMap()
}

/** 연동 상태 판정 재료 (SYN-03) — [SyncRepository.findHealthStats] */
data class SyncHealthStats(
    val lastRun: Map<String, Any?>?,
    /** 최근 실행 상태(최신 순, 진행 중·건너뜀 제외) */
    val recentRunStates: List<String>,
    val todayFailRunCnt: Long,
    val lastSuccessAt: String?,
    /** 마지막 정상 이관 후 지난 분 — 정상 이관 기록이 없으면 null */
    val staleMin: Long?,
    val openFailJobCnt: Long,
    val oldestOpenFailAt: String?,
    val stalePendingCnt: Long
)
