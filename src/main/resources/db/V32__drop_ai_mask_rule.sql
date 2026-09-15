-- =====================================================================================
--  V32 : 보안 필터링 패턴 표 정리 — ax.tb_ai_mask_rule (2026-09-15, V31 후속)
--
--  [배경]
--  V31 에서 시스템관리 5개 화면을 걷어내면서 [AI 모델 설정(SY-10)] 이 사라졌다.
--  그 화면의 "보안 필터링 패턴" 탭 전용 표가 ax.tb_ai_mask_rule 이다.
--  V31 시점에는 WEB 이 준 분류에서 "자연어 질의가 쓸 수 있다" 고 보아 남겨 두었는데,
--  API·WEB 양쪽 작업이 끝난 뒤 다시 확인해 보니 어느 쪽도 쓰지 않는다.
--
--  [다시 확인한 것 — 쓰는 곳이 없다]
--   · API 코드 : MaskRuleRepository 가 커밋 f35c015 로 삭제됐다. 남은 언급 2곳은 전부
--                KDoc 주석이다(AiChatRepository 머리말의 참조 표 목록, AiAdminController 머리말).
--                실행되는 SQL 은 한 줄도 없다.
--   · WEB      : 보안 필터링 패턴(MASK_RULES) 목 데이터·핸들러를 모두 제거했다(WEB 세션 회신).
--   · DB       : 들어오는 FK 0(유일한 자식 tb_ai_mask_rule_column 은 V31 이 지웠다) ·
--                뷰 0 · 함수 0 · 트리거 0. 로컬 행수 0.
--
--  [자연어 질의의 마스킹은 다른 길로 돈다 — 그래서 안전하다]
--  이 표는 "⑦ 보안 필터링 Agent" 의 규칙 마스터로 설계됐지만, 실제로 구현된 마스킹은
--  부서 × 데이터 항목 권한(ax.tb_sys_data_field · ax.tb_sys_dept_data_perm)을 보는
--  UserPrincipal.canReadField / MaskingSupport 경로다. 자연어 질의도 이 경로로 차단하고
--  감사 로그(MASK/BLIND)를 남긴다. 표를 지워도 그 길은 그대로다.
--  데이터 항목 권한 표는 남는다 — 로컬 기준 항목 7 · 부서 권한 29행.
--
--  [함께 지우는 것]
--  공통코드 그룹 AI_MASK_TYPE(코드 4) 과 코드 참조 1행. 이 그룹을 쓰는 다른 표는 없다.
--  참조 행을 표보다 먼저 지운다 — ax.fn_check_code_ref() 가 tb_sys_code_ref 의 모든 행에
--  `FROM ax.<target_table>` 을 동적 실행하므로 참조가 남으면 설치 후 점검 질의가 터진다.
--
--  [남는 부모 표]
--  나가는 FK 2개(ax.tb_prod_customer · ax.tb_sys_data_field)는 부모 쪽이라 그대로 남는다.
--
--  [되돌리기]
--  구조·공통코드는 rollback/V32__down.sql. 업무 데이터는 적용 전 덤프에서 되돌린다 —
--  로컬은 0행이지만 운영에는 관리자가 등록한 패턴이 있을 수 있다. 반드시 먼저 덤프한다.
--      pg_dump -U <user> -d <db> --no-owner --no-privileges -t ax.tb_ai_mask_rule > mask_rule_backup.sql
--
--  [적용]
--  docker exec -e PGPASSWORD=dwje_local dwje-pg psql -U dwje_local -d dwjedb -v ON_ERROR_STOP=1 -f V32__drop_ai_mask_rule.sql
--  두 번 실행해도 안전하다. 운영 DB 는 요청자 확인 뒤에 적용한다.
-- =====================================================================================

-- 1. 공통코드 참조 정리 (반드시 DROP TABLE 보다 먼저) ------------------------------------
DELETE FROM ax.tb_sys_code_ref
 WHERE target_table = 'tb_ai_mask_rule' AND target_column = 'mask_type_cd';

-- 2. 표 제거 --------------------------------------------------------------------------
DROP TABLE IF EXISTS ax.tb_ai_mask_rule;

-- 3. 고아가 된 공통코드 정리 ------------------------------------------------------------
DELETE FROM ax.tb_sys_code       WHERE group_cd = 'AI_MASK_TYPE';
DELETE FROM ax.tb_sys_code_group WHERE group_cd = 'AI_MASK_TYPE';
