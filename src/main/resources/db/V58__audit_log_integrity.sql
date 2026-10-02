-- =====================================================================================
--  V58 : 감사 기록 무결성 · 보존 — 연결 · 접속 환경 컬럼, 감사 유형 코드, 아카이브 표 (2026-10-01)
--
--  [배경]
--  · 권한 변경이 권한 변경 이력과 감사 로그에 두 번 따로 남아 감사 화면에 두 줄로 보였다
--    → 권한 변경 이력에 같은 사건의 감사 로그 행 번호(audit_id)를 남긴다(AUD-06).
--  · 감사 로그에 접속 브라우저 정보가, 로그인 이력에 당시 부서가 없었다(AUD-14).
--  · 감사 유형이 AUTO_GEN · RAW_VIEW 에 몰려 내려받기 · 설정 변경 · 계정 보안을 구분할 수 없었고,
--    API 가 이미 쓰는 결과 코드 MASKED 가 공통코드에 없었다(AUD-09).
--  · 보존 기간이 지난 감사 기록을 옮길 곳이 없었다(AUD-11).
--  기획: 09 보안 감사 로그 4.5 V{next}__audit_log_integrity.sql (공통 묶음 M-4 의 3단계 몫).
--  변경 차단 트리거 함수는 V51 이 만들었다. 이 파일은 그 함수를 아카이브 표에 건다.
--
--  [이 파일이 하는 일]
--   1. 컬럼 — tb_log_audit.user_agent, tb_sys_perm_log.audit_id, tb_sys_login_hist.dept_nm (모두 NULL 허용)
--      과거 행은 비워 둔다(감사 기록은 고치지 않음).
--   2. 인덱스 3개 — 권한 변경 이력의 감사 행 연결, 로그인 이력 시각 역순, 감사 로그 부서별
--   3. 공통코드 — LOG_AUDIT_RESULT/MASKED, LOG_AUDIT_TYPE/EXPORT · CONFIG_CHANGE · ACCOUNT_SEC(V48 과 공유) ·
--      ACCESS_DENIED · AUDIT_VIEW. UNMASK_REQ 는 지우지 않고 「(제거됨)」 표시 · 맨 뒤 정렬
--   4. 아카이브 표 3개 — 원본과 같은 컬럼 + archived_at. 원본 키를 그대로 옮겨 담는다(identity 복사 안 함)
--   5. 아카이브 표에 변경 차단 트리거(V51 함수 재사용)
--
--  [기록 방식 주의 — API 개발]
--  tb_sys_perm_log.audit_id 는 INSERT 때 넣는다. 감사 표는 V51 트리거로 UPDATE 가 막혀 있어
--  나중에 UPDATE 로 채울 수 없다.
--
--  [순서]
--  V51 → 이 파일 → API 배포(새 컬럼 · 새 유형 기록). API 보다 늦게 적용하면 새 컬럼을 넣는 기록이 실패한다.
--
--  [실행]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f V58__audit_log_integrity.sql
--  두 번 실행해도 안전하다. 되돌리기는 rollback/V58__down.sql.
-- =====================================================================================

BEGIN;

-- ── 0. 선행 확인 ─────────────────────────────────────────────────────────────────────
DO $$
BEGIN
    IF to_regprocedure('ax.fn_block_audit_mutation()') IS NULL OR to_regprocedure('ax.fn_block_audit_truncate()') IS NULL THEN
        RAISE EXCEPTION 'V58 중단: 변경 차단 트리거 함수가 없습니다. V51 을 먼저 적용하십시오.';
    END IF;
END $$;

-- ── 1. 컬럼 ──────────────────────────────────────────────────────────────────────────
ALTER TABLE ax.tb_log_audit      ADD COLUMN IF NOT EXISTS user_agent varchar(300);
ALTER TABLE ax.tb_sys_perm_log   ADD COLUMN IF NOT EXISTS audit_id   bigint;
ALTER TABLE ax.tb_sys_login_hist ADD COLUMN IF NOT EXISTS dept_nm    varchar(50);

COMMENT ON COLUMN ax.tb_log_audit.user_agent IS
  '행위자 접속 브라우저 정보(300자). 로그인 이력 user_agent 와 같은 뜻. 2026-10 이전 기록은 NULL';
COMMENT ON COLUMN ax.tb_sys_perm_log.audit_id IS
  '같은 사건의 보안 감사 로그 행(ax.tb_log_audit.audit_id, 논리 참조). 채워진 행은 보안 감사 로그 화면에서 감사 로그 한 줄로만 보임. 2026-10 이전 기록은 NULL';
COMMENT ON COLUMN ax.tb_sys_login_hist.dept_nm IS
  '시도 당시 소속 부서명 스냅샷. 계정이 없거나 2026-10 이전 기록이면 NULL';
COMMENT ON COLUMN ax.tb_log_audit.plant_cd IS
  '대상 사업부 코드. 2026-10 기준 기록하는 곳 없음 — 되살릴 여지로 남김';

