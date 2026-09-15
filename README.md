# 덕우전자 AX 시스템 API (dwje-api)

> 로컬에서 DB · API · 웹 · 이관 엔진을 함께 띄우는 절차는 [실행 가이드](../실행_가이드.md) 를 보십시오.

덕우전자 AX 시스템 백엔드 API. `004. 개발/기능 및 API 명세/ver01/API_목록_ver01.xlsx` 의 **233건 API 전체**를 구현했다.

- 코딩 규칙 : `004. 개발/개발스팩_및_코딩_작성_규칙.md`
- 기능 명세 : `004. 개발/기능 및 API 명세/ver01/기능명세서_ver01.xlsx`
- DB 스키마 : `004. 개발/Postgresql 스키마/*.sql`

---

## 1. 기술 스펙

| 구분 | 내용 |
|---|---|
| 언어 | Kotlin 2.1 / JDK 21 (toolchain) |
| 프레임워크 | Spring Boot 3.4.4 |
| DB 접근 | **Native SQL Direct Binding** — `NamedParameterJdbcTemplate` (iBatis/MyBatis 미사용) |
| DB | PostgreSQL (`ax` · `mes` · `vec` · `common` 스키마) |
| 인증 | JWT (Bearer Token) — jjwt 0.12 |
| 빌드 | Gradle Kotlin DSL + Wrapper 8.13 |
| 문서 | springdoc-openapi (Swagger UI) |
| 엑셀 출력 | Apache POI 5.3 |

---

## 2. 실행

```bash
# 로컬 개발 실행
./gradlew bootRun --args='--spring.profiles.active=local'

# 개발/테스트 빌드
./gradlew clean bootJar -Dspring.profiles.active=dev

# 운영 배포 빌드 및 실행
./gradlew clean bootJar -Dspring.profiles.active=prod
java -jar -Dspring.profiles.active=prod build/libs/dwje-api-0.0.1.jar

# 단위 테스트
./gradlew test
```

### 환경변수

배포 시 `config/api.env.example`을 `config/api.env`로 복사하고 실제 접속 정보를 입력한 뒤
`./start.sh --profile=prod`로 실행한다. `start.sh`가 이 파일의 변수를 export하여 Java에 전달한다.
기존 `config/api.env`가 있다면 복사로 덮어쓰지 않고 필요한 항목만 수정한다.

```bash
SPRING_DATASOURCE_USERNAME="dwje_local"
SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5432/dwjedb"
```

`SPRING_DATASOURCE_USERNAME`은 Spring의 `spring.datasource.username`을 직접 덮어쓴다.
DB 비밀번호는 `PROD_DB_PASSWORD`에 설정한다. DB가 별도 서버라면 URL의 `localhost`를
해당 DB 호스트로 변경한다. 직접 `java -jar`로 실행할 때는 환경변수 파일이 자동으로 로드되지 않는다.

프로파일 yml 의 `${...}` 는 **기본값이 없으면 전부 필수**다. 하나라도 없으면
`Could not resolve placeholder '<이름>'` 으로 기동 단계에서 죽는다.

| 프로파일 | 변수 | 누락 시 |
|---|---|---|
| local | `LOCAL_DB_PASSWORD` (기본 `dwje_local`) | 기본값으로 기동 |
| dev | `DEV_DB_PASSWORD` · `DEV_JWT_SECRET` · `DEV_MAIL_HOST` · `DEV_MAIL_USERNAME` · `DEV_MAIL_PASSWORD` | **기동 실패** (5개 전부 필수) |
| prod | `PROD_DB_PASSWORD` · `PROD_JWT_SECRET` · `PROD_MAIL_HOST` · `PROD_MAIL_USERNAME` · `PROD_MAIL_PASSWORD` | **기동 실패** (5개 전부 필수) |
| 공통(선택) | `AX_UPLOAD_DIR` (기본 `./data/ax-uploads`) · `AX_NAS_AOI_ROOT` (기본 `./data/nas-aoi`) | 기본값으로 기동 — 업로드 리포트 원본 저장소 · AOI 이미지 NAS 마운트 루트 |
| 공통(선택) | `AX_MSSQL_URL` (기본 EDGE 192.168.7.203) · `AX_MSSQL_USER` · `AX_MSSQL_PASSWORD` | 기본값으로 기동 — 계정이 비면 AOI 치수 API 가 `SOURCE_NOT_CONFIGURED` 를 낸다(원천 MSSQL 직접 조회, `docs/AOI_DIMENSION_API_20260913.md`) |

