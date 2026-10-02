package com.dwje.api

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.security.UserContext
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.model.request.ListExportRequest
import com.dwje.api.repository.DownloadLogRepository.LogFilter
import com.dwje.api.service.AuditLogService
import com.dwje.api.service.DashboardUploadService
import com.dwje.api.service.DownloadLogService
import com.dwje.api.service.ListExportService
import com.dwje.api.service.SyncService
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mockingDetails
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.annotation.Transactional

/** 10 DLG-06~11·15 · 11 UPD-04~08 · 12 SYN-04·06~09·15 — 실제 로컬 DB, 롤백. 감사는 목 */
@SpringBootTest(properties = ["app.ai.prewarm-enabled=false"])
@ActiveProfiles("local")
@Transactional
class DownloadUploadSyncP1DbTest {

    @Autowired lateinit var downloads: DownloadLogService
    @Autowired lateinit var uploads: DashboardUploadService
    @Autowired lateinit var sync: SyncService
    @Autowired lateinit var aiAdminService: com.dwje.api.service.AiAdminService
    @Autowired lateinit var exportService: com.dwje.api.service.ExportService
    @Autowired lateinit var jdbc: NamedParameterJdbcTemplate
    @MockitoBean lateinit var audit: AuditLogService

    private val admin = UserPrincipal("10000", "관리자", 1, "통합관리자", null, null, null, true)
    private lateinit var recorder: RecordingDownloadLogService
    private lateinit var export: ListExportService

    @BeforeEach
    fun login() {
        UserContext.set(admin)
        // 다운로드 이력 기록은 롤백되지 않으므로(REQUIRES_NEW·V62) 기록 인자만 받는다
        recorder = RecordingDownloadLogService(downloads)
        export = ListExportService(audit, recorder, sync, aiAdminService, exportService)
    }

    @AfterEach
    fun clear() = UserContext.clear()

    private fun long(sql: String) = jdbc.queryForObject(sql, MapSqlParameterSource(), Long::class.java)!!
    private fun exec(sql: String) = jdbc.update(sql, MapSqlParameterSource())
    private fun auditViews() = mockingDetails(audit).invocations.count { it.method.name == "recordAuditView" }
    private fun rowsOf(file: org.springframework.http.ResponseEntity<org.springframework.core.io.ByteArrayResource>) =
        XSSFWorkbook(file.body!!.inputStream).use { it.getSheetAt(0).lastRowNum }

    private fun seedDownloads(): Long {
        exec("""INSERT INTO ax.tb_rpt_download_log (downloaded_at, user_id, dept_nm, dept_id, menu_id, target_nm, format_cd, row_cnt, blind_cnt, result_cd, origin_cd, scope_cd)
                VALUES ('2001-01-02', 'ZT-DL', 'ZT', 1, 'qc-defect', 'ZT 보고서', 'XLSX', 5, 4, 'DONE', 'SERVER', 'ALL'),
                       ('2001-01-02', 'ZT-DL', 'ZT', 1, 'qc-defect', 'ZT 보고서', 'XLSX', 5, 0, 'DONE', 'CLIENT', 'VIEW'),
                       ('2001-01-02', 'ZT-DL', 'ZT', 2, 'prod-result', 'ZT 보고서', 'CSV', 5, 0, 'DONE', NULL, NULL)""")
        val id = long("SELECT min(dl_id) FROM ax.tb_rpt_download_log WHERE user_id = 'ZT-DL'")
        exec("INSERT INTO ax.tb_rpt_download_blind (dl_id, field_key, cell_cnt) VALUES ($id, 'qty', 3), ($id, 'price', 2)")
        return id
    }

