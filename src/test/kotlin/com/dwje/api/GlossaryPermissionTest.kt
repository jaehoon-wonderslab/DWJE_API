package com.dwje.api

import com.dwje.api.common.exception.BusinessException
import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.exception.MenuAccessDeniedException
import com.dwje.api.common.exception.WriteAccessDeniedException
import com.dwje.api.common.response.ErrorCode
import com.dwje.api.common.security.UserContext
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.model.request.GlossaryExportRequest
import com.dwje.api.repository.AuthRepository
import com.dwje.api.repository.GlossaryRepository
import com.dwje.api.repository.VectorIndexRepository
import com.dwje.api.service.AuthorizationService
import com.dwje.api.service.GlossaryNormalizer
import com.dwje.api.service.GlossaryService
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
 * 용어 사전 권한·유사어 규칙 — 07 GLS-01·03, 13 GLV-02·05 (DB 없이)
 *
 * 1. 공식 용어 편집은 통합관리자만(E-AUTH-002), 미배정이면 E-AUTH-004(V70), 화면 권한 없으면 E-AUTH-002
 * 2. 조회 API 는 gloss-view 로도 열리고, 그때 관리 지표·편집 표시·등록자 사번은 주지 않는다
 * 3. 유사어: 한 글자·숫자·날짜·공식 용어와 같은 낱말 400, 다른 공식 용어에 들어 있으면 경고
 */
class GlossaryPermissionTest {

    @AfterEach
    fun clear() = UserContext.clear()

    private class MemRepo : GlossaryRepository(mock(NamedParameterJdbcTemplate::class.java)) {
        val inserted = mutableListOf<String>()
        val terms = mapOf("불량" to 55, "치수불량" to 56, "LOT" to 15)
        override fun findSummary(userId: String) = mapOf<String, Any?>("termCnt" to 3L, "variantCnt" to 1L,
            "myVariantCnt" to 1L, "noVariantTermCnt" to 2L)
        override fun findTermFieldKeys() = emptySet<String>()
        override fun countTerms(keyword: String?, mineOnly: String?, hiddenKeys: Collection<String>) = if (keyword == "없음") 0L else 1L
        override fun findTerms(keyword: String?, limit: Int, offset: Int, mineOnly: String?, hiddenKeys: Collection<String>) =
            if (keyword == "없음") emptyList() else
                listOf(mapOf<String, Any?>("termId" to 15, "term" to "LOT", "definition" to "로트", "fieldKey" to null, "updatedAt" to null))
        override fun findVariantsByTermIds(termIds: List<Int>, userId: String) = mapOf(15 to listOf(
            mapOf<String, Any?>("variantId" to 12, "word" to "로트", "byEmpNo" to "10002", "byName" to "박생산", "byDept" to "생산관리팀",
                "at" to "2026-09-08 10:00:00", "editable" to (userId == "10002"))))
        override fun existsTerm(termId: Int) = termId in terms.values
        override fun findActiveTermByWord(word: String) = terms.entries.firstOrNull { it.key.equals(word.trim(), true) }
            ?.let { mapOf<String, Any?>("termId" to it.value, "term" to it.key) }
        override fun findTermsContaining(word: String, excludeTermId: Int): Pair<Long, String?> {
            val hit = terms.filter { it.value != excludeTermId && it.key != word && it.key.contains(word, true) }.keys.sorted()
            return hit.size.toLong() to hit.firstOrNull()
        }
        override fun findVariantByWord(word: String, excludeVariantId: Int?): Map<String, Any?>? = null
        override fun insertVariant(termId: Int, word: String, ownerUserId: String, ownerDeptNm: String?): Int { inserted += word; return 300 }
        override fun insertTerm(term: String, definition: String, fieldKey: String?, actor: String): Int { inserted += term; return 900 }
        override fun findTermByName(term: String): Map<String, Any?>? = null
    }

    private val repo = MemRepo()
    private val service = GlossaryService(repo, mock(GlossaryNormalizer::class.java), mock(VectorIndexRepository::class.java),
        AuthorizationService(mock(AuthRepository::class.java)))

    /** unassigned = 화면에는 접근하지만 쓰기 동작을 못 하는 미배정 계정 (V70) */
    private fun login(read: Set<String>, unassigned: Boolean = false, superAdmin: Boolean = false, user: String = "10002") =
        UserContext.set(UserPrincipal(user, "사용자", 3, if (unassigned) "미배정" else "생산관리팀", null, null, superAdmin,
            menuPerms = read, unassigned = unassigned))

