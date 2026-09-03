-- =====================================================================================
--  회원가입 승인 대기 상태 추가
--
--  배경
--  ----
--  회원가입(POST /api/v1/auth/signup)으로 만들어진 계정은 바로 로그인되면 안 된다.
--  사내 시스템이므로 전산팀이 소속 부서와 신원을 확인한 뒤 활성화한다.
--  기존 SYS_USER_STATE 에는 ACTIVE / SUSPENDED 만 있어 '승인 대기' 상태를 표현할 수 없다.
--
--  상태 전이
--    PENDING  --(전산팀 승인)-->  ACTIVE
--    PENDING  --(반려)-->        SUSPENDED
--
--  적용 : psql -d dwjedb -f V7__account_signup.sql
-- =====================================================================================

SET search_path TO ax, mes, vec, common, public;

INSERT INTO ax.tb_sys_code (group_cd, code, code_nm, code_desc, sort_seq) VALUES
 ('SYS_USER_STATE', 'PENDING', '승인 대기', '회원가입 후 관리자 승인을 기다리는 상태. 로그인할 수 없다.', 3)
ON CONFLICT (group_cd, code) DO NOTHING;

INSERT INTO ax.tb_sys_code_group (group_cd, group_nm, sort_seq) VALUES
 ('SYS_SIGNUP_ACT', '회원가입 처리 구분', 15)
ON CONFLICT (group_cd) DO NOTHING;

INSERT INTO ax.tb_sys_code (group_cd, code, code_nm, sort_seq) VALUES
 ('SYS_SIGNUP_ACT', 'REQUEST', '가입 신청', 1),
 ('SYS_SIGNUP_ACT', 'APPROVE', '가입 승인', 2),
 ('SYS_SIGNUP_ACT', 'REJECT',  '가입 반려', 3),
 ('SYS_SIGNUP_ACT', 'PWD_CHANGE', '비밀번호 변경', 4)
ON CONFLICT (group_cd, code) DO NOTHING;
