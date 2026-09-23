package com.dwje.api.service

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.security.UserContext
import com.dwje.api.common.util.DailyReportPeriod
import com.dwje.api.common.util.DataField
import com.dwje.api.common.util.DateUtils
import com.dwje.api.common.util.MaskingSupport
import com.dwje.api.common.util.MenuId
import com.dwje.api.common.util.TimeWindow
import com.dwje.api.config.AppProperties
import com.dwje.api.model.request.DailyReportRowEntry
import com.dwje.api.repository.DailyDecisionRepository
import com.dwje.api.repository.DailyDecisionRow
import com.dwje.api.repository.DayTargetRepository
import com.dwje.api.repository.ReportRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * 일일 생산현황 보고 서비스 (PR-03)
 *
 * 집계 구간은 전일 20:00 ~ 당일 08:00 이다. (야간 교대 시작 ~ 주간 교대 시작)
 * 화면은 대상일 하나만 고르고 구간 규칙은 서버가 갖는다. — [DailyReportPeriod]
 *
 * ## 문서 관리가 없다 (2026-09-04)
 * 초안·버전·확정·반려·결재는 제거되었다. 보고서는 저장되는 문서가 아니라
 * **조회 조건으로 매번 만들어 내려받는 산출물**이고, 남는 것은 다운로드 이력뿐이다.
 * 무엇을 어떤 조건으로 내려받았는지는 `ax.tb_rpt_download_log.params_json` 에 남는다.
 *
 * 다만 아침회의에서 정한 제품별 일목표·판정·담당·기한은 산출물이 아니라 사람이
 * 남기는 결정이라 따로 저장한다. 키는 문서가 아니라 (대상일, 제품) 이다.
 *
 * 접근 : 화면 권한 `prod-daily`(ax.tb_sys_dept_menu_perm) · 값 마스킹 : 데이터 권한(ax.tb_sys_dept_data_perm)
 */
