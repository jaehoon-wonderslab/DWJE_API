-- =====================================================================================
--  [검토 요청 1] 1공장 프레스 기계별 생산 개수 · 불량 개수
--
--  묻는 것 : 2026-08-01 ~ 2026-08-30 사이에
--            1공장(M-1공장) 프레스 작업장의 기계마다
--            제품을 몇 개 만들었고 그중 불량이 몇 개였는지
--
--  ── 작업자 검토 요청 사항 (아래 5가지가 현장 기준과 맞는지 봐주세요) ──────────────
--
--  (1) 대상 작업장을 "A-프레스 작업장(M-1공장)" 한 곳(W110)으로 잡았습니다.
--      1공장 프레스가 이 작업장 하나가 맞습니까?
--      (참고: B-프레스는 M-2공장, C-프레스는 M-3공장으로 등록되어 있습니다.
--       'J프레스 작업장(M-1공장)'도 있으나 2023-03-13 로 사용 종료되어 제외했습니다.)
--
--  (2) 생산 개수를 "양품 + 불량" 의 합으로 계산했습니다.
--      현장에서 말하는 '생산 개수'가 이 기준이 맞습니까?
--      아니면 양품만 세야 합니까?
--
--  (3) 기계에 등록된 34대를 모두 표시하고, 8월에 안 돌린 기계는 0 으로 나옵니다.
--      (실제로 29대만 실적이 있고 5대는 8월 내내 실적이 없습니다.)
--      안 돌린 기계는 목록에서 빼는 게 나을까요?
--
--  (4) 취소·삭제 처리된 실적(del_flg='Y')은 제외했습니다. 맞습니까?
--
--  (5) 실적에 기계 번호가 안 찍힌 건이 일부 있습니다(전사 기준 약 3%).
--      기계별 집계이므로 이런 건은 제외했습니다.
--      이 물량도 어딘가에 잡혀야 한다면 알려주세요.
--
--  ※ 기간은 8/1 00:00 부터 8/30 하루 전체를 포함합니다(8/31 00:00 직전까지).
--  ※ 조회만 하는 쿼리입니다. 데이터를 바꾸지 않습니다.
-- =====================================================================================

WITH
-- ── 조회 조건 : 이 부분만 바꾸면 기간과 작업장을 조정할 수 있습니다 ────────────────
조건 AS (
    SELECT 'PL01'::varchar                    AS 사업장,
           '2026-08-01 00:00:00'::timestamp   AS 시작,
           '2026-08-31 00:00:00'::timestamp   AS 종료_직전   -- 8/30 을 포함하려면 8/31 00:00
),
-- ── 1공장 프레스 작업장 ────────────────────────────────────────────────────────────
--    공장 구분이 작업장 코드가 아니라 작업장 '이름' 에 들어 있어 이름으로 찾습니다.
대상작업장 AS (
    SELECT w.plant_cd, w.wc_cd, w.wc_nm
    FROM mes.tb_md_workcenter w, 조건 c
    WHERE w.plant_cd = c.사업장
      AND w.wc_nm LIKE '%프레스%'
      AND w.wc_nm LIKE '%M-1공장%'
      -- 조회 기간에 실제로 운영 중이던 작업장만
      AND w.valid_from_dt <  c.종료_직전
      AND w.valid_to_dt   >= c.시작
),
-- ── 해당 작업장에 등록된 기계 (사용 중인 것만) ─────────────────────────────────────
대상기계 AS (
    SELECT t.wc_cd, t.wc_nm, e.plant_cd, e.eqpt_cd, e.eqpt_nm
    FROM 대상작업장 t
    INNER JOIN mes.tb_md_eqpt_by_workcenter ew
            ON ew.plant_cd = t.plant_cd AND ew.wc_cd = t.wc_cd
    INNER JOIN mes.tb_md_eqpt e
            ON e.plant_cd = ew.plant_cd AND e.eqpt_cd = ew.eqpt_cd
    WHERE e.use_flg = 'Y'
),
-- ── 기간 내 생산 실적을 기계 단위로 합산 ───────────────────────────────────────────
실적 AS (
    SELECT lh.eqpt_cd,
           count(DISTINCT lh.item_cd)                              AS 생산품목수,
           count(DISTINCT lh.ins_date::date)                       AS 가동일수,
           count(*)                                                AS 실적건수,
           sum(coalesce(lh.normal, 0))                             AS 양품개수,
           sum(coalesce(lh.defect, 0))                             AS 불량개수,
           sum(coalesce(lh.normal, 0) + coalesce(lh.defect, 0))    AS 총생산개수
    FROM mes.tb_pop_label_hist lh
    INNER JOIN 대상작업장 t ON t.plant_cd = lh.plant_cd AND t.wc_cd = lh.wc_cd
    CROSS JOIN 조건 c
    WHERE lh.del_flg   = 'N'          -- 취소·삭제분 제외
      AND lh.eqpt_cd IS NOT NULL      -- 기계가 찍히지 않은 실적 제외
      AND lh.ins_date >= c.시작
      AND lh.ins_date <  c.종료_직전
    GROUP BY lh.eqpt_cd
)
SELECT
    m.wc_nm                              AS "작업장",
    m.eqpt_cd                            AS "기계코드",
    m.eqpt_nm                            AS "기계명",
    coalesce(r.가동일수,   0)            AS "가동일수",
    coalesce(r.생산품목수, 0)            AS "생산품목수",
    -- 수량 컬럼은 소수 자리가 있는 형(numeric)이지만 실제 값은 전부 정수라 정수로 표시합니다.
    coalesce(r.총생산개수, 0)::bigint    AS "총생산개수",
    coalesce(r.양품개수,   0)::bigint    AS "양품개수",
    coalesce(r.불량개수,   0)::bigint    AS "불량개수"
FROM 대상기계 m
LEFT JOIN 실적 r ON r.eqpt_cd = m.eqpt_cd
ORDER BY coalesce(r.총생산개수, 0) DESC, m.eqpt_cd;
