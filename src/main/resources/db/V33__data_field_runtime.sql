-- =====================================================================================
--  V33 : 데이터 접근 항목을 운영 중에 추가할 수 있게 (2026-09-16)
--
--  [배경]
--  데이터 접근 권한 항목이 7개(qty·yield·price·customer·plan·mold·worker)로 고정돼 있다.
--  부서 × 항목 권한은 이미 DB 라 즉시 반영되는데, 막고 있는 것은 따로 있다 —
--  "unitPrice 라는 응답 값이 price 항목이다" 가 소스 코드에 박혀 있다는 점이다.
--  그 연결을 데이터로 옮겨 WEB 화면에서 항목을 추가하면 배포 없이 도는 것이 이번 목표다.
--
--  [연결 열쇠는 API 응답 JSON 필드명이다]
--  화면·열 조합이나 한글 제목이 아니라 응답 필드명(unitPrice · yieldRate · lotNo)으로 잇는다.
--  같은 필드명이 두 항목에 붙으면 WEB 이 만드는 "필드명 → 항목" 맵이 어느 쪽인지 정할 수 없어서
--  attr_name 에 (field_key 와 무관한) 전역 UNIQUE 를 건다. DB 가 막아 주는 편이 낫다.
--  JSON 키는 대소문자를 구분하므로 UNIQUE 도 대소문자를 구분한다(lower() 로 접지 않는다).
--
--  [2단계 스위치 — apply_flg]
--  항목을 등록하는 순간 마스킹이 걸리면, 부서 체크를 하기 전에 화면이 가려진다.
--  그래서 등록은 apply_flg='N'(미적용)으로 두고, 부서 권한을 다 채운 뒤 'Y' 로 켠다.
--  반영 시점은 재로그인이다(권한은 로그인 때 UserPrincipal 로 굳는다). 실시간 갱신은 하지 않는다.
--
--  [기존 7개만 켜는 이유]
--  apply_flg 를 조건 없이 UPDATE 하면, 나중에 누군가 미적용으로 등록해 둔 항목이 있는 상태에서
--  이 파일을 다시 실행할 때(통합본 재적용 등) 그 항목까지 켜진다. 검토 전 항목이 켜지는 것은
--  2단계 스위치를 둔 뜻을 무너뜨린다. 그래서 처음부터 있던 7개만 이름으로 지정해 켠다.
--
--  [tb_sys_data_field_column 은 여기서 지우지 않는다 — V34 로 미룬다]
--  지시서는 이 표(0행·판정 미사용)의 폐기까지 요청했다. 그런데 2026-09-16 확인 시점에
--  SystemUserRepository.findDataFields() 가 아직 이 표를 string_agg 로 읽고 있다.
--  지금 지우면 [데이터 접근 권한] 화면의 항목 목록 API 가 relation does not exist 로 깨진다.
--  API 가 tb_sys_data_field_attr 로 바꾼 것을 확인한 뒤 V34 로 지운다. (회신에 명시)
--
--  [감사 컬럼을 함께 넣는다 — 지시서 밖의 추가]
--  이 표는 이제 WEB 화면에서 운영 중에 행이 생기는 마스터가 된다. 게다가 마스킹 대상을
--  정하는 표라 "누가 언제 이 항목을 만들었는가" 가 감사 대상이다. 프로젝트의 다른 운영
--  마스터와 같은 4컬럼을 붙인다. 필요 없으면 down 스크립트의 해당 줄만 떼면 된다.
--
--  [적용]
--  docker exec -e PGPASSWORD=dwje_local dwje-pg psql -U dwje_local -d dwjedb -v ON_ERROR_STOP=1 -f V33__data_field_runtime.sql
--  두 번 실행해도 안전하다. 운영은 요청자 확인 뒤에 적용한다(V31·V32 도 아직 미적용).
-- =====================================================================================

-- 1. 항목 마스터 확장 ------------------------------------------------------------------
ALTER TABLE ax.tb_sys_data_field
  ADD COLUMN IF NOT EXISTS category_cd varchar(30),
  ADD COLUMN IF NOT EXISTS apply_flg   common.d_yn      NOT NULL DEFAULT 'N',
  ADD COLUMN IF NOT EXISTS ins_date    timestamptz      NOT NULL DEFAULT now(),
  ADD COLUMN IF NOT EXISTS ins_user    common.d_user_id,
  ADD COLUMN IF NOT EXISTS upd_date    timestamptz      NOT NULL DEFAULT now(),
  ADD COLUMN IF NOT EXISTS upd_user    common.d_user_id;

COMMENT ON TABLE  ax.tb_sys_data_field             IS '데이터 접근 항목 — 메뉴 접근이 허용된 화면에서도 이 항목 단위로 blind 처리한다. WEB 화면에서 운영 중에 추가할 수 있다(2026-09-16 V33)';
COMMENT ON COLUMN ax.tb_sys_data_field.category_cd IS '항목 분류. 공통코드 그룹 = DATA_FIELD_CATEGORY. 화면에서 묶어 보여 주기 위한 것이라 NULL 이어도 판정에 영향이 없다';
COMMENT ON COLUMN ax.tb_sys_data_field.apply_flg   IS '적용 스위치. ''Y'' 여야 마스킹이 걸린다. 등록은 ''N''(미적용)으로 해 두고 부서 권한을 채운 뒤 켠다. 반영 시점은 재로그인';
COMMENT ON COLUMN ax.tb_sys_data_field.use_flg     IS '항목 사용 여부. ''N'' 이면 화면 목록에서 빠진다. 마스킹이 실제로 걸리는 조건은 use_flg=''Y'' AND apply_flg=''Y''';

