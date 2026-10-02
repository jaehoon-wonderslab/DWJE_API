-- =====================================================================================
--  V52 되돌리기 — 그룹웨어 부서 매핑 CHECK · 미배정 부서 설명 (2026-10-01)
--
--  CHECK 를 지우고 미배정 부서 설명과 표 주석을 V52 직전 문구로 돌린다.
--
--  [적용]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f rollback/V52__down.sql
--  두 번 실행해도 안전하다.
-- =====================================================================================

BEGIN;

ALTER TABLE ax.tb_sys_dept_gw_map DROP CONSTRAINT IF EXISTS ck_sys_dept_gw_map_excluded;

UPDATE ax.tb_sys_dept
   SET dept_desc = '그룹웨어 자동 가입 계정 중 부서 매핑이 없는 사람 — 화면 권한 없음, 계정 관리 화면에서 실제 부서로 옮김',
       upd_date = now(), upd_user = 'V52-down'
 WHERE dept_nm = '미배정'
   AND dept_desc IS DISTINCT FROM '그룹웨어 자동 가입 계정 중 부서 매핑이 없는 사람 — 화면 권한 없음, 계정 관리 화면에서 실제 부서로 옮김';

COMMENT ON TABLE ax.tb_sys_dept_gw_map IS
  '그룹웨어 부서명 → AX 부서 매핑 — 그룹웨어 인사정보 자동 가입 때 부서를 정하는 기준, 가입 순간에만 사용';

COMMIT;
