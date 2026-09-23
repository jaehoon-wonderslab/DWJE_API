-- =====================================================================================
--  로컬·개발 전용 — 팀별 메뉴·데이터 접근 권한 샘플 (2026-09-23)
--
--  ⚠️  운영 데이터베이스에 절대 적용하지 마시오. 계정 비밀번호가 공개값(Dwje!2026)이다.
--
--  목적
--  ----
--  권한은 코드가 아니라 DB 가 기준이다(메뉴 ax.tb_sys_dept_menu_perm · 데이터 ax.tb_sys_dept_data_perm ·
--  계정별 추가 허용 ax.tb_sys_user_menu_grant). 기본 시드(seed_local_accounts.sql)의 6개 부서만으로는
--  "화면은 열리는데 값은 가려지는" 경우나 쓰기 권한·계정별 추가 허용 경로를 시험하기 어렵다.
--  시험용 부서 4개와 계정 5개를 두어 경우별로 바로 로그인해 볼 수 있게 한다.
--
--  샘플 (비밀번호 공통: Dwje!2026 · 부서명은 모두 '[샘플]' 로 시작)
--  ┌───────┬──────────────────┬──────────────────────────────┬──────────────────────┬─────────────────────────┐
--  │ 사번  │ 부서             │ 메뉴                         │ 데이터               │ 시험할 것                │
--  ├───────┼──────────────────┼──────────────────────────────┼──────────────────────┼─────────────────────────┤
--  │ 19001 │ [샘플] 품질분석  │ 품질 화면 + 제품별 수율(쓰기)│ qty·yield·customer   │ mold 만 마스킹, 쓰기 버튼 │
--  │ 19002 │ [샘플] 현장조회  │ 대시보드·모니터링·불량 조회  │ qty                  │ 수율·고객사 대량 마스킹   │
--  │ 19003 │ [샘플] 보고서작성│ 보고서 7종 전부(쓰기)        │ price 뺀 6종         │ 단가·금액만 마스킹        │
--  │ 19004 │ [샘플] 신규입사  │ 덕반장 AI 만                 │ 없음                 │ 최소 권한 · 나머지 403    │
--  │ 19005 │ 품질보증팀(기존) │ 부서 권한 + 계정 추가 허용   │ 부서와 같음          │ tb_sys_user_menu_grant   │
--  │       │                  │ (dash-ai · prod-monitor)     │                      │ 경로 (from_grant)        │
--  └───────┴──────────────────┴──────────────────────────────┴──────────────────────┴─────────────────────────┘
--
--  멱등 — 몇 번 돌려도 결과가 같다. 샘플 부서의 권한은 매번 지우고 다시 넣는다(샘플 부서만).
--  되돌리기 : 맨 아래 [샘플 제거] 블록의 주석을 풀어 실행.
--
--  적용 : docker exec -e PGPASSWORD=dwje_local dwje-pg psql -U dwje_local -d dwjedb -v ON_ERROR_STOP=1 -f seed_perm_samples.sql
--  선행 : seed_local_accounts.sql, V42 (메뉴가 웹 menu.js 와 같은 상태)
-- =====================================================================================

\set ON_ERROR_STOP on

BEGIN;

-- ── 1. 샘플 부서 ───────────────────────────────────────────────────────────────────
INSERT INTO ax.tb_sys_dept (dept_nm, dept_abbr, dept_desc, plant_cd, is_super_admin, sort_seq, ins_user, upd_user) VALUES
 ('[샘플] 품질분석',   'SQA', '시험용 — 품질 화면 · mold 마스킹 · 제품별 수율 쓰기', 'PL01', false, 91, 'SEED', 'SEED'),
 ('[샘플] 현장조회',   'SFL', '시험용 — 조회 화면만 · 수량 외 전부 마스킹',           'PL01', false, 92, 'SEED', 'SEED'),
 ('[샘플] 보고서작성', 'SRP', '시험용 — 보고서 7종 쓰기 · 단가만 마스킹',             'PL01', false, 93, 'SEED', 'SEED'),
 ('[샘플] 신규입사',   'SNW', '시험용 — 덕반장 AI 만 · 데이터 권한 없음',              'PL01', false, 94, 'SEED', 'SEED')
ON CONFLICT (dept_nm) DO UPDATE SET dept_desc = EXCLUDED.dept_desc, sort_seq = EXCLUDED.sort_seq;

