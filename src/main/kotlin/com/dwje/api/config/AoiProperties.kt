package com.dwje.api.config

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank

/**
 * AOI 치수 원천(MSSQL `EDGE.dbo.TB_SAMSUN_DIMENSION`) 직접 조회 설정 (`app.aoi.*`)
 *
 * ## 왜 조회 시점에 원천을 읽는가 (2026-09-13 현업 결정 13)
 * 원천이 실시간 IO 가 많은 표라 주기적으로 훑는 적재 배치가 장애를 만들 수 있다는 판단으로
 * 집계 테이블·배치를 만들지 않는다. 대신 `WITH (NOLOCK)` 으로 읽고, 조회 조건을 키로 결과를 잠시 보관하고,
 * 조회 기간에 상한을 둔다. 설계 근거는 `docs/AOI_DIMENSION_API_20260913.md`.
 *
 * ## 한계 세트는 설정이다 (결정 12)
 * 규격은 원천에 없고 PASSED 라벨의 포화에서 되찾은 값이다(`docs/AOI_DIMENSION_SPEC_BACKCALC_20260913.md`).
 * 수동 보정 화면을 아직 만들지 않아 테이블 대신 여기 [limits] 에 둔다. (작업장, 설비) 단위이며 설비 `*` 는
 * 그 작업장의 기본 세트다 — S110 은 GP-015 가 다른 규격을 쓴다.
 */
