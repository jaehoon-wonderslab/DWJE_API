-- =====================================================================================
--  V68 : 부서 약칭(dept_abbr) 사용 중지 — NOT NULL · UNIQUE 제약 완화 (2026-10-02)
--
--  [배경]
--  사용자 요청으로 계정 관리 화면 부서 탭의 「약칭」 기능을 DB · API · WEB 에서 모두 없앤다.
--  컬럼이 NOT NULL · UNIQUE 라 새 API 가 약칭을 넣지 않으면 부서 등록이 실패하고, 컬럼을 먼저 지우면
--  옛 API 의 /auth/me 가 dept_abbr 를 읽다가 500 이 된다. 그래서 두 단계로 나눈다.
--    V68(이 파일) 제약 완화 → API 배포(약칭을 읽지도 쓰지도 않음) → WEB 배포 → V69 컬럼 삭제
--  V68 상태에서는 옛 API · 새 API 가 모두 동작한다.
--
--  [이 파일이 하는 일]
--   1. ax.tb_sys_dept.dept_abbr — NOT NULL 해제
--   2. 유일 제약 uq_tb_sys_dept_abbr 삭제(새 부서는 약칭 없이 NULL 로 들어감)
--   3. 컬럼 주석 — 사용 중지 표시
--  기존 값은 그대로 둔다(V69 가 보관 표에 옮긴 뒤 컬럼을 지움).
--
--  [실행]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f V68__dept_abbr_relax.sql
--  두 번 실행해도 안전하다. 되돌리기는 rollback/V68__down.sql.
-- =====================================================================================

BEGIN;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = 'ax' AND table_name = 'tb_sys_dept' AND column_name = 'dept_abbr') THEN
        ALTER TABLE ax.tb_sys_dept ALTER COLUMN dept_abbr DROP NOT NULL;
        ALTER TABLE ax.tb_sys_dept DROP CONSTRAINT IF EXISTS uq_tb_sys_dept_abbr;
        COMMENT ON COLUMN ax.tb_sys_dept.dept_abbr IS
          '부서 약칭 — 사용 중지(2026-10-02). 화면 · API 에서 쓰지 않고 새 부서는 NULL. 컬럼 삭제 예정(값은 보관 표 tb_sys_dept_abbr_bak 로 옮김)';
    ELSE
        RAISE NOTICE 'V68: dept_abbr 컬럼이 이미 없어 건너뜀(V69 적용됨)';
    END IF;
END $$;

COMMIT;
