package com.dwje.api.service

/** 모델 출력에 내부 조회 문장이나 스키마 식별자가 섞이면 화면과 이력에 남기지 않는다. */
object AiResponseSanitizer {
    private val schema = Regex("(?i)\\b(?:ax|mes|vec)\\.[a-z_][a-z0-9_]*\\b|\\b[a-z][a-z0-9]*_[a-z0-9_]+\\b")
    private val sql = Regex("(?is)\\b(?:SELECT\\b|WITH\\s+[a-z_][a-z0-9_]*\\s+AS\\s*\\(|INSERT\\s+INTO|UPDATE\\s+[a-z_]|DELETE\\s+FROM)")
    const val HIDDEN = "내부 조회 정보는 표시할 수 없습니다. 질문을 다시 말씀해 주세요."

    fun publicText(value: String?): String? = value?.let { if (schema.containsMatchIn(it) || sql.containsMatchIn(it)) HIDDEN else it }
}
