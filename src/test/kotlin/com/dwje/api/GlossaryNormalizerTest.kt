package com.dwje.api

import com.dwje.api.model.request.GlossaryNormalizeRequest
import com.dwje.api.repository.GlossaryRepository
import com.dwje.api.service.GlossaryNormalizer
import jakarta.validation.Validation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/** 정규화 알고리즘 — 07 기획서 GLS-02 (한 번만 훑기·경계·보호 구간·위험 유사어 제외) */
class GlossaryNormalizerTest {

    /** (유사어, 공식 용어, 같은 낱말인 다른 공식 용어) */
    private fun normalizer(vararg dict: Triple<String, String, String?>) = GlossaryNormalizer(
        object : GlossaryRepository(mock(NamedParameterJdbcTemplate::class.java)) {
            override fun findNormalizationDictionary() = dict.mapIndexed { i, (from, to, same) ->
                mapOf<String, Any?>("variantId" to i + 1, "from" to from, "to" to to, "termId" to 100 + i, "sameAsTerm" to same)
            }
        }
    )

    private fun t(from: String, to: String, same: String? = null) = Triple(from, to, same)

    @Test
    @DisplayName("날짜 구간은 그대로 — 숫자·날짜 유사어는 치환하지 않고 skipped 로 알린다")
    fun keepsDates() {
        val r = normalizer(t("2", "R"), t("9월", "월")).normalize("2026년 9월 22일 불량률 알려줘")
        assertEquals("2026년 9월 22일 불량률 알려줘", r.normalizedText)
        assertTrue(r.replacements.isEmpty())
        assertEquals(setOf("2" to "ONE_CHAR", "9월" to "DATE_LIKE"), r.skipped.map { it["word"] to it["reasonCd"] }.toSet())
    }

    @Test
    @DisplayName("낱말 안쪽은 바꾸지 않는다 — 「설계」 의 계, R34B 의 R3")
    fun boundaries() {
        assertEquals("지난달 설계 변경 계획", normalizer(t("계", "TTL")).normalize("지난달 설계 변경 계획").normalizedText)
        assertEquals("R34B 품번", normalizer(t("R3", "R")).normalize("R34B 품번").normalizedText)
        assertEquals("R 품번", normalizer(t("R3", "R")).normalize("R3 품번").normalizedText)
    }

    @Test
    @DisplayName("조사·접미어 앞에서는 바꾸고 위치를 원문 기준(0 기반, end 배타)으로 준다")
    fun josaAndPosition() {
        val text = "2026년 9월 22일 로트별 불량률 알려줘"
        val r = normalizer(t("로트", "LOT")).normalize(text)
        assertEquals("2026년 9월 22일 LOT별 불량률 알려줘", r.normalizedText)
        val rep = r.replacements.single()
        assertEquals("로트", text.substring(rep["start"] as Int, rep["end"] as Int))
        assertEquals(13, rep["start"]); assertEquals(15, rep["end"])
    }

    @Test
    @DisplayName("대소문자를 가리지 않고, 치환 결과는 다시 보지 않는다(연쇄 치환 없음), 발생마다 1건")
    fun caseAndChain() {
        val n = normalizer(t("ln", "LINE"), t("깡통", "CAN"), t("CAN", "MOLD"))
        assertEquals("CAN LINE", n.normalize("깡통 ln").normalizedText)
        assertEquals(n.normalize("can ln").normalizedText, n.normalize("CAN LN").normalizedText, "대소문자 무관")
        val chain = n.normalize("깡통 라인 ln, Ln")
        assertEquals("CAN 라인 LINE, LINE", chain.normalizedText, "CAN 이 다시 MOLD 로 바뀌면 안 된다")
        assertEquals(3, chain.replacements.size)
    }

    @Test
    @DisplayName("다른 공식 용어와 같은 낱말은 치환하지 않는다(SAME_AS_TERM), 빈 사전은 원문 그대로")
    fun sameAsTermAndEmpty() {
        val r = normalizer(t("불량", "ISSUE", "불량")).normalize("어제 캔 라인에서 찍힘 불량")
        assertEquals("어제 캔 라인에서 찍힘 불량", r.normalizedText)
        assertEquals("공식 용어 [불량] 과 같은 낱말입니다", r.skipped.single()["reason"])
        val empty = normalizer().normalize("아무 문장")
        assertEquals("아무 문장", empty.normalizedText); assertTrue(empty.replacements.isEmpty())
    }

    @Test
    @DisplayName("미리보기 문장은 2,000자까지")
    fun requestLimit() {
        val v = Validation.buildDefaultValidatorFactory().validator
        assertTrue(v.validate(GlossaryNormalizeRequest("가".repeat(2000))).isEmpty())
        assertEquals(setOf("text"), v.validate(GlossaryNormalizeRequest("가".repeat(2001))).map { it.propertyPath.toString() }.toSet())
    }
}
