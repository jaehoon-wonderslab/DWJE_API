-- =====================================================================================
--  V48 되돌리기 — 계정 잠금 상태 · 잠금 해제 이메일 목적 · 초기 비밀번호 변경 요구 (2026-10-01)
--
--  · LOCKED 계정은 예전 표현(SUSPENDED + 실패 횟수 그대로)으로 돌리고 권한 변경 이력에 남긴다.
--    실패 횟수를 그대로 두므로 예전 판정과 같아진다.
--  · pwd_change_req_yn 컬럼을 지운다. 적용 뒤 관리자·본인이 바꾼 값은 돌아오지 않는다.
--  · 공통코드는 쓰는 행이 없을 때만 지우고, 쓰는 행이 있으면 사용 중지(use_flg = N)로 둔다.
--    (ACCOUNT_UNLOCK 인증 요청·ACCOUNT_SEC 감사 행은 이력이라 지우지 않는다)
--  · 주석은 V48 직전 값으로 돌린다.
--
--  [API 를 먼저 내린다]
--  새 API 는 LOCKED 와 pwd_change_req_yn 을 쓴다. 되돌리기 전에 API 를 이전 버전으로 내린다.
--
--  [적용]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f rollback/V48__down.sql
--  두 번 실행해도 안전하다.
-- =====================================================================================

BEGIN;

-- ── LOCKED → SUSPENDED ──────────────────────────────────────────────────────────────
WITH moved AS (
    UPDATE ax.tb_sys_user
       SET user_state_cd = 'SUSPENDED', upd_date = now(), upd_user = 'V48-down'
     WHERE user_state_cd = 'LOCKED'
    RETURNING user_id, user_nm
)
INSERT INTO ax.tb_sys_perm_log (log_at, act_cd, target_kind_cd, target_user_id, target_nm, detail,
                                actor_user_id, actor_dept_nm)
SELECT now(), 'ACCOUNT', 'USER', user_id, coalesce(user_nm, user_id),
       '계정 상태 변경 LOCKED → SUSPENDED (V48 되돌리기)', 'SYSTEM', NULL
  FROM moved;

-- ── 인덱스 · 컬럼 ────────────────────────────────────────────────────────────────────
DROP INDEX IF EXISTS ax.ix_tb_sys_email_verify_target;
DROP INDEX IF EXISTS ax.ix_sys_perm_log_user;
ALTER TABLE ax.tb_sys_user DROP COLUMN IF EXISTS pwd_change_req_yn;

-- ── 공통코드 ─────────────────────────────────────────────────────────────────────────
DELETE FROM ax.tb_sys_code c
 WHERE c.group_cd = 'SYS_USER_STATE' AND c.code = 'LOCKED'
   AND NOT EXISTS (SELECT 1 FROM ax.tb_sys_user u WHERE u.user_state_cd = 'LOCKED');
DELETE FROM ax.tb_sys_code c
 WHERE c.group_cd = 'EMAIL_VERIFY_PURPOSE' AND c.code = 'ACCOUNT_UNLOCK'
   AND NOT EXISTS (SELECT 1 FROM ax.tb_sys_email_verify v WHERE v.purpose_cd = 'ACCOUNT_UNLOCK');
DELETE FROM ax.tb_sys_code c
 WHERE c.group_cd = 'LOG_AUDIT_TYPE' AND c.code = 'ACCOUNT_SEC'
   AND NOT EXISTS (SELECT 1 FROM ax.tb_log_audit a WHERE a.log_type_cd = 'ACCOUNT_SEC');
UPDATE ax.tb_sys_code
   SET use_flg = 'N', upd_date = now(), upd_user = 'V48-down'
 WHERE (group_cd, code) IN (('SYS_USER_STATE', 'LOCKED'), ('EMAIL_VERIFY_PURPOSE', 'ACCOUNT_UNLOCK'),
                            ('LOG_AUDIT_TYPE', 'ACCOUNT_SEC'))
   AND use_flg = 'Y';

-- ── 주석 (V48 직전 값) ───────────────────────────────────────────────────────────────
COMMENT ON COLUMN ax.tb_sys_user.user_state_cd IS
  '계정 상태. 공통코드 그룹 = SYS_USER_STATE (ACTIVE=사용, SUSPENDED=정지)';
COMMENT ON COLUMN ax.tb_sys_user.login_fail_cnt IS
  '연속 로그인 실패 횟수. 상한(기본 5)에 닿으면 잠기고 성공하면 0 으로 복귀. 계정 관리 화면 계정 표의 「로그인 실패」 열';
COMMENT ON COLUMN ax.tb_sys_user.pwd_upd_at IS
  '비밀번호를 마지막으로 바꾼 시각. 초기 비밀번호 여부 판단에 사용';
COMMENT ON COLUMN ax.tb_sys_email_verify.purpose_cd IS
  '인증 목적 (EMAIL_VERIFY_PURPOSE) — SIGNUP / PASSWORD_RESET';
COMMENT ON COLUMN ax.tb_sys_perm_log.act_cd IS
  '변경 구분. 공통코드 그룹 = SYS_PERM_ACT (ACCOUNT=계정, DEPT=부서, MENU_PERM=메뉴 권한, DATA_PERM=데이터 권한)';
COMMENT ON COLUMN ax.tb_sys_dept.dept_abbr IS
  '부서 약칭 — 화면 배지에 쓰는 2자 표기 (QA, PC, MF, IT, EX, MA)';
COMMENT ON COLUMN ax.tb_sys_dept.is_super_admin IS
  'true = 통합관리자. 전 화면·전 데이터 항목 접근으로 취급하며 개별 권한 행을 만들지 않음';

COMMIT;
