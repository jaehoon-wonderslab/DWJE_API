package com.dwje.api.service

import com.dwje.api.repository.DataFieldRepository
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * 새로 발견된 응답 데이터 이름 — 항목 표에 등록되지 않은 응답 값 이름을 찾아 남긴다 (V83, 2026-10-08)
 *
 * 데이터 접근 권한은 응답 데이터 이름(JSON 필드명) 단위로 가린다. 개발자가 응답에 새 값을 넣으면 등록되기 전까지 모두에게 보인다.
 * 공통 마스킹([com.dwje.api.common.response.DataFieldMaskingAdvice])이 응답을 내보낼 때 이 서비스를 불러,
 * 숫자 · 글자 값을 담은 이름 중 항목 표에 없는 것을 `ax.tb_sys_data_attr_seen` 에 남긴다. 화면이 「새로 발견된 응답 데이터」 로 보인다.
 *
 * - 값은 남기지 않는다 — 이름 · 시각 · 횟수 · 경로만.
 * - 같은 경로는 [SAMPLE_INTERVAL_MS] 에 한 번만 본다(표본) — 모든 응답을 훑지 않는다. 통합관리자 응답도 본다.
 * - 업무 데이터 API 만 본다([OBSERVED_PREFIXES]) — 시스템관리 · 이력 · 설정 응답은 가릴 대상이 아니다.
 * - 목록 · 묶음을 담은 이름(items · rows …)은 보지 않고 그 안의 값 이름만 본다. 예약어(로그인 · 권한 키)는 뺀다.
 * - 기록 실패는 응답에 영향을 주지 않는다(로그만).
 */
