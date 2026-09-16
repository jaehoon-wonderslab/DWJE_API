-- =====================================================================================
--  V35 : 이상 알림 발송 엔진 기반 (2026-09-16)
--
--  설계서 : docs/ALERT_ENGINE_DESIGN_20260916.md
--
--  [배경]
--  SY-04 [이상 알림 발송 조건 관리] 화면은 조건을 등록·수정·삭제할 수 있지만, 등록한 조건을
--  실제로 판정하는 것이 아무것도 없다. tb_alm_cond 는 0행이고, 설령 행을 넣어도 비교할 실측치
--  (tb_met_metric_value 0행)가 없으며, 판정 결과를 담을 자리도 없다. 화면은 "발송 조건"을
--  말하는데 발송하는 주체가 없는 상태다.
--
--  [조건 표는 이미 다 있다 — 없는 것은 「돌리는 것」과 「상태」다]
--  tb_alm_cond(+channel/group/escalation) · tb_alm_recip_group(+member/channel) ·
--  tb_alm_recipient · tb_alm_duty · tb_alm_escalation_rule · tb_alm_alert · tb_alm_send_log
--  11개 표가 이미 설계돼 있다. 특히 ALM_SEND_RESULT 에 SENT/FAIL 말고 SUPPRESSED(중복 억제)·
--  SKIPPED(시간대 제외)가 처음부터 들어 있다 — 억제·제외도 로그로 남기라는 뜻이다.
--  그 약속을 지킬 수 있도록, 이 파일은 표를 새로 설계하지 않고 「빠진 것만」 채운다.
--      ① 평가 상태를 둘 곳    tb_alm_cond_state
--      ② 발송 대기열          tb_alm_send_queue
--      ③ 엔진 실행 이력       tb_alm_eval_run
--      ④ 지표 수집 정의       tb_met_metric_collect
--      ⑤ 개별 대상 선택       tb_alm_cond_target   (ALM_TARGET='PICK' 이 담길 곳이 없었다)
--
--  [판정 숫자를 코드가 아니라 데이터에 둔다 — attr1/attr2 를 채우는 이유]
--  'M30' → 30분, 'C10M' → 600초, 'D0820' → 08:00~20:00 을 Kotlin when 절에 박으면
--  중복 억제 창을 45분으로 바꾸는 일이 배포가 된다. 더 나쁜 것은 코드명 파싱이다 —
--  'D0820' 에서 시간을 떼어 쓰면 코드명을 바꾸는 순간 판정이 조용히 틀어진다.
--  ALM_* 코드의 attr1/attr2 는 지금 전부 비어 있어 덮어쓸 값이 없다. 거기에 넣는다.
--
--  [tb_alm_alert.hit_cnt 를 더하는 이유]
--  중복 억제를 "알림을 아예 안 만든다"로 처리하면 30분 동안 12번 터진 사실이 사라진다.
--  억제 창 안의 재발은 기존 알림 행의 hit_cnt 를 올리고 send_log 에 SUPPRESSED 를 남긴다.
--  목록은 1건으로 깨끗하고 근거는 남는다.
--
--  [대기열을 tb_alm_send_log 와 합치지 않는 이유]
--  재시도 루프가 로그 행을 UPDATE 하면 "3번째 시도에 나갔다"가 덮여 사라진다.
--  대기열은 '지금 해야 할 일', 로그는 '시도할 때마다 1행'. 역할이 다르므로 표를 나눈다.
--
--  [tb_met_metric_collect.sql_text 는 만들되 쓰지 않는다]
--  집계 SQL 을 표에 넣고 화면에서 편집하게 하면 지표 추가에 배포가 필요 없어진다(V33 의 연장).
--  그런데 그 표에 쓰기 권한을 가진 사람이 DB 에서 임의 SQL 을 돌릴 수 있게 된다.
--  1단계는 collect_mode_cd='BUILTIN'(Kotlin 구현체)만 쓴다. sql_text 는 컬럼만 두고,
--  읽기 전용 롤 분리 · statement_timeout · SELECT 단일문 검증 · 감사 로그가 갖춰진 뒤 연다.
--
--  [여기서 하지 않는 것]
--  · tb_met_metric_value 의 월 파티션 전환 — 설계서 §4-8. 지금 0행이라 급하지 않고,
--    인덱스·FK 를 다시 만드는 작업이라 엔진 1단계가 실제로 값을 쌓기 시작한 뒤 V36 으로 한다.
--  · 수집기 구현체가 없는 지표의 tb_met_metric_collect 행 — 짐작해 넣으면 엉뚱한 값이 쌓인다.
--    아래 6번에서 apply_alert=true 인 지표 1건만, use_flg='N'(미수집)으로 등록한다.
--  · '야간' 시각 정의(수신자 night_recv 판정 기준) — ALM_WINDOW 에 코드로 넣으면 화면의
--    [유효 시간대] 선택지에 '야간'이 끼어든다. 앱 설정(app.alert-engine)에 둔다.
--
--  [적용]
--  docker exec -e PGPASSWORD=dwje_local dwje-pg psql -U dwje_local -d dwjedb -v ON_ERROR_STOP=1 -f V35__alm_engine.sql
--  두 번 실행해도 안전하다. 운영은 요청자 확인 뒤에 적용한다(V31·V32·V33·V34 도 아직 미적용).
-- =====================================================================================

