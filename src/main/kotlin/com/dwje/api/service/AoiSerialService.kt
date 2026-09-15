package com.dwje.api.service

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.exception.ResourceNotFoundException
import com.dwje.api.common.response.PageMeta
import com.dwje.api.common.util.DataField
import com.dwje.api.common.util.DateUtils
import com.dwje.api.common.util.MaskingSupport
import com.dwje.api.common.util.MenuId
import com.dwje.api.common.util.PageRequestParam
import com.dwje.api.config.AppProperties
import com.dwje.api.repository.AoiDimensionRepository
import com.dwje.api.repository.AoiDimensionRepository.DaySerial
import com.dwje.api.repository.AoiDimensionRepository.SerialKey
import com.dwje.api.repository.AoiDimensionRepository.SerialStat
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * AOI 판정 목록·상세 — DIMENSION(MSSQL) 시리얼 단위 (실측 문서 C-1 · C-2, 6차 요청).
 *
 * ## 왜 MES 목록에 붙이지 않았나
 * 기존 `GET /quality/aoi/defects` 는 MES 라벨 이력이고 그 AOI 설비(PACKING-AOI-*)는 DIMENSION 설비(GP-*·MQ-*)와 다른 집합이다.
 * MES 라벨에는 회차(SEQ)가 없어 `failSeqCnt` 를 붙일 자리가 없다. 화면이 이미 읽는 DIMENSION 모양(`seqCnt·failSeqCnt·items[{seq,passed}]`)을
 * 서버가 실제로 내는 것이 답이다.
 *
 * ## 원천을 두 번 나눠 읽는 이유 (속도)
 * 1. 시리얼 목록은 `DATE_TIME` 인덱스만 읽는다 — 하루 49만 행이 0.5초. `PASSED` 를 여기서 보면 행마다 룩업이 생겨 82초가 된다.
 * 2. 불량 회차 수·지그·앞쪽 불량 회차 번호는 시리얼마다 **PK 탐색 한 번**으로 낸다 — 하루 144 시리얼 3.5초(콜드)·1초 안(웜).
 *    합부는 시리얼 전체(날짜 무관)로 센다 — 자정을 넘은 시리얼의 앞 구간을 놓치지 않기 위해서다.
 * 결과는 조건 키로 보관한다(오늘 10분 · 지난 날 30분).
 */
