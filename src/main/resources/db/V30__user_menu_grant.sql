-- =====================================================================================
--  V30 : 계정별 추가 허용 메뉴 — 부서 권한 UNION 계정 수동 허용 (2026-09-13 /system/account 개선)
--
--  [배경]
--  지금까지 화면 접근 권한은 부서 단위 하나뿐이었다(ax.tb_sys_dept_menu_perm).
--  계정은 소속 부서의 권한을 그대로 상속하고, 계정 단위로 더 열어 줄 방법이 없었다.
--  현장에서는 "같은 부서지만 이 사람만 이 화면을 봐야 한다"(겸직·대행·일시 위임)가 생긴다.
--  이때 부서 권한을 열면 부서원 전체가 함께 열리고, 그 사람만 위해 부서를 새로 만들면
--  부서 마스터가 사람 수만큼 늘어난다. 그래서 계정 단위 예외를 담는 표를 하나 둔다.
--
--  [추가 허용만 — 차단 override 는 두지 않는다]
--  이 표에 행이 있으면 그 화면이 "열린다". 행이 없으면 아무 영향이 없다(부서 권한 그대로).
--  행으로 화면을 "닫는" 길은 만들지 않는다. 그래서 tb_sys_dept_menu_perm 과 달리
--  can_read 컬럼을 두지 않았다 — can_read = false 행이 존재할 수 있으면 그것이 곧
--  차단 override 가 되고, 부서 권한 화면에서 열어 준 것이 계정 표에서 조용히 막히는
--  이중 진실이 생긴다. 열람 허용은 "행의 존재" 로만 표현한다.
--
--      유효 메뉴 = 부서 권한(can_read) ∪ 계정 추가 허용
--      유효 쓰기 = 부서 권한 can_write OR 계정 추가 허용 can_write     (OR 이므로 역시 가산)
--
--  통합관리자 부서(is_super_admin)는 지금처럼 조회 없이 전 화면이므로 이 표와 무관하다.
--
--  [컬럼명이 emp_no 가 아니라 user_id 인 이유]
--  웹·API 가 쓰는 이름은 empNo 가 맞다. 다만 DB 안에서 사번은 common.d_user_id 도메인이고
--  tb_sys_user.user_id · 전 테이블 ins_user/upd_user 와 같은 값이다. 같은 값에 표마다 다른
--  이름을 주면 조인이 읽히지 않는다. 앞선 같은 결정 두 건(V23 tb_sys_user_favorite,
--  V24 tb_rpt_usage — 둘 다 웹 원안 emp_no → user_id)과 맞춘다.
--  menu_id 는 웹의 screenId 와 같은 값이며 이름·길이(30)를 tb_sys_menu 와 맞췄다.
--
--  [삭제 정리]
--   · 계정 삭제 → FK ON DELETE CASCADE 로 그 계정의 추가 허용이 함께 사라진다.
--   · 화면 물리 삭제 → 같은 방식으로 사라진다.
--   · 화면을 use_flg='N' 으로 내린 경우(V15·V22 처럼 물리 삭제하지 않는다)는 행이 남는다.
--     읽는 쪽에서 use_flg='Y' 인 메뉴만 골라야 한다 — 아래 뷰가 이미 그렇게 한다.
--   · 부서 권한이 나중에 같은 화면을 포함하게 되면 그 추가 허용 행은 무의미해질 뿐
--     해를 끼치지 않는다(UNION). 지우는 것은 관리 화면의 판단이다(정리 질의는 계약 문서에).
--
--  [뷰를 함께 두는 이유]
--  UNION 규칙을 읽는 쪽마다 다시 쓰면 한 곳만 틀려도 권한이 새거나 막힌다. 규칙을 DB 에
--  한 번 적는다. ax.vw_sys_user_menu_perm 은 계정 × 유효 화면을 돌려주고 출처(부서/계정)도
--  함께 알려 준다. 통합관리자 전 화면 허용은 뷰에 넣지 않았다 — 조회 없이 통과시키는
--  기존 API 판정을 그대로 두는 편이 싸고, 뷰가 전 계정 × 전 메뉴로 부풀지도 않는다.
--
--  [데이터를 넣지 않는다]
--  실제 사용자에게 권한을 임의로 부여하지 않는다. 이 마이그레이션의 INSERT 는
--  공통코드 1행(변경 이력 구분 USER_MENU_PERM)뿐이다.
--
--  [적용]
--  docker exec -e PGPASSWORD=dwje_local dwje-pg psql -U dwje_local -d dwjedb -v ON_ERROR_STOP=1 -f V30__user_menu_grant.sql
--  두 번 실행해도 안전하다.
-- =====================================================================================

