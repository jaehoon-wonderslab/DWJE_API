package com.dwje.api

import com.dwje.api.common.exception.ConflictingValueException
import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.exception.MenuAccessDeniedException
import com.dwje.api.common.security.UserContext
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.common.util.MenuId
import com.dwje.api.model.request.DeptSaveRequest
import com.dwje.api.service.AuditLogService
import com.dwje.api.service.GlossaryService
import com.dwje.api.service.SystemUserService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
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

/** 07 GLS-05·06·07·08·10 · 13 GLV-02·06 — 실제 로컬 DB, 롤백 */
@SpringBootTest(properties = ["app.ai.prewarm-enabled=false"])
@ActiveProfiles("local")
@Transactional
class GlossaryChangeLogTest {

    @Autowired lateinit var service: GlossaryService
    @Autowired lateinit var users: SystemUserService
    @Autowired lateinit var jdbc: NamedParameterJdbcTemplate
    @MockitoBean lateinit var audit: AuditLogService

    private val admin = UserPrincipal("10000", "관리자", 1, "통합관리자", null, null, null, true)
    private val writer = UserPrincipal(
        "10002", "박생산", 3, "생산팀", null, null, null, false,
        menuPerms = setOf(MenuId.SYS_GLOSS), writePerms = setOf(MenuId.SYS_GLOSS)
    )
    private val viewer = UserPrincipal("10003", "조회", 3, "생산팀", null, null, null, false, menuPerms = setOf(MenuId.GLOSS_VIEW))

    @BeforeEach
    fun login() = UserContext.set(admin)

    @AfterEach
    fun clear() = UserContext.clear()

    private fun long(sql: String) = jdbc.queryForObject(sql, MapSqlParameterSource(), Long::class.java)!!
    private fun logCnt() = long("SELECT count(*) FROM ax.tb_gls_change_log")

    @Suppress("UNCHECKED_CAST")
    private fun latest(): Map<String, Any?> = service.getChanges(null, null, null, 1, 1).first.single()

    @Test
    @DisplayName("GLS-07 유사어 등록·수정·삭제 → 각 1행(before/after.word), 중복 409 는 이력 없음, 관리자 대리 수정 byAdmin, GLS-06 lastChangedBy")
    fun variantLog() {
        val start = logCnt()
        val id = service.createVariant(5, "ZT팔디")["variantId"] as Int
        assertEquals(start + 1, logCnt())
        latest().let {
            assertEquals("VARIANT", it["targetCd"]); assertEquals("CREATE", it["actionCd"])
            assertEquals(id, it["variantId"]); assertEquals(5, it["termId"]); assertEquals("8D", it["term"])
            assertNull(it["before"]); assertEquals(mapOf("word" to "ZT팔디"), it["after"])
            assertEquals("관리자", it["actorNm"])
        }

        assertThrows(ConflictingValueException::class.java) { service.createVariant(5, "ZT팔디") }
        assertEquals(start + 1, logCnt(), "409 는 이력을 남기지 않는다")

        // 10001 이 등록한 유사어를 관리자가 고친다 → byAdmin
        service.updateVariant(38, "ZT타발 찌그러짐")
        latest().let {
            assertEquals("UPDATE", it["actionCd"])
            assertEquals(mapOf("word" to "타발 찌그러짐"), it["before"])
            assertEquals(mapOf("word" to "ZT타발 찌그러짐", "byAdmin" to true), it["after"])
        }

        UserContext.set(writer)
        val mine = service.createVariant(5, "ZT에잇디")["variantId"] as Int
        service.deleteVariant(mine)
        latest().let {
            assertEquals("DELETE", it["actionCd"]); assertEquals(mapOf("word" to "ZT에잇디"), it["before"]); assertNull(it["after"])
        }
        assertEquals(start + 4, logCnt())

        val summary = service.getSummary()
        assertEquals("박생산", summary["lastChangedBy"])
        UserContext.set(viewer)
        assertEquals("박생산", service.getSummary()["lastChangedBy"], "조회 화면에도 이름은 준다")
    }

