-- =====================================================================================
--  V71 되돌리기 — 질의 이력 LLM 응답 정보 컬럼 제거 (2026-10-03)
--
--  컬럼 7개를 지운다. 저장된 모델 · 토큰 수 · 호출 시간은 돌아오지 않는다.
--  새 API 는 컬럼이 없으면 저장을 건너뛰므로 API 를 먼저 내리지 않아도 된다(질의 이력 목록의 해당 값은 빈칸).
--
--  [적용]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f rollback/V71__down.sql
--  두 번 실행해도 안전하다.
-- =====================================================================================

BEGIN;

ALTER TABLE ax.tb_ai_chat_log
    DROP COLUMN IF EXISTS llm_request_id,
    DROP COLUMN IF EXISTS llm_ms,
    DROP COLUMN IF EXISTS total_tokens,
    DROP COLUMN IF EXISTS completion_tokens,
    DROP COLUMN IF EXISTS prompt_tokens,
    DROP COLUMN IF EXISTS llm_finish_reason,
    DROP COLUMN IF EXISTS llm_model;

COMMIT;
