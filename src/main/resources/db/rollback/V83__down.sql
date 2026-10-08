-- V83 되돌리기 — 새로 발견된 응답 데이터 기록표를 지운다(2026-10-08). 순서: 옛 API → 이 파일
BEGIN;
DROP TABLE IF EXISTS ax.tb_sys_data_attr_seen;
COMMIT;
