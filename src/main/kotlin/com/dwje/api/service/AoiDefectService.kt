package com.dwje.api.service

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.exception.ResourceNotFoundException
import com.dwje.api.common.exception.UnauthenticatedException
import com.dwje.api.common.response.PageMeta
import com.dwje.api.common.security.JwtTokenProvider
import com.dwje.api.common.util.DataField
import com.dwje.api.common.util.DateUtils
import com.dwje.api.common.util.MenuId
import com.dwje.api.common.util.PageRequestParam
import com.dwje.api.config.AppProperties
import com.dwje.api.repository.AoiDefectRepository
import com.dwje.api.repository.AoiImageRow
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * AOI 불량 상세·이미지 서비스 (요구 9)
 *
 * ## 무엇을 하는가
 * MES 라벨 이력에서 AOI 설비가 불량으로 찍은 건을 **id·날짜로 분류**해 목록·상세로 내고,
 * NAS 에 있는 불량 사진을 **경로로 찾아** 프록시 URL 로 돌려준다.
 *
 * ## 이미지 URL 에 서명 토큰을 붙이는 이유
 * `<img src>` 는 Authorization 헤더를 못 보낸다. Access Token 을 URL 에 넣으면 세션 전체가
 * 접속 로그에 남으므로, 이미지 한 장만 여는 짧은 토큰([JwtTokenProvider.createFileToken])을 붙인다.
 *
 * ## NAS 경로 규칙은 아직 없다
 * 발주자 문서가 오기 전이라 매핑 테이블(`ax.tb_aoi_defect_image`)을 읽는 구조로 둔다.
 * 규칙이 "경로 조합"으로 확정되면 [AoiDefectRepository.findImages] 자리에서 조합하면 된다.
 */
