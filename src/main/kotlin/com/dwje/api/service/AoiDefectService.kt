package com.dwje.api.service

import com.dwje.api.common.util.MenuId
import com.dwje.api.config.AppProperties
import com.dwje.api.repository.AoiDefectRepository
import org.springframework.stereotype.Service

/**
 * AOI 불량 판정 화면 보조 서비스 (요구 9)
 *
 * 불량 목록·상세와 NAS 이미지 프록시는 2026-09-23 에 뺐다 — 웹이 부르지 않았고, 이미지 매핑 표
 * `ax.tb_aoi_defect_image` 를 V42 가 지운다. 화면 필터용 AOI 설비 목록(`/quality/aoi/defects/equipments`)만 남는다.
 */
@Service
class AoiDefectService(
    private val repository: AoiDefectRepository,
    private val authorizationService: AuthorizationService,
    private val appProperties: AppProperties
) {

    /** 화면 필터용 AOI 설비 목록 */
    fun getEquipments(): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.QC_AOI)
        return mapOf("items" to repository.findAoiEquipments(appProperties.defaultPlantCd))
    }
}
