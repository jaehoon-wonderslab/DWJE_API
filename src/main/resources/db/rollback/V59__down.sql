-- =====================================================================================
--  V59 되돌리기 — 다운로드 이력 아카이브 표 제거 (2026-10-01)
--
--  아카이브 표에 행이 있으면 아무것도 바꾸지 않고 중단한다(기록 유실 방지).
--  V62(원본 표 트리거)가 적용돼 있어도 이 파일과는 무관하다.
--
--  [적용]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f rollback/V59__down.sql
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
    IF pg_temp.has_rows('ax.tb_rpt_download_log_arch') OR pg_temp.has_rows('ax.tb_rpt_download_blind_arch') THEN
        RAISE EXCEPTION 'V59 되돌리기 중단: 다운로드 이력 아카이브 표에 행이 있습니다. 원본으로 되옮긴 뒤 다시 실행하십시오.';
    END IF;
END $$;

DROP TABLE IF EXISTS ax.tb_rpt_download_blind_arch, ax.tb_rpt_download_log_arch;

COMMIT;
