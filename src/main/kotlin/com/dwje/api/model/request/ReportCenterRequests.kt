package com.dwje.api.model.request

/**
 * 보고서 사용 기록 요청 — POST /api/v1/reports/usage
 *
 * 보고서를 한 번 만들 때(드롭다운·버튼으로 선택) 웹이 1회 호출한다. `(user_id, menu_id)` 의 횟수를 +1 한다.
 *
 * @param screenId 보고서 화면 ID (`ax.tb_sys_menu.menu_id`). 메뉴에 없으면 400, 권한 없으면 E-AUTH-002
 */
data class ReportUsageRequest(
    val screenId: String? = null
)
