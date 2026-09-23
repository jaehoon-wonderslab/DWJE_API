package com.dwje.api.service

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.exception.ResourceNotFoundException
import com.dwje.api.common.response.PageMeta
import com.dwje.api.common.util.BusinessDay
import com.dwje.api.common.util.DataField
import com.dwje.api.common.util.DateUtils
import com.dwje.api.common.util.MaskingSupport
import com.dwje.api.common.util.MenuId
import com.dwje.api.common.util.PageRequestParam
import com.dwje.api.config.AoiProperties
import com.dwje.api.config.AppProperties
import com.dwje.api.repository.AoiCosmeticRepository
import com.dwje.api.repository.AoiCosmeticRepository.SerialKey
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * AOI 외관 판정 집계 — `TB_SAMSUN_COSMETIC` (2026-09-14 발주자 지시: AOI 는 이 표만 쓴다).
 *
 * ## 치수와 근본적으로 다른 점 — 규격을 역산하지 않는다
 * 치수([AoiDimensionService])는 규격이 원천에 없어 합격 데이터의 포화로 상·하한을 되찾아야 했다.
 * 외관은 원천이 판정을 직접 말해 준다 — `PASSED`(항목) 와 `FINAL_PASSED`(제품). 그래서 한계 세트·설명률 개념이 필요 없고,
 * 대신 **어느 항목 때문에 불량이 되었는지**를 제품 단위로 귀속한다(단일 · 복합 · 설명 없음).
 *
 * ## 불량률은 제품 기준이다
 * 한 행이 (제품 × 검사 항목)이라 행을 세면 항목 수만큼 부풀려진다(09-12 하루 행 314만 vs 제품 45.8만).
 * 화면에 나가는 모든 비율의 분모는 **제품 수**이고, 불량 판정은 `FINAL_PASSED` 다.
 * 근거는 `docs/AOI_COSMETIC_SOURCE_SURVEY_20260914.md` 3절(한 `SEQ` 안에서 `FINAL_PASSED` 가 갈린 경우 0건, 전수 확인).
 *
 * ## 원천 부담을 견디는 방법은 치수와 같다
 * `WITH (NOLOCK)` · 조회 조건 단위 결과 보관(single-flight) · 기간 상한 · 설비별 쿼리 동시 실행 수 제한.
 * 다만 행수가 치수의 4.3배라 기간 상한이 더 짧다(`app.aoi.cosmetic.max-days`, 기본 3일).
 */
