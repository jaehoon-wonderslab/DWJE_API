-- =====================================================================================
--  V78 : 직급(SYS_POSITION)에 그룹웨어 직위 26종 추가 · 자동 가입 계정의 직급을 그룹웨어 직위로 채움 (2026-10-06)
--
--  [배경]
--  그룹웨어 자동 가입(MES_migration_engine GroupwareAxJoinWriter)은 그룹웨어 직위명이 SYS_POSITION 의 코드 이름과
--  **같을 때만** 그 직급을 쓰고, 아니면 사원(STAFF)으로 넣는다. AX 직급은 7종(사원 · 반장 · 선임 · 팀장 · 상무 · 이사 · 관리자)뿐이라
--  대리 · 과장 · 차장 · 부장 … 이 모두 사원이 되어, 계정 관리의 직급 목록 필터에 사원만 보였다.
--  그룹웨어 직위 26종을 직급 코드로 넣고(attr1 = 'GW' — 그룹웨어 직위 표시), 이미 사원으로 들어간 자동 가입 계정을
--  그룹웨어 직위로 고친다. 엔진은 이름으로 맞추므로 이후 새로 가입하는 계정은 코드 변경 없이 바른 직급으로 들어간다.
--
--  [이 파일이 하는 일]
--   1. SYS_POSITION 코드 24종 추가(사원 STAFF · 이사 EXEC 는 이미 있음) · 26종에 attr1 = 'GW' · 정렬 순서(직위 높은 순)
--      기존 AX 전용 직급(반장 · 선임 · 팀장 · 상무 · 관리자)은 그대로 두고 뒤로 보낸다.
--   2. 보관 표 ax.tb_mig_v78_user_pos_bak(user_id, position_cd) — 고치기 전 직급
--   3. 직급이 사원(STAFF)인 계정 중 그룹웨어 명단(groupware_user.tb_user_list, 사번이 한 명뿐인 행)의 직위가 다른 계정을
--      그 직위 코드로 바꾼다. 사람이 다른 직급으로 바꿔 둔 계정은 건드리지 않는다.
--      groupware_user 스키마가 없는 DB(그룹웨어 연동 전)에서는 3 을 건너뛴다.
--
--  [실행]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f V78__sys_position_groupware.sql
--  두 번 실행해도 안전하다. 되돌리기는 rollback/V78__down.sql(보관 표로 직급을 되돌리고 추가 코드를 지움).
-- =====================================================================================

BEGIN;

-- ── 1. 직급 코드 ─────────────────────────────────────────────────────────────────────
INSERT INTO ax.tb_sys_code (group_cd, code, code_nm, code_desc, attr1, sort_seq, use_flg, ins_user, upd_user)
SELECT 'SYS_POSITION', v.code, v.nm, '그룹웨어 직위(V78)', 'GW', v.seq, 'Y', 'V78', 'V78'
  FROM (VALUES
        ('CHAIRMAN',    '회장',       1), ('VICE_CHAIR',  '부회장',     2), ('CEO',         '대표이사',   3),
        ('VICE_PRES',   '부사장',     4), ('SR_EXEC_DIR', '전무이사',   5), ('EXEC_DIR',    '상무이사',   6),
        ('ADVISOR',     '고문',       8), ('GEN_MGR',     '부장',       9), ('DEP_GEN_MGR', '차장',      10),
        ('MANAGER',     '과장',      11), ('ASST_MGR',    '대리',      12), ('ASSOC',       '주임',      13),
        ('CHIEF_CLERK', '계장',      14), ('PRIN_RES',    '수석연구원', 15), ('SR_RES',      '책임연구원', 16),
        ('RES_SENIOR',  '선임연구원', 17), ('RES_ASSOC',   '주임연구원', 18), ('RES_FULL',    '전임연구원', 19),
        ('RESEARCHER',  '연구원',    20), ('WORK_LEAD',   '직장',      21), ('ENGINEER',    '기사',      22),
        ('ENG_ASST',    '기사보',    23), ('TECH_STAFF',  '기술사원',  25), ('CONTRACT',    '계약직',    26)
       ) AS v(code, nm, seq)