-- ── 2. 인덱스 ────────────────────────────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS ix_sys_perm_log_audit ON ax.tb_sys_perm_log (audit_id) WHERE audit_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_sys_login_at_desc  ON ax.tb_sys_login_hist (login_at DESC);
CREATE INDEX IF NOT EXISTS ix_log_audit_dept     ON ax.tb_log_audit (dept_nm, log_at DESC);

-- ── 3. 공통코드 ──────────────────────────────────────────────────────────────────────
INSERT INTO ax.tb_sys_code (group_cd, code, code_nm, sort_seq, use_flg, ins_user, upd_user) VALUES
  ('LOG_AUDIT_RESULT', 'MASKED',        '마스킹 후 제공',   4, 'Y', 'V58', 'V58'),
  ('LOG_AUDIT_TYPE',   'EXPORT',        '내려받기 · 인쇄',  7, 'Y', 'V58', 'V58'),
  ('LOG_AUDIT_TYPE',   'CONFIG_CHANGE', '설정 변경',        8, 'Y', 'V58', 'V58'),
  ('LOG_AUDIT_TYPE',   'ACCOUNT_SEC',   '계정 보안',        9, 'Y', 'V58', 'V58'),
  ('LOG_AUDIT_TYPE',   'ACCESS_DENIED', '접근 거부',       10, 'Y', 'V58', 'V58'),
  ('LOG_AUDIT_TYPE',   'AUDIT_VIEW',    '감사 기록 조회',  11, 'Y', 'V58', 'V58')
ON CONFLICT (group_cd, code) DO UPDATE
   SET use_flg = 'Y', upd_date = now(), upd_user = 'V58'
 WHERE ax.tb_sys_code.use_flg = 'N';

UPDATE ax.tb_sys_code
   SET code_nm = '마스킹 해제 요청 (제거됨)', sort_seq = 90, upd_date = now(), upd_user = 'V58'
 WHERE group_cd = 'LOG_AUDIT_TYPE' AND code = 'UNMASK_REQ'
   AND (code_nm, sort_seq) IS DISTINCT FROM ('마스킹 해제 요청 (제거됨)', 90::smallint);

-- ── 4. 아카이브 표 ───────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ax.tb_log_audit_arch      (LIKE ax.tb_log_audit      INCLUDING DEFAULTS INCLUDING COMMENTS);
CREATE TABLE IF NOT EXISTS ax.tb_sys_perm_log_arch   (LIKE ax.tb_sys_perm_log   INCLUDING DEFAULTS INCLUDING COMMENTS);
CREATE TABLE IF NOT EXISTS ax.tb_sys_login_hist_arch (LIKE ax.tb_sys_login_hist INCLUDING DEFAULTS INCLUDING COMMENTS);
ALTER TABLE ax.tb_log_audit_arch      ADD COLUMN IF NOT EXISTS archived_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE ax.tb_sys_perm_log_arch   ADD COLUMN IF NOT EXISTS archived_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE ax.tb_sys_login_hist_arch ADD COLUMN IF NOT EXISTS archived_at timestamptz NOT NULL DEFAULT now();

COMMENT ON TABLE ax.tb_log_audit_arch IS
  '보안 감사 로그 아카이브 — 보존 기간(app.audit-retention-years)이 지난 행을 옮겨 둠. 쌓기만 하고 고치지 않음';
COMMENT ON TABLE ax.tb_sys_perm_log_arch IS
  '권한 변경 이력 아카이브 — 보존 기간이 지난 행을 옮겨 둠. 쌓기만 하고 고치지 않음';
COMMENT ON TABLE ax.tb_sys_login_hist_arch IS
  '로그인 이력 아카이브 — 보존 기간이 지난 행을 옮겨 둠. 쌓기만 하고 고치지 않음';
COMMENT ON COLUMN ax.tb_log_audit_arch.archived_at      IS '아카이브 표로 옮긴 시각';
COMMENT ON COLUMN ax.tb_sys_perm_log_arch.archived_at   IS '아카이브 표로 옮긴 시각';
COMMENT ON COLUMN ax.tb_sys_login_hist_arch.archived_at IS '아카이브 표로 옮긴 시각';

-- ── 5. 아카이브 표 변경 차단 ─────────────────────────────────────────────────────────
DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['tb_log_audit_arch', 'tb_sys_perm_log_arch', 'tb_sys_login_hist_arch'] LOOP
        EXECUTE format('DROP TRIGGER IF EXISTS tg_%1$s_immutable ON ax.%1$s', t);
        EXECUTE format('CREATE TRIGGER tg_%1$s_immutable BEFORE UPDATE OR DELETE ON ax.%1$s
                        FOR EACH ROW EXECUTE FUNCTION ax.fn_block_audit_mutation()', t);
        EXECUTE format('DROP TRIGGER IF EXISTS tg_%1$s_no_truncate ON ax.%1$s', t);
        EXECUTE format('CREATE TRIGGER tg_%1$s_no_truncate BEFORE TRUNCATE ON ax.%1$s
                        FOR EACH STATEMENT EXECUTE FUNCTION ax.fn_block_audit_truncate()', t);
    END LOOP;
END $$;

COMMIT;
