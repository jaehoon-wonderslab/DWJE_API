-- =====================================================================================
--  V74 : 알림 수신자 「부재」 · 「야간 수신」 제거 — 컬럼 3개 삭제(값은 보관 표로) (2026-10-03)
--
--  [적용 순서 — 반드시 지킬 것]
--  알림 엔진 · API 새 코드(이 값들을 읽지도 쓰지도 않음) → WEB → 이 파일.
--  옛 엔진 · 옛 API 가 떠 있는 채로 적용하면 수신 대상 조회 SQL 이 없는 컬럼을 읽다가 실패한다
--  (엔진은 발송 대상 판정 중단, API 는 알림 수신자 관리 · 발송 조건 화면 500).
--
--  [없애는 것과 이후 고정 동작]
--    수신 상태   tb_alm_recipient.recv_state_cd (RECV/ABSENT) → 부재 없음. 수신 그룹의 멤버는 모두 받는다
--                (수신 가능 여부는 계정 상태 app.alert.receivable-user-states 로만 가린다)
--                API 수신/부재 전환(PATCH /alert-recipients/{id}/state)도 함께 뺐다.
--    개인 야간   tb_alm_recipient.night_recv   → 야간 제외 없음
--    그룹 야간   tb_alm_recip_group.night_recv → 야간 제외 없음. 시간에 따른 제외는 유효 시간대(window_cd)뿐
--    비고        tb_alm_recipient.remark 는 일반 비고로 남긴다(부재 사유로 쓰지 않음 — 주석만 바꿈)
--
--  [이 파일이 하는 일]
--   1. 보관 표 2개 — ax.tb_alm_recipient_absent_night_bak(수신자별 recv_state_cd · night_recv),
--      ax.tb_alm_recip_group_night_bak(그룹별 night_recv). 다시 실행하면 같은 키는 값을 새로 덮는다
--   2. 공통코드 참조 정합성 목록(tb_sys_code_ref)에서 tb_alm_recipient.recv_state_cd 행 삭제
--      (없는 컬럼을 검사하지 않게). vec.tb_code_ref 에는 이 컬럼 행이 없다(2026-10-03 로컬 확인).
--   3. 공통코드 그룹 ALM_RECV_STATE(RECV · ABSENT)는 지우지 않고 사용 중지(use_flg = 'N') —
--      되돌리기가 그대로 다시 켤 수 있게 행을 남긴다. 이 코드를 쓰는 다른 컬럼은 없다.
--   4. 주석 정정 — remark(일반 비고), tb_alm_recip_group.window_cd · tb_alm_eval_run.skip_cnt(야간 미수신 문구 삭제)
--   5. 컬럼 3개 삭제. 이 컬럼을 무는 뷰 · 함수 · 트리거 · 제약은 없다(2026-10-03 로컬 확인).
--  컬럼이 이미 없으면 1 의 보관은 건너뛴다.
--
--  [실행]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f V74__alm_recipient_absent_night_drop.sql
--  두 번 실행해도 안전하다. 되돌리기는 rollback/V74__down.sql(컬럼 3개를 되살리고 보관 표에서 값을 되돌림).
-- =====================================================================================

BEGIN;

-- ── 1. 보관 표 ───────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ax.tb_alm_recipient_absent_night_bak (
    user_id       varchar(30)  NOT NULL,
    recv_state_cd varchar(30),
    night_recv    boolean,
    backed_at     timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_tb_alm_recipient_absent_night_bak PRIMARY KEY (user_id)
);
CREATE TABLE IF NOT EXISTS ax.tb_alm_recip_group_night_bak (
    group_id   integer     NOT NULL,
    night_recv boolean,
    backed_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_tb_alm_recip_group_night_bak PRIMARY KEY (group_id)
);

COMMENT ON TABLE  ax.tb_alm_recipient_absent_night_bak               IS '알림 수신자 부재 · 야간 수신 보관 — 두 컬럼을 지울 때 남긴 수신자별 값. 되돌리기 스크립트가 이 값으로 컬럼을 되살림';
COMMENT ON COLUMN ax.tb_alm_recipient_absent_night_bak.user_id       IS '수신자 사번 (ax.tb_alm_recipient 논리 참조, FK 없음)';
COMMENT ON COLUMN ax.tb_alm_recipient_absent_night_bak.recv_state_cd IS '지우기 전 수신 상태(ALM_RECV_STATE — RECV=수신, ABSENT=부재)';
COMMENT ON COLUMN ax.tb_alm_recipient_absent_night_bak.night_recv    IS '지우기 전 개인 야간 수신 여부';
COMMENT ON COLUMN ax.tb_alm_recipient_absent_night_bak.backed_at     IS '보관한 시각';
COMMENT ON TABLE  ax.tb_alm_recip_group_night_bak            IS '알림 수신 그룹 야간 수신 보관 — 컬럼을 지울 때 남긴 그룹별 값. 되돌리기 스크립트가 이 값으로 컬럼을 되살림';
COMMENT ON COLUMN ax.tb_alm_recip_group_night_bak.group_id   IS '수신 그룹 ID (ax.tb_alm_recip_group 논리 참조, FK 없음)';
COMMENT ON COLUMN ax.tb_alm_recip_group_night_bak.night_recv IS '지우기 전 그룹 야간 수신 여부';
COMMENT ON COLUMN ax.tb_alm_recip_group_night_bak.backed_at  IS '보관한 시각';

