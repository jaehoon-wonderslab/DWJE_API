-- =====================================================================================
--  V49 되돌리기 — 미배정 부서 고정 권한 · 쓰기 권한 이관 (2026-10-01)
--
--  V49 가 남긴 기록표(ax.tb_mig_v49_menu_perm_bak)로 이 파일이 바꾼 행만 이전 값으로 돌린다.
--   · WRITE_ON        : can_write 를 false 로
--   · UNASSIGNED_ADD  : 이전에 행이 없던 것은 지우고, 있던 것은 이전 값으로
--   · UNASSIGNED_DEL  : 지운 행을 되살림
--  기록표가 없으면(이미 되돌렸거나 V49 를 적용하지 않음) 아무것도 하지 않는다.
--
--  [API 를 먼저 내린다]
--  requireWrite 가 들어간 API 를 그대로 두고 되돌리면 전산팀의 저장이 403 이 된다.
--
--  [적용]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f rollback/V49__down.sql
--  두 번 실행해도 안전하다.
-- =====================================================================================

BEGIN;

DO $$
BEGIN
    IF to_regclass('ax.tb_mig_v49_menu_perm_bak') IS NULL THEN
        RAISE NOTICE 'V49 되돌리기: 기록표가 없어 건너뜀';
        RETURN;
    END IF;

    UPDATE ax.tb_sys_dept_menu_perm p
       SET can_write = false, upd_date = now(), upd_user = 'V49-down'
      FROM ax.tb_mig_v49_menu_perm_bak b
     WHERE b.action = 'WRITE_ON'
       AND b.dept_id = p.dept_id AND b.menu_id = p.menu_id
       AND p.can_write;

    DELETE FROM ax.tb_sys_dept_menu_perm p
     USING ax.tb_mig_v49_menu_perm_bak b
     WHERE b.action = 'UNASSIGNED_ADD' AND b.prev_can_read IS NULL
       AND b.dept_id = p.dept_id AND b.menu_id = p.menu_id;

    UPDATE ax.tb_sys_dept_menu_perm p
       SET can_read = b.prev_can_read, can_write = b.prev_can_write, upd_date = now(), upd_user = 'V49-down'
      FROM ax.tb_mig_v49_menu_perm_bak b
     WHERE b.action = 'UNASSIGNED_ADD' AND b.prev_can_read IS NOT NULL
       AND b.dept_id = p.dept_id AND b.menu_id = p.menu_id;

    INSERT INTO ax.tb_sys_dept_menu_perm (dept_id, menu_id, can_read, can_write, ins_user, upd_user)
    SELECT b.dept_id, b.menu_id, b.prev_can_read, b.prev_can_write, 'V49-down', 'V49-down'
      FROM ax.tb_mig_v49_menu_perm_bak b
     WHERE b.action = 'UNASSIGNED_DEL'
       AND EXISTS (SELECT 1 FROM ax.tb_sys_dept d WHERE d.dept_id = b.dept_id)
       AND EXISTS (SELECT 1 FROM ax.tb_sys_menu m WHERE m.menu_id = b.menu_id)
    ON CONFLICT ON CONSTRAINT pk_tb_sys_dept_menu_perm DO NOTHING;

    DROP TABLE ax.tb_mig_v49_menu_perm_bak;
END $$;

COMMENT ON COLUMN ax.tb_sys_dept_menu_perm.can_write IS
  '입력·수정 권한. false 면 조회만 가능하며, 권한 변경 이력의 "입력 권한 부여" 가 이 값을 켠 기록';

COMMIT;
