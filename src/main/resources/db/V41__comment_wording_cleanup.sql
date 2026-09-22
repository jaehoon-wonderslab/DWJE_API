-- =====================================================================================
--  V41 : 예전부터 있던 주석의 문구 정리 (2026-09-22)
--
--  ┌─ 실행 방법 ──────────────────────────────────────────────────────────────────────┐
--  │   psql -h localhost -U dwje_local -d dwjedb -f V41__comment_wording_cleanup.sql   │
--  │   · BEGIN/COMMIT 을 직접 들고 있으므로 --single-transaction 은 붙이지 않는다.      │
--  │   · 두 번 실행해도 안전하다. 되돌리기 : rollback/V41__down.sql                     │
--  └──────────────────────────────────────────────────────────────────────────────────┘
--
--  [왜]
--  주석에 개발 내부 표기가 그대로 들어가 있어, 이 프로젝트를 모르는 사람이 읽으면
--  뜻을 알 수 없었다.
--    · 화면번호  SY-14 · DB-02 · RP-03 …  — 기능명세서를 봐야만 아는 기호
--    · 개발 표기 "프로토타입" · V33 · No.235 · "10.7 절"
--  또 문장이 '-다' 로 끝나 설명문처럼 길게 읽혔다. 주석은 항목의 뜻을 가리키는 말이므로
--  명사형으로 맺는 편이 읽기 쉽다.
--
--  [무엇을 바꿨는가]
--    · 화면번호 → 사람이 아는 화면 이름 (예: SY-14 → 보고서 다운로드 이력 화면)
--    · 개발 내부 표기 제거
--    · 문장 끝 '-다' → 명사형 (…사용 / …없음 / …기록 / …차단)
--  뜻은 바꾸지 않았다. 표현만 고쳤다.
--
--  대상 : 200 건 (표·뷰 68 · 컬럼 132)
--  ※ V40 이 새로 단 컬럼 주석은 처음부터 이 규칙으로 썼으므로 여기 없다.
-- =====================================================================================

\set ON_ERROR_STOP on

BEGIN;

-- ══════════════════════════════════════════════════════════════════════════════════
--  ax 스키마
-- ══════════════════════════════════════════════════════════════════════════════════

-- ── ax.tb_ai_agent_run  (1)
COMMENT ON TABLE ax.tb_ai_agent_run IS 'Agent 실행 이력 — 화면의 상태·최근 실행·처리량은 이 테이블의 최신 행에서 산출';

-- ── ax.tb_ai_chat_agent  (1)
COMMENT ON TABLE ax.tb_ai_chat_agent IS '질의 × 호출 Agent — 화면의 "호출 Agent" 열이 이 표를 읽음';

-- ── ax.tb_ai_chat_log  (2)
COMMENT ON TABLE ax.tb_ai_chat_log IS '자연어 질의 이력 — 질의·해석된 의도·호출 Agent·응답 시간·평가. 의도 파악 정확도와 재질의율의 산출 근거';
COMMENT ON COLUMN ax.tb_ai_chat_log.profile_id IS '이 응답을 만든 서빙 버전 (ax.tb_ai_serving_profile). 버전별 품질 비교와 회귀 추적의 기준';

-- ── ax.tb_ai_chat_term  (1)
COMMENT ON TABLE ax.tb_ai_chat_term IS '질의에서 정규화된 용어 — 어떤 유사어가 어떤 공식 용어로 치환되었는지 기록해 사전 품질을 개선';

-- ── ax.tb_ai_corpus_snapshot  (3)
COMMENT ON TABLE ax.tb_ai_corpus_snapshot IS '벡터 코퍼스 스냅샷 — 벡터화 결과의 버전. 어떤 임베딩 모델로 어느 문서 범위를 언제 색인했는지를 한 행으로 고정';
COMMENT ON COLUMN ax.tb_ai_corpus_snapshot.dim      IS '임베딩 차원. 스냅샷 간 차원이 다르면 같은 인덱스를 공유할 수 없음';
COMMENT ON COLUMN ax.tb_ai_corpus_snapshot.state_cd IS '상태. 공통코드 그룹 = AI_CORPUS_STATE (BUILDING=구축 중, READY=사용 가능, FAILED=실패, RETIRED=폐기). READY 가 아니면 프로필을 활성화할 수 없음';

-- ── ax.tb_ai_defect_tag_map  (1)
COMMENT ON TABLE ax.tb_ai_defect_tag_map IS 'AI 태그 ↔ MES 불량 코드 매핑. mes.tb_md_defect (plant_cd, defect_cd) 를 논리 참조';

-- ── ax.tb_ai_model_asset  (8)
COMMENT ON TABLE ax.tb_ai_model_asset IS '모델 자산 레지스트리 — 서버에 놓인 모델 파일(베이스 · LoRA 어댑터 · 임베딩 · 리랭커) 1건. 가중치 자체는 파일시스템에 있고 여기에는 경로와 메타 정보만 보관';
COMMENT ON COLUMN ax.tb_ai_model_asset.version_tag     IS '버전 태그 (예: v-202611, v2). asset_key 와 묶어 유일해야 하는 값';
COMMENT ON COLUMN ax.tb_ai_model_asset.artifact_path   IS '서버 파일 경로 (예: /checkpoints/assistant/v-202611). 이 경로가 사라지면 롤백이 불가능해지므로 배포 전 실존을 확인';
COMMENT ON COLUMN ax.tb_ai_model_asset.checksum        IS '아티팩트 SHA-256. NAS 복제본과 대조해 손상·교체 여부를 확인하는 값';
COMMENT ON COLUMN ax.tb_ai_model_asset.base_revision   IS '베이스 모델 커밋 해시. 베이스가 바뀌면 평가 기준선을 다시 잡아야 함';
COMMENT ON COLUMN ax.tb_ai_model_asset.train_method_cd IS '학습 방식. 공통코드 그룹 = AI_TRAIN_METHOD (LLM_fine_tuning/sql/02 에서 등록). LoRA 와 QLoRA 결과를 같은 선에서 비교하면 안 됨';
COMMENT ON COLUMN ax.tb_ai_model_asset.release_id      IS 'ax.tb_ai_model_release.release_id 와의 느슨한 연결. 이 파일이 sql/02 보다 먼저 실행되므로 FK 를 걸지 않음';
COMMENT ON COLUMN ax.tb_ai_model_asset.is_onprem       IS 'false = 외부 API. 기밀 문서 처리 프로필에는 선택할 수 없음';

-- ── ax.tb_ai_serving_asset  (1)
COMMENT ON TABLE ax.tb_ai_serving_asset IS '서빙 프로필 × 모델 자산. 역할당 자산 1개이며, 프로필이 참조하는 자산은 삭제할 수 없음(RESTRICT)';

