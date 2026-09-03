package com.dwje.api.repository

import com.dwje.api.common.util.Rs
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

/**
 * 이메일 인증 Repository
 *
 * 참조 테이블 : ax.tb_sys_email_verify (V8__email_verification.sql)
 *
 * 인증 코드는 해시로만 저장하며, 검증 성공 시 1회용 토큰을 발급한다.
 */
@Repository
class EmailVerificationRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) {

    /**
     * 인증 요청을 등록한다.
     *
     * @param email        수신 이메일
     * @param purposeCd    인증 목적 (SIGNUP / PASSWORD_RESET)
     * @param codeHash     인증 코드 해시
     * @param targetUserId 비밀번호 찾기 대상 계정 (가입은 null)
     * @param expireMinutes 유효 시간(분). 만료 시각은 **DB 시계** 로 계산한다.
     * @return 생성된 인증 요청 ID
     *
     * 시각 비교를 애플리케이션에서 하면 JVM(KST)과 DB(UTC)의 시간대가 어긋나 오판한다.
     * 이 클래스의 모든 시간 계산은 DB 의 now() 를 기준으로 한다.
     */
    fun insertRequest(
        email: String,
        purposeCd: String,
        codeHash: String,
        targetUserId: String?,
        expireMinutes: Int,
        ipAddr: String?
    ): Long {
        val sql = """
            INSERT INTO ax.tb_sys_email_verify (
                email, purpose_cd, code_hash, target_user_id, expires_at, ip_addr, send_result_cd
            ) VALUES (
                :email, :purposeCd, :codeHash, :targetUserId,
                now() + make_interval(mins => :expireMinutes), CAST(:ipAddr AS inet), 'SENT'
            )
            RETURNING verify_id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("email", email)
            .addValue("purposeCd", purposeCd)
            .addValue("codeHash", codeHash)
            .addValue("targetUserId", targetUserId)
            .addValue("expireMinutes", expireMinutes)
            .addValue("ipAddr", ipAddr)

        return jdbcTemplate.queryForObject(sql, params, Long::class.java) ?: 0L
    }

    /**
     * 발송 결과를 기록한다. (실패 시 사유 포함)
     */
    fun updateSendResult(verifyId: Long, resultCd: String, failReason: String?): Int {
        val sql = """
            UPDATE ax.tb_sys_email_verify
               SET send_result_cd = :resultCd,
                   fail_reason    = :failReason
             WHERE verify_id = :verifyId
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("verifyId", verifyId)
            .addValue("resultCd", resultCd)
            .addValue("failReason", failReason?.take(300))

        return jdbcTemplate.update(sql, params)
    }

