package com.dwje.api.model.request

/**
 * 수동 이관 예약 요청 — POST /api/v1/sync/jobs/manual
 *
 * @param srcTables   대상 원본 테이블 목록
 * @param kind        이관 구분 — full | incremental
 * @param scheduledAt 예약 시각 (yyyy-MM-dd HH:mm:ss)
 */
data class SyncManualRequest(
    val srcTables: List<String> = emptyList(),
    val kind: String = "incremental",
    val scheduledAt: String? = null
)

/**
 * 연결 테스트 요청 — POST /api/v1/sync/connection-test
 *
 * @param target 대상 — mssql | postgresql
 */
data class ConnectionTestRequest(
    val target: String = "postgresql"
)

/**
 * 스키마 드리프트 수동 해소 요청 — POST /api/v1/sync/schema-drift/{driftId}/resolve
 *
 * "이관 대상이 아님" 처럼 조치할 것이 없다고 판단한 건을 목록에서 내릴 때 쓴다.
 * 실제 원인이 남아 있으면 다음 배치에서 이관 엔진이 다시 열고 발견 횟수를 이어서 센다.
 *
 * @param note 해소 사유 (예: 이관 대상 아님으로 확인)
 */
data class SchemaDriftResolveRequest(
    val note: String? = null
)
