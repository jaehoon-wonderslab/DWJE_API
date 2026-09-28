package com.dwje.api.config

import java.io.File

/**
 * `config/api.env[.<프로파일>]` 환경 파일을 읽어 **비어 있는** 설정을 시스템 프로퍼티로 채운다.
 *
 * ## 왜 필요한가
 *
 * 비밀값은 `config/api.env` 계열 파일에만 두고 `.run` 실행 구성(git 추적 대상)에는 env **이름만**
 * 선언한다. 그런데 IntelliJ 의 Spring Boot 실행 구성은 `.env` / `api.env` 를 읽지 않는다.
 * `start.sh` 만 `source` 하므로, 그 외 모든 경로(intelliJ 실행, `bootRun`, `java -jar`)는
 * `PROD_DB_PASSWORD` 같은 변수를 하나도 못 받아 `${...}` 자리표시자가 그대로 남아
 * `Could not resolve placeholder` 로 기동이 실패했다.
 *
 * 여기서 파일을 직접 읽으면 실행 경로가 무엇이든 같은 설정이 적용된다.
 *
 * ## 우선순위
 *
 * 1. **비어 있지 않은** 값(실제 환경변수 · 실행 구성의 env · `-D` 시스템 프로퍼티)이 이긴다.
 *    IDE 실행 구성에서 임시로 값을 덮어쓰고 싶을 때 파일 값이 이겨버리면 안 된다.
 * 2. 비어 있거나 없는 값은 아래 환경 파일로 채운다.
 * 3. `config/api.env.<프로파일>` → `config/api.env` → `.env` 순으로 첫 번째 존재하는 파일 하나만 읽는다.
 *    (`start.sh` 의 규칙과 같다 — 운영값과 로컬값을 한 파일에 섞지 않기 위해)
 *
 * 2번 규칙이 필요한 이유: IntelliJ 실행 구성에 `value=""` 로 남은 env 는 **존재하는** 환경변수다.
 * 그냥 "있으면 건너뛰면" `.run` 실행 구성의 빈 `PROD_DB_PASSWORD` 하나가
 * `config/api.env.prod` 의 실제 비밀값을 막아버린다. 빈 값은 "의사가 아니라 미설정"이므로 채운다.
 *
 * (시작 스크립트와 다른 점: `start.sh` 는 `source` 라 파일 값이 항상 이긴다.
 *  여기서는 IDE 실행 구성의 명시적 덮어쓰기를 존중한다. 어느 쪽이 이기는지는
 *  `EnvFileLoaderTest` 가 고정한다.)
 *
 * ## 파싱 규칙
 *
 * `KEY=VALUE` 형태만 다룬다. `export KEY=VALUE` 도 허용한다.
 * `#` 으로 시작하는 줄과 빈 줄은 건너뛴다.
 *
 * 값을 감싼 **한 겹의 같은 따옴표**(`"..."` 또는 `'...'`)는 벗긴다.
 * `config/api.env.local` 이 `AX_MSSQL_URL="jdbc:sqlserver://...;sendTimeParametersAsUnicode=false"`
 * 처럼 값을 따옴표로 감싸 적고 있어서 이것을 안 하면 따옴표가 값에 남는다.
 * 그러면 JDBC URL 이 앞뒤에 따옴표가 붙은 채 되어 어떤 드라이버도
 * 받아들이지 못하고 기동이 `No suitable driver` 로 죽는다. 실제로 그렇게 죽었다.
 *
 * 그 밖의 셸 확장은 하지 않는다 — 주석 제거·변수 치환·따옴표 안의 `#` 해석 등.
 * 그래서 비밀번호에 공백이나 `#` 이 들어가도 잘리지 않는다.
 */
object EnvFileLoader {

    /**
     * 예시 파일에 남아 있는 **값의 앞부분**이 이 목록에 걸리면 미입력으로 본다.
     * 템플릿이 실제로 쓰는 표현만 넣는다 — 실제 비밀번호와 겹치면 기동을 막아 버린다.
     */
    private val PLACEHOLDER_PREFIXES = listOf(
        "your_",
        "your-",
        "replace_",
        "replace-",
        "changeme",
        "change_me",
        "todo",
        "fill_",
        "example"
    )

    /**
     * 예시 파일이 쓰는 **값 안의 표현**(`<MSSQL_HOST>` 같은 꺾쇠 괄호).
     * 실제 값에 꺾쇠 괄호가 들어갈 일은 없으므로 안전하다.
     */
    private val PLACEHOLDER_SUBSTRINGS = listOf("<", ">")

    /** 로드 결과를 사람이 읽을 수 있는 한 줄로. 로그의 "기동" 단계에서 쓴다. */
    data class Loaded(val file: File, val keys: List<String>, val placeholders: List<String>)

