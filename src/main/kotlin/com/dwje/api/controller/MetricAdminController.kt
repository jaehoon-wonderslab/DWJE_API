package com.dwje.api.controller

import com.dwje.api.common.response.ApiResponse
import com.dwje.api.model.request.MetricCollectRequest
import com.dwje.api.model.request.MetricSourceSaveRequest
import com.dwje.api.model.request.MetricStandardRequest
import com.dwje.api.model.request.MetricStandardStateRequest
import com.dwje.api.service.MetricStandardService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 지표 측정 데이터 관리 컨트롤러 (SY-13 · sys-metric)
 *
 * 여기서 정한 기준 수치(정상/주의/위험)는 대시보드 목표선, 알림 발송 조건, 보고서 신호등의
 * 공통 기준이 된다. 2026-09-15 에 화면과 함께 지웠다가 2026-09-22 요청으로 되살렸다.
 *
 * 빠진 것 — 기준 수치 변경 이력(No.220)은 근거 표 `ax.tb_met_metric_std_hist` 를 V31 이 지워
 * 제공하지 않는다. 변경 사실은 감사 로그(`ax.tb_log_audit`)에 남는다.
 *
 * 더한 것 — 수집 정의(`/collect`)와 산출 근거(`/sources`), 측정값 조회(`/values`).
 * `ax.tb_met_metric_value` 가 0행인 이유가 "값을 채우는 정의가 없어서" 라 그 자리를 연다.
 */
@RestController
@RequestMapping("/api/v1/metrics/standards")
@Tag(name = "12. 시스템관리 - 운영")
class MetricStandardController(
    private val metricStandardService: MetricStandardService
) {

    /** 지표 기준 요약 (No.215) */
    @Operation(summary = "지표 기준 요약", description = "등록 지표 수, 적용 수, 최근 24시간 판정 등급별 건수를 반환한다.")
    @GetMapping("/summary")
    fun summary(): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(metricStandardService.getSummary())

    /** 측정값 조회. `{stdId}` 매핑보다 먼저 선언한다. */
    @Operation(
        summary = "지표 측정값 조회",
        description = "ax.tb_met_metric_value 를 조회한다. 수집 정의가 없으면 이 표는 비어 있다(`/collect` 참고)."
    )
    @GetMapping("/values")
    fun values(
        @RequestParam(required = false) stdId: Int?,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @Parameter(description = "판정 — NORMAL|WARN|CRIT") @RequestParam(required = false) judge: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?
    ): ApiResponse<Map<String, Any?>> {
        val (rows, meta) = metricStandardService.getValues(stdId, from, to, judge, page, size)
        return ApiResponse.page(mapOf("items" to rows), meta)
    }

    /** 지표 기준 목록 조회 (No.216) */
    @Operation(
        summary = "지표 기준 목록 조회",
        description = "구분·적용 여부·판정 등급별 지표 기준을 조회한다. " +
            "direction(high=클수록 좋음 | low=작을수록 좋음)은 warn/critical 관계에서 산출된 값이다 — " +
            "critical > warn 이면 low, critical < warn 이면 high, 같으면 null(판별 불가)."
    )
    @GetMapping
    fun standards(
        @Parameter(description = "지표 구분 — DEFECT|EQPT|PROD|COLLECT|COST")
        @RequestParam(required = false) category: String?,
        @RequestParam(required = false) applied: Boolean?,
        @Parameter(description = "판정 등급 — NORMAL|WARN|CRIT") @RequestParam(required = false) level: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?
    ): ApiResponse<Map<String, Any?>> {
        val (rows, meta) = metricStandardService.getStandards(category, applied, level, page, size)
        return ApiResponse.page(mapOf("items" to rows), meta)
    }

    /** 지표 기준 등록 (No.217) */
    @Operation(
        summary = "지표 기준 등록",
        description = "새 지표와 정상/주의/위험 기준값을 등록한다. " +
            "direction 을 보내면 warn/critical 순서와 맞는지 검사한다 (어긋나면 400, 저장 컬럼은 아니다)."
    )
    @PostMapping
    fun createStandard(@Valid @RequestBody request: MetricStandardRequest): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(metricStandardService.createStandard(request), "지표 기준이 등록되었습니다.")

    /** 지표 기준 수치 수정 (No.218) */
    @Operation(
        summary = "지표 기준 수치 수정",
        description = "기준값을 수정하고 저장 시 판정을 다시 계산한다. " +
            "direction 은 저장되지 않고 수정 결과의 warn/critical 관계와 대조만 한다 — 방향을 바꾸려면 두 임계값을 보낸다."
    )
    @PutMapping("/{stdId}")
    fun updateStandard(
        @PathVariable stdId: Int,
        @Valid @RequestBody request: MetricStandardRequest
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(metricStandardService.updateStandard(stdId, request), "기준 수치가 수정되었습니다.")

    /** 지표 적용/해제 (No.219) */
    @Operation(summary = "지표 적용/해제", description = "지표의 대시보드·알림 적용 여부를 전환한다. 미적용 지표는 알림·판정에서 빠진다.")
    @PatchMapping("/{stdId}/state")
    fun changeState(
        @PathVariable stdId: Int,
        @Valid @RequestBody request: MetricStandardStateRequest
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(metricStandardService.changeStandardApplied(stdId, request.applied), "적용 상태가 변경되었습니다.")

    /** 기준 수치 사용처 조회 (No.221) */
    @Operation(summary = "기준 수치 사용처 조회", description = "해당 지표를 참조하는 알림 조건·대시보드·보고서를 반환한다.")
    @GetMapping("/{stdId}/usage")
    fun usage(@PathVariable stdId: Int): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(metricStandardService.getStandardUsage(stdId))

    /** 수집 정의 조회 */
    @Operation(
        summary = "지표 수집 정의 조회",
        description = "이 지표의 값을 무엇이 어떻게 채우는지와 산출 근거를 반환한다. 정의가 없으면 collect 가 null 이다."
    )
    @GetMapping("/{stdId}/collect")
    fun collect(@PathVariable stdId: Int): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(metricStandardService.getCollect(stdId))

    /** 수집 정의 저장 */
    @Operation(
        summary = "지표 수집 정의 저장",
        description = "수집 방식·주기·차원을 저장한다(지표당 한 건). 정의만 저장할 뿐 수집은 별도 프로세스가 수행한다."
    )
    @PutMapping("/{stdId}/collect")
    fun saveCollect(
        @PathVariable stdId: Int,
        @Valid @RequestBody request: MetricCollectRequest
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(metricStandardService.saveCollect(stdId, request), "수집 정의가 저장되었습니다.")

    /** 산출 근거 저장 */
    @Operation(
        summary = "지표 산출 근거 저장",
        description = "지표가 어느 표·열에서 계산되는지 등록한다. 보낸 목록으로 통째로 교체한다."
    )
    @PutMapping("/{stdId}/sources")
    fun saveSources(
        @PathVariable stdId: Int,
        @Valid @RequestBody request: MetricSourceSaveRequest
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(metricStandardService.saveSources(stdId, request), "산출 근거가 저장되었습니다.")
}
