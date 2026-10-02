package com.dwje.api.service

import com.dwje.api.repository.GlossaryRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 위험 유사어 규칙 — 정규화기(GLS-02)·유사어 등록 검증(GLS-03)·점검 목록이 같은 규칙을 쓴다.
 */
object GlossaryVariantRules {
    private val NUMERIC = Regex("^\\d+$")
    private val DATE_LIKE = Regex("^(\\d{1,2}월|\\d{1,2}일|\\d{4}년)$")

    /** 한 글자 · 숫자만 · 날짜 표현이면 그 코드, 아니면 null (앞뒤 공백은 뺀 값으로 판정) */
    fun riskOf(word: String): String? {
        val w = word.trim()
        return when {
            w.length < 2 -> "ONE_CHAR"
            NUMERIC.matches(w) -> "NUMERIC"
            DATE_LIKE.matches(w) -> "DATE_LIKE"
            else -> null
        }
    }

    /** 위험 코드 → 화면 표시 이름 (점검 목록 riskNm) */
    val RISK_NAMES = linkedMapOf(
        "ONE_CHAR" to "한 글자", "NUMERIC" to "숫자만", "DATE_LIKE" to "날짜 표현",
        "SAME_AS_TERM" to "공식 용어와 같은 낱말", "SUBSTRING_OF_TERM" to "다른 공식 용어에 포함"
    )

    /** 정규화에서 뺀 이유 (skipped[].reason) */
    fun skipReason(riskCd: String, sameAsTerm: String?): String = when (riskCd) {
        "DATE_LIKE" -> "날짜 표현은 치환하지 않습니다"
        "SAME_AS_TERM" -> "공식 용어 [$sameAsTerm] 과 같은 낱말입니다"
        "ONE_CHAR" -> "한 글자 유사어는 치환하지 않습니다"
        "NUMERIC" -> "숫자만으로 된 유사어는 치환하지 않습니다"
        else -> "치환하지 않습니다"
    }
}

/**
 * 현장 유사어 → 공식 용어 정규화기 (07 기획서 GLS-02)
 *
 * 자연어 질의 전처리와 「용어 정규화 미리보기」(No.177) API 가 공유한다.
 *
 * 예전에는 사전을 길이 순으로 돌며 문장 전체를 `replace` 했다. 그래서
 * (1) 치환 결과가 다음 유사어에 다시 걸려 연쇄 치환되고, (2) `R34B` 안의 `R3`, 「설계」 안의 「계」 처럼
 * 낱말 안쪽까지 바뀌고, (3) 「9월」·「2」 같은 날짜·숫자 유사어가 질문의 날짜를 망가뜨렸다.
 *
 * 지금 규칙
 * - **한 번만 훑는다** — 하나의 정규식으로 원문을 왼쪽부터 읽고, 치환한 결과는 다시 보지 않는다.
 * - **보호 구간** — 날짜(`2026-09-22`·`2026년`·`9월`·`22일`)와 수량(`3.5%`·`10개`)은 그대로 둔다.
 * - **경계** — 영문·숫자 유사어는 앞뒤가 영문·숫자가 아닐 때만, 한글 유사어는 앞이 한글이 아니고
 *   뒤가 조사·접미어([JOSA]) 또는 한글이 아닐 때만 맞는다.
 * - **위험 유사어 제외** — 한 글자·숫자만·날짜 표현·다른 공식 용어와 같은 낱말은 치환하지 않고 `skipped` 로 알린다.
 * - 대소문자를 가리지 않는다(`can`·`CAN` 같은 결과).
 */
