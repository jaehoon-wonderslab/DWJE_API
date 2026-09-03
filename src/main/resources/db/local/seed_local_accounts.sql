-- =====================================================================================
--  로컬·개발 전용 계정/권한 시드
--
--  ⚠️  운영 데이터베이스에 절대 적용하지 마시오.
--      모든 계정이 동일한 공개 비밀번호를 사용합니다. 실 서버에는 SSO 또는
--      개별 발급 비밀번호로 계정을 생성해야 합니다.
--
--  목적
--  ----
--  기존 DDL 에는 부서·계정·권한 시드가 없어, 스키마만 적용하면 **로그인할 수 있는 계정이
--  하나도 없다.** 그러면 Swagger UI 를 열어도 토큰을 얻지 못해 모든 API 가 401 이 된다.
--  이 스크립트는 명세의 부서 6종과 부서별 권한을 그대로 넣어 API 를 바로 시험할 수 있게 한다.
--
--  근거
--  ----
--  · 데이터 권한  : API 목록 「공통 규약 / 5. 부서별 데이터 권한 기본값」
--  · 메뉴 권한    : 기능 및 API 명세 「화면-API 매핑」 시트의 접근 부서
--
--  선행 조건 : mes_db_query.sql → ai_db_query.sql → ai_db_vector_query.sql
--              → V2__ax_report_extension.sql → V3__report_menu.sql
--
--  적용 : psql -d dwjedb -f seed_local_accounts.sql
--
--  로그인 계정 (비밀번호 공통: Dwje!2026)
--    10000 관리자   / 통합관리자   — 전 화면·전 데이터
--    10001 김품질   / 품질보증팀
--    10002 박생산   / 생산관리팀
--    10003 이제조   / 제조팀
--    10004 최전산   / 전산팀       — 시스템관리 화면
--    10005 정경영   / 경영진
-- =====================================================================================

SET search_path TO ax, mes, vec, common, public;

-- -------------------------------------------------------------------------------------
-- 1. 부서
-- -------------------------------------------------------------------------------------
INSERT INTO ax.tb_sys_dept (dept_nm, dept_abbr, dept_desc, plant_cd, is_super_admin, sort_seq, ins_user, upd_user) VALUES
 ('통합관리자',   '관리', '전 화면·전 데이터 접근',            'PL01', true,  1, 'SEED', 'SEED'),
 ('품질보증팀',   '품보', '품질 지표·보고서·AOI 분석',          'PL01', false, 2, 'SEED', 'SEED'),
 ('생산관리팀',   '생관', '생산 계획·실적·일일보고',            'PL01', false, 3, 'SEED', 'SEED'),
 ('제조팀',       '제조', '설비 운전·비가동 등록',              'PL01', false, 4, 'SEED', 'SEED'),
 ('전산팀',       '전산', '시스템·권한·AI 운영 관리',           'PL01', false, 5, 'SEED', 'SEED'),
 ('경영진',       '경영', '성과지표·보고서 열람',               'PL01', false, 6, 'SEED', 'SEED')
ON CONFLICT (dept_nm) DO NOTHING;

-- -------------------------------------------------------------------------------------
-- 2. 계정 (비밀번호는 전부 'Dwje!2026' — PBKDF2-HMAC-SHA512 해시)
-- -------------------------------------------------------------------------------------
INSERT INTO ax.tb_sys_user (
    user_id, user_nm, dept_id, plant_cd, position_cd, user_state_cd,
    is_switch_target, pwd_hash, pwd_upd_at, email, ins_user, upd_user
)
SELECT v.user_id, v.user_nm, d.dept_id, 'PL01', v.position_cd, 'ACTIVE',
       true, '{pbkdf2-sha512}210000$EfdDuCrdExTT/D+WLja3Mg$En/499a3O0IiOzUAvDYD9YHI1LrcOPVhmqWrFpGtNkcsRfyuos5N+yJGCmgHF8UbUziLfUASqXByoOoyd2n9JQ', now(), v.user_id || '@dwje.co.kr', 'SEED', 'SEED'
