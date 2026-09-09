package com.dwje.api

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.common.util.DataField
import com.dwje.api.common.util.MaskingSupport
import com.dwje.api.common.util.MenuId
import com.dwje.api.common.util.SlotBucket
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
import com.dwje.api.service.SllmClient
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 불량률 추이 칸 클릭 상세 — 칸 해석과 응답 규약
 *
 * ## 지켜야 하는 것
 * - 칸은 추이와 **같은 경계**로 잘라야 한다. 경계가 어긋나면 다이얼로그 합계가 매트릭스 칸 값과 다르다.
 * - 화면의 칸 라벨(`2026-08-28 00시`)을 그대로 받아야 한다. 화면이 시각을 다시 계산하면 규칙이 갈린다.
 * - 유형 합 + 유형 미상 = 칸 불량 수량. 비율의 분모는 칸 불량 수량이다.
 * - 수량(qty)·수율(yield)·작업자(worker) 마스킹은 다른 대시보드 조회와 같다.
 */
class DashboardAiSlotDetailsTest {

    private val repository = mock(DashboardAiRepository::class.java)
    private val authorization = mock(AuthorizationService::class.java)
    private val service = DashboardAiService(
        repository,
        mock(MetricStandardRepository::class.java),
        mock(DayTargetRepository::class.java),
        mock(AiEvidenceVerifier::class.java),
        mock(SllmClient::class.java),
        mock(DocEvidenceRepository::class.java),
        ObjectMapper(),
        authorization,
        AppProperties()
    )

    private val day = LocalDate.of(2026, 8, 28)
    private val dayWindow = TimeWindow.ofDay(day)
    private fun window(from: String, toInclusive: String) =
        TimeWindow(LocalDate.parse(from).atStartOfDay(), LocalDate.parse(toInclusive).plusDays(1).atStartOfDay())
    private fun at(text: String) = LocalDateTime.parse(text)

    private fun grant(vararg fields: String) {
        val principal = UserPrincipal(
            "test", "test", 1, "test", "test", "STAFF", "PL01", false,
            menuPerms = setOf(MenuId.DASH_AI), dataPerms = fields.toSet()
        )
        `when`(authorization.guard(MenuId.DASH_AI)).thenReturn(principal to MaskingSupport(principal))
    }

    // ------------------------------------------------------------------ 칸 해석

    @Test
    @DisplayName("SlotBucket — 칸 시작·끝·라벨이 SQL 규칙과 같다")
    fun bucketSlotMath() {
        val hourly = SlotBucket.of(dayWindow, 2)
        assertEquals(at("2026-08-28T02:00"), hourly.slotStartOf(at("2026-08-28T03:59")))
        assertEquals(at("2026-08-28T04:00"), hourly.slotEndOf(at("2026-08-28T02:00")))
        assertEquals("02:00", hourly.labelOf(at("2026-08-28T02:00")))
        assertEquals(at("2026-08-28T08:00"), hourly.parseLabel("08:00", dayWindow))
        assertNull(hourly.parseLabel("25:00", dayWindow))

        val weekly = SlotBucket.of(window("2026-08-25", "2026-08-31"), 3)
        assertEquals("08-28 03시", weekly.labelOf(at("2026-08-28T03:00")))
        assertEquals(at("2026-08-28T03:00"), weekly.parseLabel("08-28 03시", window("2026-08-25", "2026-08-31")))
        assertNull(weekly.parseLabel("09-05 03시", window("2026-08-25", "2026-08-31")))

        val daily = SlotBucket.of(window("2026-08-01", "2026-08-28"), 2)
        assertEquals(at("2026-08-28T00:00"), daily.slotStartOf(at("2026-08-28T23:00")))
        assertEquals(at("2026-08-29T00:00"), daily.slotEndOf(at("2026-08-28T00:00")))
        assertEquals("08-28", daily.labelOf(at("2026-08-28T00:00")))
        assertEquals(at("2026-08-28T00:00"), daily.parseLabel("08-28", window("2026-08-01", "2026-08-28")))

        // 연말을 넘는 구간 — 라벨에 연도가 없어도 구간 안의 해를 찾는다.
        val newYear = window("2025-12-01", "2026-01-31")
        assertEquals(at("2026-01-05T00:00"), SlotBucket.of(newYear, 2).parseLabel("01-05", newYear))

        val weekUnit = SlotBucket.of(window("2026-01-01", "2026-06-30"), 2)
        assertEquals(at("2026-08-24T00:00"), weekUnit.slotStartOf(at("2026-08-28T10:00"))) // 월요일 시작
    }

