# 실적 집계·조회 화면 전체 엑셀 내려받기 (`scope=screen`) — 2026-09-12

화면 `/production/result` (PR-02) 의 **전체 내려받기** 계약과 서버 구현 메모. 프론트(WEB-ai_concep_design)는
`useProductionResultController.exportScreenExcel` 이 이미 이 계약으로 호출한다.

## 1. 계약

```
POST /api/v1/production/results/export?scope=screen&unit=day
Authorization: Bearer …
Content-Type: application/json

{"from":"2026-09-05","to":"2026-09-11","format":"xlsx"}
```

| 항목 | 값 |
| :--- | :--- |
| `scope` (query) | `screen` — 화면 전체 통합 문서. **비우면 예전 동작**(집계 표 한 장, xls/csv) 그대로 |
| `unit` (query) | `day` 또는 생략. 그 외는 400 (`field: unit`) |
| 본문 | `ExportFormatRequest` — `from`, `to`(포함), `format`. 허용 키는 `docs/REQUEST_BODY_CONTRACT.md` 와 같다 |
| `format` | `xlsx`(기본) · `xls` · `excel` → 전부 xlsx. `csv` 는 400 (`field: format`) — 시트 3장·차트를 담을 수 없다 |
| `from`/`to` 생략 | 종료일 오늘, 시작일 오늘−7일 — 화면 기본값(`recentRange(7)`)과 같다 |
| 기간 상한 | 366일. 넘으면 400 (`field: from`) — 추이 차트 상한과 같다 |
| 권한 | 메뉴 `prod-result` (품질보증팀·생산관리팀·경영진·통합관리자). 제조팀은 403 |
| 응답 | `200` xlsx 바이너리. `Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` |
| 파일명 | `Content-Disposition: attachment; filename*=UTF-8''실적_집계_전체_{from}_{to}.xlsx` — 프론트 `filenameOf()` 가 그대로 쓴다 |
| 오류 | 기존 공통 JSON(`{success:false, message, error:{field}}`) — 프론트 `downloadFromServer` 가 `message` 를 토스트로 보여 준다 |

## 2. 문서 구성 (시트 3장)

### `조회 요약`
항목/값 두 열. 화면 · 조회 시작일/종료일/일수 · 집계 단위(일별) · 제품(전체) · 설비(전체) · 집계 기준 ·
투입/양품/불량 합계 · 불량률 · 수율 · 평균 가동률(일별 가동률의 평균, 측정 없으면 빈 칸) · 비가동 시간 합계 ·
실적 있는 일수 · 제품 소계 행 수 · 설비 행 수 · **권한 마스킹 항목** · 내려받은 사용자 · 생성 시각 · 빈 칸의 뜻.

### `일별 추이`
`일자 · 생산량 · 불량량 · 불량률(%)` 오름차순 표 + 차트 2개(생산량·불량량 막대, 불량률 꺾은선).
수량 권한이 없으면 막대 차트가, 비율 권한이 없으면 꺾은선이 빠진다 — 빈 칸을 0 으로 그리지 않는다.

### `집계 결과`
열 12개 고정: `일자 · 제품명 · 공장 · 공정 · 설비 코드 · 설비명 · 투입 수량 · 양품 수량 · 불량 수량 · 불량률(%) · 가동률(%) · 비가동 시간(분)`

- **트리 전체**(일자 → 제품 → 설비)를 평평하게 담는다. 페이지 제한 없음(7일 ≈ 3,600행, 하루 ≈ 500행).
- 깊이는 **엑셀 행 그룹(outline)** 으로 옮겼다 — 일자 행 0단계, 제품 소계 1단계, 설비 행 2단계. 접기 버튼은 부모 행 위.
  일자 행은 굵게+회색, 제품 소계는 연노랑, 설비 행은 무늬 없음.
- 필터가 되도록 **일자·제품명은 자식 행에도 채운다** (화면은 부모에만 보이지만 엑셀에서는 필터 기준이 필요하다).
- 정렬은 화면과 같다 — 일자 내림차순, 제품은 투입 수량 내림차순, 설비는 불량률 내림차순.
- 머리글 고정, 자동 필터, 숫자 서식(`#,##0` / `0.00`).

## 3. 값의 출처와 규칙

| 층 | 출처 | 비고 |
| :--- | :--- | :--- |
| 일자 행 | `ProductionRepository.findResults(unit=day)` | 화면 표·추이 차트와 같은 행. 가동률·비가동은 여기만 있다 |
| 제품 소계 | 설비 행을 (일자, 제품) 으로 더한 값 | `/dashboard/process/product-production` 과 같은 원장·같은 식이라 합이 같다 |
| 설비 행 | `DashboardAiRepository.findLineProductsByDay` | `/dashboard/ai/line-products` 의 본문 SQL 에 일자 그룹만 더한 것 |

