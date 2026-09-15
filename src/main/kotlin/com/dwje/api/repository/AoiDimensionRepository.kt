package com.dwje.api.repository

import com.dwje.api.config.AoiProperties
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * AOI 치수 원천 `EDGE.dbo.TB_SAMSUN_DIMENSION` 직접 조회 (MSSQL).
 *
 * ## 원천을 다루는 규칙 (실측 문서 · 현업 결정 13)
 * - 모든 SELECT 에 `WITH (NOLOCK)` — 실시간 적재가 많은 표라 공유 잠금을 잡지 않는다. 더티 리드는 집계 오차 몇 행으로
 *   감수한다(브리핑 용도).
 * - 날짜는 `DATE_TIME >= @from AND DATE_TIME < @to` 로만 건다. `CAST(DATE_TIME AS date)` 는 인덱스를 못 탄다.
 * - `FAI*` 는 **불량 행에서, 한계가 있는 열만** 읽는다. 행이 800B 를 넘어 열을 줄이는 것이 IO 를 줄이는 유일한 길이다.
 *   (측정 실패 판정에 필요한 "0 이 아닌 FAI 개수" 는 SQL 식으로 세서 열을 전송하지 않는다)
 * - 한계값은 설정 숫자를 리터럴로 박는다. 열 이름은 1~100 정수로만 만든다 — 사용자 입력이 SQL 에 들어가는 자리는 없다.
 *
 * 템플릿이 null 이면(계정 미주입) 호출 측이 먼저 걸러야 한다 — [available].
 */
