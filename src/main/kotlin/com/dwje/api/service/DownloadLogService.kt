package com.dwje.api.service

import com.dwje.api.common.response.PageMeta
import com.dwje.api.common.security.UserContext
import com.dwje.api.common.util.DateUtils
import com.dwje.api.common.util.MenuId
import com.dwje.api.common.util.PageRequestParam
import com.dwje.api.config.AppProperties
import com.dwje.api.repository.DownloadLogRepository
import jakarta.servlet.http.HttpServletRequest
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
    private val appProperties: AppProperties
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 다운로드 이력을 기록한다. (No.224 및 전 내려받기 API 공용)
     *
     * @param reportId   보고서 ID
     * @param reportNm   보고서/목록명
     * @param menuId     화면 ID
     * @param format     다운로드 형식 (xls/csv/pdf)
     * @param scope      조회 범위 설명
     * @param rowCnt     출력 행 수
     * @param blindCnt   마스킹 처리 건수
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
        fileNm: String? = null
    ): Long {
        return runCatching {
            val principal = UserContext.current()
            val formatCd = normalizeFormat(format)

            val dlId = downloadLogRepository.insert(
                userId = principal.userId,
                deptNm = principal.deptName,
                reportId = reportId,
                menuId = menuId,
                targetNm = reportNm,
                formatCd = formatCd,
                scopeDesc = scope,
                rowCnt = rowCnt,
                blindCnt = blindCnt,
                ipAddr = currentIp(),
                fileNm = fileNm
            )
            downloadLogRepository.insertBlindDetail(dlId, blindCells)

            // 출력 행위는 감사 로그에도 남긴다.
            auditLogService.record(
                logType = "RAW_VIEW",
                menuId = menuId,
                targetDesc = reportNm,
                resultCd = if (blindCnt > 0) "BLIND" else "ALLOW",
                maskedCnt = blindCnt,
                remark = "다운로드 형식=$formatCd, 행수=$rowCnt"
            )
            dlId
        }.onFailure { log.error("다운로드 이력 기록 실패 : report={} format={}", reportNm, format, it) }
            .getOrDefault(0L)
    }

    /**
     * 다운로드 이력 요약 조회 (No.222)
     */
    @Transactional(readOnly = true)
    fun getSummary(from: String?, to: String?): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.SYS_DL)
        val (fromDate, toDate) = DateUtils.periodOf(from, to)

        val summary = downloadLogRepository.findSummary(fromDate, toDate).toMutableMap()
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
        reportId: String?,
        deptNm: String?,
        format: String?,
        page: Int?,
        size: Int?
    ): Pair<List<Map<String, Any?>>, PageMeta> {
        authorizationService.requireMenu(MenuId.SYS_DL)

        val (fromDate, toDate) = DateUtils.periodOf(from, to)
        val paging = PageRequestParam.of(page, size)

        val total = downloadLogRepository.countLogs(fromDate, toDate, reportId, deptNm, format)
        val rows = downloadLogRepository.findLogs(fromDate, toDate, reportId, deptNm, format, paging.limit, paging.offset)

        return rows to PageMeta.of(paging.page, paging.size, total)
    }

    /**
     * 보존 정책 조회 (No.225)
     */
    @Transactional(readOnly = true)
    fun getRetentionPolicy(): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.SYS_DL)

        val status = downloadLogRepository.findRetentionStatus(appProperties.downloadRetentionYears).toMutableMap()
        // 보관 배치는 매월 1일 03:00 에 수행한다.
        status["nextArchiveAt"] = java.time.LocalDate.now()
            .plusMonths(1).withDayOfMonth(1).atTime(3, 0).format(DateUtils.DATETIME)
        return status
    }

    /** 다운로드 형식을 코드값(RPT_FORMAT)으로 정규화한다. */
    private fun normalizeFormat(format: String): String = when (format.lowercase()) {
        "xls", "xlsx", "excel" -> "XLS"
        "csv" -> "CSV"
        "pdf", "print" -> "PDF"
        else -> format.uppercase().take(30)
    }

    /** 현재 요청의 클라이언트 IP */
    private fun currentIp(): String? {
        val attrs = RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes ?: return null
        return clientIp(attrs.request)
    }

    private fun clientIp(request: HttpServletRequest): String? {
        val forwarded = request.getHeader("X-Forwarded-For")
        if (!forwarded.isNullOrBlank()) return forwarded.split(",").first().trim()
        return request.remoteAddr
    }
}
