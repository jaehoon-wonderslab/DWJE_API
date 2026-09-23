package com.dwje.api.service

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.exception.MenuAccessDeniedException
import com.dwje.api.common.security.UserContext
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.model.request.ReportUsageRequest
import com.dwje.api.repository.ReportUsageRepository
import com.dwje.api.repository.UsageRow
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 보고서 사용 횟수 서비스 — `/menu/report` 자주 쓰는 보고서 버튼
 *
 * 계정별로 보고서를 만든 횟수 순으로 상위 N 개를 낸다. 브라우저(localStorage)가 아니라
 * DB 에 두는 것은 사용자 결정이다(2026-09-09) — 기기를 바꿔도 같은 버튼이 보여야 한다.
 */
@Service
class ReportUsageService(
    private val repository: ReportUsageRepository
) {

    companion object {
        const val DEFAULT_TOP = 5
        const val MAX_TOP = 20
    }

    /**
     * 자주 쓰는 보고서 상위 [top] 개.
     *
     * 사용 중지 메뉴와 **현재 사용자에게 권한이 없는 화면은 뺀다** — 권한이 회수된 보고서 버튼이 남으면 안 된다.
     */
    fun getTop(top: Int?): Map<String, Any?> {
        val principal = UserContext.current()
        val limit = normalizeTop(top)
        return toResponse(rankFor(principal, limit))
    }

    /**
     * 보고서를 한 번 만들었음을 기록하고 상위 5개를 돌려준다.
     *
     * 웹이 버튼 줄을 한 번의 호출로 갱신할 수 있게 갱신된 행이 아니라 목록을 준다.
     * 메뉴에 없는 ID 는 400, 권한 없는 화면은 E-AUTH-002 다.
     */
    @Transactional
    fun record(request: ReportUsageRequest): Map<String, Any?> {
        val principal = UserContext.current()
        val screenId = request.screenId?.trim()?.takeIf { it.isNotBlank() }
            ?: throw InvalidParameterException("필수 파라미터가 누락되었습니다. [screenId]", "screenId")

        if (!repository.menuExists(screenId)) {
            throw InvalidParameterException("등록되지 않은 화면 ID 입니다. [$screenId]", "screenId")
        }
        if (!principal.canAccessMenu(screenId)) {
            throw MenuAccessDeniedException(screenId)
        }

        repository.increment(principal.userId, screenId)
        return toResponse(rankFor(principal, DEFAULT_TOP))
    }

    private fun rankFor(principal: UserPrincipal, limit: Int): List<UsageRow> =
        repository.findByUser(principal.userId)
            .filter { principal.canAccessMenu(it.menuId) }
            .take(limit)

    private fun normalizeTop(top: Int?): Int {
        val value = top ?: DEFAULT_TOP
        if (value < 1 || value > MAX_TOP) {
            throw InvalidParameterException("top 은 1~${MAX_TOP} 사이여야 합니다. [$value]", "top")
        }
        return value
    }

    private fun toResponse(rows: List<UsageRow>): Map<String, Any?> =
        mapOf(
            "items" to rows.map {
                mapOf("screenId" to it.menuId, "useCount" to it.useCount, "lastUsedAt" to it.lastUsedAt)
            }
        )
}
