package com.dwje.api.service

import com.dwje.api.common.util.DateUtils
import com.dwje.api.common.util.MenuId
import com.dwje.api.config.AppProperties
import com.dwje.api.repository.AuditArchiveRepository
import com.dwje.api.repository.AuditArchiveRepository.Source
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 감사 로그 보존 정책 조회 (09 AUD-11) — 원천별 건수·보존 경과·아카이브 건수, 배치 상태.
 * 조회 자체는 감사 열람으로 남기지 않는다(09 4.6). 다운로드 원천은 `/download-logs/retention-policy` 가 맡는다.
 */
@Service
class AuditRetentionService(
    private val authorizationService: AuthorizationService,
    private val archiveRepository: AuditArchiveRepository,
    private val archiveJob: AuditArchiveJob,
    private val auditLogService: AuditLogService,
    private val appProperties: AppProperties,
    private val mailSendStats: com.dwje.api.common.mail.MailSendStats = com.dwje.api.common.mail.MailSendStats()
) {

    @Transactional(readOnly = true)
    fun getRetentionPolicy(): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.SYS_AUDIT)
        val cutoff = archiveRepository.cutoff(appProperties.auditRetentionYears)
        return mapOf(
            "retentionYears" to appProperties.auditRetentionYears,
            "enabled" to appProperties.auditArchiveEnabled,
            "sources" to listOf(Source.AUDIT, Source.PERM, Source.LOGIN).map { archiveRepository.status(it, cutoff) - "lastArchivedAt" },
            "lastArchiveAt" to archiveRepository.findLastArchiveAt(),
            "nextArchiveAt" to archiveJob.nextRunAt()?.toLocalDateTime()?.format(DateUtils.DATETIME),
            "writeFailSinceBoot" to auditLogService.writeFailSinceBoot,
            // 인증 메일 발송 실패 수(기동 뒤) — 「기록 실패 수」 옆에 보인다 (R-17)
            "mailFailSinceBoot" to mailSendStats.failSinceBoot
        )
    }
}