@Service
class DataAttrDiscoveryService(
    private val dataFieldRepository: DataFieldRepository,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 경로 → 마지막으로 본 시각 */
    private val lastSampled = ConcurrentHashMap<String, Long>()
    @Volatile private var registered: Pair<Long, Set<String>>? = null
    @Volatile private var tableReady: Boolean? = null

    companion object {
        /** 같은 경로를 다시 보는 간격 */
        const val SAMPLE_INTERVAL_MS = 5 * 60_000L
        /** 등록된 이름 목록 캐시 수명 */
        const val REGISTERED_TTL_MS = 60_000L
        /** 한 응답에서 남길 이름 수 상한 */
        const val MAX_KEYS_PER_RESPONSE = 200

        /**
         * 보는 API — 업무 데이터(생산 · 품질 · 보고서 · 대시보드 · 알림 · 기준정보)만.
         * 시스템관리 · 연동 이력 · 내려받기 이력 · 감사 로그 · 알림 설정 · 용어 사전 · 질의 이력 응답은 가릴 대상이 아니어서 보지 않는다
         * (2026-10-08 로컬 전수 호출: 이름 458개 중 절반 이상이 그쪽이었다).
         */
        val OBSERVED_PREFIXES = listOf(
            "/api/v1/dashboard/", "/api/v1/production/", "/api/v1/quality/", "/api/v1/reports/", "/api/v1/alerts", "/api/v1/common/masters"
        )
        val SKIPPED_PREFIXES = listOf("/api/v1/dashboard/uploads", "/api/v1/alerts/send-logs")

        fun observed(path: String): Boolean =
            OBSERVED_PREFIXES.any { path.startsWith(it) } && SKIPPED_PREFIXES.none { path.startsWith(it) }

        /** 숫자 경로 조각 → {id} — 같은 API 를 한 경로로 묶는다 */
        fun normalizePath(path: String): String =
            path.split('/').joinToString("/") { seg -> if (seg.isNotEmpty() && (seg.all { it.isDigit() } || seg.matches(Regex("[0-9a-fA-F-]{20,}")))) "{id}" else seg }

        /** 식별자 꼴 이름 — 이것이 아니면(한글 · 공백 · 기호) 필드명이 아니라 데이터 값이 키 자리에 들어간 것이다 */
        private val IDENT = Regex("^[A-Za-z_$][A-Za-z0-9_$]*$")
        /** 대문자 · 숫자만의 이름(BURR · NG_TYPE) — 코드값이 키 자리에 들어간 경우가 많다 */
        private val CODE_LIKE = Regex("^[A-Z0-9_]{2,}$")

        /**
         * 값이 키 자리에 들어간 묶음인지 — 예) 제품별 수율 `loss: {"품질검사": 3, "스크래치": 5, "BURR": 4}` (2026-10-08).
         * 키의 절반 이상이 식별자 꼴이 아니거나 대문자 코드꼴이면 그렇다고 본다. 이런 묶음은 안의 키를 하나하나 남기지 않고
         * 묶음 이름(loss) 하나로 남긴다 — 유형이 늘 때마다 새 이름이 생기지 않게.
         */
        fun isValueKeyedMap(node: ObjectNode): Boolean {
            val names = node.fieldNames().asSequence().toList()
            if (names.size < 2) return false
            val valueLike = names.count { !IDENT.matches(it) || CODE_LIKE.matches(it) }
            return valueLike * 2 >= names.size
        }

        /**
         * 숫자 · 글자 값을 담은 이름 — 목록 · 묶음(객체 · 배열)을 담은 이름은 빼고 안으로 들어간다. null 값만 있는 이름도 넣는다
         * (이미 가려져 null 일 수 있다). 값이 키 자리에 들어간 묶음은 그 묶음 이름 하나로 넣는다([isValueKeyedMap]).
         * 스택으로 훑어 깊은 트리에서도 넘치지 않는다.
         */
        fun scalarKeys(root: JsonNode, limit: Int = MAX_KEYS_PER_RESPONSE): Set<String> {
            val out = linkedSetOf<String>()
            val stack = ArrayDeque<Pair<String?, JsonNode>>().apply { add(null to root) }
            while (stack.isNotEmpty() && out.size < limit) {
                val (name, node) = stack.removeLast()
                when (node) {
                    is ObjectNode -> {
                        if (name != null && isValueKeyedMap(node)) { out.add(name); continue }
                        node.fields().forEach { (k, v) ->
                            if (v.isContainerNode) stack.add(k to v)
                            else if (v.isNumber || v.isTextual || v.isNull) out.add(k)
                        }
                    }
                    is ArrayNode -> node.forEach { if (it.isContainerNode) stack.add(name to it) }
                    else -> Unit
                }
            }
            return out
        }
    }

    /**
     * 응답 하나를 표본으로 본다 — 간격 안이면 아무것도 하지 않는다.
     * @param path 요청 경로 (예: /api/v1/quality/aoi/dimension/summary)
     * @param data ApiResponse.data
     */
    fun observe(path: String, data: Any?) {
        if (data == null || !observed(path)) return
        val key = normalizePath(path)
        val now = System.currentTimeMillis()
        val last = lastSampled[key]
        if (last != null && now - last < SAMPLE_INTERVAL_MS) return
        lastSampled[key] = now
        try {
            if (tableReady != true) {
                tableReady = dataFieldRepository.hasSeenTable()
                if (tableReady != true) return
            }
            val tree: JsonNode = if (data is JsonNode) data else objectMapper.valueToTree(data)
            val known = registeredNames()
            val found = scalarKeys(tree)
                .filter { DataFieldService.ATTR_NAME_PATTERN.matches(it) && it !in DataFieldService.RESERVED_ATTRS && it !in known }
            if (found.isNotEmpty()) dataFieldRepository.upsertSeen(found, key)
        } catch (e: Exception) {
            log.warn("응답 데이터 이름 기록 실패 path={} : {}", key, e.message)
        }
    }

    private fun registeredNames(): Set<String> {
        val now = System.currentTimeMillis()
        registered?.let { (at, set) -> if (now - at < REGISTERED_TTL_MS) return set }
        val fresh = dataFieldRepository.findAllAttrNames()
        registered = now to fresh
        return fresh
    }

    /** 항목 표가 바뀌면 바로 다시 읽게 — DataFieldService.invalidate 가 부른다 */
    fun invalidate() { registered = null }
}
