package com.dwje.api.common.util

/**
 * 응답 문구 속 내부 주소 가림 — 12 기획서 SYN-05 (공통 D-14)
 *
 * 이관 엔진은 원본 접속 실패 원인을 드라이버 문구 그대로 남긴다(「호스트 …, 포트 …에 대한 TCP/IP 연결에 실패」).
 * 그 문구가 화면·엑셀로 나가면 사내 서버 주소가 함께 퍼진다. DB 원문은 두고 **응답 직전에만** 가린다.
 * 포트 번호와 원인 문구는 남긴다 — 운영자가 무엇이 실패했는지는 알아야 한다.
 */
object SensitiveText {

    private val JDBC_URL = Regex("""(?i)jdbc:[^\s"'<>]+""")
    private val HOST_KO = Regex("""호스트\s+[^\s,]+\s*,\s*포트""")
    private val HOST_EN = Regex("""(?i)\bhost\s+[^\s,]+\s*,\s*port""")
    private val IPV4 = Regex("""\b\d{1,3}(\.\d{1,3}){3}\b""")

    /** 접속 문자열 · 「호스트 X, 포트」 · IPv4 를 가린다. null·빈 값은 그대로 */
    fun mask(text: String?): String? {
        if (text.isNullOrEmpty()) return text
        return text
            .replace(JDBC_URL, "(접속 문자열 가림)")
            .replace(HOST_KO, "호스트 (가림), 포트")
            .replace(HOST_EN, "host (가림), port")
            .replace(IPV4, "***.***.***.***")
    }
}