DO $$
DECLARE
    v_cnt integer;
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = 'ax' AND table_name = 'tb_alm_recipient' AND column_name = 'recv_state_cd') THEN
        EXECUTE $q$
            INSERT INTO ax.tb_alm_recipient_absent_night_bak (user_id, recv_state_cd, night_recv, backed_at)
            SELECT user_id, recv_state_cd, night_recv, now()
              FROM ax.tb_alm_recipient
            ON CONFLICT (user_id) DO UPDATE
               SET recv_state_cd = EXCLUDED.recv_state_cd, night_recv = EXCLUDED.night_recv,
                   backed_at = EXCLUDED.backed_at $q$;
        GET DIAGNOSTICS v_cnt = ROW_COUNT;
        RAISE NOTICE 'V74: 수신자 부재 · 야간 수신 보관 % 건', v_cnt;

        -- 동작이 바뀌는 수신자 수(알림용)
        EXECUTE $q$ SELECT count(*) FROM ax.tb_alm_recipient WHERE recv_state_cd <> 'RECV' $q$ INTO v_cnt;
        RAISE NOTICE 'V74: 부재였던 수신자 % 명(이제 받음)', v_cnt;
        EXECUTE $q$ SELECT count(*) FROM ax.tb_alm_recipient WHERE NOT night_recv $q$ INTO v_cnt;
        RAISE NOTICE 'V74: 개인 야간 수신이 꺼져 있던 수신자 % 명(이제 야간에도 받음, 그룹 설정과 무관)', v_cnt;
    ELSE
        RAISE NOTICE 'V74: 수신자 부재 · 야간 수신 컬럼이 이미 없어 보관은 건너뜀';
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = 'ax' AND table_name = 'tb_alm_recip_group' AND column_name = 'night_recv') THEN
        EXECUTE $q$
            INSERT INTO ax.tb_alm_recip_group_night_bak (group_id, night_recv, backed_at)
            SELECT group_id, night_recv, now()
              FROM ax.tb_alm_recip_group
            ON CONFLICT (group_id) DO UPDATE
               SET night_recv = EXCLUDED.night_recv, backed_at = EXCLUDED.backed_at $q$;
        GET DIAGNOSTICS v_cnt = ROW_COUNT;
        RAISE NOTICE 'V74: 그룹 야간 수신 보관 % 건', v_cnt;
    ELSE
        RAISE NOTICE 'V74: 그룹 야간 수신 컬럼이 이미 없어 보관은 건너뜀';
    END IF;
END $$;

-- ── 2. 공통코드 정합성 목록 ──────────────────────────────────────────────────────────
DELETE FROM ax.tb_sys_code_ref WHERE target_table = 'tb_alm_recipient' AND target_column = 'recv_state_cd';

-- ── 3. 공통코드 ALM_RECV_STATE 사용 중지 ─────────────────────────────────────────────
UPDATE ax.tb_sys_code_group
   SET use_flg = 'N', upd_date = now()
 WHERE group_cd = 'ALM_RECV_STATE' AND use_flg <> 'N';
UPDATE ax.tb_sys_code
   SET use_flg = 'N', upd_date = now()
 WHERE group_cd = 'ALM_RECV_STATE' AND use_flg <> 'N';

-- ── 4. 주석 정정 ─────────────────────────────────────────────────────────────────────
COMMENT ON COLUMN ax.tb_alm_recipient.remark IS
  '비고(300자 이내). 수신자 표 「비고」 열에 표시. 부재 사유 용도는 부재 기능과 함께 없앰(2026-10-03, V74)';
COMMENT ON COLUMN ax.tb_alm_recip_group.window_cd IS
  '그룹 수신 시간대. 공통코드 ALM_WINDOW. 밖이면 이 그룹을 거친 발송을 SKIPPED 로 남김. 시간에 따른 제외는 이 값뿐(야간 수신은 2026-10-03 V74 에서 없앰)';
COMMENT ON COLUMN ax.tb_alm_eval_run.skip_cnt IS '유효 시간대로 건너뛴 수';

-- ── 5. 컬럼 삭제 ─────────────────────────────────────────────────────────────────────
ALTER TABLE ax.tb_alm_recipient
    DROP COLUMN IF EXISTS recv_state_cd,
    DROP COLUMN IF EXISTS night_recv;
ALTER TABLE ax.tb_alm_recip_group
    DROP COLUMN IF EXISTS night_recv;

COMMIT;
