-- =====================================================================================
--  V55 되돌리기 — 질의 이력 검토 · 평가 의견 · 가림 기준 · 응답 시간 · 인덱스 (2026-10-01)
--
--  · 컬럼 8개 · 인덱스 3개 · 공통코드 참조 1행을 지운다. 검토 결과 · 평가 의견은 돌아오지 않는다.
--    지우기 전에 보관하려면:
--      pg_dump -U <user> -d <db> -t ax.tb_ai_chat_log --data-only > ai_chat_log_backup.sql
--  · evidence_summary · unanswered_reason 주석은 V55 직전처럼 비운다.
--
--  [API 를 먼저 내린다]
--  새 API 는 이 컬럼에 기록한다. 컬럼이 사라지면 덕반장 AI 질의가 500 이 된다.
--
--  [적용]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f rollback/V55__down.sql
--  두 번 실행해도 안전하다.
-- =====================================================================================

BEGIN;

DROP INDEX IF EXISTS ax.ix_ai_chat_review;
DROP INDEX IF EXISTS ax.ix_ai_chat_session;
DROP INDEX IF EXISTS ax.ix_ai_chat_dept;

DELETE FROM ax.tb_sys_code_ref WHERE target_table = 'tb_ai_chat_log' AND target_column = 'review_cd';

ALTER TABLE ax.tb_ai_chat_log
    DROP COLUMN IF EXISTS ask_ms,
    DROP COLUMN IF EXISTS reviewed_at,
    DROP COLUMN IF EXISTS reviewed_by,
    DROP COLUMN IF EXISTS review_comment,
    DROP COLUMN IF EXISTS review_cd,
    DROP COLUMN IF EXISTS rating_comment,
    DROP COLUMN IF EXISTS rated_at,
    DROP COLUMN IF EXISTS blind_field_keys;

COMMENT ON COLUMN ax.tb_ai_chat_log.evidence_summary  IS NULL;
COMMENT ON COLUMN ax.tb_ai_chat_log.unanswered_reason IS NULL;

COMMIT;
