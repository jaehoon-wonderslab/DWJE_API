# QC-01 불량 현황 조회 — by-line 응답 확장 · 엑셀 내려받기 2건 (2026-09-12)

화면 `/quality/defect` 개편(설비 > 불량 유형 트리, '상위 5개' 표기 제거)에 맞춘 서버 변경. 프론트는
`useDefectStatusController.lineRows` 가 이미 `children[{defectCd, defectType, ngQty, ratio}]` 와 `okQty` 를 읽도록 되어 있다.

## 1. `GET /api/v1/quality/defects/by-line` 응답 확장

```json
{"items":[{
  "eqptCd":"MN-077","eqptNm":"Cosmetic MEM_BF (02)호기","model":null,
  "ngQty":48510,"okQty":25830,"totalQty":74340,"defectRate":65.25,"mainType":"얼룩",
  "children":[
    {"defectCd":"DF021","defectType":"얼룩","ngQty":14575,"ratio":30.05},
    {"defectCd":"DF003","defectType":"스크래치","ngQty":10998,"ratio":22.67},
    {"defectCd":null,"defectType":"유형 미상","ngQty":2210,"ratio":4.56}
  ]}]}
```

| 항목 | 값 |
| :--- | :--- |
| 추가 필드 | `okQty`(= `sum(lh.normal)`), `totalQty`(= ok + ng), `children[]` |
| `children.ngQty` | 그 설비 라벨 불량 수량을 유형 구성비로 **안분**한 값 — by-type 와 같은 식(`DefectSql.apportionedTypeCte`) |
| `children.ratio` | 그 설비의 불량 합(`ngQty`) 대비 %. 유형이 붙지 않은 몫은 `defectCd:null` · `defectType:"유형 미상"` 행으로 채워 **합 = ngQty, ratio 합 = 100%** |
| `children` 정렬 | 안분 수량 내림차순 |
| `mainType` | 가장 큰 유형의 이름. 유형이 없는 설비는 null('유형 미상' 을 주 유형으로 올리지 않는다) |
| 기존 필드 | `eqptCd` `eqptNm` `model` `ngQty` `defectRate` `mainType` 이름·뜻 그대로 |
| 마스킹 | `yield` 없음 → `items:[]`(기존과 같음). `qty` 없음 → `ngQty` `okQty` `totalQty` `children[].ngQty` 가 null, 비율·주 유형은 남음 |

### `topN` — 기본값과 상한을 이렇게 정했다

| 구분 | 이전 | 지금 |
| :--- | :--- | :--- |
| 생략 시 | 상위 5대 | **전체 설비** (불량 수량 내림차순) |
| `topN=0` | 400 아님, 1로 보정 | 전체 설비 |
| 양수 | 1~100 으로 보정 | 그 수만큼, 상한 **2000** |

**근거.** 60초 타임아웃의 원인이었던 LATERAL(설비마다 주 유형을 따로 구하던 서브쿼리)을 걷어내고,
기간 라벨 원장 하나에서 설비별 수량과 설비×유형 안분 수량을 한 번에 집계하도록 바꿨다.
그래서 비용은 **기간 길이**에만 비례하고 N 과 무관하다 — N 을 줄여도 빨라지지 않으므로 기본값을 전체로 두어도
잃는 것이 없다. 상한 2000 은 응답 크기 안전판이다(사업장 설비 마스터 1,540대 · 1년간 실적 설비 1,000대 안팎).

로컬 실측(라벨 7.3M행 · 불량 이력 9.1M행, 전체 설비):

| 기간 | 설비 수 | 유형 행 | API 응답 |
| :--- | ---: | ---: | ---: |
| 1일 (9/11) | 544 | 400 | 0.14초 |
| 7일 | 636 | 580 | 0.62초 |
| 30일 (화면 기본 — 이번 달) | 734 | 893 | 1.97초 |
| 90일 | 805 | 1,180 | 3.47초 |
| 365일 (SQL 직접) | 1,000+ | 2,669 | 10.3초 |
| 참고: 이전 LATERAL, 30일 topN=100 | 100 | — | 2.07초 (N 에 비례) |

`ngQty` `defectRate` `mainType` 은 9/11 상위 5대에서 이전 응답과 동일했다(BG-002 얼룩 · MN-077 얼룩 · YG-058 얼룩 ·
MO-006 은하수 · MN-073 얼룩). 주 유형의 정의만 "`defect_hist.qty` 원값 최대" 에서 "안분 수량 최대" 로 바뀌었는데,
같은 라벨 안에서는 두 순서가 같아 실측에서 차이가 없었다.

## 2. 엑셀 내려받기 2건

```
POST /api/v1/quality/defects/by-type/export
POST /api/v1/quality/defects/by-line/export
Content-Type: application/json
{"from":"2026-08-12","to":"2026-09-11","processId":null,"defectTypeCd":null,"format":"xlsx"}
```

