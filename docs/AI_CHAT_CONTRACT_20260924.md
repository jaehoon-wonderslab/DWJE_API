# AI 채팅 API·WEB·DB 계약 (로컬 구현, 미배포)

추가된 생산 제품 목록·불량률 순위 및 ask 진단 저장 계약은 [AI_CHAT_DEBUG_DB_CONTRACT_20260924.md](AI_CHAT_DEBUG_DB_CONTRACT_20260924.md)에 정리했다.

## 화면 호출

- 화면 진입: `GET /api/v1/ai/chat/sessions/latest` → `{sessionId, messages[]}`. 로그인 사용자에게 저장된 마지막 세션만 조회한다. 새 대화 후에는 `DELETE /api/v1/ai/chat/sessions/{sessionId}`가 반환한 `newSessionId`를 다음 질문에 사용한다. 기존 세션 이력은 보존한다.
- 질문: `POST /api/v1/ai/chat/ask`에 `{sessionId, question}` → `messageId`, `sessionId`, 문서 `sources`, 수치 `dataEvidence`. 이어서 `POST /api/ai/chat`에 `messageId`, `sessionId`, `messages`, `context`를 보낸다. `/api/ai/chat`만 호출해도 API가 질문 로그를 만들고 응답 헤더 `X-AI-Session-Id`, `X-AI-Message-Id`를 준다. 인사도 이 LLM 경로를 통과한다.
- 모델 응답은 OpenAI 호환 `data: {choices:[{delta:{content}}]}`와 `data: [DONE]` 형식이다. 내부 SQL·스키마 표시를 차단하기 위해 모델 응답 전체를 검사한 뒤 한 개의 SSE content 이벤트로 보낸다. 따라서 첫 글자는 모델 생성 완료 후 도착한다.
- 불량 top N Excel: `POST /api/v1/ai/chat/defects/top/export`에 `{from:"YYYY-MM-DD",to:"YYYY-MM-DD",limit:10}`. 양 끝 업무일 포함, 1~10건, 최대 93개 업무일. 응답은 `Content-Disposition: attachment`가 있는 xlsx 파일이다. API가 기존 `DownloadLogService.record`로 이력을 기록하므로 WEB은 `POST /api/v1/download-logs`를 다시 호출하지 않는다.
- 시스템 이력: `GET /api/v1/ai/chat/history` 및 `/{messageId}`는 질문, 응답, 판단 근거, 미응답 사유, 응답시간, 평가 기준을 준다. 응답에 intent/agents/search 원문을 싣지 않는다.

## DB 담당 확인 항목

- 교대 영업일 `D`는 `[D-1 08:00, D 08:00)`이다. `2026-09-22~23`은 `[2026-09-21 08:00, 2026-09-23 08:00)`이다. MES 원장의 타임존 없는 `timestamp(3)`에 이 벽시계 범위를 바인딩한다. 불량 유형은 범위 내 라벨 원장을 전체 LOT 키로 묶은 기존 `DefectSql` 계약을 사용한다.
- 자연어 불량 top N 날짜는 `YYYY-MM-DD`, `9-22 9-23 일`, `9월 22일 ~ 23일`, `9월 22일 ~ 9월 23일`을 양 끝 포함 업무일로 해석한다. 연도가 없으면 Asia/Seoul 오늘을 기준으로 미래가 아닌 가장 최근 유효 연도를 고르고, 12월→1월은 다음 연도로 넘긴다. **`최근`은 오늘 날짜가 아니라 MES에 실적이 있는 마지막 업무일 한 날**이다. 별도 날짜 없이 `불량 top N`만 물어도 같은 마지막 업무일을 기본값으로 쓴다.
- `vec.tb_doc`의 `ins_date`는 시스템 등록 시각, `doc_date`는 문서 기준일이다. “생성/작성” 질문은 `ins_date`를 Asia/Seoul 연도로 집계하고, “다루는 기간” 질문만 `doc_date`로 집계한다. 후자는 `doc_date IS NULL` 건수를 별도 표시한다. `COUNT(DISTINCT doc_id)`와 `vec.fn_allowed_doc(userId,false)`를 사용한다. 이 함수는 색인 완료 문서만 반환하므로 API 수치는 “열람 가능한 색인 완료 문서” 범위다. `SCRAP`, `CLAIM`은 로컬 vec DDL의 공통코드에 이름이 명시된 불량 보고서 유형이다. 실제 연도별 건수는 운영 DB에서 확인하지 않았다.
- 새 migration `V43__ai_chat_basis.sql`은 `ax.tb_ai_chat_log`에 `evidence_summary text`, `unanswered_reason text`를 추가한다. API 빌드와 `start.sh`는 이 SQL을 자동 적용하지 않는다. API 매퍼는 `information_schema.columns`에서 두 컬럼을 읽기 전용으로 확인해, V43 미적용 시 기존 INSERT·UPDATE와 `NULL` 별칭 SELECT를 사용한다. 따라서 기본 채팅은 가능하지만 수치 판단 근거·미응답 사유의 영구 저장은 제한된다. 두 컬럼 모두 확인되면 확장 SQL을 사용한다. 이번 작업에서는 운영 DDL을 적용하지 않았다.
- 다운로드 이력은 기존 `ax.tb_rpt_download_log`와 `DownloadLogService.record`를 사용한다. `GET /api/v1/download-logs`, `/summary`, `/retention-policy`에서 기존 정책대로 조회한다. 별도 테이블이나 WEB의 중복 이력 POST는 필요 없다.