-- 1. 공통코드 그룹 ---------------------------------------------------------------------
--    ALM_* 는 50~59 를 쓰고 있어 60번대에 붙인다. 지표 수집 방식만 MET_* (40~44) 뒤에 둔다.
INSERT INTO ax.tb_sys_code_group (group_cd, group_nm, group_desc, use_flg, sort_seq) VALUES
 ('MET_COLLECT_MODE', '지표 수집 방식',   '지표 실측치를 채우는 방식 (ax.tb_met_metric_collect)',              'Y',  45),
 ('ALM_SCOPE_DIM',    '알림 평가 단위',   '조건 하나를 어느 단위로 쪼개 평가할지 (ax.tb_alm_cond.scope_dim_cd)', 'Y',  60),
 ('ALM_COND_STATE',   '조건 평가 상태',   '조건×대상의 현재 판정 상태 (ax.tb_alm_cond_state)',                  'Y',  61),
 ('ALM_QUEUE_STATE',  '발송 대기 상태',   '알림 발송 대기열의 처리 상태 (ax.tb_alm_send_queue)',                'Y',  62),
 ('ALM_RUN_STATE',    '알림 엔진 실행 결과', '알림 엔진 한 번의 실행 결과 (ax.tb_alm_eval_run)',                'Y',  63),
 ('ALM_RUN_TRIGGER',  '알림 엔진 실행 주체', '알림 엔진을 돌린 주체 (ax.tb_alm_eval_run)',                      'Y',  64)
ON CONFLICT (group_cd) DO NOTHING;

