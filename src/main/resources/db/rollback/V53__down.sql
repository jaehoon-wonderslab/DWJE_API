-- =====================================================================================
--  V53 되돌리기 — 다운로드 이력 부서 ID · 기록 출처 · 범위 · 조회 조건 · 형식 코드 (2026-10-01)
--
--  · 컬럼 4개 · CHECK 2개 · 인덱스 2개를 지운다. 적용 뒤 쌓인 값은 돌아오지 않는다.
--  · 공통코드 XLSX · PNG · JSONL 은 쓰는 기록이 없을 때만 지우고, 있으면 사용 중지(use_flg = N)로 둔다.
--  · 주석은 V53 직전 문구로 돌린다.
--
--  [API 를 먼저 내린다]
--  새 API 는 네 컬럼에 기록한다. 컬럼이 사라지면 내려받기 기록 INSERT 가 실패한다.
--
--  [적용]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f rollback/V53__down.sql
--  두 번 실행해도 안전하다.
-- =====================================================================================

BEGIN;

DROP INDEX IF EXISTS ax.ix_rpt_dl_menu;
DROP INDEX IF EXISTS ax.ix_rpt_dl_dept;

ALTER TABLE ax.tb_rpt_download_log DROP CONSTRAINT IF EXISTS ck_rpt_dl_scope;
ALTER TABLE ax.tb_rpt_download_log DROP CONSTRAINT IF EXISTS ck_rpt_dl_origin;
ALTER TABLE ax.tb_rpt_download_log DROP COLUMN IF EXISTS cond_summary;
ALTER TABLE ax.tb_rpt_download_log DROP COLUMN IF EXISTS scope_cd;
ALTER TABLE ax.tb_rpt_download_log DROP COLUMN IF EXISTS origin_cd;
ALTER TABLE ax.tb_rpt_download_log DROP COLUMN IF EXISTS dept_id;

DELETE FROM ax.tb_sys_code c
 WHERE c.group_cd = 'RPT_FORMAT' AND c.code IN ('XLSX', 'PNG', 'JSONL')
   AND NOT EXISTS (SELECT 1 FROM ax.tb_rpt_download_log l WHERE l.format_cd = c.code);
UPDATE ax.tb_sys_code
   SET use_flg = 'N', upd_date = now(), upd_user = 'V53-down'
 WHERE group_cd = 'RPT_FORMAT' AND code IN ('XLSX', 'PNG', 'JSONL') AND use_flg = 'Y';

COMMENT ON COLUMN ax.tb_rpt_download_log.report_id IS
  '대상 보고서 (ax.tb_rpt_report). 보고서가 아닌 화면 내려받기면 비어 있음. 보고서 다운로드 이력 화면의 「화면」 열에 화면명과 경로로 표시';
COMMENT ON COLUMN ax.tb_rpt_download_log.format_cd IS
  '형식. 공통코드 그룹 = RPT_FORMAT (XLS=엑셀, CSV=CSV, PDF=인쇄·PDF)';
COMMENT ON COLUMN ax.tb_rpt_download_log.blind_cnt IS
  '권한이 없어 파일에서 제외된 항목 수. 상세 항목은 tb_rpt_download_blind';

COMMIT;