-- ── ax.tb_ai_serving_profile  (7)
COMMENT ON TABLE ax.tb_ai_serving_profile IS 'AI 서비스 서빙 프로필 = 관리자가 고르는 "버전". 모델 자산 조합 + 코퍼스 스냅샷 + 생성/검색 파라미터를 한 묶음으로 고정';
COMMENT ON COLUMN ax.tb_ai_serving_profile.service_cd         IS '대상 서비스. 공통코드 그룹 = AI_SERVICE (CHAT=AI 채팅, REPORT=보고서 생성, SEARCH=문서 검색). 서비스마다 ACTIVE 를 따로 관리';
COMMENT ON COLUMN ax.tb_ai_serving_profile.profile_cd         IS '프로필 계열 코드 (예: chat-default). 같은 계열의 개정이 version_no 로 누적';
COMMENT ON COLUMN ax.tb_ai_serving_profile.corpus_snapshot_id IS '이 버전이 바라보는 벡터 코퍼스 스냅샷. 어댑터가 같아도 이 값이 다르면 답이 달라짐';
COMMENT ON COLUMN ax.tb_ai_serving_profile.system_prompt      IS '이 버전의 시스템 프롬프트 전문. 프롬프트만 바뀌어도 별 버전으로 관리해야 비교가 가능';
COMMENT ON COLUMN ax.tb_ai_serving_profile.trgm_threshold     IS 'pg_trgm 단어 유사도 임계값. vec.fn_search_chunk 의 p_trgm_threshold 로 전달';
COMMENT ON COLUMN ax.tb_ai_serving_profile.must_pass_fail     IS '골든셋 must_pass 실패 건수. 0 이 아니면 제약이 배포를 차단';

-- ── ax.tb_ai_serving_route  (4)
COMMENT ON TABLE ax.tb_ai_serving_route IS '서빙 라우팅 — 전역/부서/계정 단위로 사용할 버전을 지정. 카나리 대상 지정과 A/B 비중 배분이 여기서 구성';
COMMENT ON COLUMN ax.tb_ai_serving_route.scope_cd   IS '적용 범위. 공통코드 그룹 = AI_ROUTE_SCOPE (GLOBAL=전체, DEPT=부서, USER=계정). 좁은 범위가 우선';
COMMENT ON COLUMN ax.tb_ai_serving_route.weight_pct IS '같은 범위에 여러 버전을 둘 때의 배분 비중. 사용자 ID 해시로 결정론적으로 배정';
COMMENT ON COLUMN ax.tb_ai_serving_route.valid_to   IS '적용 종료 시각. 카나리 기간을 미리 정해 두면 만료 후 자동으로 ACTIVE 로 복귀';

-- ── ax.tb_alm_alert  (5)
COMMENT ON TABLE ax.tb_alm_alert IS '발생 알림 — 발송 조건이 감지한 이상 건. 확인되지 않으면 승격 규칙에 따라 상위로 승격됨';
COMMENT ON COLUMN ax.tb_alm_alert.eqpt_cd     IS '대상 설비 코드 — mes.tb_md_eqpt 와 조인 (일일 생산현황 보고 등)';
COMMENT ON COLUMN ax.tb_alm_alert.dedup_key   IS '중복 억제 키 — 조건 + 대상 조합. 억제 구간 내 동일 키는 재발송하지 않음';
COMMENT ON COLUMN ax.tb_alm_alert.hit_cnt     IS '중복 억제 창 안에서 다시 걸린 횟수(최초 발생 포함 1). 억제됐다고 사실까지 지우지 않기 위한 값';
COMMENT ON COLUMN ax.tb_alm_alert.resolved_at IS '값이 정상으로 돌아온 시각. 확인 처리(ack)와는 다름 — 사람이 안 봐도 상황은 풀릴 수 있음';

-- ── ax.tb_alm_cond  (8)
COMMENT ON TABLE ax.tb_alm_cond IS '이상 알림 발송 조건 — 지표·비교식·임계값·지속조건·대상 범위를 정의. 수신 대상은 수신 그룹 이름으로만 지정';
COMMENT ON COLUMN ax.tb_alm_cond.msg_template      IS '메시지 템플릿. 치환자 {심각도}{조건명}{대상}{지표}{임계값}. 단가·수율 등 민감정보는 본문에 포함하지 않음';
COMMENT ON COLUMN ax.tb_alm_cond.scope_dim_cd      IS '평가 단위. 공통코드 그룹 = ALM_SCOPE_DIM. ''EQPT'' 면 설비마다 따로 판정·따로 억제됨. ''NONE'' 이면 대상 전체를 묶어 값 하나로 판정';
COMMENT ON COLUMN ax.tb_alm_cond.window_time       IS 'window_cd=''ONCE''(지정 시각 1회) 일 때의 시각. 그 밖의 시간대는 공통코드 ALM_WINDOW 의 attr1/attr2 를 사용';
COMMENT ON COLUMN ax.tb_alm_cond.eval_interval_sec IS '이 조건을 몇 초마다 평가할지. 엔진 틱(기본 60초)보다 짧게 잡아도 틱 주기가 하한';
COMMENT ON COLUMN ax.tb_alm_cond.ignore_window_flg IS '''Y'' 면 유효 시간대 밖에도 발송. 위험(CRIT) 조건을 야간에도 받아야 할 때 사용. 수신자 개인의 야간 미수신(night_recv)은 이 값과 무관하게 지켜짐';
COMMENT ON COLUMN ax.tb_alm_cond.auto_close_flg    IS '''Y'' 면 값이 정상으로 돌아올 때 tb_alm_alert.resolved_at 을 기록. 확인 처리(ack_state_cd)는 사람 몫 — 자동으로 CLOSED 로 바꾸지 않음';
COMMENT ON COLUMN ax.tb_alm_cond.last_eval_at      IS '마지막 평가 시각(화면 표시용). 대상별 정밀 상태는 ax.tb_alm_cond_state 참조';

-- ── ax.tb_alm_cond_group  (1)
COMMENT ON TABLE ax.tb_alm_cond_group IS '발송 조건 × 수신 그룹. 그룹을 참조하는 조건이 있으면 그룹 삭제를 차단 (RESTRICT)';

