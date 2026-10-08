package com.dwje.api

import com.dwje.api.service.DataAttrDiscoveryService
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 새로 발견된 응답 데이터 이름(V83) — 값 이름 고르기 · 경로 묶기 */
class DataAttrDiscoveryTest {
    private val om = ObjectMapper()

    @Test
    @DisplayName("숫자 · 글자 · null 값의 이름만 — 목록 · 묶음 이름(items · total 객체)은 빼고 안으로 들어간다")
    fun scalarKeys() {
        val tree = om.readTree(
            """{"items":[{"lineNm":"2라인","inspCnt":1520,"reworkCnt":null,"tags":["a"]}],
                "total":{"ngCnt":34,"flag":true},"meta":{"page":1}}"""
        )
        assertEquals(setOf("lineNm", "inspCnt", "reworkCnt", "ngCnt", "page"), DataAttrDiscoveryService.scalarKeys(tree))
        assertEquals(2, DataAttrDiscoveryService.scalarKeys(tree, limit = 2).size, "상한")
    }

    @Test
    @DisplayName("숫자 · 긴 식별자 경로 조각은 {id} 로 묶는다")
    fun normalizePath() {
        assertEquals("/api/v1/alerts/{id}", DataAttrDiscoveryService.normalizePath("/api/v1/alerts/123"))
        assertEquals("/api/v1/quality/aoi/serials/{id}/detail",
            DataAttrDiscoveryService.normalizePath("/api/v1/quality/aoi/serials/3f2c9a1e-77aa-4b1c-9d00-1234abcd5678/detail"))
        assertEquals("/api/v1/dashboard/ai/summary", DataAttrDiscoveryService.normalizePath("/api/v1/dashboard/ai/summary"))
    }

    @Test
    @DisplayName("업무 데이터 API 만 본다 — 시스템관리 · 연동 · 내려받기 이력 · 업로드는 뺀다")
    fun observedPaths() {
        listOf("/api/v1/dashboard/ai/summary", "/api/v1/quality/aoi/dimension/summary", "/api/v1/reports/ship-plan", "/api/v1/alerts/12", "/api/v1/common/masters/products")
            .forEach { assertEquals(true, DataAttrDiscoveryService.observed(it), it) }
        listOf("/api/v1/system/accounts", "/api/v1/sync/jobs", "/api/v1/download-logs", "/api/v1/dashboard/uploads", "/api/v1/alert-conditions", "/api/v1/ai/chat/history")
            .forEach { assertEquals(false, DataAttrDiscoveryService.observed(it), it) }
    }

    @Test
    @DisplayName("값이 키 자리에 들어간 묶음(loss: {품질검사, BURR …})은 묶음 이름 하나로 — BURR 를 따로 남기지 않는다")
    fun valueKeyedMap() {
        val tree = om.readTree(
            """{"rows":[{"model":"A","inputQty":10,"loss":{"품질검사":3,"스크래치":5,"BURR":4},"mgmt":{"재작업":1,"LOT 보류":2}}],
                "lossTotals":{"품질검사":30,"BURR":40}}"""
        )
        val keys = DataAttrDiscoveryService.scalarKeys(tree)
        assertEquals(setOf("model", "inputQty", "loss", "mgmt", "lossTotals"), keys)
    }
}