    @Test
    @DisplayName("slotAt — 화면 라벨 형식(2026-08-28 00시)과 시각 형식을 모두 그 칸으로 맞춘다")
    fun slotAtFormats() {
        val bucket = SlotBucket.of(dayWindow, 2)
        val expected = TimeWindow(at("2026-08-28T00:00"), at("2026-08-28T02:00"))

        listOf("2026-08-28 00시", "2026-08-28 01시", "2026-08-28 00:00", "2026-08-28T01:30", "2026-08-28 01:59:59", "2026-08-28").forEach {
            assertEquals(expected, service.resolveSlot(dayWindow, bucket, null, it), "slotAt=$it")
        }
        // slotAt 이 slot 보다 우선한다
        assertEquals(expected, service.resolveSlot(dayWindow, bucket, "08:00", "2026-08-28 00시"))
    }

    @Test
    @DisplayName("slot 라벨 — 추이 labels 값으로 칸을 고르고, 구간 끝 칸은 구간에서 잘린다")
    fun slotLabel() {
        val bucket = SlotBucket.of(dayWindow, 2)
        assertEquals(
            TimeWindow(at("2026-08-28T08:00"), at("2026-08-28T10:00")),
            service.resolveSlot(dayWindow, bucket, "08:00", null)
        )

        // 7일 구간 + 3시간 단위 — 마지막 칸(21시)은 3시간이지만 하루 안에서 자른 규칙상 21~24시
        val week = window("2026-08-22", "2026-08-28")
        val threeHour = SlotBucket.of(week, 3)
        assertEquals(
            TimeWindow(at("2026-08-28T21:00"), at("2026-08-29T00:00")),
            service.resolveSlot(week, threeHour, "08-28 21시", null)
        )

        // 5시간 단위 — 20시 칸은 20~25시가 아니라 조회 구간 끝(다음날 0시)까지다
        val fiveHour = SlotBucket.of(dayWindow, 5)
        assertEquals(
            TimeWindow(at("2026-08-28T20:00"), at("2026-08-29T00:00")),
            service.resolveSlot(dayWindow, fiveHour, null, "2026-08-28 22시")
        )

        // 일 단위 구간에서 시각을 주면 그 하루 전체가 칸이다
        val month = window("2026-08-01", "2026-08-28")
        assertEquals(
            TimeWindow(at("2026-08-28T00:00"), at("2026-08-29T00:00")),
            service.resolveSlot(month, SlotBucket.of(month, 2), null, "2026-08-28 00시")
        )
    }

    @Test
    @DisplayName("칸을 못 고르면 400 — 없음·구간 밖·형식 오류·모르는 라벨")
    fun slotRejected() {
        val bucket = SlotBucket.of(dayWindow, 2)
        fun field(slot: String?, slotAt: String?): String? =
            assertThrows(InvalidParameterException::class.java) { service.resolveSlot(dayWindow, bucket, slot, slotAt) }.field

        assertEquals("slotAt", field(null, null))
        assertEquals("slotAt", field(null, "2026-08-29 00시"))
        assertEquals("slotAt", field(null, "28일 0시"))
        assertEquals("slot", field("08-28 00시", null)) // 하루 구간의 라벨 형식은 HH:MM 이다
        assertEquals("slot", field("abc", null))
    }

