package com.dwje.api.controller

import com.dwje.api.common.response.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 서비스 헬스체크 컨트롤러
 *
 * 화이트리스트 경로로 인증 없이 호출 가능하며, 로드밸런서/모니터링 용도로 사용한다.
 */
@RestController
@RequestMapping("/api/v1/health")
@Tag(name = "00. 공통")
class HealthController(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) {

    /**
     * 애플리케이션 및 DB 연결 상태를 반환한다.
     *
     * @return 서비스 상태와 DB 접속 가능 여부
     */
    @Operation(summary = "헬스체크", description = "애플리케이션 및 DB 연결 상태를 확인한다.")
    @GetMapping
    fun health(): ApiResponse<Map<String, Any?>> {
        // DB 커넥션 확보 가능 여부만 가볍게 확인한다.
        val dbAlive = runCatching {
            jdbcTemplate.queryForObject("SELECT 1", MapSqlParameterSource(), Int::class.java) == 1
        }.getOrDefault(false)

        return ApiResponse.ok(
            mapOf(
                "status" to if (dbAlive) "UP" else "DEGRADED",
                "database" to if (dbAlive) "UP" else "DOWN",
                "application" to "dwje-api"
            ),
            // 문구를 상태에 맞춘다. 예전에는 고정 문자열이라 DB 가 죽어도
            // status=DEGRADED · database=DOWN 옆에 "정상 동작 중입니다" 가 나갔다.
            // 필드는 정확했지만 사람이 응답을 눈으로 훑으면 정상으로 읽힌다.
            //
            // 상태 코드는 200 을 유지한다. 이 엔드포인트는 "프로세스가 살아 있는가" 와
            // "DB 까지 정상인가" 를 한 응답에 담고 있어 코드 하나로 표현할 수 없고,
            // 503 으로 바꾸면 헬스체크를 보는 오케스트레이터가 컨테이너를 죽일 수 있다.
            // 호출부는 상태 코드가 아니라 database 필드를 봐야 한다.
            if (dbAlive) {
                "서비스가 정상 동작 중입니다."
            } else {
                "데이터베이스에 연결할 수 없습니다. 서비스가 정상 동작하지 않습니다."
            }
        )
    }
}
