# 덕우전자 AX DB 설계서

## 1. 문서 개요

이 문서는 2026년 9월 28일 맥북의 `dwjedb`에서 직접 조회한 데이터베이스 구조를 설명합니다. 스키마와 테이블의 용도, 컬럼의 자료형과 DB 주석을 확인할 때 사용합니다. 확인에는 읽기 전용 조회만 사용했습니다. 계정 정보, 비밀번호, 사용자 정보와 문서 원문은 싣지 않았습니다.

이 문서의 테이블·컬럼 목록과 자료형·주석은 맥북 DB의 시스템 카탈로그에서 직접 조회했습니다. SQL 파일의 내용과 다르면 **현재 DB에서 확인한 값**을 기준으로 합니다. 확인 시각은 2026년 9월 28일 15:09(한국시간)입니다. 표에는 데이터 행의 실제 값이나 자격증명을 포함하지 않았습니다.

## 2. DB 구성

`dwjedb`는 데이터를 용도에 따라 네 스키마로 나눕니다. `common`에는 테이블 대신 공통 자료형인 도메인이 있습니다.

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

## 3. 데이터 처리 흐름

1. 외부 MES의 품목·작업장 정보와 생산 기록을 `mes`의 13개 테이블에 복제합니다. 원본의 컬럼과 기본 키를 유지합니다. 복제 과정에서 연결된 테이블의 적재 순서가 달라질 수 있어, 원본에 없는 외래 키는 추가하지 않았습니다.
2. `ax`는 MES 품목을 제품 모델과 연결하고 계정, 부서, 화면 권한, 업무 설정을 관리합니다. `ax.tb_sync_map`에는 복제 대상의 연결 방법을, `ax.tb_sync_run`과 `ax.tb_sync_job`에는 실행 기록을 남깁니다.
3. `vec`는 문서와 그 버전, 검색용 본문 조각, 임베딩을 저장합니다. 검색 결과를 답변에 사용하기 전에 사용자의 문서 열람 권한을 확인합니다.
4. 채팅 질문과 답변은 `ax`에 저장합니다. 문서 검색 기록은 `vec`에 남깁니다. 보고서 파일은 요청할 때 만들고, 다운로드 기록은 `ax`에 저장합니다.

위 내용은 데이터가 **어디에 저장되는지**를 설명합니다. 복제나 문서 수집 작업의 현재 실행 상태를 나타내지는 않습니다.

## 4. MES 데이터 구조

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

## 5. common 스키마

`common`에는 여러 스키마에서 공통으로 사용하는 도메인 11개가 있습니다. 도메인은 컬럼에 적용할 자료형을 정의하며, 자체적으로 데이터 행을 저장하지 않습니다.

| 도메인명 | 기본 자료형 | NULL 허용 | DB 주석 |
| :--- | :--- | :---: | :--- |
| `d_defect_cd` | `character varying(50)` | Y | 불량 코드. 원본은 마스터 varchar(30) / 이력 varchar(50) 로 불일치 — 조인 통일을 위해 50 채택 |
| `d_eqpt_cd` | `character varying(50)` | Y | 설비 코드 |
| `d_grade` | `character varying(30)` | Y | 등급. 현재 R / T 사용 (성격은 유사하나 차이 있음) |
| `d_item_cd` | `character varying(50)` | Y | 품목 코드 |
| `d_lot_no` | `character varying(8)` | Y | LOT 번호 |
| `d_lot_serial` | `character varying(5)` | Y | LOT 내 SERIAL 번호. 설비/금형의 SERIAL_NO(제조 일련번호)와 다른 개념이므로 도메인을 분리 |
| `d_mold_cd` | `character varying(50)` | Y | 금형 코드 |
| `d_plant_cd` | `character varying(4)` | Y | 사업부 코드 (PL01=모바일 사업부, PL03=전장 사업부). MESDB 분리 후 PL01 만 사용 |
| `d_user_id` | `character varying(30)` | Y | 사용자 ID(사번). ax.tb_sys_user.user_id 및 MES 의 INS_USER/UPD_USER 와 동일 도메인 |
| `d_wc_cd` | `character varying(10)` | Y | 작업장(Work Center) 코드 |
| `d_yn` | `character(1)` | Y | Y/N 플래그 |

## 6. mes 스키마

MES 원본에서 복제한 기본 정보와 생산·불량·재고 기록을 저장합니다. 현재 DB에서 확인한 테이블은 13개, 컬럼은 162개입니다.

**테이블명:** `mes.tb_md_defect`

**테이블 설명:** 불량 코드 마스터 — 불량명 158종 등록 (공정별 접두 표기 예: (글루), (OQC))

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `plant_cd` | `common.d_plant_cd` | N | Y | 사업부 구분 코드 (PL01=모바일, PL03=전장) |
| `defect_cd` | `common.d_defect_cd` | N | Y | 불량 코드. 원본 varchar(30) — TB_POP_DEFECT_HIST.DEFECT_CD(varchar(50)) 와 조인하기 위해 도메인 폭 50 사용 |
| `defect_nm` | `character varying(100)` | N | N | 불량 명 |
| `use_flg` | `common.d_yn` | N | N | 사용 유무 (Y/N) |
| `remark` | `character varying(1000)` | Y | N | 비고(기타) |
| `ins_date` | `timestamp(3) without time zone` | N | N | 등록일시 |
| `ins_user` | `common.d_user_id` | Y | N | 등록자 (사번) |
| `upd_date` | `timestamp(3) without time zone` | N | N | 최종 수정일시 |
| `upd_user` | `common.d_user_id` | Y | N | 최종 수정자 (사번) |

**테이블명:** `mes.tb_md_defect_by_item`

**테이블 설명:** 품목별 불량 코드 — 작업장·품목 조합별로 POP 에서 선택 가능한 불량 코드와 수율 집계 정책

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `plant_cd` | `common.d_plant_cd` | N | Y | 사업부 구분 코드 (PL01=모바일, PL03=전장) |
| `wc_cd` | `common.d_wc_cd` | N | Y | 작업장 코드 |
| `item_cd` | `common.d_item_cd` | N | Y | 품목 코드 |
| `defect_cd` | `common.d_defect_cd` | N | Y | 불량 코드 |
| `grade` | `common.d_grade` | Y | N | 등급 — 현재 R / T 등록. 두 등급의 성격은 유사하나 차이가 있음 |
| `yield_flg` | `common.d_yn` | N | N | 수율 집계 여부 — 해당 불량 발생 시 수율 계산에 포함할지 여부 (Y/N) |
| `sort_seq` | `smallint` | N | N | POP 불량 등록 화면의 목록 정렬 순서 |
| `ins_date` | `timestamp(3) without time zone` | N | N | 등록일시 |
| `ins_user` | `common.d_user_id` | Y | N | 등록자 (사번) |
| `upd_date` | `timestamp(3) without time zone` | N | N | 최종 수정일시 |
| `upd_user` | `common.d_user_id` | Y | N | 최종 수정자 (사번) |

**테이블명:** `mes.tb_md_eqpt`

**테이블 설명:** 설비 마스터 — 프레스 · AOI · 도금조 등 생산 설비 기준정보

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `plant_cd` | `common.d_plant_cd` | N | Y | 사업부 구분 코드 (PL01=모바일, PL03=전장) |
| `eqpt_cd` | `common.d_eqpt_cd` | N | Y | 설비 코드 |
| `eqpt_nm` | `character varying(100)` | N | N | 설비 명 |
| `model_nm` | `character varying(100)` | Y | N | 모델 명 |
| `spec` | `character varying(1000)` | Y | N | 사양 |
| `serial_no` | `character varying(100)` | Y | N | 설비 제조 일련번호 (LOT SERIAL_NO 와 무관 · 실측 전건 NULL) |
| `manufacturer` | `character varying(100)` | Y | N | 제조사 |
| `manufacture_date` | `date` | Y | N | 제조일 |
| `purchase_date` | `date` | Y | N | 구매일 |
| `use_flg` | `common.d_yn` | N | N | 사용 여부 — MES 상 설비 사용 시 Y, 미사용 N |
| `remark` | `character varying(1000)` | Y | N | 비고(기타) |
| `ins_date` | `timestamp(3) without time zone` | N | N | 등록일시 |
| `ins_user` | `common.d_user_id` | Y | N | 등록자 (사번) |
| `upd_date` | `timestamp(3) without time zone` | N | N | 최종 수정일시 |
| `upd_user` | `common.d_user_id` | Y | N | 최종 수정자 (사번) |

**테이블명:** `mes.tb_md_eqpt_by_user`

**테이블 설명:** 사용자별 설비 담당 배정 — POP 로그인 사용자가 조작할 수 있는 작업장·설비 조합. USER_ID 마스터는 ax.tb_sys_user

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `plant_cd` | `common.d_plant_cd` | N | Y | 사업부 구분 코드 (PL01=모바일, PL03=전장) |
| `user_id` | `common.d_user_id` | N | Y | 사용자 ID (사번). ax.tb_sys_user.user_id 와 조인 |
| `wc_cd` | `common.d_wc_cd` | N | Y | 작업장 코드 |
| `eqpt_cd` | `common.d_eqpt_cd` | N | Y | 설비 코드 |
| `ins_date` | `timestamp(3) without time zone` | N | N | 등록일시 |
| `ins_user` | `common.d_user_id` | Y | N | 등록자 (사번) |

**테이블명:** `mes.tb_md_eqpt_by_workcenter`

**테이블 설명:** 작업장별 설비 배치 — 어떤 작업장에 어떤 설비가 배치되어 있는지와 POP 기준 가동 여부

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `plant_cd` | `common.d_plant_cd` | N | Y | 사업부 구분 코드 (PL01=모바일, PL03=전장) |
| `wc_cd` | `common.d_wc_cd` | N | Y | 작업장 코드 |
| `eqpt_cd` | `common.d_eqpt_cd` | N | Y | 설비 코드 |
| `start_flg` | `common.d_yn` | N | N | 설비 시작 여부 (Y=시작·가동중, N=비가동). POP 상 생산이 시작되면 Y 로 전환 (POP 기준) |
| `ins_date` | `timestamp(3) without time zone` | N | N | 등록일시 |
| `ins_user` | `common.d_user_id` | Y | N | 등록자 (사번) |
| `upd_date` | `timestamp(3) without time zone` | N | N | 최종 수정일시 |
| `upd_user` | `common.d_user_id` | Y | N | 최종 수정자 (사번) |

**테이블명:** `mes.tb_md_item`

**테이블 설명:** 품목 마스터 — 제품 · 반제품 · 원자재 · 상품 기준정보 (ERP 품목 구분 기준)

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `plant_cd` | `common.d_plant_cd` | N | Y | 사업부 구분 코드 (PL01=모바일, PL03=전장) |
| `item_cd` | `common.d_item_cd` | N | Y | 품목 코드 |
| `item_nm` | `character varying(100)` | N | N | 품목 명 |
| `spec` | `character varying(100)` | Y | N | 원자재 규격 (반제품 및 제품은 무시) |
| `unit` | `character varying(3)` | Y | N | 단위 — 원재료 KG, 제품/반제품 EA (실측값 KG, EA, NULL) |
| `item_acct` | `character(2)` | N | N | 품목 구분 (ERP 기준) 10=제품(FINAL PRODUCT), 20=반제품(SEMI PRODUCT), 25=재공(WORK IN PROCESS), 30=원자재(RAW MATERIAL), 33=저장품(STORAGE GOODS), 35=부자재(SUBSIDIARY MATERIAL), 50=상품(GOODS), F0=수리자재(REPAIR MATERIAL). 실측 사용값 10/20/30/35/50 |
| `item_class` | `character varying(12)` | Y | N | 양산 / 개발 구분 (11=양산, 12=개발) |
| `valid_from_dt` | `timestamp(3) without time zone` | N | N | 유효일 — 품목이 MES 에서 사용 가능해지는 일자. 현재일이 유효일 이전이면 조회되지 않음 |
| `valid_to_dt` | `timestamp(3) without time zone` | N | N | 만료일 — 초과 시 MES 에서 품목이 조회되지 않음 |
| `product_family` | `character varying(30)` | Y | N | 제품군 — 현재 관리되지 않음 (AX 에서는 ax.tb_prod_family 로 관리) |
| `standard_time` | `numeric(18,6)` | Y | N | 생산 표준 시간(제품 1개 생산 소요 시간) — 현재 관리되지 않음 |
| `year_of_mass_prod` | `character varying(20)` | Y | N | 제품 양산 년도 — 현재 관리되지 않음 |
| `project_nm` | `character varying(20)` | Y | N | 제품 프로젝트 명 |
| `long_term` | `smallint` | Y | N | 장기재고 기간(일) — 현재 관리되지 않음 |
| `mold_check` | `common.d_yn` | N | N | 금형 확인 여부 — 현재 관리되지 않음 |
| `ins_date` | `timestamp(3) without time zone` | N | N | 등록일시 |
| `ins_user` | `common.d_user_id` | Y | N | 등록자 (사번) |
| `upd_date` | `timestamp(3) without time zone` | N | N | 최종 수정일시 |
| `upd_user` | `common.d_user_id` | Y | N | 최종 수정자 (사번) |

**테이블명:** `mes.tb_md_mold`

**테이블 설명:** 금형 마스터 — 금형 차수 · Cavity 등 기준정보

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `plant_cd` | `common.d_plant_cd` | N | Y | 사업부 구분 코드 (PL01=모바일, PL03=전장) |
| `mold_cd` | `common.d_mold_cd` | N | Y | 금형 코드 |
| `mold_nm` | `character varying(100)` | N | N | 금형 명 |
| `degree` | `smallint` | N | N | 금형 차수 |
| `cavity` | `smallint` | N | N | Cavity — 금형 1 개당 생산되는 Cavity 수 |
| `spec` | `character varying(1000)` | Y | N | 금형 사양 |
| `serial_no` | `character varying(100)` | Y | N | 금형 제조 일련번호 (LOT SERIAL_NO 와 무관 · 실측 전건 NULL) |
| `manufacturer` | `character varying(100)` | Y | N | 제조사 |
| `manufacture_date` | `date` | Y | N | 제조일 |
| `purchase_date` | `date` | Y | N | 구매일 |
| `use_flg` | `common.d_yn` | N | N | 사용 여부 (Y/N) |
| `remark` | `character varying(1000)` | Y | N | 비고(기타) |
| `ins_date` | `timestamp(3) without time zone` | N | N | 등록일시 |
| `ins_user` | `common.d_user_id` | Y | N | 등록자 (사번) |
| `upd_date` | `timestamp(3) without time zone` | N | N | 최종 수정일시 |
| `upd_user` | `common.d_user_id` | Y | N | 최종 수정자 (사번) |

**테이블명:** `mes.tb_md_mold_by_eqpt`

**테이블 설명:** 설비별 사용 가능 금형 — 설비에 장착할 수 있는 금형 조합

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `plant_cd` | `common.d_plant_cd` | N | Y | 사업부 구분 코드 (PL01=모바일, PL03=전장) |
| `eqpt_cd` | `common.d_eqpt_cd` | N | Y | 설비 코드 |
| `mold_cd` | `common.d_mold_cd` | N | Y | 금형 코드 |
| `ins_date` | `timestamp(3) without time zone` | N | N | 등록일시 |
| `ins_user` | `common.d_user_id` | Y | N | 등록자 (사번) |

**테이블명:** `mes.tb_md_workcenter`

