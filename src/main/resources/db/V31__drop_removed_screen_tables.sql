-- =====================================================================================
--  V31 : 시스템관리 5개 화면 제거에 딸린 테이블 정리 (2026-09-15)
--
--  [배경]
--  시스템관리 하위 5개 화면을 걷어낸다 — 제품군 순위 관리(sys-rank) · AI 모델 설정(base-model) ·
--  AI 서비스 버전 관리(sys-model-ver) · Agent 실행 현황(ai-agent) · 지표 측정 데이터 관리(sys-metric).
--  WEB 은 화면·라우트를, API 는 엔드포인트를 걷어낸다. 여기서는 DB 만 맡는다.
--
--  [WEB 이 넘긴 후보 12개를 실제 DB·코드에 대고 다시 봤다 — 5개만 지운다]
--  WEB 카탈로그(endpoints.js 의 tables 주석)로 고른 "삭제 화면에서만 쓰는 표" 12개 중
--  7개는 살아남는 것이 실제로 쓰고 있었다. 근거는 변경내역 문서에 적었다. 요약만 —
--
--    vec.tb_doc                 자연어 질의 검색(AiChatRepository)·AI 대시보드 XAI 근거
--                               (DocEvidenceRepository)가 직접 읽는다. 지금 1,476행이 들어 있다.
--    vec.tb_doc_version         RAG 청크 표(vec.tb_doc_chunk)가 FK 로 물고 있다.
--    vec.tb_embed_model         살아남는 [용어 사전 관리] 의 임베딩 재생성이
--                               findDefaultEmbedModelId() 로 직접 읽는다. tb_ingest_job 도 FK.
--    vec.tb_ingest_error        그 색인 작업(TERM_EMBED)의 오류 기록처다. 작업 표를 남기면서
--                               오류 표만 지우면 실패 이유를 적을 곳이 없어진다.
--    ax.tb_ai_corpus_snapshot   살아남는 ax.tb_ai_serving_profile 이 FK 컬럼으로 물고 있고,
--    ax.tb_ai_serving_asset     그 표의 트리거 함수 ax.fn_guard_serving_state 가 둘을 읽는다.
--    ax.tb_ai_serving_route     ax.fn_resolve_serving_profile 이 읽는다. 뷰 2개도 물려 있다.
--
--  뒤의 ax 3개는 "지워도 되지만 살아남는 표의 트리거·컬럼·함수·뷰를 함께 손봐야 한다".
--  살아남는 표를 건드리는 판단이라 여기서 하지 않고 요청자 확인으로 넘긴다(변경내역 4절).
--
--  [지우는 5개 — 들어오는 FK·뷰·함수·트리거가 하나도 없음을 확인했다]
--    ax.tb_ai_mask_rule_column    마스킹 규칙 대상 컬럼. MaskRuleRepository(삭제 화면)만 씀
--    ax.tb_ai_pipeline_stage      오케스트레이션 단계 마스터(5행). AiModelRepository(삭제 화면)만 씀
--    ax.tb_ai_serving_deploy_log  버전 전환 이력. 같은 화면 전용
--    ax.tb_met_metric_std_hist    기준 수치 변경 이력. MetricStandardService(sys-metric 전용)만 씀
--    ax.tb_prod_rank_log          순위 변경 이력(70행). ProductRankService(sys-rank 전용)만 씀
--  기준 수치 ax.tb_met_metric_std 와 제품/제품군 ax.tb_prod_product·tb_prod_family 는 남는다 —
--  성과지표 대시보드·공정 및 제품 대시보드가 쓰는 본체다. 여기서 지우는 것은 "변경 이력" 쪽뿐이다.
--
--  [공통코드 참조를 테이블보다 먼저 지우는 이유]
--  ax.fn_check_code_ref() 는 ax.tb_sys_code_ref 의 모든 행에 대해 `FROM ax.<target_table>` 을
--  동적 실행한다. 참조 행을 남긴 채 표를 지우면 설치 후 점검 질의가 그 자리에서 터진다.
--  AI_SERVICE 그룹은 남는다 — 살아남는 tb_ai_serving_profile 이 쓰고 있다.
--
--  [메뉴는 물리 삭제하지 않는다]
--  V15·V22 와 같다. 부서별 메뉴 권한이 FK 로 붙어 있어 지우면 누가 권한을 가졌었는지가 사라진다.
--  use_flg='N' 으로 내리면 조회에서 빠지고, 되살릴 때 'Y' 로 돌리면 권한까지 그대로 복원된다.
--
--  [되돌리기]
--  구조·공통코드·메뉴는 rollback/V31__down.sql 이 그대로 되돌린다.
--  업무 데이터(순위 이력 70행 등)는 구조만으로 살아나지 않으므로 적용 전에 반드시 덤프한다.
--      pg_dump -U <user> -d <db> --no-owner --no-privileges \
--        -t ax.tb_ai_mask_rule_column -t ax.tb_ai_pipeline_stage -t ax.tb_ai_serving_deploy_log \
--        -t ax.tb_met_metric_std_hist -t ax.tb_prod_rank_log > drop5_backup.sql
--
--  [적용]
--  docker exec -e PGPASSWORD=dwje_local dwje-pg psql -U dwje_local -d dwjedb -v ON_ERROR_STOP=1 -f V31__drop_removed_screen_tables.sql
--  두 번 실행해도 안전하다. 운영 DB 는 요청자 확인 뒤에 적용한다.
-- =====================================================================================

-- 1. 공통코드 참조 정리 (반드시 DROP TABLE 보다 먼저) ------------------------------------
DELETE FROM ax.tb_sys_code_ref
 WHERE (target_table, target_column) IN (
        ('tb_ai_serving_deploy_log', 'action_cd'),
        ('tb_ai_serving_deploy_log', 'service_cd'),
        ('tb_met_metric_std_hist',   'field_cd'),
        ('tb_prod_rank_log',         'act_cd')
 );

-- 2. 테이블 제거 -----------------------------------------------------------------------
--    들어오는 FK 가 없어 순서는 상관없다. 나가는 FK(tb_ai_mask_rule · tb_met_metric_std)는
--    자식 쪽을 지우는 것이므로 부모는 그대로 남는다.
DROP TABLE IF EXISTS ax.tb_ai_mask_rule_column;
DROP TABLE IF EXISTS ax.tb_ai_pipeline_stage;
DROP TABLE IF EXISTS ax.tb_ai_serving_deploy_log;
DROP TABLE IF EXISTS ax.tb_met_metric_std_hist;
DROP TABLE IF EXISTS ax.tb_prod_rank_log;

-- 3. 고아가 된 공통코드 정리 (V19 선례) --------------------------------------------------
--    AI_SERVICE 는 지우지 않는다 — tb_ai_serving_profile·tb_ai_serving_route 가 쓴다.
DELETE FROM ax.tb_sys_code       WHERE group_cd IN ('AI_DEPLOY_ACTION', 'MET_STD_FIELD', 'PROD_RANK_ACT');
DELETE FROM ax.tb_sys_code_group WHERE group_cd IN ('AI_DEPLOY_ACTION', 'MET_STD_FIELD', 'PROD_RANK_ACT');

-- 4. 화면 내리기 (물리 삭제하지 않는다) ---------------------------------------------------
UPDATE ax.tb_sys_menu
   SET use_flg = 'N',
       upd_date = now(),
       upd_user = 'V31'
 WHERE menu_id IN ('sys-rank', 'base-model', 'sys-model-ver', 'ai-agent', 'sys-metric')
   AND use_flg = 'Y';
