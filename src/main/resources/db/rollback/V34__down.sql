-- =====================================================================================
--  V34 되돌리기 — 폐기 표 복원 + 시드 제거 (2026-09-16)
--
--  [시드는 ins_user='V34' 인 행만 지운다]
--  화면에서 나중에 추가한 행(ins_user 가 사번)은 건드리지 않는다. 표를 통째로 비우면
--  사람이 넣은 것까지 사라진다.
--
--  [복원되는 표는 비어 있다]
--  ax.tb_sys_data_field_column 은 드롭 시점에 0행이었으므로 구조만 되살리면 원상이다.
--
--  [적용]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f rollback/V34__down.sql
--  두 번 실행해도 안전하다.
-- =====================================================================================

-- 1. 시드 제거 (V34 가 넣은 것만) ----------------------------------------------------------
DELETE FROM ax.tb_sys_data_field_attr WHERE ins_user = 'V34';

-- 2. 폐기 표 복원 ----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ax.tb_sys_data_field_column (
    field_key     varchar(30)  NOT NULL,
    target_schema varchar(63)  NOT NULL,
    target_table  varchar(63)  NOT NULL,
    target_column varchar(63)  NOT NULL,
    remark        varchar(200),
    CONSTRAINT pk_tb_sys_data_field_column PRIMARY KEY (field_key, target_schema, target_table, target_column),
    CONSTRAINT tb_sys_data_field_column_field_key_fkey
        FOREIGN KEY (field_key) REFERENCES ax.tb_sys_data_field(field_key) ON DELETE CASCADE
);
COMMENT ON TABLE ax.tb_sys_data_field_column IS '데이터 항목이 실제로 어떤 물리 컬럼을 가리키는지 등록한다. mes 스키마 컬럼도 대상이며 ⑦ 보안 필터링 Agent 가 이 정의로 마스킹 대상을 판단한다';
