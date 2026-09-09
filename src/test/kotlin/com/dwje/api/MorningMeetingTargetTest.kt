package com.dwje.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 아침회의 자료 일목표 계약 테스트
 *
 * 이 테스트가 고정하는 세 가지
 *
 * 1. **목표 없음은 null 이다** — 0 으로 채우면 달성률 0 이 되어 전 행이 위험으로 뜬다.
 *    아침회의에서 "다 위험" 이라고 띄우면 자료로 쓸 수 없다. 고치기 전 상태가 그랬다:
 *    지표(`tb_met_metric_value`)가 0행인데 `coalesce(..., 0)` 로 0 을 만들고 있었다.
 *
 * 2. **달성률은 목표가 있는 공정만으로 낸다** — 목표는 일부 공정만 있는데 실적은 전
 *    공정을 더하면 분모와 짝이 맞지 않는다. 실측으로 목표 2,000,000 에 전 공정 실적
 *    3,992,507 을 나눠 199.63% 가 나왔다. 옳은 값은 112.78% 다.
 *
 * 3. **기본 범위** — 공정을 지정하지 않으면 그 보고서의 범위만 집계한다.
 *    PRESS 자료에 도금·코팅이 섞여 올라오면 안 된다.
 */
class MorningMeetingTargetTest {

    private val service = File("src/main/kotlin/com/dwje/api/service/ReportService.kt").readText()
    private val repository = File("src/main/kotlin/com/dwje/api/repository/ReportRepository.kt").readText()

    private fun morningRows(): String =
        Regex("""fun findMorningMeetingRows\(.*?\n    \}""", RegexOption.DOT_MATCHES_ALL)
            .find(repository)?.value
            ?: error("findMorningMeetingRows 를 찾지 못했다 — 이름이 바뀌었으면 이 테스트도 고쳐야 한다")

    @Test
    @DisplayName("1. 일목표는 마스터에서 온다 — 0행인 지표로 되돌아가면 실패")
    fun targetComesFromMaster() {
        val sql = morningRows()

        assertTrue(
            sql.contains("ax.tb_prod_day_target"),
            "일목표는 제품·공정별 마스터에서 읽어야 한다"
        )
        assertTrue(
            !Regex("""FROM ax\.tb_met_metric_value""").containsMatchIn(sql),
            "지표(tb_met_metric_value)로 되돌아가면 안 된다 — 0행이라 목표가 전부 0 이 된다"
        )
        assertTrue(
            sql.contains("apply_from <= :baseDate"),
            "적용일 구간을 지켜야 한다 — 미래 목표를 당겨 쓰면 안 된다"
        )
        assertTrue(
            Regex("""DISTINCT ON \(product, wc_cd\)[\s\S]{0,300}apply_from DESC""").containsMatchIn(sql),
            "제품·공정마다 그 날짜 이하의 최신 한 건만 골라야 한다"
        )
    }

    @Test
    @DisplayName("2. 목표가 없으면 0 이 아니라 null 이다 — coalesce 로 되돌아가면 실패")
    fun missingTargetStaysNull() {
        val sql = morningRows()

        assertTrue(
            Regex("""dt\.target_qty\s+AS day_target""").containsMatchIn(sql),
            "목표를 coalesce 로 0 으로 만들면 '목표 없음' 과 '목표가 0' 이 구분되지 않는다. " +
                "그러면 달성률 0 이 되어 전 행이 위험으로 뜬다"
        )
        assertTrue(
            !Regex("""coalesce\(dt\.target_qty, 0\)""").containsMatchIn(sql),
            "coalesce(dt.target_qty, 0) 으로 되돌리면 안 된다"
        )
        // 서비스도 목표가 없을 때 상태를 내지 않아야 한다.
        assertTrue(
            Regex("""val state = rate\?\.let \{ judgeSignal\(it\) \}""").containsMatchIn(service),
            "달성률이 없으면 상태도 내지 않아야 한다. judgeSignal 을 무조건 부르면 " +
                "목표 없는 공정이 CRIT 으로 나온다"
        )
    }

    @Test
    @DisplayName("3. 합계 달성률은 목표가 있는 공정만으로 낸다 — 분모와 짝을 맞춘다")
    fun totalRateMatchesDenominator() {
        assertTrue(
            service.contains("val withTarget = raw.filter { it[\"dayTarget\"] != null }"),
            "달성률은 목표가 있는 공정만 골라 계산해야 한다"
        )
        assertTrue(
            Regex("""ratedActual\s*=\s*withTarget\.sumOf""").containsMatchIn(service),
            "달성률의 분자도 목표가 있는 공정의 실적만 더해야 한다. 전 공정 실적을 쓰면 " +
                "달성률이 부풀어 오른다 (실측 199.63% vs 옳은 값 112.78%)"
        )
        assertTrue(
            Regex("""ratedActual \* 10000\.0 / totalTarget""").containsMatchIn(service),
            "달성률은 ratedActual / totalTarget 이어야 한다"
        )
        assertTrue(
            service.contains("\"rateProcessCnt\""),
            "달성률이 몇 개 공정을 근거로 나온 값인지 화면이 알아야 한다"
        )
    }

    @Test
    @DisplayName("4. 공정 미지정 시 보고서별 기본 범위를 쓴다 — 전 공정이 섞이면 안 된다")
    fun defaultScopePerReport() {
        assertTrue(
            Regex("""RPT_PLATING_MORNING\s*->\s*appProperties\.platingWorkcenters""")
                .containsMatchIn(service),
            "Plating·Coating 자료는 도금·코팅 작업장만 집계해야 한다"
        )
        assertTrue(
            Regex("""else\s*->\s*appProperties\.pressWorkcenters""").containsMatchIn(service),
            "PRESS 자료는 프레스 작업장만 집계해야 한다"
        )
        assertTrue(
            service.contains("\"processCds\" to scope"),
            "서버가 실제로 어느 작업장을 집계했는지 화면이 확인할 수 있어야 한다"
        )
    }

    @Test
    @DisplayName("5. 주간목표는 그 보고서의 주간 창에 맞춘다 — 일일 보고와 규칙이 다르다")
    fun weekTargetUsesOwnWindow() {
        assertTrue(
            Regex("""WEEK_WINDOW_DAYS\s*=\s*7""").containsMatchIn(service),
            "아침회의 자료의 주간 창은 기준일 포함 7일이다"
        )
        assertTrue(
            Regex("""dayTarget\?\.let \{ it \* WEEK_WINDOW_DAYS \}""").containsMatchIn(service),
            "주간목표는 일목표 x 주간 일수다. 목표가 없으면 낼 수 없다"
        )
        assertTrue(
            service.contains("\"weekDays\" to WEEK_WINDOW_DAYS"),
            "화면이 주간 일수를 짐작하지 않도록 함께 내려야 한다 — " +
                "일일 생산현황 보고는 그 주 월요일부터 세므로 규칙이 다르다"
        )
        // 두 보고서가 같은 상수를 쓰지 않는다는 것을 못 박는다.
        val daily = File("src/main/kotlin/com/dwje/api/common/util/DailyReportPeriod.kt").readText()
        assertEquals(
            1, Regex("""fun weekDays\(""").findAll(daily).count(),
            "일일 생산현황 보고의 주간 일수는 DailyReportPeriod.weekDays 가 계산한다 — " +
                "아침회의 자료의 고정 7일과 섞지 말 것"
        )
    }
}
