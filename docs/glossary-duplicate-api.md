# 용어 중복 — 대소문자·공백 무시 (2026-09-16, DB 유니크 인덱스 신설에 맞춤)

DB 담당이 `ax.tb_gls_term` 에 **대소문자·앞뒤 공백을 무시하는** 유니크 인덱스를 넣었다.
`can` · `CAN` · `Can` · `' can '` 은 이제 한 용어다. API 는 그 기준에 맞춰 중복을 **저장 전에** 걸러 내고,
경합으로 DB 유니크에 걸린 경우에도 같은 409 를 낸다. (전에는 이 경우 **500** 이 나갔다)

| 인덱스 | 대상 | 기준 |
| :--- | :--- | :--- |
| `uq_gls_term_lower` (신설) | `ax.tb_gls_term` | `lower(btrim(term))` — 대소문자·앞뒤 공백 무시 |
| `uq_tb_gls_term` (기존) | `ax.tb_gls_term` | `term` 원문 — 대소문자를 못 막았다 |
| `uq_gls_variant_word` | `ax.tb_gls_variant` | `lower(word)` — **표 전체**에서 한 번만(용어별이 아니다) |

## 1. 응답 — 중복은 409 `E-RULE-001`

| 상황 | 상태 | 메시지 | `field` |
| :--- | :--- | :--- | :--- |
| 등록·수정하려는 용어가 이미 있다 | 409 | `이미 등록된 용어입니다. [CAN]` — 표기가 다르면 뒤에 `대소문자·앞뒤 공백만 다른 이름은 같은 용어로 봅니다. (입력: can)` | `term` |
| 그 이름을 **삭제된** 용어가 점유 (수정 시) | 409 | `삭제된 용어가 이 이름을 쓰고 있어 바꿀 수 없습니다. [CAN] 그 용어를 되살리려면 같은 이름으로 새로 등록하세요.` | `term` |
| 유사어가 이미 있다 | 409 | `이미 등록된 유사어입니다. [깡통] 공식 용어 [CAN] 에 붙어 있습니다.` | `word` |

- 대괄호 안은 **입력값이 아니라 이미 등록된 표기**다. `can` 을 넣었는데 `[CAN]` 이 나와야 사용자가 왜 막혔는지 안다.
- 유사어는 용어별이 아니라 표 전체에서 한 번만 쓸 수 있어, 어느 공식 용어가 쓰고 있는지 함께 알려 준다.
- 경합(사전 조회를 통과한 뒤 DB 유니크에 걸린 경우)에는 이미 등록된 표기를 알 수 없어 입력값으로 안내한다.
  트랜잭션이 이미 중단돼 다시 조회할 수 없기 때문이다 — 거기서 SELECT 를 하면 409 대신 500 이 나간다.

**400 이 아니라 409 다.** 회원가입 사번·이메일 중복은 지금도 400 `E-VALID-002` 이고(`AUTH_API_FOR_WEB.md`),
그 규약은 건드리지 않았다. "이미 있는 자원과 충돌" 쪽은 데이터 접근 항목(`data-field-runtime-api.md`)과 같이
409 `E-RULE-001` 로 맞췄다. `ConflictingValueException` 이 그것이며 `field` 를 실어 WEB 이 입력칸을 짚을 수 있다.

## 2. 그 밖에 달라진 것

- **저장값은 trim 한다.** 서비스의 `trim()` 에 더해 INSERT/UPDATE SQL 에도 `btrim` 을 씌웠다.
  인덱스가 `lower(btrim(term))` 기준이라 `' CAN'` 이 그대로 들어가면 저장은 돼도 검사 기준과 어긋난다.
- **자기 자신은 검사에서 뺀다.** 표기만 바꾸는 수정(`can` → `CAN`)이 제 이름에 막히지 않는다.
- **되살림도 대소문자를 무시한다.** 삭제된 `CAN` 이 있을 때 `can` 으로 등록하면 새 행을 만들지 않고 그 행을 되살린다
  (만들면 유니크에 걸려 500 이다). 표기는 저장된 것을 유지하고, 응답 `term` 으로 어느 표기로 돌아왔는지 알려 준다.
- 응답에 `term` · `word` 를 추가했다(등록·수정). 화면이 **실제로 저장된 표기**를 그대로 보여 줄 수 있다.

| 엔드포인트 | 응답 `data` 추가분 |
| :--- | :--- |
| `POST /glossary/terms` | `term` (되살림이면 `restored` · `restoredVariants` 와 함께 되살린 행의 표기) |
| `PUT /glossary/terms/{termId}` | `term` |
| `POST /glossary/terms/{termId}/variants` | `word` |
| `PUT /glossary/variants/{variantId}` | `word` |

## 3. 고친 파일

| 파일 | 내용 |
| :--- | :--- |
| `repository/GlossaryRepository.kt` | `findTermByName` 을 `lower(btrim(term))` 기준으로(저장 표기도 반환), `findVariantByWord` 신규, 쓰기 SQL 에 `btrim` |
| `service/GlossaryService.kt` | 등록·수정 사전 검사, `DuplicateKeyException` → 409 변환, 중복 메시지 helper 2개 |
| `common/exception/CustomExceptions.kt` | `ConflictingValueException`(409 + `field`) 추가 |
| 테스트 `GlossaryDuplicateTest`(8) | 사전 검사·경합·되살림·자기 제외·trim·409 코드 |
| 테스트 `GlossaryDuplicateSqlTest`(3) | 조회식이 유니크 인덱스를 타는지, 인덱스가 DB 에 있는지 |
| `db/V37__gls_term_case_insensitive_unique.sql` · `rollback/V37__down.sql` | 유니크 인덱스 (DB 담당) |

## 4. 마이그레이션 — `V37`

`uq_gls_term_lower` 는 DB 담당이 `db/V37__gls_term_case_insensitive_unique.sql` 로 남겼다(되돌리기 `rollback/V37__down.sql`).
`setup_local_db.sh` 는 `db/V*.sql` 을 자동으로 훑으므로 손대지 않았다. 세 갈래로 돈다 —
이미 있으면 NOTICE 만 내고 건너뛰고, 깨끗하면 만들고, 대소문자 중복이 남아 있으면
`DETAIL` 에 `term_id:term` 을 나열하고 멈춘다. 로컬에서 세 갈래와 되돌리기까지 확인했다.

쌓여 있던 중복 정리(1,234 → 575건)는 사람 검토가 들어간 일회성 보정이라 회차 파일에 없다.
새로 만든 DB 는 용어 0건에서 출발하므로 인덱스만 있으면 같은 상태가 된다.
`GlossaryDuplicateSqlTest.uniqueIndexExists` 가 인덱스 유무를 지킨다.
