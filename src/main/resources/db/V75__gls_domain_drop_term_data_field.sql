-- =====================================================================================
--  V75 : 용어 분류(domain) 삭제 · 고객사 가림을 용어 단위로 옮김 (2026-10-03)
--
--  [배경]
--  용어 사전에서 「분류」 를 없앤다(화면 열 · 등록 폼 · 엑셀 · API · DB). 분류에 걸려 있던 고객사 가림(결정 R-18, V66)은
--  사용자 결정에 따라 「용어별 가림 표시」 로 옮겨 그대로 유지한다. 고객사 권한(customer)이 없는 열람자에게
--  고객사 계열 분류 4개(회사/고객사 · 고객사 · 고객협력사 · 협력업체)의 용어가 계속 「비공개 용어」 로 보여야 한다.
--  지시서: WEB 세션 req-glossary-domain-drop.md.
--
--  [이 파일이 하는 일] — ax.tb_gls_term.domain_id 가 있을 때만(두 번째 실행은 아무것도 하지 않음)
--   1. ax.tb_gls_term.data_field_key varchar(30) NULL + FK fk_gls_term_data_field → ax.tb_sys_data_field
--      (V66 의 분류 FK 와 같은 규칙 — 용어가 쓰는 데이터 항목은 지울 수 없음)
--   2. 되돌리기용 기록표 두 개
--        ax.tb_mig_v75_gls_domain_bak      — tb_gls_domain 행 전체 사본
--        ax.tb_mig_v75_gls_term_domain_bak — 용어별 domain_id
--   3. 이관 : term.data_field_key = 분류의 data_field_key (비어 있는 용어만)
--   4. ax.tb_gls_term.domain_id 삭제(FK tb_gls_term_domain_id_fkey 포함), ax.tb_gls_domain 표 삭제
--      다른 객체가 참조하면 CASCADE 없이 실패한다 — 조용히 같이 지우지 않는다.
--      (vec.vw_term_candidate 는 tb_gls_term 을 읽지만 domain_id 는 쓰지 않는다)
--   5. 주석
--
--  [순서]
--  API 를 새 코드(분류를 읽지 않고 term.data_field_key 로 가림)로 바꾼 뒤 이 파일을 적용한다.
--  옛 API 는 domain_id · tb_gls_domain 을 읽어 용어 사전 조회가 500 이 되고,
--  새 API 는 이 파일 전에는 term.data_field_key 가 없어 500 이 된다 — 둘을 붙여서 배포한다.
--
--  [실행]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f V75__gls_domain_drop_term_data_field.sql
--  두 번 실행해도 안전하다. 되돌리기는 rollback/V75__down.sql (기록표에서 분류 · 용어 분류를 되살림).
-- =====================================================================================

BEGIN;

