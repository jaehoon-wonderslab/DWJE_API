-- =====================================================================================
--  V45 : 그룹웨어 인사정보 자동 가입 — 프로필 사진 컬럼 · 미배정 부서 · 부서명 매핑표 (2026-09-30)
--
--  [배경]
--  MES 이관 엔진이 그룹웨어(한비로) 인사정보를 groupware_user.tb_user_list 로 받아 온다.
--  2026-09-30 요청으로, 받아 올 때마다 ax.tb_sys_user 에 없는 사번을 자동으로 가입시킨다.
--   · 로그인 ID = 사번, 비밀번호 = 사번, 이메일 = 사번@derkwoo.com, 상태 = ACTIVE
--   · 이미 가입된 사번은 건너뛴다(값을 덮어쓰지 않는다)
--   · 프로필 사진은 그룹웨어 PROFILE_IMAGE_PATH 를 새 컬럼 avata 에 넣는다
--  가입 로직은 MES_migration_engine 의 GroupwareAxJoinWriter 에 있다. 이 파일은 그 로직이 쓰는
--  컬럼·부서·매핑표만 만든다.
--
--  [부서를 매핑표로 정하는 이유]
--  ax.tb_sys_user.dept_id 는 필수인데, 그룹웨어 부서는 77개(멕시코법인(A)·IPQC파트(M) 등)이고
--  AX 부서는 6개라 이름으로 맞출 수 없다. ax.tb_sys_dept_gw_map 에 적힌 대로 넣고,
--  매핑이 없는 사람은 화면 권한이 하나도 없는 '미배정' 부서로 넣는다.
--  매핑은 가입하는 순간에만 쓴다 — 매핑을 나중에 바꿔도 이미 가입된 계정의 부서는 바뀌지 않는다.
--
--  [시드 매핑 기준]
--  그룹웨어 부서명에서 사업장 접미사 (A)·(M) 를 뗀 이름이 AX 부서명과 똑같은 것만 넣었다.
--  퇴사자·휴직자 '로그인불가' 부서 3개는 join_yn = 'N' 으로 가입 대상에서 뺀다.
--  나머지 부서는 발주자가 매핑을 정해 이 표에 행을 추가한다.
--
--  [실행]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f V45__groupware_auto_join.sql
--  두 번 실행해도 안전하다. 되돌리기는 rollback/V45__down.sql.
--
--  [엔진 계정 권한 — 운영에서 엔진을 별도 계정(mes_migration)으로 돌릴 때]
--  GRANT SELECT, INSERT ON ax.tb_sys_user TO mes_migration;
--  GRANT SELECT ON ax.tb_sys_dept, ax.tb_sys_dept_gw_map, ax.tb_sys_code TO mes_migration;
-- =====================================================================================

BEGIN;

-- ── 1. 프로필 사진 ──────────────────────────────────────────────────────────────────
ALTER TABLE ax.tb_sys_user ADD COLUMN IF NOT EXISTS avata varchar(200);
COMMENT ON COLUMN ax.tb_sys_user.avata IS '프로필 사진 URL — 그룹웨어 인사정보 PROFILE_IMAGE_PATH 값, 자동 가입 때 기록. 직접 가입한 계정은 비어 있음';

-- ── 2. 미배정 부서 ──────────────────────────────────────────────────────────────────
--  부서 권한(tb_sys_dept_menu_perm·tb_sys_dept_data_perm) 행을 만들지 않는다 — 로그인해도 볼 화면이 없다.
INSERT INTO ax.tb_sys_dept (dept_nm, dept_abbr, dept_desc, plant_cd, is_super_admin, sort_seq, ins_user, upd_user)
VALUES ('미배정', '미배정', '그룹웨어 자동 가입 계정 중 부서 매핑이 없는 사람 — 화면 권한 없음, 계정 관리 화면에서 실제 부서로 옮김',
        'PL01', false, 99, 'SYSTEM', 'SYSTEM')
ON CONFLICT ON CONSTRAINT uq_tb_sys_dept_nm DO NOTHING;

