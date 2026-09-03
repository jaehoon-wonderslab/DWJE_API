package com.dwje.api.service

import com.dwje.api.repository.GlossaryRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 현장 유사어 → 공식 용어 정규화기
 *
 * 자연어 질의 전처리와 「용어 정규화 미리보기」(No.177) API 가 공유한다.
 * 사전은 길이 내림차순으로 정렬되어 있어 긴 표현부터 치환하므로 부분 치환 오류가 발생하지 않는다.
 */
@Service
class GlossaryNormalizer(
    private val glossaryRepository: GlossaryRepository
) {

    /**
     * 정규화 결과와 치환 내역을 반환한다.
     *
     * @param text 원문
     * @return 정규화 문장 · 치환 내역 목록
     */
    @Transactional(readOnly = true)
    fun normalize(text: String): NormalizeResult {
        val dictionary = glossaryRepository.findNormalizationDictionary()
        var result = text
        val replacements = mutableListOf<Map<String, Any?>>()

        dictionary.forEach { entry ->
            val from = entry["from"] as? String ?: return@forEach
            val to = entry["to"] as? String ?: return@forEach

            // 이미 공식 용어로 쓰인 경우는 치환 대상이 아니다.
            if (from == to) return@forEach
            if (!result.contains(from)) return@forEach

            result = result.replace(from, to)
            replacements.add(
                mapOf(
                    "from" to from,
                    "to" to to,
                    "termId" to entry["termId"],
                    "variantId" to entry["variantId"]
                )
            )
        }

        return NormalizeResult(normalizedText = result, replacements = replacements)
    }

    /**
     * 정규화 결과
     *
     * @param normalizedText 공식 용어로 치환된 문장
     * @param replacements   치환 내역 — from, to, termId, variantId
     */
    data class NormalizeResult(
        val normalizedText: String,
        val replacements: List<Map<String, Any?>>
    )
}
