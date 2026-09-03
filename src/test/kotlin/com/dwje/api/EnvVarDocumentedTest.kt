package com.dwje.api

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 환경변수 문서화 규약 테스트
 *
 * 프로파일 yml 의 `${VAR}` 중 **기본값이 없는 것은 전부 필수**다.
 * 하나라도 주입되지 않으면 `Could not resolve placeholder` 로 기동 단계에서 죽는다.
 *
 * 실제로 `application-dev.yml`·`application-prod.yml` 이 메일 3종을 요구하는데
 * README 의 환경변수 표와 IntelliJ 실행 구성에는 DB·JWT 두 개만 적혀 있었다.
 * 그래서 안내대로 채워도 dev·prod 는 기동조차 못 했다.
 * 길이·형식 검증으로는 못 걸리는 자리다 — 값이 아니라 **문서가 빠진 것**이기 때문이다.
 *
 * 이 테스트가 고정하는 규약
 * 1. 필수 플레이스홀더는 README 환경변수 표에 이름이 있어야 한다
 * 2. dev 프로파일의 필수 변수는 IntelliJ 실행 구성에도 있어야 한다
 *    (없으면 IDE 에서 실행 버튼을 눌렀을 때만 기동이 실패한다)
 */
class EnvVarDocumentedTest {

    private val projectRoot = File("").absoluteFile
    private val resources = File(projectRoot, "src/main/resources")

    /** 기본값(`${VAR:기본값}`)이 없는 플레이스홀더만 고른다. */
    private fun requiredVars(yml: File): Set<String> =
        Regex("""\$\{([A-Za-z0-9_]+)(:[^}]*)?}""")
            .findAll(yml.readText())
            .filter { it.groupValues[2].isEmpty() }
            .map { it.groupValues[1] }
            .toSet()

    /**
     * 문서에서 환경변수 이름을 **낱말 단위**로 뽑는다.
     *
     * 부분 문자열 검사(`readme.contains(name)`)로는 접두어 관계를 못 거른다 —
     * `"DEV_MAIL_HOST" in "DEV_MAIL_HOSTNAME"` 이 참이라, 막으려던 이름이
     * 정확히 그 이유로 통과한다. 그래서 이름을 뽑아 집합으로 비교한다.
     */
    private fun documentedVars(text: String): Set<String> =
        Regex("""\b((?:DEV|PROD|LOCAL)_[A-Z0-9_]+)\b""")
            .findAll(text)
            .map { it.groupValues[1] }
            .toSet()

    @Test
    @DisplayName("프로파일이 요구하는 필수 환경변수는 README 에 모두 적혀 있다")
    fun requiredVarsAreDocumented() {
        val documented = documentedVars(File(projectRoot, "README.md").readText())

        val ymls = resources.listFiles { f -> f.name.matches(Regex("""application-\w+\.yml""")) }
            .orEmpty()

        // 파일을 하나도 못 찾으면 아래 forEach 가 돌지 않아 **검사가 그냥 통과한다.**
        // 경로가 바뀌거나 정규식이 안 맞게 되면 검사가 조용히 무력해지므로 먼저 막는다.
        assertTrue(ymls.isNotEmpty()) {
            "프로파일 yml 을 하나도 찾지 못했습니다. [${resources.absolutePath}] " +
                "경로나 파일명 규칙이 바뀌었다면 이 검사도 함께 고쳐야 합니다."
        }

        // 요구 변수가 0개면 그것도 검사가 무력해진 신호다(플레이스홀더 문법이 바뀐 경우).
        val totalRequired = ymls.sumOf { requiredVars(it).size }
        assertTrue(totalRequired > 0) {
            "필수 환경변수를 하나도 추출하지 못했습니다. \${'$'}{...} 문법이 바뀌었는지 확인하세요."
        }

        ymls.forEach { yml ->
            val missing = requiredVars(yml) - documented
            assertTrue(missing.isEmpty()) {
                "${yml.name} 이 요구하는 환경변수 ${missing.sorted()} 가 README 환경변수 표에 없습니다. " +
                    "주입하지 않으면 기동이 실패하므로 표에 추가하세요."
            }
        }
    }

