package com.dwje.api

import com.dwje.api.common.exception.BusinessRuleException
import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.exception.WriteAccessDeniedException
import com.dwje.api.common.security.UserContext
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.common.util.MenuId
import com.dwje.api.config.AppProperties
import com.dwje.api.model.request.GlossaryExportRequest
import com.dwje.api.model.request.GlossaryNormalizeRequest
import com.dwje.api.service.AiAdminService
import com.dwje.api.service.AiChatService
import com.dwje.api.service.AuditArchiveJob
import com.dwje.api.service.AuditLogService
import com.dwje.api.service.AuditRetentionService
import com.dwje.api.service.DashboardUploadService
import com.dwje.api.service.GlossaryService
import com.dwje.api.service.SystemUserService
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
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mockingDetails
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.annotation.Transactional

/** 5단계 결정 R-17~R-20 (공통 11장) — 실제 로컬 DB, 롤백. 감사는 목 */
@SpringBootTest(properties = ["app.ai.prewarm-enabled=false"])
@ActiveProfiles("local")
@Transactional
class Phase5DecisionsDbTest {

    @Autowired lateinit var glossary: GlossaryService
    @Autowired lateinit var uploads: DashboardUploadService
    @Autowired lateinit var aiAdmin: AiAdminService
    @Autowired lateinit var users: SystemUserService
    @Autowired lateinit var retention: AuditRetentionService
    @Autowired lateinit var archiveJob: AuditArchiveJob
    @Autowired lateinit var appProperties: AppProperties
    @Autowired lateinit var jdbc: NamedParameterJdbcTemplate
    @MockitoBean lateinit var audit: AuditLogService

    private val admin = UserPrincipal("10000", "관리자", 1, "통합관리자", null, null, true)
    /** 제조팀 — customer 데이터 권한 없음. 관리 화면 쓰기 가능 */
    private val maker = UserPrincipal(
        "10003", "제조", 4, "제조팀", null, null, false,
        menuPerms = setOf(MenuId.SYS_GLOSS, MenuId.GLOSS_VIEW, MenuId.SYS_UPLOAD_DOC), dataPerms = setOf("qty", "mold", "worker")
    )
    /** 품질보증팀 — customer 데이터 권한 있음 */
    private val quality = maker.copy(userId = "10001", deptId = 2, deptName = "품질보증팀", dataPerms = setOf("customer", "qty"))

    @BeforeEach
    fun login() = UserContext.set(admin)

    @AfterEach
    fun clear() = UserContext.clear()

    private fun long(sql: String) = jdbc.queryForObject(sql, MapSqlParameterSource(), Long::class.java)!!
    private fun str(sql: String) = jdbc.queryForObject(sql, MapSqlParameterSource(), String::class.java)!!
    private fun exec(sql: String) = jdbc.update(sql, MapSqlParameterSource())
    private fun maskCalls() = mockingDetails(audit).invocations.filter { it.method.name == "record" && it.arguments[0] == "MASK" }

    private val hiddenSql = "SELECT t.term_id FROM ax.tb_gls_term t " +
        "WHERE t.data_field_key = 'customer' AND t.use_flg = 'Y' ORDER BY t.term_id LIMIT 1"

    @Test
    @DisplayName("R-18 고객사 정보 용어(V75 용어별 표시) — customer 권한 없으면 목록·상세·변경 이력·내려받기에서 「비공개 용어」 와 MASK 감사, 검색어에 걸리지 않음, 고치면 409 / 권한 있으면·통합관리자는 그대로")
    fun customerTermsHidden() {
        val termId = long(hiddenSql).toInt()
        val name = str("SELECT term FROM ax.tb_gls_term WHERE term_id = $termId")
        val hiddenCnt = long("SELECT count(*) FROM ax.tb_gls_term t WHERE t.data_field_key = 'customer' AND t.use_flg = 'Y'")

        UserContext.set(maker)
        clearInvocations(audit)
        val (rows, meta) = glossary.getTerms(null, 1, 1000)
        val blinded = rows.filter { it["blinded"] == true }
        assertEquals(hiddenCnt, blinded.size.toLong())
        assertTrue(blinded.all { it["term"] == GlossaryService.BLIND_TERM && it["definition"] == null && it["variants"] == null })
        assertTrue(rows.none { it["term"] == name })
        assertEquals(long("SELECT count(*) FROM ax.tb_gls_term WHERE use_flg = 'Y'"), meta.total, "목록에서 빼지 않는다")
        assertEquals(1, maskCalls().size)
        assertEquals("customer", maskCalls().single().arguments[2]); assertEquals(hiddenCnt.toInt(), maskCalls().single().arguments[5])

        assertTrue(glossary.getTerms(name, 1, 50).first.none { it["termId"] == termId }, "가린 용어는 검색어에 걸리지 않는다")
        glossary.getTermDetail(termId).let { assertEquals(true, it["blinded"]); assertEquals(GlossaryService.BLIND_TERM, it["term"]); assertNull(it["definition"]) }
        assertThrows(BusinessRuleException::class.java) { glossary.createVariant(termId, "ZT새유사어") }

        exec("INSERT INTO ax.tb_gls_change_log (actor_id, target_cd, action_cd, term_id, after_json) VALUES ('10000', 'TERM', 'UPDATE', $termId, '{\"term\":\"x\"}')")
        val change = glossary.getChanges(termId, null, null, 1, 10).first.first()
        assertEquals(GlossaryService.BLIND_TERM, change["term"]); assertNull(change["after"]); assertEquals(true, change["blinded"])

        val export = glossary.exportTerms(GlossaryExportRequest(menuId = "sys-gloss", scopeCd = "ALL"))
        assertTrue(export.rows.none { it["term"] == name })
        assertEquals(hiddenCnt.toInt() * 2, export.blindedCells) // 뜻 · 유사어 (등록자 열은 2026-10-04 에 뺐다)

        UserContext.set(quality)
        assertTrue(glossary.getTerms(name, 1, 50).first.any { it["termId"] == termId && it["blinded"] == false })
        UserContext.set(admin)
        assertEquals(name, glossary.getTermDetail(termId)["term"])
    }

