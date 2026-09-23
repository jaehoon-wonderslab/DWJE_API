package com.dwje.api.repository

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

/**
 * AOI 불량 판정 화면 보조 Repository (요구 9)
 *
 * AOI 설비는 설비 마스터(`mes.tb_md_eqpt`)의 모델명·설비명에 `AOI` 가 들어간 것으로 식별한다
 * (기존 판정 드리프트 조회와 같은 기준).
 *
 * 불량 목록·상세·NAS 이미지(`/quality/aoi/defects`, `/quality/aoi/defects/{defectId}`,
 * `/files/aoi-images/{imageId}`)는 2026-09-23 에 뺐다 — 웹이 부르지 않았고, 이미지 매핑 표
 * `ax.tb_aoi_defect_image` 를 V42 가 지운다. 남은 것은 화면 필터용 설비 목록뿐이다.
 */
@Repository
class AoiDefectRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) {

    /** AOI 설비 목록 — 화면 필터용. */
    fun findAoiEquipments(plantCd: String): List<Map<String, Any?>> {
        val sql = """
            SELECT e.eqpt_cd, e.eqpt_nm, e.model_nm
            FROM mes.tb_md_eqpt e
            WHERE e.plant_cd = :plantCd
              AND (e.model_nm ILIKE '%AOI%' OR e.eqpt_nm ILIKE '%AOI%')
            ORDER BY e.eqpt_nm
        """.trimIndent()
        return jdbcTemplate.query(sql, MapSqlParameterSource("plantCd", plantCd)) { rs, _ ->
            mapOf("eqptCd" to rs.getString("eqpt_cd"), "eqptNm" to rs.getString("eqpt_nm"), "modelNm" to rs.getString("model_nm"))
        }
    }
}
