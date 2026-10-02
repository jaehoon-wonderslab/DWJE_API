-- =====================================================================================
--  V43 되돌리기 — 질의 이력 판단 근거 · 미응답 사유 컬럼 제거 (2026-10-01 작성)
--
--  V43 은 되돌리기 파일 없이 들어갔다. 08 자연어 질의 이력 기획 CHH-13 으로 짝을 맞춘다.
--  · ax.tb_ai_chat_log.evidence_summary · unanswered_reason 을 지운다. 값은 돌아오지 않는다.
--  · V55 가 적용돼 있으면 V55 를 먼저 되돌린다(같은 표의 주석을 V55 가 채움 — 순서가 바뀌어도
--    실패하지는 않지만 V55 되돌리기의 주석 정리가 의미 없어짐).
--
--  [API 를 먼저 내린다]
--  이 컬럼을 읽고 쓰는 API(덕반장 AI 질의 · 질의 이력)는 500 이 된다.
--
--  [적용]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f rollback/V43__down.sql
--  두 번 실행해도 안전하다.
-- =====================================================================================

BEGIN;

ALTER TABLE ax.tb_ai_chat_log
    DROP COLUMN IF EXISTS unanswered_reason,
    DROP COLUMN IF EXISTS evidence_summary;

COMMIT;
