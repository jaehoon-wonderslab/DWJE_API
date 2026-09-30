-- =====================================================================================
--  V46 : 시스템관리 › 그룹웨어 부서 매핑 화면 (sys-gw-dept) — 화면 행 · 전산팀 권한 · 이력 구분 (2026-09-30)
--
--  [배경]
--  V45 가 만든 ax.tb_sys_dept_gw_map 을 SQL 로만 고칠 수 있었고, 미배정 부서로 자동 가입된 계정을
--  한눈에 볼 방법이 없었다. WEB 이 관리 화면을 붙였다(WEB 요청 REQ_20260930_gw_dept_map).
--  API 는 /api/v1/system/gw-dept-maps/* 6건을 화면 권한 sys-gw-dept 로 막는다.
--
--  [이 파일이 하는 일]
--   1. ax.tb_sys_menu 에 sys-gw-dept 화면 행 — 사이드바 순서는 WEB 의 메뉴 정의가 정하고, sort_seq 는
--      메뉴 접근 권한 화면의 표 순서만 정한다(시스템관리 그룹 맨 끝 12)
--   2. 전산팀에 열람 권한 — 통합관리자는 행 없이 전체 허용이라 넣지 않는다
--   3. 권한 변경 이력 구분 코드 GW_DEPT_MAP — 매핑 저장·삭제를 이력 화면에서 따로 거를 수 있게 한다
--
--  [실행]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f V46__sys_gw_dept_menu.sql
--  두 번 실행해도 안전하다. 되돌리기는 rollback/V46__down.sql.
-- =====================================================================================

BEGIN;

-- ── 1. 화면 행 ──────────────────────────────────────────────────────────────────────
INSERT INTO ax.tb_sys_menu (menu_id, menu_nm, group_id, parent_menu_id, is_sub_page, route_path, tag_cd, sort_seq,
                            use_flg, ins_user, upd_user)
VALUES ('sys-gw-dept', '그룹웨어 부서 매핑', 'system', NULL, false, '/system/gw-dept-map', 'NEW', 12, 'Y', 'V46', 'V46')
ON CONFLICT (menu_id) DO NOTHING;

-- ── 2. 전산팀 권한 ──────────────────────────────────────────────────────────────────
INSERT INTO ax.tb_sys_dept_menu_perm (dept_id, menu_id, can_read, can_write, ins_user, upd_user)
SELECT d.dept_id, 'sys-gw-dept', true, false, 'V46', 'V46'
  FROM ax.tb_sys_dept d
 WHERE d.dept_nm = '전산팀'
ON CONFLICT ON CONSTRAINT pk_tb_sys_dept_menu_perm DO NOTHING;

-- ── 3. 권한 변경 이력 구분 ─────────────────────────────────────────────────────────
INSERT INTO ax.tb_sys_code (group_cd, code, code_nm, code_desc, sort_seq, use_flg, ins_user, upd_user) VALUES
 ('SYS_PERM_ACT', 'GW_DEPT_MAP', '그룹웨어 부서 매핑',
  '그룹웨어 부서명 → AX 부서 매핑 저장·삭제 (ax.tb_sys_dept_gw_map). 미배정 계정 재배정은 계정 부서 이동이라 ACCOUNT', 6, 'Y', 'V46', 'V46')
ON CONFLICT (group_cd, code) DO NOTHING;

COMMIT;
