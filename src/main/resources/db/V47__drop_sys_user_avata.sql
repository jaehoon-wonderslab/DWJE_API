-- =====================================================================================
--  V47 : ax.tb_sys_user.avata 컬럼 제거 (2026-09-30)
--
--  [배경]
--  V45 가 그룹웨어 인사정보의 PROFILE_IMAGE_PATH(그룹웨어 프로필 사진 URL)를 자동 가입 때
--  ax.tb_sys_user.avata 에 넣도록 컬럼을 만들었다. 보안상 이 URL 을 AX 에서 쓸 수 없다는 결정에 따라
--  컬럼을 지운다. 이관 엔진도 더 이상 이 값을 쓰지 않는다.
--  API·WEB 코드는 avata 를 읽지 않으므로 함께 고칠 곳은 없다.
--
--  그룹웨어 원본 값은 groupware_user.tb_user_list.profile_image_path 에 그대로 남는다 (인사정보 사본).
--
--  [실행]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f V47__drop_sys_user_avata.sql
--  두 번 실행해도 안전하다. 되돌리기는 rollback/V47__down.sql (컬럼만 되살리고 값은 돌아오지 않는다).
-- =====================================================================================

BEGIN;

ALTER TABLE ax.tb_sys_user DROP COLUMN IF EXISTS avata;

COMMIT;
