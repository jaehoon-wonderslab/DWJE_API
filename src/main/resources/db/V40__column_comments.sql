-- =====================================================================================
--  V40 : 빠져 있던 컬럼 주석 채우기 (2026-09-22)
--
--  ┌─ 실행 방법 (원격 서버에서 이 파일 하나만 돌리면 된다) ────────────────────────────┐
--  │                                                                                  │
--  │   psql -h localhost -U dwje_local -d dwjedb -f V40__column_comments.sql          │
--  │                                                                                  │
--  │   · 이 파일이 BEGIN/COMMIT 을 직접 들고 있으므로 --single-transaction 은 붙이지 않는다.
--  │   · 중간에 하나라도 실패하면 ON_ERROR_STOP 으로 전체가 롤백된다(부분 적용 없음).
--  │   · 두 번 실행해도 안전하다. 되돌리기 : rollback/V40__down.sql                     │
--  └──────────────────────────────────────────────────────────────────────────────────┘
--
--  [왜]
--  표 주석은 106개 전부 있는데 컬럼 주석이 750개 비어 있었다. 스키마만 보고는
--  그 컬럼이 무엇을 담는지, 단위가 무엇인지, 어떤 코드 집합을 따르는지 알 수 없었다.
--
--  [무엇을 근거로 썼는가]
--  추측으로 쓴 것이 없다. 네 저장소의 실제 구현을 읽고 채웠다.
--    · API                  — 전 Repository 의 SQL·DTO 매핑
--    · WEB                  — 화면에 실제로 노출되는 항목과 그 이름
--    · Alert_Engine         — ax.tb_alm_* (판정 주기 · 승격 · 발송 큐 · 재시도)
--    · MES_migration_engine — ax.tb_sync_* (프리플라이트 · 증분 · 드리프트 · 집계 기준)
--  근거를 찾지 못한 컬럼은 그 사실을 주석에 적었다(예: "현재 채우는 곳이 없다").
--
--  [문구 규칙]
--    · 화면은 사람이 아는 이름으로 적는다 — 내부 화면번호(SY-14 등)를 쓰지 않는다
--    · 마이그레이션 번호·기능명세 번호 같은 개발 내부 표기를 쓰지 않는다
--    · 문장을 '-다' 로 끝내지 않는다. 명사형으로 맺는다 (…사용 / …없음 / …기록)
--    · 코드값 컬럼은 공통코드 그룹명과 값의 뜻을 적는다
--    · 수치는 단위를 밝힌다 (초 · 분 · ms · 건 · % · byte · EA)
--    · 불리언은 true 일 때의 뜻을 적는다
--    · 이미 주석이 있던 컬럼은 건드리지 않는다 (그쪽 문구 정리는 V41)
--
--  대상 : 750 컬럼 / 93 표 (주석만 바꾸며 스키마·데이터는 건드리지 않는다)
-- =====================================================================================

\set ON_ERROR_STOP on

-- ── 선행 확인 ───────────────────────────────────────────────────────────────────────
--  주석을 다는 표가 하나라도 없으면 멈춘다. 어느 표가 없는지 한 번에 알려 준다.
--  V35(알림 엔진 스키마)를 건너뛴 서버에서 이 파일만 돌리면 여기서 걸린다.
DO $v40$
DECLARE
    missing text;
BEGIN
    SELECT string_agg(t, ', ' ORDER BY t) INTO missing
      FROM unnest(ARRAY['ax.tb_ai_agent',
                        'ax.tb_ai_agent_run',
                        'ax.tb_ai_chat_agent',
                        'ax.tb_ai_chat_log',
                        'ax.tb_ai_chat_term',
                        'ax.tb_ai_corpus_snapshot',
                        'ax.tb_ai_defect_tag',
                        'ax.tb_ai_defect_tag_map',
                        'ax.tb_ai_model_asset',
                        'ax.tb_ai_model_config',
                        'ax.tb_ai_serving_asset',
                        'ax.tb_ai_serving_profile',
                        'ax.tb_ai_serving_route',
                        'ax.tb_alm_alert',
                        'ax.tb_alm_cond',
                        'ax.tb_alm_cond_channel',
                        'ax.tb_alm_cond_escalation',
                        'ax.tb_alm_cond_group',
                        'ax.tb_alm_cond_state',
                        'ax.tb_alm_cond_target',
                        'ax.tb_alm_escalation_rule',
                        'ax.tb_alm_eval_run',
                        'ax.tb_alm_recip_group',
                        'ax.tb_alm_recip_group_channel',
                        'ax.tb_alm_recip_group_member',
                        'ax.tb_alm_recipient',
                        'ax.tb_alm_send_log',
                        'ax.tb_alm_send_queue',
                        'ax.tb_aoi_defect_image',
                        'ax.tb_dash_upload_doc',
                        'ax.tb_dash_upload_ver',
                        'ax.tb_gls_domain',
                        'ax.tb_gls_term',
                        'ax.tb_gls_variant',
                        'ax.tb_log_audit',
                        'ax.tb_met_metric_collect',
                        'ax.tb_met_metric_source',
                        'ax.tb_met_metric_std',
                        'ax.tb_met_metric_value',
                        'ax.tb_prod_customer',
                        'ax.tb_prod_daily_decision',
                        'ax.tb_prod_day_target',
                        'ax.tb_prod_downtime',
                        'ax.tb_prod_family',
                        'ax.tb_prod_item_map',
                        'ax.tb_prod_product',
                        'ax.tb_prod_project',
                        'ax.tb_prod_ship_plan',
                        'ax.tb_qc_lrr_notice',
                        'ax.tb_rpt_download_blind',
                        'ax.tb_rpt_download_log',
                        'ax.tb_rpt_form',
                        'ax.tb_rpt_form_field',
                        'ax.tb_rpt_report',
                        'ax.tb_rpt_unmask_req',
                        'ax.tb_rpt_usage',
                        'ax.tb_rpt_write_state',
                        'ax.tb_sync_job',
                        'ax.tb_sync_job_error',
                        'ax.tb_sync_map',
                        'ax.tb_sync_run',
                        'ax.tb_sync_schema_drift',
                        'ax.tb_sys_code',
                        'ax.tb_sys_code_group',
                        'ax.tb_sys_code_ref',
                        'ax.tb_sys_data_field',
                        'ax.tb_sys_data_field_attr',
                        'ax.tb_sys_dept',
                        'ax.tb_sys_dept_data_perm',
                        'ax.tb_sys_dept_menu_perm',
                        'ax.tb_sys_email_verify',
                        'ax.tb_sys_login_hist',
                        'ax.tb_sys_menu',
                        'ax.tb_sys_menu_group',
                        'ax.tb_sys_perm_log',
                        'ax.tb_sys_plant',
                        'ax.tb_sys_user',
                        'ax.tb_sys_user_favorite',
                        'ax.tb_sys_user_menu_grant',
                        'vec.tb_code_ref',
                        'vec.tb_doc',
                        'vec.tb_doc_chunk',
                        'vec.tb_doc_chunk_embed_ext',
                        'vec.tb_doc_data_field',
                        'vec.tb_doc_dept_perm',
                        'vec.tb_doc_entity',
                        'vec.tb_doc_version',
                        'vec.tb_embed_model',
                        'vec.tb_ingest_error',
                        'vec.tb_ingest_job',
                        'vec.tb_query_hit',
                        'vec.tb_query_log',
                        'vec.tb_term_embedding']) AS t
     WHERE to_regclass(t) IS NULL;
    IF missing IS NOT NULL THEN
        RAISE EXCEPTION '다음 표가 없어 주석을 달 수 없습니다: %  — 선행 마이그레이션(특히 V35 알림 엔진 스키마)을 먼저 적용하십시오.', missing;
    END IF;
END
$v40$;

BEGIN;

-- ══════════════════════════════════════════════════════════════════════════════════
--  ax 스키마
-- ══════════════════════════════════════════════════════════════════════════════════

-- ── ax.tb_ai_agent  (5)
COMMENT ON COLUMN ax.tb_ai_agent.agent_id   IS 'Agent 식별자 (PK). 코드는 agent_no 로 찾고 이 값을 박지 않음';
COMMENT ON COLUMN ax.tb_ai_agent.agent_nm   IS 'Agent 이름 (예: 비전 수집, 불량 판정, 보고서 생성)';
COMMENT ON COLUMN ax.tb_ai_agent.agent_desc IS 'Agent 설명. Agent 실행 현황 목록의 설명 칸';
COMMENT ON COLUMN ax.tb_ai_agent.sort_seq   IS '화면 표시 순서. ①~⑨ 차례를 이 값으로 부여';
COMMENT ON COLUMN ax.tb_ai_agent.use_flg    IS '사용 여부. N 이면 Agent 실행 현황 화면의 목록·요약에서 빠진다 (9행은 지우지 않는다)';

-- ── ax.tb_ai_agent_run  (7)
COMMENT ON COLUMN ax.tb_ai_agent_run.run_id         IS '실행 이력 식별자 (PK)';
COMMENT ON COLUMN ax.tb_ai_agent_run.agent_id       IS '실행한 Agent (ax.tb_ai_agent). 코드는 agent_no 로 찾아 넣음';
COMMENT ON COLUMN ax.tb_ai_agent_run.run_at         IS '실행 시각. Agent 별 최신 행이 화면의 "최근 실행" 이 됨';
COMMENT ON COLUMN ax.tb_ai_agent_run.throughput_txt IS '처리량 표기. Agent 실행 현황 화면 목록의 "처리량" 칸 (예: 판정 459건)';
COMMENT ON COLUMN ax.tb_ai_agent_run.elapsed_ms     IS '작업 소요 시간(ms). Master 요약의 평균 응답 시간 산출 근거';
COMMENT ON COLUMN ax.tb_ai_agent_run.message        IS '실행 내용 한 줄 (예: AOI 불량 판정 조회 2026-08-01~2026-08-28)';
COMMENT ON COLUMN ax.tb_ai_agent_run.err_flg        IS 'Y = 실패한 실행. 최근 10분 내 1건이라도 있으면 master.state = ERROR';

-- ── ax.tb_ai_chat_agent  (4)
COMMENT ON COLUMN ax.tb_ai_chat_agent.chat_id    IS '대상 질의 (ax.tb_ai_chat_log)';
COMMENT ON COLUMN ax.tb_ai_chat_agent.agent_id   IS '이 질의에 참여한 Agent (ax.tb_ai_agent). 의도별 매핑으로 지정';
COMMENT ON COLUMN ax.tb_ai_chat_agent.call_seq   IS '호출 순서. 1 부터 매기며 (chat_id, agent_id) 와 묶여 유일';
COMMENT ON COLUMN ax.tb_ai_chat_agent.elapsed_ms IS 'Agent 별 소요 시간(ms). 현재 API 는 계측하지 않아 NULL 로 넣음';

-- ── ax.tb_ai_chat_log  (12)
COMMENT ON COLUMN ax.tb_ai_chat_log.chat_id      IS '질의 이력 식별자 (PK). 화면·API 의 messageId 가 이 값';
COMMENT ON COLUMN ax.tb_ai_chat_log.session_id   IS '대화 세션 UUID. 한 세션의 질의가 이 값으로 묶임';
COMMENT ON COLUMN ax.tb_ai_chat_log.asked_at     IS '질의 시각';
COMMENT ON COLUMN ax.tb_ai_chat_log.user_id      IS '질의자 사번 (ax.tb_sys_user)';
COMMENT ON COLUMN ax.tb_ai_chat_log.dept_nm      IS '질의자 부서명. 질의 시점 값을 그대로 보관';
COMMENT ON COLUMN ax.tb_ai_chat_log.question     IS '사용자가 입력한 원문 질의';
COMMENT ON COLUMN ax.tb_ai_chat_log.intent_cd    IS '판정된 의도 (trend=추이, trace=이력 추적, downtime=비가동, metric=지표, unknown)';
COMMENT ON COLUMN ax.tb_ai_chat_log.intent_nm    IS '의도 표시명. 자연어 질의 이력의 "의도" 칸';
COMMENT ON COLUMN ax.tb_ai_chat_log.answer       IS '응답 본문(HTML). 데이터 권한 마스킹을 적용한 뒤의 문장';
COMMENT ON COLUMN ax.tb_ai_chat_log.response_ms  IS '응답 생성까지 걸린 시간(ms). 평균 응답 시간 지표의 원값';
COMMENT ON COLUMN ax.tb_ai_chat_log.is_reask     IS 'true = 같은 세션에 앞선 질의가 있어 이어 물은 것. 재질의율의 분자';
COMMENT ON COLUMN ax.tb_ai_chat_log.prev_chat_id IS '같은 세션의 직전 질의 (ax.tb_ai_chat_log). 재질의 판정 근거';

-- ── ax.tb_ai_chat_term  (3)
COMMENT ON COLUMN ax.tb_ai_chat_term.chat_id    IS '대상 질의 (ax.tb_ai_chat_log)';
COMMENT ON COLUMN ax.tb_ai_chat_term.term_id    IS '치환된 공식 용어 (ax.tb_gls_term)';
COMMENT ON COLUMN ax.tb_ai_chat_term.variant_id IS '질의에 쓰인 유사어 (ax.tb_gls_variant). NULL 이면 공식 용어를 그대로 사용';

-- ── ax.tb_ai_corpus_snapshot  (12)
COMMENT ON COLUMN ax.tb_ai_corpus_snapshot.snapshot_id    IS '코퍼스 스냅샷 식별자 (PK)';
COMMENT ON COLUMN ax.tb_ai_corpus_snapshot.snapshot_nm    IS '스냅샷 이름 (예: 2026-09 문서 코퍼스)';
COMMENT ON COLUMN ax.tb_ai_corpus_snapshot.embed_asset_id IS '색인에 쓴 임베딩 자산 (ax.tb_ai_model_asset). 차원이 dim 과 같아야 함';
COMMENT ON COLUMN ax.tb_ai_corpus_snapshot.doc_cnt        IS '색인된 문서 수 (vec.tb_doc 기준)';
COMMENT ON COLUMN ax.tb_ai_corpus_snapshot.token_cnt      IS '색인에 사용한 총 토큰 수. 외부 API 를 쓰면 비용 추적 근거가 됨';
COMMENT ON COLUMN ax.tb_ai_corpus_snapshot.built_started  IS '색인 시작 시각';
COMMENT ON COLUMN ax.tb_ai_corpus_snapshot.built_ended    IS '색인 완료 시각. state_cd = READY 로 올릴 때 채움';
COMMENT ON COLUMN ax.tb_ai_corpus_snapshot.remark         IS '비고';
COMMENT ON COLUMN ax.tb_ai_corpus_snapshot.ins_date       IS '등록일시';
COMMENT ON COLUMN ax.tb_ai_corpus_snapshot.ins_user       IS '등록자 (사번)';
COMMENT ON COLUMN ax.tb_ai_corpus_snapshot.upd_date       IS '최종 수정일시';
COMMENT ON COLUMN ax.tb_ai_corpus_snapshot.upd_user       IS '최종 수정자 (사번)';

-- ── ax.tb_ai_defect_tag  (4)
COMMENT ON COLUMN ax.tb_ai_defect_tag.tag_id   IS 'AI 불량 태그 식별자 (PK)';
COMMENT ON COLUMN ax.tb_ai_defect_tag.tag_nm   IS '태그명 (chip · bend · welding · stain). 대소문자를 무시하고 중복 금지';
COMMENT ON COLUMN ax.tb_ai_defect_tag.tag_desc IS '태그 설명. AI 모델 설정 화면의 설명 칸';
COMMENT ON COLUMN ax.tb_ai_defect_tag.use_flg  IS '사용 여부. N 이면 ③ 불량 판정 Agent 의 분류에서 제외';

-- ── ax.tb_ai_defect_tag_map  (5)
COMMENT ON COLUMN ax.tb_ai_defect_tag_map.tag_id    IS 'AI 불량 태그 (ax.tb_ai_defect_tag). 태그를 지우면 매핑도 함께 삭제됨';
COMMENT ON COLUMN ax.tb_ai_defect_tag_map.plant_cd  IS '공장 코드. 불량 코드가 공장별이라 함께 묶어 매핑';
COMMENT ON COLUMN ax.tb_ai_defect_tag_map.defect_cd IS 'MES 불량 코드 (mes.tb_md_defect). FK 가 없어 등록 때 실재를 확인';
COMMENT ON COLUMN ax.tb_ai_defect_tag_map.ins_date  IS '등록일시';
COMMENT ON COLUMN ax.tb_ai_defect_tag_map.ins_user  IS '등록자 (사번)';