INSERT INTO ax.tb_sys_code (group_cd, code, code_nm, code_desc, sort_seq, use_flg, ins_user, upd_user) VALUES
 ('MET_COLLECT_MODE', 'BUILTIN', '내장 구현',   'Kotlin 수집기 구현체가 채운다. collector_cd 로 구현체를 고른다',      1, 'Y', 'V35', 'V35'),
 ('MET_COLLECT_MODE', 'SQL',     '집계 SQL',    'sql_text 를 읽기 전용 롤로 실행해 채운다. 2단계 — 지금은 쓰지 않는다', 2, 'Y', 'V35', 'V35'),

 ('ALM_SCOPE_DIM',    'NONE',    '전체 1건',    '대상을 묶어 값 하나로 평가한다. scope_key = ''*''',                 1, 'Y', 'V35', 'V35'),
 ('ALM_SCOPE_DIM',    'EQPT',    '설비별',      '설비마다 따로 판정·따로 억제된다',                                  2, 'Y', 'V35', 'V35'),
 ('ALM_SCOPE_DIM',    'WC',      '공정별',      '워크센터마다 따로 판정한다',                                        3, 'Y', 'V35', 'V35'),
 ('ALM_SCOPE_DIM',    'ITEM',    '품목별',      '품목마다 따로 판정한다',                                            4, 'Y', 'V35', 'V35'),
 ('ALM_SCOPE_DIM',    'MOLD',    '금형별',      '금형마다 따로 판정한다',                                            5, 'Y', 'V35', 'V35'),
 ('ALM_SCOPE_DIM',    'PRODUCT', '제품별',      '제품마다 따로 판정한다',                                            6, 'Y', 'V35', 'V35'),

 ('ALM_COND_STATE',   'NORMAL',  '정상',        '임계 안쪽이다. breach_since 는 비어 있다',                          1, 'Y', 'V35', 'V35'),
 ('ALM_COND_STATE',   'PENDING', '감시중',      '임계는 넘었지만 지속 조건(예: 10분 연속)을 아직 못 채웠다',          2, 'Y', 'V35', 'V35'),
 ('ALM_COND_STATE',   'BREACH',  '발생',        '지속 조건까지 충족해 알림이 나갔거나 나갈 차례다',                   3, 'Y', 'V35', 'V35'),

 ('ALM_QUEUE_STATE',  'PENDING', '대기',        '아직 집어가지 않았다',                                              1, 'Y', 'V35', 'V35'),
 ('ALM_QUEUE_STATE',  'SENDING', '발송중',      '워커가 집어갔다. locked_at 이 5분을 넘으면 PENDING 으로 회수한다',    2, 'Y', 'V35', 'V35'),
 ('ALM_QUEUE_STATE',  'DONE',    '완료',        '발송에 성공했다. tb_alm_send_log 에 SENT 로 남는다',                 3, 'Y', 'V35', 'V35'),
 ('ALM_QUEUE_STATE',  'FAIL',    '실패-재시도', '실패했고 next_try_at 에 다시 시도한다',                              4, 'Y', 'V35', 'V35'),
 ('ALM_QUEUE_STATE',  'DEAD',    '포기',        '최대 시도 횟수를 넘겼다. send_log 에 FAIL 로 확정한다',              5, 'Y', 'V35', 'V35'),

 ('ALM_RUN_STATE',    'RUNNING', '실행중',      '끝나지 않았다. 오래 남아 있으면 엔진이 죽은 것이다',                 1, 'Y', 'V35', 'V35'),
 ('ALM_RUN_STATE',    'OK',      '정상',        '모든 조건을 판정했다',                                              2, 'Y', 'V35', 'V35'),
 ('ALM_RUN_STATE',    'PARTIAL', '부분 실패',   '일부 조건이 오류로 건너뛰어졌다. 나머지는 정상 판정됐다',            3, 'Y', 'V35', 'V35'),
 ('ALM_RUN_STATE',    'FAIL',    '실패',        '실행 자체가 실패했다',                                              4, 'Y', 'V35', 'V35'),

 ('ALM_RUN_TRIGGER',  'BATCH',   '정기 실행',   '엔진의 주기 틱',                                                    1, 'Y', 'V35', 'V35'),
 ('ALM_RUN_TRIGGER',  'MANUAL',  '수동 실행',   '화면에서 특정 조건을 지금 평가',                                    2, 'Y', 'V35', 'V35'),
 ('ALM_RUN_TRIGGER',  'TEST',    '테스트 발송', '조건 테스트 발송이 만든 실행',                                      3, 'Y', 'V35', 'V35')
ON CONFLICT (group_cd, code) DO NOTHING;

-- 2. 기존 ALM 코드의 숫자 의미를 데이터로 -------------------------------------------------
--    지금 attr1/attr2 는 ALM_* 전 코드에서 비어 있다. 값이 이미 있으면 건드리지 않는다
--    (운영에서 누가 조정해 둔 값을 이 파일 재적용이 되돌리면 안 된다).

-- 2-1. 중복 억제 : attr1 = 분. DAY_ONCE(-1) 는 달력일 1회
UPDATE ax.tb_sys_code SET attr1 = v.min, upd_date = now(), upd_user = 'V35'
  FROM (VALUES ('NONE','0'), ('M15','15'), ('M30','30'), ('M60','60'), ('M120','120'), ('DAY_ONCE','-1')) AS v(cd, min)
 WHERE group_cd = 'ALM_DEDUP' AND code = v.cd AND attr1 IS NULL;

-- 2-2. 지속 조건 : attr1 = 초, attr2 = 판정 방식(CONT 연속 / AVG 이동평균 / CLOSE 마감시점)
UPDATE ax.tb_sys_code SET attr1 = v.sec, attr2 = v.kind, upd_date = now(), upd_user = 'V35'
  FROM (VALUES ('IMMEDIATE','0','CONT'), ('C5M','300','CONT'), ('C10M','600','CONT'),
               ('C30M','1800','CONT'), ('MA120','7200','AVG'),
               ('DAY_CLOSE','0','CLOSE'), ('DAY_ONCE','0','CLOSE')) AS v(cd, sec, kind)
 WHERE group_cd = 'ALM_DURATION' AND code = v.cd AND attr1 IS NULL;

-- 2-3. 비교 연산 : attr1 = SQL 연산자. RATE 는 직전 값 대비 변화율(%)을 비교값으로 쓴다
UPDATE ax.tb_sys_code SET attr1 = v.op, upd_date = now(), upd_user = 'V35'
  FROM (VALUES ('GE','>='), ('GT','>'), ('LE','<='), ('LT','<'), ('EQ','='), ('RATE','PCT')) AS v(cd, op)
 WHERE group_cd = 'ALM_OP' AND code = v.cd AND attr1 IS NULL;

