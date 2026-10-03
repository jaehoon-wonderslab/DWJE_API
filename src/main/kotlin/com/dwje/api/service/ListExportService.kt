package com.dwje.api.service

import com.dwje.api.common.util.AuditType
import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.response.PageMeta
import com.dwje.api.common.util.MenuId
import com.dwje.api.common.util.ReportFormat
import com.dwje.api.model.request.ListExportRequest
import com.dwje.api.repository.AuditLogRepository
import com.dwje.api.repository.DownloadLogRepository
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service

/**
 * 목록 화면의 「전체 다운로드」 서버 생성 xlsx (공통 CMN-07, R-10·R-16)
 *
 * 감사 로그 · 다운로드 이력 · 데이터 연동(작업·실행·드리프트) · 질의 이력(질의·세션) 이 같은 규칙을 쓴다.
 * - 권한 = 그 화면의 **조회 권한**(공통 9.8). 행은 각 화면 조회 서비스를 그대로 불러 얻는다 — 권한 판정·값 가림·
 *   열람 범위(질의 이력 미배정 = 본인)가 화면과 똑같다.
 * - 쪽(1,000건)을 이어 붙여 [EXPORT_MAX] 건에서 자른다. 잘렸으면 `X-Export-Truncated`·`X-Export-Total` 헤더.
 * - 내려받기는 서버가 다운로드 이력에 직접 남긴다(origin SERVER, 범위 ALL). 화면은 따로 신고하지 않는다.
 */
