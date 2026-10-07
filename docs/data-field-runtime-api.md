# 데이터 접근 항목 운영 중 추가 — API 계약 (2026-09-16, V33)

데이터 접근 권한 항목이 7개 고정이던 것을 WEB 화면에서 **운영 중에 추가**할 수 있게 한다.
「`unitPrice` 라는 응답 값이 `price` 항목이다」는 연결을 소스 코드에서 DB(`ax.tb_sys_data_field_attr`)로 옮겼다.
DB 는 V33(DB 담당), API 는 여기. 연결 열쇠는 **API 응답 JSON 필드명**이고, 반영 시점은 **재로그인**이다.

## 1. 항목 CRUD — `/api/v1/system/data-fields`

편집 권한은 데이터 접근 권한 화면과 같다(`sys-data` — 전산팀 · 통합관리자).

| 메서드 | 경로 | 본문 | 응답 `data` |
| :--- | :--- | :--- | :--- |
| GET | `/system/data-fields` | — | `items[{key,name,desc,applyFlg,sortSeq,attrs[]}]` — 사용 중 항목 전체(미적용 포함) |
| POST | `/system/data-fields` | `{fieldKey,name,desc?}` (`category` 는 받고 버림 — 2026-10-07 분류 제거) | 등록된 항목(위 한 건 꼴). **`applyFlg='N'`** 으로 시작 |
| PUT | `/system/data-fields/{fieldKey}` | `{name,desc?}` (`category` 받고 버림) | 수정된 항목. key 는 바꿀 수 없다 |
| DELETE | `/system/data-fields/{fieldKey}` | — | `{success,fieldKey,deletedDeptPerms,deletedAttrs}` |
| POST | `/system/data-fields/{fieldKey}/attrs` | `{attrName,remark?}` | `{fieldKey,attrName,attrs[]}` |
| DELETE | `/system/data-fields/{fieldKey}/attrs/{attrName}` | — | `{fieldKey,attrName,attrs[]}` |
| PATCH | `/system/data-fields/{fieldKey}/apply` | `{on:true|false}` | `{fieldKey,applyFlg,changed}` |

규칙과 오류
- `fieldKey` — 소문자로 시작, 소문자·숫자·`_`·`-`, 2~30자. 어긋나면 400 `E-VALID-001`(`field: fieldKey`). 같은 key 는 409 `E-RULE-001`.
- `name` 필수 50자 이내, `desc` 300자 이내. 분류(`category`, 공통코드 `DATA_FIELD_CATEGORY`)는 판정·관리에 쓰이지 않아 2026-10-07 에 없앴다 — 옛 화면이 보내도 400 없이 버리고 응답에도 내지 않는다.
- `attrName` — JSON 키 꼴(`^[A-Za-z_$][A-Za-z0-9_$]{0,59}$`), 대소문자 구분. 어긋나면 400(`field: attrName`).
- **`attrName` 중복은 409 `E-RULE-001`** — 다른 항목에 붙어 있으면 `"이미 단가·금액에 등록된 필드명입니다. [unitPrice]"`, 같은 항목이면 `"이미 이 항목에 등록된 필드명입니다. [...]"`.
  저장 전에 먼저 보고, 동시 등록으로 DB UNIQUE 에 걸려도 다시 조회해 같은 409 를 낸다(500 이 나가지 않는다).
- **삭제 409** — 기본 7개 항목(`qty yield price customer plan mold worker`)은 서버 판정 코드가 key 를 직접 쓰므로 삭제할 수 없다(끄려면 apply OFF).
  알림 조건(`tb_alm_cond`) · 지표 기준(`tb_met_metric_std`) · 보고서 양식 필드(`tb_rpt_form_field`) · 문서 태그(`vec.tb_doc_data_field`)가 참조하는 항목도 409 에 건수를 적어 준다.
  그 밖의 항목은 삭제되고 부서 권한(`tb_sys_dept_data_perm`)·응답 필드명은 FK CASCADE 로 함께 지워진다(응답에 건수).
