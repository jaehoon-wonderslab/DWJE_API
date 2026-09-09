package com.dwje.api.service

import com.dwje.api.common.exception.BusinessRuleException
import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.exception.ResourceNotFoundException
import com.dwje.api.common.response.PageMeta
import com.dwje.api.common.security.UserContext
import com.dwje.api.common.util.DateUtils
import com.dwje.api.common.util.MenuId
import com.dwje.api.common.util.PageRequestParam
import com.dwje.api.config.AppProperties
import com.dwje.api.model.request.DayTargetRequest
import com.dwje.api.repository.DashboardProcessRepository
import com.dwje.api.repository.DayTargetRepository
import com.dwje.api.repository.ProductionRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * 제품·공정별 일목표 마스터 서비스
 *
 * ## 왜 필요한가
 * 제품별 일목표의 정식 출처가 없어 작성자가 보고서마다 손으로 열 줄씩 넣고 있었다.
 * 하루는 되지만 한 달은 못 간다. 2026-09-04 사용자 결정으로 담을 자리를 만들었다.
 *
 * ## 적용일 구간
 * 목표는 `applyFrom` 부터 **다음 적용일 전까지** 유효하다. 종료일을 두지 않으므로
 * 어느 날짜의 목표는 "그 날짜 이하의 적용일 중 가장 늦은 것" 한 건이다.
 *
 * ## 작성자 입력과의 우선순위
 * 일일 보고에서 작성자가 그날만 목표를 달리 잡으면 그 값이 마스터를 덮어쓴다.
 * **저장값(`tb_prod_daily_decision`) > 마스터 > 없음(null)** 이다.
 */
