# 요청 본문 계약 — 엔드포인트별 허용 키

`FAIL_ON_UNKNOWN_PROPERTIES` 가 켜져 있어 **선언되지 않은 키가 오면 400** 이다.
`error.field` 에 그 키 이름이 실리고, 메시지에 받는 키 목록이 함께 나간다.

> **이 문서는 손으로 고치지 않는다.** 컨트롤러의 `@RequestBody` 타입과
> `model/request/*.kt` 의 `data class` 프로퍼티에서 뽑은 것이다.
> `RequestBodyContractTest` 가 `Map` 본문 개수가 코드와 맞는지 검사한다 —
> 이 문서를 처음 쓴 날 용어 API 4개를 DTO 로 바꾸고 갱신하지 않아 같은 날 안에 틀렸다.

- 본문을 받는 엔드포인트 **62개**
- 타입 DTO **56개** — 모르는 키는 400
- `Map` 본문 **0개** — **전부 타입 DTO 로 전환 완료**

## 왜 켰는가

끄면 모르는 키를 조용히 버린다. 그 결과 **200 이 오는데 아무 일도 일어나지 않는** 응답이 생긴다.
2026-09-01 하루에 세 번 이 유형을 밟았다.

| 엔드포인트 | 보낸 것 | 결과 |
|---|---|---|
| `PATCH /system/users/{empNo}/state` | 본문 없음 | 200 — 실제로는 ACTIVE 로 바뀜 |
| `PUT /metrics/standards/{stdId}` | `{field,value}` | 200 — 아무 값도 안 바뀜 (API 는 2026-09-15 제거) |
| `POST /alert-conditions` | 표시명·다른 키 이름 | 400 이지만 어느 값인지 알 수 없음 |

## A. `Map` 본문 0개

**없다.** 본문을 받는 엔드포인트 전부가 타입 DTO 다.

`Map` 본문은 키를 자유롭게 받아 모르는 키를 조용히 버리므로,
`FAIL_ON_UNKNOWN_PROPERTIES` 로도 막히지 않는 사각지대였다.
2026-09-01 에 7개였고 2026-09-03 에 0개가 됐다 —
용어·유사어 4개, 제품군·제품 순서 2개, 다운로드 이력 1개 순서로 전환했다.

> 순서 변경 2개는 바깥 키(`orders`)뿐 아니라 **안쪽 항목까지 조용히 버렸다.**
> 키·타입이 어긋난 항목만 빠지고 200 이 나가서, 10개를 보냈는데 3개만 반영되고도
> 화면은 성공으로 읽었다. 지금은 어긋난 항목이 하나라도 있으면 400 이다.

## B. 타입 DTO 56개 — 선언 키만 허용

### `AiAskRequest`

허용 키 — `sessionId`, `question`

- `POST /api/v1/ai/chat/ask`

### `ExportFormatRequest`

허용 키 — `format`, `yearMonth`, `from`, `to`, `scope`

- `POST /api/v1/ai/chat/history/export-trainset`
- `POST /api/v1/dashboard/kpi/evidence-export`
- `POST /api/v1/production/results/export`

### `AiExportRequest`

허용 키 — `format`

- `POST /api/v1/ai/chat/messages/{messageId}/export`

### `AiFeedbackRequest`

허용 키 — `rating`, `comment`

- `POST /api/v1/ai/chat/messages/{messageId}/feedback`

### `AlertConditionRequest`

허용 키 — `name`, `metricStdId`, `metricDesc`, `op`, `threshold`, `thresholdText`, `thresholdUnit`, `duration`, `targetScope`, `target`, `severity`, `channels`, `groupIds`, `validWindow`, `dedupMin`, `msgTemplate`

- `POST /api/v1/alert-conditions`
- `PUT /api/v1/alert-conditions/{condId}`

### `StateChangeRequest`

허용 키 — `state`, `on`, `reason`

- `PATCH /api/v1/alert-conditions/{condId}/state`
- `PATCH /api/v1/alert-recipients/{recipientId}/state`
- `PATCH /api/v1/system/users/{empNo}/state`

