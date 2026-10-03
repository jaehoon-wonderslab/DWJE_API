package com.dwje.api.service

import com.dwje.api.common.exception.BusinessRuleException
import com.dwje.api.common.exception.ConflictingValueException
import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.exception.ResourceNotFoundException
import com.dwje.api.common.response.PageMeta
import com.dwje.api.common.util.MenuId
import com.dwje.api.common.util.PageRequestParam
import com.dwje.api.model.request.GlossaryExportRequest
import com.dwje.api.model.request.GlossaryNormalizeRequest
import com.dwje.api.repository.GlossaryRepository
import com.dwje.api.repository.VectorIndexRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 용어 사전 관리 서비스 (SY-06)
 *
 * 공식 용어는 **통합관리자만** 관리하고(07 GLS-01), 현장 유사어는 `sys-gloss` 쓰기 권한자가 본인 등록 건에 한해 관리한다.
 *
 * 접근 : 조회(요약·분류·목록·상세·내려받기)는 `sys-gloss` 또는 `gloss-view`(용어 사전 조회, 13 GL-01),
 *        쓰기·미리보기·점검은 `sys-gloss` · 값 마스킹 : 없음 (gloss-view 만 가진 사람에게는 등록자 사번·부서·관리 지표를 주지 않는다)
 */