- 없는 항목·필드명은 404 `E-NOTFOUND`.
- `PUT /system/data-perms` 의 `fieldKey` 검증이 코드 상수에서 **항목 표 기준**으로 바뀌어 새 항목에도 부서 권한을 줄 수 있다. 매트릭스·계정별 적용 결과·통합관리자 전체 허용 목록도 항목 표 기준이다.

감사 — 모든 변경은 `ax.tb_sys_perm_log` `act_cd='DATA_PERM'` `target_kind_cd='FIELD'` `target_nm=fieldKey` 로 남고(등록·수정·삭제·필드명 등록/해제·적용 ON/OFF),
`ax.tb_log_audit` 에 `PERM_CHANGE`(`menu_id=sys-data`, `field_key`) 한 건씩 남는다. 표는 늘리지 않았다.

### 필드명 영향 범위 미리보기(`attr-usage`)는 만들지 않았다
서버는 어떤 응답에 어떤 필드명이 나가는지 런타임에 세지 못한다(응답 키가 코드 곳곳의 Map 리터럴이라 카탈로그가 없다).
WEB 의 `endpoints.js` 카탈로그로 세는 쪽이 맞다. 등록된 필드명이 어느 항목에 붙어 있는지는 409 응답과 `GET /system/data-fields` 의 `attrs` 로 알 수 있다.

## 2. `GET /auth/me` — `dataFields`

```json
"dataPerms":  ["qty","worker"],
"blindFields": ["yield","price","customer","plan","mold"],
"dataFields": [
  {"key":"price","name":"단가·금액","attrs":["unitPrice","unitCost","amount"]}
]
```
- `dataFields` 는 **`use_flg='Y' AND apply_flg='Y'`** 항목만. `attrs` 가 비어 있어도 항목이 켜져 있으면 나온다.
- 분류(`category`·`categoryNm`)는 2026-10-07 에 없앴다(응답에서 빠짐).
- `dataPerms`·`blindFields` 도 코드 상수 7개가 아니라 사용 중 항목 표 기준이다(미적용 항목도 들어간다 — 권한 자체는 항목이 켜지기 전에 채우는 것이 2단계 순서라서).
- 매 호출 DB 를 읽는다(캐시 없음). 화면 반영 시점이 재로그인이라 무효화 장치는 두지 않았다.

## 3. `findDataFields()` — `tb_sys_data_field_column` → `tb_sys_data_field_attr`

`SystemUserRepository.findDataFields()` 가 `string_agg` 하던 표를 바꿨다. 이제 API 어디에도 `tb_sys_data_field_column` 참조가 없다 → **V34 드롭 가능**(DB 담당 회신용).
같은 메서드가 `GET /system/data-fields`(확장 응답) · 데이터 권한 매트릭스 · 적용 미리보기 · 계정별 결과의 항목 목록을 낸다.

## 4. 응답 마스킹(MaskingSupport)은 그대로다

지시서 4절대로 **이번 회차에는 끄지 않았다.** WEB 자동 마스킹이 붙었다는 알림을 받은 뒤 정리한다.
그때 손댈 자리: `MaskingSupport.on/check/applyTo` 호출 39곳(ProductionService · QualityDefectService · ReportService · ScrapReportService · AiBriefingInput · 엑셀 생성기)과
응답 `masked` 배열 · 다운로드 이력 `blind_cnt`. AI 브리핑 프롬프트 입력 마스킹(`AiBriefingInput.of`)은 화면이 가릴 수 없는 자리라 끄면 안 된다.

## 5. AI 답변 — 질의는 막지 않고 결과에서 값만 가린다 (2026-09-16 요청자 결정으로 변경)

처음 구현(질의 차단 `denied`)은 요청자 지시 「ai 질의 차단은 하지말고, ai 결과의 내용 중 필터가 되어야 하는 단어만 필터링해서 결과를 보도록」에 따라 걷어냈다.
**데이터 권한을 이유로 한 `denied` 는 없다.** 권한 없는 항목이 섞인 질의도 정상으로 답하고, 출력 단계에서 값만 「비공개」로 바꾼다(`AiChatService` · `DataFieldService`).