@Service
class DayTargetService(
    private val dayTargetRepository: DayTargetRepository,
    private val productionRepository: ProductionRepository,
    private val dashboardProcessRepository: DashboardProcessRepository,
    private val authorizationService: AuthorizationService,
    private val appProperties: AppProperties
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 일목표를 조회한다.
     *
     * @param date 지정하면 **그 날짜에 유효한 한 건씩만** 돌려준다. 미지정 시 전 이력.
     */
    fun getTargets(
        product: String?,
        processId: String?,
        date: String?,
        page: Int?,
        size: Int?
    ): Pair<List<Map<String, Any?>>, PageMeta> {
        authorizationService.requireMenu(MenuId.PROD_DAILY)
        val plantCd = appProperties.defaultPlantCd

        val productCd = product?.trim()?.takeIf { it.isNotBlank() }
        val wcCd = processId?.trim()?.takeIf { it.isNotBlank() }
        val on = date?.trim()?.takeIf { it.isNotBlank() }?.let { DateUtils.parseDate(it, "date") }

        // size=0 은 전건 조회 규약이다.
        val paging = PageRequestParam.ofAllowAll(page, size)
        val total = dayTargetRepository.countTargets(plantCd, productCd, wcCd, on)
        val rows = dayTargetRepository.findTargets(
            plantCd, productCd, wcCd, on,
            limit = paging.limitOrNull ?: total.toInt().coerceAtLeast(1),
            offset = paging.offset
        )

        val meta = if (paging.isAll) PageMeta.all(total) else PageMeta.of(paging.page, paging.size, total)
        return rows to meta
    }

    /** 일목표를 등록한다. */
    @Transactional
    fun create(request: DayTargetRequest): Map<String, Any?> {
        val principal = UserContext.current()
        authorizationService.requireMenu(MenuId.PROD_DAILY)
        val plantCd = appProperties.defaultPlantCd

        val product = required(request.product, "product")
        val wcCd = required(request.processId, "processId")
        val applyFrom = requiredDate(request.applyFrom)
        val targetQty = requiredQty(request.targetQty)

        requireKnownProduct(product)
        requireKnownProcess(plantCd, wcCd)

        if (dayTargetRepository.existsSameKey(plantCd, product, wcCd, applyFrom, null)) {
            throw BusinessRuleException(
                "같은 제품·공정에 같은 적용일의 목표가 이미 있습니다. " +
                    "수정하거나 다른 적용일을 쓰세요. [$product / $wcCd / $applyFrom]"
            )
        }

        val targetId = dayTargetRepository.insert(
            plantCd, product, wcCd, applyFrom, targetQty, request.remark, principal.userId
        )
        log.info("일목표 등록 : product={} wcCd={} applyFrom={} qty={}", product, wcCd, applyFrom, targetQty)

        return mapOf("targetId" to targetId)
    }

    /**
     * 일목표를 수정한다.
     *
     * 제품·공정은 바꿀 수 없다 — 바꾸면 다른 제품의 목표를 덮어쓰는 것이라
     * 삭제하고 새로 등록하는 것과 뜻이 달라진다.
     */
    @Transactional
    fun update(targetId: Long, request: DayTargetRequest): Map<String, Any?> {
        val principal = UserContext.current()
        authorizationService.requireMenu(MenuId.PROD_DAILY)
        val plantCd = appProperties.defaultPlantCd

        val current = dayTargetRepository.findById(targetId)
            ?: throw ResourceNotFoundException("일목표를 찾을 수 없습니다. [targetId=$targetId]")

        val product = current["product"] as String
        val wcCd = current["processId"] as String
        val applyFrom = requiredDate(request.applyFrom)
        val targetQty = requiredQty(request.targetQty)

        if (dayTargetRepository.existsSameKey(plantCd, product, wcCd, applyFrom, targetId)) {
            throw BusinessRuleException(
                "같은 제품·공정에 같은 적용일의 목표가 이미 있습니다. [$product / $wcCd / $applyFrom]"
            )
        }

        dayTargetRepository.update(targetId, applyFrom, targetQty, request.remark, principal.userId)
        return mapOf("targetId" to targetId)
    }

    /** 일목표를 삭제한다. */
    @Transactional
    fun delete(targetId: Long): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.PROD_DAILY)

        dayTargetRepository.findById(targetId)
            ?: throw ResourceNotFoundException("일목표를 찾을 수 없습니다. [targetId=$targetId]")

        val deleted = dayTargetRepository.delete(targetId)
        return mapOf("targetId" to targetId, "deletedCnt" to deleted)
    }

    private fun required(value: String?, field: String): String =
        value?.trim()?.takeIf { it.isNotBlank() }
            ?: throw InvalidParameterException("필수 항목입니다.", field)

    private fun requiredDate(value: String?): LocalDate =
        DateUtils.parseDate(
            value?.trim()?.takeIf { it.isNotBlank() }
                ?: throw InvalidParameterException("적용 시작일은 필수입니다.", "applyFrom"),
            "applyFrom"
        )

    private fun requiredQty(value: Long?): Long {
        val qty = value ?: throw InvalidParameterException("일목표 수량은 필수입니다.", "targetQty")
        if (qty < 0) throw InvalidParameterException("일목표는 0 이상이어야 합니다.", "targetQty")
        return qty
    }

    /**
     * 모르는 제품 코드를 막는다.
     *
     * 실적의 품목 코드(`item_cd`)로도 목표를 잡을 수 있어야 한다 — 품목 매핑이 없는
     * 실적은 양식 본문에 `item_cd` 로 올라오기 때문이다. 둘 다 아니면 404 다.
     */
    private fun requireKnownProduct(product: String) {
        val plantCd = appProperties.defaultPlantCd
        if (productionRepository.existsProductModel(product)) return
        if (productionRepository.existsMappedItem(plantCd, product)) return

        throw ResourceNotFoundException("등록되지 않은 제품 코드입니다. [product=$product]")
    }

    /** 모르는 공정 코드를 막는다. */
    private fun requireKnownProcess(plantCd: String, wcCd: String) {
        dashboardProcessRepository.findProcessInfo(plantCd, wcCd)
            ?: throw ResourceNotFoundException("등록되지 않은 공정 코드입니다. [processId=$wcCd]")
    }
}
