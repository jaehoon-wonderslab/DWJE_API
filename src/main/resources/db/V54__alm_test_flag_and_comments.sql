-- =====================================================================================
--  V54 : 이상 알림 — 테스트 구분 · 대상 범위 이관 · 알림 표 주석 정정 (2026-10-01)
--
--  [배경]
--  · 발송 조건 · 수신 그룹의 테스트 발송이 운영 알림과 같은 표에 섞여 알림 목록·요약·조건 삭제 판정에
--    들어갔다. 테스트 발송을 실제 발송 경로로 바꾸면서(ALC-03 · RCP-03) 테스트 여부를 컬럼으로 남긴다.
--  · 대상 범위가 target_desc(문구)에 코드로 들어간 조건이 있으면 코드 컬럼으로 옮기고 문구는 표기명으로.
--  · 알림 표 주석이 당번 제거 · 치환자 문법 변경 이전 내용이었다.
--  기획: 05 이상 알림 발송 조건 4.5.1 (ALC-03 · ALC-05), 06 알림 수신자 4.5.1 (공통 묶음 M-5).
--  쓰기 권한 이관(alert-cond · sys-recip)은 V49 에 이미 들어 있다.
--
--  [이 파일이 하는 일]
--   1. ax.tb_alm_alert.test_flg (d_yn, 기본 N) + 기존 테스트 알림 백필(제목 「[테스트]」 · 근거 「테스트 발송」)
--   2. 부분 인덱스 ix_alm_alert_cond_real — 조건별 운영 알림(삭제 판정 · 최근 집계)
--   3. 대상 범위 이관 — target_desc 가 ALM_TARGET 코드와 같은 조건을 코드 컬럼 + 표기명으로
--   4. 주석 정정 — tb_alm_cond 3 · tb_alm_recipient 2 · tb_alm_recip_group 2 · 대기열 · 발송 이력 is_proxy
--
--  [기획서 초안과 다른 점]
--  · 수신 그룹 window_cd 주석: 같은 날 알림 엔진이 그룹 시간대 판정을 구현했다(RCP-10, 06 기획서 8장
--    Q-01 권장안 ①). 엔진 새 빌드를 배포해야 실제로 적용된다 — 그 전에는 조건의 시간대만 판정한다.
--  · tb_alm_recipient.remark 주석: 저장 기능(수신/부재 전환 사유)은 후속 항목이라 이번에 바꾸지 않았다.
--
--  [순서]
--  이 파일 → API 배포(test_flg 를 읽고 씀) → WEB 배포. 알림 엔진은 변경 · 재기동 없음(기본값 N 으로 들어감).
--  API 보다 늦게 적용하면 test_flg 를 읽는 알림 목록 · 요약이 500 이 된다.
--
--  [실행]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f V54__alm_test_flag_and_comments.sql
--  두 번 실행해도 안전하다. 되돌리기는 rollback/V54__down.sql.
-- =====================================================================================

BEGIN;

-- ── 1. 테스트 알림 구분 ──────────────────────────────────────────────────────────────
ALTER TABLE ax.tb_alm_alert ADD COLUMN IF NOT EXISTS test_flg common.d_yn NOT NULL DEFAULT 'N';

DO $$
DECLARE
    v_cnt integer;
BEGIN
    -- 실서버는 적용 전에 건수를 확인한다:
    --   SELECT count(*) FROM ax.tb_alm_alert WHERE evidence_desc = '테스트 발송' AND title LIKE '[테스트]%';
    UPDATE ax.tb_alm_alert
       SET test_flg = 'Y'
     WHERE test_flg = 'N'
       AND evidence_desc = '테스트 발송'
       AND title LIKE '[테스트]%';
    GET DIAGNOSTICS v_cnt = ROW_COUNT;
    RAISE NOTICE 'V54: 테스트 알림 백필 % 건', v_cnt;
END $$;

-- ── 2. 운영 알림 부분 인덱스 ─────────────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS ix_alm_alert_cond_real
    ON ax.tb_alm_alert (cond_id, occurred_at DESC)
 WHERE test_flg = 'N';

-- ── 3. 대상 범위 이관 ────────────────────────────────────────────────────────────────
DO $$
DECLARE
    v_cnt integer;
BEGIN
    UPDATE ax.tb_alm_cond c
       SET target_scope_cd = sc.code,
           target_desc     = sc.code_nm,
           upd_date        = now(),
           upd_user        = 'V54'
      FROM ax.tb_sys_code sc
     WHERE sc.group_cd = 'ALM_TARGET'
       AND c.target_desc = sc.code;
    GET DIAGNOSTICS v_cnt = ROW_COUNT;
    RAISE NOTICE 'V54: 대상 범위 이관 % 건', v_cnt;
END $$;

-- ── 4. 주석 ──────────────────────────────────────────────────────────────────────────
COMMENT ON COLUMN ax.tb_alm_alert.test_flg IS
  '테스트 발송 여부 (Y/N). Y 면 발송 조건 · 수신 그룹의 테스트 발송이 만든 알림. 알림 목록 기본 조회 · 요약 통계 · 조건 삭제 판정에서 제외';

COMMENT ON COLUMN ax.tb_alm_cond.msg_template IS
  '메시지 틀. 치환자는 {{변수}} 꼴 — severity · condNm · scope · eqptNm · target · metricNm · metricDesc · value · unit · op · threshold · evidence · occurredAt · link. '
  '정의하지 않은 변수는 글자 그대로 남음. 단가 · 수율 등 민감정보는 넣지 않음';
COMMENT ON COLUMN ax.tb_alm_cond.target_scope_cd IS
  '대상 범위 구분. 공통코드 ALM_TARGET. 알림 엔진은 PICK(개별 설비 선택, tb_alm_cond_target)과 그 밖(전체)만 구분해 판정';
COMMENT ON COLUMN ax.tb_alm_cond.target_desc IS
  '대상 설명 문구(사람이 읽는 말). 비우면 ALM_TARGET 표기명. 평가 단위가 NONE 인 알림의 제목 · 본문 대상으로 쓰임. 코드값을 넣지 않음';

COMMENT ON COLUMN ax.tb_alm_recipient.recv_state_cd IS
  '수신 상태. 공통코드 ALM_RECV_STATE (RECV=수신, ABSENT=부재). 부재면 발송 대상에서 제외(대리 수신 없음 — 당번 표 제거 이후)';
COMMENT ON COLUMN ax.tb_alm_recipient.night_recv IS
  'true 면 야간(알림 엔진 설정 alert.night, 기본 22:00~06:00)에도 받음. 소속 그룹의 night_recv 가 true 여도 받음(그룹 OR 개인)';
COMMENT ON COLUMN ax.tb_alm_recip_group.group_nm IS
  '그룹 이름(유일). 발송 조건 · 승격 규칙은 group_id 로 참조하므로 이름을 바꿔도 연결 유지';
COMMENT ON COLUMN ax.tb_alm_recip_group.window_cd IS
  '그룹 수신 시간대. 공통코드 ALM_WINDOW. 밖이면 이 그룹을 거친 발송을 SKIPPED 로 남김(일반 발송 · 승격 모두). 조건의 ignore_window_flg=Y 면 무시하되 개인 야간 미수신은 계속 지킴';
COMMENT ON COLUMN ax.tb_alm_send_queue.is_proxy IS
  '대리 수신 여부. 당번 표가 빠진 뒤로는 항상 false';
COMMENT ON COLUMN ax.tb_alm_send_log.is_proxy IS
  'true = 담당 부재로 대리 수신자에게 발송된 건. 당번 표가 빠진 뒤로는 항상 false';

COMMIT;
