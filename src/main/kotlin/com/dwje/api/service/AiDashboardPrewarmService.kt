package com.dwje.api.service

import com.dwje.api.common.security.UserContext
import com.dwje.api.common.util.MenuId
import com.dwje.api.config.AppProperties
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.LocalDate

/**
 * 대시보드 AI 미리 계산 — 「AI 일일 품질·생산 종합 브리핑」 「AI 공정 원인 분석 및 처방 권고」
 *
 * 두 카드는 모델 추론이라 한 번에 30초가량 걸린다(dwje-ax 실측). 화면을 열자마자 보이게 하려고
 * **화면 기본 조회 기간**(마지막 실적일 기준 7일, 화면의 `unitRange('일별')` 과 같다)을 서버가 먼저 계산해
 * [SllmClient] 캐시에 둔다. 화면이 같은 기간을 부르면 모델을 다시 부르지 않고 바로 받는다.
 *
 * ## 누구의 권한으로 계산하나
 * 모델 입력에는 권한 마스킹이 들어간다. 그래서 `dash-ai` 를 볼 수 있는 **부서마다 활성 사용자 한 명**의
 * 권한으로 계산한다. 가려지는 항목이 같은 부서끼리는 입력이 같아 캐시가 알아서 한 번만 부른다.
 * 결과(근거 대조 포함)는 화면이 부를 때 그 사용자의 권한으로 다시 만든다 — 여기서 만든 것을 그대로 내리지 않는다.
 *
 * LLM 서버는 한 번에 한 건만 처리한다. 사용자 요청과 겹쳐도 [SllmClient] 의 대기열이 한 줄로 세운다.
 */
@Service
class AiDashboardPrewarmService(
    private val appProperties: AppProperties,
    private val dashboardAiService: DashboardAiService,
    private val authorizationService: AuthorizationService,
    private val commonMasterService: CommonMasterService,
    private val jdbcTemplate: NamedParameterJdbcTemplate
) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** 화면 기본 조회 기간 — 일별 7일 (WEB `UNIT_SPAN.일별`) */
        private const val DEFAULT_SPAN_DAYS = 7L
    }

    @Scheduled(
        initialDelayString = "\${app.ai.prewarm-initial-delay-ms:30000}",
        fixedDelayString = "\${app.ai.prewarm-interval-ms:1800000}"
    )
    fun prewarm() {
        val cfg = appProperties.ai
        if (!cfg.enabled || !cfg.prewarmEnabled) return

        val to = runCatching { LocalDate.parse(commonMasterService.getProductionDateRange(null, null)["toDate"].toString()) }
            .getOrNull() ?: return
        val from = to.minusDays(DEFAULT_SPAN_DAYS - 1)

        val users = representativeUsers()
        val started = System.currentTimeMillis()
        users.forEach { userId ->
            try {
                UserContext.set(authorizationService.loadPrincipal(userId))
                val briefing = dashboardAiService.getBriefing(null, from.toString(), to.toString())
                // 화면과 같은 순서 — 브리핑이 안 나오면 원인 분석도 부르지 않는다.
                if (briefing["reason"] == null) {
                    dashboardAiService.getCausePrescription(null, from.toString(), to.toString(), null, null, null)
                }
            } catch (e: Exception) {
                log.warn("대시보드 AI 미리 계산 실패 : user={} {}", userId, e.toString())
            } finally {
                UserContext.clear()
            }
        }
        log.info("대시보드 AI 미리 계산 : {} ~ {} 부서 {}곳 {}ms", from, to, users.size, System.currentTimeMillis() - started)
    }

    /** `dash-ai` 를 볼 수 있는 부서마다 활성 사용자 한 명(사번 가장 작은 사람) */
    private fun representativeUsers(): List<String> {
        val sql = """
            SELECT min(u.user_id) AS user_id
              FROM ax.tb_sys_user u
              JOIN ax.tb_sys_dept d ON d.dept_id = u.dept_id
             WHERE u.user_state_cd = 'ACTIVE'
               AND (d.is_super_admin
                    OR EXISTS (SELECT 1 FROM ax.tb_sys_dept_menu_perm p
                                WHERE p.dept_id = u.dept_id AND p.menu_id = :menuId AND p.can_read))
             GROUP BY u.dept_id
             ORDER BY 1
        """.trimIndent()
        return jdbcTemplate.queryForList(sql, MapSqlParameterSource("menuId", MenuId.DASH_AI), String::class.java)
    }
}
