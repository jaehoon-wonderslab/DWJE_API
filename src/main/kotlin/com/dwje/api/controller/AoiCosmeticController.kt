package com.dwje.api.controller

import com.dwje.api.common.response.ApiResponse
import com.dwje.api.service.AoiCosmeticService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * QC-02 AOI 외관 판정(`TB_SAMSUN_COSMETIC`) 집계 · 검사 항목 · 시리얼 목록/상세
 *
 * 2026-09-14 발주자 지시로 **AOI 는 이 원천만 쓴다**. 치수(`/aoi/dimension`)는 설비가 겹치지 않는 별개 화면이다
 * — 외관 `MN-*`(S135 레이저 · S138/W160 PACKING), 치수 `GP-*`·`MQ-*`(S110 도금 · S120 도장).
 *
 * 계약과 실측은 `docs/AOI_COSMETIC_SOURCE_SURVEY_20260914.md`.
 */
@RestController
@RequestMapping("/api/v1/quality/aoi/cosmetic")
@Tag(name = "05. 품질관리")
class AoiCosmeticController(
    private val cosmeticService: AoiCosmeticService
) {

    @Operation(
        summary = "AOI 외관 판정 집계",
        description = "기간·공정·설비로 걸러 제품 수·최종 불량 수·불량률과 검사 항목별 불량 건수·비중, 귀속(단일·복합·설명없음·오버라이드), 직전 동일 기간 대비를 반환한다. " +
            "**모든 비율의 분모는 제품 수이고 불량 판정은 FINAL_PASSED 다** — 한 행이 (제품 × 검사항목)이라 행을 세면 항목 수만큼 부풀려진다. " +
            "기간은 app.aoi.cosmetic.max-days 이하만 받는다(초과 400, field=to). 계정 미주입이면 reason=SOURCE_NOT_CONFIGURED. " +
            "집계·항목·시리얼 목록은 **같은 스냅샷**에서 나온다 — /items·/serials 를 이어 불러도 원천을 다시 읽지 않는다."
    )
    @GetMapping("/summary")
    fun summary(
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @Parameter(description = "직전 공정 코드 (S135 레이저 · S138 · W160). 비우면 전체") @RequestParam(required = false) wcCd: String?,
        @Parameter(description = "설비 코드 (MN-*). 비우면 전체") @RequestParam(required = false) eqptCd: String?,
        @Parameter(description = "직전 동일 기간 대비를 함께 낼지. 원천을 한 번 더 읽어 **시간이 두 배**가 된다. " +
            "비우면 직전 기간이 이미 보관돼 있을 때만 낸다(공짜일 때만).")
        @RequestParam(required = false) compare: Boolean?
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = cosmeticService.getSummary(from, to, wcCd, eqptCd, compare)
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    @Operation(
        summary = "AOI 외관 검사 항목 목록",
        description = "화면 유형 필터용. 설비마다 검사 항목 구성이 달라 전체 목록과 (공정, 설비)별 목록을 함께 낸다. " +
            "itemNm 은 설정(app.aoi.cosmetic.item-names)에 이름이 있을 때만 채워지며, 없으면 화면이 코드(DF003 등)를 그대로 쓴다. " +
            "집계 캐시를 그대로 쓰므로 원천을 다시 읽지 않는다."
    )
    @GetMapping("/items")
    fun items(
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) wcCd: String?,
        @RequestParam(required = false) eqptCd: String?
    ): ApiResponse<Map<String, Any?>> {
        val (data, mask) = cosmeticService.getItems(from, to, wcCd, eqptCd)
        return ApiResponse.ok(data, mask.maskedKeys())
    }

    @Operation(
        summary = "AOI 외관 시리얼 목록",
        description = "시리얼(LOT·SERIAL) 단위 목록. date 하루(기본 오늘) 또는 from/to. " +
            "행마다 prodCnt(제품 수)·prodNgCnt(최종 불량 제품 수)·ngRate·seqMin/seqMax·partial·cavity. " +
            "정렬 prodNgCnt(기본)·ngRate·lastAt·firstAt·serialNo·prodCnt. size=0 이면 전량."
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
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?
    ): ApiResponse<Map<String, Any?>> {
        val (data, meta, mask) = cosmeticService.getSerials(date, from, to, wcCd, eqptCd, sort, desc, page, size)
        return ApiResponse.page(data, meta, mask.maskedKeys())
    }

    @Operation(
        summary = "AOI 외관 시리얼 상세",
        description = "serialKey(wc~eqpt~lot~serial) 한 건의 제품(회차) 목록 한 쪽. only=ng(기본) 최종 불량 제품만 · all 전 제품. " +
            "items[{seq, finalPassed, measuredAt, cavity, checks[{itemCd, itemNm, value, passed}], ngItems[]}]. " +
            "쪽 나누기는 제품(SEQ) 단위다 — 항목 행 단위로 자르면 한 제품이 두 쪽에 걸린다. 잘못된 키 400, 없는 시리얼 404."
    )
    @GetMapping("/serials/{serialKey}")
    fun serial(
        @PathVariable serialKey: String,
        @Parameter(description = "ng(기본) | all") @RequestParam(required = false) only: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?
    ): ApiResponse<Map<String, Any?>> {
        val (data, meta, mask) = cosmeticService.getSerial(serialKey, only, page, size)
        return ApiResponse.page(data, meta, mask.maskedKeys())
    }
}