FROM (VALUES
    ('10000', '관리자', '통합관리자', 'ADMIN'),
    ('10001', '김품질', '품질보증팀', 'SENIOR'),
    ('10002', '박생산', '생산관리팀', 'LEADER'),
    ('10003', '이제조', '제조팀',     'FOREMAN'),
    ('10004', '최전산', '전산팀',     'SENIOR'),
    ('10005', '정경영', '경영진',     'DIRECTOR')
) AS v(user_id, user_nm, dept_nm, position_cd)
JOIN ax.tb_sys_dept d ON d.dept_nm = v.dept_nm
ON CONFLICT (user_id) DO NOTHING;

-- -------------------------------------------------------------------------------------
-- 3. 데이터 접근 권한 — API 목록 「공통 규약 / 5. 부서별 데이터 권한 기본값」
--
--    부서        | 허용 항목
--    품질보증팀  | qty, yield, customer, mold
--    생산관리팀  | qty, yield, plan, customer, mold, worker
--    제조팀      | qty, mold, worker
--    전산팀      | qty, worker
--    경영진      | 전체 7종
--    통합관리자  | 전체 7종 (is_super_admin 으로도 우회되나 매트릭스 표기를 위해 함께 등록)
-- -------------------------------------------------------------------------------------
INSERT INTO ax.tb_sys_dept_data_perm (dept_id, field_key, is_allowed, ins_user, upd_user)
SELECT d.dept_id, v.field_key, true, 'SEED', 'SEED'
FROM (VALUES
    ('품질보증팀','qty'), ('품질보증팀','yield'), ('품질보증팀','customer'), ('품질보증팀','mold'),

    ('생산관리팀','qty'), ('생산관리팀','yield'), ('생산관리팀','plan'),
    ('생산관리팀','customer'), ('생산관리팀','mold'), ('생산관리팀','worker'),

    ('제조팀','qty'), ('제조팀','mold'), ('제조팀','worker'),

    ('전산팀','qty'), ('전산팀','worker'),

    ('경영진','qty'), ('경영진','yield'), ('경영진','price'), ('경영진','customer'),
    ('경영진','plan'), ('경영진','mold'), ('경영진','worker'),

    ('통합관리자','qty'), ('통합관리자','yield'), ('통합관리자','price'), ('통합관리자','customer'),
    ('통합관리자','plan'), ('통합관리자','mold'), ('통합관리자','worker')
) AS v(dept_nm, field_key)
JOIN ax.tb_sys_dept d ON d.dept_nm = v.dept_nm
ON CONFLICT (dept_id, field_key) DO NOTHING;

-- -------------------------------------------------------------------------------------
-- 4. 메뉴 접근 권한 — 「화면-API 매핑」 시트의 접근 부서
-- -------------------------------------------------------------------------------------

-- 4-1. 전 부서 공통 화면
INSERT INTO ax.tb_sys_dept_menu_perm (dept_id, menu_id, can_read, can_write, ins_user, upd_user)
SELECT d.dept_id, m.menu_id, true, false, 'SEED', 'SEED'
FROM ax.tb_sys_dept d
CROSS JOIN (VALUES ('ai-chat'), ('dash-ai'), ('dash-proc'), ('alert-list'), ('sys-gloss'), ('chat-history')) AS m(menu_id)
WHERE d.use_flg = 'Y'
ON CONFLICT (dept_id, menu_id) DO NOTHING;