@Service
class GlossaryService(
    private val glossaryRepository: GlossaryRepository,
    private val glossaryNormalizer: GlossaryNormalizer,
    private val vectorIndexRepository: VectorIndexRepository,
    private val authorizationService: AuthorizationService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 감사 기록기 — 서비스를 직접 만드는 단위 시험에서는 없다 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    var auditLogService: AuditLogService? = null

    companion object {
        /** 용어 사전 내려받기 상한 (공통 D-29) */
        const val EXPORT_MAX = 5_000
        private const val TERM_MAX = 50
        private const val DEFINITION_MAX = 500
        private const val VARIANT_MAX = 50

        /** 가린 용어의 표시 이름 (R-18) */
        const val BLIND_TERM = "비공개 용어"
    }

    /** 조회 API 공통 — 두 화면 중 하나의 조회 권한. 관리 화면(sys-gloss) 권한이 있는지 함께 돌려준다 (GLV-02) */
    private fun requireViewer(): Pair<com.dwje.api.common.security.UserPrincipal, Boolean> {
        val principal = authorizationService.requireAnyMenu(MenuId.SYS_GLOSS, MenuId.GLOSS_VIEW)
        return principal to principal.canAccessMenu(MenuId.SYS_GLOSS)
    }

    /** 가린 분류 — 열람자가 데이터 항목을 볼 수 없는 분류(R-18). 통합관리자는 비어 있다 */
    private data class HiddenDomains(val ids: Set<Int>, val names: Set<String>, val fieldKeys: Set<String>) {
        fun isEmpty() = ids.isEmpty()
    }

    private fun hiddenDomainsOf(principal: com.dwje.api.common.security.UserPrincipal): HiddenDomains {
        if (principal.superAdmin) return HiddenDomains(emptySet(), emptySet(), emptySet())
        val hidden = glossaryRepository.findDomainFieldKeys().filterNot { principal.canReadField(it.third) }
        return HiddenDomains(hidden.map { it.first }.toSet(), hidden.map { it.second }.toSet(), hidden.map { it.third }.toSet())
    }

    /** 가린 용어 행 — 이름은 「비공개 용어」, 뜻·유사어는 null (13 GLV-08 · 공통 11.2) */
    private fun blindTerm(row: Map<String, Any?>): Map<String, Any?> =
        row + mapOf("term" to BLIND_TERM, "definition" to null, "variants" to null, "blinded" to true)

    /** 가린 건이 있으면 MASK 감사 1행 — 대상 데이터 항목과 건수 (R-18) */
    private fun auditBlind(cnt: Int, hidden: HiddenDomains, menuId: String, target: String) {
        if (cnt == 0) return
        auditLogService?.record(
            logType = com.dwje.api.common.util.AuditType.MASK, menuId = menuId,
            fieldKey = hidden.fieldKeys.sorted().joinToString(",").take(30),
            targetDesc = target, resultCd = com.dwje.api.common.util.AuditResult.MASKED, maskedCnt = cnt
        )
    }

    /** 가린 분류의 용어는 고칠 수 없다 — 보이지 않는 대상을 고치지 않게 (409, 공통 11.2) */
    private fun requireTermVisible(principal: com.dwje.api.common.security.UserPrincipal, termId: Int) {
        val hidden = hiddenDomainsOf(principal)
        if (hidden.isEmpty()) return
        if (glossaryRepository.findTermDomainId(termId) in hidden.ids) {
            throw BusinessRuleException("데이터 접근 권한이 없는 분류의 용어라 고칠 수 없습니다.")
        }
    }

    /**
     * 유사어 목록 후처리 — 쓰기 권한이 없으면 editable 은 항상 false, 조회 화면에는 등록자 사번·부서를 주지 않는다 (GLV-02·07)
     */
    private fun shapeVariants(variants: List<Map<String, Any?>>, canWrite: Boolean, manager: Boolean): List<Map<String, Any?>> =
        variants.map { v ->
            v + mapOf("editable" to (canWrite && v["editable"] == true)) +
                (if (manager) emptyMap() else mapOf("byEmpNo" to null, "byDept" to null))
        }

    /** 용어 사전 요약 (No.170) */
    @Transactional(readOnly = true)
    fun getSummary(): Map<String, Any?> {
        val (principal, manager) = requireViewer()

        val summary = glossaryRepository.findSummary(principal.userId).toMutableMap()
        summary["byDomain"] = glossaryRepository.findCountByDomain()
        if (manager) {
            // 쓰기 권한(R-06, 07 GLS-16) — false 면 화면은 유사어 추가 버튼을 그리지 않는다
            summary["canWriteVariant"] = principal.canWriteMenu(MenuId.SYS_GLOSS)
            // 공식 용어 편집은 통합관리자만 (07 GLS-01)
            summary["canEditTerm"] = principal.superAdmin
            // 위험 유사어 점검 건수 — 점검 목록이 통합관리자 전용이라 그 외에는 null (07 GLS-03)
            summary["riskVariantCnt"] = if (principal.superAdmin) riskVariantRows().size else null
        } else {
            // 용어 사전 조회 화면 — 관리 지표와 편집 표시는 주지 않는다 (07 GLS-17)
            summary["myVariantCnt"] = null
            summary["noVariantTermCnt"] = null
            summary["canWriteVariant"] = null
            summary["canEditTerm"] = null
            summary["riskVariantCnt"] = null
        }
        // 마지막 변경 시각 — 용어·유사어 변경 이력(V60) 최신, 이력이 없으면 용어·유사어 수정 시각 최신
        summary["lastChangedAt"] = glossaryRepository.findLastChangedAt()
        // 마지막 변경 수행자 이름 — 이력(V60)이 없으면 null. 조회 화면에도 이름만 준다(사번 없음, 07 GLS-06)
        summary["lastChangedBy"] = glossaryRepository.findLastChangedBy()
        return summary.toMap()
    }

    /**
     * 용어 분류(도메인) 목록 — 용어 등록 화면의 분류 선택지
     *
     * `code` 를 [createTerm] 의 `domainCd` 에 그대로 넣을 수 있다.
     */
    @Transactional(readOnly = true)
    fun getDomains(): Map<String, Any?> {
        requireViewer()
        return mapOf("domains" to glossaryRepository.findDomains())
    }

    /**
     * 용어 목록 조회 (No.171)
     *
     * 각 용어의 유사어를 함께 반환하며, 본인 등록 유사어만 editable = true 로 표시한다.
     */
    @Transactional(readOnly = true)
    fun getTerms(
        keyword: String?,
        domainCd: String?,
        page: Int?,
        size: Int?,
        mineOnly: Boolean? = null
    ): Pair<List<Map<String, Any?>>, PageMeta> {
        val (principal, manager) = requireViewer()
        val paging = PageRequestParam.of(page, size)
        // 「내 유사어만」 은 관리 화면 호출자에게만 효과가 있다 — 조회 화면은 무시한다(07 GLS-08, 내려받기와 같은 규칙)
        val mine = if (manager && mineOnly == true) principal.userId else null
        val hidden = hiddenDomainsOf(principal)

        val total = glossaryRepository.countTerms(keyword, domainCd, mine, hidden.ids)
        val terms = glossaryRepository.findTerms(keyword, domainCd, paging.limit, paging.offset, mine, hidden.ids)

        // 유사어는 한 번에 조회해 N+1 을 피한다.
        val termIds = terms.mapNotNull { it["termId"] as? Int }
        val variants = glossaryRepository.findVariantsByTermIds(termIds, principal.userId)
        val canWrite = principal.canWriteMenu(MenuId.SYS_GLOSS)

        // 볼 수 없는 분류의 용어는 목록에서 빼지 않고 「비공개 용어」 행으로 남긴다 (R-18, 13 Q5 기본안)
        val items = terms.map {
            if (it["domainId"] in hidden.ids) blindTerm(it)
            else it + mapOf("variants" to shapeVariants(variants[it["termId"]] ?: emptyList(), canWrite, manager), "blinded" to false)
        }
        auditBlind(items.count { it["blinded"] == true }, hidden, if (manager) MenuId.SYS_GLOSS else MenuId.GLOSS_VIEW, "용어 목록 가림")

        return items to PageMeta.of(paging.page, paging.size, total)
    }

    /** 공식 용어 등록 (No.172) */
    @Transactional
    fun createTerm(term: String?, definition: String?, domainCd: String?): Map<String, Any?> {
        // 공식 용어는 통합관리자만 (07 GLS-01). 화면 권한·쓰기 권한부터 보고(E-AUTH-002·004), 그다음 통합관리자(E-AUTH-002)
        authorizationService.requireWrite(MenuId.SYS_GLOSS)
        val principal = authorizationService.requireSuperAdmin()

        val termName = term?.trim()?.takeIf { it.isNotBlank() }
            ?: throw InvalidParameterException("용어를 입력해 주세요.", "term")
        val def = definition?.trim()?.takeIf { it.isNotBlank() }
            ?: throw InvalidParameterException("용어 정의를 입력해 주세요.", "definition")
        checkTermLength(termName, def)
        val domain = domainCd?.trim()
            ?: throw InvalidParameterException("도메인을 선택해 주세요.", "domainCd")

        // 대소문자·앞뒤 공백을 무시하고 먼저 본다 — DB 의 uq_gls_term_lower 와 같은 기준이다.
        val existing = glossaryRepository.findTermByName(termName)
        if (existing?.get("active") == true) {
            throw duplicatedTerm(existing, termName)
        }

        val domainId = glossaryRepository.findDomainId(domain)
            ?: throw ResourceNotFoundException("도메인을 찾을 수 없습니다. [$domain]")

        // 삭제된 동명 용어가 있으면 되살린다.
        // tb_gls_term 은 UNIQUE (term) 이고 부분 인덱스가 아니라, 사용 중지된 행도 이름을
        // 계속 점유한다. 되살리지 않으면 한 번 지운 이름을 영구히 쓸 수 없다.
        val revivedId = existing?.get("termId") as? Int
        if (revivedId != null) {
            // 이전 유사어가 함께 돌아온다. 건수를 응답에 담아, 옛 뜻의 유사어가
            // 새 정의에 붙은 것을 모르고 지나치지 않게 한다.
            val restoredVariants = glossaryRepository.countVariantsByTerm(revivedId)
            val before = glossaryRepository.findTermSnapshot(revivedId)
            glossaryRepository.reviveTerm(revivedId, def, domainId, principal.userId)
            changeLog("TERM", "RESTORE", revivedId, null, before,
                mapOf("term" to existing["term"], "termDef" to def, "domainNm" to domain, "restoredVariants" to restoredVariants))
            log.info(
                "공식 용어 되살림 : termId={} term={} 함께 복원된 유사어={}건",
                revivedId, termName, restoredVariants
            )

            // 표기는 되살린 행의 것을 그대로 둔다. 'can' 으로 되살려도 'CAN' 이면 'CAN' 이다 —
            // 어떤 표기로 돌아왔는지 응답에 담아, 화면이 입력한 대로 보여 주고 어긋나지 않게 한다.
            return mapOf(
                "termId" to revivedId,
                "term" to existing["term"],
                "restored" to true,
                "restoredVariants" to restoredVariants
            )
        }

        // 앞에서 봤어도 그 사이 다른 요청이 같은 이름을 넣을 수 있다.
        // 유니크 위반은 500 이 아니라 위와 같은 409 로 나가야 한다.
        val termId = try {
            glossaryRepository.insertTerm(termName, def, domainId, principal.userId)
        } catch (e: DuplicateKeyException) {
            throw duplicatedTerm(null, termName)
        }
        log.info("공식 용어 등록 : termId={} term={}", termId, termName)
        changeLog("TERM", "CREATE", termId, null, null, mapOf("term" to termName, "termDef" to def, "domainNm" to domain))

        return mapOf("termId" to termId, "term" to termName)
    }

    /** 공식 용어 수정 (No.173) */
    @Transactional
    fun updateTerm(termId: Int, term: String?, definition: String?, domainCd: String?): Map<String, Any?> {
        // 공식 용어는 통합관리자만 (07 GLS-01). 화면 권한·쓰기 권한부터 보고(E-AUTH-002·004), 그다음 통합관리자(E-AUTH-002)
        authorizationService.requireWrite(MenuId.SYS_GLOSS)
        val principal = authorizationService.requireSuperAdmin()

        if (!glossaryRepository.existsTerm(termId)) {
            throw ResourceNotFoundException("용어를 찾을 수 없습니다. [termId=$termId]")
        }

        val termName = term?.trim()?.takeIf { it.isNotBlank() }
            ?: throw InvalidParameterException("용어를 입력해 주세요.", "term")
        // 뜻을 지운 수정은 받지 않는다 — 등록과 같은 문구 (07 GLS-10)
        val def = definition?.trim()?.takeIf { it.isNotBlank() }
            ?: throw InvalidParameterException("용어 정의를 입력해 주세요.", "definition")
        checkTermLength(termName, def)
        // 자기 자신은 빼고 본다 — 표기만 대문자로 바꾸는 수정(can → CAN)이 막히면 안 된다.
        val holder = glossaryRepository.findTermByName(termName)
        if (holder != null && holder["termId"] != termId) {
            // 삭제된 용어도 유니크 인덱스 때문에 이름을 계속 점유한다.
            // 목록에 안 보이는 이름이 막히는 이유를 응답에서 알 수 있게 구분해 안내한다.
            if (holder["active"] == true) {
                throw duplicatedTerm(holder, termName)
            }
            throw ConflictingValueException(
                "삭제된 용어가 이 이름을 쓰고 있어 바꿀 수 없습니다. [${holder["term"]}] " +
                    "그 용어를 되살리려면 같은 이름으로 새로 등록하세요.",
                "term"
            )
        }

        val domainId = domainCd?.let {
            glossaryRepository.findDomainId(it) ?: throw ResourceNotFoundException("도메인을 찾을 수 없습니다. [$it]")
        } ?: throw InvalidParameterException("도메인을 선택해 주세요.", "domainCd")

        val before = glossaryRepository.findTermSnapshot(termId)
        try {
            glossaryRepository.updateTerm(termId, termName, def, domainId, principal.userId)
        } catch (e: DuplicateKeyException) {
            throw duplicatedTerm(null, termName)
        }
        val after = glossaryRepository.findTermSnapshot(termId)
            ?: mapOf<String, Any?>("term" to termName, "termDef" to def, "domainNm" to domainCd)
        if (before == null || before != after) changeLog("TERM", "UPDATE", termId, null, before, after)

        return mapOf("success" to true, "term" to termName)
    }

    /** 길이 상한 — 넘으면 DB 오류(500) 대신 400 (07 4.4) */
    private fun checkTermLength(term: String, definition: String?) {
        if (term.length > TERM_MAX) throw InvalidParameterException("공식 용어는 ${TERM_MAX}자 이하로 입력해 주세요.", "term")
        if ((definition?.length ?: 0) > DEFINITION_MAX) {
            throw InvalidParameterException("뜻은 ${DEFINITION_MAX}자 이하로 입력해 주세요.", "definition")
        }
    }

    /**
     * 공식 용어 삭제 — 통합관리자만 (07 GLS-01)
     *
     * 사용 중지(`use_flg='N'`)로 처리한다. 목록·요약·정규화 사전에서 함께 빠지고,
     * 그 용어에 달린 유사어도 사전 조회에서 같이 빠진다.
     * 몇 건이 함께 빠지는지 응답에 담아, 남의 유사어를 모르고 없애는 일이 없게 한다.
     *
     * 없는 용어는 404 다.
     */
    @Transactional
    fun deleteTerm(termId: Int): Map<String, Any?> {
        // 공식 용어는 통합관리자만 (07 GLS-01). 화면 권한·쓰기 권한부터 보고(E-AUTH-002·004), 그다음 통합관리자(E-AUTH-002)
        authorizationService.requireWrite(MenuId.SYS_GLOSS)
        val principal = authorizationService.requireSuperAdmin()

        val owner = glossaryRepository.findTermOwner(termId)
            ?: throw ResourceNotFoundException("용어를 찾을 수 없습니다. [termId=$termId]")
        if (owner["active"] != true) {
            throw ResourceNotFoundException("이미 삭제된 용어입니다. [termId=$termId]")
        }

        val variantCnt = glossaryRepository.countVariantsByTerm(termId)
        val before = glossaryRepository.findTermSnapshot(termId)
        glossaryRepository.deactivateTerm(termId, principal.userId)
        changeLog("TERM", "DELETE", termId, null, (before ?: emptyMap()) + mapOf("deactivatedVariants" to variantCnt), null)
        log.info("공식 용어 삭제 : termId={} 함께 빠지는 유사어={}건", termId, variantCnt)

        return mapOf("success" to true, "deactivatedVariants" to variantCnt)
    }

    /** 유사어 등록 (No.174) */
    @Transactional
    fun createVariant(termId: Int, word: String?): Map<String, Any?> {
        val principal = authorizationService.requireWrite(MenuId.SYS_GLOSS)

        if (!glossaryRepository.existsTerm(termId)) {
            throw ResourceNotFoundException("용어를 찾을 수 없습니다. [termId=$termId]")
        }
        requireTermVisible(principal, termId)
        val variantWord = word?.trim()?.takeIf { it.isNotBlank() }
            ?: throw InvalidParameterException("유사어를 입력해 주세요.", "word")
        checkVariantRules(variantWord)

        // uq_gls_variant_word 는 표 전체에서 한 번만 쓰게 한다 — 다른 용어에 붙은 것도 걸린다.
        glossaryRepository.findVariantByWord(variantWord)?.let { throw duplicatedVariant(it, variantWord) }

        val variantId = try {
            glossaryRepository.insertVariant(
                termId, variantWord, principal.userId, principal.deptName
            )
        } catch (e: DuplicateKeyException) {
            throw duplicatedVariant(null, variantWord)
        }
        changeLog("VARIANT", "CREATE", termId, variantId, null, mapOf("word" to variantWord))

        return mapOf("variantId" to variantId, "word" to variantWord, "warnings" to variantWarnings(variantWord, termId))
    }

    /**
     * 유사어 등록 규칙 (07 GLS-03) — 정규화에서 뺄 수밖에 없는 낱말은 처음부터 받지 않는다.
     * 한 글자·숫자·날짜 표현, 공식 용어(자기 용어 포함)와 같은 낱말은 400(field=word).
     */
    private fun checkVariantRules(word: String) {
        if (word.length < 2) throw InvalidParameterException("유사어는 2자 이상이어야 합니다.", "word")
        if (word.length > VARIANT_MAX) throw InvalidParameterException("유사어는 ${VARIANT_MAX}자 이하로 입력해 주세요.", "word")
        if (GlossaryVariantRules.riskOf(word) != null) {
            throw InvalidParameterException("숫자나 날짜 표현은 유사어로 등록할 수 없습니다.", "word")
        }
        glossaryRepository.findActiveTermByWord(word)?.let {
            throw InvalidParameterException("'$word' 는 공식 용어 [${it["term"]}] 입니다. 유사어로 등록할 수 없습니다.", "word")
        }
    }

    /** 다른 공식 용어 안에 들어 있는 유사어면 경고 — 경계 규칙 때문에 그 용어 안에서는 치환하지 않는다 */
    private fun variantWarnings(word: String, termId: Int?): List<String> {
        if (termId == null) return emptyList()
        val (cnt, example) = glossaryRepository.findTermsContaining(word, termId)
        return if (cnt > 0) listOf("'$word' 가 공식 용어 ${cnt}건(예: $example) 안에 들어 있습니다. 해당 용어는 치환하지 않습니다.")
        else emptyList()
    }

    /** 유사어 수정 (No.175 — 본인 등록 건만) */
    @Transactional
    fun updateVariant(variantId: Int, word: String?): Map<String, Any?> {
        val principal = authorizationService.requireWrite(MenuId.SYS_GLOSS)

        val variantWord = word?.trim()?.takeIf { it.isNotBlank() }
            ?: throw InvalidParameterException("유사어를 입력해 주세요.", "word")

        // 존재 확인을 소유권 확인보다 먼저 한다. 순서가 뒤바뀌면 없는 대상에도
        // "본인 것만 수정할 수 있습니다" 가 나가, 사용자는 남의 것을 건드린 줄 알게 된다.
        requireVariantExists(variantId)
        val before = glossaryRepository.findVariantSnapshot(variantId)
        (before?.get("termId") as Int?)?.let { requireTermVisible(principal, it) }
        checkVariantRules(variantWord)

        // 자기 자신은 빼고 본다 — 표기만 바꾸는 수정(can → CAN)이 제 이름에 막히면 안 된다.
        glossaryRepository.findVariantByWord(variantWord, variantId)
            ?.let { throw duplicatedVariant(it, variantWord) }

        val updated = try {
            glossaryRepository.updateVariant(
                variantId, variantWord, principal.userId, principal.superAdmin
            )
        } catch (e: DuplicateKeyException) {
            throw duplicatedVariant(null, variantWord)
        }
        if (updated == 0) {
            throw BusinessRuleException("본인이 등록한 유사어만 수정할 수 있습니다.")
        }
        if (before != null && before["word"] != variantWord) {
            changeLog("VARIANT", "UPDATE", before["termId"] as Int, variantId, mapOf("word" to before["word"]),
                mapOf("word" to variantWord) + byAdmin(before, principal.userId))
        }

        return mapOf("success" to true, "word" to variantWord,
            "warnings" to variantWarnings(variantWord, glossaryRepository.findVariantTermId(variantId)))
    }

    /** 유사어 삭제 (No.176 — 본인 등록 건만) */
    @Transactional
    fun deleteVariant(variantId: Int): Map<String, Any?> {
        val principal = authorizationService.requireWrite(MenuId.SYS_GLOSS)

        requireVariantExists(variantId)
        val before = glossaryRepository.findVariantSnapshot(variantId)
        (before?.get("termId") as Int?)?.let { requireTermVisible(principal, it) }

        val deleted = glossaryRepository.deleteVariant(variantId, principal.userId, principal.superAdmin)
        if (deleted == 0) {
            throw BusinessRuleException("본인이 등록한 유사어만 삭제할 수 있습니다.")
        }
        if (before != null) {
            changeLog("VARIANT", "DELETE", before["termId"] as Int, variantId,
                mapOf("word" to before["word"]) + byAdmin(before, principal.userId), null)
        }

        return mapOf("success" to true)
    }

    /** 사전 변경 이력 1행 — 업무 쓰기와 같은 트랜잭션 (07 GLS-07) */
    private fun changeLog(
        targetCd: String, actionCd: String, termId: Int, variantId: Int?, before: Map<String, Any?>?, after: Map<String, Any?>?
    ) {
        val principal = com.dwje.api.common.security.UserContext.currentOrNull() ?: return
        glossaryRepository.insertChangeLog(principal.userId, principal.deptName, targetCd, actionCd, termId, variantId, before, after)
        // 감사 타임라인에는 한 줄(동작 [대상])만 — 전후 값은 사전 변경 이력이 맡는다 (09 AUD-10)
        val what = (if (targetCd == "TERM") "공식 용어 " else "유사어 ") + when (actionCd) {
            "CREATE" -> "등록"; "UPDATE" -> "수정"; "DELETE" -> "삭제"; else -> "되살림"
        }
        val name = (after ?: before)?.let { it["term"] ?: it["word"] }
        auditLogService?.recordAfterCommit(
            com.dwje.api.common.util.AuditType.CONFIG_CHANGE, MenuId.SYS_GLOSS, "$what [$name]",
            "termId=$termId" + (variantId?.let { ", variantId=$it" } ?: "")
        )
    }

    /** 수행자가 등록자가 아니면(관리자 대리 처리) byAdmin=true 를 이력에 남긴다 */
    private fun byAdmin(variant: Map<String, Any?>, actorId: String): Map<String, Any?> =
        if (variant["ownerUserId"] != actorId) mapOf("byAdmin" to true) else emptyMap()

    /**
     * 사전 변경 이력 조회 (07 GLS-07) — 관리 화면(sys-gloss) 권한만. 조회 화면(gloss-view)에는 열지 않는다(13 GLV-02).
     *
     * termId 가 있으면 기간 기본값 없이 그 용어의 전 이력, 없으면 기본 최근 30일.
     */
    @Transactional(readOnly = true)
    fun getChanges(termId: Int?, from: String?, to: String?, page: Int?, size: Int?): Pair<List<Map<String, Any?>>, PageMeta> {
        authorizationService.requireMenu(MenuId.SYS_GLOSS)
        val paging = PageRequestParam.of(page, size)
        val (f, t) = if (termId != null && from.isNullOrBlank() && to.isNullOrBlank()) null to null
        else com.dwje.api.common.util.DateUtils.periodOf(from, to, 30)
        val total = glossaryRepository.countChanges(termId, f, t)
        val hidden = hiddenDomainsOf(authorizationService.requireMenu(MenuId.SYS_GLOSS))
        // 가린 분류 용어의 이력은 이름·전후 값을 가린다 (R-18)
        val rows = glossaryRepository.findChanges(termId, f, t, paging.limit, paging.offset).map {
            if (it["domainId"] in hidden.ids) it + mapOf("term" to BLIND_TERM, "before" to null, "after" to null, "blinded" to true)
            else it + ("blinded" to false)
        }
        auditBlind(rows.count { it["blinded"] == true }, hidden, MenuId.SYS_GLOSS, "용어 변경 이력 가림")
        return rows.map { it - "domainId" } to PageMeta.of(paging.page, paging.size, total)
    }

    /**
     * 용어 중복 409 를 만든다.
     *
     * 사전 조회에서 잡았으면 [existing] 으로 **이미 등록된 표기**를 알려 준다 —
     * 사용자가 'can' 을 넣었을 때 "이미 등록된 용어입니다. [CAN]" 이라야 왜 막혔는지 안다.
     *
     * 경합으로 DB 유니크에 걸린 경우에는 [existing] 이 null 이다. 트랜잭션이 이미 중단된
     * 상태라 표기를 다시 조회할 수 없다 — 여기서 SELECT 를 하면 "current transaction is
     * aborted" 가 나서 409 대신 500 이 나간다. 그래서 입력값만으로 메시지를 만든다.
     */
    private fun duplicatedTerm(existing: Map<String, Any?>?, input: String): ConflictingValueException {
        val stored = existing?.get("term") as? String
        return ConflictingValueException(
            if (stored != null && stored != input) {
                "이미 등록된 용어입니다. [$stored] 대소문자·앞뒤 공백만 다른 이름은 같은 용어로 봅니다. (입력: $input)"
            } else {
                "이미 등록된 용어입니다. [${stored ?: input}]"
            },
            "term"
        )
    }

    /**
     * 유사어 중복 409 를 만든다.
     *
     * 유사어는 용어별이 아니라 표 전체에서 한 번만 쓸 수 있다(uq_gls_variant_word).
     * 그래서 "어느 공식 용어가 이미 쓰고 있는지" 를 함께 알려 줘야 사용자가 다음 행동을 정한다.
     * [existing] 이 null 인 경우의 사정은 [duplicatedTerm] 과 같다.
     */
    private fun duplicatedVariant(existing: Map<String, Any?>?, input: String): ConflictingValueException {
        val stored = existing?.get("word") as? String ?: input
        val owner = existing?.get("term") as? String
        // 삭제된 용어에 붙은 유사어 — 목록에 안 보이는 이름이 왜 막히는지 알린다 (07 GLS-14)
        if (existing != null && existing["termActive"] == false) {
            return ConflictingValueException(
                "삭제된 공식 용어 [$owner] 에 붙어 있던 유사어입니다. [$stored] 그 용어를 같은 이름으로 다시 등록하면 함께 돌아옵니다" +
                    "(공식 용어 등록은 통합관리자만 할 수 있습니다).",
                "word"
            )
        }
        return ConflictingValueException(
            "이미 등록된 유사어입니다. [$stored]" +
                (owner?.let { " 공식 용어 [$it] 에 붙어 있습니다." } ?: "") +
                (if (existing != null && stored != input) " (입력: $input)" else ""),
            "word"
        )
    }

    /**
     * 유사어가 없으면 404 로 끊는다.
     *
     * 없는 대상(404)과 남의 것(409)은 다른 상황이라 응답도 달라야 한다.
     */
    private fun requireVariantExists(variantId: Int) {
        if (!glossaryRepository.existsVariant(variantId)) {
            throw ResourceNotFoundException("유사어를 찾을 수 없습니다. [variantId=$variantId]")
        }
    }

    /** 용어 정규화 미리보기 (No.177) */
    @Transactional(readOnly = true)
    fun normalize(request: GlossaryNormalizeRequest): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.SYS_GLOSS)

        val result = glossaryNormalizer.normalize(request.text)
        // 볼 수 없는 분류의 용어로 바뀐 곳은 원문 그대로 둔다 — 미리보기가 고객사 이름을 알려 주지 않게 (R-18)
        val visible = AiChatService.visibleReplacements(result.replacements, principal)
        val text = if (visible.size == result.replacements.size) result.normalizedText else buildString {
            var at = 0
            visible.sortedBy { it["start"] as Int }.forEach { r ->
                append(request.text, at, r["start"] as Int); append(r["to"]); at = r["end"] as Int
            }
            append(request.text.substring(at))
        }
        return mapOf(
            "normalizedText" to text,
            "replacements" to visible,
            // 원문에 있었지만 위험 유사어라 치환하지 않은 것 (GLS-02)
            "skipped" to result.skipped
        )
    }

    /**
     * 위험 유사어 점검 목록 (07 GLS-03) — 통합관리자만. 한 유사어가 여러 위험에 걸리면 위험마다 1행이다.
     * 기존 데이터는 고치지 않는다 — 사람이 보고 정리한다.
     */
    @Transactional(readOnly = true)
    fun getRiskVariants(): Map<String, Any?> {
        authorizationService.requireSuperAdmin()
        return mapOf("items" to riskVariantRows())
    }

    /** 위험 유사어 점검 행 — 위험 코드 순, 같은 코드 안에서는 낱말 순 */
    private fun riskVariantRows(): List<Map<String, Any?>> {
        val order = GlossaryVariantRules.RISK_NAMES.keys.toList()
        val items = glossaryRepository.findRiskVariantCandidates().flatMap { v ->
            listOfNotNull(
                GlossaryVariantRules.riskOf(v["word"] as String),
                "SAME_AS_TERM".takeIf { v["sameTerm"] != null },
                "SUBSTRING_OF_TERM".takeIf { (v["subCnt"] as Long) > 0 }
            ).map { cd ->
                mapOf("variantId" to v["variantId"], "word" to v["word"], "termId" to v["termId"], "term" to v["term"],
                    "ownerName" to v["ownerName"], "riskCd" to cd, "riskNm" to GlossaryVariantRules.RISK_NAMES[cd])
            }
        }.sortedWith(compareBy({ order.indexOf(it["riskCd"]) }, { it["word"] as String }))
        return items
    }

    /**
     * 용어 상세 (13 GLV-04) — 뜻·유사어·관련 용어(최대 10건). 두 화면(sys-gloss·gloss-view) 공용.
     */
    @Transactional(readOnly = true)
    fun getTermDetail(termId: Int): Map<String, Any?> {
        val (principal, manager) = requireViewer()
        val term = glossaryRepository.findTermDetail(termId)
            ?: throw ResourceNotFoundException("용어를 찾을 수 없습니다. [termId=$termId]")
        val variants = shapeVariants(
            glossaryRepository.findVariantsByTermIds(listOf(termId), principal.userId)[termId] ?: emptyList(),
            principal.canWriteMenu(MenuId.SYS_GLOSS), manager
        ).map { v -> v.filterKeys { it in setOf("variantId", "word", "byName", "byEmpNo", "at", "editable") } }
        val hidden = hiddenDomainsOf(principal)
        // 관련 용어 중 가린 분류는 이름을 가린다
        val related = glossaryRepository.findRelatedTerms(termId, 10).map {
            if (it["domain"] in hidden.names) it + mapOf("term" to BLIND_TERM, "blinded" to true) else it + ("blinded" to false)
        }
        val menu = if (manager) MenuId.SYS_GLOSS else MenuId.GLOSS_VIEW
        if (term["domainId"] in hidden.ids) {
            auditBlind(1 + related.count { it["blinded"] == true }, hidden, menu, "용어 상세 가림 [termId=$termId]")
            return blindTerm(term) + mapOf("relatedTerms" to related)
        }
        auditBlind(related.count { it["blinded"] == true }, hidden, menu, "용어 상세 가림 [termId=$termId]")
        return term + mapOf(
            // 고객사 분류 가림(R-18) — 볼 수 있는 용어
            "blinded" to false,
            "variants" to variants,
            "relatedTerms" to related
        )
    }

    /** 내려받기 결과 — 파일은 컨트롤러가 만든다 */
    data class ExportRows(
        val menuId: String, val scopeCd: String?, val headers: List<String>, val keys: List<String>,
        val rows: List<Map<String, Any?>>, val total: Long, val condSummary: String,
        /** 가린 칸 수 — 가린 용어 행의 뜻·유사어(+등록자) 칸 (R-18). 파일 안내·다운로드 이력 blindCnt 에 더한다 */
        val blindedCells: Int = 0
    )

    /**
     * 용어 사전 내려받기 (07 GLS-12 · 13 GLV-05) — 조회 권한으로 판정한다(공통 9.8, R-10).
     *
     * - `scopeCd=ALL` 이면 검색 조건을 무시하고 사용 중 용어 전체(상한 [EXPORT_MAX]).
     * - 열은 부르는 화면에 따라 다르다. 용어 사전 조회 화면에는 등록자 열이 없고, 어느 쪽에도 사번 열은 없다.
     * - 기록 화면은 요청의 menuId 를 쓰되, 그 화면 권한이 없으면 가진 화면으로 바꾼다(사칭 방지).
     */
    @Transactional(readOnly = true)
    fun exportTerms(req: GlossaryExportRequest?): ExportRows {
        val (principal, manager) = requireViewer()
        val asked = req?.menuId?.trim()
        val menuId = when {
            asked == MenuId.SYS_GLOSS && manager -> MenuId.SYS_GLOSS
            asked == MenuId.GLOSS_VIEW && principal.canAccessMenu(MenuId.GLOSS_VIEW) -> MenuId.GLOSS_VIEW
            manager -> MenuId.SYS_GLOSS
            else -> MenuId.GLOSS_VIEW
        }
        val scopeCd = (req?.scopeCd ?: req?.scope)?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        if (scopeCd != null && scopeCd !in setOf("VIEW", "ALL")) {
            throw InvalidParameterException("범위 코드는 VIEW 또는 ALL 이어야 합니다.", "scopeCd")
        }
        val all = scopeCd == "ALL"
        val keyword = if (all) null else req?.keyword?.takeIf { it.isNotBlank() }
        val domainCd = if (all) null else req?.domainCd?.takeIf { it.isNotBlank() }
        val mineOnly = if (!all && manager && req?.mineOnly == true) principal.userId else null

        val hidden = hiddenDomainsOf(principal)
        val total = glossaryRepository.countTerms(keyword, domainCd, mineOnly, hidden.ids)
        val terms = glossaryRepository.findTerms(keyword, domainCd, EXPORT_MAX, 0, mineOnly, hidden.ids)
        if (terms.isEmpty()) throw ResourceNotFoundException("내려받을 용어가 없습니다. 조건을 바꿔 주세요.")
        val variants = glossaryRepository.findVariantsByTermIds(terms.map { it["termId"] as Int }, principal.userId)
        val mask = com.dwje.api.service.BlindCells.MASK
        val rows = terms.map { t ->
            // 가린 분류의 용어는 이름 「비공개 용어」, 뜻·유사어·등록자는 「비공개」 (R-18)
            if (t["domainId"] in hidden.ids) return@map t + mapOf("term" to BLIND_TERM, "definition" to mask, "variants" to mask, "byName" to mask)
            val vs = variants[t["termId"]] ?: emptyList()
            t + mapOf(
                "variants" to vs.joinToString(" · ") { it["word"] as String },
                "byName" to vs.mapNotNull { it["byName"] as String? }.distinct().joinToString(", ")
            )
        }
        val blindedRows = terms.count { it["domainId"] in hidden.ids }
        val (headers, keys) = if (menuId == MenuId.SYS_GLOSS) {
            listOf("공식 용어", "뜻", "분류", "유사어", "유사어 등록자", "등록일", "최근 수정") to
                listOf("term", "definition", "domain", "variants", "byName", "createdAt", "updatedAt")
        } else {
            listOf("공식 용어", "뜻", "분류", "유사어", "최근 수정") to listOf("term", "definition", "domain", "variants", "updatedAt")
        }
        val cond = req?.condSummary?.takeIf { it.isNotBlank() }
            ?: if (all) "전체" else "검색=${keyword ?: ""}, 분류=${domainCd ?: "전체"}" + (if (mineOnly != null) ", 내 유사어" else "")
        auditBlind(blindedRows, hidden, menuId, "용어 사전 내려받기 가림")
        // 가린 칸 = 행마다 뜻·유사어(관리 화면 파일은 등록자 열도)
        val cellsPerRow = if (menuId == MenuId.SYS_GLOSS) 3 else 2
        return ExportRows(menuId, scopeCd ?: "VIEW", headers, keys, rows, total, cond.take(500), blindedRows * cellsPerRow)
    }

    /** 업로드용 템플릿 — sys-gloss 접근이면 누구나. 분류 목록은 지금 쓰는 분류 이름 */
    @Transactional(readOnly = true)
    fun importTemplate(): ByteArray {
        authorizationService.requireMenu(MenuId.SYS_GLOSS)
        val domains = glossaryRepository.findDomains().map { it["name"] as String }
        return GlossaryImportWorkbook.template(domains, TERM_MAX, DEFINITION_MAX, VARIANT_MAX)
    }

    /** 같은 파일 앞 행에서 정한 용어 — 뒤 행의 같은 용어는 기존 용어처럼 유사어만 더한다 */
    private data class FileTerm(val termId: Int?, val term: String, val row: Int)

    /**
     * 용어 사전 엑셀 업로드 — 권한 sys-gloss 쓰기(미배정 아님)
     *
     * - 공식 용어가 이미 있으면 유사어만 더하고 뜻·분류는 바꾸지 않는다. 삭제된 같은 이름은 새 용어 등록처럼 되살린다([createTerm] 과 같다).
     * - 새 공식 용어는 통합관리자만 — 그 밖의 계정이 올린 새 용어 행은 ERROR.
     * - 유사어는 개별 등록과 같은 규칙. 어긋나거나 이미 있거나 같은 파일 안에서 겹치면 그 낱말만 건너뛴다(variantsSkipped).
     * - [dryRun] 이면 아무것도 쓰지 않는다. 아니면 ERROR 행을 빼고 행 단위로 등록하고, 변경 이력·감사를 개별 등록과 같게 남긴다.
     *   미리보기와 등록이 같은 판정을 하도록 한 번의 순회에서 판정하고, 등록일 때만 그 자리에서 쓴다.
     */
    @Transactional
    fun importTerms(bytes: ByteArray?, fileName: String?, dryRun: Boolean): Map<String, Any?> {
        val principal = authorizationService.requireWrite(MenuId.SYS_GLOSS)
        val original = java.nio.file.Paths.get(fileName ?: "upload.xlsx").fileName.toString()
        if (bytes == null || bytes.isEmpty()) throw InvalidParameterException("업로드할 엑셀 파일이 없습니다.", "file")
        if (bytes.size > GlossaryImportWorkbook.MAX_BYTES) {
            throw InvalidParameterException("파일이 너무 큽니다. 최대 ${GlossaryImportWorkbook.MAX_BYTES / 1024 / 1024}MB 입니다.", "file")
        }
        if (!original.endsWith(".xlsx", ignoreCase = true)) throw InvalidParameterException("xlsx 파일만 올릴 수 있습니다. [$original]", "file")
        XlsxUploadGuard.assertPlainXlsx(bytes, original)
        val rows = GlossaryImportWorkbook.parse(bytes)

        val hidden = hiddenDomainsOf(principal)
        val domains = glossaryRepository.findDomains().associate { (it["name"] as String).lowercase() to (it["name"] as String to it["domainId"] as Int) }
        val fileTerms = mutableMapOf<String, FileTerm>()
        val fileVariants = mutableMapOf<String, Int>()
        var termNew = 0; var termExisting = 0; var variantNew = 0; var variantSkipped = 0; var errorCnt = 0

        val results = rows.map { r ->
            val errors = mutableListOf<Map<String, String>>()
            fun error(field: String, message: String) { errors += mapOf("field" to field, "message" to message) }
            val notes = mutableListOf<String>()
            val key = r.term.lowercase()

            // 1. 용어 판정 — 같은 파일 앞 행 → DB 순
            val prior = fileTerms[key]
            val stored = if (prior == null && r.term.isNotEmpty()) glossaryRepository.findTermByName(r.term) else null
            var termId: Int? = prior?.termId ?: (stored?.takeIf { it["active"] == true }?.get("termId") as Int?)
            val existing = prior != null || stored?.get("active") == true
            var domainNm: String? = null
            when {
                r.term.isEmpty() -> error("term", "공식 용어를 입력해 주세요.")
                r.term.length > TERM_MAX -> error("term", "공식 용어는 ${TERM_MAX}자 이하로 입력해 주세요.")
                existing -> {
                    notes += if (prior != null) "같은 파일 ${prior.row}행의 용어 — 유사어만 더함" else "기존 용어 — 뜻·분류는 바꾸지 않음"
                    if (termId != null && hidden.ids.isNotEmpty() && glossaryRepository.findTermDomainId(termId) in hidden.ids) {
                        error("term", "데이터 접근 권한이 없는 분류의 용어라 고칠 수 없습니다.")
                    }
                }
                !principal.superAdmin -> error("term", "공식 용어 등록은 통합관리자만 할 수 있습니다.")
                else -> {
                    if (r.definition.isEmpty()) error("definition", "용어 정의를 입력해 주세요.")
                    else if (r.definition.length > DEFINITION_MAX) error("definition", "뜻은 ${DEFINITION_MAX}자 이하로 입력해 주세요.")
                    if (r.domain.isEmpty()) error("domain", "새 공식 용어는 분류를 입력해 주세요.")
                    else domainNm = domains[r.domain.lowercase()]?.first ?: run { error("domain", "분류를 찾을 수 없습니다. [${r.domain}]"); null }
                    if (stored != null) notes += "삭제된 용어를 되살림 — 예전 유사어 ${glossaryRepository.countVariantsByTerm(stored["termId"] as Int)}건이 함께 돌아옴"
                }
            }
            if (errors.isNotEmpty()) {
                errorCnt++
                return@map mapOf("row" to r.row, "term" to r.term, "action" to "ERROR", "variantsAdded" to emptyList<String>(),
                    "variantsSkipped" to emptyList<Map<String, String>>(), "errors" to errors, "notes" to notes)
            }

            // 2. 새 용어 등록 (등록일 때만 쓴다)
            val isNew = !existing
            if (isNew) {
                termNew++
                if (!dryRun) termId = writeImportedTerm(r.term, r.definition, domainNm!!, domains[domainNm.lowercase()]!!.second, stored, principal.userId)
            } else termExisting++
            fileTerms.putIfAbsent(key, FileTerm(termId, prior?.term ?: (stored?.get("term") as String? ?: r.term), prior?.row ?: r.row))

            // 3. 유사어 — 개별 등록과 같은 규칙, 어긋나면 그 낱말만 건너뛴다
            val added = mutableListOf<String>()
            val skipped = mutableListOf<Map<String, String>>()
            val warnings = mutableListOf<String>()
            val revivedId = if (isNew) stored?.get("termId") as Int? else null
            r.variants.forEach { w ->
                val lw = w.lowercase()
                val reason = fileVariants[lw]?.let { "같은 파일 ${it}행과 중복" }
                    ?: fileTerms[lw]?.let { "공식 용어 [${it.term}] 와 같은 낱말 (같은 파일 ${it.row}행)" }
                    ?: try { checkVariantRules(w); null } catch (e: InvalidParameterException) { e.message }
                    ?: glossaryRepository.findVariantByWord(w)?.let {
                        if (it["termId"] == revivedId) "되살리는 용어에 이미 있는 유사어" else duplicatedVariant(it, w).message
                    }
                if (reason != null) { skipped += mapOf("word" to w, "reason" to reason); return@forEach }
                fileVariants[lw] = r.row
                added += w
                if (!dryRun) {
                    val id = termId!!
                    val variantId = try {
                        glossaryRepository.insertVariant(id, w, principal.userId, principal.deptName)
                    } catch (e: DuplicateKeyException) {
                        throw duplicatedVariant(null, w)
                    }
                    changeLog("VARIANT", "CREATE", id, variantId, null, mapOf("word" to w))
                }
                warnings += variantWarnings(w, termId ?: 0)
            }
            variantNew += added.size
            variantSkipped += skipped.size

            buildMap<String, Any?> {
                put("row", r.row); put("term", r.term); put("termId", termId)
                put("action", if (isNew) "NEW_TERM" else "EXISTING_TERM")
                if (isNew) { put("definition", r.definition); put("domain", domainNm); put("restored", stored != null) }
                put("variantsAdded", added); put("variantsSkipped", skipped)
                put("errors", emptyList<Map<String, String>>()); put("notes", notes); put("warnings", warnings)
            }
        }

        if (!dryRun) log.info("용어 사전 업로드 : file={} 새 용어={} 기존 용어={} 새 유사어={} 건너뜀={} 오류 행={}",
            original, termNew, termExisting, variantNew, variantSkipped, errorCnt)
        return mapOf(
            "dryRun" to dryRun, "fileName" to original, "totalRows" to rows.size,
            "termNew" to termNew, "termExisting" to termExisting,
            "variantNew" to variantNew, "variantSkipped" to variantSkipped, "errorCnt" to errorCnt,
            "rows" to results
        )
    }

    /** 업로드의 새 공식 용어 1건 — [createTerm] 과 같게 쓰고 이력을 남긴다(삭제된 같은 이름은 되살림) */
    private fun writeImportedTerm(
        term: String, def: String, domainNm: String, domainId: Int, stored: Map<String, Any?>?, actor: String
    ): Int {
        val revivedId = stored?.get("termId") as Int?
        if (revivedId != null) {
            val restoredVariants = glossaryRepository.countVariantsByTerm(revivedId)
            val before = glossaryRepository.findTermSnapshot(revivedId)
            glossaryRepository.reviveTerm(revivedId, def, domainId, actor)
            changeLog("TERM", "RESTORE", revivedId, null, before,
                mapOf("term" to stored["term"], "termDef" to def, "domainNm" to domainNm, "restoredVariants" to restoredVariants))
            return revivedId
        }
        val termId = try {
            glossaryRepository.insertTerm(term, def, domainId, actor)
        } catch (e: DuplicateKeyException) {
            throw duplicatedTerm(null, term)
        }
        changeLog("TERM", "CREATE", termId, null, null, mapOf("term" to term, "termDef" to def, "domainNm" to domainNm))
        return termId
    }

    /**
     * 용어 임베딩 재생성 (No.178) — 통합관리자만 (07 GLS-05, 공통 9.7)
     *
     * 용어·유사어 임베딩을 다시 만드는 색인 작업을 등록한다. 처리기가 아직 없어 작업은 PENDING 으로 남는다.
     * 기획의 QUEUED 는 작업 상태 공통코드(SYNC_STATE)에 없어 PENDING 으로 넣는다.
     */
    @Transactional
    fun reindex(): Map<String, Any?> {
        val principal = authorizationService.requireSuperAdmin()

        val dictionary = glossaryRepository.findNormalizationDictionary()
        val jobId = vectorIndexRepository.insertIngestJob(
            jobTypeCd = "TERM_EMBED",
            embedModelId = vectorIndexRepository.findDefaultEmbedModelId(),
            docCnt = 0,
            chunkCnt = dictionary.size,
            triggeredBy = principal.userId,
            remark = "용어 사전 임베딩 재생성 (${dictionary.size}건)",
            stateCd = "PENDING"
        )

        log.info("용어 임베딩 재생성 작업 등록 : jobId={} 대상={}건", jobId, dictionary.size)
        auditLogService?.recordAfterCommit(
            com.dwje.api.common.util.AuditType.CONFIG_CHANGE, MenuId.SYS_GLOSS, "용어 임베딩 재생성 [$jobId]", "대상 ${dictionary.size}건"
        )
        return mapOf(
            "jobId" to jobId, "targetCnt" to dictionary.size, "stateCd" to "PENDING",
            "message" to "처리기가 없어 대기 상태로 남습니다"
        )
    }
}
