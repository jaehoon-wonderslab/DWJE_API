package com.dwje.api

import com.dwje.api.common.exception.BusinessException
import com.dwje.api.common.exception.ConflictingValueException
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.common.util.MenuId
import com.dwje.api.repository.GlossaryRepository
import com.dwje.api.repository.VectorIndexRepository
import com.dwje.api.service.AuthorizationService
import com.dwje.api.service.GlossaryNormalizer
import com.dwje.api.service.GlossaryService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * 용어 사전 중복 규약 (2026-09-16 · DB 유니크 인덱스 신설에 맞춤)
 *
 *      uq_gls_term_lower    UNIQUE (lower(btrim(term)))   ← 신설. 'can' / 'CAN' / ' can ' 은 한 용어다
 *      uq_tb_gls_term       UNIQUE (term)                 ← 기존. 원문 기준이라 대소문자를 못 막았다
 *      uq_gls_variant_word  UNIQUE (lower(word))          ← 유사어는 표 전체에서 한 번만
 *
 * 1. 등록·수정은 저장 전에 같은 기준으로 먼저 보고, 걸리면 409 에 **이미 등록된 표기**를 담는다
 * 2. 사전 조회를 통과한 뒤 경합으로 유니크에 걸려도 같은 409 다 — 어느 제약이든 500 이 나가지 않는다
 * 3. 자기 자신은 검사에서 뺀다 — 표기만 바꾸는 수정(can → CAN)이 제 이름에 막히면 안 된다
 * 4. 저장값은 trim 한다. 인덱스가 btrim 기준이라 ' CAN' 을 그대로 넣으면 검사 기준과 어긋난다
 */
class GlossaryDuplicateTest {

    /** 원천 없이 도는 저장소 — 용어·유사어 표를 메모리로 두고 유니크 인덱스까지 흉내 낸다 */
    private class MemRepo : GlossaryRepository(mock(NamedParameterJdbcTemplate::class.java)) {
        val terms = linkedMapOf<Int, MutableMap<String, Any?>>()
        val variants = linkedMapOf<Int, MutableMap<String, Any?>>()
        /** 사전 조회만 못 보게 가려 경합을 만든다 (DB 인덱스는 그대로 막는다) */
        var blindTo: String? = null
        private var seq = 0

        private fun key(s: String) = s.trim().lowercase()

        fun seedTerm(term: String, active: Boolean = true): Int {
            val id = ++seq
            terms[id] = mutableMapOf("termId" to id, "term" to term.trim(), "active" to active)
            return id
        }

        fun seedVariant(termId: Int, word: String): Int {
            val id = ++seq
            variants[id] = mutableMapOf("variantId" to id, "word" to word.trim(), "termId" to termId)
            return id
        }

        /** uq_gls_term_lower · uq_tb_gls_term */
        private fun termIndexGuard(term: String, selfId: Int?) {
            if (terms.any { it.key != selfId && key(it.value["term"] as String) == key(term) }) {
                throw DuplicateKeyException("uq_gls_term_lower")
            }
        }

        /** uq_gls_variant_word */
        private fun variantIndexGuard(word: String, selfId: Int?) {
            if (variants.any { it.key != selfId && key(it.value["word"] as String) == key(word) }) {
                throw DuplicateKeyException("uq_gls_variant_word")
            }
        }

        override fun findTermByName(term: String): Map<String, Any?>? =
            if (key(term) == blindTo) null
            else terms.values.firstOrNull { key(it["term"] as String) == key(term) }?.toMap()

        override fun findVariantByWord(word: String, excludeVariantId: Int?): Map<String, Any?>? =
            if (key(word) == blindTo) null
            else variants.values
                .firstOrNull { it["variantId"] != excludeVariantId && key(it["word"] as String) == key(word) }
                ?.let { it + mapOf("term" to terms[it["termId"]]?.get("term")) }

        override fun findDomainId(domainNm: String) = 4
        override fun existsTerm(termId: Int) = terms[termId]?.get("active") == true
        override fun existsVariant(variantId: Int) = variants.containsKey(variantId)
        override fun countVariantsByTerm(termId: Int) = variants.values.count { it["termId"] == termId }.toLong()

