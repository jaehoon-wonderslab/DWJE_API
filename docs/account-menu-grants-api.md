# 계정별 추가 허용 메뉴 · 계정 화면 목록 검색/페이징 — API 계약 (2026-09-13)

DB 계약 `docs/account-menu-grants-db.md`(V30 `ax.tb_sys_user_menu_grant` · `ax.vw_sys_user_menu_perm`)의 API 쪽 구현이다.
WEB 라이브 검증 `WEB-ai_concep_design/tests/system/account-grants-live.cjs` 가 로컬 8080 에서 **PASS** 했다.

## 1. 한 줄 규칙

```
유효 화면 권한 = 부서 권한 ∪ 계정 추가 허용        — 로그인 · 계정 전환 · /auth/me · GET /menus · 모든 API 의 requireMenu 에 적용
데이터 권한   = 부서 권한 그대로                    — 추가 허용은 화면만 연다
통합관리자    = 전 화면(조회 없이)                  — 그대로
비활성 화면   = 뷰가 제외 → 어디에도 안 나온다
```

권한은 요청마다 다시 읽는다(`JwtAuthFilter` → `AuthorizationService.loadPrincipal`). 그래서 추가 허용을 넣고 빼면 **발급된 토큰에도 즉시** 반영된다(라이브 테스트 "same token loses revoked grant immediately" 통과).

## 2. 바뀐 계약

### 2-1. `GET /system/users` · `GET /system/users/pending` — 행에 `extraMenuIds`

각 행에 `extraMenuIds: string[]` 가 붙는다(없으면 `[]`). 사용 중인 화면만, 메뉴 정렬 순. 부서 권한으로 이미 열린 화면과 겹칠 수 있다(UNION 이라 무해 — DB 계약 §6).

`keyword` 는 **전 열 검색**: 사번 · 이름 · 부서명 · 부서 약칭 · 직급 코드/이름 · 상태 코드/이름 · 마지막 접속(`YYYY-MM-DD HH:MI`).
`page`/`size` 기존과 같다(기본 1/10). `users/pending` 에 `keyword` 가 새로 붙었다.

### 2-2. `POST /system/users` · `PUT /system/users/{empNo}` — 본문 `extraMenuIds`(선택)

| 값 | 뜻 |
| :--- | :--- |
| 미전달(`null`) | 추가 허용을 **그대로 둔다** |
| `[]` | 전부 회수 |
| `["sys-account", …]` | 이 목록으로 **치환**(없던 것 부여, 빠진 것 회수). 순서·중복 무관 |

- 계정 정보와 **한 트랜잭션**이다. 없는·사용 중지 화면 ID 가 하나라도 있으면 400 `E-VALID-001`(`field: extraMenuIds`)이고 이름·부서·상태도 바뀌지 않는다(저장 전에 검증 + 트랜잭션 롤백).
- 부여는 열람만(`can_write=false`). 쓰기 권한 부여는 이 화면 범위 밖이다(DB 계약 §4 — 필요해질 때 함께 설계).
- 응답에 저장 뒤 `extraMenuIds` 가 들어온다. `POST` → `{empNo, extraMenuIds}`, `PUT` → `{success, empNo, extraMenuIds}`.
- 권한: 기존과 같이 `sys-account` 메뉴 권한자(관리자). 자기 계정에 대한 부여를 막지는 않는다(DB 계약 §8-2 — 정책이 정해지면 한 줄이다).
- 부서 이동(`PUT /users/{empNo}/dept` · `PUT /users/{empNo}` 의 `deptId`) 때 추가 허용은 **남긴다**(DB 계약 §8-1). 화면에서 "함께 회수할까요" 를 물을지는 발주자 확인.
- 계정 삭제는 FK CASCADE 로 추가 허용도 지워진다.

### 2-3. 감사 이력

바뀐 것이 있을 때만 `ax.tb_sys_perm_log` 에 `act_cd = 'USER_MENU_PERM'`, `target_kind_cd = 'USER'`, `target_user_id = 사번`,
`detail = "계정 추가 화면 부여 [a, b] · 회수 [c]"` 로 남긴다. 계정 등록·수정 자체의 `ACCOUNT` 이력과 별도 행이다.
`ax.tb_sys_audit_log` 에도 `PERM_CHANGE`(`menu_id = sys-account`) 한 건을 남긴다.

### 2-4. `GET /system/depts` — `keyword` · `page` · `size`

| 호출 | 응답 |
| :--- | :--- |
| 파라미터 없음 또는 `size=0` | 기존과 같이 `data.items` 전량, **meta 없음** (선택지 호출 호환) |
| `page`/`size` 전달 | `data.items` + `meta{page,size,total,totalPages}` |

`keyword` 는 부서명 · 약칭 · 설명. 두 방식 모두에 적용된다.

### 2-5. `GET /system/perm-logs` — `keyword`

기존 `from`/`to`(기본 최근 90일) · `target` · `actType` 에 `keyword` 가 추가됐다. 전 열 검색: 대상(`target_nm`) · 구분 코드/이름(`act_cd`, `SYS_PERM_ACT` 코드명) · 내용(`detail`) ·
대상 사번 · 수행자 사번/이름/부서. 행 필드는 그대로(`ts` `target` `targetKind` `actType` `detail` `by` `byEmpNo` `byDept`).

### 2-6. `GET /system/menu-perms` — 조회 권한 확장

