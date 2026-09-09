package com.dwje.api

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.time.LocalDate
import com.dwje.api.common.exception.ResourceNotFoundException
import java.time.YearMonth

/**
 * 불량 관련 조회 SQL 이 실제 DB 에서 실행되는지 확인한다.
 *
 * 불량률 기준을 label_hist 로 통일하면서 16곳의 SQL 을 수정했다.
 * 문자열로 조립되는 SQL 은 컴파일러가 검증해 주지 않으므로, 실제 DB 에 던져 본다.
 */
@SpringBootTest
@ActiveProfiles("local")
class DefectSqlSmokeTest {

    @Autowired lateinit var quality: com.dwje.api.repository.QualityRepository
    @Autowired lateinit var kpi: com.dwje.api.repository.DashboardKpiRepository
    @Autowired lateinit var ai: com.dwje.api.repository.DashboardAiRepository
    @Autowired lateinit var process: com.dwje.api.repository.DashboardProcessRepository
    @Autowired lateinit var report: com.dwje.api.repository.ReportRepository
    @Autowired lateinit var common: com.dwje.api.repository.CommonMasterRepository
    @Autowired lateinit var jdbc: NamedParameterJdbcTemplate

    private val plant = "PL01"
    private val day = LocalDate.of(2026, 8, 28)
    private val from = LocalDate.of(2026, 8, 27)

    @Test
    fun `불량 관련 조회 SQL 이 전부 실행된다`() {
        quality.findDefectSummary(plant, from, day, null, null)
        quality.findDefectSummary(plant, from, day, "W110", null)
        quality.findDefectSummary(plant, from, day, null, "DF017")
        quality.findDefectByType(plant, from, day, null)
        quality.findDefectByLine(plant, from, day, null, 5)
        quality.findEquipmentDefectStats(plant, null, 24)
        quality.findRiskLots(plant, 7, 5)
        quality.findDefectTypeShift(plant, day, 4)
        kpi.findDefectDistribution(plant, YearMonth.of(2026, 8))
        kpi.findDefectTypeTrend(plant, YearMonth.of(2026, 6), YearMonth.of(2026, 8), 5)
        ai.findDefectTrendByType(plant, day, 3, null, 5)
        ai.findUntypedDefectTrend(plant, com.dwje.api.common.util.TimeWindow.ofDay(day), 3, null)
        ai.findSlotLabelTotals(plant, com.dwje.api.common.util.TimeWindow(day.atStartOfDay(), day.atTime(2, 0)))
        ai.findSlotDefectDetails(plant, com.dwje.api.common.util.TimeWindow(day.atStartOfDay(), day.atTime(2, 0)))
        ai.findDefectComposition(plant, day, null)
        process.findDefectComposition(plant, day, null, emptyList())
        report.findLossBreakdown(plant, YearMonth.of(2026, 8), null, null)
    }

    /**
     * 공정 코드 검증 — 없는 코드는 조용히 0건이 아니라 404 로 알려야 한다.
     *
     * 화면이 목업 잔재인 `Press` 를 보내 전 위젯이 0 으로 보였는데,
     * 응답이 정상 200 + 0건이라 원인을 찾는 데 오래 걸렸다.
     */
    @Test
    fun `없는 공정 코드는 404 로 알린다`() {
        val ex = org.junit.jupiter.api.assertThrows<ResourceNotFoundException> {
            process.findProcessInfo(plant, "Press")
                ?: throw ResourceNotFoundException("등록되지 않은 공정 코드입니다. [processId=Press]")
        }
        org.junit.jupiter.api.Assertions.assertTrue(ex.message!!.contains("Press"))

        // 실제 공정 코드는 조회된다
        org.junit.jupiter.api.Assertions.assertNotNull(process.findProcessInfo(plant, "W110"))
    }

    /** 공정별 실적 보유 구간 — 전사 toDate 에 실적이 없는 공정이 있다 */
    @Test
    fun `공정별 실적 보유 구간을 조회한다`() {
        val all = common.findProductionDateRange(plant, null)
        val w110 = common.findProductionDateRange(plant, "W110")
        org.junit.jupiter.api.Assertions.assertNotNull(all["toDate"])
        org.junit.jupiter.api.Assertions.assertNotNull(w110["toDate"])
        println("전사 = ${all["fromDate"]} ~ ${all["toDate"]},  W110 = ${w110["fromDate"]} ~ ${w110["toDate"]}")
    }
}
