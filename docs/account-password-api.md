# 계정 편집 — 관리자 비밀번호 변경 · 표 전량 조회(size=0) API 계약 (2026-09-14)

`/system/account` 편집 화면에서 **관리자만** 다른 계정의 비밀번호를 바꾸는 기능과, 세 카드(계정·부서·변경 이력)가 열 필터를 전체 결과에 걸기 위한 `size=0` 전량 조회 지원이다.
계정별 추가 허용 메뉴 계약(`docs/account-menu-grants-api.md`) 위에 얹었다.

## 1. `PUT /system/users/{empNo}` — `password`(선택)

| 값 | 동작 |
| :--- | :--- |
| 미전달 · `""` · 공백만 | 비밀번호 **그대로**. 응답 `passwordChanged: false` |
| 값 | 관리자 판정 → 정책 검사 → 해시 저장. 응답 `passwordChanged: true` |

- **관리자 판정** — 통합관리자(`superAdmin`) **또는 소속 부서가 기본으로** 계정 관리 화면(`sys-account`) 권한을 가진 사람(부서 메뉴 권한 표 `tb_sys_dept_menu_perm`). 계정 추가 허용(`extraMenuIds`)으로만 `sys-account` 를 받은 사용자는 **불가** → 403 `E-AUTH-002`
  (메시지 "비밀번호 변경은 통합관리자 또는 계정 관리 권한을 기본으로 가진 부서의 관리자만 할 수 있습니다."). 그 사용자가 같은 요청에서 이름만 바꾸는 것은 된다.
- **정책** — 기존 `PasswordEncoderService.validatePolicy`(8자 이상 · 공백 없음 · 영문/숫자/특수 2종 이상)와 사번 포함 금지. 위반은 400 `E-VALID-001`(`field: password`).
- **원자성** — 관리자 판정·정책 검사를 저장 전에 끝내고 전체가 한 트랜잭션이다. 실패하면 같은 요청의 이름·부서·상태·`extraMenuIds` 도 바뀌지 않는다(실측: `{"name":"SHOULD_NOT_APPLY","password":"123"}` → 400, 이름 유지).
- **저장** — 기존 해시 방식(PBKDF2-SHA512)으로만 저장. `pwd_upd_at = now()`, `login_fail_cnt = 0`(실패 잠금 해제). `last_login_at` 은 건드리지 않는다(로그인이 아니다). 계정 상태는 바꾸지 않는다 — 정지 계정을 살리는 것은 상태 변경 API 의 일이다.
- **감사** — `ax.tb_sys_perm_log` `ACCOUNT` / `"비밀번호 변경(관리자 {사번})"`, `ax.tb_sys_audit_log` `AUTO_GEN` / `"비밀번호 변경 [{empNo}]"`. **비밀번호 값은 어디에도 기록하지 않는다**(실측: 이력 표에 값 검색 0건). 기존 `계정 수정` 이력의 detail 에도 password 는 없다.
- **세션** — 기존 정책과 같다. 토큰은 서버 저장 없는 JWT 라 발급된 액세스 토큰을 즉시 무효화하지 않으며, 본인 비밀번호 변경(`/auth/password`)·재설정도 같은 방식이다. 새 로그인은 새 비밀번호로만 된다(실측: 옛 비밀번호 로그인 `E-AUTH-001`).
- `POST /system/users` 의 `password`(초기 비밀번호) 동작은 그대로다.

## 2. `GET /system/accounts/summary` — `canChangePassword`

```json
{"userCnt":{"active":6,"suspended":0,"pending":0},"deptCnt":6,"switchableCnt":…,
 "canChangePassword":true,
 "currentUser":{"empNo":"10000","name":"관리자","dept":"통합관리자","canChangePassword":true}}
```
현재 사용자 기준의 관리자 판정 결과다(위 규칙과 같은 함수). 화면은 이 값으로 비밀번호 필드를 보이거나 감춘다.
실측: 통합관리자 `true` · 전산팀(부서 기본 sys-account) `true` · 추가 허용으로만 sys-account 를 가진 품질팀 임시 계정 `false`.

## 3. `size=0` 전량 조회 — 세 카드

| 엔드포인트 | `size=0` | `meta` |
| :--- | :--- | :--- |
| `GET /system/users` · `/users/pending` | 전량 | `{page:1, size:total, total, totalPages:1}` |
| `GET /system/depts` | 전량(기존과 같이 **meta 없음**, `data.items`) | — (`page`/`size>0` 일 때만 meta) |
| `GET /system/perm-logs` | 전량(기본 기간 최근 90일 안) | `{page:1, size:total, total, totalPages:1}` |

`keyword` 는 전량 조회에도 그대로 걸린다. `size` 를 아예 넘기지 않으면 기존 기본 쪽 크기다. 실측: users 8건 · depts 6건 · perm-logs 518건 전량.

## 4. 바뀐 파일

| 파일 | 변경 |
| :--- | :--- |
| `service/SystemUserService.kt` | `canChangePassword(principal)` · `preparePasswordChange(raw, empNo, principal)` · `updateUser` 에 비밀번호 저장·이력 · `getAccountSummary` 에 `canChangePassword` · `getUsers`/`getPendingUsers` `size=0` 전량 |
| `repository/SystemUserRepository.kt` | `deptHasMenuPerm(deptId, menuId)` · `updatePasswordHash(empNo, hash, actor)` · `findUsers(limit: Int?)` |
| `repository/AuditLogRepository.kt` · `service/AuditLogService.kt` | `findPermLogs(limit: Int?)` · `getPermLogs` `size=0` 전량 |
| `model/request/SystemRequests.kt` | `UserSaveRequest.password` 주석(수정 시 뜻) — 허용 키는 그대로 |
| 테스트 `AccountMenuGrantTest` +2 | 관리자 판정(통합관리자·부서 기본·추가 허용만은 불가) · 비밀번호 준비(미변경·403·정책·사번·해시) |

## 5. 검증 (로컬 8080, 2026-09-14 — 임시 계정 `WT…`·`WA…` 만 사용, 전부 삭제)

- 전체 `./gradlew test` 통과.
- 품질팀 임시 계정 `WA`(extraMenuIds 로만 sys-account) → `PUT /users/WT {password}` **403**, 옛 비밀번호 로그인 유지 · 이름만 수정은 200 · `summary.canChangePassword=false`.
- 통합관리자 → `{"name":…,"password":"123"}` 400 이고 이름 미변경 · 사번 포함 400 · 공백 비밀번호 200 `passwordChanged:false` · `"New!Pass99"` 200 `passwordChanged:true` → 새 비밀번호 로그인 성공, 옛 비밀번호 `E-AUTH-001`, 이름만 다시 수정해도 새 비밀번호 유지.
- 이력 표에 비밀번호 문자열 0건. 기존 실제 계정의 비밀번호는 바꾸지 않았다.