> 메일 3종은 이 표에 없었다. 그래서 안내대로 DB·JWT 두 개만 채우면
> `Could not resolve placeholder 'DEV_MAIL_HOST'` 로 기동조차 못 했다.
> `EnvVarDocumentedTest` 가 프로파일 yml 의 필수 플레이스홀더와 이 표를 대조하므로,
> 변수를 추가하고 표를 안 고치면 빌드가 깨진다.

메일 3종은 `sender-mode: SMTP` 때문에 필요하다. 기본값을 주지 않는 이유는,
빈 값으로 기동되면 인증 메일이 조용히 실패해 회원가입·비밀번호 찾기가 막히기 때문이다.

JWT 서명 키(`app.jwt.secret`)는 생성자 기본값이 없다. 환경변수가 주입되지 않으면 기동 단계에서 아래 메시지와 함께 종료된다.

```
JWT 서명 키가 주입되지 않았습니다. app.jwt.secret 값이 '${PROD_JWT_SECRET}' 로 남아 있습니다.
```

### 설정 값 우선순위

`application-{profile}.yml` **>** Kotlin `@ConfigurationProperties` 생성자 기본값.

- yml 에 키가 있으면 언제나 yml 이 이긴다. 값을 바꿀 때는 **yml 을 수정**한다 (생성자 기본값만 바꾸면 반영되지 않는다)
- 생성자 기본값은 yml 에 키가 아예 없을 때의 안전망이다 (단위 테스트, 최소 구성 배포)
- ⚠️ Spring 의 설정 바인딩은 해석되지 않은 `${ENV}` 를 **오류로 처리하지 않고 문자열 그대로 바인딩**한다. 생성자 기본값으로 폴백하지 않는다

### 설정 검증 방식

Spring Boot 표준인 `@Validated` + Jakarta Bean Validation 제약을 쓴다. 생성자에서 `require()` 로 던지는 방식 대비 이점이 크다.

| | `init { require(...) }` | `@Validated` + JSR-303 |
|---|---|---|
| 위반 보고 | 첫 건에서 중단 | **전부 한 번에** |
| 실패 표현 | `BeanInstantiationException` 스택트레이스 | Spring Boot 실패 분석기 리포트 |
| 위치 정보 | 없음 | **yml 파일명·줄번호** 제공 |

실제 출력 (운영 프로파일에서 `PROD_JWT_SECRET` 미주입):

```
Description:

Binding to target com.dwje.api.config.JwtProperties failed:

    Property: app.jwt.secret
    Value: "${PROD_JWT_SECRET}"
    Origin: class path resource [application-prod.yml] - 28:13
    Reason: JWT 서명 키는 UTF-8 기준 32바이트(256비트) 이상이어야 합니다. (RFC 7518 §3.2)

    Property: app.jwt.secret
    Value: "${PROD_JWT_SECRET}"
    Origin: class path resource [application-prod.yml] - 28:13
    Reason: 환경변수가 주입되지 않아 설정 값이 플레이스홀더(${...}) 상태로 남아 있습니다.
```

미해석 플레이스홀더는 길이 검증만으로 못 거른다(환경변수 이름이 32자를 넘으면 통과). 전용 제약 `@ResolvedPlaceholder`(`common/validation/`)로 별도 차단한다.

`ConfigBindingTest` 7건이 이 규약을 회귀 테스트로 고정한다.

### 요청 본문 검증 규약

요청 DTO 에 제약(`@field:NotBlank` 등)을 달아도 컨트롤러 파라미터에 `@Valid` 가 없으면 **Spring 은 조용히 검증을 건너뛴다.** 개발자는 검증이 걸린 줄 알지만 실제로는 무방비다.

- 전 `@RequestBody`(76개)에 `@Valid` 를 부착했다
- `RequestValidationContractTest` 가 "제약이 있는 DTO 는 반드시 `@Valid` 로 받는다"를 빌드 시점에 강제한다. 새 API 를 추가하며 `@Valid` 를 빠뜨리면 빌드가 깨지고, 어느 컨트롤러·메서드·DTO 인지 지목한다

