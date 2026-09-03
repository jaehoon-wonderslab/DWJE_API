package com.dwje.api

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

/**
 * MES 스키마 읽기 전용 규약 테스트
 *
 * ## 지켜야 하는 것
 * `mes` 스키마는 MES 원본 시스템에서 이관된 데이터다.
 * **구조 변경과 데이터 변경/삭제/갱신을 절대 하지 않는다. 조회만 한다.**
 *
 * 실수로 쓰기 구문이 들어가면 원본 실적이 훼손되고 되돌릴 수 없다.
 * 리뷰에 의존하지 않고 빌드에서 막는다.
 *
 * ## 검사 범위
 * - `src/main/kotlin` 하위 : Repository 의 SQL 문자열
 * - `src/main/resources/db` 하위 : 마이그레이션·시드 SQL
 */
class MesReadOnlyContractTest {

    companion object {
        /** MES 스키마에 절대 쓰면 안 되는 구문 */
        private val FORBIDDEN_STATEMENTS = listOf(
            "INSERT INTO", "UPDATE", "DELETE FROM", "TRUNCATE",
            "ALTER TABLE", "DROP TABLE", "CREATE TABLE", "CREATE INDEX",
            "ALTER SCHEMA", "DROP SCHEMA", "GRANT", "REVOKE"
        )

        /** 대상 스키마 참조 패턴 — mes.테이블 */
        private val MES_REFERENCE = Regex("""\bmes\s*\.\s*[a-zA-Z_][a-zA-Z0-9_]*""", RegexOption.IGNORE_CASE)
    }

    @Test
    @DisplayName("Kotlin 소스에 mes 스키마를 변경하는 SQL 이 없어야 한다")
    fun noMesWritesInKotlinSources() {
        val violations = scan(File(projectDir(), "src/main/kotlin"), listOf("kt"), minFiles = 50)

        check(violations.isEmpty()) { report("Kotlin 소스", violations) }
    }

    @Test
    @DisplayName("마이그레이션·시드 SQL 이 mes 스키마를 변경하지 않아야 한다")
    fun noMesWritesInSqlScripts() {
        val violations = scan(File(projectDir(), "src/main/resources/db"), listOf("sql"), minFiles = 10)

        check(violations.isEmpty()) { report("SQL 스크립트", violations) }
    }

    @Test
    @DisplayName("검사기 자체가 동작하는지 확인 — 위반 문자열을 실제로 잡아내야 한다")
    fun detectorActuallyDetects() {
        val sample = """
            val sql = ""${'"'}
                UPDATE mes.tb_pop_label_hist SET normal = 0 WHERE lot_no = :lotNo
            ""${'"'}.trimIndent()
        """.trimIndent()

        val found = findViolations("Sample.kt", sample)
        check(found.isNotEmpty()) { "검사기가 명백한 위반(UPDATE mes.…)을 놓쳤다 — 규약 테스트가 무력하다" }

        // 정상적인 조회는 잡히면 안 된다.
        val readOnly = "SELECT lh.normal FROM mes.tb_pop_label_hist lh WHERE lh.plant_cd = :plantCd"
        check(findViolations("Sample.kt", readOnly).isEmpty()) { "정상 SELECT 를 위반으로 오탐했다" }
    }

    /**
     * 디렉터리를 재귀 순회하며 위반을 수집한다.
     *
     * 예전에는 `if (!root.exists()) return emptyList()` 였다. 그러면 경로가 바뀌었을 때
     * **한 파일도 안 보고 "위반 없음" 으로 통과한다.** 아래 검사기 자체 시험은 통과하므로
     * (그건 표본 문자열로 도는 것이다) 규약이 안 지켜지는 것을 아무도 모른다.
     * 그래서 대상이 없으면 실패로 본다.
     *
     * @param minFiles 최소 방문 파일 수. 이보다 적으면 순회가 무력해진 것으로 본다
     */
    private fun scan(root: File, extensions: List<String>, minFiles: Int): List<String> {
        check(root.exists()) {
            "검사 대상 경로가 없습니다. [${root.path}] " +
                "경로가 바뀌었다면 이 규약 테스트도 함께 고쳐야 합니다."
        }

        val files = root.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in extensions }
            .toList()

        check(files.size >= minFiles) {
            "${root.name} 에서 ${extensions.joinToString("/")} 파일을 ${files.size}개만 찾았습니다" +
                "(최소 $minFiles 개 기대). 순회가 무력해졌는지 확인하세요."
        }

        return files.flatMap { file -> findViolations(file.name, file.readText()) }
    }

    /**
     * 텍스트에서 "쓰기 구문 + mes 참조" 조합을 찾는다.
     *
     * 구문 단위로 잘라, 쓰기 동사로 시작하는 조각이 mes 를 참조할 때만 위반으로 본다.
     * (`SELECT … FROM mes.…` 옆에 다른 테이블 UPDATE 가 있는 경우를 오탐하지 않기 위함)
     */
    private fun findViolations(fileName: String, content: String): List<String> {
        val violations = mutableListOf<String>()
        // 주석 제거 — 문서에 적힌 예시 문구를 위반으로 세지 않는다.
        // 작은따옴표 문자열도 비운다. 코드 설명이나 시드 값에 적힌 'mes.xxx' 는
        // 테이블 참조가 아니라 데이터다. 실제 참조(mes.tb_x)는 따옴표 밖에 있으므로
        // 비워도 탐지력은 그대로다.
        val stripped = content
            .replace(Regex("""(?m)^\s*(--|\*|//).*$"""), "")
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("'(?:[^']|'')*'"), "''")

        FORBIDDEN_STATEMENTS.forEach { verb ->
            val pattern = "\\b" + verb.replace(" ", "\\s+") + "\\b"
            Regex(pattern, RegexOption.IGNORE_CASE)
                .findAll(stripped)
                .forEach { match ->
                    // 해당 구문이 끝나기 전(다음 세미콜론까지)에 mes 참조가 있는지 본다.
                    val end = stripped.indexOf(';', match.range.first).let { if (it < 0) stripped.length else it }
                    val statement = stripped.substring(match.range.first, minOf(end, match.range.first + 2000))

                    if (MES_REFERENCE.containsMatchIn(statement)) {
                        val line = stripped.substring(0, match.range.first).count { it == '\n' } + 1
                        val snippet = statement.lines().first().trim().take(90)
                        violations += "$fileName:$line  $verb … mes.*  →  $snippet"
                    }
                }
        }
        return violations
    }

    private fun report(scope: String, violations: List<String>): String = buildString {
        appendLine("$scope 에서 mes 스키마를 변경하는 구문이 발견되었다.")
        appendLine("mes 는 MES 원본 이관 데이터로 조회만 허용된다. 해당 구문을 제거하라.")
        appendLine()
        violations.forEach { appendLine("  - $it") }
    }

    /** 테스트 실행 위치와 무관하게 프로젝트 루트를 찾는다. */
    private fun projectDir(): File {
        var dir = File(System.getProperty("user.dir"))
        while (!File(dir, "settings.gradle.kts").exists() && dir.parentFile != null) {
            dir = dir.parentFile
        }
        return dir
    }
}