    @Test
    @DisplayName("GLS-07 용어 등록·수정·삭제·되살림 이력, 같은 값 수정은 이력 없음 / GLS-10 뜻을 지운 수정 400")
    fun termLog() {
        val created = service.createTerm("ZT용어", "시험 뜻", "품질관리")
        val termId = created["termId"] as Int
        latest().let { assertEquals("CREATE", it["actionCd"]); assertEquals(mapOf("term" to "ZT용어", "termDef" to "시험 뜻", "domainNm" to "품질관리"), it["after"]) }

        assertEquals("definition", assertThrows(InvalidParameterException::class.java) {
            service.updateTerm(termId, "ZT용어", "  ", "품질관리")
        }.field)

        val before = logCnt()
        service.updateTerm(termId, "ZT용어", "시험 뜻", "품질관리")
        assertEquals(before, logCnt(), "바뀐 것이 없으면 이력 없음")
        service.updateTerm(termId, "ZT용어", "고친 뜻", "품질관리")
        latest().let { assertEquals("UPDATE", it["actionCd"]); assertEquals("시험 뜻", (it["before"] as Map<*, *>)["termDef"]); assertEquals("고친 뜻", (it["after"] as Map<*, *>)["termDef"]) }

        service.createVariant(termId, "ZT용어별칭")
        service.deleteTerm(termId)
        latest().let { assertEquals("DELETE", it["actionCd"]); assertEquals(1, (it["before"] as Map<*, *>)["deactivatedVariants"]) }

        service.createTerm("zt용어", "되살린 뜻", "품질관리")
        latest().let {
            assertEquals("RESTORE", it["actionCd"]); assertEquals(termId, it["termId"])
            assertEquals(1, (it["after"] as Map<*, *>)["restoredVariants"])
        }

        // termId 지정이면 기간 기본값 없이 그 용어의 전 이력
        val (rows, meta) = service.getChanges(termId, null, null, 1, 50)
        assertEquals(5L, meta.total)
        assertEquals(listOf("RESTORE", "DELETE", "CREATE", "UPDATE", "CREATE"), rows.take(5).map { it["actionCd"] })
        assertEquals("from", assertThrows(InvalidParameterException::class.java) { service.getChanges(null, "2026-10-02", "2026-10-01", 1, 10) }.field)
    }

    @Test
    @DisplayName("GLV-02 변경 이력은 sys-gloss 만(gloss-view 403) / GLS-05 재생성 통합관리자만·PENDING·번호 중복 없음")
    fun permissionsAndReindex() {
        UserContext.set(viewer)
        assertThrows(MenuAccessDeniedException::class.java) { service.getChanges(null, null, null, 1, 10) }
        UserContext.set(writer)
        assertThrows(MenuAccessDeniedException::class.java) { service.reindex() }

        UserContext.set(admin)
        val a = service.reindex()
        val b = service.reindex()
        assertNotEquals(a["jobId"], b["jobId"])
        assertEquals("PENDING", a["stateCd"])
        assertEquals(2L, long("SELECT count(*) FROM vec.tb_ingest_job WHERE job_id IN ('${a["jobId"]}', '${b["jobId"]}') AND job_type_cd = 'TERM_EMBED' AND state_cd = 'PENDING'"))
    }

    @Test
    @DisplayName("GLS-08 mineOnly(sys-gloss 만 효과)·대소문자 무시 검색 / GLV-06 새 부서에 gloss-view·chat-history 조회 권한")
    fun filtersAndDefaults() {
        UserContext.set(writer.copy(userId = "10001"))
        val expected = long("SELECT count(DISTINCT v.term_id) FROM ax.tb_gls_variant v JOIN ax.tb_gls_term t USING (term_id) WHERE t.use_flg = 'Y' AND v.owner_user_id = '10001'")
        assertEquals(expected, service.getTerms(null, null, 1, 10, mineOnly = true).second.total)
        UserContext.set(viewer)
        val all = long("SELECT count(*) FROM ax.tb_gls_term WHERE use_flg = 'Y'")
        assertEquals(all, service.getTerms(null, null, 1, 10, mineOnly = true).second.total, "조회 화면은 mineOnly 를 무시한다")
        val (lrr, _) = service.getTerms("lrr", null, 1, 10)
        assertTrue(lrr.any { it["term"] == "LRR" })

        UserContext.set(admin)
        val deptId = users.createDept(DeptSaveRequest(deptNm = "ZT부서", abbr = "ZTD"))["deptId"] as Int
        assertEquals(
            setOf(MenuId.GLOSS_VIEW, MenuId.CHAT_HISTORY),
            jdbc.queryForList("SELECT menu_id FROM ax.tb_sys_dept_menu_perm WHERE dept_id = $deptId AND can_read", MapSqlParameterSource(), String::class.java).toSet()
        )
    }
}
