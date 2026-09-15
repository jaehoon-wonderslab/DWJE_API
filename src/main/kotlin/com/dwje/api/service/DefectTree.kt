package com.dwje.api.service

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.util.DefectSql
import com.dwje.api.common.util.WorkcenterNames
import com.dwje.api.common.util.safeRate
import com.dwje.api.repository.DefectTreeBaseRow
import com.dwje.api.repository.DefectTreeTypeRow
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 불량 상세 분해 트리의 한 단계.
 *
 * 화면이 `levels=wc,item,eqpt,defect` 처럼 순서를 고른다. 유형(defect)은 수량 원장이 아니라
 * 라벨 불량을 안분한 값이라 **맨 마지막에만** 올 수 있다 — 유형 아래에 설비를 두면 안분을 두 번 하게 된다.
 */
enum class DefectTreeLevel(val key: String, val label: String) {
    WC("wc", "공정"),
    ITEM("item", "제품"),
    EQPT("eqpt", "설비"),
    DEFECT("defect", "불량 유형");

    companion object {
        /**
         * 기본 순서 — 공정 > 제품 > 설비 > 불량 유형.
         *
         * 2026-08-12~09-11 실측(라벨 175,145행): 공정 30 · 제품 175 · 설비 734, (공정,제품) 179 · (공정,설비) 738 ·
         * (공정,제품,설비) 1,267. 설비 한 대가 만드는 제품은 평균 1.7종(최대 17)이고, 한 제품을 만드는 설비는
         * 평균 7.1대(최대 150)다. 제품을 설비 위에 두면 2단계가 179행으로 좁아지고, 3단계에서 **같은 제품을 만든
         * 설비끼리** 불량률을 나란히 비교할 수 있다(설비별로 보는 목적이 그 편차다). 설비를 위에 두면 2단계가
         * 738행으로 넓고 3단계는 대부분 1~2행이라 펼칠 이유가 없다. 실적 집계 화면의 트리(일자→제품→설비)와도 같다.
         */
        val DEFAULT = listOf(WC, ITEM, EQPT, DEFECT)

        private val BY_KEY = entries.associateBy { it.key }

        /**
         * `wc,item,eqpt,defect` 꼴 문자열을 단계 목록으로 만든다. 비면 [DEFAULT].
         *
         * @throws InvalidParameterException 모르는 이름 · 중복 · 유형이 마지막이 아닌 경우
         */
        fun parse(levels: String?): List<DefectTreeLevel> {
            val raw = levels?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }.orEmpty()
            if (raw.isEmpty()) return DEFAULT
            val parsed = raw.map {
                BY_KEY[it] ?: throw InvalidParameterException(
                    "levels 는 ${entries.joinToString(",") { e -> e.key }} 중에서 고릅니다. [levels=$levels]", "levels"
                )
            }
            if (parsed.size != parsed.toSet().size) {
                throw InvalidParameterException("levels 에 같은 단계가 두 번 있습니다. [levels=$levels]", "levels")
            }
            val defectAt = parsed.indexOf(DEFECT)
            if (defectAt in 0 until parsed.lastIndex) {
                throw InvalidParameterException("불량 유형(defect)은 마지막 단계에만 올 수 있습니다. [levels=$levels]", "levels")
            }
            return parsed
        }
    }
}

