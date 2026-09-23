-- =====================================================================================
--  V38 : 미참조 객체·중복/사장 인덱스 정리 (2026-09-21)
--
--  ┌─ 실행 방법 (원격 서버에서 이 파일 하나만 돌리면 된다) ────────────────────────────┐
--  │                                                                                  │
--  │   psql -h localhost -U dwje_local -d dwjedb -f V38__drop_unused_objects_and_indexes.sql
--  │                                                                                  │
--  │   · 이 파일이 BEGIN/COMMIT 을 직접 들고 있으므로 --single-transaction 은 붙이지 않는다.
--  │   · 중간에 하나라도 실패하면 ON_ERROR_STOP 으로 전체가 롤백된다(부분 적용 없음).
--  │   · 두 번 실행해도 안전하다(IF EXISTS). 이미 지워졌으면 조용히 넘어간다.
--  │   · 되돌리기 : rollback/V38__down.sql                                             │
--  └──────────────────────────────────────────────────────────────────────────────────┘
--
--  [무엇을 지우는가]
--   1) 미참조 뷰 5      — API·Alert_Engine·MES_migration_engine 전 소스에서 참조 0건
--   2) 구버전 뷰 1      — V30 의 vw_sys_user_menu_perm 으로 대체됨
--   3) 미참조 표 2      — 0행이고 코드 참조도 없음
--   4) 중복 인덱스 5    — UNIQUE 제약이 이미 같은 인덱스를 만든다
--   5) 사장 인덱스 3    — 같은 표의 형제는 수억 회 쓰이는데 이것만 0~2회 (합계 820 MB)
--
--  [근거]
--   · 참조 여부 : 3개 저장소 전 소스 문자열 검색
--   · 사용 여부 : pg_stat_user_indexes.idx_scan (DB 생성 이후 누적, 리셋된 적 없음)
--
--  [안전장치]
--   지우는 표에 행이 하나라도 있으면 **중단한다**. 0행 전제가 깨졌다는 뜻이므로
--   사람이 확인하기 전에는 지우지 않는다.
--
--  [이번 회차에서 일부러 남기는 것]
--   · AI 서빙 계열(tb_ai_corpus_snapshot·tb_ai_serving_asset·tb_ai_serving_route, vw_serving_*)
--     — 유지 대상인 tb_ai_serving_profile 이 corpus_snapshot 을 FK 로 참조한다.
--       살아 있는 표의 제약까지 떼야 하므로 "모델 서빙을 API 가 맡을 것인가" 결정 후 한 묶음으로.
--   · vec.* 미참조 객체 — LLM 서비스 소관이고 실제 데이터가 있다(tb_doc_entity 1,793행).
--   · ax.ix_gls_term_trgm — uq_gls_term_lower 와 식이 다르다(lower(term) vs lower(btrim(term))).
--       같은 인덱스가 아니므로 남긴다. 다만 이름의 'trgm' 은 실제와 다르다(b-tree) — 이름만 따로 정정할 것.
-- =====================================================================================

\set ON_ERROR_STOP on
\timing on

-- ── 적용 전 상태를 기록해 둔다 (세션 임시표 — 커밋 후 비교에 쓴다) ────────────────────
DROP TABLE IF EXISTS _v38_before;
CREATE TEMP TABLE _v38_before AS
SELECT (SELECT coalesce(sum(pg_relation_size(indexrelid)),0)
          FROM pg_stat_user_indexes WHERE schemaname IN ('ax','mes','vec'))          AS idx_bytes,
       (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
         WHERE n.nspname='ax' AND c.relkind='v')                                     AS ax_views,
       (SELECT pg_database_size(current_database()))                                 AS db_bytes;

\echo ''
\echo '==================== V38 : 미참조 객체·인덱스 정리 ===================='
\echo '-- 적용 전 --'
SELECT pg_size_pretty(idx_bytes) AS "인덱스 총량",
       ax_views                  AS "ax 뷰 수",
       pg_size_pretty(db_bytes)  AS "DB 크기"
FROM _v38_before;

