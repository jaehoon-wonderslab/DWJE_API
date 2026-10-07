-- =====================================================================================
--  V81 : 데이터 접근 항목의 「분류」 삭제 — tb_sys_data_field.category_cd · 공통코드 DATA_FIELD_CATEGORY (2026-10-07)
--
--  [배경]
--  V33 이 데이터 접근 항목에 화면 표시용 분류(category_cd, 공통코드 DATA_FIELD_CATEGORY 7종)를 붙였다.
--  가리기 판정(use_flg · apply_flg · 부서 데이터 권한)에도, 항목 관리에도 쓰이지 않아 사용자 결정으로 없앤다.
--  WEB 은 분류 칸을 뺐고, API 는 category_cd 를 읽고 쓰지 않게 바뀐다. 지시: WEB 세션 「데이터 항목 분류 제거」.
--
--  [참조 확인 — 2026-10-07 로컬 dwjedb 기준]
--   · DATA_FIELD_CATEGORY 를 쓰는 곳 : ax.tb_sys_code_ref 1행(tb_sys_data_field.category_cd) 뿐.
--     vec.tb_code_ref · 다른 표의 컬럼 · 뷰 · 함수 · 트리거에는 없다.
--   · tb_sys_code_ref 행을 남기면 ax.fn_check_code_ref() 가 없는 컬럼을 읽어 실패한다 — 함께 지운다.
--   · 같은 이름의 ax.tb_ai_model_config.category_cd(그룹 AI_CONFIG_CAT)는 다른 컬럼이라 건드리지 않는다.
--   · 코드 그룹은 FK(tb_sys_code · tb_sys_code_ref · vec.tb_code_ref → tb_sys_code_group)로 묶여 있어,
--     여기서 지우지 않은 참조가 남아 있으면 CASCADE 없이 실패한다 — 조용히 같이 지우지 않는다.
--
--  [이 파일이 하는 일] — ax.tb_sys_data_field.category_cd 가 있을 때만(두 번째 실행은 아무것도 하지 않음)
--   1. 되돌리기용 기록표 ax.tb_mig_v81_data_field_cat_bak — 항목별 category_cd(값이 있는 행만)
--      V33 기본 7종 밖에 운영 중 추가된 항목의 분류도 되살릴 수 있게 남긴다
--   2. ax.tb_sys_code_ref 의 (tb_sys_data_field, category_cd) 행 삭제
--   3. ax.tb_sys_data_field.category_cd 삭제
--   4. 공통코드 DATA_FIELD_CATEGORY 의 코드 행 → 그룹 행 삭제
--
--  [순서] — 새 API 배포 → 이 파일
--  옛 API 는 category_cd 를 읽고(항목 목록 · /auth/me dataFields) 써서 이 파일 뒤에는 500 이 된다.
--  새 API 는 category_cd 를 읽지 않으므로 이 파일 전에 떠도 문제없다. 반드시 새 API 를 먼저 배포한다.
--
--  [실행]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f V81__data_field_category_drop.sql
--  두 번 실행해도 안전하다. 되돌리기는 rollback/V81__down.sql (컬럼 · 코드를 V33 값으로, 항목 분류는 기록표에서 되살림).
-- =====================================================================================

BEGIN;

DO $$
DECLARE
    v_bak  integer;
    v_ref  integer;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'ax' AND table_name = 'tb_sys_data_field' AND column_name = 'category_cd') THEN
        RAISE NOTICE 'V81: 이미 적용됨 (tb_sys_data_field.category_cd 없음) — 건너뜀';
        RETURN;
    END IF;

    -- 1. 기록표
    CREATE TABLE IF NOT EXISTS ax.tb_mig_v81_data_field_cat_bak (
        field_key   varchar(30) NOT NULL PRIMARY KEY,
        category_cd varchar(30) NOT NULL,
        backed_at   timestamptz NOT NULL DEFAULT now()
    );
    COMMENT ON TABLE ax.tb_mig_v81_data_field_cat_bak IS
        'V81 되돌리기용 — 분류 삭제 직전의 데이터 접근 항목별 category_cd. rollback/V81__down.sql 이 읽는다';

    INSERT INTO ax.tb_mig_v81_data_field_cat_bak (field_key, category_cd)
    SELECT field_key, category_cd FROM ax.tb_sys_data_field WHERE category_cd IS NOT NULL
    ON CONFLICT (field_key) DO UPDATE SET category_cd = EXCLUDED.category_cd, backed_at = now();
    GET DIAGNOSTICS v_bak = ROW_COUNT;
    RAISE NOTICE 'V81: 항목 분류 보관 % 건', v_bak;

    -- 2. 코드 참조 정의
    DELETE FROM ax.tb_sys_code_ref
     WHERE target_table = 'tb_sys_data_field' AND target_column = 'category_cd';
    GET DIAGNOSTICS v_ref = ROW_COUNT;
    RAISE NOTICE 'V81: 코드 참조 정의 삭제 % 건', v_ref;

    -- 3. 컬럼
    ALTER TABLE ax.tb_sys_data_field DROP COLUMN category_cd;
END $$;

-- 4. 공통코드 (컬럼 유무와 무관하게 남은 것이 있으면 지운다 — 두 번 실행해도 0 건)
DO $$
DECLARE
    v_code integer;
BEGIN
    DELETE FROM ax.tb_sys_code WHERE group_cd = 'DATA_FIELD_CATEGORY';
    GET DIAGNOSTICS v_code = ROW_COUNT;
    DELETE FROM ax.tb_sys_code_group WHERE group_cd = 'DATA_FIELD_CATEGORY';
    RAISE NOTICE 'V81: 공통코드 DATA_FIELD_CATEGORY 코드 % 건 · 그룹 삭제', v_code;
END $$;

COMMIT;
