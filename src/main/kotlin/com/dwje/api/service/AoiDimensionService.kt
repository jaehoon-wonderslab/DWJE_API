package com.dwje.api.service

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.util.BusinessDay
import com.dwje.api.common.util.DataField
import com.dwje.api.common.util.DateUtils
import com.dwje.api.common.util.MaskingSupport
import com.dwje.api.common.util.MenuId
import com.dwje.api.config.AoiProperties
import com.dwje.api.config.AppProperties
import com.dwje.api.repository.AoiDimensionRepository
import java.math.BigDecimal
import java.math.RoundingMode
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
 * AOI 치수 집계 (QC-02 AI 브리핑의 숫자 담당) — MSSQL 원천 직접 조회 + 귀속 규칙 + 결과 보관.
 *
 * ## 숫자는 여기서 전부 만든다
 * 측정·불량 수, FAI 별 위반 건수·초과량, 귀속 유형(단일·복합·미확정·측정 실패), 설명률, 직전 기간 대비.
 * sLLM([AoiBriefingService])은 이 숫자를 받아 문장만 쓴다.
 *
 * ## 원천 부담을 견디는 네 가지 (현업 결정 13 대응)
 * 1. `WITH (NOLOCK)` — [AoiDimensionRepository]
 * 2. 조회 조건을 키로 결과를 보관한다. 적재가 아니라 "방금 읽은 것 보관" 이라 IO 가 사용자 조회 수에만 비례한다.
 *    같은 조건이 동시에 들어오면 한 번만 읽는다(single-flight).
 * 3. 기간 상한 `app.aoi.max-days` — 넘으면 400.
 * 4. 한 조회의 쿼리(설비 수 + 1, 기간 두 개)를 `parallelism` 만큼만 동시에 던진다. 풀 크기가 상한이다.
 *
 * ## 귀속 규칙 (조사 3·4차 §4)
 * 불량 행마다 — 사용 FAI 중 0 이 아닌 값이 N개 이하 → 측정 실패 / 확정 한계 위반 1개 → 단일 / 2개+ → 복합 / 0개 → 미확정.
 * 설명률 = (단일 + 복합) ÷ 불량. 설비마다 한계 세트가 다르므로 설비별로 따로 집계해 합친다.
 */
