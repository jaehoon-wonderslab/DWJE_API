package com.dwje.api.service

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.security.UserContext
import com.dwje.api.common.util.DateUtils
import com.dwje.api.common.util.MenuId
import com.dwje.api.model.request.ReportWriteStateRequest
import com.dwje.api.repository.DecisionTrace
import com.dwje.api.repository.ReportWriteStateRepository
import com.dwje.api.repository.WriteStateRow
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * 보고서 작성 상태 서비스 — 보고서 센터 "오늘 작성할 보고서" 띠
 *
 * ## 판정 규칙
 * `state = max(파생 상태, 기록 상태)`. 순서는 NONE < DRAFT < SUBMITTED < APPROVED.
 * 같으면 기록을 우선한다(누가 눌렀는지가 더 정확한 정보다).
 *
 * ## 파생은 왜 DRAFT 까지만인가
 * 2026-09-04 문서 관리(초안·확정·결재) 제거로 제출·승인을 읽어낼 원천이 없다.
 * 남은 사람 입력은 `ax.tb_prod_daily_decision` 하나라
 * - `prod-daily`        : 대상일에 행이 있으면 DRAFT
 * - `rpt-press-morning` : 판정(decision)이 적힌 행이 있으면 DRAFT
 * - `rpt-plating-morning` · `rpt-scrap` : 파생 불가 — 기록만 본다
 */
@Service
class ReportWriteStateService(
    private val repository: ReportWriteStateRepository,
    private val authorizationService: AuthorizationService
) {

    companion object {
        /** 작성 상태를 다루는 화면. 조회형(수율·LRR·출하계획)은 대상이 아니다. 응답 순서이기도 하다. */
        val TARGET_SCREENS: List<String> = listOf(
            MenuId.PROD_DAILY, MenuId.RPT_PRESS_MORNING, MenuId.RPT_PLATING_MORNING, MenuId.RPT_SCRAP
        )

        const val STATE_NONE = "NONE"
        const val STATE_DRAFT = "DRAFT"
        const val STATE_SUBMITTED = "SUBMITTED"
        const val STATE_APPROVED = "APPROVED"

        /** 기록으로 쓸 수 있는 상태. NONE 은 행이 없는 것이라 기록하지 않는다. */
        val WRITABLE_STATES: List<String> = listOf(STATE_DRAFT, STATE_SUBMITTED, STATE_APPROVED)

        private val RANK: Map<String, Int> = mapOf(
            STATE_NONE to 0, STATE_DRAFT to 1, STATE_SUBMITTED to 2, STATE_APPROVED to 3
        )

        const val SOURCE_DERIVED = "DERIVED"
        const val SOURCE_RECORDED = "RECORDED"

        /**
         * 파생 상태와 기록 상태 중 높은 쪽을 고른다. 같으면 기록.
         *
         * @return 선택된 항목 (state 가 NONE 이면 source·갱신 정보는 null)
         */
        fun resolve(screenId: String, derived: WriteStateRow?, recorded: WriteStateRow?): Map<String, Any?> {
            val derivedRank = derived?.let { RANK[it.state] } ?: 0
            val recordedRank = recorded?.let { RANK[it.state] } ?: 0

            val (chosen, source) = when {
                recordedRank == 0 && derivedRank == 0 -> null to null
                recordedRank >= derivedRank -> recorded to SOURCE_RECORDED
                else -> derived to SOURCE_DERIVED
            }

            return mapOf(
                "screenId" to screenId,
                "state" to (chosen?.state ?: STATE_NONE),
                "source" to source,
                "updatedAt" to chosen?.updatedAt,
                "updatedBy" to chosen?.updatedBy,
                "updatedByName" to chosen?.updatedByName
            )
        }

        /**
         * 아침회의 결과 행에서 파생 상태를 만든다.
         *
         * 행은 최근 갱신 순으로 온다고 가정한다 — 첫 행이 마지막으로 건드린 사람이다.
         */
        fun derive(rows: List<DecisionTrace>): Map<String, WriteStateRow> {
            val result = mutableMapOf<String, WriteStateRow>()
            rows.firstOrNull()?.let {
                result[MenuId.PROD_DAILY] = WriteStateRow(
                    MenuId.PROD_DAILY, STATE_DRAFT, it.touchedAt, it.touchedBy, it.touchedByName
                )
            }
            rows.firstOrNull { it.hasDecision }?.let {
                result[MenuId.RPT_PRESS_MORNING] = WriteStateRow(
                    MenuId.RPT_PRESS_MORNING, STATE_DRAFT, it.touchedAt, it.touchedBy, it.touchedByName
                )
            }
            return result
        }
    }

    /**
     * 대상일의 작성 상태를 화면별로 돌려준다.
     *
     * 호출자에게 메뉴 권한이 있는 화면만 낸다. 하나도 없으면 `items: []` 로 200 이다 —
     * 허브 띠가 비어 있는 것은 오류가 아니다.
     */
    fun getStatus(baseDate: String?): Map<String, Any?> {
        val principal = UserContext.current()
        val date = DateUtils.parseDate(baseDate, "baseDate", LocalDate.now())

        val visible = TARGET_SCREENS.filter { principal.canAccessMenu(it) }
        if (visible.isEmpty()) {
            return mapOf("baseDate" to date.format(DateUtils.DATE), "items" to emptyList<Any>())
        }

        val recorded = repository.findRecorded(date)
        val derived = derive(repository.findDecisionRows(date))

        val items = visible.map { screenId -> resolve(screenId, derived[screenId], recorded[screenId]) }
        return mapOf("baseDate" to date.format(DateUtils.DATE), "items" to items)
    }

    /**
     * 작성 상태를 기록한다. 화면 머리말의 「제출」「승인」「작성 중으로 되돌리기」 단추가 호출한다.
     *
     * 대상 화면이 아니면 400, 해당 화면 메뉴 권한이 없으면 E-AUTH-002 다.
     * 기록 뒤에는 파생과 다시 합치지 않고 기록 한 건을 그대로 돌려준다 — 방금 누른 값이 보여야 한다.
     */
    @Transactional
    fun setStatus(request: ReportWriteStateRequest): Map<String, Any?> {
        val screenId = request.screenId?.trim()?.takeIf { it.isNotBlank() }
            ?: throw InvalidParameterException("필수 파라미터가 누락되었습니다. [screenId]", "screenId")
        if (screenId !in TARGET_SCREENS) {
            throw InvalidParameterException(
                "작성 상태를 기록할 수 있는 화면이 아닙니다. [$screenId] 가능: ${TARGET_SCREENS.joinToString()}", "screenId"
            )
        }

        val state = request.state?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
            ?: throw InvalidParameterException("필수 파라미터가 누락되었습니다. [state]", "state")
        if (state !in WRITABLE_STATES) {
            throw InvalidParameterException(
                "상태 값이 올바르지 않습니다. [$state] 가능: ${WRITABLE_STATES.joinToString()}", "state"
            )
        }

        val date = DateUtils.parseDate(request.baseDate, "baseDate")
        val principal = authorizationService.requireMenu(screenId)

        repository.upsert(screenId, date, state, principal.userId)
        val saved = repository.findRecorded(screenId, date)
        return resolve(screenId, null, saved)
    }
}