- 세 층 모두 `mes.tb_pop_label_hist`(plant_cd=PL01, del_flg='N', ins_date 일 단위) 하나에서 나온다. **출하 원장이 아니다.**
  로컬 실측(2026-09-05~11): 일자 합 = 제품 소계 합 = 설비 행 합, DB 일별 합계와 정확히 일치(불일치 0).
- **제품명** = `tb_prod_product.model_nm`. 품목 매핑이 없는 품목은 이름이 없어 `item_cd` 를 그대로 적는다(지어내지 않는다).
  화면은 이 품목을 제품 소계에서 빠뜨리지만(product-production 이 INNER JOIN) 내려받기는 담는다 — 로컬 기간에는 1종·수량 0.
- **설비 코드/설비명** 은 API 원본 `eqptCd` / `eqptNm` 그대로 분리. 이름이 없으면 코드로 대신 적는다(SQL 의 coalesce).
- **공장** 은 작업장 이름의 `(M-n공장)` 표기에서만 읽는다(`WorkcenterNames.plantOf`). 표기가 없으면 빈 칸 — 설비 번호로 가르지 않는다.
- **공정** 은 공장 열과 **정확히 같은** 괄호 표기만 지운다(`WorkcenterNames.withoutPlant`). `A2-PLATING(전해 라인)` 같은 다른 괄호는 그대로.
  화면 `equipmentRows()` 의 정규식과 같은 규칙이며, 서버 `DashboardAiService.plantOf` 도 같은 유틸을 쓴다.
- **가동률·비가동 시간** 은 제품·설비 행에 출처가 없어 항상 빈 칸. 일자 행도 지표가 없으면 빈 칸(로컬 DB 는 지표·비가동 원장이 비어 있어 전부 빈 칸).
- **권한 마스킹** — `qty`(투입·양품·불량) · `yield`(불량률·수율) 를 표(`maskResultRows`)와 같은 기준으로 가린다. 가린 칸은 빈 셀,
  요약 시트 "권한 마스킹 항목" 에 밝히고 다운로드 이력 `blind_cnt`/`tb_rpt_download_blind` 에 남긴다. 가동률·비가동은 마스킹 대상이 아니다(표와 같다).
- null 은 **빈 셀**이다. `0` 도 `""` 도 쓰지 않는다.

## 4. 다운로드 이력 (`ax.tb_rpt_download_log`)

| 컬럼 | 값 |
| :--- | :--- |
| `target_nm` | `생산 실적 집계(화면 전체)` (예전 경로는 `생산 실적 집계`) |
| `menu_id` / `format_cd` | `prod-result` / `XLS` |
| `scope_desc` | `screen from=…, to=…, unit=day` (100자 컬럼) |
| `row_cnt` | 트리 행 수(일자+제품+설비) |
| `blind_cnt` | 가린 데이터 항목 수. 항목별 셀 수는 `tb_rpt_download_blind` |
| `file_nm` / `file_size` | 응답 파일명 / 바이트 |
| `params_json` | `scope, from, to, unit, modelCd(null), lineCd(null), format, sheets[3], dayRows, productRows, equipmentRows` |

파일을 먼저 만들고 이력을 남긴다 — 만들다 실패하면 `DONE` 이 기록되지 않는다. 감사 로그(`RAW_VIEW`)도 함께 남는다.

## 5. 테스트

- `ProductionResultScreenExportTest` — 만든 xlsx 를 다시 읽어 시트·12열·빈 셀·행 그룹·차트 수·마스킹·요약·공정명 규칙을 고정 (7건)
- `ProductionResultExportControllerTest` — scope=screen 응답(헤더·파일명·3장 문서)과 다운로드 이력 인자, 예전 경로 유지, 400 세 가지 (3건)
- 로컬 8080 실측 — 10000/10001/10002/10005 200 · 10003(제조팀) 403 · scope 오타/csv/week 400 · 이력 4건 기록 · 파일 3,629행 합 검증 통과

## 6. 바뀐 파일

- `controller/ProductionController.kt` — `scope` query 분기, `resultsExportScreen`
- `service/ProductionService.kt` — `getResultScreenExport` (트리 조립·마스킹·요약)
- `service/ProductionResultScreenExport.kt` — 데이터 모델 + `ProductionResultScreenWorkbook`(시트 3장·차트)
- `service/ExportService.kt` — `xlsx(bytes, fileName)` 공개 래퍼
- `repository/DashboardAiRepository.kt` — `findLineProductsByDay`
- `common/util/WorkcenterNames.kt` — 공장명 파싱·공정명 중복 제거(서버 단일 규칙). `DashboardAiService.plantOf` 가 위임
