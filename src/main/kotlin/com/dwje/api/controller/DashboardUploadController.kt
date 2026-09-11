package com.dwje.api.controller

import com.dwje.api.common.response.ApiResponse
import com.dwje.api.service.DashboardUploadService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.core.io.FileSystemResource
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
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
    private val uploadService: DashboardUploadService
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
    @Operation(summary = "엑셀 업로드(새 버전)", description = "multipart: file. 같은 문서에 버전을 하나 올린다. 권한 dash-ai-upload.")
    @PostMapping("/{docId}/versions", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadVersion(
        @PathVariable docId: Long,
        @RequestParam("file", required = false) file: MultipartFile?
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(uploadService.addVersion(docId, file), "새 버전을 업로드했습니다.")

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
        val disposition = ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build()
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .contentLength(size)
            .body(FileSystemResource(path))
    }
}

/**
 * 시스템관리 › 업로드 문서 목록 (요구 8 — 목록만, 편집 없음)
 */
@RestController
@RequestMapping("/api/v1/system/uploads")
@Tag(name = "12. 시스템관리 - 운영")
class UploadDocAdminController(
    private val uploadService: DashboardUploadService
) {

    @Operation(
        summary = "업로드 문서 목록(시스템관리)",
        description = "전체 업로드 문서와 최신 버전 정보. 편집·삭제 없음. 권한 sys-upload-doc. " +
            "uploadedBy 는 사번(정확 일치) 또는 이름(부분 일치) 둘 다 받는다, keyword 는 제목·메모·파일명 부분 일치."
    )
    @GetMapping
    fun docs(
        @Parameter(description = "업로더 — 사번 또는 이름") @RequestParam(required = false) uploadedBy: String?,
        @Parameter(description = "제목·메모·파일명 검색어") @RequestParam(required = false) keyword: String?
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(uploadService.listDocsForAdmin(uploadedBy, keyword))

    @Operation(summary = "업로드 문서 버전 이력(시스템관리)", description = "행 펼침용 버전 이력. 권한 sys-upload-doc 또는 dash-ai.")
    @GetMapping("/{docId}/versions")
    fun versions(@PathVariable docId: Long): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(uploadService.listVersions(docId))
}