@Service
class ListExportService(
    private val auditLogService: AuditLogService,
    private val downloadLogService: DownloadLogService,
    private val syncService: SyncService,
    private val aiAdminService: AiAdminService,
    private val exportService: ExportService
) {

    companion object {
        /** 전체 다운로드 상한 — 감사 로그·다운로드 이력·연동 작업은 [EXPORT_MAX_LARGE], 그 밖은 이 값(공통 D-29 권장안) */
        const val EXPORT_MAX = 10_000
        const val EXPORT_MAX_LARGE = 50_000

        /** 가린 응답 칸의 항목 이름 — 데이터 항목 key 가 아니라 「질의자보다 좁은 권한으로 가린 응답」 */
        const val HIDDEN_ANSWER = "chat_answer"
        private const val PAGE = 1_000

        private val JSON = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()

        /** 「전체」 의 기간 하한 — 기간 조건을 두지 않는다(공통 10.6: 전체 = 화면 조건과 무관) */
        private const val ALL_FROM = "2000-01-01"
    }

    /** 「전체」 의 기간 끝 — 오늘 */
    private fun allTo(): String = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).toString()

    /**
     * 범위 확인 — 서버 생성은 전체(ALL)만. 조회 목록(VIEW)은 화면이 만들고 브라우저 신고로 기록한다(공통 10.6).
     */
    private fun requireAll(req: ListExportRequest?) {
        val scope = (req?.scopeCd ?: req?.scope)?.trim()?.uppercase()?.ifEmpty { null } ?: "ALL"
        if (scope != "ALL") {
            throw InvalidParameterException("서버 생성 내려받기는 전체(ALL)만 받습니다. 조회 목록은 화면에서 내려받습니다. [$scope]", "scopeCd")
        }
    }

    private data class Sheet(val title: String, val headers: List<String>, val keys: List<String>)

    /** 감사 로그 전체 (09) */
    fun auditLogs(req: ListExportRequest?): ResponseEntity<ByteArrayResource> {
        val sheet = Sheet(
            "감사 로그",
            // 화면 9열 순서(결과 → 비고) + 접속 환경 + ID (09 AUD-07)
            listOf("일시", "유형", "사번", "이름", "부서", "대상", "처리 결과", "비고", "IP", "접속 환경", "ID"),
            listOf("ts", "type", "empNo", "name", "dept", "target", "result", "detail", "ip", "ua", "id")
        )
        requireAll(req)
        // 전체 = 기간·조건 무관, 모든 유형(감사 열람 포함). 내려받기는 열람 기록(AUDIT_VIEW) 대신 EXPORT 로 남는다
        val (rows, total) = collect(EXPORT_MAX_LARGE) { p ->
            auditLogService.getAuditLogs(ALL_FROM, allTo(), null, null, null, p, PAGE, AuditLogRepository.AuditFilter(), exporting = true)
        }
        return write("audit_logs", sheet, rows, total, req, MenuId.SYS_AUDIT, EXPORT_MAX_LARGE)
    }

    /** 다운로드 이력 전체 (10) */
    fun downloadLogs(req: ListExportRequest?): ResponseEntity<ByteArrayResource> {
        val sheet = Sheet(
            "다운로드 이력",
            // 화면 열 + 생성 경로·파일명·파일 크기·조건 스냅샷(JSON)·ID (10 DLG-08)
            listOf("일시", "사번", "이름", "부서", "화면", "보고서", "형식", "범위", "조회 조건", "행 수", "비공개 칸", "파일명", "파일 크기", "생성", "IP", "결과", "조건 스냅샷", "ID"),
            listOf("ts", "empNo", "name", "dept", "menuNm", "report", "format", "scopeCd", "condSummary", "rowCnt", "blindCnt", "fileNm", "fileSize", "origin", "ip", "result", "paramsJson", "dlId")
        )
        requireAll(req)
        val (rows, total) = collect(EXPORT_MAX_LARGE) { p ->
            downloadLogService.getLogs(ALL_FROM, allTo(), DownloadLogRepository.LogFilter(), p, PAGE, exporting = true).let { (items, meta) ->
                items.map { it + ("paramsJson" to (it["params"]?.let { v -> JSON.writeValueAsString(v) })) } to meta
            }
        }
        return write("download_logs", sheet, rows, total, req, MenuId.SYS_DL, EXPORT_MAX_LARGE)
    }

    /** 데이터 연동 전체 (12) — target(필수) JOBS(작업) | RUNS(실행) | DRIFTS(스키마 드리프트) */
    fun sync(req: ListExportRequest?): ResponseEntity<ByteArrayResource> {
        // 화면 권한을 먼저 본다 — 권한 없는 호출에 입력 오류(400)가 먼저 나가지 않게 (공통 9.8)
        syncService.requireViewer()
        requireAll(req)
        // 대상은 필수 (12 SYN-15 · 4.4) — 기본값으로 JOBS 를 고르지 않는다
        val target = req?.target?.trim()?.uppercase()?.ifEmpty { null }
            ?: throw InvalidParameterException("내려받기 대상 값이 올바르지 않습니다. [] 허용 값은 JOBS · RUNS · DRIFTS 입니다.", "target")
        val (sheet, fetch) = when (target) {
            "JOBS" -> Sheet(
                "이관 작업",
                listOf("작업 ID", "실행 ID", "원본 테이블", "대상 테이블", "구분", "예약 시각", "시작", "종료", "소요", "행 수", "성공 행", "실패 행", "상태",
                    "정합성", "재실행 수", "재실행 원작업", "실행 경로", "요청자", "실패 원인"),
                listOf("jobId", "runId", "srcTable", "dstTable", "kind", "scheduledAt", "startedAt", "endedAt", "duration", "rows", "okRows", "ngRows", "state",
                    "checksumNm", "retryCnt", "retryOfJobId", "triggeredBy", "triggeredByUser", "remark")
            ) to { p: Int ->
                syncService.getJobs(ALL_FROM, allTo(), null, null, p, PAGE).let { (items, meta) ->
                    // 정합성 true/false/null → 일치/불일치/검증 전 (12 SYN-15)
                    items.map { it + ("checksumNm" to when (it["checksumMatch"]) { true -> "일치"; false -> "불일치"; else -> "검증 전" }) } to meta
                }
            }
            "RUNS" -> Sheet(
                "이관 실행",
                listOf("실행 ID", "모드", "결과", "시작", "종료", "소요(초)", "실행 주체", "실행자", "테이블 수", "성공", "실패", "성공 행", "실패 행", "모의 실행",
                    "옵션", "드리프트 수", "엔진 버전", "메시지"),
                listOf("runId", "modeNm", "stateNm", "startedAt", "endedAt", "durationSec", "triggeredByCd", "triggeredBy", "tableCnt", "successCnt", "failCnt", "okRows", "ngRows", "dryRun",
                    "options", "driftOpenCntAtRun", "engineVersion", "message")
            ) to { p: Int -> syncService.getRuns(ALL_FROM, allTo(), null, null, p, PAGE) }
            "DRIFTS" -> Sheet(
                "스키마 드리프트",
                listOf("ID", "위치", "구분", "DB", "스키마", "테이블", "객체", "원본 테이블", "대상 테이블", "내용", "처음 발견", "마지막 발견", "발견 수", "해소", "해소 시각", "해소자", "해소 메모"),
                listOf("driftId", "side", "kind", "dbName", "schemaName", "tableName", "objectName", "srcTable", "dstTable", "detail", "firstSeenAt", "lastSeenAt", "detectCnt", "resolved", "resolvedAt", "resolvedBy", "resolveNote")
            ) to { p: Int -> syncService.getDrifts(null, null, null, p, PAGE) }
            else -> throw InvalidParameterException("내려받기 대상 값이 올바르지 않습니다. [$target] 허용 값은 JOBS · RUNS · DRIFTS 입니다.", "target")
        }
        val limit = if (target == "JOBS") EXPORT_MAX_LARGE else EXPORT_MAX
        val (rows, total) = collect(limit, fetch)
        return write("sync_${target.lowercase()}", sheet, rows, total, req, MenuId.SYS_SYNC, limit, mapOf("target" to target))
    }

    /**
     * 질의 이력 전체 (08) — view QUERY(질의 단위, 기본) | SESSION(세션 단위). 응답 가림은 화면 조회와 같다.
     * [historyScope] mine(기본)은 본인 질의(chat-history), all 은 전 사용자(sys-chat-history) — V70.
     */
    fun chatHistory(req: ListExportRequest?, historyScope: String? = null): ResponseEntity<ByteArrayResource> {
        requireAll(req)
        val menu = AiAdminService.menuOf(historyScope)
        // 4.4 의 MESSAGE 는 QUERY 의 다른 이름
        val view = (req?.view?.trim()?.uppercase()?.ifEmpty { null } ?: "QUERY").let { if (it == "MESSAGE") "QUERY" else it }
        val (sheet, fetch) = when (view) {
            "QUERY" -> Sheet(
                "질의 이력",
                listOf("질의 ID", "일시", "사번", "이름", "부서", "질문", "응답", "판단 근거", "미응답 사유", "응답(초)", "답변 시각", "평가", "검토", "학습 답변", "재질문", "세션"),
                listOf("messageId", "ts", "empNo", "name", "dept", "question", "answer", "judgmentBasis", "unansweredReason", "responseSec", "answeredAt", "rating", "reviewNm", "trainAnswer", "reask", "sessionKey")
            ) to { p: Int -> aiAdminService.getChatHistory(null, null, null, p, PAGE, exporting = true, scope = historyScope).let { it.rows to it.meta } }
            "SESSION" -> Sheet(
                "질의 세션",
                listOf("세션", "시작", "마지막 질의", "사번", "이름", "부서", "질의 수", "첫 질문", "응답 수", "유용", "나쁨", "검토 수", "가린 응답"),
                listOf("sessionKey", "startedAt", "lastAskedAt", "empNo", "name", "dept", "questionCnt", "firstQuestion", "answeredCnt", "usefulCnt", "badCnt", "reviewedCnt", "hiddenCnt")
            ) to { p: Int -> aiAdminService.getChatSessions(null, null, null, null, null, p, PAGE, exporting = true, scope = historyScope) }
            else -> throw InvalidParameterException("보기 단위는 QUERY 또는 SESSION 이어야 합니다. [$view]", "view")
        }
        val (rows, total) = collect(EXPORT_MAX, fetch)
        val blind = exportService.blindCells()
        // 열람자 권한으로 응답을 가린 행 — 응답·판단 근거를 「비공개」 로 채우고 건수에 넣는다(CHH-07·19, R-10)
        val shaped = rows.map { r ->
            if (r["answerHidden"] == true) r + mapOf("answer" to blind.mark(HIDDEN_ANSWER), "judgmentBasis" to blind.mark(HIDDEN_ANSWER)) else r
        }
        // 감사는 쪽마다가 아니라 내려받기 한 번에 한 행(CHH-06)
        val principal = com.dwje.api.common.security.UserContext.current()
        if (rows.any { it["empNo"] != null && it["empNo"] != principal.userId } || rows.any { it["empNo"] == null }) {
            auditLogService.record(
                logType = AuditType.EXPORT, menuId = menu, targetDesc = "질의 이력 내려받기 view=$view",
                resultCd = if (blind.total > 0) "MASKED" else "ALLOW", maskedCnt = blind.total, remark = "rows=${rows.size}, total=$total"
            )
        }
        return write("chat_history_${view.lowercase()}", sheet, shaped, total, req, menu, EXPORT_MAX, mapOf("view" to view), blind)
    }

    /** 쪽을 이어 붙인다 — 상한 또는 마지막 쪽에서 멈춘다 */
    private fun collect(limit: Int, fetch: (Int) -> Pair<List<Map<String, Any?>>, PageMeta>): Pair<List<Map<String, Any?>>, Long> {
        val rows = mutableListOf<Map<String, Any?>>()
        var page = 1
        var total = 0L
        while (rows.size < limit) {
            val (chunk, meta) = fetch(page)
            total = meta.total
            rows += chunk
            if (chunk.size < PAGE || rows.size >= total) break
            page++
        }
        return rows.take(limit) to total
    }

    private fun write(
        prefix: String,
        sheet: Sheet,
        rows: List<Map<String, Any?>>,
        total: Long,
        req: ListExportRequest?,
        defaultMenuId: String,
        limit: Int,
        extraParams: Map<String, Any?> = emptyMap(),
        blind: BlindCells = exportService.blindCells()
    ): ResponseEntity<ByteArrayResource> {
        req?.format?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "xlsx" }?.let {
            throw InvalidParameterException("지원하지 않는 형식입니다. [xlsx]", "format")
        }
        val fileName = "${prefix}_${exportService.timestamp()}"
        val file = exportService.excel(fileName, sheet.headers, sheet.keys, rows, sheet.title, blind)
        // 「전체」 는 조건과 무관하므로 요청 condSummary 보다 서버 문구가 우선이다 (09 AUD-07 · 10 DLG-08)
        val condSummary = "전체 · 최근 순 · 상한 ${"%,d".format(limit)}건" +
            (if (total > rows.size) " · 상한 적용(전체 ${"%,d".format(total)}건)" else "")
        downloadLogService.record(
            reportId = null,
            reportNm = sheet.title,
            // 화면 ID 는 서버가 정한다 — 요청 값을 그대로 남기면 다른 화면 이름으로 기록될 수 있다(DLG-08)
            menuId = defaultMenuId,
            format = ReportFormat.XLSX,
            scope = condSummary,
            rowCnt = rows.size,
            blindCnt = blind.total,
            blindCells = blind.counts(),
            fileNm = "$fileName.xlsx",
            params = mapOf("scope" to "ALL", "limit" to limit, "total" to total, "truncated" to (total > rows.size)) + extraParams,
            fileSize = file.body?.contentLength(),
            scopeCd = "ALL",
            condSummary = condSummary
        )
        return exportService.withExportTotals(file, total, rows.size, limit)
    }
}
