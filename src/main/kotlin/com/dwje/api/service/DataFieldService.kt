package com.dwje.api.service

import com.dwje.api.common.exception.BusinessRuleException
import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.exception.ResourceNotFoundException
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.common.util.DataField
import com.dwje.api.common.util.MenuId
import com.dwje.api.common.validation.CodeValidator
import com.dwje.api.model.request.DataFieldAttrRequest
import com.dwje.api.model.request.DataFieldSaveRequest
import com.dwje.api.repository.DataFieldRepository
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 데이터 접근 항목 운영 서비스 (SY-03, V33) — 항목·응답 필드명 CRUD 와 마스킹 판정용 카탈로그.
 *
 * 규칙
 * - 편집 권한은 데이터 접근 권한 화면(`sys-data`)과 같다(전산팀 · 통합관리자).
 * - 등록은 apply_flg='N'. 부서 권한을 채운 뒤 [setApply] 로 켠다. 켠 항목만 `/auth/me` 의 dataFields 에 나가고
 *   AI 답변 표 블록의 blindColumns 판정에 쓰인다. 반영은 재로그인(화면) — 서버 카탈로그는 [CACHE_TTL_MS] 안에 따라온다.
 * - 응답 필드명(attr_name)은 전역 UNIQUE. 중복은 409 `E-RULE-001` "이미 <항목명>에 등록된 필드명입니다".
 * - 기본 7개 항목([DataField.ALL])은 서버 판정 코드가 key 를 직접 쓰므로 삭제할 수 없다(409). 끄려면 apply 를 내린다.
 * - 알림 조건 · 지표 기준 · 보고서 양식 필드 · 문서 태그가 참조하는 항목은 삭제할 수 없다(409, 건수 안내).
 * - 모든 변경은 `tb_sys_perm_log` `DATA_PERM` / `tb_sys_audit_log` `PERM_CHANGE` 로 남긴다.
 */
