-- =====================================================================================
--  V9 : 설비 가동률 지표 기준 등록
--
--  DashboardProcessRepository.findAverageUptime() 및 가동률 히트맵/제품별 가동률은
--  ax.tb_met_metric_std 에 metric_cd = 'EQPT_UPTIME_RATE' 가 등록되어 있어야 동작한다.
--  기준정보가 없으면 지표 값을 적재할 대상 자체가 없어 해당 위젯이 영구히 비게 되므로,
--  코드가 참조하는 지표 코드를 기준정보로 함께 배포한다.
--
--  임계값(85 / 75 / 65)은 초기값이며 시스템관리 > 지표 기준정보 화면에서 조정한다.
-- =====================================================================================

INSERT INTO ax.tb_met_metric_std (
    metric_cd, metric_nm, cat_cd, unit_cd,
    std_val, warn_val, crit_val, window_cd, calc_base,
    apply_alert, apply_dashboard, apply_report, use_flg, ins_user, upd_user
) VALUES (
    'EQPT_UPTIME_RATE', '설비 가동률', 'PROD', 'PCT',
    85, 75, 65, 'DAY', '가동시간 ÷ 조업시간 × 100',
    TRUE, TRUE, FALSE, 'Y', 'SEED', 'SEED'
)
ON CONFLICT (metric_cd) DO NOTHING;
