package com.dwje.api

import com.dwje.api.common.exception.BusinessRuleException
import com.dwje.api.common.exception.ResourceNotFoundException
import com.dwje.api.common.exception.WriteAccessDeniedException
import com.dwje.api.common.security.UserContext
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.common.validation.CodeValidator
import com.dwje.api.repository.AuditLogRepository
import com.dwje.api.repository.AuthRepository
import com.dwje.api.repository.SyncRepository
import com.dwje.api.service.AgentRunRecorder
import com.dwje.api.service.AuditLogService
import com.dwje.api.service.AuthorizationService
import com.dwje.api.service.SyncService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * 이관 작업 재실행 규칙 — 12 기획서 SYN-02 (DB 없이)
 *
 * 1. 실패·중단 작업만, 진행 중 재실행이 있으면 409, 사용 중지 매핑 409, 없는 작업 404
 * 2. 새 작업은 원 작업을 retry_of_job_id 로 가리키고, 이후 정상 완료가 있으면 supersededBy 로 알린다
 * 3. 감사: 성공 ALLOW, 규칙 거부 REJECT, 권한 거부 REJECT
 */
class SyncServiceRetryRuleTest {

    private class MemRepo : SyncRepository(mock(NamedParameterJdbcTemplate::class.java)) {
        val jobs = mutableMapOf(
            "F1" to RetryTarget("F1", 1, "FAIL", "FULL", "Y"),
            "A1" to RetryTarget("A1", 2, "ABORTED", "INCR", "Y"),
            "D1" to RetryTarget("D1", 1, "DONE", "FULL", "Y"),
            "R1" to RetryTarget("R1", 3, "RUNNING", "FULL", "Y"),
            "OFF" to RetryTarget("OFF", 9, "FAIL", "FULL", "N")
        )
        val retries = mutableMapOf<String, MutableList<Pair<String, String>>>()   // 원 작업 → (새 작업, 상태)
        var superseded: Map<String, Any?>? = null
        override fun lockJobForRetry(jobId: String) = jobs[jobId]
        override fun findActiveRetry(jobId: String) = retries[jobId]?.firstOrNull { it.second in setOf("PENDING", "RUNNING") }?.first
        override fun findSupersededBy(jobId: String) = superseded
        override fun insertRetryJob(sourceJobId: String, triggeredBy: String): String {
            val id = "SYNC-${retries.values.sumOf { it.size } + 1}"
            retries.getOrPut(sourceJobId) { mutableListOf() } += id to "PENDING"
            return id
        }
    }

    private class MemAudit : AuditLogService(mock(AuditLogRepository::class.java), mock(AuthorizationService::class.java)) {
        val rows = mutableListOf<Pair<String?, String>>()
        override fun record(logType: String, menuId: String?, fieldKey: String?, targetDesc: String?, resultCd: String, maskedCnt: Int, remark: String?): Long? {
            rows += targetDesc to resultCd; return null
        }
    }

    private val repo = MemRepo()
    private val audit = MemAudit()
    private val service = SyncService(repo, AuthorizationService(mock(AuthRepository::class.java)), audit,
        mock(AgentRunRecorder::class.java), mock(CodeValidator::class.java))

    @BeforeEach
    fun login() = UserContext.set(UserPrincipal("10004", "전산", 5, "전산팀", null, null, null, false,
        menuPerms = setOf("sys-sync"), writePerms = setOf("sys-sync")))

    @AfterEach
    fun clear() = UserContext.clear()

    @Test
    @DisplayName("실패·중단 작업은 재실행 — 새 작업이 원 작업을 가리키고, 이후 정상 완료가 있으면 supersededBy")
    fun retriesFailedAndAborted() {
        repo.superseded = mapOf("jobId" to "MIG-260930-12", "endedAt" to "2026-09-30 12:25:50")
        val r = service.retryJob("F1")
        assertEquals("PENDING", r["state"]); assertEquals("SYNC-1", r["newJobId"])
        assertEquals("MIG-260930-12", (r["supersededBy"] as Map<*, *>)["jobId"])
        assertEquals(listOf("SYNC-1" to "PENDING"), repo.retries["F1"])
        repo.superseded = null
        assertNull(service.retryJob("A1")["supersededBy"])
        assertEquals(listOf("ALLOW", "ALLOW"), audit.rows.map { it.second })
    }

    @Test
    @DisplayName("규칙 거부 409 — 상태·진행 중 재실행·사용 중지 매핑, 각각 REJECT 감사 / 없는 작업 404")
    fun rejects() {
        val state = assertThrows(BusinessRuleException::class.java) { service.retryJob("D1") }
        assertEquals("실패하거나 중단된 작업만 재실행할 수 있습니다. [state=DONE]", state.message)
        assertThrows(BusinessRuleException::class.java) { service.retryJob("R1") }
        service.retryJob("F1")
        val dup = assertThrows(BusinessRuleException::class.java) { service.retryJob("F1") }
        assertEquals("이미 재실행이 예약되어 있습니다. [newJobId=SYNC-1]", dup.message)
        val off = assertThrows(BusinessRuleException::class.java) { service.retryJob("OFF") }
        assertEquals("사용 중지된 이관 정의라 재실행할 수 없습니다. [map_id=9]", off.message)
        assertThrows(ResourceNotFoundException::class.java) { service.retryJob("NOPE") }
        assertEquals(listOf("REJECT", "REJECT", "ALLOW", "REJECT", "REJECT"), audit.rows.map { it.second })
        assertEquals(1, repo.retries.values.sumOf { it.size }, "거부되면 새 작업이 생기지 않는다")
    }

    @Test
    @DisplayName("조회만 있으면 403 E-AUTH-004, 새 작업 없음 — 거부 기록은 전역 예외 처리기(ACCESS_DENIED)가 남겨 서비스는 따로 남기지 않는다")
    fun readOnly() {
        UserContext.set(UserPrincipal("10001", "품질", 2, "품질보증팀", null, null, null, false, menuPerms = setOf("sys-sync")))
        assertThrows(WriteAccessDeniedException::class.java) { service.retryJob("F1") }
        assertTrue(repo.retries.isEmpty()); assertTrue(audit.rows.isEmpty(), "거부가 두 줄로 남지 않는다 (09 AUD-10)")
    }

    @Test
    @DisplayName("화면 판정 retryVerdict — 목록 SQL 과 같은 규칙: 완료된 재실행도 막고 사유를 준다")
    fun verdict() {
        assertEquals(true to null, service.retryVerdict("FAIL", "Y", emptyList()))
        assertEquals(false, service.retryVerdict("DONE", "Y", emptyList()).first)
        assertEquals(false to "재실행이 이미 예약되어 있습니다. [S1]",
            service.retryVerdict("FAIL", "Y", listOf(mapOf("jobId" to "S1", "state" to "PENDING"))))
        assertEquals(false to "이미 재실행되어 완료되었습니다. [S2]",
            service.retryVerdict("ABORTED", "Y", listOf(mapOf("jobId" to "S2", "state" to "RETRY_DONE"))))
        assertEquals(false to "사용 중지된 이관 정의입니다.", service.retryVerdict("FAIL", "N", emptyList()))
        // 목록 SQL 이 같은 상태·완료 재실행 조건을 쓰는지
        listOf("'FAIL','ABORTED'", "m.use_flg = 'Y'", "'PENDING','RUNNING','DONE','RETRY_DONE'").forEach {
            assertTrue(SyncRepository.RETRYABLE_SQL.contains(it), it)
        }
    }
}
