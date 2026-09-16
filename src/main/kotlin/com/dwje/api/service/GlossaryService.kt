package com.dwje.api.service

import com.dwje.api.common.exception.BusinessRuleException
import com.dwje.api.common.exception.ConflictingValueException
import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.exception.ResourceNotFoundException
import com.dwje.api.common.response.PageMeta
import com.dwje.api.common.util.MenuId
import com.dwje.api.common.util.PageRequestParam
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
 * 공식 용어는 전산팀·통합관리자가 관리하고, 현장 유사어는 전 부서 사용자가 본인 등록 건에 한해 관리한다.
 *
 * 접근 부서 : 전 부서
 */
@Service
class GlossaryService(
    private val glossaryRepository: GlossaryRepository,
    private val glossaryNormalizer: GlossaryNormalizer,
    private val vectorIndexRepository: VectorIndexRepository,
    private val authorizationService: AuthorizationService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 용어 사전 요약 (No.170) */
    @Transactional(readOnly = true)
    fun getSummary(): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.SYS_GLOSS)

        val summary = glossaryRepository.findSummary(principal.userId).toMutableMap()
        summary["byDomain"] = glossaryRepository.findCountByDomain()
        return summary.toMap()
    }

    /**
     * 용어 분류(도메인) 목록 — 용어 등록 화면의 분류 선택지
     *
     * `code` 를 [createTerm] 의 `domainCd` 에 그대로 넣을 수 있다.
     */
    @Transactional(readOnly = true)
    fun getDomains(): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.SYS_GLOSS)
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
        size: Int?
    ): Pair<List<Map<String, Any?>>, PageMeta> {
        val principal = authorizationService.requireMenu(MenuId.SYS_GLOSS)
        val paging = PageRequestParam.of(page, size)

        val total = glossaryRepository.countTerms(keyword, domainCd)
        val terms = glossaryRepository.findTerms(keyword, domainCd, paging.limit, paging.offset)

        // 유사어는 한 번에 조회해 N+1 을 피한다.
        val termIds = terms.mapNotNull { it["termId"] as? Int }
        val variants = glossaryRepository.findVariantsByTermIds(termIds, principal.userId)

        val items = terms.map { it + mapOf("variants" to (variants[it["termId"]] ?: emptyList<Any>())) }

        return items to PageMeta.of(paging.page, paging.size, total)
    }

    /** 공식 용어 등록 (No.172) */
    @Transactional
    fun createTerm(term: String?, definition: String?, domainCd: String?): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.SYS_GLOSS)

        val termName = term?.trim()?.takeIf { it.isNotBlank() }
            ?: throw InvalidParameterException("용어를 입력해 주세요.", "term")
        val def = definition?.trim()?.takeIf { it.isNotBlank() }
            ?: throw InvalidParameterException("용어 정의를 입력해 주세요.", "definition")
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
            glossaryRepository.reviveTerm(revivedId, def, domainId, principal.userId)
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

        return mapOf("termId" to termId, "term" to termName)
    }

    /** 공식 용어 수정 (No.173) */
    @Transactional
    fun updateTerm(termId: Int, term: String?, definition: String?, domainCd: String?): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.SYS_GLOSS)

        if (!glossaryRepository.existsTerm(termId)) {
            throw ResourceNotFoundException("용어를 찾을 수 없습니다. [termId=$termId]")
        }

        val termName = term?.trim()?.takeIf { it.isNotBlank() }
            ?: throw InvalidParameterException("용어를 입력해 주세요.", "term")
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

        try {
            glossaryRepository.updateTerm(
                termId, termName, definition?.trim() ?: "", domainId, principal.userId
            )
        } catch (e: DuplicateKeyException) {
            throw duplicatedTerm(null, termName)
        }

        return mapOf("success" to true, "term" to termName)
    }

    /**
     * 공식 용어 삭제 — 등록자 본인 또는 통합관리자만
     *
     * 사용 중지(`use_flg='N'`)로 처리한다. 목록·요약·정규화 사전에서 함께 빠지고,
     * 그 용어에 달린 유사어도 사전 조회에서 같이 빠진다.
     * 몇 건이 함께 빠지는지 응답에 담아, 남의 유사어를 모르고 없애는 일이 없게 한다.
     *
     * 없는 용어는 404, 남의 용어는 409 다 — 유사어 삭제와 같은 규칙이다.
     */
    @Transactional
    fun deleteTerm(termId: Int): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.SYS_GLOSS)

        val owner = glossaryRepository.findTermOwner(termId)
            ?: throw ResourceNotFoundException("용어를 찾을 수 없습니다. [termId=$termId]")
        if (owner["active"] != true) {
            throw ResourceNotFoundException("이미 삭제된 용어입니다. [termId=$termId]")
        }
        if (!principal.superAdmin && owner["insUser"] != principal.userId) {
            throw BusinessRuleException("본인이 등록한 용어만 삭제할 수 있습니다.")
        }

        val variantCnt = glossaryRepository.countVariantsByTerm(termId)
        glossaryRepository.deactivateTerm(termId, principal.userId)
        log.info("공식 용어 삭제 : termId={} 함께 빠지는 유사어={}건", termId, variantCnt)

        return mapOf("success" to true, "deactivatedVariants" to variantCnt)
    }

    /** 유사어 등록 (No.174) */
    @Transactional
    fun createVariant(termId: Int, word: String?): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.SYS_GLOSS)

        if (!glossaryRepository.existsTerm(termId)) {
            throw ResourceNotFoundException("용어를 찾을 수 없습니다. [termId=$termId]")
        }
        val variantWord = word?.trim()?.takeIf { it.isNotBlank() }
            ?: throw InvalidParameterException("유사어를 입력해 주세요.", "word")

        // uq_gls_variant_word 는 표 전체에서 한 번만 쓰게 한다 — 다른 용어에 붙은 것도 걸린다.
        glossaryRepository.findVariantByWord(variantWord)?.let { throw duplicatedVariant(it, variantWord) }

        val variantId = try {
            glossaryRepository.insertVariant(
                termId, variantWord, principal.userId, principal.deptName
            )
        } catch (e: DuplicateKeyException) {
            throw duplicatedVariant(null, variantWord)
        }

        return mapOf("variantId" to variantId, "word" to variantWord)
    }

    /** 유사어 수정 (No.175 — 본인 등록 건만) */
    @Transactional
    fun updateVariant(variantId: Int, word: String?): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.SYS_GLOSS)

        val variantWord = word?.trim()?.takeIf { it.isNotBlank() }
            ?: throw InvalidParameterException("유사어를 입력해 주세요.", "word")

        // 존재 확인을 소유권 확인보다 먼저 한다. 순서가 뒤바뀌면 없는 대상에도
        // "본인 것만 수정할 수 있습니다" 가 나가, 사용자는 남의 것을 건드린 줄 알게 된다.
        requireVariantExists(variantId)

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

        return mapOf("success" to true, "word" to variantWord)
    }

    /** 유사어 삭제 (No.176 — 본인 등록 건만) */
    @Transactional
    fun deleteVariant(variantId: Int): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.SYS_GLOSS)

        requireVariantExists(variantId)

        val deleted = glossaryRepository.deleteVariant(variantId, principal.userId, principal.superAdmin)
        if (deleted == 0) {
            throw BusinessRuleException("본인이 등록한 유사어만 삭제할 수 있습니다.")
        }

        return mapOf("success" to true)
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
        authorizationService.requireMenu(MenuId.SYS_GLOSS)

        val result = glossaryNormalizer.normalize(request.text)
        return mapOf(
            "normalizedText" to result.normalizedText,
            "replacements" to result.replacements
        )
    }

    /**
     * 용어 임베딩 재생성 (No.178)
     *
     * 용어·유사어 임베딩을 다시 생성하는 색인 작업을 등록한다.
     */
    @Transactional
    fun reindex(): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.SYS_GLOSS)

        val dictionary = glossaryRepository.findNormalizationDictionary()
        val jobId = vectorIndexRepository.insertIngestJob(
            jobTypeCd = "TERM_EMBED",
            embedModelId = vectorIndexRepository.findDefaultEmbedModelId(),
            docCnt = 0,
            chunkCnt = dictionary.size,
            triggeredBy = principal.userId,
            remark = "용어 사전 임베딩 재생성 (${dictionary.size}건)"
        )

        log.info("용어 임베딩 재생성 작업 등록 : jobId={} 대상={}건", jobId, dictionary.size)
        return mapOf("jobId" to jobId, "targetCnt" to dictionary.size)
    }
}
