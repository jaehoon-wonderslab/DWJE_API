-- =====================================================================================
--  V72 : 발송 조건 「고급 설정」 제거 1단계 — 메시지 틀 제약 완화 · 유효 시간대 ONCE 사용 중지 (2026-10-03)
--
--  [배경]
--  사용자 요청으로 이상 알림 발송 조건 화면의 「고급 설정」 7가지(평가 단위 · 평가 주기 · 지정 시각 ·
--  시간대 무시 · 자동 해제 · 메시지 틀 · 승격 적용)를 알림 엔진 · API · DB 에서 모두 없앤다. 없앤 뒤에는
--  모든 조건이 고정값(= 지금 기본값)으로 동작한다. 컬럼을 먼저 지우면 옛 API · 엔진이 실패하므로 두 단계로 나눈다.
--    V72(이 파일) 완화 → 엔진 · API(이 값들을 읽지도 쓰지도 않음) → WEB → V73 컬럼 · 표 삭제
--  V72 상태에서는 옛 API · 옛 엔진 · 새 API · 새 엔진이 모두 동작한다.
--
--  [이 파일이 하는 일]
--   1. tb_alm_cond.msg_template — NOT NULL 해제 + 기본값을 서버 기본 틀 문장으로.
--      새 API 가 이 칸을 빼고 INSERT 해도 기본 틀이 들어가, 메시지 틀을 NULL 로 읽지 못하는 옛 엔진이 그대로 돈다.
--   2. 유효 시간대 ONCE(지정 시각 1회) — 지정 시각을 없애면 뜻이 없다.
--      · window_cd = 'ONCE' 이면서 사용 중인 조건은 중지(use_flg = 'N'). ALWAYS 로 바꾸면 하루 종일 발송되므로 바꾸지 않는다
--      · 공통코드 ALM_WINDOW/ONCE 사용 중지(코드는 남김 — 과거 조건 · 수신 그룹이 참조할 수 있음)
--      처음 적용할 때만 하고, 되돌리기용으로 바꾼 조건을 기록표 ax.tb_mig_v72_once_bak 에 남긴다.
--   3. 주석 — msg_template · window_cd
--  2026-10-03 로컬: ONCE 조건 0건, ONCE 수신 그룹 0건.
--
--  [실행]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f V72__alm_cond_adv_relax.sql
--  두 번 실행해도 안전하다. 되돌리기는 rollback/V72__down.sql.
-- =====================================================================================

BEGIN;

-- ── 1. 메시지 틀 (컬럼이 있을 때만 — V73 뒤에 다시 실행해도 실패하지 않게) ────────────────
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = 'ax' AND table_name = 'tb_alm_cond' AND column_name = 'msg_template') THEN
        ALTER TABLE ax.tb_alm_cond ALTER COLUMN msg_template DROP NOT NULL;
        ALTER TABLE ax.tb_alm_cond ALTER COLUMN msg_template
            SET DEFAULT '[{{severity}}] {{condNm}} — {{scope}} {{metricNm}} {{value}}{{unit}} ({{op}} {{threshold}}{{unit}}) {{link}}';
        COMMENT ON COLUMN ax.tb_alm_cond.msg_template IS
          '메시지 틀 — 사용 중지(2026-10-03, 고급 설정 제거). 알림 엔진은 기본 틀 하나로 메시지를 만들고 이 값을 읽지 않음. 빼고 넣으면 기본 틀 문장이 들어감. 컬럼 삭제 예정(값은 보관 표로 옮김)';
    ELSE
        RAISE NOTICE 'V72: msg_template 컬럼이 이미 없어 건너뜀(V73 적용됨)';
    END IF;
END $$;

-- ── 2. ONCE 정리 (처음 적용 때만) ────────────────────────────────────────────────────
DO $$
DECLARE
    v_first boolean;
    v_cnt   integer;
BEGIN
    v_first := to_regclass('ax.tb_mig_v72_once_bak') IS NULL;

    CREATE TABLE IF NOT EXISTS ax.tb_mig_v72_once_bak (
        cond_id      integer     NOT NULL,
        prev_use_flg char(1)     NOT NULL,
        bak_at       timestamptz NOT NULL DEFAULT now(),
        CONSTRAINT pk_tb_mig_v72_once_bak PRIMARY KEY (cond_id)
    );

    IF NOT v_first THEN
        RAISE NOTICE 'V72: 기록표가 이미 있어 ONCE 정리는 건너뜀(이미 적용됨)';
        RETURN;
    END IF;

    INSERT INTO ax.tb_mig_v72_once_bak (cond_id, prev_use_flg)
    SELECT cond_id, use_flg FROM ax.tb_alm_cond WHERE window_cd = 'ONCE' AND use_flg = 'Y';

    UPDATE ax.tb_alm_cond c
       SET use_flg = 'N', upd_date = now(), upd_user = 'V72'
      FROM ax.tb_mig_v72_once_bak b
     WHERE b.cond_id = c.cond_id;
    GET DIAGNOSTICS v_cnt = ROW_COUNT;
    RAISE NOTICE 'V72: 유효 시간대 ONCE 조건 중지 % 건', v_cnt;

    UPDATE ax.tb_sys_code
       SET use_flg = 'N', upd_date = now(), upd_user = 'V72'
     WHERE group_cd = 'ALM_WINDOW' AND code = 'ONCE' AND use_flg = 'Y';
    GET DIAGNOSTICS v_cnt = ROW_COUNT;
    RAISE NOTICE 'V72: 공통코드 ALM_WINDOW/ONCE 사용 중지 % 건', v_cnt;

    SELECT count(*) INTO v_cnt FROM ax.tb_alm_recip_group WHERE window_cd = 'ONCE';
    RAISE NOTICE 'V72: ONCE 를 쓰는 수신 그룹 % 건(바꾸지 않음)', v_cnt;
END $$;

-- ── 3. 주석 ──────────────────────────────────────────────────────────────────────────
COMMENT ON TABLE ax.tb_mig_v72_once_bak IS
  '유효 시간대 ONCE 정리 되돌리기 기록 — 지정 시각 1회를 없앨 때 중지한 발송 조건과 그 이전 사용 여부. 되돌리기 스크립트가 쓰고 지움';
COMMENT ON COLUMN ax.tb_mig_v72_once_bak.cond_id      IS '중지한 발송 조건 ID (ax.tb_alm_cond)';
COMMENT ON COLUMN ax.tb_mig_v72_once_bak.prev_use_flg IS '중지 전 사용 여부(Y)';
COMMENT ON COLUMN ax.tb_mig_v72_once_bak.bak_at       IS '기록 시각';

COMMENT ON COLUMN ax.tb_alm_cond.window_cd IS
  '유효 시간대. 공통코드 그룹 = ALM_WINDOW (ALWAYS=24시간 상시, D0820=08:00~20:00, D0618=06:00~18:00, WORKDAY=주간 근무일만). ONCE(지정 시각 1회)는 2026-10-03 사용 중지';

COMMIT;