\echo ''
\echo '-- 삭제 대상 중 현재 존재하는 것 --'
SELECT '뷰'     AS 구분, n.nspname||'.'||c.relname AS 객체, '-' AS 비고
  FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
 WHERE c.relkind='v' AND n.nspname='ax'
   AND c.relname IN ('vw_alert_context','vw_defect_detail','vw_eqpt_operator',
                     'vw_metric_value_dim','vw_sync_status','vw_user_menu_perm')
UNION ALL
SELECT '표', n.nspname||'.'||c.relname,
       (xpath('/row/c/text()', query_to_xml(format('SELECT count(*) AS c FROM %I.%I', n.nspname, c.relname),
                                            false,true,'')))[1]::text||'행'
  FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
 WHERE c.relkind='r' AND n.nspname='ax' AND c.relname IN ('tb_prod_item_price','tb_alm_duty')
UNION ALL
SELECT '인덱스', n.nspname||'.'||c.relname, pg_size_pretty(pg_relation_size(c.oid))
  FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
 WHERE c.relkind='i'
   AND c.relname IN ('ix_prod_product_family','ix_doc_chunk_doc','ix_ai_asset_kind',
                     'ix_serving_profile_cd','ix_prod_day_target_lookup',
                     'ix_pop_defect_item','ix_pop_label_type','ix_pop_defect_defect')
ORDER BY 1, 2;

BEGIN;

-- ── 0. 안전장치 : 지우려는 표에 데이터가 있으면 중단한다 ──────────────────────────────
DO $guard$
DECLARE
    v_cnt   bigint;
    v_msg   text := '';
BEGIN
    IF to_regclass('ax.tb_prod_item_price') IS NOT NULL THEN
        EXECUTE 'SELECT count(*) FROM ax.tb_prod_item_price' INTO v_cnt;
        IF v_cnt > 0 THEN v_msg := v_msg || format('  · ax.tb_prod_item_price 에 %s 행이 있습니다.%s', v_cnt, chr(10)); END IF;
    END IF;

    IF to_regclass('ax.tb_alm_duty') IS NOT NULL THEN
        EXECUTE 'SELECT count(*) FROM ax.tb_alm_duty' INTO v_cnt;
        IF v_cnt > 0 THEN v_msg := v_msg || format('  · ax.tb_alm_duty 에 %s 행이 있습니다.%s', v_cnt, chr(10)); END IF;
    END IF;

    IF v_msg <> '' THEN
        RAISE EXCEPTION E'0행 전제가 깨져 중단합니다 — 데이터가 사라질 수 있습니다.\n%사람이 내용을 확인한 뒤 진행하십시오.', v_msg;
    END IF;
END
$guard$;

-- ── 1. 미참조 뷰 ────────────────────────────────────────────────────────────────────
--  조회용으로 만들어 두었으나 API·엔진 어느 쪽도 읽지 않는다.
DROP VIEW IF EXISTS ax.vw_alert_context;
DROP VIEW IF EXISTS ax.vw_defect_detail;
DROP VIEW IF EXISTS ax.vw_eqpt_operator;
DROP VIEW IF EXISTS ax.vw_metric_value_dim;
DROP VIEW IF EXISTS ax.vw_sync_status;

-- ── 2. 구버전 뷰 ────────────────────────────────────────────────────────────────────
--  V30 에서 ax.vw_sys_user_menu_perm(부서 ∪ 계정 추가 허용)으로 대체됐다.
--  구버전은 계정별 추가 허용(tb_sys_user_menu_grant)을 반영하지 못해,
--  남겨 두면 둘 중 어느 것이 권한의 기준인지 헷갈린다.
DROP VIEW IF EXISTS ax.vw_user_menu_perm;

-- ── 3. 미참조 표 ────────────────────────────────────────────────────────────────────
--  단가 이력 표. 단가는 보고서가 MES 전표에서 직접 읽으므로 이 표는 쓰이지 않는다.
DROP TABLE IF EXISTS ax.tb_prod_item_price;

--  당직 표. V36(drop_alert_duty)이 이미 폐기 대상으로 지정했으나, 운영 서버는
--  V35·V36 이 미적용이라 아직 남아 있다. 어느 경로로 오든 정리되도록 여기서도 지운다.
DROP TABLE IF EXISTS ax.tb_alm_duty;

