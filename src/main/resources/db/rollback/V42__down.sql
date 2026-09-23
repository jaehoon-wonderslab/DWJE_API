-- =====================================================================================
--  V42 되돌리기 — 메뉴 고정 · 미사용 표 정리 복원 (2026-09-23)
--
--  V42 로 바꾼 것을 되살린다.
--   · 표·뷰·함수·트리거 구조 : 삭제 직전 로컬 DB(dwje-pg)에서 pg_dump -s 로 그대로 떠 온 것이다.
--   · 메뉴 그룹·메뉴·부서 권한·보고서 정의·공통코드 : 삭제 직전 로컬 값이다.
--     운영은 부서 권한 행이 다를 수 있다 — 정확히 되돌리려면 V42 적용 전에 뜬 v42_backup.sql 을 쓴다.
--   · 업무 데이터(즐겨찾기·작성 상태·AOI 사진·해제 요청) : 구조만 복원된다. 행은 v42_backup.sql 에서 되돌린다.
--
--  [주의] 이 파일만으로는 기능이 돌아오지 않는다. 같은 날 API 에서 지운 엔드포인트 30건
--         (AI 서비스 버전·불량 태그·즐겨찾기·보고서 작성 상태·AOI 목록/사진)도 함께 되돌려야 한다.
--
--  ┌─ 실행 방법 ──────────────────────────────────────────────────────────────────────┐
--  │   psql -h localhost -U dwje_local -d dwjedb -f rollback/V42__down.sql            │
--  │   · 이 파일이 BEGIN/COMMIT 을 직접 들고 있으므로 --single-transaction 은 붙이지 않는다.
--  │   · 하나라도 실패하면 전체 롤백된다(부분 복원 없음). 두 번 실행해도 안전하다.     │
--  └──────────────────────────────────────────────────────────────────────────────────┘
-- =====================================================================================

\set ON_ERROR_STOP on
\timing on

\echo ''
\echo '==================== V42 되돌리기 ===================='

-- 구조가 이미 있으면(두 번째 실행) 구조 복원은 건너뛴다
SELECT to_regclass('ax.tb_ai_model_asset') IS NULL AS v42_need_ddl \gset

BEGIN;

\if :v42_need_ddl
-- ── 1. 표·뷰 구조 복원 (pg_dump -s) ─────────────────────────────────────────────────
--
-- Name: tb_ai_chat_term; Type: TABLE; Schema: ax; Owner: -
--

CREATE TABLE ax.tb_ai_chat_term (
    chat_id bigint NOT NULL,
    term_id integer NOT NULL,
    variant_id integer
);

--
-- Name: TABLE tb_ai_chat_term; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON TABLE ax.tb_ai_chat_term IS '질의에서 정규화된 용어 — 어떤 유사어가 어떤 공식 용어로 치환되었는지 기록해 사전 품질을 개선';

--
-- Name: COLUMN tb_ai_chat_term.chat_id; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_chat_term.chat_id IS '대상 질의 (ax.tb_ai_chat_log)';

--
-- Name: COLUMN tb_ai_chat_term.term_id; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_chat_term.term_id IS '치환된 공식 용어 (ax.tb_gls_term)';

--
-- Name: COLUMN tb_ai_chat_term.variant_id; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_chat_term.variant_id IS '질의에 쓰인 유사어 (ax.tb_gls_variant). NULL 이면 공식 용어를 그대로 사용';

--
-- Name: tb_ai_corpus_snapshot; Type: TABLE; Schema: ax; Owner: -
--

CREATE TABLE ax.tb_ai_corpus_snapshot (
    snapshot_id integer NOT NULL,
    snapshot_cd character varying(40) NOT NULL,
    snapshot_nm character varying(200) NOT NULL,
    embed_asset_id integer,
    dim integer NOT NULL,
    doc_cnt integer DEFAULT 0 NOT NULL,
    chunk_cnt bigint DEFAULT 0 NOT NULL,
    token_cnt bigint DEFAULT 0 NOT NULL,
    index_method_cd character varying(20) DEFAULT 'HNSW'::character varying NOT NULL,
    index_params jsonb,
    source_desc character varying(500),
    ingest_job_id character varying(24),
    built_started timestamp with time zone,
    built_ended timestamp with time zone,
    state_cd character varying(30) DEFAULT 'BUILDING'::character varying NOT NULL,
    remark character varying(1000),
    ins_date timestamp with time zone DEFAULT now() NOT NULL,
    ins_user common.d_user_id,
    upd_date timestamp with time zone DEFAULT now() NOT NULL,
    upd_user common.d_user_id
);

--
-- Name: TABLE tb_ai_corpus_snapshot; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON TABLE ax.tb_ai_corpus_snapshot IS '벡터 코퍼스 스냅샷 — 벡터화 결과의 버전. 어떤 임베딩 모델로 어느 문서 범위를 언제 색인했는지를 한 행으로 고정';

--
-- Name: COLUMN tb_ai_corpus_snapshot.snapshot_id; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_corpus_snapshot.snapshot_id IS '코퍼스 스냅샷 식별자 (PK)';

--
-- Name: COLUMN tb_ai_corpus_snapshot.snapshot_cd; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_corpus_snapshot.snapshot_cd IS '스냅샷 코드 (예: corpus-202611)';

--
-- Name: COLUMN tb_ai_corpus_snapshot.snapshot_nm; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_corpus_snapshot.snapshot_nm IS '스냅샷 이름 (예: 2026-09 문서 코퍼스)';

--
-- Name: COLUMN tb_ai_corpus_snapshot.embed_asset_id; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_corpus_snapshot.embed_asset_id IS '색인에 쓴 임베딩 자산 (ax.tb_ai_model_asset). 차원이 dim 과 같아야 함';

--
-- Name: COLUMN tb_ai_corpus_snapshot.dim; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_corpus_snapshot.dim IS '임베딩 차원. 스냅샷 간 차원이 다르면 같은 인덱스를 공유할 수 없음';

--
-- Name: COLUMN tb_ai_corpus_snapshot.doc_cnt; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_corpus_snapshot.doc_cnt IS '색인된 문서 수 (vec.tb_doc 기준)';

--
-- Name: COLUMN tb_ai_corpus_snapshot.chunk_cnt; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_corpus_snapshot.chunk_cnt IS '색인된 청크 수 (vec.tb_doc_chunk 기준)';

--
-- Name: COLUMN tb_ai_corpus_snapshot.token_cnt; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_corpus_snapshot.token_cnt IS '색인에 사용한 총 토큰 수. 외부 API 를 쓰면 비용 추적 근거가 됨';

--
-- Name: COLUMN tb_ai_corpus_snapshot.index_method_cd; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_corpus_snapshot.index_method_cd IS '색인 방식. 공통코드 그룹 = AI_INDEX_METHOD (HNSW, IVFFLAT, NONE=순차 스캔)';

--
-- Name: COLUMN tb_ai_corpus_snapshot.index_params; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_corpus_snapshot.index_params IS '색인 파라미터 (예: {"m":16,"ef_construction":64}). 재현과 비교의 근거';

--
-- Name: COLUMN tb_ai_corpus_snapshot.source_desc; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_corpus_snapshot.source_desc IS '대상 문서 범위 서술 (예: NAS 제조AI 전체 + 2026-11-30 까지 등록분)';

--
-- Name: COLUMN tb_ai_corpus_snapshot.ingest_job_id; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_corpus_snapshot.ingest_job_id IS '이 스냅샷을 만든 수집 배치 (vec.tb_ingest_job.job_id)';

--
-- Name: COLUMN tb_ai_corpus_snapshot.built_started; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_corpus_snapshot.built_started IS '색인 시작 시각';

--
-- Name: COLUMN tb_ai_corpus_snapshot.built_ended; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_corpus_snapshot.built_ended IS '색인 완료 시각. state_cd = READY 로 올릴 때 채움';

--
-- Name: COLUMN tb_ai_corpus_snapshot.state_cd; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_corpus_snapshot.state_cd IS '상태. 공통코드 그룹 = AI_CORPUS_STATE (BUILDING=구축 중, READY=사용 가능, FAILED=실패, RETIRED=폐기). READY 가 아니면 프로필을 활성화할 수 없음';

--
-- Name: COLUMN tb_ai_corpus_snapshot.remark; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_corpus_snapshot.remark IS '비고';

--
-- Name: COLUMN tb_ai_corpus_snapshot.ins_date; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_corpus_snapshot.ins_date IS '등록일시';

--
-- Name: COLUMN tb_ai_corpus_snapshot.ins_user; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_corpus_snapshot.ins_user IS '등록자 (사번)';

--
-- Name: COLUMN tb_ai_corpus_snapshot.upd_date; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_corpus_snapshot.upd_date IS '최종 수정일시';

--
-- Name: COLUMN tb_ai_corpus_snapshot.upd_user; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_corpus_snapshot.upd_user IS '최종 수정자 (사번)';

--
-- Name: tb_ai_corpus_snapshot_snapshot_id_seq; Type: SEQUENCE; Schema: ax; Owner: -
--

ALTER TABLE ax.tb_ai_corpus_snapshot ALTER COLUMN snapshot_id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME ax.tb_ai_corpus_snapshot_snapshot_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

--
-- Name: tb_ai_defect_tag; Type: TABLE; Schema: ax; Owner: -
--

CREATE TABLE ax.tb_ai_defect_tag (
    tag_id integer NOT NULL,
    tag_nm character varying(30) NOT NULL,
    tag_desc character varying(200),
    use_flg common.d_yn DEFAULT 'Y'::bpchar NOT NULL
);

--
-- Name: TABLE tb_ai_defect_tag; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON TABLE ax.tb_ai_defect_tag IS 'AI 불량 유형 태그 — chip, bend, welding, stain 등 ③ 불량 판정 Agent 의 분류 태그';

--
-- Name: COLUMN tb_ai_defect_tag.tag_id; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_defect_tag.tag_id IS 'AI 불량 태그 식별자 (PK)';

--
-- Name: COLUMN tb_ai_defect_tag.tag_nm; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_defect_tag.tag_nm IS '태그명 (chip · bend · welding · stain). 대소문자를 무시하고 중복 금지';

--
-- Name: COLUMN tb_ai_defect_tag.tag_desc; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_defect_tag.tag_desc IS '태그 설명. AI 모델 설정 화면의 설명 칸';

--
-- Name: COLUMN tb_ai_defect_tag.use_flg; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_defect_tag.use_flg IS '사용 여부. N 이면 ③ 불량 판정 Agent 의 분류에서 제외';

--
-- Name: tb_ai_defect_tag_map; Type: TABLE; Schema: ax; Owner: -
--

CREATE TABLE ax.tb_ai_defect_tag_map (
    tag_id integer NOT NULL,
    plant_cd common.d_plant_cd NOT NULL,
    defect_cd common.d_defect_cd NOT NULL,
    ins_date timestamp with time zone DEFAULT now() NOT NULL,
    ins_user common.d_user_id
);

--
-- Name: TABLE tb_ai_defect_tag_map; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON TABLE ax.tb_ai_defect_tag_map IS 'AI 태그 ↔ MES 불량 코드 매핑. mes.tb_md_defect (plant_cd, defect_cd) 를 논리 참조';

--
-- Name: COLUMN tb_ai_defect_tag_map.tag_id; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_defect_tag_map.tag_id IS 'AI 불량 태그 (ax.tb_ai_defect_tag). 태그를 지우면 매핑도 함께 삭제됨';

--
-- Name: COLUMN tb_ai_defect_tag_map.plant_cd; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_defect_tag_map.plant_cd IS '공장 코드. 불량 코드가 공장별이라 함께 묶어 매핑';

--
-- Name: COLUMN tb_ai_defect_tag_map.defect_cd; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_defect_tag_map.defect_cd IS 'MES 불량 코드 (mes.tb_md_defect). FK 가 없어 등록 때 실재를 확인';

--
-- Name: COLUMN tb_ai_defect_tag_map.ins_date; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_defect_tag_map.ins_date IS '등록일시';

--
-- Name: COLUMN tb_ai_defect_tag_map.ins_user; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_defect_tag_map.ins_user IS '등록자 (사번)';

--
-- Name: tb_ai_defect_tag_tag_id_seq; Type: SEQUENCE; Schema: ax; Owner: -
--

ALTER TABLE ax.tb_ai_defect_tag ALTER COLUMN tag_id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME ax.tb_ai_defect_tag_tag_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

--
-- Name: tb_ai_model_asset; Type: TABLE; Schema: ax; Owner: -
--

CREATE TABLE ax.tb_ai_model_asset (
    asset_id integer NOT NULL,
    asset_kind_cd character varying(30) NOT NULL,
    asset_key character varying(50) NOT NULL,
    version_tag character varying(40) NOT NULL,
    asset_nm character varying(200) NOT NULL,
    serve_name character varying(100),
    artifact_path character varying(500) NOT NULL,
    artifact_size bigint,
    checksum character(64),
    verified_at timestamp with time zone,
    base_model character varying(200),
    base_revision character varying(60),
    lora_rank smallint,
    train_method_cd character varying(20),
    release_id character varying(40),
    dim integer,
    max_tokens integer,
    embed_model_key character varying(50),
    is_onprem boolean DEFAULT true NOT NULL,
    state_cd character varying(30) DEFAULT 'REGISTERED'::character varying NOT NULL,
    remark character varying(1000),
    ins_date timestamp with time zone DEFAULT now() NOT NULL,
    ins_user common.d_user_id,
    upd_date timestamp with time zone DEFAULT now() NOT NULL,
    upd_user common.d_user_id,
    CONSTRAINT ck_asset_embed_dim CHECK ((((asset_kind_cd)::text <> 'EMBED'::text) OR (dim IS NOT NULL))),
    CONSTRAINT ck_asset_lora_base CHECK ((((asset_kind_cd)::text <> 'LORA'::text) OR (base_model IS NOT NULL)))
);

--
-- Name: TABLE tb_ai_model_asset; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON TABLE ax.tb_ai_model_asset IS '모델 자산 레지스트리 — 서버에 놓인 모델 파일(베이스 · LoRA 어댑터 · 임베딩 · 리랭커) 1건. 가중치 자체는 파일시스템에 있고 여기에는 경로와 메타 정보만 보관';

--
-- Name: COLUMN tb_ai_model_asset.asset_id; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_model_asset.asset_id IS '모델 자산 식별자 (PK)';

--
-- Name: COLUMN tb_ai_model_asset.asset_kind_cd; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_model_asset.asset_kind_cd IS '자산 종류. 공통코드 그룹 = AI_ASSET_KIND (LLM_BASE=베이스 모델, LORA=LoRA 어댑터, EMBED=임베딩, RERANK=리랭커)';

--
-- Name: COLUMN tb_ai_model_asset.asset_key; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_model_asset.asset_key IS '자산 논리 키 (예: qwen3-8b, assistant, report, report-think, bge-m3). 버전을 뺀 이름';

