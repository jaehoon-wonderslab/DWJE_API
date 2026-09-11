package com.dwje.api.service

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.exception.ResourceNotFoundException
import com.dwje.api.common.exception.SystemErrorException
import com.dwje.api.common.util.MenuId
import com.dwje.api.config.AppProperties
import com.dwje.api.repository.DashboardUploadRepository
import com.dwje.api.repository.UploadVersionRow
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * 업로드 리포트 서비스 — AI 통합 대시보드 「업로드 리포트」 탭
 *
 * ## 무엇을 하는가
 * 작업자가 정해진 포맷의 엑셀을 올리면 (1) 원본을 서버 디스크에 버전별로 저장하고
 * (2) [ExcelBlockParser] 로 정규화 JSON 을 만들어 DB 에 두며 (3) 화면은 그 JSON 으로 차트·표를 그린다.
 * MES 밖에서 **가공된 결과**를 회의 자료로 쓰기 위함이다(요구 6·8).
 *
 * ## 권한 (요구 7)
 * - 보기(목록·버전·데이터·원본) : `dash-ai` — AI 통합 대시보드를 볼 수 있으면 된다.
 * - 올리기(신규·새 버전) : `dash-ai-upload` — 메뉴 접근 권한 매트릭스에서 부서별로 준다.
 * - 시스템 관리 목록 : `sys-upload-doc`.
 *
 * ## 삭제는 없다 (요구 8 · B5-Q4)
 * 버전만 쌓인다. 잘못 올렸으면 새 버전을 올린다. 발주자가 삭제를 원하면 `del_flg` 만 세우는 API 를 추가한다.
 */
