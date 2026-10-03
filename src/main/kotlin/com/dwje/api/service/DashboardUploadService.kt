package com.dwje.api.service

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.exception.ResourceNotFoundException
import com.dwje.api.common.exception.SystemErrorException
import com.dwje.api.common.response.PageMeta
import com.dwje.api.common.util.DateUtils
import com.dwje.api.common.util.MenuId
import com.dwje.api.common.util.PageRequestParam
import com.dwje.api.common.validation.CodeValidator
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
    private val objectMapper: ObjectMapper,
    private val codeValidator: CodeValidator
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 감사 기록기 — 서비스를 직접 만드는 단위 시험에서는 없다 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    var auditLogService: AuditLogService? = null

    companion object {
        /** 매크로 통합문서(xlsm)는 받지 않는다 — 회의 자료에 실행 코드가 섞이지 않게 (UPD-03) */
        private val ALLOWED_EXT = setOf("xlsx")
        private const val TITLE_MAX = 200
        private const val MEMO_MAX = 1000
        /** 시스템관리 목록 전량 조회(size=0) 상한 (공통 D-29) */
        const val ADMIN_ALL_MAX = 10_000
    }

    /** 새 문서 + 버전 1 */
    @Transactional
    fun create(file: MultipartFile?, title: String?, memo: String?): Map<String, Any?> {
        val principal = authorizationService.requireWrite(MenuId.DASH_AI_UPLOAD)
        val cleanTitle = title?.trim()?.takeIf { it.isNotBlank() }
            ?: throw InvalidParameterException("문서 제목을 입력해 주세요.", "title")
        if (cleanTitle.length > TITLE_MAX) throw InvalidParameterException("제목은 ${TITLE_MAX}자 이내여야 합니다.", "title")
        val cleanMemo = cleanMemo(memo)

        val upload = validateFile(file)
        val docId = repository.insertDoc(cleanTitle, cleanMemo, principal.userId)
        val ver = repository.nextVersion(docId, principal.userId)
            ?: throw SystemErrorException("문서 버전 번호를 확정하지 못했습니다.")

        // 등록 메모는 문서 헤더와 버전 1 에 함께 둔다 — 버전 이력에서도 첫 버전의 사유가 보이게 (UPD-02)
        return storeVersion(docId, ver, upload, principal.userId, cleanMemo)
    }

    /**
     * 기존 문서에 새 버전
     *
     * @param memo 이 버전의 변경 내용 (선택, 1000자, UPD-02)
     */
    @Transactional
    fun addVersion(docId: Long, file: MultipartFile?, memo: String? = null): Map<String, Any?> {
        val principal = authorizationService.requireWrite(MenuId.DASH_AI_UPLOAD)
        repository.findDoc(docId) ?: throw ResourceNotFoundException("업로드 문서를 찾을 수 없습니다. [docId=$docId]")
        val cleanMemo = cleanMemo(memo)

        val upload = validateFile(file)
        val ver = repository.nextVersion(docId, principal.userId)
            ?: throw ResourceNotFoundException("업로드 문서를 찾을 수 없습니다. [docId=$docId]")

        return storeVersion(docId, ver, upload, principal.userId, cleanMemo)
    }

    /** 메모 정리 — 앞뒤 공백 제거, 빈 값은 없음, 1000자 초과는 400 */
    private fun cleanMemo(memo: String?): String? {
        val clean = memo?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (clean.length > MEMO_MAX) throw InvalidParameterException("메모는 ${MEMO_MAX}자 이내여야 합니다.", "memo")
        return clean
    }

    /**
     * 문서 목록 (대시보드용)
     *
     * @param uploadedBy 업로더 — 사번(정확 일치) 또는 이름(부분 일치). 어느 버전이든 그 사람이 올린 문서
     * @param keyword    제목·메모·파일명 부분 일치
     */
    fun listDocs(uploadedBy: String? = null, keyword: String? = null): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.DASH_AI)
        return mapOf("items" to repository.findDocs(uploadedBy, keyword).map { it - "storagePath" })
    }

    /**
     * 문서 목록 (시스템 관리용 — 같은 형태, 권한과 서버 쪽 조건·쪽 나눔이 다르다, 11 UPD-01)
     *
     * @param parseState 최신 버전 파싱 상태 — OK · WARN · FAIL
     * @param from       최신 버전 업로드일(한국 날짜) 시작. 비우면 처음부터
     * @param to         최신 버전 업로드일 끝(그날 포함). 비우면 지금까지
     * @param size       쪽 크기(1~1000). `0` 이면 전체 — [ADMIN_ALL_MAX] 건에서 자르고 `meta.truncated=true`
     */
    @Transactional(readOnly = true)
    fun listDocsForAdmin(
        uploadedBy: String? = null,
        keyword: String? = null,
        parseState: String? = null,
        from: String? = null,
        to: String? = null,
        page: Int? = null,
        size: Int? = null,
        includeDeleted: Boolean = false
    ): Pair<List<Map<String, Any?>>, PageMeta> {
        authorizationService.requireMenu(MenuId.SYS_UPLOAD_DOC)
        val state = parseState?.trim()?.takeIf { it.isNotEmpty() }
        codeValidator.require("DASH_UPLOAD_PARSE", state, "parseState", "파싱 상태")
        val fromDate = from?.takeIf { it.isNotBlank() }?.let { DateUtils.parseDate(it, "from") }
        val toDate = to?.takeIf { it.isNotBlank() }?.let { DateUtils.parseDate(it, "to") }
        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            throw InvalidParameterException("조회 시작일이 종료일보다 늦습니다. [from=$fromDate, to=$toDate]", "from")
        }
        // 숨긴 문서 포함은 시스템관리 목록만 (R-19)
        val filter = DashboardUploadRepository.DocFilter(state, fromDate, toDate, includeDeleted)
        val paging = PageRequestParam.ofAllowAll(page, size)

        val total = repository.countDocs(uploadedBy, keyword, filter)
        if (paging.isAll) {
            val rows = repository.findDocs(uploadedBy, keyword, filter, ADMIN_ALL_MAX, 0).map(::withFileState)
            return rows to PageMeta(1, rows.size, total, truncated = if (total > ADMIN_ALL_MAX) true else null)
        }
        val rows = repository.findDocs(uploadedBy, keyword, filter, paging.limit, paging.offset).map(::withFileState)
        return rows to PageMeta.of(paging.page, paging.size, total)
    }

    /** 문서 행 — 최신 버전 원본 보관 상태를 붙이고 내부 경로는 뺀다 (11 UPD-08) */
    private fun withFileState(row: Map<String, Any?>): Map<String, Any?> =
        (row - "storagePath") + ("fileState" to fileStateOf(row))

    /** 시스템관리 목록의 요약 카드·업로더 선택지 (11 WEB 계약) — 조건 해석은 [listDocsForAdmin] 과 같다 */
    fun adminListExtras(uploadedBy: String?, keyword: String?, from: String?, to: String?): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.SYS_UPLOAD_DOC)
        val fromDate = from?.takeIf { it.isNotBlank() }?.let { DateUtils.parseDate(it, "from") }
        val toDate = to?.takeIf { it.isNotBlank() }?.let { DateUtils.parseDate(it, "to") }
        val filter = DashboardUploadRepository.DocFilter(null, fromDate, toDate)
        // 저장소 현황은 조건 무관 전체(11 UPD-07 · 4.4), 상태별 문서 수(total·ok·warn·fail)는 2단계 계약대로 같은 조건 기준
        val summary = repository.findStorageSummary() + mapOf("maxBytesPerFile" to appProperties.upload.maxBytes) +
            repository.findDocSummary(uploadedBy, keyword, filter)
        return mapOf(
            "summary" to summary,
            "uploaders" to repository.findUploaders()
        )
    }

    /** 버전 이력 */
    fun listVersions(docId: Long): Map<String, Any?> {
        val principal = authorizationService.requireAnyMenu(MenuId.DASH_AI, MenuId.SYS_UPLOAD_DOC)
        // 숨긴 문서의 버전 이력은 시스템관리 화면 권한자만 본다(복원 전 확인용, R-19). 대시보드에는 없는 문서다
        val doc = repository.findDoc(docId)
            ?: repository.findDocAny(docId)?.takeIf { principal.canAccessMenu(MenuId.SYS_UPLOAD_DOC) }
            ?: throw ResourceNotFoundException("업로드 문서를 찾을 수 없습니다. [docId=$docId]")
        return mapOf(
            "docId" to docId,
            "title" to doc["title"],
            "latestVersion" to doc["latestVersion"],
            "items" to repository.findVersions(docId).map { v -> (v - "storagePath") + ("fileState" to fileStateOf(v)) }
        )
    }

    /**
     * 원본 파일 보관 상태 (11 UPD-08) — OK · MISSING(없음 또는 저장 루트 밖) · SIZE_MISMATCH(크기가 기록과 다름). 해시는 다시 계산하지 않는다.
     */
    private fun fileStateOf(v: Map<String, Any?>): String {
        val rel = v["storagePath"] as String? ?: return "MISSING"
        val path = runCatching { resolveInsideRoot(rel) }.getOrNull() ?: return "MISSING"
        if (!Files.isRegularFile(path)) return "MISSING"
        return if (runCatching { Files.size(path) }.getOrNull() == v["sizeBytes"]) "OK" else "SIZE_MISMATCH"
    }

    /**
     * 업로드 문서 숨김 (R-19, 공통 11.3) — 소프트 삭제. 대시보드·AI 패널·기본 목록에서 빠지고 원본 파일은 그대로 둔다.
     * 사유 필수(200자). 이미 숨긴 문서는 409.
     */
    @Transactional
    fun hideDoc(docId: Long, reason: String?): Map<String, Any?> {
        val principal = authorizationService.requireWrite(MenuId.SYS_UPLOAD_DOC)
        val why = reason?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw InvalidParameterException("숨기는 사유를 입력해 주세요.", "reason")
        if (why.length > 200) throw InvalidParameterException("숨기는 사유는 200자 이내여야 합니다.", "reason")
        val doc = repository.findDocAny(docId) ?: throw ResourceNotFoundException("업로드 문서를 찾을 수 없습니다. [docId=$docId]")
        if (repository.hideDoc(docId, principal.userId, why) == 0) {
            throw com.dwje.api.common.exception.BusinessRuleException("이미 숨긴 문서입니다. [docId=$docId]")
        }
        auditLogService?.recordAfterCommit(
            com.dwje.api.common.util.AuditType.CONFIG_CHANGE, MenuId.SYS_UPLOAD_DOC,
            "업로드 문서 숨김 [docId=$docId] ${doc["title"]}".take(300), why
        )
        log.info("업로드 문서 숨김 docId={} by={}", docId, principal.userId)
        return mapOf("docId" to docId, "deleted" to true)
    }

    /** 업로드 문서 복원 (R-19) — 숨기지 않은 문서는 409 */
    @Transactional
    fun restoreDoc(docId: Long): Map<String, Any?> {
        val principal = authorizationService.requireWrite(MenuId.SYS_UPLOAD_DOC)
        val doc = repository.findDocAny(docId) ?: throw ResourceNotFoundException("업로드 문서를 찾을 수 없습니다. [docId=$docId]")
        if (repository.restoreDoc(docId, principal.userId) == 0) {
            throw com.dwje.api.common.exception.BusinessRuleException("숨기지 않은 문서입니다. [docId=$docId]")
        }
        auditLogService?.recordAfterCommit(
            com.dwje.api.common.util.AuditType.CONFIG_CHANGE, MenuId.SYS_UPLOAD_DOC,
            "업로드 문서 복원 [docId=$docId] ${doc["title"]}".take(300), null
        )
        log.info("업로드 문서 복원 docId={} by={}", docId, principal.userId)
        return mapOf("docId" to docId, "deleted" to false)
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
            // 내려받기 시도는 남긴다 — 내려받기 기록과 같은 유형(EXPORT), 결과 REJECT (11 UPD-04)
            auditLogService?.record(
                logType = com.dwje.api.common.util.AuditType.EXPORT, menuId = MenuId.DASH_AI,
                targetDesc = "업로드 원본 [docId=$docId, v$version] ${row.fileName}".take(300),
                resultCd = com.dwje.api.common.util.AuditResult.REJECT, remark = "원본 파일 없음"
            )
            throw ResourceNotFoundException("원본 파일이 저장소에 없습니다. 관리자에게 문의하세요.")
        }
        return Triple(path, row.fileName, row.sizeBytes)
    }

    // ---------------------------------------------------------------------------------

    private class Validated(val bytes: ByteArray, val fileName: String)

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
        val bytes = file.bytes
        XlsxUploadGuard.assertPlainXlsx(bytes, original)
        return Validated(bytes, original)
    }

    /**
     * 파일을 저장하고 파싱해 버전 행을 만든다.
     *
     * 파싱은 저장 **전에** 한다 — 엑셀로 열리지 않는 파일은 400 으로 돌려보내고 디스크에도 남기지 않는다.
     * 규칙에 안 맞는 부분은 400 이 아니라 `warnings` 로 알린다(FAIL 도 저장한다 — 무엇을 올렸는지 남아야 한다).
     */
    private fun storeVersion(docId: Long, ver: Int, upload: Validated, actor: String, memo: String?): Map<String, Any?> {
        val bytes = upload.bytes
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
        val hash = sha256(bytes)
        val parseJson = objectMapper.writeValueAsString(mapOf("sheets" to parsed.sheets))
        val warningJson = objectMapper.writeValueAsString(parsed.warnings)

        repository.insertVersion(
            docId, ver, upload.fileName, relPath, bytes.size.toLong(), hash,
            parsed.state, parseJson, warningJson, actor, memo
        )
        log.info("업로드 리포트 저장 docId={} ver={} file={} size={} state={} warnings={}",
            docId, ver, upload.fileName, bytes.size, parsed.state, parsed.warnings.size)

        val row = repository.findVersion(docId, ver)
            ?: throw SystemErrorException("저장한 버전을 다시 읽지 못했습니다.")
        val title = repository.findDoc(docId)?.get("title") as String?
        // 업로드·새 버전 등록은 운영 설정 변경으로 남긴다 — 커밋된 뒤에만 (11 UPD-05, 09 AUD-10)
        auditLogService?.recordAfterCommit(
            logType = com.dwje.api.common.util.AuditType.CONFIG_CHANGE, menuId = MenuId.DASH_AI_UPLOAD,
            targetDesc = (if (ver == 1) "업로드 문서 등록 [docId=$docId, v1] ${title.orEmpty()}" else "새 버전 [docId=$docId, v$ver] ${title.orEmpty()}").trimEnd(),
            remark = "file=${upload.fileName}, size=${bytes.size}, parse=${parsed.state}"
        )
        // 같은 파일을 다시 올렸으면 알린다 — 거부하지 않는다(11 Q7 결정 대기, UPD-10). 새 문서(v1)는 늘 null
        val dup = if (ver > 1) repository.findDuplicateOf(docId, ver, hash) else null
        return toDataResponse(docId, title, row) + ("duplicateOf" to dup)
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
            "memo" to row.memo,
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
