package com.dwje.api

import com.dwje.api.common.security.JwtTokenProvider
import com.dwje.api.service.DownloadLogService
import com.dwje.api.service.GlossaryImportWorkbook
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.transaction.annotation.Transactional
import java.io.ByteArrayOutputStream

/** 용어 사전 업로드 — 컨트롤러(multipart·파일 응답·내려받기 이력). 실제 로컬 DB, 롤백. 감사·내려받기 이력은 목 */
@SpringBootTest(properties = ["app.ai.prewarm-enabled=false"])
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Transactional
class GlossaryImportApiTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var tokens: JwtTokenProvider
    @Autowired lateinit var json: ObjectMapper
    @MockitoBean lateinit var audit: com.dwje.api.service.AuditLogService
    @MockitoBean lateinit var downloads: DownloadLogService

    private val bearer by lazy { "Bearer " + tokens.createAccessToken("10000", "관리자", 1, "통합관리자", true, null) }

    private fun xlsx(header: List<String>, vararg rows: List<String>): ByteArray = XSSFWorkbook().use { wb ->
        val s = wb.createSheet("용어")
        s.createRow(0).let { r -> header.forEachIndexed { i, h -> r.createCell(i).setCellValue(h) } }
        rows.forEachIndexed { i, cells -> s.createRow(i + 1).let { r -> cells.forEachIndexed { c, v -> r.createCell(c).setCellValue(v) } } }
        ByteArrayOutputStream().use { wb.write(it); it.toByteArray() }
    }

    @Test
    @DisplayName("템플릿은 xlsx 첨부로 내려가고 내려받기 이력을 남긴다")
    fun template() {
        val r = mvc.perform(get("/api/v1/glossary/import/template").header("Authorization", bearer)).andReturn()
        assertEquals(200, r.response.status)
        assertTrue(r.response.getHeader("Content-Disposition")!!.contains("glossary_import_template.xlsx"))
        val call = org.mockito.Mockito.mockingDetails(downloads).invocations.single { it.method.name == "record" }
        assertEquals(listOf(null, "용어 사전 업로드 템플릿", "sys-gloss", "XLSX"), call.arguments.take(4).toList())
    }

    @Test
    @DisplayName("multipart 업로드 — dryRun 생략이면 미리보기, 머리글이 다르면 400")
    fun upload() {
        val file = MockMultipartFile("file", "용어.xlsx", null,
            xlsx(GlossaryImportWorkbook.HEADERS, listOf("8D", "", "", "ZT팔디보고, 8디"), listOf("ZT새용어", "뜻", "품질관리", "ZT새말")))
        val r = mvc.perform(multipart("/api/v1/glossary/import").file(file).header("Authorization", bearer)).andReturn()
        assertEquals(200, r.response.status, r.response.getContentAsString(Charsets.UTF_8))
        val data = json.readTree(r.response.getContentAsString(Charsets.UTF_8)).path("data")
        assertTrue(data.path("dryRun").asBoolean())
        assertEquals(1, data.path("termNew").asInt()); assertEquals(1, data.path("termExisting").asInt())

        val bad = MockMultipartFile("file", "용어.xlsx", null, xlsx(listOf("용어", "뜻", "분류", "유사어"), listOf("a", "b", "c", "d")))
        val e = mvc.perform(multipart("/api/v1/glossary/import").file(bad).param("dryRun", "false").header("Authorization", bearer)).andReturn()
        assertEquals(400, e.response.status)
        assertTrue(e.response.getContentAsString(Charsets.UTF_8).contains("템플릿의 머리글과 다릅니다"))
    }
}
