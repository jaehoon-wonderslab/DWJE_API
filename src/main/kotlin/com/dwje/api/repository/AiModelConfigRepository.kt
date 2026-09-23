package com.dwje.api.repository

import com.dwje.api.common.util.Rs
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

/**
 * AI 모델 설정 Repository (SY-10 · AI 모델 설정)
 *
 * 다루는 표
 *  - `ax.tb_ai_model_config`  Agent 별 임계치·분류 기준 키/값
 *
 * 읽기 전용 조회는 [QualityRepository.findModelConfigs] 에도 있다. 그쪽은 **사용 중 행만**
 * 예측·판정 로직에 먹이는 용도라 질의가 다르다. 이 표를 관리하는 화면은 사용 중지 행까지 봐야 한다.
 */
@Repository
class AiModelConfigRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) {

    /** 설정 목록 — 관리 화면용이라 사용 중지 행도 포함한다. */
    fun findConfigs(categoryCd: String?, agentNo: String?, useFlg: Boolean?): List<Map<String, Any?>> {
        val sql = StringBuilder(
            """
            SELECT
                mc.config_id, mc.category_cd, cc.code_nm AS category_nm,
                mc.config_key, mc.config_nm, mc.config_value, mc.value_type_cd,
                vt.code_nm AS value_type_nm, mc.unit, mc.opt_values, mc.description, mc.use_flg,
                a.agent_id, a.agent_no, a.agent_nm,
                mc.upd_date, u.user_nm AS upd_user_nm
            FROM ax.tb_ai_model_config mc
            LEFT JOIN ax.tb_ai_agent  a  ON a.agent_id  = mc.agent_id
            LEFT JOIN ax.tb_sys_user  u  ON u.user_id   = mc.upd_user
            LEFT JOIN ax.tb_sys_code  cc ON cc.group_cd = 'AI_CONFIG_CAT'  AND cc.code = mc.category_cd
            LEFT JOIN ax.tb_sys_code  vt ON vt.group_cd = 'AI_VALUE_TYPE'  AND vt.code = mc.value_type_cd
            WHERE 1 = 1
            """.trimIndent()
        )
        val params = MapSqlParameterSource()
        if (!categoryCd.isNullOrBlank()) {
            sql.append(" AND mc.category_cd = :categoryCd")
            params.addValue("categoryCd", categoryCd.trim())
        }
        if (!agentNo.isNullOrBlank()) {
            sql.append(" AND a.agent_no = :agentNo")
            params.addValue("agentNo", agentNo.trim())
        }
        if (useFlg != null) {
            sql.append(" AND mc.use_flg = :useFlg")
            params.addValue("useFlg", if (useFlg) "Y" else "N")
        }
        sql.append("\nORDER BY cc.sort_seq, mc.category_cd, mc.config_key")

        return jdbcTemplate.query(sql.toString(), params) { rs, _ -> configRow(rs) }
    }

    /** 설정 한 건 — 없으면 null */
    fun findConfig(configId: Int): Map<String, Any?>? =
        jdbcTemplate.query(
            """
            SELECT
                mc.config_id, mc.category_cd, cc.code_nm AS category_nm,
                mc.config_key, mc.config_nm, mc.config_value, mc.value_type_cd,
                vt.code_nm AS value_type_nm, mc.unit, mc.opt_values, mc.description, mc.use_flg,
                a.agent_id, a.agent_no, a.agent_nm,
                mc.upd_date, u.user_nm AS upd_user_nm
            FROM ax.tb_ai_model_config mc
            LEFT JOIN ax.tb_ai_agent  a  ON a.agent_id  = mc.agent_id
            LEFT JOIN ax.tb_sys_user  u  ON u.user_id   = mc.upd_user
            LEFT JOIN ax.tb_sys_code  cc ON cc.group_cd = 'AI_CONFIG_CAT' AND cc.code = mc.category_cd
            LEFT JOIN ax.tb_sys_code  vt ON vt.group_cd = 'AI_VALUE_TYPE' AND vt.code = mc.value_type_cd
            WHERE mc.config_id = :configId
            """.trimIndent(),
            MapSqlParameterSource("configId", configId)
        ) { rs, _ -> configRow(rs) }.firstOrNull()

    /** (분류, 키) 로 찾는다 — 중복 등록을 사전에 걸러내기 위함 (`uq_tb_ai_model_config`) */
    fun findConfigByKey(categoryCd: String, configKey: String): Map<String, Any?>? =
        jdbcTemplate.query(
            """
            SELECT config_id, category_cd, config_key, config_nm, use_flg
            FROM ax.tb_ai_model_config
            WHERE category_cd = :categoryCd AND config_key = :configKey
            """.trimIndent(),
            MapSqlParameterSource().addValue("categoryCd", categoryCd).addValue("configKey", configKey)
        ) { rs, _ ->
            mapOf(
                "configId" to rs.getInt("config_id"),
                "category" to rs.getString("category_cd"),
                "key" to rs.getString("config_key"),
                "name" to rs.getString("config_nm"),
                "active" to Rs.yn(rs, "use_flg")
            )
        }.firstOrNull()

