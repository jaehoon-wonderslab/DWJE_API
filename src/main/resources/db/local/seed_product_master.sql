-- =====================================================================================
--  AX 제품 마스터 부트스트랩 시드
--
--  [목적]
--  ax.tb_prod_* (제품군·제품·프로젝트·품목매핑) 는 AX 고유의 업무 축이며 MES 에 대응 항목이 없다.
--  이 테이블들이 비어 있으면 공정·제품 대시보드(No.33~41)와 제품 랭킹(No.42~45)이
--  조인 결과 0건이 되어 화면 전체가 빈 상태로 보인다.
--  화면 연동을 가능하게 하기 위해 MES 품목 마스터에서 결정론적으로 초기 계층을 파생시킨다.
--
--  [중요] mes 스키마는 SELECT 만 한다. 쓰기는 전부 ax 스키마 대상이다.
--
--  [파생 규칙]
--  - 제품(model_cd) = item_cd 의 하이픈 앞부분.  예) D34BS-F, D34BS-P2 → D34BS
--    (품목코드는 "모델 + 공정접미사" 구조이므로 모델 단위 집계의 자연 키가 된다)
--  - 제품군(family) = project_nm 의 괄호 안 이름.  예) 23Y(Vr-Shield-Can) → Vr-Shield-Can
--    괄호 표기가 없는 품목은 '미분류' 로 모은다. (실적 품목 1,106건 중 543건)
--    item_nm 의 괄호는 '(개발)' 같은 상태값이 섞여 있어 제품군 근거로 쓰지 않는다.
--  - 한 모델이 여러 제품군에 걸치면(17건) '미분류' 가 아닌 제품군을 우선하고,
--    그래도 복수면 품목 수가 많은 쪽, 동수면 이름 오름차순으로 확정한다.
--  - 고객사(tb_prod_customer)는 MES 에 대응 정보가 없어 생성하지 않는다. (product.customer_id = NULL)
--  - 순위(rank_no)는 전체 기간 생산 수량 내림차순으로 부여한다.
--
--  [운영 전환]
--  파생 행은 ins_user = 'BOOTSTRAP' 으로 표시된다. 고객이 실제 제품 체계를 등록하면
--  이 스크립트는 더 이상 실행되지 않아야 하며, 아래 가드가 사용자 등록 행을 발견하면 중단한다.
--  재실행 시 BOOTSTRAP 행만 지우고 다시 만들므로 멱등하다.
-- =====================================================================================

\set ON_ERROR_STOP on

BEGIN;

-- ── 가드 : 사람이 등록한 행이 하나라도 있으면 손대지 않는다 ──────────────────────────
DO $guard$
BEGIN
    IF EXISTS (SELECT 1 FROM ax.tb_prod_product WHERE ins_user IS DISTINCT FROM 'BOOTSTRAP')
    OR EXISTS (SELECT 1 FROM ax.tb_prod_family  WHERE ins_user IS DISTINCT FROM 'BOOTSTRAP') THEN
        RAISE EXCEPTION
            '제품 마스터에 사용자 등록 데이터가 있어 부트스트랩 시드를 중단한다. 수동으로 확인하라.';
    END IF;
END
$guard$;

-- ── 기존 부트스트랩 행 제거 (item_map 은 product FK 가 ON DELETE CASCADE) ────────────
DELETE FROM ax.tb_prod_item_map
 WHERE product_id IN (SELECT product_id FROM ax.tb_prod_product WHERE ins_user = 'BOOTSTRAP');
DELETE FROM ax.tb_prod_product WHERE ins_user = 'BOOTSTRAP';
DELETE FROM ax.tb_prod_family  WHERE ins_user = 'BOOTSTRAP';
DELETE FROM ax.tb_prod_project
 WHERE project_cd LIKE 'BS-%'
   AND NOT EXISTS (SELECT 1 FROM ax.tb_prod_product p WHERE p.project_id = tb_prod_project.project_id);

-- ── 1. MES 에서 실적이 있는 품목만 뽑아 파생 축을 계산한다 (mes 는 SELECT 전용) ───────
CREATE TEMP TABLE tmp_item ON COMMIT DROP AS
WITH produced AS (
    SELECT plant_cd, item_cd, sum(coalesce(normal, 0) + coalesce(defect, 0)) AS total_qty
    FROM mes.tb_pop_label_hist
    WHERE del_flg = 'N'
    GROUP BY plant_cd, item_cd
)
SELECT i.plant_cd,
       i.item_cd,
       i.item_nm,
       split_part(i.item_cd, '-', 1)                                              AS model_cd,
       nullif(btrim(i.project_nm), '')                                            AS project_nm,
       coalesce(nullif(btrim(substring(i.project_nm FROM '\((.*)\)')), ''), '미분류') AS family_nm,
       pr.total_qty
FROM mes.tb_md_item i
INNER JOIN produced pr ON pr.plant_cd = i.plant_cd AND pr.item_cd = i.item_cd;