-- ── ax.tb_alm_cond_state  (6)
COMMENT ON TABLE ax.tb_alm_cond_state IS '조건 × 대상의 평가 상태. 「10분 연속」·「30분 중복 억제」를 계산할 수 있는 유일한 근거. 이 표가 없으면 엔진은 매번 처음부터 판정';
COMMENT ON COLUMN ax.tb_alm_cond_state.breach_since  IS '연속 위반이 시작된 시각. 중간에 한 번이라도 정상이면 NULL 로 되돌림 — 연속이 끊긴 것';
COMMENT ON COLUMN ax.tb_alm_cond_state.breach_cnt    IS '연속 위반으로 판정된 횟수. 이동평균·간헐 위반을 사람이 판단할 때 사용';
COMMENT ON COLUMN ax.tb_alm_cond_state.last_alert_at IS '마지막으로 알림을 낸 시각. 중복 억제 창(ALM_DEDUP.attr1 분)의 기준점';
COMMENT ON COLUMN ax.tb_alm_cond_state.suppress_cnt  IS '억제 창에 걸려 발송하지 않은 누적 횟수. 억제 설정이 과한지 보는 값';
COMMENT ON COLUMN ax.tb_alm_cond_state.next_eval_at  IS '다음 평가 예정 시각. 엔진은 이 값이 지난 행만 가져감';

-- ── ax.tb_alm_cond_target  (3)
COMMENT ON TABLE ax.tb_alm_cond_target IS '조건의 개별 대상 목록. tb_alm_cond.target_scope_cd=''PICK''(개별 설비 선택) 일 때만 사용 — 그 코드값이 있는데 담을 곳이 없음';
COMMENT ON COLUMN ax.tb_alm_cond_target.target_dim_cd IS '대상 종류. 공통코드 ALM_SCOPE_DIM 과 같은 값을 사용(EQPT·WC·ITEM·MOLD·PRODUCT)';
COMMENT ON COLUMN ax.tb_alm_cond_target.target_cd     IS '대상 코드. 설비코드·워크센터코드 등. 마스터가 지워져도 조건은 남으므로 FK 를 걸지 않음';

-- ── ax.tb_alm_eval_run  (3)
COMMENT ON TABLE ax.tb_alm_eval_run IS '알림 엔진 실행 이력. 아무 일도 없던 틱은 남기지 않음 — 1분 주기면 하루 1,440행이 쌓여 정작 봐야 할 실행이 묻힘. 알림·발송·실패가 하나라도 있을 때만 남기고, 조용한 구간은 시간당 1행 요약만 기록';
COMMENT ON COLUMN ax.tb_alm_eval_run.run_id   IS '실행 식별자. ALM-yyyyMMdd-HHmmss. 초까지 넣음 — 분 단위로는 같은 분의 두 실행이 한 행으로 겹침';
COMMENT ON COLUMN ax.tb_alm_eval_run.state_cd IS '실행 결과. 공통코드 ALM_RUN_STATE. 조건 하나의 오류가 전체를 죽이지 않으므로 PARTIAL 이 흔함';

-- ── ax.tb_alm_recip_group  (1)
COMMENT ON TABLE ax.tb_alm_recip_group IS '알림 수신 그룹 — 발송 조건이 이 그룹 이름만 참조. 멤버·연락처는 알림 수신자 관리에서만 처리';

-- ── ax.tb_alm_recip_group_member  (1)
COMMENT ON TABLE ax.tb_alm_recip_group_member IS '수신 그룹 × 수신자. 그룹은 멤버가 1명 이상이어야 하며(화면 검증) 멤버 제거는 이 행 삭제로 처리';

-- ── ax.tb_alm_recipient  (2)
COMMENT ON COLUMN ax.tb_alm_recipient.mobile_no     IS '휴대폰 번호 — 데이터 항목 worker(작업자 정보) 권한이 있는 계정에게만 표시';
COMMENT ON COLUMN ax.tb_alm_recipient.recv_state_cd IS '수신 상태. 공통코드 그룹 = ALM_RECV_STATE (RECV=수신, ABSENT=부재). 부재면 당번·대리 규칙에 따라 대리 수신자에게 발송';

-- ── ax.tb_alm_send_queue  (4)
COMMENT ON TABLE ax.tb_alm_send_queue IS '알림 발송 대기열. 발생(tb_alm_alert)과 발송을 떼어 SMTP 가 죽어도 알림은 남게 함. 발송 결과는 tb_alm_send_log 에 시도마다 1행으로 누적 (이 표를 덮어쓰지 않음)';
COMMENT ON COLUMN ax.tb_alm_send_queue.body        IS '렌더링이 끝난 본문. 수신자의 부서 데이터 권한에 따라 마스킹된 상태로 유입 — 사람마다 내용이 다를 수 있음';
COMMENT ON COLUMN ax.tb_alm_send_queue.next_try_at IS '다음 시도 시각. 실패하면 백오프(1m→5m→15m→30m→60m)만큼 뒤로 밀림';
COMMENT ON COLUMN ax.tb_alm_send_queue.locked_by   IS '집어간 워커 식별자(호스트+스레드). locked_at 이 5분을 넘으면 죽은 워커로 보고 PENDING 으로 회수';

-- ── ax.tb_aoi_defect_image  (4)
COMMENT ON COLUMN ax.tb_aoi_defect_image.wc_cd       IS '작업장 코드. 표시명은 공통코드 AOI_WC(S110=도금, S120=도장) → mes.tb_md_workcenter.wc_nm → 원값 순으로 조회. 원본에 빈 문자열·NULL_ 접두가 섞여 있음';
COMMENT ON COLUMN ax.tb_aoi_defect_image.nas_path    IS 'NAS 경로. 브라우저가 직접 읽지 않고 GET /files/aoi-images/{imageId} 프록시가 화이트리스트 루트 아래에서만 읽음';
COMMENT ON COLUMN ax.tb_aoi_defect_image.captured_at IS 'AOI 촬영 시각. 없으면 라벨의 ins_date(KST 벽시계, timestamp(3)) 를 사용';
COMMENT ON COLUMN ax.tb_aoi_defect_image.eqpt_cd     IS '설비 코드. DIMENSION 원본에만 있음';

-- ── ax.tb_dash_upload_doc  (2)
COMMENT ON COLUMN ax.tb_dash_upload_doc.latest_ver IS '최신 버전 번호. tb_dash_upload_ver 의 max(ver) 와 같아야 하며 버전 등록 트랜잭션에서 함께 갱신';
COMMENT ON COLUMN ax.tb_dash_upload_doc.del_flg    IS 'Y = 목록에서 숨김. 버전·파일은 지우지 않음';

-- ── ax.tb_dash_upload_ver  (2)
COMMENT ON COLUMN ax.tb_dash_upload_ver.storage_path IS '원본 파일 저장 경로. DB 에는 파일을 넣지 않음';
COMMENT ON COLUMN ax.tb_dash_upload_ver.sha256       IS '원본 SHA-256 (16진 64자). 같은 문서에 같은 해시가 올라오면 API 가 안내할 수 있음';