    @Test
    @DisplayName("DLG-06·09 요약 카드 = 목록 건수(같은 조건), 사번·생성 경로 필터, 366일·코드 검증 / DLG-11 첫 쪽·상세만 열람 기록")
    fun downloadFilters() {
        seedDownloads()
        listOf(
            LogFilter(empNo = "ZT-DL"), LogFilter(empNo = "ZT-DL", menuId = "qc-defect"), LogFilter(empNo = "ZT-DL", origin = "UNKNOWN"),
            LogFilter(empNo = "ZT-DL", origin = "SERVER"), LogFilter(empNo = "ZT-DL", scopeCd = "VIEW"), LogFilter(empNo = "ZT-DL", dept = "2")
        ).forEach { f ->
            val total = downloads.getLogs("2001-01-01", "2001-01-31", f, 1, 10).second.total
            assertEquals(total, downloads.getSummary("2001-01-01", "2001-01-31", f)["totalCnt"], f.toString())
        }
        assertEquals(3L, downloads.getLogs("2001-01-01", "2001-01-31", LogFilter(empNo = "ZT-DL"), 1, 10).second.total)
        assertEquals(1L, downloads.getLogs("2001-01-01", "2001-01-31", LogFilter(empNo = "ZT-DL", origin = "UNKNOWN"), 1, 10).second.total)
        assertEquals("origin", assertThrows(InvalidParameterException::class.java) {
            downloads.getLogs(null, null, LogFilter(origin = "X"), 1, 10)
        }.field)
        assertEquals("조회 기간은 366일 이내여야 합니다.", assertThrows(InvalidParameterException::class.java) {
            downloads.getLogs("2025-01-01", "2026-01-02", LogFilter(), 1, 10)
        }.message)

        clearInvocations(audit)
        downloads.getLogs("2001-01-01", "2001-01-31", LogFilter(empNo = "ZT-DL"), 1, 10)
        downloads.getLogs("2001-01-01", "2001-01-31", LogFilter(empNo = "ZT-DL"), 2, 10)
        assertEquals(1, auditViews(), "2쪽은 남기지 않는다")
        val viewArgs = mockingDetails(audit).invocations.first { it.method.name == "recordAuditView" }.arguments
        assertEquals("사번 ZT-DL", viewArgs[1]); assertEquals("sys-dl", viewArgs[2])
    }

    @Test
    @DisplayName("DLG-10 상세 — 항목별 칸 합·불일치·기준(CELL/LEGACY) / DLG-08 전체 내려받기 — 조건 무관 전체, 열람 기록 없음, 서버 condSummary")
    fun downloadDetailAndExport() {
        val id = seedDownloads()
        val d = downloads.getLog(id)
        assertEquals(5, d["blindCellSum"]); assertEquals(true, d["blindMismatch"]); assertEquals("CELL", d["blindBasis"])
        val legacy = long("SELECT dl_id FROM ax.tb_rpt_download_log WHERE user_id = 'ZT-DL' AND origin_cd IS NULL")
        assertEquals("LEGACY", downloads.getLog(legacy)["blindBasis"])
        assertEquals(false, downloads.getLog(legacy)["blindMismatch"])

        clearInvocations(audit)
        val total = long("SELECT count(*) FROM ax.tb_rpt_download_log")
        val file = export.downloadLogs(ListExportRequest(scope = "ALL", from = "2026-09-30", to = "2026-09-30", keyword = "zzz", menuId = "qc-defect"))
        assertEquals(total.toInt(), rowsOf(file))
        assertEquals(0, auditViews(), "내려받기는 열람 기록을 남기지 않는다")
        val call = recorder.calls.single()
        assertEquals("sys-dl", call.menuId, "화면 ID 는 서버가 정한다")
        assertEquals("전체 · 최근 순 · 상한 50,000건", call.condSummary)
        assertEquals(total.toInt(), call.rowCnt)
    }