-- 2-4. 유효 시간대 : attr1 = 시작, attr2 = 종료
--      ONCE(지정 시각 1회)는 조건마다 시각이 달라 tb_alm_cond.window_time 을 본다 — 여기서 채우지 않는다.
--      WORKDAY 는 시각과 별개로 '평일' 판정이 더 붙는다.
UPDATE ax.tb_sys_code SET attr1 = v.f, attr2 = v.t, upd_date = now(), upd_user = 'V35'
  FROM (VALUES ('ALWAYS','00:00','24:00'), ('D0820','08:00','20:00'),
               ('D0618','06:00','18:00'), ('WORKDAY','08:00','17:30')) AS v(cd, f, t)
 WHERE group_cd = 'ALM_WINDOW' AND code = v.cd AND attr1 IS NULL;

-- 3. 조건 표 보강 ----------------------------------------------------------------------
ALTER TABLE ax.tb_alm_cond
  ADD COLUMN IF NOT EXISTS scope_dim_cd      varchar(30) NOT NULL DEFAULT 'NONE',
  ADD COLUMN IF NOT EXISTS window_time       time,
  ADD COLUMN IF NOT EXISTS eval_interval_sec integer     NOT NULL DEFAULT 60,
  ADD COLUMN IF NOT EXISTS ignore_window_flg common.d_yn NOT NULL DEFAULT 'N',
  ADD COLUMN IF NOT EXISTS auto_close_flg    common.d_yn NOT NULL DEFAULT 'N',
  ADD COLUMN IF NOT EXISTS last_eval_at      timestamptz;

COMMENT ON COLUMN ax.tb_alm_cond.scope_dim_cd      IS '평가 단위. 공통코드 ALM_SCOPE_DIM. ''EQPT'' 면 설비마다 따로 판정·따로 억제된다. ''NONE'' 이면 대상 전체를 묶어 값 하나로 본다';
COMMENT ON COLUMN ax.tb_alm_cond.window_time       IS 'window_cd=''ONCE''(지정 시각 1회) 일 때의 시각. 그 밖의 시간대는 공통코드 ALM_WINDOW 의 attr1/attr2 를 쓴다';
COMMENT ON COLUMN ax.tb_alm_cond.eval_interval_sec IS '이 조건을 몇 초마다 평가할지. 엔진 틱(기본 60초)보다 짧게 잡아도 틱 주기가 하한이다';
COMMENT ON COLUMN ax.tb_alm_cond.ignore_window_flg IS '''Y'' 면 유효 시간대 밖에도 발송한다. 위험(CRIT) 조건을 야간에도 받아야 할 때 켠다. 수신자 개인의 야간 미수신(night_recv)은 이 값과 무관하게 지켜진다';
COMMENT ON COLUMN ax.tb_alm_cond.auto_close_flg    IS '''Y'' 면 값이 정상으로 돌아올 때 tb_alm_alert.resolved_at 을 찍는다. 확인 처리(ack_state_cd)는 사람이 한다 — 자동으로 CLOSED 로 바꾸지 않는다';
COMMENT ON COLUMN ax.tb_alm_cond.last_eval_at      IS '마지막 평가 시각(화면 표시용). 대상별 정밀 상태는 ax.tb_alm_cond_state 를 본다';

-- 4. 알림 표 보강 ----------------------------------------------------------------------
ALTER TABLE ax.tb_alm_alert
  ADD COLUMN IF NOT EXISTS scope_key   varchar(100),
  ADD COLUMN IF NOT EXISTS hit_cnt     integer NOT NULL DEFAULT 1,
  ADD COLUMN IF NOT EXISTS last_hit_at timestamptz,
  ADD COLUMN IF NOT EXISTS resolved_at timestamptz;

COMMENT ON COLUMN ax.tb_alm_alert.scope_key   IS '어느 대상에서 났는지(설비코드 등). ax.tb_alm_cond_state 와 잇는 키. scope_dim_cd=''NONE'' 이면 ''*''';
COMMENT ON COLUMN ax.tb_alm_alert.hit_cnt     IS '중복 억제 창 안에서 다시 걸린 횟수(최초 발생 포함 1). 억제됐다고 사실까지 지우지 않기 위한 값이다';
COMMENT ON COLUMN ax.tb_alm_alert.last_hit_at IS '억제 창 안에서 마지막으로 다시 걸린 시각';
COMMENT ON COLUMN ax.tb_alm_alert.resolved_at IS '값이 정상으로 돌아온 시각. 확인 처리(ack)와는 다르다 — 사람이 안 봐도 상황은 풀릴 수 있다';

