package com.dwje.api

import com.dwje.api.repository.SyncRepository
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.LocalDate

/** 연동 상태·재실행 판정 SQL 이 실제 로컬 DB 에서 도는지 (12 SYN-02·03·05). 조회만 한다. */
@SpringBootTest(properties = ["app.ai.prewarm-enabled=false"])
@ActiveProfiles("local")
class SyncSqlSmokeTest {

    @Autowired lateinit var repo: SyncRepository

    private val ipv4 = Regex("""\b\d{1,3}(\.\d{1,3}){3}\b""")

    @Test
    @DisplayName("상태 판정 재료·작업 목록 retryable·실행 메시지 가림이 실제 DB 에서 돈다")
    fun runs() {
        val stats = repo.findHealthStats(10)
        assertTrue(stats.openFailJobCnt >= 0 && stats.stalePendingCnt >= 0)
        assertFalse(stats.lastRun?.get("message")?.toString()?.let { ipv4.containsMatchIn(it) } ?: false)

        val jobs = repo.findJobs(LocalDate.now().minusDays(60), LocalDate.now(), null, null, 50, 0)
        jobs.forEach { assertNotNull(it["retryable"]) }
        repo.findRuns(LocalDate.now().minusDays(60), LocalDate.now(), null, null, 200, 0).forEach {
            assertFalse(ipv4.containsMatchIn(it["message"]?.toString() ?: ""), "실행 메시지에 IPv4 가 남았다: ${it["runId"]}")
        }
        jobs.firstOrNull()?.let { repo.findSupersededBy(it["jobId"] as String); repo.findRetriedBy(it["jobId"] as String) }
    }
}
