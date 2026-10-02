-- =====================================================================================
--  V53 : 보고서 다운로드 이력 — 부서 ID 스냅샷 · 기록 출처 · 내려받기 범위 · 조회 조건 · 형식 코드 (2026-10-01)
--
--  [배경]
--  · 부서 필터가 부서명 문자열이라 부서 이름이 바뀌면 과거 기록이 빠졌다 → 당시 부서 ID 를 함께 남긴다.
--  · 브라우저가 신고한 기록과 서버가 만든 파일의 기록을 구분할 수 없었다 → origin_cd.
--  · 엑셀 옵션 패널(조회 목록 / 전체)이 생기면서 받은 범위와 조회 조건을 남겨야 한다 → scope_cd · cond_summary.
--    API 본문 이름은 scopeCd · condSummary. 기존 scope(사람이 읽는 범위 문구)는 scope_desc 그대로.
--  · 형식 코드에 xlsx · png · jsonl 이 없어 브라우저가 표시명을 그대로 저장했다 → 공통코드 추가.
--  기획: 10 보고서 다운로드 이력 4.5 중 DLG-01 · 02 · 03 · 05 · 15 몫 (공통 묶음 M-4 의 2단계 몫).
--  아카이브 표 · 변경 차단 트리거(DLG-07)는 3단계 몫이라 넣지 않았다.
--
--  [이 파일이 하는 일]
--   1. 컬럼 4개 — dept_id · origin_cd · scope_cd · cond_summary (모두 NULL 허용, 기본값 없음)
--      과거 행은 NULL(미상)로 둔다. 값을 지어내지 않는다.
--   2. CHECK 2개 — scope_cd ∈ {VIEW, ALL}, origin_cd ∈ {CLIENT, SERVER}
--   3. 인덱스 2개 — 화면별 · 부서별 조회
--   4. 공통코드 RPT_FORMAT — XLSX · PNG · JSONL
--   5. 주석 — 새 컬럼과 report_id · format_cd · blind_cnt 뜻 정정
--
--  [순서]
--  이 파일 → API 배포(새 컬럼 기록) → WEB 배포. API 보다 늦게 적용하면 기록 INSERT 가 실패한다.
--
--  [실행]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f V53__download_log_scope_origin.sql
--  두 번 실행해도 안전하다. 되돌리기는 rollback/V53__down.sql.
-- =====================================================================================

BEGIN;

-- ── 1. 컬럼 ──────────────────────────────────────────────────────────────────────────
ALTER TABLE ax.tb_rpt_download_log ADD COLUMN IF NOT EXISTS dept_id      integer;
ALTER TABLE ax.tb_rpt_download_log ADD COLUMN IF NOT EXISTS origin_cd    varchar(10);
ALTER TABLE ax.tb_rpt_download_log ADD COLUMN IF NOT EXISTS scope_cd     varchar(10);
ALTER TABLE ax.tb_rpt_download_log ADD COLUMN IF NOT EXISTS cond_summary varchar(500);

-- ── 2. CHECK ─────────────────────────────────────────────────────────────────────────
ALTER TABLE ax.tb_rpt_download_log DROP CONSTRAINT IF EXISTS ck_rpt_dl_scope;
ALTER TABLE ax.tb_rpt_download_log
    ADD CONSTRAINT ck_rpt_dl_scope CHECK (scope_cd IS NULL OR scope_cd IN ('VIEW', 'ALL'));
ALTER TABLE ax.tb_rpt_download_log DROP CONSTRAINT IF EXISTS ck_rpt_dl_origin;
ALTER TABLE ax.tb_rpt_download_log
    ADD CONSTRAINT ck_rpt_dl_origin CHECK (origin_cd IS NULL OR origin_cd IN ('CLIENT', 'SERVER'));

-- ── 3. 인덱스 ────────────────────────────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS ix_rpt_dl_menu ON ax.tb_rpt_download_log (menu_id, downloaded_at DESC);
CREATE INDEX IF NOT EXISTS ix_rpt_dl_dept ON ax.tb_rpt_download_log (dept_id, downloaded_at DESC) WHERE dept_id IS NOT NULL;

-- ── 4. 공통코드 ──────────────────────────────────────────────────────────────────────
INSERT INTO ax.tb_sys_code (group_cd, code, code_nm, sort_seq, use_flg, ins_user, upd_user) VALUES
  ('RPT_FORMAT', 'XLSX',  '엑셀 (.xlsx)',        4, 'Y', 'V53', 'V53'),
  ('RPT_FORMAT', 'PNG',   '이미지 (.png)',       5, 'Y', 'V53', 'V53'),
  ('RPT_FORMAT', 'JSONL', '학습데이터 (.jsonl)', 6, 'Y', 'V53', 'V53')
ON CONFLICT (group_cd, code) DO UPDATE
   SET use_flg = 'Y', upd_date = now(), upd_user = 'V53'
 WHERE ax.tb_sys_code.use_flg = 'N';

-- ── 5. 주석 ──────────────────────────────────────────────────────────────────────────
COMMENT ON COLUMN ax.tb_rpt_download_log.dept_id IS
  '내려받을 당시 소속 부서 ID 스냅샷 (ax.tb_sys_dept 논리 참조, FK 없음). 2026-10 이전 기록은 NULL';
COMMENT ON COLUMN ax.tb_rpt_download_log.origin_cd IS
  '기록 출처 — CLIENT(브라우저가 만든 파일, 브라우저가 신고) / SERVER(서버가 만든 파일). 2026-10 이전 기록은 NULL(미상)';
COMMENT ON COLUMN ax.tb_rpt_download_log.scope_cd IS
  '내려받기 범위 — VIEW(조회 목록: 그리드에 보이던 행) / ALL(전체 다운로드). 2026-10 이전 기록은 NULL(미상)';
COMMENT ON COLUMN ax.tb_rpt_download_log.cond_summary IS
  '내려받을 때의 조회 조건 요약(사람이 읽는 문구, 500자). 정확한 조건은 params_json. 앞 100자는 scope_desc 에도 저장';
COMMENT ON COLUMN ax.tb_rpt_download_log.report_id IS
  '보고서 정의가 있는 화면일 때의 보고서 ID (ax.tb_rpt_report, RPT_*). 2026-10 이전 브라우저 기록에는 화면 ID 가 들어 있어 조회 시 menu_id 로 해석함';
COMMENT ON COLUMN ax.tb_rpt_download_log.format_cd IS
  '형식. 공통코드 RPT_FORMAT (XLS · XLSX · CSV · PDF · PNG · JSONL). 2026-10 이전 브라우저 기록은 표시명(예: 엑셀 (.XLS))으로 저장되어 조회 시 코드로 정규화함';
COMMENT ON COLUMN ax.tb_rpt_download_log.blind_cnt IS
  '파일에서 비공개로 채운 셀 수(데이터 접근 권한 기준). 파일 안 「비공개 처리 n건」 과 같은 값. 항목별 내역은 tb_rpt_download_blind.cell_cnt 이며 그 합과 같음';

COMMIT;
