-- =====================================================================================
--  V78 되돌리기 — 계정 직급을 보관 표의 값으로 되돌리고, V78 이 추가한 직급 코드 24종을 지우고, 표시 · 순서를 V78 전으로 (2026-10-06)
--  보관 표에 없는 계정이 새 코드를 쓰고 있으면(V78 이후 새로 가입) 사원(STAFF)으로 바꾼다. 두 번 실행해도 안전하다.
-- =====================================================================================
BEGIN;
UPDATE ax.tb_sys_user u SET position_cd = b.position_cd, upd_date = now(), upd_user = 'V78_DOWN'
  FROM ax.tb_mig_v78_user_pos_bak b WHERE b.user_id = u.user_id;
UPDATE ax.tb_sys_user SET position_cd = 'STAFF', upd_date = now(), upd_user = 'V78_DOWN'
 WHERE position_cd IN (SELECT code FROM ax.tb_sys_code WHERE group_cd = 'SYS_POSITION' AND code_desc = '그룹웨어 직위(V78)');
DELETE FROM ax.tb_sys_code WHERE group_cd = 'SYS_POSITION' AND code_desc = '그룹웨어 직위(V78)';
UPDATE ax.tb_sys_code SET attr1 = NULL, upd_date = now(), upd_user = 'V78_DOWN' WHERE group_cd = 'SYS_POSITION' AND code IN ('STAFF', 'EXEC');
UPDATE ax.tb_sys_code SET sort_seq = CASE code WHEN 'STAFF' THEN 1 WHEN 'FOREMAN' THEN 2 WHEN 'SENIOR' THEN 3 WHEN 'LEADER' THEN 4
                                               WHEN 'DIRECTOR' THEN 5 WHEN 'EXEC' THEN 6 WHEN 'ADMIN' THEN 7 END,
                          upd_date = now(), upd_user = 'V78_DOWN'
 WHERE group_cd = 'SYS_POSITION' AND code IN ('STAFF', 'FOREMAN', 'SENIOR', 'LEADER', 'DIRECTOR', 'EXEC', 'ADMIN');
COMMIT;
