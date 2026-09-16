# 이상 알림 발송 엔진 설계 (SY-04 `/system/alert-condition` 실동작화)

작성 2026-09-16 · 대상 DB `dwjedb`(localhost:5432, 스키마 `ax`) · 대상 앱 `API`(Kotlin/Spring Boot 3.4)

---

## 0. 한 줄 결론

**표는 이미 거의 다 있다. 없는 것은 「돌리는 것」과 「상태를 남기는 것」이다.**
조건·수신자·알림·발송로그 표(`tb_alm_*` 11개)는 설계가 끝나 있고 행이 0건이다.
새로 만들 것은 ① 평가 상태 표 ② 발송 대기열 ③ 엔진 실행 이력 ④ 지표 수집 정의, 그리고 이 넷을 1분 주기로 도는 **5단계 파이프라인**이다.

> **적용 현황 (2026-09-16)** — 이 설계의 **DB 부분은 `V35__alm_engine.sql` 로 로컬(`dwjedb`)에 적용 완료**.
> 표 5개·컬럼 10개·공통코드 6그룹 23코드·`ALM_*` attr 채움까지 반영했고, 재실행 안전성과 판정 흐름(지속·억제·중복 차단·연쇄 삭제)을 확인했다.
> **엔진 구현도 완료** — `004. 개발/Alert_Engine` (Kotlin/Spring Boot 배치, `alert-engine.jar`).
> 수집·평가·발생·발송·승격 5단계가 로컬에서 실제로 돌아 알림이 발생하고 메일(LOG 모드)까지 나갔다.
> 남은 것은 화면 반영(§8)과 API 추가(§7). 운영 DB 적용은 요청자 확인 후.

---

## 1. 현황 — 확인한 사실

### 1-1. 이미 있는 것 (그대로 쓴다)

| 표 | 역할 | 행수 |
|---|---|---:|
| `tb_alm_cond` | 조건 본문(지표·비교·임계·지속·대상·시간대·중복억제·`msg_template`·`blind_field_key`) | 0 |
| `tb_alm_cond_channel` / `tb_alm_cond_group` / `tb_alm_cond_escalation` | 조건 ↔ 채널·수신그룹·에스컬레이션 규칙 | 0 |
| `tb_alm_recip_group` (+`_member`, `_channel`) · `tb_alm_recipient` | 수신 그룹·멤버·연락처(`night_recv`, `recv_state_cd`) | 0 |
| `tb_alm_duty` | 당직·대리 수신 (`main_user_id` / `sub_user_id`) | 0 |
| `tb_alm_escalation_rule` | `after_min` 경과 후 `to_group_id` 로 승격 | 3 |
| `tb_alm_alert` | 발생한 알림 (`dedup_key`, `esc_level`, `ack_state_cd` 컬럼 보유) | 0 |
| `tb_alm_send_log` | 발송 기록 (`is_proxy`, `proxy_of_user_id`, `esc_level` 보유) | 0 |
| `tb_met_metric_std` | 지표 기준(SY-13) — `std/warn/crit_val`, `apply_alert` | 3 |
| `vw_alert_context` | 알림 1건의 설비·품목·지표 맥락 뷰 | — |

공통코드도 값까지 들어 있다 — `ALM_OP`(GE/GT/LE/LT/EQ/RATE) · `ALM_DURATION`(IMMEDIATE/C5M/C10M/C30M/MA120/DAY_CLOSE/DAY_ONCE) · `ALM_WINDOW`(ALWAYS/D0820/D0618/WORKDAY/ONCE) · `ALM_DEDUP`(NONE/M15/M30/M60/M120/DAY_ONCE) · `ALM_SEND_RESULT`(**SENT/FAIL/SUPPRESSED/SKIPPED**).
마지막 것이 중요하다. 스키마 설계자가 **「억제됨」·「시간대 제외」도 발송 로그에 남기도록** 이미 정해 뒀다. 엔진은 그 약속을 지키면 된다.

### 1-2. 없는 것 (이번에 만든다)

1. **판정할 값이 없다.** `tb_met_metric_value` 0행, `tb_met_metric_source` 0행. 조건이 `metric_id` 를 가리켜도 비교할 실측치가 안 들어온다.
2. **평가 상태를 둘 곳이 없다.** `tb_alm_cond` 에 `last_eval_at`·연속 위반 시작 시각·마지막 발송 시각이 없다. → 「10분 연속」도 「30분 중복 억제」도 계산 불가.
3. **실제 발송이 없다.** `AlertConfigService.testSendCondition()` 은 `tb_alm_send_log` 에 `'SENT'` 를 **INSERT 만** 한다. 메일은 나가지 않는다. 재시도·실패 처리도 없다.
4. **주기 실행이 없다.** API 전체에 `@Scheduled` 가 0건이다.
5. **대상 범위가 이름뿐이다.** `ALM_TARGET.PICK`(개별 설비 선택)이 코드로만 있고 선택 목록을 담을 표가 없다.

### 1-3. 참고할 선례 — `MES_migration_engine`

같은 저장소 계열에 이미 「주기적으로 도는 엔진」이 있다. 그 방식을 그대로 따른다.

- `TaskScheduler` + `CronTrigger`(또는 고정 지연) 로 상주
- **프로세스 내 `AtomicBoolean` + PostgreSQL advisory lock** 이중 잠금 — 정기 틱과 수동 실행이 겹치지 않는다
- 실행 이력 표 `tb_sync_run` / 작업 표 `tb_sync_job`(`ix_sync_job_pending` 부분 인덱스)
- 화면의 「지금 실행」을 위해 **DB 큐를 60초 주기로 폴링**
- **아무 일도 없던 폴링은 로그·이력을 남기지 않는다** (하루 수천 행이 쌓여 정작 볼 이력이 묻히므로)

이 설계는 표 이름만 `alm_` 으로 바꿔 같은 골격을 쓴다.

---

## 2. 설계 원칙

