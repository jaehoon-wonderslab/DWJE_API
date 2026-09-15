package com.dwje.api.repository

import com.dwje.api.config.AppProperties
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.PreparedStatementCallback
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.LocalDateTime

/**
 * AOI 외관 판정 원천 `EDGE.dbo.TB_SAMSUN_COSMETIC` 직접 조회 (MSSQL).
 *
 * ## 이 표의 모양 (실측 `docs/AOI_COSMETIC_SOURCE_SURVEY_20260914.md`)
 * 한 행 = **(제품 1개) × (검사 항목 1개)** 의 판정이다. 제품 하나(`SEQ`)에 항목 행이 6~10개 붙는다.
 * - `CATEGORY_STR` 검사 항목 · `CATEGORY_NUM` **그 항목의 측정값**(유형 번호가 아니다)
 * - `PASSED` 항목 판정 · `FINAL_PASSED` **제품 판정**(한 `SEQ` 안에서 항상 하나 — 전수 확인)
 *
 * ## 기간 전체를 한 번만 읽는다 — 설비별로 쪼개지 않는다
 * 치수([AoiDimensionRepository])는 설비마다 한계 세트가 달라 설비별로 읽을 수밖에 없었다. **외관은 한계 세트가 없다.**
 * 그래서 기간 행을 통째로 임시 테이블에 한 번 내려놓고 그 위에서 집계·시리얼 목록을 모두 뽑는다.
 *
 * 실측(09-12 하루 314만 행, 설비 13대):
 *
 * | 방식 | 시간 |
 * | :--- | ---: |
 * | 설비별로 나눠 13회 조회(parallelism 3) | 집계 90초+ · 시리얼 목록 184초(제한 시간 초과) |
 * | **기간 전체 한 번 적재 + 그 위 집계** | 적재 32초 + 집계 전부 합쳐 8초 = **약 40초** |
 *
 * 이 한 번의 적재가 집계·항목·시리얼 목록을 **모두** 먹인다. 화면이 카드 세 개를 그려도 원천은 한 번만 읽는다.
 *
 * ## 원천을 다루는 규칙
 * - 모든 SELECT 에 `WITH (NOLOCK)`. 실시간 적재가 많은 표라 공유 잠금을 잡지 않는다.
 * - 날짜는 `DATE_TIME >= @from AND DATE_TIME < @to` 로만 건다. `CAST(DATE_TIME AS date)` 는 인덱스를 못 탄다.
 * - 기간 질의는 `DATE_TIME` 인덱스를 힌트로 고정한다(이름은 [safeIndexName] 로 걸러 SQL 에 넣는다).
 * - 임시 테이블은 **세션에 매여 있다.** 문장을 나눠 보내면 JdbcTemplate 이 호출마다 커넥션을 새로 얻어 `#c` 가 사라진다
 *   (오류 208). 그래서 적재·집계·정리를 한 문장열로 묶고 결과 집합을 순서대로 읽는다.
 *
 * 템플릿이 null 이면(계정 미주입) 호출 측이 먼저 걸러야 한다 — [available].
 */
