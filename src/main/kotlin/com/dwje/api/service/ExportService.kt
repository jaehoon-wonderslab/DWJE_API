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
 */
@Service
class ExportService {

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
        rows: List<Map<String, Any?>>
    ): ResponseEntity<ByteArrayResource> {
        require(headers.size == keys.size) { "헤더와 데이터 key 개수가 일치해야 합니다." }

        val normalized = (format ?: "xls").lowercase()
        return when (normalized) {
            "xls", "xlsx", "excel" -> excel(fileName, headers, keys, rows)
            "csv" -> csv(fileName, headers, keys, rows)
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
        sheetName: String = "Sheet1"
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
                    when (val value = row[key]) {
                        null -> cell.setCellValue("")            // 마스킹 항목은 공란으로 저장
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
        rows: List<Map<String, Any?>>
    ): ResponseEntity<ByteArrayResource> {
        val sb = StringBuilder(CSV_BOM)
        sb.append(headers.joinToString(",") { escapeCsv(it) }).append("\r\n")
        rows.forEach { row ->
            sb.append(keys.joinToString(",") { escapeCsv(row[it]?.toString() ?: "") }).append("\r\n")
        }
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
