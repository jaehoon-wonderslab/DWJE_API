-- =====================================================================================
--  V83 : 새로 발견된 응답 데이터 이름 기록 (2026-10-08)
--
--  [배경]
--  데이터 접근 권한은 응답 데이터 이름(JSON 필드명) 단위로 가린다(V82). 개발자가 API 응답에 새 값을 넣으면
--  그 이름이 항목 표(tb_sys_data_field_attr)에 등록되기 전까지 모든 부서에 보인다. 등록할 이름을 사람이 찾지 않아도 되게,
--  서버가 응답을 내보낼 때 「등록되지 않은 값 이름」 을 이 표에 남기고 데이터 접근 권한 화면이 「새로 발견된 응답 데이터」 로 보인다.
--  관리자는 이름마다 기존 항목에 넣기 · 새 항목 만들기 · 가리지 않음 중 하나를 고른다(사용자 결정 2026-10-08).
--
--  · 값은 남기지 않는다 — 이름 · 처음/마지막 발견 시각 · 발견 횟수 · 나온 API 경로만.
--  · 등록된 이름은 목록에서 빠진다(조회 때 항목 표와 맞춰 본다). 이 표의 행은 남겨 둔다(다시 풀리면 다시 보이게).
--  · status_cd = NEW(처리 전, 모든 부서에 보임) | IGNORED(가리지 않음 — 다시 알리지 않음, 되돌릴 수 있음)
--
--  [순서] — 이 파일 → 새 API (옛 API 는 이 표를 모른다)
--  [실행]  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f V83__data_attr_seen.sql   (두 번 실행해도 안전)
--  되돌리기는 rollback/V83__down.sql
-- =====================================================================================

BEGIN;

CREATE TABLE IF NOT EXISTS ax.tb_sys_data_attr_seen (
    attr_name     varchar(60)  NOT NULL,
    first_seen_at timestamptz  NOT NULL DEFAULT now(),
    last_seen_at  timestamptz  NOT NULL DEFAULT now(),
    seen_cnt      bigint       NOT NULL DEFAULT 1,
    api_paths     varchar(1000),
    status_cd     varchar(10)  NOT NULL DEFAULT 'NEW',
    upd_date      timestamptz  NOT NULL DEFAULT now(),
    upd_user      common.d_user_id,
    CONSTRAINT pk_sys_data_attr_seen PRIMARY KEY (attr_name),
    CONSTRAINT ck_sys_data_attr_seen_status CHECK (status_cd IN ('NEW', 'IGNORED'))
);
COMMENT ON TABLE  ax.tb_sys_data_attr_seen               IS '응답에서 발견된, 항목 표에 등록되지 않은 응답 데이터 이름(V83). 데이터 접근 권한 화면의 「새로 발견된 응답 데이터」';
COMMENT ON COLUMN ax.tb_sys_data_attr_seen.attr_name     IS 'API 응답 JSON 필드명(대소문자 구분). 값은 저장하지 않는다';
COMMENT ON COLUMN ax.tb_sys_data_attr_seen.first_seen_at IS '처음 발견한 시각';
COMMENT ON COLUMN ax.tb_sys_data_attr_seen.last_seen_at  IS '마지막으로 발견한 시각';
COMMENT ON COLUMN ax.tb_sys_data_attr_seen.seen_cnt      IS '발견 횟수(경로마다 일정 간격으로 표본을 보므로 호출 수가 아니다)';
COMMENT ON COLUMN ax.tb_sys_data_attr_seen.api_paths     IS '나온 API 경로(숫자 경로 조각은 {id}) — 쉼표로 이어 1000자까지';
COMMENT ON COLUMN ax.tb_sys_data_attr_seen.status_cd     IS 'NEW 처리 전(모든 부서에 보임) / IGNORED 가리지 않음으로 처리';

COMMIT;
