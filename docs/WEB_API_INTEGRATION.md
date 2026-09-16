# 웹 ↔ API 연동 가이드

작성: API 세션 (`term_14bae68f`) · 기준일 2026-09-01
대상: 웹 세션 (`term_e98581da`)

로컬 API 서버 `http://localhost:8080` 을 실 DB(`localhost:5432 / dwjedb`)에 붙여
**GET 149개를 전수 호출해 실측한 결과**다. 추정이 아니라 응답을 직접 확인한 값이다.

---

## 1. 접속 정보

| 항목 | 값 |
|---|---|
| Base URL | `http://localhost:8080` |
| 인증 | `Authorization: Bearer <accessToken>` |
| Swagger | `http://localhost:8080/swagger-ui.html` |
| OpenAPI | `http://localhost:8080/v3/api-docs` |

시드 계정 6종 (비밀번호 공통 `Dwje!2026`)

| loginId | 이름 | 부서 | 비고 |
|---|---|---|---|
| 10000 | 관리자 | 전산팀 | 통합관리자(전 권한) |
| 10001 | 김품질 | 품질보증팀 | |
| 10002 | 박생산 | 생산관리팀 | |
| 10003 | 이제조 | 제조팀 | |
| 10004 | 최전산 | 전산팀 | |
| 10005 | 정경영 | 경영지원팀 | |

권한 차이를 확인하려면 10000 과 10001 을 번갈아 로그인하면 된다.
메뉴 권한이 없으면 `E-AUTH-002`, 데이터 항목 권한이 없으면 응답의 `masked` 배열에 항목 키가 실린다.

---

## 2. 실측 결과 요약

GET 149개 기준

| 구분 | 건수 | 의미 |
|---|---:|---|
| 실데이터 응답 | **92** | 지금 바로 `live: true` 전환 가능 |
| 빈 응답 | 40 | API 정상. 해당 `ax` 테이블이 0행이라 빈 배열이 온다 |
| 더미 id 로 인한 404/400 | 17 | **결함 아님.** 실제 id 로 재호출해 전부 200 확인 |
| 5xx | **0** | 서버 오류 없음 |

MES 실적 데이터는 충분하다 — 라벨 실적 749만행 / 불량 899만행 / 재고 2,440만행 / 품목 2,006건.

---

## 3. 도메인별 전환 판정

### 즉시 `live: true` — 실데이터가 나오는 도메인

| 도메인 | 실데이터 GET | 비고 |
|---|---:|---|
| `auth` | 4 | 이미 live |
| `common` | 7 | 코드·공정·설비·제품·불량유형·금형 + 신규 `data-range` |
| `dashboard/kpi` | 7 | |
| `dashboard/ai` | 8 | |
| `dashboard/process` | 8 | 이번에 제품 마스터 시드로 해소 |
| `production` | 8 | 실적·모니터링·일일보고 |
| `quality` | 10 | 불량 현황 + AOI 예측 |
| `system` | 10 | 계정·부서·메뉴권한·데이터권한 |
| `reports` | 5 | |
| `sync` | 5 | |
| `metrics` | 3 | **2026-09-15 제거**(지표 측정 데이터 관리 화면) |
| `products` | 2 | 제품군·랭킹 — **2026-09-15 제거**(제품군 순위 관리 화면) |
| `ai/*` | 8 | chat 만 남음 — agents·model-config·model-releases·vector-builds 는 **2026-09-15 제거**(`dashboard/ai/agents` 는 별개로 유지) |
| `alerts`, `alert-escalation-rules` | 2 | |
| `menus`, `audit-logs`, `download-logs`, `health` | 4 | |

### 빈 상태 UI 가 필요한 도메인 — API 는 정상, 데이터가 없음

`glossary` · `alert-conditions` · `alert-recipients` ·
`alert-recipient-groups` · ~~`ai/mask-rules` · `ai/finetune-builds`~~(2026-09-15 제거) ·
`quality/report-forms` · `quality/reports` · `reports/scrap` · `reports/ship-plan` ·
`system/users/pending` · `system/data-perms/audit`

