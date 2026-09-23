package com.dwje.api.service

import com.dwje.api.repository.AgentRunRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Agent 실행 이력 기록기 — 각 Agent 가 제 일을 마친 자리에서 한 줄을 남긴다.
 *
 * 화면(SY-12 · AI 통합 대시보드)의 상태·최근 실행·처리량은 `ax.tb_ai_agent_run` 의
 * 최신 행에서 나온다. 기록하는 쪽이 없으면 9종이 전부 `IDLE` 로 보인다.
 *
 * **기록은 곁다리다. 실패해도 본 기능을 깨뜨리지 않는다.** 두 겹으로 막는다.
 *  1. [AgentRunWriter] 가 [Propagation.REQUIRES_NEW] 로 **별도 트랜잭션에서** 넣는다.
 *     여기서 제약 위반이 나도 부르는 쪽 트랜잭션이 함께 중단되지 않는다. 같은 트랜잭션에 넣으면
 *     INSERT 하나가 터진 순간 PostgreSQL 이 트랜잭션 전체를 막아, 정작 본 작업이 500 으로 끝난다.
 *     읽기 전용 트랜잭션(`@Transactional(readOnly = true)`) 안에서 불리는 자리도 많은데,
 *     새 트랜잭션이 아니면 거기서는 INSERT 자체가 거부된다(실측: 아침회의 자료 생성).
 *  2. try/catch — 그래도 남는 실패(연결 끊김 등)는 WARN 으로만 남기고 삼킨다.
 *
 * 기록기와 쓰기를 **다른 빈으로 나눈 이유**가 여기 있다. 같은 클래스 안에서 부르면
 * 프록시를 거치지 않아 `REQUIRES_NEW` 가 아예 걸리지 않고, try/catch 도 커밋 시점의 실패를
 * 잡지 못한다. 경계를 넘겨야 두 겹이 실제로 동작한다.
 *
 * 부르는 쪽은 `record(...)` 한 줄만 두면 된다. 걸린 시간을 재려면 [measure] 를 쓴다.
 */
@Service
class AgentRunRecorder(
    private val writer: AgentRunWriter
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 정상 종료를 기록한다.
     *
     * @param agentNo    ①~⑨ (`ax.tb_ai_agent.agent_no`). 코드에 agent_id 를 박지 않는다
     * @param throughput 화면의 "처리량" 열 — 예 "1,024건" · "36h 관측"
     * @param elapsedMs  걸린 시간(ms). 요약의 평균 응답 시간이 이 값을 쓴다
     * @return 생성된 run_id. 기록하지 못했으면 0
     */
    fun record(
        agentNo: String,
        throughput: String? = null,
        elapsedMs: Long? = null,
        message: String? = null,
        stateCd: String = STATE_OK
    ): Long = safeInsert(agentNo, stateCd, throughput, elapsedMs, message, error = false)

    /** 실패를 기록한다 — 요약의 `master.state` 가 이 행 때문에 ERROR 로 바뀐다. */
    fun recordError(agentNo: String, message: String?, elapsedMs: Long? = null): Long =
        safeInsert(agentNo, STATE_ERROR, null, elapsedMs, message, error = true)

    /**
     * [block] 을 재면서 실행하고 결과에 상관없이 한 줄을 남긴다.
     *
     * 성공하면 `OK` + 걸린 시간, 예외가 나면 `ERROR` 로 남기고 **예외는 그대로 올려보낸다** —
     * 기록하려고 오류를 삼키면 화면이 성공으로 오해한다.
     *
     * @param throughput 결과로 처리량 문구를 만든다. 예외가 났으면 부르지 않는다
     */
    fun <T> measure(agentNo: String, message: String? = null, throughput: (T) -> String? = { null }, block: () -> T): T {
        val startedAt = System.nanoTime()
        val result = try {
            block()
        } catch (e: Exception) {
            recordError(agentNo, "${message ?: "실행 실패"} : ${e.message}", elapsedOf(startedAt))
            throw e
        }
        record(agentNo, throughputOf(result, throughput), elapsedOf(startedAt), message)
        return result
    }

    /** 처리량 문구를 만들다 터져도 기록을 포기하지 않는다 */
    private fun <T> throughputOf(result: T, f: (T) -> String?): String? =
        runCatching { f(result) }.getOrNull()

    private fun elapsedOf(startedAtNanos: Long): Long = (System.nanoTime() - startedAtNanos) / 1_000_000

    /**
     * 실제 기록 — 새 트랜잭션에 맡기고, 실패는 로그로만 남긴다.
     *
     * try/catch 가 [AgentRunWriter] 호출 **바깥**에 있어야 커밋 단계의 실패까지 잡는다.
     */
    private fun safeInsert(
        agentNo: String,
        stateCd: String,
        throughput: String?,
        elapsedMs: Long?,
        message: String?,
        error: Boolean
    ): Long =
        try {
            val runId = writer.insertInNewTransaction(agentNo, stateCd, throughput, elapsedMs, message, error)
            if (runId == 0L) {
                log.warn("Agent 실행 이력을 남기지 못했습니다 — 그런 agent_no 가 없습니다. [{}]", agentNo)
            }
            runId
        } catch (e: Exception) {
            // 본 기능은 이미 끝났다. 기록 실패로 응답을 깨뜨리지 않는다.
            log.warn("Agent 실행 이력 기록 실패 : agentNo={} state={} msg={}", agentNo, stateCd, e.message)
            0L
        }

    companion object {
        const val STATE_OK = "OK"
        const val STATE_ERROR = "ERROR"

        /** ① 비전 수집 — AOI 원천(COSMETIC) 조회·이미지 매핑 */
        const val VISION = "①"
        /** ② 데이터 분류 — MES 이관·후처리 */
        const val CLASSIFY = "②"
        /** ③ 불량 판정 — AOI 판정 조회·HITL */
        const val JUDGE = "③"
        /** ④ 원인 분석 — 불량 원인 추정 */
        const val CAUSE = "④"
        /** ⑤ 이력 추적 — LOT·시리얼 공정 이력 */
        const val TRACE = "⑤"
        /** ⑥ 보고서 생성 — 보고서 초안 */
        const val REPORT = "⑥"
        /** ⑦ 보안 필터링 — 마스킹·공개 정책 */
        const val SECURITY = "⑦"
        /** ⑧ KG 구축 — 지식 그래프. 해당 기능 도입 전이라 아직 부르는 곳이 없다 */
        const val KG = "⑧"
        /** ⑨ 이상 알림 — **Alert_Engine 이 기록한다. API 에서 부르지 않는다** */
        const val ALERT = "⑨"
    }
}

/**
 * Agent 실행 이력 쓰기 — **오직 트랜잭션 경계를 만들기 위해** 따로 둔 빈이다.
 *
 * [AgentRunRecorder] 안에 두면 자기 호출이라 프록시를 타지 않고, 그러면
 * `REQUIRES_NEW` 가 걸리지 않아 부르는 쪽 트랜잭션에 그대로 얹힌다.
 * 읽기 전용 트랜잭션 안이면 INSERT 가 거부되고, 쓰기 트랜잭션이면 실패가 그쪽을 함께 무너뜨린다.
 */
@Component
class AgentRunWriter(
    private val agentRunRepository: AgentRunRepository
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun insertInNewTransaction(
        agentNo: String,
        stateCd: String,
        throughput: String?,
        elapsedMs: Long?,
        message: String?,
        error: Boolean
    ): Long = agentRunRepository.insert(agentNo, stateCd, throughput, elapsedMs, message, error)
}
