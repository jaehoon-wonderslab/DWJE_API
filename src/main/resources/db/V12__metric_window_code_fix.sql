-- =====================================================================================
--  V12 : 지표 기준의 집계 구간 코드 교정
--
--  [배경]
--  ax.tb_met_metric_std.window_cd 에 공통코드 그룹 MET_WINDOW 에 없는 값이 들어가 있었다.
--
--    metric_id=5  EQPT_UPTIME_RATE  window_cd = 'DAY'
--
--  MET_WINDOW 의 허용 값은 IMMEDIATE · MA5 · MA10 · MA120 · DAY_CLOSE · DAY_AVG ·
--  MONTH_SUM · BATCH 8종이다. 'DAY' 는 그 집합 밖이라 화면이 표시명을 찾지 못하고
--  코드를 그대로 노출했다(지표 측정 데이터 관리 화면의 '구간' 열).
--
--  [교정 값 — DAY_AVG]
--  'DAY' 는 "일 단위" 로 읽히는데 후보가 DAY_CLOSE(일 마감)와 DAY_AVG(일 평균) 둘이다.
--  실제 산출 방식을 보고 DAY_AVG 로 정했다 — 가동률은 하루 여러 번 측정되고,
--  조회 쿼리가 그 값을 평균한다.
--
--    round(avg(mv.metric_value), 2) AS uptime_rate
--      ... FROM ax.tb_met_metric_value mv WHERE ms.metric_cd = 'EQPT_UPTIME_RATE'
--          AND mv.measured_at >= :dayStart AND mv.measured_at < :dayEnd
--    (DashboardAiRepository.findLines · findEquipmentUptimeHeatmap 등)
--
--  일 마감 시점의 단일 값이 아니라 일 평균이므로 DAY_AVG 가 맞다.
--  업무 기준이 '일 마감' 이라면 DAY_CLOSE 로 바꾸면 된다 — 이 값은 표시·설명용이고
--  집계 로직에는 쓰이지 않으므로 값만 바꿔도 동작에 영향이 없다.
--
--  [재발 방지]
--  PUT /api/v1/metrics/standards/{stdId} 가 window 를 MET_WINDOW 로 검증한다.
--  코드 집합 밖의 값은 400 + error.field='window' 로 막힌다.
--
--  [영향]
--  지표 측정 데이터 관리 화면의 '구간' 열이 '일 평균' 으로 표시된다.
-- =====================================================================================

UPDATE ax.tb_met_metric_std
   SET window_cd = 'DAY_AVG',
       upd_date  = now()
 WHERE window_cd = 'DAY'
   AND NOT EXISTS (
       SELECT 1 FROM ax.tb_sys_code
        WHERE group_cd = 'MET_WINDOW' AND code = 'DAY' AND use_flg = 'Y'
   );
