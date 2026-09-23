package com.dwje.api

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.common.util.DataField
import com.dwje.api.common.util.MaskingSupport
import com.dwje.api.common.util.MenuId
import com.dwje.api.common.util.TimeWindow
import com.dwje.api.config.AppProperties
import com.dwje.api.controller.DashboardAiController
import com.dwje.api.repository.DashboardAiRepository
import com.dwje.api.repository.DayTargetRepository
import com.dwje.api.repository.DocEvidenceRepository
import com.dwje.api.repository.MetricStandardRepository
import com.dwje.api.service.AiEvidenceVerifier
import com.dwje.api.service.AuthorizationService
import com.dwje.api.service.DashboardAiService
import com.dwje.api.service.DataFieldService
import com.dwje.api.service.SllmClient
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.LocalDate

/**
 * AI 대시보드 불량률 추이 — 유형 계열 범위(`topN`) 규약
 *
 * ## 지켜야 하는 것
 * 같은 화면의 불량 유형 구성(defect-composition)은 전 유형을 보이는데, 추이는 상위 2종만
 * 내려 유형 목록이 어긋났다. 화면이 `topN=all` 로 요구하면 구간에서 발생한 **전 유형**을
 * 내리고, 미지정이면 기존처럼 상위 2종을 유지해 기존 호출을 깨지 않는다.
 *
 * 계열 형태는 바뀌지 않는다 — `series[0]` 은 전체 불량률, `series[1..]` 는 유형별 수량(EA)이며
 * 모두 `labels` 와 같은 길이·순서다.
 */
class DashboardAiDefectTrendTest {

    private val repository = mock(DashboardAiRepository::class.java)
    private val metricStandard = mock(MetricStandardRepository::class.java)
    private val authorization = mock(AuthorizationService::class.java)
    private val service = DashboardAiService(
        repository,
        metricStandard,
        mock(DayTargetRepository::class.java),
        mock(AiEvidenceVerifier::class.java),
        mock(SllmClient::class.java),
        mock(DocEvidenceRepository::class.java),
        ObjectMapper(),
        authorization,
        mock(DataFieldService::class.java),
        AppProperties()
    )

    private val day = LocalDate.of(2026, 8, 28)
    private val window = TimeWindow.ofDay(day)
    private val labels = listOf("08:00", "10:00", "12:00")

    /** 유형별 슬롯 수량 — 구간 합계는 A(15) > B(7) > D(2) > C(1) */
    private val typeQty = mapOf(
        "A" to listOf(10L, 5L, 0L),
        "B" to listOf(0L, 3L, 4L),
        "C" to listOf(1L, 0L, 0L),
        "D" to listOf(0L, 0L, 2L)
    )

    private fun grantAll() {
        val principal = UserPrincipal(
            "test", "test", 1, "test", "test", "STAFF", "PL01", false,
            menuPerms = setOf(MenuId.DASH_AI), dataPerms = setOf(DataField.YIELD, DataField.QTY)
        )
        `when`(authorization.guard(MenuId.DASH_AI)).thenReturn(principal to MaskingSupport(principal))
    }

    private fun stubRows() {
        `when`(repository.findDefectTrend("PL01", window, 2, null)).thenReturn(
            labels.mapIndexed { i, slot ->
                mapOf("slot" to slot, "totalQty" to 100L, "ngQty" to (10L + i), "defectRate" to (10.0 + i))
            }
        )
        // 0 인 칸은 행이 없다 — 계열이 labels 길이로 채워지는지 보는 데 필요하다.
        val rows = typeQty.flatMap { (name, qty) ->
            qty.mapIndexedNotNull { i, q ->
                if (q == 0L) null
                else mapOf("slot" to labels[i], "defectCd" to "DF-$name", "defectNm" to name, "ngQty" to q)
            }
        }
        `when`(repository.findDefectTrendByType("PL01", window, 2, null, 500)).thenReturn(rows)
    }

    /** 유형 이력이 없는 라벨의 불량 — 첫 칸에는 행이 없다(0). */
    private fun stubUntyped(vararg qty: Pair<String, Long>) {
        `when`(repository.findUntypedDefectTrend("PL01", window, 2, null))
            .thenReturn(qty.map { (slot, q) -> mapOf("slot" to slot, "ngQty" to q) })
    }

    @Suppress("UNCHECKED_CAST")
    private fun seriesOf(data: Map<String, Any?>): List<Map<String, Any?>> = data["series"] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun scopeOf(data: Map<String, Any?>): Map<String, Any?> = data["seriesScope"] as Map<String, Any?>

    @Test
    @DisplayName("topN 미지정 — 기존과 같이 전체 불량률 + 구간 합계 상위 2종만 내린다")
    fun defaultKeepsTopTwo() {
        grantAll(); stubRows()

        val (data, _) = service.getDefectTrend("2026-08-28")

        val series = seriesOf(data)
        assertEquals(listOf("전체", "A", "B"), series.map { it["name"] })
        assertEquals(
            mapOf("kind" to "TOP_N", "topN" to 2, "count" to 2, "includesUntyped" to false),
            scopeOf(data).filterKeys { it != "note" }
        )
        // 상위 N 이면 유형 미상은 조회하지도 않는다.
        verify(repository, never()).findUntypedDefectTrend("PL01", window, 2, null)
    }

