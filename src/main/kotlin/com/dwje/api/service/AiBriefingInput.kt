package com.dwje.api.service

import com.dwje.api.common.util.DataField
import com.dwje.api.common.util.MaskingSupport

/**
 * sLLM 에 넘기는 지표 묶음 — **마스킹을 통과한 값만 담는다**
 *
 * ## 왜 별도 타입인가
 * 마스킹된 값이 프롬프트에 들어가면 모델이 문장으로 풀어 써서 **마스킹이 뚫린다.**
 * 화면에서 가린 수량을 모델이 "어제 2,200만개를 만들었습니다" 로 되돌려 주면
 * 부서 권한이 무의미해진다. 프롬프트를 만들기 전에 걸러야 하고, 부서 권한을 아는
 * 자리는 서버뿐이다.
 *
 * 그래서 지표를 Map 으로 들고 다니지 않고 이 타입으로 모은다. 필드마다 어떤
 * 데이터 항목 권한에 걸리는지 [of] 한 곳에 적혀 있어, 권한 없는 값이 들어오면
 * 그 자리에서 null 이 된다. [MaskingSupport.on] 은 권한이 없으면 원본을
 * **평가조차 하지 않으므로** 값이 메모리에 실리지도 않는다.
 *
 * ## null 의 뜻
 * `null` 은 두 가지다 — 권한이 없어 가려진 것, 또는 데이터가 없는 것.
 * 모델에는 둘 다 "모른다" 로 넘어가야 한다. 어느 쪽이든 **없는 값을 지어내면 안 된다.**
 * [maskedFields] 로 가려진 항목을 알 수 있으니, 프롬프트에는 "이 항목은 제공되지 않음"
 * 으로 적어 모델이 추측하지 않게 한다.
 */
data class AiBriefingInput(
    /** 대상 일자 (`yyyy-MM-dd`) */
    val date: String,

    /** 총 생산 수량 — [DataField.QTY] */
    val totalQty: Long?,

    /** 양품 수량 — [DataField.QTY] + [DataField.YIELD] (총 수량과 나누면 수율이 된다) */
    val okQty: Long?,

    /** 불량 수량 — [DataField.QTY] + [DataField.YIELD] (총 수량과 나누면 불량률이 된다) */
    val ngQty: Long?,

    /** 불량률(%) — [DataField.YIELD] */
    val defectRate: Double?,

    /** 수율(%) — [DataField.YIELD] */
    val yieldRate: Double?,

    /**
     * 계획 수량 — [DataField.PLAN]
     *
     * 제품·공정별 일목표 마스터(`ax.tb_prod_day_target`)에서 온다.
     * **마스터에 없으면 null 이다.** 상수를 박으면 달성률이 지어내진다 —
     * 고치기 전에는 150,000 이 박혀 있어 달성률이 14,642% 로 나왔다.
     */
    val planQty: Long?,

    /** 계획 대비 달성률(%). 계획이 없으면 null — 분모가 없으면 낼 수 없다. */
    val achievementRate: Double?,

    /** 불량 유형 구성 (상위 N) — [DataField.YIELD] */
    val defectComposition: List<DefectShare>,

    /** 이상 후보 설비 — [DataField.YIELD](설비 불량률을 싣는다) · 최소 생산량 미만은 후보에서 빠진다 */
    val anomalyCandidates: List<AnomalyCandidate>,

    /**
     * 권한이 없어 가려진 데이터 항목 key 목록
     *
     * 프롬프트에 "이 항목은 제공되지 않음" 으로 적어 모델이 추측하지 않게 한다.
     */
    val maskedFields: List<String>
) {

    /** 불량 유형 한 건 */
    data class DefectShare(val code: String?, val label: String?, val qty: Long?)

    /**
     * 이상 후보 설비 한 건
     *
     * `defectRate` 는 그 설비의 불량률이다. 생산량이 적으면 1건 불량으로도 100% 가
     * 되므로 후보에 넣기 전에 최소 생산량으로 자른다. (조립부에서 판정)
     */
    data class AnomalyCandidate(
        val eqptCd: String?,
        val eqptNm: String?,
        val qty: Long?,
        val ngQty: Long?,
        val defectRate: Double?
    )

    companion object {

        /**
         * 마스킹을 통과한 값만 담아 입력을 만든다.
         *
         * 각 필드가 어떤 데이터 항목 권한에 걸리는지는 여기 한 곳에만 적는다.
         * 호출부가 직접 값을 넣으면 걸러지지 않은 값이 섞일 수 있다.
         */
        fun of(
            date: String,
            mask: MaskingSupport,
            totalQty: Long?,
            okQty: Long?,
            ngQty: Long?,
            defectRate: Double?,
            yieldRate: Double?,
            planQty: Long?,
            defectComposition: List<DefectShare>,
            anomalyCandidates: List<AnomalyCandidate>
        ): AiBriefingInput {
            val qty = mask.on(DataField.QTY) { totalQty }
            val plan = mask.on(DataField.PLAN) { planQty }

            return AiBriefingInput(
                date = date,
                totalQty = qty,
                // 양품·불량 수량은 총 수량과 나누면 수율·불량률이 된다. 수율 권한까지 있어야 넣는다.
                okQty = mask.on(DataField.QTY) { mask.on(DataField.YIELD) { okQty } },
                ngQty = mask.on(DataField.QTY) { mask.on(DataField.YIELD) { ngQty } },
                defectRate = mask.on(DataField.YIELD) { defectRate },
                yieldRate = mask.on(DataField.YIELD) { yieldRate },
                planQty = plan,
                // 계획이나 실적 한쪽이라도 없으면 달성률을 내지 않는다.
                achievementRate = if (plan != null && plan > 0 && qty != null) {
                    Math.round(qty * 10000.0 / plan) / 100.0
                } else {
                    null
                },
                defectComposition = mask.on(DataField.YIELD) { defectComposition } ?: emptyList(),
                // 후보는 설비 불량률을 싣는다(프롬프트 「설비별 불량률」) — 수율 항목이다.
                // 설비별 수량은 수량 권한이 없으면 뺀다.
                anomalyCandidates = mask.on(DataField.YIELD) {
                    if (mask.allowed(DataField.QTY)) anomalyCandidates
                    else anomalyCandidates.map { it.copy(qty = null, ngQty = null) }
                } ?: emptyList(),
                maskedFields = mask.maskedKeys().toList()
            )
        }
    }
}
