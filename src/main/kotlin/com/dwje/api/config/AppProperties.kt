package com.dwje.api.config

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * 애플리케이션 업무 공통 설정 (`app.*`)
 *
 * ## 설정 우선순위
 * `application-{profile}.yml` 값 > Kotlin 생성자 기본값.
 *
 * 생성자 기본값은 **yml 에 키가 아예 없을 때의 안전망**이다(단위 테스트, 최소 구성 배포).
 * 실제 운영 값은 `application.yml` 의 `app:` 블록에서 관리한다.
 * 값을 바꿀 때는 yml 을 수정한다 — 생성자 기본값만 바꾸면 yml 이 이기므로 반영되지 않는다.
 *
 * ## 범위 제약을 두는 이유
 * 오타 한 글자로 업무가 조용히 뒤집히는 값들이다.
 * `login-fail-limit: 0` 이면 첫 실패에 계정이 잠기고, 음수면 영영 잠기지 않는다.
 * 기동 시점에 걸러 운영 중 사고를 막는다.
 *
 * @param defaultPlantCd         기본 사업장 코드 — MESDB_M 실적 데이터는 전건 PL01
 * @param loginFailLimit         로그인 연속 실패 잠금 임계값
 * @param maskingEnabled         데이터 접근 권한 기반 마스킹 사용 여부 (로컬 디버깅 시 해제 가능)
 * @param downloadRetentionYears 다운로드 이력 보존 연수
 */
@Validated
@ConfigurationProperties(prefix = "app")
data class AppProperties(

    @field:NotBlank(message = "기본 사업장 코드(app.default-plant-cd)는 비워 둘 수 없습니다.")
    val defaultPlantCd: String = "PL01",

    @field:Min(value = 1, message = "로그인 실패 잠금 임계값은 1 이상이어야 합니다.")
    @field:Max(value = 20, message = "로그인 실패 잠금 임계값은 20 이하로 설정하세요.")
    val loginFailLimit: Int = 5,

    val maskingEnabled: Boolean = true,

    @field:Min(value = 1, message = "다운로드 이력 보존 연수는 1년 이상이어야 합니다.")
    @field:Max(value = 10, message = "다운로드 이력 보존 연수는 10년 이하로 설정하세요.")
    val downloadRetentionYears: Int = 3
)
