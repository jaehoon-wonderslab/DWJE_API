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

        /** 문장에서 가릴 때 넣는 말 */
        const val MASK = "비공개"

        /** 숫자 값 — 천 단위 콤마 · 소수 · 바로(또는 한 칸 뒤에) 붙는 단위(%, 원, 개, EA, 건, kg, mm, 장, 매, 톤). 문장 끝 마침표는 먹지 않는다 */
        const val VALUE_PATTERN = "[0-9]+(?:[,.][0-9]+)*(?: ?(?:%|원|개|ea|건|kg|mm|㎜|장|매|톤))?"
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
     * @param maskedValues 가린 값의 문자열을 모아 준다(있으면). 답변 문장에 같은 값이 풀어 쓰여 있으면 [maskText] 가 그것도 가린다
     * @return 가린 칸 수 (감사 로그 blind_applied_cnt)
     */
    fun maskRows(
        rows: List<MutableMap<String, Any?>>,
        columns: List<String>,
        blindColumns: List<String?>,
        principal: UserPrincipal,
        maskedValues: MutableCollection<String>? = null
    ): Int {
        var masked = 0
        columns.forEachIndexed { i, col ->
            val key = blindColumns.getOrNull(i) ?: return@forEachIndexed
            if (principal.canReadField(key)) return@forEachIndexed
            rows.forEach { row ->
                val v = row[col] ?: return@forEach
                maskedValues?.add(v.toString())
                row[col] = null; masked++
            }
        }
        return masked
    }

    /**
     * 자연어 문장 안의 권한 없는 값만 「비공개」로 바꾼다 — 문장 구조는 살린다.
     *
     * 값을 찾는 규칙 두 가지.
     * 1. 항목 키워드(항목명 전체 · `·`/`/` 조각 · 응답 필드명) 뒤 24자 안에 오는 숫자 값(천 단위 콤마·소수·단위 포함)
     *    예) 「8월 평균 단가는 12,400원입니다」 → 「8월 평균 단가는 비공개입니다」
     * 2. [maskRows] 가 표에서 가린 값 그대로(2자 이상) — 표의 값이 문장에 풀어 쓰인 경우
     * HTML 태그(`<…>`)는 넘지 않는다. 숫자가 아닌 값(고객사명·작업자명)은 1번으로는 못 찾고 2번으로만 가려진다.
     *
     * @return 가린 문장 · 치환 수
     */
    fun maskText(text: String?, principal: UserPrincipal, extraValues: Collection<String> = emptyList()): Pair<String?, Int> {
        if (text.isNullOrEmpty() || principal.superAdmin) return text to 0
        var out = text; var cnt = 0
        // 표에서 가린 값(정확히 일치)을 먼저 — 키워드 규칙이 식별자 속 숫자(MDL-77 의 77)를 먼저 먹지 않게
        extraValues.filter { it.trim().length >= 2 }.distinct().sortedByDescending { it.length }.forEach { v ->
            val re = Regex(Regex.escape(v.trim()))
            out = re.replace(out!!) { cnt++; MASK }
        }
        keywordsOfBlindFields(principal).forEach { kw ->
            // 숫자 앞이 영문·숫자·'-' 면 식별자(모델 코드)의 일부라 값으로 보지 않는다
            val re = Regex("(${Regex.escape(kw)})([^0-9<>]{0,24}?)(?<![A-Za-z0-9-])(${VALUE_PATTERN})", RegexOption.IGNORE_CASE)
            out = re.replace(out!!) { m -> cnt++; m.groupValues[1] + m.groupValues[2] + MASK }
        }
        return out to cnt
    }

    /**
     * 근거 문서 한 건의 출력 마스킹.
     *
     * 문서에 권한 없는 항목이 태그돼 있으면(`fieldTags` ∩ 사용자가 못 보는 항목) 발췌를 통째로 가린다 —
     * 발췌는 원문이라 값이 숫자가 아니어도(고객사명 등) 새기 때문이다. 제목·쪽·날짜는 남겨 근거 인용은 유지한다.
     * 태그가 없는 문서는 발췌·제목에 [maskText] 만 태운다. 검색에서 문서를 빼지는 않는다(빼면 답이 틀려진다).
     *
     * @return 가린 항목 수(발췌 1 + 문장 치환 수)
     */
    @Suppress("UNCHECKED_CAST")
    fun maskHit(hit: MutableMap<String, Any?>, principal: UserPrincipal): Int {
        if (principal.superAdmin) return 0
        val blind = blindKeysFor(principal)
        val tags = (hit["fieldTags"] as? List<String>).orEmpty().filter { it in blind }
        var cnt = 0
        if (tags.isNotEmpty()) {
            val names = appliedFieldsCached().filter { it["key"] in tags }.map { it["name"] as String }
            hit["snippet"] = "비공개 항목(${names.joinToString(" · ")})이 포함된 자료입니다. 발췌는 표시하지 않습니다."
            hit["blindTags"] = tags; hit["blinded"] = true; cnt++
        } else {
            val (s, c1) = maskText(hit["snippet"] as? String, principal); hit["snippet"] = s; cnt += c1
            hit["blinded"] = c1 > 0
        }
        val (title, c2) = maskText(hit["title"] as? String, principal); hit["title"] = title; cnt += c2
        val (heading, c3) = maskText(hit["heading"] as? String, principal); hit["heading"] = heading; cnt += c3
        return cnt
    }

    /** 이 사용자가 열람할 수 없는 적용 중 항목의 키워드 — 항목명 전체 · `·`/`/` 조각(2자 이상) · 응답 필드명. 긴 것부터 */
    internal fun keywordsOfBlindFields(principal: UserPrincipal): List<String> {
        if (principal.superAdmin) return emptyList()
        return appliedFieldsCached().filterNot { principal.canReadField(it["key"] as String) }.flatMap { f ->
            val name = (f["name"] as? String).orEmpty().trim()
            @Suppress("UNCHECKED_CAST")
            val attrs = (f["attrs"] as? List<String>).orEmpty()
            (listOf(name) + name.split('·', '/') + attrs).map { it.trim() }.filter { it.length >= 2 }
        }.distinct().sortedByDescending { it.length }
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