### 접속 경로

| 항목 | 경로 |
|---|---|
| Base URL | `http://{host}:8080/api/v1` |
| Swagger UI | `http://{host}:8080/swagger-ui.html` (운영 프로파일에서는 비활성) |
| 헬스체크 | `GET /api/v1/health` |

---

## 3. DB 준비

기존 3개 스키마 스크립트를 적용한 뒤, **본 프로젝트가 추가한 확장 스크립트**를 적용한다.

```bash
psql -d dwjedb -f "004. 개발/Postgresql 스키마/mes_db_query.sql"
psql -d dwjedb -f "004. 개발/Postgresql 스키마/ai_db_query.sql"
psql -d dwjedb -f "004. 개발/Postgresql 스키마/ai_db_vector_query.sql"

# API 구현 전제 확장 스키마
psql -d dwjedb -f src/main/resources/db/V2__ax_report_extension.sql
psql -d dwjedb -f src/main/resources/db/V3__ax_sync_schema_drift.sql
psql -d dwjedb -f src/main/resources/db/V4__ax_sync_job_queue.sql
psql -d dwjedb -f src/main/resources/db/V5__report_menu.sql
psql -d dwjedb -f src/main/resources/db/V6__report_definitions.sql
psql -d dwjedb -f src/main/resources/db/V7__account_signup.sql
psql -d dwjedb -f src/main/resources/db/V8__email_verification.sql
psql -d dwjedb -f src/main/resources/db/V9__metric_uptime_standard.sql
psql -d dwjedb -f src/main/resources/db/V10__qc_nonprod_defect_codes.sql
psql -d dwjedb -f src/main/resources/db/V11__glossary_domain_seed.sql
psql -d dwjedb -f src/main/resources/db/V12__metric_window_code_fix.sql
psql -d dwjedb -f src/main/resources/db/V13__ax_sync_run.sql
psql -d dwjedb -f src/main/resources/db/V14__ax_sync_run_dry_run.sql
psql -d dwjedb -f src/main/resources/db/V15__menu_prod_down_off.sql
psql -d dwjedb -f src/main/resources/db/V23__report_center.sql
psql -d dwjedb -f src/main/resources/db/V24__report_usage.sql
psql -d dwjedb -f src/main/resources/db/V25__menu_ai_panel_upload_aoi.sql
psql -d dwjedb -f src/main/resources/db/V26__dash_upload_doc.sql
psql -d dwjedb -f src/main/resources/db/V27__aoi_defect_image.sql
psql -d dwjedb -f src/main/resources/db/V28__aoi_defect_image_dimension_key.sql
psql -d dwjedb -f src/main/resources/db/V29__aoi_wc_display_name.sql
```

> 번호는 한 번호에 한 파일이다. `V6__ax_sync_run.sql` 이 `V6__report_definitions.sql` 과
> 번호가 겹쳐 목록에서 하나가 빠지기 쉬웠고, 마이그레이션 도구를 붙이면 중복 버전으로 실패한다.
> 그래서 이관 실행 이력은 `V13` 으로 옮겼다. 새 파일은 다음 빈 번호를 쓴다.

로컬은 `db/local/setup_local_db.sh` 가 위 전체를 순서대로 적용한다. (필요 확장: `pgvector`, `pg_trgm`)

### 확장 스키마가 필요한 이유

기존 DDL에는 아래 업무를 담을 테이블이 없어 해당 API를 구현할 수 없다. 기존 테이블은 변경하지 않고 최소 확장만 추가했다.

| 추가 테이블 | 대상 API | 배경 |
|---|---|---|
| `ax.tb_rpt_doc` · `tb_rpt_doc_field` · `tb_rpt_doc_event` · `tb_rpt_doc_image` · `tb_rpt_doc_approval` | PR-03/04, QC-03, RP-06/07 | `ax.tb_rpt_report` 는 보고서 '정의'만 담고 있어 초안·버전·확정 상태·항목 값·결재선을 저장할 곳이 없음 |
| `ax.tb_rpt_scrap_row` | RP-07 (No.115~124) | 폐기 보고서 상세 행(MES 전표 + 수기 추가) |
| `ax.tb_rpt_unmask_req` | QC-03 (No.89) | 마스킹 해제 요청 |
| `ax.tb_prod_downtime` | PR-05 (No.68~72) | 설비 정지/재가동 구간과 사유 |
| `ax.tb_prod_ship_plan` | RP-03 (No.110) | 연간 출하계획 |
| `ax.tb_prod_item_price` | RP-07 (No.120) | 폐기 금액 산정 단가 |
| `ax.tb_qc_lrr_notice` | RP-05 (No.112) | 기능명세서가 "신규 테이블 필요"로 명시한 고객사 LRR 통보 접수 이력 |

