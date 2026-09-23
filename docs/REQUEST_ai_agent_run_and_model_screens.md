# 작업 요청 — Agent 실행 이력 기록 보강 + 모델 관리 화면 4종 API

요청일 2026-09-22 · 요청 출처 : DB/API 정합성 점검 세션
대상 : **API**

---

## 배경

`ax.tb_ai_*` 13개 표 가운데 **10개가 0행**입니다. 표는 설계돼 있는데 채우는 쪽이 없습니다.
그중 화면에 직접 드러나는 것이 `tb_ai_agent_run` 입니다.

표 주석 : *"Agent 실행 이력 — 화면의 상태 · 최근 실행 · 처리량은 이 테이블의 최신 행에서 산출한다"*

---

## ① Agent 실행 이력이 ④번 하나만 기록된다

기록 지점이 코드 전체에 **한 군데뿐**입니다.

```
QualityRepository.insertAgentRun(agentNo, stateCd, message, throughput)
   └ 유일한 호출부 : AoiPredictionService.kt:358  →  agentNo = "④" 고정
      (POST /api/v1/quality/aoi/prediction/recalculate 일 때만)
```

그래서 9종 중 ④ 원인 분석만, 그것도 예측 재산출을 눌렀을 때만 남습니다.

**실측** (`GET /api/v1/dashboard/ai/agents`, 활성 화면인 AI 통합 대시보드가 호출) :

```
master: {state: "OK", recentRunCnt: 0, avgElapsedMs: null}
① ~ ⑨ 전부 state=IDLE, last=null, load=null
```

읽는 쪽(`DashboardAiRepository.findAgentStatus`)은 `LEFT JOIN LATERAL` 이라 **깨지지는 않습니다.**
다만 `findMasterState()` 가 "최근 10분 내 행" 으로 판정해서, 행이 없으면 **오류가 나도 `OK`** 로 나옵니다.
장애를 정상으로 보이게 하는 쪽이라 비어 있는 것보다 나쁩니다.

### 요청

각 Agent 의 실제 작업이 끝나는 지점에서 `insertAgentRun` 을 불러 주세요.
이미 함수가 있으니 **호출 지점만 늘리면** 됩니다.

| Agent | 담당 | 기록할 만한 지점 (제안) |
|---|---|---|
| ① 비전 수집 | AOI · 비전 이미지 수집 | AOI 원천 조회 · 이미지 매핑 적재 |
| ② 데이터 분류 | 수집 데이터 정규화 | MES 이관 후처리 |
| ③ 불량 판정 | 양품/불량 판정 · HITL | AOI 판정 조회 |
| ④ 원인 분석 | 불량 원인 추정 | **이미 있음** (유지) |
| ⑤ 이력 추적 | LOT · 공정 이력 | LOT 추적 조회 |
| ⑥ 보고서 생성 | 보고서 초안 | 보고서 생성 API |
| ⑦ 보안 필터링 | 마스킹 · 공개 정책 | 마스킹 적용 지점 |
| ⑧ KG 구축 | 지식 그래프 | 해당 기능 도입 시 |
| ⑨ 이상 알림 | 임계 초과 감지 · 발송 | **Alert_Engine 이 맡습니다 — API 는 건드리지 마세요** |

> ⑨ 는 `Alert_Engine/REQUEST_agent_run_logging.md` 로 따로 요청했습니다. 중복 방지.

어디까지 붙일지는 판단에 맡깁니다. 다만 **⑨ 제외**와,
**기록 실패가 본 기능을 깨뜨리지 않게** 하는 것 두 가지만 지켜 주세요.

---

## ② 모델 관리 화면 4종이 메뉴만 있고 전부 비어 있다

