package com.dwje.api

import com.dwje.api.config.EnvFileLoader
import com.dwje.api.config.PlaceholderValuesRemainException
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * IntelliJ 실행 구성이 기동할 수 있게 하는 env 파일 로더의 규칙을 고정한다.
 *
 * 이 로더가 없으면 비밀값이 `config/api.env*` 에만 있을 때 IntelliJ 실행 구성은
 * `Could not resolve placeholder` 로 죽는다. 그리고 `.run` 실행 구성에 값을 직접
 * 쓰면 저장소에 비밀값이 커밋된다. 두 위험 사이의 균형이 여기서 정해진다.
 */
class EnvFileLoaderTest {

    @TempDir
    lateinit var home: File

    /** 로더는 시스템 프로퍼티를 쓰므로 테스트가 서로 영향을 주지 않게 되돌린다. */
    private fun withCleanProperties(vararg names: String, block: () -> Unit) {
        val saved = names.associateWith { System.getProperty(it) }
        try {
            block()
        } finally {
            saved.forEach { (key, value) ->
                if (value == null) System.clearProperty(key) else System.setProperty(key, value)
            }
        }
    }

    private fun writeEnvFile(profile: String, content: String): File {
        val dir = File(home, "config").apply { mkdirs() }
        return File(dir, "api.env.$profile").apply { writeText(content) }
    }

    @Test
    @DisplayName("프로파일 환경 파일의 값을 시스템 프로퍼티로 채운다")
    fun loadsValuesFromProfileFile() {
        val keys = listOf("ENVLOADER_T1", "ENVLOADER_T2")
        writeEnvFile("local", "ENVLOADER_T1=첫값\nENVLOADER_T2=둘째값\n")

        withCleanProperties(*keys.toTypedArray()) {
            val loaded = EnvFileLoader.load("local", home)

            assertEquals(listOf("ENVLOADER_T1", "ENVLOADER_T2"), loaded?.keys)
            assertEquals("첫값", System.getProperty("ENVLOADER_T1"))
            assertEquals("둘째값", System.getProperty("ENVLOADER_T2"))
        }
    }

    @Test
    @DisplayName("주석·빈 줄·export 접두어·공백이 섞여 있어도 값이 잘리지 않는다")
    fun toleratesShellNoise() {
        val key = "ENVLOADER_T3"
        writeEnvFile(
            "local",
            """
            # 주석 줄
               # 들여쓴 주석

            export $key = 값 with space
            """.trimIndent()
        )

        withCleanProperties(key) {
            EnvFileLoader.load("local", home)

            // 셸 확장을 하지 않으므로 공백은 그대로 남는다.
            assertEquals("값 with space", System.getProperty(key))
        }
    }

    @Test
    @DisplayName("값을 감싼 따옴표 한 겹은 벗긴다")
    fun stripsSurroundingQuotes() {
        val urlKey = "ENVLOADER_T8"
        val passKey = "ENVLOADER_T9"
        // config/api.env.local 이 실제로 따옴표로 감싸 적는 형태를 그대로 옮겼다.
        writeEnvFile(
            "local",
            """
            $urlKey="jdbc:sqlserver://192.168.7.203:1433;databaseName=EDGE;sendTimeParametersAsUnicode=false"
            $passKey='공백이 있고 #/hash 가 있는 비밀번호'
            """.trimIndent()
        )

        withCleanProperties(urlKey, passKey) {
            EnvFileLoader.load("local", home)

            assertEquals(
                "jdbc:sqlserver://192.168.7.203:1433;databaseName=EDGE;sendTimeParametersAsUnicode=false",
                System.getProperty(urlKey),
                "따옴표가 값에 남으면 JDBC URL 이 깨져 어떤 드라이버도 받지 못하고 " +
                    "기동이 'No suitable driver' 로 죽는다. 실제로 그렇게 죽었다."
            )
            assertEquals(
                "공백이 있고 #/hash 가 있는 비밀번호",
                System.getProperty(passKey),
                "따옴표 안의 공백과 # 은 값으로 보존되어야 합니다."
            )
        }
    }

    @Test
    @DisplayName("따옴표가 짝이 안 맞으면 값을 망가뜨리지 않는다")
    fun unbalancedQuoteIsKeptAsIs() {
        val key = "ENVLOADER_T10"
        writeEnvFile("local", """$key="값만 앞따옴표가 있는 경우""")

        withCleanProperties(key) {
            EnvFileLoader.load("local", home)
            assertEquals("\"값만 앞따옴표가 있는 경우", System.getProperty(key))
        }
    }

    @Test
    @DisplayName("비어 있는 실행 구성 값은 환경 파일이 채운다")
    fun blankExistingValueIsTreatedAsUnset() {
        val key = "ENVLOADER_T4"
        writeEnvFile("local", "$key=파일값\n")

        withCleanProperties(key) {
            // IntelliJ 실행 구성의 value="" 를 흉내낸다. 실제 프로세스에서는 환경변수로 오지만,
            // 시스템 프로퍼티가 빈 문자열인 상태에서 로더가 이 값을 밀어내야 한다.
            System.setProperty(key, "")

            EnvFileLoader.load("local", home)

            assertEquals(
                "파일값",
                System.getProperty(key),
                "실행 구성에 빈 값만 남아 있으면 환경 파일의 값이 적용되어야 합니다. " +
                    "그렇지 않으면 .run/*.run.xml 의 빈 PROD_*/DEV_* 가 비밀값을 계속 막습니다."
            )
        }
    }

