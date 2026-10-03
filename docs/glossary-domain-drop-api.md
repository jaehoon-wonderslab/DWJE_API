# 용어 사전 「분류」 삭제 · 고객사 가림을 용어 단위로 (2026-10-03, V75)

용어 사전에서 분류(domain)를 없앴다 — DB 표·컬럼, API 필드·파라미터, 엑셀 템플릿·업로드·내려받기 열.
분류에 걸려 있던 고객사 가림(결정 R-18, V66)은 **용어별 가림 표시**(`ax.tb_gls_term.data_field_key`)로 옮겨 그대로 유지한다.
V75 가 분류의 값을 용어로 옮기므로, 고객사 데이터 권한(`customer`)이 없는 열람자에게 가려지는 용어는 V75 전과 같다(로컬 사용 중 21개).

## 1. 순서

API 와 V75 는 붙여서 배포한다. 새 API 는 V75 전에는 `data_field_key` 가 없어 용어 목록·상세가 500, 옛 API 는 V75 뒤에 `domain_id` 를 읽다가 500.
DB 상세는 `Postgresql 스키마/변경내역_20261003_용어분류삭제.md`, 마이그레이션 `db/V75__gls_domain_drop_term_data_field.sql` · `db/rollback/V75__down.sql`.

## 2. 없앤 것

| 대상 | 전 | 후 |
| :--- | :--- | :--- |
| `GET /api/v1/glossary/domains` | 분류 선택지 | **삭제** (404) |
| `GET /glossary/summary` | `domainCnt` · `byDomain`(분류별 현황) | 둘 다 없음 |
| `GET /glossary/terms` | `domainCd` 파라미터, 행의 `domainId` · `domain` | 파라미터 없음(보내도 무시), 행에 분류 없음. 정렬은 분류 순 → **용어 이름 순** |
| `GET /glossary/terms/{termId}` | `domainId` · `domain`, 관련 용어의 `domain` | 없음 |
| 관련 용어 `reasonCd` | `SAME_DOMAIN_NAME`(같은 분류에서 이름이 서로를 품음) | **`NAME_OVERLAP`**(분류 조건 없이 이름이 서로를 품음) |
| `GET /glossary/changes` | (내부) `domainId` | 없음. V75 전 이력의 before/after 에는 `domainNm` 이 남아 있다(지우지 않음) |
| `POST /glossary/terms/export` | 「분류」 열, 본문 `domainCd` | 열 없음, `domainCd` 는 받고 버림 |
| `POST/PUT /glossary/terms` 본문 | `domainCd` 필수(없으면 400) | `domainCd` 는 받고 버림(옛 화면 호환, 400 아님) |
| 엑셀 업로드 | 머리글 `… · 분류 · …`, 새 용어 분류 필수 | 머리글 `공식 용어* · 뜻* · 고객사 정보 · 유사어` — `docs/glossary-import-api.md` |

## 3. 더한 것 — `customerInfo`

| 위치 | 내용 |
| :--- | :--- |
| `POST /glossary/terms` 본문 | `customerInfo: boolean` 선택, **생략하면 false**. true 면 `data_field_key='customer'` |
| `PUT /glossary/terms/{termId}` 본문 | `customerInfo: boolean` 선택, **생략하면 지금 값을 그대로 둔다**(옛 화면이 보내지 않아 가림이 풀리지 않게). false 면 가림 해제 |
| 목록·상세 응답 | `customerInfo: boolean` — **관리 화면(sys-gloss) 호출자에게만**, 조회 화면(gloss-view)만 가진 사람에게는 `null` |
| 변경 이력 | 용어 등록·수정·되살림의 after/before 에 `customerInfo` (전에는 `domainNm`) |

권한은 기존 공식 용어 편집과 같다 — 통합관리자만(`requireWrite(sys-gloss)` → `requireSuperAdmin`).

## 4. 가림 판정 (R-18)

용어의 `data_field_key` 가 열람자가 읽을 수 없는 데이터 항목이면 가린다. 통합관리자는 가리지 않는다. 적용 범위는 V75 전과 같다:
목록(「비공개 용어」 행, 검색어에 걸리지 않음) · 상세(관련 용어 포함) · 변경 이력(이름·전후 값) · 내려받기(「비공개」 칸, blindCnt) ·
덕반장 AI [용어] 블록과 정규화 미리보기 · 유사어 등록·수정·삭제와 업로드(가려진 용어는 409/ERROR `데이터 접근 권한이 없는 용어라 고칠 수 없습니다.`).

## 5. 응답 예

```json
// GET /api/v1/glossary/summary
{"termCnt":571,"variantCnt":280,"myVariantCnt":0,"noVariantTermCnt":459,"canWriteVariant":true,"canEditTerm":true,
 "riskVariantCnt":30,"lastChangedAt":"2026-10-03 21:44:06","lastChangedBy":"관리자"}

// GET /api/v1/glossary/terms?keyword=LGIT&size=2 (통합관리자)
{"items":[{"termId":549,"term":"3PRB00690E","definition":"LGIT Part N.O","createdAt":"2026-09-10 12:17:39",
  "updatedAt":"2026-09-10 12:17:39","customerInfo":false,"variants":[],"blinded":false}, …]}

// 고객사 권한 없는 관리 화면 계정이 본 고객사 정보 용어
{"termId":…,"term":"비공개 용어","definition":null,"variants":null,"blinded":true,"customerInfo":true,"createdAt":…,"updatedAt":…}

// POST /api/v1/glossary/terms  {"term":"…","definition":"…","customerInfo":true}
{"termId":1289,"term":"…"}
```

## 6. 시험

`GlossaryCustomerInfoTest`(신규 3건 — 등록·수정 customerInfo, 응답에 분류 없음, HTTP 본문·domains 404),
`Phase5DecisionsDbTest`(R-18 가림을 용어 기준으로), `GlossaryImportTest`(새 머리글·예전 머리글 400·고객사 정보 오류), 그 밖 용어 시험 갱신.