-- ── ax.tb_ai_model_asset  (11)
COMMENT ON COLUMN ax.tb_ai_model_asset.asset_id      IS '모델 자산 식별자 (PK)';
COMMENT ON COLUMN ax.tb_ai_model_asset.asset_nm      IS '자산 표시 이름. AI 서비스 버전 관리 화면 모델 자산 목록의 "이름" 칸';
COMMENT ON COLUMN ax.tb_ai_model_asset.artifact_size IS '아티팩트 크기(바이트)';
COMMENT ON COLUMN ax.tb_ai_model_asset.base_model    IS '베이스 모델 이름. LORA 자산은 반드시 있어야 한다 (ck_asset_lora_base)';
COMMENT ON COLUMN ax.tb_ai_model_asset.lora_rank     IS 'LoRA 랭크(r). 어댑터 용량과 표현력을 정하는 값';
COMMENT ON COLUMN ax.tb_ai_model_asset.max_tokens    IS '이 모델이 한 번에 받는 최대 토큰 수';
COMMENT ON COLUMN ax.tb_ai_model_asset.remark        IS '비고';
COMMENT ON COLUMN ax.tb_ai_model_asset.ins_date      IS '등록일시';
COMMENT ON COLUMN ax.tb_ai_model_asset.ins_user      IS '등록자 (사번)';
COMMENT ON COLUMN ax.tb_ai_model_asset.upd_date      IS '최종 수정일시';
COMMENT ON COLUMN ax.tb_ai_model_asset.upd_user      IS '최종 수정자 (사번)';

-- ── ax.tb_ai_model_config  (12)
COMMENT ON COLUMN ax.tb_ai_model_config.config_id    IS 'AI 모델 설정 식별자 (PK)';
COMMENT ON COLUMN ax.tb_ai_model_config.agent_id     IS '담당 Agent (ax.tb_ai_agent). NULL 이면 Agent 에 매이지 않는 공통 설정';
COMMENT ON COLUMN ax.tb_ai_model_config.config_key   IS '설정 키. category_cd 와 묶여 유일하며 읽는 쪽의 조회 열쇠';
COMMENT ON COLUMN ax.tb_ai_model_config.config_nm    IS '설정 표시 이름. AI 모델 설정 화면의 항목명';
COMMENT ON COLUMN ax.tb_ai_model_config.config_value IS '설정 값. 문자열로 저장하고 형식 검사는 value_type_cd 로 함';
COMMENT ON COLUMN ax.tb_ai_model_config.unit         IS '값 단위 표기 (예: %, 건, ms). 화면 입력칸 옆에 표시';
COMMENT ON COLUMN ax.tb_ai_model_config.description  IS '설정 설명. 화면의 도움말 문구로 사용';
COMMENT ON COLUMN ax.tb_ai_model_config.use_flg      IS '사용 여부. N 이면 읽는 쪽이 코드 기본값을 사용 — 행을 지우지 않고 끄는 방식';
COMMENT ON COLUMN ax.tb_ai_model_config.ins_date     IS '등록일시';
COMMENT ON COLUMN ax.tb_ai_model_config.ins_user     IS '등록자 (사번)';
COMMENT ON COLUMN ax.tb_ai_model_config.upd_date     IS '최종 수정일시';
COMMENT ON COLUMN ax.tb_ai_model_config.upd_user     IS '최종 수정자 (사번)';

-- ── ax.tb_ai_serving_asset  (5)
COMMENT ON COLUMN ax.tb_ai_serving_asset.profile_id IS '서빙 프로필 (ax.tb_ai_serving_profile). 배포 때 트리거가 필수 역할 자산을 여기서 확인';
COMMENT ON COLUMN ax.tb_ai_serving_asset.asset_id   IS '역할에 배정한 모델 자산 (ax.tb_ai_model_asset). 물린 자산은 삭제 불가';
COMMENT ON COLUMN ax.tb_ai_serving_asset.remark     IS '비고';
COMMENT ON COLUMN ax.tb_ai_serving_asset.ins_date   IS '등록일시';
COMMENT ON COLUMN ax.tb_ai_serving_asset.ins_user   IS '등록자 (사번)';

-- ── ax.tb_ai_serving_profile  (25)
COMMENT ON COLUMN ax.tb_ai_serving_profile.profile_id         IS '서빙 프로필(버전) 식별자 (PK). AI 서비스 버전 관리 화면은 꺼져 있으나 /auth/me 와 질의 이력이 읽음';
COMMENT ON COLUMN ax.tb_ai_serving_profile.version_no         IS '버전 번호. /auth/me 의 servingModelVer = profile_cd-v{version_no} 로 조립됨';
COMMENT ON COLUMN ax.tb_ai_serving_profile.profile_nm         IS '버전 이름. AI 서비스 버전 관리 목록의 "이름" 칸';
COMMENT ON COLUMN ax.tb_ai_serving_profile.description        IS '버전 설명';
COMMENT ON COLUMN ax.tb_ai_serving_profile.temperature        IS '생성 온도 0~2 (기본 0.200). 낮을수록 답이 결정적';
COMMENT ON COLUMN ax.tb_ai_serving_profile.top_p              IS '누적 확률 샘플링 상한 0~1 (기본 0.900)';
COMMENT ON COLUMN ax.tb_ai_serving_profile.max_tokens         IS '응답 최대 토큰 수 (기본 2048)';
COMMENT ON COLUMN ax.tb_ai_serving_profile.search_top_k       IS '최종 반환 청크 수 (기본 10). search_candidate_k 이하여야 함';
COMMENT ON COLUMN ax.tb_ai_serving_profile.search_candidate_k IS '경로별 후보 수 (기본 60). 벡터·전문검색·트라이그램이 각각 이 개수만큼 후보를 추출';
COMMENT ON COLUMN ax.tb_ai_serving_profile.rrf_k              IS 'RRF 상수 k (기본 60). 융합 점수 = Σ 1/(k + 경로별 순위)';
COMMENT ON COLUMN ax.tb_ai_serving_profile.rerank_flg         IS 'Y = 리랭커를 거쳐 순위를 다시 매긴다 (기본 N)';
COMMENT ON COLUMN ax.tb_ai_serving_profile.eval_score         IS '평가 점수. 기준선(eval_baseline)과의 차이가 이 버전의 개선폭';
COMMENT ON COLUMN ax.tb_ai_serving_profile.eval_baseline      IS '비교 기준선 점수. 성과지표 대시보드가 eval_score 와 함께 읽음';
COMMENT ON COLUMN ax.tb_ai_serving_profile.eval_json          IS '평가 상세(JSON). 항목별 점수를 담아 버전 간 비교에 사용';
COMMENT ON COLUMN ax.tb_ai_serving_profile.canary_at          IS '카나리 적용 시각. state_cd = CANARY 로 바뀔 때 트리거가 채움';
COMMENT ON COLUMN ax.tb_ai_serving_profile.activated_at       IS '서비스 적용 시각. ACTIVE 판정은 이 값의 내림차순 최신 1건';
COMMENT ON COLUMN ax.tb_ai_serving_profile.retired_at         IS '폐기 시각. 새 버전을 올릴 때 쓰던 버전이 여기로 내려감';
COMMENT ON COLUMN ax.tb_ai_serving_profile.rollback_at        IS '롤백 시각. ROLLED_BACK 상태는 이 값이 있어야 한다 (ck_profile_rollback)';
COMMENT ON COLUMN ax.tb_ai_serving_profile.rollback_reason    IS '롤백 사유';
COMMENT ON COLUMN ax.tb_ai_serving_profile.activated_by       IS '서비스에 올린 사람 사번 (ax.tb_sys_user). 배포 감사의 기준';
COMMENT ON COLUMN ax.tb_ai_serving_profile.remark             IS '비고';
COMMENT ON COLUMN ax.tb_ai_serving_profile.ins_date           IS '등록일시';
COMMENT ON COLUMN ax.tb_ai_serving_profile.ins_user           IS '등록자 (사번)';
COMMENT ON COLUMN ax.tb_ai_serving_profile.upd_date           IS '최종 수정일시';
COMMENT ON COLUMN ax.tb_ai_serving_profile.upd_user           IS '최종 수정자 (사번)';

-- ── ax.tb_ai_serving_route  (12)
COMMENT ON COLUMN ax.tb_ai_serving_route.route_id   IS '라우팅 식별자 (PK). 등록·조회만 되고 질의 경로는 아직 이 표를 보지 않음';
COMMENT ON COLUMN ax.tb_ai_serving_route.service_cd IS '대상 서비스. 공통코드 그룹 = AI_SERVICE (CHAT=AI 채팅, REPORT=보고서 생성, SEARCH=문서 검색)';
COMMENT ON COLUMN ax.tb_ai_serving_route.dept_id    IS '부서 범위 대상 (ax.tb_sys_dept). scope_cd = DEPT 면 반드시 있어야 함';
COMMENT ON COLUMN ax.tb_ai_serving_route.user_id    IS '계정 범위 대상 사번 (ax.tb_sys_user). scope_cd = USER 면 반드시 있어야 함';
COMMENT ON COLUMN ax.tb_ai_serving_route.profile_id IS '이 범위가 쓸 서빙 버전 (ax.tb_ai_serving_profile)';
COMMENT ON COLUMN ax.tb_ai_serving_route.valid_from IS '적용 시작 시각. 현재 API 는 채우지 않아 NULL (= 즉시 적용)';
COMMENT ON COLUMN ax.tb_ai_serving_route.use_flg    IS '사용 여부. Y 인 행만 중복 검사(uq_serving_route)와 라우팅 판정의 대상';
COMMENT ON COLUMN ax.tb_ai_serving_route.remark     IS '비고';
COMMENT ON COLUMN ax.tb_ai_serving_route.ins_date   IS '등록일시';
COMMENT ON COLUMN ax.tb_ai_serving_route.ins_user   IS '등록자 (사번)';
COMMENT ON COLUMN ax.tb_ai_serving_route.upd_date   IS '최종 수정일시';
COMMENT ON COLUMN ax.tb_ai_serving_route.upd_user   IS '최종 수정자 (사번)';

-- ── ax.tb_alm_alert  (19)
COMMENT ON COLUMN ax.tb_alm_alert.alert_id      IS '알림 식별자 (자동 채번)';
COMMENT ON COLUMN ax.tb_alm_alert.cond_id       IS '발생시킨 발송 조건. 테스트 발송으로 만든 알림은 비어 있을 수 있음';
COMMENT ON COLUMN ax.tb_alm_alert.metric_id     IS '판정에 쓴 지표 기준. 조건의 metric_id 를 그대로 이관';
COMMENT ON COLUMN ax.tb_alm_alert.severity_cd   IS '심각도. 공통코드 그룹 = ALM_SEVERITY (CRIT=위험, WARN=주의, LOW=낮음)';
COMMENT ON COLUMN ax.tb_alm_alert.title         IS '알림 제목 — 대상+지표+값+단위 로 엔진이 조립한다 (예: EQ-01 불량률 4.1%)';
COMMENT ON COLUMN ax.tb_alm_alert.occurred_at   IS '이상이 난 시각. 승격 경과(after_min)와 중복 억제 창을 재는 기준점';
COMMENT ON COLUMN ax.tb_alm_alert.metric_value  IS '이상을 낸 실측값. 억제 창 안에서 재발하면 최신 값으로 덮음';
COMMENT ON COLUMN ax.tb_alm_alert.threshold_val IS '발생 당시 비교에 쓴 임계값. 조건의 임계가 바뀌어도 이 값은 남음';
COMMENT ON COLUMN ax.tb_alm_alert.plant_cd      IS '대상 공장. 지금 엔진이 채우지 않아 늘 NULL — 조회는 NULL 도 통과시킴';
COMMENT ON COLUMN ax.tb_alm_alert.wc_cd         IS '대상 공정(워크센터). 지금 엔진이 채우지 않아 늘 NULL';
COMMENT ON COLUMN ax.tb_alm_alert.mold_cd       IS '대상 금형 — 조건의 평가 단위가 MOLD 일 때 scope_key 를 그대로 넣음';
COMMENT ON COLUMN ax.tb_alm_alert.item_cd       IS '대상 품목 — 조건의 평가 단위가 ITEM 일 때 scope_key 를 그대로 넣음';
COMMENT ON COLUMN ax.tb_alm_alert.serial_no     IS '대상 시리얼. 알림 목록·상세가 읽지만 지금 채우는 코드는 없음';
COMMENT ON COLUMN ax.tb_alm_alert.product_id    IS '제품(모델) — ax.tb_prod_product. 현재 엔진·API 어디서도 채우지 않는다 (FK 만 선언된 확장 자리)';
COMMENT ON COLUMN ax.tb_alm_alert.target_desc   IS '대상 표시 문구. 평가 단위가 NONE 이면 조건의 대상 설명, 아니면 scope_key';
COMMENT ON COLUMN ax.tb_alm_alert.ack_user_id   IS '확인 처리한 사람 (사번). 엔진이 아니라 사람이 화면에서 처리한 기록';
COMMENT ON COLUMN ax.tb_alm_alert.ack_at        IS '확인 처리 시각. 알림 목록·상세에서 확인을 누른 순간 기록됨';
COMMENT ON COLUMN ax.tb_alm_alert.ack_note      IS '조치 내용. 알림 목록·상세의 확인 처리에서 입력한다 (500자)';
COMMENT ON COLUMN ax.tb_alm_alert.ins_date      IS '등록일시. 이상이 실제로 난 시각은 occurred_at 참조';

