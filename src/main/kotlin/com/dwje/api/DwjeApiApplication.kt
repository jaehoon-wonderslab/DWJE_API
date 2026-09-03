package com.dwje.api

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * 덕우전자 AX 시스템 API 애플리케이션 진입점
 *
 * - 언어/런타임 : Kotlin 2.x / JDK 21 / Spring Boot 3.x
 * - DB 접근방식 : Native SQL Direct Binding (NamedParameterJdbcTemplate) — iBatis/MyBatis 미사용
 * - 프로파일    : local / dev / prod — **기본값 없음. 반드시 지정해야 한다**
 */
@SpringBootApplication
class DwjeApiApplication

/**
 * 지정할 수 있는 프로파일
 *
 * `src/main/resources/application-*.yml` 과 **양방향으로** 일치해야 한다.
 * 목록에만 있으면 설정 파일이 없는 프로파일을 통과시키고,
 * 파일만 있으면 정상 프로파일을 "알 수 없는 프로파일" 로 거절한다.
 * `EnvVarDocumentedTest` 가 그 일치를 검사한다.
 */
internal val KNOWN_PROFILES = setOf("local", "dev", "prod")

/**
 * 활성 프로파일이 지정되었는지 확인한다.
 *
 * 기본 프로파일(`spring.profiles.active: local`)을 없앴다. 그 값이 있으면 운영 서버에서
 * 옵션을 빼고 띄웠을 때 로컬 설정(테스트용 고정 인증코드·발송 상한 해제·소스에 박힌 JWT 키)으로
 * 돌기 때문이다.
 *
 * 대신 안 주면 죽는데, **죽는 사유를 알 수 없는 것이 문제였다.**
 * 프로파일이 없으면 datasource 설정 자체가 없어 Spring 이 실패하지만, 로그 설정의 appender 도
 * 프로파일 단위라 그 메시지까지 함께 버려졌다(exit=1, 화면에는 배너까지만).
 * 그래서 Spring 을 띄우기 전에 여기서 먼저 확인해 표준 오류로 안내한다.
 */
private fun activeProfileOrNull(args: Array<String>): String? =
    args.firstOrNull { it.startsWith("--spring.profiles.active=") }
        ?.substringAfter('=')
        ?: System.getProperty("spring.profiles.active")
        ?: System.getenv("SPRING_PROFILES_ACTIVE")

fun main(args: Array<String>) {
    val profile = activeProfileOrNull(args)?.trim()?.takeIf { it.isNotBlank() }

    if (profile == null) {
        System.err.println(
            """
            기동할 수 없습니다 — 활성 프로파일을 지정해야 합니다.

              로컬  ./gradlew bootRun --args='--spring.profiles.active=local'
              배포  java -jar -Dspring.profiles.active=prod build/libs/dwje-api-0.0.1.jar
                    (SPRING_PROFILES_ACTIVE 환경변수도 됩니다)

            지정할 수 있는 값 : ${KNOWN_PROFILES.joinToString(" / ")}
            기본값을 두지 않는 이유는, 옵션을 빼고 띄웠을 때 운영 서버가 로컬 설정으로
            도는 것을 막기 위함입니다.
            """.trimIndent()
        )
        kotlin.system.exitProcess(1)
    }

    if (profile !in KNOWN_PROFILES) {
        System.err.println(
            "알 수 없는 프로파일입니다. [$profile] " +
                "지정할 수 있는 값 : ${KNOWN_PROFILES.joinToString(" / ")}"
        )
        kotlin.system.exitProcess(1)
    }

    runApplication<DwjeApiApplication>(*args)
}