    @Test
    @DisplayName("이미 비어 있지 않은 값은 환경 파일이 덮지 않는다")
    fun existingValueWins() {
        val key = "ENVLOADER_T5"
        writeEnvFile("local", "$key=파일값\n")

        withCleanProperties(key) {
            System.setProperty(key, "IDE에서직접넣은값")

            EnvFileLoader.load("local", home)

            assertEquals(
                "IDE에서직접넣은값",
                System.getProperty(key),
                "실행 구성에서 임시로 덮어쓴 값이 환경 파일에 밀려선 안 됩니다."
            )
        }
    }

    @Test
    @DisplayName("프로파일 파일이 없으면 config/api.env 로 떨어진다")
    fun fallsBackToDefaultEnvFile() {
        val key = "ENVLOADER_T6"
        File(home, "config").mkdirs()
        File(home, "config/api.env").writeText("$key=운영기본값\n")

        withCleanProperties(key) {
            val loaded = EnvFileLoader.load("prod", home)

            assertEquals("api.env", loaded?.file?.name)
            assertEquals("운영기본값", System.getProperty(key))
        }
    }

    @Test
    @DisplayName("환경 파일이 하나도 없으면 조용히 넘어간다")
    fun missingFileIsNotAnError() {
        withCleanProperties("ENVLOADER_T7") {
            // local 프로파일은 LOCAL_DB_PASSWORD 기본값이 있어 env 파일 없이도 돈다.
            // 여기서 예외를 던지면 그 기본값 경로가 통째로 막힌다.
            assertNull(EnvFileLoader.load("local", home))
        }
    }

    @Test
    @DisplayName("local/dev 가 운영 전용 환경 파일로 떨어지면 경고한다")
    fun warnsWhenNonProdProfileFallsBackToProductionEnvFile() {
        val key = "SPRING_DATASOURCE_URL_FOR_TEST"
        File(home, "config").mkdirs()
        File(home, "config/api.env").writeText("$key=jdbc:postgresql://운영서버:5432/dwjedb\n")

        withCleanProperties(key) {
            val err = System.err
            val captured = java.io.ByteArrayOutputStream()
            System.setErr(java.io.PrintStream(captured, true, Charsets.UTF_8))
            try {
                EnvFileLoader.load("local", home)
            } finally {
                System.setErr(err)
            }

            val text = captured.toString(Charsets.UTF_8)
            assertTrue(
                text.contains("SPRING_DATASOURCE_URL"),
                "local 기동이 config/api.env 로 떨어졌는데 경고하지 않았다. " +
                    "이 파일의 SPRING_DATASOURCE_URL 은 프로파일 yml 을 덮어써서 " +
                    "조용히 운영 DB 로 접속한다. 실제 경고문: $text"
            )
        }
    }

    @Test
    @DisplayName("prod 는 자신의 환경 파일을 읽을 때 경고하지 않는다")
    fun prodDoesNotWarnAboutItsOwnEnvFile() {
        val key = "ENVLOADER_T11"
        File(home, "config").mkdirs()
        File(home, "config/api.env").writeText("$key=운영값\n")

        withCleanProperties(key) {
            val err = System.err
            val captured = java.io.ByteArrayOutputStream()
            System.setErr(java.io.PrintStream(captured, true, Charsets.UTF_8))
            try {
                EnvFileLoader.load("prod", home)
            } finally {
                System.setErr(err)
            }

            assertEquals(
                "",
                captured.toString(Charsets.UTF_8),
                "prod 가 운영 환경 파일을 읽는 것은 정상이다. 경고가 나오면 매 기동마다 시끄럽다."
            )
            assertEquals("운영값", System.getProperty(key))
        }
    }

    @Test
    @DisplayName("예시 템플릿 값이 남아 있으면 기동을 멈춘다")
    fun throwsWhenPlaceholderValuesRemain() {
        // config/api.env.dev.example 를 복사만 하고 안 채운 상태를 그대로 옮겼다.
        writeEnvFile(
            "dev",
            """
            DEV_DB_PASSWORD=your_dev_db_password_here
            DEV_JWT_SECRET=replace_with_at_least_32_random_bytes
            DEV_MAIL_HOST=smtp.office365.com
            """.trimIndent()
        )

        val e = assertFailsWith<PlaceholderValuesRemainException> {
            EnvFileLoader.load("dev", home)
        }
        assertEquals(listOf("DEV_DB_PASSWORD", "DEV_JWT_SECRET"), e.keys)
        assertTrue(
            e.message!!.contains("DEV_DB_PASSWORD"),
            "무엇을 채워야 하는지 메시지에 이름이 있어야 합니다. 실제: ${e.message}"
        )
    }