전부 **화면에서 사용자가 생성하는 데이터**다. 목록 API 는 `{"items": []}` 를 정상 반환하므로
빈 배열을 오류로 처리하지 말고 "등록된 항목이 없습니다 + 등록 버튼" 형태로 그리면 된다.
등록 API(POST) 104개는 별도로 살아 있고, 쓰기→조회 왕복을 아래 3개 도메인에서 실증했다.

- ~~`POST /metrics/standards` → `GET /metrics/standards` 반영 확인~~ (2026-09-15 API 제거)
- `POST /production/downtimes` → `GET /production/downtimes` 반영 확인
- `POST /alert-recipients` → `GET /alert-recipients` 반영 확인

(실증용으로 만든 비가동·수신자 데이터는 실제 업무 데이터가 아니므로 되돌렸다.)

---

## 4. 화면에서 반드시 반영해야 할 2가지

### 4-1. 기준일 기본값을 `오늘` 로 두지 말 것 — 신규 API 사용

실적 데이터는 **2020-01-02 ~ 2026-08-30** 구간에 있고 오늘 날짜에는 실적이 없다.
운영 환경에서도 당일 실적이 올라오기 전에는 같은 상황이 된다.
기준일을 오늘로 초기화하면 대시보드가 전부 0 으로 보인다.

이 문제 때문에 API 를 새로 추가했다.

```
GET /api/v1/common/data-range?plantCd=PL01
```
```json
{ "success": true, "data": { "plantCd": "PL01", "fromDate": "2020-01-02", "toDate": "2026-08-30" } }
```

- 앱 부팅 시 1회 호출해 전역 상태에 보관한다. (응답 약 20ms)
- 날짜 선택기의 **기본값 = `toDate`**, 선택 가능 범위 = `fromDate ~ toDate`.
- 기간 조회는 `toDate` 기준 역산(최근 7일/30일)으로 잡는다.

### 4-2. 401 처리 범위를 좁힐 것

`401` 은 **토큰 만료·미인증에서만** 발생하도록 API 쪽을 정리했다.
따라서 화면의 axios 인터셉터는 `401` 에서만 로그아웃/토큰갱신을 타면 되고,
`404`(대상 없음) · `403`/`E-AUTH-002`(메뉴 권한 없음) 는 화면 내 메시지로 처리해야 한다.

> 직전까지 `GET /system/data-perms/preview?empNo=<없는사번>` 이 401 을 반환했다.
> 조회 대상이 없을 뿐인데 401 이 나가면 인터셉터가 **호출한 관리자를 로그아웃**시킨다.
> 이번에 `404 / E-NOTFOUND` 로 수정했다.

---

## 5. 이번에 바뀐 API

| 구분 | 내용 |
|---|---|
| 신규 | `GET /api/v1/common/data-range` — 실적 보유 기간 조회 |
| 수정 | `GET /api/v1/system/data-perms/preview` — 대상 계정 없음: `401` → `404 E-NOTFOUND` |
| 데이터 | `ax` 제품 마스터 부트스트랩 (제품군 74 / 제품 205 / 프로젝트 126 / 품목매핑 1,106) |
| 데이터 | 지표 기준 `EQPT_UPTIME_RATE`(설비 가동률) 등록 |
| 수정 | `GET /api/v1/dashboard/ai/defect-trend` — `topN` 쿼리 추가 (아래 5-1) |
| 신규 | `GET /api/v1/dashboard/ai/defect-trend/slot-details` — 추이 칸 하나의 불량 유형 전량 (아래 5-2) |

기존 응답 스키마는 바꾸지 않았다. 위 4건 외에 재작업할 부분은 없다.

### 5-1. `defect-trend` 의 `topN` — 유형 계열 범위

`유형별 불량 수량 추이` 는 상위 2종만 계열로 내려, 같은 화면의 `defect-composition`(전 유형)과
유형 목록이 어긋났다. `topN` 으로 유형 계열 범위를 고른다. 기본 동작은 바뀌지 않는다.

