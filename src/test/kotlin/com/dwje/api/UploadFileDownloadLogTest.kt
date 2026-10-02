package com.dwje.api

import com.dwje.api.common.util.MenuId
import com.dwje.api.controller.DashboardUploadController
import com.dwje.api.service.DashboardUploadService
import com.dwje.api.service.DownloadLogService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import java.nio.file.Files

/** 업로드 원본 내려받기 1회 = 다운로드 이력 1행 (10 기획서 DLG-04) */
class UploadFileDownloadLogTest {

    @Test
    @DisplayName("원본 내려받기는 dash-ai · XLSX · 파일명·크기·문서 번호를 남긴다")
    fun originalFileIsRecorded() {
        val file = Files.createTempFile("upload", ".xlsx").also { Files.write(it, ByteArray(12)) }
        val uploads = mock(DashboardUploadService::class.java)
        doReturn(Triple(file, "월간보고.xlsx", 12L)).`when`(uploads).openFile(7L, 2)
        val logs = mock(DownloadLogService::class.java)
        val res = DashboardUploadController(uploads, logs).file(7L, 2)

        assertEquals(200, res.statusCode.value())
        val call = mockingDetails(logs).invocations.single { it.method.name == "record" }
        val a = call.arguments
        assertEquals(MenuId.DASH_AI, a[2]); assertEquals("XLSX", a[3]); assertEquals("월간보고.xlsx", a[8])
        assertEquals("{docId=7, version=2}", a[9].toString()); assertEquals(12L, a[10])
        Files.deleteIfExists(file)
    }
}
