-- =====================================================================================
--  V29 : AOI 작업장 표시명 대응표 채우기 (REQ_20260911 실측 A-2 · A-8 반영)
--
--  [배경]
--  V28 에서 공통코드 그룹 AOI_WC 를 만들었으나 MSSQL 의 WC_CD 실제 값을 몰라 비워 두었다.
--  2026-09-11 WEB 세션이 EDGE.dbo.TB_SAMSUN_DIMENSION 에 직접 붙어 값을 확인해 주었다.
--
--  [근거]
--  실측에서 DIMENSION 에 나타난 작업장은 S110 · S120 둘뿐이고 설비가 갈린다.
--      S110 → GP-009 · GP-011 · GP-012 · GP-013 · GP-014 · GP-015  (FAI 45개 사용)
--      S120 → MQ-002 · MQ-003 · MQ-008 · MQ018                     (FAI 58개 사용)
--  MES 작업장 마스터(mes.tb_md_workcenter)의 이름과 맞춰 보면 뜻이 분명하다.
--      S110 = 'A-PLATING(선별-출하)'  → PLATING = 도금
--      S120 = 'A-COATING'            → COATING = 도장
--  발주자가 말한 "도금 · 도장 · 레이저" 중 앞의 둘이 이 두 코드다.
--  레이저(MES 의 S135 'C-LASER(M-3공장)')는 DIMENSION 에 아직 데이터가 없어 넣지 않는다.
--
--  [NULL_ 접두를 같은 이름으로 넣는 이유]
--  원천에 WC_CD = 'NULL_S120' 3,808건 · 'NULL_S110' 3,717건이 있다. 접두만 붙었고 뒤의 코드는
--  정상이라 같은 작업장으로 본다. 원천을 고치는 것이 옳지만 덕우전자 운영 DB 라 우리가 손댈 수
--  없어서, 읽는 쪽에서 같은 이름으로 묶는다. 원천이 정리되면 이 두 행만 지우면 된다.
--
--  [빈 문자열은 코드로 넣을 수 없다]
--  WC_CD = '' 인 행 7,470건은 코드 값이 없어 이 표로 다룰 수 없다. 설비 코드로 되짚는다 —
--  GP-* → S110(도금), MQ-* → S120(도장). 이 규칙은 API 서비스가 처리한다(회신에 명시).
--
--  [해석 순서]
--  API 는 AOI_WC 코드 → mes.tb_md_workcenter.wc_nm → WC_CD 원값 순으로 표시명을 찾는다.
--
--  [적용]
--  docker exec -e PGPASSWORD=dwje_local dwje-pg psql -U dwje_local -d dwjedb -v ON_ERROR_STOP=1 -f V29__aoi_wc_display_name.sql
--  두 번 실행해도 안전하다.
-- =====================================================================================

INSERT INTO ax.tb_sys_code (group_cd, code, code_nm, code_desc, sort_seq, use_flg, ins_user, upd_user) VALUES
 ('AOI_WC', 'S110',      '도금', 'MES 작업장 S110 A-PLATING(선별-출하). 설비 GP-009~GP-015, FAI 45개', 1, 'Y', 'V29', 'V29'),
 ('AOI_WC', 'S120',      '도장', 'MES 작업장 S120 A-COATING. 설비 MQ-002·MQ-003·MQ-008·MQ018, FAI 58개', 2, 'Y', 'V29', 'V29'),
 ('AOI_WC', 'NULL_S110', '도금', '원천 표기 오류(접두 NULL_). S110 과 같은 작업장으로 묶는다', 8, 'Y', 'V29', 'V29'),
 ('AOI_WC', 'NULL_S120', '도장', '원천 표기 오류(접두 NULL_). S120 과 같은 작업장으로 묶는다', 9, 'Y', 'V29', 'V29')
ON CONFLICT (group_cd, code) DO NOTHING;

COMMENT ON COLUMN ax.tb_aoi_defect_image.wc_cd IS '작업장 코드. 표시명은 공통코드 AOI_WC(S110=도금, S120=도장) → mes.tb_md_workcenter.wc_nm → 원값 순으로 찾는다. 원천에 빈 문자열·NULL_ 접두가 섞여 있다';