--
-- Name: COLUMN tb_ai_model_asset.version_tag; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_model_asset.version_tag IS '버전 태그 (예: v-202611, v2). asset_key 와 묶어 유일해야 하는 값';

--
-- Name: COLUMN tb_ai_model_asset.asset_nm; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_model_asset.asset_nm IS '자산 표시 이름. AI 서비스 버전 관리 화면 모델 자산 목록의 "이름" 칸';

--
-- Name: COLUMN tb_ai_model_asset.serve_name; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_model_asset.serve_name IS '추론 엔진에 등록되는 이름. vLLM --lora-modules 의 이름 또는 OpenAI 호환 API 의 model 필드 값 (예: ax-assistant)';

--
-- Name: COLUMN tb_ai_model_asset.artifact_path; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_model_asset.artifact_path IS '서버 파일 경로 (예: /checkpoints/assistant/v-202611). 이 경로가 사라지면 롤백이 불가능해지므로 배포 전 실존을 확인';

--
-- Name: COLUMN tb_ai_model_asset.artifact_size; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_model_asset.artifact_size IS '아티팩트 크기(바이트)';

--
-- Name: COLUMN tb_ai_model_asset.checksum; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_model_asset.checksum IS '아티팩트 SHA-256. NAS 복제본과 대조해 손상·교체 여부를 확인하는 값';

--
-- Name: COLUMN tb_ai_model_asset.verified_at; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_model_asset.verified_at IS '경로·체크섬 최종 확인 시각. state_cd = VERIFIED 의 근거';

--
-- Name: COLUMN tb_ai_model_asset.base_model; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_model_asset.base_model IS '베이스 모델 이름. LORA 자산은 반드시 있어야 한다 (ck_asset_lora_base)';

--
-- Name: COLUMN tb_ai_model_asset.base_revision; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_model_asset.base_revision IS '베이스 모델 커밋 해시. 베이스가 바뀌면 평가 기준선을 다시 잡아야 함';

--
-- Name: COLUMN tb_ai_model_asset.lora_rank; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_model_asset.lora_rank IS 'LoRA 랭크(r). 어댑터 용량과 표현력을 정하는 값';

--
-- Name: COLUMN tb_ai_model_asset.train_method_cd; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_model_asset.train_method_cd IS '학습 방식. 공통코드 그룹 = AI_TRAIN_METHOD (LLM_fine_tuning/sql/02 에서 등록). LoRA 와 QLoRA 결과를 같은 선에서 비교하면 안 됨';

--
-- Name: COLUMN tb_ai_model_asset.release_id; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_model_asset.release_id IS 'ax.tb_ai_model_release.release_id 와의 느슨한 연결. 이 파일이 sql/02 보다 먼저 실행되므로 FK 를 걸지 않음';

--
-- Name: COLUMN tb_ai_model_asset.dim; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_model_asset.dim IS '임베딩 차원. vec.tb_doc_chunk.embedding 의 선언 차원과 반드시 일치해야 한다 (18.8 점검)';

--
-- Name: COLUMN tb_ai_model_asset.max_tokens; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_model_asset.max_tokens IS '이 모델이 한 번에 받는 최대 토큰 수';

--
-- Name: COLUMN tb_ai_model_asset.embed_model_key; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_model_asset.embed_model_key IS 'vec.tb_embed_model.model_key 와의 연결 키';

--
-- Name: COLUMN tb_ai_model_asset.is_onprem; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_model_asset.is_onprem IS 'false = 외부 API. 기밀 문서 처리 프로필에는 선택할 수 없음';

--
-- Name: COLUMN tb_ai_model_asset.state_cd; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_model_asset.state_cd IS '상태. 공통코드 그룹 = AI_ASSET_STATE (REGISTERED=등록, VERIFIED=경로·체크섬 확인됨, MISSING=파일 없음, RETIRED=폐기)';

--
-- Name: COLUMN tb_ai_model_asset.remark; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_model_asset.remark IS '비고';

--
-- Name: COLUMN tb_ai_model_asset.ins_date; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_model_asset.ins_date IS '등록일시';

--
-- Name: COLUMN tb_ai_model_asset.ins_user; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_model_asset.ins_user IS '등록자 (사번)';

--
-- Name: COLUMN tb_ai_model_asset.upd_date; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_model_asset.upd_date IS '최종 수정일시';

--
-- Name: COLUMN tb_ai_model_asset.upd_user; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_model_asset.upd_user IS '최종 수정자 (사번)';

--
-- Name: tb_ai_model_asset_asset_id_seq; Type: SEQUENCE; Schema: ax; Owner: -
--

ALTER TABLE ax.tb_ai_model_asset ALTER COLUMN asset_id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME ax.tb_ai_model_asset_asset_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

--
-- Name: tb_ai_serving_asset; Type: TABLE; Schema: ax; Owner: -
--

CREATE TABLE ax.tb_ai_serving_asset (
    profile_id integer NOT NULL,
    role_cd character varying(30) NOT NULL,
    asset_id integer NOT NULL,
    remark character varying(300),
    ins_date timestamp with time zone DEFAULT now() NOT NULL,
    ins_user common.d_user_id
);

--
-- Name: TABLE tb_ai_serving_asset; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON TABLE ax.tb_ai_serving_asset IS '서빙 프로필 × 모델 자산. 역할당 자산 1개이며, 프로필이 참조하는 자산은 삭제할 수 없음(RESTRICT)';

--
-- Name: COLUMN tb_ai_serving_asset.profile_id; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_serving_asset.profile_id IS '서빙 프로필 (ax.tb_ai_serving_profile). 배포 때 트리거가 필수 역할 자산을 여기서 확인';

--
-- Name: COLUMN tb_ai_serving_asset.role_cd; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_serving_asset.role_cd IS '역할. 공통코드 그룹 = AI_SERVING_ROLE (LLM_BASE, LORA_ASSISTANT, LORA_REPORT, LORA_REPORT_THINK, EMBED, RERANK)';

--
-- Name: COLUMN tb_ai_serving_asset.asset_id; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_serving_asset.asset_id IS '역할에 배정한 모델 자산 (ax.tb_ai_model_asset). 물린 자산은 삭제 불가';

--
-- Name: COLUMN tb_ai_serving_asset.remark; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_serving_asset.remark IS '비고';

--
-- Name: COLUMN tb_ai_serving_asset.ins_date; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_serving_asset.ins_date IS '등록일시';

--
-- Name: COLUMN tb_ai_serving_asset.ins_user; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_serving_asset.ins_user IS '등록자 (사번)';

--
-- Name: tb_ai_serving_route; Type: TABLE; Schema: ax; Owner: -
--

CREATE TABLE ax.tb_ai_serving_route (
    route_id integer NOT NULL,
    service_cd character varying(20) DEFAULT 'CHAT'::character varying NOT NULL,
    scope_cd character varying(20) NOT NULL,
    dept_id integer,
    user_id common.d_user_id,
    profile_id integer NOT NULL,
    weight_pct smallint DEFAULT 100 NOT NULL,
    valid_from timestamp with time zone,
    valid_to timestamp with time zone,
    use_flg common.d_yn DEFAULT 'Y'::bpchar NOT NULL,
    remark character varying(300),
    ins_date timestamp with time zone DEFAULT now() NOT NULL,
    ins_user common.d_user_id,
    upd_date timestamp with time zone DEFAULT now() NOT NULL,
    upd_user common.d_user_id,
    CONSTRAINT ck_route_dept CHECK ((((scope_cd)::text <> 'DEPT'::text) OR (dept_id IS NOT NULL))),
    CONSTRAINT ck_route_global CHECK ((((scope_cd)::text <> 'GLOBAL'::text) OR ((dept_id IS NULL) AND (user_id IS NULL)))),
    CONSTRAINT ck_route_period CHECK (((valid_to IS NULL) OR (valid_from IS NULL) OR (valid_to >= valid_from))),
    CONSTRAINT ck_route_user CHECK ((((scope_cd)::text <> 'USER'::text) OR (user_id IS NOT NULL))),
    CONSTRAINT ck_route_weight CHECK (((weight_pct >= 1) AND (weight_pct <= 100)))
);

--
-- Name: TABLE tb_ai_serving_route; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON TABLE ax.tb_ai_serving_route IS '서빙 라우팅 — 전역/부서/계정 단위로 사용할 버전을 지정. 카나리 대상 지정과 A/B 비중 배분이 여기서 구성';

--
-- Name: COLUMN tb_ai_serving_route.route_id; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_serving_route.route_id IS '라우팅 식별자 (PK). 등록·조회만 되고 질의 경로는 아직 이 표를 보지 않음';

--
-- Name: COLUMN tb_ai_serving_route.service_cd; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_serving_route.service_cd IS '대상 서비스. 공통코드 그룹 = AI_SERVICE (CHAT=AI 채팅, REPORT=보고서 생성, SEARCH=문서 검색)';

--
-- Name: COLUMN tb_ai_serving_route.scope_cd; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_serving_route.scope_cd IS '적용 범위. 공통코드 그룹 = AI_ROUTE_SCOPE (GLOBAL=전체, DEPT=부서, USER=계정). 좁은 범위가 우선';

--
-- Name: COLUMN tb_ai_serving_route.dept_id; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_serving_route.dept_id IS '부서 범위 대상 (ax.tb_sys_dept). scope_cd = DEPT 면 반드시 있어야 함';

--
-- Name: COLUMN tb_ai_serving_route.user_id; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_serving_route.user_id IS '계정 범위 대상 사번 (ax.tb_sys_user). scope_cd = USER 면 반드시 있어야 함';

--
-- Name: COLUMN tb_ai_serving_route.profile_id; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_serving_route.profile_id IS '이 범위가 쓸 서빙 버전 (ax.tb_ai_serving_profile)';

--
-- Name: COLUMN tb_ai_serving_route.weight_pct; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_serving_route.weight_pct IS '같은 범위에 여러 버전을 둘 때의 배분 비중. 사용자 ID 해시로 결정론적으로 배정';

--
-- Name: COLUMN tb_ai_serving_route.valid_from; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_serving_route.valid_from IS '적용 시작 시각. 현재 API 는 채우지 않아 NULL (= 즉시 적용)';

--
-- Name: COLUMN tb_ai_serving_route.valid_to; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_serving_route.valid_to IS '적용 종료 시각. 카나리 기간을 미리 정해 두면 만료 후 자동으로 ACTIVE 로 복귀';

--
-- Name: COLUMN tb_ai_serving_route.use_flg; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_serving_route.use_flg IS '사용 여부. Y 인 행만 중복 검사(uq_serving_route)와 라우팅 판정의 대상';

--
-- Name: COLUMN tb_ai_serving_route.remark; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_serving_route.remark IS '비고';

--
-- Name: COLUMN tb_ai_serving_route.ins_date; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_serving_route.ins_date IS '등록일시';

--
-- Name: COLUMN tb_ai_serving_route.ins_user; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_serving_route.ins_user IS '등록자 (사번)';

--
-- Name: COLUMN tb_ai_serving_route.upd_date; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_serving_route.upd_date IS '최종 수정일시';

--
-- Name: COLUMN tb_ai_serving_route.upd_user; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_ai_serving_route.upd_user IS '최종 수정자 (사번)';

--
-- Name: tb_ai_serving_route_route_id_seq; Type: SEQUENCE; Schema: ax; Owner: -
--

ALTER TABLE ax.tb_ai_serving_route ALTER COLUMN route_id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME ax.tb_ai_serving_route_route_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

--
-- Name: tb_aoi_defect_image; Type: TABLE; Schema: ax; Owner: -
--

CREATE TABLE ax.tb_aoi_defect_image (
    image_id bigint NOT NULL,
    plant_cd common.d_plant_cd,
    wc_cd character varying(30) NOT NULL,
    lot_no character varying(30) NOT NULL,
    serial_no character varying(30) NOT NULL,
    defect_cd common.d_defect_cd,
    seq smallint DEFAULT 1 NOT NULL,
    nas_path character varying(500) NOT NULL,
    file_size bigint,
    captured_at timestamp with time zone,
    ins_date timestamp with time zone DEFAULT now() NOT NULL,
    ins_user common.d_user_id,
    source_cd character varying(30) DEFAULT 'LABEL'::character varying NOT NULL,
    eqpt_cd character varying(50),
    defect_id character varying(200) GENERATED ALWAYS AS (
CASE
    WHEN ((source_cd)::text = 'DIMENSION'::text) THEN (((((((wc_cd)::text || '~'::text) || (eqpt_cd)::text) || '~'::text) || (lot_no)::text) || '~'::text) || (serial_no)::text)
    ELSE (((((((plant_cd)::text || '-'::text) || (wc_cd)::text) || '-'::text) || (lot_no)::text) || '-'::text) || (serial_no)::text)
END) STORED,
    CONSTRAINT ck_aoi_defect_image_key CHECK (
CASE source_cd
    WHEN 'LABEL'::text THEN (plant_cd IS NOT NULL)
    WHEN 'DIMENSION'::text THEN (eqpt_cd IS NOT NULL)
    ELSE false
END),
    CONSTRAINT ck_aoi_defect_image_seq CHECK ((seq >= 1)),
    CONSTRAINT ck_aoi_defect_image_size CHECK (((file_size IS NULL) OR (file_size >= 0)))
);

--
-- Name: TABLE tb_aoi_defect_image; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON TABLE ax.tb_aoi_defect_image IS 'AOI 판정 ↔ NAS 불량 사진 매핑. 원본 2종 — MES 라벨 이력(LABEL) · MSSQL EDGE.dbo.TB_SAMSUN_DIMENSION(DIMENSION)';

--
-- Name: COLUMN tb_aoi_defect_image.image_id; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_aoi_defect_image.image_id IS '사진 매핑 대리키';

--
-- Name: COLUMN tb_aoi_defect_image.plant_cd; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_aoi_defect_image.plant_cd IS '공장 코드. LABEL 원본에만 있다 (MSSQL DIMENSION 에는 공장 컬럼이 없음)';

--
-- Name: COLUMN tb_aoi_defect_image.wc_cd; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_aoi_defect_image.wc_cd IS '작업장 코드. 표시명은 공통코드 AOI_WC(S110=도금, S120=도장) → mes.tb_md_workcenter.wc_nm → 원값 순으로 조회. 원본에 빈 문자열·NULL_ 접두가 섞여 있음';

--
-- Name: COLUMN tb_aoi_defect_image.lot_no; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_aoi_defect_image.lot_no IS '로트 번호. MSSQL 값 길이를 확인하지 못해 도메인(varchar(8)) 대신 varchar(30)';

--
-- Name: COLUMN tb_aoi_defect_image.serial_no; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_aoi_defect_image.serial_no IS '시리얼 번호. 같은 이유로 varchar(30)';

