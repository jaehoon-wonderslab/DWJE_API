-- =====================================================================================
--  V18 : 일일 생산현황 보고 양식 본문 저장 (제품별 일목표·판정·담당·기한)
--
--  [배경]
--  아침회의에서 정한 제품별 일목표·판정·담당자·기한이 화면 상태로만 있어
--  새로고침하면 사라진다. 회의 결과이므로 남아야 한다.
--
--  [왜 tb_rpt_doc_field 를 쓰지 않는가]
--  기존 항목 저장소(tb_rpt_doc_field)는 (doc_id, field_seq) 에 한 항목씩 담는
--  세로 구조다. 제품 10종 × 항목 4개를 넣으면 40행이 되고, "D65S 의 담당자"를
--  찾으려면 field_code 문자열을 파싱해야 한다. 제품 단위로 읽고 쓰는 표라서
--  가로 구조가 맞다.
--
--  [키를 제품까지만 두는 이유]
--  양식 본문 조회는 제품 × 공정으로 나오지만, 화면은 제품 단위로 합쳐 한 줄씩
--  보여 주고 회의 결과도 제품 단위로 정한다. (웹 요청 본문도 product 만 보낸다)
--  공정까지 키에 넣으면 같은 제품인데 프레스 작업장마다 담당자가 갈리는,
--  현장에 없는 상태가 표현 가능해진다.
--
--  [일목표를 여기 두는 이유 — 임시임을 밝혀 둔다]
--  제품별 일목표의 정식 출처가 없다. 지표(PROD_DAY_TARGET)는 공정 단위로만
--  정의돼 있고 tb_met_metric_value 는 0행이다. 그래서 작성자가 보고서마다
--  손으로 넣은 값을 그 보고서에 붙여 둔다. 제품·공정별 일목표 마스터가
--  생기면 이 컬럼은 그쪽을 참조하는 형태로 옮겨야 한다.
--
--  [적용]
--  psql -U dwje_local -d dwjedb -f V18__rpt_doc_row.sql
--  두 번 실행해도 안전하다. (IF NOT EXISTS)
-- =====================================================================================

CREATE TABLE IF NOT EXISTS ax.tb_rpt_doc_row (
    doc_id      bigint        NOT NULL,
    product     varchar(50)   NOT NULL,
    target_qty  numeric(18,6),
    decision    varchar(1000),
    dri         varchar(100),
    due_date    date,
    ins_date    timestamptz   NOT NULL DEFAULT now(),
    ins_user    varchar(20),
    upd_date    timestamptz,
    upd_user    varchar(20),

    CONSTRAINT pk_rpt_doc_row PRIMARY KEY (doc_id, product),

    -- 보고서가 지워지면 그 표도 함께 지운다. 문서 없는 표는 읽을 방법이 없다.
    CONSTRAINT fk_rpt_doc_row_doc FOREIGN KEY (doc_id)
        REFERENCES ax.tb_rpt_doc (doc_id) ON DELETE CASCADE,

    -- 목표 수량은 음수가 될 수 없다. 0 은 '오늘 계획 없음' 으로 쓸 수 있어 허용한다.
    CONSTRAINT ck_rpt_doc_row_target_qty CHECK (target_qty IS NULL OR target_qty >= 0)
);

COMMENT ON TABLE  ax.tb_rpt_doc_row            IS '일일 생산현황 보고 양식 본문 — 제품별 일목표·판정·담당·기한';
COMMENT ON COLUMN ax.tb_rpt_doc_row.product    IS '제품 코드(model_cd). 품목 매핑이 없으면 item_cd 가 그대로 들어온다';
COMMENT ON COLUMN ax.tb_rpt_doc_row.target_qty IS '작성자가 입력한 일목표. 정식 출처가 생기면 그쪽 참조로 옮긴다';
COMMENT ON COLUMN ax.tb_rpt_doc_row.decision   IS '아침회의 판정 내용';
COMMENT ON COLUMN ax.tb_rpt_doc_row.dri        IS '담당(부서 또는 담당자)';
COMMENT ON COLUMN ax.tb_rpt_doc_row.due_date   IS '조치 기한';
