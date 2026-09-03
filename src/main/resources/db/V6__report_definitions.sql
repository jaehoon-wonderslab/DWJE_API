-- =====================================================================================
--  보고서 정의 등록 (ax.tb_rpt_report)
--
--  배경
--  ----
--  ax.tb_rpt_report 는 "어떤 보고서가 있는지"를 담는 정의 테이블이며,
--  ax.tb_rpt_form.report_id 와 ax.tb_rpt_doc.report_id 가 이 테이블을 FK 로 참조한다.
--  그런데 기존 DDL 에 정의 시드가 없어, 보고서 문서를 만들면 아래 오류가 난다.
--
--    ERROR: insert or update on table "tb_rpt_doc" violates foreign key constraint
--           Key (report_id)=(RPT_DAILY_PROD) is not present in table "tb_rpt_report"
--
--  일일 생산현황 보고(PR-03) 초안 생성이 여기서 막히므로 정의 8종을 등록한다.
--  menu_id 는 V5__report_menu.sql 로 등록된 화면과 연결한다.
--
--  적용 : psql -d dwjedb -f V6__report_definitions.sql
-- =====================================================================================

SET search_path TO ax, mes, vec, common, public;

INSERT INTO ax.tb_rpt_report (report_id, report_nm, report_group, menu_id, sort_seq, use_flg) VALUES
 ('RPT_DAILY_PROD',      '일일 생산현황 보고',            '생산관리', 'prod-daily',          1, 'Y'),
 ('RPT_QUALITY',         '품질 보고서',                   '품질관리', 'qc-report',           2, 'Y'),
 ('RPT_PRESS_MORNING',   '아침회의 자료 (PRESS)',          '보고서',   'rpt-press-morning',   3, 'Y'),
 ('RPT_PLATING_MORNING', '아침회의 자료 (Plating·Coating)','보고서',   'rpt-plating-morning', 4, 'Y'),
 ('RPT_SHIP_PLAN',       '연간 출하계획',                 '보고서',   'rpt-ship-plan',       5, 'Y'),
 ('RPT_YIELD_MODEL',     '제품별 수율',                   '보고서',   'rpt-yield-model',     6, 'Y'),
 ('RPT_LRR_CUSTOMER',    '고객사별 LRR',                  '보고서',   'rpt-lrr-customer',    7, 'Y'),
 ('RPT_SCRAP',           '폐기 보고서',                   '보고서',   'rpt-scrap',           8, 'Y')
ON CONFLICT (report_id) DO NOTHING;

-- menu_id 가 아직 없는 환경(V5 미적용)에서도 실패하지 않도록 정합성을 확인한다.
UPDATE ax.tb_rpt_report r
   SET menu_id = NULL
 WHERE r.menu_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM ax.tb_sys_menu m WHERE m.menu_id = r.menu_id);
