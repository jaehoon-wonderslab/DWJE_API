-- =====================================================================================
--  V66 되돌리기 — 용어 분류 데이터 접근 항목 제거 (2026-10-02)
--
--  FK 와 data_field_key 컬럼을 지운다. 분류별 지정 값은 돌아오지 않는다(다시 적용하면 고객사 계열 4개만 채움).
--  표 주석은 V66 직전 문구로.
--
--  [API 를 먼저 내린다]
--  새 API 는 data_field_key 로 용어를 가린다. 컬럼이 사라지면 용어 조회가 500 이 되고, 남겨 두면 가림이 풀린다.
--
--  [적용]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f rollback/V66__down.sql
--  두 번 실행해도 안전하다.
-- =====================================================================================

BEGIN;

ALTER TABLE ax.tb_gls_domain DROP CONSTRAINT IF EXISTS fk_gls_domain_data_field;
ALTER TABLE ax.tb_gls_domain DROP COLUMN IF EXISTS data_field_key;

COMMENT ON TABLE ax.tb_gls_domain IS
  '용어 분류/도메인 — 회사/고객사, 품질관리, 프로젝트, 제품/부품, 불량유형, 조직/부서 등 20종';

COMMIT;
