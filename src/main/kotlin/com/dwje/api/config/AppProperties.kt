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
 * @param llm                    사내 LLM 채팅 프록시 설정
 * @param unassignedDeptName     그룹웨어 자동 가입의 미배정 부서 이름 — MES 이관 엔진
 *                               `migration.groupware.ax-join.default-dept-name` 과 같은 값이어야 한다
 * @param trustedProxies         접속 IP 판정에서 믿는 앞단 프록시 주소 — 이 주소가 넘긴 요청만 X-Real-IP·X-Forwarded-For 를 본다
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

    /** 감사 로그(감사·권한 변경·로그인) 보존 연수 (09 AUD-11) — 지나면 아카이브 표로 옮긴다 */
    @field:Min(value = 1, message = "감사 로그 보존 연수는 1년 이상이어야 합니다.")
    @field:Max(value = 10, message = "감사 로그 보존 연수는 10년 이하로 설정하세요.")
    val auditRetentionYears: Int = 3,

    /**
     * 감사·다운로드 기록 아카이브 배치 사용 여부 (09 AUD-11 · 10 DLG-07).
     * 보존 기간·원본 삭제가 결정되기 전(공통 D-08)이라 모든 환경에서 기본 꺼짐이다.
     */
    val auditArchiveEnabled: Boolean = false,

    /** 아카이브 실행 주기(cron) — 기본 매월 1일 03:00 */
    val auditArchiveCron: String = "0 0 3 1 * *",

    /** 아카이브 cron 시간대 */
    val auditArchiveZone: String = "Asia/Seoul",

    /** 아카이브 한 트랜잭션에서 옮기는 최대 행 수 — 묶음이 작아야 잠금이 짧다 */
    @field:Min(value = 1, message = "아카이브 묶음 크기는 1 이상이어야 합니다.")
    val auditArchiveBatchSize: Int = 50_000,

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
    val aoi: AoiProperties = AoiProperties(),

    /** 사내 LLM 채팅 프록시(`/api/ai/chat`) — [LlmProxyProperties] */
    @field:jakarta.validation.Valid
    val llm: LlmProxyProperties = LlmProxyProperties(),

    /**
     * 그룹웨어 자동 가입 계정 중 부서 매핑이 없는 사람이 들어가는 부서 이름 (`ax.tb_sys_dept.dept_nm`).
     *
     * 엔진이 이 이름으로 부서를 찾아 넣고, API 는 같은 이름으로 미배정 계정을 찾는다.
     * 한쪽만 바꾸면 그룹웨어 부서 매핑 화면의 미배정 목록이 비어 보인다.
     */
    @field:NotBlank(message = "미배정 부서 이름(app.unassigned-dept-name)은 비워 둘 수 없습니다.")
    val unassignedDeptName: String = "미배정",

    /**
     * 접속 IP 판정에서 믿는 앞단 프록시 주소 (09 기획서 AUD-03).
     *
     * 요청을 넘긴 주소(`remoteAddr`)가 이 목록에 있을 때만 프록시가 채운 `X-Real-IP`·`X-Forwarded-For` 를 본다.
     * 목록 밖에서 온 요청의 헤더는 브라우저가 마음대로 적을 수 있으므로 무시하고 `remoteAddr` 를 쓴다.
     * 기본은 같은 서버의 프록시(루프백)뿐이다. 앞단 구성이 바뀌면 yml 을 고친다.
     */
    val trustedProxies: List<String> = listOf("127.0.0.1", "::1", "0:0:0:0:0:0:0:1"),

    /** 다운로드 이력 기록 — [DownloadLogProperties] (10 기획서 DLG-05) */
    val downloadLog: DownloadLogProperties = DownloadLogProperties(),

    /** 데이터 연동 상태 판정 — [SyncHealthProperties] (12 기획서 SYN-03) */
    val sync: SyncHealthProperties = SyncHealthProperties(),

    /** 알림 테스트 발송·수신 대상 판정 — [AlertProperties] (05 ALC-03, 06 RCP-03·04) */
    val alert: AlertProperties = AlertProperties()
)

/**
 * 알림 테스트 발송·수신 대상 판정 설정 (`app.alert.*`)
 *
 * 값은 Alert_Engine 설정(`alert.night`, 웹 주소)과 같아야 한다 — 테스트 발송(API)과 실발송(엔진)의
 * 대상이 어긋나지 않게 하기 위함이다.
 *
 * @param webBaseUrl            메일 본문 알림 링크의 웹 주소. 비어 있으면 링크 줄을 넣지 않는다(환경변수 AX_WEB_BASE_URL)
 * @param nightFrom             야간 시작(포함, HH:mm)
 * @param nightTo               야간 끝(제외, HH:mm). 시작보다 이르면 자정을 넘긴다
 * @param receivableUserStates  알림을 받을 수 있는 계정 상태(SYS_USER_STATE). 잠긴 계정(LOCKED)은 로그인만 막혔으므로 받는다
 *                              (2026-10-01 3단계 결정). 엔진 RCP-04(E-1)와 같은 값이어야 한다
 */
data class AlertProperties(
    val webBaseUrl: String = "",
    val nightFrom: String = "22:00",
    val nightTo: String = "06:00",
    val receivableUserStates: List<String> = listOf("ACTIVE", "LOCKED"),
    /** 지표 수집 중단 판정 배수 — 최근 측정값이 max(평가 주기 × 배수, 600초) 보다 오래되면 중단. 엔진 stale-factor 와 같은 값 (05 ALC-08) */
    val staleFactor: Int = 3
)

/**
 * 데이터 연동 상태 줄 판정 기준 (`app.sync.*`) — 실서버 배치 주기를 확인한 뒤 조정한다.
 *
 * @param staleWarnMin    마지막 정상 이관 후 이 분 이상이면 주의(WARN)
 * @param staleDownMin    이 분 이상이면 중단(DOWN)
 * @param stalePendingMin 예약 시각이 이 분 넘게 지난 PENDING 작업을 「오래된 예약」 으로 센다
 */
data class SyncHealthProperties(
    val staleWarnMin: Long = 30,
    val staleDownMin: Long = 120,
    val stalePendingMin: Long = 10
)

/**
 * 다운로드 이력 기록 설정 (`app.download-log.*`)
 *
 * @param requireMenuId 브라우저 내려받기 신고에 화면 ID(menuId)·범위 코드(scopeCd)를 필수로 받을지.
 *                      새 WEB 이 모든 화면에서 menuId 를 보내는 것을 확인한 뒤 켠다. 그 전에는 menuId 가 없는
 *                      옛 신고도 기록한다(menuId 가 있으면 존재·조회 권한을 항상 확인한다).
 */
data class DownloadLogProperties(
    val requireMenuId: Boolean = false
)
