-- =====================================================================================
--  덕우전자 AX 시스템 — 스키마 확장 : 이관 실행 이력 (SY-15 데이터 연동 이력)
--
--  배경
--  ----
--  ax.tb_sync_job 은 "테이블 1건의 이관" 단위라, 그 앞에서 멈춘 실행은 흔적이 없다.
--    · 원본·대상 접속 실패 → 프리플라이트에서 중단 → tb_sync_job 에 0건
--    · 이관 대상이 없어 아무 일도 하지 않은 실행
--    · 다른 인스턴스가 점유(advisory lock)해 건너뛴 실행
--  실제로 하루에 다섯 번 실행하고도 DB 에는 아무 기록이 없는 상황이 있었다.
--  파일 로그에만 남는데 그것도 실행 위치(작업 디렉터리)마다 흩어진다.
--
--  본 테이블은 **엔진을 한 번 돌릴 때마다 한 행**을 남긴다. 성공·실패·건너뜀을 가리지 않는다.
--  tb_sync_job 은 그 실행에 속한 테이블별 상세로 남고, run_id 로 묶인다.
--
--  쓰기 주체 : MES_migration_engine
--  읽기 주체 : API — GET /api/v1/sync/runs (SY-15 화면)
--
--  적용 : psql -d dwjedb -f src/main/resources/db/V13__ax_sync_run.sql
--  선행 : ai_db_query.sql
--  재실행 : 안전(멱등)
-- =====================================================================================

SET search_path TO ax, mes, common, public;
SET client_min_messages = WARNING;

BEGIN;

CREATE TABLE IF NOT EXISTS ax.tb_sync_run (
    run_id          varchar(30)     PRIMARY KEY,
    mode_cd         varchar(30)     NOT NULL,
    state_cd        varchar(30)     NOT NULL,
    started_at      timestamptz     NOT NULL DEFAULT now(),
    ended_at        timestamptz,
    duration_sec    integer,
    triggered_by_cd varchar(30)     NOT NULL DEFAULT 'BATCH',
    triggered_by    common.d_user_id,
    options_desc    varchar(300),
    target_cnt      smallint        NOT NULL DEFAULT 0,
    success_cnt     smallint        NOT NULL DEFAULT 0,
    fail_cnt        smallint        NOT NULL DEFAULT 0,
    ok_rows         bigint          NOT NULL DEFAULT 0,
    ng_rows         bigint          NOT NULL DEFAULT 0,
    drift_open_cnt  smallint,
    source_url      varchar(300),
    target_url      varchar(300),
    engine_version  varchar(20),
    host_name       varchar(100),
    message         varchar(2000),
    -- 모의 실행 여부 (V14 에서 추가. 신규 설치는 여기서 바로 생긴다)
    is_dry_run      boolean         NOT NULL DEFAULT false
);

COMMENT ON TABLE  ax.tb_sync_run                    IS '이관 실행 이력 — 엔진을 한 번 돌릴 때마다 1행. 프리플라이트 실패·무작업·건너뜀까지 모두 남긴다. 테이블별 상세는 ax.tb_sync_job (run_id 로 연결)';
COMMENT ON COLUMN ax.tb_sync_run.run_id             IS '실행 식별자 (RUN-yyyyMMdd-HHmm[-QUEUE|-RETRY]). tb_sync_job.remark 의 run= 값과 같다';
COMMENT ON COLUMN ax.tb_sync_run.mode_cd            IS '실행 모드. 공통코드 그룹 = SYNC_RUN_MODE (SCHEDULED=정기 배치, MANUAL=즉시 1회, QUEUE=예약 큐, RETRY=작업 재실행)';
COMMENT ON COLUMN ax.tb_sync_run.state_cd           IS '실행 결과. 공통코드 그룹 = SYNC_RUN_STATE (RUNNING=진행 중, DONE=전건 성공, PARTIAL=일부 실패, FAIL=전건 실패, PREFLIGHT_FAIL=접속·점검 실패로 시작 못함, NO_WORK=대상 없음, SKIPPED=다른 인스턴스 점유로 건너뜀, ABORTED=비정상 종료)';
COMMENT ON COLUMN ax.tb_sync_run.options_desc       IS '실행 옵션 요약 (--since/--until/--tables 등). 어떤 조건으로 돌렸는지 재현용';
COMMENT ON COLUMN ax.tb_sync_run.target_cnt         IS '이번 실행의 대상 테이블 수';
COMMENT ON COLUMN ax.tb_sync_run.drift_open_cnt     IS '실행 시점의 미해소 스키마 드리프트 건수';
COMMENT ON COLUMN ax.tb_sync_run.source_url         IS '접속한 원본 (비밀번호 마스킹). 어느 서버를 봤는지 사후 확인용';
COMMENT ON COLUMN ax.tb_sync_run.host_name          IS '엔진이 돌아간 호스트. 여러 대에서 돌릴 때 구분';
COMMENT ON COLUMN ax.tb_sync_run.message            IS '실패 사유. 예외 연쇄의 **근본 원인까지** 남긴다 (예: Failed to obtain JDBC Connection → No route to host)';
COMMENT ON COLUMN ax.tb_sync_run.is_dry_run         IS '모의 실행 여부. true 면 대상만 확인하고 아무것도 반영하지 않았다 (--dry-run)';

