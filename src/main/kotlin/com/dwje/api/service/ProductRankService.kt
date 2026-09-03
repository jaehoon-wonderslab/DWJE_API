package com.dwje.api.service

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
    fun updateFamilyOrder(orders: List<Map<String, Any?>>): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.SYS_RANK)

        val orderMap = orders.mapNotNull { o ->
            val familyCd = o["familyCd"] as? String ?: return@mapNotNull null
            val rank = (o["rank"] as? Number)?.toInt() ?: return@mapNotNull null
            familyCd to rank
        }.toMap()

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
    fun updateProductOrder(familyCd: String, orders: List<Map<String, Any?>>): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.SYS_RANK)

        val familyId = productRankRepository.findFamilyId(familyCd)
            ?: throw ResourceNotFoundException("제품군을 찾을 수 없습니다. [$familyCd]")

        val orderMap = orders.mapNotNull { o ->
            val code = o["code"] as? String ?: return@mapNotNull null
            val seq = (o["seq"] as? Number)?.toInt() ?: return@mapNotNull null
            code to seq
        }.toMap()

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
