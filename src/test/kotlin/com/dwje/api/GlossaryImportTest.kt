package com.dwje.api

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.exception.MenuAccessDeniedException
import com.dwje.api.common.exception.WriteAccessDeniedException
import com.dwje.api.common.security.UserContext
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.common.util.MenuId
import com.dwje.api.service.AuditLogService
import com.dwje.api.service.GlossaryImportWorkbook
import com.dwje.api.service.GlossaryService
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
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
import java.io.ByteArrayOutputStream

/** 용어 사전 엑셀 업로드·템플릿 — 실제 로컬 DB, 롤백 */
@SpringBootTest(properties = ["app.ai.prewarm-enabled=false"])
@ActiveProfiles("local")
@Transactional
class GlossaryImportTest {

    @Autowired lateinit var service: GlossaryService
    @Autowired lateinit var jdbc: NamedParameterJdbcTemplate
    @MockitoBean lateinit var audit: AuditLogService

    private val admin = UserPrincipal("10000", "관리자", 1, "통합관리자", null, null, true)
    private val writer = UserPrincipal("10002", "박생산", 3, "생산팀", null, null, false, menuPerms = setOf(MenuId.SYS_GLOSS))
    private val viewer = UserPrincipal("10003", "조회", 3, "생산팀", null, null, false, menuPerms = setOf(MenuId.GLOSS_VIEW))

    @BeforeEach
    fun login() = UserContext.set(admin)

    @AfterEach
    fun clear() = UserContext.clear()

    private fun long(sql: String) = jdbc.queryForObject(sql, MapSqlParameterSource(), Long::class.java)!!
    private fun counts() = listOf(
        long("SELECT count(*) FROM ax.tb_gls_term"), long("SELECT count(*) FROM ax.tb_gls_variant"),
        long("SELECT count(*) FROM ax.tb_gls_change_log")
    )

    private fun xlsx(header: List<String> = GlossaryImportWorkbook.HEADERS, vararg rows: List<String>): ByteArray =
        XSSFWorkbook().use { wb ->
            val s = wb.createSheet("용어")
            s.createRow(0).let { r -> header.forEachIndexed { i, h -> r.createCell(i).setCellValue(h) } }
            rows.forEachIndexed { i, cells -> s.createRow(i + 1).let { r -> cells.forEachIndexed { c, v -> r.createCell(c).setCellValue(v) } } }
            ByteArrayOutputStream().use { wb.write(it); it.toByteArray() }
        }

    @Suppress("UNCHECKED_CAST")
    private fun rows(res: Map<String, Any?>) = res["rows"] as List<Map<String, Any?>>

    private val sample = arrayOf(
        listOf("8D", "무시되는 뜻", "기타", "ZT팔디보고, ZT팔디"),          // 2행 기존 용어 — 유사어만 더함
        listOf("ZT새용어", "업로드로 넣은 뜻", "품질관리", "ZT새말1\nZT새말2, 8디"), // 3행 새 용어 — 8디는 이미 8D 의 유사어
        listOf("ZT새용어", "", "", "ZT새말3, ZT새말1"),                   // 4행 같은 파일 앞 행 용어 — ZT새말1 은 파일 안 중복
        listOf("ZT분류없음", "뜻", "없는분류", "ZT말"),                     // 5행 분류 오류
        listOf("", "", "", ""),                                         // 빈 행 — 건너뜀
        listOf("(예시) 수율", "예시", "기타", "양품률")                      // 예시 행 — 건너뜀
    )

    @Test
    @DisplayName("미리보기는 아무것도 쓰지 않고, 등록은 미리보기와 같은 결과로 오류 행만 빼고 쓴다")
    fun dryRunThenCommit() {
        val before = counts()
        val dry = service.importTerms(xlsx(rows = sample), "a.xlsx", true)
        assertEquals(before, counts(), "dryRun 은 쓰지 않는다")
        assertEquals(4, dry["totalRows"]); assertEquals(1, dry["termNew"]); assertEquals(2, dry["termExisting"])
        assertEquals(5, dry["variantNew"]); assertEquals(2, dry["variantSkipped"]); assertEquals(1, dry["errorCnt"])
        val r = rows(dry)
        assertEquals(listOf(2, 3, 4, 5), r.map { it["row"] })
        assertEquals(listOf("EXISTING_TERM", "NEW_TERM", "EXISTING_TERM", "ERROR"), r.map { it["action"] })
        assertEquals(listOf("ZT팔디보고", "ZT팔디"), r[0]["variantsAdded"])
        assertTrue((r[0]["notes"] as List<*>).contains("기존 용어 — 뜻·분류는 바꾸지 않음"))
        assertEquals(listOf("ZT새말1", "ZT새말2"), r[1]["variantsAdded"])
        assertEquals("8디", ((r[1]["variantsSkipped"] as List<*>)[0] as Map<*, *>)["word"])
        assertEquals(listOf("ZT새말3"), r[2]["variantsAdded"])
        assertEquals("같은 파일 3행과 중복", ((r[2]["variantsSkipped"] as List<*>)[0] as Map<*, *>)["reason"])
        assertEquals("domain", ((r[3]["errors"] as List<*>)[0] as Map<*, *>)["field"])

        val done = service.importTerms(xlsx(rows = sample), "a.xlsx", false)
        assertEquals(rows(dry).map { it["variantsAdded"] }, rows(done).map { it["variantsAdded"] }, "미리보기와 등록이 같은 판정")
        val after = counts()
        assertEquals(before[0] + 1, after[0]); assertEquals(before[1] + 5, after[1])
        assertEquals(before[2] + 6, after[2], "변경 이력 = 새 용어 1 + 유사어 5")
        val newId = rows(done)[1]["termId"] as Int
        assertEquals(newId, rows(done)[2]["termId"])
        assertEquals(3L, long("SELECT count(*) FROM ax.tb_gls_variant WHERE term_id = $newId"))
    }

