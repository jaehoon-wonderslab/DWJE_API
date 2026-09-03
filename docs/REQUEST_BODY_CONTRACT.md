# 요청 본문 계약 — 엔드포인트별 허용 키

`FAIL_ON_UNKNOWN_PROPERTIES` 가 켜져 있어 **선언되지 않은 키가 오면 400** 이다.
`error.field` 에 그 키 이름이 실리고, 메시지에 받는 키 목록이 함께 나간다.

> **이 문서는 손으로 고치지 않는다.** 컨트롤러의 `@RequestBody` 타입과
> `model/request/*.kt` 의 `data class` 프로퍼티에서 뽑은 것이다.
> `RequestBodyContractTest` 가 아래 `Map` 본문 목록이 코드와 맞는지 검사한다 —
> 이 문서를 처음 쓴 날 용어 API 4개를 DTO 로 바꾸고 문서를 갱신하지 않아
> 같은 날 안에 숫자가 어긋났다. 그래서 검사를 붙였다.

- 본문을 받는 엔드포인트 **83개**
- 타입 DTO **80개** — 모르는 키는 400
- `Map` 본문 **3개** — 모르는 키를 **조용히 버린다**

## 왜 켰는가

끄면 모르는 키를 조용히 버린다. 그 결과 **200 이 오는데 아무 일도 일어나지 않는** 응답이 생긴다.
2026-09-01 하루에 세 번 이 유형을 밟았다.

| 엔드포인트 | 보낸 것 | 결과 |
|---|---|---|
| `PATCH /system/users/{empNo}/state` | 본문 없음 | 200 — 실제로는 ACTIVE 로 바뀜 |
| `PUT /metrics/standards/{stdId}` | `{field,value}` | 200 — 아무 값도 안 바뀜 |
| `POST /alert-conditions` | 표시명·다른 키 이름 | 400 이지만 어느 값인지 알 수 없음 |

## A. `Map` 본문 3개 — 설정과 무관하게 조용히 무시

`Map<String, Any?>` 로 받아 Jackson 이 거를 근거가 없다. 서버가 아는 키만 읽고 나머지는 버린다.
화면이 값을 만들어 보내는 자리라 오타 경로가 좁아 남겨 둔 것이다.
사용자가 직접 입력하는 폼(용어·유사어)은 타입 DTO 로 전환했다.

- `POST /api/v1/download-logs`
- `PUT /api/v1/products/families/order`
- `PUT /api/v1/products/families/{familyCd}/products/order`

## B. 타입 DTO 80개 — 선언 키만 허용

### `AiAskRequest`

허용 키 — `sessionId`, `question`

- `POST /api/v1/ai/chat/ask`

### `ExportFormatRequest`

허용 키 — `format`, `yearMonth`, `from`, `to`, `scope`

- `POST /api/v1/ai/chat/history/export-trainset`
- `POST /api/v1/dashboard/kpi/evidence-export`
- `POST /api/v1/production/results/export`
- `POST /api/v1/quality/reports/{reportId}/export`
- `POST /api/v1/reports/{reportId}/export`

### `AiExportRequest`

허용 키 — `format`

- `POST /api/v1/ai/chat/messages/{messageId}/export`

### `AiFeedbackRequest`

허용 키 — `rating`, `comment`

- `POST /api/v1/ai/chat/messages/{messageId}/feedback`

### `FinetuneBuildRequest`

허용 키 — `baseModel`, `method`, `trainsetId`, `epoch`

- `POST /api/v1/ai/finetune-builds`

### `MaskRuleRequest`

허용 키 — `name`, `fieldKey`, `targetFields`, `action`, `customerId`, `customerPolicy`, `useYn`

- `POST /api/v1/ai/mask-rules`
- `POST /api/v1/ai/mask-rules/{ruleId}`
- `PUT /api/v1/ai/mask-rules/{ruleId}`

### `AiModelConfigRequest`

허용 키 — `thresholds`, `classification`

- `PUT /api/v1/ai/model-config`

### `ModelReleaseRequest`

허용 키 — `ver`, `vecId`, `ftId`, `mode`, `note`

- `POST /api/v1/ai/model-releases`

### `ModelApplyRequest`

허용 키 — `mode`, `reason`

- `POST /api/v1/ai/model-releases/rollback`
- `POST /api/v1/ai/model-releases/{ver}/apply`

### `VectorBuildRequest`

허용 키 — `sources`, `embedModelId`, `chunkSize`

- `POST /api/v1/ai/vector-builds`

### `AlertConditionRequest`

