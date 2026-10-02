package com.dwje.api

import com.dwje.api.common.security.UserContext
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.common.util.ClientIpResolver
import com.dwje.api.repository.AuditLogRepository
import com.dwje.api.service.AuditLogService
import com.dwje.api.service.AuthorizationService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus

/** 09 AUD-13 — 감사 기록 실패는 본 업무로 번지지 않고 실패 건수에 들어간다. DB 를 쓰지 않는다 */
class AuditWriteFailTest {

    @AfterEach
    fun clear() = UserContext.clear()

    /** 모든 쓰기가 실패하는 저장소 */
    private class FailingRepo : AuditLogRepository(mock(NamedParameterJdbcTemplate::class.java)) {
        override fun insert(
            logTypeCd: String, userId: String?, deptNm: String?, menuId: String?, fieldKey: String?, targetDesc: String?,
            resultCd: String, maskedCnt: Int, remark: String?, ipAddr: String?, userAgent: String?
        ): Long = throw DataAccessResourceFailureException("db down")

        override fun insertPermLog(
            actCd: String, targetKindCd: String, targetDeptId: Int?, targetUserId: String?, targetNm: String, detail: String,
            actorUserId: String, actorDeptNm: String?, auditId: Long?
        ): Long = throw DataAccessResourceFailureException("db down")
    }

    /** 트랜잭션을 열지 못하는 관리자 — 커넥션 풀 고갈과 같은 상황 */
    private class NoTxManager : PlatformTransactionManager {
        override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = throw CannotCreateTransactionException("no connection")
        override fun commit(status: TransactionStatus) {}
        override fun rollback(status: TransactionStatus) {}
    }

    private fun service(tm: PlatformTransactionManager? = null) =
        AuditLogService(FailingRepo(), mock(AuthorizationService::class.java), ClientIpResolver(), tm)

    @Test
    @DisplayName("저장소 실패 — record·recordPermChange·recordPermChangeAs·recordAfterCommit·recordAuditView 모두 예외 없이 끝나고 각각 1건씩 센다")
    fun repoFailures() {
        UserContext.set(UserPrincipal("10000", "관리자", 1, "통합관리자", null, null, null, true))
        val s = service()
        assertNull(assertDoesNotThrow<Long?> { s.record("AUTO_GEN", "sys-audit") })
        assertEquals(1, s.writeFailSinceBoot)
        // 연결 감사 행 실패 1 + 권한 이력 실패 1
        assertDoesNotThrow { s.recordPermChange("ACCOUNT", "USER", "x", "y") }
        assertEquals(3, s.writeFailSinceBoot)
        assertDoesNotThrow { s.recordPermChangeAs("SYSTEM", "ACCOUNT", "USER", "x", "y", auditId = 1L) }
        assertEquals(4, s.writeFailSinceBoot)
        assertDoesNotThrow { s.recordAfterCommit("CONFIG_CHANGE", "sys-gloss", "x") }
        assertEquals(5, s.writeFailSinceBoot)
        assertDoesNotThrow { s.recordAuditView("감사 로그 조회", "") }
        assertEquals(6, s.writeFailSinceBoot)
    }

    @Test
    @DisplayName("트랜잭션을 열지 못해도(커넥션 획득 실패) 호출자에게 예외가 나가지 않고 센다")
    fun transactionOpenFailure() {
        val s = service(NoTxManager())
        assertNull(assertDoesNotThrow<Long?> { s.record("AUTO_GEN", "sys-audit") })
        assertEquals(1, s.writeFailSinceBoot)
    }
}
