-- =====================================================================================
--  1공장 프레스 설비별 · 생산모델별 실적/불량 현황
--
--  질문: "1공장의 press 기계, 그 기계에서 생산중인 모델, 생산 수량과 불량 상태"
--
--  ── 이 데이터에서 반드시 알아야 할 3가지 ───────────────────────────────────────────
--
--  1) "1공장" 은 plant_cd 가 아니다.
--     mes 의 plant_cd 는 전건 'PL01' 하나뿐이다. 공장 구분은 워크센터 *이름* 에 들어 있다.
--       W110  A-프레스 작업장(M-1공장)   ← 1공장 프레스
--       W150  B-프레스 작업장(M-2공장)
--       W120  C-프레스 작업장(M-3공장)
--       S141  J프레스 작업장(M-1공장)    ← valid_to_dt 2023-03-13, 이미 폐지
--     그래서 wc_nm 으로 '프레스' + 'M-1공장' 을 걸고 유효기간(valid_from/to)까지 본다.
--     wc_cd 를 직접 쓰고 싶으면 target_wc 를 `w.wc_cd = 'W110'` 으로 바꾸면 된다.
--
--  2) 불량 이력은 LOT 이 아니라 (lot_no + serial_no) 로 붙여야 한다.
--     이 데이터의 lot_no 는 날짜 문자열('20260828')이라 LOT 만으로 조인하면
--     같은 날 전 설비의 불량이 모든 설비에 똑같이 붙는다.
--     tb_pop_defect_hist 에는 eqpt_cd 가 없으므로, 라벨 이력의 PK
--     (plant_cd, wc_cd, lot_no, serial_no) 로 조인해 설비를 특정한다.
--     → 매칭률 100%, sum(defect_hist.qty) 와 label_hist.defect 합계가 정확히 일치함을 확인했다.
--
--  3) tb_md_eqpt.model_nm 은 설비 *기종* 이지 생산 모델이 아니다.
--     (1,540대 중 30대만 채워져 있어 이 쿼리에서는 쓰지 않는다.)
--     생산 모델은 tb_pop_label_hist.item_cd → tb_md_item.item_nm 이다.
--
--  ── 파라미터 ─────────────────────────────────────────────────────────────────────
--    :plantCd  사업장 코드 — 'PL01'
--    :fromTs   조회 시작 일시 (>=)
--    :toTs     조회 종료 일시 (<)
--
--  성능: 1일 범위 약 8ms, 7일 범위 약 30ms
--        (ix_pop_label_live(plant_cd, wc_cd, ins_date) WHERE del_flg='N' 사용)
--
--  ※ mes 스키마는 조회 전용이다. 이 스크립트는 SELECT 만 한다.
-- =====================================================================================

