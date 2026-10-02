package com.dwje.api

import com.dwje.api.common.security.UserContext
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.controller.AiChatController
import com.dwje.api.model.request.AiDefectTopExportRequest
import com.dwje.api.repository.AiChatRepository
import com.dwje.api.repository.VectorIndexRepository
import com.dwje.api.service.*
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.stubbing.Answer
import org.mockito.Mockito.*
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.util.UUID

class AiChatHistoryExportTest {
    @AfterEach fun clear() = UserContext.clear()

    @Test fun `history detail exposes required fields without intent agents or internal SQL`() {
        UserContext.set(UserPrincipal("admin", "관리자", 1, "전산", null, null, true))
        val repo = mock(AiChatRepository::class.java)
        val auth = AuthorizationService(mock(com.dwje.api.repository.AuthRepository::class.java))
        `when`(repo.findChatLog(7L)).thenReturn(mapOf(
            "question" to "불량 top 2", "answer" to "SELECT * FROM mes.tb_pop_label_hist",
            "evidenceSummary" to "불량 유형 2건", "unansweredReason" to null,
            "responseMs" to 120, "rating" to "USEFUL", "maskedCnt" to 0))
        val service = AiAdminService(repo, mock(VectorIndexRepository::class.java), auth, ObjectMapper(),
            mock(AiAskDebugRecorder::class.java), mock(DataFieldService::class.java), mock(AuditLogService::class.java))
        val result = service.getChatDetail(7L)
        assertEquals("불량 top 2", result["question"])
        assertEquals(AiResponseSanitizer.HIDDEN, result["answer"])
        assertEquals("불량 유형 2건", result["judgmentBasis"])
        assertEquals(120, result["elapsedMs"])
        assertNotNull(result["evaluationCriteria"])
        assertFalse(result.containsKey("intent"))
        assertFalse(result.containsKey("agents"))
        assertFalse(result.containsKey("search"))
    }

    @Test fun `history detail needs screen read and diagnostics need write before reading records`() {
        // 화면 권한이 없으면 상세도 읽기 전에 막힌다
        UserContext.set(UserPrincipal("u1", "사용자", 1, "품질", null, null, false))
        val repo = mock(AiChatRepository::class.java)
        val service = AiAdminService(repo, mock(VectorIndexRepository::class.java),
            AuthorizationService(mock(com.dwje.api.repository.AuthRepository::class.java)), ObjectMapper(), mock(AiAskDebugRecorder::class.java),
            mock(DataFieldService::class.java), mock(AuditLogService::class.java))
        assertThrows(com.dwje.api.common.exception.MenuAccessDeniedException::class.java) { service.getChatDetail(7L) }
        // 조회만 있으면 디버그 진단은 쓰기 권한 없음(E-AUTH-004) — 관리 기능이다(08 CHH-16)
        UserContext.set(UserPrincipal("u1", "사용자", 1, "품질", null, null, false,
            menuPerms = setOf(com.dwje.api.common.util.MenuId.CHAT_HISTORY)))
        assertThrows(com.dwje.api.common.exception.WriteAccessDeniedException::class.java) {
            service.getAskDebug(UUID.randomUUID().toString())
        }
        assertFalse(mockingDetails(repo).invocations.any { it.method.name == "findChatLog" })
    }

    @Test fun `super administrator can read stored ask diagnostics`() {
        UserContext.set(UserPrincipal("admin", "관리자", 1, "전산", null, null, true))
        val id = UUID.randomUUID()
        val debug = mapOf<String, Any?>("requestId" to id.toString(), "route" to "PRODUCT_LIST")
        val recorder = mock(AiAskDebugRecorder::class.java)
        `when`(recorder.byRequestId(id)).thenReturn(debug)
        val service = AiAdminService(mock(AiChatRepository::class.java), mock(VectorIndexRepository::class.java),
            AuthorizationService(mock(com.dwje.api.repository.AuthRepository::class.java)), ObjectMapper(), recorder, mock(DataFieldService::class.java), mock(AuditLogService::class.java))
        assertEquals(debug, service.getAskDebug(id.toString()))
    }

    @Test fun `latest session restores only signed in user's messages and hides schema`() {
        UserContext.set(UserPrincipal("u1", "사용자", 1, "품질", null, null, true))
        val repo = mock(AiChatRepository::class.java)
        val id = UUID.randomUUID()
        `when`(repo.findLatestSessionId("u1")).thenReturn(id)
        `when`(repo.findSessionMessages(id, "u1")).thenReturn(listOf(
            mapOf("who" to "user", "html" to "질문"),
            mapOf("who" to "ai", "html" to "mes.tb_pop_label_hist")))
        val service = AiChatService(repo, mock(GlossaryNormalizer::class.java), mock(AuditLogService::class.java),
            mock(AuthorizationService::class.java), mock(AgentRunRecorder::class.java),
            mock(DataFieldService::class.java), mock(AiDataToolService::class.java), mock(AiAskDebugRecorder::class.java))
        val restored = service.getLatestSessionMessages()
        assertEquals(id.toString(), restored["sessionId"])
        @Suppress("UNCHECKED_CAST")
        val messages = restored["messages"] as List<Map<String, Any?>>
        assertEquals("질문", messages.first()["html"])
        assertEquals(AiResponseSanitizer.HIDDEN, messages.last()["html"])
        assertEquals("u1", mockingDetails(repo).invocations.first { it.method.name == "findLatestSessionId" }.arguments[0])
    }

    @Test fun `top ten export returns workbook and records existing download history`() {
        UserContext.set(UserPrincipal("u1", "사용자", 1, "품질", null, null, true))
        val data = mock(AiDataToolService::class.java)
        val logs = mock(DownloadLogService::class.java, Answer<Any?> { if (it.method.name == "record") 1L else null })
        val rows = listOf(mapOf<String, Any?>("rank" to 1, "from" to "2026-09-22", "to" to "2026-09-23",
            "defect" to "얼룩", "quantity" to BigDecimal.TEN))
        `when`(data.defectTopForExport(java.time.LocalDate.parse("2026-09-22"),
            java.time.LocalDate.parse("2026-09-23"), 10, UserContext.current())).thenReturn(rows to 0)
        val controller = AiChatController(mock(AiChatService::class.java), data, ExportService(), logs,
            AuthorizationService(mock(com.dwje.api.repository.AuthRepository::class.java)))
        val file = controller.exportDefectTop(AiDefectTopExportRequest("2026-09-22", "2026-09-23", 10))
        val invocation = mockingDetails(logs).invocations.last()
        assertEquals("record", invocation.method.name)
        assertEquals(1, invocation.arguments[5])
        assertEquals("XLSX", invocation.arguments[3], "실제로 만든 파일 기준 형식 코드(DLG-02)")
        assertTrue((invocation.arguments[8] as String).endsWith(".xlsx"))
        assertTrue((invocation.arguments[10] as Long) > 0)
        assertTrue(file.headers.contentDisposition.toString().contains("attachment"))
        XSSFWorkbook(ByteArrayInputStream(file.body!!.byteArray)).use { workbook ->
            assertEquals("얼룩", workbook.getSheetAt(0).getRow(1).getCell(3).stringCellValue)
        }
    }
}
