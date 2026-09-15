# AOI 치수 집계·한계 세트·AI 브리핑 API — MSSQL 직접 조회 (2026-09-13, 5차)

QC-02 AOI 화면 AI 브리핑의 백엔드. 기획은 WEB `docs/requests/REQ_20260913_aoi_ai_briefing.md`(결정 13개), 조사는
`AOI_DIMENSION_SPEC_BACKCALC_20260913.md`(S120) · `AOI_DIMENSION_S110_AND_DAILY_DESIGN_20260913.md`(S110 · 접힌 요약 테이블 설계).

## 0. 결론

| 항목 | 결과 |
| :--- | :--- |
| 원천 | `EDGE.dbo.TB_SAMSUN_DIMENSION` 을 **조회 시점에 직접** `WITH (NOLOCK)` 으로 읽는다. 적재 배치·집계 테이블 없음(결정 13) |
| 조회 기간 상한 | **7일** (`app.aoi.max-days`). 7일이 설비 전체 26~36초(서버 캐시 따뜻할 때), 14일은 쿼리 제한 180초를 넘겨 실패 |
| 응답 시간 | 캐시 미적중: S120 하루 10초 · 3일 14초 · 7일 26초, S110 하루 14초 · 7일 36초(직전 기간 포함). 캐시 적중 0초. **1~2초 요건은 첫 조회에서는 못 맞춘다** — 캐시 뒤에만 맞는다 |
| 원천 부담 | 조회 하나 = 설비 목록 1회(인덱스만) + 설비마다 기간 행 **한 번** 룩업. 직전 기간까지 두 번. 동시 접속 상한 3(풀 크기) |
| 캐시 | 부담 없다고 본다 — 결과 한 건이 수십 KB, 조건 키 200개 상한, 지난 기간 30분·오늘 10분 보관, 같은 조건 동시 요청은 한 번만 읽는다 |
| 한계 세트 | 설정 `app.aoi.limits`(결정 12). S120 공통 세트, S110 기본 세트 + GP-015 전용 세트 |
| 브리핑 | 숫자는 서버(`ruleLines`·`facts`), 문장은 sLLM(`aiLines`, 근거 대조 통과분만), 조치는 「(추정)」(`actions`). 실측 1건 95초(모델 80초) |

## 1. 계약

기본 경로 `/api/v1/quality/aoi/dimension`, 메뉴 권한 `qc-aoi`. 마스킹: `qty` 없음 → 건수 전부 null, `yield` 없음 → 비율 전부 null.

### 1-1. `GET /summary` — 집계 조회 (기획 API 1)

| 파라미터 | 뜻 |
| :--- | :--- |
| `from` / `to` | YYYY-MM-DD, 종료일 포함. 비우면 **오늘 하루**. 기간이 `maxDays` 를 넘으면 400 `E-VALID-001` (`field: to`) |
| `wcCd` | **필수** (S110 · S120). 비우면 400 (`field: wcCd`) — 한계 세트가 작업장·설비 단위이고 두 작업장을 한 번에 훑으면 부담이 두 배 |
| `eqptCd` | 선택. 비우거나 `전체` 면 작업장 전체(설비별 블록 포함) |

