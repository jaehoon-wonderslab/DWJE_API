# 맥북 PostgreSQL 데이터베이스 설계와 조회 자습서

## 1. 이 문서의 범위

이 문서는 2026년 9월 28일 맥북의 `dwjedb`에서 확인한 구조와 데이터 규모를 설명합니다. 개발자가 데이터의 출처, 주요 테이블의 관계, 날짜 기준과 접근 권한을 이해할 때 참고할 수 있습니다. 확인에는 읽기 전용 조회만 사용했습니다. 계정 정보, 비밀번호, 사용자 정보와 문서 원문은 싣지 않았습니다.

DB 설계 내용은 `../Postgresql 스키마/mes_db_query.sql`, `ai_db_query.sql`, `ai_db_vector_query.sql`과 API 저장소의 `src/main/resources/db/V*.sql`에서 확인할 수 있습니다. 파일의 설명과 현재 DB가 다르면 **실제 DB에서 확인한 구조를 기준으로 합니다.** 예를 들어 설계 파일에 적힌 버전과 달리, 현재 맥북의 PostgreSQL 버전은 16.15입니다.

## 2. 먼저 알아둘 구조

`dwjedb`는 데이터를 용도에 따라 네 스키마로 나눕니다.

| 스키마 | 저장하는 데이터 | 테이블 수 | 뷰 수 |
| :--- | :--- | ---: | ---: |
| `common` | MES와 AX에서 함께 쓰는 코드 자료형 11종 | 0 | 0 |
| `mes` | MSSQL `MESDB_M.dbo`에서 복제한 품목·작업장 정보와 생산·불량·재고 기록 | 13 | 0 |
| `ax` | 계정·권한, 제품 매핑, 채팅, 보고서, 알림, 지표, 연동 이력 | 68 | 3 |
| `vec` | 검색 대상 문서, 문서 본문 조각, 임베딩, 검색 기록 | 14 | 7 |

맥북 DB에는 `vector` 0.8.6과 `pg_trgm` 1.6이 설치되어 있습니다. `vector`는 문서의 의미가 비슷한지 찾는 데, `pg_trgm`은 일부 글자가 일치하는지 찾는 데 사용합니다.

### 용어 정리

**스키마(schema)**는 한 데이터베이스 안에서 테이블을 용도별로 묶는 이름을 의미합니다. `mes.tb_pop_label_hist`는 생산 데이터에, `ax.tb_ai_chat_log`는 애플리케이션 데이터에 속합니다.

**도메인(domain)**은 같은 종류의 값을 여러 테이블에서 같은 자료형으로 쓰기 위한 정의를 의미합니다. 예를 들어 `common.d_user_id`는 최대 30자의 사용자 아이디에 사용합니다. MES와 AX의 사용자 아이디 컬럼이 이 도메인을 공유합니다.

**LOT**는 생산 과정을 함께 추적하는 제조 단위를 의미합니다. 이 DB에서는 사업부 코드, 작업장 코드, LOT 번호, LOT 내 일련번호를 합쳐 라벨 이력 한 건을 구분합니다.

**청크(chunk)**는 문서를 검색하고 답변에 출처를 표시할 수 있도록 나눈 글 조각을 의미합니다. 문서 한 건에 청크 여러 개가 연결될 수 있습니다.

## 3. 데이터가 들어오는 경로

1. 외부 MES의 품목·작업장 정보와 생산 기록을 `mes`의 13개 테이블에 복제합니다. 원본의 컬럼과 기본 키를 유지합니다. 복제 과정에서 연결된 테이블의 적재 순서가 달라질 수 있어, 원본에 없는 외래 키는 추가하지 않았습니다.
2. `ax`는 MES 품목을 제품 모델과 연결하고 계정, 부서, 화면 권한, 업무 설정을 관리합니다. `ax.tb_sync_map`에는 복제 대상의 연결 방법을, `ax.tb_sync_run`과 `ax.tb_sync_job`에는 실행 기록을 남깁니다.
3. `vec`는 문서와 그 버전, 검색용 본문 조각, 임베딩을 저장합니다. 검색 결과를 답변에 사용하기 전에 사용자의 문서 열람 권한을 확인합니다.
4. 채팅 질문과 답변은 `ax`에 저장합니다. 문서 검색 기록은 `vec`에 남깁니다. 보고서 파일은 요청할 때 만들고, 다운로드 기록은 `ax`에 저장합니다.

