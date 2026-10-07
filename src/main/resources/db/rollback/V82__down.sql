-- =====================================================================================
--  V82 되돌리기 — 항목 단위 권한을 기본 7종 묶음으로 되돌린다 (2026-10-07)
--
--   1. 원래 묶음 기록표(tb_sys_data_attr_origin)에 있는 필드명을 원래 묶음으로 옮긴다(지금 어느 항목에 있든, 빠져 있으면 다시 넣는다)
--   2. 비게 된 V82 항목(i_*)을 지운다 — 부서 권한 행은 FK CASCADE 로 함께 지워진다
--      화면에서 i_* 항목에 다른 필드명을 넣었다면 그 항목은 남긴다(지우지 않는다)
--   3. 기록표를 지운다
--   기본 7종의 부서 권한은 V82 가 바꾸지 않았으므로 V82 전과 같다. 그 뒤 화면에서 항목별로 바꾼 부서 설정은 되돌아가지 않는다.
--
--  [순서] — 옛 API → 이 파일 (새 API 는 기록표가 없으면 기본 7종 행 권한으로 판정하므로 이 파일 뒤에도 동작한다)
-- =====================================================================================

BEGIN;

DO $$
BEGIN
    IF to_regclass('ax.tb_sys_data_attr_origin') IS NULL THEN
        RAISE NOTICE 'V82 되돌리기: 기록표가 없습니다 — 할 일이 없습니다';
        RETURN;
    END IF;

    UPDATE ax.tb_sys_data_field_attr a
       SET field_key = o.origin_key
      FROM ax.tb_sys_data_attr_origin o
     WHERE o.attr_name = a.attr_name
       AND a.field_key <> o.origin_key;

    INSERT INTO ax.tb_sys_data_field_attr (field_key, attr_name, remark, ins_user)
    SELECT o.origin_key, o.attr_name, NULL, 'V82-down'
      FROM ax.tb_sys_data_attr_origin o
     WHERE NOT EXISTS (SELECT 1 FROM ax.tb_sys_data_field_attr a WHERE a.attr_name = o.attr_name);

    DELETE FROM ax.tb_sys_data_field f
     WHERE f.field_key LIKE 'i\_%'
       AND f.ins_user = 'V82'
       AND NOT EXISTS (SELECT 1 FROM ax.tb_sys_data_field_attr a WHERE a.field_key = f.field_key);

    DROP TABLE ax.tb_sys_data_attr_origin;
END $$;

COMMIT;