@Service
class DailyReportService(
    private val reportRepository: ReportRepository,
    private val dailyDecisionRepository: DailyDecisionRepository,
    private val dayTargetRepository: DayTargetRepository,
    private val authorizationService: AuthorizationService,
    private val appProperties: AppProperties
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 보고서 양식 본문을 조회한다. (제품 × 공정)
     *
     * 주간 실적은 두 값으로 낸다 — `weekQty` 는 그 주 **보고 구간들의 합**이고,
     * `weekQtyAllShift` 는 주간 교대까지 포함한 연속 구간의 합이다.
     * 주간목표가 일목표 × `weekDays` 라서 달성률에는 `weekQty` 만 짝이 맞는다.
     *
     * `processId` 를 주지 않으면 **프레스 작업장 전체**(`app.press-workcenters`)를 뜻한다.
     * 전 공정이 아니다 — 프레스 양식에 용접·도금 제품이 섞여 올라오면 안 된다.
     *
     * `targetQty` 는 **저장값 > 마스터 > null** 순으로 정한다 —
     * 작성자가 그날만 목표를 달리 잡으면(`tb_prod_daily_decision`) 그 값이
     * 마스터(`tb_prod_day_target`)를 덮어쓴다. `targetQtyOrigin` 으로 출처를 알린다.
     *
     * 둘 다 없으면 null 이다. 공정 목표를 제품 실적 비율로 안분하지 않는다 —
     * 근거 없는 숫자가 목표처럼 보인다.
     *
     * @param targetDate 대상 일자 (미지정 시 오늘)
     * @param processId  공정 코드 (미지정 시 프레스 작업장 전체)
     */
    fun getSheet(targetDate: String?, processId: String?): Pair<Map<String, Any?>, MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.PROD_DAILY)
        val target = DateUtils.parseDate(targetDate, "targetDate", LocalDate.now())

        val day = DailyReportPeriod.of(target)
        val weekDays = DailyReportPeriod.weekDays(target)
        val reportWindows = DailyReportPeriod.weekWindows(target)

        val requested = processId?.trim()?.takeIf { it.isNotBlank() }
        val processCds = requested?.let { listOf(it) } ?: appProperties.pressWorkcenters

        val rows = reportRepository.findDailySheetRows(
            plantCd = appProperties.defaultPlantCd,
            window = day,
            reportWindows = reportWindows,
            processCds = processCds
        )

        // 회의 결과가 저장돼 있으면 얹는다.
        val saved = dailyDecisionRepository.findRows(target)

        // 목표 밑값은 마스터에서 가져온다. 작성자가 그날만 달리 잡은 값이 있으면 그쪽이 이긴다.
        val master = dayTargetRepository.findEffectiveTargets(
            appProperties.defaultPlantCd, target, processCds
        )

        val qtyAllowed = mask.check(DataField.QTY)
        val masked = rows.map { r ->
            val own = saved[r["product"] as? String]
            // 저장값 > 마스터 > 없음(null)
            val targetQty = (own?.get("targetQty") as? Long)
                ?: master["${r["product"]}|${r["processId"]}"]

            r + mapOf(
                "qty" to if (qtyAllowed) r["qty"] else null,
                "okQty" to if (qtyAllowed) r["okQty"] else null,
                "ngQty" to if (qtyAllowed) r["ngQty"] else null,
                "weekQty" to if (qtyAllowed) r["weekQty"] else null,
                "weekQtyAllShift" to if (qtyAllowed) r["weekQtyAllShift"] else null,
                "weekDays" to weekDays,
                // 작성자가 넣은 값이 있으면 그것, 없으면 마스터, 둘 다 없으면 null.
                "targetQty" to if (qtyAllowed) targetQty else null,
                // 목표가 어디서 온 값인지 화면이 구분해야 한다 — 마스터 값은 밑값이고
                // 작성자가 덮어쓸 수 있다. 출처를 숨기면 누가 정한 목표인지 알 수 없다.
                "targetQtyOrigin" to when {
                    own?.get("targetQty") != null -> "MANUAL"
                    master.containsKey("${r["product"]}|${r["processId"]}") -> "MASTER"
                    else -> null
                },
                // 주간목표는 일목표 × 주간 일수다. 일목표가 없으면 낼 수 없다.
                "weekTargetQty" to if (qtyAllowed) targetQty?.let { it * weekDays } else null,
                "decision" to own?.get("decision"),
                "dri" to own?.get("dri"),
                "due" to own?.get("due")
            )
        }

        return mapOf(
            "targetDate" to target.format(DateUtils.DATE),
            "periodFrom" to day.from.format(DateUtils.DATETIME),
            "periodTo" to day.toExclusive.format(DateUtils.DATETIME),
            "weekFrom" to reportWindows.first().from.format(DateUtils.DATETIME),
            "weekDays" to weekDays,
            "processId" to requested,
            "processCds" to processCds,
            "rows" to masked
        ) to mask
    }

    /**
     * 아침회의 결과(제품별 일목표·판정·담당·기한)를 저장한다.
     *
     * 보낸 제품만 갱신하므로 화면이 한 줄만 고쳐 보낼 수 있다.
     * 문서가 없으므로 대상일이 키다 — 확정 상태 같은 것은 없고 언제든 고칠 수 있다.
     */
    @Transactional
    fun saveRows(targetDate: String?, rows: List<DailyReportRowEntry>): Map<String, Any?> {
        val principal = UserContext.current()
        authorizationService.requireMenu(MenuId.PROD_DAILY)
        val target = DateUtils.parseDate(targetDate, "targetDate", LocalDate.now())

        if (rows.isEmpty()) {
            throw InvalidParameterException("저장할 항목이 없습니다.", "rows")
        }

        // 같은 제품을 두 번 보내면 어느 값이 남는지 순서에 달려 조용히 갈린다.
        val duplicated = rows.mapNotNull { it.product?.trim()?.takeIf { p -> p.isNotBlank() } }
            .groupingBy { it }.eachCount()
            .filterValues { it > 1 }.keys
        if (duplicated.isNotEmpty()) {
            throw InvalidParameterException(
                "같은 제품이 두 번 이상 들어 있습니다. [${duplicated.joinToString()}]", "rows"
            )
        }

        val parsed = rows.mapIndexed { idx, r ->
            val product = r.product?.trim()?.takeIf { it.isNotBlank() }
                ?: throw InvalidParameterException("제품 코드는 필수입니다. [rows[$idx]]", "product")
            if (r.targetQty != null && r.targetQty < 0) {
                throw InvalidParameterException("일목표는 0 이상이어야 합니다. [rows[$idx]]", "targetQty")
            }

            DailyDecisionRow(
                product = product,
                targetQty = r.targetQty,
                decision = r.decision?.trim()?.takeIf { it.isNotBlank() },
                dri = r.dri?.trim()?.takeIf { it.isNotBlank() },
                dueDate = r.due?.trim()?.takeIf { it.isNotBlank() }
                    ?.let { DateUtils.parseDate(it, "due") }
            )
        }

        dailyDecisionRepository.upsertRows(target, parsed, principal.userId)
        log.info("일일 생산현황 보고 회의 결과 저장 : targetDate={} 제품={}종", target, parsed.size)

        return mapOf("targetDate" to target.format(DateUtils.DATE), "savedCnt" to parsed.size)
    }
}
