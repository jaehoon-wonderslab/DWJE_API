package com.dwje.api.service

import com.dwje.api.common.exception.InvalidParameterException
import org.apache.poi.ss.usermodel.BorderStyle
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 보고서·목록 파일 내려받기 공통 서비스
 *
 * 엑셀(xlsx) / CSV / JSONL 형식을 지원하며, 마스킹 처리된 항목은 호출 측에서
 * 이미 null 로 치환된 상태로 전달된다. (blind 항목 제외 후 저장 원칙)
 *
 * 가린 칸은 빈칸이 아니라 `비공개` 로 쓴다(R-10) — [BlindCells]. 판정은 로그인한 계정의 데이터 접근 권한과
 * 응답 필드명 카탈로그로 한다. 채운 칸이 있으면 「안내」 시트에 건수를 적는다.
 */
@Service
class ExportService {

    /** 응답 필드명 카탈로그 — 단위 테스트처럼 직접 만든 인스턴스에는 없으므로 선택 주입이다 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    var dataFieldService: DataFieldService? = null

    /**
     * 지금 로그인한 계정 기준의 가린 칸 판정기 — 통합관리자·비로그인이면 아무것도 가리지 않는다.
     *
     * @param extra 카탈로그에 없는 파일 전용 열 이름 → 데이터 항목 key (예: `quantity` → qty)
     */
    fun blindCells(extra: Map<String, String> = emptyMap()): BlindCells {
        val principal = com.dwje.api.common.security.UserContext.currentOrNull() ?: return BlindCells()
        if (principal.superAdmin) return BlindCells()
        val catalog = (dataFieldService?.attrFieldMap() ?: emptyMap()) + extra
        val names = dataFieldService?.appliedFieldsCached()?.associate { it["key"] as String to (it["name"] as String? ?: "") }.orEmpty()
        // 기본 7종 key(파일 전용 extra 대응 등)는 엄격한 판정 — 그 묶음 항목을 모두 볼 수 있을 때만(V82)
        return BlindCells(com.dwje.api.common.response.DataFieldMaskingAdvice.blindAttrs(catalog) { com.dwje.api.common.response.DataFieldMaskingAdvice.readableForMasking(principal, it) }, names)
    }

    companion object {
        private val TS_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
        private const val CSV_BOM = "﻿"
    }

    /**
     * 표 형태 데이터를 요청 형식의 파일 응답으로 변환한다.
     *
     * @param format   xls | xlsx | csv
     * @param fileName 확장자를 제외한 파일명
     * @param headers  헤더 라벨 목록
     * @param keys     각 행 Map 에서 값을 꺼낼 key 목록 (headers 와 순서 일치)
     * @param rows     데이터 행 목록
     * @return 다운로드 응답 (Content-Disposition attachment)
     */
    fun export(
        format: String?,
        fileName: String,
        headers: List<String>,
        keys: List<String>,
        rows: List<Map<String, Any?>>,
        blind: BlindCells = blindCells()
    ): ResponseEntity<ByteArrayResource> {
        require(headers.size == keys.size) { "헤더와 데이터 key 개수가 일치해야 합니다." }

        val normalized = (format ?: "xls").lowercase()
        return when (normalized) {
            "xls", "xlsx", "excel" -> excel(fileName, headers, keys, rows, blind = blind)
            "csv" -> csv(fileName, headers, keys, rows, blind)
            else -> throw InvalidParameterException("지원하지 않는 다운로드 형식입니다. [format=$format]", "format")
        }
    }

