-- =====================================================================================
--  V76 : 발송 채널(ALM_CHANNEL)은 메일 · 시스템 팝업 2개만 — 「발송 연동됨」 표시 · SMS · 메신저 사용 중지 (2026-10-04)
--
--  [배경]
--  수신 그룹 화면에서 발송 채널을 고르고 바꿀 수 있게 한다. 그런데 공통코드 ALM_CHANNEL 의 SMS · 메신저 연동(MSG)은
--  연동처(문자 업체 · 사내 메신저 API)가 정해지지 않아 알림 엔진이 보내지 않는다(UnconfiguredChannels — 대기열에서 바로 DEAD).
--  고를 수 있게 두면 「SMS 로 알렸다」 는 설정만 남고 아무도 받지 못한다.
--  attr1 은 원래 「채널 발송 어댑터명」 칸이다. 엔진에 실제 어댑터가 있는 채널에만 그 이름을 채우고(MAIL · POPUP),
--  연동 전 채널은 비워 둔다. 화면은 attr1 이 있는 채널만 고르게 하고, API 는 attr1 이 빈 채널을
--  수신 그룹 · 발송 조건에 저장하지 않는다(400). 연동이 붙으면 그 코드의 attr1 만 채우면 된다
--  사용자 결정(2026-10-04): 채널은 메일 · 시스템 팝업 2개만 쓴다 — SMS · MSG 는 사용 중지(use_flg = 'N')한다.
--  사용 중지 코드는 화면 선택지에서 빠지고 API 도 「알 수 없는 발송 채널」 로 막는다. 지난 발송 기록의 표기는 코드명을 그대로 읽는다.
--  시스템 팝업은 웹 우측 상단 토스트(5초)로 띄운다(GET /alerts/popups).
--
--  [이 파일이 하는 일]
--   1. MAIL · POPUP → attr1 = 어댑터 코드(MAIL · POPUP),  SMS · MSG → attr1 = NULL · use_flg = 'N' · code_desc 안내
--   2. 그룹 · 컬럼 주석
--  지금 SMS · MSG 를 쓰는 수신 그룹 · 발송 조건이 있으면 건수를 알린다(자동으로 지우지 않는다 — 사람이 바꾼다).
--
--  [순서]
--  이 파일 → 새 API(attr1 검사) → WEB. 새 API 가 먼저 뜨면 attr1 이 비어 있어 모든 채널 저장이 400 이 된다.
--  옛 API 는 attr1 을 보지 않으므로 이 파일을 먼저 적용해도 문제없다.
--
--  [실행]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f V76__alm_channel_ready_flag.sql
--  두 번 실행해도 안전하다. 되돌리기는 rollback/V76__down.sql.
-- =====================================================================================

BEGIN;

UPDATE ax.tb_sys_code
   SET attr1 = code, upd_date = now(), upd_user = 'V76'
 WHERE group_cd = 'ALM_CHANNEL' AND code IN ('MAIL', 'POPUP') AND attr1 IS DISTINCT FROM code;

UPDATE ax.tb_sys_code
   SET attr1 = NULL, use_flg = 'N',
       code_desc = '사용 안 함(2026-10-04) — 발송 채널은 메일 · 시스템 팝업만 씁니다. 연동처도 없어 알림 엔진이 보내지 않습니다',
       upd_date = now(), upd_user = 'V76'
 WHERE group_cd = 'ALM_CHANNEL' AND code IN ('SMS', 'MSG');

DO $$
DECLARE
    v_grp  integer;
    v_cond integer;
BEGIN
    SELECT count(*) INTO v_grp  FROM ax.tb_alm_recip_group_channel WHERE channel_cd IN ('SMS', 'MSG');
    SELECT count(*) INTO v_cond FROM ax.tb_alm_cond_channel        WHERE channel_cd IN ('SMS', 'MSG');
    RAISE NOTICE 'V76: SMS · 메신저를 쓰는 수신 그룹 채널 % 건 · 발송 조건 채널 % 건(그대로 둠 — 화면에서 바꾸십시오. 엔진은 보내지 않음)', v_grp, v_cond;
END $$;

COMMENT ON COLUMN ax.tb_sys_code.attr1 IS
  '부가 속성 1 (예: 심각도 색상, 채널 발송 어댑터명). ALM_CHANNEL 은 알림 엔진 어댑터 코드 — 비어 있으면 발송 연동 전이라 수신 그룹 · 발송 조건에 고를 수 없음(V76)';

COMMIT;