    @Test
    @DisplayName("기존 용어의 뜻·분류는 파일 값으로 바뀌지 않는다(대소문자 무시)")
    fun existingTermUntouched() {
        val sql = "SELECT term_def || '|' || domain_id FROM ax.tb_gls_term WHERE term_id = 5"
        val before = jdbc.queryForObject(sql, MapSqlParameterSource(), String::class.java)
        val res = service.importTerms(xlsx(rows = arrayOf(listOf("8d", "다른 뜻", "기타", "ZT팔디보고"))), "a.xlsx", false)
        assertEquals(5, rows(res)[0]["termId"])
        assertEquals(before, jdbc.queryForObject(sql, MapSqlParameterSource(), String::class.java))
    }

    @Test
    @DisplayName("통합관리자가 아니면 새 용어 행은 ERROR, 기존 용어 유사어는 등록 · 조회 권한만이면 403")
    fun writerRules() {
        UserContext.set(writer)
        val res = service.importTerms(xlsx(rows = arrayOf(listOf("8D", "", "", "ZT팔디보고"), listOf("ZT새용어", "뜻", "기타", "ZT새말"))), "a.xlsx", false)
        val r = rows(res)
        assertEquals("EXISTING_TERM", r[0]["action"]); assertEquals(listOf("ZT팔디보고"), r[0]["variantsAdded"])
        assertEquals("ERROR", r[1]["action"])
        assertEquals("공식 용어 등록은 통합관리자만 할 수 있습니다.", ((r[1]["errors"] as List<*>)[0] as Map<*, *>)["message"])
        assertEquals("10002", jdbc.queryForObject(
            "SELECT owner_user_id FROM ax.tb_gls_variant WHERE word = 'ZT팔디보고'", MapSqlParameterSource(), String::class.java))

        UserContext.set(viewer)
        assertThrows(MenuAccessDeniedException::class.java) { service.importTerms(xlsx(rows = sample), "a.xlsx", true) }
        assertThrows(MenuAccessDeniedException::class.java) { service.importTemplate() }
        UserContext.set(writer.copy(unassigned = true))
        assertThrows(WriteAccessDeniedException::class.java) { service.importTerms(xlsx(rows = sample), "a.xlsx", true) }
    }

    @Test
    @DisplayName("머리글이 다르면·xlsx 가 아니면·행이 넘치면 400")
    fun fileRules() {
        val e = assertThrows(InvalidParameterException::class.java) {
            service.importTerms(xlsx(listOf("용어", "뜻", "분류", "유사어"), listOf("a", "b", "c", "d")), "a.xlsx", true)
        }
        assertTrue(e.message!!.startsWith("템플릿의 머리글과 다릅니다"))
        assertThrows(InvalidParameterException::class.java) { service.importTerms("a,b".toByteArray(), "a.xlsx", true) }
        assertThrows(InvalidParameterException::class.java) { service.importTerms(xlsx(rows = sample), "a.csv", true) }
        val many = Array(GlossaryImportWorkbook.MAX_ROWS + 1) { listOf("8D", "", "", "") }
        assertThrows(InvalidParameterException::class.java) { service.importTerms(xlsx(rows = many), "a.xlsx", true) }
        // 필수 표시(*)·앞뒤 공백은 무시한다
        service.importTerms(xlsx(listOf(" 공식 용어 ", "뜻", "분류", "유사어"), listOf("8D", "", "", "")), "a.xlsx", true)
    }

    @Test
    @DisplayName("템플릿 — 용어·안내 시트, 머리글, 예시 2행, 분류 목록, 그대로 올리면 등록할 행이 없다")
    fun template() {
        val bytes = service.importTemplate()
        WorkbookFactory.create(bytes.inputStream()).use { wb ->
            val s = wb.getSheet("용어")
            assertEquals(GlossaryImportWorkbook.HEADERS, (0..3).map { s.getRow(0).getCell(it).stringCellValue })
            assertEquals(2, s.lastRowNum)
            val guide = (0..wb.getSheet("안내").lastRowNum).map { wb.getSheet("안내").getRow(it).getCell(0).stringCellValue }
            assertTrue(guide.contains("품질관리")); assertTrue(guide.any { it.contains("공식 용어 50자 · 뜻 500자 · 유사어 50자") })
        }
        val e = assertThrows(InvalidParameterException::class.java) { service.importTerms(bytes, "t.xlsx", true) }
        assertTrue(e.message!!.startsWith("등록할 행이 없습니다"))
    }
}
