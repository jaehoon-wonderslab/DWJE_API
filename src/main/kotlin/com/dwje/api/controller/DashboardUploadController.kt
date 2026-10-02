package com.dwje.api.controller

import com.dwje.api.common.response.ApiResponse
import com.dwje.api.common.util.MenuId
import com.dwje.api.common.util.ReportFormat
import com.dwje.api.service.DashboardUploadService
import com.dwje.api.service.DownloadLogService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.core.io.FileSystemResource
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.nio.charset.StandardCharsets

/**
 * 업로드 리포트 컨트롤러 — AI 통합 대시보드 「업로드 리포트」 탭 (요구 6·7·8)
 *
 * 업로드는 multipart 다(`file` + `title` + `memo`). 본문이 JSON 이 아니라
 * `REQUEST_BODY_CONTRACT` 의 타입 DTO 계약 대상이 아니며, 모르는 파트는 무시된다.
 */
@RestController
@RequestMapping("/api/v1/dashboard/uploads")
@Tag(name = "03. 대시보드")
class DashboardUploadController(
    private val uploadService: DashboardUploadService,
    private val downloadLogService: DownloadLogService
) {

    /** 문서 목록 */
    @Operation(
        summary = "업로드 문서 목록",
        description = "AI 통합 대시보드 업로드 리포트 문서 목록. 최신 버전 기준 갱신자·일시·크기·파싱 상태를 포함한다. 권한 dash-ai. " +
            "uploadedBy 는 사번(정확 일치) 또는 이름(부분 일치), keyword 는 제목·메모·파일명 부분 일치."
    )
    @GetMapping
    fun docs(
        @Parameter(description = "업로더 — 사번 또는 이름") @RequestParam(required = false) uploadedBy: String?,
        @Parameter(description = "제목·메모·파일명 검색어") @RequestParam(required = false) keyword: String?
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(uploadService.listDocs(uploadedBy, keyword))

    /** 새 문서 업로드 (버전 1) */
    @Operation(
        summary = "엑셀 업로드(새 문서)",
        description = "multipart: file(xlsx, 20MB 이하) · title(필수) · memo. 원본을 저장하고 파싱해 블록 JSON 을 돌려준다. " +
            "규칙에 안 맞는 부분은 parsed.warnings 로 알린다. 권한 dash-ai-upload."
    )
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(
        @Parameter(description = "xlsx 파일") @RequestParam("file", required = false) file: MultipartFile?,
        @RequestParam("title", required = false) title: String?,
        @RequestParam("memo", required = false) memo: String?
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(uploadService.create(file, title, memo), "문서를 업로드했습니다.")

    /** 기존 문서에 새 버전 */
    @Operation(summary = "엑셀 업로드(새 버전)", description = "multipart: file · memo(선택, 1000자 — 이 버전의 변경 내용). 같은 문서에 버전을 하나 올린다. 권한 dash-ai-upload.")
    @PostMapping("/{docId}/versions", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadVersion(
        @PathVariable docId: Long,
        @RequestParam("file", required = false) file: MultipartFile?,
        @RequestParam("memo", required = false) memo: String?
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(uploadService.addVersion(docId, file, memo), "새 버전을 업로드했습니다.")

    /** 버전 이력 */
    @Operation(summary = "업로드 문서 버전 이력", description = "최신 버전이 먼저. 파일명·크기·SHA-256·업로더·파싱 상태·경고 수.")
    @GetMapping("/{docId}/versions")
    fun versions(@PathVariable docId: Long): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(uploadService.listVersions(docId))

    /** 파싱 결과 */
    @Operation(
        summary = "업로드 문서 데이터",
        description = "버전의 정규화 결과. parsed.sheets[] = { title, chartType(line|bar|grouped|donut|table), x, series[], columns[], rows[] }. 화면은 이것만으로 차트·표를 그린다."
    )
    @GetMapping("/{docId}/versions/{version}/data")
    fun data(@PathVariable docId: Long, @PathVariable version: Int): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(uploadService.getData(docId, version))

    /** 원본 다운로드 */
    @Operation(summary = "업로드 원본 내려받기", description = "해당 버전의 원본 xlsx 를 첨부파일로 내려준다.")
    @GetMapping("/{docId}/versions/{version}/file")
    fun file(@PathVariable docId: Long, @PathVariable version: Int): ResponseEntity<FileSystemResource> {
        val (path, fileName, size) = uploadService.openFile(docId, version)
        // 원본 내려받기도 다운로드 이력에 남긴다 (10 DLG-04). 서버 기록이라 실패해도 내려받기는 막지 않는다.
        downloadLogService.record(
            reportId = null,
            reportNm = "업로드 원본 [docId=$docId, v$version] $fileName".take(200),
            menuId = MenuId.DASH_AI,
            format = ReportFormat.XLSX,
            scope = "docId=$docId, version=$version",
            rowCnt = 0,
            blindCnt = 0,
            fileNm = fileName,
            params = mapOf("docId" to docId, "version" to version),
            fileSize = size
        )
        val disposition = ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build()
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .contentLength(size)
            .body(FileSystemResource(path))
    }
}

/**
 * 시스템관리 › 업로드 문서 목록 (요구 8) — 목록·버전 이력·숨김·복원(R-19)
 */
@RestController
@RequestMapping("/api/v1/system/uploads")
@Tag(name = "12. 시스템관리 - 운영")
class UploadDocAdminController(
    private val uploadService: DashboardUploadService
) {

    @Operation(
        summary = "업로드 문서 목록(시스템관리)",
        description = "전체 업로드 문서와 최신 버전 정보. 숨김·복원은 아래 API(R-19). 권한 sys-upload-doc. includeDeleted=true 면 숨긴 문서도(행 deleted·deletedAt·deletedByName·deleteReason). " +
            "uploadedBy 는 사번(정확 일치) 또는 이름(부분 일치) 둘 다 받는다, keyword 는 제목·메모·파일명 부분 일치. " +
            "parseState(OK|WARN|FAIL)·from/to(최신 버전 업로드일, 한국 날짜, 비우면 전 기간)·page/size(기본 1/50, size=0 은 전체 10,000건 상한 — 넘으면 meta.truncated=true)."
    )
    @GetMapping
    fun docs(
        @Parameter(description = "업로더 — 사번 또는 이름") @RequestParam(required = false) uploadedBy: String?,
        @Parameter(description = "제목·메모·파일명 검색어") @RequestParam(required = false) keyword: String?,
        @Parameter(description = "최신 버전 파싱 상태 — OK|WARN|FAIL") @RequestParam(required = false) parseState: String?,
        @Parameter(description = "최신 버전 업로드일 시작 yyyy-MM-dd") @RequestParam(required = false) from: String?,
        @Parameter(description = "최신 버전 업로드일 끝 yyyy-MM-dd") @RequestParam(required = false) to: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
        @Parameter(description = "숨긴 문서도 포함 (기본 false)") @RequestParam(required = false) includeDeleted: Boolean?
    ): ApiResponse<Map<String, Any?>> {
        val (rows, meta) = uploadService.listDocsForAdmin(uploadedBy, keyword, parseState, from, to, page, size, includeDeleted ?: false)
        // summary(파싱 상태별 문서 수 — 상태 조건 제외) · uploaders(업로더 선택지)
        return ApiResponse.page(mapOf("items" to rows) + uploadService.adminListExtras(uploadedBy, keyword, from, to), meta)
    }

    @Operation(
        summary = "업로드 문서 숨김",
        description = "소프트 삭제 — 대시보드 업로드 리포트·AI 패널·기본 목록에서 빠지고 원본 파일은 그대로 둔다. 본문 {reason}(필수, 200자). 이미 숨긴 문서는 409. 권한 sys-upload-doc 쓰기."
    )
    @DeleteMapping("/{docId}")
    fun hide(
        @PathVariable docId: Long,
        @jakarta.validation.Valid @RequestBody(required = false) request: com.dwje.api.model.request.UploadDocHideRequest?
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(uploadService.hideDoc(docId, request?.reason), "문서를 숨겼습니다.")

    @Operation(summary = "업로드 문서 복원", description = "숨긴 문서를 다시 보이게 한다. 숨기지 않은 문서는 409. 권한 sys-upload-doc 쓰기.")
    @PostMapping("/{docId}/restore")
    fun restore(@PathVariable docId: Long): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(uploadService.restoreDoc(docId), "문서를 복원했습니다.")

    @Operation(summary = "업로드 문서 버전 이력(시스템관리)", description = "행 펼침용 버전 이력. 권한 sys-upload-doc 또는 dash-ai.")
    @GetMapping("/{docId}/versions")
    fun versions(@PathVariable docId: Long): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(uploadService.listVersions(docId))
}
