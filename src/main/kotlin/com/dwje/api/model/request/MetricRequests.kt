package com.dwje.api.model.request

import java.math.BigDecimal

/**
 * 지표 기준 등록·수정 요청 — POST/PUT /api/v1/metrics/standards
 *
 * @param metricCd  지표 코드 (미지정 시 지표명으로 생성)
 * @param name      지표명
 * @param category  지표 구분 (MET_CATEGORY — DEFECT/EQPT/PROD/COLLECT/COST)
 * @param unit      단위 (MET_UNIT — PCT/CNT/MIN/SEC/HOUR/EA/KSTROKE/MKRW)
 * @param normal    정상 기준값
 * @param warn      주의 임계값
 * @param critical  위험 임계값
 * @param window    집계 구간 (MET_WINDOW)
 * @param basis     산출 근거 설명
 * @param applied   적용 여부
 * @param direction 값 방향 (high=클수록 좋음 / low=작을수록 좋음). **저장 컬럼이 아니다** —
 *                  warn/crit 관계(crit > warn 이면 low, crit < warn 이면 high, 같으면 null)에서 산출되며,
 *                  보낸 값이 그 관계와 어긋나면 400.
 *                  GET 응답의 `direction` 도 같은 규칙으로 계산된 값이다.
 */
data class MetricStandardRequest(
    val metricCd: String? = null,
    val name: String? = null,
    val category: String? = null,
    val unit: String? = null,
    val normal: BigDecimal? = null,
    val warn: BigDecimal? = null,
    val critical: BigDecimal? = null,
    val window: String? = null,
    val basis: String? = null,
    val applied: Boolean? = null,
    val direction: String? = null
)

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