```json
{"data":{
  "period":{"from":"2026-09-11","to":"2026-09-11","days":1}, "previousPeriod":{"from":"2026-09-10","to":"2026-09-10","days":1},
  "wcCd":"S120","eqptCd":null,"maxDays":7,
  "current":{
    "period":{…},
    "total":{"eqptCd":null,"limitBasis":"MQ-008 2026-09-09~11 · …","resolution":0.001,
             "measCnt":210328,"passCnt":171663,"failCnt":38665,"serialCnt":…,"failRate":18.38,
             "attribution":{"single":30021,"multi":2776,"unconfirmed":5849,"zero":19},
             "explainedCnt":32797,"explainedRate":84.82,"topFai":1,
             "fais":[{"fai":1,"usl":0.04,"lsl":null,"violCnt":14466,"violSingleCnt":…,"overCnt":14466,"underCnt":0,
                      "exceedSum":…,"exceedAvg":0.0092,"exceedAvgSingle":0.0091,"exceedMax":…,"sharePct":43.56,"violPct":37.41}, …],
             "firstAt":"2026-09-11 00:00:02","lastAt":"2026-09-11 23:59:58"},
    "equipments":[ {같은 모양, "eqptCd":"MQ-008", "limitBasis":…}, … ],
    "elapsedMs":6682,"queryCnt":7},
  "previous":{ 같은 모양 } | null,
  "delta":{"measCnt":-11145,"failCnt":9210,"failRatePt":5.08,"explainedRatePt":-0.15,"zeroCnt":15,"topFaiChanged":true,"prevTopFai":10},
  "limitSets":[{"wcCd":"S120","eqptCd":"*","resolution":0.001,"faiCount":58,"basis":"…","limits":[{"fai":1,"usl":0.04,"lsl":null},…],"appliesTo":["MQ-002","MQ-003",…]}],
  "fromCache":false,"cachedAt":"2026-09-13 15:34:52","elapsedMs":10622,"sourceQueryCnt":13
}}
```

| 필드 | 뜻 |
| :--- | :--- |
| `attribution` | 불량 행 귀속 — `single` 확정 한계 위반 1개 · `multi` 2개 이상 · `unconfirmed` 0개(미확정 항목 이탈) · `zero` 측정 실패(사용 FAI 중 0 이 아닌 값 ≤ 5개, **먼저** 판정) |
| `explainedRate` | (single + multi) ÷ failCnt × 100. 낮아도 그대로 낸다(결정 11) |
| `fais[]` | 한계가 있고 위반이 1건 이상인 FAI 만, `violSingleCnt` 내림차순. `sharePct` = 단일 귀속 행 중 비중("불량의 88% 가 FAI23"), `violPct` = 전체 불량 중 이 FAI 위반 행 비중 |
| `exceedAvg` / `exceedAvgSingle` | 위반 행 전체 평균 초과량 / **단일 귀속 행만**의 평균. 전항목이 어긋난 쓰레기 측정이 전체 평균을 끌어올리므로(3일 FAI11 평균 2.46) 문장은 `Single` 을 쓴다. 단위는 측정값 단위, 자릿수는 `resolution` |
| `total.usl/lsl` | 설비끼리 한계가 같을 때만 값, 다르면 null(S110 GP-015). 설비 블록에는 늘 있다 |
| `delta` | 현재 − 직전 동일 기간. `failRatePt` `explainedRatePt` 는 %p |
| `fromCache` `cachedAt` `sourceQueryCnt` | 이번 응답이 보관값인지, 언제 읽은 것인지, 원천에 실제로 나간 쿼리 수 |
| `reason: SOURCE_NOT_CONFIGURED` | `AX_MSSQL_USER` 미주입. 200 으로 빈 응답 |
| 503 `E-SOURCE-001` / 504 `E-SOURCE-002` | 원천 조회 실패 / 제한 시간(180초) 초과 — 기간을 줄이라는 문구가 `message` 에 있다 |

### 1-2. `GET /limits?wcCd&eqptCd` — 한계 세트 (기획 API 2)

`items[]` 설정의 세트 목록(작업장·설비로 걸러서), `resolved` 그 설비에 실제 적용되는 세트(설비 지정 > 작업장 `*`). 값은 설정 그대로다.

### 1-3. `POST /briefing` — 브리핑 (기획 API 3)

본문 `AoiBriefingRequest {from, to, wcCd(필수), eqptCd}` — 다른 키는 400. 집계는 `/summary` 와 같은 캐시 키라 **화면이 표를 먼저 그린 뒤 부르면 원천을 다시 읽지 않는다.**

