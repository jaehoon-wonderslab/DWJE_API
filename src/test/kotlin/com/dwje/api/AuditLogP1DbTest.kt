package com.dwje.api

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.exception.MenuAccessDeniedException
import com.dwje.api.common.exception.DataAccessDeniedException
import com.dwje.api.common.exception.GlobalExceptionHandler
import com.dwje.api.common.security.UserContext
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.model.request.ListExportRequest
import com.dwje.api.repository.AuditArchiveRepository
import com.dwje.api.repository.AuditLogRepository
import com.dwje.api.repository.AuditLogRepository.AuditFilter
import com.dwje.api.service.AiAdminService
import com.dwje.api.service.AuditLogService
import com.dwje.api.service.AuditRetentionService
import com.dwje.api.service.AuthorizationService
import com.dwje.api.service.DownloadLogService
import com.dwje.api.service.ExportService
import com.dwje.api.service.ListExportService
import com.dwje.api.service.SyncService
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * 09 AUD-05~12·17 — 실제 로컬 DB, 롤백.
 *
 * 감사 표는 지울 수 없어(V51) 빈의 AuditLogService 는 목으로 두고, 조회·열람 기록은 직접 만든 서비스(트랜잭션 관리자 없음 →
 * 테스트 트랜잭션 안에서 INSERT, 롤백)로 시험한다.
 */
@SpringBootTest(properties = ["app.ai.prewarm-enabled=false"])
@ActiveProfiles("local")
@Transactional
class AuditLogP1DbTest {

    @Autowired lateinit var auditRepo: AuditLogRepository
    @Autowired lateinit var archiveRepo: AuditArchiveRepository
    @Autowired lateinit var authz: AuthorizationService
    @Autowired lateinit var jdbc: NamedParameterJdbcTemplate
    @Autowired lateinit var downloadLogService: DownloadLogService
    @Autowired lateinit var syncService: SyncService
    @Autowired lateinit var aiAdminService: AiAdminService
    @Autowired lateinit var exportService: ExportService
    @Autowired lateinit var retention: AuditRetentionService
    @MockitoBean lateinit var audit: AuditLogService

    private val admin = UserPrincipal("10000", "관리자", 1, "통합관리자", null, null, null, true)
    private lateinit var service: AuditLogService

    @BeforeEach
    fun setUp() {
        UserContext.set(admin)
        service = AuditLogService(auditRepo, authz)
    }

    @AfterEach
    fun clear() = UserContext.clear()

    private fun long(sql: String) = jdbc.queryForObject(sql, MapSqlParameterSource(), Long::class.java)!!
    private fun exec(sql: String) = jdbc.update(sql, MapSqlParameterSource())

    @Test
    @DisplayName("AUD-05 응답 키(src·menuId·menuNm·fieldKey·maskedCnt)·안정 정렬, AUD-12 검증(결과 MASKED·유형·IP·366일)")
    fun contractAndValidation() {
        val (p1, meta) = service.getAuditLogs(null, null, null, null, null, 1, 20)
        assertTrue(p1.isNotEmpty())
        assertEquals(
            setOf("id", "src", "ts", "name", "ua", "type", "empNo", "dept", "menuId", "menuNm", "fieldKey", "maskedCnt", "target", "detail", "result", "ip"),
            p1.first().keys
        )
        val (p2, _) = service.getAuditLogs(null, null, null, null, null, 2, 20)
        if (meta.total > 20) assertTrue(p1.map { it["id"] }.intersect(p2.map { it["id"] }.toSet()).isEmpty())

        service.getAuditLogs(null, null, null, null, null, 1, 5, AuditFilter(result = "MASKED"))
        service.getAuditLogs(null, null, null, null, null, 1, 5, AuditFilter(ip = "10.0.0.0/8"))
        service.getAuditLogs(null, null, null, null, null, 1, 5, AuditFilter(ip = "127.0."))
        assertEquals("result", assertThrows(InvalidParameterException::class.java) {
            service.getAuditLogs(null, null, null, null, null, 1, 5, AuditFilter(result = "X"))
        }.field)
        assertEquals("type", assertThrows(InvalidParameterException::class.java) {
            service.getAuditLogs(null, null, "LOGIN,NOPE", null, null, 1, 5)
        }.field)
        assertEquals("ip", assertThrows(InvalidParameterException::class.java) {
            service.getAuditLogs(null, null, null, null, null, 1, 5, AuditFilter(ip = "abc"))
        }.field)
        assertEquals("조회 기간은 최대 366일입니다.", assertThrows(InvalidParameterException::class.java) {
            service.getAuditLogs("2025-01-01", "2026-01-02", null, null, null, 1, 5)
        }.message)

        val (two, _) = service.getAuditLogs("2026-09-01", null, "LOGIN,PERM_CHANGE", null, null, 1, 200)
        assertTrue(two.all { it["type"] in setOf("LOGIN", "PERM_CHANGE") })
    }

