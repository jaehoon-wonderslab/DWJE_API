-- =====================================================================================
--  V71 : 자연어 질의 이력 — LLM 응답 정보(모델 · 종료 사유 · 토큰 수 · 호출 시간 · 응답 ID) (2026-10-03)
--
--  [배경]
--  덕반장 AI 가 부르는 LLM(OpenAI 호환 vLLM) 응답에는 답변 말고도 화면에 보일 만한 값이 함께 온다
--  — model, choices[0].finish_reason, usage(prompt · completion · total tokens), id(chatcmpl-…).
--  지금은 버려져 질의 이력에서 어떤 모델이 얼마나 써서 답했는지, 답이 길이 제한에서 잘렸는지 알 수 없었다.
--  사용자 요청(2026-10-03)으로 저장하고 질의 이력 목록에 내보낸다. 스트리밍 응답은
--  stream_options.include_usage 를 보내야 마지막 조각에 usage 가 온다(API 몫).
--
--  [이 파일이 하는 일]
--  ax.tb_ai_chat_log 에 컬럼 7개(모두 NULL 허용) — 기존 행은 채우지 않는다(NULL = 이 기록 이전 · LLM 미호출).
--
--  [순서]
--  새 API 는 이 컬럼이 없으면 저장을 건너뛰므로 API 먼저 · 이 파일 나중이어도 된다. V70 뒤에 적용한다.
--
--  [실행]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f V71__chat_log_llm_meta.sql
--  두 번 실행해도 안전하다. 되돌리기는 rollback/V71__down.sql.
-- =====================================================================================

BEGIN;

ALTER TABLE ax.tb_ai_chat_log
    ADD COLUMN IF NOT EXISTS llm_model         varchar(100),
    ADD COLUMN IF NOT EXISTS llm_finish_reason varchar(30),
    ADD COLUMN IF NOT EXISTS prompt_tokens     integer,
    ADD COLUMN IF NOT EXISTS completion_tokens integer,
    ADD COLUMN IF NOT EXISTS total_tokens      integer,
    ADD COLUMN IF NOT EXISTS llm_ms            integer,
    ADD COLUMN IF NOT EXISTS llm_request_id    varchar(100);

COMMENT ON COLUMN ax.tb_ai_chat_log.llm_model IS
  '답변을 만든 LLM 모델 이름(LLM 응답의 model, 예: dwje-ax). LLM 을 부르지 않았거나 이 기록 이전이면 NULL';
COMMENT ON COLUMN ax.tb_ai_chat_log.llm_finish_reason IS
  'LLM 생성 종료 사유(응답의 choices[0].finish_reason) — stop=정상 종료, length=최대 길이에서 잘림, tool_calls 등. NULL = 미호출 · 이 기록 이전';
COMMENT ON COLUMN ax.tb_ai_chat_log.prompt_tokens IS
  'LLM 입력 토큰 수(응답의 usage.prompt_tokens). 질문 · 근거 · 시스템 지시를 합친 길이';
COMMENT ON COLUMN ax.tb_ai_chat_log.completion_tokens IS
  'LLM 출력 토큰 수(응답의 usage.completion_tokens)';
COMMENT ON COLUMN ax.tb_ai_chat_log.total_tokens IS
  'LLM 전체 토큰 수(응답의 usage.total_tokens = 입력 + 출력)';
COMMENT ON COLUMN ax.tb_ai_chat_log.llm_ms IS
  'LLM 호출 시간(ms). 근거 수집은 ask_ms, 전체는 response_ms';
COMMENT ON COLUMN ax.tb_ai_chat_log.llm_request_id IS
  'LLM 응답 ID(응답의 id, chatcmpl-…). LLM 서버 로그와 대조할 때 사용. 전사 자연어 질의 이력 화면에만 표시';

COMMIT;
