-- =====================================================================================
--  V67 되돌리기 — 업로드 문서 숨김 정보 컬럼 · 전산팀 쓰기 권한 (2026-10-02)
--
--  · 숨긴 문서(del_flg = Y)가 있으면 아무것도 바꾸지 않고 중단한다. 숨김 사유 · 시각이 사라지고
--    옛 API 에서 그 문서를 되살릴 방법이 없기 때문이다. 먼저 복원한 뒤 실행한다.
--  · 컬럼 3개를 지우고, V67 이 켠 쓰기 권한(upd_user = V67)만 끈다. del_flg 주석은 V67 직전 문구로.
--
--  [API 를 먼저 내린다]
--  새 API 는 세 컬럼을 읽고 쓴다.
--
--  [적용]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f rollback/V67__down.sql
--  두 번 실행해도 안전하다.
-- =====================================================================================

BEGIN;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM ax.tb_dash_upload_doc WHERE del_flg = 'Y') THEN
        RAISE EXCEPTION 'V67 되돌리기 중단: 숨긴 업로드 문서가 있습니다. 복원한 뒤 다시 실행하십시오.';
    END IF;
END $$;

UPDATE ax.tb_sys_dept_menu_perm
   SET can_write = false, upd_date = now(), upd_user = 'V67-down'
 WHERE menu_id = 'sys-upload-doc' AND upd_user = 'V67' AND can_write;

ALTER TABLE ax.tb_dash_upload_doc DROP COLUMN IF EXISTS del_reason;
ALTER TABLE ax.tb_dash_upload_doc DROP COLUMN IF EXISTS del_user;
ALTER TABLE ax.tb_dash_upload_doc DROP COLUMN IF EXISTS del_at;

COMMENT ON COLUMN ax.tb_dash_upload_doc.del_flg IS
  'Y = 목록에서 숨김. 버전·파일은 지우지 않음';

COMMIT;
