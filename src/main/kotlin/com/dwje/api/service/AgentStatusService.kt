package com.dwje.api.service

import com.dwje.api.common.exception.ResourceNotFoundException
import com.dwje.api.common.response.PageMeta
import com.dwje.api.common.util.DateUtils
import com.dwje.api.common.util.MenuId
import com.dwje.api.common.util.PageRequestParam
import com.dwje.api.common.validation.CodeValidator
import com.dwje.api.repository.AgentRunRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Agent 실행 현황 서비스 (SY-12 · ai-agent)
 *
 * Worker Agent 9종의 상태·최근 실행·처리량을 `ax.tb_ai_agent_run` 의 최신 행에서 낸다.
 * 이력을 **쓰는** 쪽은 [AgentRunRecorder] 다 — 각 Agent 가 제 일을 마친 자리에서 한 줄씩 남긴다.
 *
 * ## Master AI 파이프라인(No.212)은 없다
 * 근거 표 `ax.tb_ai_pipeline_stage` 를 V31(2026-09-15)이 지웠다. 단계별 계측 자리가 사라져
 * 화면의 파이프라인 도식은 이 API 로 답할 수 없다. 되살리려면 표부터 만들어야 한다(DB 담당).
 *
 * 접근 : 화면 권한 `ai-agent`(ax.tb_sys_dept_menu_perm) · 값 마스킹 : 없음 (웹 메뉴 없음 — 통합관리자 전용)
 */
@Service
class AgentStatusService(
    private val agentRunRepository: AgentRunRepository,
    private val agentRunRecorder: AgentRunRecorder,
    private val authorizationService: AuthorizationService,
    private val auditLogService: AuditLogService,
    private val codeValidator: CodeValidator
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Agent 요약 (No.210) — 30초 폴링 대상 */
    @Transactional(readOnly = true)
    fun getSummary(): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.AI_AGENT)
        return agentRunRepository.findSummary()
    }

    /** Agent 목록 조회 (No.211) — 9종 */
    @Transactional(readOnly = true)
    fun getAgents(): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.AI_AGENT)
        return mapOf("items" to agentRunRepository.findAgents())
    }

    /**
     * Agent 재시작 (No.213)
     *
     * **실제 프로세스를 다시 띄우지 않는다.** Agent 는 아직 별도 런타임이 아니라 API 안의
     * 작업 지점들이라 "죽은 것을 살리는" 대상이 없다. 그래서 이 API 가 하는 일은
     * 재시작 요청을 실행 이력과 감사 로그에 남겨 **상태를 다시 세는 기준점**을 만드는 것뿐이다.
     * 응답의 `note` 로 그 사실을 함께 알린다 — 화면이 "재시작됨" 으로 단정하지 않도록.
     */
    @Transactional
    fun restartAgent(agentCd: String): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.AI_AGENT)

        val agent = agentRunRepository.findAgent(agentCd)
            ?: throw ResourceNotFoundException("Agent 를 찾을 수 없습니다. [$agentCd]")

        val runId = agentRunRecorder.record(
            agentNo = agentCd,
            throughput = null,
            message = "관리자 재시작 요청",
            stateCd = "RUNNING"
        )

        auditLogService.record(
            logType = "AUTO_GEN",
            menuId = MenuId.AI_AGENT,
            targetDesc = "Agent 재시작 [$agentCd ${agent["name"]}]",
            remark = "runId=$runId"
        )
        log.info("Agent 재시작 요청 : agentNo={} runId={}", agentCd, runId)

        return mapOf(
            "success" to true,
            "restartedAt" to LocalDateTime.now().format(DateUtils.DATETIME),
            "runId" to runId,
            "note" to "Agent 별도 런타임이 없어 실행 이력만 남깁니다. 다음 작업이 끝나면 상태가 갱신됩니다."
        )
    }

    /** Agent 실행 이력 (No.214) — 기본 최근 7일 */
    @Transactional(readOnly = true)
    fun getAgentRuns(
        agentCd: String,
        from: String?,
        to: String?,
        state: String?,
        page: Int?,
        size: Int?
    ): Pair<List<Map<String, Any?>>, PageMeta> {
        authorizationService.requireMenu(MenuId.AI_AGENT)

        agentRunRepository.findAgent(agentCd)
            ?: throw ResourceNotFoundException("Agent 를 찾을 수 없습니다. [$agentCd]")

        codeValidator.require("AI_AGENT_STATE", state, "state", "실행 상태")

        val (fromDate, toDate) = DateUtils.periodOf(from, to, 7)
        val paging = PageRequestParam.of(page, size)

        val fromAt = fromDate.atStartOfDay().atZone(ZoneId.systemDefault()).toOffsetDateTime()
        // to 는 그 날을 포함한다
        val toAt = toDate.plusDays(1).atStartOfDay().atZone(ZoneId.systemDefault()).toOffsetDateTime()

        val total = agentRunRepository.countRuns(agentCd, fromAt, toAt, state)
        val rows = agentRunRepository.findRuns(agentCd, fromAt, toAt, state, paging.limit, paging.offset)

        return rows to PageMeta.of(paging.page, paging.size, total)
    }

    /** Agent 사용/미사용 — 마스터 9행은 지우지 않고 끄고 켠다 */
    @Transactional
    fun changeAgentState(agentCd: String, on: Boolean): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.AI_AGENT)

        val agent = agentRunRepository.findAgent(agentCd)
            ?: throw ResourceNotFoundException("Agent 를 찾을 수 없습니다. [$agentCd]")

        val changed = agentRunRepository.updateUseFlg(agentCd, on)
        auditLogService.record(
            logType = "AUTO_GEN",
            menuId = MenuId.AI_AGENT,
            targetDesc = "Agent ${if (on) "사용" else "미사용"} [$agentCd ${agent["name"]}]",
            remark = "changed=$changed"
        )

        return mapOf("success" to true, "agentCd" to agentCd, "applied" to on, "changed" to (changed > 0))
    }

}