    // ------------------------------------------------------------------ 응답

    private val slot = TimeWindow(at("2026-08-28T00:00"), at("2026-08-28T02:00"))

    private fun stubSlot() {
        `when`(repository.findSlotLabelTotals("PL01", slot)).thenReturn(
            mapOf("totalQty" to 1000L, "ngQty" to 100L, "labelCount" to 12L, "defectRate" to 10.0)
        )
        `when`(repository.findSlotDefectDetails("PL01", slot)).thenReturn(
            listOf(
                row("DF004", "찍힘", 60L, 63L, listOf("A-1", "A-2"), listOf("W110"), listOf("재검"), listOf("u1", "u2")),
                row("DF003", "스크래치", 30L, 30L, listOf("A-1"), listOf("W110", "S110"), emptyList(), listOf("u1"))
            )
        )
    }

    private fun row(
        cd: String, nm: String, ng: Long, raw: Long,
        items: List<String>, wcs: List<String>, remarks: List<String>, users: List<String>
    ): Map<String, Any?> = mapOf(
        "defectCd" to cd, "defectNm" to nm, "useFlg" to "Y", "masterRemark" to null,
        "ngQty" to ng, "rawQty" to raw, "recordCount" to 5L, "lotCount" to 2L, "itemCount" to items.size.toLong(),
        "itemCds" to items, "processIds" to wcs, "remarks" to remarks, "insUsers" to users,
        "firstAt" to "2026-08-28 00:10:00", "lastAt" to "2026-08-28 01:50:00"
    )

    @Suppress("UNCHECKED_CAST")
    private fun itemsOf(data: Map<String, Any?>) = data["items"] as List<Map<String, Any?>>

    @Test
    @DisplayName("전 권한 — 유형 전량을 순위대로, 유형 미상은 차액으로 마지막에, 합 = totalNgQty")
    fun fullDetails() {
        grant(DataField.YIELD, DataField.QTY, DataField.WORKER); stubSlot()

        val (data, mask) = service.getDefectTrendSlotDetails("2026-08-28", interval = "2h", slotAt = "2026-08-28 00시")

        assertEquals("00:00", data["slot"])
        assertEquals("2026-08-28 00:00:00", data["slotFrom"])
        assertEquals("2026-08-28 02:00:00", data["slotTo"])
        assertEquals("PL01", data["plantCd"])
        assertEquals(1000L, data["inputQty"]); assertEquals(900L, data["okQty"])
        assertEquals(100L, data["totalNgQty"]); assertEquals(90L, data["typedNgQty"]); assertEquals(10L, data["untypedNgQty"])
        assertEquals(10.0, data["defectRate"])

        val items = itemsOf(data)
        assertEquals(listOf(1, 2, 3), items.map { it["rank"] })
        assertEquals(listOf("찍힘", "스크래치", "유형 미상"), items.map { it["defectType"] })
        assertEquals(listOf("DF004", "DF003", null), items.map { it["defectTypeCd"] })
        assertEquals(listOf(false, false, true), items.map { it["untyped"] })
        assertEquals(listOf(60L, 30L, 10L), items.map { it["ngQty"] })
        assertEquals(listOf(60.0, 30.0, 10.0), items.map { it["ratio"] })
        assertEquals(100L, items.sumOf { it["ngQty"] as Long })

        // 원표 속성이 그대로 실린다
        val top = items.first()
        assertEquals(63L, top["rawQty"]); assertEquals(5L, top["recordCount"]); assertEquals(2L, top["lotCount"])
        assertEquals(listOf("A-1", "A-2"), top["itemCds"]); assertEquals(listOf("W110"), top["processIds"])
        assertEquals(listOf("재검"), top["remarks"]); assertEquals(listOf("u1", "u2"), top["insUsers"])
        assertEquals("2026-08-28 00:10:00", top["firstAt"]); assertEquals("Y", top["useFlg"])
        assertEquals(emptyList<String>(), mask.maskedKeys())
    }

