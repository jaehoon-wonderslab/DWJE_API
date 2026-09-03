package com.dwje.api.service

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.model.request.ProductOrderEntry
import com.dwje.api.model.request.FamilyOrderEntry
import com.dwje.api.common.exception.ResourceNotFoundException
import com.dwje.api.common.response.PageMeta
import com.dwje.api.common.util.DataField
import com.dwje.api.common.util.MaskingSupport
import com.dwje.api.common.util.MenuId
import com.dwje.api.common.util.PageRequestParam
import com.dwje.api.repository.ProductRankRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 제품군 순위 관리 서비스 (SY-07)
 *
 * 여기서 정한 순위는 대시보드 Top N 제품 조회(No.42)의 기준이 된다.
 *
 * 접근 부서 : 전산팀 · 경영진 · 통합관리자
 */
@Service
class ProductRankService(
    private val productRankRepository: ProductRankRepository,
    private val authorizationService: AuthorizationService,
    private val auditLogService: AuditLogService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 제품군 순위 조회 (No.179) */
    @Transactional(readOnly = true)
    fun getFamilies(): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.SYS_RANK)
        return mapOf("items" to productRankRepository.findFamilies())
    }

    /**
     * 제품군 순위 변경 (No.180)
     *
     * 순위 변경 후 제품 전체 순위를 재계산하고 이력을 남긴다.
     *
     * @param orders familyCd to rank 매핑
     */
    @Transactional
    fun updateFamilyOrder(orders: List<FamilyOrderEntry>): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.SYS_RANK)

        // 예전에는 Map 항목에서 키·타입이 어긋난 것을 조용히 버렸다(mapNotNull).
        // 10개를 보냈는데 3개만 반영되고도 200 이 나가서, 화면은 성공으로 읽고
        // 순서는 일부만 바뀐다. 어긋난 항목이 있으면 아예 받지 않는다.
        if (orders.isEmpty()) {
            throw InvalidParameterException("변경할 제품군 순위를 지정해 주세요.", "orders")
        }
        orders.forEachIndexed { i, o ->
            if (o.familyCd.isNullOrBlank()) {
                throw InvalidParameterException("제품군 코드가 없습니다. [orders[$i]]", "orders")
            }
            if (o.rank == null) {
                throw InvalidParameterException("순위가 없습니다. [orders[$i].familyCd=${o.familyCd}]", "orders")
            }
        }

        val orderMap = orders.associate { it.familyCd!!.trim() to it.rank!! }

        val changed = productRankRepository.updateFamilyOrders(orderMap, principal.userId)
        val recalculated = productRankRepository.recalculateProductRanks(principal.userId)

        productRankRepository.insertRankLog(
            actCd = "FAMILY",
            familyId = null,
            productId = null,
            detail = "제품군 순위 변경 — ${orderMap.entries.joinToString(", ") { "${it.key}→${it.value}" }}",
            actorUserId = principal.userId,
            actorDeptNm = principal.deptName
        )
        auditLogService.record(
            logType = "AUTO_GEN",
            menuId = MenuId.SYS_RANK,
            targetDesc = "제품군 순위 변경",
            remark = "변경 ${changed}건 · 제품 순위 재계산 ${recalculated}건"
        )

        log.info("제품군 순위 변경 : 변경={}건 재계산={}건", changed, recalculated)
        return mapOf("success" to true, "changedCnt" to changed, "recalculatedCnt" to recalculated)
    }

    /** 제품군 내 제품 순서 조회 (No.181) */
    @Transactional(readOnly = true)
    fun getProductsInFamily(familyCd: String): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.SYS_RANK)

        productRankRepository.findFamilyId(familyCd)
            ?: throw ResourceNotFoundException("제품군을 찾을 수 없습니다. [$familyCd]")

        val customerAllowed = mask.check(DataField.CUSTOMER)
        val items = productRankRepository.findProductsInFamily(familyCd).map {
            if (customerAllowed) it else it + mapOf("customer" to null)
        }

        return mapOf("familyCd" to familyCd, "items" to items) to mask
    }

    /**
     * 제품군 내 제품 순서 변경 (No.182)
     *
     * @param orders code to seq 매핑
     */
    @Transactional
    fun updateProductOrder(familyCd: String, orders: List<ProductOrderEntry>): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.SYS_RANK)

        val familyId = productRankRepository.findFamilyId(familyCd)
            ?: throw ResourceNotFoundException("제품군을 찾을 수 없습니다. [$familyCd]")

        if (orders.isEmpty()) {
            throw InvalidParameterException("변경할 제품 순서를 지정해 주세요.", "orders")
        }
        orders.forEachIndexed { i, o ->
            if (o.code.isNullOrBlank()) {
                throw InvalidParameterException("제품 코드가 없습니다. [orders[$i]]", "orders")
            }
            if (o.seq == null) {
                throw InvalidParameterException("순서가 없습니다. [orders[$i].code=${o.code}]", "orders")
            }
        }

        val orderMap = orders.associate { it.code!!.trim() to it.seq!! }

        val changed = productRankRepository.updateProductOrders(familyCd, orderMap, principal.userId)
        val recalculated = productRankRepository.recalculateProductRanks(principal.userId)

        productRankRepository.insertRankLog(
            actCd = "PRODUCT",
            familyId = familyId,
            productId = null,
            detail = "제품군 [$familyCd] 내 제품 순서 변경 (${changed}건)",
            actorUserId = principal.userId,
            actorDeptNm = principal.deptName
        )

        return mapOf("success" to true, "changedCnt" to changed, "recalculatedCnt" to recalculated)
    }

    /** 기본 순서 복원 (No.183) */
    @Transactional
    fun resetOrder(): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.SYS_RANK)

        val restored = productRankRepository.resetToDefaultOrder(principal.userId)
        val recalculated = productRankRepository.recalculateProductRanks(principal.userId)

        productRankRepository.insertRankLog(
            actCd = "RESET",
            familyId = null,
            productId = null,
            detail = "기본 순서 복원 (${restored}건)",
            actorUserId = principal.userId,
            actorDeptNm = principal.deptName
        )

        return mapOf("success" to true, "restoredCnt" to restored, "recalculatedCnt" to recalculated)
    }

    /** 현재 순위 상위 N 조회 (No.184) */
    @Transactional(readOnly = true)
    fun getTopRanking(topN: Int): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.SYS_RANK)

        val customerAllowed = mask.check(DataField.CUSTOMER)
        val items = productRankRepository.findTopRanking(topN.coerceIn(1, 500)).map {
            if (customerAllowed) it else it + mapOf("customer" to null)
        }

        return mapOf("items" to items) to mask
    }

    /** 순위 변경 이력 (No.185) */
    @Transactional(readOnly = true)
    fun getRankLogs(page: Int?, size: Int?): Pair<List<Map<String, Any?>>, PageMeta> {
        authorizationService.requireMenu(MenuId.SYS_RANK)
        val paging = PageRequestParam.of(page, size)

        val total = productRankRepository.countRankLogs()
        val rows = productRankRepository.findRankLogs(paging.limit, paging.offset)

        return rows to PageMeta.of(paging.page, paging.size, total)
    }
}
