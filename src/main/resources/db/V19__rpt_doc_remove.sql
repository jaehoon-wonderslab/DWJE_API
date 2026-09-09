-- =====================================================================================
--  덕우전자 AX  |  패치 2026-09-04 — 보고서 문서 관리(초안·버전·확정·결재) 제거
--  파일        : patch_20260904_rpt_doc_remove.sql
--  대상 스키마 : ax
-- -------------------------------------------------------------------------------------
--  [배경]
--    API 확장 마이그레이션 V2__ax_report_extension.sql / V18__rpt_doc_row.sql 이
--    보고서 "문서 인스턴스" 를 저장하는 tb_rpt_doc 계열 8개 테이블을 추가했다.
--    (초안 → 임시저장 → 확정/반려, version_no 로 버전 증가, 결재선, 증빙 이미지, 폐기 상세 행)
--
--    2026-09-04 기획 변경으로 문서 관리 기능이 제거되었다. 보고서는 조회 조건으로 매번 생성해
--    내려받는 산출물이 되고, 남기는 것은 다운로드 이력(tb_rpt_download_log)뿐이다.
--
--  [이 패치가 하는 일]
--    1. 문서 테이블 8개 DROP            : tb_rpt_doc, tb_rpt_doc_field, tb_rpt_doc_event,
--                                         tb_rpt_doc_image, tb_rpt_doc_approval, tb_rpt_doc_row,
--                                         tb_rpt_scrap_row, tb_rpt_unmask_req.doc_id(컬럼만)
--    2. 문서 전용 공통코드 8그룹 삭제    : RPT_DOC_KIND, RPT_DOC_STATE, RPT_DOC_EVENT, RPT_ORIGIN,
--                                         RPT_APPR_STEP, SCRAP_KIND, SCRAP_ORIGIN, PRICE_SOURCE
--                                         (PRICE_KIND 는 tb_prod_item_price 가 쓰므로 유지)
--    3. 다운로드 이력 보강               : tb_rpt_download_log.params_json / file_size 추가
--                                         — 문서가 없으므로 "어떤 조건으로 만든 파일인지" 를 이력에 남긴다
--
--  [건드리지 않는 것]
--    tb_rpt_report(보고서 정의) · tb_rpt_form / tb_rpt_form_field(양식) · tb_rpt_download_log /
--    tb_rpt_download_blind(이력) · tb_rpt_unmask_req(마스킹 해제 요청, doc_id 컬럼만 제거) ·
--    tb_prod_downtime · tb_prod_ship_plan · tb_prod_item_price · tb_qc_lrr_notice (V2 의 비문서 테이블)
--    메뉴(daily-history, rpt-scrap-new) 는 화면 소관이라 여기서 손대지 않는다.
--
--  [적용]
--    psql -U dwje_local -d dwjedb -v ON_ERROR_STOP=1 -f patch_20260904_rpt_doc_remove.sql
--    멱등 — 두 번 실행해도 안전하다. 문서 테이블 데이터는 복구되지 않으므로 필요하면 실행 전에 덤프한다.
--    API Flyway 디렉터리(API/src/main/resources/db)에는 V19__rpt_doc_remove.sql 로 복사해 두면
--    setup_local_db.sh 가 V2 → ... → V18 → V19 순으로 적용해 같은 결과가 된다.
-- =====================================================================================

SET client_min_messages = WARNING;

BEGIN;

-- -------------------------------------------------------------------------------------
-- 1. 문서 테이블 제거 (자식 → 부모 순)
-- -------------------------------------------------------------------------------------
DROP TABLE IF EXISTS ax.tb_rpt_doc_row;
DROP TABLE IF EXISTS ax.tb_rpt_doc_field;
DROP TABLE IF EXISTS ax.tb_rpt_doc_event;
DROP TABLE IF EXISTS ax.tb_rpt_doc_image;
DROP TABLE IF EXISTS ax.tb_rpt_doc_approval;
DROP TABLE IF EXISTS ax.tb_rpt_scrap_row;