-- ── 2. 샘플 계정 (비밀번호 'Dwje!2026' — seed_local_accounts.sql 과 같은 해시) ───────
INSERT INTO ax.tb_sys_user (
    user_id, user_nm, dept_id, plant_cd, position_cd, user_state_cd,
    is_switch_target, pwd_hash, pwd_upd_at, email, remark, ins_user, upd_user
)
SELECT v.user_id, v.user_nm, d.dept_id, 'PL01', v.position_cd, 'ACTIVE',
       true, '{pbkdf2-sha512}210000$EfdDuCrdExTT/D+WLja3Mg$En/499a3O0IiOzUAvDYD9YHI1LrcOPVhmqWrFpGtNkcsRfyuos5N+yJGCmgHF8UbUziLfUASqXByoOoyd2n9JQ',
       now(), v.user_id || '@dwje.co.kr', v.remark, 'SEED', 'SEED'
FROM (VALUES
    ('19001', '샘플품질', '[샘플] 품질분석',   'SENIOR', '권한 샘플 — mold 마스킹 · 쓰기'),
    ('19002', '샘플현장', '[샘플] 현장조회',   'STAFF',  '권한 샘플 — 수량만 공개'),
    ('19003', '샘플보고', '[샘플] 보고서작성', 'LEADER', '권한 샘플 — 보고서 쓰기 · 단가 마스킹'),
    ('19004', '샘플신입', '[샘플] 신규입사',   'STAFF',  '권한 샘플 — 최소 권한'),
    ('19005', '샘플추가', '품질보증팀',        'SENIOR', '권한 샘플 — 계정 추가 허용(dash-ai · prod-monitor)')
) AS v(user_id, user_nm, dept_nm, position_cd, remark)
JOIN ax.tb_sys_dept d ON d.dept_nm = v.dept_nm
ON CONFLICT (user_id) DO UPDATE SET dept_id = EXCLUDED.dept_id, remark = EXCLUDED.remark, user_state_cd = 'ACTIVE';

-- ── 3. 샘플 부서의 기존 권한을 비운다 (샘플 부서만) ─────────────────────────────────
DELETE FROM ax.tb_sys_dept_menu_perm p USING ax.tb_sys_dept d
 WHERE p.dept_id = d.dept_id AND d.dept_nm LIKE '[샘플]%';
DELETE FROM ax.tb_sys_dept_data_perm p USING ax.tb_sys_dept d
 WHERE p.dept_id = d.dept_id AND d.dept_nm LIKE '[샘플]%';

-- ── 4. 메뉴 접근 권한 ──────────────────────────────────────────────────────────────
INSERT INTO ax.tb_sys_dept_menu_perm (dept_id, menu_id, can_read, can_write, ins_user, upd_user)
SELECT d.dept_id, v.menu_id, true, v.can_write, 'SEED', 'SEED'
FROM (VALUES
    -- 품질분석 : 품질 화면 + 제품별 수율만 쓰기
    ('[샘플] 품질분석', 'ai-chat', false), ('[샘플] 품질분석', 'qc-defect', false),
    ('[샘플] 품질분석', 'qc-aoi', false),  ('[샘플] 품질분석', 'prod-result', false),
    ('[샘플] 품질분석', 'rpt-yield-model', true), ('[샘플] 품질분석', 'rpt-lrr-customer', false),
    ('[샘플] 품질분석', 'alert-list', false), ('[샘플] 품질분석', 'sys-gloss', false),
    -- 현장조회 : 보기만
    ('[샘플] 현장조회', 'ai-chat', false), ('[샘플] 현장조회', 'dash-ai', false),
    ('[샘플] 현장조회', 'dash-proc', false), ('[샘플] 현장조회', 'prod-monitor', false),
    ('[샘플] 현장조회', 'qc-defect', false), ('[샘플] 현장조회', 'alert-list', false),
    -- 보고서작성 : 보고서 7종 + 이전 보고서, 전부 쓰기
    ('[샘플] 보고서작성', 'ai-chat', false),
    ('[샘플] 보고서작성', 'prod-daily', true), ('[샘플] 보고서작성', 'daily-history', true),
    ('[샘플] 보고서작성', 'rpt-press-morning', true), ('[샘플] 보고서작성', 'rpt-plating-morning', true),
    ('[샘플] 보고서작성', 'rpt-ship-plan', true), ('[샘플] 보고서작성', 'rpt-yield-model', true),
    ('[샘플] 보고서작성', 'rpt-lrr-customer', true), ('[샘플] 보고서작성', 'rpt-scrap', true),
    -- 신규입사 : 첫 화면(덕반장 AI)만
    ('[샘플] 신규입사', 'ai-chat', false)
) AS v(dept_nm, menu_id, can_write)
JOIN ax.tb_sys_dept d ON d.dept_nm = v.dept_nm
JOIN ax.tb_sys_menu m ON m.menu_id = v.menu_id AND m.use_flg = 'Y';

