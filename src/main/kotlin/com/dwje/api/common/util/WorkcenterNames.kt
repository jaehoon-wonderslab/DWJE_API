package com.dwje.api.common.util

/**
 * 작업장(공정) 이름에서 공장명을 읽고, 공장 열이 따로 있을 때 공정명의 중복 표기를 걷어낸다.
 *
 * 공장은 별도 컬럼이 없다 — `plant_cd` 는 사업장(PL01) 하나뿐이고 lot_no 에도 공장 자리가 없다
 * (2026-09-06 전수 확인). 유일한 출처는 작업장 이름의 `(M-1공장)` 같은 괄호 표기다.
 * 뽑는 규칙을 화면과 서버 두 곳에 두면 나중에 어긋나므로 서버 이 한 곳에만 둔다.
 */
object WorkcenterNames {

    /**
     * 작업장 이름의 공장 표기 — 괄호까지 포함해 맞춘다.
     * `M-\d` 만 보면 금형코드(`MPM-058`)나 설비명의 `M-` 패턴에 걸린다.
     */
    private val PLANT_IN_NAME = Regex("""\((M-\d+공장)\)""")

    /** 괄호 묶음 하나 — 공정명에서 공장 표기를 지울 때 쓴다. */
    private val PAREN_GROUP = Regex("""\(([^()]*)\)""")

    /** 작업장 이름에서 공장명을 읽는다. 표기가 없으면 null — 설비 번호로 가르지 않는다. */
    fun plantOf(processNm: String?): String? =
        processNm?.let { PLANT_IN_NAME.find(it)?.groupValues?.get(1) }

    /**
     * 공정명에서 **공장 열과 정확히 같은** 괄호 표기만 지운다.
     *
     * 예) `PRESS(M-1공장)` + plantNm `M-1공장` → `PRESS`
     *     `A2-PLATING(전해 라인)` + plantNm null → 그대로 (다른 괄호 표기는 건드리지 않는다)
     *
     * 공장 열이 비어 있으면 아무것도 지우지 않는다 — 중복이 아니기 때문이다.
     */
    fun withoutPlant(processNm: String?, plantNm: String?): String? {
        if (processNm == null) return null
        val plant = plantNm?.trim()?.takeIf { it.isNotEmpty() } ?: return processNm
        return PAREN_GROUP.replace(processNm) { m ->
            if (m.groupValues[1].trim() == plant) "" else m.value
        }.trim()
    }
}