| 항목 | 값 |
| :--- | :--- |
| 본문 DTO | `QualityDefectExportRequest` — 허용 키 `from` `to` `processId` `defectTypeCd` `format`. 다른 키는 400(`FAIL_ON_UNKNOWN_PROPERTIES`) |
| `from`/`to` 생략 | 종료일 오늘, 시작일 −30일 (조회 API 와 같음) |
| `format` | `xlsx`(기본)·`xls`·`excel` → xlsx. `csv` 는 400 (`field: format`) |
| `defectTypeCd` | 받아서 **조회 조건 시트와 이력에만** 남긴다. 유형별 분포·설비별 표는 화면과 같이 전체 유형 기준(GET by-type/by-line 도 이 조건을 받지 않는다). 값이 있으면 시트에 "요약 카드에만 적용" 줄이 붙는다 |
| 응답 | xlsx 바이너리 · `Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` |
| 파일명 | `Content-Disposition: attachment; filename*=UTF-8''불량_유형별_분포_{from}_{to}.xlsx` / `설비별_불량률_{from}_{to}.xlsx` — 프론트 `filenameOf()` 가 그대로 쓴다 |
| 권한 | 메뉴 `qc-defect`. 마스킹은 조회 API 와 같고, 가린 칸은 빈 셀, 조회 조건 시트 "권한 마스킹 항목" 과 이력 `blind_cnt` 에 남는다 |
| 이력 | `ax.tb_rpt_download_log` (요청에 적힌 `tb_log_download` 는 이 테이블이다) — `target_nm` `불량 유형별 분포` / `설비별 불량률`, `menu_id` `qc-defect`, `format_cd` `XLS`, `row_cnt`(유형 수 / 설비 수), `file_nm`, `file_size`, `params_json`(from·to·processId·defectTypeCd·format) |
| 오류 | 기존 공통 JSON — 프론트 `downloadFromServer` 가 `message` 를 토스트로 보여 준다 |

### 시트 구성

**by-type** — `조회 조건` + `불량 유형별 분포`(불량 유형 코드 · 불량 유형 · 불량 수량 · 비중(%)).
행은 `GET by-type` 의 items 그대로(유형 미상 행 포함, 비중 합 100%, 수량 합 = 요약 카드 ngQty).

**by-line** — `조회 조건` + `설비별 불량률`(설비 코드 · 설비명 · 정상 수량 · 불량 수량 · 불량률(%) · 주 유형) +
`설비별 불량 유형 상세`(설비 코드 · 설비명 · 불량 유형 코드 · 불량 유형 · 불량 수량 · 비중(%)).
설비는 **전체**(topN 없음), 상세는 children 을 설비마다 펼친 것이다. 설비명이 없으면 모델명(화면과 같음).

`조회 조건` 시트: 화면 · 기간 · 일수 · 공정 · 불량 유형 조건 · 집계 기준 · 합계(설비 수/유형 수/불량·정상 수량 합계/유형 상세 행 수) ·
권한 마스킹 항목 · 내려받은 사용자 · 생성 시각 · 빈 칸의 뜻. 첫 시트만 보고도 무엇을 어떤 조건으로 받았는지 알 수 있게 했다.

로컬 실측(8/12~9/11, 관리자): by-type 44행(6.7KB) · by-line 734설비/893상세(61.8KB) 모두 200,
설비별 children 합 = ngQty 불일치 0/734, by-type 수량 합 = 요약 ngQty(11,224,849). 이력 dl_id 21·22 기록.

## 3. 바뀐 파일

- `repository/QualityRepository.kt` — `findDefectByLine` 단일 집계 쿼리로 재작성(topN nullable), `DefectByLineRow` · `assembleDefectByLine`
- `service/QualityDefectService.kt` — `getByLine(topN: Int?)`, 전체/상한 2000, children 마스킹
- `service/QualityDefectWorkbook.kt` — by-type · by-line xlsx 생성기, `DefectExportConditions`
- `service/WorkbookStyles.kt` — 실적 집계 내려받기에서 뽑아낸 공통 스타일·셀 쓰기(`ProductionResultScreenWorkbook` 도 이걸 쓴다)
- `model/request/QualityRequests.kt` — `QualityDefectExportRequest`
- `controller/QualityController.kt` — by-line `topN` 선택 파라미터, export 엔드포인트 2개
- `docs/REQUEST_BODY_CONTRACT.md` — DTO 추가, 엔드포인트 수 69 → 71
- 테스트 — `QualityDefectByLineAssembleTest`(2) · `QualityDefectExportTest`(6). 스키마 변경 없음

---

## 4. (2차) 불량 상세 분해 트리 — `GET /api/v1/quality/defects/tree`