    @Test
    @DisplayName("꺾쇠 괄호로 감싼 예시 값도 걸러낸다")
    fun throwsOnAngleBracketPlaceholder() {
        writeEnvFile("local", "AX_MSSQL_URL=jdbc:sqlserver://<MSSQL_HOST>:1433;databaseName=EDGE\n")

        val e = assertFailsWith<PlaceholderValuesRemainException> {
            EnvFileLoader.load("local", home)
        }
        assertEquals(listOf("AX_MSSQL_URL"), e.keys)
    }

    @Test
    @DisplayName("실제 값 형태는 자리표시자로 오인하지 않는다")
    fun doesNotFlagRealLookingValues() {
        // 이 검사가 넓어지면 문제가 아니라 사고다 — 운영 비밀번호 기동이 막힌다.
        // 그래서 실제로 쓰일 만한 값들을 넣고 **통과**를 고정한다.
        val realistic = listOf(
            "ENVLOADER_R1" to "아무거나긴한비밀번호123!@#",
            "ENVLOADER_R2" to "DwjeLx9-Prod_jwt#Key/2026",
            "ENVLOADER_R3" to "jdbc:postgresql://prod-db.dwje.internal:5432/dwjedb",
            "ENVLOADER_R4" to "http://192.168.2.8:11436",
            "ENVLOADER_R5" to "0aXk9==",
            "ENVLOADER_R6" to "smtp.office365.com"
        )
        writeEnvFile("prod", realistic.joinToString("\n") { "${it.first}=${it.second}" })

        val keys = realistic.map { it.first }
        try {
            EnvFileLoader.load("prod", home)

            realistic.forEach { (key, expected) ->
                assertEquals(expected, System.getProperty(key), "$key 가 통과해야 합니다.")
            }
        } finally {
            keys.forEach { System.clearProperty(it) }
        }
    }

    @Test
    @DisplayName("값이 your_ 로 시작하면 예시 문구로 본다")
    fun treatsYourPrefixAsPlaceholder() {
        // 실제 암호가 your_ 로 시작할 가능성은 낮고, 예시 템플릿은 대부분 그렇다.
        // 이 테스트는 그 절충점을 고정한다. 진짜 그런 암호를 쓰게 되면
        // 이 규칙을 좁히면 된다 — 어느 쪽인지 모르면 여기서 갈린다.
        writeEnvFile("local", "ENVLOADER_R7=your-db-password\n")

        val e = assertFailsWith<PlaceholderValuesRemainException> {
            EnvFileLoader.load("local", home)
        }
        assertEquals(listOf("ENVLOADER_R7"), e.keys)
    }

    @Test
    @DisplayName("저장소에 커밋되는 실행 구성에 비밀값이 들어가 있지 않다")
    fun sharedRunConfigurationsCarryNoSecretValues() {
        val runDir = File(projectRoot(), ".run")
        assertTrue(runDir.isDirectory, "실행 구성 디렉터리가 없습니다. [${runDir.path}]")

        val configs = runDir.listFiles { f: File -> f.name.endsWith(".run.xml") }.orEmpty()
        assertTrue(configs.isNotEmpty(), ".run 에 실행 구성이 하나도 없습니다.")

        // 비밀으로 볼 이름만 검사한다. LLM 주소(DWJE_LLM_BASE_URL 같은 192.168.x.x)는
        // 사내 구성값이지 비밀값이므로 실행 구성에 그대로 적어도 된다 — 적어야 IDE 에서
        // 프로파일만 골라 고를 수 있다. PASSWORD/SECRET/TOKEN/KEY 계열만 막는다.
        val secretNamePattern = Regex("PASSWORD|SECRET|TOKEN|API_?KEY", RegexOption.IGNORE_CASE)
        val valuePattern = Regex("""value="([^"]*)"""")

        val suspicious = configs.mapNotNull { file ->
            file.readLines()
                .filter { it.contains("<env name=") }
                .mapNotNull { line ->
                    val name = Regex("""name="([^"]+)"""").find(line)?.groupValues?.get(1)
                    val value = valuePattern.find(line)?.groupValues?.get(1)
                    if (name != null &&
                        value != null &&
                        value.isNotBlank() &&
                        secretNamePattern.containsMatchIn(name)
                    ) {
                        "$name=<값이 채워져 있음>"
                    } else {
                        null
                    }
                }
                .takeIf { it.isNotEmpty() }
                ?.let { "${file.name}: ${it.joinToString(", ")}" }
        }

        assertTrue(
            suspicious.isEmpty(),
            "저장소에 추적되는 실행 구성에 비밀값이 채워져 있다 — 커밋하면 회수해야 한다.\n" +
                suspicious.joinToString("\n") +
                "\n값은 비워 두고 config/api.env<프로파일> 또는 IDE 의 Environment 칸에 넣으십시오."
        )
    }

    private fun projectRoot(): File {
        var dir = File("").absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        return File("").absoluteFile
    }
}
