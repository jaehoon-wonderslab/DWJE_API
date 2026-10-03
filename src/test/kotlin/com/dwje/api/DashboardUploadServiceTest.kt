package com.dwje.api

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.exception.WriteAccessDeniedException
import com.dwje.api.common.security.UserContext
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.config.AppProperties
import com.dwje.api.config.UploadProperties
import com.dwje.api.repository.AuthRepository
import com.dwje.api.repository.DashboardUploadRepository
import com.dwje.api.repository.UploadVersionRow
import com.dwje.api.service.AuthorizationService
import com.dwje.api.service.DashboardUploadService
import com.dwje.api.service.ExcelBlockParser
import com.dwje.api.common.validation.CodeValidator
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.mock.web.MockMultipartFile
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 업로드 버전 메모(UPD-02)·매크로 통합문서 차단(UPD-03) — 서비스 규약 (DB 없이)
 */
class DashboardUploadServiceTest {

    @AfterEach
    fun clear() = UserContext.clear()

    /** 버전 행을 메모리에 두는 저장소 */
    private class MemRepo : DashboardUploadRepository(mock(NamedParameterJdbcTemplate::class.java)) {
        val docs = mutableMapOf<Long, MutableMap<String, Any?>>()
        val versions = mutableMapOf<Pair<Long, Int>, UploadVersionRow>()
        override fun insertDoc(title: String, memo: String?, actor: String): Long {
            val id = (docs.size + 1).toLong(); docs[id] = mutableMapOf("docId" to id, "title" to title, "memo" to memo, "latestVersion" to 0); return id
        }
        override fun nextVersion(docId: Long, actor: String): Int? = docs[docId]?.let { val v = (it["latestVersion"] as Int) + 1; it["latestVersion"] = v; v }
        override fun findDoc(docId: Long) = docs[docId]
        override fun insertVersion(docId: Long, ver: Int, fileNm: String, storagePath: String, fileSize: Long, sha256: String,
                                   parseState: String, parseJson: String, warningJson: String, actor: String, memo: String?) {
            versions[docId to ver] = UploadVersionRow(ver, fileNm, storagePath, fileSize, sha256, parseState, parseJson, warningJson, actor, null, null, memo)
        }
        override fun findVersion(docId: Long, ver: Int) = versions[docId to ver]
    }

    private val repo = MemRepo()
    private val dir = Files.createTempDirectory("upload-test")
    private val service = DashboardUploadService(
        repo, ExcelBlockParser(), AuthorizationService(mock(AuthRepository::class.java)),
        AppProperties(upload = UploadProperties(dir = dir.toString())), ObjectMapper(), mock(CodeValidator::class.java)
    )

    /** write=false 는 쓰기 동작을 못 하는 계정 — V70 부터는 미배정 계정뿐이다 */
    private fun login(write: Boolean = true) = UserContext.set(UserPrincipal("10004", "전산", 5, if (write) "전산팀" else "미배정", null, null, false,
        menuPerms = setOf("dash-ai-upload", "dash-ai"), unassigned = !write))

    private fun xlsxBytes(): ByteArray = ByteArrayOutputStream().also { out ->
        XSSFWorkbook().use { wb -> wb.createSheet("시트1").createRow(0).createCell(0).setCellValue("값"); wb.write(out) }
    }.toByteArray()

    /** 정상 xlsx 에 매크로 파트를 덧붙인 ZIP — 확장자는 xlsx */
    private fun withMacro(xlsx: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zo ->
            ZipInputStream(xlsx.inputStream()).use { zi ->
                generateSequence { zi.nextEntry }.forEach { e -> zo.putNextEntry(ZipEntry(e.name)); zo.write(zi.readBytes()); zo.closeEntry() }
            }
            zo.putNextEntry(ZipEntry("xl/vbaProject.bin")); zo.write(ByteArray(8)); zo.closeEntry()
        }
    }.toByteArray()

    private fun file(name: String, bytes: ByteArray) = MockMultipartFile("file", name, null, bytes)

    @Test
    @DisplayName("UPD-03 — xlsm 이름·확장자만 xlsx 인 옛 파일·매크로 파트가 든 xlsx 는 400(field=file), 정상 xlsx 는 통과")
    fun blocksMacroWorkbooks() {
        login()
        val cases = listOf(
            file("보고.xlsm", xlsxBytes()) to "xlsx 파일만",
            file("보고.xlsx", byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 1, 2, 3)) to "xlsx 형식 파일이 아닙니다",
            file("보고.xlsx", withMacro(xlsxBytes())) to "매크로가 포함된"
        )
        cases.forEach { (f, msg) ->
            val e = assertThrows(InvalidParameterException::class.java) { service.create(f, "제목", null) }
            assertEquals("file", e.field); assertTrue(e.message.contains(msg), e.message)
        }
        assertTrue(repo.versions.isEmpty(), "거부된 파일은 저장하지 않는다")
        assertEquals("보고.xlsx", service.create(file("보고.xlsx", xlsxBytes()), "제목", null)["fileName"])
    }

    @Test
    @DisplayName("UPD-02 — 등록 메모는 문서와 버전 1 에, 새 버전 메모는 그 버전에 저장하고 응답에 싣는다 · 공백만이면 없음 · 1001자 400")
    fun versionMemo() {
        login()
        val created = service.create(file("a.xlsx", xlsxBytes()), "회의", "  첫 등록  ")
        assertEquals("첫 등록", created["memo"]); assertEquals("첫 등록", repo.docs[1L]!!["memo"]); assertEquals("첫 등록", repo.versions[1L to 1]!!.memo)

        val v2 = service.addVersion(1L, file("a.xlsx", xlsxBytes()), "8/31 실적 추가")
        assertEquals("8/31 실적 추가", v2["memo"]); assertEquals("8/31 실적 추가", repo.versions[1L to 2]!!.memo)
        service.addVersion(1L, file("a.xlsx", xlsxBytes()), "   ")
        assertNull(repo.versions[1L to 3]!!.memo)

        val e = assertThrows(InvalidParameterException::class.java) { service.addVersion(1L, file("a.xlsx", xlsxBytes()), "가".repeat(1001)) }
        assertEquals("memo", e.field)
        assertEquals(3, repo.docs[1L]!!["latestVersion"], "검증에 걸리면 버전 번호도 올리지 않는다")
    }

    @Test
    @DisplayName("UPD-14 회귀 — 조회만 있으면 새 버전 업로드 E-AUTH-004, 저장 없음")
    fun readOnlyCannotUpload() {
        login(write = false)
        assertThrows(WriteAccessDeniedException::class.java) { service.create(file("a.xlsx", xlsxBytes()), "회의", null) }
        assertTrue(repo.docs.isEmpty())
    }
}
