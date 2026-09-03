package com.dwje.api.common.security

/**
 * 인증된 사용자 컨텍스트
 *
 * JwtAuthFilter 가 토큰 검증 후 생성하여 [UserContext] 에 바인딩한다.
 * 서비스 계층은 이 객체로 메뉴 권한·데이터 권한을 판정한다.
 *
 * @param userId       사번 (= 계정 ID, ax.tb_sys_user.user_id)
 * @param userName     사용자명
 * @param deptId       소속 부서 ID
 * @param deptName     소속 부서명
 * @param deptAbbr     부서 약칭
 * @param positionCd   직급 코드 (SYS_POSITION)
 * @param plantCd      사업장 코드
 * @param superAdmin   통합관리자 여부 (ax.tb_sys_dept.is_super_admin)
 * @param menuPerms    접근 가능한 메뉴 ID 집합
 * @param dataPerms    허용된 데이터 항목 key 집합 (qty/yield/price/customer/plan/mold/worker)
 * @param impersonated 계정 전환(대행 로그인) 여부
 */
data class UserPrincipal(
    val userId: String,
    val userName: String,
    val deptId: Int,
    val deptName: String,
    val deptAbbr: String?,
    val positionCd: String?,
    val plantCd: String?,
    val superAdmin: Boolean,
    val menuPerms: Set<String> = emptySet(),
    val dataPerms: Set<String> = emptySet(),
    val impersonated: Boolean = false
) {
    /** 통합관리자는 모든 메뉴에 접근한다. */
    fun canAccessMenu(menuId: String): Boolean = superAdmin || menuPerms.contains(menuId)

    /** 통합관리자·경영진은 전 데이터 항목을 열람한다. */
    fun canReadField(fieldKey: String): Boolean = superAdmin || dataPerms.contains(fieldKey)
}