1. **판정 규칙의 숫자는 코드가 아니라 데이터에 둔다.** `M30`→30분, `C10M`→600초 를 Kotlin `when` 에 박지 않고 `tb_sys_code.attr1`(지금 전부 비어 있음)에 넣는다. 중복 억제 창을 45분으로 바꾸는 일이 배포가 되어선 안 된다.
2. **알림 발생과 발송을 분리한다.** 발생(`tb_alm_alert`)은 트랜잭션 안에서 확정하고, 발송은 대기열(outbox)로 넘긴다. SMTP가 죽어도 알림은 남는다.
3. **기록 표는 덮어쓰지 않는다.** `tb_alm_send_log` 는 시도마다 1행 append. 재시도 상태는 대기열 표가 진다.
4. **엔진이 두 번 돌아도 결과는 같다.** 대기열에 `(alert_id, user_id, channel_cd, esc_level)` 유니크를 걸어 크래시 후 재기동 시 중복 발송을 DB가 막는다.
5. **테스트 발송은 실제 경로를 탄다.** 지금처럼 로그만 남기면 「테스트는 됐는데 실제로 안 온다」가 그대로 남는다.

---

## 3. 전체 구조

```
                    ┌─ 60초 틱 (advisory lock 으로 단일 실행 보장) ─────────────┐
 mes.tb_pop_*       │                                                          │
 ax.tb_prod_*  ──▶ ①수집 ──▶ ax.tb_met_metric_value                            │
 MSSQL EDGE         │            │                                             │
                    │            ▼                                             │
                    │        ②평가 ── op·threshold 비교 + 지속(duration) 판정   │
                    │            │    상태: ax.tb_alm_cond_state (조건×대상)    │
                    │            ▼                                             │
                    │        ③발생 ── 유효시간대(window)·중복억제(dedup) 통과?  │
                    │            │    ├ 통과  → tb_alm_alert INSERT + 대기열 적재 │
                    │            │    ├ 억제  → 기존 alert 에 hit_cnt++          │
                    │            │    │         + send_log 'SUPPRESSED'         │
                    │            │    └ 시간대 밖 → send_log 'SKIPPED'           │
                    │            ▼                                             │
                    │        ④발송 ── tb_alm_send_queue (FOR UPDATE SKIP LOCKED)│
                    │            │    채널 어댑터(MAIL/POPUP/SMS/MSG) → 재시도   │
                    │            │    결과 → tb_alm_send_log (SENT/FAIL)        │
                    │            ▼                                             │
                    │        ⑤승격 ── OPEN 인 채 after_min 경과 → 상위 그룹 재발송│
                    └──────────────────────────────────────────────────────────┘
                                 실행 이력 → ax.tb_alm_eval_run
```

**엔진을 어디에 둘 것인가 — API 앱 내장(권장).**
이유: 엔진 코드의 대부분이 알림 도메인 자체(수신자 해석·마스킹·메시지 템플릿·감사 로그)라 `AlertConfigRepository`·`VerificationMailSender`·`AuthorizationService` 를 그대로 쓴다. 작업량도 조건 수십 건 × 분당 1회로 API 응답에 영향이 없다.
다만 `app.alert-engine.enabled` 플래그와 advisory lock으로 **켜고 끄기·인스턴스 다중화**를 처음부터 가능하게 만들어, 부하가 커지면 `MES_migration_engine` 처럼 별도 프로세스로 **코드 변경 없이** 떼어낼 수 있게 한다. (① 수집 단계가 무거워지면 그 단계만 먼저 이관 엔진 쪽으로 옮기는 것이 자연스럽다.)

---

## 4. DB 설계

마이그레이션 파일은 기존 규칙대로 `src/main/resources/db/V35__alm_engine.sql` + `db/rollback/V35__down.sql`. 두 번 실행해도 안전하게(`IF NOT EXISTS`) 쓴다.

### 4-1. 공통코드에 숫자 의미를 채운다 (신규 표 없음)

```sql
-- 중복 억제: attr1 = 분 (DAY_ONCE 는 -1 = 달력일 1회)
UPDATE ax.tb_sys_code SET attr1='0'   WHERE group_cd='ALM_DEDUP' AND code='NONE';
UPDATE ax.tb_sys_code SET attr1='15'  WHERE group_cd='ALM_DEDUP' AND code='M15';
UPDATE ax.tb_sys_code SET attr1='30'  WHERE group_cd='ALM_DEDUP' AND code='M30';
UPDATE ax.tb_sys_code SET attr1='60'  WHERE group_cd='ALM_DEDUP' AND code='M60';
UPDATE ax.tb_sys_code SET attr1='120' WHERE group_cd='ALM_DEDUP' AND code='M120';
UPDATE ax.tb_sys_code SET attr1='-1'  WHERE group_cd='ALM_DEDUP' AND code='DAY_ONCE';

-- 지속 조건: attr1 = 초, attr2 = 판정 방식 (CONT 연속 / AVG 이동평균 / CLOSE 마감시점)
UPDATE ax.tb_sys_code SET attr1='0',    attr2='CONT'  WHERE group_cd='ALM_DURATION' AND code='IMMEDIATE';
UPDATE ax.tb_sys_code SET attr1='300',  attr2='CONT'  WHERE group_cd='ALM_DURATION' AND code='C5M';
UPDATE ax.tb_sys_code SET attr1='600',  attr2='CONT'  WHERE group_cd='ALM_DURATION' AND code='C10M';
UPDATE ax.tb_sys_code SET attr1='1800', attr2='CONT'  WHERE group_cd='ALM_DURATION' AND code='C30M';
UPDATE ax.tb_sys_code SET attr1='7200', attr2='AVG'   WHERE group_cd='ALM_DURATION' AND code='MA120';
UPDATE ax.tb_sys_code SET attr1='0',    attr2='CLOSE' WHERE group_cd='ALM_DURATION' AND code IN ('DAY_CLOSE','DAY_ONCE');

-- 비교 연산자: attr1 = SQL 연산자 (엔진이 문자열 when 을 쓰지 않도록)
UPDATE ax.tb_sys_code SET attr1='>='  WHERE group_cd='ALM_OP' AND code='GE';
UPDATE ax.tb_sys_code SET attr1='>'   WHERE group_cd='ALM_OP' AND code='GT';
UPDATE ax.tb_sys_code SET attr1='<='  WHERE group_cd='ALM_OP' AND code='LE';
UPDATE ax.tb_sys_code SET attr1='<'   WHERE group_cd='ALM_OP' AND code='LT';
UPDATE ax.tb_sys_code SET attr1='='   WHERE group_cd='ALM_OP' AND code='EQ';
UPDATE ax.tb_sys_code SET attr1='PCT' WHERE group_cd='ALM_OP' AND code='RATE';   -- 직전 대비 변화율(%)

-- 유효 시간대: attr1 = 시작, attr2 = 종료 (WORKDAY 는 평일 판정 별도)
UPDATE ax.tb_sys_code SET attr1='00:00', attr2='24:00' WHERE group_cd='ALM_WINDOW' AND code='ALWAYS';
UPDATE ax.tb_sys_code SET attr1='08:00', attr2='20:00' WHERE group_cd='ALM_WINDOW' AND code='D0820';
UPDATE ax.tb_sys_code SET attr1='06:00', attr2='18:00' WHERE group_cd='ALM_WINDOW' AND code='D0618';
UPDATE ax.tb_sys_code SET attr1='08:00', attr2='17:30' WHERE group_cd='ALM_WINDOW' AND code='WORKDAY';
-- ONCE(지정 시각 1회)는 조건마다 시각이 달라 tb_alm_cond.window_time 을 본다.
```