@Service
class DataFieldService(
    private val dataFieldRepository: DataFieldRepository,
    private val authorizationService: AuthorizationService,
    private val auditLogService: AuditLogService,
    private val codeValidator: CodeValidator
) {

    companion object {
        /** 항목 key — 소문자로 시작, 소문자·숫자·`_`·`-`, 2~30자 (기존 7개와 같은 꼴, 컬럼 varchar(30)) */
        val FIELD_KEY_PATTERN = Regex("^[a-z][a-z0-9_-]{1,29}$")

        /** 응답 필드명 — JSON 키(식별자) 꼴, 60자 이내 (컬럼 varchar(60)). 대소문자를 구분한다 */
        val ATTR_NAME_PATTERN = Regex("^[A-Za-z_$][A-Za-z0-9_$]{0,59}$")

        /** 「응답 필드명 → 항목」 카탈로그 캐시 수명 — 쓰기가 있으면 즉시 비운다 */
        const val CACHE_TTL_MS = 60_000L

        private const val TARGET_KIND = "FIELD"
    }

    // =================================================================================
    // 항목 CRUD
    // =================================================================================

    @Transactional
    fun create(request: DataFieldSaveRequest): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.SYS_DATA)
        val key = request.fieldKey?.trim().orEmpty()
        if (!FIELD_KEY_PATTERN.matches(key)) {
            throw InvalidParameterException("항목 key 는 소문자로 시작하는 소문자·숫자·'_'·'-' 2~30자여야 합니다. [$key]", "fieldKey")
        }
        val (name, desc, category) = validateFieldBody(request)
        if (dataFieldRepository.findField(key) != null) {
            throw BusinessRuleException("이미 등록된 항목 key 입니다. [$key]")
        }

        dataFieldRepository.insertField(key, name, desc, category, dataFieldRepository.nextSortSeq(), principal.userId)
        audit(key, "데이터 항목 등록 [$key / $name] (미적용)", "데이터 항목 등록 [$key]")
        return requireField(key)
    }

    @Transactional
    fun update(fieldKey: String, request: DataFieldSaveRequest): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.SYS_DATA)
        val before = requireField(fieldKey)
        val (name, desc, category) = validateFieldBody(request)

        dataFieldRepository.updateField(fieldKey, name, desc, category, principal.userId)
        audit(fieldKey, "데이터 항목 수정 [$fieldKey] ${before["name"]} → $name", "데이터 항목 수정 [$fieldKey]")
        invalidate()
        return requireField(fieldKey)
    }

    @Transactional
    fun delete(fieldKey: String): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.SYS_DATA)
        val field = requireField(fieldKey)
        if (fieldKey in DataField.ALL) {
            throw BusinessRuleException(
                "기본 항목 [$fieldKey] 은 서버 판정 코드가 직접 쓰므로 삭제할 수 없습니다. 적용을 끄거나(apply) 부서 권한으로 조정하세요."
            )
        }
        val refs = dataFieldRepository.countReferences(fieldKey)
        val blocking = listOf(
            "알림 조건" to refs["alertCond"], "지표 기준" to refs["metricStd"],
            "보고서 양식 필드" to refs["reportFormField"], "문서 태그" to refs["docTag"]
        ).filter { (it.second ?: 0L) > 0L }
        if (blocking.isNotEmpty()) {
            throw BusinessRuleException(
                "항목 [$fieldKey] 을 참조하는 데이터가 있어 삭제할 수 없습니다 — " +
                    blocking.joinToString(" · ") { "${it.first} ${it.second}건" } + ". 참조를 먼저 정리하거나 적용을 끄세요."
            )
        }

        dataFieldRepository.deleteField(fieldKey)
        audit(
            fieldKey,
            "데이터 항목 삭제 [$fieldKey / ${field["name"]}] 부서 권한 ${refs["deptPerm"]}건 · 응답 필드명 ${refs["attr"]}건 함께 삭제",
            "데이터 항목 삭제 [$fieldKey]"
        )
        invalidate()
        return mapOf("success" to true, "fieldKey" to fieldKey, "deletedDeptPerms" to refs["deptPerm"], "deletedAttrs" to refs["attr"])
    }

    // =================================================================================
    // 응답 필드명
    // =================================================================================

    @Transactional
    fun addAttr(fieldKey: String, request: DataFieldAttrRequest): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.SYS_DATA)
        val field = requireField(fieldKey)
        val attrName = request.attrName?.trim().orEmpty()
        if (!ATTR_NAME_PATTERN.matches(attrName)) {
            throw InvalidParameterException("응답 필드명은 영문자·'_'·'$' 로 시작하는 JSON 키 꼴 60자 이내여야 합니다. [$attrName]", "attrName")
        }
        val remark = request.remark?.trim()?.takeIf { it.isNotBlank() }
        if (remark != null && remark.length > 200) throw InvalidParameterException("메모는 200자 이내여야 합니다.", "remark")

        // 등록 전에 먼저 보고, 동시 등록으로 UNIQUE 에 걸리면 다시 보고 같은 409 를 낸다 — 500 이 나가면 관리자가 이유를 모른다.
        dataFieldRepository.findAttrOwner(attrName)?.let { throw duplicatedAttr(attrName, it, fieldKey) }
        try {
            dataFieldRepository.insertAttr(fieldKey, attrName, remark, principal.userId)
        } catch (e: DuplicateKeyException) {
            val owner = dataFieldRepository.findAttrOwner(attrName) ?: mapOf("fieldKey" to fieldKey, "fieldNm" to field["name"])
            throw duplicatedAttr(attrName, owner, fieldKey)
        }

        audit(fieldKey, "응답 필드명 등록 [$fieldKey] + $attrName", "응답 필드명 등록 [$fieldKey / $attrName]")
        invalidate()
        return mapOf("fieldKey" to fieldKey, "attrName" to attrName, "attrs" to attrsOf(fieldKey))
    }

    @Transactional
    fun removeAttr(fieldKey: String, attrName: String): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.SYS_DATA)
        requireField(fieldKey)
        if (dataFieldRepository.deleteAttr(fieldKey, attrName) == 0) {
            throw ResourceNotFoundException("항목 [$fieldKey] 에 등록된 응답 필드명이 아닙니다. [$attrName]")
        }
        audit(fieldKey, "응답 필드명 해제 [$fieldKey] - $attrName", "응답 필드명 해제 [$fieldKey / $attrName]")
        invalidate()
        return mapOf("fieldKey" to fieldKey, "attrName" to attrName, "attrs" to attrsOf(fieldKey))
    }

    // =================================================================================
    // 2단계 스위치
    // =================================================================================

    @Transactional
    fun setApply(fieldKey: String, on: Boolean): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.SYS_DATA)
        val field = requireField(fieldKey)
        val changed = field["applyFlg"] != (if (on) "Y" else "N")
        if (changed) {
            dataFieldRepository.updateApplyFlg(fieldKey, on, principal.userId)
            audit(
                fieldKey,
                "데이터 항목 적용 ${if (on) "ON" else "OFF"} [$fieldKey / ${field["name"]}]",
                "데이터 항목 적용 ${if (on) "ON" else "OFF"} [$fieldKey]"
            )
            invalidate()
        }
        return mapOf("fieldKey" to fieldKey, "applyFlg" to if (on) "Y" else "N", "changed" to changed)
    }

    // =================================================================================
    // 마스킹 판정 카탈로그 — 적용 중 항목만
    // =================================================================================

    @Volatile
    private var attrCache: Pair<Long, Map<String, String>>? = null

    /** 「응답 필드명 → 항목 key」(적용 중 항목만). 쓰기 뒤에는 바로, 그 밖에는 [CACHE_TTL_MS] 마다 새로 읽는다. */
    fun attrFieldMap(): Map<String, String> {
        val now = System.currentTimeMillis()
        attrCache?.let { (at, map) -> if (now - at < CACHE_TTL_MS) return map }
        val fresh = dataFieldRepository.findAppliedAttrMap()
        attrCache = now to fresh
        return fresh
    }

    /** 응답 필드명이 속한 항목 key — 등록되지 않았거나 항목이 미적용이면 null */
    fun fieldOf(attrName: String): String? = attrFieldMap()[attrName]

    /** 표 블록 열 이름마다 항목 key — `block.blindColumns` 그대로. 항목이 없는 열은 null */
    fun blindColumnsFor(columns: List<String>): List<String?> {
        val map = attrFieldMap()
        return columns.map { map[it] }
    }

    /** 이 사용자가 열람할 수 없는 적용 중 항목 key — RAG 문서 제외 · 답변 문장 차단 판정용 */
    fun blindKeysFor(principal: UserPrincipal): Set<String> {
        if (principal.superAdmin) return emptySet()
        return attrFieldMap().values.toSet().filterNot { principal.canReadField(it) }.toSet()
    }

    /**
     * 표 블록 행에서 권한 없는 열의 값을 null 로 바꾼다.
     *
     * @return 가린 칸 수 (감사 로그 blind_applied_cnt)
     */
    fun maskRows(rows: List<MutableMap<String, Any?>>, columns: List<String>, blindColumns: List<String?>, principal: UserPrincipal): Int {
        var masked = 0
        columns.forEachIndexed { i, col ->
            val key = blindColumns.getOrNull(i) ?: return@forEachIndexed
            if (principal.canReadField(key)) return@forEachIndexed
            rows.forEach { row -> if (row[col] != null) { row[col] = null; masked++ } }
        }
        return masked
    }

    /**
     * 질의 문장이 이 사용자가 열람할 수 없는 적용 중 항목을 묻는지 판정한다 — 답변 문장 차단용.
     *
     * 키워드 = 항목명 전체와 항목명을 `·` `/` 로 나눈 조각(2자 이상) + 응답 필드명(대소문자 무시).
     * 예) 「단가·금액」 → 단가, 금액 · 「작업자 정보」 → 작업자 정보 · attrs unitPrice → "unitprice".
     * 공백으로는 나누지 않는다 — 「정보」「항목」 같은 일반어가 키워드가 되면 무관한 질의까지 막힌다.
     *
     * @return 차단할 항목 key (없으면 null)
     */
    fun restrictedFieldFor(question: String, principal: UserPrincipal): String? {
        if (principal.superAdmin) return null
        val lowered = question.lowercase()
        return appliedFieldsCached().firstOrNull { f ->
            val key = f["key"] as String
            if (principal.canReadField(key)) return@firstOrNull false
            val name = (f["name"] as? String).orEmpty().trim()
            val nameParts = (listOf(name) + name.split('·', '/')).map { it.trim() }.filter { it.length >= 2 }
            @Suppress("UNCHECKED_CAST")
            val attrs = (f["attrs"] as? List<String>).orEmpty()
            nameParts.any { question.contains(it) } || attrs.any { it.length >= 2 && lowered.contains(it.lowercase()) }
        }?.get("key") as String?
    }

    @Volatile
    private var fieldsCache: Pair<Long, List<Map<String, Any?>>>? = null

    /** 적용 중 항목(이름·분류·필드명) — [CACHE_TTL_MS] 캐시 */
    fun appliedFieldsCached(): List<Map<String, Any?>> {
        val now = System.currentTimeMillis()
        fieldsCache?.let { (at, list) -> if (now - at < CACHE_TTL_MS) return list }
        val fresh = dataFieldRepository.findAppliedFields()
        fieldsCache = now to fresh
        return fresh
    }

    /** 카탈로그를 비운다 — 항목·필드명·적용 스위치가 바뀐 뒤 */
    fun invalidate() { attrCache = null; fieldsCache = null }

    // ---------------------------------------------------------------------------------
    // 내부
    // ---------------------------------------------------------------------------------

    private fun validateFieldBody(request: DataFieldSaveRequest): Triple<String, String?, String?> {
        val name = request.name?.trim().orEmpty()
        if (name.isBlank()) throw InvalidParameterException("항목명을 입력해 주세요.", "name")
        if (name.length > 50) throw InvalidParameterException("항목명은 50자 이내여야 합니다.", "name")
        val desc = request.desc?.trim()?.takeIf { it.isNotBlank() }
        if (desc != null && desc.length > 300) throw InvalidParameterException("설명은 300자 이내여야 합니다.", "desc")
        val category = request.category?.trim()?.takeIf { it.isNotBlank() }
        codeValidator.require(DataFieldRepository.CATEGORY_GROUP, category, "category", "항목 분류")
        return Triple(name, desc, category)
    }

    private fun requireField(fieldKey: String): Map<String, Any?> =
        dataFieldRepository.findField(fieldKey)
            ?: throw ResourceNotFoundException("데이터 항목을 찾을 수 없습니다. [$fieldKey]")

    /** 이 항목의 응답 필드명 전체(적용 여부 무관) — 목록 API 와 같은 원천 */
    private fun attrsOf(fieldKey: String): List<String> = dataFieldRepository.findFieldAttrs(fieldKey)

    private fun duplicatedAttr(attrName: String, owner: Map<String, Any?>, fieldKey: String): BusinessRuleException =
        if (owner["fieldKey"] == fieldKey) {
            BusinessRuleException("이미 이 항목에 등록된 필드명입니다. [$attrName]")
        } else {
            BusinessRuleException("이미 ${owner["fieldNm"]}에 등록된 필드명입니다. [$attrName]")
        }

    private fun audit(fieldKey: String, permDetail: String, targetDesc: String) {
        auditLogService.recordPermChange(actCd = "DATA_PERM", targetKindCd = TARGET_KIND, targetNm = fieldKey, detail = permDetail)
        auditLogService.record(logType = "PERM_CHANGE", menuId = MenuId.SYS_DATA, fieldKey = fieldKey, targetDesc = targetDesc)
    }
}
