package com.dwje.api

import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.common.util.DataField
import com.dwje.api.common.util.MaskingSupport
import com.dwje.api.service.AiBriefingInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

/**
 * sLLM 프롬프트 입력의 마스킹 계약 테스트
 *
 * ## 이건 기능이 아니라 보안이다
 * 마스킹된 값이 프롬프트에 들어가면 모델이 문장으로 풀어 써서 **마스킹이 뚫린다.**
 * 화면에서 가린 수량을 모델이 "어제 2,200만개를 만들었습니다" 로 되돌려 주면
 * 부서별 데이터 접근 권한이 무의미해진다.
 *
 * 부서 권한을 아는 자리는 서버뿐이므로, 프롬프트를 만들기 **전에** 걸러야 한다.
 * 그 필터가 [AiBriefingInput.of] 한 곳에 있고, 이 테스트가 그것을 고정한다.
 *
 * 실제 시드 계정의 권한으로 확인한다 —
 *   10003 이제조 : qty · mold · worker (yield·plan 없음)
 *   10001 김품질 : qty · yield · customer · mold (plan 없음)
 */
class AiBriefingMaskingTest {

    private fun principal(vararg fields: String) = UserPrincipal(
        userId = "10003",
        userName = "이제조",
        deptId = 4,
        deptName = "제조팀",
        deptAbbr = null,
        positionCd = "STAFF",
        plantCd = "PL01",
        superAdmin = false,
        dataPerms = fields.toSet()
    )

    private fun buildInput(mask: MaskingSupport) = AiBriefingInput.of(
        date = "2026-08-28",
        mask = mask,
        totalQty = 21_963_275L,
        okQty = 21_000_000L,
        ngQty = 963_275L,
        defectRate = 4.39,
        yieldRate = 95.61,
        planQty = 20_000_000L,
        defectComposition = listOf(AiBriefingInput.DefectShare("D01", "찍힘", 1000L)),
        anomalyCandidates = listOf(
            AiBriefingInput.AnomalyCandidate("BG-011", "0호기", 50_000L, 500L, 1.0)
        )
    )

    @Test
    @DisplayName("1. 수율 권한이 없으면 불량률·수율·불량 유형이 프롬프트 입력에서 빠진다")
    fun yieldMaskedOut() {
        // 제조팀 = qty 는 있고 yield 는 없다.
        val input = buildInput(MaskingSupport(principal(DataField.QTY, DataField.MOLD, DataField.WORKER)))

        assertNull(input.defectRate, "수율 권한이 없으면 불량률이 프롬프트에 들어가면 안 된다")
        assertNull(input.yieldRate, "수율 권한이 없으면 수율이 프롬프트에 들어가면 안 된다")
        assertTrue(
            input.defectComposition.isEmpty(),
            "불량 유형 구성도 수율 항목이다 — 모델이 문장으로 풀어 쓰면 마스킹이 뚫린다"
        )
        // 권한 있는 항목은 그대로 들어간다.
        assertEquals(21_963_275L, input.totalQty, "수량 권한은 있으므로 수량은 들어가야 한다")
        assertTrue(
            input.maskedFields.contains(DataField.YIELD),
            "가려진 항목을 알려 줘야 프롬프트에 '제공되지 않음' 으로 적을 수 있다"
        )
    }

    @Test
    @DisplayName("2. 계획 권한이 없으면 계획 수량과 달성률이 함께 빠진다")
    fun planMaskedOutTakesRateWithIt() {
        // 품질보증팀 = qty·yield 는 있고 plan 은 없다.
        val input = buildInput(
            MaskingSupport(principal(DataField.QTY, DataField.YIELD, DataField.CUSTOMER, DataField.MOLD))
        )

        assertNull(input.planQty, "계획 권한이 없으면 계획 수량이 프롬프트에 들어가면 안 된다")
        assertNull(
            input.achievementRate,
            "계획이 가려졌으면 달성률도 내지 않아야 한다 — 달성률과 실적으로 계획을 역산할 수 있다"
        )
        assertEquals(4.39, input.defectRate, "수율 권한은 있으므로 불량률은 들어가야 한다")
    }

    @Test
    @DisplayName("3. 수량 권한이 없으면 수량과 이상 후보가 전부 빠진다")
    fun qtyMaskedOut() {
        val input = buildInput(MaskingSupport(principal(DataField.WORKER)))

        assertNull(input.totalQty)
        assertNull(input.okQty)
        assertNull(input.ngQty)
        assertNull(input.achievementRate, "실적이 가려졌으면 달성률도 낼 수 없다")
        assertTrue(
            input.anomalyCandidates.isEmpty(),
            "이상 후보에는 설비별 수량이 실려 있다 — 수량 권한이 없으면 넘기면 안 된다"
        )
    }