DO $$
DECLARE
    v_domain integer;
    v_term   integer;
    v_hidden integer;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'ax' AND table_name = 'tb_gls_term' AND column_name = 'domain_id') THEN
        RAISE NOTICE 'V75: 이미 적용됨 (tb_gls_term.domain_id 없음) — 건너뜀';
        RETURN;
    END IF;

    -- 1. 용어별 데이터 접근 항목
    ALTER TABLE ax.tb_gls_term ADD COLUMN IF NOT EXISTS data_field_key varchar(30);
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_gls_term_data_field') THEN
        ALTER TABLE ax.tb_gls_term
            ADD CONSTRAINT fk_gls_term_data_field FOREIGN KEY (data_field_key)
                REFERENCES ax.tb_sys_data_field (field_key);
    END IF;

    -- 2. 기록표
    CREATE TABLE IF NOT EXISTS ax.tb_mig_v75_gls_domain_bak (
        domain_id      integer     NOT NULL PRIMARY KEY,
        domain_nm      varchar(50) NOT NULL,
        sort_seq       smallint    NOT NULL,
        use_flg        char(1)     NOT NULL,
        data_field_key varchar(30),
        backed_at      timestamptz NOT NULL DEFAULT now()
    );
    CREATE TABLE IF NOT EXISTS ax.tb_mig_v75_gls_term_domain_bak (
        term_id   integer     NOT NULL PRIMARY KEY,
        domain_id integer     NOT NULL,
        backed_at timestamptz NOT NULL DEFAULT now()
    );

    -- data_field_key 는 V66 이 넣은 컬럼이라 V66 없이 이 파일에 온 DB 를 위해 동적으로 읽는다
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = 'ax' AND table_name = 'tb_gls_domain' AND column_name = 'data_field_key') THEN
        EXECUTE $q$
            INSERT INTO ax.tb_mig_v75_gls_domain_bak (domain_id, domain_nm, sort_seq, use_flg, data_field_key)
            SELECT domain_id, domain_nm, sort_seq, use_flg, data_field_key FROM ax.tb_gls_domain
            ON CONFLICT (domain_id) DO UPDATE
               SET domain_nm = EXCLUDED.domain_nm, sort_seq = EXCLUDED.sort_seq, use_flg = EXCLUDED.use_flg,
                   data_field_key = EXCLUDED.data_field_key, backed_at = now()
        $q$;
    ELSE
        INSERT INTO ax.tb_mig_v75_gls_domain_bak (domain_id, domain_nm, sort_seq, use_flg)
        SELECT domain_id, domain_nm, sort_seq, use_flg FROM ax.tb_gls_domain
        ON CONFLICT (domain_id) DO UPDATE
           SET domain_nm = EXCLUDED.domain_nm, sort_seq = EXCLUDED.sort_seq, use_flg = EXCLUDED.use_flg, backed_at = now();
    END IF;
    GET DIAGNOSTICS v_domain = ROW_COUNT;

    INSERT INTO ax.tb_mig_v75_gls_term_domain_bak (term_id, domain_id)
    SELECT term_id, domain_id FROM ax.tb_gls_term
    ON CONFLICT (term_id) DO UPDATE SET domain_id = EXCLUDED.domain_id, backed_at = now();
    GET DIAGNOSTICS v_term = ROW_COUNT;

    -- 3. 이관 — 분류에 걸린 값 그대로
    UPDATE ax.tb_gls_term t
       SET data_field_key = b.data_field_key
      FROM ax.tb_mig_v75_gls_domain_bak b
     WHERE b.domain_id = t.domain_id
       AND b.data_field_key IS NOT NULL
       AND t.data_field_key IS NULL;
    GET DIAGNOSTICS v_hidden = ROW_COUNT;

    -- 4. 분류 컬럼 · 표 삭제
    ALTER TABLE ax.tb_gls_term DROP CONSTRAINT IF EXISTS tb_gls_term_domain_id_fkey;
    ALTER TABLE ax.tb_gls_term DROP COLUMN domain_id;
    DROP TABLE ax.tb_gls_domain;

    RAISE NOTICE 'V75: 분류 % 건 · 용어 % 건 기록, 데이터 접근 항목 이관 % 건, 분류 삭제', v_domain, v_term, v_hidden;
END $$;

-- 5. 주석
COMMENT ON COLUMN ax.tb_gls_term.data_field_key IS
  '이 용어를 볼 때 필요한 데이터 접근 항목(ax.tb_sys_data_field). 권한이 없으면 용어를 「비공개 용어」 로 가리고 덕반장 AI [용어] 블록에서 뺌. NULL 이면 모두 열람. 예: 고객사 · 협력사 이름 = customer';
COMMENT ON TABLE ax.tb_gls_term IS
  '공식 용어 — 보고서·리포트 출력 표기 기준. 통합관리자만 등록·수정. data_field_key 가 있으면 그 데이터 접근 권한이 있어야 봄';
COMMENT ON TABLE ax.tb_mig_v75_gls_domain_bak IS
  'V75 되돌리기용 기록 — 삭제한 용어 분류(tb_gls_domain) 행 전체 사본. rollback/V75__down.sql 이 읽음';
COMMENT ON TABLE ax.tb_mig_v75_gls_term_domain_bak IS
  'V75 되돌리기용 기록 — 분류 삭제 직전 용어별 분류(term_id → domain_id). rollback/V75__down.sql 이 읽음';

COMMIT;