    @Test
    @DisplayName("공통 설정에 기본 활성 프로파일을 두지 않는다")
    fun noDefaultActiveProfile() {
        val common = File(resources, "application.yml").readText()

        // 기본값이 있으면 운영 서버에서 옵션을 빼고 띄웠을 때 로컬 설정으로 돈다.
        // 로컬 프로파일에는 시험용 설정(고정 인증코드·발송 상한 해제·소스에 박힌 JWT 키)이 있다.
        assertTrue(!common.contains(Regex("""profiles:\s*\n\s*active:"""))) {
            "application.yml 에 기본 활성 프로파일이 들어왔습니다. " +
                "옵션 없이 띄운 운영 서버가 그 프로파일 설정으로 돌게 됩니다. " +
                "프로파일은 실행 시 지정합니다 (README '환경변수'·실행_가이드 3.1 참고)."
        }
    }

    @Test
    @DisplayName("허용 프로파일 목록과 설정 파일이 양방향으로 일치한다")
    fun knownProfilesMatchProfileFiles() {
        // 한 방향만 보면 반쪽이다.
        //   목록에만 있으면 → 설정 파일 없는 프로파일을 통과시켜, 그 환경 설정이 통째로 빠진 채 뜬다
        //   파일만 있으면   → 정상 프로파일을 "알 수 없는 프로파일" 로 거절해 기동이 막힌다
        val fromFiles = resources.listFiles { f ->
            f.name.matches(Regex("""application-(\w+)\.yml"""))
        }.orEmpty()
            .map { Regex("""application-(\w+)\.yml""").find(it.name)!!.groupValues[1] }
            .toSet()

        assertTrue(KNOWN_PROFILES == fromFiles) {
            "허용 프로파일 목록과 설정 파일이 어긋납니다.\n" +
                "  DwjeApiApplication.KNOWN_PROFILES : ${KNOWN_PROFILES.sorted()}\n" +
                "  application-*.yml                 : ${fromFiles.sorted()}\n" +
                "  목록에만 있는 것: ${(KNOWN_PROFILES - fromFiles).sorted()} (설정 파일 없이 기동됩니다)\n" +
                "  파일에만 있는 것: ${(fromFiles - KNOWN_PROFILES).sorted()} (기동이 거절됩니다)"
        }
    }

    @Test
    @DisplayName("접두어가 같은 다른 이름은 문서화된 것으로 인정하지 않는다")
    fun prefixMatchIsNotAccepted() {
        // 이 테스트가 지키는 것은 위 두 검사의 '판정 방식' 이다.
        // 부분 문자열 비교로 되돌리면 이 단정이 깨진다.
        val documented = documentedVars("| dev | `DEV_MAIL_HOSTNAME` |")

        assertTrue("DEV_MAIL_HOST" !in documented) {
            "DEV_MAIL_HOSTNAME 만 적힌 문서가 DEV_MAIL_HOST 를 문서화한 것으로 인정됐습니다. " +
                "이름 비교가 낱말 단위가 아니라 부분 문자열로 되돌아갔습니다."
        }
        assertTrue("DEV_MAIL_HOSTNAME" in documented) {
            "낱말 단위 추출이 정상 이름을 놓쳤습니다."
        }
    }

    @Test
    @DisplayName("dev 프로파일의 필수 환경변수는 IntelliJ 실행 구성에도 있다")
    fun devVarsArePresentInRunConfiguration() {
        val yml = File(resources, "application-dev.yml")
        val runConfig = File(projectRoot, ".run/dwje-api [dev].run.xml")

        // 예전에는 파일이 없으면 조용히 return 했다. 그래서 실행 구성을 지우면
        // 이 검사가 통과했다 — 막으려던 상황(IDE 실행이 기동 실패)이 그대로 통과하는 셈이다.
        // 실행 구성은 저장소에 함께 두는 파일이므로 없으면 실패로 본다.
        assertTrue(runConfig.exists()) {
            "IntelliJ 실행 구성이 없습니다. [${runConfig.path}] " +
                "저장소에 포함되는 파일입니다. 의도적으로 없애려면 이 검사도 함께 없애세요."
        }

        val declared = runConfig.readText()
        requiredVars(yml).forEach { name ->
            assertTrue(declared.contains("""name="$name"""")) {
                "application-dev.yml 이 요구하는 '$name' 이 실행 구성에 없습니다. " +
                    "IDE 에서 실행하면 Could not resolve placeholder 로 기동이 실패합니다."
            }
        }
    }
}
