package com.dwje.api.middleware

import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader

/**
 * Request Body 재읽기를 지원하는 요청 래퍼
 *
 * 표준 ServletInputStream 은 1회만 읽을 수 있어, 보안 필터가 본문을 검사하면
 * 컨트롤러가 본문을 다시 읽지 못한다. 진입 시점에 본문 전체를 메모리에 적재해
 * 필터와 컨트롤러가 각각 독립적으로 읽을 수 있게 한다.
 *
 * @param request  원본 요청
 * @param maxBytes 캐싱 상한 (초과 시 캐싱하지 않고 원본 스트림을 그대로 노출)
 */
class CachedBodyHttpServletRequest(
    request: HttpServletRequest,
    maxBytes: Int = 1024 * 1024
) : HttpServletRequestWrapper(request) {

    /** 캐싱된 본문. 상한 초과 또는 본문 없음이면 null */
    val cachedBody: ByteArray? = runCatching {
        val length = request.contentLength
        if (length in 1..maxBytes) request.inputStream.readAllBytes() else null
    }.getOrNull()

    override fun getInputStream(): ServletInputStream {
        val body = cachedBody ?: return super.getInputStream()
        val delegate = ByteArrayInputStream(body)

        return object : ServletInputStream() {
            override fun read(): Int = delegate.read()
            override fun available(): Int = delegate.available()
            override fun isFinished(): Boolean = delegate.available() == 0
            override fun isReady(): Boolean = true
            override fun setReadListener(listener: ReadListener?) = Unit
        }
    }

    override fun getReader(): BufferedReader =
        BufferedReader(InputStreamReader(inputStream, characterEncoding ?: Charsets.UTF_8.name()))

    /** 캐싱된 본문을 UTF-8 문자열로 반환한다. (보안 필터 검사용) */
    fun bodyAsString(): String? = cachedBody?.toString(Charsets.UTF_8)
}
