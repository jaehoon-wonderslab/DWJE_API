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

/**
 * NAS 이미지 경로 설정 (`app.nas.*`)
 *
 * AOI 불량 사진은 NAS 에 있고 브라우저가 직접 읽지 못하므로 API 가 프록시한다.
 * **루트 아래 경로만** 내려준다 — 매핑 테이블의 경로가 루트를 벗어나면(`..` 포함) 거절한다.
 *
 * @param aoiRoot        AOI 이미지 NAS 마운트 루트. 규칙 문서가 오기 전 임시값.
 * @param imageUrlTtlSec 상세 API 가 발급하는 이미지 URL 서명의 유효시간(초)
 */
data class NasProperties(

    @field:NotBlank(message = "AOI 이미지 NAS 루트(app.nas.aoi-root)는 비워 둘 수 없습니다.")
    val aoiRoot: String = "./data/nas-aoi",

    @field:Min(value = 60, message = "이미지 URL 유효시간(app.nas.image-url-ttl-sec)은 60초 이상이어야 합니다.")
    @field:Max(value = 86_400, message = "이미지 URL 유효시간(app.nas.image-url-ttl-sec)은 하루를 넘을 수 없습니다.")
    val imageUrlTtlSec: Long = 900
)
