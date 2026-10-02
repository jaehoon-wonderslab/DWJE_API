package com.dwje.api

import com.dwje.api.repository.DownloadLogRepository
import com.dwje.api.repository.DownloadLogRepository.LogFilter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.time.LocalDate

/**
 * 다운로드 이력 목록 필터 — 실제 로컬 DB (10 기획서 DLG-01·02·03·15)
 *
 * 먼 과거 날짜(2001-01-0x)에 시험 행을 직접 넣고 그 기간만 조회한다. 다운로드 이력 표는 V62 로 UPDATE·DELETE 가
 * 막혀 있어 지울 수 없으므로 테스트마다 트랜잭션을 롤백한다. 서비스를 거치지 않으므로 감사 로그도 남지 않는다.
 */
@SpringBootTest(properties = ["app.ai.prewarm-enabled=false"])
@ActiveProfiles("local")
@org.springframework.transaction.annotation.Transactional
class DownloadLogFilterContractTest {

    @Autowired lateinit var repo: DownloadLogRepository
    @Autowired lateinit var jdbc: NamedParameterJdbcTemplate

    private val day = LocalDate.of(2001, 1, 2)

    private fun row(deptNm: String, deptId: Int?, reportId: String?, menuId: String?, format: String, scopeCd: String?) {
        jdbc.update(
            """
            INSERT INTO ax.tb_rpt_download_log (downloaded_at, user_id, dept_nm, dept_id, report_id, menu_id, target_nm,
                                                format_cd, row_cnt, blind_cnt, result_cd, scope_cd)
            VALUES ('2001-01-02 10:00+09', 'ZT-DL', :deptNm, :deptId, :reportId, :menuId, '시험', :format, 1, 0, 'DONE', :scopeCd)
            """.trimIndent(),
            MapSqlParameterSource().addValue("deptNm", deptNm).addValue("deptId", deptId).addValue("reportId", reportId)
                .addValue("menuId", menuId).addValue("format", format).addValue("scopeCd", scopeCd)
        )
    }

    @BeforeEach
    fun seed() {
        row("전산팀", 5, null, "prod-result", "XLSX", "ALL")          // 새 서버 기록
        row("전산팀", null, "prod-result", null, "엑셀 (.XLS)", null)   // 옛 브라우저 기록 — report_id 에 화면 ID, 표시명 형식
        row("품질보증팀", 2, "RPT_DAILY_PROD", "prod-daily", "CSV", "VIEW") // 보고서 정의
        row("제조팀", 4, null, "qc-defect", "XLS", "VIEW")
    }

    private fun find(f: LogFilter) = repo.findLogs(day, day, f, 100, 0)

    @Test
    @DisplayName("부서 ID — 새 기록은 dept_id, 옛 기록은 그 부서의 지금 이름으로 잡는다 · 숫자가 아니면 부서명")
    fun deptFilter() {
        assertEquals(2, find(LogFilter(dept = "5")).size)
        assertEquals(2L, repo.countLogs(day, day, LogFilter(dept = "5")))
        assertEquals(1, find(LogFilter(dept = "품질보증팀")).size)
        assertEquals(setOf(5, null), find(LogFilter(dept = "5")).map { it["deptId"] }.toSet(), "새 기록만 부서 ID 가 있다")
    }

    @Test
    @DisplayName("화면 ID — 서버 menu_id 와 옛 report_id 화면 ID 를 함께 잡고, 응답에 menuId·menuNm")
    fun menuFilter() {
        val rows = find(LogFilter(menuId = "prod-result"))
        assertEquals(2, rows.size)
        assertTrue(rows.all { it["menuId"] == "prod-result" && it["menuNm"] != null })
        assertEquals("prod-daily", find(LogFilter(reportId = "RPT_DAILY_PROD")).single()["menuId"])
    }

    @Test
    @DisplayName("형식 — 옛 표시명 「엑셀 (.XLS)」 도 XLS 로 걸리고 응답은 코드 + 원문")
    fun formatFilter() {
        val xls = find(LogFilter(format = "xls"))
        assertEquals(2, xls.size)
        val legacy = xls.single { it["formatRaw"] == "엑셀 (.XLS)" }
        assertEquals("XLS", legacy["format"])
        assertEquals(1, find(LogFilter(format = "XLSX")).size)
    }

    @Test
    @DisplayName("범위 코드 — VIEW·ALL·UNKNOWN(코드 없는 옛 기록)")
    fun scopeFilter() {
        assertEquals(2, find(LogFilter(scopeCd = "VIEW")).size)
        assertEquals(1, find(LogFilter(scopeCd = "all")).size)
        assertEquals(1, find(LogFilter(scopeCd = "UNKNOWN")).size)
    }
}