-- 5. 개별 대상 선택 (ALM_TARGET='PICK') ---------------------------------------------------
CREATE TABLE IF NOT EXISTS ax.tb_alm_cond_target (
    cond_id       integer     NOT NULL,
    target_dim_cd varchar(30) NOT NULL,
    target_cd     varchar(50) NOT NULL,

    CONSTRAINT pk_alm_cond_target PRIMARY KEY (cond_id, target_dim_cd, target_cd),
    CONSTRAINT fk_alm_cond_target_cond
        FOREIGN KEY (cond_id) REFERENCES ax.tb_alm_cond(cond_id) ON DELETE CASCADE
);
COMMENT ON TABLE  ax.tb_alm_cond_target               IS '조건의 개별 대상 목록. tb_alm_cond.target_scope_cd=''PICK''(개별 설비 선택) 일 때만 쓴다 — 그 코드값이 있는데 담을 곳이 없었다';
COMMENT ON COLUMN ax.tb_alm_cond_target.target_dim_cd IS '대상 종류. 공통코드 ALM_SCOPE_DIM 과 같은 값을 쓴다(EQPT·WC·ITEM·MOLD·PRODUCT)';
COMMENT ON COLUMN ax.tb_alm_cond_target.target_cd     IS '대상 코드. 설비코드·워크센터코드 등. 마스터가 지워져도 조건은 남으므로 FK 를 걸지 않는다';

-- 6. 평가 상태 — 엔진의 심장 ---------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ax.tb_alm_cond_state (
    cond_id       integer      NOT NULL,
    scope_key     varchar(100) NOT NULL DEFAULT '*',
    state_cd      varchar(30)  NOT NULL DEFAULT 'NORMAL',
    last_value    numeric(18,6),
    last_eval_at  timestamptz,
    breach_since  timestamptz,
    breach_cnt    integer      NOT NULL DEFAULT 0,
    last_alert_id bigint,
    last_alert_at timestamptz,
    suppress_cnt  integer      NOT NULL DEFAULT 0,
    next_eval_at  timestamptz  NOT NULL DEFAULT now(),
    upd_date      timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT pk_alm_cond_state PRIMARY KEY (cond_id, scope_key),
    CONSTRAINT ck_alm_cond_state_scope CHECK (scope_key <> ''),
    CONSTRAINT fk_alm_cond_state_cond
        FOREIGN KEY (cond_id) REFERENCES ax.tb_alm_cond(cond_id) ON DELETE CASCADE,
    -- 알림이 지워져도 상태는 살아 있어야 한다 (억제 기준은 last_alert_at 이 따로 들고 있다)
    CONSTRAINT fk_alm_cond_state_alert
        FOREIGN KEY (last_alert_id) REFERENCES ax.tb_alm_alert(alert_id) ON DELETE SET NULL
);
-- 평가할 차례가 된 것만 훑는다
CREATE INDEX IF NOT EXISTS ix_alm_cond_state_due ON ax.tb_alm_cond_state (next_eval_at);

COMMENT ON TABLE  ax.tb_alm_cond_state               IS '조건 × 대상의 평가 상태. 「10분 연속」·「30분 중복 억제」를 계산할 수 있는 유일한 근거다. 이 표가 없으면 엔진은 매번 처음부터 판정한다';
COMMENT ON COLUMN ax.tb_alm_cond_state.scope_key     IS '대상 키(설비코드 등). tb_alm_cond.scope_dim_cd=''NONE'' 이면 ''*''';
COMMENT ON COLUMN ax.tb_alm_cond_state.state_cd      IS '현재 판정. 공통코드 ALM_COND_STATE. PENDING = 임계는 넘었으나 지속 조건 미충족';
COMMENT ON COLUMN ax.tb_alm_cond_state.breach_since  IS '연속 위반이 시작된 시각. 중간에 한 번이라도 정상이면 NULL 로 되돌린다 — 연속이 끊긴 것이다';
COMMENT ON COLUMN ax.tb_alm_cond_state.breach_cnt    IS '연속 위반으로 판정된 횟수. 이동평균·간헐 위반을 사람이 판단할 때 쓴다';
COMMENT ON COLUMN ax.tb_alm_cond_state.last_alert_at IS '마지막으로 알림을 낸 시각. 중복 억제 창(ALM_DEDUP.attr1 분)의 기준점이다';
COMMENT ON COLUMN ax.tb_alm_cond_state.suppress_cnt  IS '억제 창에 걸려 발송하지 않은 누적 횟수. 억제 설정이 과한지 보는 값이다';
COMMENT ON COLUMN ax.tb_alm_cond_state.next_eval_at  IS '다음 평가 예정 시각. 엔진은 이 값이 지난 행만 집어간다';