아침회의 일목표(RP-01/02)는 별도 테이블 없이 지표 체계를 재사용한다 — `metric_cd = 'PROD_DAY_TARGET'` 의 측정값에 `wc_cd` 를 지정해 적재.

---

## 4. 패키지 구조

```text
src/main/kotlin/com/dwje/api/
├── common/
│   ├── response/     ApiResponse · PageMeta · ErrorCode
│   ├── exception/    GlobalExceptionHandler · CustomExceptions
│   ├── security/     UserPrincipal · UserContext · JwtTokenProvider
│   └── util/         DataField · MenuId · MaskingSupport · DateUtils · PageRequestParam · SortResolver
├── config/           SecurityWhitelist · JwtProperties · AppProperties · DatabaseConfig · WebMvcConfig · OpenApiConfig
├── middleware/       AccessLogFilter → SqlInjectionCheckFilter → JwtAuthFilter
├── controller/       도메인별 REST 컨트롤러 16종
├── model/
│   ├── request/      요청 DTO
│   └── response/     응답 DTO
├── service/          비즈니스 로직 25종
└── repository/       SQL 직접 실행 계층 21종
```

---

## 5. 미들웨어 체인

```
[Client Request]
      ↓
[AccessLogFilter]         접속 IP · User-Agent · URI · 파라미터 로깅 (비밀번호·토큰 마스킹)
      ↓                   본문 재읽기용 CachedBodyHttpServletRequest 래핑
[SqlInjectionCheckFilter] URL 파라미터 · JSON 본문의 악성 SQL 패턴 8종 정규식 검증 → 위반 시 400
      ↓
[JwtAuthFilter]           화이트리스트 검사 → 토큰 서명·만료 검증 → 최신 권한 DB 조회 → UserContext 바인딩
      ↓
[Controller / Service]    비즈니스 로직
```

**화이트리스트** (`config/SecurityWhitelist.kt`) : `/auth/login`, `/auth/refresh`, `/health`, `/swagger-ui/**`, `/v3/api-docs/**`

권한은 매 요청 DB에서 조회한다 — 토큰 발급 이후 관리자가 권한을 바꿔도 즉시 반영된다.

---

## 5-1. 인증 · 회원가입

### 비밀번호 해시

`app.security.password.algorithm` 으로 선택한다. 저장 문자열에 알고리즘 접두사가 포함되어 **서로 다른 방식이 섞여 있어도 검증된다.**

| 값 | 저장 포맷 | 용도 |
|---|---|---|
| `PBKDF2_SHA512` (기본) | `{pbkdf2-sha512}210000$솔트$해시` | 솔트 + 반복 확장 (RFC 8018) |
| `SHA512` | `{sha512}솔트$해시` | 기존 시스템과 해시 값을 맞춰야 할 때 |
| — | `$2a$10$...` | 이전에 BCrypt 로 발급된 계정 (검증만 지원) |

> SHA-512 는 원래 **빠르게** 설계된 해시라 GPU 로 초당 수십억 회 대입할 수 있다. 비밀번호에 단독으로 쓰면 유출 시 취약해서, 기본값은 같은 SHA-512 계열이면서 솔트·반복을 적용하는 PBKDF2-HMAC-SHA512 로 둔다. 바이트 단위 호환이 필요할 때만 `SHA512` 로 바꾼다.

**자동 승급** — 로그인에 성공하면 저장된 해시가 현재 기준(알고리즘·반복 횟수)에 못 미칠 때 조용히 재해시한다. 기존 BCrypt 계정도 사용자가 아무것도 하지 않고 다음 로그인부터 새 포맷으로 검증된다.

**비밀번호 정책** — 최소 8자, 공백 불가, 영문·숫자·특수문자 중 2종 이상, 사번 포함 불가.

### 이메일 인증

회원가입과 비밀번호 찾기는 **이메일 소유 확인**을 거친다.

