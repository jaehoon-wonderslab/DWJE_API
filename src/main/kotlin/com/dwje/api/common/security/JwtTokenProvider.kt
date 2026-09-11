package com.dwje.api.common.security

import com.dwje.api.common.exception.UnauthenticatedException
import com.dwje.api.config.JwtProperties
import io.jsonwebtoken.Claims
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.Date
import javax.crypto.SecretKey

/**
 * JWT 토큰 발급·검증 컴포넌트
 *
 * Access Token 에는 사번·부서·통합관리자 여부 등 권한 판정 최소 정보만 담고,
 * 상세 메뉴/데이터 권한은 요청 시점에 DB 에서 조회해 최신 상태를 반영한다.
 */
@Component
class JwtTokenProvider(private val props: JwtProperties) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** HMAC-SHA 서명 키 */
    private val key: SecretKey = Keys.hmacShaKeyFor(props.secret.toByteArray(Charsets.UTF_8))

    companion object {
        const val CLAIM_USER_NAME = "unm"
        const val CLAIM_DEPT_ID = "did"
        const val CLAIM_DEPT_NAME = "dnm"
        const val CLAIM_SUPER_ADMIN = "sa"
        const val CLAIM_PLANT_CD = "plt"
        const val CLAIM_TOKEN_TYPE = "typ"
        const val CLAIM_IMPERSONATED = "imp"

        const val TYPE_ACCESS = "access"
        const val TYPE_REFRESH = "refresh"
        /** 파일 프록시용 짧은 토큰 — 이미지 한 장(sub=imageId)만 열 수 있다. */
        const val TYPE_FILE = "file"
    }

    /**
     * Access Token 발급
     *
     * @param userId       사번
     * @param userName     사용자명
     * @param deptId       부서 ID
     * @param deptName     부서명
     * @param superAdmin   통합관리자 여부
     * @param plantCd      사업장 코드
     * @param impersonated 계정 전환 여부
     */
    fun createAccessToken(
        userId: String,
        userName: String,
        deptId: Int,
        deptName: String,
        superAdmin: Boolean,
        plantCd: String?,
        impersonated: Boolean = false
    ): String {
        val now = Date()
        val expiry = Date(now.time + props.accessTokenValiditySec * 1000)
        return Jwts.builder()
            .subject(userId)
            .issuer(props.issuer)
            .issuedAt(now)
            .expiration(expiry)
            .claim(CLAIM_USER_NAME, userName)
            .claim(CLAIM_DEPT_ID, deptId)
            .claim(CLAIM_DEPT_NAME, deptName)
            .claim(CLAIM_SUPER_ADMIN, superAdmin)
            .claim(CLAIM_PLANT_CD, plantCd)
            .claim(CLAIM_IMPERSONATED, impersonated)
            .claim(CLAIM_TOKEN_TYPE, TYPE_ACCESS)
            .signWith(key)
            .compact()
    }

    /**
     * Refresh Token 발급 — 사번과 토큰 종류만 담는다.
     */
    fun createRefreshToken(userId: String): String {
        val now = Date()
        val expiry = Date(now.time + props.refreshTokenValiditySec * 1000)
        return Jwts.builder()
            .subject(userId)
            .issuer(props.issuer)
            .issuedAt(now)
            .expiration(expiry)
            .claim(CLAIM_TOKEN_TYPE, TYPE_REFRESH)
            .signWith(key)
            .compact()
    }

    /**
     * 토큰 서명·만료를 검증하고 Claims 를 반환한다.
     *
     * @throws UnauthenticatedException 서명 불일치·만료·형식 오류
     */
    fun parse(token: String): Claims =
        try {
            Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload
        } catch (e: ExpiredJwtException) {
            log.warn("만료된 토큰 접근 : sub={}", e.claims?.subject)
            throw UnauthenticatedException("세션이 만료되었습니다. 다시 로그인해 주세요.")
        } catch (e: JwtException) {
            log.warn("유효하지 않은 토큰 : {}", e.message)
            throw UnauthenticatedException("유효하지 않은 인증 토큰입니다.")
        }

    /**
     * Refresh Token 을 검증하고 사번을 반환한다.
     */
    fun parseRefreshToken(token: String): String {
        val claims = parse(token)
        if (claims[CLAIM_TOKEN_TYPE] != TYPE_REFRESH) {
            throw UnauthenticatedException("갱신 토큰이 아닙니다.")
        }
        return claims.subject
    }

    /**
     * 파일 프록시 토큰 발급 — `<img src>` 가 Authorization 헤더를 못 보내므로 URL 에 싣는다.
     *
     * Access Token 을 URL 에 넣으면 접속 로그·브라우저 이력에 세션 전체가 남는다. 그래서
     * **이미지 한 장만 여는** 별도 토큰을 짧게 발급한다.
     *
     * @param resourceId 열 수 있는 자원 ID (이미지 ID)
     * @param actor      발급 요청 사번 (감사용)
     * @param ttlSec     유효시간(초)
     */
    fun createFileToken(resourceId: String, actor: String, ttlSec: Long): String {
        val now = Date()
        return Jwts.builder()
            .subject(resourceId)
            .issuer(props.issuer)
            .issuedAt(now)
            .expiration(Date(now.time + ttlSec * 1000))
            .claim(CLAIM_TOKEN_TYPE, TYPE_FILE)
            .claim("act", actor)
            .signWith(key)
            .compact()
    }

    /**
     * 파일 프록시 토큰을 검증하고, 요청한 자원과 일치하는지 확인한다.
     *
     * @throws UnauthenticatedException 서명·만료 오류, 종류 불일치, 다른 자원의 토큰
     */
    fun verifyFileToken(token: String, resourceId: String): String {
        val claims = parse(token)
        if (claims[CLAIM_TOKEN_TYPE] != TYPE_FILE || claims.subject != resourceId) {
            throw UnauthenticatedException("이 파일에 대한 접근 토큰이 아닙니다.")
        }
        return claims["act"]?.toString() ?: "-"
    }

    /** Access Token 유효시간(초) */
    fun accessTokenValiditySec(): Long = props.accessTokenValiditySec
}