| 지점 | 동작 |
| :--- | :--- |
| 문장 `answerHtml` | [`maskText`] 라벨 후보 = **항목 표**에서 뽑은 항목명 전체 · `·`/`,`/`()` 조각(2자 이상) · 응답 필드명(대소문자 무시). 고정 목록이 없어 새 항목도 배포 없이 걸린다(WEB 9ca5bf8 와 같은 원칙). 값 토막 = 라벨 뒤 24자 안의 숫자+단위(12,400원 · 3.2%) 또는 코드형 식별자(L260824-031 · PR-03 · W-1023). 라벨은 남기고 값만 「비공개」. 표에서 가린 값이 문장에 풀어 쓰여 있으면 그것도. 일반 한글 낱말은 값 후보가 아니다. 예) 「8월 평균 단가는 12,400원입니다」 → 「8월 평균 단가는 비공개입니다」 |
| 근거 문서 `sources[]` | 검색에서 **빼지 않는다**. 권한 없는 항목이 태그된 문서(`vec.tb_doc_data_field`)는 발췌(`snippet`)를 「비공개 항목(단가·금액)이 포함된 자료입니다. 발췌는 표시하지 않습니다.」로 바꾸고 제목·쪽·날짜는 남긴다(`blinded:true`, `blindTags[]`). 태그가 없는 문서는 발췌·제목에 `maskText` 만 태운다 |
| 표 블록 `blocks[]` | 그대로 — 열 이름을 attr 표에서 찾아 `blindColumns[i]` 를 채우고 권한 없는 열은 값을 `null` 로. 가린 값은 문장 마스킹에도 쓴다 |
| 응답 `blindFields` | 이 사용자에게 가려지는 적용 중 항목 key. 화면이 「어떤 항목이 가려졌는지」 한 줄로 알린다. `blindAppliedCnt` 는 가린 칸·문장 치환·발췌 가림의 합 |
| 감사 | 가린 것이 있으면 `tb_log_audit` `MASK` / `result_cd=MASKED` / `masked_cnt` 한 건(질의는 막지 않았으므로 BLIND 가 아니다). `tb_ai_chat_log.blind_applied_cnt` 에도 같은 수 |

근거 문서를 왜 검색에서 빼지 않는가 — 빼면 답이 가려지는 게 아니라 **틀려진다**(근거 없음 → 엉뚱한 답). 검색은 그대로 두고 출력에서 가리는 쪽을 택했다.
다만 태그된 문서의 발췌는 원문이라 값이 숫자가 아니어도(고객사명·작업자명) 샐 수 있어 문장 치환이 아니라 **발췌 전체를 가린다.** 제목·쪽은 남겨 인용은 유지된다.

한계 — 숫자가 아닌 값(고객사명 등)은 키워드 규칙으로는 못 찾고 표에서 가린 값과 같은 문자열일 때만 가려진다. 지금 답변 문장은 질문 원문 echo 와 문서 제목만이라 수치가 문장에 실리는 경로가 없고,
sLLM 이 문장을 만들게 되면 그 출력도 반드시 `maskText` 를 태운다(`AiChatService` 머리말에 고정). 라벨이 값보다 앞에 오는 문장만 잡힌다 — 「L260824-031 로트의 이력」처럼 값이 먼저 오면 규칙 1 로는 못 잡고, 그 값이 표에서 가린 값일 때만(규칙 2) 가려진다. 서버가 값을 만드는 자리에서 가리는 것이 정확하므로 수치 생성 경로는 항목 판정을 거친 값만 문장에 넣는다.
용어 정규화가 숫자를 바꿔 놓으면(로컬 용어 사전에 「2→R2」 치환이 있어 실측 `12,400`→`1R2,400`) 값 인식이 끊긴다 — 용어 사전 데이터 문제라 여기서 고치지 않았다.

## 6. 기존 7개 항목의 응답 필드명 — 초기값 제안

