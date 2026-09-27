-- 질문 원문, SQL, 인증정보를 저장하지 않는 ask 진단 메타데이터.
-- 실패한 ask에는 chat_id가 없을 수 있으므로 FK를 걸지 않는다.
CREATE TABLE IF NOT EXISTS ax.tb_ai_chat_debug (
    request_id uuid PRIMARY KEY,
    chat_id bigint,
    user_id common.d_user_id NOT NULL,
    asked_at timestamptz NOT NULL DEFAULT now(),
    route_cd varchar(40) NOT NULL,
    parse_cd varchar(30) NOT NULL,
    tool_cd varchar(60),
    execute_cd varchar(30) NOT NULL,
    error_cd varchar(40),
    period_from date,
    period_to date,
    row_cnt integer NOT NULL DEFAULT 0,
    doc_hit_cnt integer NOT NULL DEFAULT 0,
    tool_ms integer NOT NULL DEFAULT 0,
    total_ms integer NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS ix_ai_chat_debug_chat ON ax.tb_ai_chat_debug (chat_id) WHERE chat_id IS NOT NULL;
