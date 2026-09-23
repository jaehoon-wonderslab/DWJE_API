package com.dwje.api.service

import com.dwje.api.common.exception.ConflictingValueException
import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.exception.ResourceNotFoundException
import com.dwje.api.common.util.MenuId
import com.dwje.api.common.validation.CodeValidator
import com.dwje.api.model.request.AiModelConfigCreateRequest
import com.dwje.api.model.request.AiModelConfigSaveRequest
import com.dwje.api.model.request.AiModelConfigUpdateRequest
import com.dwje.api.repository.AgentRunRepository
import com.dwje.api.repository.AiModelConfigRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * AI 모델 설정 서비스 (SY-10 · base-model)
 *
 * Agent 별 이상 탐지 임계치와 분류 기준을 키/값으로 관리한다.
 * (③ 불량 판정 Agent 의 불량 태그 관리는 2026-09-23 에 뺐다 — 근거 표를 V42 가 지운다.)
 *
 * ## 값은 문자열로 저장된다
 * `config_value` 가 varchar 다. 형(`value_type_cd`)에 맞는 값인지는 **저장할 때** 확인한다 —
 * 읽는 쪽(AOI 예측·판정)은 숫자로 변환해 쓰므로, 여기서 걸러 두지 않으면 나중에 엉뚱한 자리에서
 * 형 변환 오류로 터진다.
 *
 * 접근 : 화면 권한 `base-model`(ax.tb_sys_dept_menu_perm) · 값 마스킹 : 없음 (웹 메뉴 없음 — 통합관리자 전용)
 */
