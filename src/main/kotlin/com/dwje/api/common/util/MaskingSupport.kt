package com.dwje.api.common.util

import com.dwje.api.common.security.UserPrincipal

/**
 * 데이터 접근 권한 기반 응답 마스킹 지원 클래스
 *
 * 마스킹 원칙(API 목록 표지)
 * > 데이터 접근 권한이 없는 항목은 API 응답 생성 단계에서 값을 null 로 반환하고
 * > masked 배열에 key 를 표기한다. 원본을 응답에 포함하지 않는다.
 *
 * 사용 예
 * ```
 * val mask = MaskingSupport(UserContext.current())
 * val row = mapOf(
 *     "product" to rs.getString("model_cd"),
 *     "qty"     to mask.on(DataField.QTY) { rs.getBigDecimal("qty") },
 *     "customer" to mask.on(DataField.CUSTOMER) { rs.getString("customer_nm") }
 * )
 * return ApiResponse.ok(row, mask.maskedKeys())
 * ```
 *
 * @param principal 인증 사용자 (권한 판정 주체)
 */
class MaskingSupport(private val principal: UserPrincipal?) {

    /** 이번 응답에서 실제로 마스킹된 항목 key 집합 */
    private val masked = linkedSetOf<String>()

    /**
     * 데이터 항목 권한을 판정해 값을 반환하거나 null 로 마스킹한다.
     *
     * @param fieldKey 데이터 항목 key ([DataField])
     * @param supplier 권한이 있을 때 평가할 원본 값 공급 함수 (권한이 없으면 호출하지 않는다)
     * @return 권한 보유 시 원본 값, 미보유 시 null
     */
    fun <T> on(fieldKey: String, supplier: () -> T?): T? {
        // 1. 권한이 없으면 원본을 평가하지 않고 즉시 null 반환 — 값이 메모리에 실리지 않게 한다.
        if (principal == null || !principal.canReadField(fieldKey)) {
            masked.add(fieldKey)
            return null
        }
        return supplier()
    }

    /**
     * 권한 보유 여부만 판정한다. (조건부 SQL 컬럼 선택 등에 사용)
     */
    fun allowed(fieldKey: String): Boolean = principal?.canReadField(fieldKey) ?: false

    /**
     * 권한이 없으면 마스킹 목록에 기록만 하고 판정 결과를 돌려준다.
     * SELECT 절에서 컬럼 자체를 제외할 때 사용한다.
     */
    fun check(fieldKey: String): Boolean {
        val ok = allowed(fieldKey)
        if (!ok) masked.add(fieldKey)
        return ok
    }

    /**
     * Map 형태 결과 행에서 지정 항목들을 일괄 마스킹한다.
     *
     * @param row       원본 결과 행 (가변 Map)
     * @param fieldKeys "응답 필드명" to "데이터 항목 key" 매핑
     */
    fun applyTo(row: MutableMap<String, Any?>, fieldKeys: Map<String, String>) {
        fieldKeys.forEach { (responseField, dataFieldKey) ->
            if (!allowed(dataFieldKey)) {
                if (row.containsKey(responseField)) {
                    row[responseField] = null
                    masked.add(dataFieldKey)
                }
            }
        }
    }

    /** 마스킹된 항목 key 목록 (응답 masked 배열) */
    fun maskedKeys(): List<String> = masked.toList()

    /** 마스킹 발생 건수 — 감사 로그 masked_cnt 기록용 */
    fun maskedCount(): Int = masked.size
}
