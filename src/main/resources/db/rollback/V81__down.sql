-- =====================================================================================
--  V81 되돌리기 — 데이터 접근 항목의 「분류」 를 되살린다 (2026-10-07)
--
--   1. ax.tb_sys_data_field.category_cd varchar(30) NULL + 주석(V41 문구)
--   2. 공통코드 그룹 DATA_FIELD_CATEGORY · 코드 7종 — V33 의 값
--   3. ax.tb_sys_code_ref (tb_sys_data_field, category_cd, DATA_FIELD_CATEGORY, nullable 'Y') — V33 의 값
--   4. 항목별 분류 — 기록표 ax.tb_mig_v81_data_field_cat_bak 에서 먼저(코드가 있는 값만),
--      그래도 비어 있는 기본 7종(qty · yield · price · customer · plan · mold · worker)은 V33 이 넣던 값으로
--   기록표는 지우지 않는다(V81 을 다시 적용하면 그때 값으로 덮어쓴다). 두 번 실행해도 안전하다.
--
--  [순서] — 이 파일 → 옛 API. 옛 API 는 category_cd 가 없으면 500 이다.
-- =====================================================================================

BEGIN;

-- 1. 컬럼
ALTER TABLE ax.tb_sys_data_field ADD COLUMN IF NOT EXISTS category_cd varchar(30);
COMMENT ON COLUMN ax.tb_sys_data_field.category_cd IS '항목 분류. 공통코드 그룹 = DATA_FIELD_CATEGORY. 화면에서 묶어 보여 주기 위한 것이라 NULL 이어도 판정에 영향이 없음';

-- 2. 공통코드 (V33)
INSERT INTO ax.tb_sys_code_group (group_cd, group_nm, group_desc, use_flg, sort_seq) VALUES
 ('DATA_FIELD_CATEGORY', '데이터 항목 분류', '데이터 접근 항목(ax.tb_sys_data_field)의 화면 표시용 분류', 'Y', 110)
ON CONFLICT (group_cd) DO NOTHING;

INSERT INTO ax.tb_sys_code (group_cd, code, code_nm, code_desc, sort_seq, use_flg, ins_user, upd_user) VALUES
 ('DATA_FIELD_CATEGORY', 'QTY',      '수량', '투입·양품·불량·출하 수량, 실적 집계',        1, 'Y', 'V33', 'V33'),
 ('DATA_FIELD_CATEGORY', 'QUALITY',  '품질', '수율·불량률·LRR 등 품질 지표',               2, 'Y', 'V33', 'V33'),
 ('DATA_FIELD_CATEGORY', 'COST',     '원가', '단가·가공비·폐기 금액 등 금액',              3, 'Y', 'V33', 'V33'),
 ('DATA_FIELD_CATEGORY', 'CUSTOMER', '고객', '고객사·거래처·계약 조건',                    4, 'Y', 'V33', 'V33'),
 ('DATA_FIELD_CATEGORY', 'PLAN',     '계획', '출하 계획·생산 계획 수량',                   5, 'Y', 'V33', 'V33'),
 ('DATA_FIELD_CATEGORY', 'EQUIP',    '설비', '금형·설비 파라미터·공정 조건',               6, 'Y', 'V33', 'V33'),
 ('DATA_FIELD_CATEGORY', 'HR',       '인사', '사번·작업자명·근태·배치',                    7, 'Y', 'V33', 'V33')
ON CONFLICT (group_cd, code) DO NOTHING;

-- 3. 코드 참조 정의 (V33)
INSERT INTO ax.tb_sys_code_ref (target_table, target_column, group_cd, nullable_flg) VALUES
 ('tb_sys_data_field', 'category_cd', 'DATA_FIELD_CATEGORY', 'Y')
ON CONFLICT DO NOTHING;

-- 4. 항목별 분류
DO $$
DECLARE
    v_bak integer := 0;
    v_def integer;
BEGIN
    IF to_regclass('ax.tb_mig_v81_data_field_cat_bak') IS NOT NULL THEN
        UPDATE ax.tb_sys_data_field f
           SET category_cd = b.category_cd, upd_date = now(), upd_user = 'V81_DOWN'
          FROM ax.tb_mig_v81_data_field_cat_bak b
         WHERE f.field_key = b.field_key
           AND f.category_cd IS NULL
           AND EXISTS (SELECT 1 FROM ax.tb_sys_code c
                        WHERE c.group_cd = 'DATA_FIELD_CATEGORY' AND c.code = b.category_cd);
        GET DIAGNOSTICS v_bak = ROW_COUNT;
    END IF;

    UPDATE ax.tb_sys_data_field f
       SET category_cd = v.cat, upd_date = now(), upd_user = 'V81_DOWN'
      FROM (VALUES ('qty','QTY'), ('yield','QUALITY'), ('price','COST'), ('customer','CUSTOMER'),
                   ('plan','PLAN'), ('mold','EQUIP'), ('worker','HR')) AS v(fk, cat)
     WHERE f.field_key = v.fk
       AND f.category_cd IS NULL;
    GET DIAGNOSTICS v_def = ROW_COUNT;

    RAISE NOTICE 'V81 down: 기록표에서 % 건 · 기본값으로 % 건 분류 복원', v_bak, v_def;
END $$;

COMMIT;