@Repository
class AoiCosmeticRepository(
    @Qualifier("aoiJdbcTemplate") private val jdbc: NamedParameterJdbcTemplate?,
    appProperties: AppProperties? = null
) {

    private val dateIndex: String? = safeIndexName(appProperties?.aoi?.cosmetic?.dateIndex ?: DEFAULT_DATE_INDEX)

    companion object {
        const val TABLE = "EDGE.dbo.TB_SAMSUN_COSMETIC WITH (NOLOCK)"
        const val DEFAULT_DATE_INDEX = "SAMSUN_COSMETIC_DATE_TIME"

        /**
         * 별칭이 붙는 자리용. T-SQL 은 **별칭이 테이블 힌트보다 앞**이라 `테이블 WITH (NOLOCK) d` 는 구문 오류다
         * — [TABLE] 을 그대로 JOIN 에 쓰면 그 모양이 된다.
         */
        const val TABLE_ALIASED = "EDGE.dbo.TB_SAMSUN_COSMETIC d WITH (NOLOCK)"

        /** `COMMENT`(호기)를 자르고 앞뒤 공백을 턴다 — 치수에서 공백 수백 자가 붙어 오는 사례가 있었다 */
        const val CAVITY_EXPR = "LTRIM(RTRIM(CAST(COMMENT AS varchar(60))))"

        /**
         * 인덱스 이름은 SQL 문자열에 그대로 들어가므로 **식별자 모양만 통과시킨다.**
         * 설정에서 오는 값이라 실질 위험은 낮지만, 이름이 SQL 에 박히는 자리에 검사를 두지 않을 이유가 없다.
         * 비었거나 모양이 어긋나면 null — 힌트 없이 나간다.
         */
        fun safeIndexName(raw: String?): String? =
            raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= 128 && it.all { ch -> ch.isLetterOrDigit() || ch == '_' } }
    }

    /** 기간 조건 질의용 테이블 표기 — `DATE_TIME` 인덱스 힌트 포함(이름이 없거나 어긋나면 힌트 없음) */
    internal val tableByDate: String
        get() = dateIndex?.let { "EDGE.dbo.TB_SAMSUN_COSMETIC WITH (NOLOCK, INDEX($it))" } ?: TABLE

    val available: Boolean get() = jdbc != null

    private fun template(): NamedParameterJdbcTemplate =
        jdbc ?: throw IllegalStateException("AOI 원천(MSSQL)이 설정되지 않았습니다.")

    // ── 결과 모형 ─────────────────────────────────────────────────────────────

    /** (공정, 설비) 하나의 제품·판정 건수 */
    data class LineCount(
        val wcCd: String,
        val eqptCd: String,
        val prodCnt: Long,
        val prodNgCnt: Long,
        val rowCnt: Long,
        val serialCnt: Long,
        val singleCnt: Long,
        val multiCnt: Long,
        val unexplainedCnt: Long,
        val overriddenCnt: Long,
        val firstAt: LocalDateTime?,
        val lastAt: LocalDateTime?
    )

    /** 검사 항목 하나의 통계 */
    data class ItemStat(
        val itemCd: String,
        val inspCnt: Long,
        val ngCnt: Long,
        val ngFinalCnt: Long,
        val ngSingleCnt: Long,
        /** 합부를 정하는 항목인가 — `false` 면 `PASSED = 0` 이 「값 없음」이라 귀속에서 뺀다(`DF009`) */
        val verdict: Boolean = true
    )

    /** (공정, 설비) 하나의 집계 */
    data class LineSummary(val count: LineCount, val items: List<ItemStat>)

    /** 목록 한 행의 키 */
    data class SerialKey(val wcCd: String, val eqptCd: String, val lotNo: String, val serialNo: String) {
        fun encode(): String = "$wcCd~$eqptCd~$lotNo~$serialNo"

        /** `LOT_NO`·`SERIAL_NO` 가 비면 시리얼을 가리키지 못한다 — 상세를 열 수 없는 행이다 */
        val keyed: Boolean get() = lotNo.isNotBlank() && serialNo.isNotBlank()

        companion object {
            fun decode(raw: String): SerialKey? {
                val p = raw.split('~')
                if (p.size != 4) return null
                return SerialKey(p[0], p[1], p[2], p[3])
            }
        }
    }

    /**
     * 시리얼 한 건.
     *
     * `prodCnt`/`prodNgCnt` 는 **조회 기간 안**, `prodCntAll`/`prodNgCntAll` 은 **시리얼 전체**(날짜 무관)다.
     * 시리얼이 자정을 넘으면 둘이 다르다 — 화면이 두 숫자를 나란히 보여줄 수 있게 함께 낸다.
     */
    data class SerialRow(
        val key: SerialKey,
        val prodCnt: Long,
        val prodNgCnt: Long,
        val prodCntAll: Long,
        val prodNgCntAll: Long,
        val seqMin: Int,
        val seqMax: Int,
        val cavity: String?,
        val firstAt: LocalDateTime?,
        val lastAt: LocalDateTime?
    )

    /** 기간 한 번 읽기의 결과 전부 */
    data class PeriodData(
        val lines: List<LineSummary>,
        val serials: List<SerialRow>,
        /** 설비 경계를 넘어 **고유하게** 센 제품 수 — 설비별 합과 다를 수 있다(실측 09-12: 458,076 vs 458,073) */
        val distinctProdCnt: Long,
        val distinctSerialCnt: Long
    )

    // ── 기간 한 번 읽기 ───────────────────────────────────────────────────────

    /**
     * 기간 전체를 한 번 읽어 집계·항목·시리얼 목록을 **모두** 낸다.
     *
     * 결과 집합 다섯 개를 순서대로 읽는다 — 설비별 건수 · 설비별 항목 · 시리얼 목록 · 고유 합계.
     * 마지막 시리얼 목록은 기간 값과 시리얼 전체 값을 함께 내며, 전체 값은 시리얼마다 클러스터 PK 탐색 한 번이다
     * (실측 148 시리얼 2.5초).
     */
    fun periodData(
        wcCd: String?,
        eqptCd: String?,
        from: LocalDateTime,
        toExclusive: LocalDateTime,
        nonVerdictItems: List<String>
    ): PeriodData {
        val params = MapSqlParameterSource().addValue("from", from).addValue("to", toExclusive)
        if (wcCd != null) params.addValue("wc", wcCd)
        if (!eqptCd.isNullOrBlank()) params.addValue("eqpt", eqptCd.trim())
        if (nonVerdictItems.isNotEmpty()) params.addValue("nonVerdict", nonVerdictItems)

        val sql = buildPeriodSql(wcCd != null, !eqptCd.isNullOrBlank(), nonVerdictItems.isNotEmpty())

        return template().execute(sql, params, PreparedStatementCallback { ps: PreparedStatement ->
            val counts = mutableMapOf<Pair<String, String>, LineCount>()
            val items = mutableMapOf<Pair<String, String>, MutableList<ItemStat>>()
            val serials = mutableListOf<SerialRow>()
            var distinctProd = 0L
            var distinctSerial = 0L

            forEachResultSet(ps) { idx, rs ->
                when (idx) {
                    0 -> while (rs.next()) {
                        val k = rs.getString("wc_cd").orEmpty() to rs.getString("eqpt_cd").orEmpty()
                        counts[k] = LineCount(
                            wcCd = k.first, eqptCd = k.second,
                            prodCnt = rs.getLong("prod_cnt"), prodNgCnt = rs.getLong("prod_ng_cnt"),
                            rowCnt = rs.getLong("row_cnt"), serialCnt = rs.getLong("serial_cnt"),
                            singleCnt = rs.getLong("single_cnt"), multiCnt = rs.getLong("multi_cnt"),
                            unexplainedCnt = rs.getLong("unexplained_cnt"), overriddenCnt = rs.getLong("overridden_cnt"),
                            firstAt = rs.getTimestamp("first_at")?.toLocalDateTime(),
                            lastAt = rs.getTimestamp("last_at")?.toLocalDateTime()
                        )
                    }
                    1 -> while (rs.next()) {
                        val k = rs.getString("wc_cd").orEmpty() to rs.getString("eqpt_cd").orEmpty()
                        val cd = rs.getString("item_cd").orEmpty()
                        items.getOrPut(k) { mutableListOf() } += ItemStat(
                            itemCd = cd,
                            inspCnt = rs.getLong("insp_cnt"), ngCnt = rs.getLong("ng_cnt"),
                            ngFinalCnt = rs.getLong("ng_final_cnt"), ngSingleCnt = rs.getLong("ng_single_cnt"),
                            verdict = cd !in nonVerdictItems
                        )
                    }
                    2 -> while (rs.next()) {
                        serials += SerialRow(
                            key = SerialKey(
                                rs.getString("wc_cd").orEmpty(), rs.getString("eqpt_cd").orEmpty(),
                                rs.getString("lot_no").orEmpty(), rs.getString("serial_no").orEmpty()
                            ),
                            prodCnt = rs.getLong("prod_cnt"), prodNgCnt = rs.getLong("prod_ng_cnt"),
                            prodCntAll = rs.getLong("prod_all"), prodNgCntAll = rs.getLong("ng_all"),
                            seqMin = rs.getInt("seq_min"), seqMax = rs.getInt("seq_max"),
                            cavity = rs.getString("cavity")?.takeIf { it.isNotBlank() },
                            firstAt = rs.getTimestamp("first_at")?.toLocalDateTime(),
                            lastAt = rs.getTimestamp("last_at")?.toLocalDateTime()
                        )
                    }
                    3 -> if (rs.next()) {
                        distinctProd = rs.getLong("prod_uniq"); distinctSerial = rs.getLong("serial_uniq")
                    }
                }
            }

            PeriodData(
                lines = counts.map { (k, c) -> LineSummary(c, items[k].orEmpty()) }
                    .sortedWith(compareBy({ it.count.wcCd }, { it.count.eqptCd })),
                serials = serials,
                distinctProdCnt = distinctProd,
                distinctSerialCnt = distinctSerial
            )
        })!!
    }

    /** 문장열의 결과 집합을 순서대로 넘긴다. `SET NOCOUNT ON` 이라 갱신 건수는 끼어들지 않는다. */
    private fun forEachResultSet(ps: PreparedStatement, body: (Int, ResultSet) -> Unit) {
        var idx = 0
        var hasResult = ps.execute()
        while (true) {
            if (hasResult) {
                ps.resultSet.use { body(idx, it) }
                idx++
            } else if (ps.updateCount == -1) {
                break
            }
            hasResult = ps.getMoreResults()
        }
    }

    /**
     * 기간 한 번 읽기 SQL — 테스트에서 검증할 수 있게 밖으로 둔다.
     *
     * `#c` 는 기간 행(필요한 열만), `#p` 는 그것을 제품 단위로 접은 것이다. `#p` 는 **일부러 좁게** 둔다 —
     * 호기·시각까지 여기서 접으면 문자열 집계가 붙어 4초가 23초가 된다(실측). 그 둘은 시리얼 단위에서 한 번만 낸다.
     *
     * 사용자 입력이 문자열로 들어가는 자리는 없다 — 조건은 전부 파라미터이고, 인덱스 이름만 [safeIndexName] 을 거쳐 들어간다.
     */
    internal fun buildPeriodSql(hasWc: Boolean, hasEqpt: Boolean, hasNonVerdict: Boolean): String {
        val wcFilter = if (hasWc) "AND WC_CD = :wc" else ""
        val eqptFilter = if (hasEqpt) "AND EQPT_CD = :eqpt" else ""
        // 합부를 정하지 않는 항목(DF009)은 제품의 불량 항목 수에서 뺀다.
        val verdictOnly = if (hasNonVerdict) "AND CATEGORY_STR NOT IN (:nonVerdict)" else ""
        val verdictOnlyC = if (hasNonVerdict) "AND c.CATEGORY_STR NOT IN (:nonVerdict)" else ""

        return """
            SET NOCOUNT ON;
            IF OBJECT_ID('tempdb..#c') IS NOT NULL DROP TABLE #c;
            IF OBJECT_ID('tempdb..#p') IS NOT NULL DROP TABLE #p;
            IF OBJECT_ID('tempdb..#s') IS NOT NULL DROP TABLE #s;

            -- 기간 행. 여기만 원천을 읽는다(하루 314만 행, 콜드 32초·웜 18초 — 전체 시간의 절반)
            SELECT WC_CD, EQPT_CD, LOT_NO, SERIAL_NO, SEQ, CATEGORY_STR, PASSED, FINAL_PASSED, DATE_TIME,
                   $CAVITY_EXPR AS cavity
            INTO #c
            FROM $tableByDate
            WHERE DATE_TIME >= :from AND DATE_TIME < :to $wcFilter $eqptFilter;

            -- 제품(SEQ) 단위로 접는다. 호기·시각은 여기서 접지 않는다 — 문자열 집계가 붙으면 4초가 23초가 된다(실측)
            SELECT WC_CD, EQPT_CD, LOT_NO, SERIAL_NO, SEQ,
                   MAX(FINAL_PASSED)                                        AS fp,
                   SUM(CASE WHEN PASSED = 0 $verdictOnly THEN 1 ELSE 0 END) AS ng
            INTO #p
            FROM #c
            GROUP BY WC_CD, EQPT_CD, LOT_NO, SERIAL_NO, SEQ;

            -- 항목 집계가 #c(314만) × #p(46만)를 조인한다. 인덱스가 없으면 플랜이 무너져 3분을 넘긴다(실측).
            -- 인덱스를 9초 들여 만들면 조인이 7초 — 윈도 함수로 조인을 없애는 쪽(25초)보다 빠르다.
            CREATE CLUSTERED INDEX ix_p ON #p (WC_CD, EQPT_CD, LOT_NO, SERIAL_NO, SEQ);

            -- 시리얼 목록을 실체화한다. 파생 테이블 위에서 APPLY 를 걸면 옵티마이저가 제품마다 실행해 제한 시간을 넘긴다(실측).
            SELECT WC_CD, EQPT_CD, LOT_NO, SERIAL_NO,
                   COUNT_BIG(*)                            AS prod_cnt,
                   SUM(CASE WHEN fp = 0 THEN 1 ELSE 0 END) AS prod_ng_cnt,
                   MIN(SEQ) AS seq_min, MAX(SEQ) AS seq_max
            INTO #s
            FROM #p
            GROUP BY WC_CD, EQPT_CD, LOT_NO, SERIAL_NO;

            -- (0) 설비별 건수·귀속
            SELECT p.WC_CD AS wc_cd, p.EQPT_CD AS eqpt_cd,
                   COUNT_BIG(*)                                           AS prod_cnt,
                   SUM(CASE WHEN p.fp = 0 THEN 1 ELSE 0 END)              AS prod_ng_cnt,
                   SUM(CASE WHEN p.fp = 0 AND p.ng = 1 THEN 1 ELSE 0 END) AS single_cnt,
                   SUM(CASE WHEN p.fp = 0 AND p.ng > 1 THEN 1 ELSE 0 END) AS multi_cnt,
                   SUM(CASE WHEN p.fp = 0 AND p.ng = 0 THEN 1 ELSE 0 END) AS unexplained_cnt,
                   SUM(CASE WHEN p.fp = 1 AND p.ng > 0 THEN 1 ELSE 0 END) AS overridden_cnt,
                   COUNT(DISTINCT p.LOT_NO + '~' + p.SERIAL_NO)           AS serial_cnt,
                   MIN(r.row_cnt)  AS row_cnt,
                   MIN(r.first_at) AS first_at,
                   MAX(r.last_at)  AS last_at
            FROM #p p
            JOIN (SELECT WC_CD, EQPT_CD, COUNT_BIG(*) AS row_cnt,
                         MIN(DATE_TIME) AS first_at, MAX(DATE_TIME) AS last_at
                  FROM #c GROUP BY WC_CD, EQPT_CD) r
              ON r.WC_CD = p.WC_CD AND r.EQPT_CD = p.EQPT_CD
            GROUP BY p.WC_CD, p.EQPT_CD;

            -- (1) 설비별 검사 항목
            SELECT c.WC_CD AS wc_cd, c.EQPT_CD AS eqpt_cd, c.CATEGORY_STR AS item_cd,
                   COUNT_BIG(*)                                                                          AS insp_cnt,
                   SUM(CASE WHEN c.PASSED = 0 THEN 1 ELSE 0 END)                                         AS ng_cnt,
                   SUM(CASE WHEN c.PASSED = 0 AND c.FINAL_PASSED = 0 THEN 1 ELSE 0 END)                  AS ng_final_cnt,
                   SUM(CASE WHEN c.PASSED = 0 $verdictOnlyC AND p.fp = 0 AND p.ng = 1 THEN 1 ELSE 0 END) AS ng_single_cnt
            FROM #c c
            JOIN #p p ON p.WC_CD = c.WC_CD AND p.EQPT_CD = c.EQPT_CD AND p.LOT_NO = c.LOT_NO
                     AND p.SERIAL_NO = c.SERIAL_NO AND p.SEQ = c.SEQ
            GROUP BY c.WC_CD, c.EQPT_CD, c.CATEGORY_STR;

            -- (2) 시리얼 목록 — 기간 값 + 시리얼 전체 값(날짜 무관, 시리얼마다 PK 탐색 1회. 152 시리얼 3초)
            SELECT s.WC_CD AS wc_cd, s.EQPT_CD AS eqpt_cd, s.LOT_NO AS lot_no, s.SERIAL_NO AS serial_no,
                   s.prod_cnt, s.prod_ng_cnt, s.seq_min, s.seq_max,
                   t.cavity, t.first_at, t.last_at,
                   ISNULL(x.prod_all, s.prod_cnt)  AS prod_all,
                   ISNULL(x.ng_all, s.prod_ng_cnt) AS ng_all
            FROM #s s
            JOIN (SELECT WC_CD, EQPT_CD, LOT_NO, SERIAL_NO,
                         MIN(cavity) AS cavity, MIN(DATE_TIME) AS first_at, MAX(DATE_TIME) AS last_at
                  FROM #c GROUP BY WC_CD, EQPT_CD, LOT_NO, SERIAL_NO) t
              ON t.WC_CD = s.WC_CD AND t.EQPT_CD = s.EQPT_CD AND t.LOT_NO = s.LOT_NO AND t.SERIAL_NO = s.SERIAL_NO
            OUTER APPLY (
                SELECT COUNT_BIG(*) AS prod_all, SUM(CASE WHEN b.fp = 0 THEN 1 ELSE 0 END) AS ng_all
                FROM (SELECT d.SEQ, MAX(d.FINAL_PASSED) AS fp
                      FROM $TABLE_ALIASED
                      WHERE d.WC_CD = s.WC_CD AND d.EQPT_CD = s.EQPT_CD
                        AND d.LOT_NO = s.LOT_NO AND d.SERIAL_NO = s.SERIAL_NO
                        AND s.LOT_NO <> '' AND s.SERIAL_NO <> ''
                      GROUP BY d.SEQ) b
            ) x;

            -- (3) 설비 경계를 넘어 고유하게 센 수
            SELECT (SELECT COUNT_BIG(*) FROM (SELECT DISTINCT LOT_NO, SERIAL_NO, SEQ FROM #p) u) AS prod_uniq,
                   (SELECT COUNT_BIG(*) FROM (SELECT DISTINCT LOT_NO, SERIAL_NO FROM #p) v)      AS serial_uniq;

            DROP TABLE #s;
            DROP TABLE #p;
            DROP TABLE #c;
        """.trimIndent()
    }

    // ── 시리얼 상세 ───────────────────────────────────────────────────────────

    /** 시리얼 한 건의 전체 통계 — 기간을 걸지 않는다(자정 넘긴 앞 구간을 놓치지 않으려고) */
    data class SerialStat(val prodCnt: Long, val prodNgCnt: Long, val seqMin: Int, val seqMax: Int, val cavity: String?)

    /** 시리얼 한 건의 전체 통계 — 클러스터 PK 탐색 한 번 */
    fun serialStat(key: SerialKey, nonVerdictItems: List<String>): SerialStat? {
        val params = MapSqlParameterSource()
            .addValue("wc", key.wcCd).addValue("eqpt", key.eqptCd)
            .addValue("lot", key.lotNo).addValue("sn", key.serialNo)
        val sql = """
            SELECT COUNT_BIG(*) AS prod_cnt,
                   SUM(CASE WHEN fp = 0 THEN 1 ELSE 0 END) AS prod_ng_cnt,
                   MIN(SEQ) AS seq_min, MAX(SEQ) AS seq_max, MIN(cavity) AS cavity
            FROM (
                SELECT SEQ, MAX(FINAL_PASSED) AS fp, MIN($CAVITY_EXPR) AS cavity
                FROM $TABLE
                WHERE WC_CD = :wc AND EQPT_CD = :eqpt AND LOT_NO = :lot AND SERIAL_NO = :sn
                GROUP BY SEQ
            ) p
        """.trimIndent()
        return template().query(sql, params) { rs, _ ->
            if (rs.getLong("prod_cnt") == 0L) null
            else SerialStat(
                rs.getLong("prod_cnt"), rs.getLong("prod_ng_cnt"),
                rs.getInt("seq_min"), rs.getInt("seq_max"),
                rs.getString("cavity")?.takeIf { it.isNotBlank() }
            )
        }.firstOrNull()
    }

    /** 제품(회차) 한 건 — 항목별 판정을 안에 담는다 */
    data class ProductRow(
        val seq: Int,
        val finalPassed: Boolean,
        val measuredAt: LocalDateTime?,
        val cavity: String?,
        val checks: List<Check>
    )

    /** 검사 항목 하나의 판정 */
    data class Check(val itemCd: String, val value: Double?, val passed: Boolean)

    /**
     * 시리얼 한 건의 제품 목록 한 쪽. `onlyNg` 면 최종 불량 제품만.
     *
     * 쪽 나누기는 제품(`SEQ`) 단위다 — 항목 행 단위로 자르면 한 제품이 두 쪽에 걸린다.
     */
    fun serialProducts(key: SerialKey, onlyNg: Boolean, offset: Int, limit: Int): List<ProductRow> {
        val params = MapSqlParameterSource()
            .addValue("wc", key.wcCd).addValue("eqpt", key.eqptCd)
            .addValue("lot", key.lotNo).addValue("sn", key.serialNo)
            .addValue("offset", offset).addValue("limit", limit)
        val ngFilter = if (onlyNg) "WHERE fp = 0" else ""
        val sql = """
            WITH p AS (
                SELECT SEQ, MAX(FINAL_PASSED) AS fp
                FROM $TABLE
                WHERE WC_CD = :wc AND EQPT_CD = :eqpt AND LOT_NO = :lot AND SERIAL_NO = :sn
                GROUP BY SEQ
            ), page AS (
                SELECT SEQ, fp FROM p $ngFilter
                ORDER BY SEQ OFFSET :offset ROWS FETCH NEXT :limit ROWS ONLY
            )
            SELECT d.SEQ, page.fp, d.CATEGORY_STR, d.CATEGORY_NUM, d.PASSED,
                   d.DATE_TIME, $CAVITY_EXPR AS cavity
            FROM page
            JOIN $TABLE_ALIASED ON d.WC_CD = :wc AND d.EQPT_CD = :eqpt AND d.LOT_NO = :lot
                               AND d.SERIAL_NO = :sn AND d.SEQ = page.SEQ
            ORDER BY d.SEQ, d.CATEGORY_STR
        """.trimIndent()

        data class Flat(val seq: Int, val finalPassed: Boolean, val at: LocalDateTime?, val cavity: String?, val check: Check)
        val flat = template().query(sql, params) { rs, _ ->
            val v = rs.getDouble("CATEGORY_NUM").let { if (rs.wasNull()) null else it }
            Flat(
                seq = rs.getInt("SEQ"),
                finalPassed = rs.getInt("fp") != 0,
                at = rs.getTimestamp("DATE_TIME")?.toLocalDateTime(),
                cavity = rs.getString("cavity")?.takeIf { it.isNotBlank() },
                check = Check(rs.getString("CATEGORY_STR") ?: "", v, rs.getInt("PASSED") != 0)
            )
        }
        return flat.groupBy { it.seq }.map { (seq, rows) ->
            ProductRow(
                seq = seq,
                finalPassed = rows.first().finalPassed,
                measuredAt = rows.mapNotNull { it.at }.minOrNull(),
                cavity = rows.firstNotNullOfOrNull { it.cavity },
                checks = rows.map { it.check }
            )
        }.sortedBy { it.seq }
    }
}