-- ── 3. 그룹웨어 부서명 → AX 부서 매핑 ─────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ax.tb_sys_dept_gw_map (
    gw_dept_nm  varchar(100)             NOT NULL,
    dept_id     integer,
    join_yn     common.d_yn              NOT NULL DEFAULT 'Y',
    remark      varchar(200),
    ins_date    timestamp with time zone NOT NULL DEFAULT now(),
    ins_user    common.d_user_id,
    upd_date    timestamp with time zone NOT NULL DEFAULT now(),
    upd_user    common.d_user_id,
    CONSTRAINT pk_tb_sys_dept_gw_map PRIMARY KEY (gw_dept_nm),
    CONSTRAINT fk_sys_dept_gw_map_dept FOREIGN KEY (dept_id) REFERENCES ax.tb_sys_dept (dept_id)
);
CREATE INDEX IF NOT EXISTS ix_sys_dept_gw_map_dept ON ax.tb_sys_dept_gw_map (dept_id);

COMMENT ON TABLE  ax.tb_sys_dept_gw_map            IS '그룹웨어 부서명 → AX 부서 매핑 — 그룹웨어 인사정보 자동 가입 때 부서를 정하는 기준, 가입 순간에만 사용';
COMMENT ON COLUMN ax.tb_sys_dept_gw_map.gw_dept_nm IS '그룹웨어 부서명 — groupware_user.tb_user_list.dept_name 과 글자 그대로 비교';
COMMENT ON COLUMN ax.tb_sys_dept_gw_map.dept_id    IS '가입시킬 AX 부서 ID — 비어 있으면 미배정 부서';
COMMENT ON COLUMN ax.tb_sys_dept_gw_map.join_yn    IS '자동 가입 대상 여부 — N 이면 이 부서 사람은 가입시키지 않음(퇴사자·휴직자 부서 등)';
COMMENT ON COLUMN ax.tb_sys_dept_gw_map.remark     IS '매핑 근거·메모';
COMMENT ON COLUMN ax.tb_sys_dept_gw_map.ins_date   IS '등록 일시';
COMMENT ON COLUMN ax.tb_sys_dept_gw_map.ins_user   IS '등록자 사번';
COMMENT ON COLUMN ax.tb_sys_dept_gw_map.upd_date   IS '수정 일시';
COMMENT ON COLUMN ax.tb_sys_dept_gw_map.upd_user   IS '수정자 사번';

INSERT INTO ax.tb_sys_dept_gw_map (gw_dept_nm, dept_id, join_yn, remark, ins_user, upd_user)
SELECT v.gw_dept_nm, d.dept_id, v.join_yn, v.remark, 'SYSTEM', 'SYSTEM'
  FROM (VALUES
        ('생산관리팀(A)',  '생산관리팀', 'Y', '부서명 일치'),
        ('생산관리팀(M)',  '생산관리팀', 'Y', '부서명 일치'),
        ('제조팀(A)',      '제조팀',     'Y', '부서명 일치'),
        ('제조팀(M)',      '제조팀',     'Y', '부서명 일치'),
        ('품질보증팀(A)',  '품질보증팀', 'Y', '부서명 일치'),
        ('품질보증팀(M)',  '품질보증팀', 'Y', '부서명 일치'),
        ('전산팀',         '전산팀',     'Y', '부서명 일치'),
        ('퇴사자_로그인불가',    NULL, 'N', '그룹웨어에서도 로그인 불가 계정'),
        ('퇴사자 로그인 불가 x', NULL, 'N', '그룹웨어에서도 로그인 불가 계정'),
        ('휴직자_로그인불가',    NULL, 'N', '그룹웨어에서도 로그인 불가 계정')
       ) AS v (gw_dept_nm, dept_nm, join_yn, remark)
  LEFT JOIN ax.tb_sys_dept d ON d.dept_nm = v.dept_nm
ON CONFLICT (gw_dept_nm) DO NOTHING;

COMMIT;
