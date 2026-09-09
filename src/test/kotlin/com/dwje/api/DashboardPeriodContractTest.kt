package com.dwje.api

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

/**
 * AI 대시보드 구간 조회 규약 테스트
 *
 * ## 지켜야 하는 것
 * 한 화면의 조회는 **같은 구간**을 봐야 한다. 일부만 `from`·`to` 를 받으면
 * 화면에 하루치와 석 달치가 섞여 나온다 (2026-09-06 웹 세션 신고: 여덟 개 중
 * 일곱 개가 구간을 무시하고 하루만 집계하고 있었다).
 *
 * 눈으로 지키지 않고 빌드에서 막는다.
 */
class DashboardPeriodContractTest {

    companion object {
        /** 구간을 받아야 하는 조회 — 경로와 그 함수가 반드시 가져야 할 파라미터 */
        private val PERIOD_ENDPOINTS = listOf(
            "/summary",
            "/defect-trend",
            "/defect-trend/slot-details",
            "/line-production",
            "/quality-index",
            "/defect-composition",
            "/process-yield",
            "/plan-vs-actual",
            "/equipment-uptime-heatmap",
            "/lines",
            "/line-products",
            "/briefing",
            "/cause-prescription"
        )
    }

    @Test
    @DisplayName("AI 대시보드 집계 조회는 모두 from·to 를 받아야 한다")
    fun everyAggregationTakesPeriod() {
        val source = controllerSource()
        val missing = PERIOD_ENDPOINTS.filterNot { path -> signatureOf(source, path).hasPeriodParams() }

        check(missing.isEmpty()) {
            "구간(from·to)을 받지 않는 조회가 있다: $missing\n" +
                "화면 한 곳에서 기간이 갈린다. 서비스·컨트롤러 양쪽에 from·to 를 붙여야 한다."
        }
    }

    @Test
    @DisplayName("검사기 자체가 동작하는지 확인 — 구간 없는 서명을 실제로 잡아내야 한다")
    fun detectorActuallyDetects() {
        val sample = """
            @GetMapping("/no-period")
            fun noPeriod(
                @RequestParam(required = false) date: String?
            ): ApiResponse<Map<String, Any?>> {
        """.trimIndent()

        check(!signatureOf(sample, "/no-period").hasPeriodParams()) {
            "구간 없는 서명을 통과시켰다 — 검사기가 무력하다"
        }
    }

    @Test
    @DisplayName("검사 대상 경로가 실제로 컨트롤러에 있어야 한다")
    fun endpointsExist() {
        val source = controllerSource()
        val gone = PERIOD_ENDPOINTS.filterNot { source.contains("@GetMapping(\"$it\")") }

        check(gone.isEmpty()) { "컨트롤러에 없는 경로를 검사하고 있다: $gone" }
    }

    /** `@GetMapping("<path>")` 다음에 오는 함수 서명(여는 중괄호 또는 `=` 까지) */
    private fun signatureOf(source: String, path: String): String {
        val marker = "@GetMapping(\"$path\")"
        val start = source.indexOf(marker)
        if (start < 0) return ""

        val body = source.substring(start + marker.length)
        val end = body.indexOf("): ")
        return if (end < 0) body else body.substring(0, end)
    }

    private fun String.hasPeriodParams(): Boolean =
        contains("from: String?") && contains("to: String?")

    private fun controllerSource(): String =
        File(projectDir(), "src/main/kotlin/com/dwje/api/controller/DashboardAiController.kt")
            .also { check(it.exists()) { "컨트롤러를 찾지 못했다: ${it.absolutePath}" } }
            .readText()

    private fun projectDir(): File {
        var dir = File(System.getProperty("user.dir"))
        while (!File(dir, "settings.gradle.kts").exists() && dir.parentFile != null) {
            dir = dir.parentFile
        }
        return dir
    }
}
