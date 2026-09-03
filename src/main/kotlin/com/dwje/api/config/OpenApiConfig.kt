package com.dwje.api.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.tags.Tag
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * API 문서(Swagger UI) 설정
 *
 * 접근 경로 : /swagger-ui.html — 화이트리스트로 인증 없이 열람 가능하다.
 */
@Configuration
class OpenApiConfig {

    @Bean
    fun openApi(): OpenAPI {
        val schemeName = "bearerAuth"
        return OpenAPI()
            .info(
                Info()
                    .title("덕우전자 AX 시스템 API")
                    .version("v1.0")
                    .description(
                        """
                        덕우전자 AX 시스템 백엔드 API (총 233건)

                        - 인증 : Bearer Token (JWT). 로그인 시 accessToken / refreshToken 발급
                        - 권한 : 메뉴 접근 권한(부서 × 화면) + 데이터 접근 권한(부서 × 데이터 항목 7종) 2계층
                        - 마스킹 : 데이터 접근 권한이 없는 항목은 값을 null 로 반환하고 masked 배열에 key 를 표기
                        """.trimIndent()
                    )
            )
            .tags(DOMAIN_TAGS)
            .addSecurityItem(SecurityRequirement().addList(schemeName))
            .components(
                Components().addSecuritySchemes(
                    schemeName,
                    SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                )
            )
    }

    companion object {
        /**
         * 도메인 태그 정의
         *
         * 한 태그를 여러 컨트롤러가 공유하면(예: 대시보드 3종) 컨트롤러의 `@Tag(description=...)` 중
         * **마지막 하나만 반영되어** 엉뚱한 설명이 노출된다. 그래서 설명은 여기서만 정의하고
         * 컨트롤러에는 태그 이름만 둔다. 순서는 Swagger UI 의 표시 순서가 된다.
         */
        private val DOMAIN_TAGS: List<Tag> = listOf(
            tag("00. 공통", "헬스체크"),
            tag("01. 인증·공통", "로그인 · 토큰 · 내 권한 조회 · 메뉴 트리 · 공통코드 · 기준정보 (CM-01~05)"),
            tag("02. AI 질의", "자연어 질의 · 세션 · 추천 질의 · 응답 평가 (AI-01)"),
            tag("03. 대시보드", "AI 통합(DB-01) · 공정 및 제품(DB-02) · 성과지표(DB-03)"),
            tag("04. 생산관리", "생산 모니터링 · 실적 집계 · 일일 생산현황 보고 · 비가동 관리 (PR-01~05)"),
            tag("05. 품질관리", "불량 현황 · AOI 판정 분석/예측 · 품질 보고서 · 보고서 양식 (QC-01~04)"),
            tag("06. 이상 알림", "알림 목록 · 상세 · 확인 처리 · 승격 대상 · 발송 로그 (AL-01)"),
            tag("07. 보고서", "아침회의 자료 · 연간 출하계획 · 제품별 수율 · 고객사별 LRR · 폐기 보고서 (RP-01~07)"),
            tag("08. 시스템관리 - 계정·권한", "계정 · 부서 · 메뉴 권한 · 데이터 권한 · 보안 감사 로그 (SY-01~03, SY-09)"),
            tag("09. 시스템관리 - 알림", "발송 조건 · 수신 그룹 · 수신자 · 당번 · 승격 규칙 (SY-04~05)"),
            tag("10. 시스템관리 - 용어·제품", "용어 사전 · 제품군 순위 관리 (SY-06~07)"),
            tag("11. 시스템관리 - AI 운영", "질의 이력 · 모델 설정 · 모델 버전 · Agent 실행 현황 (SY-08, SY-10~12)"),
            tag("12. 시스템관리 - 운영", "지표 기준 · 다운로드 이력 · 데이터 연동 이력 (SY-13~15)")
        )

        private fun tag(name: String, description: String): Tag =
            Tag().name(name).description(description)
    }
}
