package com.dwje.api.model.request

/**
 * 사용자 즐겨찾기 화면 교체 요청 — PUT /api/v1/users/me/favorites
 *
 * 목록 **전체를 순서대로 교체**한다(멱등). 빈 배열은 전부 해제다.
 * 부분 갱신을 두지 않는 이유는 화면이 별표 순서를 통째로 들고 있어
 * "지금 보이는 순서가 곧 저장될 순서" 여야 하기 때문이다.
 *
 * @param screenIds 화면 ID(`ax.tb_sys_menu.menu_id`) 목록. 배열 순서가 곧 `sortOrder` 다.
 */
data class FavoriteScreensRequest(
    val screenIds: List<String>? = null
)

/**
 * 보고서 작성 상태 기록 요청 — PUT /api/v1/reports/status
 *
 * 워크플로우(결재선·반려)가 아니라 **표시용 상태 한 칸**을 기록한다.
 * 문서 관리가 제거된 뒤(2026-09-04) 제출·승인을 파생할 원천이 없어서
 * 화면 머리말의 「제출」「승인」「작성 중으로 되돌리기」 단추가 이 값을 쓴다.
 *
 * @param screenId 대상 화면 ID — `prod-daily` · `rpt-press-morning` · `rpt-plating-morning` · `rpt-scrap`
 * @param baseDate 보고서 대상일 (`yyyy-MM-dd`, 필수)
 * @param state    `DRAFT` | `SUBMITTED` | `APPROVED`
 */
data class ReportWriteStateRequest(
    val screenId: String? = null,
    val baseDate: String? = null,
    val state: String? = null
)

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