CREATE INDEX IF NOT EXISTS ix_sync_run_at    ON ax.tb_sync_run (started_at DESC);
CREATE INDEX IF NOT EXISTS ix_sync_run_state ON ax.tb_sync_run (state_cd, started_at DESC);

-- tb_sync_job 을 실행 단위로 묶는다 (기존 행은 NULL 로 남는다)
ALTER TABLE ax.tb_sync_job ADD COLUMN IF NOT EXISTS run_id varchar(30);
COMMENT ON COLUMN ax.tb_sync_job.run_id IS '소속 실행 (ax.tb_sync_run). 정기 배치 한 번에 여러 테이블 작업이 딸린다';
CREATE INDEX IF NOT EXISTS ix_sync_job_run ON ax.tb_sync_job (run_id);

-- ── 공통코드 ─────────────────────────────────────────────────────────────────────────
INSERT INTO ax.tb_sys_code_group (group_cd, group_nm, sort_seq) VALUES
 ('SYNC_RUN_MODE','이관 실행 모드',95),
 ('SYNC_RUN_STATE','이관 실행 결과',96)
ON CONFLICT (group_cd) DO NOTHING;

INSERT INTO ax.tb_sys_code (group_cd, code, code_nm, code_desc, sort_seq) VALUES
 ('SYNC_RUN_MODE','SCHEDULED','정기 배치','스케줄 발화',1),
 ('SYNC_RUN_MODE','MANUAL','즉시 실행','--now',2),
 ('SYNC_RUN_MODE','QUEUE','예약 큐','--queue. 화면에서 건 수동 이관 처리',3),
 ('SYNC_RUN_MODE','RETRY','작업 재실행','--retry-job',4),
 ('SYNC_RUN_STATE','RUNNING','진행 중',NULL,1),
 ('SYNC_RUN_STATE','DONE','완료','대상 전건 성공',2),
 ('SYNC_RUN_STATE','PARTIAL','일부 실패','일부 테이블만 실패. 나머지는 정상 완료',3),
 ('SYNC_RUN_STATE','FAIL','실패','대상 전건 실패',4),
 ('SYNC_RUN_STATE','PREFLIGHT_FAIL','점검 실패','접속 불가 등으로 이관을 시작하지 못함',5),
 ('SYNC_RUN_STATE','NO_WORK','대상 없음','이관 대상이나 대기 작업이 없어 아무 일도 하지 않음',6),
 ('SYNC_RUN_STATE','SKIPPED','건너뜀','다른 인스턴스가 실행 중이어서 건너뜀',7),
 ('SYNC_RUN_STATE','ABORTED','중단','프로세스 강제 종료 등으로 마감되지 못함',8)
ON CONFLICT ON CONSTRAINT pk_tb_sys_code DO NOTHING;

INSERT INTO ax.tb_sys_code_ref (target_table, target_column, group_cd, nullable_flg) VALUES
 ('tb_sync_run','mode_cd','SYNC_RUN_MODE','N'),
 ('tb_sync_run','state_cd','SYNC_RUN_STATE','N'),
 ('tb_sync_run','triggered_by_cd','SYNC_TRIGGER','N')
ON CONFLICT ON CONSTRAINT pk_tb_sys_code_ref DO NOTHING;

COMMIT;
