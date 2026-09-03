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
| `metrics` | 3 | |
| `products` | 2 | 제품군·랭킹 |
| `ai/*` | 8 | agents·chat·model-config·model-releases·vector-builds |
| `alerts`, `alert-escalation-rules` | 2 | |
| `menus`, `audit-logs`, `download-logs`, `health` | 4 | |

### 빈 상태 UI 가 필요한 도메인 — API 는 정상, 데이터가 없음

`glossary` · `alert-conditions` · `alert-duties` · `alert-recipients` ·
`alert-recipient-groups` · `ai/mask-rules` · `ai/finetune-builds` ·
`quality/report-forms` · `quality/reports` · `reports/scrap` · `reports/ship-plan` ·
`system/users/pending` · `system/data-perms/audit`

전부 **화면에서 사용자가 생성하는 데이터**다. 목록 API 는 `{"items": []}` 를 정상 반환하므로
빈 배열을 오류로 처리하지 말고 "등록된 항목이 없습니다 + 등록 버튼" 형태로 그리면 된다.
등록 API(POST) 104개는 별도로 살아 있고, 쓰기→조회 왕복을 아래 3개 도메인에서 실증했다.

- `POST /metrics/standards` → `GET /metrics/standards` 반영 확인
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

기존 응답 스키마는 바꾸지 않았다. 위 2건 외에 재작업할 부분은 없다.

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
