package com.dwje.api.repository

import com.dwje.api.common.util.DateUtils
import com.dwje.api.common.util.Rs
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.LocalDate

/**
 * AOI 불량 상세 Repository (요구 9)
 *
 * ## MES 에 "AOI 불량 판정" 테이블은 없다
 * AOI 설비는 설비 마스터(`mes.tb_md_eqpt`)의 모델명·설비명에 `AOI` 가 들어간 것으로 식별한다
 * (기존 판정 드리프트 조회와 같은 기준). AOI 불량 한 건 = 그 설비로 찍힌 **라벨 이력 한 행**
 * (`mes.tb_pop_label_hist`, `defect > 0`) 이다.
 *
 * 불량 유형은 `mes.tb_pop_defect_hist` 를 같은 (plant, wc, lot, serial) 로 붙여 읽는데,
 * **실측(2026-09-10) 으로 AOI 라벨 6,949건에 불량 이력이 0건**이라 대부분 null 로 온다.
 * 그래서 `defectTypeCd` 필터는 불량 이력이 있는 행에만 걸린다.
 *
 * ## defectId
 * MES 에 불량 id 가 없어 라벨 PK 를 `plant-wc-lot-serial` 로 이어 만든다. 이미지 매핑
 * 테이블(`ax.tb_aoi_defect_image.defect_id`)도 같은 문자열을 키로 쓴다.
 */
@Repository
class AoiDefectRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) {

    fun findDefects(
        plantCd: String, from: LocalDate, to: LocalDate,
        eqptCd: String?, defectTypeCd: String?, lotNo: String?, wcCd: String?,
        limit: Int, offset: Int
    ): List<Map<String, Any?>> {
        val sql = """
            ${baseSql()}
            ORDER BY lh.ins_date DESC, lh.lot_no DESC, lh.serial_no DESC
            LIMIT :limit OFFSET :offset
        """.trimIndent()
        val params = baseParams(plantCd, from, to, eqptCd, defectTypeCd, lotNo, wcCd)
            .addValue("limit", limit).addValue("offset", offset)
        return jdbcTemplate.query(sql, params) { rs, _ -> mapRow(rs) }
    }

    fun countDefects(
        plantCd: String, from: LocalDate, to: LocalDate,
        eqptCd: String?, defectTypeCd: String?, lotNo: String?, wcCd: String?
    ): Long {
        val sql = "SELECT count(*) FROM (\n${baseSql()}\n) t"
        return jdbcTemplate.queryForObject(
            sql, baseParams(plantCd, from, to, eqptCd, defectTypeCd, lotNo, wcCd), Long::class.java
        ) ?: 0L
    }

    /** 라벨 PK 로 한 건. AOI 설비가 아니거나 불량이 없으면 null. */
    fun findDefect(plantCd: String, wcCd: String, lotNo: String, serialNo: String): Map<String, Any?>? {
        val sql = """
            ${baseSql(byKey = true)}
        """.trimIndent()
        val params = MapSqlParameterSource()
            .addValue("plantCd", plantCd).addValue("wcCd", wcCd)
            .addValue("lotNo", lotNo).addValue("serialNo", serialNo)
        return jdbcTemplate.query(sql, params) { rs, _ -> mapRow(rs) }.firstOrNull()
    }

    /** 한 라벨에 붙은 불량 이력 전부 (유형별 수량). */
    fun findDefectBreakdown(plantCd: String, wcCd: String, lotNo: String, serialNo: String): List<Map<String, Any?>> {
        val sql = """
            SELECT dh.defect_cd, md.defect_nm, dh.qty, dh.remark
            FROM mes.tb_pop_defect_hist dh
            LEFT JOIN mes.tb_md_defect md ON md.plant_cd = dh.plant_cd AND md.defect_cd = dh.defect_cd
            WHERE dh.plant_cd = :plantCd AND dh.wc_cd = :wcCd AND dh.lot_no = :lotNo AND dh.serial_no = :serialNo
            ORDER BY dh.qty DESC, dh.defect_cd
        """.trimIndent()
        val params = MapSqlParameterSource()
            .addValue("plantCd", plantCd).addValue("wcCd", wcCd)
            .addValue("lotNo", lotNo).addValue("serialNo", serialNo)
        return jdbcTemplate.query(sql, params) { rs, _ ->
            mapOf(
                "defectTypeCd" to rs.getString("defect_cd"),
                "defectTypeNm" to rs.getString("defect_nm"),
                "qty" to Rs.qty(rs, "qty"),
                "remark" to rs.getString("remark")
            )
        }
    }