> `attr1`/`attr2` 는 전부 비어 있어 덮어쓸 값이 없다. `D0820` 같은 **코드 문자열을 파싱해서 시간을 얻는 방식은 쓰지 않는다** — 코드명을 바꾸는 순간 판정이 조용히 깨진다.

### 4-2. 기존 표 보강

```sql
ALTER TABLE ax.tb_alm_cond
  ADD COLUMN IF NOT EXISTS scope_dim_cd      varchar(30)  NOT NULL DEFAULT 'NONE',
      -- 평가를 쪼개는 단위: NONE(조건 전체 1건) | EQPT | WC | ITEM | MOLD | PRODUCT
      -- '설비별 가동률' 은 EQPT — 설비 1대가 넘으면 그 설비만 알림이 난다.
  ADD COLUMN IF NOT EXISTS window_time       time,                       -- ALM_WINDOW='ONCE' 용 지정 시각
  ADD COLUMN IF NOT EXISTS eval_interval_sec integer      NOT NULL DEFAULT 60,
  ADD COLUMN IF NOT EXISTS auto_close_flg    common.d_yn  NOT NULL DEFAULT 'N',  -- 정상 복귀 시 알림 자동 종결
  ADD COLUMN IF NOT EXISTS last_eval_at      timestamptz;                -- 화면 표시용(정밀 판정은 상태 표)

ALTER TABLE ax.tb_alm_alert
  ADD COLUMN IF NOT EXISTS scope_key   varchar(100),   -- 어느 설비/품목에서 났는지 (상태 표와 잇는 키)
  ADD COLUMN IF NOT EXISTS hit_cnt     integer NOT NULL DEFAULT 1,  -- 억제 창 안에서 재발한 횟수
  ADD COLUMN IF NOT EXISTS last_hit_at timestamptz,
  ADD COLUMN IF NOT EXISTS resolved_at timestamptz;    -- 값이 정상으로 돌아온 시각
```

`hit_cnt` 를 두는 이유: 중복 억제를 「알림을 아예 안 만든다」로 처리하면 **30분 동안 12번 터진 사실이 사라진다.** 억제 창 안의 재발은 기존 알림 행의 `hit_cnt` 를 올리고 `tb_alm_send_log` 에 `SUPPRESSED` 를 남긴다. 목록은 1건으로 깨끗하고, 근거는 남는다.

### 4-3. 신규 표 ① 평가 상태 — 엔진의 심장

```sql
CREATE TABLE IF NOT EXISTS ax.tb_alm_cond_state (
  cond_id       integer      NOT NULL REFERENCES ax.tb_alm_cond(cond_id) ON DELETE CASCADE,
  scope_key     varchar(100) NOT NULL DEFAULT '*',    -- 설비코드 등. scope_dim_cd='NONE' 이면 '*'
  state_cd      varchar(30)  NOT NULL DEFAULT 'NORMAL',  -- NORMAL | PENDING | BREACH
  last_value    numeric(18,6),
  last_eval_at  timestamptz,
  breach_since  timestamptz,                          -- 연속 위반 시작 — 지속 조건의 유일한 근거
  breach_cnt    integer      NOT NULL DEFAULT 0,
  last_alert_id bigint       REFERENCES ax.tb_alm_alert(alert_id) ON DELETE SET NULL,
  last_alert_at timestamptz,                          -- 중복 억제 창의 기준점
  suppress_cnt  integer      NOT NULL DEFAULT 0,
  next_eval_at  timestamptz  NOT NULL DEFAULT now(),
  upd_date      timestamptz  NOT NULL DEFAULT now(),
  CONSTRAINT pk_alm_cond_state PRIMARY KEY (cond_id, scope_key)
);
CREATE INDEX IF NOT EXISTS ix_alm_cond_state_due ON ax.tb_alm_cond_state (next_eval_at);
```

- `PENDING` = 임계는 넘었지만 지속 조건(10분 연속 등)을 아직 못 채운 상태. 이 상태가 없으면 「10분 연속」을 구현할 수 없다.
- 값이 정상으로 돌아오면 `breach_since=NULL`, `state_cd='NORMAL'` 로 되돌린다. **중간에 한 번이라도 정상이면 연속은 끊긴다.**
- 중복 억제는 이 표의 `last_alert_at` 하나로 계산한다 — 별도 억제 표가 필요 없다.

