-- =====================================================================================
--  V49 : 미배정 부서 고정 권한 · 쓰기 권한(can_write) 이관 (2026-10-01)
--
--  [배경]
--  · 미배정 부서(그룹웨어 자동 가입 349명)는 화면 권한이 0건이라 로그인해도 열리는 화면이 없었다.
--    결정 R-01 · R-11 로 대시보드 3개 · 덕반장 AI · 자연어 질의 이력, 모두 5개 화면만 조회로 고정한다.
--    데이터 접근 권한 행은 넣지 않는다(0건 고정 — 수량·수율도 비공개).
--  · 결정 R-06 으로 API 가 쓰기 동작에 can_write 를 판정한다(requireWrite). 지금 저장 동작을 하고 있는
--    부서 × 화면을 먼저 can_write = true 로 채우지 않으면 판정을 켜는 순간 전산팀의 저장이 모두 403 이 된다.
--  기획: 03 메뉴 접근 권한 4.5.1 (공통 묶음 M-12 + M-10). 이관 대상은 화면별 기획서 4.5 를 모은 것:
--
--    화면            | can_write = true 로 바꾸는 부서                  | 출처
--    sys-account     | 전산팀 · 통합관리자                              | 01 ACC-15
--    sys-gw-dept     | 전산팀                                           | 02 GWD-14
--    sys-menu        | 전산팀                                           | 03 MNP-16
--    sys-data        | 전산팀                                           | 04 DTP-17
--    alert-cond      | 전산팀 · 통합관리자                              | 05 ALC-16
--    sys-recip       | 전산팀 · 통합관리자                              | 06 RCP-15
--    sys-gloss       | 지금 sys-gloss 조회 권한을 가진 부서 전부(미배정 제외) | 07 GLS-16 (권장안 a)
--    chat-history    | 전산팀                                           | 08 CHH-16
--    dash-ai-upload  | 전산팀 (이미 true — 변화 없음)                   | 11 UPD-14
--    sys-sync        | 전산팀 · 통합관리자                              | 12 SYN-14
--
--    통합관리자는 행 없이 모든 판정을 통과한다. 위 표에서 통합관리자를 함께 켠 것은 메뉴 접근 권한
--    화면의 「쓰기」 칸이 실제와 같게 보이도록 한 화면별 기획서를 그대로 따른 것이다.
--    계정 추가 허용(tb_sys_user_menu_grant)의 can_write 는 이관하지 않는다(로컬·현재 0건).
--
--  [이 파일이 하는 일]
--   0. 되돌리기용 기록표 ax.tb_mig_v49_menu_perm_bak — 이 파일이 바꾼 행의 이전 값
--   1. 미배정 부서의 5개 화면 밖 권한 행 삭제(로컬 0건 — 실서버도 0건이어야 정상)
--   2. 미배정 부서 5개 화면 조회 행(can_write = false)
--   3. 쓰기 권한 이관 — 기록표가 처음 만들어질 때만. 다시 실행해도 관리자가 끈 칸을 다시 켜지 않는다
--
--  [부서 찾기]
--  부서는 이름으로 찾는다(API 설정 app.unassigned-dept-name = 미배정 과 같은 기준). 실서버 부서명이
--  다르면 0행이 바뀌므로 적용 전에 SELECT dept_id, dept_nm, is_super_admin FROM ax.tb_sys_dept 로 확인한다.
--
--  [순서]
--  이 파일 → API 배포(requireWrite · 미배정 교집합) → WEB 배포. 거꾸로 하면 운영 중 저장이 막힌다.
--
--  [실행]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f V49__unassigned_dashboard_and_write_perm.sql
--  두 번 실행해도 안전하다. 되돌리기는 rollback/V49__down.sql.
-- =====================================================================================

BEGIN;

DO $$
DECLARE
    v_first boolean;
    v_cnt   integer;
