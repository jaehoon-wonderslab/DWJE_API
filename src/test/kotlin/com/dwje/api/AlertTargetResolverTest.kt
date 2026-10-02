package com.dwje.api

import com.dwje.api.config.AppProperties
import com.dwje.api.model.request.RecipientGroupUpdateRequest
import com.dwje.api.repository.AlertConfigRepository
import com.dwje.api.service.AlertTargetResolver
import com.dwje.api.service.TargetGroupRow
import com.dwje.api.service.TargetMemberRow
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.time.LocalTime
import java.util.Optional

/** 수신 대상 판정 규칙 (05 ALC-03, 06 RCP-03·04) — DB 없이 판정 본체만 본다 */
class AlertTargetResolverTest {

    private val resolver = AlertTargetResolver(
        AlertConfigRepository(mock(NamedParameterJdbcTemplate::class.java)), AppProperties()
    )

    private fun member(id: String, state: String = "ACTIVE", recv: String = "RECV", mail: String? = "$id@x", night: Boolean = false, hp: String? = null) =
        TargetMemberRow(1, id, "사람$id", "부서", state, if (state == "ACTIVE") "사용" else "정지", recv, mail, hp, null, night)

    private val group = TargetGroupRow(1, "G", true, false, listOf("MAIL", "SMS"))
    private val noon = LocalTime.NOON

    @Test
    @DisplayName("조건 채널 ∩ 그룹 채널, 부재·정지 제외, 연락처 없음 제외 — 사유가 순서대로 나온다")
    fun rules() {
        val ms = listOf(member("A"), member("B", recv = "ABSENT"), member("C", state = "SUSPENDED"), member("D", hp = "010"))
        val r = resolver.evaluate(listOf(group), mapOf(1 to ms), listOf("MAIL", "SMS", "POPUP"), noon)
        assertEquals(setOf("A" to "MAIL", "D" to "MAIL", "D" to "SMS"), r.targets.map { it.userId to it.channel }.toSet())
        val reasons = r.skipped.associate { (it.empNo ?: "-") + it.reason to it.reasonNm }
        assertEquals("그룹이 받지 않는 채널(POPUP)", reasons["-CHANNEL_MISMATCH"])
        assertEquals("부재", reasons["BABSENT"])
        assertEquals("계정 정지", reasons["CACCOUNT_INACTIVE"])
        assertTrue("ANO_CONTACT" in reasons) // A 는 SMS 번호가 없다
    }

    @Test
    @DisplayName("야간 — 그룹·개인 모두 야간 미수신이면 제외, 자정 넘김(22~06)")
    fun night() {
        val ms = listOf(member("A"), member("B", night = true))
        val r = resolver.evaluate(listOf(group), mapOf(1 to ms), listOf("MAIL"), LocalTime.of(23, 0))
        assertEquals(listOf("B"), r.targets.map { it.userId })
        assertTrue(resolver.isNight(LocalTime.of(5, 59)))
        assertFalse(resolver.isNight(LocalTime.of(6, 0)))
        assertTrue(resolver.isNight(LocalTime.of(22, 0)))
    }

    @Test
    @DisplayName("사용 중지 그룹·채널 없음·두 그룹 중복 — 중복은 첫 그룹만")
    fun groupsAndDedup() {
        val off = TargetGroupRow(2, "OFF", false, false, listOf("MAIL"))
        val g3 = TargetGroupRow(3, "G3", true, false, listOf("MAIL"))
        val r = resolver.evaluate(listOf(group, off, g3), mapOf(1 to listOf(member("A")), 3 to listOf(member("A").copy(groupId = 3))), listOf("MAIL"), noon)
        assertEquals(listOf(1), r.targets.map { it.groupId })
        assertTrue(r.skipped.any { it.reason == "GROUP_INACTIVE" && it.groupId == 2 })
        assertEquals("NO_CHANNEL", resolver.resolve(listOf(1), emptyList(), noon).skipped.single().reason)
    }

    @Test
    @DisplayName("부분 수정 본문 — 키 없음 = null(유지), JSON null = Optional.empty(비우기), 값 = Optional.of")
    fun optionalMapping() {
        val om = ObjectMapper().registerKotlinModule().registerModule(Jdk8Module())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
        assertNull(om.readValue("{}", RecipientGroupUpdateRequest::class.java).deptId)
        assertEquals(Optional.empty<Int>(), om.readValue("""{"deptId":null}""", RecipientGroupUpdateRequest::class.java).deptId)
        assertEquals(Optional.of(4), om.readValue("""{"deptId":4}""", RecipientGroupUpdateRequest::class.java).deptId)
        assertEquals(emptyList<String>(), om.readValue("""{"memberEmpNos":[]}""", RecipientGroupUpdateRequest::class.java).memberEmpNos)
    }
}
