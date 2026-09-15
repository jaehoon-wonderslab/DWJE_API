# 계정별 추가 허용 메뉴 — DB 계약 (2026-09-13)

`/system/account` 개선에 필요한 **계정 단위 화면 권한 추가**의 DB 쪽 계약이다.
DB 작업(마이그레이션·스키마·문서)은 완료·로컬 적용·검증까지 끝났다. Kotlin 은 **건드리지 않았다** — 아래를 보고 API 세션이 구현하면 된다.

> **상태 (2026-09-16)** — API 구현이 들어왔다(`ef40d82`). `AuthRepository.findMenuPermissions` 가 4절의 뷰 질의를
> 그대로 쓰고, `SystemUserRepository` 가 5절의 조회·upsert·회수를 구현했다. 이 문서는 이제 계약서가 아니라
> **판단 근거 기록**으로 읽으면 된다.
>
> 같은 날 확인된 것 — `V30__user_menu_grant.sql` 이 저장소에 커밋되지 않은 채 남아 있었다(설치본
> `ext_api_migrations.sql` 과 운영 패치에는 들어 있었다). 새로 클론해 `setup_local_db.sh` 를 돌리면 표·뷰가
> 생기지 않아 **로그인 이후 모든 요청이 실패**한다(인증 경로가 뷰를 읽는다). 2026-09-16 에 마이그레이션과
> `rollback/V30__down.sql` 을 함께 커밋해 메웠다.

| 구분 | 파일 |
| :--- | :--- |
| 마이그레이션 | `src/main/resources/db/V30__user_menu_grant.sql` (로컬 `dwjedb` 적용 완료) |
| 운영 DB 패치 | `../Postgresql 스키마/patch_20260913_user_menu_grant.sql` (DDL 동일, 멱등) |
| 설치본 | `../Postgresql 스키마/ext_api_migrations.sql` 재생성 (V2~V30) |
| 배경·판단 | `../Postgresql 스키마/변경내역_20260913_계정_추가_메뉴_권한.md` |

---

## 1. 한 줄 규칙

```
유효 화면 권한 = 부서 권한(tb_sys_dept_menu_perm.can_read) ∪ 계정 추가 허용(tb_sys_user_menu_grant)
유효 쓰기 권한 = 부서 can_write OR 계정 추가 허용 can_write
차단           = 두 곳 모두 행이 없을 때
통합관리자     = is_super_admin 이면 조회 없이 전 화면 (지금 코드 그대로)
```

**추가 허용 전용이다.** 이 표의 행으로 화면을 닫는 길은 없다. 그래서 `can_read` 컬럼이 없다 — `can_read=false` 행이 존재할 수 있으면 그것이 곧 차단 override 이고, 메뉴 권한 화면에서 열어 준 것이 계정 표에서 조용히 막힌다. 열람 허용은 **행의 존재** 로만 표현한다.

## 2. 필드 이름 — `empNo` ↔ `user_id`

| API/WEB (JSON) | DB 컬럼 | 비고 |
| :--- | :--- | :--- |
| `empNo` | `ax.tb_sys_user_menu_grant.user_id` (`common.d_user_id`, varchar 30) | **API 응답·요청 필드명은 요청대로 `empNo` 를 쓴다.** DB 컬럼만 `user_id` 다 |
| `screenId` | `menu_id` (varchar 30) | `ax.tb_sys_menu.menu_id`. 부서 권한 API(`PUT /system/menu-perms`)가 이미 쓰는 매핑과 같다 |
| `canWrite` | `can_write` (boolean) | |
| `reason` | `grant_reason` (varchar 200, NULL 가능) | 부여 사유. 선택 |
| `grantedBy` / `grantedAt` | `ins_user` / `ins_date` | |

컬럼을 `emp_no` 로 하지 않은 이유: DB 안에서 사번은 `common.d_user_id` 도메인이고 `tb_sys_user.user_id` · 전 테이블 `ins_user/upd_user` 와 같은 값이다. 앞선 같은 결정 두 건(V23 `tb_sys_user_favorite`, V24 `tb_rpt_usage` — 웹 원안 `emp_no` → `user_id`)과 맞췄다. `SystemUserRepository` 가 이미 `"empNo" to rs.getString("user_id")` 로 쓰는 그 방식 그대로다.

