# QC-01 제품별 불량 현황 — `GET /api/v1/quality/defects/by-product` (2026-09-13)

화면 `/quality/defect` 의 **제품별 불량 현황 카드**(제품 표·차트·라인 선버스트)용 트리. 제품이 최상위이고
`제품 > 불량 유형 > 설비(라인) > 공정` 으로 내려간다. 엑셀은 이번 범위가 아니다.

## 1. 왜 별도 엔드포인트인가

기존 `GET /defects/tree` 는 `levels` 로 순서를 바꿀 수 있지만 **유형(defect)을 마지막에만** 둔다. 유형 아래로 내려가면
"정상 수량" 이 뜻을 잃고(유형에 정상 수량은 없다) `totalQty` 가 원장 총량이 아니라 **분모**가 되어 형제끼리 더할 수 없게
되는데, 이 규칙은 `tree` 의 "모든 단계 상위 = 하위 합" 계약과 다르다. 두 계약을 한 응답에 섞으면 화면이 어느 열을
더해도 되는지 노드마다 판단해야 하므로, 분모 규칙이 고정된 트리는 별도 엔드포인트로 두었다.
SQL 은 `tree` 와 **같은 두 쿼리**(라벨 원장 집계 · 유형 안분)를 재사용하고 조립만 다르다(`ProductDefectTreeAssembler`).

## 2. 계약

```
GET /api/v1/quality/defects/by-product?from=2026-08-12&to=2026-09-11[&processId=W120]
```

| 파라미터 | 뜻 |
| :--- | :--- |
| `from` / `to` | YYYY-MM-DD, 종료일 포함. 비우면 오늘 −30일 ~ 오늘(다른 QC-01 조회와 같다) |
| `processId` | 공정(워크센터) 코드. 비우면 전체. 다른 QC-01 조회와 같은 조건 |

```json
{"success":true,"data":{
  "period":{"from":"2026-08-12","to":"2026-09-11"},
  "totals":{"totalQty":531233668,"okQty":520008819,"ngQty":11224849,"defectRate":2.11,"itemCnt":175},
  "items":[
    {"level":"item","itemCd":"D53BM(C)-F","itemNm":"D53BM(MEM-Baffle) PACKING -F",
     "defectCd":null,"defectNm":null,"eqptCd":null,"eqptNm":null,"wcCd":null,"wcNm":null,"plantCd":"PL01","plantNm":null,
     "totalQty":11730207,"okQty":10680051,"ngQty":1050156,"defectRate":8.95,"ratio":9.36,
     "children":[
       {"level":"defect","itemCd":"D53BM(C)-F","itemNm":"…","defectCd":"DF021","defectNm":"얼룩", "eqptCd":null,…,
        "totalQty":11730207,"okQty":null,"ngQty":329879,"defectRate":2.81,"ratio":31.41,
        "children":[
          {"level":"eqpt","…":"…","eqptCd":"MN-077","eqptNm":"Cosmetic MEM_BF (02)호기","wcCd":null,
           "totalQty":1243953,"okQty":null,"ngQty":250147,"defectRate":20.11,"ratio":75.83,
           "children":[
             {"level":"wc","…":"…","wcCd":"S134","wcNm":"G-Cosmetic(FQC)","plantCd":"PL01","plantNm":null,
              "totalQty":1243953,"okQty":null,"ngQty":250147,"defectRate":20.11,"ratio":100.0}
           ]}
        ]}
     ]}
  ]}}
```

모든 노드에 같은 키가 있다 — `level`(item · defect · eqpt · wc), `itemCd/itemNm`, `defectCd/defectNm`, `eqptCd/eqptNm`,
`wcCd/wcNm`, `plantCd/plantNm`, `totalQty`, `okQty`, `ngQty`, `defectRate`, `ratio`. 그 단계까지 확정된 차원만 채우고
나머지는 null. `children` 은 비어 있지 않을 때만 싣는다.

### 단계별 수량의 뜻 — **열마다 세로 합을 내도 되는지가 다르다**

| 단계 | `totalQty` | `okQty` | `ngQty` | `defectRate` | `ratio` |
| :--- | :--- | :--- | :--- | :--- | :--- |
| 제품 `item` | 그 제품 라벨 원장 총량(정상+불량) | 원장 정상 | 원장 불량 | ngQty ÷ totalQty × 100 | **전체 불량** 중 이 제품 비중 (제품 ratio 합 = 100) |
| 유형 `defect` | **그 제품**의 원장 총량 — 형제 유형마다 **같은 값이 되풀이** | null | 이 유형의 안분 불량 | ngQty ÷ 제품 총량 (제품 총량 대비 이 유형 불량률) | 제품 불량 중 이 유형 비중 |
| 설비 `eqpt` | **(제품, 설비)** 원장 총량 — 같은 설비가 여러 유형 아래 나오면 같은 값 | null | 이 유형이 이 설비에서 난 안분 불량 | ngQty ÷ (제품, 설비) 총량 | 유형 불량 중 이 설비 비중 |
| 공정 `wc` | **(제품, 설비, 공정)** 원장 총량 | null | 이 유형이 이 설비·공정에서 난 안분 불량 | ngQty ÷ (제품, 설비, 공정) 총량 | 설비 불량 중 이 공정 비중 |

- **`ngQty` 는 어느 단계에서나 상위 = 하위 합**이다(유형 미상 포함). 제품 `ngQty` 합 = `totals.ngQty` = 요약 카드 ngQty.
  선버스트·누적 차트에 쓸 수 있는 열은 이것이다.
