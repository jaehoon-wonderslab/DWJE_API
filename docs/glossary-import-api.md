# 용어 사전 엑셀 업로드 · 업로드용 템플릿 (2026-10-03)

용어 사전 관리(/system/glossary, 메뉴 `sys-gloss`)에서 엑셀로 용어·유사어를 한꺼번에 등록한다.
화면 흐름은 파일 고르기 → 미리보기(`dryRun=true`) → 확인 → 등록(`dryRun=false`)이다. DB 변경은 없다(용어·유사어 표 그대로).

| 메서드 | 경로 | 권한 | 응답 |
| :--- | :--- | :--- | :--- |
| GET | `/api/v1/glossary/import/template` | `sys-gloss` 접근 | xlsx 첨부 `glossary_import_template.xlsx` |
| POST | `/api/v1/glossary/import` | `sys-gloss` 쓰기(접근 && 미배정 아님) | JSON (아래) |

## 1. 템플릿

- 시트 「용어」: 머리글 `공식 용어*` · `뜻*` · `분류` · `유사어` + 예시 2행. 예시 행은 공식 용어가 `(예시)` 로 시작하며, 지우지 않고 올려도 읽지 않는다.
  「분류」 열에는 안내 시트의 분류 목록으로 목록 선택(경고만, 막지는 않음)이 걸려 있다.
- 시트 「안내」: 규칙 8줄, 글자 수 제한(공식 용어 50 · 뜻 500 · 유사어 50 — 등록 폼과 같다), 분류 목록(지금 쓰는 분류 이름).
- 내려받기 이력: 다른 서버 생성 내려받기와 같이 서버가 남긴다(`reportNm=용어 사전 업로드 템플릿`, menuId `sys-gloss`, rowCnt 0).

## 2. 업로드

multipart: `file`(xlsx, 5MB · 데이터 1,000행 이하), `dryRun`(생략 시 true).

### 파일 검사 — 400 `field=file`

| 상황 | 메시지 |
| :--- | :--- |
| 파일 없음 | `업로드할 엑셀 파일이 없습니다.` |
| 5MB 초과 | `파일이 너무 큽니다. 최대 5MB 입니다.` |
| 확장자·형식이 xlsx 아님, 매크로 포함 | 업로드 리포트(UPD-03)와 같은 검사·문구 |
| 머리글이 템플릿과 다름 | `템플릿의 머리글과 다릅니다. 1행은 [공식 용어* · 뜻* · 분류 · 유사어] 이어야 합니다. (파일: …)` — 앞뒤 공백과 `*` 는 무시 |
| 1,000행 초과 | `한 번에 1,000행까지 올릴 수 있습니다. 파일을 나눠 올려 주십시오.` |
| 읽을 행 없음(빈 행·예시 행만) | `등록할 행이 없습니다. 「용어」 시트 2행부터 적어 주십시오.` |

시트 「용어」가 없으면 첫 시트를 읽는다. 빈 행은 건너뛰고 `totalRows` 에도 넣지 않는다.

### 행 판정

| 경우 | action | 처리 |
| :--- | :--- | :--- |
| 공식 용어가 이미 있음(대소문자·앞뒤 공백 무시) | `EXISTING_TERM` | 유사어만 더한다. 뜻·분류는 바꾸지 않는다 — `notes: ["기존 용어 — 뜻·분류는 바꾸지 않음"]` |
| 같은 파일 앞 행의 용어 | `EXISTING_TERM` | 앞 행의 용어에 유사어만 더한다 — `notes: ["같은 파일 3행의 용어 — 유사어만 더함"]` |
| 새 용어, 통합관리자 | `NEW_TERM` | 뜻·분류 필수. 삭제된 같은 이름이 있으면 개별 등록과 같이 되살린다(`restored: true`, 예전 유사어도 돌아옴) |
| 새 용어, 그 밖의 계정 | `ERROR` | `공식 용어 등록은 통합관리자만 할 수 있습니다.` |
| 공식 용어 빈칸·50자 초과, 새 용어의 뜻 빈칸·500자 초과, 분류 빈칸·없는 분류 | `ERROR` | `errors[].field` = term · definition · domain |
| 데이터 접근 권한이 없는 분류의 기존 용어 | `ERROR` | `데이터 접근 권한이 없는 분류의 용어라 고칠 수 없습니다.` (개별 유사어 등록과 같다) |

유사어(쉼표·줄바꿈 구분)는 개별 등록과 같은 규칙을 쓴다. 어긋나면 **그 낱말만** 건너뛰고 행은 ERROR 가 아니다(`variantsSkipped[].reason`):
같은 파일 안 중복, 이 파일이 등록하는 공식 용어와 같은 낱말, 2자 미만·50자 초과, 숫자·날짜 표현, 공식 용어와 같은 낱말, 이미 등록된 유사어(어느 용어에 붙었는지 함께).
다른 공식 용어 안에 들어 있는 낱말은 개별 등록처럼 `warnings` 로 알린다.

### 등록(`dryRun=false`)

- 미리보기와 같은 판정을 한 번 더 하고, ERROR 행을 뺀 나머지를 행 단위로 쓴다. 미리보기와 등록 사이에 사전이 바뀌지 않았다면 결과가 같다.
- 공식 용어 등록·되살림, 유사어 등록마다 사전 변경 이력(`tb_gls_change_log`) 1행과 감사 1줄 — 개별 등록과 같다.
- 한 트랜잭션이다. 그 사이 다른 사람이 같은 이름을 넣어 유니크에 걸리면 전체가 409 로 되돌아간다(개별 등록과 같은 문구).
- `NEW_TERM` 행의 `termId` 는 미리보기에서 null, 등록에서 새 ID.

### 응답 예 (미리보기, 통합관리자)

```json
{
  "success": true,
  "message": "미리보기입니다. 아직 등록하지 않았습니다.",
  "data": {
    "dryRun": true, "fileName": "용어.xlsx", "totalRows": 2,
    "termNew": 1, "termExisting": 1, "variantNew": 2, "variantSkipped": 1, "errorCnt": 0,
    "rows": [
      { "row": 2, "term": "8D", "termId": 5, "action": "EXISTING_TERM",
        "variantsAdded": ["ZT팔디보고"],
        "variantsSkipped": [{ "word": "8디", "reason": "이미 등록된 유사어입니다. [8디] 공식 용어 [8D] 에 붙어 있습니다." }],
        "errors": [], "notes": ["기존 용어 — 뜻·분류는 바꾸지 않음"], "warnings": [] },
      { "row": 3, "term": "ZT새용어", "termId": null, "action": "NEW_TERM",
        "definition": "뜻", "domain": "품질관리", "restored": false,
        "variantsAdded": ["ZT새말"], "variantsSkipped": [], "errors": [], "notes": [], "warnings": [] }
    ]
  }
}
```

ERROR 행은 `definition`·`domain`·`restored` 없이 `errors: [{ "field": "term", "message": "공식 용어 등록은 통합관리자만 할 수 있습니다." }]` 를 담는다.
등록 응답은 같은 모양에 `dryRun: false`, 메시지 `용어 사전을 등록했습니다.` 이다.

## 3. 시험

`GlossaryImportTest`(서비스 5건) · `GlossaryImportApiTest`(multipart·첨부·내려받기 이력 2건) — 로컬 DB, 롤백.
