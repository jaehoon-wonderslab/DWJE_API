-- =====================================================================================
--  V73 : 발송 조건 「고급 설정」 제거 2단계 — 컬럼 6개 · 조건별 승격 표 · 승격 규칙 표 삭제(값은 보관 표로) (2026-10-03)
--
--  [적용 순서 — 반드시 지킬 것]
--  V72 → 알림 엔진 · API 새 코드(이 값들을 읽지도 쓰지도 않음) → WEB → 이 파일.
--  옛 엔진 · 옛 API 가 떠 있는 채로 적용하면 발송 조건 조회 SQL 이 없는 컬럼을 읽다가 실패한다
--  (엔진은 판정 중단, API 는 발송 조건 화면 500).
--
--  [없애는 것과 이후 고정 동작]
--    평가 단위   scope_dim_cd       → NONE(지표 수집 단위 따름)
--    평가 주기   eval_interval_sec  → 60초(엔진 틱)
--    지정 시각   window_time        → 없음. 지속 조건 DAY_CLOSE · DAY_ONCE 의 기준 시각은 08:00 고정
--    시간대 무시 ignore_window_flg  → N(유효 시간대를 지킴)
--    자동 해제   auto_close_flg     → N(정상 복귀 시 해제 기록 안 함)
--    메시지 틀   msg_template       → 엔진 기본 틀
--    승격 적용   tb_alm_cond_escalation(표) → 조건별 승격 없음
--    승격 규칙   tb_alm_escalation_rule(표)  → 승격 기능 자체가 없음(엔진 EscalationRunner 삭제).
--                API 승격 규칙 조회·수정(/alert-escalation-rules) · 승격 대상(/alerts/escalation-targets),
--                수신자 관리 요약 escNoTargetCnt, 수신 그룹 escStages 도 함께 뺐다 — 이 표를 읽는 곳 0건.
--                tb_alm_alert · tb_alm_send_queue · tb_alm_send_log 의 esc_level 컬럼은 이력으로 남긴다(엔진은 0)
--
--  [이 파일이 하는 일]
--   1. 보관 표 3개 — ax.tb_alm_cond_adv_bak(조건별 6개 값), ax.tb_alm_cond_escalation_bak(조건별 승격 행),
--      ax.tb_alm_escalation_rule_bak(승격 규칙 행). 다시 실행하면 같은 키는 값을 새로 덮는다
--   2. 공통코드 참조 정합성 목록(tb_sys_code_ref)에서 tb_alm_cond.scope_dim_cd 행 삭제(없는 컬럼을 검사하지 않게)
--   3. 컬럼 6개 · 표 tb_alm_cond_escalation · 표 tb_alm_escalation_rule 삭제
--      tb_alm_cond_escalation 이 FK 로 승격 규칙 표를 물므로 그 표를 먼저 지운다.
--      승격 규칙 표가 수신 그룹(tb_alm_recip_group)을 무는 FK 는 표와 함께 사라진다(그룹은 그대로).
--      tb_sys_code_ref 에 승격 규칙 표 행은 없다(2026-10-03 로컬 확인).
--  컬럼 · 표가 이미 없으면 1 · 3 은 건너뛴다.
--
--  [공통코드 ALM_SCOPE_DIM 은 그대로 둔다]
--  지시서는 사용 중지였으나 이 코드 그룹은 지표 수집 정의(tb_met_metric_collect.dim_cd)와 조건 대상
--  (tb_alm_cond_target.target_dim_cd)도 쓴다. API 지표 수집 저장이 use_flg = 'Y' 인 코드만 받으므로
--  사용 중지하면 수집 정의 저장이 모두 400 이 된다(2026-10-03 API 터미널 확인).
--
--  [실행]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f V73__alm_cond_adv_drop.sql
--  두 번 실행해도 안전하다. 되돌리기는 rollback/V73__down.sql(컬럼 · 표 2개를 되살리고 보관 표에서 값을 되돌림).
-- =====================================================================================

BEGIN;

-- ── 1. 보관 표 ───────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ax.tb_alm_cond_adv_bak (
    cond_id           integer      NOT NULL,
    scope_dim_cd      varchar(30),
    eval_interval_sec integer,
    window_time       time,
    ignore_window_flg char(1),
    auto_close_flg    char(1),
    msg_template      text,
    backed_at         timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_tb_alm_cond_adv_bak PRIMARY KEY (cond_id)
);
CREATE TABLE IF NOT EXISTS ax.tb_alm_cond_escalation_bak (
    cond_id     integer     NOT NULL,
    esc_rule_id integer     NOT NULL,
    is_on       boolean     NOT NULL,
    backed_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_tb_alm_cond_escalation_bak PRIMARY KEY (cond_id, esc_rule_id)
);
CREATE TABLE IF NOT EXISTS ax.tb_alm_escalation_rule_bak (
    esc_rule_id     integer      NOT NULL,
    esc_level       smallint     NOT NULL,
    level_nm        varchar(20)  NOT NULL,
    after_min       integer      NOT NULL,
    to_target_desc  varchar(100) NOT NULL,
    to_group_id     integer,
    severity_filter varchar(30),
    note            varchar(300),
    use_flg         char(1)      NOT NULL,
    backed_at       timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_tb_alm_escalation_rule_bak PRIMARY KEY (esc_rule_id)
);

