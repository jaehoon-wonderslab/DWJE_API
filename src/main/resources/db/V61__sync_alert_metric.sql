-- =====================================================================================
--  V61 : 데이터 연동 이관 알림 지표 2건과 수집 정의 (2026-10-01)
--
--  [배경]
--  이관 실패 알림 기능(데이터 연동 이력 화면의 실패 알림)에 쓸 지표가 없었다. 실패 알림은
--  이상 알림 발송 조건이 맡도록 지표 기준과 수집 정의를 넣는다.
--  기획: 12 데이터 연동 이력 4.5 의 3 · 5 · SYN-04 (공통 묶음 M-9 의 3단계 몫). 기준값은 기능명세서 값.
--
--  [이 파일이 하는 일]
--   1. ax.tb_met_metric_std — SYNC_FAIL_RATE(이관 실패율 %), SYNC_STALE_MIN(이관 지연 분)
--      알림에만 쓰고 대시보드 · 보고서에는 쓰지 않음
--   2. ax.tb_met_metric_collect — 내장 수집기(BUILTIN, collector_cd = metric_cd), 5분 간격 · 60분 구간,
--      use_flg = 'N'. 알림 엔진에 수집기 2종(SyncFailRateCollector · SyncStaleCollector)이 배포된 뒤 켠다
--   3. tb_sync_job.retry_cnt 주석 정정 — 알림은 지표 조건이 담당
--
--  [하지 않는 일]
--  기본 발송 조건 시드 — 수신 그룹이 환경마다 달라 화면에서 등록한다(12 기획서 8장 Q2 권장안 ①).
--
--  [실행]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f V61__sync_alert_metric.sql
--  두 번 실행해도 안전하다. 되돌리기는 rollback/V61__down.sql.
-- =====================================================================================

BEGIN;

INSERT INTO ax.tb_met_metric_std
    (metric_cd, metric_nm, cat_cd, unit_cd, std_val, warn_val, crit_val, window_cd, calc_base,
     apply_alert, apply_dashboard, apply_report, ins_user, upd_user)
VALUES
 ('SYNC_FAIL_RATE', '이관 실패율', 'COLLECT', 'PCT', 0.5, 2.0, 5.0, 'BATCH',
  '(실패 테이블 작업 + 점검 실패 실행 × 사용 매핑 수) ÷ (전체 테이블 작업 + 점검 실패 실행 × 사용 매핑 수) × 100',
  true, false, false, 'V61', 'V61'),
 ('SYNC_STALE_MIN', '이관 지연 시간', 'COLLECT', 'MIN', 10, 30, 120, 'IMMEDIATE',
  '마지막 정상 이관(DONE · RETRY_DONE 종료 시각) 이후 경과 분',
  true, false, false, 'V61', 'V61')
ON CONFLICT (metric_cd) DO NOTHING;

INSERT INTO ax.tb_met_metric_collect
    (metric_id, collect_mode_cd, collector_cd, dim_cd, interval_sec, lookback_min, use_flg, ins_user, upd_user)
SELECT m.metric_id, 'BUILTIN', m.metric_cd, 'NONE', 300, 60, 'N', 'V61', 'V61'
  FROM ax.tb_met_metric_std m
 WHERE m.metric_cd IN ('SYNC_FAIL_RATE', 'SYNC_STALE_MIN')
ON CONFLICT (metric_id) DO NOTHING;

COMMENT ON COLUMN ax.tb_sync_job.retry_cnt IS
  '자동 재시도 횟수. 소진 시 실패 확정. 실패 알림은 지표 SYNC_FAIL_RATE · SYNC_STALE_MIN 의 이상 알림 발송 조건이 담당';

COMMIT;