-- ── ax.tb_alm_cond  (10)
COMMENT ON COLUMN ax.tb_alm_cond.cond_id        IS '발송 조건 식별자 (자동 채번)';
COMMENT ON COLUMN ax.tb_alm_cond.cond_nm        IS '조건 이름. 이상 알림 발송 조건 관리의 조건명 열이며 중복될 수 없음';
COMMENT ON COLUMN ax.tb_alm_cond.threshold_unit IS '임계값 단위 코드. 지표에 단위가 없을 때만 쓰며 MET_UNIT 의 표기값으로 바꿔 부여';
COMMENT ON COLUMN ax.tb_alm_cond.target_desc    IS '대상 설명 문구. 평가 단위가 NONE 인 알림의 제목·본문에 대상으로 쓰임';
COMMENT ON COLUMN ax.tb_alm_cond.window_cd      IS '유효 시간대. 공통코드 그룹 = ALM_WINDOW (ALWAYS=24시간 상시, D0820=08:00~20:00, D0618=06:00~18:00, WORKDAY=주간 근무일만, ONCE=지정 시각 1회)';
COMMENT ON COLUMN ax.tb_alm_cond.use_flg        IS '''Y'' 면 엔진이 이 조건을 판정. ''N'' 이면 판정 대상에서 빠짐';
COMMENT ON COLUMN ax.tb_alm_cond.ins_date       IS '등록일시';
COMMENT ON COLUMN ax.tb_alm_cond.ins_user       IS '등록자 (사번)';
COMMENT ON COLUMN ax.tb_alm_cond.upd_date       IS '최종 수정일시';
COMMENT ON COLUMN ax.tb_alm_cond.upd_user       IS '최종 수정자 (사번)';

-- ── ax.tb_alm_cond_channel  (2)
COMMENT ON COLUMN ax.tb_alm_cond_channel.cond_id    IS '발송 조건 (tb_alm_cond). 조건이 지워지면 함께 삭제됨';
COMMENT ON COLUMN ax.tb_alm_cond_channel.channel_cd IS '발송 채널. 공통코드 그룹 = ALM_CHANNEL (MAIL=메일, POPUP=시스템 팝업, SMS=SMS, MSG=메신저 연동)';

-- ── ax.tb_alm_cond_escalation  (3)
COMMENT ON COLUMN ax.tb_alm_cond_escalation.cond_id     IS '발송 조건 (tb_alm_cond). 조건이 지워지면 함께 삭제됨';
COMMENT ON COLUMN ax.tb_alm_cond_escalation.esc_rule_id IS '적용할 승격 단계 (tb_alm_escalation_rule)';
COMMENT ON COLUMN ax.tb_alm_cond_escalation.is_on       IS 'true 면 이 조건의 알림을 그 단계로 승격. false 면 시간이 지나도 올리지 않음';

-- ── ax.tb_alm_cond_group  (2)
COMMENT ON COLUMN ax.tb_alm_cond_group.cond_id  IS '발송 조건 (tb_alm_cond). 조건이 지워지면 함께 삭제됨';
COMMENT ON COLUMN ax.tb_alm_cond_group.group_id IS '수신 그룹 (tb_alm_recip_group). 참조하는 조건이 있으면 그룹 삭제를 차단';

-- ── ax.tb_alm_cond_state  (5)
COMMENT ON COLUMN ax.tb_alm_cond_state.cond_id       IS '발송 조건 (tb_alm_cond). scope_key 와 함께 이 표의 기본키';
COMMENT ON COLUMN ax.tb_alm_cond_state.last_value    IS '마지막으로 판정에 쓴 값. 이동평균 조건이면 구간 평균이 들어감';
COMMENT ON COLUMN ax.tb_alm_cond_state.last_eval_at  IS '이 대상을 마지막으로 판정한 시각. NULL 이면 아직 한 번도 판정하지 않음';
COMMENT ON COLUMN ax.tb_alm_cond_state.last_alert_id IS '이 대상에서 마지막으로 낸 알림. 값이 정상으로 돌아오면 이 알림에 resolved_at 기록';
COMMENT ON COLUMN ax.tb_alm_cond_state.upd_date      IS '최종 수정일시 — 엔진이 판정·발송·억제를 기록할 때마다 갱신';

-- ── ax.tb_alm_cond_target  (1)
COMMENT ON COLUMN ax.tb_alm_cond_target.cond_id IS '발송 조건 (tb_alm_cond). 조건이 지워지면 함께 삭제됨';

-- ── ax.tb_alm_escalation_rule  (8)
COMMENT ON COLUMN ax.tb_alm_escalation_rule.esc_rule_id    IS '승격 규칙 식별자 (자동 채번)';
COMMENT ON COLUMN ax.tb_alm_escalation_rule.esc_level      IS '승격 단계 (1=1차, 2=2차, 3=3차). 알림의 esc_level 이 이 값보다 작을 때만 등록';
COMMENT ON COLUMN ax.tb_alm_escalation_rule.level_nm       IS '단계 표시명 (1차·2차·3차). 승격 알림 본문과 발송 사유에 그대로 쓰임';
COMMENT ON COLUMN ax.tb_alm_escalation_rule.after_min      IS '발생 후 이 분(分)이 지나도록 미확인이면 승격한다 (1차 30 · 2차 120 · 3차 240)';
COMMENT ON COLUMN ax.tb_alm_escalation_rule.to_target_desc IS '승격 대상 표시 문구 (예: 제조팀 파트장). 실제 발송은 to_group_id 로 함';
COMMENT ON COLUMN ax.tb_alm_escalation_rule.to_group_id    IS '승격 알림을 받을 수신 그룹. 비어 있으면 올리지 않고 발송 기록에 실패로 남김';
COMMENT ON COLUMN ax.tb_alm_escalation_rule.note           IS '이 단계를 두는 이유 메모 (예: 1차 승격 후에도 미확인)';
COMMENT ON COLUMN ax.tb_alm_escalation_rule.use_flg        IS '''Y'' 면 엔진이 이 승격 단계를 적용';

-- ── ax.tb_alm_eval_run  (12)
COMMENT ON COLUMN ax.tb_alm_eval_run.started_at     IS '틱 시작 시각. 이 값이 1시간 넘게 끊기면 엔진이 죽은 것';
COMMENT ON COLUMN ax.tb_alm_eval_run.ended_at       IS '틱 종료 시각';
COMMENT ON COLUMN ax.tb_alm_eval_run.duration_ms    IS '틱 소요 시간 (밀리초)';
COMMENT ON COLUMN ax.tb_alm_eval_run.cond_cnt       IS '이번 틱에 읽은 활성 조건 수 (건)';
COMMENT ON COLUMN ax.tb_alm_eval_run.raise_cnt      IS '새로 만든 알림 수 (건). 억제 창에 묶인 재발은 suppress_cnt 로 집계';
COMMENT ON COLUMN ax.tb_alm_eval_run.queued_cnt     IS '발송 대기열에 넣은 수 (건). 실제로 나간 수는 sent_cnt 참조';
COMMENT ON COLUMN ax.tb_alm_eval_run.sent_cnt       IS '채널로 실제 내보낸 수 (건)';
COMMENT ON COLUMN ax.tb_alm_eval_run.fail_cnt       IS '발송 실패·연락처 없음·수신자 없음으로 실패 처리한 수 (건)';
COMMENT ON COLUMN ax.tb_alm_eval_run.triggered_by   IS '수동 실행한 사람 (사번). 정기 실행(BATCH)이면 NULL';
COMMENT ON COLUMN ax.tb_alm_eval_run.host_name      IS '틱을 돈 서버 호스트명. 여러 대를 띄웠을 때 어느 쪽이 돌았는지 확인하는 값';
COMMENT ON COLUMN ax.tb_alm_eval_run.engine_version IS '엔진 JAR 버전. 매니페스트에 없으면 1.0.0 으로 남음';
COMMENT ON COLUMN ax.tb_alm_eval_run.message        IS '실패 사유를 이어 붙인 문구. 조용한 정기 확인이면 ''변화 없음 (정기 확인)''';

