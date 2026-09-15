package com.dwje.api.service

import com.dwje.api.common.util.DefectSql
import com.dwje.api.common.util.WorkcenterNames
import com.dwje.api.common.util.safeRate
import com.dwje.api.repository.DefectTreeBaseRow
import com.dwje.api.repository.DefectTreeTypeRow
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 제품별 불량 현황 트리 — 제품 > 불량 유형 > 설비(라인) > 공정. (QC-01 제품별 불량 현황 카드)
 *
 * [DefectTreeAssembler] 는 유형을 맨 아래에만 둔다 — 유형 아래로 내려가면 "정상 수량" 이 뜻을 잃기 때문이다.
 * 이 조립기는 그 아래 단계의 뜻을 따로 정해 둔다.
 *
 * ## 수량의 뜻 (단계마다 다르다 — 시트·화면에서 세로 합을 내면 틀리는 열이 있다)
 *
 * | 단계 | totalQty | okQty | ngQty | defectRate | ratio |
 * |---|---|---|---|---|---|
 * | 제품(item) | 그 제품 라벨 원장 총량(정상+불량) | 원장 정상 | 원장 불량 | ngQty ÷ totalQty | **전체 불량** 중 이 제품 비중 |
 * | 유형(defect) | **그 제품**의 원장 총량 — 형제 유형마다 같은 값이 되풀이된다 | null | 이 유형의 안분 불량 | ngQty ÷ 제품 총량 (제품 총량 대비 이 유형 불량률) | 제품 불량 중 이 유형 비중 |
 * | 설비(eqpt) | **(제품, 설비)** 원장 총량 — 같은 설비가 여러 유형 아래 나오면 같은 값 | null | 이 유형이 이 설비에서 난 안분 불량 | ngQty ÷ (제품, 설비) 총량 | 유형 불량 중 이 설비 비중 |
 * | 공정(wc) | **(제품, 설비, 공정)** 원장 총량 | null | 이 유형이 이 설비·공정에서 난 안분 불량 | ngQty ÷ (제품, 설비, 공정) 총량 | 설비 불량 중 이 공정 비중 |
 *
 * - `ngQty` 는 어느 단계에서나 **상위 = 하위 합**이다(유형 미상 행 포함, 반올림 잔차는 가장 큰 자식에서 보정).
 * - `totalQty` 는 유형 단계부터 **분모**다. 형제끼리 더하면 같은 라벨을 여러 번 세므로 더하면 안 된다.
 * - `okQty` 는 유형 단계부터 null — 정상 수량은 유형에 귀속되지 않는다. 필요하면 `totalQty − (제품 ngQty)` 가 아니라
 *   제품 행의 okQty 를 봐야 한다.
 * - 유형이 붙지 않은 불량은 (제품, 설비, 공정) 잎마다 `원장 불량 − 유형 안분 합` 으로 계산해 `defectCd:null` ·
 *   `defectNm:"유형 미상"` 행에 넣는다. 그래서 유형 미상도 설비·공정으로 더 펼 수 있다.
 *
 * ## 정렬
 * 제품·설비·공정은 `ngQty` 내림차순(같으면 코드), 유형은 `ngQty` 내림차순이되 '유형 미상' 은 맨 뒤(by-type 와 같다).
 * 설비가 없는 라벨은 `eqptCd:null` 행으로 맨 뒤에 남긴다.
 */
object ProductDefectTreeAssembler {

    /** (공정, 설비, 제품, 유형) 잎 — 유형 미상은 `defectCd = null` */
    private data class Leaf(
        val wcCd: String, val wcNm: String?,
        val eqptCd: String?, val eqptNm: String?,
        val itemCd: String, val itemNm: String?,
        val defectCd: String?, val defectNm: String?,
        val ng: BigDecimal
    )

    private data class Grain(val wcCd: String, val eqptCd: String?, val itemCd: String)

    fun assemble(base: List<DefectTreeBaseRow>, types: List<DefectTreeTypeRow>, plantCd: String): List<Map<String, Any?>> {
        val leaves = leavesOf(base, types)
        val totalNg = base.sumOf { it.ngQty }

        return base.groupBy { it.itemCd }.entries
            .map { (itemCd, rows) ->
                val head = rows.first()
                val ok = rows.sumOf { it.okQty }
                val ng = rows.sumOf { it.ngQty }
                val total = ok + ng
                val node = node(
                    level = "item", plantCd = plantCd,
                    itemCd = itemCd, itemNm = rows.firstNotNullOfOrNull { it.itemNm } ?: head.itemNm,
                    totalQty = total, okQty = ok, ngQty = ng,
                    defectRate = rate(ng, total), ratio = rate(ng, totalNg)
                )
                val children = defectNodes(leaves.filter { it.itemCd == itemCd }, rows, ng, total, plantCd)
                if (children.isNotEmpty()) node["children"] = children
                Triple(ng, itemCd, node)
            }
            .sortedWith(compareByDescending<Triple<Long, String, Map<String, Any?>>> { it.first }.thenBy { it.second })
            .map { it.third }
    }