-- 1. 계정 × 화면 추가 허용 ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ax.tb_sys_user_menu_grant (
    user_id      common.d_user_id NOT NULL,              -- 사번. 웹·API 의 empNo
    menu_id      varchar(30)      NOT NULL,              -- ax.tb_sys_menu.menu_id (웹의 screenId)
    can_write    boolean          NOT NULL DEFAULT false,-- 입력·수정까지 추가로 열어 줄 때 true
    grant_reason varchar(200),                           -- 부여 사유 (겸직·대행·기간 위임 등). 선택
    ins_date     timestamptz      NOT NULL DEFAULT now(),
    ins_user     common.d_user_id,                       -- 부여한 관리자 사번
    upd_date     timestamptz      NOT NULL DEFAULT now(),
    upd_user     common.d_user_id,

    CONSTRAINT pk_sys_user_menu_grant PRIMARY KEY (user_id, menu_id),
    CONSTRAINT fk_sys_user_menu_grant_user
        FOREIGN KEY (user_id) REFERENCES ax.tb_sys_user(user_id) ON DELETE CASCADE,
    CONSTRAINT fk_sys_user_menu_grant_menu
        FOREIGN KEY (menu_id) REFERENCES ax.tb_sys_menu(menu_id) ON DELETE CASCADE
);
COMMENT ON TABLE  ax.tb_sys_user_menu_grant              IS '계정 × 화면 추가 허용 — 부서 권한에 더해 이 계정에만 열어 주는 화면. 행의 존재 = 열람 허용이며, 행으로 차단하는 용법은 없다(추가 허용 전용)';
COMMENT ON COLUMN ax.tb_sys_user_menu_grant.user_id      IS '사번 = ax.tb_sys_user.user_id. 웹·API 응답에서는 empNo. 계정 삭제 시 함께 삭제(CASCADE)';
COMMENT ON COLUMN ax.tb_sys_user_menu_grant.menu_id      IS '화면 ID = ax.tb_sys_menu.menu_id. 웹 API 에서는 screenId. 조회 시 use_flg=''Y'' 메뉴만 반환';
COMMENT ON COLUMN ax.tb_sys_user_menu_grant.can_write    IS '입력·수정 권한 추가 부여. 유효 쓰기 권한 = 부서 can_write OR 이 값 (가산이며 부서 권한을 낮추지 않는다)';
COMMENT ON COLUMN ax.tb_sys_user_menu_grant.grant_reason IS '부여 사유. 부서 기준을 벗어난 예외이므로 남겨 두면 감사에서 되짚기 쉽다. 없으면 NULL';
COMMENT ON COLUMN ax.tb_sys_user_menu_grant.ins_user     IS '이 권한을 부여한 관리자 사번. 변경 이력 상세는 ax.tb_sys_perm_log (act_cd = USER_MENU_PERM)';

-- 화면을 지우거나 "이 화면을 추가로 가진 사람" 을 찾을 때 쓴다 (부서 권한의 ix_dept_menu_perm_menu 와 같은 목적)
CREATE INDEX IF NOT EXISTS ix_sys_user_menu_grant_menu ON ax.tb_sys_user_menu_grant (menu_id);

-- 2. 기존 표의 설명 바로잡기 ------------------------------------------------------------
--    "권한은 전부 부서를 따른다" · "행이 없으면 차단" 이 더는 전부가 아니다.
COMMENT ON TABLE ax.tb_sys_user IS
    '가입 계정 — 아이디(사번) · 이름 · 부서 · 직급 · 상태만 관리한다. 화면/데이터 접근 권한은 소속 부서 설정을 따르며, 화면 권한만 계정별 추가 허용(ax.tb_sys_user_menu_grant)을 더할 수 있다';
COMMENT ON TABLE ax.tb_sys_dept_menu_perm IS
    '부서 × 화면 접근 권한 — 계정 권한의 기본값. 통합관리자 부서(is_super_admin)는 행 없이 전체 허용으로 판정한다. 여기에 행이 없어도 ax.tb_sys_user_menu_grant 로 계정에 개별 허용될 수 있다(차단은 두 표 모두 행이 없을 때)';

-- 3. 권한 변경 이력 구분 코드 추가 -------------------------------------------------------
--    ax.tb_sys_perm_log.act_cd 가 참조하는 그룹. 계정별 추가 허용은 부서 권한(MENU_PERM)과
--    대상이 달라 같은 코드로 묶으면 이력 화면에서 누구의 권한이 바뀐 것인지 읽히지 않는다.
INSERT INTO ax.tb_sys_code (group_cd, code, code_nm, code_desc, sort_seq, use_flg, ins_user, upd_user) VALUES
 ('SYS_PERM_ACT', 'USER_MENU_PERM', '계정 추가 메뉴 권한', '계정별 추가 허용 화면 부여·회수 (ax.tb_sys_user_menu_grant). 부서 단위 변경은 MENU_PERM', 5, 'Y', 'V30', 'V30')
ON CONFLICT (group_cd, code) DO NOTHING;

-- 4. 유효 화면 권한 뷰 (부서 ∪ 계정) ----------------------------------------------------
--    통합관리자(is_super_admin) 전 화면 허용은 포함하지 않는다 — API 가 조회 없이 통과시킨다.
--    use_flg='N' 으로 내린 화면은 여기서 걸러진다.
CREATE OR REPLACE VIEW ax.vw_sys_user_menu_perm AS
SELECT p.user_id,
       p.menu_id,
       bool_or(p.can_write)            AS can_write,
       bool_or(p.src = 'DEPT')         AS from_dept,   -- 부서 권한으로 열린 화면
       bool_or(p.src = 'USER')         AS from_grant   -- 계정 추가 허용으로 열린 화면
  FROM (
        SELECT u.user_id, dp.menu_id, dp.can_write, 'DEPT'::text AS src
          FROM ax.tb_sys_user u
          JOIN ax.tb_sys_dept_menu_perm dp ON dp.dept_id = u.dept_id
         WHERE dp.can_read
        UNION ALL
        SELECT g.user_id, g.menu_id, g.can_write, 'USER'::text
          FROM ax.tb_sys_user_menu_grant g
       ) p
  JOIN ax.tb_sys_menu m ON m.menu_id = p.menu_id
 WHERE m.use_flg = 'Y'
 GROUP BY p.user_id, p.menu_id;

COMMENT ON VIEW ax.vw_sys_user_menu_perm IS '계정 × 유효 화면 접근 권한 = 부서 권한(can_read) ∪ 계정 추가 허용. can_write 는 두 출처의 OR. 사용 중인 화면(use_flg=''Y'')만. 통합관리자 전 화면 허용은 포함하지 않는다(API 가 별도 판정)';
