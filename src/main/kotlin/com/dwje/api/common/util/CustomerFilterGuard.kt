package com.dwje.api.common.util

import com.dwje.api.common.exception.InvalidParameterException

/**
 * 고객사 조건 가드 — 고객사(customer) 데이터 권한이 없으면 고객사 코드로 거르는 조건을 받지 않는다.
 *
 * 값은 가려도 「customerCd=TSTA 로 거르면 D62 한 행만 남는다」 처럼 걸러진 결과로 고객사와 모델의 관계를 짐작할 수 있다.
 * 조건을 조용히 무시하면 화면은 거른 줄 알고 잘못 읽으므로 400 으로 알린다 (2026-10-03 데이터 권한 누출 수정).
 */
object CustomerFilterGuard {
    const val MESSAGE = "고객사 권한이 없어 고객사 조건을 쓸 수 없습니다."

    fun require(customerCd: String?, customerAllowed: Boolean) {
        if (!customerAllowed && !customerCd.isNullOrBlank()) throw InvalidParameterException(MESSAGE, "customerCd")
    }
}
