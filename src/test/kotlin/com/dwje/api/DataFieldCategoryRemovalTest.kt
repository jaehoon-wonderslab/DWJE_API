package com.dwje.api

import com.dwje.api.common.security.JwtTokenProvider
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.transaction.annotation.Transactional
import java.io.File

/**
 * 데이터 접근 항목 「분류」 제거(2026-10-07) — API 는 category_cd · 공통코드 DATA_FIELD_CATEGORY 를 읽지도 쓰지도 않는다.
 * DB 에 열이 남은 지금(V81 전)과 지운 뒤 모두에서 돌아야 하므로 SQL 에 그 이름이 없어야 한다. 실제 로컬 DB, 롤백. 감사는 목.
 */
@SpringBootTest(properties = ["app.ai.prewarm-enabled=false"])
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Transactional
class DataFieldCategoryRemovalTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var tokens: JwtTokenProvider
    @Autowired lateinit var json: ObjectMapper
    @MockitoBean lateinit var audit: com.dwje.api.service.AuditLogService

    private val bearer by lazy { "Bearer " + tokens.createAccessToken("10000", "관리자", 1, "통합관리자", true, null) }

    private fun getJson(path: String): JsonNode {
        val r = mvc.perform(get(path).header("Authorization", bearer)).andReturn().response
        assertEquals(200, r.status, r.contentAsString)
        return json.readTree(r.getContentAsString(Charsets.UTF_8))
    }

    @Test
    @DisplayName("SQL·코드 검증에 category_cd · DATA_FIELD_CATEGORY 가 없다 — V81(열 삭제) 뒤에도 같은 코드로 동작")
    fun noColumnReference() {
        val hits = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .flatMap { f -> f.readLines().mapIndexedNotNull { i, line ->
                if (line.contains("DATA_FIELD_CATEGORY") || (line.contains("category_cd") && line.contains("data_field", ignoreCase = true)) ||
                    (f.name.startsWith("DataField") && line.contains("category_cd"))) "${f.name}:${i + 1}" else null
            } }
            .filterNot { it.startsWith("DataFieldRepository.kt:11") } // 「없앴다」 는 설명 주석
            .toList()
        assertTrue(hits.isEmpty(), hits.toString())
    }

    @Test
    @DisplayName("응답에 category · categoryNm 없음 — /auth/me dataFields, 항목 목록, 데이터 권한 표 / 요청의 category 는 받고 버림(없는 코드여도 400 아님)")
    fun responsesAndRequests() {
        val me = getJson("/api/v1/auth/me").path("data").path("dataFields")
        assertTrue(me.size() > 0)
        me.forEach { assertFalse(it.has("category") || it.has("categoryNm"), it.toString()) }

        val fields = getJson("/api/v1/system/data-fields").path("data")
        val items = when { fields.isArray -> fields; fields.has("fields") -> fields.path("fields"); else -> fields.path("items") }
        assertTrue(items.size() > 0)
        items.forEach { assertFalse(it.has("category") || it.has("categoryNm"), it.toString()) }

        // reservedAttrs(가릴 수 없는 응답 필드명) 에는 AI 모델 설정 화면이 쓰는 category 가 남는다 — 항목(fields)만 본다
        val permFields = getJson("/api/v1/system/data-perms").path("data").path("fields")
        assertTrue(permFields.size() > 0)
        permFields.forEach { assertFalse(it.has("category") || it.has("categoryNm"), it.toString()) }

        val created = mvc.perform(post("/api/v1/system/data-fields").header("Authorization", bearer)
            .contentType(MediaType.APPLICATION_JSON).content("""{"fieldKey":"zt-cat","name":"ZT 분류 시험","category":"NO_SUCH"}"""))
            .andReturn().response
        assertEquals(200, created.status, created.contentAsString)
        assertFalse(json.readTree(created.contentAsString).path("data").has("category"))
    }
}
