# 요청 본문 계약 — 엔드포인트별 허용 키

`FAIL_ON_UNKNOWN_PROPERTIES` 가 켜져 있어 **선언되지 않은 키가 오면 400** 이다.
`error.field` 에 그 키 이름이 실리고, 메시지에 받는 키 목록이 함께 나간다.

> **이 문서는 손으로 고치지 않는다.** 컨트롤러의 `@RequestBody` 타입과
> `model/request/*.kt` 의 `data class` 프로퍼티에서 뽑은 것이다.
> `RequestBodyContractTest` 가 `Map` 본문 개수가 코드와 맞는지 검사한다 —
> 이 문서를 처음 쓴 날 용어 API 4개를 DTO 로 바꾸고 갱신하지 않아 같은 날 안에 틀렸다.

- 본문을 받는 엔드포인트 **86개**
- 타입 DTO **71개** — 모르는 키는 400
- `Map` 본문 **0개** — **전부 타입 DTO 로 전환 완료**

## 왜 켰는가

끄면 모르는 키를 조용히 버린다. 그 결과 **200 이 오는데 아무 일도 일어나지 않는** 응답이 생긴다.
2026-09-01 하루에 세 번 이 유형을 밟았다.

| 엔드포인트 | 보낸 것 | 결과 |
|---|---|---|
| `PATCH /system/users/{empNo}/state` | 본문 없음 | 200 — 실제로는 ACTIVE 로 바뀜 |
| `PUT /metrics/standards/{stdId}` | `{field,value}` | 지금은 400 — 받는 항목 목록을 알려 준다 (2026-09-22 복원분) |
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

## B. 타입 DTO 71개 — 선언 키만 허용

### `TrainsetExportRequest`

허용 키 — `from`, `to`, `ratingFilter`, `source`, `format`

- `POST /api/v1/ai/chat/history/export-trainset`

### `AiReviewRequest`

허용 키 — `reviewCd`, `comment`

- `PUT /api/v1/ai/chat/history/{messageId}/review`

### `ListExportRequest`

허용 키 — `scope`, `scopeCd`, `menuId`, `condSummary`, `format`, `from`, `to`, `target`, `view`, `keyword`

- `POST /api/v1/ai/chat/history/export`
- `POST /api/v1/download-logs/export`
- `POST /api/v1/sync/export`
- `POST /api/v1/audit-logs/export`

### `AiAskRequest`

허용 키 — `sessionId`, `question`

- `POST /api/v1/ai/chat/ask`

### `AiExportRequest`

허용 키 — `format`

- `POST /api/v1/ai/chat/messages/{messageId}/export`

### `AiDefectTopExportRequest`

허용 키 — `from`, `to`, `limit`

- `POST /api/v1/ai/chat/defects/top/export`

### `AiFeedbackRequest`

허용 키 — `rating`, `comment`

- `POST /api/v1/ai/chat/messages/{messageId}/feedback`

### `AiModelConfigSaveRequest`

허용 키 — `items`

- `PUT /api/v1/ai/model-config`

### `AiModelConfigCreateRequest`

허용 키 — `category`, `key`, `name`, `value`, `valueType`, `unit`, `options`, `description`, `agentCd`

- `POST /api/v1/ai/model-config`

### `AiModelConfigUpdateRequest`

허용 키 — `name`, `value`, `valueType`, `unit`, `options`, `description`, `agentCd`

- `PUT /api/v1/ai/model-config/{configId}`

### `AiModelConfigStateRequest`

허용 키 — `on`

- `PATCH /api/v1/ai/model-config/{configId}/state`

### `AiAgentStateRequest`

허용 키 — `on`

- `PATCH /api/v1/ai/agents/{agentCd}/state`

### `AlertConditionRequest`

허용 키 — `name`, `metricStdId`, `metricDesc`, `op`, `threshold`, `thresholdVal`, `thresholdText`, `thresholdUnit`, `duration`, `targetScope`, `target`, `severity`, `channels`, `groupIds`, `validWindow`, `dedupMin`, `msgTemplate`, `pickTargets`, `scopeDim`, `windowTime`, `evalIntervalSec`, `ignoreWindow`, `autoClose`, `escalation`

- `POST /api/v1/alert-conditions`

### `AlertConditionUpdateRequest`

허용 키 — `name`, `metricStdId`, `metricDesc`, `op`, `threshold`, `thresholdVal`, `thresholdText`, `thresholdUnit`, `duration`, `targetScope`, `target`, `pickTargets`, `severity`, `channels`, `groupIds`, `validWindow`, `dedupMin`, `msgTemplate`, `updatedAt`, `scopeDim`, `windowTime`, `evalIntervalSec`, `ignoreWindow`, `autoClose`, `escalation`

- `PUT /api/v1/alert-conditions/{condId}`

