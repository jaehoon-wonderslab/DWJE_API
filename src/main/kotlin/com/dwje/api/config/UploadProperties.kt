package com.dwje.api.config

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank

/**
 * 업로드 문서 저장소 설정 (`app.upload.*`)
 *
 * AI 통합 대시보드 「업로드 리포트」 탭이 받는 엑셀 원본을 어디에, 얼마까지 저장하는지.
 * 파싱 결과(JSON)는 DB(`ax.tb_dash_upload_ver.parse_json`)에 두고 **원본 파일만** 이 디렉터리에 둔다.
 *
 * 저장 경로 규칙 : `{dir}/{docId}/{version}/{원본 파일명}`
 *
 * @param dir      저장 루트. 상대 경로면 서버 작업 디렉터리 기준. 없으면 기동 시 만든다.
 * @param maxBytes 원본 1건 상한(바이트). `spring.servlet.multipart.max-file-size` 보다 작아야 한다.
 */
data class UploadProperties(

    @field:NotBlank(message = "업로드 저장 경로(app.upload.dir)는 비워 둘 수 없습니다.")
    val dir: String = "./data/ax-uploads",

    @field:Min(value = 1024, message = "업로드 상한(app.upload.max-bytes)은 1KB 이상이어야 합니다.")
    @field:Max(value = 52_428_800, message = "업로드 상한(app.upload.max-bytes)은 50MB 를 넘을 수 없습니다.")
    val maxBytes: Long = 20L * 1024 * 1024
)