@Service
class AiModelConfigService(
    private val repository: AiModelConfigRepository,
    private val agentRunRepository: AgentRunRepository,
    private val authorizationService: AuthorizationService,
    private val auditLogService: AuditLogService,
    private val codeValidator: CodeValidator
) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val CAT_GROUP = "AI_CONFIG_CAT"
        private const val VALUE_TYPE_GROUP = "AI_VALUE_TYPE"

        /** 설정 키 형식 — 영문 소문자로 시작하는 snake/dot 표기 */
        private val KEY_PATTERN = Regex("^[a-z][a-z0-9_.]{1,49}$")
    }

    // ── 설정 키/값 ──────────────────────────────────────────────────────────

    /**
     * 설정 조회 (No.191)
     *
     * 화면이 분류별 탭으로 그리므로 `byCategory` 로 묶어서도 함께 낸다.
     */
    @Transactional(readOnly = true)
    fun getConfigs(category: String?, agentCd: String?, applied: Boolean?): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.BASE_MODEL)

        val items = repository.findConfigs(category, agentCd, applied)
        return mapOf(
            "items" to items,
            "byCategory" to items.groupBy { it["category"] }
                .map { (cat, rows) ->
                    mapOf(
                        "category" to cat,
                        "categoryNm" to rows.first()["categoryNm"],
                        "items" to rows
                    )
                },
            "agents" to agentRunRepository.findAgents().map {
                mapOf("agentCd" to it["no"], "name" to it["name"])
            }
        )
    }

    /** 설정 등록 */
    @Transactional
    fun createConfig(request: AiModelConfigCreateRequest): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.BASE_MODEL)

        val category = request.category.trim()
        codeValidator.require(CAT_GROUP, category, "category", "설정 분류")
        codeValidator.require(VALUE_TYPE_GROUP, request.valueType, "valueType", "값 형식")

        val key = request.key.trim()
        if (!KEY_PATTERN.matches(key)) {
            throw InvalidParameterException(
                "설정 키는 영문 소문자로 시작하는 2~50자(소문자·숫자·`_`·`.`)여야 합니다. [$key]", "key"
            )
        }
        val value = requireValidValue(request.value.trim(), request.valueType, request.options)
        requireAgentExists(request.agentCd)

        repository.findConfigByKey(category, key)?.let { throw duplicatedConfig(it) }

        val configId = try {
            repository.insertConfig(
                categoryCd = category,
                configKey = key,
                configNm = request.name.trim(),
                configValue = value,
                valueTypeCd = request.valueType,
                unit = request.unit?.trim()?.takeIf { it.isNotBlank() },
                optValues = request.options?.trim()?.takeIf { it.isNotBlank() },
                description = request.description?.trim()?.takeIf { it.isNotBlank() },
                agentNo = request.agentCd?.trim()?.takeIf { it.isNotBlank() },
                actor = principal.userId
            )
        } catch (e: DuplicateKeyException) {
            // 사전 조회를 통과한 뒤 경합으로 UNIQUE 에 걸린 경우 — 500 대신 같은 409 를 낸다.
            throw ConflictingValueException("이미 등록된 설정 키입니다. [$category / $key]", "key")
        }

        audit("AI 모델 설정 등록 [$category / $key]", "값=$value, 형식=${request.valueType}")
        log.info("AI 모델 설정 등록 : configId={} {}/{}", configId, category, key)

        return mapOf("configId" to configId, "category" to category, "key" to key)
    }

    /** 설정 수정 — 분류·키는 바꾸지 않는다(그 둘이 읽는 쪽의 조회 열쇠다) */
    @Transactional
    fun updateConfig(configId: Int, request: AiModelConfigUpdateRequest): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.BASE_MODEL)

        val before = repository.findConfig(configId)
            ?: throw ResourceNotFoundException("AI 모델 설정을 찾을 수 없습니다. [configId=$configId]")

        codeValidator.require(VALUE_TYPE_GROUP, request.valueType, "valueType", "값 형식")
        val value = requireValidValue(request.value.trim(), request.valueType, request.options)
        requireAgentExists(request.agentCd)

        repository.updateConfig(
            configId = configId,
            configNm = request.name.trim(),
            configValue = value,
            valueTypeCd = request.valueType,
            unit = request.unit?.trim()?.takeIf { it.isNotBlank() },
            optValues = request.options?.trim()?.takeIf { it.isNotBlank() },
            description = request.description?.trim()?.takeIf { it.isNotBlank() },
            agentNo = request.agentCd?.trim()?.takeIf { it.isNotBlank() },
            actor = principal.userId
        )

        audit(
            "AI 모델 설정 수정 [${before["category"]} / ${before["key"]}]",
            "값 ${before["value"]} → $value"
        )
        return mapOf("success" to true, "config" to repository.findConfig(configId))
    }

    /**
     * 값 일괄 저장 (No.192)
     *
     * 화면이 여러 칸을 한 번에 저장한다. 한 줄이라도 형식이 어긋나면 **아무것도 저장하지 않는다** —
     * 절반만 반영되면 어느 값이 새 값이고 어느 값이 옛 값인지 화면이 알 수 없다.
     */
    @Transactional
    fun saveConfigValues(request: AiModelConfigSaveRequest): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.BASE_MODEL)

        if (request.items.isEmpty()) {
            throw InvalidParameterException("저장할 설정이 없습니다.", "items")
        }

        // 1) 전부 찾아 두고 형식까지 확인한 뒤에 쓴다
        val targets = request.items.map { item ->
            val found = when {
                item.configId != null -> repository.findConfig(item.configId)
                !item.category.isNullOrBlank() && !item.key.isNullOrBlank() ->
                    repository.findConfigByKey(item.category.trim(), item.key.trim())
                        ?.let { repository.findConfig(it["configId"] as Int) }
                else -> throw InvalidParameterException(
                    "대상을 지정해 주세요. configId 또는 (category, key) 가 있어야 합니다.", "configId"
                )
            } ?: throw ResourceNotFoundException(
                "AI 모델 설정을 찾을 수 없습니다. [${item.configId ?: "${item.category}/${item.key}"}]"
            )

            val value = requireValidValue(
                item.value.trim(),
                found["valueType"] as? String ?: "TEXT",
                found["options"] as? String
            )
            found to value
        }

        // 2) 쓴다
        var changed = 0
        targets.forEach { (found, value) ->
            if (found["value"] != value) {
                repository.updateConfigValue(found["configId"] as Int, value, principal.userId)
                changed++
            }
        }

        audit(
            "AI 모델 설정 일괄 저장",
            targets.joinToString(", ") { (f, v) -> "${f["key"]}=$v" }.take(400)
        )
        log.info("AI 모델 설정 일괄 저장 : 요청 {}건 · 변경 {}건", targets.size, changed)

        return mapOf("success" to true, "savedCnt" to targets.size, "changedCnt" to changed)
    }

    /** 사용/미사용 전환 — 지우지 않는다(읽는 쪽이 키로 값을 찾기 때문) */
    @Transactional
    fun changeConfigState(configId: Int, on: Boolean): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.BASE_MODEL)

        val before = repository.findConfig(configId)
            ?: throw ResourceNotFoundException("AI 모델 설정을 찾을 수 없습니다. [configId=$configId]")

        val changed = repository.updateConfigUseFlg(configId, on, principal.userId)
        audit("AI 모델 설정 ${if (on) "사용" else "미사용"} [${before["category"]} / ${before["key"]}]", "configId=$configId")

        return mapOf("success" to true, "applied" to on, "changed" to (changed > 0))
    }

    // ── 내부 보조 ───────────────────────────────────────────────────────────

    /** 값이 형식에 맞는지 본다 — 어긋나면 저장하지 않는다 */
    private fun requireValidValue(value: String, valueType: String, options: String?): String {
        when (valueType) {
            "NUM" -> value.toBigDecimalOrNull()
                ?: throw InvalidParameterException("값 형식이 숫자(NUM)인데 숫자가 아닙니다. [$value]", "value")
            "BOOL" -> if (value.lowercase() !in setOf("true", "false", "y", "n")) {
                throw InvalidParameterException("값 형식이 여부(BOOL)입니다. true/false 또는 Y/N 을 보내 주세요. [$value]", "value")
            }
            "SELECT" -> {
                val allowed = options?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
                if (allowed.isNotEmpty() && value !in allowed) {
                    throw InvalidParameterException(
                        "선택 값이 목록에 없습니다. [$value] 허용 값은 ${allowed.joinToString(" · ")} 입니다.", "value"
                    )
                }
            }
            // TEXT · LIST 는 형 제약이 없다
        }
        return value
    }

    /** 담당 Agent 번호가 실재하는지 — 없는 번호를 넣으면 agent_id 가 null 로 들어가 조용히 어긋난다 */
    private fun requireAgentExists(agentCd: String?) {
        val no = agentCd?.trim()?.takeIf { it.isNotBlank() } ?: return
        agentRunRepository.findAgent(no)
            ?: throw InvalidParameterException("그런 Agent 번호가 없습니다. [$no] ①~⑨ 중 하나여야 합니다.", "agentCd")
    }

    private fun duplicatedConfig(existing: Map<String, Any?>): ConflictingValueException =
        ConflictingValueException(
            "이미 등록된 설정 키입니다. [${existing["category"]} / ${existing["key"]}]" +
                if (existing["active"] == false) " (미사용 상태입니다 — 다시 켜서 쓰세요)" else "",
            "key"
        )

    private fun audit(targetDesc: String, remark: String?) {
        auditLogService.record(
            logType = "AUTO_GEN",
            menuId = MenuId.BASE_MODEL,
            targetDesc = targetDesc,
            remark = remark
        )
    }
}
