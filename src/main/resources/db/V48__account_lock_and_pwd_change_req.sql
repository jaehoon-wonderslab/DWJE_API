-- =====================================================================================
--  V48 : 계정 잠금 상태(LOCKED) · 잠금 해제 이메일 목적 · 초기 비밀번호 변경 요구 (2026-10-01)
--
--  [배경]
--  · 로그인 5회 실패 잠금이 관리자 정지(SUSPENDED)와 같은 상태로 저장돼 구분되지 않았다.
--    잠금은 본인 이메일 인증(ACCOUNT_UNLOCK) 또는 관리자 해제로 풀도록 상태를 나눈다(결정 R-02).
--  · 그룹웨어 자동 가입 계정은 비밀번호가 사번 그대로다. 비밀번호를 바꾸기 전에는 업무 API 를
--    막는다(결정 R-04). 서버가 판정할 표시 컬럼 pwd_change_req_yn 을 둔다.
--  · 계정별 권한 변경 이력 조회용 인덱스(계정 관리 화면 「변경 이력」).
--  기획: 09 보안 감사 로그 AUD-16, 01 계정 관리 ACC-03 · ACC-09 · ACC-12 (공통 묶음 M-13 + M-1)
--
--  [이 파일이 하는 일]
--   1. 공통코드 SYS_USER_STATE/LOCKED · EMAIL_VERIFY_PURPOSE/ACCOUNT_UNLOCK · LOG_AUDIT_TYPE/ACCOUNT_SEC
--   2. 백필 — SUSPENDED 이면서 login_fail_cnt >= 5 인 계정을 LOCKED 로 (LOCKED 코드를 처음 켤 때만)
--   3. ax.tb_sys_user.pwd_change_req_yn (기본 Y) — 컬럼이 없을 때만 추가하고 백필
--      · 그룹웨어 자동 가입(ins_user = SYSTEM, remark '그룹웨어 자동 가입…')이면서 비밀번호를
--        한 번도 바꾸지 않은 계정만 Y, 나머지(관리자 등록·회원가입·로컬 시드)는 N
--      · 기본값을 Y 로 두는 까닭: 이관 엔진의 자동 가입 INSERT 가 이 컬럼을 모름
--   4. 인덱스 2개 — 이메일 인증 요청의 대상 계정 조회, 권한 변경 이력의 계정별 조회
--   5. 주석 정정
--
--  [순서]
--  이 파일 → API 배포(로그인 실패 기록 분리 · 잠금 · 초기 비밀번호 차단) → WEB 배포.
--  API 보다 늦게 적용하면 /auth/login · /auth/me 가 pwd_change_req_yn 을 읽다가 500 이 된다.
--
--  [실행]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f V48__account_lock_and_pwd_change_req.sql
--  두 번 실행해도 안전하다. 백필 두 가지는 첫 적용 때만 돈다(다시 돌려도 관리자가 바꾼 값을 덮지 않음).
--  되돌리기는 rollback/V48__down.sql.
-- =====================================================================================

BEGIN;

-- ── 1·2. 공통코드와 LOCKED 백필 ─────────────────────────────────────────────────────
DO $$
DECLARE
    v_first boolean;
    v_moved integer := 0;
BEGIN
    -- LOCKED 코드가 없거나 되돌리기로 사용 중지된 상태면 첫 적용으로 본다
    v_first := NOT EXISTS (SELECT 1 FROM ax.tb_sys_code
                            WHERE group_cd = 'SYS_USER_STATE' AND code = 'LOCKED' AND use_flg = 'Y');

    INSERT INTO ax.tb_sys_code (group_cd, code, code_nm, code_desc, sort_seq, use_flg, ins_user, upd_user) VALUES
      ('SYS_USER_STATE',       'LOCKED',         '잠김',
       '비밀번호 연속 실패로 시스템이 잠금. 본인 이메일 인증 또는 관리자 해제로 ACTIVE', 4, 'Y', 'V48', 'V48'),
      ('EMAIL_VERIFY_PURPOSE', 'ACCOUNT_UNLOCK', '계정 잠금 해제',
       '잠긴 계정의 본인 확인. 대상 계정(target_user_id) 필수', 3, 'Y', 'V48', 'V48'),
      ('LOG_AUDIT_TYPE',       'ACCOUNT_SEC',    '계정 보안', NULL, 9, 'Y', 'V48', 'V48')
    ON CONFLICT (group_cd, code) DO UPDATE
       SET use_flg = 'Y', upd_date = now(), upd_user = 'V48'
     WHERE ax.tb_sys_code.use_flg = 'N';

    IF v_first THEN
        -- 실서버는 적용 전에 건수를 확인한다:
        --   SELECT user_id FROM ax.tb_sys_user WHERE user_state_cd = 'SUSPENDED' AND login_fail_cnt >= 5;
        WITH moved AS (
            UPDATE ax.tb_sys_user
               SET user_state_cd = 'LOCKED', upd_date = now(), upd_user = 'V48'
             WHERE user_state_cd = 'SUSPENDED' AND login_fail_cnt >= 5
            RETURNING user_id, user_nm
        ), logged AS (
            INSERT INTO ax.tb_sys_perm_log (log_at, act_cd, target_kind_cd, target_user_id, target_nm, detail,
                                            actor_user_id, actor_dept_nm)
            SELECT now(), 'ACCOUNT', 'USER', user_id, coalesce(user_nm, user_id),
                   '계정 상태 변경 SUSPENDED → LOCKED (V48 백필: 연속 실패 잠금을 정지와 분리)', 'SYSTEM', NULL
              FROM moved
            RETURNING 1
        )
        SELECT count(*) INTO v_moved FROM logged;
        RAISE NOTICE 'V48: SUSPENDED → LOCKED 백필 % 건', v_moved;
    END IF;