위 내용은 데이터가 **어디에 저장되는지**를 설명합니다. 복제나 문서 수집 작업이 지금 실행 중이라는 뜻은 아닙니다.

## 4. MES 데이터 읽기

### 4.1 기본 정보와 생산 기록

| 테이블명 | 저장 내용 | 관련 정보 |
| :--- | :--- | :--- |
| `mes.tb_md_workcenter` | 작업장 목록 | 사업부·작업장 코드 |
| `mes.tb_md_item` | 품목 목록 | 생산 기록에 쓰인 품목 코드 |
| `mes.tb_md_defect` | 불량 코드와 이름 | 불량 등록 기록 |
| `mes.tb_md_eqpt`, `mes.tb_md_mold` | 설비와 금형 목록 | LOT에 기록된 설비·금형 코드 |
| `mes.tb_pop_label_hist` | 작업이 끝난 LOT의 양품·불량 수량 | 생산량 계산에 사용하는 기록 |
| `mes.tb_pop_defect_hist` | LOT의 불량 종류별 수량 | 라벨 기록에 연결된 불량 정보 |
| `mes.tb_pop_stock` | LOT의 현재 재고 위치와 수량 | 현재 재고 조회 |
| `mes.tb_pop_stock_hist` | LOT의 재고 이동 기록 | 이전 위치와 상태 |

`mes.tb_pop_label_hist`에서는 `(plant_cd, wc_cd, lot_no, serial_no)` 네 값을 합쳐 LOT 기록 한 건을 구분합니다. `normal`은 양품 수량이고 `defect`는 불량 수량입니다. 불량 종류별 수량은 같은 네 값을 사용해 `mes.tb_pop_defect_hist`에서 찾습니다. `del_flg = 'Y'`인 기록은 일반적인 생산 실적 조회에서 제외합니다.

현재 재고는 `mes.tb_pop_stock`에서, 이전 재고 이동은 `mes.tb_pop_stock_hist`에서 확인합니다. 재고 이동 기록에는 원본부터 기본 키가 없으며, 맥북 DB에도 기본 키가 없습니다. 조회 결과에 표시되는 행 순서로 LOT를 식별해서는 안 됩니다.

MES 테이블은 서로 연결되는 값이 있어도 이를 외래 키로 검사하지 않는 경우가 많습니다. 테이블을 조인할 때는 LOT 번호만 사용하지 말고 사업부와 작업장 코드도 함께 확인하십시오. `ax`와 `vec`에는 일부 연결을 외래 키로 검사하는 테이블이 있습니다.

### 4.2 현재 규모

아래는 각 테이블의 행 수입니다. 작은 테이블은 `COUNT(*)`로 직접 셌습니다. 수백만 건이 있는 테이블은 PostgreSQL에 저장된 통계값을 사용했으므로 실제 행 수와 다를 수 있습니다.

| 테이블명 | 행 수 | 집계 방식 |
| :--- | ---: | :--- |
| `mes.tb_md_workcenter` | 39 | 직접 집계 |
| `mes.tb_md_item` | 2,016 | 직접 집계 |
| `mes.tb_md_defect` | 172 | 직접 집계 |
| `mes.tb_pop_label_hist` | 약 760만 | 통계값 기준 |
| `mes.tb_pop_defect_hist` | 약 908만 | 통계값 기준 |
| `mes.tb_pop_stock_hist` | 약 2,471만 | 통계값 기준 |

생산·불량·재고 기록은 양이 많습니다. 날짜를 조건에 넣어 필요한 기간만 조회하십시오. 날짜 검색을 돕는 인덱스가 마련되어 있습니다.

## 5. 날짜와 시간 기준

### 5.1 MES 시각과 AX·문서 시각

MES의 `ins_date` 등은 **시간대 정보가 없는 `timestamp`**입니다. 값은 한국시간으로 기록하지만, 컬럼 자체에는 시간대가 표시되지 않습니다. 반면 채팅 시각인 `ax.tb_ai_chat_log.asked_at`과 문서 등록 시각인 `vec.tb_doc.ins_date`는 시간대 정보를 처리하는 **`timestamptz`**입니다. 확인 당시 DB 세션의 시간대는 `Etc/UTC`였습니다.

