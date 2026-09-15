package com.dwje.api.service

import com.dwje.api.common.response.PageMeta
import com.dwje.api.common.security.UserContext
import com.dwje.api.common.util.DateUtils
import com.dwje.api.common.util.MenuId
import com.dwje.api.common.util.PageRequestParam
import com.dwje.api.repository.AuditLogRepository
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/**
 * 보안 감사 로그 서비스 (SY-09)
 *
 * 공통 규약 「6. 감사 로그 자동 기록 대상」에 정의된 처리를 각 서비스가 호출한다.
 * 감사 기록 실패가 본 업무를 되돌리지 않도록 별도 트랜잭션(REQUIRES_NEW)으로 처리한다.
 */
@Service
class AuditLogService(
    private val auditLogRepository: AuditLogRepository,
    private val authorizationService: AuthorizationService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 감사 로그를 기록한다.
     *
     * @param logType    LOG_AUDIT_TYPE — MASK / UNMASK_REQ / RAW_VIEW / PERM_CHANGE / LOGIN / AUTO_GEN
     * @param menuId     화면 ID
     * @param fieldKey   대상 데이터 항목 key
     * @param targetDesc 대상 설명 (보고서명, 대상 계정 등)
     * @param resultCd   LOG_AUDIT_RESULT — ALLOW / BLIND / REJECT
     * @param maskedCnt  마스킹 처리 건수
     * @param remark     비고
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun record(
        logType: String,
        menuId: String?,
        fieldKey: String? = null,
        targetDesc: String? = null,
        resultCd: String = "ALLOW",
        maskedCnt: Int = 0,
        remark: String? = null
    ) {
        // 감사 기록 실패가 본 업무 트랜잭션을 되돌리면 안 되므로 예외를 삼키고 로그만 남긴다.
        runCatching {
            val principal = UserContext.currentOrNull()
            auditLogRepository.insert(
                logTypeCd = logType,
                userId = principal?.userId,
                deptNm = principal?.deptName,
                menuId = menuId,
                fieldKey = fieldKey,
                targetDesc = targetDesc,
                resultCd = resultCd,
                maskedCnt = maskedCnt,
                remark = remark,
                ipAddr = currentIp()
            )
        }.onFailure { log.error("감사 로그 기록 실패 : type={} menu={}", logType, menuId, it) }
    }

    /**
     * 권한 변경 이력을 기록한다. (계정·부서·메뉴권한·데이터권한 변경 시)
     *
     * @param actCd        SYS_PERM_ACT — ACCOUNT / DEPT / MENU_PERM / DATA_PERM
     * @param targetKindCd 대상 종류 (USER / DEPT / MENU / FIELD)
     * @param targetNm     대상 표시명
     * @param detail       변경 내용 (변경 전 → 변경 후)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recordPermChange(
        actCd: String,
        targetKindCd: String,
        targetNm: String,
        detail: String,
        targetDeptId: Int? = null,
        targetUserId: String? = null
    ) {
        runCatching {
            val principal = UserContext.current()
            auditLogRepository.insertPermLog(
                actCd = actCd,
                targetKindCd = targetKindCd,
                targetDeptId = targetDeptId,
                targetUserId = targetUserId,
                targetNm = targetNm,
                detail = detail,
                actorUserId = principal.userId,
                actorDeptNm = principal.deptName
            )
        }.onFailure { log.error("권한 변경 이력 기록 실패 : act={} target={}", actCd, targetNm, it) }
    }

    /**
     * 감사 로그를 조회한다. (No.190)
     *
     * @param from      조회 시작일
     * @param to        조회 종료일
     * @param type      로그 유형
     * @param userGroup 부서명
     * @param empNo     대상 사번
     */
    @Transactional(readOnly = true)
    fun getAuditLogs(
        from: String?,
        to: String?,
        type: String?,
        userGroup: String?,
        empNo: String?,
        page: Int?,
        size: Int?
    ): Pair<List<Map<String, Any?>>, PageMeta> {
        authorizationService.requireMenu(MenuId.SYS_AUDIT)

        val (fromDate, toDate) = DateUtils.periodOf(from, to)
        val paging = PageRequestParam.of(page, size)

        val total = auditLogRepository.countAuditLogs(fromDate, toDate, type, userGroup, empNo)
        val rows = auditLogRepository.findAuditLogs(fromDate, toDate, type, userGroup, empNo, paging.limit, paging.offset)

        return rows to PageMeta.of(paging.page, paging.size, total)
    }

    /**
     * 계정·권한 변경 이력을 조회한다. (No.139)
     */
    @Transactional(readOnly = true)
    fun getPermLogs(
        from: String?,
        to: String?,
        target: String?,
        actType: String?,
        page: Int?,
        size: Int?,
        keyword: String? = null
    ): Pair<List<Map<String, Any?>>, PageMeta> {
        authorizationService.requireAnyMenu(MenuId.SYS_ACCOUNT, MenuId.SYS_MENU, MenuId.SYS_DATA)

        val (fromDate, toDate) = DateUtils.periodOf(from, to, 90)
        // size=0 이면 전량 — 화면이 열 필터를 전체 결과에 걸고 쪽은 브라우저에서 나눈다.
        val paging = PageRequestParam.ofAllowAll(page, size)

        val total = auditLogRepository.countPermLogs(fromDate, toDate, target, actType, keyword)
        val rows = auditLogRepository.findPermLogs(fromDate, toDate, target, actType, paging.limitOrNull, paging.offset, keyword)
        if (paging.isAll) return rows to PageMeta.all(total)

        return rows to PageMeta.of(paging.page, paging.size, total)
    }

    /**
     * 데이터 접근 감사 조회 (No.150)
     */
    @Transactional(readOnly = true)
    fun getDataAccessAudit(
        from: String?,
        to: String?,
        empNo: String?,
        fieldKey: String?,
        page: Int?,
        size: Int?
    ): Pair<List<Map<String, Any?>>, PageMeta> {
        authorizationService.requireMenu(MenuId.SYS_DATA)

        val (fromDate, toDate) = DateUtils.periodOf(from, to)
        val paging = PageRequestParam.of(page, size)

        val total = auditLogRepository.countDataAccessAudit(fromDate, toDate, empNo, fieldKey)
        val rows = auditLogRepository.findDataAccessAudit(fromDate, toDate, empNo, fieldKey, paging.limit, paging.offset)

        return rows to PageMeta.of(paging.page, paging.size, total)
    }

    /**
     * 현재 요청의 클라이언트 IP 를 추출한다. (요청 컨텍스트가 없으면 null)
     */
    private fun currentIp(): String? {
        val attrs = RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes ?: return null
        return clientIp(attrs.request)
    }

    /** 프록시 경유 시 실제 클라이언트 IP 를 추출한다. */
    private fun clientIp(request: HttpServletRequest): String? {
        val forwarded = request.getHeader("X-Forwarded-For")
        if (!forwarded.isNullOrBlank()) return forwarded.split(",").first().trim()
        return request.remoteAddr
    }
}
