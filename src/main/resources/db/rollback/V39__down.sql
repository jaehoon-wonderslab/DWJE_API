-- =====================================================================================
--  V39 되돌리기 — 주석을 '원천' 표현으로 복원 (2026-09-21)
--
--  ┌─ 실행 방법 ──────────────────────────────────────────────────────────────────────┐
--  │   psql -h localhost -U dwje_local -d dwjedb -f rollback/V39__down.sql             │
--  │   · BEGIN/COMMIT 을 직접 들고 있다. 두 번 실행해도 안전하다.                       │
--  └──────────────────────────────────────────────────────────────────────────────────┘
--
--  V39 적용 직전의 주석을 그대로 떠 온 것이다. 주석만 되돌리며 스키마는 건드리지 않는다.
-- =====================================================================================

\set ON_ERROR_STOP on

BEGIN;

COMMENT ON TABLE ax.tb_sys_perm_log IS
  '계정 · 부서 · 권한 변경 이력 (append-only). 화면 하단 "계정·권한 변경 이력" 카드의 원천';

COMMENT ON TABLE ax.tb_met_metric_source IS
  '지표 산출 원천 매핑 — 지표가 mes 스키마의 어느 테이블·컬럼에서 계산되는지 등록한다 (예: 공정 불량률 → mes.tb_pop_defect_hist.qty / mes.tb_pop_label_hist.normal)';

COMMENT ON TABLE ax.tb_ai_chat_log IS
  '자연어 질의 이력 — 질의·해석된 의도·호출 Agent·응답 시간·평가. 의도 파악 정확도와 재질의율 산출 원천';

COMMENT ON TABLE ax.tb_ai_chat_agent IS
  '질의 × 호출 Agent — 화면의 "호출 Agent" 컬럼 원천';

COMMENT ON VIEW ax.vw_serving_profile_detail IS
  'AI 서비스 버전 상세 — 역할별 모델 자산과 코퍼스 스냅샷을 한 행으로 펼친다. 관리자 화면의 버전 목록·상세 원천';

COMMENT ON TABLE ax.tb_sync_map IS
  '이관 매핑 — MSSQL 원본 테이블과 PostgreSQL 대상 테이블 대응. 화면의 "연동 대상 · 매핑" 카드 원천';

COMMENT ON TABLE ax.tb_aoi_defect_image IS
  'AOI 판정 ↔ NAS 불량 사진 매핑. 원천 2종 — MES 라벨 이력(LABEL) · MSSQL EDGE.dbo.TB_SAMSUN_DIMENSION(DIMENSION)';

COMMENT ON COLUMN ax.tb_aoi_defect_image.eqpt_cd IS
  '설비 코드. DIMENSION 원천에만 있다';

COMMENT ON COLUMN ax.tb_aoi_defect_image.plant_cd IS
  '공장 코드. LABEL 원천에만 있다 (MSSQL DIMENSION 에는 공장 컬럼이 없음)';

COMMENT ON COLUMN ax.tb_aoi_defect_image.source_cd IS
  '판정 키 원천. 공통코드 그룹 = AOI_IMG_SOURCE (LABEL=MES 라벨 이력, DIMENSION=MSSQL 치수검사)';

COMMENT ON COLUMN ax.tb_aoi_defect_image.wc_cd IS
  '작업장 코드. 표시명은 공통코드 AOI_WC(S110=도금, S120=도장) → mes.tb_md_workcenter.wc_nm → 원값 순으로 찾는다. 원천에 빈 문자열·NULL_ 접두가 섞여 있다';

COMMENT ON COLUMN ax.tb_met_metric_collect.lookback_min IS
  '한 번 수집할 때 거슬러 보는 구간(분). 이관이 늦은 원천을 메우기 위해 주기보다 넉넉히 잡는다';

COMMENT ON COLUMN ax.tb_met_metric_value.src_cd IS
  '수집 원천. 공통코드 그룹 = MET_SRC (MES=MES 이관, IOT=설비 IoT, AOI=AOI 로그, BATCH=배치 집계, MANUAL=수동 입력)';

COMMIT;

\echo ''
\echo '-- 복원된 ''원천'' 주석 (표·뷰 7 · 컬럼 6 이어야 정상) --'
SELECT (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
         WHERE n.nspname IN ('ax','mes','vec') AND c.relkind IN ('r','v')
           AND obj_description(c.oid) LIKE '%원천%')  AS "표·뷰",
       (SELECT count(*) FROM pg_description d
          JOIN pg_class c ON c.oid=d.objoid JOIN pg_namespace n ON n.oid=c.relnamespace
         WHERE n.nspname IN ('ax','mes','vec') AND d.objsubid>0
           AND d.description LIKE '%원천%')           AS "컬럼";
\echo ''