조회는 `sys-menu` **또는** `sys-account` 권한이면 된다(계정별 추가 화면의 선택지 = `screens[{id,name,group,sub}]`, `matrix{deptId:[menuId]}`).
`PUT /menu-perms` · `/menu-perms/group` · `/menu-perms/copy` 는 `sys-menu` 그대로(라이브 테스트: 계정 관리자만 가진 사용자의 PUT → 403 확인).

### 2-7. `GET /system/accounts/summary` — `userCnt.pending`

`userCnt` 에 `pending`(승인 대기 전체 건수)이 추가됐다. 검색 결과의 `meta.total` 과 분리되어 검색 중에도 상단 전체 건수가 바뀌지 않는다.
`{"userCnt":{"active":6,"suspended":0,"pending":0},"deptCnt":6,"switchableCnt":…}`

### 2-8. `GET /menus`(좌측 메뉴 트리) — 유효 권한 기준

`AuthRepository.findMenuTree` 가 부서만 보던 것을 사번 기준 뷰로 바꿨다. 계정에만 열어 준 화면이 메뉴에 나오고, 회수하면 바로 빠진다(실측 확인).

## 3. 바뀐 파일

| 파일 | 변경 |
| :--- | :--- |
| `repository/AuthRepository.kt` | `findEffectiveMenuPermissions(userId)`(뷰) 추가 · `findMenuTree(userId, superAdmin)` 뷰 기준 |
| `service/AuthorizationService.kt` | `loadPrincipal` · `loadPrincipalForInspection` 의 화면 권한을 사번 기준 유효 권한으로 |
| `service/AuthService.kt` | `getMenuTree` 가 사번을 넘긴다 |
| `model/request/SystemRequests.kt` | `UserSaveRequest.extraMenuIds: List<String>?` |
| `repository/SystemUserRepository.kt` | 계정 키워드 전 열 · `findDepts(keyword, limit, offset)` · `countDepts` · 추가 허용 CRUD(`findUserGrants` `findUserGrantIds` `findActiveMenuIds` `insertUserGrants` `deleteUserGrants`) · 요약 `pending_cnt` |
| `service/SystemUserService.kt` | 목록 `extraMenuIds` 부착 · 등록/수정 원자 저장(`validateExtraMenus` → `applyExtraMenus`) · `getDepts(keyword,page,size)` · 승인 대기 `keyword` · `getMenuPermMatrix` 조회 권한 확장 · `planGrantChanges` |
| `repository/AuditLogRepository.kt` · `service/AuditLogService.kt` | 변경 이력 `keyword` 전 열 검색 |
| `controller/SystemUserController.kt` | `depts` `pending` `perm-logs` 의 `keyword`/`page`/`size` |
| `docs/REQUEST_BODY_CONTRACT.md` | `UserSaveRequest` 허용 키에 `extraMenuIds` |
| 테스트 `AccountMenuGrantTest`(4) | 치환 계획 · 검증(400) · 적용/이력 · DTO 계약 |

DB 마이그레이션·스키마 문서는 DB 담당이 했다(V30, `Postgresql 스키마/` 패치·설치본·변경내역). Kotlin 은 여기서만 바뀌었다.

## 4. 검증 (로컬 8080, 2026-09-13)

- `./gradlew test` 전체 통과(신규 4건 포함).
- WEB 라이브 테스트 `node tests/system/account-grants-live.cjs` → **PASS**: 임시 부서·계정 생성 → 부서 권한 `dash-ai` 만 → `/system/users` 403 → `PUT extraMenuIds:['sys-account']` → 같은 토큰으로 `/auth/me` 에 `sys-account` 포함 · 데이터 권한 불변 · `/system/users` 200 · `/system/menu-perms` 조회 200 · `PUT menu-perms` 403 → 이름만 수정해도 추가 허용 유지 → 없는 화면 ID 로 저장 시 ≥400 이고 이름도 안 바뀜 → `[]` 회수 후 같은 토큰으로 `/system/users` 403 → `users`·`depts`·`perm-logs` 세 표 `keyword`+`page`+`size` 검색/페이징 → 임시 계정·부서 삭제.
- 직접 확인: `depts` 파라미터 없음 → meta 없음 6건 / `keyword=팀&page=1&size=2` → meta total 4 / `size=0&keyword=전산` → meta 없음 1건. `users?keyword=품질`(부서명) 1건 · `keyword=사용`(상태명) 6건. `perm-logs?keyword=계정 추가 화면` 2건(`USER_MENU_PERM`). `accounts/summary.userCnt.pending` 0. 임시 계정에 `sys-account` 부여 → `GET /menus` 에 나타나고 회수 후 빠짐.
- 실제 계정 권한은 건드리지 않았다. 검증은 `WG…` 임시 계정으로만 했고 전부 삭제했다(`tb_sys_user_menu_grant` 0행). 감사 이력(`USER_MENU_PERM`)에는 임시 계정 행이 남아 있다.

## 5. 남은 결정 (DB 계약 §8)

1. 부서 이동 시 추가 허용 동반 회수 여부 — 지금은 남긴다.
2. 자기 계정 부여 금지 여부 — 지금은 허용(관리자 권한자만 호출 가능).
3. 하위 화면(`is_sub_page`) 단독 부여 안내 — 부서 권한 화면과 같은 규칙을 쓸지.
4. 쓰기 권한(`can_write`) 부여 UI — `UserPrincipal` 이 쓰기 권한을 담지 않아 지금은 열람만 부여한다.