### 4-4. 신규 표 ② 발송 대기열(outbox)

```sql
CREATE TABLE IF NOT EXISTS ax.tb_alm_send_queue (
  queue_id         bigint       GENERATED BY DEFAULT AS IDENTITY,
  alert_id         bigint       NOT NULL REFERENCES ax.tb_alm_alert(alert_id) ON DELETE CASCADE,
  group_id         integer,
  user_id          common.d_user_id,
  channel_cd       varchar(30)  NOT NULL,
  dest_addr        varchar(200),
  subject          varchar(300),
  body             text,
  esc_level        smallint     NOT NULL DEFAULT 0,
  is_proxy         boolean      NOT NULL DEFAULT false,
  proxy_of_user_id common.d_user_id,
  state_cd         varchar(30)  NOT NULL DEFAULT 'PENDING',  -- PENDING|SENDING|DONE|FAIL|DEAD
  try_cnt          smallint     NOT NULL DEFAULT 0,
  next_try_at      timestamptz  NOT NULL DEFAULT now(),
  locked_by        varchar(100),
  locked_at        timestamptz,
  last_error       varchar(500),
  ins_date         timestamptz  NOT NULL DEFAULT now(),
  CONSTRAINT pk_alm_send_queue PRIMARY KEY (queue_id)
);
-- 밀린 건만 훑는다 (완료 행은 인덱스에 들어가지 않는다)
CREATE INDEX IF NOT EXISTS ix_alm_send_queue_due
    ON ax.tb_alm_send_queue (next_try_at, queue_id) WHERE state_cd IN ('PENDING','FAIL');
-- 같은 알림·같은 사람·같은 채널·같은 승격 단계는 한 번만. 엔진이 재기동해도 두 번 가지 않는다.
CREATE UNIQUE INDEX IF NOT EXISTS ux_alm_send_queue_once
    ON ax.tb_alm_send_queue (alert_id, coalesce(user_id,''), channel_cd, esc_level);
```

**대기열을 `tb_alm_send_log` 와 합치지 않는 이유.** 재시도 루프가 로그 행을 `UPDATE` 하면 「3번째 시도에 나갔다」가 덮여 사라진다. 대기열은 *지금 해야 할 일*, 로그는 *시도할 때마다 1행*. 역할이 다르므로 표를 나눈다.

### 4-5. 신규 표 ③ 엔진 실행 이력

```sql
CREATE TABLE IF NOT EXISTS ax.tb_alm_eval_run (
  run_id          varchar(30)  NOT NULL,        -- ALM-20260916-140500
  started_at      timestamptz  NOT NULL DEFAULT now(),
  ended_at        timestamptz,
  duration_ms     integer,
  state_cd        varchar(30)  NOT NULL,        -- RUNNING | OK | PARTIAL | FAIL
  cond_cnt        smallint     NOT NULL DEFAULT 0,
  eval_cnt        integer      NOT NULL DEFAULT 0,
  raise_cnt       integer      NOT NULL DEFAULT 0,
  suppress_cnt    integer      NOT NULL DEFAULT 0,
  skip_cnt        integer      NOT NULL DEFAULT 0,
  queued_cnt      integer      NOT NULL DEFAULT 0,
  sent_cnt        integer      NOT NULL DEFAULT 0,
  fail_cnt        integer      NOT NULL DEFAULT 0,
  triggered_by_cd varchar(30)  NOT NULL DEFAULT 'BATCH',   -- BATCH | MANUAL | TEST
  triggered_by    common.d_user_id,
  host_name       varchar(100),
  engine_version  varchar(20),
  message         varchar(2000),
  CONSTRAINT pk_alm_eval_run PRIMARY KEY (run_id)
);
CREATE INDEX IF NOT EXISTS ix_alm_eval_run_at ON ax.tb_alm_eval_run (started_at DESC);
```

**아무 일도 없던 틱은 행을 남기지 않는다.** 1분 주기면 하루 1,440행이 쌓여 정작 볼 실행이 묻힌다. 알림 발생·발송·실패가 하나라도 있을 때만 남기고, 조용한 구간은 **1시간에 1행** 요약만 남긴다(이관 엔진의 큐 폴링과 같은 규칙).

### 4-6. 신규 표 ④ 지표 수집 정의

```sql
CREATE TABLE IF NOT EXISTS ax.tb_met_metric_collect (
  metric_id       integer      NOT NULL REFERENCES ax.tb_met_metric_std(metric_id) ON DELETE CASCADE,
  collect_mode_cd varchar(30)  NOT NULL DEFAULT 'BUILTIN',  -- BUILTIN | SQL(2단계)
  collector_cd    varchar(50),                -- BUILTIN 구현체 키 (기본: metric_cd)
  dim_cd          varchar(30)  NOT NULL DEFAULT 'NONE',     -- 값을 쪼개는 단위 (EQPT/WC/ITEM/…)
  interval_sec    integer      NOT NULL DEFAULT 300,
  lookback_min    integer      NOT NULL DEFAULT 60,
  sql_text        text,                       -- SQL 모드 전용(2단계). 읽기 전용 롤로만 실행
  use_flg         common.d_yn  NOT NULL DEFAULT 'Y',
  last_run_at     timestamptz,
  last_value_at   timestamptz,
  last_error      varchar(500),
  ins_date        timestamptz  NOT NULL DEFAULT now(),
  ins_user        common.d_user_id,
  upd_date        timestamptz  NOT NULL DEFAULT now(),
  upd_user        common.d_user_id,
  CONSTRAINT pk_met_metric_collect PRIMARY KEY (metric_id)
);
```

