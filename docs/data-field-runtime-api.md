# 데이터 접근 항목 운영 중 추가 — API 계약 (2026-09-16, V33)

데이터 접근 권한 항목이 7개 고정이던 것을 WEB 화면에서 **운영 중에 추가**할 수 있게 한다.
「`unitPrice` 라는 응답 값이 `price` 항목이다」는 연결을 소스 코드에서 DB(`ax.tb_sys_data_field_attr`)로 옮겼다.
DB 는 V33(DB 담당), API 는 여기. 연결 열쇠는 **API 응답 JSON 필드명**이고, 반영 시점은 **재로그인**이다.

## 1. 항목 CRUD — `/api/v1/system/data-fields`

편집 권한은 데이터 접근 권한 화면과 같다(`sys-data` — 전산팀 · 통합관리자).

| 메서드 | 경로 | 본문 | 응답 `data` |
| :--- | :--- | :--- | :--- |
| GET | `/system/data-fields` | — | `items[{key,name,desc,category,categoryNm,applyFlg,sortSeq,attrs[]}]` — 사용 중 항목 전체(미적용 포함) |
| POST | `/system/data-fields` | `{fieldKey,name,desc?,category?}` | 등록된 항목(위 한 건 꼴). **`applyFlg='N'`** 으로 시작 |
| PUT | `/system/data-fields/{fieldKey}` | `{name,desc?,category?}` | 수정된 항목. key 는 바꿀 수 없다 |
| DELETE | `/system/data-fields/{fieldKey}` | — | `{success,fieldKey,deletedDeptPerms,deletedAttrs}` |
| POST | `/system/data-fields/{fieldKey}/attrs` | `{attrName,remark?}` | `{fieldKey,attrName,attrs[]}` |
| DELETE | `/system/data-fields/{fieldKey}/attrs/{attrName}` | — | `{fieldKey,attrName,attrs[]}` |
| PATCH | `/system/data-fields/{fieldKey}/apply` | `{on:true|false}` | `{fieldKey,applyFlg,changed}` |