화면이 Tabulator dataTree 로 펼치는 `공정 > 제품 > 설비 > 불량 유형` 트리. by-line 은 기존 화면·export 가 쓰고 있어
그대로 두고 **엔드포인트를 새로 열었다**. by-line/export 엑셀에는 이 트리가 `불량 상세 분해` 시트로 추가된다.

```
GET /api/v1/quality/defects/tree?from=2026-08-12&to=2026-09-11&processId=&levels=wc,item,eqpt,defect
→ data: { levels:["wc","item","eqpt","defect"], period:{from,to},
          totals:{okQty, ngQty, defectRate},          // 요약 카드 ngQty 와 같은 값
          items:[ { level:"wc", plantCd, plantNm, wcCd, wcNm, itemCd:null, itemNm:null, eqptCd:null, eqptNm:null, defectCd:null, defectNm:null,
                    okQty, ngQty, defectRate, ratio:null,
                    children:[ { level:"item", …, children:[ { level:"eqpt", …, children:[ { level:"defect", …, okQty:null, ngQty, defectRate:null, ratio } ] } ] } ] } ] }
```

| 항목 | 값 |
| :--- | :--- |
| 단계 행 공통 열 | `level` + `plantCd/plantNm · wcCd/wcNm · itemCd/itemNm · eqptCd/eqptNm · defectCd/defectNm` — 그 단계까지 확정된 값만, 나머지 null |
| 수량 단계(wc·item·eqpt) | `okQty` `ngQty` `defectRate`. 라벨 원장 합이라 **상위 = 하위 합**이 항상 성립(30일·90일·365일 실측 불일치 0) |
| 유형 단계(defect) | `ngQty`(안분 수량) + `ratio`(상위 ngQty 대비 %). `okQty` `defectRate` 는 null — 유형에 정상 수량은 없다. 유형 합 = 상위 ngQty (모자란 몫은 `defectCd:null` '유형 미상', 반올림 초과분은 가장 큰 유형에서 보정) |
| `children` | 비어 있지 않을 때만 싣는다(불량 0 인 설비는 유형 자식이 없어 키가 없다) |
| 정렬 | 각 단계 `ngQty` 내림차순, 같으면 코드 순 |
| `levels` | `wc,item,eqpt,defect` 중 골라 콤마로. 비면 기본. `defect` 는 마지막에만, 중복·모르는 이름은 400(`field: levels`). 유형을 빼면 불량 이력을 읽지 않아 3배 빠르다 |
| `plantCd` / `plantNm` | `plantCd` 는 라벨의 사업장 코드(PL01 하나). `plantNm` 은 **작업장 이름의 `(M-n공장)` 표기**에서 읽은 공장(실적 집계 화면의 공장 열과 같은 규칙, `WorkcenterNames.plantOf`). 표기가 없는 공정은 null — 사업장 마스터(tb_md_plant)는 없다 |
| 마스킹 | yield 없음 → `items:[]`, `totals:null`. qty 없음 → 모든 단계 `okQty` `ngQty` 와 `totals` 수량 null, 비율은 남음 |
| 설비 없는 라벨 | `eqptCd:null` 행으로 남긴다(30일 7건·불량 0). 빼면 요약 카드와 어긋난다 |

### (1) 계층 순서 — 공정 > 제품 > 설비 > 유형 을 기본으로, `levels` 로 바꿀 수 있게

2026-08-12~09-11 라벨 175,145행 실측:

| 차원 | 종수 | 조합 | 종수 |
| :--- | ---: | :--- | ---: |
| 공정(wc) | 30 | (공정, 제품) | 179 |
| 제품(item) | 175 | (공정, 설비) | 738 |
| 설비(eqpt) | 734 | (공정, 제품, 설비) | 1,267 |
| 잎 (공정, 제품, 설비, 유형) | 1,239 (+유형 미상 ≤ 345) | | |

- 설비는 공정에 거의 1:1 로 속한다(두 공정에 걸친 설비 4대). 공정이 맨 위인 것은 자연스럽다.
- **설비 한 대가 만드는 제품은 평균 1.7종(최대 17)**, **한 제품을 만드는 설비는 평균 7.1대(최대 150)** 다.
  - 제품을 설비 위에 두면 2단계가 179행으로 좁고, 3단계에서 **같은 제품을 만든 설비끼리** 불량률을 나란히 비교한다 — 설비별로 보는 목적(편차 찾기)에 맞다.
  - 설비를 위에 두면 2단계가 738행으로 넓고 3단계는 대부분 1~2행이라 펼칠 이유가 없다(노드 수도 3,304 대 2,744 로 더 많다).
- 실적 집계 화면의 트리(일자 → 제품 → 설비)와 같은 방향이라 두 화면의 읽는 법이 같다.
- 그래도 설비 중심으로 보고 싶을 때가 있어 `levels=wc,eqpt,item,defect` 를 허용한다. 총합은 순서와 무관하게 같다(실측 동일).

