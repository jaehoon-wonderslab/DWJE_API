package com.dwje.api

import com.dwje.api.common.response.DataFieldMaskingAdvice
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 응답 필드명 공통 마스킹 (2026-09-23) — 운영 중 추가한 항목도 API 응답에서 가려야 한다.
 */
class DataFieldMaskingAdviceTest {

    private val mapper = jacksonObjectMapper()

    @Test
    @DisplayName("열람 못 하는 항목의 필드명만 고른다 — 판정은 항목 key 당 한 번")
    fun blindAttrsFiltersByFieldKey() {
        val calls = mutableListOf<String>()
        val blind = DataFieldMaskingAdvice.blindAttrs(
            mapOf("eqptNm" to "f_new", "okQty" to "qty", "ngQty" to "qty", "customer" to "customer")
        ) { key -> calls += key; key == "qty" }
        assertEquals(mapOf("eqptNm" to "f_new", "customer" to "customer"), blind)
        assertEquals(3, calls.size)
    }

    @Test
    @DisplayName("깊이와 상관없이 같은 이름 키를 null 로 — 하위 배열·객체는 통째로, 없는 키는 masked 에 넣지 않는다")
    fun masksNestedKeys() {
        val tree: JsonNode = mapper.valueToTree(
            mapOf(
                "items" to listOf(
                    mapOf("eqptNm" to "PRESS-1", "okQty" to 10, "children" to listOf(mapOf("eqptNm" to "PRESS-2"))),
                    mapOf("eqptNm" to null, "other" to "x")
                ),
                "totals" to mapOf("insUsers" to listOf("a", "b"))
            )
        )
        val hit = DataFieldMaskingAdvice.maskTree(tree, mapOf("eqptNm" to "f_new", "insUsers" to "worker", "moldCd" to "mold"))

        assertEquals(setOf("f_new", "worker"), hit)
        assertTrue(tree["items"][0]["eqptNm"].isNull)
        assertTrue(tree["items"][0]["children"][0]["eqptNm"].isNull)
        assertEquals(10, tree["items"][0]["okQty"].asInt())
        assertEquals("x", tree["items"][1]["other"].asText())
        assertTrue(tree["totals"]["insUsers"].isNull)
    }

    @Test
    @DisplayName("가릴 키가 없으면 트리를 건드리지 않는다")
    fun untouchedWhenAbsent() {
        val tree: JsonNode = mapper.valueToTree(mapOf("a" to 1, "b" to listOf(mapOf("c" to 2))))
        val before = tree.toString()
        assertTrue(DataFieldMaskingAdvice.maskTree(tree, mapOf("eqptNm" to "f_new")).isEmpty())
        assertEquals(before, tree.toString())
    }
}
