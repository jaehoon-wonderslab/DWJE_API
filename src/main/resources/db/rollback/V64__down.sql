-- =====================================================================================
--  V64 되돌리기 — 판정 뷰를 화면 use_flg 만 보던 정의로 (2026-10-01)
--
--  열 이름 · 순서는 같으므로 CREATE OR REPLACE 로 돌린다. 주석도 V64 직전 문구로.
--
--  [적용]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f rollback/V64__down.sql
--  두 번 실행해도 안전하다.
-- =====================================================================================

BEGIN;

CREATE OR REPLACE VIEW ax.vw_sys_user_menu_perm AS
SELECT p.user_id,
       p.menu_id,
       bool_or(p.can_write)    AS can_write,
       bool_or(p.src = 'DEPT') AS from_dept,
       bool_or(p.src = 'USER') AS from_grant
  FROM (
        SELECT u.user_id, dp.menu_id, dp.can_write, 'DEPT'::text AS src
          FROM ax.tb_sys_user u
          JOIN ax.tb_sys_dept_menu_perm dp ON dp.dept_id = u.dept_id
         WHERE dp.can_read
        UNION ALL
        SELECT g.user_id, g.menu_id, g.can_write, 'USER'::text
          FROM ax.tb_sys_user_menu_grant g
       ) p
  JOIN ax.tb_sys_menu m ON m.menu_id = p.menu_id
 WHERE m.use_flg = 'Y'
 GROUP BY p.user_id, p.menu_id;

COMMENT ON VIEW ax.vw_sys_user_menu_perm IS
  '계정 × 유효 화면 접근 권한 = 부서 권한(can_read) ∪ 계정 추가 허용. can_write 는 두 출처의 OR. 사용 중인 화면(use_flg=''Y'')만. 통합관리자 전 화면 허용은 포함하지 않음(API 가 별도 판정)';

COMMIT;