### (2) 응답 크기·성능 — 전량 응답, 지연 로딩·상한 없음

| 기간 | 노드(wc/item/eqpt/defect) | 총 노드 | JSON 원문 | gzip | 응답 시간 |
| :--- | :--- | ---: | ---: | ---: | ---: |
| 1일 | 22 / 89 / 686 / 490 | 1,287 | 375 KB | 18 KB | 0.18초 |
| 7일 | 24 / 114 / 1,016 / 747 | 1,901 | 554 KB | 27 KB | 0.72초 |
| **30일 (화면 기본)** | 30 / 179 / 1,268 / 1,267 | **2,744** | **806 KB** | **40 KB** | **2.1초** |
| 30일 `levels=wc,item,eqpt` | 30 / 179 / 1,268 | 1,477 | 418 KB | 22 KB | 0.61초 |
| 90일 | 30 / 238 / 1,764 / 1,966 | 3,998 | 1.18 MB | 58 KB | 7.0초 |
| 365일 | 30 / 514 / 3,330 / 4,640 | 8,514 | 2.5 MB | 123 KB | 13.2초 |
| 30일, 공정 하나(W120) | 1 / 7 / 14 / 65 | 87 | 27 KB | 2 KB | 0.06초 |

- 30일 기준 잎 1,267행·총 2,744노드로 **한 번에 주는 편이 안전하다.** `server.compression` 이 켜져 있어 실제 전송은 40 KB 다.
  지연 로딩을 하면 (공정×제품) 179번의 추가 호출이 생기고, 설비별 유형 안분은 어차피 라벨 원장 전체를 다시 읽어야 해서 서버 비용이 줄지 않는다.
- 비용은 기간에 비례한다(라벨 스캔 + 불량 이력 조인). 90일 7초·365일 13초는 화면 기본(30일)에서는 닿지 않는 구간이다.
  긴 기간을 화면에서 허용하려면 `levels=wc,item,eqpt` 로 먼저 받고 유형은 선택 공정(`processId`)만 다시 받는 식이 가장 싸다 — 그때 요청하면 붙이겠다.
- 쿼리는 둘이다: 수량 원장 `(wc,eqpt,item)` 집계(30일 0.3초)와 유형 안분 `(wc,eqpt,item,defect)`(30일 0.5초, 불량 있는 라벨만 조인).
  순서 중첩은 서버 메모리에서 하므로 `levels` 가 무엇이든 SQL 은 같다.

### 확인 요청 — '라인' = '설비'

맞다. `ProductionRepository.ResultFilter.lineCd` 는 `label_hist.eqpt_cd` 정확 일치이고(`AND lh.eqpt_cd = :lineCd`),
모니터링의 `lineRange` 도 `eqpt_cd` 전방 일치다. `tb_md_eqpt` 에 라인 컬럼은 따로 없다(plant_cd·eqpt_cd·eqpt_nm·model_nm·spec…).
이 API 에서 '라인' 은 설비 코드를 가리키는 옛 이름이니 화면에서 한 열로 쓰면 된다.

### by-line/export 변경

본문에 `levels`(선택) 추가. 응답 시트가 4장이 된다 — `조회 조건` · `설비별 불량률` · `설비별 불량 유형 상세` · **`불량 상세 분해`**
(단계 · 공정 코드 · 공정명 · 공장 · 제품 코드 · 제품명 · 설비 코드 · 설비명 · 불량 유형 코드 · 불량 유형 · 정상 수량 · 불량 수량 · 불량률(%) · 유형 비중(%)).
깊이를 엑셀 행 그룹으로 옮겨 접고 펼 수 있고, 조회 조건 시트에 단계 순서·행 수가 적힌다. 30일 실측 2,744행 · 214 KB, 이력 `params_json.levels` 기록.

### 바뀐 파일 (2차)

- `service/DefectTree.kt` — `DefectTreeLevel`(순서 파싱·검증), `DefectTreeAssembler`(중첩·합 보정·평탄화·마스킹)
- `repository/QualityRepository.kt` — `findDefectTreeBase` · `findDefectTreeTypes`, `DefectTreeBaseRow` · `DefectTreeTypeRow`
- `service/QualityDefectService.kt` — `getDefectTree`
- `controller/QualityController.kt` — `GET /defects/tree`, by-line export 에 트리 시트
- `service/QualityDefectWorkbook.kt` — `불량 상세 분해` 시트
- `model/request/QualityRequests.kt` · `docs/REQUEST_BODY_CONTRACT.md` — `levels` 키
- 테스트 `QualityDefectTreeTest`(5) 추가, `QualityDefectExportTest` 갱신. 스키마 변경 없음