@Service
class AoiDefectService(
    private val repository: AoiDefectRepository,
    private val authorizationService: AuthorizationService,
    private val jwtTokenProvider: JwtTokenProvider,
    private val appProperties: AppProperties
) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val IMAGE_URL_PREFIX = "/api/v1/files/aoi-images/"
        /** 마스킹 대상 — 수량 3종은 qty, 금형은 mold 권한을 따른다. */
        private val MASK_FIELDS = mapOf(
            "okQty" to DataField.QTY, "ngQty" to DataField.QTY, "sampleQty" to DataField.QTY,
            "moldCd" to DataField.MOLD, "cavity" to DataField.MOLD
        )
        private val IMAGE_EXT = mapOf(
            "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png",
            "bmp" to "image/bmp", "gif" to "image/gif", "webp" to "image/webp", "tif" to "image/tiff", "tiff" to "image/tiff"
        )

        /**
         * defectId(`plant-wc-lot-serial`) 를 라벨 PK 로 나눈다.
         *
         * 작업장 코드에 `-` 가 들어갈 수 있어 **앞에서 plant 하나, 뒤에서 lot·serial 둘**을 떼고
         * 남은 가운데를 wc 로 본다.
         */
        fun splitDefectId(defectId: String): DefectKey? {
            val parts = defectId.trim().split('-')
            if (parts.size < 4 || parts.any { it.isBlank() }) return null
            return DefectKey(
                plantCd = parts.first(),
                wcCd = parts.subList(1, parts.size - 2).joinToString("-"),
                lotNo = parts[parts.size - 2],
                serialNo = parts.last()
            )
        }
    }

    data class DefectKey(val plantCd: String, val wcCd: String, val lotNo: String, val serialNo: String)

    /** 불량 목록 — 기간 필수(기본 최근 7일), 설비·불량유형·LOT·공정 필터. */
    fun getDefects(
        from: String?, to: String?, eqptCd: String?, defectTypeCd: String?, lotNo: String?, processId: String?,
        page: Int?, size: Int?
    ): Triple<Map<String, Any?>, PageMeta, com.dwje.api.common.util.MaskingSupport> {
        val (_, mask) = authorizationService.guard(MenuId.QC_AOI)
        val plantCd = appProperties.defaultPlantCd
        val (fromDate, toDate) = DateUtils.periodOf(from, to, defaultDays = 7)

        val paging = PageRequestParam.of(page, size)
        val total = repository.countDefects(plantCd, fromDate, toDate, eqptCd, defectTypeCd, lotNo, processId)
        val rows = repository.findDefects(
            plantCd, fromDate, toDate, eqptCd, defectTypeCd, lotNo, processId, paging.limit, paging.offset
        )
        val imageCnt = repository.countImages(rows.mapNotNull { it["defectId"] as String? })

        val items = rows.map { row ->
            val m = row.toMutableMap()
            mask.applyTo(m, MASK_FIELDS)
            m["imageCnt"] = imageCnt[row["defectId"]] ?: 0
            m
        }
        val data = mapOf(
            "from" to fromDate.format(DateUtils.DATE),
            "to" to toDate.format(DateUtils.DATE),
            "items" to items
        )
        return Triple(data, PageMeta.of(paging.page, paging.size, total), mask)
    }

    /** 불량 상세 + 불량 유형 내역 + 이미지(프록시 URL). */
    fun getDefect(defectId: String): Pair<Map<String, Any?>, com.dwje.api.common.util.MaskingSupport> {
        val (principal, mask) = authorizationService.guard(MenuId.QC_AOI)
        val key = splitDefectId(defectId)
            ?: throw InvalidParameterException("불량 ID 형식이 올바르지 않습니다. plant-wc-lot-serial 이어야 합니다. [$defectId]", "defectId")

        val row = repository.findDefect(key.plantCd, key.wcCd, key.lotNo, key.serialNo)
            ?: throw ResourceNotFoundException("AOI 불량을 찾을 수 없습니다. [$defectId]")
        val breakdown = repository.findDefectBreakdown(key.plantCd, key.wcCd, key.lotNo, key.serialNo)
        val images = repository.findImages(row["defectId"] as String)

        val ttl = appProperties.nas.imageUrlTtlSec
        val detail = row.toMutableMap()
        mask.applyTo(detail, MASK_FIELDS)
        return detail + mapOf(
            "defects" to breakdown,
            "imageCnt" to images.size,
            "imageUrlTtlSec" to ttl,
            "images" to images.map { img ->
                mapOf(
                    "imageId" to img.imageId,
                    "seq" to img.seq,
                    "defectCd" to img.defectCd,
                    "nasPath" to img.nasPath,
                    "capturedAt" to img.capturedAt,
                    "sizeBytes" to img.sizeBytes,
                    "available" to resolveNasFile(img).let { it != null && Files.isRegularFile(it) },
                    "url" to imageUrl(img.imageId, principal.userId, ttl),
                    "thumbUrl" to imageUrl(img.imageId, principal.userId, ttl) + "&w=160"
                )
            }
        ) to mask
    }

    /** 화면 필터용 AOI 설비 목록 */
    fun getEquipments(): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.QC_AOI)
        return mapOf("items" to repository.findAoiEquipments(appProperties.defaultPlantCd))
    }

    /**
     * 이미지 프록시 — 토큰을 검증하고 NAS 파일 경로와 MIME 을 돌려준다.
     *
     * 화이트리스트 경로라 [com.dwje.api.common.security.UserContext] 가 비어 있다. 토큰이 곧 인증이다.
     */
    fun openImage(imageId: Long, token: String?): Pair<Path, String> {
        if (token.isNullOrBlank()) throw UnauthenticatedException("이미지 접근 토큰이 없습니다.")
        val actor = jwtTokenProvider.verifyFileToken(token, imageId.toString())

        val img = repository.findImage(imageId) ?: throw ResourceNotFoundException("이미지를 찾을 수 없습니다. [imageId=$imageId]")
        val path = resolveNasFile(img)
            ?: run {
                log.warn("NAS 경로가 루트 밖을 가리켜 거절 imageId={} path={} actor={}", imageId, img.nasPath, actor)
                throw ResourceNotFoundException("이미지 경로가 허용 범위 밖입니다.")
            }
        if (!Files.isRegularFile(path)) {
            throw ResourceNotFoundException("NAS 에 이미지 파일이 없습니다. [${img.nasPath}]")
        }
        val ext = path.fileName.toString().substringAfterLast('.', "").lowercase()
        return path to (IMAGE_EXT[ext] ?: "application/octet-stream")
    }

    // ---------------------------------------------------------------------------------

    private fun imageUrl(imageId: Long, actor: String, ttl: Long): String =
        IMAGE_URL_PREFIX + imageId + "?token=" + jwtTokenProvider.createFileToken(imageId.toString(), actor, ttl)

    /**
     * NAS 경로를 루트 아래 절대 경로로 푼다. 절대 경로면 루트 아래인지만 확인하고,
     * 상대 경로면 루트에 붙인다. 루트 밖(`..` 포함)이면 null.
     */
    private fun resolveNasFile(img: AoiImageRow): Path? {
        val root = Paths.get(appProperties.nas.aoiRoot).toAbsolutePath().normalize()
        val raw = img.nasPath.trim().replace('\\', '/')
        val candidate = Paths.get(raw)
        val resolved = (if (candidate.isAbsolute) candidate else root.resolve(raw)).normalize()
        return if (resolved.startsWith(root)) resolved else null
    }
}
