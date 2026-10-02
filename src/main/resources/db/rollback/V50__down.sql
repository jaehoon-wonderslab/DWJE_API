-- =====================================================================================
--  V50 되돌리기 — 대그룹 「자연어 질의 이력」·「용어 사전」 · 화면 gloss-view (2026-10-01)
--
--  · gloss-view 화면 행을 지운다. 부서 권한·계정 추가 허용·보고서 사용 기록은 FK CASCADE 로 함께 지워진다.
--  · chat-history 를 시스템관리 그룹(경로 /system/chat-history, 순서 7, NEW)으로 돌리고,
--    V50 이 넣은 chat-history 조회 권한 행만 지운다(ins_user = V50).
--  · 비게 된 대그룹 history · glossary 를 지우고 alert 5 · system 6 순서로 돌린다.
--
--  [WEB 을 함께 되돌린다]
--  WEB 메뉴 정의가 새 그룹을 쓰는 채로 두면 메뉴 접근 권한 화면에서 chat-history 행이 엉뚱한 그룹에 붙는다.
--
--  [적용]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f rollback/V50__down.sql
--  두 번 실행해도 안전하다.
-- =====================================================================================

BEGIN;

DELETE FROM ax.tb_sys_menu WHERE menu_id = 'gloss-view';

DELETE FROM ax.tb_sys_dept_menu_perm WHERE menu_id = 'chat-history' AND ins_user = 'V50';

UPDATE ax.tb_sys_menu
   SET group_id = 'system', route_path = '/system/chat-history', sort_seq = 7, tag_cd = 'NEW',
       upd_date = now(), upd_user = 'V50-down'
 WHERE menu_id = 'chat-history'
   AND (group_id, route_path, sort_seq, tag_cd) IS DISTINCT FROM ('system', '/system/chat-history', 7::smallint, 'NEW');

DELETE FROM ax.tb_sys_menu_group g
 WHERE g.group_id IN ('history', 'glossary')
   AND NOT EXISTS (SELECT 1 FROM ax.tb_sys_menu m WHERE m.group_id = g.group_id);

UPDATE ax.tb_sys_menu_group SET sort_seq = 5 WHERE group_id = 'alert'  AND sort_seq IS DISTINCT FROM 5;
UPDATE ax.tb_sys_menu_group SET sort_seq = 6 WHERE group_id = 'system' AND sort_seq IS DISTINCT FROM 6;

COMMIT;
