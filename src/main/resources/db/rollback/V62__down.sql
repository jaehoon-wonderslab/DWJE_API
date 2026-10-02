-- =====================================================================================
--  V62 되돌리기 — 다운로드 이력 원본 표 트리거 제거 (2026-10-01)
--
--  트리거 4개를 지운다. 기록 행과 V51 함수는 건드리지 않는다.
--
--  [적용]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f rollback/V62__down.sql
--  두 번 실행해도 안전하다.
-- =====================================================================================

BEGIN;

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['tb_rpt_download_log', 'tb_rpt_download_blind'] LOOP
        EXECUTE format('DROP TRIGGER IF EXISTS tg_%1$s_immutable ON ax.%1$s', t);
        EXECUTE format('DROP TRIGGER IF EXISTS tg_%1$s_no_truncate ON ax.%1$s', t);
    END LOOP;
END $$;

COMMIT;