    /** 설정 등록 — 중복이면 [org.springframework.dao.DuplicateKeyException] */
    fun insertConfig(
        categoryCd: String,
        configKey: String,
        configNm: String,
        configValue: String,
        valueTypeCd: String,
        unit: String?,
        optValues: String?,
        description: String?,
        agentNo: String?,
        actor: String
    ): Int {
        val sql = """
            INSERT INTO ax.tb_ai_model_config (
                agent_id, category_cd, config_key, config_nm, config_value,
                value_type_cd, unit, opt_values, description, use_flg, ins_user, upd_user
            )
            VALUES (
                (SELECT agent_id FROM ax.tb_ai_agent WHERE agent_no = :agentNo),
                :categoryCd, :configKey, :configNm, :configValue,
                :valueTypeCd, :unit, :optValues, :description, 'Y', :actor, :actor
            )
            RETURNING config_id
        """.trimIndent()

        return jdbcTemplate.queryForObject(sql, configParams(
            categoryCd, configKey, configNm, configValue, valueTypeCd, unit, optValues, description, agentNo, actor
        ), Int::class.java) ?: 0
    }

    /** 설정 수정 — 키(category_cd, config_key)는 바꾸지 않는다 */
    fun updateConfig(
        configId: Int,
        configNm: String,
        configValue: String,
        valueTypeCd: String,
        unit: String?,
        optValues: String?,
        description: String?,
        agentNo: String?,
        actor: String
    ): Int {
        val sql = """
            UPDATE ax.tb_ai_model_config
               SET agent_id      = (SELECT agent_id FROM ax.tb_ai_agent WHERE agent_no = :agentNo),
                   config_nm     = :configNm,
                   config_value  = :configValue,
                   value_type_cd = :valueTypeCd,
                   unit          = :unit,
                   opt_values    = :optValues,
                   description   = :description,
                   upd_date      = now(),
                   upd_user      = :actor
             WHERE config_id = :configId
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("configId", configId)
            .addValue("configNm", configNm.take(100))
            .addValue("configValue", configValue.take(300))
            .addValue("valueTypeCd", valueTypeCd)
            .addValue("unit", unit?.take(20))
            .addValue("optValues", optValues?.take(500))
            .addValue("description", description?.take(300))
            .addValue("agentNo", agentNo)
            .addValue("actor", actor)

        return jdbcTemplate.update(sql, params)
    }

    /** 값만 바꾼다 — 화면의 일괄 저장이 쓴다 */
    fun updateConfigValue(configId: Int, configValue: String, actor: String): Int =
        jdbcTemplate.update(
            """
            UPDATE ax.tb_ai_model_config
               SET config_value = :configValue, upd_date = now(), upd_user = :actor
             WHERE config_id = :configId
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("configId", configId)
                .addValue("configValue", configValue.take(300))
                .addValue("actor", actor)
        )

    /**
     * 사용/미사용 전환.
     *
     * 물리 삭제하지 않는다 — 예측·판정 코드가 (분류, 키)로 값을 찾고, 지운 키는
     * 코드의 기본값으로 조용히 돌아가 버린다. 꺼 두면 화면에서 그 사실이 보인다.
     */
    fun updateConfigUseFlg(configId: Int, on: Boolean, actor: String): Int =
        jdbcTemplate.update(
            """
            UPDATE ax.tb_ai_model_config
               SET use_flg = :useFlg, upd_date = now(), upd_user = :actor
             WHERE config_id = :configId AND use_flg <> :useFlg
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("configId", configId)
                .addValue("useFlg", if (on) "Y" else "N")
                .addValue("actor", actor)
        )

    private fun configParams(
        categoryCd: String, configKey: String, configNm: String, configValue: String,
        valueTypeCd: String, unit: String?, optValues: String?, description: String?,
        agentNo: String?, actor: String
    ) = MapSqlParameterSource()
        .addValue("categoryCd", categoryCd)
        .addValue("configKey", configKey.take(50))
        .addValue("configNm", configNm.take(100))
        .addValue("configValue", configValue.take(300))
        .addValue("valueTypeCd", valueTypeCd)
        .addValue("unit", unit?.take(20))
        .addValue("optValues", optValues?.take(500))
        .addValue("description", description?.take(300))
        .addValue("agentNo", agentNo)
        .addValue("actor", actor)

    private fun configRow(rs: java.sql.ResultSet): Map<String, Any?> = mapOf(
        "configId" to rs.getInt("config_id"),
        "category" to rs.getString("category_cd"),
        "categoryNm" to rs.getString("category_nm"),
        "key" to rs.getString("config_key"),
        "name" to rs.getString("config_nm"),
        "value" to rs.getString("config_value"),
        "valueType" to rs.getString("value_type_cd"),
        "valueTypeNm" to rs.getString("value_type_nm"),
        "unit" to rs.getString("unit"),
        "options" to rs.getString("opt_values"),
        "description" to rs.getString("description"),
        "agentCd" to rs.getString("agent_no"),
        "agentNm" to rs.getString("agent_nm"),
        "useFlg" to rs.getString("use_flg"),
        "applied" to Rs.yn(rs, "use_flg"),
        "updatedAt" to Rs.dateTime(rs, "upd_date"),
        "updatedBy" to rs.getString("upd_user_nm")
    )
}
