package com.dwje.api.service

import com.dwje.api.common.util.DateUtils
import com.dwje.api.repository.AlertConfigRepository
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.OffsetDateTime

/**
 * 알림 엔진 상태 (05 ALC-07) — 발송 조건 요약과 테스트 발송 응답이 함께 쓴다.
 *
 * 엔진은 조용해도 60분에 한 번 평가 실행 기록을 남긴다. 최근 기록이 [STOPPED_SEC] 넘게 없으면 멈춘 것으로 본다.
 */
@Component
class AlertEngineMonitor(private val alertConfigRepository: AlertConfigRepository) {

    fun state(): Map<String, Any?> {
        val raw = alertConfigRepository.findEngineState()
        val last = raw["lastRunAt"] as OffsetDateTime?
        val lagSec = last?.let { Duration.between(it, OffsetDateTime.now()).seconds }
        return mapOf(
            "lastRunAt" to last?.let { DateUtils.format(it) },
            "lastState" to raw["lastState"],
            "lagSec" to lagSec,
            "pendingQueueCnt" to raw["pendingQueueCnt"],
            "deadQueueCnt" to raw["deadQueueCnt"],
            "judge" to judgeOf(lagSec)
        )
    }

    companion object {
        /** 엔진 중지 판정 — 하트비트(60분) + 5분 여유 */
        const val STOPPED_SEC = 3_900L

        /** 기록 없음 UNKNOWN · 오래됨 STOPPED · 그 밖 OK */
        fun judgeOf(lagSec: Long?): String = when {
            lagSec == null -> "UNKNOWN"
            lagSec > STOPPED_SEC -> "STOPPED"
            else -> "OK"
        }
    }
}