    /**
     * 엑셀(xlsx) 파일을 생성한다.
     */
    fun excel(
        fileName: String,
        headers: List<String>,
        keys: List<String>,
        rows: List<Map<String, Any?>>,
        sheetName: String = "Sheet1",
        blind: BlindCells = blindCells()
    ): ResponseEntity<ByteArrayResource> {
        val bytes = XSSFWorkbook().use { workbook ->
            val sheet = workbook.createSheet(sheetName)

            // 1. 헤더 스타일 — 회색 배경 + 굵은 글씨 + 테두리
            val headerStyle = workbook.createCellStyle().apply {
                fillForegroundColor = IndexedColors.GREY_25_PERCENT.index
                fillPattern = FillPatternType.SOLID_FOREGROUND
                alignment = HorizontalAlignment.CENTER
                borderBottom = BorderStyle.THIN
                borderTop = BorderStyle.THIN
                borderLeft = BorderStyle.THIN
                borderRight = BorderStyle.THIN
                setFont(workbook.createFont().apply { bold = true })
            }

            // 2. 헤더 행 작성
            val headerRow = sheet.createRow(0)
            headers.forEachIndexed { idx, label ->
                headerRow.createCell(idx).apply {
                    setCellValue(label)
                    cellStyle = headerStyle
                }
            }

            // 3. 데이터 행 작성 — 숫자는 숫자 셀로, 그 외는 문자열로 기록한다.
            rows.forEachIndexed { rowIdx, row ->
                val sheetRow = sheet.createRow(rowIdx + 1)
                keys.forEachIndexed { colIdx, key ->
                    val cell = sheetRow.createCell(colIdx)
                    when (val value = blind.fill(key, row[key])) {
                        null -> cell.setCellValue("")            // 원래 빈 값
                        is Number -> cell.setCellValue(value.toDouble())
                        is Boolean -> cell.setCellValue(if (value) "Y" else "N")
                        else -> cell.setCellValue(value.toString())
                    }
                }
            }

            // 4. 열 너비 자동 조정 (헤더 기준 최소 너비 확보)
            headers.indices.forEach { idx ->
                sheet.autoSizeColumn(idx)
                sheet.setColumnWidth(idx, (sheet.getColumnWidth(idx) + 1024).coerceAtMost(60 * 256))
            }

            // 가린 칸 건수를 안내 시트에 남긴다(0건 포함) — 다운로드 이력 blindCnt 와 같은 값(R-10, 공통 10.6)
            blind.writeNoticeSheet(workbook)

            ByteArrayOutputStream().use { out ->
                workbook.write(out)
                out.toByteArray()
            }
        }

        return download(bytes, "$fileName.xlsx", MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        ))
    }

    /**
     * 이미 만들어진 xlsx 바이트를 첨부파일 응답으로 감싼다.
     *
     * 시트가 여럿이거나 차트가 있는 통합 문서는 호출 측이 직접 만들고 여기로 넘긴다.
     * (표 한 장짜리는 [excel] 을 쓴다)
     *
     * @param fileName 파일명 — `.xlsx` 가 없으면 붙인다
     */
    fun xlsx(bytes: ByteArray, fileName: String): ResponseEntity<ByteArrayResource> =
        download(
            bytes,
            if (fileName.endsWith(".xlsx", ignoreCase = true)) fileName else "$fileName.xlsx",
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        )

    /**
     * CSV 파일을 생성한다. (Excel 한글 깨짐 방지를 위해 UTF-8 BOM 을 붙인다)
     */
    fun csv(
        fileName: String,
        headers: List<String>,
        keys: List<String>,
        rows: List<Map<String, Any?>>,
        blind: BlindCells = blindCells()
    ): ResponseEntity<ByteArrayResource> {
        val sb = StringBuilder(CSV_BOM)
        sb.append(headers.joinToString(",") { escapeCsv(it) }).append("\r\n")
        rows.forEach { row ->
            sb.append(keys.joinToString(",") { escapeCsv(blind.fill(it, row[it])?.toString() ?: "") }).append("\r\n")
        }
        // CSV 는 시트가 없으므로 마지막 줄에 안내를 남긴다(R-10)
        sb.append(escapeCsv(blind.notice())).append("\r\n")
        return download(sb.toString().toByteArray(StandardCharsets.UTF_8), "$fileName.csv", MediaType("text", "csv", StandardCharsets.UTF_8))
    }

    /**
     * JSONL(줄 단위 JSON) 파일을 생성한다. — 파인튜닝 학습데이터 내보내기용
     */
    fun jsonl(fileName: String, lines: List<String>): ResponseEntity<ByteArrayResource> {
        val body = lines.joinToString("\n").toByteArray(StandardCharsets.UTF_8)
        return download(body, "$fileName.jsonl", MediaType.APPLICATION_OCTET_STREAM)
    }

    /**
     * 파일명 뒤에 붙일 타임스탬프 문자열을 생성한다. (yyyyMMdd_HHmmss)
     */
    fun timestamp(): String = LocalDateTime.now().format(TS_FORMAT)

    /**
     * 서버 생성 전체 내려받기의 상한 표시 (공통 CMN-07) — [limit] 을 주면 `X-Export-Limit`, 전체 [total] 건 중 [shown] 건만 담았으면
     * `X-Export-Truncated: true` · `X-Export-Total: total` 헤더를 붙인다. 브라우저가 읽도록 CORS 노출 헤더에도 있다.
     */
    fun withExportTotals(file: ResponseEntity<ByteArrayResource>, total: Long, shown: Int, limit: Int? = null): ResponseEntity<ByteArrayResource> {
        if (total <= shown && limit == null) return file
        val b = ResponseEntity.status(file.statusCode).headers(file.headers)
        // 이 화면의 상한 — 화면이 「최대 n건」 안내에 쓴다(공통 D-29)
        limit?.let { b.header("X-Export-Limit", it.toString()) }
        if (total > shown) b.header("X-Export-Truncated", "true").header("X-Export-Total", total.toString())
        return b.body(file.body)
    }

    /**
     * 바이트 배열을 첨부파일 다운로드 응답으로 감싼다.
     */
    private fun download(bytes: ByteArray, fileName: String, mediaType: MediaType): ResponseEntity<ByteArrayResource> {
        val disposition = ContentDisposition.attachment()
            .filename(fileName, StandardCharsets.UTF_8)
            .build()

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .contentType(mediaType)
            .contentLength(bytes.size.toLong())
            .body(ByteArrayResource(bytes))
    }

    /** CSV 특수문자(쉼표·따옴표·개행)를 이스케이프한다. */
    private fun escapeCsv(value: String): String =
        if (value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r')) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
}
