-- =====================================================================================
--  V25 : 메뉴 — 업로드 리포트 동작 권한 · 업로드 문서 목록 화면 · 이름 변경 2건
--        (REQ_20260910 요구 1·7·8·9, B4)
--
--  [배경]
--  발주자 요구 9건(2026-09-10) 중 tb_sys_menu 가 원천인 것들이다.
--   요구 1  '자연어 질의' → '덕파트장 AI'          : 사이드바 이름은 /auth/me 가 tb_sys_menu.menu_nm 을
--                                                   내려주므로 DB 를 고쳐야 화면이 바뀐다.
--   요구 7  업로드 권한을 메뉴 접근 권한에서 관리 : 화면 행 dash-ai-upload (동작 권한). 부서별 권한은
--                                                   tb_sys_dept_menu_perm × tb_sys_menu 로만 판정하므로
--                                                   행이 있어야 권한을 줄 수 있다 (V5 와 같은 이유).
--   요구 8  시스템 관리에 업로드 문서 목록        : 화면 행 sys-upload-doc.
--   요구 9  'AOI 판정 분석/예측' → 'AOI 판정 분석' : menu_nm 만 바꾼다. menu_id(qc-aoi)·경로는 유지.
--
--  [동작 권한 행의 표현]
--  tb_sys_menu 에 "종류" 컬럼은 없다. is_sub_page = true 로 두면 사이드바에는 나오지 않고
--  권한 매트릭스(findAllMenus, is_sub_page 필터 없음)에는 들어온다 — 하위 화면(daily-history 등)과
--  같은 방식이다. 경로가 없는 동작이므로 route_path 는 NULL. 하위 화면 관례대로 sort_seq 90.
--
--  [부서 기본 권한]
--  통합관리자는 is_super_admin 으로 행 없이 전체 허용이라 넣지 않는다. 전산팀만 넣는다.
--  부서는 환경마다 dept_id 가 다르므로 dept_nm 으로 찾는다. 해당 부서가 없으면 0행이고,
--  그 환경은 권한 관리 화면에서 부여한다. can_write 는 업로드(쓰기 동작)만 true.
--
--  [적용]
--  docker exec -e PGPASSWORD=dwje_local dwje-pg psql -U dwje_local -d dwjedb -v ON_ERROR_STOP=1 -f V25__menu_ai_panel_upload_aoi.sql
--  두 번 실행해도 안전하다. (이미 반영돼 있으면 0행)
-- =====================================================================================

-- 1. 화면 행 2건 -------------------------------------------------------------------------
INSERT INTO ax.tb_sys_menu (menu_id, menu_nm, group_id, parent_menu_id, is_sub_page, route_path, tag_cd, sort_seq) VALUES
 ('dash-ai-upload', '업로드 리포트 업로드', 'dashboard', 'dash-ai', true,  NULL,              'NEW', 90),
 ('sys-upload-doc', '업로드 문서 목록',     'system',    NULL,      false, '#sys-upload-doc', 'NEW', 16)
ON CONFLICT (menu_id) DO NOTHING;

-- 2. 이름 변경 2건 -----------------------------------------------------------------------
UPDATE ax.tb_sys_menu SET menu_nm = '덕파트장 AI',   upd_date = now()
 WHERE menu_id = 'ai-chat' AND menu_nm <> '덕파트장 AI';
UPDATE ax.tb_sys_menu SET menu_nm = 'AOI 판정 분석', upd_date = now()
 WHERE menu_id = 'qc-aoi'  AND menu_nm <> 'AOI 판정 분석';

-- 3. 전산팀 기본 권한 ---------------------------------------------------------------------
INSERT INTO ax.tb_sys_dept_menu_perm (dept_id, menu_id, can_read, can_write, ins_user, upd_user)
SELECT d.dept_id, v.menu_id, true, v.can_write, 'V25', 'V25'
  FROM (VALUES ('dash-ai-upload', true), ('sys-upload-doc', false)) AS v(menu_id, can_write)
  JOIN ax.tb_sys_dept d ON d.dept_nm = '전산팀' AND d.use_flg = 'Y'
ON CONFLICT (dept_id, menu_id) DO NOTHING;

COMMENT ON COLUMN ax.tb_sys_menu.is_sub_page IS 'true = 사이드바에 노출되지 않고 버튼·링크로만 진입하는 하위 화면(daily-history 등) 또는 경로 없는 동작 권한(dash-ai-upload). 권한 매트릭스에는 포함된다';
