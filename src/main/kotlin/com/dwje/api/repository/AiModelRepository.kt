package com.dwje.api.repository

import com.dwje.api.common.util.Rs
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.LocalDate

/**
 * AI 모델 버전 · Agent 관리 Repository (SY-11, SY-12)
 *
 * 참조 테이블 : ax.tb_ai_serving_profile, ax.tb_ai_serving_asset, ax.tb_ai_serving_route,
 *              ax.tb_ai_serving_deploy_log, ax.tb_ai_model_asset, ax.tb_ai_corpus_snapshot,
 *              ax.tb_ai_agent, ax.tb_ai_agent_run, ax.tb_ai_pipeline_stage
 */
@Repository
class AiModelRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) {

    // =================================================================================
    // SY-11. AI 모델 버전 관리
    // =================================================================================

    /**
     * 모델 버전 요약을 조회한다. (No.195)
     */
    fun findReleaseSummary(): Map<String, Any?> {
        val sql = """
            SELECT
                (
                    SELECT p.profile_cd || '-v' || p.version_no
                      FROM ax.tb_ai_serving_profile p
                     WHERE p.service_cd = 'CHAT' AND p.state_cd = 'ACTIVE'
                     ORDER BY p.activated_at DESC NULLS LAST LIMIT 1
                )                                                                       AS serving_ver,
                (
                    SELECT p.activated_at
                      FROM ax.tb_ai_serving_profile p
                     WHERE p.service_cd = 'CHAT' AND p.state_cd = 'ACTIVE'
                     ORDER BY p.activated_at DESC NULLS LAST LIMIT 1
                )                                                                       AS applied_at,
                (SELECT count(*) FROM ax.tb_ai_serving_profile)                         AS release_cnt,
                (SELECT count(*) FROM ax.tb_ai_corpus_snapshot WHERE state_cd = 'READY') AS vec_completed_cnt,
                (SELECT count(*) FROM ax.tb_ai_model_asset
                  WHERE asset_kind_cd = 'LORA' AND state_cd = 'VERIFIED')               AS ft_completed_cnt
        """.trimIndent()

        return jdbcTemplate.queryForObject(sql, MapSqlParameterSource()) { rs, _ ->
            mapOf(
                "serving" to mapOf(
                    "ver" to rs.getString("serving_ver"),
                    "mode" to "ACTIVE",
                    "appliedAt" to Rs.dateTime(rs, "applied_at")
                ),
                "releaseCnt" to rs.getLong("release_cnt"),
                "vecCompletedCnt" to rs.getLong("vec_completed_cnt"),
                "ftCompletedCnt" to rs.getLong("ft_completed_cnt")
            )
        } ?: emptyMap()
    }

