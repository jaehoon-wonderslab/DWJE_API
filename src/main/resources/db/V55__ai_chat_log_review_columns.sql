-- =====================================================================================
--  V55 : 자연어 질의 이력 — 관리자 검토 · 평가 의견 · 가림 기준 · 응답 시간 분리 · 세션 인덱스 (2026-10-01)
--
--  [배경]
--  · 질의 이력을 전 부서가 열람하게 되면서(결정 R-08), 질의자보다 권한이 좁은 열람자에게 응답을 가릴
--    기준(질의 시점에 질의자에게 가려진 항목)이 저장되지 않았다 → blind_field_keys.
--  · 관리자가 남의 질의를 평가할 곳이 없었다(rating_cd 는 질의자 본인만) → review_* 4개.
--  · 질의자 평가의 시각 · 의견, 근거 수집 구간 소요 시간이 없었다 → rated_at · rating_comment · ask_ms.
--  · 세션별 조회(결정 R-12) · 부서별 조회 · 검토 건 조회용 인덱스.
--  기획: 08 자연어 질의 이력 4.5(나) — CHH-02 · 04 · 11 · 13 · 18 (공통 묶음 M-7).
--
--  [이 파일이 하는 일]
--   1. ax.tb_ai_chat_log 컬럼 8개(모두 NULL 허용) — 기존 행은 채우지 않는다
--      blind_field_keys 가 NULL 인 과거 이력은 조회 쪽이 보수적으로 가린다(08 기획서 8장 Q2 권장안 a)
--   2. 인덱스 3개 — 부서별, 검토 건, 세션별
--   3. 공통코드 참조 등록 — review_cd → AI_CHAT_RATING (fn_check_code_ref 대상)
--   4. 주석 — 새 컬럼과 evidence_summary · unanswered_reason(주석이 비어 있던 컬럼)
--
--  [순서]
--  이 파일 → API 배포. API 보다 늦게 적용하면 이력 INSERT 가 새 컬럼을 넣다가 실패해
--  덕반장 AI 질의 전체가 500 이 된다.
--
--  [실행]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f V55__ai_chat_log_review_columns.sql
--  두 번 실행해도 안전하다. 되돌리기는 rollback/V55__down.sql.
-- =====================================================================================

BEGIN;

-- ── 1. 컬럼 ──────────────────────────────────────────────────────────────────────────
ALTER TABLE ax.tb_ai_chat_log
    ADD COLUMN IF NOT EXISTS blind_field_keys text[],
    ADD COLUMN IF NOT EXISTS rated_at         timestamptz,
    ADD COLUMN IF NOT EXISTS rating_comment   varchar(500),
    ADD COLUMN IF NOT EXISTS review_cd        varchar(30),
    ADD COLUMN IF NOT EXISTS review_comment   varchar(500),
    ADD COLUMN IF NOT EXISTS reviewed_by      common.d_user_id,
    ADD COLUMN IF NOT EXISTS reviewed_at      timestamptz,
    ADD COLUMN IF NOT EXISTS ask_ms           integer;

-- ── 2. 인덱스 ────────────────────────────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS ix_ai_chat_dept    ON ax.tb_ai_chat_log (dept_nm, asked_at DESC);
CREATE INDEX IF NOT EXISTS ix_ai_chat_review  ON ax.tb_ai_chat_log (review_cd, asked_at DESC) WHERE review_cd IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_ai_chat_session ON ax.tb_ai_chat_log (session_id, asked_at) WHERE session_id IS NOT NULL;

-- ── 3. 공통코드 참조 ─────────────────────────────────────────────────────────────────
INSERT INTO ax.tb_sys_code_ref (target_table, target_column, group_cd, nullable_flg)
VALUES ('tb_ai_chat_log', 'review_cd', 'AI_CHAT_RATING', 'Y')
ON CONFLICT DO NOTHING;

-- ── 4. 주석 ──────────────────────────────────────────────────────────────────────────
COMMENT ON COLUMN ax.tb_ai_chat_log.evidence_summary IS
  '판단 근거 요약 — 근거 문서 제목과 DB 집계 문장(질의자 권한으로 가린 뒤). 자연어 질의 이력 화면의 「판단 근거」 칸';
COMMENT ON COLUMN ax.tb_ai_chat_log.unanswered_reason IS
  '미응답 사유 — 근거 없음 · 모델 응답 없음 등. 응답이 저장되면 NULL';
COMMENT ON COLUMN ax.tb_ai_chat_log.blind_field_keys IS
  '질의 시점에 질의자에게 가려진 데이터 항목 key. 열람자에게 이보다 더 가려지는 항목이 있으면 응답을 표시하지 않음. NULL = 이 기록 이전 이력(비관리자에게는 가림)';
COMMENT ON COLUMN ax.tb_ai_chat_log.rated_at IS
  '질의자 평가 시각 (rating_cd 와 함께 저장)';
COMMENT ON COLUMN ax.tb_ai_chat_log.rating_comment IS
  '질의자 평가 의견';
COMMENT ON COLUMN ax.tb_ai_chat_log.review_cd IS
  '관리자 검토 결과. 공통코드 AI_CHAT_RATING. 학습데이터 선정 시 질의자 평가(rating_cd)보다 우선';
COMMENT ON COLUMN ax.tb_ai_chat_log.review_comment IS
  '관리자 검토 의견';
COMMENT ON COLUMN ax.tb_ai_chat_log.reviewed_by IS
  '검토자 사번';
COMMENT ON COLUMN ax.tb_ai_chat_log.reviewed_at IS
  '검토 시각';
COMMENT ON COLUMN ax.tb_ai_chat_log.ask_ms IS
  '근거 수집 구간 소요 시간(ms). response_ms 는 근거 수집 + LLM 전체';

COMMIT;
