package com.dwje.api.controller

import com.dwje.api.service.AoiDefectService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * 파일 프록시 — NAS 이미지 스트림 (요구 9)
 *
 * 경로는 [com.dwje.api.config.SecurityWhitelist] 에 있어 JWT 필터를 거치지 않는다.
 * 대신 상세 API 가 발급한 짧은 서명 토큰(`?token=`)을 검증한다 — 토큰 없이는 404 도 아니고 401 이다.
 */
@RestController
@RequestMapping("/api/v1/files")
@Tag(name = "05. 품질관리")
class FileProxyController(
    private val aoiDefectService: AoiDefectService
) {

    companion object {
        private const val THUMB_MIN = 32
        private const val THUMB_MAX = 800
    }

    @Operation(
        summary = "AOI 불량 이미지 프록시",
        description = "NAS 이미지를 그대로 내려준다. token 은 불량 상세 API 의 images[].url 에 이미 들어 있다(유효 15분). " +
            "w 를 주면(32~800) 가로 폭을 맞춘 JPEG 썸네일로 내려준다."
    )
    @GetMapping("/aoi-images/{imageId}")
    fun aoiImage(
        @PathVariable imageId: Long,
        @Parameter(description = "상세 API 가 발급한 서명 토큰") @RequestParam(required = false) token: String?,
        @Parameter(description = "썸네일 가로 폭(px)") @RequestParam(required = false) w: Int?
    ): ResponseEntity<Resource> {
        val (path, mime) = aoiDefectService.openImage(imageId, token)
        val cache = CacheControl.maxAge(10, TimeUnit.MINUTES).cachePrivate()

        if (w != null && mime.startsWith("image/")) {
            thumbnail(path, w.coerceIn(THUMB_MIN, THUMB_MAX))?.let { bytes ->
                return ResponseEntity.ok().cacheControl(cache)
                    .contentType(MediaType.IMAGE_JPEG).contentLength(bytes.size.toLong())
                    .body(ByteArrayResource(bytes))
            }
            // 디코딩이 안 되는 형식(TIFF 등)은 원본으로 떨어진다.
        }

        return ResponseEntity.ok().cacheControl(cache)
            .contentType(MediaType.parseMediaType(mime))
            .contentLength(Files.size(path))
            .body(FileSystemResource(path))
    }

    /** 가로 폭 기준 축소 JPEG. 원본이 더 작으면 그대로 다시 인코딩만 한다. */
    private fun thumbnail(path: Path, width: Int): ByteArray? {
        val src = runCatching { Files.newInputStream(path).use { ImageIO.read(it) } }.getOrNull() ?: return null
        val targetW = minOf(width, src.width)
        val targetH = maxOf(1, (src.height.toLong() * targetW / src.width).toInt())

        val out = BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.color = java.awt.Color.WHITE
            g.fillRect(0, 0, targetW, targetH)
            g.drawImage(src, 0, 0, targetW, targetH, null)
        } finally {
            g.dispose()
        }
        val bos = ByteArrayOutputStream()
        ImageIO.write(out, "jpg", bos)
        return bos.toByteArray()
    }
}
