-- =====================================================================================
--  V72 되돌리기 — 메시지 틀 NOT NULL 복원 · ONCE 다시 사용 (2026-10-03)
--
--  · V73 이 적용돼 msg_template 컬럼이 없으면 아무것도 바꾸지 않고 중단한다(V73 을 먼저 되돌린다).
--  · 메시지 틀이 빈(NULL) 조건은 서버 기본 틀 문장으로 채운 뒤 NOT NULL 을 되돌리고 기본값을 지운다.
--  · 기록표에 있는 조건(V72 가 중지한 ONCE 조건)을 이전 사용 여부로, V72 가 사용 중지한 ONCE 코드(upd_user = V72)를
--    다시 사용으로 돌린다. 기록표를 지운다.
--  · 주석은 V72 직전 문구로.
--
--  [적용]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f rollback/V72__down.sql
--  두 번 실행해도 안전하다.
-- =====================================================================================

BEGIN;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = 'ax' AND table_name = 'tb_alm_cond' AND column_name = 'msg_template') THEN
        EXECUTE $q$
            UPDATE ax.tb_alm_cond
               SET msg_template = '[{{severity}}] {{condNm}} — {{scope}} {{metricNm}} {{value}}{{unit}} ({{op}} {{threshold}}{{unit}}) {{link}}'
             WHERE msg_template IS NULL $q$;
        ALTER TABLE ax.tb_alm_cond ALTER COLUMN msg_template SET NOT NULL;
        ALTER TABLE ax.tb_alm_cond ALTER COLUMN msg_template DROP DEFAULT;
        COMMENT ON COLUMN ax.tb_alm_cond.msg_template IS
          '메시지 틀. 치환자는 {{변수}} 꼴 — severity · condNm · scope · eqptNm · target · metricNm · metricDesc · value · unit · op · threshold · evidence · occurredAt · link. 정의하지 않은 변수는 글자 그대로 남음. 단가 · 수율 등 민감정보는 넣지 않음';
    ELSE
        RAISE EXCEPTION 'V72 되돌리기 중단: msg_template 컬럼이 없습니다. rollback/V73__down.sql 을 먼저 실행하십시오.';
    END IF;

    IF to_regclass('ax.tb_mig_v72_once_bak') IS NOT NULL THEN
        UPDATE ax.tb_alm_cond c
           SET use_flg = b.prev_use_flg, upd_date = now(), upd_user = 'V72-down'
          FROM ax.tb_mig_v72_once_bak b
         WHERE b.cond_id = c.cond_id;
        DROP TABLE ax.tb_mig_v72_once_bak;
    END IF;
END $$;

UPDATE ax.tb_sys_code
   SET use_flg = 'Y', upd_date = now(), upd_user = 'V72-down'
 WHERE group_cd = 'ALM_WINDOW' AND code = 'ONCE' AND upd_user = 'V72' AND use_flg = 'N';

COMMENT ON COLUMN ax.tb_alm_cond.window_cd IS
  '유효 시간대. 공통코드 그룹 = ALM_WINDOW (ALWAYS=24시간 상시, D0820=08:00~20:00, D0618=06:00~18:00, WORKDAY=주간 근무일만, ONCE=지정 시각 1회)';

COMMIT;