    @Test
    @DisplayName("수량 권한 없음 — 수량은 null, 순위·비율·속성은 남고 masked 에 qty 가 실린다")
    fun qtyMasked() {
        grant(DataField.YIELD, DataField.WORKER); stubSlot()

        val (data, mask) = service.getDefectTrendSlotDetails("2026-08-28", interval = "2h", slot = "00:00")

        listOf("inputQty", "okQty", "totalNgQty", "typedNgQty", "untypedNgQty").forEach { assertNull(data[it], it) }
        assertEquals(10.0, data["defectRate"])
        val items = itemsOf(data)
        assertEquals(3, items.size)
        items.forEach { assertNull(it["ngQty"]); assertNull(it["rawQty"]) }
        assertEquals(listOf(60.0, 30.0, 10.0), items.map { it["ratio"] })
        assertEquals(listOf("u1", "u2"), items.first()["insUsers"])
        assertEquals(listOf(DataField.QTY), mask.maskedKeys())
    }

    @Test
    @DisplayName("작업자 권한 없음 — 등록자 목록만 null")
    fun workerMasked() {
        grant(DataField.YIELD, DataField.QTY); stubSlot()

        val (data, mask) = service.getDefectTrendSlotDetails("2026-08-28", interval = "2h", slot = "00:00")

        itemsOf(data).forEach { assertNull(it["insUsers"]) }
        assertEquals(60L, itemsOf(data).first()["ngQty"])
        assertEquals(listOf(DataField.WORKER), mask.maskedKeys())
    }

    @Test
    @DisplayName("수율 권한 없음 — 칸 정보만 내리고 items 는 비우며 DB 를 조회하지 않는다")
    fun yieldMasked() {
        grant(DataField.QTY)

        val (data, mask) = service.getDefectTrendSlotDetails("2026-08-28", interval = "2h", slot = "00:00")

        assertEquals("00:00", data["slot"])
        assertEquals(emptyList<Any>(), data["items"])
        assertNull(data["totalNgQty"])
        assertEquals(listOf(DataField.YIELD), mask.maskedKeys())
        verifyNoInteractions(repository)
    }

    @Test
    @DisplayName("공장 코드를 주면 그 공장으로 조회한다")
    fun plantOverride() {
        grant(DataField.YIELD, DataField.QTY, DataField.WORKER)
        `when`(repository.findSlotLabelTotals("PL02", slot)).thenReturn(mapOf("totalQty" to 0L, "ngQty" to 0L, "labelCount" to 0L, "defectRate" to 0.0))

        val (data, _) = service.getDefectTrendSlotDetails("2026-08-28", interval = "2h", slot = "00:00", plantCd = " PL02 ")

        assertEquals("PL02", data["plantCd"])
        assertEquals(emptyList<Any>(), data["items"])
        verify(repository).findSlotDefectDetails("PL02", slot)
    }

    @Test
    @DisplayName("컨트롤러는 파라미터를 그대로 서비스에 넘긴다")
    fun controllerPassesThrough() {
        val svc = mock(DashboardAiService::class.java)
        val principal = UserPrincipal("test", "test", 1, "test", "test", "STAFF", "PL01", true)
        `when`(svc.getDefectTrendSlotDetails("2026-08-28", "2026-08-22", "2026-08-28", "2h", "08-28 00시", "2026-08-28 00시", "PL01"))
            .thenReturn(mapOf("slot" to "08-28 00시") to MaskingSupport(principal))

        val response = DashboardAiController(svc)
            .defectTrendSlotDetails("2026-08-28", "2026-08-22", "2026-08-28", "2h", "08-28 00시", "2026-08-28 00시", "PL01")

        assertEquals(mapOf("slot" to "08-28 00시"), response.data)
    }
}
