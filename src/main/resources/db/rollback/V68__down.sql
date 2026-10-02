-- =====================================================================================
--  V68 되돌리기 — 부서 약칭 NOT NULL · UNIQUE 복원 (2026-10-02)
--
--  · V69 가 적용돼 컬럼이 없으면 아무것도 하지 않는다(V69 를 먼저 되돌린다).
--  · 약칭이 비어 있는 부서(V68 이후 새로 만든 부서)에 겹치지 않는 4자 이내 값을 채운 뒤 제약을 되돌린다.
--      1순위 'D' || dept_id (예: D61) — 4자를 넘거나 이미 쓰이면
--      2순위 'X' || 16진수 3자리(X001, X002 …) 중 비어 있는 첫 값
--    채운 부서는 권한 변경 이력이 아니라 NOTICE 로만 알린다(옛 API 는 약칭을 화면 배지로만 씀).
--  · 주석은 V68 직전 문구로.
--
--  [적용]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f rollback/V68__down.sql
--  두 번 실행해도 안전하다.
-- =====================================================================================

BEGIN;

DO $$
DECLARE
    r    record;
    cand text;
    n    integer;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'ax' AND table_name = 'tb_sys_dept' AND column_name = 'dept_abbr') THEN
        RAISE NOTICE 'V68 되돌리기: dept_abbr 컬럼이 없어 건너뜀(V69 를 먼저 되돌리십시오)';
        RETURN;
    END IF;

    FOR r IN SELECT dept_id, dept_nm FROM ax.tb_sys_dept WHERE dept_abbr IS NULL ORDER BY dept_id LOOP
        cand := 'D' || r.dept_id;
        IF length(cand) > 4 OR EXISTS (SELECT 1 FROM ax.tb_sys_dept WHERE dept_abbr = cand) THEN
            n := 1;
            LOOP
                cand := 'X' || lpad(to_hex(n), 3, '0');
                EXIT WHEN NOT EXISTS (SELECT 1 FROM ax.tb_sys_dept WHERE dept_abbr = cand);
                n := n + 1;
                IF n > 4095 THEN
                    RAISE EXCEPTION 'V68 되돌리기 중단: 부서 % 에 줄 약칭 후보가 없습니다.', r.dept_id;
                END IF;
            END LOOP;
        END IF;
        UPDATE ax.tb_sys_dept SET dept_abbr = cand WHERE dept_id = r.dept_id;
        RAISE NOTICE 'V68 되돌리기: 부서 % (%) 약칭을 % 로 채움', r.dept_id, r.dept_nm, cand;
    END LOOP;

    IF EXISTS (SELECT 1 FROM ax.tb_sys_dept GROUP BY dept_abbr HAVING count(*) > 1) THEN
        RAISE EXCEPTION 'V68 되돌리기 중단: 같은 약칭을 가진 부서가 있습니다. 먼저 정리하십시오.';
    END IF;

    ALTER TABLE ax.tb_sys_dept ALTER COLUMN dept_abbr SET NOT NULL;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uq_tb_sys_dept_abbr') THEN
        ALTER TABLE ax.tb_sys_dept ADD CONSTRAINT uq_tb_sys_dept_abbr UNIQUE (dept_abbr);
    END IF;
    COMMENT ON COLUMN ax.tb_sys_dept.dept_abbr IS '부서 약칭 — 화면 배지 표기, 최대 4자';
END $$;

COMMIT;