BEGIN
    -- ── 0. 되돌리기용 기록표 ─────────────────────────────────────────────────────────
    v_first := to_regclass('ax.tb_mig_v49_menu_perm_bak') IS NULL;

    CREATE TABLE IF NOT EXISTS ax.tb_mig_v49_menu_perm_bak (
        dept_id        integer     NOT NULL,
        menu_id        varchar(30) NOT NULL,
        action         varchar(20) NOT NULL,
        prev_can_read  boolean,
        prev_can_write boolean,
        bak_at         timestamptz NOT NULL DEFAULT now(),
        CONSTRAINT pk_tb_mig_v49_menu_perm_bak PRIMARY KEY (dept_id, menu_id, action)
    );

    -- ── 1. 미배정 부서의 5개 화면 밖 권한 행 정리 ───────────────────────────────────
    INSERT INTO ax.tb_mig_v49_menu_perm_bak (dept_id, menu_id, action, prev_can_read, prev_can_write)
    SELECT p.dept_id, p.menu_id, 'UNASSIGNED_DEL', p.can_read, p.can_write
      FROM ax.tb_sys_dept_menu_perm p
      JOIN ax.tb_sys_dept d ON d.dept_id = p.dept_id
     WHERE d.dept_nm = '미배정'
       AND p.menu_id NOT IN ('dash-ai', 'dash-proc', 'prod-monitor', 'ai-chat', 'chat-history')
    ON CONFLICT DO NOTHING;

    DELETE FROM ax.tb_sys_dept_menu_perm p
     USING ax.tb_sys_dept d
     WHERE d.dept_id = p.dept_id
       AND d.dept_nm = '미배정'
       AND p.menu_id NOT IN ('dash-ai', 'dash-proc', 'prod-monitor', 'ai-chat', 'chat-history');
    GET DIAGNOSTICS v_cnt = ROW_COUNT;
    RAISE NOTICE 'V49: 미배정 범위 밖 권한 행 삭제 % 건', v_cnt;

    -- ── 2. 미배정 부서 5개 화면 조회 행 ──────────────────────────────────────────────
    INSERT INTO ax.tb_mig_v49_menu_perm_bak (dept_id, menu_id, action, prev_can_read, prev_can_write)
    SELECT d.dept_id, m.menu_id, 'UNASSIGNED_ADD', p.can_read, p.can_write
      FROM ax.tb_sys_dept d
      JOIN ax.tb_sys_menu m ON m.menu_id IN ('dash-ai', 'dash-proc', 'prod-monitor', 'ai-chat', 'chat-history')
      LEFT JOIN ax.tb_sys_dept_menu_perm p ON p.dept_id = d.dept_id AND p.menu_id = m.menu_id
     WHERE d.dept_nm = '미배정'
    ON CONFLICT DO NOTHING;

    INSERT INTO ax.tb_sys_dept_menu_perm (dept_id, menu_id, can_read, can_write, ins_user, upd_user)
    SELECT d.dept_id, m.menu_id, true, false, 'V49', 'V49'
      FROM ax.tb_sys_dept d
      JOIN ax.tb_sys_menu m ON m.menu_id IN ('dash-ai', 'dash-proc', 'prod-monitor', 'ai-chat', 'chat-history')
                           AND m.use_flg = 'Y'
     WHERE d.dept_nm = '미배정'
    ON CONFLICT ON CONSTRAINT pk_tb_sys_dept_menu_perm DO UPDATE
       SET can_read = true, can_write = false, upd_date = now(), upd_user = 'V49'
     WHERE ax.tb_sys_dept_menu_perm.can_read IS DISTINCT FROM true
        OR ax.tb_sys_dept_menu_perm.can_write IS DISTINCT FROM false;
    GET DIAGNOSTICS v_cnt = ROW_COUNT;
    RAISE NOTICE 'V49: 미배정 5개 화면 행 추가·정정 % 건', v_cnt;

    -- ── 3. 쓰기 권한 이관 (첫 적용 때만) ─────────────────────────────────────────────
    IF v_first THEN
        INSERT INTO ax.tb_mig_v49_menu_perm_bak (dept_id, menu_id, action, prev_can_read, prev_can_write)
        SELECT p.dept_id, p.menu_id, 'WRITE_ON', p.can_read, p.can_write
          FROM ax.tb_sys_dept_menu_perm p
          JOIN ax.tb_sys_dept d ON d.dept_id = p.dept_id
         WHERE p.can_read
           AND NOT p.can_write
           AND d.dept_nm <> '미배정'
           AND (   (d.dept_nm = '전산팀'
                    AND p.menu_id IN ('sys-account', 'sys-gw-dept', 'sys-menu', 'sys-data', 'alert-cond',
                                      'sys-recip', 'sys-gloss', 'sys-sync', 'chat-history', 'dash-ai-upload'))
                OR (d.is_super_admin
                    AND p.menu_id IN ('sys-account', 'alert-cond', 'sys-recip', 'sys-sync'))
                OR p.menu_id = 'sys-gloss')
        ON CONFLICT DO NOTHING;

        UPDATE ax.tb_sys_dept_menu_perm p
           SET can_write = true, upd_date = now(), upd_user = 'V49'
          FROM ax.tb_mig_v49_menu_perm_bak b
         WHERE b.action = 'WRITE_ON'
           AND b.dept_id = p.dept_id
           AND b.menu_id = p.menu_id
           AND NOT p.can_write;
        GET DIAGNOSTICS v_cnt = ROW_COUNT;
        RAISE NOTICE 'V49: can_write 이관 % 건', v_cnt;
    ELSE
        RAISE NOTICE 'V49: 기록표가 이미 있어 쓰기 권한 이관은 건너뜀(이미 적용됨)';
    END IF;
END $$;

-- ── 주석 ─────────────────────────────────────────────────────────────────────────────
COMMENT ON TABLE  ax.tb_mig_v49_menu_perm_bak IS
  '메뉴 권한 이관 되돌리기 기록 — 미배정 부서 고정·쓰기 권한 이관 때 바뀐 부서 권한 행의 이전 값. 되돌리기 스크립트가 지움';
COMMENT ON COLUMN ax.tb_mig_v49_menu_perm_bak.dept_id        IS '부서 ID (ax.tb_sys_dept)';
COMMENT ON COLUMN ax.tb_mig_v49_menu_perm_bak.menu_id        IS '화면 ID (ax.tb_sys_menu)';
COMMENT ON COLUMN ax.tb_mig_v49_menu_perm_bak.action         IS
  '바꾼 방식 — UNASSIGNED_DEL=미배정 범위 밖 행 삭제, UNASSIGNED_ADD=미배정 5개 화면 행 추가·정정, WRITE_ON=쓰기 권한 켬';
COMMENT ON COLUMN ax.tb_mig_v49_menu_perm_bak.prev_can_read  IS '바꾸기 전 조회 권한. NULL 이면 행이 없었음';
COMMENT ON COLUMN ax.tb_mig_v49_menu_perm_bak.prev_can_write IS '바꾸기 전 쓰기 권한. NULL 이면 행이 없었음';
COMMENT ON COLUMN ax.tb_mig_v49_menu_perm_bak.bak_at         IS '기록 시각';

COMMENT ON COLUMN ax.tb_sys_dept_menu_perm.can_write IS
  '입력·수정 권한. true 여야 쓰기 API 를 통과(조회 권한도 함께 필요). 메뉴 접근 권한 화면의 「쓰기」 칸. '
  '통합관리자는 이 값과 무관하게 통과, 미배정 부서는 늘 false';

COMMIT;
