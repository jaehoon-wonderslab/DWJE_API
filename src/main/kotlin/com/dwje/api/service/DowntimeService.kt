package com.dwje.api.service

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.exception.ResourceNotFoundException
import com.dwje.api.common.response.PageMeta
import com.dwje.api.common.util.DateUtils
import com.dwje.api.common.util.MenuId
import com.dwje.api.common.util.PageRequestParam
import com.dwje.api.config.AppProperties
import com.dwje.api.model.request.DowntimeCreateRequest
import com.dwje.api.model.request.DowntimeUpdateRequest
import com.dwje.api.repository.DowntimeRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * 비가동 관리 서비스 (PR-05)
 *
 * IoT 가 감지한 설비 정지 구간에 담당자가 사유를 등록하고,
 * Agent 는 과거 이력을 근거로 사유 후보를 제안한다.
 *
 * 접근 부서 : 생산관리팀 · 제조팀 · 통합관리자
 */
@Service
class DowntimeService(
    private val downtimeRepository: DowntimeRepository,
    private val authorizationService: AuthorizationService,
    private val auditLogService: AuditLogService,
    private val appProperties: AppProperties
) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** 요청 본문의 일시 표기 형식 후보 */
        private val DATETIME_FORMATS = listOf(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME
        )

        /** Agent 사유 후보 제안 건수 */
        private const val SUGGESTION_LIMIT = 3
    }

    /**
     * 비가동 요약 (No.68)
     */
    @Transactional(readOnly = true)
    fun getSummary(date: String?): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.PROD_DOWN)
        val target = DateUtils.parseDate(date, "date", LocalDate.now())
        val plantCd = appProperties.defaultPlantCd

        val summary = downtimeRepository.findSummary(plantCd, target).toMutableMap()
        summary["byReason"] = downtimeRepository.findSummaryByReason(plantCd, target)
        summary["date"] = target.format(DateUtils.DATE)

        return summary.toMap()
    }

    /**
     * 비가동 이력 조회 (No.69)
     */
    @Transactional(readOnly = true)
    fun getDowntimes(
        date: String?,
        eqptCd: String?,
        reasonCd: String?,
        registered: Boolean?,
        page: Int?,
        size: Int?
    ): Pair<List<Map<String, Any?>>, PageMeta> {
        authorizationService.requireMenu(MenuId.PROD_DOWN)
        val target = DateUtils.parseDate(date, "date", LocalDate.now())
        val plantCd = appProperties.defaultPlantCd
        val paging = PageRequestParam.of(page, size)

        val total = downtimeRepository.countDowntimes(plantCd, target, eqptCd, reasonCd, registered)
        val rows = downtimeRepository.findDowntimes(
            plantCd, target, eqptCd, reasonCd, registered, paging.limit, paging.offset
        )

        return rows to PageMeta.of(paging.page, paging.size, total)
    }

    /**
     * Agent 사유 후보 제안 (No.70)
     *
     * 동일 설비의 최근 90일 이력 중 유사 시간대 패턴을 빈도순으로 제시한다.
     *
     * @param eqptCd 설비 코드
     * @param stopAt 정지 시각
     */
    @Transactional(readOnly = true)
    fun getReasonSuggestions(eqptCd: String, stopAt: String?): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.PROD_DOWN)
        val stopAtTime = parseDateTime(stopAt, "stopAt") ?: LocalDateTime.now()

        return mapOf(
            "eqptCd" to eqptCd,
            "stopAt" to stopAtTime.format(DateUtils.DATETIME),
            "candidates" to downtimeRepository.findReasonSuggestions(
                appProperties.defaultPlantCd, eqptCd, stopAtTime, SUGGESTION_LIMIT
            )
        )
    }

    /**
     * 비가동 사유 등록 (No.71)
     *
     * IoT 가 이미 감지한 정지 구간이 있으면 사유만 채우고, 없으면 수기 등록으로 생성한다.
     */
    @Transactional
    fun createDowntime(request: DowntimeCreateRequest): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.PROD_DOWN)
        val plantCd = appProperties.defaultPlantCd

        val stopAt = parseDateTime(request.stopAt, "stopAt")
            ?: throw InvalidParameterException("정지 시각 형식이 올바르지 않습니다.", "stopAt")
        val resumeAt = parseDateTime(request.resumeAt, "resumeAt")

        // 재가동 시각이 정지 시각보다 빠를 수 없다.
        if (resumeAt != null && resumeAt.isBefore(stopAt)) {
            throw InvalidParameterException("재가동 시각이 정지 시각보다 빠릅니다.", "resumeAt")
        }

        val wcCd = downtimeRepository.findWorkcenterOfEquipment(plantCd, request.eqptCd)
        val downtimeId = downtimeRepository.upsertDowntime(
            plantCd = plantCd,
            eqptCd = request.eqptCd,
            wcCd = wcCd,
            stopAt = stopAt,
            resumeAt = resumeAt,
            reasonCd = request.reasonCd,
            remark = request.remark,
            actor = principal.userId
        )

        log.info("비가동 사유 등록 : downtimeId={} eqpt={} reason={}", downtimeId, request.eqptCd, request.reasonCd)
        return mapOf("downtimeId" to downtimeId)
    }

    /**
     * 비가동 사유 수정 (No.72 — 감사 로그 기록)
     */
    @Transactional
    fun updateDowntime(downtimeId: Long, request: DowntimeUpdateRequest): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.PROD_DOWN)

        val before = downtimeRepository.findDowntime(downtimeId)
            ?: throw ResourceNotFoundException("비가동 이력을 찾을 수 없습니다. [downtimeId=$downtimeId]")

        val resumeAt = parseDateTime(request.resumeAt, "resumeAt")
        val updated = downtimeRepository.updateDowntime(
            downtimeId, request.reasonCd, request.remark, resumeAt, principal.userId
        )
        if (updated == 0) throw ResourceNotFoundException("비가동 이력을 찾을 수 없습니다. [downtimeId=$downtimeId]")

        // 변경 전후를 감사 로그에 남긴다.
        auditLogService.record(
            logType = "AUTO_GEN",
            menuId = MenuId.PROD_DOWN,
            targetDesc = "비가동 사유 수정 [설비=${before["eqptCd"]}, 정지=${before["stopAt"]}]",
            remark = "사유 ${before["reasonCd"]} → ${request.reasonCd ?: before["reasonCd"]}"
        )

        return mapOf("success" to true, "downtimeId" to downtimeId)
    }

    /**
     * 요청 본문의 일시 문자열을 파싱한다. (여러 표기 허용)
     */
    private fun parseDateTime(value: String?, paramName: String): LocalDateTime? {
        val raw = value?.trim()?.takeIf { it.isNotBlank() } ?: return null

        DATETIME_FORMATS.forEach { format ->
            runCatching { return LocalDateTime.parse(raw, format) }
        }
        // 날짜만 넘어온 경우 00:00 으로 해석한다.
        runCatching { return DateUtils.parseDate(raw, paramName).atStartOfDay() }

        throw InvalidParameterException("일시 형식이 올바르지 않습니다(yyyy-MM-dd HH:mm:ss). [$paramName=$raw]", paramName)
    }
}