**1단계는 `BUILTIN` 만 쓴다.** 집계 SQL을 표에 넣어 화면에서 편집하게 하면(= V33 「설정을 데이터로」의 연장) 지표 추가에 배포가 필요 없어지지만, 그 표에 쓰기 권한을 가진 사람이 **DB에서 임의 SQL을 돌릴 수 있게 된다.** `sql_text` 는 컬럼만 만들어 두고, 2단계에서 ⓐ 전용 읽기 전용 롤 ⓑ `statement_timeout` ⓒ `SELECT` 단일문 검증 ⓓ 전산팀 전용 + 감사 로그 를 갖춘 뒤 연다.

### 4-7. 신규 표 ⑤ 개별 대상 선택 (`ALM_TARGET='PICK'`)

```sql
CREATE TABLE IF NOT EXISTS ax.tb_alm_cond_target (
  cond_id       integer     NOT NULL REFERENCES ax.tb_alm_cond(cond_id) ON DELETE CASCADE,
  target_dim_cd varchar(30) NOT NULL,     -- EQPT | WC | ITEM | MOLD | PRODUCT
  target_cd     varchar(50) NOT NULL,
  CONSTRAINT pk_alm_cond_target PRIMARY KEY (cond_id, target_dim_cd, target_cd)
);
```

### 4-8. 시계열 보존

`tb_met_metric_value` 는 지표 × 설비 × 분 단위로 쌓인다. 설비 50대 × 지표 5개 × 5분 주기 = **일 72,000행**.

- `measured_at` 기준 **월 RANGE 파티션** 으로 만들고, 다음 달 파티션을 엔진이 미리 만든다.
- 보존 12개월. 초과 파티션은 `DETACH` 후 삭제(월 1회) — 일 마감·월 누계 지표가 전년 대비를 쓰므로 90일은 짧다.
- `tb_alm_send_queue` 의 `DONE` 행은 7일 후 삭제(로그는 `tb_alm_send_log` 에 영구 보존).

---

## 5. 판정 규칙 상세

### 5-1. 한 틱에서 하는 일 (의사 코드)

```
tick(60s):
  if !advisoryLock(ALM_ENGINE_KEY): return          # 다른 인스턴스가 돌고 있다
  run = beginRun()
  ① collect()   : use_flg='Y' & 주기 도래한 지표 → metric_value UPSERT
  ② evaluate()  : next_eval_at <= now 인 (조건 × 대상) 를 훑는다
  ③ raise()     : 위반 확정 건 → 시간대·억제 판정 → alert / suppress / skip
  ④ dispatch()  : 대기열 워커 (SKIP LOCKED)
  ⑤ escalate()  : OPEN + after_min 경과 → 상위 그룹
  endRun(run)   # 변화가 없으면 run 행을 남기지 않는다
```

### 5-2. 평가 대상 전개 (`scope_dim_cd` × `target_scope_cd`)

| `target_scope_cd` | 전개 대상 |
|---|---|
| `ALL_EQPT` | `mes.tb_md_eqpt` 사용 중 설비 전체 |
| `PRESS` / `AOI` | 워크센터 구분으로 필터한 설비 |
| `ALL_MODEL` / `ALL_PROC` / `ALL_CUST` / `MATERIAL` | 각 마스터 전체 |
| `PICK` | `tb_alm_cond_target` 에 담긴 목록 |

`scope_dim_cd='NONE'` 이면 대상 전체를 묶어 값 1개로 평가하고 `scope_key='*'`. `EQPT` 면 설비마다 상태 1행이 생겨 **설비별로 따로 터지고 따로 억제된다.**

### 5-3. 비교 · 지속 판정

1. `metric_value` 에서 `lookback` 구간의 값을 읽는다.
   - `attr2='CONT'` → 가장 최근 값
   - `attr2='AVG'` → 구간 이동평균 (MA120 = 최근 120분)
   - `attr2='CLOSE'` → 일 마감 확정값 1건
2. `op.attr1` 연산자로 `threshold_val` 과 비교. `RATE` 는 직전 값 대비 변화율(%)을 비교값으로 쓴다.
3. 위반이면
   - `breach_since` 가 비어 있으면 지금으로 채우고 `state_cd='PENDING'`
   - `now - breach_since >= duration.attr1(초)` 이면 `state_cd='BREACH'` → ③단계로
4. 정상이면 `breach_since=NULL`, `state_cd='NORMAL'`. `auto_close_flg='Y'` 이고 직전 알림이 `OPEN` 이면 `resolved_at` 을 찍는다(확인 처리는 사람이 한다 — 자동으로 `CLOSED` 로 바꾸지 않는다).

### 5-4. 유효 시간대 · 중복 억제 (발생 단계)

판정 순서는 **① 알림 생성 → ② 시간대 → ③ 억제** 다. 시간대 밖이라고 알림 자체를 버리면 새벽에 난 이상이 아침에 아무 흔적도 없다.

| 상황 | `tb_alm_alert` | `tb_alm_send_log` | 대기열 |
|---|---|---|---|
| 정상 발송 | 신규 1행 | 발송 후 `SENT`/`FAIL` | 적재 |
| 억제 창 안 재발 | 기존 행 `hit_cnt++`, `last_hit_at` | `SUPPRESSED` 1행 | 적재 안 함 |
| 유효 시간대 밖 | 신규 1행 | `SKIPPED` 1행 | 적재 안 함 |
| 수신자 야간 미수신 | 신규 1행 | 그 수신자만 `SKIPPED` | 그 수신자만 제외 |

- 억제 창 = `dedup.attr1` 분. `DAY_ONCE(-1)` 는 `last_alert_at::date = today` 로 판정.
- `dedup_key = cond_id || '|' || scope_key` (기존 `ix_alm_alert_dedup` 부분 인덱스를 그대로 쓴다).
- `severity_cd='CRIT'` 는 시간대 밖에도 보낼지를 조건별로 정할 수 있어야 한다 → `tb_alm_cond` 에 `ignore_window_flg` 를 둘지는 **결정 필요**(§9).