WITH target_wc AS (
    -- 1공장(M-1공장) 프레스 작업장. 유효기간이 지난 작업장은 제외한다.
    SELECT w.plant_cd, w.wc_cd, w.wc_nm
    FROM mes.tb_md_workcenter w
    WHERE w.plant_cd = :plantCd
      AND w.wc_nm LIKE '%프레스%'
      AND w.wc_nm LIKE '%M-1공장%'
      AND now() BETWEEN w.valid_from_dt AND w.valid_to_dt
),
label AS (
    -- 대상 기간의 라벨 실적. 이후 집계와 불량 조인이 모두 이 집합을 재사용한다.
    SELECT lh.plant_cd, lh.wc_cd, lh.lot_no, lh.serial_no,
           lh.eqpt_cd, lh.item_cd, lh.mold_cd,
           coalesce(lh.normal, 0) AS ok_qty,
           coalesce(lh.defect, 0) AS ng_qty,
           lh.ins_date
    FROM mes.tb_pop_label_hist lh
    INNER JOIN target_wc t ON t.plant_cd = lh.plant_cd AND t.wc_cd = lh.wc_cd
    WHERE lh.del_flg   = 'N'
      AND lh.eqpt_cd IS NOT NULL      -- 설비가 찍히지 않은 실적은 기계별 집계 대상이 아니다
      AND lh.ins_date >= :fromTs
      AND lh.ins_date <  :toTs
),
agg AS (
    -- 설비 × 생산모델 단위 집계
    SELECT plant_cd, wc_cd, eqpt_cd, item_cd,
           sum(ok_qty)          AS ok_qty,
           sum(ng_qty)          AS ng_qty,
           sum(ok_qty + ng_qty) AS total_qty,
           count(*)             AS label_cnt,
           max(ins_date)        AS last_at
    FROM label
    GROUP BY 1, 2, 3, 4
),
defect AS (
    -- 불량 유형 분해. serial_no 까지 붙여 설비를 특정한다. (위 주석 2번)
    SELECT l.plant_cd, l.wc_cd, l.eqpt_cd, l.item_cd,
           coalesce(md.defect_nm, dh.defect_cd) AS defect_nm,
           sum(dh.qty)                          AS defect_qty
    FROM label l
    INNER JOIN mes.tb_pop_defect_hist dh
            ON dh.plant_cd  = l.plant_cd
           AND dh.wc_cd     = l.wc_cd
           AND dh.lot_no    = l.lot_no
           AND dh.serial_no = l.serial_no
    LEFT JOIN mes.tb_md_defect md
            ON md.plant_cd = dh.plant_cd AND md.defect_cd = dh.defect_cd
    GROUP BY 1, 2, 3, 4, 5
)
SELECT t.wc_nm                                            AS "공정",
       a.eqpt_cd                                          AS "설비코드",
       e.eqpt_nm                                          AS "설비명",
       a.item_cd                                          AS "품목코드",
       i.item_nm                                          AS "생산모델",
       split_part(a.item_cd, '-', 1)                      AS "모델코드",
       mold.mold_cd                                       AS "금형",
       a.total_qty                                        AS "생산수량",
       a.ok_qty                                           AS "양품수량",
       a.ng_qty                                           AS "불량수량",
       round(100.0 * a.ng_qty / nullif(a.total_qty, 0), 2) AS "불량률",
       d.defect_detail                                    AS "주요불량유형",
       to_char(a.last_at, 'YYYY-MM-DD HH24:MI')           AS "최종실적시각"
FROM agg a
INNER JOIN target_wc t      ON t.plant_cd = a.plant_cd AND t.wc_cd   = a.wc_cd
LEFT  JOIN mes.tb_md_eqpt e ON e.plant_cd = a.plant_cd AND e.eqpt_cd = a.eqpt_cd
LEFT  JOIN mes.tb_md_item i ON i.plant_cd = a.plant_cd AND i.item_cd = a.item_cd
-- 가장 최근 실적에 찍힌 금형 (설비 × 모델 조합에서 금형이 바뀔 수 있다)
LEFT JOIN LATERAL (
    SELECT l.mold_cd
    FROM label l
    WHERE l.plant_cd = a.plant_cd AND l.wc_cd   = a.wc_cd
      AND l.eqpt_cd  = a.eqpt_cd  AND l.item_cd = a.item_cd
    ORDER BY l.ins_date DESC
    LIMIT 1
) mold ON TRUE
-- 상위 3개 불량 유형을 "유형 수량" 형태로 이어 붙인다
LEFT JOIN LATERAL (
    SELECT string_agg(x.defect_nm || ' ' || x.defect_qty::bigint, ', '
                      ORDER BY x.defect_qty DESC) AS defect_detail
    FROM (
        SELECT d.defect_nm, d.defect_qty
        FROM defect d
        WHERE d.plant_cd = a.plant_cd AND d.wc_cd   = a.wc_cd
          AND d.eqpt_cd  = a.eqpt_cd  AND d.item_cd = a.item_cd
        ORDER BY d.defect_qty DESC
        LIMIT 3
    ) x
) d ON TRUE
ORDER BY a.total_qty DESC;
