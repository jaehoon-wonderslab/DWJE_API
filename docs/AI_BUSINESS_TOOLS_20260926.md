# AI 업무 데이터 도구 계약 (로컬 코드)

LLM은 질문을 먼저 판단해 OpenAI `tool_calls`로 도구와 인자를 선택한다. API는 도구 이름과 ISO 날짜·limit를 검사하고, 고정 SQL과 바인드 파라미터만 실행한다. 문서 근거 검색은 별도로 유지한다. 운영 DB에서 결과를 확인한 문서는 아니다.

생산·불량·AOI 도구로 분류된 질문은 업무 DB 도구 결과만 답변 근거로 사용하며, 오래되거나 범위가 다른 벡터 문서 검색을 함께 수행하지 않는다. DB 조회가 빈 결과면 빈 결과임을 그대로 알리고 문서 검색 결과로 대체하지 않는다.

| 화면 / 기존 조회 근거 | 지원 도구와 질문 예시 | 실행 결과 |
|---|---|---|
| AI 통합 대시보드 `DashboardAiController/Service/Repository` (summary, defect-trend, defect-composition, process-yield) | `production_period_compare`: “이번 달 생산량과 불량률은?”; `defect_top`: “9월 불량 상위 10개” | 기간 생산·불량·수율 집계, 불량 유형 순위 |
| 공정 및 제품 대시보드 `DashboardProcessController/Service/Repository` (period, product-production, defect-composition) | `production_period_compare`: “이번 달과 지난달 공정 실적 비교”; `defect_rate_top`: “9월 제품별 불량률 상위 20” | 공정 실적, 모델별 가중 불량률 |
| 생산 모니터링 `ProductionController/Service/Repository` (monitor/summary, monitor/equipments) | `production_period_compare`: “오늘 생산 실적은?” | 확정 생산 실적만 지원. 실시간 설비 상태는 미지원 |
| 실적 집계/조회 `ProductionController/Service/Repository` (results, results/trend) | `production_product_list`: “9월 20~22일 생산 제품 목록을 일자별로”; `production_period_compare`: “전월 대비 생산량” | 날짜별 모델 생산량, 기간 실적 |
| 불량 현황 조회 `QualityController/QualityDefectService/QualityRepository` (defects/by-type, by-product, tree) | `defect_top`: “불량 종류 상위 10”; `daily_product_defect`: “9월 20일~22일까지 생산된 불량 종류 및 제품 구분을 일자별로 정리” | 유형 순위, 일자×모델×유형 표 |
| AOI 판정 분석 `AoiDimensionController/AoiDimensionService/AoiDimensionRepository` (summary) | `aoi_dimension_summary`: “S120 작업장 AOI 판정 요약”, “S110 작업장의 9월 22일 AOI 불량률” | 기존 화면의 권한·마스킹을 거친 현재 기간 치수 요약. 작업장 미지정 시 조회 중지 |

## `daily_product_defect`

- JSON Schema: `from`, `to`는 필수 `YYYY-MM-DD`; `limit`는 선택 정수 1~200, 기본 200. LLM에 예시를 제공한다. 연도 없는 월일은 모델에게 Asia/Seoul 오늘 이전의 최근 유효 연도로 해석하도록 지시하고, API는 반환된 ISO 날짜가 미래인지 검사한다.
- 범위: 양 끝 업무일 포함, 최대 31개 업무일. 9월 20~22일은 `[9월 19일 08:00, 9월 22일 08:00)` (타임존 없는 MES 벽시계). SQL 날짜 분류는 16시간 이동 후 날짜를 쓴다.
- 집계: 라벨 원장을 먼저 기간·공장으로 제한하고 전체 LOT 키(`plant_cd,wc_cd,lot_no,serial_no`)로 유형 이력을 결합한다. `DefectSql.excludeNonProduction`을 적용하고 유형 수량 비중으로 라벨 불량량을 안분한다. 차액은 `유형 미상` 행이다. 제품 매핑이 있으면 모델과 제품군, 없으면 품목 코드를 모델 구분값으로 표시한다.
- 권한: 수량 권한(`qty`)이 없으면 조회를 실행하지 않고 `DENIED_FIELDS`를 반환한다. 데이터가 없으면 `EMPTY`다. 표와 LLM 근거에는 내부 SQL·스키마 이름을 넣지 않는다. 수율은 이 도구에 포함되지 않는다.
- 응답: `POST /api/ai/tools/daily_product_defect`는 `rows`, `totalCount`, `limit`를 반환한다. 채팅 `/api/v1/ai/chat/ask`는 근거 텍스트와 `blocks[type=table]`을 반환하고, `/api/ai/chat`이 같은 근거를 LLM에 넘긴다. 응답 행은 제한되며 `totalCount > rows.size`이면 일부만 표시된 것이다.

## `aoi_dimension_summary`

- JSON Schema: `wcCd` 필수(질문에 없으면 빈 문자열), `from`·`to`·`eqptCd` 선택. 작업장 코드를 추측하지 않는다. 빈 `wcCd`는 `WORKCENTER_REQUIRED`이며 원천 조회를 실행하지 않는다.
- `AoiDimensionService.getSummary`를 직접 재사용한다. `QC_AOI` 메뉴 권한, 화면 설정의 `maxDays`, 안전한 MSSQL 조회 및 `qty`/`yield` 마스킹은 기존 서비스가 적용한다. AOI 기간 경계도 기존 화면의 `BusinessDay.ofRange` 계약을 따른다.
- LLM 근거와 표는 기존 서비스가 공개한 현재 기간 집계에서 작업장 전체와 최대 49개 설비의 `measCnt`, `failCnt`, `failRate`, `explainedRate`만 투영한다. 설정의 한계값, FAI 상세, serial, 원시 행은 포함하지 않는다.

미지원: 실시간 설비 상태, 설비/공정/고객별 임의 조합, AOI raw serial/detail/limits/예측, 임의 SQL, 31일 초과 일자×모델×유형 조회. 해당 조회는 검증된 고정 SELECT와 별도 권한·상한 계약을 추가하기 전까지 문서 검색/미지원 응답으로 처리한다.
