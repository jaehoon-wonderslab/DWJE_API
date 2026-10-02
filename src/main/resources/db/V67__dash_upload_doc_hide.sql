-- =====================================================================================
--  V67 : 업로드 문서 숨김 · 복원 — 숨긴 시각 · 숨긴 사람 · 사유, 전산팀 쓰기 권한 (2026-10-02)
--
--  [배경]
--  결정 R-19(D-13): 잘못 올린 업로드 문서를 회의 드롭다운에서 치울 수단이 없었다. 문서 단위 소프트 삭제
--  (숨김 · 복원)를 둔다. 기존 del_flg 를 쓰고, 누가 · 언제 · 왜 숨겼는지를 더한다. 원본 파일은 지우지 않는다.
--  기획: 공통 문서 11.3, 11 업로드 문서 목록 8장 Q1 · UPD-14.
--
--  [이 파일이 하는 일]
--   1. ax.tb_dash_upload_doc — del_at timestamptz · del_user d_user_id · del_reason varchar(200) (모두 NULL 허용)
--      숨길 때 채우고, 복원하면 비운다(복원 기록은 감사 로그 CONFIG_CHANGE 에 남음)
--   2. 쓰기 권한 이관 — 전산팀 × sys-upload-doc can_write = true (컬럼을 처음 만들 때만).
--      숨김 · 복원 API 는 requireWrite(SYS_UPLOAD_DOC). 통합관리자는 행 없이 통과
--   3. 주석 — 새 컬럼 3개와 del_flg
--
--  [순서]
--  이 파일 → API 배포(숨김 · 복원 API). 거꾸로 하면 전산팀의 숨김이 403 이고, 숨김 저장이 실패한다.
--
--  [실행]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f V67__dash_upload_doc_hide.sql
--  두 번 실행해도 안전하다. 되돌리기는 rollback/V67__down.sql.
-- =====================================================================================

BEGIN;

DO $$
DECLARE
    v_first boolean;
    v_cnt   integer;
BEGIN
    v_first := NOT EXISTS (SELECT 1 FROM information_schema.columns
                            WHERE table_schema = 'ax' AND table_name = 'tb_dash_upload_doc' AND column_name = 'del_at');

    ALTER TABLE ax.tb_dash_upload_doc ADD COLUMN IF NOT EXISTS del_at     timestamptz;
    ALTER TABLE ax.tb_dash_upload_doc ADD COLUMN IF NOT EXISTS del_user   common.d_user_id;
    ALTER TABLE ax.tb_dash_upload_doc ADD COLUMN IF NOT EXISTS del_reason varchar(200);

    IF v_first THEN
        UPDATE ax.tb_sys_dept_menu_perm p
           SET can_write = true, upd_date = now(), upd_user = 'V67'
          FROM ax.tb_sys_dept d
         WHERE d.dept_id = p.dept_id
           AND d.dept_nm = '전산팀'
           AND p.menu_id = 'sys-upload-doc'
           AND p.can_read
           AND NOT p.can_write;
        GET DIAGNOSTICS v_cnt = ROW_COUNT;
        RAISE NOTICE 'V67: 전산팀 sys-upload-doc 쓰기 권한 이관 % 건', v_cnt;
    ELSE
        RAISE NOTICE 'V67: 컬럼이 이미 있어 쓰기 권한 이관은 건너뜀(이미 적용됨)';
    END IF;
END $$;

COMMENT ON COLUMN ax.tb_dash_upload_doc.del_flg IS
  'Y = 숨김(소프트 삭제). 대시보드 업로드 리포트 · AI 패널 문서 선택 · 업로드 문서 목록 기본 조회에서 빠짐. 버전 · 파일은 지우지 않음';
COMMENT ON COLUMN ax.tb_dash_upload_doc.del_at IS
  '숨긴 시각. 숨기지 않았거나 복원하면 NULL';
COMMENT ON COLUMN ax.tb_dash_upload_doc.del_user IS
  '숨긴 사람 사번. 숨기지 않았거나 복원하면 NULL';
COMMENT ON COLUMN ax.tb_dash_upload_doc.del_reason IS
  '숨긴 사유(필수 입력, 200자). 업로드 문서 목록 화면 숨긴 행에 표시. 숨기지 않았거나 복원하면 NULL';

COMMIT;
