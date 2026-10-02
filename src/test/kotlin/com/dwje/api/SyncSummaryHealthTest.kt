package com.dwje.api

import com.dwje.api.config.SyncHealthProperties
import com.dwje.api.repository.SyncHealthStats
import com.dwje.api.service.SyncHealth
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 데이터 연동 상태 줄 판정 — 12 기획서 SYN-03 (순수 함수) */
class SyncSummaryHealthTest {

    private val props = SyncHealthProperties()

    private fun stats(states: List<String> = listOf("DONE"), staleMin: Long? = 3, openFail: Long = 0, stalePending: Long = 0,
                      lastState: String = states.firstOrNull() ?: "DONE", lastSuccess: String? = "2026-09-30 12:25:50") =
        SyncHealthStats(mapOf("state" to lastState), states, 0, lastSuccess, staleMin, openFail, null, stalePending)

    @Test
    @DisplayName("원본 접속 실패 3연속 → DOWN 「원본 접속 실패 3회 연속」, 다른 실패가 섞이면 「이관 실행 실패」")
    fun preflightDown() {
        assertEquals("DOWN" to "원본 접속 실패 3회 연속", SyncHealth.evaluate(stats(listOf("PREFLIGHT_FAIL", "PREFLIGHT_FAIL", "PREFLIGHT_FAIL", "DONE")), props))
        assertEquals("DOWN" to "이관 실행 실패 3회 연속", SyncHealth.evaluate(stats(listOf("FAIL", "PREFLIGHT_FAIL", "ABORTED")), props))
        assertEquals(2, SyncHealth.consecutiveFailRuns(listOf("FAIL", "FAIL", "DONE", "FAIL")))
    }

    @Test
    @DisplayName("WARN — 실패 1회 연속·최근 실행 일부 실패·오래된 예약·미조치 실패, 사유는 순서대로 잇는다")
    fun warn() {
        assertEquals("WARN" to "이관 실행 실패 1회 연속", SyncHealth.evaluate(stats(listOf("FAIL", "DONE")), props))
        assertEquals("WARN" to "최근 실행 일부 실패", SyncHealth.evaluate(stats(listOf("PARTIAL")), props))
        assertEquals("WARN" to "오래된 예약 작업 1건", SyncHealth.evaluate(stats(stalePending = 1), props))
        assertEquals("WARN" to "미조치 실패 작업 2건 · 오래된 예약 작업 1건", SyncHealth.evaluate(stats(openFail = 2, stalePending = 1), props))
    }

    @Test
    @DisplayName("경과 시간 — 120분 이상 DOWN, 30~119분 WARN, 정상 이관 기록이 없으면 WARN, 모두 정상이면 OK")
    fun staleness() {
        val down = SyncHealth.evaluate(stats(staleMin = 1500), props)
        assertEquals("DOWN", down.first); assertTrue(down.second.startsWith("마지막 정상 이관 후 25시간"), down.second)
        assertEquals("WARN" to "마지막 정상 이관 후 45분 경과", SyncHealth.evaluate(stats(staleMin = 45), props))
        assertEquals("WARN" to "정상 이관 기록 없음", SyncHealth.evaluate(stats(staleMin = null, lastSuccess = null), props))
        assertEquals("OK" to "정상", SyncHealth.evaluate(stats(), props))
    }
}