-- ── 2. 제품군 : 생산 수량 내림차순으로 순위 부여 ───────────────────────────────────
INSERT INTO ax.tb_prod_family (family_cd, family_nm, rank_no, def_rank_no, use_flg, ins_user, upd_user)
SELECT 'BSF' || lpad(row_number() OVER (ORDER BY sum(total_qty) DESC, family_nm)::text, 3, '0'),
       family_nm,
       row_number() OVER (ORDER BY sum(total_qty) DESC, family_nm),
       row_number() OVER (ORDER BY sum(total_qty) DESC, family_nm),
       'Y', 'BOOTSTRAP', 'BOOTSTRAP'
FROM tmp_item
GROUP BY family_nm;

-- ── 3. 프로젝트 ──────────────────────────────────────────────────────────────────
INSERT INTO ax.tb_prod_project (project_cd, project_nm, use_flg)
SELECT 'BS-' || lpad(row_number() OVER (ORDER BY project_nm)::text, 4, '0'), project_nm, 'Y'
FROM (SELECT DISTINCT project_nm FROM tmp_item WHERE project_nm IS NOT NULL) d
ON CONFLICT (project_nm) DO NOTHING;

-- ── 4. 모델별 대표 제품군·프로젝트 확정 ───────────────────────────────────────────
--     '미분류' 가 아닌 제품군 우선 → 품목 수 많은 쪽 → 이름 오름차순
CREATE TEMP TABLE tmp_model ON COMMIT DROP AS
SELECT m.model_cd,
       m.model_nm,
       m.total_qty,
       (SELECT t.family_nm
          FROM tmp_item t
         WHERE t.model_cd = m.model_cd
         GROUP BY t.family_nm
         ORDER BY (t.family_nm = '미분류'), count(*) DESC, t.family_nm
         LIMIT 1)                                                     AS family_nm,
       (SELECT t.project_nm
          FROM tmp_item t
         WHERE t.model_cd = m.model_cd AND t.project_nm IS NOT NULL
         GROUP BY t.project_nm
         ORDER BY count(*) DESC, t.project_nm
         LIMIT 1)                                                     AS project_nm
FROM (
    SELECT model_cd,
           sum(total_qty) AS total_qty,
           -- 대표 품목명 : 생산 수량이 가장 많은 품목의 이름
           (array_agg(item_nm ORDER BY total_qty DESC))[1] AS model_nm
    FROM tmp_item
    GROUP BY model_cd
) m;

-- ── 5. 제품 ─────────────────────────────────────────────────────────────────────
INSERT INTO ax.tb_prod_product
    (model_cd, model_nm, family_id, customer_id, project_id,
     seq_in_family, def_seq, rank_no, use_flg, ins_user, upd_user)
SELECT mm.model_cd,
       mm.model_nm,
       f.family_id,
       NULL,                                 -- 고객사 정보는 MES 에 없다
       pj.project_id,
       row_number() OVER (PARTITION BY f.family_id ORDER BY mm.total_qty DESC, mm.model_cd),
       row_number() OVER (PARTITION BY f.family_id ORDER BY mm.total_qty DESC, mm.model_cd),
       row_number() OVER (ORDER BY mm.total_qty DESC, mm.model_cd),
       'Y', 'BOOTSTRAP', 'BOOTSTRAP'
FROM tmp_model mm
INNER JOIN ax.tb_prod_family f  ON f.family_nm = mm.family_nm AND f.ins_user = 'BOOTSTRAP'
LEFT  JOIN ax.tb_prod_project pj ON pj.project_nm = mm.project_nm;

-- ── 6. 품목 ↔ 제품 매핑 ─────────────────────────────────────────────────────────
INSERT INTO ax.tb_prod_item_map (plant_cd, item_cd, product_id, map_kind_cd, remark, ins_user)
SELECT t.plant_cd, t.item_cd, p.product_id, 'PRODUCT', 'MES 품목코드 규칙 기반 자동 매핑', 'BOOTSTRAP'
FROM tmp_item t
INNER JOIN ax.tb_prod_product p ON p.model_cd = t.model_cd AND p.ins_user = 'BOOTSTRAP'
ON CONFLICT (plant_cd, item_cd) DO NOTHING;

COMMIT;

\echo '--- 부트스트랩 시드 결과 ---'
SELECT (SELECT count(*) FROM ax.tb_prod_family   WHERE ins_user='BOOTSTRAP') AS 제품군,
       (SELECT count(*) FROM ax.tb_prod_product  WHERE ins_user='BOOTSTRAP') AS 제품,
       (SELECT count(*) FROM ax.tb_prod_project  WHERE project_cd LIKE 'BS-%') AS 프로젝트,
       (SELECT count(*) FROM ax.tb_prod_item_map WHERE ins_user='BOOTSTRAP') AS 품목매핑;
