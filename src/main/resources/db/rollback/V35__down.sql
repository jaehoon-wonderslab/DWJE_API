-- =====================================================================================
--  V35 되돌리기 — 이상 알림 발송 엔진 기반 취소 (2026-09-16)
--
--  V35__alm_engine.sql 이 더한 것을 그대로 되돌린다.
--  표 5개 · 컬럼 6+4개 · 공통코드 그룹 6 · 코드 23 · 코드 참조 9행 · ALM 코드 attr1/attr2.
--
--  [지우는 데이터]
--  · ax.tb_alm_cond_state  — 조건별 평가 상태 전부. 되돌린 뒤 엔진을 다시 켜면 모든 조건이
--    "방금 처음 본 것"으로 판정된다. 연속 위반 중이던 것은 연속이 끊기고, 중복 억제 창도 풀린다
--    (억제 창 안이던 알림이 다시 나갈 수 있다).
--  · ax.tb_alm_send_queue  — 아직 못 보낸 발송 대기 건 전부. 보내지 못한 채 사라진다.
--    되돌리기 전에 PENDING/FAIL 이 남았는지 반드시 본다:
--        SELECT state_cd, count(*) FROM ax.tb_alm_send_queue GROUP BY 1;
--  · ax.tb_alm_eval_run    — 엔진 실행 이력 전부
--  · ax.tb_met_metric_collect — 지표 수집 정의
--  · ax.tb_alm_cond_target — 조건의 개별 대상 선택(PICK) 목록
--  · tb_alm_cond 의 평가 설정 6컬럼, tb_alm_alert 의 hit_cnt 등 4컬럼 값
--
--  [건드리지 않는 것]
--  이미 발생한 알림(tb_alm_alert)과 발송 로그(tb_alm_send_log) 의 행은 지우지 않는다.
--  엔진이 만든 것이라도 그것은 "실제로 있었던 일" 이다. hit_cnt · resolved_at 컬럼만 사라진다.
--  ax.tb_met_metric_value 에 쌓인 실측치도 남는다 — 대시보드가 함께 쓰는 값이다.
--
--  [ALM 코드의 attr1/attr2]
--  V35 가 채운 값(M30→30, C10M→600 등)을 지운다. 이 값은 V35 이전에 전부 비어 있었다.
--  운영에서 누가 조정해 둔 값이 있다면 그것도 함께 지워지므로, 아까우면 먼저 떠 둔다 —
--      SELECT group_cd, code, attr1, attr2 FROM ax.tb_sys_code
--       WHERE group_cd IN ('ALM_DEDUP','ALM_DURATION','ALM_OP','ALM_WINDOW');
--
--  [적용]
--  docker exec -e PGPASSWORD=dwje_local dwje-pg psql -U dwje_local -d dwjedb -v ON_ERROR_STOP=1 -f rollback/V35__down.sql
--  두 번 실행해도 안전하다.
-- =====================================================================================

-- 1. 코드 참조를 먼저 지운다 (그룹을 참조하고 있다) -------------------------------------------
DELETE FROM ax.tb_sys_code_ref
 WHERE (target_table, target_column) IN (
        ('tb_alm_cond',           'scope_dim_cd'),
        ('tb_alm_cond_target',    'target_dim_cd'),
        ('tb_alm_cond_state',     'state_cd'),
        ('tb_alm_send_queue',     'state_cd'),
        ('tb_alm_send_queue',     'channel_cd'),
        ('tb_alm_eval_run',       'state_cd'),
        ('tb_alm_eval_run',       'triggered_by_cd'),
        ('tb_met_metric_collect', 'collect_mode_cd'),
        ('tb_met_metric_collect', 'dim_cd'));

-- 2. 신규 표 제거 (참조 방향 반대 순서) ------------------------------------------------------
DROP TABLE IF EXISTS ax.tb_met_metric_collect;
DROP TABLE IF EXISTS ax.tb_alm_eval_run;
DROP TABLE IF EXISTS ax.tb_alm_send_queue;
DROP TABLE IF EXISTS ax.tb_alm_cond_state;
DROP TABLE IF EXISTS ax.tb_alm_cond_target;

-- 3. 공통코드 (코드 → 그룹 순서) -------------------------------------------------------------
DELETE FROM ax.tb_sys_code
 WHERE group_cd IN ('MET_COLLECT_MODE', 'ALM_SCOPE_DIM', 'ALM_COND_STATE',
                    'ALM_QUEUE_STATE', 'ALM_RUN_STATE', 'ALM_RUN_TRIGGER');
DELETE FROM ax.tb_sys_code_group
 WHERE group_cd IN ('MET_COLLECT_MODE', 'ALM_SCOPE_DIM', 'ALM_COND_STATE',
                    'ALM_QUEUE_STATE', 'ALM_RUN_STATE', 'ALM_RUN_TRIGGER');

-- 4. 기존 ALM 코드의 attr1/attr2 를 V35 이전(빈 값)으로 --------------------------------------
UPDATE ax.tb_sys_code
   SET attr1 = NULL, attr2 = NULL, upd_date = now(), upd_user = 'V35-down'
 WHERE group_cd IN ('ALM_DEDUP', 'ALM_DURATION', 'ALM_OP', 'ALM_WINDOW');

-- 5. 컬럼 제거 -------------------------------------------------------------------------
ALTER TABLE ax.tb_alm_alert
  DROP COLUMN IF EXISTS scope_key,
  DROP COLUMN IF EXISTS hit_cnt,
  DROP COLUMN IF EXISTS last_hit_at,
  DROP COLUMN IF EXISTS resolved_at;

ALTER TABLE ax.tb_alm_cond
  DROP COLUMN IF EXISTS scope_dim_cd,
  DROP COLUMN IF EXISTS window_time,
  DROP COLUMN IF EXISTS eval_interval_sec,
  DROP COLUMN IF EXISTS ignore_window_flg,
  DROP COLUMN IF EXISTS auto_close_flg,
  DROP COLUMN IF EXISTS last_eval_at;
