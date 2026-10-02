package com.dwje.api

import com.dwje.api.common.exception.BusinessException
import com.dwje.api.common.response.ErrorCode
import com.dwje.api.common.security.UserContext
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.config.SecurityWhitelist
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.mvc.method.RequestMappingInfo
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import java.lang.reflect.InvocationTargetException
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.kotlinFunction

/**
 * 화면 권한 점검 테스트 — 공통 기획서 4.2 CMN-02 · 9.4 CMN-06
 *
 * 시스템관리 화면·덕반장 AI 가 쓰는 엔드포인트를 [RequestMappingHandlerMapping] 으로 **전부** 모아 직접 부른다.
 * 엔드포인트를 새로 만들고 권한 검사를 빠뜨리면 이 테스트가 실패한다(목록을 손으로 관리하지 않는다).
 *
 * 1. 화면 권한이 하나도 없는 계정 → 모든 대상 엔드포인트가 403 E-AUTH-002
 * 2. 사용 중 전 화면을 **조회만** 가진 계정 → 조회 성격이 아닌 모든 쓰기(POST·PUT·PATCH·DELETE) 엔드포인트가
 *    E-AUTH-004(쓰기 권한 없음) 또는 E-AUTH-002(통합관리자 전용·제거된 화면)로 막힌다.
 *    조회 성격 POST(내려받기·미리보기)는 [READ_LIKE_WRITES] 에 이유와 함께 적는다(R-10 — 내려받기는 쓰기 대상이 아니다).
 *
 * HTTP 를 거치지 않고 컨트롤러 메서드를 직접 부르므로 본문 검증(@Valid)에 걸리지 않고 서비스의 권한 검사까지 간다.
 * 인자는 존재할 수 없는 값(사번 `__guard__`, ID -1)으로 채우고, 호출은 롤백되는 트랜잭션 안에서 한다.
 * 화이트리스트(로그인 전 경로)는 [SecurityWhitelist] 그대로 뺀다.
 */
@SpringBootTest(properties = ["app.ai.prewarm-enabled=false"])
@ActiveProfiles("local")
class MenuGuardCoverageTest {

    @Autowired lateinit var context: ApplicationContext
    @Autowired @Qualifier("requestMappingHandlerMapping") lateinit var mapping: RequestMappingHandlerMapping
    @Autowired lateinit var jdbc: NamedParameterJdbcTemplate
    @Autowired lateinit var txManager: PlatformTransactionManager

    companion object {
        /** 점검 대상 경로 — 시스템관리 13개 화면 · 이상 알림 설정 · 연동 · 감사 · 업로드 · 덕반장 AI */
        private val TARGET_PREFIXES = listOf(
            "/api/v1/system/", "/api/v1/alert-conditions", "/api/v1/alert-recipient", "/api/v1/alert-escalation-rules",
            "/api/v1/glossary", "/api/v1/ai/chat", "/api/v1/sync", "/api/v1/audit-logs", "/api/v1/download-logs",
            "/api/v1/metrics/standards", "/api/v1/dashboard/uploads", "/api/ai/"
        )

        /**
         * 조회 성격의 POST — 화면 조회 권한으로 통과하는 것이 맞다. 「메서드 경로」 → 이유.
         * 덕반장 AI(ai-chat)는 쓰기 칸이 없는 화면이라 2단계 점검에서 전부 뺀다.
         */
        private val READ_LIKE_WRITES = mapOf(
            "POST /api/v1/glossary/normalize" to "저장하지 않는 정규화 미리보기(07 GLS-16)",
            "POST /api/v1/glossary/terms/export" to "용어 사전 내려받기 — 조회 권한(공통 9.8, R-10)",
            "POST /api/v1/ai/chat/history/export" to "질의 이력 전체 내려받기 — 조회 권한(공통 9.8, R-10)",
            "POST /api/v1/audit-logs/export" to "감사 로그 전체 내려받기 — 조회 권한(공통 9.8, R-10)",
            "POST /api/v1/download-logs/export" to "다운로드 이력 전체 내려받기 — 조회 권한(공통 9.8, R-10)",
            "POST /api/v1/sync/export" to "데이터 연동 전체 내려받기 — 조회 권한(공통 9.8, R-10)",
            "POST /api/v1/download-logs" to "브라우저 내려받기의 이력 신고 — 내려받기는 조회 권한(공통 9.8)"
        )

        /**
         * 화면 권한 검사를 일부러 두지 않은 엔드포인트 — 「메서드 경로」 → 이유. 부르지도 않는다(부르면 실제 기록이 남는다).
         */
        private val NO_SCREEN_GUARD = mapOf(
            "POST /api/v1/download-logs" to "신고 본문의 menuId 화면 조회 권한으로 판정한다(DLG-05, DownloadLogRecordGuardTest). menuId 없는 옛 신고는 app.download-log.require-menu-id 를 켜기 전까지 기록한다"
        )

        /** 쓰기 칸이 없는 화면 — 조회 권한만으로 쓰는 엔드포인트 묶음 */
        private val READ_ONLY_SCREEN_PREFIXES = listOf("/api/v1/ai/chat/", "/api/ai/")
    }

    @AfterEach
    fun clear() = UserContext.clear()

    private data class Endpoint(val method: String, val path: String, val handler: HandlerMethod) {
        val key get() = "$method $path"
        override fun toString() = key
    }

    private fun endpoints(): List<Endpoint> =
        mapping.handlerMethods.flatMap { (info: RequestMappingInfo, handler: HandlerMethod) ->
            val paths = info.pathPatternsCondition?.patternValues ?: info.patternsCondition?.patterns.orEmpty()
            val methods = info.methodsCondition.methods.map { it.name }.ifEmpty { listOf("GET") }
            paths.flatMap { p -> methods.map { m -> Endpoint(m, p, handler) } }
        }.filter { e -> TARGET_PREFIXES.any { e.path.startsWith(it) } && !SecurityWhitelist.isWhitelisted(e.path) }
            .sortedBy { it.key }

