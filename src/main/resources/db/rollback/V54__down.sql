-- =====================================================================================
--  V54 되돌리기 — 알림 테스트 구분 · 주석 (2026-10-01)
--
--  · 부분 인덱스와 test_flg 컬럼을 지운다. 테스트 알림은 다시 운영 알림과 구분되지 않는다.
--    지우기 전에 테스트 알림을 보관하려면: SELECT * FROM ax.tb_alm_alert WHERE test_flg = 'Y';
--  · 대상 범위 이관은 되돌리지 않는다(코드 → 표기명 교정이며, 되돌리면 알림 제목에 코드가 다시 찍힘).
--  · 주석은 V54 직전 문구로 돌린다.
--
--  [API 를 먼저 내린다]
--  새 API 는 test_flg 를 읽는다. 컬럼이 사라지면 알림 목록 · 요약이 500 이 된다.
--
--  [적용]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f rollback/V54__down.sql
--  두 번 실행해도 안전하다.
-- =====================================================================================

BEGIN;

DROP INDEX IF EXISTS ax.ix_alm_alert_cond_real;
ALTER TABLE ax.tb_alm_alert DROP COLUMN IF EXISTS test_flg;

COMMENT ON COLUMN ax.tb_alm_cond.msg_template IS
  '메시지 템플릿. 치환자 {심각도}{조건명}{대상}{지표}{임계값}. 단가·수율 등 민감정보는 본문에 포함하지 않음';
COMMENT ON COLUMN ax.tb_alm_cond.target_scope_cd IS
  '대상 범위 구분. 공통코드 그룹 = ALM_TARGET (ALL_EQPT=전체 설비, PRESS=프레스 전체, AOI=AOI 전체, ALL_MODEL=전체 모델, ALL_PROC=전 공정, ALL_CUST=전체 고객사, MATERIAL=주요 소재, PICK=개별 설비 선택)';
COMMENT ON COLUMN ax.tb_alm_cond.target_desc IS
  '대상 설명 문구. 평가 단위가 NONE 인 알림의 제목·본문에 대상으로 쓰임';
COMMENT ON COLUMN ax.tb_alm_recipient.recv_state_cd IS
  '수신 상태. 공통코드 그룹 = ALM_RECV_STATE (RECV=수신, ABSENT=부재). 부재면 당번·대리 규칙에 따라 대리 수신자에게 발송';
COMMENT ON COLUMN ax.tb_alm_recipient.night_recv IS
  'true 면 이 수신자는 야간에도 수신. false 면 야간 건을 SKIPPED 로 남기고 보내지 않음';
COMMENT ON COLUMN ax.tb_alm_recip_group.group_nm IS
  '그룹 이름. 발송 조건은 이 이름만 참조하며 중복될 수 없음';
COMMENT ON COLUMN ax.tb_alm_recip_group.window_cd IS
  '수신 시간대. 공통코드 그룹 = ALM_WINDOW (ALWAYS=24시간 상시, D0820=08:00~20:00, D0618=06:00~18:00, WORKDAY=주간 근무일만, ONCE=지정 시각 1회)';
COMMENT ON COLUMN ax.tb_alm_send_queue.is_proxy IS
  '당직 대리 수신 여부. 원래 수신자가 부재(tb_alm_recipient.recv_state_cd)일 때 tb_alm_duty 로 대신 받는 경우';
COMMENT ON COLUMN ax.tb_alm_send_log.is_proxy IS
  'true = 담당 부재로 대리 수신자에게 발송된 건';

COMMIT;
