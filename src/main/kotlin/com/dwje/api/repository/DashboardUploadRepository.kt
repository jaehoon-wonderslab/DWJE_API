package com.dwje.api.repository

import com.dwje.api.common.util.DateUtils
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

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
        parseState: String, parseJson: String, warningJson: String, actor: String
    ) {
        val sql = """
            INSERT INTO ax.tb_dash_upload_ver (
                doc_id, ver, file_nm, storage_path, file_size, sha256,
                parse_state_cd, parse_json, warning_json, ins_user
            ) VALUES (
                :docId, :ver, :fileNm, :storagePath, :fileSize, :sha256,
                :parseState, CAST(:parseJson AS jsonb), CAST(:warningJson AS jsonb), :actor
            )
        """.trimIndent()
        val params = MapSqlParameterSource()
            .addValue("docId", docId).addValue("ver", ver)
            .addValue("fileNm", fileNm).addValue("storagePath", storagePath)
            .addValue("fileSize", fileSize).addValue("sha256", sha256)
            .addValue("parseState", parseState).addValue("parseJson", parseJson)
            .addValue("warningJson", warningJson).addValue("actor", actor)
        jdbcTemplate.update(sql, params)
    }

    /**
     * 문서 목록 — 최신 버전 정보와 함께. 삭제 표시(del_flg='Y')는 뺀다.
     *
     * `updatedBy/At` 은 최신 버전을 올린 사람·시각이다(문서 헤더 갱신이 아니라 "내용이 바뀐" 시점).
     */
    fun findDocs(uploadedBy: String? = null, keyword: String? = null): List<Map<String, Any?>> {
        // uploadedBy 는 사번(정확 일치) 또는 이름(부분 일치) 둘 다 받는다 — 화면이 목록의 이름을 그대로 보낸다.
        // 최신 버전 업로더뿐 아니라 어느 버전이든 그 사람이 올린 문서면 맞는 것으로 본다.
        val uploaderFilter = if (uploadedBy.isNullOrBlank()) "" else """
              AND EXISTS (
                    SELECT 1 FROM ax.tb_dash_upload_ver x
                    LEFT JOIN ax.tb_sys_user xu ON xu.user_id = x.ins_user
                    WHERE x.doc_id = d.doc_id
                      AND (x.ins_user = :uploadedBy OR xu.user_nm ILIKE '%' || :uploadedBy || '%')
              )"""
        val keywordFilter = if (keyword.isNullOrBlank()) "" else """
              AND (d.title ILIKE '%' || :keyword || '%' OR d.memo ILIKE '%' || :keyword || '%'
                   OR lv.file_nm ILIKE '%' || :keyword || '%')"""
        val sql = """
            SELECT d.doc_id, d.title, d.memo, d.latest_ver,
                   d.ins_user AS created_by, cu.user_nm AS created_by_nm, d.ins_date AS created_at,
                   (SELECT count(*) FROM ax.tb_dash_upload_ver v WHERE v.doc_id = d.doc_id) AS version_cnt,
                   lv.ins_user AS updated_by, uu.user_nm AS updated_by_nm, lv.ins_date AS updated_at,
                   lv.file_nm, lv.file_size, lv.parse_state_cd
            FROM ax.tb_dash_upload_doc d
            LEFT JOIN ax.tb_dash_upload_ver lv ON lv.doc_id = d.doc_id AND lv.ver = d.latest_ver
            LEFT JOIN ax.tb_sys_user cu ON cu.user_id = d.ins_user
            LEFT JOIN ax.tb_sys_user uu ON uu.user_id = lv.ins_user
            WHERE d.del_flg = 'N' AND d.latest_ver > 0
              $uploaderFilter
              $keywordFilter
            ORDER BY coalesce(lv.ins_date, d.ins_date) DESC, d.doc_id DESC
        """.trimIndent()
        val params = MapSqlParameterSource()
            .addValue("uploadedBy", uploadedBy?.trim()?.takeIf { it.isNotBlank() })
            .addValue("keyword", keyword?.trim()?.takeIf { it.isNotBlank() })
        return jdbcTemplate.query(sql, params) { rs, _ ->
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
                "parseState" to rs.getString("parse_state_cd")
            )
        }
    }

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
            SELECT v.ver, v.file_nm, v.file_size, v.sha256, v.parse_state_cd,
                   v.ins_user, u.user_nm, v.ins_date,
                   coalesce(jsonb_array_length(v.warning_json), 0) AS warning_cnt
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
                "warningCnt" to rs.getInt("warning_cnt")
            )
        }
    }

    /** 버전 한 건의 파싱 결과와 파일 위치. */
    fun findVersion(docId: Long, ver: Int): UploadVersionRow? {
        val sql = """
            SELECT v.ver, v.file_nm, v.storage_path, v.file_size, v.sha256, v.parse_state_cd,
                   v.parse_json::text AS parse_json, v.warning_json::text AS warning_json,
                   v.ins_user, u.user_nm, v.ins_date
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
                uploadedAt = DateUtils.format(rs.getObject("ins_date"))
            )
        }.firstOrNull()
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
    val uploadedAt: String?
)