```json
{"data":{
  "status":"OK" | null, "reason": null | "MODEL_NOT_READY" | "MODEL_BUSY",
  "period":{…},"wcCd":"S120","eqptCd":"MQ-008",
  "ruleLines":[ "2026-09-11 MQ-008 측정 59,328건 중 불량 11,527건(불량률 19.43%), 설명률 98.41%.",
                "원인 항목: FAI10 상한 22.880 초과 5,164건(단일 귀속 44.68%), 평균 +0.009, FAI1 상한 0.040 초과 4,971건(단일 귀속 42.63%), 평균 +0.009, …",
                "귀속: 단일 항목 10,174건 · 복합 1,170건 · 미확정 항목 이탈 180건 · 측정 실패 3건.",
                "직전 동일 기간(2026-09-10~2026-09-10) 불량률 11.91% 대비 +7.52%p, 1위 원인이 FAI1에서 FAI10로 바뀜." ],
  "facts":[{"key":"failCnt","value":11527},{"key":"FAI10.sharePct","value":44.68},…],
  "aiLines":[{"text":"불량 중 FAI10이 상한 초과로 5,164건(44.68%)을 차지하며 …","evidence":[{"key":"FAI10.violCnt","value":5164},{"key":"FAI10.sharePct","value":44.68}]},…],
  "actions":[{"text":"(추정) FAI10에 대해 측정 공정의 상한 기준값(22.88)을 재점검하고, 측정 장비의 교정 상태를 점검해야 합니다.","fai":10,"estimate":true},…],
  "droppedCnt":0,"generatedAt":"2026-09-13 15:37:58","modelVer":"gemma4:e4b-dwje"
}}
```

- `ruleLines` 는 **항상** 나간다(모델이 없어도). 숫자는 전부 여기와 `facts` 에서 나온다. FAI 이름은 없고 번호와 한계값만(결정 9).
- `aiLines` 는 문장마다 `evidence` 의 key 를 `facts` 와 대조해 **키가 없거나 값이 다르면(상대 1%·절대 0.01 초과) 버린다** → `droppedCnt`.
  `facts` 키 규칙: `measCnt` `failCnt` `failRate` `explainedRate` `singleCnt` `multiCnt` `unconfirmedCnt` `zeroCnt`,
  `FAIn.usl` `FAIn.lsl` `FAIn.violCnt` `FAIn.violSingleCnt` `FAIn.overCnt` `FAIn.underCnt` `FAIn.exceedAvg`(단일 귀속 평균) `FAIn.sharePct` `FAIn.violPct`(상위 5개),
  `prev.failCnt` `prev.failRate` `prev.explainedRate` `delta.failRatePt`.
- `actions` 는 상위 FAI 를 겨누는 것만 남기고 모두 「(추정)」 을 붙인다(결정 5). 설명률은 낮아도 첫 줄에 그대로 적는다(결정 11).
- 모델 호출은 서버가 한 줄로 세운다(`SllmClient` 세마포어). 바쁘면 `MODEL_BUSY`, 꺼져 있거나 실패·잘림이면 `MODEL_NOT_READY`.
  실측 1건 95초(모델 약 80초) — **화면은 버튼 또는 비동기로** 부르고, 표는 `/summary` 로 먼저 그린다.

## 2. 조회 기간 상한 — 실측

원격(사내망 밖 개발 PC → 192.168.7.203), 풀 3 · 동시 쿼리 3, 직전 동일 기간 포함.

**첫 구현(건수 질의 + 불량 분해 질의, 같은 행을 두 번 룩업) — 서버 캐시 콜드**

| 조회 | 설비 | 현재 기간 | 직전 기간 | 합계 |
| :--- | ---: | ---: | ---: | ---: |
| S120 1일 | 6 | 67초 | 19초 | 86초 |
| S120 3일 | 7 | 64초 | 39초 | 103초 |
| S120 7일 | 7 | 108초 | 149초 | **257초** |
| S110 1일 | 6 | 54초 | 16초 | 70초 |
| S110 7일 | 6 | 79초 | 126초 | 205초 |
| S120 14일 | 7 | — | — | **실패 (쿼리 제한 180초 초과, 504)** |

**단일 패스(설비 목록은 인덱스만, 설비마다 기간 행을 임시 테이블에 한 번 내려 건수·분해를 함께) — 같은 날짜 재측정(서버 캐시 따뜻함)**