COMMENT ON TABLE  ax.tb_alm_cond_adv_bak                   IS '발송 조건 고급 설정 보관 — 고급 설정 컬럼을 지울 때 남긴 조건별 값. 되돌리기 스크립트가 이 값으로 컬럼을 되살림';
COMMENT ON COLUMN ax.tb_alm_cond_adv_bak.cond_id           IS '발송 조건 ID (ax.tb_alm_cond 논리 참조, FK 없음)';
COMMENT ON COLUMN ax.tb_alm_cond_adv_bak.scope_dim_cd      IS '지우기 전 평가 단위(ALM_SCOPE_DIM)';
COMMENT ON COLUMN ax.tb_alm_cond_adv_bak.eval_interval_sec IS '지우기 전 평가 주기(초)';
COMMENT ON COLUMN ax.tb_alm_cond_adv_bak.window_time       IS '지우기 전 지정 시각(ONCE 시각 · 일 마감 · 일 1회 기준 시각)';
COMMENT ON COLUMN ax.tb_alm_cond_adv_bak.ignore_window_flg IS '지우기 전 시간대 무시 여부(Y/N)';
COMMENT ON COLUMN ax.tb_alm_cond_adv_bak.auto_close_flg    IS '지우기 전 자동 해제 여부(Y/N)';
COMMENT ON COLUMN ax.tb_alm_cond_adv_bak.msg_template      IS '지우기 전 메시지 틀';
COMMENT ON COLUMN ax.tb_alm_cond_adv_bak.backed_at         IS '보관한 시각';
COMMENT ON TABLE  ax.tb_alm_cond_escalation_bak             IS '조건별 승격 적용 보관 — 조건별 승격 표(tb_alm_cond_escalation)를 지울 때 남긴 행. 되돌리기 스크립트가 이 값으로 표를 되살림';
COMMENT ON COLUMN ax.tb_alm_cond_escalation_bak.cond_id     IS '발송 조건 ID (ax.tb_alm_cond 논리 참조)';
COMMENT ON COLUMN ax.tb_alm_cond_escalation_bak.esc_rule_id IS '승격 단계 ID (ax.tb_alm_escalation_rule_bak 논리 참조 — 승격 규칙 표는 V73 에서 지움)';
COMMENT ON COLUMN ax.tb_alm_cond_escalation_bak.is_on       IS '지우기 전 승격 적용 여부';
COMMENT ON COLUMN ax.tb_alm_cond_escalation_bak.backed_at   IS '보관한 시각';
COMMENT ON TABLE  ax.tb_alm_escalation_rule_bak                 IS '승격 규칙 보관 — 승격 기능을 없애며 승격 규칙 표(tb_alm_escalation_rule)를 지울 때 남긴 행. 되돌리기 스크립트가 이 값으로 표를 되살림';
COMMENT ON COLUMN ax.tb_alm_escalation_rule_bak.esc_rule_id     IS '지우기 전 승격 규칙 식별자';
COMMENT ON COLUMN ax.tb_alm_escalation_rule_bak.esc_level       IS '지우기 전 승격 단계 (1=1차, 2=2차, 3=3차)';
COMMENT ON COLUMN ax.tb_alm_escalation_rule_bak.level_nm        IS '지우기 전 단계 표시명';
COMMENT ON COLUMN ax.tb_alm_escalation_rule_bak.after_min       IS '지우기 전 승격 대기 시간(분)';
COMMENT ON COLUMN ax.tb_alm_escalation_rule_bak.to_target_desc  IS '지우기 전 승격 대상 표시 문구';
COMMENT ON COLUMN ax.tb_alm_escalation_rule_bak.to_group_id     IS '지우기 전 승격 수신 그룹 ID (ax.tb_alm_recip_group 논리 참조, FK 없음)';
COMMENT ON COLUMN ax.tb_alm_escalation_rule_bak.severity_filter IS '지우기 전 심각도 제한. NULL 이면 전 심각도';
COMMENT ON COLUMN ax.tb_alm_escalation_rule_bak.note            IS '지우기 전 메모';
COMMENT ON COLUMN ax.tb_alm_escalation_rule_bak.use_flg         IS '지우기 전 사용 여부(Y/N)';
COMMENT ON COLUMN ax.tb_alm_escalation_rule_bak.backed_at       IS '보관한 시각';