채팅·문서 시각을 한국시간으로 표시할 때는 `asked_at AT TIME ZONE 'Asia/Seoul'`처럼 변환합니다. MES 시각과 `timestamptz` 값을 비교할 때는 `mes.ins_date AT TIME ZONE 'Asia/Seoul'`처럼 MES 값의 시간대를 명시합니다. 아래는 MES 기록을 업무일 기준으로 조회하는 예입니다.

```sql
BEGIN READ ONLY;

SELECT count(*)
FROM mes.tb_pop_label_hist
WHERE ins_date >= timestamp '2026-09-21 08:00:00'
  AND ins_date <  timestamp '2026-09-23 08:00:00'
  AND del_flg = 'N';

ROLLBACK;
```

이 쿼리는 **9월 22일과 23일, 이틀간의 생산 기록**을 셉니다. 9월 22일 업무일은 21일 08:00부터 22일 08:00 직전까지이고, 23일 업무일은 그다음 24시간입니다. 따라서 시작은 21일 08:00 이상, 끝은 23일 08:00 미만으로 지정합니다. 끝 시각을 제외하면 연속된 기간을 조회해도 같은 기록을 두 번 세지 않습니다.

### 5.2 같은 날짜를 입력해도 조회 범위가 다른 경우

자연어 질문에서 “9월 22일부터 23일까지”라고 하면 **두 업무일을 모두** 조회합니다. `AiBusinessPeriod`는 이를 `9월 21일 08:00 이상 ~ 9월 23일 08:00 미만`으로 바꿉니다.

일반 공정 기간 화면에서 시작일을 9월 22일, 종료일을 9월 23일로 선택하면 **9월 23일 업무일 하루**를 조회합니다. `BusinessDay.ofRange`는 시작일을 구간의 시작 시각으로 받아 `9월 22일 08:00 이상 ~ 9월 23일 08:00 미만`으로 바꿉니다.

두 입력은 날짜가 같아 보여도 조회하는 시간이 다릅니다. 날짜 조건을 직접 작성할 때는 해당 기능이 어느 방식을 사용하는지 확인하십시오. 감사·다운로드·동기화·채팅 기록에는 생산 교대일 기준을 적용하지 않습니다.

## 6. AX 애플리케이션 데이터 읽기

### 6.1 계정과 권한

계정은 `ax.tb_sys_user`, 부서는 `ax.tb_sys_dept`, 화면 정보는 `ax.tb_sys_menu`에 저장합니다. 부서별 화면 접근 권한은 `ax.tb_sys_dept_menu_perm`에 저장합니다. `ax.tb_sys_user_menu_grant`는 특정 계정에 추가로 허용한 화면만 기록합니다. 수량이나 불량률 같은 데이터 항목을 볼 수 있는지는 `ax.tb_sys_data_field`와 `ax.tb_sys_dept_data_perm`으로 별도 확인합니다. 화면에 접근할 수 있어도 그 안의 모든 값을 볼 수 있는 것은 아닙니다.

문서 열람 권한은 문서의 공개 범위인 `vec.tb_doc.scope_cd`, 담당 부서, `vec.tb_doc_dept_perm`, 민감 데이터 항목을 기준으로 확인합니다. DB에서 원문을 직접 조회하면 API의 권한 검사와 민감 정보 가리기 기능을 거치지 않습니다. 공유할 자료에는 테이블 구조와 집계 결과만 사용하십시오.

### 6.2 채팅, 보고서, 알림

`ax.tb_ai_chat_log`에는 질문과 답변, 질문의 분류 결과, 응답 시간, 평가, 답변 근거 요약 등이 저장됩니다. `ax.tb_ai_chat_agent`에는 각 채팅에서 호출한 에이전트가 기록됩니다. 현재 채팅 로그에는 답변 근거를 요약하는 `evidence_summary`와 답변하지 못한 이유를 기록하는 `unanswered_reason` 컬럼이 있습니다. 두 컬럼의 자료형은 `text`입니다.

보고서 파일을 계속 DB에 저장하지는 않습니다. 다운로드할 때마다 `ax.tb_rpt_download_log`에 시각, 대상, 파일 형식, 조회 조건, 가려진 항목 수 등을 기록합니다. `ax.tb_rpt_download_blind`에는 보고서에서 제외한 데이터 항목을 기록합니다. 사용자가 올린 원본 자료는 `ax.tb_dash_upload_doc`와 `ax.tb_dash_upload_ver`에서 별도로 관리합니다.