@Repository
class AoiDimensionRepository(
    @Qualifier("aoiJdbcTemplate") private val jdbc: NamedParameterJdbcTemplate?,
    private val appProperties: AoiRepositoryProps = AoiRepositoryProps()
) {

    /** 기간 조건 질의용 테이블 표기 — `DATE_TIME` 인덱스 힌트 포함(설정이 비면 힌트 없음) */
    private val tableByDate: String
        get() = appProperties.dateIndex?.takeIf { it.isNotBlank() }
            ?.let { "EDGE.dbo.TB_SAMSUN_DIMENSION WITH (NOLOCK, INDEX($it))" } ?: TABLE

    companion object {
        const val TABLE = "EDGE.dbo.TB_SAMSUN_DIMENSION WITH (NOLOCK)"
        /** 행 수준 집계를 담는 가짜 FAI 키 */
        const val ROW_KEY = "__ROW__"
    }

    val available: Boolean get() = jdbc != null

    private fun template(): NamedParameterJdbcTemplate =
        jdbc ?: throw IllegalStateException("AOI 원천(MSSQL)이 설정되지 않았습니다.")

    /**
     * 기간에 측정 기록이 있는 설비 목록 — `DATE_TIME` 비클러스터 인덱스만으로 답한다.
     *
     * 클러스터 키(WC_CD·EQPT_CD·LOT_NO·SERIAL_NO·SEQ)가 비클러스터 인덱스에 따라붙으므로 이 질의는 행 룩업이 없다.
     * `PASSED` 나 `FAI*` 를 건드리는 순간 행마다 룩업이 생긴다 — 그 일은 [equipmentSummary] 한 번으로 몰아 둔다.
     */
    fun findEquipments(wcCd: String, eqptCd: String?, from: LocalDateTime, toExclusive: LocalDateTime): List<String> {
        val params = MapSqlParameterSource().addValue("wc", wcCd).addValue("from", from).addValue("to", toExclusive)
        val eqptFilter = if (eqptCd.isNullOrBlank()) "" else { params.addValue("eqpt", eqptCd.trim()); "AND EQPT_CD = :eqpt" }
        val sql = """
            SELECT DISTINCT EQPT_CD FROM $tableByDate
            WHERE WC_CD = :wc AND DATE_TIME >= :from AND DATE_TIME < :to $eqptFilter
            ORDER BY EQPT_CD
        """.trimIndent()
        return template().query(sql, params) { rs, _ -> rs.getString("EQPT_CD") }
    }

    /** 설비별 측정·불량·시리얼 수 */
    data class EquipmentCount(
        val eqptCd: String,
        val measCnt: Long,
        val failCnt: Long,
        val serialCnt: Long,
        val firstAt: LocalDateTime?,
        val lastAt: LocalDateTime?
    )

    /**
     * 기간·작업장(·설비) 측정 수와 불량 수를 설비별로 센다. `FAI*` 를 읽지 않는다.
     */
    fun countByEquipment(wcCd: String, eqptCd: String?, from: LocalDateTime, toExclusive: LocalDateTime): List<EquipmentCount> {
        val params = MapSqlParameterSource()
            .addValue("wc", wcCd).addValue("from", from).addValue("to", toExclusive)
        val eqptFilter = if (eqptCd.isNullOrBlank()) "" else {
            params.addValue("eqpt", eqptCd.trim()); "AND EQPT_CD = :eqpt"
        }
        val sql = """
            SELECT EQPT_CD,
                   COUNT_BIG(*)                                        AS meas_cnt,
                   SUM(CASE WHEN PASSED = '0' THEN 1 ELSE 0 END)       AS fail_cnt,
                   COUNT(DISTINCT LOT_NO + '~' + SERIAL_NO)            AS serial_cnt,
                   MIN(DATE_TIME) AS first_at, MAX(DATE_TIME) AS last_at
            FROM $TABLE
            WHERE WC_CD = :wc AND DATE_TIME >= :from AND DATE_TIME < :to $eqptFilter
            GROUP BY EQPT_CD
            ORDER BY EQPT_CD
        """.trimIndent()
        return template().query(sql, params) { rs, _ ->
            EquipmentCount(
                eqptCd = rs.getString("EQPT_CD"),
                measCnt = rs.getLong("meas_cnt"),
                failCnt = rs.getLong("fail_cnt"),
                serialCnt = rs.getLong("serial_cnt"),
                firstAt = rs.getTimestamp("first_at")?.toLocalDateTime(),
                lastAt = rs.getTimestamp("last_at")?.toLocalDateTime()
            )
        }
    }

    /** 불량 행의 귀속·FAI 별 위반 집계 — 설비 하나 */
    data class FailBreakdown(
        val failCnt: Long,
        val zeroCnt: Long,
        val singleCnt: Long,
        val multiCnt: Long,
        val unconfirmedCnt: Long,
        val fais: List<FaiStat>
    )

    data class FaiStat(
        val fai: Int,
        val usl: Double?,
        val lsl: Double?,
        val violCnt: Long,
        val violSingleCnt: Long,
        val overCnt: Long,
        val underCnt: Long,
        val exceedSum: BigDecimal,
        /** 단일 귀속 행(그 FAI 만 벗어난 행)만의 초과량 합 — 전항목 이탈 같은 쓰레기 측정이 평균을 끌어올리지 않게 따로 낸다 */
        val exceedSumSingle: BigDecimal,
        val exceedMax: BigDecimal?
    )

    /** 설비 하나의 기간 집계 — 건수와 불량 분해를 **한 번의 행 읽기**로 */
    data class EquipmentSummary(val count: EquipmentCount, val breakdown: FailBreakdown)

    /**
     * 설비 하나를 **한 번만 읽어** 측정·불량·시리얼 수와 불량 분해를 함께 낸다.
     *
     * ## 왜 한 번인가
     * 원천에는 `DATE_TIME` 인덱스에 `PASSED` 가 없어, `PASSED` 를 보는 모든 질의가 기간 행 전부를 룩업한다.
     * 건수 질의와 불량 분해 질의를 따로 내면 같은 행을 두 번 룩업한다(1일 6설비 실측 67초). 그래서 기간 행을
     * 필요한 열만 골라 세션 임시 테이블에 한 번 내려놓고(`SELECT … INTO #a`), 건수와 분해를 그 위에서 낸다.
     * 임시 테이블은 배치 끝에서 지운다 — 풀이 세션을 재사용하기 때문이다.
     *
     * 결과 집합은 하나다: [ROW_KEY] 행이 건수·귀속 합계를, `FAIn` 행이 FAI 별 통계를 든다.
     */
    fun equipmentSummary(
        wcCd: String,
        eqptCd: String,
        from: LocalDateTime,
        toExclusive: LocalDateTime,
        limits: AoiProperties.LimitSet?,
        faiCount: Int,
        zeroNonzeroMax: Int
    ): EquipmentSummary {
        val fais = limits?.faiNumbers.orEmpty()
        val sql = buildEquipmentSummarySql(fais, limits, faiCount, zeroNonzeroMax)
        val params = MapSqlParameterSource()
            .addValue("wc", wcCd).addValue("eqpt", eqptCd).addValue("from", from).addValue("to", toExclusive)

        var count: EquipmentCount? = null
        var row: FailBreakdown? = null
        val stats = mutableListOf<FaiStat>()
        template().query(sql, params) { rs ->
            val k = rs.getString("k")
            if (k == ROW_KEY) {
                count = EquipmentCount(
                    eqptCd = eqptCd, measCnt = rs.getLong("meas_cnt"), failCnt = rs.getLong("n"),
                    serialCnt = rs.getLong("serial_cnt"),
                    firstAt = rs.getTimestamp("first_at")?.toLocalDateTime(), lastAt = rs.getTimestamp("last_at")?.toLocalDateTime()
                )
                row = FailBreakdown(
                    failCnt = rs.getLong("n"), zeroCnt = rs.getLong("zero_cnt"),
                    singleCnt = rs.getLong("single_rows"), multiCnt = rs.getLong("multi_rows"),
                    unconfirmedCnt = rs.getLong("unconfirmed_rows"), fais = emptyList()
                )
            } else {
                val fai = k.removePrefix("FAI").toInt()
                stats += FaiStat(
                    fai = fai, usl = limits?.usl?.get(fai), lsl = limits?.lsl?.get(fai),
                    violCnt = rs.getLong("viol_cnt"), violSingleCnt = rs.getLong("viol_single_cnt"),
                    overCnt = rs.getLong("over_cnt"), underCnt = rs.getLong("under_cnt"),
                    exceedSum = rs.getBigDecimal("exceed_sum") ?: BigDecimal.ZERO,
                    exceedSumSingle = rs.getBigDecimal("exceed_sum_single") ?: BigDecimal.ZERO,
                    exceedMax = rs.getBigDecimal("exceed_max")
                )
            }
        }
        return EquipmentSummary(
            count = count ?: EquipmentCount(eqptCd, 0, 0, 0, null, null),
            breakdown = (row ?: FailBreakdown(0, 0, 0, 0, 0, emptyList())).copy(fais = stats.sortedBy { it.fai })
        )
    }

    /**
     * 단일 패스 집계 SQL — 테스트에서 검증할 수 있게 밖으로 둔다. [buildFailBreakdownSql] 과 같은 식을 쓰되
     * 기간 행 전체를 임시 테이블에 한 번 내려 건수까지 함께 낸다.
     */
    internal fun buildEquipmentSummarySql(
        fais: List<Int>,
        limits: AoiProperties.LimitSet?,
        faiCount: Int,
        zeroNonzeroMax: Int
    ): String {
        require(faiCount in 1..100) { "faiCount 는 1~100 이어야 한다" }
        require(fais.all { it in 1..100 }) { "FAI 번호는 1~100 이어야 한다" }
        val nonzeroExpr = (1..faiCount).joinToString(" + ") { "CASE WHEN FAI$it <> 0 THEN 1 ELSE 0 END" }
        val violExpr = if (fais.isEmpty()) "0" else fais.joinToString(" + ") { n ->
            val usl = limits?.usl?.get(n)?.let { "FAI$n > ${lit(it)}" }
            val lsl = limits?.lsl?.get(n)?.let { "FAI$n < ${lit(it)}" }
            "CASE WHEN ${listOfNotNull(usl, lsl).joinToString(" OR ")} THEN 1 ELSE 0 END"
        }
        val faiCols = if (fais.isEmpty()) "" else ", " + fais.joinToString(", ") { "FAI$it" }
        val values = listOf("('$ROW_KEY', CAST(NULL AS float), CAST(NULL AS float), CAST(NULL AS float))") +
            fais.map { n -> "('FAI$n', FAI$n, ${limits?.usl?.get(n)?.let(::lit) ?: "NULL"}, ${limits?.lsl?.get(n)?.let(::lit) ?: "NULL"})" }

        return """
            SET NOCOUNT ON;
            IF OBJECT_ID('tempdb..#a') IS NOT NULL DROP TABLE #a;
            SELECT PASSED, LOT_NO, SERIAL_NO, DATE_TIME,
                   CASE WHEN PASSED = '0' AND ($nonzeroExpr) <= $zeroNonzeroMax THEN 1 ELSE 0 END AS z,
                   CASE WHEN PASSED = '0' THEN ($violExpr) ELSE 0 END AS viol$faiCols
            INTO #a
            FROM $tableByDate
            WHERE WC_CD = :wc AND EQPT_CD = :eqpt AND DATE_TIME >= :from AND DATE_TIME < :to;
            SELECT x.k,
                   (SELECT COUNT_BIG(*) FROM #a)                                               AS meas_cnt,
                   (SELECT COUNT(DISTINCT LOT_NO + '~' + SERIAL_NO) FROM #a)                    AS serial_cnt,
                   (SELECT MIN(DATE_TIME) FROM #a)                                              AS first_at,
                   (SELECT MAX(DATE_TIME) FROM #a)                                              AS last_at,
                   COUNT_BIG(*)                                                                 AS n,
                   SUM(z)                                                                       AS zero_cnt,
                   SUM(CASE WHEN z = 0 AND viol = 1 THEN 1 ELSE 0 END)                          AS single_rows,
                   SUM(CASE WHEN z = 0 AND viol >= 2 THEN 1 ELSE 0 END)                         AS multi_rows,
                   SUM(CASE WHEN z = 0 AND viol = 0 THEN 1 ELSE 0 END)                          AS unconfirmed_rows,
                   SUM(CASE WHEN z = 0 AND (x.v > x.usl OR x.v < x.lsl) THEN 1 ELSE 0 END)      AS viol_cnt,
                   SUM(CASE WHEN z = 0 AND viol = 1 AND (x.v > x.usl OR x.v < x.lsl) THEN 1 ELSE 0 END) AS viol_single_cnt,
                   SUM(CASE WHEN z = 0 AND x.v > x.usl THEN 1 ELSE 0 END)                       AS over_cnt,
                   SUM(CASE WHEN z = 0 AND x.v < x.lsl THEN 1 ELSE 0 END)                       AS under_cnt,
                   SUM(CASE WHEN z = 0 AND x.v > x.usl THEN x.v - x.usl
                            WHEN z = 0 AND x.v < x.lsl THEN x.lsl - x.v ELSE 0 END)             AS exceed_sum,
                   SUM(CASE WHEN z = 0 AND viol = 1 AND x.v > x.usl THEN x.v - x.usl
                            WHEN z = 0 AND viol = 1 AND x.v < x.lsl THEN x.lsl - x.v ELSE 0 END) AS exceed_sum_single,
                   MAX(CASE WHEN z = 0 AND x.v > x.usl THEN x.v - x.usl
                            WHEN z = 0 AND x.v < x.lsl THEN x.lsl - x.v ELSE 0 END)             AS exceed_max
            FROM #a f
            CROSS APPLY (VALUES ${values.joinToString(", ")}) x(k, v, usl, lsl)
            WHERE f.PASSED = '0'
            GROUP BY x.k;
            DROP TABLE #a;
        """.trimIndent()
    }

    /**
     * 설비 하나의 불량 행을 한 번 읽어 귀속 유형(측정 실패 · 단일 · 복합 · 미확정)과 FAI 별 위반 통계를 낸다.
     *
     * ## 한 쿼리로 끝내는 방법
     * 불량 행마다 `z`(측정 실패 여부)와 `viol`(확정 한계를 넘은 FAI 개수)을 계산한 뒤 `CROSS APPLY (VALUES …)` 로
     * 한계가 있는 FAI 만 세로로 펴서 FAI 별로 묶는다. 가짜 키 [ROW_KEY] 행이 행 수준 합계를 들고 온다
     * (모든 키 행이 같은 행 집합을 보므로 어느 행에서 읽어도 같다).
     *
     * 귀속 규칙(4차 문서 §4): 측정 실패를 **먼저** 가르고, 나머지를 viol 개수로 1 · 2+ · 0 으로 나눈다.
     * 한계값이 NULL 인 쪽 비교(`v > NULL`)는 거짓이라 한쪽 한계만 있어도 맞게 센다.
     */
    fun failBreakdown(
        wcCd: String,
        eqptCd: String,
        from: LocalDateTime,
        toExclusive: LocalDateTime,
        limits: AoiProperties.LimitSet?,
        faiCount: Int,
        zeroNonzeroMax: Int
    ): FailBreakdown {
        val fais = limits?.faiNumbers.orEmpty()
        val sql = buildFailBreakdownSql(fais, limits, faiCount, zeroNonzeroMax)
        val params = MapSqlParameterSource()
            .addValue("wc", wcCd).addValue("eqpt", eqptCd).addValue("from", from).addValue("to", toExclusive)

        var row: FailBreakdown? = null
        val stats = mutableListOf<FaiStat>()
        template().query(sql, params) { rs ->
            val k = rs.getString("k")
            if (k == ROW_KEY) {
                row = FailBreakdown(
                    failCnt = rs.getLong("n"),
                    zeroCnt = rs.getLong("zero_cnt"),
                    singleCnt = rs.getLong("single_rows"),
                    multiCnt = rs.getLong("multi_rows"),
                    unconfirmedCnt = rs.getLong("unconfirmed_rows"),
                    fais = emptyList()
                )
            } else {
                val fai = k.removePrefix("FAI").toInt()
                stats += FaiStat(
                    fai = fai,
                    usl = limits?.usl?.get(fai),
                    lsl = limits?.lsl?.get(fai),
                    violCnt = rs.getLong("viol_cnt"),
                    violSingleCnt = rs.getLong("viol_single_cnt"),
                    overCnt = rs.getLong("over_cnt"),
                    underCnt = rs.getLong("under_cnt"),
                    exceedSum = rs.getBigDecimal("exceed_sum") ?: BigDecimal.ZERO,
                    exceedSumSingle = rs.getBigDecimal("exceed_sum_single") ?: BigDecimal.ZERO,
                    exceedMax = rs.getBigDecimal("exceed_max")
                )
            }
        }
        val base = row ?: FailBreakdown(0, 0, 0, 0, 0, emptyList())
        return base.copy(fais = stats.sortedBy { it.fai })
    }

    /**
     * 불량 행 집계 SQL — 테스트에서 검증할 수 있게 밖으로 둔다.
     *
     * @param fais           한계가 있는 FAI 번호(비면 FAI 행 없이 행 수준만)
     * @param faiCount       측정 실패 판정에 셀 FAI 범위(1..faiCount)
     * @param zeroNonzeroMax 0 이 아닌 FAI 가 이 개수 이하면 측정 실패
     */
    internal fun buildFailBreakdownSql(
        fais: List<Int>,
        limits: AoiProperties.LimitSet?,
        faiCount: Int,
        zeroNonzeroMax: Int
    ): String {
        require(faiCount in 1..100) { "faiCount 는 1~100 이어야 한다" }
        require(fais.all { it in 1..100 }) { "FAI 번호는 1~100 이어야 한다" }

        val nonzeroExpr = (1..faiCount).joinToString(" + ") { "CASE WHEN FAI$it <> 0 THEN 1 ELSE 0 END" }
        val violExpr = if (fais.isEmpty()) "0" else fais.joinToString(" + ") { n ->
            val usl = limits?.usl?.get(n)?.let { "FAI$n > ${lit(it)}" }
            val lsl = limits?.lsl?.get(n)?.let { "FAI$n < ${lit(it)}" }
            "CASE WHEN ${listOfNotNull(usl, lsl).joinToString(" OR ")} THEN 1 ELSE 0 END"
        }
        val faiCols = if (fais.isEmpty()) "" else ", " + fais.joinToString(", ") { "FAI$it" }
        val values = listOf("('$ROW_KEY', CAST(NULL AS float), CAST(NULL AS float), CAST(NULL AS float))") +
            fais.map { n ->
                "('FAI$n', FAI$n, ${limits?.usl?.get(n)?.let(::lit) ?: "NULL"}, ${limits?.lsl?.get(n)?.let(::lit) ?: "NULL"})"
            }

        return """
            WITH f AS (
                SELECT CASE WHEN ($nonzeroExpr) <= $zeroNonzeroMax THEN 1 ELSE 0 END AS z,
                       ($violExpr) AS viol$faiCols
                FROM $TABLE
                WHERE WC_CD = :wc AND EQPT_CD = :eqpt AND DATE_TIME >= :from AND DATE_TIME < :to AND PASSED = '0'
            )
            SELECT x.k,
                   COUNT_BIG(*)                                                              AS n,
                   SUM(z)                                                                    AS zero_cnt,
                   SUM(CASE WHEN z = 0 AND viol = 1 THEN 1 ELSE 0 END)                       AS single_rows,
                   SUM(CASE WHEN z = 0 AND viol >= 2 THEN 1 ELSE 0 END)                      AS multi_rows,
                   SUM(CASE WHEN z = 0 AND viol = 0 THEN 1 ELSE 0 END)                       AS unconfirmed_rows,
                   SUM(CASE WHEN z = 0 AND (x.v > x.usl OR x.v < x.lsl) THEN 1 ELSE 0 END)   AS viol_cnt,
                   SUM(CASE WHEN z = 0 AND viol = 1 AND (x.v > x.usl OR x.v < x.lsl) THEN 1 ELSE 0 END) AS viol_single_cnt,
                   SUM(CASE WHEN z = 0 AND x.v > x.usl THEN 1 ELSE 0 END)                    AS over_cnt,
                   SUM(CASE WHEN z = 0 AND x.v < x.lsl THEN 1 ELSE 0 END)                    AS under_cnt,
                   SUM(CASE WHEN z = 0 AND x.v > x.usl THEN x.v - x.usl
                            WHEN z = 0 AND x.v < x.lsl THEN x.lsl - x.v ELSE 0 END)          AS exceed_sum,
                   SUM(CASE WHEN z = 0 AND viol = 1 AND x.v > x.usl THEN x.v - x.usl
                            WHEN z = 0 AND viol = 1 AND x.v < x.lsl THEN x.lsl - x.v ELSE 0 END) AS exceed_sum_single,
                   MAX(CASE WHEN z = 0 AND x.v > x.usl THEN x.v - x.usl
                            WHEN z = 0 AND x.v < x.lsl THEN x.lsl - x.v ELSE 0 END)          AS exceed_max
            FROM f
            CROSS APPLY (VALUES ${values.joinToString(", ")}) x(k, v, usl, lsl)
            GROUP BY x.k
        """.trimIndent()
    }


    // ── 시리얼 목록·상세 (QC-02 AOI 판정 목록 — 실측 문서 C-1 · C-2) ─────────────

    /** 시리얼 키 — 클러스터 PK 의 앞 네 열. `serialKey` 문자열은 `wc~eqpt~lot~serial` */
    data class SerialKey(val wcCd: String, val eqptCd: String, val lotNo: String, val serialNo: String) {
        val key: String get() = listOf(wcCd, eqptCd, lotNo, serialNo).joinToString("~")

        companion object {
            /** `S120~MQ-008~20260910~00013` → 키. 네 조각이 아니거나 빈 조각이 있으면 null */
            fun parse(serialKey: String?): SerialKey? {
                val parts = serialKey?.trim()?.split('~') ?: return null
                if (parts.size != 4 || parts.drop(1).any { it.isBlank() }) return null
                return SerialKey(parts[0], parts[1], parts[2], parts[3])
            }
        }
    }

    /** 하루(기간) 안에서 본 시리얼 한 건 — 인덱스만으로 얻는 값 */
    data class DaySerial(val key: SerialKey, val daySeqCnt: Long, val seqMin: Int, val seqMax: Int, val firstAt: LocalDateTime?, val lastAt: LocalDateTime?)

    /**
     * 기간에 측정 기록이 있는 시리얼 목록 — `DATE_TIME` 인덱스만 읽는다(클러스터 키가 따라붙어 룩업 없음).
     * 실측: 하루 전 사업장 49만 행 → 144 시리얼, 0.5초. `PASSED`·`COMMENT` 를 여기서 읽으면 행마다 룩업이 생기므로 [serialStats] 로 뺀다.
     * 로트·시리얼이 비어 있는 행(하루 1~7건, 설비만 있는 측정)은 뺀다.
     */
    fun findDaySerials(from: LocalDateTime, toExclusive: LocalDateTime, wcCd: String?, eqptCd: String?): List<DaySerial> {
        val params = MapSqlParameterSource().addValue("from", from).addValue("to", toExclusive)
        val wcFilter = if (wcCd.isNullOrBlank()) "" else { params.addValue("wc", wcCd.trim()); "AND WC_CD = :wc" }
        val eqptFilter = if (eqptCd.isNullOrBlank()) "" else { params.addValue("eqpt", eqptCd.trim()); "AND EQPT_CD = :eqpt" }
        val sql = """
            SELECT WC_CD, EQPT_CD, LOT_NO, SERIAL_NO, COUNT_BIG(*) AS day_seq_cnt, MIN(SEQ) AS seq_min, MAX(SEQ) AS seq_max,
                   MIN(DATE_TIME) AS first_at, MAX(DATE_TIME) AS last_at
            FROM $tableByDate
            WHERE DATE_TIME >= :from AND DATE_TIME < :to AND LOT_NO IS NOT NULL AND SERIAL_NO IS NOT NULL $wcFilter $eqptFilter
            GROUP BY WC_CD, EQPT_CD, LOT_NO, SERIAL_NO
        """.trimIndent()
        return template().query(sql, params) { rs, _ ->
            DaySerial(
                SerialKey(rs.getString("WC_CD") ?: "", rs.getString("EQPT_CD"), rs.getString("LOT_NO"), rs.getString("SERIAL_NO")),
                rs.getLong("day_seq_cnt"), rs.getInt("seq_min"), rs.getInt("seq_max"),
                rs.getTimestamp("first_at")?.toLocalDateTime(), rs.getTimestamp("last_at")?.toLocalDateTime()
            )
        }
    }

    /** 시리얼 전체(날짜 무관) 통계 — 회차 수 · 불량 회차 수 · 지그 · 앞쪽 불량 회차 번호 */
    data class SerialStat(val key: SerialKey, val seqCnt: Long, val failSeqCnt: Long, val cavity: String?, val failSeqs: List<Int>)

    /** [serialStats] 한 번에 묶는 키 수 — VALUES 목록 하나에 파라미터 4개씩 */
    private val statsBatch = 40

    /**
     * 시리얼마다 **클러스터 PK 탐색 한 번**으로 회차 수·불량 회차 수·지그·앞쪽 불량 회차 번호(`topN`개)를 낸다.
     *
     * 한 시리얼은 2,000~3,800행이 PK 순으로 붙어 있어 탐색 하나가 수십 ms 다. 실측: 하루 144 시리얼 전부 3.5초(콜드), 웜 1초 안.
     * 합부는 **시리얼 전체**(날짜 무관)로 센다 — 자정을 넘은 시리얼의 앞 구간을 빼면 불량을 놓친다(실측 문서 A-4·A-5).
     */
    fun serialStats(keys: List<SerialKey>, topN: Int): List<SerialStat> {
        require(topN in 0..200) { "topN 은 0~200 이어야 한다" }
        return keys.chunked(statsBatch).flatMap { chunk ->
            val params = MapSqlParameterSource().addValue("topN", topN)
            val values = chunk.mapIndexed { i, k ->
                params.addValue("wc$i", k.wcCd).addValue("eq$i", k.eqptCd).addValue("lot$i", k.lotNo).addValue("sn$i", k.serialNo)
                "(:wc$i, :eq$i, :lot$i, :sn$i)"
            }.joinToString(", ")
            val sql = """
                SELECT v.wc, v.eq, v.lot, v.sn, f.seq_cnt, f.fail_seq_cnt, f.cavity, g.seqs
                FROM (VALUES $values) v(wc, eq, lot, sn)
                CROSS APPLY (
                    SELECT COUNT_BIG(*) AS seq_cnt, SUM(CASE WHEN d.PASSED = '0' THEN 1 ELSE 0 END) AS fail_seq_cnt,
                           MAX(CAST(d.COMMENT AS varchar(60))) AS cavity
                    FROM EDGE.dbo.TB_SAMSUN_DIMENSION d WITH (NOLOCK)
                    WHERE d.WC_CD = v.wc AND d.EQPT_CD = v.eq AND d.LOT_NO = v.lot AND d.SERIAL_NO = v.sn
                ) f
                CROSS APPLY (
                    SELECT STRING_AGG(CAST(t.SEQ AS varchar(10)), ',') WITHIN GROUP (ORDER BY t.SEQ) AS seqs
                    FROM (SELECT TOP (:topN) d.SEQ FROM EDGE.dbo.TB_SAMSUN_DIMENSION d WITH (NOLOCK)
                          WHERE d.WC_CD = v.wc AND d.EQPT_CD = v.eq AND d.LOT_NO = v.lot AND d.SERIAL_NO = v.sn AND d.PASSED = '0'
                          ORDER BY d.SEQ) t
                ) g
            """.trimIndent()
            template().query(sql, params) { rs, _ ->
                SerialStat(
                    SerialKey(rs.getString("wc"), rs.getString("eq"), rs.getString("lot"), rs.getString("sn")),
                    rs.getLong("seq_cnt"), rs.getLong("fail_seq_cnt"), rs.getString("cavity"),
                    rs.getString("seqs")?.split(',')?.filter { it.isNotBlank() }?.map { it.trim().toInt() } ?: emptyList()
                )
            }
        }
    }

    /** 측정 한 회차 — 값은 FAI1..FAI{faiCount} 순서, 없는 열은 null */
    data class SerialItem(val seq: Int, val passed: Boolean, val measuredAt: LocalDateTime?, val cavity: String?, val values: List<Double?>)

    /**
     * 시리얼 한 건의 회차 목록 한 쪽 — PK 탐색 + OFFSET/FETCH. 기본은 불량 회차만.
     * 실측: 불량 100회차 + FAI 전열 31ms.
     */
    fun serialItems(key: SerialKey, onlyNg: Boolean, faiCount: Int, limit: Int, offset: Int): List<SerialItem> {
        require(faiCount in 1..100) { "faiCount 는 1~100 이어야 한다" }
        val cols = (1..faiCount).joinToString(", ") { "FAI$it" }
        val sql = """
            SELECT SEQ, PASSED, DATE_TIME, CAST(COMMENT AS varchar(60)) AS cavity, $cols
            FROM $TABLE
            WHERE WC_CD = :wc AND EQPT_CD = :eq AND LOT_NO = :lot AND SERIAL_NO = :sn ${if (onlyNg) "AND PASSED = '0'" else ""}
            ORDER BY SEQ
            OFFSET :offset ROWS FETCH NEXT :limit ROWS ONLY
        """.trimIndent()
        val params = MapSqlParameterSource()
            .addValue("wc", key.wcCd).addValue("eq", key.eqptCd).addValue("lot", key.lotNo).addValue("sn", key.serialNo)
            .addValue("offset", offset).addValue("limit", limit)
        return template().query(sql, params) { rs, _ ->
            SerialItem(
                seq = rs.getInt("SEQ"), passed = rs.getString("PASSED") == "1",
                measuredAt = rs.getTimestamp("DATE_TIME")?.toLocalDateTime(), cavity = rs.getString("cavity"),
                values = (1..faiCount).map { n -> rs.getObject("FAI$n")?.let { (it as Number).toDouble() } }
            )
        }
    }

    /** 한계값 리터럴 — 지수 표기 없이 그대로 */
    private fun lit(v: Double): String = BigDecimal.valueOf(v).toPlainString()
}

/**
 * 리포지토리가 보는 원천 설정 — `app.aoi.mssql.date-index` 하나. 테스트에서 기본값으로 만들 수 있게 따로 둔다.
 */
@org.springframework.stereotype.Component
class AoiRepositoryProps(appProperties: com.dwje.api.config.AppProperties? = null) {
    val dateIndex: String? = appProperties?.aoi?.mssql?.dateIndex ?: "SAMSUN_DIMENSION_DATE_TIME"
}
