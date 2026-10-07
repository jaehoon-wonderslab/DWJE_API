package com.dwje.api.service

import com.dwje.api.common.exception.MenuAccessDeniedException
import com.dwje.api.common.exception.ResourceNotFoundException
import com.dwje.api.common.exception.UnauthenticatedException
import com.dwje.api.common.exception.WriteAccessDeniedException
import com.dwje.api.common.security.UserContext
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.common.util.MaskingSupport
import com.dwje.api.common.util.MenuId
import com.dwje.api.config.AppProperties
import com.dwje.api.repository.AuthRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 권한 판정 서비스
 *
 * 권한 모델은 2계층으로 구성된다.
 * 1. 메뉴 접근 권한 : 부서 × 화면 — 화면 진입 자체를 통제. 접근할 수 있는 화면의 동작은 모두 허용한다(V70, 미배정은 쓰기 동작 불가)
 * 2. 데이터 접근 권한 : 부서 × 데이터 항목 7종 — 응답 값 마스킹을 통제
 *
 * 계정은 소속 부서의 권한을 상속하며, `is_super_admin` 부서(통합관리자)는 전 권한을 보유한다.
 */
@Service
class AuthorizationService(
    private val authRepository: AuthRepository,
    private val appProperties: AppProperties = AppProperties()
) {

    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)

    /**
     * 사번으로 인증 컨텍스트를 구성한다. (JwtAuthFilter 에서 매 요청 호출)
     *
     * 토큰 발급 이후 관리자가 권한을 변경한 경우를 즉시 반영하기 위해 매 요청 DB 를 조회한다.
     *
     * @param userId         사번
     * @param impersonated   계정 전환(대행 로그인) 여부
     * @param impersonatedBy 계정 전환을 한 통합관리자 사번 (전환 토큰에만 있다)
     * @throws UnauthenticatedException 계정이 없거나 사용 상태(ACTIVE)가 아닌 경우
     */
    @Transactional(readOnly = true)
    fun loadPrincipal(userId: String, impersonated: Boolean = false, impersonatedBy: String? = null): UserPrincipal {
        // 1. 계정 · 부서 조회
        val user = authRepository.findUserWithDept(userId)
            ?: throw UnauthenticatedException("존재하지 않는 계정입니다.")

        // 2. ACTIVE 만 통과 — 잠금(LOCKED)은 다시 로그인하면 잠금 해제 안내를 받으므로 문구를 나눈다 (09 AUD-16)
        when (user["userStateCd"]) {
            "ACTIVE" -> Unit
            "LOCKED" -> throw UnauthenticatedException("계정이 잠겼습니다. 다시 로그인해 잠금을 해제해 주세요.")
            else -> throw UnauthenticatedException("사용이 정지된 계정입니다. 관리자에게 문의하세요.")
        }

        return buildPrincipal(user, impersonated, impersonatedBy)
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
        return buildPrincipal(user, impersonated = false, impersonatedBy = null)
    }

    /**
     * 계정 행으로 권한 집합을 만든다 — [loadPrincipal] 과 [loadPrincipalForInspection] 이 같은 규칙을 쓴다.
     *
     * - 통합관리자: 권한 행을 읽지 않고 전 권한(집합은 비워 두고 `superAdmin` 으로 판정).
     * - 미배정(R-01·R-11): 화면 권한 = 유효 권한 ∩ [MenuId.UNASSIGNED_SCREENS], 데이터 권한 = 빈 집합.
     *   DB 에 행이 잘못 들어가도 넓어지지 않게 하는 방어선이다. 쓰기 동작은 [UserPrincipal.canWriteMenu] 가 막는다.
     * - 그 밖: 화면 권한 = 부서 ∪ 계정 추가 허용(V30 뷰), 데이터 권한 = 부서 기준. 쓰기 판정은 화면 권한과 같다(V70).
     *   매 요청 다시 읽으므로 추가 허용을 넣고 빼면 발급된 토큰에도 바로 반영된다.
     */
    private fun buildPrincipal(user: Map<String, Any?>, impersonated: Boolean, impersonatedBy: String?): UserPrincipal {
        val userId = user["userId"] as String
        val deptId = user["deptId"] as Int
        val deptName = user["deptName"] as String
        val superAdmin = user["superAdmin"] as Boolean
        val unassigned = !superAdmin && deptName == appProperties.unassignedDeptName

        val effective: Set<String> = if (superAdmin) emptySet() else authRepository.findEffectiveMenuPermissions(userId)
        val menuPerms = when {
            superAdmin -> emptySet()
            unassigned -> effective.filterTo(linkedSetOf()) { it in MenuId.UNASSIGNED_SCREENS }
            else -> effective
        }
        // 기본 7종은 항목 권한에서 계산한 느슨한 · 엄격한 두 집합(V82) — AuthRepository.findDataPermSets
        val (dataPerms, dataPermsStrict) =
            if (superAdmin || unassigned) emptySet<String>() to emptySet() else authRepository.findDataPermSets(deptId)
        if (unassigned) warnClampedUnassigned(userId, deptId, effective - MenuId.UNASSIGNED_SCREENS)

        return UserPrincipal(
            userId = userId,
            userName = user["userName"] as String,
            deptId = deptId,
            deptName = deptName,
            positionCd = user["positionCd"] as String?,
            plantCd = user["plantCd"] as String?,
            superAdmin = superAdmin,
            menuPerms = menuPerms,
            dataPerms = dataPerms,
            dataPermsStrict = dataPermsStrict,
            impersonated = impersonated,
            pwdChangeRequired = user["pwdChangeRequired"] == true,
            unassigned = unassigned,
            impersonatedBy = impersonatedBy
        )
    }

    /**
     * 미배정 방어선이 잘라 낸 권한이 있으면 경고를 남긴다(03 MNP-15, 04 DTP-16) — DB 에 잘못 들어간 행을 찾기 위함이다.
     * 판정 결과는 바꾸지 않는다(이미 잘렸다).
     */
    private fun warnClampedUnassigned(userId: String, deptId: Int, extraMenus: Set<String>) {
        val extraData = authRepository.findDataPermissions(deptId)
        if (extraMenus.isNotEmpty() || extraData.isNotEmpty()) {
            log.warn("미배정 계정 권한을 고정값으로 잘랐습니다 : user={} 화면={} 데이터={}", userId, extraMenus, extraData)
        }
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
     * 현재 사용자가 화면의 쓰기 동작(저장·삭제 등)을 할 수 있는지 검증한다 (V70 — 조회/쓰기 칸 통합).
     *
     * 판정 = 통합관리자 → 통과, 화면 접근 권한이 없으면 E-AUTH-002, 미배정 계정이면 E-AUTH-004, 그 밖에는 통과.
     * 이름은 호출부를 그대로 두려고 남겼다. 의미는 「화면 접근 + 미배정 아님」이다.
     * 엑셀 내려받기는 쓰기 대상이 아니다 — [requireMenu] 로 판정한다(공통 9.8, R-10).
     *
     * @param menuId 화면 ID (ax.tb_sys_menu.menu_id)
     */
    fun requireWrite(menuId: String): UserPrincipal {
        val principal = requireMenu(menuId)
        if (!principal.canWriteMenu(menuId)) {
            throw WriteAccessDeniedException(menuId)
        }
        return principal
    }

    /**
     * 여러 화면 중 하나라도 쓰기 동작이 허용되면 통과시킨다. (한 쓰기 API 를 두 화면이 함께 쓰는 경우)
     * 어느 화면에도 접근할 수 없으면 E-AUTH-002, 미배정 계정이면 E-AUTH-004 다.
     */
    fun requireAnyWrite(vararg menuIds: String): UserPrincipal {
        val principal = requireAnyMenu(*menuIds)
        if (menuIds.none { principal.canWriteMenu(it) }) {
            throw WriteAccessDeniedException(menuIds.joinToString("/"))
        }
        return principal
    }

    /** 현재 사용자가 화면 쓰기 동작을 할 수 있는지(접근 권한 + 미배정 아님) — 예외 없이 응답 필드(`canWrite` 등)를 채울 때 쓴다 */
    fun canWrite(menuId: String): Boolean = UserContext.currentOrNull()?.canWriteMenu(menuId) == true

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

    /**
     * [guard] 와 같되 엄격한 판정(V82) — 기본 7종 key 는 그 묶음에서 온 항목을 모두 볼 수 있을 때만 통과한다.
     * AI 프롬프트 · 브리핑 문장 · 근거 대조처럼 응답 키 단위로 다시 가릴 수 없는 출력을 만드는 API 가 쓴다.
     */
    fun guardStrict(menuId: String): Pair<UserPrincipal, MaskingSupport> {
        val principal = requireMenu(menuId)
        return principal to MaskingSupport(principal, strict = true)
    }
}