    @Test
    @DisplayName("R-18 덕반장 AI — 볼 수 없는 용어의 치환은 응답·[용어] 블록에서 빠지고, 미리보기 문장은 원문을 남긴다")
    fun aiReplacementsFiltered() {
        val r = listOf(
            mapOf("from" to "a", "to" to "고객A", "fieldKey" to "customer", "start" to 0, "end" to 1),
            mapOf("from" to "b", "to" to "공정B", "fieldKey" to null, "start" to 2, "end" to 3)
        )
        assertEquals(listOf("공정B"), AiChatService.visibleReplacements(r, maker).map { it["to"] })
        assertEquals(2, AiChatService.visibleReplacements(r, quality).size)
        assertTrue(AiChatService.visibleReplacements(r, quality).none { "fieldKey" in it })

        val termId = long(hiddenSql).toInt()
        val word = jdbc.queryForList("SELECT word FROM ax.tb_gls_variant WHERE term_id = $termId", MapSqlParameterSource(), String::class.java).firstOrNull() ?: return
        UserContext.set(maker)
        val preview = glossary.normalize(GlossaryNormalizeRequest("확인 $word 건"))
        @Suppress("UNCHECKED_CAST")
        assertTrue((preview["replacements"] as List<Map<String, Any?>>).none { it["termId"] == termId })
        assertFalse((preview["normalizedText"] as String).contains(str("SELECT term FROM ax.tb_gls_term WHERE term_id = $termId")))
    }

    @Test
    @DisplayName("R-19 업로드 문서 숨김·복원 — 사유 필수, 기본 목록·대시보드에서 빠짐, includeDeleted 로 보임, 요약 deletedDocCnt, 두 번 숨기기·숨기지 않은 복원 409, 쓰기 권한 없으면 403")
    fun uploadHideRestore() {
        assertEquals("reason", assertThrows(InvalidParameterException::class.java) { uploads.hideDoc(1, " ") }.field)
        val beforeDeleted = long("SELECT count(*) FROM ax.tb_dash_upload_doc WHERE del_flg = 'Y' AND latest_ver > 0")
        uploads.hideDoc(1, "시험 숨김")
        assertTrue(uploads.listDocsForAdmin().first.none { it["docId"] == 1L })
        @Suppress("UNCHECKED_CAST")
        assertTrue((uploads.listDocs()["items"] as List<Map<String, Any?>>).none { it["docId"] == 1L }, "대시보드·AI 패널 목록에서도 빠진다")
        val row = uploads.listDocsForAdmin(includeDeleted = true).first.single { it["docId"] == 1L }
        assertEquals(true, row["deleted"]); assertEquals("시험 숨김", row["deleteReason"]); assertNotNull(row["deletedAt"]); assertNotNull(row["deletedByName"])
        @Suppress("UNCHECKED_CAST")
        assertEquals(beforeDeleted + 1, (uploads.adminListExtras(null, null, null, null)["summary"] as Map<String, Any?>)["deletedDocCnt"])
        assertNotNull(uploads.listVersions(1)["items"], "관리 화면은 숨긴 문서의 버전 이력을 볼 수 있다")
        assertThrows(BusinessRuleException::class.java) { uploads.hideDoc(1, "다시") }

        uploads.restoreDoc(1)
        assertTrue(uploads.listDocsForAdmin().first.any { it["docId"] == 1L })
        assertThrows(BusinessRuleException::class.java) { uploads.restoreDoc(1) }

        // V70 — 화면에 접근하면 숨김도 된다. 쓰기 동작을 못 하는 것은 미배정 계정뿐이다
        UserContext.set(maker.copy(deptId = 59, deptName = "미배정", unassigned = true))
        assertThrows(WriteAccessDeniedException::class.java) { uploads.hideDoc(1, "권한 없음") }
    }

    @Test
    @DisplayName("R-20 보존 배치 켬(감사 아카이브·질의 이력 1,095일), 질의 이력 요약 expiredCnt, 보존 정책 nextArchiveAt / R-17 mailFailSinceBoot·mailLastFailAt")
    fun retentionOnAndMailStats() {
        assertTrue(appProperties.auditArchiveEnabled)
        assertEquals(1095, appProperties.ai.chatRetentionDays)
        val summary = aiAdmin.getChatHistorySummary(null, null, null)
        assertEquals(1095, summary["retentionDays"])
        assertEquals(long("SELECT count(*) FROM ax.tb_ai_chat_log WHERE asked_at < (now() AT TIME ZONE 'Asia/Seoul')::date - 1095"), summary["expiredCnt"])
        val policy = retention.getRetentionPolicy()
        assertEquals(true, policy["enabled"]); assertNotNull(policy["nextArchiveAt"]); assertTrue("mailFailSinceBoot" in policy)
        assertNotNull(archiveJob.nextRunAt())
        assertTrue("mailLastFailAt" in users.getAccountSummary())
    }
}
