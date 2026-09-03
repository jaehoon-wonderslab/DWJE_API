# 인증 화면 개발용 API 명세 (회원가입 · 로그인 · 비밀번호 찾기)

덕우전자 AX 시스템 백엔드가 제공하는 인증 API 규격이다. React 화면 3종 개발에 필요한 내용만 담았다.

- **Base URL** : `http://localhost:8080/api/v1`
- **API 문서** : http://localhost:8080/swagger-ui.html
- 아래 인증 API 는 **모두 토큰 없이 호출**한다. (로그인 전에 쓰이므로)

---

## 1. 공통 응답 포맷

모든 API 가 같은 껍데기를 쓴다.

```jsonc
// 성공
{ "success": true, "code": "SUCCESS", "message": "...", "data": { }, "timestamp": 1788… }

// 실패
{ "success": false, "code": "E-VALID-001", "message": "비밀번호는 8자 이상이어야 합니다.",
  "error": { "code": "E-VALID-001", "message": "…", "field": "password" }, "timestamp": 1788… }
```

`error.field` 가 오면 **해당 입력란 아래에 메시지를 붙여** 표시한다. 없으면 폼 상단에 표시한다.

### 에러 코드

| 코드 | HTTP | 화면 처리 |
|---|---|---|
| `E-VALID-001` | 400 | 입력값 오류. `error.field` 기준으로 필드 아래 표시 |
| `E-VALID-002` | 400 | 중복(사번·이메일). 해당 필드에 표시 |
| `E-RULE-001` | 409 | 업무 규칙 위반(만료·재발송 대기·시도 초과). 폼 상단 표시 |
| `E-AUTH-001` | 401 | 로그인 실패·미인증 |
| `E-NOTFOUND` | 404 | 대상 없음 |
| `E-SERVER` | 500 | 시스템 오류. "잠시 후 다시 시도" 안내 |

---

## 2. 로그인 화면

### 2-1. 로그인
```
POST /auth/login
{ "loginId": "10004", "password": "Dwje!2026" }
```
```jsonc
// 200
{ "success": true, "data": {
    "accessToken": "eyJhbGciOi…",     // 이후 모든 요청에 Authorization: Bearer {accessToken}
    "refreshToken": "eyJhbGciOi…",
    "expiresIn": 3600,                 // 초
    "user": { "empNo": "10004", "name": "최전산", "dept": "전산팀",
              "pos": "SENIOR", "deptId": 5, "superAdmin": false } } }
```

**실패 응답 (모두 401 `E-AUTH-001`)** — `message` 를 그대로 노출한다.

| 상황 | message |
|---|---|
| 사번·비밀번호 불일치 | `사번 또는 비밀번호가 올바르지 않습니다.` |
| 승인 대기 계정 | `가입 승인 대기 중인 계정입니다. 전산팀 승인 후 로그인할 수 있습니다.` |
| 정지 계정 | `사용이 정지된 계정입니다. 관리자에게 문의하세요.` |
| 연속 실패 5회 | `비밀번호를 5회 잘못 입력하여 계정이 정지되었습니다.` |

> 사번이 없는 경우와 비밀번호가 틀린 경우의 메시지가 **의도적으로 동일**하다. 화면에서 구분해 보여주면 안 된다.

### 2-2. 토큰 갱신
```
POST /auth/refresh
{ "refreshToken": "…" }
→ data: { "accessToken": "…", "expiresIn": 3600 }
```

### 2-3. 내 정보·권한 (로그인 후 최초 1회)
```
GET /auth/me            Authorization: Bearer {accessToken}
→ data: { user, dept, menuPerms: ["dash-ai", …], dataPerms: ["qty", …],
          blindFields: ["price", …], servingModelVer, impersonated }
```
`menuPerms` 로 사이드바를 구성하고, `blindFields` 로 '비공개' 배지를 판단한다.

### 2-4. 로그아웃
```
POST /auth/logout       Authorization: Bearer {accessToken}
```

---

## 3. 회원가입 화면 (이메일 인증 포함)

### 흐름
```
[1] 사번 중복 확인   GET  /auth/signup/check-emp-no?empNo=30001
[2] 부서 목록        GET  /auth/signup/depts
[3] 이메일 인증 발송 POST /auth/email/send-code       { email, purpose: "SIGNUP" }
[4] 인증 코드 검증   POST /auth/email/verify-code     { email, purpose: "SIGNUP", code }
                     → verificationToken 획득
[5] 가입 신청        POST /auth/signup                { …, verificationToken }
                     → 상태 PENDING (관리자 승인 후 로그인 가능)
```

### 3-1. 사번 중복 확인
```
GET /auth/signup/check-emp-no?empNo=30001
→ data: { "empNo": "30001", "available": true, "message": "사용할 수 있는 사번입니다." }
```
사번 형식: 영문·숫자·하이픈·밑줄 **4~30자**.