-- 7. 발송 대기열 (outbox) ------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ax.tb_alm_send_queue (
    queue_id         bigint       GENERATED BY DEFAULT AS IDENTITY,
    alert_id         bigint       NOT NULL,
    group_id         integer,
    user_id          common.d_user_id,
    channel_cd       varchar(30)  NOT NULL,
    dest_addr        varchar(200),
    subject          varchar(300),
    body             text,
    esc_level        smallint     NOT NULL DEFAULT 0,
    is_proxy         boolean      NOT NULL DEFAULT false,
    proxy_of_user_id common.d_user_id,
    state_cd         varchar(30)  NOT NULL DEFAULT 'PENDING',
    try_cnt          smallint     NOT NULL DEFAULT 0,
    next_try_at      timestamptz  NOT NULL DEFAULT now(),
    locked_by        varchar(100),
    locked_at        timestamptz,
    last_error       varchar(500),
    ins_date         timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT pk_alm_send_queue PRIMARY KEY (queue_id),
    CONSTRAINT ck_alm_send_queue_try CHECK (try_cnt >= 0),
    CONSTRAINT fk_alm_send_queue_alert
        FOREIGN KEY (alert_id) REFERENCES ax.tb_alm_alert(alert_id) ON DELETE CASCADE,
    CONSTRAINT fk_alm_send_queue_group
        FOREIGN KEY (group_id) REFERENCES ax.tb_alm_recip_group(group_id)
);
-- 밀린 건만 훑는다. 완료 행은 인덱스에 들어가지 않는다
CREATE INDEX IF NOT EXISTS ix_alm_send_queue_due
    ON ax.tb_alm_send_queue (next_try_at, queue_id) WHERE state_cd IN ('PENDING', 'FAIL');
-- 회수용 — SENDING 으로 굳은 행 찾기
CREATE INDEX IF NOT EXISTS ix_alm_send_queue_stuck
    ON ax.tb_alm_send_queue (locked_at) WHERE state_cd = 'SENDING';
-- 같은 알림·같은 사람·같은 채널·같은 승격 단계는 한 번만.
-- 엔진이 발송 직전에 죽어 재기동해도 두 번 가지 않는 것을 DB 가 보장한다.
CREATE UNIQUE INDEX IF NOT EXISTS ux_alm_send_queue_once
    ON ax.tb_alm_send_queue (alert_id, coalesce(user_id, ''), channel_cd, esc_level);

COMMENT ON TABLE  ax.tb_alm_send_queue                  IS '알림 발송 대기열. 발생(tb_alm_alert)과 발송을 떼어 SMTP 가 죽어도 알림은 남게 한다. 발송 결과는 tb_alm_send_log 에 시도마다 1행으로 쌓는다(이 표를 덮어쓰지 않는다)';
COMMENT ON COLUMN ax.tb_alm_send_queue.state_cd         IS '처리 상태. 공통코드 ALM_QUEUE_STATE';
COMMENT ON COLUMN ax.tb_alm_send_queue.next_try_at      IS '다음 시도 시각. 실패하면 백오프(1m→5m→15m→30m→60m)로 밀린다';
COMMENT ON COLUMN ax.tb_alm_send_queue.locked_by        IS '집어간 워커 식별자(호스트+스레드). locked_at 이 5분을 넘으면 죽은 워커로 보고 PENDING 으로 회수한다';
COMMENT ON COLUMN ax.tb_alm_send_queue.is_proxy         IS '당직 대리 수신 여부. 원래 수신자가 부재(tb_alm_recipient.recv_state_cd)일 때 tb_alm_duty 로 대신 받는 경우';
COMMENT ON COLUMN ax.tb_alm_send_queue.proxy_of_user_id IS '대리 수신일 때 원래 수신자';
COMMENT ON COLUMN ax.tb_alm_send_queue.body             IS '렌더링이 끝난 본문. 수신자의 부서 데이터 권한에 따라 마스킹된 상태로 들어온다 — 사람마다 내용이 다를 수 있다';

