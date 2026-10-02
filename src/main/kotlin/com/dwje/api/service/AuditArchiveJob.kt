package com.dwje.api.service

import com.dwje.api.common.util.AuditResult
import com.dwje.api.common.util.AuditType
import com.dwje.api.common.util.MenuId
import com.dwje.api.config.AppProperties
import com.dwje.api.repository.AuditArchiveRepository
import com.dwje.api.repository.AuditArchiveRepository.Source
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.support.CronExpression
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 감사·다운로드 기록 아카이브 배치 (09 AUD-11 · 10 DLG-07)
 *
 * 보존 연수가 지난 행을 원본 표에서 아카이브 표(`*_arch`)로 옮긴다. 원천 순서는 감사 → 권한 변경 → 로그인 → 다운로드.
 * 묶음 하나가 트랜잭션 하나다 — 실패하면 그 묶음만 롤백되고 앞 묶음은 커밋된 그대로 남는다. 다음 원천으로 넘어간다.
 * 기본 꺼짐(`app.audit-archive-enabled=false`) — 보존 기간·원본 삭제 결정(공통 D-08) 전이다. 수동 실행 HTTP API 는 두지 않는다.
 */
@Component
class AuditArchiveJob(
    private val repository: AuditArchiveRepository,
    private val auditLogService: AuditLogService,
    private val appProperties: AppProperties,
    private val transactionManager: PlatformTransactionManager
) {

    private val log = LoggerFactory.getLogger(javaClass)

    data class ArchiveResult(val moved: Map<String, Int>, val failed: List<String>, val elapsedMs: Long, val skipped: Boolean = false)

    @Scheduled(cron = "\${app.audit-archive-cron:0 0 3 1 * *}", zone = "\${app.audit-archive-zone:Asia/Seoul}")
    fun scheduled() {
        if (!appProperties.auditArchiveEnabled) return
        runOnce("SCHEDULE")
    }

    /** 한 회차 실행 — 시험·운영 점검용(HTTP 로는 열지 않는다) */
    fun runOnce(trigger: String): ArchiveResult {
        val started = System.currentTimeMillis()
        val moved = linkedMapOf<String, Int>()
        val failed = mutableListOf<String>()
        val tx = TransactionTemplate(transactionManager)
        val batch = appProperties.auditArchiveBatchSize
        var locked = false
        for (source in Source.entries) {
            val years = if (source == Source.DOWNLOAD) appProperties.downloadRetentionYears else appProperties.auditRetentionYears
            var total = 0
            try {
                while (true) {
                    val n = tx.execute {
                        if (!repository.tryLock()) { locked = true; return@execute -1 }
                        repository.archiveBefore(source, repository.cutoff(years), batch)
                    } ?: 0
                    if (n < 0) break
                    total += n
                    if (n < batch) break
                }
            } catch (e: Exception) {
                failed += source.name
                log.error("감사 기록 아카이브 실패 : source={}", source, e)
            }
            moved[source.name] = total
            if (locked) break
        }
        val elapsed = System.currentTimeMillis() - started
        if (locked) {
            log.warn("감사 기록 아카이브 건너뜀 — 다른 인스턴스가 실행 중입니다")
            return ArchiveResult(moved, failed, elapsed, skipped = true)
        }
        val cut = repository.cutoff(appProperties.auditRetentionYears).toLocalDate()
        auditLogService.record(
            logType = AuditType.AUTO_GEN, menuId = MenuId.SYS_AUDIT,
            targetDesc = "감사 기록 아카이브 [기준 $cut]",
            resultCd = if (failed.isEmpty()) AuditResult.ALLOW else AuditResult.REJECT,
            remark = "감사 ${moved["AUDIT"] ?: 0} · 권한 ${moved["PERM"] ?: 0} · 로그인 ${moved["LOGIN"] ?: 0} · 다운로드 ${moved["DOWNLOAD"] ?: 0}" +
                " · ${elapsed}ms · $trigger" + (if (failed.isNotEmpty()) " · 실패 ${failed.joinToString(",")}" else "")
        )
        log.info("감사 기록 아카이브 : moved={} failed={} {}ms", moved, failed, elapsed)
        return ArchiveResult(moved, failed, elapsed)
    }

    /** 다음 실행 시각 — 꺼져 있으면 null (10 DLG-07, 09 G-11) */
    fun nextRunAt(): ZonedDateTime? =
        if (!appProperties.auditArchiveEnabled) null
        else CronExpression.parse(appProperties.auditArchiveCron).next(ZonedDateTime.now(ZoneId.of(appProperties.auditArchiveZone)))
}
