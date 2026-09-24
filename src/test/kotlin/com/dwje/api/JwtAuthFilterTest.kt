package com.dwje.api

import com.dwje.api.common.exception.BusinessException
import com.dwje.api.common.exception.GlobalExceptionHandler
import com.dwje.api.common.response.ErrorCode
import com.dwje.api.common.security.JwtTokenProvider
import com.dwje.api.common.security.UserContext
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.config.JwtProperties
import com.dwje.api.middleware.JwtAuthFilter
import com.dwje.api.service.AuthorizationService
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

class JwtAuthFilterTest {

    @RestController
    class FailingChatController {
        @PostMapping("/api/ai/chat")
        fun chat(): Unit = throw BusinessException(ErrorCode.LLM_UPSTREAM_REJECTED)
    }

    @Test
    fun downstreamBusinessExceptionIsNotConvertedToServerError() {
        val tokenProvider = JwtTokenProvider(JwtProperties(secret = "test-only-jwt-signing-secret-123456"))
        val authorizationService = Mockito.mock(AuthorizationService::class.java)
        val principal = UserPrincipal("user", "User", 1, "Dept", null, null, null, false)
        Mockito.doReturn(principal).`when`(authorizationService).loadPrincipal("user", false)

        val request = MockHttpServletRequest("POST", "/api/ai/chat")
        request.addHeader(
            "Authorization",
            "Bearer ${tokenProvider.createAccessToken("user", "User", 1, "Dept", false, null)}"
        )
        val response = MockHttpServletResponse()
        val filter = JwtAuthFilter(tokenProvider, authorizationService, ObjectMapper())

        val e = assertThrows(BusinessException::class.java) {
            filter.doFilter(request, response, FilterChain { _, _ ->
                throw BusinessException(ErrorCode.LLM_UPSTREAM_REJECTED)
            })
        }
        assertEquals(ErrorCode.LLM_UPSTREAM_REJECTED, e.errorCode)
        assertNull(UserContext.currentOrNull())
    }

    @Test
    fun upstreamRejectionReachesWebAsBadGateway() {
        val tokenProvider = JwtTokenProvider(JwtProperties(secret = "test-only-jwt-signing-secret-123456"))
        val authorizationService = Mockito.mock(AuthorizationService::class.java)
        val principal = UserPrincipal("user", "User", 1, "Dept", null, null, null, false)
        Mockito.doReturn(principal).`when`(authorizationService).loadPrincipal("user", false)
        val filter = JwtAuthFilter(tokenProvider, authorizationService, ObjectMapper())
        val token = tokenProvider.createAccessToken("user", "User", 1, "Dept", false, null)
        val builder = MockMvcBuilders.standaloneSetup(FailingChatController())
            .setControllerAdvice(GlobalExceptionHandler())
        builder.addFilters<org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder>(filter)
        val mvc = builder.build()

        val response = mvc.perform(post("/api/ai/chat").header("Authorization", "Bearer $token"))
            .andReturn().response

        assertEquals(502, response.status)
        assertEquals(true, response.contentAsString.contains(ErrorCode.LLM_UPSTREAM_REJECTED.code))
        assertNull(UserContext.currentOrNull())
    }
}
