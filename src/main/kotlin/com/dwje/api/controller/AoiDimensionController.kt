package com.dwje.api.controller

import com.dwje.api.common.response.ApiResponse
import com.dwje.api.model.request.AoiBriefingRequest
import com.dwje.api.service.AoiBriefingService
import com.dwje.api.service.AoiDimensionService
import com.dwje.api.service.AoiSerialService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * QC-02 AOI 치수(TB_SAMSUN_DIMENSION) 집계 · 한계 세트 · AI 브리핑
 *
 * 원천은 MSSQL EDGE 를 **조회 시점에 직접** 읽는다(적재 배치 없음 — 현업 결정 13).
 * 계약과 실측은 `docs/AOI_DIMENSION_API_20260913.md`.
 */
@RestController
@RequestMapping("/api/v1/quality/aoi/dimension")
@Tag(name = "05. 품질관리")
class AoiDimensionController(
    private val dimensionService: AoiDimensionService,
    private val briefingService: AoiBriefingService,
    private val serialService: AoiSerialService
) {

    /** 시리얼 목록 (실측 문서 C-1 · 6차 — failSeqCnt · failSeqs) */
    @Operation(
        summary = "AOI 시리얼 목록",
        description = "DIMENSION 원천의 시리얼(LOT·SERIAL) 단위 판정 목록. date 하루(기본 오늘) 또는 from/to(상한 app.aoi.max-days). " +
            "행마다 seqCnt·failSeqCnt(시리얼 전체 기준)·failRate·passed·daySeqCnt·seqMin/seqMax·partial·cavity·faiUsed 와 " +
            "앞쪽 불량 회차 번호 failSeqs(기본 20개, failSeqsTruncated 로 잘림 표시). 정렬 failSeqCnt(기본)·failRate·lastAt·firstAt·serialNo·seqCnt. size=0 이면 전량."
    )
    @GetMapping("/serials")
    fun serials(
        @Parameter(description = "하루 조회 (YYYY-MM-DD). 주면 from/to 를 무시한다") @RequestParam(required = false) date: String?,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) wcCd: String?,
        @RequestParam(required = false) eqptCd: String?,
        @RequestParam(required = false) sort: String?,
        @RequestParam(required = false) desc: Boolean?,
        @Parameter(description = "행마다 싣는 불량 회차 번호 수 (0~100, 기본 20)") @RequestParam(required = false) failSeqsTop: Int?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?
    ): ApiResponse<Map<String, Any?>> {
        val (data, meta, mask) = serialService.getSerials(date, from, to, wcCd, eqptCd, sort, desc, failSeqsTop, page, size)
        return ApiResponse.page(data, meta, mask.maskedKeys())
    }

    /** 시리얼 상세 (실측 문서 C-2) */
    @Operation(
        summary = "AOI 시리얼 상세",
        description = "serialKey(wc~eqpt~lot~serial) 한 건의 회차 목록 한 쪽. only=ng(기본) 불량 회차만 · all 전 회차, 기본 100개. " +
            "items[{seq, passed, measuredAt, cavity, measurements[{no,value}], violFais[]}] 와 faiNos·spec(확정 상·하한)."
    )
    @GetMapping("/serials/{serialKey}")
    fun serial(
        @PathVariable serialKey: String,
        @Parameter(description = "ng(기본) | all") @RequestParam(required = false) only: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?
    ): ApiResponse<Map<String, Any?>> {
        val (data, meta, mask) = serialService.getSerial(serialKey, only, page, size)
        return ApiResponse.page(data, meta, mask.maskedKeys())
    }

    /** 집계 조회 (기획 API 1) */
    @Operation(
        summary = "AOI 치수 집계",
        description = "기간·작업장·설비로 걸러 측정 수·불량 수·불량률, FAI 번호별 위반 건수·초과량 합/평균, 귀속 유형별 건수(단일·복합·미확정 항목 이탈·측정 실패), " +
            "설명률, 직전 동일 기간 대비를 반환한다. MSSQL 원천을 NOLOCK 으로 직접 읽고 조회 조건 단위로 결과를 보관한다(fromCache). " +
            "기간은 app.aoi.max-days 이하만 받는다(초과 400, field=to). 계정 미주입이면 reason=SOURCE_NOT_CONFIGURED."
    )
    @GetMapping("/summary")
    fun summary(
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @Parameter(description = "작업장 코드 — 필수 (S110 · S120)") @RequestParam(required = false) wcCd: String?,
        @Parameter(description = "설비 코드. 비우면 작업장 전체") @RequestParam(required = false) eqptCd: String?
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = dimensionService.getSummary(from, to, wcCd, eqptCd)
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    /** 한계 세트 조회 (기획 API 2) — 설정에서 읽어 그대로 */
    @Operation(
        summary = "AOI 확정 한계 세트",
        description = "(작업장, 설비)별 확정 상·하한과 분해능·근거를 반환한다. eqptCd 를 주면 그 설비에 적용되는 세트(resolved)를 함께 낸다."
    )
    @GetMapping("/limits")
    fun limits(
        @RequestParam(required = false) wcCd: String?,
        @RequestParam(required = false) eqptCd: String?
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(dimensionService.getLimitSets(wcCd, eqptCd))

    /** 브리핑 생성 (기획 API 3) */
    @Operation(
        summary = "AOI 치수 AI 브리핑",
        description = "집계(캐시)를 sLLM 에 넣어 요약 문장과 조치 제안을 만든다. 숫자는 서버가 계산한 ruleLines·facts 에 있고 모델 문장은 근거 대조를 통과한 것만 aiLines 에 담는다. " +
            "조치 제안(actions)에는 「(추정)」 이 붙는다. 모델이 없으면 reason=MODEL_NOT_READY, 바쁘면 MODEL_BUSY 이며 ruleLines 는 항상 나간다."
    )
    @PostMapping("/briefing")
    fun briefing(@Valid @RequestBody request: AoiBriefingRequest): ApiResponse<Map<String, Any?>> {
        val (data, mask) = briefingService.briefing(request.from, request.to, request.wcCd.trim(), request.eqptCd)
        return ApiResponse.ok(data, mask.maskedKeys())
    }
}
