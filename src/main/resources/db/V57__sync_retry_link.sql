-- =====================================================================================
--  V57 : 데이터 연동 이력 — 재실행 연결 컬럼 · 작업 목록 기간 인덱스 · 그룹웨어 실행 모드 코드 (2026-10-01)
--
--  [배경]
--  · 재실행 작업과 원 작업을 잇는 값이 remark 문구뿐이었고, 엔진이 작업을 마감할 때 remark 를 덮어써
--    연결이 사라졌다. 같은 원 작업의 중복 재실행을 막을(SYN-02) 근거가 없었다 → retry_of_job_id.
--  · 작업 목록 기간 조건 coalesce(started_at, scheduled_at) 가 인덱스를 쓰지 못했다.
--  · 실행 모드 GROUPWARE 코드가 이관 엔진 저장소의 SQL 로만 들어와 있어, API 마이그레이션만으로
--    새 환경을 재현할 수 없었다.
--  기획: 12 데이터 연동 이력 4.5 중 1 · 2 · 4 (SYN-02 의 선행인 SYN-06, SYN-08 인덱스, 공통 묶음 M-9).
--
--  [하지 않는 일]
--  · 이관 알림 지표 SYNC_FAIL_RATE · SYNC_STALE_MIN 과 수집 정의(SYN-04) · retry_cnt 주석 정정은
--    알림 엔진 수집기와 함께 3단계에 넣는다.
--  · 쓰기 권한 이관(sys-sync)은 V49 에 이미 들어 있다.
--
--  [이 파일이 하는 일]
--   1. ax.tb_sync_job.retry_of_job_id varchar(20) + 부분 인덱스
--   2. 백필 — 아직 마감되지 않은 PENDING 재실행 작업만 remark 의 「작업 X 재실행」 에서 원 작업 ID 를 채움
--      (마감된 작업은 원문이 없어 채우지 않는다)
--   3. 식 인덱스 ix_sync_job_eff_at — coalesce(started_at, scheduled_at) DESC
--   4. 공통코드 SYNC_RUN_MODE/GROUPWARE (이미 있으면 그대로)
--
--  [순서]
--  이 파일 → API 배포. 이관 엔진은 변경 · 재기동 없음.
--
--  [실행]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f V57__sync_retry_link.sql
--  두 번 실행해도 안전하다. 되돌리기는 rollback/V57__down.sql.
-- =====================================================================================

BEGIN;

-- ── 1. 재실행 연결 ───────────────────────────────────────────────────────────────────
ALTER TABLE ax.tb_sync_job ADD COLUMN IF NOT EXISTS retry_of_job_id varchar(20);
CREATE INDEX IF NOT EXISTS ix_sync_job_retry_of
    ON ax.tb_sync_job (retry_of_job_id) WHERE retry_of_job_id IS NOT NULL;

-- ── 2. 백필 (마감 전 재실행 작업만) ──────────────────────────────────────────────────
DO $$
DECLARE
    v_cnt integer;
BEGIN
    UPDATE ax.tb_sync_job
       SET retry_of_job_id = substring(remark from '작업 (\S+) 재실행')
     WHERE retry_of_job_id IS NULL
       AND state_cd = 'PENDING'
       AND remark ~ '작업 \S+ 재실행';
    GET DIAGNOSTICS v_cnt = ROW_COUNT;
    RAISE NOTICE 'V57: 재실행 연결 백필 % 건', v_cnt;
END $$;

-- ── 3. 기간 조건 식 인덱스 ───────────────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS ix_sync_job_eff_at
    ON ax.tb_sync_job ((coalesce(started_at, scheduled_at)) DESC);

-- ── 4. 실행 모드 코드 ────────────────────────────────────────────────────────────────
INSERT INTO ax.tb_sys_code (group_cd, code, code_nm, code_desc, sort_seq, use_flg, ins_user, upd_user)
VALUES ('SYNC_RUN_MODE', 'GROUPWARE', '그룹웨어 인사정보', '그룹웨어 인사정보 동기화', 5, 'Y', 'V57', 'V57')
ON CONFLICT ON CONSTRAINT pk_tb_sys_code DO NOTHING;

-- ── 주석 ─────────────────────────────────────────────────────────────────────────────
COMMENT ON COLUMN ax.tb_sync_job.retry_of_job_id IS
  '재실행 원 작업 ID (ax.tb_sync_job 논리 참조). 데이터 연동 이력 화면의 재실행이 채움. 화면 작업 ID 아래 「← 원 작업」 과 상세의 재실행 이력. NULL 이면 원 작업이거나 이 컬럼 이전 기록';

COMMIT;
