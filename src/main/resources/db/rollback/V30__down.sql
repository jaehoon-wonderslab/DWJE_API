-- =====================================================================================
--  V30 되돌리기 — 계정별 추가 허용 메뉴 취소 (2026-09-13 작성분, 2026-09-16 추가)
--
--  V30__user_menu_grant.sql 이 더한 것을 그대로 되돌린다.
--  뷰 1 · 표 1 · 인덱스 1 · 공통코드 1행 · 바꾼 표 주석 2건.
--
--  ★★ 지금은 이 파일을 돌리면 API 가 멈춘다 ★★
--  2026-09-16 기준 인증 경로가 이 뷰를 읽는다 —
--      AuthRepository.findMenuPermissions : SELECT menu_id FROM ax.vw_sys_user_menu_perm WHERE user_id = :userId
--      AuthRepository (메뉴 트리) · SystemUserRepository (계정별 추가 허용 CRUD) 도 마찬가지.
--  뷰가 사라지면 화면 하나가 아니라 **로그인 이후 모든 요청**이 실패한다.
--  되돌리려면 그 코드를 부서 권한만 보던 질의로 함께 되돌려야 한다.
--  (V30 이전 질의: ax.tb_sys_dept_menu_perm × ax.tb_sys_menu 를 dept_id 로 조회)
--
--  [업무 데이터]
--  ax.tb_sys_user_menu_grant 의 행(계정별 예외 허용)은 표와 함께 사라진다. 먼저 떠 둔다 —
--      pg_dump -U <user> -d <db> --no-owner --no-privileges -t ax.tb_sys_user_menu_grant > grant_backup.sql
--  되살릴 때는 덤프를 먼저 적용하고(표+행) V30 을 다시 돌린다(뷰·코드·주석).
--
--  [건드리지 않는 것]
--  부서 권한 ax.tb_sys_dept_menu_perm 의 행. V30 은 그 표에 손대지 않았다(추가 허용 전용).
--
--  [적용]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f rollback/V30__down.sql
--  두 번 실행해도 안전하다.
-- =====================================================================================

-- 1. 유효 권한 뷰 제거 (표보다 먼저 — 뷰가 표를 참조한다) --------------------------------------
DROP VIEW IF EXISTS ax.vw_sys_user_menu_perm;

-- 2. 계정 × 화면 추가 허용 표 제거 ----------------------------------------------------------
--    인덱스 ix_sys_user_menu_grant_menu 는 표와 함께 사라진다.
DROP TABLE IF EXISTS ax.tb_sys_user_menu_grant;

-- 3. 권한 변경 이력 구분 코드 제거 -----------------------------------------------------------
--    tb_sys_perm_log 에 act_cd='USER_MENU_PERM' 인 행이 남아 있으면 fn_check_code_ref() 가
--    그 값을 잡아낸다. 이력은 append-only 라 지우지 않으므로, 남아 있을 때는 코드를 둔다.
DELETE FROM ax.tb_sys_code
 WHERE group_cd = 'SYS_PERM_ACT' AND code = 'USER_MENU_PERM'
   AND NOT EXISTS (SELECT 1 FROM ax.tb_sys_perm_log WHERE act_cd = 'USER_MENU_PERM');

-- 4. 표 주석을 V30 이전 문장으로 되돌린다 -----------------------------------------------------
COMMENT ON TABLE ax.tb_sys_user IS '가입 계정 — 아이디(사번) · 이름 · 부서 · 직급 · 상태만 관리한다. 화면/데이터 접근 권한은 전부 소속 부서 설정을 따른다';
COMMENT ON TABLE ax.tb_sys_dept_menu_perm IS '부서 × 화면 접근 권한 — 행이 없으면 접근 차단. 통합관리자 부서(is_super_admin)는 행 없이 전체 허용으로 판정한다';
