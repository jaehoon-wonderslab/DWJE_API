-- =====================================================================================
--  V70 : 메뉴 접근 권한 조회/쓰기 칸 통합 · 전사 자연어 질의 이력 화면 · 학습 답변 컬럼 (2026-10-03)
--
--  [배경]
--  · 메뉴 접근 권한 화면이 부서 × 화면마다 조회(can_read) · 쓰기(can_write) 두 칸을 따로 두어, 화면에는
--    들어가는데 저장이 403 인 경우가 잦았다. 사용자 결정으로 칸을 「접근」 하나로 합친다 — 화면에 접근할 수
--    있으면 그 화면의 모든 동작을 허용한다. 예외는 서버 규칙 하나다: 미배정 부서 계정은 어떤 화면에서도
--    쓰기 동작을 못 한다(E-AUTH-004). 통합관리자는 행 없이 전부 통과한다.
--  · 자연어 질의 이력(chat-history, /history/chat)은 로그인한 계정 본인 질의만 보는 화면이 된다.
--    전 사용자 질의 · 검수 · 학습 데이터는 새 화면 sys-chat-history(시스템관리 › 전사 자연어 질의 이력,
--    /system/chat-history)로 옮긴다. 이 화면은 **관리자(통합관리자 부서) 전용**이다(2026-10-03 사용자 결정) —
--    권한 행을 두지 않고 통합관리자만 행 없이 통과한다. 다른 부서 · 계정에 주는 것은 관리 화면 규칙대로
--    통합관리자만 할 수 있다(API ADMIN_SCREENS).
--  · 전사 화면의 「답변 추가(학습 데이터)」 칸을 저장할 곳이 없었다 → tb_ai_chat_log.train_answer* 3개.
--
--  [이 파일이 하는 일]
--   1. ax.tb_sys_menu 에 sys-chat-history 화면 행(시스템관리 그룹 맨 끝 13)
--   2. 되돌리기용 기록표 ax.tb_mig_v70_menu_perm_bak — can_write 를 지우기 전 값(처음 적용 때만 채움)
--   3. sys-chat-history 권한 행은 넣지 않는다(관리자 전용). chat-history 쓰기 권한이 있던 부서(로컬은 전산팀)
--      · 계정 추가 허용도 옮기지 않는다 — 그 사람들은 본인 질의 화면(chat-history)만 쓰게 된다
--   4. chat-history 를 사용 중인 모든 부서(통합관리자 포함)에 기본 허용 — 행이 없을 때만 넣는다
--   5. ax.tb_ai_chat_log 학습 답변 컬럼 3개(모두 NULL 허용)
--   6. 판정 뷰 ax.vw_sys_user_menu_perm 을 지우고 can_write 컬럼 2개를 지운 뒤 뷰를 다시 만든다
--      (열: user_id, menu_id, from_dept, from_grant). can_read 는 남긴다 — 행 존재 = 접근이고,
--      기존 질의가 p.can_read 로 거른다.
--
--  [부서 찾기]
--  부서명을 보지 않는다. chat-history 기본 허용은 사용 중인 모든 부서에 행이 없을 때만 넣는다.
--
--  [순서]
--  API 배포 → 이 파일 → WEB 배포. 새 API 는 can_write 를 읽지 않고 학습 답변 컬럼이 없어도 동작하므로 먼저 올려도 된다.
--  거꾸로 옛 API 가 이 파일 뒤에 돌면 판정 뷰의 can_write 를 읽다가 모든 요청이 500 이 된다(로그인 포함).
--
--  [실행]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f V70__menu_access_unify_chat_history_admin.sql
--  두 번 실행해도 안전하다. 되돌리기는 rollback/V70__down.sql.
-- =====================================================================================

BEGIN;