### `StateChangeRequest`

허용 키 — `state`, `on`, `reason`

- `PATCH /api/v1/alert-conditions/{condId}/state`
- `PATCH /api/v1/alert-recipient-groups/{groupId}/state`
- `PATCH /api/v1/alert-recipients/{recipientId}/state`

### `RecipientGroupRequest`

허용 키 — `name`, `channels`, `validWindow`, `night`, `deptId`, `memberEmpNos`

- `POST /api/v1/alert-recipient-groups`

### `RecipientGroupUpdateRequest`

허용 키 — `name`, `channels`, `validWindow`, `night`, `deptId`, `memberEmpNos`, `updatedAt`

- `PUT /api/v1/alert-recipient-groups/{groupId}`

### `RecipientRequest`

허용 키 — `empNo`, `mail`, `hp`, `messenger`, `night`

- `POST /api/v1/alert-recipients`
- `PUT /api/v1/alert-recipients/{recipientId}`

### `EscalationRuleRequest`

허용 키 — `stages`

- `PUT /api/v1/alert-escalation-rules`

### `ReasonRequest`

허용 키 — `reason`, `actionNote`

- `POST /api/v1/alerts/{alertId}/ack`

### `AoiBriefingRequest`

허용 키 — `from`, `to`, `wcCd`, `eqptCd`

- `POST /api/v1/quality/aoi/dimension/briefing`

### `LoginRequest`

허용 키 — `loginId`, `password`

- `POST /api/v1/auth/login`

### `RefreshTokenRequest`

허용 키 — `refreshToken`

- `POST /api/v1/auth/refresh`

### `SwitchAccountRequest`

허용 키 — `empNo`

- `POST /api/v1/auth/switch`

### `EmailCodeSendRequest`

허용 키 — `email`, `purpose`

- `POST /api/v1/auth/email/send-code`

### `EmailCodeVerifyRequest`

허용 키 — `email`, `purpose`, `code`

- `POST /api/v1/auth/email/verify-code`

### `UnlockCodeRequest`

허용 키 — `empNo`

- `POST /api/v1/auth/unlock/request`

### `UnlockVerifyRequest`

허용 키 — `empNo`, `code`

- `POST /api/v1/auth/unlock/verify`

### `UnlockCompleteRequest`

허용 키 — `verificationToken`, `newPassword`, `newPasswordConfirm`

- `POST /api/v1/auth/unlock/complete`

### `PasswordForgotRequest`

허용 키 — `empNo`, `email`

- `POST /api/v1/auth/password/forgot`

### `PasswordResetRequest`

허용 키 — `verificationToken`, `newPassword`, `newPasswordConfirm`

- `POST /api/v1/auth/password/reset`

### `SignupRequest`

허용 키 — `empNo`, `name`, `deptId`, `pos`, `email`, `verificationToken`, `password`, `passwordConfirm`

- `POST /api/v1/auth/signup`

### `PasswordChangeRequest`

허용 키 — `currentPassword`, `newPassword`, `newPasswordConfirm`

- `POST /api/v1/auth/password`

### `ExportFormatRequest`

허용 키 — `format`, `yearMonth`, `from`, `to`, `scope`

- `POST /api/v1/dashboard/kpi/evidence-export`
- `POST /api/v1/production/results/export`

### `UploadDocHideRequest`

허용 키 — `reason`

- `DELETE /api/v1/system/uploads/{docId}`

### `DataFieldMappingRequest`

허용 키 — `newFields`, `moves`, `screenId`

- `PUT /api/v1/system/data-fields/mapping`

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

### `GlossaryExportRequest`

허용 키 — `keyword`, `domainCd`, `mineOnly`, `menuId`, `format`, `scopeCd`, `scope`, `condSummary`

- `POST /api/v1/glossary/terms/export`

### `GlossaryTermRequest`

허용 키 — `term`, `definition`, `domainCd`

- `POST /api/v1/glossary/terms`
- `PUT /api/v1/glossary/terms/{termId}`

### `GlossaryVariantRequest`

허용 키 — `word`

- `POST /api/v1/glossary/terms/{termId}/variants`
- `PUT /api/v1/glossary/variants/{variantId}`

### `GlossaryNormalizeRequest`

허용 키 — `text`

- `POST /api/v1/glossary/normalize`

### `GwDeptMapSaveRequest`

허용 키 — `gwDeptNm`, `deptId`, `joinYn`, `remark`, `fromGwDeptNm`

- `PUT /api/v1/system/gw-dept-maps`

### `GwDeptMapBulkSaveRequest`

허용 키 — `gwDeptNms`, `deptId`, `joinYn`, `keepRemark`, `remark`

- `PUT /api/v1/system/gw-dept-maps/bulk`

### `GwDeptReassignRequest`