/**
 * 불량 상세 분해 트리 조립 — 라벨 원장 집계행과 유형 안분행을 `levels` 순서대로 중첩한다.
 *
 * ## 규약
 * - 모든 단계 행에 `plantCd/plantNm · wcCd/wcNm · itemCd/itemNm · eqptCd/eqptNm · defectCd/defectNm` 열이 있고,
 *   그 단계까지 확정된 값만 채운다(나머지는 null). `level` 이 단계 이름이다.
 * - 수량 단계(공정·제품·설비)의 `okQty`·`ngQty` 는 라벨 원장 합이라 **상위 = 하위 합**이 항상 성립한다.
 * - 유형 단계는 `ngQty` 만 있다(`okQty`·`defectRate` 는 null — 유형에 정상 수량은 없다). 대신 `ratio` 가 상위
 *   `ngQty` 대비 %다. 안분·반올림으로 생기는 차액은 '유형 미상' 행(모자랄 때) 또는 가장 큰 유형에서 보정(넘칠 때)해
 *   **유형 합 = 상위 ngQty** 를 맞춘다. 유형 미상은 실제로 유형이 붙지 않은 라벨 몫이기도 하다(by-type 와 같다).
 * - `children` 은 비어 있지 않을 때만 싣는다(Tabulator 가 펼침 표시를 그 유무로 그린다).
 * - 정렬은 각 단계에서 `ngQty` 내림차순, 같으면 코드 순.
 */
object DefectTreeAssembler {

    fun assemble(
        levels: List<DefectTreeLevel>,
        base: List<DefectTreeBaseRow>,
        types: List<DefectTreeTypeRow>,
        plantCd: String
    ): List<Map<String, Any?>> {
        require(levels.isNotEmpty()) { "levels 가 비어 있다" }
        val root = Dims(plantCd = plantCd)
        return build(levels, 0, base, types, root)
    }

    /** 그 단계까지 확정된 차원 값 */
    private data class Dims(
        val plantCd: String,
        val plantNm: String? = null,
        val wcCd: String? = null, val wcNm: String? = null,
        val itemCd: String? = null, val itemNm: String? = null,
        val eqptCd: String? = null, val eqptNm: String? = null,
        val defectCd: String? = null, val defectNm: String? = null
    ) {
        fun toMap(level: DefectTreeLevel): LinkedHashMap<String, Any?> = linkedMapOf(
            "level" to level.key,
            "plantCd" to plantCd, "plantNm" to plantNm,
            "wcCd" to wcCd, "wcNm" to wcNm,
            "itemCd" to itemCd, "itemNm" to itemNm,
            "eqptCd" to eqptCd, "eqptNm" to eqptNm,
            "defectCd" to defectCd, "defectNm" to defectNm
        )
    }

    private fun build(
        levels: List<DefectTreeLevel>,
        depth: Int,
        base: List<DefectTreeBaseRow>,
        types: List<DefectTreeTypeRow>,
        dims: Dims
    ): List<Map<String, Any?>> {
        val level = levels[depth]
        if (level == DefectTreeLevel.DEFECT) return defectRows(types, base.sumOf { it.ngQty }, dims)

        val groups = base.groupBy { keyOf(level, it) }
        return groups.entries
            .map { (key, rows) ->
                val head = rows.first()
                val next = when (level) {
                    DefectTreeLevel.WC -> dims.copy(wcCd = head.wcCd, wcNm = head.wcNm, plantNm = WorkcenterNames.plantOf(head.wcNm))
                    DefectTreeLevel.ITEM -> dims.copy(itemCd = head.itemCd, itemNm = head.itemNm)
                    DefectTreeLevel.EQPT -> dims.copy(eqptCd = head.eqptCd, eqptNm = head.eqptNm)
                    DefectTreeLevel.DEFECT -> dims
                }
                val ok = rows.sumOf { it.okQty }
                val ng = rows.sumOf { it.ngQty }
                val node = next.toMap(level)
                node["okQty"] = ok
                node["ngQty"] = ng
                node["defectRate"] = safeRate(BigDecimal.valueOf(ng), BigDecimal.valueOf(ok + ng))
                node["ratio"] = null
                if (depth + 1 < levels.size) {
                    val childTypes = if (levels.last() == DefectTreeLevel.DEFECT) types.filter { keyOf(level, it) == key } else emptyList()
                    val children = build(levels, depth + 1, rows, childTypes, next)
                    if (children.isNotEmpty()) node["children"] = children
                }
                Triple(ng, key ?: "", node)
            }
            .sortedWith(compareByDescending<Triple<Long, String, Map<String, Any?>>> { it.first }.thenBy { it.second })
            .map { it.third }
    }

