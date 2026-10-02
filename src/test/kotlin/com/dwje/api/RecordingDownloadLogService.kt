package com.dwje.api

import com.dwje.api.common.response.PageMeta
import com.dwje.api.config.AppProperties
import com.dwje.api.repository.DownloadLogRepository
import com.dwje.api.service.AuditLogService
import com.dwje.api.service.AuthorizationService
import com.dwje.api.service.DownloadLogService
import com.fasterxml.jackson.databind.ObjectMapper
import org.mockito.Mockito.mock

/**
 * 다운로드 이력 기록을 DB 에 남기지 않는 시험용 기록기.
 *
 * 실제 [DownloadLogService.record] 는 REQUIRES_NEW 라 테스트 롤백 밖에서 커밋되고, 다운로드 이력 표는 지울 수 없다(V62).
 * 그래서 전체 내려받기 시험은 이것으로 기록 인자만 받고, 목록 조회([getLogs])는 실제 빈에 넘긴다.
 */
class RecordingDownloadLogService(private val real: DownloadLogService? = null) : DownloadLogService(
    mock(DownloadLogRepository::class.java), mock(AuditLogService::class.java),
    mock(AuthorizationService::class.java), mock(AppProperties::class.java), ObjectMapper()
) {
    data class Call(
        val reportNm: String, val menuId: String?, val rowCnt: Int, val blindCnt: Int,
        val params: Map<String, Any?>?, val scopeCd: String, val condSummary: String?
    )

    val calls = mutableListOf<Call>()

    override fun record(
        reportId: String?, reportNm: String, menuId: String?, format: String, scope: String?, rowCnt: Int, blindCnt: Int,
        blindCells: Map<String, Int>, fileNm: String?, params: Map<String, Any?>?, fileSize: Long?,
        scopeCd: String, condSummary: String?
    ): Long {
        calls += Call(reportNm, menuId, rowCnt, blindCnt, params, scopeCd, condSummary)
        return calls.size.toLong()
    }

    override fun getLogs(
        from: String?, to: String?, filter: DownloadLogRepository.LogFilter, page: Int?, size: Int?, exporting: Boolean
    ): Pair<List<Map<String, Any?>>, PageMeta> = real!!.getLogs(from, to, filter, page, size, exporting)
}
