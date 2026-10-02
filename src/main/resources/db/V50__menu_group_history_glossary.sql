-- =====================================================================================
--  V50 : 대그룹 「자연어 질의 이력」·「용어 사전」 신설과 화면 「용어 사전 조회」(gloss-view) 등록 (2026-10-01)
--
--  [배경]
--  결정 R-08 · R-09. 자연어 질의 이력(chat-history)을 시스템관리에서 「보고서」 처럼 별도 대그룹으로 옮기고,
--  용어 사전 조회·검색 전용 화면을 새 대그룹에 둔다. 사이드바 순서는 WEB 메뉴 정의 배열이 정하고,
--  메뉴 트리 API 와 메뉴 접근 권한 화면은 이 표의 sort_seq 로 정렬하므로 둘을 같게 맞춘다.
--  기획: 08 자연어 질의 이력 4.5(가) · 13 용어 사전 조회 4.5 · 03 MNP-17 (공통 묶음 M-11, 공통 9.5 순서표)
--
--  [이 파일이 하는 일]
--   1. 대그룹 2행 — history(자연어 질의 이력) · glossary(용어 사전), 둘 다 single 방식(is_solo = false)
--   2. 대그룹 순서 — assistant 1 · dashboard 2 · operation 3 · report 4 · history 5 · glossary 6 · alert 7 · system 8
--   3. chat-history 화면 행 — 그룹 history, 경로 /history/chat, 순서 1
--   4. gloss-view 화면 행 — 그룹 glossary, 경로 /glossary/view, 순서 1
--   5. 조회 권한 시드 (첫 적용 때만 — 다시 실행해도 관리자가 지운 행을 되살리지 않음)
--      · chat-history : 미배정을 포함한 사용 중 부서 전부 (미배정 행은 보통 V49 가 이미 넣음)
--      · gloss-view   : 미배정을 제외한 사용 중 부서 전부
--      쓰기 권한 이관(전산팀 chat-history can_write)은 V49 에 있다.
--
--  [하지 않는 일]
--  미배정 부서의 gloss-view 권한(결정 R-11 의 5개 화면에 없음), 데이터 접근 권한
--
--  [주의]
--  V42 를 다시 실행하면 chat-history 가 system 그룹으로 돌아간다(V42 의 ON CONFLICT UPDATE).
--  로컬 설치 스크립트처럼 V* 전체를 다시 돌리는 경우에는 이 파일이 뒤에서 다시 옮기므로 결과가 같다.
--  V42 만 따로 다시 실행하지 않는다.
--
--  [순서]
--  WEB 메뉴 정의(menu.js) 변경과 같은 배포 창에 적용한다. 어긋나면 메뉴 접근 권한 화면에서
--  chat-history 행이 다른 그룹 행에 붙는다.
--
--  [실행]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f V50__menu_group_history_glossary.sql
--  두 번 실행해도 안전하다. 되돌리기는 rollback/V50__down.sql.
-- =====================================================================================

BEGIN;

DO $$
DECLARE
    v_first boolean;
    v_cnt   integer;
BEGIN
    v_first := NOT EXISTS (SELECT 1 FROM ax.tb_sys_menu_group WHERE group_id = 'glossary');

    -- ── 1. 대그룹 ────────────────────────────────────────────────────────────────────
    INSERT INTO ax.tb_sys_menu_group (group_id, group_nm, is_solo, sort_seq, use_flg) VALUES
        ('history',  '자연어 질의 이력', false, 5, 'Y'),
        ('glossary', '용어 사전',        false, 6, 'Y')
    ON CONFLICT (group_id) DO UPDATE
       SET group_nm = EXCLUDED.group_nm, is_solo = EXCLUDED.is_solo, sort_seq = EXCLUDED.sort_seq, use_flg = 'Y';

    -- ── 2. 대그룹 순서 ───────────────────────────────────────────────────────────────
    UPDATE ax.tb_sys_menu_group g
       SET sort_seq = v.sort_seq
      FROM (VALUES ('assistant', 1), ('dashboard', 2), ('operation', 3), ('report', 4),
                   ('history', 5), ('glossary', 6), ('alert', 7), ('system', 8)) AS v(group_id, sort_seq)
     WHERE g.group_id = v.group_id
       AND g.sort_seq IS DISTINCT FROM v.sort_seq::smallint;

    -- ── 3. chat-history 이동 ─────────────────────────────────────────────────────────
    UPDATE ax.tb_sys_menu
       SET group_id = 'history', route_path = '/history/chat', sort_seq = 1, tag_cd = 'MOD',
           upd_date = now(), upd_user = 'V50'
     WHERE menu_id = 'chat-history'
       AND (group_id, route_path, sort_seq, tag_cd) IS DISTINCT FROM ('history', '/history/chat', 1::smallint, 'MOD');

    -- ── 4. gloss-view 화면 행 ────────────────────────────────────────────────────────
    INSERT INTO ax.tb_sys_menu (menu_id, menu_nm, group_id, parent_menu_id, is_sub_page, route_path, tag_cd, sort_seq,
                                use_flg, ins_user, upd_user)
    VALUES ('gloss-view', '용어 사전 조회', 'glossary', NULL, false, '/glossary/view', 'NEW', 1, 'Y', 'V50', 'V50')
    ON CONFLICT (menu_id) DO UPDATE
       SET menu_nm = EXCLUDED.menu_nm, group_id = EXCLUDED.group_id, route_path = EXCLUDED.route_path,
           sort_seq = EXCLUDED.sort_seq, use_flg = 'Y', upd_date = now(), upd_user = 'V50'
     WHERE (ax.tb_sys_menu.menu_nm, ax.tb_sys_menu.group_id, ax.tb_sys_menu.route_path,
            ax.tb_sys_menu.sort_seq, ax.tb_sys_menu.use_flg)
           IS DISTINCT FROM (EXCLUDED.menu_nm, EXCLUDED.group_id, EXCLUDED.route_path, EXCLUDED.sort_seq, 'Y'::bpchar);

    -- ── 5. 조회 권한 시드 (첫 적용 때만) ─────────────────────────────────────────────
    IF v_first THEN
        INSERT INTO ax.tb_sys_dept_menu_perm (dept_id, menu_id, can_read, can_write, ins_user, upd_user)
        SELECT d.dept_id, 'chat-history', true, false, 'V50', 'V50'
          FROM ax.tb_sys_dept d
         WHERE d.use_flg = 'Y'
        ON CONFLICT ON CONSTRAINT pk_tb_sys_dept_menu_perm DO NOTHING;
        GET DIAGNOSTICS v_cnt = ROW_COUNT;
        RAISE NOTICE 'V50: chat-history 조회 권한 추가 % 건', v_cnt;

        -- 미배정 이름은 API 설정 app.unassigned-dept-name 과 같아야 한다
        INSERT INTO ax.tb_sys_dept_menu_perm (dept_id, menu_id, can_read, can_write, ins_user, upd_user)
        SELECT d.dept_id, 'gloss-view', true, false, 'V50', 'V50'
          FROM ax.tb_sys_dept d
         WHERE d.use_flg = 'Y'
           AND d.dept_nm <> '미배정'
        ON CONFLICT ON CONSTRAINT pk_tb_sys_dept_menu_perm DO NOTHING;
        GET DIAGNOSTICS v_cnt = ROW_COUNT;
        RAISE NOTICE 'V50: gloss-view 조회 권한 추가 % 건', v_cnt;
    ELSE
        RAISE NOTICE 'V50: glossary 그룹이 이미 있어 권한 시드는 건너뜀(이미 적용됨)';
    END IF;
END $$;

COMMIT;