    @Test
    @DisplayName("AUD-06 감사 행과 이어진 권한 변경은 한 줄(A-)만, 이어지지 않은 옛 행은 P- 로 그대로 / AUD-10 로그아웃 O- 행")
    fun permLinkAndLogout() {
        val auditId = auditRepo.insert("PERM_CHANGE", "10000", "통합관리자", "sys-menu", null, "ZTAUD 연결", "ALLOW", 0, "ZTAUD", null)
        auditRepo.insertPermLog("MENU_PERM", "MENU", null, null, "ZTAUD 연결", "ZTAUD 연결 상세", "10000", "통합관리자", auditId)
        auditRepo.insertPermLog("MENU_PERM", "MENU", null, null, "ZTAUD 단독", "ZTAUD 단독 상세", "10000", "통합관리자")
        val (rows, _) = service.getAuditLogs(null, null, null, null, null, 1, 50, AuditFilter(keyword = "ZTAUD"))
        assertEquals(listOf("A-$auditId"), rows.filter { it["target"] == "ZTAUD 연결" }.map { it["id"] })
        assertEquals(listOf("PERM"), rows.filter { it["target"] == "ZTAUD 단독" }.map { it["src"] })
        assertEquals(auditId, long("SELECT audit_id FROM ax.tb_sys_perm_log WHERE target_nm = 'ZTAUD 연결'"))

        exec("INSERT INTO ax.tb_sys_login_hist (user_id, login_at, logout_at, result_cd, ip_addr, user_agent) VALUES ('10000', now() - interval '1 hour', now(), 'SUCCESS', '10.9.9.9', 'ZTUA')")
        val (login, _) = service.getAuditLogs(null, null, "LOGIN", null, "10000", 1, 20, AuditFilter(ip = "10.9.9.9"))
        assertEquals(setOf("로그인", "로그아웃"), login.map { it["target"] }.toSet())
        assertTrue(login.single { it["target"] == "로그아웃" }["id"].toString().startsWith("O-"))
        val (noSuccess, _) = service.getAuditLogs(null, null, "LOGIN", null, "10000", 1, 20, AuditFilter(ip = "10.9.9.9", excludeLoginSuccess = true))
        assertTrue(noSuccess.isEmpty(), "로그인 성공 제외는 로그아웃도 뺀다")
    }

