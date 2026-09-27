package com.dwje.api

import com.dwje.api.common.util.AiBusinessPeriod
import com.dwje.api.repository.AiFactRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.time.LocalDate

class AiFactRepositoryContractTest {
    private val jdbc = mock(NamedParameterJdbcTemplate::class.java)
    private val repository = AiFactRepository(jdbc)

    @Test fun `daily product defects uses bounded fixed ledger select full lot join apportionment and family`() {
        val period = AiBusinessPeriod(LocalDate.parse("2026-09-20"), LocalDate.parse("2026-09-22"))
        repository.dailyProductDefects("P1", period, 200)
        val call = mockingDetails(jdbc).invocations.single()
        val sql = call.arguments[0] as String
        val params = call.arguments[1] as MapSqlParameterSource
        assertTrue(sql.trimStart().startsWith("WITH labels AS"))
        assertTrue(sql.contains("lh.ins_date >= :from AND lh.ins_date < :toExclusive"))
        assertTrue(sql.contains("to_char(lh.ins_date + interval '16 hours', 'YYYY-MM-DD')"))
        for (key in listOf("plant_cd", "wc_cd", "lot_no", "serial_no")) assertTrue(sql.contains("dh.$key = l.$key"))
        assertTrue(sql.contains("QC_DEFECT_NONPROD"))
        assertTrue(sql.contains("ng_qty * type_qty / nullif(label_type_total, 0)"))
        assertTrue(sql.contains("ax.tb_prod_family"))
        assertTrue(sql.contains("ORDER BY c.work_date ASC, c.model_cd ASC"))
        assertTrue(sql.contains("LIMIT :limit"))
        assertEquals(LocalDate.parse("2026-09-19").atTime(8, 0), params.getValue("from"))
        assertEquals(LocalDate.parse("2026-09-22").atTime(8, 0), params.getValue("toExclusive"))
        assertEquals(200, params.getValue("limit"))
        assertThrows(IllegalArgumentException::class.java) { repository.dailyProductDefects("P1", period, 201) }
        assertThrows(IllegalArgumentException::class.java) {
            repository.dailyProductDefects("P1", AiBusinessPeriod(LocalDate.parse("2026-08-01"), LocalDate.parse("2026-09-01")), 200)
        }
    }

    @Test fun `product list and rate top twenty use bounded ordered selects with KST business boundaries`() {
        val period = AiBusinessPeriod(LocalDate.parse("2026-09-20"), LocalDate.parse("2026-09-22"))
        repository.producedProducts("P1", period)
        repository.topDefectRates("P1", period, 20)
        val calls = mockingDetails(jdbc).invocations.filter { it.method.name == "query" }.toList()
        assertEquals(2, calls.size)
        val products = calls[0].arguments[0] as String
        val rates = calls[1].arguments[0] as String
        val productParams = calls[0].arguments[1] as MapSqlParameterSource
        val rateParams = calls[1].arguments[1] as MapSqlParameterSource
        assertTrue(products.trimStart().startsWith("SELECT"))
        assertTrue(products.contains("LEFT JOIN ax.tb_prod_item_map"))
        assertTrue(products.contains("coalesce(p.model_cd, lh.item_cd)"))
        assertTrue(products.contains("ORDER BY model_cd ASC"))
        assertTrue(products.contains("LIMIT 100"))
        assertTrue(rates.contains("ORDER BY defect_rate DESC, ng_qty DESC, model_cd ASC"))
        assertTrue(rates.contains("LIMIT :limit"))
        assertEquals(20, rateParams.getValue("limit"))
        for (params in listOf(productParams, rateParams)) {
            assertEquals(LocalDate.parse("2026-09-19").atTime(8, 0), params.getValue("from"))
            assertEquals(LocalDate.parse("2026-09-22").atTime(8, 0), params.getValue("toExclusive"))
        }
        assertThrows(IllegalArgumentException::class.java) { repository.topDefectRates("P1", period, 21) }
    }

    @Test fun `producedProductsDaily groups by business day shift and orders by work_date asc`() {
        val period = AiBusinessPeriod(LocalDate.parse("2026-09-20"), LocalDate.parse("2026-09-22"))
        repository.producedProductsDaily("P1", period)
        val calls = mockingDetails(jdbc).invocations.filter { it.method.name == "query" }.toList()
        assertEquals(1, calls.size)
        val sql = calls[0].arguments[0] as String
        val params = calls[0].arguments[1] as MapSqlParameterSource
        assertTrue(sql.trimStart().startsWith("SELECT"))
        assertTrue(sql.contains("to_char(lh.ins_date + interval '16 hours', 'YYYY-MM-DD') AS work_date"))
        assertTrue(sql.contains("ORDER BY work_date ASC, model_cd ASC"))
        assertTrue(sql.contains("LIMIT 200"))
        assertEquals(LocalDate.parse("2026-09-19").atTime(8, 0), params.getValue("from"))
        assertEquals(LocalDate.parse("2026-09-22").atTime(8, 0), params.getValue("toExclusive"))
    }

    @Test fun `top defects uses fixed bounded SELECT and inclusive named business days`() {
        repository.topDefects("P1", AiBusinessPeriod(LocalDate.parse("2026-09-22"), LocalDate.parse("2026-09-23")), 10)
        val call = mockingDetails(jdbc).invocations.single()
        val sql = call.arguments[0] as String
        val params = call.arguments[1] as MapSqlParameterSource
        assertTrue(sql.trimStart().startsWith("WITH"))
        assertTrue(sql.contains("ORDER BY t.ng_qty DESC, t.defect_cd ASC"))
        assertTrue(sql.contains("LIMIT :limit"))
        assertTrue(sql.contains("lh.ins_date >= :from"))
        assertTrue(sql.contains("lh.ins_date <  :toExclusive"))
        assertEquals(LocalDate.parse("2026-09-21").atTime(8, 0), params.getValue("from"))
        assertEquals(LocalDate.parse("2026-09-23").atTime(8, 0), params.getValue("toExclusive"))
        assertEquals(10, params.getValue("limit"))
        assertThrows(IllegalArgumentException::class.java) {
            repository.topDefects("P1", AiBusinessPeriod(LocalDate.parse("2026-09-22"), LocalDate.parse("2026-09-23")), 11)
        }
    }

    @Test fun `document count uses allowed document function and Seoul registration boundaries`() {
        repository.documentCount(2025, "user", true, true)
        val call = mockingDetails(jdbc).invocations.single()
        val sql = call.arguments[0] as String
        val params = call.arguments[1] as MapSqlParameterSource
        assertTrue(sql.contains("count(DISTINCT d.doc_id)"))
        assertTrue(sql.contains("vec.fn_allowed_doc(:userId, false)"))
        assertTrue(sql.contains("d.doc_type_cd IN ('SCRAP', 'CLAIM')"))
        assertTrue(sql.contains("d.ins_date >= :fromTime AND d.ins_date < :toTime"))
        assertEquals("2025-01-01T00:00+09:00", params.getValue("fromTime").toString())
        assertEquals("2026-01-01T00:00+09:00", params.getValue("toTime").toString())
    }
}
