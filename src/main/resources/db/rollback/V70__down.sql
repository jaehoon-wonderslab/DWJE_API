-- =====================================================================================
--  V70 되돌리기 — 메뉴 접근 권한 칸 통합 · 전사 자연어 질의 이력 · 학습 답변 컬럼 (2026-10-03)
--
--   1. can_write 컬럼 2개를 되살린다. 값은 can_read 로 채운 뒤(통합 뒤 의미 = 접근하면 쓰기 가능),
--      V70 기록표(ax.tb_mig_v70_menu_perm_bak)에 있는 행은 지우기 전 값으로 돌린다.
--      V70 뒤에 새로 생긴 행은 기록표에 없으므로 can_read 값 그대로다.
--   2. 판정 뷰를 V64 모양(user_id, menu_id, can_write, from_dept, from_grant)으로 다시 만든다.
--   3. 학습 답변 컬럼 3개를 지운다(저장된 학습 답변도 함께 사라진다).
--   4. sys-chat-history 화면 행과 그 권한 행을 지운다. V70 은 권한 행을 넣지 않지만(관리자 전용), 그 뒤
--      통합관리자가 부서 · 계정에 준 행이 있으면 함께 사라진다. V70 이 기본 허용으로 넣은 chat-history
--      부서 행 중 그 뒤에 바뀌지 않은 것도 지운다.
--   5. 기록표를 지운다.
--
--  [API 를 먼저 내린다]
--  새 API 는 can_write 를 읽지 않으므로 이 파일 뒤에도 돌기는 하지만, 쓰기 칸 판정(writePerms)이 돌아오려면
--  V70 이전 API 로 함께 되돌려야 한다.
--
--  [적용]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f rollback/V70__down.sql
--  두 번 실행해도 안전하다.
-- =====================================================================================

BEGIN;

-- ── 1. can_write 컬럼 ────────────────────────────────────────────────────────────────
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'ax' AND table_name = 'tb_sys_dept_menu_perm' AND column_name = 'can_write') THEN
        ALTER TABLE ax.tb_sys_dept_menu_perm ADD COLUMN can_write boolean NOT NULL DEFAULT false;
        UPDATE ax.tb_sys_dept_menu_perm SET can_write = can_read;
        IF to_regclass('ax.tb_mig_v70_menu_perm_bak') IS NOT NULL THEN
            UPDATE ax.tb_sys_dept_menu_perm p
               SET can_write = b.can_write
              FROM ax.tb_mig_v70_menu_perm_bak b
             WHERE b.src = 'DEPT' AND b.owner_id = p.dept_id::text AND b.menu_id = p.menu_id;
        END IF;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'ax' AND table_name = 'tb_sys_user_menu_grant' AND column_name = 'can_write') THEN
        ALTER TABLE ax.tb_sys_user_menu_grant ADD COLUMN can_write boolean NOT NULL DEFAULT false;
        UPDATE ax.tb_sys_user_menu_grant SET can_write = true;
        IF to_regclass('ax.tb_mig_v70_menu_perm_bak') IS NOT NULL THEN
            UPDATE ax.tb_sys_user_menu_grant g
               SET can_write = b.can_write
              FROM ax.tb_mig_v70_menu_perm_bak b
             WHERE b.src = 'USER' AND b.owner_id = g.user_id AND b.menu_id = g.menu_id;
        END IF;
    END IF;
END $$;

COMMENT ON COLUMN ax.tb_sys_dept_menu_perm.can_write IS
  '입력·수정 권한. true 여야 쓰기 API 를 통과(조회 권한도 함께 필요). 메뉴 접근 권한 화면의 「쓰기」 칸. '
  '통합관리자는 이 값과 무관하게 통과, 미배정 부서는 늘 false';
COMMENT ON COLUMN ax.tb_sys_dept_menu_perm.can_read IS
  '조회 권한. 행이 있으면서 true 여야 화면에 들어갈 수 있음. 메뉴 접근 권한 화면 표의 체크 상태';
COMMENT ON COLUMN ax.tb_sys_user_menu_grant.can_write IS
  '입력·수정 권한 추가 부여. 유효 쓰기 권한 = 부서 can_write OR 이 값 (가산이며 부서 권한을 낮추지 않는다)';

-- ── 2. 판정 뷰 (V64 모양) ────────────────────────────────────────────────────────────
DROP VIEW IF EXISTS ax.vw_sys_user_menu_perm;

CREATE VIEW ax.vw_sys_user_menu_perm AS
SELECT p.user_id,
       p.menu_id,
       bool_or(p.can_write)    AS can_write,
       bool_or(p.src = 'DEPT') AS from_dept,
       bool_or(p.src = 'USER') AS from_grant
  FROM (
        SELECT u.user_id, dp.menu_id, dp.can_write, 'DEPT'::text AS src
          FROM ax.tb_sys_user u
          JOIN ax.tb_sys_dept_menu_perm dp ON dp.dept_id = u.dept_id
         WHERE dp.can_read
        UNION ALL
        SELECT g.user_id, g.menu_id, g.can_write, 'USER'::text
          FROM ax.tb_sys_user_menu_grant g
       ) p
  JOIN ax.tb_sys_menu m        ON m.menu_id = p.menu_id AND m.use_flg = 'Y'
  JOIN ax.tb_sys_menu_group mg ON mg.group_id = m.group_id AND mg.use_flg = 'Y'
 GROUP BY p.user_id, p.menu_id;

COMMENT ON VIEW ax.vw_sys_user_menu_perm IS
  '계정 × 유효 화면 접근 권한 = 부서 권한(can_read) ∪ 계정 추가 허용. can_write 는 두 출처의 OR. 화면과 메뉴 그룹이 모두 사용 중(use_flg=''Y'')인 것만. 통합관리자 전 화면 허용은 포함하지 않음(API 가 별도 판정)';

-- ── 3. 학습 답변 컬럼 ────────────────────────────────────────────────────────────────
ALTER TABLE ax.tb_ai_chat_log
    DROP COLUMN IF EXISTS train_answer,
    DROP COLUMN IF EXISTS train_answer_by,
    DROP COLUMN IF EXISTS train_answer_at;

-- ── 4. 전사 화면 · 기본 허용 행 ──────────────────────────────────────────────────────
DELETE FROM ax.tb_sys_dept_menu_perm  WHERE menu_id = 'sys-chat-history';
DELETE FROM ax.tb_sys_user_menu_grant WHERE menu_id = 'sys-chat-history';
DELETE FROM ax.tb_sys_dept_menu_perm
 WHERE menu_id = 'chat-history' AND ins_user = 'V70' AND upd_user = 'V70';
DELETE FROM ax.tb_sys_menu WHERE menu_id = 'sys-chat-history';

-- ── 5. 기록표 ────────────────────────────────────────────────────────────────────────
DROP TABLE IF EXISTS ax.tb_mig_v70_menu_perm_bak;

COMMIT;
