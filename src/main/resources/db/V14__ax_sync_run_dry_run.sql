-- =====================================================================================
--  V14 : ax.tb_sync_run 에 모의 실행(DRY-RUN) 구분 추가
--
--  배경
--    dry-run 은 대상 검사만 하고 아무것도 반영하지 않는데, 이력에는
--      state_cd = 'DONE', target_cnt = 13, success_cnt = 13, ok_rows = 0
--    으로 남았다. SY-15 화면에서 운영자가 "13개 테이블 이관 완료" 로 읽는다.
--    `ok_rows = 0` 이 단서지만 이관할 것이 없어 0 인 경우와 구분되지 않는다
--    (PREFLIGHT_FAIL 도 0 이다).
--
--  왜 별도 컬럼인가
--    모드(SCHEDULED·MANUAL·QUEUE·RETRY)와 상태(DONE·FAIL·…) 두 축 모두와 직교한다.
--    특히 상태 축에 얹으면 dry-run 중 프리플라이트가 실패했을 때 상태가 PREFLIGHT_FAIL 이
--    되면서 모의 실행이었다는 사실이 사라진다 — 실패한 모의 실행을 진짜 실패와 구분할 수 없다.
--
--  적용 : psql -U <계정> -d <DB> -f V14__ax_sync_run_dry_run.sql
--  멱등 : 여러 번 적용해도 안전하다
-- =====================================================================================

ALTER TABLE ax.tb_sync_run
    ADD COLUMN IF NOT EXISTS is_dry_run boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN ax.tb_sync_run.is_dry_run IS
    '모의 실행 여부. true 면 대상만 확인하고 아무것도 반영하지 않았다 (--dry-run)';

-- 이 컬럼이 생기기 전에 쌓인 dry-run 이력 보정.
--   options_desc 부분 문자열로 찾는다. 이후로는 엔진이 컬럼에 직접 쓰므로
--   이 방식은 여기서 한 번만 쓴다. 문구가 바뀌면 이 보정만 못 잡을 뿐,
--   앞으로의 기록에는 영향이 없다.
UPDATE ax.tb_sync_run
   SET is_dry_run = true
 WHERE NOT is_dry_run
   AND options_desc LIKE '%DRY-RUN%';
