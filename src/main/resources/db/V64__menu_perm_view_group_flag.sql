-- =====================================================================================
--  V64 : 화면 권한 판정 뷰가 메뉴 그룹 사용 여부도 보게 (2026-10-01)
--
--  [배경]
--  메뉴 그룹(tb_sys_menu_group)을 사용 중지해도 판정 뷰 ax.vw_sys_user_menu_perm 은 화면의 use_flg 만 봐서
--  그 그룹 화면이 /auth/me 의 menuPerms 와 서버 판정에 그대로 남았다. 메뉴 접근 권한 매트릭스
--  (findMenuPermMatrix)는 그룹 사용 여부를 보므로 두 기준이 어긋났다.
--  기획: 03 메뉴 접근 권한 4.5.2 · MNP-14 (공통 묶음 M-3, 4단계 P2).
--
--  [이 파일이 하는 일]
--  뷰 정의를 바꾼다 — 화면과 메뉴 그룹이 모두 use_flg = 'Y' 인 것만. 열 이름 · 순서 · 뜻은 그대로다
--  (can_write 집계는 쓰기 권한 판정 writePerms 의 원천이라 바꾸지 않음).
--  2026-10-01 로컬은 그룹 8개가 모두 사용 중이라 결과 행 수가 같다(적용 전후 비교).
--
--  [API 쪽 같은 조건]
--  부서 기준 보조 질의 AuthRepository.findMenuPermissions 에도 같은 그룹 조건을 넣는 것은 API 몫이다.
--
--  [실행]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f V64__menu_perm_view_group_flag.sql
--  두 번 실행해도 안전하다. 되돌리기는 rollback/V64__down.sql.
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
  JOIN ax.tb_sys_menu m        ON m.menu_id = p.menu_id AND m.use_flg = 'Y'
  JOIN ax.tb_sys_menu_group mg ON mg.group_id = m.group_id AND mg.use_flg = 'Y'
 GROUP BY p.user_id, p.menu_id;

COMMENT ON VIEW ax.vw_sys_user_menu_perm IS
  '계정 × 유효 화면 접근 권한 = 부서 권한(can_read) ∪ 계정 추가 허용. can_write 는 두 출처의 OR. 화면과 메뉴 그룹이 모두 사용 중(use_flg=''Y'')인 것만. 통합관리자 전 화면 허용은 포함하지 않음(API 가 별도 판정)';

COMMIT;
