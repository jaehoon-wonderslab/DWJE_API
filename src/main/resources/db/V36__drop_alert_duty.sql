-- =====================================================================================
--  V36 : 당번 · 대리 수신 표 정리 — ax.tb_alm_duty (2026-09-16)
--
--  [배경]
--  [시스템관리 > 알림 수신자 관리(SY-05)] 에서 「당번 · 승격」 탭을 걷어낸다 (2026-09-16 WEB 요청).
--  당번은 이 화면에서만 쓰던 기능이라 표까지 함께 정리한다.
--
--  [쓰는 곳이 없다 — 확인한 것]
--   · API : AlertConfigRepository 의 findDuties · insertDuty · updateDuty · deleteDuty 를 지웠다.
--           수신자 관리 요약(No.157)의 activeDutyCnt 도 같이 뺐다. 남은 언급 0건.
--   · WEB : 화면 탭 · 컨트롤러 · systemRepository · systemService · endpoints · 목 핸들러 전부 제거.
--   · DB  : 들어오는 FK 0 · 뷰 0 · 함수 0 · 트리거 0. 로컬 행수 0.
--
--  [번호]
--  처음에 V35 로 만들었는데, 같은 날 다른 작업(이상 알림 발송 엔진)이 V35__alm_engine.sql 을
--  가져가 V36 으로 옮겼다. 두 작업은 서로 겹치지 않는다 — 저쪽은 표를 더하고 이쪽은 tb_alm_duty
--  하나를 지운다. 적용 순서도 상관없다.
--
--  [승격 규칙 표는 지우지 않는다]
--  ax.tb_alm_escalation_rule 은 [알림 현황] 의 「승격 대상」(GET /alerts/escalation-targets) 이
--  그대로 읽는다. 수신자 관리 화면에서만 걷어내고 표·조회 API 는 살려 둔다.
--
--  [함께 지우는 것]
--  공통코드 그룹 ALM_DUTY_REASON(코드 6) 과 코드 참조 1행. 이 그룹을 쓰는 다른 표는 없다.
--  참조 행을 표보다 먼저 지운다 — ax.fn_check_code_ref() 가 tb_sys_code_ref 의 모든 행에
--  `FROM ax.<target_table>` 을 동적 실행하므로 참조가 남으면 설치 후 점검 질의가 터진다.
--
--  [남는 부모 표]
--  나가는 FK 3개(ax.tb_alm_recip_group · ax.tb_sys_user ×2)는 부모 쪽이라 그대로 남는다.
--
--  [되돌리기]
--  구조·공통코드는 rollback/V36__down.sql. 업무 데이터는 적용 전 덤프에서 되돌린다 —
--  로컬은 0행이지만 운영에는 등록된 당번이 있을 수 있다. 반드시 먼저 덤프한다.
--      pg_dump -U <user> -d <db> --no-owner --no-privileges -t ax.tb_alm_duty > alm_duty_backup.sql
--
--  [적용]
--  docker exec -e PGPASSWORD=dwje_local dwje-pg psql -U dwje_local -d dwjedb -v ON_ERROR_STOP=1 -f V36__drop_alert_duty.sql
--  두 번 실행해도 안전하다. 운영 DB 는 요청자 확인 뒤에 적용한다.
-- =====================================================================================

-- 1. 공통코드 참조 정리 (반드시 DROP TABLE 보다 먼저) ------------------------------------
DELETE FROM ax.tb_sys_code_ref
 WHERE target_table = 'tb_alm_duty' AND target_column = 'reason_cd';

-- 2. 표 제거 --------------------------------------------------------------------------
DROP TABLE IF EXISTS ax.tb_alm_duty;

-- 3. 고아가 된 공통코드 정리 ------------------------------------------------------------
DELETE FROM ax.tb_sys_code       WHERE group_cd = 'ALM_DUTY_REASON';
DELETE FROM ax.tb_sys_code_group WHERE group_cd = 'ALM_DUTY_REASON';
