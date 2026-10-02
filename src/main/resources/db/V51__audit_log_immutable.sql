-- =====================================================================================
--  V51 : 감사 기록 위변조 방지 트리거 (2026-10-01)
--
--  [배경]
--  보안 감사 로그 · 권한 변경 이력 · 로그인 이력은 DB 계정만 있으면 UPDATE · DELETE · TRUNCATE 로
--  고치거나 지울 수 있었다. 감사 기록은 쌓기만 하는 표(append-only)로 두어야 한다.
--  기획: 09 보안 감사 로그 AUD-04 (공통 묶음 M-4 중 1단계 몫 — 트리거만)
--
--  [이 파일이 하는 일]
--   1. ax.fn_block_audit_mutation()  — 행 단위 UPDATE · DELETE 거부. 예외 두 가지
--        · 로그인 이력의 로그아웃 시각 채우기(logout_at NULL → 값, 다른 컬럼은 그대로) — 로그아웃 API 가 씀
--        · 세션 설정 ax.audit_purge = on 인 DELETE — 보존 기간 경과분 아카이브 배치 전용
--   2. ax.fn_block_audit_truncate()  — TRUNCATE 거부
--   3. 트리거 — ax.tb_log_audit · ax.tb_sys_perm_log · ax.tb_sys_login_hist
--
--  [하지 않는 일]
--  아카이브 표 · user_agent · audit_id · dept_nm 컬럼 · 감사 유형 공통코드는 3단계 마이그레이션 몫이다.
--  아카이브 표가 생기면 그 마이그레이션이 같은 함수로 트리거를 더 건다.
--
--  [적용 전 확인 — 2026-10-01]
--  API 코드에서 세 표를 UPDATE · DELETE 하는 곳은 AuthRepository.updateLogout(로그아웃 시각 채우기)
--  한 곳뿐이고, 위 예외로 허용된다. 이관 엔진 · 알림 엔진은 세 표에 쓰지 않는다.
--  나중에 기록 행을 고치는 코드(예: 감사 행 연결 번호를 UPDATE 로 채우기)를 넣으면 이 트리거가 막는다.
--  INSERT 시점에 값을 다 넣도록 만든다.
--
--  [실행]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f V51__audit_log_immutable.sql
--  두 번 실행해도 안전하다. 되돌리기는 rollback/V51__down.sql.
--  보존 기간 정리 배치: BEGIN; SET LOCAL ax.audit_purge = 'on'; DELETE ...; COMMIT;
-- =====================================================================================

BEGIN;

CREATE OR REPLACE FUNCTION ax.fn_block_audit_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' AND current_setting('ax.audit_purge', true) = 'on' THEN
        RETURN OLD;                                   -- 아카이브 배치만 허용
    END IF;
    -- PL/pgSQL 은 AND 를 단락 평가하지 않으므로 표 이름을 먼저 따로 거른다
    -- (다른 표에서 OLD.logout_at 을 읽으면 「필드 없음」 오류가 난다)
    IF TG_TABLE_NAME = 'tb_sys_login_hist' AND TG_OP = 'UPDATE' THEN
        IF (to_jsonb(OLD) ->> 'logout_at') IS NULL AND (to_jsonb(NEW) ->> 'logout_at') IS NOT NULL
           AND (to_jsonb(NEW) - 'logout_at') = (to_jsonb(OLD) - 'logout_at') THEN
            RETURN NEW;                               -- 로그아웃 시각 채우기만 허용
        END IF;
    END IF;
    RAISE EXCEPTION 'E-AUDIT-IMMUTABLE: %.% 는 고칠 수 없는 감사 기록입니다 (%).', TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'insufficient_privilege';
END $$;

CREATE OR REPLACE FUNCTION ax.fn_block_audit_truncate() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'E-AUDIT-IMMUTABLE: %.% 는 TRUNCATE 할 수 없는 감사 기록입니다.', TG_TABLE_SCHEMA, TG_TABLE_NAME
        USING ERRCODE = 'insufficient_privilege';
END $$;

COMMENT ON FUNCTION ax.fn_block_audit_mutation() IS
  '감사 기록 표의 UPDATE·DELETE 거부 트리거 함수. 로그인 이력의 로그아웃 시각 채우기와 ax.audit_purge = on 인 아카이브 삭제만 허용';
COMMENT ON FUNCTION ax.fn_block_audit_truncate() IS
  '감사 기록 표의 TRUNCATE 거부 트리거 함수';

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['tb_log_audit', 'tb_sys_perm_log', 'tb_sys_login_hist'] LOOP
        EXECUTE format('DROP TRIGGER IF EXISTS tg_%1$s_immutable ON ax.%1$s', t);
        EXECUTE format('CREATE TRIGGER tg_%1$s_immutable BEFORE UPDATE OR DELETE ON ax.%1$s
                        FOR EACH ROW EXECUTE FUNCTION ax.fn_block_audit_mutation()', t);
        EXECUTE format('DROP TRIGGER IF EXISTS tg_%1$s_no_truncate ON ax.%1$s', t);
        EXECUTE format('CREATE TRIGGER tg_%1$s_no_truncate BEFORE TRUNCATE ON ax.%1$s
                        FOR EACH STATEMENT EXECUTE FUNCTION ax.fn_block_audit_truncate()', t);
    END LOOP;
END $$;

COMMIT;