@Service
class AoiSerialService(
    private val repository: AoiDimensionRepository,
    private val dimensionService: AoiDimensionService,
    private val authorizationService: AuthorizationService,
    private val appProperties: AppProperties
) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** 목록 행에 싣는 불량 회차 번호 수 — 화면이 「5, 12~14 외 N회차」 로 접는다 */
        const val DEFAULT_FAIL_SEQS_TOP = 20
        const val MAX_FAIL_SEQS_TOP = 100
        private val SORTS = setOf("failSeqCnt", "failRate", "lastAt", "firstAt", "serialNo", "seqCnt")
    }

    private val executor: ExecutorService by lazy {
        Executors.newFixedThreadPool(appProperties.aoi.mssql.parallelism.coerceAtMost(appProperties.aoi.mssql.poolSize).coerceAtLeast(1))
    }

    /** 목록 한 행 (마스킹 전) */
    data class SerialRow(val day: DaySerial, val stat: SerialStat?, val faiUsed: Int?) {
        val key: SerialKey get() = day.key
        val seqCnt: Long? get() = stat?.seqCnt
        val failSeqCnt: Long? get() = stat?.failSeqCnt
        val failRate: Double? get() = stat?.let { if (it.seqCnt == 0L) 0.0 else round2(it.failSeqCnt * 100.0 / it.seqCnt) }
        val partial: Boolean get() = day.seqMin > 1
    }

    private data class CacheKey(val from: LocalDate, val to: LocalDate, val wcCd: String?, val eqptCd: String?, val topN: Int)
    private class Entry(val rows: List<SerialRow>, val cachedAt: LocalDateTime, val expiresAt: LocalDateTime)
    private val cache = ConcurrentHashMap<CacheKey, Entry>()
    private val inFlight = ConcurrentHashMap<CacheKey, CompletableFuture<List<SerialRow>>>()

    // ── 목록 ─────────────────────────────────────────────────────────────────

    /**
     * 시리얼 목록. `date` 하루가 기본이고 `from`/`to` 도 받는다(상한 `app.aoi.max-days`).
     *
     * @param sort failSeqCnt(기본, 내림차순) · failRate · lastAt · firstAt · serialNo · seqCnt
     * @param failSeqsTop 행마다 싣는 불량 회차 번호 수(기본 20, 최대 100). 0 이면 번호 없이 개수만
     */
    fun getSerials(
        date: String?, from: String?, to: String?, wcCd: String?, eqptCd: String?,
        sort: String?, desc: Boolean?, failSeqsTop: Int?, page: Int?, size: Int?
    ): Triple<Map<String, Any?>, PageMeta, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.QC_AOI)
        val (fromDate, toDate) = periodOf(date, from, to)
        val wc = wcCd?.trim()?.takeIf { it.isNotEmpty() && it != "전체" }
        val eqpt = eqptCd?.trim()?.takeIf { it.isNotEmpty() && it != "전체" }
        val topN = (failSeqsTop ?: DEFAULT_FAIL_SEQS_TOP).coerceIn(0, MAX_FAIL_SEQS_TOP)
        val sortKey = (sort?.trim()?.takeIf { it.isNotEmpty() } ?: "failSeqCnt").also {
            if (it !in SORTS) throw InvalidParameterException("sort 는 ${SORTS.joinToString("·")} 중 하나입니다. [sort=$sort]", "sort")
        }
        val paging = PageRequestParam.ofAllowAll(page, size)

        if (!repository.available) {
            return Triple(
                mapOf("reason" to AoiDimensionService.SOURCE_NOT_CONFIGURED, "date" to fromDate.format(DateUtils.DATE), "items" to emptyList<Any>()),
                PageMeta.of(1, paging.size.coerceAtLeast(1), 0), mask
            )
        }

        val key = CacheKey(fromDate, toDate, wc, eqpt, topN)
        val hit = cache[key]?.takeIf { it.expiresAt.isAfter(LocalDateTime.now()) } != null
        val started = System.currentTimeMillis()
        val rows = cached(key)

        val sorted = sortRows(rows, sortKey, desc ?: true)
        val pageRows = if (paging.isAll) sorted else sorted.drop(paging.offset).take(paging.limit)
        val qty = mask.check(DataField.QTY)
        val yield = mask.check(DataField.YIELD)

        val data = mapOf(
            "date" to fromDate.format(DateUtils.DATE),
            "from" to fromDate.format(DateUtils.DATE), "to" to toDate.format(DateUtils.DATE),
            "wcCd" to wc, "eqptCd" to eqpt,
            "sort" to sortKey, "desc" to (desc ?: true), "failSeqsTop" to topN,
            "items" to pageRows.map { rowMap(it, qty, yield) },
            "fromCache" to hit, "cachedAt" to cache[key]?.cachedAt?.format(DateUtils.DATETIME),
            "elapsedMs" to (System.currentTimeMillis() - started)
        )
        val meta = if (paging.isAll) PageMeta.all(rows.size.toLong()) else PageMeta.of(paging.page, paging.size, rows.size.toLong())
        return Triple(data, meta, mask)
    }

    /** 원천을 실제로 읽는다 — 인덱스 목록 1회 + 시리얼 통계(40개씩 묶어 병렬) */
    private fun compute(key: CacheKey): List<SerialRow> {
        val from = key.from.atStartOfDay()
        val toEx = key.to.plusDays(1).atStartOfDay()
        val days = repository.findDaySerials(from, toEx, key.wcCd, key.eqptCd)
        if (days.isEmpty()) return emptyList()

        val stats = days.map { it.key }.chunked(40).map { chunk ->
            CompletableFuture.supplyAsync({ repository.serialStats(chunk, key.topN) }, executor)
        }.flatMap { it.join() }.associateBy { it.key }

        return days.map { d -> SerialRow(d, stats[d.key], dimensionService.resolveLimits(d.key.wcCd, d.key.eqptCd)?.faiCount) }
    }

    private fun cached(key: CacheKey): List<SerialRow> {
        cache[key]?.let { if (it.expiresAt.isAfter(LocalDateTime.now())) return it.rows else cache.remove(key) }
        var owner = false
        val future = inFlight.computeIfAbsent(key) { owner = true; CompletableFuture<List<SerialRow>>() }
        if (owner) {
            try {
                val rows = compute(key)
                val cfg = appProperties.aoi
                val ttl = if (!key.to.isBefore(LocalDate.now())) cfg.todayCacheTtlSec else cfg.cacheTtlSec
                if (ttl > 0) {
                    if (cache.size >= cfg.cacheMaxEntries) cache.entries.sortedBy { it.value.cachedAt }.take(cache.size - cfg.cacheMaxEntries + 1).forEach { cache.remove(it.key) }
                    cache[key] = Entry(rows, LocalDateTime.now(), LocalDateTime.now().plusSeconds(ttl))
                }
                future.complete(rows)
            } catch (t: Throwable) {
                log.error("AOI 시리얼 목록 조회 실패 : {} — {}", key, t.toString())
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

    internal fun sortRows(rows: List<SerialRow>, sort: String, desc: Boolean): List<SerialRow> {
        val cmp: Comparator<SerialRow> = when (sort) {
            "failRate" -> compareBy<SerialRow> { it.failRate ?: -1.0 }
            "lastAt" -> compareBy { it.day.lastAt }
            "firstAt" -> compareBy { it.day.firstAt }
            "serialNo" -> compareBy<SerialRow> { it.key.lotNo }.thenBy { it.key.serialNo }
            "seqCnt" -> compareBy { it.seqCnt ?: -1L }
            else -> compareBy<SerialRow> { it.failSeqCnt ?: -1L }
        }
        val tie = compareBy<SerialRow> { it.key.wcCd }.thenBy { it.key.eqptCd }.thenBy { it.key.lotNo }.thenBy { it.key.serialNo }
        return rows.sortedWith((if (desc) cmp.reversed() else cmp).thenComparing(tie))
    }

    internal fun rowMap(r: SerialRow, qty: Boolean, yield: Boolean): Map<String, Any?> {
        val failSeqs = r.stat?.failSeqs.orEmpty()
        return linkedMapOf(
            "serialKey" to r.key.key,
            "wcCd" to r.key.wcCd, "eqptCd" to r.key.eqptCd, "lotNo" to r.key.lotNo, "serialNo" to r.key.serialNo,
            "seqCnt" to r.seqCnt?.takeIf { qty },
            "failSeqCnt" to r.failSeqCnt?.takeIf { qty },
            "failRate" to r.failRate?.takeIf { yield },
            "passed" to r.failSeqCnt?.let { it == 0L },
            // 조회 기간 안의 구간 — 시리얼이 자정을 넘으면 앞 구간은 전날에 있다(partial)
            "daySeqCnt" to r.day.daySeqCnt.takeIf { qty },
            "seqMin" to r.day.seqMin, "seqMax" to r.day.seqMax, "partial" to r.partial,
            "firstAt" to r.day.firstAt?.format(DateUtils.DATETIME), "lastAt" to r.day.lastAt?.format(DateUtils.DATETIME),
            "cavity" to r.stat?.cavity,
            "faiUsed" to r.faiUsed,
            // 앞쪽 불량 회차 번호. failSeqCnt 보다 짧으면 잘린 것 — 화면은 「5, 12~14 외 N회차」 로 적는다
            "failSeqs" to failSeqs,
            "failSeqsTruncated" to (r.failSeqCnt?.let { it > failSeqs.size } ?: false)
        )
    }

    // ── 상세 ─────────────────────────────────────────────────────────────────

    /**
     * 시리얼 한 건 — 머리(회차·불량 회차·지그) + 회차 목록 한 쪽(기본 불량 회차만, 100개) + FAI 번호·확정 한계(spec).
     *
     * @param only `ng`(기본) 불량 회차만 · `all` 전 회차
     */
    fun getSerial(serialKey: String, only: String?, page: Int?, size: Int?): Triple<Map<String, Any?>, PageMeta, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.QC_AOI)
        val key = SerialKey.parse(serialKey)
            ?: throw InvalidParameterException("serialKey 는 wc~eqpt~lot~serial 네 조각이어야 합니다. [$serialKey]", "serialKey")
        val onlyNg = when ((only ?: "ng").trim().lowercase()) {
            "ng" -> true; "all" -> false
            else -> throw InvalidParameterException("only 는 ng 또는 all 입니다. [only=$only]", "only")
        }
        if (!repository.available) {
            return Triple(mapOf("reason" to AoiDimensionService.SOURCE_NOT_CONFIGURED, "serialKey" to key.key), PageMeta.of(1, 100, 0), mask)
        }
        val paging = PageRequestParam.of(page, size ?: 100)

        val stat = repository.serialStats(listOf(key), DEFAULT_FAIL_SEQS_TOP).firstOrNull()
        if (stat == null || stat.seqCnt == 0L) throw ResourceNotFoundException("AOI 시리얼을 찾을 수 없습니다. [$serialKey]")

        val limits = dimensionService.resolveLimits(key.wcCd, key.eqptCd)
        val faiCount = limits?.faiCount ?: appProperties.aoi.limits.firstOrNull { it.wcCd == key.wcCd }?.faiCount ?: 100
        val items = repository.serialItems(key, onlyNg, faiCount, paging.limit, paging.offset)
        val total = if (onlyNg) stat.failSeqCnt else stat.seqCnt

        // 그 설비가 실제로 쓰는 FAI — 이 쪽에서 값이 하나라도 있는 번호. 화면이 이걸로 열을 만든다.
        val faiNos = (1..faiCount).filter { n -> items.any { it.values[n - 1] != null } }.ifEmpty { (1..faiCount).toList() }
        val qty = mask.check(DataField.QTY)
        val yield = mask.check(DataField.YIELD)

        val data = linkedMapOf<String, Any?>(
            "serialKey" to key.key,
            "wcCd" to key.wcCd, "eqptCd" to key.eqptCd, "lotNo" to key.lotNo, "serialNo" to key.serialNo,
            "seqCnt" to stat.seqCnt.takeIf { qty }, "failSeqCnt" to stat.failSeqCnt.takeIf { qty },
            "failRate" to (if (stat.seqCnt == 0L) 0.0 else round2(stat.failSeqCnt * 100.0 / stat.seqCnt)).takeIf { yield },
            "passed" to (stat.failSeqCnt == 0L),
            "cavity" to stat.cavity,
            "failSeqs" to stat.failSeqs, "failSeqsTruncated" to (stat.failSeqCnt > stat.failSeqs.size),
            "only" to (if (onlyNg) "ng" else "all"),
            "faiNos" to faiNos,
            // 확정 한계가 있는 FAI 만 — 화면이 벗어난 값을 붉게 칠 수 있다(실측 문서 C-3). 없는 FAI 는 한계 없음
            "spec" to limits?.faiNumbers?.map { n -> mapOf("no" to n, "lower" to limits.lsl[n], "upper" to limits.usl[n]) }.orEmpty(),
            "limitBasis" to limits?.basis, "resolution" to limits?.resolution,
            "items" to items.map { it ->
                val violations = limits?.let { l ->
                    l.faiNumbers.filter { n ->
                        val v = it.values.getOrNull(n - 1) ?: return@filter false
                        (l.usl[n]?.let { u -> v > u } ?: false) || (l.lsl[n]?.let { lo -> v < lo } ?: false)
                    }
                }.orEmpty()
                mapOf(
                    "seq" to it.seq, "passed" to it.passed, "measuredAt" to it.measuredAt?.format(DateUtils.DATETIME), "cavity" to it.cavity,
                    "measurements" to faiNos.mapNotNull { n -> it.values[n - 1]?.let { v -> mapOf("no" to n, "value" to v) } },
                    "violFais" to violations
                )
            }
        )
        return Triple(data, PageMeta.of(paging.page, paging.size, total), mask)
    }

    // ── 도우미 ───────────────────────────────────────────────────────────────

    /** `date` 하루 또는 `from`/`to`. 둘 다 없으면 오늘. 상한은 집계와 같다 */
    internal fun periodOf(date: String?, from: String?, to: String?): Pair<LocalDate, LocalDate> {
        date?.trim()?.takeIf { it.isNotEmpty() }?.let { d -> val x = DateUtils.parseDate(d, "date"); return x to x }
        val (f, t) = dimensionService.periodOf(from, to)
        if (ChronoUnit.DAYS.between(f, t) + 1 > appProperties.aoi.maxDays) {
            throw InvalidParameterException("AOI 시리얼 목록 기간은 최대 ${appProperties.aoi.maxDays}일입니다.", "to")
        }
        return f to t
    }
}