### 5-5. 수신자 해석

```
조건 ─ tb_alm_cond_group ─ 수신그룹
                            ├ tb_alm_recip_group_member ─ tb_alm_recipient
                            │    recv_state_cd='RECV' 인 사람만 (부재는 제외)
                            └ 채널 = 조건 채널 ∩ 그룹 채널
                                 MAIL→email · SMS→mobile_no · MSG→messenger_id · POPUP→DB
야간(그룹.night_recv=false & 수신자.night_recv=false) 이고 지금이 야간이면 그 사람은 SKIPPED
연락처가 비어 있으면 FAIL('연락처 없음') — 조용히 건너뛰지 않는다
보낼 사람이 0명이면 FAIL('수신 상태인 멤버가 없음') — 등록만 하고 아무도 안 받는 상태를 화면에서 보이게
```

> **당직 대리 수신은 하지 않는다 (2026-09-16 변경).** 이 설계서 초안은 부재자를 `tb_alm_duty` 의 대리자로
> 바꾸도록 적었으나, 같은 날 V36 이 SY-05 화면의 「당번·승격」 탭과 함께 `ax.tb_alm_duty` 표를 제거했다.
> 엔진은 부재자를 그냥 제외한다. `tb_alm_send_log` · `tb_alm_send_queue` 의 `is_proxy` · `proxy_of_user_id`
> 컬럼은 남아 있지만 지금은 항상 `false` · `null` 이다 — 당번이 다시 생기면 수신자 질의만 고치면 된다.
> 승격 규칙(`tb_alm_escalation_rule`)은 그대로 살아 있어 §5-7 은 유효하다.

### 5-6. 메시지 생성과 마스킹

- `tb_alm_cond.msg_template` 치환 변수: `{{condNm}} {{metricNm}} {{value}} {{unit}} {{op}} {{threshold}} {{scope}} {{eqptNm}} {{itemNm}} {{occurredAt}} {{severity}} {{link}}`
- `{{link}}` 는 `/alert/list?alertId=…` 딥링크.
- **마스킹은 수신자마다 다르다.** `tb_alm_cond.blind_field_key` 가 걸린 조건은 수신자의 부서 데이터 권한(`vw_user_data_perm`)을 확인해 본문 값을 가린다. 메일은 화면과 달리 권한 검사를 통과해서 나가는 경로가 아니므로, 여기서 안 가리면 **데이터 접근 권한 설계가 메일로 새어 나간다.**

### 5-7. 에스컬레이션

```sql
-- 승격 대상: 확인되지 않은 채 after_min 이 지났고, 아직 그 단계로 안 올라간 알림
SELECT a.alert_id, r.esc_rule_id, r.esc_level, r.to_group_id
  FROM ax.tb_alm_alert a
  JOIN ax.tb_alm_cond_escalation ce ON ce.cond_id = a.cond_id AND ce.is_on
  JOIN ax.tb_alm_escalation_rule r  ON r.esc_rule_id = ce.esc_rule_id AND r.use_flg = 'Y'
 WHERE a.ack_state_cd = 'OPEN'
   AND a.resolved_at IS NULL
   AND a.esc_level < r.esc_level
   AND now() >= a.occurred_at + make_interval(mins => r.after_min)
   AND (r.severity_filter IS NULL OR r.severity_filter = a.severity_cd);
```
승격 시 `to_group_id` 로 대기열을 적재하고 `a.esc_level = r.esc_level` 로 올린다. 대기열 유니크 키에 `esc_level` 이 들어 있어 같은 단계가 두 번 가지 않는다.

---

## 6. 개발 설계 (API)

### 6-1. 패키지

```
com.dwje.api.
├─ service/alert/engine/
│   ├─ AlertEngineScheduler.kt     틱 · advisory lock · run 이력 · 수동 트리거 수용
│   ├─ MetricCollectService.kt     ① 수집
│   ├─ collector/MetricCollector.kt        인터페이스 (metricCd, collect(from,to): List<MetricPoint>)
│   ├─ collector/EqptUptimeCollector.kt    EQPT_UPTIME_RATE (mes.tb_pop_* · downtime)
│   ├─ collector/DefectRateCollector.kt    공정 불량률
│   ├─ collector/AchieveRateCollector.kt   PROD_ACHIEVE_RATE
│   ├─ ConditionEvaluator.kt       ② 대상 전개 · 비교 · 지속 판정 · 상태 UPSERT
│   ├─ AlertRaiser.kt              ③ 시간대 · 억제 · alert INSERT · 대기열 적재
│   ├─ SendDispatcher.kt           ④ SKIP LOCKED 워커 · 재시도 · send_log 확정
│   ├─ EscalationRunner.kt         ⑤ 승격
│   ├─ AlertMessageRenderer.kt     템플릿 치환 + 수신자별 마스킹
│   └─ channel/{MailChannel,PopupChannel,SmsChannel,MessengerChannel}.kt
├─ repository/AlertEngineRepository.kt     (JdbcTemplate — 기존 규칙대로 Native SQL)
└─ config/AlertEngineProperties.kt
```

채널은 `interface AlertChannel { val code: String; fun send(msg: OutboundMessage) }` 하나로 두고, SMS·메신저는 1단계에서 `LOG` 구현만 등록한다. 연동처가 정해지면 구현체만 갈아 끼운다.

### 6-2. 설정 (`application.yml`)

```yaml
app:
  alert-engine:
    enabled: true                  # local 기본 true, 운영 인스턴스 다중화 시에도 advisory lock 으로 단일 실행
    tick: 60s
    timezone: Asia/Seoul
    advisory-lock-key: 480401
    max-cond-per-tick: 500         # 폭주 방지 상한
    collect:
      enabled: true
      statement-timeout: 20s
    dispatch:
      workers: 2
      batch-size: 50
      max-try: 5
      backoff: [1m, 5m, 15m, 30m, 60m]
    channel:
      mail: LOG                    # LOG | SMTP  (운영·개발은 SMTP)
      popup: DB
      sms: LOG
      messenger: LOG
    retention:
      queue-done-days: 7
      metric-value-months: 12
```