@Service
class AoiDimensionService(
    private val repository: AoiDimensionRepository,
    private val authorizationService: AuthorizationService,
    private val appProperties: AppProperties
) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val SOURCE_NOT_CONFIGURED = "SOURCE_NOT_CONFIGURED"
        const val SOURCE_ERROR = "SOURCE_ERROR"
    }

    private val cfg: AoiProperties get() = appProperties.aoi

    /** 원천 쿼리 동시 실행 풀 — 풀 크기(접속 수)와 같은 수만 동시에 돈다. */
    private val executor: ExecutorService by lazy {
        Executors.newFixedThreadPool(cfg.mssql.parallelism.coerceAtMost(cfg.mssql.poolSize).coerceAtLeast(1))
    }

    // ── 캐시 ─────────────────────────────────────────────────────────────────

    data class CacheKey(val from: LocalDate, val to: LocalDate, val wcCd: String, val eqptCd: String?)

    private class Entry(val value: Summary, val cachedAt: LocalDateTime, val expiresAt: LocalDateTime)

    private val cache = ConcurrentHashMap<CacheKey, Entry>()
    private val inFlight = ConcurrentHashMap<CacheKey, CompletableFuture<Summary>>()

    // ── 결과 모형 ─────────────────────────────────────────────────────────────

    /** 한 기간의 집계 (작업장 전체 + 설비별) */
    data class Summary(
        val from: LocalDate,
        val to: LocalDate,
        val wcCd: String,
        val eqptCd: String?,
        val total: Block,
        val equipments: List<Block>,
        val elapsedMs: Long,
        val queryCnt: Int
    ) {
        val days: Long get() = ChronoUnit.DAYS.between(from, to) + 1
    }

    /** 집계 한 덩어리 — 작업장 전체 또는 설비 하나 */
    data class Block(
        val eqptCd: String?,
        val limitBasis: String?,
        val resolution: Double?,
        val measCnt: Long,
        val failCnt: Long,
        val serialCnt: Long,
        val singleCnt: Long,
        val multiCnt: Long,
        val unconfirmedCnt: Long,
        val zeroCnt: Long,
        val fais: List<Fai>,
        val firstAt: LocalDateTime?,
        val lastAt: LocalDateTime?
    ) {
        val passCnt: Long get() = measCnt - failCnt
        val failRate: Double get() = pct(failCnt, measCnt)
        val explainedCnt: Long get() = singleCnt + multiCnt
        val explainedRate: Double get() = pct(explainedCnt, failCnt)
        val topFai: Fai? get() = fais.maxByOrNull { it.violSingleCnt }
    }

    /** FAI 하나의 위반 통계 */
    data class Fai(
        val fai: Int,
        val usl: Double?,
        val lsl: Double?,
        val violCnt: Long,
        val violSingleCnt: Long,
        val overCnt: Long,
        val underCnt: Long,
        val exceedSum: BigDecimal,
        /** 단일 귀속 행만의 초과량 합 — 평균은 이것으로 낸다(쓰레기 측정 행의 영향을 뺀 값) */
        val exceedSumSingle: BigDecimal,
        val exceedMax: BigDecimal?,
        /** 단일 귀속 행 중 이 FAI 의 비중(%) — "불량의 88% 가 FAI23" 의 값 */
        val sharePct: Double,
        /** 전체 불량 중 이 FAI 위반 행 비중(%) */
        val violPct: Double
    ) {
        /** 위반 행 전체 평균 초과량 */
        val exceedAvg: Double? get() = if (violCnt == 0L) null else exceedSum.divide(BigDecimal.valueOf(violCnt), 6, RoundingMode.HALF_UP).toDouble()
        /** 단일 귀속 행 평균 초과량 — 브리핑 문장은 이 값을 쓴다. 단일 행이 없으면 전체 평균 */
        val exceedAvgSingle: Double? get() =
            if (violSingleCnt == 0L) exceedAvg else exceedSumSingle.divide(BigDecimal.valueOf(violSingleCnt), 6, RoundingMode.HALF_UP).toDouble()
    }

    // ── 공개 API ─────────────────────────────────────────────────────────────

    /**
     * 집계 조회 — 현재 기간과 직전 동일 기간을 함께 낸다.
     *
     * @param wcCd   필수 — 한계 세트가 작업장·설비 단위이고, 작업장 둘을 한 번에 훑으면 원천 부담이 두 배다
     * @param eqptCd 선택. 비우면 작업장 전체(설비별 블록 포함)
     */
    fun getSummary(from: String?, to: String?, wcCd: String?, eqptCd: String?): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.QC_AOI)
        val wc = wcCd?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw InvalidParameterException("작업장 코드(wcCd)를 지정해 주세요 — 한계 세트가 작업장·설비 단위입니다.", "wcCd")
        val eqpt = eqptCd?.trim()?.takeIf { it.isNotEmpty() && it != "전체" }
        val (fromDate, toDate) = periodOf(from, to)

        if (!repository.available) {
            return mapOf(
                "reason" to SOURCE_NOT_CONFIGURED,
                "period" to periodMap(fromDate, toDate), "wcCd" to wc, "eqptCd" to eqpt,
                "maxDays" to cfg.maxDays
            ) to mask
        }

        val days = ChronoUnit.DAYS.between(fromDate, toDate) + 1
        val prevTo = fromDate.minusDays(1)
        val prevFrom = prevTo.minusDays(days - 1)

        val started = System.currentTimeMillis()
        val curKey = CacheKey(fromDate, toDate, wc, eqpt)
        val prevKey = CacheKey(prevFrom, prevTo, wc, eqpt)
        val curHit = cache[curKey]?.takeIf { it.expiresAt.isAfter(LocalDateTime.now()) }

        val current = cached(curKey)
        val previous = runCatching { cached(prevKey) }
            .onFailure { log.warn("AOI 직전 기간 집계 실패 — 현재 기간만 낸다 : {}", it.toString()) }
            .getOrNull()

        val qtyAllowed = mask.check(DataField.QTY)
        val yieldAllowed = mask.check(DataField.YIELD)

        return mapOf(
            "period" to periodMap(fromDate, toDate),
            "previousPeriod" to periodMap(prevFrom, prevTo),
            "wcCd" to wc,
            "eqptCd" to eqpt,
            "maxDays" to cfg.maxDays,
            "current" to summaryMap(current, qtyAllowed, yieldAllowed),
            "previous" to previous?.let { summaryMap(it, qtyAllowed, yieldAllowed) },
            "delta" to previous?.let { deltaMap(current.total, it.total, qtyAllowed, yieldAllowed) },
            "limitSets" to limitSetsOf(wc, current.equipments.map { it.eqptCd!! }),
            "fromCache" to (curHit != null),
            "cachedAt" to (cache[curKey]?.cachedAt?.format(DateUtils.DATETIME)),
            "elapsedMs" to (System.currentTimeMillis() - started),
            "sourceQueryCnt" to (if (curHit != null) 0 else current.queryCnt) + (previous?.queryCnt ?: 0)
        ) to mask
    }

    /** 브리핑이 쓰는 원자료 — 마스킹 전 [Summary] (마스킹은 브리핑 서비스가 입력을 만들 때 건다) */
    fun summaryFor(from: String?, to: String?, wcCd: String, eqptCd: String?): Pair<Summary, Summary?> {
        val (fromDate, toDate) = periodOf(from, to)
        val eqpt = eqptCd?.trim()?.takeIf { it.isNotEmpty() && it != "전체" }
        val days = ChronoUnit.DAYS.between(fromDate, toDate) + 1
        val prevTo = fromDate.minusDays(1)
        val current = cached(CacheKey(fromDate, toDate, wcCd, eqpt))
        val previous = runCatching { cached(CacheKey(prevTo.minusDays(days - 1), prevTo, wcCd, eqpt)) }.getOrNull()
        return current to previous
    }

    /** 한계 세트 조회 — 설정을 그대로 내린다(기획 API 2) */
    fun getLimitSets(wcCd: String?, eqptCd: String?): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.QC_AOI)
        val sets = cfg.limits.filter { wcCd.isNullOrBlank() || it.wcCd == wcCd.trim() }
            .filter { eqptCd.isNullOrBlank() || it.eqptCd == eqptCd.trim() || it.isDefault }
        return mapOf(
            "items" to sets.map { limitSetMap(it) },
            "resolved" to if (!wcCd.isNullOrBlank() && !eqptCd.isNullOrBlank()) resolveLimits(wcCd.trim(), eqptCd.trim())?.let { limitSetMap(it) } else null
        )
    }

    /** 기간 검증 — 기본은 오늘 하루, 상한은 설정 */
    fun periodOf(from: String?, to: String?): Pair<LocalDate, LocalDate> {
        val toDate = DateUtils.parseDate(to, "to", LocalDate.now())
        val fromDate = DateUtils.parseDate(from, "from", toDate)
        if (fromDate.isAfter(toDate)) {
            throw InvalidParameterException("조회 시작일이 종료일보다 늦습니다. [from=$fromDate, to=$toDate]", "from")
        }
        val days = ChronoUnit.DAYS.between(fromDate, toDate) + 1
        if (days > cfg.maxDays) {
            throw InvalidParameterException(
                "AOI 치수 조회 기간은 최대 ${cfg.maxDays}일입니다. [from=$fromDate, to=$toDate, ${days}일] — 원천을 직접 읽어 기간에 비례해 느려집니다.",
                "to"
            )
        }
        return fromDate to toDate
    }

    /** 설비에 맞는 한계 세트 — 설비 지정 세트 > 작업장 기본(`*`) > 없음 */
    fun resolveLimits(wcCd: String, eqptCd: String): AoiProperties.LimitSet? =
        cfg.limits.firstOrNull { it.wcCd == wcCd && it.eqptCd == eqptCd }
            ?: cfg.limits.firstOrNull { it.wcCd == wcCd && it.isDefault }

    // ── 집계 ─────────────────────────────────────────────────────────────────

    /**
     * 보관된 값이 있으면 그것, 없으면 한 번만 읽어 보관한다.
     *
     * 같은 키를 먼저 잡은 호출(owner)이 **자기 스레드에서** 읽고, 나중 호출은 그 결과를 기다린다.
     * 읽기를 [executor] 에 넘기지 않는 이유 — [compute] 가 설비별 쿼리를 같은 풀에 넣고 기다리므로,
     * 상위 작업까지 풀에 넣으면 요청이 겹칠 때 풀이 전부 기다리는 쪽으로 차서 멈춘다.
     */
    private fun cached(key: CacheKey): Summary {
        cache[key]?.let { if (it.expiresAt.isAfter(LocalDateTime.now())) return it.value else cache.remove(key) }

        var owner = false
        val future = inFlight.computeIfAbsent(key) { owner = true; CompletableFuture<Summary>() }
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
                log.error("AOI 원천 집계 실패 : {} — {}", key, t.toString())
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
     * 원천을 실제로 읽는다 — 설비 목록(인덱스만, 룩업 없음) 1회 + 설비마다 단일 패스 집계 1회, 병렬 [executor].
     * 설비 하나의 기간 행은 **한 번만** 룩업된다([AoiDimensionRepository.equipmentSummary]).
     */
    private fun compute(key: CacheKey): Summary {
        val started = System.currentTimeMillis()
        val window = BusinessDay.ofRange(key.from, key.to)
        val from = window.from
        val toEx = window.toExclusive

        val eqpts = repository.findEquipments(key.wcCd, key.eqptCd, from, toEx)
        val summaries = eqpts.map { e ->
            CompletableFuture.supplyAsync({
                val limits = resolveLimits(key.wcCd, e)
                val faiCount = limits?.faiCount ?: cfg.limits.firstOrNull { it.wcCd == key.wcCd }?.faiCount ?: 100
                repository.equipmentSummary(key.wcCd, e, from, toEx, limits, faiCount, cfg.zeroRowNonzeroMax)
            }, executor)
        }.map { it.join() }

        val blocks = summaries.map { (c, b) ->
            val limits = resolveLimits(key.wcCd, c.eqptCd)
            Block(
                eqptCd = c.eqptCd, limitBasis = limits?.basis, resolution = limits?.resolution,
                measCnt = c.measCnt, failCnt = c.failCnt, serialCnt = c.serialCnt,
                singleCnt = b.singleCnt, multiCnt = b.multiCnt, unconfirmedCnt = b.unconfirmedCnt, zeroCnt = b.zeroCnt,
                fais = b.fais.map { f ->
                    Fai(f.fai, f.usl, f.lsl, f.violCnt, f.violSingleCnt, f.overCnt, f.underCnt, f.exceedSum, f.exceedSumSingle, f.exceedMax,
                        sharePct = pct(f.violSingleCnt, b.singleCnt), violPct = pct(f.violCnt, c.failCnt))
                }.filter { it.violCnt > 0 }.sortedByDescending { it.violSingleCnt },
                firstAt = c.firstAt, lastAt = c.lastAt
            )
        }.sortedByDescending { it.failCnt }

        return Summary(
            from = key.from, to = key.to, wcCd = key.wcCd, eqptCd = key.eqptCd,
            total = merge(blocks), equipments = blocks,
            elapsedMs = System.currentTimeMillis() - started, queryCnt = 1 + eqpts.size
        )
    }

    /** 설비 블록을 작업장 전체로 합친다. FAI 는 번호로 합치되 한계값은 설비마다 다를 수 있어 세트가 하나일 때만 적는다. */
    internal fun merge(blocks: List<Block>): Block {
        val failCnt = blocks.sumOf { it.failCnt }
        val singleCnt = blocks.sumOf { it.singleCnt }
        val fais = blocks.flatMap { it.fais }.groupBy { it.fai }.map { (n, list) ->
            val usl = list.map { it.usl }.distinct(); val lsl = list.map { it.lsl }.distinct()
            val viol = list.sumOf { it.violCnt }; val single = list.sumOf { it.violSingleCnt }
            Fai(
                fai = n, usl = usl.singleOrNull(), lsl = lsl.singleOrNull(),
                violCnt = viol, violSingleCnt = single,
                overCnt = list.sumOf { it.overCnt }, underCnt = list.sumOf { it.underCnt },
                exceedSum = list.fold(BigDecimal.ZERO) { a, f -> a + f.exceedSum },
                exceedSumSingle = list.fold(BigDecimal.ZERO) { a, f -> a + f.exceedSumSingle },
                exceedMax = list.mapNotNull { it.exceedMax }.maxOrNull(),
                sharePct = pct(single, singleCnt), violPct = pct(viol, failCnt)
            )
        }.sortedByDescending { it.violSingleCnt }
        val bases = blocks.mapNotNull { it.limitBasis }.distinct()
        return Block(
            eqptCd = null, limitBasis = bases.singleOrNull() ?: bases.takeIf { it.isNotEmpty() }?.joinToString(" / "),
            resolution = blocks.mapNotNull { it.resolution }.distinct().singleOrNull(),
            measCnt = blocks.sumOf { it.measCnt }, failCnt = failCnt, serialCnt = blocks.sumOf { it.serialCnt },
            singleCnt = singleCnt, multiCnt = blocks.sumOf { it.multiCnt },
            unconfirmedCnt = blocks.sumOf { it.unconfirmedCnt }, zeroCnt = blocks.sumOf { it.zeroCnt },
            fais = fais, firstAt = blocks.mapNotNull { it.firstAt }.minOrNull(), lastAt = blocks.mapNotNull { it.lastAt }.maxOrNull()
        )
    }

    // ── 응답 모양 ─────────────────────────────────────────────────────────────

    private fun summaryMap(s: Summary, qty: Boolean, yield: Boolean): Map<String, Any?> = mapOf(
        "period" to periodMap(s.from, s.to),
        "total" to blockMap(s.total, qty, yield),
        "equipments" to s.equipments.map { blockMap(it, qty, yield) },
        "elapsedMs" to s.elapsedMs,
        "queryCnt" to s.queryCnt
    )

    internal fun blockMap(b: Block, qty: Boolean, yield: Boolean): Map<String, Any?> = mapOf(
        "eqptCd" to b.eqptCd,
        "limitBasis" to b.limitBasis,
        "resolution" to b.resolution?.let { plain(it) },
        "measCnt" to b.measCnt.q(qty), "passCnt" to b.passCnt.q(qty), "failCnt" to b.failCnt.q(qty),
        "serialCnt" to b.serialCnt.q(qty),
        "failRate" to b.failRate.r(yield),
        "attribution" to mapOf(
            "single" to b.singleCnt.q(qty), "multi" to b.multiCnt.q(qty),
            "unconfirmed" to b.unconfirmedCnt.q(qty), "zero" to b.zeroCnt.q(qty)
        ),
        "explainedCnt" to b.explainedCnt.q(qty),
        "explainedRate" to b.explainedRate.r(yield),
        "topFai" to b.topFai?.fai,
        "fais" to b.fais.map { f ->
            mapOf(
                "fai" to f.fai, "usl" to f.usl, "lsl" to f.lsl,
                "violCnt" to f.violCnt.q(qty), "violSingleCnt" to f.violSingleCnt.q(qty),
                "overCnt" to f.overCnt.q(qty), "underCnt" to f.underCnt.q(qty),
                "exceedSum" to if (qty) f.exceedSum.setScale(4, RoundingMode.HALF_UP).toDouble() else null,
                "exceedAvg" to f.exceedAvg?.let { round4(it) },
                "exceedAvgSingle" to f.exceedAvgSingle?.let { round4(it) },
                "exceedMax" to f.exceedMax?.setScale(4, RoundingMode.HALF_UP)?.toDouble(),
                "sharePct" to f.sharePct.r(yield), "violPct" to f.violPct.r(yield)
            )
        },
        "firstAt" to b.firstAt?.format(DateUtils.DATETIME), "lastAt" to b.lastAt?.format(DateUtils.DATETIME)
    )

    internal fun deltaMap(cur: Block, prev: Block, qty: Boolean, yield: Boolean): Map<String, Any?> = mapOf(
        "measCnt" to (cur.measCnt - prev.measCnt).q(qty),
        "failCnt" to (cur.failCnt - prev.failCnt).q(qty),
        "failRatePt" to round2(cur.failRate - prev.failRate).r(yield),
        "explainedRatePt" to round2(cur.explainedRate - prev.explainedRate).r(yield),
        "zeroCnt" to (cur.zeroCnt - prev.zeroCnt).q(qty),
        "topFaiChanged" to (cur.topFai?.fai != prev.topFai?.fai),
        "prevTopFai" to prev.topFai?.fai
    )

    private fun limitSetsOf(wcCd: String, eqpts: List<String>): List<Map<String, Any?>> =
        eqpts.mapNotNull { e -> resolveLimits(wcCd, e)?.let { it to e } }
            .groupBy({ it.first }, { it.second })
            .map { (set, list) -> limitSetMap(set) + mapOf("appliesTo" to list) }

    private fun limitSetMap(set: AoiProperties.LimitSet): Map<String, Any?> = mapOf(
        "wcCd" to set.wcCd, "eqptCd" to set.eqptCd, "resolution" to plain(set.resolution), "faiCount" to set.faiCount,
        "basis" to set.basis,
        "limits" to set.faiNumbers.map { n -> mapOf("fai" to n, "usl" to set.usl[n], "lsl" to set.lsl[n]) }
    )

    private fun periodMap(from: LocalDate, to: LocalDate) = mapOf(
        "from" to from.format(DateUtils.DATE), "to" to to.format(DateUtils.DATE), "days" to ChronoUnit.DAYS.between(from, to) + 1
    )

    /** 0.0001 이 `1.0E-4` 로 나가지 않게 — 분해능은 자릿수 그 자체가 정보다 */
    private fun plain(v: Double): BigDecimal = BigDecimal.valueOf(v).stripTrailingZeros()

    private fun Long.q(allowed: Boolean): Long? = if (allowed) this else null
    private fun Double.r(allowed: Boolean): Double? = if (allowed) this else null
}

