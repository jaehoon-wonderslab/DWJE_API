-- =====================================================================================
--  V15 : 비가동 관리 화면 내리기 (prod-down)
--
--  [배경]
--  2026-09-04 지시 — "비가동 관리 메뉴를 삭제할 것. (해당 기능은 공장 현장의 별도의
--  시스템에서 등록해 줄 것임)"
--
--  기능이 없어지는 것이 아니라 **등록 주체가 공장 현장 시스템으로 옮겨 간다.**
--  그래서 API 엔드포인트(POST/PUT /api/v1/production/downtimes 등)는 **그대로 둔다** —
--  현장 시스템이 그 엔드포인트를 호출한다. 지우면 그쪽이 등록할 경로가 없어진다.
--
--  내리는 것은 화면 마스터의 한 행뿐이다. 웹이 화면·라우트·리포지토리를 걷어냈는데
--  서버 마스터에 남아 있으면, 권한 관리 화면(sys-menu)에 '비가동 관리' 행이 뜨고
--  관리자가 켜고 꺼도 아무 일이 일어나지 않는다.
--
--  [물리 삭제하지 않는 이유]
--  1. 부서별 메뉴 권한 3건(통합관리자·생산관리팀·제조팀)이 FK 로 붙어 있다.
--     물리 삭제하려면 그 행도 지워야 하고, 누가 접근 권한을 가졌었는지 기록이 사라진다.
--  2. 현장 시스템 이관이 뒤집히면 use_flg 를 'Y' 로 되돌리면 권한까지 그대로 복원된다.
--  3. 조회 네 곳이 이미 use_flg = 'Y' 로 걸러서, 응답에서 빠지는 효과는 물리 삭제와 같다.
--       SystemUserRepository.findAllMenus()        /system/menu-perms 의 screens
--       AuthRepository.findMenuPermissions()       비-통합관리자 /auth/me 의 menuPerms
--       AuthRepository.findAllMenuIds()            통합관리자 /auth/me 의 menuPerms
--       AuthRepository.findMenuTree()              좌측 메뉴 트리
--     그래서 애플리케이션 코드 변경은 없다.
--
--  권한 행 3건도 남긴다. 메뉴가 내려가면 무력해지므로(전 쿼리가 use_flg='Y' 로 조인)
--  지워도 동작은 같은데, 남기면 복원 시 정확히 되돌아간다.
--
--  [영향]
--  사용중 화면 36 → 35건 (웹 메뉴 35건과 일치)
--  production 그룹에 다른 화면 4개가 남아 그룹이 비지 않는다.
--
--  [되돌리기]
--  UPDATE ax.tb_sys_menu SET use_flg = 'Y' WHERE menu_id = 'prod-down';
-- =====================================================================================

SET search_path TO ax, mes, common, public;

UPDATE ax.tb_sys_menu
   SET use_flg = 'N'
 WHERE menu_id = 'prod-down'
   AND use_flg = 'Y';
