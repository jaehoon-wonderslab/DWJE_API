# Agent 실행 이력 + 모델 관리 화면 4종 — API 계약 (2026-09-22)

요청 : `docs/REQUEST_ai_agent_run_and_model_screens.md` (DB/API 정합성 점검 세션)

두 가지다. ① 각 Agent 가 제 일을 마친 자리에서 실행 이력을 남기게 하고,
② 표만 있고 컨트롤러가 없던 모델 관리 화면 4종(`ai-agent` · `base-model` · `sys-model-ver` · `sys-metric`)의
조회·등록·수정 API 를 만들어 `endpoints.js` 에 등록했다.

> **2026-09-23 갱신** — 웹 메뉴 고정에 맞춰 SY-11 16건과 SY-10 불량 태그 4건을 다시 뺐다(아래 각 절). 남은 것은 21건이다.
>
> **먼저 알릴 것 — 이 4개 화면의 API 는 2026-09-15 에 "지운" 것이다.**
> WEB 담당 지시서로 시스템관리 하위 5개 화면의 엔드포인트 42건을 지웠다(커밋 f35c015).
> 이번 요청은 그중 4개(제품군 순위 관리 제외)를 되살리는 일이라, 새로 설계하지 않고
> **그때의 명세 번호(No.191~221)를 그대로 잇는다.** 지운 쪽과 되살리라는 쪽이 다른 자리라
> 두 요청이 서로를 모를 수 있다 — 화면을 켜기 전에 WEB 담당과 맞춰 두는 편이 좋다.

---

## 1. Agent 실행 이력 기록 (①)

### 기록 지점

| Agent | 기록하는 자리 | 처리량(`load`) | 비고 |
|---|---|---|---|
| ① 비전 수집 | `AoiCosmeticService.getSummary` | `제품 N건 · 질의 N회` | **원천을 실제로 읽었을 때만.** 캐시로 끝난 호출은 남기지 않는다 |
| ② 데이터 분류 | `SyncService.scheduleManualJobs` | `예약 N건` | API 가 끝낸 일은 **예약까지**다 (아래 참고) |
| ③ 불량 판정 | `AoiDefectService.getDefects` | `판정 N건` | 첫 쪽에서만. 쪽마다 남기면 한 작업이 여러 줄이 된다 |
| ④ 원인 분석 | `AoiPredictionService.recalculate` | `Nh 관측` | 원래 있던 유일한 기록 지점 (유지) |
| ⑤ 이력 추적 | `AoiSerialService.getSerials` | `시리얼 N건` | 캐시 히트 제외 |
| ⑥ 보고서 생성 | `ReportService.getMorningMeeting` | `공정 N건` | 아침회의 자료 한 벌 |
| ⑦ 보안 필터링 | `AiChatService.ask` | `마스킹 N건` | 0건이어도 남긴다 — "필터가 돌고 있다"가 이 화면이 보려는 것 |
| ⑧ KG 구축 | — | — | 해당 기능이 아직 없다 |
| ⑨ 이상 알림 | — | — | **Alert_Engine 담당. API 는 건드리지 않았다** (이미 기록 중인 것을 확인했다) |

### 기록이 본 기능을 깨뜨리지 않는 구조

`AgentRunRecorder.record(...)` 한 줄이면 된다. 걸린 시간까지 재려면 `measure { }` 를 쓴다
(예외가 나면 `ERROR` 로 남기고 예외는 그대로 올려보낸다 — 삼키면 화면이 성공으로 오해한다).

두 겹으로 막는다.

1. **별도 트랜잭션** — `AgentRunWriter` 가 `REQUIRES_NEW` 로 넣는다.
   기록기와 쓰기를 **다른 빈으로 나눈 것이 핵심**이다. 같은 클래스 안에서 부르면 프록시를 타지 않아
   `REQUIRES_NEW` 가 아예 걸리지 않는다. 실제로 그렇게 만들었다가 아침회의 자료(읽기 전용 트랜잭션)에서
   INSERT 가 거부되는 것을 로컬에서 확인하고 고쳤다.
2. **try/catch** — 남는 실패는 WARN 으로만 남기고 삼킨다. 트랜잭션 경계 **바깥**에 있어야 커밋 실패까지 잡는다.

### 곁들여 고친 것 — `master.state` 가 장애를 정상으로 보이게 하던 자리

`findMasterState()` 는 "최근 10분 창"으로 판정하는데, 그 창에 행이 없으면 `OK` 를 돌려줬다.
아무것도 안 돌고 있는 상태와 정상이 같은 값으로 보인다. **행이 없으면 `IDLE`** 로 바꿨다
(`GET /dashboard/ai/agents` 와 `GET /ai/agents/summary` 둘 다).