`channel.mail` 은 인증 메일(`app.security.email-verification.sender-mode`)과 **별개 스위치**로 둔다. 인증 메일은 켜고 알림 메일은 끄는 상황(운영 초기)이 실제로 있다.

### 6-3. 동시성 · 실패 처리

| 위험 | 대응 |
|---|---|
| API 다중 인스턴스에서 평가 중복 | `pg_try_advisory_lock(480401)` — 못 잡으면 그 틱은 조용히 건너뛴다 |
| 한 틱이 다음 틱까지 안 끝남 | 프로세스 내 `AtomicBoolean` (이관 엔진과 동일) |
| 발송 워커 경합 | `SELECT … FOR UPDATE SKIP LOCKED LIMIT :batch` — 워커 N개 안전 |
| 엔진 크래시로 `SENDING` 고착 | `locked_at < now() - 5분` 이면 `PENDING` 으로 회수 |
| SMTP 장애 | `try_cnt` 증가 + 백오프. `max-try` 초과 시 `DEAD` + `send_log FAIL` + 전산팀 팝업 1건 |
| 조건 하나의 SQL 오류가 전체를 죽임 | 조건 단위 try/catch — 실패는 `last_error` 에 남기고 다음 조건 계속, run 은 `PARTIAL` |
| 지표 수집 지연 | `last_value_at` 이 `interval × 3` 보다 오래되면 **판정하지 않는다**(오래된 값으로 알림을 내지 않는다) + 엔진 상태에 `STALE` 표시 |

마지막 항목이 특히 중요하다. 수집이 멈췄는데 마지막 값으로 계속 판정하면 **이미 끝난 이상으로 계속 알림이 나가거나, 진짜 이상을 정상으로 본다.**

---

## 7. API 변경

| 구분 | Method · Path | 내용 |
|---|---|---|
| 신규 | `GET /api/v1/alert-engine/status` | `lastRunAt`, `lagSec`, `state`, `pendingCnt`, `failCnt`, `staleMetrics[]` |
| 신규 | `POST /api/v1/alert-conditions/{condId}/evaluate` | 지금 1회 평가. `?dryRun=true` 면 발송 없이 판정 결과만 |
| 신규 | `GET /api/v1/alert-conditions/{condId}/state` | 대상별 현재 상태(값·연속 시작·마지막 발송) |
| 변경 | `POST /alert-conditions/{condId}/test-send` | 로그 INSERT → **실제 대기열·채널 경로**를 타게. 응답은 기존 `{sentCnt, recipients[], channels[]}` 유지 |
| 변경 | `GET /alert-conditions/summary` | `engine{lastRunAt,lagSec,pendingCnt}` 추가. 지금 `todaySentCnt` 가 `SUPPRESSED/SKIPPED` 까지 세고 있어 `send_result_cd='SENT'` 필터 필요 |

> 현재 `findTodaySendStats()` 는 `tb_alm_send_log` 전 행을 세므로 억제·제외 건이 「오늘 발송」에 섞인다. 엔진이 돌기 시작하면 이 수치가 바로 틀어지므로 같은 PR에서 고친다.

---

## 8. WEB 화면 영향 (`/system/alert-condition`)

- 조건 등록·편집 폼에 **평가 단위(`scope_dim_cd`)** 추가 — 「설비별로 따로 볼지, 전체 하나로 볼지」는 사용자가 정해야 한다.
- `ALM_WINDOW='ONCE'` 선택 시 **시각 입력**(`window_time`) 노출.
- `ALM_TARGET='PICK'` 선택 시 **대상 선택 UI**(설비 다중 선택).
- 표에 「현재 상태 / 마지막 평가」 열 추가 — 정상·감시중(PENDING)·발생(BREACH) 배지.
- 요약 카드 또는 헤더에 **엔진 상태 배지**(`정상 / 지연 Ns / 중지`). 엔진이 죽어 있으면 조건 화면이 아무리 멀쩡해도 알림은 안 온다.
- 기존 4개 카드 중 하나를 엔진 상태로 바꾸는 것이 자연스럽다(현재 「중지 조건」이 등록 조건 카드 보조 문구와 중복).

---

## 9. 단계별 이행

| 단계 | 범위 | 끝났을 때 확인되는 것 |
|---|---|---|
| **1단계** | V35 스키마 + 지표 수집(BUILTIN 3종) + 평가 + 발생. 발송은 `LOG` 모드 | 임계를 넘기면 `/alert/list` 에 알림이 실제로 쌓인다. 지속·억제·시간대 판정이 로그로 검증된다 |
| **2단계** | 대기열 + SMTP 실발송 + 재시도 + 야간·부재·대리(당직) + 마스킹 | 메일이 실제로 도착한다. 테스트 발송이 실제 경로를 탄다 |
| **3단계** | 에스컬레이션 + 엔진 상태 API·화면 + POPUP 채널 + 보존/파티션 운영 | 확인 안 된 위험이 상위로 올라간다. 엔진 이상을 화면에서 안다 |

1단계만으로도 **「알림 목록 화면이 비어 있다」는 현재 상태는 해소된다.** 발송을 뒤로 미루는 이유는, 판정이 틀린 채로 메일이 나가면 되돌릴 수 없기 때문이다.

---

## 10. 결정이 필요한 것

