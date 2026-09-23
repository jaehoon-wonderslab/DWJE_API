-- =====================================================================================
--  V42 : 웹 메뉴 고정 — DB 메뉴를 웹과 맞추고, 웹이 쓰지 않는 표를 정리 (2026-09-23)
--
--  ┌─ 실행 방법 (원격 서버에서 이 파일 하나만 돌리면 된다) ────────────────────────────┐
--  │                                                                                  │
--  │   0) 먼저 데이터가 있는 표를 덤프한다 (되돌릴 때 필요 — 아래 [되돌리기] 참고)      │
--  │   1) psql -h localhost -U dwje_local -d dwjedb -f V42__menu_fix_and_unused_tables.sql
--  │                                                                                  │
--  │   · 이 파일이 BEGIN/COMMIT 을 직접 들고 있으므로 --single-transaction 은 붙이지 않는다.
--  │   · 중간에 하나라도 실패하면 ON_ERROR_STOP 으로 전체가 롤백된다(부분 적용 없음).
--  │   · 두 번 실행해도 안전하다(IF EXISTS · 값 비교 UPDATE).                          │
--  │   · 되돌리기 : rollback/V42__down.sql                                             │
--  └──────────────────────────────────────────────────────────────────────────────────┘
--
--  [배경]
--  웹 대메뉴·하위 메뉴를 WEB `src/shared/constants/menu.js` 기준으로 고정한다.
--  DB 의 ax.tb_sys_menu 는 프로토타입 시절 구성(생산관리·품질관리 분리, route_path='#id')에 머물러
--  [시스템관리 > 계정 관리] 의 메뉴 선택 목록이 옛 그룹으로 보였다.
--
--  [근거 — 2026-09-23 전수 조사]
--   · WEB  : app/ 의 라우트 27개에서 import 를 따라가 실제로 부르는 API 180건을 뽑았다.
--   · API  : 엔드포인트 264건 × 테이블(컨트롤러→서비스→리포지토리 SQL 추적).
--   · 엔진 : Alert_Engine(표 26 + 뷰 1), MES_migration_engine(mes 13 · ax.tb_sync_* 5 · mes_stg).
--   · LLM  : LLM_fine_tuning · sllm_fine_tuning 런타임 코드(vec.* · mes.* · tb_sys_* 일부).
--   아래 표는 "웹이 부르는 API · 엔진 · LLM 어느 쪽도 쓰지 않는다" 가 확인된 것이다.
--   웹이 부르지 않던 API 30건은 같은 날 API 코드에서 제거했다(API 도 함께 배포).
--
--  [무엇을 바꾸는가]
--   1) 메뉴 정렬     — 그룹 6개·화면 28개를 웹과 같게. '생산 및 품질 관리'(operation) 신설,
--                      production·quality 그룹 폐지, route_path 를 실제 URL 로.
--   2) 숨김 메뉴 10  — use_flg='N' 인 화면 행 삭제. 부서 권한 행은 FK CASCADE 로 함께 지워진다.
--                      dash-kpi · prod-down · qc-report · report-forms · rpt-scrap-new · sys-rank ·
--                      base-model · ai-agent · sys-metric · sys-model-ver  (+ tb_rpt_report 의 RPT_QUALITY)
--   3) AI 서비스 버전 관리 계열 — 표 4 · 뷰 3 · 함수 2 · 트리거 1 (전부 0행)
--        tb_ai_serving_asset · tb_ai_serving_route · tb_ai_corpus_snapshot · tb_ai_model_asset
--        vw_serving_active · vw_serving_profile_detail · vw_serving_asset_health
--        fn_guard_serving_state(+trg_guard_serving_state) · fn_resolve_serving_profile
--      ax.tb_ai_serving_profile 은 남긴다(/auth/me · AI 대시보드가 읽는다). corpus_snapshot 을
--      가리키던 FK 만 뗀다 — corpus_snapshot_id 컬럼은 남는다(값 보존, 정리는 다음 회차).
--   4) 불량 태그     — tb_ai_defect_tag · tb_ai_defect_tag_map (0행)
--   5) 웹 미사용 기능 표 — tb_sys_user_favorite(즐겨찾기) · tb_rpt_write_state(보고서 작성 상태) ·
--                      tb_aoi_defect_image(AOI 사진 — 09-14 에 MSSQL DIMENSION 으로 전환됨)
--   6) 미참조 표     — tb_ai_chat_term(0행) · tb_rpt_unmask_req(마스킹 해제 요청 — 화면 없음)
--   7) 위 표 전용 공통코드 8그룹과 코드 참조 9행 (+ tb_ai_serving_route.service_cd → AI_SERVICE 참조 1행.
--      AI_SERVICE 그룹은 tb_ai_serving_profile 이 계속 쓰므로 남는다)
--      + V38 이 남긴 끊긴 참조(tb_prod_item_price.price_kind_cd)와 그 코드 그룹 PRICE_KIND
--
--  [일부러 남기는 것]
--   · vec.* 전부              — LLM 서비스 소관이고 실데이터가 있다(V38 과 같은 판단).
--   · tb_rpt_report·tb_rpt_form — vec.tb_doc 가 FK 로 가리키고, 사용 중인 tb_rpt_form_field 가 딸려 있다.
--   · tb_met_metric_* · tb_prod_downtime · tb_ai_agent* — 웹 화면은 없어졌지만 Alert_Engine 이 읽고 쓴다.
--   · tb_sys_code_ref · vw_lot_trace · vw_user_data_perm — LLM·Alert_Engine 이 쓴다.
--   · mes_stg.*               — MES_migration_engine 의 배치 임시표. 운영에서는 배치가 끝날 때 스스로 지운다.
--
--  [안전장치]
--   · 로컬에서 0행이던 표에 운영 데이터가 있으면 **중단한다**(사람 확인 전에는 지우지 않는다).
--   · 이 파일이 모르는 메뉴 행이 운영에 있으면 **중단한다**(정렬 기준이 어긋났다는 뜻).
--   · DROP 은 CASCADE 를 쓰지 않는다 — 예상 못 한 의존 객체가 있으면 실패하고 전체 롤백된다.
--
--  [되돌리기]
--  구조·메뉴·권한·공통코드는 rollback/V42__down.sql. 업무 데이터는 적용 전 덤프에서 되돌린다 —
--  로컬 기준 즐겨찾기 5 · 작성 상태 2 · AOI 사진 2 · 해제 요청 1행이고, 운영은 다를 수 있다. 반드시 먼저 덤프한다.
--      pg_dump -U <user> -d <db> --no-owner --no-privileges --data-only \
--        -t ax.tb_sys_user_favorite -t ax.tb_rpt_write_state -t ax.tb_aoi_defect_image \
--        -t ax.tb_rpt_unmask_req -t ax.tb_sys_dept_menu_perm -t ax.tb_sys_menu \
--        -t ax.tb_sys_menu_group -t ax.tb_rpt_report > v42_backup.sql
--
--  [배포 순서]
--  API(2026-09-23 빌드) 를 먼저 올리는 것이 원칙이다. 지워지는 표를 읽던 API 는 웹이 부르지 않는 것뿐이라
--  표 삭제로 화면이 깨지지는 않는다. 단 아래 dash-kpi 한 건은 API 빌드에 따라 달라진다.
--
--  [dash-kpi]
--  운영은 dash-kpi 가 use_flg='Y' 로 켜져 있고, 2026-09-23 이전 API 는 AI 통합 대시보드의 KPI 카드
--  (/dashboard/kpi/* 11건)를 dash-kpi 권한으로 판정한다. 그래서 이전 API 에 이 파일을 적용하면
--  **새 API 를 올리기 전까지 통합관리자 외에는 KPI 카드가 403** 이다. 새 API 는 dash-ai 권한으로 판정한다.
--  2026-09-23 운영 적용은 이 공백을 감수하고 API 배포보다 먼저 진행했다(요청자 결정).
-- =====================================================================================

\set ON_ERROR_STOP on
\timing on

-- ── 적용 전 상태를 기록해 둔다 (세션 임시표 — 커밋 후 비교에 쓴다) ────────────────────
DROP TABLE IF EXISTS _v42_before;
CREATE TEMP TABLE _v42_before AS
SELECT (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
         WHERE n.nspname='ax' AND c.relkind='r')                                     AS ax_tables,
       (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
         WHERE n.nspname='ax' AND c.relkind='v')                                     AS ax_views,
       (SELECT count(*) FROM ax.tb_sys_menu)                                         AS menus,
       (SELECT count(*) FROM ax.tb_sys_menu_group)                                   AS menu_groups,
       (SELECT count(*) FROM ax.tb_sys_dept_menu_perm)                               AS dept_perms;

\echo ''
\echo '==================== V42 : 메뉴 고정 · 미사용 표 정리 ===================='
\echo '-- 삭제 대상 표 중 현재 존재하는 것 (행수) --'
SELECT n.nspname||'.'||c.relname AS 표,
       (xpath('/row/c/text()', query_to_xml(format('SELECT count(*) AS c FROM %I.%I', n.nspname, c.relname),
                                            false,true,'')))[1]::text AS 행수
  FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
 WHERE c.relkind='r' AND n.nspname='ax'
   AND c.relname IN ('tb_ai_serving_asset','tb_ai_serving_route','tb_ai_corpus_snapshot','tb_ai_model_asset',
                     'tb_ai_defect_tag','tb_ai_defect_tag_map','tb_ai_chat_term',
                     'tb_sys_user_favorite','tb_rpt_write_state','tb_aoi_defect_image','tb_rpt_unmask_req')
 ORDER BY 1;

BEGIN;

--  운영 중 적용 — 조회가 잦은 표(tb_sys_menu · tb_ai_serving_profile)의 잠금을 오래 기다리지 않는다.
--  10초 안에 못 잡으면 실패하고 전체가 롤백된다. 한산할 때 다시 돌리면 된다.
SET LOCAL lock_timeout = '10s';

-- ── 0. 안전장치 ─────────────────────────────────────────────────────────────────────
DO $guard$
DECLARE
    v_tbl   text;
    v_cnt   bigint;
    v_msg   text := '';
    v_extra text;
BEGIN
    -- 0-1. 로컬에서 0행이던 표 — 운영에 데이터가 있으면 멈춘다
    FOREACH v_tbl IN ARRAY ARRAY['tb_ai_serving_asset','tb_ai_serving_route','tb_ai_corpus_snapshot',
                                 'tb_ai_model_asset','tb_ai_defect_tag','tb_ai_defect_tag_map','tb_ai_chat_term']
    LOOP
        IF to_regclass('ax.'||v_tbl) IS NOT NULL THEN
            EXECUTE format('SELECT count(*) FROM ax.%I', v_tbl) INTO v_cnt;
            IF v_cnt > 0 THEN v_msg := v_msg || format('  · ax.%s 에 %s 행이 있습니다.%s', v_tbl, v_cnt, chr(10)); END IF;
        END IF;
    END LOOP;

    -- 0-2. 이 파일이 모르는 메뉴 — 웹 28 + 숨김 10 이외의 행이 있으면 멈춘다
    SELECT string_agg(menu_id, ', ' ORDER BY menu_id) INTO v_extra
      FROM ax.tb_sys_menu
     WHERE menu_id NOT IN ('ai-chat','dash-ai','dash-proc','prod-monitor','dash-ai-upload',
                           'prod-result','qc-defect','qc-aoi',
                           'prod-daily','daily-history','rpt-press-morning','rpt-plating-morning',
                           'rpt-ship-plan','rpt-yield-model','rpt-lrr-customer','rpt-scrap',
                           'alert-list',
                           'sys-account','sys-menu','sys-data','alert-cond','sys-recip','sys-gloss',
                           'chat-history','sys-audit','sys-dl','sys-upload-doc','sys-sync',
                           'dash-kpi','prod-down','qc-report','report-forms','rpt-scrap-new','sys-rank',
                           'base-model','ai-agent','sys-metric','sys-model-ver');
    IF v_extra IS NOT NULL THEN
        v_msg := v_msg || format('  · 이 파일이 모르는 메뉴가 있습니다: %s%s', v_extra, chr(10));
    END IF;

    -- 0-3. 숨김 메뉴가 아직 사용 중(use_flg='Y')이면 멈춘다 — 누가 다시 켰다는 뜻
    --      dash-kpi 는 예외다. 운영은 설치본대로 'Y' 이고(로컬만 손으로 'N'), 웹 메뉴에 없는 화면이라
    --      켜져 있어도 지운다(2026-09-23 결정). 아래 [dash-kpi] 참고.
    SELECT string_agg(menu_id, ', ' ORDER BY menu_id) INTO v_extra
      FROM ax.tb_sys_menu
     WHERE use_flg = 'Y'
       AND menu_id IN ('prod-down','qc-report','report-forms','rpt-scrap-new','sys-rank',
                       'base-model','ai-agent','sys-metric','sys-model-ver');
    IF v_extra IS NOT NULL THEN
        v_msg := v_msg || format('  · 지울 메뉴가 사용 중(use_flg=Y)입니다: %s%s', v_extra, chr(10));
    END IF;

    -- 0-4. 지울 보고서 정의(RPT_QUALITY)를 문서가 가리키면 멈춘다
    IF to_regclass('vec.tb_doc') IS NOT NULL THEN
        SELECT count(*) INTO v_cnt FROM vec.tb_doc WHERE report_id = 'RPT_QUALITY';
        IF v_cnt > 0 THEN v_msg := v_msg || format('  · vec.tb_doc %s 건이 RPT_QUALITY 를 가리킵니다.%s', v_cnt, chr(10)); END IF;
    END IF;

    IF v_msg <> '' THEN
        RAISE EXCEPTION E'V42 전제가 깨져 중단합니다 — 아무것도 바뀌지 않았습니다.\n%사람이 내용을 확인한 뒤 진행하십시오.', v_msg;
    END IF;
END
$guard$;

-- ── 1. 공통코드 참조 정리 (반드시 DROP TABLE 보다 먼저) ─────────────────────────────
--  ax.fn_check_code_ref() 가 tb_sys_code_ref 의 모든 행에 `FROM ax.<target_table>` 을 동적 실행하므로
--  참조가 남으면 설치 후 점검 질의가 터진다.
DELETE FROM ax.tb_sys_code_ref
 WHERE target_table IN ('tb_ai_model_asset','tb_ai_corpus_snapshot','tb_ai_serving_asset','tb_ai_serving_route',
                        'tb_ai_defect_tag','tb_ai_defect_tag_map','tb_ai_chat_term',
                        'tb_sys_user_favorite','tb_rpt_write_state','tb_aoi_defect_image','tb_rpt_unmask_req',
                        -- V38 이 표(tb_prod_item_price)만 지우고 참조를 남겨 fn_check_code_ref() 가 깨져 있었다
                        'tb_prod_item_price');

-- ── 2. AI 서비스 버전 관리 계열 ─────────────────────────────────────────────────────
--  버전 등록·적용·롤백 API(/ai/model-releases · assets · corpus-snapshots · serving-routes)는
--  웹 화면이 없어 API 에서 제거했다. 남는 tb_ai_serving_profile 은 읽기만 된다.
DROP TRIGGER  IF EXISTS trg_guard_serving_state ON ax.tb_ai_serving_profile;
DROP FUNCTION IF EXISTS ax.fn_guard_serving_state();
DROP FUNCTION IF EXISTS ax.fn_resolve_serving_profile(common.d_user_id, character varying);

DROP VIEW IF EXISTS ax.vw_serving_active;              -- vw_serving_profile_detail 을 읽으므로 먼저
DROP VIEW IF EXISTS ax.vw_serving_profile_detail;
DROP VIEW IF EXISTS ax.vw_serving_asset_health;

ALTER TABLE ax.tb_ai_serving_profile DROP CONSTRAINT IF EXISTS tb_ai_serving_profile_corpus_snapshot_id_fkey;

DROP TABLE IF EXISTS ax.tb_ai_serving_asset;           -- → model_asset · serving_profile
DROP TABLE IF EXISTS ax.tb_ai_serving_route;           -- → serving_profile · sys_dept · sys_user
DROP TABLE IF EXISTS ax.tb_ai_corpus_snapshot;         -- → model_asset
DROP TABLE IF EXISTS ax.tb_ai_model_asset;

-- ── 3. 불량 태그 ────────────────────────────────────────────────────────────────────
DROP TABLE IF EXISTS ax.tb_ai_defect_tag_map;
DROP TABLE IF EXISTS ax.tb_ai_defect_tag;

-- ── 4. 웹 미사용 기능 표 ────────────────────────────────────────────────────────────
DROP TABLE IF EXISTS ax.tb_sys_user_favorite;          -- 즐겨찾기 (GET·PUT /users/me/favorites 제거)
DROP TABLE IF EXISTS ax.tb_rpt_write_state;            -- 보고서 작성 상태 (GET·PUT /reports/status 제거)
DROP TABLE IF EXISTS ax.tb_aoi_defect_image;           -- AOI 사진 — 화면은 09-14 에 MSSQL DIMENSION 으로 옮겼다

-- ── 5. 미참조 표 ────────────────────────────────────────────────────────────────────
DROP TABLE IF EXISTS ax.tb_ai_chat_term;               -- 채팅 ↔ 용어 연결. 채우는 코드가 없었다
DROP TABLE IF EXISTS ax.tb_rpt_unmask_req;             -- 마스킹 해제 요청. 화면·API 없음(주석에만 이름)

-- ── 6. 고아가 된 공통코드 ───────────────────────────────────────────────────────────
--  위 표에서만 쓰던 그룹이다. 다른 표가 참조하고 있으면 지우지 않는다.
DELETE FROM ax.tb_sys_code c
 WHERE c.group_cd IN ('AI_ASSET_KIND','AI_ASSET_STATE','AI_CORPUS_STATE','AI_INDEX_METHOD',
                      'AI_ROUTE_SCOPE','AI_SERVING_ROLE','UNMASK_STATE','AOI_IMG_SOURCE','PRICE_KIND')
   AND NOT EXISTS (SELECT 1 FROM ax.tb_sys_code_ref r WHERE r.group_cd = c.group_cd);
DELETE FROM ax.tb_sys_code_group g
 WHERE g.group_cd IN ('AI_ASSET_KIND','AI_ASSET_STATE','AI_CORPUS_STATE','AI_INDEX_METHOD',
                      'AI_ROUTE_SCOPE','AI_SERVING_ROLE','UNMASK_STATE','AOI_IMG_SOURCE','PRICE_KIND')
   AND NOT EXISTS (SELECT 1 FROM ax.tb_sys_code_ref r WHERE r.group_cd = g.group_cd)
   AND NOT EXISTS (SELECT 1 FROM ax.tb_sys_code     c WHERE c.group_cd = g.group_cd)
   -- vec.tb_code_ref 도 이 표를 FK 로 가리킨다(LLM 쪽 코드 참조) — 걸려 있으면 그룹은 남긴다
   AND NOT EXISTS (SELECT 1 FROM vec.tb_code_ref   v WHERE v.group_cd = g.group_cd);

-- ── 7. 메뉴 그룹 — 웹 menu.js 와 같게 ───────────────────────────────────────────────
INSERT INTO ax.tb_sys_menu_group AS g (group_id, group_nm, is_solo, sort_seq, use_flg)
VALUES
    ('assistant', 'AI 어시스턴트', true, 1, 'Y'),
    ('dashboard', '대시보드', false, 2, 'Y'),
    ('operation', '생산 및 품질 관리', false, 3, 'Y'),
    ('report', '보고서', false, 4, 'Y'),
    ('alert', '이상 알림', false, 5, 'Y'),
    ('system', '시스템관리', false, 6, 'Y')
ON CONFLICT (group_id) DO UPDATE
   SET group_nm = EXCLUDED.group_nm, is_solo = EXCLUDED.is_solo,
       sort_seq = EXCLUDED.sort_seq, use_flg = EXCLUDED.use_flg
 WHERE (g.group_nm, g.is_solo, g.sort_seq, g.use_flg)
       IS DISTINCT FROM (EXCLUDED.group_nm, EXCLUDED.is_solo, EXCLUDED.sort_seq, EXCLUDED.use_flg);

-- ── 8. 숨김 메뉴 삭제 ───────────────────────────────────────────────────────────────
--  부서 권한(tb_sys_dept_menu_perm)·계정 추가 허용·보고서 사용 기록은 FK CASCADE 로 함께 지워진다.
--  보고서 정의(tb_rpt_report)는 CASCADE 가 아니라 먼저 지운다. 하위 화면(parent)부터 지운다.
DELETE FROM ax.tb_rpt_report WHERE menu_id IN ('qc-report');
DELETE FROM ax.tb_sys_menu WHERE menu_id IN ('report-forms','rpt-scrap-new');   -- 하위 화면
DELETE FROM ax.tb_sys_menu WHERE menu_id IN ('qc-report');                      -- report-forms 의 부모
DELETE FROM ax.tb_sys_menu WHERE menu_id IN ('dash-kpi','prod-down','sys-rank','base-model',
                                             'ai-agent','sys-metric','sys-model-ver');

-- ── 9. 화면 메뉴 — 웹 menu.js 와 같게 (이름 · 그룹 · 부모 · 경로 · 태그 · 순서) ────────
--  운영에 없는 행(적용 안 된 회차가 있을 때)은 새로 만든다. 새 행의 부서 권한은 없다 —
--  통합관리자가 [메뉴 접근 권한] 화면에서 준다.
INSERT INTO ax.tb_sys_menu AS m (menu_id, menu_nm, group_id, parent_menu_id, is_sub_page, route_path, tag_cd, sort_seq,
                                 use_flg, ins_user, upd_user)
SELECT v.menu_id, v.menu_nm, v.group_id, v.parent_menu_id, v.is_sub_page, v.route_path, v.tag_cd, v.sort_seq,
       'Y', 'V42', 'V42'
  FROM (VALUES
    ('ai-chat', '덕반장 AI', 'assistant', NULL, false, '/ai/chat', 'NEW', 1),
    ('dash-ai', 'AI 통합 대시보드', 'dashboard', NULL, false, '/dashboard/ai', 'NEW', 1),
    ('dash-proc', '공정 및 제품 대시보드', 'dashboard', NULL, false, '/dashboard/process', 'NEW', 2),
    ('prod-monitor', '생산 모니터링', 'dashboard', NULL, false, '/production/monitor', 'MOD', 3),
    ('prod-result', '실적 집계·조회', 'operation', NULL, false, '/production/result', 'MOD', 1),
    ('qc-defect', '불량 현황 조회', 'operation', NULL, false, '/quality/defect', 'NEW', 2),
    ('qc-aoi', 'AOI 판정 분석', 'operation', NULL, false, '/quality/aoi', 'MOD', 3),
    ('prod-daily', '일일 생산현황 보고', 'report', NULL, false, '/production/daily-report', 'NEW', 1),
    ('rpt-press-morning', '아침회의 자료 (PRESS)', 'report', NULL, false, '/report/press-morning', 'NEW', 2),
    ('rpt-plating-morning', '아침회의 자료 (Plating·Coating)', 'report', NULL, false, '/report/plating-morning', 'NEW', 3),
    ('rpt-ship-plan', '연간 출하계획', 'report', NULL, false, '/report/ship-plan', 'NEW', 4),
    ('rpt-yield-model', '제품별 수율', 'report', NULL, false, '/report/yield-by-model', 'NEW', 5),
    ('rpt-lrr-customer', '고객사별 LRR', 'report', NULL, false, '/report/lrr-by-customer', 'NEW', 6),
    ('rpt-scrap', '폐기 보고서', 'report', NULL, false, '/report/scrap', 'NEW', 7),
    ('alert-list', '알림 목록·상세', 'alert', NULL, false, '/alert/list', 'NEW', 1),
    ('sys-account', '계정 관리', 'system', NULL, false, '/system/account', 'NEW', 1),
    ('sys-menu', '메뉴 접근 권한', 'system', NULL, false, '/system/menu-perm', 'NEW', 2),
    ('sys-data', '데이터 접근 권한', 'system', NULL, false, '/system/data-perm', 'NEW', 3),
    ('alert-cond', '이상 알림 발송 조건 관리', 'system', NULL, false, '/system/alert-condition', 'NEW', 4),
    ('sys-recip', '알림 수신자 관리', 'system', NULL, false, '/system/recipient', 'NEW', 5),
    ('sys-gloss', '용어 사전 관리', 'system', NULL, false, '/system/glossary', 'NEW', 6),
    ('chat-history', '자연어 질의 이력', 'system', NULL, false, '/system/chat-history', 'NEW', 7),
    ('sys-audit', '보안 감사 로그', 'system', NULL, false, '/system/audit-log', 'NEW', 8),
    ('sys-dl', '보고서 다운로드 이력', 'system', NULL, false, '/system/download-log', 'NEW', 9),
    ('sys-upload-doc', '업로드 문서 목록', 'system', NULL, false, '/system/upload-doc', 'NEW', 10),
    ('sys-sync', '데이터 연동 이력', 'system', NULL, false, '/system/sync-history', 'REQ', 11),
    -- 하위 화면 (상위 화면의 버튼으로 진입) — 권한 판정 대상이라 행은 있어야 한다
    ('dash-ai-upload', '업로드 리포트 업로드', 'dashboard', 'dash-ai', true, '/dashboard/ai#upload', NULL, 90),
    ('daily-history', '이전 보고서', 'report', 'prod-daily', true, '/production/daily-report/history', NULL, 90)
  ) AS v(menu_id, menu_nm, group_id, parent_menu_id, is_sub_page, route_path, tag_cd, sort_seq)
ON CONFLICT (menu_id) DO UPDATE
   SET menu_nm = EXCLUDED.menu_nm, group_id = EXCLUDED.group_id, parent_menu_id = EXCLUDED.parent_menu_id,
       is_sub_page = EXCLUDED.is_sub_page, route_path = EXCLUDED.route_path, tag_cd = EXCLUDED.tag_cd,
       sort_seq = EXCLUDED.sort_seq, use_flg = 'Y', upd_date = now(), upd_user = 'V42'
 WHERE (m.menu_nm, m.group_id, m.parent_menu_id, m.is_sub_page, m.route_path, m.tag_cd, m.sort_seq, m.use_flg)
       IS DISTINCT FROM
       (EXCLUDED.menu_nm, EXCLUDED.group_id, EXCLUDED.parent_menu_id, EXCLUDED.is_sub_page,
        EXCLUDED.route_path, EXCLUDED.tag_cd, EXCLUDED.sort_seq, 'Y'::bpchar);

--  보고서 정의의 묶음 이름도 메뉴 그룹과 맞춘다 (일일 생산현황은 보고서로 옮겨 왔다)
UPDATE ax.tb_rpt_report SET report_group = '보고서'
 WHERE report_id = 'RPT_DAILY_PROD' AND report_group IS DISTINCT FROM '보고서';

-- ── 10. 빈 그룹 폐지 — 생산관리·품질관리는 '생산 및 품질 관리' 로 합쳤다 ──────────────
DELETE FROM ax.tb_sys_menu_group g
 WHERE g.group_id IN ('production','quality')
   AND NOT EXISTS (SELECT 1 FROM ax.tb_sys_menu m WHERE m.group_id = g.group_id);

COMMIT;

-- ── 11. 적용 결과 ───────────────────────────────────────────────────────────────────
\echo ''
\echo '-- 남아 있으면 안 되는 객체 (전부 0 이어야 정상) --'
SELECT (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
         WHERE n.nspname='ax'
           AND c.relname IN ('tb_ai_serving_asset','tb_ai_serving_route','tb_ai_corpus_snapshot','tb_ai_model_asset',
                             'tb_ai_defect_tag','tb_ai_defect_tag_map','tb_ai_chat_term','tb_sys_user_favorite',
                             'tb_rpt_write_state','tb_aoi_defect_image','tb_rpt_unmask_req',
                             'vw_serving_active','vw_serving_profile_detail','vw_serving_asset_health'))  AS "남은 표·뷰",
       (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
         WHERE n.nspname='ax' AND p.proname IN ('fn_guard_serving_state','fn_resolve_serving_profile'))  AS "남은 함수",
       (SELECT count(*) FROM ax.tb_sys_menu WHERE use_flg <> 'Y' OR route_path NOT LIKE '/%')          AS "숨김·옛경로 메뉴",
       (SELECT count(*) FROM ax.tb_sys_code_ref r
         WHERE to_regclass('ax.'||r.target_table) IS NULL)                                            AS "끊긴 코드참조";

\echo ''
\echo '-- 메뉴 (웹 menu.js 순서와 같아야 한다) --'
SELECT g.sort_seq AS g순, g.group_nm AS 그룹, m.sort_seq AS 순, m.menu_id, m.menu_nm, m.route_path,
       coalesce(m.parent_menu_id,'') AS 부모
  FROM ax.tb_sys_menu m JOIN ax.tb_sys_menu_group g USING (group_id)
 ORDER BY g.sort_seq, m.is_sub_page, m.sort_seq;

\echo ''
\echo '-- 적용 전 → 후 --'
SELECT b.ax_tables AS "ax 표 전",
       (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
         WHERE n.nspname='ax' AND c.relkind='r') AS "ax 표 후",
       b.ax_views AS "ax 뷰 전",
       (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
         WHERE n.nspname='ax' AND c.relkind='v') AS "ax 뷰 후",
       b.menus AS "메뉴 전", (SELECT count(*) FROM ax.tb_sys_menu) AS "메뉴 후",
       b.menu_groups AS "그룹 전", (SELECT count(*) FROM ax.tb_sys_menu_group) AS "그룹 후",
       b.dept_perms AS "부서권한 전", (SELECT count(*) FROM ax.tb_sys_dept_menu_perm) AS "부서권한 후"
FROM _v42_before b;

DROP TABLE IF EXISTS _v42_before;

\echo ''
\echo '==================== V42 완료 ===================='
\echo '되돌리려면 : psql -h <host> -U dwje_local -d dwjedb -f rollback/V42__down.sql  (그다음 v42_backup.sql 로 데이터 복원)'
\echo ''
