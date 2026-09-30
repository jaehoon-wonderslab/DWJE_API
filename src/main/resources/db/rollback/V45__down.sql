-- =====================================================================================
--  V45 되돌리기 — 그룹웨어 인사정보 자동 가입 구조 제거 (2026-09-30)
--
--  V45__groupware_auto_join.sql 이 만든 매핑표·미배정 부서·avata 컬럼을 지운다.
--
--  [먼저 할 일 — 자동 가입된 계정]
--  미배정 부서에 계정이 남아 있으면 부서를 지울 수 없다(FK). 이 파일은 계정을 지우지 않고 멈춘다.
--  자동 가입 계정까지 없애려면 먼저 아래로 대상을 보고 사람이 정리한다.
--      SELECT u.user_id, u.user_nm, d.dept_nm, u.ins_date
--        FROM ax.tb_sys_user u JOIN ax.tb_sys_dept d USING (dept_id)
--       WHERE u.remark = '그룹웨어 자동 가입' ORDER BY u.ins_date;
--  엔진도 함께 멈추거나 migration.groupware.ax-join.enabled=false 로 둔다. 그대로 두면 다음 동기화에서
--  매핑표가 없어 가입 단계가 실패한다(인사정보 반영까지 롤백됨).
--
--  [적용]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f rollback/V45__down.sql
--  두 번 실행해도 안전하다.
-- =====================================================================================

BEGIN;

DO $$
DECLARE n integer;
BEGIN
    SELECT count(*) INTO n FROM ax.tb_sys_user u JOIN ax.tb_sys_dept d USING (dept_id) WHERE d.dept_nm = '미배정';
    IF n > 0 THEN
        RAISE EXCEPTION '미배정 부서에 계정 %건이 남아 있어 되돌리지 않습니다 — 계정을 다른 부서로 옮기거나 정리한 뒤 다시 실행', n;
    END IF;
END $$;

DROP TABLE IF EXISTS ax.tb_sys_dept_gw_map;
DELETE FROM ax.tb_sys_dept WHERE dept_nm = '미배정';
ALTER TABLE ax.tb_sys_user DROP COLUMN IF EXISTS avata;

COMMIT;