END $$;

-- ── 3. 초기 비밀번호 변경 요구 ───────────────────────────────────────────────────────
DO $$
DECLARE
    v_y integer;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'ax' AND table_name = 'tb_sys_user' AND column_name = 'pwd_change_req_yn') THEN
        ALTER TABLE ax.tb_sys_user ADD COLUMN pwd_change_req_yn common.d_yn NOT NULL DEFAULT 'Y';

        UPDATE ax.tb_sys_user
           SET pwd_change_req_yn = CASE
                   WHEN ins_user = 'SYSTEM'
                    AND remark LIKE '그룹웨어 자동 가입%'
                    AND (pwd_upd_at IS NULL OR pwd_upd_at <= ins_date + interval '1 minute') THEN 'Y'
                   ELSE 'N' END;

        SELECT count(*) INTO v_y FROM ax.tb_sys_user WHERE pwd_change_req_yn = 'Y';
        RAISE NOTICE 'V48: pwd_change_req_yn 추가 — Y % 건', v_y;
    END IF;
END $$;

-- ── 4. 인덱스 ────────────────────────────────────────────────────────────────────────
-- 잠금 해제 2단계는 이메일이 아니라 사번으로 인증 요청을 찾는다
CREATE INDEX IF NOT EXISTS ix_tb_sys_email_verify_target
    ON ax.tb_sys_email_verify (target_user_id, purpose_cd, ins_date DESC) WHERE target_user_id IS NOT NULL;
-- 계정 관리 화면의 계정별 변경 이력
CREATE INDEX IF NOT EXISTS ix_sys_perm_log_user
    ON ax.tb_sys_perm_log (target_user_id, log_at DESC);

-- ── 5. 주석 ──────────────────────────────────────────────────────────────────────────
COMMENT ON COLUMN ax.tb_sys_user.pwd_change_req_yn IS
  '비밀번호 변경 요구 (Y/N). Y 면 로그인은 되지만 비밀번호를 바꾸기 전까지 업무 API 를 막음. '
  '자동 가입·관리자 등록·관리자 비밀번호 변경·관리자 잠금 해제 시 Y, 본인 변경·재설정·회원가입 시 N. '
  '기본값 Y 는 이관 엔진의 자동 가입 INSERT 가 이 컬럼을 모르기 때문';
COMMENT ON COLUMN ax.tb_sys_user.user_state_cd IS
  '계정 상태. 공통코드 그룹 = SYS_USER_STATE (ACTIVE=사용, SUSPENDED=정지(관리자·퇴사), PENDING=승인 대기, '
  'LOCKED=잠김(비밀번호 연속 실패, 본인 이메일 인증 또는 관리자 해제)). ACTIVE 만 로그인';
COMMENT ON COLUMN ax.tb_sys_user.login_fail_cnt IS
  '연속 로그인 실패 횟수. 상한(기본 5)에 닿으면 LOCKED 로 잠김. 성공·잠금 해제 시 0 으로 복귀. '
  '계정 관리 화면 계정 표의 「로그인 실패」 열';
COMMENT ON COLUMN ax.tb_sys_user.pwd_upd_at IS
  '비밀번호를 마지막으로 바꾼 시각. 변경 요구 여부는 pwd_change_req_yn';
COMMENT ON COLUMN ax.tb_sys_email_verify.purpose_cd IS
  '인증 목적 (EMAIL_VERIFY_PURPOSE) — SIGNUP / PASSWORD_RESET / ACCOUNT_UNLOCK';
COMMENT ON COLUMN ax.tb_sys_perm_log.act_cd IS
  '변경 구분. 공통코드 그룹 = SYS_PERM_ACT (ACCOUNT=계정, DEPT=부서, MENU_PERM=메뉴 권한, DATA_PERM=데이터 권한, '
  'USER_MENU_PERM=계정 추가 메뉴 권한, GW_DEPT_MAP=그룹웨어 부서 매핑)';
COMMENT ON COLUMN ax.tb_sys_dept.dept_abbr IS
  '부서 약칭 — 화면 배지 표기, 최대 4자';
COMMENT ON COLUMN ax.tb_sys_dept.is_super_admin IS
  'true = 통합관리자. 권한 판정은 권한 행과 무관하게 전 화면·전 데이터 항목 허용. 남아 있는 권한 행은 판정에 쓰지 않음';

COMMIT;
