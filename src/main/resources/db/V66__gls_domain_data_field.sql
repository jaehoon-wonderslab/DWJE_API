-- =====================================================================================
--  V66 : 용어 분류별 데이터 접근 항목 — 고객사 계열 용어 가림 (2026-10-02)
--
--  [배경]
--  결정 R-18(D-25): 고객사 · 협력사 이름이 용어 사전 조회 · 관리 화면과 덕반장 AI 의 [용어] 블록으로
--  고객사 데이터 권한(customer)이 없는 사람에게도 보였다. 분류 단위로 필요한 데이터 접근 항목을 두고,
--  API 가 그 권한이 없는 열람자에게 그 분류의 용어를 「비공개 용어」 로 가린다.
--  기획: 공통 문서 11.2, 13 용어 사전 조회 GLV-08(4.5), 07 GLS-17.
--
--  [이 파일이 하는 일]
--   1. ax.tb_gls_domain.data_field_key varchar(30) NULL + FK fk_gls_domain_data_field → ax.tb_sys_data_field
--      (삭제 동작 기본값 — 분류가 쓰는 데이터 항목은 지울 수 없음)
--   2. 고객사 계열 분류에 customer 지정 — 2026-10-02 로컬 분류 101개에서 고른 4개:
--        회사/고객사(1) · 고객사(14) · 고객협력사(15) · 협력업체(98)
--      비어 있는(NULL) 분류만 채운다. 다시 실행해도 관리자가 바꾼 값을 덮지 않는다.
--   3. 표 · 컬럼 주석
--
--  [빈 DB 설치 주의]
--  V11 은 회사/고객사만 넣고, 나머지 세 분류는 선택 시드 seed_glossary.sql 이 마이그레이션 뒤에 넣는다.
--  그 시드를 넣었다면 이 파일(또는 patch_20261002_gls_domain_data_field.sql)을 한 번 더 실행한다.
--
--  [순서]
--  이 파일 → API 배포(가림 판정). API 보다 늦게 적용하면 data_field_key 를 읽는 용어 조회가 500 이 된다.
--
--  [실행]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f V66__gls_domain_data_field.sql
--  두 번 실행해도 안전하다. 되돌리기는 rollback/V66__down.sql.
-- =====================================================================================

BEGIN;

ALTER TABLE ax.tb_gls_domain ADD COLUMN IF NOT EXISTS data_field_key varchar(30);

DO $$
DECLARE
    v_cnt integer;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_gls_domain_data_field') THEN
        ALTER TABLE ax.tb_gls_domain
            ADD CONSTRAINT fk_gls_domain_data_field FOREIGN KEY (data_field_key)
                REFERENCES ax.tb_sys_data_field (field_key);
    END IF;

    -- 실서버는 적용 전에 분류 이름을 확인한다:
    --   SELECT domain_id, domain_nm FROM ax.tb_gls_domain WHERE domain_nm ~ '(고객|협력|업체|거래|회사)';
    UPDATE ax.tb_gls_domain
       SET data_field_key = 'customer'
     WHERE domain_nm IN ('회사/고객사', '고객사', '고객협력사', '협력업체')
       AND data_field_key IS NULL;
    GET DIAGNOSTICS v_cnt = ROW_COUNT;
    RAISE NOTICE 'V66: customer 지정 % 건', v_cnt;
END $$;

COMMENT ON COLUMN ax.tb_gls_domain.data_field_key IS
  '이 분류 용어를 볼 때 필요한 데이터 접근 항목(ax.tb_sys_data_field). 권한이 없으면 용어를 「비공개 용어」 로 가리고 덕반장 AI [용어] 블록에서 뺌. NULL 이면 모두 열람. 예: 고객사 계열 분류 = customer';
COMMENT ON TABLE ax.tb_gls_domain IS
  '용어 분류/도메인 — 회사/고객사, 품질관리, 불량유형 등. data_field_key 가 있으면 그 데이터 접근 권한이 있어야 그 분류 용어를 봄';

COMMIT;
