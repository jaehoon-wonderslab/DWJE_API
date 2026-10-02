-- =====================================================================================
--  V58 되돌리기 — 감사 기록 연결 · 접속 환경 컬럼, 감사 유형 코드, 아카이브 표 (2026-10-01)
--
--  · 아카이브 표에 행이 있으면 아무것도 바꾸지 않고 중단한다(감사 기록 유실 방지).
--    원본으로 되옮긴 뒤 다시 실행한다.
--  · 컬럼 3개 · 인덱스 3개를 지운다. 적용 뒤 쌓인 user_agent · audit_id · dept_nm 값은 돌아오지 않는다.
--  · 공통코드는 쓰는 행이 없을 때만 지우고, 있으면 사용 중지(use_flg = N)로 둔다.
--    ACCOUNT_SEC 는 V48 몫이라 건드리지 않는다. UNMASK_REQ 표시명 · 순서는 V58 직전 값으로.
--
--  [API 를 먼저 내린다]
--  새 API 는 세 컬럼과 새 유형으로 기록한다.
--
--  [적용]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f rollback/V58__down.sql
--  두 번 실행해도 안전하다.
-- =====================================================================================

BEGIN;

-- 표가 이미 없을 때도 돌도록 행 확인은 동적 SQL 로 한다(세션 임시 함수)
CREATE OR REPLACE FUNCTION pg_temp.has_rows(p_table text) RETURNS boolean LANGUAGE plpgsql AS $f$
DECLARE v boolean;
BEGIN
    IF to_regclass(p_table) IS NULL THEN RETURN false; END IF;
    EXECUTE format('SELECT EXISTS (SELECT 1 FROM %s)', p_table) INTO v;
    RETURN v;
END $f$;

DO $$
DECLARE
    t text;
BEGIN
    IF pg_temp.has_rows('ax.tb_log_audit_arch') OR pg_temp.has_rows('ax.tb_sys_perm_log_arch')
       OR pg_temp.has_rows('ax.tb_sys_login_hist_arch') THEN
        RAISE EXCEPTION 'V58 되돌리기 중단: 아카이브 표에 행이 있습니다. 원본으로 되옮긴 뒤 다시 실행하십시오.';
    END IF;

    FOREACH t IN ARRAY ARRAY['tb_log_audit_arch', 'tb_sys_perm_log_arch', 'tb_sys_login_hist_arch'] LOOP
        IF to_regclass('ax.' || t) IS NOT NULL THEN
            EXECUTE format('DROP TRIGGER IF EXISTS tg_%1$s_immutable ON ax.%1$s', t);
            EXECUTE format('DROP TRIGGER IF EXISTS tg_%1$s_no_truncate ON ax.%1$s', t);
        END IF;
    END LOOP;
END $$;

DROP TABLE IF EXISTS ax.tb_log_audit_arch, ax.tb_sys_perm_log_arch, ax.tb_sys_login_hist_arch;

DROP INDEX IF EXISTS ax.ix_sys_perm_log_audit;
DROP INDEX IF EXISTS ax.ix_sys_login_at_desc;
DROP INDEX IF EXISTS ax.ix_log_audit_dept;

ALTER TABLE ax.tb_log_audit      DROP COLUMN IF EXISTS user_agent;
ALTER TABLE ax.tb_sys_perm_log   DROP COLUMN IF EXISTS audit_id;
ALTER TABLE ax.tb_sys_login_hist DROP COLUMN IF EXISTS dept_nm;

DELETE FROM ax.tb_sys_code c
 WHERE c.group_cd = 'LOG_AUDIT_RESULT' AND c.code = 'MASKED'
   AND NOT EXISTS (SELECT 1 FROM ax.tb_log_audit a WHERE a.result_cd = 'MASKED');
DELETE FROM ax.tb_sys_code c
 WHERE c.group_cd = 'LOG_AUDIT_TYPE' AND c.code IN ('EXPORT', 'CONFIG_CHANGE', 'ACCESS_DENIED', 'AUDIT_VIEW')
   AND NOT EXISTS (SELECT 1 FROM ax.tb_log_audit a WHERE a.log_type_cd = c.code);
UPDATE ax.tb_sys_code
   SET use_flg = 'N', upd_date = now(), upd_user = 'V58-down'
 WHERE ((group_cd = 'LOG_AUDIT_RESULT' AND code = 'MASKED')
     OR (group_cd = 'LOG_AUDIT_TYPE' AND code IN ('EXPORT', 'CONFIG_CHANGE', 'ACCESS_DENIED', 'AUDIT_VIEW')))
   AND use_flg = 'Y';

UPDATE ax.tb_sys_code
   SET code_nm = '마스킹 해제 요청', sort_seq = 2, upd_date = now(), upd_user = 'V58-down'
 WHERE group_cd = 'LOG_AUDIT_TYPE' AND code = 'UNMASK_REQ'
   AND (code_nm, sort_seq) IS DISTINCT FROM ('마스킹 해제 요청', 2::smallint);

COMMENT ON COLUMN ax.tb_log_audit.plant_cd IS '대상이 특정 사업부인 경우의 사업부 코드';

COMMIT;
