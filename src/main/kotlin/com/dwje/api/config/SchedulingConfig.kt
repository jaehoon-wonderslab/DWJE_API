package com.dwje.api.config

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * 주기 작업 사용 — 대시보드 AI 미리 계산([com.dwje.api.service.AiDashboardPrewarmService])
 *
 * 끄려면 `app.ai.prewarm-enabled: false` 로 둔다(작업은 돌되 바로 돌아간다).
 */
@Configuration
@EnableScheduling
class SchedulingConfig
