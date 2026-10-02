package com.dwje.api

import com.dwje.api.common.util.SensitiveText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 연동 메시지의 내부 주소 가림 — 12 기획서 SYN-05. 시험 주소는 문서용 대역을 조립해 쓴다. */
class SensitiveTextTest {

    private val ip = listOf(203, 0, 113, 7).joinToString(".")
    private val ipv4 = Regex("""\b\d{1,3}(\.\d{1,3}){3}\b""")

    @Test
    @DisplayName("한글 드라이버 문구 — 호스트만 가리고 포트·원인은 남긴다")
    fun hostKo() {
        val msg = "원본 MSSQL 에 접속할 수 없습니다: Failed to obtain JDBC Connection → 호스트 $ip, 포트 1433에 대한 TCP/IP 연결에 실패했습니다."
        val masked = SensitiveText.mask(msg)!!
        assertFalse(ipv4.containsMatchIn(masked), masked)
        assertTrue(masked.contains("호스트 (가림), 포트 1433에 대한 TCP/IP 연결에 실패했습니다."), masked)
        assertTrue(masked.startsWith("원본 MSSQL 에 접속할 수 없습니다"))
    }

    @Test
    @DisplayName("영문 문구·접속 문자열·남은 IPv4 도 가린다, null·빈 값은 그대로")
    fun others() {
        assertEquals("The TCP/IP connection to the host (가림), port 1433 has failed.",
            SensitiveText.mask("The TCP/IP connection to the host $ip, port 1433 has failed."))
        assertEquals("접속 (접속 문자열 가림) 실패", SensitiveText.mask("접속 jdbc:sqlserver://$ip:1433;databaseName=MES 실패"))
        assertEquals("주소 ***.***.***.*** 응답 없음", SensitiveText.mask("주소 $ip 응답 없음"))
        assertEquals("Connection is not available, request timed out after 30009ms.",
            SensitiveText.mask("Connection is not available, request timed out after 30009ms."))
        assertNull(SensitiveText.mask(null)); assertEquals("", SensitiveText.mask(""))
    }
}