| 조회 | 현재 기간 | 직전 기간 | 합계 | 읽은 행 |
| :--- | ---: | ---: | ---: | ---: |
| S120 1일 | 6.7초 | 3.9초 | **10초** | 21만 + 22만 |
| S120 3일 | 8.8초 | 5.2초 | 14초 | 68만 |
| S120 7일 | 11.8초 | 14.3초 | **26초** | 121만 + 직전 |
| S110 1일 | 11.4초 | 3.0초 | 14초 | 26만 |
| S110 7일 | 10.9초 | 24.7초 | **36초** | 128만 + 직전 |
| 캐시 적중 | — | — | **0.0초** | 0 |

두 표는 조건이 다르다(콜드 대 웜). 단일 패스의 콜드 값은 전 볼륨의 안 읽은 주간이 남아 있지 않아 못 재었다 —
구조상 룩업 횟수가 절반이므로 콜드도 첫 표의 절반 안팎으로 본다(1일 40초대, 7일 2분 안팎). 상한 결정은 그 보수적 가정으로 했다.

**결정 — `max-days: 7`.** 7일이 웜 26~36초·콜드 2분 안팎으로 "기다릴 수 있는 첫 조회" 의 끝이고, 14일은 제한 시간을 넘긴다.
화면은 7일을 넘는 기간을 고를 수 없게 막는다(응답 `maxDays` 로도 내린다). 원천 부담은 조회 하나에 **설비 수 × 기간 행 룩업 1회**(직전 기간 포함 2회)이고,
동시 접속은 풀 3 이 상한이다. 하루 210k 행을 룩업하는 질의가 설비별로 6개 도는 셈이며, 커버링 인덱스(`DATE_TIME` INCLUDE `PASSED`)가 붙으면 건수 쪽 룩업이 사라져 절반 이하가 된다.

**1~2초 요건에 대한 답** — 캐시 뒤에서만 맞는다. 첫 조회는 10~36초(웜)다. 화면은 표를 먼저 그리고(`/summary` 도 비동기), 브리핑은 그 뒤에 부르는 흐름이 맞다.

## 3. 캐시 — 부담이 아니라고 본 근거

- 보관 대상은 **집계 결과 한 건**(수십 KB)이지 원천 행이 아니다. 키는 (from, to, wcCd, eqptCd), 상한 200건(오래된 것부터 버림).
- 지난 기간 30분, 오늘이 든 기간 10분 — 현업 요구 최신성 30분~1시간 안이다. 원천 IO 는 사용자 조회 수에만 비례한다.
- 같은 조건이 동시에 오면 한 번만 읽는다(single-flight). 브리핑은 `/summary` 와 같은 키를 쓴다.
- 인메모리라 API 재기동 때 비워진다. 그것으로 충분하다 — 적재가 아니다.

캐시까지 부담이라 판단되면 `cache-ttl-sec: 0` 으로 끄면 된다(그때는 브리핑 호출이 원천을 다시 읽는다).

## 4. 설정 (`app.aoi.*`)

| 키 | 기본 | 뜻 |
| :--- | :--- | :--- |
| `mssql.url` / `username` / `password` | `AX_MSSQL_URL` / `AX_MSSQL_USER` / `AX_MSSQL_PASSWORD` | 계정은 환경변수로만. 비면 `SOURCE_NOT_CONFIGURED` (기동은 된다) |
| `mssql.pool-size` / `parallelism` | 3 / 3 | 원천 동시 접속 상한 = 조회 하나의 동시 쿼리 수 |
| `mssql.query-timeout-sec` | 180 | 넘으면 504 `E-SOURCE-002` |
| `max-days` | 7 | 조회 기간 상한 |
| `cache-ttl-sec` / `today-cache-ttl-sec` / `cache-max-entries` | 1800 / 600 / 200 | 결과 보관 |
| `zero-row-nonzero-max` | 5 | 측정 실패 판정 — 사용 FAI 중 0 이 아닌 값이 이 수 이하 |
| `briefing-top-fai` | 5 | 브리핑에 올리는 FAI 수 |
| `limits[]` | S120 `*` · S110 `*` · S110 GP-015 | `wc-cd` `eqpt-cd`(`*` 기본) `resolution` `fai-count` `usl{"n":v}` `lsl{"n":v}` `basis`. **맵 키는 문자열로 감싼다**(YAML 정수 키를 Spring 이 못 읽는다) |