알림을 발생시키는 조건은 `ax.tb_alm_cond`에 저장합니다. 조건에 맞아 발생한 알림은 `ax.tb_alm_alert`에, 발송 대기 항목은 `ax.tb_alm_send_queue`에 저장합니다. `ax.tb_alm_send_log`에는 발송 시도를 기록합니다. 발송에 실패해도 발생한 알림의 기록은 남습니다.

### 6.3 제품, 용어, 지표

MES의 품목 정보는 `mes.tb_md_item`에 있습니다. 화면이나 분석에 쓰는 제품 정보는 `ax.tb_prod_product`에 있고, `ax.tb_prod_item_map`이 두 정보를 연결합니다. 제품별 생산량을 계산할 때는 이 연결 정보를 사용하는지 확인하십시오. 연결되지 않은 품목을 제외할지, 품목 코드로 표시할지는 해당 조회 기능의 처리 규칙을 확인하십시오.

공식 용어는 `ax.tb_gls_term`에, 현장에서 쓰는 비슷한 표현은 `ax.tb_gls_variant`에 저장합니다. 질문을 검색하기 전에 표현을 맞추는 데 사용합니다. 원본 데이터의 값은 바꾸지 않습니다. 측정된 지표값은 `ax.tb_met_metric_value`에, 비교 기준은 `ax.tb_met_metric_std`에 저장합니다. 알림이 발생하는 기준 수치와 실제 측정값은 구분해서 읽어야 합니다.

### 6.4 현재 규모와 설계 상태

| 테이블명 | 행 수 |
| :--- | ---: |
| 계정 `ax.tb_sys_user` | 6 |
| 부서 `ax.tb_sys_dept` | 6 |
| 화면 `ax.tb_sys_menu` | 28 |
| 데이터 접근 항목 `ax.tb_sys_data_field` | 7 |
| 채팅 이력 `ax.tb_ai_chat_log` | 64 |
| 보고서 다운로드 이력 `ax.tb_rpt_download_log` | 44 |
| 데이터 복제 연결 정보 `ax.tb_sync_map` | 13 |
| 발생한 알림 `ax.tb_alm_alert` | 194 |

저장소에는 `V44__ai_chat_debug.sql` 파일이 있습니다. 그러나 확인 당시 맥북 DB에는 이 파일이 정의한 채팅 진단 테이블이 **없었습니다**. SQL 파일이 있어도 DB에 적용되었다는 뜻은 아닙니다. 테이블이 실제로 있는지는 `to_regclass` 등으로 확인하십시오. 이 문서를 작성하면서 DB 구조를 변경하지는 않았습니다.

## 7. 문서 검색 저장소 읽기

`vec.tb_doc`에는 문서 한 건의 제목, 날짜 등 문서 정보가 저장됩니다. `doc_date`는 문서의 기준일이고, `ins_date`는 DB에 등록한 시각입니다. 따라서 “작년에 작성된 문서”의 건수를 셀 때는 문서 기준일과 DB 등록일 중 어느 날짜를 사용할지 먼저 정해야 합니다. `doc_date`의 자료형은 `date`, `ins_date`의 자료형은 `timestamptz`입니다.

`vec.tb_doc_version`에는 문서에서 글을 추출하고 검색용 조각과 임베딩을 만든 버전이 저장됩니다. `vec.tb_doc_chunk`에는 검색과 출처 표시에 사용할 문서의 글 조각이 저장됩니다. 각 조각의 `embedding`은 1,024개 숫자로 이루어진 벡터입니다. 단어 검색용 `tsv`와 일부 글자가 일치하는 자료를 찾는 인덱스도 있습니다.

`vec.tb_doc_dept_perm`과 `vec.tb_doc_data_field`는 문서 열람 권한과 민감 데이터 항목을 확인할 때 사용합니다. 검색 기록은 `vec.tb_query_log`에, 검색에서 찾은 문서 조각과 인용 기록은 `vec.tb_query_hit`에 저장합니다.

확인 당시 삭제 표시가 없는 문서는 **1,476건**, 문서의 글 조각은 **40,340건**이었습니다. 문서 모두 `EMBEDDED` 상태였고, 모든 글 조각에 임베딩이 있었습니다. 문서 기준일이 비어 있는 문서는 0건이었습니다. 보존 기한을 나타내는 `retention_until`에 값이 들어 있는 문서도 0건이었습니다. 이 컬럼이 있다고 해서 보존 기한이 지난 문서를 자동으로 처리하는 작업이 실행 중이라고 단정할 수는 없습니다.