-- ── 1. 화면 행 ──────────────────────────────────────────────────────────────────────
INSERT INTO ax.tb_sys_menu (menu_id, menu_nm, group_id, parent_menu_id, is_sub_page, route_path, tag_cd, sort_seq,
                            use_flg, ins_user, upd_user)
VALUES ('sys-chat-history', '전사 자연어 질의 이력', 'system', NULL, false, '/system/chat-history', 'NEW', 13, 'Y', 'V70', 'V70')
ON CONFLICT (menu_id) DO NOTHING;

-- ── 2. can_write 기록 (can_write 컬럼이 남아 있을 때만) ─────────────────────────────────────
--     sys-chat-history 는 관리자 전용이라 부서 · 계정 추가 허용으로 옮기지 않는다
CREATE TABLE IF NOT EXISTS ax.tb_mig_v70_menu_perm_bak (
    src        varchar(4)  NOT NULL,
    owner_id   varchar(30) NOT NULL,
    menu_id    varchar(30) NOT NULL,
    can_write  boolean     NOT NULL,
    bak_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_tb_mig_v70_menu_perm_bak PRIMARY KEY (src, owner_id, menu_id)
);

DO $$
DECLARE
    v_cnt integer;
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = 'ax' AND table_name = 'tb_sys_dept_menu_perm' AND column_name = 'can_write') THEN
        EXECUTE $q$
            INSERT INTO ax.tb_mig_v70_menu_perm_bak (src, owner_id, menu_id, can_write)
            SELECT 'DEPT', p.dept_id::text, p.menu_id, p.can_write FROM ax.tb_sys_dept_menu_perm p
            ON CONFLICT DO NOTHING $q$;

        GET DIAGNOSTICS v_cnt = ROW_COUNT;
        RAISE NOTICE 'V70: 부서 권한 can_write 기록 % 건', v_cnt;
    ELSE
        RAISE NOTICE 'V70: tb_sys_dept_menu_perm.can_write 가 이미 없어 부서 이관은 건너뜀(이미 적용됨)';
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = 'ax' AND table_name = 'tb_sys_user_menu_grant' AND column_name = 'can_write') THEN
        EXECUTE $q$
            INSERT INTO ax.tb_mig_v70_menu_perm_bak (src, owner_id, menu_id, can_write)
            SELECT 'USER', g.user_id, g.menu_id, g.can_write FROM ax.tb_sys_user_menu_grant g
            ON CONFLICT DO NOTHING $q$;

        GET DIAGNOSTICS v_cnt = ROW_COUNT;
        RAISE NOTICE 'V70: 계정 추가 허용 can_write 기록 % 건', v_cnt;
    END IF;
END $$;

-- ── 4. chat-history 기본 허용 (사용 중인 모든 부서) ─────────────────────────────────────
INSERT INTO ax.tb_sys_dept_menu_perm (dept_id, menu_id, can_read, ins_user, upd_user)
SELECT d.dept_id, 'chat-history', true, 'V70', 'V70'
  FROM ax.tb_sys_dept d
 WHERE d.use_flg = 'Y'
   AND EXISTS (SELECT 1 FROM ax.tb_sys_menu m WHERE m.menu_id = 'chat-history')
ON CONFLICT ON CONSTRAINT pk_tb_sys_dept_menu_perm DO NOTHING;

-- ── 5. 학습 답변 컬럼 ────────────────────────────────────────────────────────────────
ALTER TABLE ax.tb_ai_chat_log
    ADD COLUMN IF NOT EXISTS train_answer    text,
    ADD COLUMN IF NOT EXISTS train_answer_by varchar(30),
    ADD COLUMN IF NOT EXISTS train_answer_at timestamptz;

COMMENT ON COLUMN ax.tb_ai_chat_log.train_answer IS
  '학습 답변 — 전사 자연어 질의 이력 화면의 「답변 추가(학습 데이터)」. 4000자 이내. 학습데이터 내보내기는 이 값이 있으면 평가와 관계없이 넣고 응답 대신 이 값을 씀. NULL = 없음';