한계 세트 값과 근거는 `application.yml` 주석과 조사 문서에 있다. GP-015 세트는 하루 표본 포화값이라 재확정 대상으로 표시했다.
보조 데이터소스는 `AoiMssqlConfig` 가 HikariCP 로 직접 만들고 `aoiJdbcTemplate` 만 빈으로 낸다(기본 PostgreSQL 자동 구성을 건드리지 않기 위해 `DataSource` 빈으로 내지 않는다). 드라이버 `mssql-jdbc` 는 Spring Boot BOM 버전.

## 5. 집계 SQL 요지

```sql
-- 설비 목록 (DATE_TIME 인덱스만 — 클러스터 키가 따라붙어 룩업 없음)
SELECT DISTINCT EQPT_CD FROM EDGE.dbo.TB_SAMSUN_DIMENSION WITH (NOLOCK) WHERE WC_CD=:wc AND DATE_TIME>=:from AND DATE_TIME<:to;

-- 설비 하나 단일 패스: 필요한 열만 임시 테이블에 한 번 내리고, 건수(전체)와 분해(불량 행만 CROSS APPLY)를 같은 결과 집합으로
SET NOCOUNT ON;
SELECT PASSED, LOT_NO, SERIAL_NO, DATE_TIME,
       CASE WHEN PASSED='0' AND (CASE WHEN FAI1<>0 THEN 1 ELSE 0 END + … ) <= 5 THEN 1 ELSE 0 END AS z,     -- 측정 실패
       CASE WHEN PASSED='0' THEN (CASE WHEN FAI1 > 0.04 THEN 1 ELSE 0 END + …) ELSE 0 END AS viol,         -- 확정 한계 위반 개수
       FAI1, FAI10, …                                                                                       -- 한계 있는 FAI 만
INTO #a FROM EDGE.dbo.TB_SAMSUN_DIMENSION WITH (NOLOCK)
WHERE WC_CD=:wc AND EQPT_CD=:eqpt AND DATE_TIME>=:from AND DATE_TIME<:to;
SELECT x.k, (SELECT COUNT_BIG(*) FROM #a) meas_cnt, …, SUM(z) zero_cnt, SUM(CASE WHEN z=0 AND viol=1 …) single_rows, …,
       SUM(CASE WHEN z=0 AND (x.v>x.usl OR x.v<x.lsl) …) viol_cnt, … exceed_sum, exceed_sum_single, exceed_max
FROM #a f CROSS APPLY (VALUES ('__ROW__',NULL,NULL,NULL), ('FAI1',FAI1,0.04,NULL), ('FAI10',FAI10,22.88,NULL), …) x(k,v,usl,lsl)
WHERE f.PASSED='0' GROUP BY x.k;
DROP TABLE #a;
```
한계값은 설정 숫자 리터럴, 열 이름은 1~100 정수로만 만든다. 사용자 입력이 SQL 문자열에 들어가는 자리는 없다.

## 6. 바뀐 파일

- `config/AoiProperties.kt` · `config/AoiMssqlConfig.kt` · `config/AppProperties.kt`(aoi) · `config/DatabaseConfig.kt`(`@Primary`)
- `repository/AoiDimensionRepository.kt` — 설비 목록 · 단일 패스 집계(SQL 빌더 공개, 테스트)
- `service/AoiDimensionService.kt` — 기간 검증 · 한계 세트 결정 · 캐시(single-flight) · 병합 · 마스킹 · `AoiSourceException`
- `service/AoiBriefingService.kt` — 사실 목록 · 규칙 문장 · sLLM 스키마·지시문 · 근거 대조 · 「(추정)」
- `controller/AoiDimensionController.kt` — `/summary` `/limits` `/briefing`
- `model/request/QualityRequests.kt`(`AoiBriefingRequest`) · `common/response/ErrorCode.kt`(`E-SOURCE-001/002`)
- `application.yml`(`app.aoi`) · `build.gradle.kts`(mssql-jdbc) · `README.md`(환경변수) · `docs/REQUEST_BODY_CONTRACT.md`(72개)
- 테스트 `AoiDimensionTest`(8) — SQL 빌더 · 세트 결정 · 기간 · 병합 · 마스킹 · 사실 · 규칙 문장 · 근거 대조