-- ── ax.tb_gls_term  (1)
COMMENT ON TABLE ax.tb_gls_term IS '공식 용어 — 보고서·리포트 출력 표기 기준. 통합관리자만 등록·수정';

-- ── ax.tb_gls_variant  (1)
COMMENT ON TABLE ax.tb_gls_variant IS '유사어 — 현장에서 실제로 쓰는 말·약칭·한글 표기. 자연어 질의와 보고서 생성 시 공식 용어로 정규화하는 데 사용';

-- ── ax.tb_log_audit  (1)
COMMENT ON COLUMN ax.tb_log_audit.menu_id IS '대상 화면. tb_sys_menu 를 논리 참조하나 화면 삭제 후에도 기록을 보존하므로 FK 를 걸지 않음';

-- ── ax.tb_met_metric_collect  (8)
COMMENT ON TABLE ax.tb_met_metric_collect IS '지표 실측치(ax.tb_met_metric_value)를 채우는 방법. 지금 그 표가 0행이라 알림 조건이 비교할 값이 없음 — 그 구멍을 메우는 정의';
COMMENT ON COLUMN ax.tb_met_metric_collect.collect_mode_cd IS '수집 방식. 공통코드 MET_COLLECT_MODE. 1단계는 BUILTIN 만 사용';
COMMENT ON COLUMN ax.tb_met_metric_collect.collector_cd    IS 'BUILTIN 일 때 Kotlin 구현체 키. 비우면 tb_met_metric_std.metric_cd 를 사용';
COMMENT ON COLUMN ax.tb_met_metric_collect.dim_cd          IS '값을 쪼개는 단위. 공통코드 ALM_SCOPE_DIM. ''EQPT'' 면 설비마다 값이 따로 누적';
COMMENT ON COLUMN ax.tb_met_metric_collect.lookback_min    IS '한 번 수집할 때 거슬러 보는 구간(분). 이관이 늦은 원본을 메우려고 주기보다 넉넉히 설정';
COMMENT ON COLUMN ax.tb_met_metric_collect.sql_text        IS '집계 SQL (collect_mode_cd=''SQL'' 전용). 2단계 기능이라 지금은 사용하지 않음 — 이 컬럼에 쓰기 권한을 주는 것은 DB 에서 임의 SQL 을 돌릴 권한을 주는 것과 같음. 읽기 전용 롤 분리·SELECT 단일문 검증·감사 로그가 갖춰진 뒤에 개방';
COMMENT ON COLUMN ax.tb_met_metric_collect.use_flg         IS '수집 스위치. 기본 ''N''(미수집) — 구현체가 붙고 값을 확인한 뒤 ''Y'' 로 전환';
COMMENT ON COLUMN ax.tb_met_metric_collect.last_value_at   IS '마지막으로 값이 쌓인 시각. interval_sec × 3 보다 오래되면 엔진이 그 지표를 판정하지 않음 — 멈춘 수집의 낡은 값으로 알림을 내면 이미 끝난 이상이 계속 나가거나 진짜 이상을 정상으로 오판';

-- ── ax.tb_met_metric_std  (3)
COMMENT ON TABLE ax.tb_met_metric_std IS '지표 기준 수치 — 정상/주의/위험 임계값. 이상 알림 발송 판정, 대시보드 목표선·색상, 보고서 신호등 색에 함께 사용됨';
COMMENT ON COLUMN ax.tb_met_metric_std.crit_val      IS '위험 임계. crit_val >= warn_val 이면 "값이 클수록 나쁨", 반대면 "값이 작을수록 나쁨" 으로 판정 방향을 결정';
COMMENT ON COLUMN ax.tb_met_metric_std.owner_dept_id IS '소관 부서 — 이 부서와 전산팀·통합관리자만 기준을 변경할 수 있음';

-- ── ax.tb_met_metric_value  (2)
COMMENT ON TABLE ax.tb_met_metric_value IS '지표 측정 데이터 — 지표별 측정 시점 값. MES 차원 키를 함께 보관해 설비·작업장·품목·LOT 단위로 조인 조회';
COMMENT ON COLUMN ax.tb_met_metric_value.judge_cd IS '판정. 공통코드 그룹 = MET_JUDGE (NORMAL=정상, WARN=주의, CRIT=위험). 측정 시점 기준값으로 판정해 저장';

-- ── ax.tb_prod_customer  (1)
COMMENT ON TABLE ax.tb_prod_customer IS '고객사 — 데이터 접근 항목 customer 의 blind 대상. 보고서 양식의 고객사별 공개 정책과도 연결됨';

-- ── ax.tb_prod_daily_decision  (3)
COMMENT ON TABLE ax.tb_prod_daily_decision IS '일일 생산현황 보고 아침회의 결과 — 제품별 일목표·판정·담당·기한. 문서를 저장하지 않으므로 (대상일, 제품) 이 키';
COMMENT ON COLUMN ax.tb_prod_daily_decision.product    IS '제품 코드(model_cd). 품목 매핑이 없으면 item_cd 가 그대로 유입';
COMMENT ON COLUMN ax.tb_prod_daily_decision.target_qty IS '작성자가 입력한 일목표. 정식 출처(제품·공정별 목표 마스터)가 생기면 그쪽 참조로 이관';

-- ── ax.tb_prod_day_target  (5)
COMMENT ON TABLE ax.tb_prod_day_target IS '제품·공정별 일목표 마스터 — 적용일부터 다음 적용일 전까지 유효';
COMMENT ON COLUMN ax.tb_prod_day_target.product    IS '제품 코드(model_cd). 품목 매핑이 없으면 item_cd 를 사용';
COMMENT ON COLUMN ax.tb_prod_day_target.wc_cd      IS '작업장(공정) 코드. 같은 제품도 공정마다 목표가 다름';
COMMENT ON COLUMN ax.tb_prod_day_target.apply_from IS '적용 시작일. 종료일은 두지 않고 다음 적용일 전까지 유효한 것으로 간주';
COMMENT ON COLUMN ax.tb_prod_day_target.target_qty IS '일목표 수량. 일일 보고에서 작성자가 넣은 값(tb_prod_daily_decision)이 있으면 그 값이 우선';

-- ── ax.tb_prod_family  (2)
COMMENT ON TABLE ax.tb_prod_family IS '제품군 — 순위 관리 단위. 순위를 바꾸면 소속 제품의 매출 순위가 한꺼번에 밀림';
COMMENT ON COLUMN ax.tb_prod_family.rank_no IS '제품군 순위 (1 = 주력). 순위 재배치 시 여러 행이 동시에 바뀌므로 UNIQUE 를 DEFERRABLE 로 선언';

