package com.dwje.api

import com.dwje.api.repository.GlossaryRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.ActiveProfiles

/**
 * 용어 중복 검사 SQL 이 실제 DB 에서 도는지, 그리고 **DB 인덱스와 같은 기준**인지 확인한다.
 *
 * 서비스의 사전 조회와 DB 유니크 인덱스가 서로 다른 식을 쓰면
 * "조회로는 안 걸렸는데 INSERT 는 막히는" 구간이 생기고, 그게 그대로 500 이 된다.
 * 식을 글자로 맞춰 두는 것만으로는 어긋남을 막을 수 없어 실제 DB 에 던져 본다.
 */
@SpringBootTest
@ActiveProfiles("local")
class GlossaryDuplicateSqlTest {

    @Autowired lateinit var glossary: GlossaryRepository
    @Autowired lateinit var jdbc: NamedParameterJdbcTemplate

    /**
     * 사전 조회가 유니크 인덱스를 그대로 타는지 — 식이 어긋나면 Seq Scan 으로 떨어진다.
     */
    @Test
    @DisplayName("용어 조회가 uq_gls_term_lower 와 같은 식이라 인덱스를 탄다")
    fun termLookupUsesIndex() {
        val plan = jdbc.query(
            "EXPLAIN SELECT term_id FROM ax.tb_gls_term WHERE lower(btrim(term)) = lower(btrim(:term))",
            MapSqlParameterSource("term", "can")
        ) { rs, _ -> rs.getString(1) }.joinToString("\n")

        assertTrue(plan.contains("uq_gls_term_lower"), "사전 조회가 유니크 인덱스를 타지 않는다 :\n$plan")
    }

    /**
     * 대소문자·앞뒤 공백을 무시하고 **저장된 표기**를 돌려줘야 409 안내가 쓸모 있다.
     */
    @Test
    @DisplayName("용어·유사어 조회 SQL 이 실제로 실행되고, 저장된 표기를 돌려준다")
    fun lookupSql() {
        val any = jdbc.queryForObject(
            "SELECT term FROM ax.tb_gls_term WHERE use_flg = 'Y' ORDER BY term_id LIMIT 1",
            MapSqlParameterSource(), String::class.java
        )!!

        val found = glossary.findTermByName("  ${any.uppercase()}  ")
        assertNotNull(found, "대소문자·공백을 무시하고 찾지 못했다 : [$any]")
        assertEquals(any, found!!["term"], "입력값이 아니라 저장된 표기를 돌려줘야 한다")

        // 유사어 — 제외 파라미터가 null 일 때와 값이 있을 때 모두 던져 본다 (`:id::int IS NULL` 분기)
        glossary.findVariantByWord("존재하지-않는-유사어", null)
        glossary.findVariantByWord("존재하지-않는-유사어", 1)

        val variant = jdbc.query(
            "SELECT variant_id, word FROM ax.tb_gls_variant ORDER BY variant_id LIMIT 1",
            MapSqlParameterSource()
        ) { rs, _ -> rs.getInt(1) to rs.getString(2) }.firstOrNull() ?: return

        val (variantId, word) = variant
        assertNotNull(glossary.findVariantByWord(" $word ", null), "유사어를 공백 무시로 찾지 못했다 : [$word]")
        assertEquals(null, glossary.findVariantByWord(word, variantId), "자기 자신은 검사에서 빠져야 한다")
    }

    /**
     * 사전 조회가 놓친 경합을 막아 주는 마지막 보루다. 이게 없으면 조용히 중복이 쌓인다.
     *
     * 2026-09-16 운영 DB 에 직접 적용됐다. 이 검사가 깨지면 `db/V*.sql` 에 그 인덱스가
     * 빠져 있다는 뜻이다 — 새로 만든 DB 에는 대소문자 중복이 그대로 들어간다.
     */
    @Test
    @DisplayName("대소문자 중복을 막는 유니크 인덱스가 DB 에 있다")
    fun uniqueIndexExists() {
        val indexes = jdbc.query(
            "SELECT indexname FROM pg_indexes WHERE schemaname = 'ax' AND tablename IN ('tb_gls_term', 'tb_gls_variant')",
            MapSqlParameterSource()
        ) { rs, _ -> rs.getString(1) }

        assertTrue("uq_gls_term_lower" in indexes,
            "ax.tb_gls_term 에 uq_gls_term_lower 가 없다 — db/V*.sql 에 마이그레이션이 빠졌다. 현재 인덱스 : $indexes")
        assertTrue("uq_gls_variant_word" in indexes,
            "ax.tb_gls_variant 에 uq_gls_variant_word 가 없다. 현재 인덱스 : $indexes")
    }
}