허용 키 — `name`, `metricStdId`, `metricDesc`, `op`, `threshold`, `thresholdText`, `thresholdUnit`, `duration`, `targetScope`, `target`, `severity`, `channels`, `groupIds`, `validWindow`, `dedupMin`, `msgTemplate`

- `POST /api/v1/alert-conditions`
- `PUT /api/v1/alert-conditions/{condId}`

### `StateChangeRequest`

허용 키 — `state`, `on`, `reason`

- `PATCH /api/v1/alert-conditions/{condId}/state`
- `PATCH /api/v1/alert-recipients/{recipientId}/state`
- `PATCH /api/v1/metrics/standards/{stdId}/state`
- `PATCH /api/v1/system/users/{empNo}/state`

### `DutyRequest`

허용 키 — `from`, `to`, `groupId`, `mainEmpNo`, `subEmpNo`, `reason`, `remark`

- `POST /api/v1/alert-duties`
- `PUT /api/v1/alert-duties/{dutyId}`

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
- `POST /api/v1/production/daily-reports/{reportId}/reject`
- `POST /api/v1/quality/reports/{reportId}/reject`

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

### `MetricStandardRequest`

허용 키 — `metricCd`, `name`, `category`, `unit`, `normal`, `warn`, `critical`, `window`, `basis`, `applied`, `direction`

- `POST /api/v1/metrics/standards`
- `PUT /api/v1/metrics/standards/{stdId}`

### `ReportRegenerateRequest`

허용 키 — `targetDate`

- `POST /api/v1/production/daily-reports/draft/regenerate`

### `ReportCorrectionRequest`

허용 키 — `sections`, `remark`

- `PUT /api/v1/production/daily-reports/{reportId}`
- `POST /api/v1/production/daily-reports/{reportId}/save`
- `PUT /api/v1/quality/reports/{reportId}`

### `ReportCopyRequest`

허용 키 — `targetDate`

- `POST /api/v1/production/daily-reports/{reportId}/copy`

### `DowntimeCreateRequest`

허용 키 — `eqptCd`, `stopAt`, `resumeAt`, `reasonCd`, `remark`

- `POST /api/v1/production/downtimes`

### `DowntimeUpdateRequest`

허용 키 — `reasonCd`, `remark`, `resumeAt`

- `PUT /api/v1/production/downtimes/{downtimeId}`

### `ReportFormRequest`

허용 키 — `name`, `type`, `customerId`, `disclosurePolicy`, `reportId`, `fields`

- `POST /api/v1/quality/report-forms`
- `PUT /api/v1/quality/report-forms/{formId}`

### `QualityReportDraftRequest`

허용 키 — `formId`, `lotNo`, `occurDate`, `disclosurePolicy`

- `POST /api/v1/quality/reports/draft`

### `EvidenceImageRequest`

허용 키 — `imageIds`, `images`

- `POST /api/v1/quality/reports/{reportId}/evidence-images`

### `UnmaskRequest`

허용 키 — `fields`, `reason`

- `POST /api/v1/quality/reports/{reportId}/unmask-request`

### `ScrapDraftRequest`

허용 키 — `step`, `cond`, `pickedVoucherIds`, `form`, `review`

- `POST /api/v1/reports/scrap/drafts`
- `PUT /api/v1/reports/scrap/drafts/{draftId}`

### `ApprovalLineRequest`

허용 키 — `depts`, `appr`, `due`, `notifyChannels`

- `PUT /api/v1/reports/scrap/drafts/{draftId}/approval-line`
- `POST /api/v1/reports/scrap/drafts/{draftId}/review-request`

### `ScrapManualRowRequest`

허용 키 — `model`, `process`, `reason`, `kind`, `qty`, `itemCd`, `occurDate`

- `POST /api/v1/reports/scrap/drafts/{draftId}/manual-rows`

### `ScrapUnitPriceRequest`

허용 키 — `key`, `keyValue`, `unitPrice`, `reason`

- `PUT /api/v1/reports/scrap/drafts/{draftId}/unit-price`

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

허용 키 — `empNo`, `name`, `deptId`, `pos`, `state`, `switchable`, `plantCd`, `password`, `remark`

- `POST /api/v1/system/users`
- `PUT /api/v1/system/users/{empNo}`

### `SignupApprovalRequest`

허용 키 — `approve`, `reason`

- `POST /api/v1/system/users/{empNo}/approve`

### `UserDeptChangeRequest`

허용 키 — `deptId`

- `PUT /api/v1/system/users/{empNo}/dept`

