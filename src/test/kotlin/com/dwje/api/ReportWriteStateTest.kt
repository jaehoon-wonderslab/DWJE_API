package com.dwje.api

import com.dwje.api.common.util.MenuId
import com.dwje.api.repository.DecisionTrace
import com.dwje.api.repository.WriteStateRow
import com.dwje.api.service.ReportWriteStateService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 보고서 작성 상태 판정 규약 테스트
 *
 * 화면 띠의 상태는 `max(파생, 기록)` 이고 같으면 기록이다. 파생은 아침회의 결과 행에서
 * DRAFT 까지만 나온다. 이 두 규칙이 어긋나면 "제출했는데 작성 중으로 보인다" 같은 오해가 생긴다.
 */
class ReportWriteStateTest {

    private fun row(menuId: String, state: String, by: String = "10002") =
        WriteStateRow(menuId, state, "2026-09-09 09:00:00", by, "박생산")

    @Test
    @DisplayName("파생도 기록도 없으면 NONE 이고 부가 정보는 전부 null 이다")
    fun noneWhenNothing() {
        val r = ReportWriteStateService.resolve(MenuId.RPT_SCRAP, null, null)
        assertEquals("NONE", r["state"])
        assertNull(r["source"])
        assertNull(r["updatedAt"])
        assertNull(r["updatedBy"])
        assertNull(r["updatedByName"])
    }

    @Test
    @DisplayName("기록이 더 높으면 기록을, 파생만 있으면 파생을 고른다")
    fun higherWins() {
        val derived = row(MenuId.PROD_DAILY, "DRAFT")
        val recorded = row(MenuId.PROD_DAILY, "SUBMITTED", by = "10000")

        val withBoth = ReportWriteStateService.resolve(MenuId.PROD_DAILY, derived, recorded)
        assertEquals("SUBMITTED", withBoth["state"])
        assertEquals("RECORDED", withBoth["source"])
        assertEquals("10000", withBoth["updatedBy"])

        val onlyDerived = ReportWriteStateService.resolve(MenuId.PROD_DAILY, derived, null)
        assertEquals("DRAFT", onlyDerived["state"])
        assertEquals("DERIVED", onlyDerived["source"])
    }

    @Test
    @DisplayName("파생과 기록이 같은 등급이면 기록을 우선한다 — 누가 눌렀는지가 더 정확하다")
    fun tieGoesToRecorded() {
        val derived = row(MenuId.PROD_DAILY, "DRAFT", by = "10002")
        val recorded = row(MenuId.PROD_DAILY, "DRAFT", by = "10000")
        val r = ReportWriteStateService.resolve(MenuId.PROD_DAILY, derived, recorded)
        assertEquals("RECORDED", r["source"])
        assertEquals("10000", r["updatedBy"])
    }

    @Test
    @DisplayName("행이 있으면 일일 보고는 DRAFT, 판정이 적힌 행이 있어야 PRESS 아침회의도 DRAFT 다")
    fun deriveFromDecisionRows() {
        val none = ReportWriteStateService.derive(emptyList())
        assertEquals(emptyMap<String, WriteStateRow>(), none)

        val targetOnly = ReportWriteStateService.derive(
            listOf(DecisionTrace(hasDecision = false, "2026-09-09 08:00:00", "10002", "박생산"))
        )
        assertEquals("DRAFT", targetOnly[MenuId.PROD_DAILY]?.state)
        assertNull(targetOnly[MenuId.RPT_PRESS_MORNING])

        // 최근 갱신 순으로 온다 — 첫 행이 마지막으로 건드린 사람이다.
        val withDecision = ReportWriteStateService.derive(
            listOf(
                DecisionTrace(hasDecision = false, "2026-09-09 09:30:00", "10003", "이제조"),
                DecisionTrace(hasDecision = true, "2026-09-09 08:00:00", "10002", "박생산")
            )
        )
        assertEquals("10003", withDecision[MenuId.PROD_DAILY]?.updatedBy)
        assertEquals("10002", withDecision[MenuId.RPT_PRESS_MORNING]?.updatedBy)
        assertEquals("DRAFT", withDecision[MenuId.RPT_PRESS_MORNING]?.state)
    }
}