    /** 유형 단계 — 안분 수량을 유형별로 더해 반올림하고, 차액을 보정해 합을 상위 ngQty 에 맞춘다. */
    private fun defectRows(types: List<DefectTreeTypeRow>, parentNg: Long, dims: Dims): List<Map<String, Any?>> {
        if (parentNg <= 0L && types.isEmpty()) return emptyList()

        val summed = types.groupBy { it.defectCd }
            .map { (cd, rows) ->
                Typed(cd, rows.firstNotNullOfOrNull { it.defectNm } ?: cd,
                    rows.fold(BigDecimal.ZERO) { acc, r -> acc + r.ngQty }.setScale(0, RoundingMode.HALF_UP).toLong())
            }
            .filter { it.ngQty > 0L }
            .sortedWith(compareByDescending<Typed> { it.ngQty }.thenBy { it.defectCd })
            .toMutableList()

        val diff = parentNg - summed.sumOf { it.ngQty }
        if (diff > 0L) {
            // 유형이 붙지 않은 몫(+ 반올림 잔차). 실제 불량코드가 아니므로 코드는 비운다.
            summed += Typed(null, DefectSql.UNTYPED_LABEL, diff)
        } else if (diff < 0L && summed.isNotEmpty()) {
            // 반올림이 넘친 만큼 가장 큰 유형에서 덜어 합을 맞춘다(수량 원장이 기준이다).
            val top = summed[0]
            summed[0] = top.copy(ngQty = (top.ngQty + diff).coerceAtLeast(0L))
        }

        val parent = BigDecimal.valueOf(parentNg)
        return summed.map { t ->
            dims.copy(defectCd = t.defectCd, defectNm = t.defectNm).toMap(DefectTreeLevel.DEFECT).apply {
                put("okQty", null)
                put("ngQty", t.ngQty)
                put("defectRate", null)
                put("ratio", safeRate(BigDecimal.valueOf(t.ngQty), parent))
            }
        }
    }

    private data class Typed(val defectCd: String?, val defectNm: String?, val ngQty: Long)

    private fun keyOf(level: DefectTreeLevel, r: DefectTreeBaseRow): String? = when (level) {
        DefectTreeLevel.WC -> r.wcCd
        DefectTreeLevel.ITEM -> r.itemCd
        DefectTreeLevel.EQPT -> r.eqptCd
        DefectTreeLevel.DEFECT -> null
    }

    private fun keyOf(level: DefectTreeLevel, r: DefectTreeTypeRow): String? = when (level) {
        DefectTreeLevel.WC -> r.wcCd
        DefectTreeLevel.ITEM -> r.itemCd
        DefectTreeLevel.EQPT -> r.eqptCd
        DefectTreeLevel.DEFECT -> null
    }

    /** 트리를 깊이 우선으로 편다(엑셀 시트용). `depth` 는 1부터. */
    @Suppress("UNCHECKED_CAST")
    fun flatten(items: List<Map<String, Any?>>, depth: Int = 1): List<Pair<Int, Map<String, Any?>>> =
        items.flatMap { node ->
            listOf(depth to node) + flatten((node["children"] as? List<Map<String, Any?>>).orEmpty(), depth + 1)
        }

    /** 수량 권한이 없을 때 — 모든 단계의 okQty·ngQty 를 비운다. 비율(불량률·유형 비중)은 남긴다. */
    @Suppress("UNCHECKED_CAST")
    fun maskQty(items: List<Map<String, Any?>>): List<Map<String, Any?>> = items.map { node ->
        val m = LinkedHashMap(node)
        m["okQty"] = null
        m["ngQty"] = null
        (node["children"] as? List<Map<String, Any?>>)?.let { m["children"] = maskQty(it) }
        m
    }
}
