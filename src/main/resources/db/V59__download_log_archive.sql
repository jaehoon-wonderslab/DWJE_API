-- =====================================================================================
--  V59 : 보고서 다운로드 이력 아카이브 표 (2026-10-01)
--
--  [배경]
--  보존 기간이 지난 다운로드 이력을 옮길 곳이 없었다. 감사 기록 아카이브(V58)와 같은 방식으로 둔다.
--  기획: 10 보고서 다운로드 이력 4.5 · DLG-07 (공통 묶음 M-4 의 3단계 몫).
--
--  [이 파일이 하는 일]
--   1. ax.tb_rpt_download_log_arch · ax.tb_rpt_download_blind_arch — 원본과 같은 컬럼 + archived_at
--      (V53 컬럼이 들어간 뒤의 구조. 원본 키를 그대로 옮겨 담는다 — identity · FK 는 복사하지 않음)
--   2. 아카이브 표 두 개에 변경 차단 트리거(V51 함수 재사용)
--
--  [하지 않는 일 — V62 로 분리]
--  원본 표(tb_rpt_download_log · tb_rpt_download_blind)의 변경 차단 트리거는 V62 에 따로 두었다.
--  API 테스트가 원본 표를 DELETE 로 정리하던 것을 고친 뒤 적용하도록 나눈 것이다.
--
--  [실행]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f V59__download_log_archive.sql
--  두 번 실행해도 안전하다. 되돌리기는 rollback/V59__down.sql.
-- =====================================================================================

BEGIN;

DO $$
BEGIN
    IF to_regprocedure('ax.fn_block_audit_mutation()') IS NULL OR to_regprocedure('ax.fn_block_audit_truncate()') IS NULL THEN
        RAISE EXCEPTION 'V59 중단: 변경 차단 트리거 함수가 없습니다. V51 을 먼저 적용하십시오.';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'ax' AND table_name = 'tb_rpt_download_log' AND column_name = 'scope_cd') THEN
        RAISE EXCEPTION 'V59 중단: 다운로드 이력에 V53 컬럼이 없습니다. V53 을 먼저 적용하십시오.';
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS ax.tb_rpt_download_log_arch   (LIKE ax.tb_rpt_download_log   INCLUDING DEFAULTS INCLUDING COMMENTS);
CREATE TABLE IF NOT EXISTS ax.tb_rpt_download_blind_arch (LIKE ax.tb_rpt_download_blind INCLUDING DEFAULTS INCLUDING COMMENTS);
ALTER TABLE ax.tb_rpt_download_log_arch   ADD COLUMN IF NOT EXISTS archived_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE ax.tb_rpt_download_blind_arch ADD COLUMN IF NOT EXISTS archived_at timestamptz NOT NULL DEFAULT now();

COMMENT ON TABLE ax.tb_rpt_download_log_arch IS
  '보고서 다운로드 이력 아카이브 — 보존 기간(app.download-retention-years)이 지난 행을 옮겨 둠. 쌓기만 하고 고치지 않음';
COMMENT ON TABLE ax.tb_rpt_download_blind_arch IS
  '다운로드 비공개 항목 내역 아카이브 — 아카이브된 다운로드 이력의 항목별 비공개 셀 수. 쌓기만 하고 고치지 않음';
COMMENT ON COLUMN ax.tb_rpt_download_log_arch.archived_at   IS '아카이브 표로 옮긴 시각';
COMMENT ON COLUMN ax.tb_rpt_download_blind_arch.archived_at IS '아카이브 표로 옮긴 시각';

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['tb_rpt_download_log_arch', 'tb_rpt_download_blind_arch'] LOOP
        EXECUTE format('DROP TRIGGER IF EXISTS tg_%1$s_immutable ON ax.%1$s', t);
        EXECUTE format('CREATE TRIGGER tg_%1$s_immutable BEFORE UPDATE OR DELETE ON ax.%1$s
                        FOR EACH ROW EXECUTE FUNCTION ax.fn_block_audit_mutation()', t);
        EXECUTE format('DROP TRIGGER IF EXISTS tg_%1$s_no_truncate ON ax.%1$s', t);
        EXECUTE format('CREATE TRIGGER tg_%1$s_no_truncate BEFORE TRUNCATE ON ax.%1$s
                        FOR EACH STATEMENT EXECUTE FUNCTION ax.fn_block_audit_truncate()', t);
    END LOOP;
END $$;

COMMIT;
