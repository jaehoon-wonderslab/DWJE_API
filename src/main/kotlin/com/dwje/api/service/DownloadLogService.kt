package com.dwje.api.service

import com.dwje.api.common.util.AuditType
import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.exception.SystemErrorException
import com.dwje.api.common.response.PageMeta
import com.dwje.api.common.util.ReportFormat
import com.dwje.api.model.request.DownloadLogRecordRequest
import com.dwje.api.common.security.UserContext
import com.dwje.api.common.util.DateUtils
import com.dwje.api.common.util.MenuId
import com.dwje.api.common.util.PageRequestParam
import com.dwje.api.config.AppProperties
import com.dwje.api.repository.DownloadLogRepository
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import com.dwje.api.common.util.ClientIpResolver
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/**
 * 보고서 다운로드 이력 서비스 (SY-14)
 *
 * 모든 파일 내려받기 API 는 본 서비스의 [record] 를 호출해 이력을 남긴다.
 * (공통 규약 6 — 출력 감사 대상 : 보고서 · 형식 · 행 수 · blind 건수 · IP)
 */
@Service
class DownloadLogService(
    private val downloadLogRepository: DownloadLogRepository,
    private val auditLogService: AuditLogService,
    private val authorizationService: AuthorizationService,
    private val appProperties: AppProperties,
    private val objectMapper: ObjectMapper,
    private val clientIpResolver: ClientIpResolver = ClientIpResolver()
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * **서버가 만든 파일**의 다운로드 이력을 기록한다. (서버 내려받기 API 공용)
     *
     * 출처 SERVER, 범위 ALL(각 화면 기획이 VIEW/ALL 을 정하기 전까지), 조건 요약 = [scope], 부서 ID 스냅샷을 함께 남긴다.
     * 본 업무(파일 생성)를 지키기 위해 기록 실패는 삼키고 0 을 돌려준다 — 기록 선행이 필요한 경로는 호출부가 0 을 보고 막는다.
     * 브라우저가 만든 파일의 신고는 [recordFromClient] 다(실패를 삼키지 않는다).
     * [params] 에 비밀번호·토큰·이메일 원문을 넣지 않는다.
     *
     * @param reportId   보고서 정의 ID (RPT_*). 비우면 [menuId] 의 보고서 정의로 채운다
     * @param reportNm   보고서/목록명
     * @param menuId     화면 ID
     * @param format     **실제로 만든 파일**의 형식 — [ReportFormat] 상수 (DLG-02)
     * @param scope      조회 범위 설명
     * @param rowCnt     출력 행 수
     * @param blindCnt   비공개로 채운 **셀** 수 — [blindCells] 합계와 같아야 한다 (DLG-15)
     * @param blindCells 데이터 항목별 마스킹 셀 수
     * @return 생성된 다운로드 이력 ID
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun record(
        reportId: String?,
        reportNm: String,
        menuId: String?,
        format: String,
        scope: String?,
        rowCnt: Int,
        blindCnt: Int,
        blindCells: Map<String, Int> = emptyMap(),
        fileNm: String? = null,
        params: Map<String, Any?>? = null,
        fileSize: Long? = null,
        scopeCd: String = "ALL",
        condSummary: String? = scope
    ): Long {
        return runCatching {
            val formatCd = ReportFormat.normalize(format)
                ?: format.uppercase().take(30).also { log.warn("다운로드 이력 — 알 수 없는 형식 코드 [{}]", format) }
            insertLog(
                originCd = "SERVER", reportId = reportId, reportNm = reportNm, menuId = menuId, formatCd = formatCd,
                scopeDesc = scope, scopeCd = scopeCd, condSummary = condSummary, rowCnt = rowCnt, blindCnt = blindCnt,
                blindCells = blindCells, fileNm = fileNm, params = params, fileSize = fileSize
            )
        }.onFailure { log.error("다운로드 이력 기록 실패 : report={} format={}", reportNm, format, it) }
            .getOrDefault(0L)
    }

    /**
     * **브라우저가 만든 파일**의 내려받기 신고 — POST /api/v1/download-logs (10 기획서 DLG-05·15)
     *
     * - 화면 ID 가 오면 등록된 화면이어야 하고(400) 그 화면의 **조회 권한**이 있어야 한다(403 E-AUTH-002).
     *   내려받기는 쓰기 권한 대상이 아니다(공통 9.8, R-10).
     * - 과거 클라이언트가 화면 ID 를 reportId 에 넣던 것은 menuId 로 옮긴다(DLG-03).
     * - `app.download-log.require-menu-id=false`(기본)인 동안은 화면 ID 없는 옛 신고도 기록한다.
     * - 기록에 실패하면 500 — 화면은 이 응답을 받고서야 파일을 저장한다(기록 선행, D-09).
     *
     * @return 생성된 다운로드 이력 ID
     */
    @Transactional
    fun recordFromClient(req: DownloadLogRecordRequest): Long {
        val principal = UserContext.current()
        val requireMenuId = appProperties.downloadLog.requireMenuId

        // 1. 화면 ID 확정 — 옛 클라이언트는 화면 ID 를 reportId 에 넣었다
        var menuId = req.menuId?.trim()?.takeIf { it.isNotEmpty() }
        var reportId = req.reportId?.trim()?.takeIf { it.isNotEmpty() }
        if (menuId == null && reportId != null) {
            if (downloadLogRepository.menuExists(reportId)) {
                menuId = reportId; reportId = null
            } else {
                menuId = downloadLogRepository.findMenuByReportId(reportId)
            }
        }
        if (menuId == null && requireMenuId) throw InvalidParameterException("화면 ID 가 올바르지 않습니다.", "menuId")

        // 2. 화면 존재 · 조회 권한 (requireWrite 아님)
        if (menuId != null) {
            if (!downloadLogRepository.menuExists(menuId)) throw InvalidParameterException("화면 ID 가 올바르지 않습니다.", "menuId")
            authorizationService.requireMenu(menuId)
        }

        // 3. 형식 · 범위 코드
        val formatCd = if (req.format.isNullOrBlank()) ReportFormat.XLS
            else ReportFormat.normalize(req.format)
                ?: throw InvalidParameterException("지원하지 않는 형식입니다. [${ReportFormat.ALL.joinToString(", ")}]", "format")
        val scopeCd = req.scopeCd?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        if (scopeCd != null && scopeCd !in setOf("VIEW", "ALL")) {
            throw InvalidParameterException("범위 코드는 VIEW 또는 ALL 이어야 합니다.", "scopeCd")
        }
        if (scopeCd == null && requireMenuId) throw InvalidParameterException("범위 코드는 VIEW 또는 ALL 이어야 합니다.", "scopeCd")

        // 4. 범위 문구 — 옛 클라이언트는 scope 에 조건을 적었다. 새 클라이언트는 condSummary 앞 100자를 scope_desc 에도 둔다
        val condSummary = req.condSummary?.takeIf { it.isNotBlank() } ?: req.scope?.takeIf { it.isNotBlank() }
        val scopeDesc = (req.scope?.takeIf { it.isNotBlank() } ?: condSummary)?.take(100)

        return try {
            insertLog(
                originCd = "CLIENT", reportId = reportId, reportNm = req.reportNm?.takeIf { it.isNotBlank() } ?: "보고서",
                menuId = menuId, formatCd = formatCd, scopeDesc = scopeDesc, scopeCd = scopeCd, condSummary = condSummary,
                rowCnt = req.rowCnt ?: 0, blindCnt = req.blindCnt ?: 0, blindCells = emptyMap(),
                fileNm = null, params = req.params, fileSize = req.fileSize, deptId = principal.deptId
            )
        } catch (e: Exception) {
            log.error("다운로드 이력 기록 실패(브라우저 신고) : menu={} format={}", menuId, formatCd, e)
            throw SystemErrorException("내려받기 기록에 실패했습니다.")
        }
    }

    /** 서버·브라우저 공통 기록 — 부서 ID 스냅샷, 보고서 정의 ID 보정, 셀 상세, 감사 로그 */
    private fun insertLog(
        originCd: String, reportId: String?, reportNm: String, menuId: String?, formatCd: String,
        scopeDesc: String?, scopeCd: String?, condSummary: String?, rowCnt: Int, blindCnt: Int,
        blindCells: Map<String, Int>, fileNm: String?, params: Map<String, Any?>?, fileSize: Long?,
        deptId: Int? = null
    ): Long {
        val principal = UserContext.current()
        // report_id 는 보고서 정의(RPT_*)만 둔다 — 화면은 menu_id 가 맡는다(DLG-03)
        val resolvedReportId = reportId ?: menuId?.let { downloadLogRepository.findReportIdByMenu(it) }
        val mismatch = blindCells.isNotEmpty() && blindCells.values.sum() != blindCnt
        if (mismatch) {
            log.warn("다운로드 이력 — 비공개 건수 불일치 : report={} blindCnt={} cells={}", reportNm, blindCnt, blindCells.values.sum())
        }
        val dlId = downloadLogRepository.insert(
            userId = principal.userId,
            deptNm = principal.deptName,
            reportId = resolvedReportId,
            menuId = menuId,
            targetNm = reportNm,
            formatCd = formatCd,
            scopeDesc = scopeDesc,
            rowCnt = rowCnt,
            blindCnt = blindCnt,
            ipAddr = currentIp(),
            fileNm = fileNm,
            // 조건 스냅샷은 비어 있으면 굳이 빈 객체를 남기지 않는다.
            paramsJson = params?.takeIf { it.isNotEmpty() }?.let { objectMapper.writeValueAsString(it) },
            fileSize = fileSize,
            originCd = originCd,
            deptId = deptId ?: principal.deptId,
            scopeCd = scopeCd,
            condSummary = condSummary
        )
        downloadLogRepository.insertBlindDetail(dlId, blindCells)

        // 출력 행위는 감사 로그에도 남긴다.
        auditLogService.record(
            logType = AuditType.EXPORT,
            menuId = menuId,
            targetDesc = reportNm,
            resultCd = if (blindCnt > 0) "BLIND" else "ALLOW",
            maskedCnt = blindCnt,
            remark = "다운로드 형식=$formatCd, 행수=$rowCnt, 출처=$originCd" + (scopeCd?.let { ", 범위=$it" } ?: "") +
                (if (mismatch) ", 비공개 건수 불일치(cells=${blindCells.values.sum()})" else "")
        )
        return dlId
    }

    /**
     * 다운로드 이력 요약 조회 (No.222)
     */
    @Transactional(readOnly = true)
    fun getSummary(from: String?, to: String?, filter: DownloadLogRepository.LogFilter = DownloadLogRepository.LogFilter()): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.SYS_DL)
        validateFilter(filter)
        val (fromDate, toDate) = boundedPeriod(from, to)

        // 카드 수는 목록과 같은 조건 — byReport·byUser 는 기간만 (10 DLG-06)
        val summary = downloadLogRepository.findSummary(fromDate, toDate, filter).toMutableMap()
        val byUser = downloadLogRepository.findCountByUser(fromDate, toDate, 10)

        summary["byReport"] = downloadLogRepository.findCountByReport(fromDate, toDate, 10)
        summary["byUser"] = byUser
        summary["topUser"] = byUser.firstOrNull()?.let { mapOf("name" to it["name"], "cnt" to it["cnt"]) }

        return summary
    }

    /**
     * 다운로드 이력 조회 (No.223)
     */
    @Transactional(readOnly = true)
    fun getLogs(
        from: String?,
        to: String?,
        filter: DownloadLogRepository.LogFilter,
        page: Int?,
        size: Int?,
        exporting: Boolean = false
    ): Pair<List<Map<String, Any?>>, PageMeta> {
        authorizationService.requireMenu(MenuId.SYS_DL)
        validateFilter(filter)
        // 전체 내려받기는 기간 상한·열람 기록 없이 읽는다 (10 DLG-08·11)
        val (fromDate, toDate) = if (exporting) DateUtils.periodOf(from, to) else boundedPeriod(from, to)
        val paging = PageRequestParam.of(page, size)

        val total = downloadLogRepository.countLogs(fromDate, toDate, filter)
        val rows = downloadLogRepository.findLogs(fromDate, toDate, filter, paging.limit, paging.offset)

        // 첫 쪽 조회 = 화면 진입 1회 — 감사 열람(AUDIT_VIEW) 1행 (10 DLG-11)
        if (!exporting && paging.page == 1) {
            auditLogService.recordAuditView("다운로드 이력 조회 [$fromDate~$toDate]", condSummaryOf(filter), MenuId.SYS_DL)
        }
        return rows to PageMeta.of(paging.page, paging.size, total)
    }

    /** 범위·생성 경로 코드 확인 */
    private fun validateFilter(filter: DownloadLogRepository.LogFilter) {
        val scopeCd = filter.scopeCd?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        if (scopeCd != null && scopeCd !in setOf("VIEW", "ALL", "UNKNOWN")) {
            throw InvalidParameterException("범위 코드는 VIEW · ALL · UNKNOWN 중 하나여야 합니다.", "scopeCd")
        }
        val origin = filter.origin?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        if (origin != null && origin !in setOf("CLIENT", "SERVER", "UNKNOWN")) {
            throw InvalidParameterException("생성 경로는 CLIENT · SERVER · UNKNOWN 중 하나여야 합니다.", "origin")
        }
    }

    /** 조회 기간 — 최대 366일 (10 DLG-09) */
    private fun boundedPeriod(from: String?, to: String?): Pair<java.time.LocalDate, java.time.LocalDate> {
        val (f, t) = DateUtils.periodOf(from, to)
        if (java.time.temporal.ChronoUnit.DAYS.between(f, t) + 1 > 366) {
            throw InvalidParameterException("조회 기간은 366일 이내여야 합니다.", "from")
        }
        return f to t
    }

    /** 열람 기록의 조건 요약 — 값이 있는 조건만 */
    private fun condSummaryOf(f: DownloadLogRepository.LogFilter): String =
        listOfNotNull(
            f.menuId?.takeIf { it.isNotBlank() }?.let { "화면 $it" },
            f.reportId?.takeIf { it.isNotBlank() }?.let { "보고서 $it" },
            f.dept?.takeIf { it.isNotBlank() }?.let { "부서 $it" },
            f.format?.takeIf { it.isNotBlank() }?.let { "형식 $it" },
            f.scopeCd?.takeIf { it.isNotBlank() }?.let { "범위 $it" },
            f.keyword?.takeIf { it.isNotBlank() }?.let { "검색어 $it" },
            f.empNo?.takeIf { it.isNotBlank() }?.let { "사번 $it" },
            f.origin?.takeIf { it.isNotBlank() }?.let { "생성 $it" },
            "비공개 포함만".takeIf { f.blindOnly }
        ).joinToString(" · ").take(500)

    /** 다운로드 이력 상세 — 목록 행 + 항목별 비공개 칸 (10 WEB 계약). 기간과 무관하게 ID 로 찾는다 */
    @Transactional(readOnly = true)
    fun getLog(dlId: Long): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.SYS_DL)
        val row = downloadLogRepository.findLogs(
            java.time.LocalDate.of(2000, 1, 1), java.time.LocalDate.now().plusDays(1),
            DownloadLogRepository.LogFilter(dlId = dlId), 1, 0
        ).firstOrNull() ?: throw com.dwje.api.common.exception.ResourceNotFoundException("다운로드 이력을 찾을 수 없습니다. [dlId=$dlId]")
        val fields = downloadLogRepository.findBlindFields(dlId)
        val cellSum = fields.sumOf { it["cellCnt"] as Int }
        auditLogService.recordAuditView("다운로드 이력 상세 [dlId=$dlId]", "", MenuId.SYS_DL)
        return row + mapOf(
            "blindFields" to fields,
            // 항목별 칸 합과 기록된 비공개 건수 비교 (10 DLG-10·15)
            "blindCellSum" to cellSum,
            "blindMismatch" to (fields.isNotEmpty() && cellSum != row["blindCnt"]),
            // 생성 경로가 없는 옛 서버 기록의 blind_cnt 는 항목 키 수였다 (10 8장 18번)
            "blindBasis" to if (row["origin"] == null) "LEGACY" else "CELL"
        )
    }

    /**
     * 보존 정책 조회 (No.225)
     */
    @Transactional(readOnly = true)
    fun getRetentionPolicy(): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.SYS_DL)

        val status = downloadLogRepository.findRetentionStatus(appProperties.downloadRetentionYears).toMutableMap()
        // 아카이브 배치(AuditArchiveJob)가 꺼져 있으면 다음 실행 시각은 없다 (10 DLG-07)
        status["enabled"] = appProperties.auditArchiveEnabled
        status["nextArchiveAt"] = if (!appProperties.auditArchiveEnabled) null else
            org.springframework.scheduling.support.CronExpression.parse(appProperties.auditArchiveCron)
                .next(java.time.ZonedDateTime.now(java.time.ZoneId.of(appProperties.auditArchiveZone)))
                ?.toLocalDateTime()?.format(DateUtils.DATETIME)
        return status
    }

    /** 현재 요청의 클라이언트 IP — 판정 규칙은 [ClientIpResolver] 한 곳에 있다(AUD-03) */
    private fun currentIp(): String? = clientIpResolver.currentRequestIp()
}
