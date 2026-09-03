-- =====================================================================================
--  덕우전자 AX 시스템 — 스키마 확장 : 이관 작업 예약 큐 (SY-15 데이터 연동 이력)
--
--  배경
--  ----
--  화면의 [수동 이관](No.230)과 [작업 재실행](No.229)은 ax.tb_sync_job 에 행을 만들지만,
--  이관 엔진(MES_migration_engine)이 그 행을 집어갈 방법이 없어 실제로 실행되지 않았다.
--  또한 두 기능 모두 state_cd = 'RUNNING' 으로 행을 만들어,
--  엔진이 기동 시 수행하는 "미완료 RUNNING 정리" 에 걸려 ABORTED 로 뒤집히는 문제가 있었다.
--
--  본 스크립트는 예약(대기) 상태와 예약 시각을 도입해 화면 → 엔진 인계를 성립시킨다.
--
--    화면/API  : state_cd = 'PENDING', scheduled_at = 실행 희망 시각 으로 행 생성
--    엔진      : scheduled_at 이 지난 PENDING 행을 원자적으로 선점(RUNNING)하고 실행
--                → 완료 시 DONE / RETRY_DONE / FAIL 로 마감
--
--  [설계 근거]
--    · started_at 을 예약 시각으로 쓰지 않는다. "아직 시작하지 않은 작업" 의 시작 시각은
--      NULL 이어야 하고, 예약 시각은 의미가 다른 별도 값이다.
--      (기존에는 예약 시각을 started_at 에 넣어, 02:00 예약 작업이 지금 RUNNING 인 것처럼 보였다)
--    · 선점은 FOR UPDATE SKIP LOCKED 로 한다. 엔진 인스턴스가 둘 이상이어도
--      같은 작업을 두 번 실행하지 않는다.
--
--  적용 : psql -d dwjedb -f src/main/resources/db/V4__ax_sync_job_queue.sql
--  선행 : ai_db_query.sql
--  재실행 : 안전(멱등)
-- =====================================================================================

SET search_path TO ax, mes, common, public;
SET client_min_messages = WARNING;

BEGIN;

-- 1. 예약 시각 — 엔진이 이 시각이 지난 작업만 집어간다
ALTER TABLE ax.tb_sync_job ADD COLUMN IF NOT EXISTS scheduled_at timestamptz;

COMMENT ON COLUMN ax.tb_sync_job.scheduled_at IS
  '실행 예약 시각. 화면의 수동 이관·재실행이 설정하며, 이관 엔진이 이 시각이 지난 PENDING 작업을 집어간다. 정기 배치가 만든 작업은 NULL';

-- 2. 아직 시작하지 않은 작업(PENDING)의 시작 시각은 NULL 이어야 한다
ALTER TABLE ax.tb_sync_job ALTER COLUMN started_at DROP NOT NULL;

COMMENT ON COLUMN ax.tb_sync_job.started_at IS
  '실제 실행 시작 시각. PENDING(예약 대기) 상태에서는 NULL 이고, 엔진이 작업을 선점하는 순간 기록된다';

-- 3. 예약 대기 상태 코드 추가
INSERT INTO ax.tb_sys_code (group_cd, code, code_nm, code_desc, sort_seq) VALUES
 ('SYNC_STATE','PENDING','예약 대기','화면에서 예약되어 이관 엔진이 집어가기를 기다리는 상태',0)
ON CONFLICT ON CONSTRAINT pk_tb_sys_code DO NOTHING;

-- 4. 큐 선점 인덱스 — 대기 중인 작업만 담아 배치 주기마다의 조회를 가볍게 유지한다
CREATE INDEX IF NOT EXISTS ix_sync_job_pending
    ON ax.tb_sync_job (scheduled_at NULLS FIRST, job_id)
 WHERE state_cd = 'PENDING';

-- 5. 예약 작업 ID 채번 시퀀스
--    job_id 는 varchar(20) 이라 초 단위 타임스탬프까지만 담을 수 있다.
--    같은 초에 두 요청이 들어오면 ID 가 겹쳐 PK 위반이 나므로, 접미 번호를 시퀀스로 뽑는다.
--    (요청 안의 순번을 쓰면 요청 경계를 넘는 충돌을 막지 못한다)
CREATE SEQUENCE IF NOT EXISTS ax.seq_sync_job_no AS integer CYCLE MINVALUE 1 MAXVALUE 999;

COMMENT ON SEQUENCE ax.seq_sync_job_no IS
  '화면에서 예약하는 이관 작업(SYNC-yyMMddHHmmss-N)의 접미 번호. 같은 초 안의 ID 충돌을 막는다';

-- 6. 기존에 잘못 만들어진 예약 행 정리
--    화면에서 예약했으나 실행되지 못한 채 RUNNING 으로 남아 있는 행을 PENDING 으로 되돌린다.
--    (엔진이 만든 작업은 remark 에 run= 식별자가 있으므로 구분된다)
UPDATE ax.tb_sync_job
   SET state_cd     = 'PENDING',
       scheduled_at = COALESCE(scheduled_at, started_at),
       started_at   = NULL
 WHERE state_cd = 'RUNNING'
   AND triggered_by_cd IN ('MANUAL', 'RETRY')
   AND ended_at IS NULL
   AND remark NOT LIKE 'run=%';

COMMIT;

-- 적용 확인
SELECT state_cd, triggered_by_cd, count(*)
  FROM ax.tb_sync_job
 GROUP BY state_cd, triggered_by_cd
 ORDER BY 1, 2;