-- ── ax.tb_prod_item_map  (1)
COMMENT ON TABLE ax.tb_prod_item_map IS '제품(모델) ↔ MES 품목 매핑. mes.tb_md_item (plant_cd, item_cd) 를 논리 참조하며 MES 실적을 모델 단위로 집계하는 기준이 됨';

-- ── ax.tb_prod_product  (1)
COMMENT ON TABLE ax.tb_prod_product IS '제품(모델) 마스터 — 대시보드의 주력 제품 Top N 이 이 순위를 따름';

-- ── ax.tb_qc_lrr_notice  (1)
COMMENT ON COLUMN ax.tb_qc_lrr_notice.ship_year IS '귀속 출하 연도 — 통보일이 아닌 출하 시점 기준으로 집계';

-- ── ax.tb_rpt_download_blind  (1)
COMMENT ON TABLE ax.tb_rpt_download_blind IS '다운로드 파일에서 blind 처리된 데이터 항목 내역 — 감사 시 "무엇이 제외되었는지" 를 증빙';

-- ── ax.tb_rpt_download_log  (1)
COMMENT ON TABLE ax.tb_rpt_download_log IS '보고서 다운로드 이력 — 엑셀·CSV·인쇄(PDF) 모두 기록. 문서를 저장하지 않으므로 이 이력이 "누가 · 언제 · 어떤 조건으로 · 무엇을" 내려받았는지의 유일한 기록. 보존 3년 · 보안 감사 로그와 함께 제출 대상 (append-only)';

-- ── ax.tb_rpt_form  (1)
COMMENT ON TABLE ax.tb_rpt_form IS '보고서 양식 — 8D 리포트, 불량 폐기 보고서 등. 양식 구조가 바뀌면 파서 버전을 등록';

-- ── ax.tb_rpt_form_field  (2)
COMMENT ON TABLE ax.tb_rpt_form_field IS '보고서 양식 항목 — 화면의 "항목 수" 는 이 행 수로 산출';
COMMENT ON COLUMN ax.tb_rpt_form_field.blind_field_key IS '이 항목이 데이터 접근 권한 대상인 경우의 데이터 항목. 권한 없는 계정에게는 값 대신 "비공개" 로 출력됨';

-- ── ax.tb_rpt_report  (2)
COMMENT ON TABLE ax.tb_rpt_report IS '보고서 마스터 — 보고서 모듈(reports/*.js)로 등록되는 화면. 다운로드 이력의 대상이 됨';
COMMENT ON COLUMN ax.tb_rpt_report.menu_id IS '연결된 화면 ID. 보고서도 권한 관리 대상이므로 tb_sys_menu 에 등록됨';

-- ── ax.tb_rpt_unmask_req  (1)
COMMENT ON TABLE ax.tb_rpt_unmask_req IS '마스킹 해제 요청 — 화면(menu_id) 과 데이터 항목(field_keys) 단위로 요청. 감사 로그와 연계';

-- ── ax.tb_rpt_write_state  (1)
COMMENT ON COLUMN ax.tb_rpt_write_state.menu_id IS '보고서 화면 ID = ax.tb_sys_menu.menu_id. 보고서 화면인지는 API 가 검증';

-- ── ax.tb_sync_job  (5)
COMMENT ON COLUMN ax.tb_sync_job.started_at     IS '실제 실행 시작 시각. PENDING(예약 대기) 상태에서는 NULL 이고, 엔진이 작업을 선점하는 순간 기록됨';
COMMENT ON COLUMN ax.tb_sync_job.checksum_match IS '원본·대상 건수 및 체크섬 대조 결과. false 면 실패 처리';
COMMENT ON COLUMN ax.tb_sync_job.retry_cnt      IS '자동 재시도 횟수. 3회 초과 시 실패 확정하고 전산팀에 이상 알림을 발송';
COMMENT ON COLUMN ax.tb_sync_job.scheduled_at   IS '실행 예약 시각. 화면의 수동 이관·재실행이 설정하며, 이관 엔진이 이 시각이 지난 PENDING 작업을 가져감. 정기 배치가 만든 작업은 NULL';
COMMENT ON COLUMN ax.tb_sync_job.run_id         IS '소속 실행 (ax.tb_sync_run). 정기 배치 한 번에 여러 테이블 작업이 딸림';

-- ── ax.tb_sync_map  (2)
COMMENT ON TABLE ax.tb_sync_map IS '이관 매핑 — MSSQL 원본 테이블과 PostgreSQL 대상 테이블 대응. 화면의 "연동 대상 · 매핑" 카드가 이 표를 읽음';
COMMENT ON COLUMN ax.tb_sync_map.key_columns IS 'UPSERT 키 컬럼 목록 (쉼표 구분). 원본에 PK 가 없는 테이블은 자연키 조합을 명시';

-- ── ax.tb_sync_run  (2)
COMMENT ON TABLE ax.tb_sync_run IS '이관 실행 이력 — 엔진을 한 번 돌릴 때마다 1행. 프리플라이트 실패·무작업·건너뜀까지 모두 남김. 테이블별 상세는 ax.tb_sync_job (run_id 로 연결)';
COMMENT ON COLUMN ax.tb_sync_run.run_id IS '실행 식별자 (RUN-yyyyMMdd-HHmm[-QUEUE|-RETRY]). tb_sync_job.remark 의 run= 값과 같음';

-- ── ax.tb_sync_schema_drift  (4)
COMMENT ON TABLE ax.tb_sync_schema_drift IS '이관 스키마 드리프트 — ax.tb_sync_map 의 이관 정의와 원본·대상 DB 의 실제 테이블 목록이 어긋난 사실. 이관 엔진이 배치마다 기록하고 데이터 연동 이력 화면이 조회';
COMMENT ON COLUMN ax.tb_sync_schema_drift.table_nm   IS '테이블 명. 원본은 대문자, 대상은 소문자 원형 그대로 기록';
COMMENT ON COLUMN ax.tb_sync_schema_drift.detect_cnt IS '누적 발견 횟수. 값이 크면 오래 방치된 드리프트';
COMMENT ON COLUMN ax.tb_sync_schema_drift.resolved   IS '해소 여부. 다음 배치에서 드리프트가 사라지면 엔진이 자동으로 true 로 닫고, 화면에서 수동으로 닫을 수도 있음';

-- ── ax.tb_sys_code_ref  (1)
COMMENT ON TABLE ax.tb_sys_code_ref IS '코드 컬럼 ↔ 코드 그룹 매핑 메타데이터. ax.fn_check_code_ref() 가 이 정의로 코드 정합성을 검사';