## 7. 남은 것

- 커버링 인덱스(`DATE_TIME` INCLUDE `PASSED`)는 여전히 덕우전자 전산 요청 사안. 붙으면 첫 조회가 절반 이하로 준다.
- 한계 재확정은 배치가 아니라 수동 조사(3·4차 스크립트). 7일 표본으로 `pass_at ≥ 20`·GP-015 세트를 재확정한 뒤 yml 을 고친다.
- 측정 실패 판정(0 이 아닌 FAI ≤ 5)은 전부 0.0 인 행만 잡는다. FAI 일부만 0 인 부분 실패 행(예 MQ-008 09-11 FAI2=0 인 35행 중 32행)은 `unconfirmed` 나 `multi` 로 간다.
- 콜드 캐시 단일 패스 실측은 새 주간 데이터가 쌓이면 한 번 재어 상한을 다시 본다.

---

## 8. (6차) 시리얼 목록·상세 — `GET /serials` · `GET /serials/{serialKey}`

**요청** — MES 목록(`/quality/aoi/defects`)에 불량 회차 수(`failSeqCnt`)와 앞쪽 회차 번호(`failSeqs`)를 붙여 달라.
**확인한 것** — 그 목록은 MES 라벨 이력이고 AOI 설비(PACKING-AOI-*)가 DIMENSION 설비(GP-*·MQ-*)와 **다른 집합**이다(9/11 하루치 0건). 라벨에는 회차(SEQ)가 없어 붙일 자리가 없다.
화면이 이미 읽는 DIMENSION 모양(`seqCnt·failSeqCnt·items[{seq,passed}]`, 실측 문서 C-1·C-2)은 mock 이었다. 그래서 그 계약을 서버에 만들었다. MES 목록은 그대로다.

### 8-1. `GET /api/v1/quality/aoi/dimension/serials`

| 파라미터 | 뜻 |
| :--- | :--- |
| `date` | 하루(YYYY-MM-DD, 기본 오늘). 주면 from/to 를 무시한다 — 화면은 `date`·`from`·`to` 를 같은 날로 함께 보낸다 |
| `from` / `to` | 기간(상한 `app.aoi.max-days`) |
| `wcCd` / `eqptCd` | 선택 |
| `sort` / `desc` | `failSeqCnt`(기본, 내림차순) · `failRate` · `lastAt` · `firstAt` · `serialNo` · `seqCnt` |
| `failSeqsTop` | 행마다 싣는 불량 회차 번호 수(0~100, 기본 20) |
| `page` / `size` | 기본 10 · `size=0` 전량 |

행: `serialKey`(`wc~eqpt~lot~serial`) · `wcCd` `eqptCd` `lotNo` `serialNo` · **`seqCnt` `failSeqCnt` `failRate` `passed`(시리얼 전체 기준 — 자정을 넘은 앞 구간 포함)** ·
`daySeqCnt` `seqMin` `seqMax` `partial`(조회 기간 안 구간, `seqMin>1` 이면 앞 구간은 전날) · `firstAt` `lastAt` · `cavity`(COMMENT) · `faiUsed`(세트의 FAI 수) ·
**`failSeqs`**(앞쪽 불량 회차 번호) · **`failSeqsTruncated`**(`failSeqCnt > failSeqs.size`). 화면은 「5, 12~14 외 N회차」 로 접는다.
마스킹: `qty` 없음 → `seqCnt` `failSeqCnt` `daySeqCnt` null, `yield` 없음 → `failRate` null. 회차 번호는 수량이 아니라 남긴다.

### 8-2. `GET /api/v1/quality/aoi/dimension/serials/{serialKey}?only=ng|all&page&size`

