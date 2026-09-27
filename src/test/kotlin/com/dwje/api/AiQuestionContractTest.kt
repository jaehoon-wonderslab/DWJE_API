package com.dwje.api

import com.dwje.api.common.util.AiBusinessPeriod
import com.dwje.api.service.AiQuestionParser
import com.dwje.api.service.AiQuestionPrivacy
import com.dwje.api.service.AiResponseSanitizer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate

class AiQuestionContractTest {
    private val latest = LocalDate.parse("2026-09-24")

    @Test fun `two named business days include both boundary dates`() {
        val top = AiQuestionParser.defectTop("2026-09-22~23 양일 불량 top 10", latest)!!
        assertEquals(10, top.limit)
        assertEquals(LocalDate.parse("2026-09-22"), top.period.from)
        assertEquals(LocalDate.parse("2026-09-23"), top.period.to)
        assertEquals("2026-09-21T08:00", top.period.startInclusive.toString())
        assertEquals("2026-09-23T08:00", top.period.endExclusive.toString())
    }

    @Test fun `user month day examples resolve both days in injected 2026`() {
        val today = LocalDate.parse("2026-09-24")
        listOf("9-22 9-23 일 불량 top 10", "9월 22일 ~ 23일 불량 top 10",
            "9월 22일 ~ 9월 23일 불량 top 10").forEach { question ->
            val top = AiQuestionParser.defectTop(question, latest, today)!!
            assertEquals(LocalDate.parse("2026-09-22"), top.period.from, question)
            assertEquals(LocalDate.parse("2026-09-23"), top.period.to, question)
            assertEquals("2026-09-21T08:00", top.period.startInclusive.toString(), question)
            assertEquals("2026-09-23T08:00", top.period.endExclusive.toString(), question)
        }
    }

    @Test fun `product list and defect rate top twenty use explicit business date ranges`() {
        val today = LocalDate.parse("2026-09-24")
        val products = AiQuestionParser.productList("9월 20일 부터 9월 22일까지 생산된 제품 목록 출력", today)!!
        assertEquals(LocalDate.parse("2026-09-20"), products.period.from)
        assertEquals(LocalDate.parse("2026-09-22"), products.period.to)
        assertEquals("2026-09-19T08:00", products.period.startInclusive.toString())
        assertEquals("2026-09-22T08:00", products.period.endExclusive.toString())

        val rates = AiQuestionParser.defectRateTop("09-20 부터 09-22 까지 불량률 top 20", today)!!
        assertEquals(20, rates.limit)
        assertEquals(products.period, rates.period)
        assertNull(AiQuestionParser.defectRateTop("09-20 부터 09-22 까지 불량률 top 21", today))
        assertNull(AiQuestionParser.productList("9월 31일 부터 10월 2일까지 생산된 제품 목록 출력", today))
    }

    @Test fun `yearless dates use most recent valid year relative to injected Seoul today`() {
        val question = "9-22 9-23 일 불량 top 10"
        val in2025 = AiQuestionParser.defectTop(question, latest, LocalDate.parse("2025-09-24"))!!
        assertEquals(LocalDate.parse("2025-09-22"), in2025.period.from)
        assertEquals(LocalDate.parse("2025-09-23"), in2025.period.to)
        val beforeThisYearDate = AiQuestionParser.defectTop(question, latest, LocalDate.parse("2026-09-20"))!!
        assertEquals(LocalDate.parse("2025-09-22"), beforeThisYearDate.period.from)
        assertEquals(LocalDate.parse("2025-09-23"), beforeThisYearDate.period.to)
    }

    @Test fun `yearless December to January range crosses year boundary`() {
        val top = AiQuestionParser.defectTop("12-31 1-1 일 불량 top 2", latest,
            LocalDate.parse("2026-01-02"))!!
        assertEquals(LocalDate.parse("2025-12-31"), top.period.from)
        assertEquals(LocalDate.parse("2026-01-01"), top.period.to)
        assertEquals("2025-12-30T08:00", top.period.startInclusive.toString())
        assertEquals("2026-01-01T08:00", top.period.endExclusive.toString())
    }

    @Test fun `recent two and whole September are different periods`() {
        val recent = AiQuestionParser.defectTop("최근 불량 top 2", latest)!!
        val month = AiQuestionParser.defectTop("9월 전체 불량 top 10", latest)!!
        assertEquals(2, recent.limit)
        assertEquals(latest, recent.period.from)
        assertEquals(LocalDate.parse("2026-09-01"), month.period.from)
        assertEquals(LocalDate.parse("2026-09-30"), month.period.to)
        assertEquals("2026-08-31T08:00", month.period.startInclusive.toString())
    }

    @Test fun `explicit end date is included and invalid limits are rejected`() {
        val day = AiQuestionParser.defectTop("2026-09-22 불량 top 1", latest)!!
        assertEquals("2026-09-21T08:00", day.period.startInclusive.toString())
        assertEquals("2026-09-22T08:00", day.period.endExclusive.toString())
        assertNull(AiQuestionParser.defectTop("불량 top 11", latest))
        assertThrows(IllegalArgumentException::class.java) {
            AiBusinessPeriod(LocalDate.parse("2026-09-23"), LocalDate.parse("2026-09-22"))
        }
    }

    @Test fun `document creation uses registration time and document period uses date`() {
        val created = AiQuestionParser.documentCount("작년에 생성된 불량 보고서 몇 개?", latest)!!
        assertEquals(listOf(2025), created.years)
        assertTrue(created.byRegistration)
        assertTrue(created.defectReports)
        assertFalse(AiQuestionParser.documentCount("올해 문서가 다루는 기간 몇 건?", latest)!!.byRegistration)
    }

    @Test fun `internal SQL and schema identifiers never become public text`() {
        assertEquals(AiResponseSanitizer.HIDDEN, AiResponseSanitizer.publicText("SELECT * FROM mes.tb_pop_label_hist"))
        assertEquals(AiResponseSanitizer.HIDDEN, AiResponseSanitizer.publicText("vec.tb_doc의 값"))
        assertEquals(AiResponseSanitizer.HIDDEN, AiResponseSanitizer.publicText("defect_cd는 비공개"))
        assertEquals("안녕하세요", AiResponseSanitizer.publicText("안녕하세요"))
    }

    @Test fun `stored questions redact credentials and SQL conditions`() {
        val safe = AiQuestionPrivacy.forStorage("불량률 top 20 password=secret-value Bearer token-value")
        assertFalse(safe.contains("secret-value"))
        assertFalse(safe.contains("token-value"))
        assertTrue(safe.contains("불량률 top 20"))
        assertEquals(AiResponseSanitizer.HIDDEN,
            AiQuestionPrivacy.forStorage("SELECT * FROM mes.tb_pop_label_hist WHERE item_cd='private'"))
    }
}