-- ── 5. 데이터 접근 권한 (허용하는 항목만 행으로 둔다 — 기본 시드와 같은 방식) ─────────
INSERT INTO ax.tb_sys_dept_data_perm (dept_id, field_key, is_allowed, ins_user, upd_user)
SELECT d.dept_id, v.field_key, true, 'SEED', 'SEED'
FROM (VALUES
    ('[샘플] 품질분석', 'qty'), ('[샘플] 품질분석', 'yield'), ('[샘플] 품질분석', 'customer'),
    ('[샘플] 현장조회', 'qty'),
    ('[샘플] 보고서작성', 'qty'), ('[샘플] 보고서작성', 'yield'), ('[샘플] 보고서작성', 'customer'),
    ('[샘플] 보고서작성', 'plan'), ('[샘플] 보고서작성', 'mold'), ('[샘플] 보고서작성', 'worker')
    -- [샘플] 신규입사 : 없음
) AS v(dept_nm, field_key)
JOIN ax.tb_sys_dept d ON d.dept_nm = v.dept_nm
JOIN ax.tb_sys_data_field f ON f.field_key = v.field_key;

-- ── 6. 계정별 추가 허용 — 부서 권한에 없는 화면을 한 사람에게만 연다 ──────────────────
DELETE FROM ax.tb_sys_user_menu_grant WHERE user_id = '19005';
INSERT INTO ax.tb_sys_user_menu_grant (user_id, menu_id, can_write, grant_reason, ins_user, upd_user) VALUES
 ('19005', 'dash-ai',      false, '권한 샘플 — 품질보증팀에 없는 AI 통합 대시보드를 개인에게만 허용', 'SEED', 'SEED'),
 ('19005', 'prod-monitor', false, '권한 샘플 — 품질보증팀에 없는 생산 모니터링을 개인에게만 허용',   'SEED', 'SEED');

COMMIT;

-- ── 7. 결과 ────────────────────────────────────────────────────────────────────────
SELECT u.user_id AS 사번, u.user_nm AS 이름, d.dept_nm AS 부서,
       (SELECT string_agg(p.menu_id || CASE WHEN p.can_write THEN '(쓰기)' ELSE '' END, ', ' ORDER BY p.menu_id)
          FROM ax.vw_sys_user_menu_perm p WHERE p.user_id = u.user_id)           AS "유효 화면 권한",
       (SELECT string_agg(p.menu_id, ', ') FROM ax.vw_sys_user_menu_perm p
         WHERE p.user_id = u.user_id AND p.from_grant AND NOT p.from_dept)       AS "계정 추가 허용",
       (SELECT coalesce(string_agg(dp.field_key, ', ' ORDER BY f.sort_seq), '(없음)')
          FROM ax.tb_sys_dept_data_perm dp JOIN ax.tb_sys_data_field f USING (field_key)
         WHERE dp.dept_id = u.dept_id AND dp.is_allowed)                         AS "데이터 권한"
  FROM ax.tb_sys_user u JOIN ax.tb_sys_dept d USING (dept_id)
 WHERE u.user_id BETWEEN '19001' AND '19005'
 ORDER BY u.user_id;

-- ── [샘플 제거] 필요할 때 주석을 풀어 실행 ─────────────────────────────────────────
-- BEGIN;
-- DELETE FROM ax.tb_sys_user_menu_grant WHERE user_id BETWEEN '19001' AND '19005';
-- DELETE FROM ax.tb_sys_user WHERE user_id BETWEEN '19001' AND '19005';
-- DELETE FROM ax.tb_sys_dept WHERE dept_nm LIKE '[샘플]%';   -- 메뉴·데이터 권한은 FK CASCADE
-- COMMIT;
