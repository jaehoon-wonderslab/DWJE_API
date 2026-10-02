-- =====================================================================================
--  V51 되돌리기 — 감사 기록 위변조 방지 트리거 제거 (2026-10-01)
--
--  트리거 6개와 함수 2개를 지운다. 감사 기록 행은 건드리지 않는다.
--  나중에 아카이브 표 트리거가 같은 함수를 쓰게 되면 그 마이그레이션을 먼저 되돌린다
--  (함수를 쓰는 트리거가 남아 있으면 DROP FUNCTION 이 실패해 전체가 취소된다).
--
--  [적용]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f rollback/V51__down.sql
--  두 번 실행해도 안전하다.
-- =====================================================================================

BEGIN;

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['tb_log_audit', 'tb_sys_perm_log', 'tb_sys_login_hist'] LOOP
        EXECUTE format('DROP TRIGGER IF EXISTS tg_%1$s_immutable ON ax.%1$s', t);
        EXECUTE format('DROP TRIGGER IF EXISTS tg_%1$s_no_truncate ON ax.%1$s', t);
    END LOOP;
END $$;

DROP FUNCTION IF EXISTS ax.fn_block_audit_mutation();
DROP FUNCTION IF EXISTS ax.fn_block_audit_truncate();

COMMIT;