    @Test
    @DisplayName("AUD-08 첫 쪽 조회만 AUDIT_VIEW 1행, 기본 목록은 AUDIT_VIEW 를 빼고 type=AUDIT_VIEW 로만 본다 / AUD-09 EXPORT 는 옛 내려받기 RAW_VIEW 도")
    fun auditView() {
        val before = long("SELECT count(*) FROM ax.tb_log_audit WHERE log_type_cd = 'AUDIT_VIEW'")
        service.getAuditLogs(null, null, "LOGIN", null, null, 1, 5, AuditFilter(keyword = "x"))
        assertEquals(before + 1, long("SELECT count(*) FROM ax.tb_log_audit WHERE log_type_cd = 'AUDIT_VIEW'"))
        assertEquals("유형 LOGIN · 검색어 x", jdbc.queryForObject(
            "SELECT remark FROM ax.tb_log_audit WHERE log_type_cd = 'AUDIT_VIEW' ORDER BY audit_id DESC LIMIT 1", MapSqlParameterSource(), String::class.java))
        service.getAuditLogs(null, null, null, null, null, 2, 5)
        assertEquals(before + 1, long("SELECT count(*) FROM ax.tb_log_audit WHERE log_type_cd = 'AUDIT_VIEW'"), "2쪽은 남기지 않는다")
        assertThrows(InvalidParameterException::class.java) { service.getAuditLogs(null, null, "NOPE", null, null, 1, 5) }
        assertEquals(before + 1, long("SELECT count(*) FROM ax.tb_log_audit WHERE log_type_cd = 'AUDIT_VIEW'"), "400 은 남기지 않는다")

        val (def, _) = service.getAuditLogs(null, null, null, null, "10000", 1, 200)
        assertFalse(def.any { it["type"] == "AUDIT_VIEW" })
        val (only, _) = service.getAuditLogs(null, null, "AUDIT_VIEW", null, "10000", 1, 200)
        assertTrue(only.isNotEmpty() && only.all { it["type"] == "AUDIT_VIEW" })

        auditRepo.insert("RAW_VIEW", "10000", "통합관리자", "qc-defect", null, "ZT 옛 내려받기", "ALLOW", 0, "다운로드 형식=XLSX, 행수=1", null)
        auditRepo.insert("RAW_VIEW", "10000", "통합관리자", "chat-history", null, "ZT 질의 열람", "ALLOW", 0, "남의 기록", null)
        val (exp, _) = service.getAuditLogs(null, null, "EXPORT", null, "10000", 1, 200)
        assertTrue(exp.any { it["target"] == "ZT 옛 내려받기" })
        assertFalse(exp.any { it["target"] == "ZT 질의 열람" })
    }

    @Test
    @DisplayName("AUD-07 전체 내려받기 — 조건 무관 전체(AUDIT_VIEW 포함), 열람 기록 없음, scope=VIEW 400, 서버 condSummary")
    fun exportAll() {
        val recorder = RecordingDownloadLogService()
        val export = ListExportService(service, recorder, syncService, aiAdminService, exportService)
        assertEquals("scopeCd", assertThrows(InvalidParameterException::class.java) {
            export.auditLogs(ListExportRequest(scope = "VIEW"))
        }.field)
        val viewBefore = long("SELECT count(*) FROM ax.tb_log_audit WHERE log_type_cd = 'AUDIT_VIEW'")
        val expected = service.getAuditLogs("2000-01-01", null, null, null, null, 1, 1, exporting = true).second.total
        val file = export.auditLogs(ListExportRequest(scope = "ALL", from = "2026-09-30", to = "2026-09-30", keyword = "zzz"))
        XSSFWorkbook(file.body!!.inputStream).use { wb ->
            val sheet = wb.getSheetAt(0)
            assertEquals(minOf(expected, 50_000L), sheet.lastRowNum.toLong(), "조건을 줘도 전체")
            assertEquals(listOf("일시", "유형", "사번", "이름", "부서", "대상", "처리 결과", "비고", "IP", "접속 환경", "ID"),
                (0 until 11).map { sheet.getRow(0).getCell(it).stringCellValue })
        }
        assertEquals(viewBefore, long("SELECT count(*) FROM ax.tb_log_audit WHERE log_type_cd = 'AUDIT_VIEW'"), "내려받기는 열람 기록을 남기지 않는다")
        val call = recorder.calls.single()
        assertEquals("sys-audit", call.menuId); assertEquals("ALL", call.scopeCd)
        assertEquals("전체 · 최근 순 · 상한 50,000건", call.condSummary)
    }

