package com.dwje.api.controller

import com.dwje.api.common.response.ApiResponse
import com.dwje.api.service.CommonMasterService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 공통 코드 · 기준정보 조회 컨트롤러 (CM-05, DB-02)
 *
 * 전 부서가 접근 가능한 조회 전용 API 이며, 고객사·금형 항목은 데이터 접근 권한에 따라 마스킹된다.
 */
@RestController
@RequestMapping("/api/v1/common")
@Tag(name = "01. 인증·공통")
class CommonMasterController(
    private val commonMasterService: CommonMasterService
) {

    /**
     * 공통코드 조회 (No.7)
     *
     * @param groupCd 코드 그룹 (예: ALM_SEVERITY, MET_UNIT, SYS_USER_STATE)
     */
    @Operation(summary = "공통코드 조회", description = "비가동 표준분류·불량유형·상태값 등 공통코드를 조회한다.")
    @GetMapping("/codes")
    fun codes(
        @Parameter(description = "코드 그룹 코드") @RequestParam(required = false) groupCd: String?
    ): ApiResponse<Map<String, Any?>> {
        val codes = commonMasterService.getCodes(groupCd)
        return ApiResponse.ok(mapOf("codes" to codes))
    }

    /**
     * 공정 목록 조회 (No.8)
     */
    @Operation(summary = "공정 목록 조회", description = "Press / A Plating / B Plating / Coating 공정 정보를 조회한다.")
    @GetMapping("/masters/processes")
    fun processes(): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(mapOf("processes" to commonMasterService.getProcesses()))

    /**
     * 설비 목록 조회 (No.9)
     *
     * @param processId 공정 코드
     * @param keyword   설비코드/설비명 검색어
     */
    @Operation(summary = "설비 목록 조회", description = "공정별 설비 목록을 조회한다.")
    @GetMapping("/masters/equipments")
    fun equipments(
        @Parameter(description = "공정(작업장) 코드") @RequestParam(required = false) processId: String?,
        @Parameter(description = "설비코드·설비명 검색어") @RequestParam(required = false) keyword: String?
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(mapOf("equipments" to commonMasterService.getEquipments(processId, keyword)))

    /**
     * 제품 목록 조회 (No.10) — 제품 선택 팝업(113종) 검색·필터·정렬 대응
     */
    @Operation(summary = "제품 목록 조회", description = "제품 선택 팝업용 목록을 검색·필터·정렬해 조회한다.")
    @GetMapping("/masters/products")
    fun products(
        @Parameter(description = "제품코드·제품명 검색어") @RequestParam(required = false) keyword: String?,
        @Parameter(description = "제품군 코드") @RequestParam(required = false) familyCd: String?,
        @Parameter(description = "고객사 코드") @RequestParam(required = false) customerCd: String?,
        @Parameter(description = "프로젝트 코드") @RequestParam(required = false) projectCd: String?,
        @Parameter(description = "정렬 — rank|name|family") @RequestParam(required = false) sort: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?
    ): ApiResponse<Map<String, Any?>> {
        val (rows, meta, mask) = commonMasterService.getProducts(keyword, familyCd, customerCd, projectCd, sort, page, size)
        return ApiResponse.page(mapOf("products" to rows), meta, mask.maskedKeys())
    }

    /**
     * 고객사 목록 조회 (No.11)
     */
    @Operation(summary = "고객사 목록 조회", description = "고객사 목록을 조회한다. (customer 데이터 권한 필요)")
    @GetMapping("/masters/customers")
    fun customers(): ApiResponse<Map<String, Any?>> {
        val (rows, mask) = commonMasterService.getCustomers()
        return ApiResponse.ok(mapOf("customers" to rows), mask.maskedKeys())
    }

    /**
     * 불량 유형 목록 조회 (No.12)
     *
     * @param processId 공정 코드
     */
    @Operation(summary = "불량 유형 목록 조회", description = "공정별 불량 유형과 AI 불량 태그 분류를 조회한다.")
    @GetMapping("/masters/defect-types")
    fun defectTypes(
        @Parameter(description = "공정(작업장) 코드") @RequestParam(required = false) processId: String?
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(mapOf("defectTypes" to commonMasterService.getDefectTypes(processId)))

    /**
     * 금형 목록 조회 (No.13)
     *
     * @param eqptCd 설비 코드
     */
    @Operation(summary = "금형 목록 조회", description = "설비별 금형 목록을 조회한다. (mold 데이터 권한 필요)")
    @GetMapping("/masters/molds")
    fun molds(
        @Parameter(description = "설비 코드") @RequestParam(required = false) eqptCd: String?
    ): ApiResponse<Map<String, Any?>> {
        val (rows, mask) = commonMasterService.getMolds(eqptCd)
        return ApiResponse.ok(mapOf("molds" to rows), mask.maskedKeys())
    }

    /**
     * 실적 데이터 보유 기간 조회
     *
     * 화면이 기준일 선택기를 초기화할 때 사용한다. `toDate` 가 실적이 존재하는 마지막 날짜다.
     */
    @Operation(
        summary = "실적 데이터 보유 기간 조회",
        description = "실적이 존재하는 최초·최종 일자를 반환한다. 화면 기준일 기본값을 toDate 로 설정한다."
    )
    @GetMapping("/data-range")
    fun dataRange(
        @Parameter(description = "사업장 코드") @RequestParam(required = false) plantCd: String?,
        @Parameter(description = "공정 코드 — 주면 그 공정의 실적 보유 구간을 반환한다.")
        @RequestParam(required = false) processId: String?
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(commonMasterService.getProductionDateRange(plantCd, processId))
}
