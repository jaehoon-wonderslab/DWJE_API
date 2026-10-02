package com.dwje.api.service

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.response.PageMeta
import com.dwje.api.common.security.UserContext
import com.dwje.api.common.util.AuditResult
import com.dwje.api.common.util.AuditType
import com.dwje.api.common.util.ClientIpResolver
import com.dwje.api.common.util.DateUtils
import com.dwje.api.common.util.MenuId
import com.dwje.api.common.util.PageRequestParam
import com.dwje.api.repository.AuditLogRepository
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicLong
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
    private val authorizationService: AuthorizationService,
    private val clientIpResolver: ClientIpResolver = ClientIpResolver(),
    private val transactionManager: PlatformTransactionManager? = null
) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** 감사 로그 조회 기간 상한 (09 AUD-12) */
        const val AUDIT_MAX_DAYS = 366L

        /** 감사 기록 실패 표시 (09 AUD-13) */
        private val AUDIT_WRITE_FAIL: org.slf4j.Marker = org.slf4j.MarkerFactory.getMarker("AUDIT_WRITE_FAIL")
    }

    /** 기동 뒤 감사 기록 실패 건수 — 보존 정책 응답의 writeFailSinceBoot (09 AUD-13 앞당김) */
    private val writeFails = AtomicLong()

    val writeFailSinceBoot: Long get() = writeFails.get()

    /**
     * 별도 트랜잭션에서 실행한다 — 트랜잭션 관리자가 없으면(단위 시험) 바로 실행한다.
     * `@Transactional(REQUIRES_NEW)` 대신 이것을 쓰는 이유: 프록시가 트랜잭션을 여는 단계의 실패(커넥션 획득 실패 등)도
     * 아래 runCatching 안에서 잡혀 본 업무로 번지지 않고 실패 건수에 들어간다. 같은 빈 안의 호출에도 새 트랜잭션이 걸린다 (09 AUD-13).
     */
    private fun <T> inNewTx(work: () -> T): T? =
        transactionManager?.let {
            TransactionTemplate(it).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }.execute { work() }
        } ?: work()

    /**
     * 감사 기록 실패 한 곳 — 실패 건수를 올리고 `AUDIT_WRITE_FAIL` 로 남긴다(로그 수집이 이 낱말로 찾는다, 09 AUD-13).
     *
     * @param kind AUDIT · AUDIT_AFTER_COMMIT · PERM_LINKED_AUDIT · PERM_LOG · AUDIT_VIEW
     */
    private fun onWriteFail(kind: String, logType: String?, menuId: String?, target: String?, e: Throwable) {
        writeFails.incrementAndGet()
        log.error(AUDIT_WRITE_FAIL, "AUDIT_WRITE_FAIL kind={} type={} menu={} target={}", kind, logType, menuId, target, e)
    }

    /**
     * 감사 로그를 기록한다.
     *
     * @param logType    LOG_AUDIT_TYPE — [AuditType]
     * @param menuId     화면 ID
     * @param fieldKey   대상 데이터 항목 key
     * @param targetDesc 대상 설명 (보고서명, 대상 계정 등)
     * @param resultCd   LOG_AUDIT_RESULT — [AuditResult]
     * @param maskedCnt  마스킹 처리 건수
     * @param remark     비고
     * @return 감사 행 ID — 실패하면 null. 권한 변경 이력과 잇는 데 쓴다 (09 AUD-06)
     */
    fun record(
        logType: String,
        menuId: String?,
        fieldKey: String? = null,
        targetDesc: String? = null,
        resultCd: String = "ALLOW",
        maskedCnt: Int = 0,
        remark: String? = null
    ): Long? {
        // 감사 기록 실패가 본 업무 트랜잭션을 되돌리면 안 되므로 예외를 삼키고 로그만 남긴다.
        val principal = UserContext.currentOrNull()
        return runCatching {
            // 계정 전환 토큰으로 한 행위는 원래 통합관리자와 잇는다 (09 AUD-02)
            val impersonator = principal?.impersonatedBy
            inNewTx { auditLogRepository.insert(
                logTypeCd = logType,
                userId = principal?.userId,
                deptNm = principal?.deptName,
                menuId = menuId,
                fieldKey = fieldKey,
                targetDesc = targetDesc,
                resultCd = resultCd,
                maskedCnt = maskedCnt,
                remark = if (impersonator != null) "[대행:$impersonator] ${remark.orEmpty()}".trimEnd() else remark,
                ipAddr = currentIp(),
                userAgent = currentUserAgent()
            ) }
        }.onFailure { onWriteFail("AUDIT", logType, menuId, targetDesc, it) }.getOrNull()
    }

    /**
     * 업무 트랜잭션이 **커밋된 뒤에** 감사 행을 남긴다 (09 AUD-10 · 11 UPD-05, 공통 CMN-03 「허용 기록은 커밋 뒤」).
     * 롤백되면 남기지 않는다. 트랜잭션 밖에서 부르면 바로 남긴다.
     */
    fun recordAfterCommit(logType: String, menuId: String?, targetDesc: String?, remark: String? = null) {
        val principal = UserContext.currentOrNull()
        val ip = currentIp()
        val ua = currentUserAgent()
        val work = {
            runCatching {
                val impersonator = principal?.impersonatedBy
                val run = {
                    auditLogRepository.insert(
                        logTypeCd = logType, userId = principal?.userId, deptNm = principal?.deptName, menuId = menuId, fieldKey = null,
                        targetDesc = targetDesc, resultCd = AuditResult.ALLOW, maskedCnt = 0,
                        remark = if (impersonator != null) "[대행:$impersonator] ${remark.orEmpty()}".trimEnd() else remark,
                        ipAddr = ip, userAgent = ua
                    )
                }
                inNewTx(run)
            }.onFailure { onWriteFail("AUDIT_AFTER_COMMIT", logType, menuId, targetDesc, it) }
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() { work() }
            })
        } else {
            work()
        }
    }

    /**
     * 권한 변경에 딸린 감사 행을 따로 커밋한다 — 실패해도 권한 변경 이력은 audit_id 없이 남는다 (09 AUD-06).
     * Postgres 는 한 문장이 실패하면 트랜잭션 전체가 중단되므로 같은 트랜잭션에 넣지 않는다.
     */
    private fun insertLinkedAudit(
        logType: String, userId: String?, deptNm: String?, menuId: String?, targetDesc: String?, resultCd: String, remark: String?
    ): Long? {
        val work = {
            auditLogRepository.insert(
                logTypeCd = logType, userId = userId, deptNm = deptNm, menuId = menuId, fieldKey = null,
                targetDesc = targetDesc, resultCd = resultCd, maskedCnt = 0, remark = remark,
                ipAddr = currentIp(), userAgent = currentUserAgent()
            )
        }
        return runCatching { inNewTx(work) }
            .onFailure { onWriteFail("PERM_LINKED_AUDIT", logType, menuId, targetDesc, it) }.getOrNull()
    }

    /** 권한 변경 종류별 기본 화면 — 감사 행의 menuId (09 AUD-06) */
    private fun defaultMenuOf(actCd: String): String? = when (actCd) {
        "ACCOUNT", "USER_MENU_PERM", "DEPT" -> MenuId.SYS_ACCOUNT
        "MENU_PERM" -> MenuId.SYS_MENU
        "DATA_PERM" -> MenuId.SYS_DATA
        "GW_DEPT_MAP" -> MenuId.SYS_GW_DEPT
        else -> null
    }

    /**
     * 권한 변경 이력을 기록한다. (계정·부서·메뉴권한·데이터권한 변경 시)
     *
     * @param actCd        SYS_PERM_ACT — ACCOUNT / DEPT / MENU_PERM / DATA_PERM
     * @param targetKindCd 대상 종류 (USER / DEPT / MENU / FIELD)
     * @param targetNm     대상 표시명
     * @param detail       변경 내용 (변경 전 → 변경 후)
     * @param auditId      같은 사건의 감사 행 ID — 호출처가 [record] 로 먼저 남겼으면 넘긴다. 없으면 기본 문구로 감사 행을 만든다
     *                     (09 AUD-06 — 감사 타임라인에는 이 감사 행 한 줄만 보인다)
     */
    fun recordPermChange(
        actCd: String,
        targetKindCd: String,
        targetNm: String,
        detail: String,
        targetDeptId: Int? = null,
        targetUserId: String? = null,
        auditId: Long? = null
    ) {
        runCatching {
            val principal = UserContext.current()
            val linked = auditId ?: insertLinkedAudit(
                AuditType.PERM_CHANGE, principal.userId, principal.deptName, defaultMenuOf(actCd), targetNm, AuditResult.ALLOW,
                principal.impersonatedBy?.let { "[대행:$it] $detail" } ?: detail
            )
            inNewTx { auditLogRepository.insertPermLog(
                actCd = actCd,
                targetKindCd = targetKindCd,
                targetDeptId = targetDeptId,
                targetUserId = targetUserId,
                targetNm = targetNm,
                detail = detail,
                actorUserId = principal.userId,
                actorDeptNm = principal.deptName,
                auditId = linked
            ) }
        }.onFailure { onWriteFail("PERM_LOG", actCd, defaultMenuOf(actCd), targetNm, it) }
    }

    /**
     * 로그인하지 않은 경로(시스템 잠금·본인 잠금 해제 등)의 권한 변경 이력 — 행위자를 직접 준다 (09 AUD-16).
     *
     * [recordPermChange] 는 로그인 사용자를 행위자로 쓰므로 비로그인 경로에서는 기록이 빠진다.
     * 행위자 관례는 퇴사 배치·로그인 잠금과 같은 `SYSTEM` 이다.
     */
    fun recordPermChangeAs(
        actorUserId: String,
        actCd: String,
        targetKindCd: String,
        targetNm: String,
        detail: String,
        targetUserId: String? = null,
        actorDeptNm: String? = null,
        auditId: Long? = null
    ) {
        runCatching {
            // 비로그인 경로라 감사 행의 사번은 비우고 대상 사번은 대상 설명에 넣는다 (09 AUD-16)
            val linked = auditId ?: insertLinkedAudit(
                AuditType.ACCOUNT_SEC, null, null, defaultMenuOf(actCd), targetNm, AuditResult.ALLOW, detail
            )
            inNewTx { auditLogRepository.insertPermLog(
                actCd = actCd,
                targetKindCd = targetKindCd,
                targetDeptId = null,
                targetUserId = targetUserId,
                targetNm = targetNm,
                detail = detail,
                actorUserId = actorUserId,
                actorDeptNm = actorDeptNm,
                auditId = linked
            ) }
        }.onFailure { onWriteFail("PERM_LOG", actCd, defaultMenuOf(actCd), targetNm, it) }
    }

    /**
     * 감사 로그를 조회한다. (No.190)
     *
     * - 기간 최대 366일, 유형은 쉼표로 여러 개, 결과는 [AuditResult] (09 AUD-09·12)
     * - 첫 쪽을 성공적으로 조회하면 감사 열람(AUDIT_VIEW)을 1행 남긴다. 검증 오류(400)면 남기지 않는다 (09 AUD-08)
     * - [exporting] 은 전체 내려받기 — 기간 상한·열람 기록 없이 모든 유형(AUDIT_VIEW 포함)을 읽는다(09 AUD-07 G-4)
     *
     * @param from      조회 시작일
     * @param to        조회 종료일
     * @param type      로그 유형 — 쉼표로 여러 개
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
        size: Int?,
        extra: AuditLogRepository.AuditFilter = AuditLogRepository.AuditFilter(),
        exporting: Boolean = false
    ): Pair<List<Map<String, Any?>>, PageMeta> {
        authorizationService.requireMenu(MenuId.SYS_AUDIT)
        extra.result?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }?.let {
            if (it !in AuditResult.ALL) {
                throw InvalidParameterException("결과는 ${AuditResult.ALL.joinToString(" · ")} 중 하나여야 합니다.", "result")
            }
        }
        auditLogRepository.typesOf(type).firstOrNull { it !in AuditType.ALL }?.let {
            throw InvalidParameterException("알 수 없는 감사 유형입니다. [$it]", "type")
        }
        auditLogRepository.ipMode(extra.ip)

        val (fromDate, toDate) = DateUtils.periodOf(from, to)
        if (!exporting && ChronoUnit.DAYS.between(fromDate, toDate) + 1 > AUDIT_MAX_DAYS) {
            throw InvalidParameterException("조회 기간은 최대 ${AUDIT_MAX_DAYS}일입니다.", "from")
        }
        val paging = PageRequestParam.of(page, size)
        val types = if (exporting && type.isNullOrBlank()) AuditType.ALL.joinToString(",") else type

        val total = auditLogRepository.countAuditLogs(fromDate, toDate, types, userGroup, empNo, extra)
        val rows = auditLogRepository.findAuditLogs(fromDate, toDate, types, userGroup, empNo, paging.limit, paging.offset, extra)

        if (!exporting && paging.page == 1) {
            recordAuditView("감사 로그 조회 [$fromDate~$toDate]", auditCondSummary(type, userGroup, empNo, extra))
        }
        return rows to PageMeta.of(paging.page, paging.size, total)
    }

    /**
     * 감사·다운로드 기록 열람(AUDIT_VIEW) 1행 (09 AUD-08) — 읽기 전용 트랜잭션 안에서 불려도 [record] 가 새 트랜잭션을 연다.
     */
    fun recordAuditView(targetDesc: String, condSummary: String, menuId: String = MenuId.SYS_AUDIT) {
        runCatching { record(logType = AuditType.AUDIT_VIEW, menuId = menuId, targetDesc = targetDesc, remark = condSummary.ifEmpty { null }) }
            .onFailure { onWriteFail("AUDIT_VIEW", AuditType.AUDIT_VIEW, menuId, targetDesc, it) }
    }

    /** 감사 열람 기록의 조건 요약 — 값만 적고 결과 행은 적지 않는다 */
    private fun auditCondSummary(type: String?, userGroup: String?, empNo: String?, extra: AuditLogRepository.AuditFilter): String =
        listOfNotNull(
            auditLogRepository.typesOf(type).takeIf { it.isNotEmpty() }?.let { "유형 ${it.joinToString(",")}" },
            userGroup?.trim()?.takeIf { it.isNotEmpty() }?.let { "부서 $it" },
            empNo?.trim()?.takeIf { it.isNotEmpty() }?.let { "사번 $it" },
            extra.keyword?.trim()?.takeIf { it.isNotEmpty() }?.let { "검색어 $it" },
            extra.ip?.trim()?.takeIf { it.isNotEmpty() }?.let { "IP $it" },
            extra.result?.trim()?.takeIf { it.isNotEmpty() }?.let { "결과 ${it.uppercase()}" },
            "로그인 성공 제외".takeIf { extra.excludeLoginSuccess }
        ).joinToString(" · ").take(500)

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
        keyword: String? = null,
        targetUserId: String? = null
    ): Pair<List<Map<String, Any?>>, PageMeta> {
        val principal = authorizationService.requireAnyMenu(MenuId.SYS_ACCOUNT, MenuId.SYS_MENU, MenuId.SYS_DATA, MenuId.SYS_GW_DEPT)
        // 그룹웨어 부서 매핑 화면 권한만 있으면 그 화면 이력만 본다(01 ACC-09 · 02 GWD-10)
        val gwDeptOnly = listOf(MenuId.SYS_ACCOUNT, MenuId.SYS_MENU, MenuId.SYS_DATA).none { principal.canAccessMenu(it) }

        val (fromDate, toDate) = DateUtils.periodOf(from, to, 90)
        if (java.time.temporal.ChronoUnit.DAYS.between(fromDate, toDate) + 1 > 366) {
            throw com.dwje.api.common.exception.InvalidParameterException("조회 기간은 최대 365일입니다.", "to")
        }
        // size=0 이면 전량 — 화면이 열 필터를 전체 결과에 걸고 쪽은 브라우저에서 나눈다.
        val paging = PageRequestParam.ofAllowAll(page, size)

        val total = auditLogRepository.countPermLogs(fromDate, toDate, target, actType, keyword, targetUserId, gwDeptOnly)
        val rows = auditLogRepository.findPermLogs(fromDate, toDate, target, actType, paging.limitOrNull, paging.offset, keyword,
            targetUserId, gwDeptOnly)
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

    /** 현재 요청의 클라이언트 IP — 판정 규칙은 [ClientIpResolver] 한 곳에 있다(AUD-03). 요청 컨텍스트가 없으면 null */
    private fun currentIp(): String? = clientIpResolver.currentRequestIp()

    /** 요청의 브라우저 정보(User-Agent) — 요청 밖(배치)이면 null (09 AUD-14, V58) */
    private fun currentUserAgent(): String? =
        (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request?.getHeader("User-Agent")
}
