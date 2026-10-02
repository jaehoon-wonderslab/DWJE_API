package com.dwje.api

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.exception.MenuAccessDeniedException
import com.dwje.api.common.exception.SystemErrorException
import com.dwje.api.common.security.UserContext
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.common.util.ReportFormat
import com.dwje.api.config.AppProperties
import com.dwje.api.config.DownloadLogProperties
import com.dwje.api.model.request.DownloadLogRecordRequest
import com.dwje.api.repository.AuditLogRepository
import com.dwje.api.repository.AuthRepository
import com.dwje.api.repository.DownloadLogRepository
import com.dwje.api.service.AuditLogService
import com.dwje.api.service.AuthorizationService
import com.dwje.api.service.DownloadLogService
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.validation.Validation
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * 다운로드 이력 기록 — 10 기획서 DLG-02·03·05·15 (DB 없이)
 *
 * 1. 브라우저 신고는 그 화면의 **조회** 권한으로 판정한다(쓰기 권한 아님, 공통 9.8). 없는 화면 400, 권한 없음 403
 * 2. 기록 실패는 500 — 화면은 기록 뒤에만 파일을 저장한다(기록 선행). 서버 기록은 실패를 삼킨다
 * 3. 출처 CLIENT/SERVER, 부서 ID 스냅샷, 화면 ID 를 reportId 에 넣던 옛 신고 호환, RPT_* 보정
 * 4. 형식 코드 6종 정규화(옛 표시명 포함), 범위 코드 VIEW/ALL, 옛 scope 문구는 조건 요약으로
 */
class DownloadLogRecordGuardTest {

    @AfterEach
    fun clear() = UserContext.clear()

    /** insert 인자를 모으는 저장소 — 화면 rpt-ship-plan 은 보고서 RPT_SHIP_PLAN */
    private class MemRepo : DownloadLogRepository(mock(NamedParameterJdbcTemplate::class.java), ObjectMapper()) {
        val rows = mutableListOf<Map<String, Any?>>()
        var fail = false
        private val menus = setOf("rpt-ship-plan", "sys-dl", "qc-defect", "dash-ai")
        override fun menuExists(menuId: String) = menuId in menus
        override fun findReportIdByMenu(menuId: String) = if (menuId == "rpt-ship-plan") "RPT_SHIP_PLAN" else null
        override fun findMenuByReportId(reportId: String) = if (reportId == "RPT_SHIP_PLAN") "rpt-ship-plan" else null
        override fun insertBlindDetail(dlId: Long, blindCells: Map<String, Int>) {}
        override fun insert(userId: String, deptNm: String?, reportId: String?, menuId: String?, targetNm: String, formatCd: String,
                            scopeDesc: String?, rowCnt: Int, blindCnt: Int, ipAddr: String?, fileNm: String?, paramsJson: String?,
                            fileSize: Long?, originCd: String?, deptId: Int?, scopeCd: String?, condSummary: String?): Long {
            if (fail) throw IllegalStateException("DB 오류")
            rows += mapOf("reportId" to reportId, "menuId" to menuId, "formatCd" to formatCd, "scopeDesc" to scopeDesc,
                "originCd" to originCd, "deptId" to deptId, "scopeCd" to scopeCd, "condSummary" to condSummary, "blindCnt" to blindCnt)
            return rows.size.toLong()
        }
    }

    private val repo = MemRepo()
    private fun service(requireMenuId: Boolean = false) = DownloadLogService(
        repo, mock(AuditLogService::class.java), AuthorizationService(mock(AuthRepository::class.java)),
        AppProperties(downloadLog = DownloadLogProperties(requireMenuId = requireMenuId)), ObjectMapper()
    )

    /** 제조팀 — 출하 계획 화면 조회 권한만(쓰기 없음) */
    private fun login() = UserContext.set(UserPrincipal("10003", "제조", 4, "제조팀", null, null, false,
        menuPerms = setOf("rpt-ship-plan")))

    @Test
    @DisplayName("조회 권한만 있으면 기록 200 — 출처 CLIENT · 부서 ID · 보고서 정의 ID 보정 · 범위 코드 · 조건 요약")
    fun recordsWithReadPermission() {
        login()
        val id = service().recordFromClient(DownloadLogRecordRequest(menuId = "rpt-ship-plan", reportNm = "출하계획_2026",
            format = "CSV", scopeCd = "view", condSummary = "2026년 · 고객사 전체", rowCnt = 48, blindCnt = 2))
        assertEquals(1L, id)
        val r = repo.rows.single()
        assertEquals("CLIENT", r["originCd"]); assertEquals(4, r["deptId"]); assertEquals("RPT_SHIP_PLAN", r["reportId"])
        assertEquals("VIEW", r["scopeCd"]); assertEquals("2026년 · 고객사 전체", r["condSummary"])
        assertEquals("2026년 · 고객사 전체", r["scopeDesc"], "새 신고는 조건 요약 앞 100자를 범위 문구에도 둔다")
        assertEquals("CSV", r["formatCd"])
    }

    @Test
    @DisplayName("없는 화면 400(field=menuId) · 조회 권한 없는 화면 403 E-AUTH-002")
    fun guardsMenu() {
        login()
        val e = assertThrows(InvalidParameterException::class.java) {
            service().recordFromClient(DownloadLogRecordRequest(menuId = "abc"))
        }
        assertEquals("menuId", e.field); assertEquals("화면 ID 가 올바르지 않습니다.", e.message)
        assertThrows(MenuAccessDeniedException::class.java) { service().recordFromClient(DownloadLogRecordRequest(menuId = "sys-dl")) }
        assertTrue(repo.rows.isEmpty())
    }

