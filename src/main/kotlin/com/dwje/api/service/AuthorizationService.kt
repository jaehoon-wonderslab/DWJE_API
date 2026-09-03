package com.dwje.api.service

import com.dwje.api.common.exception.MenuAccessDeniedException
import com.dwje.api.common.exception.ResourceNotFoundException
import com.dwje.api.common.exception.UnauthenticatedException
import com.dwje.api.common.security.UserContext
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.common.util.MaskingSupport
import com.dwje.api.repository.AuthRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 권한 판정 서비스
 *
 * 권한 모델은 2계층으로 구성된다.
 * 1. 메뉴 접근 권한 : 부서 × 화면 — 화면 진입 자체를 통제
 * 2. 데이터 접근 권한 : 부서 × 데이터 항목 7종 — 응답 값 마스킹을 통제
 *
 * 계정은 소속 부서의 권한을 상속하며, `is_super_admin` 부서(통합관리자)는 전 권한을 보유한다.
 */
@Service
class AuthorizationService(
    private val authRepository: AuthRepository
) {

    /**
     * 사번으로 인증 컨텍스트를 구성한다. (JwtAuthFilter 에서 매 요청 호출)
     *
     * 토큰 발급 이후 관리자가 권한을 변경한 경우를 즉시 반영하기 위해 매 요청 DB 를 조회한다.
     *
     * @param userId       사번
     * @param impersonated 계정 전환(대행 로그인) 여부
     * @throws UnauthenticatedException 계정이 없거나 정지 상태인 경우
     */
    @Transactional(readOnly = true)
    fun loadPrincipal(userId: String, impersonated: Boolean = false): UserPrincipal {
        // 1. 계정 · 부서 조회
        val user = authRepository.findUserWithDept(userId)
            ?: throw UnauthenticatedException("존재하지 않는 계정입니다.")

        // 2. 정지 계정 차단
        if (user["userStateCd"] != "ACTIVE") {
            throw UnauthenticatedException("사용이 정지된 계정입니다. 관리자에게 문의하세요.")
        }

        val deptId = user["deptId"] as Int
        val superAdmin = user["superAdmin"] as Boolean

        // 3. 부서 기준 메뉴/데이터 권한 조회 (통합관리자는 조회 없이 전 권한)
        val menuPerms = if (superAdmin) emptySet() else authRepository.findMenuPermissions(deptId)
        val dataPerms = if (superAdmin) emptySet() else authRepository.findDataPermissions(deptId)

        return UserPrincipal(
            userId = user["userId"] as String,
            userName = user["userName"] as String,
            deptId = deptId,
            deptName = user["deptName"] as String,
            deptAbbr = user["deptAbbr"] as String?,
            positionCd = user["positionCd"] as String?,
            plantCd = user["plantCd"] as String?,
            superAdmin = superAdmin,
            menuPerms = menuPerms,
            dataPerms = dataPerms,
            impersonated = impersonated
        )
    }

    /**
     * 관리자가 *다른* 계정의 권한을 조회할 때 사용한다. (권한 미리보기 등)
     *
     * [loadPrincipal] 과 분리한 이유는 실패 의미가 다르기 때문이다.
     * 인증 경로에서 계정이 없으면 "호출자가 인증되지 않음"(401)이지만,
     * 조회 경로에서 계정이 없으면 "조회 대상이 없음"(404)이다.
     * 이를 401 로 내보내면 화면의 401 인터셉터가 *호출한 관리자* 를 로그아웃시킨다.
     *
     * 정지 계정도 조회 대상이 된다. 정지된 계정의 권한을 확인하는 것은 정상 업무다.
     *
     * @param userId 조회 대상 사번
     * @throws ResourceNotFoundException 대상 계정이 없는 경우
     */
    @Transactional(readOnly = true)
    fun loadPrincipalForInspection(userId: String): UserPrincipal {
        val user = authRepository.findUserWithDept(userId)
            ?: throw ResourceNotFoundException("계정을 찾을 수 없습니다. [empNo=$userId]")

        val deptId = user["deptId"] as Int
        val superAdmin = user["superAdmin"] as Boolean
        val menuPerms = if (superAdmin) emptySet() else authRepository.findMenuPermissions(deptId)
        val dataPerms = if (superAdmin) emptySet() else authRepository.findDataPermissions(deptId)

        return UserPrincipal(
            userId = user["userId"] as String,
            userName = user["userName"] as String,
            deptId = deptId,
            deptName = user["deptName"] as String,
            deptAbbr = user["deptAbbr"] as String?,
            positionCd = user["positionCd"] as String?,
            plantCd = user["plantCd"] as String?,
            superAdmin = superAdmin,
            menuPerms = menuPerms,
            dataPerms = dataPerms,
            impersonated = false
        )
    }

    /**
     * 현재 사용자의 화면 접근 권한을 검증한다. 권한이 없으면 E-AUTH-002 로 차단한다.
     *
     * @param menuId 화면 ID (ax.tb_sys_menu.menu_id)
     */
    fun requireMenu(menuId: String): UserPrincipal {
        val principal = UserContext.current()
        if (!principal.canAccessMenu(menuId)) {
            throw MenuAccessDeniedException(menuId)
        }
        return principal
    }

    /**
     * 여러 화면 중 하나라도 접근 권한이 있으면 통과시킨다.
     * (하나의 API 가 여러 화면에서 공유되는 경우에 사용)
     */
    fun requireAnyMenu(vararg menuIds: String): UserPrincipal {
        val principal = UserContext.current()
        if (menuIds.none { principal.canAccessMenu(it) }) {
            throw MenuAccessDeniedException(menuIds.joinToString("/"))
        }
        return principal
    }

    /**
     * 통합관리자 권한을 요구한다. (계정 전환 등 관리 기능)
     */
    fun requireSuperAdmin(): UserPrincipal {
        val principal = UserContext.current()
        if (!principal.superAdmin) {
            throw MenuAccessDeniedException("SUPER_ADMIN")
        }
        return principal
    }

    /**
     * 현재 사용자 기준 마스킹 지원 객체를 생성한다.
     */
    fun masking(): MaskingSupport = MaskingSupport(UserContext.currentOrNull())

    /**
     * 화면 권한 검증과 마스킹 객체 생성을 한 번에 수행한다.
     *
     * @return 검증된 사용자와 마스킹 지원 객체 쌍
     */
    fun guard(menuId: String): Pair<UserPrincipal, MaskingSupport> {
        val principal = requireMenu(menuId)
        return principal to MaskingSupport(principal)
    }
}
