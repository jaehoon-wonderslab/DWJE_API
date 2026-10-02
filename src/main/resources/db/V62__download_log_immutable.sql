-- =====================================================================================
--  V62 : 보고서 다운로드 이력 원본 표 위변조 방지 트리거 (2026-10-01)
--
--  [배경]
--  다운로드 이력도 감사 기록이라 고치거나 지울 수 없어야 한다. 감사 표(V51)와 같은 함수를 건다.
--  기획: 10 보고서 다운로드 이력 4.5 · DLG-07 (공통 묶음 M-4).
--
--  [이 파일이 하는 일]
--  ax.tb_rpt_download_log · ax.tb_rpt_download_blind 에 행 UPDATE · DELETE 차단, TRUNCATE 차단 트리거.
--  예외는 V51 함수 그대로 — 세션 설정 ax.audit_purge = on 인 DELETE(보존 기간 정리 배치)만 허용.
--  tb_rpt_download_blind 는 원본 log 삭제 때 FK CASCADE 로 함께 지워지므로 정리 배치는 반드시
--  SET LOCAL ax.audit_purge = 'on' 안에서 지운다.
--
--  [적용 전 확인 — 2026-10-01]
--  · API DownloadLogRepository.insertBlindDetail 은 ON CONFLICT DO NOTHING 이라 트리거와 충돌하지 않는다.
--  · API 테스트 DownloadLogFilterContractTest 는 원본 표를 DELETE 로 정리하던 것을 롤백 트랜잭션
--    (클래스 @Transactional)으로 바꿨다. 그 뒤 API 코드 · 테스트에 두 표를 UPDATE · DELETE · TRUNCATE 하는 곳이 없다.
--  · 원본 표를 고치는 코드를 새로 넣으면 이 트리거가 막는다. 정리가 필요하면 롤백 트랜잭션을 쓴다.
--
--  [실행]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f V62__download_log_immutable.sql
--  두 번 실행해도 안전하다. 되돌리기는 rollback/V62__down.sql.
-- =====================================================================================

BEGIN;

DO $$
DECLARE
    t text;
BEGIN
    IF to_regprocedure('ax.fn_block_audit_mutation()') IS NULL OR to_regprocedure('ax.fn_block_audit_truncate()') IS NULL THEN
        RAISE EXCEPTION 'V62 중단: 변경 차단 트리거 함수가 없습니다. V51 을 먼저 적용하십시오.';
    END IF;

    FOREACH t IN ARRAY ARRAY['tb_rpt_download_log', 'tb_rpt_download_blind'] LOOP
        EXECUTE format('DROP TRIGGER IF EXISTS tg_%1$s_immutable ON ax.%1$s', t);
        EXECUTE format('CREATE TRIGGER tg_%1$s_immutable BEFORE UPDATE OR DELETE ON ax.%1$s
                        FOR EACH ROW EXECUTE FUNCTION ax.fn_block_audit_mutation()', t);
        EXECUTE format('DROP TRIGGER IF EXISTS tg_%1$s_no_truncate ON ax.%1$s', t);
        EXECUTE format('CREATE TRIGGER tg_%1$s_no_truncate BEFORE TRUNCATE ON ax.%1$s
                        FOR EACH STATEMENT EXECUTE FUNCTION ax.fn_block_audit_truncate()', t);
    END LOOP;
END $$;

COMMIT;