    /** 이미지 매핑 (`ax.tb_aoi_defect_image`) — seq 순. */
    fun findImages(defectId: String): List<AoiImageRow> {
        val sql = """
            SELECT image_id, defect_id, defect_cd, seq, nas_path, captured_at, file_size
            FROM ax.tb_aoi_defect_image
            WHERE defect_id = :defectId
            ORDER BY seq, image_id
        """.trimIndent()
        return jdbcTemplate.query(sql, MapSqlParameterSource("defectId", defectId)) { rs, _ -> mapImage(rs) }
    }

    fun findImage(imageId: Long): AoiImageRow? {
        val sql = """
            SELECT image_id, defect_id, defect_cd, seq, nas_path, captured_at, file_size
            FROM ax.tb_aoi_defect_image
            WHERE image_id = :imageId
        """.trimIndent()
        return jdbcTemplate.query(sql, MapSqlParameterSource("imageId", imageId)) { rs, _ -> mapImage(rs) }.firstOrNull()
    }

    /** 목록의 각 불량에 붙은 이미지 수. */
    fun countImages(defectIds: Collection<String>): Map<String, Int> {
        if (defectIds.isEmpty()) return emptyMap()
        val sql = """
            SELECT defect_id, count(*) AS cnt
            FROM ax.tb_aoi_defect_image
            WHERE defect_id = ANY(:ids)
            GROUP BY defect_id
        """.trimIndent()
        val params = MapSqlParameterSource("ids", defectIds.toTypedArray())
        return jdbcTemplate.query(sql, params) { rs, _ -> rs.getString("defect_id") to rs.getInt("cnt") }.toMap()
    }

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

    // ---------------------------------------------------------------------------------

    private fun baseSql(byKey: Boolean = false): String {
        val where = if (byKey) """
            WHERE lh.plant_cd  = :plantCd
              AND lh.wc_cd     = :wcCd
              AND lh.lot_no    = :lotNo
              AND lh.serial_no = :serialNo
              AND lh.del_flg   = 'N'
              AND coalesce(lh.defect, 0) > 0
        """ else """
            WHERE lh.plant_cd  = :plantCd
              AND lh.del_flg   = 'N'
              AND coalesce(lh.defect, 0) > 0
              AND lh.ins_date >= :from
              AND lh.ins_date <  :toExclusive
              AND (:eqptCd::varchar IS NULL OR lh.eqpt_cd = :eqptCd)
              AND (:wcCd::varchar   IS NULL OR lh.wc_cd   = :wcCd)
              AND (:lotNo::varchar  IS NULL OR lh.lot_no  = :lotNo)
              AND (:defectTypeCd::varchar IS NULL OR d.defect_cd = :defectTypeCd)
        """
        return """
            SELECT lh.plant_cd, lh.wc_cd, lh.lot_no, lh.serial_no,
                   lh.plant_cd || '-' || lh.wc_cd || '-' || lh.lot_no || '-' || lh.serial_no AS defect_id,
                   lh.ins_date, lh.eqpt_cd, e.eqpt_nm, w.wc_nm,
                   lh.item_cd, p.model_cd, p.model_nm, lh.mold_cd, lh.cavity,
                   lh.normal, lh.defect, lh.sample, lh.grade, lh.prod_remark,
                   d.defect_cd, md.defect_nm, d.defect_cnt
            FROM mes.tb_pop_label_hist lh
            INNER JOIN mes.tb_md_eqpt e
                    ON e.plant_cd = lh.plant_cd AND e.eqpt_cd = lh.eqpt_cd
                   AND (e.model_nm ILIKE '%AOI%' OR e.eqpt_nm ILIKE '%AOI%')
            LEFT JOIN mes.tb_md_workcenter w ON w.plant_cd = lh.plant_cd AND w.wc_cd = lh.wc_cd
            LEFT JOIN ax.tb_prod_item_map pm ON pm.plant_cd = lh.plant_cd AND pm.item_cd = lh.item_cd
            LEFT JOIN ax.tb_prod_product p ON p.product_id = pm.product_id
            -- 대표 불량 유형 = 수량이 가장 큰 것. 이력이 없으면(AOI 는 대부분) null.
            LEFT JOIN LATERAL (
                SELECT dh.defect_cd, count(*) OVER () AS defect_cnt
                FROM mes.tb_pop_defect_hist dh
                WHERE dh.plant_cd = lh.plant_cd AND dh.wc_cd = lh.wc_cd
                  AND dh.lot_no = lh.lot_no AND dh.serial_no = lh.serial_no
                ORDER BY dh.qty DESC
                LIMIT 1
            ) d ON true
            LEFT JOIN mes.tb_md_defect md ON md.plant_cd = lh.plant_cd AND md.defect_cd = d.defect_cd
            $where
        """.trimIndent()
    }

