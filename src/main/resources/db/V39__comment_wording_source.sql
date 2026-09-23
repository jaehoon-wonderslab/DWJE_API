-- =====================================================================================
--  V39 : 주석의 '원천' 표현 정리 (2026-09-21)
--
--  ┌─ 실행 방법 ──────────────────────────────────────────────────────────────────────┐
--  │   psql -h localhost -U dwje_local -d dwjedb -f V39__comment_wording_source.sql    │
--  │   · BEGIN/COMMIT 을 직접 들고 있으므로 --single-transaction 은 붙이지 않는다.      │
--  │   · 두 번 실행해도 안전하다. 되돌리기 : rollback/V39__down.sql                     │
--  └──────────────────────────────────────────────────────────────────────────────────┘
--
--  [왜]
--  주석에 '원천' 이 13군데 나오는데, 뜻이 두 갈래로 섞여 있어 읽기 나쁘다.
--    (가) "이 표가 어느 화면의 데이터 출처다" — 문장을 고쳐 '원천' 을 없앤다
--    (나) "바깥 시스템의 원본 데이터" — '원본' 으로 바꾼다 (이미 tb_sync_map 이 쓰던 말)
--
--  표·뷰 7건 + 컬럼 6건. 주석만 바꾸며 스키마·데이터는 건드리지 않는다.
-- =====================================================================================

\set ON_ERROR_STOP on

BEGIN;

-- ── (가) 문장을 고쳐 '원천' 을 없앤다 ────────────────────────────────────────────────
--    "…카드의 원천" 처럼 명사로 끝나 무슨 말인지 흐릿하던 것을 동사로 풀었다.

COMMENT ON TABLE ax.tb_sys_perm_log IS
  '계정 · 부서 · 권한 변경 이력 (append-only). 화면 하단 "계정·권한 변경 이력" 카드가 이 표를 읽는다';

COMMENT ON TABLE ax.tb_met_metric_source IS
  '지표 산출 근거 매핑 — 지표가 mes 스키마의 어느 테이블·컬럼에서 계산되는지 등록한다 (예: 공정 불량률 → mes.tb_pop_defect_hist.qty / mes.tb_pop_label_hist.normal)';

COMMENT ON TABLE ax.tb_ai_chat_log IS
  '자연어 질의 이력 — 질의·해석된 의도·호출 Agent·응답 시간·평가. 의도 파악 정확도와 재질의율을 이 표에서 뽑는다';

COMMENT ON TABLE ax.tb_ai_chat_agent IS
  '질의 × 호출 Agent — 화면의 "호출 Agent" 열이 이 표를 읽는다';

COMMENT ON VIEW ax.vw_serving_profile_detail IS
  'AI 서비스 버전 상세 — 역할별 모델 자산과 코퍼스 스냅샷을 한 행으로 펼친다. 관리자 화면의 버전 목록·상세가 이 뷰를 읽는다';

COMMENT ON TABLE ax.tb_sync_map IS
  '이관 매핑 — MSSQL 원본 테이블과 PostgreSQL 대상 테이블 대응. 화면의 "연동 대상 · 매핑" 카드가 이 표를 읽는다';

-- ── (나) 바깥 시스템의 원본을 가리키는 것은 '원본' 으로 ──────────────────────────────
--    MES·MSSQL 처럼 우리 밖에 있는 데이터를 말한다. tb_sync_map 이 이미 쓰던 표현에 맞춘다.

COMMENT ON TABLE ax.tb_aoi_defect_image IS
  'AOI 판정 ↔ NAS 불량 사진 매핑. 원본 2종 — MES 라벨 이력(LABEL) · MSSQL EDGE.dbo.TB_SAMSUN_DIMENSION(DIMENSION)';

COMMENT ON COLUMN ax.tb_aoi_defect_image.eqpt_cd IS
  '설비 코드. DIMENSION 원본에만 있다';

COMMENT ON COLUMN ax.tb_aoi_defect_image.plant_cd IS
  '공장 코드. LABEL 원본에만 있다 (MSSQL DIMENSION 에는 공장 컬럼이 없음)';

COMMENT ON COLUMN ax.tb_aoi_defect_image.source_cd IS
  '판정 키 원본. 공통코드 그룹 = AOI_IMG_SOURCE (LABEL=MES 라벨 이력, DIMENSION=MSSQL 치수검사)';

COMMENT ON COLUMN ax.tb_aoi_defect_image.wc_cd IS
  '작업장 코드. 표시명은 공통코드 AOI_WC(S110=도금, S120=도장) → mes.tb_md_workcenter.wc_nm → 원값 순으로 찾는다. 원본에 빈 문자열·NULL_ 접두가 섞여 있다';

COMMENT ON COLUMN ax.tb_met_metric_collect.lookback_min IS
  '한 번 수집할 때 거슬러 보는 구간(분). 이관이 늦은 원본을 메우기 위해 주기보다 넉넉히 잡는다';

COMMENT ON COLUMN ax.tb_met_metric_value.src_cd IS
  '수집 원본. 공통코드 그룹 = MET_SRC (MES=MES 이관, IOT=설비 IoT, AOI=AOI 로그, BATCH=배치 집계, MANUAL=수동 입력)';

COMMIT;

-- ── 결과 확인 ───────────────────────────────────────────────────────────────────────
\echo ''
\echo '-- 남은 ''원천'' (0 이어야 정상) --'
SELECT (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
         WHERE n.nspname IN ('ax','mes','vec') AND c.relkind IN ('r','v')
           AND obj_description(c.oid) LIKE '%원천%')                       AS "표·뷰",
       (SELECT count(*) FROM pg_description d
          JOIN pg_class c ON c.oid=d.objoid JOIN pg_namespace n ON n.oid=c.relnamespace
         WHERE n.nspname IN ('ax','mes','vec') AND d.objsubid>0
           AND d.description LIKE '%원천%')                                AS "컬럼";
\echo ''