-- ── ax.tb_sys_data_field  (4)
COMMENT ON TABLE ax.tb_sys_data_field IS '데이터 접근 항목 — 메뉴 접근이 허용된 화면에서도 이 항목 단위로 값을 가림. 2026-09-16 부터 WEB 화면에서 운영 중에 추가 가능';
COMMENT ON COLUMN ax.tb_sys_data_field.use_flg     IS '항목 사용 여부. ''N'' 이면 화면 목록에서 빠짐. 마스킹이 실제로 걸리는 조건은 use_flg=''Y'' AND apply_flg=''Y''';
COMMENT ON COLUMN ax.tb_sys_data_field.category_cd IS '항목 분류. 공통코드 그룹 = DATA_FIELD_CATEGORY. 화면에서 묶어 보여 주기 위한 것이라 NULL 이어도 판정에 영향이 없음';
COMMENT ON COLUMN ax.tb_sys_data_field.apply_flg   IS '적용 스위치. ''Y'' 여야 마스킹이 적용됨. 등록은 ''N''(미적용)으로 해 두고 부서 권한을 채운 뒤 ''Y'' 로 전환. 반영 시점은 재로그인';

-- ── ax.tb_sys_data_field_attr  (3)
COMMENT ON TABLE ax.tb_sys_data_field_attr IS '데이터 접근 항목 ↔ API 응답 필드명. WEB 이 "필드명 → 항목" 맵을 만들어 마스킹 대상을 판별';
COMMENT ON COLUMN ax.tb_sys_data_field_attr.attr_name IS 'API 응답 JSON 필드명. 전역 UNIQUE — 한 필드명은 한 항목에만 붙음. JSON 키라 대소문자를 구분';
COMMENT ON COLUMN ax.tb_sys_data_field_attr.remark    IS '어느 화면·API 의 값인지 메모. 판정에는 쓰지 않음';

-- ── ax.tb_sys_dept  (4)
COMMENT ON TABLE ax.tb_sys_dept IS '부서 — 메뉴/데이터 접근 권한을 부여하는 단위. 계정은 소속 부서의 권한을 상속';
COMMENT ON COLUMN ax.tb_sys_dept.dept_id        IS '부서 대리키. 부서명은 변경 가능하므로 권한 테이블은 이 키로 참조';
COMMENT ON COLUMN ax.tb_sys_dept.dept_abbr      IS '부서 약칭 — 화면 배지에 쓰는 2자 표기 (QA, PC, MF, IT, EX, MA)';
COMMENT ON COLUMN ax.tb_sys_dept.is_super_admin IS 'true = 통합관리자. 전 화면·전 데이터 항목 접근으로 취급하며 개별 권한 행을 만들지 않음';

-- ── ax.tb_sys_dept_data_perm  (1)
COMMENT ON TABLE ax.tb_sys_dept_data_perm IS '부서 × 데이터 항목 열람 권한 — 허용되지 않은 항목은 화면·보고서·인쇄물·CSV 모두에서 값 자체를 제외';

-- ── ax.tb_sys_dept_menu_perm  (2)
COMMENT ON TABLE ax.tb_sys_dept_menu_perm IS '부서 × 화면 접근 권한 — 계정 권한의 기본값. 통합관리자 부서(is_super_admin)는 행 없이 전체 허용으로 판정. 여기에 행이 없어도 ax.tb_sys_user_menu_grant 로 계정에 개별 허용될 수 있음(차단은 두 표 모두 행이 없을 때)';
COMMENT ON COLUMN ax.tb_sys_dept_menu_perm.can_write IS '입력·수정 권한. false 면 조회만 가능하며, 권한 변경 이력의 "입력 권한 부여" 가 이 값을 켠 기록';

-- ── ax.tb_sys_email_verify  (4)
COMMENT ON COLUMN ax.tb_sys_email_verify.code_hash      IS '인증 코드 해시. 평문은 저장하지 않음';
COMMENT ON COLUMN ax.tb_sys_email_verify.verify_token   IS '검증 성공 시 발급하는 1회용 토큰. 사용하면 consumed_at 이 채워짐';
COMMENT ON COLUMN ax.tb_sys_email_verify.target_user_id IS '비밀번호 찾기 대상 계정. 토큰을 다른 계정에 재사용하지 못하게 묶음';
COMMENT ON COLUMN ax.tb_sys_email_verify.attempt_cnt    IS '코드 검증 시도 횟수. 상한 초과 시 해당 요청을 폐기';

-- ── ax.tb_sys_login_hist  (1)
COMMENT ON COLUMN ax.tb_sys_login_hist.user_id IS '시도한 아이디. 삭제된 계정의 기록도 보존하므로 FK 를 걸지 않음';

-- ── ax.tb_sys_menu  (3)
COMMENT ON COLUMN ax.tb_sys_menu.menu_id        IS '화면 ID — 웹 주소의 해시 경로 값과 같다 (dash-ai, sys-account, alert-cond …)';
COMMENT ON COLUMN ax.tb_sys_menu.parent_menu_id IS '하위 화면의 진입 상위 화면. 상위만 열고 하위를 닫으면 버튼 진입이 차단되므로 함께 부여하도록 안내';
COMMENT ON COLUMN ax.tb_sys_menu.is_sub_page    IS 'true = 사이드바에 노출되지 않고 버튼·링크로만 진입하는 하위 화면(daily-history 등) 또는 경로 없는 동작 권한(dash-ai-upload). 권한 매트릭스에는 포함됨';

-- ── ax.tb_sys_perm_log  (2)
COMMENT ON TABLE ax.tb_sys_perm_log IS '계정 · 부서 · 권한 변경 이력 (append-only). 화면 하단 "계정·권한 변경 이력" 카드가 이 표를 읽음';
COMMENT ON COLUMN ax.tb_sys_perm_log.target_nm IS '변경 대상 표시명. 대상이 삭제되어도 기록이 남도록 이름을 함께 저장';

-- ── ax.tb_sys_plant  (1)
COMMENT ON TABLE ax.tb_sys_plant IS '사업부 마스터 — MES 의 PLANT_CD(PL01=모바일, PL03=전장) 마스터. MESDB_M 에는 이 마스터가 없어 AX 에서 관리';

-- ── ax.tb_sys_user  (2)
COMMENT ON TABLE ax.tb_sys_user IS '가입 계정 — 아이디(사번) · 이름 · 부서 · 직급 · 상태만 관리. 화면/데이터 접근 권한은 소속 부서 설정을 따르며, 화면 권한만 계정별 추가 허용(ax.tb_sys_user_menu_grant)을 더할 수 있음';
COMMENT ON COLUMN ax.tb_sys_user.is_switch_target IS '우측 상단 계정 전환 목록에 이 계정을 띄울지 여부. 시연용 플래그';

-- ── ax.tb_sys_user_favorite  (1)
COMMENT ON COLUMN ax.tb_sys_user_favorite.sort_seq IS '사이드바 표시 순서(0부터). PUT 이 목록 전체를 다시 사용';