--
-- Name: COLUMN tb_aoi_defect_image.defect_cd; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_aoi_defect_image.defect_cd IS 'AOI 장비가 준 불량 유형 코드(mes.tb_md_defect 와 논리 연결). MES 라벨에는 유형이 없어 NULL 이 보통';

--
-- Name: COLUMN tb_aoi_defect_image.seq; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_aoi_defect_image.seq IS '같은 판정 키에 사진이 여러 장일 때의 순번 (1부터). 품질 보고서 화면 불량 상세 모달의 「#1」 표시와 사진 넘김 차례';

--
-- Name: COLUMN tb_aoi_defect_image.nas_path; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_aoi_defect_image.nas_path IS 'NAS 경로. 브라우저가 직접 읽지 않고 GET /files/aoi-images/{imageId} 프록시가 화이트리스트 루트 아래에서만 읽음';

--
-- Name: COLUMN tb_aoi_defect_image.file_size; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_aoi_defect_image.file_size IS '사진 파일 크기(byte). 품질 보고서 화면의 불량 상세 모달에서 KB 로 환산해 촬영 시각 옆에 표시';

--
-- Name: COLUMN tb_aoi_defect_image.captured_at; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_aoi_defect_image.captured_at IS 'AOI 촬영 시각. 없으면 라벨의 ins_date(KST 벽시계, timestamp(3)) 를 사용';

--
-- Name: COLUMN tb_aoi_defect_image.ins_date; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_aoi_defect_image.ins_date IS '등록일시';

--
-- Name: COLUMN tb_aoi_defect_image.ins_user; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_aoi_defect_image.ins_user IS '등록자 (사번)';

--
-- Name: COLUMN tb_aoi_defect_image.source_cd; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_aoi_defect_image.source_cd IS '판정 키 원본. 공통코드 그룹 = AOI_IMG_SOURCE (LABEL=MES 라벨 이력, DIMENSION=MSSQL 치수검사)';

--
-- Name: COLUMN tb_aoi_defect_image.eqpt_cd; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_aoi_defect_image.eqpt_cd IS '설비 코드. DIMENSION 원본에만 있음';

--
-- Name: COLUMN tb_aoi_defect_image.defect_id; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_aoi_defect_image.defect_id IS '생성 컬럼. DIMENSION = wc~eqpt~lot~serial, LABEL = plant-wc-lot-serial. API 의 defectId';

--
-- Name: tb_aoi_defect_image_image_id_seq; Type: SEQUENCE; Schema: ax; Owner: -
--

ALTER TABLE ax.tb_aoi_defect_image ALTER COLUMN image_id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME ax.tb_aoi_defect_image_image_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

--
-- Name: tb_rpt_unmask_req; Type: TABLE; Schema: ax; Owner: -
--

CREATE TABLE ax.tb_rpt_unmask_req (
    req_id bigint NOT NULL,
    menu_id character varying(30),
    field_keys text[] NOT NULL,
    reason character varying(500) NOT NULL,
    state_cd character varying(30) DEFAULT 'REQUESTED'::character varying NOT NULL,
    requested_at timestamp with time zone DEFAULT now() NOT NULL,
    requester_id common.d_user_id NOT NULL,
    requester_dept character varying(50),
    approver_id common.d_user_id,
    approved_at timestamp with time zone,
    approve_note character varying(500)
);

--
-- Name: TABLE tb_rpt_unmask_req; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON TABLE ax.tb_rpt_unmask_req IS '마스킹 해제 요청 — 화면(menu_id) 과 데이터 항목(field_keys) 단위로 요청. 감사 로그와 연계';

--
-- Name: COLUMN tb_rpt_unmask_req.req_id; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_rpt_unmask_req.req_id IS '해제 요청 대리키';

--
-- Name: COLUMN tb_rpt_unmask_req.menu_id; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_rpt_unmask_req.menu_id IS '해제를 요청한 화면 (ax.tb_sys_menu)';

--
-- Name: COLUMN tb_rpt_unmask_req.field_keys; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_rpt_unmask_req.field_keys IS '해제를 요청한 데이터 항목들 (ax.tb_sys_data_field.field_key 배열)';

--
-- Name: COLUMN tb_rpt_unmask_req.reason; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_rpt_unmask_req.reason IS '요청 사유. 승인 판단과 감사 증빙에 사용';

--
-- Name: COLUMN tb_rpt_unmask_req.state_cd; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_rpt_unmask_req.state_cd IS '처리 상태 — REQUESTED / APPROVED / REJECTED';

--
-- Name: COLUMN tb_rpt_unmask_req.requested_at; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_rpt_unmask_req.requested_at IS '요청 시각';

--
-- Name: COLUMN tb_rpt_unmask_req.requester_id; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_rpt_unmask_req.requester_id IS '요청자 (사번)';

--
-- Name: COLUMN tb_rpt_unmask_req.requester_dept; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_rpt_unmask_req.requester_dept IS '요청 당시 소속 부서명 스냅샷';

--
-- Name: COLUMN tb_rpt_unmask_req.approver_id; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_rpt_unmask_req.approver_id IS '승인·반려한 사람 (사번). 미처리면 비어 있음';

--
-- Name: COLUMN tb_rpt_unmask_req.approved_at; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_rpt_unmask_req.approved_at IS '승인·반려 처리 시각';

--
-- Name: COLUMN tb_rpt_unmask_req.approve_note; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_rpt_unmask_req.approve_note IS '승인·반려 의견. 반려 사유를 여기에 남김';

--
-- Name: tb_rpt_unmask_req_req_id_seq; Type: SEQUENCE; Schema: ax; Owner: -
--

ALTER TABLE ax.tb_rpt_unmask_req ALTER COLUMN req_id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME ax.tb_rpt_unmask_req_req_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

--
-- Name: tb_rpt_write_state; Type: TABLE; Schema: ax; Owner: -
--

CREATE TABLE ax.tb_rpt_write_state (
    menu_id character varying(30) NOT NULL,
    biz_date date NOT NULL,
    state_cd character varying(30) NOT NULL,
    ins_date timestamp with time zone DEFAULT now() NOT NULL,
    ins_user common.d_user_id,
    upd_date timestamp with time zone DEFAULT now() NOT NULL,
    upd_user common.d_user_id,
    CONSTRAINT ck_rpt_write_state_cd CHECK (((state_cd)::text = ANY ((ARRAY['DRAFT'::character varying, 'SUBMITTED'::character varying, 'APPROVED'::character varying])::text[])))
);

--
-- Name: TABLE tb_rpt_write_state; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON TABLE ax.tb_rpt_write_state IS '보고서 화면·대상일별 작성 상태 표시 (워크플로우 아님, 행 없음 = NONE)';

--
-- Name: COLUMN tb_rpt_write_state.menu_id; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_rpt_write_state.menu_id IS '보고서 화면 ID = ax.tb_sys_menu.menu_id. 보고서 화면인지는 API 가 검증';

--
-- Name: COLUMN tb_rpt_write_state.biz_date; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_rpt_write_state.biz_date IS '보고서 대상일 (작성일이 아님)';

--
-- Name: COLUMN tb_rpt_write_state.state_cd; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_rpt_write_state.state_cd IS 'DRAFT | SUBMITTED | APPROVED. NONE 은 행이 없는 상태';

--
-- Name: COLUMN tb_rpt_write_state.ins_date; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_rpt_write_state.ins_date IS '등록일시';

--
-- Name: COLUMN tb_rpt_write_state.ins_user; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_rpt_write_state.ins_user IS '등록자 (사번)';

--
-- Name: COLUMN tb_rpt_write_state.upd_date; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_rpt_write_state.upd_date IS '최종 수정일시 — 상태를 마지막으로 바꾼 시각';

--
-- Name: COLUMN tb_rpt_write_state.upd_user; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_rpt_write_state.upd_user IS '최종 수정자 (사번). 화면의 "작성자" 표시가 이 값';

--
-- Name: tb_sys_user_favorite; Type: TABLE; Schema: ax; Owner: -
--

CREATE TABLE ax.tb_sys_user_favorite (
    user_id common.d_user_id NOT NULL,
    menu_id character varying(30) NOT NULL,
    sort_seq smallint NOT NULL,
    ins_date timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_sys_user_favorite_seq CHECK ((sort_seq >= 0))
);

--
-- Name: TABLE tb_sys_user_favorite; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON TABLE ax.tb_sys_user_favorite IS '사용자별 즐겨찾기 화면 (보고서 센터 사이드바 고정)';

--
-- Name: COLUMN tb_sys_user_favorite.user_id; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_sys_user_favorite.user_id IS '즐겨찾기를 등록한 사번 (ax.tb_sys_user). 계정 삭제 시 함께 삭제';

--
-- Name: COLUMN tb_sys_user_favorite.menu_id; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_sys_user_favorite.menu_id IS '화면 ID = ax.tb_sys_menu.menu_id. 웹 API 에서는 screenId. 조회 시 use_flg=''Y'' 메뉴만 반환';

--
-- Name: COLUMN tb_sys_user_favorite.sort_seq; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_sys_user_favorite.sort_seq IS '사이드바 표시 순서(0부터). PUT 이 목록 전체를 다시 사용';

--
-- Name: COLUMN tb_sys_user_favorite.ins_date; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON COLUMN ax.tb_sys_user_favorite.ins_date IS '등록일시';

--
-- Name: vw_serving_profile_detail; Type: VIEW; Schema: ax; Owner: -
--

CREATE VIEW ax.vw_serving_profile_detail AS
 SELECT p.profile_id,
    p.service_cd,
    p.profile_cd,
    p.version_no,
    p.profile_nm,
    p.description,
    p.state_cd,
    p.canary_at,
    p.activated_at,
    p.retired_at,
    p.rollback_at,
    p.rollback_reason,
    au.user_nm AS activated_by_nm,
    prev.profile_nm AS prev_profile_nm,
    p.eval_score,
    p.eval_baseline,
    p.must_pass_fail,
    p.temperature,
    p.top_p,
    p.max_tokens,
    p.thinking_mode_cd,
    p.search_top_k,
    p.search_candidate_k,
    p.rrf_k,
    p.trgm_threshold,
    p.hnsw_ef_search,
    p.rerank_flg,
    p.strict_perm_flg,
    cs.snapshot_cd,
    cs.snapshot_nm,
    cs.dim AS corpus_dim,
    cs.doc_cnt,
    cs.chunk_cnt,
    cs.state_cd AS corpus_state_cd,
    cs.built_ended AS corpus_built_at,
    max((a.asset_nm)::text) FILTER (WHERE ((sa.role_cd)::text = 'LLM_BASE'::text)) AS llm_base_nm,
    max((a.version_tag)::text) FILTER (WHERE ((sa.role_cd)::text = 'LLM_BASE'::text)) AS llm_base_ver,
    max((a.serve_name)::text) FILTER (WHERE ((sa.role_cd)::text = 'LORA_ASSISTANT'::text)) AS lora_assistant,
    max((a.version_tag)::text) FILTER (WHERE ((sa.role_cd)::text = 'LORA_ASSISTANT'::text)) AS lora_assistant_ver,
    max((a.serve_name)::text) FILTER (WHERE ((sa.role_cd)::text = 'LORA_REPORT'::text)) AS lora_report,
    max((a.version_tag)::text) FILTER (WHERE ((sa.role_cd)::text = 'LORA_REPORT'::text)) AS lora_report_ver,
    max((a.serve_name)::text) FILTER (WHERE ((sa.role_cd)::text = 'LORA_REPORT_THINK'::text)) AS lora_report_think,
    max((a.asset_key)::text) FILTER (WHERE ((sa.role_cd)::text = 'EMBED'::text)) AS embed_model,
    max(a.dim) FILTER (WHERE ((sa.role_cd)::text = 'EMBED'::text)) AS embed_dim,
    max((a.asset_key)::text) FILTER (WHERE ((sa.role_cd)::text = 'RERANK'::text)) AS rerank_model,
    bool_and(a.is_onprem) AS all_onprem,
    count(sa.role_cd) AS asset_cnt,
    ( SELECT count(*) AS count
           FROM ax.tb_ai_serving_route r
          WHERE ((r.profile_id = p.profile_id) AND ((r.use_flg)::bpchar = 'Y'::bpchar))) AS route_cnt,
    ( SELECT count(*) AS count
           FROM ax.tb_ai_chat_log ch
          WHERE (ch.profile_id = p.profile_id)) AS chat_cnt,
    p.ins_date,
    p.upd_date
   FROM (((((ax.tb_ai_serving_profile p
     LEFT JOIN ax.tb_ai_corpus_snapshot cs ON ((cs.snapshot_id = p.corpus_snapshot_id)))
     LEFT JOIN ax.tb_ai_serving_asset sa ON ((sa.profile_id = p.profile_id)))
     LEFT JOIN ax.tb_ai_model_asset a ON ((a.asset_id = sa.asset_id)))
     LEFT JOIN ax.tb_sys_user au ON (((au.user_id)::text = (p.activated_by)::text)))
     LEFT JOIN ax.tb_ai_serving_profile prev ON ((prev.profile_id = p.prev_profile_id)))
  GROUP BY p.profile_id, au.user_nm, prev.profile_nm, cs.snapshot_cd, cs.snapshot_nm, cs.dim, cs.doc_cnt, cs.chunk_cnt, cs.state_cd, cs.built_ended;

--
-- Name: VIEW vw_serving_profile_detail; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON VIEW ax.vw_serving_profile_detail IS 'AI 서비스 버전 상세 — 역할별 모델 자산과 코퍼스 스냅샷을 한 행으로 전개. 관리자 화면의 버전 목록·상세가 이 뷰를 읽음';

--
-- Name: vw_serving_active; Type: VIEW; Schema: ax; Owner: -
--

CREATE VIEW ax.vw_serving_active AS
 SELECT service_cd,
    state_cd,
    profile_id,
    profile_cd,
    version_no,
    profile_nm,
    llm_base_nm,
    llm_base_ver,
    lora_assistant,
    lora_report,
    embed_model,
    embed_dim,
    snapshot_cd,
    chunk_cnt,
    activated_by_nm,
    canary_at,
    activated_at,
    eval_score,
    eval_baseline,
    route_cnt,
    chat_cnt,
    all_onprem
   FROM ax.vw_serving_profile_detail d
  WHERE ((state_cd)::text = ANY ((ARRAY['ACTIVE'::character varying, 'CANARY'::character varying])::text[]));

--
-- Name: VIEW vw_serving_active; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON VIEW ax.vw_serving_active IS '현재 서비스 중인 버전 — 서비스별 ACTIVE 와 CANARY. 관리자 화면 상단 요약 카드';

--
-- Name: vw_serving_asset_health; Type: VIEW; Schema: ax; Owner: -
--