V33 은 attr 행을 넣지 않았다(「API 가 실제 응답을 보고 채운다」). 서버 코드의 마스킹 지점(`mask.on` · `mask.applyTo` · `mask.check` 분기)에서 뽑은 응답 키다.
WEB 화면에서 등록해도 되고, 아래 SQL 을 DB 담당이 V34 에 넣어도 된다(전역 UNIQUE 라 `ON CONFLICT DO NOTHING`). **이번 회차에는 넣지 않았다** — 어느 쪽이 넣을지 요청자 확인.

```sql
INSERT INTO ax.tb_sys_data_field_attr (field_key, attr_name, remark, ins_user) VALUES
 ('qty','qty','공통',NULL),('qty','inputQty','실적·모니터링',NULL),('qty','okQty','실적·불량',NULL),('qty','ngQty','실적·불량',NULL),
 ('qty','totalQty','불량 현황',NULL),('qty','prevNgQty','불량 현황',NULL),('qty','hourlyThroughput','생산 모니터링',NULL),('qty','totalThroughput','생산 모니터링',NULL),
 ('qty','todayQty','생산 모니터링',NULL),('qty','sampleQty','AOI',NULL),('qty','shipQty','출하 계획 보고서',NULL),('qty','lrrQty','LRR 보고서',NULL),
 ('qty','dayActual','아침회의',NULL),('qty','dayTarget','아침회의',NULL),('qty','weekActual','아침회의',NULL),('qty','weekTarget','아침회의',NULL),('qty','ratedActual','아침회의',NULL),
 ('yield','yield','공통',NULL),('yield','defectRate','공통',NULL),('yield','momChange','불량 현황',NULL),('yield','rate','보고서',NULL),
 ('yield','avgRate','보고서',NULL),('yield','weekRate','아침회의',NULL),('yield','lrrRate','LRR 보고서',NULL),('yield','yoyImprovement','수율 보고서',NULL),
 ('customer','customer','공통',NULL),('customer','customerCd','공통',NULL),
 ('mold','moldCd','생산 모니터링',NULL),('mold','moldNm','생산 모니터링',NULL),('mold','spec','생산 모니터링',NULL),('mold','cavity','생산 모니터링',NULL),('mold','strokeSpeed','생산 모니터링',NULL)
ON CONFLICT (attr_name) DO NOTHING;
```
`price`·`plan`·`worker` 는 응답 키가 조건 분기(`planAllowed`·`priceAllowed`) 안에 흩어져 있어 위 목록에 없다 — 화면 쪽 카탈로그로 보태는 것이 정확하다.
`name`·`code`·`value` 같은 흔한 이름은 전 화면을 가리므로 등록하지 않는다.
**`worker` 에 `empNo`·`userId`·`by`·`byEmpNo` 를 붙이지 않는다** — 계정 관리 · 승인 대기 · 변경 이력 · 감사 로그 · 다운로드 이력이 사번을 행 키로 쓰고 있어
`worker` 권한이 없는 관리자의 관리 화면이 통째로 가려진다(2026-09-16 WEB 확인). 위 목록(32개)에는 사번 키가 없다.

## 7. 바뀐 파일

| 파일 | 변경 |
| :--- | :--- |
| `repository/DataFieldRepository.kt` (신규) | 항목·필드명 CRUD · 적용 중 attr 맵 · 참조 건수 |
| `service/DataFieldService.kt` (신규) | 검증 · 409 · 삭제 가드 · 감사 · 카탈로그(blindColumns · 차단 키워드 · 문서 필터 키) |
| `controller/DataFieldController.kt` (신규) · `model/request/DataFieldRequests.kt` (신규) | 6개 엔드포인트 · DTO 3종 |
| `repository/SystemUserRepository.kt` | `findDataFields()` — attr 표 · applyFlg |
| `service/SystemUserService.kt` | 매트릭스·권한 변경·계정별 결과의 항목 기준을 항목 표로 |
| `service/AuthService.kt` · `model/response/AuthResponses.kt` | `/auth/me` `dataFields`(`DataFieldInfo`) · dataPerms/blindFields 항목 표 기준 |
| `service/AiChatService.kt` · `repository/AiChatRepository.kt` | 출력 마스킹(문장 `maskText` · 발췌 `maskHit` · 표 블록 blindColumns/null) · `blindFields`·`blindAppliedCnt` · 문서 태그 조회. 데이터 권한 `denied` 없음 |
| `docs/REQUEST_BODY_CONTRACT.md` | DTO 3종 추가 |
| 테스트 `DataFieldRuntimeTest`(8) | 등록·409 메시지(사전/경쟁)·삭제 가드·적용 스위치·blindColumns·문장 마스킹·발췌 가림·키워드 |

