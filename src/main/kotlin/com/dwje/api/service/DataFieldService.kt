package com.dwje.api.service

import com.dwje.api.common.util.AuditType
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
 *   AI 답변 표 블록의 blindColumns 판정에 쓰이며, **모든 API 응답의 같은 이름 키**도 가려진다
 *   ([com.dwje.api.common.response.DataFieldMaskingAdvice], 2026-09-23). 화면 표시는 그 화면을 다시 열 때,
 *   서버 판정은 다음 요청부터(카탈로그는 쓰기 즉시 · DB 직접 수정은 [CACHE_TTL_MS] 안에) 따라온다.
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

        /** 문서 제목 맨 앞 대괄호 표기 — 고객사·사업부 */
        private val LEADING_BRACKET = Regex("^\\s*\\[[^\\]]{1,60}\\]")

        /**
         * 값 토막 — (1) 숫자: 천 단위 콤마 · 소수 · 바로(또는 한 칸 뒤에) 붙는 단위(%, 원, 개, EA, 건, kg, mm, 장, 매, 톤)
         *          (2) 코드형 식별자: 영문 1~6자 + 숫자(하이픈 구간 포함) — L260824-031 · PR-03 · W-1023
         * 일반 한글 낱말은 값 후보에 넣지 않는다 — 라벨 뒤의 멀쩡한 말까지 집어삼켜 문장이 망가진다. 문장 끝 마침표는 먹지 않는다
         */
        /**
         * 가릴 수 없는 응답 필드명 (04 DTP-01) — 로그인·권한 판정·공통 응답 틀이 쓰는 키다.
         * 이 이름을 항목에 붙이면 공통 마스킹([com.dwje.api.common.response.DataFieldMaskingAdvice])이 화면 메뉴·사용자 이름까지 지워
         * 화면이 깨지므로 등록 단계에서 막는다.
         */
        val RESERVED_ATTRS: Set<String> = sortedSetOf(
            // 로그인·권한
            "empNo", "name", "dept", "deptId", "deptNm", "pos", "superAdmin", "menuPerms", "dataPerms",
            "blindFields", "dataFields", "attrs", "impersonated", "servingModelVer", "userId",
            // 권한 관리 응답
            "id", "key", "group", "groupId", "label", "screens", "depts", "matrix", "fields", "applyFlg", "category",
            "categoryNm", "sortSeq", "desc", "userCnt", "menuCnt", "dataCnt",
            // 공통 응답·식별
            "items", "meta", "masked", "success", "message", "code", "ts", "title", "target", "detail", "by", "state", "status"
        )

        /** 매핑 저장 한 번에 옮길 수 있는 열 수 */
        const val MAPPING_MAX = 100

        const val VALUE_PATTERN = "(?:[0-9]+(?:[,.][0-9]+)*(?: ?(?:%|원|개|ea|건|kg|mm|㎜|장|매|톤))?|[A-Za-z]{1,6}-?[0-9][A-Za-z0-9]*(?:-[A-Za-z0-9]+)*)(?![A-Za-z0-9])"
    }

    // =================================================================================
    // 항목 CRUD
    // =================================================================================

    @Transactional
    fun create(request: DataFieldSaveRequest): Map<String, Any?> {
        val principal = authorizationService.requireWrite(MenuId.SYS_DATA)
        val key = request.fieldKey?.trim().orEmpty()
        if (!FIELD_KEY_PATTERN.matches(key)) {
            throw InvalidParameterException("항목 key 는 소문자로 시작하는 소문자·숫자·'_'·'-' 2~30자여야 합니다. [$key]", "fieldKey")
        }
        // `mapping` 은 일괄 저장 경로(PUT /data-fields/mapping)와 겹쳐 그 항목을 고칠 수 없게 되므로 쓰지 않는다
        if (key == "mapping") throw InvalidParameterException("mapping 은 항목 key 로 쓸 수 없습니다.", "fieldKey")
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
        val principal = authorizationService.requireWrite(MenuId.SYS_DATA)
        val before = requireField(fieldKey)
        val (name, desc, category) = validateFieldBody(request)

        // 바뀐 칸만 기록하고, 바뀐 것이 없으면 저장·기록하지 않는다 (04 DTP-11). 설명 원문은 길어 넣지 않는다
        val changes = buildList {
            if (before["name"] != name) add("이름 ${before["name"]} → $name")
            if ((before["desc"] as String?) != desc) add("설명 변경")
            if ((before["category"] as String?) != category) add("분류 ${before["category"] ?: "없음"} → ${category ?: "없음"}")
        }
        if (changes.isEmpty()) return before + ("changed" to false)
        dataFieldRepository.updateField(fieldKey, name, desc, category, principal.userId)
        audit(fieldKey, "데이터 항목 수정 [$fieldKey / $name] ${changes.joinToString(", ")}", "데이터 항목 수정 [$fieldKey]")
        invalidate()
        return requireField(fieldKey) + ("changed" to true)
    }

    @Transactional
    fun delete(fieldKey: String): Map<String, Any?> {
        authorizationService.requireWrite(MenuId.SYS_DATA)
        val field = requireField(fieldKey)
        if (fieldKey in DataField.ALL) {
            throw BusinessRuleException(
                "기본 항목 [$fieldKey] 은 서버 판정 코드가 직접 쓰므로 삭제할 수 없습니다. 부서 권한으로 조정하세요."
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
        val principal = authorizationService.requireWrite(MenuId.SYS_DATA)
        val field = requireField(fieldKey)
        val attrName = request.attrName?.trim().orEmpty()
        if (!ATTR_NAME_PATTERN.matches(attrName)) {
            throw InvalidParameterException("응답 필드명은 영문자·'_'·'$' 로 시작하는 JSON 키 꼴 60자 이내여야 합니다. [$attrName]", "attrName")
        }
        assertNotReserved(attrName)
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
        authorizationService.requireWrite(MenuId.SYS_DATA)
        requireField(fieldKey)
        if (dataFieldRepository.deleteAttr(fieldKey, attrName) == 0) {
            throw ResourceNotFoundException("항목 [$fieldKey] 에 등록된 응답 필드명이 아닙니다. [$attrName]")
        }
        audit(fieldKey, "응답 필드명 해제 [$fieldKey] - $attrName", "응답 필드명 해제 [$fieldKey / $attrName]")
        invalidate()
        return mapOf("fieldKey" to fieldKey, "attrName" to attrName, "attrs" to attrsOf(fieldKey))
    }

    /**
     * 화면 열 매핑 일괄 저장 (04 DTP-02) — 새 종류 · 이동 · 해제 · 적용을 한 트랜잭션으로.
     *
     * 검증을 저장 전에 전부 끝낸다(하나라도 틀리면 아무것도 바뀌지 않는다). 개별 API(create·addAttr)를 부르지 않는다 —
     * 각자 이력을 남기므로, 여기서는 저장소를 직접 쓰고 이력은 한 줄로 남긴다.
     */
    @Transactional
    fun saveMapping(request: com.dwje.api.model.request.DataFieldMappingRequest, unassignedDeptName: String): Map<String, Any?> {
        val principal = authorizationService.requireWrite(MenuId.SYS_DATA)
        val moves = request.moves.orEmpty()
        val newFields = request.newFields.orEmpty()

        // ---- 검증(저장 전) ----
        if (moves.size > MAPPING_MAX) throw InvalidParameterException("한 번에 ${MAPPING_MAX}개 열까지 저장할 수 있습니다.", "moves")
        val names = moves.map { it.attrName?.trim().orEmpty() }
        names.groupBy { it }.filter { it.value.size > 1 }.keys.firstOrNull()?.let {
            throw InvalidParameterException("같은 필드명이 두 번 들어 있습니다. [$it]", "attrName")
        }
        names.forEach {
            if (!ATTR_NAME_PATTERN.matches(it)) {
                throw InvalidParameterException("응답 필드명은 영문자·'_'·'$' 로 시작하는 JSON 키 꼴 60자 이내여야 합니다. [$it]", "attrName")
            }
            assertNotReserved(it)
        }
        moves.forEach { m ->
            m.remark?.trim()?.let { if (it.length > 200) throw InvalidParameterException("메모는 200자 이내여야 합니다.", "remark") }
        }
        val newKeys = newFields.map { nf ->
            val key = nf.fieldKey?.trim().orEmpty()
            if (!FIELD_KEY_PATTERN.matches(key)) {
                throw InvalidParameterException("항목 key 는 소문자로 시작하는 소문자·숫자·'_'·'-' 2~30자여야 합니다. [$key]", "fieldKey")
            }
            validateFieldBody(DataFieldSaveRequest(key, nf.name, nf.desc, nf.category))
            if (dataFieldRepository.findField(key) != null) throw BusinessRuleException("이미 등록된 항목 key 입니다. [$key]")
            key
        }
        newKeys.groupBy { it }.filter { it.value.size > 1 }.keys.firstOrNull()?.let {
            throw InvalidParameterException("같은 항목 key 가 두 번 들어 있습니다. [$it]", "fieldKey")
        }
        val targets = moves.mapNotNull { it.toFieldKey?.trim() }.distinct()
        val targetFields = targets.associateWith { key ->
            if (key in newKeys) null
            else dataFieldRepository.findField(key) ?: throw ResourceNotFoundException("데이터 항목을 찾을 수 없습니다. [$key]")
        }

        // ---- 저장 ----
        newFields.forEach { nf ->
            val key = nf.fieldKey!!.trim()
            val (name, desc, category) = validateFieldBody(DataFieldSaveRequest(key, nf.name, nf.desc, nf.category))
            dataFieldRepository.insertField(key, name, desc, category, dataFieldRepository.nextSortSeq(), principal.userId)
            if (nf.grantAllDepts) dataFieldRepository.grantFieldToDepts(key, unassignedDeptName, principal.userId)
        }
        val moved = mutableListOf<Map<String, Any?>>()
        val released = mutableListOf<Map<String, Any?>>()
        moves.forEachIndexed { i, m ->
            val attrName = names[i]
            val from = dataFieldRepository.findAttrOwner(attrName)?.get("fieldKey") as String?
            val to = m.toFieldKey?.trim()
            val remark = m.remark?.trim()?.ifEmpty { null }
            when {
                to == null -> if (from != null) {
                    dataFieldRepository.releaseAttr(attrName)
                    released += mapOf("attrName" to attrName, "from" to from)
                }
                to == from -> if (remark != null) dataFieldRepository.moveAttr(attrName, to, remark, principal.userId)
                else -> {
                    dataFieldRepository.moveAttr(attrName, to, remark, principal.userId)
                    moved += mapOf("attrName" to attrName, "from" to from, "to" to to)
                }
            }
        }
        val applied = newFields.filter { it.apply }.map { it.fieldKey!!.trim() }
        applied.forEach { dataFieldRepository.updateApplyFlg(it, true, principal.userId) }
        val notApplied = targets.filter { key ->
            if (key in newKeys) key !in applied else targetFields[key]?.get("applyFlg") != "Y"
        }

        val where = request.screenId?.trim()?.ifEmpty { null } ?: "매핑"
        // 새 종류는 key 와 이름을 함께 남긴다 — 자동 key(f_…)만으로는 무엇인지 알 수 없다 (04 DTP-11)
        val newNames = newFields.filter { it.fieldKey?.trim() in newKeys }.joinToString(", ") { "${it.fieldKey?.trim()} / ${it.name?.trim()}" }
        val summary = ("새 종류 ${newKeys.size} · 이동 ${moved.size} · 해제 ${released.size} · 적용 ${applied.size}" +
            (if (newNames.isNotEmpty()) " — 새 종류: $newNames" else "")).take(480)
        val permAuditId = auditLogService.record(logType = AuditType.PERM_CHANGE, menuId = MenuId.SYS_DATA, targetDesc = "데이터 항목 매핑 저장 [$where]", remark = summary)
        auditLogService.recordPermChange(
            actCd = "DATA_PERM", targetKindCd = TARGET_KIND, targetNm = where, detail = "매핑 저장 [$where] $summary",
            auditId = permAuditId
        )
        invalidate()

        return mapOf(
            "created" to newKeys, "moved" to moved, "released" to released,
            "applied" to applied, "notApplied" to notApplied
        )
    }

    // =================================================================================
    // 2단계 스위치
    // =================================================================================

    @Transactional
    fun setApply(fieldKey: String, on: Boolean): Map<String, Any?> {
        val principal = authorizationService.requireWrite(MenuId.SYS_DATA)
        val field = requireField(fieldKey)
        // 기본 7종은 서버 판정 코드가 key 를 직접 쓰므로 끄지 않는다 — 열람 범위는 부서 권한으로 조정한다(04 DTP-05)
        if (!on && fieldKey in DataField.ALL) {
            throw BusinessRuleException("기본 항목은 서버 판정 코드가 직접 쓰므로 적용을 끌 수 없습니다. 부서 권한으로 조정하세요.")
        }
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

    /**
     * 조회자가 열람할 수 없는 적용 중 항목의 **응답 필드명** — 통합관리자는 빈 집합.
     * 응답 키는 공통 advice 가 가리지만, sLLM 입력·근거 이름표처럼 **값을 문장에 옮기는 자리**는
     * 키로 가릴 수 없어 이 목록으로 값을 뺀다(2026-09-23, 운영 중 추가한 설비명이 브리핑 문장에 나온 건).
     */
    fun blindAttrNames(principal: UserPrincipal): Set<String> =
        if (principal.superAdmin) emptySet()
        else attrFieldMap().filterValues { !principal.canReadField(it) }.keys

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
     * 값을 찾는 규칙 두 가지. 라벨(항목 키워드)은 **항목 표에서** 뽑는다 — 고정 목록이 없어 새 항목도 배포 없이 걸린다.
     * 1. 항목 키워드(항목명 전체 · `·`/`,`/`()` 조각 · 응답 필드명) 뒤 24자 안에 오는 값 토막([VALUE_PATTERN] — 숫자+단위 또는 코드형 식별자)
     *    예) 「8월 평균 단가는 12,400원입니다」 → 「8월 평균 단가는 비공개입니다」, 「작업자 사번 W-1023 배치」 → 「작업자 사번 비공개 배치」
     * 2. [maskRows] 가 표에서 가린 값 그대로(2자 이상) — 표의 값이 문장에 풀어 쓰인 경우. 값이 라벨보다 앞에 오는 문장도 이 규칙으로는 가려진다
     * HTML 태그(`<…>`)는 넘지 않는다. 일반 한글 낱말 값(고객사명·작업자명)은 1번으로는 못 찾고 2번으로만 가려진다.
     * WEB(f6bb760 · 9ca5bf8)의 문장 필터와 같은 원칙이다 — 라벨 후보 · 값 토막 · 「라벨은 남기고 값만」.
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
            // 값 토막은 앞이 영문·숫자·'-' 가 아닌 자리에서 시작한다(식별자 한가운데의 숫자를 값으로 보지 않는다)
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
        // 고객사 권한이 없으면 제목·소제목·발췌의 고객사 이름을 가린다 — 「[Cowell] …」 처럼 제목에 그대로 나왔다 (2026-10-03)
        if (!principal.canReadField(com.dwje.api.common.util.DataField.CUSTOMER)) {
            listOf("title", "heading", "snippet").forEach { key ->
                val (out, c) = maskCustomerNames(hit[key] as? String)
                if (c > 0) { hit[key] = out; cnt += c }
            }
        }
        return cnt
    }

    /**
     * 고객사 이름 가림 — 맨 앞 대괄호 표기는 통째로 `[비공개]`, 본문 속 이름은 `비공개`.
     * 이름 앞뒤가 영문자면 다른 낱말의 일부로 보고 두지 않는다(대소문자 무시).
     *
     * @return 가린 문장과 가린 곳 수
     */
    fun maskCustomerNames(text: String?): Pair<String?, Int> {
        if (text.isNullOrEmpty()) return text to 0
        var cnt = 0
        var out: String = LEADING_BRACKET.replace(text) { cnt++; "[$MASK]" }
        customerNamePattern()?.let { re -> out = re.replace(out) { cnt++; MASK } }
        return out to cnt
    }

    @Volatile
    private var customerCache: Pair<Long, Regex?>? = null

    /** 고객사 이름 정규식 — 이름과 그 첫 낱말(3자 이상 영문, 「LGIT CM」 → 「LGIT」). [CACHE_TTL_MS] 캐시 */
    private fun customerNamePattern(): Regex? {
        val now = System.currentTimeMillis()
        customerCache?.let { (at, re) -> if (now - at < CACHE_TTL_MS) return re }
        val names = runCatching { dataFieldRepository.findCustomerNames() }.getOrDefault(emptyList())
            .flatMap { n -> listOf(n.trim()) + n.trim().split(Regex("\\s+")).first().takeIf { it.length >= 3 && it.all { c -> c.isLetter() } }.let(::listOfNotNull) }
            .filter { it.length >= 2 }.distinct().sortedByDescending { it.length }
        val re = names.takeIf { it.isNotEmpty() }?.let { list ->
            Regex("(?<![A-Za-z])(" + list.joinToString("|") { Regex.escape(it) } + ")(?![A-Za-z])", RegexOption.IGNORE_CASE)
        }
        customerCache = now to re
        return re
    }

    /** 이 사용자가 열람할 수 없는 적용 중 항목의 키워드 — 항목명 전체 · `·`/`,`/`()` 조각(2자 이상) · 응답 필드명. 공백으로는 나누지 않는다. 긴 것부터 */
    internal fun keywordsOfBlindFields(principal: UserPrincipal): List<String> {
        if (principal.superAdmin) return emptyList()
        return appliedFieldsCached().filterNot { principal.canReadField(it["key"] as String) }.flatMap { f ->
            val name = (f["name"] as? String).orEmpty().trim()
            @Suppress("UNCHECKED_CAST")
            val attrs = (f["attrs"] as? List<String>).orEmpty()
            (listOf(name) + name.split('·', '/', ',', '(', ')') + attrs).map { it.trim() }.filter { it.length >= 2 }
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

    /** 예약어 필드명은 가릴 수 없다 (409, 04 DTP-01) */
    private fun assertNotReserved(attrName: String) {
        if (attrName in RESERVED_ATTRS) throw BusinessRuleException("시스템이 쓰는 필드명이라 가릴 수 없습니다. [$attrName]")
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
        val permAuditId = auditLogService.record(logType = AuditType.PERM_CHANGE, menuId = MenuId.SYS_DATA, fieldKey = fieldKey, targetDesc = targetDesc)
        auditLogService.recordPermChange(actCd = "DATA_PERM", targetKindCd = TARGET_KIND, targetNm = fieldKey, detail = permDetail, auditId = permAuditId)
    }
}
