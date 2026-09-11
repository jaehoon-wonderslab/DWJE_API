-- =====================================================================================
--  V24 : 보고서 사용 횟수 — /menu/report "자주 쓰는 보고서" 버튼(최대 5개)
--
--  [배경]
--  보고서 화면이 "보고서 선택 드롭다운 + 자주 쓰는 보고서 버튼" 구성으로 바뀐다(2026-09-09 WEB 요청).
--  자주 쓰는 보고서는 **계정별로 보고서를 만든 횟수** 순이고, 사용자 요청으로 브라우저가 아닌
--  DB 에 둔다. 즐겨찾기(V23 tb_sys_user_favorite)는 순서 목록이라 횟수를 담을 수 없어 따로 둔다.
--  GET/POST /api/v1/reports/usage
--
--  [키]
--  (user_id, menu_id). 보고서를 한 번 만들 때마다 use_cnt + 1, last_used_at = now().
--  INSERT ... ON CONFLICT (user_id, menu_id) DO UPDATE 한 문장으로 처리하면 동시 요청에도 안전하다.
--  보존 기간 정리는 없다 — 계정 × 보고서 조합이라 행이 많지 않다.
--
--  [순위 인덱스를 두지 않는 이유]
--  사용자당 행 수는 보고서 화면 수(현재 7개)를 넘지 않는다. PK 로 사용자 행을 읽고 메모리에서
--  정렬하면 끝이라 (user_id, use_cnt DESC, last_used_at DESC) 인덱스는 읽기에 도움이 없다.
--  반면 use_cnt·last_used_at 은 매 사용마다 바뀌는 컬럼이어서 인덱스가 있으면 갱신마다
--  인덱스 항목이 옮겨지고 HOT 갱신도 막힌다. 보고서 화면이 수십 개로 늘면 그때 추가한다.
--
--  [use_cnt 가 integer 인 이유]
--  상한 약 21억. 한 계정이 1초에 한 번씩 보고서를 만들어도 68년이 걸린다. smallint(32,767)는
--  하루 30건이면 3년 안에 넘칠 수 있어 부족하다.
--
--  [적용]
--  docker exec -e PGPASSWORD=dwje_local dwje-pg psql -U dwje_local -d dwjedb -v ON_ERROR_STOP=1 -f V24__report_usage.sql
--  두 번 실행해도 안전하다.
-- =====================================================================================

CREATE TABLE IF NOT EXISTS ax.tb_rpt_usage (
    user_id       common.d_user_id  NOT NULL,
    menu_id       varchar(30)       NOT NULL,   -- ax.tb_sys_menu.menu_id (웹의 screenId)
    use_cnt       integer           NOT NULL DEFAULT 0,
    last_used_at  timestamptz       NOT NULL DEFAULT now(),
    ins_date      timestamptz       NOT NULL DEFAULT now(),   -- 처음 사용한 시각

    CONSTRAINT pk_rpt_usage PRIMARY KEY (user_id, menu_id),
    CONSTRAINT fk_rpt_usage_user
        FOREIGN KEY (user_id) REFERENCES ax.tb_sys_user(user_id) ON DELETE CASCADE,
    CONSTRAINT fk_rpt_usage_menu
        FOREIGN KEY (menu_id) REFERENCES ax.tb_sys_menu(menu_id) ON DELETE CASCADE,
    CONSTRAINT ck_rpt_usage_cnt CHECK (use_cnt >= 0)
);

COMMENT ON TABLE  ax.tb_rpt_usage              IS '계정별 보고서 사용 횟수 (자주 쓰는 보고서 버튼, 상위 5개). 순위는 use_cnt DESC, last_used_at DESC';
COMMENT ON COLUMN ax.tb_rpt_usage.menu_id      IS '보고서 화면 ID = ax.tb_sys_menu.menu_id. 웹 API 에서는 screenId. 조회 시 use_flg=''Y'' 메뉴만 반환';
COMMENT ON COLUMN ax.tb_rpt_usage.use_cnt      IS '보고서를 만든 횟수. 선택 1회 = +1. 행이 있으면 1 이상이어야 자연스럽다 (INSERT 시 1 로 넣을 것)';
COMMENT ON COLUMN ax.tb_rpt_usage.last_used_at IS '마지막으로 만든 시각. 횟수가 같을 때의 2차 정렬 키';
COMMENT ON COLUMN ax.tb_rpt_usage.ins_date     IS '처음 만든 시각';