| 메뉴 ID | 화면명 | DB 표 | 관리 API | 명세 | 웹 화면 | `use_flg` |
|---|---|---|---|---|---|---|
| `ai-agent` | Agent 실행 현황 | ✓ | ✗ | ✗ | ✗ | N |
| `base-model` | AI 모델 설정 | ✓ | ✗ | ✗ | ✗ | N |
| `sys-model-ver` | AI 서비스 버전 관리 | ✓ | ✗ | ✗ | ✗ | N |
| `sys-metric` | 지표 측정 데이터 관리 | ✓ | ✗ | ✗ | ✗ | N |

표는 다 있는데 **전용 컨트롤러가 하나도 없습니다.** 관련 표는 다른 기능이 곁다리로 읽기만 합니다.

```
tb_ai_serving_profile  ← DashboardKpiRepository · AiChatRepository · AuthRepository (읽기만)
tb_ai_model_config     ← QualityRepository · DashboardAiRepository · AoiPredictionService (읽기만)
tb_ai_model_asset      ← DashboardKpiRepository (읽기만)
```

부수 효과로 **`/auth/me` 의 `servingModelVer` 가 `null`** 입니다
(`AuthRepository.findServingModelVersion()` → `tb_ai_serving_profile` 0행).

### 요청

위 4개 화면의 **조회 · 등록 · 수정 API** 를 만들어 주세요. 대상 표는 이미 있습니다.

| 화면 | 주 표 |
|---|---|
| Agent 실행 현황 | `tb_ai_agent` · `tb_ai_agent_run` |
| AI 모델 설정 | `tb_ai_model_config` · `tb_ai_defect_tag` · `tb_ai_defect_tag_map` |
| AI 서비스 버전 관리 | `tb_ai_serving_profile` · `tb_ai_serving_asset` · `tb_ai_serving_route` · `tb_ai_model_asset` · `tb_ai_corpus_snapshot` |
| 지표 측정 데이터 관리 | `tb_met_metric_std` · `tb_met_metric_collect` · `tb_met_metric_source` · `tb_met_metric_value` |

만드신 뒤 **`endpoints.js` 명세에도 등록**해 주세요.
지금 이 4개 화면은 명세(`WEB/src/services/api/endpoints.js`)에 아예 없습니다.
웹 화면은 별도 세션에서 붙일 것이라, API 와 명세까지가 이번 범위입니다.

### 순서 제안

`tb_ai_serving_*` 은 서로 FK 로 묶여 있어 한 묶음으로 보셔야 합니다.
`tb_ai_serving_profile` 이 `tb_ai_corpus_snapshot` 을 FK 로 참조합니다
(이 관계 때문에 정리 작업 때도 이 계열은 손대지 않고 남겨 뒀습니다).

**AI 모델 설정 → Agent 실행 현황 → 지표 측정 → 서비스 버전 관리** 순이
의존이 적은 쪽부터라 편할 것입니다.

---

## 확인 방법

```bash
# ① Agent 기록
curl -s "http://localhost:8080/api/v1/dashboard/ai/agents" -H "Authorization: Bearer <토큰>"
#   → 작업을 수행한 Agent 의 state 가 IDLE 이 아니고 last 에 시각이 들어오면 성공

# ② servingModelVer
curl -s "http://localhost:8080/api/v1/auth/me" -H "Authorization: Bearer <토큰>"
#   → servingModelVer 가 null 이 아니면 성공 (버전 데이터 등록 후)
```

---

## 참고

- 스키마는 **로컬 · 운영 서버 모두 V39 까지 적용**돼 동일합니다. 표를 새로 만들 필요는 없습니다.
- 화면을 켜는 것(`tb_sys_menu.use_flg = 'Y'`)은 웹 화면까지 준비된 뒤에 하는 편이 좋습니다.
  지금 켜면 메뉴만 뜨고 빈 화면이 나옵니다.
- `tb_ai_agent` 9행은 이미 들어 있습니다. `agent_id` 를 코드에 박지 말고
  `agent_no`(①~⑨)로 찾는 기존 방식을 유지해 주세요.