    @Test
    @DisplayName("AUD-11 아카이브 — 기준 시각 이전 행만 원본에서 아카이브 표로(다운로드는 비공개 내역 함께), 보존 정책 응답(R-20 켬 — 다음 실행 시각 있음)")
    fun archive() {
        exec("INSERT INTO ax.tb_log_audit (log_at, log_type_cd, user_id, target_desc, result_cd, masked_cnt) VALUES ('2000-01-01', 'AUTO_GEN', 'ZT-ARCH', 'ZT 아카이브', 'ALLOW', 0)")
        exec("INSERT INTO ax.tb_rpt_download_log (downloaded_at, user_id, target_nm, format_cd, row_cnt, blind_cnt, result_cd) VALUES ('2000-01-02', 'ZT-ARCH', 'ZT 아카이브', 'XLSX', 1, 3, 'DONE')")
        val dlId = long("SELECT dl_id FROM ax.tb_rpt_download_log WHERE user_id = 'ZT-ARCH'")
        exec("INSERT INTO ax.tb_rpt_download_blind (dl_id, field_key, cell_cnt) VALUES ($dlId, 'qty', 2), ($dlId, 'price', 1)")
        val totalBefore = long("SELECT count(*) FROM ax.tb_log_audit")
        val cutoff = OffsetDateTime.of(2001, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)

        assertTrue(archiveRepo.tryLock())
        assertEquals(1, archiveRepo.archiveBefore(AuditArchiveRepository.Source.AUDIT, cutoff, 10))
        assertEquals(totalBefore - 1, long("SELECT count(*) FROM ax.tb_log_audit"))
        assertEquals(1L, long("SELECT count(*) FROM ax.tb_log_audit_arch WHERE user_id = 'ZT-ARCH' AND archived_at IS NOT NULL"))
        assertEquals(1, archiveRepo.archiveBefore(AuditArchiveRepository.Source.DOWNLOAD, cutoff, 10))
        assertEquals(0L, long("SELECT count(*) FROM ax.tb_rpt_download_log WHERE dl_id = $dlId"))
        assertEquals(2L, long("SELECT count(*) FROM ax.tb_rpt_download_blind_arch WHERE dl_id = $dlId"))
        assertEquals(0, archiveRepo.archiveBefore(AuditArchiveRepository.Source.PERM, cutoff, 10))

        val policy = retention.getRetentionPolicy()
        // R-20 으로 배치를 켰다 — 다음 실행 시각이 있다
        assertEquals(true, policy["enabled"]); assertNotNull(policy["nextArchiveAt"]); assertEquals(3, policy["retentionYears"])
        @Suppress("UNCHECKED_CAST")
        assertEquals(listOf("AUDIT", "PERM", "LOGIN"), (policy["sources"] as List<Map<String, Any?>>).map { it["src"] })
        val dl = downloadLogService.getRetentionPolicy()
        assertEquals(true, dl["enabled"]); assertNotNull(dl["nextArchiveAt"]); assertEquals(dl["expiredCnt"], dl["archiveTargetCnt"])
        assertEquals(long("SELECT count(*) FROM ax.tb_rpt_download_log_arch"), dl["archivedCnt"])
    }

    @Test
    @DisplayName("AUD-10 권한 거부는 ACCESS_DENIED · REJECT 1행, 같은 사람·대상은 60초 안에 다시 남기지 않는다")
    fun accessDenied() {
        val recorder = mock(AuditLogService::class.java)
        val handler = GlobalExceptionHandler().also { it.auditLogService = recorder }
        val req = MockHttpServletRequest("GET", "/api/v1/audit-logs")
        fun calls() = mockingDetails(recorder).invocations.count { it.method.name == "record" }
        handler.recordAccessDenied(MenuAccessDeniedException("sys-audit"), req, now = 1_000)
        handler.recordAccessDenied(MenuAccessDeniedException("sys-audit"), req, now = 30_000)
        assertEquals(1, calls())
        handler.recordAccessDenied(DataAccessDeniedException("price"), req, now = 30_000)
        assertEquals(2, calls())
        handler.recordAccessDenied(MenuAccessDeniedException("sys-audit"), req, now = 62_000)
        assertEquals(3, calls())
        handler.recordAccessDenied(InvalidParameterException("x", "y"), req, now = 62_000)
        assertEquals(3, calls(), "권한 거부가 아니면 남기지 않는다")
        val args = mockingDetails(recorder).invocations.first { it.method.name == "record" }.arguments
        assertEquals("ACCESS_DENIED", args[0]); assertEquals("sys-audit", args[1]); assertEquals("GET /api/v1/audit-logs", args[3]); assertEquals("REJECT", args[4])
    }
}
