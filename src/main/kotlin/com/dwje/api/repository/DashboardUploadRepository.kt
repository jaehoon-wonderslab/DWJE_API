package com.dwje.api.repository

import com.dwje.api.common.util.DateUtils
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.LocalDate

/**
 * 업로드 리포트 문서 Repository (`ax.tb_dash_upload_doc` · `ax.tb_dash_upload_ver`, V25+)
 *
 * 문서(제목·메모) 한 건 아래 버전이 쌓인다. 원본 파일은 디스크(`app.upload.dir`)에,
 * 파싱 결과(JSON)는 버전 행의 `parse_json` 에 둔다 — 화면은 파일을 다시 읽지 않고 JSON 만 받는다.
 */
@Repository
class DashboardUploadRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) {

    /** 문서 헤더를 만들고 doc_id 를 돌려준다. latest_ver 는 첫 버전 저장 시 1 로 맞춘다. */
    fun insertDoc(title: String, memo: String?, actor: String): Long {
        val sql = """
            INSERT INTO ax.tb_dash_upload_doc (title, memo, latest_ver, ins_user, upd_user)
            VALUES (:title, :memo, 0, :actor, :actor)
            RETURNING doc_id
        """.trimIndent()
        val params = MapSqlParameterSource()
            .addValue("title", title).addValue("memo", memo).addValue("actor", actor)
        return jdbcTemplate.queryForObject(sql, params, Long::class.java)!!
    }

    /**
     * 새 버전 번호를 확정한다 — 문서 행을 잠근 채 latest_ver + 1 로 올리고 그 값을 돌려준다.
     * 같은 문서에 동시에 올려도 번호가 겹치지 않는다.
     */
    fun nextVersion(docId: Long, actor: String): Int? {
        val sql = """
            UPDATE ax.tb_dash_upload_doc
               SET latest_ver = latest_ver + 1, upd_date = now(), upd_user = :actor
             WHERE doc_id = :docId AND del_flg = 'N'
            RETURNING latest_ver
        """.trimIndent()
        val params = MapSqlParameterSource().addValue("docId", docId).addValue("actor", actor)
        return jdbcTemplate.query(sql, params) { rs, _ -> rs.getInt("latest_ver") }.firstOrNull()
    }

    /** 버전 행을 저장한다. 파싱 결과와 경고는 jsonb. */
    fun insertVersion(
        docId: Long, ver: Int, fileNm: String, storagePath: String, fileSize: Long, sha256: String,
        parseState: String, parseJson: String, warningJson: String, actor: String, memo: String? = null
    ) {
        val sql = """
            INSERT INTO ax.tb_dash_upload_ver (
                doc_id, ver, file_nm, storage_path, file_size, sha256,
                parse_state_cd, parse_json, warning_json, ins_user, memo
            ) VALUES (
                :docId, :ver, :fileNm, :storagePath, :fileSize, :sha256,
                :parseState, CAST(:parseJson AS jsonb), CAST(:warningJson AS jsonb), :actor, :memo
            )
        """.trimIndent()
        val params = MapSqlParameterSource()
            .addValue("docId", docId).addValue("ver", ver)
            .addValue("fileNm", fileNm).addValue("storagePath", storagePath)
            .addValue("fileSize", fileSize).addValue("sha256", sha256)
            .addValue("parseState", parseState).addValue("parseJson", parseJson)
            .addValue("warningJson", warningJson).addValue("actor", actor)
            .addValue("memo", memo)
        jdbcTemplate.update(sql, params)
    }

