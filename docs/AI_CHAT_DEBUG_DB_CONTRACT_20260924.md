# AI ask 업무 조회·진단 저장 계약 (API 로컬 구현, 운영 미적용)

## 질의 경로

AGY 모델 계약에 맞춰 `/ask`가 먼저 `POST {DWJE_LLM_BASE_URL}/v1/chat/completions`에 OpenAI `tools`·`tool_choice:auto`를 보낸다. API는 `choices[0].message.tool_calls[0].function.name/arguments`를 구조적으로 읽어 허용된 이름·날짜·limit만 받아들인다. 모델이 반환한 SQL은 실행하지 않는다. 모델 도구 판단이 실패하거나 인자가 유효하지 않으면 잘못된 기간으로 대체 조회하지 않는다. `/api/ai/chat` 단독 호출은 `/ask` 결과로 `assistant.tool_calls`와 `role:tool`을 구성해 2차 LLM에 보낸다. WEB이 `/ask`와 `/api/ai/chat`을 분리 호출하고 `messageId`를 넘기는 기존 흐름은 `context`의 근거 텍스트를 사용한다. 도구를 고르지 않은 질문도 기존 문서 검색과 두 번째 답변 경로를 거친다.

AGY 담당자가 외부 API 서버용 게이트웨이 주소를 `http://192.168.2.8:11436`으로 확인했다. 로컬 코드나 배포 설정의 URL 값은 이번 작업에서 바꾸지 않았다. 새 모델 학습·서빙 완료 전이므로 실제 새 모델의 도구 반환은 호출 검증하지 않았다.

- `9월 20일 부터 9월 22일까지 생산된 제품 목록 출력`: 서울 기준 최근 유효 연도, 교대 업무일 `[9월 19일 08:00, 9월 22일 08:00)`를 파싱한다. `production_product_list` 고정 SELECT가 기간 내 MES 라벨 원장을 제한하고 제품 매핑 후 제품 코드순 최대 100종을 반환한다. 매핑이 없는 품목은 품목 코드로 표시한다. 생산량은 기존 데이터 항목 `qty` 권한으로 마스킹한다.
- `09-20 부터 09-22 까지 불량률 top 20`: 같은 기간, `defect_rate_top` 고정 SELECT가 제품별 `sum(defect) / sum(normal + defect)`의 가중 불량률을 계산한다. 불량률 내림차순, 불량 수량 내림차순, 제품 코드 오름차순으로 최대 20종이다. `yield` 권한이 없으면 순위 조회 자체를 하지 않는다. 수량은 `qty` 권한으로 마스킹한다.
- SQL에는 사용자 문장을 넣지 않는다. 사업장·시각·limit만 바인딩한다. MES 원장 시각은 타임존 없는 벽시계 `timestamp(3)`이며 시작 포함·종료 미포함이다.
- 명시 기간이나 순위를 파싱할 수 없으면 최신일·이번 달로 대체 조회하지 않는다. `INVALID_CONDITION` 진단 코드와 사용자용 조건 확인 문장만 반환한다.

## V44 저장 계약 — DB 담당 검토 대상

로컬 [V44__ai_chat_debug.sql](../src/main/resources/db/V44__ai_chat_debug.sql)은 `ax.tb_ai_chat_debug`를 만든다. **운영 DB에는 적용하지 않았다.** API 빌드·`start.sh`는 이 SQL을 자동 적용하지 않는다.

| 컬럼 | 형식 | 뜻 |
| --- | --- | --- |
| `request_id` | `uuid` PK | ask 1회 식별자. chat 로그 생성 전 실패해도 기록 |
| `chat_id` | `bigint` NULL | 성공 시 기존 채팅 로그 식별자. 실패 시 NULL. 실패 추적 때문에 FK 없음 |
| `user_id` | `common.d_user_id` | 요청자 |
| `asked_at` | `timestamptz` | 기록 시각 |
| `route_cd`, `parse_cd`, `tool_cd`, `execute_cd`, `error_cd` | 제한된 문자열 코드 | 날짜 해석·도구 선택·실행 결과. 예외 메시지 미저장 |
| `period_from`, `period_to` | `date` NULL | 해석된 교대 업무일 양 끝 |
| `row_cnt`, `doc_hit_cnt`, `tool_ms`, `total_ms` | `integer` | 반환 행·문서 히트·도구 시간·총 시간 |

원문 질문, SQL, 바인딩 값, 비밀번호, 토큰, 모델 답변은 이 테이블에 저장하지 않는다. API는 `to_regclass`로 V44 존재를 읽기 전용으로 확인하고, 미적용 시 구조화 서버 로그만 남긴다. `GET /api/v1/ai/chat/history/{messageId}`의 `debug`와 `GET /api/v1/ai/chat/history/debug/{requestId}`는 메뉴 권한에 더해 **통합관리자만** 조회할 수 있다. V44 미적용 시 상세의 `debug`는 null이며 requestId 조회는 자료 없음이다.

`POST /api/v1/ai/chat/ask` 성공 응답의 `debugRequestId`로 진단 행을 찾는다. 채팅 기록 생성 전에 실패한 요청은 `chat_id`가 NULL이므로 서버 구조화 로그의 requestId로 조회한다.

V43(`evidence_summary`, `unanswered_reason`)과 V44는 별도 계약이다. V44는 V43 컬럼을 참조하지 않는다. DB 담당은 V44의 컬럼 타입·인덱스·보존 기간을 검토해 API와 합의해야 한다. 현재 운영 적용 상태나 실제 조회 건수는 이 작업에서 확인하지 않았다.
