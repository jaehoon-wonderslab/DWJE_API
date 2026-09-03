# 덕우전자 AX — API 현황 분석 및 연동 세션(term_64c8c8ce) 피드백 리포트

- **일시**: 2026-09-03 15:20 (KST)
- **작성 세션**: API 백엔드 개발 세션 (`term_fd0e1b0c-4c32-4446-a898-1495f409afd4`)
- **대상 세션**: React WEB 프론트엔드 세션 (`term_64c8c8ce-807b-4e5b-92c3-bb2f9540a260`)

---

## 1. API 백엔드 프로젝트 현황 분석 (`004. 개발/API`)

### 1-1. 기술 아키텍처
- **Language / Runtime**: Kotlin 2.1 / JDK 21 (toolchain)
- **Framework**: Spring Boot 3.4.4
- **Database Access**: `NamedParameterJdbcTemplate` 기반 **Native SQL Direct Binding** (iBatis/MyBatis 미사용으로 성능 및 SQL 투명성 확보)
- **Database**: PostgreSQL (`dwje-pg`, Docker port 5432, 4개 스키마: `ax` 운영계, `mes` 실적, `vec` 벡터, `common` 공통 마스터)
- **인증 / 권한**: JWT Bearer Token (`jjwt 0.12`), PBKDF2 암호화, 6개 부서별 메뉴 권한 및 7대 데이터 항목 마스킹(Blind) 필터
- **문서 / 툴ing**: springdoc-openapi (Swagger UI `http://localhost:8080/swagger-ui.html`), Apache POI 5.3 (엑셀 Export), Gradle 8.13

### 1-2. 엔드포인트 카탈로그 (총 261건)
- **GET**: 154건 (공통코드, 기준정보, 대시보드, 생산/품질 실적, 보고서, 시스템 관리 등)
- **POST**: 69건 (등록, 로그인, 검증, 승인, AI 질의/ASR/피드백 등)
- **PUT**: 25건 (수정, 지표 기준 수정, 마스킹 규칙 갱신 등)
- **DELETE**: 9건 (계정, 알림조건, 용어/유사어, AI 세션, **폐기 보고서 초안**)
- **PATCH**: 4건 (부분 상태 갱신)
- 기존 명세서(ver01)의 233건 전수 구현 + 프론트엔드 실서버 연동을 위한 28건 추가 구현 완료

### 1-3. 최근 커밋 내역 (`d91fbdc`, 2026-09-03 15:02:59)
1. **폐기 보고서 마법사 초안 삭제 API 구현**:
   - `DELETE /api/v1/reports/scrap/drafts/{draftId}` (`ReportController.kt`, `ScrapReportService.kt`, `ReportDocRepository.kt`)
   - 2~5단계 작성 도중 취소 시 초안 잔존 방지를 위한 소프트 삭제(`state = 'DELETED'`)
   - 발행(`PUBLISHED`)·확정(`CONFIRMED`) 완료 문서는 409 Conflict 반환하여 불법 삭제 차단
2. **지표 기준 `direction` 산출 및 판정 로직 개선**:
   - `GET /api/v1/metrics/standards` 응답에 `direction: "high" | "low" | null` 동적 산출 필드 추가
   - `PUT /api/v1/metrics/standards/{id}` 시 입력된 임계치와 현재값에 따라 `judgeLevel`("NORMAL", "WARN", "CRIT") 즉시 재계산
3. **요청 DTO 전환을 통한 `FAIL_ON_UNKNOWN_PROPERTIES` 100% 적용**:
   - `ProductRankService`: `updateFamilyOrder` (`FamilyOrderEntry`), `updateProductOrder` (`ProductOrderEntry`) DTO 전환
   - `GlossaryRequests`, `SystemRequests`: Map 본문 0개화 완료 (스펙 외 임의의 키 전송 시 400 Bad Request 및 오류 필드명 반환)

### 1-4. 빌드 및 런타임 상태
- **단위 테스트**: `./gradlew test` 8개 테스트 스위트 전수 통과 (`BUILD SUCCESSFUL in 20s`)
- **서버 런타임**:
  - 직전 세션에서 Graceful Shutdown 이후 세션 제한으로 재기동 대기 중이었던 **8080 포트를 local 프로파일로 백그라운드 정상 재기동 완료 (PID: 64180)**
  - `http://localhost:8080/api/v1/health` 정상 응답 검증 완료 (`status: UP, database: UP`)

---

## 2. 'term_64c8c8ce-807b-4e5b-92c3-bb2f9540a260' 연동 및 요청 대응/피드백

### 2-1. 연동 상대 세션 정보
- **세션 ID / Handle**: `term_64c8c8ce-807b-4e5b-92c3-bb2f9540a260`
- **역할**: `004. 개발/WEB` (React Native for Web) 화면 UI/UX 및 API 연동 개발 세션
- **현 상태**: 8080 서버 미기동으로 인해 API 테스트가 스킵된 상태에서 36개 전체 화면 명세 및 뷰 코드 전수 검토 수행 완료

### 2-2. 프론트엔드 주요 요구사항별 API 준비 상태
| 요구사항 | API 엔드포인트 | 구현 및 배포 상태 | 프론트엔드 조치 가이드 |
|---|---|---|---|
| **8080 서버 재기동** | `http://localhost:8080` | **정상 기동 완료 (PID 64180)** | `npm test` 및 `npm run test:api` 즉시 실행 가능 |
| **지표 기준 direction 지원** | `GET /api/v1/metrics/standards` | **완료 (`d91fbdc`)** | 응답의 `direction` ("high" / "low" / null) 바인딩 및 표시 |
| **폐기 보고서 초안 취소/삭제** | `DELETE /api/v1/reports/scrap/drafts/{draftId}` | **완료 (`d91fbdc`)** | 위저드 2~5단계 취소 버튼 클릭 시 해당 API 호출 |
| **알림 발송 조건 삭제** | `DELETE /api/v1/alert/conditions/{condId}` | **기구현 완료** | 조건 목록 화면 삭제 모달에서 해당 API 호출 |
| **학습셋 레지스트리 연동** | `GET /api/v1/ai/trainsets` | **DDL 준비 완료** | 사용자 지침(LLM 작업 보류) 해제 시 API 즉시 연동 |
| **고객사 스키마 확장** | `tb_prod_ship_plan` 복합키 | **사용자 확정 대기** | 고객사 8종 실데이터 확정 후 UNIQUE 키 변경 및 시드 투입 |

### 2-3. 웹 세션(`term_64c8c8ce`)에 전달할 피드백
1. **8080 API 서버 가동 완료**: 이제 모든 화면이 로컬 8080 실서버에 붙어 정상 통신 가능하며, 프론트엔드 자동 테스트 12개 스펙(92개 검사)을 전수 돌릴 수 있습니다.
2. **신규 API 연동**: 폐기 보고서 초안 삭제(`DELETE /reports/scrap/drafts/{draftId}`)와 지표 판정 방향(`direction`)이 이미 실서버에 배포되어 있으므로 즉시 뷰/컨트롤러에 연결하시면 됩니다.
3. **요청 본문 검증 주의**: 백엔드에 `FAIL_ON_UNKNOWN_PROPERTIES`가 활성화되어 있으므로 요청 Body에 스펙에 없는 임의의 키를 보낼 경우 400 에러가 발생합니다. 필드명은 `docs/REQUEST_BODY_CONTRACT.md`를 준수해 주십시오.
