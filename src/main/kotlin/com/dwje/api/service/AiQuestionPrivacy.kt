package com.dwje.api.service

/** 채팅 이력·검색 이력에 비밀번호, 토큰, SQL 조건값 원문을 남기지 않는다. */
object AiQuestionPrivacy {
    private val assignment = Regex("(?i)\\b(password|passwd|pwd|token|api[_-]?key|secret|authorization)\\b\\s*[:=]\\s*(?:\"[^\"]*\"|'[^']*'|\\S+)")
    private val bearer = Regex("(?i)\\bBearer\\s+\\S+")
    private val sqlPredicate = Regex("(?i)\\b(?:WHERE|INSERT\\s+INTO|UPDATE\\s+\\w+\\s+SET)\\b")

    fun forStorage(question: String): String {
        if (sqlPredicate.containsMatchIn(question)) return AiResponseSanitizer.HIDDEN
        val safe = AiResponseSanitizer.publicText(question) ?: ""
        return assignment.replace(bearer.replace(safe, "Bearer [비공개]")) { match ->
            "${match.groupValues[1]}=[비공개]"
        }
    }
}
