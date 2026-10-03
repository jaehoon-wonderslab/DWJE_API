-- =====================================================================================
--  로컬·개발 전용 알림 수신자 시드 (SY-05)
--
--  ⚠️  운영 데이터베이스에 적용하지 마시오.
--      연락처가 전부 가짜다. 실 서버의 수신자는 화면에서 직접 등록한다.
--
--  목적
--  ----
--  기준 DDL 에는 수신 그룹·수신자 시드가 없어, 스키마만 적용하면 [시스템관리 >
--  알림 수신자 관리] 가 **통째로 빈 화면**이다. 그러면 발송 조건(SY-04)에서 고를 수신 그룹도
--  없고, 그룹 테스트 발송도 눌러 볼 수 없다.
--  이 스크립트는 seed_local_accounts.sql 이 만든 계정 6명을 그대로 수신자로 세우고,
--  운영에서 쓸 수신 그룹 2개를 채운다.
--
--  선행 조건 : seed_local_accounts.sql (계정 10000~10005 · 부서 6종)
--              tb_alm_recipient 은 tb_sys_user 를 FK 로 물고,
--              tb_alm_recip_group_member 는 tb_alm_recipient 을 FK 로 문다.
--
--  적용 : psql -d dwjedb -f seed_alert_recipient.sql
--         멱등 — 두 번 실행해도 안전하다.
--
--  넣는 것
--    수신 그룹 2 — 엔진 가동 · 생산 이슈 (2026-09-16 요청)
--    수신자   6 — 계정 6명 전원
--  수신자 부재(recv_state_cd)·야간 수신(night_recv)은 V74 에서 없앴다(2026-10-03). 이 시드는 두 컬럼을
--  쓰지 않으므로 V74 앞뒤 어느 DB 에서도 돈다(V74 이전 DB 에서는 기본값 RECV · false 가 들어간다).
--    그룹 채널 · 그룹 멤버
--
--  발송 채널은 메일(MAIL) 하나만 쓴다 (2026-09-16 요청). 공통코드 ALM_CHANNEL 에는
--  POPUP · SMS · MSG 가 그대로 있지만 — 발송 조건(SY-04)이 함께 쓰는 코드라 건드리지 않았다 —
--  수신 그룹에는 넣지 않는다.
-- =====================================================================================

SET client_min_messages = WARNING;

BEGIN;

-- -------------------------------------------------------------------------------------
-- 0. 예전 시드가 만든 그룹 정리
--    그룹 이름이 바뀌었다(품질 이상 대응·생산 지연 대응·설비 비가동·경영 보고 → 엔진 가동·생산 이슈).
--    이 줄이 없으면 이미 시드를 돌린 DB 에서 옛 그룹이 그대로 남아 6개가 된다.
--
--    지우는 것은 **시드가 만든 것(ins_user='SEED')뿐**이다. 사람이 화면에서 만든 그룹은
--    ins_user 가 사번이라 그대로 남는다. 발송 조건이 물고 있는 그룹도 건드리지 않는다
--    (그쪽 FK 는 ON DELETE RESTRICT 라, 걸러 두지 않으면 시드 전체가 실패한다).
--    승격 규칙 표(tb_alm_escalation_rule)는 V73 에서 지웠으므로 더는 거르지 않는다(2026-10-03).
--    V73 이전 DB 에서 승격 규칙이 시드 그룹을 물고 있으면 이 DELETE 가 FK 로 실패한다 — V73 부터 적용한다.
--    채널·멤버는 ON DELETE CASCADE 로 함께 빠진다.
-- -------------------------------------------------------------------------------------
DELETE FROM ax.tb_alm_recip_group g
 WHERE g.ins_user = 'SEED'
   AND g.group_nm NOT IN ('엔진 가동', '생산 이슈')
   AND NOT EXISTS (SELECT 1 FROM ax.tb_alm_cond_group cg WHERE cg.group_id = g.group_id);