    @Test
    @DisplayName("옛 신고 호환 — reportId 의 화면 ID 는 menuId 로, RPT_* 는 화면을 찾아 권한 확인, menuId 없는 신고는 설정이 꺼진 동안 기록")
    fun legacyCompatibility() {
        login()
        service().recordFromClient(DownloadLogRecordRequest(reportId = "rpt-ship-plan", format = "엑셀 (.XLSX)", scope = "2026년"))
        val r = repo.rows.last()
        assertEquals("rpt-ship-plan", r["menuId"]); assertEquals("RPT_SHIP_PLAN", r["reportId"]); assertEquals("XLSX", r["formatCd"])
        assertNull(r["scopeCd"]); assertEquals("2026년", r["condSummary"], "옛 scope 문구는 조건 요약으로")

        service().recordFromClient(DownloadLogRecordRequest(reportId = "RPT_SHIP_PLAN"))
        assertEquals("rpt-ship-plan", repo.rows.last()["menuId"])

        service().recordFromClient(DownloadLogRecordRequest(reportNm = "옛 화면"))
        assertNull(repo.rows.last()["menuId"])
        assertThrows(InvalidParameterException::class.java) { service(requireMenuId = true).recordFromClient(DownloadLogRecordRequest()) }
    }

    @Test
    @DisplayName("형식 목록 밖·범위 코드 목록 밖은 400 — 각각 field=format · scopeCd")
    fun rejectsBadCodes() {
        login()
        val f = assertThrows(InvalidParameterException::class.java) {
            service().recordFromClient(DownloadLogRecordRequest(menuId = "rpt-ship-plan", format = "docx"))
        }
        assertEquals("format", f.field); assertEquals("지원하지 않는 형식입니다. [XLS, XLSX, CSV, PDF, PNG, JSONL]", f.message)
        val s = assertThrows(InvalidParameterException::class.java) {
            service().recordFromClient(DownloadLogRecordRequest(menuId = "rpt-ship-plan", scopeCd = "PAGE"))
        }
        assertEquals("scopeCd", s.field)
    }

    @Test
    @DisplayName("기록 실패는 브라우저 신고에서 500(E-SERVER), 서버 기록은 0 으로 삼킨다 · 서버 기록은 출처 SERVER · 범위 ALL")
    fun failureAndServerOrigin() {
        login()
        repo.fail = true
        val e = assertThrows(SystemErrorException::class.java) {
            service().recordFromClient(DownloadLogRecordRequest(menuId = "rpt-ship-plan"))
        }
        assertEquals("내려받기 기록에 실패했습니다.", e.message)
        assertEquals(0L, service().record(null, "x", "rpt-ship-plan", "xlsx", "from=1", 1, 0))

        repo.fail = false
        service().record(null, "불량", "qc-defect", ReportFormat.XLSX, "from=2026-09-01", 10, 20, mapOf("qty" to 10, "yield" to 10))
        val r = repo.rows.last()
        assertEquals("SERVER", r["originCd"]); assertEquals("ALL", r["scopeCd"]); assertEquals("from=2026-09-01", r["condSummary"])
        assertEquals(4, r["deptId"])
    }

    @Test
    @DisplayName("형식 코드 정규화 — 소문자·옛 표시명·png·jsonl, 목록 밖 null, 서버 파일은 csv 외 xlsx")
    fun formatNormalize() {
        assertEquals("XLS", ReportFormat.normalize("xls")); assertEquals("XLS", ReportFormat.normalize("엑셀 (.XLS)"))
        assertEquals("XLSX", ReportFormat.normalize(" xlsx ")); assertEquals("XLSX", ReportFormat.normalize("엑셀 (.xlsx)"))
        assertEquals("CSV", ReportFormat.normalize("CSV (.csv)")); assertEquals("PDF", ReportFormat.normalize("인쇄 · PDF"))
        assertEquals("PNG", ReportFormat.normalize("png")); assertEquals("JSONL", ReportFormat.normalize("jsonl"))
        assertNull(ReportFormat.normalize("docx")); assertNull(ReportFormat.normalize(" "))
        assertEquals("XLSX", ReportFormat.ofServerExport("xls")); assertEquals("CSV", ReportFormat.ofServerExport("CSV"))
    }

    @Test
    @DisplayName("본문 길이 — reportNm 201자·scope 101자·condSummary 501자는 400")
    fun bodyLimits() {
        val v = Validation.buildDefaultValidatorFactory().validator
        assertEquals(setOf("reportNm"), v.validate(DownloadLogRecordRequest(reportNm = "가".repeat(201))).map { it.propertyPath.toString() }.toSet())
        assertEquals(setOf("scope"), v.validate(DownloadLogRecordRequest(scope = "가".repeat(101))).map { it.propertyPath.toString() }.toSet())
        assertEquals(setOf("condSummary"), v.validate(DownloadLogRecordRequest(condSummary = "가".repeat(501))).map { it.propertyPath.toString() }.toSet())
        assertTrue(v.validate(DownloadLogRecordRequest(reportNm = "가".repeat(200))).isEmpty())
    }
}