머리(`seqCnt` `failSeqCnt` `failRate` `passed` `cavity` `failSeqs`) + `faiNos`(이 쪽에서 값이 있는 FAI 번호) + **`spec`**(확정 상·하한 `{no, lower, upper}` — 실측 문서 C-3 의 요청. 없는 FAI 는 한계 없음) + `limitBasis` `resolution` +
`items[{seq, passed, measuredAt, cavity, measurements[{no,value}], violFais[]}]`(기본 불량 회차만 100개, `meta.total` 은 불량 회차 수 또는 전 회차 수).
`violFais` 는 확정 한계를 벗어난 FAI 번호 — 화면이 값을 붉게 칠 수 있다. 잘못된 키 400, 없는 시리얼 404.

### 8-3. 비용 — (1) 과 (2) 모두 싸다

| 단계 | 방법 | 실측 |
| :--- | :--- | ---: |
| 시리얼 목록 | `DATE_TIME` 인덱스만(클러스터 키가 따라붙어 룩업 없음) | 하루 전 사업장 49만 행 → 146 시리얼 0.5초 |
| 불량 회차 수·지그·앞 20개 번호 | 시리얼마다 클러스터 PK 탐색 1회(40개씩 묶어 병렬) | 144 시리얼 3.5초(콜드) · 1초 안(웜) |
| 상세 한 쪽 | PK 탐색 + OFFSET/FETCH | 0.2초 |

API 실측(웜): 하루 전 사업장 1.9초 · S120 하루 1.1초 · S110 7일(363 시리얼) 11.6초 · 상세 0.2초. 캐시 뒤 0초. MES 목록 속도는 그대로(0.03~0.08초).

### 8-4. 함께 고친 것 — JDBC 문자열 파라미터

첫 실측에서 같은 질의가 sqlcmd 4초 → API **122초**였다. JDBC 가 문자열을 nvarchar 로 보내 varchar 키 열(EQPT_CD·LOT_NO·SERIAL_NO)에 암시적 변환이 걸려 PK 탐색이 스캔이 된 것이다.
`sendStringParametersAsUnicode=false` 를 URL 에 두고(없으면 `AoiMssqlConfig` 가 붙인다), 그 여파로 집계 쿼리가 (WC_CD, EQPT_CD) 클러스터 범위를 타 6.7초 → 26.5초가 되자
기간 조건 질의에 `INDEX(SAMSUN_DIMENSION_DATE_TIME)` 힌트를 고정했다(`app.aoi.mssql.date-index`, 비우면 힌트 없음). 집계는 7.8초로 돌아왔다.
커버링 인덱스로 바꿀 때 `DROP_EXISTING` 으로 이름을 유지하면 힌트가 그대로 맞는다.

바뀐 파일: `repository/AoiDimensionRepository.kt`(`findDaySerials` `serialStats` `serialItems` `SerialKey`, 인덱스 힌트, `AoiRepositoryProps`) · `service/AoiSerialService.kt` · `controller/AoiDimensionController.kt` · `config/AoiMssqlConfig.kt`(varchar 플래그) · `config/AoiProperties.kt`(`date-index`) · 테스트 `AoiSerialTest`(6).

---

## 9. (2026-09-14) 발주자 확정 — 컬럼 뜻과 그 여파

발주자가 원천 컬럼의 뜻을 확정해 주었다. **`WC_CD` 가 '직전 공정' 이라는 점이 그동안의 읽기와 다르다.**

| 컬럼 | 발주자 정의 | 확인 |
| :--- | :--- | :--- |
| `WC_CD` | **이전(직전) 작업장 코드** — 도금 · 도장 · 레이저 … | 원천 실측값이 MES `mes.tb_md_workcenter` 와 그대로 맞음(아래) |
| `EQPT_CD` | 설비 코드 | 측정을 수행한 AOI 설비. `GP-*` · `MQ-*` |
| `LOT_NO` | 로트 번호 | `20260911` 형태(일자) |
| `SERIAL_NO` | 시리얼 번호 | `00015` 형태. `0` 인 행도 있음 |
| `SEQ` | **시리얼 번호 안에서 측정된 순서** | 한 시리얼 2,000~3,800회 |
| `PASSED` | 통과 여부 — `1` 통과 · `0` 불량 | 기존 구현과 같음 |
| `DATE_TIME` | 측정 시간 | 기간 조건·인덱스의 기준 열 |
| `FAI1`~`FAI100` | (정의 미제공) | 측정값. 항목명·규격 없음 확정(3차 C-3), 한계는 포화 역산 |
| `COMMENT` | (정의 미제공) | `#12 Y` 류 8종. 지그/캐비티로 **추정**해 `cavity` 로 내보내는 중 — **확인 필요** |