-- ── ax.tb_sys_user_menu_grant  (2)
COMMENT ON TABLE ax.tb_sys_user_menu_grant IS '계정 × 화면 추가 허용 — 부서 권한에 더해 이 계정에만 열어 주는 화면. 행의 존재 = 열람 허용이며, 행으로 차단하는 용법은 없음(추가 허용 전용)';
COMMENT ON COLUMN ax.tb_sys_user_menu_grant.grant_reason IS '부여 사유. 부서 기준을 벗어난 예외이므로 남겨 두면 감사에서 되짚기 쉬움. 없으면 NULL';

-- ── ax.vw_serving_asset_health  (1)
COMMENT ON VIEW ax.vw_serving_asset_health IS '서비스 중인 버전과 롤백 대상 직전 버전이 참조하는 모델 파일 목록. 배포 스크립트가 이 경로의 실존을 확인해야 함 — DB 만으로는 파일 유무를 알 수 없음';

-- ── ax.vw_serving_profile_detail  (1)
COMMENT ON VIEW ax.vw_serving_profile_detail IS 'AI 서비스 버전 상세 — 역할별 모델 자산과 코퍼스 스냅샷을 한 행으로 전개. 관리자 화면의 버전 목록·상세가 이 뷰를 읽음';

-- ── ax.vw_sys_user_menu_perm  (1)
COMMENT ON VIEW ax.vw_sys_user_menu_perm IS '계정 × 유효 화면 접근 권한 = 부서 권한(can_read) ∪ 계정 추가 허용. can_write 는 두 출처의 OR. 사용 중인 화면(use_flg=''Y'')만. 통합관리자 전 화면 허용은 포함하지 않음(API 가 별도 판정)';

-- ── ax.vw_user_data_perm  (1)
COMMENT ON VIEW ax.vw_user_data_perm IS '계정별 데이터 항목 열람 권한 — is_allowed = false 인 항목은 화면·보고서·인쇄물·CSV 모두에서 blind 처리';

-- ══════════════════════════════════════════════════════════════════════════════════
--  mes 스키마
-- ══════════════════════════════════════════════════════════════════════════════════

-- ── mes.tb_pop_label_hist  (1)
COMMENT ON TABLE mes.tb_pop_label_hist IS 'LOT 라벨 이력 — 작업 완료 시 생성되는 LOT 단위 실적. (PLANT_CD, WC_CD, LOT_NO, SERIAL_NO) 가 LOT 식별 키이며 재고·불량 이력의 기준이 됨';

-- ── mes.tb_pop_stock_hist  (1)
COMMENT ON TABLE mes.tb_pop_stock_hist IS 'LOT 재고 이동 이력 — 이력 발생 시점의 상태·위치·수량. 원본 MSSQL 에 PK 가 선언되어 있지 않아 그대로 복제';

-- ══════════════════════════════════════════════════════════════════════════════════
--  vec 스키마
-- ══════════════════════════════════════════════════════════════════════════════════

-- ── vec.tb_code_ref  (1)
COMMENT ON TABLE vec.tb_code_ref IS 'vec 스키마 코드 컬럼 ↔ 코드 그룹 매핑. vec.fn_check_code_ref() 가 이 정의로 검사';

-- ── vec.tb_doc  (7)
COMMENT ON TABLE vec.tb_doc IS '문서 마스터 — 벡터화 대상 원본 문서 1건. MES 조인 키를 직접 보유해 문서 검색과 실적 조회를 함께 수행할 수 있음';
COMMENT ON COLUMN vec.tb_doc.doc_uid         IS '외부 노출용 UUID. 응답 인용(citation) 링크에 순번 대신 이 값을 사용';
COMMENT ON COLUMN vec.tb_doc.doc_date        IS '문서 기준일 (보고서 작성 대상일). 기간 필터의 기준이며 파티셔닝 후보 키';
COMMENT ON COLUMN vec.tb_doc.form_id         IS '보고서 양식 (ax.tb_rpt_form). 양식이 있으면 섹션 기반 청킹의 기준으로 사용';
COMMENT ON COLUMN vec.tb_doc.confidential_cd IS '기밀 등급. 공통코드 그룹 = VEC_CONFIDENTIAL (PUBLIC, INTERNAL, CONFIDENTIAL, CUSTOMER=고객사 제공물). CONFIDENTIAL 이상은 외부 임베딩 API 전송을 금지';
COMMENT ON COLUMN vec.tb_doc.retention_until IS '보존 만료일. 경과 문서는 청크·임베딩까지 함께 삭제';
COMMENT ON COLUMN vec.tb_doc.cur_ver         IS '현재 유효 버전 번호. 검색은 이 버전의 청크만 대상으로 함';

-- ── vec.tb_doc_chunk  (5)
COMMENT ON TABLE vec.tb_doc_chunk IS '문서 청크 + 임베딩 — 검색의 최소 단위. 응답 근거(citation)는 이 행을 가리킴';
COMMENT ON COLUMN vec.tb_doc_chunk.section_path IS '문서 내 위치 경로 (예: 8D > D4 근본원인 > 4-2 5Why). 양식 문서는 이 경로가 검색 품질에 크게 기여';
COMMENT ON COLUMN vec.tb_doc_chunk.tsv          IS '전문검색 벡터. 한국어 형태소 분석기(pgroonga/pg_bigm)가 없어 simple 구성이며, 한국어 부분일치는 pg_trgm 인덱스가 담당';
COMMENT ON COLUMN vec.tb_doc_chunk.del_flg      IS 'tb_doc.del_flg 복제값. 삭제 문서 청크를 색인에서 제외';
COMMENT ON COLUMN vec.tb_doc_chunk.is_current   IS 'tb_doc.cur_ver 와 일치하는 최신 버전 청크 여부. HNSW 부분 인덱스 조건으로 사용해 구버전을 색인에서 제외';

-- ── vec.tb_doc_chunk_embed_ext  (2)
COMMENT ON TABLE vec.tb_doc_chunk_embed_ext IS '대체 모델 임베딩 — 차원별 컬럼을 두어 한 테이블에서 여러 모델을 병행 평가. 운영 전환 시 tb_doc_chunk.embedding 을 재선언하고 이관';
COMMENT ON COLUMN vec.tb_doc_chunk_embed_ext.embedding_3072 IS 'OpenAI text-embedding-3-large 급 3072차원 (halfvec). vector(3072) 는 HNSW 색인 한계 2000 을 넘어 색인할 수 없음';

-- ── vec.tb_doc_entity  (1)
COMMENT ON COLUMN vec.tb_doc_entity.surface_form IS '문서에 실제로 나타난 표현. 용어 사전 유사어 등록 후보를 발굴하는 데 사용';

