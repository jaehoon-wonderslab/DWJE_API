package com.dwje.api.service

import com.dwje.api.common.response.PageMeta
import com.dwje.api.common.util.DataField
import com.dwje.api.common.util.DateUtils
import com.dwje.api.common.util.MaskingSupport
import com.dwje.api.common.util.MenuId
import com.dwje.api.common.util.PageRequestParam
import com.dwje.api.config.AppProperties
import com.dwje.api.repository.ScrapReportRepository
import org.springframework.stereotype.Service

/**
 * 폐기 전표 조회 서비스 (RP-06)
 *
 * ## 문서 관리 제거 후 남은 것 (2026-09-04)
 * 폐기 보고서 문서 흐름(초안 생성·수기 행 추가·집계·단가 적용·결재선·검토 요청·발행·
 * 상세 조회·목록·출력·인쇄)은 `ax.tb_rpt_doc` / `ax.tb_rpt_scrap_row` 와 함께 사라졌다.
 *
 * 남는 것은 **MES 폐기 전표 조회**뿐이다 — 문서가 아니라 MES 실적을 그대로 읽는 조회다.
 *
 * 주의: 폐기 보고서에 손으로 행을 추가하던 기능은 저장할 곳이 없어졌다.
 * 대체 저장소가 필요한지는 기획 확인 대기 사항이다.
 */
@Service
class ScrapReportService(
    private val scrapReportRepository: ScrapReportRepository,
    private val authorizationService: AuthorizationService,
    private val appProperties: AppProperties
) {

    /**
     * MES 폐기 전표를 조회한다. (No.115)
     *
     * @param from         조회 시작일 (미지정 시 60일 전)
     * @param to           조회 종료일 (미지정 시 오늘)
     * @param processId    공정 코드
     * @param modelCd      제품 모델 코드
     * @param defectTypeCd 불량 유형 코드
     * @param originType   발생 구분
     */
    fun getMesVouchers(
        from: String?,
        to: String?,
        processId: String?,
        modelCd: String?,
        defectTypeCd: String?,
        originType: String?,
        page: Int?,
        size: Int?
    ): Triple<List<Map<String, Any?>>, PageMeta, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.RPT_SCRAP_NEW)

        val (fromDate, toDate) = DateUtils.periodOf(from, to, 60)
        val plantCd = appProperties.defaultPlantCd

        // size=0 은 전건 조회 규약이다. 폐기 보고서 양식은 전표를 모두 받아
        // 모델별·공정별로 묶어야 하므로 잘라 주면 합계가 어긋난다.
        val paging = PageRequestParam.ofAllowAll(page, size)

        val total = scrapReportRepository.countMesVouchers(
            plantCd, fromDate, toDate, processId, modelCd, defectTypeCd, originType
        )
        val rows = scrapReportRepository.findMesVouchers(
            plantCd, fromDate, toDate, processId, modelCd, defectTypeCd, originType,
            limit = paging.limitOrNull ?: total.toInt().coerceAtLeast(1),
            offset = paging.offset
        )

        val qtyAllowed = mask.check(DataField.QTY)
        val masked = if (qtyAllowed) rows else rows.map { it + mapOf("qty" to null) }

        val meta = if (paging.isAll) PageMeta.all(total) else PageMeta.of(paging.page, paging.size, total)
        return Triple(masked, meta, mask)
    }
}
