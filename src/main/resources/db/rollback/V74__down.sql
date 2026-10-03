-- =====================================================================================
--  V74 되돌리기 — 알림 수신자 부재(recv_state_cd) · 개인 야간 수신 · 그룹 야간 수신 컬럼 복원 (2026-10-03)
--
--  · 컬럼을 V73 상태로 되살린다(V35 정의 — night_recv boolean NOT NULL DEFAULT false,
--    recv_state_cd varchar(30) NOT NULL DEFAULT 'RECV', 주석은 V54 · V40 최종 문구). 되살린 컬럼은 표 맨 뒤에 붙는다.
--  · 보관 표 ax.tb_alm_recipient_absent_night_bak · ax.tb_alm_recip_group_night_bak 에 있는 행은 값을 되돌리고,
--    V74 이후 만든 수신자 · 그룹은 기본값(RECV · false)으로 둔다.
--  · 공통코드 정합성 목록에 recv_state_cd 를 다시 넣고, 공통코드 그룹 ALM_RECV_STATE(RECV · ABSENT)를 다시 켠다.
--  · remark · window_cd · skip_cnt 주석을 V74 이전 문구로 되돌린다.
--  · 보관 표는 남긴다(다시 V74 를 적용할 때 덮어씀).
--
--  [옛 엔진 · 옛 API 로 함께 되돌린다]
--
--  [적용]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f rollback/V74__down.sql
--  두 번 실행해도 안전하다.
-- =====================================================================================

BEGIN;

-- ── 1. 컬럼 ──────────────────────────────────────────────────────────────────────────
ALTER TABLE ax.tb_alm_recipient
    ADD COLUMN IF NOT EXISTS night_recv    boolean     NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS recv_state_cd varchar(30) NOT NULL DEFAULT 'RECV';
ALTER TABLE ax.tb_alm_recip_group
    ADD COLUMN IF NOT EXISTS night_recv boolean NOT NULL DEFAULT false;

DO $$
DECLARE
    v_cnt integer := 0;
    v_grp integer := 0;
BEGIN
    IF to_regclass('ax.tb_alm_recipient_absent_night_bak') IS NOT NULL THEN
        UPDATE ax.tb_alm_recipient r
           SET recv_state_cd = coalesce(b.recv_state_cd, 'RECV'),
               night_recv    = coalesce(b.night_recv, false)
          FROM ax.tb_alm_recipient_absent_night_bak b
         WHERE b.user_id = r.user_id;
        GET DIAGNOSTICS v_cnt = ROW_COUNT;
    END IF;
    IF to_regclass('ax.tb_alm_recip_group_night_bak') IS NOT NULL THEN
        UPDATE ax.tb_alm_recip_group g
           SET night_recv = coalesce(b.night_recv, false)
          FROM ax.tb_alm_recip_group_night_bak b
         WHERE b.group_id = g.group_id;
        GET DIAGNOSTICS v_grp = ROW_COUNT;
    END IF;
    RAISE NOTICE 'V74 되돌리기: 수신자 값 되돌림 % 건, 그룹 값 되돌림 % 건', v_cnt, v_grp;
END $$;

COMMENT ON COLUMN ax.tb_alm_recipient.recv_state_cd IS
  '수신 상태. 공통코드 ALM_RECV_STATE (RECV=수신, ABSENT=부재). 부재면 발송 대상에서 제외(대리 수신 없음 — 당번 표 제거 이후)';
COMMENT ON COLUMN ax.tb_alm_recipient.night_recv IS
  'true 면 야간(알림 엔진 설정 alert.night, 기본 22:00~06:00)에도 받음. 소속 그룹의 night_recv 가 true 여도 받음(그룹 OR 개인)';
COMMENT ON COLUMN ax.tb_alm_recip_group.night_recv IS 'true 면 그룹 전체가 야간에도 수신. 멤버 개인 설정이 꺼져 있어도 발송';

-- ── 2. 주석 ──────────────────────────────────────────────────────────────────────────
COMMENT ON COLUMN ax.tb_alm_recipient.remark IS
  '비고. 부재 사유 등(300자 이내 입력). 알림 수신자 관리 화면의 수신/부재 전환에서 사유로 저장하고 수신자 표 「비고」 열에 표시';
COMMENT ON COLUMN ax.tb_alm_recip_group.window_cd IS
  '그룹 수신 시간대. 공통코드 ALM_WINDOW. 밖이면 이 그룹을 거친 발송을 SKIPPED 로 남김(일반 발송 · 승격 모두). 조건의 ignore_window_flg=Y 면 무시하되 개인 야간 미수신은 계속 지킴';
COMMENT ON COLUMN ax.tb_alm_eval_run.skip_cnt IS '유효 시간대·야간 미수신으로 건너뛴 수';

-- ── 3. 공통코드 ──────────────────────────────────────────────────────────────────────
UPDATE ax.tb_sys_code_group
   SET use_flg = 'Y', upd_date = now()
 WHERE group_cd = 'ALM_RECV_STATE' AND use_flg <> 'Y';
UPDATE ax.tb_sys_code
   SET use_flg = 'Y', upd_date = now()
 WHERE group_cd = 'ALM_RECV_STATE' AND code IN ('RECV', 'ABSENT') AND use_flg <> 'Y';

INSERT INTO ax.tb_sys_code_ref (target_table, target_column, group_cd, nullable_flg)
VALUES ('tb_alm_recipient', 'recv_state_cd', 'ALM_RECV_STATE', 'N')
ON CONFLICT DO NOTHING;

COMMIT;