### `EscalationRuleRequest`

허용 키 — `stages`

- `PUT /api/v1/alert-escalation-rules`

### `RecipientGroupRequest`

허용 키 — `name`, `channels`, `validWindow`, `night`, `deptId`, `memberEmpNos`

- `POST /api/v1/alert-recipient-groups`
- `PUT /api/v1/alert-recipient-groups/{groupId}`

### `RecipientRequest`

허용 키 — `empNo`, `mail`, `hp`, `messenger`, `night`

- `POST /api/v1/alert-recipients`
- `PUT /api/v1/alert-recipients/{recipientId}`

### `ReasonRequest`

허용 키 — `reason`, `actionNote`

- `POST /api/v1/alerts/{alertId}/ack`

### `EmailCodeSendRequest`

허용 키 — `email`, `purpose`

- `POST /api/v1/auth/email/send-code`

### `EmailCodeVerifyRequest`

허용 키 — `email`, `purpose`, `code`

- `POST /api/v1/auth/email/verify-code`

### `LoginRequest`

허용 키 — `loginId`, `password`

- `POST /api/v1/auth/login`

### `PasswordChangeRequest`

허용 키 — `currentPassword`, `newPassword`, `newPasswordConfirm`

- `POST /api/v1/auth/password`

### `PasswordForgotRequest`

허용 키 — `empNo`, `email`

- `POST /api/v1/auth/password/forgot`

### `PasswordResetRequest`

허용 키 — `verificationToken`, `newPassword`, `newPasswordConfirm`

- `POST /api/v1/auth/password/reset`

### `RefreshTokenRequest`

허용 키 — `refreshToken`

- `POST /api/v1/auth/refresh`

### `SignupRequest`

허용 키 — `empNo`, `name`, `deptId`, `pos`, `email`, `verificationToken`, `password`, `passwordConfirm`

- `POST /api/v1/auth/signup`

### `SwitchAccountRequest`

허용 키 — `empNo`

- `POST /api/v1/auth/switch`

### `DownloadLogRecordRequest`

허용 키 — `reportId`, `reportNm`, `menuId`, `format`, `scope`, `rowCnt`, `blindCnt`, `params`, `fileSize`

- `POST /api/v1/download-logs`

### `GlossaryNormalizeRequest`

허용 키 — `text`

- `POST /api/v1/glossary/normalize`

### `GlossaryTermRequest`

허용 키 — `term`, `definition`, `domainCd`

- `POST /api/v1/glossary/terms`
- `PUT /api/v1/glossary/terms/{termId}`

### `GlossaryVariantRequest`

허용 키 — `word`

- `POST /api/v1/glossary/terms/{termId}/variants`
- `PUT /api/v1/glossary/variants/{variantId}`

### `ReportRegenerateRequest`

허용 키 — `targetDate`


### `ReportCorrectionRequest`

허용 키 — `sections`, `remark`


### `DayTargetRequest`

허용 키 — `product`, `processId`, `applyFrom`, `targetQty`, `remark`
(수정 시 `product`·`processId` 는 무시된다 — 제품·공정은 바꿀 수 없다)

- `POST /api/v1/production/day-targets`
- `PUT /api/v1/production/day-targets/{targetId}`

### `DailyReportRowsRequest`

허용 키 — `targetDate`, `rows`
`rows[]` 허용 키 — `product`, `targetQty`, `decision`, `dri`, `due`

- `POST /api/v1/production/daily-reports/rows`


### `FavoriteScreensRequest`

허용 키 — `screenIds`
(배열 순서가 곧 `sortOrder`. 공백·중복은 정리하고, 20개 초과·메뉴에 없는 ID 는 400)

- `PUT /api/v1/users/me/favorites`

### `ReportWriteStateRequest`

허용 키 — `screenId`, `baseDate`, `state`
(`screenId` 는 prod-daily · rpt-press-morning · rpt-plating-morning · rpt-scrap 만, `state` 는 DRAFT · SUBMITTED · APPROVED 만)

- `PUT /api/v1/reports/status`

### `ReportUsageRequest`

