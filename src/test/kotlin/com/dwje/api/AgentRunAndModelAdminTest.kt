package com.dwje.api

import com.dwje.api.common.exception.ConflictingValueException
import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.common.util.MenuId
import com.dwje.api.common.validation.CodeValidator
import com.dwje.api.model.request.AiModelConfigCreateRequest
import com.dwje.api.repository.AgentRunRepository
import com.dwje.api.repository.AiModelConfigRepository
import com.dwje.api.service.AgentRunRecorder
import com.dwje.api.service.AgentRunWriter
import com.dwje.api.service.AgentStatusService
import com.dwje.api.service.AiModelConfigService
import com.dwje.api.service.AuditLogService
import com.dwje.api.service.AuthorizationService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * Agent 실행 이력 기록 + 모델 관리 화면 4종 — 서비스 규약 (2026-09-22)
 *
 * 1. 기록은 곁다리다 — 저장소가 터져도 본 기능은 값을 그대로 돌려준다
 * 2. `measure` 는 예외를 ERROR 로 남기고 **그대로 올려보낸다** (삼키면 화면이 성공으로 오해한다)
 * 3. ⑨ 는 API 가 부르지 않는다 (Alert_Engine 담당 — 중복 방지)
 * 4. 최근 10분 창에 실행이 없으면 master.state 는 OK 가 아니라 IDLE
 * 5. 설정 값은 형식(valueType)에 맞아야 저장된다 · 같은 (분류,키)는 409
 */
class AgentRunAndModelAdminTest {

    private val admin = UserPrincipal(
        userId = "T1", userName = "t", deptId = 9, deptName = "전산팀", deptAbbr = null, positionCd = null,
        plantCd = null, superAdmin = true
    )

    private fun auth(menuId: String) = mock(AuthorizationService::class.java)
        .also { `when`(it.requireMenu(menuId)).thenReturn(admin) }

    private val audit = object : AuditLogService(mock(com.dwje.api.repository.AuditLogRepository::class.java), mock(AuthorizationService::class.java)) {
        override fun record(logType: String, menuId: String?, fieldKey: String?, targetDesc: String?, resultCd: String, maskedCnt: Int, remark: String?) {}
    }

    // ── 기록기 ──────────────────────────────────────────────────────────────

    /** 실행 이력을 메모리에 쌓는 저장소 */
    private class MemRuns : AgentRunRepository(mock(NamedParameterJdbcTemplate::class.java)) {
        val rows = mutableListOf<Map<String, Any?>>()
        var fail = false
        override fun insert(agentNo: String, stateCd: String, throughput: String?, elapsedMs: Long?, message: String?, error: Boolean): Long {
            if (fail) throw IllegalStateException("DB 없음")
            rows += mapOf("agentNo" to agentNo, "state" to stateCd, "load" to throughput, "elapsedMs" to elapsedMs, "error" to error)
            return rows.size.toLong()
        }
        override fun findAgent(agentNo: String) =
            if (agentNo in listOf("①", "③", "⑦")) mapOf("agentId" to 1, "no" to agentNo, "name" to "시험", "active" to true) else null
    }

    private fun recorder(repo: MemRuns) = AgentRunRecorder(AgentRunWriter(repo))

    @Test
    @DisplayName("기록 — 저장소가 터져도 본 기능은 멈추지 않는다(0 을 돌려주고 로그만)")
    fun recordNeverBreaksCaller() {
        val repo = MemRuns().apply { fail = true }
        assertEquals(0L, recorder(repo).record(AgentRunRecorder.VISION, "제품 1건"))
        assertTrue(repo.rows.isEmpty())

        // 없는 번호도 예외가 아니라 0 이다
        val ok = MemRuns()
        assertEquals(1L, recorder(ok).record(AgentRunRecorder.JUDGE, "판정 5건", elapsedMs = 12))
        assertEquals("③", ok.rows.single()["agentNo"])
        assertEquals(12L, ok.rows.single()["elapsedMs"])
    }

    @Test
    @DisplayName("measure — 성공은 걸린 시간과 처리량을, 실패는 ERROR 를 남기고 예외를 그대로 올려보낸다")
    fun measureRecordsBothOutcomes() {
        val repo = MemRuns()
        val rec = recorder(repo)

        val result = rec.measure(AgentRunRecorder.VISION, "수집", throughput = { n: Int -> "제품 ${n}건" }) { 7 }
        assertEquals(7, result)
        assertEquals("제품 7건", repo.rows.single()["load"])
        assertEquals(false, repo.rows.single()["error"])

        val boom = assertThrows(IllegalStateException::class.java) {
            rec.measure<Int>(AgentRunRecorder.VISION, "수집") { throw IllegalStateException("원천 응답 없음") }
        }
        assertEquals("원천 응답 없음", boom.message, "기록하려고 예외를 삼키면 화면이 성공으로 오해한다")
        assertEquals("ERROR", repo.rows.last()["state"])
        assertEquals(true, repo.rows.last()["error"])
    }