허용 키 — `empNos`, `gwDeptNms`, `all`, `includeSuspended`

- `POST /api/v1/system/gw-dept-maps/reassign`

### `LlmChatRequest`

허용 키 — `messages`, `context`, `messageId`, `sessionId`

- `POST /api/ai/chat`

### `AiToolCallRequest`

허용 키 — `from`, `to`, `wcCd`, `eqptCd`, `groupByDate`, `compareFrom`, `compareTo`, `label`, `compareLabel`, `limit`, `years`, `basis`, `defectReports`

- `POST /api/ai/tools/{name}`

### `MetricStandardRequest`

허용 키 — `metricCd`, `name`, `category`, `unit`, `normal`, `warn`, `critical`, `window`, `basis`, `applied`, `direction`

- `POST /api/v1/metrics/standards`
- `PUT /api/v1/metrics/standards/{stdId}`

### `MetricStandardStateRequest`

허용 키 — `applied`

- `PATCH /api/v1/metrics/standards/{stdId}/state`

### `MetricCollectRequest`

허용 키 — `collectMode`, `collectorCd`, `dimCd`, `intervalSec`, `lookbackMin`, `sqlText`, `applied`

- `PUT /api/v1/metrics/standards/{stdId}/collect`

### `MetricSourceSaveRequest`

허용 키 — `items`

- `PUT /api/v1/metrics/standards/{stdId}/sources`

### `DailyReportRowsRequest`

허용 키 — `targetDate`, `rows`

- `POST /api/v1/production/daily-reports/rows`

### `DayTargetRequest`

허용 키 — `product`, `processId`, `applyFrom`, `targetQty`, `remark`

- `POST /api/v1/production/day-targets`
- `PUT /api/v1/production/day-targets/{targetId}`

### `DowntimeCreateRequest`

허용 키 — `eqptCd`, `stopAt`, `resumeAt`, `reasonCd`, `remark`

- `POST /api/v1/production/downtimes`

### `DowntimeUpdateRequest`

허용 키 — `reasonCd`, `remark`, `resumeAt`

- `PUT /api/v1/production/downtimes/{downtimeId}`

### `QualityDefectExportRequest`

허용 키 — `from`, `to`, `processId`, `defectTypeCd`, `format`, `levels`

- `POST /api/v1/quality/defects/by-type/export`
- `POST /api/v1/quality/defects/by-line/export`

### `ReportUsageRequest`

허용 키 — `screenId`

- `POST /api/v1/reports/usage`

### `DownloadLogRecordRequest`

허용 키 — `reportId`, `reportNm`, `menuId`, `format`, `scope`, `scopeCd`, `condSummary`, `rowCnt`, `blindCnt`, `params`, `fileSize`

- `POST /api/v1/download-logs`

### `SyncManualRequest`

허용 키 — `srcTables`, `kind`, `scheduledAt`

- `POST /api/v1/sync/jobs/manual`

### `ConnectionTestRequest`

허용 키 — `target`

- `POST /api/v1/sync/connection-test`

### `SchemaDriftResolveRequest`

허용 키 — `note`

- `POST /api/v1/sync/schema-drift/{driftId}/resolve`

### `SignupApprovalRequest`

허용 키 — `approve`, `reason`, `deptId`

- `POST /api/v1/system/users/{empNo}/approve`

### `UserSaveRequest`

허용 키 — `empNo`, `name`, `deptId`, `pos`, `state`, `switchable`, `plantCd`, `password`, `remark`, `extraMenuIds`, `extraMenuReasons`

- `POST /api/v1/system/users`
- `PUT /api/v1/system/users/{empNo}`

### `UserStateChangeRequest`

허용 키 — `state`, `reason`, `resetPassword`

- `PATCH /api/v1/system/users/{empNo}/state`

### `UserDeptChangeRequest`

허용 키 — `deptId`

- `PUT /api/v1/system/users/{empNo}/dept`

### `DeptSaveRequest`

허용 키 — `deptNm`, `abbr`, `desc`, `plantCd`, `initPermFrom`

- `POST /api/v1/system/depts`
- `PUT /api/v1/system/depts/{deptId}`

### `MenuPermRequest`

허용 키 — `deptId`, `screenId`, `allowed`, `perm`

- `PUT /api/v1/system/menu-perms`

### `MenuPermGroupRequest`

허용 키 — `deptId`, `groupId`, `groupNm`, `allowed`, `perm`, `includeActions`

- `PUT /api/v1/system/menu-perms/group`

### `MenuPermCopyRequest`

허용 키 — `fromDeptId`, `toDeptId`, `dryRun`, `expectedHash`

- `POST /api/v1/system/menu-perms/copy`

### `DataPermRequest`

허용 키 — `deptId`, `fieldKey`, `allowed`

- `PUT /api/v1/system/data-perms`
