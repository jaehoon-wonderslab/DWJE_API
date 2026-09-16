-- =====================================================================================
--  V37 : 용어 대소문자 중복 방지 — ax.tb_gls_term 유니크 인덱스 (2026-09-16)
--
--  [배경]
--  [시스템관리 > 용어 사전 관리] 에 baffle/Baffle, can/CAN/Can, dwerkwoo/Dwerkwoo 처럼
--  대소문자만 다른 같은 용어가 쌓여 있었다(1,234건 중 131그룹 283행).
--  기존 제약 uq_tb_gls_term 은 term 원문 기준이라 'can' 과 'CAN' 을 서로 다른 값으로 본다.
--  유사어 쪽은 처음부터 uq_gls_variant_word(lower(word)) 로 막고 있었는데 용어 쪽만 빠져 있었다.
--
--  [이 파일이 하는 일 / 하지 않는 일]
--  하는 일   : 인덱스 하나를 만든다.
--  하지 않는 일 : 이미 쌓인 중복을 정리하지 않는다.
--  쌓여 있던 중복은 2026-09-16 에 로컬·원격(192.168.2.8) DB 에서 일회성 데이터 보정으로
--  끝냈다(1,234 → 575건). 재현할 수 있는 성질의 작업이 아니라 회차 파일로 남기지 않는다.
--  새로 만든 DB 는 용어가 0건이므로 인덱스만 있으면 같은 상태에서 출발한다.
--
--  [왜 lower 만이 아니라 trim 까지 묶는가]
--  문서에서 긁어온 값이라 ' CAN ' 처럼 앞뒤 공백이 붙어 들어오는 경우가 있었다.
--  lower(term) 만 걸면 ' can ' 이 'can' 과 다른 값이 되어 그대로 통과한다.
--  API 도 같은 기준으로 맞췄다 — GlossaryRepository 가 조회·저장 양쪽에 btrim 을 쓴다.
--
--  [기존 인덱스와의 관계]
--   · uq_tb_gls_term (term)            남긴다. 이 인덱스에 논리적으로 포함되지만,
--                                      제약 이름으로 분기하는 코드가 생길 여지를 없애지 않는다.
--   · ix_gls_term_trgm (lower(term))   남긴다. 표현식이 달라(trim 없음) 유사어 검색이 이 인덱스를
--                                      계속 쓴다. 새 인덱스가 대체하지 못한다.
--
--  [중복이 남아 있는 DB 에 적용하면]
--  인덱스를 만들지 못한다. 그래서 만들기 전에 검사해 중복 용어를 이름과 함께 알려주고 멈춘다.
--  그 DB 는 아래 질의로 먼저 상태를 보고, 사람이 남길 표기를 정한 뒤 다시 적용한다.
--      SELECT lower(btrim(term)) AS 기준, count(*),
--             string_agg(term_id || ':' || term, ' | ' ORDER BY term_id) AS 행
--        FROM ax.tb_gls_term GROUP BY 1 HAVING count(*) > 1 ORDER BY 2 DESC;
--
--  [API 쪽 대응 — 함께 반영됨]
--  이 인덱스가 걸리면 중복 등록이 DB 에서 막힌다. 그대로 두면 사용자에게 500 이 나가므로
--  GlossaryService 가 등록·수정 전에 lower(btrim()) 으로 먼저 검사하고,
--  경합으로 유니크 위반이 나도 DuplicateKeyException 을 잡아 409 로 바꾼다.
--  (기존 400 E-VALID-002 가 아니라 409 E-RULE-001 이다 — WEB 계약 변경.
--   docs/glossary-duplicate-api.md · GlossaryDuplicateTest · GlossaryDuplicateSqlTest 참고)
--
--  [되돌리기]
--  rollback/V37__down.sql. 인덱스만 지우므로 데이터 손실은 없다.
--
--  [적용]
--  docker exec -e PGPASSWORD=dwje_local dwje-pg psql -U dwje_local -d dwjedb -v ON_ERROR_STOP=1 -f V37__gls_term_case_insensitive_unique.sql
--  두 번 실행해도 안전하다. 운영 DB 는 요청자 확인 뒤에 적용한다.
-- =====================================================================================

DO $$
DECLARE
    dup_list text;
BEGIN
    -- 1. 이미 있으면 아무것도 하지 않는다 ------------------------------------------------
    IF EXISTS (
        SELECT 1 FROM pg_indexes
         WHERE schemaname = 'ax' AND indexname = 'uq_gls_term_lower'
    ) THEN
        RAISE NOTICE 'uq_gls_term_lower 가 이미 있어 건너뜁니다.';
        RETURN;
    END IF;

    -- 2. 중복이 있으면 이름을 보여주고 멈춘다 --------------------------------------------
    SELECT string_agg(g.sample, E'\n  ' ORDER BY g.sample)
      INTO dup_list
      FROM (
            SELECT string_agg(t.term_id || ':' || t.term, ' | ' ORDER BY t.term_id) AS sample
              FROM ax.tb_gls_term t
             GROUP BY lower(btrim(t.term))
            HAVING count(*) > 1
           ) g;

    IF dup_list IS NOT NULL THEN
        RAISE EXCEPTION '대소문자/공백만 다른 용어가 남아 있어 인덱스를 만들 수 없습니다.'
              USING DETAIL = dup_list,
                    HINT   = '남길 표기 하나만 두고 나머지는 유사어(ax.tb_gls_variant)로 옮긴 뒤 다시 적용하세요.';
    END IF;

    -- 3. 생성 --------------------------------------------------------------------------
    EXECUTE 'CREATE UNIQUE INDEX uq_gls_term_lower ON ax.tb_gls_term (lower(btrim(term)))';
    EXECUTE $c$COMMENT ON INDEX ax.uq_gls_term_lower IS
        '용어 대소문자/앞뒤공백 무시 중복 방지 (can/CAN/Can 동일 취급)'$c$;

    RAISE NOTICE 'uq_gls_term_lower 를 만들었습니다.';
END $$;
