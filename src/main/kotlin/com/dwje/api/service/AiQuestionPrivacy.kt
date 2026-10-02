package com.dwje.api.service

/**
 * 채팅 이력·검색 이력에 비밀번호, 토큰, SQL 조건값 원문을 남기지 않는다.
 *
 * 개인정보(전화번호·이메일·주민등록번호·카드번호)도 저장 시점에 가린다(08 CHH-09). 되돌릴 수 없다.
 * 카드 → 주민 → 전화 순서로 바꾼다 — 카드번호 16자리 안의 일부가 전화번호로 걸리지 않게 하기 위해서다.
 * 앞뒤가 숫자인 자리는 건너뛴다(제품 코드·긴 숫자 안의 일부). 사번은 가리지 않는다(08 Q6).
 */
object AiQuestionPrivacy {
    private val assignment = Regex("(?i)\\b(password|passwd|pwd|token|api[_-]?key|secret|authorization)\\b\\s*[:=]\\s*(?:\"[^\"]*\"|'[^']*'|\\S+)")
    private val bearer = Regex("(?i)\\bBearer\\s+\\S+")
    private val sqlPredicate = Regex("(?i)\\b(?:WHERE|INSERT\\s+INTO|UPDATE\\s+\\w+\\s+SET)\\b")

    /** 개인정보 규칙 — 적용 순서대로 */
    private val personal = listOf(
        Regex("(?<![\\d-])\\d{4}-?\\d{4}-?\\d{4}-?\\d{4}(?![\\d-])") to "[카드번호]",
        Regex("(?<![\\d-])\\d{6}-?[1-4]\\d{6}(?![\\d-])") to "[주민번호]",
        Regex("(?<![\\d-])01[016-9]-?\\d{3,4}-?\\d{4}(?![\\d-])") to "[전화번호]",
        Regex("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}") to "[이메일]"
    )

    fun forStorage(question: String): String {
        if (sqlPredicate.containsMatchIn(question)) return AiResponseSanitizer.HIDDEN
        val safe = AiResponseSanitizer.publicText(question) ?: ""
        val secretsHidden = assignment.replace(bearer.replace(safe, "Bearer [비공개]")) { match ->
            "${match.groupValues[1]}=[비공개]"
        }
        return personal.fold(secretsHidden) { text, (re, label) -> re.replace(text, label) }
    }
}