-- 8. 엔진 실행 이력 ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ax.tb_alm_eval_run (
    run_id          varchar(30)  NOT NULL,
    started_at      timestamptz  NOT NULL DEFAULT now(),
    ended_at        timestamptz,
    duration_ms     integer,
    state_cd        varchar(30)  NOT NULL,
    cond_cnt        smallint     NOT NULL DEFAULT 0,
    eval_cnt        integer      NOT NULL DEFAULT 0,
    raise_cnt       integer      NOT NULL DEFAULT 0,
    suppress_cnt    integer      NOT NULL DEFAULT 0,
    skip_cnt        integer      NOT NULL DEFAULT 0,
    queued_cnt      integer      NOT NULL DEFAULT 0,
    sent_cnt        integer      NOT NULL DEFAULT 0,
    fail_cnt        integer      NOT NULL DEFAULT 0,
    triggered_by_cd varchar(30)  NOT NULL DEFAULT 'BATCH',
    triggered_by    common.d_user_id,
    host_name       varchar(100),
    engine_version  varchar(20),
    message         varchar(2000),

    CONSTRAINT pk_alm_eval_run PRIMARY KEY (run_id)
);
CREATE INDEX IF NOT EXISTS ix_alm_eval_run_at    ON ax.tb_alm_eval_run (started_at DESC);
CREATE INDEX IF NOT EXISTS ix_alm_eval_run_state ON ax.tb_alm_eval_run (state_cd, started_at DESC);

COMMENT ON TABLE  ax.tb_alm_eval_run                 IS '알림 엔진 실행 이력. 아무 일도 없던 틱은 남기지 않는다 — 1분 주기면 하루 1,440행이 쌓여 정작 봐야 할 실행이 묻힌다. 알림·발송·실패가 하나라도 있을 때만 남기고, 조용한 구간은 시간당 1행 요약만 남긴다';
COMMENT ON COLUMN ax.tb_alm_eval_run.run_id          IS '실행 식별자. ALM-yyyyMMdd-HHmmss. 초까지 넣는다 — 분 단위로는 같은 분의 두 실행이 한 행으로 겹친다';
COMMENT ON COLUMN ax.tb_alm_eval_run.state_cd        IS '실행 결과. 공통코드 ALM_RUN_STATE. 조건 하나의 오류가 전체를 죽이지 않으므로 PARTIAL 이 흔하다';
COMMENT ON COLUMN ax.tb_alm_eval_run.eval_cnt        IS '판정한 (조건 × 대상) 수';
COMMENT ON COLUMN ax.tb_alm_eval_run.suppress_cnt    IS '중복 억제로 발송하지 않은 수';
COMMENT ON COLUMN ax.tb_alm_eval_run.skip_cnt        IS '유효 시간대·야간 미수신으로 건너뛴 수';
COMMENT ON COLUMN ax.tb_alm_eval_run.triggered_by_cd IS '실행 주체. 공통코드 ALM_RUN_TRIGGER';

