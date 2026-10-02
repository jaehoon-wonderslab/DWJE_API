package com.dwje.api.service

import com.dwje.api.common.util.DateUtils
import com.dwje.api.config.SyncHealthProperties
import com.dwje.api.repository.SyncHealthStats

/**
 * 데이터 연동 상태 줄 판정 — 12 기획서 SYN-03
 *
 * 위에서부터 먼저 맞는 단계를 고른다.
 * - DOWN : 실행 실패 3회 연속, 또는 마지막 정상 이관 후 `stale-down-min` 이상
 * - WARN : 실행 실패 1회 이상 연속, 미조치 실패 작업, 오래된 예약, `stale-warn-min` 이상, 최근 실행 일부 실패, 정상 이관 기록 없음
 * - OK   : 그 밖
 *
 * 사유(`healthReason`)는 그 단계에 해당하는 것을 순서대로 「 · 」 로 잇는다. DB 를 보지 않는 순수 함수라 단위 시험으로 고정한다.
 */
object SyncHealth {

    private val FAIL_STATES = setOf("FAIL", "PREFLIGHT_FAIL", "ABORTED")

    /** 최근부터 실패가 이어진 실행 수 — 실패가 아닌 첫 실행에서 끊는다 */
    fun consecutiveFailRuns(recentStates: List<String>): Int = recentStates.takeWhile { it in FAIL_STATES }.size

    /** @return (healthState, healthReason) */
    fun evaluate(stats: SyncHealthStats, props: SyncHealthProperties): Pair<String, String> {
        val fails = consecutiveFailRuns(stats.recentRunStates)
        val preflight = fails > 0 && stats.recentRunStates.take(fails).all { it == "PREFLIGHT_FAIL" }
        val failReason = if (preflight) "원본 접속 실패 ${fails}회 연속" else "이관 실행 실패 ${fails}회 연속"
        val staleReason = stats.staleMin?.let { "마지막 정상 이관 후 ${DateUtils.humanizeMinutes(it) ?: "${it}분"} 경과" }

        val down = listOfNotNull(
            failReason.takeIf { fails >= 3 },
            staleReason.takeIf { (stats.staleMin ?: 0) >= props.staleDownMin }
        )
        if (down.isNotEmpty()) return "DOWN" to down.joinToString(" · ")

        val warn = listOfNotNull(
            failReason.takeIf { fails >= 1 },
            staleReason.takeIf { (stats.staleMin ?: 0) >= props.staleWarnMin },
            "정상 이관 기록 없음".takeIf { stats.lastSuccessAt == null },
            "미조치 실패 작업 ${stats.openFailJobCnt}건".takeIf { stats.openFailJobCnt > 0 },
            "오래된 예약 작업 ${stats.stalePendingCnt}건".takeIf { stats.stalePendingCnt > 0 },
            "최근 실행 일부 실패".takeIf { stats.lastRun?.get("state") == "PARTIAL" }
        )
        if (warn.isNotEmpty()) return "WARN" to warn.joinToString(" · ")
        return "OK" to "정상"
    }
}