-- ── 4. 중복 인덱스 ──────────────────────────────────────────────────────────────────
--  UNIQUE 제약이 이미 같은 인덱스를 만든다. 같은 열 구성을 한 벌 더 두면 쓰기만 두 배가 된다.
DROP INDEX IF EXISTS ax.ix_prod_product_family;    -- = uq_tb_prod_product_seq (완전 동일)
DROP INDEX IF EXISTS vec.ix_doc_chunk_doc;         -- = uq_tb_doc_chunk        (완전 동일)

--  아래 셋은 UNIQUE 와 마지막 열의 정렬 방향(DESC)만 다르다.
--  b-tree 는 역방향 스캔이 가능해 ORDER BY ... DESC 를 UNIQUE 인덱스로 그대로 처리한다.
DROP INDEX IF EXISTS ax.ix_ai_asset_kind;          -- = uq_tb_ai_model_asset     + DESC
DROP INDEX IF EXISTS ax.ix_serving_profile_cd;     -- = uq_tb_ai_serving_profile + DESC
DROP INDEX IF EXISTS ax.ix_prod_day_target_lookup; -- = uq_prod_day_target       + DESC

-- ── 5. 사장(死藏) 인덱스 ────────────────────────────────────────────────────────────
--  "표를 아직 안 썼다" 가 아니라 "이 인덱스만 안 쓰인다" 는 것이 근거다 —
--  같은 표의 다른 인덱스는 수억 회 쓰이는데 아래 셋만 0~2회다. 합계 820 MB.
--  MES 동기화가 대량 적재하는 표라 적재 속도에도 도움이 된다.
DROP INDEX IF EXISTS mes.ix_pop_defect_item;       -- 303 MB · 0회  (같은 표 PK 는 2.9억 회)
DROP INDEX IF EXISTS mes.ix_pop_label_type;        -- 209 MB · 0회  (형제 7개는 555~23억 회)
DROP INDEX IF EXISTS mes.ix_pop_defect_defect;     -- 308 MB · 2회  (사실상 미사용)

COMMIT;

-- ── 6. 적용 결과 ────────────────────────────────────────────────────────────────────
\echo ''
\echo '-- 남아 있으면 안 되는 객체 (셋 다 0 이어야 정상) --'
SELECT (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
         WHERE n.nspname='ax' AND c.relkind='v'
           AND c.relname IN ('vw_alert_context','vw_defect_detail','vw_eqpt_operator',
                             'vw_metric_value_dim','vw_sync_status','vw_user_menu_perm'))  AS "남은 뷰",
       (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
         WHERE n.nspname='ax' AND c.relname IN ('tb_prod_item_price','tb_alm_duty'))       AS "남은 표",
       (SELECT count(*) FROM pg_class
         WHERE relname IN ('ix_prod_product_family','ix_doc_chunk_doc','ix_ai_asset_kind',
                           'ix_serving_profile_cd','ix_prod_day_target_lookup',
                           'ix_pop_defect_item','ix_pop_label_type','ix_pop_defect_defect')) AS "남은 인덱스";

\echo ''
\echo '-- 적용 전 → 후 --'
SELECT pg_size_pretty(b.idx_bytes) AS "인덱스 전",
       pg_size_pretty((SELECT coalesce(sum(pg_relation_size(indexrelid)),0)
                         FROM pg_stat_user_indexes WHERE schemaname IN ('ax','mes','vec'))) AS "인덱스 후",
       pg_size_pretty(b.idx_bytes - (SELECT coalesce(sum(pg_relation_size(indexrelid)),0)
                         FROM pg_stat_user_indexes WHERE schemaname IN ('ax','mes','vec'))) AS "회수",
       b.ax_views AS "ax 뷰 전",
       (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
         WHERE n.nspname='ax' AND c.relkind='v') AS "ax 뷰 후"
FROM _v38_before b;

DROP TABLE IF EXISTS _v38_before;

\echo ''
\echo '==================== V38 완료 ===================='
\echo '되돌리려면 : psql -h <host> -U dwje_local -d dwjedb -f rollback/V38__down.sql'
\echo ''
