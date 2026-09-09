package com.dwje.api

import com.dwje.api.common.util.ProductionMonitorPeriod
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.time.LocalDate

class ProductionMonitorPeriodTest {

    @Test
    @DisplayName("2026-08-30 생산 모니터링은 실적 조회와 같이 그 날 전체를 집계한다")
    fun targetDateUsesResultDayWindow() {
        val period = ProductionMonitorPeriod.of(LocalDate.of(2026, 8, 30))

        assertEquals("2026-08-30T00:00", period.from.toString())
        assertEquals("2026-08-31T00:00", period.toExclusive.toString())
    }

    @Test
    @DisplayName("요약과 설비 API는 targetDate를 받고 SQL은 명시적 시작·종료 경계를 쓴다")
    fun endpointsAndRepositoryUseTargetDateWindow() {
        val controller = source("controller/ProductionController.kt")
        val repository = source("repository/ProductionRepository.kt")
        val service = source("service/ProductionService.kt")

        listOf("/monitor/summary", "/monitor/equipments").forEach { path ->
            val signature = signatureOf(controller, path)
            check("targetDate: String?" in signature) { "$path 에 targetDate 파라미터가 없습니다." }
        }
        check("lh.ins_date >= :from AND lh.ins_date < :toExclusive" in repository)
        check("mv.measured_at >= :from AND mv.measured_at < :toExclusive" in repository)
        check("totalThroughput" in repository) { "기준일 실적 총량을 응답에 제공해야 합니다." }
        check("DEFAULT_MONITOR_PRESS_PROCESS = \"W120\"" in service)
        check("DEFAULT_MONITOR_PRESS_PREFIX = \"MT\"" in service)
    }

    private fun signatureOf(source: String, path: String): String {
        val start = source.indexOf("@GetMapping(\"$path\")")
        check(start >= 0) { "경로를 찾을 수 없습니다: $path" }
        val following = source.substring(start)
        return following.substring(0, following.indexOf("): "))
    }

    private fun source(relative: String): String {
        var root = File(System.getProperty("user.dir"))
        while (!File(root, "settings.gradle.kts").exists() && root.parentFile != null) root = root.parentFile
        return File(root, "src/main/kotlin/com/dwje/api/$relative").readText()
    }
}
