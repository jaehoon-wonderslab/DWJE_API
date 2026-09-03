package com.dwje.api.common.security

import com.dwje.api.common.exception.UnauthenticatedException

/**
 * 요청 스레드 단위 인증 사용자 보관소
 *
 * JwtAuthFilter 가 요청 진입 시 [set], 응답 완료 시 [clear] 를 호출한다.
 * 컨트롤러/서비스는 [current] 또는 [currentOrNull] 로 접근한다.
 */
object UserContext {

    private val holder = ThreadLocal<UserPrincipal?>()

    /** 인증 사용자 바인딩 */
    fun set(principal: UserPrincipal) = holder.set(principal)

    /** 인증 사용자 조회 — 미인증이면 E-AUTH-001 */
    fun current(): UserPrincipal = holder.get() ?: throw UnauthenticatedException()

    /** 인증 사용자 조회 — 미인증이면 null (화이트리스트 API 용) */
    fun currentOrNull(): UserPrincipal? = holder.get()

    /** 스레드 로컬 해제 (스레드 풀 재사용 시 정보 누수 방지) */
    fun clear() = holder.remove()
}