### ② 는 경계가 API 바깥이다

`scheduleManualJobs` 는 이관을 **예약**할 뿐이고 실제 이관은 `MES_migration_engine` 이 한다.
그래서 "완료"가 아니라 `예약 N건` 으로 남긴다. 이관이 끝난 시점의 기록은 엔진 쪽에서 남기는 편이 맞다 —
⑨ 를 Alert_Engine 이 맡는 것과 같은 경계다.

---

## 2. 화면 4종 API

경로는 2026-09-15 이전 명세를 그대로 잇는다. `endpoints.js` 에 41건을 등록했다(`no: 191~221`).

### 공통 — 지금은 통합관리자만 닿는다

네 화면 모두 `tb_sys_menu.use_flg = 'N'` 이고, 권한 뷰(`vw_sys_user_menu_perm`)가 **켜진 메뉴만** 내준다.
그래서 전산팀 계정은 메뉴를 켜기 전까지 403 이다. 웹 화면이 붙은 뒤 켜면 된다(요청서 「참고」와 같은 판단).

중복 충돌은 **409 `E-RULE-001`** 로 통일했다(용어 사전과 같은 `ConflictingValueException`).
회원가입 사번·이메일 중복이 400 `E-VALID-002` 로 WEB 에 문서화돼 있어 그쪽은 건드리지 않았다.

### SY-10 · AI 모델 설정 (`base-model`) — 5건 (불량 태그 4건은 2026-09-23 제거)

| 메서드 | 경로 | 비고 |
|---|---|---|
| GET | `/ai/model-config` | `byCategory` 로도 묶어서 낸다 |
| POST · PUT · PATCH | `/ai/model-config`, `/{configId}`, `/{configId}/state` | 등록·수정·사용전환 |
| PUT | `/ai/model-config` | 값 일괄 저장 — **한 줄이라도 형식이 어긋나면 아무것도 저장하지 않는다** |

- 값 형식(`valueType`)은 **저장할 때** 본다. `NUM` 인데 숫자가 아니면 400 — 읽는 쪽(AOI 예측·판정)이
  숫자로 변환해 쓰므로, 여기서 안 거르면 나중에 엉뚱한 자리에서 형 변환 오류로 터진다.
- 설정은 **지우지 않고 끈다**. 읽는 쪽이 (분류, 키)로 값을 찾기 때문에, 지우면 조용히 코드 기본값으로 돌아간다.
- 불량 태그(`/ai/defect-tags…` 4건)는 2026-09-23 에 뺐다 — 웹이 부르지 않았고, `tb_ai_defect_tag` ·
  `tb_ai_defect_tag_map` 을 V42 가 지운다. 태그 분류를 붙여 주던 `GET /common/masters/defect-types` 도 함께 뺐다.
- 구 `/ai/mask-rules` 4건은 돌아오지 않았다 — 근거 표 `ax.tb_ai_mask_rule` 을 V32 가 지웠고,
  마스킹 규칙은 [데이터 접근 권한] 화면(V33)이 이어받았다.

### SY-11 · AI 서비스 버전 관리 (`sys-model-ver`) — **2026-09-23 제거**

`/ai/model-releases*` · `/ai/assets*` · `/ai/corpus-snapshots*` · `/ai/serving-routes*` 16건을 뺐다.
웹이 한 번도 부르지 않았고, 근거 표(`tb_ai_model_asset` · `tb_ai_corpus_snapshot` · `tb_ai_serving_asset` ·
`tb_ai_serving_route`)와 뷰 3개 · 함수 2개(`fn_guard_serving_state` 트리거 포함)를 V42 가 지운다.
`ax.tb_ai_serving_profile` 은 남는다 — `/auth/me` 의 `servingModelVer`, 성과지표 대시보드의 AI 성능,
질의 이력의 `profile_id` 가 읽는다. 다만 이 표에 **쓰는 API 는 이제 없다**(버전을 올리려면 DB 에서 직접 넣어야 한다).

### SY-12 · Agent 실행 현황 (`ai-agent`) — 5건

| 메서드 | 경로 | 비고 |
|---|---|---|
| GET | `/ai/agents/summary`, `/ai/agents`, `/{agentCd}/runs` | 요약·목록·실행 이력 |
| POST | `/{agentCd}/restart` | **프로세스를 다시 띄우지 않는다** |
| PATCH | `/{agentCd}/state` | 사용/미사용 |

