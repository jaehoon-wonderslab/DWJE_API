-- =====================================================================================
--  V46 되돌리기 — 그룹웨어 부서 매핑 화면 행 · 권한 · 이력 구분 제거 (2026-09-30)
--
--  화면 행을 지우면 부서 권한(tb_sys_dept_menu_perm)·계정 추가 허용(tb_sys_user_menu_grant) 행은
--  FK CASCADE 로 함께 지워진다. 이력 구분 코드는 이미 쌓인 이력(act_cd = GW_DEPT_MAP)이 있으면
--  공통코드 정합성(ax.fn_check_code_ref)이 깨지므로 남긴다 — 쓰는 행이 없을 때만 지운다.
--
--  [API 는 함께 되돌린다]
--  /api/v1/system/gw-dept-maps/* 는 화면 행이 없으면 통합관리자만 닿는다(다른 계정은 403).
--
--  [적용]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f rollback/V46__down.sql
--  두 번 실행해도 안전하다.
-- =====================================================================================

BEGIN;

DELETE FROM ax.tb_sys_menu WHERE menu_id = 'sys-gw-dept';

DELETE FROM ax.tb_sys_code c
 WHERE c.group_cd = 'SYS_PERM_ACT' AND c.code = 'GW_DEPT_MAP'
   AND NOT EXISTS (SELECT 1 FROM ax.tb_sys_perm_log l WHERE l.act_cd = 'GW_DEPT_MAP');

COMMIT;
