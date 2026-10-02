-- =====================================================================================
--  V57 되돌리기 — 데이터 연동 재실행 연결 · 기간 인덱스 (2026-10-01)
--
--  · 인덱스 2개와 retry_of_job_id 컬럼을 지운다. 재실행 연결은 돌아오지 않는다.
--  · SYNC_RUN_MODE/GROUPWARE 코드는 지우지 않는다. 이관 엔진 SQL 도 같은 코드를 넣고,
--    그룹웨어 동기화 실행 이력이 이 코드를 쓴다.
--
--  [API 를 먼저 내린다]
--  새 API 는 retry_of_job_id 를 읽고 쓴다.
--
--  [적용]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f rollback/V57__down.sql
--  두 번 실행해도 안전하다.
-- =====================================================================================

BEGIN;

DROP INDEX IF EXISTS ax.ix_sync_job_eff_at;
DROP INDEX IF EXISTS ax.ix_sync_job_retry_of;
ALTER TABLE ax.tb_sync_job DROP COLUMN IF EXISTS retry_of_job_id;

COMMIT;