- `agentCd` 는 ①~⑨ 그대로라 URL 인코딩이 필요하다 (`①` = `%E2%91%A0`).
- 재시작은 Agent 가 아직 별도 런타임이 아니라 "죽은 것을 살리는" 대상이 없다.
  실행 이력과 감사 로그에 요청을 남겨 **상태를 다시 세는 기준점**을 만드는 것이 전부이고,
  그 사실을 응답 `note` 로 함께 내려 화면이 "재시작됨" 으로 단정하지 않게 한다.
- 빠진 것 — Master AI 파이프라인(구 No.212)은 `ax.tb_ai_pipeline_stage` 를 V31 이 지워 없다.

### SY-13 · 지표 측정 데이터 관리 (`sys-metric`) — 11건

| 메서드 | 경로 | 비고 |
|---|---|---|
| GET | `/metrics/standards/summary`, `/metrics/standards`, `/{stdId}/usage` | 요약·목록·사용처 |
| POST · PUT · PATCH | `/metrics/standards`, `/{stdId}`, `/{stdId}/state` | 등록·수치 수정·적용 전환 |
| GET · PUT | `/{stdId}/collect` | **수집 정의** (신규) |
| PUT | `/{stdId}/sources` | **산출 근거** (신규) |
| GET | `/metrics/standards/values` | **측정값 조회** (신규) |

- `direction`(high/low)은 저장 컬럼이 아니라 `warn`/`critical` 관계에서 산출된다.
  보낸 값이 그 관계와 어긋나면 400 으로 어느 값을 어떻게 고칠지 알려 준다.
- **`tb_met_metric_value` 가 0행인 이유가 수집 정의가 없어서다.** 표 주석이 그렇게 적혀 있고,
  그 자리를 `/collect` 가 연다. 다만 **정의만 저장할 뿐 수집은 별도 프로세스의 몫**이라,
  저장해도 `lastValueAt` 이 바로 차지 않는다는 사실을 응답 `note` 로 함께 알린다.
- 빠진 것 — 기준 수치 변경 이력(구 No.220)은 `ax.tb_met_metric_std_hist` 를 V31 이 지워 없다.
  변경 사실은 감사 로그(`ax.tb_log_audit`)에 남는다.

---

## 3. 고친 파일

| 파일 | 내용 |
|---|---|
| `service/AgentRunRecorder.kt` (신규) | 기록기 + `AgentRunWriter`(REQUIRES_NEW 경계) |
| `repository/AgentRunRepository.kt` (신규) | 실행 이력 쓰기·읽기, Agent 목록·요약 |
| `service/AgentStatusService.kt` · `controller` 3종 (신규) | SY-12 |
| `repository/AiModelConfigRepository.kt` · `AiDefectTagRepository.kt` · `service/AiModelConfigService.kt` (신규) | SY-10 |
| `repository/AiServingRepository.kt` · `service/AiServingService.kt` (신규) | SY-11 |
| `repository/MetricStandardRepository.kt` · `service/MetricStandardService.kt` | SY-13 화면 메서드 복원(이력 3개 제외) + 수집·근거·측정값 |
| `controller/AiModelAdminController.kt` · `MetricAdminController.kt` (신규) | 41개 엔드포인트 |
| `model/request/AiAdminRequests.kt` (신규) · `MetricRequests.kt` | DTO 16종 |
| `service/{AoiCosmetic,AoiDefect,AoiSerial,Sync,Report,AiChat,AoiPrediction}Service.kt` | 기록 지점 7곳 |
| `repository/{Quality,DashboardAi,CommonMaster}Repository.kt` | 기록 함수 이전 · IDLE 판정 · MES 불량 코드 확인 |
| `common/util/DataField.kt` · `config/OpenApiConfig.kt` | MenuId 상수 4개 · 태그 설명 |
| `WEB/src/services/api/endpoints.js` | SY-10~13 41건 등록 |

## 4. 남은 것

- **화면 켜기** — `tb_sys_menu.use_flg = 'Y'` 는 웹 화면이 붙은 뒤에(요청서 「참고」와 같은 판단). DB 담당 몫이다.
- **되살릴지 정해야 하는 표 3개** — `tb_ai_serving_deploy_log`(배포 이력) · `tb_ai_pipeline_stage`(파이프라인) ·
  `tb_met_metric_std_hist`(기준 변경 이력). V31 이 지운 것이라 API 로는 메울 수 없다.
- **`vec` 계열** — 벡터 색인·파인튜닝 빌드 화면(구 No.202~207)은 이번 표 목록에 없어 손대지 않았다.
- **수집 프로세스** — `/collect` 로 정의를 넣어도 값을 채우는 주체가 아직 없다. 그게 붙어야
  `tb_met_metric_value` 가 차고 알림 조건이 비교할 값이 생긴다.
