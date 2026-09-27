# AI 채팅 이력 DB 확인 및 V43 적용 절차 (2026-09-24)

## 확인 결과

- 대상: 지정된 SSH 터미널에서 지정된 DB 호스트로 접속. `current_database() = dwjedb`, `current_user = dwje_local`, `inet_server_addr() = 172.18.0.2`, 서버 포트 `5432`. 마지막 주소는 PostgreSQL 컨테이너 내부 주소이므로 외부 접속 IP와 다르다.
- `ax.tb_ai_chat_log`는 존재한다. `information_schema.columns` 실측 결과 아래 16개 컬럼이 있고, `evidence_summary`와 `unanswered_reason`은 없다. 따라서 V43의 **효과는 현재 DB에 없다**.
- 비시스템 스키마에서 이름에 migration, flyway, changelog, schema history/version, db version이 들어간 이력 테이블은 없었다. `public`/`ax`의 표준 Flyway·Liquibase·schema_migrations 후보 6개도 모두 없었다. DB에 V43 적용 기록이 있다고 확인할 수 없다.
- 로컬 API `build.gradle.kts`에는 Flyway·Liquibase 의존성이 없다. `db/local/setup_local_db.sh`는 로컬용 V*.sql 일괄 적용 스크립트이며 운영 적용 수단으로 사용하면 안 된다.
- 이 조사는 `BEGIN READ ONLY`의 SELECT만 실행했다. ALTER, DDL, 운영 데이터 변경은 하지 않았다.

| 실제 컬럼 | 정보 스키마 타입 | NULL 허용 |
| --- | --- | --- |
| chat_id | bigint | 아니요 |
| session_id | uuid | 예 |
| asked_at | timestamp with time zone | 아니요 |
| user_id | character varying | 아니요 |
| dept_nm | character varying | 예 |
| question | text | 아니요 |
| normalized_question | text | 예 |
| intent_cd | character varying | 예 |
| intent_nm | character varying | 예 |
| answer | text | 예 |
| response_ms | integer | 예 |
| rating_cd | character varying | 예 |
| is_reask | boolean | 아니요 |
| prev_chat_id | bigint | 예 |
| blind_applied_cnt | integer | 아니요 |
| profile_id | integer | 예 |

## API 담당 판단 사항

현재 저장소의 `AiChatRepository.hasBasisColumns()`는 두 컬럼이 모두 없으면 INSERT에서 이들을 제외한다. 따라서 운영 로그의 `evidence_summary` 부재 오류는 **배포된 API 바이너리가 이 작업 트리의 조건부 코드와 다르거나, 다른 DB 연결/버전의 요청**이라는 추정이 가능하다. 배포 버전과 실제 API 연결 대상을 별도로 대조해야 한다. V43 적용은 현재 DB의 스키마 간극을 해소하지만 배포 불일치 자체의 증거는 아니다.

## 적용 전 검증안 (읽기 전용)

```sql
BEGIN READ ONLY;
SELECT current_database(), current_user, inet_server_addr(), inet_server_port();
SELECT column_name, data_type, is_nullable
FROM information_schema.columns
WHERE table_schema = 'ax' AND table_name = 'tb_ai_chat_log'
  AND column_name IN ('evidence_summary', 'unanswered_reason')
ORDER BY column_name;
ROLLBACK;
```

DB와 역할이 승인된 대상인지 확인하고, 대상 컬럼이 둘 다 없거나 일부만 있는지 확인한다. migration history 테이블은 실측상 없으므로 파일 적용 이력을 주장하지 않는다.

## 필요한 migration SQL — 이번 조사에서는 실행하지 않음

현재 worktree의 `src/main/resources/db/V43__ai_chat_basis.sql`에 있는 변경문은 다음 한 문장이다. 실행한다면 짧은 잠금 제한을 둔 단일 트랜잭션에서 **이 ALTER만** 적용하고, 잠금 시간 초과나 오류가 나면 중단한다.

```sql
BEGIN;
SET LOCAL lock_timeout = '3s';
ALTER TABLE ax.tb_ai_chat_log ADD COLUMN IF NOT EXISTS evidence_summary text, ADD COLUMN IF NOT EXISTS unanswered_reason text;
COMMIT;
```

## 적용 후 검증안 (읽기 전용)

```sql
BEGIN READ ONLY;
SELECT column_name, data_type, is_nullable
FROM information_schema.columns
WHERE table_schema = 'ax' AND table_name = 'tb_ai_chat_log'
  AND column_name IN ('evidence_summary', 'unanswered_reason')
ORDER BY column_name;
ROLLBACK;
```

정상 결과는 두 행 모두 `text`, `YES`다. 이 검증은 스키마만 확인하며 사용자 질문·응답 데이터는 조회하지 않는다. 채팅 API의 오류 해소 여부는 API 담당이 배포 버전과 함께 확인한다.

## Rollback안 — 이번 조사에서는 실행하지 않음

우선 API를 기존 컬럼만 사용하는 버전으로 되돌린다. 신규 컬럼에 값이 기록됐다면 컬럼을 유지해 데이터 손실을 막는다. 두 컬럼이 불필요하고 저장된 값도 없음을 확인한 경우에만 별도 승인 후 아래 DDL을 검토한다.

```sql
BEGIN;
SET LOCAL lock_timeout = '3s';
ALTER TABLE ax.tb_ai_chat_log DROP COLUMN IF EXISTS evidence_summary, DROP COLUMN IF EXISTS unanswered_reason;
COMMIT;
```

DROP은 저장된 값을 삭제한다. rollback 여부는 새 API의 참조 제거와 데이터 보존 판단 후 결정해야 한다.
