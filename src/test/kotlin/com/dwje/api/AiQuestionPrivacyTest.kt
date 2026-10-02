package com.dwje.api

import com.dwje.api.service.AiQuestionPrivacy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 질문 원문 저장 시점 개인정보 가림 (08 CHH-09) */
class AiQuestionPrivacyTest {

    private fun s(q: String) = AiQuestionPrivacy.forStorage(q)

    @Test
    @DisplayName("전화번호·이메일·주민번호·카드번호를 가린다")
    fun masksPersonal() {
        assertEquals("[전화번호] 로 연락한 협력사 불량", s("010-1234-5678 로 연락한 협력사 불량"))
        assertEquals("[전화번호] 확인", s("01012345678 확인"))
        assertEquals("담당 [이메일] 에게", s("담당 kim.q@dwje.co.kr 에게"))
        assertEquals("주민 [주민번호]", s("주민 900101-1234567"))
        assertEquals("카드 [카드번호] 결제", s("카드 1234-5678-9012-3456 결제"))
    }

    @Test
    @DisplayName("수량·날짜·사번·제품 코드는 그대로, 기존 비밀값 규칙도 유지")
    fun keepsOthers() {
        val q = "2026-09-30 D63A-S 불량 12,345 개, 사번 10003 의 LOT L260824-031"
        assertEquals(q, s(q))
        // 더 긴 숫자 안의 일부는 전화번호로 보지 않는다
        assertEquals("일련번호 90101234567812", s("일련번호 90101234567812"))
        assertTrue(s("password=abc 로 접속").contains("password=[비공개]"))
    }
}