data class AoiProperties(

    /** MSSQL 원천 접속 — [Mssql] */
    @field:jakarta.validation.Valid
    val mssql: Mssql = Mssql(),

    /** AOI 외관 판정 원천(`TB_SAMSUN_COSMETIC`) 설정 — [Cosmetic] */
    @field:jakarta.validation.Valid
    val cosmetic: Cosmetic = Cosmetic(),

    /**
     * 조회 기간 상한(일). 실측으로 정한다 — `docs/AOI_DIMENSION_API_20260913.md` 2절.
     * 화면은 이 값을 넘는 기간을 고를 수 없게 막는다.
     */
    @field:Min(value = 1, message = "AOI 조회 기간 상한(app.aoi.max-days)은 1 이상이어야 합니다.")
    @field:Max(value = 31, message = "AOI 조회 기간 상한(app.aoi.max-days)은 31 이하로 두세요 — 원천 부담이 기간에 비례합니다.")
    val maxDays: Int = 7,

    /** 지난 기간 결과의 보관 시간(초). 지난 날짜는 바뀌지 않으므로 길게 둔다. */
    @field:Min(value = 0, message = "캐시 보관 시간(app.aoi.cache-ttl-sec)은 0 이상이어야 합니다.")
    val cacheTtlSec: Long = 1800,

    /** 오늘이 들어간 기간의 보관 시간(초). 현업 요구 최신성이 30분~1시간이다. */
    @field:Min(value = 0, message = "당일 캐시 보관 시간(app.aoi.today-cache-ttl-sec)은 0 이상이어야 합니다.")
    val todayCacheTtlSec: Long = 600,

    /** 보관하는 조회 결과 수 상한. 넘으면 오래된 것부터 버린다. */
    @field:Min(value = 1, message = "캐시 항목 수(app.aoi.cache-max-entries)는 1 이상이어야 합니다.")
    val cacheMaxEntries: Int = 200,

    /**
     * 측정 실패 판정 — 사용 FAI 중 0 이 아닌 값이 이 개수 이하면 그 행은 '측정 실패' 다.
     * 실측: 실패 행은 FAI 가 전부 0.0 이거나 한두 개만 값이 있다.
     */
    @field:Min(value = 0, message = "측정 실패 판정 개수(app.aoi.zero-row-nonzero-max)는 0 이상이어야 합니다.")
    val zeroRowNonzeroMax: Int = 5,

    /** 브리핑 문장에 올리는 FAI 상위 개수 */
    @field:Min(value = 1, message = "브리핑 FAI 상위 개수(app.aoi.briefing-top-fai)는 1 이상이어야 합니다.")
    val briefingTopFai: Int = 5,

    /** (작업장, 설비)별 확정 한계 세트 — [LimitSet] */
    @field:jakarta.validation.Valid
    val limits: List<LimitSet> = emptyList()
) {

    /**
     * MSSQL 접속.
     *
     * 계정은 저장소에 두지 않는다(실측 문서 요구) — `AX_MSSQL_URL` · `AX_MSSQL_USER` · `AX_MSSQL_PASSWORD` 로 주입한다.
     * 계정이 비어 있으면 [enabled] 를 꺼 두는 것과 같이 동작한다(`SOURCE_NOT_CONFIGURED`).
     *
     * @param poolSize        동시 접속 상한. 원천 부담을 이 수로 막는다 — 조회 하나가 설비 수만큼 쿼리를 내므로 3~4 면 충분하다
     * @param queryTimeoutSec 쿼리 하나의 제한 시간. 실측 하루치 43초 — 기간 상한과 함께 정한다
     * @param parallelism     한 조회 안에서 동시에 던지는 쿼리 수. [poolSize] 를 넘지 않는다
     */
    data class Mssql(
        val enabled: Boolean = true,

        @field:NotBlank(message = "AOI 원천 JDBC URL(app.aoi.mssql.url)은 비워 둘 수 없습니다.")
        val url: String = "jdbc:sqlserver://192.168.7.203:1433;databaseName=EDGE;encrypt=true;trustServerCertificate=true;sendStringParametersAsUnicode=false",

        val username: String = "",

        val password: String = "",

        @field:Min(value = 1, message = "AOI 원천 접속 수(app.aoi.mssql.pool-size)는 1 이상이어야 합니다.")
        @field:Max(value = 10, message = "AOI 원천 접속 수(app.aoi.mssql.pool-size)는 10 이하로 두세요.")
        val poolSize: Int = 3,

        @field:Min(value = 5, message = "AOI 원천 쿼리 제한 시간(app.aoi.mssql.query-timeout-sec)은 5초 이상이어야 합니다.")
        val queryTimeoutSec: Int = 180,

        @field:Min(value = 1, message = "AOI 원천 동시 쿼리 수(app.aoi.mssql.parallelism)는 1 이상이어야 합니다.")
        val parallelism: Int = 3,

        /**
         * 기간 조건 질의에 고정할 `DATE_TIME` 인덱스 이름. 비우면 힌트를 붙이지 않는다.
         *
         * 문자열 파라미터를 varchar 로 보내자 옵티마이저가 (WC_CD, EQPT_CD) 클러스터 범위(전 기간 수백만 행)를 택해
         * 하루 집계가 6.7초 → 26.5초가 됐다(실측). 기간이 선택적인 질의는 이 인덱스가 늘 맞다.
         * 커버링 인덱스로 교체할 때 `DROP_EXISTING` 으로 이름을 유지하면 그대로 쓴다.
         */
        val dateIndex: String = "SAMSUN_DIMENSION_DATE_TIME"
    ) {
        val configured: Boolean get() = enabled && username.isNotBlank()
    }

    /**
     * AOI 외관 판정 원천 `EDGE.dbo.TB_SAMSUN_COSMETIC` (2026-09-14 발주자 지시 — AOI 는 이 표만 쓴다).
     *
     * ## 치수(DIMENSION)와 무엇이 다른가
     * 설비가 겹치지 않는다 — 외관은 `MN-*`(공정 S135 레이저 · S138/W160 PACKING), 치수는 `GP-*`·`MQ-*`(S110 도금 · S120 도장).
     * 한 행이 **(제품 1개) × (검사 항목 1개)** 라 치수의 `FAI1~FAI100` 가로 전개를 세로로 편 모양이고,
     * 판정이 둘이다 — `PASSED`(항목) 와 `FINAL_PASSED`(제품, 한 `SEQ` 안에서 항상 하나. 전수 확인).
     * 규격 역산이 필요 없다: 불량 여부를 원천이 직접 말해 준다.
     *
     * ## 기간 상한을 따로 두는 이유
     * 행수가 3,022만으로 치수(696만)의 **4.3배**다. 하루치 집계 실측이 17~52초라 치수의 7일을 그대로 쓰면 제한 시간에 걸린다.
     * 실측 근거는 `docs/AOI_COSMETIC_SOURCE_SURVEY_20260914.md` 8절.
     *
     * @param maxDays   조회 기간 상한(일). 치수보다 짧다
     * @param dateIndex 기간 조건 질의에 고정할 `DATE_TIME` 인덱스 이름. 비우면 힌트를 붙이지 않는다
     */
    data class Cosmetic(
        val enabled: Boolean = true,

        @field:Min(value = 1, message = "AOI 외관 조회 기간 상한(app.aoi.cosmetic.max-days)은 1 이상이어야 합니다.")
        @field:Max(value = 31, message = "AOI 외관 조회 기간 상한(app.aoi.cosmetic.max-days)은 31 이하로 두세요.")
        val maxDays: Int = 3,

        val dateIndex: String = "SAMSUN_COSMETIC_DATE_TIME",

        /**
         * 항목 코드 → 한글 이름. 원천에 이름이 없는 `DF003` 류를 화면 문장에 쓰기 위한 자리다.
         * 발주자가 이름을 주기 전까지 비어 있고, 비면 화면은 코드를 그대로 쓴다(2026-09-14 확인).
         */
        val itemNames: Map<String, String> = emptyMap(),

        /**
         * **합부를 정하지 않는 항목** — 이 항목의 `PASSED = 0` 은 불량이 아니라 「값이 없음」이다.
         *
         * `DF009` 가 그렇다(2026-09-14 실측). 09-12 하루 전수로:
         * - `DF009` **하나만** `PASSED = 0` 인 제품 50,976개 중 최종 불량 **0개(0.00%)**
         * - 다른 항목(`DF004`·`DF021`·`DF003`·`DF014`·`DF007`·`DF006`)은 그 항목만 불량이면 **100% 최종 불량**
         * - `DF009` 는 `PASSED = 0` 일 때 값이 항상 `0.0`, `PASSED = 1` 일 때만 `1.0~6.0`
         *
         * 이 항목을 불량으로 세면 S135 불량률이 99.5% 로 뜨고, 늘 끼어들어 귀속이 전부 '복합'이 된다.
         * 그래서 귀속·설명률 계산에서 뺀다. 다만 항목 목록에서 지우지는 않는다 — 측정 자체는 의미가 있어
         * `verdict: false` 로 표시해 내보낸다.
         */
        val nonVerdictItems: List<String> = listOf("DF009")
    )

    /**
     * (작업장, 설비) 한 세트.
     *
     * @param wcCd       작업장 코드
     * @param eqptCd     설비 코드. `*` 면 그 작업장의 기본 세트 — 설비 지정 세트가 있으면 그것이 이긴다
     * @param resolution 측정 분해능 — 포화 판정 단계이자 문장에 적는 자릿수(S120 0.001 · S110 0.0001)
     * @param faiCount   사용 FAI 개수 — 측정 실패 판정에서 0 이 아닌 값을 세는 범위(S120 58 · S110 45)
     * @param usl        FAI 번호 → 확정 상한
     * @param lsl        FAI 번호 → 확정 하한
     * @param basis      어떤 표본으로 확정했는지 — 응답과 문서에 그대로 내려 근거를 밝힌다
     */
    data class LimitSet(
        @field:NotBlank(message = "한계 세트의 작업장 코드(app.aoi.limits[].wc-cd)는 비워 둘 수 없습니다.")
        val wcCd: String = "",

        val eqptCd: String = "*",

        @field:jakarta.validation.constraints.DecimalMin(
            value = "0.00001", message = "분해능(app.aoi.limits[].resolution)은 0 보다 커야 합니다."
        )
        val resolution: Double = 0.001,

        @field:Min(value = 1, message = "사용 FAI 개수(app.aoi.limits[].fai-count)는 1 이상이어야 합니다.")
        @field:Max(value = 100, message = "사용 FAI 개수(app.aoi.limits[].fai-count)는 100 이하입니다.")
        val faiCount: Int = 58,

        val usl: Map<Int, Double> = emptyMap(),

        val lsl: Map<Int, Double> = emptyMap(),

        val basis: String = ""
    ) {
        /** 한계가 하나라도 붙은 FAI 번호(오름차순) */
        val faiNumbers: List<Int> get() = (usl.keys + lsl.keys).toSortedSet().toList()

        val isDefault: Boolean get() = eqptCd == "*"
    }
}
