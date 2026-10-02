-- =====================================================================================
--  V52 : 그룹웨어 부서 매핑 보강 — 가입 제외 행 CHECK · 미배정 부서 설명 갱신 (2026-10-01)
--
--  [배경]
--  · 가입 제외(join_yn = N) 매핑 행에 AX 부서를 넣을 수 있었다. 서비스 규칙만 있고 DB 에는 없었다.
--  · 미배정 부서 설명이 「화면 권한 없음」 으로 남아 있었다. V49 로 고정 5개 화면 조회 전용이 됐다.
--  기획: 02 그룹웨어 부서 매핑 4.5 (GWD-05 · GWD-08 · GWD-13, 공통 묶음 M-2).
--  같은 기획의 쓰기 권한 이관(sys-gw-dept 전산팀)은 V49 에 이미 들어 있어 여기서는 뺐다.
--
--  [이 파일이 하는 일]
--   1. 위반 행이 있으면 중단(정리는 사람이 한다)
--   2. CHECK ck_sys_dept_gw_map_excluded — join_yn = 'Y' OR dept_id IS NULL
--   3. 미배정 부서 dept_desc 갱신 — 부서명은 바꾸지 않는다(엔진·API 가 이름으로 찾음)
--   4. 표 주석에 관리 화면 이름 추가
--
--  [실행]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f V52__gw_dept_map_constraint.sql
--  두 번 실행해도 안전하다. 되돌리기는 rollback/V52__down.sql.
-- =====================================================================================

BEGIN;

-- ── 1. 선행 확인 ─────────────────────────────────────────────────────────────────────
DO $$
DECLARE
    v_bad integer;
BEGIN
    SELECT count(*) INTO v_bad FROM ax.tb_sys_dept_gw_map WHERE join_yn = 'N' AND dept_id IS NOT NULL;
    IF v_bad > 0 THEN
        RAISE EXCEPTION 'V52 중단: join_yn = N 인데 dept_id 가 있는 매핑 행이 % 건 있습니다. 먼저 정리하십시오.', v_bad;
    END IF;
END $$;

-- ── 2. CHECK ─────────────────────────────────────────────────────────────────────────
ALTER TABLE ax.tb_sys_dept_gw_map DROP CONSTRAINT IF EXISTS ck_sys_dept_gw_map_excluded;
ALTER TABLE ax.tb_sys_dept_gw_map
    ADD CONSTRAINT ck_sys_dept_gw_map_excluded CHECK (join_yn = 'Y' OR dept_id IS NULL);
COMMENT ON CONSTRAINT ck_sys_dept_gw_map_excluded ON ax.tb_sys_dept_gw_map IS
  '가입 제외(join_yn=N) 행은 AX 부서를 두지 않음 — 서비스 규칙을 DB 에도 둠';

-- ── 3. 미배정 부서 설명 ──────────────────────────────────────────────────────────────
UPDATE ax.tb_sys_dept
   SET dept_desc = '그룹웨어 자동 가입 계정 중 부서 매핑이 없는 사람 — 고정 5개 화면(dash-ai·dash-proc·prod-monitor·ai-chat·chat-history) 조회 전용, 데이터 접근 권한 0건, 권한 변경 불가. 그룹웨어 부서 매핑 화면에서 실제 부서로 옮김. 이름을 바꾸면 자동 가입이 멈춤',
       upd_date = now(), upd_user = 'V52'
 WHERE dept_nm = '미배정'
   AND dept_desc IS DISTINCT FROM '그룹웨어 자동 가입 계정 중 부서 매핑이 없는 사람 — 고정 5개 화면(dash-ai·dash-proc·prod-monitor·ai-chat·chat-history) 조회 전용, 데이터 접근 권한 0건, 권한 변경 불가. 그룹웨어 부서 매핑 화면에서 실제 부서로 옮김. 이름을 바꾸면 자동 가입이 멈춤';

-- ── 4. 표 주석 ───────────────────────────────────────────────────────────────────────
COMMENT ON TABLE ax.tb_sys_dept_gw_map IS
  '그룹웨어 부서명 → AX 부서 매핑 — 자동 가입 때 부서를 정하는 기준, 가입 순간에만 사용. 관리 화면은 그룹웨어 부서 매핑';

COMMIT;