**테이블 설명:** 작업장 마스터 — 사내 공장 및 외주 제조 업체를 포함한 작업장 정보

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `plant_cd` | `common.d_plant_cd` | N | Y | 사업부 구분 코드 (PL01=모바일, PL03=전장). DB 분리 후 PL01 만 사용 |
| `wc_cd` | `common.d_wc_cd` | N | Y | 작업장 코드 (사내 S###, 외주 W###) |
| `wc_nm` | `character varying(100)` | N | N | 작업장 명 |
| `valid_from_dt` | `timestamp(3) without time zone` | N | N | 유효일 — 이 일자 이전에는 MES 에서 작업장이 조회되지 않음 |
| `valid_to_dt` | `timestamp(3) without time zone` | N | N | 만료일 — 이 일자를 초과하면 MES 에서 작업장이 조회되지 않음 |
| `sort_seq` | `smallint` | N | N | 정렬순서 — POP 및 기타 메뉴에서 작업장 선택 시 정렬 기준 |
| `bp_nm` | `character varying(50)` | Y | N | 상호 (사내 공장 또는 담당 제조 업체명) |
| `bp_user` | `character varying(50)` | Y | N | 담당자 |
| `bp_addr` | `character varying(100)` | Y | N | 주소 |
| `bp_tel` | `character varying(20)` | Y | N | 전화번호 |
| `ins_date` | `timestamp(3) without time zone` | N | N | 등록일시 |
| `ins_user` | `common.d_user_id` | Y | N | 등록자 (사번) |
| `upd_date` | `timestamp(3) without time zone` | N | N | 최종 수정일시 |
| `upd_user` | `common.d_user_id` | Y | N | 최종 수정자 (사번) |

**테이블명:** `mes.tb_pop_defect_hist`

**테이블 설명:** LOT 불량 등록 이력 — LOT 단위로 등록된 불량 코드별 수량. TB_POP_LABEL_HIST.DEFECT 의 상세 내역

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `plant_cd` | `common.d_plant_cd` | N | Y | 사업부 구분 코드 (PL01=모바일, PL03=전장) |
| `wc_cd` | `common.d_wc_cd` | N | Y | LOT 의 작업장 코드 |
| `lot_no` | `common.d_lot_no` | N | Y | LOT NO |
| `serial_no` | `common.d_lot_serial` | N | Y | SERIAL NO |
| `defect_cd` | `common.d_defect_cd` | N | Y | 불량 코드 (원본 varchar(50) — 마스터 TB_MD_DEFECT 는 varchar(30)) |
| `item_cd` | `common.d_item_cd` | N | N | 품목 코드 |
| `qty` | `numeric(18,6)` | N | N | 불량 수량 |
| `remark` | `character varying(1000)` | Y | N | 비고(기타) |
| `ins_date` | `timestamp(3) without time zone` | N | N | 등록일시 |
| `ins_user` | `common.d_user_id` | Y | N | 등록자 (사번) |

**테이블명:** `mes.tb_pop_label_hist`

**테이블 설명:** LOT 라벨 이력 — 작업 완료 시 생성되는 LOT 단위 실적. (PLANT_CD, WC_CD, LOT_NO, SERIAL_NO) 가 LOT 식별 키이며 재고·불량 이력의 기준이 됨

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `plant_cd` | `common.d_plant_cd` | N | Y | 사업부 구분 코드 (PL01=모바일, PL03=전장) |
| `wc_cd` | `common.d_wc_cd` | N | Y | LOT 이 생성된 작업장 코드 |
| `lot_no` | `common.d_lot_no` | N | Y | LOT NO |
| `serial_no` | `common.d_lot_serial` | N | Y | LOT 내 SERIAL NO (00001 부터 부여) |
| `item_cd` | `common.d_item_cd` | N | N | 품목 코드 |
| `normal` | `numeric(18,6)` | Y | N | 양품 수량 |
| `defect` | `numeric(18,6)` | Y | N | 불량 수량 (상세 불량 코드별 내역은 TB_POP_DEFECT_HIST) |
| `sample` | `numeric(18,6)` | Y | N | 샘플 수량 |
| `start_remark` | `character varying(1000)` | Y | N | 작업 시작 비고 |
| `prod_remark` | `character varying(1000)` | Y | N | 작업(생산) 비고 |
| `eqpt_cd` | `common.d_eqpt_cd` | Y | N | 설비 코드 |
| `mold_cd` | `common.d_mold_cd` | Y | N | 금형 코드 |
| `cavity` | `smallint` | Y | N | Cavity |
| `label_type` | `character varying(10)` | N | N | 작업 완료 Type — REGULAR=정규생산, REWORK=재작업, CREATION=생성 |
| `grade` | `common.d_grade` | Y | N | 등급 (R / T) |
| `stock_flg` | `common.d_yn` | N | N | LOT 생성 후 실적처리(재고 반영)가 되었으면 Y |
| `del_flg` | `common.d_yn` | N | N | LOT 삭제 여부 (Y=삭제). 조회 시 기본 제외 대상 |
| `ins_date` | `timestamp(3) without time zone` | N | N | 등록일시 |
| `ins_user` | `common.d_user_id` | Y | N | 등록자 (사번) |
| `upd_date` | `timestamp(3) without time zone` | N | N | 최종 수정일시 |
| `upd_user` | `common.d_user_id` | Y | N | 최종 수정자 (사번) |

**테이블명:** `mes.tb_pop_stock`

**테이블 설명:** LOT 현재 재고 — LOT 별 현재 위치(CUR_WC_CD)와 상태(STATUS_CD)를 담은 스냅샷. 이력은 TB_POP_STOCK_HIST

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `plant_cd` | `common.d_plant_cd` | N | Y | 사업부 구분 코드 (PL01=모바일, PL03=전장) |
| `wc_cd` | `common.d_wc_cd` | N | Y | LOT 이 생성된 작업장 코드 (LOT 식별 키의 일부) |
| `lot_no` | `common.d_lot_no` | N | Y | LOT NO |
| `serial_no` | `common.d_lot_serial` | N | Y | SERIAL NO |
| `cur_wc_cd` | `common.d_wc_cd` | N | N | 현재 LOT 가 있는 작업장 코드 (WC_CD 와 별개로 이동에 따라 변경됨) |
| `item_cd` | `common.d_item_cd` | N | N | 품목 코드 |
| `qty` | `numeric(18,6)` | N | N | 수량 |
| `status_cd` | `character varying(30)` | N | N | 현재 상태 — AVAILABLE=가용, HOLDING=홀딩, IN USE=사용중, MOVE OUT=이동중, STOCKTAKING=실사중, IQC=수입검사중, OQC=출하검사중 |
| `attr1` | `character varying(50)` | Y | N | 사용중(IN USE) LOT 인 경우 생산에서 사용중인 금형 정보 |
| `attr2` | `character varying(50)` | Y | N | 미사용 컬럼 (실측 전건 NULL) |
| `attr3` | `character varying(50)` | Y | N | 미사용 컬럼 (실측 전건 NULL) |
| `attr4` | `character varying(1000)` | Y | N | LOT 특이사항 (비고) |
| `ins_date` | `timestamp(3) without time zone` | N | N | 등록일시 |
| `ins_user` | `common.d_user_id` | Y | N | 등록자 (사번) |
| `upd_date` | `timestamp(3) without time zone` | N | N | 최종 수정일시 |
| `upd_user` | `common.d_user_id` | Y | N | 최종 수정자 (사번) |

**테이블명:** `mes.tb_pop_stock_hist`

**테이블 설명:** LOT 재고 이동 이력 — 이력 발생 시점의 상태·위치·수량. 원본 MSSQL 에 PK 가 선언되어 있지 않아 그대로 복제

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `plant_cd` | `common.d_plant_cd` | N | N | 사업부 구분 코드 (실측 전건 PL01) |
| `wc_cd` | `common.d_wc_cd` | N | N | LOT 의 작업장 코드 (LOT 생성 작업장) |
| `lot_no` | `common.d_lot_no` | N | N | LOT NO |
| `serial_no` | `common.d_lot_serial` | N | N | SERIAL NO |
| `hist_date` | `date` | N | N | 이력 발생 일자 (원본 자료형 date — 시각 정보 없음. 시각은 INS_DATE 사용) |
| `hist_type` | `character varying(30)` | N | N | 이력 발생 시점의 상태 — ADJUSTMENT(조정), BASIC(기초), CREATION(생성), ETC MOVE OUT(기타 출고), ETC USED(기타 사용), MOVE IN(입고), MOVE OUT(출고), PRODUCTION(생산), REWORK(재작업), SAMPLE MOVE OUT(샘플 출고), SCRAP(폐기), STOCKTAKING(실사), USED(사용) |
| `item_cd` | `common.d_item_cd` | N | N | 품목 코드 |
| `qty` | `numeric(18,6)` | N | N | 수량 |
| `cur_wc_cd` | `common.d_wc_cd` | N | N | 이력 발생 시점에 LOT 가 있었던 작업장 코드 |
| `remark` | `character varying(1000)` | Y | N | 비고(기타) |
| `ins_date` | `timestamp(3) without time zone` | N | N | 등록일시 |
| `ins_user` | `common.d_user_id` | Y | N | 등록자 (사번) |


## 7. ax 스키마

계정·권한, 제품, 채팅, 보고서, 알림, 지표, 데이터 복제 이력을 저장합니다. 현재 DB에서 확인한 테이블은 68개, 컬럼은 772개입니다.

**테이블명:** `ax.tb_ai_agent`

**테이블 설명:** Worker Agent 마스터 9종 — 1 비전 수집 ~ 9 이상 알림

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `agent_id` | `integer` | N | Y | Agent 식별자 (PK). 코드는 agent_no 로 찾고 이 값을 박지 않음 |
| `agent_no` | `character varying(4)` | N | N | Agent 번호 표기 (1~9) |
| `agent_nm` | `character varying(50)` | N | N | Agent 이름 (예: 비전 수집, 불량 판정, 보고서 생성) |
| `agent_desc` | `character varying(300)` | Y | N | Agent 설명. Agent 실행 현황 목록의 설명 칸 |
| `sort_seq` | `smallint` | N | N | 화면 표시 순서. 1~9 차례를 이 값으로 부여 |
| `use_flg` | `common.d_yn` | N | N | 사용 여부. N 이면 Agent 실행 현황 화면의 목록·요약에서 빠진다 (9행은 지우지 않는다) |

**테이블명:** `ax.tb_ai_agent_run`

**테이블 설명:** Agent 실행 이력 — 화면의 상태·최근 실행·처리량은 이 테이블의 최신 행에서 산출

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `run_id` | `bigint` | N | Y | 실행 이력 식별자 (PK) |
| `agent_id` | `integer` | N | N | 실행한 Agent (ax.tb_ai_agent). 코드는 agent_no 로 찾아 넣음 |
| `run_at` | `timestamp with time zone` | N | N | 실행 시각. Agent 별 최신 행이 화면의 "최근 실행" 이 됨 |
| `state_cd` | `character varying(30)` | N | N | 상태. 공통코드 그룹 = AI_AGENT_STATE (OK=정상, RUNNING=실행중, IDLE=대기, ERROR=오류, STOPPED=중지) |
| `throughput_txt` | `character varying(50)` | Y | N | 처리량 표기. Agent 실행 현황 화면 목록의 "처리량" 칸 (예: 판정 459건) |
| `elapsed_ms` | `integer` | Y | N | 작업 소요 시간(ms). Master 요약의 평균 응답 시간 산출 근거 |
| `message` | `character varying(500)` | Y | N | 실행 내용 한 줄 (예: AOI 불량 판정 조회 2026-08-01~2026-08-28) |
| `err_flg` | `common.d_yn` | N | N | Y = 실패한 실행. 최근 10분 내 1건이라도 있으면 master.state = ERROR |

**테이블명:** `ax.tb_ai_chat_agent`

**테이블 설명:** 질의 × 호출 Agent — 화면의 "호출 Agent" 열이 이 표를 읽음

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `chat_id` | `bigint` | N | Y | 대상 질의 (ax.tb_ai_chat_log) |
| `agent_id` | `integer` | N | Y | 이 질의에 참여한 Agent (ax.tb_ai_agent). 의도별 매핑으로 지정 |
| `call_seq` | `smallint` | N | Y | 호출 순서. 1 부터 매기며 (chat_id, agent_id) 와 묶여 유일 |
| `elapsed_ms` | `integer` | Y | N | Agent 별 소요 시간(ms). 현재 API 는 계측하지 않아 NULL 로 넣음 |

**테이블명:** `ax.tb_ai_chat_log`

**테이블 설명:** 자연어 질의 이력 — 질의·해석된 의도·호출 Agent·응답 시간·평가. 의도 파악 정확도와 재질의율의 산출 근거

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `chat_id` | `bigint` | N | Y | 질의 이력 식별자 (PK). 화면·API 의 messageId 가 이 값 |
| `session_id` | `uuid` | Y | N | 대화 세션 UUID. 한 세션의 질의가 이 값으로 묶임 |
| `asked_at` | `timestamp with time zone` | N | N | 질의 시각 |
| `user_id` | `common.d_user_id` | N | N | 질의자 사번 (ax.tb_sys_user) |
| `dept_nm` | `character varying(50)` | Y | N | 질의자 부서명. 질의 시점 값을 그대로 보관 |
| `question` | `text` | N | N | 사용자가 입력한 원문 질의 |
| `normalized_question` | `text` | Y | N | 용어 사전으로 정규화된 질의문 (유사어 → 공식 용어 치환 결과) |
| `intent_cd` | `character varying(50)` | Y | N | 판정된 의도 (trend=추이, trace=이력 추적, downtime=비가동, metric=지표, unknown) |
| `intent_nm` | `character varying(100)` | Y | N | 의도 표시명. 자연어 질의 이력의 "의도" 칸 |
| `answer` | `text` | Y | N | 응답 본문(HTML). 데이터 권한 마스킹을 적용한 뒤의 문장 |
| `response_ms` | `integer` | Y | N | 응답 생성까지 걸린 시간(ms). 평균 응답 시간 지표의 원값 |
| `rating_cd` | `character varying(30)` | Y | N | 평가. 공통코드 그룹 = AI_CHAT_RATING (USEFUL=유용, REASK=재질의, BAD=오답) |
| `is_reask` | `boolean` | N | N | true = 같은 세션에 앞선 질의가 있어 이어 물은 것. 재질의율의 분자 |
| `prev_chat_id` | `bigint` | Y | N | 같은 세션의 직전 질의 (ax.tb_ai_chat_log). 재질의 판정 근거 |
| `blind_applied_cnt` | `integer` | N | N | 응답 생성 시 데이터 접근 권한으로 blind 처리된 항목 수 |
| `profile_id` | `integer` | Y | N | 이 응답을 만든 서빙 버전 (ax.tb_ai_serving_profile). 버전별 품질 비교와 회귀 추적의 기준 |
| `evidence_summary` | `text` | Y | N | 주석 없음 |
| `unanswered_reason` | `text` | Y | N | 주석 없음 |
| `train_answer` | `text` | Y | N | 학습 답변 — 전사 자연어 질의 이력 화면의 「답변 추가(학습 데이터)」. 4000자 이내. 학습데이터 내보내기는 이 값이 있으면 평가와 관계없이 넣고 응답 대신 이 값을 씀 (V70) |
| `train_answer_by` | `character varying(30)` | Y | N | 학습 답변 작성자 사번 (ax.tb_sys_user.user_id). 답변을 지우면 NULL (V70) |
| `train_answer_at` | `timestamp with time zone` | Y | N | 학습 답변 저장 시각. 답변을 지우면 NULL (V70) |

**테이블명:** `ax.tb_ai_model_config`

**테이블 설명:** AI 모델 설정 — Agent별 이상 탐지 임계치, 분류 기준(신뢰도 상·하한, 경계 구간 처리) 등 키/값 설정

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `config_id` | `integer` | N | Y | AI 모델 설정 식별자 (PK) |
| `agent_id` | `integer` | Y | N | 담당 Agent (ax.tb_ai_agent). NULL 이면 Agent 에 매이지 않는 공통 설정 |
| `category_cd` | `character varying(30)` | N | N | 설정 분류. 공통코드 그룹 = AI_CONFIG_CAT (ANOMALY=이상 탐지 임계치, CLASSIFY=분류 기준, SECURITY=보안 필터링) |
| `config_key` | `character varying(50)` | N | N | 설정 키. category_cd 와 묶여 유일하며 읽는 쪽의 조회 열쇠 |
| `config_nm` | `character varying(100)` | N | N | 설정 표시 이름. AI 모델 설정 화면의 항목명 |
| `config_value` | `character varying(300)` | N | N | 설정 값. 문자열로 저장하고 형식 검사는 value_type_cd 로 함 |
| `value_type_cd` | `character varying(30)` | N | N | 값 자료형. 공통코드 그룹 = AI_VALUE_TYPE (NUM=숫자, TEXT=문자, SELECT=선택, BOOL=여부, LIST=구분자 목록) |
| `unit` | `character varying(20)` | Y | N | 값 단위 표기 (예: %, 건, ms). 화면 입력칸 옆에 표시 |
| `opt_values` | `character varying(500)` | Y | N | SELECT 유형일 때 선택 가능한 값 목록 (구분자  / ) |
| `description` | `character varying(300)` | Y | N | 설정 설명. 화면의 도움말 문구로 사용 |
| `use_flg` | `common.d_yn` | N | N | 사용 여부. N 이면 읽는 쪽이 코드 기본값을 사용 — 행을 지우지 않고 끄는 방식 |
| `ins_date` | `timestamp with time zone` | N | N | 등록일시 |
| `ins_user` | `common.d_user_id` | Y | N | 등록자 (사번) |
| `upd_date` | `timestamp with time zone` | N | N | 최종 수정일시 |
| `upd_user` | `common.d_user_id` | Y | N | 최종 수정자 (사번) |

**테이블명:** `ax.tb_ai_serving_profile`

**테이블 설명:** AI 서비스 서빙 프로필 = 관리자가 고르는 "버전". 모델 자산 조합 + 코퍼스 스냅샷 + 생성/검색 파라미터를 한 묶음으로 고정

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `profile_id` | `integer` | N | Y | 서빙 프로필(버전) 식별자 (PK). AI 서비스 버전 관리 화면은 꺼져 있으나 /auth/me 와 질의 이력이 읽음 |
| `service_cd` | `character varying(20)` | N | N | 대상 서비스. 공통코드 그룹 = AI_SERVICE (CHAT=AI 채팅, REPORT=보고서 생성, SEARCH=문서 검색). 서비스마다 ACTIVE 를 따로 관리 |
| `profile_cd` | `character varying(40)` | N | N | 프로필 계열 코드 (예: chat-default). 같은 계열의 개정이 version_no 로 누적 |
| `version_no` | `integer` | N | N | 버전 번호. /auth/me 의 servingModelVer = profile_cd-v{version_no} 로 조립됨 |
| `profile_nm` | `character varying(200)` | N | N | 버전 이름. AI 서비스 버전 관리 목록의 "이름" 칸 |
| `description` | `character varying(1000)` | Y | N | 버전 설명 |
| `corpus_snapshot_id` | `integer` | Y | N | 이 버전이 바라보는 벡터 코퍼스 스냅샷. 어댑터가 같아도 이 값이 다르면 답이 달라짐 |
| `temperature` | `numeric(4,3)` | N | N | 생성 온도 0~2 (기본 0.200). 낮을수록 답이 결정적 |
| `top_p` | `numeric(4,3)` | N | N | 누적 확률 샘플링 상한 0~1 (기본 0.900) |
| `max_tokens` | `integer` | N | N | 응답 최대 토큰 수 (기본 2048) |
| `thinking_mode_cd` | `character varying(20)` | N | N | thinking 사용. 공통코드 그룹 = AI_THINKING_MODE (OFF, ON, AUTO=태스크별 자동) |
| `system_prompt` | `text` | Y | N | 이 버전의 시스템 프롬프트 전문. 프롬프트만 바뀌어도 별 버전으로 관리해야 비교가 가능 |
| `search_top_k` | `integer` | N | N | 최종 반환 청크 수 (기본 10). search_candidate_k 이하여야 함 |
| `search_candidate_k` | `integer` | N | N | 경로별 후보 수 (기본 60). 벡터·전문검색·트라이그램이 각각 이 개수만큼 후보를 추출 |
| `rrf_k` | `integer` | N | N | RRF 상수 k (기본 60). 융합 점수 = Σ 1/(k + 경로별 순위) |
| `trgm_threshold` | `numeric(3,2)` | N | N | pg_trgm 단어 유사도 임계값. vec.fn_search_chunk 의 p_trgm_threshold 로 전달 |
| `hnsw_ef_search` | `integer` | N | N | HNSW 탐색 폭. 높을수록 재현율↑ 속도↓ |
| `rerank_flg` | `common.d_yn` | N | N | Y = 리랭커를 거쳐 순위를 다시 매긴다 (기본 N) |
| `strict_perm_flg` | `common.d_yn` | N | N | Y = 권한 없는 민감 항목을 포함한 문서를 검색에서 제외 (vec.fn_allowed_doc 의 p_strict) |
| `eval_score` | `numeric(6,3)` | Y | N | 평가 점수. 기준선(eval_baseline)과의 차이가 이 버전의 개선폭 |
| `eval_baseline` | `numeric(6,3)` | Y | N | 비교 기준선 점수. 성과지표 대시보드가 eval_score 와 함께 읽음 |
| `eval_json` | `jsonb` | Y | N | 평가 상세(JSON). 항목별 점수를 담아 버전 간 비교에 사용 |
| `must_pass_fail` | `integer` | N | N | 골든셋 must_pass 실패 건수. 0 이 아니면 제약이 배포를 차단 |
| `state_cd` | `character varying(30)` | N | N | 배포 상태. 공통코드 그룹 = AI_SERVING_STATE (DRAFT=작성 중, CANARY=일부 대상 시범, ACTIVE=전량 서비스, ROLLED_BACK=롤백됨, RETIRED=폐기) |
| `canary_at` | `timestamp with time zone` | Y | N | 카나리 적용 시각. state_cd = CANARY 로 바뀔 때 트리거가 채움 |
| `activated_at` | `timestamp with time zone` | Y | N | 서비스 적용 시각. ACTIVE 판정은 이 값의 내림차순 최신 1건 |
| `retired_at` | `timestamp with time zone` | Y | N | 폐기 시각. 새 버전을 올릴 때 쓰던 버전이 여기로 내려감 |
| `rollback_at` | `timestamp with time zone` | Y | N | 롤백 시각. ROLLED_BACK 상태는 이 값이 있어야 한다 (ck_profile_rollback) |
| `rollback_reason` | `character varying(500)` | Y | N | 롤백 사유 |
| `activated_by` | `common.d_user_id` | Y | N | 서비스에 올린 사람 사번 (ax.tb_sys_user). 배포 감사의 기준 |
| `prev_profile_id` | `integer` | Y | N | 직전 버전. 롤백 대상이자 평가 기준선 상대 |
| `remark` | `character varying(1000)` | Y | N | 비고 |
| `ins_date` | `timestamp with time zone` | N | N | 등록일시 |
| `ins_user` | `common.d_user_id` | Y | N | 등록자 (사번) |
| `upd_date` | `timestamp with time zone` | N | N | 최종 수정일시 |
| `upd_user` | `common.d_user_id` | Y | N | 최종 수정자 (사번) |

**테이블명:** `ax.tb_alm_alert`

**테이블 설명:** 발생 알림 — 발송 조건이 감지한 이상 건. 확인되지 않으면 승격 규칙에 따라 상위로 승격됨

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `alert_id` | `bigint` | N | Y | 알림 식별자 (자동 채번) |
| `cond_id` | `integer` | Y | N | 발생시킨 발송 조건. 테스트 발송으로 만든 알림은 비어 있을 수 있음 |
| `metric_id` | `integer` | Y | N | 판정에 쓴 지표 기준. 조건의 metric_id 를 그대로 이관 |
| `detect_agent_id` | `integer` | Y | N | 감지 Agent (tb_ai_agent.agent_id). 예: 9 이상 알림, 3 불량 판정, 6 보고서 생성 |
| `severity_cd` | `character varying(30)` | N | N | 심각도. 공통코드 그룹 = ALM_SEVERITY (CRIT=위험, WARN=주의, LOW=낮음) |
| `title` | `character varying(200)` | N | N | 알림 제목 — 대상+지표+값+단위 로 엔진이 조립한다 (예: EQ-01 불량률 4.1%) |
| `occurred_at` | `timestamp with time zone` | N | N | 이상이 난 시각. 승격 경과(after_min)와 중복 억제 창을 재는 기준점 |
| `metric_value` | `numeric(18,6)` | Y | N | 이상을 낸 실측값. 억제 창 안에서 재발하면 최신 값으로 덮음 |
| `threshold_val` | `numeric(18,4)` | Y | N | 발생 당시 비교에 쓴 임계값. 조건의 임계가 바뀌어도 이 값은 남음 |
| `evidence_desc` | `character varying(300)` | Y | N | 근거 수치 표시 문자열 (예: 4.1% (임계 3.0%)) |
| `plant_cd` | `common.d_plant_cd` | Y | N | 대상 공장. 지금 엔진이 채우지 않아 늘 NULL — 조회는 NULL 도 통과시킴 |
| `wc_cd` | `common.d_wc_cd` | Y | N | 대상 공정(워크센터). 지금 엔진이 채우지 않아 늘 NULL |
| `eqpt_cd` | `common.d_eqpt_cd` | Y | N | 대상 설비 코드 — mes.tb_md_eqpt 와 조인 (일일 생산현황 보고 등) |
| `mold_cd` | `common.d_mold_cd` | Y | N | 대상 금형 — 조건의 평가 단위가 MOLD 일 때 scope_key 를 그대로 넣음 |
| `item_cd` | `common.d_item_cd` | Y | N | 대상 품목 — 조건의 평가 단위가 ITEM 일 때 scope_key 를 그대로 넣음 |
| `lot_no` | `common.d_lot_no` | Y | N | 대상 LOT — mes.tb_pop_label_hist / tb_pop_stock 과 조인 |
| `serial_no` | `common.d_lot_serial` | Y | N | 대상 시리얼. 알림 목록·상세가 읽지만 지금 채우는 코드는 없음 |
| `defect_cd` | `common.d_defect_cd` | Y | N | 대상 불량 코드 — mes.tb_md_defect 와 조인 |
| `product_id` | `integer` | Y | N | 제품(모델) — ax.tb_prod_product. 현재 엔진·API 어디서도 채우지 않는다 (FK 만 선언된 확장 자리) |
| `target_desc` | `character varying(200)` | Y | N | 대상 표시 문구. 평가 단위가 NONE 이면 조건의 대상 설명, 아니면 scope_key |
| `ack_state_cd` | `character varying(30)` | N | N | 확인 상태. 공통코드 그룹 = ALM_ACK_STATE (OPEN=미확인, ACKED=확인됨, CLOSED=조치 완료, IGNORED=무시) |
| `ack_user_id` | `common.d_user_id` | Y | N | 확인 처리한 사람 (사번). 엔진이 아니라 사람이 화면에서 처리한 기록 |
| `ack_at` | `timestamp with time zone` | Y | N | 확인 처리 시각. 알림 목록·상세에서 확인을 누른 순간 기록됨 |
| `ack_note` | `character varying(500)` | Y | N | 조치 내용. 알림 목록·상세의 확인 처리에서 입력한다 (500자) |
| `esc_level` | `smallint` | N | N | 현재 승격 단계 (0=미승격) |
| `dedup_key` | `character varying(200)` | Y | N | 중복 억제 키 — 조건 + 대상 조합. 억제 구간 내 동일 키는 재발송하지 않음 |
| `ins_date` | `timestamp with time zone` | N | N | 등록일시. 이상이 실제로 난 시각은 occurred_at 참조 |
| `scope_key` | `character varying(100)` | Y | N | 어느 대상에서 났는지(설비코드 등). ax.tb_alm_cond_state 와 잇는 키. scope_dim_cd='NONE' 이면 '*' |
| `hit_cnt` | `integer` | N | N | 중복 억제 창 안에서 다시 걸린 횟수(최초 발생 포함 1). 억제됐다고 사실까지 지우지 않기 위한 값 |
| `last_hit_at` | `timestamp with time zone` | Y | N | 억제 창 안에서 마지막으로 다시 걸린 시각 |
| `resolved_at` | `timestamp with time zone` | Y | N | 값이 정상으로 돌아온 시각. 확인 처리(ack)와는 다름 — 사람이 안 봐도 상황은 풀릴 수 있음 |

**테이블명:** `ax.tb_alm_cond`

**테이블 설명:** 이상 알림 발송 조건 — 지표·비교식·임계값·지속조건·대상 범위를 정의. 수신 대상은 수신 그룹 이름으로만 지정

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `cond_id` | `integer` | N | Y | 발송 조건 식별자 (자동 채번) |
| `cond_nm` | `character varying(100)` | N | N | 조건 이름. 이상 알림 발송 조건 관리의 조건명 열이며 중복될 수 없음 |
| `severity_cd` | `character varying(30)` | N | N | 심각도. 공통코드 그룹 = ALM_SEVERITY (CRIT=위험, WARN=주의, LOW=낮음) |
| `metric_id` | `integer` | Y | N | 연결된 지표 기준. 지표 측정 데이터 관리의 임계값과 함께 관리한다 (NULL 이면 조건 자체 임계값만 사용) |
| `metric_desc` | `character varying(100)` | N | N | 감지 지표 표시명 (지표 미연결 조건도 목록에 표시되도록 별도 보관) |
| `op_cd` | `character varying(10)` | N | N | 비교 연산. 공통코드 그룹 = ALM_OP (GE=>=, GT=>, LE=<=, LT=<, EQ==, RATE=변화율) |
| `threshold_val` | `numeric(18,4)` | Y | N | 임계값 수치. 비교 판정에 사용 |
| `threshold_text` | `character varying(50)` | N | N | 임계값 표시 문자열 (예: 3.0 %, 50,000,000 원) |
| `threshold_unit` | `character varying(20)` | Y | N | 임계값 단위 코드. 지표에 단위가 없을 때만 쓰며 MET_UNIT 의 표기값으로 바꿔 부여 |
| `duration_cd` | `character varying(30)` | N | N | 지속 조건. 공통코드 그룹 = ALM_DURATION (IMMEDIATE=즉시, C5M=5분 연속, C10M=10분 연속, C30M=30분 연속, MA120=2시간 이동평균, DAY_CLOSE=일 마감 시, DAY_ONCE=일 1회 집계) |
| `target_scope_cd` | `character varying(30)` | N | N | 대상 범위 구분. 공통코드 그룹 = ALM_TARGET (ALL_EQPT=전체 설비, PRESS=프레스 전체, AOI=AOI 전체, ALL_MODEL=전체 모델, ALL_PROC=전 공정, ALL_CUST=전체 고객사, MATERIAL=주요 소재, PICK=개별 설비 선택) |
| `target_desc` | `character varying(200)` | N | N | 대상 설명 문구. 평가 단위가 NONE 인 알림의 제목·본문에 대상으로 쓰임 |
| `window_cd` | `character varying(30)` | N | N | 유효 시간대. 공통코드 그룹 = ALM_WINDOW (ALWAYS=24시간 상시, D0820=08:00~20:00, D0618=06:00~18:00, WORKDAY=주간 근무일만, ONCE=지정 시각 1회) |
| `dedup_cd` | `character varying(30)` | N | N | 중복 억제. 공통코드 그룹 = ALM_DEDUP (NONE=없음, M15/M30/M60/M120=분 단위, DAY_ONCE=일 1회) |
| `blind_field_key` | `character varying(30)` | Y | N | 임계값 자체가 민감정보인 경우의 데이터 항목 (예: 월 폐기 금액 → price) |
| `msg_template` | `text` | N | N | 메시지 템플릿. 치환자 {심각도}{조건명}{대상}{지표}{임계값}. 단가·수율 등 민감정보는 본문에 포함하지 않음 |
| `use_flg` | `common.d_yn` | N | N | 'Y' 면 엔진이 이 조건을 판정. 'N' 이면 판정 대상에서 빠짐 |
| `ins_date` | `timestamp with time zone` | N | N | 등록일시 |
| `ins_user` | `common.d_user_id` | Y | N | 등록자 (사번) |
| `upd_date` | `timestamp with time zone` | N | N | 최종 수정일시 |
| `upd_user` | `common.d_user_id` | Y | N | 최종 수정자 (사번) |
| `scope_dim_cd` | `character varying(30)` | N | N | 평가 단위. 공통코드 그룹 = ALM_SCOPE_DIM. 'EQPT' 면 설비마다 따로 판정·따로 억제됨. 'NONE' 이면 대상 전체를 묶어 값 하나로 판정 |
| `window_time` | `time without time zone` | Y | N | window_cd='ONCE'(지정 시각 1회) 일 때의 시각. 그 밖의 시간대는 공통코드 ALM_WINDOW 의 attr1/attr2 를 사용 |
| `eval_interval_sec` | `integer` | N | N | 이 조건을 몇 초마다 평가할지. 엔진 틱(기본 60초)보다 짧게 잡아도 틱 주기가 하한 |
| `ignore_window_flg` | `common.d_yn` | N | N | 'Y' 면 유효 시간대 밖에도 발송. 위험(CRIT) 조건을 야간에도 받아야 할 때 사용. 수신자 개인의 야간 미수신(night_recv)은 이 값과 무관하게 지켜짐 |
| `auto_close_flg` | `common.d_yn` | N | N | 'Y' 면 값이 정상으로 돌아올 때 tb_alm_alert.resolved_at 을 기록. 확인 처리(ack_state_cd)는 사람 몫 — 자동으로 CLOSED 로 바꾸지 않음 |
| `last_eval_at` | `timestamp with time zone` | Y | N | 마지막 평가 시각(화면 표시용). 대상별 정밀 상태는 ax.tb_alm_cond_state 참조 |

**테이블명:** `ax.tb_alm_cond_channel`

**테이블 설명:** 발송 조건 × 발송 채널 (공통코드 그룹 ALM_CHANNEL)

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `cond_id` | `integer` | N | Y | 발송 조건 (tb_alm_cond). 조건이 지워지면 함께 삭제됨 |
| `channel_cd` | `character varying(30)` | N | Y | 발송 채널. 공통코드 그룹 = ALM_CHANNEL (MAIL=메일, POPUP=시스템 팝업, SMS=SMS, MSG=메신저 연동) |

**테이블명:** `ax.tb_alm_cond_escalation`

**테이블 설명:** 발송 조건별 승격 단계 적용 여부

> **제거됨(2026-10-03).** 발송 조건 「고급 설정」 제거로 V73 에서 이 표를 삭제합니다. 삭제 전 행은 `ax.tb_alm_cond_escalation_bak` 에 보관합니다.

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `cond_id` | `integer` | N | Y | 발송 조건 (tb_alm_cond). 조건이 지워지면 함께 삭제됨 |
| `esc_rule_id` | `integer` | N | Y | 적용할 승격 단계 (tb_alm_escalation_rule) |
| `is_on` | `boolean` | N | N | true 면 이 조건의 알림을 그 단계로 승격. false 면 시간이 지나도 올리지 않음 |

**테이블명:** `ax.tb_alm_cond_group`

**테이블 설명:** 발송 조건 × 수신 그룹. 그룹을 참조하는 조건이 있으면 그룹 삭제를 차단 (RESTRICT)

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `cond_id` | `integer` | N | Y | 발송 조건 (tb_alm_cond). 조건이 지워지면 함께 삭제됨 |
| `group_id` | `integer` | N | Y | 수신 그룹 (tb_alm_recip_group). 참조하는 조건이 있으면 그룹 삭제를 차단 |

**테이블명:** `ax.tb_alm_cond_state`

**테이블 설명:** 조건 × 대상의 평가 상태. 「10분 연속」·「30분 중복 억제」를 계산할 수 있는 유일한 근거. 이 표가 없으면 엔진은 매번 처음부터 판정

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `cond_id` | `integer` | N | Y | 발송 조건 (tb_alm_cond). scope_key 와 함께 이 표의 기본키 |
| `scope_key` | `character varying(100)` | N | Y | 대상 키(설비코드 등). tb_alm_cond.scope_dim_cd='NONE' 이면 '*' |
| `state_cd` | `character varying(30)` | N | N | 현재 판정. 공통코드 ALM_COND_STATE. PENDING = 임계는 넘었으나 지속 조건 미충족 |
| `last_value` | `numeric(18,6)` | Y | N | 마지막으로 판정에 쓴 값. 이동평균 조건이면 구간 평균이 들어감 |
| `last_eval_at` | `timestamp with time zone` | Y | N | 이 대상을 마지막으로 판정한 시각. NULL 이면 아직 한 번도 판정하지 않음 |
| `breach_since` | `timestamp with time zone` | Y | N | 연속 위반이 시작된 시각. 중간에 한 번이라도 정상이면 NULL 로 되돌림 — 연속이 끊긴 것 |
| `breach_cnt` | `integer` | N | N | 연속 위반으로 판정된 횟수. 이동평균·간헐 위반을 사람이 판단할 때 사용 |
| `last_alert_id` | `bigint` | Y | N | 이 대상에서 마지막으로 낸 알림. 값이 정상으로 돌아오면 이 알림에 resolved_at 기록 |
| `last_alert_at` | `timestamp with time zone` | Y | N | 마지막으로 알림을 낸 시각. 중복 억제 창(ALM_DEDUP.attr1 분)의 기준점 |
| `suppress_cnt` | `integer` | N | N | 억제 창에 걸려 발송하지 않은 누적 횟수. 억제 설정이 과한지 보는 값 |
| `next_eval_at` | `timestamp with time zone` | N | N | 다음 평가 예정 시각. 엔진은 이 값이 지난 행만 가져감 |
| `upd_date` | `timestamp with time zone` | N | N | 최종 수정일시 — 엔진이 판정·발송·억제를 기록할 때마다 갱신 |

**테이블명:** `ax.tb_alm_cond_target`

**테이블 설명:** 조건의 개별 대상 목록. tb_alm_cond.target_scope_cd='PICK'(개별 설비 선택) 일 때만 사용 — 그 코드값이 있는데 담을 곳이 없음

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `cond_id` | `integer` | N | Y | 발송 조건 (tb_alm_cond). 조건이 지워지면 함께 삭제됨 |
| `target_dim_cd` | `character varying(30)` | N | Y | 대상 종류. 공통코드 ALM_SCOPE_DIM 과 같은 값을 사용(EQPT·WC·ITEM·MOLD·PRODUCT) |
| `target_cd` | `character varying(50)` | N | Y | 대상 코드. 설비코드·워크센터코드 등. 마스터가 지워져도 조건은 남으므로 FK 를 걸지 않음 |

**테이블명:** `ax.tb_alm_escalation_rule`

**테이블 설명:** 미확인 알림 승격 단계 — 1차 30분/파트장, 2차 2시간/팀장, 3차 4시간/경영진

> **제거됨(2026-10-03).** 알림 엔진이 승격을 하지 않아 V73 에서 이 표를 삭제합니다. 삭제 전 행은 `ax.tb_alm_escalation_rule_bak` 에 보관하고, `rollback/V73__down.sql` 이 표와 행을 되살립니다. 이 표를 읽던 API(승격 규칙 조회·수정, 승격 대상)도 함께 제거했습니다.

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `esc_rule_id` | `integer` | N | Y | 승격 규칙 식별자 (자동 채번) |
| `esc_level` | `smallint` | N | N | 승격 단계 (1=1차, 2=2차, 3=3차). 알림의 esc_level 이 이 값보다 작을 때만 등록 |
| `level_nm` | `character varying(20)` | N | N | 단계 표시명 (1차·2차·3차). 승격 알림 본문과 발송 사유에 그대로 쓰임 |
| `after_min` | `integer` | N | N | 발생 후 이 분(分)이 지나도록 미확인이면 승격한다 (1차 30 · 2차 120 · 3차 240) |
| `to_target_desc` | `character varying(100)` | N | N | 승격 대상 표시 문구 (예: 제조팀 파트장). 실제 발송은 to_group_id 로 함 |
| `to_group_id` | `integer` | Y | N | 승격 알림을 받을 수신 그룹. 비어 있으면 올리지 않고 발송 기록에 실패로 남김 |
| `severity_filter` | `character varying(30)` | Y | N | 이 단계로 승격시킬 심각도 제한 (예: CRIT 만 3차 승격). NULL 이면 전 심각도 |
| `note` | `character varying(300)` | Y | N | 이 단계를 두는 이유 메모 (예: 1차 승격 후에도 미확인) |
| `use_flg` | `common.d_yn` | N | N | 'Y' 면 엔진이 이 승격 단계를 적용 |

**테이블명:** `ax.tb_alm_eval_run`

**테이블 설명:** 알림 엔진 실행 이력. 아무 일도 없던 틱은 남기지 않음 — 1분 주기면 하루 1,440행이 쌓여 정작 봐야 할 실행이 묻힘. 알림·발송·실패가 하나라도 있을 때만 남기고, 조용한 구간은 시간당 1행 요약만 기록

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `run_id` | `character varying(30)` | N | Y | 실행 식별자. ALM-yyyyMMdd-HHmmss. 초까지 넣음 — 분 단위로는 같은 분의 두 실행이 한 행으로 겹침 |
| `started_at` | `timestamp with time zone` | N | N | 틱 시작 시각. 이 값이 1시간 넘게 끊기면 엔진이 죽은 것 |
| `ended_at` | `timestamp with time zone` | Y | N | 틱 종료 시각 |
| `duration_ms` | `integer` | Y | N | 틱 소요 시간 (밀리초) |
| `state_cd` | `character varying(30)` | N | N | 실행 결과. 공통코드 ALM_RUN_STATE. 조건 하나의 오류가 전체를 죽이지 않으므로 PARTIAL 이 흔함 |
| `cond_cnt` | `smallint` | N | N | 이번 틱에 읽은 활성 조건 수 (건) |
| `eval_cnt` | `integer` | N | N | 판정한 (조건 × 대상) 수 |
| `raise_cnt` | `integer` | N | N | 새로 만든 알림 수 (건). 억제 창에 묶인 재발은 suppress_cnt 로 집계 |
| `suppress_cnt` | `integer` | N | N | 중복 억제로 발송하지 않은 수 |
| `skip_cnt` | `integer` | N | N | 유효 시간대·야간 미수신으로 건너뛴 수 |
| `queued_cnt` | `integer` | N | N | 발송 대기열에 넣은 수 (건). 실제로 나간 수는 sent_cnt 참조 |
| `sent_cnt` | `integer` | N | N | 채널로 실제 내보낸 수 (건) |
| `fail_cnt` | `integer` | N | N | 발송 실패·연락처 없음·수신자 없음으로 실패 처리한 수 (건) |
| `triggered_by_cd` | `character varying(30)` | N | N | 실행 주체. 공통코드 ALM_RUN_TRIGGER |
| `triggered_by` | `common.d_user_id` | Y | N | 수동 실행한 사람 (사번). 정기 실행(BATCH)이면 NULL |
| `host_name` | `character varying(100)` | Y | N | 틱을 돈 서버 호스트명. 여러 대를 띄웠을 때 어느 쪽이 돌았는지 확인하는 값 |
| `engine_version` | `character varying(20)` | Y | N | 엔진 JAR 버전. 매니페스트에 없으면 1.0.0 으로 남음 |
| `message` | `character varying(2000)` | Y | N | 실패 사유를 이어 붙인 문구. 조용한 정기 확인이면 '변화 없음 (정기 확인)' |

**테이블명:** `ax.tb_alm_recip_group`

**테이블 설명:** 알림 수신 그룹 — 발송 조건이 이 그룹 이름만 참조. 멤버·연락처는 알림 수신자 관리에서만 처리

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `group_id` | `integer` | N | Y | 수신 그룹 식별자 (자동 채번) |
| `group_nm` | `character varying(50)` | N | N | 그룹 이름. 발송 조건은 이 이름만 참조하며 중복될 수 없음 |
| `window_cd` | `character varying(30)` | N | N | 수신 시간대. 공통코드 그룹 = ALM_WINDOW (ALWAYS=24시간 상시, D0820=08:00~20:00, D0618=06:00~18:00, WORKDAY=주간 근무일만, ONCE=지정 시각 1회) |
| `night_recv` | `boolean` | N | N | true 면 그룹 전체가 야간에도 수신. 멤버 개인 설정이 꺼져 있어도 발송 |
| `dept_id` | `integer` | Y | N | 대응 부서 (그룹이 부서 단위인 경우). "현장 반장" 처럼 부서와 무관한 그룹은 NULL |
| `use_flg` | `common.d_yn` | N | N | 'Y' 면 발송 대상으로 전개. 'N' 이면 멤버가 있어도 아무도 받지 않음 |
| `ins_date` | `timestamp with time zone` | N | N | 등록일시 |
| `ins_user` | `common.d_user_id` | Y | N | 등록자 (사번) |
| `upd_date` | `timestamp with time zone` | N | N | 최종 수정일시 |
| `upd_user` | `common.d_user_id` | Y | N | 최종 수정자 (사번) |

**테이블명:** `ax.tb_alm_recip_group_channel`

**테이블 설명:** 수신 그룹 기본 채널 (다중 선택)

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `group_id` | `integer` | N | Y | 수신 그룹 (tb_alm_recip_group). 그룹이 지워지면 함께 삭제됨 |
| `channel_cd` | `character varying(30)` | N | Y | 채널. 공통코드 그룹 = ALM_CHANNEL (MAIL=메일, POPUP=시스템 팝업, SMS=SMS, MSG=메신저 연동) |

**테이블명:** `ax.tb_alm_recip_group_member`

**테이블 설명:** 수신 그룹 × 수신자. 그룹은 멤버가 1명 이상이어야 하며(화면 검증) 멤버 제거는 이 행 삭제로 처리

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `group_id` | `integer` | N | Y | 수신 그룹 (tb_alm_recip_group). 그룹이 지워지면 함께 삭제됨 |
| `user_id` | `common.d_user_id` | N | Y | 그룹에 속한 수신자 (사번). 멤버 제외는 이 행을 지워서 함 |
| `ins_date` | `timestamp with time zone` | N | N | 등록일시 |
| `ins_user` | `common.d_user_id` | Y | N | 등록자 (사번) |

**테이블명:** `ax.tb_alm_recipient`

**테이블 설명:** 알림 수신자 연락처

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `user_id` | `common.d_user_id` | N | Y | 수신자 (사번). 계정 1건당 연락처 1행이며 계정이 지워지면 함께 삭제됨 |
| `email` | `character varying(200)` | N | N | 메일 주소. MAIL 채널의 수신 주소이며 '@' 가 없으면 등록되지 않음 |
| `mobile_no` | `character varying(20)` | Y | N | 휴대폰 번호 — 데이터 항목 worker(작업자 정보) 권한이 있는 계정에게만 표시 |
| `messenger_id` | `character varying(50)` | Y | N | 메신저 계정. MSG 채널의 수신 주소로 쓰며 비면 그 건은 실패로 남음 |
| `night_recv` | `boolean` | N | N | true 면 이 수신자는 야간에도 수신. false 면 야간 건을 SKIPPED 로 남기고 보내지 않음 |
| `recv_state_cd` | `character varying(30)` | N | N | 수신 상태. 공통코드 그룹 = ALM_RECV_STATE (RECV=수신, ABSENT=부재). 부재면 당번·대리 규칙에 따라 대리 수신자에게 발송 |
| `remark` | `character varying(300)` | Y | N | 비고. 알림 수신자 관리 목록이 읽지만 지금 저장하는 화면 기능은 없음 |
| `ins_date` | `timestamp with time zone` | N | N | 등록일시 |
| `ins_user` | `common.d_user_id` | Y | N | 등록자 (사번) |
| `upd_date` | `timestamp with time zone` | N | N | 최종 수정일시 |
| `upd_user` | `common.d_user_id` | Y | N | 최종 수정자 (사번) |

**테이블명:** `ax.tb_alm_send_log`

**테이블 설명:** 알림 발송 이력 — 수신자·채널 단위 (append-only)

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `send_id` | `bigint` | N | Y | 발송 기록 식별자 (자동 채번) |
| `alert_id` | `bigint` | N | N | 어느 알림의 발송인가 (tb_alm_alert). 알림이 지워지면 함께 삭제됨 |
| `group_id` | `integer` | Y | N | 발송 근거가 된 수신 그룹. 그룹을 펼치기 전에 실패했으면 NULL |
| `user_id` | `common.d_user_id` | Y | N | 받은 사람 (사번). 사람 단위로 펼치기 전에 실패했으면 NULL |
| `channel_cd` | `character varying(30)` | N | N | 발송 채널. 공통코드 그룹 = ALM_CHANNEL (MAIL=메일, POPUP=시스템 팝업, SMS=SMS, MSG=메신저 연동) |
| `dest_addr` | `character varying(200)` | Y | N | 실제 보낸 주소. 채널별로 메일 주소·휴대폰·메신저 계정이며 POPUP 은 사번 |
| `sent_at` | `timestamp with time zone` | N | N | 발송을 시도한 시각. 시도마다 1행이라 재시도는 행이 증가 |
| `send_result_cd` | `character varying(30)` | N | N | 발송 결과. 공통코드 그룹 = ALM_SEND_RESULT (SENT=발송, FAIL=실패, SUPPRESSED=중복 억제, SKIPPED=시간대 제외) |
| `fail_reason` | `character varying(300)` | Y | N | 보내지 못한 이유. 중복 억제·시간대 제외·연락처 없음도 여기에 기재 |
| `esc_level` | `smallint` | N | N | 이 발송이 몇 차 승격 건인가 (0=최초 발송) |
| `is_proxy` | `boolean` | N | N | true = 담당 부재로 대리 수신자에게 발송된 건 |
| `proxy_of_user_id` | `common.d_user_id` | Y | N | 대리 발송의 원래 담당자 |

**테이블명:** `ax.tb_alm_send_queue`

**테이블 설명:** 알림 발송 대기열. 발생(tb_alm_alert)과 발송을 떼어 SMTP 가 죽어도 알림은 남게 함. 발송 결과는 tb_alm_send_log 에 시도마다 1행으로 누적 (이 표를 덮어쓰지 않음)

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `queue_id` | `bigint` | N | Y | 대기열 식별자 (자동 채번) |
| `alert_id` | `bigint` | N | N | 보낼 알림 (tb_alm_alert). 알림이 지워지면 대기열도 함께 삭제됨 |
| `group_id` | `integer` | Y | N | 이 수신자를 꺼낸 수신 그룹 (tb_alm_recip_group) |
| `user_id` | `common.d_user_id` | Y | N | 받을 사람 (사번). 알림·사람·채널·승격단계가 같으면 한 번만 들어감 |
| `channel_cd` | `character varying(30)` | N | N | 발송 채널. 공통코드 그룹 = ALM_CHANNEL (MAIL=메일, POPUP=시스템 팝업, SMS=SMS, MSG=메신저 연동) |
| `dest_addr` | `character varying(200)` | Y | N | 보낼 주소. 채널별로 메일 주소·휴대폰·메신저 계정이며 POPUP 은 사번 |
| `subject` | `character varying(300)` | Y | N | 렌더링이 끝난 제목. 메일 제목으로 그대로 나감 |
| `body` | `text` | Y | N | 렌더링이 끝난 본문. 수신자의 부서 데이터 권한에 따라 마스킹된 상태로 유입 — 사람마다 내용이 다를 수 있음 |
| `esc_level` | `smallint` | N | N | 몇 차 승격으로 넣은 건인가 (0=최초 발송). 같은 단계는 한 번만 들어감 |
| `is_proxy` | `boolean` | N | N | 당직 대리 수신 여부. 원래 수신자가 부재(tb_alm_recipient.recv_state_cd)일 때 tb_alm_duty 로 대신 받는 경우 |
| `proxy_of_user_id` | `common.d_user_id` | Y | N | 대리 수신일 때 원래 수신자 |
| `state_cd` | `character varying(30)` | N | N | 처리 상태. 공통코드 ALM_QUEUE_STATE |
| `try_cnt` | `smallint` | N | N | 발송 시도 횟수 (회). 가져갈 때 올리며 5회째 실패하면 DEAD 로 중단 |
| `next_try_at` | `timestamp with time zone` | N | N | 다음 시도 시각. 실패하면 백오프(1m→5m→15m→30m→60m)만큼 뒤로 밀림 |
| `locked_by` | `character varying(100)` | Y | N | 집어간 워커 식별자(호스트+스레드). locked_at 이 5분을 넘으면 죽은 워커로 보고 PENDING 으로 회수 |
| `locked_at` | `timestamp with time zone` | Y | N | 워커가 집어간 시각. 300초를 넘으면 죽은 워커로 보고 PENDING 으로 회수 |
| `last_error` | `character varying(500)` | Y | N | 마지막 시도의 실패 사유. 성공하면 비움 |
| `ins_date` | `timestamp with time zone` | N | N | 등록일시. 발송이 끝난 행은 이 값 기준 7일 뒤 정리 |

**테이블명:** `ax.tb_dash_upload_doc`

**테이블 설명:** AI 통합 대시보드 업로드 문서 — 작업자가 올린 엑셀의 묶음 단위. 버전은 tb_dash_upload_ver

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `doc_id` | `bigint` | N | Y | 업로드 문서 대리키. 업로드 문서 목록 화면의 검색어(문서 ID)와 버전 이력 드로어 부제에 표시 |
| `title` | `character varying(200)` | N | N | 문서 제목. 대시보드·시스템관리 목록에 표시됨. 업로드 문서 목록 화면의 「문서명」 열 · AI 통합 대시보드 화면의 업로드 리포트 문서 선택 드롭다운 |
| `memo` | `character varying(1000)` | Y | N | 업로더가 남기는 설명. 화면 표시용이며 파싱에는 쓰지 않음. 업로드 문서 목록 화면의 「문서명」 열 아래 회색 보조 문구 |
| `latest_ver` | `integer` | N | N | 최신 버전 번호. tb_dash_upload_ver 의 max(ver) 와 같아야 하며 버전 등록 트랜잭션에서 함께 갱신 |
| `del_flg` | `common.d_yn` | N | N | Y = 목록에서 숨김. 버전·파일은 지우지 않음 |
| `ins_date` | `timestamp with time zone` | N | N | 최초 업로드 일시 (목록의 createdAt) |
| `ins_user` | `common.d_user_id` | Y | N | 최초 업로더 (시스템 관리 목록의 createdBy) |
| `upd_date` | `timestamp with time zone` | N | N | 최종 수정일시. 새 버전이 올라오면 함께 갱신됨. 업로드 문서 목록 화면의 「최근 업로드」 열 |
| `upd_user` | `common.d_user_id` | Y | N | 최종 수정자 (사번). 업로드 문서 목록 화면의 「최근 업로더」 열 |

**테이블명:** `ax.tb_dash_upload_ver`

**테이블 설명:** 업로드 문서 버전 — 파일 1개 = 버전 1개. 원본은 storage_path, 화면용 데이터는 parse_json

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `doc_id` | `bigint` | N | Y | 소속 업로드 문서 (ax.tb_dash_upload_doc). 업로드 문서 목록 화면의 행을 펼친 버전 이력의 묶음 기준 |
| `ver` | `integer` | N | Y | 버전 번호 (1부터). 문서 안에서만 유일. 업로드 문서 목록 화면의 「최신 버전」 열(v3)과 버전 이력 드로어의 버전 |
| `file_nm` | `character varying(300)` | N | N | 업로드 당시 원본 파일명. 업로드 문서 목록 화면의 버전 이력 드로어에 버전마다 표시 |
| `storage_path` | `character varying(500)` | N | N | 원본 파일 저장 경로. DB 에는 파일을 넣지 않음 |
| `file_size` | `bigint` | N | N | 원본 파일 크기(byte). 업로드 문서 목록 화면의 「크기」 열에 KB·MB 로 환산해 표시 |
| `sha256` | `character(64)` | N | N | 원본 SHA-256 (16진 64자). 같은 문서에 같은 해시가 올라오면 API 가 안내할 수 있음 |
| `parse_state_cd` | `character varying(30)` | N | N | 파싱 결과. 공통코드 그룹 = DASH_UPLOAD_PARSE (OK=정상, WARN=경고 있음, FAIL=실패 — parse_json 없음) |
| `parse_json` | `jsonb` | Y | N | 엑셀을 화면 계약으로 정규화한 결과. 시트별 { title, chartType(line / bar / grouped / donut / table), x, series[], rows[] } |
| `warning_json` | `jsonb` | Y | N | 파싱 경고 목록. parse_state_cd='WARN' 일 때 무엇이 걸렸는지 담음. AI 통합 대시보드 화면의 업로드 리포트 상단 경고 줄과 버전 이력의 「경고 N건」 |
| `ins_date` | `timestamp with time zone` | N | N | 업로드 일시 (uploadedAt). 업로드 문서 목록 화면 버전 이력 드로어의 업로더 옆 일시 |
| `ins_user` | `common.d_user_id` | Y | N | 업로더 (uploadedBy). ins_date = uploadedAt |

**테이블명:** `ax.tb_gls_domain`

**테이블 설명:** 용어 분류/도메인 — 회사/고객사, 품질관리, 프로젝트, 제품/부품, 불량유형, 조직/부서 등 20종

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `domain_id` | `integer` | N | Y | 용어 분류 대리키 |
| `domain_nm` | `character varying(50)` | N | N | 분류명. 용어 사전 관리 화면의 분류 선택지가 이 값. 같은 화면의 「분류」 열과 공식 용어 등록 폼의 분류 선택 |
| `sort_seq` | `smallint` | N | N | 분류 선택지 표시 순서 |
| `use_flg` | `common.d_yn` | N | N | 사용 유무 (Y/N). N 이면 분류 선택지에서 빠짐 |

**테이블명:** `ax.tb_gls_term`

**테이블 설명:** 공식 용어 — 보고서·리포트 출력 표기 기준. 통합관리자만 등록·수정

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `term_id` | `integer` | N | Y | 공식 용어 대리키 |
| `term` | `character varying(50)` | N | N | 공식 용어 표기. 대소문자를 무시하고 중복 금지. 용어 사전 관리 화면 「공식 용어」 열 |
| `term_def` | `character varying(500)` | N | N | 용어 정의 · 설명 |
| `domain_id` | `integer` | N | N | 소속 분류 (ax.tb_gls_domain). 용어 사전 관리 화면의 「분류」 열에 분류명으로 표시 |
| `use_flg` | `common.d_yn` | N | N | 사용 유무 (Y/N). N 이면 정규화·검색 대상에서 빠짐 |
| `ins_date` | `timestamp with time zone` | N | N | 등록일시 |
| `ins_user` | `common.d_user_id` | Y | N | 등록자 (사번) |
| `upd_date` | `timestamp with time zone` | N | N | 최종 수정일시 |
| `upd_user` | `common.d_user_id` | Y | N | 최종 수정자 (사번) |

**테이블명:** `ax.tb_gls_variant`

**테이블 설명:** 유사어 — 현장에서 실제로 쓰는 말·약칭·한글 표기. 자연어 질의와 보고서 생성 시 공식 용어로 정규화하는 데 사용

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `variant_id` | `integer` | N | Y | 유사어 대리키 |
| `term_id` | `integer` | N | N | 정규화 대상 공식 용어 (ax.tb_gls_term) |
| `word` | `character varying(50)` | N | N | 현장에서 실제로 쓰는 말·약칭. 질의에서 이 말이 나오면 공식 용어로 치환. 용어 사전 관리 화면 「유사어 (등록자)」 열의 칩 글자 |
| `owner_user_id` | `common.d_user_id` | N | N | 등록자. 등록 본인만 수정·삭제할 수 있다 (애플리케이션에서 검증) |
| `owner_dept_nm` | `character varying(50)` | Y | N | 등록 당시 주 사용 부서명 스냅샷 |
| `note` | `character varying(300)` | Y | N | 등록 맥락 메모 (어느 공정·문서에서 쓰는 말인지) |
| `reg_at` | `timestamp with time zone` | N | N | 등록일시 |
| `upd_at` | `timestamp with time zone` | N | N | 최종 수정일시 |

**테이블명:** `ax.tb_log_audit`

**테이블 설명:** 보안 감사 로그 — 보안 필터링 처리 · 원본 조회 · 권한 변경 · 로그인 · 자동 생성 이력 통합 (보존 3년, append-only)

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `audit_id` | `bigint` | N | Y | 감사 로그 대리키 |
| `log_at` | `timestamp with time zone` | N | N | 행위 발생 시각. 화면 기본 정렬 키. 보안 감사 로그의 「시각」 열 |
| `log_type_cd` | `character varying(30)` | N | N | 유형. 공통코드 그룹 = LOG_AUDIT_TYPE (MASK=마스킹 처리, UNMASK_REQ=마스킹 해제 요청, RAW_VIEW=원본 조회, PERM_CHANGE=권한 변경, LOGIN=로그인, AUTO_GEN=자동 생성) |
| `user_id` | `common.d_user_id` | Y | N | 행위자 사번. 삭제된 계정의 기록도 보존하므로 FK 를 걸지 않음 |
| `dept_nm` | `character varying(50)` | Y | N | 수행 당시 소속 부서명 스냅샷 |
| `menu_id` | `character varying(30)` | Y | N | 대상 화면. tb_sys_menu 를 논리 참조하나 화면 삭제 후에도 기록을 보존하므로 FK 를 걸지 않음 |
| `field_key` | `character varying(30)` | Y | N | 대상 데이터 항목 (tb_sys_data_field 논리 참조) |
| `target_desc` | `character varying(300)` | Y | N | 무엇을 대상으로 했는지 사람이 읽는 설명 (화면·보고서·조회 조건). 보안 감사 로그의 「대상」 열 |
| `result_cd` | `character varying(30)` | N | N | 처리 결과. 공통코드 그룹 = LOG_AUDIT_RESULT (ALLOW=허용/열람, BLIND=blind 처리, REJECT=반려) |
| `masked_cnt` | `integer` | N | N | 마스킹 처리된 항목 수 |
| `remark` | `character varying(500)` | Y | N | 비고. 판정 근거나 반려 사유처럼 자유 서술을 남김 |
| `ip_addr` | `inet` | Y | N | 행위자 접속 IP. 화면에는 host() 로 주소만 표시 |
| `plant_cd` | `common.d_plant_cd` | Y | N | 대상이 특정 사업부인 경우의 사업부 코드 |
| `wc_cd` | `common.d_wc_cd` | Y | N | 대상이 특정 작업장인 경우의 작업장 코드 |
| `lot_no` | `common.d_lot_no` | Y | N | 대상이 특정 LOT 인 경우의 LOT NO. mes.tb_pop_label_hist 와 조인 가능 |
| `serial_no` | `common.d_lot_serial` | Y | N | 대상 LOT 의 시리얼 번호. lot_no 와 함께 라벨 이력을 가리킴 |
| `item_cd` | `common.d_item_cd` | Y | N | 대상이 특정 품목인 경우의 품목 코드 |

**테이블명:** `ax.tb_met_metric_collect`

**테이블 설명:** 지표 실측치(ax.tb_met_metric_value)를 채우는 방법. 지금 그 표가 0행이라 알림 조건이 비교할 값이 없음 — 그 구멍을 메우는 정의

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `metric_id` | `integer` | N | Y | 수집 정의를 붙일 지표 (ax.tb_met_metric_std). 지표 1건에 정의 1행 |
| `collect_mode_cd` | `character varying(30)` | N | N | 수집 방식. 공통코드 MET_COLLECT_MODE. 1단계는 BUILTIN 만 사용 |
| `collector_cd` | `character varying(50)` | Y | N | BUILTIN 일 때 Kotlin 구현체 키. 비우면 tb_met_metric_std.metric_cd 를 사용 |
| `dim_cd` | `character varying(30)` | N | N | 값을 쪼개는 단위. 공통코드 ALM_SCOPE_DIM. 'EQPT' 면 설비마다 값이 따로 누적 |
| `interval_sec` | `integer` | N | N | 수집 주기(초). 기본 300(5분) |
| `lookback_min` | `integer` | N | N | 한 번 수집할 때 거슬러 보는 구간(분). 이관이 늦은 원본을 메우려고 주기보다 넉넉히 설정 |
| `sql_text` | `text` | Y | N | 집계 SQL (collect_mode_cd='SQL' 전용). 2단계 기능이라 지금은 사용하지 않음 — 이 컬럼에 쓰기 권한을 주는 것은 DB 에서 임의 SQL 을 돌릴 권한을 주는 것과 같음. 읽기 전용 롤 분리·SELECT 단일문 검증·감사 로그가 갖춰진 뒤에 개방 |
| `use_flg` | `common.d_yn` | N | N | 수집 스위치. 기본 'N'(미수집) — 구현체가 붙고 값을 확인한 뒤 'Y' 로 전환 |
| `last_run_at` | `timestamp with time zone` | Y | N | 마지막 수집 시도 시각. 값이 안 쌓여도 갱신됨 |
| `last_value_at` | `timestamp with time zone` | Y | N | 마지막으로 값이 쌓인 시각. interval_sec × 3 보다 오래되면 엔진이 그 지표를 판정하지 않음 — 멈춘 수집의 낡은 값으로 알림을 내면 이미 끝난 이상이 계속 나가거나 진짜 이상을 정상으로 오판 |
| `last_error` | `character varying(500)` | Y | N | 마지막 수집 실패 사유. 성공하면 비움 |
| `ins_date` | `timestamp with time zone` | N | N | 등록일시 |
| `ins_user` | `common.d_user_id` | Y | N | 등록자 (사번) |
| `upd_date` | `timestamp with time zone` | N | N | 최종 수정일시 |
| `upd_user` | `common.d_user_id` | Y | N | 최종 수정자 (사번) |

**테이블명:** `ax.tb_met_metric_source`

**테이블 설명:** 지표 산출 근거 매핑 — 지표가 mes 스키마의 어느 테이블·컬럼에서 계산되는지 등록한다 (예: 공정 불량률 → mes.tb_pop_defect_hist.qty / mes.tb_pop_label_hist.normal)

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `metric_id` | `integer` | N | Y | 산출 근거를 붙일 지표 (ax.tb_met_metric_std) |
| `src_seq` | `smallint` | N | Y | 한 지표가 여러 원본을 쓸 때의 순번 (분자·분모 등) |
| `src_schema` | `character varying(63)` | N | N | 원본 스키마명 (보통 mes) |
| `src_table` | `character varying(63)` | N | N | 원본 테이블명 (예: tb_pop_defect_hist) |
| `src_column` | `character varying(63)` | Y | N | 원본 컬럼명. 행 수만 세는 경우 등에는 비움 |
| `agg_expr` | `character varying(300)` | Y | N | 집계 식 서술 (예: sum(qty) / sum(normal)) |
| `remark` | `character varying(200)` | Y | N | 비고 — 집계에서 제외하는 조건 등 보충 설명 |

**테이블명:** `ax.tb_met_metric_std`

**테이블 설명:** 지표 기준 수치 — 정상/주의/위험 임계값. 이상 알림 발송 판정, 대시보드 목표선·색상, 보고서 신호등 색에 함께 사용됨

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `metric_id` | `integer` | N | Y | 지표 대리키 |
| `metric_cd` | `character varying(30)` | N | N | 지표 코드. API·수집 구현체가 이 값으로 지표를 찾는다 (예: EQPT_UPTIME_RATE) |
| `metric_nm` | `character varying(100)` | N | N | 지표 표시명. 화면·보고서에 그대로 표시 |
| `cat_cd` | `character varying(30)` | N | N | 구분. 공통코드 그룹 = MET_CATEGORY (DEFECT=불량, EQPT=설비 장애, PROD=생산, COLLECT=데이터 수집, COST=원가) |
| `unit_cd` | `character varying(20)` | N | N | 단위. 공통코드 그룹 = MET_UNIT (PCT=%, CNT=건, MIN=분, SEC=초, HOUR=시간, EA=EA, KSTROKE=천타, MKRW=백만원) |
| `std_val` | `numeric(18,4)` | N | N | 정상 기준 |
| `warn_val` | `numeric(18,4)` | N | N | 주의 임계 |
| `crit_val` | `numeric(18,4)` | N | N | 위험 임계. crit_val >= warn_val 이면 "값이 클수록 나쁨", 반대면 "값이 작을수록 나쁨" 으로 판정 방향을 결정 |
| `window_cd` | `character varying(30)` | N | N | 집계 구간. 공통코드 그룹 = MET_WINDOW (IMMEDIATE=즉시, MA5=5분 연속, MA10=10분 이동, MA120=2시간 이동, DAY_CLOSE=일 마감, DAY_AVG=일 평균, MONTH_SUM=월 누계, BATCH=배치별) |
| `calc_base` | `character varying(300)` | N | N | 산출 근거 서술 (예: MES 생산 이력 · AOI 판정 로그) |
| `owner_dept_id` | `integer` | Y | N | 소관 부서 — 이 부서와 전산팀·통합관리자만 기준을 변경할 수 있음 |
| `apply_alert` | `boolean` | N | N | true 면 이상 알림 발송 조건에서 이 지표를 고를 수 있음 |
| `apply_dashboard` | `boolean` | N | N | true 면 대시보드 목표선·신호등 색에 이 기준을 사용 |
| `apply_report` | `boolean` | N | N | true 면 보고서 신호등 색에 이 기준을 사용 |
| `blind_field_key` | `character varying(30)` | Y | N | 이 지표 값이 blind 대상인 경우의 데이터 항목 (예: 폐기 금액 → price) |
| `use_flg` | `common.d_yn` | N | N | 사용 유무 (Y/N). N 이면 화면 목록과 판정에서 모두 빠짐 |
| `ins_date` | `timestamp with time zone` | N | N | 등록일시 |
| `ins_user` | `common.d_user_id` | Y | N | 등록자 (사번) |
| `upd_date` | `timestamp with time zone` | N | N | 최종 수정일시 |
| `upd_user` | `common.d_user_id` | Y | N | 최종 수정자 (사번) |

**테이블명:** `ax.tb_met_metric_value`

**테이블 설명:** 지표 측정 데이터 — 지표별 측정 시점 값. MES 차원 키를 함께 보관해 설비·작업장·품목·LOT 단위로 조인 조회

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `value_id` | `bigint` | N | Y | 측정값 대리키. 같은 시각이 겹칠 때의 2차 정렬 키로도 사용 |
| `metric_id` | `integer` | N | N | 측정 대상 지표 (ax.tb_met_metric_std) |
| `measured_at` | `timestamp with time zone` | N | N | 측정 시각. 조회·판정 구간의 기준이며 알림 엔진이 이 값의 최신성을 확인 |
| `metric_value` | `numeric(18,6)` | N | N | 측정값. 단위는 지표의 unit_cd 를 따름 |
| `judge_cd` | `character varying(30)` | N | N | 판정. 공통코드 그룹 = MET_JUDGE (NORMAL=정상, WARN=주의, CRIT=위험). 측정 시점 기준값으로 판정해 저장 |
| `plant_cd` | `common.d_plant_cd` | Y | N | 사업부 구분 코드 (PL01=모바일, PL03=전장) |
| `wc_cd` | `common.d_wc_cd` | Y | N | 작업장 코드 — mes.tb_md_workcenter 와 조인 |
| `eqpt_cd` | `common.d_eqpt_cd` | Y | N | 설비 코드 — mes.tb_md_eqpt 와 조인 |
| `mold_cd` | `common.d_mold_cd` | Y | N | 금형 코드 — mes.tb_md_mold 와 조인 |
| `item_cd` | `common.d_item_cd` | Y | N | 품목 코드 — mes.tb_md_item 와 조인 |
| `lot_no` | `common.d_lot_no` | Y | N | LOT 번호 — mes.tb_pop_label_hist 와 조인 |
| `serial_no` | `common.d_lot_serial` | Y | N | LOT 시리얼 번호. lot_no 와 함께 LOT 을 가리킴 |
| `product_id` | `integer` | Y | N | 제품(모델) — ax.tb_prod_product. 품목 매핑을 거치지 않고 바로 붙일 때 사용 |
| `src_cd` | `character varying(30)` | Y | N | 수집 원본. 공통코드 그룹 = MET_SRC (MES=MES 이관, IOT=설비 IoT, AOI=AOI 로그, BATCH=배치 집계, MANUAL=수동 입력) |
| `remark` | `character varying(300)` | Y | N | 비고 — 보정·재계산 등 이 값에 붙는 메모 |
| `ins_date` | `timestamp with time zone` | N | N | 등록일시. 측정 시각(measured_at)과 다를 수 있음 |

**테이블명:** `ax.tb_prod_customer`

**테이블 설명:** 고객사 — 데이터 접근 항목 customer 의 blind 대상. 보고서 양식의 고객사별 공개 정책과도 연결됨

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `customer_id` | `integer` | N | Y | 고객사 대리키 |
| `customer_cd` | `character varying(30)` | Y | N | 고객사 코드. 외부 표기·파일명에 쓰는 약칭 |
| `customer_nm` | `character varying(100)` | N | N | 고객사명. 데이터 접근 항목 customer 의 blind 대상. 고객사별 LRR 화면의 「고객사」 열과 연간 출하계획 화면의 「고객사」 열 |
| `disclosure_note` | `character varying(300)` | Y | N | 고객사별 공개 정책 요약 (예: 단가·수율 비공개) |
| `use_flg` | `common.d_yn` | N | N | 사용 유무 (Y/N) |
| `ins_date` | `timestamp with time zone` | N | N | 등록일시 |
| `ins_user` | `common.d_user_id` | Y | N | 등록자 (사번) |
| `upd_date` | `timestamp with time zone` | N | N | 최종 수정일시 |
| `upd_user` | `common.d_user_id` | Y | N | 최종 수정자 (사번) |

**테이블명:** `ax.tb_prod_daily_decision`

**테이블 설명:** 일일 생산현황 보고 아침회의 결과 — 제품별 일목표·판정·담당·기한. 문서를 저장하지 않으므로 (대상일, 제품) 이 키

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `target_date` | `date` | N | Y | 보고 대상일 (작성일이 아님). 제품과 함께 키를 구성. 일일 생산현황 보고의 조회 기준일 |
| `product` | `common.d_item_cd` | N | Y | 제품 코드(model_cd). 품목 매핑이 없으면 item_cd 가 그대로 유입 |
| `target_qty` | `numeric(18,6)` | Y | N | 작성자가 입력한 일목표. 정식 출처(제품·공정별 목표 마스터)가 생기면 그쪽 참조로 이관 |
| `decision` | `character varying(1000)` | Y | N | 아침회의 판정 내용 |
| `dri` | `character varying(100)` | Y | N | 담당(부서 또는 담당자) |
| `due_date` | `date` | Y | N | 조치 기한 |
| `ins_date` | `timestamp with time zone` | N | N | 등록일시 |
| `ins_user` | `common.d_user_id` | Y | N | 등록자 (사번) |
| `upd_date` | `timestamp with time zone` | Y | N | 최종 수정일시 |
| `upd_user` | `common.d_user_id` | Y | N | 최종 수정자 (사번) |

**테이블명:** `ax.tb_prod_day_target`

**테이블 설명:** 제품·공정별 일목표 마스터 — 적용일부터 다음 적용일 전까지 유효

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `target_id` | `bigint` | N | Y | 일목표 대리키 |
| `plant_cd` | `common.d_plant_cd` | N | N | 사업부 구분 코드 (PL01=모바일, PL03=전장) |
| `product` | `common.d_item_cd` | N | N | 제품 코드(model_cd). 품목 매핑이 없으면 item_cd 를 사용 |
| `wc_cd` | `common.d_wc_cd` | N | N | 작업장(공정) 코드. 같은 제품도 공정마다 목표가 다름 |
| `apply_from` | `date` | N | N | 적용 시작일. 종료일은 두지 않고 다음 적용일 전까지 유효한 것으로 간주 |
| `target_qty` | `numeric(18,6)` | N | N | 일목표 수량. 일일 보고에서 작성자가 넣은 값(tb_prod_daily_decision)이 있으면 그 값이 우선 |
| `remark` | `character varying(500)` | Y | N | 비고 — 목표를 그렇게 잡은 근거를 남김 |
| `ins_date` | `timestamp with time zone` | N | N | 등록일시 |
| `ins_user` | `common.d_user_id` | Y | N | 등록자 (사번) |
| `upd_date` | `timestamp with time zone` | Y | N | 최종 수정일시 |
| `upd_user` | `common.d_user_id` | Y | N | 최종 수정자 (사번) |

**테이블명:** `ax.tb_prod_downtime`

**테이블 설명:** 설비 비가동 이력 — IoT 자동 감지분과 사람이 등록한 사유

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `downtime_id` | `bigint` | N | Y | 비가동 이력 대리키 |
| `plant_cd` | `common.d_plant_cd` | N | N | 사업부 구분 코드 (PL01=모바일, PL03=전장) |
| `eqpt_cd` | `common.d_eqpt_cd` | N | N | 멈춘 설비 코드 — mes.tb_md_eqpt 와 조인 |
| `wc_cd` | `common.d_wc_cd` | Y | N | 설비가 속한 작업장 코드. 공정 단위 집계에 사용 |
| `stop_at` | `timestamp with time zone` | N | N | 정지 시각. 비가동 구간의 시작 |
| `resume_at` | `timestamp with time zone` | Y | N | 재가동 시각. 비어 있으면 아직 멈춰 있는 건 |
| `elapsed_min` | `integer` | Y | N | 비가동 시간(분). resume_at - stop_at 으로 API 가 계산해 넣음 |
| `reason_cd` | `character varying(30)` | Y | N | 비가동 사유 (DOWN_REASON 공통코드) |
| `remark` | `character varying(500)` | Y | N | 비고 — 사유 등록 시 담당자가 남기는 설명 |
| `is_registered` | `boolean` | N | N | 사유 등록 여부 — false 면 미등록(사유 미입력) 건 |
| `detected_by_cd` | `character varying(30)` | N | N | 감지 출처 — IOT / MES / MANUAL |
| `agent_reason_cd` | `character varying(30)` | Y | N | Agent 가 제안한 사유 후보 (채택 전) |
| `agent_confidence` | `numeric(5,4)` | Y | N | Agent 제안 사유의 확신도(0~1). 제안 기능 미구현이라 현재 전건 NULL |
| `agent_basis` | `character varying(300)` | Y | N | Agent 가 그 사유를 제안한 근거. 제안 기능 미구현이라 현재 전건 NULL |
| `registered_at` | `timestamp with time zone` | Y | N | 사유를 등록한 시각. is_registered 가 true 가 된 시점 |
| `registered_by` | `common.d_user_id` | Y | N | 사유를 등록한 사람 (사번) |
| `ins_date` | `timestamp with time zone` | N | N | 등록일시 — 감지·수집으로 행이 만들어진 시각 |
| `ins_user` | `common.d_user_id` | Y | N | 등록자 (사번). IoT 자동 감지분은 비어 있음 |
| `upd_date` | `timestamp with time zone` | N | N | 최종 수정일시 |
| `upd_user` | `common.d_user_id` | Y | N | 최종 수정자 (사번) |

**테이블명:** `ax.tb_prod_family`

**테이블 설명:** 제품군 — 순위 관리 단위. 순위를 바꾸면 소속 제품의 매출 순위가 한꺼번에 밀림

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `family_id` | `integer` | N | Y | 제품군 대리키 |
| `family_cd` | `character varying(10)` | N | N | 제품군 코드 (SHD, CAN, STF, BFL, CM, RNG, PLT) |
| `family_nm` | `character varying(50)` | N | N | 제품군명. 대시보드·보고서의 제품군 축에 표시. 공정 및 제품 대시보드 화면 제품 선택 팝업의 「제품군」 열과 제품군 필터 |
| `rank_no` | `smallint` | N | N | 제품군 순위 (1 = 주력). 순위 재배치 시 여러 행이 동시에 바뀌므로 UNIQUE 를 DEFERRABLE 로 선언 |
| `def_rank_no` | `smallint` | N | N | 기본 순서 — "기본 순서 복원" 기능이 되돌릴 값 |
| `use_flg` | `common.d_yn` | N | N | 사용 유무 (Y/N) |
| `ins_date` | `timestamp with time zone` | N | N | 등록일시 |
| `ins_user` | `common.d_user_id` | Y | N | 등록자 (사번) |
| `upd_date` | `timestamp with time zone` | N | N | 최종 수정일시 |
| `upd_user` | `common.d_user_id` | Y | N | 최종 수정자 (사번) |

**테이블명:** `ax.tb_prod_item_map`

**테이블 설명:** 제품(모델) ↔ MES 품목 매핑. mes.tb_md_item (plant_cd, item_cd) 를 논리 참조하며 MES 실적을 모델 단위로 집계하는 기준이 됨

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `plant_cd` | `common.d_plant_cd` | N | Y | 사업부 구분 코드 (PL01=모바일, PL03=전장) |
| `item_cd` | `common.d_item_cd` | N | Y | MES 품목 코드 — mes.tb_md_item 을 논리 참조 |
| `product_id` | `integer` | N | N | 이 품목이 속하는 제품(모델) — ax.tb_prod_product |
| `map_kind_cd` | `character varying(30)` | N | N | 매핑 구분. 공통코드 그룹 = PROD_MAP_KIND (PRODUCT=제품, SEMI=반제품, WIP=재공) |
| `remark` | `character varying(200)` | Y | N | 비고 — 매핑 근거나 예외 처리 메모 |
| `ins_date` | `timestamp with time zone` | N | N | 등록일시 |
| `ins_user` | `common.d_user_id` | Y | N | 등록자 (사번) |

**테이블명:** `ax.tb_prod_product`

**테이블 설명:** 제품(모델) 마스터 — 대시보드의 주력 제품 Top N 이 이 순위를 따름

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `product_id` | `integer` | N | Y | 제품(모델) 대리키 |
| `model_cd` | `character varying(50)` | N | N | 모델 코드 (KRIOS, EOS-S, BOI, SHD-101 ...) |
| `model_nm` | `character varying(100)` | Y | N | 모델 표시명. 비우면 화면이 model_cd 를 그대로 사용. 공정 및 제품 대시보드 화면 제품 선택 팝업의 「제품명」 열 |
| `family_id` | `integer` | N | N | 소속 제품군 (ax.tb_prod_family). 공정 및 제품 대시보드 화면 제품 선택 팝업의 「제품군」 열에 이름으로 표시 |
| `customer_id` | `integer` | Y | N | 납품 고객사 (ax.tb_prod_customer). 데이터 접근 항목 customer 의 blind 대상. 연간 출하계획 표의 「고객사」 열에 이름으로 표시 |
| `project_id` | `integer` | Y | N | 소속 프로젝트 (ax.tb_prod_project). 공정 및 제품 대시보드 화면 제품 선택 팝업의 「프로젝트」 열에 이름으로 표시 |
| `seq_in_family` | `smallint` | N | N | 제품군 내 순서 |
| `def_seq` | `smallint` | N | N | 기본 순서 — "기본 순서 복원" 이 seq_in_family 를 되돌릴 값 |
| `rank_no` | `integer` | Y | N | 전체 매출 순위 — 제품군 순위와 제품군 내 순서로 재계산되는 파생값 (순위 변경 시 일괄 갱신) |
| `use_flg` | `common.d_yn` | N | N | 사용 유무 (Y/N) |
| `ins_date` | `timestamp with time zone` | N | N | 등록일시. 공정 및 제품 대시보드 화면 제품 선택 팝업의 「등록일」 열 |
| `ins_user` | `common.d_user_id` | Y | N | 등록자 (사번) |
| `upd_date` | `timestamp with time zone` | N | N | 최종 수정일시. 공정 및 제품 대시보드 화면 제품 선택 팝업의 「수정일」 열 |
| `upd_user` | `common.d_user_id` | Y | N | 최종 수정자 (사번) |

**테이블명:** `ax.tb_prod_project`

**테이블 설명:** 프로젝트 — MEM · VR · Sphinx · Centaur · PDX · CM 등. mes.tb_md_item.project_nm 과 대응

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `project_id` | `integer` | N | Y | 프로젝트 대리키 |
| `project_cd` | `character varying(30)` | Y | N | 프로젝트 코드. 외부 표기용 약칭 |
| `project_nm` | `character varying(50)` | N | N | 프로젝트명. mes.tb_md_item.project_nm 과 대응. 공정 및 제품 대시보드 화면 제품 선택 팝업의 「프로젝트」 열과 프로젝트 필터 |
| `use_flg` | `common.d_yn` | N | N | 사용 유무 (Y/N) |

**테이블명:** `ax.tb_prod_ship_plan`

**테이블 설명:** 연간·월별 출하계획 — 데이터 접근 항목 plan / price 대상

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `plan_id` | `bigint` | N | Y | 출하계획 대리키 |
| `plan_year` | `smallint` | N | N | 계획 연도. (연·월·제품)이 유일해야 함. 연간 출하계획의 「계획 연도」 필터 |
| `plan_month` | `smallint` | N | N | 계획 월 (1~12). 연간 출하계획 화면 표의 월 열 12칸을 가르는 값 |
| `product_id` | `integer` | N | N | 계획 대상 제품(모델) — ax.tb_prod_product. 연간 출하계획 화면 표의 「모델」 열 |
| `customer_id` | `integer` | Y | N | 납품 고객사 (ax.tb_prod_customer). 연간 출하계획 화면 표의 「고객사」 열 |
| `plan_qty` | `numeric(18,6)` | N | N | 월별 출하 계획 수량(EA). 데이터 접근 항목 plan 의 blind 대상. 연간 출하계획 화면의 「모델·고객사별 월 출하계획」 표의 각 월 값 |
| `unit_price` | `numeric(18,4)` | Y | N | 출하 단가(원). 데이터 접근 항목 price 의 blind 대상 |
| `plan_amount` | `numeric(18,4)` | Y | N | 계획 금액 = 계획 수량 × 단가 (price 권한 필요) |
| `actual_qty` | `numeric(18,6)` | Y | N | 실제 출하 수량(EA). 계획 대비 달성률의 분자 |
| `remark` | `character varying(300)` | Y | N | 비고 — 계획 변경 사유 등 |
| `ins_date` | `timestamp with time zone` | N | N | 등록일시 |
| `ins_user` | `common.d_user_id` | Y | N | 등록자 (사번) |
| `upd_date` | `timestamp with time zone` | N | N | 최종 수정일시 |
| `upd_user` | `common.d_user_id` | Y | N | 최종 수정자 (사번) |

**테이블명:** `ax.tb_qc_lrr_notice`

**테이블 설명:** 고객사 LRR(라인 불량) 통보 접수 이력 — LRR(%) = LRR Q'ty / Ship Q'ty

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `notice_id` | `bigint` | N | Y | LRR 통보 대리키 |
| `notice_no` | `character varying(50)` | Y | N | 고객사가 부여한 통보 문서 번호. 고객사 문의 시 대조하는 값 |
| `customer_id` | `integer` | N | N | 통보한 고객사 (ax.tb_prod_customer). 고객사별 LRR 화면 「고객사 누계」 표의 「고객사」 열 |
| `product_id` | `integer` | Y | N | 대상 제품(모델) — ax.tb_prod_product |
| `plant_cd` | `common.d_plant_cd` | Y | N | 사업부 구분 코드 (PL01=모바일, PL03=전장) |
| `lot_no` | `common.d_lot_no` | Y | N | 대상 LOT 번호 — mes.tb_pop_label_hist 와 조인 |
| `item_cd` | `common.d_item_cd` | Y | N | 대상 품목 코드 — mes.tb_md_item 와 조인 |
| `defect_cd` | `common.d_defect_cd` | Y | N | 불량 코드 — mes.tb_md_defect 와 조인. 표시명은 마스터 우선 |
| `defect_txt` | `character varying(200)` | Y | N | 고객사가 적어 온 불량 내용. 코드로 분류되지 않을 때 이 글을 사용. 고객사별 LRR 화면의 「불량 유형별 발생 건수」 피벗의 행 이름 |
| `notice_date` | `date` | N | N | 고객사에게 통보받은 날. 집계 귀속은 통보일이 아니라 출하 연월 기준 |
| `ship_year` | `smallint` | N | N | 귀속 출하 연도 — 통보일이 아닌 출하 시점 기준으로 집계 |
| `ship_month` | `smallint` | N | N | 귀속 출하 월 (1~12). 고객사별 LRR 화면 피벗 표의 기간 열을 가르는 값 |
| `lrr_qty` | `numeric(18,6)` | N | N | 고객사가 통보한 불량 수량(EA). LRR(%) 의 분자. 고객사별 LRR 화면의 Q'ty 열과 「고객사별 LRR 수량」 피벗 |
| `ship_qty` | `numeric(18,6)` | Y | N | 해당 출하 연월의 출하 수량(EA). LRR(%) 의 분모. 고객사별 LRR 화면 「고객사 누계」 표의 Ship Q'ty 열 |
| `state_cd` | `character varying(30)` | N | N | 처리 상태 — RECEIVED(접수) / ANALYZING(분석중) / CLOSED(종결) |
| `remark` | `character varying(500)` | Y | N | 비고 — 분석 경과·조치 내용을 남김 |
| `ins_date` | `timestamp with time zone` | N | N | 등록일시 |
| `ins_user` | `common.d_user_id` | Y | N | 등록자 (사번) |
| `upd_date` | `timestamp with time zone` | N | N | 최종 수정일시 |
| `upd_user` | `common.d_user_id` | Y | N | 최종 수정자 (사번) |

**테이블명:** `ax.tb_rpt_download_blind`

**테이블 설명:** 다운로드 파일에서 blind 처리된 데이터 항목 내역 — 감사 시 "무엇이 제외되었는지" 를 증빙

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `dl_id` | `bigint` | N | Y | 대상 다운로드 이력 (ax.tb_rpt_download_log) |
| `field_key` | `character varying(30)` | N | Y | 제외된 데이터 항목 (ax.tb_sys_data_field) |
| `cell_cnt` | `integer` | N | N | 그 항목 때문에 값이 빠진 셀 수 |

**테이블명:** `ax.tb_rpt_download_log`

**테이블 설명:** 보고서 다운로드 이력 — 엑셀·CSV·인쇄(PDF) 모두 기록. 문서를 저장하지 않으므로 이 이력이 "누가 · 언제 · 어떤 조건으로 · 무엇을" 내려받았는지의 유일한 기록. 보존 3년 · 보안 감사 로그와 함께 제출 대상 (append-only)

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `dl_id` | `bigint` | N | Y | 다운로드 이력 대리키 |
| `downloaded_at` | `timestamp with time zone` | N | N | 내려받은 시각. 화면 기본 정렬 키. 보고서 다운로드 이력의 「일시」 열 |
| `user_id` | `common.d_user_id` | N | N | 내려받은 사람 (사번). 삭제된 계정의 기록도 보존하므로 FK 를 걸지 않음. 보고서 다운로드 이력 화면의 「계정」 열 |
| `dept_nm` | `character varying(50)` | Y | N | 내려받을 당시 소속 부서명 스냅샷. 보고서 다운로드 이력 화면의 「부서」 열 |
| `report_id` | `character varying(30)` | Y | N | 대상 보고서 (ax.tb_rpt_report). 보고서가 아닌 화면 내려받기면 비어 있음. 보고서 다운로드 이력 화면의 「화면」 열에 화면명과 경로로 표시 |
| `menu_id` | `character varying(30)` | Y | N | 내려받은 화면 ID (ax.tb_sys_menu). 화면이 삭제돼도 기록을 보존하므로 FK 를 걸지 않음 |
| `target_nm` | `character varying(200)` | N | N | 보고서 · 화면 표시명 스냅샷 (보고서가 삭제·개편되어도 이력이 남도록) |
| `format_cd` | `character varying(30)` | N | N | 형식. 공통코드 그룹 = RPT_FORMAT (XLS=엑셀, CSV=CSV, PDF=인쇄·PDF) |
| `scope_desc` | `character varying(100)` | Y | N | 조회 범위 표시문 (예: 2026-09-01 ~ 09-03 · PRESS). 사람이 읽는 요약이고, 정확한 조건은 params_json |
| `row_cnt` | `integer` | N | N | 파일에 담긴 데이터 행 수. 보고서 다운로드 이력 화면의 「행 수」 열 |
| `blind_cnt` | `integer` | N | N | 권한이 없어 파일에서 제외된 항목 수. 상세 항목은 tb_rpt_download_blind |
| `ip_addr` | `inet` | Y | N | 내려받은 접속 IP. 화면에는 host() 로 주소만 표시. 보고서 다운로드 이력 화면의 「IP」 열 |
| `result_cd` | `character varying(30)` | N | N | 처리 결과. 현재 구현은 성공 시 DONE 만 기록 |
| `file_nm` | `character varying(200)` | Y | N | 생성한 파일명. 인쇄(PDF)처럼 파일이 없으면 비어 있음 |
| `params_json` | `jsonb` | Y | N | 생성 조건 스냅샷 (대상일·기간·공정·LOT·양식·고객사 공개 정책 등 요청 파라미터 그대로). 문서를 저장하지 않으므로 같은 산출물을 다시 만들 수 있는 유일한 단서 |
| `file_size` | `bigint` | Y | N | 내려받은 파일 크기(byte). 인쇄(PDF) 처럼 파일이 없으면 NULL |

**테이블명:** `ax.tb_rpt_form`

**테이블 설명:** 보고서 양식 — 8D 리포트, 불량 폐기 보고서 등. 양식 구조가 바뀌면 파서 버전을 등록

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `form_id` | `integer` | N | Y | 양식 대리키 |
| `form_nm` | `character varying(100)` | N | N | 양식명. 양식 관리 화면이 내려간 뒤로는 정의만 남아 있음 |
| `form_type_cd` | `character varying(30)` | N | N | 양식 유형. 공통코드 그룹 = RPT_FORM_TYPE (QUALITY=품질 이슈, SCRAP=폐기, CLAIM=고객 클레임, ANALYSIS=품질 분석, PERIODIC=정기 보고) |
| `customer_id` | `integer` | Y | N | 고객사 전용 양식일 때의 고객사 (ax.tb_prod_customer) |
| `disclosure_policy` | `character varying(200)` | Y | N | 고객사 공개 정책 요약 (예: 글로벌 고객사 A · 단가/수율 비공개, 내부용) |
| `parser_ver` | `character varying(20)` | N | N | 파서 버전 — 양식 구조 변경 이력 관리 |
| `report_id` | `character varying(30)` | Y | N | 이 양식이 붙는 보고서 (ax.tb_rpt_report) |
| `use_flg` | `common.d_yn` | N | N | 사용 유무 (Y/N) |
| `ins_date` | `timestamp with time zone` | N | N | 등록일시 |
| `ins_user` | `common.d_user_id` | Y | N | 등록자 (사번) |
| `upd_date` | `timestamp with time zone` | N | N | 최종 수정일시 |
| `upd_user` | `common.d_user_id` | Y | N | 최종 수정자 (사번) |

**테이블명:** `ax.tb_rpt_form_field`

**테이블 설명:** 보고서 양식 항목 — 화면의 "항목 수" 는 이 행 수로 산출

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `form_id` | `integer` | N | Y | 소속 양식 (ax.tb_rpt_form) |
| `field_seq` | `smallint` | N | Y | 양식 안의 항목 순번. form_id 와 함께 키를 구성 |
| `field_nm` | `character varying(100)` | N | N | 항목 표시명 (예: 발생 일자, 불량 내용) |
| `field_code` | `character varying(50)` | Y | N | 항목 식별 코드. 양식 파서가 값을 꽂을 자리를 찾는 키 |
| `is_required` | `boolean` | N | N | true 면 필수 입력 항목. 양식 관리 화면이 내려가 현재 판정에 쓰이지 않음 |
| `blind_field_key` | `character varying(30)` | Y | N | 이 항목이 데이터 접근 권한 대상인 경우의 데이터 항목. 권한 없는 계정에게는 값 대신 "비공개" 로 출력됨 |
| `remark` | `character varying(200)` | Y | N | 비고 — 작성 요령 등 항목 설명 |

**테이블명:** `ax.tb_rpt_report`

**테이블 설명:** 보고서 마스터 — 보고서 모듈(reports/*.js)로 등록되는 화면. 다운로드 이력의 대상이 됨

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `report_id` | `character varying(30)` | N | Y | 보고서 코드 (예: RPT_DAILY_PROD). 다운로드 이력이 이 값을 남김 |
| `report_nm` | `character varying(100)` | N | N | 보고서 표시명 |
| `report_group` | `character varying(50)` | Y | N | 보고서 분류 (생산관리 · 품질관리 등). 보고서 센터의 묶음 단위 |
| `menu_id` | `character varying(30)` | Y | N | 연결된 화면 ID. 보고서도 권한 관리 대상이므로 tb_sys_menu 에 등록됨 |
| `sort_seq` | `smallint` | N | N | 보고서 목록 표시 순서 |
| `use_flg` | `common.d_yn` | N | N | 사용 유무 (Y/N). N 이면 보고서 선택 목록에서 빠짐 |

**테이블명:** `ax.tb_rpt_usage`

**테이블 설명:** 계정별 보고서 사용 횟수 (자주 쓰는 보고서 버튼, 상위 5개). 순위는 use_cnt DESC, last_used_at DESC

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `user_id` | `common.d_user_id` | N | Y | 사용 횟수를 센 계정 사번 (ax.tb_sys_user). 계정 삭제 시 함께 삭제 |
| `menu_id` | `character varying(30)` | N | Y | 보고서 화면 ID = ax.tb_sys_menu.menu_id. 웹 API 에서는 screenId. 조회 시 use_flg='Y' 메뉴만 반환 |
| `use_cnt` | `integer` | N | N | 보고서를 만든 횟수. 선택 1회 = +1. 행이 있으면 1 이상이어야 자연스럽다 (INSERT 시 1 로 넣을 것) |
| `last_used_at` | `timestamp with time zone` | N | N | 마지막으로 만든 시각. 횟수가 같을 때의 2차 정렬 키 |
| `ins_date` | `timestamp with time zone` | N | N | 처음 만든 시각 |

**테이블명:** `ax.tb_sync_job`

**테이블 설명:** 이관 작업 이력 — 작업별 시작·종료 시각, 대상/성공/실패 건수, 정합성 검증 결과. 운영 로그 3년 보존 필수

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `job_id` | `character varying(20)` | N | Y | 작업 ID (MIG-YYMMDD-NN) |
| `map_id` | `integer` | N | N | 이관 매핑 ID. ax.tb_sync_map 의 어느 정의로 돌렸는지 |
| `sync_kind_cd` | `character varying(30)` | N | N | 이관 구분. 공통코드 그룹 = SYNC_KIND (FULL=전량 교체, INCR=증분 UPSERT). key_columns 가 비면 창 재적재로 돌아도 INCR 로 기록 |
| `started_at` | `timestamp with time zone` | Y | N | 실제 실행 시작 시각. PENDING(예약 대기) 상태에서는 NULL 이고, 엔진이 작업을 선점하는 순간 기록됨 |
| `ended_at` | `timestamp with time zone` | Y | N | 작업 종료 일시. 시작 시 NULL 이고 마감 UPDATE 에서 채움 |
| `duration_sec` | `integer` | Y | N | 이관 소요 시간(초). started_at~ended_at 차이를 초로 버림 |
| `target_rows` | `bigint` | N | N | 원본 조회 대상 행 수(건). 시작 시 0, COUNT 직후 확정하고 마감 때 ok+ng 보다 작으면 그만큼 등록 |
| `ok_rows` | `bigint` | N | N | 스테이징 COPY 에 성공한 원본 행 수(건). 대상 실제 반영분은 remark 의 "적용 N행" |
| `ng_rows` | `bigint` | N | N | 스테이징 COPY 에 실패한 원본 행 수(건). 1건이라도 있으면 대상 미반영 FAIL |
| `state_cd` | `character varying(30)` | N | N | 상태. 공통코드 그룹 = SYNC_STATE (DONE=완료, RUNNING=진행 중, FAIL=실패, RETRY_DONE=재시도 완료, ABORTED=중단) |
| `checksum_match` | `boolean` | Y | N | 원본·대상 건수 및 체크섬 대조 결과. false 면 실패 처리 |
| `retry_cnt` | `smallint` | N | N | 자동 재시도 횟수. 3회 초과 시 실패 확정하고 전산팀에 이상 알림을 발송 |
| `triggered_by_cd` | `character varying(30)` | N | N | 실행 주체. 공통코드 그룹 = SYNC_TRIGGER (BATCH=배치, MANUAL=수동 이관, RETRY=재실행) |
| `triggered_by` | `common.d_user_id` | Y | N | 실행자 (사번). --user 값이며 기본 SYSTEM, 화면 예약 작업은 요청자 |
| `remark` | `character varying(500)` | Y | N | 비고. 시작 시 run=실행ID·원본·대상, 종료 시 적용 행수·검증 결과 또는 실패 사유 (500자 절단) |
| `scheduled_at` | `timestamp with time zone` | Y | N | 실행 예약 시각. 화면의 수동 이관·재실행이 설정하며, 이관 엔진이 이 시각이 지난 PENDING 작업을 가져감. 정기 배치가 만든 작업은 NULL |
| `run_id` | `character varying(30)` | Y | N | 소속 실행 (ax.tb_sync_run). 정기 배치 한 번에 여러 테이블 작업이 딸림 |

**테이블명:** `ax.tb_sync_job_error`

**테이블 설명:** 이관 실패 상세 — 실패 건만 재실행할 수 있도록 원본 키와 페이로드를 보관한다 (실패 원본 페이로드 90일 보존)

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `err_id` | `bigint` | N | Y | 실패 상세 일련번호 (PK) |
| `job_id` | `character varying(20)` | N | N | 오류가 난 이관 작업 ID (MIG-YYMMDD-NN) |
| `err_seq` | `integer` | N | N | 작업 내 오류 일련번호 (1부터). 작업 1건당 1,000건까지만 기록 |
| `err_code` | `character varying(50)` | Y | N | 오류 코드. SQLSTATE 값 (22001=길이 초과, 23514=CHECK 위반, 23505=키 중복). 테이블 단위 오류는 TABLE |
| `err_msg` | `character varying(1000)` | N | N | 실패 원인 (예: 대상 컬럼 judge_code 길이 초과 — 원본 4자 / 대상 3자) |
| `src_key` | `character varying(300)` | Y | N | 실패한 원본 행의 키 값 (JSON 문자열 또는 구분자 목록) |
| `payload` | `jsonb` | Y | N | 실패한 원본 행 전체 JSON (jsonb). 값은 문자열로 담으며 실패분 재적재에 사용 |
| `retried_at` | `timestamp with time zone` | Y | N | 조치 후 재적재한 일시. 운영자가 resolved 와 함께 수동으로 기록 |
| `resolved` | `boolean` | N | N | true 면 조치 완료. 엔진은 건드리지 않고 운영자가 수동으로 표시하며, 미해결 목록은 NOT resolved |
| `ins_date` | `timestamp with time zone` | N | N | 등록일시 |

**테이블명:** `ax.tb_sync_map`

**테이블 설명:** 이관 매핑 — MSSQL 원본 테이블과 PostgreSQL 대상 테이블 대응. 화면의 "연동 대상 · 매핑" 카드가 이 표를 읽음

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `map_id` | `integer` | N | Y | 이관 매핑 ID (PK). 작업 이력·드리프트 기록이 이 값을 참조 |
| `src_db` | `character varying(63)` | N | N | 원본 MSSQL 데이터베이스명 (MESDB_M) |
| `src_schema` | `character varying(63)` | N | N | 원본 MSSQL 스키마명 (dbo) |
| `src_table` | `character varying(63)` | N | N | 원본 MSSQL 테이블명. 원본 표기 그대로 대문자 (TB_MD_ITEM) |
| `tgt_schema` | `character varying(63)` | N | N | 대상 PostgreSQL 스키마명 (mes) |
| `tgt_table` | `character varying(63)` | N | N | 대상 PostgreSQL 테이블명. 소문자 (tb_md_item) |
| `sync_kind_cd` | `character varying(30)` | N | N | 이관 구분. 공통코드 그룹 = SYNC_KIND (INCR=증분, FULL=전체) |
| `schedule_desc` | `character varying(50)` | Y | N | 주기 표시 (예: 증분 (일 1회), 증분 (10분), 전체 (주 1회)) |
| `schedule_cron` | `character varying(50)` | Y | N | 테이블별 개별 이관 주기 (6필드 cron). 비면 전역 스케줄 — 엔진은 아직 쓰지 않음 |
| `key_columns` | `character varying(200)` | N | N | UPSERT 키 컬럼 목록 (쉼표 구분). 원본에 PK 가 없는 테이블은 자연키 조합을 명시 |
| `cdc_column` | `character varying(63)` | Y | N | 증분 기준 컬럼 (최종 이관 시각 이후 변경분 판별용, 통상 UPD_DATE 또는 INS_DATE) |
| `cumulative_rows` | `bigint` | N | N | 누적 이관 건수 |
| `last_sync_at` | `timestamp with time zone` | Y | N | 증분 워터마크. 마지막 이관의 배치 시작 시각이며 다음 증분 조회의 하한이 됨 |
| `last_job_id` | `character varying(20)` | Y | N | 마지막으로 성공한 이관 작업 ID (MIG-YYMMDD-NN) |
| `use_flg` | `common.d_yn` | N | N | 이관 대상 여부. Y 면 정기 배치가 이관하고 N 은 제외하되 드리프트 점검에는 남김 |
| `remark` | `character varying(300)` | Y | N | 비고. 이 매핑의 설명과 이관 방식을 그렇게 정한 이유 |
| `ins_date` | `timestamp with time zone` | N | N | 등록일시 |
| `ins_user` | `common.d_user_id` | Y | N | 등록자 (사번) |
| `upd_date` | `timestamp with time zone` | N | N | 최종 수정일시 |
| `upd_user` | `common.d_user_id` | Y | N | 최종 수정자 (사번). 엔진이 워터마크를 밀 때는 SYSTEM 이 들어감 |

**테이블명:** `ax.tb_sync_run`

**테이블 설명:** 이관 실행 이력 — 엔진을 한 번 돌릴 때마다 1행. 프리플라이트 실패·무작업·건너뜀까지 모두 남김. 테이블별 상세는 ax.tb_sync_job (run_id 로 연결)

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `run_id` | `character varying(30)` | N | Y | 실행 식별자 (RUN-yyyyMMdd-HHmm[-QUEUE / -RETRY]). tb_sync_job.remark 의 run= 값과 같음 |
| `mode_cd` | `character varying(30)` | N | N | 실행 모드. 공통코드 그룹 = SYNC_RUN_MODE (SCHEDULED=정기 배치, MANUAL=즉시 1회, QUEUE=예약 큐, RETRY=작업 재실행) |
| `state_cd` | `character varying(30)` | N | N | 실행 결과. 공통코드 그룹 = SYNC_RUN_STATE (RUNNING=진행 중, DONE=전건 성공, PARTIAL=일부 실패, FAIL=전건 실패, PREFLIGHT_FAIL=접속·점검 실패로 시작 못함, NO_WORK=대상 없음, SKIPPED=다른 인스턴스 점유로 건너뜀, ABORTED=비정상 종료) |
| `started_at` | `timestamp with time zone` | N | N | 실행 시작 일시. 프리플라이트보다 먼저 기록해 접속 실패(PREFLIGHT_FAIL)도 남김 |
| `ended_at` | `timestamp with time zone` | Y | N | 실행 종료 일시. RUNNING 중에는 NULL 이고 강제 종료분은 다음 기동이 ABORTED 로 채움 |
| `duration_sec` | `integer` | Y | N | 실행 소요 시간(초). started_at~ended_at 차이이며 건너뛴 실행(SKIPPED)은 0 |
| `triggered_by_cd` | `character varying(30)` | N | N | 실행 주체. 공통코드 그룹 = SYNC_TRIGGER (BATCH=정기 배치, MANUAL=즉시·예약 큐 실행, RETRY=작업 재실행). 실행 모드 mode_cd 와는 별개 |
| `triggered_by` | `common.d_user_id` | Y | N | 실행자 (사번). --user 값이며 기본 SYSTEM |
| `options_desc` | `character varying(300)` | Y | N | 실행 옵션 요약 (--since/--until/--tables 등). 어떤 조건으로 돌렸는지 재현용 |
| `target_cnt` | `smallint` | N | N | 이번 실행의 대상 테이블 수 |
| `success_cnt` | `smallint` | N | N | 성공한 테이블 작업 수(건). state_cd 가 DONE 또는 RETRY_DONE 인 작업만 집계 |
| `fail_cnt` | `smallint` | N | N | 실패한 테이블 작업 수(건). 0 이면 state_cd=DONE, 전건이면 FAIL, 일부면 PARTIAL |
| `ok_rows` | `bigint` | N | N | 이번 실행의 적재 행 수 합계(건). 테이블별 ok_rows 의 합이며 모의 실행은 0 |
| `ng_rows` | `bigint` | N | N | 이번 실행의 COPY 실패 행 수 합계(건). 테이블별 ng_rows 의 합 |
| `drift_open_cnt` | `smallint` | Y | N | 실행 시점의 미해소 스키마 드리프트 건수 |
| `source_url` | `character varying(300)` | Y | N | 접속한 원본 (비밀번호 마스킹). 어느 서버를 봤는지 사후 확인용 |
| `target_url` | `character varying(300)` | Y | N | 이번 실행이 붙은 대상 PostgreSQL 접속 문자열. 비밀번호는 **** 로 가림 |
| `engine_version` | `character varying(20)` | Y | N | 실행한 엔진 JAR 버전. 버전 정보가 없으면 dev |
| `host_name` | `character varying(100)` | Y | N | 엔진이 돌아간 호스트. 여러 대에서 돌릴 때 구분 |
| `message` | `character varying(2000)` | Y | N | 실패 사유. 예외 연쇄의 **근본 원인까지** 남긴다 (예: Failed to obtain JDBC Connection → No route to host) |
| `is_dry_run` | `boolean` | N | N | 모의 실행 여부. true 면 대상만 확인하고 아무것도 반영하지 않았다 (--dry-run) |

**테이블명:** `ax.tb_sync_schema_drift`

**테이블 설명:** 이관 스키마 드리프트 — ax.tb_sync_map 의 이관 정의와 원본·대상 DB 의 실제 테이블 목록이 어긋난 사실. 이관 엔진이 배치마다 기록하고 데이터 연동 이력 화면이 조회

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `drift_id` | `bigint` | N | Y | 스키마 드리프트 일련번호 (PK). 데이터 연동 이력 화면 수동 해소 API 의 driftId |
| `side_cd` | `character varying(30)` | N | N | 발견 위치. 공통코드 그룹 = SYNC_DRIFT_SIDE (SOURCE=원본 MSSQL, TARGET=대상 PostgreSQL) |
| `drift_cd` | `character varying(30)` | N | N | 드리프트 구분. 공통코드 그룹 = SYNC_DRIFT_KIND (NEW=이관 정의에 없는 신규 테이블, MISSING=정의에는 있으나 실물이 없는 테이블) |
| `db_name` | `character varying(63)` | N | N | 데이터베이스 명 (원본 MESDB_M, 대상은 PostgreSQL 데이터베이스명) |
| `schema_nm` | `character varying(63)` | N | N | 스키마 명 (원본 dbo, 대상 mes) |
| `table_nm` | `character varying(63)` | N | N | 테이블 명. 원본은 대문자, 대상은 소문자 원형 그대로 기록 |
| `map_id` | `integer` | Y | N | MISSING 인 경우 해당 이관 매핑. NEW 는 매핑이 없으므로 NULL |
| `detail` | `character varying(500)` | Y | N | 조치 안내 문구 (엔진이 생성) |
| `first_seen_at` | `timestamp with time zone` | N | N | 최초 발견 시각 |
| `first_run_id` | `character varying(30)` | Y | N | 최초 발견 배치 실행 식별자 (RUN-yyyyMMdd-HHmm) |
| `last_seen_at` | `timestamp with time zone` | N | N | 최종 발견 시각 |
| `last_run_id` | `character varying(30)` | Y | N | 최종 발견 배치 실행 식별자 |
| `detect_cnt` | `integer` | N | N | 누적 발견 횟수. 값이 크면 오래 방치된 드리프트 |
| `resolved` | `boolean` | N | N | 해소 여부. 다음 배치에서 드리프트가 사라지면 엔진이 자동으로 true 로 닫고, 화면에서 수동으로 닫을 수도 있음 |
| `resolved_at` | `timestamp with time zone` | Y | N | 해소 일시. 다음 배치에서 사라지면 자동으로 찍히고 재발견되면 NULL 로 되돌림 |
| `resolved_by` | `common.d_user_id` | Y | N | 수동 해소 처리자. 엔진이 자동으로 닫은 경우 NULL |
| `resolve_note` | `character varying(300)` | Y | N | 수동 해소 사유 (예: 이관 대상 아님으로 확인) |

**테이블명:** `ax.tb_sys_code`

**테이블 설명:** 공통코드 — 코드 그룹별 상세 코드. 각 업무 테이블의 *_cd 컬럼이 참조하는 값 집합

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `group_cd` | `character varying(30)` | N | Y | 소속 코드 그룹 (ax.tb_sys_code_group). 예 LOG_AUDIT_TYPE 이면 보안 감사 로그 화면의 「유형」 목록이 됨 |
| `code` | `character varying(30)` | N | Y | 코드 값. 업무 표의 *_cd 컬럼에 실제로 들어가는 문자열. 보안 감사 로그 화면의 「유형」 등 선택 상자가 저장·조회에 쓰는 값 |
| `code_nm` | `character varying(100)` | N | N | 코드 표시명. 화면·보고서에 이 이름이 표시. 보안 감사 로그 화면의 「유형」 등 선택 상자와 표 배지에 보이는 글자 |
| `code_desc` | `character varying(500)` | Y | N | 코드 설명. 화면 도움말이나 선택 시 안내 문구로 사용 |
| `attr1` | `character varying(100)` | Y | N | 부가 속성 1 (예: 심각도 색상, 채널 발송 어댑터명) |
| `attr2` | `character varying(100)` | Y | N | 부가 속성 2 (그룹마다 뜻이 다르다) |
| `sort_seq` | `smallint` | N | N | 그룹 안에서의 선택 목록 표시 순서 |
| `use_flg` | `common.d_yn` | N | N | 사용 유무 (Y/N). N 이면 선택 목록에서 빠지나 기존 데이터의 표시명은 계속 찾을 수 있음 |
| `ins_date` | `timestamp with time zone` | N | N | 등록일시 |
| `ins_user` | `common.d_user_id` | Y | N | 등록자 (사번) |
| `upd_date` | `timestamp with time zone` | N | N | 최종 수정일시 |
| `upd_user` | `common.d_user_id` | Y | N | 최종 수정자 (사번) |

**테이블명:** `ax.tb_sys_code_group`

**테이블 설명:** 공통코드 그룹 — 화면 선택 목록(심각도·채널·직급·단위 등)의 코드 집합 정의

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `group_cd` | `character varying(30)` | N | Y | 코드 그룹 코드 |
| `group_nm` | `character varying(100)` | N | N | 그룹 표시명 |
| `group_desc` | `character varying(500)` | Y | N | 그룹 설명 — 어느 컬럼이 쓰는 코드 집합인지 기재 |
| `use_flg` | `common.d_yn` | N | N | 사용 유무 (Y/N) |
| `sort_seq` | `smallint` | N | N | 코드 관리 화면의 그룹 표시 순서 |
| `ins_date` | `timestamp with time zone` | N | N | 등록일시 |
| `ins_user` | `common.d_user_id` | Y | N | 등록자 (사번) |
| `upd_date` | `timestamp with time zone` | N | N | 최종 수정일시 |
| `upd_user` | `common.d_user_id` | Y | N | 최종 수정자 (사번) |

**테이블명:** `ax.tb_sys_code_ref`

**테이블 설명:** 코드 컬럼 ↔ 코드 그룹 매핑 메타데이터. ax.fn_check_code_ref() 가 이 정의로 코드 정합성을 검사

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `target_table` | `character varying(63)` | N | Y | 코드를 쓰는 테이블명 (스키마 없이 표 이름만) |
| `target_column` | `character varying(63)` | N | Y | 코드를 담는 컬럼명 |
| `group_cd` | `character varying(30)` | N | N | 그 컬럼이 따라야 하는 코드 그룹 (ax.tb_sys_code_group) |
| `nullable_flg` | `common.d_yn` | N | N | NULL 허용 여부 (Y/N). N 이면 ax.fn_check_code_ref() 가 NULL 도 위반으로 검출 |

**테이블명:** `ax.tb_sys_data_field`

**테이블 설명:** 데이터 접근 항목 — 메뉴 접근이 허용된 화면에서도 이 항목 단위로 값을 가림. 2026-09-16 부터 WEB 화면에서 운영 중에 추가 가능

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `field_key` | `character varying(30)` | N | Y | 데이터 접근 항목 키. API 응답 필드명과의 연결은 ax.tb_sys_data_field_attr. 데이터 접근 권한 화면 항목 등록 폼의 「항목 key」 |
| `field_nm` | `character varying(50)` | N | N | 항목 표시명. 데이터 접근 권한 화면에 이 이름이 표시. 같은 화면 권한 표의 「데이터 항목」 열 |
| `field_desc` | `character varying(300)` | Y | N | 항목 설명 — 어떤 값이 가려지는지 기재. 데이터 접근 권한 화면 「포함 데이터」 열 |
| `sort_seq` | `smallint` | N | N | 권한 화면의 항목 표시 순서 |
| `use_flg` | `common.d_yn` | N | N | 항목 사용 여부. 'N' 이면 화면 목록에서 빠짐. 마스킹이 실제로 걸리는 조건은 use_flg='Y' AND apply_flg='Y' |
| `category_cd` | `character varying(30)` | Y | N | 항목 분류. 공통코드 그룹 = DATA_FIELD_CATEGORY. 화면에서 묶어 보여 주기 위한 것이라 NULL 이어도 판정에 영향이 없음 |
| `apply_flg` | `common.d_yn` | N | N | 적용 스위치. 'Y' 여야 마스킹이 적용됨. 등록은 'N'(미적용)으로 해 두고 부서 권한을 채운 뒤 'Y' 로 전환. 반영 시점은 재로그인 |
| `ins_date` | `timestamp with time zone` | N | N | 등록일시 |
| `ins_user` | `common.d_user_id` | Y | N | 등록자 (사번) |
| `upd_date` | `timestamp with time zone` | N | N | 최종 수정일시 |
| `upd_user` | `common.d_user_id` | Y | N | 최종 수정자 (사번) |

**테이블명:** `ax.tb_sys_data_field_attr`

**테이블 설명:** 데이터 접근 항목 ↔ API 응답 필드명. WEB 이 "필드명 → 항목" 맵을 만들어 마스킹 대상을 판별

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `field_key` | `character varying(30)` | N | Y | 이 응답 필드명이 속한 데이터 접근 항목 (ax.tb_sys_data_field) |
| `attr_name` | `character varying(60)` | N | Y | API 응답 JSON 필드명. 전역 UNIQUE — 한 필드명은 한 항목에만 붙음. JSON 키라 대소문자를 구분 |
| `remark` | `character varying(200)` | Y | N | 어느 화면·API 의 값인지 메모. 판정에는 쓰지 않음 |
| `ins_date` | `timestamp with time zone` | N | N | 등록일시 |
| `ins_user` | `common.d_user_id` | Y | N | 등록자 (사번). 최초 적재분은 사람이 아니라 적재 스크립트 이름으로 남아 있음 |

**테이블명:** `ax.tb_sys_dept`

**테이블 설명:** 부서 — 메뉴/데이터 접근 권한을 부여하는 단위. 계정은 소속 부서의 권한을 상속

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `dept_id` | `integer` | N | Y | 부서 대리키. 부서명은 변경 가능하므로 권한 테이블은 이 키로 참조 |
| `dept_nm` | `character varying(50)` | N | N | 부서명. 변경될 수 있어 권한 표는 dept_id 로 참조. 계정 관리 화면 부서 표의 「부서」 열과 부서 등록 폼의 「부서명」 |
| `dept_abbr` | `character varying(4)` | N | N | 부서 약칭 — 화면 배지에 쓰는 2자 표기 (QA, PC, MF, IT, EX, MA) |
| `dept_desc` | `character varying(200)` | Y | N | 부서 설명 — 담당 업무 범위를 기재. 계정 관리 화면 부서 표의 「설명」 열과 부서 등록 폼의 「설명」 |
| `plant_cd` | `common.d_plant_cd` | Y | N | 소속 사업부. 부서 단위 사업부 필터가 필요할 때 사용 |
| `is_super_admin` | `boolean` | N | N | true = 통합관리자. 전 화면·전 데이터 항목 접근으로 취급하며 개별 권한 행을 만들지 않음 |
| `sort_seq` | `smallint` | N | N | 부서 목록 표시 순서 |
| `use_flg` | `common.d_yn` | N | N | 사용 유무 (Y/N). N 이면 부서 선택 목록에서 빠짐 |
| `ins_date` | `timestamp with time zone` | N | N | 등록일시 |
| `ins_user` | `common.d_user_id` | Y | N | 등록자 (사번) |
| `upd_date` | `timestamp with time zone` | N | N | 최종 수정일시 |
| `upd_user` | `common.d_user_id` | Y | N | 최종 수정자 (사번) |

**테이블명:** `ax.tb_sys_dept_data_perm`

**테이블 설명:** 부서 × 데이터 항목 열람 권한 — 허용되지 않은 항목은 화면·보고서·인쇄물·CSV 모두에서 값 자체를 제외

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `dept_id` | `integer` | N | Y | 권한을 받는 부서 (ax.tb_sys_dept). 데이터 접근 권한 화면 권한 표의 부서 열 머리 |
| `field_key` | `character varying(30)` | N | Y | 대상 데이터 접근 항목 (ax.tb_sys_data_field). 데이터 접근 권한 화면 권한 표의 행 |
| `is_allowed` | `boolean` | N | N | true 면 값을 그대로 보여 주고, false 면 마스킹. 데이터 접근 권한 화면 표의 체크 상태 |
| `ins_date` | `timestamp with time zone` | N | N | 등록일시 |
| `ins_user` | `common.d_user_id` | Y | N | 등록자 (사번) |
| `upd_date` | `timestamp with time zone` | N | N | 최종 수정일시 |
| `upd_user` | `common.d_user_id` | Y | N | 최종 수정자 (사번) |

**테이블명:** `ax.tb_sys_dept_menu_perm`

**테이블 설명:** 부서 × 화면 접근 권한 — 계정 권한의 기본값. 통합관리자 부서(is_super_admin)는 행 없이 전체 허용으로 판정. 여기에 행이 없어도 ax.tb_sys_user_menu_grant 로 계정에 개별 허용될 수 있음(차단은 두 표 모두 행이 없을 때)

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `dept_id` | `integer` | N | Y | 권한을 받는 부서 (ax.tb_sys_dept). 메뉴 접근 권한 화면 메뉴 권한 표의 부서 열 머리 |
| `menu_id` | `character varying(30)` | N | Y | 대상 화면 (ax.tb_sys_menu). 메뉴 접근 권한 화면 메뉴 권한 표의 행 |
| `can_read` | `boolean` | N | N | 접근 권한. 행이 있으면서 true 여야 화면에 들어갈 수 있고, 들어갈 수 있으면 그 화면의 모든 동작을 허용(V70 에서 쓰기 칸 통합). 메뉴 접근 권한 화면 표의 「접근」 칸 |

> 2026-10-03 V70 로 `can_write` 컬럼을 삭제했습니다. 접근 권한이 있으면 쓰기 동작도 허용하며, 미배정 부서 계정의 쓰기 동작 거부(E-AUTH-004)는 API 가 판정합니다. 판정 뷰 `ax.vw_sys_user_menu_perm` 의 열은 `user_id, menu_id, from_dept, from_grant` 입니다.
| `ins_date` | `timestamp with time zone` | N | N | 등록일시 |
| `ins_user` | `common.d_user_id` | Y | N | 등록자 (사번) |
| `upd_date` | `timestamp with time zone` | N | N | 최종 수정일시 |
| `upd_user` | `common.d_user_id` | Y | N | 최종 수정자 (사번) |

**테이블명:** `ax.tb_sys_email_verify`

**테이블 설명:** 이메일 인증 요청 — 회원가입·비밀번호 찾기 본인 확인

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `verify_id` | `bigint` | N | Y | 인증 요청 대리키 |
| `email` | `character varying(200)` | N | N | 인증 코드를 보낼 주소. 가입 전 계정도 있어 tb_sys_user 를 참조하지 않음. 회원가입·비밀번호 찾기 화면에 마스킹해 표시 |
| `purpose_cd` | `character varying(30)` | N | N | 인증 목적 (EMAIL_VERIFY_PURPOSE) — SIGNUP / PASSWORD_RESET |
| `code_hash` | `character varying(200)` | N | N | 인증 코드 해시. 평문은 저장하지 않음 |
| `verify_token` | `character varying(64)` | Y | N | 검증 성공 시 발급하는 1회용 토큰. 사용하면 consumed_at 이 채워짐 |
| `target_user_id` | `common.d_user_id` | Y | N | 비밀번호 찾기 대상 계정. 토큰을 다른 계정에 재사용하지 못하게 묶음 |
| `expires_at` | `timestamp with time zone` | N | N | 인증 코드 만료 시각. 지나면 검증을 받지 않음 |
| `verified_at` | `timestamp with time zone` | Y | N | 코드 검증에 성공한 시각. 비어 있으면 아직 미검증 |
| `consumed_at` | `timestamp with time zone` | Y | N | 발급한 1회용 토큰을 실제로 쓴 시각. 채워지면 재사용할 수 없음 |
| `attempt_cnt` | `smallint` | N | N | 코드 검증 시도 횟수. 상한 초과 시 해당 요청을 폐기 |
| `send_result_cd` | `character varying(30)` | N | N | 메일 발송 결과. 공통코드 그룹 = EMAIL_SEND_RESULT (SENT=발송, FAIL=실패, SUPPRESSED=발송 억제) |
| `fail_reason` | `character varying(300)` | Y | N | 발송·폐기 사유 (예: 새 인증 코드 발송으로 폐기) |
| `ip_addr` | `inet` | Y | N | 인증을 요청한 접속 IP. 무차별 시도 추적에 사용 |
| `ins_date` | `timestamp with time zone` | N | N | 요청 등록일시 |

**테이블명:** `ax.tb_sys_login_hist`

**테이블 설명:** 사용자 접속 이력 — 보존 3년 (append-only)

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `login_id` | `bigint` | N | Y | 접속 이력 대리키 |
| `user_id` | `common.d_user_id` | N | N | 시도한 아이디. 삭제된 계정의 기록도 보존하므로 FK 를 걸지 않음 |
| `login_at` | `timestamp with time zone` | N | N | 로그인 시도 시각. 계정 관리 화면 계정 표의 「최근 접속」 열에 가장 최근 값이 표시 |
| `logout_at` | `timestamp with time zone` | Y | N | 로그아웃 시각. 세션 만료로 끝나면 비어 있음 |
| `result_cd` | `character varying(30)` | N | N | 결과. 공통코드 그룹 = SYS_LOGIN_RESULT (SUCCESS / FAIL / LOCKED) |
| `fail_reason` | `character varying(200)` | Y | N | 실패 사유 (비밀번호 불일치·잠금 등). 성공이면 비어 있음 |
| `ip_addr` | `inet` | Y | N | 접속 IP. 화면에는 host() 로 주소만 표시 |
| `user_agent` | `character varying(300)` | Y | N | 접속 브라우저 정보. 이상 접속 확인에 사용 |

**테이블명:** `ax.tb_sys_menu`

**테이블 설명:** 화면(메뉴) 정의 — 보고서 화면 7종 포함. 메뉴 접근 권한 판정의 기준 테이블

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `menu_id` | `character varying(30)` | N | Y | 화면 ID — 웹 주소의 해시 경로 값과 같다 (dash-ai, sys-account, alert-cond …) |
| `menu_nm` | `character varying(50)` | N | N | 화면 표시명. 사이드바 이름이 이 값이라 바꾸면 화면에 바로 반영됨. 메뉴 접근 권한 화면의 「화면」 열과 좌측 사이드바의 메뉴 항목명 |
| `group_id` | `character varying(30)` | N | N | 소속 사이드바 그룹 (ax.tb_sys_menu_group) |
| `parent_menu_id` | `character varying(30)` | Y | N | 하위 화면의 진입 상위 화면. 상위만 열고 하위를 닫으면 버튼 진입이 차단되므로 함께 부여하도록 안내 |
| `is_sub_page` | `boolean` | N | N | true = 사이드바에 노출되지 않고 버튼·링크로만 진입하는 하위 화면(daily-history 등) 또는 경로 없는 동작 권한(dash-ai-upload). 권한 매트릭스에는 포함됨 |
| `route_path` | `character varying(100)` | Y | N | 화면 경로 (해시 라우트). 경로 없는 동작 권한이면 비어 있음 |
| `tag_cd` | `character varying(30)` | Y | N | 개발 범위 표기. 공통코드 그룹 = SYS_MENU_TAG (NEW=신규, MOD=수정, REQ=필수) |
| `sort_seq` | `smallint` | N | N | 그룹 안에서의 사이드바 표시 순서 |
| `use_flg` | `common.d_yn` | N | N | 사용 유무 (Y/N). N 이면 사이드바에서 내려가나 API 와 권한 행은 남음 |
| `ins_date` | `timestamp with time zone` | N | N | 등록일시 |
| `ins_user` | `common.d_user_id` | Y | N | 등록자 (사번) |
| `upd_date` | `timestamp with time zone` | N | N | 최종 수정일시 |
| `upd_user` | `common.d_user_id` | Y | N | 최종 수정자 (사번) |

**테이블명:** `ax.tb_sys_menu_group`

**테이블 설명:** 메뉴 그룹 — 사이드바 그룹 (AI 어시스턴트 · 대시보드 · 생산관리 · 품질관리 · 보고서 · 이상 알림 · 시스템관리)

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `group_id` | `character varying(30)` | N | Y | 메뉴 그룹 ID (dashboard, system ...) |
| `group_nm` | `character varying(50)` | N | N | 그룹 표시명. 사이드바 머리글이 이 값. 좌측 사이드바 대분류와 메뉴 접근 권한 화면 표의 그룹 펼침 줄 |
| `is_solo` | `boolean` | N | N | true = 그룹 없이 사이드바 최상단 단독 고정 (AI 어시스턴트) |
| `sort_seq` | `smallint` | N | N | 사이드바 그룹 표시 순서 |
| `use_flg` | `common.d_yn` | N | N | 사용 유무 (Y/N). N 이면 그룹째 사이드바에서 빠짐 |

**테이블명:** `ax.tb_sys_perm_log`

**테이블 설명:** 계정 · 부서 · 권한 변경 이력 (append-only). 화면 하단 "계정·권한 변경 이력" 카드가 이 표를 읽음

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `log_id` | `bigint` | N | Y | 변경 이력 대리키 |
| `log_at` | `timestamp with time zone` | N | N | 변경 시각. 화면 기본 정렬 키. 계정 관리 화면 「변경 이력」 표의 「시각」 열 |
| `act_cd` | `character varying(30)` | N | N | 변경 구분. 공통코드 그룹 = SYS_PERM_ACT (ACCOUNT=계정, DEPT=부서, MENU_PERM=메뉴 권한, DATA_PERM=데이터 권한) |
| `target_kind_cd` | `character varying(30)` | N | N | 대상 종류 — USER(계정) / DEPT(부서) / MENU(화면) / FIELD(데이터 항목). 계정 관리 화면의 변경 이력 「구분」 열의 계정·부서 배지 |
| `target_dept_id` | `integer` | Y | N | 대상이 부서일 때의 부서 (ax.tb_sys_dept). 계정 관리 화면 변경 이력의 「대상」 열 |
| `target_user_id` | `common.d_user_id` | Y | N | 대상이 계정일 때의 사번. 계정 관리 화면 변경 이력의 「대상」 열 |
| `target_nm` | `character varying(100)` | N | N | 변경 대상 표시명. 대상이 삭제되어도 기록이 남도록 이름을 함께 저장 |
| `detail` | `character varying(500)` | N | N | 무엇이 어떻게 바뀌었는지 사람이 읽는 설명 (변경 전후 값). 계정 관리 화면 변경 이력의 「변경 내용」 열 |
| `actor_user_id` | `common.d_user_id` | N | N | 변경을 수행한 관리자 사번. 계정 관리 화면 변경 이력의 「수행자」 열 |
| `actor_dept_nm` | `character varying(50)` | Y | N | 수행자 소속 부서명 스냅샷 (수행 당시 값 보존) |

**테이블명:** `ax.tb_sys_plant`

**테이블 설명:** 사업부 마스터 — MES 의 PLANT_CD(PL01=모바일, PL03=전장) 마스터. MESDB_M 에는 이 마스터가 없어 AX 에서 관리

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `plant_cd` | `common.d_plant_cd` | N | Y | 사업부 구분 코드 (PL01=모바일, PL03=전장). mes 전 테이블의 plant_cd 마스터 |
| `plant_nm` | `character varying(100)` | N | N | 사업부명. 불량 현황 조회 상세 표의 「공장」 열 |
| `remark` | `character varying(500)` | Y | N | 비고 |
| `sort_seq` | `smallint` | N | N | 사업부 선택 목록 표시 순서 |
| `use_flg` | `common.d_yn` | N | N | 사용 유무 (Y/N) |
| `ins_date` | `timestamp with time zone` | N | N | 등록일시 |
| `ins_user` | `common.d_user_id` | Y | N | 등록자 (사번) |
| `upd_date` | `timestamp with time zone` | N | N | 최종 수정일시 |
| `upd_user` | `common.d_user_id` | Y | N | 최종 수정자 (사번) |

**테이블명:** `ax.tb_sys_user`

**테이블 설명:** 가입 계정 — 아이디(사번) · 이름 · 부서 · 직급 · 상태만 관리. 화면/데이터 접근 권한은 소속 부서 설정을 따르며, 화면 권한만 계정별 추가 허용(ax.tb_sys_user_menu_grant)을 더할 수 있음

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `user_id` | `common.d_user_id` | N | Y | 로그인 아이디 = 사번. MES tb_md_eqpt_by_user.user_id 및 전 테이블 ins_user/upd_user 와 동일 도메인 |
| `user_nm` | `character varying(50)` | N | N | 사용자 이름. 화면 상단·이력의 표시명이 이 값. 계정 관리 화면 계정 표의 「이름」 열과 좌측 하단 계정 카드 |
| `dept_id` | `integer` | N | N | 소속 부서 (ax.tb_sys_dept). 화면·데이터 접근 권한을 이 부서에서 상속. 계정 관리 화면의 「소속 부서」 열과 계정 등록 폼의 「소속 부서」 |
| `plant_cd` | `common.d_plant_cd` | Y | N | 소속 사업부 구분 코드 (PL01=모바일, PL03=전장) |
| `position_cd` | `character varying(30)` | N | N | 직급. 공통코드 그룹 = SYS_POSITION (사원/반장/선임/팀장/상무/이사/관리자) |
| `user_state_cd` | `character varying(30)` | N | N | 계정 상태. 공통코드 그룹 = SYS_USER_STATE (ACTIVE=사용, SUSPENDED=정지) |
| `is_switch_target` | `boolean` | N | N | 우측 상단 계정 전환 목록에 이 계정을 띄울지 여부. 시연용 플래그 |
| `pwd_hash` | `character varying(200)` | Y | N | 비밀번호 해시 (PBKDF2-SHA512). 평문은 저장하지 않음 |
| `pwd_upd_at` | `timestamp with time zone` | Y | N | 비밀번호를 마지막으로 바꾼 시각. 초기 비밀번호 여부 판단에 사용 |
| `login_fail_cnt` | `smallint` | N | N | 연속 로그인 실패 횟수. 상한(기본 5)에 닿으면 잠기고 성공하면 0 으로 복귀. 계정 관리 화면 계정 표의 「로그인 실패」 열 |
| `last_login_at` | `timestamp with time zone` | Y | N | 최근 접속 시각. 상세 이력은 tb_sys_login_hist |
| `remark` | `character varying(500)` | Y | N | 비고 — 계정 정지 사유 등 관리 메모 |
| `ins_date` | `timestamp with time zone` | N | N | 등록일시 — 가입 신청 시각 |
| `ins_user` | `common.d_user_id` | Y | N | 등록자 (사번). 회원가입으로 만들어졌으면 비어 있음 |
| `upd_date` | `timestamp with time zone` | N | N | 최종 수정일시 |
| `upd_user` | `common.d_user_id` | Y | N | 최종 수정자 (사번) |
| `email` | `character varying(200)` | Y | N | 계정 이메일 — 회원가입 본인 확인 및 비밀번호 찾기에 사용 |

**테이블명:** `ax.tb_sys_user_menu_grant`

**테이블 설명:** 계정 × 화면 추가 허용 — 부서 권한에 더해 이 계정에만 열어 주는 화면. 행의 존재 = 접근 허용이며, 행으로 차단하는 용법은 없음(추가 허용 전용). `can_write` 컬럼은 V70(2026-10-03)에서 삭제

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `user_id` | `common.d_user_id` | N | Y | 사번 = ax.tb_sys_user.user_id. 웹·API 응답에서는 empNo. 계정 삭제 시 함께 삭제(CASCADE) |
| `menu_id` | `character varying(30)` | N | Y | 화면 ID = ax.tb_sys_menu.menu_id. 웹 API 에서는 screenId. 조회 시 use_flg='Y' 메뉴만 반환 |
| `grant_reason` | `character varying(200)` | Y | N | 부여 사유. 부서 기준을 벗어난 예외이므로 남겨 두면 감사에서 되짚기 쉬움. 없으면 NULL |
| `ins_date` | `timestamp with time zone` | N | N | 권한을 부여한 일시 |
| `ins_user` | `common.d_user_id` | Y | N | 이 권한을 부여한 관리자 사번. 변경 이력 상세는 ax.tb_sys_perm_log (act_cd = USER_MENU_PERM) |
| `upd_date` | `timestamp with time zone` | N | N | 최종 수정일시 |
| `upd_user` | `common.d_user_id` | Y | N | 최종 수정자 (사번) |


## 8. vec 스키마

문서, 검색용 문서 조각, 임베딩, 검색 기록을 저장합니다. 현재 DB에서 확인한 테이블은 14개, 컬럼은 204개입니다.

**테이블명:** `vec.tb_code_ref`

**테이블 설명:** vec 스키마 코드 컬럼 ↔ 코드 그룹 매핑. vec.fn_check_code_ref() 가 이 정의로 검사

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `target_table` | `character varying(63)` | N | Y | 검사 대상 표 이름. vec 스키마 안의 이름만 기재 |
| `target_column` | `character varying(63)` | N | Y | 검사 대상 코드 컬럼명 |
| `group_cd` | `character varying(30)` | N | N | 대조할 공통코드 그룹 (ax.tb_sys_code.group_cd) |
| `nullable_flg` | `common.d_yn` | N | N | N = NULL 값도 위반으로 검출. fn_check_code_ref() 의 판정 기준 |

**테이블명:** `vec.tb_doc`

**테이블 설명:** 문서 마스터 — 벡터화 대상 원본 문서 1건. MES 조인 키를 직접 보유해 문서 검색과 실적 조회를 함께 수행할 수 있음

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `doc_id` | `bigint` | N | Y | 문서 식별자 (PK) |
| `doc_uid` | `uuid` | N | N | 외부 노출용 UUID. 응답 인용(citation) 링크에 순번 대신 이 값을 사용 |
| `title` | `character varying(300)` | N | N | 문서 제목. 검색 결과의 표시 이름이며 trigram 색인이 걸려 있음 |
| `doc_type_cd` | `character varying(30)` | N | N | 문서 유형. 공통코드 그룹 = VEC_DOC_TYPE (REPORT_8D, SCRAP, CLAIM, YIELD, DAILY, STANDARD=작업표준, MINUTES=회의록, SPEC=규격서, ETC) |
| `doc_date` | `date` | Y | N | 문서 기준일 (보고서 작성 대상일). 기간 필터의 기준이며 파티셔닝 후보 키 |
| `source_cd` | `character varying(30)` | N | N | 수집 경로. 공통코드 그룹 = VEC_SOURCE (UPLOAD=화면 업로드, FILESRV=파일 서버, MAIL=메일 첨부, SCAN=스캔·이미지, MES=MES 첨부, API) |
| `source_path` | `character varying(1000)` | Y | N | 원본 경로(NAS 등). 재수집과 원문 열람의 기준 |
| `file_nm` | `character varying(300)` | Y | N | 원본 파일명 |
| `mime_type` | `character varying(100)` | Y | N | 원본 MIME 타입 (예: application/pdf) |
| `file_size` | `bigint` | Y | N | 원본 파일 크기(바이트) |
| `content_hash` | `vec.d_content_hash` | Y | N | 원본 파일 SHA-256. 중복 수집 차단 및 재임베딩 필요 판정 |
| `page_cnt` | `integer` | Y | N | 원본 쪽 수 |
| `lang_cd` | `character varying(10)` | N | N | 문서 언어 (기본 ko) |
| `form_id` | `integer` | Y | N | 보고서 양식 (ax.tb_rpt_form). 양식이 있으면 섹션 기반 청킹의 기준으로 사용 |
| `report_id` | `character varying(30)` | Y | N | 이 문서를 만든 보고서 (ax.tb_rpt_report). 보고서 산출물일 때만 채움 |
| `customer_id` | `integer` | Y | N | 관련 고객사 (ax.tb_prod_customer). 문서 검색과 실적 조회를 잇는 키 |
| `product_id` | `integer` | Y | N | 관련 제품 모델 (ax.tb_prod_product) |
| `owner_dept_id` | `integer` | Y | N | 문서 소관 부서. scope_cd = DEPT 일 때 열람 기본 허용 대상 |
| `author_user_id` | `common.d_user_id` | Y | N | 작성자 사번. scope_cd = OWNER 문서는 이 사람만 열람 |
| `plant_cd` | `common.d_plant_cd` | Y | N | 공장 코드. 청크에 복제돼 검색 필터로 쓰임 |
| `wc_cd` | `common.d_wc_cd` | Y | N | 관련 작업장 코드. 1,476건 전부 비어 있어 검색 조건으로 쓰지 않음 |
| `eqpt_cd` | `common.d_eqpt_cd` | Y | N | 관련 설비 코드. wc_cd 와 마찬가지로 비어 있어 질의 문장으로 대신 탐색 |
| `mold_cd` | `common.d_mold_cd` | Y | N | 관련 금형 코드 (MES) |
| `item_cd` | `common.d_item_cd` | Y | N | 관련 품목 코드 (MES). 품목으로 문서를 좁힐 때 사용 |
| `lot_no` | `common.d_lot_no` | Y | N | 대상 LOT — mes.tb_pop_label_hist / tb_pop_stock 과 조인 |
| `serial_no` | `common.d_lot_serial` | Y | N | 관련 시리얼 번호 (MES) |
| `defect_cd` | `common.d_defect_cd` | Y | N | 관련 불량 코드 (MES). 불량 유형으로 문서를 좁힐 때 사용 |
| `scope_cd` | `character varying(30)` | N | N | 열람 범위. 공통코드 그룹 = VEC_SCOPE (ALL=전사 공개, DEPT=소관 부서 + ACL, ACL=ACL 명시 부서만, OWNER=작성자만) |
| `confidential_cd` | `character varying(30)` | N | N | 기밀 등급. 공통코드 그룹 = VEC_CONFIDENTIAL (PUBLIC, INTERNAL, CONFIDENTIAL, CUSTOMER=고객사 제공물). CONFIDENTIAL 이상은 외부 임베딩 API 전송을 금지 |
| `retention_until` | `date` | Y | N | 보존 만료일. 경과 문서는 청크·임베딩까지 함께 삭제 |
| `ingest_state_cd` | `character varying(30)` | N | N | 수집 상태. 공통코드 그룹 = VEC_INGEST_STATE (REGISTERED=등록, EXTRACTED=텍스트 추출 완료, CHUNKED=청킹 완료, EMBEDDED=임베딩 완료, FAILED=실패, SKIPPED=대상 아님) |
| `cur_ver` | `integer` | N | N | 현재 유효 버전 번호. 검색은 이 버전의 청크만 대상으로 함 |
| `chunk_cnt` | `integer` | N | N | 현재 버전(cur_ver)의 청크 수 |
| `del_flg` | `common.d_yn` | N | N | Y = 삭제된 문서. 청크에 복제돼 색인에서도 함께 빠짐 |
| `remark` | `character varying(1000)` | Y | N | 비고 |
| `ins_date` | `timestamp with time zone` | N | N | 등록일시 |
| `ins_user` | `common.d_user_id` | Y | N | 등록자 (사번) |
| `upd_date` | `timestamp with time zone` | N | N | 최종 수정일시 |
| `upd_user` | `common.d_user_id` | Y | N | 최종 수정자 (사번) |

**테이블명:** `vec.tb_doc_chunk`

**테이블 설명:** 문서 청크 + 임베딩 — 검색의 최소 단위. 응답 근거(citation)는 이 행을 가리킴

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `chunk_id` | `bigint` | N | Y | 청크 식별자 (PK). 응답 근거(citation)가 가리키는 값 |
| `doc_id` | `bigint` | N | N | 원본 문서 (vec.tb_doc) |
| `doc_ver` | `integer` | N | N | 이 청크를 만든 문서 버전. tb_doc.cur_ver 와 같으면 is_current 가 참 |
| `chunk_seq` | `integer` | N | N | 문서 안 청크 순번. 1 부터 |
| `chunk_text` | `text` | N | N | 청크 원문. 마스킹하지 않은 원문을 저장하고 마스킹은 응답 생성 단계에서 적용한다 (설계 결정 3번) |
| `char_cnt` | `integer` | N | N | 청크 문자 수 |
| `token_cnt` | `integer` | Y | N | 임베딩 모델 토크나이저 기준 토큰 수. max_tokens 초과 청크를 걸러내는 데 사용 |
| `page_no` | `integer` | Y | N | 원본 페이지 번호. "8D 리포트 3쪽" 처럼 근거 위치를 제시하는 데 사용 |
| `section_path` | `character varying(300)` | Y | N | 문서 내 위치 경로 (예: 8D > D4 근본원인 > 4-2 5Why). 양식 문서는 이 경로가 검색 품질에 크게 기여 |
| `heading` | `character varying(300)` | Y | N | 청크가 속한 소제목. 검색 결과에 위치를 함께 표시 |
| `overlap_chars` | `integer` | N | N | 앞 청크와 겹친 문자 수. 문맥 단절을 막기 위해 통상 청크 길이의 10~15% |
| `embed_model_id` | `integer` | Y | N | 임베딩에 쓴 모델 (vec.tb_embed_model). 차원이 다르면 함께 검색할 수 없음 |
| `embedding` | `vector(1024)` | Y | N | 임베딩 벡터(1024차원). NULL = 청킹은 됐으나 임베딩 미완료 |
| `embedded_at` | `timestamp with time zone` | Y | N | 임베딩 완료 시각. NULL 이면 청킹만 되고 벡터가 없음 |
| `tsv` | `tsvector` | Y | N | 전문검색 벡터. 한국어 형태소 분석기(pgroonga/pg_bigm)가 없어 simple 구성이며, 한국어 부분일치는 pg_trgm 인덱스가 담당 |
| `doc_type_cd` | `character varying(30)` | N | N | 문서 유형 (tb_doc 복제값). 공통코드 그룹 = VEC_DOC_TYPE (REPORT_8D=8D 리포트, FACA, SPEC=규격서·도면 등) |
| `doc_date` | `date` | Y | N | 문서 일자 (tb_doc 복제값). 기간 필터를 청크에서 바로 적용 |
| `plant_cd` | `common.d_plant_cd` | Y | N | 공장 코드 (tb_doc 복제값). 검색 필터 조건 |
| `owner_dept_id` | `integer` | Y | N | 소관 부서 (tb_doc 복제값, ax.tb_sys_dept). 권한 판정에 사용 |
| `scope_cd` | `character varying(30)` | N | N | 공개 범위 (tb_doc 복제값). 공통코드 그룹 = VEC_SCOPE (ALL=전사 공개, DEPT=소관 부서+ACL, ACL=명시 부서만, OWNER=작성자만) |
| `del_flg` | `common.d_yn` | N | N | tb_doc.del_flg 복제값. 삭제 문서 청크를 색인에서 제외 |
| `is_current` | `boolean` | N | N | tb_doc.cur_ver 와 일치하는 최신 버전 청크 여부. HNSW 부분 인덱스 조건으로 사용해 구버전을 색인에서 제외 |
| `ins_date` | `timestamp with time zone` | N | N | 등록일시. 복제 컬럼은 trg_doc_sync_chunk 가 문서 변경 때 맞춰 갱신 |

**테이블명:** `vec.tb_doc_chunk_embed_ext`

**테이블 설명:** 대체 모델 임베딩 — 차원별 컬럼을 두어 한 테이블에서 여러 모델을 병행 평가. 운영 전환 시 tb_doc_chunk.embedding 을 재선언하고 이관

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `chunk_id` | `bigint` | N | Y | 대상 청크 (vec.tb_doc_chunk) |
| `model_id` | `integer` | N | Y | 대체 임베딩 모델 (vec.tb_embed_model). 본 컬럼과 병행 평가하는 모델 |
| `embedding_3072` | `halfvec(3072)` | Y | N | OpenAI text-embedding-3-large 급 3072차원 (halfvec). vector(3072) 는 HNSW 색인 한계 2000 을 넘어 색인할 수 없음 |
| `embedding_768` | `vector(768)` | Y | N | Ko-SBERT / e5-base 급 768차원 |
| `embedded_at` | `timestamp with time zone` | N | N | 이 모델로 임베딩한 시각 |

**테이블명:** `vec.tb_doc_data_field`

**테이블 설명:** 문서가 포함한 민감 데이터 항목 (qty/yield/price/customer/plan/mold/worker). 검색 권한 판정의 두 번째 축

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `doc_id` | `bigint` | N | Y | 대상 문서 (vec.tb_doc). 문서를 지우면 함께 삭제됨 |
| `field_key` | `character varying(30)` | N | Y | 문서에 들어 있는 민감 데이터 항목 (ax.tb_sys_data_field — qty·price 등) |
| `hit_cnt` | `integer` | N | N | 문서 내 탐지 횟수. 마스킹 비용 추정과 감사 로그 masked_cnt 산정에 사용 |
| `detected_by` | `character varying(30)` | Y | N | 탐지 주체 (RULE=정규식 규칙, AGENT=7 보안 필터링, MANUAL=수동 지정) |
| `ins_date` | `timestamp with time zone` | N | N | 등록일시 |

**테이블명:** `vec.tb_doc_dept_perm`

**테이블 설명:** 문서 × 부서 열람 권한. scope_cd = ACL 또는 DEPT 인 문서에 적용. 통합관리자 부서(is_super_admin)는 ACL 과 무관하게 전체 열람

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `doc_id` | `bigint` | N | Y | 대상 문서 (vec.tb_doc). 문서를 지우면 함께 삭제됨 |
| `dept_id` | `integer` | N | Y | 열람을 허용한 부서 (ax.tb_sys_dept) |
| `can_read` | `boolean` | N | N | true = 그 부서가 이 문서를 읽을 수 있음. scope_cd = DEPT·ACL 에서 판정됨 |
| `ins_date` | `timestamp with time zone` | N | N | 등록일시 |
| `ins_user` | `common.d_user_id` | Y | N | 등록자 (사번) |

**테이블명:** `vec.tb_doc_entity`

**테이블 설명:** 문서 엔터티 링크 — 문서/청크에서 추출한 표현을 MES·AX 마스터 키로 확정한 결과. 문서 검색과 실적 조회를 연결하는 브릿지

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `entity_id` | `bigint` | N | Y | 엔터티 링크 식별자 (PK) |
| `doc_id` | `bigint` | N | N | 대상 문서 (vec.tb_doc) |
| `chunk_id` | `bigint` | Y | N | 표현이 나온 청크. NULL 이면 문서 단위로만 확정한 것 |
| `entity_type_cd` | `character varying(30)` | N | N | 엔터티 유형. 공통코드 그룹 = VEC_ENTITY_TYPE (ITEM, EQPT, MOLD, DEFECT, LOT, WC, PRODUCT, CUSTOMER, TERM, USER, DATE, QTY) |
| `surface_form` | `character varying(300)` | N | N | 문서에 실제로 나타난 표현. 용어 사전 유사어 등록 후보를 발굴하는 데 사용 |
| `confidence` | `numeric(5,4)` | Y | N | 연결 신뢰도 0~1. 낮은 건은 담당자 검토(HITL) 대상 |
| `plant_cd` | `common.d_plant_cd` | Y | N | 확정된 공장 코드 (MES) |
| `wc_cd` | `common.d_wc_cd` | Y | N | 확정된 작업장 코드 (MES) |
| `eqpt_cd` | `common.d_eqpt_cd` | Y | N | 확정된 설비 코드 (MES) |
| `mold_cd` | `common.d_mold_cd` | Y | N | 확정된 금형 코드 (MES) |
| `item_cd` | `common.d_item_cd` | Y | N | 확정된 품목 코드 (MES) |
| `lot_no` | `common.d_lot_no` | Y | N | 확정된 LOT 번호 (MES) |
| `serial_no` | `common.d_lot_serial` | Y | N | 확정된 시리얼 번호 (MES) |
| `defect_cd` | `common.d_defect_cd` | Y | N | 확정된 불량 코드 (MES) |
| `product_id` | `integer` | Y | N | 확정된 제품 모델 (ax.tb_prod_product) |
| `customer_id` | `integer` | Y | N | 확정된 고객사 (ax.tb_prod_customer) |
| `term_id` | `integer` | Y | N | 용어 사전 공식 용어 (ax.tb_gls_term). 표기 정규화 결과 |
| `ref_user_id` | `common.d_user_id` | Y | N | 문서가 가리킨 인원 사번. entity_type_cd = USER 일 때 채움 |
| `extracted_by` | `character varying(30)` | N | N | 추출 주체 (AGENT=8 KG 구축, RULE=규칙, MANUAL=수동) |
| `ins_date` | `timestamp with time zone` | N | N | 등록일시 |

**테이블명:** `vec.tb_doc_version`

**테이블 설명:** 문서 버전 — 텍스트 추출·청킹·임베딩 단위. 이전 버전 청크를 남겨 두면 무중단 재임베딩이 가능

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `doc_id` | `bigint` | N | Y | 대상 문서 (vec.tb_doc) |
| `doc_ver` | `integer` | N | Y | 문서 버전 번호. 청크가 이 값을 물어 무중단 재임베딩이 가능 |
| `content_hash` | `vec.d_content_hash` | Y | N | 본문 해시. 같으면 재추출·재임베딩을 건너뜀 |
| `extract_tool` | `character varying(50)` | Y | N | 텍스트 추출 도구 (예: pdfplumber, tika, docling, tesseract) |
| `parser_ver` | `character varying(20)` | Y | N | 추출기 버전. 파서를 올리면 결과가 달라져 재현에 필요 |
| `ocr_flg` | `common.d_yn` | N | N | OCR 경유 여부. 스캔 문서·보고서 스크린샷은 Y |
| `ocr_confidence` | `numeric(5,4)` | Y | N | OCR 평균 신뢰도. 낮은 문서는 검색 결과 신뢰도 표기에 반영 |
| `page_cnt` | `integer` | Y | N | 추출된 쪽 수 |
| `char_cnt` | `integer` | Y | N | 추출된 본문 문자 수 |
| `chunk_cnt` | `integer` | N | N | 이 버전에서 만든 청크 수 |
| `state_cd` | `character varying(30)` | N | N | 상태. 공통코드 그룹 = VEC_INGEST_STATE |
| `extracted_at` | `timestamp with time zone` | N | N | 텍스트 추출 시각 |
| `embedded_at` | `timestamp with time zone` | Y | N | 임베딩 완료 시각. NULL 이면 아직 벡터가 없음 |
| `ingest_job_id` | `character varying(24)` | Y | N | 이 버전을 만든 수집 배치 (vec.tb_ingest_job) |
| `remark` | `character varying(500)` | Y | N | 비고 |

**테이블명:** `vec.tb_embed_model`

**테이블 설명:** 임베딩 모델 레지스트리 — 모델 교체·A/B 비교·재임베딩 이력 추적의 기준

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `model_id` | `integer` | N | Y | 임베딩 모델 식별자 (PK) |
| `model_key` | `character varying(50)` | N | N | 모델 식별 키 (예: bge-m3, cohere-embed-multilingual-v3, text-embedding-3-large) |
| `model_nm` | `character varying(100)` | N | N | 모델 표시 이름 |
| `provider_cd` | `character varying(30)` | N | N | 제공자. 공통코드 그룹 = VEC_PROVIDER (HF=HuggingFace 자체 호스팅, OPENAI, COHERE, AZURE, ETC) |
| `dim` | `integer` | N | N | 벡터 차원. tb_doc_chunk.embedding 의 선언 차원과 일치해야 한다 (불일치 시 적재 실패) |
| `max_tokens` | `integer` | Y | N | 한 번에 임베딩할 수 있는 최대 토큰 수. 넘는 청크는 걸러냄 |
| `distance_cd` | `character varying(20)` | N | N | 거리 함수. 공통코드 그룹 = VEC_DISTANCE (COSINE, L2, IP=내적). 인덱스 연산자 클래스와 일치시켜야 함 |
| `is_normalized` | `boolean` | N | N | true = 모델이 L2 정규화된 벡터를 반환. 정규화 벡터면 COSINE 과 IP 가 동일 순위를 산출 |
| `endpoint_url` | `character varying(300)` | Y | N | 추론 엔드포인트 URL. 사내 호스팅이면 내부 주소 |
| `is_onprem` | `boolean` | N | N | true = 사내 호스팅. 단가·수율·거래처 등 민감정보가 포함된 문서는 외부 API 전송을 금지하므로 이 값이 정책 판단 기준이 됨 |
| `is_default` | `boolean` | N | N | true = 기본 임베딩 모델. 모델을 지정하지 않은 색인이 이 모델을 사용 |
| `use_flg` | `common.d_yn` | N | N | 사용 여부. N 이면 기본 모델 선택에서 빠짐 |
| `remark` | `character varying(500)` | Y | N | 비고 |
| `ins_date` | `timestamp with time zone` | N | N | 등록일시 |
| `ins_user` | `common.d_user_id` | Y | N | 등록자 (사번) |
| `upd_date` | `timestamp with time zone` | N | N | 최종 수정일시 |
| `upd_user` | `common.d_user_id` | Y | N | 최종 수정자 (사번) |

**테이블명:** `vec.tb_ingest_error`

**테이블 설명:** 수집·임베딩 실패 상세. 실패 문서만 재처리할 수 있도록 단계와 원본 정보를 남김

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `err_id` | `bigint` | N | Y | 수집 오류 식별자 (PK) |
| `job_id` | `character varying(24)` | N | N | 실패가 난 수집 배치 (vec.tb_ingest_job) |
| `doc_id` | `bigint` | Y | N | 실패한 문서 (vec.tb_doc) |
| `chunk_id` | `bigint` | Y | N | 실패한 청크. 청킹 이후 단계(EMBED 등)에서만 채워짐 |
| `stage_cd` | `character varying(30)` | N | N | 실패 단계. 공통코드 그룹 = VEC_STAGE (FETCH=수집, EXTRACT=텍스트 추출, OCR, NORMALIZE=용어 정규화, CHUNK=청킹, TAG=민감정보 태깅, EMBED=임베딩, ENTITY=엔터티 추출, INDEX=색인) |
| `err_code` | `character varying(50)` | Y | N | 오류 코드 |
| `err_msg` | `character varying(1000)` | N | N | 오류 메시지 |
| `payload` | `jsonb` | Y | N | 실패 당시 입력·응답 원본(JSON). 재처리 판단 근거 |
| `resolved` | `boolean` | N | N | true = 조치 완료. 재처리 대상 목록에서 빠짐 |
| `ins_date` | `timestamp with time zone` | N | N | 등록일시 |

**테이블명:** `vec.tb_ingest_job`

**테이블 설명:** 문서 수집·임베딩 배치 이력. 상태·실행주체 코드는 ax 의 SYNC_STATE / SYNC_TRIGGER 그룹을 재사용

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `job_id` | `character varying(24)` | N | Y | 수집 배치 식별자 (PK). JOB-yyyyMMddHHmmss 형식으로 채번 |
| `job_type_cd` | `character varying(30)` | N | N | 작업 유형. 공통코드 그룹 = VEC_JOB_TYPE (INGEST=신규 수집, REEMBED=재임베딩, REINDEX=인덱스 재생성, PURGE=보존 만료 삭제, ENTITY=엔터티 재추출) |
| `embed_model_id` | `integer` | Y | N | 이 배치가 쓴 임베딩 모델 (vec.tb_embed_model) |
| `started_at` | `timestamp with time zone` | N | N | 시작 시각 |
| `ended_at` | `timestamp with time zone` | Y | N | 종료 시각 |
| `duration_sec` | `integer` | Y | N | 소요 시간(초) |
| `doc_cnt` | `integer` | N | N | 처리 대상 문서 수 |
| `chunk_cnt` | `integer` | N | N | 만들어진 청크 수 |
| `embed_cnt` | `integer` | N | N | 임베딩한 청크 수 |
| `ok_cnt` | `integer` | N | N | 성공 건수 |
| `ng_cnt` | `integer` | N | N | 실패 건수. 상세는 vec.tb_ingest_error 에 남음 |
| `token_cnt` | `bigint` | N | N | 임베딩 토큰 사용량. 외부 API 사용 시 비용 추적 |
| `state_cd` | `character varying(30)` | N | N | 상태. 공통코드 그룹 = SYNC_STATE (PENDING=예약 대기, RUNNING=진행 중, DONE=완료, FAIL=실패) |
| `triggered_by_cd` | `character varying(30)` | N | N | 실행 주체. 공통코드 그룹 = SYNC_TRIGGER (BATCH=배치, MANUAL=수동 이관, RETRY=재실행) |
| `triggered_by` | `common.d_user_id` | Y | N | 실행을 요청한 사번. 용어 사전 관리 화면의 용어 재색인은 누른 사람이 들어감 |
| `remark` | `character varying(500)` | Y | N | 비고 (예: 용어 사전 임베딩 재생성 (280건)) |

**테이블명:** `vec.tb_query_hit`

**테이블 설명:** 질의별 검색 결과 1건 — 어느 경로에서 몇 위로 걸렸고, 리랭킹 후 몇 번째가 되었으며, 인용·클릭되었는지. chunk_id/doc_id 에 FK 를 걸지 않아 문서가 삭제·재수집되어도 이력이 보존됨

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `query_id` | `bigint` | N | Y | 대상 질의 (vec.tb_query_log) |
| `chunk_id` | `bigint` | N | Y | 검색된 청크 (vec.tb_doc_chunk). FK 를 걸지 않아 문서가 지워져도 이력은 남음 |
| `doc_id` | `bigint` | N | N | 그 청크의 문서 (vec.tb_doc). FK 를 걸지 않음 |
| `rank_no` | `integer` | N | N | RRF 융합 순위 (리랭킹 전). 1 부터 |
| `vec_rank` | `integer` | Y | N | 벡터(HNSW) 경로 순위. NULL = 이 경로에서는 후보에 들지 못함 |
| `ts_rank` | `integer` | Y | N | tsvector 전문검색 경로 순위. 품번·LOT·설비코드 등 완전일치가 여기서 잡힘 |
| `trgm_rank` | `integer` | Y | N | pg_trgm 부분일치 경로 순위. 한국어 조사 변화·오타가 여기서 잡힘 |
| `vec_sim` | `real` | Y | N | 벡터 경로 점수 = 1 − (embedding <=> 질의벡터) 코사인 유사도. 1 에 가까울수록 유사 |
| `ts_score` | `real` | Y | N | 전문검색 경로 점수 = ts_rank_cd(tsv, websearch_to_tsquery). 클수록 적합 |
| `trgm_sim` | `real` | Y | N | 트라이그램 경로 점수 = word_similarity(질의, 청크 본문) 0~1. 임계값 기본 0.4 |
| `rrf_score` | `real` | Y | N | RRF 융합 점수 = Σ 1/(rrf_k + 경로별 순위), 세 경로 합 (rrf_k 기본 60) |
| `rerank_score` | `real` | Y | N | 리랭커(cross-encoder) 점수. DB 가 아니라 애플리케이션이 계산해 채움 |
| `final_seq` | `smallint` | Y | N | 리랭킹 후 최종 노출 순서. NULL 이면 리랭커가 탈락시킨 후보 |
| `is_cited` | `boolean` | N | N | true = 최종 응답에 실제로 인용된 청크. 근거 소명과 검색 품질 평가의 기준이며 학습 데이터(T5) 정답 근거로도 쓰임 |
| `is_clicked` | `boolean` | N | N | true = 사용자가 인용을 눌러 원문을 열람. 관련성의 가장 강한 신호 |
| `masked_field_keys` | `text[]` | Y | N | strict_flg = N 로 노출했을 때 마스킹 처리한 데이터 항목 목록 |

**테이블명:** `vec.tb_query_log`

**테이블 설명:** RAG 검색 질의 이력 — 질의 임베딩까지 보관해 유사 질의 캐싱, 의도 클러스터링, 재질의 원인 분석에 사용

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `query_id` | `bigint` | N | Y | 검색 이력 식별자 (PK) |
| `chat_id` | `bigint` | Y | N | 대응하는 채팅 질의 (ax.tb_ai_chat_log) |
| `asked_at` | `timestamp with time zone` | N | N | 질의 시각 |
| `user_id` | `common.d_user_id` | N | N | 질의자 사번 (ax.tb_sys_user). 문서 권한 판정의 주체 |
| `dept_id` | `integer` | Y | N | 질의자 부서 (ax.tb_sys_dept). 문서 열람 권한 판정 기준 |
| `query_text` | `text` | N | N | 사용자가 입력한 원문 질의 |
| `normalized_text` | `text` | Y | N | 용어 사전으로 정규화한 질의문. 실제 임베딩 대상은 이 값 |
| `query_embedding` | `vector(1024)` | Y | N | 질의 임베딩 벡터(1024차원). 유사 질의 캐싱·의도 클러스터링에 사용 |
| `embed_model_id` | `integer` | Y | N | 질의 임베딩에 쓴 모델 (vec.tb_embed_model) |
| `top_k` | `integer` | N | N | 최종 반환 요청 청크 수 (기본 10) |
| `candidate_k` | `integer` | N | N | 경로별 후보 수 (기본 60). 벡터·전문검색·트라이그램이 각각 이 개수만큼 후보를 추출 |
| `strict_flg` | `common.d_yn` | N | N | Y = 권한 없는 민감 항목을 포함한 문서를 검색에서 제외. N = 노출하되 마스킹 대상으로 표시 |
| `filter_json` | `jsonb` | Y | N | 적용한 필터 (기간·문서유형·LOT·설비 등). 동일 조건 재현과 캐시 키로 사용 |
| `pool_cnt` | `integer` | N | N | 세 경로에서 모인 후보 청크 수(중복 제거 후) = 리랭커 입력 크기. hit_cnt 와의 차이가 리랭킹이 걸러낸 양 |
| `hit_cnt` | `integer` | N | N | 최종 반환된 청크 수. top_k 이하이며 cited_cnt 와의 비율이 인용률 |
| `cited_cnt` | `integer` | N | N | 실제로 응답에 인용된 청크 수. hit_cnt 와의 비율(인용률)이 1차 검색 정밀도 지표 |
| `blocked_doc_cnt` | `integer` | N | N | 권한 때문에 제외된 문서 수. 0 이 아니면 "권한 밖 자료가 있습니다" 안내 근거가 됨 |
| `reranker_key` | `character varying(50)` | Y | N | 사용한 리랭커 모델 키 (예: qwen3-reranker-0.6b). NULL 이면 리랭킹 없이 RRF 순위를 그대로 사용 |
| `embed_ms` | `integer` | Y | N | 질의 임베딩 소요 시간(ms). search_ms 와 나눠 기록해야 병목을 구분 |
| `search_ms` | `integer` | Y | N | 하이브리드 검색(3경로 + RRF 융합) 소요 시간(ms) |
| `rerank_ms` | `integer` | Y | N | 리랭킹 소요 시간(ms). search_ms 와 나눠 기록해야 병목을 구분할 수 있음 |
| `total_ms` | `integer` | Y | N | 임베딩·검색·리랭킹을 합친 전체 소요 시간(ms) |

**테이블명:** `vec.tb_term_embedding`

**테이블 설명:** 용어 사전 임베딩 — 공식 용어(variant_id IS NULL)와 유사어 각각을 벡터화. 질의 정규화와 유사어 후보 추천에 사용

| 컬럼명 | 자료형 | NULL 허용 | 기본 키 | DB 주석 |
| :--- | :--- | :---: | :---: | :--- |
| `term_emb_id` | `bigint` | N | Y | 용어 임베딩 식별자 (PK) |
| `term_id` | `integer` | N | N | 대상 공식 용어 (ax.tb_gls_term) |
| `variant_id` | `integer` | Y | N | 대상 유사어 (ax.tb_gls_variant). NULL 이면 공식 용어 자체의 벡터 |
| `embed_source` | `character varying(500)` | N | N | 임베딩에 실제로 넣은 문자열 (예: "Stiffener — 스티프너 / FPCB 보강판"). 정의문까지 포함하면 정규화 정확도가 상승 |
| `embed_model_id` | `integer` | N | N | 임베딩에 쓴 모델 (vec.tb_embed_model) |
| `embedding` | `vector(1024)` | N | N | 용어 임베딩 벡터(1024차원). 질의 정규화와 유사어 후보 추천에 사용 |
| `embedded_at` | `timestamp with time zone` | N | N | 임베딩 시각 |