-- 4-2. 부서 지정 화면
INSERT INTO ax.tb_sys_dept_menu_perm (dept_id, menu_id, can_read, can_write, ins_user, upd_user)
SELECT d.dept_id, v.menu_id, true, false, 'SEED', 'SEED'
FROM (VALUES
    -- 대시보드 (제조팀 제외)
    ('dash-kpi','품질보증팀'), ('dash-kpi','생산관리팀'), ('dash-kpi','전산팀'), ('dash-kpi','경영진'), ('dash-kpi','통합관리자'),

    -- 생산관리
    ('prod-monitor','품질보증팀'), ('prod-monitor','생산관리팀'), ('prod-monitor','제조팀'), ('prod-monitor','통합관리자'),
    ('prod-result','품질보증팀'), ('prod-result','생산관리팀'), ('prod-result','경영진'), ('prod-result','통합관리자'),
    ('prod-daily','생산관리팀'), ('prod-daily','통합관리자'),
    ('daily-history','생산관리팀'), ('daily-history','통합관리자'),
    ('prod-down','생산관리팀'), ('prod-down','제조팀'), ('prod-down','통합관리자'),

    -- 품질관리
    ('qc-defect','품질보증팀'), ('qc-defect','생산관리팀'), ('qc-defect','제조팀'), ('qc-defect','경영진'), ('qc-defect','통합관리자'),
    ('qc-aoi','품질보증팀'), ('qc-aoi','생산관리팀'), ('qc-aoi','제조팀'), ('qc-aoi','통합관리자'),
    ('qc-report','품질보증팀'), ('qc-report','생산관리팀'), ('qc-report','통합관리자'),
    ('report-forms','품질보증팀'), ('report-forms','생산관리팀'), ('report-forms','통합관리자'),

    -- 보고서 (V3__report_menu.sql 로 등록된 화면)
    ('rpt-press-morning','생산관리팀'), ('rpt-press-morning','제조팀'), ('rpt-press-morning','통합관리자'),
    ('rpt-plating-morning','생산관리팀'), ('rpt-plating-morning','제조팀'), ('rpt-plating-morning','통합관리자'),
    ('rpt-ship-plan','생산관리팀'), ('rpt-ship-plan','경영진'), ('rpt-ship-plan','통합관리자'),
    ('rpt-yield-model','품질보증팀'), ('rpt-yield-model','경영진'), ('rpt-yield-model','통합관리자'),
    ('rpt-lrr-customer','품질보증팀'), ('rpt-lrr-customer','경영진'), ('rpt-lrr-customer','통합관리자'),
    ('rpt-scrap','품질보증팀'), ('rpt-scrap','생산관리팀'), ('rpt-scrap','경영진'), ('rpt-scrap','통합관리자'),
    ('rpt-scrap-new','품질보증팀'), ('rpt-scrap-new','생산관리팀'), ('rpt-scrap-new','경영진'), ('rpt-scrap-new','통합관리자'),

    -- 시스템관리 (전산팀·통합관리자)
    ('sys-account','전산팀'), ('sys-account','통합관리자'),
    ('sys-menu','전산팀'), ('sys-menu','통합관리자'),
    ('sys-data','전산팀'), ('sys-data','통합관리자'),
    ('alert-cond','전산팀'), ('alert-cond','통합관리자'),
    ('sys-recip','전산팀'), ('sys-recip','통합관리자'),
    ('sys-audit','전산팀'), ('sys-audit','통합관리자'),
    ('base-model','전산팀'), ('base-model','통합관리자'),
    ('ai-agent','전산팀'), ('ai-agent','통합관리자'),
    ('sys-metric','전산팀'), ('sys-metric','통합관리자'),
    ('sys-dl','전산팀'), ('sys-dl','통합관리자'),
    ('sys-sync','전산팀'), ('sys-sync','통합관리자'),
    ('sys-model-ver','전산팀'), ('sys-model-ver','통합관리자'),

    -- 제품군 순위 (전산팀·경영진·통합관리자)
    ('sys-rank','전산팀'), ('sys-rank','경영진'), ('sys-rank','통합관리자')
) AS v(menu_id, dept_nm)
JOIN ax.tb_sys_dept d ON d.dept_nm = v.dept_nm
WHERE EXISTS (SELECT 1 FROM ax.tb_sys_menu m WHERE m.menu_id = v.menu_id)
ON CONFLICT (dept_id, menu_id) DO NOTHING;

-- -------------------------------------------------------------------------------------
-- 5. 적용 결과 확인
-- -------------------------------------------------------------------------------------
SELECT d.dept_nm AS "부서",
       count(DISTINCT u.user_id)  AS "계정",
       count(DISTINCT mp.menu_id) AS "메뉴권한",
       count(DISTINCT dp.field_key) AS "데이터권한"
FROM ax.tb_sys_dept d
LEFT JOIN ax.tb_sys_user u             ON u.dept_id  = d.dept_id
LEFT JOIN ax.tb_sys_dept_menu_perm mp  ON mp.dept_id = d.dept_id
LEFT JOIN ax.tb_sys_dept_data_perm dp  ON dp.dept_id = d.dept_id AND dp.is_allowed
GROUP BY d.dept_nm, d.sort_seq
ORDER BY d.sort_seq;