## 8. 검증 (로컬 8080, 2026-09-16 — 임시 항목 `zz_e2e` 만 사용, 전부 삭제)

- `./gradlew test` 207건 통과.
- 전산팀 10004 로 등록 → `applyFlg=N`, `attrs=[]` · 같은 key 409 · `Bad Key` 400(fieldKey) · 분류 `NOPE` 400(허용 값 안내) · 모르는 본문 키 400.
- 필드명 `zzE2eAmount` 등록 → 같은 이름을 `price` 에 등록 시 **409 "이미 E2E금액에 등록된 필드명입니다. [zzE2eAmount]"**, 같은 항목 재등록 409, `bad-name` 400, 없는 항목 404.
- `/auth/me`: 적용 전 `dataFields` 에 없음(`blindFields` 에는 있음) → apply ON → `{"key":"zz_e2e",…,"attrs":["zzE2eAmount"]}` 즉시(재로그인 없이도 API 는 DB 를 읽는다).
- AI 질의(식별자 값): 10004 로 `단가 코드 PR-03 은 얼마` → 「단가 코드 비공개 은 얼마」, `금형 T-0417 실적 얼마` → 「금형 비공개 실적 얼마」(각 `blindAppliedCnt=1`).
- AI 질의(차단 제거 뒤, 전산팀 10004 = price·yield·customer·mold·plan 없음): `8월 평균 단가 12,400원은 얼마` → `intent=metric`, `deniedField` 없음, echo 문장의 값이 「비공개」(`blindAppliedCnt=1`) · `oil contamination` → 근거 2건 모두 `blinded:true`(`blindTags` customer·mold(·plan)), 발췌 대신 안내문, `blindAppliedCnt=8`, `tb_log_audit` `MASK/MASKED/8` · 통합관리자 10000 은 같은 질의에 `blindFields=[]`, 발췌 원문 그대로.
- `PUT /system/data-perms {fieldKey:"zz_e2e"}` 200(항목 표 기준) → `/auth/me.dataPerms` 에 반영 · 없는 key 400 · 매트릭스 `fields` 에 포함, 통합관리자 부서 허용 8개.
- 삭제: `qty` 409(기본 항목) · 없는 key 404 · 필드명 해제 후 재해제 404 · 항목 삭제 200 `deletedDeptPerms=1` → 표·권한·필드명 0행.
- 이력: `tb_sys_perm_log` DATA_PERM 6행(등록·필드명 등록·수정·적용 ON·필드명 해제·삭제), `tb_log_audit` PERM_CHANGE 7행.
- 실제 항목 7개의 데이터·권한은 건드리지 않았다. 로컬 `tb_ai_chat_log` 에 검증 질의 7행이 남아 있다(10004, 로컬 전용).

## 9. 항목 단위 권한 (V82, 2026-10-07)

WEB 데이터 접근 권한 화면을 「항목 × 부서」 표 하나로 바꿨다(사용자 결정 — 묶음 없이 항목마다 부서 체크). 설계: WEB `docs/DATA_ITEM_MASKING_DESIGN_20261007.md`.