    @Test
    @DisplayName("UPD-06·07·08 — 저장소 현황은 조건 무관 전체, 업로더 docCnt, 검색어 숫자는 문서 ID, 사번은 정확 일치, 행·버전 fileState·내부 경로 없음")
    fun uploads() {
        val all = uploads.adminListExtras(null, null, null, null)
        val none = uploads.adminListExtras(null, "없는검색어zz", "2030-01-01", "2030-01-02")
        @Suppress("UNCHECKED_CAST")
        val s = all["summary"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val s2 = none["summary"] as Map<String, Any?>
        listOf("docCnt", "versionCnt", "totalBytes", "failDocCnt", "lastUploadedAt", "maxBytesPerFile").forEach { assertEquals(s[it], s2[it], it) }
        assertEquals(long("SELECT count(*) FROM ax.tb_dash_upload_ver v JOIN ax.tb_dash_upload_doc d USING (doc_id) WHERE d.del_flg = 'N'"), s["versionCnt"])
        assertEquals(0L, s2["total"], "상태별 수는 같은 조건 기준(2단계 계약)")
        @Suppress("UNCHECKED_CAST")
        val uploaders = all["uploaders"] as List<Map<String, Any?>>
        assertTrue(uploaders.all { "docCnt" in it && "userId" in it && "empNo" in it })

        val (byId, _) = uploads.listDocsForAdmin(keyword = "1")
        assertTrue(byId.any { it["docId"] == 1L })
        assertTrue(byId.all { "fileState" in it && "storagePath" !in it })
        assertTrue(byId.all { it["fileState"] in setOf("OK", "MISSING", "SIZE_MISMATCH") })
        val owner = jdbc.queryForObject("SELECT ins_user FROM ax.tb_dash_upload_ver ORDER BY doc_id, ver LIMIT 1", MapSqlParameterSource(), String::class.java)!!
        val (byEmp, _) = uploads.listDocsForAdmin(uploadedBy = owner)
        assertEquals(long("SELECT count(DISTINCT v.doc_id) FROM ax.tb_dash_upload_ver v JOIN ax.tb_dash_upload_doc d USING (doc_id) WHERE d.del_flg = 'N' AND d.latest_ver > 0 AND v.ins_user = '$owner'"), byEmp.size.toLong())
        // 사번 형식(숫자)은 정확 일치만 — 사번 앞부분으로는 아무것도 찾지 않는다(이름 부분 일치로 넘어가지 않음)
        if (owner.length > 1 && long("SELECT count(*) FROM ax.tb_sys_user WHERE user_id = '${owner.dropLast(1)}'") == 0L) {
            assertEquals(0, uploads.listDocsForAdmin(uploadedBy = owner.dropLast(1)).first.size)
        }

        @Suppress("UNCHECKED_CAST")
        val versions = uploads.listVersions(1)["items"] as List<Map<String, Any?>>
        assertTrue(versions.isNotEmpty() && versions.all { it["fileState"] in setOf("OK", "MISSING", "SIZE_MISMATCH") && "storagePath" !in it })
    }

    @Test
    @DisplayName("SYN-08·09 — 대기 작업은 기간 밖이어도 기본 포함(includePending=false 면 제외), 원본 테이블 대소문자·스키마 무시, runId, 실행 구분 source")
    fun syncLists() {
        val pending = "SYNC-260922161043-2"
        val (def, _) = sync.getJobs("2026-09-30", "2026-09-30", null, null, 1, 50)
        assertTrue(def.any { it["jobId"] == pending })
        val (noPending, _) = sync.getJobs("2026-09-30", "2026-09-30", null, null, 1, 50, includePending = false)
        assertFalse(noPending.any { it["jobId"] == pending })
        val (fail, _) = sync.getJobs("2026-09-01", "2026-09-30", null, "FAIL", 1, 200)
        assertTrue(fail.all { it["state"] == "FAIL" })

        val a = sync.getJobs("2026-08-01", "2026-09-30", "tb_md_item", null, 1, 5).second.total
        val b = sync.getJobs("2026-08-01", "2026-09-30", "dbo.TB_MD_ITEM", null, 1, 5).second.total
        assertEquals(a, b); assertTrue(a > 0)

        val runId = "RUN-20260904-141723"
        assertEquals(long("SELECT count(*) FROM ax.tb_sync_job WHERE run_id = '$runId'"),
            sync.getJobs("2026-08-01", "2026-09-30", null, null, 1, 5, runId = runId).second.total)

        val (mes, _) = sync.getRuns("2026-08-01", "2026-10-01", null, null, 1, 500, source = "MES")
        assertTrue(mes.isNotEmpty() && mes.none { it["mode"] == "GROUPWARE" })
        val (gw, _) = sync.getRuns("2026-08-01", "2026-10-01", null, null, 1, 500, source = "groupware")
        assertTrue(gw.all { it["mode"] == "GROUPWARE" })
        assertEquals("source", assertThrows(InvalidParameterException::class.java) { sync.getRuns(null, null, null, null, 1, 10, source = "X") }.field)
    }

    @Test
    @DisplayName("SYN-07 상세 — runId·scheduledAt·요청자·errorTotal·srcKey/payload / SYN-04 실패 알림 조건은 지표로 찾는다 / SYN-15 전체 내려받기")
    fun syncDetailPolicyExport() {
        val jobId = jdbc.queryForObject("SELECT job_id FROM ax.tb_sync_job WHERE run_id IS NOT NULL ORDER BY job_id LIMIT 1", MapSqlParameterSource(), String::class.java)!!
        exec("INSERT INTO ax.tb_sync_job_error (job_id, err_seq, err_code, err_msg, src_key, payload, resolved, ins_date) VALUES ('$jobId', 1, 'ZT', 'jdbc:sqlserver://10.1.2.3:1433 실패', 'K1', '{\"a\":1}', false, now())")
        val d = sync.getJob(jobId)
        @Suppress("UNCHECKED_CAST")
        val job = d["job"] as Map<String, Any?>
        assertNotNull(job["runId"]); assertTrue("scheduledAt" in job && "triggeredByUser" in job && "triggeredByName" in job)
        assertEquals(1L, d["errorTotal"])
        @Suppress("UNCHECKED_CAST")
        val err = (d["errors"] as List<Map<String, Any?>>).single()
        assertEquals("K1", err["srcKey"]); assertEquals("{\"a\": 1}", err["payload"])
        assertFalse((err["message"] as String).contains("10.1.2.3"))

        val metricId = long("SELECT metric_id FROM ax.tb_met_metric_std WHERE metric_cd = 'SYNC_STALE_MIN'")
        exec(
            "INSERT INTO ax.tb_alm_cond (cond_nm, severity_cd, metric_desc, op_cd, threshold_text, duration_cd, target_scope_cd, target_desc, window_cd, dedup_cd, msg_template, metric_id) " +
                "SELECT 'ZT 지연 조건', severity_cd, metric_desc, op_cd, threshold_text, duration_cd, target_scope_cd, target_desc, window_cd, dedup_cd, msg_template, $metricId FROM ax.tb_alm_cond ORDER BY cond_id LIMIT 1"
        )
        assertEquals(long("SELECT cond_id FROM ax.tb_alm_cond WHERE cond_nm = 'ZT 지연 조건'").toInt(), sync.getPolicy()["failAlertCondId"])

        assertEquals("target", assertThrows(InvalidParameterException::class.java) { export.sync(ListExportRequest(scope = "ALL")) }.field)
        assertEquals("scopeCd", assertThrows(InvalidParameterException::class.java) { export.sync(ListExportRequest(scope = "VIEW", target = "JOBS")) }.field)
        val file = export.sync(ListExportRequest(scope = "ALL", target = "JOBS", from = "2026-09-30", to = "2026-09-30"))
        assertEquals(long("SELECT count(*) FROM ax.tb_sync_job").toInt(), rowsOf(file))
        XSSFWorkbook(file.body!!.inputStream).use { wb ->
            val head = wb.getSheetAt(0).getRow(0).let { r -> (0 until r.lastCellNum).map { r.getCell(it).stringCellValue } }
            assertTrue(head.containsAll(listOf("정합성", "실행 경로", "요청자", "실패 원인")))
        }
        val runs = export.sync(ListExportRequest(scope = "ALL", target = "RUNS"))
        assertEquals(long("SELECT count(*) FROM ax.tb_sync_run").toInt(), rowsOf(runs))
        assertEquals(listOf("sys-sync", "sys-sync"), recorder.calls.map { it.menuId })
        assertEquals(listOf("JOBS", "RUNS"), recorder.calls.map { it.params?.get("target") })
    }
}
