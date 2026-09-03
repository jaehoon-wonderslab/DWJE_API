-- =====================================================================================
--  V11 : 용어 사전 분류(도메인) 기준정보
--
--  [배경]
--  ax.tb_gls_domain 은 스키마 생성 시 만들어졌지만 한 건도 등록되지 않았다(0행).
--  ax.tb_gls_term.domain_id 는 NOT NULL + FK 라, 분류가 없으면 용어를 등록할 수 없다.
--  POST /api/v1/glossary/terms 는 domainCd 를 domain_nm 으로 조회해 없으면 404 를 준다.
--  즉 초기 상태에서는 어떤 값을 넣어도 첫 용어를 만들 수 없었다.
--
--  화면(용어 사전 관리)의 분류 선택지도 이 표에서 나온다.
--  GET /api/v1/glossary/summary 의 byDomain 은 tb_gls_domain LEFT JOIN tb_gls_term 이라
--  용어가 0건이어도 분류는 나와야 하는데, 표 자체가 비어 있어 빈 배열이었다.
--
--  [설계]
--  분류명은 스키마 주석에 명시된 것을 그대로 쓴다.
--    COMMENT ON TABLE ax.tb_gls_domain IS
--      '용어 분류/도메인 — 회사/고객사, 품질관리, 프로젝트, 제품/부품, 불량유형, 조직/부서 등 20종'
--  주석은 '20종' 이라고 하지만 이름이 적힌 것은 6종뿐이다.
--  남은 항목은 업무 담당자가 확정해야 하므로 여기서 추측해 넣지 않는다.
--  분류 추가는 이 표에 INSERT 하면 되고 애플리케이션 수정은 필요 없다.
--
--  [영향]
--  GET /api/v1/glossary/domains  → 6건
--  GET /api/v1/glossary/summary  → domainCnt 6, byDomain 6건(termCnt 0)
--  용어 등록 시 domainCd 에 아래 domain_nm 을 넣으면 통과한다.
--
--  [되돌리기]
--  DELETE FROM ax.tb_gls_domain WHERE domain_nm IN (...);  -- 용어가 달리기 전에만 가능
-- =====================================================================================

INSERT INTO ax.tb_gls_domain (domain_nm, sort_seq, use_flg) VALUES
    ('회사/고객사',  10, 'Y'),
    ('품질관리',     20, 'Y'),
    ('프로젝트',     30, 'Y'),
    ('제품/부품',    40, 'Y'),
    ('불량유형',     50, 'Y'),
    ('조직/부서',    60, 'Y')
ON CONFLICT (domain_nm) DO NOTHING;
