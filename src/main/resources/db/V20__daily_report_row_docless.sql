-- =====================================================================================
--  V20 : 일일 생산현황 보고 회의 결과 — 문서 없는 저장소
--
--  [배경]
--  V19 가 문서 관리를 제거하면서 ax.tb_rpt_doc_row 도 사라진다. 그 테이블은
--  doc_id(문서)로 키를 잡고 있었기 때문이다.
--
--  그런데 그 테이블이 담던 것은 문서 부속물이 아니라 **아침회의 결과**다 —
--  제품별 일목표·판정·담당·기한. 2026-09-04 에 화면까지 붙어 왕복 확인이 끝난
--  기능이라 문서와 함께 지우면 그 날 납품한 기능이 되돌아간다.
--
--  그래서 키를 문서에서 **(대상일, 제품)** 으로 옮겨 같은 기능을 유지한다.
--  보고서는 조회 조건으로 매번 만들어 내려받는 산출물이 되었지만, 회의에서 정한
--  목표·담당·기한은 산출물이 아니라 사람이 남기는 결정이다.
--
--  [일목표를 여기 두는 것은 여전히 임시다]
--  제품별 일목표의 정식 출처가 없다. 지표(PROD_DAY_TARGET)는 공정 단위로만
--  정의돼 있고 tb_met_metric_value 는 0행이다. 제품·공정별 일목표 마스터가
--  생기면 target_qty 는 그쪽 참조로 옮겨야 한다.
--
--  [적용]
--  psql -U dwje_local -d dwjedb -v ON_ERROR_STOP=1 -f V20__daily_report_row_docless.sql
--  두 번 실행해도 안전하다. (IF NOT EXISTS)
-- =====================================================================================

CREATE TABLE IF NOT EXISTS ax.tb_prod_daily_decision (
    target_date date              NOT NULL,
    product     common.d_item_cd  NOT NULL,
    target_qty  numeric(18,6),
    decision    varchar(1000),
    dri         varchar(100),
    due_date    date,
    ins_date    timestamptz       NOT NULL DEFAULT now(),
    ins_user    common.d_user_id,
    upd_date    timestamptz,
    upd_user    common.d_user_id,

    -- 문서가 없으므로 대상일 + 제품이 곧 식별자다.
    CONSTRAINT pk_prod_daily_decision PRIMARY KEY (target_date, product),

    -- 목표 수량은 음수가 될 수 없다. 0 은 '오늘 계획 없음' 으로 쓸 수 있어 허용한다.
    CONSTRAINT ck_prod_daily_decision_target_qty CHECK (target_qty IS NULL OR target_qty >= 0)
);

COMMENT ON TABLE  ax.tb_prod_daily_decision            IS '일일 생산현황 보고 아침회의 결과 — 제품별 일목표·판정·담당·기한. 문서를 저장하지 않으므로 (대상일, 제품) 이 키다';
COMMENT ON COLUMN ax.tb_prod_daily_decision.product    IS '제품 코드(model_cd). 품목 매핑이 없으면 item_cd 가 그대로 들어온다';
COMMENT ON COLUMN ax.tb_prod_daily_decision.target_qty IS '작성자가 입력한 일목표. 정식 출처(제품·공정별 목표 마스터)가 생기면 그쪽 참조로 옮긴다';
COMMENT ON COLUMN ax.tb_prod_daily_decision.decision   IS '아침회의 판정 내용';
COMMENT ON COLUMN ax.tb_prod_daily_decision.dri        IS '담당(부서 또는 담당자)';
COMMENT ON COLUMN ax.tb_prod_daily_decision.due_date   IS '조치 기한';