CREATE VIEW ax.vw_serving_asset_health AS
 SELECT DISTINCT p.service_cd,
    p.state_cd AS profile_state_cd,
    p.profile_nm,
    sa.role_cd,
    a.asset_id,
    a.asset_kind_cd,
    a.asset_key,
    a.version_tag,
    a.artifact_path,
    a.checksum,
    a.state_cd AS asset_state_cd,
    a.verified_at,
        CASE
            WHEN ((a.state_cd)::text = 'MISSING'::text) THEN '파일 없음'::text
            WHEN ((a.state_cd)::text = 'RETIRED'::text) THEN '폐기된 자산'::text
            WHEN (a.verified_at IS NULL) THEN '미검증'::text
            WHEN (a.verified_at < (now() - '30 days'::interval)) THEN '검증 30일 초과'::text
            ELSE 'OK'::text
        END AS health_note
   FROM ((ax.tb_ai_serving_profile p
     JOIN ax.tb_ai_serving_asset sa ON ((sa.profile_id = p.profile_id)))
     JOIN ax.tb_ai_model_asset a ON ((a.asset_id = sa.asset_id)))
  WHERE (((p.state_cd)::text = ANY ((ARRAY['ACTIVE'::character varying, 'CANARY'::character varying])::text[])) OR (p.profile_id IN ( SELECT p2.prev_profile_id
           FROM ax.tb_ai_serving_profile p2
          WHERE (((p2.state_cd)::text = ANY ((ARRAY['ACTIVE'::character varying, 'CANARY'::character varying])::text[])) AND (p2.prev_profile_id IS NOT NULL)))));

--
-- Name: VIEW vw_serving_asset_health; Type: COMMENT; Schema: ax; Owner: -
--

COMMENT ON VIEW ax.vw_serving_asset_health IS '서비스 중인 버전과 롤백 대상 직전 버전이 참조하는 모델 파일 목록. 배포 스크립트가 이 경로의 실존을 확인해야 함 — DB 만으로는 파일 유무를 알 수 없음';

--
-- Name: tb_rpt_write_state pk_rpt_write_state; Type: CONSTRAINT; Schema: ax; Owner: -
--

ALTER TABLE ONLY ax.tb_rpt_write_state
    ADD CONSTRAINT pk_rpt_write_state PRIMARY KEY (menu_id, biz_date);

--
-- Name: tb_sys_user_favorite pk_sys_user_favorite; Type: CONSTRAINT; Schema: ax; Owner: -
--

ALTER TABLE ONLY ax.tb_sys_user_favorite
    ADD CONSTRAINT pk_sys_user_favorite PRIMARY KEY (user_id, menu_id);

--
-- Name: tb_ai_chat_term pk_tb_ai_chat_term; Type: CONSTRAINT; Schema: ax; Owner: -
--

ALTER TABLE ONLY ax.tb_ai_chat_term
    ADD CONSTRAINT pk_tb_ai_chat_term PRIMARY KEY (chat_id, term_id);

--
-- Name: tb_ai_defect_tag_map pk_tb_ai_defect_tag_map; Type: CONSTRAINT; Schema: ax; Owner: -
--

ALTER TABLE ONLY ax.tb_ai_defect_tag_map
    ADD CONSTRAINT pk_tb_ai_defect_tag_map PRIMARY KEY (tag_id, plant_cd, defect_cd);

--
-- Name: tb_ai_serving_asset pk_tb_ai_serving_asset; Type: CONSTRAINT; Schema: ax; Owner: -
--

ALTER TABLE ONLY ax.tb_ai_serving_asset
    ADD CONSTRAINT pk_tb_ai_serving_asset PRIMARY KEY (profile_id, role_cd);

--
-- Name: tb_ai_corpus_snapshot tb_ai_corpus_snapshot_pkey; Type: CONSTRAINT; Schema: ax; Owner: -
--

ALTER TABLE ONLY ax.tb_ai_corpus_snapshot
    ADD CONSTRAINT tb_ai_corpus_snapshot_pkey PRIMARY KEY (snapshot_id);

--
-- Name: tb_ai_defect_tag tb_ai_defect_tag_pkey; Type: CONSTRAINT; Schema: ax; Owner: -
--

ALTER TABLE ONLY ax.tb_ai_defect_tag
    ADD CONSTRAINT tb_ai_defect_tag_pkey PRIMARY KEY (tag_id);

--
-- Name: tb_ai_model_asset tb_ai_model_asset_pkey; Type: CONSTRAINT; Schema: ax; Owner: -
--

ALTER TABLE ONLY ax.tb_ai_model_asset
    ADD CONSTRAINT tb_ai_model_asset_pkey PRIMARY KEY (asset_id);

--
-- Name: tb_ai_serving_route tb_ai_serving_route_pkey; Type: CONSTRAINT; Schema: ax; Owner: -
--

ALTER TABLE ONLY ax.tb_ai_serving_route
    ADD CONSTRAINT tb_ai_serving_route_pkey PRIMARY KEY (route_id);

--
-- Name: tb_aoi_defect_image tb_aoi_defect_image_pkey; Type: CONSTRAINT; Schema: ax; Owner: -
--

ALTER TABLE ONLY ax.tb_aoi_defect_image
    ADD CONSTRAINT tb_aoi_defect_image_pkey PRIMARY KEY (image_id);

--
-- Name: tb_rpt_unmask_req tb_rpt_unmask_req_pkey; Type: CONSTRAINT; Schema: ax; Owner: -
--

ALTER TABLE ONLY ax.tb_rpt_unmask_req
    ADD CONSTRAINT tb_rpt_unmask_req_pkey PRIMARY KEY (req_id);

--
-- Name: tb_aoi_defect_image uq_aoi_defect_image_defect_seq; Type: CONSTRAINT; Schema: ax; Owner: -
--

ALTER TABLE ONLY ax.tb_aoi_defect_image
    ADD CONSTRAINT uq_aoi_defect_image_defect_seq UNIQUE (defect_id, seq);

--
-- Name: tb_sys_user_favorite uq_sys_user_favorite_seq; Type: CONSTRAINT; Schema: ax; Owner: -
--

ALTER TABLE ONLY ax.tb_sys_user_favorite
    ADD CONSTRAINT uq_sys_user_favorite_seq UNIQUE (user_id, sort_seq) DEFERRABLE INITIALLY DEFERRED;

--
-- Name: tb_ai_corpus_snapshot uq_tb_ai_corpus_snapshot; Type: CONSTRAINT; Schema: ax; Owner: -
--

ALTER TABLE ONLY ax.tb_ai_corpus_snapshot
    ADD CONSTRAINT uq_tb_ai_corpus_snapshot UNIQUE (snapshot_cd);

--
-- Name: tb_ai_defect_tag uq_tb_ai_defect_tag_nm; Type: CONSTRAINT; Schema: ax; Owner: -
--

ALTER TABLE ONLY ax.tb_ai_defect_tag
    ADD CONSTRAINT uq_tb_ai_defect_tag_nm UNIQUE (tag_nm);

--
-- Name: tb_ai_model_asset uq_tb_ai_model_asset; Type: CONSTRAINT; Schema: ax; Owner: -
--

ALTER TABLE ONLY ax.tb_ai_model_asset
    ADD CONSTRAINT uq_tb_ai_model_asset UNIQUE (asset_kind_cd, asset_key, version_tag);

--
-- Name: tb_ai_model_asset uq_tb_ai_model_asset_path; Type: CONSTRAINT; Schema: ax; Owner: -
--

ALTER TABLE ONLY ax.tb_ai_model_asset
    ADD CONSTRAINT uq_tb_ai_model_asset_path UNIQUE (artifact_path);

--
-- Name: ix_ai_asset_release; Type: INDEX; Schema: ax; Owner: -
--

CREATE INDEX ix_ai_asset_release ON ax.tb_ai_model_asset USING btree (release_id) WHERE (release_id IS NOT NULL);

--
-- Name: ix_ai_asset_state; Type: INDEX; Schema: ax; Owner: -
--

CREATE INDEX ix_ai_asset_state ON ax.tb_ai_model_asset USING btree (state_cd);

--
-- Name: ix_ai_corpus_state; Type: INDEX; Schema: ax; Owner: -
--

CREATE INDEX ix_ai_corpus_state ON ax.tb_ai_corpus_snapshot USING btree (state_cd, built_ended DESC);

--
-- Name: ix_ai_tag_map_defect; Type: INDEX; Schema: ax; Owner: -
--

CREATE INDEX ix_ai_tag_map_defect ON ax.tb_ai_defect_tag_map USING btree (plant_cd, defect_cd);

--
-- Name: ix_aoi_defect_image_captured; Type: INDEX; Schema: ax; Owner: -
--

CREATE INDEX ix_aoi_defect_image_captured ON ax.tb_aoi_defect_image USING btree (captured_at);

--
-- Name: ix_rpt_write_state_date; Type: INDEX; Schema: ax; Owner: -
--

CREATE INDEX ix_rpt_write_state_date ON ax.tb_rpt_write_state USING btree (biz_date);

--
-- Name: ix_serving_asset_asset; Type: INDEX; Schema: ax; Owner: -
--

CREATE INDEX ix_serving_asset_asset ON ax.tb_ai_serving_asset USING btree (asset_id);

--
-- Name: ix_serving_route_dept; Type: INDEX; Schema: ax; Owner: -
--

CREATE INDEX ix_serving_route_dept ON ax.tb_ai_serving_route USING btree (service_cd, dept_id) WHERE (((scope_cd)::text = 'DEPT'::text) AND ((use_flg)::bpchar = 'Y'::bpchar));

--
-- Name: ix_serving_route_prof; Type: INDEX; Schema: ax; Owner: -
--

CREATE INDEX ix_serving_route_prof ON ax.tb_ai_serving_route USING btree (profile_id);

--
-- Name: ix_serving_route_user; Type: INDEX; Schema: ax; Owner: -
--

CREATE INDEX ix_serving_route_user ON ax.tb_ai_serving_route USING btree (service_cd, user_id) WHERE (((scope_cd)::text = 'USER'::text) AND ((use_flg)::bpchar = 'Y'::bpchar));

--
-- Name: uq_serving_route; Type: INDEX; Schema: ax; Owner: -
--

CREATE UNIQUE INDEX uq_serving_route ON ax.tb_ai_serving_route USING btree (service_cd, scope_cd, dept_id, user_id, profile_id) NULLS NOT DISTINCT WHERE ((use_flg)::bpchar = 'Y'::bpchar);

--
-- Name: tb_rpt_write_state fk_rpt_write_state_menu; Type: FK CONSTRAINT; Schema: ax; Owner: -
--

ALTER TABLE ONLY ax.tb_rpt_write_state
    ADD CONSTRAINT fk_rpt_write_state_menu FOREIGN KEY (menu_id) REFERENCES ax.tb_sys_menu(menu_id);

--
-- Name: tb_sys_user_favorite fk_sys_user_favorite_menu; Type: FK CONSTRAINT; Schema: ax; Owner: -
--

ALTER TABLE ONLY ax.tb_sys_user_favorite
    ADD CONSTRAINT fk_sys_user_favorite_menu FOREIGN KEY (menu_id) REFERENCES ax.tb_sys_menu(menu_id) ON DELETE CASCADE;

--
-- Name: tb_sys_user_favorite fk_sys_user_favorite_user; Type: FK CONSTRAINT; Schema: ax; Owner: -
--

ALTER TABLE ONLY ax.tb_sys_user_favorite
    ADD CONSTRAINT fk_sys_user_favorite_user FOREIGN KEY (user_id) REFERENCES ax.tb_sys_user(user_id) ON DELETE CASCADE;

--
-- Name: tb_ai_chat_term tb_ai_chat_term_chat_id_fkey; Type: FK CONSTRAINT; Schema: ax; Owner: -
--

ALTER TABLE ONLY ax.tb_ai_chat_term
    ADD CONSTRAINT tb_ai_chat_term_chat_id_fkey FOREIGN KEY (chat_id) REFERENCES ax.tb_ai_chat_log(chat_id) ON DELETE CASCADE;

--
-- Name: tb_ai_chat_term tb_ai_chat_term_term_id_fkey; Type: FK CONSTRAINT; Schema: ax; Owner: -
--

ALTER TABLE ONLY ax.tb_ai_chat_term
    ADD CONSTRAINT tb_ai_chat_term_term_id_fkey FOREIGN KEY (term_id) REFERENCES ax.tb_gls_term(term_id);

--
-- Name: tb_ai_chat_term tb_ai_chat_term_variant_id_fkey; Type: FK CONSTRAINT; Schema: ax; Owner: -
--

ALTER TABLE ONLY ax.tb_ai_chat_term
    ADD CONSTRAINT tb_ai_chat_term_variant_id_fkey FOREIGN KEY (variant_id) REFERENCES ax.tb_gls_variant(variant_id);

--
-- Name: tb_ai_corpus_snapshot tb_ai_corpus_snapshot_embed_asset_id_fkey; Type: FK CONSTRAINT; Schema: ax; Owner: -
--

ALTER TABLE ONLY ax.tb_ai_corpus_snapshot
    ADD CONSTRAINT tb_ai_corpus_snapshot_embed_asset_id_fkey FOREIGN KEY (embed_asset_id) REFERENCES ax.tb_ai_model_asset(asset_id);

--
-- Name: tb_ai_defect_tag_map tb_ai_defect_tag_map_tag_id_fkey; Type: FK CONSTRAINT; Schema: ax; Owner: -
--

ALTER TABLE ONLY ax.tb_ai_defect_tag_map
    ADD CONSTRAINT tb_ai_defect_tag_map_tag_id_fkey FOREIGN KEY (tag_id) REFERENCES ax.tb_ai_defect_tag(tag_id) ON DELETE CASCADE;

--
-- Name: tb_ai_serving_asset tb_ai_serving_asset_asset_id_fkey; Type: FK CONSTRAINT; Schema: ax; Owner: -
--

ALTER TABLE ONLY ax.tb_ai_serving_asset
    ADD CONSTRAINT tb_ai_serving_asset_asset_id_fkey FOREIGN KEY (asset_id) REFERENCES ax.tb_ai_model_asset(asset_id) ON DELETE RESTRICT;

--
-- Name: tb_ai_serving_asset tb_ai_serving_asset_profile_id_fkey; Type: FK CONSTRAINT; Schema: ax; Owner: -
--

ALTER TABLE ONLY ax.tb_ai_serving_asset
    ADD CONSTRAINT tb_ai_serving_asset_profile_id_fkey FOREIGN KEY (profile_id) REFERENCES ax.tb_ai_serving_profile(profile_id) ON DELETE CASCADE;

--
-- Name: tb_ai_serving_route tb_ai_serving_route_dept_id_fkey; Type: FK CONSTRAINT; Schema: ax; Owner: -
--

ALTER TABLE ONLY ax.tb_ai_serving_route
    ADD CONSTRAINT tb_ai_serving_route_dept_id_fkey FOREIGN KEY (dept_id) REFERENCES ax.tb_sys_dept(dept_id) ON DELETE CASCADE;

--
-- Name: tb_ai_serving_route tb_ai_serving_route_profile_id_fkey; Type: FK CONSTRAINT; Schema: ax; Owner: -
--