        override fun insertTerm(term: String, definition: String, domainId: Int, actor: String): Int {
            termIndexGuard(term, null)
            return seedTerm(term)
        }

        override fun updateTerm(termId: Int, term: String, definition: String, domainId: Int, actor: String): Int {
            termIndexGuard(term, termId)
            terms[termId]!!["term"] = term.trim()
            return 1
        }

        override fun reviveTerm(termId: Int, definition: String, domainId: Int, actor: String): Int {
            terms[termId]!!["active"] = true
            return 1
        }

        override fun insertVariant(termId: Int, word: String, ownerUserId: String, ownerDeptNm: String?): Int {
            variantIndexGuard(word, null)
            return seedVariant(termId, word)
        }

        override fun updateVariant(variantId: Int, word: String, ownerUserId: String, superAdmin: Boolean): Int {
            variantIndexGuard(word, variantId)
            variants[variantId]!!["word"] = word.trim()
            return 1
        }
    }

    private val admin = UserPrincipal(
        userId = "T1", userName = "t", deptId = 9, deptName = "전산팀", deptAbbr = null, positionCd = null,
        plantCd = null, superAdmin = true, menuPerms = setOf(MenuId.SYS_GLOSS)
    )

    private fun service(repo: MemRepo): GlossaryService {
        val auth = mock(AuthorizationService::class.java)
            .also { `when`(it.requireMenu(MenuId.SYS_GLOSS)).thenReturn(admin) }
        return GlossaryService(repo, mock(GlossaryNormalizer::class.java), mock(VectorIndexRepository::class.java), auth)
    }

    private fun repoWith(vararg terms: String) = MemRepo().apply { terms.forEach { seedTerm(it) } }

    @Test
    @DisplayName("용어 등록 — 대소문자·공백만 다른 이름은 409, 메시지에 이미 등록된 표기(CAN)를 담는다")
    fun createTermDuplicate() {
        val repo = repoWith("CAN")
        val svc = service(repo)

        val e = assertThrows(ConflictingValueException::class.java) { svc.createTerm("can", "정의", "치공구") }
        assertEquals("term", e.field)
        assertEquals(409, e.errorCode.status.value())
        assertTrue(e.message.startsWith("이미 등록된 용어입니다. [CAN]"), e.message)
        assertTrue(e.message.contains("입력: can"), "입력한 표기도 함께 알려 준다 : ${e.message}")

        // 앞뒤 공백도 같은 용어로 본다 — 인덱스가 btrim 기준이다
        assertThrows(ConflictingValueException::class.java) { svc.createTerm("  CAN  ", "정의", "치공구") }
        // 표기가 같으면 군더더기 없이 한 줄
        assertEquals("이미 등록된 용어입니다. [CAN]",
            assertThrows(ConflictingValueException::class.java) { svc.createTerm("CAN", "정의", "치공구") }.message)
    }

    @Test
    @DisplayName("용어 등록 — 사전 조회를 통과한 뒤 경합으로 유니크에 걸려도 같은 409 (500 이 나가지 않는다)")
    fun createTermRace() {
        val repo = repoWith("CAN").apply { blindTo = "can" }
        val e = assertThrows(ConflictingValueException::class.java) { service(repo).createTerm(" can ", "정의", "치공구") }
        assertEquals("term", e.field)
        assertEquals(409, e.errorCode.status.value())
        assertEquals("이미 등록된 용어입니다. [can]", e.message, "중단된 트랜잭션에서 다시 조회하지 않고 입력값으로 안내한다")
    }

    @Test
    @DisplayName("용어 등록 — 사용 중지된 동명 용어는 대소문자가 달라도 되살린다 (표기는 저장된 것을 유지)")
    fun createTermRevive() {
        val repo = MemRepo().apply { seedTerm("CAN", active = false).also { seedVariant(it, "캔") } }
        val out = service(repo).createTerm("can", "정의", "치공구")
        assertEquals(true, out["restored"])
        assertEquals("CAN", out["term"], "되살린 행의 표기를 그대로 알려 준다")
        assertEquals(1L, out["restoredVariants"])
        assertEquals(1, repo.terms.size, "새 행을 만들지 않는다 — 만들면 유니크에 걸려 500 이다")
    }