    @Test
    @DisplayName("3-1. 수량 권한만 있으면 양품·불량 수량과 설비 불량률이 빠진다 — 총량과 나누면 수율·불량률이 된다")
    fun qtyWithoutYieldHidesRecomputableCounts() {
        // 제조팀(10003) = qty 는 있고 yield 는 없다.
        val input = buildInput(MaskingSupport(principal(DataField.QTY, DataField.MOLD, DataField.WORKER)))

        assertEquals(21_963_275L, input.totalQty, "총 수량만으로는 비율을 알 수 없다 — 그대로 둔다")
        assertNull(input.okQty, "양품 ÷ 총량 = 수율")
        assertNull(input.ngQty, "불량 ÷ 총량 = 불량률")
        assertTrue(input.anomalyCandidates.isEmpty(), "이상 후보는 설비 불량률을 싣는다 — 수율 항목이다")
    }

    @Test
    @DisplayName("3-2. 수율 권한만 있으면 이상 후보는 들어가되 설비별 수량은 빠진다")
    fun yieldWithoutQtyKeepsRateOnly() {
        val input = buildInput(MaskingSupport(principal(DataField.YIELD)))

        assertEquals(1, input.anomalyCandidates.size)
        assertEquals(1.0, input.anomalyCandidates.first().defectRate)
        assertNull(input.anomalyCandidates.first().qty)
        assertNull(input.anomalyCandidates.first().ngQty)
    }

    @Test
    @DisplayName("4. 권한이 전부 있으면 값이 그대로 들어가고 달성률이 계산된다")
    fun allAllowed() {
        val input = buildInput(
            MaskingSupport(
                principal(
                    DataField.QTY, DataField.YIELD, DataField.PLAN,
                    DataField.PRICE, DataField.CUSTOMER, DataField.MOLD, DataField.WORKER
                )
            )
        )

        assertEquals(21_963_275L, input.totalQty)
        assertEquals(20_000_000L, input.planQty)
        assertEquals(109.82, input.achievementRate, "21,963,275 / 20,000,000 = 109.82%")
        assertEquals(1, input.defectComposition.size)
        assertEquals(1, input.anomalyCandidates.size)
        assertTrue(input.maskedFields.isEmpty(), "가려진 항목이 없어야 한다")
    }

    @Test
    @DisplayName("5. 계획이 없으면 달성률을 내지 않는다 — 상수를 박으면 달성률이 지어내진다")
    fun noPlanNoRate() {
        val mask = MaskingSupport(principal(DataField.QTY, DataField.YIELD, DataField.PLAN))
        val input = AiBriefingInput.of(
            date = "2026-08-28", mask = mask,
            totalQty = 21_963_275L, okQty = null, ngQty = null,
            defectRate = null, yieldRate = null,
            planQty = null, // 일목표 마스터에 없음
            defectComposition = emptyList(), anomalyCandidates = emptyList()
        )

        assertNull(input.planQty, "마스터에 목표가 없으면 null 이다")
        assertNull(
            input.achievementRate,
            "계획이 없으면 달성률을 낼 수 없다. 고치기 전에는 150,000 이 박혀 있어 " +
                "달성률이 14,642% 로 나왔다"
        )
    }

    @Test
    @DisplayName("6. 지어낸 값이 코드로 되돌아오면 실패한다")
    fun noFabricatedConstants() {
        val src = File("src/main/kotlin/com/dwje/api/service/DashboardAiService.kt").readText()
        // 주석에 남긴 사고 기록은 제외하고 실행 코드만 본다.
        val code = src.lines()
            .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }
            .joinToString("\n")

        listOf(
            "planQty = 150000" to "계획 수량 상수 — 일목표 마스터에서 읽어야 한다",
            "\"PR-0" to "없는 설비 코드 — 설비 마스터 1,540대에 PR- 는 0대다",
            "48.5" to "지어낸 금형 온도 — 수집 경로가 없다",
            "118.4" to "지어낸 타발 압력 — 수집 경로가 없다",
            "182 SPM" to "지어낸 타발 속도 — 수집 경로가 없다"
        ).forEach { (needle, why) ->
            assertTrue(!code.contains(needle), "$needle 이 실행 코드에 있다: $why")
        }

        assertTrue(
            code.contains("MODEL_NOT_READY"),
            "모델이 없으면 값을 지어내지 말고 없다고 말해야 한다"
        )
    }
}