1. **엔진 위치** — API 내장(권장) vs `MES_migration_engine` 처럼 별도 프로세스. 운영 서버 대수·재기동 정책에 달렸다.
2. **`CRIT` 는 유효 시간대를 무시할 것인가.** V35 에서 `tb_alm_cond.ignore_window_flg` 컬럼으로 **조건마다 정할 수 있게** 넣었다(기본 `'N'`). 남은 결정은 운영 방침 — 위험 조건을 등록할 때 이 값을 기본으로 켤 것인지다. 수신자 개인의 야간 미수신(`night_recv`)은 이 값과 무관하게 지킨다.
3. **정상 복귀 알림(해제 통보)을 보낼 것인가.** 지금 설계는 상태만 되돌리고 통보하지 않는다.
4. **`DAY_CLOSE` 의 「마감」 기준 시각** — 이관 엔진의 야간 배치 완료 시점인지, 고정 시각(예: 08:00)인지.
5. **SMS·메신저 연동처** 존재 여부. 없으면 1~3단계 모두 `LOG` 구현으로 두고 채널 선택지에서 감춘다.
6. **지표 수집 SQL을 화면에서 편집하게 할 것인가**(§4-6). 열려면 읽기 전용 롤 분리가 선행되어야 한다.
7. **초기 지표 3종 확정** — `EQPT_UPTIME_RATE` 는 있고 `apply_alert=true`. 불량률·달성률 등 알림에 실제로 쓸 지표를 SY-13에 먼저 등록해야 조건을 만들 수 있다.

---

## 부록. 적용 현황 (2026-09-16)

### 적용한 것

```bash
docker exec -e PGPASSWORD=dwje_local dwje-pg \
  psql -U dwje_local -d dwjedb -v ON_ERROR_STOP=1 -f V35__alm_engine.sql
```

- 파일 : `src/main/resources/db/V35__alm_engine.sql` · 되돌리기 `db/rollback/V35__down.sql`
- 범위 : §4-1 ~ §4-7 전부 (신규 표 5개 · 기존 표 컬럼 10개 · 공통코드 6그룹 23코드 · `ALM_*` attr1/attr2 · 코드 참조 9행)
- `tb_met_metric_collect` 는 `EQPT_UPTIME_RATE` **1행만** `use_flg='N'`(미수집)으로 등록했다. 수집기 구현체가 붙고 값을 확인한 뒤 켠다.

### 확인한 것

| 확인 | 결과 |
|---|---|
| 재실행 안전성 | 2회 실행 종료코드 0, 오류·경고 없음 |
| 지속 조건 판정 | `ALM_DURATION.attr1`(C10M=600초) 로 연속 240초 → **미발생** 판정 정상 |
| 중복 억제 판정 | `ALM_DEDUP.attr1`(M30=30분) 로 직전 발송 12분 전 → **SUPPRESSED** 판정 정상 |
| 중복 발송 차단 | 같은 (알림·수신자·채널·단계) 두 번째 INSERT 가 `ux_alm_send_queue_once` 에 막힘 |
| 수신자 없는 채널 | `user_id IS NULL`(POPUP)이 `coalesce` 로 구분되어 별도 적재됨 |
| 워커 집어가기 | `FOR UPDATE SKIP LOCKED` 질의 정상 |
| 연쇄 삭제 | 조건 삭제 시 `tb_alm_cond_state` · `tb_alm_cond_target` 함께 정리됨 |
| 잔여 데이터 | 검증용 행 전부 롤백 — 모든 `tb_alm_*` 0행 |

### 검증 중 확인된 기존 제약

`tb_alm_alert.cond_id` FK 에는 `ON DELETE CASCADE` 가 없다. 그래서 **알림이 한 번이라도 발생한 조건은 DB 가 삭제를 막는다.**
이는 `AlertConfigService.deleteCondition()` 의 업무 규칙(「이미 알림이 발생한 조건은 삭제할 수 없습니다 … 삭제 대신 중지를 사용하세요」)과 같은 판단이며, 서비스 검사가 빠져도 DB 가 한 번 더 막는다. 그대로 둔다.

### 엔진 구현 (2026-09-16 완료)

`004. 개발/Alert_Engine` — 이관 엔진(`MES_migration_engine`)과 같은 구성이다.
Spring Boot 배치(web 없음) · `alert-engine.jar` · `start.sh`/`stop.sh`/`status.sh` · advisory lock · 실행 이력.

로컬에서 확인한 것 —

| 단계 | 확인 |
|---|---|
| ① 수집 | 설비 446대의 가동률을 `tb_met_metric_value` 에 적재 (비가동 구간을 반영해 50% → 0%) |
| ② 평가 | 임계 비교 + 연속 판정 → `tb_alm_cond_state` 가 `BREACH` 로 전이 |
| ③ 발생 | `tb_alm_alert` 1행 + 대기열 적재. 2분 뒤 재판정은 **중복 억제**(hit_cnt 2 · `SUPPRESSED` 기록) |
| ④ 발송 | 대기열 → 메일(LOG 모드) → `tb_alm_send_log` `SENT` |
| ⑤ 승격 | 규칙 질의까지 확인 (미확인 알림이 생기는 시간이 필요해 실발생은 미확인) |
| 상주 | 1분 주기 백그라운드 기동 · 중복 기동 차단 · 안전 종료(2초) |
| 이력 | 조용한 틱은 행을 남기지 않고, 일이 있었던 틱만 `tb_alm_eval_run` 에 기록 |

점검용으로 넣은 조건·그룹·알림·지표 값은 전부 지웠고, 지표 수집 스위치도 V35 가 둔 `use_flg='N'` 로 되돌렸다.

### 남은 일

1. **API** — §7 의 신규·변경 5건 (엔진 상태 조회 · 즉시 평가 · test-send 실경로화 · summary 의 `SENT` 필터)
2. **WEB** — §8 (평가 단위·지정 시각·PICK 대상 입력, 조건 표의 현재 상태 열, 엔진 상태 배지)
3. **지표** — SY-13 에 불량률(`PROC_DEFECT_RATE`) 등록 후 수집 스위치 켜기. 가동률만으로는 조건을 다양하게 못 만든다
4. **파티션** — `tb_met_metric_value` 월 파티션(V36 예정). 설비 446대 × 5분이면 하루 12만 행이다