-- 마스킹 해제 요청은 데이터 접근 권한 기능이라 남긴다. 문서를 가리키던 컬럼만 뺀다.
-- 요청 맥락은 menu_id + field_keys 로 남는다.
ALTER TABLE IF EXISTS ax.tb_rpt_unmask_req DROP COLUMN IF EXISTS doc_id;
COMMENT ON TABLE ax.tb_rpt_unmask_req IS '마스킹 해제 요청 — 화면(menu_id) 과 데이터 항목(field_keys) 단위로 요청한다. 감사 로그와 연계';

DROP TABLE IF EXISTS ax.tb_rpt_doc;

-- -------------------------------------------------------------------------------------
-- 2. 문서 전용 공통코드 정리 (ref → code → group 순)
-- -------------------------------------------------------------------------------------
DELETE FROM ax.tb_sys_code_ref
 WHERE target_table IN ('tb_rpt_doc','tb_rpt_doc_field','tb_rpt_doc_event',
                        'tb_rpt_doc_approval','tb_rpt_scrap_row');

DELETE FROM ax.tb_sys_code
 WHERE group_cd IN ('RPT_DOC_KIND','RPT_DOC_STATE','RPT_DOC_EVENT','RPT_ORIGIN',
                    'RPT_APPR_STEP','SCRAP_KIND','SCRAP_ORIGIN','PRICE_SOURCE');

DELETE FROM ax.tb_sys_code_group
 WHERE group_cd IN ('RPT_DOC_KIND','RPT_DOC_STATE','RPT_DOC_EVENT','RPT_ORIGIN',
                    'RPT_APPR_STEP','SCRAP_KIND','SCRAP_ORIGIN','PRICE_SOURCE');

-- -------------------------------------------------------------------------------------
-- 3. 다운로드 이력 보강 — ai_db_query.sql 의 tb_rpt_download_log 정의와 동일
-- -------------------------------------------------------------------------------------
ALTER TABLE ax.tb_rpt_download_log ADD COLUMN IF NOT EXISTS params_json jsonb;
ALTER TABLE ax.tb_rpt_download_log ADD COLUMN IF NOT EXISTS file_size   bigint;

COMMENT ON TABLE  ax.tb_rpt_download_log             IS '보고서 다운로드 이력 — 엑셀·CSV·인쇄(PDF) 모두 기록한다. 문서를 저장하지 않으므로 이 이력이 "누가 · 언제 · 어떤 조건으로 · 무엇을" 내려받았는지의 유일한 기록이다. 보존 3년 · 보안 감사 로그와 함께 제출 대상 (append-only)';
COMMENT ON COLUMN ax.tb_rpt_download_log.scope_desc  IS '조회 범위 표시문 (예: 2026-09-01 ~ 09-03 · PRESS). 사람이 읽는 요약이고, 정확한 조건은 params_json';
COMMENT ON COLUMN ax.tb_rpt_download_log.params_json IS '생성 조건 스냅샷 (대상일·기간·공정·LOT·양식·고객사 공개 정책 등 요청 파라미터 그대로). 문서를 저장하지 않으므로 같은 산출물을 다시 만들 수 있는 유일한 단서';
COMMENT ON COLUMN ax.tb_rpt_download_log.file_size   IS '내려받은 파일 크기(byte). 인쇄(PDF) 처럼 파일이 없으면 NULL';

COMMIT;

-- 확인용
-- SELECT table_name FROM information_schema.tables WHERE table_schema='ax' AND table_name LIKE 'tb_rpt_%' ORDER BY 1;
-- SELECT group_cd FROM ax.tb_sys_code_group WHERE group_cd LIKE 'RPT_%' OR group_cd LIKE 'SCRAP_%' OR group_cd LIKE 'PRICE_%';
