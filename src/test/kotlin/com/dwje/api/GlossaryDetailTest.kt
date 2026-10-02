package com.dwje.api

import com.dwje.api.common.exception.ResourceNotFoundException
import com.dwje.api.common.security.UserContext
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.service.GlossaryService
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

/** 용어 상세·관련 용어·위험 유사어 점검·내려받기 행 — 실제 로컬 DB (13 GLV-04·05, 07 GLS-03). 조회만 한다. */
@SpringBootTest(properties = ["app.ai.prewarm-enabled=false"])
@ActiveProfiles("local")
class GlossaryDetailTest {

    @Autowired lateinit var service: GlossaryService
    @Autowired lateinit var jdbc: NamedParameterJdbcTemplate

    @BeforeEach
    fun login() = UserContext.set(UserPrincipal("10000", "관리자", 1, "통합관리자", null, null, null, true))

    @AfterEach
    fun clear() = UserContext.clear()

    private fun long(sql: String) = jdbc.queryForObject(sql, MapSqlParameterSource(), Long::class.java)!!

    @Test
    @DisplayName("LOT 상세 — 유사어 전부, 관련 용어 10건 이내, 삭제 용어는 404")
    fun detail() {
        val lot = jdbc.queryForObject("SELECT term_id FROM ax.tb_gls_term WHERE term = 'LOT' AND use_flg = 'Y'", MapSqlParameterSource(), Int::class.java)!!
        val d = service.getTermDetail(lot)
        @Suppress("UNCHECKED_CAST") val variants = d["variants"] as List<Map<String, Any?>>
        assertEquals(long("SELECT count(*) FROM ax.tb_gls_variant WHERE term_id = $lot").toInt(), variants.size)
        @Suppress("UNCHECKED_CAST") val related = d["relatedTerms"] as List<Map<String, Any?>>
        assertTrue(related.size <= 10)
        assertTrue(related.all { it["reasonCd"] in setOf("REF_IN_DEF", "REF_BY", "SAME_DOMAIN_NAME") })
        assertEquals(false, d["blinded"])
        jdbc.queryForList("SELECT term_id FROM ax.tb_gls_term WHERE use_flg = 'N' LIMIT 1", MapSqlParameterSource(), Int::class.java)
            .firstOrNull()?.let { gone -> assertThrows(ResourceNotFoundException::class.java) { service.getTermDetail(gone) } }
    }

    @Test
    @DisplayName("위험 유사어 점검과 내려받기 행 수 — 사용 중 용어 수와 같다(ALL), 점검 목록은 위험 코드 순")
    fun risksAndExport() {
        @Suppress("UNCHECKED_CAST")
        val items = service.getRiskVariants()["items"] as List<Map<String, Any?>>
        assertTrue(items.isNotEmpty())
        val order = listOf("ONE_CHAR", "NUMERIC", "DATE_LIKE", "SAME_AS_TERM", "SUBSTRING_OF_TERM")
        assertEquals(items.map { order.indexOf(it["riskCd"]) }.sorted(), items.map { order.indexOf(it["riskCd"]) })
        val active = long("SELECT count(*) FROM ax.tb_gls_term WHERE use_flg = 'Y'")
        assertEquals(minOf(active, GlossaryService.EXPORT_MAX.toLong()).toInt(),
            service.exportTerms(com.dwje.api.model.request.GlossaryExportRequest(scopeCd = "ALL", domainCd = "불량유형")).rows.size)
    }
}