-- -------------------------------------------------------------------------------------
-- 1. 수신 그룹 — 발송 조건(SY-04)이 이름으로 참조하는 단위
--    window_cd 는 공통코드 ALM_WINDOW (ALWAYS · D0820 · D0618 · WORKDAY · ONCE)
-- -------------------------------------------------------------------------------------
INSERT INTO ax.tb_alm_recip_group (group_nm, window_cd, dept_id, use_flg, ins_user, upd_user)
SELECT v.group_nm, v.window_cd, d.dept_id, 'Y', 'SEED', 'SEED'
FROM (VALUES
    -- 설비가 도는 동안은 늘 받는다
    ('엔진 가동', 'ALWAYS', '제조팀'),
    -- 생산 이슈는 주간 대응이면 충분하다
    ('생산 이슈', 'D0820',  '생산관리팀')
) AS v(group_nm, window_cd, dept_nm)
JOIN ax.tb_sys_dept d ON d.dept_nm = v.dept_nm
ON CONFLICT (group_nm) DO NOTHING;

-- -------------------------------------------------------------------------------------
-- 2. 그룹 발송 채널 — 메일 하나만 쓴다 (2026-09-16 요청)
--    예전 시드가 넣어 둔 POPUP · SMS 가 남지 않도록 메일 아닌 것은 먼저 지운다.
-- -------------------------------------------------------------------------------------
DELETE FROM ax.tb_alm_recip_group_channel gc
 WHERE gc.channel_cd <> 'MAIL'
   AND EXISTS (SELECT 1 FROM ax.tb_alm_recip_group g
                WHERE g.group_id = gc.group_id AND g.ins_user = 'SEED');

INSERT INTO ax.tb_alm_recip_group_channel (group_id, channel_cd)
SELECT g.group_id, 'MAIL'
FROM ax.tb_alm_recip_group g
WHERE g.group_nm IN ('엔진 가동', '생산 이슈')
ON CONFLICT (group_id, channel_cd) DO NOTHING;

-- -------------------------------------------------------------------------------------
-- 3. 수신자 — 계정의 연락처
--    email 은 CHECK (position('@' in email) > 1) 을 만족해야 한다
-- -------------------------------------------------------------------------------------
INSERT INTO ax.tb_alm_recipient (
    user_id, email, mobile_no, messenger_id, remark, ins_user, upd_user
) VALUES
 ('10000', '10000@dwje.co.kr', '010-1000-0000', 'dwje.admin',   '통합관리자 — 전 알림 수신', 'SEED', 'SEED'),
 ('10001', '10001@dwje.co.kr', '010-1000-0001', 'dwje.quality', NULL,                        'SEED', 'SEED'),
 ('10002', '10002@dwje.co.kr', '010-1000-0002', 'dwje.plan',    NULL,                        'SEED', 'SEED'),
 ('10003', '10003@dwje.co.kr', '010-1000-0003', 'dwje.mfg',     NULL,                        'SEED', 'SEED'),
 ('10004', '10004@dwje.co.kr', '010-1000-0004', 'dwje.it',      NULL,                        'SEED', 'SEED'),
 ('10005', '10005@dwje.co.kr', '010-1000-0005', 'dwje.exec',    NULL,                        'SEED', 'SEED')
ON CONFLICT (user_id) DO NOTHING;

-- -------------------------------------------------------------------------------------
-- 4. 그룹 멤버 — 수신자로 등록된 계정만 편성할 수 있다 (FK: tb_alm_recipient)
-- -------------------------------------------------------------------------------------
INSERT INTO ax.tb_alm_recip_group_member (group_id, user_id, ins_user)
SELECT g.group_id, v.user_id, 'SEED'
FROM (VALUES
    ('엔진 가동', '10003'), ('엔진 가동', '10004'), ('엔진 가동', '10000'),
    ('생산 이슈', '10002'), ('생산 이슈', '10001')
) AS v(group_nm, user_id)
JOIN ax.tb_alm_recip_group g ON g.group_nm = v.group_nm
ON CONFLICT (group_id, user_id) DO NOTHING;

COMMIT;

-- 확인용
-- SELECT g.group_nm, count(m.user_id) FROM ax.tb_alm_recip_group g
--   LEFT JOIN ax.tb_alm_recip_group_member m ON m.group_id = g.group_id GROUP BY 1 ORDER BY 1;   -- 2행
-- SELECT DISTINCT channel_cd FROM ax.tb_alm_recip_group_channel;                                 -- MAIL 만
-- SELECT count(*) FROM ax.tb_alm_recipient;                                                     -- 6
-- =====================================================================================
-- EOF seed_alert_recipient.sql
-- =====================================================================================