    private fun baseParams(
        plantCd: String, from: LocalDate, to: LocalDate,
        eqptCd: String?, defectTypeCd: String?, lotNo: String?, wcCd: String?
    ): MapSqlParameterSource = MapSqlParameterSource()
        .addValue("plantCd", plantCd)
        .addValue("from", from.atStartOfDay())
        .addValue("toExclusive", to.plusDays(1).atStartOfDay())
        .addValue("eqptCd", eqptCd?.trim()?.takeIf { it.isNotBlank() })
        .addValue("defectTypeCd", defectTypeCd?.trim()?.takeIf { it.isNotBlank() })
        .addValue("lotNo", lotNo?.trim()?.takeIf { it.isNotBlank() })
        .addValue("wcCd", wcCd?.trim()?.takeIf { it.isNotBlank() })

    private fun mapRow(rs: java.sql.ResultSet): Map<String, Any?> = mapOf(
        "defectId" to rs.getString("defect_id"),
        "judgedAt" to DateUtils.format(rs.getObject("ins_date")),
        "plantCd" to rs.getString("plant_cd"),
        "processId" to rs.getString("wc_cd"),
        "processNm" to rs.getString("wc_nm"),
        "eqptCd" to rs.getString("eqpt_cd"),
        "eqptNm" to rs.getString("eqpt_nm"),
        "lotNo" to rs.getString("lot_no"),
        "serialNo" to rs.getString("serial_no"),
        "itemCd" to rs.getString("item_cd"),
        "model" to rs.getString("model_cd"),
        "modelNm" to rs.getString("model_nm"),
        "moldCd" to rs.getString("mold_cd"),
        "cavity" to Rs.intOrNull(rs, "cavity"),
        "okQty" to Rs.qty(rs, "normal"),
        "ngQty" to Rs.qty(rs, "defect"),
        "sampleQty" to Rs.qty(rs, "sample"),
        "grade" to rs.getString("grade"),
        "remark" to rs.getString("prod_remark"),
        "defectTypeCd" to rs.getString("defect_cd"),
        "defectTypeNm" to rs.getString("defect_nm"),
        "defectTypeCnt" to (Rs.intOrNull(rs, "defect_cnt") ?: 0)
    )

    private fun mapImage(rs: java.sql.ResultSet): AoiImageRow = AoiImageRow(
        imageId = rs.getLong("image_id"),
        defectId = rs.getString("defect_id"),
        defectCd = rs.getString("defect_cd"),
        seq = rs.getInt("seq"),
        nasPath = rs.getString("nas_path"),
        capturedAt = DateUtils.format(rs.getObject("captured_at")),
        sizeBytes = rs.getObject("file_size")?.let { (it as Number).toLong() }
    )
}

/** 이미지 매핑 한 건 */
data class AoiImageRow(
    val imageId: Long,
    val defectId: String,
    val defectCd: String?,
    val seq: Int,
    val nasPath: String,
    val capturedAt: String?,
    val sizeBytes: Long?
)
