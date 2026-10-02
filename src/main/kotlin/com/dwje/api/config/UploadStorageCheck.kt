package com.dwje.api.config

import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Paths

/**
 * 업로드 원본 저장 루트 기동 점검 (11 UPD-08) — 기동은 막지 않고 로그만 남긴다.
 *
 * 운영(prod)에서 루트가 상대 경로면 작업 디렉터리가 바뀔 때 원본이 「없음」 이 되므로 경고한다.
 * 디렉터리가 없으면 만들지 않는다 — 첫 저장 때 만든다.
 */
@Component
class UploadStorageCheck(
    private val appProperties: AppProperties,
    private val environment: Environment
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun onReady() {
        check()
    }

    /** 점검 결과 경고 문구 — 시험용으로 돌려준다 */
    fun check(): List<String> {
        val configured = appProperties.upload.dir
        val root = Paths.get(configured).toAbsolutePath().normalize()
        val exists = Files.isDirectory(root)
        log.info("업로드 저장 루트: {} (있음={}, 쓰기 가능={})", root, exists, exists && Files.isWritable(root))
        val warnings = mutableListOf<String>()
        if (!exists) warnings += "업로드 저장 루트가 아직 없습니다. 첫 업로드 때 만듭니다. [$root]"
        if ("prod" in environment.activeProfiles && !Paths.get(configured).isAbsolute) {
            warnings += "운영 프로파일인데 업로드 저장 루트가 상대 경로입니다. AX_UPLOAD_DIR 에 절대 경로를 지정하세요. [$configured]"
        }
        warnings.forEach { log.warn(it) }
        return warnings
    }
}
