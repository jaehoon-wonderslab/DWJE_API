package com.dwje.api

import com.dwje.api.common.util.DataField
import com.dwje.api.common.util.MaskingSupport
import com.dwje.api.common.util.PageRequestParam
import com.dwje.api.common.util.SortResolver
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.response.ApiResponse
import com.dwje.api.common.response.PageMeta
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 공통 규약 단위 테스트
 *
 * DB 연결 없이 검증 가능한 마스킹·페이징·정렬·응답 포맷 규칙을 확인한다.
 */
class ApiContextTest {

    /** 품질보증팀 기준 권한 — qty, yield, customer, mold 허용 */
    private val qaPrincipal = UserPrincipal(
        userId = "10001",
        userName = "테스터",
        deptId = 1,
        deptName = "품질보증팀",
        deptAbbr = "품보",
        positionCd = "STAFF",
        plantCd = "PL01",
        superAdmin = false,
        dataPerms = setOf(DataField.QTY, DataField.YIELD, DataField.CUSTOMER, DataField.MOLD)
    )

    @Test
    @DisplayName("데이터 접근 권한이 없는 항목은 null 로 마스킹되고 masked 배열에 기록된다")
    fun maskingHidesUnauthorizedField() {
        val mask = MaskingSupport(qaPrincipal)

        val qty = mask.on(DataField.QTY) { 1200L }
        val price = mask.on(DataField.PRICE) { 60.5 }

        assertEquals(1200L, qty)
        assertNull(price, "price 권한이 없으므로 값이 노출되면 안 된다")
        assertEquals(listOf(DataField.PRICE), mask.maskedKeys())
    }

    @Test
    @DisplayName("통합관리자는 전 데이터 항목을 열람한다")
    fun superAdminReadsAllFields() {
        val admin = qaPrincipal.copy(superAdmin = true, dataPerms = emptySet())
        val mask = MaskingSupport(admin)

        DataField.ALL.forEach { field ->
            assertTrue(mask.allowed(field), "통합관리자는 $field 를 열람할 수 있어야 한다")
        }
        assertTrue(mask.maskedKeys().isEmpty())
    }

    @Test
    @DisplayName("Map 결과 행의 마스킹 항목은 값이 제거된다")
    fun applyToMasksRowValues() {
        val mask = MaskingSupport(qaPrincipal)
        val row = mutableMapOf<String, Any?>("qty" to 100L, "amount" to 5000.0, "customer" to "A사")

        mask.applyTo(row, mapOf("qty" to DataField.QTY, "amount" to DataField.PRICE, "customer" to DataField.CUSTOMER))

        assertEquals(100L, row["qty"])
        assertNull(row["amount"])
        assertEquals("A사", row["customer"])
        assertEquals(listOf(DataField.PRICE), mask.maskedKeys())
    }

    @Test
    @DisplayName("페이징 기본값은 1페이지 50건이며 상한을 넘지 않는다")
    fun pagingDefaultsAndBounds() {
        val default = PageRequestParam.of(null, null)
        assertEquals(1, default.page)
        assertEquals(50, default.size)
        assertEquals(0, default.offset)

        val bounded = PageRequestParam.of(0, 99999)
        assertEquals(1, bounded.page)
        assertEquals(1000, bounded.size)

        val third = PageRequestParam.of(3, 20)
        assertEquals(40, third.offset)
    }

    @Test
    @DisplayName("정렬 파라미터는 화이트리스트 컬럼만 허용한다")
    fun sortResolverRejectsUnknownColumn() {
        val allowed = mapOf("qty" to "total_qty", "name" to "p.model_cd")

        assertEquals("ORDER BY total_qty DESC", SortResolver.resolve("qty,desc", allowed, "total_qty"))
        assertEquals("ORDER BY p.model_cd ASC", SortResolver.resolve("name", allowed, "total_qty"))
        assertEquals("ORDER BY total_qty", SortResolver.resolve(null, allowed, "total_qty"))

        // 화이트리스트에 없는 컬럼은 SQL 로 넘어가지 않는다.
        assertThrows(InvalidParameterException::class.java) {
            SortResolver.resolve("(SELECT 1)", allowed, "total_qty")
        }
    }

    @Test
    @DisplayName("표준 응답 포맷은 success/code/message 를 항상 포함한다")
    fun apiResponseFormat() {
        val ok = ApiResponse.ok(mapOf("a" to 1))
        assertTrue(ok.success)
        assertEquals("SUCCESS", ok.code)

        val page = ApiResponse.page(listOf(1, 2), PageMeta.of(2, 10, 35), listOf("price"))
        assertEquals(2, page.meta?.page)
        assertEquals(4, page.meta?.totalPages)
        assertEquals(listOf("price"), page.masked)

        val error = ApiResponse.error("E-AUTH-003", "데이터 접근 권한이 없습니다.", "price")
        assertFalse(error.success)
        assertEquals("price", error.error?.field)
    }
}