### 9-1. `WC_CD` = 직전 공정 — 실측이 뒷받침한다

원천의 `WC_CD` 전수와 MES 작업장 마스터를 맞춰 보면 공정명이 그대로 나온다.

| `WC_CD` | `mes.tb_md_workcenter.wc_nm` | 전 기간 행수 | 설비 |
| :--- | :--- | ---: | ---: |
| `S120` | A-COATING (**도장**) | 3,731,013 | 7 |
| `S110` | A-PLATING(선별-출하) (**도금**) | 3,130,533 | 8 |
| `S112` | B-PLATING (**도금 B라인**) | 69,759 | 4 |
| `` (빈 문자열) | — | 11,102 | 3 |
| `S136` | C1-FQC(M-3공장) | 6,720 | 1 |
| `NULL_S120` | — (리터럴 문자열) | 3,808 | 1 |
| `NULL_S110` | — (리터럴 문자열) | 3,717 | 1 |
| `W150` | B-프레스 작업장(M-2공장) | 3,486 | 1 |

**결정적 근거 — 한 시리얼이 `WC_CD` 를 두 개 갖는다.** `LOT=20260909 SN=00141` 은 `WC_CD` 2종 · 설비 2종에 11,885행이 있다.
같은 제품이 도금을 거쳐 한 번, 도장을 거쳐 또 한 번 측정된다는 뜻이다. 설비 `MQ-005` 가 `S110` 과 `S120` 양쪽에 나타나는 것도 같은 이유다.

> 이것이 **한계 세트를 (작업장, 설비) 단위로 잡은 것이 왜 맞았는지**를 설명한다(4차 결론). 설비만으로 키를 잡았다면 `MQ-005` 에서 도금·도장 규격이 섞였을 것이다.
> 다만 키 이름은 맞았어도 **뜻은 '작업장' 이 아니라 '직전 공정'** 이다.

### 9-2. 이 정의가 바꾸는 것

1. **용어** — 계약·화면·브리핑 문장의 「작업장」은 「직전 공정」이 맞다. 파라미터 이름(`wcCd`)은 원천 컬럼명이라 그대로 두고, 설명과 라벨만 고치는 쪽을 권한다.
2. **이름 표시** — 지금 API 는 `wcCd` 코드만 내려준다. MES 마스터에 이름이 있으므로 `wcNm`(`A-COATING` 등)을 같이 내리면 화면이 코드를 외우지 않아도 된다.
3. **한계 세트 공백** — `app.aoi.limits` 는 `S110` · `S120` 뿐이다. `S112`(최근 7일 14,039행) · `S136`(6,720행) · 빈 문자열(7,286행)로 조회하면 `resolveLimits` 가 null 을 돌려주고 `faiCount` 가 100 으로 떨어져 **불량이 전부 `unconfirmed`(설명률 0%)** 가 된다. 막지 않고 쓸모없는 답을 준다.
4. **원천 이상값** — `NULL_S110` · `NULL_S120` 은 리터럴 문자열이고 빈 문자열 행은 08-05부터 09-12까지 계속 쌓인다. `wcCd` 가 필수라 이 행들은 어떤 조회로도 잡히지 않는다.
5. **레이저** — 발주자가 든 `C-LASER`(`S135`)는 원천에 **아직 한 행도 없다**. 앞으로 들어올 공정으로 보인다.

### 9-3. 확인이 필요한 것

- (a) `COMMENT` 의 뜻 — 지그/캐비티가 맞는지. 화면이 `cavity` 로 그리고 있다.
- (b) `S112` · `S136` · `W150` 을 화면 선택지에 넣을지. 넣는다면 한계 세트 역산을 그 공정에도 해야 한다.
- (c) 빈 문자열 · `NULL_S110/S120` 행의 처리 — 원천 적재를 고칠 사안인지, API 가 '미지정' 으로 묶어 보여줄 사안인지.