    @Test
    @DisplayName("⑨ 이상 알림은 API 가 부르지 않는다 — Alert_Engine 담당이라 중복이 된다")
    fun alertAgentIsNotRecordedByApi() {
        val sources = java.io.File("src/main/kotlin/com/dwje/api/service").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "AgentRunRecorder.kt" }
            .toList()
        assertTrue(sources.size > 20, "서비스 소스를 못 찾았다 — 경로가 바뀌었는지 확인")

        val callers = sources.filter { f ->
            val t = f.readText()
            t.contains("AgentRunRecorder.ALERT") || Regex("""agentNo\s*=\s*"⑨"""").containsMatchIn(t)
        }
        assertTrue(callers.isEmpty(), "⑨ 를 기록하는 곳이 생겼다 : ${callers.map { it.name }}")
    }

    // ── Agent 현황 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("재시작 — 프로세스를 띄우지 않는다는 사실을 응답 note 로 알린다. 없는 번호는 404")
    fun restartIsBookkeepingOnly() {
        val repo = MemRuns()
        val service = AgentStatusService(repo, recorder(repo), auth(MenuId.AI_AGENT), audit, mock(CodeValidator::class.java))

        val out = service.restartAgent("③")
        assertEquals(true, out["success"])
        assertTrue((out["note"] as String).contains("실행 이력만"), out["note"].toString())
        assertEquals("RUNNING", repo.rows.single()["state"])

        assertThrows(com.dwje.api.common.exception.ResourceNotFoundException::class.java) { service.restartAgent("X") }
    }

    // ── AI 모델 설정 ────────────────────────────────────────────────────────

    private class MemConfig : AiModelConfigRepository(mock(NamedParameterJdbcTemplate::class.java)) {
        val rows = linkedMapOf<Int, MutableMap<String, Any?>>()
        private var seq = 0
        override fun findConfigByKey(categoryCd: String, configKey: String) =
            rows.values.firstOrNull { it["category"] == categoryCd && it["key"] == configKey }?.toMap()
        override fun findConfig(configId: Int) = rows[configId]?.toMap()
        override fun insertConfig(
            categoryCd: String, configKey: String, configNm: String, configValue: String, valueTypeCd: String,
            unit: String?, optValues: String?, description: String?, agentNo: String?, actor: String
        ): Int {
            val id = ++seq
            rows[id] = mutableMapOf(
                "configId" to id, "category" to categoryCd, "key" to configKey, "name" to configNm,
                "value" to configValue, "valueType" to valueTypeCd, "options" to optValues, "active" to true
            )
            return id
        }
    }

    private fun configService(repo: MemConfig): AiModelConfigService {
        val validator = mock(CodeValidator::class.java)
        return AiModelConfigService(
            repo, MemRuns(), auth(MenuId.BASE_MODEL), audit, validator
        )
    }

    @Test
    @DisplayName("설정 등록 — 값이 형식에 맞아야 저장되고, 같은 (분류,키)는 409")
    fun configValueTypeAndDuplicate() {
        val repo = MemConfig()
        val service = configService(repo)

        service.createConfig(AiModelConfigCreateRequest("ANOMALY", "defect_rate_warn", "주의 임계", "3.5", "NUM", "%"))
        assertEquals("3.5", repo.rows.values.single()["value"])

        val dup = assertThrows(ConflictingValueException::class.java) {
            service.createConfig(AiModelConfigCreateRequest("ANOMALY", "defect_rate_warn", "중복", "1", "NUM"))
        }
        assertEquals(409, dup.errorCode.status.value())

        val bad = assertThrows(InvalidParameterException::class.java) {
            service.createConfig(AiModelConfigCreateRequest("ANOMALY", "other_key", "숫자 아님", "abc", "NUM"))
        }
        assertEquals("value", bad.field)

        val badKey = assertThrows(InvalidParameterException::class.java) {
            service.createConfig(AiModelConfigCreateRequest("ANOMALY", "BadKey", "형식 위반", "1", "NUM"))
        }
        assertEquals("key", badKey.field)

        val badSelect = assertThrows(InvalidParameterException::class.java) {
            service.createConfig(AiModelConfigCreateRequest("CLASSIFY", "mode", "선택", "zzz", "SELECT", options = "auto,manual"))
        }
        assertTrue(badSelect.message.contains("auto"), badSelect.message)
    }
}
