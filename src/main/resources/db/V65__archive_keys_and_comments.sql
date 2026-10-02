-- =====================================================================================
--  V65 : 아카이브 표 키 · 옮긴 시각 인덱스, 업로드 문서 ID · 감사 로그 미사용 컬럼 주석 (2026-10-01)
--
--  [배경 — 3단계 API 보고가 남긴 선택 사항 3건 + 09 AUD-15 주석]
--  · 다운로드 이력 아카이브 두 표에 키가 없어, 아카이브 배치가 같은 행을 두 번 옮겨도 막을 수 없었다.
--    배치는 비공개 내역(blind)을 먼저 복사한 뒤 원본 log 를 지우며 옮기므로 키가 중복 적재를 막는다.
--  · 보존 정책 응답이 아카이브 표마다 max(archived_at) 을 읽는다(마지막 아카이브 시각).
--  · 업로드 문서 목록 검색어가 숫자면 문서 ID 일치로 찾게 됐다(11 UPD-06). 주석이 옛 설명이었다.
--  · 감사 로그의 작업장 · LOT · 시리얼 · 품목 컬럼은 기록하는 곳이 없다(09 AUD-15). 지우지 않고 주석에 밝힌다
--    (사업부 plant_cd 는 V58 에서 같은 문구를 붙였다).
--
--  [이 파일이 하는 일]
--   1. PK — tb_rpt_download_log_arch (dl_id), tb_rpt_download_blind_arch (dl_id, field_key)
--      중복 행이 있으면 아무것도 바꾸지 않고 중단한다(감사 기록이라 지우지 않음).
--   2. 인덱스 — archived_at DESC, 보존 정책이 읽는 아카이브 표 4개(감사 로그 · 권한 변경 · 로그인 · 다운로드 log)
--   3. 주석 — tb_dash_upload_doc.doc_id, tb_log_audit 의 wc_cd · lot_no · serial_no · item_cd
--  PK · 인덱스 추가는 행을 고치지 않으므로 아카이브 표의 변경 차단 트리거와 충돌하지 않는다.
--
--  [실행]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f V65__archive_keys_and_comments.sql
--  두 번 실행해도 안전하다. 되돌리기는 rollback/V65__down.sql.
-- =====================================================================================

BEGIN;

-- ── 1. 다운로드 아카이브 키 ──────────────────────────────────────────────────────────
DO $$
BEGIN
    IF to_regclass('ax.tb_rpt_download_log_arch') IS NULL OR to_regclass('ax.tb_rpt_download_blind_arch') IS NULL THEN
        RAISE EXCEPTION 'V65 중단: 다운로드 아카이브 표가 없습니다. V59 를 먼저 적용하십시오.';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'pk_tb_rpt_download_log_arch') THEN
        IF EXISTS (SELECT 1 FROM ax.tb_rpt_download_log_arch GROUP BY dl_id HAVING count(*) > 1) THEN
            RAISE EXCEPTION 'V65 중단: tb_rpt_download_log_arch 에 같은 dl_id 가 두 번 이상 있습니다. 담당자와 확인하십시오.';
        END IF;
        ALTER TABLE ax.tb_rpt_download_log_arch
            ADD CONSTRAINT pk_tb_rpt_download_log_arch PRIMARY KEY (dl_id);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'pk_tb_rpt_download_blind_arch') THEN
        IF EXISTS (SELECT 1 FROM ax.tb_rpt_download_blind_arch GROUP BY dl_id, field_key HAVING count(*) > 1) THEN
            RAISE EXCEPTION 'V65 중단: tb_rpt_download_blind_arch 에 같은 (dl_id, field_key) 가 두 번 이상 있습니다. 담당자와 확인하십시오.';
        END IF;
        ALTER TABLE ax.tb_rpt_download_blind_arch
            ADD CONSTRAINT pk_tb_rpt_download_blind_arch PRIMARY KEY (dl_id, field_key);
    END IF;
END $$;

-- ── 2. 옮긴 시각 인덱스 ──────────────────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS ix_log_audit_arch_at      ON ax.tb_log_audit_arch      (archived_at DESC);
CREATE INDEX IF NOT EXISTS ix_sys_perm_log_arch_at   ON ax.tb_sys_perm_log_arch   (archived_at DESC);
CREATE INDEX IF NOT EXISTS ix_sys_login_hist_arch_at ON ax.tb_sys_login_hist_arch (archived_at DESC);
CREATE INDEX IF NOT EXISTS ix_rpt_dl_arch_at         ON ax.tb_rpt_download_log_arch (archived_at DESC);

-- ── 3. 주석 ──────────────────────────────────────────────────────────────────────────
COMMENT ON COLUMN ax.tb_dash_upload_doc.doc_id IS
  '업로드 문서 대리키. 업로드 문서 목록 화면 검색어가 숫자일 때 이 값과 일치 비교하며, 버전 이력 드로어 부제에 표시';
COMMENT ON COLUMN ax.tb_log_audit.wc_cd IS
  '대상 작업장 코드. 2026-10 기준 기록하는 곳 없음 — 되살릴 여지로 남김';
COMMENT ON COLUMN ax.tb_log_audit.lot_no IS
  '대상 LOT NO (mes.tb_pop_label_hist 와 조인 가능). 2026-10 기준 기록하는 곳 없음 — 되살릴 여지로 남김';
COMMENT ON COLUMN ax.tb_log_audit.serial_no IS
  '대상 LOT 의 시리얼 번호(lot_no 와 함께 라벨 이력을 가리킴). 2026-10 기준 기록하는 곳 없음 — 되살릴 여지로 남김';
COMMENT ON COLUMN ax.tb_log_audit.item_cd IS
  '대상 품목 코드. 2026-10 기준 기록하는 곳 없음 — 되살릴 여지로 남김';

COMMIT;
