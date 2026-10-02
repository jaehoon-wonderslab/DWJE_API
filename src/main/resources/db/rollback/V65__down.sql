-- =====================================================================================
--  V65 되돌리기 — 아카이브 키 · 옮긴 시각 인덱스 · 주석 (2026-10-01)
--
--  PK 2개와 인덱스 4개를 지우고 주석을 V65 직전 문구로 돌린다. 아카이브 행은 건드리지 않는다.
--  아카이브 표가 이미 없으면(V58 · V59 를 먼저 되돌림) 해당 부분은 건너뛴다.
--
--  [적용]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f rollback/V65__down.sql
--  두 번 실행해도 안전하다.
-- =====================================================================================

BEGIN;

DO $$
BEGIN
    IF to_regclass('ax.tb_rpt_download_log_arch') IS NOT NULL THEN
        ALTER TABLE ax.tb_rpt_download_log_arch DROP CONSTRAINT IF EXISTS pk_tb_rpt_download_log_arch;
    END IF;
    IF to_regclass('ax.tb_rpt_download_blind_arch') IS NOT NULL THEN
        ALTER TABLE ax.tb_rpt_download_blind_arch DROP CONSTRAINT IF EXISTS pk_tb_rpt_download_blind_arch;
    END IF;
END $$;

DROP INDEX IF EXISTS ax.ix_log_audit_arch_at;
DROP INDEX IF EXISTS ax.ix_sys_perm_log_arch_at;
DROP INDEX IF EXISTS ax.ix_sys_login_hist_arch_at;
DROP INDEX IF EXISTS ax.ix_rpt_dl_arch_at;

COMMENT ON COLUMN ax.tb_dash_upload_doc.doc_id IS
  '업로드 문서 대리키. 업로드 문서 목록 화면의 검색어(문서 ID)와 버전 이력 드로어 부제에 표시';
COMMENT ON COLUMN ax.tb_log_audit.wc_cd IS
  '대상이 특정 작업장인 경우의 작업장 코드';
COMMENT ON COLUMN ax.tb_log_audit.lot_no IS
  '대상이 특정 LOT 인 경우의 LOT NO. mes.tb_pop_label_hist 와 조인 가능';
COMMENT ON COLUMN ax.tb_log_audit.serial_no IS
  '대상 LOT 의 시리얼 번호. lot_no 와 함께 라벨 이력을 가리킴';
COMMENT ON COLUMN ax.tb_log_audit.item_cd IS
  '대상이 특정 품목인 경우의 품목 코드';

COMMIT;
