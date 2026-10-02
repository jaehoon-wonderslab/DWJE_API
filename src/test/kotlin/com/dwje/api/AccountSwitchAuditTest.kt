package com.dwje.api

import com.dwje.api.common.exception.BusinessRuleException
import com.dwje.api.common.exception.UnlockUnavailableException
import com.dwje.api.common.response.ErrorCode
import com.dwje.api.common.security.JwtTokenProvider
import com.dwje.api.common.security.PasswordEncoderService
import com.dwje.api.common.security.UserContext
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.config.AccountUnlockProperties
import com.dwje.api.config.AppProperties
import com.dwje.api.config.JwtProperties
import com.dwje.api.config.PasswordProperties
import com.dwje.api.model.request.SwitchAccountRequest
import com.dwje.api.model.request.UnlockCompleteRequest
import com.dwje.api.model.request.UnlockVerifyRequest
import com.dwje.api.repository.AuditLogRepository
import com.dwje.api.repository.AuthRepository
import com.dwje.api.repository.DataFieldRepository
import com.dwje.api.repository.EmailVerificationRepository
import com.dwje.api.service.AuditLogService
import com.dwje.api.service.AuthService
import com.dwje.api.service.AuthorizationService
import com.dwje.api.service.EmailVerificationService
import com.dwje.api.service.SystemDeptGuard
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock

/**
 * 계정 전환 감사(09 AUD-02)와 이메일 잠금 해제 꺼짐(E-AUTH-007) — 서비스 규약.
 *
 * 1. 전환 성공은 ACCOUNT_SEC/ALLOW, 거부(허용 대상 아님·정지·없음)는 ACCOUNT_SEC/REJECT 를 남긴다
 * 2. 전환 토큰에는 원래 관리자 사번(impBy)이 실린다 — 전환 상태의 감사 기록에 [대행:사번] 을 붙이는 근거
 * 3. 이메일 잠금 해제가 꺼져 있으면(dev·prod 기본) 잠금 해제 3경로가 503 E-AUTH-007
 */
class AccountSwitchAuditTest {

    @AfterEach
    fun clear() = UserContext.clear()

    private class MemAudit : AuditLogService(mock(AuditLogRepository::class.java), mock(AuthorizationService::class.java)) {
        val rows = mutableListOf<Triple<String, String?, String>>()
        override fun record(logType: String, menuId: String?, fieldKey: String?, targetDesc: String?, resultCd: String, maskedCnt: Int, remark: String?): Long? {
            rows += Triple(logType, targetDesc, resultCd); return null
        }
    }

    private val tokens = JwtTokenProvider(JwtProperties(secret = "test-only-jwt-signing-secret-123456"))
    private val repo = mock(AuthRepository::class.java)
    private val audit = MemAudit()

    private fun service(emailEnabled: Boolean = true) = AuthService(
        repo, AuthorizationService(repo), tokens, AppProperties(), PasswordEncoderService(PasswordProperties()), audit,
        mock(EmailVerificationService::class.java), mock(EmailVerificationRepository::class.java), mock(DataFieldRepository::class.java),
        SystemDeptGuard(AppProperties()), AccountUnlockProperties(emailEnabled = emailEnabled)
    )

    private fun target(id: String, switchable: Boolean, state: String = "ACTIVE") = mapOf<String, Any?>(
        "userId" to id, "userName" to "대상", "deptId" to 2, "deptName" to "품질보증팀", "superAdmin" to false,
        "switchable" to switchable, "userStateCd" to state, "plantCd" to null, "positionCd" to null
    )

    private fun loginAdmin() = UserContext.set(UserPrincipal("10000", "관리자", 1, "통합관리자", null, null, true))

    @Test
    @DisplayName("전환 성공 — ACCOUNT_SEC/ALLOW 1행, 토큰에 원래 관리자 사번")
    fun switchSuccessIsAudited() {
        loginAdmin()
        doReturn(target("10001", switchable = true)).`when`(repo).findUserWithDept("10001")
        val res = service().switchAccount(SwitchAccountRequest("10001"))
        assertEquals(listOf(Triple("ACCOUNT_SEC", "계정 전환 [10000 → 10001]", "ALLOW")), audit.rows)
        val claims = tokens.parse(res.accessToken)
        assertEquals(true, claims[JwtTokenProvider.CLAIM_IMPERSONATED])
        assertEquals("10000", claims[JwtTokenProvider.CLAIM_IMPERSONATED_BY])
    }

    @Test
    @DisplayName("전환 거부 — 허용 대상 아님·정지 계정은 4xx 와 함께 ACCOUNT_SEC/REJECT")
    fun switchRejectIsAudited() {
        loginAdmin()
        doReturn(target("10002", switchable = false)).`when`(repo).findUserWithDept("10002")
        doReturn(target("10003", switchable = true, state = "SUSPENDED")).`when`(repo).findUserWithDept("10003")
        assertThrows(BusinessRuleException::class.java) { service().switchAccount(SwitchAccountRequest("10002")) }
        assertThrows(BusinessRuleException::class.java) { service().switchAccount(SwitchAccountRequest("10003")) }
        assertEquals(listOf("REJECT", "REJECT"), audit.rows.map { it.third })
        assertEquals(listOf("ACCOUNT_SEC", "ACCOUNT_SEC"), audit.rows.map { it.first })
    }

    @Test
    @DisplayName("이메일 잠금 해제가 꺼져 있으면 잠금 해제 3경로 모두 503 E-AUTH-007")
    fun unlockDisabled() {
        val svc = service(emailEnabled = false)
        val e = assertThrows(UnlockUnavailableException::class.java) { svc.requestUnlock("10001") }
        assertEquals(ErrorCode.AUTH_UNLOCK_UNAVAILABLE, e.errorCode)
        assertEquals(503, e.errorCode.status.value())
        assertThrows(UnlockUnavailableException::class.java) { svc.verifyUnlock(UnlockVerifyRequest("10001", "000000")) }
        assertThrows(UnlockUnavailableException::class.java) { svc.completeUnlock(UnlockCompleteRequest("t", "a", "a")) }
    }

    @Test
    @DisplayName("T-G09 /auth/me — 미배정 소속이면 최상위 unassigned 와 dept.unassigned 가 true, 초기 비밀번호 경로에서도 같다")
    fun myInfoUnassigned() {
        UserContext.set(UserPrincipal("20250311", "미배정", 59, "미배정", null, null, false,
            menuPerms = setOf("dash-ai"), unassigned = true))
        val me = service().getMyInfo()
        assertEquals(true, me.unassigned); assertEquals(true, me.dept.unassigned)

        UserContext.set(UserPrincipal("20250311", "미배정", 59, "미배정", null, null, false,
            unassigned = true, pwdChangeRequired = true))
        assertEquals(true, service().getMyInfo().unassigned)

        UserContext.set(UserPrincipal("10001", "품질", 2, "품질보증팀", null, null, false))
        assertEquals(false, service().getMyInfo().unassigned)
    }
}