문서 건수를 셀 때는 **문서 테이블의 행 수**를 사용합니다. 문서와 글 조각을 연결한 뒤 `COUNT(*)`를 사용하면 한 문서가 조각 수만큼 여러 번 세어집니다. 사용자가 볼 수 있는 문서의 건수는 해당 사용자의 권한을 적용한 조회 결과로 계산합니다.

## 8. 읽기 전용 조회 실습

아래 SQL은 맥북 DB에서 구조와 건수를 확인하는 예입니다. 모두 읽기 전용 트랜잭션으로 실행합니다. 조회 결괏값에는 문서 원문이나 사용자 정보를 포함하지 않습니다.

### 실습 1. 스키마와 테이블 확인

```sql
BEGIN READ ONLY;

SELECT table_schema, count(*) AS table_count
FROM information_schema.tables
WHERE table_schema IN ('mes', 'ax', 'vec')
  AND table_type = 'BASE TABLE'
GROUP BY table_schema
ORDER BY table_schema;

SELECT to_regclass('ax.tb_ai_chat_log') AS chat_log,
       to_regclass('ax.tb_ai_chat_debug') AS chat_debug;

ROLLBACK;
```

`to_regclass`가 `NULL`을 반환하면 현재 DB에 해당 테이블이 없습니다. 저장소에 SQL 파일이 있는지와 DB에 테이블이 있는지는 별도로 확인해야 합니다.

### 실습 2. 컬럼의 실제 자료형 확인

```sql
BEGIN READ ONLY;

SELECT column_name, data_type, is_nullable
FROM information_schema.columns
WHERE table_schema = 'vec'
  AND table_name = 'tb_doc'
  AND column_name IN ('doc_id', 'doc_date', 'ins_date', 'retention_until')
ORDER BY ordinal_position;

ROLLBACK;
```

### 실습 3. 문서 등록 연도 집계

```sql
BEGIN READ ONLY;

SELECT extract(year FROM ins_date AT TIME ZONE 'Asia/Seoul')::integer AS registered_year,
       count(*) AS document_count
FROM vec.tb_doc
WHERE del_flg = 'N'
GROUP BY registered_year
ORDER BY registered_year;

ROLLBACK;
```

이 쿼리는 문서가 **DB에 등록된 연도별 건수**를 셉니다. 문서 기준일(`doc_date`)의 연도별 건수와는 다를 수 있습니다. 특정 사용자가 볼 수 있는 문서 건수를 구하려면 문서 권한과 검색 준비 상태도 확인해야 합니다.

### 실습 4. 시각 자료형 확인

```sql
BEGIN READ ONLY;

SELECT table_schema, table_name, column_name, data_type
FROM information_schema.columns
WHERE (table_schema = 'mes' AND table_name = 'tb_pop_label_hist' AND column_name = 'ins_date')
   OR (table_schema = 'ax' AND table_name = 'tb_ai_chat_log' AND column_name = 'asked_at')
   OR (table_schema = 'vec' AND table_name = 'tb_doc' AND column_name = 'ins_date')
ORDER BY table_schema;

ROLLBACK;
```

## 9. 설계 파일과 실제 DB 확인 방법

1. **현재 DB 구조를 확인합니다.** `information_schema`와 `pg_catalog`에서 테이블, 컬럼, 인덱스, 제약 조건을 조회합니다.
2. **설계 파일을 읽습니다.** 기본 스키마 SQL의 주석과 API의 버전별 SQL을 확인합니다. 기본 스키마 파일에는 나중에 제거된 테이블의 설명이 남아 있을 수 있습니다.
3. **API의 조회 방식을 확인합니다.** 저장소에서 Repository와 날짜 변환 코드를 읽습니다. 기간 조건, 권한 검사, 삭제 표시를 어디서 적용하는지 확인합니다.
4. **공유할 내용을 점검합니다.** 사용자 정보, 문서 원문, 질문, 비밀번호, 접근 토큰(access token)은 예제나 공유 문서에 복사하지 않습니다. 구조와 집계 결과만 사용합니다.

이 문서의 행 수와 설치 버전은 **2026년 9월 28일에 확인한 맥북 DB 상태**입니다. 운영 DB의 상태와 다를 수 있습니다.
