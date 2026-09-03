package com.dwje.api.repository

import com.dwje.api.common.util.Rs
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
        offset: Int
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
            """.trimIndent()
        )

        val params = runParams(from, to, state, mode)
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
                "options" to rs.getString("options_desc"),
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
                "host" to rs.getString("host_name"),
                "message" to rs.getString("message")
            )
        }
    }

    /** 이관 실행 이력 전체 건수 */
    fun countRuns(from: LocalDate, to: LocalDate, state: String?, mode: String?): Long {
        val sql = StringBuilder(
            """
            SELECT count(*)
            FROM ax.tb_sync_run r
            WHERE r.started_at >= :from
              AND r.started_at <  :toExclusive
              AND (:state::varchar IS NULL OR r.state_cd = :state)
              AND (:mode::varchar  IS NULL OR r.mode_cd  = :mode)
            """.trimIndent()
        )
        return jdbcTemplate.queryForObject(
            sql.toString(), runParams(from, to, state, mode), Long::class.java
        ) ?: 0L
    }

    /** 실행 이력 공통 조건 — 상태·모드 필터를 함께 붙인다. */
    private fun runParams(
        from: LocalDate,
        to: LocalDate,
        state: String?,
        mode: String?
    ): MapSqlParameterSource = MapSqlParameterSource()
        .addValue("from", from.atStartOfDay())
        .addValue("toExclusive", to.plusDays(1).atStartOfDay())
        .addValue("state", state?.trim()?.takeIf { it.isNotBlank() })
        .addValue("mode", mode?.trim()?.takeIf { it.isNotBlank() })

    /**
     * 이관 작업 이력을 조회한다. (No.227)
     */
    fun findJobs(
        from: LocalDate,
        to: LocalDate,
        srcTable: String?,
        state: String?,
        limit: Int,
        offset: Int
    ): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT
                j.job_id, j.map_id, j.run_id, j.sync_kind_cd, j.started_at, j.scheduled_at,
                j.ended_at, j.duration_sec,
                j.target_rows, j.ok_rows, j.ng_rows, j.state_cd, j.checksum_match,
                j.retry_cnt, j.triggered_by_cd, j.triggered_by, j.remark,
                m.src_db, m.src_schema, m.src_table, m.tgt_schema, m.tgt_table
            FROM ax.tb_sync_job j
            INNER JOIN ax.tb_sync_map m ON m.map_id = j.map_id
            -- PENDING 작업은 아직 시작하지 않아 started_at 이 NULL 이다. 예약 시각으로 기간을 판정한다.
            WHERE coalesce(j.started_at, j.scheduled_at) >= :from
              AND coalesce(j.started_at, j.scheduled_at) <  :toExclusive
            """.trimIndent()
        )

        val params = MapSqlParameterSource()
            .addValue("from", from.atStartOfDay())
            .addValue("toExclusive", to.plusDays(1).atStartOfDay())

        appendJobFilters(sql, params, srcTable, state)

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
                "triggeredBy" to rs.getString("triggered_by_cd")
            )
        }
    }

    /** 이관 작업 전체 건수 */
    fun countJobs(from: LocalDate, to: LocalDate, srcTable: String?, state: String?): Long {
        val sql = StringBuilder(
            """
            SELECT count(*)
            FROM ax.tb_sync_job j
            INNER JOIN ax.tb_sync_map m ON m.map_id = j.map_id
            WHERE coalesce(j.started_at, j.scheduled_at) >= :from
              AND coalesce(j.started_at, j.scheduled_at) <  :toExclusive
            """.trimIndent()
        )

        val params = MapSqlParameterSource()
            .addValue("from", from.atStartOfDay())
            .addValue("toExclusive", to.plusDays(1).atStartOfDay())

        appendJobFilters(sql, params, srcTable, state)

        return jdbcTemplate.queryForObject(sql.toString(), params, Long::class.java) ?: 0L
    }

    /** 작업 목록/건수 공통 동적 조건 */
    private fun appendJobFilters(
        sql: StringBuilder,
        params: MapSqlParameterSource,
        srcTable: String?,
        state: String?
    ) {
        if (!srcTable.isNullOrBlank()) {
            sql.append(" AND m.src_table = :srcTable")
            params.addValue("srcTable", srcTable.trim())
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
                j.job_id, j.map_id, j.sync_kind_cd, j.started_at, j.ended_at, j.duration_sec,
                j.target_rows, j.ok_rows, j.ng_rows, j.state_cd, j.checksum_match,
                j.retry_cnt, j.triggered_by_cd, j.triggered_by, j.remark,
                m.src_db, m.src_schema, m.src_table, m.tgt_schema, m.tgt_table,
                m.key_columns, m.cdc_column, m.schedule_desc, m.schedule_cron
            FROM ax.tb_sync_job j
            INNER JOIN ax.tb_sync_map m ON m.map_id = j.map_id
            WHERE j.job_id = :jobId
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource("jobId", jobId)) { rs, _ ->
            mapOf(
                "job" to mapOf(
                    "jobId" to rs.getString("job_id"),
                    "kind" to rs.getString("sync_kind_cd"),
                    "startedAt" to Rs.dateTime(rs, "started_at"),
                    "endedAt" to Rs.dateTime(rs, "ended_at"),
                    "duration" to Rs.intOrNull(rs, "duration_sec"),
                    "rows" to rs.getLong("target_rows"),
                    "okRows" to rs.getLong("ok_rows"),
                    "ngRows" to rs.getLong("ng_rows"),
                    "state" to rs.getString("state_cd"),
                    "checksumMatch" to Rs.boolOrNull(rs, "checksum_match"),
                    "retryCnt" to rs.getInt("retry_cnt"),
                    "triggeredBy" to rs.getString("triggered_by_cd"),
                    "remark" to rs.getString("remark")
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
                "mapId" to rs.getInt("map_id")
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
                "message" to rs.getString("err_msg"),
                "rawData" to (rs.getString("payload") ?: rs.getString("src_key")),
                "retriedAt" to Rs.dateTime(rs, "retried_at"),
                "resolved" to rs.getBoolean("resolved")
            )
        }
    }

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
                state_cd, retry_cnt, triggered_by_cd, triggered_by, remark
            )
            SELECT
                :newJobId, j.map_id, j.sync_kind_cd, NULL, now(), 0, 0, 0,
                'PENDING', j.retry_cnt + 1, 'RETRY', :triggeredBy,
                '작업 ' || j.job_id || ' 재실행'
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
                     WHERE c.cond_nm ILIKE '%연동%' OR c.cond_nm ILIKE '%sync%'
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
}
