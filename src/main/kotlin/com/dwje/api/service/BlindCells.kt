package com.dwje.api.service

/**
 * 서버 생성 파일의 가린 칸 (R-10, 3단계 메인 결정) — 빈칸이 아니라 `비공개` 로 채우고 실제로 채운 칸 수를 센다.
 *
 * 판정은 응답 필드명 카탈로그 기준이다: 열 이름(행 Map 의 key)이 조회자가 열람할 수 없는 적용 중 데이터 항목의
 * 응답 필드명이면 **값과 무관하게** 가린 칸이다(API 응답의 공통 마스킹과 같은 판정, 04 DTP-09).
 * 기본 7종은 서비스가 미리 null 로 만들지만, 운영 중 추가한 항목은 파일 응답이 공통 마스킹을 거치지 않아 값이 그대로
 * 들어온다 — 여기서 값을 버려야 새지 않는다. 원래 비어 있던 칸도 `비공개` 다(볼 수 없는 항목이 비었는지도 알리지 않는다).
 *
 * 한 파일에 하나를 만들어 쓰고, 다 쓴 뒤 [total]·[counts] 를 다운로드 이력의 blindCnt·blindCells 로 남긴다.
 *
 * @param blindAttrs 조회자가 열람할 수 없는 「응답 필드명 → 데이터 항목 key」
 */
class BlindCells(
    private val blindAttrs: Map<String, String> = emptyMap(),
    /** 데이터 항목 key → 이름 — 안내 문구용 */
    private val fieldNames: Map<String, String> = emptyMap()
) {

    private val counts = linkedMapOf<String, Int>()

    /** 가린 열이면 값과 무관하게 [MASK] 를 돌려주고 센다. 아니면 값 그대로 */
    fun fill(key: String?, value: Any?): Any? {
        val field = key?.let { blindAttrs[it] } ?: return value
        counts.merge(field, 1, Int::plus)
        return MASK
    }

    /**
     * 열 판정과 무관하게 가린 칸 하나를 센다 — 행 단위로 가리는 값(질의 이력의 가린 응답 등)
     *
     * @param field 건수를 묶을 이름(다운로드 이력 blindCells 의 key)
     */
    fun mark(field: String): String {
        counts.merge(field, 1, Int::plus)
        return MASK
    }

    /** 이 열이 가린 열인지 (값과 무관) */
    fun isBlind(key: String?): Boolean = key != null && key in blindAttrs

    /** 채운 칸 수 합계 — 다운로드 이력 blindCnt */
    val total: Int get() = counts.values.sum()

    /** 항목별 채운 칸 수 — 다운로드 이력 blindCells */
    fun counts(): Map<String, Int> = counts.toMap()

    /** 파일 안내 문구 (R-10) — 0건이어도 쓴다. 브라우저 생성 파일과 같은 규칙이다(공통 10.6, 4단계 메인 결정) */
    fun notice(): String = "비공개 처리 ${total}건(데이터 접근 권한 기준)"

    /** 항목별 건수 문구 — 「단가·금액 12건, 설비 코드 3건」. 채운 칸이 없으면 null */
    fun detail(): String? = counts.takeIf { it.isNotEmpty() }
        ?.entries?.joinToString(", ") { "${fieldNames[it.key] ?: it.key} ${it.value}건" }

    /** 통합 문서의 「안내」 시트 — 0건이어도 만든다 (실적·불량 통합 문서와 단일 표 공용) */
    fun writeNoticeSheet(wb: org.apache.poi.ss.usermodel.Workbook) {
        val sheet = wb.getSheet("안내") ?: wb.createSheet("안내")
        sheet.createRow(0).createCell(0).setCellValue(notice())
        detail()?.let { sheet.createRow(1).createCell(0).setCellValue("항목별: $it") }
    }

    companion object {
        const val MASK = "비공개"
    }
}