ALTER TABLE ONLY ax.tb_ai_serving_route
    ADD CONSTRAINT tb_ai_serving_route_profile_id_fkey FOREIGN KEY (profile_id) REFERENCES ax.tb_ai_serving_profile(profile_id) ON DELETE CASCADE;

--
-- Name: tb_ai_serving_route tb_ai_serving_route_user_id_fkey; Type: FK CONSTRAINT; Schema: ax; Owner: -
--

ALTER TABLE ONLY ax.tb_ai_serving_route
    ADD CONSTRAINT tb_ai_serving_route_user_id_fkey FOREIGN KEY (user_id) REFERENCES ax.tb_sys_user(user_id) ON DELETE CASCADE;

--
--

-- ── 2. tb_ai_serving_profile → corpus_snapshot FK 복원 ─────────────────────────────
ALTER TABLE ONLY ax.tb_ai_serving_profile
    ADD CONSTRAINT tb_ai_serving_profile_corpus_snapshot_id_fkey
    FOREIGN KEY (corpus_snapshot_id) REFERENCES ax.tb_ai_corpus_snapshot(snapshot_id);

-- ── 3. 함수·트리거 복원 ─────────────────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION ax.fn_guard_serving_state()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
DECLARE
    v_missing_role  text;
    v_bad_asset     text;
    v_corpus_state  varchar(30);
BEGIN
    IF NEW.state_cd NOT IN ('CANARY','ACTIVE') THEN
        RETURN NEW;
    END IF;

    -- (1) 필수 역할 자산이 지정되어 있는가
    SELECT string_agg(r.role_cd, ', ') INTO v_missing_role
      FROM (VALUES ('LLM_BASE'), ('EMBED')) AS r(role_cd)
     WHERE NOT EXISTS (SELECT 1 FROM ax.tb_ai_serving_asset sa
                        WHERE sa.profile_id = NEW.profile_id AND sa.role_cd = r.role_cd);
    IF v_missing_role IS NOT NULL THEN
        RAISE EXCEPTION '서빙 프로필 %(%) 에 필수 역할 자산이 없습니다: %',
            NEW.profile_nm, NEW.profile_id, v_missing_role
            USING HINT = 'ax.tb_ai_serving_asset 에 LLM_BASE / EMBED 역할을 등록한 뒤 배포하십시오.';
    END IF;

    -- (2) 참조 자산이 사용 가능한 상태인가
    SELECT string_agg(a.asset_key || ':' || a.version_tag || '(' || a.state_cd || ')', ', ')
      INTO v_bad_asset
      FROM ax.tb_ai_serving_asset sa
      JOIN ax.tb_ai_model_asset   a ON a.asset_id = sa.asset_id
     WHERE sa.profile_id = NEW.profile_id
       AND a.state_cd NOT IN ('REGISTERED','VERIFIED');
    IF v_bad_asset IS NOT NULL THEN
        RAISE EXCEPTION '사용할 수 없는 모델 자산이 포함되어 있습니다: %', v_bad_asset
            USING HINT = 'MISSING / RETIRED 자산은 배포 대상이 될 수 없습니다.';
    END IF;

    -- (3) 코퍼스 스냅샷이 준비되었는가
    IF NEW.corpus_snapshot_id IS NULL THEN
        RAISE EXCEPTION '서빙 프로필 % 에 코퍼스 스냅샷이 지정되지 않았습니다', NEW.profile_nm
            USING HINT = 'RAG 를 쓰지 않는 프로필이라면 빈 스냅샷(state_cd=READY, chunk_cnt=0)을 만들어 지정하십시오.';
    END IF;
    SELECT cs.state_cd INTO v_corpus_state
      FROM ax.tb_ai_corpus_snapshot cs WHERE cs.snapshot_id = NEW.corpus_snapshot_id;
    IF v_corpus_state <> 'READY' THEN
        RAISE EXCEPTION '코퍼스 스냅샷 상태가 % 입니다 (READY 여야 함)', coalesce(v_corpus_state,'없음');
    END IF;

    -- (4) 시각 자동 기록
    IF NEW.state_cd = 'CANARY' AND NEW.canary_at    IS NULL THEN NEW.canary_at    := now(); END IF;
    IF NEW.state_cd = 'ACTIVE' AND NEW.activated_at IS NULL THEN NEW.activated_at := now(); END IF;

    RETURN NEW;
END;
$function$
;
CREATE OR REPLACE FUNCTION ax.fn_resolve_serving_profile(p_user_id common.d_user_id, p_service_cd character varying DEFAULT 'CHAT'::character varying)
 RETURNS integer
 LANGUAGE sql
 STABLE