```
코드 발송  POST /auth/email/send-code    {email, purpose}
코드 검증  POST /auth/email/verify-code  {email, purpose, code} → verificationToken (1회용, 10분)
본 처리    signup / password/reset       {…, verificationToken}  → 토큰 소모
```

| 보호 장치 | 기본값 | 이유 |
|---|---|---|
| 코드 해시 저장 | — | DB 유출 시에도 코드를 역산할 수 없다 |
| 코드 유효 시간 | 5분 | 탈취 시 사용 가능 시간 최소화 |
| 검증 시도 상한 | 5회 | 6자리 무차별 대입 차단 |
| 재발송 대기 | 60초 | 메일 폭탄 방지 |
| 일일 발송 상한 | 10회/주소 | 남용 방지 |
| 이전 코드 폐기 | — | 새 코드 발송 시 옛 코드를 즉시 무효화해 동시 시도를 막는다 |

발송 방식은 `sender-mode` 로 고른다. 로컬은 `LOG`(코드를 서버 로그에 출력), dev·prod 는 `SMTP`.

> ⚠️ 시간 계산(만료·재발송 대기)은 **전부 DB 시계(`now()`)** 로 한다. JVM(KST)과 DB(UTC)의 시간대가 달라 애플리케이션에서 `LocalDateTime` 으로 비교하면 9시간이 어긋나 만료 판정과 발송 제한이 모두 무너진다.

### 회원가입 흐름

```
[이메일 인증] POST /auth/email/send-code → verify-code → verificationToken
                                               ↓
[가입 신청]  POST /api/v1/auth/signup        → 계정 생성 (PENDING)
                                               ↓ 로그인 시도하면 차단
[승인 대기]  GET  /api/v1/system/users/pending  (전산팀)
                                               ↓
[승인/반려]  POST /api/v1/system/users/{empNo}/approve
                                               ↓
[로그인]     POST /api/v1/auth/login          → 부서 권한 자동 상속
```

### 비밀번호 찾기 흐름

```
POST /auth/password/forgot  {empNo, email}   → 사번·이메일 일치 시에만 코드 발송
POST /auth/email/verify-code (PASSWORD_RESET) → verificationToken
POST /auth/password/reset   {token, newPassword}
```

계정 존재 여부를 노출하지 않기 위해 **일치하지 않아도 동일한 성공 응답**을 반환한다. 연속 실패로 잠긴 계정은 재설정 시 자동 해제된다.

사내 시스템이므로 가입 즉시 사용할 수 없다. 계정은 **승인 대기(PENDING)** 로 만들어지고 전산팀이 소속·신원을 확인해 활성화한다. 승인되면 소속 부서의 메뉴·데이터 권한을 그대로 상속한다.

| 메서드 | 경로 | 인증 | 설명 |
|---|---|---|---|
| POST | `/api/v1/auth/signup` | 불필요 | 회원가입 신청 |
| GET | `/api/v1/auth/signup/check-emp-no` | 불필요 | 사번 중복 확인 |
| GET | `/api/v1/auth/signup/depts` | 불필요 | 가입 가능 부서 목록 |
| POST | `/api/v1/auth/email/send-code` | 불필요 | 이메일 인증 코드 발송 |
| POST | `/api/v1/auth/email/verify-code` | 불필요 | 인증 코드 검증 → 1회용 토큰 |
| POST | `/api/v1/auth/password/forgot` | 불필요 | 비밀번호 찾기 — 코드 발송 |
| POST | `/api/v1/auth/password/reset` | 불필요 | 비밀번호 재설정 |
| POST | `/api/v1/auth/password` | 필요 | 본인 비밀번호 변경 |
| GET | `/api/v1/system/users/pending` | 전산팀 | 승인 대기 목록 |
| POST | `/api/v1/system/users/{empNo}/approve` | 전산팀 | 가입 승인·반려 |

가입 신청·승인·비밀번호 변경·재설정은 모두 감사 로그에 기록된다.

프론트엔드용 상세 명세는 `docs/AUTH_API_FOR_WEB.md` 에 있다.

---

## 6. 권한 모델 (2계층)

### 메뉴 접근 권한 (부서 × 화면)
`ax.tb_sys_dept_menu_perm` 기준. 컨트롤러 진입 시 `AuthorizationService.requireMenu(menuId)` 로 판정하고, 권한이 없으면 `E-AUTH-002`.