허용 키 — `screenId`
(메뉴에 없는 ID 는 400, 권한 없는 화면은 E-AUTH-002. 응답은 상위 5개 목록)

- `POST /api/v1/reports/usage`

### `ReportCopyRequest`

허용 키 — `targetDate`


### `DowntimeCreateRequest`

허용 키 — `eqptCd`, `stopAt`, `resumeAt`, `reasonCd`, `remark`

- `POST /api/v1/production/downtimes`

### `DowntimeUpdateRequest`

허용 키 — `reasonCd`, `remark`, `resumeAt`

- `PUT /api/v1/production/downtimes/{downtimeId}`

### `QualityReportDraftRequest`

허용 키 — `formId`, `lotNo`, `occurDate`, `disclosurePolicy`


### `EvidenceImageRequest`

허용 키 — `imageIds`, `images`


### `AoiBriefingRequest`

허용 키 — `from`, `to`, `wcCd`, `eqptCd`

- `POST /api/v1/quality/aoi/dimension/briefing`

### `QualityDefectExportRequest`

허용 키 — `from`, `to`, `processId`, `defectTypeCd`, `format`, `levels`

- `POST /api/v1/quality/defects/by-type/export`
- `POST /api/v1/quality/defects/by-line/export`

### `ScrapDraftRequest`

허용 키 — `step`, `cond`, `pickedVoucherIds`, `form`, `review`


### `ApprovalLineRequest`

허용 키 — `depts`, `appr`, `due`, `notifyChannels`


### `ScrapManualRowRequest`

허용 키 — `model`, `process`, `reason`, `kind`, `qty`, `itemCd`, `occurDate`


### `ScrapUnitPriceRequest`

허용 키 — `key`, `keyValue`, `unitPrice`, `reason`


### `ConnectionTestRequest`

허용 키 — `target`

- `POST /api/v1/sync/connection-test`

### `SyncManualRequest`

허용 키 — `srcTables`, `kind`, `scheduledAt`

- `POST /api/v1/sync/jobs/manual`

### `SchemaDriftResolveRequest`

허용 키 — `note`

- `POST /api/v1/sync/schema-drift/{driftId}/resolve`

### `DataPermRequest`

허용 키 — `deptId`, `fieldKey`, `allowed`

- `PUT /api/v1/system/data-perms`

### `DataFieldSaveRequest`

허용 키 — `fieldKey`, `name`, `desc`, `category`

- `POST /api/v1/system/data-fields`
- `PUT /api/v1/system/data-fields/{fieldKey}`

### `DataFieldAttrRequest`

허용 키 — `attrName`, `remark`

- `POST /api/v1/system/data-fields/{fieldKey}/attrs`

### `DataFieldApplyRequest`

허용 키 — `on`

- `PATCH /api/v1/system/data-fields/{fieldKey}/apply`

### `DeptSaveRequest`

허용 키 — `deptNm`, `abbr`, `desc`, `plantCd`, `initPermFrom`

- `POST /api/v1/system/depts`
- `PUT /api/v1/system/depts/{deptId}`

### `MenuPermRequest`

허용 키 — `deptId`, `screenId`, `allowed`

- `PUT /api/v1/system/menu-perms`

### `MenuPermCopyRequest`

허용 키 — `fromDeptId`, `toDeptId`

- `POST /api/v1/system/menu-perms/copy`

### `MenuPermGroupRequest`

허용 키 — `deptId`, `groupNm`, `allowed`

- `PUT /api/v1/system/menu-perms/group`

### `UserSaveRequest`

허용 키 — `empNo`, `name`, `deptId`, `pos`, `state`, `switchable`, `plantCd`, `password`, `remark`, `extraMenuIds`

- `POST /api/v1/system/users`
- `PUT /api/v1/system/users/{empNo}`

### `SignupApprovalRequest`

허용 키 — `approve`, `reason`

- `POST /api/v1/system/users/{empNo}/approve`

### `UserDeptChangeRequest`

허용 키 — `deptId`

- `PUT /api/v1/system/users/{empNo}/dept`