    /**
     * 검증 대상인 최신 인증 요청을 조회한다.
     *
     * 아직 소모되지 않고 시도 상한에 걸리지 않은 가장 최근 요청 1건을 본다.
     */
    fun findLatestPending(email: String, purposeCd: String, maxAttempts: Int): Map<String, Any?>? {
        val sql = """
            SELECT verify_id, email, purpose_cd, code_hash, target_user_id,
                   verified_at, consumed_at, attempt_cnt,
                   -- 만료 판정은 DB 시계로 한다 (JVM 시간대와 무관)
                   extract(epoch FROM (expires_at - now()))::bigint AS expires_in_sec
            FROM ax.tb_sys_email_verify
            WHERE lower(email) = lower(:email)
              AND purpose_cd   = :purposeCd
              AND consumed_at IS NULL
              AND attempt_cnt  < :maxAttempts
            ORDER BY ins_date DESC
            LIMIT 1
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("email", email)
            .addValue("purposeCd", purposeCd)
            .addValue("maxAttempts", maxAttempts)

        return jdbcTemplate.query(sql, params) { rs, _ ->
            mapOf(
                "verifyId" to rs.getLong("verify_id"),
                "email" to rs.getString("email"),
                "purposeCd" to rs.getString("purpose_cd"),
                "codeHash" to rs.getString("code_hash"),
                "targetUserId" to rs.getString("target_user_id"),
                "expiresInSec" to rs.getLong("expires_in_sec"),
                "verifiedAt" to Rs.dateTime(rs, "verified_at"),
                "attemptCnt" to rs.getInt("attempt_cnt")
            )
        }.firstOrNull()
    }

    /** 코드 검증 시도 횟수를 1 증가시키고 증가 후 값을 돌려준다. */
    fun increaseAttempt(verifyId: Long): Int {
        val sql = """
            UPDATE ax.tb_sys_email_verify
               SET attempt_cnt = attempt_cnt + 1
             WHERE verify_id = :verifyId
            RETURNING attempt_cnt
        """.trimIndent()

        return jdbcTemplate.queryForObject(sql, MapSqlParameterSource("verifyId", verifyId), Int::class.java) ?: 0
    }

    /**
     * 검증 성공을 기록하고 1회용 토큰을 저장한다.
     */
    fun markVerified(verifyId: Long, token: String): Int {
        val sql = """
            UPDATE ax.tb_sys_email_verify
               SET verified_at  = now(),
                   verify_token = :token
             WHERE verify_id    = :verifyId
               AND verified_at IS NULL
        """.trimIndent()

        val params = MapSqlParameterSource().addValue("verifyId", verifyId).addValue("token", token)
        return jdbcTemplate.update(sql, params)
    }

    /**
     * 1회용 토큰을 조회한다. (아직 소모되지 않고 유효 시간 내인 것만)
     *
     * @param tokenExpireMinutes 검증 시각 기준 토큰 유효 시간(분)
     */
    fun findUsableToken(token: String, purposeCd: String, tokenExpireMinutes: Int): Map<String, Any?>? {
        val sql = """
            SELECT verify_id, email, purpose_cd, target_user_id, verified_at
            FROM ax.tb_sys_email_verify
            WHERE verify_token = :token
              AND purpose_cd   = :purposeCd
              AND verified_at IS NOT NULL
              AND consumed_at IS NULL
              AND verified_at >= now() - make_interval(mins => :tokenExpireMinutes)
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("token", token)
            .addValue("purposeCd", purposeCd)
            .addValue("tokenExpireMinutes", tokenExpireMinutes)

        return jdbcTemplate.query(sql, params) { rs, _ ->
            mapOf(
                "verifyId" to rs.getLong("verify_id"),
                "email" to rs.getString("email"),
                "targetUserId" to rs.getString("target_user_id"),
                "verifiedAt" to Rs.dateTime(rs, "verified_at")
            )
        }.firstOrNull()
    }

    /**
     * 토큰을 소모 처리한다. (1회용 보장)
     *
     * @return 실제로 소모된 건수. 0 이면 이미 사용된 토큰이다.
     */
    fun consumeToken(verifyId: Long): Int {
        val sql = """
            UPDATE ax.tb_sys_email_verify
               SET consumed_at = now()
             WHERE verify_id   = :verifyId
               AND consumed_at IS NULL
        """.trimIndent()

        return jdbcTemplate.update(sql, MapSqlParameterSource("verifyId", verifyId))
    }

    /**
     * 마지막 발송 이후 경과 초를 조회한다. (재발송 대기 판정)
     *
     * 애플리케이션에서 시각을 빼면 JVM·DB 시간대 차이만큼 어긋나 제한이 무력화된다.
     * 반드시 DB 시계로 계산한다.
     *
     * @return 경과 초. 발송 이력이 없으면 null
     */
    fun findSecondsSinceLastSend(email: String, purposeCd: String): Long? {
        val sql = """
            SELECT extract(epoch FROM (now() - max(ins_date)))::bigint AS elapsed_sec
            FROM ax.tb_sys_email_verify
            WHERE lower(email) = lower(:email) AND purpose_cd = :purposeCd
        """.trimIndent()

        val params = MapSqlParameterSource().addValue("email", email).addValue("purposeCd", purposeCd)
        return jdbcTemplate.query(sql, params) { rs, _ ->
            val v = rs.getLong("elapsed_sec")
            if (rs.wasNull()) null else v
        }.firstOrNull()
    }