### 데이터 접근 권한 (부서 × 데이터 항목 7종)
`ax.tb_sys_dept_data_perm` 기준.

| key | 항목명 | 포함 데이터 |
|---|---|---|
| `qty` | 생산·출하 수량 | 투입·양품·불량·출하 수량, 실적 집계 |
| `yield` | 수율·불량률 | 제품별 수율, 공정 불량률, 달성률, LRR(%) |
| `price` | 단가·금액 | 품목 단가, 가공비, 폐기 금액, 원가 |
| `customer` | 고객사·거래처 | 고객사명, 거래처, 계약 조건 |
| `plan` | 출하 계획 | 연간·월별 출하 계획 수량 |
| `mold` | 금형·설비 상세 | 금형 이력, 설비 파라미터, 공정 조건 |
| `worker` | 작업자 정보 | 사번, 작업자명, 근태·배치 |

`is_super_admin = true` 부서(통합관리자)는 전 권한을 보유한다.

### 마스킹 원칙

> 데이터 접근 권한이 없는 항목은 **API 응답 생성 단계**에서 값을 `null` 로 반환하고 `masked` 배열에 key 를 표기한다. 원본을 응답에 포함하지 않는다.

`common/util/MaskingSupport.kt` 가 이를 강제한다. `on()` 은 권한이 없으면 공급 함수를 **호출하지 않아** 원본 값이 메모리에 실리지 않는다.

```kotlin
val (principal, mask) = authorizationService.guard(MenuId.DASH_AI)
val row = mutableMapOf("qty" to 1200L, "amount" to 5000.0)
mask.applyTo(row, mapOf("qty" to DataField.QTY, "amount" to DataField.PRICE))
return ApiResponse.ok(row, mask.maskedKeys())   // masked: ["price"]
```

---

