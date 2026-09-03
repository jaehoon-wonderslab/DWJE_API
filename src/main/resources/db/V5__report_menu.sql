-- =====================================================================================
--  보고서 화면(RP-01~07) 메뉴 등록
--
--  배경
--  ----
--  기능명세서 CM-01-F07 은 프로토타입에서 보고서 모듈이 `window.REPORTS` 에 스스로 등록되어
--  메뉴 그룹이 **동적으로 삽입**되는 방식을 설명한다. 화면단 프로토타입에서는 유효하지만,
--  서버는 메뉴 접근 권한을 `ax.tb_sys_dept_menu_perm × ax.tb_sys_menu` 로 판정하므로
--  보고서 화면이 tb_sys_menu 에 없으면 **어떤 부서에도 권한을 줄 수 없다.**
--  (통합관리자만 is_super_admin 으로 우회 접근)
--
--  기존 DDL 에 보고서 화면 7종이 누락되어 있어 여기서 등록한다.
--  화면 ID 와 접근 부서는 「기능 및 API 명세 / 화면-API 매핑」 시트를 따른다.
--
--  적용 : psql -d dwjedb -f V5__report_menu.sql
-- =====================================================================================

SET search_path TO ax, mes, vec, common, public;

INSERT INTO ax.tb_sys_menu (menu_id, menu_nm, group_id, parent_menu_id, is_sub_page, route_path, tag_cd, sort_seq) VALUES
 ('rpt-press-morning',   '아침회의 자료 (PRESS)',            'report', NULL, false, '#rpt-press-morning',   'NEW', 1),
 ('rpt-plating-morning', '아침회의 자료 (Plating·Coating)',  'report', NULL, false, '#rpt-plating-morning', 'NEW', 2),
 ('rpt-ship-plan',       '연간 출하계획',                     'report', NULL, false, '#rpt-ship-plan',       'NEW', 3),
 ('rpt-yield-model',     '제품별 수율',                       'report', NULL, false, '#rpt-yield-model',     'NEW', 4),
 ('rpt-lrr-customer',    '고객사별 LRR',                      'report', NULL, false, '#rpt-lrr-customer',    'NEW', 5),
 ('rpt-scrap',           '폐기 보고서',                       'report', NULL, false, '#rpt-scrap',           'NEW', 6)
ON CONFLICT (menu_id) DO NOTHING;

-- 폐기 보고서 작성 위저드는 폐기 보고서의 하위 화면이다.
INSERT INTO ax.tb_sys_menu (menu_id, menu_nm, group_id, parent_menu_id, is_sub_page, route_path, tag_cd, sort_seq) VALUES
 ('rpt-scrap-new', '폐기 보고서 작성 위저드', 'report', 'rpt-scrap', true, '#rpt-scrap-new', 'NEW', 7)
ON CONFLICT (menu_id) DO NOTHING;

COMMENT ON TABLE ax.tb_sys_menu IS '화면(메뉴) 정의 — 보고서 화면 7종 포함. 메뉴 접근 권한 판정의 기준 테이블';
