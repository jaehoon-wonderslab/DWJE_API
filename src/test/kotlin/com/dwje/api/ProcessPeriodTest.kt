package com.dwje.api

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.common.util.*
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ProcessPeriodTest {
    /**
     * 구간은 `시작일 08:00` 에 시작하므로 시작일 자신은 업무일로 들어오지 않는다.
     * 2025-12-31 ~ 2026-01-06 이 덮는 업무일은 2026-01-01 ~ 01-06 이고,
     * 그래서 월 버킷에 2025-12 가 없다. (2026-09-16 업무일 기준 적용)
     */
    @Test fun `buckets use Mondays across year boundary and do not expand source range`() {
        val range = ProcessPeriod.parse("2025-12-31", "2026-01-06", "week")
        assertEquals(listOf("2025-12-29", "2026-01-05"), range.buckets())
        assertEquals("2025-12-31", range.from.toString())
        assertEquals(listOf("2026-01-01"), ProcessPeriod.parse("2025-12-31", "2026-01-06", "month").buckets())
    }

    @Test fun `strict dates units and maximum interval`() {
        for ((from, to, unit) in listOf(
            Triple("2026-02-30", "2026-03-01", "day"),
            Triple("2026-1-01", "2026-03-01", "day"),
            Triple("2026-03-01", "2026-02-28", "day"),
            Triple("2026-01-01", "2026-04-04", "day"),
            Triple("2026-01-01", "2026-01-02", "hour")
        )) assertThrows(InvalidParameterException::class.java) { ProcessPeriod.parse(from, to, unit) }
        // 01-01 ~ 04-03 은 93일이지만 구간은 01-01 08:00 에 시작한다 — 업무일은 01-02 ~ 04-03 의 92일.
        assertEquals(92, ProcessPeriod.parse("2026-01-01", "2026-04-03", "day").buckets().size)
    }

    @Test fun `ratios use original decimal totals and unknown is not zero`() {
        val row = ProcessPeriodRow.of(BigDecimal("97.5"), BigDecimal("2.5"))
        assertEquals(BigDecimal("100.0"), row.qty)
        assertEquals(2.5, row.defectRate)
        assertEquals(97.5, row.yieldRate)
        val unknown = ProcessPeriodRow.of(BigDecimal.TEN, null)
        assertNull(unknown.qty)
        assertNull(unknown.ngQty)
        assertNull(unknown.yieldRate)
        assertEquals(BigDecimal.TEN, unknown.okQty)
        assertEquals(BigDecimal.ZERO, ProcessPeriodRow.empty().qty)
        assertNull(ProcessPeriodRow.empty().defectRate)
    }

    @Test fun `masking preserves explicit null metrics under application serialization defaults`() {
        val principal = UserPrincipal("test", "test", 1, "test", "test", "STAFF", "PL01", false,
            dataPerms = setOf(DataField.YIELD))
        val mask = MaskingSupport(principal)
        val row = ProcessPeriodRow.of(BigDecimal("90"), BigDecimal.TEN).copy(code = "P1", productNm = "Product").masked(mask)
        val mapper = jacksonObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL)
        val json = mapper.readTree(mapper.writeValueAsString(row))
        for (key in listOf("qty", "okQty", "ngQty")) {
            assertTrue(json.has(key))
            assertTrue(json[key].isNull)
        }
        assertEquals(90.0, json["yieldRate"].asDouble())
        assertFalse(json.has("period"))
        assertEquals(listOf(DataField.QTY), mask.maskedKeys())
        val noYield = ProcessPeriodRow.empty().masked(MaskingSupport(principal.copy(dataPerms = setOf(DataField.QTY))))
        assertNull(noYield.yieldRate)
        assertNull(noYield.defectRate)
    }
}