    private fun principal(menus: Set<String>) =
        UserPrincipal("__guard__", "권한 점검", -1, "권한 점검", null, null, false, menuPerms = menus)

    /** 대상 메서드를 롤백되는 트랜잭션 안에서 부르고, 던진 업무 예외(없으면 null)를 돌려준다 */
    private fun invoke(e: Endpoint): Throwable? {
        val bean = context.getBean(e.handler.bean as String)
        val fn = e.handler.method.kotlinFunction ?: error("Kotlin 함수가 아닙니다: $e")
        fn.isAccessible = true
        val args = HashMap<KParameter, Any?>()
        fn.parameters.forEach { p ->
            when {
                p.kind == KParameter.Kind.INSTANCE -> args[p] = bean
                p.isOptional -> Unit
                p.type.isMarkedNullable -> args[p] = null
                else -> args[p] = dummy(p.type)
            }
        }
        var thrown: Throwable? = null
        TransactionTemplate(txManager).execute { status ->
            status.setRollbackOnly()
            try {
                fn.callBy(args)
            } catch (ex: InvocationTargetException) {
                thrown = ex.targetException
            } catch (ex: Exception) {
                thrown = ex
            }
        }
        return thrown
    }

    /** 존재할 수 없는 값으로 인자를 채운다 — 권한 검사가 빠져 업무 로직까지 가도 실제 행을 건드리지 않게 */
    private fun dummy(type: KType): Any? {
        val cls = type.classifier as? KClass<*> ?: return null
        return when (cls) {
            String::class -> "__guard__"
            Int::class -> -1
            Long::class -> -1L
            Boolean::class -> false
            Double::class -> -1.0
            BigDecimal::class -> BigDecimal.ONE
            LocalDate::class -> LocalDate.now()
            LocalDateTime::class -> LocalDateTime.now()
            List::class, Collection::class -> emptyList<Any>()
            Set::class -> emptySet<Any>()
            Map::class -> emptyMap<String, Any>()
            jakarta.servlet.http.HttpServletRequest::class -> MockHttpServletRequest()
            jakarta.servlet.http.HttpServletResponse::class -> MockHttpServletResponse()
            org.springframework.web.multipart.MultipartFile::class -> MockMultipartFile("file", "guard.xlsx", null, ByteArray(1))
            else -> {
                val ctor = cls.primaryConstructor ?: return null
                ctor.isAccessible = true
                val ctorArgs = ctor.parameters.filterNot { it.isOptional }
                    .associateWith { if (it.type.isMarkedNullable) null else dummy(it.type) }
                ctor.callBy(ctorArgs)
            }
        }
    }

    private fun codeOf(t: Throwable?): String? = (t as? BusinessException)?.errorCode?.code

    @Test
    @DisplayName("화면 권한이 없는 계정 — 대상 엔드포인트 전부 403 E-AUTH-002")
    fun everyEndpointNeedsScreenPermission() {
        val targets = endpoints()
        assertTrue(targets.size >= 80, "대상 엔드포인트를 모으지 못했다 (${targets.size}건) — 경로 접두사를 확인하세요")

        val violations = targets.filterNot { it.key in NO_SCREEN_GUARD }.mapNotNull { e ->
            UserContext.set(principal(emptySet()))
            val t = invoke(e)
            if (codeOf(t) == ErrorCode.AUTH_MENU_DENIED.code) null
            else "$e → ${t?.let { "${it.javaClass.simpleName}(${codeOf(it)}) ${it.message}" } ?: "통과(권한 검사 없음)"}"
        }
        assertTrue(violations.isEmpty(), "화면 권한 검사가 빠진 엔드포인트 ${violations.size}건:\n" + violations.joinToString("\n"))
    }

    @Test
    @DisplayName("조회 권한만 있는 계정 — 조회 성격이 아닌 쓰기 엔드포인트는 전부 E-AUTH-004(또는 통합관리자 전용 E-AUTH-002)")
    fun writeEndpointsNeedWritePermission() {
        val allMenus = jdbc.queryForList("SELECT menu_id FROM ax.tb_sys_menu WHERE use_flg = 'Y'", MapSqlParameterSource(), String::class.java).toSet()
        val writes = endpoints().filter { it.method != "GET" }
            // 덕반장 AI 는 쓰기 칸이 없지만 질의 이력(/ai/chat/history)의 관리 기능은 쓰기 권한이다 (08 CHH-16)
            .filterNot { e -> READ_ONLY_SCREEN_PREFIXES.any { e.path.startsWith(it) } && !e.path.startsWith("/api/v1/ai/chat/history") }
            .filterNot { it.key in READ_LIKE_WRITES }
        assertTrue(writes.size >= 40, "쓰기 엔드포인트를 모으지 못했다 (${writes.size}건)")

        val allowed = setOf(ErrorCode.AUTH_WRITE_DENIED.code, ErrorCode.AUTH_MENU_DENIED.code)
        val violations = writes.mapNotNull { e ->
            UserContext.set(principal(allMenus))
            val t = invoke(e)
            if (codeOf(t) in allowed) null
            else "$e → ${t?.let { "${it.javaClass.simpleName}(${codeOf(it)}) ${it.message}" } ?: "통과(쓰기 권한 검사 없음)"}"
        }
        assertTrue(violations.isEmpty(), "쓰기 권한 검사가 빠진 엔드포인트 ${violations.size}건:\n" + violations.joinToString("\n"))
    }
}