## 3. 테이블 · 뷰

```sql
ax.tb_sys_user_menu_grant (
    user_id      common.d_user_id NOT NULL,   -- empNo. FK tb_sys_user ON DELETE CASCADE
    menu_id      varchar(30)      NOT NULL,   -- screenId. FK tb_sys_menu ON DELETE CASCADE
    can_write    boolean          NOT NULL DEFAULT false,
    grant_reason varchar(200),
    ins_date timestamptz NOT NULL DEFAULT now(), ins_user common.d_user_id,
    upd_date timestamptz NOT NULL DEFAULT now(), upd_user common.d_user_id,
    PRIMARY KEY (user_id, menu_id)
)
ix_sys_user_menu_grant_menu (menu_id)

ax.vw_sys_user_menu_perm (user_id, menu_id, can_write, from_dept, from_grant)
```

뷰가 UNION 규칙을 담고 있다. `use_flg='N'` 으로 내린 화면은 뷰에서 이미 제외되며, 통합관리자 전 화면 허용은 **뷰에 없다**(API 가 조회 없이 통과시키는 지금 판정을 그대로 둔다).
`user_id` 조건은 두 갈래 모두로 밀려 들어간다 — 로컬 EXPLAIN 기준 실행 0.2 ms 로, 매 요청 호출해도 된다.

## 4. 권한 판정 — 바꿔야 할 곳

`AuthRepository.findMenuPermissions(deptId: Int)` 는 부서만 본다(`AuthorizationService.loadPrincipal` · `loadPrincipalForInspection` 두 곳에서 호출). 이것을 **사번 기준** 으로 바꾸면 끝이다.

```sql
-- 대체 질의 (기존 findMenuPermissions 의 부서 질의를 이 한 문장으로)
SELECT menu_id
  FROM ax.vw_sys_user_menu_perm
 WHERE user_id = :userId
```

- `superAdmin` 분기는 지금처럼 조회 자체를 건너뛴다(뷰에 전 화면 허용이 없으므로 반드시 유지).
- `UserPrincipal.menuPerms` 의 타입(`Set<String>`)은 그대로다. 화면 진입 판정 로직은 손댈 것이 없다.
- 토큰 발급 뒤 권한 변경을 즉시 반영하려고 매 요청 조회하는 지금 구조도 그대로 둔다.
- 쓰기 권한까지 쓰려면 `SELECT menu_id, can_write FROM ax.vw_sys_user_menu_perm WHERE user_id = :userId` — 다만 현재 `UserPrincipal` 은 쓰기 권한을 담지 않으므로, 필요해질 때 함께 설계한다.

뷰를 쓰지 않고 직접 쓰려면 아래와 같다(뷰와 같은 결과, 규칙이 두 곳으로 갈라지므로 권장하지 않는다).

```sql
SELECT p.menu_id FROM (
    SELECT dp.menu_id FROM ax.tb_sys_user u
      JOIN ax.tb_sys_dept_menu_perm dp ON dp.dept_id = u.dept_id AND dp.can_read
     WHERE u.user_id = :userId
    UNION
    SELECT g.menu_id FROM ax.tb_sys_user_menu_grant g WHERE g.user_id = :userId
) p JOIN ax.tb_sys_menu m ON m.menu_id = p.menu_id AND m.use_flg = 'Y'
```

## 5. 관리 질의 (계정 관리 화면용)

**조회 — 이 계정의 추가 허용 목록**
```sql
SELECT g.menu_id, m.menu_nm, g.can_write, g.grant_reason, g.ins_user, g.ins_date
  FROM ax.tb_sys_user_menu_grant g
  JOIN ax.tb_sys_menu m ON m.menu_id = g.menu_id
 WHERE g.user_id = :empNo AND m.use_flg = 'Y'
 ORDER BY m.group_id, m.sort_seq
```

**조회 — 화면 목록에 출처 표시** (부서로 열림 / 계정으로 열림 / 닫힘)
```sql
SELECT menu_id, can_write, from_dept, from_grant
  FROM ax.vw_sys_user_menu_perm WHERE user_id = :empNo
```