**DB (V82, 로컬 dwjedb 적용 2026-10-07)**
- 기본 7종에 든 응답 필드명 35개를 필드명마다 항목(`i_<필드명 소문자>`)으로 나누고 부서 권한을 원래 묶음에서 복사했다 — 적용 직후 바뀌는 사람은 없다.
- 코드만 가리던 같은 뜻의 다른 이름 54개를 등록했다(불량 수량 = ngQty · failCnt · ngCnt · prodNgCnt …, 불량률 = defectRate · failRate · ngRate …, 새 항목 24개). 화면마다 뜻이 다른 이름(rate · ratio · total · cnt · value · actual · label · spec · name)은 넣지 않았다.
- `ax.tb_sys_data_attr_origin(attr_name, origin_key)` — 필드명이 원래 어느 기본 묶음 것인지. 필드명을 옮기거나 풀어도 남는다.
- 기본 7종 행과 그 부서 권한 행은 남긴다(알림 조건 · 지표 기준 · 문서 태그가 key 를 참조).

**판정 (`AuthRepository.findDataPermSets`)**
- 서버 코드는 여전히 기본 7종 key 로 가린다(`MaskingSupport` 약 150곳). 그 key 의 권한을 항목 권한에서 계산한다.
  - 느슨한(`canReadField`) — 그 묶음에서 온 필드명 중 하나라도 볼 수 있음. JSON 응답을 만드는 코드가 쓴다. 실제로 어느 키를 가릴지는 공통 마스킹(advice)이 항목 권한으로 정한다.
  - 엄격한(`canReadFieldStrict`) — 그 묶음에서 온 필드명을 모두 볼 수 있음. 응답 키가 없는 출력이 쓴다: AI 원인·처방 / 브리핑(`guardStrict`), AOI 브리핑, AI 도구 · 근거 문장(`AiDataToolService`), AI 표 블록 칸(`AiChatService`), 용어 치환 · 용어 가림, 문서 태그(`blindKeysFor`), 고객사명 치환, 엑셀 extra 열, 필드명이 아직 기본 key 에 직접 붙은 경우(`DataFieldMaskingAdvice.readableForMasking`).
  - 필드명 「볼 수 있음」 = 어느 항목에도 없음 · 그 항목 미적용 · 부서가 그 항목 허용. 기록표가 없거나 그 묶음 기록이 없으면 예전처럼 기본 key 행으로 판정.
- 화면마다 뜻이 다른 이름으로 나가는 값 — 불량 현황 유형표(`/quality/defects/by-type`)의 `cnt`(불량 수량) · `ratio`(불량 비중)는 `DataFieldService.canReadAttr` 로 그 뜻의 항목 필드명(ngQty · sharePct)에 맞춰 가린다.
- 알려진 한계 — 그 밖의 뜻이 다른 이름(rate · total · value · actual · segments 값 등)은 항목에 넣지 않았으므로, 그 묶음 항목 중 하나라도 볼 수 있으면 보인다. 같은 방법(canReadAttr)으로 자리마다 고치거나, 응답 키 이름을 바꾸거나, 범위 별칭(설계 5.1)을 넣어야 풀린다. 자리 목록은 2026-10-07 전수 조사(WEB 세션 기록) 기준.

**API**
- `PUT /api/v1/system/data-fields/item-perms` `{name, attrs[], perms{deptId: boolean}}` — 한 트랜잭션.
  필드명들이 이미 「그 필드명만 가진 항목」 하나에 있으면 재사용, 아니면 새 항목(`i_<시각36진><난수>`, 적용 켬)을 만들고 옮긴다(새 항목의 부서 권한 = 필드명이 있던 곳을 모두 볼 수 있던 부서만 허용). 비게 된 항목은 지운다(기본 7종 · 참조 있음 제외). 그다음 perms 반영. 감사 `PERM_CHANGE` · `tb_sys_perm_log DATA_PERM`.
- 시험: `./gradlew test` 556건 통과(DataFieldRuntimeTest blindKeysFor 기대값 · WritePermissionTest 목 갱신). 실측: WEB `tests/system/data-item-perm-live.cjs` — 전산팀에게 불량 수량만 숨기면 /auth/me 의 qty 는 남고, /dashboard/ai/summary 의 ngQty 만 null(okQty · todayQty 값 그대로), 되돌림.
- 되돌리기: `rollback/V82__down.sql` (필드명을 원래 묶음으로 돌리고 V82 항목 · 기록표 삭제).