ON CONFLICT (group_cd, code) DO UPDATE
   SET code_nm = EXCLUDED.code_nm, attr1 = 'GW', sort_seq = EXCLUDED.sort_seq, use_flg = 'Y', upd_date = now(), upd_user = 'V78';

-- 사원 · 이사는 이미 있다 — 그룹웨어 직위 표시와 순서만
UPDATE ax.tb_sys_code SET attr1 = 'GW', sort_seq = 7,  upd_date = now(), upd_user = 'V78' WHERE group_cd = 'SYS_POSITION' AND code = 'EXEC';
UPDATE ax.tb_sys_code SET attr1 = 'GW', sort_seq = 24, upd_date = now(), upd_user = 'V78' WHERE group_cd = 'SYS_POSITION' AND code = 'STAFF';
-- AX 전용 직급은 뒤로(반장 · 선임 · 팀장 · 상무 · 관리자)
UPDATE ax.tb_sys_code SET sort_seq = 100 + sort_seq, upd_date = now(), upd_user = 'V78'
 WHERE group_cd = 'SYS_POSITION' AND code IN ('FOREMAN', 'SENIOR', 'LEADER', 'DIRECTOR', 'ADMIN') AND sort_seq < 100;

-- ── 2 · 3. 자동 가입 계정 직급 채우기 ───────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ax.tb_mig_v78_user_pos_bak (
    user_id     varchar(30) NOT NULL,
    position_cd varchar(30),
    backed_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_tb_mig_v78_user_pos_bak PRIMARY KEY (user_id)
);
COMMENT ON TABLE  ax.tb_mig_v78_user_pos_bak             IS 'V78 되돌리기용 — 그룹웨어 직위로 바꾸기 전 계정 직급';
COMMENT ON COLUMN ax.tb_mig_v78_user_pos_bak.user_id     IS '사번 (ax.tb_sys_user 논리 참조)';
COMMENT ON COLUMN ax.tb_mig_v78_user_pos_bak.position_cd IS '바꾸기 전 직급 코드(SYS_POSITION)';
COMMENT ON COLUMN ax.tb_mig_v78_user_pos_bak.backed_at   IS '보관한 시각';

DO $$
DECLARE
    v_cnt integer;
BEGIN
    IF to_regclass('groupware_user.tb_user_list') IS NULL THEN
        RAISE NOTICE 'V78: groupware_user.tb_user_list 가 없어 계정 직급 채우기는 건너뜀';
        RETURN;
    END IF;
    EXECUTE $q$
        WITH gw AS (
            SELECT l.empno, l.position_name
              FROM groupware_user.tb_user_list l
             WHERE NOT EXISTS (SELECT 1 FROM groupware_user.tb_user_list x WHERE x.empno = l.empno AND x.id <> l.id)
        ), target AS (
            SELECT u.user_id, c.code
              FROM ax.tb_sys_user u
              JOIN gw ON gw.empno = u.user_id
              JOIN ax.tb_sys_code c ON c.group_cd = 'SYS_POSITION' AND c.use_flg = 'Y' AND c.code_nm = gw.position_name
             WHERE u.position_cd = 'STAFF' AND c.code <> 'STAFF'
        ), bak AS (
            INSERT INTO ax.tb_mig_v78_user_pos_bak (user_id, position_cd)
            SELECT user_id, 'STAFF' FROM target
            ON CONFLICT (user_id) DO NOTHING
            RETURNING user_id
        )
        UPDATE ax.tb_sys_user u SET position_cd = t.code, upd_date = now(), upd_user = 'V78'
          FROM target t WHERE u.user_id = t.user_id $q$;
    GET DIAGNOSTICS v_cnt = ROW_COUNT;
    RAISE NOTICE 'V78: 자동 가입 계정 직급을 그룹웨어 직위로 바꿈 % 건', v_cnt;
END $$;

COMMIT;