    /**
     * [profile] 에 해당하는 환경 파일을 찾아 시스템 프로퍼티로 반영한다.
     * 파일이 없으면 null 을 반환한다(기동은 계속된다 — local 프로파일은 env 파일이 없어도 돈다).
     *
     * @throws PlaceholderValuesRemainException 자리표시자 값을 넘어 트면 기동을 멈춘다.
     */
    @Throws(PlaceholderValuesRemainException::class)
    fun load(profile: String, appHome: File = File(System.getProperty("user.dir"))): Loaded? {
        val file = resolveFile(profile, appHome) ?: return null
        warnOnProductionFallback(profile, file)

        val applied = mutableListOf<String>()
        val placeholders = mutableListOf<String>()
        file.forEachLine { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachLine

            val withoutExport = line.removePrefix("export ").trimStart()
            val separator = withoutExport.indexOf('=')
            if (separator <= 0) return@forEachLine

            val key = withoutExport.substring(0, separator).trim()
            // `KEY =VALUE` 처럼 공백이 섞여 들어온 오타도 한 번 더 정리한다.
            if (key.isEmpty() || !key.all { it.isLetterOrDigit() || it == '_' }) return@forEachLine

            val value = unquote(withoutExport.substring(separator + 1).trim())

            // 1순위: 이미 **비어 있지 않은** 값이 있으면 파일로 덮지 않는다.
            // 빈 문자열은 미설정으로 본다 — IntelliJ 실행 구성의 value="" 가
            // 환경 파일의 실제 값을 막지 않게 하기 위해서다.
            if (!System.getProperty(key).isNullOrBlank()) return@forEachLine
            if (!System.getenv(key).isNullOrBlank()) return@forEachLine

            System.setProperty(key, value)
            applied += key
            if (isPlaceholder(value)) placeholders += key
        }

        if (placeholders.isNotEmpty()) throw PlaceholderValuesRemainException(file, placeholders)

        return Loaded(file, applied, emptyList())
    }

    /**
     * 예시 파일의 값이 그대로 남아 있는지 본다.
     *
     * `start.sh` 의 필수 변수 검사는 **비어 있음만** 본다. 그래서 템플릿을 복사만 하고
     * 값을 채우지 않으면 `DEV_DB_PASSWORD=your_dev_db_password_here` 가 그대로 있어도
     * 통과해 버리고, 기동은 되다가 DB 인증에서 죽는다. "값을 안 넣었다"는 사실이
     * "비밀번호가 틀렸다"로 보이게 되어 원인을 찾기 어렵다.
     *
     * 그래서 여기서 걸러 기동 단계에서 멈춘다 — 같은 취지로
     * `DwjeApiApplication` 이 프로파일 미지정을 애초에 거부한다.
     */
    private fun isPlaceholder(value: String): Boolean {
        val v = value.lowercase()
        return PLACEHOLDER_PREFIXES.any { v.startsWith(it) } ||
            PLACEHOLDER_SUBSTRINGS.any { it in v }
    }

    /**
     * 값을 감싼 같은 따옴표 한 겹만 벗는다. `""` 처럼 안쪽이 빈 값도 `""` 가 남지 않게 한다.
     * 감기지 않은 따옴표(앞만 있는 등)는 값을 망가뜨리지 않도록 그대로 둔다.
     */
    private fun unquote(value: String): String {
        if (value.length < 2) return value
        val quote = value.first()
        if (quote != '"' && quote != '\'') return value
        if (value.last() != quote) return value
        return value.substring(1, value.length - 1)
    }

    /**
     * local/dev 로 기동하는데 **운영 전용 파일** `config/api.env` 로 떨어졌으면 경고한다.
     *
     * 이 파일에는 `SPRING_DATASOURCE_URL` 이 들어 있다. Spring 의 느슨한 바인딩은 이를
     * `spring.datasource.url` 로 붙이므로, 프로파일 yml 의 DB 주소보다 **우선한다.**
     * 즉 조용히 로컬/개발 기동이 운영 DB 로 접속하게 된다 — `config/api.env.local.example`
     * 머리말이 경고하는 바로 그 사고다. `start.sh` 는 이 경우를 경고하지만
     * 기동은 계속하므로, 여기서도 같은 경고를 낸다.
     */
    private fun warnOnProductionFallback(profile: String, file: File) {
        val isProductionFile = file.name == "api.env"
        if (profile == "prod" || !isProductionFile) return

        System.err.println(
            """
            [주의] [${profile}] 프로파일인데 운영 전용 환경 파일 ${file.path} 을 읽었습니다.
                   이 파일의 SPRING_DATASOURCE_URL/_USERNAME 이 프로파일 yml 보다 우선합니다.
                   개발자 PC 라면 아래를 먼저 확인하십시오.
                     cp config/api.env.${'$'}{profile}.example config/api.env.${'$'}{profile}
            """.trimIndent()
        )
    }

    /**
     * `config/api.env.<프로파일>` 을 먼저 보고, 없으면 `config/api.env`, 없으면 `.env`.
     * `start.sh` 와 같은 순서여야 IDE 와 스크립트에서 서로 다른 값이 나오지 않는다.
     */
    private fun resolveFile(profile: String, appHome: File): File? {
        val candidates = listOf(
            File(appHome, "config/api.env.$profile"),
            File(appHome, "config/api.env"),
            File(appHome, ".env")
        )
        return candidates.firstOrNull { it.isFile }
    }
}

/**
 * 환경 파일에 예시 템플릿의 값이 그대로 남아 있을 때 던진다.
 *
 * 예외로 만든 이유는 이 메시지를 **스택트레이스 없이** 사람에게 보여 주기 위해서다.
 * `BeanCreationException` 처럼 래핑되어 나오면 "무엇을 채워야 하는지"가 묻힌다.
 */
class PlaceholderValuesRemainException(
    val file: File,
    val keys: List<String>
) : RuntimeException(
    """
    기동을 멈춥니다 — 환경 파일에 예시 값이 그대로 남아 있습니다.

      파일 : ${file.path}
      항목 : ${keys.joinToString(", ")}

    이 값들은 템플릿의 안내 문구입니다. 그대로 두면 기동은 되다가
    DB 비밀번호 오류·SMTP 인증 실패처럼 "값이 틀렸다"는 사실과 다른 증상으로 죽어서
    원인을 찾기 어렵습니다.

    해결 : 해당 값을 실제 값으로 바꾸십시오.
           ((${keys.joinToString(", ")}) 항목)
    """.trimIndent()
)