    @Test
    @DisplayName("GLS-01 공식 용어 편집 — 화면 접근자도 통합관리자가 아니면 403 E-AUTH-002, 미배정이면 E-AUTH-004, 통합관리자는 통과")
    fun termsSuperAdminOnly() {
        login(setOf("sys-gloss"))
        listOf({ service.createTerm("새용어", "뜻") }, { service.updateTerm(15, "LOT", "뜻") }, { service.deleteTerm(15) })
            .forEach { call -> assertEquals(ErrorCode.AUTH_MENU_DENIED, assertThrows(BusinessException::class.java) { call() }.errorCode) }
        login(setOf("sys-gloss"), unassigned = true)
        assertThrows(WriteAccessDeniedException::class.java) { service.createTerm("새용어", "뜻") }
        login(setOf("gloss-view"))
        assertThrows(MenuAccessDeniedException::class.java) { service.createTerm("새용어", "뜻") }
        assertTrue(repo.inserted.isEmpty())

        login(emptySet(), superAdmin = true, user = "10000")
        assertEquals(900, service.createTerm("새용어", "뜻")["termId"])
        assertEquals("term", assertThrows(InvalidParameterException::class.java) { service.createTerm("가".repeat(51), "뜻") }.field)
        assertEquals("definition", assertThrows(InvalidParameterException::class.java) { service.createTerm("용어", "가".repeat(501)) }.field)
    }

    @Test
    @DisplayName("GLV-02 gloss-view 만 — 요약·목록·상세·내려받기는 열리고 관리 지표·편집 표시·등록자 사번은 null")
    fun viewOnly() {
        login(setOf("gloss-view"))
        val s = service.getSummary()
        assertNull(s["myVariantCnt"]); assertNull(s["noVariantTermCnt"]); assertNull(s["canEditTerm"]); assertNull(s["canWriteVariant"])
        assertEquals(3L, s["termCnt"])
        @Suppress("UNCHECKED_CAST")
        val v = ((service.getTerms(null, 1, 50).first.single()["variants"]) as List<Map<String, Any?>>).single()
        assertNull(v["byEmpNo"]); assertNull(v["byDept"]); assertEquals(false, v["editable"], "쓰기 권한이 없으면 본인 것도 편집 불가")
        assertEquals("gloss-view", service.exportTerms(GlossaryExportRequest(menuId = "sys-gloss")).menuId, "권한 없는 화면으로 기록하지 않는다")
        assertTrue(service.exportTerms(null).headers.none { it.contains("등록자") })
        assertThrows(MenuAccessDeniedException::class.java) { service.normalize(com.dwje.api.model.request.GlossaryNormalizeRequest("문장")) }
        assertThrows(MenuAccessDeniedException::class.java) { service.createVariant(15, "로트2") }
        assertThrows(MenuAccessDeniedException::class.java) { service.getRiskVariants() }

        login(emptySet())
        assertThrows(MenuAccessDeniedException::class.java) { service.getSummary() }

        login(setOf("sys-gloss"))
        val m = service.getSummary()
        assertEquals(1L, m["myVariantCnt"]); assertEquals(false, m["canEditTerm"]); assertEquals(true, m["canWriteVariant"])
        assertTrue(service.exportTerms(GlossaryExportRequest(menuId = "sys-gloss")).headers.contains("유사어 등록자"))
    }

    @Test
    @DisplayName("GLV-05 내려받기 — 0건 404, 범위 코드 밖 400, ALL 은 조건을 무시")
    fun export() {
        login(setOf("gloss-view"))
        assertThrows(com.dwje.api.common.exception.ResourceNotFoundException::class.java) {
            service.exportTerms(GlossaryExportRequest(keyword = "없음"))
        }
        assertEquals(1, service.exportTerms(GlossaryExportRequest(keyword = "없음", scope = "ALL")).rows.size)
        assertEquals("scopeCd", assertThrows(InvalidParameterException::class.java) { service.exportTerms(GlossaryExportRequest(scopeCd = "PAGE")) }.field)
    }

    @Test
    @DisplayName("GLS-03 유사어 규칙 — 한 글자·숫자·날짜·공식 용어(자기 용어 포함)와 같은 낱말은 400, 다른 용어에 들어 있으면 경고")
    fun variantRules() {
        login(setOf("sys-gloss"))
        mapOf("계" to "2자 이상", "2026" to "숫자나 날짜", "9월" to "숫자나 날짜", "불량" to "공식 용어 [불량]", "lot" to "공식 용어 [LOT]")
            .forEach { (w, msg) ->
                val e = assertThrows(InvalidParameterException::class.java) { service.createVariant(15, w) }
                assertEquals("word", e.field); assertTrue(e.message.contains(msg), "$w → ${e.message}")
            }
        val ok = service.createVariant(15, "치수")
        assertEquals(listOf("'치수' 가 공식 용어 1건(예: 치수불량) 안에 들어 있습니다. 해당 용어는 치환하지 않습니다."), ok["warnings"])
        assertEquals(emptyList<String>(), service.createVariant(15, "로트")["warnings"])
    }
}
