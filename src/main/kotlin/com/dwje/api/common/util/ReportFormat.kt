package com.dwje.api.common.util

/**
 * 내려받기 형식 코드 — 공통코드 `RPT_FORMAT` (10 기획서 DLG-02)
 *
 * 예전에는 xls·xlsx 를 모두 `XLS` 로 저장했고, 브라우저는 표시명(`엑셀 (.XLSX)`)을 그대로 보내 같은 형식이 여러 값으로 쌓였다.
 * 저장·필터는 이 코드 6종만 쓴다. 서버가 만든 파일은 요청 값이 아니라 **실제로 만든 파일** 기준으로 넘긴다
 * (ExportService 는 xls 를 요청해도 .xlsx 를 만든다).
 */
object ReportFormat {
    const val XLS = "XLS"
    const val XLSX = "XLSX"
    const val CSV = "CSV"
    const val PDF = "PDF"
    const val PNG = "PNG"
    const val JSONL = "JSONL"

    val ALL = listOf(XLS, XLSX, CSV, PDF, PNG, JSONL)

    /** 예전 브라우저가 보내던 표시명 → 코드 (대소문자 무시, 공백은 그대로 비교) */
    private val LEGACY_LABELS = mapOf(
        "엑셀 (.XLS)" to XLS, "엑셀 (.XLSX)" to XLSX, "CSV (.CSV)" to CSV, "인쇄 · PDF" to PDF
    )

    /** 형식 값을 코드로 바꾼다. 목록 밖이면 null */
    fun normalize(raw: String?): String? {
        val v = raw?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
        LEGACY_LABELS[v]?.let { return it }
        return when (v) {
            "XLS", "EXCEL" -> XLS
            "XLSX" -> XLSX
            "CSV" -> CSV
            "PDF", "PRINT" -> PDF
            "PNG" -> PNG
            "JSONL" -> JSONL
            else -> null
        }
    }

    /** 서버 엑셀·CSV 내보내기(ExportService)가 실제로 만드는 파일의 형식 — csv 만 CSV, 그 밖은 xlsx */
    fun ofServerExport(requested: String?): String = if (requested?.trim()?.lowercase() == "csv") CSV else XLSX

    /**
     * 저장된 원문(과거 표시명 포함)을 코드로 읽는 SQL 식 — 조회·필터 공용. `l` 은 다운로드 이력 표 별칭.
     * 과거 행은 고치지 않고(UPDATE 금지) 읽을 때만 정규화한다.
     */
    const val SQL_NORMALIZED = """CASE upper(l.format_cd)
        WHEN '엑셀 (.XLS)' THEN 'XLS' WHEN '엑셀 (.XLSX)' THEN 'XLSX'
        WHEN 'CSV (.CSV)' THEN 'CSV' WHEN '인쇄 · PDF' THEN 'PDF'
        ELSE upper(l.format_cd) END"""
}
