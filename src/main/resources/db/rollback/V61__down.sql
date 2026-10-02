-- =====================================================================================
--  V61 되돌리기 — 이관 알림 지표 2건 · 수집 정의 제거 (2026-10-01)
--
--  · 발송 조건 · 알림 · 지표 값이 이 지표를 참조하면 아무것도 바꾸지 않고 중단한다.
--    조건을 먼저 정리한 뒤 실행한다.
--  · 수집 정의는 지표 삭제 때 FK CASCADE 로 함께 지워진다.
--  · retry_cnt 주석은 V61 직전 문구로.
--
--  [적용]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f rollback/V61__down.sql
--  두 번 실행해도 안전하다.
-- =====================================================================================

BEGIN;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM ax.tb_alm_cond c JOIN ax.tb_met_metric_std m ON m.metric_id = c.metric_id
                WHERE m.metric_cd IN ('SYNC_FAIL_RATE', 'SYNC_STALE_MIN'))
       OR EXISTS (SELECT 1 FROM ax.tb_alm_alert a JOIN ax.tb_met_metric_std m ON m.metric_id = a.metric_id
                   WHERE m.metric_cd IN ('SYNC_FAIL_RATE', 'SYNC_STALE_MIN'))
       OR EXISTS (SELECT 1 FROM ax.tb_met_metric_value v JOIN ax.tb_met_metric_std m ON m.metric_id = v.metric_id
                   WHERE m.metric_cd IN ('SYNC_FAIL_RATE', 'SYNC_STALE_MIN')) THEN
        RAISE EXCEPTION 'V61 되돌리기 중단: 이관 알림 지표를 쓰는 발송 조건 · 알림 · 지표 값이 있습니다. 먼저 정리하십시오.';
    END IF;
END $$;

DELETE FROM ax.tb_met_metric_std WHERE metric_cd IN ('SYNC_FAIL_RATE', 'SYNC_STALE_MIN');

COMMENT ON COLUMN ax.tb_sync_job.retry_cnt IS
  '자동 재시도 횟수. 3회 초과 시 실패 확정하고 전산팀에 이상 알림을 발송';

COMMIT;