    /**
     * 당일 발송 횟수를 조회한다. (일일 상한 판정)
     */
    fun countTodaySends(email: String): Long {
        val sql = """
            SELECT count(*)
            FROM ax.tb_sys_email_verify
            WHERE lower(email) = lower(:email)
              AND ins_date >= date_trunc('day', now())
        """.trimIndent()

        return jdbcTemplate.queryForObject(sql, MapSqlParameterSource("email", email), Long::class.java) ?: 0L
    }

    /**
     * 같은 이메일·목적의 기존 미소모 요청을 폐기한다. (새 코드 발송 시)
     *
     * 이전 코드가 계속 유효하면 공격자가 여러 코드를 동시에 시도할 수 있다.
     */
    fun expirePrevious(email: String, purposeCd: String): Int {
        val sql = """
            UPDATE ax.tb_sys_email_verify
               SET consumed_at = now(),
                   fail_reason = '새 인증 코드 발송으로 폐기'
             WHERE lower(email) = lower(:email)
               AND purpose_cd   = :purposeCd
               AND consumed_at IS NULL
        """.trimIndent()

        val params = MapSqlParameterSource().addValue("email", email).addValue("purposeCd", purposeCd)
        return jdbcTemplate.update(sql, params)
    }

    /**
     * 사번과 이메일이 모두 일치하는 계정을 찾는다. (비밀번호 찾기 본인 확인)
     *
     * @return 계정 정보. 일치하는 계정이 없으면 null
     */
    fun findUserByEmpNoAndEmail(empNo: String, email: String): Map<String, Any?>? {
        val sql = """
            SELECT u.user_id, u.user_nm, u.email, u.user_state_cd
            FROM ax.tb_sys_user u
            WHERE u.user_id      = :empNo
              AND lower(u.email) = lower(:email)
        """.trimIndent()

        val params = MapSqlParameterSource().addValue("empNo", empNo).addValue("email", email)

        return jdbcTemplate.query(sql, params) { rs, _ ->
            mapOf(
                "empNo" to rs.getString("user_id"),
                "name" to rs.getString("user_nm"),
                "email" to rs.getString("email"),
                "stateCd" to rs.getString("user_state_cd")
            )
        }.firstOrNull()
    }

    /**
     * 인증 요청의 만료 시각을 표시용 문자열로 조회한다. (응답 안내)
     */
    fun findExpiresAtText(verifyId: Long): String? {
        val sql = """
            SELECT to_char(expires_at AT TIME ZONE 'Asia/Seoul', 'YYYY-MM-DD HH24:MI:SS') AS expires_at_text
            FROM ax.tb_sys_email_verify
            WHERE verify_id = :verifyId
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource("verifyId", verifyId)) { rs, _ ->
            rs.getString("expires_at_text")
        }.firstOrNull()
    }

    /** 이메일 중복 여부 확인 (회원가입) */
    fun existsEmail(email: String): Boolean {
        val sql = "SELECT count(*) FROM ax.tb_sys_user WHERE lower(email) = lower(:email)"
        return (jdbcTemplate.queryForObject(sql, MapSqlParameterSource("email", email), Long::class.java) ?: 0L) > 0
    }

    /** 회원가입 시 계정 이메일을 함께 저장한다. */
    fun updateUserEmail(userId: String, email: String): Int {
        val sql = """
            UPDATE ax.tb_sys_user
               SET email    = :email,
                   upd_date = now()
             WHERE user_id  = :userId
        """.trimIndent()

        val params = MapSqlParameterSource().addValue("userId", userId).addValue("email", email)
        return jdbcTemplate.update(sql, params)
    }
}
