package com.dwje.api.common.mail

import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

/**
 * 인증 메일 발송 실패 건수 — 기동 뒤부터 센다 (R-17). 보안 감사 로그 보존 정책 응답의 `mailFailSinceBoot`.
 *
 * 한비로 SMTP 계정은 32일 동안 웹 로그인이 없으면 「사용 안 함」 이 되어 발송이 멈춘다 — 실패가 쌓이면 화면이 경고한다.
 * 마지막 실패 시각은 재기동에도 남도록 인증 요청 표(`send_result_cd='FAIL'`)에서 읽는다.
 */
@Component
class MailSendStats {
    private val fails = AtomicLong()

    fun recordFailure() { fails.incrementAndGet() }

    val failSinceBoot: Long get() = fails.get()
}