| `topN` | 유형 계열(`series[1..]`) | `seriesScope` |
|---|---|---|
| 미지정 | 구간 합계 상위 **2종** (기존 동작) | `{ kind: "TOP_N", topN: 2, count, includesUntyped: false }` |
| `all` | 그 구간·공장에서 발생한 **전 유형** + 마지막에 `유형 미상` | `{ kind: "ALL", topN: null, count, includesUntyped }` |
| 양의 정수 `N` | 구간 합계 상위 N종 | `{ kind: "TOP_N", topN: N, count, includesUntyped: false }` |
| 그 외 (`0`, `abc` …) | `400 E-VALID-001` (`field: "topN"`) | |

- `series[0]` 은 전체 불량률(%) 계열이고, `series[1..]` 는 유형별 수량(EA) 계열이다. 모두 `labels` 와
  같은 길이·순서이며 그 칸에 실적이 없으면 `0` 이다. 유형 계열 순서는 구간 합계 내림차순이다.
- `seriesScope.count` 는 실제로 내린 유형 계열 수다. `kind: "TOP_N"` 일 때 유형 계열 합은 총 불량이 아니다.
- `topN=all` 일 때 유형이 붙지 않은 불량(라벨에는 불량 수량이 있으나 불량 이력이 없는 건)이 구간에
  하나라도 있으면 **마지막 계열 `유형 미상`** 으로 낸다 (`includesUntyped: true`). `defect-composition` 의
  `유형 미상` 세그먼트(`code: null`)와 같은 정의라 두 위젯의 유형 집합이 같다. 발생량이 0 이면 계열을 붙이지 않는다.
- 화면의 `유형별 불량 수량 추이` 는 `topN=all` 로 호출한다 (구성비 위젯과 유형 목록을 맞춘다).

```
GET /api/v1/dashboard/ai/defect-trend?from=2026-08-01&to=2026-08-28&interval=2h&topN=all
```

### 5-2. `defect-trend/slot-details` — 칸 클릭 상세 (불량 유형 전량)

일자 × 시간대 매트릭스의 칸을 누르면 그 칸에서 발생한 불량 유형을 **전량** 표로 보인다.
칸의 경계는 서버가 추이와 같은 규칙으로 자르므로, 화면은 추이를 조회할 때 쓴 값을 그대로 넘긴다.

| 파라미터 | 의미 |
|---|---|
| `date` / `from` / `to` / `interval` | **추이(defect-trend) 조회와 같은 값.** 칸의 폭(집계 단위)이 이 값으로 정해진다 |
| `slotAt` | 칸 시작 시각. `yyyy-MM-dd HH시`(화면 라벨 그대로) · `yyyy-MM-dd HH:mm` · `yyyy-MM-ddTHH:mm` · `yyyy-MM-dd`. 칸 안의 아무 시각이어도 그 칸으로 맞춘다. `slot` 보다 우선 |
| `slot` | 추이 응답 `labels[]` / `slots[].slot` 의 값 (`08:00`, `08-28 00시`, `08-28`) |
| `plantCd` | 공장 코드. 미지정 시 기본 사업장(`PL01`) |

`slotAt`·`slot` 둘 다 없거나, 칸이 조회 구간 밖이거나, 형식이 다르면 `400 E-VALID-001` (`field: slotAt` 또는 `slot`).

```
GET /api/v1/dashboard/ai/defect-trend/slot-details?date=2026-08-28&interval=2h&slotAt=2026-08-28 00시
GET /api/v1/dashboard/ai/defect-trend/slot-details?from=2026-08-22&to=2026-08-28&interval=2h&slot=08-28 00시
```

응답 `data`

| 키 | 의미 |
|---|---|
| `slot`, `slotFrom`, `slotTo` | 서버가 실제로 본 칸 — 라벨과 시작(포함)·끝(미포함) 시각 |
| `plantCd`, `period`, `bucket` | 공장, 조회 구간, 집계 단위 (추이와 같은 형식) |
| `inputQty`, `okQty`, `totalNgQty`, `defectRate`, `labelCount` | 칸의 투입·양품·불량 수량, 불량률(%), 라벨 건수 |
| `typedNgQty`, `untypedNgQty` | 유형이 붙은 불량 / 유형 미상 불량. 합이 `totalNgQty` |
| `items[]` | 불량 유형 전량. 순위 내림차순, `유형 미상` 이 있으면 마지막 행 |

