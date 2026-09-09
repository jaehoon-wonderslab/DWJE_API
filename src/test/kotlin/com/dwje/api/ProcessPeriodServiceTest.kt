package com.dwje.api

import com.dwje.api.common.exception.MenuAccessDeniedException
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.common.util.*
import com.dwje.api.config.AppProperties
import com.dwje.api.repository.DashboardProcessRepository
import com.dwje.api.repository.MetricStandardRepository
import com.dwje.api.service.AuthorizationService
import com.dwje.api.service.DashboardAiService
import com.dwje.api.service.DashboardProcessService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.math.BigDecimal

class ProcessPeriodServiceTest {
    private val repository = mock(DashboardProcessRepository::class.java)
    private val authorization = mock(AuthorizationService::class.java)
    private val service = DashboardProcessService(repository, mock(DashboardAiService::class.java),
        mock(MetricStandardRepository::class.java), authorization, AppProperties())

    @Test fun `menu denied before source access`() {
        `when`(authorization.guard(MenuId.DASH_PROC)).thenThrow(MenuAccessDeniedException(MenuId.DASH_PROC))
        assertThrows(MenuAccessDeniedException::class.java) {
            service.getPeriod("2026-08-01", "2026-08-02", "day", null, null)
        }
        verifyNoInteractions(repository)
    }

    @Test fun `quantity denied across summary all lists and generated empty bucket`() {
        val principal = UserPrincipal("test", "test", 1, "test", "test", "STAFF", "PL01", false,
            menuPerms = setOf(MenuId.DASH_PROC), dataPerms = setOf(DataField.YIELD))
        `when`(authorization.guard(MenuId.DASH_PROC)).thenReturn(principal to MaskingSupport(principal))
        val row = ProcessPeriodRow.of(BigDecimal("90"), BigDecimal.TEN)
        `when`(repository.findPeriod("PL01", ProcessPeriod.parse("2026-08-01", "2026-08-02", "day"), null, listOf("P1", "P2")))
            .thenReturn(listOf("summary" to row, "periods" to row.copy(period = "2026-08-01"),
                "products" to row.copy(code = "P1", productNm = "Product"),
                "processes" to row.copy(processId = "W110", process = "Press")))
        val (data, mask) = service.getPeriod("2026-08-01", "2026-08-02", "day", listOf(" P1,P2", "P1"), null)
        val rows = listOf(data["summary"] as ProcessPeriodRow) + listOf("periods", "products", "processes").flatMap {
            (data[it] as List<*>).filterIsInstance<ProcessPeriodRow>()
        }
        assertEquals(5, rows.size)
        rows.forEach { assertNull(it.qty); assertNull(it.okQty); assertNull(it.ngQty) }
        assertEquals(90.0, rows.first().yieldRate)
        assertNull(rows.single { it.period == "2026-08-02" }.yieldRate)
        assertEquals(listOf(DataField.QTY), mask.maskedKeys())
    }
}