    @Test
    @DisplayName("용어 수정 — 남의 이름은 409, 표기만 바꾸는 자기 수정(can → CAN)은 통과, 저장값은 trim")
    fun updateTerm() {
        val repo = repoWith("CAN", "버(burr)")
        val svc = service(repo)

        val e = assertThrows(ConflictingValueException::class.java) { svc.updateTerm(2, "can", "정의", "치공구") }
        assertTrue(e.message.startsWith("이미 등록된 용어입니다. [CAN]"), e.message)

        assertEquals(true, svc.updateTerm(1, "  Can  ", "정의", "치공구")["success"])
        assertEquals("Can", repo.terms[1]!!["term"], "앞뒤 공백을 떼고 저장한다")
    }

    @Test
    @DisplayName("용어 수정 — 삭제된 용어가 이름을 점유하면 되살리라고 안내한다 (409)")
    fun updateTermBlockedByDeleted() {
        val repo = MemRepo().apply { seedTerm("CAN", active = false); seedTerm("버(burr)") }
        val e = assertThrows(ConflictingValueException::class.java) { service(repo).updateTerm(2, "can", "정의", "치공구") }
        assertEquals("term", e.field)
        assertTrue(e.message.contains("삭제된 용어가 이 이름을 쓰고 있어"), e.message)
        assertTrue(e.message.contains("[CAN]"), "삭제된 쪽의 표기를 보여 준다 : ${e.message}")
    }

    @Test
    @DisplayName("유사어 등록 — 다른 용어에 붙은 단어도 409, 어느 공식 용어가 쓰고 있는지 알려 준다")
    fun createVariantDuplicate() {
        val repo = repoWith("CAN", "버(burr)").apply { seedVariant(1, "깡통") }
        val svc = service(repo)

        val e = assertThrows(ConflictingValueException::class.java) { svc.createVariant(2, " 깡통 ") }
        assertEquals("word", e.field)
        assertEquals(409, e.errorCode.status.value())
        assertTrue(e.message.contains("이미 등록된 유사어입니다. [깡통]"), e.message)
        assertTrue(e.message.contains("공식 용어 [CAN]"), e.message)

        // 경합 — 사전 조회를 통과해도 같은 409
        repo.blindTo = "깡통"
        val r = assertThrows(ConflictingValueException::class.java) { svc.createVariant(2, "깡통") }
        assertEquals(409, r.errorCode.status.value())
        assertEquals("이미 등록된 유사어입니다. [깡통]", r.message)
    }

    @Test
    @DisplayName("유사어 등록·수정 — 저장값은 trim 하고, 자기 자신은 중복 검사에서 뺀다")
    fun variantTrimAndSelf() {
        val repo = repoWith("CAN")
        val svc = service(repo)

        val created = svc.createVariant(1, "  깡통  ")
        assertEquals("깡통", created["word"])
        assertEquals("깡통", repo.variants[repo.variants.keys.last()]!!["word"])

        val variantId = created["variantId"] as Int
        assertEquals(true, svc.updateVariant(variantId, " 깡통 ")["success"], "제 이름으로 다시 저장해도 막히지 않는다")
        assertEquals("깡통", repo.variants[variantId]!!["word"])
    }

    @Test
    @DisplayName("중복 409 는 E-RULE-001 로 나간다 — 회원가입 중복(400 E-VALID-002) 규약은 건드리지 않는다")
    fun errorCodeContract() {
        val e: BusinessException = assertThrows(ConflictingValueException::class.java) {
            service(repoWith("CAN")).createTerm("can", "정의", "치공구")
        }
        assertEquals("E-RULE-001", e.errorCode.code)
        assertEquals(409, e.errorCode.status.value())
    }
}
