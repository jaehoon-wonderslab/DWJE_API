package com.dwje.api.controller

import com.dwje.api.common.response.ApiResponse
import com.dwje.api.model.request.GlossaryNormalizeRequest
import com.dwje.api.model.request.GlossaryTermRequest
import com.dwje.api.model.request.GlossaryVariantRequest
import com.dwje.api.service.GlossaryService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 용어 사전 관리 컨트롤러 (SY-06)
 *
 * 접근 부서 : 전 부서 (유사어는 본인 등록 건만 수정·삭제 가능)
 */
@RestController
@RequestMapping("/api/v1/glossary")
@Tag(name = "10. 시스템관리 - 용어·제품")
class GlossaryController(
    private val glossaryService: GlossaryService
) {

    /** 용어 사전 요약 (No.170) */
    @Operation(summary = "용어 사전 요약", description = "용어·유사어·도메인 수와 내가 등록한 유사어 건수를 반환한다.")
    @GetMapping("/summary")
    fun summary(): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(glossaryService.getSummary())

    /** 용어 분류 목록 조회 */
    @Operation(
        summary = "용어 분류 목록 조회",
        description = "용어 등록 화면의 분류 선택지. code 를 용어 등록의 domainCd 에 그대로 넣는다."
    )
    @GetMapping("/domains")
    fun domains(): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(glossaryService.getDomains())

    /** 용어 목록 조회 (No.171) */
    @Operation(summary = "용어 목록 조회", description = "공식 용어와 등록된 유사어를 함께 조회한다.")
    @GetMapping("/terms")
    fun terms(
        @RequestParam(required = false) keyword: String?,
        @Parameter(description = "도메인명") @RequestParam(required = false) domainCd: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?
    ): ApiResponse<Map<String, Any?>> {
        val (rows, meta) = glossaryService.getTerms(keyword, domainCd, page, size)
        return ApiResponse.page(mapOf("items" to rows), meta)
    }

    /** 공식 용어 등록 (No.172) */
    @Operation(summary = "공식 용어 등록", description = "공식 용어와 정의를 등록한다.")
    @PostMapping("/terms")
    fun createTerm(@Valid @RequestBody request: GlossaryTermRequest): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(
            glossaryService.createTerm(request.term, request.definition, request.domainCd),
            "용어가 등록되었습니다."
        )

    /** 공식 용어 수정 (No.173) */
    @Operation(summary = "공식 용어 수정", description = "공식 용어와 정의를 수정한다.")
    @PutMapping("/terms/{termId}")
    fun updateTerm(
        @PathVariable termId: Int,
        @Valid @RequestBody request: GlossaryTermRequest
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(
            glossaryService.updateTerm(termId, request.term, request.definition, request.domainCd),
            "용어가 수정되었습니다."
        )

    /** 공식 용어 삭제 */
    @Operation(
        summary = "공식 용어 삭제",
        description = "등록자 본인 또는 통합관리자만 삭제할 수 있다. 사용 중지로 처리하며, " +
            "딸린 유사어도 정규화 사전에서 함께 빠진다(deactivatedVariants 로 건수를 알려 준다)."
    )
    @DeleteMapping("/terms/{termId}")
    fun deleteTerm(@PathVariable termId: Int): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(glossaryService.deleteTerm(termId), "용어가 삭제되었습니다.")

    /** 유사어 등록 (No.174) */
    @Operation(summary = "유사어 등록", description = "공식 용어에 현장 유사어를 등록한다.")
    @PostMapping("/terms/{termId}/variants")
    fun createVariant(
        @PathVariable termId: Int,
        @Valid @RequestBody request: GlossaryVariantRequest
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(glossaryService.createVariant(termId, request.word), "유사어가 등록되었습니다.")

    /** 유사어 수정 (No.175) */
    @Operation(summary = "유사어 수정", description = "본인이 등록한 유사어를 수정한다.")
    @PutMapping("/variants/{variantId}")
    fun updateVariant(
        @PathVariable variantId: Int,
        @Valid @RequestBody request: GlossaryVariantRequest
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(glossaryService.updateVariant(variantId, request.word), "유사어가 수정되었습니다.")

    /** 유사어 삭제 (No.176) */
    @Operation(summary = "유사어 삭제", description = "본인이 등록한 유사어를 삭제한다.")
    @DeleteMapping("/variants/{variantId}")
    fun deleteVariant(@PathVariable variantId: Int): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(glossaryService.deleteVariant(variantId), "유사어가 삭제되었습니다.")

    /** 용어 정규화 미리보기 (No.177) */
    @Operation(summary = "용어 정규화 미리보기", description = "문장의 현장 유사어를 공식 용어로 치환한 결과를 미리 본다.")
    @PostMapping("/normalize")
    fun normalize(@Valid @RequestBody request: GlossaryNormalizeRequest): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(glossaryService.normalize(request))

    /** 용어 임베딩 재생성 (No.178) */
    @Operation(summary = "용어 임베딩 재생성", description = "용어·유사어 임베딩 재생성 작업을 등록한다.")
    @PostMapping("/reindex")
    fun reindex(): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(glossaryService.reindex(), "임베딩 재생성 작업을 등록했습니다.")
}