    /**
     * 문서 목록 — 최신 버전 정보와 함께. 삭제 표시(del_flg='Y')는 뺀다.
     *
     * `updatedBy/At` 은 최신 버전을 올린 사람·시각이다(문서 헤더 갱신이 아니라 "내용이 바뀐" 시점).
     */
    fun findDocs(
        uploadedBy: String? = null,
        keyword: String? = null,
        filter: DocFilter = DocFilter(),
        limit: Int? = null,
        offset: Int = 0
    ): List<Map<String, Any?>> {
        val params = docParams(uploadedBy, keyword, filter)
        val sql = StringBuilder("""
            SELECT d.doc_id, d.title, d.memo, d.latest_ver,
                   d.ins_user AS created_by, cu.user_nm AS created_by_nm, d.ins_date AS created_at,
                   (SELECT count(*) FROM ax.tb_dash_upload_ver v WHERE v.doc_id = d.doc_id) AS version_cnt,
                   lv.ins_user AS updated_by, uu.user_nm AS updated_by_nm, lv.ins_date AS updated_at,
                   lv.file_nm, lv.file_size, lv.parse_state_cd, lv.storage_path,
                   d.del_flg, d.del_at, d.del_reason, coalesce(du.user_nm, d.del_user) AS del_user_nm
        """.trimIndent()).append("\n").append(docFrom(uploadedBy, keyword, filter))
            .append("\nORDER BY coalesce(lv.ins_date, d.ins_date) DESC, d.doc_id DESC")
        // limit 이 null 이면 전량 — 대시보드 목록은 예전처럼 전부 준다
        if (limit != null) {
            sql.append("\nLIMIT :limit OFFSET :offset")
            params.addValue("limit", limit).addValue("offset", offset)
        }
        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "docId" to rs.getLong("doc_id"),
                "title" to rs.getString("title"),
                "memo" to rs.getString("memo"),
                "latestVersion" to rs.getInt("latest_ver"),
                "versionCnt" to rs.getInt("version_cnt"),
                "createdBy" to rs.getString("created_by"),
                "createdByName" to rs.getString("created_by_nm"),
                "createdAt" to DateUtils.format(rs.getObject("created_at")),
                "updatedBy" to rs.getString("updated_by"),
                "updatedByName" to rs.getString("updated_by_nm"),
                "updatedAt" to DateUtils.format(rs.getObject("updated_at")),
                "fileName" to rs.getString("file_nm"),
                "sizeBytes" to rs.getObject("file_size")?.let { (it as Number).toLong() },
                "parseState" to rs.getString("parse_state_cd"),
                // 서버 내부 경로 — 서비스가 보관 상태 판정에만 쓰고 응답에서 뺀다 (11 UPD-08)
                "storagePath" to rs.getString("storage_path"),
                // 숨김(소프트 삭제) — 시스템관리 목록에서 includeDeleted 일 때만 true 행이 나온다 (R-19)
                "deleted" to (rs.getString("del_flg") == "Y"),
                "deletedAt" to DateUtils.format(rs.getObject("del_at")),
                "deletedByName" to rs.getString("del_user_nm"),
                "deleteReason" to rs.getString("del_reason")
            )
        }
    }

    /** 문서 목록 전체 건수 — [findDocs] 와 같은 조건 */
    fun countDocs(uploadedBy: String? = null, keyword: String? = null, filter: DocFilter = DocFilter()): Long =
        jdbcTemplate.queryForObject(
            "SELECT count(*)\n" + docFrom(uploadedBy, keyword, filter),
            docParams(uploadedBy, keyword, filter), Long::class.java
        ) ?: 0L

    /**
     * 시스템관리 목록의 추가 조건 (11 기획서 UPD-01)
     *
     * @param parseState 최신 버전 파싱 상태 (DASH_UPLOAD_PARSE — OK · WARN · FAIL)
     * @param from       최신 버전 업로드일(한국 날짜) 시작
     * @param to         최신 버전 업로드일(한국 날짜) 끝 — 그날 포함
     */
    data class DocFilter(
        val parseState: String? = null, val from: LocalDate? = null, val to: LocalDate? = null,
        /** 숨긴 문서도 포함 — 시스템관리 목록만 (R-19). 대시보드·AI 패널은 늘 숨김 제외 */
        val includeDeleted: Boolean = false
    )

    private fun docFrom(uploadedBy: String?, keyword: String?, filter: DocFilter): String {
        // uploadedBy 가 숫자(사번 형식)면 사번 정확 일치만, 아니면 이름 부분 일치 — 화면이 목록의 이름을 그대로 보낼 수 있다 (11 UPD-06).
        // 최신 버전 업로더뿐 아니라 어느 버전이든 그 사람이 올린 문서면 맞는 것으로 본다.
        val byEmpNo = uploadedBy?.trim()?.let { EMP_NO.matches(it) } == true
        val uploaderFilter = if (uploadedBy.isNullOrBlank()) "" else """
              AND EXISTS (
                    SELECT 1 FROM ax.tb_dash_upload_ver x
                    LEFT JOIN ax.tb_sys_user xu ON xu.user_id = x.ins_user
                    WHERE x.doc_id = d.doc_id
                      AND ${if (byEmpNo) "x.ins_user = :uploadedBy" else "xu.user_nm ILIKE '%' || :uploadedBy || '%'"}
              )"""
        // 검색어가 숫자면 문서 ID 도 본다 (11 UPD-06)
        val keywordId = keyword?.trim()?.takeIf { EMP_NO.matches(it) }?.toLongOrNull()
        val keywordFilter = if (keyword.isNullOrBlank()) "" else """
              AND (d.title ILIKE '%' || :keyword || '%' OR d.memo ILIKE '%' || :keyword || '%'
                   OR lv.file_nm ILIKE '%' || :keyword || '%'${if (keywordId != null) " OR d.doc_id = :keywordId" else ""})"""
        // 기간은 한국 날짜로 자른다 — DB 세션 시간대(UTC)와 무관하게
        val extra = buildString {
            if (filter.parseState != null) append("\n  AND lv.parse_state_cd = :parseState")
            if (filter.from != null) append("\n  AND lv.ins_date >= (CAST(:fromDate AS date))::timestamp AT TIME ZONE 'Asia/Seoul'")
            if (filter.to != null) append("\n  AND lv.ins_date <  (CAST(:toDate AS date) + 1)::timestamp AT TIME ZONE 'Asia/Seoul'")
        }
        return """
            FROM ax.tb_dash_upload_doc d
            LEFT JOIN ax.tb_dash_upload_ver lv ON lv.doc_id = d.doc_id AND lv.ver = d.latest_ver
            LEFT JOIN ax.tb_sys_user cu ON cu.user_id = d.ins_user
            LEFT JOIN ax.tb_sys_user uu ON uu.user_id = lv.ins_user
            LEFT JOIN ax.tb_sys_user du ON du.user_id = d.del_user
            WHERE ${if (filter.includeDeleted) "" else "d.del_flg = 'N' AND "}d.latest_ver > 0
        """.trimIndent() + uploaderFilter + keywordFilter + extra
    }

    /**
     * 시스템관리 목록 요약 — 같은 조건(파싱 상태 조건만 빼고)의 최신 버전 파싱 상태별 문서 수 (11 WEB 계약 `summary`)
     * 상태 조건을 빼는 것은 카드 숫자가 상태 필터를 눌러도 바뀌지 않게 하기 위함이다.
     */
    fun findDocSummary(uploadedBy: String?, keyword: String?, filter: DocFilter): Map<String, Any?> {
        val f = filter.copy(parseState = null)
        return jdbcTemplate.queryForObject(
            """
            SELECT count(*) AS total,
                   count(*) FILTER (WHERE lv.parse_state_cd = 'OK')   AS ok_cnt,
                   count(*) FILTER (WHERE lv.parse_state_cd = 'WARN') AS warn_cnt,
                   count(*) FILTER (WHERE lv.parse_state_cd = 'FAIL') AS fail_cnt
            """.trimIndent() + "\n" + docFrom(uploadedBy, keyword, f),
            docParams(uploadedBy, keyword, f)
        ) { rs, _ ->
            mapOf("total" to rs.getLong("total"), "ok" to rs.getLong("ok_cnt"), "warn" to rs.getLong("warn_cnt"), "fail" to rs.getLong("fail_cnt"))
        } ?: emptyMap()
    }

    /**
     * 저장소 현황 — 조회 조건과 무관한 전체 (11 UPD-07). 이번 달은 한국 시각 기준.
     */
    fun findStorageSummary(): Map<String, Any?> =
        jdbcTemplate.queryForObject(
            """
            SELECT count(DISTINCT d.doc_id) FILTER (WHERE d.latest_ver > 0)                       AS doc_cnt,
                   count(v.doc_id)                                                                AS version_cnt,
                   count(v.doc_id) FILTER (
                       WHERE v.ins_date >= (date_trunc('month', now() AT TIME ZONE 'Asia/Seoul')) AT TIME ZONE 'Asia/Seoul'
                   )                                                                              AS month_version_cnt,
                   coalesce(sum(v.file_size), 0)                                                  AS total_bytes,
                   count(DISTINCT d.doc_id) FILTER (WHERE v.ver = d.latest_ver AND v.parse_state_cd = 'FAIL') AS fail_doc_cnt,
                   max(v.ins_date)                                                                AS last_uploaded_at,
                   (SELECT count(*) FROM ax.tb_dash_upload_doc x WHERE x.del_flg = 'Y' AND x.latest_ver > 0) AS deleted_doc_cnt
              FROM ax.tb_dash_upload_doc d
              LEFT JOIN ax.tb_dash_upload_ver v ON v.doc_id = d.doc_id
             WHERE d.del_flg = 'N'
            """.trimIndent(),
            MapSqlParameterSource()
        ) { rs, _ ->
            mapOf(
                "docCnt" to rs.getLong("doc_cnt"),
                "versionCnt" to rs.getLong("version_cnt"),
                "monthVersionCnt" to rs.getLong("month_version_cnt"),
                "totalBytes" to rs.getLong("total_bytes"),
                "failDocCnt" to rs.getLong("fail_doc_cnt"),
                "lastUploadedAt" to DateUtils.format(rs.getObject("last_uploaded_at")),
                // 숨긴 문서 수 — docCnt 는 숨김 제외 (R-19)
                "deletedDocCnt" to rs.getLong("deleted_doc_cnt")
            )
        } ?: emptyMap()

    /** 업로더 선택지 — 삭제되지 않은 문서의 어느 버전이든 올린 사람, 그 사람이 올린 버전 수(cnt)·문서 수(docCnt) */
    fun findUploaders(): List<Map<String, Any?>> =
        jdbcTemplate.query(
            """
            SELECT v.ins_user, max(u.user_nm) AS user_nm, count(*) AS cnt, count(DISTINCT v.doc_id) AS doc_cnt
              FROM ax.tb_dash_upload_ver v
              JOIN ax.tb_dash_upload_doc d ON d.doc_id = v.doc_id AND d.del_flg = 'N'
              LEFT JOIN ax.tb_sys_user u ON u.user_id = v.ins_user
             WHERE v.ins_user IS NOT NULL
             GROUP BY v.ins_user
             ORDER BY max(u.user_nm), v.ins_user
            """.trimIndent(),
            MapSqlParameterSource()
        ) { rs, _ ->
            // 4.4 이름(userId·userName·docCnt)을 더하고 2단계 이름(empNo·name·cnt)은 남긴다 (11 UPD-06)
            mapOf(
                "userId" to rs.getString("ins_user"), "userName" to rs.getString("user_nm"), "docCnt" to rs.getLong("doc_cnt"),
                "empNo" to rs.getString("ins_user"), "name" to rs.getString("user_nm"), "cnt" to rs.getLong("cnt")
            )
        }

    private fun docParams(uploadedBy: String?, keyword: String?, filter: DocFilter) = MapSqlParameterSource()
        .addValue("uploadedBy", uploadedBy?.trim()?.takeIf { it.isNotBlank() })
        .addValue("keyword", keyword?.trim()?.takeIf { it.isNotBlank() })
        .addValue("keywordId", keyword?.trim()?.takeIf { EMP_NO.matches(it) }?.toLongOrNull())
        .addValue("parseState", filter.parseState)
        .addValue("fromDate", filter.from?.toString())
        .addValue("toDate", filter.to?.toString())

    /** 문서 헤더 한 건. 삭제 표시면 null. */
    fun findDoc(docId: Long): Map<String, Any?>? {
        val sql = """
            SELECT doc_id, title, memo, latest_ver, ins_user
            FROM ax.tb_dash_upload_doc
            WHERE doc_id = :docId AND del_flg = 'N'
        """.trimIndent()
        return jdbcTemplate.query(sql, MapSqlParameterSource("docId", docId)) { rs, _ ->
            mapOf(
                "docId" to rs.getLong("doc_id"),
                "title" to rs.getString("title"),
                "memo" to rs.getString("memo"),
                "latestVersion" to rs.getInt("latest_ver"),
                "createdBy" to rs.getString("ins_user")
            )
        }.firstOrNull()
    }

    /** 문서의 버전 이력 — 최신이 먼저. */
    fun findVersions(docId: Long): List<Map<String, Any?>> {
        val sql = """
            SELECT v.ver, v.file_nm, v.file_size, v.sha256, v.parse_state_cd, v.storage_path,
                   v.ins_user, u.user_nm, v.ins_date, v.memo,
                   coalesce(jsonb_array_length(v.warning_json), 0) AS warning_cnt,
                   v.warning_json::text AS warning_json,
                   (SELECT max(p.ver) FROM ax.tb_dash_upload_ver p
                     WHERE p.doc_id = v.doc_id AND p.ver < v.ver AND p.sha256 = v.sha256) AS duplicate_of
            FROM ax.tb_dash_upload_ver v
            LEFT JOIN ax.tb_sys_user u ON u.user_id = v.ins_user
            WHERE v.doc_id = :docId
            ORDER BY v.ver DESC
        """.trimIndent()
        return jdbcTemplate.query(sql, MapSqlParameterSource("docId", docId)) { rs, _ ->
            mapOf(
                "version" to rs.getInt("ver"),
                "fileName" to rs.getString("file_nm"),
                "sizeBytes" to rs.getLong("file_size"),
                "sha256" to rs.getString("sha256"),
                "uploadedBy" to rs.getString("ins_user"),
                "uploadedByName" to rs.getString("user_nm"),
                "uploadedAt" to DateUtils.format(rs.getObject("ins_date")),
                "parseState" to rs.getString("parse_state_cd"),
                "warningCnt" to rs.getInt("warning_cnt"),
                // 파싱 경고 문장 — 최대 20건, 넘으면 warningTruncated (11 UPD-09)
                "warnings" to warningsOf(rs.getString("warning_json")).take(WARNINGS_MAX),
                "warningTruncated" to (rs.getInt("warning_cnt") > WARNINGS_MAX),
                // 같은 문서의 앞 버전 중 내용(해시)이 같은 가장 큰 버전 — 같은 파일을 다시 올렸다는 안내 (11 UPD-10)
                "duplicateOf" to com.dwje.api.common.util.Rs.intOrNull(rs, "duplicate_of"),
                // 버전 메모 — 새 버전을 올릴 때 적은 변경 내용(버전 1 은 문서 등록 메모, UPD-02)
                "memo" to rs.getString("memo"),
                // 서버 내부 경로 — 서비스가 보관 상태 판정에만 쓰고 응답에서 뺀다 (11 UPD-08)
                "storagePath" to rs.getString("storage_path")
            )
        }
    }

    /** 버전 한 건의 파싱 결과와 파일 위치. */
    fun findVersion(docId: Long, ver: Int): UploadVersionRow? {
        val sql = """
            SELECT v.ver, v.file_nm, v.storage_path, v.file_size, v.sha256, v.parse_state_cd,
                   v.parse_json::text AS parse_json, v.warning_json::text AS warning_json,
                   v.ins_user, u.user_nm, v.ins_date, v.memo
            FROM ax.tb_dash_upload_ver v
            INNER JOIN ax.tb_dash_upload_doc d ON d.doc_id = v.doc_id AND d.del_flg = 'N'
            LEFT JOIN ax.tb_sys_user u ON u.user_id = v.ins_user
            WHERE v.doc_id = :docId AND v.ver = :ver
        """.trimIndent()
        val params = MapSqlParameterSource().addValue("docId", docId).addValue("ver", ver)
        return jdbcTemplate.query(sql, params) { rs, _ ->
            UploadVersionRow(
                version = rs.getInt("ver"),
                fileName = rs.getString("file_nm"),
                storagePath = rs.getString("storage_path"),
                sizeBytes = rs.getLong("file_size"),
                sha256 = rs.getString("sha256"),
                parseState = rs.getString("parse_state_cd"),
                parseJson = rs.getString("parse_json"),
                warningJson = rs.getString("warning_json"),
                uploadedBy = rs.getString("ins_user"),
                uploadedByName = rs.getString("user_nm"),
                uploadedAt = DateUtils.format(rs.getObject("ins_date")),
                memo = rs.getString("memo")
            )
        }.firstOrNull()
    }

    /** 문서 한 건 — 숨김 여부 무관 (R-19 숨김·복원) */
    fun findDocAny(docId: Long): Map<String, Any?>? =
        jdbcTemplate.query(
            "SELECT doc_id, title, latest_ver, del_flg FROM ax.tb_dash_upload_doc WHERE doc_id = :docId",
            MapSqlParameterSource("docId", docId)
        ) { rs, _ ->
            mapOf("docId" to rs.getLong("doc_id"), "title" to rs.getString("title"), "latestVersion" to rs.getInt("latest_ver"),
                "deleted" to (rs.getString("del_flg") == "Y"))
        }
            .firstOrNull()

    /** 숨김 — 이미 숨긴 문서면 0 (R-19). 원본 파일·버전 행은 그대로 둔다 */
    fun hideDoc(docId: Long, actor: String, reason: String): Int =
        jdbcTemplate.update(
            """
            UPDATE ax.tb_dash_upload_doc
               SET del_flg = 'Y', del_at = now(), del_user = :actor, del_reason = :reason, upd_date = now(), upd_user = :actor
             WHERE doc_id = :docId AND del_flg = 'N'
            """.trimIndent(),
            MapSqlParameterSource().addValue("docId", docId).addValue("actor", actor).addValue("reason", reason)
        )

    /** 복원 — 숨기지 않은 문서면 0 (R-19) */
    fun restoreDoc(docId: Long, actor: String): Int =
        jdbcTemplate.update(
            """
            UPDATE ax.tb_dash_upload_doc
               SET del_flg = 'N', del_at = NULL, del_user = NULL, del_reason = NULL, upd_date = now(), upd_user = :actor
             WHERE doc_id = :docId AND del_flg = 'Y'
            """.trimIndent(),
            MapSqlParameterSource().addValue("docId", docId).addValue("actor", actor)
        )

    /** 앞 버전 중 해시가 같은 가장 큰 버전 (11 UPD-10) — 없으면 null */
    fun findDuplicateOf(docId: Long, ver: Int, sha256: String): Int? =
        jdbcTemplate.queryForObject(
            "SELECT max(ver) FROM ax.tb_dash_upload_ver WHERE doc_id = :docId AND ver < :ver AND sha256 = :sha",
            MapSqlParameterSource().addValue("docId", docId).addValue("ver", ver).addValue("sha", sha256), Int::class.java
        )

    /** warning_json(문장 배열)을 읽는다 — 형식이 다르면 빈 목록 */
    private fun warningsOf(json: String?): List<String> = json?.let {
        runCatching { JSON.readValue(it, List::class.java).map { w -> w.toString() } }.getOrNull()
    }.orEmpty()

    companion object {
        /** 버전 이력 응답의 경고 문장 상한 (11 UPD-09) */
        const val WARNINGS_MAX = 20
        private val JSON = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()

        /** 숫자만 — 사번·문서 ID 형식 */
        private val EMP_NO = Regex("^[0-9]+$")
    }
}

/** 버전 한 건 */
data class UploadVersionRow(
    val version: Int,
    val fileName: String,
    val storagePath: String,
    val sizeBytes: Long,
    val sha256: String?,
    val parseState: String,
    val parseJson: String?,
    val warningJson: String?,
    val uploadedBy: String?,
    val uploadedByName: String?,
    val uploadedAt: String?,
    /** 버전 메모 (UPD-02) */
    val memo: String? = null
)
