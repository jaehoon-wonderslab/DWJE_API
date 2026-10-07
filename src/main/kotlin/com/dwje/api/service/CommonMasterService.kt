package com.dwje.api.service

import com.dwje.api.common.response.PageMeta
import com.dwje.api.common.util.CustomerFilterGuard
import com.dwje.api.common.util.DataField
import com.dwje.api.common.util.MaskingSupport
import com.dwje.api.common.util.PageRequestParam
import com.dwje.api.common.util.SortResolver
import com.dwje.api.common.util.WorkcenterNames
import com.dwje.api.config.AppProperties
import com.dwje.api.repository.CommonMasterRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 공통 코드 · 기준정보 조회 서비스 (CM-05, DB-02)
 *
 * 전 부서가 호출하는 조회 전용 API 로, 고객사·금형 항목은 데이터 접근 권한에 따라 마스킹한다.
 */
@Service
class CommonMasterService(
    private val commonMasterRepository: CommonMasterRepository,
    private val authorizationService: AuthorizationService,
    private val appProperties: AppProperties
) {

    /** 제품 목록 정렬 허용 항목 — 요청 필드명 to 실제 SQL 표현식 */
    private val productSortColumns = mapOf(
        "rank" to "p.rank_no",
        "name" to "p.model_cd",
        "family" to "f.rank_no",
        "seq" to "p.seq_in_family",
        "createdAt" to "item_date.created_at",
        "updatedAt" to "item_date.updated_at"
    )

    /**
     * 공통코드 조회 (No.7)
     *
     * @param groupCd 코드 그룹 (비가동 표준분류·불량유형·상태값 등)
     */
    @Transactional(readOnly = true)
    fun getCodes(groupCd: String?): List<Map<String, Any?>> =
        commonMasterRepository.findCodes(groupCd)

    /**
     * 공정 목록 조회 (No.8) — Press / A Plating / B Plating / Coating
     */
    @Transactional(readOnly = true)
    fun getProcesses(): List<Map<String, Any?>> =
        commonMasterRepository.findProcesses(appProperties.defaultPlantCd)

    /**
     * 설비 목록 조회 (No.9)
     *
     * @param processId 공정 코드
     * @param keyword   설비코드/설비명 검색어
     */
    @Transactional(readOnly = true)
    fun getEquipments(processId: String?, keyword: String?, factory: String? = null, kind: String? = null): List<Map<String, Any?>> {
        // 종류를 먼저 검사한다 — 알 수 없는 값을 조용히 무시하면 화면이 거른 줄 알고 전체 설비를 프레스로 읽는다
        val kindWord = equipmentKindWord(kind)
        return mergeEquipments(commonMasterRepository.findEquipments(appProperties.defaultPlantCd, processId, keyword), factory, kindWord)
    }

    /**
     * 설비 종류 → 작업장 이름에 들어 있어야 하는 낱말 (2026-10-07). 비면 null(조건 없음), 모르는 값은 400.
     * 설비 마스터에 종류 열이 없어 작업장 이름(`A-프레스 작업장(M-1공장)`)으로 가른다 — 공장 표기와 같은 방식.
     */
    internal fun equipmentKindWord(kind: String?): String? {
        val k = kind?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return EQUIPMENT_KIND_WORDS[k.uppercase()]
            ?: throw com.dwje.api.common.exception.InvalidParameterException(
                "설비 종류는 ${EQUIPMENT_KIND_WORDS.keys.joinToString("·")} 만 쓸 수 있습니다. [$kind]", "kind")
    }

    /**
     * 제품 목록 조회 (No.10) — 제품 선택 팝업의 검색·필터·정렬을 지원한다.
     *
     * 고객사(customer)는 데이터 접근 권한이 없으면 null 로 마스킹한다.
     *
     * @return 제품 목록 · 페이징 정보 · 마스킹 지원 객체
     */
    @Transactional(readOnly = true)
    fun getProducts(
        keyword: String?,
        familyCd: String?,
        customerCd: String?,
        projectCd: String?,
        sort: String?,
        page: Int?,
        size: Int?
    ): Triple<List<Map<String, Any?>>, PageMeta, MaskingSupport> {
        val paging = PageRequestParam.of(page, size)
        val orderBy = SortResolver.resolve(sort, productSortColumns, "f.rank_no, p.seq_in_family")
        val mask = authorizationService.masking()
        // 고객사 권한이 없으면 고객사 조건으로 거를 수 없다 — 걸러진 행으로 고객사를 짐작하게 된다 (2026-10-03)
        CustomerFilterGuard.require(customerCd, mask.allowed(DataField.CUSTOMER))

        val total = commonMasterRepository.countProducts(keyword, familyCd, customerCd, projectCd)
        val rows = commonMasterRepository.findProducts(
            keyword, familyCd, customerCd, projectCd, orderBy, paging.limit, paging.offset
        )

        // 고객사 정보는 데이터 접근 권한(customer) 보유자에게만 노출한다.
        val masked = rows.map { row ->
            val m = row.toMutableMap()
            mask.applyTo(m, mapOf("customer" to DataField.CUSTOMER, "customerCd" to DataField.CUSTOMER))
            m.toMap()
        }

        return Triple(masked, PageMeta.of(paging.page, paging.size, total), mask)
    }

    /**
     * 고객사 목록 조회 (No.11)
     *
     * 고객사 데이터 접근 권한이 없으면 빈 목록을 반환한다. (원본을 응답에 포함하지 않는다)
     */
    @Transactional(readOnly = true)
    fun getCustomers(): Pair<List<Map<String, Any?>>, MaskingSupport> {
        val mask = authorizationService.masking()
        if (!mask.check(DataField.CUSTOMER)) {
            return emptyList<Map<String, Any?>>() to mask
        }
        return commonMasterRepository.findCustomers() to mask
    }

    /**
     * 금형 목록 조회 (No.13)
     *
     * 금형·설비 상세(mold) 데이터 접근 권한이 없으면 빈 목록을 반환한다.
     *
     * @param eqptCd 설비 코드
     */
    @Transactional(readOnly = true)
    fun getMolds(eqptCd: String?): Pair<List<Map<String, Any?>>, MaskingSupport> {
        val mask = authorizationService.masking()
        if (!mask.check(DataField.MOLD)) {
            return emptyList<Map<String, Any?>>() to mask
        }
        return commonMasterRepository.findMolds(appProperties.defaultPlantCd, eqptCd) to mask
    }

    /**
     * 실적 데이터 보유 기간 조회
     *
     * 화면의 기준일 기본값을 오늘로 두면, 당일 실적이 아직 올라오지 않은 시각(및 이관이 밀린 환경)에는
     * 대시보드가 전부 0 으로 보인다. 화면이 이 범위를 받아 `toDate` 를 기본 기준일로 쓰도록 한다.
     *
     * 공정(`processId`)을 함께 주면 그 공정의 실적 보유 구간을 반환한다.
     * 전사 마지막 실적일에 특정 공정은 실적이 없을 수 있다.
     * 예를 들어 2026-08-30 에는 W120(3공장 프레스)만 실적이 있고 W110(1공장 프레스)은 없어,
     * 전사 `toDate` 를 그대로 기준일로 쓰면 W110 화면이 통째로 비어 보인다.
     *
     * @param plantCd   사업장 코드 (미지정 시 기본 사업장)
     * @param processId 공정 코드 (미지정 시 전 공정 기준)
     */
    @Transactional(readOnly = true)
    fun getProductionDateRange(plantCd: String?, processId: String?): Map<String, Any?> {
        val plant = plantCd?.trim()?.takeIf { it.isNotEmpty() } ?: appProperties.defaultPlantCd
        val process = processId?.trim()?.takeIf { it.isNotEmpty() }
        val range = commonMasterRepository.findProductionDateRange(plant, process)
        return mapOf(
            "plantCd" to plant,
            "processId" to process,
            "fromDate" to range["fromDate"],
            "toDate" to range["toDate"]
        )
    }

    /**
     * 설비 목록 정리 (2026-10-03)
     * 1. [factory] 를 주면 작업장 이름의 공장 표기([WorkcenterNames.plantOf])가 같은 행만 남긴다 — 표기 없는 작업장·작업장 없는 설비는 빠진다
     *    [kindWord] 를 주면(kind=PRESS → 「프레스」) 작업장 이름에 그 낱말이 든 행만 남긴다(2026-10-07) — factory 와 함께면 둘 다 맞는 작업장
     * 2. 설비코드 기준 한 행으로 합친다 — 작업장 2곳에 걸린 설비가 두 행으로 와서 화면이 같은 key 로 그리다 오류가 났다(로컬 21대).
     *    wcCd·wcNm 은 첫 작업장(작업장 코드순), wcCds·wcNms 에 걸린 작업장 전부.
     */
    internal fun mergeEquipments(rows: List<Map<String, Any?>>, factory: String?, kindWord: String? = null): List<Map<String, Any?>> {
        val plant = factory?.trim()?.takeIf { it.isNotEmpty() }
        // 작업장 행 단위로 거른 뒤 합친다 — 공장·종류가 모두 맞는 작업장만 wcCds 에 남는다
        val kept = rows.filter { r ->
            val wcNm = r["wcNm"] as String?
            (plant == null || WorkcenterNames.plantOf(wcNm) == plant) && (kindWord == null || wcNm?.contains(kindWord) == true)
        }
        return kept.groupBy { it["eqptCd"] }.values.map { same ->
            val first = same.first()
            if (same.size == 1) first + mapOf("wcCds" to listOfNotNull(first["wcCd"]), "wcNms" to listOfNotNull(first["wcNm"]))
            else first + mapOf("wcCds" to same.mapNotNull { it["wcCd"] }.distinct(), "wcNms" to same.mapNotNull { it["wcNm"] }.distinct())
        }
    }

    companion object {
        /** 설비 검색 종류 → 작업장 이름 낱말 (kind 파라미터) */
        private val EQUIPMENT_KIND_WORDS = mapOf("PRESS" to "프레스")
    }
}