`items[]` 한 행

| 키 | 의미 |
|---|---|
| `rank` | 순위 (1부터) |
| `defectTypeCd` | 불량 코드. `유형 미상` 행은 `null` |
| `defectType` | 표시명 |
| `ngQty` | 불량 수량(EA). 불량 유형 구성과 같은 라벨 원장 안분 값 — 행 합 = `totalNgQty` |
| `ratio` | `totalNgQty` 대비 % (소수 2자리). 행 합 ≈ 100 |
| `untyped` | `유형 미상` 행이면 `true` |
| `rawQty` | 원표(`tb_pop_defect_hist`) 수량 합 — 안분 전 값. 라벨 불량 수량과 어긋날 수 있어 분모로 쓰지 않는다 |
| `recordCount`, `lotCount`, `itemCount` | 원표 이력 건수, LOT 수, 품목 수 |
| `itemCds[]`, `processIds[]` | 그 유형이 발생한 품목 코드·공정(작업장) 코드 목록 |
| `remarks[]` | 원표 비고(빈 값 제외, 중복 제거) |
| `insUsers[]` | 원표 등록자 목록 — **작업자(worker) 권한** 없으면 `null` |
| `firstAt`, `lastAt` | 그 유형 원표의 최초·최종 등록 시각. 칸은 **라벨 시각** 기준이라 원표 시각은 칸 밖일 수 있다 |
| `useFlg`, `masterRemark` | 불량 코드 마스터(`tb_md_defect`)의 사용 여부·비고 |

마스킹은 다른 대시보드 조회와 같다.

- 수율(`yield`) 권한 없음 → `items: []`, 수량·불량률 전부 `null`, `masked: ["yield"]`
- 수량(`qty`) 권한 없음 → `inputQty`·`okQty`·`totalNgQty`·`typedNgQty`·`untypedNgQty`·행의 `ngQty`·`rawQty` 가 `null`. 순위·비율·속성은 남는다
- 작업자(`worker`) 권한 없음 → 행의 `insUsers` 만 `null`

---

## 6. 알아둘 제약

**제품 계층은 부트스트랩 값이다.**
`제품군`/`제품` 은 AX 고유 업무 축인데 MES 에 대응 항목이 없어(품목 2,006건 중 `product_family` 가 전건 비어 있음),
MES 품목코드 규칙에서 파생시켰다.

- 제품 = `item_cd` 의 하이픈 앞 (`D34BS-F`, `D34BS-P2` → `D34BS`)
- 제품군 = `project_nm` 괄호 안 (`23Y(Vr-Shield-Can)` → `Vr-Shield-Can`), 괄호가 없으면 `미분류`
- **`미분류` 제품군이 122개 제품으로 가장 큼** — 랭킹 화면 상단에 `미분류` 가 뜨는 건 데이터 특성이지 버그가 아니다.
- 고객사(`customer`)는 MES 에 정보가 없어 전부 `null` 이다. 고객사 컬럼은 빈 값 처리를 해두어야 한다.

**가동률 값은 아직 없다.**
`EQPT_UPTIME_RATE` 지표 *기준* 은 등록했지만 측정 *값*(`ax.tb_met_metric_value`)은 0행이라
`avgUptime`, `equipment-uptime-heatmap`, `product-uptime`, `kpi/basis`, `manhour-saving`,
`monthly-matrix` 는 `null`/빈 배열이 온다. 지표 값을 화면에서 적재하면 채워진다.
해당 위젯은 "데이터 없음" 표시로 처리해달라.

**MES 스키마는 조회 전용이다.**
`mes.*` 에 대한 쓰기는 API 코드·SQL 전체에서 금지되며, 빌드 시 `MesReadOnlyContractTest` 가 검사한다.
웹에서도 MES 원본을 바꾸는 기능은 만들지 않는다.
