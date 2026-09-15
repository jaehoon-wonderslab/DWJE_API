-- =====================================================================================
--  V33 되돌리기 — 데이터 접근 항목 확장 취소 (2026-09-16)
--
--  V33__data_field_runtime.sql 이 더한 것을 그대로 되돌린다.
--  컬럼 4+2개 · 표 1개 · 공통코드 그룹 1 · 코드 7 · 코드 참조 1행.
--
--  [지우는 데이터]
--  · ax.tb_sys_data_field_attr 의 행 전부 (항목 ↔ 응답 필드명 연결). 표를 지우므로 함께 사라진다.
--  · tb_sys_data_field.category_cd · apply_flg 값. 컬럼을 떼므로 함께 사라진다.
--  되살려야 하면 V33 을 다시 적용한 뒤 attr 행을 다시 넣는다. 그 행이 아까우면 먼저 떠 둔다 —
--      pg_dump -U <user> -d <db> --data-only -t ax.tb_sys_data_field_attr > attr_backup.sql
--
--  [건드리지 않는 것]
--  ax.tb_sys_data_field 의 원래 5개 컬럼과 7개 행, ax.tb_sys_dept_data_perm 의 부서 권한(29행).
--  마스킹 판정은 V33 이전으로 돌아간다 — UserPrincipal.canReadField 는 apply_flg 를 보지 않았다.
--
--  [적용]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f rollback/V33__down.sql
--  두 번 실행해도 안전하다.
-- =====================================================================================

-- 1. 항목 ↔ 응답 필드명 표 제거 -----------------------------------------------------------
DROP TABLE IF EXISTS ax.tb_sys_data_field_attr;

-- 2. 코드 참조 → 코드 → 그룹 순서로 정리 (참조를 먼저 지운다) --------------------------------
DELETE FROM ax.tb_sys_code_ref
 WHERE target_table = 'tb_sys_data_field' AND target_column = 'category_cd';
DELETE FROM ax.tb_sys_code       WHERE group_cd = 'DATA_FIELD_CATEGORY';
DELETE FROM ax.tb_sys_code_group WHERE group_cd = 'DATA_FIELD_CATEGORY';

-- 3. 컬럼 제거 -------------------------------------------------------------------------
--    감사 컬럼 4개는 V33 이 지시서 밖으로 더한 것이다. 그것만 남기고 싶으면 아래 4줄을 뺀다.
ALTER TABLE ax.tb_sys_data_field
  DROP COLUMN IF EXISTS category_cd,
  DROP COLUMN IF EXISTS apply_flg,
  DROP COLUMN IF EXISTS ins_date,
  DROP COLUMN IF EXISTS ins_user,
  DROP COLUMN IF EXISTS upd_date,
  DROP COLUMN IF EXISTS upd_user;

-- 4. 주석을 V33 이전 상태로 되돌린다 --------------------------------------------------------
--    컬럼 주석은 컬럼과 함께 사라진다. 표 주석은 원문으로, use_flg 는 원래 주석이 없었으므로 지운다.
COMMENT ON TABLE  ax.tb_sys_data_field         IS '데이터 접근 항목 — 메뉴 접근이 허용된 화면에서도 이 항목 단위로 blind 처리한다 (qty/yield/price/customer/plan/mold/worker)';
COMMENT ON COLUMN ax.tb_sys_data_field.use_flg IS NULL;
