-- =====================================================================================
--  V47 되돌리기 — ax.tb_sys_user.avata 컬럼 복원 (2026-09-30)
--
--  컬럼만 되살린다. 지울 때 있던 값은 돌아오지 않는다.
--  이관 엔진은 이 컬럼에 쓰지 않으므로 되살려도 비어 있는 채로 남는다.
--
--  [적용]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f rollback/V47__down.sql
--  두 번 실행해도 안전하다.
-- =====================================================================================

BEGIN;

ALTER TABLE ax.tb_sys_user ADD COLUMN IF NOT EXISTS avata varchar(200);
COMMENT ON COLUMN ax.tb_sys_user.avata IS '프로필 사진 URL (V47 에서 제거했다가 되돌림 — 엔진은 쓰지 않음)';

COMMIT;