### 3-2. 가입 가능 부서 목록
```
GET /auth/signup/depts
→ data: { "depts": [ { "deptId": 2, "deptNm": "품질보증팀", "abbr": "품보", "desc": "…" }, … ] }
```
통합관리자 부서는 목록에 없다(스스로 신청 불가).

### 3-3. 이메일 인증 코드 발송
```
POST /auth/email/send-code
{ "email": "hong@dwje.co.kr", "purpose": "SIGNUP" }
```
```jsonc
// 200
{ "data": { "email": "ho**@dwje.co.kr",        // 마스킹된 주소 (그대로 표시)
            "purpose": "SIGNUP",
            "expiresAt": "2026-08-31 23:09:32", // 만료 시각 (KST)
            "expireMinutes": 5,
            "resendAvailableInSec": 60 } }      // 재발송 버튼 비활성 시간
```
**실패**
| 상황 | 코드 | message |
|---|---|---|
| 재발송 대기 | `E-RULE-001` | `인증 코드를 다시 보내려면 47초 후에 시도해 주세요.` |
| 일일 상한(10회) | `E-RULE-001` | `오늘 인증 코드 발송 횟수를 초과했습니다…` |
| 이메일 형식 오류 | `E-VALID-001` | `이메일 형식이 올바르지 않습니다.` (field=`email`) |

> 화면에서는 `expireMinutes` 로 **남은 시간 카운트다운**, `resendAvailableInSec` 로 **재발송 버튼 쿨다운**을 구현한다.

### 3-4. 인증 코드 검증
```
POST /auth/email/verify-code
{ "email": "hong@dwje.co.kr", "purpose": "SIGNUP", "code": "382831" }
→ data: { "verificationToken": "hZ8k…", "purpose": "SIGNUP", "expireMinutes": 10 }
```
**실패**
| 상황 | 코드 | message |
|---|---|---|
| 코드 불일치 | `E-VALID-001` | `인증 코드가 올바르지 않습니다. (남은 시도 4회)` (field=`code`) |
| 시도 5회 초과 | `E-RULE-001` | `인증 시도 횟수를 초과했습니다. 인증 코드를 다시 요청해 주세요.` |
| 코드 만료 | `E-RULE-001` | `인증 코드가 만료되었습니다. 다시 요청해 주세요.` |

`verificationToken` 은 **10분간 유효한 1회용** 토큰이다. 가입 요청에 그대로 실어 보낸다.

### 3-5. 가입 신청
```
POST /auth/signup
{
  "empNo": "30001", "name": "신입사원", "deptId": 3, "pos": "STAFF",
  "email": "hong@dwje.co.kr",
  "verificationToken": "hZ8k…",
  "password": "Test!2026", "passwordConfirm": "Test!2026"
}
```
```jsonc
// 200
{ "data": { "empNo": "30001", "email": "ho**@dwje.co.kr", "state": "PENDING",
            "message": "가입 신청이 접수되었습니다. 전산팀 승인 후 로그인할 수 있습니다." } }
```
**실패**
| 상황 | 코드 | field |
|---|---|---|
| 비밀번호 확인 불일치 | `E-VALID-001` | `passwordConfirm` |
| 비밀번호 정책 위반 | `E-VALID-001` | `password` |
| 비밀번호에 사번 포함 | `E-VALID-001` | `password` |
| 사번 중복 | `E-VALID-002` | `empNo` |
| 이메일 중복 | `E-VALID-002` | `email` |
| 부서 미선택·미존재 | `E-VALID-001` | `deptId` |
| 토큰 만료·재사용 | `E-RULE-001` | — |
| 인증 이메일과 입력 이메일 불일치 | `E-RULE-001` | — |

가입 완료 후에는 **"승인 대기" 안내 화면**으로 보낸다. 바로 로그인시키면 안 된다.

### 비밀번호 정책 (화면에서도 동일하게 안내)
- 8자 이상
- 공백 불가
- **영문 · 숫자 · 특수문자 중 2종 이상** 조합
- 사번 포함 불가

---

## 4. 비밀번호 찾기 화면 (이메일 인증 포함)

### 흐름
```
[1] 본인 확인 + 발송  POST /auth/password/forgot   { empNo, email }
[2] 인증 코드 검증    POST /auth/email/verify-code { email, purpose: "PASSWORD_RESET", code }
                      → verificationToken
[3] 새 비밀번호 설정  POST /auth/password/reset    { verificationToken, newPassword, newPasswordConfirm }
[4] 로그인 화면으로
```