    /** 유형 안분행에 잎마다의 '유형 미상'(원장 불량 − 안분 합)을 더해 잎 목록을 만든다. */
    private fun leavesOf(base: List<DefectTreeBaseRow>, types: List<DefectTreeTypeRow>): List<Leaf> {
        val typedByGrain = types.groupBy { Grain(it.wcCd, it.eqptCd, it.itemCd) }
        val leaves = mutableListOf<Leaf>()
        base.forEach { b ->
            val typed = typedByGrain[Grain(b.wcCd, b.eqptCd, b.itemCd)].orEmpty()
            typed.forEach { t ->
                leaves += Leaf(b.wcCd, b.wcNm, b.eqptCd, b.eqptNm, b.itemCd, b.itemNm, t.defectCd, t.defectNm ?: t.defectCd, t.ngQty)
            }
            val untyped = BigDecimal.valueOf(b.ngQty) - typed.fold(BigDecimal.ZERO) { acc, t -> acc + t.ngQty }
            // 안분 합은 원장 불량을 넘지 않는다(각 라벨에서 몫의 합 = 그 라벨 불량). 수치 오차만 걸러 낸다.
            if (untyped.compareTo(BigDecimal("0.0001")) > 0) {
                leaves += Leaf(b.wcCd, b.wcNm, b.eqptCd, b.eqptNm, b.itemCd, b.itemNm, null, DefectSql.UNTYPED_LABEL, untyped)
            }
        }
        return leaves
    }

    /** 제품 아래 유형 단계 — 분모는 제품 총량, ratio 는 제품 불량 대비. */
    private fun defectNodes(
        leaves: List<Leaf>, itemRows: List<DefectTreeBaseRow>, itemNg: Long, itemTotal: Long, plantCd: String
    ): List<Map<String, Any?>> {
        val groups = leaves.groupBy { it.defectCd }
        val built = groups.entries.map { (cd, ls) ->
            Built(key = cd, name = ls.firstNotNullOfOrNull { it.defectNm } ?: cd, ng = round(ls.sumNg()), leaves = ls)
        }.toMutableList()
        fixResidual(built, itemNg)

        return built
            .filter { it.ng > 0L }
            .sortedWith(compareBy<Built> { it.key == null }.thenByDescending { it.ng }.thenBy { it.key ?: "" })
            .map { d ->
                val head = d.leaves.first()
                val node = node(
                    level = "defect", plantCd = plantCd,
                    itemCd = head.itemCd, itemNm = head.itemNm,
                    defectCd = d.key, defectNm = d.name,
                    totalQty = itemTotal, okQty = null, ngQty = d.ng,
                    defectRate = rate(d.ng, itemTotal), ratio = rate(d.ng, itemNg)
                )
                val children = eqptNodes(d.leaves, itemRows, d.ng, plantCd)
                if (children.isNotEmpty()) node["children"] = children
                node
            }
    }

    /** 유형 아래 설비 단계 — 분모는 (제품, 설비) 원장 총량, ratio 는 유형 불량 대비. */
    private fun eqptNodes(
        leaves: List<Leaf>, itemRows: List<DefectTreeBaseRow>, parentNg: Long, plantCd: String
    ): List<Map<String, Any?>> {
        val built = leaves.groupBy { it.eqptCd }.entries.map { (cd, ls) ->
            Built(key = cd, name = ls.firstNotNullOfOrNull { it.eqptNm }, ng = round(ls.sumNg()), leaves = ls)
        }.toMutableList()
        fixResidual(built, parentNg)

        return built
            .filter { it.ng > 0L }
            .sortedWith(compareBy<Built> { it.key == null }.thenByDescending { it.ng }.thenBy { it.key ?: "" })
            .map { e ->
                val head = e.leaves.first()
                val denominatorRows = itemRows.filter { it.eqptCd == e.key }
                val total = denominatorRows.sumOf { it.okQty + it.ngQty }
                val node = node(
                    level = "eqpt", plantCd = plantCd,
                    itemCd = head.itemCd, itemNm = head.itemNm,
                    defectCd = head.defectCd, defectNm = head.defectNm,
                    eqptCd = e.key, eqptNm = e.name,
                    totalQty = total, okQty = null, ngQty = e.ng,
                    defectRate = rate(e.ng, total), ratio = rate(e.ng, parentNg)
                )
                val children = wcNodes(e.leaves, denominatorRows, e.ng, plantCd)
                if (children.isNotEmpty()) node["children"] = children
                node
            }
    }

