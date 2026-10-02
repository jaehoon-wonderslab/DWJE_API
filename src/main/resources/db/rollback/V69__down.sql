-- =====================================================================================
--  V69 되돌리기 — 부서 약칭 컬럼 복원 (2026-10-02)
--
--  · dept_abbr 를 NULL 허용으로 되살리고(V68 상태) 보관 표 ax.tb_sys_dept_abbr_bak 에서 값을 되돌린다.
--  · 보관 표는 지우지 않는다(다시 V69 를 적용할 때 덮어씀).
--  · V69 이후 새로 만든 부서는 보관 값이 없어 NULL 로 남는다. V68 까지 되돌리면 V68 되돌리기가 채운다.
--  · 주석은 V68 의 「사용 중지」 문구.
--
--  [적용]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f rollback/V69__down.sql
--  두 번 실행해도 안전하다.
-- =====================================================================================

BEGIN;

DO $$
DECLARE
    v_cnt integer := 0;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'ax' AND table_name = 'tb_sys_dept' AND column_name = 'dept_abbr') THEN
        ALTER TABLE ax.tb_sys_dept ADD COLUMN dept_abbr varchar(4);
        IF to_regclass('ax.tb_sys_dept_abbr_bak') IS NOT NULL THEN
            EXECUTE $q$
                UPDATE ax.tb_sys_dept d SET dept_abbr = b.dept_abbr
                  FROM ax.tb_sys_dept_abbr_bak b
                 WHERE b.dept_id = d.dept_id
            $q$;
            GET DIAGNOSTICS v_cnt = ROW_COUNT;
        END IF;
        RAISE NOTICE 'V69 되돌리기: 약칭 컬럼 복원, 값 되돌림 % 건', v_cnt;
    ELSE
        RAISE NOTICE 'V69 되돌리기: dept_abbr 컬럼이 이미 있어 건너뜀';
    END IF;

    COMMENT ON COLUMN ax.tb_sys_dept.dept_abbr IS
      '부서 약칭 — 사용 중지(2026-10-02). 화면 · API 에서 쓰지 않고 새 부서는 NULL. 컬럼 삭제 예정(값은 보관 표 tb_sys_dept_abbr_bak 로 옮김)';
END $$;

COMMIT;