AS $function$
    WITH me AS (
        SELECT u.user_id, u.dept_id
          FROM ax.tb_sys_user u
         WHERE u.user_id = p_user_id AND u.user_state_cd = 'ACTIVE'
    ),
    cand AS (
        SELECT r.profile_id,
               r.weight_pct,
               CASE r.scope_cd WHEN 'USER' THEN 1 WHEN 'DEPT' THEN 2 ELSE 3 END AS prio
          FROM ax.tb_ai_serving_route r
         CROSS JOIN me
          JOIN ax.tb_ai_serving_profile p
               ON p.profile_id = r.profile_id
              AND p.state_cd IN ('ACTIVE','CANARY')
         WHERE r.use_flg = 'Y'
           AND r.service_cd = p_service_cd
           AND (r.valid_from IS NULL OR r.valid_from <= now())
           AND (r.valid_to   IS NULL OR r.valid_to   >= now())
           AND (   (r.scope_cd = 'USER'   AND r.user_id = me.user_id)
                OR (r.scope_cd = 'DEPT'   AND r.dept_id = me.dept_id)
                OR (r.scope_cd = 'GLOBAL'))
    ),
    win AS (   -- 가장 좁은 범위만 남긴다
        SELECT c.* FROM cand c
         WHERE c.prio = (SELECT min(c2.prio) FROM cand c2)
    ),
    dist AS (  -- weight_pct 누적 구간을 만든다
        SELECT w.profile_id,
               sum(w.weight_pct) OVER (ORDER BY w.profile_id
                                       ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS cum,
               sum(w.weight_pct) OVER ()                                                  AS total
          FROM win w
    )
    SELECT coalesce(
        (SELECT d.profile_id FROM dist d
          WHERE d.cum > (('x' || substr(md5(p_user_id), 1, 7))::bit(28)::int % greatest(d.total, 1))
          ORDER BY d.cum
          LIMIT 1),
        (SELECT p.profile_id FROM ax.tb_ai_serving_profile p
          WHERE p.service_cd = p_service_cd AND p.state_cd = 'ACTIVE')
    );
$function$
;
CREATE TRIGGER trg_guard_serving_state BEFORE INSERT OR UPDATE OF state_cd ON ax.tb_ai_serving_profile FOR EACH ROW EXECUTE FUNCTION ax.fn_guard_serving_state();
COMMENT ON FUNCTION ax.fn_guard_serving_state() IS '서빙 프로필을 CANARY/ACTIVE 로 올릴 때 필수 자산·자산 상태·코퍼스 준비 여부를 검사한다. 화면을 우회한 변경도 막는다';
COMMENT ON FUNCTION ax.fn_resolve_serving_profile(common.d_user_id,character varying) IS '사용자에게 적용할 서빙 프로필(버전)을 판정한다. USER > DEPT > GLOBAL 순이며 동순위는 weight_pct 로 결정론적 배분. 라우팅이 없으면 ACTIVE 프로필을 반환한다';
\else
\echo '구조는 이미 복원돼 있어 건너뜁니다.'
\endif

-- ── 4. 공통코드 그룹·코드·코드 참조 복원 ────────────────────────────────────────────
INSERT INTO ax.tb_sys_code_group (group_cd, group_nm, group_desc, use_flg, sort_seq) VALUES ('AI_ASSET_KIND', '모델 자산 종류', NULL, 'Y', 76) ON CONFLICT (group_cd) DO NOTHING;
INSERT INTO ax.tb_sys_code_group (group_cd, group_nm, group_desc, use_flg, sort_seq) VALUES ('AI_ASSET_STATE', '모델 자산 상태', NULL, 'Y', 77) ON CONFLICT (group_cd) DO NOTHING;
INSERT INTO ax.tb_sys_code_group (group_cd, group_nm, group_desc, use_flg, sort_seq) VALUES ('AI_CORPUS_STATE', '코퍼스 스냅샷 상태', NULL, 'Y', 78) ON CONFLICT (group_cd) DO NOTHING;
INSERT INTO ax.tb_sys_code_group (group_cd, group_nm, group_desc, use_flg, sort_seq) VALUES ('AI_INDEX_METHOD', '벡터 색인 방식', NULL, 'Y', 79) ON CONFLICT (group_cd) DO NOTHING;
INSERT INTO ax.tb_sys_code_group (group_cd, group_nm, group_desc, use_flg, sort_seq) VALUES ('AI_ROUTE_SCOPE', '라우팅 적용 범위', NULL, 'Y', 82) ON CONFLICT (group_cd) DO NOTHING;
INSERT INTO ax.tb_sys_code_group (group_cd, group_nm, group_desc, use_flg, sort_seq) VALUES ('AI_SERVING_ROLE', '서빙 자산 역할', NULL, 'Y', 81) ON CONFLICT (group_cd) DO NOTHING;
INSERT INTO ax.tb_sys_code_group (group_cd, group_nm, group_desc, use_flg, sort_seq) VALUES ('AOI_IMG_SOURCE', 'AOI 사진 원천', 'ax.tb_aoi_defect_image.source_cd — 판정 키가 어느 원천의 것인지', 'Y', 97) ON CONFLICT (group_cd) DO NOTHING;
INSERT INTO ax.tb_sys_code_group (group_cd, group_nm, group_desc, use_flg, sort_seq) VALUES ('UNMASK_STATE', '마스킹 해제 요청 상태', NULL, 'Y', 99) ON CONFLICT (group_cd) DO NOTHING;
INSERT INTO ax.tb_sys_code (group_cd, code, code_nm, code_desc, attr1, attr2, sort_seq, use_flg) VALUES ('AI_ASSET_KIND', 'LLM_BASE', '베이스 모델', '파인튜닝하지 않은 원본 가중치', NULL, NULL, 1, 'Y') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_code (group_cd, code, code_nm, code_desc, attr1, attr2, sort_seq, use_flg) VALUES ('AI_ASSET_KIND', 'LORA', 'LoRA 어댑터', '파인튜닝 산출물. 베이스와 함께 로드한다', NULL, NULL, 2, 'Y') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_code (group_cd, code, code_nm, code_desc, attr1, attr2, sort_seq, use_flg) VALUES ('AI_ASSET_KIND', 'EMBED', '임베딩 모델', '벡터화 모델. 차원이 코퍼스와 일치해야 한다', NULL, NULL, 3, 'Y') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_code (group_cd, code, code_nm, code_desc, attr1, attr2, sort_seq, use_flg) VALUES ('AI_ASSET_KIND', 'RERANK', '리랭커', '검색 후보 재정렬 모델', NULL, NULL, 4, 'Y') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_code (group_cd, code, code_nm, code_desc, attr1, attr2, sort_seq, use_flg) VALUES ('AI_ASSET_STATE', 'REGISTERED', '등록', '경로만 등록된 상태', NULL, NULL, 1, 'Y') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_code (group_cd, code, code_nm, code_desc, attr1, attr2, sort_seq, use_flg) VALUES ('AI_ASSET_STATE', 'VERIFIED', '검증 완료', '경로·체크섬 확인됨', NULL, NULL, 2, 'Y') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_code (group_cd, code, code_nm, code_desc, attr1, attr2, sort_seq, use_flg) VALUES ('AI_ASSET_STATE', 'MISSING', '파일 없음', '경로에 아티팩트가 없다 — 배포 불가', NULL, NULL, 3, 'Y') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_code (group_cd, code, code_nm, code_desc, attr1, attr2, sort_seq, use_flg) VALUES ('AI_ASSET_STATE', 'RETIRED', '폐기', '더 이상 사용하지 않음', NULL, NULL, 4, 'Y') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_code (group_cd, code, code_nm, code_desc, attr1, attr2, sort_seq, use_flg) VALUES ('AI_CORPUS_STATE', 'BUILDING', '구축 중', '임베딩·색인 진행 중', NULL, NULL, 1, 'Y') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_code (group_cd, code, code_nm, code_desc, attr1, attr2, sort_seq, use_flg) VALUES ('AI_CORPUS_STATE', 'READY', '사용 가능', '활성화 가능한 상태', NULL, NULL, 2, 'Y') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_code (group_cd, code, code_nm, code_desc, attr1, attr2, sort_seq, use_flg) VALUES ('AI_CORPUS_STATE', 'FAILED', '실패', '구축 실패', NULL, NULL, 3, 'Y') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_code (group_cd, code, code_nm, code_desc, attr1, attr2, sort_seq, use_flg) VALUES ('AI_CORPUS_STATE', 'RETIRED', '폐기', '보존 만료 또는 대체됨', NULL, NULL, 4, 'Y') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_code (group_cd, code, code_nm, code_desc, attr1, attr2, sort_seq, use_flg) VALUES ('AI_INDEX_METHOD', 'HNSW', 'HNSW', NULL, NULL, NULL, 1, 'Y') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_code (group_cd, code, code_nm, code_desc, attr1, attr2, sort_seq, use_flg) VALUES ('AI_INDEX_METHOD', 'IVFFLAT', 'IVFFlat', NULL, NULL, NULL, 2, 'Y') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_code (group_cd, code, code_nm, code_desc, attr1, attr2, sort_seq, use_flg) VALUES ('AI_INDEX_METHOD', 'NONE', '색인 없음', '순차 스캔 (소규모 코퍼스)', NULL, NULL, 3, 'Y') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_code (group_cd, code, code_nm, code_desc, attr1, attr2, sort_seq, use_flg) VALUES ('AI_ROUTE_SCOPE', 'GLOBAL', '전체', '기본 적용 대상', NULL, NULL, 1, 'Y') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_code (group_cd, code, code_nm, code_desc, attr1, attr2, sort_seq, use_flg) VALUES ('AI_ROUTE_SCOPE', 'DEPT', '부서', '부서 단위 지정', NULL, NULL, 2, 'Y') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_code (group_cd, code, code_nm, code_desc, attr1, attr2, sort_seq, use_flg) VALUES ('AI_ROUTE_SCOPE', 'USER', '계정', '계정 단위 지정. 가장 우선한다', NULL, NULL, 3, 'Y') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_code (group_cd, code, code_nm, code_desc, attr1, attr2, sort_seq, use_flg) VALUES ('AI_SERVING_ROLE', 'LLM_BASE', 'LLM 베이스', '필수', NULL, NULL, 1, 'Y') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_code (group_cd, code, code_nm, code_desc, attr1, attr2, sort_seq, use_flg) VALUES ('AI_SERVING_ROLE', 'LORA_ASSISTANT', 'LoRA 질의이해·도구호출', NULL, NULL, NULL, 2, 'Y') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_code (group_cd, code, code_nm, code_desc, attr1, attr2, sort_seq, use_flg) VALUES ('AI_SERVING_ROLE', 'LORA_REPORT', 'LoRA 서술·보고서', NULL, NULL, NULL, 3, 'Y') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_code (group_cd, code, code_nm, code_desc, attr1, attr2, sort_seq, use_flg) VALUES ('AI_SERVING_ROLE', 'LORA_REPORT_THINK', 'LoRA 원인분석 thinking', NULL, NULL, NULL, 4, 'Y') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_code (group_cd, code, code_nm, code_desc, attr1, attr2, sort_seq, use_flg) VALUES ('AI_SERVING_ROLE', 'EMBED', '임베딩 모델', '필수', NULL, NULL, 5, 'Y') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_code (group_cd, code, code_nm, code_desc, attr1, attr2, sort_seq, use_flg) VALUES ('AI_SERVING_ROLE', 'RERANK', '리랭커', NULL, NULL, NULL, 6, 'Y') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_code (group_cd, code, code_nm, code_desc, attr1, attr2, sort_seq, use_flg) VALUES ('AOI_IMG_SOURCE', 'LABEL', 'MES 라벨 이력', 'mes.tb_pop_label_hist 키 (plant·wc·lot·serial)', NULL, NULL, 1, 'Y') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_code (group_cd, code, code_nm, code_desc, attr1, attr2, sort_seq, use_flg) VALUES ('AOI_IMG_SOURCE', 'DIMENSION', 'MSSQL 치수검사', 'EDGE.dbo.TB_SAMSUN_DIMENSION 키 (wc·eqpt·lot·serial)', NULL, NULL, 2, 'Y') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_code (group_cd, code, code_nm, code_desc, attr1, attr2, sort_seq, use_flg) VALUES ('UNMASK_STATE', 'REQUESTED', '요청', NULL, NULL, NULL, 1, 'Y') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_code (group_cd, code, code_nm, code_desc, attr1, attr2, sort_seq, use_flg) VALUES ('UNMASK_STATE', 'APPROVED', '승인', NULL, NULL, NULL, 2, 'Y') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_code (group_cd, code, code_nm, code_desc, attr1, attr2, sort_seq, use_flg) VALUES ('UNMASK_STATE', 'REJECTED', '반려', NULL, NULL, NULL, 3, 'Y') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_code_ref (target_table, target_column, group_cd, nullable_flg) VALUES ('tb_ai_corpus_snapshot', 'index_method_cd', 'AI_INDEX_METHOD', 'N') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_code_ref (target_table, target_column, group_cd, nullable_flg) VALUES ('tb_ai_corpus_snapshot', 'state_cd', 'AI_CORPUS_STATE', 'N') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_code_ref (target_table, target_column, group_cd, nullable_flg) VALUES ('tb_ai_model_asset', 'asset_kind_cd', 'AI_ASSET_KIND', 'N') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_code_ref (target_table, target_column, group_cd, nullable_flg) VALUES ('tb_ai_model_asset', 'state_cd', 'AI_ASSET_STATE', 'N') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_code_ref (target_table, target_column, group_cd, nullable_flg) VALUES ('tb_ai_serving_asset', 'role_cd', 'AI_SERVING_ROLE', 'N') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_code_ref (target_table, target_column, group_cd, nullable_flg) VALUES ('tb_ai_serving_route', 'scope_cd', 'AI_ROUTE_SCOPE', 'N') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_code_ref (target_table, target_column, group_cd, nullable_flg) VALUES ('tb_ai_serving_route', 'service_cd', 'AI_SERVICE', 'N') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_code_ref (target_table, target_column, group_cd, nullable_flg) VALUES ('tb_aoi_defect_image', 'source_cd', 'AOI_IMG_SOURCE', 'N') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_code_ref (target_table, target_column, group_cd, nullable_flg) VALUES ('tb_rpt_unmask_req', 'state_cd', 'UNMASK_STATE', 'N') ON CONFLICT DO NOTHING;

INSERT INTO ax.tb_sys_code_group (group_cd, group_nm, group_desc, use_flg, sort_seq) VALUES ('PRICE_KIND', '단가 구분', NULL, 'Y', 96) ON CONFLICT (group_cd) DO NOTHING;
INSERT INTO ax.tb_sys_code (group_cd, code, code_nm, code_desc, attr1, attr2, sort_seq, use_flg) VALUES ('PRICE_KIND', 'COST', '원가', NULL, NULL, NULL, 1, 'Y') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_code (group_cd, code, code_nm, code_desc, attr1, attr2, sort_seq, use_flg) VALUES ('PRICE_KIND', 'PROCESS', '가공비', NULL, NULL, NULL, 2, 'Y') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_code (group_cd, code, code_nm, code_desc, attr1, attr2, sort_seq, use_flg) VALUES ('PRICE_KIND', 'SALE', '판매가', NULL, NULL, NULL, 3, 'Y') ON CONFLICT DO NOTHING;
--  아래 한 행은 V38 이 남긴 끊긴 참조다(tb_prod_item_price 는 V38 에서 이미 지워졌다). 원상 그대로 되살린다.
INSERT INTO ax.tb_sys_code_ref (target_table, target_column, group_cd, nullable_flg) VALUES ('tb_prod_item_price', 'price_kind_cd', 'PRICE_KIND', 'N') ON CONFLICT DO NOTHING;

-- ── 5. 메뉴 그룹 복원 (production · quality 되살림) ─────────────────────────────────
INSERT INTO ax.tb_sys_menu_group (group_id, group_nm, is_solo, sort_seq, use_flg) VALUES ('assistant', 'AI 어시스턴트', 't', 1, 'Y') ON CONFLICT (group_id) DO UPDATE SET group_nm=EXCLUDED.group_nm, is_solo=EXCLUDED.is_solo, sort_seq=EXCLUDED.sort_seq, use_flg=EXCLUDED.use_flg;
INSERT INTO ax.tb_sys_menu_group (group_id, group_nm, is_solo, sort_seq, use_flg) VALUES ('dashboard', '대시보드', 'f', 2, 'Y') ON CONFLICT (group_id) DO UPDATE SET group_nm=EXCLUDED.group_nm, is_solo=EXCLUDED.is_solo, sort_seq=EXCLUDED.sort_seq, use_flg=EXCLUDED.use_flg;
INSERT INTO ax.tb_sys_menu_group (group_id, group_nm, is_solo, sort_seq, use_flg) VALUES ('production', '생산관리', 'f', 3, 'Y') ON CONFLICT (group_id) DO UPDATE SET group_nm=EXCLUDED.group_nm, is_solo=EXCLUDED.is_solo, sort_seq=EXCLUDED.sort_seq, use_flg=EXCLUDED.use_flg;
INSERT INTO ax.tb_sys_menu_group (group_id, group_nm, is_solo, sort_seq, use_flg) VALUES ('quality', '품질관리', 'f', 4, 'Y') ON CONFLICT (group_id) DO UPDATE SET group_nm=EXCLUDED.group_nm, is_solo=EXCLUDED.is_solo, sort_seq=EXCLUDED.sort_seq, use_flg=EXCLUDED.use_flg;
INSERT INTO ax.tb_sys_menu_group (group_id, group_nm, is_solo, sort_seq, use_flg) VALUES ('report', '보고서', 'f', 5, 'Y') ON CONFLICT (group_id) DO UPDATE SET group_nm=EXCLUDED.group_nm, is_solo=EXCLUDED.is_solo, sort_seq=EXCLUDED.sort_seq, use_flg=EXCLUDED.use_flg;
INSERT INTO ax.tb_sys_menu_group (group_id, group_nm, is_solo, sort_seq, use_flg) VALUES ('alert', '이상 알림', 'f', 6, 'Y') ON CONFLICT (group_id) DO UPDATE SET group_nm=EXCLUDED.group_nm, is_solo=EXCLUDED.is_solo, sort_seq=EXCLUDED.sort_seq, use_flg=EXCLUDED.use_flg;
INSERT INTO ax.tb_sys_menu_group (group_id, group_nm, is_solo, sort_seq, use_flg) VALUES ('system', '시스템관리', 'f', 7, 'Y') ON CONFLICT (group_id) DO UPDATE SET group_nm=EXCLUDED.group_nm, is_solo=EXCLUDED.is_solo, sort_seq=EXCLUDED.sort_seq, use_flg=EXCLUDED.use_flg;

-- ── 6. 메뉴 복원 (V42 직전 값 — 숨김 메뉴 10 포함, 부모가 먼저) ──────────────────────
--  dash-kpi 는 운영 값('Y')으로 되살린다. 로컬은 손으로 'N' 으로 바꿔 두었던 것이라 운영과 달랐다.
INSERT INTO ax.tb_sys_menu (menu_id, menu_nm, group_id, parent_menu_id, is_sub_page, route_path, tag_cd, sort_seq, use_flg) VALUES ('alert-list', '알림 목록·상세', 'alert', NULL, 'f', '#alert-list', 'NEW', 1, 'Y') ON CONFLICT (menu_id) DO UPDATE SET menu_nm=EXCLUDED.menu_nm, group_id=EXCLUDED.group_id, parent_menu_id=EXCLUDED.parent_menu_id, is_sub_page=EXCLUDED.is_sub_page, route_path=EXCLUDED.route_path, tag_cd=EXCLUDED.tag_cd, sort_seq=EXCLUDED.sort_seq, use_flg=EXCLUDED.use_flg;
INSERT INTO ax.tb_sys_menu (menu_id, menu_nm, group_id, parent_menu_id, is_sub_page, route_path, tag_cd, sort_seq, use_flg) VALUES ('ai-chat', '덕파트장 AI', 'assistant', NULL, 'f', '#ai-chat', 'NEW', 1, 'Y') ON CONFLICT (menu_id) DO UPDATE SET menu_nm=EXCLUDED.menu_nm, group_id=EXCLUDED.group_id, parent_menu_id=EXCLUDED.parent_menu_id, is_sub_page=EXCLUDED.is_sub_page, route_path=EXCLUDED.route_path, tag_cd=EXCLUDED.tag_cd, sort_seq=EXCLUDED.sort_seq, use_flg=EXCLUDED.use_flg;
INSERT INTO ax.tb_sys_menu (menu_id, menu_nm, group_id, parent_menu_id, is_sub_page, route_path, tag_cd, sort_seq, use_flg) VALUES ('dash-ai', 'AI 통합 대시보드', 'dashboard', NULL, 'f', '#dash-ai', 'NEW', 1, 'Y') ON CONFLICT (menu_id) DO UPDATE SET menu_nm=EXCLUDED.menu_nm, group_id=EXCLUDED.group_id, parent_menu_id=EXCLUDED.parent_menu_id, is_sub_page=EXCLUDED.is_sub_page, route_path=EXCLUDED.route_path, tag_cd=EXCLUDED.tag_cd, sort_seq=EXCLUDED.sort_seq, use_flg=EXCLUDED.use_flg;
INSERT INTO ax.tb_sys_menu (menu_id, menu_nm, group_id, parent_menu_id, is_sub_page, route_path, tag_cd, sort_seq, use_flg) VALUES ('dash-proc', '공정 및 제품 대시보드', 'dashboard', NULL, 'f', '#dash-proc', 'NEW', 2, 'Y') ON CONFLICT (menu_id) DO UPDATE SET menu_nm=EXCLUDED.menu_nm, group_id=EXCLUDED.group_id, parent_menu_id=EXCLUDED.parent_menu_id, is_sub_page=EXCLUDED.is_sub_page, route_path=EXCLUDED.route_path, tag_cd=EXCLUDED.tag_cd, sort_seq=EXCLUDED.sort_seq, use_flg=EXCLUDED.use_flg;
INSERT INTO ax.tb_sys_menu (menu_id, menu_nm, group_id, parent_menu_id, is_sub_page, route_path, tag_cd, sort_seq, use_flg) VALUES ('dash-kpi', '성과지표 대시보드', 'dashboard', NULL, 'f', '#dash-kpi', 'NEW', 3, 'Y') ON CONFLICT (menu_id) DO UPDATE SET menu_nm=EXCLUDED.menu_nm, group_id=EXCLUDED.group_id, parent_menu_id=EXCLUDED.parent_menu_id, is_sub_page=EXCLUDED.is_sub_page, route_path=EXCLUDED.route_path, tag_cd=EXCLUDED.tag_cd, sort_seq=EXCLUDED.sort_seq, use_flg=EXCLUDED.use_flg;
INSERT INTO ax.tb_sys_menu (menu_id, menu_nm, group_id, parent_menu_id, is_sub_page, route_path, tag_cd, sort_seq, use_flg) VALUES ('prod-monitor', '생산 모니터링', 'production', NULL, 'f', '#prod-monitor', 'MOD', 1, 'Y') ON CONFLICT (menu_id) DO UPDATE SET menu_nm=EXCLUDED.menu_nm, group_id=EXCLUDED.group_id, parent_menu_id=EXCLUDED.parent_menu_id, is_sub_page=EXCLUDED.is_sub_page, route_path=EXCLUDED.route_path, tag_cd=EXCLUDED.tag_cd, sort_seq=EXCLUDED.sort_seq, use_flg=EXCLUDED.use_flg;
INSERT INTO ax.tb_sys_menu (menu_id, menu_nm, group_id, parent_menu_id, is_sub_page, route_path, tag_cd, sort_seq, use_flg) VALUES ('prod-result', '실적 집계·조회', 'production', NULL, 'f', '#prod-result', 'MOD', 2, 'Y') ON CONFLICT (menu_id) DO UPDATE SET menu_nm=EXCLUDED.menu_nm, group_id=EXCLUDED.group_id, parent_menu_id=EXCLUDED.parent_menu_id, is_sub_page=EXCLUDED.is_sub_page, route_path=EXCLUDED.route_path, tag_cd=EXCLUDED.tag_cd, sort_seq=EXCLUDED.sort_seq, use_flg=EXCLUDED.use_flg;
INSERT INTO ax.tb_sys_menu (menu_id, menu_nm, group_id, parent_menu_id, is_sub_page, route_path, tag_cd, sort_seq, use_flg) VALUES ('prod-daily', '일일 생산현황 보고', 'production', NULL, 'f', '#prod-daily', 'NEW', 3, 'Y') ON CONFLICT (menu_id) DO UPDATE SET menu_nm=EXCLUDED.menu_nm, group_id=EXCLUDED.group_id, parent_menu_id=EXCLUDED.parent_menu_id, is_sub_page=EXCLUDED.is_sub_page, route_path=EXCLUDED.route_path, tag_cd=EXCLUDED.tag_cd, sort_seq=EXCLUDED.sort_seq, use_flg=EXCLUDED.use_flg;
INSERT INTO ax.tb_sys_menu (menu_id, menu_nm, group_id, parent_menu_id, is_sub_page, route_path, tag_cd, sort_seq, use_flg) VALUES ('prod-down', '비가동 관리', 'production', NULL, 'f', '#prod-down', 'MOD', 4, 'N') ON CONFLICT (menu_id) DO UPDATE SET menu_nm=EXCLUDED.menu_nm, group_id=EXCLUDED.group_id, parent_menu_id=EXCLUDED.parent_menu_id, is_sub_page=EXCLUDED.is_sub_page, route_path=EXCLUDED.route_path, tag_cd=EXCLUDED.tag_cd, sort_seq=EXCLUDED.sort_seq, use_flg=EXCLUDED.use_flg;
INSERT INTO ax.tb_sys_menu (menu_id, menu_nm, group_id, parent_menu_id, is_sub_page, route_path, tag_cd, sort_seq, use_flg) VALUES ('qc-defect', '불량 현황 조회', 'quality', NULL, 'f', '#qc-defect', 'NEW', 1, 'Y') ON CONFLICT (menu_id) DO UPDATE SET menu_nm=EXCLUDED.menu_nm, group_id=EXCLUDED.group_id, parent_menu_id=EXCLUDED.parent_menu_id, is_sub_page=EXCLUDED.is_sub_page, route_path=EXCLUDED.route_path, tag_cd=EXCLUDED.tag_cd, sort_seq=EXCLUDED.sort_seq, use_flg=EXCLUDED.use_flg;
INSERT INTO ax.tb_sys_menu (menu_id, menu_nm, group_id, parent_menu_id, is_sub_page, route_path, tag_cd, sort_seq, use_flg) VALUES ('qc-aoi', 'AOI 판정 분석', 'quality', NULL, 'f', '#qc-aoi', 'NEW', 2, 'Y') ON CONFLICT (menu_id) DO UPDATE SET menu_nm=EXCLUDED.menu_nm, group_id=EXCLUDED.group_id, parent_menu_id=EXCLUDED.parent_menu_id, is_sub_page=EXCLUDED.is_sub_page, route_path=EXCLUDED.route_path, tag_cd=EXCLUDED.tag_cd, sort_seq=EXCLUDED.sort_seq, use_flg=EXCLUDED.use_flg;
INSERT INTO ax.tb_sys_menu (menu_id, menu_nm, group_id, parent_menu_id, is_sub_page, route_path, tag_cd, sort_seq, use_flg) VALUES ('rpt-press-morning', '아침회의 자료 (PRESS)', 'report', NULL, 'f', '#rpt-press-morning', 'NEW', 1, 'Y') ON CONFLICT (menu_id) DO UPDATE SET menu_nm=EXCLUDED.menu_nm, group_id=EXCLUDED.group_id, parent_menu_id=EXCLUDED.parent_menu_id, is_sub_page=EXCLUDED.is_sub_page, route_path=EXCLUDED.route_path, tag_cd=EXCLUDED.tag_cd, sort_seq=EXCLUDED.sort_seq, use_flg=EXCLUDED.use_flg;
INSERT INTO ax.tb_sys_menu (menu_id, menu_nm, group_id, parent_menu_id, is_sub_page, route_path, tag_cd, sort_seq, use_flg) VALUES ('rpt-plating-morning', '아침회의 자료 (Plating·Coating)', 'report', NULL, 'f', '#rpt-plating-morning', 'NEW', 2, 'Y') ON CONFLICT (menu_id) DO UPDATE SET menu_nm=EXCLUDED.menu_nm, group_id=EXCLUDED.group_id, parent_menu_id=EXCLUDED.parent_menu_id, is_sub_page=EXCLUDED.is_sub_page, route_path=EXCLUDED.route_path, tag_cd=EXCLUDED.tag_cd, sort_seq=EXCLUDED.sort_seq, use_flg=EXCLUDED.use_flg;
INSERT INTO ax.tb_sys_menu (menu_id, menu_nm, group_id, parent_menu_id, is_sub_page, route_path, tag_cd, sort_seq, use_flg) VALUES ('rpt-ship-plan', '연간 출하계획', 'report', NULL, 'f', '#rpt-ship-plan', 'NEW', 3, 'Y') ON CONFLICT (menu_id) DO UPDATE SET menu_nm=EXCLUDED.menu_nm, group_id=EXCLUDED.group_id, parent_menu_id=EXCLUDED.parent_menu_id, is_sub_page=EXCLUDED.is_sub_page, route_path=EXCLUDED.route_path, tag_cd=EXCLUDED.tag_cd, sort_seq=EXCLUDED.sort_seq, use_flg=EXCLUDED.use_flg;
INSERT INTO ax.tb_sys_menu (menu_id, menu_nm, group_id, parent_menu_id, is_sub_page, route_path, tag_cd, sort_seq, use_flg) VALUES ('rpt-yield-model', '제품별 수율', 'report', NULL, 'f', '#rpt-yield-model', 'NEW', 4, 'Y') ON CONFLICT (menu_id) DO UPDATE SET menu_nm=EXCLUDED.menu_nm, group_id=EXCLUDED.group_id, parent_menu_id=EXCLUDED.parent_menu_id, is_sub_page=EXCLUDED.is_sub_page, route_path=EXCLUDED.route_path, tag_cd=EXCLUDED.tag_cd, sort_seq=EXCLUDED.sort_seq, use_flg=EXCLUDED.use_flg;
INSERT INTO ax.tb_sys_menu (menu_id, menu_nm, group_id, parent_menu_id, is_sub_page, route_path, tag_cd, sort_seq, use_flg) VALUES ('rpt-lrr-customer', '고객사별 LRR', 'report', NULL, 'f', '#rpt-lrr-customer', 'NEW', 5, 'Y') ON CONFLICT (menu_id) DO UPDATE SET menu_nm=EXCLUDED.menu_nm, group_id=EXCLUDED.group_id, parent_menu_id=EXCLUDED.parent_menu_id, is_sub_page=EXCLUDED.is_sub_page, route_path=EXCLUDED.route_path, tag_cd=EXCLUDED.tag_cd, sort_seq=EXCLUDED.sort_seq, use_flg=EXCLUDED.use_flg;
INSERT INTO ax.tb_sys_menu (menu_id, menu_nm, group_id, parent_menu_id, is_sub_page, route_path, tag_cd, sort_seq, use_flg) VALUES ('rpt-scrap', '폐기 보고서', 'report', NULL, 'f', '#rpt-scrap', 'NEW', 6, 'Y') ON CONFLICT (menu_id) DO UPDATE SET menu_nm=EXCLUDED.menu_nm, group_id=EXCLUDED.group_id, parent_menu_id=EXCLUDED.parent_menu_id, is_sub_page=EXCLUDED.is_sub_page, route_path=EXCLUDED.route_path, tag_cd=EXCLUDED.tag_cd, sort_seq=EXCLUDED.sort_seq, use_flg=EXCLUDED.use_flg;
INSERT INTO ax.tb_sys_menu (menu_id, menu_nm, group_id, parent_menu_id, is_sub_page, route_path, tag_cd, sort_seq, use_flg) VALUES ('sys-account', '계정 관리', 'system', NULL, 'f', '#sys-account', 'NEW', 1, 'Y') ON CONFLICT (menu_id) DO UPDATE SET menu_nm=EXCLUDED.menu_nm, group_id=EXCLUDED.group_id, parent_menu_id=EXCLUDED.parent_menu_id, is_sub_page=EXCLUDED.is_sub_page, route_path=EXCLUDED.route_path, tag_cd=EXCLUDED.tag_cd, sort_seq=EXCLUDED.sort_seq, use_flg=EXCLUDED.use_flg;
INSERT INTO ax.tb_sys_menu (menu_id, menu_nm, group_id, parent_menu_id, is_sub_page, route_path, tag_cd, sort_seq, use_flg) VALUES ('sys-menu', '메뉴 접근 권한', 'system', NULL, 'f', '#sys-menu', 'NEW', 2, 'Y') ON CONFLICT (menu_id) DO UPDATE SET menu_nm=EXCLUDED.menu_nm, group_id=EXCLUDED.group_id, parent_menu_id=EXCLUDED.parent_menu_id, is_sub_page=EXCLUDED.is_sub_page, route_path=EXCLUDED.route_path, tag_cd=EXCLUDED.tag_cd, sort_seq=EXCLUDED.sort_seq, use_flg=EXCLUDED.use_flg;
INSERT INTO ax.tb_sys_menu (menu_id, menu_nm, group_id, parent_menu_id, is_sub_page, route_path, tag_cd, sort_seq, use_flg) VALUES ('sys-data', '데이터 접근 권한', 'system', NULL, 'f', '#sys-data', 'NEW', 3, 'Y') ON CONFLICT (menu_id) DO UPDATE SET menu_nm=EXCLUDED.menu_nm, group_id=EXCLUDED.group_id, parent_menu_id=EXCLUDED.parent_menu_id, is_sub_page=EXCLUDED.is_sub_page, route_path=EXCLUDED.route_path, tag_cd=EXCLUDED.tag_cd, sort_seq=EXCLUDED.sort_seq, use_flg=EXCLUDED.use_flg;
INSERT INTO ax.tb_sys_menu (menu_id, menu_nm, group_id, parent_menu_id, is_sub_page, route_path, tag_cd, sort_seq, use_flg) VALUES ('alert-cond', '이상 알림 발송 조건 관리', 'system', NULL, 'f', '#alert-cond', 'NEW', 4, 'Y') ON CONFLICT (menu_id) DO UPDATE SET menu_nm=EXCLUDED.menu_nm, group_id=EXCLUDED.group_id, parent_menu_id=EXCLUDED.parent_menu_id, is_sub_page=EXCLUDED.is_sub_page, route_path=EXCLUDED.route_path, tag_cd=EXCLUDED.tag_cd, sort_seq=EXCLUDED.sort_seq, use_flg=EXCLUDED.use_flg;
INSERT INTO ax.tb_sys_menu (menu_id, menu_nm, group_id, parent_menu_id, is_sub_page, route_path, tag_cd, sort_seq, use_flg) VALUES ('sys-recip', '알림 수신자 관리', 'system', NULL, 'f', '#sys-recip', 'NEW', 5, 'Y') ON CONFLICT (menu_id) DO UPDATE SET menu_nm=EXCLUDED.menu_nm, group_id=EXCLUDED.group_id, parent_menu_id=EXCLUDED.parent_menu_id, is_sub_page=EXCLUDED.is_sub_page, route_path=EXCLUDED.route_path, tag_cd=EXCLUDED.tag_cd, sort_seq=EXCLUDED.sort_seq, use_flg=EXCLUDED.use_flg;
INSERT INTO ax.tb_sys_menu (menu_id, menu_nm, group_id, parent_menu_id, is_sub_page, route_path, tag_cd, sort_seq, use_flg) VALUES ('sys-gloss', '용어 사전 관리', 'system', NULL, 'f', '#sys-gloss', 'NEW', 6, 'Y') ON CONFLICT (menu_id) DO UPDATE SET menu_nm=EXCLUDED.menu_nm, group_id=EXCLUDED.group_id, parent_menu_id=EXCLUDED.parent_menu_id, is_sub_page=EXCLUDED.is_sub_page, route_path=EXCLUDED.route_path, tag_cd=EXCLUDED.tag_cd, sort_seq=EXCLUDED.sort_seq, use_flg=EXCLUDED.use_flg;
INSERT INTO ax.tb_sys_menu (menu_id, menu_nm, group_id, parent_menu_id, is_sub_page, route_path, tag_cd, sort_seq, use_flg) VALUES ('sys-rank', '제품군 순위 관리', 'system', NULL, 'f', '#sys-rank', 'NEW', 7, 'N') ON CONFLICT (menu_id) DO UPDATE SET menu_nm=EXCLUDED.menu_nm, group_id=EXCLUDED.group_id, parent_menu_id=EXCLUDED.parent_menu_id, is_sub_page=EXCLUDED.is_sub_page, route_path=EXCLUDED.route_path, tag_cd=EXCLUDED.tag_cd, sort_seq=EXCLUDED.sort_seq, use_flg=EXCLUDED.use_flg;
INSERT INTO ax.tb_sys_menu (menu_id, menu_nm, group_id, parent_menu_id, is_sub_page, route_path, tag_cd, sort_seq, use_flg) VALUES ('chat-history', '자연어 질의 이력', 'system', NULL, 'f', '#chat-history', 'NEW', 8, 'Y') ON CONFLICT (menu_id) DO UPDATE SET menu_nm=EXCLUDED.menu_nm, group_id=EXCLUDED.group_id, parent_menu_id=EXCLUDED.parent_menu_id, is_sub_page=EXCLUDED.is_sub_page, route_path=EXCLUDED.route_path, tag_cd=EXCLUDED.tag_cd, sort_seq=EXCLUDED.sort_seq, use_flg=EXCLUDED.use_flg;
INSERT INTO ax.tb_sys_menu (menu_id, menu_nm, group_id, parent_menu_id, is_sub_page, route_path, tag_cd, sort_seq, use_flg) VALUES ('sys-audit', '보안 감사 로그', 'system', NULL, 'f', '#sys-audit', 'NEW', 9, 'Y') ON CONFLICT (menu_id) DO UPDATE SET menu_nm=EXCLUDED.menu_nm, group_id=EXCLUDED.group_id, parent_menu_id=EXCLUDED.parent_menu_id, is_sub_page=EXCLUDED.is_sub_page, route_path=EXCLUDED.route_path, tag_cd=EXCLUDED.tag_cd, sort_seq=EXCLUDED.sort_seq, use_flg=EXCLUDED.use_flg;
INSERT INTO ax.tb_sys_menu (menu_id, menu_nm, group_id, parent_menu_id, is_sub_page, route_path, tag_cd, sort_seq, use_flg) VALUES ('base-model', 'AI 모델 설정', 'system', NULL, 'f', '#base-model', 'NEW', 10, 'N') ON CONFLICT (menu_id) DO UPDATE SET menu_nm=EXCLUDED.menu_nm, group_id=EXCLUDED.group_id, parent_menu_id=EXCLUDED.parent_menu_id, is_sub_page=EXCLUDED.is_sub_page, route_path=EXCLUDED.route_path, tag_cd=EXCLUDED.tag_cd, sort_seq=EXCLUDED.sort_seq, use_flg=EXCLUDED.use_flg;
INSERT INTO ax.tb_sys_menu (menu_id, menu_nm, group_id, parent_menu_id, is_sub_page, route_path, tag_cd, sort_seq, use_flg) VALUES ('ai-agent', 'Agent 실행 현황', 'system', NULL, 'f', '#ai-agent', 'NEW', 11, 'N') ON CONFLICT (menu_id) DO UPDATE SET menu_nm=EXCLUDED.menu_nm, group_id=EXCLUDED.group_id, parent_menu_id=EXCLUDED.parent_menu_id, is_sub_page=EXCLUDED.is_sub_page, route_path=EXCLUDED.route_path, tag_cd=EXCLUDED.tag_cd, sort_seq=EXCLUDED.sort_seq, use_flg=EXCLUDED.use_flg;
INSERT INTO ax.tb_sys_menu (menu_id, menu_nm, group_id, parent_menu_id, is_sub_page, route_path, tag_cd, sort_seq, use_flg) VALUES ('sys-metric', '지표 측정 데이터 관리', 'system', NULL, 'f', '#sys-metric', 'NEW', 12, 'N') ON CONFLICT (menu_id) DO UPDATE SET menu_nm=EXCLUDED.menu_nm, group_id=EXCLUDED.group_id, parent_menu_id=EXCLUDED.parent_menu_id, is_sub_page=EXCLUDED.is_sub_page, route_path=EXCLUDED.route_path, tag_cd=EXCLUDED.tag_cd, sort_seq=EXCLUDED.sort_seq, use_flg=EXCLUDED.use_flg;
INSERT INTO ax.tb_sys_menu (menu_id, menu_nm, group_id, parent_menu_id, is_sub_page, route_path, tag_cd, sort_seq, use_flg) VALUES ('sys-dl', '보고서 다운로드 이력', 'system', NULL, 'f', '#sys-dl', 'NEW', 13, 'Y') ON CONFLICT (menu_id) DO UPDATE SET menu_nm=EXCLUDED.menu_nm, group_id=EXCLUDED.group_id, parent_menu_id=EXCLUDED.parent_menu_id, is_sub_page=EXCLUDED.is_sub_page, route_path=EXCLUDED.route_path, tag_cd=EXCLUDED.tag_cd, sort_seq=EXCLUDED.sort_seq, use_flg=EXCLUDED.use_flg;
INSERT INTO ax.tb_sys_menu (menu_id, menu_nm, group_id, parent_menu_id, is_sub_page, route_path, tag_cd, sort_seq, use_flg) VALUES ('sys-sync', '데이터 연동 이력', 'system', NULL, 'f', '#sys-sync', 'REQ', 14, 'Y') ON CONFLICT (menu_id) DO UPDATE SET menu_nm=EXCLUDED.menu_nm, group_id=EXCLUDED.group_id, parent_menu_id=EXCLUDED.parent_menu_id, is_sub_page=EXCLUDED.is_sub_page, route_path=EXCLUDED.route_path, tag_cd=EXCLUDED.tag_cd, sort_seq=EXCLUDED.sort_seq, use_flg=EXCLUDED.use_flg;
INSERT INTO ax.tb_sys_menu (menu_id, menu_nm, group_id, parent_menu_id, is_sub_page, route_path, tag_cd, sort_seq, use_flg) VALUES ('sys-model-ver', 'AI 서비스 버전 관리', 'system', NULL, 'f', '#sys-model-ver', 'NEW', 15, 'N') ON CONFLICT (menu_id) DO UPDATE SET menu_nm=EXCLUDED.menu_nm, group_id=EXCLUDED.group_id, parent_menu_id=EXCLUDED.parent_menu_id, is_sub_page=EXCLUDED.is_sub_page, route_path=EXCLUDED.route_path, tag_cd=EXCLUDED.tag_cd, sort_seq=EXCLUDED.sort_seq, use_flg=EXCLUDED.use_flg;
INSERT INTO ax.tb_sys_menu (menu_id, menu_nm, group_id, parent_menu_id, is_sub_page, route_path, tag_cd, sort_seq, use_flg) VALUES ('sys-upload-doc', '업로드 문서 목록', 'system', NULL, 'f', '#sys-upload-doc', 'NEW', 16, 'Y') ON CONFLICT (menu_id) DO UPDATE SET menu_nm=EXCLUDED.menu_nm, group_id=EXCLUDED.group_id, parent_menu_id=EXCLUDED.parent_menu_id, is_sub_page=EXCLUDED.is_sub_page, route_path=EXCLUDED.route_path, tag_cd=EXCLUDED.tag_cd, sort_seq=EXCLUDED.sort_seq, use_flg=EXCLUDED.use_flg;
INSERT INTO ax.tb_sys_menu (menu_id, menu_nm, group_id, parent_menu_id, is_sub_page, route_path, tag_cd, sort_seq, use_flg) VALUES ('dash-ai-upload', '업로드 리포트 업로드', 'dashboard', 'dash-ai', 't', NULL, 'NEW', 90, 'Y') ON CONFLICT (menu_id) DO UPDATE SET menu_nm=EXCLUDED.menu_nm, group_id=EXCLUDED.group_id, parent_menu_id=EXCLUDED.parent_menu_id, is_sub_page=EXCLUDED.is_sub_page, route_path=EXCLUDED.route_path, tag_cd=EXCLUDED.tag_cd, sort_seq=EXCLUDED.sort_seq, use_flg=EXCLUDED.use_flg;
INSERT INTO ax.tb_sys_menu (menu_id, menu_nm, group_id, parent_menu_id, is_sub_page, route_path, tag_cd, sort_seq, use_flg) VALUES ('daily-history', '이전 보고서', 'production', 'prod-daily', 't', '#daily-history', NULL, 90, 'Y') ON CONFLICT (menu_id) DO UPDATE SET menu_nm=EXCLUDED.menu_nm, group_id=EXCLUDED.group_id, parent_menu_id=EXCLUDED.parent_menu_id, is_sub_page=EXCLUDED.is_sub_page, route_path=EXCLUDED.route_path, tag_cd=EXCLUDED.tag_cd, sort_seq=EXCLUDED.sort_seq, use_flg=EXCLUDED.use_flg;
INSERT INTO ax.tb_sys_menu (menu_id, menu_nm, group_id, parent_menu_id, is_sub_page, route_path, tag_cd, sort_seq, use_flg) VALUES ('qc-report', '품질 보고서', 'quality', 'qc-defect', 't', '#qc-report', NULL, 91, 'N') ON CONFLICT (menu_id) DO UPDATE SET menu_nm=EXCLUDED.menu_nm, group_id=EXCLUDED.group_id, parent_menu_id=EXCLUDED.parent_menu_id, is_sub_page=EXCLUDED.is_sub_page, route_path=EXCLUDED.route_path, tag_cd=EXCLUDED.tag_cd, sort_seq=EXCLUDED.sort_seq, use_flg=EXCLUDED.use_flg;
INSERT INTO ax.tb_sys_menu (menu_id, menu_nm, group_id, parent_menu_id, is_sub_page, route_path, tag_cd, sort_seq, use_flg) VALUES ('report-forms', '보고서 양식 관리', 'quality', 'qc-report', 't', '#report-forms', NULL, 92, 'N') ON CONFLICT (menu_id) DO UPDATE SET menu_nm=EXCLUDED.menu_nm, group_id=EXCLUDED.group_id, parent_menu_id=EXCLUDED.parent_menu_id, is_sub_page=EXCLUDED.is_sub_page, route_path=EXCLUDED.route_path, tag_cd=EXCLUDED.tag_cd, sort_seq=EXCLUDED.sort_seq, use_flg=EXCLUDED.use_flg;
INSERT INTO ax.tb_sys_menu (menu_id, menu_nm, group_id, parent_menu_id, is_sub_page, route_path, tag_cd, sort_seq, use_flg) VALUES ('rpt-scrap-new', '폐기 보고서 작성 위저드', 'report', 'rpt-scrap', 't', '#rpt-scrap-new', 'NEW', 7, 'N') ON CONFLICT (menu_id) DO UPDATE SET menu_nm=EXCLUDED.menu_nm, group_id=EXCLUDED.group_id, parent_menu_id=EXCLUDED.parent_menu_id, is_sub_page=EXCLUDED.is_sub_page, route_path=EXCLUDED.route_path, tag_cd=EXCLUDED.tag_cd, sort_seq=EXCLUDED.sort_seq, use_flg=EXCLUDED.use_flg;

-- ── 7. 숨김 메뉴의 부서 권한 · 보고서 정의 복원 ──────────────────────────────────────
INSERT INTO ax.tb_sys_dept_menu_perm (dept_id, menu_id, can_read, can_write, ins_user, upd_user) VALUES (1, 'ai-agent', 't', 'f', 'SEED', 'SEED') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_dept_menu_perm (dept_id, menu_id, can_read, can_write, ins_user, upd_user) VALUES (5, 'ai-agent', 't', 'f', 'SEED', 'SEED') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_dept_menu_perm (dept_id, menu_id, can_read, can_write, ins_user, upd_user) VALUES (1, 'base-model', 't', 'f', 'SEED', 'SEED') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_dept_menu_perm (dept_id, menu_id, can_read, can_write, ins_user, upd_user) VALUES (5, 'base-model', 't', 'f', 'SEED', 'SEED') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_dept_menu_perm (dept_id, menu_id, can_read, can_write, ins_user, upd_user) VALUES (1, 'dash-kpi', 't', 'f', 'SEED', 'SEED') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_dept_menu_perm (dept_id, menu_id, can_read, can_write, ins_user, upd_user) VALUES (2, 'dash-kpi', 't', 'f', 'SEED', 'SEED') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_dept_menu_perm (dept_id, menu_id, can_read, can_write, ins_user, upd_user) VALUES (3, 'dash-kpi', 't', 'f', 'SEED', 'SEED') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_dept_menu_perm (dept_id, menu_id, can_read, can_write, ins_user, upd_user) VALUES (5, 'dash-kpi', 't', 'f', 'SEED', 'SEED') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_dept_menu_perm (dept_id, menu_id, can_read, can_write, ins_user, upd_user) VALUES (6, 'dash-kpi', 't', 'f', 'SEED', 'SEED') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_dept_menu_perm (dept_id, menu_id, can_read, can_write, ins_user, upd_user) VALUES (1, 'prod-down', 't', 'f', 'SEED', 'SEED') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_dept_menu_perm (dept_id, menu_id, can_read, can_write, ins_user, upd_user) VALUES (3, 'prod-down', 't', 'f', 'SEED', 'SEED') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_dept_menu_perm (dept_id, menu_id, can_read, can_write, ins_user, upd_user) VALUES (4, 'prod-down', 't', 'f', 'SEED', 'SEED') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_dept_menu_perm (dept_id, menu_id, can_read, can_write, ins_user, upd_user) VALUES (1, 'qc-report', 't', 'f', 'SEED', 'SEED') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_dept_menu_perm (dept_id, menu_id, can_read, can_write, ins_user, upd_user) VALUES (2, 'qc-report', 't', 'f', 'SEED', 'SEED') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_dept_menu_perm (dept_id, menu_id, can_read, can_write, ins_user, upd_user) VALUES (3, 'qc-report', 't', 'f', 'SEED', 'SEED') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_dept_menu_perm (dept_id, menu_id, can_read, can_write, ins_user, upd_user) VALUES (1, 'report-forms', 't', 'f', 'SEED', 'SEED') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_dept_menu_perm (dept_id, menu_id, can_read, can_write, ins_user, upd_user) VALUES (2, 'report-forms', 't', 'f', 'SEED', 'SEED') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_dept_menu_perm (dept_id, menu_id, can_read, can_write, ins_user, upd_user) VALUES (3, 'report-forms', 't', 'f', 'SEED', 'SEED') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_dept_menu_perm (dept_id, menu_id, can_read, can_write, ins_user, upd_user) VALUES (1, 'rpt-scrap-new', 't', 'f', 'SEED', 'SEED') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_dept_menu_perm (dept_id, menu_id, can_read, can_write, ins_user, upd_user) VALUES (2, 'rpt-scrap-new', 't', 'f', 'SEED', 'SEED') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_dept_menu_perm (dept_id, menu_id, can_read, can_write, ins_user, upd_user) VALUES (3, 'rpt-scrap-new', 't', 'f', 'SEED', 'SEED') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_dept_menu_perm (dept_id, menu_id, can_read, can_write, ins_user, upd_user) VALUES (6, 'rpt-scrap-new', 't', 'f', 'SEED', 'SEED') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_dept_menu_perm (dept_id, menu_id, can_read, can_write, ins_user, upd_user) VALUES (1, 'sys-metric', 't', 'f', 'SEED', 'SEED') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_dept_menu_perm (dept_id, menu_id, can_read, can_write, ins_user, upd_user) VALUES (5, 'sys-metric', 't', 'f', 'SEED', 'SEED') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_dept_menu_perm (dept_id, menu_id, can_read, can_write, ins_user, upd_user) VALUES (1, 'sys-model-ver', 't', 'f', 'SEED', 'SEED') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_dept_menu_perm (dept_id, menu_id, can_read, can_write, ins_user, upd_user) VALUES (5, 'sys-model-ver', 't', 'f', 'SEED', 'SEED') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_dept_menu_perm (dept_id, menu_id, can_read, can_write, ins_user, upd_user) VALUES (1, 'sys-rank', 't', 'f', 'SEED', 'SEED') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_dept_menu_perm (dept_id, menu_id, can_read, can_write, ins_user, upd_user) VALUES (5, 'sys-rank', 't', 'f', 'SEED', 'SEED') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_sys_dept_menu_perm (dept_id, menu_id, can_read, can_write, ins_user, upd_user) VALUES (6, 'sys-rank', 't', 'f', 'SEED', 'SEED') ON CONFLICT DO NOTHING;
INSERT INTO ax.tb_rpt_report (report_id, report_nm, report_group, menu_id, sort_seq, use_flg) VALUES ('RPT_QUALITY', '품질 보고서', '품질관리', 'qc-report', 2, 'Y') ON CONFLICT (report_id) DO NOTHING;
UPDATE ax.tb_rpt_report SET report_group = '생산관리' WHERE report_id = 'RPT_DAILY_PROD';

-- ── 8. V42 가 만든 그룹 제거 ────────────────────────────────────────────────────────
DELETE FROM ax.tb_sys_menu_group g
 WHERE g.group_id = 'operation'
   AND NOT EXISTS (SELECT 1 FROM ax.tb_sys_menu m WHERE m.group_id = g.group_id);

COMMIT;

\echo ''
\echo '-- 복원 확인 --'
SELECT (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
         WHERE n.nspname='ax'
           AND c.relname IN ('tb_ai_serving_asset','tb_ai_serving_route','tb_ai_corpus_snapshot','tb_ai_model_asset',
                             'tb_ai_defect_tag','tb_ai_defect_tag_map','tb_ai_chat_term','tb_sys_user_favorite',
                             'tb_rpt_write_state','tb_aoi_defect_image','tb_rpt_unmask_req',
                             'vw_serving_active','vw_serving_profile_detail','vw_serving_asset_health')) AS "표·뷰 (14)",
       (SELECT count(*) FROM ax.tb_sys_menu)       AS "메뉴 (38)",
       (SELECT count(*) FROM ax.tb_sys_menu_group) AS "그룹 (7)";

\echo ''
\echo '==================== V42 되돌리기 완료 — 데이터는 v42_backup.sql 로 복원 ===================='