-- 9. 지표 수집 정의 ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ax.tb_met_metric_collect (
    metric_id       integer      NOT NULL,
    collect_mode_cd varchar(30)  NOT NULL DEFAULT 'BUILTIN',
    collector_cd    varchar(50),
    dim_cd          varchar(30)  NOT NULL DEFAULT 'NONE',
    interval_sec    integer      NOT NULL DEFAULT 300,
    lookback_min    integer      NOT NULL DEFAULT 60,
    sql_text        text,
    use_flg         common.d_yn  NOT NULL DEFAULT 'N',
    last_run_at     timestamptz,
    last_value_at   timestamptz,
    last_error      varchar(500),
    ins_date        timestamptz  NOT NULL DEFAULT now(),
    ins_user        common.d_user_id,
    upd_date        timestamptz  NOT NULL DEFAULT now(),
    upd_user        common.d_user_id,

    CONSTRAINT pk_met_metric_collect PRIMARY KEY (metric_id),
    CONSTRAINT ck_met_metric_collect_interval CHECK (interval_sec > 0 AND lookback_min > 0),
    CONSTRAINT fk_met_metric_collect_metric
        FOREIGN KEY (metric_id) REFERENCES ax.tb_met_metric_std(metric_id) ON DELETE CASCADE
);
COMMENT ON TABLE  ax.tb_met_metric_collect                 IS '지표 실측치(ax.tb_met_metric_value)를 채우는 방법. 지금 그 표가 0행이라 알림 조건이 비교할 값이 없다 — 그 구멍을 메우는 정의다';
COMMENT ON COLUMN ax.tb_met_metric_collect.collect_mode_cd IS '수집 방식. 공통코드 MET_COLLECT_MODE. 1단계는 BUILTIN 만 쓴다';
COMMENT ON COLUMN ax.tb_met_metric_collect.collector_cd    IS 'BUILTIN 일 때 Kotlin 구현체 키. 비우면 tb_met_metric_std.metric_cd 를 쓴다';
COMMENT ON COLUMN ax.tb_met_metric_collect.dim_cd          IS '값을 쪼개는 단위. 공통코드 ALM_SCOPE_DIM. ''EQPT'' 면 설비마다 값이 따로 쌓인다';
COMMENT ON COLUMN ax.tb_met_metric_collect.lookback_min    IS '한 번 수집할 때 거슬러 보는 구간(분). 이관이 늦은 원천을 메우기 위해 주기보다 넉넉히 잡는다';
COMMENT ON COLUMN ax.tb_met_metric_collect.sql_text         IS '집계 SQL (collect_mode_cd=''SQL'' 전용). 2단계 기능이라 지금은 쓰지 않는다 — 이 컬럼에 쓰기 권한을 주는 것은 DB 에서 임의 SQL 을 돌릴 권한을 주는 것과 같다. 읽기 전용 롤 분리·SELECT 단일문 검증·감사 로그가 갖춰진 뒤에 연다';
COMMENT ON COLUMN ax.tb_met_metric_collect.use_flg          IS '수집 스위치. 기본 ''N''(미수집) — 구현체가 붙고 값을 확인한 뒤 ''Y'' 로 켠다. V33 의 2단계 스위치와 같은 뜻이다';
COMMENT ON COLUMN ax.tb_met_metric_collect.last_value_at    IS '마지막으로 값이 쌓인 시각. interval_sec × 3 보다 오래되면 엔진은 그 지표를 판정하지 않는다 — 멈춘 수집의 낡은 값으로 알림을 내면 이미 끝난 이상이 계속 나가거나 진짜 이상을 정상으로 본다';

-- 9-1. 알림 대상 지표만 등록한다 (미수집 상태) ---------------------------------------------
--      apply_alert=true 인 지표는 EQPT_UPTIME_RATE 하나뿐이다. 나머지 둘은 알림 대상이 아니고
--      (PROD_DAY_TARGET 은 생산관리팀이 손으로 적재한다) 수집기도 정해지지 않았다.
--      짐작해 넣으면 엉뚱한 값이 쌓이므로 넣지 않는다.
INSERT INTO ax.tb_met_metric_collect (metric_id, collect_mode_cd, collector_cd, dim_cd, interval_sec, lookback_min, use_flg, ins_user, upd_user)
SELECT m.metric_id, 'BUILTIN', m.metric_cd, 'EQPT', 300, 60, 'N', 'V35', 'V35'
  FROM ax.tb_met_metric_std m
 WHERE m.metric_cd = 'EQPT_UPTIME_RATE'
ON CONFLICT (metric_id) DO NOTHING;

-- 10. 코드 참조 등록 --------------------------------------------------------------------
--     NULL 을 허용하지 않는 컬럼은 nullable_flg='N' 으로 둔다.
INSERT INTO ax.tb_sys_code_ref (target_table, target_column, group_cd, nullable_flg) VALUES
 ('tb_alm_cond',        'scope_dim_cd',    'ALM_SCOPE_DIM',    'N'),
 ('tb_alm_cond_target', 'target_dim_cd',   'ALM_SCOPE_DIM',    'N'),
 ('tb_alm_cond_state',  'state_cd',        'ALM_COND_STATE',   'N'),
 ('tb_alm_send_queue',  'state_cd',        'ALM_QUEUE_STATE',  'N'),
 ('tb_alm_send_queue',  'channel_cd',      'ALM_CHANNEL',      'N'),
 ('tb_alm_eval_run',    'state_cd',        'ALM_RUN_STATE',    'N'),
 ('tb_alm_eval_run',    'triggered_by_cd', 'ALM_RUN_TRIGGER',  'N'),
 ('tb_met_metric_collect', 'collect_mode_cd', 'MET_COLLECT_MODE', 'N'),
 ('tb_met_metric_collect', 'dim_cd',          'ALM_SCOPE_DIM',    'N')
ON CONFLICT (target_table, target_column) DO NOTHING;