-- ── ax.tb_alm_recip_group  (8)
COMMENT ON COLUMN ax.tb_alm_recip_group.group_id   IS '수신 그룹 식별자 (자동 채번)';
COMMENT ON COLUMN ax.tb_alm_recip_group.group_nm   IS '그룹 이름. 발송 조건은 이 이름만 참조하며 중복될 수 없음';
COMMENT ON COLUMN ax.tb_alm_recip_group.night_recv IS 'true 면 그룹 전체가 야간에도 수신. 멤버 개인 설정이 꺼져 있어도 발송';
COMMENT ON COLUMN ax.tb_alm_recip_group.use_flg    IS '''Y'' 면 발송 대상으로 전개. ''N'' 이면 멤버가 있어도 아무도 받지 않음';
COMMENT ON COLUMN ax.tb_alm_recip_group.ins_date   IS '등록일시';
COMMENT ON COLUMN ax.tb_alm_recip_group.ins_user   IS '등록자 (사번)';
COMMENT ON COLUMN ax.tb_alm_recip_group.upd_date   IS '최종 수정일시';
COMMENT ON COLUMN ax.tb_alm_recip_group.upd_user   IS '최종 수정자 (사번)';

-- ── ax.tb_alm_recip_group_channel  (1)
COMMENT ON COLUMN ax.tb_alm_recip_group_channel.group_id IS '수신 그룹 (tb_alm_recip_group). 그룹이 지워지면 함께 삭제됨';

-- ── ax.tb_alm_recip_group_member  (4)
COMMENT ON COLUMN ax.tb_alm_recip_group_member.group_id IS '수신 그룹 (tb_alm_recip_group). 그룹이 지워지면 함께 삭제됨';
COMMENT ON COLUMN ax.tb_alm_recip_group_member.user_id  IS '그룹에 속한 수신자 (사번). 멤버 제외는 이 행을 지워서 함';
COMMENT ON COLUMN ax.tb_alm_recip_group_member.ins_date IS '등록일시';
COMMENT ON COLUMN ax.tb_alm_recip_group_member.ins_user IS '등록자 (사번)';

-- ── ax.tb_alm_recipient  (9)
COMMENT ON COLUMN ax.tb_alm_recipient.user_id      IS '수신자 (사번). 계정 1건당 연락처 1행이며 계정이 지워지면 함께 삭제됨';
COMMENT ON COLUMN ax.tb_alm_recipient.email        IS '메일 주소. MAIL 채널의 수신 주소이며 ''@'' 가 없으면 등록되지 않음';
COMMENT ON COLUMN ax.tb_alm_recipient.messenger_id IS '메신저 계정. MSG 채널의 수신 주소로 쓰며 비면 그 건은 실패로 남음';
COMMENT ON COLUMN ax.tb_alm_recipient.night_recv   IS 'true 면 이 수신자는 야간에도 수신. false 면 야간 건을 SKIPPED 로 남기고 보내지 않음';
COMMENT ON COLUMN ax.tb_alm_recipient.remark       IS '비고. 알림 수신자 관리 목록이 읽지만 지금 저장하는 화면 기능은 없음';
COMMENT ON COLUMN ax.tb_alm_recipient.ins_date     IS '등록일시';
COMMENT ON COLUMN ax.tb_alm_recipient.ins_user     IS '등록자 (사번)';
COMMENT ON COLUMN ax.tb_alm_recipient.upd_date     IS '최종 수정일시';
COMMENT ON COLUMN ax.tb_alm_recipient.upd_user     IS '최종 수정자 (사번)';

-- ── ax.tb_alm_send_log  (9)
COMMENT ON COLUMN ax.tb_alm_send_log.send_id     IS '발송 기록 식별자 (자동 채번)';
COMMENT ON COLUMN ax.tb_alm_send_log.alert_id    IS '어느 알림의 발송인가 (tb_alm_alert). 알림이 지워지면 함께 삭제됨';
COMMENT ON COLUMN ax.tb_alm_send_log.group_id    IS '발송 근거가 된 수신 그룹. 그룹을 펼치기 전에 실패했으면 NULL';
COMMENT ON COLUMN ax.tb_alm_send_log.user_id     IS '받은 사람 (사번). 사람 단위로 펼치기 전에 실패했으면 NULL';
COMMENT ON COLUMN ax.tb_alm_send_log.channel_cd  IS '발송 채널. 공통코드 그룹 = ALM_CHANNEL (MAIL=메일, POPUP=시스템 팝업, SMS=SMS, MSG=메신저 연동)';
COMMENT ON COLUMN ax.tb_alm_send_log.dest_addr   IS '실제 보낸 주소. 채널별로 메일 주소·휴대폰·메신저 계정이며 POPUP 은 사번';
COMMENT ON COLUMN ax.tb_alm_send_log.sent_at     IS '발송을 시도한 시각. 시도마다 1행이라 재시도는 행이 증가';
COMMENT ON COLUMN ax.tb_alm_send_log.fail_reason IS '보내지 못한 이유. 중복 억제·시간대 제외·연락처 없음도 여기에 기재';
COMMENT ON COLUMN ax.tb_alm_send_log.esc_level   IS '이 발송이 몇 차 승격 건인가 (0=최초 발송)';

-- ── ax.tb_alm_send_queue  (12)
COMMENT ON COLUMN ax.tb_alm_send_queue.queue_id   IS '대기열 식별자 (자동 채번)';
COMMENT ON COLUMN ax.tb_alm_send_queue.alert_id   IS '보낼 알림 (tb_alm_alert). 알림이 지워지면 대기열도 함께 삭제됨';
COMMENT ON COLUMN ax.tb_alm_send_queue.group_id   IS '이 수신자를 꺼낸 수신 그룹 (tb_alm_recip_group)';
COMMENT ON COLUMN ax.tb_alm_send_queue.user_id    IS '받을 사람 (사번). 알림·사람·채널·승격단계가 같으면 한 번만 들어감';
COMMENT ON COLUMN ax.tb_alm_send_queue.channel_cd IS '발송 채널. 공통코드 그룹 = ALM_CHANNEL (MAIL=메일, POPUP=시스템 팝업, SMS=SMS, MSG=메신저 연동)';
COMMENT ON COLUMN ax.tb_alm_send_queue.dest_addr  IS '보낼 주소. 채널별로 메일 주소·휴대폰·메신저 계정이며 POPUP 은 사번';
COMMENT ON COLUMN ax.tb_alm_send_queue.subject    IS '렌더링이 끝난 제목. 메일 제목으로 그대로 나감';
COMMENT ON COLUMN ax.tb_alm_send_queue.esc_level  IS '몇 차 승격으로 넣은 건인가 (0=최초 발송). 같은 단계는 한 번만 들어감';
COMMENT ON COLUMN ax.tb_alm_send_queue.try_cnt    IS '발송 시도 횟수 (회). 가져갈 때 올리며 5회째 실패하면 DEAD 로 중단';
COMMENT ON COLUMN ax.tb_alm_send_queue.locked_at  IS '워커가 집어간 시각. 300초를 넘으면 죽은 워커로 보고 PENDING 으로 회수';
COMMENT ON COLUMN ax.tb_alm_send_queue.last_error IS '마지막 시도의 실패 사유. 성공하면 비움';
COMMENT ON COLUMN ax.tb_alm_send_queue.ins_date   IS '등록일시. 발송이 끝난 행은 이 값 기준 7일 뒤 정리';

-- ── ax.tb_aoi_defect_image  (5)
COMMENT ON COLUMN ax.tb_aoi_defect_image.image_id  IS '사진 매핑 대리키';
COMMENT ON COLUMN ax.tb_aoi_defect_image.seq       IS '같은 판정 키에 사진이 여러 장일 때의 순번 (1부터). 품질 보고서 화면 불량 상세 모달의 「#1」 표시와 사진 넘김 차례';
COMMENT ON COLUMN ax.tb_aoi_defect_image.file_size IS '사진 파일 크기(byte). 품질 보고서 화면의 불량 상세 모달에서 KB 로 환산해 촬영 시각 옆에 표시';
COMMENT ON COLUMN ax.tb_aoi_defect_image.ins_date  IS '등록일시';
COMMENT ON COLUMN ax.tb_aoi_defect_image.ins_user  IS '등록자 (사번)';

-- ── ax.tb_dash_upload_doc  (6)
COMMENT ON COLUMN ax.tb_dash_upload_doc.doc_id   IS '업로드 문서 대리키. 업로드 문서 목록 화면의 검색어(문서 ID)와 버전 이력 드로어 부제에 표시';
COMMENT ON COLUMN ax.tb_dash_upload_doc.title    IS '문서 제목. 대시보드·시스템관리 목록에 표시됨. 업로드 문서 목록 화면의 「문서명」 열 · AI 통합 대시보드 화면의 업로드 리포트 문서 선택 드롭다운';
COMMENT ON COLUMN ax.tb_dash_upload_doc.memo     IS '업로더가 남기는 설명. 화면 표시용이며 파싱에는 쓰지 않음. 업로드 문서 목록 화면의 「문서명」 열 아래 회색 보조 문구';
COMMENT ON COLUMN ax.tb_dash_upload_doc.ins_date IS '최초 업로드 일시 (목록의 createdAt)';
COMMENT ON COLUMN ax.tb_dash_upload_doc.upd_date IS '최종 수정일시. 새 버전이 올라오면 함께 갱신됨. 업로드 문서 목록 화면의 「최근 업로드」 열';
COMMENT ON COLUMN ax.tb_dash_upload_doc.upd_user IS '최종 수정자 (사번). 업로드 문서 목록 화면의 「최근 업로더」 열';

-- ── ax.tb_dash_upload_ver  (6)
COMMENT ON COLUMN ax.tb_dash_upload_ver.doc_id       IS '소속 업로드 문서 (ax.tb_dash_upload_doc). 업로드 문서 목록 화면의 행을 펼친 버전 이력의 묶음 기준';
COMMENT ON COLUMN ax.tb_dash_upload_ver.ver          IS '버전 번호 (1부터). 문서 안에서만 유일. 업로드 문서 목록 화면의 「최신 버전」 열(v3)과 버전 이력 드로어의 버전';
COMMENT ON COLUMN ax.tb_dash_upload_ver.file_nm      IS '업로드 당시 원본 파일명. 업로드 문서 목록 화면의 버전 이력 드로어에 버전마다 표시';
COMMENT ON COLUMN ax.tb_dash_upload_ver.file_size    IS '원본 파일 크기(byte). 업로드 문서 목록 화면의 「크기」 열에 KB·MB 로 환산해 표시';
COMMENT ON COLUMN ax.tb_dash_upload_ver.warning_json IS '파싱 경고 목록. parse_state_cd=''WARN'' 일 때 무엇이 걸렸는지 담음. AI 통합 대시보드 화면의 업로드 리포트 상단 경고 줄과 버전 이력의 「경고 N건」';
COMMENT ON COLUMN ax.tb_dash_upload_ver.ins_date     IS '업로드 일시 (uploadedAt). 업로드 문서 목록 화면 버전 이력 드로어의 업로더 옆 일시';

-- ── ax.tb_gls_domain  (4)
COMMENT ON COLUMN ax.tb_gls_domain.domain_id IS '용어 분류 대리키';
COMMENT ON COLUMN ax.tb_gls_domain.domain_nm IS '분류명. 용어 사전 관리 화면의 분류 선택지가 이 값. 같은 화면의 「분류」 열과 공식 용어 등록 폼의 분류 선택';
COMMENT ON COLUMN ax.tb_gls_domain.sort_seq  IS '분류 선택지 표시 순서';
COMMENT ON COLUMN ax.tb_gls_domain.use_flg   IS '사용 유무 (Y/N). N 이면 분류 선택지에서 빠짐';

-- ── ax.tb_gls_term  (8)
COMMENT ON COLUMN ax.tb_gls_term.term_id   IS '공식 용어 대리키';
COMMENT ON COLUMN ax.tb_gls_term.term      IS '공식 용어 표기. 대소문자를 무시하고 중복 금지. 용어 사전 관리 화면 「공식 용어」 열';
COMMENT ON COLUMN ax.tb_gls_term.domain_id IS '소속 분류 (ax.tb_gls_domain). 용어 사전 관리 화면의 「분류」 열에 분류명으로 표시';
COMMENT ON COLUMN ax.tb_gls_term.use_flg   IS '사용 유무 (Y/N). N 이면 정규화·검색 대상에서 빠짐';
COMMENT ON COLUMN ax.tb_gls_term.ins_date  IS '등록일시';
COMMENT ON COLUMN ax.tb_gls_term.ins_user  IS '등록자 (사번)';
COMMENT ON COLUMN ax.tb_gls_term.upd_date  IS '최종 수정일시';
COMMENT ON COLUMN ax.tb_gls_term.upd_user  IS '최종 수정자 (사번)';

-- ── ax.tb_gls_variant  (6)
COMMENT ON COLUMN ax.tb_gls_variant.variant_id IS '유사어 대리키';
COMMENT ON COLUMN ax.tb_gls_variant.term_id    IS '정규화 대상 공식 용어 (ax.tb_gls_term)';
COMMENT ON COLUMN ax.tb_gls_variant.word       IS '현장에서 실제로 쓰는 말·약칭. 질의에서 이 말이 나오면 공식 용어로 치환. 용어 사전 관리 화면 「유사어 (등록자)」 열의 칩 글자';
COMMENT ON COLUMN ax.tb_gls_variant.note       IS '등록 맥락 메모 (어느 공정·문서에서 쓰는 말인지)';
COMMENT ON COLUMN ax.tb_gls_variant.reg_at     IS '등록일시';
COMMENT ON COLUMN ax.tb_gls_variant.upd_at     IS '최종 수정일시';

-- ── ax.tb_log_audit  (10)
COMMENT ON COLUMN ax.tb_log_audit.audit_id    IS '감사 로그 대리키';
COMMENT ON COLUMN ax.tb_log_audit.log_at      IS '행위 발생 시각. 화면 기본 정렬 키. 보안 감사 로그의 「시각」 열';
COMMENT ON COLUMN ax.tb_log_audit.user_id     IS '행위자 사번. 삭제된 계정의 기록도 보존하므로 FK 를 걸지 않음';
COMMENT ON COLUMN ax.tb_log_audit.target_desc IS '무엇을 대상으로 했는지 사람이 읽는 설명 (화면·보고서·조회 조건). 보안 감사 로그의 「대상」 열';
COMMENT ON COLUMN ax.tb_log_audit.remark      IS '비고. 판정 근거나 반려 사유처럼 자유 서술을 남김';
COMMENT ON COLUMN ax.tb_log_audit.ip_addr     IS '행위자 접속 IP. 화면에는 host() 로 주소만 표시';
COMMENT ON COLUMN ax.tb_log_audit.plant_cd    IS '대상이 특정 사업부인 경우의 사업부 코드';
COMMENT ON COLUMN ax.tb_log_audit.wc_cd       IS '대상이 특정 작업장인 경우의 작업장 코드';
COMMENT ON COLUMN ax.tb_log_audit.serial_no   IS '대상 LOT 의 시리얼 번호. lot_no 와 함께 라벨 이력을 가리킴';
COMMENT ON COLUMN ax.tb_log_audit.item_cd     IS '대상이 특정 품목인 경우의 품목 코드';

-- ── ax.tb_met_metric_collect  (8)
COMMENT ON COLUMN ax.tb_met_metric_collect.metric_id    IS '수집 정의를 붙일 지표 (ax.tb_met_metric_std). 지표 1건에 정의 1행';
COMMENT ON COLUMN ax.tb_met_metric_collect.interval_sec IS '수집 주기(초). 기본 300(5분)';
COMMENT ON COLUMN ax.tb_met_metric_collect.last_run_at  IS '마지막 수집 시도 시각. 값이 안 쌓여도 갱신됨';
COMMENT ON COLUMN ax.tb_met_metric_collect.last_error   IS '마지막 수집 실패 사유. 성공하면 비움';
COMMENT ON COLUMN ax.tb_met_metric_collect.ins_date     IS '등록일시';
COMMENT ON COLUMN ax.tb_met_metric_collect.ins_user     IS '등록자 (사번)';
COMMENT ON COLUMN ax.tb_met_metric_collect.upd_date     IS '최종 수정일시';
COMMENT ON COLUMN ax.tb_met_metric_collect.upd_user     IS '최종 수정자 (사번)';

-- ── ax.tb_met_metric_source  (7)
COMMENT ON COLUMN ax.tb_met_metric_source.metric_id  IS '산출 근거를 붙일 지표 (ax.tb_met_metric_std)';
COMMENT ON COLUMN ax.tb_met_metric_source.src_seq    IS '한 지표가 여러 원본을 쓸 때의 순번 (분자·분모 등)';
COMMENT ON COLUMN ax.tb_met_metric_source.src_schema IS '원본 스키마명 (보통 mes)';
COMMENT ON COLUMN ax.tb_met_metric_source.src_table  IS '원본 테이블명 (예: tb_pop_defect_hist)';
COMMENT ON COLUMN ax.tb_met_metric_source.src_column IS '원본 컬럼명. 행 수만 세는 경우 등에는 비움';
COMMENT ON COLUMN ax.tb_met_metric_source.agg_expr   IS '집계 식 서술 (예: sum(qty) / sum(normal))';
COMMENT ON COLUMN ax.tb_met_metric_source.remark     IS '비고 — 집계에서 제외하는 조건 등 보충 설명';

-- ── ax.tb_met_metric_std  (11)
COMMENT ON COLUMN ax.tb_met_metric_std.metric_id       IS '지표 대리키';
COMMENT ON COLUMN ax.tb_met_metric_std.metric_cd       IS '지표 코드. API·수집 구현체가 이 값으로 지표를 찾는다 (예: EQPT_UPTIME_RATE)';
COMMENT ON COLUMN ax.tb_met_metric_std.metric_nm       IS '지표 표시명. 화면·보고서에 그대로 표시';
COMMENT ON COLUMN ax.tb_met_metric_std.apply_alert     IS 'true 면 이상 알림 발송 조건에서 이 지표를 고를 수 있음';
COMMENT ON COLUMN ax.tb_met_metric_std.apply_dashboard IS 'true 면 대시보드 목표선·신호등 색에 이 기준을 사용';
COMMENT ON COLUMN ax.tb_met_metric_std.apply_report    IS 'true 면 보고서 신호등 색에 이 기준을 사용';
COMMENT ON COLUMN ax.tb_met_metric_std.use_flg         IS '사용 유무 (Y/N). N 이면 화면 목록과 판정에서 모두 빠짐';
COMMENT ON COLUMN ax.tb_met_metric_std.ins_date        IS '등록일시';
COMMENT ON COLUMN ax.tb_met_metric_std.ins_user        IS '등록자 (사번)';
COMMENT ON COLUMN ax.tb_met_metric_std.upd_date        IS '최종 수정일시';
COMMENT ON COLUMN ax.tb_met_metric_std.upd_user        IS '최종 수정자 (사번)';

-- ── ax.tb_met_metric_value  (11)
COMMENT ON COLUMN ax.tb_met_metric_value.value_id     IS '측정값 대리키. 같은 시각이 겹칠 때의 2차 정렬 키로도 사용';
COMMENT ON COLUMN ax.tb_met_metric_value.metric_id    IS '측정 대상 지표 (ax.tb_met_metric_std)';
COMMENT ON COLUMN ax.tb_met_metric_value.measured_at  IS '측정 시각. 조회·판정 구간의 기준이며 알림 엔진이 이 값의 최신성을 확인';
COMMENT ON COLUMN ax.tb_met_metric_value.metric_value IS '측정값. 단위는 지표의 unit_cd 를 따름';
COMMENT ON COLUMN ax.tb_met_metric_value.plant_cd     IS '사업부 구분 코드 (PL01=모바일, PL03=전장)';
COMMENT ON COLUMN ax.tb_met_metric_value.mold_cd      IS '금형 코드 — mes.tb_md_mold 와 조인';
COMMENT ON COLUMN ax.tb_met_metric_value.lot_no       IS 'LOT 번호 — mes.tb_pop_label_hist 와 조인';
COMMENT ON COLUMN ax.tb_met_metric_value.serial_no    IS 'LOT 시리얼 번호. lot_no 와 함께 LOT 을 가리킴';
COMMENT ON COLUMN ax.tb_met_metric_value.product_id   IS '제품(모델) — ax.tb_prod_product. 품목 매핑을 거치지 않고 바로 붙일 때 사용';
COMMENT ON COLUMN ax.tb_met_metric_value.remark       IS '비고 — 보정·재계산 등 이 값에 붙는 메모';
COMMENT ON COLUMN ax.tb_met_metric_value.ins_date     IS '등록일시. 측정 시각(measured_at)과 다를 수 있음';

-- ── ax.tb_prod_customer  (8)
COMMENT ON COLUMN ax.tb_prod_customer.customer_id IS '고객사 대리키';
COMMENT ON COLUMN ax.tb_prod_customer.customer_cd IS '고객사 코드. 외부 표기·파일명에 쓰는 약칭';
COMMENT ON COLUMN ax.tb_prod_customer.customer_nm IS '고객사명. 데이터 접근 항목 customer 의 blind 대상. 고객사별 LRR 화면의 「고객사」 열과 연간 출하계획 화면의 「고객사」 열';
COMMENT ON COLUMN ax.tb_prod_customer.use_flg     IS '사용 유무 (Y/N)';
COMMENT ON COLUMN ax.tb_prod_customer.ins_date    IS '등록일시';
COMMENT ON COLUMN ax.tb_prod_customer.ins_user    IS '등록자 (사번)';
COMMENT ON COLUMN ax.tb_prod_customer.upd_date    IS '최종 수정일시';
COMMENT ON COLUMN ax.tb_prod_customer.upd_user    IS '최종 수정자 (사번)';

-- ── ax.tb_prod_daily_decision  (5)
COMMENT ON COLUMN ax.tb_prod_daily_decision.target_date IS '보고 대상일 (작성일이 아님). 제품과 함께 키를 구성. 일일 생산현황 보고의 조회 기준일';
COMMENT ON COLUMN ax.tb_prod_daily_decision.ins_date    IS '등록일시';
COMMENT ON COLUMN ax.tb_prod_daily_decision.ins_user    IS '등록자 (사번)';
COMMENT ON COLUMN ax.tb_prod_daily_decision.upd_date    IS '최종 수정일시';
COMMENT ON COLUMN ax.tb_prod_daily_decision.upd_user    IS '최종 수정자 (사번)';

-- ── ax.tb_prod_day_target  (7)
COMMENT ON COLUMN ax.tb_prod_day_target.target_id IS '일목표 대리키';
COMMENT ON COLUMN ax.tb_prod_day_target.plant_cd  IS '사업부 구분 코드 (PL01=모바일, PL03=전장)';
COMMENT ON COLUMN ax.tb_prod_day_target.remark    IS '비고 — 목표를 그렇게 잡은 근거를 남김';
COMMENT ON COLUMN ax.tb_prod_day_target.ins_date  IS '등록일시';
COMMENT ON COLUMN ax.tb_prod_day_target.ins_user  IS '등록자 (사번)';
COMMENT ON COLUMN ax.tb_prod_day_target.upd_date  IS '최종 수정일시';
COMMENT ON COLUMN ax.tb_prod_day_target.upd_user  IS '최종 수정자 (사번)';

-- ── ax.tb_prod_downtime  (16)
COMMENT ON COLUMN ax.tb_prod_downtime.downtime_id      IS '비가동 이력 대리키';
COMMENT ON COLUMN ax.tb_prod_downtime.plant_cd         IS '사업부 구분 코드 (PL01=모바일, PL03=전장)';
COMMENT ON COLUMN ax.tb_prod_downtime.eqpt_cd          IS '멈춘 설비 코드 — mes.tb_md_eqpt 와 조인';
COMMENT ON COLUMN ax.tb_prod_downtime.wc_cd            IS '설비가 속한 작업장 코드. 공정 단위 집계에 사용';
COMMENT ON COLUMN ax.tb_prod_downtime.stop_at          IS '정지 시각. 비가동 구간의 시작';
COMMENT ON COLUMN ax.tb_prod_downtime.resume_at        IS '재가동 시각. 비어 있으면 아직 멈춰 있는 건';
COMMENT ON COLUMN ax.tb_prod_downtime.elapsed_min      IS '비가동 시간(분). resume_at - stop_at 으로 API 가 계산해 넣음';
COMMENT ON COLUMN ax.tb_prod_downtime.remark           IS '비고 — 사유 등록 시 담당자가 남기는 설명';
COMMENT ON COLUMN ax.tb_prod_downtime.agent_confidence IS 'Agent 제안 사유의 확신도(0~1). 제안 기능 미구현이라 현재 전건 NULL';
COMMENT ON COLUMN ax.tb_prod_downtime.agent_basis      IS 'Agent 가 그 사유를 제안한 근거. 제안 기능 미구현이라 현재 전건 NULL';
COMMENT ON COLUMN ax.tb_prod_downtime.registered_at    IS '사유를 등록한 시각. is_registered 가 true 가 된 시점';
COMMENT ON COLUMN ax.tb_prod_downtime.registered_by    IS '사유를 등록한 사람 (사번)';
COMMENT ON COLUMN ax.tb_prod_downtime.ins_date         IS '등록일시 — 감지·수집으로 행이 만들어진 시각';
COMMENT ON COLUMN ax.tb_prod_downtime.ins_user         IS '등록자 (사번). IoT 자동 감지분은 비어 있음';
COMMENT ON COLUMN ax.tb_prod_downtime.upd_date         IS '최종 수정일시';
COMMENT ON COLUMN ax.tb_prod_downtime.upd_user         IS '최종 수정자 (사번)';

-- ── ax.tb_prod_family  (7)
COMMENT ON COLUMN ax.tb_prod_family.family_id IS '제품군 대리키';
COMMENT ON COLUMN ax.tb_prod_family.family_nm IS '제품군명. 대시보드·보고서의 제품군 축에 표시. 공정 및 제품 대시보드 화면 제품 선택 팝업의 「제품군」 열과 제품군 필터';
COMMENT ON COLUMN ax.tb_prod_family.use_flg   IS '사용 유무 (Y/N)';
COMMENT ON COLUMN ax.tb_prod_family.ins_date  IS '등록일시';
COMMENT ON COLUMN ax.tb_prod_family.ins_user  IS '등록자 (사번)';
COMMENT ON COLUMN ax.tb_prod_family.upd_date  IS '최종 수정일시';
COMMENT ON COLUMN ax.tb_prod_family.upd_user  IS '최종 수정자 (사번)';

-- ── ax.tb_prod_item_map  (6)
COMMENT ON COLUMN ax.tb_prod_item_map.plant_cd   IS '사업부 구분 코드 (PL01=모바일, PL03=전장)';
COMMENT ON COLUMN ax.tb_prod_item_map.item_cd    IS 'MES 품목 코드 — mes.tb_md_item 을 논리 참조';
COMMENT ON COLUMN ax.tb_prod_item_map.product_id IS '이 품목이 속하는 제품(모델) — ax.tb_prod_product';
COMMENT ON COLUMN ax.tb_prod_item_map.remark     IS '비고 — 매핑 근거나 예외 처리 메모';
COMMENT ON COLUMN ax.tb_prod_item_map.ins_date   IS '등록일시';
COMMENT ON COLUMN ax.tb_prod_item_map.ins_user   IS '등록자 (사번)';

-- ── ax.tb_prod_product  (11)
COMMENT ON COLUMN ax.tb_prod_product.product_id  IS '제품(모델) 대리키';
COMMENT ON COLUMN ax.tb_prod_product.model_nm    IS '모델 표시명. 비우면 화면이 model_cd 를 그대로 사용. 공정 및 제품 대시보드 화면 제품 선택 팝업의 「제품명」 열';
COMMENT ON COLUMN ax.tb_prod_product.family_id   IS '소속 제품군 (ax.tb_prod_family). 공정 및 제품 대시보드 화면 제품 선택 팝업의 「제품군」 열에 이름으로 표시';
COMMENT ON COLUMN ax.tb_prod_product.customer_id IS '납품 고객사 (ax.tb_prod_customer). 데이터 접근 항목 customer 의 blind 대상. 연간 출하계획 표의 「고객사」 열에 이름으로 표시';
COMMENT ON COLUMN ax.tb_prod_product.project_id  IS '소속 프로젝트 (ax.tb_prod_project). 공정 및 제품 대시보드 화면 제품 선택 팝업의 「프로젝트」 열에 이름으로 표시';
COMMENT ON COLUMN ax.tb_prod_product.def_seq     IS '기본 순서 — "기본 순서 복원" 이 seq_in_family 를 되돌릴 값';
COMMENT ON COLUMN ax.tb_prod_product.use_flg     IS '사용 유무 (Y/N)';
COMMENT ON COLUMN ax.tb_prod_product.ins_date    IS '등록일시. 공정 및 제품 대시보드 화면 제품 선택 팝업의 「등록일」 열';
COMMENT ON COLUMN ax.tb_prod_product.ins_user    IS '등록자 (사번)';
COMMENT ON COLUMN ax.tb_prod_product.upd_date    IS '최종 수정일시. 공정 및 제품 대시보드 화면 제품 선택 팝업의 「수정일」 열';
COMMENT ON COLUMN ax.tb_prod_product.upd_user    IS '최종 수정자 (사번)';

-- ── ax.tb_prod_project  (4)
COMMENT ON COLUMN ax.tb_prod_project.project_id IS '프로젝트 대리키';
COMMENT ON COLUMN ax.tb_prod_project.project_cd IS '프로젝트 코드. 외부 표기용 약칭';
COMMENT ON COLUMN ax.tb_prod_project.project_nm IS '프로젝트명. mes.tb_md_item.project_nm 과 대응. 공정 및 제품 대시보드 화면 제품 선택 팝업의 「프로젝트」 열과 프로젝트 필터';
COMMENT ON COLUMN ax.tb_prod_project.use_flg    IS '사용 유무 (Y/N)';

-- ── ax.tb_prod_ship_plan  (13)
COMMENT ON COLUMN ax.tb_prod_ship_plan.plan_id     IS '출하계획 대리키';
COMMENT ON COLUMN ax.tb_prod_ship_plan.plan_year   IS '계획 연도. (연·월·제품)이 유일해야 함. 연간 출하계획의 「계획 연도」 필터';
COMMENT ON COLUMN ax.tb_prod_ship_plan.plan_month  IS '계획 월 (1~12). 연간 출하계획 화면 표의 월 열 12칸을 가르는 값';
COMMENT ON COLUMN ax.tb_prod_ship_plan.product_id  IS '계획 대상 제품(모델) — ax.tb_prod_product. 연간 출하계획 화면 표의 「모델」 열';
COMMENT ON COLUMN ax.tb_prod_ship_plan.customer_id IS '납품 고객사 (ax.tb_prod_customer). 연간 출하계획 화면 표의 「고객사」 열';
COMMENT ON COLUMN ax.tb_prod_ship_plan.plan_qty    IS '월별 출하 계획 수량(EA). 데이터 접근 항목 plan 의 blind 대상. 연간 출하계획 화면의 「모델·고객사별 월 출하계획」 표의 각 월 값';
COMMENT ON COLUMN ax.tb_prod_ship_plan.unit_price  IS '출하 단가(원). 데이터 접근 항목 price 의 blind 대상';
COMMENT ON COLUMN ax.tb_prod_ship_plan.actual_qty  IS '실제 출하 수량(EA). 계획 대비 달성률의 분자';
COMMENT ON COLUMN ax.tb_prod_ship_plan.remark      IS '비고 — 계획 변경 사유 등';
COMMENT ON COLUMN ax.tb_prod_ship_plan.ins_date    IS '등록일시';
COMMENT ON COLUMN ax.tb_prod_ship_plan.ins_user    IS '등록자 (사번)';
COMMENT ON COLUMN ax.tb_prod_ship_plan.upd_date    IS '최종 수정일시';
COMMENT ON COLUMN ax.tb_prod_ship_plan.upd_user    IS '최종 수정자 (사번)';

-- ── ax.tb_qc_lrr_notice  (18)
COMMENT ON COLUMN ax.tb_qc_lrr_notice.notice_id   IS 'LRR 통보 대리키';
COMMENT ON COLUMN ax.tb_qc_lrr_notice.notice_no   IS '고객사가 부여한 통보 문서 번호. 고객사 문의 시 대조하는 값';
COMMENT ON COLUMN ax.tb_qc_lrr_notice.customer_id IS '통보한 고객사 (ax.tb_prod_customer). 고객사별 LRR 화면 「고객사 누계」 표의 「고객사」 열';
COMMENT ON COLUMN ax.tb_qc_lrr_notice.product_id  IS '대상 제품(모델) — ax.tb_prod_product';
COMMENT ON COLUMN ax.tb_qc_lrr_notice.plant_cd    IS '사업부 구분 코드 (PL01=모바일, PL03=전장)';
COMMENT ON COLUMN ax.tb_qc_lrr_notice.lot_no      IS '대상 LOT 번호 — mes.tb_pop_label_hist 와 조인';
COMMENT ON COLUMN ax.tb_qc_lrr_notice.item_cd     IS '대상 품목 코드 — mes.tb_md_item 와 조인';
COMMENT ON COLUMN ax.tb_qc_lrr_notice.defect_cd   IS '불량 코드 — mes.tb_md_defect 와 조인. 표시명은 마스터 우선';
COMMENT ON COLUMN ax.tb_qc_lrr_notice.defect_txt  IS '고객사가 적어 온 불량 내용. 코드로 분류되지 않을 때 이 글을 사용. 고객사별 LRR 화면의 「불량 유형별 발생 건수」 피벗의 행 이름';
COMMENT ON COLUMN ax.tb_qc_lrr_notice.notice_date IS '고객사에게 통보받은 날. 집계 귀속은 통보일이 아니라 출하 연월 기준';
COMMENT ON COLUMN ax.tb_qc_lrr_notice.ship_month  IS '귀속 출하 월 (1~12). 고객사별 LRR 화면 피벗 표의 기간 열을 가르는 값';
COMMENT ON COLUMN ax.tb_qc_lrr_notice.lrr_qty     IS '고객사가 통보한 불량 수량(EA). LRR(%) 의 분자. 고객사별 LRR 화면의 Q''ty 열과 「고객사별 LRR 수량」 피벗';
COMMENT ON COLUMN ax.tb_qc_lrr_notice.ship_qty    IS '해당 출하 연월의 출하 수량(EA). LRR(%) 의 분모. 고객사별 LRR 화면 「고객사 누계」 표의 Ship Q''ty 열';
COMMENT ON COLUMN ax.tb_qc_lrr_notice.remark      IS '비고 — 분석 경과·조치 내용을 남김';
COMMENT ON COLUMN ax.tb_qc_lrr_notice.ins_date    IS '등록일시';
COMMENT ON COLUMN ax.tb_qc_lrr_notice.ins_user    IS '등록자 (사번)';
COMMENT ON COLUMN ax.tb_qc_lrr_notice.upd_date    IS '최종 수정일시';
COMMENT ON COLUMN ax.tb_qc_lrr_notice.upd_user    IS '최종 수정자 (사번)';

-- ── ax.tb_rpt_download_blind  (3)
COMMENT ON COLUMN ax.tb_rpt_download_blind.dl_id     IS '대상 다운로드 이력 (ax.tb_rpt_download_log)';
COMMENT ON COLUMN ax.tb_rpt_download_blind.field_key IS '제외된 데이터 항목 (ax.tb_sys_data_field)';
COMMENT ON COLUMN ax.tb_rpt_download_blind.cell_cnt  IS '그 항목 때문에 값이 빠진 셀 수';

-- ── ax.tb_rpt_download_log  (10)
COMMENT ON COLUMN ax.tb_rpt_download_log.dl_id         IS '다운로드 이력 대리키';
COMMENT ON COLUMN ax.tb_rpt_download_log.downloaded_at IS '내려받은 시각. 화면 기본 정렬 키. 보고서 다운로드 이력의 「일시」 열';
COMMENT ON COLUMN ax.tb_rpt_download_log.user_id       IS '내려받은 사람 (사번). 삭제된 계정의 기록도 보존하므로 FK 를 걸지 않음. 보고서 다운로드 이력 화면의 「계정」 열';
COMMENT ON COLUMN ax.tb_rpt_download_log.dept_nm       IS '내려받을 당시 소속 부서명 스냅샷. 보고서 다운로드 이력 화면의 「부서」 열';
COMMENT ON COLUMN ax.tb_rpt_download_log.report_id     IS '대상 보고서 (ax.tb_rpt_report). 보고서가 아닌 화면 내려받기면 비어 있음. 보고서 다운로드 이력 화면의 「화면」 열에 화면명과 경로로 표시';
COMMENT ON COLUMN ax.tb_rpt_download_log.menu_id       IS '내려받은 화면 ID (ax.tb_sys_menu). 화면이 삭제돼도 기록을 보존하므로 FK 를 걸지 않음';
COMMENT ON COLUMN ax.tb_rpt_download_log.row_cnt       IS '파일에 담긴 데이터 행 수. 보고서 다운로드 이력 화면의 「행 수」 열';
COMMENT ON COLUMN ax.tb_rpt_download_log.ip_addr       IS '내려받은 접속 IP. 화면에는 host() 로 주소만 표시. 보고서 다운로드 이력 화면의 「IP」 열';
COMMENT ON COLUMN ax.tb_rpt_download_log.result_cd     IS '처리 결과. 현재 구현은 성공 시 DONE 만 기록';
COMMENT ON COLUMN ax.tb_rpt_download_log.file_nm       IS '생성한 파일명. 인쇄(PDF)처럼 파일이 없으면 비어 있음';

-- ── ax.tb_rpt_form  (9)
COMMENT ON COLUMN ax.tb_rpt_form.form_id     IS '양식 대리키';
COMMENT ON COLUMN ax.tb_rpt_form.form_nm     IS '양식명. 양식 관리 화면이 내려간 뒤로는 정의만 남아 있음';
COMMENT ON COLUMN ax.tb_rpt_form.customer_id IS '고객사 전용 양식일 때의 고객사 (ax.tb_prod_customer)';
COMMENT ON COLUMN ax.tb_rpt_form.report_id   IS '이 양식이 붙는 보고서 (ax.tb_rpt_report)';
COMMENT ON COLUMN ax.tb_rpt_form.use_flg     IS '사용 유무 (Y/N)';
COMMENT ON COLUMN ax.tb_rpt_form.ins_date    IS '등록일시';
COMMENT ON COLUMN ax.tb_rpt_form.ins_user    IS '등록자 (사번)';
COMMENT ON COLUMN ax.tb_rpt_form.upd_date    IS '최종 수정일시';
COMMENT ON COLUMN ax.tb_rpt_form.upd_user    IS '최종 수정자 (사번)';

-- ── ax.tb_rpt_form_field  (6)
COMMENT ON COLUMN ax.tb_rpt_form_field.form_id     IS '소속 양식 (ax.tb_rpt_form)';
COMMENT ON COLUMN ax.tb_rpt_form_field.field_seq   IS '양식 안의 항목 순번. form_id 와 함께 키를 구성';
COMMENT ON COLUMN ax.tb_rpt_form_field.field_nm    IS '항목 표시명 (예: 발생 일자, 불량 내용)';
COMMENT ON COLUMN ax.tb_rpt_form_field.field_code  IS '항목 식별 코드. 양식 파서가 값을 꽂을 자리를 찾는 키';
COMMENT ON COLUMN ax.tb_rpt_form_field.is_required IS 'true 면 필수 입력 항목. 양식 관리 화면이 내려가 현재 판정에 쓰이지 않음';
COMMENT ON COLUMN ax.tb_rpt_form_field.remark      IS '비고 — 작성 요령 등 항목 설명';

-- ── ax.tb_rpt_report  (5)
COMMENT ON COLUMN ax.tb_rpt_report.report_id    IS '보고서 코드 (예: RPT_DAILY_PROD). 다운로드 이력이 이 값을 남김';
COMMENT ON COLUMN ax.tb_rpt_report.report_nm    IS '보고서 표시명';
COMMENT ON COLUMN ax.tb_rpt_report.report_group IS '보고서 분류 (생산관리 · 품질관리 등). 보고서 센터의 묶음 단위';
COMMENT ON COLUMN ax.tb_rpt_report.sort_seq     IS '보고서 목록 표시 순서';
COMMENT ON COLUMN ax.tb_rpt_report.use_flg      IS '사용 유무 (Y/N). N 이면 보고서 선택 목록에서 빠짐';

-- ── ax.tb_rpt_unmask_req  (10)
COMMENT ON COLUMN ax.tb_rpt_unmask_req.req_id         IS '해제 요청 대리키';
COMMENT ON COLUMN ax.tb_rpt_unmask_req.menu_id        IS '해제를 요청한 화면 (ax.tb_sys_menu)';
COMMENT ON COLUMN ax.tb_rpt_unmask_req.field_keys     IS '해제를 요청한 데이터 항목들 (ax.tb_sys_data_field.field_key 배열)';
COMMENT ON COLUMN ax.tb_rpt_unmask_req.reason         IS '요청 사유. 승인 판단과 감사 증빙에 사용';
COMMENT ON COLUMN ax.tb_rpt_unmask_req.requested_at   IS '요청 시각';
COMMENT ON COLUMN ax.tb_rpt_unmask_req.requester_id   IS '요청자 (사번)';
COMMENT ON COLUMN ax.tb_rpt_unmask_req.requester_dept IS '요청 당시 소속 부서명 스냅샷';
COMMENT ON COLUMN ax.tb_rpt_unmask_req.approver_id    IS '승인·반려한 사람 (사번). 미처리면 비어 있음';
COMMENT ON COLUMN ax.tb_rpt_unmask_req.approved_at    IS '승인·반려 처리 시각';
COMMENT ON COLUMN ax.tb_rpt_unmask_req.approve_note   IS '승인·반려 의견. 반려 사유를 여기에 남김';

-- ── ax.tb_rpt_usage  (1)
COMMENT ON COLUMN ax.tb_rpt_usage.user_id IS '사용 횟수를 센 계정 사번 (ax.tb_sys_user). 계정 삭제 시 함께 삭제';

-- ── ax.tb_rpt_write_state  (4)
COMMENT ON COLUMN ax.tb_rpt_write_state.ins_date IS '등록일시';
COMMENT ON COLUMN ax.tb_rpt_write_state.ins_user IS '등록자 (사번)';
COMMENT ON COLUMN ax.tb_rpt_write_state.upd_date IS '최종 수정일시 — 상태를 마지막으로 바꾼 시각';
COMMENT ON COLUMN ax.tb_rpt_write_state.upd_user IS '최종 수정자 (사번). 화면의 "작성자" 표시가 이 값';

-- ── ax.tb_sync_job  (9)
COMMENT ON COLUMN ax.tb_sync_job.map_id       IS '이관 매핑 ID. ax.tb_sync_map 의 어느 정의로 돌렸는지';
COMMENT ON COLUMN ax.tb_sync_job.sync_kind_cd IS '이관 구분. 공통코드 그룹 = SYNC_KIND (FULL=전량 교체, INCR=증분 UPSERT). key_columns 가 비면 창 재적재로 돌아도 INCR 로 기록';
COMMENT ON COLUMN ax.tb_sync_job.ended_at     IS '작업 종료 일시. 시작 시 NULL 이고 마감 UPDATE 에서 채움';
COMMENT ON COLUMN ax.tb_sync_job.duration_sec IS '이관 소요 시간(초). started_at~ended_at 차이를 초로 버림';
COMMENT ON COLUMN ax.tb_sync_job.target_rows  IS '원본 조회 대상 행 수(건). 시작 시 0, COUNT 직후 확정하고 마감 때 ok+ng 보다 작으면 그만큼 등록';
COMMENT ON COLUMN ax.tb_sync_job.ok_rows      IS '스테이징 COPY 에 성공한 원본 행 수(건). 대상 실제 반영분은 remark 의 "적용 N행"';
COMMENT ON COLUMN ax.tb_sync_job.ng_rows      IS '스테이징 COPY 에 실패한 원본 행 수(건). 1건이라도 있으면 대상 미반영 FAIL';
COMMENT ON COLUMN ax.tb_sync_job.triggered_by IS '실행자 (사번). --user 값이며 기본 SYSTEM, 화면 예약 작업은 요청자';
COMMENT ON COLUMN ax.tb_sync_job.remark       IS '비고. 시작 시 run=실행ID·원본·대상, 종료 시 적용 행수·검증 결과 또는 실패 사유 (500자 절단)';

-- ── ax.tb_sync_job_error  (8)
COMMENT ON COLUMN ax.tb_sync_job_error.err_id     IS '실패 상세 일련번호 (PK)';
COMMENT ON COLUMN ax.tb_sync_job_error.job_id     IS '오류가 난 이관 작업 ID (MIG-YYMMDD-NN)';
COMMENT ON COLUMN ax.tb_sync_job_error.err_seq    IS '작업 내 오류 일련번호 (1부터). 작업 1건당 1,000건까지만 기록';
COMMENT ON COLUMN ax.tb_sync_job_error.err_code   IS '오류 코드. SQLSTATE 값 (22001=길이 초과, 23514=CHECK 위반, 23505=키 중복). 테이블 단위 오류는 TABLE';
COMMENT ON COLUMN ax.tb_sync_job_error.payload    IS '실패한 원본 행 전체 JSON (jsonb). 값은 문자열로 담으며 실패분 재적재에 사용';
COMMENT ON COLUMN ax.tb_sync_job_error.retried_at IS '조치 후 재적재한 일시. 운영자가 resolved 와 함께 수동으로 기록';
COMMENT ON COLUMN ax.tb_sync_job_error.resolved   IS 'true 면 조치 완료. 엔진은 건드리지 않고 운영자가 수동으로 표시하며, 미해결 목록은 NOT resolved';
COMMENT ON COLUMN ax.tb_sync_job_error.ins_date   IS '등록일시';

-- ── ax.tb_sync_map  (15)
COMMENT ON COLUMN ax.tb_sync_map.map_id        IS '이관 매핑 ID (PK). 작업 이력·드리프트 기록이 이 값을 참조';
COMMENT ON COLUMN ax.tb_sync_map.src_db        IS '원본 MSSQL 데이터베이스명 (MESDB_M)';
COMMENT ON COLUMN ax.tb_sync_map.src_schema    IS '원본 MSSQL 스키마명 (dbo)';
COMMENT ON COLUMN ax.tb_sync_map.src_table     IS '원본 MSSQL 테이블명. 원본 표기 그대로 대문자 (TB_MD_ITEM)';
COMMENT ON COLUMN ax.tb_sync_map.tgt_schema    IS '대상 PostgreSQL 스키마명 (mes)';
COMMENT ON COLUMN ax.tb_sync_map.tgt_table     IS '대상 PostgreSQL 테이블명. 소문자 (tb_md_item)';
COMMENT ON COLUMN ax.tb_sync_map.schedule_cron IS '테이블별 개별 이관 주기 (6필드 cron). 비면 전역 스케줄 — 엔진은 아직 쓰지 않음';
COMMENT ON COLUMN ax.tb_sync_map.last_sync_at  IS '증분 워터마크. 마지막 이관의 배치 시작 시각이며 다음 증분 조회의 하한이 됨';
COMMENT ON COLUMN ax.tb_sync_map.last_job_id   IS '마지막으로 성공한 이관 작업 ID (MIG-YYMMDD-NN)';
COMMENT ON COLUMN ax.tb_sync_map.use_flg       IS '이관 대상 여부. Y 면 정기 배치가 이관하고 N 은 제외하되 드리프트 점검에는 남김';
COMMENT ON COLUMN ax.tb_sync_map.remark        IS '비고. 이 매핑의 설명과 이관 방식을 그렇게 정한 이유';
COMMENT ON COLUMN ax.tb_sync_map.ins_date      IS '등록일시';
COMMENT ON COLUMN ax.tb_sync_map.ins_user      IS '등록자 (사번)';
COMMENT ON COLUMN ax.tb_sync_map.upd_date      IS '최종 수정일시';
COMMENT ON COLUMN ax.tb_sync_map.upd_user      IS '최종 수정자 (사번). 엔진이 워터마크를 밀 때는 SYSTEM 이 들어감';

-- ── ax.tb_sync_run  (11)
COMMENT ON COLUMN ax.tb_sync_run.started_at      IS '실행 시작 일시. 프리플라이트보다 먼저 기록해 접속 실패(PREFLIGHT_FAIL)도 남김';
COMMENT ON COLUMN ax.tb_sync_run.ended_at        IS '실행 종료 일시. RUNNING 중에는 NULL 이고 강제 종료분은 다음 기동이 ABORTED 로 채움';
COMMENT ON COLUMN ax.tb_sync_run.duration_sec    IS '실행 소요 시간(초). started_at~ended_at 차이이며 건너뛴 실행(SKIPPED)은 0';
COMMENT ON COLUMN ax.tb_sync_run.triggered_by_cd IS '실행 주체. 공통코드 그룹 = SYNC_TRIGGER (BATCH=정기 배치, MANUAL=즉시·예약 큐 실행, RETRY=작업 재실행). 실행 모드 mode_cd 와는 별개';
COMMENT ON COLUMN ax.tb_sync_run.triggered_by    IS '실행자 (사번). --user 값이며 기본 SYSTEM';
COMMENT ON COLUMN ax.tb_sync_run.success_cnt     IS '성공한 테이블 작업 수(건). state_cd 가 DONE 또는 RETRY_DONE 인 작업만 집계';
COMMENT ON COLUMN ax.tb_sync_run.fail_cnt        IS '실패한 테이블 작업 수(건). 0 이면 state_cd=DONE, 전건이면 FAIL, 일부면 PARTIAL';
COMMENT ON COLUMN ax.tb_sync_run.ok_rows         IS '이번 실행의 적재 행 수 합계(건). 테이블별 ok_rows 의 합이며 모의 실행은 0';
COMMENT ON COLUMN ax.tb_sync_run.ng_rows         IS '이번 실행의 COPY 실패 행 수 합계(건). 테이블별 ng_rows 의 합';
COMMENT ON COLUMN ax.tb_sync_run.target_url      IS '이번 실행이 붙은 대상 PostgreSQL 접속 문자열. 비밀번호는 **** 로 가림';
COMMENT ON COLUMN ax.tb_sync_run.engine_version  IS '실행한 엔진 JAR 버전. 버전 정보가 없으면 dev';

-- ── ax.tb_sync_schema_drift  (2)
COMMENT ON COLUMN ax.tb_sync_schema_drift.drift_id    IS '스키마 드리프트 일련번호 (PK). 데이터 연동 이력 화면 수동 해소 API 의 driftId';
COMMENT ON COLUMN ax.tb_sync_schema_drift.resolved_at IS '해소 일시. 다음 배치에서 사라지면 자동으로 찍히고 재발견되면 NULL 로 되돌림';

-- ── ax.tb_sys_code  (11)
COMMENT ON COLUMN ax.tb_sys_code.group_cd  IS '소속 코드 그룹 (ax.tb_sys_code_group). 예 LOG_AUDIT_TYPE 이면 보안 감사 로그 화면의 「유형」 목록이 됨';
COMMENT ON COLUMN ax.tb_sys_code.code      IS '코드 값. 업무 표의 *_cd 컬럼에 실제로 들어가는 문자열. 보안 감사 로그 화면의 「유형」 등 선택 상자가 저장·조회에 쓰는 값';
COMMENT ON COLUMN ax.tb_sys_code.code_nm   IS '코드 표시명. 화면·보고서에 이 이름이 표시. 보안 감사 로그 화면의 「유형」 등 선택 상자와 표 배지에 보이는 글자';
COMMENT ON COLUMN ax.tb_sys_code.code_desc IS '코드 설명. 화면 도움말이나 선택 시 안내 문구로 사용';
COMMENT ON COLUMN ax.tb_sys_code.attr2     IS '부가 속성 2 (그룹마다 뜻이 다르다)';
COMMENT ON COLUMN ax.tb_sys_code.sort_seq  IS '그룹 안에서의 선택 목록 표시 순서';
COMMENT ON COLUMN ax.tb_sys_code.use_flg   IS '사용 유무 (Y/N). N 이면 선택 목록에서 빠지나 기존 데이터의 표시명은 계속 찾을 수 있음';
COMMENT ON COLUMN ax.tb_sys_code.ins_date  IS '등록일시';
COMMENT ON COLUMN ax.tb_sys_code.ins_user  IS '등록자 (사번)';
COMMENT ON COLUMN ax.tb_sys_code.upd_date  IS '최종 수정일시';
COMMENT ON COLUMN ax.tb_sys_code.upd_user  IS '최종 수정자 (사번)';

-- ── ax.tb_sys_code_group  (8)
COMMENT ON COLUMN ax.tb_sys_code_group.group_nm   IS '그룹 표시명';
COMMENT ON COLUMN ax.tb_sys_code_group.group_desc IS '그룹 설명 — 어느 컬럼이 쓰는 코드 집합인지 기재';
COMMENT ON COLUMN ax.tb_sys_code_group.use_flg    IS '사용 유무 (Y/N)';
COMMENT ON COLUMN ax.tb_sys_code_group.sort_seq   IS '코드 관리 화면의 그룹 표시 순서';
COMMENT ON COLUMN ax.tb_sys_code_group.ins_date   IS '등록일시';
COMMENT ON COLUMN ax.tb_sys_code_group.ins_user   IS '등록자 (사번)';
COMMENT ON COLUMN ax.tb_sys_code_group.upd_date   IS '최종 수정일시';
COMMENT ON COLUMN ax.tb_sys_code_group.upd_user   IS '최종 수정자 (사번)';

-- ── ax.tb_sys_code_ref  (4)
COMMENT ON COLUMN ax.tb_sys_code_ref.target_table  IS '코드를 쓰는 테이블명 (스키마 없이 표 이름만)';
COMMENT ON COLUMN ax.tb_sys_code_ref.target_column IS '코드를 담는 컬럼명';
COMMENT ON COLUMN ax.tb_sys_code_ref.group_cd      IS '그 컬럼이 따라야 하는 코드 그룹 (ax.tb_sys_code_group)';
COMMENT ON COLUMN ax.tb_sys_code_ref.nullable_flg  IS 'NULL 허용 여부 (Y/N). N 이면 ax.fn_check_code_ref() 가 NULL 도 위반으로 검출';

-- ── ax.tb_sys_data_field  (8)
COMMENT ON COLUMN ax.tb_sys_data_field.field_key  IS '데이터 접근 항목 키. API 응답 필드명과의 연결은 ax.tb_sys_data_field_attr. 데이터 접근 권한 화면 항목 등록 폼의 「항목 key」';
COMMENT ON COLUMN ax.tb_sys_data_field.field_nm   IS '항목 표시명. 데이터 접근 권한 화면에 이 이름이 표시. 같은 화면 권한 표의 「데이터 항목」 열';
COMMENT ON COLUMN ax.tb_sys_data_field.field_desc IS '항목 설명 — 어떤 값이 가려지는지 기재. 데이터 접근 권한 화면 「포함 데이터」 열';
COMMENT ON COLUMN ax.tb_sys_data_field.sort_seq   IS '권한 화면의 항목 표시 순서';
COMMENT ON COLUMN ax.tb_sys_data_field.ins_date   IS '등록일시';
COMMENT ON COLUMN ax.tb_sys_data_field.ins_user   IS '등록자 (사번)';
COMMENT ON COLUMN ax.tb_sys_data_field.upd_date   IS '최종 수정일시';
COMMENT ON COLUMN ax.tb_sys_data_field.upd_user   IS '최종 수정자 (사번)';

-- ── ax.tb_sys_data_field_attr  (3)
COMMENT ON COLUMN ax.tb_sys_data_field_attr.field_key IS '이 응답 필드명이 속한 데이터 접근 항목 (ax.tb_sys_data_field)';
COMMENT ON COLUMN ax.tb_sys_data_field_attr.ins_date  IS '등록일시';
COMMENT ON COLUMN ax.tb_sys_data_field_attr.ins_user  IS '등록자 (사번). 최초 적재분은 사람이 아니라 적재 스크립트 이름으로 남아 있음';

-- ── ax.tb_sys_dept  (8)
COMMENT ON COLUMN ax.tb_sys_dept.dept_nm   IS '부서명. 변경될 수 있어 권한 표는 dept_id 로 참조. 계정 관리 화면 부서 표의 「부서」 열과 부서 등록 폼의 「부서명」';
COMMENT ON COLUMN ax.tb_sys_dept.dept_desc IS '부서 설명 — 담당 업무 범위를 기재. 계정 관리 화면 부서 표의 「설명」 열과 부서 등록 폼의 「설명」';
COMMENT ON COLUMN ax.tb_sys_dept.sort_seq  IS '부서 목록 표시 순서';
COMMENT ON COLUMN ax.tb_sys_dept.use_flg   IS '사용 유무 (Y/N). N 이면 부서 선택 목록에서 빠짐';
COMMENT ON COLUMN ax.tb_sys_dept.ins_date  IS '등록일시';
COMMENT ON COLUMN ax.tb_sys_dept.ins_user  IS '등록자 (사번)';
COMMENT ON COLUMN ax.tb_sys_dept.upd_date  IS '최종 수정일시';
COMMENT ON COLUMN ax.tb_sys_dept.upd_user  IS '최종 수정자 (사번)';

-- ── ax.tb_sys_dept_data_perm  (7)
COMMENT ON COLUMN ax.tb_sys_dept_data_perm.dept_id    IS '권한을 받는 부서 (ax.tb_sys_dept). 데이터 접근 권한 화면 권한 표의 부서 열 머리';
COMMENT ON COLUMN ax.tb_sys_dept_data_perm.field_key  IS '대상 데이터 접근 항목 (ax.tb_sys_data_field). 데이터 접근 권한 화면 권한 표의 행';
COMMENT ON COLUMN ax.tb_sys_dept_data_perm.is_allowed IS 'true 면 값을 그대로 보여 주고, false 면 마스킹. 데이터 접근 권한 화면 표의 체크 상태';
COMMENT ON COLUMN ax.tb_sys_dept_data_perm.ins_date   IS '등록일시';
COMMENT ON COLUMN ax.tb_sys_dept_data_perm.ins_user   IS '등록자 (사번)';
COMMENT ON COLUMN ax.tb_sys_dept_data_perm.upd_date   IS '최종 수정일시';
COMMENT ON COLUMN ax.tb_sys_dept_data_perm.upd_user   IS '최종 수정자 (사번)';

-- ── ax.tb_sys_dept_menu_perm  (7)
COMMENT ON COLUMN ax.tb_sys_dept_menu_perm.dept_id  IS '권한을 받는 부서 (ax.tb_sys_dept). 메뉴 접근 권한 화면 메뉴 권한 표의 부서 열 머리';
COMMENT ON COLUMN ax.tb_sys_dept_menu_perm.menu_id  IS '대상 화면 (ax.tb_sys_menu). 메뉴 접근 권한 화면 메뉴 권한 표의 행';
COMMENT ON COLUMN ax.tb_sys_dept_menu_perm.can_read IS '조회 권한. 행이 있으면서 true 여야 화면에 들어갈 수 있음. 메뉴 접근 권한 화면 표의 체크 상태';
COMMENT ON COLUMN ax.tb_sys_dept_menu_perm.ins_date IS '등록일시';
COMMENT ON COLUMN ax.tb_sys_dept_menu_perm.ins_user IS '등록자 (사번)';
COMMENT ON COLUMN ax.tb_sys_dept_menu_perm.upd_date IS '최종 수정일시';
COMMENT ON COLUMN ax.tb_sys_dept_menu_perm.upd_user IS '최종 수정자 (사번)';

-- ── ax.tb_sys_email_verify  (9)
COMMENT ON COLUMN ax.tb_sys_email_verify.verify_id      IS '인증 요청 대리키';
COMMENT ON COLUMN ax.tb_sys_email_verify.email          IS '인증 코드를 보낼 주소. 가입 전 계정도 있어 tb_sys_user 를 참조하지 않음. 회원가입·비밀번호 찾기 화면에 마스킹해 표시';
COMMENT ON COLUMN ax.tb_sys_email_verify.expires_at     IS '인증 코드 만료 시각. 지나면 검증을 받지 않음';
COMMENT ON COLUMN ax.tb_sys_email_verify.verified_at    IS '코드 검증에 성공한 시각. 비어 있으면 아직 미검증';
COMMENT ON COLUMN ax.tb_sys_email_verify.consumed_at    IS '발급한 1회용 토큰을 실제로 쓴 시각. 채워지면 재사용할 수 없음';
COMMENT ON COLUMN ax.tb_sys_email_verify.send_result_cd IS '메일 발송 결과. 공통코드 그룹 = EMAIL_SEND_RESULT (SENT=발송, FAIL=실패, SUPPRESSED=발송 억제)';
COMMENT ON COLUMN ax.tb_sys_email_verify.fail_reason    IS '발송·폐기 사유 (예: 새 인증 코드 발송으로 폐기)';
COMMENT ON COLUMN ax.tb_sys_email_verify.ip_addr        IS '인증을 요청한 접속 IP. 무차별 시도 추적에 사용';
COMMENT ON COLUMN ax.tb_sys_email_verify.ins_date       IS '요청 등록일시';

-- ── ax.tb_sys_login_hist  (6)
COMMENT ON COLUMN ax.tb_sys_login_hist.login_id    IS '접속 이력 대리키';
COMMENT ON COLUMN ax.tb_sys_login_hist.login_at    IS '로그인 시도 시각. 계정 관리 화면 계정 표의 「최근 접속」 열에 가장 최근 값이 표시';
COMMENT ON COLUMN ax.tb_sys_login_hist.logout_at   IS '로그아웃 시각. 세션 만료로 끝나면 비어 있음';
COMMENT ON COLUMN ax.tb_sys_login_hist.fail_reason IS '실패 사유 (비밀번호 불일치·잠금 등). 성공이면 비어 있음';
COMMENT ON COLUMN ax.tb_sys_login_hist.ip_addr     IS '접속 IP. 화면에는 host() 로 주소만 표시';
COMMENT ON COLUMN ax.tb_sys_login_hist.user_agent  IS '접속 브라우저 정보. 이상 접속 확인에 사용';

-- ── ax.tb_sys_menu  (9)
COMMENT ON COLUMN ax.tb_sys_menu.menu_nm    IS '화면 표시명. 사이드바 이름이 이 값이라 바꾸면 화면에 바로 반영됨. 메뉴 접근 권한 화면의 「화면」 열과 좌측 사이드바의 메뉴 항목명';
COMMENT ON COLUMN ax.tb_sys_menu.group_id   IS '소속 사이드바 그룹 (ax.tb_sys_menu_group)';
COMMENT ON COLUMN ax.tb_sys_menu.route_path IS '화면 경로 (해시 라우트). 경로 없는 동작 권한이면 비어 있음';
COMMENT ON COLUMN ax.tb_sys_menu.sort_seq   IS '그룹 안에서의 사이드바 표시 순서';
COMMENT ON COLUMN ax.tb_sys_menu.use_flg    IS '사용 유무 (Y/N). N 이면 사이드바에서 내려가나 API 와 권한 행은 남음';
COMMENT ON COLUMN ax.tb_sys_menu.ins_date   IS '등록일시';
COMMENT ON COLUMN ax.tb_sys_menu.ins_user   IS '등록자 (사번)';
COMMENT ON COLUMN ax.tb_sys_menu.upd_date   IS '최종 수정일시';
COMMENT ON COLUMN ax.tb_sys_menu.upd_user   IS '최종 수정자 (사번)';

-- ── ax.tb_sys_menu_group  (4)
COMMENT ON COLUMN ax.tb_sys_menu_group.group_id IS '메뉴 그룹 ID (dashboard, system ...)';
COMMENT ON COLUMN ax.tb_sys_menu_group.group_nm IS '그룹 표시명. 사이드바 머리글이 이 값. 좌측 사이드바 대분류와 메뉴 접근 권한 화면 표의 그룹 펼침 줄';
COMMENT ON COLUMN ax.tb_sys_menu_group.sort_seq IS '사이드바 그룹 표시 순서';
COMMENT ON COLUMN ax.tb_sys_menu_group.use_flg  IS '사용 유무 (Y/N). N 이면 그룹째 사이드바에서 빠짐';

-- ── ax.tb_sys_perm_log  (7)
COMMENT ON COLUMN ax.tb_sys_perm_log.log_id         IS '변경 이력 대리키';
COMMENT ON COLUMN ax.tb_sys_perm_log.log_at         IS '변경 시각. 화면 기본 정렬 키. 계정 관리 화면 「변경 이력」 표의 「시각」 열';
COMMENT ON COLUMN ax.tb_sys_perm_log.target_kind_cd IS '대상 종류 — USER(계정) / DEPT(부서) / MENU(화면) / FIELD(데이터 항목). 계정 관리 화면의 변경 이력 「구분」 열의 계정·부서 배지';
COMMENT ON COLUMN ax.tb_sys_perm_log.target_dept_id IS '대상이 부서일 때의 부서 (ax.tb_sys_dept). 계정 관리 화면 변경 이력의 「대상」 열';
COMMENT ON COLUMN ax.tb_sys_perm_log.target_user_id IS '대상이 계정일 때의 사번. 계정 관리 화면 변경 이력의 「대상」 열';
COMMENT ON COLUMN ax.tb_sys_perm_log.detail         IS '무엇이 어떻게 바뀌었는지 사람이 읽는 설명 (변경 전후 값). 계정 관리 화면 변경 이력의 「변경 내용」 열';
COMMENT ON COLUMN ax.tb_sys_perm_log.actor_user_id  IS '변경을 수행한 관리자 사번. 계정 관리 화면 변경 이력의 「수행자」 열';

-- ── ax.tb_sys_plant  (9)
COMMENT ON COLUMN ax.tb_sys_plant.plant_cd IS '사업부 구분 코드 (PL01=모바일, PL03=전장). mes 전 테이블의 plant_cd 마스터';
COMMENT ON COLUMN ax.tb_sys_plant.plant_nm IS '사업부명. 불량 현황 조회 상세 표의 「공장」 열';
COMMENT ON COLUMN ax.tb_sys_plant.remark   IS '비고';
COMMENT ON COLUMN ax.tb_sys_plant.sort_seq IS '사업부 선택 목록 표시 순서';
COMMENT ON COLUMN ax.tb_sys_plant.use_flg  IS '사용 유무 (Y/N)';
COMMENT ON COLUMN ax.tb_sys_plant.ins_date IS '등록일시';
COMMENT ON COLUMN ax.tb_sys_plant.ins_user IS '등록자 (사번)';
COMMENT ON COLUMN ax.tb_sys_plant.upd_date IS '최종 수정일시';
COMMENT ON COLUMN ax.tb_sys_plant.upd_user IS '최종 수정자 (사번)';

-- ── ax.tb_sys_user  (11)
COMMENT ON COLUMN ax.tb_sys_user.user_nm        IS '사용자 이름. 화면 상단·이력의 표시명이 이 값. 계정 관리 화면 계정 표의 「이름」 열과 좌측 하단 계정 카드';
COMMENT ON COLUMN ax.tb_sys_user.dept_id        IS '소속 부서 (ax.tb_sys_dept). 화면·데이터 접근 권한을 이 부서에서 상속. 계정 관리 화면의 「소속 부서」 열과 계정 등록 폼의 「소속 부서」';
COMMENT ON COLUMN ax.tb_sys_user.plant_cd       IS '소속 사업부 구분 코드 (PL01=모바일, PL03=전장)';
COMMENT ON COLUMN ax.tb_sys_user.pwd_hash       IS '비밀번호 해시 (PBKDF2-SHA512). 평문은 저장하지 않음';
COMMENT ON COLUMN ax.tb_sys_user.pwd_upd_at     IS '비밀번호를 마지막으로 바꾼 시각. 초기 비밀번호 여부 판단에 사용';
COMMENT ON COLUMN ax.tb_sys_user.login_fail_cnt IS '연속 로그인 실패 횟수. 상한(기본 5)에 닿으면 잠기고 성공하면 0 으로 복귀. 계정 관리 화면 계정 표의 「로그인 실패」 열';
COMMENT ON COLUMN ax.tb_sys_user.remark         IS '비고 — 계정 정지 사유 등 관리 메모';
COMMENT ON COLUMN ax.tb_sys_user.ins_date       IS '등록일시 — 가입 신청 시각';
COMMENT ON COLUMN ax.tb_sys_user.ins_user       IS '등록자 (사번). 회원가입으로 만들어졌으면 비어 있음';
COMMENT ON COLUMN ax.tb_sys_user.upd_date       IS '최종 수정일시';
COMMENT ON COLUMN ax.tb_sys_user.upd_user       IS '최종 수정자 (사번)';

-- ── ax.tb_sys_user_favorite  (2)
COMMENT ON COLUMN ax.tb_sys_user_favorite.user_id  IS '즐겨찾기를 등록한 사번 (ax.tb_sys_user). 계정 삭제 시 함께 삭제';
COMMENT ON COLUMN ax.tb_sys_user_favorite.ins_date IS '등록일시';

-- ── ax.tb_sys_user_menu_grant  (3)
COMMENT ON COLUMN ax.tb_sys_user_menu_grant.ins_date IS '권한을 부여한 일시';
COMMENT ON COLUMN ax.tb_sys_user_menu_grant.upd_date IS '최종 수정일시';
COMMENT ON COLUMN ax.tb_sys_user_menu_grant.upd_user IS '최종 수정자 (사번)';

-- ══════════════════════════════════════════════════════════════════════════════════
--  vec 스키마
-- ══════════════════════════════════════════════════════════════════════════════════

-- ── vec.tb_code_ref  (4)
COMMENT ON COLUMN vec.tb_code_ref.target_table  IS '검사 대상 표 이름. vec 스키마 안의 이름만 기재';
COMMENT ON COLUMN vec.tb_code_ref.target_column IS '검사 대상 코드 컬럼명';
COMMENT ON COLUMN vec.tb_code_ref.group_cd      IS '대조할 공통코드 그룹 (ax.tb_sys_code.group_cd)';
COMMENT ON COLUMN vec.tb_code_ref.nullable_flg  IS 'N = NULL 값도 위반으로 검출. fn_check_code_ref() 의 판정 기준';

-- ── vec.tb_doc  (26)
COMMENT ON COLUMN vec.tb_doc.doc_id         IS '문서 식별자 (PK)';
COMMENT ON COLUMN vec.tb_doc.title          IS '문서 제목. 검색 결과의 표시 이름이며 trigram 색인이 걸려 있음';
COMMENT ON COLUMN vec.tb_doc.source_path    IS '원본 경로(NAS 등). 재수집과 원문 열람의 기준';
COMMENT ON COLUMN vec.tb_doc.file_nm        IS '원본 파일명';
COMMENT ON COLUMN vec.tb_doc.mime_type      IS '원본 MIME 타입 (예: application/pdf)';
COMMENT ON COLUMN vec.tb_doc.file_size      IS '원본 파일 크기(바이트)';
COMMENT ON COLUMN vec.tb_doc.page_cnt       IS '원본 쪽 수';
COMMENT ON COLUMN vec.tb_doc.lang_cd        IS '문서 언어 (기본 ko)';
COMMENT ON COLUMN vec.tb_doc.report_id      IS '이 문서를 만든 보고서 (ax.tb_rpt_report). 보고서 산출물일 때만 채움';
COMMENT ON COLUMN vec.tb_doc.customer_id    IS '관련 고객사 (ax.tb_prod_customer). 문서 검색과 실적 조회를 잇는 키';
COMMENT ON COLUMN vec.tb_doc.product_id     IS '관련 제품 모델 (ax.tb_prod_product)';
COMMENT ON COLUMN vec.tb_doc.author_user_id IS '작성자 사번. scope_cd = OWNER 문서는 이 사람만 열람';
COMMENT ON COLUMN vec.tb_doc.plant_cd       IS '공장 코드. 청크에 복제돼 검색 필터로 쓰임';
COMMENT ON COLUMN vec.tb_doc.wc_cd          IS '관련 작업장 코드. 1,476건 전부 비어 있어 검색 조건으로 쓰지 않음';
COMMENT ON COLUMN vec.tb_doc.eqpt_cd        IS '관련 설비 코드. wc_cd 와 마찬가지로 비어 있어 질의 문장으로 대신 탐색';
COMMENT ON COLUMN vec.tb_doc.mold_cd        IS '관련 금형 코드 (MES)';
COMMENT ON COLUMN vec.tb_doc.item_cd        IS '관련 품목 코드 (MES). 품목으로 문서를 좁힐 때 사용';
COMMENT ON COLUMN vec.tb_doc.serial_no      IS '관련 시리얼 번호 (MES)';
COMMENT ON COLUMN vec.tb_doc.defect_cd      IS '관련 불량 코드 (MES). 불량 유형으로 문서를 좁힐 때 사용';
COMMENT ON COLUMN vec.tb_doc.chunk_cnt      IS '현재 버전(cur_ver)의 청크 수';
COMMENT ON COLUMN vec.tb_doc.del_flg        IS 'Y = 삭제된 문서. 청크에 복제돼 색인에서도 함께 빠짐';
COMMENT ON COLUMN vec.tb_doc.remark         IS '비고';
COMMENT ON COLUMN vec.tb_doc.ins_date       IS '등록일시';
COMMENT ON COLUMN vec.tb_doc.ins_user       IS '등록자 (사번)';
COMMENT ON COLUMN vec.tb_doc.upd_date       IS '최종 수정일시';
COMMENT ON COLUMN vec.tb_doc.upd_user       IS '최종 수정자 (사번)';

-- ── vec.tb_doc_chunk  (14)
COMMENT ON COLUMN vec.tb_doc_chunk.chunk_id       IS '청크 식별자 (PK). 응답 근거(citation)가 가리키는 값';
COMMENT ON COLUMN vec.tb_doc_chunk.doc_id         IS '원본 문서 (vec.tb_doc)';
COMMENT ON COLUMN vec.tb_doc_chunk.doc_ver        IS '이 청크를 만든 문서 버전. tb_doc.cur_ver 와 같으면 is_current 가 참';
COMMENT ON COLUMN vec.tb_doc_chunk.chunk_seq      IS '문서 안 청크 순번. 1 부터';
COMMENT ON COLUMN vec.tb_doc_chunk.char_cnt       IS '청크 문자 수';
COMMENT ON COLUMN vec.tb_doc_chunk.heading        IS '청크가 속한 소제목. 검색 결과에 위치를 함께 표시';
COMMENT ON COLUMN vec.tb_doc_chunk.embed_model_id IS '임베딩에 쓴 모델 (vec.tb_embed_model). 차원이 다르면 함께 검색할 수 없음';
COMMENT ON COLUMN vec.tb_doc_chunk.embedded_at    IS '임베딩 완료 시각. NULL 이면 청킹만 되고 벡터가 없음';
COMMENT ON COLUMN vec.tb_doc_chunk.doc_type_cd    IS '문서 유형 (tb_doc 복제값). 공통코드 그룹 = VEC_DOC_TYPE (REPORT_8D=8D 리포트, FACA, SPEC=규격서·도면 등)';
COMMENT ON COLUMN vec.tb_doc_chunk.doc_date       IS '문서 일자 (tb_doc 복제값). 기간 필터를 청크에서 바로 적용';
COMMENT ON COLUMN vec.tb_doc_chunk.plant_cd       IS '공장 코드 (tb_doc 복제값). 검색 필터 조건';
COMMENT ON COLUMN vec.tb_doc_chunk.owner_dept_id  IS '소관 부서 (tb_doc 복제값, ax.tb_sys_dept). 권한 판정에 사용';
COMMENT ON COLUMN vec.tb_doc_chunk.scope_cd       IS '공개 범위 (tb_doc 복제값). 공통코드 그룹 = VEC_SCOPE (ALL=전사 공개, DEPT=소관 부서+ACL, ACL=명시 부서만, OWNER=작성자만)';
COMMENT ON COLUMN vec.tb_doc_chunk.ins_date       IS '등록일시. 복제 컬럼은 trg_doc_sync_chunk 가 문서 변경 때 맞춰 갱신';

-- ── vec.tb_doc_chunk_embed_ext  (3)
COMMENT ON COLUMN vec.tb_doc_chunk_embed_ext.chunk_id    IS '대상 청크 (vec.tb_doc_chunk)';
COMMENT ON COLUMN vec.tb_doc_chunk_embed_ext.model_id    IS '대체 임베딩 모델 (vec.tb_embed_model). 본 컬럼과 병행 평가하는 모델';
COMMENT ON COLUMN vec.tb_doc_chunk_embed_ext.embedded_at IS '이 모델로 임베딩한 시각';

-- ── vec.tb_doc_data_field  (3)
COMMENT ON COLUMN vec.tb_doc_data_field.doc_id    IS '대상 문서 (vec.tb_doc). 문서를 지우면 함께 삭제됨';
COMMENT ON COLUMN vec.tb_doc_data_field.field_key IS '문서에 들어 있는 민감 데이터 항목 (ax.tb_sys_data_field — qty·price 등)';
COMMENT ON COLUMN vec.tb_doc_data_field.ins_date  IS '등록일시';

-- ── vec.tb_doc_dept_perm  (5)
COMMENT ON COLUMN vec.tb_doc_dept_perm.doc_id   IS '대상 문서 (vec.tb_doc). 문서를 지우면 함께 삭제됨';
COMMENT ON COLUMN vec.tb_doc_dept_perm.dept_id  IS '열람을 허용한 부서 (ax.tb_sys_dept)';
COMMENT ON COLUMN vec.tb_doc_dept_perm.can_read IS 'true = 그 부서가 이 문서를 읽을 수 있음. scope_cd = DEPT·ACL 에서 판정됨';
COMMENT ON COLUMN vec.tb_doc_dept_perm.ins_date IS '등록일시';
COMMENT ON COLUMN vec.tb_doc_dept_perm.ins_user IS '등록자 (사번)';

-- ── vec.tb_doc_entity  (15)
COMMENT ON COLUMN vec.tb_doc_entity.entity_id   IS '엔터티 링크 식별자 (PK)';
COMMENT ON COLUMN vec.tb_doc_entity.doc_id      IS '대상 문서 (vec.tb_doc)';
COMMENT ON COLUMN vec.tb_doc_entity.chunk_id    IS '표현이 나온 청크. NULL 이면 문서 단위로만 확정한 것';
COMMENT ON COLUMN vec.tb_doc_entity.plant_cd    IS '확정된 공장 코드 (MES)';
COMMENT ON COLUMN vec.tb_doc_entity.wc_cd       IS '확정된 작업장 코드 (MES)';
COMMENT ON COLUMN vec.tb_doc_entity.eqpt_cd     IS '확정된 설비 코드 (MES)';
COMMENT ON COLUMN vec.tb_doc_entity.mold_cd     IS '확정된 금형 코드 (MES)';
COMMENT ON COLUMN vec.tb_doc_entity.item_cd     IS '확정된 품목 코드 (MES)';
COMMENT ON COLUMN vec.tb_doc_entity.lot_no      IS '확정된 LOT 번호 (MES)';
COMMENT ON COLUMN vec.tb_doc_entity.serial_no   IS '확정된 시리얼 번호 (MES)';
COMMENT ON COLUMN vec.tb_doc_entity.defect_cd   IS '확정된 불량 코드 (MES)';
COMMENT ON COLUMN vec.tb_doc_entity.product_id  IS '확정된 제품 모델 (ax.tb_prod_product)';
COMMENT ON COLUMN vec.tb_doc_entity.customer_id IS '확정된 고객사 (ax.tb_prod_customer)';
COMMENT ON COLUMN vec.tb_doc_entity.ref_user_id IS '문서가 가리킨 인원 사번. entity_type_cd = USER 일 때 채움';
COMMENT ON COLUMN vec.tb_doc_entity.ins_date    IS '등록일시';

-- ── vec.tb_doc_version  (11)
COMMENT ON COLUMN vec.tb_doc_version.doc_id        IS '대상 문서 (vec.tb_doc)';
COMMENT ON COLUMN vec.tb_doc_version.doc_ver       IS '문서 버전 번호. 청크가 이 값을 물어 무중단 재임베딩이 가능';
COMMENT ON COLUMN vec.tb_doc_version.content_hash  IS '본문 해시. 같으면 재추출·재임베딩을 건너뜀';
COMMENT ON COLUMN vec.tb_doc_version.parser_ver    IS '추출기 버전. 파서를 올리면 결과가 달라져 재현에 필요';
COMMENT ON COLUMN vec.tb_doc_version.page_cnt      IS '추출된 쪽 수';
COMMENT ON COLUMN vec.tb_doc_version.char_cnt      IS '추출된 본문 문자 수';
COMMENT ON COLUMN vec.tb_doc_version.chunk_cnt     IS '이 버전에서 만든 청크 수';
COMMENT ON COLUMN vec.tb_doc_version.extracted_at  IS '텍스트 추출 시각';
COMMENT ON COLUMN vec.tb_doc_version.embedded_at   IS '임베딩 완료 시각. NULL 이면 아직 벡터가 없음';
COMMENT ON COLUMN vec.tb_doc_version.ingest_job_id IS '이 버전을 만든 수집 배치 (vec.tb_ingest_job)';
COMMENT ON COLUMN vec.tb_doc_version.remark        IS '비고';

-- ── vec.tb_embed_model  (11)
COMMENT ON COLUMN vec.tb_embed_model.model_id     IS '임베딩 모델 식별자 (PK)';
COMMENT ON COLUMN vec.tb_embed_model.model_nm     IS '모델 표시 이름';
COMMENT ON COLUMN vec.tb_embed_model.max_tokens   IS '한 번에 임베딩할 수 있는 최대 토큰 수. 넘는 청크는 걸러냄';
COMMENT ON COLUMN vec.tb_embed_model.endpoint_url IS '추론 엔드포인트 URL. 사내 호스팅이면 내부 주소';
COMMENT ON COLUMN vec.tb_embed_model.is_default   IS 'true = 기본 임베딩 모델. 모델을 지정하지 않은 색인이 이 모델을 사용';
COMMENT ON COLUMN vec.tb_embed_model.use_flg      IS '사용 여부. N 이면 기본 모델 선택에서 빠짐';
COMMENT ON COLUMN vec.tb_embed_model.remark       IS '비고';
COMMENT ON COLUMN vec.tb_embed_model.ins_date     IS '등록일시';
COMMENT ON COLUMN vec.tb_embed_model.ins_user     IS '등록자 (사번)';
COMMENT ON COLUMN vec.tb_embed_model.upd_date     IS '최종 수정일시';
COMMENT ON COLUMN vec.tb_embed_model.upd_user     IS '최종 수정자 (사번)';

-- ── vec.tb_ingest_error  (9)
COMMENT ON COLUMN vec.tb_ingest_error.err_id   IS '수집 오류 식별자 (PK)';
COMMENT ON COLUMN vec.tb_ingest_error.job_id   IS '실패가 난 수집 배치 (vec.tb_ingest_job)';
COMMENT ON COLUMN vec.tb_ingest_error.doc_id   IS '실패한 문서 (vec.tb_doc)';
COMMENT ON COLUMN vec.tb_ingest_error.chunk_id IS '실패한 청크. 청킹 이후 단계(EMBED 등)에서만 채워짐';
COMMENT ON COLUMN vec.tb_ingest_error.err_code IS '오류 코드';
COMMENT ON COLUMN vec.tb_ingest_error.err_msg  IS '오류 메시지';
COMMENT ON COLUMN vec.tb_ingest_error.payload  IS '실패 당시 입력·응답 원본(JSON). 재처리 판단 근거';
COMMENT ON COLUMN vec.tb_ingest_error.resolved IS 'true = 조치 완료. 재처리 대상 목록에서 빠짐';
COMMENT ON COLUMN vec.tb_ingest_error.ins_date IS '등록일시';

-- ── vec.tb_ingest_job  (14)
COMMENT ON COLUMN vec.tb_ingest_job.job_id          IS '수집 배치 식별자 (PK). JOB-yyyyMMddHHmmss 형식으로 채번';
COMMENT ON COLUMN vec.tb_ingest_job.embed_model_id  IS '이 배치가 쓴 임베딩 모델 (vec.tb_embed_model)';
COMMENT ON COLUMN vec.tb_ingest_job.started_at      IS '시작 시각';
COMMENT ON COLUMN vec.tb_ingest_job.ended_at        IS '종료 시각';
COMMENT ON COLUMN vec.tb_ingest_job.duration_sec    IS '소요 시간(초)';
COMMENT ON COLUMN vec.tb_ingest_job.doc_cnt         IS '처리 대상 문서 수';
COMMENT ON COLUMN vec.tb_ingest_job.chunk_cnt       IS '만들어진 청크 수';
COMMENT ON COLUMN vec.tb_ingest_job.embed_cnt       IS '임베딩한 청크 수';
COMMENT ON COLUMN vec.tb_ingest_job.ok_cnt          IS '성공 건수';
COMMENT ON COLUMN vec.tb_ingest_job.ng_cnt          IS '실패 건수. 상세는 vec.tb_ingest_error 에 남음';
COMMENT ON COLUMN vec.tb_ingest_job.state_cd        IS '상태. 공통코드 그룹 = SYNC_STATE (PENDING=예약 대기, RUNNING=진행 중, DONE=완료, FAIL=실패)';
COMMENT ON COLUMN vec.tb_ingest_job.triggered_by_cd IS '실행 주체. 공통코드 그룹 = SYNC_TRIGGER (BATCH=배치, MANUAL=수동 이관, RETRY=재실행)';
COMMENT ON COLUMN vec.tb_ingest_job.triggered_by    IS '실행을 요청한 사번. 용어 사전 관리 화면의 용어 재색인은 누른 사람이 들어감';
COMMENT ON COLUMN vec.tb_ingest_job.remark          IS '비고 (예: 용어 사전 임베딩 재생성 (280건))';

-- ── vec.tb_query_hit  (7)
COMMENT ON COLUMN vec.tb_query_hit.query_id  IS '대상 질의 (vec.tb_query_log)';
COMMENT ON COLUMN vec.tb_query_hit.chunk_id  IS '검색된 청크 (vec.tb_doc_chunk). FK 를 걸지 않아 문서가 지워져도 이력은 남음';
COMMENT ON COLUMN vec.tb_query_hit.doc_id    IS '그 청크의 문서 (vec.tb_doc). FK 를 걸지 않음';
COMMENT ON COLUMN vec.tb_query_hit.vec_sim   IS '벡터 경로 점수 = 1 − (embedding <=> 질의벡터) 코사인 유사도. 1 에 가까울수록 유사';
COMMENT ON COLUMN vec.tb_query_hit.ts_score  IS '전문검색 경로 점수 = ts_rank_cd(tsv, websearch_to_tsquery). 클수록 적합';
COMMENT ON COLUMN vec.tb_query_hit.trgm_sim  IS '트라이그램 경로 점수 = word_similarity(질의, 청크 본문) 0~1. 임계값 기본 0.4';
COMMENT ON COLUMN vec.tb_query_hit.rrf_score IS 'RRF 융합 점수 = Σ 1/(rrf_k + 경로별 순위), 세 경로 합 (rrf_k 기본 60)';

-- ── vec.tb_query_log  (14)
COMMENT ON COLUMN vec.tb_query_log.query_id        IS '검색 이력 식별자 (PK)';
COMMENT ON COLUMN vec.tb_query_log.chat_id         IS '대응하는 채팅 질의 (ax.tb_ai_chat_log)';
COMMENT ON COLUMN vec.tb_query_log.asked_at        IS '질의 시각';
COMMENT ON COLUMN vec.tb_query_log.user_id         IS '질의자 사번 (ax.tb_sys_user). 문서 권한 판정의 주체';
COMMENT ON COLUMN vec.tb_query_log.dept_id         IS '질의자 부서 (ax.tb_sys_dept). 문서 열람 권한 판정 기준';
COMMENT ON COLUMN vec.tb_query_log.query_text      IS '사용자가 입력한 원문 질의';
COMMENT ON COLUMN vec.tb_query_log.query_embedding IS '질의 임베딩 벡터(1024차원). 유사 질의 캐싱·의도 클러스터링에 사용';
COMMENT ON COLUMN vec.tb_query_log.embed_model_id  IS '질의 임베딩에 쓴 모델 (vec.tb_embed_model)';
COMMENT ON COLUMN vec.tb_query_log.top_k           IS '최종 반환 요청 청크 수 (기본 10)';
COMMENT ON COLUMN vec.tb_query_log.candidate_k     IS '경로별 후보 수 (기본 60). 벡터·전문검색·트라이그램이 각각 이 개수만큼 후보를 추출';
COMMENT ON COLUMN vec.tb_query_log.hit_cnt         IS '최종 반환된 청크 수. top_k 이하이며 cited_cnt 와의 비율이 인용률';
COMMENT ON COLUMN vec.tb_query_log.embed_ms        IS '질의 임베딩 소요 시간(ms). search_ms 와 나눠 기록해야 병목을 구분';
COMMENT ON COLUMN vec.tb_query_log.search_ms       IS '하이브리드 검색(3경로 + RRF 융합) 소요 시간(ms)';
COMMENT ON COLUMN vec.tb_query_log.total_ms        IS '임베딩·검색·리랭킹을 합친 전체 소요 시간(ms)';

-- ── vec.tb_term_embedding  (6)
COMMENT ON COLUMN vec.tb_term_embedding.term_emb_id    IS '용어 임베딩 식별자 (PK)';
COMMENT ON COLUMN vec.tb_term_embedding.term_id        IS '대상 공식 용어 (ax.tb_gls_term)';
COMMENT ON COLUMN vec.tb_term_embedding.variant_id     IS '대상 유사어 (ax.tb_gls_variant). NULL 이면 공식 용어 자체의 벡터';
COMMENT ON COLUMN vec.tb_term_embedding.embed_model_id IS '임베딩에 쓴 모델 (vec.tb_embed_model)';
COMMENT ON COLUMN vec.tb_term_embedding.embedding      IS '용어 임베딩 벡터(1024차원). 질의 정규화와 유사어 후보 추천에 사용';
COMMENT ON COLUMN vec.tb_term_embedding.embedded_at    IS '임베딩 시각';

COMMIT;

-- ── 결과 확인 ───────────────────────────────────────────────────────────────────────
\echo ''
\echo '-- 주석 없는 컬럼 수 (0 이어야 정상) --'
SELECT n.nspname AS "스키마", count(*) AS "주석없음"
  FROM pg_attribute a
  JOIN pg_class c ON c.oid = a.attrelid
  JOIN pg_namespace n ON n.oid = c.relnamespace
  LEFT JOIN pg_description d ON d.objoid = c.oid AND d.objsubid = a.attnum
 WHERE c.relkind = 'r' AND n.nspname IN ('ax','mes','vec')
   AND a.attnum > 0 AND NOT a.attisdropped AND d.description IS NULL
 GROUP BY 1 ORDER BY 1;
\echo ''
