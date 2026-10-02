package com.dwje.api.service

import com.dwje.api.common.util.AuditType
import com.dwje.api.common.util.MenuId
import com.dwje.api.config.AppProperties
import com.dwje.api.repository.AiChatRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.ZoneId

/**
 * 질의 이력 보존 기간 경과 파기 (08 CHH-08) — 매일 03:10(한국 시각).
 *
 * `app.ai.chat-retention-days` 가 0 이하면 아무것도 하지 않는다(보존 기간 결정 전 기본 0, Q5).
 * 검토가 있는 질의를 더 오래 두는 규칙은 결정 대기라 두지 않았다.
 */
@Component
class AiChatRetentionJob(
    private val aiChatRepository: AiChatRepository,
    private val auditLogService: AuditLogService,
    private val appProperties: AppProperties
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 10 3 * * *", zone = "Asia/Seoul")
    fun run() {
        runCatching { purge(appProperties.ai.chatRetentionDays) }
            .onFailure { log.error("질의 이력 보존 기간 파기 실패", it) }
    }

    /**
     * [days] 일보다 오래된 질의를 지운다. 0 이하면 0건.
     *
     * @return 표별 지운 행 수 (chat · query · debug)
     */
    @Transactional
    fun purge(days: Int): Map<String, Int> {
        if (days <= 0) return emptyMap()
        val cut = LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(days.toLong()).atStartOfDay()
        val deleted = aiChatRepository.purgeBefore(cut)
        auditLogService.record(
            logType = AuditType.AUTO_GEN, menuId = MenuId.CHAT_HISTORY,
            targetDesc = "질의 이력 보존 기간 경과 삭제", remark = "보존 ${days}일, cut=$cut, " +
                deleted.entries.joinToString(", ") { "${it.key}=${it.value}" }
        )
        log.info("질의 이력 보존 기간 파기 : days={} cut={} deleted={}", days, cut, deleted)
        return deleted
    }
}
