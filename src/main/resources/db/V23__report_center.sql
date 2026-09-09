-- =====================================================================================
--  V23 : 보고서 센터 — 사용자 즐겨찾기 화면 · 보고서 작성 상태
--
--  [배경]
--  웹이 보고서 대메뉴를 "보고서 센터" 허브로 바꾼다 (2026-09-09 요청).
--   1) 사용자가 별표한 화면을 사이드바 상단에 고정 → 사용자별 즐겨찾기 저장소가 필요
--      GET/PUT /api/v1/users/me/favorites
--   2) 허브 상단 "오늘 작성할 보고서" 띠 → 화면·대상일 단위 작성 상태가 필요
--      GET /api/v1/reports/status?date=   (NONE | DRAFT | SUBMITTED | APPROVED)
--
--  [왜 상태 테이블을 새로 두는가]
--  2026-09-04 문서 관리(초안·확정·결재, ax.tb_rpt_doc 계열)가 V19 에서 제거되어 보고서는
--  조회 조건으로 매번 만들어 내려받는 산출물이 됐다. 그래서 "제출/승인"을 파생할
--  원천이 없다. 남은 사람 입력은 ax.tb_prod_daily_decision(대상일·제품) 하나뿐이고,
--  이것으로는 DRAFT 까지만 알 수 있다. 워크플로우를 되살리지 않고 "표시 상태"만
--  가볍게 기록하는 한 테이블로 간다. NONE 은 행이 없는 것이다.
--  이름에 doc 을 쓰지 않는다 — V19 에서 지운 tb_rpt_doc 계열과 섞여 보이기 때문이다.
--
--  [화면 ID 는 menu_id 다]
--  웹이 말하는 screenId 는 ax.tb_sys_menu.menu_id 와 같은 값이다. 부서별 메뉴 권한
--  (ax.tb_sys_dept_menu_perm.menu_id, PUT /system/menu-perms 의 screenId)이 이미 그렇게
--  쓰고 있으므로 컬럼명도 menu_id 로 통일한다. API 응답에서는 screenId 로 내보낸다.
--  메뉴는 물리 삭제하지 않고 use_flg='N' 으로 내리므로(V15·V22) 조회 시 use_flg='Y'
--  인 메뉴만 골라야 한다. FK 의 ON DELETE CASCADE 는 물리 삭제될 때의 안전장치다.
--
--  [적용]
--  docker exec -e PGPASSWORD=dwje_local dwje-pg psql -U dwje_local -d dwjedb -v ON_ERROR_STOP=1 -f V23__report_center.sql
--  두 번 실행해도 안전하다.
-- =====================================================================================

-- 1. 사용자 즐겨찾기 화면 --------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ax.tb_sys_user_favorite (
    user_id     common.d_user_id  NOT NULL,
    menu_id     varchar(30)       NOT NULL,   -- ax.tb_sys_menu.menu_id (웹의 screenId)
    sort_seq    smallint          NOT NULL,   -- 0부터. 프로젝트 관례(sort_seq)
    ins_date    timestamptz       NOT NULL DEFAULT now(),

    CONSTRAINT pk_sys_user_favorite PRIMARY KEY (user_id, menu_id),
    -- 순서가 겹치면 사이드바 정렬이 흔들린다. 다만 PUT 이 목록을 통째로 다시 쓰거나
    -- 자리를 서로 바꾸는 UPDATE 를 할 때 문장 중간 상태에서 걸리지 않도록 커밋 시점에 검사한다.
    CONSTRAINT uq_sys_user_favorite_seq UNIQUE (user_id, sort_seq) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_sys_user_favorite_user
        FOREIGN KEY (user_id) REFERENCES ax.tb_sys_user(user_id) ON DELETE CASCADE,
    CONSTRAINT fk_sys_user_favorite_menu
        FOREIGN KEY (menu_id) REFERENCES ax.tb_sys_menu(menu_id) ON DELETE CASCADE,
    CONSTRAINT ck_sys_user_favorite_seq CHECK (sort_seq >= 0)
);
COMMENT ON TABLE  ax.tb_sys_user_favorite          IS '사용자별 즐겨찾기 화면 (보고서 센터 사이드바 고정)';
COMMENT ON COLUMN ax.tb_sys_user_favorite.menu_id  IS '화면 ID = ax.tb_sys_menu.menu_id. 웹 API 에서는 screenId. 조회 시 use_flg=''Y'' 메뉴만 반환';
COMMENT ON COLUMN ax.tb_sys_user_favorite.sort_seq IS '사이드바 표시 순서(0부터). PUT 이 목록 전체를 다시 쓴다';

-- 2. 보고서 작성 상태 ------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ax.tb_rpt_write_state (
    menu_id     varchar(30)       NOT NULL,   -- 보고서 화면 ID (prod-daily, rpt-press-morning …)
    biz_date    date              NOT NULL,   -- 보고서 대상일
    state_cd    varchar(30)       NOT NULL,   -- DRAFT | SUBMITTED | APPROVED (NONE = 행 없음)
    ins_date    timestamptz       NOT NULL DEFAULT now(),
    ins_user    common.d_user_id,
    upd_date    timestamptz       NOT NULL DEFAULT now(),
    upd_user    common.d_user_id,

    CONSTRAINT pk_rpt_write_state PRIMARY KEY (menu_id, biz_date),
    -- 상태 기록은 이력이므로 메뉴가 지워져도 따라 지우지 않는다 (삭제가 막힌다).
    CONSTRAINT fk_rpt_write_state_menu
        FOREIGN KEY (menu_id) REFERENCES ax.tb_sys_menu(menu_id),
    CONSTRAINT ck_rpt_write_state_cd
        CHECK (state_cd IN ('DRAFT', 'SUBMITTED', 'APPROVED'))
);
-- GET /reports/status?date= 는 "그 날짜의 모든 화면" 을 읽는다. PK 선두가 menu_id 라 날짜 단독 조회용 인덱스가 필요하다.
CREATE INDEX IF NOT EXISTS ix_rpt_write_state_date ON ax.tb_rpt_write_state (biz_date);

COMMENT ON TABLE  ax.tb_rpt_write_state          IS '보고서 화면·대상일별 작성 상태 표시 (워크플로우 아님, 행 없음 = NONE)';
COMMENT ON COLUMN ax.tb_rpt_write_state.menu_id  IS '보고서 화면 ID = ax.tb_sys_menu.menu_id. 보고서 화면인지는 API 가 검증한다';
COMMENT ON COLUMN ax.tb_rpt_write_state.biz_date IS '보고서 대상일 (작성일이 아님)';
COMMENT ON COLUMN ax.tb_rpt_write_state.state_cd IS 'DRAFT | SUBMITTED | APPROVED. NONE 은 행이 없는 상태';
