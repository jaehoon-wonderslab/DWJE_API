-- =====================================================================================
--  V69 : 부서 약칭(dept_abbr) 컬럼 삭제 — 값은 보관 표로 (2026-10-02)
--
--  [적용 순서 — 반드시 지킬 것]
--  V68 → API 배포(약칭을 읽지도 쓰지도 않는 코드) → WEB 배포 → 이 파일.
--  옛 API 가 떠 있는 채로 적용하면 /auth/me 가 dept_abbr 를 읽다가 500 이 되어 로그인할 수 없다.
--
--  [이 파일이 하는 일]
--   1. 보관 표 ax.tb_sys_dept_abbr_bak — 부서 ID · 약칭 · 보관 시각. 약칭이 있는 부서만 옮긴다
--      (다시 실행하면 같은 부서는 값을 새로 덮음)
--   2. ax.tb_sys_dept.dept_abbr 컬럼 삭제(유일 제약은 V68 에서 이미 지움)
--   컬럼이 이미 없으면 아무것도 하지 않는다.
--
--  [실행]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f V69__dept_abbr_drop.sql
--  두 번 실행해도 안전하다. 되돌리기는 rollback/V69__down.sql(컬럼을 NULL 허용으로 되살리고 보관 표에서 값을 되돌림).
-- =====================================================================================

BEGIN;

CREATE TABLE IF NOT EXISTS ax.tb_sys_dept_abbr_bak (
    dept_id   integer     NOT NULL,
    dept_abbr varchar(4),
    backed_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_tb_sys_dept_abbr_bak PRIMARY KEY (dept_id)
);
COMMENT ON TABLE  ax.tb_sys_dept_abbr_bak           IS '부서 약칭 보관 — 약칭 컬럼(tb_sys_dept.dept_abbr)을 지울 때 남긴 값. 되돌리기 스크립트가 이 값으로 컬럼을 되살림';
COMMENT ON COLUMN ax.tb_sys_dept_abbr_bak.dept_id   IS '부서 ID (ax.tb_sys_dept 논리 참조, FK 없음 — 부서가 지워져도 보관 값은 남김)';
COMMENT ON COLUMN ax.tb_sys_dept_abbr_bak.dept_abbr IS '지우기 직전의 부서 약칭';
COMMENT ON COLUMN ax.tb_sys_dept_abbr_bak.backed_at IS '보관한 시각';

DO $$
DECLARE
    v_cnt integer;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'ax' AND table_name = 'tb_sys_dept' AND column_name = 'dept_abbr') THEN
        RAISE NOTICE 'V69: dept_abbr 컬럼이 이미 없어 건너뜀';
        RETURN;
    END IF;

    EXECUTE $q$
        INSERT INTO ax.tb_sys_dept_abbr_bak (dept_id, dept_abbr, backed_at)
        SELECT dept_id, dept_abbr, now() FROM ax.tb_sys_dept WHERE dept_abbr IS NOT NULL
        ON CONFLICT (dept_id) DO UPDATE SET dept_abbr = EXCLUDED.dept_abbr, backed_at = EXCLUDED.backed_at
    $q$;
    GET DIAGNOSTICS v_cnt = ROW_COUNT;
    RAISE NOTICE 'V69: 약칭 보관 % 건', v_cnt;

    ALTER TABLE ax.tb_sys_dept DROP CONSTRAINT IF EXISTS uq_tb_sys_dept_abbr;
    ALTER TABLE ax.tb_sys_dept DROP COLUMN dept_abbr;
END $$;

COMMIT;