**부여 (멱등 upsert)**
```sql
INSERT INTO ax.tb_sys_user_menu_grant (user_id, menu_id, can_write, grant_reason, ins_user, upd_user)
VALUES (:empNo, :menuId, :canWrite, :reason, :actor, :actor)
ON CONFLICT (user_id, menu_id) DO UPDATE
   SET can_write = EXCLUDED.can_write,
       grant_reason = EXCLUDED.grant_reason,
       upd_user = EXCLUDED.upd_user,
       upd_date = now()
```

**회수** — `DELETE FROM ax.tb_sys_user_menu_grant WHERE user_id = :empNo AND menu_id = :menuId`
행을 남긴 채 끄는 방법은 없다. 회수 = 삭제다.

**변경 이력** — `ax.tb_sys_perm_log` 에 `act_cd = 'USER_MENU_PERM'`(이번에 추가한 공통코드), `target_kind_cd`/`target_user_id` 는 대상 계정, `detail` 에 화면명·부여/회수를 적는다. 부서 단위 변경(`MENU_PERM`)과 섞지 말 것 — 이력 화면에서 누구의 권한이 바뀐 것인지 읽히지 않는다.

## 6. 정리(삭제) 규칙

| 상황 | 처리 | API 가 할 일 |
| :--- | :--- | :--- |
| 계정 삭제 | FK CASCADE 로 자동 삭제 | 없음 |
| 화면 물리 삭제 | FK CASCADE 로 자동 삭제 | 없음 |
| 화면 `use_flg='N'` | 행은 남고 조회에서 제외 | 뷰를 쓰면 자동. 직접 질의하면 `m.use_flg='Y'` 필수 |
| 부서 권한이 같은 화면을 나중에 포함 | 무해(UNION). 중복일 뿐 | 화면에서 "부서 권한으로 이미 열림" 으로 보여 주면 관리자가 지울지 판단한다. 아래 질의로 찾는다 |

```sql
-- 부서 권한과 겹쳐 의미가 없어진 추가 허용 (쓰기도 부서가 이미 주는 경우만)
SELECT g.user_id, g.menu_id
  FROM ax.tb_sys_user_menu_grant g
  JOIN ax.tb_sys_user u  ON u.user_id = g.user_id
  JOIN ax.tb_sys_dept_menu_perm dp ON dp.dept_id = u.dept_id AND dp.menu_id = g.menu_id
 WHERE dp.can_read AND (dp.can_write OR NOT g.can_write)
```

**자동으로 지우지 말 것.** 부서 권한이 다시 닫히면 그 계정만은 열려 있어야 하는 경우가 있다.

## 7. 검증된 것 (로컬 `dwjedb`)

- V30 2회 적용 — 오류 0, 재실행은 `already exists, skipping` 만.
- FK 거부(없는 계정 / 없는 화면), PK 중복 거부, 계정 삭제 CASCADE, 화면 삭제 CASCADE, 뷰의 UNION·`use_flg='N'` 제외·`can_write` OR — 전부 확인. **트랜잭션 안에서 시험하고 롤백** 해 실제 계정·화면은 그대로다.
- 빈 DB 설치본 + 패치와 `dwjedb` 를 전수 비교 — 3,212 항목 중 차이 1건(`dash-kpi` `use_flg`, 전부터 있던 미해결 건. 이번 변경과 무관).
- **실제 사용자에게 부여한 권한 0건.** `SELECT count(*) FROM ax.tb_sys_user_menu_grant` = 0.

## 8. API 쪽에서 결정해야 할 것

1. **부서 이동 시 추가 허용 처리** — DB 는 남긴다(자동 삭제는 되돌릴 수 없다). 계정 관리 화면에서 부서를 바꿀 때 "추가 허용 n건도 함께 회수할까요" 를 묻는 편이 안전하다. 발주자·WEB 확인 필요.
2. **자기 자신에게 부여 금지 여부** — DB 는 막지 않는다. 통합관리자만 이 API 를 호출할 수 있게 할지, 자기 계정 대상은 거부할지는 API 정책.
3. **상위/하위 화면 동반 부여** — `tb_sys_menu.parent_menu_id` 가 있는 하위 화면(`is_sub_page`)만 열면 진입 경로가 없다. 부서 권한 화면이 쓰는 안내와 같은 규칙을 계정 화면에도 적용할지.
4. **정지 계정(SUSPENDED)** 에 대한 부여는 DB 가 막지 않는다(정지 계정도 관리 대상이다). 로그인 단계에서 어차피 차단된다.