-- ── vec.tb_doc_version  (2)
COMMENT ON TABLE vec.tb_doc_version IS '문서 버전 — 텍스트 추출·청킹·임베딩 단위. 이전 버전 청크를 남겨 두면 무중단 재임베딩이 가능';
COMMENT ON COLUMN vec.tb_doc_version.ocr_confidence IS 'OCR 평균 신뢰도. 낮은 문서는 검색 결과 신뢰도 표기에 반영';

-- ── vec.tb_embed_model  (3)
COMMENT ON COLUMN vec.tb_embed_model.distance_cd   IS '거리 함수. 공통코드 그룹 = VEC_DISTANCE (COSINE, L2, IP=내적). 인덱스 연산자 클래스와 일치시켜야 함';
COMMENT ON COLUMN vec.tb_embed_model.is_normalized IS 'true = 모델이 L2 정규화된 벡터를 반환. 정규화 벡터면 COSINE 과 IP 가 동일 순위를 산출';
COMMENT ON COLUMN vec.tb_embed_model.is_onprem     IS 'true = 사내 호스팅. 단가·수율·거래처 등 민감정보가 포함된 문서는 외부 API 전송을 금지하므로 이 값이 정책 판단 기준이 됨';

-- ── vec.tb_ingest_error  (1)
COMMENT ON TABLE vec.tb_ingest_error IS '수집·임베딩 실패 상세. 실패 문서만 재처리할 수 있도록 단계와 원본 정보를 남김';

-- ── vec.tb_ingest_job  (1)
COMMENT ON TABLE vec.tb_ingest_job IS '문서 수집·임베딩 배치 이력. 상태·실행주체 코드는 ax 의 SYNC_STATE / SYNC_TRIGGER 그룹을 재사용';

-- ── vec.tb_query_hit  (6)
COMMENT ON TABLE vec.tb_query_hit IS '질의별 검색 결과 1건 — 어느 경로에서 몇 위로 걸렸고, 리랭킹 후 몇 번째가 되었으며, 인용·클릭되었는지. chunk_id/doc_id 에 FK 를 걸지 않아 문서가 삭제·재수집되어도 이력이 보존됨';
COMMENT ON COLUMN vec.tb_query_hit.ts_rank      IS 'tsvector 전문검색 경로 순위. 품번·LOT·설비코드 등 완전일치가 여기서 잡힘';
COMMENT ON COLUMN vec.tb_query_hit.trgm_rank    IS 'pg_trgm 부분일치 경로 순위. 한국어 조사 변화·오타가 여기서 잡힘';
COMMENT ON COLUMN vec.tb_query_hit.rerank_score IS '리랭커(cross-encoder) 점수. DB 가 아니라 애플리케이션이 계산해 채움';
COMMENT ON COLUMN vec.tb_query_hit.is_cited     IS 'true = 최종 응답에 실제로 인용된 청크. 근거 소명과 검색 품질 평가의 기준이며 학습 데이터(T5) 정답 근거로도 쓰임';
COMMENT ON COLUMN vec.tb_query_hit.is_clicked   IS 'true = 사용자가 인용을 눌러 원문을 열람. 관련성의 가장 강한 신호';

-- ── vec.tb_query_log  (6)
COMMENT ON TABLE vec.tb_query_log IS 'RAG 검색 질의 이력 — 질의 임베딩까지 보관해 유사 질의 캐싱, 의도 클러스터링, 재질의 원인 분석에 사용';
COMMENT ON COLUMN vec.tb_query_log.normalized_text IS '용어 사전으로 정규화한 질의문. 실제 임베딩 대상은 이 값';
COMMENT ON COLUMN vec.tb_query_log.pool_cnt        IS '세 경로에서 모인 후보 청크 수(중복 제거 후) = 리랭커 입력 크기. hit_cnt 와의 차이가 리랭킹이 걸러낸 양';
COMMENT ON COLUMN vec.tb_query_log.cited_cnt       IS '실제로 응답에 인용된 청크 수. hit_cnt 와의 비율(인용률)이 1차 검색 정밀도 지표';
COMMENT ON COLUMN vec.tb_query_log.blocked_doc_cnt IS '권한 때문에 제외된 문서 수. 0 이 아니면 "권한 밖 자료가 있습니다" 안내 근거가 됨';
COMMENT ON COLUMN vec.tb_query_log.rerank_ms       IS '리랭킹 소요 시간(ms). search_ms 와 나눠 기록해야 병목을 구분할 수 있음';

-- ── vec.tb_term_embedding  (1)
COMMENT ON COLUMN vec.tb_term_embedding.embed_source IS '임베딩에 실제로 넣은 문자열 (예: "Stiffener — 스티프너 / FPCB 보강판"). 정의문까지 포함하면 정규화 정확도가 상승';

-- ── vec.vw_doc_lot_fact  (1)
COMMENT ON VIEW vec.vw_doc_lot_fact IS '문서 ↔ LOT 실적 뷰 — 문서에 지정된 LOT 키 또는 추출 엔터티로 MES 실적·현재 재고를 연결. "이 LOT 의 8D 보고서와 실적" 질의의 기준 뷰';

-- ── vec.vw_query_trace  (1)
COMMENT ON VIEW vec.vw_query_trace IS '질의 근거 추적 뷰 — "이 답변은 어떤 문서 몇 쪽을 근거로 했는가" 를 소명. ax.tb_log_audit 과 함께 보안 감사 제출 대상';

-- ── vec.vw_search_path_contrib  (1)
COMMENT ON VIEW vec.vw_search_path_contrib IS '검색 경로별 기여도. *_only 열이 그 경로의 존재 가치 — trgm_only 가 0 에 가까우면 LC_CTYPE 가 C 로 잘못 잡혔는지부터 확인';

-- ── vec.vw_term_candidate  (1)
COMMENT ON VIEW vec.vw_term_candidate IS '유사어 등록 후보 — 문서에 2회 이상 등장했으나 용어 사전에 없는 표현. 용어 사전 관리 화면에서 일괄 등록 대상으로 제시';

COMMIT;

-- ── 결과 확인 ───────────────────────────────────────────────────────────────────────
\echo ''
\echo '-- 남은 화면번호·「-다」 종결 (둘 다 0 이어야 정상) --'
SELECT count(*) FILTER (WHERE d.description ~ '(SY|DB|PR|QC|RP|CM|AL)-[0-9]+') AS "화면번호",
       count(*) FILTER (WHERE d.description ~ '[가-힣]다(\.|$)')                AS "-다 종결"
  FROM pg_description d
  JOIN pg_class c ON c.oid = d.objoid
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname IN ('ax','mes','vec') AND c.relkind IN ('r','v');
\echo ''