-- 처음부터 운영 중이던 7개만 켠다 (위 머리말 참조)
UPDATE ax.tb_sys_data_field
   SET apply_flg = 'Y', upd_date = now(), upd_user = 'V33'
 WHERE field_key IN ('qty','yield','price','customer','plan','mold','worker')
   AND apply_flg = 'N';

-- 2. 항목 ↔ 응답 필드명 ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ax.tb_sys_data_field_attr (
    field_key varchar(30)      NOT NULL,
    attr_name varchar(60)      NOT NULL,   -- API 응답 JSON 필드명 (unitPrice · yieldRate · lotNo)
    remark    varchar(200),
    ins_date  timestamptz      NOT NULL DEFAULT now(),
    ins_user  common.d_user_id,

    CONSTRAINT pk_sys_data_field_attr PRIMARY KEY (field_key, attr_name),
    -- 전역 UNIQUE. 같은 필드명이 두 항목에 붙으면 "필드명 → 항목" 맵이 모호해진다
    CONSTRAINT uq_sys_data_field_attr_name UNIQUE (attr_name),
    CONSTRAINT fk_sys_data_field_attr_field
        FOREIGN KEY (field_key) REFERENCES ax.tb_sys_data_field(field_key) ON DELETE CASCADE
);
COMMENT ON TABLE  ax.tb_sys_data_field_attr           IS '데이터 접근 항목 ↔ API 응답 필드명. WEB 이 "필드명 → 항목" 맵을 만들어 마스킹 대상을 찾는다';
COMMENT ON COLUMN ax.tb_sys_data_field_attr.attr_name IS 'API 응답 JSON 필드명. 전역 UNIQUE — 한 필드명은 한 항목에만 붙는다. JSON 키라 대소문자를 구분한다';
COMMENT ON COLUMN ax.tb_sys_data_field_attr.remark    IS '어느 화면·API 의 값인지 메모. 판정에는 쓰지 않는다';

-- 행은 넣지 않는다. 기존 7개의 응답 필드명은 API 가 실제 응답을 보고 채운다 —
-- 여기서 짐작해 넣으면 엉뚱한 값이 마스킹되거나 마스킹돼야 할 값이 새는 쪽으로 틀린다.

-- 3. 분류 공통코드 ---------------------------------------------------------------------
INSERT INTO ax.tb_sys_code_group (group_cd, group_nm, group_desc, use_flg, sort_seq) VALUES
 ('DATA_FIELD_CATEGORY', '데이터 항목 분류', '데이터 접근 항목(ax.tb_sys_data_field)의 화면 표시용 분류', 'Y', 110)
ON CONFLICT (group_cd) DO NOTHING;

INSERT INTO ax.tb_sys_code (group_cd, code, code_nm, code_desc, sort_seq, use_flg, ins_user, upd_user) VALUES
 ('DATA_FIELD_CATEGORY', 'QTY',      '수량', '투입·양품·불량·출하 수량, 실적 집계',        1, 'Y', 'V33', 'V33'),
 ('DATA_FIELD_CATEGORY', 'QUALITY',  '품질', '수율·불량률·LRR 등 품질 지표',               2, 'Y', 'V33', 'V33'),
 ('DATA_FIELD_CATEGORY', 'COST',     '원가', '단가·가공비·폐기 금액 등 금액',              3, 'Y', 'V33', 'V33'),
 ('DATA_FIELD_CATEGORY', 'CUSTOMER', '고객', '고객사·거래처·계약 조건',                    4, 'Y', 'V33', 'V33'),
 ('DATA_FIELD_CATEGORY', 'PLAN',     '계획', '출하 계획·생산 계획 수량',                   5, 'Y', 'V33', 'V33'),
 ('DATA_FIELD_CATEGORY', 'EQUIP',    '설비', '금형·설비 파라미터·공정 조건',               6, 'Y', 'V33', 'V33'),
 ('DATA_FIELD_CATEGORY', 'HR',       '인사', '사번·작업자명·근태·배치',                    7, 'Y', 'V33', 'V33')
ON CONFLICT (group_cd, code) DO NOTHING;

-- 판정에 쓰지 않는 표시용이라 NULL 을 허용한다 → nullable_flg='Y'
INSERT INTO ax.tb_sys_code_ref (target_table, target_column, group_cd, nullable_flg) VALUES
 ('tb_sys_data_field', 'category_cd', 'DATA_FIELD_CATEGORY', 'Y')
ON CONFLICT DO NOTHING;

-- 4. 기존 7개에 분류 채우기 --------------------------------------------------------------
UPDATE ax.tb_sys_data_field f
   SET category_cd = v.cat, upd_date = now(), upd_user = 'V33'
  FROM (VALUES ('qty','QTY'), ('yield','QUALITY'), ('price','COST'), ('customer','CUSTOMER'),
               ('plan','PLAN'), ('mold','EQUIP'), ('worker','HR')) AS v(fk, cat)
 WHERE f.field_key = v.fk
   AND f.category_cd IS DISTINCT FROM v.cat;