## 7. 응답 포맷

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "조회가 완료되었습니다.",
  "data": { },
  "meta": { "page": 1, "size": 50, "total": 233 },
  "masked": ["price", "customer"],
  "timestamp": 1724888400000
}
```

`null` 필드는 응답에서 생략된다 (`@JsonInclude(NON_NULL)`).

### 에러 코드

| 코드 | HTTP | 의미 |
|---|---|---|
| `E-AUTH-001` | 401 | 미인증 · 세션 만료 |
| `E-AUTH-002` | 403 | 메뉴 접근 권한 없음 |
| `E-AUTH-003` | 403 | 데이터 접근 권한 없음 |
| `E-VALID-001` | 400 | 필수 항목 누락 |
| `E-VALID-002` | 400 | 중복 값 |
| `E-RULE-001` | 409 | 업무 규칙 위반 |
| `E-NOTFOUND` | 404 | 대상 없음 |
| `E-SERVER` | 500 | 서버 오류 |

---

## 8. 쿼리 작성 표준

iBatis/MyBatis를 쓰지 않고 Kotlin 소스 내 문자열 변수로 SQL을 직접 관리한다. **모든 값은 Named Parameter 로 바인딩**한다.

```kotlin
fun findLineProduction(plantCd: String, date: LocalDate, processId: String?): List<Map<String, Any?>> {
    // 1. 기본 SQL 정의
    val sql = StringBuilder("""
        SELECT lh.eqpt_cd, coalesce(sum(lh.normal), 0) AS ok_qty
        FROM mes.tb_pop_label_hist lh
        WHERE lh.plant_cd = :plantCd AND lh.del_flg = 'N'
    """.trimIndent())

    // 2. 파라미터 바인딩 객체
    val params = MapSqlParameterSource("plantCd", plantCd)

    // 3. 동적 조건 (값은 반드시 :param 바인딩)
    if (!processId.isNullOrBlank()) {
        sql.append(" AND lh.wc_cd = :processId")
        params.addValue("processId", processId.trim())
    }

    // 4. 실행 및 매핑
    return jdbcTemplate.query(sql.toString(), params) { rs, _ -> mapOf(...) }
}
```

### 컬럼명이 SQL에 직접 들어가는 두 곳

정렬(`ORDER BY`)과 집계 단위(`day/week/month`)는 값 바인딩이 불가능하므로 **화이트리스트로만** 결정한다.

- `SortResolver.resolve(sort, allowed, default)` — 허용 맵에 없는 필드는 `E-VALID-001`
- `ProductionService.normalizeUnit(unit)` — `day|week|month` 외 거부
- `ScrapReportRepository.updateUnitPriceManually` — `model|process` 외 거부

### 주요 산출식

| 지표 | 산출식 | 원천 |
|---|---|---|
| 생산량 | `normal + defect` | `mes.tb_pop_label_hist` |
| 불량률(%) | `defect / (normal + defect) × 100` | 동일 |
| 수율(%) | `normal / (normal + defect) × 100` | 동일 |
| 불량 유형별 | `sum(qty) group by defect_cd` | `mes.tb_pop_defect_hist` |
| 가동률 | `avg(metric_value)` where `metric_cd='EQPT_UPTIME_RATE'` | `ax.tb_met_metric_value` |
| LRR(%) | `LRR Q'ty / Ship Q'ty × 100` | `ax.tb_qc_lrr_notice` |

요약 지표는 단순 평균이 아닌 **가중 평균**(총불량 ÷ 총생산)으로 산출한다.

MES 실적은 품목 코드(`item_cd`) 기준이고 화면은 제품 코드(`model_cd`) 기준이므로, `ax.tb_prod_item_map` 을 경유해 두 체계를 연결한다.

---

## 9. 감사 로그 자동 기록

공통 규약 6의 8개 항목을 각 서비스에서 호출한다.

| 구분 | 대상 | 서비스 |
|---|---|---|
| 권한 변경 | `/system/menu-perms`, `/data-perms`, `/users`, `/depts` | `SystemUserService` |
| 마스킹 | `/quality/reports/{id}/unmask-request`, AI 질의 denied 분기 | `QualityReportService` · `AiChatService` |
| 출력 | 전 내려받기 API | `DownloadLogService` |
| 알림 조건 | `/alert-conditions/*` | `AlertConfigService` |
| 연동 | `/sync/jobs/{id}/retry`, `/sync/jobs/manual`, `/sync/schema-drift/{id}/resolve` | `SyncService` |

감사 기록 실패가 본 업무를 되돌리지 않도록 `REQUIRES_NEW` 트랜잭션에서 처리하고 예외를 삼킨다.

> AI 모델(`/ai/model-releases`, `/ai/model-config`) · 기준 수치(`/metrics/standards`) · 순위(`/products/families/order`) 항목은
> 2026-09-15 에 해당 화면 5개(제품군 순위 관리 · AI 모델 설정 · AI 모델 버전 관리 · Agent 실행 현황 · 지표 측정 데이터 관리)와 함께 API 를 제거해 더 이상 기록하지 않는다.

---

## 10. 도메인별 구현 현황

| 도메인 | 건수 | 컨트롤러 |
|---|---:|---|
| 인증·공통 (CM) | 13 | `AuthController` · `MenuController` · `CommonMasterController` |
| AI 질의 (AI-01) | 7 | `AiChatController` |
| 대시보드 (DB-01~03) | 34 | `DashboardAiController` · `DashboardProcessController` · `DashboardKpiController` |
| 생산관리 (PR-01~05) | 18 | `ProductionController` |
| 품질관리 (QC-01~04) | 29 | `QualityController` |
| 이상 알림 (AL-01) | 5 | `AlertController` |
| 보고서 (RP-01~07) | 20 | `ReportController` |
| 시스템관리 (SY-01~15) | 107 | `SystemUserController` · `AuditLogController` · `AlertConditionController` · `AlertRecipientController` · `GlossaryController` · `AiAdminController`(질의 이력만) · `DownloadLogController` · `SyncController` — SY-07·10·11·12·13 은 2026-09-15 제거 |

위 건수는 **API 목록 명세 기준의 초기 설계 값**이다. 이후 화면 요구로 엔드포인트가 늘었으므로
**현재 개수를 이 표에서 읽지 말 것.** 지금 등록된 오퍼레이션은 여기서 본다.

```
http://localhost:8080/swagger-ui.html      # 태그별로 접혀 있고 검색창이 있다
http://localhost:8080/v3/api-docs          # 기계 판독용
```

> 합계를 적어 두지 않는 이유는, 손으로 유지하는 숫자가 결국 어긋나기 때문이다.
> 실제로 이 표의 합계(233)와 본문 서술(240), `application.yml` 주석(243)이
> 서로 다르고 셋 다 현재 값과 달랐다. 동기화할 대상을 없애는 쪽이 맞다.

---

## 11. 외부 연동이 필요한 구간

아래 기능은 스키마·API 계약과 이력 기록까지 구현했으나, 실제 엔진 연동 시 내부 산출 로직을 교체해야 한다. 각 API는 산출 근거를 응답에 명시한다.

| 기능 | 현재 구현 | 연동 대상 |
|---|---|---|
| AI 질의 응답 (No.14) | 용어 정규화 + 키워드 의도 분류 + 전문검색(tsv) 기반 근거 검색. 부서 열람 권한 필터 적용 | 온프레미스 LLM 서빙 + pgvector 임베딩 검색 |
| AOI 불량률 예측 (No.76~80) | 최소제곱 선형 추세 + 95% 신뢰 밴드. `No.84 추정 근거 조회` 가 방식·표본·잔차·한계를 그대로 노출 | 예측 모델 서빙 |
| 음성 입력 변환 (No.20) | 파일 접수 후 `supported: false` 반환 | ASR 엔진 |
| 증빙 이미지 후보 (No.90) | 첨부된 이미지만 반환 | NAS 이미지 인덱스 |
| 알림 발송 (No.156 등) | 발송 로그(`ax.tb_alm_send_log`) 기록까지 수행 | 메일·SMS·메신저 게이트웨이 |
| MSSQL 연결 테스트 (No.231) | 최근 24시간 이관 성공 이력으로 대체 판정 | MSSQL 직접 커넥션 |

### 이관 엔진 연동 (SY-15)

`ax.tb_sync_job` · `tb_sync_job_error` · `tb_sync_schema_drift` 는 **MES_migration_engine
(`004. 개발/MES_migration_engine`)이 배치마다 JDBC 로 직접 기록한다.** API 는 조회와
운영 조작(재실행·수동 예약·드리프트 해소)만 담당하고 기록 경로를 갖지 않는다.

| 테이블 | 쓰기 | 읽기 |
|---|---|---|
| `ax.tb_sync_map` | 엔진(워터마크·누적 건수) / 운영자(이관 정의) | No.232 |
| `ax.tb_sync_job` | API(No.229·230 이 PENDING 예약 행 생성) / 엔진(선점·실행·마감) | No.226~228 |
| `ax.tb_sync_job_error` | 엔진(행 단위 실패) | No.228 |
| `ax.tb_sync_schema_drift` | 엔진(테이블 신규·유실 판정) | No.234~236 |

`V3__ax_sync_schema_drift.sql` 의 테이블은 엔진이 쓰고 화면이 읽으므로, 컬럼을 바꿀 때는
엔진의 `SchemaDriftRepository` 를 함께 고쳐야 한다.

#### 수동 이관·재실행의 실행 주체 (V4)

**API 는 작업을 실행하지 않는다.** No.229·No.230 은 `state_cd='PENDING'` 행을 만들 뿐이고,
이관 엔진이 `scheduled_at` 이 지난 행을 `FOR UPDATE SKIP LOCKED` 로 선점해 실행한다.
따라서 엔진이 떠 있지 않으면 화면의 수동 이관은 대기 상태로 남는다.

- `PENDING` 은 `SYNC_STATE` 공통코드에 추가된 상태다 (V4)
- 예약 행의 `started_at` 은 NULL 이고, 엔진이 선점하는 순간 기록된다
- 예약 작업 ID 는 `SYNC-yyMMddHHmmss-N` — `job_id` 가 varchar(20) 이므로 2자리 연도를 쓰고,
  같은 초의 충돌을 막기 위해 접미 번호를 `ax.seq_sync_job_no` 시퀀스에서 뽑는다

---

## 12. 로깅

| 프로파일 | root | `com.dwje.api` | JDBC |
|---|---|---|---|
| local · dev | INFO (콘솔) | DEBUG | DEBUG/TRACE (실행 쿼리·바인딩 파라미터) |
| prod | WARN (콘솔 + `logs/error.log`) | INFO | WARN |

접속 로그는 `logs/access.log` 에 별도 기록하며 90일 보관한다. 비밀번호·토큰 등 민감 파라미터는 `****` 로 마스킹한다.