    /** 설비 아래 공정 단계 — 분모는 (제품, 설비, 공정) 원장 총량, ratio 는 설비 불량 대비. 공장은 작업장 이름에서 읽는다. */
    private fun wcNodes(
        leaves: List<Leaf>, eqptRows: List<DefectTreeBaseRow>, parentNg: Long, plantCd: String
    ): List<Map<String, Any?>> {
        val built = leaves.groupBy { it.wcCd }.entries.map { (cd, ls) ->
            Built(key = cd, name = ls.firstNotNullOfOrNull { it.wcNm }, ng = round(ls.sumNg()), leaves = ls)
        }.toMutableList()
        fixResidual(built, parentNg)

        return built
            .filter { it.ng > 0L }
            .sortedWith(compareByDescending<Built> { it.ng }.thenBy { it.key ?: "" })
            .map { w ->
                val head = w.leaves.first()
                val total = eqptRows.filter { it.wcCd == w.key }.sumOf { it.okQty + it.ngQty }
                node(
                    level = "wc", plantCd = plantCd, plantNm = WorkcenterNames.plantOf(w.name),
                    itemCd = head.itemCd, itemNm = head.itemNm,
                    defectCd = head.defectCd, defectNm = head.defectNm,
                    eqptCd = head.eqptCd, eqptNm = head.eqptNm,
                    wcCd = w.key, wcNm = w.name,
                    totalQty = total, okQty = null, ngQty = w.ng,
                    defectRate = rate(w.ng, total), ratio = rate(w.ng, parentNg)
                )
            }
    }

    // ── 도우미 ─────────────────────────────────────────────────────────────────

    private class Built(val key: String?, val name: String?, var ng: Long, val leaves: List<Leaf>)

    /** 반올림 뒤 자식 합이 상위와 어긋나면 가장 큰 자식에서 맞춘다. 상위가 원장 기준이다. */
    private fun fixResidual(children: MutableList<Built>, parentNg: Long) {
        if (children.isEmpty()) return
        val diff = parentNg - children.sumOf { it.ng }
        if (diff == 0L) return
        val top = children.maxByOrNull { it.ng } ?: return
        top.ng = (top.ng + diff).coerceAtLeast(0L)
    }

    private fun List<Leaf>.sumNg(): BigDecimal = fold(BigDecimal.ZERO) { acc, l -> acc + l.ng }
    private fun round(v: BigDecimal): Long = v.setScale(0, RoundingMode.HALF_UP).toLong()
    private fun rate(n: Long, d: Long): Double = safeRate(BigDecimal.valueOf(n), BigDecimal.valueOf(d))

    private fun node(
        level: String, plantCd: String, plantNm: String? = null,
        itemCd: String? = null, itemNm: String? = null,
        defectCd: String? = null, defectNm: String? = null,
        eqptCd: String? = null, eqptNm: String? = null,
        wcCd: String? = null, wcNm: String? = null,
        totalQty: Long?, okQty: Long?, ngQty: Long?, defectRate: Double?, ratio: Double?
    ): LinkedHashMap<String, Any?> = linkedMapOf(
        "level" to level,
        "itemCd" to itemCd, "itemNm" to itemNm,
        "defectCd" to defectCd, "defectNm" to defectNm,
        "eqptCd" to eqptCd, "eqptNm" to eqptNm,
        "wcCd" to wcCd, "wcNm" to wcNm,
        "plantCd" to plantCd, "plantNm" to plantNm,
        "totalQty" to totalQty, "okQty" to okQty, "ngQty" to ngQty,
        "defectRate" to defectRate, "ratio" to ratio
    )

    /** 수량 권한이 없을 때 — 모든 단계의 totalQty·okQty·ngQty 를 비운다. 비율(불량률·비중)은 남긴다. */
    @Suppress("UNCHECKED_CAST")
    fun maskQty(items: List<Map<String, Any?>>): List<Map<String, Any?>> = items.map { n ->
        val m = LinkedHashMap(n)
        m["totalQty"] = null; m["okQty"] = null; m["ngQty"] = null
        (n["children"] as? List<Map<String, Any?>>)?.let { m["children"] = maskQty(it) }
        m
    }
}
