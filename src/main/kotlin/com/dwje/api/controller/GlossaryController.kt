package com.dwje.api.controller

import com.dwje.api.common.response.ApiResponse
import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.util.ReportFormat
import com.dwje.api.model.request.GlossaryExportRequest
import com.dwje.api.model.request.GlossaryNormalizeRequest
import com.dwje.api.model.request.GlossaryTermRequest
import com.dwje.api.model.request.GlossaryVariantRequest
import com.dwje.api.service.DownloadLogService
import com.dwje.api.service.ExportService
import com.dwje.api.service.GlossaryService
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.ResponseEntity
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
import org.springframework.web.multipart.MultipartFile
import org.springframework.http.MediaType

/**
 * 용어 사전 관리 컨트롤러 (SY-06)
 *
 * 접근 : 조회 API(요약·목록·상세·내려받기)는 `sys-gloss` 또는 `gloss-view`(용어 사전 조회, GL-01),
 *        쓰기·미리보기는 `sys-gloss`, 공식 용어 편집·위험 유사어 점검은 통합관리자 · 값 마스킹 : 없음
 *        (유사어는 쓰기 권한자가 본인 등록 건만 수정·삭제)
 */
@RestController
@RequestMapping("/api/v1/glossary")
@Tag(name = "10. 시스템관리 - 용어·제품")
class GlossaryController(
    private val glossaryService: GlossaryService,
    private val exportService: ExportService,
    private val downloadLogService: DownloadLogService
) {

    /** 용어 사전 요약 (No.170) */
    @Operation(summary = "용어 사전 요약", description = "용어·유사어·도메인 수와 내가 등록한 유사어 건수를 반환한다.")
    @GetMapping("/summary")
    fun summary(): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(glossaryService.getSummary())

    /** 용어 목록 조회 (No.171) */
    @Operation(summary = "용어 목록 조회", description = "공식 용어와 등록된 유사어를 함께 조회한다.")
    @GetMapping("/terms")
    fun terms(
        @RequestParam(required = false) keyword: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
        @Parameter(description = "내가 등록한 유사어가 있는 용어만 — sys-gloss 호출자에게만 효과") @RequestParam(required = false) mineOnly: Boolean?
    ): ApiResponse<Map<String, Any?>> {
        val (rows, meta) = glossaryService.getTerms(keyword, page, size, mineOnly)
        return ApiResponse.page(mapOf("items" to rows), meta)
    }

    /** 사전 변경 이력 (07 GLS-07) — sys-gloss 화면 권한만 */
    @Operation(summary = "사전 변경 이력", description = "공식 용어·유사어의 등록·수정·삭제·되살림 이력. termId 가 있으면 그 용어 전 이력, 없으면 기본 최근 30일. 권한 sys-gloss.")
    @GetMapping("/changes")
    fun changes(
        @RequestParam(required = false) termId: Int?,
        @Parameter(description = "시작일 yyyy-MM-dd") @RequestParam(required = false) from: String?,
        @Parameter(description = "종료일 yyyy-MM-dd") @RequestParam(required = false) to: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?
    ): ApiResponse<Map<String, Any?>> {
        val (rows, meta) = glossaryService.getChanges(termId, from, to, page, size)
        return ApiResponse.page(mapOf("items" to rows), meta)
    }

    /** 용어 상세 (GL-01 — 용어 사전 조회·관리 공용) */
    @Operation(summary = "용어 상세", description = "공식 용어 하나의 뜻·유사어·관련 용어(최대 10건)를 조회한다. 권한 sys-gloss 또는 gloss-view.")
    @GetMapping("/terms/{termId}")
    fun termDetail(@PathVariable termId: Int): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(glossaryService.getTermDetail(termId))

    /**
     * 용어 사전 내려받기 (GL-01 · SY-06 공유) — 서버가 xlsx 를 만들고 다운로드 이력을 직접 남긴다.
     * 화면은 따로 이력을 신고하지 않는다.
     */
    @Operation(
        summary = "용어 사전 내려받기",
        description = "용어 사전을 서버가 xlsx 로 만든다. scopeCd=ALL 이면 조건을 무시하고 사용 중 용어 전체(상한 5,000). " +
            "권한은 조회 권한(sys-gloss 또는 gloss-view). 다운로드 이력은 서버가 기록한다."
    )
    @PostMapping("/terms/export")
    fun exportTerms(@Valid @RequestBody(required = false) request: GlossaryExportRequest?): ResponseEntity<ByteArrayResource> {
        if (request?.format != null && request.format.trim().lowercase() != "xlsx") {
            throw InvalidParameterException("지원하지 않는 형식입니다. [xlsx]", "format")
        }
        val export = glossaryService.exportTerms(request)
        val fileName = "glossary_${exportService.timestamp()}"
        val blind = exportService.blindCells()
        // 가린 용어의 칸(R-18)도 비공개 건수에 넣는다 — 파일 안내와 다운로드 이력 blindCnt 가 같다
        repeat(export.blindedCells) { blind.mark(com.dwje.api.common.util.DataField.CUSTOMER) }
        val file = exportService.excel(fileName, export.headers, export.keys, export.rows, "용어 사전", blind)
        downloadLogService.record(
            reportId = null,
            reportNm = "용어 사전",
            menuId = export.menuId,
            format = ReportFormat.XLSX,
            scope = export.condSummary,
            rowCnt = export.rows.size,
            blindCnt = blind.total,
            blindCells = blind.counts(),
            fileNm = "$fileName.xlsx",
            params = mapOf("keyword" to request?.keyword, "mineOnly" to request?.mineOnly,
                "scopeCd" to export.scopeCd, "total" to export.total, "truncated" to (export.total > export.rows.size)),
            fileSize = file.body?.contentLength(),
            scopeCd = export.scopeCd ?: "ALL",
            condSummary = export.condSummary
        )
        return exportService.withExportTotals(file, export.total, export.rows.size)
    }

    /** 용어 사전 업로드용 템플릿 — 내려받기 이력은 서버가 남긴다 */
    @Operation(
        summary = "용어 사전 업로드 템플릿",
        description = "업로드용 xlsx. 「용어」 시트(머리글 공식 용어*·뜻*·고객사 정보·유사어 + 예시 2행) · 「안내」 시트(규칙·글자 수). " +
            "권한 sys-gloss. 다운로드 이력은 서버가 기록한다."
    )
    @GetMapping("/import/template")
    fun importTemplate(): ResponseEntity<ByteArrayResource> {
        val bytes = glossaryService.importTemplate()
        val fileName = "glossary_import_template.xlsx"
        downloadLogService.record(
            reportId = null, reportNm = "용어 사전 업로드 템플릿", menuId = com.dwje.api.common.util.MenuId.SYS_GLOSS,
            format = ReportFormat.XLSX, scope = "템플릿", rowCnt = 0, blindCnt = 0, fileNm = fileName,
            fileSize = bytes.size.toLong(), scopeCd = "ALL", condSummary = "업로드 템플릿"
        )
        return exportService.xlsx(bytes, fileName)
    }

    /** 용어 사전 엑셀 업로드 — dryRun=true(기본) 는 미리보기만 */
    @Operation(
        summary = "용어 사전 엑셀 업로드",
        description = "multipart: file(xlsx, 5MB · 1,000행 이하) · dryRun(기본 true). 기존 용어에는 유사어만 더하고 뜻·고객사 정보는 바꾸지 않는다. " +
            "새 공식 용어는 통합관리자만(그 밖은 그 행 ERROR). dryRun=false 는 ERROR 행을 빼고 등록한다. 머리글이 템플릿과 다르면 400. " +
            "권한 sys-gloss 쓰기(미배정 아님)."
    )
    @PostMapping("/import", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun importTerms(
        @Parameter(description = "xlsx 파일") @RequestParam("file", required = false) file: MultipartFile?,
        @Parameter(description = "true 면 미리보기만(기본)") @RequestParam("dryRun", required = false) dryRun: Boolean?
    ): ApiResponse<Map<String, Any?>> {
        val dry = dryRun ?: true
        // 5MB 를 넘는 파일은 메모리에 읽기 전에 끊는다
        if (file != null && file.size > com.dwje.api.service.GlossaryImportWorkbook.MAX_BYTES) {
            throw InvalidParameterException("파일이 너무 큽니다. 최대 5MB 입니다.", "file")
        }
        val result = glossaryService.importTerms(file?.takeUnless { it.isEmpty }?.bytes, file?.originalFilename, dry)
        return ApiResponse.ok(result, if (dry) "미리보기입니다. 아직 등록하지 않았습니다." else "용어 사전을 등록했습니다.")
    }

    /** 위험 유사어 점검 목록 (07 GLS-03) — 통합관리자 */
    @Operation(summary = "위험 유사어 점검", description = "정규화에서 빠지거나 오치환을 일으킬 수 있는 유사어(한 글자·숫자·날짜·공식 용어와 같은 낱말·다른 용어에 포함). 통합관리자만.")
    @GetMapping("/variants/risks")
    fun variantRisks(): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(glossaryService.getRiskVariants())

    /** 공식 용어 등록 (No.172) */
    @Operation(summary = "공식 용어 등록", description = "공식 용어·정의·고객사 정보(customerInfo, 생략 시 false)를 등록한다. 통합관리자만.")
    @PostMapping("/terms")
    fun createTerm(@Valid @RequestBody request: GlossaryTermRequest): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(
            glossaryService.createTerm(request.term, request.definition, request.customerInfo),
            "용어가 등록되었습니다."
        )

    /** 공식 용어 수정 (No.173) */
    @Operation(summary = "공식 용어 수정", description = "공식 용어·정의·고객사 정보를 수정한다. customerInfo 를 생략하면 지금 값을 둔다. 통합관리자만.")
    @PutMapping("/terms/{termId}")
    fun updateTerm(
        @PathVariable termId: Int,
        @Valid @RequestBody request: GlossaryTermRequest
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(
            glossaryService.updateTerm(termId, request.term, request.definition, request.customerInfo),
            "용어가 수정되었습니다."
        )

    /** 공식 용어 삭제 */
    @Operation(
        summary = "공식 용어 삭제",
        description = "통합관리자만 삭제할 수 있다. 사용 중지로 처리하며, " +
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