@Service
class AoiCosmeticService(
    private val repository: AoiCosmeticRepository,
    private val authorizationService: AuthorizationService,
    private val agentRunRecorder: AgentRunRecorder,
    private val appProperties: AppProperties
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val cfg: AoiProperties get() = appProperties.aoi
    private val cos: AoiProperties.Cosmetic get() = appProperties.aoi.cosmetic

    private val executor: ExecutorService by lazy {
        Executors.newFixedThreadPool(cfg.mssql.parallelism.coerceAtMost(cfg.mssql.poolSize).coerceAtLeast(1))
    }

    companion object {
        private val SORTS = setOf("prodNgCnt", "ngRate", "lastAt", "firstAt", "serialNo", "prodCnt")
    }

    // ── 결과 모형 ─────────────────────────────────────────────────────────────

    /**
     * 기간 한 번 읽기의 결과 — 집계와 시리얼 목록을 **함께** 담는다.
     *
     * 화면은 집계 카드와 목록을 나란히 그리는데, 둘을 따로 읽으면 같은 314만 행을 두 번 훑는다.
     * 원천을 한 번만 읽고 이 한 덩어리를 보관해 `/summary`·`/items`·`/serials` 가 모두 여기서 답한다.
     */
    data class Snapshot(
        val from: LocalDate,
        val to: LocalDate,
        val wcCd: String?,
        val eqptCd: String?,
        val total: Block,
        val lines: List<Block>,
        val serials: List<AoiCosmeticRepository.SerialRow>,
        val elapsedMs: Long,
        val queryCnt: Int
    ) {
        val days: Long get() = ChronoUnit.DAYS.between(from, to) + 1
    }

    /** 집계 한 덩어리 — 전체 또는 (공정, 설비) 하나 */
    data class Block(
        val wcCd: String?,
        val eqptCd: String?,
        val prodCnt: Long,
        val prodNgCnt: Long,
        val rowCnt: Long,
        val serialCnt: Long,
        val singleCnt: Long,
        val multiCnt: Long,
        val unexplainedCnt: Long,
        val overriddenCnt: Long,
        /** 불량이 있는 항목만, 합부 항목 우선 정렬 — 집계 카드가 쓴다 */
        val items: List<Item>,
        /** 검사된 항목 **전부**(불량 0건 포함) — 필터 목록이 쓴다 */
        val allItems: List<Item>,
        val firstAt: LocalDateTime?,
        val lastAt: LocalDateTime?
    ) {
        /** 설비 경계를 넘어 고유하게 센 제품 수. 합친 블록에서만 채워진다 — 설비별 단순 합과 미세하게 다르다 */
        var distinctProdCnt: Long? = null
        var distinctSerialCnt: Long? = null
        val prodOkCnt: Long get() = prodCnt - prodNgCnt
        /** 제품 기준 불량률 — 분모는 제품 수다 */
        val ngRate: Double get() = pct(prodNgCnt, prodCnt)
        /** 불량 원인이 항목으로 설명된 제품 비율 */
        val explainedRate: Double get() = pct(singleCnt + multiCnt, prodNgCnt)
        /** 화면 문장이 지목하는 항목 — 합부를 정하는 항목 중에서만 고른다 */
        val topItem: Item? get() = items.filter { it.verdict }.maxByOrNull { it.ngSingleCnt }
    }

    /** 검사 항목 하나 */
    data class Item(
        val itemCd: String,
        /** 설정에 이름이 있으면 그 이름, 없으면 null — 화면은 null 이면 코드를 그대로 쓴다 */
        val itemNm: String?,
        val inspCnt: Long,
        val ngCnt: Long,
        val ngFinalCnt: Long,
        val ngSingleCnt: Long,
        /** 합부를 정하는 항목인가 — `false`(DF009)면 `ngCnt` 는 「값 없음」의 수라 불량이 아니다 */
        val verdict: Boolean = true
    ) {
        /** 이 항목이 검사된 제품 중 불량 비율 */
        val ngRate: Double get() = pct(ngCnt, inspCnt)
        /** 단일 귀속 제품 중 이 항목의 비중 — "불량의 88% 가 DF009" 의 값 */
        var sharePct: Double = 0.0
        /** 최종 불량 제품 중 이 항목이 불량인 비율 */
        var ngPct: Double = 0.0
    }

    // ── 캐시 ─────────────────────────────────────────────────────────────────

    data class CacheKey(val from: LocalDate, val to: LocalDate, val wcCd: String?, val eqptCd: String?)
    private class Entry(val value: Snapshot, val cachedAt: LocalDateTime, val expiresAt: LocalDateTime)
    private val cache = ConcurrentHashMap<CacheKey, Entry>()
    private val inFlight = ConcurrentHashMap<CacheKey, CompletableFuture<Snapshot>>()

    // ── 집계 조회 ─────────────────────────────────────────────────────────────

    /**
     * 집계 조회 — 현재 기간과 직전 동일 기간을 함께 낸다.
     *
     * 치수와 달리 `wcCd` 가 **필수가 아니다**. 한계 세트가 없어 공정을 나눠 읽을 이유가 없고,
     * 원천 부담은 설비 수가 아니라 기간이 정한다(설비마다 한 번씩 읽는 것은 같다).
     */
    fun getSummary(from: String?, to: String?, wcCd: String?, eqptCd: String?, compare: Boolean?): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.QC_AOI)
        val (fromDate, toDate) = periodOf(from, to)
        val wc = wcCd?.trim()?.takeIf { it.isNotEmpty() && it != "전체" }
        val eqpt = eqptCd?.trim()?.takeIf { it.isNotEmpty() && it != "전체" }

        if (!repository.available) {
            return mapOf(
                "reason" to AoiDimensionService.SOURCE_NOT_CONFIGURED,
                "period" to periodMap(fromDate, toDate), "wcCd" to wc, "eqptCd" to eqpt,
                "current" to null, "previous" to null
            ) to mask
        }

        val started = System.currentTimeMillis()
        val key = CacheKey(fromDate, toDate, wc, eqpt)
        val hit = cache[key]?.takeIf { it.expiresAt.isAfter(LocalDateTime.now()) } != null
        // ① 비전 수집 Agent — **원천을 실제로 읽을 때만** 감싼다.
        // 캐시로 끝난 호출까지 기록하면 화면을 열 때마다 이력이 쌓여, 정작 언제 수집했는지가 묻힌다.
        // 원천이 응답하지 않으면 measure 가 ERROR 로 남기고 예외를 그대로 올려보낸다 —
        // 그래야 Agent 화면·Master 상태가 장애를 장애로 보여 준다.
        val current = if (hit) cached(key) else agentRunRecorder.measure(
            agentNo = AgentRunRecorder.VISION,
            message = "AOI 외관 원천(COSMETIC) 수집 $fromDate~$toDate" +
                (wc?.let { " · 공정 $it" } ?: "") + (eqpt?.let { " · 설비 $it" } ?: ""),
            throughput = { snap -> "제품 %,d건 · 질의 %d회".format(snap.total.prodCnt, snap.queryCnt) }
        ) { cached(key) }

        val days = ChronoUnit.DAYS.between(fromDate, toDate) + 1
        val prevTo = fromDate.minusDays(1)
        val prevKey = CacheKey(prevTo.minusDays(days - 1), prevTo, wc, eqpt)
        // 기간마다 따로 본다 — 현재가 캐시에 있다고 직전도 있는 것은 아니다
        val prevHit = cache[prevKey]?.takeIf { it.expiresAt.isAfter(LocalDateTime.now()) } != null
        // 직전 기간은 원천을 한 번 더 읽는다 = 시간이 두 배다. 이미 보관돼 있으면 공짜이므로 그때는 그냥 낸다.
        val wantCompare = compare ?: prevHit
        val previous = if (wantCompare) runCatching { cached(prevKey) }.getOrNull() else null

        val qty = mask.check(DataField.QTY)
        val yield = mask.check(DataField.YIELD)

        val data = mapOf(
            "period" to periodMap(fromDate, toDate),
            "previousPeriod" to previous?.let { periodMap(it.from, it.to) },
            "wcCd" to wc, "eqptCd" to eqpt, "maxDays" to cos.maxDays, "compare" to wantCompare,
            "current" to summaryMap(current, qty, yield),
            "previous" to previous?.let { summaryMap(it, qty, yield) },
            "delta" to previous?.let { deltaMap(current, it, qty, yield) },
            "fromCache" to hit,
            "cachedAt" to cache[key]?.cachedAt?.format(DateUtils.DATETIME),
            "elapsedMs" to (System.currentTimeMillis() - started),
            "sourceQueryCnt" to (if (hit) 0 else current.queryCnt) + (if (prevHit || previous == null) 0 else previous.queryCnt)
        )

        return data to mask
    }

    /**
     * 검사 항목 목록 — 화면의 유형 필터용. 설비마다 구성이 달라 (공정, 설비)별로도 낸다.
     *
     * **불량 0건인 항목도 남긴다.** 집계(`/summary`)의 `items` 는 불량이 있는 항목만 추려 보여 주지만,
     * 필터 목록에서 「그 기간 한 번도 안 걸린 항목」이 사라지면 화면이 고를 수가 없다.
     *
     * 같은 조건의 스냅샷이 있으면 그것을 쓰고, 없으면 이 호출이 원천을 읽는다 —
     * `/summary` 를 먼저 부른 경우에만 공짜다(화면이 `/items` 를 먼저 부르면 여기서 기다린다).
     */
    fun getItems(from: String?, to: String?, wcCd: String?, eqptCd: String?): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.QC_AOI)
        val (fromDate, toDate) = periodOf(from, to)
        val wc = wcCd?.trim()?.takeIf { it.isNotEmpty() && it != "전체" }
        val eqpt = eqptCd?.trim()?.takeIf { it.isNotEmpty() && it != "전체" }

        if (!repository.available) {
            return mapOf("reason" to AoiDimensionService.SOURCE_NOT_CONFIGURED, "items" to emptyList<Any>()) to mask
        }
        val key = CacheKey(fromDate, toDate, wc, eqpt)
        val hit = cache[key]?.takeIf { it.expiresAt.isAfter(LocalDateTime.now()) } != null
        val snapshot = cached(key)
        val qty = mask.check(DataField.QTY)

        return mapOf(
            "period" to periodMap(fromDate, toDate), "wcCd" to wc, "eqptCd" to eqpt,
            "items" to snapshot.total.allItems.map { itemFilterMap(it, qty) },
            "byLine" to snapshot.lines.map {
                mapOf("wcCd" to it.wcCd, "eqptCd" to it.eqptCd, "items" to it.allItems.map { i -> itemFilterMap(i, qty) })
            },
            "fromCache" to hit, "cachedAt" to cache[key]?.cachedAt?.format(DateUtils.DATETIME)
        ) to mask
    }

    /** 필터 목록 한 항목 — `verdict` 를 반드시 싣는다. 없으면 화면이 DF009 를 불량으로 오해한다. */
    private fun itemFilterMap(i: Item, qty: Boolean): Map<String, Any?> = mapOf(
        "itemCd" to i.itemCd, "itemNm" to i.itemNm, "verdict" to i.verdict,
        "inspCnt" to i.inspCnt.q(qty), "ngCnt" to i.ngCnt.q(qty)
    )

    // ── 시리얼 목록·상세 ──────────────────────────────────────────────────────

    /**
     * 시리얼 목록. `date` 하루가 기본이고 `from`/`to` 도 받는다.
     *
     * 집계와 **같은 스냅샷**에서 답한다 — 두 카드의 합이 어긋나지 않고, 원천을 다시 읽지도 않는다.
     */
    fun getSerials(
        date: String?, from: String?, to: String?, wcCd: String?, eqptCd: String?,
        sort: String?, desc: Boolean?, page: Int?, size: Int?
    ): Triple<Map<String, Any?>, PageMeta, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.QC_AOI)
        val (fromDate, toDate) = if (!date.isNullOrBlank()) {
            val d = DateUtils.parseDate(date, "date", LocalDate.now()); d to d
        } else periodOf(from, to)
        val wc = wcCd?.trim()?.takeIf { it.isNotEmpty() && it != "전체" }
        val eqpt = eqptCd?.trim()?.takeIf { it.isNotEmpty() && it != "전체" }
        val sortKey = (sort?.trim()?.takeIf { it.isNotEmpty() } ?: "prodNgCnt").also {
            if (it !in SORTS) throw InvalidParameterException("sort 는 ${SORTS.joinToString("·")} 중 하나입니다. [sort=$sort]", "sort")
        }
        val paging = PageRequestParam.ofAllowAll(page, size)

        if (!repository.available) {
            return Triple(
                mapOf("reason" to AoiDimensionService.SOURCE_NOT_CONFIGURED, "items" to emptyList<Any>()),
                PageMeta.of(1, paging.size.coerceAtLeast(1), 0), mask
            )
        }

        val key = CacheKey(fromDate, toDate, wc, eqpt)
        val hit = cache[key]?.takeIf { it.expiresAt.isAfter(LocalDateTime.now()) } != null
        val started = System.currentTimeMillis()
        val rows = cached(key).serials

        val d = desc ?: true
        val base: Comparator<AoiCosmeticRepository.SerialRow> = when (sortKey) {
            "ngRate" -> compareBy { if (it.prodCnt == 0L) 0.0 else it.prodNgCnt * 1.0 / it.prodCnt }
            "lastAt" -> compareBy(nullsFirst()) { it.lastAt }
            "firstAt" -> compareBy(nullsFirst()) { it.firstAt }
            "serialNo" -> compareBy { it.key.serialNo }
            "prodCnt" -> compareBy { it.prodCnt }
            else -> compareBy { it.prodNgCnt }
        }
        val sorted = rows.sortedWith(if (d) base.reversed() else base)
        val pageRows = if (paging.isAll) sorted else sorted.drop(paging.offset).take(paging.limit)
        val qty = mask.check(DataField.QTY)
        val yield = mask.check(DataField.YIELD)

        val data = mapOf(
            "date" to fromDate.format(DateUtils.DATE),
            "from" to fromDate.format(DateUtils.DATE), "to" to toDate.format(DateUtils.DATE),
            "wcCd" to wc, "eqptCd" to eqpt, "sort" to sortKey, "desc" to d,
            "items" to pageRows.map { serialRowMap(it, qty, yield) },
            "fromCache" to hit, "cachedAt" to cache[key]?.cachedAt?.format(DateUtils.DATETIME),
            "elapsedMs" to (System.currentTimeMillis() - started)
        )
        return Triple(data, PageMeta.of(paging.page, paging.size, sorted.size.toLong()), mask)
    }

    /**
     * 목록 한 행.
     *
     * `prodCnt`/`prodNgCnt` 는 조회 기간 안, `prodCntAll`/`prodNgCntAll` 은 시리얼 전체(날짜 무관)다.
     * 자정을 넘긴 시리얼은 둘이 다르고, 화면은 「그날 1,946 / 전체 3,480」처럼 나란히 보여줄 수 있다.
     */
    internal fun serialRowMap(r: AoiCosmeticRepository.SerialRow, qty: Boolean, yield: Boolean): Map<String, Any?> = mapOf(
        // 키가 없는 행은 상세를 열 수 없다 — serialKey 를 주지 않아 화면이 링크를 걸지 않게 한다
        "serialKey" to r.key.encode().takeIf { r.key.keyed },
        "keyed" to r.key.keyed,
        "wcCd" to r.key.wcCd, "eqptCd" to r.key.eqptCd, "lotNo" to r.key.lotNo, "serialNo" to r.key.serialNo,
        "prodCnt" to r.prodCnt.q(qty), "prodNgCnt" to r.prodNgCnt.q(qty),
        "ngRate" to pct(r.prodNgCnt, r.prodCnt).r(yield),
        "prodCntAll" to r.prodCntAll.q(qty), "prodNgCntAll" to r.prodNgCntAll.q(qty),
        "ngRateAll" to pct(r.prodNgCntAll, r.prodCntAll).r(yield),
        "seqMin" to r.seqMin, "seqMax" to r.seqMax,
        // 앞 구간이 조회 기간 밖에 있다는 뜻. 키가 없는 행에는 뜻이 없으므로 false 로 둔다
        "partial" to (r.key.keyed && r.prodCntAll > r.prodCnt),
        "cavity" to r.cavity,
        "firstAt" to r.firstAt?.format(DateUtils.DATETIME), "lastAt" to r.lastAt?.format(DateUtils.DATETIME)
    )

    /** 시리얼 상세 — 제품(회차) 목록 한 쪽. `only=ng`(기본) 최종 불량만 · `all` 전 제품. */
    fun getSerial(serialKey: String, only: String?, page: Int?, size: Int?): Triple<Map<String, Any?>, PageMeta, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.QC_AOI)
        val key = SerialKey.decode(serialKey)
            ?: throw InvalidParameterException("serialKey 는 wc~eqpt~lot~serial 형식입니다. [serialKey=$serialKey]", "serialKey")
        requireKeyed(key, serialKey)
        val onlyNg = parseOnly(only)
        val paging = PageRequestParam.ofAllowAll(page, size)

        if (!repository.available) {
            return Triple(
                mapOf("reason" to AoiDimensionService.SOURCE_NOT_CONFIGURED, "items" to emptyList<Any>()),
                PageMeta.of(1, paging.size.coerceAtLeast(1), 0), mask
            )
        }

        val stat = runCatching { repository.serialStat(key, cos.nonVerdictItems) }.getOrElse { throw AoiSourceException(it) }
            ?: throw ResourceNotFoundException("해당 시리얼을 찾을 수 없습니다. [serialKey=$serialKey]")
        val total = if (onlyNg) stat.prodNgCnt else stat.prodCnt
        val limit = if (paging.isAll) total.toInt().coerceAtLeast(1) else paging.limit
        val products = runCatching { repository.serialProducts(key, onlyNg, if (paging.isAll) 0 else paging.offset, limit) }
            .getOrElse { throw AoiSourceException(it) }

        val qty = mask.check(DataField.QTY)
        val yield = mask.check(DataField.YIELD)
        val names = cos.itemNames

        val data = mapOf(
            "serialKey" to key.encode(),
            "wcCd" to key.wcCd, "eqptCd" to key.eqptCd, "lotNo" to key.lotNo, "serialNo" to key.serialNo,
            "prodCnt" to stat.prodCnt.q(qty), "prodNgCnt" to stat.prodNgCnt.q(qty),
            "ngRate" to pct(stat.prodNgCnt, stat.prodCnt).r(yield),
            "seqMin" to stat.seqMin, "seqMax" to stat.seqMax, "cavity" to stat.cavity,
            "only" to (if (onlyNg) "ng" else "all"),
            "items" to products.map { p ->
                mapOf(
                    "seq" to p.seq, "finalPassed" to p.finalPassed,
                    "measuredAt" to p.measuredAt?.format(DateUtils.DATETIME), "cavity" to p.cavity,
                    "checks" to p.checks.map { c ->
                        mapOf(
                            "itemCd" to c.itemCd, "itemNm" to names[c.itemCd],
                            "verdict" to (c.itemCd !in cos.nonVerdictItems),
                            "value" to c.value, "passed" to c.passed
                        )
                    },
                    // 이 제품을 불량으로 만든 항목 — 화면이 붉게 칠하는 자리.
                    // 합부를 정하지 않는 항목(DF009)은 빼야 한다. 넣으면 모든 제품이 불량으로 보인다.
                    "ngItems" to p.checks.filter { !it.passed && it.itemCd !in cos.nonVerdictItems }.map { it.itemCd }
                )
            }
        )
        return Triple(data, PageMeta.of(paging.page, paging.size, total), mask)
    }

    /**
     * `only` 해석 — `ng`(기본) 불량만 · `all` 전부.
     *
     * **모르는 값을 조용히 기본값으로 되돌리지 않는다.** 웹의 `EMPTY_FILTERS` 가 `all` 을 쿼리에서 지워 버려
     * 「전체 회차」를 눌러도 불량 회차만 보이던 일이 있었다(2026-09-14). 화면은 그 사실을 말하지 않았고,
     * 표의 판정이 전부 「불량」이라 원천의 극성이 뒤집힌 것처럼 보였다. 서버가 400 을 냈다면 그날 바로 드러났을 일이다.
     */
    internal fun parseOnly(only: String?): Boolean = when ((only ?: "ng").trim().lowercase()) {
        "ng" -> true
        "all" -> false
        else -> throw InvalidParameterException("only 는 ng 또는 all 입니다. [only=$only]", "only")
    }

    /**
     * 로트·시리얼이 빈 행은 시리얼을 가리키지 못한다.
     *
     * 열면 그 설비의 전 기간 누적이 나와 시리얼처럼 보이지만 실제로는 키가 없는 행을 모아 놓은 것이다
     * (실측 09-12: `MN-069` 488제품 · `MN-065` 13 · `MN-066` 9 · `MN-067` 2 · `MN-071` 1).
     */
    internal fun requireKeyed(key: SerialKey, raw: String) {
        if (!key.keyed) {
            throw InvalidParameterException(
                "로트·시리얼 번호가 없는 행은 상세를 열 수 없습니다. 원천에 키가 비어 있습니다. [serialKey=$raw]",
                "serialKey"
            )
        }
    }

    // ── 내부 ─────────────────────────────────────────────────────────────────

    /** 기간 해석 — 상한은 치수와 따로다(`app.aoi.cosmetic.max-days`). */
    fun periodOf(from: String?, to: String?): Pair<LocalDate, LocalDate> {
        val toDate = DateUtils.parseDate(to, "to", LocalDate.now())
        val fromDate = DateUtils.parseDate(from, "from", toDate)
        if (fromDate.isAfter(toDate)) {
            throw InvalidParameterException("조회 시작일이 종료일보다 늦습니다. [from=$fromDate, to=$toDate]", "from")
        }
        val days = ChronoUnit.DAYS.between(fromDate, toDate) + 1
        if (days > cos.maxDays) {
            throw InvalidParameterException(
                "AOI 외관 조회 기간은 최대 ${cos.maxDays}일입니다. [from=$fromDate, to=$toDate, ${days}일] — " +
                    "원천을 직접 읽고 행수가 치수의 4.3배라 기간에 비례해 느려집니다.",
                "to"
            )
        }
        return fromDate to toDate
    }

    private fun cached(key: CacheKey): Snapshot {
        cache[key]?.let { if (it.expiresAt.isAfter(LocalDateTime.now())) return it.value else cache.remove(key) }

        var owner = false
        val future = inFlight.computeIfAbsent(key) { owner = true; CompletableFuture<Snapshot>() }
        if (owner) {
            try {
                val value = compute(key)
                val ttl = if (!key.to.isBefore(LocalDate.now())) cfg.todayCacheTtlSec else cfg.cacheTtlSec
                if (ttl > 0) {
                    evictIfFull()
                    cache[key] = Entry(value, LocalDateTime.now(), LocalDateTime.now().plusSeconds(ttl))
                }
                future.complete(value)
            } catch (t: Throwable) {
                log.error("AOI 외관 집계 실패 : {} — {}", key, t.toString())
                future.completeExceptionally(t)
            } finally {
                inFlight.remove(key, future)
            }
        }
        try {
            return future.get()
        } catch (e: java.util.concurrent.ExecutionException) {
            val cause = e.cause ?: e
            throw if (cause is InvalidParameterException) cause else AoiSourceException(cause)
        }
    }

    private fun evictIfFull() {
        if (cache.size < cfg.cacheMaxEntries) return
        cache.entries.sortedBy { it.value.cachedAt }.take(cache.size - cfg.cacheMaxEntries + 1).forEach { cache.remove(it.key) }
    }

    /**
     * 원천을 실제로 읽는다 — **기간 전체를 한 번**.
     *
     * 예전에는 설비 목록을 뽑고 설비마다 한 번씩 읽었다(치수를 그대로 따른 것). 치수는 설비마다 한계 세트가 달라
     * 그래야 했지만 외관은 그렇지 않다. 설비별로 나누면 같은 기간 인덱스 범위를 설비 수만큼 다시 훑어
     * 09-12 하루가 집계 90초·시리얼 목록 184초(제한 시간 초과)였다. 한 번 읽기는 40초다.
     */
    private fun compute(key: CacheKey): Snapshot {
        val started = System.currentTimeMillis()
        val window = BusinessDay.ofRange(key.from, key.to)
        val data = repository.periodData(
            key.wcCd, key.eqptCd, window.from, window.toExclusive,
            cos.nonVerdictItems
        )

        val blocks = data.lines
            .map { block(it.count.wcCd, it.count.eqptCd, it.count, it.items) }
            .sortedByDescending { it.prodNgCnt }

        val total = merge(blocks).also {
            it.distinctProdCnt = data.distinctProdCnt
            it.distinctSerialCnt = data.distinctSerialCnt
        }

        return Snapshot(
            from = key.from, to = key.to, wcCd = key.wcCd, eqptCd = key.eqptCd,
            total = total, lines = blocks, serials = data.serials,
            elapsedMs = System.currentTimeMillis() - started, queryCnt = 1
        )
    }

    private fun block(
        wcCd: String?, eqptCd: String?,
        c: AoiCosmeticRepository.LineCount,
        items: List<AoiCosmeticRepository.ItemStat>
    ): Block {
        val names = cos.itemNames
        val mapped = items.map { Item(it.itemCd, names[it.itemCd], it.inspCnt, it.ngCnt, it.ngFinalCnt, it.ngSingleCnt, it.verdict) }
        val ranked = share(mapped, c.singleCnt, c.prodNgCnt)
        return Block(
            wcCd = wcCd, eqptCd = eqptCd,
            prodCnt = c.prodCnt, prodNgCnt = c.prodNgCnt, rowCnt = c.rowCnt, serialCnt = c.serialCnt,
            singleCnt = c.singleCnt, multiCnt = c.multiCnt, unexplainedCnt = c.unexplainedCnt, overriddenCnt = c.overriddenCnt,
            items = ranked.filter { it.ngCnt > 0 },
            allItems = ranked,
            firstAt = c.firstAt, lastAt = c.lastAt
        )
    }

    /** 비중을 채우고 단일 귀속 많은 순으로 정렬한다. */
    /**
     * 비중을 채우고 정렬한다. **거르지는 않는다** — 불량 0건 항목을 여기서 버리면 필터 목록에서 사라진다.
     * 집계 카드용으로 거르는 일은 [block] 이 `items` 에서 한다.
     */
    private fun share(items: List<Item>, singleCnt: Long, prodNgCnt: Long): List<Item> =
        items.onEach { it.sharePct = pct(it.ngSingleCnt, singleCnt); it.ngPct = pct(it.ngFinalCnt, prodNgCnt) }
            // 합부 항목을 앞에, 그 안에서 단일 귀속 많은 순. DF009 류는 섞이지 않게 뒤로 민다
            .sortedWith(compareByDescending<Item> { it.verdict }.thenByDescending { it.ngSingleCnt })

    /** 설비 블록을 전체로 합친다. 항목은 코드로 합친다 — 설비마다 구성이 달라 없는 설비는 그냥 빠진다. */
    internal fun merge(blocks: List<Block>): Block {
        val singleCnt = blocks.sumOf { it.singleCnt }
        val prodNgCnt = blocks.sumOf { it.prodNgCnt }
        val items = blocks.flatMap { it.allItems }.groupBy { it.itemCd }.map { (cd, list) ->
            Item(
                cd, list.first().itemNm, list.sumOf { it.inspCnt }, list.sumOf { it.ngCnt },
                list.sumOf { it.ngFinalCnt }, list.sumOf { it.ngSingleCnt }, list.first().verdict
            )
        }
        val ranked = share(items, singleCnt, prodNgCnt)
        return Block(
            wcCd = null, eqptCd = null,
            prodCnt = blocks.sumOf { it.prodCnt }, prodNgCnt = prodNgCnt,
            rowCnt = blocks.sumOf { it.rowCnt }, serialCnt = blocks.sumOf { it.serialCnt },
            singleCnt = singleCnt, multiCnt = blocks.sumOf { it.multiCnt },
            unexplainedCnt = blocks.sumOf { it.unexplainedCnt }, overriddenCnt = blocks.sumOf { it.overriddenCnt },
            items = ranked.filter { it.ngCnt > 0 },
            allItems = ranked,
            firstAt = blocks.mapNotNull { it.firstAt }.minOrNull(), lastAt = blocks.mapNotNull { it.lastAt }.maxOrNull()
        )
    }

    // ── 응답 조립 ─────────────────────────────────────────────────────────────

    private fun periodMap(from: LocalDate, to: LocalDate): Map<String, Any?> = mapOf(
        "from" to from.format(DateUtils.DATE), "to" to to.format(DateUtils.DATE),
        "days" to (ChronoUnit.DAYS.between(from, to) + 1)
    )

    private fun summaryMap(s: Snapshot, qty: Boolean, yield: Boolean): Map<String, Any?> = mapOf(
        "period" to periodMap(s.from, s.to),
        "total" to blockMap(s.total, qty, yield),
        "lines" to s.lines.map { blockMap(it, qty, yield) },
        "elapsedMs" to s.elapsedMs, "queryCnt" to s.queryCnt
    )

    private fun blockMap(b: Block, qty: Boolean, yield: Boolean): Map<String, Any?> = mapOf(
        "wcCd" to b.wcCd, "eqptCd" to b.eqptCd,
        "prodCnt" to b.prodCnt.q(qty), "prodOkCnt" to b.prodOkCnt.q(qty), "prodNgCnt" to b.prodNgCnt.q(qty),
        "rowCnt" to b.rowCnt.q(qty), "serialCnt" to b.serialCnt.q(qty),
        // 설비별 단순 합은 설비를 두 대 거친 제품·시리얼을 두 번 센다(실측 09-12: 제품 3건·시리얼 4건).
        // 합친 블록에서만 고유 수를 함께 낸다 — 화면이 어느 쪽을 쓸지 고를 수 있게.
        "distinctProdCnt" to b.distinctProdCnt?.q(qty), "distinctSerialCnt" to b.distinctSerialCnt?.q(qty),
        "ngRate" to b.ngRate.r(yield),
        "attribution" to mapOf(
            "single" to b.singleCnt.q(qty), "multi" to b.multiCnt.q(qty),
            "unexplained" to b.unexplainedCnt.q(qty), "overridden" to b.overriddenCnt.q(qty)
        ),
        "explainedRate" to b.explainedRate.r(yield),
        "topItem" to b.topItem?.itemCd,
        "items" to b.items.map { i ->
            mapOf(
                "itemCd" to i.itemCd, "itemNm" to i.itemNm, "verdict" to i.verdict,
                "inspCnt" to i.inspCnt.q(qty), "ngCnt" to i.ngCnt.q(qty),
                "ngFinalCnt" to i.ngFinalCnt.q(qty), "ngSingleCnt" to i.ngSingleCnt.q(qty),
                "ngRate" to i.ngRate.r(yield), "sharePct" to i.sharePct.r(yield), "ngPct" to i.ngPct.r(yield)
            )
        },
        "firstAt" to b.firstAt?.format(DateUtils.DATETIME), "lastAt" to b.lastAt?.format(DateUtils.DATETIME)
    )

    private fun deltaMap(cur: Snapshot, prev: Snapshot, qty: Boolean, yield: Boolean): Map<String, Any?> = mapOf(
        "prodCnt" to (cur.total.prodCnt - prev.total.prodCnt).q(qty),
        "prodNgCnt" to (cur.total.prodNgCnt - prev.total.prodNgCnt).q(qty),
        "ngRatePt" to round2(cur.total.ngRate - prev.total.ngRate).r(yield),
        "explainedRatePt" to round2(cur.total.explainedRate - prev.total.explainedRate).r(yield),
        "topItemChanged" to (cur.total.topItem?.itemCd != prev.total.topItem?.itemCd),
        "prevTopItem" to prev.total.topItem?.itemCd
    )

    private fun Long.q(allowed: Boolean): Long? = if (allowed) this else null
    private fun Double.r(allowed: Boolean): Double? = if (allowed) this else null
}