규칙과 오류
- `fieldKey` — 소문자로 시작, 소문자·숫자·`_`·`-`, 2~30자. 어긋나면 400 `E-VALID-001`(`field: fieldKey`). 같은 key 는 409 `E-RULE-001`.
- `name` 필수 50자 이내, `desc` 300자 이내, `category` 는 공통코드 `DATA_FIELD_CATEGORY`(QTY·QUALITY·COST·CUSTOMER·PLAN·EQUIP·HR) 안의 값 — 아니면 400 에 허용 값 안내.
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
  {"key":"price","name":"단가·금액","category":"COST","categoryNm":"원가","attrs":["unitPrice","unitCost","amount"]}
]
```
- `dataFields` 는 **`use_flg='Y' AND apply_flg='Y'`** 항목만. `attrs` 가 비어 있어도 항목이 켜져 있으면 나온다.
- `category` 는 코드(`COST`), `categoryNm` 이 표시명(`원가`) — 지시서 예시의 `"category":"원가"` 는 `categoryNm` 으로 받으면 된다.
- `dataPerms`·`blindFields` 도 코드 상수 7개가 아니라 사용 중 항목 표 기준이다(미적용 항목도 들어간다 — 권한 자체는 항목이 켜지기 전에 채우는 것이 2단계 순서라서).
- 매 호출 DB 를 읽는다(캐시 없음). 화면 반영 시점이 재로그인이라 무효화 장치는 두지 않았다.

## 3. `findDataFields()` — `tb_sys_data_field_column` → `tb_sys_data_field_attr`

`SystemUserRepository.findDataFields()` 가 `string_agg` 하던 표를 바꿨다. 이제 API 어디에도 `tb_sys_data_field_column` 참조가 없다 → **V34 드롭 가능**(DB 담당 회신용).
같은 메서드가 `GET /system/data-fields`(확장 응답) · 데이터 권한 매트릭스 · 적용 미리보기 · 계정별 결과의 항목 목록을 낸다.

## 4. 응답 마스킹(MaskingSupport)은 그대로다

지시서 4절대로 **이번 회차에는 끄지 않았다.** WEB 자동 마스킹이 붙었다는 알림을 받은 뒤 정리한다.
그때 손댈 자리: `MaskingSupport.on/check/applyTo` 호출 39곳(ProductionService · QualityDefectService · ReportService · ScrapReportService · AiBriefingInput · 엑셀 생성기)과
응답 `masked` 배열 · 다운로드 이력 `blind_cnt`. AI 브리핑 프롬프트 입력 마스킹(`AiBriefingInput.of`)은 화면이 가릴 수 없는 자리라 끄면 안 된다.

## 5. AI 답변 — 권한 없는 항목 값을 넣지 않는다

세 지점에서 서버가 막는다(`AiChatService`).

1. **질의 차단(denied)** — 코드 키워드(`RESTRICTED_KEYWORDS`: 단가·금액·고객사·출하계획·작업자 …)에 더해 **항목 표**의 항목명 전체와 `·`/`/` 조각(2자 이상), 응답 필드명(대소문자 무시)이 키워드다.
   적용 중 항목 중 사용자가 열람할 수 없는 것이 질의에 나오면 답변 없이 `intent=denied`, `deniedField=key`, 감사 `MASK/BLIND`. 새 항목도 apply 를 켜면 배포 없이 걸린다.
   원문과 용어 정규화 문장을 **둘 다** 본다 — 실측에서 용어 치환이 `E2E`→`ER2E` 로 바꿔 놓아 정규화 문장만 보면 빠졌다.
   항목명은 공백으로 나누지 않는다(「정보」「항목」 같은 일반어로 막지 않기 위해). 기본 항목의 이름 조각(생산·출하 수량·수율·불량률·금형…)도 키워드가 되므로 그 항목 권한이 없는 사용자는 해당 질의가 denied 다.
2. **근거 문서 필터** — `vec.tb_doc_data_field` 에 사용자가 열람할 수 없는 적용 중 항목이 태그된 문서는 검색(`searchDocumentChunks`)에서 뺀다. 제목·발췌가 `answerHtml` 과 `sources` 에 그대로 실리기 때문이다. 응답 `blindFields` 에 그 항목 key 를 함께 준다.
3. **표 블록** — 블록의 `columns`(없으면 첫 행의 키)마다 `tb_sys_data_field_attr` 에서 항목을 찾아 `blindColumns[i]`(없으면 null)를 채우고, 권한 없는 열은 값을 `null` 로 보낸다. 가린 칸 수가 `blind_applied_cnt` 로 남는다.
   지금 블록은 `sources`(title·page·date) 하나라 실측 `blindColumns=[null,null,null]` 이다. 수치 표 블록이 생기면 같은 경로를 탄다.

`answerHtml` 은 질문 원문과 (걸러진) 문서 제목만으로 만들고 수치를 문장에 넣는 경로가 없다. 그런 경로를 새로 만들 때는 `DataFieldService.fieldOf(attrName)` 으로 항목을 찾아 `canReadField` 를 통과한 값만 쓴다(코드 주석에 고정).
카탈로그(`DataFieldService.attrFieldMap`)는 60초 캐시, 항목·필드명·적용 스위치를 바꾸면 즉시 비운다.

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

## 7. 바뀐 파일

| 파일 | 변경 |
| :--- | :--- |
| `repository/DataFieldRepository.kt` (신규) | 항목·필드명 CRUD · 적용 중 attr 맵 · 참조 건수 |
| `service/DataFieldService.kt` (신규) | 검증 · 409 · 삭제 가드 · 감사 · 카탈로그(blindColumns · 차단 키워드 · 문서 필터 키) |
| `controller/DataFieldController.kt` (신규) · `model/request/DataFieldRequests.kt` (신규) | 6개 엔드포인트 · DTO 3종 |
| `repository/SystemUserRepository.kt` | `findDataFields()` — attr 표 · category · applyFlg |
| `service/SystemUserService.kt` | 매트릭스·권한 변경·계정별 결과의 항목 기준을 항목 표로 |
| `service/AuthService.kt` · `model/response/AuthResponses.kt` | `/auth/me` `dataFields`(`DataFieldInfo`) · dataPerms/blindFields 항목 표 기준 |
| `service/AiChatService.kt` · `repository/AiChatRepository.kt` | 카탈로그 차단 · 문서 필터 · 표 블록 blindColumns/마스킹 · `blindFields` |
| `docs/REQUEST_BODY_CONTRACT.md` | DTO 3종 추가 |
| 테스트 `DataFieldRuntimeTest`(6) | 등록·409 메시지(사전/경쟁)·삭제 가드·적용 스위치·blindColumns·차단 키워드 |

## 8. 검증 (로컬 8080, 2026-09-16 — 임시 항목 `zz_e2e` 만 사용, 전부 삭제)

- `./gradlew test` 205건 통과.
- 전산팀 10004 로 등록 → `applyFlg=N`, `attrs=[]` · 같은 key 409 · `Bad Key` 400(fieldKey) · 분류 `NOPE` 400(허용 값 안내) · 모르는 본문 키 400.
- 필드명 `zzE2eAmount` 등록 → 같은 이름을 `price` 에 등록 시 **409 "이미 E2E금액에 등록된 필드명입니다. [zzE2eAmount]"**, 같은 항목 재등록 409, `bad-name` 400, 없는 항목 404.
- `/auth/me`: 적용 전 `dataFields` 에 없음(`blindFields` 에는 있음) → apply ON → `{"key":"zz_e2e",…,"attrs":["zzE2eAmount"]}` 즉시(재로그인 없이도 API 는 DB 를 읽는다).
- AI 질의: `zzE2eAmount 값이 얼마야?` → `denied / deniedField=zz_e2e`(응답 필드명 키워드) · `E2E금액 알려줘` → `denied / price`(코드 키워드 「금액」이 먼저) · 무관한 질의 → 정상, `blindFields=["zz_e2e"]`.
- `PUT /system/data-perms {fieldKey:"zz_e2e"}` 200(항목 표 기준) → `/auth/me.dataPerms` 에 반영 · 없는 key 400 · 매트릭스 `fields` 에 포함, 통합관리자 부서 허용 8개.
- 삭제: `qty` 409(기본 항목) · 없는 key 404 · 필드명 해제 후 재해제 404 · 항목 삭제 200 `deletedDeptPerms=1` → 표·권한·필드명 0행.
- 이력: `tb_sys_perm_log` DATA_PERM 6행(등록·필드명 등록·수정·적용 ON·필드명 해제·삭제), `tb_log_audit` PERM_CHANGE 7행.
- 실제 항목 7개의 데이터·권한은 건드리지 않았다. 로컬 `tb_ai_chat_log` 에 검증 질의 7행이 남아 있다(10004, 로컬 전용).