COMMENT ON COLUMN ax.tb_ai_chat_log.train_answer_by IS
  '학습 답변 작성자 사번 (ax.tb_sys_user.user_id). 답변을 지우면 NULL';
COMMENT ON COLUMN ax.tb_ai_chat_log.train_answer_at IS
  '학습 답변 저장 시각. 답변을 지우면 NULL';

-- ── 6. 판정 뷰 · can_write 컬럼 ─────────────────────────────────────────────────────
DROP VIEW IF EXISTS ax.vw_sys_user_menu_perm;

ALTER TABLE ax.tb_sys_dept_menu_perm  DROP COLUMN IF EXISTS can_write;
ALTER TABLE ax.tb_sys_user_menu_grant DROP COLUMN IF EXISTS can_write;

CREATE VIEW ax.vw_sys_user_menu_perm AS
SELECT p.user_id,
       p.menu_id,
       bool_or(p.src = 'DEPT') AS from_dept,
       bool_or(p.src = 'USER') AS from_grant
  FROM (
        SELECT u.user_id, dp.menu_id, 'DEPT'::text AS src
          FROM ax.tb_sys_user u
          JOIN ax.tb_sys_dept_menu_perm dp ON dp.dept_id = u.dept_id
         WHERE dp.can_read
        UNION ALL
        SELECT g.user_id, g.menu_id, 'USER'::text
          FROM ax.tb_sys_user_menu_grant g
       ) p
  JOIN ax.tb_sys_menu m        ON m.menu_id = p.menu_id AND m.use_flg = 'Y'
  JOIN ax.tb_sys_menu_group mg ON mg.group_id = m.group_id AND mg.use_flg = 'Y'
 GROUP BY p.user_id, p.menu_id;

-- ── 주석 ─────────────────────────────────────────────────────────────────────────────
COMMENT ON VIEW ax.vw_sys_user_menu_perm IS
  '계정 × 유효 화면 접근 권한 = 부서 권한(can_read) ∪ 계정 추가 허용. 접근할 수 있는 화면은 모든 동작을 허용(미배정 계정의 쓰기 동작 거부는 API 가 판정). 화면과 메뉴 그룹이 모두 사용 중(use_flg=''Y'')인 것만. 통합관리자 전 화면 허용은 포함하지 않음(API 가 별도 판정)';
COMMENT ON COLUMN ax.tb_sys_dept_menu_perm.can_read IS
  '접근 권한. 행이 있으면서 true 여야 화면에 들어갈 수 있고, 들어갈 수 있으면 그 화면의 모든 동작을 허용(2026-10 조회 · 쓰기 칸 통합). 메뉴 접근 권한 화면 표의 「접근」 칸';

COMMENT ON TABLE  ax.tb_mig_v70_menu_perm_bak IS
  '메뉴 권한 칸 통합 되돌리기 기록 — 조회 · 쓰기 칸을 합칠 때 지운 can_write 의 이전 값. 되돌리기 스크립트가 쓰고 지움';
COMMENT ON COLUMN ax.tb_mig_v70_menu_perm_bak.src       IS '출처 — DEPT=부서 권한(ax.tb_sys_dept_menu_perm), USER=계정 추가 허용(ax.tb_sys_user_menu_grant)';
COMMENT ON COLUMN ax.tb_mig_v70_menu_perm_bak.owner_id  IS 'DEPT 면 부서 ID, USER 면 사번';
COMMENT ON COLUMN ax.tb_mig_v70_menu_perm_bak.menu_id   IS '화면 ID (ax.tb_sys_menu)';
COMMENT ON COLUMN ax.tb_mig_v70_menu_perm_bak.can_write IS '지우기 전 쓰기 권한 값';
COMMENT ON COLUMN ax.tb_mig_v70_menu_perm_bak.bak_at    IS '기록 시각';

COMMIT;
