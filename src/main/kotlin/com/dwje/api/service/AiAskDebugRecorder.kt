package com.dwje.api.service

import com.dwje.api.repository.AiChatDebugRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.transaction.TransactionDefinition
import java.time.LocalDate
import java.util.UUID

data class AiAskDebug(
    val requestId: UUID,
    val userId: String,
    val chatId: Long?,
    val route: String,
    val parseCode: String,
    val tool: String?,
    val executionCode: String,
    val errorCode: String?,
    val periodFrom: LocalDate?,
    val periodTo: LocalDate?,
    val rowCount: Int,
    val docHitCount: Int,
    val toolMs: Int,
    val totalMs: Int
)

@Service
class AiAskDebugRecorder(private val repository: AiChatDebugRepository, transactionManager: PlatformTransactionManager) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val writeTransaction = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    /** 실패한 ask의 바깥 트랜잭션과 별도로 기록한다. V44 미적용 시 구조화 로그만 남긴다. */
    fun record(debug: AiAskDebug) {
        runCatching { writeTransaction.execute { if (repository.available()) repository.insert(debug); null } }
            .onFailure { log.warn("AI ask 진단 저장 실패 requestId={} type={}", debug.requestId, it.javaClass.simpleName) }
        log.info("AI ask 진단 requestId={} route={} parse={} tool={} execution={} error={} rows={} hits={} toolMs={} totalMs={}",
            debug.requestId, debug.route, debug.parseCode, debug.tool, debug.executionCode,
            debug.errorCode, debug.rowCount, debug.docHitCount, debug.toolMs, debug.totalMs)
    }

    @Transactional(readOnly = true)
    fun byChatId(chatId: Long): Map<String, Any?>? = if (repository.available()) repository.findByChatId(chatId) else null

    @Transactional(readOnly = true)
    fun byRequestId(requestId: UUID): Map<String, Any?>? = if (repository.available()) repository.findByRequestId(requestId) else null
}
