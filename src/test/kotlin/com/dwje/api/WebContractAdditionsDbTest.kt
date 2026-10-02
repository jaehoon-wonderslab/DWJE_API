package com.dwje.api

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.security.UserContext
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.model.request.ListExportRequest
import com.dwje.api.repository.AuditLogRepository
import com.dwje.api.service.AiAdminService
import com.dwje.api.service.AlertConfigService
import com.dwje.api.service.AuditLogService
import com.dwje.api.service.DashboardUploadService
import com.dwje.api.service.DownloadLogService
import com.dwje.api.service.ExportService
import com.dwje.api.service.ListExportService
import com.dwje.api.service.SyncService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.annotation.Transactional

/**
 * 메인 전달(WEB 계약) 으로 더한 조회·내려받기 — 실제 로컬 DB, 롤백.
 * 다운로드 이력·감사 표는 지울 수 없으므로(V51·V62) 기록 서비스는 목으로 바꿔 쓰지 않는다.
 */
@SpringBootTest(properties = ["app.ai.prewarm-enabled=false"])
@ActiveProfiles("local")
@Transactional
class WebContractAdditionsDbTest {

    @Autowired lateinit var auditRepo: AuditLogRepository
    @Autowired lateinit var authz: com.dwje.api.service.AuthorizationService
    @Autowired lateinit var alertConfig: AlertConfigService
    @Autowired lateinit var aiAdmin: AiAdminService
    @Autowired lateinit var uploads: DashboardUploadService
    @Autowired lateinit var sync: SyncService
    @Autowired lateinit var exportService: ExportService
    @Autowired lateinit var jdbc: NamedParameterJdbcTemplate
    @MockitoBean lateinit var downloadLog: DownloadLogService
    // 감사 표는 지울 수 없다(V51) — 빈은 목, 감사 조회는 트랜잭션 관리자 없이 직접 만든 서비스(열람 기록이 테스트 롤백 안에 남는다)
    @MockitoBean lateinit var auditMock: AuditLogService
    private val auditLogs by lazy { AuditLogService(auditRepo, authz) }
    private val listExport by lazy { ListExportService(auditLogs, downloadLog, sync, aiAdmin, exportService) }

    private val admin = UserPrincipal("10000", "관리자", 1, "통합관리자", null, null, true)

    @BeforeEach
    fun login() = UserContext.set(admin)

    @AfterEach
    fun clear() = UserContext.clear()

    @Test
    @DisplayName("감사 로그 — 행에 id·name·ua, 로그인 성공 제외·결과 값 검증")
    fun auditFilters() {
        val (rows, meta) = auditLogs.getAuditLogs("2026-01-01", null, null, null, null, 1, 50,
            AuditLogRepository.AuditFilter(excludeLoginSuccess = true))
        assertTrue(rows.none { it["type"] == "LOGIN" && it["result"] == "ALLOW" })
        rows.firstOrNull()?.let { assertTrue((it["id"] as String).matches(Regex("[APL]-\\d+")) && "name" in it && "ua" in it) }
        val (all, allMeta) = auditLogs.getAuditLogs("2026-01-01", null, null, null, null, 1, 1)
        assertTrue(allMeta.total >= meta.total)
        assertEquals("result", assertThrows(InvalidParameterException::class.java) {
            auditLogs.getAuditLogs(null, null, null, null, null, 1, 10, AuditLogRepository.AuditFilter(result = "OK"))
        }.field)
    }

    @Test
    @DisplayName("전체 내려받기 — 상한 안이면 헤더 없음, 넘으면 X-Export-Truncated·X-Export-Total, 연동 대상 코드 검증")
    fun exportHeaders() {
        val file = listExport.auditLogs(ListExportRequest(scope = "ALL", menuId = "sys-audit", from = "2026-09-01"))
        assertTrue(file.body!!.contentLength() > 0)
        val big = exportService.withExportTotals(file, 12_000, 10_000)
        assertEquals("true", big.headers.getFirst("X-Export-Truncated"))
        assertEquals("12000", big.headers.getFirst("X-Export-Total"))
        assertNull(exportService.withExportTotals(file, 10, 10).headers.getFirst("X-Export-Truncated"))
        assertEquals("target", assertThrows(InvalidParameterException::class.java) {
            listExport.sync(ListExportRequest(target = "LOGS"))
        }.field)
        listExport.chatHistory(ListExportRequest(view = "SESSION", from = "2026-09-15"))
    }

    @Test
    @DisplayName("수신자 후보 — 미배정·이미 수신자인 계정 제외")
    fun candidates() {
        val (data, _) = alertConfig.getRecipientCandidates(null, null, 100)
        @Suppress("UNCHECKED_CAST") val items = data["items"] as List<Map<String, Any?>>
        assertTrue(items.none { it["dept"] == "미배정" })
        val recipients = jdbc.queryForList("SELECT user_id FROM ax.tb_alm_recipient", MapSqlParameterSource(), String::class.java)
        assertTrue(items.none { it["empNo"] in recipients })
    }

    @Test
    @DisplayName("질의 이력 부서 목록 · 업로드 요약 · 연동 요약 alert")
    fun summaries() {
        @Suppress("UNCHECKED_CAST")
        val groups = aiAdmin.getChatHistoryGroups(java.time.LocalDate.now().minusDays(60).toString(), null)["items"] as List<Map<String, Any?>>
        assertTrue(groups.all { "dept" in it && "cnt" in it })
        val extras = uploads.adminListExtras(null, null, null, null)
        assertTrue("total" in (extras["summary"] as Map<*, *>))
        assertTrue(extras["uploaders"] is List<*>)
        val alert = sync.getSummary(null)["alert"] as Map<*, *>
        assertTrue("condCnt" in alert && "openAlertCnt" in alert)
    }
}
