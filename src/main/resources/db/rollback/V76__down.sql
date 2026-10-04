-- =====================================================================================
--  V76 되돌리기 — ALM_CHANNEL attr1 · code_desc 를 V76 전(비어 있음)으로, SMS · MSG 사용 다시 켬, attr1 주석 원래대로 (2026-10-04)
--  옛 API(attr1 을 보지 않음)로 함께 되돌린다. 새 API 가 떠 있는 채로 되돌리면 모든 채널 저장이 400 이 된다.
--  두 번 실행해도 안전하다.
-- =====================================================================================
BEGIN;
UPDATE ax.tb_sys_code SET attr1 = NULL, upd_date = now(), upd_user = 'V76_DOWN'
 WHERE group_cd = 'ALM_CHANNEL' AND code IN ('MAIL', 'POPUP');
UPDATE ax.tb_sys_code SET code_desc = NULL, use_flg = 'Y', upd_date = now(), upd_user = 'V76_DOWN'
 WHERE group_cd = 'ALM_CHANNEL' AND code IN ('SMS', 'MSG');
COMMENT ON COLUMN ax.tb_sys_code.attr1 IS '부가 속성 1 (예: 심각도 색상, 채널 발송 어댑터명)';
COMMIT;