@Service
class GlossaryNormalizer(
    private val glossaryRepository: GlossaryRepository
) {

    companion object {
        /** 한글 유사어 뒤에 붙어도 낱말 끝으로 보는 조사·접미어 */
        val JOSA = listOf("으로", "에서", "은", "는", "이", "가", "을", "를", "의", "에", "로", "도", "만", "과", "와", "률", "별")

        /** 그대로 두는 구간 — 날짜·수량 */
        const val PROTECTED_PATTERN = """\d{4}-\d{2}-\d{2}|\d{4}년|\d{1,2}월|\d{1,2}일|\d+(?:\.\d+)?\s?(?:%|ea|개|건)"""

        private fun isAlnum(c: Char) = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9'
        private fun isHangul(c: Char) = c in '가'..'힣'

        /** 유사어 하나의 정규식 조각 — 첫·끝 글자 종류에 따라 경계를 붙인다 */
        internal fun wordPattern(word: String): String {
            val head = when {
                isAlnum(word.first()) -> "(?<![A-Za-z0-9])"
                isHangul(word.first()) -> "(?<![가-힣])"
                else -> ""
            }
            val tail = when {
                isAlnum(word.last()) -> "(?![A-Za-z0-9])"
                isHangul(word.last()) -> "(?=(?:${JOSA.joinToString("|")})|[^가-힣]|$)"
                else -> ""
            }
            return head + Regex.escape(word) + tail
        }
    }

    /**
     * 정규화 결과와 치환 내역을 반환한다.
     *
     * @param text 원문
     * @return 정규화 문장 · 치환 내역(발생 1회당 1건, 원문 기준 위치) · 치환하지 않은 위험 유사어
     */
    @Transactional(readOnly = true)
    fun normalize(text: String): NormalizeResult {
        val dictionary = glossaryRepository.findNormalizationDictionary()

        val usable = LinkedHashMap<String, Map<String, Any?>>()     // lower(word) → 사전 행
        val skipped = ArrayList<Map<String, Any?>>()
        val lowerText = text.lowercase()
        dictionary.forEach { entry ->
            val from = (entry["from"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
            val to = entry["to"] as? String ?: return@forEach
            // 자기 공식 용어와 같은 낱말은 바꿔도 같으므로 조용히 건너뛴다
            if (from.equals(to, ignoreCase = true)) return@forEach
            val sameAsTerm = entry["sameAsTerm"] as String?
            val risk = GlossaryVariantRules.riskOf(from) ?: sameAsTerm?.let { "SAME_AS_TERM" }
            if (risk != null) {
                if (lowerText.contains(from.lowercase()) && skipped.none { it["word"] == from }) {
                    skipped += mapOf("word" to from, "termId" to entry["termId"], "reasonCd" to risk,
                        "reason" to GlossaryVariantRules.skipReason(risk, sameAsTerm))
                }
                return@forEach
            }
            usable.putIfAbsent(from.lowercase(), entry + mapOf("from" to from))
        }
        if (usable.isEmpty()) return NormalizeResult(text, emptyList(), skipped)

        // 긴 유사어가 먼저 맞도록 길이 내림차순으로 잇는다
        val alternatives = usable.keys.sortedByDescending { it.length }
            .joinToString("|") { wordPattern((usable[it]!!["from"] as String)) }
        val regex = Regex("(?<p>$PROTECTED_PATTERN)|(?<w>$alternatives)", RegexOption.IGNORE_CASE)

        val replacements = ArrayList<Map<String, Any?>>()
        val normalized = regex.replace(text) { m ->
            if (m.groups["p"] != null) return@replace m.value
            val entry = usable[m.value.lowercase()] ?: return@replace m.value
            replacements += mapOf(
                "from" to entry["from"], "to" to entry["to"], "termId" to entry["termId"], "variantId" to entry["variantId"],
                // 공식 용어의 뜻 — LLM [용어] 블록에 쓴다 (07 GLS-04)
                "definition" to entry["definition"],
                "fieldKey" to entry["fieldKey"],
                // 0 기반, end 는 그 글자를 포함하지 않는다 (text.substring(start, end) 가 원문 일치 부분)
                "start" to m.range.first, "end" to m.range.last + 1
            )
            entry["to"] as String
        }
        return NormalizeResult(normalizedText = normalized, replacements = replacements, skipped = skipped)
    }

    /**
     * 정규화 결과
     *
     * @param normalizedText 공식 용어로 치환된 문장
     * @param replacements   치환 내역 — from, to, termId, variantId, start, end
     * @param skipped        원문에 있었지만 위험 유사어라 치환하지 않은 것 — word, termId, reasonCd, reason
     */
    data class NormalizeResult(
        val normalizedText: String,
        val replacements: List<Map<String, Any?>>,
        val skipped: List<Map<String, Any?>> = emptyList()
    )
}