/**
 * 원천(MSSQL) 조회 실패 — 화면이 "원천 응답 없음" 으로 그릴 수 있게 전용 코드로 나간다.
 * 제한 시간 초과(`QueryTimeoutException`)는 504 `E-SOURCE-002`, 그 외는 503 `E-SOURCE-001`.
 */
class AoiSourceException(cause: Throwable) : com.dwje.api.common.exception.BusinessException(
    if (isTimeout(cause)) com.dwje.api.common.response.ErrorCode.SOURCE_TIMEOUT else com.dwje.api.common.response.ErrorCode.SOURCE_UNAVAILABLE,
    if (isTimeout(cause)) "AOI 원천 조회가 제한 시간을 넘었습니다. 조회 기간을 줄여 다시 시도해 주세요."
    else "AOI 원천(MSSQL) 조회에 실패했습니다. 잠시 뒤 다시 시도해 주세요. [${cause.javaClass.simpleName}]"
) {
    companion object {
        private fun isTimeout(t: Throwable?): Boolean {
            var c = t
            while (c != null) {
                if (c is org.springframework.dao.QueryTimeoutException || c.javaClass.simpleName.contains("Timeout")) return true
                c = c.cause
            }
            return false
        }
    }
}

internal fun pct(n: Long, d: Long): Double = if (d <= 0L) 0.0 else round2(n * 100.0 / d)
internal fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
internal fun round4(v: Double): Double = Math.round(v * 10000.0) / 10000.0