- **`totalQty` 는 유형 단계부터 분모다.** 같은 라벨이 유형마다 되풀이되므로 형제끼리 더하면 같은 생산량을 유형 수만큼 세게 된다.
  화면·엑셀에서 이 열의 세로 합을 내지 말 것. 제품 행의 `totalQty` 만 원장 총량이고 제품 합 = `totals.totalQty`.
- **`okQty` 는 유형 단계부터 null** — 정상 수량은 유형에 귀속되지 않는다. 필요한 정상 수량은 제품 행에 있다.
- `defectRate` 는 그 단계 분모 대비다. 유형 행의 불량률(제품 총량 대비 이 유형)은 제품 불량률을 유형 비중으로 나눈 것과 같다.
  설비 행의 불량률은 "이 제품을 이 설비에서 만든 것 중 이 유형이 난 비율" 이다 — 설비 전체 불량률(by-line)과 다르다.
- **유형 미상**(`defectCd:null`, `defectNm:"유형 미상"`)은 (제품, 설비, 공정) 잎마다 `원장 불량 − 유형 안분 합` 으로 계산해 넣는다.
  그래서 유형 미상도 설비·공정으로 펼쳐지고, 제품 불량 합이 보존된다. 유형 목록에서는 맨 뒤다(by-type 와 같다).
- 반올림 잔차: 안분 수량은 잎에서 소수이므로 단계마다 합쳐 반올림하면 형제 합이 상위와 ±1 어긋날 수 있다.
  그 차이는 **가장 큰 자식**에 더하거나 빼서 상위(원장 기준)에 맞춘다. 30일·90일·365일 실측 어긋남 0.
- 정렬: 제품·설비·공정 `ngQty` 내림차순(같으면 코드), 유형은 내림차순이되 유형 미상 맨 뒤. 설비가 없는 라벨은 `eqptCd:null` 로 맨 뒤.
- `plantCd` 는 라벨의 사업장 코드(PL01 하나). `plantNm` 은 공정 행에서만 작업장 이름의 `(M-n공장)` 표기로 읽는다
  (실적 집계 화면·`tree` 와 같은 규칙). 표기가 없으면 null — 사업장 마스터는 없다.
- 마스킹: `yield` 없음 → `items:[]`, `totals:null`. `qty` 없음 → 모든 단계 `totalQty` `okQty` `ngQty` 와 `totals` 수량이 null, 비율(`defectRate` `ratio`)은 남는다.

### '라인' = 설비 코드 — 맞다

`eqptCd` 는 `mes.tb_pop_label_hist.eqpt_cd` 그대로다. `ProductionRepository.ResultFilter.lineCd` 도 이 컬럼 정확 일치이고
모니터링 `lineRange` 도 이 컬럼 전방 일치다. 설비 마스터(`tb_md_eqpt`)에 라인 컬럼은 따로 없다. 선버스트의 라인 고리는 `eqptCd/eqptNm` 을 쓰면 된다.

## 3. 실측 (로컬 PL01 · 라벨 7.3M행 · 불량 이력 9.1M행 · 관리자 10000)

| 기간 | 노드 (item / defect / eqpt / wc) | 총 노드 | JSON 원문 | gzip 전송 | 응답 |
| :--- | :--- | ---: | ---: | ---: | ---: |
| 1일 (9/11) | 89 / 233 / 490 / 490 | 1,302 | 395 KB | 22 KB | 0.11초 |
| 7일 | 113 / 314 / 747 / 747 | 1,921 | 586 KB | 32 KB | 0.53초 |
| **30일 (화면 기본)** | 175 / 499 / 1,267 / 1,267 | **3,208** | **982 KB** | **53 KB** | **2.1초** |
| 90일 | 229 / 688 / 1,966 / 1,966 | 4,849 | 1.49 MB | 79 KB | 8.2초 |
| 365일 | 458 / 1,476 / 4,639 / 4,639 | 11,212 | 3.47 MB | 179 KB | 19.7초 |
| 30일, 공정 W120 | 7 / 20 / 65 / 65 | 157 | 49 KB | 3 KB | 0.07초 |

검증(각 기간 전부): `ngQty` 상위 = 하위 합 어긋남 0 · 유형 이하 `okQty` 비null 0 · 유형 `totalQty` ≠ 제품 총량 0 ·
같은 (제품, 설비)의 `totalQty` 불일치 0 · 제품 `ngQty` 합 = `totals.ngQty` · 제품 `ratio` 합 100.0(90일·365일은 반올림으로 99.9).
30일 제품 행의 정상·불량은 `GET /defects/tree?levels=item` 의 175개 제품과 전부 동일하고, `totals` 는 `GET /defects/summary` 의
`totalQty 531,233,668 · ngQty 11,224,849` 와 같다.

공정 수는 설비 수와 같다(설비 한 대는 거의 한 공정에 속한다 — 30일 기준 두 공정에 걸친 설비 4대). 그래서 공정 고리는 대부분 100% 한 조각이다.
비용은 기간에 비례한다(`tree` 와 같은 두 쿼리 + 조립). 화면 기본 30일은 2초 안이고, 긴 기간을 열어야 하면 `processId` 로 나눠 받는 편이 싸다.

## 4. 바뀐 파일

- `service/ProductDefectTree.kt` — `ProductDefectTreeAssembler`(잎 구성·유형 미상·단계별 분모·잔차 보정·마스킹). 단계별 수량 뜻은 이 파일 주석이 원본이다
- `service/QualityDefectService.kt` — `getByProduct`
- `controller/QualityController.kt` — `GET /defects/by-product`
- 테스트 `QualityDefectByProductTest`(5) — 합계·분모·비율·유형 미상 드릴다운·마스킹. 스키마 변경 없음, 요청 본문 없음(계약 문서 변경 없음)