    /**
     * 모델 자산 목록을 조회한다. (파인튜닝 폼의 베이스 모델·LoRA 선택지)
     *
     * 파인튜닝을 실행하면 이 표에 자산 한 건이 등록된다.
     *
     * @param kind 자산 종류 (AI_ASSET_KIND — LLM_BASE / LORA / EMBED / RERANK). null 이면 전체
     */
    fun findAssets(kind: String?): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT
                a.asset_id,
                a.asset_kind_cd,
                coalesce(k.code_nm, a.asset_kind_cd) AS kind_nm,
                a.asset_key,
                a.version_tag,
                a.asset_nm,
                a.base_model,
                a.dim,
                a.max_tokens,
                a.state_cd,
                coalesce(st.code_nm, a.state_cd)     AS state_nm,
                a.is_onprem,
                a.verified_at
            FROM ax.tb_ai_model_asset a
            LEFT JOIN ax.tb_sys_code k
                   ON k.group_cd = 'AI_ASSET_KIND' AND k.code = a.asset_kind_cd
            LEFT JOIN ax.tb_sys_code st
                   ON st.group_cd = 'AI_ASSET_STATE' AND st.code = a.state_cd
            WHERE 1 = 1
            """.trimIndent()
        )

        val params = MapSqlParameterSource()
        if (!kind.isNullOrBlank()) {
            sql.append(" AND a.asset_kind_cd = :kind")
            params.addValue("kind", kind.trim())
        }
        sql.append("\nORDER BY a.asset_kind_cd, a.asset_id DESC")

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "assetId" to rs.getInt("asset_id"),
                "kind" to rs.getString("asset_kind_cd"),
                "kindNm" to rs.getString("kind_nm"),
                "assetKey" to rs.getString("asset_key"),
                "version" to rs.getString("version_tag"),
                "name" to rs.getString("asset_nm"),
                "baseModel" to rs.getString("base_model"),
                "dim" to Rs.intOrNull(rs, "dim"),
                "maxTokens" to Rs.intOrNull(rs, "max_tokens"),
                "state" to rs.getString("state_cd"),
                "stateNm" to rs.getString("state_nm"),
                "onPrem" to rs.getBoolean("is_onprem"),
                "verifiedAt" to Rs.dateTime(rs, "verified_at")
            )
        }
    }

    /**
     * 임베딩 모델 목록을 조회한다. (재색인 폼의 embedModelId 선택지)
     *
     * 모델 자산(`ax.tb_ai_model_asset`)과 다른 표다 —
     * 임베딩 모델은 벡터 차원이 `vec.tb_doc_chunk` 컬럼 정의에 묶여 있어 `vec` 스키마에서 관리한다.
     * `current` 가 true 인 것이 기본 모델이다.
     */
    fun findEmbedModels(): List<Map<String, Any?>> {
        val sql = """
            SELECT
                m.model_id,
                m.model_key,
                m.model_nm,
                m.provider_cd,
                coalesce(p.code_nm, m.provider_cd) AS provider_nm,
                m.dim,
                m.max_tokens,
                m.is_onprem,
                m.is_default,
                m.remark
            FROM vec.tb_embed_model m
            LEFT JOIN ax.tb_sys_code p
                   ON p.group_cd = 'VEC_PROVIDER' AND p.code = m.provider_cd
            WHERE m.use_flg = 'Y'
            ORDER BY m.is_default DESC, m.model_id
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource()) { rs, _ ->
            mapOf(
                "embedModelId" to rs.getInt("model_id"),
                "key" to rs.getString("model_key"),
                "name" to rs.getString("model_nm"),
                "provider" to rs.getString("provider_cd"),
                "providerNm" to rs.getString("provider_nm"),
                "dim" to Rs.intOrNull(rs, "dim"),
                "maxTokens" to Rs.intOrNull(rs, "max_tokens"),
                "onPrem" to rs.getBoolean("is_onprem"),
                "current" to rs.getBoolean("is_default"),
                "remark" to rs.getString("remark")
            )
        }
    }

    /**
     * 릴리스(서빙 프로파일) 목록을 조회한다. (No.196)
     */
    fun findReleases(state: String?, limit: Int, offset: Int): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT
                p.profile_id, p.profile_cd, p.version_no, p.profile_nm, p.description,
                p.state_cd, p.eval_score, p.eval_baseline, p.must_pass_fail,
                p.canary_at, p.activated_at, p.retired_at, p.rollback_at, p.rollback_reason,
                p.activated_by, u.user_nm AS activated_by_nm, p.ins_date, p.remark,
                cs.snapshot_cd, cs.snapshot_nm,
                (
                    SELECT string_agg(a.asset_key || ':' || a.version_tag, ',' ORDER BY sa.role_cd)
                      FROM ax.tb_ai_serving_asset sa
                     INNER JOIN ax.tb_ai_model_asset a ON a.asset_id = sa.asset_id
                     WHERE sa.profile_id = p.profile_id
                ) AS assets,
                (
                    SELECT a.asset_key || ':' || a.version_tag
                      FROM ax.tb_ai_serving_asset sa
                     INNER JOIN ax.tb_ai_model_asset a ON a.asset_id = sa.asset_id
                     WHERE sa.profile_id = p.profile_id AND sa.role_cd LIKE 'LORA%'
                     LIMIT 1
                ) AS ft_id
            FROM ax.tb_ai_serving_profile p
            LEFT JOIN ax.tb_ai_corpus_snapshot cs ON cs.snapshot_id = p.corpus_snapshot_id
            LEFT JOIN ax.tb_sys_user           u  ON u.user_id      = p.activated_by
            WHERE p.service_cd = 'CHAT'
            """.trimIndent()
        )

        val params = MapSqlParameterSource()
        if (!state.isNullOrBlank()) {
            sql.append(" AND p.state_cd = :state")
            params.addValue("state", state.trim().uppercase())
        }

        sql.append("\nORDER BY p.version_no DESC\nLIMIT :limit OFFSET :offset")
        params.addValue("limit", limit).addValue("offset", offset)

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "profileId" to rs.getInt("profile_id"),
                "ver" to "${rs.getString("profile_cd")}-v${rs.getInt("version_no")}",
                "profileCd" to rs.getString("profile_cd"),
                "versionNo" to rs.getInt("version_no"),
                "name" to rs.getString("profile_nm"),
                "description" to rs.getString("description"),
                "state" to rs.getString("state_cd"),
                "mode" to if (rs.getString("state_cd") == "CANARY") "점진 전환" else "즉시 전환",
                "vecId" to rs.getString("snapshot_cd"),
                "vecNm" to rs.getString("snapshot_nm"),
                "ftId" to rs.getString("ft_id"),
                "assets" to (rs.getString("assets")?.split(",") ?: emptyList()),
                "evalScore" to Rs.rate(rs, "eval_score", 3),
                "evalBaseline" to Rs.rate(rs, "eval_baseline", 3),
                "mustPassFail" to rs.getInt("must_pass_fail"),
                "registeredAt" to Rs.dateTime(rs, "ins_date"),
                "registeredBy" to rs.getString("activated_by_nm"),
                "canaryAt" to Rs.dateTime(rs, "canary_at"),
                "activatedAt" to Rs.dateTime(rs, "activated_at"),
                "rollbackAt" to Rs.dateTime(rs, "rollback_at"),
                "rollbackReason" to rs.getString("rollback_reason"),
                "note" to rs.getString("remark")
            )
        }
    }

    /** 릴리스 전체 건수 */
    fun countReleases(state: String?): Long {
        val sql = StringBuilder("SELECT count(*) FROM ax.tb_ai_serving_profile p WHERE p.service_cd = 'CHAT'")
        val params = MapSqlParameterSource()
        if (!state.isNullOrBlank()) {
            sql.append(" AND p.state_cd = :state")
            params.addValue("state", state.trim().uppercase())
        }
        return jdbcTemplate.queryForObject(sql.toString(), params, Long::class.java) ?: 0L
    }

    /**
     * 버전 태그(profileCd-vN)로 릴리스를 조회한다.
     */
    fun findReleaseByVer(profileCd: String, versionNo: Int): Map<String, Any?>? {
        val sql = """
            SELECT profile_id, profile_cd, version_no, profile_nm, state_cd,
                   eval_score, eval_baseline, eval_json, must_pass_fail, corpus_snapshot_id
            FROM ax.tb_ai_serving_profile
            WHERE service_cd = 'CHAT' AND profile_cd = :profileCd AND version_no = :versionNo
        """.trimIndent()

        val params = MapSqlParameterSource().addValue("profileCd", profileCd).addValue("versionNo", versionNo)

        return jdbcTemplate.query(sql, params) { rs, _ ->
            mapOf(
                "profileId" to rs.getInt("profile_id"),
                "ver" to "${rs.getString("profile_cd")}-v${rs.getInt("version_no")}",
                "name" to rs.getString("profile_nm"),
                "state" to rs.getString("state_cd"),
                "evalScore" to Rs.rate(rs, "eval_score", 3),
                "evalBaseline" to Rs.rate(rs, "eval_baseline", 3),
                "evalJson" to rs.getString("eval_json"),
                "mustPassFail" to rs.getInt("must_pass_fail"),
                "corpusSnapshotId" to Rs.intOrNull(rs, "corpus_snapshot_id")
            )
        }.firstOrNull()
    }

    /**
     * 릴리스를 등록한다. (No.197)
     *
     * @return 생성된 프로파일 ID
     */
    fun insertRelease(
        profileCd: String,
        versionNo: Int,
        profileNm: String,
        corpusSnapshotId: Int?,
        description: String?,
        actor: String
    ): Int {
        val sql = """
            INSERT INTO ax.tb_ai_serving_profile (
                service_cd, profile_cd, version_no, profile_nm, description,
                corpus_snapshot_id, state_cd, ins_user, upd_user
            ) VALUES (
                'CHAT', :profileCd, :versionNo, :profileNm, :description,
                :corpusSnapshotId, 'DRAFT', :actor, :actor
            )
            RETURNING profile_id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("profileCd", profileCd.take(40))
            .addValue("versionNo", versionNo)
            .addValue("profileNm", profileNm.take(200))
            .addValue("description", description?.take(1000))
            .addValue("corpusSnapshotId", corpusSnapshotId)
            .addValue("actor", actor)

        return jdbcTemplate.queryForObject(sql, params, Int::class.java) ?: 0
    }

    /** 릴리스에 모델 자산을 연결한다. */
    fun linkServingAsset(profileId: Int, roleCd: String, assetId: Int, actor: String): Int {
        val sql = """
            INSERT INTO ax.tb_ai_serving_asset (profile_id, role_cd, asset_id, ins_user)
            VALUES (:profileId, :roleCd, :assetId, :actor)
            ON CONFLICT (profile_id, role_cd) DO UPDATE SET asset_id = EXCLUDED.asset_id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("profileId", profileId)
            .addValue("roleCd", roleCd)
            .addValue("assetId", assetId)
            .addValue("actor", actor)

        return jdbcTemplate.update(sql, params)
    }

    /**
     * 서비스 적용 (No.199)
     *
     * 즉시 전환은 ACTIVE, 점진 전환은 CANARY 상태로 전환하고 기존 ACTIVE 는 RETIRED 로 내린다.
     *
     * @param immediate true 면 즉시 전환(ACTIVE), false 면 점진 전환(CANARY)
     */
    fun activateRelease(profileId: Int, immediate: Boolean, actor: String): Int {
        // 즉시 전환일 때만 기존 서비스 버전을 내린다.
        if (immediate) {
            jdbcTemplate.update(
                """
                UPDATE ax.tb_ai_serving_profile
                   SET state_cd    = 'RETIRED',
                       retired_at  = now(),
                       upd_date    = now(),
                       upd_user    = :actor
                 WHERE service_cd  = 'CHAT'
                   AND state_cd    = 'ACTIVE'
                   AND profile_id <> :profileId
                """.trimIndent(),
                MapSqlParameterSource().addValue("profileId", profileId).addValue("actor", actor)
            )
        }

        val sql = """
            UPDATE ax.tb_ai_serving_profile
               SET state_cd     = :stateCd,
                   canary_at    = CASE WHEN :stateCd = 'CANARY' THEN now() ELSE canary_at END,
                   activated_at = CASE WHEN :stateCd = 'ACTIVE' THEN now() ELSE activated_at END,
                   activated_by = :actor,
                   upd_date     = now(),
                   upd_user     = :actor
             WHERE profile_id = :profileId
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("profileId", profileId)
            .addValue("stateCd", if (immediate) "ACTIVE" else "CANARY")
            .addValue("actor", actor)

        return jdbcTemplate.update(sql, params)
    }

    /**
     * 직전 버전으로 롤백한다. (No.200)
     *
     * @return 롤백 후 서비스 버전 정보
     */
    fun rollbackRelease(reason: String?, actor: String): Map<String, Any?>? {
        // 1. 현재 서비스 중인 버전을 ROLLED_BACK 으로 내린다.
        val currentSql = """
            UPDATE ax.tb_ai_serving_profile
               SET state_cd        = 'ROLLED_BACK',
                   rollback_at     = now(),
                   rollback_reason = :reason,
                   upd_date        = now(),
                   upd_user        = :actor
             WHERE service_cd = 'CHAT'
               AND state_cd   = 'ACTIVE'
            RETURNING profile_id, prev_profile_id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("reason", reason?.take(500))
            .addValue("actor", actor)

        val current = jdbcTemplate.query(currentSql, params) { rs, _ ->
            Rs.intOrNull(rs, "profile_id") to Rs.intOrNull(rs, "prev_profile_id")
        }.firstOrNull() ?: return null

        // 2. 직전 버전을 다시 ACTIVE 로 올린다. prev 가 없으면 가장 최근 RETIRED 버전을 사용한다.
        val restoreSql = """
            UPDATE ax.tb_ai_serving_profile
               SET state_cd     = 'ACTIVE',
                   activated_at = now(),
                   activated_by = :actor,
                   retired_at   = NULL,
                   upd_date     = now(),
                   upd_user     = :actor
             WHERE profile_id = coalesce(
                 :prevProfileId,
                 (
                     SELECT p.profile_id FROM ax.tb_ai_serving_profile p
                      WHERE p.service_cd = 'CHAT' AND p.state_cd = 'RETIRED'
                      ORDER BY p.retired_at DESC NULLS LAST LIMIT 1
                 )
             )
            RETURNING profile_cd || '-v' || version_no AS serving_ver
        """.trimIndent()

        val restoreParams = MapSqlParameterSource()
            .addValue("prevProfileId", current.second)
            .addValue("actor", actor)

        val servingVer = jdbcTemplate.query(restoreSql, restoreParams) { rs, _ -> rs.getString("serving_ver") }
            .firstOrNull()

        return mapOf("servingVer" to servingVer, "rolledBackProfileId" to current.first)
    }

    /**
     * 릴리스를 보관한다. (No.201)
     */
    fun archiveRelease(profileId: Int, actor: String): Int {
        val sql = """
            UPDATE ax.tb_ai_serving_profile
               SET state_cd   = 'RETIRED',
                   retired_at = now(),
                   upd_date   = now(),
                   upd_user   = :actor
             WHERE profile_id = :profileId
               AND state_cd  <> 'ACTIVE'
        """.trimIndent()

        val params = MapSqlParameterSource().addValue("profileId", profileId).addValue("actor", actor)
        return jdbcTemplate.update(sql, params)
    }

    /**
     * 배포·학습 이력을 기록한다. (No.199 / No.200)
     */
    fun insertDeployLog(
        actionCd: String,
        profileId: Int?,
        profileNm: String,
        fromProfileId: Int?,
        fromProfileNm: String?,
        reason: String?,
        actorUserId: String,
        actorDeptNm: String?
    ) {
        val sql = """
            INSERT INTO ax.tb_ai_serving_deploy_log (
                log_at, service_cd, action_cd, profile_id, profile_nm,
                from_profile_id, from_profile_nm, reason, actor_user_id, actor_dept_nm
            ) VALUES (
                now(), 'CHAT', :actionCd, :profileId, :profileNm,
                :fromProfileId, :fromProfileNm, :reason, :actorUserId, :actorDeptNm
            )
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("actionCd", actionCd)
            .addValue("profileId", profileId)
            .addValue("profileNm", profileNm.take(200))
            .addValue("fromProfileId", fromProfileId)
            .addValue("fromProfileNm", fromProfileNm?.take(200))
            .addValue("reason", reason?.take(500))
            .addValue("actorUserId", actorUserId)
            .addValue("actorDeptNm", actorDeptNm)

        jdbcTemplate.update(sql, params)
    }

    /**
     * 배포·학습 이력을 조회한다. (No.209)
     */
    fun findDeployLogs(limit: Int, offset: Int): List<Map<String, Any?>> {
        val sql = """
            SELECT l.log_at, l.action_cd, l.profile_nm, l.from_profile_nm, l.reason,
                   l.actor_user_id, l.actor_dept_nm, u.user_nm
            FROM ax.tb_ai_serving_deploy_log l
            LEFT JOIN ax.tb_sys_user u ON u.user_id = l.actor_user_id
            ORDER BY l.log_at DESC
            LIMIT :limit OFFSET :offset
        """.trimIndent()

        val params = MapSqlParameterSource().addValue("limit", limit).addValue("offset", offset)

        return jdbcTemplate.query(sql, params) { rs, _ ->
            mapOf(
                "ts" to Rs.dateTime(rs, "log_at"),
                "type" to rs.getString("action_cd"),
                "detail" to buildString {
                    rs.getString("from_profile_nm")?.let { append("$it → ") }
                    append(rs.getString("profile_nm"))
                    rs.getString("reason")?.let { append(" ($it)") }
                },
                "by" to (rs.getString("user_nm") ?: rs.getString("actor_user_id")),
                "byDept" to rs.getString("actor_dept_nm")
            )
        }
    }

    /** 배포 이력 전체 건수 */
    fun countDeployLogs(): Long =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ax.tb_ai_serving_deploy_log", MapSqlParameterSource(), Long::class.java
        ) ?: 0L

    /**
     * 파인튜닝 체크포인트 목록을 조회한다. (No.205)
     */
    fun findFinetuneAssets(state: String?, limit: Int, offset: Int): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT
                a.asset_id, a.asset_key, a.version_tag, a.asset_nm, a.base_model, a.base_revision,
                a.lora_rank, a.train_method_cd, a.release_id, a.artifact_path, a.artifact_size,
                a.state_cd, a.verified_at, a.ins_date, a.remark
            FROM ax.tb_ai_model_asset a
            WHERE a.asset_kind_cd = 'LORA'
            """.trimIndent()
        )

        val params = MapSqlParameterSource()
        if (!state.isNullOrBlank()) {
            sql.append(" AND a.state_cd = :state")
            params.addValue("state", state.trim().uppercase())
        }

        sql.append("\nORDER BY a.ins_date DESC\nLIMIT :limit OFFSET :offset")
        params.addValue("limit", limit).addValue("offset", offset)

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "ftId" to "${rs.getString("asset_key")}:${rs.getString("version_tag")}",
                "assetId" to rs.getInt("asset_id"),
                "name" to rs.getString("asset_nm"),
                "baseModel" to rs.getString("base_model"),
                "baseRevision" to rs.getString("base_revision"),
                "method" to rs.getString("train_method_cd"),
                "loraRank" to Rs.intOrNull(rs, "lora_rank"),
                "releaseId" to rs.getString("release_id"),
                "artifactPath" to rs.getString("artifact_path"),
                "artifactSize" to Rs.longOrNull(rs, "artifact_size"),
                "state" to rs.getString("state_cd"),
                "verifiedAt" to Rs.dateTime(rs, "verified_at"),
                "startedAt" to Rs.dateTime(rs, "ins_date"),
                "remark" to rs.getString("remark")
            )
        }
    }

    /** 파인튜닝 체크포인트 전체 건수 */
    fun countFinetuneAssets(state: String?): Long {
        val sql = StringBuilder("SELECT count(*) FROM ax.tb_ai_model_asset WHERE asset_kind_cd = 'LORA'")
        val params = MapSqlParameterSource()
        if (!state.isNullOrBlank()) {
            sql.append(" AND state_cd = :state")
            params.addValue("state", state.trim().uppercase())
        }
        return jdbcTemplate.queryForObject(sql.toString(), params, Long::class.java) ?: 0L
    }

    /**
     * 파인튜닝 자산을 등록한다. (No.206)
     *
     * @return 생성된 자산 ID
     */
    fun insertFinetuneAsset(
        assetKey: String,
        versionTag: String,
        assetNm: String,
        baseModel: String,
        trainMethodCd: String,
        artifactPath: String,
        remark: String?,
        actor: String
    ): Int {
        val sql = """
            INSERT INTO ax.tb_ai_model_asset (
                asset_kind_cd, asset_key, version_tag, asset_nm, base_model,
                train_method_cd, artifact_path, state_cd, remark, ins_user, upd_user
            ) VALUES (
                'LORA', :assetKey, :versionTag, :assetNm, :baseModel,
                :trainMethodCd, :artifactPath, 'REGISTERED', :remark, :actor, :actor
            )
            RETURNING asset_id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("assetKey", assetKey.take(50))
            .addValue("versionTag", versionTag.take(40))
            .addValue("assetNm", assetNm.take(200))
            .addValue("baseModel", baseModel.take(200))
            .addValue("trainMethodCd", trainMethodCd.take(20))
            .addValue("artifactPath", artifactPath.take(500))
            .addValue("remark", remark?.take(1000))
            .addValue("actor", actor)

        return jdbcTemplate.queryForObject(sql, params, Int::class.java) ?: 0
    }

    /**
     * 파인튜닝 상세를 조회한다. (No.207)
     */
    fun findFinetuneAsset(assetKey: String, versionTag: String): Map<String, Any?>? {
        val sql = """
            SELECT a.asset_id, a.asset_key, a.version_tag, a.asset_nm, a.base_model, a.base_revision,
                   a.lora_rank, a.train_method_cd, a.artifact_path, a.artifact_size, a.checksum,
                   a.state_cd, a.verified_at, a.remark, a.ins_date
            FROM ax.tb_ai_model_asset a
            WHERE a.asset_kind_cd = 'LORA' AND a.asset_key = :assetKey AND a.version_tag = :versionTag
        """.trimIndent()

        val params = MapSqlParameterSource().addValue("assetKey", assetKey).addValue("versionTag", versionTag)

        return jdbcTemplate.query(sql, params) { rs, _ ->
            mapOf(
                "ftId" to "${rs.getString("asset_key")}:${rs.getString("version_tag")}",
                "config" to mapOf(
                    "baseModel" to rs.getString("base_model"),
                    "baseRevision" to rs.getString("base_revision"),
                    "method" to rs.getString("train_method_cd"),
                    "loraRank" to Rs.intOrNull(rs, "lora_rank"),
                    "artifactPath" to rs.getString("artifact_path"),
                    "artifactSize" to Rs.longOrNull(rs, "artifact_size"),
                    "checksum" to rs.getString("checksum")
                ),
                "state" to rs.getString("state_cd"),
                "verifiedAt" to Rs.dateTime(rs, "verified_at"),
                "startedAt" to Rs.dateTime(rs, "ins_date"),
                "log" to rs.getString("remark")
            )
        }.firstOrNull()
    }

    /** 코퍼스 스냅샷 ID 를 코드로 조회한다. */
    fun findSnapshotId(snapshotCd: String): Int? {
        val sql = "SELECT snapshot_id FROM ax.tb_ai_corpus_snapshot WHERE snapshot_cd = :snapshotCd"
        return jdbcTemplate.query(sql, MapSqlParameterSource("snapshotCd", snapshotCd)) { rs, _ ->
            rs.getInt("snapshot_id")
        }.firstOrNull()
    }

    /** 자산 키·버전으로 asset_id 를 조회한다. */
    fun findAssetId(assetKey: String, versionTag: String): Int? {
        val sql = """
            SELECT asset_id FROM ax.tb_ai_model_asset
            WHERE asset_key = :assetKey AND version_tag = :versionTag
            LIMIT 1
        """.trimIndent()
        val params = MapSqlParameterSource().addValue("assetKey", assetKey).addValue("versionTag", versionTag)
        return jdbcTemplate.query(sql, params) { rs, _ -> rs.getInt("asset_id") }.firstOrNull()
    }

    /** 다음 버전 번호를 채번한다. */
    fun nextVersionNo(profileCd: String): Int {
        val sql = """
            SELECT coalesce(max(version_no), 0) + 1
            FROM ax.tb_ai_serving_profile
            WHERE service_cd = 'CHAT' AND profile_cd = :profileCd
        """.trimIndent()
        return jdbcTemplate.queryForObject(sql, MapSqlParameterSource("profileCd", profileCd), Int::class.java) ?: 1
    }

    // =================================================================================
    // SY-12. Agent 실행 현황
    // =================================================================================

    /**
     * Agent 요약을 조회한다. (No.210)
     */
    fun findAgentSummary(): Map<String, Any?> {
        val sql = """
            SELECT
                (SELECT count(*) FROM ax.tb_ai_agent WHERE use_flg = 'Y')                    AS agent_cnt,
                count(*) FILTER (WHERE r.run_at >= now() - interval '1 minute')               AS events_per_min,
                count(DISTINCT r.agent_id) FILTER (WHERE r.run_at >= now() - interval '10 minutes') AS active_agent_cnt,
                round(avg(r.elapsed_ms) FILTER (WHERE r.run_at >= now() - interval '1 hour')) AS avg_elapsed_ms
            FROM ax.tb_ai_agent_run r
            WHERE r.run_at >= now() - interval '1 hour'
        """.trimIndent()

        return jdbcTemplate.queryForObject(sql, MapSqlParameterSource()) { rs, _ ->
            mapOf(
                "agentCnt" to rs.getLong("agent_cnt"),
                "activeAgentCnt" to rs.getLong("active_agent_cnt"),
                "eventsPerMin" to rs.getLong("events_per_min"),
                "avgResponseSec" to Rs.intOrNull(rs, "avg_elapsed_ms")?.let { Math.round(it / 100.0) / 10.0 }
            )
        } ?: emptyMap()
    }

    /**
     * Master AI 파이프라인 단계를 조회한다. (No.212)
     */
    fun findPipelineStages(): List<Map<String, Any?>> {
        val sql = """
            SELECT s.stage_id, s.stage_nm, s.stage_desc, s.sort_seq
            FROM ax.tb_ai_pipeline_stage s
            WHERE s.use_flg = 'Y'
            ORDER BY s.sort_seq
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource()) { rs, _ ->
            mapOf(
                "stage" to rs.getInt("sort_seq"),
                "name" to rs.getString("stage_nm"),
                "desc" to rs.getString("stage_desc")
            )
        }
    }

    /**
     * Agent 재시작을 기록한다. (No.213)
     *
     * @return 생성된 실행 이력 ID
     */
    fun insertAgentRestart(agentNo: String, actor: String): Long {
        val sql = """
            INSERT INTO ax.tb_ai_agent_run (agent_id, run_at, state_cd, message, err_flg)
            SELECT a.agent_id, now(), 'RUNNING', :message, 'N'
            FROM ax.tb_ai_agent a
            WHERE a.agent_no = :agentNo
            RETURNING run_id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("agentNo", agentNo)
            .addValue("message", "관리자($actor) 요청으로 재시작")

        return jdbcTemplate.query(sql, params) { rs, _ -> rs.getLong("run_id") }.firstOrNull() ?: 0L
    }

    /**
     * Agent 실행 이력을 조회한다. (No.214)
     */
    fun findAgentRuns(
        agentNo: String,
        from: LocalDate,
        to: LocalDate,
        state: String?,
        limit: Int,
        offset: Int
    ): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT r.run_id, r.run_at, r.state_cd, r.throughput_txt, r.elapsed_ms, r.message, r.err_flg
            FROM ax.tb_ai_agent_run r
            INNER JOIN ax.tb_ai_agent a ON a.agent_id = r.agent_id
            WHERE a.agent_no = :agentNo
              AND r.run_at  >= :from
              AND r.run_at  <  :toExclusive
            """.trimIndent()
        )

        val params = MapSqlParameterSource()
            .addValue("agentNo", agentNo)
            .addValue("from", from.atStartOfDay())
            .addValue("toExclusive", to.plusDays(1).atStartOfDay())

        if (!state.isNullOrBlank()) {
            sql.append(" AND r.state_cd = :state")
            params.addValue("state", state.trim().uppercase())
        }

        sql.append("\nORDER BY r.run_at DESC\nLIMIT :limit OFFSET :offset")
        params.addValue("limit", limit).addValue("offset", offset)

        return jdbcTemplate.query(sql.toString(), params) { rs, _ ->
            mapOf(
                "runId" to rs.getLong("run_id"),
                "startedAt" to Rs.dateTime(rs, "run_at"),
                "state" to rs.getString("state_cd"),
                "throughput" to rs.getString("throughput_txt"),
                "elapsedMs" to Rs.intOrNull(rs, "elapsed_ms"),
                "errorMsg" to if (Rs.yn(rs, "err_flg")) rs.getString("message") else null,
                "message" to rs.getString("message")
            )
        }
    }

    /** Agent 실행 이력 전체 건수 */
    fun countAgentRuns(agentNo: String, from: LocalDate, to: LocalDate, state: String?): Long {
        val sql = StringBuilder(
            """
            SELECT count(*)
            FROM ax.tb_ai_agent_run r
            INNER JOIN ax.tb_ai_agent a ON a.agent_id = r.agent_id
            WHERE a.agent_no = :agentNo
              AND r.run_at  >= :from
              AND r.run_at  <  :toExclusive
            """.trimIndent()
        )

        val params = MapSqlParameterSource()
            .addValue("agentNo", agentNo)
            .addValue("from", from.atStartOfDay())
            .addValue("toExclusive", to.plusDays(1).atStartOfDay())

        if (!state.isNullOrBlank()) {
            sql.append(" AND r.state_cd = :state")
            params.addValue("state", state.trim().uppercase())
        }

        return jdbcTemplate.queryForObject(sql.toString(), params, Long::class.java) ?: 0L
    }

    /** Agent 존재 확인 */
    fun existsAgent(agentNo: String): Boolean {
        val sql = "SELECT count(*) FROM ax.tb_ai_agent WHERE agent_no = :agentNo AND use_flg = 'Y'"
        return (jdbcTemplate.queryForObject(sql, MapSqlParameterSource("agentNo", agentNo), Long::class.java) ?: 0L) > 0
    }
}
