package com.dwje.api.model.request

/**
 * 파일 내려받기 공통 요청
 *
 * @param format    다운로드 형식 — xls | csv | pdf
 * @param yearMonth 대상 연월 (YYYY-MM)
 * @param from      조회 시작일
 * @param to        조회 종료일
 * @param scope     조회 범위 설명 (다운로드 이력 기록용)
 */
data class ExportFormatRequest(
    val format: String = "xls",
    val yearMonth: String? = null,
    val from: String? = null,
    val to: String? = null,
    val scope: String? = null
)

/**
 * 상태 변경 공통 요청 (사용/정지, 활성/중지, 수신/부재 등)
 *
 * @param state  상태 값 (문자열 표기)
 * @param on     불리언 표기 상태 값
 * @param reason 변경 사유
 */
data class StateChangeRequest(
    val state: String? = null,
    val on: Boolean? = null,
    val reason: String? = null
)

/**
 * 사유·의견 입력 공통 요청 (반려, 확인 처리 등)
 *
 * @param reason     사유
 * @param actionNote 조치 내용
 */
data class ReasonRequest(
    val reason: String? = null,
    val actionNote: String? = null
)
