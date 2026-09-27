ALTER TABLE ax.tb_ai_chat_log
  ADD COLUMN IF NOT EXISTS evidence_summary text,
  ADD COLUMN IF NOT EXISTS unanswered_reason text;