    @Test
    @DisplayName("topN=all — 구간에서 발생한 전 유형을 합계 내림차순으로, labels 와 같은 길이·순서로 내린다")
    fun allReturnsEveryType() {
        grantAll(); stubRows()

        val (data, _) = service.getDefectTrend("2026-08-28", null, null, "2h", "all")

        assertEquals(labels, data["labels"])
        val series = seriesOf(data)
        assertEquals(listOf("전체", "A", "B", "D", "C"), series.map { it["name"] })

        // 불량률 계열이 첫 계열이다.
        assertEquals(listOf(10.0, 11.0, 12.0), series.first()["data"])

        // 유형 계열은 수량(EA)이며 행이 없는 칸은 0 으로 채운다.
        series.drop(1).forEach { s ->
            assertEquals(typeQty.getValue(s["name"] as String), s["data"], "계열 ${s["name"]} 이 labels 와 어긋난다")
        }

        val scope = scopeOf(data)
        assertEquals("ALL", scope["kind"])
        assertNull(scope["topN"])
        assertEquals(4, scope["count"])
        assertEquals(false, scope["includesUntyped"])
    }

    @Test
    @DisplayName("topN=all — 유형이 붙지 않은 불량이 있으면 '유형 미상' 을 마지막 계열로 붙인다")
    fun allAppendsUntypedLast() {
        grantAll(); stubRows(); stubUntyped("10:00" to 7L, "12:00" to 2L)

        val (data, _) = service.getDefectTrend("2026-08-28", topN = "all")

        val series = seriesOf(data)
        assertEquals(listOf("전체", "A", "B", "D", "C", "유형 미상"), series.map { it["name"] })
        assertEquals(listOf(0L, 7L, 2L), series.last()["data"])

        val scope = scopeOf(data)
        assertEquals(5, scope["count"])
        assertEquals(true, scope["includesUntyped"])
    }

    @Test
    @DisplayName("topN=3 — 상위 N 에는 '유형 미상' 을 붙이지 않는다")
    fun topNNeverAppendsUntyped() {
        grantAll(); stubRows(); stubUntyped("10:00" to 7L)

        val (data, _) = service.getDefectTrend("2026-08-28", topN = "3")

        assertEquals(listOf("전체", "A", "B", "D"), seriesOf(data).map { it["name"] })
    }

    @Test
    @DisplayName("topN=all 은 대소문자를 가리지 않고, 정수는 상위 N종으로 본다")
    fun integerAndCaseInsensitive() {
        grantAll(); stubRows()

        assertEquals(5, seriesOf(service.getDefectTrend("2026-08-28", topN = "ALL").first).size)

        val (three, _) = service.getDefectTrend("2026-08-28", topN = "3")
        assertEquals(listOf("전체", "A", "B", "D"), seriesOf(three).map { it["name"] })
        assertEquals(3, scopeOf(three)["topN"])

        // 유형 수보다 큰 N 은 있는 만큼만 낸다.
        val (ten, _) = service.getDefectTrend("2026-08-28", topN = "10")
        assertEquals(5, seriesOf(ten).size)
        assertEquals(4, scopeOf(ten)["count"])
    }

    @Test
    @DisplayName("topN 이 all 도 양의 정수도 아니면 400 으로 거절한다")
    fun invalidTopNRejected() {
        grantAll()

        listOf("0", "-1", "abc", "2.5").forEach { bad ->
            val ex = assertThrows(InvalidParameterException::class.java) {
                service.getDefectTrend("2026-08-28", topN = bad)
            }
            assertEquals("topN", ex.field, "topN=$bad")
        }
    }

    @Test
    @DisplayName("수율 권한이 없으면 topN=all 이어도 계열을 내리지 않는다")
    fun yieldDeniedReturnsNoSeries() {
        val principal = UserPrincipal(
            "test", "test", 1, "test", "test", "STAFF", "PL01", false,
            menuPerms = setOf(MenuId.DASH_AI), dataPerms = emptySet()
        )
        `when`(authorization.guard(MenuId.DASH_AI)).thenReturn(principal to MaskingSupport(principal))

        val (data, mask) = service.getDefectTrend("2026-08-28", topN = "all")

        assertEquals(emptyList<Any>(), data["series"])
        assertEquals(listOf(DataField.YIELD), mask.maskedKeys())
    }

    @Test
    @DisplayName("컨트롤러는 topN 쿼리를 그대로 서비스에 넘긴다")
    fun controllerPassesTopN() {
        val svc = mock(DashboardAiService::class.java)
        val principal = UserPrincipal("test", "test", 1, "test", "test", "STAFF", "PL01", true)
        `when`(svc.getDefectTrend("2026-08-28", "2026-08-01", "2026-08-28", "2h", "all"))
            .thenReturn(mapOf("labels" to emptyList<String>()) to MaskingSupport(principal))

        val response = DashboardAiController(svc).defectTrend("2026-08-28", "2026-08-01", "2026-08-28", "2h", "all")

        verify(svc).getDefectTrend("2026-08-28", "2026-08-01", "2026-08-28", "2h", "all")
        assertEquals(mapOf("labels" to emptyList<String>()), response.data)
    }
}
