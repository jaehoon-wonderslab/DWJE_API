-- =====================================================================================
--  V21 : 제품·공정별 일목표 마스터
--
--  [배경]
--  제품별 일목표의 정식 출처가 없었다. 지표(PROD_DAY_TARGET)는 공정(wc_cd) 단위로만
--  정의돼 있고 ax.tb_met_metric_value 는 0행이다. 그래서 작성자가 보고서마다 손으로
--  열 줄씩 목표를 넣고 있었다 — 하루는 되지만 한 달은 못 간다.
--
--  2026-09-04 사용자 결정으로 목표를 담을 자리를 만든다.
--
--  [적용일 구간을 두는 이유]
--  목표는 분기·월 단위로 바뀌고 매일 바뀌지 않는다. 날짜마다 한 행을 넣으면
--  같은 값이 수백 행 쌓이고, 바뀔 때 어디까지 고쳐야 하는지 알 수 없다.
--  그래서 "언제부터 적용" 만 넣고, 그 다음 적용일 전까지 유효한 것으로 본다.
--  종료일을 따로 두지 않는 이유는 구간이 끊기거나 겹치는 상태를 만들 수 없게 하기
--  위함이다. (끝을 적으면 시작과 끝이 어긋난 행을 막을 방법이 필요해진다)
--
--  조회는 "그 날짜 이하의 적용일 중 가장 늦은 것" 한 건을 고른다.
--
--  [작성자 입력과의 관계]
--  일일 보고 화면에서 작성자가 그날만 목표를 달리 잡는 일이 있다. 그 값은
--  ax.tb_prod_daily_decision.target_qty(V20) 에 남고 **마스터를 덮어쓴다.**
--  우선순위는 저장값 > 마스터 > 없음(null) 이다.
--
--  [적용]
--  psql -U dwje_local -d dwjedb -v ON_ERROR_STOP=1 -f V21__prod_day_target.sql
--  두 번 실행해도 안전하다.
-- =====================================================================================

CREATE TABLE IF NOT EXISTS ax.tb_prod_day_target (
    target_id   bigserial         PRIMARY KEY,
    plant_cd    common.d_plant_cd NOT NULL,
    product     common.d_item_cd  NOT NULL,
    wc_cd       common.d_wc_cd    NOT NULL,
    apply_from  date              NOT NULL,
    target_qty  numeric(18,6)     NOT NULL,
    remark      varchar(500),
    ins_date    timestamptz       NOT NULL DEFAULT now(),
    ins_user    common.d_user_id,
    upd_date    timestamptz,
    upd_user    common.d_user_id,

    -- 같은 제품·공정에 같은 적용일이 두 번 들어오면 어느 값이 이기는지 알 수 없다.
    CONSTRAINT uq_prod_day_target UNIQUE (plant_cd, product, wc_cd, apply_from),

    -- 목표가 음수일 수는 없다. 0 은 '이 구간에는 계획 없음' 으로 쓸 수 있어 허용한다.
    CONSTRAINT ck_prod_day_target_qty CHECK (target_qty >= 0)
);

-- 조회는 항상 "그 날짜 이하의 적용일 중 가장 늦은 것" 이다.
CREATE INDEX IF NOT EXISTS ix_prod_day_target_lookup
    ON ax.tb_prod_day_target (plant_cd, product, wc_cd, apply_from DESC);

COMMENT ON TABLE  ax.tb_prod_day_target             IS '제품·공정별 일목표 마스터 — 적용일부터 다음 적용일 전까지 유효하다';
COMMENT ON COLUMN ax.tb_prod_day_target.product     IS '제품 코드(model_cd). 품목 매핑이 없으면 item_cd 를 쓴다';
COMMENT ON COLUMN ax.tb_prod_day_target.wc_cd       IS '작업장(공정) 코드. 같은 제품도 공정마다 목표가 다르다';
COMMENT ON COLUMN ax.tb_prod_day_target.apply_from  IS '적용 시작일. 종료일은 두지 않고 다음 적용일 전까지 유효한 것으로 본다';
COMMENT ON COLUMN ax.tb_prod_day_target.target_qty  IS '일목표 수량. 일일 보고에서 작성자가 넣은 값(tb_prod_daily_decision)이 있으면 그쪽이 이긴다';