@Service
class DashboardUploadService(
    private val repository: DashboardUploadRepository,
    private val parser: ExcelBlockParser,
    private val authorizationService: AuthorizationService,
    private val appProperties: AppProperties,
    private val objectMapper: ObjectMapper
) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private val ALLOWED_EXT = setOf("xlsx", "xlsm")
        private const val TITLE_MAX = 200
        private const val MEMO_MAX = 1000
    }

    /** 새 문서 + 버전 1 */
    @Transactional
    fun create(file: MultipartFile?, title: String?, memo: String?): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.DASH_AI_UPLOAD)
        val cleanTitle = title?.trim()?.takeIf { it.isNotBlank() }
            ?: throw InvalidParameterException("문서 제목을 입력해 주세요.", "title")
        if (cleanTitle.length > TITLE_MAX) throw InvalidParameterException("제목은 ${TITLE_MAX}자 이내여야 합니다.", "title")
        val cleanMemo = memo?.trim()?.takeIf { it.isNotBlank() }
        if (cleanMemo != null && cleanMemo.length > MEMO_MAX) throw InvalidParameterException("메모는 ${MEMO_MAX}자 이내여야 합니다.", "memo")

        val upload = validateFile(file)
        val docId = repository.insertDoc(cleanTitle, cleanMemo, principal.userId)
        val ver = repository.nextVersion(docId, principal.userId)
            ?: throw SystemErrorException("문서 버전 번호를 확정하지 못했습니다.")

        return storeVersion(docId, ver, upload, principal.userId)
    }

    /** 기존 문서에 새 버전 */
    @Transactional
    fun addVersion(docId: Long, file: MultipartFile?): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.DASH_AI_UPLOAD)
        repository.findDoc(docId) ?: throw ResourceNotFoundException("업로드 문서를 찾을 수 없습니다. [docId=$docId]")

        val upload = validateFile(file)
        val ver = repository.nextVersion(docId, principal.userId)
            ?: throw ResourceNotFoundException("업로드 문서를 찾을 수 없습니다. [docId=$docId]")

        return storeVersion(docId, ver, upload, principal.userId)
    }

    /**
     * 문서 목록 (대시보드용)
     *
     * @param uploadedBy 업로더 — 사번(정확 일치) 또는 이름(부분 일치). 어느 버전이든 그 사람이 올린 문서
     * @param keyword    제목·메모·파일명 부분 일치
     */
    fun listDocs(uploadedBy: String? = null, keyword: String? = null): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.DASH_AI)
        return mapOf("items" to repository.findDocs(uploadedBy, keyword))
    }

    /** 문서 목록 (시스템 관리용 — 같은 형태·필터, 권한만 다르다) */
    fun listDocsForAdmin(uploadedBy: String? = null, keyword: String? = null): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.SYS_UPLOAD_DOC)
        return mapOf("items" to repository.findDocs(uploadedBy, keyword))
    }

    /** 버전 이력 */
    fun listVersions(docId: Long): Map<String, Any?> {
        authorizationService.requireAnyMenu(MenuId.DASH_AI, MenuId.SYS_UPLOAD_DOC)
        val doc = repository.findDoc(docId) ?: throw ResourceNotFoundException("업로드 문서를 찾을 수 없습니다. [docId=$docId]")
        return mapOf(
            "docId" to docId,
            "title" to doc["title"],
            "latestVersion" to doc["latestVersion"],
            "items" to repository.findVersions(docId)
        )
    }

    /** 파싱 결과 — 화면은 이것만으로 차트·표를 그린다. */
    fun getData(docId: Long, version: Int): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.DASH_AI)
        val doc = repository.findDoc(docId) ?: throw ResourceNotFoundException("업로드 문서를 찾을 수 없습니다. [docId=$docId]")
        val row = repository.findVersion(docId, version)
            ?: throw ResourceNotFoundException("해당 버전이 없습니다. [docId=$docId, version=$version]")
        return toDataResponse(docId, doc["title"] as String?, row)
    }

    /** 원본 파일 — 다운로드용. (경로, 파일명, 크기) */
    fun openFile(docId: Long, version: Int): Triple<Path, String, Long> {
        authorizationService.requireMenu(MenuId.DASH_AI)
        repository.findDoc(docId) ?: throw ResourceNotFoundException("업로드 문서를 찾을 수 없습니다. [docId=$docId]")
        val row = repository.findVersion(docId, version)
            ?: throw ResourceNotFoundException("해당 버전이 없습니다. [docId=$docId, version=$version]")

        val path = resolveInsideRoot(row.storagePath)
        if (!Files.isRegularFile(path)) {
            log.error("업로드 원본이 디스크에 없습니다. docId={} ver={} path={}", docId, version, path)
            throw ResourceNotFoundException("원본 파일이 저장소에 없습니다. 관리자에게 문의하세요.")
        }
        return Triple(path, row.fileName, row.sizeBytes)
    }

    // ---------------------------------------------------------------------------------

    private class Validated(val file: MultipartFile, val fileName: String)

    private fun validateFile(file: MultipartFile?): Validated {
        if (file == null || file.isEmpty) throw InvalidParameterException("업로드할 엑셀 파일이 없습니다.", "file")
        val max = appProperties.upload.maxBytes
        if (file.size > max) {
            throw InvalidParameterException("파일이 너무 큽니다. 최대 ${max / 1024 / 1024}MB. [${file.size / 1024 / 1024}MB]", "file")
        }
        val original = Paths.get(file.originalFilename ?: "upload.xlsx").fileName.toString()
        val ext = original.substringAfterLast('.', "").lowercase()
        if (ext !in ALLOWED_EXT) {
            throw InvalidParameterException("xlsx 파일만 올릴 수 있습니다. [$original]", "file")
        }
        return Validated(file, original)
    }

    /**
     * 파일을 저장하고 파싱해 버전 행을 만든다.
     *
     * 파싱은 저장 **전에** 한다 — 엑셀로 열리지 않는 파일은 400 으로 돌려보내고 디스크에도 남기지 않는다.
     * 규칙에 안 맞는 부분은 400 이 아니라 `warnings` 로 알린다(FAIL 도 저장한다 — 무엇을 올렸는지 남아야 한다).
     */
    private fun storeVersion(docId: Long, ver: Int, upload: Validated, actor: String): Map<String, Any?> {
        val bytes = upload.file.bytes
        val parsed = try {
            parser.parse(bytes.inputStream())
        } catch (e: IllegalArgumentException) {
            throw InvalidParameterException(e.message ?: "엑셀 파일을 읽을 수 없습니다.", "file")
        }

        val dir = root().resolve(docId.toString()).resolve(ver.toString())
        Files.createDirectories(dir)
        val target = dir.resolve(upload.fileName)
        Files.copy(bytes.inputStream(), target, StandardCopyOption.REPLACE_EXISTING)

        val relPath = root().relativize(target).toString().replace('\\', '/')
        val parseJson = objectMapper.writeValueAsString(mapOf("sheets" to parsed.sheets))
        val warningJson = objectMapper.writeValueAsString(parsed.warnings)

        repository.insertVersion(
            docId, ver, upload.fileName, relPath, bytes.size.toLong(), sha256(bytes),
            parsed.state, parseJson, warningJson, actor
        )
        log.info("업로드 리포트 저장 docId={} ver={} file={} size={} state={} warnings={}",
            docId, ver, upload.fileName, bytes.size, parsed.state, parsed.warnings.size)

        val row = repository.findVersion(docId, ver)
            ?: throw SystemErrorException("저장한 버전을 다시 읽지 못했습니다.")
        val title = repository.findDoc(docId)?.get("title") as String?
        return toDataResponse(docId, title, row)
    }

    private fun toDataResponse(docId: Long, title: String?, row: UploadVersionRow): Map<String, Any?> {
        val sheets = row.parseJson?.let { objectMapper.readTree(it).get("sheets") }
        val warnings = row.warningJson?.let { objectMapper.readTree(it) }
        return mapOf(
            "docId" to docId,
            "title" to title,
            "version" to row.version,
            "fileName" to row.fileName,
            "sizeBytes" to row.sizeBytes,
            "sha256" to row.sha256,
            "uploadedBy" to row.uploadedBy,
            "uploadedByName" to row.uploadedByName,
            "uploadedAt" to row.uploadedAt,
            "parseState" to row.parseState,
            "parsed" to mapOf(
                "sheets" to (sheets ?: emptyList<Any>()),
                "warnings" to (warnings ?: emptyList<Any>())
            )
        )
    }

    private fun root(): Path = Paths.get(appProperties.upload.dir).toAbsolutePath().normalize()

    /** 저장 경로가 루트 아래인지 확인한다 — DB 값이 손상돼도 루트 밖 파일은 내려주지 않는다. */
    private fun resolveInsideRoot(relative: String): Path {
        val path = root().resolve(relative).normalize()
        if (!path.startsWith(root())) {
            throw SystemErrorException("저장 경로가 저장소 밖을 가리킵니다.")
        }
        return path
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
