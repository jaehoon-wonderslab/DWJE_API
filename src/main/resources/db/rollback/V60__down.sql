-- =====================================================================================
--  V60 되돌리기 — 용어 사전 변경 이력 표 제거 (2026-10-01)
--
--  이력 표를 지운다. 쌓인 이력은 돌아오지 않으므로 행이 있으면 중단한다.
--  정말 지우려면 먼저 덤프한 뒤 표를 비우고 다시 실행한다:
--    pg_dump -U <user> -d <db> -t ax.tb_gls_change_log > gls_change_log_backup.sql
--
--  [API 를 먼저 내린다]
--  새 API 는 용어 사전 변경 때 이 표에 기록한다.
--
--  [적용]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f rollback/V60__down.sql
--  두 번 실행해도 안전하다.
-- =====================================================================================

BEGIN;

-- 표가 이미 없을 때도 돌도록 행 확인은 동적 SQL 로 한다(세션 임시 함수)
CREATE OR REPLACE FUNCTION pg_temp.has_rows(p_table text) RETURNS boolean LANGUAGE plpgsql AS $f$
DECLARE v boolean;
BEGIN
    IF to_regclass(p_table) IS NULL THEN RETURN false; END IF;
    EXECUTE format('SELECT EXISTS (SELECT 1 FROM %s)', p_table) INTO v;
    RETURN v;
END $f$;

DO $$
BEGIN
    IF pg_temp.has_rows('ax.tb_gls_change_log') THEN
        RAISE EXCEPTION 'V60 되돌리기 중단: 용어 사전 변경 이력에 행이 있습니다. 덤프하고 비운 뒤 다시 실행하십시오.';
    END IF;
END $$;

DROP TABLE IF EXISTS ax.tb_gls_change_log;

COMMIT;