### 4-1. 인증 코드 발송
```
POST /auth/password/forgot
{ "empNo": "10001", "email": "10001@dwje.co.kr" }
```
```jsonc
// 200 — 계정이 없어도 동일한 응답이 온다
{ "data": { "email": "10***@dwje.co.kr", "expireMinutes": 5,
            "message": "입력하신 정보와 일치하는 계정이 있으면 인증 코드를 보냈습니다. 메일함을 확인해 주세요." } }
```

> **중요** — 사번·이메일이 일치하지 않아도 **성공 응답**이 온다. 계정 존재 여부를 알려 주지 않기 위한 의도적 설계다.
> 화면은 언제나 "메일을 확인하세요" 로 안내하고, 다음 단계(코드 입력)로 진행시킨다.

### 4-2. 인증 코드 검증
3-4 와 동일하되 `purpose` 를 **`PASSWORD_RESET`** 으로 보낸다. 목적이 다르면 토큰이 통하지 않는다.

### 4-3. 비밀번호 재설정
```
POST /auth/password/reset
{ "verificationToken": "9Fk2…", "newPassword": "Reset!2026", "newPasswordConfirm": "Reset!2026" }
→ data: { "success": true, "empNo": "10001",
          "message": "비밀번호가 재설정되었습니다. 새 비밀번호로 로그인해 주세요." }
```
**실패**
| 상황 | 코드 | field |
|---|---|---|
| 확인 불일치 | `E-VALID-001` | `newPasswordConfirm` |
| 정책 위반 | `E-VALID-001` | `newPassword` |
| 이전 비밀번호와 동일 | `E-VALID-001` | `newPassword` |
| 토큰 만료·재사용 | `E-RULE-001` | — |

연속 실패로 잠긴 계정은 재설정 시 **자동 해제**된다.

---

## 5. 로컬 개발 환경

### API 서버 기동
```bash
cd "/Users/jaehun/Desktop/wonderslab/덕우전자/004. 개발/API"
./src/main/resources/db/local/setup_local_db.sh    # DB 준비 (최초 1회)
./gradlew bootRun --args='--spring.profiles.active=local'
```

### 인증 메일 확인 방법
로컬은 SMTP 없이 **인증 코드를 서버 로그에 출력**한다.

```
[메일 발송(로그 모드)] ───────────────────────────────
  받는 사람 : hong@dwje.co.kr
  제목      : [덕우전자 AX] 회원가입 인증 코드
  인증 코드 : 382831  (유효 5분)
─────────────────────────────────────────────────
```

### 기존 시드 계정 (비밀번호 공통 `Dwje!2026`)
| 사번 | 이름 | 부서 | 이메일 |
|---|---|---|---|
| 10000 | 관리자 | 통합관리자 | 10000@dwje.co.kr |
| 10001 | 김품질 | 품질보증팀 | 10001@dwje.co.kr |
| 10002 | 박생산 | 생산관리팀 | 10002@dwje.co.kr |
| 10003 | 이제조 | 제조팀 | 10003@dwje.co.kr |
| 10004 | 최전산 | 전산팀 | 10004@dwje.co.kr |
| 10005 | 정경영 | 경영진 | 10005@dwje.co.kr |

### CORS
`http://localhost:*` 는 허용되어 있다. Vite/CRA 기본 포트 그대로 개발하면 된다.

---

## 6. 화면 구현 시 유의사항

1. **토큰 저장** — `accessToken` 유효기간 1시간. 만료 시 `/auth/refresh` 로 갱신하고, 실패하면 로그인 화면으로 보낸다.
2. **인증 단계 상태 유지** — `verificationToken` 은 *가입/재설정에 성공한 시점에* 소모되는 1회용이다.
   가입/재설정이 실패하면(사번 중복·이메일 중복·부서 오류 등) **토큰은 그대로 유효하므로 코드 검증으로 되돌릴 필요가 없다.**
   `signup` / `resetPassword` 는 `@Transactional` 이라 실패 시 토큰 소모까지 함께 롤백된다.
   화면은 입력값만 고쳐 같은 토큰으로 재요청하면 된다. (실서버로 검증: 사번 중복 실패 → 동일 토큰 재사용 가입 성공 → 3회차 재사용은 `E-RULE-001` 로 거부)
   단, 토큰 자체가 만료(기본 10분)되면 그때는 코드 검증부터 다시 해야 한다.
3. **재발송 쿨다운** — `resendAvailableInSec`(60초) 동안 버튼을 비활성화한다. 서버도 막지만 화면에서 먼저 막아야 사용자가 헷갈리지 않는다.
4. **마스킹된 이메일** — 응답의 `email` 은 이미 마스킹되어 있다. 원본을 다시 표시하지 말 것.
5. **가입 후 안내** — 즉시 로그인이 아니라 "전산팀 승인 대기" 안내가 맞다.
