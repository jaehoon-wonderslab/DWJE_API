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
 * @param pressWorkcenters       프레스 작업장 코드 — 일일 생산현황 보고·PRESS 아침회의의 기본 범위
 * @param platingWorkcenters     도금·코팅 작업장 코드 — Plating·Coating 아침회의의 기본 범위
 * @param anomalyMinQty          이상 후보 설비의 최소 생산량 — 이 미만은 후보에서 뺀다
 * @param ai                     sLLM 서빙 설정
 * @param upload                 업로드 문서 저장소 설정
 * @param nas                    NAS 이미지 경로 설정
 * @param aoi                    AOI 치수 원천(MSSQL) 직접 조회·한계 세트 설정
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
    val downloadRetentionYears: Int = 3,

    /**
     * 프레스 작업장 코드 — 일일 생산현황 보고는 공정을 지정하지 않으면 이 범위만 집계한다.
     *
     * `mes.tb_md_workcenter` 에 공정 구분 컬럼이 없어 작업장 이름(`%프레스%`)으로만
     * 알 수 있다. 이름에 기대면 현장에서 이름을 바꾸는 순간 집계 범위가 조용히 바뀌므로
     * 코드로 못 박아 설정에 둔다. 작업장이 늘면 yml 을 고친다.
     */
    @field:jakarta.validation.constraints.NotEmpty(
        message = "프레스 작업장 코드(app.press-workcenters)는 최소 한 건이 필요합니다."
    )
    val pressWorkcenters: List<String> = listOf("W110", "W150", "W120"),

    /**
     * 도금·코팅 작업장 코드 — Plating·Coating 아침회의 자료의 기본 범위.
     *
     * [pressWorkcenters] 와 같은 이유로 코드를 못 박는다. 이름(`%PLATING%`·`%COATING%`)
     * 으로 고르면 현장에서 이름을 바꾸는 순간 범위가 조용히 바뀐다.
     */
    @field:jakarta.validation.constraints.NotEmpty(
        message = "도금·코팅 작업장 코드(app.plating-workcenters)는 최소 한 건이 필요합니다."
    )
    val platingWorkcenters: List<String> = listOf(
        "V110", "V111", "V112", "V113",
        "S114", "S115", "S140", "S116", "S110", "S117", "S118", "S112", "S113",
        "S121", "S122", "S123", "S120", "S142"
    )
,

    /**
     * 이상 후보 설비의 최소 생산량 — 이 수량 미만인 설비는 후보에서 뺀다.
     *
     * 생산량이 적으면 불량 1건으로도 불량률 100% 가 되어 그날의 최대 이슈로 올라온다.
     * (실측: 1건 생산 · 1건 불량 · 불량률 100% · 이상점수 99)
     *
     * **현업 확인 전 임시값이다.** 얼마가 맞는지는 현장 감각이 필요해 설정으로 빼 두었다.
     * 값이 정해지면 yml 을 고친다.
     */
    @field:Min(value = 1, message = "이상 후보 최소 생산량(app.anomaly-min-qty)은 1 이상이어야 합니다.")
    val anomalyMinQty: Long = 1000
,

    /** sLLM 서빙 설정 — [AiProperties] */
    @field:jakarta.validation.Valid
    val ai: AiProperties = AiProperties(),

    /** 업로드 문서 저장소 — [UploadProperties] */
    @field:jakarta.validation.Valid
    val upload: UploadProperties = UploadProperties(),

    /** AOI 치수 원천(MSSQL) 직접 조회·한계 세트 — [AoiProperties] */
    @field:jakarta.validation.Valid
    val aoi: AoiProperties = AoiProperties()
)