DO $$
DECLARE
    v_cnt integer;
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = 'ax' AND table_name = 'tb_alm_cond' AND column_name = 'scope_dim_cd') THEN
        EXECUTE $q$
            INSERT INTO ax.tb_alm_cond_adv_bak (cond_id, scope_dim_cd, eval_interval_sec, window_time,
                                                ignore_window_flg, auto_close_flg, msg_template, backed_at)
            SELECT cond_id, scope_dim_cd, eval_interval_sec, window_time,
                   ignore_window_flg, auto_close_flg, msg_template, now()
              FROM ax.tb_alm_cond
            ON CONFLICT (cond_id) DO UPDATE
               SET scope_dim_cd = EXCLUDED.scope_dim_cd, eval_interval_sec = EXCLUDED.eval_interval_sec,
                   window_time = EXCLUDED.window_time, ignore_window_flg = EXCLUDED.ignore_window_flg,
                   auto_close_flg = EXCLUDED.auto_close_flg, msg_template = EXCLUDED.msg_template,
                   backed_at = EXCLUDED.backed_at $q$;
        GET DIAGNOSTICS v_cnt = ROW_COUNT;
        RAISE NOTICE 'V73: 고급 설정 보관 % 건', v_cnt;

        -- 동작이 바뀌는 조건 수(알림용) — 기본값과 다른 값을 쓰던 조건
        EXECUTE $q$
            SELECT count(*) FROM ax.tb_alm_cond
             WHERE scope_dim_cd <> 'NONE' OR eval_interval_sec <> 60 OR ignore_window_flg = 'Y' OR auto_close_flg = 'Y'
                OR (duration_cd IN ('DAY_CLOSE', 'DAY_ONCE') AND window_time IS NOT NULL AND window_time <> '08:00') $q$
           INTO v_cnt;
        RAISE NOTICE 'V73: 기본값과 다른 고급 설정을 쓰던 조건 % 건(이제 고정값으로 동작)', v_cnt;
    ELSE
        RAISE NOTICE 'V73: 고급 설정 컬럼이 이미 없어 보관은 건너뜀';
    END IF;

    IF to_regclass('ax.tb_alm_cond_escalation') IS NOT NULL THEN
        EXECUTE $q$
            INSERT INTO ax.tb_alm_cond_escalation_bak (cond_id, esc_rule_id, is_on, backed_at)
            SELECT cond_id, esc_rule_id, is_on, now() FROM ax.tb_alm_cond_escalation
            ON CONFLICT (cond_id, esc_rule_id) DO UPDATE SET is_on = EXCLUDED.is_on, backed_at = EXCLUDED.backed_at $q$;
        GET DIAGNOSTICS v_cnt = ROW_COUNT;
        RAISE NOTICE 'V73: 조건별 승격 보관 % 건', v_cnt;
    END IF;

    IF to_regclass('ax.tb_alm_escalation_rule') IS NOT NULL THEN
        EXECUTE $q$
            INSERT INTO ax.tb_alm_escalation_rule_bak (esc_rule_id, esc_level, level_nm, after_min, to_target_desc,
                                                       to_group_id, severity_filter, note, use_flg, backed_at)
            SELECT esc_rule_id, esc_level, level_nm, after_min, to_target_desc,
                   to_group_id, severity_filter, note, use_flg, now()
              FROM ax.tb_alm_escalation_rule
            ON CONFLICT (esc_rule_id) DO UPDATE
               SET esc_level = EXCLUDED.esc_level, level_nm = EXCLUDED.level_nm, after_min = EXCLUDED.after_min,
                   to_target_desc = EXCLUDED.to_target_desc, to_group_id = EXCLUDED.to_group_id,
                   severity_filter = EXCLUDED.severity_filter, note = EXCLUDED.note, use_flg = EXCLUDED.use_flg,
                   backed_at = EXCLUDED.backed_at $q$;
        GET DIAGNOSTICS v_cnt = ROW_COUNT;
        RAISE NOTICE 'V73: 승격 규칙 보관 % 건', v_cnt;
    ELSE
        RAISE NOTICE 'V73: 승격 규칙 표가 이미 없어 보관은 건너뜀';
    END IF;
END $$;

-- ── 2. 공통코드 정합성 목록 ──────────────────────────────────────────────────────────
DELETE FROM ax.tb_sys_code_ref WHERE target_table = 'tb_alm_cond' AND target_column = 'scope_dim_cd';

-- ── 3. 컬럼 · 표 삭제 ────────────────────────────────────────────────────────────────
-- 조건별 승격 표가 승격 규칙 표를 FK 로 물므로 먼저 지운다
DROP TABLE IF EXISTS ax.tb_alm_cond_escalation;
DROP TABLE IF EXISTS ax.tb_alm_escalation_rule;
ALTER TABLE ax.tb_alm_cond
    DROP COLUMN IF EXISTS scope_dim_cd,
    DROP COLUMN IF EXISTS eval_interval_sec,
    DROP COLUMN IF EXISTS window_time,
    DROP COLUMN IF EXISTS ignore_window_flg,
    DROP COLUMN IF EXISTS auto_close_flg,
    DROP COLUMN IF EXISTS msg_template;

COMMIT;
